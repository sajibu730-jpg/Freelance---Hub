create table if not exists public.user_platform_activity (
  user_id uuid not null references auth.users(id) on delete cascade,
  platform text not null check (platform in ('android','web','ios','other')),
  first_seen_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  last_activity_type text not null default 'session_start',
  primary key (user_id, platform)
);

create index if not exists user_platform_activity_last_seen_idx
  on public.user_platform_activity (last_seen_at desc);
create index if not exists user_platform_activity_platform_last_seen_idx
  on public.user_platform_activity (platform, last_seen_at desc);

alter table public.user_platform_activity enable row level security;
revoke all on public.user_platform_activity from anon, authenticated;
grant all on public.user_platform_activity to service_role;

comment on table public.user_platform_activity is
  'One privacy-minimized latest-activity row per authenticated user and platform; used for cross-platform unique-user analytics.';
comment on column public.user_platform_activity.last_activity_type is
  'Coarse event category only; never store screen content, messages, or personal data.';
