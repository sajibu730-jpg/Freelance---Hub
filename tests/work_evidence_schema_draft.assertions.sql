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

INSERT INTO auth.users(id) VALUES ('11111111-1111-4111-8111-111111111111');

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
