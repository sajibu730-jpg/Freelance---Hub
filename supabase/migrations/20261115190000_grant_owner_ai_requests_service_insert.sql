-- Allow the authenticated Owner AI Edge Function's service-role client
-- to persist AI drafts. The function verifies the caller's JWT and active
-- owner record before inserting; browser roles remain unable to INSERT.
grant insert on table public.owner_ai_requests to service_role;

do $$
begin
  if not has_table_privilege('service_role', 'public.owner_ai_requests', 'INSERT') then
    raise exception 'service_role is missing INSERT on public.owner_ai_requests';
  end if;
  if has_table_privilege('authenticated', 'public.owner_ai_requests', 'INSERT') then
    raise exception 'authenticated must not have INSERT on public.owner_ai_requests';
  end if;
  if has_table_privilege('anon', 'public.owner_ai_requests', 'INSERT') then
    raise exception 'anon must not have INSERT on public.owner_ai_requests';
  end if;
end;
$$;
