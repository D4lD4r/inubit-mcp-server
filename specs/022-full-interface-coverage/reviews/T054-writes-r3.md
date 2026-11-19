TASK: T054 (write set, second approved list W5–W10)  ROUND: 3
STAGE1: PASS
STAGE2: PASS
TESTS: `env -u INUBIT_LIVE_NODE mvn -o -q test -Dtest='OperateServerWritesLiveTestSupportTest,OperateServerReadsLiveTestSupportTest,NoCustomerIdentifiersTest' -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-022-tmp` exit 0: 70 / 19 / 20, 0 failures. No live test, no full build. No change under src/main.

Checked:
1. Gate exact: full list or exactly W5,W6,W7,W7b,W8,W8b,W9,W10; everything else fails before contact (tested); W5_TO_W10 derived from STEPS (same instances).
2. No command outside the approved list: step loop over approved steps only; restorePlan filters RESTORE to forms in the list; filtered properties become "DEVIATION … not restored", nothing sent; `maintenance off` never sent in subset (tested at every step, maintenance on/unknown); no retries.
3. Full-list behaviour identical to r2 (filter is a no-op; r2 tests unchanged apart from the FULL argument).
4. W5: always sent from READY, strict 2xx, expects READY (tested).
5. Same full baseline B0/B1; run folder via INUBIT_LIVE_T054_RUN; docs give run 3 example; no customer identifiers.

Implementer question (round 2 restore still runs when maintenance is found on in the subset): acceptable — approved commands, once each, matches r2 design; B1 still fails naming maintenance.

FINDINGS: none
PREVIOUS FINDINGS: r2 minors (B1 exception class, ANSWERED_503 text copy) unchanged, optional.

VERDICT: APPROVED
