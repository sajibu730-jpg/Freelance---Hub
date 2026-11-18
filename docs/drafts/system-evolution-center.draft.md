# System Evolution Center — Draft Design

Status: proposal and dashboard UI draft only. This document is not an activation instruction.

## Non-negotiable boundary

- No Production deployment, Supabase mutation, Edge Function deployment, feature-flag change, Auth action, or external side effect is performed by this draft.
- All UI controls for saving/submitting proposals, approving/rejecting releases, and rollback remain disabled.
- A proposal, a passing CI run, or an AI recommendation is never equivalent to Owner release approval.
- The primary Owner is the only production approver. Approval requires a verified Supabase Auth identity, active primary Owner authorization, and AAL2.
- Approval is bound to one immutable source commit and one immutable build artifact. A new commit, changed artifact, changed migration, expired evidence, or changed risk assessment invalidates approval.

## Proposal record

A future proposal record should include:
- UUID and idempotency key;
- title, description, source (Owner, AI, or staff), proposer identity, and timestamps;
- change category: remote configuration, backend/database, Android, dashboard/web, or external integration;
- affected components and critical user flows;
- risk level, privacy/security impact, cost/operational impact;
- exact source repository, branch, commit SHA, review reference, and artifact digest;
- required checks, staging evidence, recovery plan, and current lifecycle state.

Never accept privileged actor IDs, role names, deployment targets, shell commands, SQL, arbitrary URLs, or secret values from proposal payloads.

## Lifecycle

`draft -> submitted -> checks_running -> staging_passed -> owner_approved -> production_deployed -> monitored`

Any failed, missing, stale, or inconclusive mandatory check transitions the candidate to `blocked`. Rejected candidates remain immutable history. A changed candidate must be resubmitted and revalidated. No automatic retry may turn a failed or uncertain deployment into a success state.

## Required gates

1. **Source integrity:** immutable commit, reviewed diff, dependency lock, secret scan, and artifact digest.
2. **Build and tests:** Android build when native code changes; dashboard syntax/safety checks; Deno/unit/integration tests; critical-flow regression.
3. **Database/backend:** migration static analysis and disposable PostgreSQL tests; disposable Supabase Auth/PostgREST/RLS/Edge Function integration before any production migration.
4. **Staging:** exact candidate deployed only to isolated staging; auth, authorization, RLS, business-flow and failure-injection tests; no production credentials.
5. **Security review:** least privilege, RLS/grants, secrets exposure, dependency and Supabase Security/Performance Advisor findings assessed.
6. **Recovery:** last-known-good version, verified deployment adapter, tested backup/restore or forward-fix plan, data compatibility, monitoring and incident owner.
7. **Owner approval:** primary Owner + AAL2 reviews the exact commit, artifact, evidence, risk and recovery plan. Approval is explicit, audited, one-use, and expires if candidate identity changes.

## Production adapter requirements

The future release adapter must:
- accept only a server-generated candidate ID, never arbitrary URLs or commands;
- independently re-fetch and verify the candidate, CI runs, staging evidence, approval, and artifact digest;
- use a concurrency/compare-and-set lock to prevent two releases racing;
- deploy only to an allow-listed target and only after all gates pass;
- write append-only audit events atomically with state transitions;
- stop safely on timeouts or ambiguous outcomes and require Owner reconciliation;
- verify the deployed version and health before marking success.

If the hosting platform cannot prove rollback, do not offer a rollback button. Code/config rollback, database restore/forward-fix, and compensation for already-sent email/payment/third-party effects are distinct recovery paths. Never claim an irreversible external side effect was undone.

## Audit and monitoring

Every state transition records actor, action, candidate ID, source commit, artifact digest, previous/new state, evidence references, timestamp, and outcome. Secrets, tokens, passwords, and private user data must never enter logs. Monitoring checks the deployed version and critical health signals for a defined observation window. A failed monitor blocks further rollout and opens an incident/recovery record; it must not silently retry deployment.

## Initial draft implementation

- Add an Owner-only System Evolution navigation entry and a disabled proposal/evidence/approval/history layout.
- Keep all state-changing controls disabled and do not wire a production adapter.
- Add CI assertions that the controls remain disabled and the Pages deploy workflow remains main-only and is not triggered by pull requests.
- Continue design and disposable tests before considering a separate staging integration phase.

## Explicitly not implemented

No proposal persistence, release database tables, approval RPC, staging deployment, production adapter, canary rollout, rollback adapter, or live audit API is implemented by this draft. Do not activate this feature by changing a browser-side flag. Backend authorization and server-side release gates must exist first.
