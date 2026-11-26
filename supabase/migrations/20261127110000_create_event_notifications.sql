-- Event-driven in-app notifications for proposals and messages.
-- Notification rows are written only by narrowly scoped trigger functions.

create or replace function public.create_proposal_notification()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_owner_id uuid;
  v_job_title text;
begin
  select j.owner_id, j.title into v_owner_id, v_job_title
  from public.jobs j
  where j.id = new.job_id;

  if v_owner_id is not null and v_owner_id <> new.freelancer_id then
    insert into public.notifications(user_id, notification_type, title, body, target_type, target_id)
    values (v_owner_id, 'proposal_submitted', 'New proposal received',
      'A Freelancer submitted a proposal for "' || coalesce(v_job_title, 'your job') || '".',
      'proposal', new.id);
  end if;
  return new;
end;
$$;

create or replace function public.create_proposal_status_notification()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_job_title text;
  v_title text;
  v_body text;
begin
  if old.status is distinct from new.status
     and new.status in ('accepted', 'rejected') then
    select j.title into v_job_title from public.jobs j where j.id = new.job_id;

    if new.status = 'accepted' then
      v_title := 'Proposal accepted';
      v_body := 'Your proposal for "' || coalesce(v_job_title, 'a job') || '" was accepted. Your contract is now active.';
    else
      v_title := 'Proposal update';
      v_body := 'Your proposal for "' || coalesce(v_job_title, 'a job') || '" was not selected.';
    end if;

    insert into public.notifications(user_id, notification_type, title, body, target_type, target_id)
    values (new.freelancer_id, 'proposal_' || new.status, v_title, v_body, 'proposal', new.id);
  end if;
  return new;
end;
$$;

create or replace function public.create_message_notification()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_client_id uuid;
  v_freelancer_id uuid;
  v_recipient_id uuid;
begin
  select c.client_id, c.freelancer_id into v_client_id, v_freelancer_id
  from public.conversations c where c.id = new.conversation_id;

  if new.sender_id = v_client_id then
    v_recipient_id := v_freelancer_id;
  elsif new.sender_id = v_freelancer_id then
    v_recipient_id := v_client_id;
  end if;

  if v_recipient_id is not null and v_recipient_id <> new.sender_id then
    insert into public.notifications(user_id, notification_type, title, body, target_type, target_id)
    values (v_recipient_id, 'new_message', 'New message',
      'You received a new message.', 'conversation', new.conversation_id);
  end if;
  return new;
end;
$$;

drop trigger if exists trg_notify_proposal_submitted on public.proposals;
create trigger trg_notify_proposal_submitted
after insert on public.proposals
for each row execute function public.create_proposal_notification();

drop trigger if exists trg_notify_proposal_status on public.proposals;
create trigger trg_notify_proposal_status
after update of status on public.proposals
for each row execute function public.create_proposal_status_notification();

drop trigger if exists trg_notify_new_message on public.messages;
create trigger trg_notify_new_message
after insert on public.messages
for each row execute function public.create_message_notification();

revoke all on function public.create_proposal_notification() from public, anon, authenticated;
revoke all on function public.create_proposal_status_notification() from public, anon, authenticated;
revoke all on function public.create_message_notification() from public, anon, authenticated;

do $$
begin
  if not exists (
    select 1 from pg_publication_tables
    where pubname = 'supabase_realtime'
      and schemaname = 'public'
      and tablename = 'notifications'
  ) then
    alter publication supabase_realtime add table public.notifications;
  end if;
end
$$;

-- Notify applicants when a job they applied to changes lifecycle state.
create or replace function public.create_job_status_notifications()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_title text;
  v_body text;
begin
  if old.status is distinct from new.status
     and new.status in ('in_progress', 'completed', 'cancelled') then
    if new.status = 'in_progress' then
      v_title := 'Job started';
      v_body := 'The job "' || new.title || '" has moved to in progress.';
    elsif new.status = 'completed' then
      v_title := 'Job completed';
      v_body := 'The job "' || new.title || '" has been marked completed.';
    else
      v_title := 'Job cancelled';
      v_body := 'The job "' || new.title || '" has been cancelled by the Client.';
    end if;

    insert into public.notifications(user_id, notification_type, title, body, target_type, target_id)
    select distinct p.freelancer_id, 'job_' || new.status, v_title, v_body, 'job', new.id
    from public.proposals p
    where p.job_id = new.id
      and (
        (new.status in ('in_progress', 'completed') and p.status = 'accepted')
        or (new.status = 'cancelled' and p.status in ('pending', 'accepted'))
      )
      and p.freelancer_id <> new.owner_id;
  end if;
  return new;
end;
$$;

drop trigger if exists trg_notify_job_status on public.jobs;
create trigger trg_notify_job_status
after update of status on public.jobs
for each row execute function public.create_job_status_notifications();

revoke all on function public.create_job_status_notifications() from public, anon, authenticated;
