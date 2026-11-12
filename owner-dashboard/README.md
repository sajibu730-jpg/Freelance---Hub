# Freelance Hub Owner Dashboard

A separate, mobile-friendly web console. It is not part of the Android app.

## Deployment

The GitHub Actions workflow `.github/workflows/deploy-owner-dashboard.yml` publishes this folder to GitHub Pages.

Expected URL: https://sajibu730-jpg.github.io/Freelance---Hub/

If the first deployment reports that Pages is not enabled, open repository **Settings → Pages → Build and deployment**, choose **GitHub Actions**, then re-run the workflow.

## Access and security

- Sign in with the existing Supabase Auth owner account.
- The page verifies the signed-in user against `public.app_admins` and requires `role = owner` and `is_active = true`.
- The browser contains only the Supabase publishable key. Never put a Supabase secret/service-role key or Gemini key in this folder.
- AI requests are saved in `public.owner_ai_requests`; the queue is protected by owner-only RLS.
- Review decisions are processed by the `owner-ai-review` Edge Function, which independently validates the JWT and active owner record.
- Approval applies only the two allowlisted remote settings: `app.maintenance.enabled` and `app.maintenance.message`. Other proposals are approved for planning only and do not deploy code or add app features.

## Important

Remote settings can only change behavior already implemented in the installed app. New Android features still require a new APK release.


## System monitoring

- The dashboard includes a **System Monitoring** panel with the latest 20 owner-only reports and a manual read-only check.
- Supabase Cron runs the backend monitor daily at 14:00 UTC (18:00 UAE time). Reports are stored in `public.owner_system_reports`.
- The scheduled backend checks the active Owner record, AI Request Queue, required Remote Settings, latest Android Build, and latest Owner Dashboard deployment.
- Supabase logs, Security/Performance/Health Advisors, and Edge Function deployment metadata are not part of this backend snapshot; the separate daily ChatGPT monitor continues to check those sources.
- The monitor never changes code, database records (other than saving its own report), settings, or deployments. It does not approve or reject AI requests.
- The scheduled invocation token is stored in Supabase Vault and is not exposed in the dashboard or Android app.

## Invited Dashboard access

- The primary Owner can open **Device Access** and invite a separate email account.
- Supabase Auth sends the invitation email. The invited person accepts it, confirms the email, sets their password, and then signs in at the Owner Dashboard URL.
- Invited accounts receive **read-only Viewer** access to System Monitoring only. They cannot access Owner AI, review/approve AI requests, or change Remote Settings.
- The Owner can see invitation status and the last browser-reported device label, and can revoke pending or active access at any time.
- This is account-based access, not a hardware lock: the invited account can sign in from another phone too. Revoking the invitation disables that account's Dashboard access across devices.
- The Auth email template and SMTP delivery must be configured. For the invitation link to return directly to GitHub Pages, allowlist `https://sajibu730-jpg.github.io/Freelance---Hub/?invite=1` in Supabase Authentication → URL Configuration → Redirect URLs. If it is not allowlisted, the recipient can still accept the invitation and then open the Dashboard URL manually.
