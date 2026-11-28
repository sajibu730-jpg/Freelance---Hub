-- Disposable local Supabase fixture only. Never use against Production.
-- The evidence draft references these pre-existing application tables.
create table public.jobs (
  id uuid primary key,
  owner_id uuid not null references auth.users(id),
  title text not null default 'CI test job',
  status text not null default 'open'
);

create table public.contracts (
  id uuid primary key,
  job_id uuid not null references public.jobs(id),
  client_id uuid not null references auth.users(id),
  freelancer_id uuid not null references auth.users(id),
  status text not null default 'active'
);

create table public.work_submissions (
  id uuid primary key,
  job_id uuid not null references public.jobs(id),
  contract_id uuid references public.contracts(id),
  freelancer_id uuid not null references auth.users(id)
);

create table public.proposals (
  id uuid primary key,
  job_id uuid not null references public.jobs(id),
  freelancer_id uuid not null references auth.users(id)
);

create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  display_name text,
  country text
);

create table public.app_admins (
  user_id uuid primary key references auth.users(id) on delete cascade,
  role text not null default 'owner' check (role in ('owner', 'admin')),
  is_active boolean not null default true,
  created_at timestamptz not null default now()
);
