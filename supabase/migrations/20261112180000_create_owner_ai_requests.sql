create table if not exists public.owner_ai_requests (
  id uuid primary key default gen_random_uuid(),
  created_by uuid not null references auth.users(id) on delete cascade,
  prompt text not null check (char_length(prompt) between 1 and 6000),
  response text not null,
  proposal jsonb,
  status text not null default 'draft'
    check (status in ('draft','approved','rejected')),
  created_at timestamptz not null default now(),
  reviewed_at timestamptz
);
alter table public.owner_ai_requests enable row level security;
revoke all on public.owner_ai_requests from anon;
grant select on public.owner_ai_requests to authenticated;
drop policy if exists "Active owners can read AI requests" on public.owner_ai_requests;
create policy "Active owners can read AI requests"
on public.owner_ai_requests for select to authenticated
using (exists (
  select 1 from public.app_admins a
  where a.user_id = (select auth.uid()) and a.role = 'owner' and a.is_active = true
));
comment on table public.owner_ai_requests is
  'Owner-only history of AI-generated system proposals. Drafts never apply changes automatically.';
