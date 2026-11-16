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

-- Production intentionally grants authenticated no direct SELECT on contracts.
create schema freelance_hub_private;
revoke all on schema freelance_hub_private from public, anon;
grant usage on schema freelance_hub_private to authenticated;

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
  ('a0000000-0000-0000-0000-000000000003', '30000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000002', 90000, 'EUR', 'active'),
  ('a0000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', 30000, 'USD', 'cancelled'),
  ('a0000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', 40000, 'USD', 'disputed');

insert into public.work_submissions (id, contract_id, freelancer_id, status)
values
  ('b0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', 'approved'),
  ('b0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000002', 'submitted'),
  ('b0000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000002', 'approved'),
  ('b0000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000002', 'approved');

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
  begin
    perform public.complete_contract('a0000000-0000-0000-0000-000000000004');
    raise exception 'cancelled contract unexpectedly completed';
  exception when others then
    if sqlerrm = 'cancelled contract unexpectedly completed' then raise; end if;
    if sqlerrm <> 'CONTRACT_NOT_READY_OR_NOT_AUTHORIZED' then raise; end if;
  end;
  begin
    perform public.complete_contract('a0000000-0000-0000-0000-000000000005');
    raise exception 'disputed contract unexpectedly completed';
  exception when others then
    if sqlerrm = 'disputed contract unexpectedly completed' then raise; end if;
    if sqlerrm <> 'CONTRACT_NOT_READY_OR_NOT_AUTHORIZED' then raise; end if;
  end;
end
$$;

-- A client may create one review for their completed contract.
insert into public.freelancer_reviews
  (contract_id, freelancer_id, rating, review_text)
values
  ('a0000000-0000-0000-0000-000000000001',
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

-- Direct review-table reads are denied; safe RPCs return only public review fields.
do $$
declare payload jsonb;
begin
  if has_table_privilege('authenticated', 'public.freelancer_reviews', 'SELECT') then
    raise exception 'authenticated role has direct SELECT on freelancer_reviews';
  end if;
  if has_column_privilege('authenticated', 'public.freelancer_reviews', 'created_at', 'INSERT') then
    raise exception 'authenticated role can forge freelancer review timestamps';
  end if;
  if has_column_privilege('authenticated', 'public.freelancer_reviews', 'id', 'INSERT') then
    raise exception 'authenticated role can supply freelancer review IDs';
  end if;
  if has_table_privilege('authenticated', 'public.contracts', 'SELECT') then
    raise exception 'authenticated role unexpectedly has direct SELECT on contracts';
  end if;
  payload := public.get_public_freelancer_reviews('20000000-0000-0000-0000-000000000002');
  if jsonb_array_length(payload) <> 1 then raise exception 'safe public review RPC returned wrong count'; end if;
  if payload->0 ? 'client_id' or payload->0 ? 'contract_id' or payload->0 ? 'freelancer_id' then
    raise exception 'safe public review RPC leaked internal identifiers';
  end if;
  payload := public.get_my_freelancer_review('a0000000-0000-0000-0000-000000000001');
  if payload->>'rating' <> '5' then raise exception 'client could not read own review'; end if;
  if payload ? 'client_id' or payload ? 'contract_id' then
    raise exception 'own review RPC leaked internal identifiers';
  end if;
end
$$;

-- Duplicate review and wrong-client review must fail under the client identity.
do $review_validation$
begin
  begin
    insert into public.freelancer_reviews
      (contract_id, freelancer_id, rating, review_text)
    values
      ('a0000000-0000-0000-0000-000000000001',
       '20000000-0000-0000-0000-000000000002', 4, 'Duplicate');
    raise exception 'duplicate review unexpectedly succeeded';
  exception when unique_violation then null;
  end;

  begin
    insert into public.freelancer_reviews
      (contract_id, freelancer_id, rating, review_text)
    values
      ('a0000000-0000-0000-0000-000000000003',
       '20000000-0000-0000-0000-000000000002', 4, 'Wrong client');
    raise exception 'wrong-client review unexpectedly succeeded';
  exception when insufficient_privilege then null;
  end;

  begin
    insert into public.freelancer_reviews
      (contract_id, freelancer_id, rating, review_text)
    values
      ('a0000000-0000-0000-0000-000000000001',
       '30000000-0000-0000-0000-000000000003', 4, 'Wrong freelancer');
    raise exception 'review for a mismatched freelancer unexpectedly succeeded';
  exception when insufficient_privilege then null;
  end;

  begin
    insert into public.freelancer_reviews
      (contract_id, freelancer_id, rating, review_text)
    values
      ('a0000000-0000-0000-0000-000000000001',
       '20000000-0000-0000-0000-000000000002', 0, 'Invalid low rating');
    raise exception 'rating below 1 unexpectedly succeeded';
  exception when check_violation then null;
  end;

  begin
    insert into public.freelancer_reviews
      (contract_id, freelancer_id, rating, review_text)
    values
      ('a0000000-0000-0000-0000-000000000001',
       '20000000-0000-0000-0000-000000000002', 6, 'Invalid high rating');
    raise exception 'rating above 5 unexpectedly succeeded';
  exception when check_violation then null;
  end;
end
$review_validation$;


-- An unrelated client cannot complete another client's contract or submit a review for it.
select set_config('request.jwt.claim.sub', '30000000-0000-0000-0000-000000000003', false);
do $$
begin
  begin
    perform public.complete_contract('a0000000-0000-0000-0000-000000000002');
    raise exception 'unrelated client unexpectedly completed another client contract';
  exception when others then
    if sqlerrm = 'unrelated client unexpectedly completed another client contract' then raise; end if;
    if sqlerrm <> 'CONTRACT_NOT_READY_OR_NOT_AUTHORIZED' then raise; end if;
  end;

  begin
    insert into public.freelancer_reviews
      (contract_id, freelancer_id, rating, review_text)
    values
      ('a0000000-0000-0000-0000-000000000001',
       '20000000-0000-0000-0000-000000000002', 4, 'Unrelated client');
    raise exception 'unrelated client unexpectedly reviewed another client contract';
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
      (contract_id, freelancer_id, rating, review_text)
    values
      ('a0000000-0000-0000-0000-000000000001',
       '20000000-0000-0000-0000-000000000002', 5, 'Self review');
    raise exception 'freelancer unexpectedly reviewed own work';
  exception when insufficient_privilege then null;
  end;
end
$$;

-- Even an authenticated database role must have a real Supabase user identity.
select set_config('request.jwt.claim.sub', '', false);
do $unauthenticated_rpc$
begin
  begin
    perform public.complete_contract('a0000000-0000-0000-0000-000000000001');
    raise exception 'unauthenticated complete_contract unexpectedly succeeded';
  exception when others then
    if sqlerrm = 'unauthenticated complete_contract unexpectedly succeeded' then raise; end if;
    if sqlerrm <> 'AUTHENTICATION_REQUIRED' then raise; end if;
  end;

  begin
    perform public.get_public_freelancer_profile('20000000-0000-0000-0000-000000000002');
    raise exception 'unauthenticated public profile RPC unexpectedly succeeded';
  exception when others then
    if sqlerrm = 'unauthenticated public profile RPC unexpectedly succeeded' then raise; end if;
    if sqlerrm <> 'AUTHENTICATION_REQUIRED' then raise; end if;
  end;

  begin
    perform public.get_public_freelancer_reviews('20000000-0000-0000-0000-000000000002');
    raise exception 'unauthenticated public reviews RPC unexpectedly succeeded';
  exception when others then
    if sqlerrm = 'unauthenticated public reviews RPC unexpectedly succeeded' then raise; end if;
    if sqlerrm <> 'AUTHENTICATION_REQUIRED' then raise; end if;
  end;

  begin
    perform public.get_my_freelancer_review('a0000000-0000-0000-0000-000000000001');
    raise exception 'unauthenticated own-review RPC unexpectedly succeeded';
  exception when others then
    if sqlerrm = 'unauthenticated own-review RPC unexpectedly succeeded' then raise; end if;
    if sqlerrm <> 'AUTHENTICATION_REQUIRED' then raise; end if;
  end;
end
$unauthenticated_rpc$;

reset role;
do $privilege_check$
begin
  if has_function_privilege('anon', 'public.complete_contract(uuid)', 'EXECUTE') then
    raise exception 'anon can execute complete_contract';
  end if;
  if has_function_privilege('anon', 'public.get_public_freelancer_profile(uuid)', 'EXECUTE') then
    raise exception 'anon can execute public profile RPC';
  end if;
  if has_function_privilege('anon', 'public.get_public_freelancer_reviews(uuid)', 'EXECUTE') then
    raise exception 'anon can execute public review RPC';
  end if;
  if has_function_privilege('anon', 'public.get_my_freelancer_review(uuid)', 'EXECUTE') then
    raise exception 'anon can execute own-review RPC';
  end if;
end
$privilege_check$;

-- RLS and privileged-function hardening checks.
do $security_surface$
declare
  v_signature text;
  v_is_security_definer boolean;
  v_config text[];
begin
  if not (select relrowsecurity from pg_class where oid = 'public.freelancer_reviews'::regclass) then
    raise exception 'freelancer_reviews row-level security is not enabled';
  end if;

  if not exists (
    select 1 from pg_policies
    where schemaname = 'public'
      and tablename = 'freelancer_reviews'
      and policyname = 'Completed contract clients can review freelancer'
      and cmd = 'INSERT'
  ) then
    raise exception 'completed-contract review insert policy is missing';
  end if;

  if has_column_privilege('authenticated', 'public.freelancer_reviews', 'client_id', 'INSERT') then
    raise exception 'authenticated role can forge freelancer review client_id';
  end if;
  if has_table_privilege('authenticated', 'public.freelancer_reviews', 'UPDATE') then
    raise exception 'authenticated role can update freelancer reviews directly';
  end if;
  if has_table_privilege('authenticated', 'public.freelancer_reviews', 'DELETE') then
    raise exception 'authenticated role can delete freelancer reviews directly';
  end if;
  if has_table_privilege('anon', 'public.freelancer_reviews', 'SELECT')
     or has_table_privilege('anon', 'public.freelancer_reviews', 'INSERT') then
    raise exception 'anon role has direct access to freelancer_reviews';
  end if;

  if has_function_privilege('anon', 'freelance_hub_private.can_review_completed_contract(uuid,uuid)', 'EXECUTE') then
    raise exception 'anon can execute the private review authorization helper';
  end if;
  if not has_function_privilege('authenticated', 'freelance_hub_private.can_review_completed_contract(uuid,uuid)', 'EXECUTE') then
    raise exception 'authenticated role cannot evaluate the review authorization policy';
  end if;

  foreach v_signature in array array[
    'freelance_hub_private.can_review_completed_contract(uuid,uuid)',
    'public.complete_contract(uuid)',
    'public.get_public_freelancer_profile(uuid)',
    'public.get_public_freelancer_reviews(uuid)',
    'public.get_my_freelancer_review(uuid)'
  ]
  loop
    select p.prosecdef, p.proconfig
      into v_is_security_definer, v_config
      from pg_proc p
     where p.oid = to_regprocedure(v_signature);

    if not coalesce(v_is_security_definer, false) then
      raise exception 'expected SECURITY DEFINER function: %', v_signature;
    end if;
    if not exists (
      select 1
        from unnest(coalesce(v_config, array[]::text[])) setting
       where setting like 'search_path=%'
    ) then
      raise exception 'function has no fixed search_path: %', v_signature;
    end if;
  end loop;
end
$security_surface$;

select 'PASS: local PostgreSQL draft integration checks completed' as result;
