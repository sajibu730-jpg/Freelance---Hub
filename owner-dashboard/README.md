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
