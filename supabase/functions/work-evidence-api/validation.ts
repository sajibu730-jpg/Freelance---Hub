export const MAX_FILE_BYTES = 10 * 1024 * 1024;
export const ALLOWED_EVIDENCE_MIME = new Set([
  "application/pdf",
  "image/jpeg",
  "image/png",
  "image/webp",
]);

export function isUuid(value: unknown): value is string {
  return typeof value === "string"
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

export function cleanText(value: unknown, max: number): string | null {
  if (typeof value !== "string") return null;
  const result = value.trim();
  if (!result || result.length > max) return null;
  return result;
}

export function safeHttpsUrl(value: unknown): string | null {
  if (typeof value !== "string" || value.length > 2048) return null;
  try {
    const url = new URL(value);
    if (url.protocol !== "https:" || !url.hostname || url.username || url.password) return null;

    // Evidence links are opened by a human reviewer, never fetched by this API.
    // Still reject IP literals and local/reserved hostnames to avoid exposing
    // reviewers to links aimed at loopback, private networks, or local services.
    const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, "").replace(/\.$/, "");
    const localSuffixes = [".localhost", ".local", ".internal", ".test", ".invalid", ".example", ".home.arpa"];
    const isIpLiteral = host.includes(":") || /^\d+(?:\.\d+){0,3}$/.test(host);
    if (!host.includes(".") || isIpLiteral || host === "localhost"
      || localSuffixes.some((suffix) => host.endsWith(suffix))) return null;

    return url.toString();
  } catch {
    return null;
  }
}

export function safeOriginalFilename(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const name = value.trim();
  if (!name || name.length > 128 || name === "." || name === ".."
    || name.includes("/") || name.includes("\\")
    || [...name].some((character) => {
      const code = character.charCodeAt(0);
      return code < 32 || code === 127;
    })) return null;
  return name;
}

export function hasExpectedMagic(mime: string, bytes: Uint8Array): boolean {
  if (mime === "application/pdf") {
    return bytes.length >= 5 && new TextDecoder().decode(bytes.slice(0, 5)) === "%PDF-";
  }
  if (mime === "image/jpeg") {
    return bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  }
  if (mime === "image/png") {
    return bytes.length >= 8 && [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]
      .every((value, index) => bytes[index] === value);
  }
  if (mime === "image/webp") {
    return bytes.length >= 12
      && new TextDecoder().decode(bytes.slice(0, 4)) === "RIFF"
      && new TextDecoder().decode(bytes.slice(8, 12)) === "WEBP";
  }
  return false;
}

export function hasRequiredOwnerMfa(
  factors: { all?: Array<{ factor_type?: string; status?: string }> } | null | undefined,
  aal: string | null | undefined,
): boolean {
  if (!factors || !Array.isArray(factors.all)) return false;
  const totpFactors = factors.all.filter((factor) => factor.factor_type === "totp");
  return totpFactors.some((factor) => factor.status === "verified") && aal === "aal2";
}

export function isSoleActiveOwner(
  owners: Array<{ user_id?: string }> | null | undefined,
  userId: string,
): boolean {
  return Array.isArray(owners) && owners.length === 1 && owners[0].user_id === userId;
}

export function isWorkSubmissionParticipant(
  userId: string,
  freelancerId: string,
  jobOwnerId: string | null | undefined,
): boolean {
  return userId === freelancerId || (!!jobOwnerId && userId === jobOwnerId);
}

export function submissionMatchesContract(
  submissionFreelancerId: string,
  contractFreelancerId: string,
): boolean {
  return submissionFreelancerId === contractFreelancerId;
}

export function canReviewEvidence(currentStatus: string, nextStatus: string): boolean {
  return ["submitted", "needs_info"].includes(currentStatus)
    && ["verified", "rejected", "needs_info"].includes(nextStatus);
}
