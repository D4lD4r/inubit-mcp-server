# Quickstart: INUBIT 7.4 Support

## Mandate A — offline validation (any machine, no INUBIT contact)

```bash
mvn -q -o clean verify
```

| What | Test(s) | Spec |
|---|---|---|
| `V7_4` and 7.4 pins load; error text names `V7_4` | `VersionSettingTest`, `ConfigLoaderTest` | FR-001 |
| fourth part kept, shown | `InubitVersionTest` | FR-002, FR-004 |
| patch comparison with four parts on 7.4, three elsewhere | `ClientVersionTest`, `CapabilityMatrixTest`, `ConfigSummaryTest`, `ListNodesToolTest`, `VersionGateTest` | FR-003, SC-004 |
| 404 hint names `V6_1` and `V7_4`; other failures unchanged | `VersionGateTest` (hint) | FR-005, SC-002 |
| every contacting tool refused on 7.4, before any launch, request or handshake | `V74RefusalTest` (one case per tool) | FR-006, FR-007, FR-011, SC-003 |
| refusal logged | `V74RefusalTest` (log record) | FR-012 |
| `check_artifacts` skips the lookup | `ArtifactCheckServiceTest` (7.4 case) | FR-008 |
| 7.4-only profiles announce only the always-announced tools (`get_health` stays announced and refuses per node, feature 012 D-12); no read, opt-in, write, log-level, development, deployment or e2e tool | `ToolAnnouncementTest`, `DevelopmentWiringTest` (7.4 cases) | FR-009 |
| `list_nodes` / `--check-config` show 7.4 | `ListNodesToolTest`, `ConfigSummaryTest` | FR-010, SC-001 |
| `cliClients."7.4"` handling, no 6.1-style warning, note in `--check-config` and `list_nodes` warnings | `ConfigValidatorTest`, `ConfigSummaryTest`, `ListNodesToolTest`, `MainTest` (`--check-config`, POSIX only) | FR-010a |
| no `-h` option probe for 7.4 | `CliClientProbeTest`, `ConfigValidatorTest`, `MainTest` (POSIX only) | FR-010a, FR-007 |
| 7.4 port set refuses, builds no REST client | `V74PortSetTest`, `AdapterGatewayFactoryTest` | FR-011 |
| fixture layout and guards | `V74FixturesShapeTest`, `V74RecordedFixturesTest` | FR-014, FR-015, FR-016a |
| the real help case | `V74HelpContractTest` | FR-014 |
| recorder catalogue and safeguards | `RecordFixturesPs1Test` | FR-016 – FR-019, SC-006 |
| 6.1 / 8.1 / 9.0 unchanged | `VersionLineRegressionTest` + the existing suite | FR-020, SC-005 |

The Windows CI job runs the same suite (FR-013).

Recorder self-check on Windows (no server):

```powershell
powershell -ExecutionPolicy Bypass -File tools\record-fixtures.ps1 -List
```

## Mandate B — validation (offline)

Owner decision 2026-11-26 (spec, Clarifications): the recording session (IMC-22) and the Windows
live check will not take place. Mandate B is validated by the offline suite on the macOS/Linux and
the Windows CI jobs (`mvn -q -o clean verify`; spec FR-030, FR-031, FR-036, tasks T037), for 7.4
and, since the owner's second decision of 2026-11-26, for 6.1 health and processes as well. Its
only precondition, the ratified constitution 2.1.0, is met (2026-11-26, IMC-25). No step below is
needed for Mandate B.

| What | Test(s) | Spec |
|---|---|---|
| unverified read on a CLI-only version runs with the warning (7.4, 6.1); `list_nodes` and `--check-config` count it as usable | `VersionGateTest`, `ListNodesToolTest`, `ConfigSummaryTest` (test matrices, T038) | FR-010, FR-030, FR-036 |
| 7.4 health: parts, `NOT_AVAILABLE_ON_7_4`, failures, CRLF and German variants, TLS | `V74HealthContractTest`, `V74PortSetTest`, `HealthServiceTest` (T031) | FR-024 – FR-027, FR-029 |
| 7.4 processes: fields, states, hanging, time range, truncation, `tag`, variants, version note | `V74ProcessQueryContractTest`, `V74PortSetTest`, `DiagnosisServiceTest` (T032) | FR-024, FR-025, FR-028 |
| client order and flag, `defaults.cliClients."7.4"` text without "cliHome" | `EffectiveNodeConfigTest`, `CliRunnerTest` (T033) | FR-029 |
| fail-closed `-v`, also for `get_health`; `version` case per capability | `VersionGateTest`, `HealthServiceTest`, `ListNodesToolTest`, `CliClientProbeTest`, `V74FixturesShapeTest` (T046) | FR-014, FR-015, FR-029 |
| "; supported: …" never lists a CLI-only line | `CapabilityMatrixTest`, `VersionLineRegressionTest` (T039) | FR-035 |
| guards | `V74RecordedFixturesTest`, `V74FixturesShapeTest` (T040), `V61RecordedFixturesTest` (T042) | FR-015, FR-037, SC-009, SC-010 |
| contacted marker: plain before the handshake or the session's process start, marked after; probes never marked | `V61ConnectionTest`, `CliRunnerTest`, `V74HealthContractTest`, `V74ProcessQueryContractTest` (T041) | FR-030 (b) |
| warnings on failed entries: `NodeResult`, `FanOut` deadline, `SecretTiming`, rendering, schema description | `NodeResultTest`, `NodePagesTest`, `FanOutTest`, `SecretTimingTest`, `FindProcessesToolTest` (T044) | FR-030 (b) |
| services: warning on data, contacted errors and deadlines of admitted nodes, none on pre-contact refusals | `DiagnosisServiceTest`, `HealthServiceTest` (T045) | FR-030 (b), SC-007 |
| 6.1 reads as unverified | 6.1 contract tests, `DiagnosisServiceTest`, `HealthServiceTest` (T043) | FR-036 |
| the flip and the deliberate test changes | `CapabilityMatrixTest`, `V74RefusalTest`, `V61RefusalTest`, `ToolAnnouncementTest`, `ListNodesToolTest`, `ConfigSummaryTest`, `CliClientProbeTest`, `MainTest`, `ConfigValidatorTest`, `VersionLineRegressionTest` (T035) | FR-020, FR-030, FR-035, FR-036, SC-005 |

## Optional later verification — recording session (Windows, by hand, after approval)

Not a precondition of this feature since 2026-11-26. It is kept for a future mandate that may raise
7.4 (or 6.1) from unverified to supported (recordings or evaluated recordings plus a live check,
constitution 2.0.0 / 2.1.0). Preconditions of such a session: Windows machine with the 7.4
Workbench client and its JDK; CLI login to a **non-production** 7.4.0.90 node; the owner's
approval in the chat.

For IMC-17 the steps below are wrapped in the recording kit (`.apex/work/IMC-17/`: run sheet
`record-v74.ps1`, German guide `ANLEITUNG.txt`, built by `build-kit.py`; phases `Check`,
`Research`, `Session`, `Pack`). It needs Python 3.7 or newer (standard library only) for
`tools\neutralize.py`. IMC-24 (spec FR-021): the kit writes the raw results to `C:\imc17-results`
(recorder `-OutputDirectory`) with a map that replaces nothing; they never enter the repository,
are evaluated outside it and deleted afterwards. Steps 1–5 below describe the original
in-repository recording and no longer apply as written.

1. Set the allowlists for this run only:

   ```powershell
   $env:RECORD_ALLOWED_GROUPS = '<group>'; $env:RECORD_ALLOWED_HOSTS = '<non-production host>'
   ```

2. Local and research cases first:

   ```powershell
   powershell -ExecutionPolicy Bypass -File tools\record-fixtures.ps1 -Group <group> -Node <node> `
     -CliHome <7.4 client> -JavaHome <its JDK> -CliUrl <url>/ibis/servlet/IBISSoapServlet `
     -Case v74_help,v74_version,v74_exec_help,v74_exec_ps_help,v74_direct_health
   ```

3. Read `v74_exec_help` and `v74_exec_ps_help`; if the commands differ from the catalogue, adjust the
   7.4 entries of the catalogue (one place) and note the change here.
4. Session and failure cases:

   ```powershell
   ... -Case v74_health_session,v74_ps_all,v74_ps_error,v74_ps_waiting,v74_ps_queued,v74_ps_limit5,v74_login_failed,v74_failure
   ```

5. Run `NoCustomerIdentifiersTest`, review the diff, commit the recordings.
6. Live check on Windows: withdrawn for this feature on 2026-11-26 (spec FR-031; `AUTO` detection
   withdrawn, FR-032). A future mandate defines its own live check.

## Validation record

| Date | Step | Platform | Outcome |
|---|---|---|---|
| 2026-11-19 | Mandate A: `mvn -q -o clean verify` at `b17b853f` (T001–T023) | macOS | exit 0; 11,263 tests, 0 failures, 0 errors, 62 skipped |
| 2026-11-19 | `VersionLineRegressionTest`: unchanged since T003 (`66f2f420`), run alone | macOS | green (38 tests) |
| 2026-11-19 | `NoCustomerIdentifiersTest` with the local identifier lists, in the full suite and alone | macOS | green (20 tests, none skipped) |
| 2026-11-19 | Mandate A: `mvn -q -o clean verify` at `23a97fd2` (T001–T025 and clean-up) | macOS | exit 0; 11,264 tests, 0 failures, 0 errors, 62 skipped |
| 2026-11-19 | CI on [PR #104](https://github.com/D4lD4r/inubit-mcp-server/pull/104) at `23a97fd2`: [build](https://github.com/D4lD4r/inubit-mcp-server/actions/runs/44573778803/job/135735630386) and [windows](https://github.com/D4lD4r/inubit-mcp-server/actions/runs/44573778803/job/135735629782) (run 44573778803), CodeQL | Linux, Windows | all green, first attempt, no rerun |
| 2026-11-23 | Mandate B T026: owner's statements in the chat — a Windows machine (not known to the owner, run together with its owner) with an INUBIT 7.4 Workbench client and its JDK; not production; Python unknown (the run sheet's `Check` phase verifies it); recording approved by the owner. Open: client version (`v74_version` must report 7.4) and the node's non-production classification, confirmed by the machine owner in the run sheet (`$NodeIsNonProduction`) before the first recording | Windows | approval given; preconditions checked at the session |
| 2026-11-26 | Mandate B replanned by the owner: no recording session, no live check; 7.4 health and processes to be offered as unverified (untested) on pinned nodes; detection withdrawn; constitution 2.1.0 proposed (T026–T029, T034 withdrawn) | – | decision recorded in the spec |
| 2026-11-26 | Spec review spec-replan-r1 (CHANGES_REQUESTED) answered: the owner accepts 6.1-derived fixtures for 7.4 unverified and widens Mandate B to 6.1 health and processes; constitution 2.1.0 ratified by the owner (IMC-25, `660683ba`) | – | decisions recorded in the spec |

### Adjusted existing tests

Existing assertions changed on this branch (new cases and new test files are not listed):

- `VersionGateTest` (T016, FR-005): the 404 hint text names `V6_1` and `V7_4`; the case is renamed
  `theUnknownVersionHintsAtV61AndV74OnlyAfterADetectionAnsweredWith404`.
- `ConfigLoaderTest.VersionLineValues` (T004, FR-001): the error text lists `V7_4`; `V7_X` added to
  the refused values. `VersionSettingTest.refusesEverythingElse` (T004): `V7_X` and `7.4` added.
- `RefusingPorts` constructor (T008), production entries in `CapabilityMatrixTest` (T007), static
  `unknown` callers (T016): no existing test needed a change; only new cases were added.
- Beyond the four expected kinds, spec-backed:
  - `ConfigLoaderTest` (`6.1.0.89-HF1` pin), `VersionSettingTest.aFourPartSixOnePinIsSixOneZeroWithTheTextKept`,
    `NodeVersionStateTest` (T005, FR-002): a parsed `6.1.0.89` now keeps its fourth part, so the
    expected value is built with `OptionalInt.of(89)`; these three used the four-argument
    constructor (no fourth part), contrary to research D-2's "no existing equality changes".
    Ordering, range membership and contradiction are unchanged (FR-004, `VersionLineRegressionTest`).
  - `RecordFixturesPs1Test` (T021–T023, FR-016 – FR-018): `listsAllSixReadOnlyCases` became
    `listsEveryCaseOfTheTable` (named in T021); the password-use check accepts
    `-PasswordLine $passwordBytes` (T022, FR-017); the host allowlist is checked in the shared
    `Assert-CliUrl` instead of `Assert-ExportInputs` (T023, FR-017); the `record` helper takes an
    optional `-OutputDirectory` and sets `RECORD_ALLOWED_HOSTS`. The `win_*` cases are unchanged.
- Shared test fixtures extended without changing an existing assertion: the node map of
  `VersionGateTest` (T009, two 7.4 nodes), the nodes of `ListNodesToolTest` (T013, `qa/node5`–`7`).
- Mandate B, T035 (the flip of 7.4 and 6.1 health and processes to `UNVERIFIED`; spec FR-020,
  FR-029, FR-030, FR-035, FR-036, SC-005), each change deliberate:
  - `CapabilityMatrixTest`: `everyCapabilityIsUnsupportedOn61` → `onlyMonitoringAndProcessDiagnosisAreUnverifiedOn61`;
    `everyCapabilityIsUnsupportedOn74WithTheNote` → `onlyMonitoringAndProcessDiagnosisAreUnverifiedOn74`
    (the two without a note, every other capability with "not yet verified");
    `ownClientOnlyIsTheFlagOfTheVersionsEntry` also asserts the production flag (7.4 only);
    `onlyEveryCapabilityNotedIsAllNotYetVerified`: false for every production entry, the positive
    case on a matrix whose 7.4 entry notes every capability.
  - `VersionLineRegressionTest`: the 6.1 rows of MONITORING and PROCESS_DIAGNOSIS `UNSUPPORTED` →
    `UNVERIFIED` (the one deliberate change of that test, FR-020); nothing else.
  - `V74RefusalTest`: `get_health` and `find_processes` leave the per-tool refusal cases; new cases
    run them on `legacy74/node1` through the replayed 7.4 fixtures (`-h` the recorded help, `-v` the
    reconstructed version, sessions `health_session_ok` / `ps_session_all`) with the warning, and
    refuse `bare74/node1` (no client) with `CLI_UNAVAILABLE` naming `defaults.cliClients."7.4"`
    without a warning; the group case `aGroupOf81And74AnswersThe81NodeAndRefusesThe74NodeInItsOwnEntry`
    → `aGroupOf81And74AnswersBothNodesThe74NodeWithTheWarning`; the launcher replays only when a
    test sets sessions; `legacy74/node1` gets a `cli.javaHome` with a Java 8 `release` (the PKCS12
    trust store reaches the 7.4 client through `JAVA_TOOL_OPTIONS`); the `read_io_log` matrix
    repeats the new production 7.4 entry.
  - `V61RefusalTest`: `getHealthReportsTheRefusalForThe61Node` → `getHealthReadsThe61NodeThroughStartCliWithTheWarning`;
    `find_processes` leaves `perNodeReads` for `findProcessesListsThe61NodeThroughStartCliWithTheWarningAndTheVersionNote`;
    the launcher replays the 6.1 help, a 6.1.0 `-v` and the session when a test sets sessions;
    `old/node2` gets a `cli.javaHome` with a Java 8 `release`; every other tool unchanged.
  - `ToolAnnouncementTest`: `onlySixOneNodesBeforeTheFlipAnnounceNoReadTool` and
    `onlySixOneNodesAfterTheFlipAnnounceProcessDiagnosisOnly` (with its own matrix) →
    `onlySixOneNodesAnnounceProcessDiagnosisAsTheOnlyReadTool` on the production matrix;
    `onlySevenFourNodesAnnounceOnlyTheToolsThatAreAlwaysAnnounced` →
    `onlySevenFourNodesAnnounceTheToolsThatAreAlwaysAnnouncedAndFindProcesses`, with and without a
    client home.
  - `ListNodesToolTest`: `aV61NodeIsCliOnlyAndNoCapabilityIsUsableWithoutContactingIt` →
    `aV61NodeIsCliOnlyWithHealthAndProcessesUsableWithoutContactingIt`;
    `aContradictedV61PinLeavesNoCapabilityUsable` → `aContradictedV61PinLeavesOnlyMonitoringUsable`
    (monitoring is judged on the pin, where it is unverified);
    `aV74NodeIsCliOnlyWithItsVersionAndNoUsableCapabilityWithoutContactingIt` →
    `aV74NodeIsCliOnlyWithItsVersionAndHealthAndProcessesUsableWithoutContactingIt`;
    `aV74NodeWarnsThatItsClientIsNotUsedUntilA74CapabilityIsVerified` →
    `aV74NodeCarriesNoWarningAboutItsClient` (the T014 warning ends, FR-035).
  - `ConfigSummaryTest`: the 6.1 summaries (`aV61NodeShowsStartCliOnly…` renamed
    `aV61NodeShowsStartCliOnlyWithHealthAndProcessesAvailableAsUnverified`, the three opt-in cases,
    `aV61NodeWithoutCliHomeIsShownWithItsWarning`) list `available: monitoring, process diagnosis`;
    the 7.4 summaries show the client choice (a 7.4 node chooses its client by version) and no
    "not used until …" suffix (`aV74NodeShowsItsVersionAndStartCliOnlyWithoutAnyAvailableCapability`
    → `aV74NodeShowsItsVersionStartCliOnlyAndHealthAndProcessesAvailableAsUnverified`,
    `theClientOfAV74NodeIsShownAsNotUsedUntilA74CapabilityIsVerified` →
    `theClientOfAV74NodeIsShownWithoutANotUsedSuffix`,
    `theClientIsComparedWithA74PinOnFourPartsAndWithA61PinOnThree`); the T038 case
    `anUnverifiedReadOnACliOnlyVersionIsListedAsAvailable` expects the client choice for 7.4.
  - `ConfigValidatorTest`: `aV74NodeWithoutCliHomeHasNoFindingWhileNoCapabilityIsVerified` →
    `aV74NodeWithoutItsOwnClientIsAWarningNamingTheCliClientsEntry` (also with a
    `defaults.cliHome`, which a 7.4 node does not use).
  - `CliClientProbeTest`: `aSevenFourNodeStartsNoHelpRunWhileNothingIsVerifiedOnIt` →
    `aSevenFourNodeIsProbedWithMinusHAsASixOneNode` (the real 7.4 help: `--execCommand`, no TLS
    option).
  - `MainTest`: `checkConfigProbesA74EntryLocallyAndShowsItsClientAsNotUsedYet` →
    `checkConfigProbesA74EntryLocallyWithTheVersionAndTheOptionProbe` (`-v` and `-h`, options in
    the client part); `checkConfigShowsAV61NodeAsStartCliOnlyWithoutContactingInubit` and
    `checkConfigListsTheUnsupportedReadsAsRefusedOnlyForAnOptedInV61Group`: the available list.
  - `CliCompatibilityTest.every81And61AndAutoNodeKeepsItsStartCliValuesOf080`: 7.4 pins are no
    longer compared with the 0.8.0 rule (FR-029 deviates from it for 7.4).
  - `V74HelpContractTest`: the production matrix instead of its own CLI-only 7.4 matrix.
  - `VersionGateTest`: `theProductionMatrixRefusesEveryCapabilityOf61BeforeAnyDetectionOrProbe` →
    `theProductionMatrixRefusesEveryOtherCapabilityOf61BeforeAnyDetectionOrProbe` (monitoring and
    process diagnosis skipped, the log example on log diagnosis);
    `monitoringAccessOfA61PinIsRefusedWithTheProductionMatrix` →
    `monitoringAccessOfA61PinIsCliWithTheProductionMatrix`, its refusal kept as
    `monitoringAccessOfACliOnlyPinIsRefusedWhenTheMatrixDeclaresItUnsupported` on a pre-flip 6.1
    entry; `a61RefusalNamesTheVersionLineTheCapabilityAndItsNextStep` without monitoring and process
    diagnosis; `a74RefusalKeepsTheNoteMessageAndNamesTheRecognisedReleaseInItsNextStep` and
    `a74PinIsNamedByItsVersionAndItsReleaseIsTakenFromIt` refuse log diagnosis instead of
    monitoring and process diagnosis (texts unchanged); `cliMonitoringWarningsAreEmptyUnlessMonitoringIsUnverifiedOnACliOnlyPin`
    takes its `UNSUPPORTED` case from an inline matrix and asserts the production warning on 7.4.
  - `HealthServiceTest`: `a61NodeIsRefusedBeforeAnyContactWhileAutoAnd81NodesAreReadAsBefore` and
    `aConcrete61PinIsRefusedBeforeAnyContact` run on `refusing61()` (6.1 as before the flip), so the
    refusal before contact of feature 012 stays under test.
  - `TestMatrices` (test helper): Javadoc only, the matrices now repeat the production entries.
