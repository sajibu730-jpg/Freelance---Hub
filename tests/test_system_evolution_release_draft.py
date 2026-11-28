import unittest
from pathlib import Path

ROOT = Path(__file__).parents[1]
DRAFT = ROOT / "docs/drafts/system-evolution-backend-release-approval.draft.md"
TEXT = DRAFT.read_text(encoding="utf-8")


class SystemEvolutionReleaseDraftTests(unittest.TestCase):
    def test_document_is_design_only(self):
        self.assertIn("Status: design-only", TEXT)
        self.assertIn("creates no tables, policies, functions, triggers, secrets", TEXT)
        self.assertIn("No production adapter is part of this design", TEXT)

    def test_primary_owner_and_fresh_aal2_are_required(self):
        self.assertIn("Only the verified primary Owner identity may approve", TEXT)
        self.assertIn("require a current AAL2 session", TEXT)
        self.assertIn("re-checked at the moment the atomic approval transaction commits", TEXT)
        self.assertIn("AAL2 alone is insufficient", TEXT)

    def test_approval_is_bound_to_immutable_artifact_and_candidate(self):
        for marker in (
            "approved_commit_sha",
            "approved_artifact_sha256",
            "evidence_set_digest",
            "candidate_version",
            "consumed_at",
            "invalidated_at",
        ):
            self.assertIn(marker, TEXT)

    def test_evidence_must_match_commit_artifact_and_freshness(self):
        self.assertIn("matches the candidate's exact commit and artifact", TEXT)
        self.assertIn("within its freshness window", TEXT)
        self.assertIn("Missing, failed, stale, inconclusive, or mismatched mandatory evidence blocks progression", TEXT)

    def test_lifecycle_is_server_owned_and_fail_closed(self):
        self.assertIn("compare-and-set protected", TEXT)
        self.assertIn("deny-by-default", TEXT)
        self.assertIn("deployment_uncertain", TEXT)
        self.assertIn("never retry blindly", TEXT)

    def test_client_cannot_write_release_state_or_use_service_key(self):
        self.assertIn("browser dashboard is an untrusted client", TEXT)
        self.assertIn("no direct browser writes to approvals, evidence, events, or lifecycle state", TEXT)
        self.assertIn("never embedded in the dashboard or Android APK", TEXT)

    def test_no_claim_of_automatic_rollback_for_irreversible_effects(self):
        self.assertIn("external email, payment, and third-party effects cannot be described as undone", TEXT)
        self.assertIn("Database schema changes may require forward-fix or tested restore", TEXT)


if __name__ == "__main__":
    unittest.main()
