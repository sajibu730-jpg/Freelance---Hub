import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "migration_guard", Path(__file__).parents[1] / "scripts" / "check_supabase_migrations.py"
)
guard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(guard)

class MigrationGuardTests(unittest.TestCase):
    def test_new_table_requires_rls_and_policy(self):
        sql = """
        create table public.work_items (id uuid primary key);
        alter table public.work_items enable row level security;
        create policy "owner reads" on public.work_items for select to authenticated using (true);
        """
        self.assertEqual([], guard.validate(Path("example.sql"), sql))

    def test_missing_rls_is_blocked(self):
        sql = "create table public.work_items (id uuid primary key);"
        self.assertTrue(any("missing ENABLE ROW LEVEL SECURITY" in e
                            for e in guard.validate(Path("example.sql"), sql)))

    def test_service_only_exception_is_explicit(self):
        sql = """
        -- RLS-EXCEPTION: service-role-only
        create table public.private_events (id uuid primary key);
        alter table public.private_events enable row level security;
        """
        self.assertEqual([], guard.validate(Path("example.sql"), sql))

    def test_security_definer_needs_review_marker(self):
        sql = "create function public.f() returns void language sql security definer as $$ select; $$;"
        self.assertTrue(any("SECURITY DEFINER" in e
                            for e in guard.validate(Path("example.sql"), sql)))

    def test_destructive_change_needs_owner_marker(self):
        sql = "drop table public.old_table;"
        self.assertTrue(any("destructive SQL" in e
                            for e in guard.validate(Path("example.sql"), sql)))

if __name__ == "__main__":
    unittest.main()
