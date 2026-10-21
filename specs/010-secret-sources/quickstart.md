# Quickstart validation: Configurable Secret Sources (feature 010)

All scenarios run offline: Parameter Store is a test double of the `ParameterStore` port (and, for
the adapter itself, a local stub HTTP server answering the SSM JSON protocol). No test contacts AWS
or INUBIT (Constitution III).

## Commands

```bash
mvn -q -o clean verify
```

```bash
mvn -q -o -Dtest=NoCustomerIdentifiersTest test
```

The first build after adding the AWS SDK needs one online dependency resolution from Maven Central
(`mvn -q dependency:resolve`); afterwards the offline build works.

## Scenarios and their tests

| # | Scenario (spec) | Expected | Test |
|---|---|---|---|
| 1 | US1-1: existing profiles unchanged | same variables, errors, `--check-config` output; all existing tests green | full suite, `CredentialResolverTest`, `ConfigSummaryTest` |
| 2 | US1-2: explicit environment source | `--check-config` shows `secrets: environment` and the variable names | `ConfigSummaryTest` |
| 3 | US1-3: `envPrefix` vs profile environment `prefix` | configuration error naming both | `SecretSourceConfigTest` / `ConfigValidatorTest` |
| 4 | US2-1: AWS node authenticates with stored values | values used, nothing leaked | `NodeSecretsTest`, `AdapterGatewayFactoryTest`, `NoSecretLeakTest` |
| 5 | US2-2: missing / denied parameter | `AUTH_FAILED` naming node, parameter, kind; no value | `NodeSecretsTest` |
| 6 | US2-3: `String` accepted with warning, `StringList` refused | warning in log and `--check-config`; `INVALID_VALUE` | `NodeSecretsTest`, `ConfigSummaryTest` |
| 7 | US2-4: AWS profile / default chain; unknown profile | provider chosen; unknown profile = first-fetch error | `SsmParameterStoreTest`, `MainProcessTest` (default chain) |
| 8 | FR-006a / SC-006: first use only | startup without AWS; untouched nodes never fetched; configuration-only paths fetch nothing | `NodeSecretsTest`, `AdapterGatewayFactoryTest`, `MainTest` |
| 9 | FR-011: 10 s timeout, 30 s back-off, single flight | one fetch for concurrent calls; immediate failure within 30 s | `NodeSecretsTest` (fake clock) |
| 10 | R-8: fan-out deadline with a slow store | partial result, not `TIMEOUT` | `InventoryServiceTest`, `DiagnosisServiceTest` |
| 11 | R-9: `get_health` budget | pending node → failed report `TIMEOUT` "credentials not yet available", budget kept, next call uses the secrets | `HealthServiceTest`, `GetHealthToolTest` |
| 11a | R-15: TLS and rejection texts per source | trust store / `AUTH_FAILED` texts name the parameter for AWS nodes | `SslContextsTest`, `SecretLabelsTest`, `CredentialGuardTest`, `CliOutputClassifierTest` |
| 11b | R-16: audited account | AWS node audit shows the fetched username (parameter name before the first fetch) | `WiringAuditTest` |
| 11c | e2e SOAP trust store password from Parameter Store | TLS works for `run_e2e_test` on an AWS node | `WiringE2eTest` |
| 12 | US3: `--check-config` with/without AWS access | exit 1 on missing/denied/invalid/unknown profile; exit 0 + warning when unavailable | `MainTest`, `ConfigSummaryTest` |
| 13 | US4 / FR-010: rotation | changed → next call uses new value; unchanged → 60 s pause; max two failed logins per 60 s | `CredentialGuardTest`, `AdapterGatewayFactoryTest` |
| 14 | R-13: env checks only for env nodes | no shared-variable error between two AWS profiles | `ConfigValidatorTest` |
| 15 | R-4: pasted secret in `parameters` | error naming the key path, value not echoed | `ConfigLoaderTest` |
| 16 | R-2: AWS SDK only in `adapter/aws` | boundary rule green | `PackageBoundaryTest` |
| 17 | SC-005: added latency | stub answering in ≤ 1 s → first call ≤ 2 s slower; later calls ≤ 50 ms | `NodeSecretsTest` (timing with fake store) |

## Optional live check

Only with the person's explicit approval and a real Parameter Store: `--check-config` against a
profile with an AWS node (read-only). Not part of the automated suite.

## Validation record (T019)

**2026-10-21, branch `010-secret-sources`, commit `a5579b0` plus the uncommitted T018 review
carry-over fixes to `docs/tools.md`: offline validation done. The optional live check against a
real Parameter Store was not run (it needs the person's explicit approval).**

- `mvn -q -o clean verify`: exit 0 on the first run (no rerun). 231 test classes, 3448 tests,
  0 failures, 0 errors, 3 skipped. The 3 skipped tests are the opt-in corpus tests
  (`ArchiveCorpusTest`, `StructureCorpusTest`, `XsltCorpusTest`; corpus variables not set).
- `NoCustomerIdentifiersTest` ran (not skipped) inside the build with the local identifier lists
  (denylist, neutralize map, synthetic map and allowlist all present): 20 tests, 0 failures,
  0 skipped; 1274 files scanned, no finding.
- `mvn -q -o -Dtest=NoCustomerIdentifiersTest test` on its own: exit 0; 20 tests, 0 failures,
  0 skipped; 1276 files scanned (the 1274 git files plus the two jars the build left in
  `target/`), no finding.
- Table corrections: row 7 adds `MainProcessTest` (the real `SsmParameterStore` without
  `awsProfile`, i.e. the default chain, classifies missing AWS credentials without a request;
  `SsmParameterStoreTest` covers named and unknown profiles only); row 11a names
  `SecretLabelsTest`, `CredentialGuardTest` and `CliOutputClassifierTest` for the rejection
  texts instead of `AdapterGatewayFactoryTest` (which checks the fetch-failure texts, rows 4 and
  5); row 11b names `WiringAuditTest` only (`ProcessControlServiceTest` has no Parameter Store
  test).
- Scenario coverage: every class named in the table exists and ran green in the build (tests per
  class): `CredentialResolverTest` 36, `ConfigSummaryTest` 32, `SecretSourceConfigTest` 44,
  `ConfigValidatorTest` 128, `NodeSecretsTest` 50, `AdapterGatewayFactoryTest` 55,
  `NoSecretLeakTest` 2, `SsmParameterStoreTest` 16, `MainProcessTest` 4, `MainTest` 41,
  `InventoryServiceTest` 39, `DiagnosisServiceTest` 28, `HealthServiceTest` 46,
  `GetHealthToolTest` 13, `SslContextsTest` 12, `SecretLabelsTest` 5, `CredentialGuardTest` 12,
  `CliOutputClassifierTest` 41, `WiringAuditTest` 4, `WiringE2eTest` 4, `ConfigLoaderTest` 149,
  `PackageBoundaryTest` 18. No scenario is without automated coverage. Row 7 is covered only in
  part: no test signs a request with credentials from the default chain (it would need real or
  process-wide AWS credentials); a named profile signing with its own credentials is tested.
  Row 1 is also covered by the full suite being green.
- Live (optional) `--check-config` against a real Parameter Store: not run.
