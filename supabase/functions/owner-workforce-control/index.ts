import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import {
  authorizeAssignedTaskMutation,
  authorizePrimaryOwner,
  authorizeStaffTaskAction,
  extractBearerToken,
  type OwnerLookup,
  type StaffMembershipLookup,
} from "../_shared/workforce-auth.ts";
import { isUuid, parseWorkforceBody, rpcErrorStatus, taskListPolicy } from "./request.ts";

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
    if (action !== "list_members" && !projectKey) {
      return json({ success: false, error: "A valid project_key is required." }, 400);
    }
    if (action !== "list_tasks" && action !== "list_members" && !isUuid(requestId)) {
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
