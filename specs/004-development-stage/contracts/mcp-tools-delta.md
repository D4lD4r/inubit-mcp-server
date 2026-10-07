# MCP tools delta (feature 004)

Five new tools, offered only if at least one node has `development.enabled` (`run_e2e_test` only if,
in addition, a node's `e2eTests` is not `FORBIDDEN`). All are `ToolHints.destructive(…)`
(`destructiveHint: true`, `idempotentHint: false`, `openWorldHint: true`), audited, use the profile
prefix and terminology, return bounded results (fields without a value are absent), and refuse every
non-development node with `NOT_DEVELOPMENT`. Confirmation follows the node's
`development.confirmation` (`SERVER`: first call → `{"challenge": {...}}`, second call with
`confirmationCode` → `{"result": {...}}`; `CLIENT`: one call). Schemas under
`src/main/resources/schemas/<tool>.{input,output}.json`.

Common inputs: `node` (one node id `<group>/<node>`, required), `confirmationCode` (22 chars),
`reason` (1–500 chars, no `###`, `@@@` or control characters; required for import, restore,
activate, tag); `owner` defaults to `inventory.owner` for every tool that takes it.

## `import_artifacts`

> [acme] Import the changed workflows of ONE diagram group (with their changed or new modules), or
> changed single modules, from the workspace into ONE development {node}. The server checks the files,
> refuses on conflicts (changed on the server or open in the Workbench), backs up, imports only what
> changed, verifies by re-export and rolls back on failure. Secrets are taken from the {node}.

Input: `node`, `owner` (default `inventory.owner`), exactly one of `diagramGroup` (string) or
`modules` (1–50 `{name, pluginType?}`), `reason`, `confirmationCode`.

Challenge preview: `{ "scope", "baseCommit", "create": [names], "modify": [names], "notImported":
[paths], "checkWarnings": n }` + code/expiry (no owner kind, research D-26).

Result: `{ "outcome": "EXECUTED"|"FAILED", "commit", "backupRef", "created": [...], "modified": [...],
"notImported": [...], "rollback": "NOT_NEEDED"|"SUCCEEDED"|"FAILED", "createdNotRemoved": [...],
"reports": [paths], "warnings": [...] }`.

Failure model (research D-25): refusals **before** anything is sent are tool errors —
`NOT_DEVELOPMENT`, `INVALID_INPUT` (scope, deletions, repository changes, reason with `###`/`@@@`),
`PRECONDITION_FAILED` (check errors with the findings report path, missing referenced modules, lock,
no base export), `CONFLICT` (diff path),
`SECRET_UNRESOLVED` (artifact + property path), `CONFIRMATION_INVALID`, `CLI_UNAVAILABLE`,
`AUTH_FAILED`. Once anything was sent, the call returns a **result** with `outcome: FAILED`,
`failure: {code: IMPORT_FAILED | VERIFY_MISMATCH, step, message}` and `rollback`. Fields without a
value are absent.

## `restore_backup`

> [acme] Re-import the backup taken by an earlier development call on ONE {node}, limited to the
> artifacts that call changed; same checks, conflict detection, verification and rollback.

Input: `node`, `backupRef` (an auditId, UUID), `reason`, `confirmationCode`. Result as
`import_artifacts`. Errors additionally: `NOT_FOUND` (unknown or removed backup).

## `set_active`

> [acme] Activate or deactivate ONE workflow on ONE development {node} (INUBIT creates a new version).

Input: `node`, `owner`, `diagramGroup`, `workflow`, `active` (boolean), `reason`, `confirmationCode`.
Result as `import_artifacts` (only that workflow).

## `tag_artifacts`

> [acme] Tag the current versions of the technical workflows (and their modules) of the given diagram
> groups of an owner on ONE development {node}. Never owner-wide; an existing tag is never moved.

Input: `node`, `owner`, `diagramGroups` (1–20, non-blank), `tag` (`CliCommand.VALUE`), `reason`,
`confirmationCode`. Result: `{ "tag", "diagramGroups", "workflows": n, "modules": n, "removedAgain":
false }`. Errors: `INVALID_INPUT` (blank group, existing tag), `VERIFY_MISMATCH` (tag reached other
artifacts — removed again).

## `run_e2e_test`

> [acme] Send a SOAP envelope from the workspace to an endpoint of ONE {node} and report the response
> and the process instances, errors and log entries it caused. Allowed only where e2eTests permits.

Input: `node`, `envelope` (workspace path, confined), `path` (relative endpoint path), `soapAction`,
`workflow` (for correlation), `timeoutSeconds` (1–120, default 60), `includeExcerpt` (default
false), `confirmationCode` (when `e2eTests: CONFIRM`).
Preview (CONFIRM): `{ "endpoint", "payloadBytes", "soapAction" }`.
Result: `{ "testId", "status", "durationMs", "timedOut", "responseFile", "excerpt", "correlation":
"BY_TEST_ID"|"TIME_WINDOW_UNCERTAIN", "processes": [...], "errors": [...], "logEntries": [...],
"truncated" }`. Errors: `E2E_FORBIDDEN`, `INVALID_INPUT` (path escapes), `UNREACHABLE`, `TLS_ERROR`.
