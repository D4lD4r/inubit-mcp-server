FEATURE: specs/009-inubit-version-detection  ROUND: 4 (new loop after rounds 1-3)

Round-3 major and all five round-3 minors resolved. One new major (a write can go through when the re-detection before it fails), five minors.

COVERAGE:
- FR-001 → T007, T008, T012, T013, T014, T016, T021; FR-002 → T006, T017–T019; FR-003 → T003, T008, T019; FR-004 → T020; FR-005 → T008–T012, T019, T023; FR-006 → T012; FR-007 → T015; FR-008 → T008–T011, T021, T023 (partial, M1); FR-009 → T008, T012–T014; FR-010 → T006–T008, T023 (partial, M1); FR-011 → T004, T008–T012, T014, T018–T020, T023; FR-011a → T022; FR-012 → T005, T006, T016, T021, T022; FR-013 → T008, T024, T025; FR-014 → T003, T024, T025; FR-014a → T023; FR-015 → T008–T011, T023; FR-016 → T008, T017, T018, T020.
- US1-1..6 → T018, T020; US2-1..4 → T008–T012; US2-5 → T009, T019; US2-6 → T015; US3-1 → T008, T009, T012; US3-2 → T025; US3-3 → T024, T025; US4-1 → T021; US4-2 → T016, T020; US4-3 → T005, T015, T022.
- Edge cases and SC-001..005 covered; T001, T026–T029 infrastructure/polish; no scope creep.

PREVIOUS FINDINGS (plan-r3): M1 RESOLVED (preview output mapping residue, n1); n1 RESOLVED for configured()/detected() (fallback text open, n2); n2–n5 RESOLVED.

FINDINGS:
- [major] M1 T006 / T008 / data-model NodeVersionState / D-4, D-5 – Undefined when the re-detection before a write fails: effectiveVersion stays the earlier detection, stale is cleared only by a detection, needsDetection is false for 60 s after a failure → an AUTO node upgraded from 8.1 to 9.0 with a failing re-detection still admits writes on cached 8.1 (FR-008, FR-010, upgrade edge case). – For WRITE, a stale or over-bound version counts as unknown if its re-detection failed or is suppressed by the back-off; refuse with the detection-failure text; reads keep the cache. Rule in data-model, T006, T008; tests in NodeVersionsTest and VersionGateTest (second write within 60 s refused without a request).
- [minor] n1 T023 – DeployReleaseTool builds the preview output by hand (:100); name it and add a tool-level assertion; schema description: top-level warnings are strings, per-plan warnings are objects.
- [minor] n2 T013 – fallbackWarning (AdapterGatewayFactory :345-347, "… assuming 8.1") still reaches get_health; drop or reword; HealthServiceTest: failed AUTO detection → exactly one version warning, no "assuming 8.1".
- [minor] n3 T023 – a per-node gate refusal hides the FR-014a message naming every node; collect per-node results in DeployGuard and build one refusal, or change the contract; assert the text.
- [minor] n4 T011 – admitDeploymentRestore builds its own DevelopmentPolicy (:130); warnings into DevelopmentPolicy.warnings and the restore result; test client mismatch → executed with warning.
- [minor] n5 Dependencies – T023 needs T019; T025 needs T020.

VERDICT: CHANGES_REQUESTED
