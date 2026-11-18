FEATURE: specs/025-inubit-74-support  ROUND: 3

Both round-2 majors and all four round-2 minors are fixed (lines() excludes an entry where every capability has a note, T003 pins the "; supported: …" texts; the list_nodes note goes into the node's free-string warnings, no schema change; RefusingPorts likely cause parameterised; T019 uses synthetic lists; T014 split into T014/T015; D-13 matches T003). No new blocker or major.

COVERAGE:
- FR-001 → T004, T013; FR-002 → T005, T013; FR-003 → T006, T007, T017; FR-004 → T003, T005
- FR-005 → T016; FR-006 → T007; FR-007 → T009, T010, T015; FR-008 → T009(b), T011
- FR-009 → T012; FR-010 → T013; FR-010a → T014, T015; FR-011 → T008, T010; FR-012 → T010; FR-013 → T010, T024
- FR-014 → T001, T002, T018; FR-015 → T001, T019; FR-016 → T023; FR-016a → T001, T018, T019; FR-017 → T022, T023; FR-018 → T021, T023; FR-019 → T002
- FR-020 → T003, T024, T025
- Mandate B (blocked, placeholders): FR-021–FR-023 → T026–T029; FR-024–FR-028 → T031, T032; FR-029 → T033; FR-030 → T019, T035; FR-031 → T035; FR-032, FR-033 → T034; FR-034 → T036
- Scenarios: US1 → T007–T015; US2 → T016; US3 → T005, T006, T017; US4 → T001, T002, T018–T023; US5 → T003, T024; US6–US8 → T026–T037

FINDINGS:
- [minor] T017 / D-3 – HealthService has no CapabilityMatrix (fields :152-164) and ConfigSummary.client (:398) is static; T017 has no test for HealthService:686. – Say how they get the matrix (e.g. VersionGate.comparePatch), or move :686 to T031.
- [minor] T003 – on an undetected AUTO node refuseUnsupported takes the missing-name branch (:862-869) without "; supported: …"; the suffix appears for a pin outside every range or V9_X. – Reword.
- [minor] T014 / T015 MainTest – --check-config creates SystemProcessLauncher itself (Launcher.java:415); MainTest uses POSIX startcli.sh stand-ins, disabled on Windows. – Fix wording; name ConfigValidatorTest, ConfigSummaryTest, CliClientProbeTest as the tests that also run on Windows.
- [minor] T018 – fixtures/v6_1/cli has no ps_session_waiting, ps_session_queued, ps_session_limit5, only ps_session_waiting_queued. – Name the sources.
- [minor] D-15 / T029 – lines() exclusion, the T014 warning and the T015 -h gate all switch at the first Mandate B flip; 7.4 would then show in "; supported: 7.4, 8.1, 9.0" for capabilities out of scope on 7.4 for good. – Add to the D-15 revisit list and T029.
- [minor] plan Structure Decision / quickstart – stage 6 lists .gitattributes (done in T002); quickstart lacks CliClientProbeTest (T015) and ConfigSummaryTest / ListNodesToolTest for the patch comparison (T017). – Align.

VERDICT: APPROVED
