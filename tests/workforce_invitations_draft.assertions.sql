-- Disposable PostgreSQL tests only. Never execute against Production.
-- Assumes the draft schema and RPC files have been applied by the CI workflow.

insert into auth.users (id, email, invited_at, email_confirmed_at) values
  ('66666666-6666-4666-8666-666666666666', 'new.staff@example.com', pg_catalog.now(), pg_catalog.now()),
  ('77777777-7777-4777-8777-777777777777', 'recovery.staff@example.com', pg_catalog.now(), null),
  ('88888888-8888-4888-8888-888888888888', 'unverified.staff@example.com', pg_catalog.now(), null)
on conflict (id) do nothing;

set role service_role;

do $$
declare
  v_invite jsonb;
  v_retry jsonb;
  v_invite_id uuid;
  v_cancel_id uuid;
  v_review_id uuid;
  v_claim jsonb;
  v_member jsonb;
  v_event_count integer;
begin
  -- Prepare is Owner-only, normalizes nothing silently, and is idempotent by request ID.
  v_invite := public.owner_workforce_prepare_invitation(
    '99999999-9999-4999-8999-999999999999',
    'new.staff@example.com',
    'developer',
    array['freelance-hub'],
    'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee1'
  );
  v_invite_id := (v_invite ->> 'id')::uuid;
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
  if (v_retry ->> 'id')::uuid <> v_invite_id then
    raise exception 'Matching invitation retry created a duplicate.';
  end if;

  -- A different payload cannot reuse the same idempotency key.
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

  -- Only one concurrent caller can claim prepared -> dispatching.
  v_claim := public.owner_workforce_claim_invitation_dispatch(
    '99999999-9999-4999-8999-999999999999', v_invite_id
  );
  if (v_claim ->> 'claimed')::boolean is not true then
    raise exception 'First invitation dispatch claim failed.';
  end if;
  v_claim := public.owner_workforce_claim_invitation_dispatch(
    '99999999-9999-4999-8999-999999999999', v_invite_id
  );
  if (v_claim ->> 'claimed')::boolean is not false then
    raise exception 'Invitation dispatch claim was not one-time.';
  end if;

  -- Auth user is created by the Auth Admin API outside this SQL transaction.
  v_invite := public.owner_workforce_record_invitation_sent(
    '99999999-9999-4999-8999-999999999999',
    v_invite_id,
    '66666666-6666-4666-8666-666666666666'
  );
  if v_invite ->> 'status' <> 'sent' then
    raise exception 'Invitation was not recorded as sent.';
  end if;
  v_retry := public.owner_workforce_record_invitation_sent(
    '99999999-9999-4999-8999-999999999999',
    v_invite_id,
    '66666666-6666-4666-8666-666666666666'
  );
  if v_retry ->> 'status' <> 'sent' then
    raise exception 'Recording the same successful Auth invite was not idempotent.';
  end if;

  -- The Auth user is already verified in this isolated fixture.
  v_member := public.owner_workforce_accept_invitation(
    '66666666-6666-4666-8666-666666666666', v_invite_id
  );
  if v_member ->> 'role' <> 'developer'
     or v_member ->> 'status' <> 'active'
     or (v_member ->> 'user_id')::uuid <> '66666666-6666-4666-8666-666666666666' then
    raise exception 'Verified invite acceptance did not create the expected membership.';
  end if;

  -- A retry is idempotent and does not duplicate the membership.
  v_member := public.owner_workforce_accept_invitation(
    '66666666-6666-4666-8666-666666666666', v_invite_id
  );
  if v_member ->> 'role' <> 'developer' then
    raise exception 'Invitation acceptance retry failed.';
  end if;

  -- Auth API/network ambiguity is quarantined; cancellation does not delete Auth users.
  v_invite := public.owner_workforce_prepare_invitation(
    '99999999-9999-4999-8999-999999999999',
    'recovery.staff@example.com',
    'support',
    array['freelance-hub'],
    'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee2'
  );
  v_review_id := (v_invite ->> 'id')::uuid;
  perform public.owner_workforce_claim_invitation_dispatch(
    '99999999-9999-4999-8999-999999999999', v_review_id
  );
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

  -- Cancellation is idempotent and accepted membership cannot be cancelled as an invite.
  v_invite := public.owner_workforce_cancel_invitation(
    '99999999-9999-4999-8999-999999999999',
    v_review_id,
    'Repeat cancellation.'
  );
  if v_invite ->> 'status' <> 'cancelled' then
    raise exception 'Cancellation retry failed.';
  end if;

  begin
    perform public.owner_workforce_cancel_invitation(
      '99999999-9999-4999-8999-999999999999',
      v_invite_id,
      'Cannot cancel accepted membership.'
    );
    raise exception 'Accepted invitation was cancelled instead of requiring member suspension.';
  exception when invalid_parameter_value then
    null;
  end;

  -- An unverified email cannot activate workforce access.
  v_invite := public.owner_workforce_prepare_invitation(
    '99999999-9999-4999-8999-999999999999',
    'unverified.staff@example.com',
    'qa',
    array['freelance-hub'],
    'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee3'
  );
  v_cancel_id := (v_invite ->> 'id')::uuid;
  perform public.owner_workforce_claim_invitation_dispatch(
    '99999999-9999-4999-8999-999999999999', v_cancel_id
  );
  perform public.owner_workforce_record_invitation_sent(
    '99999999-9999-4999-8999-999999999999',
    v_cancel_id,
    '88888888-8888-4888-8888-888888888888'
  );
  begin
    perform public.owner_workforce_accept_invitation(
      '88888888-8888-4888-8888-888888888888', v_cancel_id
    );
    raise exception 'Unverified invited user was accepted.';
  exception when insufficient_privilege then
    null;
  end;
  perform public.owner_workforce_cancel_invitation(
    '99999999-9999-4999-8999-999999999999',
    v_cancel_id,
    'Unverified test invitation closed.'
  );

  if jsonb_array_length(public.owner_workforce_list_invitations(
      '99999999-9999-4999-8999-999999999999', 100
    )) <> 3 then
    raise exception 'Owner invitation list did not return all three test invitations.';
  end if;

  -- A non-Owner cannot create invitations.
  begin
    perform public.owner_workforce_prepare_invitation(
      '22222222-2222-4222-8222-222222222222',
      'forbidden@example.com',
      'support',
      array['freelance-hub'],
      'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee3'
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
