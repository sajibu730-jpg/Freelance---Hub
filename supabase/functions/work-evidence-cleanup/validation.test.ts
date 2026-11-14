import { constantTimeEqual, isExpectedEvidenceStoragePath } from "./validation.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

const USER_ID = "11111111-1111-4111-8111-111111111111";
const EVIDENCE_ID = "22222222-2222-4222-8222-222222222222";
const PATH = `${USER_ID}/${EVIDENCE_ID}/proof`;

Deno.test("cleanup accepts only the exact user/evidence/proof object path", () => {
  assert(isExpectedEvidenceStoragePath(PATH, USER_ID, EVIDENCE_ID), "canonical evidence path rejected");
  assert(isExpectedEvidenceStoragePath(PATH.toUpperCase(), USER_ID, EVIDENCE_ID), "UUID case variation rejected");
  assert(!isExpectedEvidenceStoragePath(PATH, "33333333-3333-4333-8333-333333333333", EVIDENCE_ID),
    "path belonging to another user accepted");
  assert(!isExpectedEvidenceStoragePath(PATH, USER_ID, "44444444-4444-4444-8444-444444444444"),
    "path belonging to another evidence record accepted");
  assert(!isExpectedEvidenceStoragePath("../other-user/private", USER_ID, EVIDENCE_ID),
    "path traversal accepted");
  assert(!isExpectedEvidenceStoragePath(`${USER_ID}/${EVIDENCE_ID}/other`, USER_ID, EVIDENCE_ID),
    "unexpected object name accepted");
  assert(!isExpectedEvidenceStoragePath(`${USER_ID}/proof`, USER_ID, EVIDENCE_ID),
    "short path accepted");
  assert(!isExpectedEvidenceStoragePath(null, USER_ID, EVIDENCE_ID), "non-string path accepted");
  assert(!isExpectedEvidenceStoragePath(PATH, "not-a-uuid", EVIDENCE_ID), "invalid user ID accepted");
});

Deno.test("cleanup secret comparison checks full input length and content", () => {
  assert(constantTimeEqual("secret-value", "secret-value"), "equal secrets rejected");
  assert(!constantTimeEqual("secret-value", "secret-value-x"), "longer secret accepted");
  assert(!constantTimeEqual("secret-value", "secret-valuf"), "different secret accepted");
  assert(!constantTimeEqual("secret-value", ""), "empty secret accepted");
});
