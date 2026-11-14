FEATURE: specs/022-full-interface-coverage  ROUND: 4

All four round-3 findings are fixed.

**Major from round 3 (the wait marker is now data): fixed.**
- `contracts/coverage-list.md`: the TSV header has a new `waits` column with the values `-`, `L-n` or `9.0-pin`. The example now shows `GET /license` as `planned` on 9.0 with `9.0-pin` under `delivered-stories: US1`. Check 5 is restated using the column.
- `data-model.md`: `CoverageEntry.waits` is defined, along with three loader failure rules: an unknown value, a value on a row that is not `planned`, and a `planned` row of a delivered story with `-`.
- `research.md` D-2 matches this.
- T006: new header and loader rules.
- T007: every row starts with `-`.
- T025: `waits` goes back to `-` when a row is flipped.
- T027, T037, T047, T066, T074, T091 and T102: each sets `waits` on the rows of its story that are still open.
- T104: asserts that no `waits` value is left.

**Minors from round 3: all fixed.**
- `ChangeGuard`: research D-12 and T049 both now read `admit(tool, nodeId, ActionSpec, input)`. Step 5 passes `ActionSpec.entries(input)` and `ActionSpec.capability()`. `ActionSpec` has a `capability` field in both the data model and T010.
- Names: the quickstart no longer lists `AdministrationConfigTest`. The plan's Project Structure now names `ProcessMetadataParser`, `IsConnectorCheckParser` and `UserImportFile`.
- T011: T082 is no longer in the list of tasks that add a capability.

COVERAGE (task IDs unchanged from round 3; checked again against the current tasks.md):
- FR-001 → T006, T007
- FR-002 → T007, T021, T045, T104
- FR-003 → T004, T005, T008, T010 (check 6)
- FR-004 → T006/T008 (check 5, `waits`), T027, T037, T047, T066, T074, T091, T102
- FR-005 → T044
- FR-006 → T001, T107
- FR-007 → T022, T035, T043, T044, T061, T064, T073, T086, T089, T101
- FR-008 → T019, T032, T035, T041, T044
- FR-009 → T021, T034, T043, T044
- FR-010 → T013, T017, T036, T040
- FR-011 → T030, T034, T036
- FR-012 → T044
- FR-013 → T010 (admitParts), T019, T021
- FR-014 → T049, T065, T073, T090, T101
- FR-015 → T051
- FR-016 → T049, T067, T085, T090
- FR-016a → T072
- FR-017 → T051, T060, T072, T085
- FR-018 → T053, T064, T083, T084, T085
- FR-019 → T094, T097, T098
- FR-019a → T062, T063, T064, T066
- FR-019b → T095, T099
- FR-020 → T041, T068, T088, T100
- FR-021 → T075, T076
- FR-022 → T077, T088, T093, T098
- FR-023 → T052
- FR-024 → T059, T060, T064, T072, T084, T085, T088, T097
- FR-025 → T010, T025, T026, T103
- FR-026 → T054, T055, T069, T079, T092 (gates)
- FR-027 → T027, T038, T047, T066, T074, T091, T102, T104
- US1 AS1–AS7 → T015–T023, T025
- US2 AS1–AS6 → T029–T037
- US3: AS1, AS5 → T045; AS2, AS3, AS6 (read), AS8 → T043; AS4, AS6 (export), AS7 → T044
- US4: AS1–AS3, AS5–AS11 → T059; AS4 → T060; AS12–AS14 → T064
- US5 AS1–AS7 → T072, T073
- US6: AS1, AS2, AS9, AS10, AS12, AS16, AS17 → T084; AS3, AS4, AS8, AS14, AS15 → T085; AS5–AS7, AS11, AS13 → T088
- US7: AS1, AS4, AS5, AS9, AS10 → T097; AS1 (with entry), AS2–AS4 → T098; AS6, AS7 → T095, T099; AS8 → T100
- Edge cases: mapped as in round 3 (T010, T018, T019, T020, T043, T049, T050, T051, T059, T060, T076, T079, T084, T085, T088, T094)

FINDINGS:
- [minor] data-model `CoverageEntry.waits` / contract check 5 / T027 – There is one `waits` value per row, but a row can be `planned` on both versions for different reasons. Example: at the US1 PR, if T014 is not yet done, a row waits on L-1 for 8.1 and on `9.0-pin` for 9.0. T027 says "`9.0-pin` for the 9.0 rows, `L-n` for rows waiting on a live check" and does not say which wins. This does not break check 5: any non-`-` value passes. – Add a precedence rule to the data model and contract (for example, the 8.1 wait first). Or allow a combined value such as `L-1+9.0-pin`.
- [minor] data-model `CoverageEntry` – The `waits` field rule says "`-` on every other row", so a `planned` row of a story that is not yet delivered should carry `-`. But neither the loader rules (data-model lines 56–58, T006) nor check 5 reject a `waits` value on such a row. – Add that case to the loader failure rules and to the T006 tests, or relax the field rule to match.

VERDICT: APPROVED

Files reviewed (all under specs/022-full-interface-coverage/):
- reviews/plan-r3.md
- contracts/coverage-list.md
- data-model.md (lines 1–130)
- research.md (D-2 lines 70–101, D-12 lines 322–347)
- plan.md (full)
- tasks.md: lines 1–400, 540–600, 1195–1279, plus searches for `waits` and the `admit*` signatures in the rest of the file
- quickstart.md (searched for the renamed tests)

In this round I checked the round-3 fixes and any inconsistencies they could have introduced. I did not read tasks.md from line 400 onward in full again: rounds 1–3 had reviewed those parts and the task IDs are unchanged.
