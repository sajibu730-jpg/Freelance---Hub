import { constantTimeEqual, isExpectedEvidenceStoragePath } from "./validation.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

Deno.test("cleanup accepts only the canonical user/evidence/proof object path", () => {
  assert(isExpectedEvidenceStoragePath(
    "11111111-1111-4111-8111-111111111111/22222222-2222-4222-8222-222222222222/proof",
  ), "canonical evidence path rejected");
  assert(!isExpectedEvidenceStoragePath("../other-user/private"), "path traversal accepted");
  assert(!isExpectedEvidenceStoragePath(
    "11111111-1111-4111-8111-111111111111/22222222-2222-4222-8222-222222222222/other",
  ), "unexpected object name accepted");
  assert(!isExpectedEvidenceStoragePath(
    "11111111-1111-4111-8111-111111111111/proof",
  ), "short path accepted");
  assert(!isExpectedEvidenceStoragePath(null), "non-string path accepted");
});

Deno.test("cleanup secret comparison checks full input length and content", () => {
  assert(constantTimeEqual("secret-value", "secret-value"), "equal secrets rejected");
  assert(!constantTimeEqual("secret-value", "secret-value-x"), "longer secret accepted");
  assert(!constantTimeEqual("secret-value", "secret-valuf"), "different secret accepted");
  assert(!constantTimeEqual("secret-value", ""), "empty secret accepted");
});
