\set ON_ERROR_STOP on

-- Isolated test schema for the Jobs/Proposals workflow migration.
-- PostgreSQL roles are cluster-wide, so reuse roles created by other CI suites.
do $roles$ begin
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then
    create role authenticated nologin;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'anon') then
    create role anon nologin;
  end if;
end; $roles$;
create schema if not exists auth;
create or replace function auth.uid()
returns uuid
language sql
stable
as $$
  select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$$;

create table public.jobs (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null,
  title text not null,
  description text not null,
  budget_minor bigint not null default 0,
  currency text not null default 'USD',
  status text not null default 'open',
  skills text[] not null default '{}',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.proposals (
  id uuid primary key default gen_random_uuid(),
  job_id uuid not null references public.jobs(id),
  freelancer_id uuid not null,
  cover_letter text not null,
  proposed_budget_minor bigint not null,
  currency text not null default 'USD',
  status text not null default 'pending',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.contracts (
  id uuid primary key default gen_random_uuid(),
  job_id uuid not null references public.jobs(id),
  proposal_id uuid not null references public.proposals(id),
  client_id uuid not null,
  freelancer_id uuid not null,
  agreed_budget_minor bigint not null,
  currency text not null default 'USD',
  status text not null default 'active',
  started_at timestamptz not null default now(),
  completed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

\ir ../../supabase/migrations/20261119090000_complete_jobs_proposals_workflow.sql
\ir ../../supabase/migrations/20261119232200_add_job_posting_requirements.sql

do $test$
declare
  v_client uuid := '10000000-0000-4000-8000-000000000001';
  v_freelancer uuid := '20000000-0000-4000-8000-000000000002';
  v_other_freelancer uuid := '30000000-0000-4000-8000-000000000003';
  v_job uuid;
  v_first_proposal uuid;
  v_second_proposal uuid;
  v_contract public.contracts%rowtype;
begin
  perform set_config('request.jwt.claim.sub', v_client::text, true);

  insert into public.jobs(owner_id,title,description,budget_minor,category,experience_level,duration_days,freelancers_needed)
  values(v_client,'Build a landing page','A detailed landing page project for testing.',50000,'Programming & Tech','Intermediate',14,2)
  returning id into v_job;

  if not exists (
    select 1 from public.jobs
    where id=v_job and category='Programming & Tech'
      and experience_level='Intermediate' and duration_days=14 and freelancers_needed=2
  ) then
    raise exception 'Job posting metadata was not saved correctly';
  end if;

  insert into public.proposals(job_id,freelancer_id,cover_letter,proposed_budget_minor,delivery_days)
  values(v_job,v_freelancer,'I can deliver a responsive and tested landing page.',45000,5)
  returning id into v_first_proposal;

  insert into public.proposals(job_id,freelancer_id,cover_letter,proposed_budget_minor,delivery_days)
  values(v_job,v_other_freelancer,'I can also deliver the requested project.',47000,7)
  returning id into v_second_proposal;

  select * into v_contract from public.accept_job_proposal(v_first_proposal);

  if v_contract.job_id <> v_job or v_contract.freelancer_id <> v_freelancer
     or v_contract.agreed_budget_minor <> 45000 or v_contract.status <> 'active' then
    raise exception 'Accepted proposal did not create the expected active contract';
  end if;

  if (select status from public.jobs where id=v_job) <> 'closed' then
    raise exception 'Accepted job was not closed';
  end if;

  if (select status from public.proposals where id=v_first_proposal) <> 'accepted' then
    raise exception 'Winning proposal was not accepted';
  end if;

  if (select status from public.proposals where id=v_second_proposal) <> 'rejected' then
    raise exception 'Competing proposal was not rejected';
  end if;

  if (select count(*) from public.contracts where job_id=v_job) <> 1 then
    raise exception 'Acceptance must create exactly one contract';
  end if;
end;
$test$;

do $reject_test$
declare
  v_client uuid := '40000000-0000-4000-8000-000000000004';
  v_freelancer uuid := '50000000-0000-4000-8000-000000000005';
  v_job uuid;
  v_proposal uuid;
begin
  perform set_config('request.jwt.claim.sub', v_client::text, true);
  insert into public.jobs(owner_id,title,description)
  values(v_client,'Design a logo','A logo design project for rejection testing.')
  returning id into v_job;
  insert into public.proposals(job_id,freelancer_id,cover_letter,proposed_budget_minor)
  values(v_job,v_freelancer,'I can design the logo for your business.',12000)
  returning id into v_proposal;

  if public.reject_job_proposal(v_proposal) is not true then
    raise exception 'Reject RPC did not return true';
  end if;
  if (select status from public.proposals where id=v_proposal) <> 'rejected' then
    raise exception 'Proposal was not rejected';
  end if;
end;
$reject_test$;

do $authorization_test$
declare
  v_client uuid := '60000000-0000-4000-8000-000000000006';
  v_other_client uuid := '70000000-0000-4000-8000-000000000007';
  v_freelancer uuid := '80000000-0000-4000-8000-000000000008';
  v_job uuid;
  v_proposal uuid;
  v_blocked boolean := false;
begin
  perform set_config('request.jwt.claim.sub', v_client::text, true);
  insert into public.jobs(owner_id,title,description)
  values(v_client,'Private client job','Only the owner may manage proposals.')
  returning id into v_job;
  insert into public.proposals(job_id,freelancer_id,cover_letter,proposed_budget_minor)
  values(v_job,v_freelancer,'I am interested in this protected job.',8000)
  returning id into v_proposal;

  perform set_config('request.jwt.claim.sub', v_other_client::text, true);
  begin
    perform public.reject_job_proposal(v_proposal);
  exception when others then
    v_blocked := true;
  end;
  if not v_blocked then
    raise exception 'A different client was able to reject another client proposal';
  end if;
  if (select status from public.proposals where id=v_proposal) <> 'pending' then
    raise exception 'Unauthorized proposal status was modified';
  end if;

  v_blocked := false;
  begin
    perform public.accept_job_proposal(v_proposal);
  exception when others then
    v_blocked := true;
  end;
  if not v_blocked then
    raise exception 'A different client was able to accept another client proposal';
  end if;
  if (select status from public.proposals where id=v_proposal) <> 'pending' then
    raise exception 'Unauthorized accept modified proposal status';
  end if;
end;
$authorization_test$;

do $metadata_constraint_tests$
declare
  v_client uuid := '90000000-0000-4000-8000-000000000009';
  v_rejected boolean := false;
begin
  begin
    insert into public.jobs(owner_id,title,description,experience_level)
    values(v_client,'Invalid experience','Testing invalid experience level.','Advanced');
  exception when check_violation then
    v_rejected := true;
  end;
  if not v_rejected then raise exception 'Unsupported experience level was accepted'; end if;

  v_rejected := false;
  begin
    insert into public.jobs(owner_id,title,description,duration_days)
    values(v_client,'Invalid duration','Testing invalid duration.',0);
  exception when check_violation then
    v_rejected := true;
  end;
  if not v_rejected then raise exception 'Invalid project duration was accepted'; end if;

  v_rejected := false;
  begin
    insert into public.jobs(owner_id,title,description,freelancers_needed)
    values(v_client,'Invalid freelancer count','Testing invalid freelancer count.',101);
  exception when check_violation then
    v_rejected := true;
  end;
  if not v_rejected then raise exception 'Invalid freelancer count was accepted'; end if;
end;
$metadata_constraint_tests$;

select 'jobs-proposals-workflow-tests: PASS' as result;
