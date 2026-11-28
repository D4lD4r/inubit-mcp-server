# Research: Modules Managed by a System Diagram

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-11-28

## Evidence

- **Live probe 2026-11-27** (8.1.17, non-production node, structure only: element names, `name` and
  `type` attributes, no values; see `fixtures/v8_1/rest/admin-model-node-by-model-sd-ext-system.README`):
  - `get_inventory_item` (`kind: DIAGRAM`) of a system diagram lists its elements from
    `GET /model/modelByName/<name>?user=` as `ModuleRef(name, type, nodeId)`: two `sdExtSystem`, one
    `sdInubitIS`.
  - `GET /model/nodeByModel/<diagram>/<nodeId>` of an `sdExtSystem` answers `ns2:Property`
    `ResourceType` (String), `ResourceSettings` (Map: `ServerName`, `Login`, `Password` (Password),
    `SSLKeyStore` (HTTPConnectorTrustStore), and on one element 13 OAuth entries) and
    `ResourcesLinked` (List of `Module` (String)). Since PR #113 `read_model_details` reads it, masking
    per entry.
  - With the fixed build, each of the two elements linked one module by its exact name; one of them
    was an HTTP Connector whose own `ServerName` (read with `read_model_details`) was a local dummy
    endpoint, while the element's `ServerName` was another server.
- **Recorded system diagram exports** (`fixtures/v8_1/rest/model_export_systemdiagram.zip`,
  `fixtures/v8_1/cli/export_systemdiagram.zip`, feature 018) contain only `sdCommunicationCloud`
  modules. Whether the REST export's `workflow.xml` carries the `sdExtSystem` properties is **not
  recorded**; a probe on 2026-11-28 could not run (all non-production nodes unreachable).
- **Code base** (see the code map in the plan): `get_inventory_item` does not read module properties
  today; `read_model_details` has no owner, cache or deadline; `InventoryCache` is shared by
  `InventoryService` and `PartnerService`; `ModuleUsage` matches node names to module names exactly
  and tells shared names apart by plugin type.

## D-1 Channel: element list plus module detail per external system

**Decision**: Per system diagram, read the element list with `modelByName` (`InventoryPort.diagramDetail`,
already used by `get_inventory_item`) and, for every element of type `sdExtSystem`, its module detail
with `nodeByModel` (`ModelDetailPort.module`, already used by `read_model_details`).

**Rationale**: both reads and their parsers exist and were verified live for exactly this data; the
module-detail parser already masks `ResourceSettings` per entry (PR #113, SecretRules +
SecretTextMasker) and bounds depth. No new INUBIT endpoint, no new parser for secrets.

**Alternatives considered**:
- REST diagram export (`/model/export/<name>`, one request per system diagram, the channel of
  feature 018): fewer requests, but the `sdExtSystem` shape in `workflow.xml` is unrecorded, its
  masking would need `PartnerSecretRules` (package-private, partner-specific) and the anonymizer does
  not neutralize `ResourceSettings`. Kept as a later optimization: task T-record also records the
  export of the same system diagram; if it carries the same properties, a follow-up can switch.
- StartCLI `systemdiagram` export: slower (process start, export trace on INUBIT) and CLI-only.

**Cost**: per server and owner, one `modelByName` per system diagram plus one `nodeByModel` per
external system element (probe: 10 system diagrams; an HTTP diagram had 2 elements). Cached (D-4).

## D-2 Capability `MODULE_MANAGEMENT`

**Decision** (owner decision 2026-11-28, plan review round 2): a new capability
`MODULE_MANAGEMENT(READ, {REST})`: **8.1 supported**; 9.0 refused as "not yet verified" until a 9.0
recording of an `sdExtSystem` module detail exists; 6.1, 7.4 and unknown versions refused. A
refusal does not fail the tool: the section gets status `unknown` with the gate's reason (FR-015).

**Why 8.1 is supported before T025** (Principle III, V): both endpoints the check uses have recorded
8.1 contracts (`modelByName_sample.*`, feature 001; `admin-model-node-by-model*`, feature 022 T039),
and on 2026-11-27 the fixed build parsed real `sdExtSystem` answers of an 8.1.17 node live
(`ResourceType`, `ResourceSettings`, `ResourcesLinked`, masking per entry). What is not yet recorded
is only the neutralized copy of those answers; T025 replaces the synthetic fixtures by it and
settles D-3. FR-015 is met from the first merge; no deviation is needed.

**Rationale**: the check reads through two existing capabilities (`INVENTORY` for `modelByName`,
`MODEL_DETAILS` for `nodeByModel`), but whether the result is right depends on the `sdExtSystem`
shape, which is verified on 8.1 only. A separate capability states exactly that in `list_nodes` and
`--check-config` and keeps `INVENTORY`/`MODEL_DETAILS` unchanged on 9.0.

**Alternatives**: gate by `MODEL_DETAILS` alone (would claim 9.0 support without evidence); gate by
`PARTNER_MANAGEMENT` (unrelated data).

## D-3 Matching and shared names (FR-003)

**Decision**: an element links a module if a `ResourcesLinked/Module` value equals the module name
(exact, case-sensitive, no trim). If the owner's module list has several modules of that name, the
link goes to those whose plugin type (`ModuleEntry.type`, from `PluginName`) equals the element's
`ResourceType` ignoring case; if none or more than one remains, or `ResourceType` is absent or not a
plugin name, every module of that name is linked with the warning "cannot be attributed to a single
module". A single module whose plugin type is known and differs gets the mismatch warning.

**Scope for implementation**: T007 implements exactly this plugin-name comparison plus the
fallback. The form of `ResourceType` values is not recorded (the probes printed names and types
only); T025 records it. If it is not a plugin name (e.g. a class name), the conditional task T026
adds a mapping table; until then such values simply take the fallback. The same conditional task
covers another element type with `ResourcesLinked`, should the recording show one (spec
Assumptions). Either way the result never silently drops a link.

## D-4 Management index and cache (FR-016)

**Decision**: an immutable domain type `ManagementIndex` (`domain/model/management`) per server and
owner: the system diagrams
checked, per external system element (`DiagramRef`, element name, node id, connector type, masked
central settings, linked names), the unreadable items (first one plus count, with error code and
message), `collectedAt`. Cached in the shared `InventoryCache` under `(server, DIAGRAM,
"management:<owner>")`, single-flight and TTL `inventory.cacheTtl` as today; an index with
unreadable items is served once but not cached (as the module usage index). `get_inventory_item`
passes its `refresh`; `read_model_details` always `false`. The system diagram list comes from the
inventory diagram list `(server, DIAGRAM, "all")` (REST `GET /model/models`), filtered to type
`systemdiagram`. The index holds no module list (D-13).

**Alternatives**: reuse the partner snapshot (`partners:<owner>`): it holds only masked clouds; adding
elements would couple two features and the export channel (D-1). A new cache: unnecessary.

## D-5 Budget and asynchronous read (FR-017)

**Decision**: like the partner usage of feature 018, the index is loaded on a virtual thread and the
caller waits at most `managementBudget = 4 × timeout` (`timeout` = the server's REST timeout): one
round for the diagram list (usually cached), one for the `modelByName` reads and one for the
`nodeByModel` reads (each with a concurrency of 4), one for the own-properties read of D-6. A round
holds several waves of 4 requests (10 system diagrams are 3 waves, 20 elements 5), so the budget
holds on a responsive node (requests well below `timeout`, V-22); on a slow node it is reached by
design (FR-017) and the next call is served from the cache. On timeout the section is `unknown`
(`TIMEOUT`, no `firstNotRead`) with "the read goes on and fills the cache"; the item deadline of `get_inventory_item` (MODULE and DIAGRAM) grows by `managementBudget`; `read_model_details` has no call deadline and none is added: its check is bounded by the `budget` argument (`managementBudget`) of `forModule`. The own-properties read of D-6 counts
against the same budget.

## D-6 The module's own properties for the comparison (FR-007)

**Decision**: `read_model_details` already has the module's properties. `get_inventory_item` reads
them only for a managed module: `modelByName` of the first using technical workflow (from the usage
index) gives the module's node id, then `nodeByModel`. Not cached (two requests, only for managed
modules). If the module is used by no technical workflow, or a read fails, the section says the
comparison is unavailable with the reason; status and elements are unaffected.

**Comparison**: for each central entry that is shown (not masked) and whose value contains no
`${secret:` placeholder, and a module property of the same name that is shown, the values differ
if they are not equal as strings. Nested entries (a central entry that is itself a container) are not
compared.

## D-7 `read_model_details` and the owner (FR-009)

**Decision**: `ModelDetailService` gets the configured owner per server, the diagram list and the
management service; `module(...)` returns `Read<ManagedModuleDetail>` (application record:
`ModuleDetail module`, `Optional<ManagementSection> management`), which the tool maps to
`Result.module`. Without an owner: section `unknown`, `NOT_CONFIGURED`. A `diagram` not in the
owner's diagram list (cached `(server, DIAGRAM, "all")`): section `unknown` with "the diagram does not
belong to the configured owner". A module whose type starts with `sd`: no section. The module's own
properties are read and returned as today, also when the section is `unknown`. `Wiring` creates
`ModelDetailService` after `InventoryCache`, `InventoryService` and the new service (reorder).

## D-8 System diagram detail (FR-010)

**Decision**: `get_inventory_item` with `kind: DIAGRAM` for a diagram of type `systemdiagram` adds
`managedModules`: the index entries of that diagram (from the cached index, same budget), each with
connector type, masked settings and linked names; a name is marked `found: false` when the owner's
module list (only if already cached, D-13) has no module of that name, and has no `found` field when
no cached list exists (the list then carries `moduleListUnavailable`). An element whose detail could
not be read appears with name, node id and error. When the index cannot be had at all (gate refused,
diagram list failed, budget reached), or the index has no usable entry for this diagram (the diagram
is itself an unreadable `SYSTEM_DIAGRAM` item; or it is missing from a cached index because it was
created after the index was collected), `managedModules` carries only `unavailable` with the reason:
the gate's, the `DIAGRAM_LIST` or `SYSTEM_DIAGRAM` item's code and message with a fixed next step, the
budget's `TIMEOUT` ("the read goes on and fills the cache"), or `NOT_FOUND` "the system diagram is not
in the index collected at <time>; call again with `refresh: true`". `collectedAt` is present whenever
an index was had (also with a failed diagram list). The gate's warnings go to
`managedModules.warnings`.

## D-9 Secrets

**Decision**: central settings are `ModuleDetail.Property` values from the module-detail parser
(masked per entry, secret-named containers as a whole, credentials inside values replaced). The
management types hold only these masked values; no raw XML or value is kept. Linked module names are
not secret. The section is not audited (a read, like the partner usage); one DEBUG log line per index
load with server, owner, counts and duration, no values. Leak tests cover result, error excerpts,
logs and the cache contents.

## D-10 Output shape

**Decision**: one section shape `management` in both tools (contracts/management-section.md); status
values `managed`, `not_managed`, `unknown` (JSON enum spelling of the spec's "not managed"). The
section sits inside the module item (`InventoryDetail.management`, `ReadModelDetailsTool.Module.management`), so a
stage call shows it per server. Bounds: at most 10 elements per section, 100 linked names per
element, 50 elements per system diagram detail; settings follow `ItemBounds`; cuts set the existing
`truncated` flag and a per-list `truncated`.

**JSON**: `infra/ResultJson.java` registers serializers (as it does for `NodeId` and others) for
`ModuleDetail.Property` (the contract form `{name, type, value, truncated?}` |
`{name, type, masked: true}` | `{name, type, entries}`, the form `ReadModelDetailsTool.Property`
already writes), `ManagementSection.Status` (`managed`, `not_managed`, `unknown`) and
`Unreadable.Kind` (`diagramList`, `systemDiagram`, `element`), plus `NON_EMPTY` mixins (as
`HealthReportJson`) for `ManagementSection.elements` and `ManagingElement.differing`, so empty lists are left out where the contract says "when non-empty"; `ElementEntry.settings`/`linked` are `Optional<List<…>>` (empty with `error`, else written also when `[]`), and `warnings` and the `settings` of a managing element stay, also when empty. `read_model_details` carries the domain `ManagementSection` in
`ReadModelDetailsTool.Module.management` (the service returns it next to the `ModuleDetail`, D-7);
both tools serialize it through `ResultJson`, so they write the same JSON by construction; a test
compares the two.

## D-11 Fixtures and recording

**Decision**: tests run on synthetic fixtures built from the probed structure: the existing
`admin-model-node-by-model-sd-ext-system.xml` plus a synthetic `modelByName` answer of a system
diagram (`sdExtSystem` ×2, `sdInubitIS`, `sdCommunicationCloud`) and a managed module's own detail.
Task T-record (owner-gated, needs a reachable non-production node and the operator's credentials)
adds recorder cases for `modelByName` of a system diagram, `nodeByModel` of an `sdExtSystem` and
the REST export of the same system diagram (D-1 alternative), extends `tools/anonymize.py` to
neutralize `ResourceSettings` values and `ResourcesLinked` module names, and replaces the synthetic
fixtures by recorded ones; it also resolves D-3's open point.

## D-12 Tool descriptions and docs (FR-012)

**Decision**: one sentence in each description: "A connector module's connection settings can be
managed centrally by a system diagram; the result's `management` says whether, by which element, and
with which settings — read it before trusting the module's own connection properties." The pinned
description tests, `docs/tools.md` (sections, field tables, rendered descriptions) and the
`DevelopmentToolsReferenceTest` expectations are updated; `docs/interface-coverage.md` and
`interface-coverage.tsv` gain the `get_inventory_item` use of `nodeByModel`.

## D-13 The owner's module list (FR-003, FR-010) and completeness

**Fact**: the owner's module list `(server, MODULE, "all")` is a StartCLI export
(`V81InventoryAdapter.listModules` → module export, bounded by `cliExportTimeout`, holding the
credential guard), not a REST list.

**Decision**: the check never starts that export.
- `get_inventory_item` (`kind: MODULE`) passes the module list its own lookup has just loaded (it
  needs it to find the module), so shared names are always resolved there.
- `read_model_details` and the system diagram detail use only an already cached list
  (`InventoryCache.peek`). Without one, a managed module takes FR-003's fallback with the warning
  "module list not loaded; names are not checked for duplicates (a `get_inventory_item` or
  `list_inventory` call with `kind: MODULE` loads it)", and the system diagram detail shows the
  linked names without `found` marks plus `moduleListUnavailable` with the same reason.
- The capability stays REST-only and the budget stays `4 × timeout`; REST-only setups work.

**Completeness**: `complete` and `firstNotRead` describe the index only (an `UNKNOWN` reason taken from
`firstNotRead` gets a fixed next step per kind: diagram list — "check `get_health` and
`list_inventory` with `type: systemdiagram`, then retry"; system diagram or element — "check the
system diagram with `get_inventory_item`, then retry with `refresh: true`") (the diagram list, the
system diagrams, the elements). The module list never makes the check incomplete and never
changes the status: it is only needed when a link was found, and then its absence is a warning (the
spec's FR-005 list naming is met by the warning text and by `moduleListUnavailable`). A failed
diagram list gives `unknown`, `complete: false`, `systemDiagramsChecked: 0` and `firstNotRead` of
kind `diagramList`.
