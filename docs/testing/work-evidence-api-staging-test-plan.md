# Work Evidence API — Staging Integration Test Plan

Status: **Prepared, not executed**  
Scope: PR #14 draft branch only.  
Safety: Use a disposable Supabase staging project with synthetic accounts and data. Never point these tests at Production project `imffbmwdlenhmhxopwoq`.

## 1. Entry criteria

- [ ] Owner explicitly authorizes a separate Staging environment after reviewing any cost implications.
- [ ] Staging project URL and keys are stored only as GitHub Actions secrets; never commit credentials.
- [ ] Staging schema is created from `docs/drafts/work-evidence-api-schema.draft.sql`.
- [ ] Edge Function is deployed only to Staging and configured with JWT verification.
- [ ] Staging bucket `work-evidence-private` is private, limited to 10 MiB, and allows only PDF/JPEG/PNG/WebP.
- [ ] Synthetic users exist: primary Owner, second Owner, freelancer A, freelancer B, client A, unrelated user.
- [ ] Synthetic jobs/contracts/submissions cover valid and deliberately mismatched relationships.
- [ ] No real customer records, email addresses, phone numbers, or uploaded documents are used.

## 2. Authentication and authorization

| Case | Actor / setup | Expected result |
|---|---|---|
| A01 | No Authorization header | 401 |
| A02 | Expired, malformed, or revoked JWT | 401 |
| A03 | Authenticated unrelated user requests Owner list/review/download/events | 403 |
| A04 | Sole active Owner with no TOTP factor | 403 |
| A05 | Owner with unverified TOTP | 403 |
| A06 | Owner with verified TOTP but AAL1 | 403 |
| A07 | Sole active Owner with verified TOTP and AAL2 | Owner action allowed |
| A08 | Two active `role=owner` records | All Owner actions fail closed |
| A09 | Owner account deactivated | Owner actions denied |
| A10 | Non-Owner with AAL2 | Owner actions denied |

Verify every Owner endpoint independently: `owner_list`, `owner_review`, `owner_download`, and `owner_events`.

## 3. Participant and relationship authorization

| Case | Setup | Expected result |
|---|---|---|
| R01 | Freelancer submits evidence for own submission | Allowed |
| R02 | Job owner submits evidence for a no-contract submission | Allowed |
| R03 | Unrelated user submits evidence for another user's submission | 403 |
| R04 | Contract participant submits for linked contract | Allowed |
| R05 | Unrelated user references a contract | 403 |
| R06 | Submission job_id conflicts with supplied job_id | 400 |
| R07 | Submission contract_id conflicts with supplied contract_id | 400 |
| R08 | Submission freelancer differs from linked contract freelancer | 403 |
| R09 | Contract job differs from referenced job | 400 |
| R10 | Freelancer merely has a proposal on the same job but targets another freelancer's submission | 403 |
| R11 | Off-app evidence without an in-app relationship | Allowed only under documented off-app policy; confirm product rule before release |

## 4. File upload and private Storage

- [ ] Prepare upload with valid PDF, JPEG, PNG, and WebP metadata.
- [ ] Reject unsupported MIME types, invalid filenames, zero-byte files, and files over 10 MiB.
- [ ] Confirm returned path is scoped to the authenticated user and evidence UUID.
- [ ] Confirm Storage bucket is not public and unauthenticated object reads fail.
- [ ] Upload valid bytes using the signed upload URL; complete upload; status becomes `submitted`.
- [ ] Upload a file whose actual size differs from declared size; reject and remove object.
- [ ] Upload HTML or spoofed file bytes with an allowed MIME label; reject by signature and remove object.
- [ ] Confirm another user cannot call retry/complete for the evidence ID.
- [ ] Confirm a second upload to the same path is rejected (`upsert=false`).
- [ ] Confirm expired upload URL cannot upload; retry issues a fresh URL only while the record is eligible.
- [ ] Owner download returns a signed URL with a 300-second expiry; non-Owner cannot obtain one.
- [ ] Confirm no public URL or permanent Storage URL is returned by any endpoint.

## 5. Submission, review, and audit trail

- [ ] Submit HTTPS link and note evidence; verify required fields and request idempotency.
- [ ] Reject HTTP, credential-bearing, localhost, private-IP, and malformed URLs.
- [ ] Owner can review only `submitted` or `needs_info` evidence.
- [ ] `rejected` and `needs_info` require a non-empty review note.
- [ ] Verify/reject/re-request transitions record reviewer ID and timestamp.
- [ ] Final states cannot be overwritten by a second review.
- [ ] Every insert and status update creates exactly one audit event.
- [ ] Audit event UPDATE and DELETE fail.
- [ ] Original evidence identity and proof fields cannot be changed after insert.
- [ ] Owner event timeline is chronological and available only after Owner MFA checks.

## 6. Quota, idempotency, and concurrency

- [ ] A user can claim 20 unique requests in a rolling 24-hour window.
- [ ] The 21st unique request is rejected with 429.
- [ ] Repeating the same request ID does not consume another quota slot.
- [ ] Two users have independent quotas.
- [ ] Forty concurrent claims for one user yield exactly 20 accepted and 20 rejected.
- [ ] A failed insert or upload preparation does not permit duplicate evidence creation.
- [ ] Verify quota cleanup does not delete events inside the rolling 24-hour window.

## 7. Regression and browser integration

- [ ] Existing work-submission create/update/list flow still works for freelancer and client.
- [ ] Existing messaging, jobs, contracts, and profile flows are unchanged.
- [ ] Browser preflight from the approved Owner Dashboard origin succeeds.
- [ ] Unexpected browser origins are rejected.
- [ ] Android/native requests without an Origin header still authenticate through JWT.
- [ ] No credentials or signed URLs are written to logs or client analytics.

## 8. Evidence required for a PASS

Attach to the PR:
- Staging project identifier (not secrets), schema version, and deployed function commit.
- CI run links and job conclusions.
- Sanitized test report with case IDs and HTTP outcomes.
- Storage privacy, upload-size, signature-validation, and signed-link expiry results.
- Quota concurrency counts.
- Audit immutability and review transition results.
- Regression run results.

A test is **PASS** only when its expected result is observed in Staging. Static review and disposable PostgreSQL CI do not count as Supabase Auth/Storage E2E verification.

## 9. Cleanup

- Delete synthetic evidence rows and test objects only after collecting test results.
- Remove synthetic users and test jobs/contracts/submissions from Staging.
- Revoke temporary test secrets and signed URLs.
- Keep Production untouched. No migration, Edge Function deployment, bucket creation, or dashboard integration is authorized by this plan.
