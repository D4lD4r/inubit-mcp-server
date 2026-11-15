TASK: merge of origin/main (feature 022: #87, #88, #89) into feature 024 + T074 (partial)  ROUND: 1
STAGE1: FAIL (bookkeeping only)
STAGE2: PASS

TESTS: ALL (`mvn clean verify`): exit 0, 10064 tests, 0 failures, 58 skipped; `check-identifiers.py --all` exit 0; no conflict markers; lost-content script (every line each parent added since merge-base 913fdf93 checked against the result): nothing lost, every missing line a deliberate rewrite.

FINDINGS:
- [major] tasks.md T058/T065/T074 – the follow-up "set the `CLI trace --nodelevel/--package/--reset` rows to `reachable` on the line that verifies log_level_write" was only in reviews/implementation.md and the TSV header; T074 still named `trace -nl`/`-r` "reachable". → fixed by the orchestrator: notes under T058, T065 and T074.
- [minor] CHANGELOG.md:31-32 – missing blank line between the 022 and 024 paragraphs. → fixed.
- [minor] PortSet.java:47 – 131-char Javadoc line. → fixed.
- [minor] AdapterGatewayFactoryTest.java:1340, RefusingPortsTest.java:151 – over-long lines. → fixed.

Points:
1. Capability states: 022's SERVER_SETTINGS/USER_DIRECTORY supported on 8.1/9.0, unsupported elsewhere; 024's states exact; unknown-version map equivalent.
2. PortSet chain 16 → 12 (NodeToolsUnavailable) → 11 → 10 → 9 → 8; V81PortSet with helper host passes the real node-tool adapters plus 022's settings/users adapters; V61 refusing; wrappers cover all 16 ports; both sides tested. 024 node tools use VersionGate.admit, not 022's coverage gate.
3. T074 deviations acceptable: long-form rows (check 1), `planned` US4 (check 3), `cachemode nodelist` excluded, coverage page regenerated (254 entries), ChangeGuard hand-off recorded.
4. Counts and order consistent ("twenty-nine", 58 schemas, list_nodes order, announcement order, CHANGELOG entries complete).
5. Follow-up integration edits correct (ConfigSummary.optedIn, MainTest, CliSettingsRunnerTest, CapabilityExtensionTest).

VERDICT: CHANGES_REQUESTED → findings fixed by the orchestrator and the merge agent (affected tests green, check-identifiers exit 0).
