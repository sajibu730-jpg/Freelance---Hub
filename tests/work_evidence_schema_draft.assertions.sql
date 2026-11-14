\set ON_ERROR_STOP on

DO $$
BEGIN
  IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.work_evidence'::regclass) THEN
    RAISE EXCEPTION 'work_evidence RLS is not enabled';
  END IF;
  IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.work_evidence_events'::regclass) THEN
    RAISE EXCEPTION 'work_evidence_events RLS is not enabled';
  END IF;
  IF has_table_privilege('anon', 'public.work_evidence', 'SELECT')
    OR has_table_privilege('authenticated', 'public.work_evidence', 'SELECT')
    OR has_table_privilege('anon', 'public.work_evidence_events', 'SELECT')
    OR has_table_privilege('authenticated', 'public.work_evidence_events', 'SELECT') THEN
    RAISE EXCEPTION 'Evidence tables are directly readable by client roles';
  END IF;
  IF NOT has_table_privilege('service_role', 'public.work_evidence', 'SELECT')
    OR NOT has_table_privilege('service_role', 'public.work_evidence', 'INSERT')
    OR NOT has_table_privilege('service_role', 'public.work_evidence', 'UPDATE')
    OR NOT has_table_privilege('service_role', 'public.work_evidence_events', 'INSERT') THEN
    RAISE EXCEPTION 'Edge Function service role grants are incomplete';
  END IF;
  IF has_table_privilege('anon', 'public.work_evidence_quota_events', 'SELECT')
    OR has_table_privilege('authenticated', 'public.work_evidence_quota_events', 'SELECT')
    OR has_table_privilege('service_role', 'public.work_evidence_quota_events', 'SELECT')
    OR has_table_privilege('service_role', 'public.work_evidence_quota_events', 'INSERT')
    OR NOT has_function_privilege('service_role', 'public.claim_work_evidence_quota(uuid,uuid)', 'EXECUTE')
    OR has_function_privilege('anon', 'public.claim_work_evidence_quota(uuid,uuid)', 'EXECUTE')
    OR has_function_privilege('authenticated', 'public.claim_work_evidence_quota(uuid,uuid)', 'EXECUTE') THEN
    RAISE EXCEPTION 'Evidence quota ledger or RPC privileges are unsafe';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM storage.buckets
    WHERE id='work-evidence-private' AND public=false AND file_size_limit=10485760
  ) THEN
    RAISE EXCEPTION 'Private evidence bucket configuration is missing or unsafe';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname='work_evidence_audit' AND NOT tgisinternal)
    OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname='work_evidence_events_no_update' AND NOT tgisinternal) THEN
    RAISE EXCEPTION 'Evidence audit or append-only trigger is missing';
  END IF;
END;
$$;

INSERT INTO auth.users(id) VALUES
  ('11111111-1111-4111-8111-111111111111'),
  ('33333333-3333-4333-8333-333333333333');

-- Exercise the atomic quota RPC through the only permitted API role.
SET ROLE service_role;
DO $quota_test$
DECLARE
  i integer;
  v_request uuid;
  v_first_request uuid;
BEGIN
  FOR i IN 1..20 LOOP
    v_request := gen_random_uuid();
    IF i = 1 THEN v_first_request := v_request; END IF;
    IF NOT public.claim_work_evidence_quota(
      '11111111-1111-4111-8111-111111111111', v_request
    ) THEN
      RAISE EXCEPTION 'Evidence quota rejected an allowed submission at slot %', i;
    END IF;
  END LOOP;

  IF NOT public.claim_work_evidence_quota(
    '11111111-1111-4111-8111-111111111111', v_first_request
  ) THEN
    RAISE EXCEPTION 'Idempotent retry should not consume another quota slot';
  END IF;

  IF public.claim_work_evidence_quota(
    '11111111-1111-4111-8111-111111111111',
    '44444444-4444-4444-8444-444444444444'
  ) THEN
    RAISE EXCEPTION 'Twenty-first evidence submission unexpectedly passed';
  END IF;

  IF NOT public.claim_work_evidence_quota(
    '33333333-3333-4333-8333-333333333333',
    '55555555-5555-4555-8555-555555555555'
  ) THEN
    RAISE EXCEPTION 'Quota should be isolated per user';
  END IF;
END;
$quota_test$;
RESET ROLE;



INSERT INTO public.work_evidence (
  request_id, submitted_by, origin, evidence_type, title, description, status, submitted_at
) VALUES (
  '22222222-2222-4222-8222-222222222222',
  '11111111-1111-4111-8111-111111111111',
  'off_app', 'note', 'Disposable evidence test', 'Test-only evidence record',
  'submitted', now()
);

DO $$
DECLARE
  v_id uuid;
  v_count integer;
BEGIN
  SELECT id INTO v_id FROM public.work_evidence WHERE title='Disposable evidence test';
  SELECT count(*) INTO v_count FROM public.work_evidence_events WHERE evidence_id=v_id;
  IF v_count <> 1 THEN RAISE EXCEPTION 'Insert audit event was not recorded atomically'; END IF;

  BEGIN
    UPDATE public.work_evidence SET title='Tampered title' WHERE id=v_id;
    RAISE EXCEPTION 'Evidence identity mutation unexpectedly succeeded';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM = 'Evidence identity mutation unexpectedly succeeded' THEN RAISE; END IF;
  END;

  UPDATE public.work_evidence
    SET status='verified',
        reviewed_by='11111111-1111-4111-8111-111111111111',
        reviewed_at=now(),
        review_note='Disposable verification'
  WHERE id=v_id;

  IF (SELECT title FROM public.work_evidence WHERE id=v_id) <> 'Disposable evidence test' THEN
    RAISE EXCEPTION 'Immutable evidence title changed';
  END IF;
  SELECT count(*) INTO v_count FROM public.work_evidence_events WHERE evidence_id=v_id;
  IF v_count <> 2 THEN RAISE EXCEPTION 'Review audit event was not recorded'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.work_evidence_events
    WHERE evidence_id=v_id AND event_type='reviewed'
      AND details->>'review_note'='Disposable verification'
  ) THEN
    RAISE EXCEPTION 'Review decision details were not preserved in the audit event';
  END IF;

  BEGIN
    UPDATE public.work_evidence_events SET to_status='tampered' WHERE evidence_id=v_id;
    RAISE EXCEPTION 'Audit event mutation unexpectedly succeeded';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM = 'Audit event mutation unexpectedly succeeded' THEN RAISE; END IF;
  END;

  IF (SELECT count(*) FROM public.work_evidence_events WHERE evidence_id=v_id) <> 2 THEN
    RAISE EXCEPTION 'Audit event history changed';
  END IF;
END;
$$;

SELECT 'work evidence schema assertions passed' AS result;
