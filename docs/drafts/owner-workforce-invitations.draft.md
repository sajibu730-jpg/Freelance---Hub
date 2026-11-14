# Owner Workforce Invitation Lifecycle (Draft)

**Status: design and isolated orchestration tests only.** This is not deployed, does not send email, does not create Auth users, and must not be connected to Production without explicit Owner approval.

## Why this is a separate workflow

Supabase Auth Admin `inviteUserByEmail()` is a trusted-server action that creates an unconfirmed Auth user and sends an invitation email. Email delivery and PostgreSQL cannot participate in one atomic transaction. A process can therefore lose its response after Auth has acted, or fail to persist the returned Auth user ID. Retrying blindly could duplicate invitations; deleting the Auth user could destroy an account after the invite was accepted.

## Required lifecycle

`prepared -> dispatching -> sent -> accepted`

Exceptional states:

- `needs_review`: Auth returned an error, threw an exception, or the database could not persist the successful Auth response. No automatic retry is permitted.
- `cancelled`: the Owner has cancelled the workforce invitation. Cancellation prevents workforce membership activation; it does not guarantee that an already delivered Auth link is invalidated.

## Dispatch protocol

1. Owner AAL2 is verified by the Edge Function after Supabase Auth `getUser(token)`. Role, normalized email, project scopes, and request UUID are validated server-side.
2. A durable invitation intent is inserted with a unique request UUID and normalized email. No Auth action happens in this step.
3. A database compare-and-set claims only `prepared -> dispatching`. Concurrent requests cannot both claim the same invitation.
4. Only the request that successfully claimed the invitation calls Auth Admin `inviteUserByEmail()` from the Edge Function. The secret/service-role credential is never sent to a browser or Android client.
5. On a successful Auth response, persist the returned Auth user UUID and transition to `sent`.
6. If Auth returns an error, the request throws, or final persistence fails, quarantine the invitation as `needs_review` (or leave it `dispatching` if even quarantine persistence fails). Never call Auth again automatically for that invitation.
7. An Owner-only reconciliation action inspects the Auth user by exact email and trusted Auth-side timestamps/state. It may record the known Auth user UUID and mark `sent`, or close the invitation as failed/cancelled only after verifying that no invite was created. Do not infer success from user-editable `user_metadata`; do not delete an Auth user as compensation.

## Acceptance

The invitee must complete the Supabase invitation flow, sign in with a verified Auth session, and match the invitation's exact normalized email and stored Auth user UUID. Only then may a server-side transaction create the workforce membership and mark the invitation `accepted`, writing an audit event atomically. A client-supplied role, scope, user ID, or metadata claim is never trusted.

## Cancellation and compensation limits

- A cancellation after email delivery blocks workforce access but cannot promise that the external email or Auth link has been recalled.
- A partially created Auth account is not automatically deleted. It may already have been accepted or may contain user data.
- If delivery or persistence is uncertain, pause and reconcile with the Owner. Do not resend, delete, or broaden permissions automatically.
- Resend/reissue is a distinct Owner-approved action with a new idempotency key and an explicit check of the existing Auth account state.

## Current code

- `supabase/functions/owner-workforce-control/invitation-lifecycle.ts` contains a side-effect-injected dispatch coordinator.
- Its Deno tests prove one-time claim behavior, no automatic retry on ambiguous Auth outcomes, and preservation of the returned Auth UUID when database finalization fails.
- `owner_workforce_invitations` and `owner_workforce_invitation_events` are draft tables with RLS enabled; API roles have no direct audit-table access.
- Server-only RPC drafts cover prepare, one-time dispatch claim, sent/reconciliation recording, verified-email acceptance, cancellation, and bounded Owner listing. The PostgreSQL workflow tests these transitions in a disposable database.
- The coordinator is wired into the undeployed `owner-workforce-control` Edge Function through server-side Supabase RPC and Auth Admin adapters. Actions include Owner-AAL2 `invite_member`, `list_invitations`, `cancel_invitation`, and `reconcile_invitation`, plus verified-session `accept_invitation`.
- The Owner Dashboard now contains invitation and reconciliation controls, but `WORKFORCE_API_ENABLED=false` keeps every Workforce action disabled. The PR gate asserts the Dashboard activation boundary; Deno tests assert the server flag fails closed for missing, false, or malformed values.
- The draft invitation table and atomic prepare/claim/record/review/accept/cancel/list RPCs now exist and are covered by disposable PostgreSQL assertions. The Owner reconciliation UI and Edge Function adapters are present in the draft. Staging Auth integration tests, final security review, deployment, and explicit Owner approval remain pending.

## Activation gate

Before deployment: implement invitation table and atomic RPCs; run disposable PostgreSQL tests; wire Owner AAL2 Edge Function actions; test against a disposable Supabase Auth project with email delivery stubbed or disabled; verify accepted, duplicate, expired, cancelled, timeout, and database-outage paths; review Supabase Advisors; and obtain explicit Owner approval. No Production schema, Auth user, email, or deployment is changed by this draft.
