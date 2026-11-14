# Freelance Hub Workforce Control & Zero-Failure Protection

Status: architecture specification; not yet enabled in production.

## Non-negotiable owner rules

1. The Owner is the final authority. AI, staff, administrators, moderators, and automated workflows cannot publish a production change without explicit Owner approval.
2. AI can be disabled without disabling human work, monitoring, audit history, or release protection.
3. AI and human changes use the same task, review, test, and release pipeline.
4. A failed validation must block production save/apply/deploy. The last known-good release remains active and the Owner receives a clear warning.
5. A saved draft is not a production change. Drafts may be retained for review in an isolated queue; failed drafts must never be applied to production.
6. New functionality must not regress existing critical flows (authentication, signup, profiles, jobs, proposals, contracts, messaging, notifications, and payments when introduced).

## Operating modes

- **AI mode**: AI may prepare drafts and implementation tasks; it cannot publish.
- **Human mode**: AI task generation is disabled. Authorized people own assignments and implementation. The same validation and release gate remains mandatory.
- **Hybrid mode**: AI can prepare suggestions and humans can work concurrently; one shared task queue and release gate.
- **Manual approval mode**: AI is advisory only; humans prepare changes and the Owner explicitly approves each release.

Changing mode is an Owner-only, audited action. A mode switch never disables backups, monitoring, tests, or rollback protection.

## Workforce model

Roles are least-privilege and separately assigned:
- Owner: all permissions, final approval, mode control, emergency rollback.
- Project manager: create/assign tasks and review progress; cannot approve production release.
- Developer: work only on assigned tasks and non-production branches/environments.
- QA tester: run tests, attach evidence, mark test results; cannot change production.
- Moderator/support: handle approved operational queues only; no code, schema, secrets, or release permissions.

Each work item records its title, scope, priority, owner, assignee, source (AI or human), status, branch/commit, test evidence, review notes, and timestamps. Assignment, reassignment, handoff, and completion are audited. Staff access is revoked centrally.

## System Evolution Center

A new system, rule, or large change follows this lifecycle:

1. Owner request and scope.
2. AI or human proposal, impact analysis, and risk classification.
3. Owner approval of scope and budget/operational impact.
4. Isolated development environment and versioned change.
5. Automated checks and QA evidence.
6. Owner release approval.
7. Canary/limited rollout where technically supported.
8. Health monitoring and full rollout only after success.
9. Version history, audit trail, and rollback plan retained.

Classify every change:
- Remote configuration: no APK only when the installed app already supports that exact key and behavior.
- Backend change: requires reviewed database/API/Edge Function deployment.
- Native Android change: requires a new Android build and distribution.
- External integration or irreversible side effect: requires a compensating/reconciliation plan.

## ZFPS release gate

Before production deployment:
- Validate syntax, types, dependency integrity, secrets exposure, authorization, RLS, and migration safety.
- Build Android and web artifacts as applicable.
- Run unit/integration tests and critical-flow regression checks.
- Verify migrations against a disposable/staging database before production.
- Record the last-known-good release identifier and compatible configuration.
- Require explicit Owner approval.

If any mandatory check fails:
- Block production deployment and production configuration writes.
- Preserve the current production release.
- Mark the candidate as failed and retain diagnostic evidence.
- Show this warning to the Owner:

  **কাজটা ভুল হয়েছে! আবার সঠিক জায়গায় ফিরে আসছি। দয়া করে আবার সঠিকভাবে চেষ্টা করুন।**

- Restore the last-known-good code/configuration automatically only where the deployment platform supports a safe rollback; otherwise abort before release and require a verified recovery action.

## Rollback boundaries

Code, feature flags, and compatible configuration can usually be rolled back. Database changes must use backward-compatible expand/contract migrations; destructive data/schema changes require a verified backup and a tested restore or forward-fix plan. Payments, emails, notifications already delivered, and third-party actions cannot be literally undone; use idempotency, reconciliation, and compensating actions.

No system can guarantee zero defects. ZFPS is designed to prevent detectable failures from reaching production and to recover safely from supported failures.

## Initial implementation sequence

1. Add PR checks for Owner Dashboard source and preserve main-only deployment.
2. Add a guarded Workforce Control UI and server-enforced Owner member/task APIs (draft implementation; not deployed).
3. Complete least-privilege staff identities, task assignments, audit events, and operating-mode settings.
4. Add release records, approvals, preflight checks, stable-version pointer, and rollback orchestration.
5. Add staging verification for database migrations and Edge Functions.
6. Connect Android/web release pipelines and critical-flow regression suites.
7. Verify each step before enabling it in production.

## Current audit findings (2026-11-14)

- The Owner AI Assistant saves drafts to `owner_ai_requests`; its prompt explicitly prohibits deployment or direct settings changes.
- Existing Owner Dashboard has Overview, AI Assistant, AI Request Queue, Remote Settings, System Monitoring, Device Access, and Owner Security.
- A draft Workforce Control panel is now present in the PR dashboard, with member list, add/update, suspend/reactivate, and audit-history UI. It is intentionally disabled while the backend remains undeployed.
- Existing Android CI builds both Android APKs for pull requests to `main`.
- Owner Dashboard Pages deploys on pushes to `main`; it already runs a JavaScript syntax check before publishing.
- Existing database includes `app_admins`, `owner_ai_requests`, `owner_system_reports`, `owner_device_access`, `user_platform_activity`, and app business tables. No workforce task, release approval, or release history tables were found in the schema inventory.
- Supabase Security Advisor currently reports items requiring review, including six RLS-enabled tables without policies, a SECURITY DEFINER public profile function callable by authenticated users, and leaked-password protection disabled. These findings must be assessed before expanding staff access. Do not blindly change existing policies as part of this feature.

## Production safety boundary

This specification and PR checks do not themselves activate Workforce Mode, create staff accounts, change Supabase production schema, or install a production rollback service. Those require separately reviewed implementation, staging tests, and Owner-approved deployment.


## Workforce implementation progress (draft PR, 2026-11-14)

- Added server-side Owner AAL2-protected actions to list members, add/update an existing Auth account's workforce role and project scopes, suspend/reactivate a member, and read bounded membership audit history.
- The database RPC layer independently checks the active primary Owner row in `app_admins`; mutations and audit events are written atomically. Audit tables remain inaccessible directly to API roles.
- The member-management SQL is applied only in disposable PostgreSQL CI. Tests cover Owner-only authorization, idempotency, role/scope changes, suspend/reactivate, audit history, and service-role-only RPC grants.
- The Owner Dashboard contains a Workforce Control panel, but its API feature flag is deliberately `false`; controls remain disabled until an explicitly approved backend deployment.
- The first version manages existing Auth user UUIDs only. It does not create accounts or send invitation emails; that workflow needs a separate recoverable invitation design.
- None of these draft APIs or tables are active in the Production Supabase project.


## Staff invitation implementation progress (2026-11-14)

A draft-only, side-effect-injected invitation dispatcher and Deno tests have been added. It requires a one-time database claim before calling Auth Admin, records the returned Auth user ID, and sends ambiguous outcomes to manual Owner reconciliation rather than retrying or deleting accounts. The implementation is not wired to the live Edge Function; no email or Auth user is created. See `docs/drafts/owner-workforce-invitations.draft.md`. CI now runs its isolated Deno tests.
