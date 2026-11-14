\set ON_ERROR_STOP on

-- Disposable PostgreSQL only. auth.uid()/auth.jwt() are test doubles for Supabase JWT context.
create or replace function auth.uid()
returns uuid
language sql
stable
as $$
  select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$$;

create or replace function auth.jwt()
returns jsonb
language sql
stable
as $$
  select coalesce(nullif(current_setting('request.jwt.claims', true), '')::jsonb, '{}'::jsonb)
$$;

insert into auth.users (id) values
  ('88888888-8888-4888-8888-888888888888'),
  ('77777777-7777-4777-8777-777777777777')
on conflict (id) do nothing;

insert into public.app_admins (user_id, role, is_active) values
  ('88888888-8888-4888-8888-888888888888', 'owner', true),
  ('77777777-7777-4777-8777-777777777777', 'developer', true)
on conflict (user_id) do update set role = excluded.role, is_active = excluded.is_active;

do $$
declare
  v_candidate uuid;
  v_missing_evidence_candidate uuid;
  v_result jsonb;
  v_approval_id uuid;
  v_event_count integer;
begin
  if has_function_privilege('anon', 'public.approve_evolution_release(uuid,text,uuid)', 'EXECUTE')
     or has_function_privilege('service_role', 'public.approve_evolution_release(uuid,text,uuid)', 'EXECUTE')
     or not has_function_privilege('authenticated', 'public.approve_evolution_release(uuid,text,uuid)', 'EXECUTE') then
    raise exception 'Approval RPC execute grants are not least-privilege.';
  end if;

  if has_table_privilege('authenticated', 'public.evolution_candidates', 'SELECT')
     or has_table_privilege('authenticated', 'public.evolution_approvals', 'INSERT')
     or has_table_privilege('service_role', 'public.evolution_approvals', 'INSERT') then
    raise exception 'Direct table access must remain revoked.';
  end if;

  insert into public.evolution_candidates (
    created_by, title, description, change_category, risk_level,
    impact_summary, privacy_security_impact, cost_impact,
    repository, source_branch, source_commit_sha, artifact_name,
    artifact_sha256, artifact_size_bytes, build_run_id,
    lifecycle_state, recovery_plan, recovery_plan_digest,
    staging_environment_id, staging_deployed_commit, staging_verified_at, submitted_at
  ) values (
    '99999999-9999-4999-8999-999999999999',
    'AAL2 Owner approval fixture', 'Disposable CI test only',
    'android', 'low', 'No production impact', 'No personal data change', 'No new cost',
    'sajibu730-jpg/Freelance---Hub', 'test/owner-approval', repeat('a', 40),
    'Freelance-Hub-debug.apk', repeat('b', 64), 123456, 987654,
    'staging_ready', 'Restore the prior known-good build', repeat('c', 64),
    'disposable-staging', repeat('a', 40), now(), now()
  ) returning id into v_candidate;

  insert into public.evolution_evidence (
    candidate_id, evidence_type, provider, provider_reference,
    subject_commit_sha, artifact_sha256, result, observed_at, expires_at, details_digest
  )
  select v_candidate, required.evidence_type, 'github_actions',
         'disposable/' || required.evidence_type,
         repeat('a', 40), repeat('b', 64), 'passed',
         now() - interval '1 minute', now() + interval '1 hour',
         repeat('d', 64)
  from unnest(array[
    'ci_run', 'android_build', 'artifact_digest', 'static_analysis',
    'unit_tests', 'database_tests', 'security_review',
    'staging_verification', 'recovery_test'
  ]) as required(evidence_type);

  insert into public.evolution_candidates (
    created_by, title, description, change_category, risk_level,
    impact_summary, privacy_security_impact, cost_impact,
    repository, source_branch, source_commit_sha, artifact_name,
    artifact_sha256, artifact_size_bytes, build_run_id,
    lifecycle_state, recovery_plan, recovery_plan_digest,
    staging_environment_id, staging_deployed_commit, staging_verified_at, submitted_at
  ) values (
    '99999999-9999-4999-8999-999999999999',
    'Missing evidence fixture', 'Must remain blocked',
    'dashboard_web', 'low', 'No production impact', 'No personal data change', 'No new cost',
    'sajibu730-jpg/Freelance---Hub', 'test/missing-evidence', repeat('e', 40),
    'dashboard.zip', repeat('f', 64), 1000, 987655,
    'staging_ready', 'Restore prior version', repeat('1', 64),
    'disposable-staging', repeat('e', 40), now(), now()
  ) returning id into v_missing_evidence_candidate;
end $$;

select set_config('test.approval_candidate_id', (select id::text from public.evolution_candidates where title = 'AAL2 Owner approval fixture'), false);
select set_config('test.missing_evidence_candidate_id', (select id::text from public.evolution_candidates where title = 'Missing evidence fixture'), false);

set role authenticated;

-- AAL1 must fail closed.
select set_config('request.jwt.claim.sub', '88888888-8888-4888-8888-888888888888', false);
select set_config('request.jwt.claims',
  '{"sub":"88888888-8888-4888-8888-888888888888","aal":"aal1"}', false);
do $$
begin
  begin
    perform public.approve_evolution_release(
      current_setting('test.approval_candidate_id')::uuid,
      'Risk accepted', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1'
    );
    raise exception 'AAL1 session unexpectedly approved a release.';
  exception when insufficient_privilege then
    null;
  end;
end $$;

-- A non-Owner with AAL2 must also fail.
select set_config('request.jwt.claim.sub', '77777777-7777-4777-8777-777777777777', false);
select set_config('request.jwt.claims',
  '{"sub":"77777777-7777-4777-8777-777777777777","aal":"aal2"}', false);
do $$
begin
  begin
    perform public.approve_evolution_release(
      current_setting('test.approval_candidate_id')::uuid,
      'Risk accepted', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2'
    );
    raise exception 'A non-Owner unexpectedly approved a release.';
  exception when insufficient_privilege then
    null;
  end;
end $$;

-- Active primary Owner with AAL2 cannot approve a candidate with missing evidence.
select set_config('request.jwt.claim.sub', '88888888-8888-4888-8888-888888888888', false);
select set_config('request.jwt.claims',
  '{"sub":"88888888-8888-4888-8888-888888888888","aal":"aal2"}', false);
do $$
begin
  begin
    perform public.approve_evolution_release(
      current_setting('test.missing_evidence_candidate_id')::uuid,
      'Risk accepted', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3'
    );
    raise exception 'Candidate with missing evidence unexpectedly approved.';
  exception when invalid_parameter_value then
    null;
  end;
end $$;

-- Correct Owner + AAL2 + exact complete evidence succeeds atomically.
do $$
declare
  v_result jsonb;
  v_candidate uuid;
  v_approval uuid;
  v_state text;
  v_event_count integer;
begin
  v_candidate := current_setting('test.approval_candidate_id')::uuid;

  v_result := public.approve_evolution_release(
    v_candidate, 'I reviewed and accept the low-risk release.', 
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa4'
  );
  v_approval := (v_result ->> 'approval_id')::uuid;

  if v_result ->> 'lifecycle_state' <> 'owner_approved'
     or v_result ->> 'approved_commit_sha' <> repeat('a', 40)
     or v_result ->> 'approved_artifact_sha256' <> repeat('b', 64)
     or length(v_result ->> 'evidence_set_digest') <> 64 then
    raise exception 'Approval response is not bound to the exact candidate evidence.';
  end if;

  begin
    perform public.approve_evolution_release(
      v_candidate, 'Second approval attempt', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa5'
    );
    raise exception 'A candidate outside staging_ready was approved twice.';
  exception when invalid_parameter_value then
    null;
  end;
end $$;

reset role;

do $verify$
declare
  v_candidate uuid := current_setting('test.approval_candidate_id')::uuid;
  v_state text;
  v_approval uuid;
  v_event_count integer;
begin
  select lifecycle_state into v_state
  from public.evolution_candidates where id = v_candidate;
  if v_state <> 'owner_approved' then
    raise exception 'Candidate state and approval were not committed together.';
  end if;

  select id into v_approval from public.evolution_approvals
  where candidate_id = v_candidate and owner_user_id = '88888888-8888-4888-8888-888888888888';

  select count(*) into v_event_count
  from public.evolution_release_events
  where candidate_id = v_candidate and event_type = 'approval_granted'
    and approval_id = v_approval
    and actor_user_id = '88888888-8888-4888-8888-888888888888';
  if v_event_count <> 1 then
    raise exception 'Approval audit event missing or duplicated.';
  end if;
end $verify$;

select 'System Evolution Owner AAL2 approval RPC disposable PostgreSQL tests passed.' as result;
