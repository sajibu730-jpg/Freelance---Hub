import {
  isUuid,
  parseWorkforceBody,
  rpcErrorStatus,
  taskListPolicy,
} from "./request.ts";

Deno.test("workforce body accepts a JSON object", () => {
  const result = parseWorkforceBody('{"action":"create_task"}');
  if (!result.ok || result.body.action !== "create_task") {
    throw new Error("valid object body was rejected");
  }
});

Deno.test("workforce body rejects malformed, primitive, array, and oversized input", () => {
  for (const raw of ["{", "null", "1", "[]", '"text"', " ".repeat(16_001)]) {
    if (parseWorkforceBody(raw).ok) throw new Error("invalid body was accepted");
  }
});

Deno.test("request UUID validation rejects malformed identifiers", () => {
  if (!isUuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1")) throw new Error("valid UUID rejected");
  for (const value of ["", "not-a-uuid", "aaaaaaaa-aaaa-4aaa-7aaa-aaaaaaaaaaa1", null, 12]) {
    if (isUuid(value)) throw new Error("invalid UUID accepted");
  }
});

Deno.test("RPC errors map to safe HTTP status classes", () => {
  const cases: Array<[string, number]> = [
    ["42501", 403], ["P0002", 404], ["22023", 400], ["23505", 409], ["XX000", 500],
  ];
  for (const [code, expected] of cases) {
    if (rpcErrorStatus({ code }) !== expected) throw new Error("incorrect RPC error mapping");
  }
});


Deno.test("read-only task list does not require a mutation idempotency key", () => {
  const body = parseWorkforceBody('{"action":"list_tasks","project_key":"freelance-hub"}');
  if (!body.ok || body.body.action !== "list_tasks") {
    throw new Error("valid read-only list request was rejected");
  }
});


Deno.test("task list policy scopes Developers and QA to their own assignments", () => {
  for (const role of ["developer", "qa"] as const) {
    const policy = taskListPolicy(role, "user-123");
    if (policy.assigneeId !== "user-123" || policy.taskKinds !== null) {
      throw new Error(role + " task list is not restricted to the verified assignee");
    }
  }
});

Deno.test("task list policy limits Support to support and incident tasks", () => {
  const policy = taskListPolicy("support", "support-user");
  if (policy.assigneeId !== null || !policy.taskKinds
      || policy.taskKinds.join(",") !== "support,incident") {
    throw new Error("Support task list includes an unauthorized task category");
  }
});

Deno.test("task list policy leaves Project Manager within the project-level query scope", () => {
  const policy = taskListPolicy("project_manager", "pm-user");
  if (policy.assigneeId !== null || policy.taskKinds !== null) {
    throw new Error("Project Manager task list should include all task kinds in their scoped project");
  }
});
