-- DRAFT ONLY — NOT A SUPABASE MIGRATION.
-- Apply only to disposable PostgreSQL in CI. Never execute against Production.
-- This RPC uses the caller's authenticated Supabase JWT context directly.
-- It does not accept an actor ID, AAL value, commit, artifact digest, or evidence digest
-- from the client. Those values are resolved and checked inside this transaction.

create schema if not exists extensions;
create extension if not exists pgcrypto with schema extensions;

create or replace function public.approve_evolution_release(
  p_candidate_id uuid,
  p_risk_acknowledgement text,
  p_correlation_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_actor uuid;
  v_claims jsonb;
  v_owner_role text;
  v_owner_active boolean;
  v_candidate public.evolution_candidates%rowtype;
  v_evidence_count integer;
  v_evidence_digest text;
  v_approval public.evolution_approvals%rowtype;
  v_required text[] := array[
    'ci_run', 'android_build', 'artifact_digest', 'static_analysis',
    'unit_tests', 'database_tests', 'security_review',
    'staging_verification', 'recovery_test'
  ];
begin
  -- Supabase supplies these from the verified request JWT, never request JSON.
  v_actor := auth.uid();
  v_claims := auth.jwt();

  if v_actor is null or v_claims ->> 'sub' is distinct from v_actor::text then
    raise exception using errcode = '28000', message = 'A valid authenticated session is required.';
  end if;
  if v_claims ->> 'aal' is distinct from 'aal2' then
    raise exception using errcode = '42501', message = 'A current AAL2 session is required.';
  end if;
  if p_candidate_id is null or p_correlation_id is null
     or p_risk_acknowledgement is null
     or length(btrim(p_risk_acknowledgement)) not between 1 and 2000 then
    raise exception using errcode = '22023', message = 'Approval input is invalid.';
  end if;

  -- Resolve current Owner authorization inside the same transaction.
  select a.role, a.is_active
    into v_owner_role, v_owner_active
  from public.app_admins a
  where a.user_id = v_actor
  for share;

  if not found or v_owner_role is distinct from 'owner' or v_owner_active is distinct from true then
    raise exception using errcode = '42501', message = 'Only the active primary Owner may approve a release.';
  end if;

  select c.* into v_candidate
  from public.evolution_candidates c
  where c.id = p_candidate_id
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'Release candidate was not found.';
  end if;

  if v_candidate.lifecycle_state <> 'staging_ready'
     or v_candidate.staging_verified_at is null
     or v_candidate.staging_deployed_commit is distinct from v_candidate.source_commit_sha
     or v_candidate.artifact_name is null
     or v_candidate.artifact_sha256 is null
     or v_candidate.artifact_size_bytes is null then
    raise exception using errcode = '22023', message = 'Candidate is not fully verified in staging.';
  end if;

  -- All required evidence must be successful, fresh, and bound to the exact
  -- candidate commit and artifact. Extra evidence is allowed but must also match.
  select count(distinct e.evidence_type),
         encode(extensions.digest(
           convert_to(
             string_agg(
               e.evidence_type || ':' || e.provider || ':' || e.provider_reference || ':' ||
               e.subject_commit_sha || ':' || coalesce(e.artifact_sha256, '') || ':' ||
               e.result || ':' || e.observed_at::text || ':' || e.expires_at::text || ':' ||
               e.details_digest,
               E'\n' order by e.evidence_type, e.provider_reference
             ),
             'UTF8'
           ),
           'sha256'
         ), 'hex')
    into v_evidence_count, v_evidence_digest
  from public.evolution_evidence e
  where e.candidate_id = v_candidate.id
    and e.result = 'passed'
    and e.observed_at <= pg_catalog.now()
    and e.expires_at > pg_catalog.now()
    and e.subject_commit_sha = v_candidate.source_commit_sha
    and e.artifact_sha256 = v_candidate.artifact_sha256;

  if v_evidence_count <> cardinality(v_required)
     or exists (
       select 1 from pg_catalog.unnest(v_required) r(evidence_type)
       where not exists (
         select 1 from public.evolution_evidence e
         where e.candidate_id = v_candidate.id
           and e.evidence_type = r.evidence_type
           and e.result = 'passed'
           and e.observed_at <= pg_catalog.now()
           and e.expires_at > pg_catalog.now()
           and e.subject_commit_sha = v_candidate.source_commit_sha
           and e.artifact_sha256 = v_candidate.artifact_sha256
       )
     )
     or v_evidence_digest is null then
    raise exception using errcode = '22023', message = 'Required evidence is missing, stale, failed, or mismatched.';
  end if;

  insert into public.evolution_approvals (
    candidate_id, candidate_version, owner_user_id,
    approved_commit_sha, approved_artifact_sha256, evidence_set_digest,
    risk_acknowledgement, recovery_plan_digest, approved_risk_level, expires_at
  ) values (
    v_candidate.id, v_candidate.candidate_version, v_actor,
    v_candidate.source_commit_sha, v_candidate.artifact_sha256, v_evidence_digest,
    p_risk_acknowledgement, v_candidate.recovery_plan_digest,
    v_candidate.risk_level, pg_catalog.now() + interval '15 minutes'
  )
  returning * into v_approval;

  update public.evolution_candidates
  set lifecycle_state = 'owner_approved',
      updated_at = pg_catalog.now()
  where id = v_candidate.id
    and lifecycle_state = 'staging_ready';

  if not found then
    raise exception using errcode = '40001', message = 'Candidate changed during approval; retry after review.';
  end if;

  insert into public.evolution_release_events (
    candidate_id, event_type, actor_user_id, actor_kind,
    previous_state, new_state, source_commit_sha, artifact_sha256,
    evidence_set_digest, approval_id, correlation_id
  ) values (
    v_candidate.id, 'approval_granted', v_actor, 'owner',
    'staging_ready', 'owner_approved', v_candidate.source_commit_sha,
    v_candidate.artifact_sha256, v_evidence_digest, v_approval.id, p_correlation_id
  );

  return pg_catalog.jsonb_build_object(
    'approval_id', v_approval.id,
    'candidate_id', v_candidate.id,
    'candidate_version', v_candidate.candidate_version,
    'approved_commit_sha', v_candidate.source_commit_sha,
    'approved_artifact_sha256', v_candidate.artifact_sha256,
    'evidence_set_digest', v_evidence_digest,
    'expires_at', v_approval.expires_at,
    'lifecycle_state', 'owner_approved'
  );
end;
$function$;

revoke all on function public.approve_evolution_release(uuid, text, uuid)
  from public, anon, service_role;
grant execute on function public.approve_evolution_release(uuid, text, uuid)
  to authenticated;

comment on function public.approve_evolution_release(uuid, text, uuid)
  is 'DRAFT ONLY. Authenticated caller must be current active Owner with JWT AAL2. Approval is transactionally bound to exact commit, artifact, recovery plan and fresh required evidence. No release/deploy action is performed.';
