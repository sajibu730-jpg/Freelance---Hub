import re
import unittest
from pathlib import Path

DRAFT = Path(__file__).parents[1] / "docs" / "drafts" / "owner-workforce-schema.draft.sql"
SQL = DRAFT.read_text(encoding="utf-8")


class WorkforceSchemaDraftTests(unittest.TestCase):
    def test_is_explicitly_non_production_draft(self):
        self.assertIn("DRAFT ONLY", SQL)
        self.assertIn("Do not execute against Production", SQL)

    def test_all_six_tables_enable_rls(self):
        tables = re.findall(r"create table public\.(owner_[a-z_]+)", SQL, re.I)
        self.assertEqual(6, len(tables), tables)
        for table in tables:
            self.assertRegex(
                SQL,
                rf"alter table public\.{re.escape(table)} enable row level security",
            )

    def test_tables_are_not_granted_to_public_clients(self):
        self.assertRegex(SQL, r"revoke all on table[\s\S]*from public, anon, authenticated")
        self.assertRegex(SQL, r"grant all on table[\s\S]*to service_role")
        self.assertNotRegex(SQL, r"grant\s+[^;]*\b(?:anon|authenticated)\b")

    def test_audit_tables_are_append_only_for_service_role(self):
        self.assertIn(
            "grant select, insert on table public.owner_workforce_member_events,",
            SQL,
        )
        self.assertIn("public.owner_release_events\n  to service_role;", SQL)
        self.assertNotIn("grant all on table public.owner_workforce_member_events", SQL)
        self.assertNotIn("grant all on table public.owner_workforce_task_events", SQL)
        self.assertNotIn("grant all on table public.owner_release_events", SQL)

    def test_event_tables_have_unique_request_ids(self):
        for table in (
            "owner_workforce_member_events",
            "owner_workforce_task_events",
            "owner_release_events",
        ):
            section = SQL.split(f"create table public.{table}", 1)[1].split(");", 1)[0]
            self.assertRegex(section, r"request_id uuid not null unique")

    def test_task_kind_separates_general_support_and_incident_work(self):
        self.assertIn(
            "task_kind text not null default 'general' check (task_kind in ('general', 'support', 'incident'))",
            SQL,
        )

    def test_done_tasks_require_completion_evidence(self):
        self.assertIn(
            "check ((status = 'done' and completion_evidence is not null) or status <> 'done')",
            SQL,
        )

    def test_no_privileged_definer_function_is_hidden_in_draft(self):
        self.assertNotRegex(SQL, r"security\s+definer")

    def test_audit_event_tables_are_present(self):
        for table in (
            "owner_workforce_member_events",
            "owner_workforce_task_events",
            "owner_release_events",
        ):
            self.assertIn(f"create table public.{table}", SQL)


if __name__ == "__main__":
    unittest.main()
