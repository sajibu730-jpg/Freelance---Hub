const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function isExpectedEvidenceStoragePath(
  value: unknown,
  userId: unknown,
  evidenceId: unknown,
): value is string {
  if (typeof value !== "string" || typeof userId !== "string" || typeof evidenceId !== "string") {
    return false;
  }
  if (!UUID.test(userId) || !UUID.test(evidenceId)) return false;
  return value === `${userId}/${evidenceId}/proof`;
}

export function constantTimeEqual(left: string, right: string): boolean {
  const length = Math.max(left.length, right.length);
  let difference = left.length ^ right.length;
  for (let i = 0; i < length; i++) {
    difference |= (left.charCodeAt(i) || 0) ^ (right.charCodeAt(i) || 0);
  }
  return difference === 0;
}
