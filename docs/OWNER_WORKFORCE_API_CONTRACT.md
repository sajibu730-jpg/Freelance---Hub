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
- `owner_workforce_invitations` / `owner_workforce_invitation_events`: normalized target email, proposed role/scope, dispatch/acceptance/reconciliation status, Auth UUID, and append-only lifecycle audit.
- `owner_workforce_tasks`: title, description, task kind (`general`, `support`, `incident`), project scope, priority, lifecycle status, creator, assignee, due date, completion evidence.
- `owner_workforce_task_events`: append-only assignment/status/comment/evidence audit events.
- `owner_release_proposals`: source commit, change summary, risk, test evidence, approval status, approved actor/time, release and rollback references.
- `owner_release_events`: append-only CI, staging, approval, deployment, monitor, block, and rollback events.

Foreign keys must target stable UUID identities. Use check constraints for roles, statuses, and risk levels. Use unique/idempotency keys for external request IDs where retries could otherwise create duplicate assignments or release events.

## Draft atomic mutation RPC layer

The non-production SQL draft is `docs/drafts/owner-workforce-rpcs.draft.sql`. It defines four server-only operations:

- `owner_workforce_create_task` — Project Manager may create general tasks; Support may create support/incident tasks.
- `owner_workforce_assign_task` — Project Manager assigns within project scope to an active member whose role matches the task kind.
- `owner_workforce_update_task_status` — only the active assigned Developer/QA member may use the permitted status transitions; blocked reason and completion evidence are mandatory when applicable.
- `owner_workforce_append_task_evidence` — only the active assigned Developer/QA member may append evidence; completed tasks are read-only.

Each operation takes an idempotency request UUID, serializes retries for that request, and commits the task mutation plus one audit event in the same PostgreSQL function transaction. A repeated matching request returns the original task; reusing the request key with a different actor or operation is rejected.

These functions use `SECURITY DEFINER` only because the workforce tables intentionally have no client grants or policies. Every function pins `search_path = ''`, fully qualifies database objects, revokes EXECUTE from `PUBLIC`, `anon`, and `authenticated`, and grants EXECUTE only to `service_role`. This does not make a browser safe to call them: only the trusted Edge Function may hold/use the service-role credential. The Edge Function must validate the user with Auth `getUser(token)` and supply the verified Auth user ID as `p_actor_user_id`; it must never accept that ID from client JSON. Owner-only RPCs are not part of this draft; any such operation must require verified Owner AAL2 in the Edge Function before calling a server-only RPC.

The draft and its disposable PostgreSQL tests are not a Supabase migration and have not been applied to Production. Passing disposable PostgreSQL checks does not prove deployed Supabase Auth integration, PostgREST exposure, Edge Function behavior, or production readiness.

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


## Read-only task listing (draft Edge Function)

The undeployed `owner-workforce-control` handler now supports `action: "list_tasks"`. It validates the bearer token through Supabase Auth, checks active membership and project scope, then applies role-specific server-side filters: Project Managers see tasks in their scoped project, Developers and QA see only tasks assigned to their verified account, and Support sees only support/incident tasks. Results are limited to the latest 50 records and expose no audit-event table. This endpoint is still draft source; it is not deployed and has not been integration-tested against a real Supabase staging project.


## Owner-only workforce roster (draft Edge Function)

The handler now supports `action: "list_members"` as a read-only Owner roster. It does not require a project key or mutation idempotency key. Before reading the roster, it calls the shared primary-Owner authorization helper, which verifies the bearer token with Supabase Auth, binds the token subject to the verified user, requires AAL2, and confirms an active `app_admins` Owner record. The response is limited to 100 membership records and excludes audit-event tables. This is draft-only source and is not deployed.


## Owner workforce member management (draft only)

The undeployed Edge Function now supports Owner-only \`set_member\` and \`set_member_status\` actions. The Owner can add an existing Supabase Auth account to the workforce, change its role/project scopes, suspend it, or reactivate it. Every request requires a verified primary Owner AAL2 session; the database RPC independently checks the actor against the active \`app_admins\` Owner row and writes the membership change plus audit event atomically.

This first safe phase intentionally does not create Auth users or send invitation emails. It only manages an existing Auth user UUID, preventing an email invite from being sent before the membership transaction is committed. A separate invitation workflow must later handle email delivery and compensating cleanup/reconciliation.


The Owner-only \`list_member_events\` action returns a bounded, newest-first membership audit history (maximum 100 records). The event table remains inaccessible to API roles; the read is performed by a SECURITY DEFINER function that checks the active primary Owner row, while the Edge Function separately requires AAL2.


## Staff invitation safety extension (draft)

The recoverable dispatch design is documented in `docs/drafts/owner-workforce-invitations.draft.md` and its side-effect-injected coordinator is in `supabase/functions/owner-workforce-control/invitation-lifecycle.ts`. Invitation email delivery is an external Auth side effect and cannot be made atomic with a database transaction. The dispatch coordinator therefore requires a durable compare-and-set claim before Auth is called, records the returned Auth user UUID, and quarantines ambiguous Auth/network/database outcomes for Owner reconciliation. It never automatically retries an uncertain send and never deletes an Auth user as compensation. The coordinator is tested with Deno fakes but is not wired to the Edge Function or deployed. Draft invitation persistence RPCs and verified-email acceptance now exist and are exercised against disposable PostgreSQL. Owner reconciliation UI, Edge Function adapter wiring, and real staging Auth integration remain pending.
