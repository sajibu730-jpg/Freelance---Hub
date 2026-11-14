-- DRAFT ONLY — NOT A SUPABASE MIGRATION.
-- Do not execute against Production. This file is applied only to disposable PostgreSQL
-- in CI. Requires real Supabase staging, Edge Function integration, Advisors, and Owner approval.
--
-- Trust boundary:
--   * The Edge Function MUST validate the bearer token with Supabase Auth getUser(token).
--   * actor_user_id MUST come from that verified Auth user, never request JSON.
--   * The Edge Function MUST enforce Owner AAL2 before any future Owner-only RPC.
--   * Only the server-side service_role may execute these RPCs; never expose that key.
--   * Each call performs its data mutation and audit insert in one PostgreSQL transaction.
--
-- SECURITY DEFINER is used because the workforce tables intentionally have no client
-- grants/policies. Every function pins search_path to '' and schema-qualifies all objects.
-- EXECUTE is explicitly revoked from PUBLIC/anon/authenticated and granted to service_role.

create or replace function public.owner_workforce_create_task(
  p_actor_user_id uuid,
  p_project_key text,
  p_title text,
  p_description text,
  p_task_kind text,
  p_priority text,
  p_due_at timestamptz,
  p_request_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_role text;
  v_status text;
  v_scopes text[];
  v_task public.owner_workforce_tasks%rowtype;
  v_existing public.owner_workforce_task_events%rowtype;
begin
  if p_actor_user_id is null or p_request_id is null
     or p_project_key is null or p_project_key <> btrim(p_project_key)
     or length(p_project_key) not between 1 and 100
     or p_title is null or p_title <> btrim(p_title)
     or length(p_title) not between 1 and 180
     or p_description is null or length(p_description) > 8000
     or p_task_kind is null or p_task_kind not in ('general', 'support', 'incident')
     or p_priority is null or p_priority not in ('low', 'normal', 'high', 'urgent') then
    raise exception using errcode = '22023', message = 'Invalid task input.';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(p_request_id::text));

  select e.* into v_existing
  from public.owner_workforce_task_events e
  where e.request_id = p_request_id;

  if found then
    if v_existing.actor_user_id <> p_actor_user_id
       or v_existing.event_type <> 'created'
       or v_existing.details ->> 'operation' is distinct from 'create_task' then
      raise exception using errcode = '23505', message = 'Idempotency key was already used.';
    end if;
    select t.* into v_task from public.owner_workforce_tasks t where t.id = v_existing.task_id;
    if not found then
      raise exception using errcode = 'P0002', message = 'Previously created task no longer exists.';
    end if;
    return pg_catalog.to_jsonb(v_task);
  end if;

  select m.role, m.status, m.project_scopes
    into v_role, v_status, v_scopes
  from public.owner_workforce_members m
  where m.user_id = p_actor_user_id;

  if not found or v_status <> 'active'
     or not (p_project_key = any(coalesce(v_scopes, array[]::text[]))) then
    raise exception using errcode = '42501', message = 'Active workforce membership and project scope are required.';
  end if;

  if (v_role = 'project_manager' and p_task_kind <> 'general')
     or (v_role = 'support' and p_task_kind not in ('support', 'incident'))
     or v_role not in ('project_manager', 'support') then
    raise exception using errcode = '42501', message = 'This role cannot create the requested task kind.';
  end if;

  insert into public.owner_workforce_tasks
    (project_key, title, description, task_kind, priority, due_at, created_by)
  values
    (p_project_key, p_title, p_description, p_task_kind, p_priority, p_due_at, p_actor_user_id)
  returning * into v_task;

  insert into public.owner_workforce_task_events
    (request_id, task_id, project_key, actor_user_id, event_type, details)
  values
    (p_request_id, v_task.id, p_project_key, p_actor_user_id, 'created',
     pg_catalog.jsonb_build_object('operation', 'create_task', 'task_kind', p_task_kind));

  return pg_catalog.to_jsonb(v_task);
end;
$function$;

create or replace function public.owner_workforce_assign_task(
  p_actor_user_id uuid,
  p_task_id uuid,
  p_assignee_id uuid,
  p_request_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_role text;
  v_status text;
  v_scopes text[];
  v_assignee_role text;
  v_assignee_status text;
  v_assignee_scopes text[];
  v_task public.owner_workforce_tasks%rowtype;
  v_existing public.owner_workforce_task_events%rowtype;
  v_event_type text;
begin
  if p_actor_user_id is null or p_task_id is null or p_assignee_id is null or p_request_id is null then
    raise exception using errcode = '22023', message = 'Invalid assignment input.';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(p_request_id::text));

  select e.* into v_existing
  from public.owner_workforce_task_events e
  where e.request_id = p_request_id;

  if found then
    if v_existing.actor_user_id <> p_actor_user_id
       or v_existing.task_id <> p_task_id
       or v_existing.event_type not in ('assigned', 'reassigned')
       or v_existing.details ->> 'operation' is distinct from 'assign_task'
       or (v_existing.details ->> 'assignee_id')::uuid is distinct from p_assignee_id then
      raise exception using errcode = '23505', message = 'Idempotency key was already used.';
    end if;
    select t.* into v_task from public.owner_workforce_tasks t where t.id = p_task_id;
    if not found then
      raise exception using errcode = 'P0002', message = 'Previously assigned task no longer exists.';
    end if;
    return pg_catalog.to_jsonb(v_task);
  end if;

  select t.* into v_task
  from public.owner_workforce_tasks t
  where t.id = p_task_id
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'Task not found.';
  end if;

  select m.role, m.status, m.project_scopes
    into v_role, v_status, v_scopes
  from public.owner_workforce_members m
  where m.user_id = p_actor_user_id;

  if not found or v_role <> 'project_manager' or v_status <> 'active'
     or v_task.status = 'done'
     or not (v_task.project_key = any(coalesce(v_scopes, array[]::text[]))) then
    raise exception using errcode = '42501', message = 'An active scoped Project Manager is required.';
  end if;

  select m.role, m.status, m.project_scopes
    into v_assignee_role, v_assignee_status, v_assignee_scopes
  from public.owner_workforce_members m
  where m.user_id = p_assignee_id;

  if not found or v_assignee_status <> 'active'
     or not (v_task.project_key = any(coalesce(v_assignee_scopes, array[]::text[]))) then
    raise exception using errcode = '42501', message = 'Assignee must be active and scoped to this project.';
  end if;

  if (v_task.task_kind = 'general' and v_assignee_role not in ('developer', 'qa'))
     or (v_task.task_kind in ('support', 'incident') and v_assignee_role <> 'support') then
    raise exception using errcode = '42501', message = 'Assignee role does not match the task kind.';
  end if;

  v_event_type := case when v_task.assignee_id is null then 'assigned' else 'reassigned' end;

  update public.owner_workforce_tasks
  set assignee_id = p_assignee_id,
      status = case when status = 'backlog' then 'assigned' else status end,
      updated_at = pg_catalog.now()
  where id = p_task_id
  returning * into v_task;

  insert into public.owner_workforce_task_events
    (request_id, task_id, project_key, actor_user_id, event_type, details)
  values
    (p_request_id, p_task_id, v_task.project_key, p_actor_user_id, v_event_type,
     pg_catalog.jsonb_build_object('operation', 'assign_task', 'assignee_id', p_assignee_id));

  return pg_catalog.to_jsonb(v_task);
end;
$function$;

create or replace function public.owner_workforce_update_task_status(
  p_actor_user_id uuid,
  p_task_id uuid,
  p_new_status text,
  p_blocked_reason text,
  p_completion_evidence text,
  p_request_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_role text;
  v_member_status text;
  v_scopes text[];
  v_task public.owner_workforce_tasks%rowtype;
  v_existing public.owner_workforce_task_events%rowtype;
  v_previous_status text;
begin
  if p_actor_user_id is null or p_task_id is null or p_request_id is null
     or p_new_status is null or p_new_status not in ('in_progress', 'blocked', 'in_review', 'done')
     or (p_blocked_reason is not null and length(p_blocked_reason) > 1000)
     or (p_completion_evidence is not null and length(p_completion_evidence) > 4000) then
    raise exception using errcode = '22023', message = 'Invalid task status input.';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(p_request_id::text));

  select e.* into v_existing
  from public.owner_workforce_task_events e
  where e.request_id = p_request_id;

  if found then
    if v_existing.actor_user_id <> p_actor_user_id
       or v_existing.task_id <> p_task_id
       or v_existing.event_type <> 'status_changed'
       or v_existing.details ->> 'operation' is distinct from 'update_task_status'
       or v_existing.details ->> 'new_status' is distinct from p_new_status then
      raise exception using errcode = '23505', message = 'Idempotency key was already used.';
    end if;
    select t.* into v_task from public.owner_workforce_tasks t where t.id = p_task_id;
    if not found then
      raise exception using errcode = 'P0002', message = 'Previously updated task no longer exists.';
    end if;
    return pg_catalog.to_jsonb(v_task);
  end if;

  select t.* into v_task
  from public.owner_workforce_tasks t
  where t.id = p_task_id
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'Task not found.';
  end if;

  select m.role, m.status, m.project_scopes
    into v_role, v_member_status, v_scopes
  from public.owner_workforce_members m
  where m.user_id = p_actor_user_id;

  if not found or v_role not in ('developer', 'qa') or v_member_status <> 'active'
     or v_task.assignee_id is distinct from p_actor_user_id
     or not (v_task.project_key = any(coalesce(v_scopes, array[]::text[]))) then
    raise exception using errcode = '42501', message = 'Only the active assigned Developer or QA member may update this task.';
  end if;

  if not (
    (v_task.status = 'assigned' and p_new_status in ('in_progress', 'blocked'))
    or (v_task.status = 'in_progress' and p_new_status in ('blocked', 'in_review'))
    or (v_task.status = 'blocked' and p_new_status = 'in_progress')
    or (v_task.status = 'in_review' and p_new_status in ('in_progress', 'done'))
  ) then
    raise exception using errcode = '22023', message = 'Task status transition is not permitted.';
  end if;

  if p_new_status = 'blocked' and (p_blocked_reason is null or length(btrim(p_blocked_reason)) = 0) then
    raise exception using errcode = '22023', message = 'A blocked reason is required.';
  end if;

  if p_new_status = 'done' and (p_completion_evidence is null or length(btrim(p_completion_evidence)) = 0) then
    raise exception using errcode = '22023', message = 'Completion evidence is required.';
  end if;

  v_previous_status := v_task.status;

  update public.owner_workforce_tasks
  set status = p_new_status,
      blocked_reason = case when p_new_status = 'blocked' then p_blocked_reason else null end,
      completion_evidence = case when p_new_status = 'done' then p_completion_evidence else completion_evidence end,
      completed_at = case when p_new_status = 'done' then pg_catalog.now() else null end,
      updated_at = pg_catalog.now()
  where id = p_task_id
  returning * into v_task;

  insert into public.owner_workforce_task_events
    (request_id, task_id, project_key, actor_user_id, event_type, details)
  values
    (p_request_id, p_task_id, v_task.project_key, p_actor_user_id, 'status_changed',
     pg_catalog.jsonb_build_object(
       'operation', 'update_task_status',
       'previous_status', v_previous_status,
       'new_status', p_new_status,
       'has_completion_evidence', (p_new_status = 'done')
     ));

  return pg_catalog.to_jsonb(v_task);
end;
$function$;

create or replace function public.owner_workforce_append_task_evidence(
  p_actor_user_id uuid,
  p_task_id uuid,
  p_evidence text,
  p_request_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_role text;
  v_member_status text;
  v_scopes text[];
  v_task public.owner_workforce_tasks%rowtype;
  v_existing public.owner_workforce_task_events%rowtype;
begin
  if p_actor_user_id is null or p_task_id is null or p_request_id is null
     or p_evidence is null or length(btrim(p_evidence)) = 0 or length(p_evidence) > 4000 then
    raise exception using errcode = '22023', message = 'Evidence must contain 1 to 4000 characters.';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(p_request_id::text));

  select e.* into v_existing
  from public.owner_workforce_task_events e
  where e.request_id = p_request_id;

  if found then
    if v_existing.actor_user_id <> p_actor_user_id
       or v_existing.task_id <> p_task_id
       or v_existing.event_type <> 'evidence_added'
       or v_existing.details ->> 'operation' is distinct from 'append_task_evidence' then
      raise exception using errcode = '23505', message = 'Idempotency key was already used.';
    end if;
    select t.* into v_task from public.owner_workforce_tasks t where t.id = p_task_id;
    if not found then
      raise exception using errcode = 'P0002', message = 'Previously updated task no longer exists.';
    end if;
    return pg_catalog.to_jsonb(v_task);
  end if;

  select t.* into v_task
  from public.owner_workforce_tasks t
  where t.id = p_task_id
  for update;

  if not found then
    raise exception using errcode = 'P0002', message = 'Task not found.';
  end if;

  select m.role, m.status, m.project_scopes
    into v_role, v_member_status, v_scopes
  from public.owner_workforce_members m
  where m.user_id = p_actor_user_id;

  if not found or v_role not in ('developer', 'qa') or v_member_status <> 'active'
     or v_task.assignee_id <> p_actor_user_id
     or not (v_task.project_key = any(coalesce(v_scopes, array[]::text[]))) then
    raise exception using errcode = '42501', message = 'Only the active assigned Developer or QA member may append evidence.';
  end if;

  if v_task.status = 'done' then
    raise exception using errcode = '22023', message = 'Completed tasks are read-only.';
  end if;

  insert into public.owner_workforce_task_events
    (request_id, task_id, project_key, actor_user_id, event_type, details)
  values
    (p_request_id, p_task_id, v_task.project_key, p_actor_user_id, 'evidence_added',
     pg_catalog.jsonb_build_object('operation', 'append_task_evidence', 'evidence', p_evidence));

  return pg_catalog.to_jsonb(v_task);
end;
$function$;

revoke all on function public.owner_workforce_create_task(uuid, text, text, text, text, text, timestamptz, uuid) from public, anon, authenticated;
revoke all on function public.owner_workforce_assign_task(uuid, uuid, uuid, uuid) from public, anon, authenticated;
revoke all on function public.owner_workforce_update_task_status(uuid, uuid, text, text, text, uuid) from public, anon, authenticated;
revoke all on function public.owner_workforce_append_task_evidence(uuid, uuid, text, uuid) from public, anon, authenticated;

grant execute on function public.owner_workforce_create_task(uuid, text, text, text, text, text, timestamptz, uuid) to service_role;
grant execute on function public.owner_workforce_assign_task(uuid, uuid, uuid, uuid) to service_role;
grant execute on function public.owner_workforce_update_task_status(uuid, uuid, text, text, text, uuid) to service_role;
grant execute on function public.owner_workforce_append_task_evidence(uuid, uuid, text, uuid) to service_role;

comment on function public.owner_workforce_create_task(uuid, text, text, text, text, text, timestamptz, uuid)
  is 'DRAFT ONLY. Service-role Edge Function RPC. Actor ID must come from Auth getUser(token). Atomic task creation and audit event.';
comment on function public.owner_workforce_assign_task(uuid, uuid, uuid, uuid)
  is 'DRAFT ONLY. Service-role Edge Function RPC. Enforces PM scope and active assignee role. Atomic assignment and audit event.';
comment on function public.owner_workforce_update_task_status(uuid, uuid, text, text, text, uuid)
  is 'DRAFT ONLY. Service-role Edge Function RPC. Assigned Developer/QA only; strict transitions and evidence. Atomic update and audit event.';
comment on function public.owner_workforce_append_task_evidence(uuid, uuid, text, uuid)
  is 'DRAFT ONLY. Service-role Edge Function RPC. Assigned Developer/QA only. Atomic append-only evidence event.';
