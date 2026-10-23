FEATURE: specs/009-inubit-version-detection  ROUND: 2

COVERAGE:
- FR-001 → T007, T008, T012, T013, T014, T016, T021; FR-002 → T006, T017, T018, T019; FR-003 → T003, T008, T019; FR-004 → T020; FR-005 → T008–T012, T019; FR-006 → T012; FR-007 → T015; FR-008 → T008–T011, T021, T023; FR-009 → T008, T012–T014; FR-010 → T006–T008; FR-011 → T004, T008, T012, T014, T018–T020; FR-011a → T022; FR-012 → T005, T006, T016, T021, T022; FR-013 → T008, T024, T025; FR-014 → T003, T024, T025; FR-014a → T023; FR-015 → T008–T011, T023; FR-016 → T008, T017, T018, T020.
- US1-1..6 → T018, T020; US2-1..6 → T009–T012, T015, T019 (US2-5 ordered wrong, M1); US3-1 → T008, T009, T012; US3-2 → T025 (listing not asserted, n6); US3-3 → T024, T025; US4-1 → T021; US4-2 → T016, T020 (contradiction not asserted in list_nodes, n6); US4-3 → T005, T015, T022.
- Edge cases → T002, T006/T007, T019, T012, T023; "no StartCLI installation → client not available" untested (n6).
- SC-001..005 → T020, T009–T011/T023, full suite, T025, T008.
- T001, T026–T029 infrastructure/docs/validation; no scope creep.

PREVIOUS FINDINGS (plan-r1): B1 RESOLVED; check_artifacts RESOLVED (n3 new); INVENTORY mixed-channel RESOLVED; warnings into write results RESOLVED (carrier open, n5); stale triggers RESOLVED (feasible at InubitHttpClient :336/:350); extension point RESOLVED (layering n4); VersionSetting location RESOLVED; T003/T004 dependency RESOLVED; r1 minors resolved except the T005 user list (n8).

FINDINGS:
- [major] T010 / T019 (US2-5) – T010 tests a probe timeout and the 60 s retry, but the gate runs the probe only from T019 and ClientVersionPort exists from T017; T010 cannot go green in place. – Move the case to T019 (ProcessControlServiceTest) or make T010 depend on T017/T019; state that client results are seeded through NodeVersions in T009/T010/T012/T014.
- [minor] n1 T021 – No production change left (T006, T003, T006/T008 cover it); tests would pass at once. – Fold into T008 or mark as verification task.
- [minor] n2 – D-11 puts the deploy check in DeployService, D-5/T023 in DeployGuard.check (the workable one); quickstart names DeployServiceTest vs T023's DeployServiceVersionTest; T023 omits the DeployGuard constructor change (Wiring, fixtures) and the warnings carrier in Admitted; D-14 omits DeployGuard.
- [minor] n3 T014 – check_artifacts.output.json has no `warnings`; report as a WARNING finding or add the field.
- [minor] n4 T024 / D-16 – adapter reading NodeVersions (application) inverts the hexagonal direction. – Read through a domain port.
- [minor] n5 T009/T010 – No carrier for warnings (guards return DevelopmentPolicy/WritePolicy); the Operation rename touches ImportService (:365, :578, :746), TagService (:160), E2eTestService (:203) and tests. – Name carrier and call sites.
- [minor] n6 – Missing assertions: US3-2 listing in T025; US4-2 contradiction shown in list_nodes (T020); "no StartCLI installation → not available" (T018/T020); T025 "others refused" vs MONITORING never refuses.
- [minor] n7 T005 / T020 – list_nodes schema keeps the versionLine enum until T020; concrete pins could produce schema-invalid output in between. – Move the schema change into T005.
- [minor] n8 T005 – CliVersionProbeTest and HealthServiceTest also use VersionLine.
- [minor] n9 T007 / T016 – Two observation paths; Slot.succeeded returns early for non-AUTO nodes and on tryLock failure (:296), losing pinned observations. – One owner: HealthService; the listener only feeds AUTO detection.
- [minor] n10 data-model – clientFailure includes "no range" while T004/T017 treat it as a successful ClientVersion (NOT_EVALUABLE). – A success with an empty range, kept for the process lifetime.
- [minor] n11 – tasks.md prerequisites say D-1…D-15; D-16 sits between D-13 and D-14.

VERDICT: CHANGES_REQUESTED
