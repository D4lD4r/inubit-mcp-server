# Data Model: Modules Managed by a System Diagram

**Feature**: [spec.md](spec.md) | **Research**: [research.md](research.md)

All types are immutable records; lists are copied. Domain types live in `domain/model/management`,
application types in `application`. No type holds a raw secret: settings are
`ModuleDetail.Property` values, already masked by the module-detail parser (research D-9).

## Domain (`domain/model/management`)

### ExternalSystem

One `sdExtSystem` element of a system diagram, as read with `nodeByModel` (research D-1).

| Field | Type | Rule |
|---|---|---|
| `systemDiagram` | `DiagramRef` (existing, feature 018) | name and diagram group |
| `element` | String | the element's module name (from `modelByName`) |
| `nodeId` | String | 1–10 digits |
| `connectorType` | `Optional<String>` | the shown value of `ResourceType`; empty if absent or masked |
| `settings` | `List<ModuleDetail.Property>` | the entries of `ResourceSettings` in INUBIT's order; one masked property if the container is masked as a whole; empty if absent |
| `linkedNames` | `List<String>` | the shown values of `ResourcesLinked/Module` in INUBIT's order; empty if absent |

Built by `ExternalSystems.from(DiagramRef, ModuleRef, ModuleDetail)`: takes `ResourceType`,
`ResourceSettings` and `ResourcesLinked` by exact name from the detail's top-level properties;
ignores other properties; a `Module` entry that is masked or not a String is skipped with a warning
(not expected; names are not secret).

### Unreadable

| Field | Type | Rule |
|---|---|---|
| `kind` | enum `DIAGRAM_LIST`, `MODULE_LIST`, `SYSTEM_DIAGRAM`, `ELEMENT` | what could not be read |
| `name` | `Optional<String>` | system diagram or element name; empty for the lists |
| `systemDiagram` | `Optional<DiagramRef>` | for `ELEMENT` |
| `code` | `ErrorCode` | |
| `message` | String | bounded by `ItemBounds.MAX_MESSAGE_CHARS`, no excerpt |

### ManagementIndex

Everything the check knows about one server and owner (research D-4).

| Field | Type | Rule |
|---|---|---|
| `owner` | String | |
| `systemDiagrams` | `List<DiagramRef>` | all system diagrams of the owner in diagram-list order |
| `externalSystems` | `List<ExternalSystem>` | of the readable system diagrams, in diagram order, then element order |
| `unreadable` | `List<Unreadable>` | in the order met |
| `collectedAt` | `Instant` | when the load finished |

`complete()` = `unreadable` is empty. `linking(name)` = the external systems whose `linkedNames`
contain `name` (exact).

### ManagementSection

The answer for one module on one server (contracts/management-section.md).

| Field | Type | Rule |
|---|---|---|
| `status` | enum `MANAGED`, `NOT_MANAGED`, `UNKNOWN` | state rules below |
| `complete` | boolean | |
| `owner` | `Optional<String>` | empty only for `NOT_CONFIGURED` |
| `collectedAt` | `Optional<Instant>` | empty when nothing was read |
| `systemDiagramsChecked` | int ≥ 0 | readable system diagrams |
| `systemDiagramsNotRead` | int ≥ 0 | |
| `firstNotRead` | `Optional<Unreadable>` | present iff not `complete` and something was read |
| `elements` | `List<ManagingElement>` | non-empty iff `MANAGED`; at most 10, then `truncated` |
| `warnings` | `List<String>` | managed more than once; ambiguous name; type mismatch; incomplete |
| `reason` | `Optional<Reason>` | present iff `UNKNOWN`: code, message, nextStep |
| `truncated` | boolean | |

**State rules** (FR-001 … FR-005):

```text
                 ┌────────────── gate refused / no owner / foreign diagram ──────────────┐
                 │                                                                       ▼
 load index ──► links found? ──yes──► MANAGED (complete = index.complete())          UNKNOWN
                 │                                                                       ▲
                 no ──► index.complete()? ──yes──► NOT_MANAGED (complete = true)         │
                                    └──────no──────────────────────────────────────────┘
 budget reached before the index is loaded ──► UNKNOWN (TIMEOUT, "the read goes on and fills the cache")
```

`UNKNOWN` never carries elements; `NOT_MANAGED` is never incomplete.

### ManagingElement

| Field | Type | Rule |
|---|---|---|
| `systemDiagram` | `DiagramRef` | |
| `element` | String | |
| `connectorType` | `Optional<String>` | |
| `settings` | `List<ModuleDetail.Property>` | as in `ExternalSystem` |
| `differing` | `List<Difference>` | FR-007; empty if none or unavailable |
| `comparisonUnavailable` | `Optional<Reason>` | present iff the module's own properties could not be read |

### Difference

| Field | Type | Rule |
|---|---|---|
| `name` | String | a non-secret setting present on both sides |
| `central` | String | the element's shown value |
| `module` | String | the module's shown value |

Only top-level shown entries; neither value contains `${secret:` (research D-6).

### SystemDiagramManagement

For the detail of a system diagram (FR-010, research D-8).

| Field | Type | Rule |
|---|---|---|
| `elements` | `List<ElementEntry>` | at most 50, then `truncated` |
| `moduleListUnavailable` | `Optional<Reason>` | present iff the module list could not be read; then no `found` marks |
| `collectedAt` | `Instant` | |
| `truncated` | boolean | |

`ElementEntry`: `element`, `nodeId`, and either `connectorType`, `settings`, `linked`
(`List<LinkedModule(name, Optional<Boolean> found)>`, at most 100) or `error` (`code`, `message`).

## Application

### ManagementService (new)

| Method | Use |
|---|---|
| `ManagementSection forModule(NodeId, String owner, ModuleTarget, boolean refresh, Duration budget)` | `get_inventory_item` MODULE and `read_model_details` |
| `SystemDiagramManagement forSystemDiagram(NodeId, String owner, DiagramRef, boolean refresh, Duration budget)` | `get_inventory_item` DIAGRAM of type `systemdiagram` |

`ModuleTarget`: name, plugin type (if known), and either the module's own properties (from
`read_model_details`) or a supplier that reads them (research D-6).

Dependencies: `VersionGate` (capability `MODULE_MANAGEMENT`), `InventoryCache`,
`GatewayFactory` (inventory port for `listDiagrams`/`diagramDetail`/`listModules`, model-detail port
for `module`), `Clock`, executor for virtual threads.

### ManagementMatcher (new, pure)

`ManagementSection match(ManagementIndex, String name, Optional<String> pluginType,
List<ModuleEntry> sameName, Optional<List<ModuleDetail.Property>> own)`: applies research D-3 and
D-6 and the state rules. No I/O; unit-tested on its own.

## Changed types

| Type | Change |
|---|---|
| `Capability` | `MODULE_MANAGEMENT(READ, {REST})` |
| `CapabilityMatrix` | 8.1 supported; 9.0 refused "not yet verified"; 6.1, 7.4, unknown refused |
| `InventoryDetail` | `Optional<ManagementSection> management` (modules), `Optional<SystemDiagramManagement> managedModules` (system diagrams) |
| `InventoryBounds` | bounds of both new fields |
| `InventoryService` | MODULE and DIAGRAM lookups call `ManagementService`; deadlines + `managementBudget` |
| `ModelDetailService` | owner function, diagram list, `ManagementService`; deadline + `managementBudget` |
| `ReadModelDetailsTool.Module` | `Optional<Management> management` |
| `Wiring` | creates `ModelDetailService` after `InventoryCache`, `InventoryService`, `ManagementService` |
| `NodeOverview`, `ConfigSummary` | list `module_management` |
