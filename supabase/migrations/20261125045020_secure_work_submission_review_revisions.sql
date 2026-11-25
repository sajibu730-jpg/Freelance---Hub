-- Secure client review and freelancer revision history.
create table if not exists public.work_submission_revisions (
  id uuid primary key default gen_random_uuid(),
  submission_id uuid not null references public.work_submissions(id) on delete restrict,
  version integer not null check (version > 0),
  summary text not null check (char_length(btrim(summary)) between 10 and 10000),
  file_links text[] not null default '{}'::text[],
  submitted_by uuid not null references public.profiles(id) on delete restrict,
  created_at timestamptz not null default now(),
  unique (submission_id, version)
);

create table if not exists public.work_submission_client_reviews (
  id uuid primary key default gen_random_uuid(),
  submission_id uuid not null references public.work_submissions(id) on delete restrict,
  client_id uuid not null references public.profiles(id) on delete restrict,
  decision text not null check (decision in ('revision_requested','approved')),
  comment text not null default '' check (char_length(comment) <= 10000),
  created_at timestamptz not null default now()
);

create index if not exists work_submission_revisions_submission_idx
  on public.work_submission_revisions(submission_id, version desc);
create index if not exists work_submission_client_reviews_submission_idx
  on public.work_submission_client_reviews(submission_id, created_at desc);

alter table public.work_submission_revisions enable row level security;
alter table public.work_submission_client_reviews enable row level security;

drop policy if exists work_submission_revisions_participant_select on public.work_submission_revisions;
create policy work_submission_revisions_participant_select
on public.work_submission_revisions for select to authenticated
using (
  exists (
    select 1
    from public.work_submissions ws
    join public.contracts c on c.id = ws.contract_id
    where ws.id = submission_id
      and auth.uid() in (c.client_id, c.freelancer_id)
  )
);

drop policy if exists work_submission_client_reviews_participant_select on public.work_submission_client_reviews;
create policy work_submission_client_reviews_participant_select
on public.work_submission_client_reviews for select to authenticated
using (
  exists (
    select 1
    from public.work_submissions ws
    join public.contracts c on c.id = ws.contract_id
    where ws.id = submission_id
      and auth.uid() in (c.client_id, c.freelancer_id)
  )
);

revoke insert, update, delete on public.work_submission_revisions from anon, authenticated;
revoke insert, update, delete on public.work_submission_client_reviews from anon, authenticated;
grant select on public.work_submission_revisions, public.work_submission_client_reviews to authenticated;

create or replace function freelance_hub_private.create_my_work_submission(
  p_job_id uuid, p_summary text, p_file_links text[], p_check_score integer
) returns jsonb
language plpgsql
security definer
set search_path to ''
as $function$
declare
  v_contract public.contracts%rowtype;
  v_submission public.work_submissions%rowtype;
begin
  if auth.uid() is null then
    raise exception 'Authentication required' using errcode = '42501';
  end if;
  if p_summary is null or char_length(btrim(p_summary)) < 10 or char_length(p_summary) > 10000 then
    raise exception 'Submission summary must be 10 to 10000 characters';
  end if;
  if p_check_score is null or p_check_score < 0 or p_check_score > 100 then
    raise exception 'Invalid check score';
  end if;

  select * into v_contract
  from public.contracts c
  where c.job_id = p_job_id
    and c.freelancer_id = auth.uid()
    and c.status in ('active', 'in_progress')
  order by c.created_at desc
  limit 1;

  if v_contract.id is null then
    raise exception 'An active contract for this job is required' using errcode = '42501';
  end if;

  insert into public.work_submissions (
    contract_id, job_id, freelancer_id, submission_notes, deliverable_urls
  ) values (
    v_contract.id, p_job_id, auth.uid(), btrim(p_summary),
    coalesce(p_file_links, array[]::text[])
  ) returning * into v_submission;

  insert into public.work_submission_revisions
    (submission_id, version, summary, file_links, submitted_by)
  values
    (v_submission.id, 1, v_submission.submission_notes, v_submission.deliverable_urls, auth.uid());

  return to_jsonb(v_submission) || jsonb_build_object(
    'summary', v_submission.submission_notes,
    'file_links', to_jsonb(v_submission.deliverable_urls),
    'check_score', 0
  );
end;
$function$;

create or replace function public.create_my_work_submission(
  p_job_id uuid, p_summary text, p_file_links text[], p_check_score integer
) returns jsonb
language sql
set search_path to ''
as $function$
  select freelance_hub_private.create_my_work_submission(
    p_job_id, p_summary, p_file_links, p_check_score
  );
$function$;

create or replace function public.review_my_work_submission(
  p_submission_id uuid, p_status text, p_comment text default ''
) returns jsonb
language plpgsql
security definer
set search_path to ''
as $function$
declare
  v_submission public.work_submissions%rowtype;
  v_contract public.contracts%rowtype;
  v_status text;
  v_comment text := coalesce(p_comment, '');
begin
  if auth.uid() is null then
    raise exception 'Authentication required' using errcode = '42501';
  end if;
  if p_status not in ('Approved', 'Changes requested') then
    raise exception 'Invalid review decision';
  end if;
  if char_length(v_comment) > 10000 then
    raise exception 'Review comment is too long';
  end if;

  select ws.* into v_submission
  from public.work_submissions ws
  where ws.id = p_submission_id
  for update;
  if v_submission.id is null then
    raise exception 'Submission not found' using errcode = 'P0002';
  end if;

  select * into v_contract from public.contracts c
  where c.id = v_submission.contract_id;
  if v_contract.client_id is distinct from auth.uid() then
    raise exception 'Only the contract client can review this submission' using errcode = '42501';
  end if;
  if v_contract.status not in ('active', 'in_progress') then
    raise exception 'Contract is not open for review';
  end if;
  if v_submission.status not in ('submitted', 'analysis_pending', 'revision_requested') then
    raise exception 'Submission cannot be reviewed in its current state';
  end if;
  if p_status = 'Changes requested' and btrim(v_comment) = '' then
    raise exception 'Please explain what needs to change';
  end if;

  v_status := case when p_status = 'Approved' then 'approved' else 'revision_requested' end;
  update public.work_submissions
  set status = v_status, reviewed_at = now(), updated_at = now()
  where id = p_submission_id;

  insert into public.work_submission_client_reviews
    (submission_id, client_id, decision, comment)
  values
    (p_submission_id, auth.uid(), v_status, v_comment);

  return jsonb_build_object(
    'submission_id', p_submission_id,
    'status', p_status,
    'comment', v_comment
  );
end;
$function$;

create or replace function public.resubmit_my_work_submission(
  p_submission_id uuid, p_summary text, p_file_links text[] default '{}'::text[]
) returns jsonb
language plpgsql
security definer
set search_path to ''
as $function$
declare
  v_submission public.work_submissions%rowtype;
  v_contract public.contracts%rowtype;
  v_version integer;
begin
  if auth.uid() is null then
    raise exception 'Authentication required' using errcode = '42501';
  end if;
  if p_summary is null or char_length(btrim(p_summary)) < 10 or char_length(p_summary) > 10000 then
    raise exception 'Revision summary must be 10 to 10000 characters';
  end if;

  select ws.* into v_submission
  from public.work_submissions ws
  where ws.id = p_submission_id
  for update;
  if v_submission.id is null then
    raise exception 'Submission not found' using errcode = 'P0002';
  end if;
  if v_submission.freelancer_id is distinct from auth.uid() then
    raise exception 'Only the assigned freelancer can resubmit this work' using errcode = '42501';
  end if;
  if v_submission.status <> 'revision_requested' then
    raise exception 'The client must request changes before a revision can be submitted';
  end if;

  select * into v_contract from public.contracts c
  where c.id = v_submission.contract_id;
  if v_contract.freelancer_id is distinct from auth.uid()
     or v_contract.status not in ('active', 'in_progress') then
    raise exception 'An active assigned contract is required' using errcode = '42501';
  end if;

  select coalesce(max(r.version), 0) + 1 into v_version
  from public.work_submission_revisions r
  where r.submission_id = p_submission_id;

  update public.work_submissions
  set submission_notes = btrim(p_summary),
      deliverable_urls = coalesce(p_file_links, '{}'::text[]),
      status = 'submitted',
      submitted_at = now(),
      reviewed_at = null,
      updated_at = now()
  where id = p_submission_id
  returning * into v_submission;

  insert into public.work_submission_revisions
    (submission_id, version, summary, file_links, submitted_by)
  values
    (p_submission_id, v_version, v_submission.submission_notes,
     v_submission.deliverable_urls, auth.uid());

  return to_jsonb(v_submission) || jsonb_build_object(
    'summary', v_submission.submission_notes,
    'file_links', to_jsonb(v_submission.deliverable_urls),
    'check_score', 0
  );
end;
$function$;

create or replace function public.list_my_work_submission_revisions(
  p_submission_id uuid
) returns jsonb
language plpgsql
stable
security definer
set search_path to ''
as $function$
declare
  v_allowed boolean;
begin
  if auth.uid() is null then
    raise exception 'Authentication required' using errcode = '42501';
  end if;
  select exists (
    select 1
    from public.work_submissions ws
    join public.contracts c on c.id = ws.contract_id
    where ws.id = p_submission_id
      and auth.uid() in (c.client_id, c.freelancer_id)
  ) into v_allowed;
  if not v_allowed then
    raise exception 'Submission not found or access denied' using errcode = '42501';
  end if;

  return coalesce((
    select jsonb_agg(
      jsonb_build_object(
        'id', r.id,
        'submission_id', r.submission_id,
        'version', r.version,
        'summary', r.summary,
        'file_links', to_jsonb(r.file_links),
        'created_at', r.created_at
      ) order by r.version
    )
    from public.work_submission_revisions r
    where r.submission_id = p_submission_id
  ), '[]'::jsonb);
end;
$function$;

revoke all on function public.review_my_work_submission(uuid,text,text) from public, anon;
revoke all on function public.resubmit_my_work_submission(uuid,text,text[]) from public, anon;
revoke all on function public.list_my_work_submission_revisions(uuid) from public, anon;
grant execute on function public.review_my_work_submission(uuid,text,text) to authenticated;
grant execute on function public.resubmit_my_work_submission(uuid,text,text[]) to authenticated;
grant execute on function public.list_my_work_submission_revisions(uuid) to authenticated;
