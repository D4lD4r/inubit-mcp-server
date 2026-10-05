# Contract Delta: MCP Tools (002 vs. 001)

Base contract: [001 contracts/mcp-tools.md](../../001-inubit-mcp-mvp/contracts/mcp-tools.md). Everything not
listed here (tool semantics, decision flows, paging rule, bounds, failure kinds, annotations, deadlines)
is unchanged. During implementation the 001 contract file is updated in place to the 002 names, with a
header note "renamed in feature 002".

## Renamed machine-readable names

| Area | 001 | 002 |
|---|---|---|
| tool name | `list_servers` | `list_nodes` |
| `list_nodes` output | `{ stages: [{ name, production, servers: ServerSummary[] }] }` | `{ profile: {name, description?}, terminology: {group:{singular,plural}, node:{singular,plural}}, groups: [{ name, production, nodes: NodeSummary[] }] }`; never the credential prefix |
| summary record | `ServerSummary { id, stage, server, … }` | `NodeSummary { id, group, node, production, writeEnabled, confirmationMode, versionLine, cliAvailable }` |
| `get_health` report | `server`, `stage` | `node`, `group` |
| per-node result entries (find_processes, query_logs, list_inventory, get_inventory_item) | `server` | `node` |
| record fields (ProcessInstance, LogEntry, InventoryItem, InventoryDetail, Preview, ProcessControlResult) | `server` | `node` |
| `ToolError` | `server?` | `node?` |
| `ProcessInstance` Queue Manager column `node` | `node` | `inubitNode` (frees `node` for the target id; implementation decision in T006) |
| write tool input | `server` (pattern `^…/…$`) | `node` (same pattern) |
| read tool input | `target` | `target` (unchanged name; description uses terminology) |
| error code | `ENVIRONMENT_UNKNOWN` | `TARGET_UNKNOWN` |
| new error code | — | `NOT_CONFIGURED` (required setting missing, e.g. `inventory.owner`) |

## Descriptions and terminology

- Every tool description starts with the profile prefix `[<profile>: <description>] ` (or `[<profile>] `).
- Tool, parameter and output-field descriptions use the profile's terminology via placeholders
  (`{group}`, `{groups}`, `{node}`, `{nodes}` exactly as configured; `{Group}`, … with an upper-cased
  first letter, only at a sentence start or in a title; `{profile}`); rendered at startup.
- Template texts (in the code/schema files) are written so that they read correctly with any
  terminology and language: no "a"/"an" directly before a placeholder, no inflection, id forms
  `<{group}>/<{node}>` (`TemplateGrammarTest`), e.g. "Check whether the INUBIT {nodes} are reachable … `target`
  takes the id of one {node} (`<{group}>/<{node}>`) or of one {group} (all its {nodes}); omit it for all
  {nodes}."
- Placeholders are allowed in schema `description` strings only; a placeholder in another string, or an
  unknown placeholder, fails the server start. Tool titles (`title`, `annotations.title`) are rendered too.
- Every read tool's `target` input has a description ("Id of one {group} (all its {nodes}) or of one
  {node} (`<{group}>/<{node}>`)"); the write tools' `node` input: "Id of exactly one {node}
  (`<{group}>/<{node}>`); {group} ids are rejected".
- Human-readable messages (`message`, `likelyCause`, `nextStep`, warnings) use the terminology and the
  effective credential prefix; "restart Claude Code" becomes "restart the MCP client". Where a message
  does not need the level word, it names the id instead; "INUBIT server" (the product instance) and "MCP
  server" (this process) are not level words.

## INUBIT's own "group" and "node" (unchanged names)

These names keep their INUBIT meaning and are not templated (see data-model.md "Names that keep their
INUBIT meaning"):

- `list_inventory` input `group` and the `group` of inventory items: INUBIT's diagram or module group. The
  input description says "INUBIT diagram/module group (not the configured {group})"; the item field
  descriptions say "INUBIT diagram group, or module group (= plugin type); not the configured {group}".
- `query_logs` `LogEntry.fields.node`: a raw INUBIT column, passed through; its description says so.
- `find_processes` `ProcessInstance.inubitNode`: the Queue Manager `node` column.

## Profile visibility

- `list_nodes` returns `profile` and `terminology` (above).
- MCP `initialize` result: `serverInfo.name` stays `inubit-mcp-server`, `serverInfo.title` is
  "INUBIT MCP – <profile>"; server `instructions` carry the profile, its description and the terminology
  (research D-5; spike T-S1 confirmed both, so the fallback name is not used). Example for `acme`
  ("ACME test", Umgebung/Knoten): "INUBIT systems of profile acme (ACME test). The targets are Umgebungen
  and their Knoten. Knoten ids have the form `<Umgebung>/<Knoten>`; the id of one Umgebung selects all its
  Knoten. Read tools take the id of one Umgebung or one Knoten in `target`; write tools (if offered) take
  exactly one Knoten id in `node`. Results use the fields `group` and `node` for these levels. Call
  list_nodes first to learn the valid ids."
- The profile description is inserted as given (never rendered as a template), in the instructions and
  in the description prefix.

## Audit record (JSON Lines)

Field order: `auditId`, `timestamp`, `profile`, `node`, `group`, `capability`, `step`, `inputs`, `account`,
`outcome`, `reason`, `mcpClient`. Default location `~/.inubit-mcp/<profile>/audit/audit-YYYY-MM.jsonl`.

## Inventory without owner

`list_inventory` / `get_inventory_item` for a node without effective `inventory.owner` → per-node
`ToolError{code: NOT_CONFIGURED, nextStep: "set inventory.owner for <group>/<node> or in defaults"}`;
other nodes of a group target are unaffected.
