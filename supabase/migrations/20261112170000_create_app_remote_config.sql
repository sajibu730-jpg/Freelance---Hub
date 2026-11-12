-- Public remote configuration values for the Freelance Hub Android client.
-- Only explicitly public rows are readable by the app; only active owners can manage rows.

create table if not exists public.app_remote_config (
  key text primary key check (key ~ '^[a-z][a-z0-9_.-]{1,79}$'),
  value jsonb not null,
  is_public boolean not null default true,
  description text,
  updated_at timestamptz not null default now()
);

alter table public.app_remote_config enable row level security;
revoke all on public.app_remote_config from anon, authenticated;
grant select on public.app_remote_config to anon, authenticated;
grant insert, update, delete on public.app_remote_config to authenticated;
revoke truncate, trigger, references on public.app_remote_config from anon, authenticated;

drop policy if exists "Public remote config is readable" on public.app_remote_config;
create policy "Public remote config is readable"
  on public.app_remote_config for select to anon, authenticated
  using (is_public = true);

drop policy if exists "Owners can read all remote config" on public.app_remote_config;
create policy "Owners can read all remote config"
  on public.app_remote_config for select to authenticated
  using (exists (
    select 1 from public.app_admins a
    where a.user_id = (select auth.uid())
      and a.is_active = true and a.role = 'owner'
  ));

drop policy if exists "Owners can insert remote config" on public.app_remote_config;
create policy "Owners can insert remote config"
  on public.app_remote_config for insert to authenticated
  with check (exists (
    select 1 from public.app_admins a
    where a.user_id = (select auth.uid())
      and a.is_active = true and a.role = 'owner'
  ));

drop policy if exists "Owners can update remote config" on public.app_remote_config;
create policy "Owners can update remote config"
  on public.app_remote_config for update to authenticated
  using (exists (
    select 1 from public.app_admins a
    where a.user_id = (select auth.uid())
      and a.is_active = true and a.role = 'owner'
  ))
  with check (exists (
    select 1 from public.app_admins a
    where a.user_id = (select auth.uid())
      and a.is_active = true and a.role = 'owner'
  ));

drop policy if exists "Owners can delete remote config" on public.app_remote_config;
create policy "Owners can delete remote config"
  on public.app_remote_config for delete to authenticated
  using (exists (
    select 1 from public.app_admins a
    where a.user_id = (select auth.uid())
      and a.is_active = true and a.role = 'owner'
  ));

create index if not exists app_remote_config_public_key_idx
  on public.app_remote_config (key) where is_public = true;

insert into public.app_remote_config (key, value, is_public, description)
values
  ('app.maintenance.enabled', 'false'::jsonb, true, 'Temporarily show the maintenance screen in the Android app'),
  ('app.maintenance.message', '"Freelance Hub is temporarily under maintenance. Please try again soon."'::jsonb, true, 'Message shown while maintenance mode is enabled')
on conflict (key) do nothing;
