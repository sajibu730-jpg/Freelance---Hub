-- Executed only against a disposable PostgreSQL service in GitHub Actions.
-- This validates SQL syntax, core constraints, RLS flags, and table grants.
-- It is not a substitute for a real Supabase staging project or Edge Function tests.

insert into auth.users (id)
values ('11111111-1111-4111-8111-111111111111')
on conflict (id) do nothing;

do $$
declare
  t text;
begin
  foreach t in array array[
    'owner_workforce_members',
    'owner_workforce_tasks',
    'owner_workforce_member_events',
    'owner_workforce_task_events',
    'owner_release_proposals',
    'owner_release_events'
  ] loop
    if not exists (
      select 1 from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
      where n.nspname = 'public' and c.relname = t and c.relrowsecurity
    ) then
      raise exception 'RLS is not enabled on %', t;
    end if;
    if has_table_privilege('anon', 'public.' || t, 'SELECT')
      or has_table_privilege('authenticated', 'public.' || t, 'SELECT') then
      raise exception 'Client role unexpectedly has SELECT on %', t;
    end if;
  end loop;

  if not has_table_privilege('service_role', 'public.owner_workforce_members', 'SELECT')
    or not has_table_privilege('service_role', 'public.owner_workforce_tasks', 'SELECT')
    or has_table_privilege('service_role', 'public.owner_workforce_tasks', 'INSERT')
    or has_table_privilege('service_role', 'public.owner_workforce_tasks', 'UPDATE')
    or has_table_privilege('service_role', 'public.owner_workforce_tasks', 'DELETE') then
    raise exception 'service_role must have read-only access to workforce tables';
  end if;

  if has_table_privilege('service_role', 'public.owner_workforce_task_events', 'SELECT')
    or has_table_privilege('service_role', 'public.owner_workforce_task_events', 'INSERT')
    or has_table_privilege('service_role', 'public.owner_workforce_task_events', 'UPDATE')
    or has_table_privilege('service_role', 'public.owner_workforce_task_events', 'DELETE') then
    raise exception 'Task audit history must not be directly accessible to service_role';
  end if;

  if has_table_privilege('service_role', 'public.owner_workforce_member_events', 'SELECT')
    or has_table_privilege('service_role', 'public.owner_workforce_member_events', 'INSERT')
    or has_table_privilege('service_role', 'public.owner_workforce_member_events', 'UPDATE')
    or has_table_privilege('service_role', 'public.owner_workforce_member_events', 'DELETE') then
    raise exception 'Member audit history must not be directly accessible to service_role';
  end if;
end $$;

insert into public.owner_workforce_members (user_id, role, invited_by)
values (
  '11111111-1111-4111-8111-111111111111',
  'developer',
  '11111111-1111-4111-8111-111111111111'
);

do $$
begin
  begin
    insert into public.owner_workforce_tasks (project_key, title, status, created_by)
    values ('freelance-hub', 'Invalid completed task', 'done', '11111111-1111-4111-8111-111111111111');
    raise exception 'A completed task without evidence was accepted';
  exception when check_violation then
    null;
  end;
end $$;

do $$
begin
  begin
    insert into public.owner_workforce_tasks (project_key, title, task_kind, created_by)
    values ('freelance-hub', 'Invalid task kind', 'owner_override', '11111111-1111-4111-8111-111111111111');
    raise exception 'An invalid task kind was accepted';
  exception when check_violation then
    null;
  end;
end $$;

insert into public.owner_workforce_tasks (project_key, title, created_by)
values ('freelance-hub', 'Valid draft task', '11111111-1111-4111-8111-111111111111');

do $$
declare
  request_key uuid := '22222222-2222-4222-8222-222222222222';
begin
  insert into public.owner_workforce_member_events
    (request_id, member_user_id, actor_user_id, event_type)
  values
    (request_key, '11111111-1111-4111-8111-111111111111',
     '11111111-1111-4111-8111-111111111111', 'added');

  begin
    insert into public.owner_workforce_member_events
      (request_id, member_user_id, actor_user_id, event_type)
    values
      (request_key, '11111111-1111-4111-8111-111111111111',
       '11111111-1111-4111-8111-111111111111', 'added');
    raise exception 'Duplicate request_id was accepted';
  exception when unique_violation then
    null;
  end;
end $$;

select 'Workforce schema draft PostgreSQL checks passed.' as result;
