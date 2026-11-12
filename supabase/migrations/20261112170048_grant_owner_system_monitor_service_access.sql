grant usage on schema public to service_role;
grant select on public.app_admins, public.owner_ai_requests, public.app_remote_config to service_role;
grant select, insert on public.owner_system_reports to service_role;
