# Data Model: INUBIT MCP Server MVP

> **Renamed in feature 002**: the machine names below follow the 002 neutral names (YAML keys
> `groups`/`nodes`; types `ProfileConfig`, `GroupConfig`, `NodeConfig`, `NodeSummary`, `GroupId`,
> `NodeId`; fields `group`/`node`; `ProcessInstance.inubitNode`; error code `TARGET_UNKNOWN`). The
> prose still says "stage" and "server" for the two levels (002: group and node). Feature 002 adds
> the profile, terminology and credentials sections: see
> [002 data-model.md](../002-customer-agnostic-config/data-model.md).

Derived from [spec.md](spec.md) (Key Entities, FR-001 … FR-030). All entities are immutable value
objects (Java records) unless stated otherwise. Field types are logical types; JSON names in tool
results are the camelCase field names below.

## Configuration entities

Configuration comes from a YAML file (structure, no credentials) and from environment variables
(credentials only, FR-002a). See [contracts/configuration.md](contracts/configuration.md).

### ProfileConfig (001: ServerConfig)

| Field | Type | Rules |
|---|---|---|
| `groups` | list of `GroupConfig` | ≥ 1; stage names unique |
| `defaults` | `Defaults` | optional; values apply where a stage/server does not override |
| `auditDirectory` | path | default `~/.inubit-mcp/audit`; created with owner-only permissions |
| `logLevel` | enum `ERROR\|WARN\|INFO\|DEBUG\|TRACE` | default `INFO` (FR-030) |
| `resultLimits` | `ResultLimits` | default `maxItems=100`, `maxChars=50000` (FR-026); `maxItems ≥ 1`, `maxChars ≥ 10000`; limits apply per tool result (see `Page<T>`) |

### Defaults

| Field | Type | Default |
|---|---|---|
| `timeout` | duration | `PT5S` (SC-002) |
| `cliTimeout` | duration | `PT30S` |
| `hangingThreshold` | duration | `PT60M` (FR-010) |
| `confirmationTtl` | duration | `PT5M` (FR-022); at most `PT1H` (startup error otherwise) |
| `cliExportTimeout` | duration | `PT120S` (inventory exports, R-11) |
| `inventory.owner` | string | `OWNERS` — owning Workbench user/group (FR-016a) |
| `inventory.cacheTtl` | duration | `PT10M` (FR-016b) |
| `cliHome` | path | none — CLI-backed capabilities unavailable if unset |
| `cliJavaHome` | path | none — falls back to the server's `JAVA_HOME` |

### GroupConfig (001: StageConfig)

| Field | Type | Rules |
|---|---|---|
| `name` | string | required; `^[a-z0-9][a-z0-9-]{0,31}$`; unique |
| `production` | boolean | default `false`; applies to all servers of the stage |
| `nodes` | list of `NodeConfig` | ≥ 1; server names unique within the stage |
| `write`, `tls`, `cli`, `inventory`, `versionLine`, `timeout`, `cliTimeout`, `cliExportTimeout`, `hangingThreshold`, `confirmationTtl` | as in `NodeConfig` / `Defaults` | stage-level values, inherited by servers |

### NodeConfig (001: ServerEntryConfig; one INUBIT server = one environment)

| Field | Type | Rules |
|---|---|---|
| `name` | string | required; `^[a-z0-9][a-z0-9-]{0,31}$` |
| *(derived)* `id` | string | `<stage>/<server>`, e.g. `test/inubit01` — used in all tool inputs/results |
| `baseUrl` | URL | required; `https://` expected; `http://` allowed only with `allowInsecureHttp: true` (startup warning) |
| `versionLine` | enum `V8_1\|V9_X\|AUTO` | default `AUTO` (detected from system info); `V9_X` → warning "unsupported" (FR-029) |
| `write.enabled` | boolean | default `false` (FR-019, FR-024) |
| `write.productionOptIn` | boolean | default `false`; only meaningful if the stage is `production` |
| `write.confirmation` | enum `SERVER\|CLIENT` | default `SERVER`; `CLIENT` rejected at startup on production stages (FR-022) |
| `tls.trustStore` | path | optional; default JVM trust store |
| `tls.disableHostnameVerification` | boolean | default `false`; startup warning when `true`; allowed **only** together with `tls.pinnedCertificateSha256` |
| `tls.pinnedCertificateSha256` | hex fingerprint | optional; if set, the server's leaf certificate MUST have exactly this SHA-256 fingerprint (in addition to chain validation against the trust store) |
| *(rule)* trust store and CLI | — | StartCLI can only receive a trust-store password as a process argument, so a server with CLI configured (`cli.home`/`cliHome`) MUST use a password-less trust store: a resolved `INUBIT_…_TRUSTSTORE_PASSWORD` for such a server is a startup error. StartCLI gets `--trustStoreFilePath` only if `tls.trustStore` is set and `--disableHostNameVerification` only if `tls.disableHostnameVerification: true` |
| `cli.url` | URL | default `<baseUrl>/ibis/servlet/IBISSoapServlet` (8.1) |
| `cli.home` | path | overrides `defaults.cliHome` |
| `cli.javaHome` | path | `JAVA_HOME` for the StartCLI child process (8.1: JDK 17); default `defaults.cliJavaHome`, else the server's own `JAVA_HOME`; missing → `CLI_UNAVAILABLE` |
| `timeout`, `cliTimeout`, `hangingThreshold`, `confirmationTtl` | duration | override stage/defaults |

Effective value of every setting: server → stage → `defaults` → built-in default.

### Credentials (resolved from environment variables)

| Field | Source (first non-empty wins) |
|---|---|
| `username` | `INUBIT_<STAGE>_<SERVER>_USERNAME` → `INUBIT_<STAGE>_USERNAME` |
| `password` | `INUBIT_<STAGE>_<SERVER>_PASSWORD` → `INUBIT_<STAGE>_PASSWORD` |
| `trustStorePassword` (optional) | `INUBIT_<STAGE>_<SERVER>_TRUSTSTORE_PASSWORD` → `INUBIT_<STAGE>_TRUSTSTORE_PASSWORD`; not allowed for a server with CLI configured (startup error, see trust store and CLI rule above) |

Secrets are the password, the derived Basic-auth token and the trust-store password. The username is
a non-secret identifier (spec FR-025): it appears in audit records (`account`) and is passed to
StartCLI with `-u`.

Name derivation: `INUBIT_` + uppercase(name) with every non-`[A-Z0-9]` character replaced by `_`.
Derived names MUST be collision-free across all configured servers (startup error otherwise).
Each resolved value records its **source variable name** (reported by `--check-config`).
Password values are held in a `Secret` wrapper whose `toString()` returns `***` and which registers
the value (and the derived Basic-auth token) with the `SecretScrubber`. Credential keys in the YAML
file are a startup error.

**Validation (FR-004)**: all rules above are checked at startup; violations are collected and
reported together; the server refuses to start on any error and logs warnings for insecure-but-
allowed settings.

## Domain entities

### Target (resolved)

Input string of read capabilities → `Target`: either a stage (`test` → all its servers) or a single
server (`test/inubit01`). Pattern: `^[a-z0-9][a-z0-9-]{0,31}(/[a-z0-9][a-z0-9-]{0,31})?$`.
Unknown → `TARGET_UNKNOWN` error listing all stage and server ids (Story 1 / AS 5).
Write capabilities accept only a server id; a stage id → `INVALID_INPUT` (Story 4 / AS 8).

### NodeSummary (result of `list_nodes`; 001: ServerSummary)

`id` (`<stage>/<server>`), `group`, `node`, `production`, `writeEnabled` (effective:
`write.enabled && (!production || productionOptIn)`), `confirmationMode`, `versionLine`,
`cliAvailable` (CLI home configured and `startcli` script found). URLs and usernames are not
included (usernames are not secret, but are omitted to keep the result minimal); credentials never
are (FR-003).

### HealthReport

| Field | Type | Source (8.1) |
|---|---|---|
| `node` (id), `group` | string | config |
| `checkedAt` | timestamp | INUBIT `/healthcheck` timestamp when available, else the MCP server clock |
| `reachable` | boolean | any HTTP response from `/healthcheck` |
| `status` | enum `OK\|ERROR\|UNKNOWN` | `/healthcheck.status` |
| `ready` | boolean? | `/ready` (200 → true, 503 → false) |
| `readyMessage` | string? | `/ready.message` |
| `maintenanceMode` | boolean? | `/healthcheck.maintenancemode` |
| `version` | string? | `/system/info` |
| `systemInfo` | `SystemInfo`? | `/system/info` — only when `includeSystemInfo=true` (FR-007) |
| `load` | `LoadFigures`? | `/metrics?format=json` |
| `unavailable` | list of `UnavailablePart` | e.g. `{load, "METRICS_NOT_LICENSED: … HTTP 403", likelyCause, nextStep}` (FR-008) |
| `error` | `ToolError`? | set when `reachable=false` |
| `warnings` | list of string | e.g. unsupported version line (FR-029) |

`UnavailablePart`: `part` (`status`, `ready`, `version`, `systemInfo`, `load`), `reason`
(`CODE: message`, e.g. `METRICS_NOT_LICENSED: GET /ibis/rest/metrics answered HTTP 403`,
`AUTH_FAILED: …`, `TIMEOUT: …`), and optional `likelyCause` and `nextStep`, copied from the
`ToolError` of the failed call (Phase 3 review m1).

### LoadFigures

`usedMemoryMb`, `freeMemoryMb`, `maxMemoryMb`, `memoryUsedPercent` (derived), `threadsInUse`,
`licensedThreads`, `maxThreads`, `blockingQueueEntries`, `maxBlockingQueueSize`,
`blockingQueuePercent` (derived).

### SystemInfo

`version`, `jdk`, `os`, `maxHeap`, `tracingEnabled`, `schedulerThreads`, plus `raw` (map of the
remaining name/value pairs from `SystemInformationList`). Fixed bounds: at most **50** entries,
values cut to **200** chars, names cut to 100 chars (a name that collides after the cut gets a
`~2`, `~3`, … suffix).

### ProcessInstance

Source in 8.1: the Queue Manager view `POST /ibis/rest/log/queueLog` (research R-8, spike S-4).
The equivalent `ps -csv` column is given for reference (it is not used by the MVP).

| Field | Type | Source (8.1 REST `queueLog` row) | `ps -csv` column |
|---|---|---|---|
| `node` | string | target server id | — |
| `inubitNode` | string | `node` | `NODE` |
| `processId` | string | `workflowId`, decimal (`^[1-9][0-9]{0,18}$`; 9 digits on DEV). This is the id that `processErrorStart`/`kill` take. | `PID` |
| `globalProcessId` | string | `globalPId`: equals `workflowId` for top-level processes, a UUID for sub-workflow rows | — |
| `owner` | string | `owner` (user group, e.g. `OWNERS`) | `UID` |
| `priority` | string | `priority` (e.g. `normal`) | `PRIO` |
| `state` | enum `ERROR\|ACTIVE\|WAITING\|QUEUED\|OTHER` + `rawState` | `status.content` (state table below) | `STATE` |
| `since` | timestamp | `startTime` (epoch ms): when the entry entered the Queue Manager in its current state; for `Error` the time of the error (FR-013) | `DATE` (`yyyy-MM-dd'T'HH:mm:ss`, Europe/Berlin, no offset) |
| `timeInState` | duration | `now - since` | — |
| `hanging` | boolean | `state` non-final (table below) `&& timeInState > threshold` (FR-010) | — |
| `workflow` | string | `workflowName` | `WORKFLOW` |
| `module` | string | `moduleName` | `MODULE` |
| `moduleType` | string | `moduleType` (plugin type) | — |
| `tag` | string | `tag` | `TAG` |

The same `processId` can occur in several rows (one per workflow/module of a process with
sub-workflows; 131 of 695 rows on DEV). Rows are keyed by `(processId, workflow, module)`.

**State table** (spike S-4). All Queue Manager entries are non-final: finished instances leave the
queue. `ERROR` is reported as failed and never as hanging.

| `rawState` (`status.content`, `ps` `STATE`) | `state` | final? | counts for `hanging` | observed on DEV | `queueLog` filter accepted |
|---|---|---|---|---|---|
| `Error` | `ERROR` | no (waits for restart or kill) | no | ✅ 695 rows (`level` 0) | ✅ |
| `Waiting` | `WAITING` | no | yes | ✅ 58 rows (`level` 1) | ✅ |
| `Retry` | `WAITING` | no | yes | — | ✅ |
| `Queued` | `QUEUED` | no | yes | — | ✅ |
| `Processing` | `ACTIVE` | no | yes | — | ✅ |
| anything else | `OTHER` | unknown | no | — | — (e.g. `Suspended` → HTTP 500) |

Request mapping for `find_processes(states)`: `ERROR` → `Error`, `WAITING` → `Waiting` and
`Retry`, `QUEUED` → `Queued`, `ACTIVE` → `Processing`; one `status EQUAL` request per raw value,
combined by the paging rule (research R-9; see `Page<T>`). No `states` and no `hangingOnly` → no
status filter. `hangingOnly` queries only the raw values that count for hanging (`Waiting`,
`Retry`, `Queued`, `Processing`), intersected with `states`; `states=[ERROR]` with `hangingOnly`
gives an empty page without a request.

### LogEntry

`node`, `logType`, `timestamp`, `severity` (normalized `ERROR|WARN|INFO|DEBUG|OTHER` +
`rawSeverity`), `workflow?`, `module?`, `processId?`, `message` (truncated to 2,000 chars with marker),
`fields` (remaining columns as map; each value is rendered as a string and cut to **200 chars** with
the truncation marker `…[truncated]`). Mappers MUST keep each `LogEntry`'s serialized size at or
below `ResultLimiter.MAX_ITEM_CHARS` (4,000 chars, see `Page<T>`): `message` is cut first (down to
its 2,000-char bound), then `fields` values (200 chars each); if the item is still too large,
the remaining `fields` entries are dropped from the end, and `message` is cut further until the
item fits `MAX_ITEM_CHARS` (serialized). Any such cut sets `Page.truncated`. The same
rule applies to `ProcessInstance` rows (text fields cut first). Supported `logType` values (FR-011): `systemLog`,
`queueLog`, `connectionLog`, `schedulerLog`, `auditLog`, `keyManagerLog`, `webserviceManager`.
`processLog` is not supported: the endpoint fails on 8.1.17 (research S-5), and workflow
executions are recorded in `systemLog`.

**Severity**: INUBIT 8.1 log rows have **no severity field**. `severity` is derived from the
success or status field of the log type (raw value in `rawSeverity`), and the `severity` filter of
`query_logs` is translated into the same fields. One request is sent per raw value when a
severity maps to several values; the results are merged and sorted by `timestamp` descending.

| `logType` | Source field | `ERROR` | `WARN` | `INFO` | `DEBUG` | no source value |
|---|---|---|---|---|---|---|
| `systemLog` | `success` (`true` or `{"level": 0, "content": false}`) | `success = false` | — | `success = true` | — | — |
| `auditLog` | `success` (as above) | `success = false` | — | `success = true` | — | — |
| `queueLog` | `status.content` | `Error` | `Waiting`, `Retry` | `Queued`, `Processing` | — | other values → `OTHER` |
| `connectionLog`, `schedulerLog`, `keyManagerLog`, `webserviceManager` | none | — | — | — | — | `OTHER`; a `severity` filter → `INVALID_INPUT` |

"—" in a severity column means that the filter value is rejected with `INVALID_INPUT` for that log
type.

### InventoryItem (list element)

| Field | Diagram (REST `/model/models`) | Module (CLI module index) |
|---|---|---|
| `node`, `kind` | `DIAGRAM` | `MODULE` |
| `name` | `name` | `ModuleName` |
| `type` | diagram type (`technical`, `bpd`, …); empty text if missing | plugin type `PluginName` (e.g. `XSLT Converter`)¹ |
| `group` | diagram group (e.g. `GRP-41`); empty text if missing | module group `ModuleGroupName` = plugin type (e.g. `XSLT Converter`) |
| `owner` | configured owner (`OWNERS`) | configured owner |
| `active` | — (detail only) | `IsActive` |
| `lastChange` | — (detail only) | `LastUpdate` (last content change, `dd.MM.yyyy HH:mm:ss`, Europe/Berlin) |
| `workflows` | — | technical workflows of the owner that use the module, sorted by name, at most 5 (T126: from the usage index — workflow `Node`s naming the module — plus a connector's `WorkflowName`) |
| `workflowCount` | — | number of all using workflows |

¹ Phase 5 decision (US3 design note 1): the module `type` attribute of `module.xml` is always
`technical`, so `type` is the plugin type, as in contracts/mcp-tools.md §5, quickstart V7a and
spec Story 3 / AS 3. Not passed through: `ExportUser`, `ModuleUId`, `ErrorSuppression`, …

### InventoryDetail

`node`, `kind`, `name`, `owner` (required), `type`, `group` (optional: absent only for a
not-found item) plus:
- `active` (boolean; diagrams: `workflow.xml/IsActive` of the REST export; modules: `IsActive`)
- `lastChange` (diagrams: check-in time of the newest version; modules: `LastUpdate`)
- `workflows`, `workflowCount`, `usageComplete` (modules only, T126): all using technical
  workflows (sorted), their number, and whether the server's usage index is complete (only then
  does an empty list mean unused)
- `checkinComment`, `userComment` (diagrams: the head's `CheckinComment` from `workflow.xml`;
  modules: `CheckinComment`, `UserComment` of the module index)
- `versions`: list of `{version (integer, versionNode), checkinUser?, checkinAt? (ISO-8601,
  absent if unparseable), checkinComment?, userComment?, tags[]}` from `versionHistory.xml`, newest
  first (FR-016). Modules: `Modules/Module` of the history export of the group of the first
  using workflow (order of `workflows`) that is in the diagram list (research S-6b; T010 user comparison still open). Absent if unavailable.
- `modules` (diagrams only): list of `{name, type, nodeId}` from `modelByName`
- `connector` (modules only): `{input, output, scheduled}`
- `similarNames`: only when not found (Story 3 / AS 5), up to 5 (case-insensitive): names
  containing the requested name, names of at least 3 chars contained in it, or names within an
  edit distance of `max(2, length / 3)`; ordered by edit distance, then by name
- `unavailable` (Phase 5 addition): `[{part: "versions", reason: "CODE: message", likelyCause?,
  nextStep?}]` (`UnavailableInventoryPart`), e.g. `CLI_UNAVAILABLE`, a CLI failure, a group outside
  the R-11 quoting rule (`UNEXPECTED_RESPONSE`), `NOT_FOUND` for a module that no technical
  workflow uses (complete usage index), or the failure of an incomplete usage index
- `truncated` (Phase 5 addition): texts cut to 200 chars, or, if the detail exceeds the server's
  share of `resultLimits.maxChars`, the `workflows` list cut to a quarter of that budget
  (`workflowCount` stays), then the oldest versions, then the last modules and similar names
  dropped, then the longest texts halved (escape-aware, measured as JSON)
- No `activeVersion` field: INUBIT 8.1 does not expose one (FR-016).

### InventorySnapshot (cache entry, internal)

Key `(server, kind, scope)` with `scope` = `all` (lists), `versions:<type>/<diagram group>`
(version history, kind `DIAGRAM`) or `usage` (module usage index, kind `MODULE`, T126: module
name → using technical workflows with node types, workflows read/total, first failure; an
incomplete index is served to the waiting callers but discarded); fields `collectedAt` (start of the load), `expiresAt`
(`collectedAt + inventory.cacheTtl`), parsed items. One in-flight load per key (a `refresh` joins a
load in flight); `refresh=true` otherwise replaces the entry; failed loads are not cached.

### Page<T>

`items` (≤ `limit`), `offset`, `limit`, `total` (null only if INUBIT cannot report it — then
`totalIsLowerBound=true`), `truncated` (true if items or text were cut), `nextOffset?`.

Size invariants (`ResultLimiter`; limits apply per **tool result**):

- Every item is at most `MAX_ITEM_CHARS` = 4,000 chars serialized; text fields are cut before
  paging (log messages to 2,000 chars with a marker). A larger item is a programming error.
- A page holds at most `maxItems` items whose sizes add up to at most its char budget. The budget
  is never below `MAX_ITEM_CHARS`, so a page holds at least one item when one is left (paging
  always makes progress).
- A tool result over `n` servers pages each server with `share(n)`: budget `maxChars / n`, but at
  least `MAX_ITEM_CHARS`. The whole result's items thus add up to at most
  `max(maxChars, n × MAX_ITEM_CHARS)`, i.e. to `maxChars` for `n ≤ maxChars / 4000` (12 servers at
  the default 50,000; `maxChars` must be ≥ 10,000).
- `truncated` is true when the page holds fewer items than requested although more were
  available (cut by `maxItems` or the char budget) or when text inside an item was cut; plain
  pagination only sets `nextOffset`.

Paging against INUBIT log endpoints (research R-9): a single request uses `startIndex = offset`,
`noOfItems = limit` and the response `total`. When one query needs several requests (several raw
values for a state or severity), each request uses `startIndex = 0`, `noOfItems = offset + limit`;
rows are merged, sorted by time descending and sliced to `[offset, offset + limit)`, and `total` is
the sum of the response totals. `offset + limit > 10000` → `INVALID_INPUT`.
Stage targets: one `Page` per server plus per-server `error` if that server failed
(partial results are allowed; failures never hide successful servers).

### ConfirmationChallenge (result of a write capability's preview step)

`confirmationCode` (128-bit random, URL-safe Base64), `expiresAt`, `preview`:
`{node, action (RESTART|KILL), processId, workflow, module, state, since}`, `message` (what to
do next; added in Phase 6). For a restart the preview shows the row in `ERROR`; for a kill the
top-level row (`globalProcessId == processId`), else the newest row.

### PendingConfirmation (in-memory, internal)

| Field | Rule |
|---|---|
| `code` | key; single use — removed on first redemption attempt, successful or not |
| `node`, `action`, `processId` | redemption MUST match all three |
| `previewed` | the previewed row: `state`, `rawState`, `since`, `workflow`, `module`; returned on redemption, and the action is refused (`PRECONDITION_FAILED`, previewed vs actual) if the freshly read row differs in any of them |
| `expiresAt` | `issuedAt + confirmationTtl`; expired → `CONFIRMATION_INVALID` |

At most 1000 unexpired entries: a further preview is refused with `PRECONDITION_FAILED` ("too
many pending confirmations") after expired entries were swept.

State transitions: `ISSUED → REDEEMED` (match, not expired) · `ISSUED → REJECTED` (mismatch or
expired) · `ISSUED → EXPIRED` (housekeeping). Codes do not survive a server restart.

### ProcessControlResult

`node`, `action`, `processId`, `outcome` (`EXECUTED|REFUSED|FAILED`), `stateBefore`,
`stateAfter?` (re-read after execution: a `state` value, or `NOT_IN_QUEUE` if the instance left
the Queue Manager; absent if the re-read failed), `message`, `auditId` (shared by the `PENDING`
and the final record of the execution). Refusals are tool errors, so `REFUSED` does not occur in
results of the MVP.

### AuditRecord (JSON Lines, append-only)

| Field | Type |
|---|---|
| `auditId` | UUID |
| `timestamp` | ISO-8601 UTC |
| `node` (id), `group` | string (`node` as requested, also if unknown; `group` omitted if the id cannot be parsed) |
| `capability` | `restart_process\|kill_process` |
| `step` | `PREVIEW\|EXECUTE` |
| `inputs` | sanitized map (`processId`, `reason`; `confirmationCode` and, on a preview, `issuedConfirmationCode` replaced by `sha256:` + the first 16 hex chars of their SHA-256) |
| `account` | INUBIT username used (omitted for an unknown server) |
| `outcome` | `CHALLENGE_ISSUED\|PENDING\|EXECUTED\|REFUSED\|FAILED` — `PENDING` is the `EXECUTE` record written before the CLI call (research R-14); the final `EXECUTED`/`FAILED` record has the same `auditId` |
| `reason` | refusal/failure code + message (scrubbed) |
| `mcpClient` | client name/version from MCP initialize, if provided |

File: `<auditDirectory>/audit-YYYY-MM.jsonl`, owner-only permissions, each record flushed and
`fsync`ed before the tool result is returned (FR-023: survives restarts). Write failure of the audit
log → the write action is not executed (fail closed).

### CredentialGuard (internal, per server)

Shared by the REST client and the StartCLI calls of one server (research R-12, Phase 3 review
M2): `rejectedAt?` (set by HTTP 401 / CLI `LoginFailure`), `confirmed` (set by an accepted
attempt). Within 60 s after a rejection every authenticated call fails with a cached
`AUTH_FAILED` without network or CLI access; while unconfirmed only one authenticated call is in
flight. At most one failed login per server per 60 s.

### ToolError

| Field | Type |
|---|---|
| `code` | enum (FR-027): `TARGET_UNKNOWN`, `UNREACHABLE`, `TIMEOUT`, `TLS_ERROR`, `AUTH_FAILED`, `FORBIDDEN`, `MAINTENANCE_MODE`, `NOT_FOUND`, `INVALID_INPUT`, `CLI_UNAVAILABLE`, `UNEXPECTED_RESPONSE`, `WRITE_DISABLED`, `PRODUCTION_PROTECTED`, `CONFIRMATION_REQUIRED`, `CONFIRMATION_INVALID`, `PRECONDITION_FAILED`, `UNSUPPORTED_VERSION`, `INTERNAL` |
| `message` | what failed (scrubbed) |
| `likelyCause` | string |
| `nextStep` | string |
| `node` | string? |
| `excerpt` | string? — ≤ 500 chars of the unexpected response, scrubbed |

`TARGET_UNKNOWN` is used for unknown stage **and** server ids (environment = server, spec
Overview); the message lists all configured stage and server ids.
