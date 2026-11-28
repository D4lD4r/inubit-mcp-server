TASK: CLEANUP (Mandate B, list in reviews/cleanup-mandate-b-list.md)  ROUND: 1
STAGE1: PASS
STAGE2: PASS
TESTS: mvn -q -o test -Dtest='CliRunnerMessagesTest,V61SessionParserTest,HealthServiceTest,VersionGateTest,ConfigValidatorTest,EffectiveNodeConfigCliForTest,ToolErrorExceptionTest,CliRunnerTest,V61ConnectionTest,V61HealthAdapterTest,V61ProcessQueryAdapterTest,CapabilityMatrixTest,V61UnverifiedReadsTest,CliClientProbeTest' -Dsurefire.failIfNoSpecifiedTests=false → exit 0 (e.g. HealthServiceTest 98, VersionGateTest 121, ConfigValidatorTest 210 / 1 skipped, CliRunnerTest 107, V61SessionParserTest 6, ToolErrorExceptionTest 4). No added line > 100 characters.

| # | Status |
|---|---|
| 1 | done (HealthService Javadoc rewrapped; already short since T045) |
| 2 | done — named() returns error unchanged unless UNEXPECTED_RESPONSE; two V61SessionParserTest cases |
| 3 | done — Javadoc on V61ProcessQueryAdapter (and V61HealthAdapter) 4-argument constructors |
| 4 | done — select-based CliRunnerMessagesTest case; V7_4 line node in EffectiveNodeConfigCliForTest |
| 5 | done — VersionGate @throws rewrapped |
| 6 | done (behaviour) — own-client-only branch probes the pinned version (same derivation as VersionGate.pinnedVersion :893-894); aContradicted74PinProbesThePinnedClientAndStaysRefused; test helpers default to ClientKey.NONE |
| 7 | done — ContactedException(ToolError, Object details); withWarnings keeps details; call sites pass e.details().orElse(null); ToolErrorExceptionTest |
| 8 | done — runSession @throws documents ContactedException |
| 9 | done — recording hook only when markContact |
| 10 | done (optional) — port-await deadline TIMEOUT carries the warning |
| 11 | done — asserts against gate(CapabilityMatrix.production()) on V6_1 and 6.1.0.89, no probe/detection |
| 12 | done — comment points to CliClientProbeTest.aSevenFourNodeIsProbedWithMinusHAsASixOneNode |
| 13 | done — CapabilityMatrix and TestMatrices Javadoc rewrapped |

FINDINGS:
- [minor] VersionGateTest.java:1435-1436 – check uses contains/endsWith instead of full equality (exact texts pinned in V61RefusalTest, VersionLineRegressionTest) – optional: compare one full message.
- [minor] HealthService.java:460 – pin derived inline, duplicating private VersionGate.pinnedVersion – optional: expose from VersionGate.
- [minor] ToolErrorExceptionTest.java:41-49 – details survival tested at class level only, not at the CliRunner/V61Connection re-wraps – optional: assert in one contacted-error test.

VERDICT: APPROVED
