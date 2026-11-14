export type WorkforceRequestBody = Record<string, unknown>;

export type ParsedWorkforceBody =
  | { ok: true; body: WorkforceRequestBody }
  | { ok: false; error: string };

export function parseWorkforceBody(raw: string): ParsedWorkforceBody {
  if (raw.length > 16_000) return { ok: false, error: "Request body is too large." };
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { ok: false, error: "Invalid JSON body." };
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    return { ok: false, error: "A JSON object is required." };
  }
  return { ok: true, body: parsed as WorkforceRequestBody };
}

export function isUuid(value: unknown): value is string {
  return typeof value === "string"
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

export function rpcErrorStatus(error: { code?: string } | null): number {
  switch (error?.code) {
    case "42501": return 403;
    case "P0002": return 404;
    case "22023": return 400;
    case "23505": return 409;
    default: return 500;
  }
}


export type TaskListRole = "project_manager" | "developer" | "qa" | "support";

export function taskListPolicy(role: TaskListRole, userId: string): {
  assigneeId: string | null;
  taskKinds: readonly string[] | null;
} {
  if (role === "developer" || role === "qa") {
    return { assigneeId: userId, taskKinds: null };
  }
  if (role === "support") {
    return { assigneeId: null, taskKinds: ["support", "incident"] };
  }
  return { assigneeId: null, taskKinds: null };
}


export type WorkforceRole = "project_manager" | "developer" | "qa" | "support";
export type WorkforceMemberStatus = "active" | "suspended";

export function parseWorkforceRole(value: unknown): WorkforceRole | null {
  return value === "project_manager" || value === "developer" || value === "qa" || value === "support"
    ? value
    : null;
}

export function parseProjectScopes(value: unknown): string[] | null {
  if (!Array.isArray(value) || value.length < 1 || value.length > 50) return null;
  if (!value.every((item) => typeof item === "string"
      && item.length >= 1 && item.length <= 100
      && item.trim() === item)) return null;
  const scopes = value as string[];
  if (new Set(scopes).size !== scopes.length) return null;
  return scopes;
}

export function parseMemberStatus(value: unknown): WorkforceMemberStatus | null {
  return value === "active" || value === "suspended" ? value : null;
}
