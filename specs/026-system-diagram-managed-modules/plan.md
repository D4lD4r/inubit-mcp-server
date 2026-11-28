# Implementation Plan: Modules Managed by a System Diagram

**Branch**: `026-system-diagram-managed-modules` | **Date**: 2026-11-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/026-system-diagram-managed-modules/spec.md`
(approved in spec review round 2).

## Summary

No new tool. A fixed check adds a `management` section to every found module of
`get_inventory_item` and to `read_model_details` (`action: module`), and `managedModules` to the
detail of a system diagram.

- **Channel by evidence** (research D-1): per system diagram the element list (`modelByName`), per
  `sdExtSystem` element its module detail (`nodeByModel`), both existing reads verified live on
  8.1.17; the module-detail parser of PR #113 masks the central settings per entry.
- **Capability** `MODULE_MANAGEMENT` (D-2, owner decision 2026-11-28): 8.1 supported (recorded
  endpoint contracts plus the live run on real `sdExtSystem` answers); everything else refused; a refusal gives status `unknown`,
  never a failed tool call.
- **Module list** (D-13): the check never starts the StartCLI module export; `get_inventory_item`
  passes the list its lookup loaded, the other paths use only a cached list, else a documented
  fallback. The check stays REST-only.
- **JSON** (D-10): `ResultJson` serializers for `ModuleDetail.Property` and the enums, so both tools
  write the same section.
- **Matching** (D-3): exact module name; shared names narrowed by plugin type vs. `ResourceType`;
  every ambiguity is a warning, never a dropped link.
- **Index and cache** (D-4): one `ManagementIndex` per server and owner in the shared
  `InventoryCache` (`management:<owner>`), loaded on a virtual thread within
  `managementBudget = 4 × timeout` (D-5); partial loads are served, not cached.
- **Comparison** (D-6): non-secret top-level settings that differ from the module's own properties;
  `get_inventory_item` reads those only for managed modules.
- **`read_model_details`** (D-7) gets the owner, the diagram list and the check; foreign diagrams and
  missing owners give `unknown`; `sd*` elements carry no section.

## Technical Context

**Language/Version**: Java 21 (unchanged).

**Primary Dependencies**: unchanged (MCP Java SDK, Jackson, logback). No new dependency.

**Storage**: none new; the masked `ManagementIndex` lives in the in-memory `InventoryCache`.

**Testing**:
- JUnit 5, AssertJ; WireMock through `InventoryToolFixture` and the model-detail contract setup;
- synthetic fixtures from the probed structure (research D-11): the existing
  `admin-model-node-by-model-sd-ext-system.xml` (parser tests) and the synthetic set of tasks.md
  T002 (diagram list, system diagram and workflow `modelByName`, both element details, the managed
  module's detail, a StartCLI module export), wired by a new `ManagementFixture`; recorded ones
  replace them in T-record;
- `ManagementMatcher` as pure unit tests (state rules, shared names, comparison);
- secret-leak tests in `security/` (`ManagementSecretLeakTest`: result, error excerpts, logs,
  cache); `NoCustomerIdentifiersTest` and `tools/check-identifiers.py`;
- `PackageBoundaryTest` keeps HTTP types out of `application` and `domain`.

**Target Platform**: macOS, Linux, Windows (REST only).

**Project Type**: single Maven module, shaded JAR.

**Performance Goals**: SC-005: an owner with ten system diagrams and 20 elements loads within
`managementBudget` on a responsive node (≈ 3 request rounds with concurrency 4); SC-004: a second
call within `inventory.cacheTtl` makes no system diagram read.

**Constraints**:
- no raw secret in any new type (only parser-masked `ModuleDetail.Property`);
- read-only: only `GET modelByName`, `GET nodeByModel`, the cached diagram and module lists;
- the module's own properties of `read_model_details` stay byte-for-byte as today;
- bounded output (research D-10); no customer identifiers in code, fixtures, docs or commits.

**Scale/Scope**:
- 0 new tools (count unchanged); `get_inventory_item` gains `management` and `managedModules`;
  `read_model_details` gains `module.management`; `list_nodes`/`--check-config` show
  `module_management`.
- New production types (about 17): `Reason`, `ExternalSystem`, `ExternalSystems`, `Unreadable`,
  `ManagementIndex`, `ManagementSection`, `ManagingElement`, `Difference`,
  `SystemDiagramManagement`, `ElementEntry`, `LinkedModule` (domain); `ModuleTarget`, `OwnProperties`,
  `ManagedModuleDetail`, `ManagementService`, `ManagementMatcher`, `ManagementBounds` (application).
- Extended: `Capability`, `CapabilityMatrix`, `NodeOverview`, `ConfigSummary`, `InventoryDetail`,
  `InventoryBounds`, `InventoryService`, `ModelDetailService`, `ReadModelDetailsTool`,
  `GetInventoryItemTool` (description), `Wiring`, `infra/ResultJson`, both output schemas,
  `list_nodes.output.json`, `interface-coverage.tsv`,
  `tools/record-fixtures.sh`, `tools/anonymize.py`, docs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ✅ read only | ✅ <ul><li>Only GETs that the two tools already send; no write interface; read-only hints unchanged.</li><li>No new input parameter.</li></ul> |
| II. Secrets Stay Out | ⚠️ central settings hold passwords, trust stores, OAuth secrets | ✅ <ul><li>Settings come only from the module-detail parser, masked per entry (PR #113, review-hardened excerpt masking).</li><li>New types hold only masked `Property` values and names; no raw XML retained.</li><li>Comparison skips masked values and `${secret:` placeholders (D-6).</li><li>Leak tests on result, error excerpts, logs and cache.</li></ul> |
| III. Test-First | ⚠️ `sdExtSystem` only probed (structure), not recorded | ✅ <ul><li>Synthetic fixtures with the probed structure and planted secrets drive every task test-first.</li><li>8.1 is supported on the recorded 8.1 contracts of both endpoints and the live run of 2026-11-27 on real `sdExtSystem` answers (research D-2, owner decision); T025 records `modelByName` and `nodeByModel` of a real system diagram, neutralized, and swaps the synthetic fixtures when a non-production node is reachable.</li></ul> |
| IV. Use-Case-Oriented Tools | ✅ no new tool | ✅ The check lives where agents already look (spec option (b)); descriptions point to it (FR-012). |
| V. Adapter Isolation & Version Compatibility | ✅ existing adapters | ✅ <ul><li>Uses `InventoryPort` and `ModelDetailPort` only; no HTTP in `application`; never the StartCLI module export (D-13).</li><li>New capability `MODULE_MANAGEMENT`: 8.1 supported, 9.0 "not yet verified" (D-2); the docs state it; no version branching in tool logic.</li></ul> |
| VI. Bounded, Structured Output | ✅ | ✅ Fixed bounds (≤ 10 elements per section, ≤ 50 per diagram, ≤ 100 names per element, `ItemBounds` for values), `truncated` flags; failures as structured reasons with next step. |
| VII. Observability & Auditability | ✅ read only | ✅ One DEBUG line per index load (server, owner, counts, duration); no audit record (read, like the partner usage). |
| Tech constraints | ✅ | ✅ No new dependency; TLS and pinning unchanged. |

No violation; Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
specs/026-system-diagram-managed-modules/
├── spec.md
├── plan.md                         # this file
├── research.md                     # D-1 … D-12
├── data-model.md                   # domain and application types, state rules
├── quickstart.md                   # validation scenarios V-1 … V-18, live acceptance
├── contracts/
│   └── management-section.md       # management, managedModules, descriptions, other surfaces
├── checklists/requirements.md
├── reviews/
└── tasks.md                        # /speckit-tasks
```

### Source Code (repository root)

```text
src/main/java/de/dadecker/inubit/mcp/
├── domain/model/
│   ├── Capability.java, CapabilityMatrix.java         # + MODULE_MANAGEMENT
│   ├── InventoryDetail.java                           # + management, managedModules
│   └── management/                                    # new package
│       ├── Reason.java, ExternalSystem.java, ExternalSystems.java, Unreadable.java
│       ├── ManagementIndex.java, ManagementSection.java, ManagingElement.java
│       ├── Difference.java, SystemDiagramManagement.java, ElementEntry.java, LinkedModule.java
│       └── package-info.java
├── application/
│   ├── ManagementService.java                         # index load, cache, budget, gate
│   ├── ManagementMatcher.java                         # pure match + compare, state rules
│   ├── ModuleTarget.java, OwnProperties.java
│   ├── ManagementBounds.java
│   ├── InventoryService.java, InventoryBounds.java    # MODULE + DIAGRAM hooks, deadlines
│   ├── ManagedModuleDetail.java                       # ModuleDetail + management
│   └── ModelDetailService.java                        # owner, diagram list, check
├── mcp/tools/
│   ├── GetInventoryItemTool.java                      # description
│   └── ReadModelDetailsTool.java                      # Module.management, description
├── infra/ResultJson.java                              # Property, Status, Kind serializers
└── Wiring.java                                        # order: cache → management → inventory → model details
src/main/resources/
├── schemas/get_inventory_item.output.json             # $defs.Management, $defs.SystemDiagramManagement
├── schemas/read_model_details.output.json             # $defs.Management
├── schemas/list_nodes.output.json                     # module_management
└── coverage/interface-coverage.tsv                    # nodeByModel for get_inventory_item
src/test/java/de/dadecker/inubit/mcp/
├── domain/model/management/*Test.java
├── application/ManagementMatcherTest.java, ManagementServiceTest.java
├── application/InventoryServiceManagementTest.java, ModelDetailServiceManagementTest.java
├── mcp/tools/GetInventoryItemToolTest.java, ReadModelDetailsToolTest.java   # pinned descriptions, schema
└── security/ManagementSecretLeakTest.java
src/test/resources/fixtures/v8_1/rest/
├── admin-model-node-by-model-sd-ext-system.*          # existing (PR #113), parser tests
├── admin-model-node-by-model-central-backend.*        # new, synthetic (node 201)
├── admin-model-node-by-model-sd-ext-system-2.*        # new, synthetic (node 202)
├── model-models-management.*, model-by-name-systemdiagram.*, model-by-name-tw-out.*  # new, synthetic
└── admin-model-node-by-model-managed-module.*         # new, synthetic (node 301)
src/test/resources/fixtures/v8_1/cli/export_modules_management.*   # new, synthetic module list
tools/record-fixtures.sh, tools/anonymize.py           # T-record cases, ResourceSettings neutralization
docs/tools.md, docs/interface-coverage.md, CHANGELOG.md
```

**Structure Decision**: single project, as all earlier features; the new domain package mirrors
`domain/model/partner` of feature 018.

## Implementation order

1. **Foundation**: capability and matrix (8.1 supported); domain types with invariants;
   `ExternalSystems.from` on the PR #113 fixture; `ResultJson` serializers; synthetic fixtures.
2. **US1 core** (P1): `ManagementMatcher` (state rules, D-3, D-6, D-13) → `ManagementService` (index
   load, cache, budget, gate) → `InventoryService` MODULE hook with its constructor, every
   construction site and `Wiring` in the same task (the build stays green) → bounds → schema and
   tool test → coverage → leak test.
3. **US2** (P2): `ModelDetailService` owner/diagram-list/check with its constructor, construction
   sites and the `Wiring` reorder in one task → `read_model_details` schema and tool test.
4. **US3** (P2): `forSystemDiagram`, `InventoryService` DIAGRAM hook, `managedModules` schema.
5. **US4** (P3): descriptions, pinned tests, docs (`tools.md`, interface coverage), CHANGELOG.
6. **Recording** (T022–T026): recorder cases and anonymizer (agent); recording (operator, needs a
   reachable non-production node); recorded fixtures replace the synthetic ones, D-3's
   `ResourceType` form is settled; a mapping table or another element type
   only if the recording requires it (conditional T026).
7. **Live acceptance** (T027, operator): 8.1 with a build of the branch; SC-001 run.

US2 and US3 depend on US1's service and wiring; US4 can follow any time after US1. Phases 1–6 of
tasks.md form the feature PR; T022–T027 (recording, live acceptance) land as their own PR when a
non-production node is reachable.

## Complexity Tracking

No constitution violations; nothing to justify.
