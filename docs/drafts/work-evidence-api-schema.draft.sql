-- DRAFT ONLY — do not apply to Production.
-- Evidence API v1: private proof storage, participant-linked records, Owner review,
-- and transactionally generated append-only audit events.
-- Requires disposable PostgreSQL/Supabase validation and explicit Owner approval.

create table public.work_evidence (
  id uuid primary key default gen_random_uuid(),
  request_id uuid not null,
  submitted_by uuid not null references auth.users(id) on delete restrict,
  origin text not null check (origin in ('in_app', 'off_app')),
  evidence_type text not null check (evidence_type in ('file', 'link', 'note')),
  title text not null check (length(btrim(title)) between 1 and 160),
  description text not null default '' check (length(description) <= 3000),
  job_id uuid references public.jobs(id) on delete restrict,
  contract_id uuid references public.contracts(id) on delete restrict,
  submission_id uuid references public.work_submissions(id) on delete restrict,
  external_url text check (external_url is null or length(external_url) <= 2048),
  storage_path text unique check (storage_path is null or length(storage_path) <= 500),
  original_filename text check (original_filename is null or length(original_filename) <= 128),
  mime_type text check (mime_type is null or mime_type in ('application/pdf', 'image/jpeg', 'image/png', 'image/webp')),
  byte_size bigint check (byte_size is null or byte_size between 1 and 10485760),
  status text not null check (status in ('upload_pending', 'submitted', 'verified', 'rejected', 'needs_info', 'upload_failed')),
  submitted_at timestamptz,
  reviewed_by uuid references auth.users(id) on delete restrict,
  reviewed_at timestamptz,
  review_note text check (review_note is null or length(review_note) <= 2000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (submitted_by, request_id),
  check (
    (evidence_type = 'file' and storage_path is not null and original_filename is not null
      and mime_type is not null and byte_size is not null and external_url is null)
    or (evidence_type = 'link' and external_url is not null and storage_path is null
      and original_filename is null and mime_type is null and byte_size is null)
    or (evidence_type = 'note' and external_url is null and storage_path is null
      and original_filename is null and mime_type is null and byte_size is null)
  ),
  check ((status in ('verified', 'rejected', 'needs_info') and reviewed_by is not null and reviewed_at is not null)
      or status not in ('verified', 'rejected', 'needs_info')),
  check ((status = 'upload_pending' and evidence_type = 'file' and submitted_at is null)
      or (status <> 'upload_pending' and submitted_at is not null))
);

create index work_evidence_owner_review_idx
  on public.work_evidence (status, submitted_at desc);
create index work_evidence_submitter_idx
  on public.work_evidence (submitted_by, created_at desc);
create index work_evidence_job_idx
  on public.work_evidence (job_id, created_at desc) where job_id is not null;
create index work_evidence_contract_idx
  on public.work_evidence (contract_id, created_at desc) where contract_id is not null;

-- Atomic per-user rolling 24-hour quota. The Edge Function can call only this
-- SECURITY DEFINER function; API roles cannot read or write the quota ledger.
create table public.work_evidence_quota_events (
  id uuid primary key default gen_random_uuid(),
  submitted_by uuid not null references auth.users(id) on delete restrict,
  request_id uuid not null,
  created_at timestamptz not null default now(),
  unique (submitted_by, request_id)
);
create index work_evidence_quota_events_window_idx
  on public.work_evidence_quota_events (submitted_by, created_at desc);
alter table public.work_evidence_quota_events enable row level security;
revoke all on table public.work_evidence_quota_events from public, anon, authenticated, service_role;

create or replace function public.claim_work_evidence_quota(
  p_submitted_by uuid,
  p_request_id uuid
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $quota$
declare
  v_count integer;
begin
  if p_submitted_by is null or p_request_id is null then
    raise exception using errcode = '22023', message = 'Submitter and request ID are required';
  end if;

  -- Serialize quota claims for this account so concurrent requests cannot exceed 20.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_submitted_by::text, 0)
  );

  delete from public.work_evidence_quota_events
  where submitted_by = p_submitted_by
    and created_at < pg_catalog.now() - interval '24 hours';

  if exists (
    select 1 from public.work_evidence_quota_events
    where submitted_by = p_submitted_by and request_id = p_request_id
  ) then
    return true;
  end if;

  select count(*) into v_count
  from public.work_evidence_quota_events
  where submitted_by = p_submitted_by
    and created_at >= pg_catalog.now() - interval '24 hours';

  if v_count >= 20 then
    return false;
  end if;

  insert into public.work_evidence_quota_events (submitted_by, request_id)
  values (p_submitted_by, p_request_id);
  return true;
end;
$quota$;

revoke all on function public.claim_work_evidence_quota(uuid, uuid) from public, anon, authenticated;
grant execute on function public.claim_work_evidence_quota(uuid, uuid) to service_role;

create table public.work_evidence_events (
  id uuid primary key default gen_random_uuid(),
  evidence_id uuid not null references public.work_evidence(id) on delete restrict,
  actor_user_id uuid references auth.users(id) on delete restrict,
  event_type text not null check (event_type in ('created', 'upload_completed', 'upload_failed', 'submitted', 'reviewed', 'storage_cleanup_completed', 'storage_cleanup_retry')),
  from_status text,
  to_status text not null,
  details jsonb not null default '{}'::jsonb check (jsonb_typeof(details) = 'object'),
  created_at timestamptz not null default now()
);
create index work_evidence_events_timeline_idx
  on public.work_evidence_events (evidence_id, created_at asc);

-- Durable, retryable queue for removing abandoned private Storage objects.
-- The evidence row and its audit history are retained permanently.
create table public.work_evidence_cleanup_queue (
  evidence_id uuid primary key references public.work_evidence(id) on delete restrict,
  storage_path text not null unique check (length(storage_path) <= 500),
  state text not null default 'pending' check (state in ('pending', 'processing', 'completed')),
  attempts integer not null default 0 check (attempts >= 0),
  next_attempt_at timestamptz not null default now(),
  lease_started_at timestamptz,
  last_error text check (last_error is null or length(last_error) <= 500),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  completed_at timestamptz,
  check ((state = 'processing' and lease_started_at is not null)
      or (state <> 'processing' and lease_started_at is null)),
  check ((state = 'completed' and completed_at is not null)
      or (state <> 'completed' and completed_at is null))
);
create index work_evidence_cleanup_due_idx
  on public.work_evidence_cleanup_queue (next_attempt_at, created_at)
  where state = 'pending';
alter table public.work_evidence_cleanup_queue enable row level security;
revoke all on table public.work_evidence_cleanup_queue from public, anon, authenticated, service_role;
grant select, insert, update on table public.work_evidence_cleanup_queue to service_role;

alter table public.work_evidence enable row level security;
alter table public.work_evidence_events enable row level security;

-- No direct Data API access. The Edge Function uses the server-only service role
-- after validating the caller's JWT, participant relationship, and Owner MFA.
revoke all on table public.work_evidence, public.work_evidence_events from public, anon, authenticated;
grant select, insert, update on table public.work_evidence to service_role;
grant select, insert on table public.work_evidence_events to service_role;

create or replace function public.guard_work_evidence_update()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  v_status_changed boolean := new.status is distinct from old.status;
  v_review_changed boolean :=
    new.reviewed_by is distinct from old.reviewed_by
    or new.reviewed_at is distinct from old.reviewed_at
    or new.review_note is distinct from old.review_note;
  v_is_review_status boolean := new.status in ('verified', 'rejected', 'needs_info');
begin
  if new.id is distinct from old.id
    or new.request_id is distinct from old.request_id
    or new.submitted_by is distinct from old.submitted_by
    or new.origin is distinct from old.origin
    or new.evidence_type is distinct from old.evidence_type
    or new.title is distinct from old.title
    or new.description is distinct from old.description
    or new.job_id is distinct from old.job_id
    or new.contract_id is distinct from old.contract_id
    or new.submission_id is distinct from old.submission_id
    or new.external_url is distinct from old.external_url
    or new.storage_path is distinct from old.storage_path
    or new.original_filename is distinct from old.original_filename
    or new.mime_type is distinct from old.mime_type
    or new.byte_size is distinct from old.byte_size
    or new.created_at is distinct from old.created_at then
    raise exception 'Submitted evidence identity and original proof are immutable';
  end if;

  if v_status_changed and not (
    (old.status = 'upload_pending' and new.status in ('submitted', 'upload_failed'))
    or (old.status in ('submitted', 'needs_info')
      and new.status in ('verified', 'rejected', 'needs_info'))
  ) then
    raise exception 'Invalid work evidence status transition: % -> %', old.status, new.status;
  end if;

  if new.submitted_at is distinct from old.submitted_at then
    if old.status <> 'upload_pending'
      or new.status not in ('submitted', 'upload_failed')
      or new.submitted_at is null then
      raise exception 'Submission timestamp can only be set when an upload leaves pending state';
    end if;
  end if;

  if v_review_changed then
    if old.status not in ('submitted', 'needs_info')
      or not v_is_review_status
      or new.reviewed_by is null
      or new.reviewed_at is null then
      raise exception 'Review metadata can only change during an authorized review transition';
    end if;
  elsif v_status_changed and v_is_review_status then
    raise exception 'A review status transition must include reviewer identity and timestamp';
  end if;

  new.updated_at := pg_catalog.now();
  return new;
end;
$$;

create or replace function public.audit_work_evidence_change()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  v_actor uuid;
  v_event text;
  v_from text;
  v_details jsonb := '{}'::jsonb;
begin
  if tg_op = 'INSERT' then
    v_actor := new.submitted_by;
    v_event := case when new.status = 'submitted' then 'submitted' else 'created' end;
    v_from := null;
    v_details := jsonb_build_object('origin', new.origin, 'evidence_type', new.evidence_type);
  else
    v_actor := coalesce(new.reviewed_by, new.submitted_by);
    v_from := old.status;
    v_event := case
      when old.status = 'upload_pending' and new.status = 'submitted' then 'upload_completed'
      when new.status = 'upload_failed' then 'upload_failed'
      when new.status in ('verified', 'rejected', 'needs_info') then 'reviewed'
      else 'submitted'
    end;
    if new.status in ('verified', 'rejected', 'needs_info') then
      v_details := jsonb_build_object('review_note', new.review_note);
    end if;
  end if;

  insert into public.work_evidence_events
    (evidence_id, actor_user_id, event_type, from_status, to_status, details)
  values
    (new.id, v_actor, v_event, v_from, new.status, v_details);

  -- Any failed pending upload is queued transactionally for idempotent Storage cleanup.
  if tg_op = 'UPDATE' and old.status = 'upload_pending'
    and new.status = 'upload_failed' and new.storage_path is not null then
    insert into public.work_evidence_cleanup_queue (evidence_id, storage_path)
    values (new.id, new.storage_path)
    on conflict (evidence_id) do nothing;
  end if;
  return new;
end;
$$;

create or replace function public.prevent_work_evidence_event_mutation()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  raise exception 'Evidence audit events are append-only';
end;
$$;

create trigger work_evidence_guard_update
before update on public.work_evidence
for each row execute function public.guard_work_evidence_update();

create trigger work_evidence_audit
after insert or update on public.work_evidence
for each row execute function public.audit_work_evidence_change();

create trigger work_evidence_events_no_update
before update or delete on public.work_evidence_events
for each row execute function public.prevent_work_evidence_event_mutation();

-- Claim an atomic batch of due cleanup jobs. Expire uploads only after 24 hours;
-- retry_upload is separately capped so no fresh two-hour URL overlaps this deadline.
create or replace function public.claim_work_evidence_cleanup_batch(p_limit integer default 50)
returns table (evidence_id uuid, storage_path text, attempt_count integer)
language plpgsql
security definer
set search_path = ''
as $cleanup_claim$
begin
  if p_limit is null or p_limit < 1 or p_limit > 100 then
    raise exception using errcode = '22023', message = 'Cleanup batch size must be between 1 and 100';
  end if;

  update public.work_evidence
  set status = 'upload_failed',
      submitted_at = pg_catalog.now()
  where status = 'upload_pending'
    and created_at <= pg_catalog.now() - interval '24 hours';

  update public.work_evidence_cleanup_queue
  set state = 'pending',
      lease_started_at = null,
      updated_at = pg_catalog.now()
  where state = 'processing'
    and lease_started_at <= pg_catalog.now() - interval '15 minutes';

  return query
  with candidates as (
    select q.evidence_id
    from public.work_evidence_cleanup_queue q
    where q.state = 'pending'
      and q.next_attempt_at <= pg_catalog.now()
    order by q.created_at, q.evidence_id
    for update skip locked
    limit p_limit
  ),
  claimed as (
    update public.work_evidence_cleanup_queue q
    set state = 'processing',
        attempts = q.attempts + 1,
        lease_started_at = pg_catalog.now(),
        updated_at = pg_catalog.now()
    from candidates c
    where q.evidence_id = c.evidence_id
    returning q.evidence_id, q.storage_path, q.attempts
  )
  select claimed.evidence_id, claimed.storage_path, claimed.attempts
  from claimed;
end;
$cleanup_claim$;

create or replace function public.finish_work_evidence_cleanup(
  p_evidence_id uuid,
  p_succeeded boolean,
  p_error text default null
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $cleanup_finish$
declare
  v_job public.work_evidence_cleanup_queue%rowtype;
begin
  if p_evidence_id is null or p_succeeded is null then
    raise exception using errcode = '22023', message = 'Evidence ID and cleanup result are required';
  end if;

  update public.work_evidence_cleanup_queue
  set state = case when p_succeeded then 'completed' else 'pending' end,
      lease_started_at = null,
      next_attempt_at = case
        when p_succeeded then next_attempt_at
        else pg_catalog.now() + pg_catalog.make_interval(
          secs => least(3600, (30 * pg_catalog.power(2, least(attempts, 7)))::integer)
        )
      end,
      last_error = case when p_succeeded then null else left(coalesce(p_error, 'Storage cleanup failed'), 500) end,
      updated_at = pg_catalog.now(),
      completed_at = case when p_succeeded then pg_catalog.now() else null end
  where evidence_id = p_evidence_id
    and state = 'processing'
  returning * into v_job;

  if not found then return false; end if;

  insert into public.work_evidence_events
    (evidence_id, actor_user_id, event_type, from_status, to_status, details)
  values (
    v_job.evidence_id,
    null,
    case when p_succeeded then 'storage_cleanup_completed' else 'storage_cleanup_retry' end,
    'upload_failed',
    'upload_failed',
    jsonb_build_object('attempt', v_job.attempts)
  );
  return true;
end;
$cleanup_finish$;

revoke all on function public.claim_work_evidence_cleanup_batch(integer) from public, anon, authenticated;
revoke all on function public.finish_work_evidence_cleanup(uuid, boolean, text) from public, anon, authenticated;
grant execute on function public.claim_work_evidence_cleanup_batch(integer) to service_role;
grant execute on function public.finish_work_evidence_cleanup(uuid, boolean, text) to service_role;

-- Private bucket; no client Storage policies are created. Upload/download access
-- is granted only through short-lived signed URLs after Edge Function checks.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'work-evidence-private',
  'work-evidence-private',
  false,
  10485760,
  array['application/pdf', 'image/jpeg', 'image/png', 'image/webp']
)
on conflict (id) do nothing;

do $$
begin
  if exists (
    select 1 from storage.buckets
    where id = 'work-evidence-private'
      and (public is true or file_size_limit is distinct from 10485760
        or allowed_mime_types is distinct from array['application/pdf', 'image/jpeg', 'image/png', 'image/webp'])
  ) then
    raise exception 'Existing work-evidence-private bucket has unsafe settings; manual review required';
  end if;
end;
$$;
