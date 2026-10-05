# Data Model Delta: Customer-Agnostic Configuration

Base: [001 data-model.md](../001-inubit-mcp-mvp/data-model.md). Only changes are listed; the 001 document
is updated in place to the new names during implementation.

## Renamed entities and fields

| 001 | 002 |
|---|---|
| `ServerConfig` (root) | `ProfileConfig` (root) |
| `stages` / `StageConfig` | `groups` / `GroupConfig` |
| `servers` / `ServerEntryConfig` | `nodes` / `NodeConfig` |
| `EffectiveServerConfig` | `EffectiveNodeConfig` |
| `StageId`, `ServerId` (`<stage>/<server>`) | `GroupId`, `NodeId` (`<group>/<node>`) |
| `Target.Stage` / `Target.Server` | `Target.Group` / `Target.Node` |
| `ServerSummary` | `NodeSummary` |
| `ServerResult` | `NodeResult` |
| fields `stage` / `server` | `group` / `node` |
| `ProcessInstance.node` (Queue Manager column) | `ProcessInstance.inubitNode` |
| `ErrorCode.ENVIRONMENT_UNKNOWN` | `ErrorCode.TARGET_UNKNOWN` |
| — | `ErrorCode.NOT_CONFIGURED` |

Name patterns unchanged: group and node names `^[a-z0-9][a-z0-9-]{0,31}$`; id
`^[a-z0-9][a-z0-9-]{0,31}(/[a-z0-9][a-z0-9-]{0,31})?$`.

## New entities

### Profile (config root fields)

| Field | Type | Rules |
|---|---|---|
| `profile.name` | string | required; `^[a-z0-9][a-z0-9-]{0,31}$`; `audit` is reserved (US2 review P3b) |
| `profile.description` | string | optional; ≤ 200 characters counted as Unicode code points; no control character (`\p{Cc}`: tab, line breaks, escape, …) and no U+2028/U+2029 |
| `terminology.group.singular/plural` | string | default `group`/`groups`; `^\p{L}[\p{L}\p{N} _-]{0,31}$`; written as inside an English sentence (`stage`, `Umgebung`) |
| `terminology.node.singular/plural` | string | default `node`/`nodes`; same pattern; differs from group (case-insensitive) |
| `terminology.group` / `terminology.node` | mapping | omitted or `null` → defaults; a given level, also an empty one (`{}`), needs both `singular` and `plural` |
| `credentials` | mapping | omitted, empty (`credentials:`) or `{}` → absent; a scalar or list value is a credential key (stripped, startup error) |
| `credentials.envPrefix` | string | optional; `^[A-Z][A-Z0-9_]{0,63}$`; default `INUBIT_` + normalize(`profile.name`) |
| `auditDirectory` | path | default `~/.inubit-mcp/<profile.name>/audit` |

### Terminology (domain value object)

`groupSingular`, `groupPlural`, `nodeSingular`, `nodePlural`; `render(template)` replaces `{group}`,
`{groups}`, `{node}`, `{nodes}` with the configured names **exactly** (no case change) and `{Group}`,
`{Groups}`, `{Node}`, `{Nodes}` with the names whose first code point is upper-cased (only at the start of
a sentence or in a title); an unknown `{placeholder}` (letters in braces) is an error. Template grammar
(enforced by `TemplateGrammarTest`): no "a"/"an" directly before a placeholder (invariant determiners such
as the, this, its, one, each, every, all, any, no are fine), no inflection (`{node}s`, `{node}'s`,
`{group}-…`), id forms always `<{group}>/<{node}>`.
`{profile}` is rendered by `ProfileInfo.render(template)`, which knows the profile name (the terminology
does not). Braces around anything else (e.g. `{0,31}` in a pattern) are plain text.

### ProfileInfo (domain value object)

`name`, `description?`, `terminology`, `credentialPrefix` (effective); `render(template)` (terminology plus
`{profile}`). Part of the `list_nodes` result (`profile {name, description?}` and `terminology`, built from
dedicated result records, so `credentialPrefix` can never be serialized) and of every audit record (`name`
only).

## Names that keep their INUBIT meaning

The neutral level names `group`/`node` do not replace INUBIT's own vocabulary where a field carries an
INUBIT value; these fields are never rendered with the terminology:

| Field | Meaning |
|---|---|
| `InventoryItem.group`, `InventoryDetail.group`, `list_inventory` input `group` | INUBIT's diagram group, or a module's module group (= plugin type); **not** the configured group level |
| `LogEntry.fields.node` (`query_logs`) | a raw INUBIT log column (`node`) passed through as is; not the configured node |
| `ProcessInstance.inubitNode` (`find_processes`) | the INUBIT `node` column of the Queue Manager row |
| "workflow nodes" in descriptions | the elements of an INUBIT workflow that reference a module |

## Changed rules

- `Defaults.inventory.owner`: **no built-in default** (001: a customer-specific built-in value). Effective owner = node → group →
  defaults (`EffectiveNodeConfig.Inventory.owner` is `Optional`); absent → inventory capabilities return `NOT_CONFIGURED` for
  that node before it is contacted. `--check-config` warns once when no owner is set anywhere while a CLI is configured.
- Credentials: `<PREFIX>_<GROUP>[_<NODE>]_<KIND>`, PREFIX per profile (see above); collision detection
  and unmatched-variable warning on the effective prefix.
- Audit record: + `profile`; `server` → `node`; `stage` → `group`; field order in the tools delta contract.
- Export directory name: `inubit-mcp-export-<profile>-<pid>-<random>`; stale sweep only for the own
  profile (+ legacy 001 names `inubit-mcp-export-<pid>-<random>`), owner = current user, pid dead, no
  symlinks.
