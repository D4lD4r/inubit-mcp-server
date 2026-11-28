# Plan review – round 4

FEATURE: specs/026-system-diagram-managed-modules  ROUND: 4

## Round-3 status

- Fixed: ElementEntry `settings`/`linked` as `Optional<List<…>>` (T006, T018/V-13); match/compare
  split (`OwnProperties`, `ModuleTarget.java`); §2 "Where" wording; T020 after T016/T019; plan
  source tree types.
- Partly fixed: T013 (contract §4 still says the opposite); `read_model_details` deadline (data-model
  and plan.md still say "deadline"); `systemDiagramsChecked` (contract prose); T002/V-3 (new
  conflicts, major 1); T008 still large.

## Coverage

- FR-001 → T010, T012, T014
- FR-002 → T007, T010, T012
- FR-003 → T007 (D-3), T010 (V-6), T026 (conditional)
- FR-004 → T004, T007, T008, T010 (V-2)
- FR-005 → T004, T007, T008 (V-5, V-21)
- FR-006 → T005, T006, T014, T017
- FR-007 → T007, T009, T010 (V-1)
- FR-008 → T007, T010 (V-3)
- FR-009 → T015, T016, T017
- FR-010 → T018, T019
- FR-011 → T010, T015
- FR-012 → T020, T021
- FR-013 → T008, T015
- FR-014 → T007, T011, T018
- FR-015 → T003, T008 (V-15, V-19)
- FR-016 → T008, T010 (V-17)
- FR-017 → T008 (V-16), T010/T018, T015
- US1 AS1–AS7 → T007, T010 (V-1…V-6); US2 AS1–AS4 → T015, T016 (V-7…V-10); US3 AS1–AS5 → T018,
  T019 (V-11…V-13); US4 AS1 → T020
- SC-001 → T027; SC-002 → T010, T012, T015, T016; SC-003 → T014, T017; SC-004 → T008/T010 (V-17);
  SC-005 → T008 (V-22)
- No scope creep.

## Cross-check mismatches

- §1 `systemDiagramsChecked` "readable system diagrams" ↔ data-model "size − notRead: read
  completely".
- §1 table has no rows for `elements[].systemDiagram/element/connectorType`; §2 lacks the ≤ 100
  bound of `linked`.
- §2 example (`Broken-Element` 202, Central-Backend linking `Demo-HTTP-Gone`) not aligned with the
  fixtures.
- §4 "reachable for `get_inventory_item/default`" ↔ T013 (comment line only).
- data-model "Changed types" `ModelDetailService` "deadline" ↔ D-5/T015.

## Findings

- [major] T002 / V-1, V-3 / SC-001: the default fixture set manages `Demo-HTTP-Out-A` twice (node
  201 already links A and B, `-2` links A as well), so V-1/AS1 ("exactly one element") cannot run;
  the existing fixture's `ServerName` is `https://demo-backend.example.test/api/rates`, not the
  `https://central.example.test` of US1's independent test and SC-001; changing it breaks
  `ModuleDetailXmlParserTest.java:160`; `-2`'s `ResourceType`/`ServerName` unspecified.
- [major] T002 / T010: the fake server for V-1…V-6 is not planned beyond the three REST files: the
  module list is the StartCLI `export_modules_sample.zip` (no `Demo-HTTP-Out-A`, no HTTP Connector,
  no shared-name pair); the diagram list `model_models_owner.xml` has about 12 system diagrams and no
  `SD-HTTP` (V-2 expects `systemDiagramsChecked: 1`); no `modelByName` answer for `TW-Out` for the
  own-properties read.
- [major] contract §1 `systemDiagramsChecked`: reword to "system diagrams read completely (all
  elements read)".
- [minor] contract §4 and D-12: match T013.
- [minor] data-model "Changed types" `ModelDetailService` and plan.md source tree: drop "deadline".
- [minor] contract §1/§2: add the missing rows and the ≤ 100 bound.
- [minor] T010/T018: name the `InventoryDetail` construction sites `InventoryBounds.java` and
  `InventoryBoundsTest.java`.
- [minor] T008, T010 still large.
- [minor] T003 not marked [P]; plan "about 16" omits `ManagedModuleDetail`.

VERDICT: CHANGES_REQUESTED
