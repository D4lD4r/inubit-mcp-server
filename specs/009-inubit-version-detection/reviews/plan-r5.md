FEATURE: specs/009-inubit-version-detection  ROUND: 5

All round-4 findings resolved (the major and all five minors); no contradictions, no blocker or major. Code references checked (AdapterGatewayFactory :268-269, :329-334, :345-347; DevelopmentGuard :118, :130; DeployReleaseTool :99-104; ArtifactCheckService :513; DeployGuard Admitted/refuse; WriteGuard :92; users of DevelopmentGuard.Capability and VersionLine).

COVERAGE:
- FR-001 → T007, T008, T012–T014, T016, T021; FR-002 → T006, T017–T019; FR-003 → T003, T008, T019; FR-004 → T020; FR-005 → T008–T012, T019, T023; FR-006 → T012; FR-007 → T015; FR-008 → T006 (writableVersion), T008–T011, T021, T023; FR-009 → T008, T012–T014; FR-010 → T006–T008, T023; FR-011 → T004, T008, T012, T014, T018–T020, T023; FR-011a → T022; FR-012 → T005, T006, T016, T021, T022; FR-013 → T008, T024, T025; FR-014 → T003, T024, T025; FR-014a → T023; FR-015 → T008–T011, T023; FR-016 → T008, T017, T018, T020.
- US1-1/2/3/5 → T020 (T018 client); US1-4 → T018, T020 (no explicit mismatch assertion, n3); US1-6 → T018; US2-1..4 → T008–T012, T021; US2-5 → T019; US2-6 → T015; US3-1 → T008, T012; US3-2 → T025; US3-3 → T024, T025; US4-1 → T021; US4-2 → T016, T020; US4-3 → T005, T015, T022.
- Edge cases and SC-001..005 covered; T001, T026–T029 infrastructure/polish; no scope creep.

PREVIOUS FINDINGS (plan-r4): M1, n1–n5 RESOLVED.

FINDINGS:
- [minor] data-model / T006 – On AUTO, `stale` is cleared only by a detection, but after the first detection Slot.succeeded returns early (:300-301), so a later successful get_health only reports `observed`; a write within 60 s after a failed re-detection stays refused despite a fresh observation (safe, but conflicts with FR-011 "observation replaces the cached version"). – On AUTO an observation clears `stale`, restarts the bound and supersedes a failed re-detection; NodeVersionsTest case.
- [minor] T013 – Dropping the old texts also breaks AdapterGatewayFactoryTest :218 and :451 (ConfigValidatorTest :759, ConfigSummaryTest :234 change with T022). – Name AdapterGatewayFactoryTest in T013; define the unknown-version warning wording.
- [minor] T018 / T020 – US1-4 mismatch not asserted. – 8.1.17 client at 8.1.18: get_health `comparison: mismatch` + warning; list_nodes `mismatch` with StartCLI capabilities still usable.

VERDICT: APPROVED
