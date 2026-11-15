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
    request("POST", worker_url,
            headers={**headers(SERVICE, SERVICE), "x-work-evidence-cleanup-secret": "wrong"},
            body={}, expected=(401,))
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

    print("LOCAL SUPABASE INTEGRATION PASS: Auth, TOTP/AAL2, Owner review/events, Edge JWT, private Storage, signed links, cleanup worker")


if __name__ == "__main__":
    main()
