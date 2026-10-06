# MCP tools delta (feature 003)

Two new tools. All general rules of the tool reference (`docs/tools.md`) apply: `structuredContent`
matching the output schema plus the same JSON as text; `isError: true` with one `{"error":
ToolError}` block on failure; profile prefix and terminology in descriptions and messages; input
schema violations rejected before the tool runs. Schemas:
`src/main/resources/schemas/export_artifacts.{input,output}.json`,
`src/main/resources/schemas/check_artifacts.{input,output}.json`.

Both tools are read-only towards INUBIT: `ToolHints.readOnly(…)` (`readOnlyHint: true`,
`destructiveHint: false`, `idempotentHint: true` for checks, `false` for export because it writes a
history entry, `openWorldHint: true` for export, `false` for checks except server lookups). They are
not audited (Constitution VII covers state-changing tools only).

## `export_artifacts`

> [acme] Export technical workflows (by diagram group) or single modules from one {group} or {node}
> into the local workspace as readable files, record the export in the workspace history and list
> what changed. Only technical workflows are exported (no system diagrams or other diagram types).
> Secrets are replaced by placeholders. Read-only for INUBIT.

Offered when at least one node has a CLI installation.

**Input** (`export_artifacts.input.json`):

| Property | Rules |
|---|---|
| `target` (required) | id of one group or one node (existing id pattern); a group uses its first node |
| `owner` | INUBIT user or user group; default `inventory.owner` of the node; `CliCommand.VALUE` |
| `diagramGroups` | array (1–20) of diagram group names; each non-blank and matching `CliCommand.VALUE` |
| `modules` | array (1–50) of `{name, pluginType?}`; `pluginType` looked up in the module list if absent |
| — | exactly one of `diagramGroups` / `modules` is required |

There is no diagram type parameter: only technical workflows are exported (clarification 2).

**Output** (`export_artifacts.output.json`):

```json
{
  "node": "dev/node1",
  "owner": "OWNERS",
  "workspace": "/home/jdoe/.inubit-mcp/acme/workspace",
  "localChanges": {"commit": "a1b2c3d", "files": 2},
  "commit": "d4e5f6a",
  "unchanged": false,
  "counts": {"added": 3, "modified": 1, "deleted": 0},
  "changes": [{"path": "dev/OWNERS/workflows/GRP-41/Order-Intake.xml", "kind": "MODIFIED"}],
  "truncated": false,
  "secretsReplaced": 4,
  "warnings": []
}
```

- `localChanges` is present only when uncommitted changes were recorded first (clarification 1).
- Fields without a value are absent, never `null` (as in every result of this server).
- `unchanged: true` → `commit` is absent and `changes` empty (SC-001).
- `changes` holds at most `resultLimits.maxItems` entries; otherwise `truncated: true` and
  `fullList` is the workspace-relative path of `.reports/export-<commit>.txt`; `fullList` is absent
  when not truncated.
- `warnings` e.g. "workflow X is in edit mode by Y" (CheckoutUser), "N used modules belong to
  another owner and were not exported".

**Errors** (existing codes): `TARGET_UNKNOWN`, `INVALID_INPUT` (empty group, unsupported characters,
case-only path collision, neither/both of `diagramGroups`/`modules`), `NOT_CONFIGURED` (no owner),
`CLI_UNAVAILABLE`, `AUTH_FAILED`, `TIMEOUT` (names `cliExportTimeout`), `NOT_FOUND` (diagram group
or module does not exist for the owner), `UNEXPECTED_RESPONSE` (archive cannot be processed — the
workspace is unchanged), `PRECONDITION_FAILED` (another export or check is running — refused at once, no waiting — or the
workspace is not usable), `INTERNAL`.

## `check_artifacts`

> [acme] Check workspace files before an import: workflow structure (edges, ids, branch conditions,
> referenced modules, variables, repository references), run a stylesheet against an input file,
> or validate XML against a schema. Never changes INUBIT; writes only test outputs.

Always offered.

**Input** (`check_artifacts.input.json`):

| Property | Rules |
|---|---|
| `paths` | array (1–200) of workspace-relative files or directories; structure and well-formedness checks of every workflow, module and XML file below them |
| `xslt` | `{stylesheet, input, params?: {name: string}, now?: date-time}`; workspace-relative paths |
| `schema` | workspace-relative XSD; with `paths`, each XML file is validated against it |
| `verifyOnServer` | boolean, default `true`: look up referenced modules missing in the workspace on the group's first node |
| — | at least one of `paths` / `xslt` is required; paths must stay inside the workspace (no `..`, no absolute paths, no symlinks leaving it) |

**Output** (`check_artifacts.output.json`):

```json
{
  "counts": {"ERROR": 1, "WARNING": 0, "INFO": 1},
  "findings": [
    {"severity": "ERROR", "check": "STRUCTURE", "code": "EDGE_TARGET_MISSING",
     "path": "dev/OWNERS/workflows/GRP-41/Order-Intake.xml",
     "location": "WorkflowModule[ModuleId=5]/Connection", "message": "edge to node 99, which does not exist"},
    {"severity": "INFO", "check": "XSLT", "code": "XSLT_STANDINS_USED",
     "path": "dev/OWNERS/modules/XSLT Converter/Map-Order/xslt.stylesheet.xsl",
     "message": "stand-ins used: Misc.guid, Formatter.changeDateFormat"}
  ],
  "xslt": {"outcome": "OK", "output": ".tests/dev/OWNERS/Map-Order/order.xml.out", "standInsUsed": ["Misc.guid"]},
  "truncated": false
}
```

Fields without a value (`location`, `fullReport`, `xslt` without a run, `xslt.output` unless `OK`)
are absent, never `null`. `fullReport` is present only when `truncated` is true.

Finding codes: see [data-model.md](../data-model.md) (`CheckFinding`). Findings and messages never
contain secret values.

**Errors**: `INVALID_INPUT` (path outside the workspace, missing file, neither `paths` nor `xslt`),
`PRECONDITION_FAILED` (workspace locked or unusable), `INTERNAL`. Server lookup failures are findings
(`MODULE_UNVERIFIED`, WARNING), not errors.
