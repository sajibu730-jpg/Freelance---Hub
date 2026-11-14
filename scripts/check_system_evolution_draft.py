from pathlib import Path

html = Path("owner-dashboard/index.html").read_text(encoding="utf-8")
workflow = Path(".github/workflows/deploy-owner-dashboard.yml").read_text(encoding="utf-8")
backend_draft = Path("docs/drafts/system-evolution-backend-release-approval.draft.md").read_text(encoding="utf-8")

required = [
    'data-tab="evolution"',
    'id="panel-evolution"',
    'System Evolution Center',
    'Production safety lock',
    'evolution-save-draft" disabled',
    'evolution-submit" disabled',
    'Approve exact release candidate</button>',
    'Request verified rollback</button>',
    'verified AAL2 session',
    'Any new commit invalidates prior approval',
    'No Production action can be started here',
]
missing = [item for item in required if item not in html]
if missing:
    raise SystemExit("Missing System Evolution safety markers: " + ", ".join(missing))

# The UI must keep all state-changing controls inert until a separately reviewed
# backend and activation design exists.
for control_id in ("evolution-save-draft", "evolution-submit"):
    import re
    match = re.search(r'<button[^>]*id="' + re.escape(control_id) + r'"[^>]*>', html)
    if not match or "disabled" not in match.group(0):
        raise SystemExit(f"{control_id} must remain disabled")

if "branches: [main]" not in workflow or "pull_request:" in workflow:
    raise SystemExit("Production Pages deployment must remain main-only, never PR-triggered")

backend_required = [
    "browser dashboard is an untrusted client",
    "require a current AAL2 session",
    "approved_artifact_sha256",
    "evolution_release_events",
    "compare-and-set",
    "deny-by-default",
    "No production adapter is part of this design",
    "creates no tables, policies, functions, triggers, secrets",
]
missing_backend = [item for item in backend_required if item not in backend_draft]
if missing_backend:
    raise SystemExit("Missing backend/release safety requirements: " + ", ".join(missing_backend))

print("System Evolution UI and backend/release design safety checks passed; all release controls remain disabled.")
