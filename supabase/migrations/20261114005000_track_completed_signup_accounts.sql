alter table public.profiles
  add column if not exists signup_completed_at timestamptz;

comment on column public.profiles.signup_completed_at is
  'Set only after the final mandatory signup page has completed successfully; the canonical inclusion flag for owner user counts.';

create index if not exists profiles_signup_completed_at_idx
  on public.profiles (signup_completed_at desc)
  where signup_completed_at is not null;

update public.profiles p
set signup_completed_at = p.created_at
from auth.users u
where u.id = p.id
  and p.signup_completed_at is null
  and u.email_confirmed_at is not null
  and nullif(btrim(p.display_name), '') is not null
  and nullif(btrim(p.country), '') is not null
  and nullif(btrim(p.mobile_number), '') is not null
  and nullif(btrim(p.gender), '') is not null
  and p.birthday is not null;
