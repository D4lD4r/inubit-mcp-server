# Quickstart validation: Credentials Only from a Credential Store (feature 011)

The default build runs offline: the credential store is the in-memory `FakeCredentialStore`, the
macOS and Linux adapters run against fake `security` / `secret-tool` executables, the Windows
adapter against its `CredApi` seam. No test of the default build contacts a real store, AWS or
INUBIT (Constitution III).

## Commands

```bash
mvn -q -o clean verify
```

```bash
INUBIT_LIVE_STORE=1 mvn -q verify -Plive-store
```

The second command is the opt-in live check of the running system (FR-017); it expects the entries
of `docs/live-tests.md` for the fictitious profile `livetest`. The first build after adding JNA needs
one online dependency resolution.

## Scenarios and their tests

| # | Scenario (spec) | Expected | Test |
|---|---|---|---|
| 1 | US1-1 / SC-001: entries of the start script, no script | node authenticates; no read at startup | `NodeSecretsCredentialStoreTest`, `AdapterGatewayFactoryTest`, live check macOS |
| 2 | US1-2: node entry before group entry | node entry used; group entry when node entry missing or empty; no fallback after a failure | `NodeSecretsCredentialStoreTest` |
| 3 | US1-3: entry missing | `AUTH_FAILED` naming store and both accounts; other nodes work | `NodeSecretsCredentialStoreTest`, `InventoryServiceTest` |
| 4 | US1-4: dialog denied / unanswered | `AUTH_FAILED` access denied, no new read for 30 s; `UNREACHABLE` timeout | `NodeSecretsCredentialStoreTest` (fake clock), `KeychainStoreTest` |
| 5 | R-3: `security -g` parsing | text, `0x` hex (UTF-8), empty; exit codes 44 / 128 / 51 / 36 / 50 mapped first; arguments contain no value; a `password:` line with an unexpected exit never reaches the log | `KeychainStoreTest` (fake executable) |
| 6 | R-4: Windows decoding | UTF-16LE, odd size → invalid, 1168 → not found, 1312 → not available, `LinkageError` → not available | `WindowsCredentialStoreTest` (`CredApi` seam) |
| 7 | R-5 / US2-3: `secret-tool` | found, not found, no D-Bus / not installed → not available, timeout kills the process; environment allowlist (no `INUBIT_*`); stdout never logged | `SecretServiceStoreTest` (fake executable) |
| 8 | FR-008 / R-8: serialised reads, node deadline | one entry read at a time; waiting counts in the node's 60 s | `StoreGateTest`, `NodeSecretsCredentialStoreTest` |
| 9 | FR-008: fan-out allowance only while not held | deadlines as today once held, for both sources | `SecretTimingTest`, `InventoryServiceTest` |
| 10 | US3-1: `type: environment` | configuration error with path, replacement, guide | `ConfigLoaderTest` |
| 11 | US3-2 / FR-003: leftover variables | one warning with names only (all kinds, all nodes incl. AWS); no typo warning | `LeftoverVariablesTest`, `MainTest` |
| 12 | US3-4 / FR-005: `prefix` / `envPrefix` / `source.prefix` | deprecation warning; conflicts are errors; overrides use the profile-wide prefix | `ProfileConfigTest`, `ConfigValidatorTest` |
| 13 | FR-005: name checks | other profile, same `profile.name`, intra-profile, `e2e` node | `ConfigValidatorTest` |
| 14 | R-11: moved value checks | short password / e2e password, trust store with CLI, e2e over `http://` at read time | `NodeSecretsCredentialStoreTest`, `WiringE2eTest` |
| 15 | US4 / FR-011: `--check-config` | lines, outcomes, exit codes; never a value | `ConfigSummaryTest`, `EntryCheckTest`, `MainTest` |
| 16 | US5 / FR-009: rotation | changed → next call uses it; unchanged → 60 s pause; ≤ 2 failed logins / 60 s | `CredentialGuardTest`, `AdapterGatewayFactoryTest` |
| 17 | FR-010 / R-12: texts | store names, accounts, next steps; no "restart the MCP client" | `SecretLabelsTest`, `CliOutputClassifierTest`, `HealthServiceTest` |
| 18 | SC-002: no environment credentials | every derived variable set; none reaches a request, stdin or result | `NoEnvironmentCredentialsTest` |
| 19 | SC-003: no leak | values absent from results, logs, errors, audit, arguments, summary | `NoSecretLeakTest` |
| 20 | US6 / FR-014: live tests | `LiveTarget` resolves like the server for both sources | `LiveTargetTest` |
| 21 | Boundary | JNA only in `adapter/os` | `PackageBoundaryTest` |
| 22 | R-7: single-node wait per source | a call whose fake store answers after 30 s succeeds (no 10 s give-up) | `AdapterGatewayFactoryTest`, `WiringE2eTest` |
| 23 | R-8: re-read inside a fan-out | branch `TIMEOUT`, re-read finishes, next call uses its result | `CredentialGuardTest`, `InventoryServiceTest` |
| 24 | SC-006: first-call overhead | 25 ms per entry, ≤ 6 reads → ≤ 200 ms extra; later calls unchanged | `NodeSecretsCredentialStoreTest` (timed, fake store) |
| 25 | FR-013: `secret.fetch` record | `source=credentialStore`, `store`, `entries`, `durationMs`, `outcome`, `changed` on re-read; INFO/WARN; none within back-off; never a value | `NodeSecretsCredentialStoreTest` (log capture) |
| 26 | R-17: audit account before first read | "(credential store <account>)", then the read username | `WiringAuditTest` |
| 27 | R-13: `--check-config` reads each account once | a group entry shared by N nodes read once | `EntryCheckTest` |

## Validation record

2026-10-31, task T024 (offline, in a Linux container running as root, `LC_ALL=C.UTF-8`):

- `mvn -B -o clean verify`: 3574 tests, 3 failures, 0 errors, 5 skipped. The three failures are
  environment-only and fail on the base of this feature as well:
  `ConfigValidatorTest.aWorkspaceThatIsNotWritableIsUnusable` and
  `CliResourcesTest.theSweepLeavesDirectoriesOfOtherOwnersAlone` (root ignores file permissions
  and ownership), `InubitHttpClientTest.unknownHostIsUnreachable` (the proxy of the container
  resolves every host name). Without `LC_ALL=C.UTF-8` the git and deployment tests fail on file
  names with umlauts (POSIX locale of the container).
- `mvn -o -Dtest=NoCustomerIdentifiersTest test`: 20 tests, 1 skipped — the private identifier
  lists are not present here. **To do by the person with the lists:** run it locally (it must
  run, not skip).
- `System.getenv` in `src/main/java` (6 hits), each allowed by research R-10:
  - `ConfigLoader` (system loader): `INUBIT_MCP_CONFIG`, `INUBIT_MCP_PROFILE`, `APPDATA` (where
    the configuration file is);
  - `ConfigValidator` (system validator): the `JAVA_HOME` fallback of StartCLI and the names of
    leftover credential variables (`LeftoverVariables`, set or not, never the value);
  - `Launcher.Context.system` (2 hits): the context's environment (as above) and the environment
    the credential store adapters copy their allowlists from (`HOME`, `USER`, `LANG`; for
    `secret-tool` also the session bus and display variables and `PATH`);
  - `Launcher.serve` and `Wiring` (default constructor): the environment the StartCLI and git
    child processes copy their allowlists from (no `INUBIT_*` variable is passed on); the AWS SDK
    reads its own variables itself.
  No code path reads an INUBIT credential value from the environment
  (`NoEnvironmentCredentialsTest`).
- **Pending: T020** — the opt-in live checks against the real credential store
  (`INUBIT_LIVE_STORE=1 mvn -q verify -Plive-store`, docs/live-tests.md) on macOS (required before
  the release) and, where available, Windows and Linux, plus the manual access-denied steps and the
  recording of the exit codes and texts of `security` / `secret-tool` (research R-3, R-5).
