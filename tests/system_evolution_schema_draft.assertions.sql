\set ON_ERROR_STOP on

DO $$
DECLARE
  v_table text;
  v_candidate uuid;
  v_mismatch_candidate uuid;
  v_owner uuid := '99999999-9999-4999-8999-999999999999';
  v_approval uuid;
BEGIN
  FOREACH v_table IN ARRAY ARRAY[
    'evolution_candidates',
    'evolution_evidence',
    'evolution_approvals',
    'evolution_release_events'
  ] LOOP
    IF NOT EXISTS (
      SELECT 1
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public'
        AND c.relname = v_table
        AND c.relrowsecurity
        AND c.relforcerowsecurity
    ) THEN
      RAISE EXCEPTION 'RLS and FORCE RLS must be enabled on %', v_table;
    END IF;

    IF has_table_privilege('anon', 'public.' || v_table, 'SELECT')
       OR has_table_privilege('anon', 'public.' || v_table, 'INSERT')
       OR has_table_privilege('authenticated', 'public.' || v_table, 'SELECT')
       OR has_table_privilege('authenticated', 'public.' || v_table, 'INSERT')
       OR has_table_privilege('service_role', 'public.' || v_table, 'SELECT')
       OR has_table_privilege('service_role', 'public.' || v_table, 'INSERT')
       OR has_table_privilege('service_role', 'public.' || v_table, 'UPDATE')
       OR has_table_privilege('service_role', 'public.' || v_table, 'DELETE') THEN
      RAISE EXCEPTION 'Unexpected direct API/service_role privilege on %', v_table;
    END IF;
  END LOOP;

  INSERT INTO public.evolution_candidates (
    created_by, title, description, change_category, risk_level,
    impact_summary, privacy_security_impact, cost_impact,
    repository, source_branch, source_commit_sha, artifact_name,
    artifact_sha256, artifact_size_bytes, build_run_id,
    recovery_plan, recovery_plan_digest
  ) VALUES (
    v_owner, 'Disposable test candidate', 'Only a local CI fixture',
    'dashboard_web', 'low', 'No live user impact',
    'No personal data change', 'No new cost',
    'sajibu730-jpg/Freelance---Hub', 'test/draft',
    repeat('a', 40), 'fixture.apk', repeat('b', 64), 12345, 123,
    'Restore previous known-good artifact', repeat('c', 64)
  ) RETURNING id INTO v_candidate;

  INSERT INTO public.evolution_evidence (
    candidate_id, evidence_type, provider, provider_reference,
    subject_commit_sha, artifact_sha256, result, observed_at,
    expires_at, details_digest
  ) VALUES (
    v_candidate, 'android_build', 'github_actions', 'run/123',
    repeat('a', 40), repeat('b', 64), 'passed', now(),
    now() + interval '1 hour', repeat('d', 64)
  );

  INSERT INTO public.evolution_approvals (
    candidate_id, candidate_version, owner_user_id,
    approved_commit_sha, approved_artifact_sha256, evidence_set_digest,
    risk_acknowledgement, recovery_plan_digest, approved_risk_level, expires_at
  ) VALUES (
    v_candidate, 1, v_owner, repeat('a', 40), repeat('b', 64),
    repeat('e', 64), 'I acknowledge low risk', repeat('c', 64), 'low',
    now() + interval '1 hour'
  ) RETURNING id INTO v_approval;

  INSERT INTO public.evolution_candidates (
    created_by, title, description, change_category, risk_level,
    impact_summary, privacy_security_impact, cost_impact,
    repository, source_branch, source_commit_sha, artifact_name,
    artifact_sha256, artifact_size_bytes, build_run_id,
    recovery_plan, recovery_plan_digest
  ) VALUES (
    v_owner, 'Mismatch fixture', 'A separate candidate for FK test',
    'dashboard_web', 'low', 'No live user impact',
    'No personal data change', 'No new cost',
    'sajibu730-jpg/Freelance---Hub', 'test/mismatch',
    repeat('a', 40), 'fixture-mismatch.apk', repeat('b', 64), 12345, 124,
    'Restore previous known-good artifact', repeat('c', 64)
  ) RETURNING id INTO v_mismatch_candidate;

  BEGIN
    INSERT INTO public.evolution_approvals (
      candidate_id, candidate_version, owner_user_id,
      approved_commit_sha, approved_artifact_sha256, evidence_set_digest,
      risk_acknowledgement, recovery_plan_digest, approved_risk_level, expires_at
    ) VALUES (
      v_mismatch_candidate, 1, v_owner, repeat('f', 40), repeat('b', 64),
      repeat('f', 64), 'Mismatched commit must be rejected', repeat('c', 64), 'low',
      now() + interval '1 hour'
    );
    RAISE EXCEPTION 'Approval for a different commit unexpectedly succeeded';
  EXCEPTION WHEN foreign_key_violation THEN
    NULL;
  END;

  BEGIN
    INSERT INTO public.evolution_approvals (
      candidate_id, candidate_version, owner_user_id,
      approved_commit_sha, approved_artifact_sha256, evidence_set_digest,
      risk_acknowledgement, recovery_plan_digest, approved_risk_level, expires_at
    ) VALUES (
      v_candidate, 1, v_owner, repeat('a', 40), repeat('b', 64),
      repeat('f', 64), 'Duplicate must be rejected', repeat('c', 64), 'low',
      now() + interval '1 hour'
    );
    RAISE EXCEPTION 'Duplicate active approval unexpectedly succeeded';
  EXCEPTION WHEN unique_violation THEN
    NULL;
  END;

  INSERT INTO public.evolution_release_events (
    candidate_id, event_type, actor_user_id, actor_kind,
    previous_state, new_state, source_commit_sha,
    artifact_sha256, evidence_set_digest, approval_id, correlation_id
  ) VALUES (
    v_candidate, 'approval_granted', v_owner, 'owner',
    'staging_ready', 'owner_approved', repeat('a', 40),
    repeat('b', 64), repeat('e', 64), v_approval, gen_random_uuid()
  );
END;
$$;

SELECT 'system evolution schema draft assertions passed' AS result;
