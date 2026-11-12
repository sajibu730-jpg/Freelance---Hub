import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const DASHBOARD_ORIGIN = "https://sajibu730-jpg.github.io";
const corsHeaders = {
  "Access-Control-Allow-Origin": DASHBOARD_ORIGIN,
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Vary": "Origin",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...corsHeaders, "Content-Type": "application/json" } });

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ success: false, error: "Method not allowed." }, 405);

  try {
    const authorization = req.headers.get("Authorization") ?? "";
    const token = authorization.match(/^Bearer\s+(.+)$/i)?.[1];
    if (!token) return json({ success: false, error: "Sign in is required." }, 401);

    const url = Deno.env.get("SUPABASE_URL");
    const anon = Deno.env.get("SUPABASE_ANON_KEY");
    const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!url || !anon || !service) return json({ success: false, error: "Server configuration is incomplete." }, 503);

    const userClient = createClient(url, anon, { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: authData, error: authError } = await userClient.auth.getUser(token);
    if (authError || !authData.user) return json({ success: false, error: "Session invalid or expired." }, 401);

    const admin = createClient(url, service, { auth: { persistSession: false, autoRefreshToken: false } });
    const user = authData.user;
    const { data: owner, error: ownerError } = await admin.from("app_admins")
      .select("role,is_active").eq("user_id", user.id).maybeSingle();
    if (ownerError) throw ownerError;
    const isOwner = owner?.role === "owner" && owner?.is_active === true;

    const body = await req.json().catch(() => ({}));
    const action = String(body.action ?? "");

    if (action === "verify") {
      if (isOwner) return json({ success: true, access: { role: "owner", label: "Owner access" } });
      const { data: grant, error } = await admin.from("owner_device_access")
        .select("id,owner_user_id,invited_email,access_level,status")
        .eq("invited_user_id", user.id).maybeSingle();
      if (error) throw error;
      if (!grant || String(user.email ?? "").toLowerCase() !== grant.invited_email.toLowerCase()) {
        return json({ success: false, error: "This account has no Owner Dashboard invitation." }, 403);
      }
      if (grant.status === "revoked") return json({ success: false, error: "This Dashboard access has been revoked by the Owner." }, 403);
      if (!user.email_confirmed_at) return json({ success: false, error: "Please accept the invitation from your email first, then sign in again." }, 403);
      if (grant.status === "pending") {
        const { error: activateError } = await admin.from("owner_device_access").update({
          status: "active", accepted_at: new Date().toISOString(), last_seen_at: new Date().toISOString(),
          last_device_label: String(body.device_label ?? "Mobile browser").slice(0, 80),
        }).eq("id", grant.id).eq("status", "pending");
        if (activateError) throw activateError;
      } else {
        const { error: seenError } = await admin.from("owner_device_access").update({
          last_seen_at: new Date().toISOString(),
          last_device_label: String(body.device_label ?? "Mobile browser").slice(0, 80),
        }).eq("id", grant.id).eq("status", "active");
        if (seenError) throw seenError;
      }
      return json({ success: true, access: { role: "viewer", label: "Read-only invited access" } });
    }

    if (!isOwner) return json({ success: false, error: "Only the primary Owner can manage invitations." }, 403);

    if (action === "list") {
      const { data, error } = await admin.from("owner_device_access")
        .select("id,invited_email,access_level,status,invited_at,accepted_at,revoked_at,last_seen_at,last_device_label")
        .eq("owner_user_id", user.id).order("invited_at", { ascending: false }).limit(50);
      if (error) throw error;
      return json({ success: true, invitations: data ?? [] });
    }

    if (action === "invite") {
      const email = String(body.email ?? "").trim().toLowerCase();
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254) {
        return json({ success: false, error: "Enter a valid email address." }, 400);
      }
      if (email === String(user.email ?? "").toLowerCase()) {
        return json({ success: false, error: "Your primary Owner email is already the owner account." }, 400);
      }
      const { data: existing, error: existingError } = await admin.from("owner_device_access")
        .select("id,status").eq("owner_user_id", user.id).eq("invited_email", email).maybeSingle();
      if (existingError) throw existingError;
      if (existing && existing.status !== "revoked") {
        return json({ success: false, error: "An invitation or active access already exists for this email." }, 409);
      }
      if (existing?.status === "revoked") {
        const { error: deleteError } = await admin.from("owner_device_access").delete().eq("id", existing.id);
        if (deleteError) throw deleteError;
      }

      const { data: pending, error: insertError } = await admin.from("owner_device_access").insert({
        owner_user_id: user.id, invited_email: email, access_level: "viewer", status: "pending",
      }).select("id").single();
      if (insertError || !pending) {
        if (insertError?.code === "23505") return json({ success: false, error: "An invitation already exists for this email." }, 409);
        throw insertError ?? new Error("Could not create invitation.");
      }

      const { data: invited, error: inviteError } = await admin.auth.admin.inviteUserByEmail(email, {
        redirectTo: "https://sajibu730-jpg.github.io/Freelance---Hub/?invite=1",
      });
      if (inviteError || !invited.user) {
        await admin.from("owner_device_access").delete().eq("id", pending.id);
        const message = inviteError?.message ?? "Invitation email could not be sent.";
        if (/already|registered|exists/i.test(message)) {
          return json({ success: false, error: "This email already has a Supabase account. Use a new email address for a separate invited account." }, 409);
        }
        console.error("Owner dashboard invite email failed", message);
        return json({ success: false, error: "Invitation email could not be sent. Check Auth email/SMTP settings and try again." }, 502);
      }

      const { error: linkError } = await admin.from("owner_device_access")
        .update({ invited_user_id: invited.user.id }).eq("id", pending.id);
      if (linkError) throw linkError;
      return json({ success: true, invitation: { id: pending.id, email, status: "pending", access_level: "viewer" }, message: "Invitation email sent." });
    }

    if (action === "revoke") {
      const id = String(body.invitation_id ?? "");
      if (!/^[0-9a-f-]{36}$/i.test(id)) return json({ success: false, error: "Invalid invitation." }, 400);
      const { data, error } = await admin.from("owner_device_access").update({
        status: "revoked", revoked_at: new Date().toISOString(),
      }).eq("id", id).eq("owner_user_id", user.id).in("status", ["pending", "active"])
        .select("id,status").maybeSingle();
      if (error) throw error;
      if (!data) return json({ success: false, error: "Invitation not found or already revoked." }, 404);
      return json({ success: true, invitation: data, message: "Dashboard access revoked." });
    }

    return json({ success: false, error: "Unknown action." }, 400);
  } catch (error) {
    console.error("owner-device-access error", error instanceof Error ? error.message : "unknown");
    return json({ success: false, error: "Device access request failed. Please try again." }, 500);
  }
});
