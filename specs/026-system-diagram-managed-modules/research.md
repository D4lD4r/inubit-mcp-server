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

**Decision**: a new capability `MODULE_MANAGEMENT(READ, {REST})`: 8.1 supported; 9.0 refused as "not
yet verified" until a 9.0 recording of an `sdExtSystem` module detail exists; 6.1, 7.4 and unknown
versions refused. A refusal does not fail the tool: the section gets status `unknown` with the
gate's reason (FR-015).

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

**Open (resolved by T-record)**: the form of `ResourceType` values (the probe recorded names and types
only). If it is a plugin name such as `HTTP Connector`, the comparison applies; if it is a class name,
a mapping table from the recording is added, or the fallback stays. Either way the result never
silently drops a link.

## D-4 Management index and cache (FR-016)

**Decision**: a pure application type `ManagementIndex` per server and owner: the system diagrams
checked, per external system element (`DiagramRef`, element name, node id, connector type, masked
central settings, linked names), the unreadable items (first one plus count, with error code and
message), `collectedAt`. Cached in the shared `InventoryCache` under `(server, DIAGRAM,
"management:<owner>")`, single-flight and TTL `inventory.cacheTtl` as today; an index with
unreadable items is served once but not cached (as the module usage index). `get_inventory_item`
passes its `refresh`; `read_model_details` always `false`. The system diagram list comes from the
inventory diagram list `(server, DIAGRAM, "all")`, filtered to type `systemdiagram`.

**Alternatives**: reuse the partner snapshot (`partners:<owner>`): it holds only masked clouds; adding
elements would couple two features and the export channel (D-1). A new cache: unnecessary.

## D-5 Budget and asynchronous read (FR-017)

**Decision**: like the partner usage of feature 018, the index is loaded on a virtual thread and the
caller waits at most `managementBudget = 4 × timeout` (`timeout` = the server's REST timeout): one
round for the diagram list (usually cached), one for the `modelByName` reads and one for the
`nodeByModel` reads (each with a concurrency of 4), one for the own-properties read of D-6. On
timeout the section is `unknown` (or incomplete `managed`) with "the read goes on and
fills the cache"; the item deadline of `get_inventory_item` (MODULE and DIAGRAM) and the call
deadline of `read_model_details` grow by `managementBudget`. The own-properties read of D-6 counts
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
management service. Without an owner: section `unknown`, `NOT_CONFIGURED`. A `diagram` not in the
owner's diagram list (cached `(server, DIAGRAM, "all")`): section `unknown` with "the diagram does not
belong to the configured owner". A module whose type starts with `sd`: no section. The module's own
properties are read and returned as today, also when the section is `unknown`. `Wiring` creates
`ModelDetailService` after `InventoryCache`, `InventoryService` and the new service (reorder).

## D-8 System diagram detail (FR-010)

**Decision**: `get_inventory_item` with `kind: DIAGRAM` for a diagram of type `systemdiagram` adds
`managedModules`: the index entries of that diagram (from the cached index, same budget), each with
connector type, masked settings and linked names; a name is marked `found: false` when the owner's
module list (cached `(server, MODULE, "all")`) has no module of that name, and has no `found` field
when the module list could not be read (the list then carries `moduleListUnavailable`). An element
whose detail could not be read appears with name, node id and error.

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
section sits inside the module item (`InventoryDetail.management`, `ModuleDetail.management`), so a
stage call shows it per server. Bounds: at most 10 elements per section, 100 linked names per
element, 50 elements per system diagram detail; settings follow `ItemBounds`; cuts set the existing
`truncated` flag and a per-list `truncated`.

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
