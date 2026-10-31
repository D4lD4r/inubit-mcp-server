# MCP Tools Delta: Read-Only INUBIT 6.1

Delta to [docs/tools.md](../../../docs/tools.md). No tool is added or removed; inputs are
unchanged. Output changes are additive except the capability names (deliberate, SC-005).

## All versions

- Capability names in `list_nodes` (`version.capabilities.usable[]`, `notUsable[].capability`):
  `diagnosis` is replaced by `process_diagnosis` and `log_diagnosis`. In messages: "process
  diagnosis", "log diagnosis".
- `list_nodes` version block: new field `access`: `rest_and_cli` | `cli_only`.
- Tool announcement: `find_processes`, `query_logs`, `list_inventory`, `get_inventory_item` and
  `export_artifacts` are not announced when no configured node may run them (e.g. a profile of
  6.1 nodes only); `list_nodes`, `get_health` and `check_artifacts` stay always announced.
- `AUTO` node whose detection request is answered with HTTP 404: the unknown-version text gets
  `; if <node id> runs INUBIT 6.1, pin V6_1` right after the failure in parentheses, before any
  suffix of the message, e.g. `the INUBIT version of dev/node1 is unknown (UNEXPECTED_RESPONSE at
  2026-10-18T08:00:00Z); if dev/node1 runs INUBIT 6.1, pin V6_1; results are unverified`. The node
  id stands for "this node" so the text needs no level word of the profile's terminology.

## `get_health` on a 6.1 node

```json
{
  "node": "legacy/node1", "group": "legacy", "checkedAt": "2026-11-02T09:00:00Z",
  "reachable": true, "status": "OK", "maintenanceMode": false,
  "version": "6.1.0.89",
  "load": { "usedMemoryMb": 1210, "freeMemoryMb": 838, "maxMemoryMb": 2048, "memoryUsedPercent": 59.1 },
  "processCounts": { "error": 3, "processing": 0, "waiting": 12, "queued": 0 },
  "client": { "version": "6.1.0", "supportedFrom": "4.0.1", "supportedTo": "6.1.0", "comparison": "match" },
  "unavailable": [
    { "part": "ready", "reason": "NOT_AVAILABLE_ON_6_1: INUBIT 6.1 has no readiness check" },
    { "part": "threads", "reason": "NOT_AVAILABLE_ON_6_1: INUBIT 6.1 reports no thread figures" },
    { "part": "blockingQueue", "reason": "NOT_AVAILABLE_ON_6_1: INUBIT 6.1 reports no blocking queue" }
  ]
}
```

- `status`: `OK` (alive check succeeded), `ERROR` (alive check failed), `UNKNOWN` (not obtained:
  timeout, `CLI_UNAVAILABLE`, login refused, unreachable, `TLS_ERROR`).
- `threads` covers threads in use, licensed threads and maximum threads; `blockingQueue` covers
  entries, maximum size and percentage.
- `version`: the version StartCLI reports; if the recordings show it is the client's, the field is
  absent and a warning carries the value labelled as the client's, e.g. `the version 6.1.0 read on
  legacy/node1 is the StartCLI client's; the server version cannot be read` (FR-006).
- Single 6.1 node: within 15 s. Several nodes: within the 10 s overview; unfinished 6.1 parts are
  listed with `TIMEOUT: …`.

## `find_processes` on a 6.1 node

- Same output shape as 8.1 (`results[].page.items[]` of `ProcessInstance`); `tag`, `moduleType`,
  `globalProcessId` are absent; `inubitNode` absent for `__NotAssigned__`.
- `results[].warnings` contains `logs of INUBIT 6.1 cannot be read with query_logs; read them in
  the Workbench`.
- When a listing reached 10,000 rows: `page.truncated: true`, `page.totalIsLowerBound: true`.
- Refused for the node with `UNSUPPORTED_VERSION`: input `tag`; and, if the recordings require
  it, `states: [ACTIVE]` or the time inputs (`since`, `until`, `hangingOnly`,
  `hangingThresholdMinutes`).

## Refusals on a 6.1 node (`UNSUPPORTED_VERSION`)

Message pattern (feature 009): `<tool>: INUBIT 6.1 (versionLine V6_1) on <node> does not support
<capability or input>; <next step>; nothing was sent` (a node pinned to a concrete version, e.g.
`6.1.0.89`, is named `INUBIT 6.1.0.89` without the line). `nextStep` holds the next step,
except for the contradiction, whose `nextStep` stays that of feature 009 (`Correct versionLine of
<node> (get_health observed INUBIT <version>) and restart the server`); "node" is written in the
profile's terminology (a placeholder takes no "a"/"an" before it).

| Tool / input | Next step |
|---|---|
| `query_logs` | "read the logs of INUBIT 6.1 in the Workbench" |
| `list_inventory`, `get_inventory_item` | "use the Workbench, or another node with a different INUBIT version"; the message has "modules of INUBIT 6.1 cannot be looked up" before it |
| `get_health`, `find_processes` while their capability is not supported for 6.1 | "use the Workbench, or another node with a different INUBIT version" |
| `export_artifacts` (until US4), module export | "use the Workbench export" |
| write tools | "use the Workbench" |
| any tool except `get_health` while the 6.1 pin is contradicted | "check the version with get_health and correct versionLine" |

`check_artifacts` on a 6.1 node: offline checks run; module lookups give `MODULE_UNVERIFIED`
findings with "modules of INUBIT 6.1 cannot be looked up".

## Connection errors on a 6.1 node

| Case | Result |
|---|---|
| certificate does not match the pin / not trusted | `TLS_ERROR`, no StartCLI process started |
| host-name skip requested, client lacks the option | `TLS_ERROR` naming the missing client option |
| trust-store format unreadable for the client runtime | `TLS_ERROR` with "convert the trust store to JKS" |
| more than two concurrent StartCLI runs on the node | the third waits up to `cliTimeout`, then `TIMEOUT` |
