ALTER TABLE public.profiles
  ADD COLUMN IF NOT EXISTS mobile_verified boolean NOT NULL DEFAULT false;

UPDATE public.profiles p
SET mobile_verified = true
FROM auth.users u
WHERE u.id = p.id
  AND u.phone_confirmed_at IS NOT NULL
  AND regexp_replace(coalesce(u.phone, ''), '\\D', '', 'g')
      = regexp_replace(coalesce(p.mobile_number, ''), '\\D', '', 'g')
  AND p.mobile_verified IS DISTINCT FROM true;

CREATE OR REPLACE FUNCTION public.enforce_profile_mobile_change_cooldown()
RETURNS trigger
LANGUAGE plpgsql
SET search_path TO 'pg_catalog', 'public', 'auth'
AS $function$
DECLARE
  verified_phone text;
  phone_is_verified boolean;
BEGIN
  SELECT u.phone, (u.phone_confirmed_at IS NOT NULL)
    INTO verified_phone, phone_is_verified
  FROM auth.users u
  WHERE u.id = NEW.id;

  IF TG_OP = 'INSERT' THEN
    IF NEW.mobile_number IS NOT NULL AND btrim(NEW.mobile_number) <> ''
       AND phone_is_verified IS TRUE
       AND verified_phone IS NOT DISTINCT FROM NEW.mobile_number THEN
      NEW.mobile_verified := true;
      NEW.mobile_number_changed_at := COALESCE(NEW.mobile_number_changed_at, now());
    ELSE
      NEW.mobile_verified := false;
      NEW.mobile_number_changed_at := NULL;
    END IF;
    RETURN NEW;
  END IF;

  NEW.mobile_number_changed_at := OLD.mobile_number_changed_at;

  IF NEW.mobile_number IS DISTINCT FROM OLD.mobile_number THEN
    IF OLD.mobile_number_changed_at IS NOT NULL
       AND now() < OLD.mobile_number_changed_at + interval '6 months' THEN
      RAISE EXCEPTION 'Mobile number can only be changed once every six months'
        USING errcode = 'P0001';
    END IF;

    IF phone_is_verified IS TRUE
       AND verified_phone IS NOT DISTINCT FROM NEW.mobile_number THEN
      NEW.mobile_verified := true;
      NEW.mobile_number_changed_at := now();
    ELSIF OLD.mobile_number IS NULL
          AND OLD.mobile_number_changed_at IS NULL
          AND NEW.mobile_number IS NOT NULL
          AND btrim(NEW.mobile_number) <> '' THEN
      -- First-time signup may save a number without verifying it.
      -- The six-month change lock starts only after successful verification.
      NEW.mobile_verified := false;
      NEW.mobile_number_changed_at := NULL;
    ELSE
      RAISE EXCEPTION 'Mobile number must be verified before saving to the profile'
        USING errcode = 'P0001';
    END IF;
  ELSIF NEW.mobile_verified IS DISTINCT FROM OLD.mobile_verified THEN
    IF NEW.mobile_verified IS TRUE THEN
      IF phone_is_verified IS TRUE
         AND verified_phone IS NOT DISTINCT FROM NEW.mobile_number THEN
        NEW.mobile_number_changed_at := COALESCE(OLD.mobile_number_changed_at, now());
      ELSE
        RAISE EXCEPTION 'Mobile number must be verified before marking it as verified'
          USING errcode = 'P0001';
      END IF;
    ELSE
      NEW.mobile_number_changed_at := OLD.mobile_number_changed_at;
    END IF;
  END IF;

  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS profiles_mobile_change_cooldown ON public.profiles;
CREATE TRIGGER profiles_mobile_change_cooldown
BEFORE INSERT OR UPDATE ON public.profiles
FOR EACH ROW EXECUTE FUNCTION public.enforce_profile_mobile_change_cooldown();

INSERT INTO public.app_feature_flags (key, enabled)
VALUES
  ('signup_sms_otp_enabled', false),
  ('signup_whatsapp_otp_enabled', false)
ON CONFLICT (key) DO NOTHING;

GRANT SELECT ON public.app_feature_flags TO anon, authenticated;
