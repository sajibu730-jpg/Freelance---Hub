# Work Evidence API — Draft Contract

**Status:** Draft only. The Edge Function and SQL are not deployed. The private Storage bucket does not yet exist in Production. The Owner Dashboard is not yet wired to this API.

## Purpose

Record verifiable evidence of work completed either inside Freelance Hub or outside the app. Each record identifies the submitting account, source (in-app/off-app), optional job/contract/submission references, submission time, evidence type, and review status.

The existing `work_submissions`, `work_analyses`, and `work_reviews` tables remain the canonical in-app delivery/review workflow. This API adds a separate evidence register and does not rewrite those tables.

## API

Function: `work-evidence-api`  
Method: `POST`  
JWT verification: enabled (default; must remain enabled).  
Authentication: Supabase user JWT in `Authorization: Bearer <user-jwt>`.

All requests use JSON and one `action`:

| Action | Caller | Purpose |
| --- | --- | --- |
| `prepare_upload` | Signed-in user | Create an upload-pending evidence record and obtain a signed upload URL |
| `complete_upload` | Submitting user | Confirm uploaded object, check byte count and file signature, then submit it |
| `retry_upload` | Submitting user | Renew an expired upload URL if the private object has not arrived yet |
| `submit_link` | Signed-in user | Submit an HTTPS external evidence link |
| `submit_note` | Signed-in user | Submit a written work record |
| `my_evidence` | Signed-in user | Read only that account's evidence status/history |
| `owner_list` | Primary Owner | List evidence for review |
| `owner_review` | Primary Owner | Mark evidence verified, rejected, or needs-info |
| `owner_download` | Primary Owner | Obtain a five-minute signed download URL for a private file |
| `owner_events` | Primary Owner | Read the append-only audit timeline for one evidence record |

Every submission requires a client-generated UUID `request_id` idempotency key. The server derives `submitted_by` from the verified JWT; clients cannot nominate another account.

### Relationship rules

- `origin=in_app` requires a valid job, contract, or work-submission reference.
- If a contract is supplied, the caller must be its client or freelancer. A supplied job/submission must match that contract.
- If only a job is supplied, the caller must own the job or have submitted a proposal for it.
- `origin=off_app` may be unlinked, allowing proof of work arranged outside Freelance Hub. If it references an app job/contract, the same participant checks apply.
- The API never trusts a user-supplied account ID or display name.

### Evidence and status model

Evidence types: `file`, `link`, `note`.

Statuses:
- `upload_pending`: a file record exists but upload has not been confirmed.
- `submitted`: proof is stored and ready for Owner review.
- `verified`: Owner accepted the evidence.
- `rejected`: Owner rejected it with a review note.
- `needs_info`: Owner requested more information.
- `upload_failed`: upload failed or the file did not pass integrity checks.

Allowed status transitions:
- `upload_pending` → `submitted` or `upload_failed`.
- `submitted` → `verified`, `rejected`, or `needs_info`.
- `needs_info` → `verified`, `rejected`, or `needs_info` (the Owner may request clarification again).
- `verified` and `rejected` are final; no further review transition is allowed.

The database trigger enforces this state machine and prevents review metadata changes outside a valid review transition. Original submission fields and evidence identity are immutable after creation. Database triggers append an audit event in the same transaction as each insert/update; audit events reject UPDATE and DELETE.

### Pending-upload expiry and private Storage cleanup

- A file upload remains `upload_pending` for at most **24 hours** from record creation. The evidence record is retained; it is not deleted by cleanup.
- The API stops issuing renewed two-hour signed upload URLs after **21 hours 45 minutes**, leaving a safety margin before the 24-hour expiry.
- The cleanup worker first atomically changes expired `upload_pending` rows to `upload_failed`. The same database transaction creates a durable cleanup-queue row and the normal audit event. A concurrent `complete_upload` can win only if its conditional status update commits first; once expiry wins, completion is rejected.
- The worker claims queue entries with `FOR UPDATE SKIP LOCKED`, a 15-minute processing lease, and batches of at most 50. It removes only the exact expected `{user_uuid}/{evidence_uuid}/proof` object from the private bucket.
- Missing objects count as already cleaned. Storage failures return the queue entry to `pending` with exponential backoff (30 seconds up to one hour); stale processing leases are reclaimable. Evidence rows and existing audit events remain intact.
- Successful removals and retry outcomes append system audit events. Raw provider errors, signed URLs, and secrets are never stored in audit details.
- The separate `work-evidence-cleanup` Edge Function requires the platform JWT check, an Authorization bearer matching the server-side Supabase service-role key, and a server-only `WORK_EVIDENCE_CLEANUP_SECRET` header. Both values must be supplied only by a trusted server-side scheduler and stored only in server-side secret storage; never in Git, Android, browser JavaScript, or the Owner Dashboard.
- Suggested schedule after Staging validation: invoke the worker every 15 minutes using a trusted server-side scheduler. No schedule, secret, Edge Function, SQL, or Storage resource has been configured or deployed by this draft.

The worker is implemented as draft code, but real Supabase Storage behavior, scheduler authentication, retries, and race handling still require Staging integration tests before release.

## File and privacy controls

- Proposed private bucket: `work-evidence-private`; public access disabled.
- Maximum file size: 10 MiB.
- Initial allowlist: PDF, JPEG, PNG, WebP. HTML, SVG, archives, and executable formats are rejected.
- The API checks the actual file signature and exact byte count after upload; it does not trust the file name or MIME header alone.
- Upload uses a signed URL (Supabase currently documents a two-hour validity window). Owner download links expire after five minutes.
- No public Storage URL or direct client table grant is created. Evidence tables have RLS enabled and direct `anon`/`authenticated` grants revoked. The Edge Function uses the server-only service role only after verifying the user JWT and relationship.
- External links must be HTTPS and contain no embedded credentials. The server does not fetch links or generate previews.
- The current draft does not include antivirus scanning or content disarm/reconstruction. Signature checks are not malware scanning; downloaded files must still be treated as untrusted. Add a quarantine/scanning integration before broad production rollout.
- The API enforces a 20-record per-user rolling 24-hour limit through a database RPC serialized with a per-user transaction advisory lock. The quota ledger is inaccessible to API roles; the Edge Function can execute only the quota-claim RPC. Repeated request IDs are idempotent and do not consume additional slots.
- Evidence is not automatically deleted. A retention policy, legal hold process, user deletion rules, and owner export procedure require explicit policy decisions before production.

## Owner authorization

Only the single active `app_admins` record with `role = owner` may list, review, or download evidence. Because the existing `app_admins` schema has no primary-owner flag, the API fails closed if more than one active Owner record exists; invited dashboard viewers and workforce roles are not granted access by this draft. A verified TOTP factor is mandatory, and the request JWT must have AAL2. Missing or unverified TOTP enrollment is denied. The primary Owner must complete authenticator enrollment and verification in Supabase Auth before using the evidence review API.

## Audit fields

The evidence row records:
- submitting Auth account UUID;
- source origin and evidence type;
- optional job, contract, and work-submission UUIDs;
- title and description;
- external URL or private Storage path;
- original filename, MIME type, byte count;
- submitted, reviewed, and created timestamps;
- verification status, reviewer account, and review note.

The append-only event stream records evidence creation, upload completion, submission, and review status transitions. It intentionally excludes file contents, signed URLs, access tokens, passwords, and OTPs.

## Test plan

1. Deno validation tests: UUID/idempotency format, HTTPS URL policy, filename/path traversal, MIME allowlist, file size, and PDF/image signatures.
2. Disposable PostgreSQL: RLS enabled, client grants absent, service-role grants present, private bucket settings, immutable evidence identity, atomic audit event creation, and append-only event history.
3. Edge Function integration (staging only): JWT rejection, account isolation, contract/job participant authorization, Owner MFA, signed upload expiry, object size/signature validation, review authorization, and signed download expiry.
4. Dashboard integration after PR #13 is merged: Owner evidence queue, account/job/contract details, preview/download controls, review notes, filters, and audit timeline.
5. Regression: existing in-app work submissions, AI analysis, and work review behavior remains unchanged.

## Release boundary

No Production schema, Storage bucket, Edge Function, dashboard page, or live account has been changed by this draft. Do not apply the SQL, deploy the function, or activate the UI until disposable tests pass, staging integration is completed, security review is accepted, and the Owner explicitly approves the release.
