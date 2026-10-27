-- Freelance Hub profile contact safeguards.
-- Apply in the Supabase SQL Editor. Existing historical timestamps are unknown,
-- so this starts enforcing six months from the first mobile change after migration.
alter table public.profiles
  add column if not exists mobile_number_changed_at timestamptz;

create or replace function public.enforce_profile_mobile_change_cooldown()
returns trigger
language plpgsql
as $$
begin
  -- This value is server-managed; ignore any client-supplied timestamp.
  new.mobile_number_changed_at := old.mobile_number_changed_at;

  if new.mobile_number is distinct from old.mobile_number then
    if old.mobile_number_changed_at is not null
       and now() < old.mobile_number_changed_at + interval '6 months' then
      raise exception 'Mobile number can only be changed once every six months'
        using errcode = 'P0001';
    end if;
    new.mobile_number_changed_at := now();
  end if;

  return new;
end;
$$;

drop trigger if exists profiles_mobile_change_cooldown on public.profiles;
create trigger profiles_mobile_change_cooldown
before update on public.profiles
for each row execute function public.enforce_profile_mobile_change_cooldown();

comment on column public.profiles.mobile_number_changed_at is
  'Server-managed timestamp used to enforce the six-month mobile number change cooldown.';
