import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "Content-Type": "application/json" },
  });
}

async function fingerprint(value: string, secret: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "HMAC",
    key,
    new TextEncoder().encode(value),
  );
  return Array.from(new Uint8Array(signature))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

async function checkPhoneDuplicate(
  req: Request,
  phone: string,
  supabaseUrl: string,
  serviceRole: string,
  userId: string,
): Promise<{ allowed: boolean; duplicate: boolean }> {
  const forwarded = req.headers.get("x-forwarded-for") ?? "";
  const ip = req.headers.get("cf-connecting-ip") ??
    req.headers.get("x-real-ip") ??
    forwarded.split(",").at(-1)?.trim() ??
    "unknown";
  const [ipHash, identifierHash] = await Promise.all([
    fingerprint("ip:" + ip, serviceRole),
    fingerprint("phone:" + phone, serviceRole),
  ]);
  const response = await fetch(supabaseUrl + "/rest/v1/rpc/check_signup_identifier", {
    method: "POST",
    headers: {
      apikey: serviceRole,
      Authorization: "Bearer " + serviceRole,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      p_kind: "phone",
      p_value: phone,
      p_ip_hash: ipHash,
      p_identifier_hash: identifierHash,
      p_user_id: userId,
    }),
  });
  if (!response.ok) {
    console.error("Signup phone duplicate check failed", response.status);
    throw new Error("Duplicate check unavailable.");
  }
  const result = await response.json();
  return {
    allowed: result?.allowed === true,
    duplicate: result?.duplicate === true,
  };
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json(405, { error: "Method not allowed." });

  const authorization = req.headers.get("Authorization") ?? "";
  const jwt = authorization.replace(/^Bearer\s+/i, "");
  if (!jwt || jwt === authorization) return json(401, { error: "Sign in to verify your signup." });

  const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? Deno.env.get("SUPABASE_PUBLISHABLE_KEY") ?? "";
  const serviceRole = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const accountSid = Deno.env.get("TWILIO_ACCOUNT_SID") ?? "";
  const authToken = Deno.env.get("TWILIO_AUTH_TOKEN") ?? "";
  const serviceSid = Deno.env.get("TWILIO_VERIFY_SERVICE_SID") ?? "";

  if (!anonKey || !serviceRole || !accountSid || !authToken || !serviceSid) {
    return json(503, { error: "Signup mobile verification is not configured." });
  }

  const userResponse = await fetch(supabaseUrl + "/auth/v1/user", {
    headers: { apikey: anonKey, Authorization: "Bearer " + jwt },
  });
  if (!userResponse.ok) return json(401, { error: "Your signup session is invalid or expired." });
  const user = await userResponse.json();
  if (!user?.id || !user?.email_confirmed_at) {
    return json(403, { error: "Verify your email address before verifying your mobile number." });
  }

  let body: { action?: string; phone?: string; code?: string; channel?: string };
  try { body = await req.json(); } catch { return json(400, { error: "Invalid request." }); }
  const action = body.action;
  const phone = (body.phone ?? "").trim();
  const channel = (body.channel ?? "sms").trim().toLowerCase();
  if (!/^\+[1-9][0-9]{7,14}$/.test(phone)) {
    return json(400, { error: "Enter a valid mobile number with country code." });
  }
  if (action !== "send" && action !== "verify") {
    return json(400, { error: "Unsupported verification action." });
  }
  if (channel !== "sms" && channel !== "whatsapp") {
    return json(400, { error: "Choose SMS or WhatsApp verification." });
  }
  if (action === "verify" && !/^\d{8}$/.test((body.code ?? "").trim())) {
    return json(400, { error: "Enter the 8-digit verification code." });
  }

  // Check the server-controlled flag before any paid provider request.
  if (action === "send") {
    const flagKey = channel === "whatsapp"
      ? "signup_whatsapp_otp_enabled"
      : "signup_sms_otp_enabled";
    const flagResponse = await fetch(
      supabaseUrl + "/rest/v1/app_feature_flags?key=eq." + flagKey + "&select=enabled",
      { headers: { apikey: serviceRole, Authorization: "Bearer " + serviceRole } },
    );
    if (!flagResponse.ok) {
      console.error("Could not read signup OTP feature flag", flagResponse.status);
      return json(503, { error: "Mobile verification settings are temporarily unavailable." });
    }
    const flagRows = await flagResponse.json().catch(() => []);
    if (!Array.isArray(flagRows) || flagRows.length !== 1 || flagRows[0]?.enabled !== true) {
      return json(403, {
        error: channel === "whatsapp"
          ? "WhatsApp verification is currently disabled."
          : "SMS verification is currently disabled.",
      });
    }
  }

  let duplicateCheck: { allowed: boolean; duplicate: boolean };
  try {
    duplicateCheck = await checkPhoneDuplicate(req, phone, supabaseUrl, serviceRole, user.id);
  } catch {
    return json(503, { error: "মোবাইল নম্বর যাচাই করা যাচ্ছে না। কিছুক্ষণ পরে আবার চেষ্টা করুন।" });
  }
  if (!duplicateCheck.allowed) {
    return json(429, { error: "অনেকবার যাচাইয়ের চেষ্টা হয়েছে। কিছুক্ষণ পরে আবার চেষ্টা করুন।" });
  }
  if (duplicateCheck.duplicate) {
    return json(409, { error: "এই মোবাইল নম্বরটি আগে ব্যবহার করা হয়েছে। নতুন অ্যাকাউন্ট খুলতে অন্য নম্বর ব্যবহার করুন।" });
  }

  const basic = btoa(accountSid + ":" + authToken);
  const endpoint = "https://verify.twilio.com/v2/Services/" + serviceSid + "/Verifications";
  const checkEndpoint = "https://verify.twilio.com/v2/Services/" + serviceSid + "/VerificationCheck";
  const form = new URLSearchParams();
  if (action === "send") {
    form.set("To", phone);
    form.set("Channel", channel);
  } else {
    form.set("To", phone);
    form.set("Code", (body.code ?? "").trim());
  }

  const response = await fetch(action === "send" ? endpoint : checkEndpoint, {
    method: "POST",
    headers: {
      Authorization: "Basic " + basic,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: form,
  });
  const result = await response.json().catch(() => ({}));
  if (!response.ok) {
    console.error("Twilio signup verification failed", response.status, result?.code ?? "unknown");
    return json(502, { error: action === "send"
      ? (channel === "whatsapp"
        ? "WhatsApp code could not be sent. Check the number and WhatsApp availability."
        : "SMS could not be sent. Check the number and try again.")
      : "The verification code could not be checked. Request a new code if it has expired." });
  }

  if (action === "send") {
    return json(200, { sent: true, status: result.status ?? "pending", codeLength: 8, channel });
  }

  if (result.status !== "approved") {
    return json(400, { error: "The 8-digit verification code is incorrect or expired." });
  }

  const userEndpoint = supabaseUrl + "/auth/v1/admin/users/" + encodeURIComponent(user.id);
  const authUpdateResponse = await fetch(userEndpoint, {
    method: "PUT",
    headers: {
      apikey: serviceRole,
      Authorization: "Bearer " + serviceRole,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ phone, phone_confirm: true }),
  });
  if (!authUpdateResponse.ok) {
    console.error("Could not update verified signup phone in Supabase Auth", authUpdateResponse.status);
    return json(500, { error: "SMS code was accepted, but Supabase could not confirm the mobile number. Please retry." });
  }

  const confirmedUserResponse = await fetch(userEndpoint, {
    headers: {
      apikey: serviceRole,
      Authorization: "Bearer " + serviceRole,
    },
  });
  const confirmedUser = await confirmedUserResponse.json().catch(() => ({}));
  const normalizePhone = (value: unknown) => String(value ?? "").replace(/\D/g, "");
  const authPhoneConfirmed =
    confirmedUserResponse.ok &&
    normalizePhone(confirmedUser?.phone) === normalizePhone(phone) &&
    Boolean(confirmedUser?.phone_confirmed_at);

  if (!authPhoneConfirmed) {
    console.error(
      "Verified signup phone is not confirmed in Supabase Auth",
      confirmedUserResponse.status,
      Boolean(confirmedUser?.phone),
      Boolean(confirmedUser?.phone_confirmed_at),
    );
    return json(500, { error: "SMS code was accepted, but Supabase could not confirm the mobile number. Please retry." });
  }

  const profileResponse = await fetch(
    supabaseUrl + "/rest/v1/profiles?on_conflict=id",
    {
      method: "POST",
      headers: {
        apikey: serviceRole,
        Authorization: "Bearer " + serviceRole,
        "Content-Type": "application/json",
        Prefer: "resolution=merge-duplicates,return=representation",
      },
      body: JSON.stringify({
        id: user.id,
        mobile_number: phone,
        mobile_verified: true,
        updated_at: new Date().toISOString(),
      }),
    },
  );
  const savedProfiles = await profileResponse.json().catch(() => []);
  if (!profileResponse.ok || !Array.isArray(savedProfiles) || savedProfiles.length !== 1 ||
      normalizePhone(savedProfiles[0]?.mobile_number) !== normalizePhone(phone)) {
    console.error("Could not persist verified signup phone", profileResponse.status);
    return json(500, { error: "Mobile number was verified, but the profile could not be saved. Please retry." });
  }
  return json(200, { verified: true, phone, profileSaved: true, channel });
});
