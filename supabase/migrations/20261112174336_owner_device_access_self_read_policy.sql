grant select on public.owner_device_access to authenticated;
drop policy if exists "Users can read own invited dashboard access" on public.owner_device_access;
create policy "Users can read own invited dashboard access"
on public.owner_device_access
for select
to authenticated
using (invited_user_id = (select auth.uid()));
