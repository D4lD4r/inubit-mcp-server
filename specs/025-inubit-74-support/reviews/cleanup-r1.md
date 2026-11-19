TASK: cleanup  ROUND: 1
Scope: the cheap, clearly safe open minors of T006, T008, T009, T010, T015, T018, T019, T020 and T025 (before the Mandate A PR); out of scope T014, T016, T017, T021, T023.
STAGE1: PASS
STAGE2: PASS
TESTS: `mvn -q -o test -Dtest='ClientVersionTest,V74PortSetTest,V74FixturesShapeTest,V74RecordedFixturesTest,V74HelpContractTest,V74RefusalTest,ConfigValidatorTest,VersionGateTest,ToolAnnouncementTest,ListNodesToolTest,CliClientProbeTest' -Dsurefire.failIfNoSpecifiedTests=false` exit 0 (ClientVersionTest 25, V74PortSetTest 55 — 53 parameterised port methods, now including overridden default methods —, V74FixturesShapeTest 6, V74RecordedFixturesTest 5, V74HelpContractTest 1, V74RefusalTest 58, VersionGateTest 79, ConfigValidatorTest 210 with 1 Windows-only skipped as before, ToolAnnouncementTest 52, ListNodesToolTest 46, CliClientProbeTest 16). `git diff --check` clean; no untracked files. The implementer also ran the doc tests and NoCustomerIdentifiersTest (exit 0).

FINDINGS:
- [minor] V74RefusalTest.java:537 – Javadoc line still 101 characters (only :67 was re-wrapped; T010-r2 named both). `.editorconfig` max_line_length = 100, not enforced by the build. Re-wrap, or give a reason for leaving it.
- [minor] docs/setup.md:2814-2815, README.md:181-182 – say `record_knowledge` is offered / "still works" in a 6.1/7.4-only profile without the `knowledge.recording: false` exception (ToolAnnouncementTest Javadoc :31-32; setup.md:463 names it). True for the default configuration; optional.

PREVIOUS FINDINGS (the named minors):
- T006 → resolved (`requireNonNull(depth)`).
- T008 → resolved (filter takes default methods the port overrides; `RefusingPorts.queueEntries` now checked; non-overridden defaults still skipped).
- T009 → resolved (method Javadoc matches VersionGate.java:938-948).
- T010 → partly resolved (see first finding).
- T015 → resolved without losing coverage (renamed; comment points to `CliClientProbeTest#aSevenFourNodeStartsNoHelpRunWhileNothingIsVerifiedOnIt`, which exists and passes).
- T018 → resolved (containment check with a description; "twelve" matches RECONSTRUCTED_CASES).
- T019 → resolved.
- T020 → resolved (Javadoc no longer claims "as in production").
- T025 JSON example → resolved: `Wiring.allowed` (:1059-1100) adds server_settings, user_directory, model_details for every node and trace_log_read with a client; NodeOverview iterates `Capability.values()`, so trace_log_read comes first; names match `NodeOverview.name()`, reasons match `Capability.label()` and `NodeOverview.refusal`; production 7.4 notes every capability.
- T025 tool lists → resolved: matches ALWAYS in ToolAnnouncementTest :54-55 and the four exclusions in V74RefusalTest; the 6.1 phrasing (README:171, setup.md:403) unchanged; setup.md:2814 is shared by 6.1 and 7.4 and is also true for 6.1 (`theKnowledgeToolsFollowGetHealthEvenWithoutAnyReadTool`).

No out-of-scope item touched. No user-visible behaviour change (the one code change is a null check no caller triggers).

VERDICT: APPROVED
