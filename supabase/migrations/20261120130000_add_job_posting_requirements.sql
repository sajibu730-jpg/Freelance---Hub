-- Store all requirements collected by the three-step Job Posting flow.
alter table public.jobs
  add column if not exists category text not null default 'Other',
  add column if not exists experience_level text not null default 'Beginner',
  add column if not exists duration_days integer not null default 7,
  add column if not exists freelancers_needed integer not null default 1;

do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'jobs_category_length_check' and conrelid = 'public.jobs'::regclass) then
    alter table public.jobs add constraint jobs_category_length_check check (char_length(btrim(category)) between 2 and 80) not valid;
  end if;
  if not exists (select 1 from pg_constraint where conname = 'jobs_experience_level_check' and conrelid = 'public.jobs'::regclass) then
    alter table public.jobs add constraint jobs_experience_level_check check (experience_level in ('Beginner', 'Intermediate', 'Expert')) not valid;
  end if;
  if not exists (select 1 from pg_constraint where conname = 'jobs_duration_days_check' and conrelid = 'public.jobs'::regclass) then
    alter table public.jobs add constraint jobs_duration_days_check check (duration_days between 1 and 3650) not valid;
  end if;
  if not exists (select 1 from pg_constraint where conname = 'jobs_freelancers_needed_check' and conrelid = 'public.jobs'::regclass) then
    alter table public.jobs add constraint jobs_freelancers_needed_check check (freelancers_needed between 1 and 100) not valid;
  end if;
end $$;

alter table public.jobs validate constraint jobs_category_length_check;
alter table public.jobs validate constraint jobs_experience_level_check;
alter table public.jobs validate constraint jobs_duration_days_check;
alter table public.jobs validate constraint jobs_freelancers_needed_check;

create index if not exists jobs_category_status_created_idx
  on public.jobs (category, status, created_at desc);

comment on column public.jobs.category is 'Marketplace category selected by the client when publishing the job.';
comment on column public.jobs.experience_level is 'Required freelancer experience level: Beginner, Intermediate, or Expert.';
comment on column public.jobs.duration_days is 'Number of days allowed to complete the project.';
comment on column public.jobs.freelancers_needed is 'Number of freelancer positions requested by the client.';