-- Store per-user Android Firebase Cloud Messaging registration tokens.
-- The Firebase service account is never stored in this table or shipped in the app.

create table if not exists public.device_push_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  fcm_token text not null unique,
  platform text not null default 'android' check (platform in ('android')),
  device_label text,
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now()
);

create index if not exists idx_device_push_tokens_user_active
  on public.device_push_tokens(user_id, is_active);

alter table public.device_push_tokens enable row level security;

drop policy if exists "Users manage own push tokens" on public.device_push_tokens;
create policy "Users manage own push tokens"
  on public.device_push_tokens
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.device_push_tokens to authenticated;
revoke all on public.device_push_tokens from anon;
