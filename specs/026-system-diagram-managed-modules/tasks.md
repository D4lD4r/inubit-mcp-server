# Tasks: Modules Managed by a System Diagram

**Input**: Design documents from `specs/026-system-diagram-managed-modules/` (spec approved in review
round 2, plan, research D-1…D-13, data-model, contracts/management-section.md, quickstart)

**Tests**: mandatory (constitution Principle III, TDD): each implementation task starts with its
failing test in the same task, and every task ends with a green build. No test contacts INUBIT;
REST behaviour is tested with WireMock and the fixtures under
`src/test/resources/fixtures/v8_1/rest/` (synthetic until T025 swaps in recorded ones; fictitious
names only: `SD-HTTP`, `GRP-CONNECT`, `Central-Backend`, `Demo-HTTP-Out-A`, `*.example.test`).

**Build**: `mvn -o -q verify` (never two builds in parallel); Python:
`python3 -I -m unittest discover -s tools/tests`. Paths below are relative to
`src/main/java/de/dadecker/inubit/mcp/` (main) and `src/test/java/de/dadecker/inubit/mcp/` (test)
unless they start with `src/`, `docs/`, `tools/`, `specs/`.

**Secrets (FR-006, SC-003, Constitution II)**: every task that adds a result field, reason, warning
or log line asserts that none of the planted secrets `synthetic-secret-3911`…`3914`, `3917`, `3918`
appears in it — named below as "leak check".

**Identifiers**: before every commit `python3 tools/check-identifiers.py --staged` (the pre-commit
hook runs it too).

**Reviews**: each task goes through the duo implementer → task-reviewer loop
(`/d4ld4r-speckit-duo:implement`); decisions go to `specs/026-system-diagram-managed-modules/reviews/`.

**Merge**: phases 1–6 form the feature PR (8.1 supported, research D-2, owner decision
2026-11-28); T022–T027 need a reachable non-production node and land in their own PR.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [ ] T001 Baseline: the build is green on branch `026-system-diagram-managed-modules`; note the test
  count in `specs/026-system-diagram-managed-modules/reviews/implementation.md` ("Baseline")
- [ ] T002 [P] Synthetic fixtures (research D-11, quickstart "Validation scenarios"), each with a
  `.README` starting `SYNTHETIC –` that names the probe of 2026-11-27 as the source of the shape:
  `src/test/resources/fixtures/v8_1/rest/model-by-name-systemdiagram.{http,xml}` in the format of
  `modelByName_sample.*` (system diagram `SD-HTTP`, group `GRP-CONNECT`; nodes `Central-Backend`
  `sdExtSystem` 201, `Second-Backend` `sdExtSystem` 202, one `sdInubitIS`, one
  `sdCommunicationCloud`); `admin-model-node-by-model-sd-ext-system-2.{http,xml}` (a second external
  system linking `Demo-HTTP-Out-A` (so V-3 has a module managed twice), `Demo-HTTP-Out-C` and the missing `Demo-HTTP-Gone`, planted
  `synthetic-secret-3917`); `admin-model-node-by-model-managed-module.{http,xml}` (an HTTP Connector
  `Demo-HTTP-Out-A` with `ServerName` `http://localhost:8000/dummy` and a planted Password
  `synthetic-secret-3918`); a fixture test in `adapter/rest/v81/ManagementFixturesTest.java` asserts
  each file parses with the existing parsers and carries its planted secret

## Phase 2: Foundational (blocks every story)

- [ ] T003 Capability (research D-2): `domain/model/Capability.java` gains
  `MODULE_MANAGEMENT(READ, {REST})`; `domain/model/CapabilityMatrix.java`: 8.1 supported, 9.0
  refused "not yet verified", 6.1, 7.4 and unknown versions refused; `NodeOverview` and
  `ConfigSummary` list `module_management`; `src/main/resources/schemas/list_nodes.output.json` gains
  the enum value. Tests first in `domain/model/CapabilityMatrixTest.java` and the existing
  `list_nodes` / `--check-config` tests
- [ ] T004 [P] Domain types in `domain/model/management/` with `package-info.java`: `Reason`,
  `ExternalSystem`, `Unreadable`, `ManagementIndex` (`complete()`, `linking(name)`,
  `systemDiagramsNotRead()`), `ManagementSection`, `ManagingElement`, `Difference`,
  `SystemDiagramManagement`, `ElementEntry`, `LinkedModule`. Constructor invariants per
  data-model.md: the state rules of "ManagementSection" (`elements` non-empty iff `MANAGED`,
  `reason` iff `UNKNOWN`, `NOT_MANAGED` only with `complete` and without `firstNotRead`), data-model.md
  `ManagementSection.firstNotRead` (present iff an index was had and it is not complete),
  `ManagementSection.elements` (≤ 10), `Difference.central`/`Difference.module` (no `${secret:`),
  `SystemDiagramManagement.elements` (≤ 50, empty iff `unavailable`),
  `SystemDiagramManagement.collectedAt` (present iff an index was had), `ElementEntry.linked` (≤ 100),
  `LinkedModule.found` (empty iff `moduleListUnavailable`), `SystemDiagramManagement.unavailable` (no
  usable index for this diagram, all five cases of data-model.md), `SystemDiagramManagement.warnings`,
  `Unreadable.kind` (a `DIAGRAM_LIST` item
  only alone, data-model.md `ManagementIndex.unreadable`), `ExternalSystem.nodeId`,
  `Reason.message`, `Unreadable.message`. Tests first, one test class per type in
  `domain/model/management/`; `PackageBoundaryTest` stays green
- [ ] T005 `domain/model/management/ExternalSystems.java` `from(DiagramRef, ModuleRef, ModuleDetail)`
  (data-model.md `ExternalSystem.connectorType`, `ExternalSystem.settings`,
  `ExternalSystem.linkedNames`): `ResourceType`, `ResourceSettings`, `ResourcesLinked` by exact name;
  a masked container stays one masked setting; a masked or non-String `Module` entry is skipped with
  a warning. Tests first in `domain/model/management/ExternalSystemsTest.java` on constructed details
  and in `adapter/rest/v81/ExternalSystemsFixtureTest.java` on
  `admin-model-node-by-model-sd-ext-system.xml` parsed by `ModuleDetailXmlParser`; leak check
- [ ] T006 (after T004) `infra/ResultJson.java` serializers and mixins (research D-10): `ModuleDetail.Property` in the form of
  `ReadModelDetailsTool.Property` (`{name, type, value, truncated?}` | `{name, type, masked: true}` |
  `{name, type, entries}`), `ManagementSection.Status` (`managed`, `not_managed`, `unknown`),
  `Unreadable.Kind` (`diagramList`, `systemDiagram`, `element`); `NON_EMPTY` mixins (as
  `HealthReportJson`) for `ManagementSection.elements` and `ManagingElement.differing` (`ElementEntry.settings`/`linked` are `Optional<List<…>>`, data-model.md "ElementEntry"). Tests first in `infra/ResultJsonTest.java`: a
  `ManagementSection` and a `SystemDiagramManagement` serialize to the examples of
  contracts/management-section.md §1 and §2; a `not_managed` and an `unknown` section have no
  `elements`; an error `ElementEntry` has no `connectorType`/`settings`/`linked`; a readable element without `ResourcesLinked` has `linked: []`; `warnings: []` stays; a
  `ModuleDetail.Property` serializes exactly like `ReadModelDetailsTool.Property.of` of the same value

**Checkpoint**: capability, model and JSON are in place; stories can start.

## Phase 3: User Story 1 — a module result says that a system diagram manages it (P1) 🎯 MVP

**Goal**: every found module of `get_inventory_item` carries `management` (FR-001…FR-008, FR-011,
FR-013…FR-017).

**Independent Test**: quickstart V-1…V-6, V-15…V-19, V-21, V-22 against the fake server.

- [ ] T007 [US1] `application/ManagementMatcher.java` with `match` and `compare`, `application/ModuleTarget.java` and `application/OwnProperties.java` (pure, data-model.md "ManagementMatcher", research D-3, D-6, D-13): exact name
  match; shared names (data-model.md `ModuleTarget.sameName`) narrowed by plugin type vs.
  `ResourceType` ignoring case, else every module of the name with "cannot be attributed to a single
  module"; no module list at hand → that fallback with "module list not loaded …"; single module
  with a known differing type → "type does not match"; more than one element → "managed more than
  once"; incomplete index with a link → `MANAGED`, `complete: false`, `firstNotRead`, "further
  management cannot be ruled out"; incomplete without a link → `UNKNOWN` with `firstNotRead`'s code
  and message and the fixed next step per kind (research D-13); comparison of top-level shown settings (data-model.md `Difference`);
  `comparisonUnavailable` when the own properties failed (data-model.md
  `ManagingElement.comparisonUnavailable`); the gate's warnings appended; elements cut to 10 with
  `truncated`. Scope: plugin-name comparison plus fallback only (a mapping table is T026). Tests
  first in `application/ManagementMatcherTest.java` covering US1 AS1–AS4, AS6, AS7 (all three
  branches) and the edge cases "no system diagram", "element without `ResourcesLinked`", "masked
  container", "secret settings that differ"
- [ ] T008 [US1] `application/ManagementService.java` index load (research D-1, D-4, D-5, D-13): gate
  `MODULE_MANAGEMENT`/READ/REST (refusal → `UNKNOWN` with the gate's reason; any gate warning carried on); system diagrams from the inventory diagram list `(server, DIAGRAM, "all")` filtered to
  `systemdiagram` (a failure → index with the single `DIAGRAM_LIST` item); `diagramDetail` per system
  diagram and `ModelDetailPort.module` per `sdExtSystem` node, each with concurrency 4; every other
  failure becomes an `Unreadable` and the rest is still read; cache key
  `(server, DIAGRAM, "management:<owner>")`, an incomplete index served once and not cached; load on a
  virtual thread, wait at most `managementBudget = 4 × timeout`, on timeout `UNKNOWN`/`TIMEOUT` "the
  read goes on and fills the cache"; never `listModules`; one DEBUG line per load (server, owner,
  counts, duration). Tests first in `application/ManagementServiceTest.java` with fake ports and
  `MutableClock` (V-5, V-15, V-16, V-17, V-19, V-21) and
  `application/ManagementServicePerformanceTest.java` (V-22: ten system diagrams, 20 elements within
  the budget, as `PartnerServicePerformanceTest`); leak check on the log
- [ ] T009 [US1] `ManagementService.forModule` (data-model.md "ModuleTarget", "ManagementService"):
  calls `match`; only for a `MANAGED` result calls `ModuleTarget.ownProperties` within the remaining budget, catching a `ToolErrorException` (or "no using technical workflow") into `OwnProperties.unavailable`; then `compare`. Tests first in `application/ManagementServiceTest.java`
- [ ] T010 [US1] `application/InventoryService.java` MODULE lookup: builds the `ModuleTarget` from the
  module list it has loaded (`sameName`, `pluginType`) and an own-properties supplier
  (`diagramDetail` of the first using technical workflow → node id → `ModelDetailPort.module`, not
  cached); `InventoryDetail.management` (new field in `domain/model/InventoryDetail.java`) on every
  found module, not on NOT_FOUND items or server errors; `refresh` passed through; `itemDeadline`
  for MODULE grows by `managementBudget`. In the same task: the new constructor parameter at every
  construction site — `Wiring.java` (creates `ManagementService` with the shared `InventoryCache`,
  `GatewayFactory`, `VersionGate` and passes it), `mcp/tools/InventoryToolFixture.java`,
  `application/CapabilityExtensionTest.java`, `application/InventoryOwnerTest.java`,
  `application/InventoryServiceTest.java`. Tests first in
  `application/InventoryServiceManagementTest.java` through WireMock (V-1…V-6, V-17) and the existing
  `InventoryServiceTest` (deadline arithmetic); leak check
- [ ] T011 [US1] `application/ManagementBounds.java` and `application/InventoryBounds.java`: cut the
  module item's `management` per research D-10 (settings per `ItemBounds`, warnings, `truncated`).
  Tests first in `application/InventoryBoundsTest.java`
- [ ] T012 [US1] `src/main/resources/schemas/get_inventory_item.output.json`: `$defs.Management` per
  contracts/management-section.md §1 with its schema rules; `InventoryDetail.management` references
  it. Tests first in `mcp/tools/GetInventoryItemToolTest.java` (`assertMatchesOutputSchema` for
  `managed`, `not_managed`, `unknown`, incomplete `managed`, `unknown` with `firstNotRead`; the
  schema rejects `not_managed` with `elements`)
- [ ] T013 [US1] `src/main/resources/coverage/interface-coverage.tsv`: a comment line above the `GET /model/nodeByModel/{model}/{node}` row (as the comment on line 236) names its use by `get_inventory_item` (the management check) and the check's `modelByName`/`models` reads from `read_model_details`; the state column stays `reachable:read_model_details/module`; regenerate `docs/interface-coverage.md`. Check: the existing coverage/docs consistency tests stay green (no new failing test)
- [ ] T014 [US1] `security/ManagementSecretLeakTest.java`: `get_inventory_item` with all fixtures;
  none of the planted secrets in results, error excerpts (a 500 body echoing the planted values), log
  lines or the cached `ManagementIndex` (`InventoryCache.peek`) (SC-003, V-18)

**Checkpoint**: US1 complete and demonstrable (MVP).

## Phase 4: User Story 2 — the module detail carries the same notice (P2)

**Goal**: `read_model_details` (`action: module`) carries `module.management` (FR-009).

**Independent Test**: quickstart V-7…V-10, V-15, V-20.

- [ ] T015 [US2] `application/ModelDetailService.java` (research D-7, D-13): `module(...)` returns
  `Read<ManagedModuleDetail>` (new `application/ManagedModuleDetail.java`, data-model.md
  "ManagedModuleDetail"), mapped by `ReadModelDetailsTool` to `Result.module`; owner per server,
  diagram-list check, `ManagementService.forModule` with a `ModuleTarget` from the cached module list
  (`InventoryCache.peek`, else `sameName` empty) and the properties just read as own properties; no
  section for a type starting with `sd`; `UNKNOWN`/`NOT_CONFIGURED` without owner; `UNKNOWN` "the
  diagram does not belong to the configured owner" outside the owner's list; the module's own
  properties exactly as today; no call deadline is added: the check is bounded by the `budget` argument (`managementBudget`) of `forModule`; never a StartCLI run. In
  the same task: the new constructor parameters at every construction site — `Wiring.java` (creates
  `ModelDetailService` after `InventoryCache`, `ManagementService` and `InventoryService`),
  `application/ModelDetailServiceTest.java`, `mcp/tools/ReadModelDetailsToolTest.java`,
  `security/ModelDetailsSecretLeakTest.java`. Tests first in
  `application/ModelDetailServiceManagementTest.java` (V-7…V-10, V-20) and the existing
  `ModelDetailServiceTest` (unchanged properties)
- [ ] T016 [US2] `mcp/tools/ReadModelDetailsTool.java` `Module` gains
  `Optional<ManagementSection> management`; `src/main/resources/schemas/read_model_details.output.json`
  gains `$defs.Management` identical to T012's. Tests first in `mcp/tools/ReadModelDetailsToolTest.java`
  (schema for all statuses; no section for an `sdExtSystem`; the section's JSON equals
  `get_inventory_item`'s for the same module) and `adapter/rest/v81/ModelDetailsV81ContractTest.java`
  (V-7, V-8)
- [ ] T017 [US2] `security/ModelDetailsSecretLeakTest.java`: the managed-module and both
  `sdExtSystem` fixtures through `read_model_details`; leak check on result, errors and logs

## Phase 5: User Story 3 — a system diagram's detail lists the modules it manages (P2)

**Goal**: `get_inventory_item` (`kind: DIAGRAM`, type `systemdiagram`) carries `managedModules`
(FR-010).

**Independent Test**: quickstart V-11…V-13, V-20, V-21.

- [ ] T018 [US3] `ManagementService.forSystemDiagram` (research D-8, D-13) and the DIAGRAM hook in
  `application/InventoryService.java`; `domain/model/InventoryDetail.java` gains
  `Optional<SystemDiagramManagement> managedModules`: elements of that diagram from the (cached) index;
  `found` marks only from a cached module list (`peek`), else none plus `moduleListUnavailable`; an
  unreadable element with name, node id and `error`; `unavailable` when there is no usable index for
  this diagram (gate, `TIMEOUT`, `DIAGRAM_LIST` item, the diagram itself unreadable, the diagram
  missing from a cached index → `NOT_FOUND` "call again with `refresh: true`"); the gate's warnings in
  `managedModules.warnings`; no `managedModules` without `sdExtSystem` elements; bounds of
  data-model.md `SystemDiagramManagement.elements` and `ElementEntry.linked` in `ManagementBounds`;
  DIAGRAM `itemDeadline` grows by `managementBudget`. Tests first in
    `application/InventoryServiceManagementTest.java` (V-11, V-12, V-13 including a readable element without `ResourcesLinked` → `linked: []`, V-19, V-20, V-21); leak check
- [ ] T019 [US3] `get_inventory_item.output.json` `$defs.SystemDiagramManagement` per
  contracts/management-section.md §2. Tests first in `mcp/tools/GetInventoryItemToolTest.java`

## Phase 6: User Story 4 — the agent is told about the check (P3)

**Goal**: both descriptions name the check (FR-012).

**Independent Test**: quickstart V-14.

- [ ] T020 [US4] (after T016 and T019: they edit `ReadModelDetailsTool`/its test and `GetInventoryItemToolTest`) `mcp/tools/GetInventoryItemTool.java`
  and `mcp/tools/ReadModelDetailsTool.java` descriptions gain the sentences of
  contracts/management-section.md §3. Tests first: the pinned descriptions in
  `GetInventoryItemToolTest` and `ReadModelDetailsToolTest`
- [ ] T021 [US4] `docs/tools.md`: rendered descriptions, the `management` and `managedModules` field
  tables, `module_management` in the capability table (8.1 supported, 9.0 not yet verified); `CHANGELOG.md`
  "Unreleased / Added"; `DevelopmentToolsReferenceTest` expectations for both sections. Tests first in
  `mcp/tools/DevelopmentToolsReferenceTest.java`

## Phase 7: Recording and live acceptance (after a non-production node is reachable)

- [ ] T022 [P] `tools/anonymize.py`: neutralize the values of `ResourceSettings` entries (keep names
  and types) and the `ResourcesLinked/Module` names (consistent mapping with the module names of the
  same recording); `--verify` rejects kept values. Tests first in
  `tools/tests/test_anonymize_management.py`
- [ ] T023 [P] `tools/record-fixtures.sh` (and the catalogue test): cases
  `model_by_name_systemdiagram` (`GET /model/modelByName/<sd>?user=`), `node_by_model_sd_ext_system`
  (`GET /model/nodeByModel/<sd>/<nodeId>`), and the existing `model_export_systemdiagram` usable for
  a system diagram with `sdExtSystem` elements (`RECORD_SAMPLE_SYSTEMDIAGRAM`); all read-only. Tests
  first in `tools/tests/` and `src/test/java/de/dadecker/inubit/mcp/tools/RecordFixturesCatalogueTest.java`
- [ ] T024 Operator (live, non-production, after T022 and T023): record the three cases of T023 for
  one system diagram that manages connectors centrally, with the commands of quickstart.md "Live
  acceptance"; check with `anonymize.py --verify` and `check-identifiers.py --all`. **Blocks T025**
- [ ] T025 Swap the synthetic `model-by-name-systemdiagram.*` and `admin-model-node-by-model-sd-ext-system*`
  fixtures for the recorded ones (planted secrets re-added at the places their README documents);
  record in `specs/026-system-diagram-managed-modules/research.md` the form of `ResourceType` (D-3)
  and whether the export carries the `sdExtSystem` properties (D-1 alternative). Tests first: the
fixture and contract tests on the recorded files; own PR
- [ ] T026 Conditional (only if T025 finds `ResourceType` is not a plugin name, or another element
  type with `ResourcesLinked`): a mapping table from `ResourceType` to plugin names in
  `application/ManagementMatcher.java`, or the additional element type in
  `application/ManagementService.java`. Tests first in `ManagementMatcherTest` /
  `ManagementServiceTest` with the recorded values; otherwise mark it "not needed" in
  `reviews/implementation.md`
- [ ] T027 Operator (live, after T025): live acceptance with a build of the branch on an 8.1
  non-production node (quickstart.md "Live acceptance" steps 2–3), including the SC-001 run; results
  in `specs/026-system-diagram-managed-modules/reviews/implementation.md`
- [ ] T028 Final: `mvn -o -q verify`, Python tests and `check-identifiers.py --all` green; CHANGELOG entry updated for the recorded fixtures

## Dependencies & Execution Order

```text
T001 ─► T002 ─┐
T003 ─────────┤
T004 ─► T005 ─┼─► T007 ─► T008 ─► T009 ─► T010 ─► T011 ─► T012 ─► T013 ─► T014   (US1, MVP)
T004 ─► T006 ─┘                                                            │
                                                                                                                                                        ├─► T015 ─► T016 ─► T017 ──┐     (US2)
                                                                            └─► T018 ─► T019 ───────────┴─► T020 ─► T021 (US3, then US4)
T022, T023 (any time) ─► T024 (operator) ─► T025 ─► T026 (conditional) ─► T027 (operator) ─► T028
```

- Phase 2 blocks all stories. US2 and US3 start after US1 is complete (T014: `$defs.Management`,
  `ManagementBounds`, `get_inventory_item.output.json` and `GetInventoryItemToolTest` are settled),
  not on each other. T020 follows T016 and T019 (shared tool and test files); T021 follows T020 and documents US2 and US3.
- T002, T003 and T004 can run in parallel; T006 follows T004; T022 and T023 in parallel with any story.
- T024 and T027 need a reachable non-production node and the operator; phases 1–6 do not wait for
  them.

## Parallel examples

- Foundation: T002 ∥ T003 ∥ T004, then T005 ∥ T006.
- After T014: T015 (US2) ∥ T018 (US3); `Wiring.java` is edited only in T015, `InventoryService.java`
  and `ManagementBounds.java` only in T018.
- Tooling: T022 ∥ T023.

## Implementation Strategy

1. **MVP = Phases 1–3 (US1)**: after T014 an agent calling `get_inventory_item` sees `management`;
   run quickstart V-1…V-6, V-15…V-19, V-21, V-22.
2. **Increment 2**: US2 (the notice next to the misleading values in `read_model_details`).
3. **Increment 3**: US3 (overview per system diagram), US4 (descriptions, docs, CHANGELOG). Feature PR.
4. **Evidence**: T022–T027 replace synthetic by recorded fixtures, settle D-3, make 8.1 supported
   (own PR); T028 closes.
