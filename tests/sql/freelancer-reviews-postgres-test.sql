-- Local PostgreSQL integration harness for the draft Supabase SQL.
-- Uses synthetic identities and never connects to the production database.
\set ON_ERROR_STOP on

do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'anon') then
    create role anon nologin;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then
    create role authenticated nologin;
  end if;
end
$$;

create schema if not exists auth;
create or replace function auth.uid()
returns uuid
language sql
stable
as $$
  select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$$;
grant usage on schema auth to authenticated;
grant execute on function auth.uid() to authenticated;

create table public.profiles (
  id uuid primary key,
  display_name text,
  photo_url text,
  cover_photo_url text,
  country text,
  bio text,
  capabilities text,
  skills text,
  email text,
  mobile_number text
);

create table public.contracts (
  id uuid primary key,
  client_id uuid not null references public.profiles(id),
  freelancer_id uuid not null references public.profiles(id),
  agreed_budget_minor bigint not null,
  currency text not null,
  status text not null,
  completed_at timestamptz,
  updated_at timestamptz not null default now()
);

create table public.work_submissions (
  id uuid primary key,
  contract_id uuid not null references public.contracts(id),
  freelancer_id uuid not null references public.profiles(id),
  status text not null
);

-- Mirror the normal Supabase authenticated read grant used by the review RLS policy.
grant select on public.contracts to authenticated;

insert into public.profiles
  (id, display_name, country, bio, capabilities, skills, email, mobile_number)
values
  ('10000000-0000-0000-0000-000000000001', 'Test Client', 'UAE', 'Client bio', '', '', 'client@example.test', '+971000000001'),
  ('20000000-0000-0000-0000-000000000002', 'Test Freelancer', 'UAE', 'Freelancer bio', 'Design', 'UI,UX', 'freelancer@example.test', '+971000000002'),
  ('30000000-0000-0000-0000-000000000003', 'Other Client', 'UAE', '', '', '', 'other@example.test', '+971000000003');

insert into public.contracts
  (id, client_id, freelancer_id, agreed_budget_minor, currency, status)
values
  ('a0000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', 125000, 'USD', 'active'),
  ('a0000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', 50000, 'USD', 'active'),
  ('a0000000-0000-0000-0000-000000000003', '30000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000002', 90000, 'EUR', 'active');

insert into public.work_submissions (id, contract_id, freelancer_id, status)
values
  ('b0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', 'approved'),
  ('b0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000002', 'submitted');

\i docs/drafts/freelancer-reviews-profile-stats.sql

-- Client completes only the contract with an approved submission.
set role authenticated;
select set_config('request.jwt.claim.sub', '10000000-0000-0000-0000-000000000001', false);
do $$
declare result jsonb;
begin
  result := public.complete_contract('a0000000-0000-0000-0000-000000000001');
  if result->>'status' <> 'completed' then
    raise exception 'approved contract was not completed';
  end if;
  -- Idempotent retry must return the same completed contract.
  result := public.complete_contract('a0000000-0000-0000-0000-000000000001');
  if result->>'status' <> 'completed' then
    raise exception 'completion retry was not idempotent';
  end if;
  begin
    perform public.complete_contract('a0000000-0000-0000-0000-000000000002');
    raise exception 'pending submission was incorrectly completed';
  exception when others then
    if sqlerrm = 'pending submission was incorrectly completed' then raise; end if;
    if sqlerrm <> 'CONTRACT_NOT_READY_OR_NOT_AUTHORIZED' then raise; end if;
  end;
end
$$;

-- A client may create one review for their completed contract.
insert into public.freelancer_reviews
  (contract_id, client_id, freelancer_id, rating, review_text)
values
  ('a0000000-0000-0000-0000-000000000001',
   '10000000-0000-0000-0000-000000000001',
   '20000000-0000-0000-0000-000000000002',
   5, 'Excellent delivery.');

-- Public profile payload must aggregate correctly and omit private contact fields.
do $$
declare payload jsonb;
begin
  payload := public.get_public_freelancer_profile('20000000-0000-0000-0000-000000000002');
  if (payload->>'completed_jobs')::int <> 1 then raise exception 'wrong completed_jobs'; end if;
  if (payload->>'active_jobs')::int <> 2 then raise exception 'wrong active_jobs'; end if;
  if (payload->>'completed_usd_minor')::bigint <> 125000 then raise exception 'wrong completed USD total'; end if;
  if (payload->>'average_rating')::numeric <> 5 then raise exception 'wrong average rating'; end if;
  if (payload->>'review_count')::int <> 1 then raise exception 'wrong review count'; end if;
  if payload ? 'email' or payload ? 'mobile_number' then raise exception 'private contact data leaked'; end if;
end
$$;

-- Duplicate review and wrong-client review must fail under the client identity.
do $$
begin
  begin
    insert into public.freelancer_reviews
      (contract_id, client_id, freelancer_id, rating, review_text)
    values
      ('a0000000-0000-0000-0000-000000000001',
       '10000000-0000-0000-0000-000000000001',
       '20000000-0000-0000-0000-000000000002', 4, 'Duplicate');
    raise exception 'duplicate review unexpectedly succeeded';
  exception when unique_violation then null;
  end;

  begin
    insert into public.freelancer_reviews
      (contract_id, client_id, freelancer_id, rating, review_text)
    values
      ('a0000000-0000-0000-0000-000000000003',
       '10000000-0000-0000-0000-000000000001',
       '20000000-0000-0000-0000-000000000002', 4, 'Wrong client');
    raise exception 'wrong-client review unexpectedly succeeded';
  exception when insufficient_privilege then null;
  end;
end
$$;


-- Freelancer cannot complete a client-owned contract or review their own work.
select set_config('request.jwt.claim.sub', '20000000-0000-0000-0000-000000000002', false);
do $$
begin
  begin
    perform public.complete_contract('a0000000-0000-0000-0000-000000000002');
    raise exception 'freelancer unexpectedly completed a client contract';
  exception when others then
    if sqlerrm = 'freelancer unexpectedly completed a client contract' then raise; end if;
    if sqlerrm <> 'CONTRACT_NOT_READY_OR_NOT_AUTHORIZED' then raise; end if;
  end;

  begin
    insert into public.freelancer_reviews
      (contract_id, client_id, freelancer_id, rating, review_text)
    values
      ('a0000000-0000-0000-0000-000000000001',
       '20000000-0000-0000-0000-000000000002',
       '20000000-0000-0000-0000-000000000002', 5, 'Self review');
    raise exception 'freelancer unexpectedly reviewed own work';
  exception when insufficient_privilege then null;
  end;
end
$$;

reset role;
do $$
begin
  if has_function_privilege('anon', 'public.complete_contract(uuid)', 'EXECUTE') then
    raise exception 'anon can execute complete_contract';
  end if;
  if has_function_privilege('anon', 'public.get_public_freelancer_profile(uuid)', 'EXECUTE') then
    raise exception 'anon can execute public profile RPC';
  end if;
end
$$;

select 'PASS: local PostgreSQL draft integration checks completed' as result;
