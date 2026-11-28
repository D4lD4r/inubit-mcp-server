FEATURE: specs/026-system-diagram-managed-modules  ROUND: 3

Round-2 status: all five majors fixed (major 1 by the owner decision of 2026-11-28 in D-2: 8.1 supported on the recorded 8.1 contracts of both adapters plus the live run of 2026-11-27; holds under Principles III and V — no new adapter, 8.1 is the baseline). Round-2 minors fixed except the size of T008/T010.

COVERAGE: FR-001…FR-017, US1–US4 acceptance scenarios, edge cases and SC-001…SC-005 mapped; no scope creep.

CROSS-CHECK: all fields consistent except §2 `elements[]` "either connectorType?, settings, linked or error" vs. NON_EMPTY on `ElementEntry.settings`/`linked` (major), and §2 "Where" wording (minor).

FINDINGS:
- [major] Contract §2 `elements[]`, data-model ElementEntry, T006, T019 – NON_EMPTY on `ElementEntry.settings`/`linked` drops the field for a readable element with an empty list (spec edge case "element without `ResourcesLinked` … with an empty module list"; element without `ResourceSettings`); a T019 schema requiring them rejects it; no test serializes such an element. Either (a) `Optional<List<…>>` (empty with `error`, else a possibly empty list) and drop those two mixins, or (b) contract says left out when empty and T019 requires neither; add a test in T006 and T018/V-13.
- [minor] data-model ModuleTarget.ownProperties / ManagementMatcher.match, T007, T009 – `Supplier<Result<…>>` names a non-existent type; `match(…, Optional<List<Property>> own, …)` loses the failure, so `comparisonUnavailable` has no source, and implies reading before matching (D-6 reads after a link). Name the result type (or catch `ToolErrorException` into a `Reason`), split into `match(index, target, gateWarnings)` plus a comparison step; name `application/ModuleTarget.java` in T007.
- [minor] Contract §2 "Where" – "or when the check could not get an index (then only `unavailable`)" reads as if cloud-only diagrams get `managedModules`; "only" ignores `collectedAt`, `elements: []`, `warnings`, `truncated`. Reword.
- [minor] T013 – the tsv state column holds one `reachable:<tool>/<action>`; the row is already `reachable:read_model_details/module`; marking it for `get_inventory_item/default` overwrites or changes nothing; no failing test. Add a comment line above the row (as line 236), regenerate `docs/interface-coverage.md`, drop "tests first" or name the docs test.
- [minor] T015 / D-5 – `ModelDetailService` has no call deadline; say the check is bounded by the `budget` argument and no call deadline is added.
- [minor] tasks.md dependencies – T016 and T020 both edit `ReadModelDetailsTool.java` and its test; T021 documents US2; T020 should follow T016 (or T017) too.
- [minor] T002 / V-3 – no fixture links a module from two elements; let `-2` also link `Demo-HTTP-Out-A` (or name the WireMock variant); align the §2 example.
- [minor] data-model systemDiagramsChecked – overlaps with `systemDiagramsNotRead` for a diagram with an unreadable element; define checked = size − notRead, or state the overlap.
- [minor] plan.md source tree – `Reason`, `ElementEntry`, `LinkedModule`, `ModuleTarget` missing.
- [minor] T008, T010 – still large; consider splitting the performance test and the own-properties supplier.

VERDICT: CHANGES_REQUESTED
