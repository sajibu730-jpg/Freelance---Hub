#!/usr/bin/env python3
"""Disposable local Supabase Auth/MFA/Storage/Edge Function integration tests.

All users, records, keys, and files are created only in the ephemeral CI stack.
"""
import base64
import hashlib
import hmac
import json
import os
import struct
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

API = os.environ["LOCAL_SUPABASE_URL"].rstrip("/")
ANON = os.environ["LOCAL_SUPABASE_ANON_KEY"]
SERVICE = os.environ["LOCAL_SUPABASE_SERVICE_ROLE_KEY"]
DB_URL = os.environ["LOCAL_SUPABASE_DB_URL"]
CLEANUP_SECRET = "ci-only-cleanup-secret-never-reuse"
FUNCTIONS = API + "/functions/v1"
AUTH = API + "/auth/v1"
STORAGE = API + "/storage/v1"
EMAIL = "evidence-owner@example.invalid"
PASSWORD = "Local-CI-Only-Password-2026!"
PDF = b"%PDF-1.7\nFreelance Hub local integration proof\n%%EOF\n"


def request(method, url, *, headers=None, body=None, expected=None):
    data = None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as exc:
        status, raw = exc.code, exc.read()
    if expected is not None and status not in expected:
        raise AssertionError(f"{method} {urlparse_path(url)} returned {status}; expected {expected}: {raw[:500]!r}")
    try:
        parsed = json.loads(raw.decode()) if raw else {}
    except (UnicodeDecodeError, json.JSONDecodeError):
        parsed = raw
    return status, parsed, raw


def urlparse_path(url):
    parsed = urllib.parse.urlsplit(url)
    return parsed.path


def headers(key, token=None, content_type="application/json"):
    result = {"apikey": key, "Content-Type": content_type}
    if token:
        result["Authorization"] = "Bearer " + token
    return result


def api_action(token, payload, expected=(200,)):
    status, parsed, _ = request(
        "POST", FUNCTIONS + "/work-evidence-api",
        headers=headers(ANON, token), body=payload, expected=expected,
    )
    return status, parsed


def totp(secret):
    key = base64.b32decode(secret.upper() + "=" * ((8 - len(secret) % 8) % 8))
    counter = int(time.time()) // 30
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    value = struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF
    return str(value % 1_000_000).zfill(6)


def psql(sql):
    return subprocess.run(
        ["psql", DB_URL, "-v", "ON_ERROR_STOP=1", "-At", "-c", sql],
        check=True, capture_output=True, text=True,
    ).stdout.strip()


def upload_signed(signed_url, token):
    parsed = urllib.parse.urlsplit(signed_url)
    url = API + parsed.path + (("?" + parsed.query) if parsed.query else "") if parsed.scheme else urllib.parse.urljoin(API + "/", signed_url)
    request(
        "PUT", url,
        headers=headers(ANON, token, "application/pdf"),
        body=PDF, expected=(200, 201),
    )


def sql_literal(value):
    quote = chr(39)
    return quote + str(value).replace(quote, quote + quote) + quote


def create_auth_user(email):
    _, created, _ = request(
        "POST", AUTH + "/admin/users",
        headers=headers(SERVICE, SERVICE),
        body={"email": email, "password": PASSWORD, "email_confirm": True},
        expected=(200, 201),
    )
    user = created.get("user", created)
    _, session, _ = request(
        "POST", AUTH + "/token?grant_type=password",
        headers=headers(ANON),
        body={"email": email, "password": PASSWORD},
        expected=(200,),
    )
    return user["id"], session["access_token"]

def assert_owner_endpoints_denied(access_token, reason):
    payloads = [
        {"action": "owner_list"},
        {"action": "owner_review", "evidence_id": str(uuid.uuid4()), "status": "verified"},
        {"action": "owner_download", "evidence_id": str(uuid.uuid4())},
        {"action": "owner_events", "evidence_id": str(uuid.uuid4())},
    ]
    for payload in payloads:
        status, _ = api_action(access_token, payload, expected=(403,))
        assert status == 403, reason + ": " + payload["action"] + " returned " + str(status)


def main():
    # Gateway-level JWT verification must reject both missing and malformed tokens.
    request("POST", FUNCTIONS + "/work-evidence-api",
            headers={"apikey": ANON, "Content-Type": "application/json"},
            body={"action": "my_evidence"}, expected=(401, 403))
    request("POST", FUNCTIONS + "/work-evidence-api",
            headers=headers(ANON, "not-a-valid-jwt"),
            body={"action": "my_evidence"}, expected=(401, 403))
    print("PASS: Edge Function gateway rejects missing and malformed JWTs")

    # Create an isolated synthetic Owner through local Auth Admin API.
    _, created, _ = request(
        "POST", AUTH + "/admin/users",
        headers=headers(SERVICE, SERVICE),
        body={"email": EMAIL, "password": PASSWORD, "email_confirm": True},
        expected=(200, 201),
    )
    owner = created.get("user", created)
    owner_id = owner["id"]
    psql(
        "INSERT INTO public.app_admins(user_id, role, is_active) "
        f"VALUES ('{owner_id}', 'owner', true); "
        "INSERT INTO public.profiles(id, display_name, country) "
        f"VALUES ('{owner_id}', 'CI Owner', 'Test');"
    )

    _, session, _ = request(
        "POST", AUTH + "/token?grant_type=password",
        headers=headers(ANON),
        body={"email": EMAIL, "password": PASSWORD},
        expected=(200,),
    )
    aal1 = session["access_token"]
    status, denied = api_action(aal1, {"action": "owner_list"}, expected=(403,))
    assert status == 403, f"Owner without TOTP was not blocked: {denied}"
    print("PASS: Owner review is denied before TOTP enrollment")

    # Enroll and verify a real local Auth TOTP factor, then use the AAL2 token.
    _, factor, _ = request(
        "POST", AUTH + "/factors",
        headers=headers(ANON, aal1),
        body={"factor_type": "totp", "friendly_name": "CI Owner TOTP"},
        expected=(200, 201),
    )
    factor_id = factor["id"]
    # Enrollment alone is not verification: a pending/unverified TOTP factor
    # must not unlock any privileged Owner endpoint.
    assert_owner_endpoints_denied(aal1, "Unverified TOTP enrollment must not unlock Owner API access")
    print("PASS: all four privileged Owner endpoints reject an unverified TOTP enrollment")
    secret = factor["totp"]["secret"]
    _, challenge, _ = request(
        "POST", AUTH + f"/factors/{factor_id}/challenge",
        headers=headers(ANON, aal1),
        body={},
        expected=(200, 201),
    )
    _, verified, _ = request(
        "POST", AUTH + f"/factors/{factor_id}/verify",
        headers=headers(ANON, aal1),
        body={"challenge_id": challenge["id"], "code": totp(secret)},
        expected=(200,),
    )
    aal2 = verified["access_token"]
    status, owner_list = api_action(aal2, {"action": "owner_list"})
    assert status == 200 and owner_list.get("success") is True, owner_list
    print("PASS: verified Auth TOTP upgrades Owner session to AAL2 and permits Owner list")

    # Upload a real PDF to the private local Storage bucket through the API-issued URL.
    upload_request_id = str(uuid.uuid4())
    _, prepared = api_action(aal2, {
        "action": "prepare_upload",
        "request_id": upload_request_id,
        "origin": "off_app",
        "title": "Local Storage integration proof",
        "description": "Disposable CI PDF upload",
        "file_name": "local-proof.pdf",
        "mime_type": "application/pdf",
        "byte_size": len(PDF),
    })
    evidence_id = prepared["evidence"]["id"]
    evidence_path = prepared["path"]
    upload_signed(prepared["signed_upload_url"], aal2)
    _, completed = api_action(aal2, {
        "action": "complete_upload", "evidence_id": evidence_id,
    })
    assert completed.get("evidence", {}).get("status") == "submitted", completed
    print("PASS: signed private upload and server-side size/signature verification")

    # Owner download URL must work and be temporary; direct unauthenticated object access must fail.
    _, download = api_action(aal2, {
        "action": "owner_download", "evidence_id": evidence_id,
    })
    assert download.get("expires_in_seconds") == 300, download
    parsed_download = urllib.parse.urlsplit(download["signed_download_url"])
    signed_download = API + parsed_download.path + (("?" + parsed_download.query) if parsed_download.query else "") if parsed_download.scheme else urllib.parse.urljoin(API + "/", download["signed_download_url"])
    _, _, downloaded = request("GET", signed_download, expected=(200,))
    assert downloaded == PDF, "Signed download did not return the original bytes"
    direct_url = STORAGE + "/object/work-evidence-private/" + evidence_path
    request("GET", direct_url, headers={"apikey": ANON}, expected=(400, 401, 403, 404))
    print("PASS: Owner signed download works while direct private-object access is denied")

    # Exercise Owner event timeline and review state machine end to end.
    _, note_result = api_action(aal2, {
        "action": "submit_note",
        "request_id": str(uuid.uuid4()),
        "origin": "off_app",
        "title": "Local Owner review workflow",
        "description": "Synthetic note for Owner review and audit timeline tests.",
    })
    review_id = note_result["evidence"]["id"]

    # Every privileged Owner endpoint must independently reject non-Owners,
    # ambiguous multiple-Owner configuration, and an inactive primary Owner.
    owner_endpoint_payloads = [
        {"action": "owner_list"},
        {"action": "owner_review", "evidence_id": review_id, "status": "verified"},
        {"action": "owner_download", "evidence_id": evidence_id},
        {"action": "owner_events", "evidence_id": review_id},
    ]

    # A valid Owner role alone is insufficient: every privileged endpoint
    # must reject the pre-MFA AAL1 session even after the TOTP factor exists.
    assert_owner_endpoints_denied(aal1, "Owner AAL1 session must be denied")
    print("PASS: all four privileged Owner endpoints require an AAL2 session")

    non_owner_id, non_owner_access = create_auth_user("non-owner@example.invalid")
    assert_owner_endpoints_denied(non_owner_access, "Non-Owner access must be denied")
    print("PASS: all four privileged Owner endpoints reject a normal authenticated user")

    second_owner_id, _ = create_auth_user("second-owner@example.invalid")
    psql(
        "INSERT INTO public.app_admins(user_id, role, is_active) "
        f"VALUES ('{second_owner_id}', 'owner', true);"
    )
    assert_owner_endpoints_denied(aal2, "Multiple active Owners must fail closed")
    psql(
        "UPDATE public.app_admins SET is_active=false "
        f"WHERE user_id='{second_owner_id}' AND role='owner';"
    )
    print("PASS: all four privileged Owner endpoints fail closed when multiple active Owners exist")

    psql(
        "UPDATE public.app_admins SET is_active=false "
        f"WHERE user_id='{owner_id}' AND role='owner';"
    )
    assert_owner_endpoints_denied(aal2, "Inactive primary Owner must be denied")
    psql(
        "UPDATE public.app_admins SET is_active=true "
        f"WHERE user_id='{owner_id}' AND role='owner';"
    )
    print("PASS: all four privileged Owner endpoints reject an inactive primary Owner")

    _, initial_events = api_action(aal2, {"action": "owner_events", "evidence_id": review_id})
    assert initial_events.get("events"), "Initial submission audit event is missing"
    api_action(aal2, {
        "action": "owner_review", "evidence_id": review_id, "status": "needs_info",
    }, expected=(400,))
    _, needs_info = api_action(aal2, {
        "action": "owner_review", "evidence_id": review_id,
        "status": "needs_info", "review_note": "Please provide one more detail.",
    })
    assert needs_info.get("evidence", {}).get("status") == "needs_info", needs_info
    _, verified_review = api_action(aal2, {
        "action": "owner_review", "evidence_id": review_id, "status": "verified",
    })
    assert verified_review.get("evidence", {}).get("status") == "verified", verified_review
    _, final_events = api_action(aal2, {"action": "owner_events", "evidence_id": review_id})
    events = final_events.get("events", [])
    event_types = [event.get("event_type") for event in events]
    target_statuses = [event.get("to_status") for event in events]
    assert len(events) >= 3, final_events
    assert event_types.count("reviewed") >= 2, event_types
    assert "needs_info" in target_statuses and "verified" in target_statuses, target_statuses
    api_action(aal2, {
        "action": "owner_review", "evidence_id": review_id,
        "status": "rejected", "review_note": "Terminal state must not be overwritten.",
    }, expected=(409,))
    print("PASS: Owner events, required review notes, review transitions, and terminal-state lock")

    # Verify real freelancer/client relationship checks against local Auth and DB rows.
    freelancer_id, freelancer_access = create_auth_user("freelancer@example.invalid")
    client_id, client_access = create_auth_user("client@example.invalid")
    outsider_id, outsider_access = create_auth_user("outsider@example.invalid")
    other_freelancer_id, other_freelancer_access = create_auth_user("other-freelancer@example.invalid")
    job_one, job_two, job_three = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    contract_one, contract_two = str(uuid.uuid4()), str(uuid.uuid4())
    submission_one, submission_no_contract, submission_mismatch = (str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4()))
    setup_sql = " ".join([
        "INSERT INTO public.jobs(id, owner_id) VALUES (" + sql_literal(job_one) + ", " + sql_literal(client_id) + "), (" + sql_literal(job_two) + ", " + sql_literal(client_id) + "), (" + sql_literal(job_three) + ", " + sql_literal(client_id) + ");",
        "INSERT INTO public.contracts(id, job_id, client_id, freelancer_id) VALUES (" + sql_literal(contract_one) + ", " + sql_literal(job_one) + ", " + sql_literal(client_id) + ", " + sql_literal(freelancer_id) + "), (" + sql_literal(contract_two) + ", " + sql_literal(job_two) + ", " + sql_literal(client_id) + ", " + sql_literal(other_freelancer_id) + ");",
        "INSERT INTO public.work_submissions(id, job_id, contract_id, freelancer_id) VALUES (" + sql_literal(submission_one) + ", " + sql_literal(job_one) + ", " + sql_literal(contract_one) + ", " + sql_literal(freelancer_id) + "), (" + sql_literal(submission_no_contract) + ", " + sql_literal(job_two) + ", NULL, " + sql_literal(freelancer_id) + "), (" + sql_literal(submission_mismatch) + ", " + sql_literal(job_three) + ", " + sql_literal(contract_one) + ", " + sql_literal(other_freelancer_id) + ");",
        "INSERT INTO public.proposals(id, job_id, freelancer_id) VALUES (" + sql_literal(str(uuid.uuid4())) + ", " + sql_literal(job_two) + ", " + sql_literal(outsider_id) + ");",
    ])
    psql(setup_sql)
    _, freelancer_evidence = api_action(freelancer_access, {
        "action": "submit_note", "request_id": str(uuid.uuid4()), "origin": "in_app",
        "title": "Freelancer own submission", "description": "Valid participant evidence.",
        "submission_id": submission_one,
    })
    assert freelancer_evidence.get("evidence", {}).get("submission_id") == submission_one, freelancer_evidence
    _, client_evidence = api_action(client_access, {
        "action": "submit_note", "request_id": str(uuid.uuid4()), "origin": "in_app",
        "title": "Client contract evidence", "description": "Valid client participant evidence.",
        "contract_id": contract_one,
    })
    assert client_evidence.get("evidence", {}).get("contract_id") == contract_one, client_evidence
    api_action(outsider_access, {
        "action": "submit_note", "request_id": str(uuid.uuid4()), "origin": "in_app",
        "title": "Unrelated contract attempt", "description": "Must be denied.",
        "contract_id": contract_one,
    }, expected=(403,))
    api_action(outsider_access, {
        "action": "submit_note", "request_id": str(uuid.uuid4()), "origin": "in_app",
        "title": "Proposal is not submission ownership", "description": "Must be denied.",
        "job_id": job_two, "submission_id": submission_no_contract,
    }, expected=(403,))
    api_action(freelancer_access, {
        "action": "submit_note", "request_id": str(uuid.uuid4()), "origin": "in_app",
        "title": "Mismatched job", "description": "Must be rejected.",
        "job_id": job_two, "submission_id": submission_one,
    }, expected=(400,))
    api_action(freelancer_access, {
        "action": "submit_note", "request_id": str(uuid.uuid4()), "origin": "in_app",
        "title": "Mismatched contract", "description": "Must be rejected.",
        "contract_id": contract_two, "submission_id": submission_one,
    }, expected=(400,))
    api_action(other_freelancer_access, {
        "action": "submit_note", "request_id": str(uuid.uuid4()), "origin": "in_app",
        "title": "Wrong freelancer on contract", "description": "Must be denied.",
        "contract_id": contract_one, "submission_id": submission_mismatch,
    }, expected=(403,))
    print("PASS: freelancer/client participation, unrelated-user denial, proposal boundary, and mismatched references")

    # Expiring stale pending uploads is bounded by the requested cleanup batch size.
    # This prevents one worker invocation from locking/auditing an unbounded backlog.
    batch_title = "Cleanup batch boundary " + owner_id
    psql(
        "WITH generated AS ("
        "  SELECT gen_random_uuid() AS evidence_id FROM generate_series(1, 15)"
        ") "
        "INSERT INTO public.work_evidence ("
        "  id, request_id, submitted_by, origin, evidence_type, title, storage_path, "
        "  original_filename, mime_type, byte_size, status, created_at"
        ") "
        "SELECT evidence_id, gen_random_uuid(), " + sql_literal(owner_id) + ", "
        "  'off_app', 'file', " + sql_literal(batch_title) + ", "
        "  " + sql_literal(owner_id + "/") + " || evidence_id::text || '/proof', "
        "  'expired.pdf', 'application/pdf', 1024, 'upload_pending', "
        "  now() - interval '25 hours' "
        "FROM generated;"
    )
    first_batch = psql("SELECT count(*) FROM public.claim_work_evidence_cleanup_batch(10);")
    assert first_batch == "10", "Expected exactly 10 expired uploads in the first batch: " + first_batch
    expired_after_first = psql(
        "SELECT count(*) FROM public.work_evidence "
        "WHERE title = " + sql_literal(batch_title) + " AND status = 'upload_failed';"
    )
    pending_after_first = psql(
        "SELECT count(*) FROM public.work_evidence "
        "WHERE title = " + sql_literal(batch_title) + " AND status = 'upload_pending';"
    )
    assert expired_after_first == "10" and pending_after_first == "5", (
        "Expired-upload transition exceeded its batch limit: "
        + expired_after_first + " expired, " + pending_after_first + " still pending"
    )
    finalized_first = psql(
        "WITH claimed AS ("
        "  SELECT q.evidence_id, q.lease_token "
        "  FROM public.work_evidence_cleanup_queue q "
        "  JOIN public.work_evidence e ON e.id = q.evidence_id "
        "  WHERE e.title = " + sql_literal(batch_title) + " AND q.state = 'processing'"
        ") "
        "SELECT count(*) FROM claimed "
        "WHERE public.finish_work_evidence_cleanup(evidence_id, lease_token, true, null);"
    )
    assert finalized_first == "10", "Could not finalize first bounded cleanup batch: " + finalized_first
    second_batch = psql("SELECT count(*) FROM public.claim_work_evidence_cleanup_batch(10);")
    assert second_batch == "5", "Expected the remaining 5 expired uploads in the second batch: " + second_batch
    finalized_second = psql(
        "WITH claimed AS ("
        "  SELECT q.evidence_id, q.lease_token "
        "  FROM public.work_evidence_cleanup_queue q "
        "  JOIN public.work_evidence e ON e.id = q.evidence_id "
        "  WHERE e.title = " + sql_literal(batch_title) + " AND q.state = 'processing'"
        ") "
        "SELECT count(*) FROM claimed "
        "WHERE public.finish_work_evidence_cleanup(evidence_id, lease_token, true, null);"
    )
    assert finalized_second == "5", "Could not finalize second bounded cleanup batch: " + finalized_second
    batch_completed = psql(
        "SELECT count(*) FROM public.work_evidence_cleanup_queue q "
        "JOIN public.work_evidence e ON e.id = q.evidence_id "
        "WHERE e.title = " + sql_literal(batch_title) + " AND q.state = 'completed';"
    )
    assert batch_completed == "15", "Expired cleanup backlog was not fully processed: " + batch_completed
    print("PASS: stale pending-upload expiration is bounded and drains across multiple batches")

    # Exercise the cleanup worker against an actual uploaded private object.
    cleanup_request_id = str(uuid.uuid4())
    _, cleanup_prepared = api_action(aal2, {
        "action": "prepare_upload",
        "request_id": cleanup_request_id,
        "origin": "off_app",
        "title": "Cleanup worker integration proof",
        "description": "Disposable CI object to be removed",
        "file_name": "cleanup-proof.pdf",
        "mime_type": "application/pdf",
        "byte_size": len(PDF),
    })
    cleanup_id = cleanup_prepared["evidence"]["id"]
    cleanup_path = cleanup_prepared["path"]
    upload_signed(cleanup_prepared["signed_upload_url"], aal2)
    psql(
        "UPDATE public.work_evidence SET status='upload_failed', submitted_at=now() "
        f"WHERE id='{cleanup_id}' AND status='upload_pending';"
    )
    queue_state = psql(
        "SELECT state FROM public.work_evidence_cleanup_queue "
        f"WHERE evidence_id='{cleanup_id}';"
    )
    assert queue_state == "pending", f"Expected durable cleanup queue row, got {queue_state!r}"

    worker_url = FUNCTIONS + "/work-evidence-cleanup"
    # The gateway must reject missing/invalid JWTs before the worker runs.
    request("POST", worker_url,
            headers={"apikey": ANON, "Content-Type": "application/json",
                     "x-work-evidence-cleanup-secret": CLEANUP_SECRET},
            body={}, expected=(401, 403))
    request("POST", worker_url,
            headers={**headers(SERVICE, "not-a-valid-jwt"),
                     "x-work-evidence-cleanup-secret": CLEANUP_SECRET},
            body={}, expected=(401, 403))
    # A valid but insufficient anon JWT must not satisfy the service-role check.
    request("POST", worker_url,
            headers={**headers(SERVICE, ANON),
                     "x-work-evidence-cleanup-secret": CLEANUP_SECRET},
            body={}, expected=(401,))
    request("POST", worker_url,
            headers={**headers(SERVICE, SERVICE), "x-work-evidence-cleanup-secret": ""},
            body={}, expected=(401,))
    request("POST", worker_url,
            headers={**headers(SERVICE, SERVICE), "x-work-evidence-cleanup-secret": "wrong"},
            body={}, expected=(401,))
    request("GET", worker_url,
            headers={**headers(SERVICE, SERVICE),
                     "x-work-evidence-cleanup-secret": CLEANUP_SECRET},
            expected=(405,))

    # A corrupted queue path must never let one evidence job delete another
    # evidence record's valid proof object. Restore the correct path afterward
    # so the same integration fixture can verify successful cleanup as well.
    psql(
        "UPDATE public.work_evidence_cleanup_queue SET storage_path = "
        + sql_literal(evidence_path) + " WHERE evidence_id = " + sql_literal(cleanup_id) + ";"
    )
    _, tamper_result, _ = request(
        "POST", worker_url,
        headers={**headers(SERVICE, SERVICE), "x-work-evidence-cleanup-secret": CLEANUP_SECRET},
        body={}, expected=(200,),
    )
    assert tamper_result.get("completed") == 0 and tamper_result.get("retried") == 1, tamper_result
    sentinel_parent = "/".join(evidence_path.split("/")[:2])
    _, sentinel_objects, _ = request(
        "POST", STORAGE + "/object/list/work-evidence-private",
        headers=headers(SERVICE, SERVICE),
        body={"prefix": sentinel_parent, "limit": 10},
        expected=(200,),
    )
    assert any(item.get("name") == "proof" for item in sentinel_objects), (
        "Cleanup worker removed an unrelated evidence object's proof"
    )
    psql(
        "UPDATE public.work_evidence_cleanup_queue SET storage_path = "
        + sql_literal(cleanup_path) + ", next_attempt_at = now() "
        "WHERE evidence_id = " + sql_literal(cleanup_id) + " AND state = 'pending';"
    )
    print("PASS: cleanup worker refuses a queue path that targets another evidence object's proof")

    _, cleanup_result, _ = request(
        "POST", worker_url,
        headers={**headers(SERVICE, SERVICE), "x-work-evidence-cleanup-secret": CLEANUP_SECRET},
        body={}, expected=(200,),
    )
    assert cleanup_result.get("completed") == 1 and cleanup_result.get("removed") == 1, cleanup_result
    final_state = psql(
        "SELECT state FROM public.work_evidence_cleanup_queue "
        f"WHERE evidence_id='{cleanup_id}';"
    )
    assert final_state == "completed", f"Cleanup queue not completed: {final_state!r}"
    _, remaining, _ = request(
        "POST", STORAGE + "/object/list/work-evidence-private",
        headers=headers(SERVICE, SERVICE),
        body={"prefix": f"{owner_id}/{cleanup_id}", "limit": 10},
        expected=(200,),
    )
    assert not any(item.get("name") == "proof" for item in remaining), remaining
    request("GET", direct_url.replace(evidence_path, cleanup_path),
            headers={"apikey": ANON}, expected=(400, 401, 403, 404))
    print("PASS: cleanup worker enforces secret, removes exact private object, and finalizes queue")

    # A deleted Auth identity must not continue using its previously issued JWT.
    revoked_user_id, revoked_access = create_auth_user("revoked-session@example.invalid")
    request(
        "DELETE", AUTH + f"/admin/users/{revoked_user_id}",
        headers=headers(SERVICE, SERVICE),
        expected=(200, 204),
    )
    api_action(revoked_access, {"action": "my_evidence"}, expected=(401,))
    print("PASS: previously issued JWT is rejected after its Auth user is deleted")

    # A previously issued AAL2 token must not retain Owner access after its
    # verified TOTP factor is removed. Check every privileged endpoint using
    # the same token so the server must validate both current factor state and AAL.
    request(
        "DELETE", AUTH + f"/factors/{factor_id}",
        headers=headers(ANON, aal2),
        expected=(200, 204),
    )
    assert_owner_endpoints_denied(aal2, "Deleted TOTP factor must revoke Owner API access")
    print("PASS: all four privileged Owner endpoints reject a stale AAL2 session after TOTP factor removal")

    print("LOCAL SUPABASE INTEGRATION PASS: Auth, TOTP/AAL2, Owner review/events, participant relationships, Edge JWT, private Storage, signed links, cleanup worker")


if __name__ == "__main__":
    main()
