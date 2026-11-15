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
    constraint freelancer_reviews_distinct_participants check (client_id <> freelancer_id),
    constraint freelancer_reviews_one_per_contract unique (contract_id)
);

create index if not exists freelancer_reviews_freelancer_created_idx
    on public.freelancer_reviews (freelancer_id, created_at desc);

alter table public.freelancer_reviews enable row level security;

revoke all on table public.freelancer_reviews from anon, authenticated;
grant insert on table public.freelancer_reviews to authenticated;

drop policy if exists "Authenticated users can read freelancer reviews" on public.freelancer_reviews;

-- The policy must not query contracts directly: authenticated has no table
-- SELECT grant in Production. A narrow helper performs only this authorization
-- check from a non-exposed schema; it returns no contract or client data.
create schema if not exists freelance_hub_private;
revoke all on schema freelance_hub_private from public, anon;
grant usage on schema freelance_hub_private to authenticated;

create or replace function freelance_hub_private.can_review_completed_contract(
    p_contract_id uuid,
    p_freelancer_id uuid
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $function$
    select (select auth.uid()) is not null
       and exists (
            select 1
            from public.contracts c
            where c.id = p_contract_id
              and c.client_id = (select auth.uid())
              and c.freelancer_id = p_freelancer_id
              and c.status = 'completed'
       );
$function$;

revoke all on function freelance_hub_private.can_review_completed_contract(uuid, uuid) from public, anon;
grant execute on function freelance_hub_private.can_review_completed_contract(uuid, uuid) to authenticated;

drop policy if exists "Completed contract clients can review freelancer" on public.freelancer_reviews;
create policy "Completed contract clients can review freelancer"
    on public.freelancer_reviews
    for insert
    to authenticated
    with check (
        client_id = (select auth.uid())
        and freelance_hub_private.can_review_completed_contract(contract_id, freelancer_id)
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
             and ws.freelancer_id = c.freelancer_id
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


-- Public, authenticated-only freelancer profile payload. Never expose email or mobile.
create or replace function public.get_public_freelancer_profile(p_freelancer_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
    result jsonb;
begin
    if (select auth.uid()) is null then
        raise exception 'AUTHENTICATION_REQUIRED';
    end if;

    with contract_stats as (
        select
            count(*) filter (where c.status = 'completed')::integer as completed_jobs,
            count(*) filter (where c.status = 'active')::integer as active_jobs,
            coalesce(
                sum(c.agreed_budget_minor) filter (
                    where c.status = 'completed' and upper(c.currency) = 'USD'
                ),
                0
            )::bigint as completed_usd_minor
        from public.contracts c
        where c.freelancer_id = p_freelancer_id
    ),
    all_reviews as (
        select r.id, r.rating, r.review_text, r.created_at
        from public.freelancer_reviews r
        where r.freelancer_id = p_freelancer_id
    ),
    recent_reviews as (
        select r.id, r.rating, r.review_text, r.created_at
        from all_reviews r
        order by r.created_at desc
        limit 20
    ),
    review_stats as (
        select
            coalesce(avg(r.rating), 0)::numeric(4,2) as average_rating,
            count(r.id)::integer as review_count
        from all_reviews r
    ),
    review_list as (
        select coalesce(
            jsonb_agg(
                jsonb_build_object(
                    'id', r.id,
                    'rating', r.rating,
                    'review_text', r.review_text,
                    'created_at', r.created_at
                )
                order by r.created_at desc
            ),
            '[]'::jsonb
        ) as reviews
        from recent_reviews r
    )
    select jsonb_build_object(
        'id', p.id,
        'display_name', p.display_name,
        'photo_url', p.photo_url,
        'cover_photo_url', p.cover_photo_url,
        'country', p.country,
        'bio', p.bio,
        'capabilities', p.capabilities,
        'skills', p.skills,
        'completed_jobs', cs.completed_jobs,
        'active_jobs', cs.active_jobs,
        'completed_usd_minor', cs.completed_usd_minor,
        'average_rating', rs.average_rating,
        'review_count', rs.review_count,
        'reviews', rl.reviews
    )
    into result
    from public.profiles p
    cross join contract_stats cs
    cross join review_stats rs
    cross join review_list rl
    where p.id = p_freelancer_id
      and p.display_name is not null;

    if result is null then
        raise exception 'PUBLIC_FREELANCER_PROFILE_NOT_FOUND';
    end if;

    return result;
end;
$function$;

revoke all on function public.get_public_freelancer_profile(uuid) from public, anon;
grant execute on function public.get_public_freelancer_profile(uuid) to authenticated;

comment on function public.get_public_freelancer_profile(uuid) is
    'Returns only approved public profile fields, completed/active contract statistics, and recent client reviews. Never returns email or mobile.';


-- Safe read endpoints: never expose contract_id or client_id from the review table.
create or replace function public.get_public_freelancer_reviews(p_freelancer_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
    result jsonb;
begin
    if (select auth.uid()) is null then
        raise exception 'AUTHENTICATION_REQUIRED';
    end if;

    select coalesce(
        jsonb_agg(
            jsonb_build_object(
                'id', r.id,
                'rating', r.rating,
                'review_text', r.review_text,
                'created_at', r.created_at
            )
            order by r.created_at desc
        ),
        '[]'::jsonb
    )
    into result
    from (
        select id, rating, review_text, created_at
        from public.freelancer_reviews
        where freelancer_id = p_freelancer_id
        order by created_at desc
        limit 50
    ) r;

    return result;
end;
$function$;

create or replace function public.get_my_freelancer_review(p_contract_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
    result jsonb;
begin
    if (select auth.uid()) is null then
        raise exception 'AUTHENTICATION_REQUIRED';
    end if;

    select jsonb_build_object(
        'id', r.id,
        'rating', r.rating,
        'review_text', r.review_text,
        'created_at', r.created_at
    )
    into result
    from public.freelancer_reviews r
    where r.contract_id = p_contract_id
      and r.client_id = (select auth.uid());

    return coalesce(result, '{}'::jsonb);
end;
$function$;

revoke all on function public.get_public_freelancer_reviews(uuid) from public, anon;
grant execute on function public.get_public_freelancer_reviews(uuid) to authenticated;
revoke all on function public.get_my_freelancer_review(uuid) from public, anon;
grant execute on function public.get_my_freelancer_review(uuid) to authenticated;

comment on function public.get_public_freelancer_reviews(uuid) is
    'Returns only review id, rating, text, and date to authenticated users; never returns client or contract identifiers.';
comment on function public.get_my_freelancer_review(uuid) is
    'Returns the calling client own review for one contract without exposing other client or contract identifiers.';

commit;
