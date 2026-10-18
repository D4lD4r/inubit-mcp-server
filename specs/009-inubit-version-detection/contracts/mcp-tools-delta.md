# MCP tools delta (feature 009)

No new tool. Changes are additive except where marked **changed behaviour**. Research D-5 to D-12.

## Error code `UNSUPPORTED_VERSION` (now returned)

Returned by every tool whose capability the node's version or StartCLI client does not provide
(spec tool classification, FR-005). Shape (as every `ToolError`):

```json
{
  "code": "UNSUPPORTED_VERSION",
  "message": "import_artifacts needs development writes, which INUBIT 9.0.7 on next/node1 does not support yet; nothing was sent",
  "likelyCause": "The node runs a version for which no verified write access exists",
  "nextStep": "Use a node running INUBIT 8.1, or wait for a version of this server that supports 9.0",
  "node": "next/node1"
}
```

Variants of `message` / `nextStep`:

| Cause | message contains | nextStep |
|---|---|---|
| version unsupported for the capability | tool, capability, version, node | use a supported node |
| `AUTO` detection failed (write) | "the INUBIT version of <node> is unknown (<code> at <time>)" | fix the cause, or pin the version the node runs (`versionLine: V8_1` or `"8.1.17"`) |
| pin contradicted by `get_health` | pin and observed version | correct `versionLine` and restart |
| client range excludes the server | client version, declared range, server version | install a StartCLI client that supports <server major.minor> |
| `deploy_release` versions differ / unknown | every source and target node with its version | deploy only between nodes of the same minor version |

Write tools audit these refusals as `REFUSED` (unchanged mechanism).

## `list_nodes` (widened)

Still configuration-only for INUBIT: no request to any node, no StartCLI run. Each node item gains
`version` (data-model.md `NodeSummary`):

```json
{
  "id": "dev/node1",
  "versionLine": "AUTO",
  "version": {
    "setting": "AUTO",
    "server": { "version": "8.1.17", "source": "detected", "at": "2026-10-18T09:12:03Z" },
    "client": { "version": "8.1.17", "supportedFrom": "4.0.1", "supportedTo": "8.1.17", "comparison": "match" },
    "status": "supported",
    "capabilities": { "usable": ["monitoring", "diagnosis", "inventory", "workspace_export", "development_writes"], "notUsable": [] }
  }
}
```

States without values: `"server": {"state": "not_yet_detected"}` (next step `get_health` in the
tool description), `"server": {"state": "unknown", "reason": "FORBIDDEN", "at": "…"}`,
`"client": {"state": "not_yet_detected" | "not_available" | "unknown", …}`. `versionLine` changes
from an enum to a string (the configured text). `notUsable` items: `{"capability": …, "reason": …}`.

## `get_health` (widened)

- The first `get_health` on an `AUTO` node is its detection (as today) and records an observation
  for every node (pinned ones too).
- New part `client` in `unavailable` (`status`, `ready`, `version`, `systemInfo`, `load`,
  **`client`**) when the probe fails or does not finish within the node's health deadline
  (`not yet detected (timeout)`).
- Each report gains `client` (`version`, `supportedFrom`, `supportedTo`, `comparison`) when known.
- Warnings: "INUBIT <v> on <node> is not verified …" replaces "unsupported … the 8.1 adapters are
  used"; a pin contradiction and a client mismatch are warnings too.
- Time budget unchanged (feature 001 SC-002).

## Read tools: `find_processes`, `query_logs`, `list_inventory`, `get_inventory_item` (widened)

- Optional `warnings: string[]` in the result (only when non-empty), e.g. the "unverified version"
  warning on a 9.0, newer or unknown node, or a client mismatch.
- **Changed behaviour**: on a node whose client range excludes the server's major.minor,
  `list_inventory` for modules (a StartCLI export) fails with `UNSUPPORTED_VERSION`;
  `get_inventory_item` returns its REST data with the history as an `unavailable` part (code
  `UNSUPPORTED_VERSION`), as today when the history fails; `list_inventory` for diagrams is
  REST-only and unaffected.
- `check_artifacts`: the optional module lookup is skipped on such a node and reported as a finding
  with the reason.
- The first call on an `AUTO` node detects its version (one extra request).

## Write tools (changed behaviour)

`import_artifacts`, `restore_backup` (development backups), `set_active`, `tag_artifacts`,
`run_e2e_test`, `restart_process`, `kill_process`, `deploy_release`:

- refused with `UNSUPPORTED_VERSION` on a node whose version is unknown (including a failed `AUTO`
  detection), `V9_X`, 9.0 or any version without a `supported` matrix entry, or whose pin is
  contradicted;
- before running, an `AUTO` node's version is detected if missing, stale or older than
  `inventory.cacheTtl` (one request); nothing else is sent before the check passes;
- StartCLI-backed write tools refused when the client range excludes the server's major.minor;
  a newer server patch, a failed probe or a missing range give a warning only, shown in the
  result's `warnings` (`restart_process` and `kill_process` gain an optional `warnings` field).

`restore_backup` of a **deployment** backup follows the deployment rules (target node must be
`supported`).

## `deploy_release` (changed behaviour)

In preview and again before execution: every source and target node is checked (detection rule
above). Refused as a whole if any node is unknown, any target not `supported` for deployments,
sources differ from each other or from any target in major.minor (`V9_X` counts as unknown).

## Tool announcement (changed behaviour)

Decided at startup as before (`listChanged = false`). A tool is additionally not announced when
every node that could run it by configuration is pinned to a version whose matrix state for its
capability is `unsupported` (e.g. a profile whose only development nodes are `V9_X`).
