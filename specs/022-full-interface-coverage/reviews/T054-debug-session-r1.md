TASK: T054 (debug clarification, `live/OperateServerDebugSessionLiveTest`)  ROUND: 1
STAGE1: PASS
STAGE2: PASS
TESTS: `env -u INUBIT_LIVE_NODE mvn -o -q test -Dtest='OperateServerDebugSessionLiveTestSupportTest,OperateServerWritesLiveTestSupportTest,OperateServerReadsLiveTestSupportTest,CliSessionCommandTest,NoCustomerIdentifiersTest,DocsWindowsStatementsTest' -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-022-tmp` exit 0: 12/70/19/40/6/20, 0 failures. IDE unresolved imports are a stale index. No live test.

Checked: no change in src/main or pom.xml; SESSION_OPTIONS untouched and `command("debug")` still refused; gate order (LiveTarget, approval, record dir, no task folder) before contact; requireV81 before folder and StartCLI; exactly one session `debug, debug on, debug, debug off, debug` + `exit` via production runSession (same client options as CliSettingsRunner), no retries; stdin only password + lines; owner-only recording; console only derived states, exit codes, byte counts.
Implementer choices: (a) test enum on package-private `command(String, Map)` acceptable; (b) skip R6 after ToolErrorException/login failure reasonable; (c) assertNoFailure wording acceptable.

FINDINGS:
- [minor] OperateServerDebugSessionLiveTest.java:94 – only isLoginFailure stops R6; non-zero exit/truncation/missing CLI right still send R6; docs could be read as "R6 not sent" on any error. – Clarify docs or also skip on isMissingCliRight.
- [minor] OperateServerDebugSessionLiveTest.java:127 – message "T054 reads that ended in an error" also covers the session entry. – Optional neutral wording.
- [minor] OperateServerDebugSessionLiveTest.java:100-123 – skip-R6 rule has no offline test. – Optional.

VERDICT: APPROVED

Orchestrator follow-up (same commit): finding 1 resolved in docs (non-zero session exit is recorded, R6 still runs). Findings 2 and 3 left for the phase code review.
