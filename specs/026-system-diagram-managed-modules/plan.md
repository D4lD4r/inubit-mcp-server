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
- **Capability** `MODULE_MANAGEMENT` (D-2): 8.1 supported, everything else refused; a refusal gives
  status `unknown`, never a failed tool call.
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
  `admin-model-node-by-model-sd-ext-system.xml`, a new synthetic `model-by-name-systemdiagram.xml`
  and a managed module's own detail; recorded ones replace them in T-record;
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
- New production types (about 11): `ExternalSystem`, `ExternalSystems`, `Unreadable`,
  `ManagementIndex`, `ManagementSection`, `ManagingElement`, `Difference`,
  `SystemDiagramManagement` (domain); `ManagementService`, `ManagementMatcher`,
  `ManagementBounds` (application).
- Extended: `Capability`, `CapabilityMatrix`, `NodeOverview`, `ConfigSummary`, `InventoryDetail`,
  `InventoryBounds`, `InventoryService`, `ModelDetailService`, `ReadModelDetailsTool`,
  `GetInventoryItemTool` (description), `Wiring`, both output schemas, `interface-coverage.tsv`,
  `tools/record-fixtures.sh`, `tools/anonymize.py`, docs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ✅ read only | ✅ <ul><li>Only GETs that the two tools already send; no write interface; read-only hints unchanged.</li><li>No new input parameter.</li></ul> |
| II. Secrets Stay Out | ⚠️ central settings hold passwords, trust stores, OAuth secrets | ✅ <ul><li>Settings come only from the module-detail parser, masked per entry (PR #113, review-hardened excerpt masking).</li><li>New types hold only masked `Property` values and names; no raw XML retained.</li><li>Comparison skips masked values and `${secret:` placeholders (D-6).</li><li>Leak tests on result, error excerpts, logs and cache.</li></ul> |
| III. Test-First | ⚠️ `sdExtSystem` only probed (structure), not recorded | ✅ <ul><li>Synthetic fixtures with the probed structure and planted secrets drive every task test-first.</li><li>T-record (owner-gated) records `modelByName` and `nodeByModel` of a real system diagram, neutralized, and swaps the fixtures; until then 8.1 support rests on the live probe and the live acceptance (T-live).</li></ul> |
| IV. Use-Case-Oriented Tools | ✅ no new tool | ✅ The check lives where agents already look (spec option (b)); descriptions point to it (FR-012). |
| V. Adapter Isolation & Version Compatibility | ✅ existing adapters | ✅ <ul><li>Uses `InventoryPort` and `ModelDetailPort` only; no HTTP in `application`.</li><li>New capability `MODULE_MANAGEMENT`: 8.1 supported, 9.0 "not yet verified" (D-2); no version branching in tool logic.</li></ul> |
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
│       ├── ExternalSystem.java, ExternalSystems.java, Unreadable.java
│       ├── ManagementIndex.java, ManagementSection.java, ManagingElement.java
│       ├── Difference.java, SystemDiagramManagement.java
│       └── package-info.java
├── application/
│   ├── ManagementService.java                         # index load, cache, budget, gate
│   ├── ManagementMatcher.java                         # pure matching and state rules
│   ├── ManagementBounds.java
│   ├── InventoryService.java, InventoryBounds.java    # MODULE + DIAGRAM hooks, deadlines
│   └── ModelDetailService.java                        # owner, diagram list, check, deadline
├── mcp/tools/
│   ├── GetInventoryItemTool.java                      # description
│   └── ReadModelDetailsTool.java                      # Module.management, description
└── infra/Wiring.java                                  # order: cache → inventory → management → model details
src/main/resources/
├── schemas/get_inventory_item.output.json             # $defs.Management, $defs.SystemDiagramManagement
├── schemas/read_model_details.output.json             # $defs.Management
└── coverage/interface-coverage.tsv                    # nodeByModel for get_inventory_item
src/test/java/de/dadecker/inubit/mcp/
├── domain/model/management/*Test.java
├── application/ManagementMatcherTest.java, ManagementServiceTest.java
├── application/InventoryServiceManagementTest.java, ModelDetailServiceManagementTest.java
├── mcp/tools/GetInventoryItemToolTest.java, ReadModelDetailsToolTest.java   # pinned descriptions, schema
└── security/ManagementSecretLeakTest.java
src/test/resources/fixtures/v8_1/rest/
├── admin-model-node-by-model-sd-ext-system.*          # existing (PR #113)
├── model-by-name-systemdiagram.*                      # new, synthetic
└── admin-model-node-by-model-managed-module.*         # new, synthetic
tools/record-fixtures.sh, tools/anonymize.py           # T-record cases, ResourceSettings neutralization
docs/tools.md, docs/interface-coverage.md, CHANGELOG.md
```

**Structure Decision**: single project, as all earlier features; the new domain package mirrors
`domain/model/partner` of feature 018.

## Implementation order

1. **Foundation**: capability and matrix; domain types with invariants; `ExternalSystems.from` on the
   PR #113 fixture; synthetic fixtures.
2. **US1 core** (P1): `ManagementMatcher` (state rules, D-3, D-6) → `ManagementService` (index load,
   cache, budget, gate) → `InventoryService` MODULE hook, deadline, bounds → schema and tool test →
   leak test.
3. **US2** (P2): `ModelDetailService` owner/diagram-list/check, `Wiring` reorder, `read_model_details`
   schema and tool test.
4. **US3** (P2): `forSystemDiagram`, `InventoryService` DIAGRAM hook, `managedModules` schema.
5. **US4** (P3): descriptions, pinned tests, docs (`tools.md`, interface coverage), CHANGELOG.
6. **T-record** (owner-gated): recorder cases, anonymizer, recorded fixtures replace the synthetic
   ones; resolve D-3's `ResourceType` form; record the export alternative of D-1.
7. **T-live** (owner-gated): live acceptance on 8.1 with a build of the branch; SC-001 run.

US2 and US3 depend on US1's service; US4 can follow any time after US1.

## Complexity Tracking

No constitution violations; nothing to justify.
