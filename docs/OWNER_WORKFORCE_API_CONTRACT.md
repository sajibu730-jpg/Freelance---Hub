# Owner Workforce API Contract (Draft)

Status: design contract only. No tables, Edge Functions, staff accounts, or modes are active yet.
This document is not a migration and must not be applied to Production.

## Security boundary

- The browser and Android clients use only the public/publishable key. Never place a service-role or secret key in either client.
- All workforce and release-control reads/writes go through authenticated Edge Functions.
- Every request validates the bearer token with Supabase Auth `getUser(token)`.
- The server reads the actor's current access record from the database. Never authorize from `user_metadata`, client-supplied role names, or UI visibility.
- The primary Owner is the active `app_admins` row with role `owner`. Owner-only mutations also require an AAL2 session. The bearer token must first pass Supabase Auth `getUser(token)`; only then may its `sub` and `aal` claims be used, and `sub` must match the verified Auth user ID.
- Staff permissions come from a server-owned workforce membership record. Staff cannot grant themselves roles or broaden their own scope.
- Database tables use RLS. Tables intentionally accessible only through the trusted Edge Function service role must have no client grants/policies and must have table-scoped RLS exception documentation in the migration review.
- Every mutation writes an immutable audit event with actor, action, target, timestamp, and a minimal before/after summary. The data mutation and audit insert must be atomic in one database transaction; separate REST calls are not sufficient. Never log tokens, passwords, or sensitive profile data.

## Roles and scope

| Role | Permitted work | Explicitly prohibited |
|---|---|---|
| Owner | Invite/suspend staff, set roles/scopes, assign/reassign any task, approve/reject releases, change operating mode | Cannot bypass release evidence or audit trail |
| Project Manager | Create tasks and assign/reassign within assigned project scope; review progress | Cannot manage staff, change modes, approve Production |
| Developer | Read assigned tasks, post progress, attach implementation/test evidence | Cannot self-assign, change permissions, approve/release |
| QA | Read assigned test tasks, record test results and blockers | Cannot approve own code for Production or deploy |
| Support | Read permitted operational summaries, create support tasks and incident reports | No source/database secrets, staff administration, or Production access |

No role is granted by a client payload. A staff user with no active membership has no workforce access. The shared authorization helper and Deno tests now encode the Owner+AAL2 check, the staff role matrix, project scopes, and assignee ownership; they are not yet wired to a deployed Edge Function.

## Proposed data model

The proposed SQL structure is recorded in `docs/drafts/owner-workforce-schema.draft.sql`. It is a non-migration design artifact and must not be executed. Convert it into a real migration only after the project has a disposable local/staging database and verified migration workflow.

- `owner_workforce_members`: auth user ID, role, active/suspended status, project scope, inviter, timestamps.
- `owner_workforce_tasks`: title, description, task kind (`general`, `support`, `incident`), project scope, priority, lifecycle status, creator, assignee, due date, completion evidence.
- `owner_workforce_task_events`: append-only assignment/status/comment/evidence audit events.
- `owner_release_proposals`: source commit, change summary, risk, test evidence, approval status, approved actor/time, release and rollback references.
- `owner_release_events`: append-only CI, staging, approval, deployment, monitor, block, and rollback events.

Foreign keys must target stable UUID identities. Use check constraints for roles, statuses, and risk levels. Use unique/idempotency keys for external request IDs where retries could otherwise create duplicate assignments or release events.

## Edge Function contract

Single function family: `owner-workforce-control`. Accept POST JSON only; reject unknown actions and oversized fields. Suggested actions:

- `list_members` — Owner only.
- `invite_member` — Owner only; create invitation and membership in a recoverable sequence.
- `change_member_role` / `suspend_member` — Owner only; require AAL2 and write audit event.
- `list_tasks` — return only tasks within the actor's server-calculated scope.
- `create_task` — Owner or Project Manager within scope.
- `assign_task` / `reassign_task` — Owner or Project Manager; validate active assignee and project scope.
- `update_task_status` — assignee or authorized manager; enforce allowed state transitions.
- `append_task_evidence` — assigned Developer/QA or authorized manager; append-only event.
- `submit_release_proposal` — Developer/QA/Manager may submit evidence; this never deploys.
- `approve_release` — primary Owner only, AAL2, with passing required checks and a reviewed artifact/commit.
- `reject_release` — primary Owner only; record reason and audit event.

Do not implement arbitrary SQL, arbitrary function invocation, shell execution, or user-provided URLs as privileged actions.

## Task lifecycle

`backlog -> assigned -> in_progress -> blocked | in_review -> done`

- A task cannot become `done` without completion evidence or an explicit Owner-approved exception.
- A blocked task requires a reason and remains visible to the assigning manager.
- Reassignment preserves the previous assignee and creates an event; it never overwrites history.
- A task status update is rejected if the actor is outside scope or the transition is invalid.

## System Evolution / release gate

`draft -> submitted -> checks_running -> staging_passed -> owner_approved -> production_deployed -> monitored`

Failure transitions to `blocked`; an already deployed, recoverable release may transition to `rollback_requested -> rolled_back` only through a verified deployment adapter.

Production release requires:
1. Immutable source commit and build artifact identifiers.
2. Passing Android build and dashboard/migration safety gates relevant to the change.
3. Staging functional, authorization, and RLS tests.
4. Supabase Security and Performance Advisor review after schema changes.
5. Explicit primary Owner approval with AAL2.
6. A documented recovery plan and post-release monitoring window.

A failed candidate is never promoted. If production deployment already occurred, rollback is permitted only when the target platform supports a verified rollback and the data changes are reversible. Database migrations often need a forward-fix or tested restore rather than a blind rollback. External email, payment, and third-party side effects require reconciliation/compensation.

## Operating modes

- `human`: people execute assigned work; CI, security gates, audit, monitoring, and Owner approval remain mandatory.
- `hybrid`: AI may prepare drafts/summaries and humans perform/approve work; AI cannot assign itself authority or release.
- `ai_assist`: AI may prepare proposals and test plans only; privileged execution remains human-approved.

Mode changes are Owner-only, AAL2, audited, and default to the safest mode if configuration is missing. These modes do not turn off security monitoring or release protection.

## Required tests before activation

- Anonymous and ordinary authenticated users cannot read/write any workforce or release table directly.
- Staff cannot read another project's tasks or modify another member's role.
- Suspended staff lose function access immediately on the next request.\n- Developers and QA cannot update a task unless the database-fetched task is assigned to their verified Auth user ID.\n- Support can create only support/incident task kinds; it cannot create general tasks.
- Only primary Owner + AAL2 can invite/suspend staff, change operating mode, and approve releases.
- Invalid state transitions, duplicate retries, forged actor IDs, and cross-project assignment are rejected.
- Every accepted mutation produces exactly one audit event.
- A failing CI/staging check blocks release approval and production promotion.
- Human mode retains all CI, audit, monitoring, and rollback safeguards.

## Activation boundary

The current Supabase account has one Production project and no development branches. Until a disposable database is available and these tests pass, keep the workforce UI and actions unavailable. Do not apply the draft SQL or a migration to the current production Supabase project as a substitute for staging.
