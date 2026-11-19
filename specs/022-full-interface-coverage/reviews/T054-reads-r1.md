TASK: T054 (read set R1–R9 only)  ROUND: 1
STAGE1: PASS
STAGE2: PASS
TESTS:
- `env -u INUBIT_LIVE_NODE mvn -o -q test -Dtest='OperateServerReadsLiveTestSupportTest,CliCommandTest,OperateServerReadsLiveTest' -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-022-tmp` exit 0: OperateServerReadsLiveTestSupportTest 18, CliCommandTest 191, 0 failures. Live test excluded by tag; not run against a server.

All requirements met:
- Read set: R1–R9 in the approved order and forms; R1/R2 from the production factories; R3 unauthenticated `GET /ready`; R8/R9 authenticated; each read once, no retries; REST reads accept every status; `recordCli` fails if a read started more than one StartCLI run.
- Gates: LiveTarget (node, production refusal); `INUBIT_LIVE_T054_READS` (unset → skip, other than `approved` → fail); `INUBIT_LIVE_RECORD_DIR` via `QueueEntryLiveTestSupport.privateDirectory`; `requireV81` before R1.
- Allowlist: only production change is package-private `CliCommand.unlisted(List<String>)`, called only by the test-source enum `UnlistedReadCommand`; `OPTIONS` unchanged; `trace`/`debug` and `--help` still refused by the Builder.
- Credentials from `NodeSecrets` as in production; password on stdin through `CliRunner.run`; `ProcessRecorder` records no stdin.
- Output only to owner-only files below `<INUBIT_LIVE_RECORD_DIR>/T054/`; console and failure messages carry ids, codes and byte counts only.
- docs/live-tests.md row and section; no customer identifiers.

FINDINGS:
- [minor] OperateServerReadsLiveTest.java:98-101 – real `V81MaintenanceProbe`: on a 503 of the version read it sends `GET /ibis/rest/healthcheck`, outside the approved set. – Pass a probe returning empty, or document it.
- [minor] OperateServerReadsLiveTest.java:81 – T054 folder created before the credential and 8.1 checks; a refused run leaves an empty folder and blocks the next run. – Check for absence first, create after `requireV81`.
- [minor] CliCommand.java:189-198 – "no production caller" of the seam is not enforced beyond package-private visibility. – Optional scan test.

VERDICT: APPROVED

Orchestrator follow-up (same commit): findings 1 and 2 fixed — `MaintenanceProbe.NONE` instead of the V81 probe; `requireNoTaskDirectory` before any contact, folder created after `requireV81` (new offline test). Area tests rerun: OperateServerReadsLiveTestSupportTest 19, CliCommandTest 191, LiveTargetTest 19, NoCustomerIdentifiersTest 20, 0 failures. Finding 3 left for the phase code review.
