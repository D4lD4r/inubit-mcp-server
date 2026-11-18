TASK: T054 (write set W1–W10)  ROUND: 1
STAGE1: PASS
STAGE2: PASS
TESTS: `env -u INUBIT_LIVE_NODE mvn -o -q test -Dtest='OperateServerWritesLiveTestSupportTest,OperateServerReadsLiveTestSupportTest,CliCommandTest,NoCustomerIdentifiersTest' -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-022-tmp` exit 0: CliCommandTest 191, OperateServerReadsLiveTestSupportTest 19, OperateServerWritesLiveTestSupportTest 43, NoCustomerIdentifiersTest 20, 0 failures. No live test, no full build.

Checked on request:
1. No command outside the catalogue, no retry: W-steps from production CliCommand factories and RestEndpoint.READY_SWITCH (forms pinned by test); RESTORE maps each property to one catalogue command, each property in exactly one restore round; no retries in InubitHttpClient.post or CliRunner.run; MaintenanceProbe.NONE; state reads reuse R1, R3, R6, R8; no production code changed.
2. No silent off-baseline: assertOutcome fails on any stop and on any off-baseline or unknown property at B1; restore exceptions listed (test aFailedRestoreIsNotRetriedAndTheTestNamesTheStateLeft).
3. Read test behaviour unchanged (same order); only two failure messages reworded.
4. Unreadable property counts as off-baseline during restore: accepted (restore commands are idempotent baseline setters, approved, once each; B1 verifies; documented; tested).
5. IDE signature errors stale; module compiles, tests pass.

FINDINGS:
- [minor] docs/live-tests.md:824 – "No step is retried", yet a restore command can equal the failed step's command (sent once more). – Add half a sentence.
- [minor] OperateServerWritesLiveTestSupport.java:468 – an exception in the final B1 read loses the stop/restore summary (test still fails loudly). – Optional: catch around B1, report end state unknown.

VERDICT: APPROVED

Orchestrator follow-up (same commit): finding 1 fixed in docs/live-tests.md. Finding 2 left for the phase code review.
