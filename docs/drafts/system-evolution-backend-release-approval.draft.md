# System Evolution Backend & Release Approval — Draft Specification

Status: design-only. A separate DRAFT SQL file (`system-evolution-approval-rpc.draft.sql`) contains the proposed authenticated Owner AAL2 approval RPC and is applied only to disposable PostgreSQL in CI. No Supabase migration, Edge Function, credential, or deployment adapter is deployed or activated.

## Security boundary

- The browser dashboard is an untrusted client. It may display records, but it cannot grant roles, set approval state, sign evidence, choose deployment targets, or execute releases.
- Only the verified primary Owner identity may approve a candidate. Authorization is resolved server-side from the authenticated subject and current database authorization record; never trust a user ID, role, email, or AAL claim supplied in request JSON.
- Every approval request must be authenticated, require a current AAL2 session, and be re-checked at the moment the atomic approval transaction commits.
- No staff, delegated admin, AI agent, service-role browser key, or API key can substitute for the primary Owner's explicit approval.
- This draft does not authorize production schema changes, secret creation, Edge Function deployment, or release execution.

## Proposed data model (not implemented)

### evolution_candidates
One immutable candidate identity per exact source/artifact combination:
- id UUID primary key; created_at; created_by; title; description;
- change_category; risk_level; impact_summary; privacy_security_impact; cost_impact;
- repository; source_branch; source_commit_sha; pull_request_number;
- artifact_name; artifact_sha256; artifact_size; build_run_id;
- lifecycle_state; candidate_version; submitted_at; expires_at;
- recovery_plan; staging_environment_id; staging_deployed_commit; staging_verified_at.

The server derives repository allow-list, PR head SHA, workflow conclusions, artifact digest, and staging identity from trusted GitHub/staging integrations. Client payloads may not provide trusted evidence fields.

### evolution_evidence
Append-only evidence rows:
- id; candidate_id; evidence_type; provider; provider_reference;
- subject_commit_sha; artifact_sha256; result; observed_at; expires_at;
- details_digest; created_at.
Evidence is valid only when it matches the candidate's exact commit and artifact, comes from an allow-listed provider, is successful, and is within its freshness window. Raw secrets, environment values, personal data, and bearer tokens are prohibited.

### evolution_approvals
Append-only approval records:
- id; candidate_id; candidate_version; owner_user_id;
- approved_commit_sha; approved_artifact_sha256; evidence_set_digest;
- risk_acknowledgement; recovery_plan_digest; approved_at; expires_at;
- consumed_at; invalidated_at; invalidation_reason.
A database uniqueness rule should prevent more than one active approval for the same candidate version. Approval cannot be edited or transferred. A changed commit, artifact, risk, recovery plan, required evidence, or candidate version invalidates it.

### evolution_release_events
Append-only audit events:
- id; candidate_id; event_type; actor_user_id; actor_kind;
- previous_state; new_state; source_commit_sha; artifact_sha256;
- evidence_set_digest; approval_id; correlation_id; occurred_at; outcome_code.
Do not store tokens, passwords, full request headers, or unnecessary personal data. Events are written in the same transaction as each state transition.

## State machine

Allowed transitions are server-owned and compare-and-set protected:

`draft -> submitted -> validating -> staging_ready -> owner_approved -> release_queued -> deploying -> monitoring -> released`

Terminal or exceptional states: `blocked`, `rejected`, `expired`, `deployment_uncertain`, `recovery_required`, `recovered`.

- Missing, failed, stale, inconclusive, or mismatched mandatory evidence blocks progression.
- A rejected, expired, blocked, or changed candidate cannot be silently reopened; create a new candidate version and rerun required checks.
- `deployment_uncertain` requires reconciliation against the provider's actual deployed version; never retry blindly.
- State transitions use row locking or an equivalent compare-and-set guard and an idempotency key. Duplicate requests return the prior result without repeating side effects.

## Proposed server-side operations (not implemented)

1. `create_evolution_candidate`: authenticated proposer; validate bounded text and allow-listed change category; create draft only. No privileged fields accepted.
2. `submit_evolution_candidate`: server validates immutable source identity; records submission; starts trusted evidence collection.
3. `record_evolution_evidence`: service-to-service only; verifies signed/provider-origin evidence and exact commit/artifact match.
4. `mark_staging_verified`: service-to-service only; confirms isolated staging target, deployed SHA, health checks, and critical-flow tests.
5. `approve_evolution_release`: a draft-only SQL RPC now reads `auth.uid()` and `auth.jwt()->>'aal'` directly, checks the active `app_admins` Owner row in the transaction, validates all nine required fresh evidence types against the exact source commit and artifact digest, then atomically writes the one-use 15-minute approval, candidate state, and audit event. It does not deploy or queue a release.
6. `queue_evolution_release`: server only; re-check approval freshness, candidate version, idempotency key, concurrency lock, and target allow-list. It may enqueue a future adapter but must not deploy in this draft phase.
7. `record_evolution_release_result`: trusted adapter only; reconcile provider deployment identity and health result; ambiguous results become `deployment_uncertain`.
8. `invalidate_evolution_approval`: automatic on any material candidate/evidence change or expiry; append audit event.

## Authorization and database policy

- Enable and force RLS on all user-facing candidate/evidence/approval/event tables.
- Revoke broad table writes from `anon` and `authenticated`; no direct browser writes to approvals, evidence, events, or lifecycle state.
- Read access is limited to the primary Owner and narrowly scoped read-only views; sensitive provider references are not exposed to staff.
- Security-definer functions, if eventually implemented, must pin `search_path`, schema-qualify objects, validate `auth.uid()`, validate AAL2 from trusted auth context, and enforce active primary Owner authorization inside the transaction.
- Service-role credentials remain server-side and are never embedded in the dashboard or Android APK.
- AAL2 alone is insufficient: the current authenticated subject must also match the active primary Owner record.
- All authorization failures are deny-by-default and must not leak whether another account or candidate exists.

## Required gates before a future activation proposal

- CI runs are successful for the exact PR head commit; no skipped or cancelled required job.
- Android APK or web artifact is reproducible and its SHA-256 digest is recorded.
- Static checks, unit tests, disposable PostgreSQL tests, RLS/grant tests, and integration tests pass.
- Isolated staging runs the exact approved commit and artifact; critical auth, account, job, proposal, messaging, notification, and payment-adjacent flows are verified where applicable.
- Security review confirms least privilege, no client-side service key, no unsafe definer function, and no unresolved critical/high finding.
- Recovery plan is actionable and independently tested for the affected component. Database rollback is not assumed; forward-fix/restore and external side-effect compensation are documented separately.
- Evidence has not expired, and candidate commit, artifact digest, risk classification, and recovery-plan digest are unchanged.
- Owner approval is explicit, one-use, time-limited, and bound to the exact candidate version.

## Release and recovery rules

No production adapter is part of this design. A future adapter must use a fixed allow-listed target, short-lived server-side credentials, immutable artifact digest, concurrency lock, idempotent request, and post-deploy verification. It must never accept arbitrary shell commands, SQL, URLs, branch names, or environment variables from a client.

If deployment outcome is ambiguous, stop and reconcile the provider state before any retry. If a release is unhealthy, halt further rollout and create a recovery event. Only offer automated rollback when the platform proves that the exact previous version can be restored safely. Database schema changes may require forward-fix or tested restore; external email, payment, and third-party effects cannot be described as undone unless independently compensated and verified.

## Not implemented / activation prohibited

This specification creates no tables, policies, functions, triggers, secrets, staging resources, webhooks, provider credentials, or deployment jobs. It does not connect the dashboard buttons. Production release and rollback remain unavailable until a separate implementation PR, disposable integration tests, staging validation, security review, and explicit Owner approval are completed.
