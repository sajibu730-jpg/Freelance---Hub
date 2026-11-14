-- DRAFT ONLY — NOT A SUPABASE MIGRATION.
-- This schema is intended for disposable PostgreSQL validation only.
-- Do not execute against Production. No RPC, Edge Function, or deploy adapter is included.
--
-- All writes and reads are intended to be mediated by separately reviewed server-side
-- functions. Browser roles and service_role receive no direct table privileges.

create table public.evolution_candidates (
  id uuid primary key default gen_random_uuid(),
  candidate_version integer not null default 1 check (candidate_version > 0),
  created_by uuid not null references auth.users(id) on delete restrict,
  title text not null check (length(btrim(title)) between 1 and 180),
  description text not null default '' check (length(description) <= 8000),
  change_category text not null check (change_category in
    ('remote_config', 'backend_database', 'android', 'dashboard_web', 'external_integration')),
  risk_level text not null check (risk_level in ('low', 'medium', 'high', 'critical')),
  impact_summary text not null check (length(btrim(impact_summary)) between 1 and 4000),
  privacy_security_impact text not null check (length(btrim(privacy_security_impact)) between 1 and 4000),
  cost_impact text not null check (length(btrim(cost_impact)) between 1 and 2000),
  repository text not null check (repository = 'sajibu730-jpg/Freelance---Hub'),
  source_branch text not null check (length(btrim(source_branch)) between 1 and 200),
  source_commit_sha text not null check (source_commit_sha ~ '^[0-9a-f]{40}([0-9a-f]{24})?$'),
  pull_request_number integer check (pull_request_number is null or pull_request_number > 0),
  artifact_name text check (artifact_name is null or length(artifact_name) between 1 and 200),
  artifact_sha256 text check (artifact_sha256 is null or artifact_sha256 ~ '^[0-9a-f]{64}$'),
  artifact_size_bytes bigint check (artifact_size_bytes is null or artifact_size_bytes > 0),
  build_run_id bigint check (build_run_id is null or build_run_id > 0),
  lifecycle_state text not null default 'draft' check (lifecycle_state in
    ('draft', 'submitted', 'validating', 'staging_ready', 'owner_approved',
     'release_queued', 'deploying', 'monitoring', 'released', 'blocked',
     'rejected', 'expired', 'deployment_uncertain', 'recovery_required', 'recovered')),
  recovery_plan text not null check (length(btrim(recovery_plan)) between 1 and 8000),
  recovery_plan_digest text not null check (recovery_plan_digest ~ '^[0-9a-f]{64}$'),
  staging_environment_id text check (staging_environment_id is null or length(staging_environment_id) <= 200),
  staging_deployed_commit text check (staging_deployed_commit is null or staging_deployed_commit ~ '^[0-9a-f]{40}([0-9a-f]{24})?$'),
  staging_verified_at timestamptz,
  submitted_at timestamptz,
  expires_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check ((artifact_sha256 is null and artifact_name is null and artifact_size_bytes is null)
      or (artifact_sha256 is not null and artifact_name is not null and artifact_size_bytes is not null)),
  check ((staging_verified_at is null and staging_deployed_commit is null)
      or (staging_verified_at is not null and staging_deployed_commit = source_commit_sha)),
  check ((lifecycle_state in ('submitted', 'validating', 'staging_ready', 'owner_approved',
      'release_queued', 'deploying', 'monitoring', 'released')
      and submitted_at is not null) or lifecycle_state not in
      ('submitted', 'validating', 'staging_ready', 'owner_approved',
       'release_queued', 'deploying', 'monitoring', 'released')),
  unique (id, candidate_version, source_commit_sha, artifact_sha256, recovery_plan_digest, risk_level)
);

create table public.evolution_evidence (
  id uuid primary key default gen_random_uuid(),
  candidate_id uuid not null references public.evolution_candidates(id) on delete restrict,
  evidence_type text not null check (evidence_type in
    ('ci_run', 'android_build', 'artifact_digest', 'static_analysis', 'unit_tests',
     'database_tests', 'security_review', 'staging_verification', 'recovery_test')),
  provider text not null check (provider in ('github_actions', 'staging_verifier', 'security_reviewer')),
  provider_reference text not null check (length(btrim(provider_reference)) between 1 and 500),
  subject_commit_sha text not null check (subject_commit_sha ~ '^[0-9a-f]{40}([0-9a-f]{24})?$'),
  artifact_sha256 text check (artifact_sha256 is null or artifact_sha256 ~ '^[0-9a-f]{64}$'),
  result text not null check (result in ('passed', 'failed', 'inconclusive')),
  observed_at timestamptz not null,
  expires_at timestamptz not null,
  details_digest text not null check (details_digest ~ '^[0-9a-f]{64}$'),
  created_at timestamptz not null default now(),
  check (expires_at > observed_at),
  check (result <> 'passed' or expires_at > created_at)
);

create table public.evolution_approvals (
  id uuid primary key default gen_random_uuid(),
  candidate_id uuid not null,
  candidate_version integer not null check (candidate_version > 0),
  owner_user_id uuid not null references auth.users(id) on delete restrict,
  approved_commit_sha text not null check (approved_commit_sha ~ '^[0-9a-f]{40}([0-9a-f]{24})?$'),
  approved_artifact_sha256 text not null check (approved_artifact_sha256 ~ '^[0-9a-f]{64}$'),
  evidence_set_digest text not null check (evidence_set_digest ~ '^[0-9a-f]{64}$'),
  risk_acknowledgement text not null check (length(btrim(risk_acknowledgement)) between 1 and 2000),
  recovery_plan_digest text not null check (recovery_plan_digest ~ '^[0-9a-f]{64}$'),
  approved_risk_level text not null check (approved_risk_level in ('low', 'medium', 'high', 'critical')),
  approved_at timestamptz not null default now(),
  expires_at timestamptz not null,
  consumed_at timestamptz,
  invalidated_at timestamptz,
  invalidation_reason text check (invalidation_reason is null or length(invalidation_reason) <= 1000),
  created_at timestamptz not null default now(),
  check (expires_at > approved_at),
  check ((invalidated_at is null and invalidation_reason is null)
      or (invalidated_at is not null and invalidation_reason is not null)),
  foreign key (candidate_id, candidate_version, approved_commit_sha,
    approved_artifact_sha256, recovery_plan_digest, approved_risk_level)
    references public.evolution_candidates
      (id, candidate_version, source_commit_sha, artifact_sha256, recovery_plan_digest, risk_level)
);

create unique index evolution_one_unconsumed_approval_per_candidate_version
  on public.evolution_approvals(candidate_id, candidate_version)
  where consumed_at is null and invalidated_at is null;

create table public.evolution_release_events (
  id uuid primary key default gen_random_uuid(),
  candidate_id uuid not null references public.evolution_candidates(id) on delete restrict,
  event_type text not null check (event_type in
    ('created', 'submitted', 'validation_started', 'evidence_recorded', 'blocked',
     'staging_verified', 'approval_granted', 'approval_invalidated', 'approval_consumed',
     'release_queued', 'deployment_started', 'deployment_succeeded',
     'deployment_failed', 'deployment_uncertain', 'recovery_started', 'recovered',
     'rejected', 'expired')),
  actor_user_id uuid references auth.users(id) on delete restrict,
  actor_kind text not null check (actor_kind in ('owner', 'proposer', 'system', 'trusted_adapter')),
  previous_state text,
  new_state text,
  source_commit_sha text check (source_commit_sha is null or source_commit_sha ~ '^[0-9a-f]{40}([0-9a-f]{24})?$'),
  artifact_sha256 text check (artifact_sha256 is null or artifact_sha256 ~ '^[0-9a-f]{64}$'),
  evidence_set_digest text check (evidence_set_digest is null or evidence_set_digest ~ '^[0-9a-f]{64}$'),
  approval_id uuid references public.evolution_approvals(id) on delete restrict,
  correlation_id uuid not null,
  outcome_code text check (outcome_code is null or length(outcome_code) <= 100),
  occurred_at timestamptz not null default now()
);

create index evolution_candidates_state_time_idx
  on public.evolution_candidates(lifecycle_state, created_at desc);
create index evolution_evidence_candidate_time_idx
  on public.evolution_evidence(candidate_id, observed_at desc);
create index evolution_approvals_candidate_version_idx
  on public.evolution_approvals(candidate_id, candidate_version, approved_at desc);
create index evolution_release_events_candidate_time_idx
  on public.evolution_release_events(candidate_id, occurred_at desc);

alter table public.evolution_candidates enable row level security;
alter table public.evolution_candidates force row level security;
alter table public.evolution_evidence enable row level security;
alter table public.evolution_evidence force row level security;
alter table public.evolution_approvals enable row level security;
alter table public.evolution_approvals force row level security;
alter table public.evolution_release_events enable row level security;
alter table public.evolution_release_events force row level security;

revoke all on table public.evolution_candidates, public.evolution_evidence,
  public.evolution_approvals, public.evolution_release_events
  from public, anon, authenticated, service_role;

-- No direct grants are intentionally issued. Future narrowly scoped SECURITY DEFINER
-- functions must be separately reviewed, pin search_path, verify the current primary
-- Owner and AAL2 inside the transaction, and append audit events atomically.

create unique index evolution_one_unconsumed_approval_per_candidate_version
  on public.evolution_approvals(candidate_id, candidate_version)
  where consumed_at is null and invalidated_at is null;

create table public.evolution_release_events (
  id uuid primary key default gen_random_uuid(),
  candidate_id uuid not null references public.evolution_candidates(id) on delete restrict,
  event_type text not null check (event_type in
    ('created', 'submitted', 'validation_started', 'evidence_recorded', 'blocked',
     'staging_verified', 'approval_granted', 'approval_invalidated', 'approval_consumed',
     'release_queued', 'deployment_started', 'deployment_succeeded',
     'deployment_failed', 'deployment_uncertain', 'recovery_started', 'recovered',
     'rejected', 'expired')),
  actor_user_id uuid references auth.users(id) on delete restrict,
  actor_kind text not null check (actor_kind in ('owner', 'proposer', 'system', 'trusted_adapter')),
  previous_state text,
  new_state text,
  source_commit_sha text check (source_commit_sha is null or source_commit_sha ~ '^[0-9a-f]{40}([0-9a-f]{24})?$'),
  artifact_sha256 text check (artifact_sha256 is null or artifact_sha256 ~ '^[0-9a-f]{64}$'),
  evidence_set_digest text check (evidence_set_digest is null or evidence_set_digest ~ '^[0-9a-f]{64}$'),
  approval_id uuid references public.evolution_approvals(id) on delete restrict,
  correlation_id uuid not null,
  outcome_code text check (outcome_code is null or length(outcome_code) <= 100),
  occurred_at timestamptz not null default now()
);

create index evolution_candidates_state_time_idx
  on public.evolution_candidates(lifecycle_state, created_at desc);
create index evolution_evidence_candidate_time_idx
  on public.evolution_evidence(candidate_id, observed_at desc);
create index evolution_approvals_candidate_version_idx
  on public.evolution_approvals(candidate_id, candidate_version, approved_at desc);
create index evolution_release_events_candidate_time_idx
  on public.evolution_release_events(candidate_id, occurred_at desc);

alter table public.evolution_candidates enable row level security;
alter table public.evolution_candidates force row level security;
alter table public.evolution_evidence enable row level security;
alter table public.evolution_evidence force row level security;
alter table public.evolution_approvals enable row level security;
alter table public.evolution_approvals force row level security;
alter table public.evolution_release_events enable row level security;
alter table public.evolution_release_events force row level security;

revoke all on table public.evolution_candidates, public.evolution_evidence,
  public.evolution_approvals, public.evolution_release_events
  from public, anon, authenticated, service_role;

-- No direct grants are intentionally issued. Future narrowly scoped SECURITY DEFINER
-- functions must be separately reviewed, pin search_path, verify the current primary
-- Owner and AAL2 inside the transaction, and append audit events atomically.
