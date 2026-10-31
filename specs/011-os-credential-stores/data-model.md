# Data Model: Credentials Only from a Credential Store (feature 011)

All types are in memory. Secret values live only in `infra.Secret` (never in `toString`, logs or
results). Packages follow research R-2. Types of feature 010 not listed here are unchanged.

## SecretSourceConfig (config, changed)

Sealed: `CredentialStore(Optional<String> prefix)` | `AwsParameterStore(String region,
Optional<String> awsProfile)` (unchanged). `Environment` is removed.

| Field | Rule |
|---|---|
| `type` | `credentialStore` or `awsParameterStore`; `environment` → configuration error with the removal message (R-6); anything else → error listing both types |
| `prefix` (credentialStore) | optional; `ProfileInfo.PREFIX_PATTERN` (`^[A-Z][A-Z0-9_]{0,63}$`) |

## CredentialsConfig (config, extended)

Top level: `prefix` (new), `envPrefix` (deprecated alias, warning), `source`. Group and node level:
`source`, `parameters` (unchanged). `prefix`/`envPrefix` top-level only.

`ProfileConfig.credentialPrefix()`: `prefix`, else `envPrefix`, else `INUBIT_<PROFILE>` normalised.
Errors: `prefix` ≠ `envPrefix`; top-level `source.prefix` ≠ the profile-wide prefix when one is set.

## CredentialNames (config, renamed from CredentialVariables)

`(String prefix, NodeId node)`; `nodeName(kind)` = `<PREFIX>_<GROUP>_<NODE>_<KIND>`, `groupName(kind)`
= `<PREFIX>_<GROUP>_<KIND>`, `candidates(kind)` = [node, group]. Kinds: `USERNAME`, `PASSWORD`,
`TRUSTSTORE_PASSWORD`, `E2E_USERNAME`, `E2E_PASSWORD`. Normalisation unchanged.

## SecretPlan (config, changed)

Per node: effective `source` (node → group → profile → `CredentialStore(empty)`), `origin`,
`names` (`CredentialNames` for credential store nodes, `ParameterNames` for AWS nodes).

## CredentialStore port (config, new)

| Member | Meaning |
|---|---|
| `SERVICE = "inubit-mcp"` | constant service (Out of Scope: not configurable) |
| `StoreKind kind()` | `KEYCHAIN`, `CREDENTIAL_MANAGER`, `SECRET_SERVICE`, `NONE`; display names "macOS Keychain", "Windows Credential Manager", "Secret Service", "no credential store" |
| `Lookup read(String account, Duration timeout)` | never throws for store failures |

`Lookup`: `Found(String value)` | `NotFound` | `Failed(FailureKind kind, String detail)`; `detail` is
raw store text for the scrubbed log only.

## FailureKind (config, extended)

Adds `STORE_NOT_AVAILABLE`, `TIMEOUT`. Mapping (R-12): `NOT_FOUND`, `ACCESS_DENIED`,
`INVALID_VALUE`, `STORE_NOT_AVAILABLE`, `UNKNOWN_PROFILE`, `NO_CREDENTIALS` → `AUTH_FAILED`;
`UNREACHABLE`, `TIMEOUT` → `UNREACHABLE`.

## StoreGate (adapter/os, new)

Process-wide fair lock; `read(deadline, action)` waits for the lock until `deadline` (then `TIMEOUT`)
and runs one entry read at a time (R-8).

## NodeSecrets (adapter, changed)

Factories `credentialStore(SecretPlan, CredentialStore, SecretScrubber, Clock)` and `aws(...)`
(unchanged); the environment constructor is removed. State per cell unchanged (single flight, 30 s
back-off, settle grace). `held()` returns the held credentials without reading (used by
`SecretTiming`). Timeout: `CREDENTIAL_STORE_TIMEOUT` = 60 s or `STORE_TIMEOUT` = 10 s by source.

Main read (credential store): per kind `USERNAME`, `PASSWORD`, and `TRUSTSTORE_PASSWORD` if
`tls.trustStore` is set — node entry; on `NotFound` or empty, group entry; `Failed` or invalid stops.
Missing `USERNAME`/`PASSWORD` → `AUTH_FAILED` naming both entries. Trust store password with CLI →
`AUTH_FAILED` (R-11).

E2E read: `E2E_USERNAME`, `E2E_PASSWORD` the same way; none → no basic authentication; exactly one →
`AUTH_FAILED` naming the missing entry; found together with an `http://` `e2e.soap.baseUrl` while e2e
tests are allowed → refused before sending.

## NodeCredentials (config, changed)

Only the deferred form remains at startup; values exist only inside `NodeSecrets` after a read.

## SecretTiming (application, changed)

`(Map<NodeId, Duration> fetchWait, Predicate<NodeId> held)`; `allowance(node)` = `fetchWait + GRACE`
while `!held(node)`, else zero.

## LeftoverVariables (config, new)

`warning(Map<String,String> environment, ProfileConfig)`: the set names of
`ProfileConfig.credentialNameSet()` (every kind of every node, under the prefix the node would use as
a credential store node), one warning, names only, or none.

## EntryCheck (config, new)

`run(Collection<SecretPlan>, CredentialStore)` → per node and kind: the found name or both names,
outcome `ok | not found | invalid value | access denied | not checked`; findings per FR-011 / R-13.
