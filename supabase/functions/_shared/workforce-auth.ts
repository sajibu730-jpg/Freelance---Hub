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
