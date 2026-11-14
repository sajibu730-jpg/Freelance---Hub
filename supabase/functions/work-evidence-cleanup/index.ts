import { createClient } from "npm:@supabase/supabase-js@2";
import { constantTimeEqual, isExpectedEvidenceStoragePath } from "./validation.ts";

const BUCKET = "work-evidence-private";
const BATCH_SIZE = 50;
function reply(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
    },
  });
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return reply({ success: false, error: "Method not allowed." }, 405);

  const expectedSecret = Deno.env.get("WORK_EVIDENCE_CLEANUP_SECRET");
  const suppliedSecret = req.headers.get("x-work-evidence-cleanup-secret") || "";
  if (!expectedSecret) {
    return reply({ success: false, error: "Cleanup worker is not configured." }, 503);
  }
  if (!constantTimeEqual(suppliedSecret, expectedSecret)) {
    return reply({ success: false, error: "Unauthorized." }, 401);
  }

  const url = Deno.env.get("SUPABASE_URL");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !serviceRoleKey) {
    return reply({ success: false, error: "Server configuration is incomplete." }, 503);
  }
  const bearer = req.headers.get("Authorization")?.match(/^Bearer\s+(.+)$/i)?.[1] || "";
  if (!constantTimeEqual(bearer, serviceRoleKey)) {
    return reply({ success: false, error: "Unauthorized." }, 401);
  }

  const admin = createClient(url, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  try {
    const { data: jobs, error: claimError } = await admin.rpc(
      "claim_work_evidence_cleanup_batch",
      { p_limit: BATCH_SIZE },
    );
    if (claimError) throw claimError;

    let removed = 0;
    let alreadyAbsent = 0;
    let retried = 0;
    let completed = 0;

    for (const job of jobs || []) {
      try {
        if (!isExpectedEvidenceStoragePath(job.storage_path)) {
          throw new Error("Storage path failed the expected evidence-path check.");
        }

        const segments = job.storage_path.split("/");
        const parentPath = `${segments[0]}/${segments[1]}`;
        const { data: objects, error: listError } = await admin.storage
          .from(BUCKET)
          .list(parentPath, { limit: 10 });
        if (listError) throw listError;

        const proofExists = (objects || []).some((item: { name?: string }) => item.name === "proof");
        if (proofExists) {
          const { error: removeError } = await admin.storage.from(BUCKET).remove([job.storage_path]);
          if (removeError) throw removeError;
          removed++;
        } else {
          alreadyAbsent++;
        }

        const { data: finished, error: finishError } = await admin.rpc(
          "finish_work_evidence_cleanup",
          {
            p_evidence_id: job.evidence_id,
            p_succeeded: true,
            p_error: null,
          },
        );
        if (finishError || finished !== true) throw finishError || new Error("Cleanup lease could not be finalized.");
        completed++;
      } catch {
        // Never persist raw provider errors, object metadata, credentials, or signed URLs.
        const { error: retryError } = await admin.rpc("finish_work_evidence_cleanup", {
          p_evidence_id: job.evidence_id,
          p_succeeded: false,
          p_error: "Private Storage cleanup failed; retry scheduled.",
        });
        if (retryError) console.error("Could not release a failed evidence cleanup lease.");
        retried++;
      }
    }

    return reply({
      success: true,
      claimed: (jobs || []).length,
      completed,
      removed,
      already_absent: alreadyAbsent,
      retried,
    });
  } catch {
    console.error("Work evidence cleanup batch failed.");
    return reply({ success: false, error: "Cleanup batch failed; it can be retried." }, 503);
  }
});
