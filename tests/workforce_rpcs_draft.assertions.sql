-- Executed only against disposable PostgreSQL in GitHub Actions.
-- Assumes owner-workforce-schema.draft.sql and owner-workforce-rpcs.draft.sql were applied.
-- This does not replace Supabase staging, Edge Function auth tests, or production approval.

insert into auth.users (id) values
  ('22222222-2222-4222-8222-222222222222'),
  ('33333333-3333-4333-8333-333333333333'),
  ('44444444-4444-4444-8444-444444444444')
on conflict (id) do nothing;

insert into public.owner_workforce_members (user_id, role, status, project_scopes, invited_by)
values
  ('22222222-2222-4222-8222-222222222222', 'project_manager', 'active', array['freelance-hub'], '11111111-1111-4111-8111-111111111111'),
  ('33333333-3333-4333-8333-333333333333', 'qa', 'active', array['freelance-hub'], '11111111-1111-4111-8111-111111111111'),
  ('44444444-4444-4444-8444-444444444444', 'support', 'active', array['freelance-hub'], '11111111-1111-4111-8111-111111111111');

-- RPC execution is limited to the trusted server role; client roles cannot execute.
do $$
declare
  fn regprocedure;
begin
  foreach fn in array array[
    'public.owner_workforce_create_task(uuid,text,text,text,text,text,timestamp with time zone,uuid)'::regprocedure,
    'public.owner_workforce_assign_task(uuid,uuid,uuid,uuid)'::regprocedure,
    'public.owner_workforce_update_task_status(uuid,uuid,text,text,text,uuid)'::regprocedure,
    'public.owner_workforce_append_task_evidence(uuid,uuid,text,uuid)'::regprocedure
  ] loop
    if has_function_privilege('anon', fn, 'EXECUTE')
       or has_function_privilege('authenticated', fn, 'EXECUTE')
       or not has_function_privilege('service_role', fn, 'EXECUTE') then
      raise exception 'Incorrect RPC EXECUTE grants: %', fn;
    end if;
  end loop;
end $$;

set role service_role;

do $$
declare
  v_task jsonb;
  v_task_id uuid;
  v_support_task jsonb;
  v_support_task_id uuid;
  v_event_count integer;
begin
  -- Project Manager creates a general task. A retry with the same request ID is idempotent.
  v_task := public.owner_workforce_create_task(
    '22222222-2222-4222-8222-222222222222',
    'freelance-hub', 'Build workforce RPC tests', 'Disposable database test', 'general', 'high',
    null, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1'
  );
  v_task_id := (v_task ->> 'id')::uuid;
  if v_task ->> 'status' <> 'backlog' then
    raise exception 'New task should start in backlog.';
  end if;

  if (public.owner_workforce_create_task(
    '22222222-2222-4222-8222-222222222222',
    'freelance-hub', 'Build workforce RPC tests', 'Disposable database test', 'general', 'high',
    null, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1'
  ) ->> 'id')::uuid <> v_task_id then
    raise exception 'Create-task retry did not return the original task.';
  end if;

  -- Project Manager assigns to an active, scoped Developer.
  v_task := public.owner_workforce_assign_task(
    '22222222-2222-4222-8222-222222222222',
    v_task_id, '11111111-1111-4111-8111-111111111111',
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2'
  );
  if v_task ->> 'status' <> 'assigned' then
    raise exception 'Assignment should move backlog to assigned.';
  end if;

  -- Only the assignee may advance the task; evidence is append-only.
  perform public.owner_workforce_update_task_status(
    '11111111-1111-4111-8111-111111111111',
    v_task_id, 'in_progress', null, null, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3'
  );
  perform public.owner_workforce_append_task_evidence(
    '11111111-1111-4111-8111-111111111111',
    v_task_id, 'Implementation commit: 0123456789abcdef0123456789abcdef01234567',
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa4'
  );
  perform public.owner_workforce_update_task_status(
    '11111111-1111-4111-8111-111111111111',
    v_task_id, 'in_review', null, null, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa5'
  );
  v_task := public.owner_workforce_update_task_status(
    '11111111-1111-4111-8111-111111111111',
    v_task_id, 'done', null, 'CI and review evidence recorded',
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa6'
  );
  if v_task ->> 'status' <> 'done' or v_task ->> 'completion_evidence' <> 'CI and review evidence recorded' then
    raise exception 'Completion did not retain required evidence.';
  end if;

  -- A completed status retry must not create another event.
  perform public.owner_workforce_update_task_status(
    '11111111-1111-4111-8111-111111111111',
    v_task_id, 'done', null, 'CI and review evidence recorded',
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa6'
  );

  select count(*) into v_event_count
  from public.owner_workforce_task_events e
  where e.task_id = v_task_id;
  if v_event_count <> 6 then
    raise exception 'Expected exactly 6 task audit events, found %', v_event_count;
  end if;

  -- QA cannot mutate a task assigned to a different user.
  begin
    perform public.owner_workforce_update_task_status(
      '33333333-3333-4333-8333-333333333333',
      v_task_id, 'in_progress', null, null, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa7'
    );
    raise exception 'QA changed a task assigned to another member.';
  exception when insufficient_privilege then
    null;
  end;

  -- A Developer cannot assign tasks.
  begin
    perform public.owner_workforce_assign_task(
      '11111111-1111-4111-8111-111111111111',
      v_task_id, '33333333-3333-4333-8333-333333333333',
      'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa8'
    );
    raise exception 'Developer was able to assign a task.';
  exception when insufficient_privilege then
    null;
  end;

  -- Support can create support/incident work but not general development work.
  v_support_task := public.owner_workforce_create_task(
    '44444444-4444-4444-8444-444444444444',
    'freelance-hub', 'Investigate support incident', 'Test support queue', 'incident', 'normal',
    null, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa9'
  );
  v_support_task_id := (v_support_task ->> 'id')::uuid;
  begin
    perform public.owner_workforce_create_task(
      '44444444-4444-4444-8444-444444444444',
      'freelance-hub', 'Forbidden general task', '', 'general', 'normal',
      null, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa0'
    );
    raise exception 'Support created a general task.';
  exception when insufficient_privilege then
    null;
  end;

  -- Project Manager can assign support work only to Support role.
  v_support_task := public.owner_workforce_assign_task(
    '22222222-2222-4222-8222-222222222222',
    v_support_task_id, '44444444-4444-4444-8444-444444444444',
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1'
  );
  if v_support_task ->> 'assignee_id' <> '44444444-4444-4444-8444-444444444444' then
    raise exception 'Support task assignment failed.';
  end if;

  -- Idempotency keys cannot be replayed by another actor.
  begin
    perform public.owner_workforce_create_task(
      '11111111-1111-4111-8111-111111111111',
      'freelance-hub', 'Spoofed retry', '', 'general', 'normal',
      null, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1'
    );
    raise exception 'A different actor reused another actor idempotency key.';
  exception when unique_violation then
    null;
  end;
end $$;

reset role;

select 'Atomic workforce RPC disposable PostgreSQL tests passed.' as result;
