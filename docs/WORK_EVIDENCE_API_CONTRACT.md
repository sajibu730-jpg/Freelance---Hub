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

Original submission fields and evidence identity are immutable after creation. Review decisions are separate updates. Database triggers append an audit event in the same transaction as each insert/update; audit events reject UPDATE and DELETE.

## File and privacy controls

- Proposed private bucket: `work-evidence-private`; public access disabled.
- Maximum file size: 10 MiB.
- Initial allowlist: PDF, JPEG, PNG, WebP. HTML, SVG, archives, and executable formats are rejected.
- The API checks the actual file signature and exact byte count after upload; it does not trust the file name or MIME header alone.
- Upload uses a signed URL (Supabase currently documents a two-hour validity window). Owner download links expire after five minutes.
- No public Storage URL or direct client table grant is created. Evidence tables have RLS enabled and direct `anon`/`authenticated` grants revoked. The Edge Function uses the server-only service role only after verifying the user JWT and relationship.
- External links must be HTTPS and contain no embedded credentials. The server does not fetch links or generate previews.
- The current draft does not include antivirus scanning or content disarm/reconstruction. Signature checks are not malware scanning; downloaded files must still be treated as untrusted. Add a quarantine/scanning integration before broad production rollout.
- The API has a best-effort limit of 20 evidence records per user per rolling 24 hours. This count-based check is not atomic under concurrent requests; replace it with an atomic database rate-limit mechanism before production rollout.
- Evidence is not automatically deleted. A retention policy, legal hold process, user deletion rules, and owner export procedure require explicit policy decisions before production.

## Owner authorization

Only an active primary Owner account may list, review, or download evidence. When the Owner has a verified TOTP factor, the JWT must have AAL2. Invited dashboard viewers and workforce roles are not granted access by this draft.

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
