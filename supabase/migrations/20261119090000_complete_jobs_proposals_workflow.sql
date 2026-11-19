-- Jobs and proposals workflow hardening.
-- Existing row-level policies remain the authorization boundary.
grant select, insert, update on table public.jobs to authenticated;
grant select, insert, update on table public.proposals to authenticated;
grant select on table public.contracts to authenticated;

create or replace function public.accept_job_proposal(p_proposal_id uuid)
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

  select p.* into v_proposal
  from public.proposals p
  join public.jobs j on j.id = p.job_id
  where p.id = p_proposal_id
    and j.owner_id = auth.uid()
    and j.status = 'open'
    and p.status in ('pending', 'submitted', 'shortlisted')
  for update of p;

  if not found then
    raise exception 'Proposal not found or no longer actionable' using errcode = 'P0002';
  end if;

  select j.* into v_job
  from public.jobs j
  where j.id = v_proposal.job_id
    and j.owner_id = auth.uid()
    and j.status = 'open'
  for update;

  if not found then
    raise exception 'Job is no longer open' using errcode = 'P0001';
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

create or replace function public.reject_job_proposal(p_proposal_id uuid)
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

revoke all on function public.accept_job_proposal(uuid) from public, anon;
revoke all on function public.reject_job_proposal(uuid) from public, anon;
grant execute on function public.accept_job_proposal(uuid) to authenticated;
grant execute on function public.reject_job_proposal(uuid) to authenticated;
