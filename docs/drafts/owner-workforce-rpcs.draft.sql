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
  where m.user_id = p_actor_user_id
  for share;

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
  where m.user_id = p_actor_user_id
  for share;

  if not found or v_role <> 'project_manager' or v_status <> 'active'
     or v_task.status = 'done'
     or not (v_task.project_key = any(coalesce(v_scopes, array[]::text[]))) then
    raise exception using errcode = '42501', message = 'An active scoped Project Manager is required.';
  end if;

  select m.role, m.status, m.project_scopes
    into v_assignee_role, v_assignee_status, v_assignee_scopes
  from public.owner_workforce_members m
  where m.user_id = p_assignee_id
  for share;

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
  where m.user_id = p_actor_user_id
  for share;

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
  where m.user_id = p_actor_user_id
  for share;

  if not found or v_role not in ('developer', 'qa') or v_member_status <> 'active'
     or v_task.assignee_id is distinct from p_actor_user_id
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


-- Owner-only workforce membership administration.
-- AAL2 is enforced by the Edge Function; this database layer independently
-- verifies that the supplied actor is the active primary Owner.
create or replace function public.owner_workforce_set_member(
  p_actor_user_id uuid,
  p_member_user_id uuid,
  p_role text,
  p_project_scopes text[],
  p_request_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_owner_role text;
  v_owner_active boolean;
  v_member public.owner_workforce_members%rowtype;
  v_existing public.owner_workforce_member_events%rowtype;
  v_event_type text;
  v_old_role text;
  v_old_scopes text[];
  v_invalid_scopes integer;
begin
  if p_actor_user_id is null or p_member_user_id is null or p_request_id is null
     or p_actor_user_id = p_member_user_id
     or p_role is null or p_role not in ('project_manager', 'developer', 'qa', 'support')
     or p_project_scopes is null or cardinality(p_project_scopes) < 1
     or cardinality(p_project_scopes) > 50 then
    raise exception using errcode = '22023', message = 'Invalid workforce member details.';
  end if;

  select count(*) into v_invalid_scopes
  from pg_catalog.unnest(p_project_scopes) as scopes(scope_value)
  where scope_value is null or length(scope_value) not between 1 and 100 or scope_value <> btrim(scope_value);
  if v_invalid_scopes > 0
     or cardinality(p_project_scopes) <> (
       select count(distinct scope_value) from pg_catalog.unnest(p_project_scopes) as scopes(scope_value)
     ) then
    raise exception using errcode = '22023', message = 'Project scopes must be unique, trimmed keys of 1 to 100 characters.';
  end if;

  select a.role, a.is_active into v_owner_role, v_owner_active
  from public.app_admins a
  where a.user_id = p_actor_user_id;
  if not found or v_owner_role <> 'owner' or v_owner_active is distinct from true then
    raise exception using errcode = '42501', message = 'Active primary Owner authorization is required.';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(p_request_id::text));

  select e.* into v_existing
  from public.owner_workforce_member_events e
  where e.request_id = p_request_id;
  if found then
    if v_existing.actor_user_id <> p_actor_user_id
       or v_existing.member_user_id <> p_member_user_id
       or v_existing.details ->> 'operation' is distinct from 'set_member'
       or v_existing.details ->> 'role' is distinct from p_role
       or v_existing.details -> 'project_scopes' is distinct from pg_catalog.to_jsonb(p_project_scopes) then
      raise exception using errcode = '23505', message = 'Idempotency key was already used.';
    end if;
    select m.* into v_member from public.owner_workforce_members m where m.user_id = p_member_user_id;
    if not found then
      raise exception using errcode = 'P0002', message = 'Previously managed member no longer exists.';
    end if;
    return pg_catalog.to_jsonb(v_member);
  end if;

  select m.* into v_member
  from public.owner_workforce_members m
  where m.user_id = p_member_user_id
  for update;

  if not found then
    insert into public.owner_workforce_members
      (user_id, role, status, project_scopes, invited_by)
    values
      (p_member_user_id, p_role, 'active', p_project_scopes, p_actor_user_id)
    returning * into v_member;
    v_event_type := 'added';
    v_old_role := null;
    v_old_scopes := null;
  else
    v_old_role := v_member.role;
    v_old_scopes := v_member.project_scopes;
    if v_member.role = p_role and v_member.project_scopes = p_project_scopes then
      raise exception using errcode = '22023', message = 'No workforce member changes were requested.';
    end if;
    if exists (
      select 1
      from public.owner_workforce_tasks t
      where t.assignee_id = p_member_user_id
        and t.status <> 'done'
        and (
          v_member.role is distinct from p_role
          or not (t.project_key = any(p_project_scopes))
        )
    ) then
      raise exception using errcode = '22023',
        message = 'Reassign or complete open tasks before changing this member role or project scope.';
    end if;
    update public.owner_workforce_members
    set role = p_role, project_scopes = p_project_scopes, updated_at = pg_catalog.now()
    where user_id = p_member_user_id
    returning * into v_member;
    v_event_type := case when v_old_role is distinct from p_role then 'role_changed' else 'scope_changed' end;
  end if;

  insert into public.owner_workforce_member_events
    (request_id, member_user_id, actor_user_id, event_type, details)
  values
    (p_request_id, p_member_user_id, p_actor_user_id, v_event_type,
     pg_catalog.jsonb_build_object(
       'operation', 'set_member',
       'role', p_role,
       'project_scopes', p_project_scopes,
       'previous_role', v_old_role,
       'previous_project_scopes', v_old_scopes
     ));

  return pg_catalog.to_jsonb(v_member);
end;
$function$;

create or replace function public.owner_workforce_set_member_status(
  p_actor_user_id uuid,
  p_member_user_id uuid,
  p_new_status text,
  p_request_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_owner_role text;
  v_owner_active boolean;
  v_member public.owner_workforce_members%rowtype;
  v_existing public.owner_workforce_member_events%rowtype;
  v_event_type text;
begin
  if p_actor_user_id is null or p_member_user_id is null or p_request_id is null
     or p_actor_user_id = p_member_user_id
     or p_new_status is null or p_new_status not in ('active', 'suspended') then
    raise exception using errcode = '22023', message = 'Invalid workforce status request.';
  end if;

  select a.role, a.is_active into v_owner_role, v_owner_active
  from public.app_admins a
  where a.user_id = p_actor_user_id;
  if not found or v_owner_role <> 'owner' or v_owner_active is distinct from true then
    raise exception using errcode = '42501', message = 'Active primary Owner authorization is required.';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(p_request_id::text));

  select e.* into v_existing
  from public.owner_workforce_member_events e
  where e.request_id = p_request_id;
  if found then
    if v_existing.actor_user_id <> p_actor_user_id
       or v_existing.member_user_id <> p_member_user_id
       or v_existing.details ->> 'operation' is distinct from 'set_member_status'
       or v_existing.details ->> 'status' is distinct from p_new_status then
      raise exception using errcode = '23505', message = 'Idempotency key was already used.';
    end if;
    select m.* into v_member from public.owner_workforce_members m where m.user_id = p_member_user_id;
    if not found then
      raise exception using errcode = 'P0002', message = 'Previously managed member no longer exists.';
    end if;
    return pg_catalog.to_jsonb(v_member);
  end if;

  select m.* into v_member
  from public.owner_workforce_members m
  where m.user_id = p_member_user_id
  for update;
  if not found then
    raise exception using errcode = 'P0002', message = 'Workforce member not found.';
  end if;
  if v_member.status = p_new_status then
    raise exception using errcode = '22023', message = 'Member already has the requested status.';
  end if;

  update public.owner_workforce_members
  set status = p_new_status, updated_at = pg_catalog.now()
  where user_id = p_member_user_id
  returning * into v_member;

  v_event_type := case when p_new_status = 'suspended' then 'suspended' else 'reactivated' end;
  insert into public.owner_workforce_member_events
    (request_id, member_user_id, actor_user_id, event_type, details)
  values
    (p_request_id, p_member_user_id, p_actor_user_id, v_event_type,
     pg_catalog.jsonb_build_object(
       'operation', 'set_member_status',
       'previous_status', case when p_new_status = 'suspended' then 'active' else 'suspended' end,
       'status', p_new_status
     ));

  return pg_catalog.to_jsonb(v_member);
end;
$function$;

revoke all on function public.owner_workforce_set_member(uuid, uuid, text, text[], uuid) from public, anon, authenticated;
revoke all on function public.owner_workforce_set_member_status(uuid, uuid, text, uuid) from public, anon, authenticated;
grant execute on function public.owner_workforce_set_member(uuid, uuid, text, text[], uuid) to service_role;
grant execute on function public.owner_workforce_set_member_status(uuid, uuid, text, uuid) to service_role;

comment on function public.owner_workforce_set_member(uuid, uuid, text, text[], uuid)
  is 'DRAFT ONLY. Owner AAL2 Edge Function required; DB also checks active app_admins Owner. Adds or updates existing Auth accounts and writes an atomic member audit event. Does not send invitations.';
comment on function public.owner_workforce_set_member_status(uuid, uuid, text, uuid)
  is 'DRAFT ONLY. Owner AAL2 Edge Function required; DB also checks active app_admins Owner. Suspends/reactivates workforce access with an atomic audit event.';


create or replace function public.owner_workforce_list_member_events(
  p_actor_user_id uuid,
  p_member_user_id uuid,
  p_limit integer
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_owner_role text;
  v_owner_active boolean;
  v_events jsonb;
begin
  if p_actor_user_id is null or p_limit is null or p_limit not between 1 and 100 then
    raise exception using errcode = '22023', message = 'Invalid member audit query.';
  end if;

  select a.role, a.is_active into v_owner_role, v_owner_active
  from public.app_admins a
  where a.user_id = p_actor_user_id
  for share;
  if not found or v_owner_role <> 'owner' or v_owner_active is distinct from true then
    raise exception using errcode = '42501', message = 'Active primary Owner authorization is required.';
  end if;

  select coalesce(
    pg_catalog.jsonb_agg(pg_catalog.to_jsonb(event_row) order by event_row.created_at desc),
    '[]'::jsonb
  )
  into v_events
  from (
    select e.id, e.member_user_id, e.actor_user_id, e.event_type, e.details, e.created_at
    from public.owner_workforce_member_events e
    where p_member_user_id is null or e.member_user_id = p_member_user_id
    order by e.created_at desc
    limit p_limit
  ) as event_row;

  return v_events;
end;
$function$;

revoke all on function public.owner_workforce_list_member_events(uuid, uuid, integer) from public, anon, authenticated;
grant execute on function public.owner_workforce_list_member_events(uuid, uuid, integer) to service_role;
comment on function public.owner_workforce_list_member_events(uuid, uuid, integer)
  is 'DRAFT ONLY. Owner AAL2 Edge Function required; DB independently checks active app_admins Owner. Read-only bounded membership audit history.';


-- ---------------------------------------------------------------------------
-- Recoverable Owner-managed invitation RPCs. Draft only; disposable CI database.
-- Auth email delivery is external and is deliberately NOT performed in SQL.
-- ---------------------------------------------------------------------------

create or replace function public.owner_workforce_prepare_invitation(
  p_actor_user_id uuid,
  p_email text,
  p_role text,
  p_project_scopes text[],
  p_request_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_invite public.owner_workforce_invitations%rowtype;
begin
  if p_actor_user_id is null or p_request_id is null
     or p_email is null or p_email <> pg_catalog.lower(pg_catalog.btrim(p_email))
     or length(p_email) not between 5 and 254
     or p_email !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
     or p_role is null or p_role not in ('project_manager', 'developer', 'qa', 'support')
     or p_project_scopes is null or pg_catalog.cardinality(p_project_scopes) not between 1 and 50
     or exists (select 1 from pg_catalog.unnest(p_project_scopes) as x(scope)
                where scope is null or scope = '' or scope <> pg_catalog.btrim(scope) or length(scope) > 100)
     or (select count(*) from pg_catalog.unnest(p_project_scopes)) <>
        (select count(distinct scope) from pg_catalog.unnest(p_project_scopes) as x(scope)) then
    raise exception using errcode = '22023', message = 'Invalid workforce invitation input.';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(p_request_id::text));

  if not exists (
    select 1 from public.app_admins a
    where a.user_id = p_actor_user_id and a.role = 'owner' and a.is_active = true
  ) then
    raise exception using errcode = '42501', message = 'Active primary Owner is required.';
  end if;

  select i.* into v_invite
  from public.owner_workforce_invitations i
  where i.request_id = p_request_id;
  if found then
    if v_invite.invited_by <> p_actor_user_id
       or v_invite.email <> p_email
       or v_invite.role <> p_role
       or v_invite.project_scopes <> p_project_scopes then
      raise exception using errcode = '23505', message = 'Invitation request ID was already used.';
    end if;
    return pg_catalog.to_jsonb(v_invite);
  end if;

  if exists (select 1 from auth.users u where pg_catalog.lower(u.email) = p_email) then
    raise exception using errcode = '23505', message = 'An Auth account already exists for this email.';
  end if;

  insert into public.owner_workforce_invitations
    (request_id, email, role, project_scopes, invited_by)
  values (p_request_id, p_email, p_role, p_project_scopes, p_actor_user_id)
  returning * into v_invite;

  insert into public.owner_workforce_invitation_events
    (invitation_id, actor_user_id, event_type, details)
  values
    (v_invite.id, p_actor_user_id, 'prepared',
     pg_catalog.jsonb_build_object('role', p_role, 'project_scope_count', pg_catalog.cardinality(p_project_scopes)));

  return pg_catalog.to_jsonb(v_invite);
end;
$function$;

create or replace function public.owner_workforce_claim_invitation_dispatch(
  p_actor_user_id uuid,
  p_invitation_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_invite public.owner_workforce_invitations%rowtype;
begin
  if p_actor_user_id is null or p_invitation_id is null then
    raise exception using errcode = '22023', message = 'Invalid invitation dispatch claim.';
  end if;
  if not exists (
    select 1 from public.app_admins a
    where a.user_id = p_actor_user_id and a.role = 'owner' and a.is_active = true
  ) then
    raise exception using errcode = '42501', message = 'Active primary Owner is required.';
  end if;

  select i.* into v_invite
  from public.owner_workforce_invitations i
  where i.id = p_invitation_id
  for update;
  if not found then
    raise exception using errcode = 'P0002', message = 'Invitation not found.';
  end if;

  if v_invite.status <> 'prepared' then
    return pg_catalog.jsonb_build_object('claimed', false, 'invitation', pg_catalog.to_jsonb(v_invite));
  end if;

  update public.owner_workforce_invitations
  set status = 'dispatching', updated_at = pg_catalog.now()
  where id = p_invitation_id
  returning * into v_invite;

  insert into public.owner_workforce_invitation_events
    (invitation_id, actor_user_id, event_type, details)
  values (p_invitation_id, p_actor_user_id, 'dispatch_claimed', '{}'::jsonb);

  return pg_catalog.jsonb_build_object('claimed', true, 'invitation', pg_catalog.to_jsonb(v_invite));
end;
$function$;

create or replace function public.owner_workforce_record_invitation_sent(
  p_actor_user_id uuid,
  p_invitation_id uuid,
  p_auth_user_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_invite public.owner_workforce_invitations%rowtype;
  v_auth_email text;
  v_invited_at timestamptz;
begin
  if p_actor_user_id is null or p_invitation_id is null or p_auth_user_id is null then
    raise exception using errcode = '22023', message = 'Invalid invitation completion.';
  end if;
  if not exists (
    select 1 from public.app_admins a
    where a.user_id = p_actor_user_id and a.role = 'owner' and a.is_active = true
  ) then
    raise exception using errcode = '42501', message = 'Active primary Owner is required.';
  end if;

  select i.* into v_invite
  from public.owner_workforce_invitations i
  where i.id = p_invitation_id
  for update;
  if not found then
    raise exception using errcode = 'P0002', message = 'Invitation not found.';
  end if;
  if v_invite.status in ('sent', 'accepted') and v_invite.auth_user_id = p_auth_user_id then
    return pg_catalog.to_jsonb(v_invite);
  end if;
  if v_invite.status not in ('dispatching', 'needs_review') then
    raise exception using errcode = '22023', message = 'Invitation is not awaiting Auth reconciliation.';
  end if;

  select u.email, u.invited_at into v_auth_email, v_invited_at
  from auth.users u
  where u.id = p_auth_user_id;
  if not found or pg_catalog.lower(v_auth_email) <> v_invite.email or v_invited_at is null then
    raise exception using errcode = '22023', message = 'Auth user does not match the invited email.';
  end if;

  update public.owner_workforce_invitations
  set status = 'sent', auth_user_id = p_auth_user_id,
      sent_at = coalesce(sent_at, pg_catalog.now()), review_code = null,
      updated_at = pg_catalog.now()
  where id = p_invitation_id
  returning * into v_invite;

  insert into public.owner_workforce_invitation_events
    (invitation_id, actor_user_id, event_type, details)
  values (p_invitation_id, p_actor_user_id, 'sent',
          pg_catalog.jsonb_build_object('auth_user_id', p_auth_user_id));

  return pg_catalog.to_jsonb(v_invite);
end;
$function$;

create or replace function public.owner_workforce_mark_invitation_review(
  p_actor_user_id uuid,
  p_invitation_id uuid,
  p_reason text,
  p_auth_user_id uuid default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_invite public.owner_workforce_invitations%rowtype;
  v_auth_email text;
begin
  if p_actor_user_id is null or p_invitation_id is null
     or p_reason not in ('auth_error', 'auth_exception', 'record_sent_failed') then
    raise exception using errcode = '22023', message = 'Invalid invitation review details.';
  end if;
  if not exists (
    select 1 from public.app_admins a
    where a.user_id = p_actor_user_id and a.role = 'owner' and a.is_active = true
  ) then
    raise exception using errcode = '42501', message = 'Active primary Owner is required.';
  end if;

  select i.* into v_invite
  from public.owner_workforce_invitations i
  where i.id = p_invitation_id
  for update;
  if not found then
    raise exception using errcode = 'P0002', message = 'Invitation not found.';
  end if;
  if v_invite.status = 'needs_review' then
    return pg_catalog.to_jsonb(v_invite);
  end if;
  if v_invite.status <> 'dispatching' then
    raise exception using errcode = '22023', message = 'Invitation is not in dispatching state.';
  end if;

  if p_auth_user_id is not null then
    select u.email into v_auth_email from auth.users u where u.id = p_auth_user_id;
    if not found or pg_catalog.lower(v_auth_email) <> v_invite.email then
      raise exception using errcode = '22023', message = 'Auth user does not match the invited email.';
    end if;
  end if;

  update public.owner_workforce_invitations
  set status = 'needs_review', auth_user_id = coalesce(p_auth_user_id, auth_user_id),
      review_code = p_reason, updated_at = pg_catalog.now()
  where id = p_invitation_id
  returning * into v_invite;

  insert into public.owner_workforce_invitation_events
    (invitation_id, actor_user_id, event_type, details)
  values (p_invitation_id, p_actor_user_id, 'needs_review',
          pg_catalog.jsonb_build_object('reason', p_reason, 'auth_user_id', p_auth_user_id));

  return pg_catalog.to_jsonb(v_invite);
end;
$function$;

create or replace function public.owner_workforce_accept_invitation(
  p_actor_user_id uuid,
  p_invitation_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_invite public.owner_workforce_invitations%rowtype;
  v_auth_email text;
  v_confirmed_at timestamptz;
  v_member public.owner_workforce_members%rowtype;
begin
  if p_actor_user_id is null or p_invitation_id is null then
    raise exception using errcode = '22023', message = 'Invalid invitation acceptance.';
  end if;

  select i.* into v_invite
  from public.owner_workforce_invitations i
  where i.id = p_invitation_id
  for update;
  if not found then
    raise exception using errcode = 'P0002', message = 'Invitation not found.';
  end if;
  if v_invite.status = 'accepted' and v_invite.auth_user_id = p_actor_user_id then
    select m.* into v_member from public.owner_workforce_members m where m.user_id = p_actor_user_id;
    return pg_catalog.to_jsonb(v_member);
  end if;
  if v_invite.status not in ('sent', 'needs_review') or v_invite.auth_user_id is distinct from p_actor_user_id then
    raise exception using errcode = '42501', message = 'This Auth account is not authorized for the invitation.';
  end if;

  select u.email, u.email_confirmed_at into v_auth_email, v_confirmed_at
  from auth.users u
  where u.id = p_actor_user_id;
  if not found or pg_catalog.lower(v_auth_email) <> v_invite.email or v_confirmed_at is null then
    raise exception using errcode = '42501', message = 'A matching verified email is required to accept the invitation.';
  end if;

  insert into public.owner_workforce_members
    (user_id, role, status, project_scopes, invited_by)
  values
    (p_actor_user_id, v_invite.role, 'active', v_invite.project_scopes, v_invite.invited_by)
  returning * into v_member;

  update public.owner_workforce_invitations
  set status = 'accepted', accepted_at = pg_catalog.now(), updated_at = pg_catalog.now()
  where id = p_invitation_id
  returning * into v_invite;

  insert into public.owner_workforce_member_events
    (member_user_id, actor_user_id, event_type, details)
  values (p_actor_user_id, v_invite.invited_by, 'added',
          pg_catalog.jsonb_build_object('source', 'invitation', 'invitation_id', p_invitation_id));

  insert into public.owner_workforce_invitation_events
    (invitation_id, actor_user_id, event_type, details)
  values (p_invitation_id, p_actor_user_id, 'accepted', '{}'::jsonb);

  return pg_catalog.to_jsonb(v_member);
end;
$function$;

create or replace function public.owner_workforce_cancel_invitation(
  p_actor_user_id uuid,
  p_invitation_id uuid,
  p_reason text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_invite public.owner_workforce_invitations%rowtype;
begin
  if p_actor_user_id is null or p_invitation_id is null
     or p_reason is null or length(p_reason) not between 1 and 1000 then
    raise exception using errcode = '22023', message = 'A cancellation reason is required.';
  end if;
  if not exists (
    select 1 from public.app_admins a
    where a.user_id = p_actor_user_id and a.role = 'owner' and a.is_active = true
  ) then
    raise exception using errcode = '42501', message = 'Active primary Owner is required.';
  end if;

  select i.* into v_invite
  from public.owner_workforce_invitations i
  where i.id = p_invitation_id
  for update;
  if not found then
    raise exception using errcode = 'P0002', message = 'Invitation not found.';
  end if;
  if v_invite.status = 'cancelled' then
    return pg_catalog.to_jsonb(v_invite);
  end if;
  if v_invite.status = 'accepted' then
    raise exception using errcode = '22023', message = 'An accepted workforce membership must be suspended separately.';
  end if;

  update public.owner_workforce_invitations
  set status = 'cancelled', cancelled_at = pg_catalog.now(),
      updated_at = pg_catalog.now()
  where id = p_invitation_id
  returning * into v_invite;

  insert into public.owner_workforce_invitation_events
    (invitation_id, actor_user_id, event_type, details)
  values (p_invitation_id, p_actor_user_id, 'cancelled',
          pg_catalog.jsonb_build_object('reason', p_reason, 'auth_account_deleted', false));

  return pg_catalog.to_jsonb(v_invite);
end;
$function$;

create or replace function public.owner_workforce_list_invitations(
  p_actor_user_id uuid,
  p_limit integer default 50
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $function$
begin
  if p_actor_user_id is null or p_limit is null or p_limit not between 1 and 100 then
    raise exception using errcode = '22023', message = 'Invitation list limit must be from 1 to 100.';
  end if;
  if not exists (
    select 1 from public.app_admins a
    where a.user_id = p_actor_user_id and a.role = 'owner' and a.is_active = true
  ) then
    raise exception using errcode = '42501', message = 'Active primary Owner is required.';
  end if;
  return coalesce((
    select pg_catalog.jsonb_agg(pg_catalog.to_jsonb(x) order by x.created_at desc)
    from (
      select i.id, i.email, i.role, i.project_scopes, i.status, i.invited_by,
             i.auth_user_id, i.review_code, i.created_at, i.updated_at,
             i.sent_at, i.accepted_at, i.cancelled_at
      from public.owner_workforce_invitations i
      order by i.created_at desc
      limit p_limit
    ) x
  ), '[]'::jsonb);
end;
$function$;

revoke all on function public.owner_workforce_prepare_invitation(uuid,text,text,text[],uuid) from public, anon, authenticated;
revoke all on function public.owner_workforce_claim_invitation_dispatch(uuid,uuid) from public, anon, authenticated;
revoke all on function public.owner_workforce_record_invitation_sent(uuid,uuid,uuid) from public, anon, authenticated;
revoke all on function public.owner_workforce_mark_invitation_review(uuid,uuid,text,uuid) from public, anon, authenticated;
revoke all on function public.owner_workforce_accept_invitation(uuid,uuid) from public, anon, authenticated;
revoke all on function public.owner_workforce_cancel_invitation(uuid,uuid,text) from public, anon, authenticated;
revoke all on function public.owner_workforce_list_invitations(uuid,integer) from public, anon, authenticated;

grant execute on function public.owner_workforce_prepare_invitation(uuid,text,text,text[],uuid) to service_role;
grant execute on function public.owner_workforce_claim_invitation_dispatch(uuid,uuid) to service_role;
grant execute on function public.owner_workforce_record_invitation_sent(uuid,uuid,uuid) to service_role;
grant execute on function public.owner_workforce_mark_invitation_review(uuid,uuid,text,uuid) to service_role;
grant execute on function public.owner_workforce_accept_invitation(uuid,uuid) to service_role;
grant execute on function public.owner_workforce_cancel_invitation(uuid,uuid,text) to service_role;
grant execute on function public.owner_workforce_list_invitations(uuid,integer) to service_role;
