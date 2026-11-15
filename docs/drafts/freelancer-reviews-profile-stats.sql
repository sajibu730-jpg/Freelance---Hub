-- DRAFT ONLY — do not apply to Production until the Owner approves the release.
-- Adds client-to-freelancer ratings and a guarded contract-completion RPC.
-- Existing public.work_reviews is reserved for AI/Human work-check requests.

begin;

create table if not exists public.freelancer_reviews (
    id uuid primary key default gen_random_uuid(),
    contract_id uuid not null references public.contracts(id) on delete restrict,
    client_id uuid not null default auth.uid() references public.profiles(id) on delete restrict,
    freelancer_id uuid not null references public.profiles(id) on delete restrict,
    rating smallint not null check (rating between 1 and 5),
    review_text text not null default '' check (char_length(review_text) <= 2000),
    created_at timestamptz not null default now(),
    constraint freelancer_reviews_one_per_contract unique (contract_id)
);

create index if not exists freelancer_reviews_freelancer_created_idx
    on public.freelancer_reviews (freelancer_id, created_at desc);

alter table public.freelancer_reviews enable row level security;

revoke all on table public.freelancer_reviews from anon, authenticated;
grant select, insert on table public.freelancer_reviews to authenticated;

drop policy if exists "Authenticated users can read freelancer reviews" on public.freelancer_reviews;
create policy "Authenticated users can read freelancer reviews"
    on public.freelancer_reviews
    for select
    to authenticated
    using (true);

drop policy if exists "Completed contract clients can review freelancer" on public.freelancer_reviews;
create policy "Completed contract clients can review freelancer"
    on public.freelancer_reviews
    for insert
    to authenticated
    with check (
        client_id = (select auth.uid())
        and exists (
            select 1
            from public.contracts c
            where c.id = freelancer_reviews.contract_id
              and c.client_id = (select auth.uid())
              and c.freelancer_id = freelancer_reviews.freelancer_id
              and c.status = 'completed'
        )
    );

-- A contract can be completed only by its client and only after at least one
-- work submission for that contract has been explicitly approved.
create or replace function public.complete_contract(p_contract_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $function$
declare
    result jsonb;
begin
    if (select auth.uid()) is null then
        raise exception 'AUTHENTICATION_REQUIRED';
    end if;

    update public.contracts c
       set status = 'completed',
           completed_at = coalesce(c.completed_at, now()),
           updated_at = now()
     where c.id = p_contract_id
       and c.client_id = (select auth.uid())
       and c.status = 'active'
       and exists (
           select 1
           from public.work_submissions ws
           where ws.contract_id = c.id
             and ws.status = 'approved'
       );

    if not found then
        -- Idempotent retry: return the already-completed contract to its client.
        select jsonb_build_object(
            'id', c.id,
            'status', c.status,
            'completed_at', c.completed_at
        )
          into result
          from public.contracts c
         where c.id = p_contract_id
           and c.client_id = (select auth.uid())
           and c.status = 'completed';

        if result is null then
            raise exception 'CONTRACT_NOT_READY_OR_NOT_AUTHORIZED';
        end if;

        return result;
    end if;

    select jsonb_build_object(
        'id', c.id,
        'status', c.status,
        'completed_at', c.completed_at
    )
      into result
      from public.contracts c
     where c.id = p_contract_id
       and c.client_id = (select auth.uid());

    return result;
end;
$function$;

revoke all on function public.complete_contract(uuid) from public, anon;
grant execute on function public.complete_contract(uuid) to authenticated;

comment on table public.freelancer_reviews is
    'One client rating per completed contract. Public to authenticated users; writes are restricted by RLS to the contract client.';
comment on function public.complete_contract(uuid) is
    'Client-only, idempotent completion after an approved work submission. Draft release artifact; not yet deployed.';

commit;
