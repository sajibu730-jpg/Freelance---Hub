-- Disposable PostgreSQL tests only. Never execute against Production.
-- Assumes the draft schema and RPC files have been applied by the CI workflow.

-- Reserve stable Auth UUIDs without emails. The email is attached only after the
-- invitation intent is durably prepared, simulating Auth Admin's external side effect.
insert into auth.users (id) values
  ('66666666-6666-4666-8666-666666666666'),
  ('77777777-7777-4777-8777-777777777777'),
  ('88888888-8888-4888-8888-888888888888')
on conflict (id) do nothing;

set role service_role;

do $$
declare
  v_invite jsonb;
  v_retry jsonb;
  v_id uuid;
begin
  -- Prepare invitations before the Auth Admin side effect exists.
  v_invite := public.owner_workforce_prepare_invitation(
    '99999999-9999-4999-8999-999999999999',
    'new.staff@example.com',
    'developer',
    array['freelance-hub'],
    'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee1'
  );
  v_id := (v_invite ->> 'id')::uuid;
  if v_invite ->> 'status' <> 'prepared' then
    raise exception 'New invitation should be prepared.';
  end if;

  v_retry := public.owner_workforce_prepare_invitation(
    '99999999-9999-4999-8999-999999999999',
    'new.staff@example.com',
    'developer',
    array['freelance-hub'],
    'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee1'
  );
  if (v_retry ->> 'id')::uuid <> v_id then
    raise exception 'Matching invitation retry created a duplicate.';
  end if;

  begin
    perform public.owner_workforce_prepare_invitation(
      '99999999-9999-4999-8999-999999999999',
      'new.staff@example.com',
      'qa',
      array['freelance-hub'],
      'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee1'
    );
    raise exception 'Invitation request key was reused with different data.';
  exception when unique_violation then
    null;
  end;

  -- A second and third invitation exercise ambiguous and unverified outcomes.
  v_invite := public.owner_workforce_prepare_invitation(
    '99999999-9999-4999-8999-999999999999',
    'recovery.staff@example.com',
    'support',
    array['freelance-hub'],
    'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee2'
  );
  perform public.owner_workforce_prepare_invitation(
    '99999999-9999-4999-8999-999999999999',
    'unverified.staff@example.com',
    'qa',
    array['freelance-hub'],
    'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee3'
  );

  -- Each prepared invite has exactly one successful dispatch claim.
  for v_id in
    select i.id from public.owner_workforce_invitations i
    where i.email in ('new.staff@example.com', 'recovery.staff@example.com', 'unverified.staff@example.com')
  loop
    v_invite := public.owner_workforce_claim_invitation_dispatch(
      '99999999-9999-4999-8999-999999999999', v_id
    );
    if (v_invite ->> 'claimed')::boolean is not true then
      raise exception 'First invitation dispatch claim failed.';
    end if;
    v_invite := public.owner_workforce_claim_invitation_dispatch(
      '99999999-9999-4999-8999-999999999999', v_id
    );
    if (v_invite ->> 'claimed')::boolean is not false then
      raise exception 'Invitation dispatch claim was not one-time.';
    end if;
  end loop;
end $$;

reset role;

-- Simulate Auth Admin creating invited Auth users after the DB intent and claim.
update auth.users
set email = 'new.staff@example.com', invited_at = pg_catalog.now(),
    email_confirmed_at = pg_catalog.now()
where id = '66666666-6666-4666-8666-666666666666';

update auth.users
set email = 'recovery.staff@example.com', invited_at = pg_catalog.now()
where id = '77777777-7777-4777-8777-777777777777';

update auth.users
set email = 'unverified.staff@example.com', invited_at = pg_catalog.now()
where id = '88888888-8888-4888-8888-888888888888';

set role service_role;

do $$
declare
  v_invite jsonb;
  v_retry jsonb;
  v_member jsonb;
  v_primary_id uuid;
  v_review_id uuid;
  v_unverified_id uuid;
begin
  select id into v_primary_id from public.owner_workforce_invitations
  where email = 'new.staff@example.com';
  select id into v_review_id from public.owner_workforce_invitations
  where email = 'recovery.staff@example.com';
  select id into v_unverified_id from public.owner_workforce_invitations
  where email = 'unverified.staff@example.com';

  v_invite := public.owner_workforce_record_invitation_sent(
    '99999999-9999-4999-8999-999999999999',
    v_primary_id,
    '66666666-6666-4666-8666-666666666666'
  );
  if v_invite ->> 'status' <> 'sent' then
    raise exception 'Invitation was not recorded as sent.';
  end if;
  v_retry := public.owner_workforce_record_invitation_sent(
    '99999999-9999-4999-8999-999999999999',
    v_primary_id,
    '66666666-6666-4666-8666-666666666666'
  );
  if v_retry ->> 'status' <> 'sent' then
    raise exception 'Recording the same successful Auth invite was not idempotent.';
  end if;

  -- Verified Auth email is required before workforce membership is activated.
  v_member := public.owner_workforce_accept_invitation(
    '66666666-6666-4666-8666-666666666666', v_primary_id
  );
  if v_member ->> 'role' <> 'developer'
     or v_member ->> 'status' <> 'active'
     or (v_member ->> 'user_id')::uuid <> '66666666-6666-4666-8666-666666666666' then
    raise exception 'Verified invite acceptance did not create the expected membership.';
  end if;
  v_member := public.owner_workforce_accept_invitation(
    '66666666-6666-4666-8666-666666666666', v_primary_id
  );
  if v_member ->> 'role' <> 'developer' then
    raise exception 'Invitation acceptance retry failed.';
  end if;

  -- Auth/network ambiguity is quarantined; cancellation never deletes Auth accounts.
  v_invite := public.owner_workforce_mark_invitation_review(
    '99999999-9999-4999-8999-999999999999',
    v_review_id,
    'auth_exception',
    null
  );
  if v_invite ->> 'status' <> 'needs_review' then
    raise exception 'Ambiguous Auth result was not quarantined.';
  end if;
  v_invite := public.owner_workforce_cancel_invitation(
    '99999999-9999-4999-8999-999999999999',
    v_review_id,
    'Owner verified no workforce activation should proceed.'
  );
  if v_invite ->> 'status' <> 'cancelled' then
    raise exception 'Owner could not cancel an unresolved invitation.';
  end if;
  v_invite := public.owner_workforce_cancel_invitation(
    '99999999-9999-4999-8999-999999999999',
    v_review_id,
    'Repeat cancellation.'
  );
  if v_invite ->> 'status' <> 'cancelled' then
    raise exception 'Cancellation retry failed.';
  end if;

  -- An unverified Auth email cannot activate workforce access.
  perform public.owner_workforce_record_invitation_sent(
    '99999999-9999-4999-8999-999999999999',
    v_unverified_id,
    '88888888-8888-4888-8888-888888888888'
  );
  begin
    perform public.owner_workforce_accept_invitation(
      '88888888-8888-4888-8888-888888888888', v_unverified_id
    );
    raise exception 'Unverified invited user was accepted.';
  exception when insufficient_privilege then
    null;
  end;
  perform public.owner_workforce_cancel_invitation(
    '99999999-9999-4999-8999-999999999999',
    v_unverified_id,
    'Unverified test invitation closed.'
  );

  if jsonb_array_length(public.owner_workforce_list_invitations(
      '99999999-9999-4999-8999-999999999999', 100
    )) <> 3 then
    raise exception 'Owner invitation list did not return all three test invitations.';
  end if;

  -- Accepted membership cannot be cancelled as an invitation.
  begin
    perform public.owner_workforce_cancel_invitation(
      '99999999-9999-4999-8999-999999999999',
      v_primary_id,
      'Cannot cancel accepted membership.'
    );
    raise exception 'Accepted invitation was cancelled instead of requiring member suspension.';
  exception when invalid_parameter_value then
    null;
  end;

  -- A non-Owner cannot create invitations.
  begin
    perform public.owner_workforce_prepare_invitation(
      '22222222-2222-4222-8222-222222222222',
      'forbidden@example.com',
      'support',
      array['freelance-hub'],
      'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee4'
    );
    raise exception 'Non-Owner created a workforce invitation.';
  exception when insufficient_privilege then
    null;
  end;
end $$;

reset role;

do $$
declare
  v_count integer;
begin
  select count(*) into v_count
  from public.owner_workforce_invitation_events e
  where e.invitation_id in (
    select i.id from public.owner_workforce_invitations i
    where i.email in ('new.staff@example.com', 'recovery.staff@example.com', 'unverified.staff@example.com')
  );
  if v_count <> 12 then
    raise exception 'Expected 12 invitation audit events across three invitations, found %', v_count;
  end if;

  if has_table_privilege('service_role', 'public.owner_workforce_invitation_events', 'SELECT')
     or has_table_privilege('service_role', 'public.owner_workforce_invitation_events', 'INSERT') then
    raise exception 'Service role has direct invitation audit table access.';
  end if;
end $$;

select 'Recoverable workforce invitation disposable PostgreSQL tests passed.' as result;
