-- Assertions that are safe against the real local Supabase Auth schema.
-- No synthetic rows are inserted into auth.users here; API-driven test users are
-- created through the local Auth Admin API by work_evidence_local_integration.py.
DO $$
BEGIN
  IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.work_evidence'::regclass)
    OR NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.work_evidence_events'::regclass)
    OR NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.work_evidence_cleanup_queue'::regclass)
    OR NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.work_evidence_quota_events'::regclass) THEN
    RAISE EXCEPTION 'Evidence RLS is not enabled on every protected table';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM storage.buckets
    WHERE id='work-evidence-private' AND public=false
      AND file_size_limit=10485760
      AND allowed_mime_types=ARRAY['application/pdf','image/jpeg','image/png','image/webp']::text[]
  ) THEN
    RAISE EXCEPTION 'Private evidence bucket settings are missing or unsafe';
  END IF;

  IF has_table_privilege('anon','public.work_evidence','SELECT')
    OR has_table_privilege('authenticated','public.work_evidence','SELECT')
    OR has_table_privilege('service_role','public.work_evidence_cleanup_queue','SELECT')
    OR has_table_privilege('service_role','public.work_evidence_quota_events','SELECT') THEN
    RAISE EXCEPTION 'Evidence data is exposed to an unintended database role';
  END IF;

  IF NOT has_function_privilege('service_role','public.claim_work_evidence_quota(uuid,uuid)','EXECUTE')
    OR NOT has_function_privilege('service_role','public.claim_work_evidence_cleanup_batch(integer)','EXECUTE')
    OR NOT has_function_privilege('service_role','public.finish_work_evidence_cleanup(uuid,uuid,boolean,text)','EXECUTE')
    OR has_function_privilege('service_role','public.audit_work_evidence_change()','EXECUTE') THEN
    RAISE EXCEPTION 'Evidence function grants are incomplete or too broad';
  END IF;
END;
$$;
