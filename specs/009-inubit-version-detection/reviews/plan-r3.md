FEATURE: specs/009-inubit-version-detection  ROUND: 3

All 11 round-2 findings resolved. One new major: deploy_release never gets the StartCLI client check (FR-011).

COVERAGE:
- FR-001 → T007, T008, T012, T013, T014, T016, T021; FR-002 → T006, T017–T019; FR-003 → T003, T008, T019; FR-004 → T020; FR-005 → T008–T012, T019, T023; FR-006 → T012; FR-007 → T015; FR-008 → T008–T011, T021, T023; FR-009 → T008, T012–T014; FR-010 → T006–T008, T023; FR-011 → T004, T008, T012, T014, T018–T020, partial: no client check for deploy_release (T023) or deployment-backup restores (T011) ← M1; FR-011a → T022; FR-012 → T005, T006, T016, T021, T022; FR-013 → T008, T024, T025; FR-014 → T003, T024, T025; FR-014a → T023; FR-015 → T008, T009–T011, T023; FR-016 → T008, T017, T018, T020.
- US1-1..6 → T018, T020; US2-1..4 → T008–T012; US2-5 → T019, T009; US2-6 → T015; US3-1 → T008, T009, T012; US3-2 → T025; US3-3 → T024, T025; US4-1 → T021; US4-2 → T016, T020; US4-3 → T005, T015, T022.
- Edge cases and SC-001..005 covered; T001, T026–T029 infrastructure; no scope creep.

PREVIOUS FINDINGS (plan-r2): M1 RESOLVED; n1–n11 RESOLVED (n5 call-site list incomplete, see n2).

FINDINGS:
- [major] M1 T023 / D-11 / T011 – FR-011 not applied to deploy_release (sources export via ArtifactPort.exportRelease, ReleaseDiscovery:121; targets import/tag via StartCLI, NodeDeployer:374/606/1017). T023 only applies detection, matrix and same-minor. Admitted.warnings has no source; DeploymentPreview (DeployService:440) has no top-level `warnings`. T011's channels unspecified. – Call VersionGate.admit(node, DEPLOYMENT, WRITE, {CLI, REST}) for every source and target node, then the cross-node check; name where preview warnings appear; tests (client range excludes a node → refused; 8.1.17 client at 8.1.18 → warning in preview and result); channels {CLI, REST} in T011.
- [minor] n1 T013 – The old "unsupported … 8.1 adapters" texts come from AdapterGatewayFactory (:268-269) via HealthService.gatewayWarnings; name the factory change or drop the version part; assert no duplicate.
- [minor] n2 T009 – Rename call-site list incomplete (WriteChallengeRegistry, DevelopmentGuardTest, WriteChallengeRegistryTest, TagService:74, E2eTestService:87, more ImportService lines). – Write "all users of DevelopmentGuard.Capability".
- [minor] n3 T008/T019 – V81VersionDetector:46 suggests "Set versionLine: V8_1" unconditionally; the gate must build its own nextStep and never forward the detector's.
- [minor] n4 T020 – add workspace_export (CLI-only) to the not-usable assertion for US1-5.
- [minor] n5 plan Scale/Scope – the class list omits Channel, VersionRange, NodeVersionState, VersionObserver, StaleReportingPorts, PortSetRegistry.

VERDICT: CHANGES_REQUESTED
