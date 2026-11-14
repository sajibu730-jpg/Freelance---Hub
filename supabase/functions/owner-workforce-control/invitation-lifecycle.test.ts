import {
  dispatchWorkforceInvitation,
  type AuthInvitationAdmin,
  type InvitationRepository,
  type WorkforceInvitation,
} from "./invitation-lifecycle.ts";

const invitation: WorkforceInvitation = {
  id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1",
  email: "staff@example.com",
  status: "dispatching",
  auth_user_id: null,
};

function repository(overrides: Partial<InvitationRepository> = {}) {
  const calls = { claim: 0, sent: 0, review: 0 };
  const repo: InvitationRepository = {
    async claimForDispatch() {
      calls.claim++;
      return { claimed: true, invitation: { ...invitation } };
    },
    async recordSent() {
      calls.sent++;
      return { ok: true };
    },
    async markNeedsReview() {
      calls.review++;
      return { ok: true };
    },
    ...overrides,
  };
  return { repo, calls };
}

Deno.test("successful invite is finalized once", async () => {
  const { repo, calls } = repository();
  const auth: AuthInvitationAdmin = {
    async inviteUserByEmail(email) {
      if (email !== invitation.email) throw new Error("unexpected email");
      return { userId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2", error: null };
    },
  };
  const result = await dispatchWorkforceInvitation(invitation.id, repo, auth);
  if (result.outcome !== "sent" || calls.sent !== 1 || calls.review !== 0) {
    throw new Error("successful invitation was not finalized");
  }
});

Deno.test("a non-claimed invitation never calls Auth", async () => {
  const { repo } = repository({
    async claimForDispatch() {
      return {
        claimed: false,
        invitation: { ...invitation, status: "dispatching" },
      };
    },
  });
  let authCalls = 0;
  const auth: AuthInvitationAdmin = {
    async inviteUserByEmail() {
      authCalls++;
      return { userId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2", error: null };
    },
  };
  const result = await dispatchWorkforceInvitation(invitation.id, repo, auth);
  if (result.outcome !== "not_claimed" || authCalls !== 0) {
    throw new Error("already-claimed invitation was dispatched again");
  }
});

Deno.test("Auth API error is quarantined and never auto-retried", async () => {
  const { repo, calls } = repository();
  let authCalls = 0;
  const auth: AuthInvitationAdmin = {
    async inviteUserByEmail() {
      authCalls++;
      return { userId: null, error: new Error("Auth response uncertain") };
    },
  };
  const result = await dispatchWorkforceInvitation(invitation.id, repo, auth);
  if (result.outcome !== "reconciliation_required"
      || calls.review !== 1 || authCalls !== 1) {
    throw new Error("Auth error did not enter manual reconciliation");
  }
});

Deno.test("network exception is quarantined without retry", async () => {
  const { repo, calls } = repository();
  let authCalls = 0;
  const auth: AuthInvitationAdmin = {
    async inviteUserByEmail() {
      authCalls++;
      throw new Error("network dropped after request");
    },
  };
  const result = await dispatchWorkforceInvitation(invitation.id, repo, auth);
  if (result.outcome !== "reconciliation_required"
      || calls.review !== 1 || authCalls !== 1) {
    throw new Error("ambiguous network result was retried");
  }
});

Deno.test("database finalization failure preserves Auth user for reconciliation", async () => {
  const expectedUserId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2";
  let preservedUserId: string | undefined;
  const { repo, calls } = repository({
    async recordSent() {
      calls.sent++;
      return { ok: false, error: "database unavailable" };
    },
    async markNeedsReview(_id, reason, authUserId) {
      calls.review++;
      if (reason !== "record_sent_failed") throw new Error("wrong review reason");
      preservedUserId = authUserId;
      return { ok: true };
    },
  });
  const auth: AuthInvitationAdmin = {
    async inviteUserByEmail() {
      return { userId: expectedUserId, error: null };
    },
  };
  const result = await dispatchWorkforceInvitation(invitation.id, repo, auth);
  if (result.outcome !== "reconciliation_required"
      || result.authUserId !== expectedUserId
      || preservedUserId !== expectedUserId
      || calls.sent !== 1 || calls.review !== 1) {
    throw new Error("Auth-created user ID was not preserved for reconciliation");
  }
});
