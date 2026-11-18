FEATURE: specs/025-inubit-74-support  ROUND: 2

All 6 majors and 9 minors from round 1 are fixed in tasks.md (the T003 location minor only partly). Checking the code turned up 2 new major gaps.

COVERAGE:
- FR-001 → T004; FR-002 → T005; FR-003 → T006, T007, T016; FR-004 → T003, T005
- FR-005 → T015 (VersionGate path and NodeOverview:194 list_nodes path); FR-006 → T007; FR-007 → T009, T010; FR-008 → T009(b), T011
- FR-009 → T012; FR-010 → T013; FR-010a → T014; FR-011 → T008, T010; FR-012 → T010; FR-013 → T010, T023
- FR-014 → T001, T002, T017; FR-015 → T001, T018; FR-016 → T022; FR-016a → T001, T017, T018; FR-017 → T021, T022; FR-018 → T020, T022; FR-019 → T002
- FR-020 → T003, T023, T024 (baseline misses the "; supported: …" texts, first major); FR-021–FR-023 → T025–T028; FR-024–FR-028 → T030, T031 (placeholders); FR-029 → T032; FR-030 → T018, T034; FR-031 → T034; FR-032, FR-033 → T033; FR-034 → T035
- Scenarios US1–US5 → T001–T023; US6–US8 → T025–T036 (Mandate B, blocked)

FINDINGS:
- [major] research D-4 / T007 (FR-020, SC-005, US5-3) – The note "not yet verified" on every 7.4 capability leaks into CapabilityMatrix.lines(capability) (:199-206 counts SUPPORTED or a note). VersionGate.supportedLines (:909-916) appends it to refusals of QUEUE_ENTRY_READ, TRACE_LOG_READ, WEBSERVICE_LOG_READ, CLUSTER_NODES_READ, IO_LOG_READ, NODE_CONFIG_READ, LOG_LEVEL_WRITE in the CLI-only branch (6.1) and the generic branch (unknown/other). With 7.4 in PRODUCTION a 6.1 or unknown refusal would read "; supported: 7.4, 8.1, 9.0", changing texts pinned in VersionGateTest, QueueEntryServiceTest, QueueEntryServiceVersionTest, ServerLogVersionSelectionTest, NodeToolsVersionSelectionTest and docs/tools.md. T003 checks only one refused capability per line. – Keep 7.4 out of lines() (ignore the notes of an entry where allNotYetVerified holds, or a dedicated marker); say so in D-4, data-model, T007; add to T003 a QUEUE_ENTRY_READ refusal on V6_1 and on an unknown version asserting "; supported: 8.1, 9.0".
- [major] T014 / contracts – the note "not used until a 7.4 capability is verified" has no defined place in list_nodes output: NodeSummary.Client (:156-184) and StartCli (:63-90) have no note field and list_nodes.output.json is closed (additionalProperties false). The contract says no schema changes shape. – Name the location: an entry in the node's warnings (no schema change) or an optional field with schema, NodeSummary and ListNodesToolTest changes. Keep the --check-config text line (ConfigSummary.client) separate.
- [minor] T008 / D-5 – RefusingPorts also hardcodes the likelyCause "INUBIT 6.1 is supported read-only through StartCLI, for a few reads only" (:85); parameterise it or give 7.4 its own, assert in V74PortSetTest.
- [minor] T018 / T017 [P] – T018's self-test (a) needs RECONSTRUCTED.txt entries that only T017 writes; order T017 → T018 or use synthetic lists.
- [minor] T014 size – three changes and four test classes; consider splitting the CliClientProbe -h gating into its own task.
- [minor] research D-13 still places VersionLineRegressionTest in T/domain/model/ and mentions "ClientVersion.compare with the patchDepth the matrix returns", which does not exist at T003. Align with T003.

VERDICT: CHANGES_REQUESTED
