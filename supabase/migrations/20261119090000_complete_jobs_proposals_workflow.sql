-- Jobs and proposals workflow hardening.
alter table public.proposals add column if not exists delivery_days integer not null default 7;
do $delivery_days$ begin
  if not exists (select 1 from pg_constraint where conname = 'proposals_delivery_days_range') then
    alter table public.proposals add constraint proposals_delivery_days_range check (delivery_days between 1 and 3650);
  end if;
end;
$delivery_days$;

-- Existing row-level policies remain the authorization boundary.
grant select, insert, update on table public.jobs to authenticated;
grant select, insert, update on table public.proposals to authenticated;
grant select on table public.contracts to authenticated;

-- Keep privileged implementations in the existing non-exposed private schema.
create schema if not exists freelance_hub_private;
revoke all on schema freelance_hub_private from public, anon;
grant usage on schema freelance_hub_private to authenticated;

create or replace function freelance_hub_private.accept_job_proposal(p_proposal_id uuid)
returns public.contracts
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_proposal public.proposals%rowtype;
  v_job public.jobs%rowtype;
  v_contract public.contracts%rowtype;
begin
  if auth.uid() is null then
    raise exception 'Authentication required' using errcode = '28000';
  end if;

  -- Lock the parent job first. All accept operations for one job serialize
  -- on this row before locking proposal rows, avoiding cross-proposal deadlocks.
  select j.* into v_job
  from public.jobs j
  where j.id = (
      select p.job_id from public.proposals p where p.id = p_proposal_id
    )
    and j.owner_id = auth.uid()
    and j.status = 'open'
  for update;

  if not found then
    raise exception 'Proposal not found or job is no longer open' using errcode = 'P0002';
  end if;

  select p.* into v_proposal
  from public.proposals p
  where p.id = p_proposal_id
    and p.job_id = v_job.id
    and p.status in ('pending', 'submitted', 'shortlisted')
  for update;

  if not found then
    raise exception 'Proposal not found or no longer actionable' using errcode = 'P0002';
  end if;

  insert into public.contracts (
    job_id, proposal_id, client_id, freelancer_id,
    agreed_budget_minor, currency, status
  ) values (
    v_job.id, v_proposal.id, v_job.owner_id, v_proposal.freelancer_id,
    v_proposal.proposed_budget_minor, v_proposal.currency, 'active'
  )
  returning * into v_contract;

  update public.proposals
  set status = case when id = v_proposal.id then 'accepted' else 'rejected' end,
      updated_at = now()
  where job_id = v_job.id
    and status in ('pending', 'submitted', 'shortlisted');

  update public.jobs
  set status = 'closed', updated_at = now()
  where id = v_job.id;

  return v_contract;
end;
$$;

create or replace function freelance_hub_private.reject_job_proposal(p_proposal_id uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_updated integer;
begin
  if auth.uid() is null then
    raise exception 'Authentication required' using errcode = '28000';
  end if;

  update public.proposals p
  set status = 'rejected', updated_at = now()
  from public.jobs j
  where p.id = p_proposal_id
    and j.id = p.job_id
    and j.owner_id = auth.uid()
    and j.status = 'open'
    and p.status in ('pending', 'submitted', 'shortlisted');

  get diagnostics v_updated = row_count;
  if v_updated = 0 then
    raise exception 'Proposal not found or no longer actionable' using errcode = 'P0002';
  end if;

  return true;
end;
$$;

-- Public API wrappers execute as the caller; only the private, owner-checked
-- implementation runs with elevated privileges.
create or replace function public.accept_job_proposal(p_proposal_id uuid)
returns public.contracts
language sql
security invoker
set search_path = ''
as $accept$
  select * from freelance_hub_private.accept_job_proposal(p_proposal_id);
$accept$;

create or replace function public.reject_job_proposal(p_proposal_id uuid)
returns boolean
language sql
security invoker
set search_path = ''
as $reject$
  select freelance_hub_private.reject_job_proposal(p_proposal_id);
$reject$;

revoke all on function freelance_hub_private.accept_job_proposal(uuid) from public, anon;
revoke all on function freelance_hub_private.reject_job_proposal(uuid) from public, anon;
grant execute on function freelance_hub_private.accept_job_proposal(uuid) to authenticated;
grant execute on function freelance_hub_private.reject_job_proposal(uuid) to authenticated;

revoke all on function public.accept_job_proposal(uuid) from public, anon;
revoke all on function public.reject_job_proposal(uuid) from public, anon;
grant execute on function public.accept_job_proposal(uuid) to authenticated;
grant execute on function public.reject_job_proposal(uuid) to authenticated;
