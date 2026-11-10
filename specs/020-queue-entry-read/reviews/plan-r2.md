FEATURE: specs/020-queue-entry-read  ROUND: 2
All round-1 findings fixed (verified against the codebase). Full coverage of FR-001..FR-020, US1..US3, edge cases, SC-001..SC-005.
FINDINGS:
- [minor] T003/T027 – stale cross-references after renumbering (T042 → T038, T040 → T035).
- [minor] T002 – TestNodeConfig path is src/test/java/de/dadecker/inubit/mcp/adapter/TestNodeConfig.java; direct constructor callers EffectiveNodeConfigCliForTest, StartCliPinProbeTest, CliRunnerTest.
- [minor] contracts/mcp-tools-delta.md – no SECRET_UNRESOLVED row.
- [minor] plan Constitution Check – helper compiled with release 17 vs constitution "Java 21 or newer": name as justified deviation pointing to Complexity Tracking.
VERDICT: APPROVED
