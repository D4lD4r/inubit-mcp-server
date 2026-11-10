TASK: phase 6 code review fixes  ROUND: 1
STAGE1: PASS
STAGE2: PASS
TESTS: InventoryServiceTest 49 (new: aPartnerReadPastItsBudgetIsATimeoutOfThePartnerUsageOnlyAndFillsTheCacheLater; fails with the fix reverted: the read blocks 20 s and the call ends INTERNAL) — 0 failures; full build see reviews.md.
FINDINGS:
- none
Decisions judged: the read thread is never cancelled; `InventoryCache` shares one load per key (a `refresh` joins a running load too), so retries and concurrent calls join it instead of starting new partner reads, each call adds at most one cheap waiting virtual thread, bounded by the adapter's per-request timeouts; a timed-out read that later fails removes its entry (`entries.remove(key, mine)`), nothing is cached, as before for a call `FanOut` cancelled. Deadline: the wait starts after the diagram steps `itemDeadline` already bounds and lasts at most `3 × timeout + cliExportTimeout`, the term `itemDeadline` adds, so the partial result with `partnerUsage.unavailable` (`TIMEOUT`) comes back within the `FanOut` deadline (`FAN_OUT_GRACE` to spare). Interrupt: re-interrupted, `TIMEOUT` thrown, `FanOut` has already recorded its own result. `ExecutionException` rethrows the cause (only `RuntimeException`/`Error` possible). No thread context lost: no MDC or `ScopedValue`; the only `ThreadLocal` (`NodeVersions.HANDING_OFF`) is for write hand-offs; gate refusals still reach `InventoryService`'s catch; credentials are held since the inventory port was acquired. The test is deterministic on its first call (20 s latch vs 300 ms budget, outer deadline above 34 s); its second call needs the released load within 300 ms (slight risk). Contract `other-changes.md` updated; `docs/tools.md` `partnerUsage` belongs to T032.
VERDICT: APPROVED

Handled: the slight risk of the test's second call removed: after releasing the read the test restores the default timeouts, which the budget reads per call.
