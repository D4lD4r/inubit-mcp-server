FEATURE: specs/021-server-log-download  ROUND: 3

All five round-2 findings fixed. Spec round 4 (705eae96) changed US1-8, US1-9, added US1-10 and extended FR-010/FR-013; plan and tasks do not fully follow yet.

FINDINGS:
- [major] T013, T014, quickstart rows US1-8/US1-9 – test statements follow the pre-round-4 wording. T013: "token on line 10, line 11 empty, line 12 = `</password>` + following text, line 13 keeps number 13". T014: "every line from the window start up to the end tag masked; the end-tag line keeps the end tag and the text after it". Same in quickstart.md.
- [major] contracts/mcp-tools-delta.md withheld variant, D-11, T028 – FR-010 requires the withheld result to say that every excerpt is withheld until that source can be read; extend the hint (e.g. "…; every excerpt is withheld until this source can be read; the file is written") and assert it in T028.
- [major] FR-013 "the server's login credentials" – plan Constraints, D-15, T027 – with a pinned versionLine the first wait is GatewayFactory.serverLogs(node) → AdapterGatewayFactory Slot.gateway() → awaitCredentials() waiting secrets.timeout() (up to 60 s; adapter/AdapterGatewayFactory.java:343), not bounded by cliExportTimeout. In T027 call GatewayFactory.prepareCredentials(node, remaining) (domain/port/GatewayFactory.java:189) after setting deadlineNanos and before serverLogs(node): PENDING → TIMEOUT naming cliExportTimeout; fetch failure passes through (SECRET_UNRESOLVED…). Mirror in D-15 and plan Constraints. Tests in ServerLogServiceTest: PENDING → TIMEOUT, port not called, audit FAILED; bound equals remaining deadline.
- [minor] T028 / US1-10 – assert audit EXECUTED for the withheld case.
- [minor] tasks.md Dependencies US2 line – "T045 needs T009" → add T042.
- [minor] data-model.md ServerLogService row and State and lifecycle – ExcerptPermit.tryAcquire shown before the NotReady check; T028 takes no permit on NotReady; align.
- [minor] D-15 Version check note – VersionGate with channel CLI may probe the StartCLI client (probeClient, bounded by cliTimeout); state that the 017 client check (cached) is bounded by cliTimeout, or confirm it does not run for this capability.

VERDICT: CHANGES_REQUESTED
