FEATURE: specs/026-system-diagram-managed-modules  ROUND: 2

Round-1 status: fixed — D-3 scoped (plugin-name comparison plus fallback, conditional T026); D-13 (never the StartCLI module export); ResultJson serializers named; `unavailable` and optional `collectedAt`; firstNotRead / diagram-list rules; construction sites and Wiring in T010/T015 with the right path; all round-1 minors. Replaced by new findings: the Constitution III fix (major 1) and the JSON mapping (major 4).

COVERAGE: FR-001…FR-017, all acceptance scenarios and SC-001…SC-005 mapped (T007, T010, T012; T015–T017; T018–T019; T020–T021; T003/T008/T025; SC-005 → T008 V-22). No scope creep.

CROSS-CHECK mismatches: §1 `differing` "when non-empty" and `elements` "managed only" vs. empty Lists written as `[]` (NON_ABSENT); §2 "either … or error" vs. empty `settings`/`linked`; §2 example `error {code, message}` vs. `Reason` with `nextStep`; §2 "no index (diagram list failed)" vs. the data model (a failed diagram list is an index); `SystemDiagramManagement` has no field for the gate's unverified warning.

FINDINGS:
- [major] Plan Constitution Check row V, research D-2, T003 – 8.1 declared unverified "without a deviation", but Principle V (constitution.md 90–95) allows unverified only for a version other than the baseline (8.1); precedent for not-yet-verified on 8.1 (`IO_LOG_READ`, `TAG_ADMINISTRATION` in `CapabilityMatrix.V8_1_NOT_YET_VERIFIED`) is refusal; FR-015 ("MUST be supported on INUBIT 8.1") is not met in the interim. Record a deviation in Complexity Tracking (with FR-015 note), or follow the precedent, or argue 8.1 SUPPORTED on the recorded adapter contract tests.
- [major] Contract §2, data-model SystemDiagramManagement, T018 – no field carries the gate's unverified warning (only `ManagementSection.warnings`); Principle V requires every result of an unverified capability to carry it. Add `warnings` or route into `results[].warnings`; contract, T018, V-19. Moot if major 1 is resolved by not running on 8.1 before T025.
- [major] Contract §2, data-model SystemDiagramManagement/ManagementIndex, T004, T018 – `managedModules` undefined when the requested system diagram is itself an unreadable `SYSTEM_DIAGRAM` item, or missing from a cached index; "elements empty iff unavailable" cannot hold; "`unavailable` iff no index (… diagram list failed)" contradicts the data model. Define both cases; restate the invariant as "no usable index for this diagram"; T004, T018, V-13.
- [major] T006, infra/ResultJson.java, contract §1/§2 – NON_ABSENT writes empty Lists: `not_managed`/`unknown` get `"elements": []` (forbidden by T012's schema rule), `differing: []`, error ElementEntry gets `settings: []`, `linked: []`. Add NON_EMPTY mixins in T006 (precedent `HealthReportJson`) for `ManagementSection.elements`, `ManagingElement.differing`, `ElementEntry.settings`/`linked`, with tests; or change contract and schema to accept `[]`.
- [major] Contract §2 example – `error {code, message}` but `ElementEntry.error` is a `Reason` with required `nextStep` (FR-010/US3 "what to do"); T006's test serializes to the example. Add `nextStep` or make it optional, consistently.
- [minor] tasks.md dependencies – T006 serializes T004's types but is listed parallel to T004; US2/US3 branch at T010 while T011–T014 are open (T016 copies T012's `$defs.Management`; T018/T011 both edit `ManagementBounds.java`; T019/T012 both edit `get_inventory_item.output.json` and `GetInventoryItemToolTest`). Let T006 follow T004; branch after T012 (or T014).
- [minor] D-10 / data-model / T015–T016 – D-10 says `ModuleDetail.management`, data-model changes `ReadModelDetailsTool.Module`; `ModelDetailService` returns `Read<ModuleDetail>`; name the carrier in T015.
- [minor] D-5 – "on timeout `unknown` (or incomplete `managed`)" vs. state rules (budget always `UNKNOWN` without `firstNotRead`).
- [minor] T007 / contract §1 `reason` – for an incomplete index without a link, where does `nextStep` come from (`Unreadable` has none)?
- [minor] Consistency – T024 records three cases, quickstart lists two commands (`model_export_systemdiagram` missing); quickstart fixture text omits the `sdCommunicationCloud`; plan source tree omits `admin-model-node-by-model-sd-ext-system-2.*`.
- [minor] T023 path – `tools/RecordFixturesCatalogueTest.java` resolves to repo-root `tools/`; the file is `src/test/java/de/dadecker/inubit/mcp/tools/RecordFixturesCatalogueTest.java`.
- [minor] D-5 budget arithmetic – 10 `modelByName` at concurrency 4 are 3 waves, 20 `nodeByModel` 5 waves; `4 × timeout` holds only on a responsive node; state it or base the budget on waves.
- [minor] T008 and T010 still large; consider splitting the performance test or the own-properties supplier.

VERDICT: CHANGES_REQUESTED
