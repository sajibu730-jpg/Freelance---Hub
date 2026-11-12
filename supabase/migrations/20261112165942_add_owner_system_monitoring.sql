create extension if not exists pg_cron with schema pg_catalog;
create extension if not exists pg_net;

create table if not exists public.owner_system_reports (
  id uuid primary key default gen_random_uuid(),
  created_at timestamptz not null default now(),
  trigger_source text not null default 'scheduled',
  status text not null check (status in ('all_clear','attention','incomplete')),
  summary text not null,
  details jsonb not null default '{}'::jsonb
);

create index if not exists owner_system_reports_created_at_idx
  on public.owner_system_reports (created_at desc);

alter table public.owner_system_reports enable row level security;
grant select on public.owner_system_reports to authenticated;
grant insert on public.owner_system_reports to service_role;

drop policy if exists "Active owners can read system reports" on public.owner_system_reports;
create policy "Active owners can read system reports"
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
);

create or replace function public.verify_owner_monitor_token(p_token text)
returns boolean
language sql
security definer
set search_path = pg_catalog, vault
as $$
  select p_token is not null
     and length(p_token) >= 40
     and exists (
       select 1
       from vault.decrypted_secrets s
       where s.name = 'owner_system_monitor_token'
         and s.decrypted_secret = p_token
     );
$$;

revoke all on function public.verify_owner_monitor_token(text) from public, anon, authenticated;
grant execute on function public.verify_owner_monitor_token(text) to service_role;

do $$
begin
  if not exists (
    select 1 from vault.secrets where name = 'owner_system_monitor_token'
  ) then
    perform vault.create_secret(
      gen_random_uuid()::text || gen_random_uuid()::text || gen_random_uuid()::text,
      'owner_system_monitor_token',
      'Private token used only by the scheduled Freelance Hub system monitor'
    );
  end if;
end
$$;

select cron.schedule(
  'freelance-hub-owner-system-monitor',
  '0 14 * * *',
  $job$
    select net.http_post(
      url := 'https://imffbmwdlenhmhxopwoq.supabase.co/functions/v1/owner-system-monitor',
      headers := jsonb_build_object(
        'Content-Type', 'application/json',
        'apikey', 'sb_publishable_dn88vDD3YMqs_-KBbfkwSA_kU1PGyPE',
        'x-monitor-token', (
          select decrypted_secret
          from vault.decrypted_secrets
          where name = 'owner_system_monitor_token'
        )
      ),
      body := jsonb_build_object('trigger', 'scheduled'),
      timeout_milliseconds := 25000
    );
  $job$
);
