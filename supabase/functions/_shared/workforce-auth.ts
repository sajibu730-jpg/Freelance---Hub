/**
 * Server-only authorization primitives for the future Owner Workforce Edge Function.
 *
 * This module is intentionally not wired to a deployed function yet. The workforce
 * schema and atomic mutation RPCs must be staged and verified before activation.
 *
 * Important: the caller must first validate the bearer token with Supabase Auth
 * getUser(token). Only then may the AAL claim decoded here be trusted.
 */
export type WorkforceUser = { id: string; email?: string | null };

export type AuthLookup = {
  auth: {
    getUser(token: string): Promise<{
      data: { user: WorkforceUser | null };
      error: unknown | null;
    }>;
  };
};

export type OwnerLookup = {
  from(table: string): {
    select(columns: string): {
      eq(column: string, value: string): {
        maybeSingle(): Promise<{
          data: { role?: string | null; is_active?: boolean | null } | null;
          error: unknown | null;
        }>;
      };
    };
  };
};

export type OwnerAuthorization =
  | { ok: true; user: WorkforceUser; aal: "aal2" }
  | { ok: false; status: 401 | 403; error: string };

export function extractBearerToken(header: string | null): string | null {
  if (!header || header.length > 12_000) return null;
  const match = header.match(/^Bearer\s+([^\s]+)$/i);
  return match?.[1] ?? null;
}

function decodeJwtPayload(token: string): Record<string, unknown> | null {
  const parts = token.split(".");
  if (parts.length !== 3 || !parts[1]) return null;
  try {
    const base64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
    const binary = atob(padded);
    const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
    const payload = new TextDecoder().decode(bytes);
    const parsed: unknown = JSON.parse(payload);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : null;
  } catch {
    return null;
  }
}

/**
 * The token must already have passed auth.getUser(token). We additionally bind
 * the signed token subject to the verified Auth user ID before accepting AAL2.
 */
export async function authorizePrimaryOwner(
  token: string,
  authClient: AuthLookup,
  adminClient: OwnerLookup,
): Promise<OwnerAuthorization> {
  const { data, error } = await authClient.auth.getUser(token);
  const user = data?.user;
  if (error || !user?.id) {
    return { ok: false, status: 401, error: "Session invalid or expired." };
  }

  const claims = decodeJwtPayload(token);
  if (!claims || claims.sub !== user.id) {
    return { ok: false, status: 401, error: "Session claims could not be verified." };
  }
  if (claims.aal !== "aal2") {
    return { ok: false, status: 403, error: "Owner action requires a verified AAL2 session." };
  }

  const { data: owner, error: ownerError } = await adminClient
    .from("app_admins")
    .select("role,is_active")
    .eq("user_id", user.id)
    .maybeSingle();

  if (ownerError) {
    return { ok: false, status: 403, error: "Owner authorization could not be confirmed." };
  }
  if (owner?.role !== "owner" || owner?.is_active !== true) {
    return { ok: false, status: 403, error: "Only the primary active Owner can perform this action." };
  }

  return { ok: true, user, aal: "aal2" };
}

export type WorkforceRole = "project_manager" | "developer" | "qa" | "support";
export type WorkforceAction =
  | "list_tasks"
  | "create_task"
  | "assign_task"
  | "reassign_task"
  | "update_task_status"
  | "append_task_evidence"
  | "submit_release_proposal"
  | "create_support_task";

export type StaffMembershipLookup = {
  from(table: string): {
    select(columns: string): {
      eq(column: string, value: string): {
        maybeSingle(): Promise<{
          data: {
            role?: string | null;
            status?: string | null;
            project_scopes?: string[] | null;
          } | null;
          error: unknown | null;
        }>;
      };
    };
  };
};

const STAFF_ACTIONS: Record<WorkforceRole, readonly WorkforceAction[]> = {
  project_manager: ["list_tasks", "create_task", "assign_task", "reassign_task"],
  developer: ["list_tasks", "update_task_status", "append_task_evidence", "submit_release_proposal"],
  qa: ["list_tasks", "update_task_status", "append_task_evidence", "submit_release_proposal"],
  support: ["list_tasks", "create_support_task"],
};

export type StaffAuthorization =
  | { ok: true; userId: string; role: WorkforceRole; projectKey: string }
  | { ok: false; status: 403; error: string };

/**
 * Call only after auth.getUser(token) has verified the bearer token.
 * userId must come from that verified Auth response, never from request JSON.
 */
export async function authorizeStaffTaskAction(
  userId: string,
  projectKey: string,
  action: WorkforceAction,
  membershipClient: StaffMembershipLookup,
): Promise<StaffAuthorization> {
  if (!userId || !projectKey || projectKey.length > 100 || projectKey.trim() !== projectKey) {
    return { ok: false, status: 403, error: "Invalid workforce scope." };
  }

  const { data: member, error } = await membershipClient
    .from("owner_workforce_members")
    .select("role,status,project_scopes")
    .eq("user_id", userId)
    .maybeSingle();

  if (error || !member || member.status !== "active") {
    return { ok: false, status: 403, error: "Active workforce membership is required." };
  }

  const role = member.role;
  if (role !== "project_manager" && role !== "developer" && role !== "qa" && role !== "support") {
    return { ok: false, status: 403, error: "Workforce role is not recognized." };
  }

  if (!Array.isArray(member.project_scopes) || !member.project_scopes.includes(projectKey)) {
    return { ok: false, status: 403, error: "This project is outside your assigned scope." };
  }

  if (!STAFF_ACTIONS[role].includes(action)) {
    return { ok: false, status: 403, error: "Your workforce role cannot perform this action." };
  }

  return { ok: true, userId, role, projectKey };
}

export type AssignedTaskMutation = "update_task_status" | "append_task_evidence";

/**
 * Enforce row ownership after the caller has fetched the task from the database.
 * Managers need separate review/assignment actions; Developer and QA evidence
 * mutations are allowed only for the task currently assigned to that user.
 */
export function authorizeAssignedTaskMutation(
  actor: StaffAuthorization,
  action: AssignedTaskMutation,
  task: { project_key?: string | null; assignee_id?: string | null },
): { ok: true } | { ok: false; status: 403; error: string } {
  if (!actor.ok || (actor.role !== "developer" && actor.role !== "qa")) {
    return { ok: false, status: 403, error: "An assigned Developer or QA member is required." };
  }
  if (task.project_key !== actor.projectKey || task.assignee_id !== actor.userId) {
    return { ok: false, status: 403, error: "You can update only a task assigned to your account in this project." };
  }
  if (action !== "update_task_status" && action !== "append_task_evidence") {
    return { ok: false, status: 403, error: "Task action is not permitted." };
  }
  return { ok: true };
}
