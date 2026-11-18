export type InvitationStatus =
  | "prepared"
  | "dispatching"
  | "sent"
  | "accepted"
  | "cancelled"
  | "needs_review";

export interface WorkforceInvitation {
  id: string;
  email: string;
  status: InvitationStatus;
  auth_user_id: string | null;
}

export interface InvitationRepository {
  /**
   * Atomically compare-and-set prepared -> dispatching.
   * A request that is already dispatching/sent/accepted must never dispatch email again.
   */
  claimForDispatch(invitationId: string): Promise<{
    claimed: boolean;
    invitation: WorkforceInvitation | null;
    error?: string;
  }>;
  recordSent(invitationId: string, authUserId: string): Promise<{ ok: boolean; error?: string }>;
  markNeedsReview(
    invitationId: string,
    reason: "auth_error" | "auth_exception" | "record_sent_failed",
    authUserId?: string,
  ): Promise<{ ok: boolean }>;
}

export interface AuthInvitationAdmin {
  inviteUserByEmail(email: string): Promise<{
    userId: string | null;
    error: unknown | null;
  }>;
}

export type DispatchResult =
  | { outcome: "sent"; authUserId: string }
  | { outcome: "not_claimed"; status: InvitationStatus | "missing" }
  | { outcome: "reconciliation_required"; reason: string; authUserId?: string }
  | { outcome: "claim_failed" };

/**
 * Dispatch exactly once after a durable database claim.
 *
 * Auth invitation delivery is an external side effect and cannot share a PostgreSQL
 * transaction. Any ambiguous Auth result or failed DB finalization is quarantined for
 * Owner reconciliation; it is never retried automatically.
 */
export async function dispatchWorkforceInvitation(
  invitationId: string,
  repository: InvitationRepository,
  authAdmin: AuthInvitationAdmin,
): Promise<DispatchResult> {
  const claim = await repository.claimForDispatch(invitationId);
  if (claim.error || !claim.invitation) return { outcome: "claim_failed" };
  if (!claim.claimed) {
    return { outcome: "not_claimed", status: claim.invitation.status };
  }

  const invitation = claim.invitation;
  if (invitation.status !== "dispatching") {
    return { outcome: "claim_failed" };
  }

  let authResult: { userId: string | null; error: unknown | null };
  try {
    authResult = await authAdmin.inviteUserByEmail(invitation.email);
  } catch {
    await repository.markNeedsReview(invitation.id, "auth_exception");
    return { outcome: "reconciliation_required", reason: "auth_exception" };
  }

  if (authResult.error || !authResult.userId) {
    await repository.markNeedsReview(invitation.id, "auth_error");
    return { outcome: "reconciliation_required", reason: "auth_error" };
  }

  const finalized = await repository.recordSent(invitation.id, authResult.userId);
  if (!finalized.ok) {
    // Preserve the Auth user ID where possible so an Owner can reconcile without
    // sending a second invitation or deleting an account that may already be active.
    await repository.markNeedsReview(invitation.id, "record_sent_failed", authResult.userId);
    return {
      outcome: "reconciliation_required",
      reason: "record_sent_failed",
      authUserId: authResult.userId,
    };
  }

  return { outcome: "sent", authUserId: authResult.userId };
}
