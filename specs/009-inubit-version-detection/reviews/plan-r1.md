FEATURE: specs/009-inubit-version-detection  ROUND: 1

COVERAGE:
- FR-001 → T007, T008, T012, T013, T015, T020 (gap: check_artifacts never triggers detection, M1)
- FR-002 → T006, T016, T017, T018
- FR-003 → T003, T008, T018
- FR-004 → T019
- FR-005 → T008, T009, T010, T011, T018
- FR-006 → T012 (partial: no test where one node in a group is refused)
- FR-007 → T014
- FR-008 → T008, T009, T010, T011, T020
- FR-009 → T008, T012, T013 (gap: StartCLI parts of mixed-channel reads, M2)
- FR-010 → T006, T007, T008 (stale-trigger mechanism does not work as designed, M4)
- FR-011 → T004, T016, T017, T018, T019 (gaps: warnings on write results M3, check_artifacts M1)
- FR-011a → T021
- FR-012 → T005, T006, T015, T020, T021
- FR-013 → T008, T023 (extension point does not exist, M5)
- FR-014 → T003, T023
- FR-014a → T022
- FR-015 → T008, T009, T010, T011, T022
- FR-016 → (none) ← B1
- US1-1..6, US2-1..6, US3-1..3, US4-1..3, edge cases, SC-001..005 mapped (gaps as noted: US1-5/M2, US2-5/M3, US3-3/M5, REST+StartCLI edge case/M2).
- Non-requirement tasks: T001, T024–T027. No scope creep.

FINDINGS:
- [blocker] FR-016 / tasks – No task covers FR-016; Constitution III requires dedicated tests for every Principle II rule. New surfaces: refusal texts, list_nodes `unknown` reason, get_health `client`/`unavailable`, probe failure excerpts. – Add assertions to T008, T017, T019 (no baseUrl/username/password; scrubbed excerpts); map FR-016.
- [major] check_artifacts / FR-001, FR-011 – ArtifactCheckService.java:513 module lookup (REST+StartCLI) is not gated. – Add a task gating it (INVENTORY read, CLI part) in ArtifactCheckService and Wiring, with tests.
- [major] D-5 / data-model Capability / T012, T018, T019 – INVENTORY is mixed-channel; a per-capability client check would refuse the REST-only diagram listing on 9.0 with an 8.1 client, contradicting the spec table, US1-5 and the REST+StartCLI edge case. – Give the gate a channel/part parameter or split the capability; InventoryService runs the client check only before the StartCLI part; define list_nodes display; tests.
- [major] T009, T010, T022 / FR-011, US2-5 – Admission warnings never reach write results; restart/kill output schemas have no `warnings`. – Pass gate warnings into results; add `warnings` to restart/kill schemas and records; test US2-5 end to end.
- [major] D-10 / T007 – Stale triggers: InubitHttpClient maps every 404 to NOT_FOUND (:336), 406 → UNEXPECTED_RESPONSE (:350), most UNEXPECTED_RESPONSEs are thrown by v81 parsers after the client returned. – Specify the hook (e.g. a decorating wrapper around the ports reporting ToolErrorException codes) and which 404s count; test each trigger.
- [major] T023 / FR-013, US3-3 – The extension point does not exist (Slot.ensureClient hard-wires the V81 port set before detection). – Research decision on port-set selection by effective version after the gate's detection; explicit production task with tests; keep T023 as the proof.
- [major] T005 / plan / data-model – VersionSetting in config but NodeVersions (application) and NodeVersionState (domain) hold it; PackageBoundaryTest forbids application → config. – VersionSetting value in domain/model, only the deserializer in config.
- [major] T003, T004 – marked parallel with T002 but depend on InubitVersion. – Depend on T002.
- [minor] T011 – the method is DevelopmentGuard.admitDeploymentRestore (:118), called from ImportService.admitRestore (:760).
- [minor] T003/T009 – DevelopmentGuard has a nested `enum Capability` (:43); state the mapping and resolve the clash.
- [minor] T009–T013 – constructor changes in DevelopmentGuard, WriteGuard, DiagnosisService, InventoryService, WorkspaceService, HealthService touch Wiring.java and fixtures; T012/T013 not file-disjoint. – Name them, sequence or drop [P].
- [minor] T013/T015 – the gate in HealthService must not detect (get_health's read is the detection); MONITORING never detects; warning derived after the observation.
- [minor] T012 / D-14 – DiagnosisToolFixture and InventoryToolFixture use AUTO; first read now sends /system/info. – List in D-14 and T012.
- [minor] T008 / plan – bound hard-coded as 10 minutes; use the node's inventory.cacheTtl.
- [minor] data-model CapabilityMatrix / T003, T020 – a major-only V9_X cannot be looked up. – Define as unknown, consistent with FR-014a.
- [minor] T015 – add pinned "8.1.17" observing 8.1.18 → warning, no contradiction.
- [minor] T012 – add an FR-006 test where one node of a group is refused while the call succeeds.
- [minor] T010 – name restart_process and kill_process; assert audit REFUSED.
- [minor] T017 – later get_health calls reuse a successful probe; successes recorded in NodeVersions.
- [minor] T022 – DeployGuard.refuse is private (:193); put the check into DeployGuard or name the audited path.
- [minor] T005 – LiveTarget is not a VersionLine user; omits Defaults.java, ConfigValidator and test users (GetHealthToolTest, ListNodesToolTest, ProcessControlToolsTest, Diagnosis/InventoryToolFixture, AdapterGatewayFactoryTest).
- [minor] T014 – add a deployment-target announcement case (every EXECUTE target pinned V9_X).
- [minor] T007 – two owners of back-off state (Slot.fallbackUntil, NodeVersions); Slot.gateway() caches success for good. – detect(NodeId) bypasses the slot cache for re-detection; name the back-off owner.

VERDICT: CHANGES_REQUESTED
