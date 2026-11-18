FEATURE: specs/025-inubit-74-support  ROUND: 1

Verdict: CHANGES_REQUESTED. All requirements have tasks, but the design has 6 major gaps against the current code; three would show for 7.4 users in Mandate A.

COVERAGE:
- FR-001 → T004; FR-002 → T005, T013; FR-003 → T006, T007, T016 (only partly delivered, M1); FR-004 → T005, T003
- FR-005 → T015 (list_nodes path missed, M2); FR-006 → T007; FR-007 → T009, T010; FR-008 → T011 (clashes with T009, M3)
- FR-009 → T012; FR-010 → T013; FR-010a → T014 (-h probe not considered, M4); FR-011 → T008; FR-012 → T010; FR-013 → T010, T023
- FR-014 → T001, T002, T017; FR-015 → T001, T018, T023; FR-016 → T022; FR-016a → T001, T017, T018; FR-017 → T021, T022; FR-018 → T020, T022; FR-019 → T002, T022
- FR-020 → T003, T023, T024; FR-021 – FR-023 → T025–T028; FR-024 – FR-028 → T030, T031 (placeholders, allowed by "Planning depth"); FR-029 → T032; FR-030 → T018, T034; FR-031 → T034; FR-032, FR-033 → T033; FR-034 → T035
- US1–US5 scenarios mapped to T001–T023; US6–US8 → T025–T036 (Mandate B, blocked)

FINDINGS:
- [major] research D-3 / T016 (FR-003, SC-004, criterion 4) – The patch depth is passed only in VersionGate.checkClient, which never runs for a 7.4 node in Mandate A (every capability refused first, VersionGate.java:387-389). The comparisons a user sees use the three-part rule: ConfigSummary.java:408 ("client vs pin"), NodeOverview.java:145 and :250 (list_nodes client line), HealthService.java:686 (Mandate B). – Pass matrix.patchDepth at every ClientVersion.compare call site or a single helper; add ConfigSummaryTest and ListNodesToolTest cases (7.4.0.80 vs 7.4.0.90 mismatch, 6.1.0.80 vs 6.1.0.89 match); list the files in D-3, T016 and Scale/Scope.
- [major] research D-7 / T015 (FR-005, SC-002) – NodeOverview.java:194 calls VersionGate.unknown statically for list_nodes; making it an instance method breaks that call and list_nodes keeps the 6.1-only hint. – Keep it static with a matrix/cliOnlyLines parameter; add NodeOverview to T015 and a ListNodesToolTest case for an AUTO node with a 404; correct D-7.
- [major] T009 vs T011 / contracts "check_artifacts" (FR-008) – check_artifacts uses the gate's refusal message as its notice (ArtifactCheckService.java:528-535). T009's new branch before the CLI-only branch drops the INVENTORY suffix "; modules of INUBIT <release> cannot be looked up" (VersionGate.java:893-894); T011 expects it. – Keep the suffix in the new branch or change T011 and the contract.
- [major] research D-8/D-9 / T014 (FR-010a, FR-007) – Once 7.4 is CLI-only, --check-config also runs the client's -h option probe for every CLI-only node with a client (Launcher.java:420-441 → CliClientProbe.optionsFor, gated only on isCliOnly) and emits ConfigValidator.clientOptionWarnings (e.g. "… refused (TLS_ERROR)"); ConfigSummary shows the options. FR-010a names only -v, D-8 claims no new code, T019 treats the help parser as Mandate B. – Decide in D-9: skip the option probe and its warnings while allNotYetVerified, or keep them with 7.4 texts; add a MainTest/ConfigValidatorTest case to T014.
- [major] T001/T002 [P] – both write fixtures/v7_4/cli/README.md and V74FixturesShapeTest; T002 needs T001's README. – Remove [P], order T001 → T002, fix Dependencies.
- [major] Implementation Strategy "One PR per phase" (workflow gate "docs in the same change"; criterion 3) – Merging Phase 2 alone ships V7_4 without the 7.4 matrix entry: a V7_4 pin would run reads through the 8.1 adapters with an "unverified" warning. – Ship Mandate A as one PR (or Phases 2–3 with the docs).
- [minor] contracts refusal example / T009 – named() prefixes "<tool>: ", the name is "INUBIT 7.4" / "INUBIT 7.4.0.90", refuse() appends "; nothing was sent"; refuseUnsupported has no tool name. – Align, e.g. "get_health: INUBIT 7.4 on legacy74/node1: monitoring is not yet verified for INUBIT 7.4; nothing was sent".
- [minor] data-model "Guard rule" says "source in RECONSTRUCTED.txt"; FR-015/T018 say "source not in the README's recorded list". – Align.
- [minor] T003 asserts VersionGate texts from test/domain/model; the helpers are package-private in application. – Move to test/application/.
- [minor] T023 / SC-005 – more existing tests change: static unknown callers, the RefusingPorts constructor, CapabilityMatrixTest production entries. – List them in the validation record.
- [minor] T019 – CliClientProbe.optionsFor returns empty unless the pin is CLI-only; T019 needs T007. – Add T007 → T019.
- [minor] T026/T027 – research recordings land in fixtures/v7_4/cli/ and would fail T001's "every .stdout in exactly one list" and T017's "exactly these twelve plus help". – Put them in the README recorded list; relax the T017 count.
- [minor] Latent for Mandate B – ConfigValidator.java:784 hardcodes "INUBIT 6.1"; the D-6 next step "no tool is verified on it yet" would stay on out-of-scope capabilities after the flip. – Take the release from the matrix now, or record both as T028 inputs.
- [minor] T005 / US3-6 – no assertion for "7.4.0.90-HF1". – Add it.

VERDICT: CHANGES_REQUESTED
