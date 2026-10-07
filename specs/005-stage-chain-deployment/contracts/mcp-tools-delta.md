# MCP tools delta (feature 005)

One new tool and two widened tools. Research D-3, D-13, D-14.

## `deploy_release` (new)

> [acme] Deploy a release — every diagram group that carries the given tag on the source {group} —
> into ONE target {group}, {node} by {node}. The source is always the configured predecessor of the
> target. The first call returns a preview per {node} (new, changed, layout-only, unchanged,
> excluded, warnings) and a confirmation code; the call with the code backs up, imports only what
> changed with each {node}'s own secrets, verifies, rolls back a failing {node} and stops there, and
> tags the deployed groups. For a package-only target the call with the code writes import packages
> instead of importing.

Registered if at least one group has `deploy`. `ToolHints.destructive(…)`, audited, profile prefix
and terminology, bounded results.

Input:

| Field | Type | Rules |
|---|---|---|
| `target` | string | one group id; the schema admits `<group>/<node>` (`^[a-z0-9][a-z0-9-]{0,31}(/[a-z0-9][a-z0-9-]{0,31})?$`) only so that the guard can answer a node id with `INVALID_INPUT` naming its group |
| `tag` | string | `CliCommand.VALUE`, not blank, no `*`/`?` |
| `owner` | string | optional, default `inventory.owner` of the target |
| `confirmationCode` | string | optional, 22 chars |

Errors before anything is contacted: `TARGET_UNKNOWN`, `INVALID_INPUT`, `CHAIN_VIOLATION` (no
`deploy` on the target), `DEPLOY_LOCKED`, `PRECONDITION_FAILED` (workspace busy).

Challenge (first call):

```json
{"challenge": {
  "target": "int", "source": "dev", "tag": "REL-1", "owner": "OWNERS", "mode": "EXECUTE",
  "diagramGroups": ["GRP-01"],
  "olderThanHead": [],
  "nodes": [{
    "node": "int/node1",
    "counts": {"new": 1, "changed": 2, "layoutOnly": 1, "unchanged": 7, "excluded": 0, "onlyOnTarget": 0},
    "activeFlags": [{"workflow": "Workflow-0001", "active": true, "kept": true}],
    "warnings": [{"kind": "OUTSIDE_CHAIN", "artifact": "Module-0003"}],
    "errors": [],
    "diff": ".reports/deploy-<auditId>/int-node1.diff"
  }],
  "executable": true,
  "confirmationCode": "…", "expiresAt": "…"}}
```

Without `executable` (an error in any node plan) there is no code; the challenge lists the errors.
Errors that fail the preview as a whole: `NOT_FOUND` (no diagram group carries the tag),
`SOURCE_INCONSISTENT`, `CLI_UNAVAILABLE`, `AUTH_FAILED`, `TIMEOUT`.

Result (second call):

```json
{"result": {
  "auditId": "…", "outcome": "EXECUTED | FAILED | PACKAGED",
  "target": "int", "source": "dev", "tag": "REL-1",
  "nodes": [{
    "node": "int/node1", "state": "DEPLOYED | UNCHANGED | ROLLED_BACK | ROLLBACK_FAILED | NOT_STARTED | PACKAGED",
    "backupRef": "…", "imported": ["…"], "created": ["…"],
    "tag": {"applied": true, "workflows": 3, "modules": 9},
    "failure": {"code": "VERIFY_MISMATCH", "step": "verify", "message": "…"},
    "package": "~/.inubit-mcp/acme/packages/<auditId>/prod-node1"
  }],
  "commit": "…", "reports": ["…"], "warnings": ["…"]}}
```

A changed release or target since the preview → `CONFLICT` before the first node; an invalid or
expired code → `CONFIRMATION_INVALID`. `outcome: FAILED` names every node's state and the backup
reference of every node written.

## `restore_backup` (widened)

Also accepts the backup reference of a deployment for its node (a node of a target group). For such
a backup the preview and the server-issued code are always required. The restore covers the
workflows, modules and repository files the deployment changed on that node.

## `run_e2e_test` (widened)

Also admitted on nodes of non-production groups that receive deployments, governed by their
`e2eTests` policy. Registered if any such node or development node allows end-to-end tests.

## Error codes

New: `CHAIN_VIOLATION`, `SOURCE_INCONSISTENT`, `DEPLOY_LOCKED` — each with likely cause and next step,
documented in `docs/tools.md`.
