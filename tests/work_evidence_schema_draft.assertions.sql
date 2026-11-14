\set ON_ERROR_STOP on

DO $$
BEGIN
  IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.work_evidence'::regclass) THEN
    RAISE EXCEPTION 'work_evidence RLS is not enabled';
  END IF;
  IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.work_evidence_events'::regclass) THEN
    RAISE EXCEPTION 'work_evidence_events RLS is not enabled';
  END IF;
  IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.work_evidence_cleanup_queue'::regclass) THEN
    RAISE EXCEPTION 'work_evidence_cleanup_queue RLS is not enabled';
  END IF;
  IF has_table_privilege('anon', 'public.work_evidence', 'SELECT')
    OR has_table_privilege('authenticated', 'public.work_evidence', 'SELECT')
    OR has_table_privilege('anon', 'public.work_evidence_events', 'SELECT')
    OR has_table_privilege('authenticated', 'public.work_evidence_events', 'SELECT')
    OR has_table_privilege('anon', 'public.work_evidence_cleanup_queue', 'SELECT')
    OR has_table_privilege('authenticated', 'public.work_evidence_cleanup_queue', 'SELECT') THEN
    RAISE EXCEPTION 'Evidence tables are directly readable by client roles';
  END IF;
  IF NOT has_table_privilege('service_role', 'public.work_evidence', 'SELECT')
    OR NOT has_table_privilege('service_role', 'public.work_evidence', 'INSERT')
    OR NOT has_table_privilege('service_role', 'public.work_evidence', 'UPDATE')
    OR NOT has_table_privilege('service_role', 'public.work_evidence_events', 'INSERT')
    OR has_table_privilege('service_role', 'public.work_evidence', 'DELETE')
    OR has_table_privilege('service_role', 'public.work_evidence_events', 'UPDATE')
    OR has_table_privilege('service_role', 'public.work_evidence_events', 'DELETE')
    OR NOT has_table_privilege('service_role', 'public.work_evidence_cleanup_queue', 'SELECT')
    OR NOT has_table_privilege('service_role', 'public.work_evidence_cleanup_queue', 'INSERT')
    OR NOT has_table_privilege('service_role', 'public.work_evidence_cleanup_queue', 'UPDATE')
    OR has_table_privilege('service_role', 'public.work_evidence_cleanup_queue', 'DELETE')
    OR NOT has_function_privilege('service_role', 'public.claim_work_evidence_cleanup_batch(integer)', 'EXECUTE')
    OR NOT has_function_privilege('service_role', 'public.finish_work_evidence_cleanup(uuid,boolean,text)', 'EXECUTE')
    OR has_function_privilege('authenticated', 'public.claim_work_evidence_cleanup_batch(integer)', 'EXECUTE')
    OR has_function_privilege('anon', 'public.finish_work_evidence_cleanup(uuid,boolean,text)', 'EXECUTE') THEN
    RAISE EXCEPTION 'Edge Function service role grants are incomplete or overly broad';
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
    WHERE id='work-evidence-private'
      AND public=false
      AND file_size_limit=10485760
      AND allowed_mime_types = ARRAY['application/pdf', 'image/jpeg', 'image/png', 'image/webp']::text[]
  ) THEN
    RAISE EXCEPTION 'Private evidence bucket configuration or MIME allowlist is missing or unsafe';
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

  -- The database must reject a second, contradictory review after verification.
  BEGIN
    UPDATE public.work_evidence
      SET status='rejected',
          reviewed_by='33333333-3333-4333-8333-333333333333',
          reviewed_at=now(),
          review_note='Contradictory second review'
    WHERE id=v_id;
    RAISE EXCEPTION 'Verified evidence was allowed to transition to rejected';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM = 'Verified evidence was allowed to transition to rejected' THEN RAISE; END IF;
  END;

  -- Review metadata cannot be edited independently of a valid review transition.
  BEGIN
    UPDATE public.work_evidence
      SET review_note='Tampered audit note'
    WHERE id=v_id;
    RAISE EXCEPTION 'Review metadata was editable without a valid transition';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM = 'Review metadata was editable without a valid transition' THEN RAISE; END IF;
  END;
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
    RAISE EXCEPTION 'Audit event UPDATE unexpectedly succeeded';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM = 'Audit event UPDATE unexpectedly succeeded' THEN RAISE; END IF;
  END;

  BEGIN
    DELETE FROM public.work_evidence_events WHERE evidence_id=v_id;
    RAISE EXCEPTION 'Audit event DELETE unexpectedly succeeded';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM = 'Audit event DELETE unexpectedly succeeded' THEN RAISE; END IF;
  END;

  IF (SELECT count(*) FROM public.work_evidence_events WHERE evidence_id=v_id) <> 2 THEN
    RAISE EXCEPTION 'Audit event history changed';
  END IF;
END;
$$;

-- Verify failed and expired uploads enter a durable, retryable cleanup queue.
INSERT INTO public.work_evidence (
  id, request_id, submitted_by, origin, evidence_type, title, description,
  storage_path, original_filename, mime_type, byte_size, status, submitted_at
) VALUES (
  '66666666-6666-4666-8666-666666666666',
  '66666666-6666-4666-8666-666666666667',
  '11111111-1111-4111-8111-111111111111',
  'off_app', 'file', 'Failed upload cleanup test', '',
  '11111111-1111-4111-8111-111111111111/66666666-6666-4666-8666-666666666666/proof',
  'proof.pdf', 'application/pdf', 12, 'upload_pending', null
);
UPDATE public.work_evidence
SET status='upload_failed', submitted_at=now()
WHERE id='66666666-6666-4666-8666-666666666666';

INSERT INTO public.work_evidence (
  id, request_id, submitted_by, origin, evidence_type, title, description,
  storage_path, original_filename, mime_type, byte_size, status, submitted_at,
  created_at, updated_at
) VALUES (
  '77777777-7777-4777-8777-777777777777',
  '77777777-7777-4777-8777-777777777778',
  '11111111-1111-4111-8111-111111111111',
  'off_app', 'file', 'Expired upload cleanup test', '',
  '11111111-1111-4111-8111-111111111111/77777777-7777-4777-8777-777777777777/proof',
  'proof.pdf', 'application/pdf', 12, 'upload_pending', null,
  now() - interval '25 hours', now() - interval '25 hours'
);

SET ROLE service_role;
DO $cleanup_test$
DECLARE
  v_claimed integer;
  v_ok boolean;
BEGIN
  SELECT count(*) INTO v_claimed
  FROM public.claim_work_evidence_cleanup_batch(10);
  IF v_claimed <> 2 THEN
    RAISE EXCEPTION 'Expected two cleanup jobs (failed + expired), got %', v_claimed;
  END IF;

  IF (SELECT status FROM public.work_evidence
      WHERE id='77777777-7777-4777-8777-777777777777') <> 'upload_failed' THEN
    RAISE EXCEPTION 'Expired upload was not atomically marked upload_failed';
  END IF;

  SELECT public.finish_work_evidence_cleanup(
    '66666666-6666-4666-8666-666666666666', true, null
  ) INTO v_ok;
  IF NOT v_ok THEN RAISE EXCEPTION 'Cleanup success could not be committed'; END IF;

  SELECT public.finish_work_evidence_cleanup(
    '77777777-7777-4777-8777-777777777777', false, 'Disposable retry test'
  ) INTO v_ok;
  IF NOT v_ok THEN RAISE EXCEPTION 'Cleanup retry could not be committed'; END IF;

  IF (SELECT state FROM public.work_evidence_cleanup_queue
      WHERE evidence_id='66666666-6666-4666-8666-666666666666') <> 'completed' THEN
    RAISE EXCEPTION 'Successful cleanup was not marked completed';
  END IF;
  IF (SELECT state FROM public.work_evidence_cleanup_queue
      WHERE evidence_id='77777777-7777-4777-8777-777777777777') <> 'pending' THEN
    RAISE EXCEPTION 'Failed cleanup was not returned to retry queue';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.work_evidence_events
    WHERE evidence_id='66666666-6666-4666-8666-666666666666'
      AND event_type='storage_cleanup_completed'
  ) THEN
    RAISE EXCEPTION 'Successful Storage cleanup audit event is missing';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.work_evidence_events
    WHERE evidence_id='77777777-7777-4777-8777-777777777777'
      AND event_type='storage_cleanup_retry'
  ) THEN
    RAISE EXCEPTION 'Failed Storage cleanup retry audit event is missing';
  END IF;
END;
$cleanup_test$;
RESET ROLE;

SELECT 'work evidence schema assertions passed' AS result;
