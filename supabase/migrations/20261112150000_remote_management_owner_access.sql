-- Remote management owner access foundation.
-- The owner account is provisioned separately in public.app_admins so no
-- personal email address or auth user UUID is committed to source control.

create table if not exists public.app_admins (
  user_id uuid primary key references auth.users(id) on delete cascade,
  role text not null default 'owner' check (role in ('owner', 'admin')),
  is_active boolean not null default true,
  created_at timestamptz not null default now()
);

alter table public.app_admins enable row level security;
revoke all on public.app_admins from anon, authenticated;
grant select on public.app_admins to authenticated;

drop policy if exists "Admins can read own admin record" on public.app_admins;
create policy "Admins can read own admin record"
  on public.app_admins
  for select
  to authenticated
  using (user_id = (select auth.uid()));

-- Feature flags remain publicly readable, but only an active owner can mutate them.
grant insert, update, delete on public.app_feature_flags to authenticated;
revoke truncate, trigger, references on public.app_feature_flags from anon, authenticated;

drop policy if exists "Owners can insert app feature flags" on public.app_feature_flags;
drop policy if exists "Owners can update app feature flags" on public.app_feature_flags;
drop policy if exists "Owners can delete app feature flags" on public.app_feature_flags;

create policy "Owners can insert app feature flags"
  on public.app_feature_flags
  for insert
  to authenticated
  with check (
    exists (
      select 1
      from public.app_admins a
      where a.user_id = (select auth.uid())
        and a.is_active = true
        and a.role = 'owner'
    )
  );

create policy "Owners can update app feature flags"
  on public.app_feature_flags
  for update
  to authenticated
  using (
    exists (
      select 1
      from public.app_admins a
      where a.user_id = (select auth.uid())
        and a.is_active = true
        and a.role = 'owner'
    )
  )
  with check (
    exists (
      select 1
      from public.app_admins a
      where a.user_id = (select auth.uid())
        and a.is_active = true
        and a.role = 'owner'
    )
  );

create policy "Owners can delete app feature flags"
  on public.app_feature_flags
  for delete
  to authenticated
  using (
    exists (
      select 1
      from public.app_admins a
      where a.user_id = (select auth.uid())
        and a.is_active = true
        and a.role = 'owner'
    )
  );
