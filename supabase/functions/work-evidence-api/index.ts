import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import {
  ALLOWED_EVIDENCE_MIME,
  canReviewEvidence,
  cleanText,
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

const BUCKET = "work-evidence-private";
const OWNER_DASHBOARD_ORIGIN = "https://sajibu730-jpg.github.io";
const MAX_BODY_BYTES = 32 * 1024;
const headers = {
  "Access-Control-Allow-Origin": OWNER_DASHBOARD_ORIGIN,
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Vary": "Origin",
};
const reply = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { ...headers, "Content-Type": "application/json", "Cache-Control": "no-store" },
  });

class HttpError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

function requiredDescription(value: unknown): string {
  if (value === undefined || value === null) return "";
  if (typeof value !== "string" || value.length > 3000) {
    throw new HttpError(400, "Description must be 3000 characters or fewer.");
  }
  return value.trim();
}

async function readJsonLimited(req: Request): Promise<Record<string, any>> {
  if (!req.body) throw new HttpError(400, "A JSON request body is required.");
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.length;
    if (total > MAX_BODY_BYTES) {
      await reader.cancel();
      throw new HttpError(413, "Request body is too large.");
    }
    chunks.push(value);
  }
  const body = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.length;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder().decode(body));
  } catch {
    throw new HttpError(400, "Request body must be valid JSON.");
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new HttpError(400, "A JSON object is required.");
  }
  return parsed as Record<string, any>;
}

function optionalUuid(value: unknown, field: string): string | null {
  if (value === undefined || value === null || value === "") return null;
  if (!isUuid(value)) throw new HttpError(400, `${field} must be a valid ID.`);
  return value;
}

async function ownerMfaSatisfied(authClient: any, token: string): Promise<boolean> {
  const [{ data: factors, error: factorError }, { data: assurance, error: assuranceError }] =
    await Promise.all([
      authClient.auth.mfa.listFactors(),
      authClient.auth.mfa.getAuthenticatorAssuranceLevel(token),
    ]);
  if (factorError || assuranceError) throw new HttpError(503, "Could not verify Owner MFA status.");
  return hasRequiredOwnerMfa(factors, assurance?.currentLevel);
}

async function requireOwner(admin: any, authClient: any, token: string, userId: string) {
  // app_admins has no separate primary-owner flag. Treat the sole active role=owner
  // record as the primary Owner and fail closed if the data contains multiple owners.
  const { data: owners, error } = await admin.from("app_admins")
    .select("user_id").eq("role", "owner").eq("is_active", true);
  if (error) throw error;
  if (!isSoleActiveOwner(owners, userId)) {
    throw new HttpError(403, "Only the single active primary Owner can access evidence review.");
  }
  if (!(await ownerMfaSatisfied(authClient, token))) {
    throw new HttpError(403, "Complete Owner TOTP enrollment and verify MFA (AAL2) before continuing.");
  }
}

async function claimDailyQuota(admin: any, userId: string, requestId: string) {
  const { data, error } = await admin.rpc("claim_work_evidence_quota", {
    p_submitted_by: userId,
    p_request_id: requestId,
  });
  if (error) throw new HttpError(503, "Could not confirm the evidence submission quota.");
  if (data !== true) {
    throw new HttpError(429, "Daily evidence submission limit reached. Try again later.");
  }
}

async function validateRelationship(
  admin: any,
  userId: string,
  origin: string,
  rawJobId: unknown,
  rawContractId: unknown,
  rawSubmissionId: unknown,
) {
  let jobId = optionalUuid(rawJobId, "job_id");
  let contractId = optionalUuid(rawContractId, "contract_id");
  const submissionId = optionalUuid(rawSubmissionId, "submission_id");
  let submissionFreelancerId: string | null = null;
  if (origin === "in_app" && !jobId && !contractId && !submissionId) {
    throw new HttpError(400, "In-app evidence must reference a job, contract, or submission.");
  }

  if (submissionId) {
    const { data: submission, error } = await admin.from("work_submissions")
      .select("id,job_id,contract_id,freelancer_id")
      .eq("id", submissionId).maybeSingle();
    if (error) throw error;
    if (!submission) throw new HttpError(404, "Work submission not found.");
    submissionFreelancerId = submission.freelancer_id;
    if (jobId && jobId !== submission.job_id) throw new HttpError(400, "Submission and job do not match.");
    if (contractId && contractId !== submission.contract_id) {
      throw new HttpError(400, "Submission and contract do not match.");
    }
    // A submission belongs to its freelancer and (through its job) the client.
    // A proposal on the same job is not enough to claim somebody else's submission.
    if (!submission.contract_id && userId !== submission.freelancer_id) {
      const { data: jobOwner, error: jobOwnerError } = await admin.from("jobs")
        .select("owner_id").eq("id", submission.job_id).maybeSingle();
      if (jobOwnerError) throw jobOwnerError;
      if (!jobOwner || !isWorkSubmissionParticipant(userId, submission.freelancer_id, jobOwner.owner_id)) {
        throw new HttpError(403, "You are not a participant in this work submission.");
      }
    }
    jobId = submission.job_id;
    contractId = submission.contract_id;
  }

  if (contractId) {
    const { data: contract, error } = await admin.from("contracts")
      .select("id,job_id,client_id,freelancer_id")
      .eq("id", contractId).maybeSingle();
    if (error) throw error;
    if (!contract) throw new HttpError(404, "Contract not found.");
    if (submissionFreelancerId !== null
      && !submissionMatchesContract(submissionFreelancerId, contract.freelancer_id)) {
      throw new HttpError(403, "This work submission does not belong to the linked contract freelancer.");
    }
    if (!isWorkSubmissionParticipant(userId, contract.freelancer_id, contract.client_id)) {
      throw new HttpError(403, "You are not a participant in this contract.");
    }
    if (jobId && jobId !== contract.job_id) throw new HttpError(400, "Contract and job do not match.");
    jobId = contract.job_id;
  } else if (jobId && !submissionId) {
    const { data: job, error } = await admin.from("jobs")
      .select("id,owner_id").eq("id", jobId).maybeSingle();
    if (error) throw error;
    if (!job) throw new HttpError(404, "Job not found.");
    if (job.owner_id !== userId) {
      const { data: proposal, error: proposalError } = await admin.from("proposals")
        .select("id").eq("job_id", jobId).eq("freelancer_id", userId).limit(1).maybeSingle();
      if (proposalError) throw proposalError;
      if (!proposal) throw new HttpError(403, "You are not linked to this job.");
    }
  }
  return { job_id: jobId, contract_id: contractId, submission_id: submissionId };
}

async function insertEvidence(admin: any, row: Record<string, unknown>) {
  const { data, error } = await admin.from("work_evidence").insert(row).select("id,status,submitted_at").single();
  if (error?.code === "23505") throw new HttpError(409, "This request ID has already been used.");
  if (error) throw error;
  return data;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers });
  if (req.method !== "POST") return reply({ success: false, error: "Method not allowed." }, 405);

  const origin = req.headers.get("Origin");
  if (origin && origin !== OWNER_DASHBOARD_ORIGIN) {
    return reply({ success: false, error: "Origin not allowed." }, 403);
  }
  const contentLength = Number(req.headers.get("Content-Length") || 0);
  if (Number.isFinite(contentLength) && contentLength > MAX_BODY_BYTES) {
    return reply({ success: false, error: "Request body is too large." }, 413);
  }

  try {
    const url = Deno.env.get("SUPABASE_URL");
    const anon = Deno.env.get("SUPABASE_ANON_KEY");
    const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!url || !anon || !service) return reply({ success: false, error: "Server configuration is incomplete." }, 503);

    const token = (req.headers.get("Authorization") || "").match(/^Bearer\s+(.+)$/i)?.[1];
    if (!token) throw new HttpError(401, "Sign in required.");

    const authClient = createClient(url, anon, {
      global: { headers: { Authorization: `Bearer ${token}` } },
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: authData, error: authError } = await authClient.auth.getUser(token);
    if (authError || !authData.user) throw new HttpError(401, "Session invalid or expired.");
    const user = authData.user;
    const admin = createClient(url, service, { auth: { persistSession: false, autoRefreshToken: false } });

    const body = await readJsonLimited(req);
    const action = String(body.action || "");

    if (action === "submit_link" || action === "submit_note" || action === "prepare_upload") {
      const originValue = body.origin === "off_app" ? "off_app" : body.origin === "in_app" ? "in_app" : "";
      if (!originValue) throw new HttpError(400, "origin must be in_app or off_app.");
      const title = cleanText(body.title, 160);
      if (!title) throw new HttpError(400, "A title between 1 and 160 characters is required.");
      const description = requiredDescription(body.description);
      const relation = await validateRelationship(
        admin, user.id, originValue, body.job_id, body.contract_id, body.submission_id,
      );
      if (!isUuid(body.request_id)) throw new HttpError(400, "request_id must be a UUID idempotency key.");

      if (action === "submit_link") {
        const externalUrl = safeHttpsUrl(body.external_url);
        if (!externalUrl) throw new HttpError(400, "Only safe HTTPS evidence links are accepted.");
        await claimDailyQuota(admin, user.id, body.request_id);
        const row = await insertEvidence(admin, {
          request_id: body.request_id, submitted_by: user.id, origin: originValue,
          evidence_type: "link", title, description, ...relation,
          external_url: externalUrl, status: "submitted", submitted_at: new Date().toISOString(),
        });
        return reply({ success: true, evidence: row });
      }

      if (action === "submit_note") {
        if (!description) throw new HttpError(400, "A description is required for note evidence.");
        await claimDailyQuota(admin, user.id, body.request_id);
        const row = await insertEvidence(admin, {
          request_id: body.request_id, submitted_by: user.id, origin: originValue,
          evidence_type: "note", title, description, ...relation,
          status: "submitted", submitted_at: new Date().toISOString(),
        });
        return reply({ success: true, evidence: row });
      }

      const filename = safeOriginalFilename(body.file_name);
      const mime = String(body.mime_type || "").toLowerCase();
      const byteSize = Number(body.byte_size);
      if (!filename) throw new HttpError(400, "File name is invalid.");
      if (!ALLOWED_EVIDENCE_MIME.has(mime)) throw new HttpError(415, "Only PDF, JPEG, PNG, and WebP files are accepted.");
      if (!Number.isInteger(byteSize) || byteSize < 1 || byteSize > MAX_FILE_BYTES) {
        throw new HttpError(413, "File size must be between 1 byte and 10 MB.");
      }
      await claimDailyQuota(admin, user.id, body.request_id);

      const evidenceId = crypto.randomUUID();
      const storagePath = `${user.id}/${evidenceId}/proof`;
      const pending = await insertEvidence(admin, {
        id: evidenceId, request_id: body.request_id, submitted_by: user.id, origin: originValue,
        evidence_type: "file", title, description, ...relation,
        storage_path: storagePath, original_filename: filename, mime_type: mime,
        byte_size: byteSize, status: "upload_pending", submitted_at: null,
      });
      const { data: signed, error: signedError } = await admin.storage.from(BUCKET)
        .createSignedUploadUrl(storagePath, { upsert: false });
      if (signedError || !signed?.signedUrl) {
        await admin.from("work_evidence").update({
          status: "upload_failed", submitted_at: new Date().toISOString(),
        }).eq("id", evidenceId).eq("status", "upload_pending");
        throw new HttpError(503, "Could not prepare secure upload. Please retry with a new request ID.");
      }
      return reply({
        success: true, evidence: pending, bucket: BUCKET, path: storagePath,
        signed_upload_url: signed.signedUrl,
        upload_url_expires_in_seconds: 7200,
      });
    }

    if (action === "retry_upload") {
      if (!isUuid(body.evidence_id)) throw new HttpError(400, "evidence_id must be a UUID.");
      const { data: evidence, error } = await admin.from("work_evidence")
        .select("id,submitted_by,storage_path,status")
        .eq("id", body.evidence_id).eq("submitted_by", user.id).maybeSingle();
      if (error) throw error;
      if (!evidence) throw new HttpError(404, "Evidence upload not found.");
      if (evidence.status !== "upload_pending") throw new HttpError(409, "Evidence is not awaiting upload.");
      const parentPath = `${user.id}/${evidence.id}`;
      const { data: objects, error: listError } = await admin.storage.from(BUCKET)
        .list(parentPath, { limit: 10 });
      if (listError) throw new HttpError(503, "Could not verify the current upload state.");
      if ((objects || []).some((item: any) => item.name === "proof")) {
        throw new HttpError(409, "The file is already uploaded. Call complete_upload instead.");
      }
      const { data: signed, error: signedError } = await admin.storage.from(BUCKET)
        .createSignedUploadUrl(evidence.storage_path, { upsert: false });
      if (signedError || !signed?.signedUrl) throw new HttpError(503, "Could not renew the secure upload URL.");
      return reply({
        success: true, bucket: BUCKET, path: evidence.storage_path,
        signed_upload_url: signed.signedUrl, upload_url_expires_in_seconds: 7200,
      });
    }

    if (action === "complete_upload") {
      if (!isUuid(body.evidence_id)) throw new HttpError(400, "evidence_id must be a UUID.");
      const { data: evidence, error } = await admin.from("work_evidence")
        .select("id,submitted_by,storage_path,mime_type,byte_size,status")
        .eq("id", body.evidence_id).eq("submitted_by", user.id).maybeSingle();
      if (error) throw error;
      if (!evidence) throw new HttpError(404, "Evidence upload not found.");
      if (evidence.status !== "upload_pending") throw new HttpError(409, "Evidence is not awaiting upload.");

      const { data: fileBlob, error: fileError } = await admin.storage.from(BUCKET).download(evidence.storage_path);
      if (fileError || !fileBlob) throw new HttpError(409, "Uploaded file is not available yet.");
      if (fileBlob.size !== Number(evidence.byte_size) || fileBlob.size > MAX_FILE_BYTES) {
        await admin.storage.from(BUCKET).remove([evidence.storage_path]);
        await admin.from("work_evidence").update({
          status: "upload_failed", submitted_at: new Date().toISOString(),
        }).eq("id", evidence.id).eq("status", "upload_pending");
        throw new HttpError(400, "Uploaded file size did not match the declared size.");
      }
      const bytes = new Uint8Array(await fileBlob.arrayBuffer());
      if (!hasExpectedMagic(evidence.mime_type, bytes)) {
        await admin.storage.from(BUCKET).remove([evidence.storage_path]);
        await admin.from("work_evidence").update({
          status: "upload_failed", submitted_at: new Date().toISOString(),
        }).eq("id", evidence.id).eq("status", "upload_pending");
        throw new HttpError(415, "Uploaded file content does not match the allowed file type.");
      }
      const { data: updated, error: updateError } = await admin.from("work_evidence")
        .update({ status: "submitted", submitted_at: new Date().toISOString() })
        .eq("id", evidence.id).eq("submitted_by", user.id).eq("status", "upload_pending")
        .select("id,status,submitted_at").maybeSingle();
      if (updateError) throw updateError;
      if (!updated) throw new HttpError(409, "Upload state changed; refresh your evidence list.");
      return reply({ success: true, evidence: updated });
    }

    if (action === "my_evidence") {
      const page = Math.max(0, Math.min(10000, Number.isInteger(body.page) ? body.page : 0));
      const { data, error } = await admin.from("work_evidence")
        .select("id,origin,evidence_type,title,description,job_id,contract_id,submission_id,status,submitted_at,reviewed_at,review_note,created_at")
        .eq("submitted_by", user.id).order("created_at", { ascending: false })
        .range(page * 50, page * 50 + 49);
      if (error) throw error;
      return reply({ success: true, evidence: data || [], page, page_size: 50 });
    }

    if (action === "owner_list" || action === "owner_review" || action === "owner_download" || action === "owner_events") {
      await requireOwner(admin, authClient, token, user.id);

      if (action === "owner_list") {
        const page = Math.max(0, Math.min(10000, Number.isInteger(body.page) ? body.page : 0));
        let query = admin.from("work_evidence").select("id,submitted_by,origin,evidence_type,title,description,job_id,contract_id,submission_id,external_url,original_filename,mime_type,byte_size,status,submitted_at,reviewed_by,reviewed_at,review_note,created_at,updated_at", { count: "exact" })
          .order("submitted_at", { ascending: false, nullsFirst: false })
          .range(page * 50, page * 50 + 49);
        const allowedStatuses = ["upload_pending", "submitted", "verified", "rejected", "needs_info", "upload_failed"];
        if (body.status !== undefined && body.status !== null && body.status !== "") {
          if (typeof body.status !== "string" || !allowedStatuses.includes(body.status)) {
            throw new HttpError(400, "Unsupported evidence status filter.");
          }
          query = query.eq("status", body.status);
        }
        const { data, error, count } = await query;
        if (error) throw error;
        const ids = [...new Set((data || []).map((item: any) => item.submitted_by))];
        const { data: profiles, error: profileError } = ids.length
          ? await admin.from("profiles").select("id,display_name,country").in("id", ids)
          : { data: [], error: null };
        if (profileError) throw profileError;
        const accounts = await Promise.all(ids.map(async (id: string) => {
          const { data: account, error: accountError } = await admin.auth.admin.getUserById(id);
          if (accountError) throw accountError;
          return { id, email: account.user?.email || null };
        }));
        const jobIds = [...new Set((data || []).map((item: any) => item.job_id).filter(Boolean))];
        const contractIds = [...new Set((data || []).map((item: any) => item.contract_id).filter(Boolean))];
        const [{ data: jobs, error: jobsError }, { data: contracts, error: contractsError }] = await Promise.all([
          jobIds.length ? admin.from("jobs").select("id,title,status").in("id", jobIds) : { data: [], error: null },
          contractIds.length ? admin.from("contracts").select("id,job_id,status,client_id,freelancer_id").in("id", contractIds) : { data: [], error: null },
        ]);
        if (jobsError) throw jobsError;
        if (contractsError) throw contractsError;
        return reply({
          success: true, evidence: data || [], profiles: profiles || [], accounts,
          jobs: jobs || [], contracts: contracts || [],
          total: count || 0, page, page_size: 50,
        });
      }

      if (!isUuid(body.evidence_id)) throw new HttpError(400, "evidence_id must be a UUID.");
      if (action === "owner_events") {
        const { data: events, error: eventsError } = await admin.from("work_evidence_events")
          .select("id,evidence_id,actor_user_id,event_type,from_status,to_status,details,created_at")
          .eq("evidence_id", body.evidence_id).order("created_at", { ascending: true });
        if (eventsError) throw eventsError;
        return reply({ success: true, events: events || [] });
      }
      const { data: evidence, error } = await admin.from("work_evidence")
        .select("id,status,storage_path,original_filename")
        .eq("id", body.evidence_id).maybeSingle();
      if (error) throw error;
      if (!evidence) throw new HttpError(404, "Evidence not found.");

      if (action === "owner_download") {
        if (!evidence.storage_path || evidence.status === "upload_pending" || evidence.status === "upload_failed") {
          throw new HttpError(409, "This evidence file is not available for review.");
        }
        const { data: signed, error: signError } = await admin.storage.from(BUCKET)
          .createSignedUrl(evidence.storage_path, 300);
        if (signError || !signed?.signedUrl) throw new HttpError(503, "Could not create a temporary evidence link.");
        return reply({ success: true, signed_download_url: signed.signedUrl, expires_in_seconds: 300 });
      }

      const newStatus = String(body.status || "");
      if (!["verified", "rejected", "needs_info"].includes(newStatus)) {
        throw new HttpError(400, "Review status must be verified, rejected, or needs_info.");
      }
      if (body.review_note !== undefined && body.review_note !== null
        && (typeof body.review_note !== "string" || body.review_note.length > 2000)) {
        throw new HttpError(400, "Review note must be 2000 characters or fewer.");
      }
      const note = body.review_note === undefined || body.review_note === null ? null : cleanText(body.review_note, 2000);
      if ((newStatus === "rejected" || newStatus === "needs_info") && !note) {
        throw new HttpError(400, "A review note is required for rejected or needs_info evidence.");
      }
      if (!canReviewEvidence(evidence.status, newStatus)) {
        throw new HttpError(409, "Only submitted or needs_info evidence can be reviewed.");
      }
      const { data: updated, error: updateError } = await admin.from("work_evidence")
        .update({
          status: newStatus, reviewed_by: user.id, reviewed_at: new Date().toISOString(),
          review_note: note,
        })
        .eq("id", evidence.id).in("status", ["submitted", "needs_info"])
        .select("id,status,reviewed_by,reviewed_at,review_note").maybeSingle();
      if (updateError) throw updateError;
      if (!updated) throw new HttpError(409, "Evidence status changed; refresh and retry.");
      return reply({ success: true, evidence: updated });
    }

    throw new HttpError(400, "Unsupported action.");
  } catch (error) {
    if (error instanceof HttpError) return reply({ success: false, error: error.message }, error.status);
    console.error("work-evidence-api failed", error);
    return reply({ success: false, error: "Evidence request failed. Check the Edge Function logs." }, 500);
  }
});
