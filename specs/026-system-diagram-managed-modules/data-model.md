# Data Model: Modules Managed by a System Diagram

**Feature**: [spec.md](spec.md) | **Research**: [research.md](research.md)

All types are immutable records; lists are copied. Domain types live in `domain/model/management`,
application types in `application`. No type holds a raw secret: settings are
`ModuleDetail.Property` values, already masked by the module-detail parser (research D-9). JSON is
written by `infra/ResultJson` (research D-10).

## Domain (`domain/model/management`)

### Reason

| Field | Type | Rule |
|---|---|---|
| `code` | `ErrorCode` | |
| `message` | String | ≤ `ItemBounds.MAX_MESSAGE_CHARS`, no excerpt |
| `nextStep` | String | ≤ `ItemBounds.MAX_MESSAGE_CHARS` |

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

An item of the index that could not be read (research D-13: the module list is not one).

| Field | Type | Rule |
|---|---|---|
| `kind` | enum `DIAGRAM_LIST`, `SYSTEM_DIAGRAM`, `ELEMENT` | JSON `diagramList`, `systemDiagram`, `element` |
| `name` | `Optional<String>` | system diagram or element name; empty for `DIAGRAM_LIST` |
| `systemDiagram` | `Optional<DiagramRef>` | for `ELEMENT` |
| `code` | `ErrorCode` | |
| `message` | String | ≤ `ItemBounds.MAX_MESSAGE_CHARS`, no excerpt |

### ManagementIndex

Everything the check knows about one server and owner (research D-4).

| Field | Type | Rule |
|---|---|---|
| `owner` | String | |
| `systemDiagrams` | `List<DiagramRef>` | all system diagrams of the owner in diagram-list order; empty if the diagram list failed |
| `externalSystems` | `List<ExternalSystem>` | of the readable system diagrams, in diagram order, then element order |
| `unreadable` | `List<Unreadable>` | in the order met; a `DIAGRAM_LIST` item is the only item when present |
| `collectedAt` | `Instant` | when the load finished |

`complete()` = `unreadable` is empty. `linking(name)` = the external systems whose `linkedNames`
contain `name` (exact). `systemDiagramsNotRead()` = number of `SYSTEM_DIAGRAM` items plus the system
diagrams with an unreadable `ELEMENT`.

### ManagementSection

The answer for one module on one server (contracts/management-section.md §1).

| Field | Type | Rule |
|---|---|---|
| `status` | enum `MANAGED`, `NOT_MANAGED`, `UNKNOWN` | JSON `managed`, `not_managed`, `unknown`; state rules below |
| `complete` | boolean | `index.complete()` when an index was had, else `false` |
| `owner` | `Optional<String>` | empty only for `NOT_CONFIGURED` |
| `collectedAt` | `Optional<Instant>` | the index's, when an index was had |
| `systemDiagramsChecked` | int ≥ 0 | `systemDiagrams.size() − systemDiagramsNotRead()`: system diagrams read completely |
| `systemDiagramsNotRead` | int ≥ 0 | `index.systemDiagramsNotRead()` |
| `firstNotRead` | `Optional<Unreadable>` | present iff an index was had and it is not complete (a failed diagram list included) |
| `elements` | `List<ManagingElement>` | non-empty iff `MANAGED`; ≤ 10 (the matcher cuts and sets `truncated`) |
| `warnings` | `List<String>` | managed more than once; cannot be attributed; type does not match; module list not loaded; incomplete; the version gate's warnings, if any |
| `reason` | `Optional<Reason>` | present iff `UNKNOWN`; from `firstNotRead` its code and message plus the fixed next step per kind of research D-13 |
| `truncated` | boolean | |

**State rules** (FR-001 … FR-005, research D-13):

```text
 gate refused │ no owner │ foreign diagram │ budget reached ──► UNKNOWN (reason; no index, no firstNotRead)

 index had ──► links found? ──yes──► MANAGED  (complete = index.complete(); firstNotRead if incomplete)
                    │
                    no ──► index.complete()? ──yes──► NOT_MANAGED (complete = true)
                                    │
                                    no ──► UNKNOWN (reason from firstNotRead; firstNotRead present;
                                                    a failed diagram list: systemDiagramsChecked = 0)
```

`UNKNOWN` never carries elements; `NOT_MANAGED` is never incomplete and has no `firstNotRead`.

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
| `central` | String | the element's shown value; never contains `${secret:` |
| `module` | String | the module's shown value; never contains `${secret:` |

Only top-level shown entries (research D-6).

### SystemDiagramManagement

For the detail of a system diagram (FR-010, research D-8).

| Field | Type | Rule |
|---|---|---|
| `elements` | `List<ElementEntry>` | ≤ 50, then `truncated`; empty iff `unavailable` |
| `moduleListUnavailable` | `Optional<Reason>` | present iff no cached module list and not `unavailable`; then no `found` marks |
| `unavailable` | `Optional<Reason>` | present iff there is no usable index for this diagram: gate refused, budget reached (`TIMEOUT`), the index's `DIAGRAM_LIST` item, the diagram being an unreadable `SYSTEM_DIAGRAM` item, or the diagram missing from a cached index (`NOT_FOUND`, "call again with `refresh: true`") (research D-8) |
| `collectedAt` | `Optional<Instant>` | present iff an index was had (also with a failed diagram list) |
| `warnings` | `List<String>` | the version gate's warnings, if any (none on 8.1) |
| `truncated` | boolean | |

### ElementEntry

| Field | Type | Rule |
|---|---|---|
| `element` | String | |
| `nodeId` | String | |
| `connectorType` | `Optional<String>` | absent with `error` |
| `settings` | `Optional<List<ModuleDetail.Property>>` | empty Optional with `error`; otherwise the entries, possibly `[]` |
| `linked` | `Optional<List<LinkedModule>>` | empty Optional with `error`; otherwise ≤ 100 entries, possibly `[]` (an element without `ResourcesLinked`), then `truncated` of the enclosing list |
| `error` | `Optional<Reason>` | the element's detail could not be read |

### LinkedModule

| Field | Type | Rule |
|---|---|---|
| `name` | String | |
| `found` | `Optional<Boolean>` | empty iff `moduleListUnavailable` |

## Application

### ManagedModuleDetail

What `ModelDetailService.module` returns (research D-7).

| Field | Type | Rule |
|---|---|---|
| `module` | `ModuleDetail` | the module's own properties, as today |
| `management` | `Optional<ManagementSection>` | empty for a type starting with `sd` |

### ModuleTarget

| Field | Type | Rule |
|---|---|---|
| `name` | String | the module name |
| `pluginType` | `Optional<String>` | from the module list, when known |
| `sameName` | `Optional<List<ModuleEntry>>` | all modules of the owner with this name; empty when no module list is at hand (D-13) |
| `ownProperties` | `Supplier<List<ModuleDetail.Property>>` | the module's own properties, called only after a link was found (D-6); a `ToolErrorException` it throws becomes the `Reason` of `comparisonUnavailable` |

### ManagementService (new)

| Method | Use |
|---|---|
| `ManagementSection forModule(NodeId, String owner, ModuleTarget, boolean refresh, Duration budget)` | `get_inventory_item` MODULE and `read_model_details` |
| `SystemDiagramManagement forSystemDiagram(NodeId, String owner, DiagramRef, boolean refresh, Duration budget)` | `get_inventory_item` DIAGRAM of type `systemdiagram` |

Dependencies: `VersionGate` (capability `MODULE_MANAGEMENT`), `InventoryCache` (`get` for the
diagram list and the index, `peek` for the module list), `GatewayFactory` (inventory port for
`listDiagrams`/`diagramDetail`, model-detail port for `module`), `Clock`, executor for virtual
threads. It never calls `listModules`.

### ManagementMatcher (new, pure)

Two pure steps, no I/O, unit-tested on their own: `ManagementSection match(ManagementIndex, ModuleTarget, List<String> gateWarnings)` applies research D-3, D-13 and the state rules and cuts elements to 10; `ManagementSection compare(ManagementSection, OwnProperties)` adds `differing` or `comparisonUnavailable` to each managing element (D-6). `OwnProperties` (application record): `Optional<List<ModuleDetail.Property>> properties` or `Optional<Reason> unavailable`, exactly one present. `ManagementService.forModule` calls `match`, and only if the result is `MANAGED` the supplier, then `compare`.

## Changed types

| Type | Change |
|---|---|
| `Capability` | `MODULE_MANAGEMENT(READ, {REST})` |
| `CapabilityMatrix` | 8.1 supported; 9.0 refused "not yet verified"; 6.1, 7.4, unknown refused |
| `InventoryDetail` | `Optional<ManagementSection> management` (modules), `Optional<SystemDiagramManagement> managedModules` (system diagrams) |
| `InventoryBounds`, `ManagementBounds` (new) | bounds of both new fields |
| `InventoryService` | MODULE and DIAGRAM lookups call `ManagementService`; deadlines + `managementBudget` |
| `ModelDetailService` | owner function, diagram list, `ManagementService`; returns `Read<ManagedModuleDetail>`; no call deadline, the check bounded by the `budget` argument (research D-5) |
| `ReadModelDetailsTool.Module` | `Optional<ManagementSection> management` |
| `infra/ResultJson` | serializers for `ModuleDetail.Property`, `ManagementSection.Status`, `Unreadable.Kind`; `NON_EMPTY` mixins for `ManagementSection.elements` and `ManagingElement.differing` |
| `Wiring` (root package) | creates `ManagementService` after `InventoryCache`; `ModelDetailService` after `InventoryService` and `ManagementService` |
| `NodeOverview`, `ConfigSummary`, `list_nodes.output.json` | list `module_management` |
