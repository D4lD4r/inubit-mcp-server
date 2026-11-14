FEATURE: specs/022-full-interface-coverage  ROUND: 6

Scope: the uncommitted US3 changes after T039: spec.md, plan.md, research.md (D-14, D-21, live checks, "Live recording T039", "Decided after T039" items 1–8), data-model.md, contracts/mcp-tools-delta.md, contracts/coverage-list.md, quickstart.md, tasks.md Phase 5 (T039–T047a with T045a, conditional T046b–T046d) and T079, T085, T091, T103, T104; checked against the constitution and the code (`VersionGate.refusal`, `InterfaceCoverage`, `CoverageEntry.WAITS_ITEM`, the T039 fixtures, `fixtures/queue-entry/v9_0/error_entry.rows.json` with `.origin` and `userDefined1` set).

PREVIOUS FINDINGS (round 5):
- [major] 1 `userColumn` gate: RESOLVED (T045 `InterfaceCoverage.servedBy` + `VersionGate.admitOption`, `admitCall`/`refusal` unchanged, no version check in `DiagnosisService`; tests incl. the flipped 9.0 row; D-21 and the `find_processes` contract agree; T046a enables 9.0 by the row flip).
- [major] 2 positive L-19/L-20/L-7: RESOLVED (conditional T046b, T046c, T046d with files and tests, test-first; contract, D-21, L-19/L-20 rows and T047 refer to them).
- [major] 3 L-21 / `deleteAllLibraries`: RESOLVED (T079 lists after `PUT /libraries`; T091 updates parser, fixtures, `rowFormatVerified`; T085 and the `administer_objects` contract show the count only while L-21 is open, confirmation bound to the count, with a test; fallback in research).
- [minor] 4 decision 8 / FR-002 reasons: RESOLVED.
- [minor] 5 `ProcessUserColumn`: RESOLVED.
- [minor] 6 existing 9.0 `queueLog` row: RESOLVED.
- [minor] 7 9.0 exclusion reason: RESOLVED.
- [minor] 8 stale T046 tag / `WAITS_ITEM`: RESOLVED (T045a, test-first).
- [minor] 9 quickstart: RESOLVED.
- [minor] 10 open questions / T047a: RESOLVED.

COVERAGE (US3 and touched rows; all other rows unchanged from round 5):
- FR-002 → T044, T045, T046, T046b/T046c, T104
- FR-004 → T045a, T046, T047, T046a, T047a, T104
- FR-005 → T044, T045
- FR-007 → T043, T044, T045
- FR-008 → T041, T044
- FR-009 → T043, T044, T045
- FR-010 → T040, T043, T045, T046b, T046c
- FR-012 → T044, T046d
- FR-017 (libraries) → T085, T091 (L-21 from T079)
- FR-020 → T041, T044
- FR-025 → T042, T043, T045, T046, T046a
- FR-027 → T047, T047a, T104
- US3 AS1 → existing `find_processes`, T045
- US3 AS2 → T040, T042, T043, T046, (9.0) T046a
- US3 AS3 → T040, T042, T043, T046; row format: T079 (L-21), T091
- US3 AS4 → T041, T044, T039a/T046a/T046d
- US3 AS5 → T040, T045, T046, T046a
- US3 AS6 → export: T041, T044, T046; read: T046b or T104
- US3 AS7 → T041, T044, T046
- US3 AS8 → T046c or T104
- US6 AS4 → T085, T091

FINDINGS:
- [minor] T040 / T045 granularity – T040 holds three unrelated parsers plus domain records; T045 touches about ten files. – Move the `QueueLogRowParser` and `ProcessQuery`/`ProcessInstance` part out of T040; split T045 into (a) the gate (`servedBy`, `admitOption`, tests) and (b) schemas, service, adapter, tool and the `ps -u` row; check that [P] T040 ∥ T041 still touch separate files.
- [minor] T045 tests – The rule "a server that is not admitted gets `userColumn` reported unavailable in the warnings" has no named test. – Add a mixed 8.1 + 9.0 (or 6.1) call to `DiagnosisServiceTest`.
- [minor] data-model.md `InterfaceCoverage` – `servedBy(CoverageKey, MajorMinor, tool)` is missing from the method list. – Add it with its rule.
- [minor] T039a / T046a / T046d / T047a – 9.0 `fetchXSLT1.0`: no row reason if the command exists but writes on the INUBIT host, or the owner declines; T047a does not update `docs/research/inubit-interfaces.md` with the 9.0 facts. – Name both reasons; add the research note to T047a.
- [minor] T046b / T046c / T047a placement – Unclear whether the 9.0 PR waits for L-19/L-20. – T047a waits for T046b/T046c only if their recording exists when T046a is done; otherwise they ship in their own PR (own docs/CHANGELOG step) or fall back in T104.
- [minor] spec.md US6 AS4 (and US3 AS6/AS8) – AS4 says the preview "lists every file" while T085 shows the count until L-21; AS6/AS8 say "stays planned" while decision 8 excludes in Phase 10. – Add the L-21 count-only fallback to AS4 (or an edge case citing FR-017); phrase AS6/AS8 as "stays planned until its check, otherwise excluded at the end of the feature (Clarifications)".

VERDICT: APPROVED
