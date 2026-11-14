#!/usr/bin/env python3
"""Static safety guard for changed Supabase SQL migrations.

This is a preflight heuristic, not a PostgreSQL parser or a substitute for
staging execution, RLS tests, Supabase Advisors, or Owner release approval.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

IDENT = r'(?:"?[a-zA-Z_][\w$]*"?\.)?"?[a-zA-Z_][\w$]*"?'
CREATE_TABLE = re.compile(r"\bcreate\s+table\s+(?:if\s+not\s+exists\s+)?" + rf"(?P<table>{IDENT})", re.I)
ENABLE_RLS = re.compile(r"\balter\s+table\s+(?:if\s+exists\s+)?" + rf"(?P<table>{IDENT})\s+enable\s+row\s+level\s+security\b", re.I)
CREATE_POLICY = re.compile(
    r'\bcreate\s+policy\s+(?:"[^"]+"|[a-zA-Z_][\w$]*)\s+on\s+' + rf"(?P<table>{IDENT})",
    re.I,
)

def clean_name(value: str) -> str:
    return value.replace('"', "").lower()

def changed_files(base: str, head: str) -> list[Path]:
    result = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...{head}", "--", "supabase/migrations"],
        check=True, capture_output=True, text=True,
    )
    return [Path(name) for name in result.stdout.splitlines() if name.endswith(".sql")]

def validate(path: Path, sql: str) -> list[str]:
    errors: list[str] = []
    tables = {clean_name(m.group("table")) for m in CREATE_TABLE.finditer(sql)}
    rls_tables = {clean_name(m.group("table")) for m in ENABLE_RLS.finditer(sql)}
    policy_tables = {clean_name(m.group("table")) for m in CREATE_POLICY.finditer(sql)}
    service_only_exceptions = {
        clean_name(name)
        for name in re.findall(
            r"--\s*RLS-EXCEPTION:\s*service-role-only\s+([a-zA-Z_][\w$]*(?:\.[a-zA-Z_][\w$]*)?)",
            sql, re.I,
        )
    }

    for table in sorted(tables):
        if table not in rls_tables:
            errors.append(f"{path}: new table {table} is missing ENABLE ROW LEVEL SECURITY")
        if table not in policy_tables and table not in service_only_exceptions:
            errors.append(
                f"{path}: new table {table} has no CREATE POLICY; add a least-privilege policy "
                f"or a table-scoped '-- RLS-EXCEPTION: service-role-only {table}' comment"
            )

    if re.search(r"\bsecurity\s+definer\b", sql, re.I) and not re.search(
        r"--\s*SECURITY-DEFINER-REVIEWED\b", sql, re.I
    ):
        errors.append(f"{path}: SECURITY DEFINER requires an explicit review marker and separate security review")

    destructive = re.search(r"\b(drop\s+table|drop\s+column|truncate\s+(?:table\s+)?|drop\s+schema)\b", sql, re.I)
    if destructive and not re.search(r"--\s*OWNER-APPROVED-DESTRUCTIVE:\s*.+", sql, re.I):
        errors.append(f"{path}: destructive SQL requires an Owner approval marker and verified backup/restore evidence")

    if re.search(r"\b(?:service_role|sb_secret_[A-Za-z0-9_]+)\b", sql, re.I):
        errors.append(f"{path}: server-only credential name/pattern must not be committed in SQL")
    return errors

def main() -> int:
    base = sys.argv[1] if len(sys.argv) > 1 else ""
    head = sys.argv[2] if len(sys.argv) > 2 else "HEAD"
    if not base:
        print("ERROR: base commit is required", file=sys.stderr)
        return 2
    files = changed_files(base, head)
    if not files:
        print("No changed Supabase migration files; safety check passed.")
        return 0
    errors: list[str] = []
    for path in files:
        if path.exists():
            errors.extend(validate(path, path.read_text(encoding="utf-8")))
    if errors:
        print("\n".join("BLOCKED: " + item for item in errors), file=sys.stderr)
        return 1
    print(f"Static migration safety checks passed for {len(files)} changed SQL file(s).")
    print("Reminder: run migrations in disposable staging, test RLS as anon/authenticated/service roles,")
    print("run Supabase Advisors, and obtain explicit Owner release approval before production.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
