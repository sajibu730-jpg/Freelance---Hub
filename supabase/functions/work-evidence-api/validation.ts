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
    const host = url.hostname.toLowerCase();
    if (host === "localhost" || host.endsWith(".localhost") || host === "127.0.0.1"
      || host === "::1" || host.startsWith("10.") || host.startsWith("192.168.")
      || /^172\.(1[6-9]|2[0-9]|3[01])\./.test(host)) return null;
    return url.toString();
  } catch {
    return null;
  }
}

export function safeOriginalFilename(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const name = value.trim();
  if (!name || name.length > 128 || /[\\/\\0-\\x1f\\x7f]/.test(name) || name === "." || name === "..") return null;
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
