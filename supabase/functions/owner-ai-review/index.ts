import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...corsHeaders, "Content-Type": "application/json" } });

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ success: false, error: "Method not allowed." }, 405);
  try {
    const token = (req.headers.get("Authorization") ?? "").match(/^Bearer\\s+(.+)$/i)?.[1];
    if (!token) return json({ success: false, error: "Sign in is required." }, 401);
    const url = Deno.env.get("SUPABASE_URL");
    const anon = Deno.env.get("SUPABASE_ANON_KEY");
    const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!url || !anon || !service) return json({ success: false, error: "Server configuration is incomplete." }, 503);
    const userClient = createClient(url, anon, { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: authData, error: authError } = await userClient.auth.getUser(token);
    if (authError || !authData.user) return json({ success: false, error: "Session invalid or expired." }, 401);
    const admin = createClient(url, service, { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: owner, error: ownerError } = await admin.from("app_admins").select("role,is_active").eq("user_id", authData.user.id).maybeSingle();
    if (ownerError || !owner || owner.role !== "owner" || owner.is_active !== true) return json({ success: false, error: "Owner access is required." }, 403);
    const body = await req.json().catch(() => ({}));
    const requestId = String(body.request_id ?? "");
    const decision = String(body.decision ?? "");
    if (!/^[0-9a-f-]{36}$/i.test(requestId) || !["approve", "reject"].includes(decision)) return json({ success: false, error: "Invalid review request." }, 400);
    const { data: item, error: itemError } = await admin.from("owner_ai_requests").select("id,proposal,status").eq("id", requestId).single();
    if (itemError || !item) return json({ success: false, error: "AI request was not found." }, 404);
    if (item.status !== "draft") return json({ success: false, error: "This request has already been reviewed." }, 409);
    let applied = false;
    if (decision === "approve" && item.proposal && typeof item.proposal === "object") {
      const proposal = item.proposal as Record<string, unknown>;
      const changes = Array.isArray(proposal.changes) ? proposal.changes as Array<Record<string, unknown>> : [];
      const allowed = new Set(["app.maintenance.enabled", "app.maintenance.message"]);
      const safeChanges = changes.length > 0 && changes.every((change) => {
        const key = String(change.key ?? "");
        if (!allowed.has(key)) return false;
        if (key === "app.maintenance.enabled") return typeof change.value === "boolean";
        return key === "app.maintenance.message" && typeof change.value === "string" && change.value.length <= 500;
      });
      if (proposal.category === "remote_config" && safeChanges) {
        const rows = changes.map((change) => ({
          key: String(change.key),
          value: change.value,
          is_public: true,
          description: "Approved by the owner from the separate Owner Dashboard",
          updated_at: new Date().toISOString(),
        }));
        const { error: configError } = await admin.from("app_remote_config").upsert(rows, { onConflict: "key" });
        if (configError) {
          console.error("Remote config apply failed", configError.message);
          return json({ success: false, error: "Could not apply the approved remote setting." }, 500);
        }
        applied = true;
      }
    }
    const { error: updateError } = await admin.from("owner_ai_requests")
      .update({ status: decision === "approve" ? "approved" : "rejected", reviewed_at: new Date().toISOString() })
      .eq("id", requestId).eq("status", "draft");
    if (updateError) return json({ success: false, error: "Could not save the review decision." }, 500);
    return json({ success: true, request_id: requestId, status: decision === "approve" ? "approved" : "rejected", applied });
  } catch (error) {
    console.error("Owner AI review error", error instanceof Error ? error.message : "unknown");
    return json({ success: false, error: "Review request failed." }, 500);
  }
});
