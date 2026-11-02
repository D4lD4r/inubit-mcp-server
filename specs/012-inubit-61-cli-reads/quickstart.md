# Quickstart: Validating Read-Only INUBIT 6.1 Support

How to prove the feature works. Offline scenarios need no INUBIT server; the live part needs the
person's approval in the chat (FR-020). Contracts: [configuration-delta](contracts/configuration-delta.md),
[mcp-tools-delta](contracts/mcp-tools-delta.md); decisions: [research.md](research.md).

## Prerequisites

- JDK 21, Maven 3.9; the repository builds offline: `mvn -q -o clean verify`.
- For the live part only: a 6.1 node reachable from this machine, a StartCLI client for it (6.1
  client with its Java runtime, or the 8.1.17 client), a password-less trust store and the pin of
  the node's certificate.

## Offline validation

```bash
mvn -q -o clean verify
mvn -q -o test -Dtest='V61*Test,VersionGate*Test,CapabilityMatrixTest,HealthServiceTest,DiagnosisServiceTest,ListNodesToolTest,ConfigValidatorTest,TlsHandshakeCheckTest,NoCustomerIdentifiersTest'
```

| Scenario (spec) | Expected |
|---|---|
| `versionLine: V6_1`, `"6.1.0.89-HF1"` load; `"6.1"` is refused (FR-002) | config tests green; error text names `V6_1` |
| `get_health` on a pinned 6.1 node with session fixtures (US1) | report with memory, counts, maintenance, `ready`/`threads`/`blockingQueue` as `NOT_AVAILABLE_ON_6_1`; WireMock records 0 requests |
| alive check negative / login refused / unreachable (US1-2, US1-6) | `status` `ERROR` / `UNKNOWN`; one login per 60 s |
| group with 8.1 and 6.1 nodes (US1-5) | each node by its own channel; within 10 s; 6.1 parts `TIMEOUT` when the fake session hangs |
| `find_processes` states, hanging, workflow, time range, cap, `Total: 0` (US2) | filtered, newest first, truncation marker at 10,000 rows, empty page |
| `tag` filter on 6.1 (US2-4) | `UNSUPPORTED_VERSION` naming `tag`, no launch |
| every unsupported tool on 6.1 (US3, SC-003) | `UNSUPPORTED_VERSION` before any launch, TLS check or REST request |
| contradicted 6.1 pin (US3-6) | `find_processes` refused, `get_health` runs |
| `AUTO` node, detection HTTP 404 vs. 401 (US3-7) | hint only for 404 |
| `check_artifacts` with module lookup (US3-8) | offline findings plus `MODULE_UNVERIFIED` with the 6.1 text, no launch |
| pin mismatch / untrusted certificate on the local TLS test server (US5-1, US5-2) | `TLS_ERROR`, no launch |
| host-name skip without client option (US5-4) | `TLS_ERROR` naming the option; `--check-config` warning |
| password channel (US5-7) | password only on stdin; not in argv or environment of the fake launch |
| five parallel calls on one 6.1 node (US5-10) | at most two launches at once; the rest wait, then `TIMEOUT` |
| production matrix vs. `RECONSTRUCTED.txt` (FR-005) | `V61RecordedFixturesTest` green |
| existing 8.1 suites (SC-005) | green, apart from the capability names and the 404 hint |

## Live recordings (FR-020, by hand, read-only)

Run only after the person has approved the recording in the chat; for a node classified
production, the person also confirms node and classification. Nothing here writes to INUBIT.

1. Configure the node in a local profile with `versionLine: V6_1` (never committed).
2. Record the catalogue cases with StartCLI only (password from the environment via stdin):

   ```bash
   RECORD_ALLOWED_GROUPS=legacy tools/record-fixtures.sh legacy node1 v61_help
   ```

   Cases: `v61_help`, `v61_version`, `v61_health_session`, `v61_ps_all`, `v61_ps_error`,
   `v61_ps_waiting`, `v61_ps_queued`, `v61_ps_limit5`; for US4 later `v61_export_group`,
   `v61_export_repository`.
3. Neutralize with `tools/neutralize.py` and the local maps; run
   `mvn -q -o test -Dtest=NoCustomerIdentifiersTest` (must run, not be skipped).
4. Answer the REC questions of research D-5, D-6 and D-10 in `docs/research/inubit-interfaces.md`
   (6.1 section): interactive mode through stdin; `uname` = server version; meaning of `DATE`;
   Processing rows in `ps -a`; rows dropped at `-l`; German labels.
5. Replace the reconstructed cases, remove them from `RECONSTRUCTED.txt`, flip the 6.1
   capabilities in `CapabilityMatrix.production()`, run `mvn -q -o clean verify`.

## Live smoke check (optional, after step 5, read-only)

With the approved node: `get_health` for the node (within 15 s, version and counts plausible),
`find_processes` with `states: [ERROR]`, and `query_logs` (expect `UNSUPPORTED_VERSION`). Record
the result in the "Validation record" section of this file.

## Validation record

### Offline validation, 2026-11-02 (T043, before Phase 8)

- **State**: commit `d83e045` on branch `012-inubit-61-cli-reads`, clean working tree, before
  Phase 8 (T036–T040 open). At this commit `CapabilityMatrix.production()` has every
  capability `UNSUPPORTED` on 6.1 (`CapabilityMatrixTest.everyCapabilityIsUnsupportedOn61`),
  and all 6.1 fixtures are still the reconstructed cases listed in `RECONSTRUCTED.txt`.
- **`mvn -q -o clean verify`**: exit 0. The surefire reports list 273 classes, 4060 tests,
  0 failures, 0 errors, 3 skipped. The build has no failsafe reports (no integration-test
  phase is configured), so these figures are the totals. The 3 skipped are the opt-in corpus
  tests (`ArchiveCorpusTest`, `XsltCorpusTest`, `StructureCorpusTest`), which skip through
  `assumeTrue` when no local corpus is configured. They are unrelated to 6.1.
- **`NoCustomerIdentifiersTest`**: 20 tests, 0 failures, 0 errors, 0 skipped (in the full
  build and in the focused run).
- **Focused command of this file** (`mvn -q -o test -Dtest='V61*Test,VersionGate*Test,…'`):
  exit 0; 18 classes, 593 tests, 0 failures, 0 errors, 0 skipped.
- **Additional classes for the rows below** (`ConfigLoaderTest`, `VersionSettingTest`,
  `GetHealthToolTest`, `FindProcessesToolTest`, `ArtifactCheckServiceTest`,
  `AdapterGatewayFactoryTest`, `CliRunnerTest`, `CliConcurrencyLimiterTest`,
  `CliClientProbeTest`, `MainTest`, `ToolAnnouncementTest`, `PortSetRegistryTest`,
  `RefusingPortsTest`, `RecordFixturesCatalogueTest`, `GetHealthOutputSchemaTest`): exit 0;
  484 tests, 0 failures, 0 errors, 0 skipped.

**Read this first.** Before T040, the supported 6.1 paths (`get_health` and `find_processes`
over StartCLI) are proven only with a test matrix that declares them `SUPPORTED`
(`WITH_61` / `with61()` / `matrix61()` in the tests), against reconstructed fixtures. With
the production matrix, every tool on a 6.1 node returns `UNSUPPORTED_VERSION` before contact
(`V61RefusalTest`, which uses the production wiring). Rows marked **Phase 8** pass at this
commit only in that sense. Re-run them after T040, once the recorded fixtures are in and the
production matrix declares the capabilities supported.

| Scenario (spec) | Result at `d83e045` | Evidence |
|---|---|---|
| `versionLine: V6_1`, `"6.1.0.89-HF1"` load; `"6.1"` refused (FR-002) | pass | `ConfigLoaderTest` `sixOneIsALineAndAFourPartPin`, `anythingElseIsRefusedWithoutEcho` (error text lists `V6_1`); `VersionSettingTest` |
| `get_health` on a 6.1 node with session fixtures (US1) | pass with test matrix, **Phase 8**; production matrix: `UNSUPPORTED_VERSION` | `GetHealthToolTest.a61NodeIsReportedFromOneStartCliSessionAsInTheContractWithoutRest` (memory, counts, maintenance, 3 × `NOT_AVAILABLE_ON_6_1`, 0 WireMock requests to the 6.1 node, 1 launch); `V61RefusalTest.getHealthReportsTheRefusalForThe61Node` |
| alive check negative / login refused / unreachable (US1-2, US1-6) | pass with test matrix, **Phase 8** | `GetHealthToolTest.aNegativeAliveCheckOnA61NodeIsErrorWithTheOtherPartsStillReported` (`ERROR`); `HealthServiceTest.aSessionThatCouldNotRunIsUnknownWithItsError` (`UNKNOWN`), `aRejectedLoginIsTriedOnceWithinSixtySecondsAndTheSecondReportSaysWhy`; `V61HealthAdapterTest` login-failed / unreachable cases |
| group with 8.1 and 6.1 nodes (US1-5) | pass with test matrix, **Phase 8** | `GetHealthToolTest.aGroupOfAn81AndA61NodeReportsEachByItsOwnChannelWithinTheOverviewBudget` (< 10 s); `HealthServiceTest.aHanging61SessionInAnOverviewIsTimeoutWithinTheBudgetWhileTheOtherNodesReport`; `V61HealthTimingTest` |
| `find_processes` states, hanging, workflow, time range, cap, `Total: 0` (US2) | pass with test matrix, **Phase 8** (D-10 questions about `DATE` and Processing rows are open until T038/T039) | `V61ProcessQueryAdapterTest` (newest first, hanging threshold, workflow, `since` inclusive, truncation at 10,000 rows, empty listing → empty page); `V61PsParserTest`; `FindProcessesToolTest.theFailedProcessesOfA61NodeAreListedFromOneSessionWithinFifteenSeconds` |
| `tag` filter on 6.1 (US2-4) | pass | `V61ProcessQueryAdapterTest.validateRefusesTagForTheNodeWithoutLaunching`; `FindProcessesToolTest.tagInAMixedGroupRefusesThe61NodeWithoutAnyLaunchAndListsThe81Node`; `DiagnosisServiceTest.aHeld61NodeRefusingTag…` |
| every unsupported tool on 6.1 (US3, SC-003) | pass; at this commit `get_health` and `find_processes` are refused as well (production matrix) | `V61RefusalTest` (16 tests, production wiring: 0 launches, 0 requests to the 6.1 node); `VersionGateTest.theProductionMatrixRefusesEveryCapabilityOf61BeforeAnyDetectionOrProbe`; `RefusingPortsTest` |
| contradicted 6.1 pin (US3-6) | refusal part passes; "`get_health` runs" holds only with test matrix, **Phase 8** (production matrix refuses monitoring on 6.1) | `VersionGateTest.aContradicted61PinRefusesEveryCapabilityExceptMonitoring`, `monitoringAccessOfA61PinIsCliWhenSupportedEvenWhileThePinIsContradicted`, `monitoringAccessOfA61PinIsRefusedWithTheProductionMatrix`; `ListNodesToolTest.aContradictedV61PinLeavesNoCapabilityUsable` |
| `AUTO` node, detection HTTP 404 vs. 401 (US3-7) | pass | `VersionGateTest.theUnknownVersionHintsAtV61OnlyAfterADetectionAnsweredWith404`; `AdapterGatewayFactoryTest.aDetectionAnswered404IsReportedWithTheEndpointMissing`, `rejectedCredentialsAreNotRetriedWithinTheWindow` (401 → `AUTH_FAILED`, no endpoint-missing flag) |
| `check_artifacts` with module lookup (US3-8) | pass | `ArtifactCheckServiceTest.onA61NodeTheLookupIsSkippedWithTheGatesRefusalAndTheOfflineChecksRun`, `whileThe61PinIsContradictedTheLookupIsSkippedAndTheOfflineChecksRun` (no StartCLI run, no REST request) |
| pin mismatch / untrusted certificate on the local TLS test server (US5-1, US5-2) | pass | `V61ConnectionTest.aPinMismatchIsATlsErrorAndStartsNoSession`; `TlsHandshakeCheckTest.anUntrustedCertificateIsATlsError`, `aPinMismatchIsATlsErrorNamingThePin`. On the 6.1 path, the untrusted case is covered by the same `TlsHandshakeCheck.verify` call that runs before the launch; `V61ConnectionTest` has no dedicated untrusted-certificate test |
| host-name skip without client option (US5-4) | pass | `V61ConnectionTest.aClientWithoutTheHostNameOptionIsRefusedNamingIt` (`TLS_ERROR` naming `--disableHostNameVerification`, no handshake, no launch); `ConfigValidatorTest.aClientWithoutHostNameOptionIsAWarningWhenHostNamesAreSkippedWithAPin`; `MainTest.checkConfigWarnsAboutWhatTheStartCliClientOfAV61NodeCannotDo` |
| password channel (US5-7) | pass | `CliRunnerTest.aSessionHasNeitherThePasswordNorAnyCommandTextInItsArguments`, `aSessionWritesThePasswordLineTheCommandsAndExitToStdinWhichIsThenClosed`, `aSessionSharesTheEnvironmentAllowlistAndNoPasswordReachesIt`; `V61HealthAdapterTest.theSessionSendsThePasswordAndTheHealthCommandsOnStandardInput`; `RecordFixturesCatalogueTest` |
| five parallel calls on one 6.1 node (US5-10) | pass | `V61ConnectionTest.fiveParallelCallsWithHangingSessionsLaunchAtMostTwo` (2 launches, 3 × `TIMEOUT`); `CliConcurrencyLimiterTest.fiveParallelAcquisitionsOnA61NodeAdmitTwoAndTimeOutTheRest` |
| production matrix vs. `RECONSTRUCTED.txt` (FR-005) | green, but only because nothing is supported on 6.1 yet; **Phase 8**: meaningful only after T040 | `V61RecordedFixturesTest` (2 tests); all 14 entries (13 cases) of `RECONSTRUCTED.txt` are still listed |
| existing 8.1 suites (SC-005) | pass | full `clean verify` above (4060 tests, 0 failures, 0 errors) |

### Live smoke check

Not run: the person has not approved it, and no INUBIT 6.1 node is available. The live
recordings (T036) were not run either. Nothing was run against an INUBIT server or AWS.

### To re-run after T040

The rows marked **Phase 8**, `V61RecordedFixturesTest` against the recorded cases,
`V61RefusalTest` (which switches to "admitted" for `get_health`/`find_processes`), the full
`mvn -q -o clean verify`, `NoCustomerIdentifiersTest` with the neutralized recordings, and,
with approval, the live smoke check.
