create table if not exists public.owner_device_access (
  id uuid primary key default gen_random_uuid(),
  owner_user_id uuid not null references public.app_admins(user_id) on delete cascade,
  invited_user_id uuid references auth.users(id) on delete cascade,
  invited_email text not null check (length(trim(invited_email)) between 3 and 254),
  access_level text not null default 'viewer' check (access_level in ('viewer')),
  status text not null default 'pending' check (status in ('pending','active','revoked')),
  invited_at timestamptz not null default now(),
  accepted_at timestamptz,
  revoked_at timestamptz,
  last_seen_at timestamptz,
  last_device_label text
);

create unique index if not exists owner_device_access_owner_email_uidx
  on public.owner_device_access (owner_user_id, lower(invited_email));
create index if not exists owner_device_access_user_status_idx
  on public.owner_device_access (invited_user_id, status);

alter table public.owner_device_access enable row level security;
revoke all on public.owner_device_access from anon, authenticated;
grant all on public.owner_device_access to service_role;

drop policy if exists "Active invited viewers can read owner system reports" on public.owner_system_reports;
create policy "Active invited viewers can read owner system reports"
on public.owner_system_reports
for select
to authenticated
using (
  exists (
    select 1
    from public.owner_device_access d
    where d.invited_user_id = (select auth.uid())
      and d.status = 'active'
      and d.access_level = 'viewer'
  )
);

comment on table public.owner_device_access is
  'Owner-managed invitation access for read-only Owner Dashboard viewers. Access is revoked centrally by status; invited accounts are not Owners.';
