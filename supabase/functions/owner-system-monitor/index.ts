import { createClient } from "https://esm.sh/@supabase/supabase-js@2.80.0";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-monitor-token",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });

type Check = {
  id: string;
  label: string;
  status: "ok" | "attention" | "incomplete" | "info";
  message: string;
  details?: unknown;
};

async function latestWorkflowRun(path: string) {
  const response = await fetch(
    "https://api.github.com/repos/sajibu730-jpg/Freelance---Hub/actions/runs?per_page=50",
    {
      headers: {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
      },
    },
  );
  if (!response.ok) throw new Error(`GitHub API returned HTTP ${response.status}`);
  const payload = await response.json();
  const run = (payload.workflow_runs ?? [])
    .filter((item: any) => String(item.path ?? "").endsWith(path))
    .sort((a: any, b: any) =>
      new Date(b.created_at).getTime() - new Date(a.created_at).getTime()
    )[0];
  return run ?? null;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ success: false, error: "Method not allowed" }, 405);

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!supabaseUrl || !serviceKey) throw new Error("Server configuration is incomplete.");

    const admin = createClient(supabaseUrl, serviceKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });

    const monitorToken = req.headers.get("x-monitor-token");
    let triggerSource = "manual";
    let authorized = false;

    if (monitorToken) {
      const { data, error } = await admin.rpc("verify_owner_monitor_token", {
        p_token: monitorToken,
      });
      if (error) throw new Error("Scheduled monitor authentication could not be verified.");
      authorized = data === true;
      triggerSource = "scheduled";
    } else {
      const authorization = req.headers.get("authorization") ?? "";
      const jwt = authorization.replace(/^Bearer\s+/i, "");
      if (!jwt || jwt === authorization) return json({ success: false, error: "Owner sign-in required." }, 401);
      const { data: userData, error: userError } = await admin.auth.getUser(jwt);
      if (userError || !userData.user) return json({ success: false, error: "Owner sign-in required." }, 401);
      const { data: owner, error: ownerError } = await admin
        .from("app_admins")
        .select("role,is_active")
        .eq("user_id", userData.user.id)
        .maybeSingle();
      if (ownerError) throw ownerError;
      const isOwner = owner?.role === "owner" && owner?.is_active === true;
      if (isOwner) {
        authorized = true;
      } else if (userData.user.email_confirmed_at) {
        const { data: invitedViewer, error: accessError } = await admin
          .from("owner_device_access")
          .select("id,invited_email,status,access_level")
          .eq("invited_user_id", userData.user.id)
          .eq("status", "active")
          .eq("access_level", "viewer")
          .maybeSingle();
        if (accessError) throw accessError;
        authorized = Boolean(
          invitedViewer &&
          String(userData.user.email ?? "").toLowerCase() === invitedViewer.invited_email.toLowerCase()
        );
      }
    }

    if (!authorized) return json({ success: false, error: "Active Owner or invited read-only access required." }, 403);

    const checks: Check[] = [];
    const [ownersResult, requestsResult, configResult] = await Promise.all([
      admin.from("app_admins").select("user_id,role,is_active"),
      admin.from("owner_ai_requests").select("status,created_at").order("created_at", { ascending: false }).limit(1000),
      admin.from("app_remote_config").select("key,is_public").in("key", [
        "app.maintenance.enabled",
        "app.maintenance.message",
      ]),
    ]);

    if (ownersResult.error) {
      checks.push({ id: "owner_access", label: "Owner access record", status: "incomplete", message: "Could not read owner access records." });
    } else {
      const activeOwners = (ownersResult.data ?? []).filter((row: any) =>
        row.role === "owner" && row.is_active === true
      ).length;
      checks.push({
        id: "owner_access",
        label: "Owner access record",
        status: activeOwners === 1 ? "ok" : "attention",
        message: activeOwners === 1 ? "Exactly one active Owner record is present." : `Expected exactly one active Owner record; found ${activeOwners}.`,
        details: { active_owner_count: activeOwners },
      });
    }

    if (requestsResult.error) {
      checks.push({ id: "ai_queue", label: "AI Request Queue", status: "incomplete", message: "Could not read the AI request queue." });
    } else {
      const counts = { draft: 0, approved: 0, rejected: 0, other: 0 };
      for (const row of requestsResult.data ?? []) {
        if (row.status === "draft" || row.status === "approved" || row.status === "rejected") counts[row.status]++;
        else counts.other++;
      }
      checks.push({
        id: "ai_queue",
        label: "AI Request Queue",
        status: "ok",
        message: counts.draft ? `${counts.draft} draft request(s) are waiting for Owner review.` : "No draft AI requests are waiting for review.",
        details: { ...counts, scanned_limit: 1000 },
      });
    }

    if (configResult.error) {
      checks.push({ id: "remote_settings", label: "Remote Settings", status: "incomplete", message: "Could not read the required remote settings." });
    } else {
      const keys = new Set((configResult.data ?? []).map((row: any) => row.key));
      const missing = ["app.maintenance.enabled", "app.maintenance.message"].filter((key) => !keys.has(key));
      checks.push({
        id: "remote_settings",
        label: "Remote Settings",
        status: missing.length ? "attention" : "ok",
        message: missing.length ? `Missing required setting(s): ${missing.join(", ")}.` : "Both supported remote settings are present.",
        details: { checked_keys: 2, missing },
      });
    }

    const [androidRun, dashboardRun] = await Promise.allSettled([
      latestWorkflowRun("main.yml"),
      latestWorkflowRun("deploy-owner-dashboard.yml"),
    ]);

    for (const [id, label, result] of [
      ["android_build", "Latest Android Build", androidRun],
      ["dashboard_deploy", "Latest Owner Dashboard Deploy", dashboardRun],
    ] as const) {
      if (result.status === "rejected") {
        checks.push({ id, label, status: "incomplete", message: "GitHub Actions status could not be retrieved." });
        continue;
      }
      const run = result.value;
      if (!run) {
        checks.push({ id, label, status: "incomplete", message: "No matching workflow run was found." });
        continue;
      }
      const state = run.status === "completed"
        ? (run.conclusion === "success" ? "ok" : "attention")
        : "incomplete";
      checks.push({
        id,
        label,
        status: state,
        message: `${run.name ?? label}: ${run.status}${run.conclusion ? " / " + run.conclusion : ""} (run #${run.run_number}).`,
        details: {
          run_number: run.run_number,
          status: run.status,
          conclusion: run.conclusion,
          created_at: run.created_at,
          html_url: run.html_url,
        },
      });
    }

    checks.push({
      id: "extended_supabase",
      label: "Supabase Logs, Advisors & Function deployment metadata",
      status: "info",
      message: "These Management API sources are not queried by this scheduled backend check; they remain part of the separate daily ChatGPT monitoring report.",
    });

    const hasAttention = checks.some((check) => check.status === "attention");
    const hasIncomplete = checks.some((check) => check.status === "incomplete");
    const status = hasAttention ? "attention" : hasIncomplete ? "incomplete" : "all_clear";
    const summary = status === "all_clear"
      ? "All checks covered by this backend monitor passed. Supabase logs/advisors remain in the separate daily report."
      : status === "attention"
      ? "A problem was detected. Review the checks marked Attention."
      : "Some checks could not be completed. Do not treat this report as an all-clear.";

    const report = {
      checked_at: new Date().toISOString(),
      coverage: "database_and_github_actions",
      status,
      summary,
      checks,
      read_only: true,
      automatic_changes: false,
    };

    const { data: saved, error: saveError } = await admin
      .from("owner_system_reports")
      .insert({
        trigger_source: triggerSource,
        status,
        summary,
        details: report,
      })
      .select("id,created_at,status,summary")
      .single();

    if (saveError) throw saveError;

    return json({ success: true, report: saved, details: report });
  } catch (error) {
    console.error("owner-system-monitor error", error);
    return json({
      success: false,
      error: error instanceof Error ? error.message : "System monitor failed.",
    }, 500);
  }
});
