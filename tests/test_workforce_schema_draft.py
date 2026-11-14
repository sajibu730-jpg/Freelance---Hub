import re
import unittest
from pathlib import Path

DRAFT = Path(__file__).parents[1] / "docs" / "drafts" / "owner-workforce-schema.draft.sql"
SQL = DRAFT.read_text(encoding="utf-8")


class WorkforceSchemaDraftTests(unittest.TestCase):
    def test_is_explicitly_non_production_draft(self):
        self.assertIn("DRAFT ONLY", SQL)
        self.assertIn("Do not execute against Production", SQL)

    def test_all_eight_tables_enable_rls(self):
        tables = re.findall(r"create table public\.(owner_[a-z_]+)", SQL, re.I)
        self.assertEqual(8, len(tables), tables)
        for table in tables:
            self.assertRegex(
                SQL,
                rf"alter table public\.{re.escape(table)} enable row level security",
            )

    def test_service_role_is_read_only_on_workforce_tables(self):
        self.assertIn(
            "from public, anon, authenticated, service_role;",
            SQL,
        )
        self.assertIn(
            "grant select on table public.owner_workforce_members, public.owner_workforce_tasks,",
            SQL,
        )
        self.assertIn("public.owner_workforce_invitations", SQL)
        self.assertNotRegex(SQL, r"grant\s+(?:all|insert|update|delete)[^;]*to service_role")
        self.assertNotRegex(SQL, r"grant\s+[^;]*\b(?:anon|authenticated)\b")

    def test_audit_tables_have_no_direct_api_role_grants(self):
        self.assertIn("Event tables are inaccessible to API roles.", SQL)
        self.assertIn("public.owner_workforce_invitation_events", SQL)
        self.assertNotRegex(
            SQL,
            r"grant\s+[^;]*public\.owner_workforce_(?:member|task)_events",
        )
        self.assertNotRegex(SQL, r"grant\s+[^;]*public\.owner_release_events")
        self.assertNotRegex(SQL, r"grant\s+[^;]*public\.owner_workforce_invitation_events")
        self.assertNotRegex(SQL, r"grant\s+[^;]*public\.owner_workforce_invitation_events")

    def test_event_tables_have_unique_request_ids(self):
        for table in (
            "owner_workforce_member_events",
            "owner_workforce_task_events",
            "owner_release_events",
            "owner_workforce_invitation_events",
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

    def test_no_privileged_definer_function_is_hidden_in_schema_draft(self):
        self.assertNotRegex(SQL, r"security\s+definer")

    def test_audit_event_tables_are_present(self):
        for table in (
            "owner_workforce_member_events",
            "owner_workforce_task_events",
            "owner_release_events",
            "owner_workforce_invitation_events",
        ):
            self.assertIn(f"create table public.{table}", SQL)


if __name__ == "__main__":
    unittest.main()
