import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import { dispatchWorkforceInvitation, type InvitationStatus } from "./invitation-lifecycle.ts";
import {
  authorizeAssignedTaskMutation,
  authorizePrimaryOwner,
  authorizeStaffTaskAction,
  extractBearerToken,
  type OwnerLookup,
  type StaffMembershipLookup,
} from "../_shared/workforce-auth.ts";
import {
  isUuid,
  parseWorkforceBody,
  rpcErrorStatus,
  taskListPolicy,
  parseWorkforceRole,
  parseProjectScopes,
  parseMemberStatus,
  parseInvitationEmail,
} from "./request.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

function text(value: unknown, max: number): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed.length > 0 && trimmed.length <= max ? trimmed : null;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ success: false, error: "Method not allowed." }, 405);

  try {
    const token = extractBearerToken(req.headers.get("Authorization"));
    if (!token) return json({ success: false, error: "Sign in is required." }, 401);

    const parsed = parseWorkforceBody(await req.text());
    if (!parsed.ok) return json({ success: false, error: parsed.error }, 400);
    const body = parsed.body;

    const action = body.action;
    const projectKey = text(body.project_key, 100);
    const requestId = body.request_id;
    const noProjectActions = [
      "list_members", "list_member_events", "set_member", "set_member_status",
      "list_invitations", "invite_member", "accept_invitation",
      "cancel_invitation", "reconcile_invitation",
    ];
    if (!noProjectActions.includes(String(action)) && !projectKey) {
      return json({ success: false, error: "A valid project_key is required." }, 400);
    }
    const noRequestIdActions = [
      "list_tasks", "list_members", "list_member_events", "list_invitations",
      "accept_invitation", "cancel_invitation", "reconcile_invitation",
    ];
    if (!noRequestIdActions.includes(String(action)) && !isUuid(requestId)) {
      return json({ success: false, error: "A valid request_id is required for this action." }, 400);
    }

    const url = Deno.env.get("SUPABASE_URL");
    const anon = Deno.env.get("SUPABASE_ANON_KEY");
    const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!url || !anon || !service) {
      return json({ success: false, error: "Workforce service configuration is incomplete." }, 503);
    }

    const userClient = createClient(url, anon, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: authData, error: authError } = await userClient.auth.getUser(token);
    if (authError || !authData.user?.id) {
      return json({ success: false, error: "Session invalid or expired." }, 401);
    }

    // The actor identity is taken only from Auth getUser(token), never from request JSON.
    const actorId = authData.user.id;
    const admin = createClient(url, service, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    // Narrow the large generated Supabase client type to the small lookup contract.
    const membershipClient = admin as unknown as StaffMembershipLookup;
    const ownerClient = admin as unknown as OwnerLookup;

    if (action === "list_invitations") {
      const owner = await authorizePrimaryOwner(token, userClient, ownerClient);
      if (!owner.ok) return json({ success: false, error: owner.error }, owner.status);
      const { data, error } = await admin.rpc("owner_workforce_list_invitations", {
        p_actor_user_id: actorId,
        p_limit: 50,
      });
      if (error) {
        console.error("Workforce list_invitations RPC failed", error.code ?? "unknown");
        return json({ success: false, error: "Invitations could not be loaded." }, rpcErrorStatus(error));
      }
      return json({ success: true, invitations: data ?? [] });
    }

    if (action === "invite_member") {
      const owner = await authorizePrimaryOwner(token, userClient, ownerClient);
      if (!owner.ok) return json({ success: false, error: owner.error }, owner.status);

      const email = parseInvitationEmail(body.email);
      const role = parseWorkforceRole(body.role);
      const projectScopes = parseProjectScopes(body.project_scopes);
      if (!email || !role || !projectScopes) {
        return json({ success: false, error: "A valid email, workforce role, and 1 to 50 unique project scopes are required." }, 400);
      }

      const { data: prepared, error: prepareError } = await admin.rpc("owner_workforce_prepare_invitation", {
        p_actor_user_id: actorId,
        p_email: email,
        p_role: role,
        p_project_scopes: projectScopes,
        p_request_id: requestId,
      });
      if (prepareError) {
        console.error("Workforce prepare_invitation RPC failed", prepareError.code ?? "unknown");
        return json({ success: false, error: "Invitation could not be prepared." }, rpcErrorStatus(prepareError));
      }
      const invitationId = prepared?.id;
      if (!isUuid(invitationId)) {
        return json({ success: false, error: "Invitation was prepared but its identifier could not be confirmed." }, 500);
      }

      const dispatch = await dispatchWorkforceInvitation(
        invitationId,
        {
          async claimForDispatch(id) {
            const { data, error } = await admin.rpc("owner_workforce_claim_invitation_dispatch", {
              p_actor_user_id: actorId,
              p_invitation_id: id,
            });
            if (error) return { claimed: false, invitation: null, error: error.code ?? "claim_failed" };
            const row = data?.invitation;
            if (!row || !isUuid(row.id) || typeof row.email !== "string") {
              return { claimed: false, invitation: null, error: "invalid_claim_response" };
            }
            return {
              claimed: data.claimed === true,
              invitation: {
                id: row.id,
                email: row.email,
                status: row.status as InvitationStatus,
                auth_user_id: isUuid(row.auth_user_id) ? row.auth_user_id : null,
              },
            };
          },
          async recordSent(id, authUserId) {
            const { error } = await admin.rpc("owner_workforce_record_invitation_sent", {
              p_actor_user_id: actorId,
              p_invitation_id: id,
              p_auth_user_id: authUserId,
            });
            return { ok: !error, error: error?.code };
          },
          async markNeedsReview(id, reason, authUserId) {
            const { error } = await admin.rpc("owner_workforce_mark_invitation_review", {
              p_actor_user_id: actorId,
              p_invitation_id: id,
              p_reason: reason,
              p_auth_user_id: authUserId ?? null,
            });
            return { ok: !error };
          },
        },
        {
          async inviteUserByEmail(targetEmail) {
            const { data, error } = await admin.auth.admin.inviteUserByEmail(targetEmail);
            return { userId: data.user?.id ?? null, error };
          },
        },
      );

      if (dispatch.outcome === "sent") {
        return json({ success: true, outcome: "sent", invitation_id: invitationId, auth_user_id: dispatch.authUserId });
      }
      if (dispatch.outcome === "not_claimed") {
        return json({
          success: false,
          outcome: "not_claimed",
          invitation_id: invitationId,
          status: dispatch.status,
          error: "This invitation is already being processed. Do not send it again.",
        }, 409);
      }
      if (dispatch.outcome === "reconciliation_required") {
        return json({
          success: true,
          outcome: "needs_review",
          invitation_id: invitationId,
          reason: dispatch.reason,
          auth_user_id: dispatch.authUserId ?? null,
          message: "The Auth result is uncertain. The invitation is quarantined for Owner reconciliation; it will not be resent automatically.",
        }, 202);
      }
      return json({
        success: false,
        outcome: "claim_failed",
        invitation_id: invitationId,
        error: "The invitation dispatch claim could not be confirmed.",
      }, 500);
    }

    if (action === "accept_invitation") {
      const invitationId = body.invitation_id;
      if (!isUuid(invitationId)) return json({ success: false, error: "A valid invitation_id is required." }, 400);
      const { data, error } = await admin.rpc("owner_workforce_accept_invitation", {
        p_actor_user_id: actorId,
        p_invitation_id: invitationId,
      });
      if (error) {
        console.error("Workforce accept_invitation RPC failed", error.code ?? "unknown");
        return json({ success: false, error: "Invitation acceptance failed. Verify the invitation email first." }, rpcErrorStatus(error));
      }
      return json({ success: true, member: data });
    }

    if (action === "cancel_invitation") {
      const owner = await authorizePrimaryOwner(token, userClient, ownerClient);
      if (!owner.ok) return json({ success: false, error: owner.error }, owner.status);
      const invitationId = body.invitation_id;
      const reason = text(body.reason, 1000);
      if (!isUuid(invitationId) || !reason) {
        return json({ success: false, error: "A valid invitation_id and cancellation reason are required." }, 400);
      }
      const { data, error } = await admin.rpc("owner_workforce_cancel_invitation", {
        p_actor_user_id: actorId,
        p_invitation_id: invitationId,
        p_reason: reason,
      });
      if (error) {
        console.error("Workforce cancel_invitation RPC failed", error.code ?? "unknown");
        return json({ success: false, error: "Invitation could not be cancelled." }, rpcErrorStatus(error));
      }
      return json({ success: true, invitation: data, auth_account_deleted: false });
    }

    if (action === "reconcile_invitation") {
      const owner = await authorizePrimaryOwner(token, userClient, ownerClient);
      if (!owner.ok) return json({ success: false, error: owner.error }, owner.status);
      const invitationId = body.invitation_id;
      const resolution = body.resolution;
      if (!isUuid(invitationId)) return json({ success: false, error: "A valid invitation_id is required." }, 400);

      if (resolution === "confirm_sent") {
        const authUserId = body.auth_user_id;
        if (!isUuid(authUserId)) {
          return json({ success: false, error: "Confirming a sent invitation requires the verified Auth User UUID from the Owner's Auth review." }, 400);
        }
        const { data, error } = await admin.rpc("owner_workforce_record_invitation_sent", {
          p_actor_user_id: actorId,
          p_invitation_id: invitationId,
          p_auth_user_id: authUserId,
        });
        if (error) {
          console.error("Workforce invitation reconciliation failed", error.code ?? "unknown");
          return json({ success: false, error: "Auth user could not be matched to this invitation." }, rpcErrorStatus(error));
        }
        return json({ success: true, resolution: "sent", invitation: data });
      }

      if (resolution === "cancel") {
        const reason = text(body.reason, 1000);
        if (!reason) return json({ success: false, error: "A reconciliation reason is required." }, 400);
        const { data, error } = await admin.rpc("owner_workforce_cancel_invitation", {
          p_actor_user_id: actorId,
          p_invitation_id: invitationId,
          p_reason: reason,
        });
        if (error) {
          console.error("Workforce invitation cancellation failed", error.code ?? "unknown");
          return json({ success: false, error: "Invitation could not be cancelled." }, rpcErrorStatus(error));
        }
        return json({ success: true, resolution: "cancelled", invitation: data, auth_account_deleted: false });
      }

      return json({ success: false, error: "Resolution must be confirm_sent or cancel." }, 400);
    }

    if (action === "list_members") {
      const owner = await authorizePrimaryOwner(token, userClient, ownerClient);
      if (!owner.ok) return json({ success: false, error: owner.error }, owner.status);

      const { data, error } = await admin.from("owner_workforce_members")
        .select("user_id,role,status,project_scopes,invited_by,created_at,updated_at")
        .order("created_at", { ascending: false })
        .limit(100);
      if (error) {
        console.error("Workforce list_members query failed", error.code ?? "unknown");
        return json({ success: false, error: "Workforce members could not be loaded." }, 500);
      }
      return json({ success: true, members: data ?? [] });
    }

    if (action === "list_member_events") {
      const owner = await authorizePrimaryOwner(token, userClient, ownerClient);
      if (!owner.ok) return json({ success: false, error: owner.error }, owner.status);

      const memberUserId = body.member_user_id === undefined ? null : body.member_user_id;
      if (memberUserId !== null && !isUuid(memberUserId)) {
        return json({ success: false, error: "member_user_id must be a valid UUID when supplied." }, 400);
      }
      const limit = body.limit === undefined ? 50 : body.limit;
      if (typeof limit !== "number" || !Number.isInteger(limit) || limit < 1 || limit > 100) {
        return json({ success: false, error: "Audit limit must be an integer from 1 to 100." }, 400);
      }
      const { data, error } = await admin.rpc("owner_workforce_list_member_events", {
        p_actor_user_id: actorId,
        p_member_user_id: memberUserId,
        p_limit: limit,
      });
      if (error) {
        console.error("Workforce list_member_events RPC failed", error.code ?? "unknown");
        return json({ success: false, error: "Member audit history could not be loaded." }, rpcErrorStatus(error));
      }
      return json({ success: true, events: data ?? [] });
    }

    if (action === "set_member" || action === "set_member_status") {
      const owner = await authorizePrimaryOwner(token, userClient, ownerClient);
      if (!owner.ok) return json({ success: false, error: owner.error }, owner.status);

      const memberUserId = body.member_user_id;
      if (!isUuid(memberUserId)) {
        return json({ success: false, error: "A valid member_user_id is required." }, 400);
      }

      if (action === "set_member") {
        const role = parseWorkforceRole(body.role);
        const projectScopes = parseProjectScopes(body.project_scopes);
        if (!role || !projectScopes) {
          return json({ success: false, error: "A valid workforce role and 1 to 50 unique project scopes are required." }, 400);
        }
        const { data, error } = await admin.rpc("owner_workforce_set_member", {
          p_actor_user_id: actorId,
          p_member_user_id: memberUserId,
          p_role: role,
          p_project_scopes: projectScopes,
          p_request_id: requestId,
        });
        if (error) {
          console.error("Workforce set_member RPC failed", error.code ?? "unknown");
          return json({ success: false, error: "Workforce member could not be added or updated." }, rpcErrorStatus(error));
        }
        return json({ success: true, member: data });
      }

      const status = parseMemberStatus(body.status);
      if (!status) {
        return json({ success: false, error: "Member status must be active or suspended." }, 400);
      }
      const { data, error } = await admin.rpc("owner_workforce_set_member_status", {
        p_actor_user_id: actorId,
        p_member_user_id: memberUserId,
        p_new_status: status,
        p_request_id: requestId,
      });
      if (error) {
        console.error("Workforce set_member_status RPC failed", error.code ?? "unknown");
        return json({ success: false, error: "Workforce member status could not be changed." }, rpcErrorStatus(error));
      }
      return json({ success: true, member: data });
    }

    if (!projectKey) {
      return json({ success: false, error: "A valid project_key is required." }, 400);
    }
    const scopedProjectKey = projectKey;

    if (action === "list_tasks") {
      const authorization = await authorizeStaffTaskAction(
        actorId, scopedProjectKey, "list_tasks", membershipClient,
      );
      if (!authorization.ok) {
        return json({ success: false, error: authorization.error }, authorization.status);
      }

      // Read-only listing is constrained server-side by verified membership and role.
      let query = admin.from("owner_workforce_tasks")
        .select("id,project_key,title,description,task_kind,priority,status,created_by,assignee_id,due_at,blocked_reason,completion_evidence,completed_at,created_at,updated_at")
        .eq("project_key", scopedProjectKey)
        .order("created_at", { ascending: false })
        .limit(50);

      const policy = taskListPolicy(authorization.role, actorId);
      if (policy.assigneeId) {
        query = query.eq("assignee_id", policy.assigneeId);
      }
      if (policy.taskKinds) {
        query = query.in("task_kind", [...policy.taskKinds]);
      }

      const { data, error } = await query;
      if (error) {
        console.error("Workforce list_tasks query failed", error.code ?? "unknown");
        return json({ success: false, error: "Tasks could not be loaded." }, 500);
      }
      return json({ success: true, tasks: data ?? [] });
    }

    if (action === "create_task") {
      const title = text(body.title, 180);
      const description = typeof body.description === "string" ? body.description : "";
      const taskKind = body.task_kind;
      const priority = body.priority ?? "normal";
      const dueAt = body.due_at ?? null;
      if (!title || description.length > 8000
          || !["general", "support", "incident"].includes(String(taskKind))
          || !["low", "normal", "high", "urgent"].includes(String(priority))
          || (dueAt !== null && (typeof dueAt !== "string" || !Number.isFinite(Date.parse(dueAt))))) {
        return json({ success: false, error: "Task details are invalid." }, 400);
      }

      const membershipAction = taskKind === "general" ? "create_task" : "create_support_task";
      const authorization = await authorizeStaffTaskAction(actorId, scopedProjectKey, membershipAction, membershipClient);
      if (!authorization.ok) return json({ success: false, error: authorization.error }, authorization.status);

      const { data, error } = await admin.rpc("owner_workforce_create_task", {
        p_actor_user_id: actorId,
        p_project_key: scopedProjectKey,
        p_title: title,
        p_description: description,
        p_task_kind: taskKind,
        p_priority: priority,
        p_due_at: dueAt,
        p_request_id: requestId,
      });
      if (error) {
        console.error("Workforce create_task RPC failed", error.code ?? "unknown");
        return json({ success: false, error: "Task could not be created." }, rpcErrorStatus(error));
      }
      return json({ success: true, task: data });
    }

    if (action === "assign_task") {
      const taskId = body.task_id;
      const assigneeId = body.assignee_id;
      if (!isUuid(taskId) || !isUuid(assigneeId)) {
        return json({ success: false, error: "Valid task_id and assignee_id are required." }, 400);
      }
      const authorization = await authorizeStaffTaskAction(actorId, scopedProjectKey, "assign_task", membershipClient);
      if (!authorization.ok) return json({ success: false, error: authorization.error }, authorization.status);

      const { data, error } = await admin.rpc("owner_workforce_assign_task", {
        p_actor_user_id: actorId,
        p_task_id: taskId,
        p_assignee_id: assigneeId,
        p_request_id: requestId,
      });
      if (error) {
        console.error("Workforce assign_task RPC failed", error.code ?? "unknown");
        return json({ success: false, error: "Task could not be assigned." }, rpcErrorStatus(error));
      }
      return json({ success: true, task: data });
    }

    if (action === "update_task_status" || action === "append_task_evidence") {
      const taskId = body.task_id;
      if (!isUuid(taskId)) return json({ success: false, error: "A valid task_id is required." }, 400);

      const membershipAction = action === "update_task_status" ? "update_task_status" : "append_task_evidence";
      const authorization = await authorizeStaffTaskAction(actorId, scopedProjectKey, membershipAction, membershipClient);
      if (!authorization.ok) return json({ success: false, error: authorization.error }, authorization.status);

      const { data: task, error: taskError } = await admin.from("owner_workforce_tasks")
        .select("project_key,assignee_id").eq("id", taskId).maybeSingle();
      if (taskError) {
        console.error("Workforce task lookup failed", taskError.code ?? "unknown");
        return json({ success: false, error: "Task access could not be confirmed." }, 500);
      }
      if (!task) return json({ success: false, error: "Task not found." }, 404);

      const mutationAuthorization = authorizeAssignedTaskMutation(
        authorization, membershipAction, task,
      );
      if (!mutationAuthorization.ok) {
        return json({ success: false, error: mutationAuthorization.error }, mutationAuthorization.status);
      }

      if (action === "update_task_status") {
        const newStatus = body.new_status;
        const blockedReason = body.blocked_reason ?? null;
        const completionEvidence = body.completion_evidence ?? null;
        if (!["in_progress", "blocked", "in_review", "done"].includes(String(newStatus))
            || (blockedReason !== null && typeof blockedReason !== "string")
            || (completionEvidence !== null && typeof completionEvidence !== "string")) {
          return json({ success: false, error: "Task status details are invalid." }, 400);
        }
        const { data, error } = await admin.rpc("owner_workforce_update_task_status", {
          p_actor_user_id: actorId,
          p_task_id: taskId,
          p_new_status: newStatus,
          p_blocked_reason: blockedReason,
          p_completion_evidence: completionEvidence,
          p_request_id: requestId,
        });
        if (error) {
          console.error("Workforce update_task_status RPC failed", error.code ?? "unknown");
          return json({ success: false, error: "Task status could not be updated." }, rpcErrorStatus(error));
        }
        return json({ success: true, task: data });
      }

      const evidence = text(body.evidence, 4000);
      if (!evidence) return json({ success: false, error: "Evidence must contain 1 to 4000 characters." }, 400);
      const { data, error } = await admin.rpc("owner_workforce_append_task_evidence", {
        p_actor_user_id: actorId,
        p_task_id: taskId,
        p_evidence: evidence,
        p_request_id: requestId,
      });
      if (error) {
        console.error("Workforce append_task_evidence RPC failed", error.code ?? "unknown");
        return json({ success: false, error: "Evidence could not be saved." }, rpcErrorStatus(error));
      }
      return json({ success: true, task: data });
    }

    return json({ success: false, error: "Unsupported workforce action." }, 400);
  } catch (error) {
    console.error("Owner Workforce request failed", error instanceof Error ? error.message : "unknown");
    return json({ success: false, error: "Workforce request failed. Please try again." }, 500);
  }
});
