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
| 7 | US2-4: AWS profile / default chain; unknown profile | provider chosen; unknown profile = first-fetch error | `SsmParameterStoreTest` |
| 8 | FR-006a / SC-006: first use only | startup without AWS; untouched nodes never fetched; configuration-only paths fetch nothing | `NodeSecretsTest`, `AdapterGatewayFactoryTest`, `MainTest` |
| 9 | FR-011: 10 s timeout, 30 s back-off, single flight | one fetch for concurrent calls; immediate failure within 30 s | `NodeSecretsTest` (fake clock) |
| 10 | R-8: fan-out deadline with a slow store | partial result, not `TIMEOUT` | `InventoryServiceTest`, `DiagnosisServiceTest` |
| 11 | R-9: `get_health` budget | pending node → failed report `TIMEOUT` "credentials not yet available", budget kept, next call uses the secrets | `HealthServiceTest`, `GetHealthToolTest` |
| 11a | R-15: TLS and rejection texts per source | trust store / `AUTH_FAILED` texts name the parameter for AWS nodes | `SslContextsTest`, `AdapterGatewayFactoryTest` |
| 11b | R-16: audited account | AWS node audit shows the fetched username (parameter name before the first fetch) | `ProcessControlServiceTest` / `WiringAuditTest` |
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
