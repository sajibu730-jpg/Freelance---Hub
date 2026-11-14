-- DRAFT ONLY — NOT A SUPABASE MIGRATION.
-- Do not execute against Production. Requires a disposable local/staging database,
-- transactional mutation RPCs, RLS tests, Supabase Advisors, and explicit Owner approval.
--
-- All reads/writes are intended to go through authenticated Edge Functions using
-- the server-only service role. No anon/authenticated table grants or policies.

create table public.owner_workforce_members (
  user_id uuid primary key references auth.users(id) on delete restrict,
  role text not null check (role in ('project_manager', 'developer', 'qa', 'support')),
  status text not null default 'active' check (status in ('active', 'suspended')),
  project_scopes text[] not null default '{}',
  invited_by uuid not null references auth.users(id) on delete restrict,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (cardinality(project_scopes) <= 50)
);

create table public.owner_workforce_tasks (
  id uuid primary key default gen_random_uuid(),
  project_key text not null check (length(btrim(project_key)) between 1 and 100),
  title text not null check (length(btrim(title)) between 1 and 180),
  description text not null default '' check (length(description) <= 8000),
  priority text not null default 'normal' check (priority in ('low', 'normal', 'high', 'urgent')),
  status text not null default 'backlog'
    check (status in ('backlog', 'assigned', 'in_progress', 'blocked', 'in_review', 'done')),
  created_by uuid not null references auth.users(id) on delete restrict,
  assignee_id uuid references public.owner_workforce_members(user_id) on delete restrict,
  due_at timestamptz,
  blocked_reason text check (blocked_reason is null or length(blocked_reason) <= 1000),
  completion_evidence text check (completion_evidence is null or length(completion_evidence) <= 4000),
  completed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check ((status = 'blocked' and blocked_reason is not null) or status <> 'blocked'),
  check ((status = 'done' and completion_evidence is not null) or status <> 'done')
);

create table public.owner_workforce_member_events (
  id uuid primary key default gen_random_uuid(),
  request_id uuid not null unique default gen_random_uuid(),
  member_user_id uuid not null references auth.users(id) on delete restrict,
  actor_user_id uuid not null references auth.users(id) on delete restrict,
  event_type text not null check (event_type in ('invited', 'role_changed', 'scope_changed', 'suspended', 'reactivated')),
  details jsonb not null default '{}'::jsonb check (jsonb_typeof(details) = 'object'),
  created_at timestamptz not null default now()
);

create table public.owner_workforce_task_events (
  id uuid primary key default gen_random_uuid(),
  request_id uuid not null unique default gen_random_uuid(),
  task_id uuid not null references public.owner_workforce_tasks(id) on delete restrict,
  project_key text not null,
  actor_user_id uuid not null references auth.users(id) on delete restrict,
  event_type text not null check (event_type in ('created', 'assigned', 'reassigned', 'status_changed', 'commented', 'evidence_added')),
  details jsonb not null default '{}'::jsonb check (jsonb_typeof(details) = 'object'),
  created_at timestamptz not null default now()
);

create table public.owner_release_proposals (
  id uuid primary key default gen_random_uuid(),
  project_key text not null check (length(btrim(project_key)) between 1 and 100),
  source_commit text not null check (source_commit ~ '^[0-9a-fA-F]{40}([0-9a-fA-F]{24})?$'),
  artifact_id text check (artifact_id is null or length(artifact_id) <= 500),
  summary text not null check (length(btrim(summary)) between 1 and 8000),
  risk text not null check (risk in ('low', 'medium', 'high', 'critical')),
  status text not null default 'draft'
    check (status in ('draft', 'submitted', 'checks_running', 'staging_passed', 'owner_approved',
      'production_deployed', 'monitored', 'blocked', 'rollback_requested', 'rolled_back', 'rejected')),
  test_evidence jsonb not null default '{}'::jsonb check (jsonb_typeof(test_evidence) = 'object'),
  recovery_plan text not null check (length(btrim(recovery_plan)) between 1 and 4000),
  rejection_reason text check (rejection_reason is null or length(rejection_reason) <= 2000),
  created_by uuid not null references auth.users(id) on delete restrict,
  approved_by uuid references auth.users(id) on delete restrict,
  approved_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check ((status in ('owner_approved', 'production_deployed', 'monitored') and approved_by is not null and approved_at is not null)
    or status not in ('owner_approved', 'production_deployed', 'monitored'))
);

create table public.owner_release_events (
  id uuid primary key default gen_random_uuid(),
  request_id uuid not null unique default gen_random_uuid(),
  proposal_id uuid not null references public.owner_release_proposals(id) on delete restrict,
  actor_user_id uuid references auth.users(id) on delete restrict,
  event_type text not null check (event_type in ('submitted', 'check_started', 'check_passed', 'check_failed',
    'staging_passed', 'owner_approved', 'rejected', 'deployed', 'monitoring_started',
    'blocked', 'rollback_requested', 'rolled_back')),
  evidence jsonb not null default '{}'::jsonb check (jsonb_typeof(evidence) = 'object'),
  created_at timestamptz not null default now()
);

create index owner_workforce_tasks_scope_status_idx
  on public.owner_workforce_tasks (project_key, status, updated_at desc);
create index owner_workforce_tasks_assignee_idx
  on public.owner_workforce_tasks (assignee_id, status, updated_at desc);
create index owner_workforce_task_events_task_time_idx
  on public.owner_workforce_task_events (task_id, created_at desc);
create index owner_release_proposals_status_time_idx
  on public.owner_release_proposals (status, created_at desc);
create index owner_release_events_proposal_time_idx
  on public.owner_release_events (proposal_id, created_at desc);

alter table public.owner_workforce_members enable row level security;
alter table public.owner_workforce_tasks enable row level security;
alter table public.owner_workforce_member_events enable row level security;
alter table public.owner_workforce_task_events enable row level security;
alter table public.owner_release_proposals enable row level security;
alter table public.owner_release_events enable row level security;

-- RLS-EXCEPTION: service-role-only public.owner_workforce_members
-- RLS-EXCEPTION: service-role-only public.owner_workforce_tasks
-- RLS-EXCEPTION: service-role-only public.owner_workforce_member_events
-- RLS-EXCEPTION: service-role-only public.owner_workforce_task_events
-- RLS-EXCEPTION: service-role-only public.owner_release_proposals
-- RLS-EXCEPTION: service-role-only public.owner_release_events
revoke all on table public.owner_workforce_members, public.owner_workforce_tasks,
  public.owner_workforce_member_events, public.owner_workforce_task_events,
  public.owner_release_proposals, public.owner_release_events
  from public, anon, authenticated;
grant all on table public.owner_workforce_members, public.owner_workforce_tasks,
  public.owner_release_proposals
  to service_role;

-- Audit/event history is append-only even for the service-role API client.
grant select, insert on table public.owner_workforce_member_events,
  public.owner_workforce_task_events, public.owner_release_events
  to service_role;

-- IMPORTANT: this draft intentionally omits mutation functions and policies.
-- A later implementation must add transaction-safe, server-only RPC operations
-- that validate the actor, enforce scope/AAL2, and append exactly one immutable
-- audit event in the same transaction. Never expose these tables directly.
