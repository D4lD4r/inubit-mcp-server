# Contract: MCP Tools

> **Renamed in feature 002**: tool `list_servers` → `list_nodes`; result fields `stage`/`server` →
> `group`/`node` (`list_nodes` returns `groups[].nodes[]` of `NodeSummary`); the write-tool input
> `server` → `node`; `ToolError.server` → `ToolError.node`; error code `ENVIRONMENT_UNKNOWN` →
> `TARGET_UNKNOWN`; the INUBIT Queue Manager column of a `ProcessInstance` is now `inubitNode`.
> See [002 contracts/mcp-tools-delta.md](../../002-customer-agnostic-config/contracts/mcp-tools-delta.md).
> Descriptions quoted below keep their 001 wording ("stage"/"server"); feature 002 renders them with
> the profile's terminology.

The MCP tool surface offered to the AI assistant (Constitution IV: small, use-case oriented).
Result shapes reference [data-model.md](../data-model.md). Every tool returns its result as MCP
`structuredContent` (matching the declared output schema) **and** as a compact JSON text block for
clients without structured-output support. Errors are returned as tool results with `isError: true`
— never as protocol-level exceptions (Constitution VI), with the one exception of an unknown tool
name (see "Failure kinds").

### Failure kinds

| Kind | How it is returned |
|---|---|
| **Tool error** (any `ToolError` of the error mapping below, raised by the tool for the whole call) | `isError: true` with exactly one text block containing the compact JSON `{"error": ToolError}` and **no** `structuredContent` (clients validate `structuredContent` against the tool's `outputSchema`, which an error does not match). Unexpected internal failures are tool errors with code `INTERNAL` and no stack trace |
| **Schema violation** of the arguments (unknown property, wrong type, enum, pattern, min/max …) | rejected by the MCP SDK *before* the tool runs: `isError: true` with a plain-text validation message (English) — no `ToolError` and no `code` |
| **Unknown tool name** | JSON-RPC error `-32602` (invalid params) from the SDK; the only protocol-level error, accepted because no tool exists to produce a result |

Per-server failures inside a successful multi-server result (e.g. `results[].error`,
`reports[].error`) are `ToolError` objects inside `structuredContent`, not error results.

Common input conventions:

- `target`: a stage (`test`) or a single server (`test/inubit01`) — read tools;
  `node`: a single server id only — write tools.
  Pattern `^[a-z0-9][a-z0-9-]{0,31}(/[a-z0-9][a-z0-9-]{0,31})?$` (write tools: the `/` part is
  required).
- Timestamps: ISO-8601 (`2026-10-01T08:00:00Z` or with offset); relative shortcuts `PT24H`-style
  durations are accepted for `since` and mean "now minus duration".
- `offset` 0…9999 (default 0); `limit` 1…`resultLimits.maxItems` (default 50); for the log-backed
  tools `offset + limit` ≤ 10,000 (research R-9; the schema bound on `offset` follows from it).
  Integer arguments outside the 32-bit range are rejected (`INVALID_INPUT`), never wrapped.
- Unknown properties are rejected (`additionalProperties: false`).

| # | Tool | Story | Annotations | Backed by (8.1) |
|---|---|---|---|---|
| 1 | `list_nodes` | all | readOnly, idempotent, closedWorld | config only |
| 2 | `get_health` | P1 | readOnly, idempotent, openWorld | REST |
| 3 | `find_processes` | P2 | readOnly, idempotent, openWorld | REST (`queueLog`, R-8) |
| 4 | `query_logs` | P2 | readOnly, idempotent, openWorld | REST |
| 5 | `list_inventory` | P3 | readOnly, idempotent, openWorld | diagrams: REST · modules: CLI export (R-11) |
| 6 | `get_inventory_item` | P3 | readOnly, idempotent, openWorld | REST + CLI export with history (R-11) |
| 7 | `restart_process` | P4 | destructive, non-idempotent, openWorld | CLI |
| 8 | `kill_process` | P4 | destructive, non-idempotent, openWorld | CLI |

Tools 7–8 are **registered only if at least one server has effective write access**
(Story 4 / AS 6). Tools 7 and 8 report `CLI_UNAVAILABLE` per server when no CLI home is
configured; they are still listed so the assistant can explain the gap. Tools 5 and 6 report the
CLI-backed parts (module list, version history) as unavailable in that case.

---

## 1. `list_nodes`

**Description (for the model)**: "List the configured INUBIT stages and their servers (ids like
`test/inubit01`), with production classification and whether write actions are enabled. Call this
first to learn valid `target` and `node` values."

**Input**: `{}`

**Output**: `{ "groups": [{ "name", "production", "nodes": NodeSummary[] }] }`

---

## 2. `get_health`

**Description**: "Check whether INUBIT servers are reachable and ready: maintenance mode, version,
memory, threads, and blocking queue. Target a server, a stage, or omit `target` for all."

**Input**:

```json
{
  "type": "object",
  "properties": {
    "target": { "type": "string", "description": "Stage or stage/server; omit for all",
                "pattern": "^[a-z0-9][a-z0-9-]{0,31}(/[a-z0-9][a-z0-9-]{0,31})?$" },
    "includeSystemInfo": { "type": "boolean", "default": false }
  },
  "additionalProperties": false
}
```

**Output**: `{ "reports": HealthReport[] }` — one per resolved server, in config order.
Unreachable servers are reports with `reachable=false` and `error` set; the tool itself does
not fail because one server failed (FR-006). Overall wall time ≤ max server timeout + 1 s.
Parts that could not be obtained are `unavailable: [{ part, reason, likelyCause?, nextStep? }]`
(`reason` = `CODE: message`; cause and step copied from the `ToolError`). `/healthcheck` and
`/ready` start at once (never waiting for an `AUTO` version detection); with unconfirmed
credentials `/metrics` follows `/system/info` (research R-10, R-12). Schema:
`src/main/resources/schemas/get_health.output.json`.

---

## 3. `find_processes`

**Description**: "Find process instances on INUBIT servers, e.g. failed (ERROR) or hanging ones,
filtered by workflow, state, and time. Use the returned processId and time range with `query_logs`
to find the cause."

Backed by the Queue Manager view `POST /ibis/rest/log/queueLog` (research R-8, spike S-4); state
mapping and fields in [data-model.md](../data-model.md) → ProcessInstance. `workflow` and `tag`
are exact matches (`EQUAL`), `since`/`until` filter `startTime`, and INUBIT sorts, pages and
counts.

- **Paging**: a single request uses `startIndex = offset`, `noOfItems = limit`. When the states
  expand to several raw values (e.g. `WAITING` = `Waiting` + `Retry`, or several `states`), each
  raw value is one request with `startIndex = 0`, `noOfItems = offset + limit`; rows are merged,
  sorted by `since` descending and sliced to `[offset, offset + limit)`, `total` = sum of the
  totals (research R-9). `offset + limit > 10000` → `INVALID_INPUT`.
- **`hangingOnly`**: restricts the query to the raw states that count for hanging (`Waiting`,
  `Retry`, `Queued`, `Processing`), intersected with `states`; `states=[ERROR]` with
  `hangingOnly=true` returns an empty page. Rows must also be older than the threshold
  (`startTime LESSER now − threshold`).

**Input**:

```json
{
  "type": "object",
  "required": ["target"],
  "properties": {
    "target":   { "type": "string",
                  "pattern": "^[a-z0-9][a-z0-9-]{0,31}(/[a-z0-9][a-z0-9-]{0,31})?$" },
    "states":   { "type": "array", "items": { "enum": ["ERROR", "ACTIVE", "WAITING", "QUEUED"] },
                  "description": "Default: all states" },
    "hangingOnly": { "type": "boolean", "default": false },
    "hangingThresholdMinutes": { "type": "integer", "minimum": 1, "maximum": 10080 },
    "workflow": { "type": "string", "maxLength": 200, "description": "Exact workflow name" },
    "tag":      { "type": "string", "maxLength": 100 },
    "since":    { "type": "string", "description": "ISO-8601 timestamp or duration (PT24H)" },
    "until":    { "type": "string" },
    "offset":   { "type": "integer", "minimum": 0, "maximum": 9999, "default": 0 },
    "limit":    { "type": "integer", "minimum": 1, "maximum": 100, "default": 50 }
  },
  "additionalProperties": false
}
```

**Output**: `{ "results": [{ "node", "page": Page<ProcessInstance>?, "error": ToolError? }] }`,
items sorted by `since` descending.

---

## 4. `query_logs`

**Description**: "Read INUBIT log entries (systemLog = workflow executions, queueLog, auditLog,
schedulerLog, connectionLog, keyManagerLog, webserviceManager) filtered by time, workflow,
severity, process ID, or text. `text` is a case-sensitive substring match in which `%` matches any
sequence and `_` any single character. Newest first, paginated."

**Input**:

```json
{
  "type": "object",
  "required": ["target", "logType"],
  "properties": {
    "target":    { "type": "string",
                   "pattern": "^[a-z0-9][a-z0-9-]{0,31}(/[a-z0-9][a-z0-9-]{0,31})?$" },
    "logType":   { "enum": ["systemLog", "queueLog", "connectionLog", "schedulerLog",
                            "auditLog", "keyManagerLog", "webserviceManager"] },
    "since":     { "type": "string" },
    "until":     { "type": "string" },
    "workflow":  { "type": "string", "maxLength": 200 },
    "processId": { "type": "string", "pattern": "^[0-9A-Za-z_-]{1,64}$" },
    "severity":  { "type": "array", "items": { "enum": ["ERROR", "WARN", "INFO", "DEBUG"] } },
    "text":      { "type": "string", "maxLength": 200,
                   "description": "Case-sensitive substring match on the log type's text field; % and _ are wildcards" },
    "offset":    { "type": "integer", "minimum": 0, "maximum": 9999, "default": 0 },
    "limit":     { "type": "integer", "minimum": 1, "maximum": 100, "default": 50 }
  },
  "additionalProperties": false
}
```

**Output**: `{ "results": [{ "node", "page": Page<LogEntry>?, "error": ToolError? }] }`.
Filters that a given log type does not support (e.g. `workflow` on `auditLog`) → `INVALID_INPUT`
naming the supported filters for that log type (table below).

`processLog` is not offered: on 8.1.17 the endpoint fails (GET → HTTP 500, POST → HTTP 400;
research S-5). Workflow executions are in `systemLog`.

**Per-log-type filter table** (INUBIT 8.1.17, spike S-5; research → Spike results "S-5").
Each cell names the INUBIT field the tool input maps to and the comparison sent in the
`<logRequest>`. All time fields are epoch milliseconds; `since`/`until` become `BETWEEN`
(or `GREATER`/`LESSER` when only one is given). Results are always sorted by the time field
`DESCENDING`; log types without a time field are sorted by `index` order of the response.
"—" means the filter is not supported for that log type (→ `INVALID_INPUT`).

| `logType` | time field (`since`/`until`) | `workflow` (`EQUAL`) | `processId` (`EQUAL`) | `severity` | `text` (`LIKE`) | other fields in each row |
|---|---|---|---|---|---|---|
| `systemLog` | `startTime` ✓ (also `endTime` ✓) | `workflowName` ✓ | `workflowId` ✓ (digits) / `globalPId` ✓ (UUID) | `ERROR` → `success EQUAL false` ✓, `INFO` → `success EQUAL true` ✓ | `message` ✓ | `owner`, `node`, `priority`, `tag`, `inputModule`, `outputModule`, `duration`, `inputSize`, `outputSize`, `endTime`, `userDefined1…5` (not filterable) |
| `queueLog` | `startTime` ✓ | `workflowName` ✓ | `workflowId` ✓ (digits) / `globalPId` ✓ (UUID) | `ERROR` → `status EQUAL Error` ✓, `WARN` → `Waiting` ✓ + `Retry` ✓, `INFO` → `Queued` ✓ + `Processing` ✓ | `moduleName` ✓ (no message field) | `moduleType`, `fileName`, `fileSize`, `node`, `owner`, `priority`, `tag`, `nextStartTime`, `userDefined1…5` (not filterable) |
| `auditLog` | `time` ✓ | — | — | `ERROR` → `success EQUAL false` ✓, `INFO` → `success EQUAL true` | `message` ✓ | `user` ✓, `operation` ✓, `objectName` ✓ |
| `schedulerLog` | `nextStartTime` ✓ | `workflowName` ✓ | — | — | `moduleName` ✓ | `moduleType`, `statusActive` ✓, `owner`, `tag` |
| `connectionLog` | `lastConnection` ✓ | — | — | — | `systemType` ✓ | `systemId` ✓, `version`, `connectionStatus`, `licenceDuration`, `licenceLastUpdate` |
| `keyManagerLog` | `validity` ✓ (certificate expiry) | — | — | — | `name` ✓ | `owner` ✓, `type` ✓, `propertyName`, `subject`, `serial_number`, `description`, `URL`, `tag` |
| `webserviceManager` | — (no time field) | `workflowName` ✓ | — | — | `moduleName` ✓ | `moduleType`, `webserviceStatus` ✓ (`true` or e.g. `"workflowNotActive"`), `owner`, `tag` |

✓ = filter accepted by DEV and returned a plausible subset. Notes:

- `text` → `LIKE`: a case-sensitive substring match in which `%` and `_` act as SQL wildcards.
  There is no escape syntax; the value is passed through unchanged and the wildcards are
  documented (tool description, `docs/tools.md`).
- `processId`: a value of digits maps to `workflowId` (the `processId` of `find_processes`), any
  other value (UUID) to `globalPId`.
- Unknown or non-filterable fields (`userDefined1…5`) and unknown comparisons give HTTP 400;
  invalid `status` values give HTTP 500. The table is the allowlist that prevents both.
- INUBIT has **no severity field**. The `severity` column follows the severity table in
  [data-model.md](../data-model.md) → LogEntry; values not listed there (e.g. `DEBUG`, `WARN` on
  systemLog, any `severity` on the log types without a column entry) → `INVALID_INPUT`. A value
  that maps to several raw values is sent as one request per raw value, merged.
- Paging (research R-9): a single request uses `<startIndex>` = `offset`, `<noOfItems>` = `limit`.
  When a filter expands to several raw values (e.g. `severity: [WARN]` on queueLog), each request
  uses `startIndex = 0`, `noOfItems = offset + limit`; rows are merged, sorted by time descending
  and sliced to `[offset, offset + limit)`, `total` = sum of the totals. `offset + limit > 10000`
  → `INVALID_INPUT`.

---

## 5. `list_inventory`

**Description**: "List diagrams (technical workflows, BPDs, process maps, …) or modules on an INUBIT
server or stage, filtered by name, type, or group."

**Input**:

```json
{
  "type": "object",
  "required": ["target", "kind"],
  "properties": {
    "target":       { "type": "string",
                      "pattern": "^[a-z0-9][a-z0-9-]{0,31}(/[a-z0-9][a-z0-9-]{0,31})?$" },
    "kind":         { "enum": ["DIAGRAM", "MODULE"] },
    "nameContains": { "type": "string", "maxLength": 200 },
    "type":         { "type": "string", "maxLength": 100,
                      "description": "Diagram type (technical, bpd, processmap, organigram, systemdiagram, constraintsdiagram) or module plugin type (e.g. 'XSLT Converter')" },
    "group":        { "type": "string", "maxLength": 200 },
    "offset":       { "type": "integer", "minimum": 0, "default": 0 },
    "limit":        { "type": "integer", "minimum": 1, "maximum": 100, "default": 50 },
    "refresh":      { "type": "boolean", "default": false, "description": "Bypass the inventory cache" }
  },
  "additionalProperties": false
}
```

**Output**: `{ "results": [{ "node", "collectedAt", "usageComplete"?, "page": Page<InventoryItem>?, "error": ToolError? }] }`,
sorted by `name`. `group` filters the diagram group (diagrams) or module group = plugin type (modules).
First module listing per server takes ~10–15 s (CLI export), later calls are served from the cache.
Schema: `src/main/resources/schemas/list_inventory.output.json`.

- `collectedAt` (ISO-8601, when the cached data was collected) is present whenever `page` is; a
  server that failed has only `error` (Phase 5 schema decision: `collectedAt` is optional in the
  schema).
- Filters: `nameContains` case-insensitive substring; `type` and `group` case-insensitive
  equality. Sorting by name, case-insensitive.
- Items are bounded like log entries: texts ≤ 200 chars with `…[truncated]`, each item ≤ 4,000
  chars as JSON (escape-aware); any cut sets `page.truncated`.
- CLI exports (module list here, version histories in §6), Phase 5 review:
  - While the server's credentials are unconfirmed, an export that can run is preceded by one
    authenticated `GET /ibis/rest/system/info`, so that StartCLI never holds the credential
    guard's single unconfirmed permit for the whole export; a rejected login stops the export
    before StartCLI starts (I3).
  - Exports run in `java.io.tmpdir/inubit-mcp-export-<pid>-<random>` (owner-only). Running
    StartCLI processes and these directories are registered: on stdin EOF and in the JVM shutdown
    hook (SIGTERM) the process trees are destroyed (killed after 2 s) and the directories
    deleted; at startup, directories of dead MCP server processes of the same user are deleted
    (SIGKILL). Links are never followed (I1).
  - A `java.io.tmpdir` path that StartCLI cannot take (the R-11 path rule) is `CLI_UNAVAILABLE`
    and a `--check-config` warning (I9). An export `TIMEOUT` names `cliExportTimeout` (I5).
- **Module usage** (T126, finding F1 of the validation 2026-10-03): the module export names a
  workflow (`WorkflowName`) only for connector modules. Module items therefore carry
  `workflows` (the technical workflows of the owner that use the module, sorted by name; at most
  5 in a list item) and `workflowCount` (all of them), from a per-server **usage index**: the
  `Node` elements of every technical workflow (`GET /model/modelByName`; read one at a time until
  the first read succeeds, then 8 in flight, and no new read after an `AUTH_FAILED`, so that a
  password that became invalid costs one failed login; all within
  `cliExportTimeout`, through the credential guard) — a node names the module it runs —
  joined with the connectors' `WorkflowName`. A module name shared by several modules of the
  owner is resolved by the node type (`tw<PluginName without spaces>`). The index is cached
  like the module list (same TTL, single flight, `refresh`); an incomplete one (a workflow not
  readable or not read in time, or the diagram list failed) is returned but not cached, and the
  server entry states `usageComplete: false` — then an empty `workflows` does not mean unused.
  `usageComplete` is present only for `kind: MODULE`; `collectedAt` is then the older of the
  module list and the usage index.
- Per-server deadline: diagrams `2 × timeout + 1 s`; modules `export + cliExportTimeout (usage
  index) + 3 × timeout (guard wait, login, diagram list) + 1 s`, with `export = cliExportTimeout
  + 2 × 2 s (stop StartCLI)` (I4, T126).

---

## 6. `get_inventory_item`

**Description**: "Show details of one diagram or module: version history (version, check-in user and
time, comment, tags), active flag, modules used (diagrams), last change. Call it with a stage target to
compare servers."

**Input**:

```json
{
  "type": "object",
  "required": ["target", "kind", "name"],
  "properties": {
    "target": { "type": "string",
                "pattern": "^[a-z0-9][a-z0-9-]{0,31}(/[a-z0-9][a-z0-9-]{0,31})?$" },
    "kind":   { "enum": ["DIAGRAM", "MODULE"] },
    "name":   { "type": "string", "minLength": 1, "maxLength": 200 },
    "refresh": { "type": "boolean", "default": false }
  },
  "additionalProperties": false
}
```

**Output**: `{ "results": [{ "node", "collectedAt", "item": InventoryDetail?, "error": ToolError? }] }`.
Not found → `error.code = NOT_FOUND` and `item.similarNames` up to 5 names by edit distance.
Schema: `src/main/resources/schemas/get_inventory_item.output.json`.

- `collectedAt` is present whenever `item` is (also for `NOT_FOUND`) and is the oldest collection
  time of the cached parts used (diagram list, module list, version history).
- `InventoryDetail` (data-model.md): required `node`, `kind`, `name`, `owner`, `unavailable`,
  `truncated`; everything else optional. Not found: only `node`, `kind`, `name`, `owner`,
  `similarNames` plus `unavailable: []`, `truncated: false`. Similar names (case-insensitive):
  names that contain the requested name, names of at least 3 chars contained in it (e.g.
  `Workflow-0101` for `Workflow-0101-old`), or names within an edit distance of `max(2, length / 3)`;
  ordered by edit distance, then by name; at most 5.
- Modules (T126): `workflows` (all using technical workflows, sorted), `workflowCount`,
  `usageComplete` as in §5. The version history comes from the history export of the group of
  the **first** using workflow (in the order of `workflows`) that is in the diagram list (a
  module's history is the same in every group export that contains it).
- Schema additions of Phase 5 (beyond the field list of data-model.md): `unavailable: [{ part:
  "versions", reason: "CODE: message", likelyCause?, nextStep? }]` — a failed or impossible
  version history (no CLI, CLI failure, group outside the R-11 quoting rule, a module without
  using workflow: `NOT_FOUND` "not used by any technical workflow" if the usage index is
  complete, otherwise the index's failure) is a partial result next to the rest of the detail,
  not a server error; `truncated` —
  texts cut to 200 chars, or, when the detail exceeds the server's share of
  `resultLimits.maxChars`, the oldest versions (then the last modules / similar names) dropped.
- A REST failure of the diagram list, `modelByName` or the export fails that server's result.
- Per-server deadline (fan-out, review I4; one export = `cliExportTimeout + 2 × 2 s` to stop
  StartCLI): diagram `export + 5 × timeout + 1 s` (guard wait, list, nodes, export, login),
  module `2 × export + cliExportTimeout (usage index) + 4 × timeout + 1 s` (T126). A stopped history export therefore ends before the
  deadline and is reported in `unavailable` (`TIMEOUT: …`), not as a server `TIMEOUT`. The CLI
  rules of §5 (login before the export, cleanup on shutdown, `java.io.tmpdir`) apply.

---

## 7. `restart_process` / 8. `kill_process`

**Description (restart)**: "Restart ONE process instance that is in ERROR state on ONE INUBIT
server. Changes production data flow. On servers with server-side confirmation, the first call only
returns a preview and a confirmationCode; call again with the code to execute."

**Description (kill)**: "Delete (kill) ONE process instance on ONE INUBIT server. Irreversible.
Same two-step confirmation as restart_process."

**Input** (both):

```json
{
  "type": "object",
  "required": ["node", "processId"],
  "properties": {
    "node":             { "type": "string", "pattern": "^[a-z0-9][a-z0-9-]{0,31}/[a-z0-9][a-z0-9-]{0,31}$",
                          "description": "Single server id (stage/server), not a stage" },
    "processId":        { "type": "string", "pattern": "^[1-9][0-9]{0,18}$" },
    "confirmationCode": { "type": "string", "pattern": "^[A-Za-z0-9_-]{22}$" },
    "reason":           { "type": "string", "maxLength": 500,
                          "description": "Why this action is taken; stored in the audit log" }
  },
  "additionalProperties": false
}
```

`processId` is the decimal Queue Manager id: `processId` from `find_processes`, i.e. `ps` `PID` =
queueLog `workflowId` (spike S-4). A UUID `globalPId` is not accepted, because
`processErrorStart`/`kill` take the Queue Manager id.

**Decision flow** (each step that ends the flow writes an audit record):

```text
resolve server ─────(stage / unknown)──► INVALID_INPUT / TARGET_UNKNOWN   [audit REFUSED]
  │
production && !productionOptIn ──► PRODUCTION_PROTECTED (regardless of write.enabled)  [audit REFUSED]
production && confirmation = CLIENT ──► PRODUCTION_PROTECTED (defense in depth)   [audit REFUSED]
  │
write.enabled? ──no──► WRITE_DISABLED (+ how to enable)                          [audit REFUSED]
  │
CLI available? ──no──► CLI_UNAVAILABLE                                            [audit REFUSED]
  │
read state (queueLog) ──(not found)──► NOT_FOUND                                  [audit REFUSED]
  │   restart && state != ERROR ──► PRECONDITION_FAILED (actual state)            [audit REFUSED]
  │
confirmation = SERVER and no code ──► ConfirmationChallenge (isError=false)       [audit CHALLENGE_ISSUED]
  (1000 unexpired codes pending ──► PRECONDITION_FAILED "too many pending confirmations")  [audit REFUSED]
confirmation = SERVER and code invalid/expired/mismatch ──► CONFIRMATION_INVALID  [audit REFUSED]
confirmation = SERVER, code valid, but the instance row differs from the previewed one
  (state, rawState, since, workflow or module) ──► PRECONDITION_FAILED (previewed vs actual)  [audit REFUSED]
  │
append audit (EXECUTE, PENDING) ── fail ──► INTERNAL, action NOT executed
  │
execute CLI command ──► re-read state ──► ProcessControlResult                    [audit EXECUTED/FAILED]
```

Notes on the flow:

- A confirmation code is bound to the server, the action, the `processId` **and the previewed
  state** of the instance (the previewed row's state, raw state, `since`, workflow and module).
  A presented code is used up by the call, whatever its outcome.
- Any unexpected exception before the CLI call (guard, state read, code handling) ends the flow
  as an audited `INTERNAL` refusal; nothing is executed.
- Before the server is resolved, the semantic input checks run (`processId`
  `^[1-9][0-9]{0,18}$`, code pattern, `reason` ≤ 500 chars → `INVALID_INPUT`, audited). They
  repeat the schema rules and are reached only if the SDK's schema validation is bypassed.
- **Not audited**: arguments that violate the input schema (a stage id, a malformed `processId`
  or `confirmationCode`, a `reason` over 500 chars, an unknown property) are rejected by the MCP
  SDK before the tool runs (see "Failure kinds"); no tool code, and hence no audit, is involved
  and nothing can be changed.

**Output**: either `{ "challenge": ConfirmationChallenge }` or `{ "result": ProcessControlResult }`.

---

## Error mapping (all tools)

| Condition | `ToolError.code` |
|---|---|
| target not configured | `TARGET_UNKNOWN` (message lists valid names) |
| connect refused / DNS failure | `UNREACHABLE` |
| timeout exceeded | `TIMEOUT` |
| TLS handshake / hostname mismatch | `TLS_ERROR` |
| HTTP 401, CLI login rejected | `AUTH_FAILED` |
| HTTP 403, missing role/right | `FORBIDDEN` |
| HTTP 503 and the healthcheck reports maintenance mode (one unauthenticated `GET /ibis/rest/healthcheck`, timeout ≤ 2 s) | `MAINTENANCE_MODE` |
| HTTP 503 without maintenance flag (server answered but is not serving) | `UNREACHABLE` (+ scrubbed `excerpt`; next step: run `get_health`) |
| response body larger than 64 MB | `UNEXPECTED_RESPONSE` ("response too large") |
| HTTP 404, unknown diagram/process | `NOT_FOUND` |
| semantic validation (e.g. a stage id for a write tool, `offset + limit > 10000`); schema violations: see "Failure kinds" | `INVALID_INPUT` |
| CLI home missing / script not found / Windows (CLI tools unsupported in this version) | `CLI_UNAVAILABLE` |
| unparseable response or CLI output | `UNEXPECTED_RESPONSE` (+ scrubbed `excerpt`) |
| version line ≠ 8.1 | warning on success; `UNSUPPORTED_VERSION` only where behaviour is unknown |
