# Freelance Hub mobile OTP channels

## Current production state

- Email signup OTP remains enabled and uses the existing 8-digit email flow.
- Mobile numbers are required at signup and saved in `profiles.mobile_number`.
- Mobile numbers are marked `mobile_verified = false` until an OTP is successfully verified.
- Both paid mobile OTP channels are OFF:
  - `signup_sms_otp_enabled = false`
  - `signup_whatsapp_otp_enabled = false`
- The Edge Function checks the server-side flag before calling Twilio Verify, so a disabled channel cannot trigger a paid send request.

## Future activation

The owner can enable one channel by changing its row in `public.app_feature_flags`:

```sql
UPDATE public.app_feature_flags
SET enabled = true, updated_at = now()
WHERE key = 'signup_sms_otp_enabled';

-- Or enable WhatsApp:
UPDATE public.app_feature_flags
SET enabled = true, updated_at = now()
WHERE key = 'signup_whatsapp_otp_enabled';
```

Turn a channel off by setting `enabled = false`. The future Admin Dashboard should expose these two settings to the owner only; ordinary app users must never be allowed to update feature flags.

## WhatsApp prerequisites

Twilio Verify supports `Channel=whatsapp`, but WhatsApp OTP requires a WhatsApp Business sender registered/configured for the Twilio Verify service. Do not enable the WhatsApp flag until the sender, account billing, country availability, and message costs have been checked. See the official Twilio guide: https://www.twilio.com/docs/verify/whatsapp

## Important

Do not put Twilio Account SID, Auth Token, service credentials, or Supabase service-role keys in the Android app. Keep provider credentials in Supabase Edge Function secrets. Email OTP remains independent from both mobile channels.
