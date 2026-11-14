import {
  authorizePrimaryOwner,
  extractBearerToken,
  type AuthLookup,
  type OwnerLookup,
} from "./workforce-auth.ts";

function jwt(payload: Record<string, unknown>): string {
  const encode = (value: unknown) => {
    const bytes = new TextEncoder().encode(JSON.stringify(value));
    let binary = "";
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
  };
  return `${encode({ alg: "HS256", typ: "JWT" })}.${encode(payload)}.signature`;
}

function clients(options: {
  user?: { id: string } | null;
  authError?: boolean;
  owner?: { role?: string; is_active?: boolean } | null;
  ownerError?: boolean;
}) {
  const authClient: AuthLookup = {
    auth: {
      getUser: async () => ({
        data: { user: options.user === undefined ? { id: "owner-1" } : options.user },
        error: options.authError ? new Error("invalid") : null,
      }),
    },
  };
  const adminClient: OwnerLookup = {
    from: (table: string) => {
      if (table !== "app_admins") throw new Error("Unexpected table");
      return {
        select: (columns: string) => {
          if (columns !== "role,is_active") throw new Error("Unexpected columns");
          return {
            eq: (column: string, value: string) => {
              if (column !== "user_id" || value !== "owner-1") throw new Error("Unexpected owner lookup");
              return {
                maybeSingle: async () => ({
                  data: options.owner === undefined ? { role: "owner", is_active: true } : options.owner,
                  error: options.ownerError ? new Error("db unavailable") : null,
                }),
              };
            },
          };
        },
      };
    },
  };
  return { authClient, adminClient };
}

Deno.test("extracts only a single Bearer token", () => {
  if (extractBearerToken("Bearer abc.def.ghi") !== "abc.def.ghi") throw new Error("valid bearer rejected");
  if (extractBearerToken("Basic abc") !== null) throw new Error("basic auth accepted");
  if (extractBearerToken("Bearer a b") !== null) throw new Error("multiple token parts accepted");
  if (extractBearerToken("Bearer " + "x".repeat(12_001)) !== null) throw new Error("oversized header accepted");
});

Deno.test("allows only verified primary Owner with AAL2", async () => {
  const token = jwt({ sub: "owner-1", aal: "aal2" });
  const { authClient, adminClient } = clients({});
  const result = await authorizePrimaryOwner(token, authClient, adminClient);
  if (!result.ok || result.user.id !== "owner-1") throw new Error("valid owner was rejected");
});

Deno.test("rejects invalid Auth session", async () => {
  const { authClient, adminClient } = clients({ authError: true });
  const result = await authorizePrimaryOwner(jwt({ sub: "owner-1", aal: "aal2" }), authClient, adminClient);
  if (result.ok || result.status !== 401) throw new Error("invalid session was accepted");
});

Deno.test("rejects subject mismatch", async () => {
  const { authClient, adminClient } = clients({});
  const result = await authorizePrimaryOwner(jwt({ sub: "other-user", aal: "aal2" }), authClient, adminClient);
  if (result.ok || result.status !== 401) throw new Error("mismatched subject was accepted");
});

Deno.test("rejects AAL1 even for primary Owner", async () => {
  const { authClient, adminClient } = clients({});
  const result = await authorizePrimaryOwner(jwt({ sub: "owner-1", aal: "aal1" }), authClient, adminClient);
  if (result.ok || result.status !== 403) throw new Error("AAL1 owner was accepted");
});

Deno.test("rejects staff and inactive Owner accounts", async () => {
  for (const owner of [{ role: "developer", is_active: true }, { role: "owner", is_active: false }, null]) {
    const { authClient, adminClient } = clients({ owner });
    const result = await authorizePrimaryOwner(jwt({ sub: "owner-1", aal: "aal2" }), authClient, adminClient);
    if (result.ok || result.status !== 403) throw new Error("non-owner or inactive account was accepted");
  }
});

Deno.test("fails closed when Owner lookup errors", async () => {
  const { authClient, adminClient } = clients({ ownerError: true });
  const result = await authorizePrimaryOwner(jwt({ sub: "owner-1", aal: "aal2" }), authClient, adminClient);
  if (result.ok || result.status !== 403) throw new Error("database error did not fail closed");
});
