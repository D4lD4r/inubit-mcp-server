FEATURE: specs/021-server-log-download  ROUND: 2

All 13 round-1 findings resolved (verified against ConfiguredSecrets, ProfileSecrets.timeout(), Wiring.java:437). Full coverage FR-001…FR-018, US1-1…US1-9, US2-1…US2-6, US3-1…US3-4, edge cases, SC-001…SC-005, except the FR-013 "version check" clause.

FINDINGS:
- [major] plan.md Constraints FR-013, research D-15, T027 – spec FR-013 (ba5d9e0b) bounds the "version check" by cliExportTimeout; the plan starts the deadline after VersionGate.admitWithDetection, which takes no bound (application/VersionGate.java:386); detection is bounded only by the node's REST `timeout`. Reword FR-013 ("version check bounded by the node's `timeout`, as for every tool (cached)") and mirror in D-15/plan, or start the deadline before the gate with bounded detection.
- [minor] T023 – "FileWasNotFound → NOT_FOUND in the trace context only": CliOutputClassifier.classify(CliResult) has no context parameter; name the mechanism (classifyTraceDownload used by CliTraceRunner, or the rule inside CliTraceRunner) and adjust the "export context unchanged" test.
- [minor] T045 – section-6 summary example with serverLogRead depends on the format implemented in T042; make T045 depend on T042.
- [minor] T017/T028 – remaining time may be negative after a long download; clamp to Duration.ZERO and test "deadline already passed after download" (prepare(ZERO); permit still taken if free).
- [minor] contracts/mcp-tools-delta.md vs data-model LogExcerpt / T030 – `excerpt.returnedLines` not in data model or tasks; state it is lines.size(), set during JSON mapping (T030 output schema).

VERDICT: CHANGES_REQUESTED
