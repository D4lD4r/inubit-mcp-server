FEATURE: specs/021-server-log-download  ROUND: 1

No blockers; every FR, US scenario, edge case and SC maps to at least one task (coverage list in the review transcript: FR-001…FR-018, US1-1…US1-9, US2-1…US2-6, US3-1…US3-4, SC-001…SC-005; gap: maintenance/credentials/TLS edge case without own test).

FINDINGS:
- [major] T035/T036 order and `op` handling – T035 makes HelperRequest.encode() emit `op`, but the helper only learns `op` in T036; the existing helper rejects unknown keys (QueueEntryHelper.readRequest "unknown or repeated key", exit 2) and requires every key (validate: request.size() != KEYS.size()); HelperRequestTest round-trips through the real helper, so T035 cannot end green, and "every 020 queue test unchanged" in T036 is impossible if `op` is required. Decide required vs optional (default queueEntry) in D-4/T036, move helper acceptance of op=queueEntry into T035 (or before), T036 only adds op=serverLog; keep the round-trip test for both operations.
- [major] Dependencies / Parallel opportunities – [P] tasks touching the same files: T041 (ConfigSummary.java, ConfigSummaryTest.java, MainTest.java) vs T009, T030, T032; T044 (docs/setup.md) vs T009; T056 and T057 both extend ServerLogDocsTest.java. Make T041/T044 depend on T009, T041 not parallel to T030/T032, T057 after T056 (or fold), fix the Parallel opportunities section.
- [minor] T026 – slot-wait TIMEOUT from CliConcurrencyLimiter.take says "raise cliTimeout"; must name cliExportTimeout; assert the next-step text in CliServerLogAdapterTest.
- [minor] D-13/D-15 vs FR-013 – ExcerptPermit wait and placement outside the deadline although FR-013 bounds each call; bound the permit wait by the remaining cliExportTimeout (TIMEOUT) or reword FR-013.
- [minor] T028/contract Variants – US2-4 alternative branch: webservice + presence UNKNOWN must carry the "web service logging may be disabled" hint; add and test.
- [minor] Edge case maintenance mode / credentials / TLS – no task asserts SECRET_UNRESOLVED, MAINTENANCE_MODE, TLS_ERROR for download_server_log on either channel (nothing launched, no file, audit FAILED); add to T025/T040 or T031/T043.
- [minor] T044 – stale note about "30 s" in configuration-delta.md; remove.
- [minor] T029 – INFO line has no implementing task; make T029 test-first implementation or move into T028.
- [minor] T018 – test belongs in application/ (uses application/LogWindowReader); use the real known-span function (SecretScrubber.occurrences with configured secrets) so the 20 s bound includes the scrubber scan.
- [minor] Parallel opportunities – T016 listed alongside T010 but needs LogWindow from T010.
- [minor] research D-6 – first bullet "8.1 both SUPPORTED" contradicts the third; reword to "per (version, log) after its recording".
- [minor] D-11/T030 – known-credential spans from SecretScrubber.global(); credentials of other nodes not yet read are not masked; name the limit or use domain/port/ConfiguredSecrets with prepare(...) before masking.
- [minor] T009/T030 size – T030 could be split into (a) tool + schemas + description test, (b) wiring announcement/allowed() + wiring tests.

VERDICT: CHANGES_REQUESTED
