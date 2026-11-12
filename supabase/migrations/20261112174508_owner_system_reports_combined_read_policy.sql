drop policy if exists "Active invited viewers can read owner system reports" on public.owner_system_reports;
drop policy if exists "Active owners can read system reports" on public.owner_system_reports;
create policy "Owners and invited viewers can read system reports"
on public.owner_system_reports
for select
to authenticated
using (
  exists (
    select 1 from public.app_admins a
    where a.user_id = (select auth.uid())
      and a.role = 'owner'
      and a.is_active = true
  )
  or exists (
    select 1 from public.owner_device_access d
    where d.invited_user_id = (select auth.uid())
      and d.status = 'active'
      and d.access_level = 'viewer'
  )
);
