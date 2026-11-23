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

## Mandate B — recording session (Windows, by hand, after approval)

Preconditions (spec): Windows machine with the 7.4 Workbench client and its JDK; CLI login to a
**non-production** 7.4.0.90 node; the local neutralize map; the owner's approval in the chat.

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
6. Live check on Windows (MCP server with a `V7_4` node and an `AUTO` node; macOS and Linux are not
   live-checked, spec FR-031):
   record here the outcome per step, including the detection failure code of the `AUTO` node.

## Validation record

| Date | Step | Platform | Outcome |
|---|---|---|---|
| 2026-11-19 | Mandate A: `mvn -q -o clean verify` at `b17b853f` (T001–T023) | macOS | exit 0; 11,263 tests, 0 failures, 0 errors, 62 skipped |
| 2026-11-19 | `VersionLineRegressionTest`: unchanged since T003 (`66f2f420`), run alone | macOS | green (38 tests) |
| 2026-11-19 | `NoCustomerIdentifiersTest` with the local identifier lists, in the full suite and alone | macOS | green (20 tests, none skipped) |
| 2026-11-19 | Mandate A: `mvn -q -o clean verify` at `23a97fd2` (T001–T025 and clean-up) | macOS | exit 0; 11,264 tests, 0 failures, 0 errors, 62 skipped |
| 2026-11-19 | CI on [PR #104](https://github.com/D4lD4r/inubit-mcp-server/pull/104) at `23a97fd2`: [build](https://github.com/D4lD4r/inubit-mcp-server/actions/runs/44573778803/job/135735630386) and [windows](https://github.com/D4lD4r/inubit-mcp-server/actions/runs/44573778803/job/135735629782) (run 44573778803), CodeQL | Linux, Windows | all green, first attempt, no rerun |

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
