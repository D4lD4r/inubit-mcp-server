TASK: T054 (write set W1–W10, round after live run 1)  ROUND: 2
STAGE1: PASS
STAGE2: PASS
TESTS: `env -u INUBIT_LIVE_NODE mvn -o -q test -Dtest='OperateServerWritesLiveTestSupportTest,OperateServerReadsLiveTestSupportTest,InubitHttpClientTest,NoCustomerIdentifiersTest' -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-022-tmp` exit 0: 54 / 19 / 52 / 20, 0 failures. No live test, no full build. No change under src/main.

Checked:
1. No false 503 tolerance: `answeredStatus` returns 503 only for UNREACHABLE with likelyCause "INUBIT answered 503:", used only in the 503 branch of `statusError`; transport errors, interrupts, timeouts and TLS failures carry other texts/codes (tested).
2. Effect check sound against run 1 recordings (W2a maintenance message vs W2b-after "No Service."); "no" in the right cases (HTML body, missing message, same message, READY).
3. No raw body, excerpt or ready message printed; `.excerpt` owner-only; tests check printed lines.
4. Requests identical to run 1 (same `post(READY_SWITCH, …, permit, writeLock)`, no accepted statuses); StartCLI writes and reads unchanged; no retries.
5. W4/W5/W2u/restore strict 2xx; B1 exception caught (end unknown, stop/restores still named); `INUBIT_LIVE_T054_RUN` validated before contact; gate unchanged; docs updated.

FINDINGS:
- [minor] `run`, B1 catch – exception dropped without trace; message does not say B1 itself failed. – Optional: name the class in the outcome.
- [minor] `ANSWERED_503` copies the production text (package-private elsewhere); drift makes W2b strict (safe). – Optional Javadoc sentence.

PREVIOUS FINDINGS: r1 docs retry sentence → resolved; r1 B1 outcome loss → resolved.

VERDICT: APPROVED
