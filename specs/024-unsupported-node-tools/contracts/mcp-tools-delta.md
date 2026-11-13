# MCP Tools Delta: Unsupported Node Tools

Delta to [docs/tools.md](../../../docs/tools.md). Five tools are added: `list_cluster_nodes`,
`read_io_log`, `get_node_config`, `set_temporary_log_level`, `reset_temporary_log_level`.
`get_health` gains the optional field `temporaryLogLevels`; `list_nodes` gains the capability names
`cluster_nodes_read`, `io_log_read`, `node_config_read`, `log_level_write` (each only for nodes of
groups with its opt-in and a client home). No other existing tool changes.

## Common rules

- `target` / `node`: exactly one server id `<group>/<node>`, pattern
  `^[a-z0-9][a-z0-9-]{0,31}/[a-z0-9][a-z0-9-]{0,31}$` (a group id fails the schema; the service
  repeats the check with the `TargetResolver.resolveSingleServer` text).
- Decision flow of every read tool (nothing is sent before step 4): 1. input checks
  (`INVALID_INPUT`); 2. one server (`TARGET_UNKNOWN`); 3. the group's opt-in (`NOT_CONFIGURED`, no
  detection); 4. version gate `admitWithDetection` (`UNSUPPORTED_VERSION` or the detection's
  error); 5. call deadline (`cliTimeout`; `read_io_log` with `message`: `cliExportTimeout`), login
  credentials prepared within it; 6. one helper run (slot, TLS check, process); 7. result, one INFO
  log line (no values).
- Every result names `inubitNode`, the INUBIT node that answered (FR-004), and carries the warning
  `Read through an unsupported, undocumented INUBIT interface; diagnosis only`.
- Every string passes `SecretScrubber` (`ResultMapper`); every value, detail and message text is
  masked (020 rules + the known credentials of every configured node) before it is cut.

## Announcement (FR-002, FR-040, SC-005)

| Tool | In `tools/list` iff at least one node … |
|---|---|
| `list_cluster_nodes` | has `unsupported.nodeStatusRead`, a client home for one of its possible versions, and is not pinned to a version on which `cluster_nodes_read` is unsupported |
| `read_io_log` | the same with `unsupported.ioLogRead` / `io_log_read` |
| `get_node_config` | the same with `unsupported.nodeConfigRead` / `node_config_read` |
| `set_temporary_log_level`, `reset_temporary_log_level` | has effective write access (`write.enabled`, and `write.productionOptIn` on production), `unsupported.logLevelWrite`, a client home, and is not pinned to a version on which `log_level_write` is unsupported |

Without any of these opt-ins the tool list is exactly the one of the 021 build.

Annotations: the three reads `ToolHints.readOnly(<title>, true)` (`readOnlyHint: true`,
`openWorldHint: true`); the two log-level tools `ToolHints.destructive(<title>)` (as
`restart_process`).

---

## `list_cluster_nodes`

### Description

```text
Lists every INUBIT node the cluster of one {node} knows, as the Workbench's Cluster Manager shows it: name, status (ACTIVE, MAINTENANCE, OFFLINE, or UNKNOWN for a node that does not answer), INUBIT version, zone, node ID and uptime, and names the node that answered. With nodeName it also returns that node's details (server, JDK, operating system, memory, cluster and zone names, internal addresses and file paths), masked. Use it to check whether a cluster is complete and healthy, including nodes this profile does not configure. UNSUPPORTED: it uses an undocumented internal INUBIT interface without vendor support or stability guarantee, for diagnosis only; it may stop working after any INUBIT update; INUBIT checks no right beyond the login; available only for groups with unsupported.nodeStatusRead. Changes nothing on INUBIT.
```

### Input schema (`schemas/list_cluster_nodes.input.json`)

```json
{
  "type": "object",
  "required": ["target"],
  "properties": {
    "target": { "type": "string", "pattern": "^[a-z0-9][a-z0-9-]{0,31}/[a-z0-9][a-z0-9-]{0,31}$",
      "description": "Id of exactly one {node} (`<{group}>/<{node}>`)" },
    "nodeName": { "type": "string", "pattern": "^[A-Za-z0-9_.:-]{1,128}$",
      "description": "Name of a node from the list; adds that node's details" }
  },
  "additionalProperties": false
}
```

### Output (`schemas/list_cluster_nodes.output.json`)

```json
{
  "node": "test/node1",
  "inubitNode": "node1",
  "total": 2,
  "truncated": false,
  "nodes": [
    { "name": "node1", "status": "ACTIVE", "version": "8.1.17", "zone": "zone-a",
      "nodeId": "a1b2c3", "uptime": "PT76H5M12S", "uptimeSeconds": 273912 },
    { "name": "node2", "status": "UNKNOWN" }
  ],
  "details": {
    "node": "node1",
    "available": true,
    "pairs": [
      { "key": "JDKVersion", "value": "17.0.9" },
      { "key": "InternalNodeAddress", "value": "10.0.0.11:7800" }
    ],
    "truncated": false
  },
  "hints": [],
  "warnings": [
    "Read through an unsupported, undocumented INUBIT interface; diagnosis only",
    "The node details contain internal addresses and file paths of the INUBIT installation"
  ]
}
```

| Field | Rule |
|---|---|
| `nodes[].status` | `ACTIVE`, `MAINTENANCE`, `OFFLINE`, `UNKNOWN`; `rawStatus` only for an unknown non-empty status text |
| `nodes[].version`, `zone`, `nodeId`, `uptime`, `uptimeSeconds` | absent for a node that does not answer |
| `nodes` | at most 200, server order; `total` always; `truncated: true` when cut |
| `details` | only with `nodeName`; `available: false` + `reason` when INUBIT could not deliver them (the list is still returned); `pairs` ≤ 500, value ≤ 2,000 chars masked, `cut: true` when cut; `details.truncated: true` when the 500-pair cap or the helper's 256 KiB budget for detail frames stopped the pairs (hint `Only part of the node details was returned`) |

### Errors

| Code | When | Message (example) | Next step |
|---|---|---|---|
| `INVALID_INPUT` | input; `nodeName` not in the list | `Node nodeX is not in the cluster of test/node1; known nodes: node1, node2` | `Use a name from the list` |
| `TARGET_UNKNOWN` | unknown id | existing text | `Use an id from list_nodes` |
| `NOT_CONFIGURED` | no opt-in (nothing sent) | `list_cluster_nodes is not enabled for {group} test: unsupported.nodeStatusRead is not set` | `Set unsupported.nodeStatusRead: true on {group} test and restart the MCP client (unsupported INUBIT functionality, see docs/setup.md "Unsupported functions")` |
| `UNSUPPORTED_VERSION` | 6.1, other line, "not yet verified" | gate texts | gate texts |
| detection error's own code | version not detectable | detection text | `Fix the cause and retry in a minute, or set versionLine of test/node1` |
| `CLI_UNAVAILABLE` | no client home, JDK/`ibis.jar` missing, launch failure, vendor API missing | 017/020 texts, e.g. `… lacks com.inubit.ibis.server.cluster.ClusterClient.getNodes` | `Check that defaults.cliClients."8.1" points to an INUBIT 8.1 client` |
| `AUTH_FAILED` / `TLS_ERROR` / `UNREACHABLE` / `FORBIDDEN` | `HelperFailures` | existing texts | existing texts |
| `TIMEOUT` | `cliTimeout` exceeded | `list_cluster_nodes on test/node1 did not finish within cliTimeout (30 s)` | `Raise cliTimeout of test/node1` |
| `MAINTENANCE_MODE` / credential codes | detection / credentials | existing texts | — |
| `UNEXPECTED_RESPONSE` | `node error`, `members error`, garbage frames, invalid node name or status text | `list_cluster_nodes got an unexpected answer from test/node1; the unsupported INUBIT interface may have changed after an INUBIT update` | `Use the Workbench's Cluster Manager; report the INUBIT version` |
| `INTERNAL` | helper resource missing | existing text | — |

---

## `read_io_log`

### Description

```text
Lists the logged input and output messages of the System Connector and Message Logger Utility modules of one process run on one INUBIT {node}, as the Workbench's input/output logging view shows them, or returns one of them. Call it with workflow and processId (from query_logs) to get the list; call it again with message set to a listed ref to get that message's text, masked and cut at maxChars, with its full size; binary messages only by size and type. Input/output logging must be switched on for the process and its modules in INUBIT. Messages can contain business and personal data that masking does not cover. UNSUPPORTED: it uses an undocumented internal INUBIT interface without vendor support or stability guarantee, for diagnosis only; it may stop working after any INUBIT update; INUBIT requires a right of the configured user for the listing (FORBIDDEN without it); available only for groups with unsupported.ioLogRead. Changes nothing on INUBIT.
```

### Input schema (`schemas/read_io_log.input.json`)

```json
{
  "type": "object",
  "required": ["target", "workflow", "processId"],
  "properties": {
    "target": { "type": "string", "pattern": "^[a-z0-9][a-z0-9-]{0,31}/[a-z0-9][a-z0-9-]{0,31}$" },
    "workflow": { "type": "string", "minLength": 1, "maxLength": 200,
      "pattern": "^(?!.*(@@@|###))[^\\u0000-\\u001F\\u007F-\\u009F]{1,200}$",
      "description": "Process (workflow) name as query_logs returns it" },
    "processId": { "type": "string", "pattern": "^[0-9A-Za-z_-]{1,64}$",
      "description": "Process ID of the run as query_logs returns it" },
    "message": { "type": "string", "pattern": "^[A-Za-z0-9_-]{22}$",
      "description": "ref of one entry of this run's listing; returns that message" },
    "module": { "type": "string", "minLength": 1, "maxLength": 200,
      "pattern": "^[^\\u0000-\\u001F\\u007F-\\u009F]{1,200}$",
      "description": "Lists only entries whose module name or module ID equals this text" },
    "maxChars": { "type": "integer", "minimum": 1, "maximum": 100000, "default": 20000,
      "description": "Maximum characters of the returned message text (after masking)" }
  },
  "additionalProperties": false
}
```

### Output (`schemas/read_io_log.output.json`)

Listing (no `message`):

```json
{
  "node": "test/node1",
  "inubitNode": "node1",
  "workflow": "Orders-Inbound",
  "processId": "4711",
  "total": 4,
  "matching": 4,
  "entries": [
    { "ref": "Q2Fh7tZk1oVYbP3x9w-aAA", "moduleName": "Order Connector", "moduleId": "Module-0003",
      "direction": "INPUT", "index": 1 },
    { "ref": "m0kq3T6cJYb1hX9Z2s_eRw", "moduleName": "Order Connector", "moduleId": "Module-0003",
      "direction": "OUTPUT", "index": 1 }
  ],
  "truncated": false,
  "hints": [],
  "warnings": ["Read through an unsupported, undocumented INUBIT interface; diagnosis only"]
}
```

Read (`message` set): the same fields plus

```json
"message": {
  "ref": "Q2Fh7tZk1oVYbP3x9w-aAA", "moduleName": "Order Connector", "moduleId": "Module-0003",
  "direction": "INPUT", "index": 1,
  "kind": "text", "charset": "UTF-8", "sizeBytes": 1834, "textChars": 1790,
  "text": "<order><customer>acme</customer><password>${secret:data.password}</password></order>",
  "cut": false
}
```

| Field | Rule |
|---|---|
| `entries` | server order, after the `module` filter, at most 500; `total` = all entries of the run, `matching` = after the filter; `truncated` when more matched |
| `entries[].moduleId` | absent when the entry's module part does not end in `(<id>)` (hint `Some entries have an unexpected form; they are listed with their raw module part`) |
| `message.kind` | `text` (with `charset` `UTF-8` or `ISO-8859-1`, `text`, `textChars` before the cut, `cut`), `binary` (`detected` if a signature matched; hint `Binary content is not returned`), `empty` (hint `The message is empty or no longer available`), `tooLarge` (hint `The message has <n> bytes and is too large to show (limit 10 MiB)`) |
| empty listing | `entries: []`, hint `No logged message for this run: input/output logging may be off for the process or its modules, the messages may already be removed, or the run may have executed on another cluster node` |

### Errors

As `list_cluster_nodes` (tool name and opt-in `unsupported.ioLogRead`), plus:

| Code | When | Message (example) | Next step |
|---|---|---|---|
| `INVALID_INPUT` | `message` not in the run's listing (no download) | `Message Q2Fh7tZk1oVYbP3x9w-aAA is not in the input/output logging of process 4711 of Orders-Inbound on test/node1` | `Call read_io_log without message and use a listed ref` |
| `INVALID_INPUT` | `workflow` with a separator or control character, `processId`, `module`, `maxChars` outside the limits | `Invalid maxChars 0 (1–100000)` | fix the input |
| `TIMEOUT` | listing: `cliTimeout`; read: `cliExportTimeout` | `read_io_log on test/node1 did not finish within cliExportTimeout (120 s)` | `Raise cliExportTimeout of test/node1` |
| `FORBIDDEN` | `io list error` with INUBIT's `NotAllowedAccess` (the account lacks the right to read the input/output logging; spike T018) | `test/node1 refused the input/output logging for the configured user` | `Give the user the right the Workbench's input/output logging view needs` |
| `UNEXPECTED_RESPONSE` | another `io list error`, `part message error`, listing > 16 MiB, an entry of another process | as above | `Use the Workbench's input/output logging view; report the INUBIT version` |

---

## `get_node_config`

### Description

```text
Returns configuration values of the INUBIT node one {node} reaches, from a fixed list of values that matter for diagnosis and hold no secrets: OperationMode, ClusterName, ZoneName, NodeName, LocalMaintenanceMode, ActivateClusterManager, ClusterDebug, ContainerShutdownOnNodeFailure, RegisterMBeans, RegisterInternalMBeans, RuntimeDataCacheXMXPercentage, socketTimeoutInMillisecs, indexEnabled, iterationCount, DataSourceLocation, LogsDBDataSourceLocation, RepositoryDataSourceLocation, ClusterBindAddress, noOfRetries, retryInterval. Without names it returns all of them; per name value, type, display name, description, category and whether it is editable or deprecated, or that the node has no such value. Any other name is refused. UNSUPPORTED: it uses an undocumented internal INUBIT interface without vendor support or stability guarantee, for diagnosis only; it may stop working after any INUBIT update; INUBIT checks no right beyond the login; available only for groups with unsupported.nodeConfigRead. Changes nothing on INUBIT.
```

### Input schema (`schemas/get_node_config.input.json`)

```json
{
  "type": "object",
  "required": ["target"],
  "properties": {
    "target": { "type": "string", "pattern": "^[a-z0-9][a-z0-9-]{0,31}/[a-z0-9][a-z0-9-]{0,31}$" },
    "names": {
      "type": "array", "minItems": 1, "maxItems": 20, "uniqueItems": true,
      "items": { "enum": ["OperationMode", "ClusterName", "ZoneName", "NodeName",
        "LocalMaintenanceMode", "ActivateClusterManager", "ClusterDebug",
        "ContainerShutdownOnNodeFailure", "RegisterMBeans", "RegisterInternalMBeans",
        "RuntimeDataCacheXMXPercentage", "socketTimeoutInMillisecs", "indexEnabled",
        "iterationCount", "DataSourceLocation", "LogsDBDataSourceLocation",
        "RepositoryDataSourceLocation", "ClusterBindAddress", "noOfRetries", "retryInterval"] }
    }
  },
  "additionalProperties": false
}
```

### Output (`schemas/get_node_config.output.json`)

```json
{
  "node": "test/node1",
  "inubitNode": "node1",
  "values": [
    { "name": "OperationMode", "present": true, "value": "CLUSTER", "type": "SelectProperty",
      "displayName": "Operation mode", "description": "Single or cluster operation",
      "category": "Cluster", "editable": true, "deprecated": false },
    { "name": "noOfRetries", "present": false }
  ],
  "warnings": ["Read through an unsupported, undocumented INUBIT interface; diagnosis only"]
}
```

`values` in request order (all 20 in list order without `names`); `value` masked, ≤ 2,000 chars
(`cut: true`); `present: false` = "not present on this node"; a single failed name has
`error: {code, message}` and the others are still returned.

### Errors

As `list_cluster_nodes` (tool name, opt-in `unsupported.nodeConfigRead`), plus `INVALID_INPUT`
`Configuration value <not echoed> is not readable; allowed: OperationMode, ClusterName, …` (the
service's check behind the schema; nothing sent; SC-003).

---

## `set_temporary_log_level`

### Description

```text
Raises the log level of one to five INUBIT loggers (names below com.inubit.ibis) on the INUBIT node one {node} reaches, for a limited time (default 10 minutes, at most 60), through StartCLI's documented trace -nl command; then download the trace log with download_server_log. The MCP server records a lease and resets the node when it expires, when the MCP server shuts down, or at its next start, also when write access is switched off meanwhile. The reset (StartCLI trace -r) restores every temporary log level on that node, also those other users set; levels can also be lost earlier (INUBIT restart, log configuration reload). Only the exactly named logger changes. Confirmation as restart_process. UNSUPPORTED: uses unsupported, undocumented INUBIT functionality (the lookup of the answering node goes through an internal INUBIT interface) without vendor support or stability guarantee, for diagnosis only; it may stop working after any INUBIT update. Needs write access and unsupported.logLevelWrite. Address cluster nodes directly, not through a load balancer.
```

### Input schema (`schemas/set_temporary_log_level.input.json`)

```json
{
  "type": "object",
  "required": ["node", "loggers", "level"],
  "properties": {
    "node": { "type": "string", "pattern": "^[a-z0-9][a-z0-9-]{0,31}/[a-z0-9][a-z0-9-]{0,31}$" },
    "loggers": { "type": "array", "minItems": 1, "maxItems": 5, "uniqueItems": true,
      "items": { "type": "string", "maxLength": 200,
        "pattern": "^com\\.inubit\\.ibis(\\.[A-Za-z_][A-Za-z0-9_]*)+$" } },
    "level": { "enum": ["TRACE", "DEBUG", "INFO", "WARN", "ERROR"] },
    "durationMinutes": { "type": "integer", "minimum": 1, "maximum": 60, "default": 10 },
    "confirmationCode": { "type": "string", "pattern": "^[A-Za-z0-9_-]{22}$" }
  },
  "additionalProperties": false
}
```

### Output (`schemas/set_temporary_log_level.output.json`)

`oneOf` `challenge` | `result` (shape of `restart_process`):

```json
{ "challenge": {
    "confirmationCode": "Zk3p0Qx8LmN2vB7cR1tY4w", "expiresAt": "2026-11-12T08:20:30Z",
    "preview": { "node": "test/node1", "inubitNode": "node1",
      "loggers": [{ "name": "com.inubit.ibis.acme.Orders", "level": "DEBUG" }],
      "durationMinutes": 10, "openLease": null },
    "message": "Nothing was changed. Call set_temporary_log_level again with confirmationCode before 2026-11-12T08:20:30Z to raise the level; the later reset restores every temporary level on node1, also other users'" } }
```

```json
{ "result": {
    "node": "test/node1", "inubitNode": "node1", "outcome": "EXECUTED",
    "leaseId": "3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01",
    "loggers": [{ "name": "com.inubit.ibis.acme.Orders", "level": "DEBUG" }],
    "expiresAt": "2026-11-12T08:25:31Z",
    "message": "Raised on INUBIT node node1 until 2026-11-12T08:25:31Z; then the MCP server resets every temporary log level on node1, also other users'. Levels can be lost earlier (INUBIT restart, log configuration reload).",
    "auditId": "8c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
    "warnings": [] } }
```

`loggers` in the result = all loggers of the (merged) lease; `expiresAt` = the lease's expiry,
counted from the executed change. `outcome: FAILED` when a StartCLI run failed: the node was reset
at once (the message says so and that a merged lease ended), `leaseId` of the ended lease.

### Errors

| Code | When | Message (example) | Next step |
|---|---|---|---|
| `INVALID_INPUT` | logger outside `com.inubit.ibis`, duplicate, > 5, invalid level, duration outside 1–60, bad code (US4-5) | `Invalid logger 2 (a name below com.inubit.ibis)` | fix the input |
| `TARGET_UNKNOWN` / `INVALID_INPUT` | unknown id / group id | existing texts | — |
| `PRODUCTION_PROTECTED` / `WRITE_DISABLED` | write guard (tool named) | `Write access is disabled for test/node1; nothing was changed` | `… set write.enabled: true …` |
| `NOT_CONFIGURED` | no `unsupported.logLevelWrite` (after the write checks) | `set_temporary_log_level is not enabled for {group} test: unsupported.logLevelWrite is not set` | `Set unsupported.logLevelWrite: true …` |
| `UNSUPPORTED_VERSION` | gate | gate texts | — |
| `PRECONDITION_FAILED` | `RESET_FAILED` lease (US4-11); another node answers than the open lease names (US4-6); another change for the node is running; lease store unreadable; preview state changed | `The last reset of test/node1 failed (lease 3f2b…, 2026-11-12T08:26:01Z: node unreachable); retry reset_temporary_log_level first` / `INUBIT node node2 answers for test/node1, but the open lease was set on node1` | as named |
| `CONFIRMATION_INVALID` | unknown, used, expired or foreign code | existing text | `Call again without confirmationCode` |
| `CLI_UNAVAILABLE` / `AUTH_FAILED` / `TLS_ERROR` / `UNREACHABLE` / `FORBIDDEN` / `TIMEOUT` (`cliTimeout`) | as the read tools and StartCLI | existing texts | — |
| `UNEXPECTED_RESPONSE` | own-node lookup frames; StartCLI without the confirmation | `… StartCLI did not confirm the log level change` | `Reset with reset_temporary_log_level; report the INUBIT version` |
| `INTERNAL` | audit `PENDING` / write-ahead lease not writable (nothing sent); lease update failed after the change (node reset at once) | `The levels were raised but the lease could not be recorded; the node was reset` | — |

---

## `reset_temporary_log_level`

### Description

```text
Resets every temporary log level on the INUBIT node one {node} reaches through StartCLI's documented trace -r command (every temporary log level on that node, also those other users set), and ends the MCP server's lease for it; works also without a lease (to clean up an earlier change). With leaseId and forget: true it instead ends a lease whose automatic reset failed, without contacting INUBIT; the level may then stay raised until that node restarts. Confirmation as restart_process. UNSUPPORTED: uses unsupported, undocumented INUBIT functionality (the lookup of the answering node goes through an internal INUBIT interface) without vendor support or stability guarantee, for diagnosis only; it may stop working after any INUBIT update. Needs write access and unsupported.logLevelWrite. Address cluster nodes directly, not through a load balancer.
```

### Input schema (`schemas/reset_temporary_log_level.input.json`)

```json
{
  "type": "object",
  "properties": {
    "node": { "type": "string", "pattern": "^[a-z0-9][a-z0-9-]{0,31}/[a-z0-9][a-z0-9-]{0,31}$" },
    "leaseId": { "type": "string",
      "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$" },
    "forget": { "const": true },
    "confirmationCode": { "type": "string", "pattern": "^[A-Za-z0-9_-]{22}$" }
  },
  "oneOf": [
    { "required": ["node"], "not": { "anyOf": [{ "required": ["leaseId"] }, { "required": ["forget"] }] } },
    { "required": ["leaseId", "forget"], "not": { "required": ["node"] } }
  ],
  "additionalProperties": false
}
```

### Output (`schemas/reset_temporary_log_level.output.json`)

`oneOf` `challenge` | `result`; result:

```json
{ "result": {
    "node": "test/node1", "inubitNode": "node1", "outcome": "EXECUTED",
    "leaseId": "3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01", "leaseOpen": true,
    "message": "Every temporary log level on INUBIT node node1 was reset, also other users'; the lease ended",
    "auditId": "…", "warnings": [] } }
```

Without a lease: `leaseOpen: false`, message `No lease was open for test/node1; every temporary
log level on INUBIT node node1 was reset, also other users'` (US4-8). Forget: `outcome:
EXECUTED`, `inubitNode` from the lease, message `Lease 3f2b… ended as FORGOTTEN without contacting
INUBIT; the level may still be raised on node1 until its restart` (US4-9).

### Errors

As `set_temporary_log_level`, plus `INVALID_INPUT` for: an input combination other than the two
forms; an unknown `leaseId`; `forget` of a lease not in `RESET_FAILED` (`Lease 3f2b… is ACTIVE;
only a lease whose reset failed can be forgotten`); a lease whose server is no longer configured
(`… ends automatically 7 days after its expiry, on 2026-11-19`).

---

## `get_health` (changed, additive)

`HealthReport` `+ temporaryLogLevels`, a list of `LeaseSummary` (`$defs/LeaseSummary`; absent when
the node has no open lease), shown whether write tools are on or off:

```json
"temporaryLogLevels": [
  { "leaseId": "3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01", "inubitNode": "node1",
    "loggers": [{ "name": "com.inubit.ibis.acme.Orders", "level": "DEBUG" }],
    "setAt": "2026-11-12T08:15:31Z", "expiresAt": "2026-11-12T08:25:31Z", "state": "RESET_FAILED",
    "lastAttempt": { "at": "2026-11-12T08:26:01Z", "outcome": "FAILED", "code": "UNREACHABLE",
      "reason": "node unreachable" } }
]
```

An unreadable lease store adds the warning `The temporary log level leases could not be read
(<reason>)` to every report; never an error.

## Capability table and `list_nodes` (docs/tools.md)

| Tool | Capability | Kind | Channel | 8.1 | 9.0 | 6.1 / other / unknown |
|---|---|---|---|---|---|---|
| `list_cluster_nodes` | `cluster_nodes_read` | read (unsupported) | helper on the INUBIT client | supported after the 8.1 recording | supported after the 9.0 recording, else "not yet verified" | refused |
| `read_io_log` | `io_log_read` | read (unsupported) | helper | as above | as above | refused |
| `get_node_config` | `node_config_read` | read (unsupported) | helper | as above | as above | refused |
| `set_temporary_log_level`, `reset_temporary_log_level` | `log_level_write` | write | StartCLI + helper (answering node) | as above | as above | refused |

`list_nodes`: each name appears in `usable`/`notUsable` only for nodes of groups with its opt-in
and a client home (`list_nodes.output.json` enum `+` the four names).
