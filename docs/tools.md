# Tool reference

The MCP tools of the INUBIT MCP server, for users of an MCP client such as Claude Code. The
authoritative contract is
[contracts/mcp-tools.md](../specs/001-inubit-mcp-mvp/contracts/mcp-tools.md); the JSON schemas
the server announces are in `src/main/resources/schemas/`; the two workspace tools of feature 003
follow [contracts/mcp-tools-delta.md](../specs/003-artifact-workspace/contracts/mcp-tools-delta.md),
the five development tools of feature 004
[their delta](../specs/004-development-stage/contracts/mcp-tools-delta.md).
All fifteen tools are described here; each section quotes the description the server announces in
`tools/list` for a profile `acme` without description and with the default terminology
(Group/Node).

General rules:

- Every tool returns `structuredContent` (matching its output schema) and the same JSON as a
  compact text block.
- A failure of the whole call is `isError: true` with one text block `{"error": ToolError}`
  and no `structuredContent` (see [Common shapes](#common-shapes-page-and-toolerror)). Arguments
  that violate the input schema (unknown property, wrong type, bad id pattern) are rejected
  before the tool runs, with a plain-text validation message.
- Ids: the id of one group (`test`) or of one node (`<group>/<node>`, e.g. `test/node1`), pattern
  `^[a-z0-9][a-z0-9-]{0,31}(/[a-z0-9][a-z0-9-]{0,31})?$`. An id that is not configured gives
  `TARGET_UNKNOWN`, and the message lists all configured group and node ids.
- Field and input names are the same for every profile (feature 002): `group` names the first
  level (e.g. a stage), `node` one INUBIT server (id `<group>/<node>`). This reference uses the
  default display names "group" and "node". The profile's terminology
  ([setup.md](setup.md), "Profile, terminology and credential prefix") changes only display text.
- **Profile prefix and terminology** (feature 002): every tool description starts with
  `[<profile>: <description>] ` (or `[<profile>] ` without a description), so the assistant
  always sees which customer's systems a tool reaches. Descriptions, titles, the `description`
  texts of the schemas and all messages (`message`, `likelyCause`, `nextStep`, warnings) use the
  profile's display names: with `terminology: {group: {singular: Umgebung, plural: Umgebungen},
  node: {singular: Knoten, plural: Knoten}}` the `get_health` description reads "[acme: ACME
  test] Check whether the INUBIT Knoten are reachable and ready: … `target` takes the id of one
  Knoten (`<Umgebung>/<Knoten>`) or of one Umgebung (all its Knoten); omit it for all Knoten."
  The names are inserted exactly as configured (see setup.md). The MCP `initialize` result
  carries `serverInfo.title` "INUBIT MCP – <profile>" and `instructions` naming the profile, its
  description and the terminology.
- The word "group" in the inventory tools (`list_inventory` input `group`, the `group` of an
  inventory item) means INUBIT's own diagram or module group, never the configured group level.
  Likewise, a `node` column inside `query_logs` `fields` or `find_processes` `inubitNode` is
  INUBIT's own value, not the configured node.
- No result contains a URL, username, password or other credential.

| Tool | Purpose | Annotations | Talks to INUBIT |
|---|---|---|---|
| [`list_nodes`](#list_nodes) | which groups and nodes exist | read-only, idempotent, closed world | no (configuration only) |
| [`get_health`](#get_health) | is a node up, ready, in maintenance; version and load | read-only, idempotent, open world | yes (REST) |
| [`find_processes`](#find_processes) | failed, waiting or hanging process instances | read-only, idempotent, open world | yes (REST `queueLog`) |
| [`query_logs`](#query_logs) | INUBIT log entries by time, workflow, severity, process id, text | read-only, idempotent, open world | yes (REST) |
| [`list_inventory`](#list_inventory) | diagrams or modules filtered by name, type, group | read-only, idempotent, open world | yes (diagrams: REST; modules: StartCLI export) |
| [`get_inventory_item`](#get_inventory_item) | one diagram or module: versions, tags, active flag, modules used | read-only, idempotent, open world | yes (REST + StartCLI export with history) |
| [`restart_process`](#restart_process--kill_process) | restart ONE process instance in ERROR on ONE node | **destructive**, non-idempotent, open world | yes (REST state read + StartCLI `processErrorStart`) |
| [`kill_process`](#restart_process--kill_process) | delete ONE process instance on ONE node | **destructive**, non-idempotent, open world | yes (REST state read + StartCLI `kill`) |
| [`export_artifacts`](#export_artifacts) | export technical workflows or modules into the local workspace and its history | read-only for INUBIT, non-idempotent (records a history entry), open world | yes (StartCLI `export`, read-only) |
| [`check_artifacts`](#check_artifacts) | check workspace files offline: structure, a stylesheet run, XML/XSD | read-only, idempotent, open world | only to look up modules missing in the workspace (module list) |
| [`import_artifacts`](#import_artifacts) | import the changed workflows of ONE diagram group (with their changed or new modules), or changed modules, into ONE development node | **destructive**, non-idempotent, open world | yes (StartCLI `export` and `import`) |
| [`restore_backup`](#restore_backup) | re-import the backup of an earlier development call | **destructive**, non-idempotent, open world | yes (StartCLI `export` and `import`) |
| [`set_active`](#set_active) | activate or deactivate ONE workflow on ONE development node | **destructive**, non-idempotent, open world | yes (StartCLI `export` and `import`) |
| [`tag_artifacts`](#tag_artifacts) | tag the head versions of whole diagram groups (and their modules) | **destructive**, non-idempotent, open world | yes (StartCLI history `export` and `tag`) |
| [`run_e2e_test`](#run_e2e_test) | send a SOAP test message and report what INUBIT did | **destructive**, non-idempotent, open world | yes (SOAP endpoint, REST logs and Queue Manager) |

`restart_process` and `kill_process` are **offered only if at least one node has effective
write access** (`write.enabled: true`, and on a `production: true` group also
`write.productionOptIn: true`; see [setup.md](setup.md#write-settings-restart-and-kill)). With the
default configuration the server is read-only and `tools/list` does not contain them.

`export_artifacts` is **offered only if at least one node has a StartCLI installation**
(`cliHome`); `check_artifacts` is always offered.

The development tools of feature 004 are **offered only if at least one node is a development
stage** (`development.enabled: true`, never on production groups; see
[setup.md](setup.md#development-settings)); `run_e2e_test` only if, in addition, such a node's
`e2eTests` is not `FORBIDDEN`. Every other node is refused with `NOT_DEVELOPMENT`.

## Common shapes: Page and ToolError

Tools that target a group return one entry per node (`results[]` or `reports[]`, in
configuration order); a node that fails has an `error` (`ToolError`) instead of its data, the
other nodes are still reported.

**`Page`** — the `page` of `find_processes`, `query_logs` and `list_inventory`:

| Field | Meaning |
|---|---|
| `items` | the entries of this page (at most `limit`) |
| `offset`, `limit` | the requested slice; `limit` is capped by `resultLimits.maxItems` (default 100) |
| `total` | number of all matches (from INUBIT, or of the cached list); absent only if INUBIT did not report it |
| `totalIsLowerBound` | `true` when `total` is absent (the number of matches is unknown, at least `offset` + items) |
| `truncated` | `true` if items were dropped or texts were cut to keep the result within `resultLimits.maxChars` |
| `nextOffset` | the `offset` of the next page; absent on the last page |

**`ToolError`** — `error` of a server entry or of a failed call:

| Field | Meaning |
|---|---|
| `code` | one of the codes below |
| `message` | what failed |
| `likelyCause` | the most likely cause |
| `nextStep` | what to do, e.g. which variable or setting to fix |
| `node` | the node id, when the error belongs to one node |
| `excerpt` | at most 500 chars of the unexpected INUBIT or StartCLI output, scrubbed of secrets |

| `code` | Meaning |
|---|---|
| `TARGET_UNKNOWN` | the group or node id is not configured; the message lists the configured ids |
| `UNREACHABLE` | connection refused, DNS or VPN problem, or HTTP 503 outside maintenance mode |
| `TIMEOUT` | no answer within `timeout` (REST), `cliTimeout` or `cliExportTimeout` (StartCLI); `nextStep` names the setting |
| `TLS_ERROR` | certificate not trusted, pin mismatch or host name mismatch |
| `AUTH_FAILED` | INUBIT rejected the credentials (HTTP 401, StartCLI login failure), a recent rejection is not retried (account-lockout protection), or no credentials are set |
| `FORBIDDEN` | HTTP 403: the account lacks the INUBIT permission or license |
| `MAINTENANCE_MODE` | HTTP 503 while the server is in maintenance mode |
| `NOT_FOUND` | the name, id or process instance does not exist (for `get_inventory_item` with `similarNames`) |
| `INVALID_INPUT` | the arguments are well-formed but not valid (time format, a filter the log type does not support, a group id for a write tool, …) |
| `CLI_UNAVAILABLE` | StartCLI cannot be used: no `cliHome` or JDK, Windows, or an unusable `java.io.tmpdir` |
| `UNEXPECTED_RESPONSE` | INUBIT or StartCLI answered in an unexpected way (status, format); see `excerpt` |
| `WRITE_DISABLED`, `PRODUCTION_PROTECTED`, `CONFIRMATION_INVALID`, `PRECONDITION_FAILED` | refusals of `restart_process` / `kill_process` (see their refusal table); `PRECONDITION_FAILED` also when the workspace is busy (another export or check is running) or not usable |
| `CONFIRMATION_REQUIRED`, `UNSUPPORTED_VERSION` | reserved in the catalogue, not returned by this version: the first write call returns a `challenge`, and 9.x servers run with the 8.1 adapters and a warning |
| `NOT_CONFIGURED` | a setting the tool needs is not configured for this node, e.g. `inventory.owner` for `list_inventory` / `get_inventory_item`; `nextStep` names the setting ("set inventory.owner for <group>/<node> or in defaults") |
| `NOT_DEVELOPMENT` | a development tool was called for a node that is not a development stage |
| `CONFLICT` | the artifacts changed on the server since the export (or since the referenced call), or a workflow is open in Workbench edit mode; nothing was sent, the difference is in `.reports/conflict-<auditId>.diff` |
| `SECRET_UNRESOLVED` | a `${secret:…}` placeholder has no value on the target (names artifact and property path, never a value); nothing was sent |
| `IMPORT_FAILED`, `VERIFY_MISMATCH` | only inside the `failure` of a development tool's result: a StartCLI write failed or its protocol did not match, or the re-export (history export for tags) did not show the intended state |
| `E2E_FORBIDDEN` | `run_e2e_test` on a node whose `e2eTests` is `FORBIDDEN` (the default) |
| `INTERNAL` | an unexpected error inside the MCP server, the server is shutting down, or an audit record could not be written |

## `list_nodes`

> [acme] List the configured INUBIT groups and their nodes (ids `<group>/<node>`, e.g.
> `test/node1`), with production classification and whether write actions are enabled. Call this
> first to learn valid `target` and `node` values.

Lists the configured groups and their nodes, with production classification and whether write
actions are enabled. The assistant calls it first to learn valid
`target` and `node` values.

**Input** (`list_nodes.input.json`): none — `{}`; any property is rejected.

**Output**: `{ "profile": {"name", "description"?}, "terminology": {"group": {"singular",
"plural"}, "node": {"singular", "plural"}}, "groups": [ { "name", "production", "nodes":
[NodeSummary] } ] }`, groups and nodes in configuration order. `profile` and `terminology` name
the profile this server instance serves and its display names; the credential variable prefix
is never part of the result.

```json
{"profile":{"name":"acme","description":"ACME test"},
 "terminology":{"group":{"singular":"Umgebung","plural":"Umgebungen"},"node":{"singular":"Knoten","plural":"Knoten"}},
 "groups":[{"name":"test","production":false,"nodes":[{"id":"test/node1","group":"test","node":"node1","production":false,"writeEnabled":false,"confirmationMode":"SERVER","versionLine":"AUTO","cliAvailable":true}]}]}
```

| `NodeSummary` field | Meaning |
|---|---|
| `id` | `<group>/<node>`, e.g. `test/node1` |
| `group`, `node` | the two parts of the id |
| `production` | the group is configured with `production: true` |
| `writeEnabled` | effective write access: `write.enabled && (!production \|\| write.productionOptIn)` |
| `confirmationMode` | `SERVER` (two-step confirmation by the MCP server) or `CLIENT` |
| `versionLine` | as configured: `AUTO`, `V8_1` or `V9_X` |
| `cliAvailable` | a CLI home is configured and its `bin/startcli.sh` (`.bat` on Windows) exists |

**Example prompt** (quickstart V1): "Which INUBIT systems do you know?" → the groups with their
nodes, e.g. `test/node1`, `test/node2`, `prod/node1`.

## `get_health`

> [acme] Check whether the INUBIT nodes are reachable and ready: maintenance mode, version,
> memory, threads, and blocking queue. `target` takes the id of one node (`<group>/<node>`) or of
> one group (all its nodes); omit it for all nodes.

Checks whether INUBIT nodes are reachable and ready: maintenance mode, version, memory, threads
and blocking queue. Target one node, one group, or omit `target` for all nodes.

**Input** (`get_health.input.json`):

| Property | Type | Default | Meaning |
|---|---|---|---|
| `target` | string (id pattern) | all nodes | the id of one group (all its nodes) or of one node |
| `includeSystemInfo` | boolean | `false` | add the system information block (FR-007) |

**Output**: `{ "reports": [HealthReport] }` — one report per resolved node, in configuration
order. A node that cannot be reached is a report with `reachable: false` and `error`; the call
itself fails only for an invalid or unknown `target`.

| `HealthReport` field | Meaning |
|---|---|
| `node`, `group` | the node id and its group |
| `checkedAt` | ISO-8601; the timestamp of the INUBIT healthcheck (server clock), or the MCP server's clock if INUBIT gave none |
| `reachable` | `/healthcheck` gave any HTTP answer |
| `status` | `OK`, `ERROR` or `UNKNOWN` (from `/healthcheck`) |
| `ready`, `readyMessage` | `/ready`: HTTP 200 = ready, 503 = not ready, with INUBIT's message |
| `maintenanceMode` | from `/healthcheck`; when on, `warnings` says that the server is not processing normally |
| `version` | e.g. `8.1.17`, from `/system/info` |
| `systemInfo` | only with `includeSystemInfo: true`: `version`, `jdk`, `os`, `maxHeap`, `tracingEnabled`, `schedulerThreads`, `raw` (the other name/value pairs, at most 50, values cut to 200 chars) |
| `load` | from `/metrics`: `usedMemoryMb`, `freeMemoryMb`, `maxMemoryMb`, `memoryUsedPercent`, `threadsInUse`, `licensedThreads`, `maxThreads`, `blockingQueueEntries`, `maxBlockingQueueSize`, `blockingQueuePercent` (percentages derived, one decimal) |
| `unavailable` | parts that could not be obtained: `{part, reason, likelyCause?, nextStep?}` with `part` one of `status`, `ready`, `version`, `systemInfo`, `load`; `reason` starts with a code, e.g. `METRICS_NOT_LICENSED: GET /ibis/rest/metrics answered HTTP 403`, `AUTH_FAILED: …`, `TIMEOUT: …`; `likelyCause` and `nextStep` as in a `ToolError` |
| `error` | only when `reachable: false`: `UNREACHABLE` (connection refused, DNS, VPN), `TIMEOUT`, `TLS_ERROR` (certificate, pin, host name), … with `likelyCause` and `nextStep` |
| `warnings` | e.g. maintenance mode, "INUBIT version could not be detected (AUTH_FAILED); assuming 8.1", an unsupported version line |

How the report is built (research R-10):

- `/healthcheck` and `/ready` (both without credentials) start at once and never wait for a
  version detection; the healthcheck alone decides `reachable`. `/system/info` (with
  credentials) runs in parallel; for `versionLine: AUTO` its answer is also the version
  detection. `/metrics?format=json` runs in parallel too once the credentials have been
  accepted, otherwise right after `/system/info`. All nodes are checked in parallel. The whole
  call returns within the largest server `timeout` + 1 s, even if a server hangs (a hanging part
  is reported as `TIMEOUT`).
- **Account-lockout protection**: when INUBIT rejects a server's credentials (HTTP 401, or a
  StartCLI login failure), every authenticated call to that server fails for 60 s with
  `AUTH_FAILED` "authentication failed recently; not retried for N s to avoid account lockout",
  without contacting INUBIT. At most one failed login per server per 60 s; reachability,
  readiness and maintenance mode are still reported. Both the first `AUTH_FAILED` (HTTP 401)
  and the cached one name the concrete variables of the server, e.g. "Fix
  `INUBIT_DEV_NODE1_PASSWORD` (or `INUBIT_DEV_PASSWORD`) and `_USERNAME`, then restart Claude
  Code". The guard is shared by all tools of a server (REST and StartCLI).
- **First call after start (trade-off)**: until INUBIT has accepted a server's credentials once,
  its authenticated calls run one after the other, so `/metrics` starts only when
  `/system/info` has answered. On a very slow server the first `get_health` can therefore report
  `load` as `TIMEOUT` although `/metrics` itself would have answered; the reason then names the
  budget that was left, e.g. `TIMEOUT: No answer from dev/node1 to the metrics call within the
  remaining 400 ms of the 2 s budget (it ran after the system information call because the
  credentials are not confirmed yet)`, or says that the call did not start at all. The next call
  runs both in parallel. This is deliberate: wrong credentials must cost one failed login, not
  two.
- Any failure of a part is reported in `unavailable` while the rest is still returned (FR-008).
  Wrong credentials therefore show up as `AUTH_FAILED` for `version` and `load`, while
  reachability, readiness and maintenance mode are still reported.
- A `/metrics` failure other than 401, 503, timeout or unreachable (e.g. 402/403/404/500 or a
  body without the JSON figures) is reported as `METRICS_NOT_LICENSED`: the metrics depend on an
  INUBIT license entry.

**Example prompts** (quickstart V2, V3):

- "Is dev up?" → `get_health` with `target: "dev"`: reachable, ready, maintenance flag, version
  and load figures (or `unavailable: load` with the reason) within 5 s.
- "Health of all systems?" → `get_health` without `target`: one report per node. A node
  that cannot be reached (e.g. `dev/bogus` with `baseUrl: https://127.0.0.1:9`) is reported as
  `reachable: false` with `error.code: UNREACHABLE`, the others normally, within its timeout + 1 s.
- "Show the system information of qa/node1" → `includeSystemInfo: true`.

## `find_processes`

> [acme] Find process instances on INUBIT nodes, e.g. failed (ERROR) or hanging ones, filtered by
> workflow, state, and time. Use the returned processId and time range with `query_logs` to find
> the cause.

Finds process instances in the INUBIT Queue Manager: failed (`ERROR`), waiting, queued or
processing ones, optionally only the hanging ones, filtered by workflow, tag and time. Backed by
the REST Queue Manager view `POST /ibis/rest/log/queueLog` (research R-8; the same entries as
`ps -csv -a`); INUBIT filters, sorts, pages and counts. No CLI installation is needed.

**Input** (`find_processes.input.json`):

| Property | Type | Default | Meaning |
|---|---|---|---|
| `target` | string (id pattern), required | — | a group (all its nodes) or one node |
| `states` | array of `ERROR`, `ACTIVE`, `WAITING`, `QUEUED` | all states | the states to list |
| `hangingOnly` | boolean | `false` | only hanging instances (see below) |
| `hangingThresholdMinutes` | integer 1–10080 | the server's `hangingThreshold`, else 60 | threshold for `hanging` |
| `workflow` | string ≤ 200 | — | exact workflow name (`EQUAL`; spaces allowed) |
| `tag` | string ≤ 100 | — | exact tag |
| `since`, `until` | string | — | ISO-8601 with offset (`2026-10-01T08:00:00Z`, `…+02:00`) or a duration back from now (`PT24H`, `P7D`); both bounds inclusive |
| `offset` | integer 0–9999 | 0 | paging; `offset + limit` ≤ 10,000 |
| `limit` | integer 1–100 | 50 | page size (capped by `resultLimits.maxItems`) |

**Output**: `{ "results": [ { "node", "page"?: Page<ProcessInstance>, "error"?: ToolError } ] }`
— one entry per resolved server in configuration order; a server that fails (e.g.
`UNREACHABLE`, `AUTH_FAILED`) has only `error`, the others are still reported. Items are sorted
by `since`, newest first.

| `ProcessInstance` field | Meaning |
|---|---|
| `node` | the node (server) id |
| `processId` | INUBIT `workflowId` (digits): the id that restart/kill take; use it with `query_logs(processId=…)` |
| `globalProcessId` | `globalPId`: equals `processId` for top-level processes, the caller's id or a UUID for sub-workflow rows |
| `state`, `rawState` | normalized state and the INUBIT status (state table below) |
| `since` | ISO-8601: when the entry entered its current state; for `ERROR` the time of the error |
| `timeInState` | ISO-8601 duration `now − since` (e.g. `PT2H5M`) |
| `hanging` | see below |
| `workflow`, `module`, `moduleType`, `tag`, `inubitNode`, `owner`, `priority` | the Queue Manager columns (absent when empty); `inubitNode` is the Queue Manager column `node` |

The same `processId` can occur in several rows (one per workflow/module of a process that called
sub-workflows); the rows stay separate.

**State table** (spike S-4):

| INUBIT status (`rawState`) | `state` | counts for `hanging` |
|---|---|---|
| `Error` | `ERROR` | no — waits for restart or kill; never hanging |
| `Waiting`, `Retry` | `WAITING` | yes |
| `Queued` | `QUEUED` | yes |
| `Processing` | `ACTIVE` | yes |
| anything else | `OTHER` | no |

**Hanging** (FR-010): an instance is `hanging` when its state is `ACTIVE`, `WAITING` or `QUEUED`
and the exact time since `since` (not the `timeInState` shown, which is cut to seconds) exceeds
the threshold — `hangingThresholdMinutes` of the request, else the
server's `hangingThreshold` from the configuration, else 60 minutes. `hangingOnly: true` asks
INUBIT only for the raw states `Processing`, `Waiting`, `Retry` and `Queued` (intersected with
`states`) that entered their state before `now − threshold`; `states: [ERROR]` with
`hangingOnly: true` is an empty page without any request.

**Paging** (research R-9): with at most one raw status value the request uses INUBIT's paging
(`startIndex = offset`, `noOfItems = limit`) and its `total`. When the states expand to several
raw values (`WAITING` = `Waiting` + `Retry`, or several `states`), one request per raw value
fetches the first `offset + limit` rows; they are merged newest first and sliced, and `total` is
the sum of the totals. `offset + limit > 10000` → `INVALID_INPUT`. The page is further bounded by
the result size limit (`truncated`, `nextOffset`).

**Example prompts** (quickstart V4, V5):

- "Show failed processes on dev in the last 24 hours" → `find_processes(target: "dev",
  states: ["ERROR"], since: "PT24H")`: instances with `processId`, `workflow`, `module`, `since`.
- "Which processes on qa have been hanging for more than 2 hours?" →
  `find_processes(target: "qa", hangingOnly: true, hangingThresholdMinutes: 120)`.
- "Why did process 110219899 fail?" → `find_processes` for module and `since`, then
  `query_logs(target: "dev/node1", logType: "systemLog", processId: "110219899")`.

## `query_logs`

> [acme] Read INUBIT log entries (systemLog = workflow executions, queueLog, auditLog, schedulerLog,
> connectionLog, keyManagerLog, webserviceManager) filtered by time, workflow, severity, process
> ID, or text. `text` is a case-sensitive substring match in which `%` matches any sequence and
> `_` any single character. Newest first, paginated.

Reads INUBIT log entries, newest first and paginated: `systemLog` (workflow executions),
`queueLog`, `auditLog`, `schedulerLog`, `connectionLog`, `keyManagerLog`, `webserviceManager`.
Backed by `POST /ibis/rest/log/<logType>?format=json` with an XML `<logRequest>` (research R-9).

**`processLog` is not offered**: on INUBIT 8.1.17 the endpoint fails (GET → HTTP 500, POST →
HTTP 400; spike S-5). Workflow executions are recorded in `systemLog` — use that instead.

**Input** (`query_logs.input.json`):

| Property | Type | Default | Meaning |
|---|---|---|---|
| `target` | string (id pattern), required | — | a group or one node |
| `logType` | enum of the 7 log names, required | — | `processLog` or an unknown name is rejected by the schema, which lists the supported names |
| `since`, `until` | string | — | as for `find_processes`, on the log type's time field |
| `workflow` | string ≤ 200 | — | exact workflow name |
| `processId` | string `^[0-9A-Za-z_-]{1,64}$` | — | digits → `workflowId`, anything else (UUID) → `globalPId` |
| `severity` | array of `ERROR`, `WARN`, `INFO`, `DEBUG` | — | see "Severity" |
| `text` | string ≤ 200 | — | `LIKE` on the log type's text field, see "Text" |
| `offset`, `limit` | integers | 0, 50 | as for `find_processes` (`offset` 0–9999, `limit` 1–100, `offset + limit` ≤ 10,000) |

**Output**: `{ "results": [ { "node", "page"?: Page<LogEntry>, "error"?: ToolError } ] }`.

| `LogEntry` field | Meaning |
|---|---|
| `node`, `logType` | where the entry comes from |
| `timestamp` | ISO-8601 value of the log type's time field (absent for `webserviceManager`) |
| `severity`, `rawSeverity` | derived severity (`ERROR`, `WARN`, `INFO`, `OTHER`; the schema also allows `DEBUG`, which INUBIT 8.1 never yields) and the INUBIT value it was derived from |
| `workflow`, `module`, `processId` | `workflowName`, `moduleName`, `workflowId`, where the log type has them |
| `message` | the log message (systemLog, auditLog), at most 2,000 chars |
| `fields` | the remaining non-empty columns in INUBIT's order, as strings, each at most 200 chars; epoch-millisecond columns such as `endTime` as ISO-8601 |

Size bounds: every entry is at most 4,000 chars as JSON (measured after JSON escaping, so
control characters, quotes and backslashes count with their escapes). Cut text ends with
`…[truncated]`; if an entry is still too large, trailing `fields` are dropped, then the message
is cut further, then `workflow`, `module`, `processId` and `rawSeverity` are shortened. Entries
are never rejected for their size. Any cut sets `page.truncated`. The same holds for
`ProcessInstance` items of `find_processes` (optional texts are shortened first).

**Per-log-type filters** (INUBIT 8.1.17, spike S-5). A filter that the log type does not support
→ `INVALID_INPUT` naming the supported filters for that log type, before INUBIT is contacted
(INUBIT itself would answer HTTP 400/500).

| `logType` | time field (`since`/`until`, sorting) | `workflow` | `processId` | `severity` | `text` |
|---|---|---|---|---|---|
| `systemLog` | `startTime` | `workflowName` | `workflowId` / `globalPId` | `ERROR`, `INFO` | `message` |
| `queueLog` | `startTime` | `workflowName` | `workflowId` / `globalPId` | `ERROR`, `WARN`, `INFO` | `moduleName` |
| `auditLog` | `time` | — | — | `ERROR`, `INFO` | `message` |
| `schedulerLog` | `nextStartTime` | `workflowName` | — | — | `moduleName` |
| `connectionLog` | `lastConnection` | — | — | — | `systemType` |
| `keyManagerLog` | `validity` (certificate expiry) | — | — | — | `name` |
| `webserviceManager` | — (INUBIT's order) | `workflowName` | — | — | `moduleName` |

**Severity**: INUBIT log rows have **no severity field**. `severity` is derived from the success
or status field of the log type (data-model.md → LogEntry), and the `severity` filter is
translated into the same field; values not in the table → `INVALID_INPUT`:

| `logType` | source field | `ERROR` | `WARN` | `INFO` |
|---|---|---|---|---|
| `systemLog`, `auditLog` | `success` | `false` | — | `true` |
| `queueLog` | `status` | `Error` | `Waiting`, `Retry` | `Queued`, `Processing` |
| the other log types | — | — | — | — (`severity` is always `OTHER`) |

`DEBUG` is never supported on 8.1. A severity with several raw values (e.g. `WARN` on queueLog)
or several severities are one request per raw value, merged by the paging rule of
`find_processes`.

**Text**: `text` is a **case-sensitive substring** match (`LIKE`) on the text field of the log
type. `%` matches any sequence and `_` any single character; they cannot be escaped, so a
literal `%` or `_` in the search text also acts as a wildcard (`G_IP` matches `GZIP`). `*` is a
literal character. Control characters other than tab, line feed and carriage return (e.g.
U+0001) cannot be sent to INUBIT (its queries are XML) and give `INVALID_INPUT`; the same holds
for `workflow`, `tag` and `processId` of both tools.

Validation errors that do not depend on a server (schema bounds, time format, an unsupported
filter, an unsendable character) fail the whole call with `INVALID_INPUT` and carry no `node`.
Times must fit INUBIT's epoch milliseconds (e.g. `+999999999-12-31T23:59:59Z` or
`P106751991167300D` are `INVALID_INPUT`).

**Example prompts** (quickstart V5, V6):

- "Why did process 110219899 fail?" → `query_logs(target: "dev/node1", logType: "systemLog",
  processId: "110219899", severity: ["ERROR"])`, or with the `workflow` from `find_processes`
  and a `since` a few minutes **before** the instance's `since`: systemLog filters on
  `startTime`, the start of the execution, which precedes the time of the error that
  `find_processes` reports. systemLog rows carry no module; the failing module comes from
  `find_processes`, the error message from systemLog.
- "Show the last 500 systemLog errors" → `query_logs(target: "dev", logType: "systemLog",
  severity: ["ERROR"], limit: 100)`: the first page, `total` and `nextOffset`; ask for the next page with
  `offset: <nextOffset>`.
- "Which certificates in the key manager expire before the end of the year?" →
  `query_logs(target: "dev", logType: "keyManagerLog", until: "2026-12-31T23:59:59Z")`.

## Inventory: owner, groups, caching

`list_inventory` and `get_inventory_item` (user story 3) show what is deployed on a node.

- **Owner**: every lookup is restricted to the owning Workbench user or user group of the node,
  `inventory.owner` (no built-in default; set it in `defaults`, per group or per node, see
  [setup.md](setup.md#inventory-settings)). Diagrams or modules of another owner are not found.
  A node without an owner gets the per-node error `NOT_CONFIGURED` ("set inventory.owner for
  <group>/<node> or in defaults") and is not contacted; the other nodes of a group target are
  listed normally, and the other tools are unaffected.
- **Diagram group vs. module group**: a diagram's `group` is its diagram group in the Workbench
  (e.g. `GRP-41`). A module's `group` is its module group, which INUBIT names
  after the plugin type (e.g. `XSLT Converter`, `HTTP Connector`); a module's `type` is the
  plugin type (`PluginName`) as well.
- **Sources** (INUBIT 8.1, research R-11): diagrams come from REST (`/model/models`,
  `/model/modelByName`, `/model/export`); the module list and every version history need
  StartCLI exports (`export … --includeHistory`, `export --exportModule ''`), which run into a
  private (`rwx------`) directory `inubit-mcp-export-<profile>-<pid>-<random>` below
  `java.io.tmpdir` that is deleted afterwards (setup.md, "Several profiles side by side").
  Without a CLI home (or on Windows) the module list is `CLI_UNAVAILABLE`
  and a diagram's `versions` are reported in `unavailable`. The exports use `cliExportTimeout`
  (default 120 s); a timed-out export names that setting in its `nextStep`.
- **Module usage** (which workflows use a module; research R-11, T126): INUBIT 8.1's module
  export names a workflow only for **connector** modules (the workflow the connector is bound
  to). So the server builds a **usage index** per server: it reads the nodes of every technical
  workflow of the owner (`/model/modelByName`; one at a time until the first read succeeds,
  then 8 at a time, and no new read after a rejected login — at most one failed login; all within
  `cliExportTimeout`; on
  DEV 392 workflows in about 2–3 s) — a node names the module it runs — and joins them with the
  connectors' own workflow. Module results carry `workflows` and `workflowCount` and, per
  server, `usageComplete`. If a workflow could not be read (or not in time), or the diagram
  list failed, `usageComplete` is `false` and an empty `workflows` list does **not** mean
  "unused"; such an incomplete index is not cached, the next call builds it again. Module names
  are unique per owner on DEV; if two modules of the owner shared a name, only nodes of the
  module's plugin type (`tw<PluginName>`, e.g. `twXSLTConverter`) would count.
- **Login before an export**: while a server's credentials are not yet confirmed, an export is
  preceded by one cheap authenticated REST call (`/system/info`), so that the long export does
  not block the server's other REST calls behind the one-login-at-a-time lockout protection; a
  rejected login stops the export before StartCLI starts.
- **Shutdown**: when the MCP client closes the connection or the server is terminated (SIGTERM),
  running StartCLI exports are stopped (killed after 2 s) and their directories deleted. After a
  hard kill (SIGKILL) the next server start deletes the directories left behind by dead server
  processes of the same user.
- **Duration and cache**: the first module listing of a server takes about **10–15 s** (module
  export of ~1,600 modules on DEV plus the usage index); a version history about 5 s per
  diagram group. Diagram lists, module lists, the usage index and the version history per
  diagram group are cached per server for `inventory.cacheTtl` (default 10 minutes). Every result carries `collectedAt`, the time the
  (oldest) cached data was collected; pass `refresh: true` to collect it again. Concurrent
  requests for the same data share one export.
- **No "active version"**: INUBIT 8.1 exposes no marker for an active version. The tools show
  the version history (number, check-in user and time, comments, tags) and the `active` flag of
  the diagram or module, and never infer an active version. Tags are the *current* assignment:
  a tag that was moved to another version later appears only there (the tag history is only in
  `auditLog`).

## `list_inventory`

> [acme] List diagrams (technical workflows, BPDs, process maps, …) or modules on one INUBIT node
> or on all nodes of one group, filtered by name, type, or INUBIT diagram/module group.

Lists diagrams (technical workflows, BPDs, process maps, …) or modules of the owner on one node
or all nodes of a group, filtered by name, type or INUBIT diagram/module group, sorted by name and paginated.

**Input** (`list_inventory.input.json`):

| Property | Type | Default | Meaning |
|---|---|---|---|
| `target` | string (id pattern), required | — | a group or one node |
| `kind` | `DIAGRAM` or `MODULE`, required | — | what to list |
| `nameContains` | string ≤ 200 | — | case-insensitive substring of the name |
| `type` | string ≤ 100 | — | diagrams: `technical`, `bpd`, `processmap`, `organigram`, `systemdiagram`, `constraintsdiagram`; modules: the plugin type, e.g. `XSLT Converter` (case-insensitive) |
| `group` | string ≤ 200 | — | diagram group, or module group (= plugin type) (case-insensitive) |
| `offset`, `limit` | integers | 0, 50 | `offset` ≥ 0, `limit` 1–100 |
| `refresh` | boolean | `false` | bypass the cache |

**Output**: `{ "results": [ { "node", "collectedAt"?, "usageComplete"?, "page"?:
Page<InventoryItem>, "error"?: ToolError } ] }`, one entry per server in config order.
`collectedAt` (ISO-8601) is present together with `page` (for modules: the older of the module
list and the usage index); `usageComplete` only for `kind: MODULE` — whether every technical
workflow of the owner could be read for the usage; a failed server has only `error`.

| `InventoryItem` field | Diagram | Module |
|---|---|---|
| `node`, `kind` | node id, `DIAGRAM` | node id, `MODULE` |
| `name` | diagram name | module name |
| `type` | diagram type | plugin type |
| `group` | diagram group | module group (= plugin type) |
| `owner` | `inventory.owner` | `inventory.owner` |
| `active` | — (see `get_inventory_item`) | `IsActive` |
| `lastChange` | — | last content change of the module (`LastUpdate`), not its last check-in |
| `workflows` | — | technical workflows that use the module, sorted by name, **at most 5** (all in `get_inventory_item`); `[]` = not used, if `usageComplete` |
| `workflowCount` | — | number of all using workflows |

Texts are cut to 200 chars (`…[truncated]`, sets `page.truncated`); every item is at most 4,000
chars as JSON (if needed, listed workflow names are dropped first; `workflowCount` stays).

**Example prompts** (quickstart V7, V7a):

- "List technical workflows in group GRP-41 on dev" → `list_inventory(target: "dev",
  kind: "DIAGRAM", type: "technical", group: "GRP-41", limit: 100)`.
- "Which XSLT Converter modules are not used by any workflow on dev?" →
  `list_inventory(target: "dev", kind: "MODULE", type: "XSLT Converter", limit: 100)`, all pages
  (`nextOffset`), and pick the items with `workflowCount: 0` — valid only if `usageComplete` is
  `true` (the first call takes 10–15 s, later ones come from the cache).

## `get_inventory_item`

> [acme] Show details of one diagram or module: version history (version, check-in user and time,
> comment, tags), active flag, modules used (diagrams), last change. Call it with the id of one
> group to compare its nodes.

Shows the details of one diagram or module per server, in the same structure on every server so
that a group target compares them field by field.

**Input** (`get_inventory_item.input.json`): `target` (required), `kind` (`DIAGRAM` or `MODULE`,
required), `name` (exact, case-sensitive, 1–200 chars, required), `refresh` (default `false`).

**Output**: `{ "results": [ { "node", "collectedAt"?, "item"?: InventoryDetail, "error"?:
ToolError } ] }`.

| `InventoryDetail` field | Diagram | Module |
|---|---|---|
| `node`, `kind`, `name`, `type`, `group`, `owner` | from the diagram list; `owner` from the export | from the module list |
| `active` | `IsActive` of the head version (REST export) | `IsActive` |
| `lastChange` | check-in time of the newest version | last content change (`LastUpdate`) |
| `workflows`, `workflowCount` | — | **all** technical workflows that use the module (sorted by name) and their number; the count stays if the list is cut |
| `usageComplete` | — | `true` if every technical workflow could be read; only then does `workflows: []` mean "not used" |
| `checkinComment`, `userComment` | check-in comment of the head version | check-in and user comment of the module |
| `versions` | newest first: `version`, `checkinUser`, `checkinAt` (ISO-8601; INUBIT stores Europe/Berlin local time), `checkinComment`, `userComment`, `tags` | the same, from the history export of the group of the **first** using workflow (in the order of `workflows`) that is in the diagram list |
| `modules` | the modules (nodes) used: `name`, `type`, `nodeId` | — |
| `connector` | — | `input`, `output`, `scheduled` |
| `unavailable` | parts that could not be obtained, e.g. `{ "part": "versions", "reason": "CLI_UNAVAILABLE: …", likelyCause, nextStep }` | same; also without a using workflow: `NOT_FOUND` "not used by any technical workflow" if `usageComplete`, otherwise the reason the usage index is incomplete (INUBIT exports module history only with the group of a workflow that uses it) |
| `truncated` | true if texts, the oldest versions or list entries were cut to keep the result bounded | |

- **Not found**: `error.code = NOT_FOUND` and an `item` with `node`, `kind`, `name`, `owner`
  and up to five `similarNames` (case-insensitive: names containing the requested one, names of
  at least 3 chars contained in it, or names within an edit distance of `max(2, length / 3)`;
  closest first). The other nodes of a group are not affected.
- A REST failure (diagram list, nodes, export) of a diagram detail fails that server (`error`);
  a failed or timed-out version history only adds an `unavailable` entry, a failed usage index
  only `usageComplete: false`.
- **Deadlines per server** (a stopped export then is a partial result, not a server
  `TIMEOUT`); `export` = `cliExportTimeout` + 4 s to stop StartCLI, `t` = `timeout`, plus 1 s
  grace:

  | Call | Deadline |
  |---|---|
  | `list_inventory` `DIAGRAM` | 2 t |
  | `list_inventory` `MODULE` | export + `cliExportTimeout` (usage index) + 3 t |
  | `get_inventory_item` `DIAGRAM` | export + 5 t |
  | `get_inventory_item` `MODULE` | 2 × export + `cliExportTimeout` (usage index) + 4 t |
- Bounds: texts are cut to 200 chars; if the detail exceeds the server's share of
  `resultLimits.maxChars`, the list of `workflows` is cut to a quarter of it first
  (`workflowCount` stays), then the oldest versions are dropped (`truncated: true`).

**Example prompts** (quickstart V8):

- "Show the version history of Example-Workflow on qa" → `get_inventory_item(target: "qa",
  kind: "DIAGRAM", name: "Example-Workflow")`: one entry per QA server with versions, tags and
  `active`.
- "Where is the module Example-Mapping used?" → `get_inventory_item(target: "dev", kind:
  "MODULE", name: "Example-Mapping")`: `workflows`, `workflowCount`, `usageComplete` and the
  module's versions.

## `restart_process` / `kill_process`

Change the state of **one** process instance on **one** node (US4). Bulk operations do not
exist. Both tools are annotated `destructiveHint: true`, `idempotentHint: false`,
`readOnlyHint: false`, so that clients ask before calling them.

- `restart_process`: "[acme] Restart ONE process instance that is in ERROR state on ONE INUBIT
  node. Changes production data flow. On nodes with `confirmationMode` `SERVER` (the default),
  the first call only returns a preview and a confirmationCode; call again with the code to
  execute." (StartCLI `processErrorStart <processId>`)
- `kill_process`: "[acme] Delete (kill) ONE process instance on ONE INUBIT node. Irreversible.
  Same two-step confirmation as restart_process." (StartCLI `kill <processId>`)

**Input** (`restart_process.input.json`, `kill_process.input.json`):

| Property | Rules |
|---|---|
| `node` (required) | the id of exactly one node `<group>/<node>`, pattern `^[a-z0-9][a-z0-9-]{0,31}/[a-z0-9][a-z0-9-]{0,31}$`; group ids are rejected (write actions never target all nodes of a group) |
| `processId` (required) | the decimal Queue Manager id without leading zero, pattern `^[1-9][0-9]{0,18}$`: `processId` from `find_processes` (queueLog `workflowId`). A UUID `globalProcessId` is not accepted |
| `confirmationCode` | the code of the preview, pattern `^[A-Za-z0-9_-]{22}$` |
| `reason` | why the action is taken, at most 500 chars; stored in the audit log |

**Output**: either `{ "challenge": ConfirmationChallenge }` (preview step) or
`{ "result": ProcessControlResult }` (execution). Refusals are tool errors (`isError: true`).

| `ConfirmationChallenge` field | Meaning |
|---|---|
| `confirmationCode` | one-time code (16 random bytes, URL-safe Base64, 22 chars), bound to this server, action and `processId` |
| `expiresAt` | ISO-8601 UTC; issue time + `confirmationTtl` (default 5 minutes) |
| `preview` | `node`, `action` (`RESTART`/`KILL`), `processId`, `workflow`, `module`, `state`, `since` of the instance as the Queue Manager shows it now (for a restart: the row in `ERROR`) |
| `message` | what to do next: show the preview to the user, then call again with the code |

| `ProcessControlResult` field | Meaning |
|---|---|
| `node`, `action`, `processId` | what was done |
| `outcome` | `EXECUTED` (INUBIT confirmed the action) or `FAILED` (it did not; see `message`). `REFUSED` is part of the data model but refusals are returned as tool errors |
| `stateBefore` | state read just before the action |
| `stateAfter` | re-read after the action: `ERROR`, `ACTIVE`, `WAITING`, `QUEUED`, `OTHER`, or `NOT_IN_QUEUE` if the instance left the Queue Manager; absent if the re-read failed |
| `message` | INUBIT's confirmation text, or the failure (`CODE: message`, likely cause, next step, excerpt) |
| `auditId` | id of the execution's audit records |

**Decision flow** (every step that ends the flow writes an audit record):

1. `node` must be one configured node: a group id → `INVALID_INPUT`, an unknown id →
   `TARGET_UNKNOWN`. (Malformed ids are already rejected by the input schema.)
2. Production group without `write.productionOptIn` → `PRODUCTION_PROTECTED`, **whatever
   `write.enabled` says**; also a production server with `write.confirmation: CLIENT` (normally
   already a startup error).
3. `write.enabled: false` (the default) → `WRITE_DISABLED`; `nextStep` says how to enable it.
4. No usable StartCLI (no `cliHome`, no JDK, Windows) → `CLI_UNAVAILABLE`; no credentials →
   `AUTH_FAILED`.
5. The current state is read from the Queue Manager (REST `queueLog`, `workflowId EQUAL
   <processId>`). No entry → `NOT_FOUND` (finished, already restarted/killed, or wrong id).
   `restart_process` needs a row in `ERROR`; otherwise `PRECONDITION_FAILED` with the actual
   state(s). `kill_process` accepts any state. A second call for the same instance while one runs
   → `PRECONDITION_FAILED`.
6. Confirmation `SERVER` (default): without `confirmationCode` the call **changes nothing** and
   returns the `challenge`. With a code, the code must be known, unexpired and issued for the same
   server, action and `processId`; otherwise `CONFIRMATION_INVALID`. The code is also bound to
   the **previewed state**: if the instance row now differs from the preview (state, raw state,
   `since`, workflow or module — e.g. it was restarted and failed again), the call is refused
   with `PRECONDITION_FAILED` naming previewed and actual state. A code is single use: it is
   consumed by the first call that presents it, also if that call is refused. At most 1000 codes
   can be pending; a further preview is `PRECONDITION_FAILED` ("too many pending
   confirmations"). Confirmation `CLIENT`: the action runs on the first call; the client is
   responsible for asking the user (never allowed on production groups).
   An unexpected internal error in steps 1–6 is an audited `INTERNAL` refusal.
7. An audit record `PENDING` is written and forced to disk **before** StartCLI runs; if it cannot
   be written, nothing is executed (`INTERNAL`).
8. StartCLI runs with `cliTimeout`; the state is re-read; a final audit record `EXECUTED` or
   `FAILED` follows. A StartCLI failure (`NOT_FOUND`, `AUTH_FAILED`, `TIMEOUT`,
   `UNEXPECTED_RESPONSE` …) is a result with `outcome: FAILED`, not a tool error, so that the
   audit id and the re-read state are shown.

Because the state is read on every call, an instance that completes or changes state between
preview and confirmation is reported (`NOT_FOUND` / `PRECONDITION_FAILED`) and not touched.

| Refusal code | When |
|---|---|
| `INVALID_INPUT` | a group id instead of a node id; malformed `processId`, code or reason |
| `TARGET_UNKNOWN` | the server is not configured |
| `PRODUCTION_PROTECTED` | production group without `write.productionOptIn`, or with `write.confirmation: CLIENT` |
| `WRITE_DISABLED` | `write.enabled` is not `true` for the server |
| `CLI_UNAVAILABLE` | StartCLI cannot be used for the server |
| `NOT_FOUND` | the instance is not (or no longer) in the Queue Manager |
| `PRECONDITION_FAILED` | restart of an instance that is not in `ERROR`; the instance changed since the preview; the same instance is being changed by another call; too many pending confirmations |
| `CONFIRMATION_INVALID` | the code is unknown, used, expired, or for another server, action or process |
| `INTERNAL` | the audit record could not be written, or an unexpected error occurred before the action (nothing was executed; audited if possible) |
| `UNREACHABLE`, `TIMEOUT`, `AUTH_FAILED`, `TLS_ERROR`, … | the state read failed; nothing was executed |

Every call that reaches the tool — refused, previewed, executed or failed — is recorded in the
audit log (see [setup.md](setup.md#audit-log)). Arguments that violate the input schema (a group
instead of a server, a malformed `processId` or `confirmationCode`, a `reason` over 500 chars,
an unknown property) are rejected by the MCP SDK before the tool runs and are therefore **not
audited**; nothing can be changed by them.

**Example prompt** (quickstart V10): "Restart process 110219899 on dev/node1" →
`restart_process(node: "dev/node1", processId: "110219899")` returns the preview and a code;
after the user confirms, `restart_process(node: "dev/node1", processId: "110219899",
confirmationCode: "…")` returns `outcome: EXECUTED` with `stateAfter`.

## The workspace (feature 003)

`export_artifacts` and `check_artifacts` work on the profile's **workspace**, a local directory
with its own git history (setting `workspace`, default `~/.inubit-mcp/<profile>/workspace`; see
[setup.md](setup.md#artifact-workspace)). Only one export or check runs at a time: a second call
is refused at once with `PRECONDITION_FAILED` ("another export or check is running"), also across
the processes of one profile. Paths in results are workspace-relative with `/`. Fields without a
value are **absent, never `null`**.

## `export_artifacts`

> [acme] Export technical workflows (by diagram group) or single modules from one group or node
> into the local workspace as readable files, record the export in the workspace history and list
> what changed. Only technical workflows are exported (no system diagrams or other diagram types).
> Secrets are replaced by placeholders. Read-only for INUBIT.

Runs one StartCLI `export` per diagram group or module (`--exportWorkflowType 'technical'`
always), reads, redacts and splits the archive **in memory**, then writes the files of the
exported diagram groups and modules and records one history entry. Before the export, uncommitted
changes in the workspace are recorded as their own entry `local changes: <n> files`, so nothing
of yours is lost. Every secret (passwords, encrypted values, keystores, key material, saved test
messages, `xslt.sourceVariables`) is replaced by `${secret:<property path>}`; key material in a
repository file is not written at all. The history is never pushed anywhere.

**Input** (`export_artifacts.input.json`):

| Property | Rules |
|---|---|
| `target` (required) | id of one group (its first node is used and named in the result) or one node |
| `owner` | INUBIT user or user group; default `inventory.owner` of the node |
| `diagramGroups` | 1–20 diagram group names (letters, digits, `_ . -` and spaces) |
| `modules` | 1–50 `{name, pluginType?}`; without `pluginType` it is looked up in the module list |
| — | exactly one of `diagramGroups` / `modules` |

**Output** (`export_artifacts.output.json`):

| Field | Meaning |
|---|---|
| `node`, `owner`, `workspace` | where it came from and where it went (absolute workspace path) |
| `localChanges` | `{commit, files}` — only if uncommitted changes were recorded first |
| `commit` | the history entry of the export; absent if `unchanged` |
| `unchanged` | `true` if the export changed no file (an unchanged re-export records nothing) |
| `counts` | `{added, modified, deleted}` over all changed files |
| `changes` | `[{path, kind}]`, at most `resultLimits.maxItems` |
| `truncated`, `fullList` | `fullList` (`.reports/export-<commit>.txt`, all changes) only when `truncated` |
| `secretsReplaced` | number of values replaced by placeholders (count only) |
| `warnings` | e.g. a workflow in edit mode (`CheckoutUser`), used modules of another owner that were not exported, repository files with key material that were not written, values with a secret-like name that were kept |

Directory layout: `<group>/<owner>/workflows/<diagram group>/<workflow>.xml`,
`<group>/<owner>/modules/<plugin type>/<module>/{module.xml, index.xml, <property>.<ext>}`,
`<group>/<owner>/repository/<repository path>`, volatile values (UIDs, export suffix of check-in
comments, archive context) under `.meta/`. Files of artifacts no longer exported are removed.

**Errors**: `TARGET_UNKNOWN`; `INVALID_INPUT` (neither/both of `diagramGroups`/`modules`, a blank
or unsupported name, two artifacts whose paths differ only in case); `NOT_CONFIGURED` (no owner);
`CLI_UNAVAILABLE`, `AUTH_FAILED`, `TIMEOUT` (names `cliExportTimeout`); `NOT_FOUND` (diagram group
or module does not exist for the owner); `UNEXPECTED_RESPONSE` (the archive cannot be processed —
the workspace is unchanged); `PRECONDITION_FAILED` (workspace busy or not usable; a failed write
is undone from the history); `INTERNAL`.

**Example prompt**: "Export diagram group GRP-01 of dev" → `export_artifacts(target: "dev",
diagramGroups: ["GRP-01"])`.

## `check_artifacts`

> [acme] Check workspace files before an import: workflow structure (edges, ids, branch
> conditions, referenced modules, variables, repository references), run a stylesheet against an
> input file, or validate XML against a schema. Never changes INUBIT; writes only test outputs.

Works offline on the workspace; only module names missing in the workspace are looked up in the
module list of the group's first node. Writes only below `.tests/` (stylesheet outputs) and
`.reports/` (full finding lists).

**Input** (`check_artifacts.input.json`):

| Property | Rules |
|---|---|
| `paths` | 1–200 workspace-relative files or directories: every workflow, module and repository file below them is checked, every XML document (`.xml .xsd .xsl .xslt .wsdl`) for well-formedness |
| `xslt` | `{stylesheet, input, params?, now?}`: run a workspace stylesheet on a workspace input; `now` (ISO-8601) is what the date functions return |
| `schema` | workspace-relative XSD; with `paths`, each `.xml` file is validated against it |
| `verifyOnServer` | default `true`: look up modules missing in the workspace on the group's first node |
| — | at least one of `paths` / `xslt`; every path stays inside the workspace (no `..`, no absolute path, no symbolic link leaving it) |

**Output** (`check_artifacts.output.json`): `counts` (`ERROR`, `WARNING`, `INFO` over all
findings), `findings` (most severe first, at most `resultLimits.maxItems`, each
`{severity, check, path, location?, code, message}` with a message of at most 500 characters),
`xslt` (`{outcome, output?, standInsUsed}` if a stylesheet ran; `output` only for `OK`),
`truncated` and `fullReport` (`.reports/check-<timestamp>.json`, only when `truncated`).

**Finding codes**:

| Code | Severity | Check |
|---|---|---|
| `EDGE_TARGET_MISSING` | ERROR | a `Connection` targets a node that does not exist |
| `ID_COLLISION` | ERROR | a `ModuleId` or `ConnectionId` is used twice (one id space) |
| `DEMUX_KEY_UNMATCHED` | ERROR | a Demultiplexer key `<Name>(<id>)@@@…` or `DefaultOutput` names an existing node that is not an outgoing edge of this node, or one with another name |
| `DEMUX_KEY_STALE` | WARNING | a Demultiplexer key or `DefaultOutput` names a node that no longer exists in the workflow, or an old name of a renamed node that also has a key under its current name (leftovers the Workbench keeps; INUBIT ignores them) |
| `PARENT_REF_MISSING` | ERROR | `ParentModule` or `EndLoopId` names a node that does not exist (`Connection@scopeChildId` names internal ids of a scope and is not checked) |
| `MODULE_MISSING` | ERROR | a module is neither in the workspace (any owner of the group) nor in the module list of the artifact's owner or of `inventory.owner` |
| `MODULE_UNVERIFIED` | WARNING | not in the workspace, and the module list could not be read or `verifyOnServer` is `false` |
| `REPOSITORY_REF_MISSING` | ERROR | an `inubitrepository:` reference is not in the workspace repository |
| `REPOSITORY_REF_UNVERIFIED` | WARNING | the reference lies in another owner's repository (`Root/<owner>/…`), which an export does not include |
| `VARIABLE_UNRESOLVED` | WARNING | a variable reference names no declared variable (names starting with `IS` are taken as INUBIT system variables) |
| `DERIVED_VALUE_MISMATCH` | WARNING | `<property>MD5` of an embedded document or `contentMD5`/`contentSize` of a repository file no longer matches the content (a rebuild recomputes them) |
| `XML_NOT_WELL_FORMED` | ERROR | with `line:column`; a document type declaration counts (DTDs and entities are never read) |
| `XSD_INVALID` | ERROR | one finding per position of a violation, or a schema that cannot be loaded |
| `XSLT_STATIC_ERROR` | ERROR | a static error of the stylesheet, with `line:column` and the error code |
| `XSLT_RUNTIME_ERROR` | ERROR | the run failed on the given input (error code), did not finish within 60 s, or its output exceeds 64 MiB — never passed |
| `XSLT_NOT_TESTABLE` | WARNING | the stylesheet needs something only INUBIT has (see below) |
| `XSLT_STANDINS_USED` | INFO | which INUBIT extension functions local stand-ins served |
| `XSLT_STANDIN_ASSUMED` | WARNING | the output rests on a stand-in with assumed behaviour or on a fallback (e.g. an unreadable date kept) |

**Stylesheet runs**: Saxon-HE 10 (whatever `xslt.transformer` a module names). INUBIT's extension
functions (`Formatter`, `Misc`, `ISFunctions`, `java.util.UUID`, `java.lang.Thread`,
`java.net.URLDecoder`) are served by deterministic stand-ins: GUIDs
`00000000-0000-0000-0000-000000000000`, time `2000-01-01T00:00:00Z` or `xslt.now`, `sleep` returns
at once. `NOT_TESTABLE` reasons: an extension function without stand-in (another Java class,
`Formatter:calculateDateDifference`), a construct of a licensed Saxon edition,
`xsl:result-document`, a document type declaration, an unseeded `random-number-generator`. A run
sees no environment variables or system properties of the server and reads only workspace files
(`inubitrepository:` from the stylesheet owner's `repository/`).

**Known limitation**: Saxon-HE cannot stop a running transformation. A stylesheet run that does
not finish within 60 s is reported as `XSLT_RUNTIME_ERROR`, its output is discarded and the
workspace lock is released, but the run keeps one CPU core busy until it ends by itself or the
server process ends.

**Errors**: `INVALID_INPUT` (a path outside the workspace or missing, neither `paths` nor `xslt`,
`schema` without `paths`, a malformed `now`); `PRECONDITION_FAILED` (workspace busy or not
usable); `INTERNAL`. Server lookup failures are findings (`MODULE_UNVERIFIED`), not errors.

**Example prompts**: "Check the workflows of GRP-01" → `check_artifacts(paths:
["dev/OWNERS/workflows/GRP-01"])`; "Run the stylesheet of Map-Order on this test message" →
`check_artifacts(xslt: {stylesheet: "dev/OWNERS/modules/XSLT Converter/Map-Order/xslt.stylesheet.xsl",
input: "inputs/order.xml"})`.

## Development on a development stage (feature 004)

The five development tools write to INUBIT. The server, not the assistant, enforces the order of
every write; each step that refuses sends nothing to INUBIT:

1. the node must be a **development stage** (`NOT_DEVELOPMENT` otherwise, never production);
   the inputs are checked (names StartCLI quoting can carry, a `reason` of 1–500 characters
   without `#`, `@` or control characters — it becomes the person-written segment of the
   check-in comment);
2. the workspace lock (one workspace operation at a time; `tag_artifacts` and `run_e2e_test`
   do not touch the workspace and take no lock); uncommitted edits are recorded as
   `local changes` first;
3. the change set against the **last verified server state** of each artifact (its last export
   or development call in the workspace history);
4. the checks of `check_artifacts` — any `ERROR` refuses with `PRECONDITION_FAILED` and the path
   of the findings report;
5. the owner (default `inventory.owner`): a Workbench user or a user group alike — every
   import, restore, activation and rollback names it with `--importUser` (INUBIT 8.1 refuses
   `--importUserGroup` with "Missing user or group!" and takes a user group with
   `--importUser`), so there is no owner lookup and no owner setting;
6. the **conflict check** on a fresh export: an artifact changed on the server since its base, a
   new artifact that already exists, or a workflow in Workbench **edit mode** is a `CONFLICT`;
7. with `development.confirmation` `SERVER` (the default) the first call returns a **preview**
   (`challenge`) and a one-time `confirmationCode` bound to the inputs, the workspace and the
   server state; the second call with the code repeats the conflict check right before sending
   (`CONFLICT` if the server changed, `CONFIRMATION_INVALID` if inputs or workspace changed —
   "preview again"). With `CLIENT` the first call runs and the MCP client must ask the user;
8. the **backup** (the raw export of the scope, owner-only, `backupRef` = the call's audit id),
   the `PENDING` audit record, then the StartCLI import of an archive with **only** the changed
   artifacts and the target's **current secret values** (taken from the fresh export in memory;
   a placeholder without a value is `SECRET_UNRESOLVED`);
9. the import protocol must name exactly the sent artifacts; the scope is exported again and
   every sent artifact must equal the intended state, with exactly the reason in its check-in
   comment;
10. success: only the changed files and their `.meta/` records are replaced by the verified
    server state and committed with a `Server-State` trailer; failure: **rollback**.

**Failure model.** Refusals before anything is sent are tool errors (`isError: true`):
`NOT_DEVELOPMENT`, `INVALID_INPUT`, `PRECONDITION_FAILED`, `CONFLICT`, `SECRET_UNRESOLVED`,
`CONFIRMATION_INVALID`, `CLI_UNAVAILABLE`, `AUTH_FAILED`, `NOT_FOUND`. Once anything may have been
sent the call returns a **result** with `outcome: FAILED`, a `failure` `{code: IMPORT_FAILED |
VERIFY_MISMATCH, step: import | protocol | verify | commit | tag, message}` and the `rollback`
state. An unexpected internal error after sending is reported the same way (`IMPORT_FAILED` at
the step reached).

**Rollback semantics.** After any failure the scope is exported again; if a modified artifact
changed, the backup is re-imported (with the target's current secrets) and verified:
`rollback` is `NOT_NEEDED`, `SUCCEEDED` or `FAILED` (then the message names the kept backup —
restore it with `restore_backup` or in the Workbench). Nothing in this feature deletes anything:
**created artifacts are not removed**, so artifacts a failed call created are listed in
`createdNotRemoved` — delete them in the Workbench if needed. A StartCLI timeout is decided by the
re-export: if it shows the intended state, the call succeeded.

**Backups** are kept 30 days in `~/.inubit-mcp/<profile>/backups` (the newest per node, owner and
scope always); older ones are removed at the start of the next writing call, each removal audited
(`backup_retention`).

**Result** of `import_artifacts`, `restore_backup` and `set_active` (fields without a value are
absent):

| Field | Meaning |
|---|---|
| `auditId` | the id of the call's audit records |
| `outcome` | `EXECUTED` or `FAILED` |
| `failure` | `{code, step, message}` on failure |
| `commit` | the history entry of the verified state |
| `backupRef` | the backup of the state before the call (input of `restore_backup`) |
| `created`, `modified`, `notImported` | artifact names; workspace files changed outside the scope |
| `rollback` | `NOT_NEEDED`, `SUCCEEDED`, `FAILED` (failures only) |
| `createdNotRemoved` | artifacts created (by this or the restored call) and not removed |
| `reports`, `warnings` | workspace-relative report files (`.reports/verify-<auditId>.diff`, …); notes |

## `import_artifacts`

> [acme] Import the changed workflows of ONE diagram group (with their changed or new modules),
> or changed single modules, from the workspace into ONE development node. The server checks the
> files, refuses on conflicts (changed on the server or open in the Workbench), backs up,
> imports only what changed, verifies by re-export and rolls back on failure. Secrets are taken
> from the node.

**Input**: `node` (one node id), `owner` (default `inventory.owner`), exactly one of
`diagramGroup` or `modules` (1–50 `{name, pluginType?}`), `reason`, `confirmationCode`.

- Changed workflows of the diagram group go with their **changed or new** modules; unchanged
  referenced modules are not sent (every import creates a new version). A module import sends
  module-only archives. Changes outside the scope stay and are listed in `notImported`.
- Refused with `INVALID_INPUT`: a deleted workflow or module file ("deleting artifacts is not
  supported"), any change below `repository/`, a new workflow whose file does not say
  `IsActive` `false` (INUBIT creates it inactive; switch it on with `set_active`), a workflow file
  that names another owner in `UserOrUserGroupName`. `PRECONDITION_FAILED`: a referenced module
  that is neither sent nor on the node (INUBIT would break the diagram group), a new name the
  owner already uses for another workflow or module, no exported base ("export the scope
  first"). UIDs and module file names of modified artifacts come from the node's fresh export,
  never from `.meta/`.
- **Preview** (`challenge`): `scope`, `baseCommit`, `create`, `modify`, `notImported`,
  `checkWarnings`, `confirmationCode`, `expiresAt`, `message`.

**Example prompt**: "Import the layout change of GRP-01 to dev with reason 'layout'" →
`import_artifacts(node: "dev/node1", diagramGroup: "GRP-01", reason: "layout")` returns the
preview; after the user approves, the same call with `confirmationCode`.

## `restore_backup`

> [acme] Re-import the backup taken by an earlier development call on ONE node, limited to the
> artifacts that call changed; same checks, conflict detection, verification and rollback.

**Input**: `node`, `backupRef` (the `backupRef`/`auditId` of an earlier result, a UUID),
`reason`, `confirmationCode`.

- An unknown, removed (older than 30 days and not the newest of its scope) or foreign (another
  node) reference is `NOT_FOUND` before anything is read from INUBIT; a malformed one
  `INVALID_INPUT`.
- Only artifacts the referenced call changed **and that existed before it** are re-imported;
  created ones stay and are reported in `createdNotRemoved`.
- The conflict check compares the server with the **state the referenced call left** (recorded in
  its backup manifest; for a failed call the state its last verification saw): a change since
  then, or edit mode, is `CONFLICT`; a call without a recorded state is `PRECONDITION_FAILED`.
- The restore takes its own backup (its `backupRef`) and rolls back on failure like an import;
  the secrets come from the node's **current** export (old passwords never come back).
- **Preview** (`challenge`): `scope`, `modify`, `notes`, `confirmationCode`, `expiresAt`,
  `message`.

**Example prompt**: "Undo that import" → `restore_backup(node: "dev/node1", backupRef: "…",
reason: "undo layout")`.

## `set_active`

> [acme] Activate or deactivate ONE workflow on ONE development node (INUBIT creates a new
> version).

**Input**: `node`, `owner`, `diagramGroup`, `workflow`, `active` (boolean), `reason`,
`confirmationCode`.

- The archive holds **only that workflow**, built from the node's **fresh export** with
  `IsActive` changed (never from the workspace), imported with `--importWorkflowActive` or
  `--importWorkflowInactive`; the verification checks `IsActive`; a rollback re-imports the
  previous state with the previous flag.
- Refused with `PRECONDITION_FAILED` if the workspace file of the workflow has **unimported
  edits** (import them first) or was never exported; `CONFLICT` if the workflow changed on the
  server or is in edit mode (other workflows of the group do not matter).
- A workflow already in the requested state sends nothing. The result's warnings note the new
  version INUBIT creates.

## `tag_artifacts`

> [acme] Tag the current versions of the technical workflows (and their modules) of the given
> diagram groups of an owner on ONE development node. Only whole diagram groups, never
> owner-wide; an existing tag name is reused and moves to the current versions within these
> groups only.

**Input**: `node`, `owner`, `diagramGroups` (1–20 distinct names), `tag`, `reason`,
`confirmationCode`.

- INUBIT tags only **whole diagram groups** (`--tagDiagram` is ignored), so a tag always covers
  every technical workflow of a requested group and the modules they use. Blank, empty, duplicate
  or wildcard-like groups are refused (`INVALID_INPUT`) before anything is read — StartCLI would
  tag **everything** of the owner without a group. A group without technical workflows is
  `NOT_FOUND`. The owner may be a user or a user group.
- **Tag names**: an existing tag name is reused. Within the requested groups it moves to the
  current versions (re-tagging after a new version moves it from the old to the new head);
  the same tag in other diagram groups stays untouched (probed). Artifacts outside the requested
  groups are never tagged or untagged.
- Pre-check and verification export **only the requested groups** with their history — never
  the whole owner. One `tag --tagMove '<tag>' --tagWorkflowGroup '<group>' --tagWorkflowType
  'technical' --tagUser '<owner>'` per group. **Verification**: the current version of every
  technical workflow of the requested groups and of every module they use must carry the tag.
- On a deviation (`VERIFY_MISMATCH`, report `.reports/tag-<auditId>.txt`) or a failing tag
  command (`IMPORT_FAILED` at step `tag`) the result is `FAILED` with `failure`; **nothing is
  removed** — StartCLI removes a tag only for the whole owner, which could take away a tag that
  marks another group's state, so the server never does. The warning names the groups already
  tagged and asks to call `tag_artifacts` again.
- **Result**: `auditId`, `outcome`, `failure`, `tag`, `diagramGroups`, `workflows`, `modules`
  (how many current versions carry the tag), `reports`, `warnings`. **Preview**: `owner`, `tag`,
  `diagramGroups`, `workflows`, `confirmationCode`, `expiresAt`, `message`; the code is bound to
  the head versions, so a publish in between is `CONFLICT`.

Every history export appends to the check-in comments of the exported workflows in INUBIT
(an INUBIT behaviour, see the spike notes).

## `run_e2e_test`

> [acme] Send a SOAP envelope from the workspace to an endpoint of ONE node and report the
> response and the process instances, errors and log entries it caused. Allowed only where
> e2eTests permits.

**Input**: `node`, `envelope` (workspace path), `path` (relative to `e2e.soap.baseUrl`),
`soapAction`, `workflow` (for the fallback), `timeoutSeconds` (1–120, default 60),
`includeExcerpt` (default `false`), `confirmationCode` (only where `e2eTests` is `CONFIRM`).

- Policy per node: `FREE` (one call), `CONFIRM` (preview with `endpoint`, `payloadBytes`,
  `soapAction`, and a code bound to the inputs and the payload hash), `FORBIDDEN` (the default,
  always on production: `E2E_FORBIDDEN`).
- The envelope must be a regular file inside the workspace (real path; not below `.git`,
  `.meta`, `.reports`); an envelope with a `Password` element holding a value other than a
  `${secret:…}` placeholder is refused. The endpoint path must stay below the base address (no
  scheme, host, `..`, query). Optional basic authentication comes only from the variables
  `<PREFIX>_<GROUP>[_<NODE>]_E2E_USERNAME` / `_E2E_PASSWORD`; redirects are never followed.
- The envelope is sent unchanged with `Content-Type: text/xml; charset=utf-8`, `SOAPAction` and
  a unique test id in the header `X-Inubit-Mcp-Test-Id`. The response goes to
  `.tests/e2e/<auditId>.response.xml` (files older than 30 days are removed); `excerpt` (at most
  2 KB) only with `includeExcerpt: true`.
- **Correlation**: the system and audit logs are searched for the test id in
  `[start − 5 s, end + 30 s]`; found entries give the process instances by their process ids
  (`correlation: BY_TEST_ID`). Otherwise the instances and system log entries of `workflow` in
  that window are returned, marked `TIME_WINDOW_UNCERTAIN` (other traffic may be among them). A
  timeout keeps all of this (`timedOut: true`, no `status`); unreadable logs are a warning.
- **Result**: `auditId`, `testId`, `endpoint`, `status`, `durationMs`, `timedOut`,
  `responseFile`, `excerpt`, `correlation`, `processes`, `errors`, `logEntries` (at most 20 each),
  `truncated`, `warnings`. Errors: `E2E_FORBIDDEN`, `INVALID_INPUT`, `UNREACHABLE`, `TLS_ERROR`.
  The audit records only the payload hash, never the envelope.

**Example prompt**: "Send samples/order.xml to /ibis/ws/Service-01 on dev" →
`run_e2e_test(node: "dev/node1", envelope: "samples/order.xml", path: "/ibis/ws/Service-01",
workflow: "Order-Inbound")`.
