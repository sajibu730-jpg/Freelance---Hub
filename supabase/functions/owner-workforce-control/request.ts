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
