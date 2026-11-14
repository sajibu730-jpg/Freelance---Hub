import {
  ALLOWED_EVIDENCE_MIME,
  canReviewEvidence,
  hasExpectedMagic,
  hasRequiredOwnerMfa,
  isSoleActiveOwner,
  isUuid,
  isWorkSubmissionParticipant,
  submissionMatchesContract,
  MAX_FILE_BYTES,
  safeHttpsUrl,
  safeOriginalFilename,
} from "./validation.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

Deno.test("accepts UUID idempotency keys and rejects malformed values", () => {
  assert(isUuid("d2719d3e-7b1c-4a9a-9e23-21cbe43d5a01"), "valid UUID rejected");
  assert(!isUuid("not-a-uuid"), "invalid UUID accepted");
});

Deno.test("only accepts HTTPS links without credentials or local hosts", () => {
  assert(!!safeHttpsUrl("https://example.com/proof/123"), "valid HTTPS URL rejected");
  assert(!safeHttpsUrl("http://example.com/proof"), "HTTP URL accepted");
  assert(!safeHttpsUrl("javascript:alert(1)"), "javascript URL accepted");
  assert(!safeHttpsUrl("https://user:pass@example.com/proof"), "credential URL accepted");
  assert(!safeHttpsUrl("https://localhost/proof"), "localhost URL accepted");
  assert(!safeHttpsUrl("https://printer.local/status"), "local hostname accepted");
  assert(!safeHttpsUrl("https://admin.internal/"), "internal hostname accepted");
  assert(!safeHttpsUrl("https://127.0.0.1/"), "IPv4 loopback accepted");
  assert(!safeHttpsUrl("https://2130706433/"), "integer-form loopback accepted");
  assert(!safeHttpsUrl("https://10.0.0.8/"), "private IPv4 accepted");
  assert(!safeHttpsUrl("https://169.254.169.254/"), "link-local IPv4 accepted");
  assert(!safeHttpsUrl("https://[::1]/"), "IPv6 loopback accepted");
  assert(!safeHttpsUrl("https://intranet/"), "single-label hostname accepted");
});

Deno.test("rejects unsafe filenames", () => {
  assert(!!safeOriginalFilename("final-proof.pdf"), "safe filename rejected");
  assert(!safeOriginalFilename("../secret.pdf"), "path traversal filename accepted");
  assert(!safeOriginalFilename("folder\\proof.pdf"), "path separator accepted");
  assert(!safeOriginalFilename(""), "empty filename accepted");
});

Deno.test("file policy is limited to PDF and raster images up to 10 MB", () => {
  assert(ALLOWED_EVIDENCE_MIME.has("application/pdf"), "PDF missing");
  assert(ALLOWED_EVIDENCE_MIME.has("image/jpeg"), "JPEG missing");
  assert(ALLOWED_EVIDENCE_MIME.has("image/png"), "PNG missing");
  assert(ALLOWED_EVIDENCE_MIME.has("image/webp"), "WebP missing");
  assert(!ALLOWED_EVIDENCE_MIME.has("text/html"), "HTML must not be allowed");
  assert(MAX_FILE_BYTES === 10485760, "unexpected file size cap");
});

Deno.test("checks file signatures rather than trusting the MIME label", () => {
  assert(hasExpectedMagic("application/pdf", new TextEncoder().encode("%PDF-1.7")), "PDF signature rejected");
  assert(hasExpectedMagic("image/jpeg", new Uint8Array([0xff, 0xd8, 0xff, 0x00])), "JPEG signature rejected");
  assert(hasExpectedMagic("image/png", new Uint8Array([0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a])), "PNG signature rejected");
  assert(hasExpectedMagic("image/webp", new TextEncoder().encode("RIFF0000WEBP")), "WebP signature rejected");
  assert(!hasExpectedMagic("application/pdf", new TextEncoder().encode("<html>")), "spoofed PDF accepted");
});

Deno.test("Owner MFA fails closed for unverified TOTP and requires AAL2", () => {
  assert(!hasRequiredOwnerMfa({ all: [] }, "aal1"), "Owner without TOTP enrollment must be blocked");
  assert(!hasRequiredOwnerMfa(undefined, "aal2"), "missing factor response must fail closed");
  assert(!hasRequiredOwnerMfa({ all: [{ factor_type: "totp", status: "unverified" }] }, "aal1"),
    "unverified TOTP enrollment must not bypass MFA");
  assert(!hasRequiredOwnerMfa({ all: [{ factor_type: "totp", status: "verified" }] }, "aal1"),
    "verified TOTP must require AAL2");
  assert(hasRequiredOwnerMfa({ all: [{ factor_type: "totp", status: "verified" }] }, "aal2"),
    "verified TOTP with AAL2 should pass");
});

Deno.test("only the sole active Owner account receives review access", () => {
  assert(isSoleActiveOwner([{ user_id: "owner-1" }], "owner-1"), "sole Owner rejected");
  assert(!isSoleActiveOwner([{ user_id: "owner-1" }, { user_id: "owner-2" }], "owner-1"),
    "multiple active Owners must fail closed");
  assert(!isSoleActiveOwner([], "owner-1"), "no active Owner must fail closed");
  assert(!isSoleActiveOwner(null, "owner-1"), "missing Owner query must fail closed");
});

Deno.test("submission and contract access requires a real participant", () => {
  assert(isWorkSubmissionParticipant("freelancer-1", "freelancer-1", "client-1"),
    "submission freelancer rejected");
  assert(isWorkSubmissionParticipant("client-1", "freelancer-1", "client-1"),
    "job or contract client rejected");
  assert(!isWorkSubmissionParticipant("other-user", "freelancer-1", "client-1"),
    "unrelated user accepted");
  assert(!isWorkSubmissionParticipant("other-user", "freelancer-1", null),
    "unrelated user accepted without a job owner");
});

Deno.test("submission freelancer must match the linked contract freelancer", () => {
  assert(submissionMatchesContract("freelancer-1", "freelancer-1"),
    "matching submission and contract freelancer rejected");
  assert(!submissionMatchesContract("freelancer-2", "freelancer-1"),
    "a different freelancer's submission was accepted through this contract");
});

Deno.test("review policy allows only submitted or needs-info evidence", () => {
  assert(canReviewEvidence("submitted", "verified"), "submitted evidence cannot be verified");
  assert(canReviewEvidence("submitted", "rejected"), "submitted evidence cannot be rejected");
  assert(canReviewEvidence("needs_info", "verified"), "needs-info evidence cannot be verified");
  assert(canReviewEvidence("needs_info", "needs_info"), "needs-info evidence cannot be re-requested");
  assert(!canReviewEvidence("upload_pending", "verified"), "pending upload can be reviewed");
  assert(!canReviewEvidence("upload_failed", "verified"), "failed upload can be reviewed");
  assert(!canReviewEvidence("verified", "rejected"), "final review can be overwritten");
});
