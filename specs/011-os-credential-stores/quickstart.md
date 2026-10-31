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
| 5 | R-3: `security -g` parsing | text, `0x` hex (UTF-8), empty, not found (44), denied, no GUI session; arguments contain no value | `KeychainStoreTest` (fake executable) |
| 6 | R-4: Windows decoding | UTF-16LE, odd size → invalid, 1168 → not found, 1312 → not available | `WindowsCredentialStoreTest` (`CredApi` seam) |
| 7 | R-5 / US2-3: `secret-tool` | found, not found, no D-Bus / not installed → not available, timeout kills the process | `SecretServiceStoreTest` (fake executable) |
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
