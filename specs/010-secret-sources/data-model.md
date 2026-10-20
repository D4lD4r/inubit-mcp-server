# Data Model: Configurable Secret Sources (feature 010)

All types are in memory. Secret values live only in `infra.Secret` (never in `toString`, logs or
results). Packages follow research R-2.

## SecretSourceConfig (config, new)

Sealed: `Environment(Optional<String> prefix)` | `AwsParameterStore(String region,
Optional<String> awsProfile)`.

| Field | Rule |
|---|---|
| `type` | `environment` or `awsParameterStore` (YAML spelling, case-sensitive) |
| `prefix` (environment) | optional; `ProfileInfo.PREFIX_PATTERN` (`^[A-Z][A-Z0-9_]{0,63}$`) |
| `region` (aws) | required; `^[a-z]{2}(-[a-z]+)+-\d$` (e.g. `eu-central-1`) |
| `awsProfile` (aws) | optional; `^[A-Za-z0-9_.+=,@-]{1,64}$`; existence checked at first fetch and by `--check-config` |

## ParameterNames (config, new)

Optional full names per kind: `username`, `password`, `trustStorePassword`, `e2eUsername`,
`e2ePassword`. Pattern `^/[A-Za-z0-9_.\-/]{1,1010}$`, no `//`, no trailing `/` (R-5).

## CredentialsConfig (config, extended)

Top level: `envPrefix` (unchanged), `source` (new, optional). Group and node level (new):
`source` (optional override), `parameters` (optional `ParameterNames`). `envPrefix` stays
top-level only.

## SecretPlan (config, new; per node, computed at load time)

| Field | Content |
|---|---|
| `node` | NodeId |
| `source` | effective `SecretSourceConfig` (R-6) |
| `variables` | `CredentialVariables` (environment source only) |
| `names` | resolved `ParameterNames` (aws only): node name per kind, else group name if the node has no own source |
| `origin` | where the effective source was declared (`node`, `group`, `profile`, `default`) — for `--check-config` |

Validation (startup configuration errors): aws without `username` or `password` name; only one of
`e2eUsername`/`e2ePassword`; profile-level `environment.prefix` ≠ `credentials.envPrefix`;
invalid region/profile/name pattern; e2e parameter names with an `http://` SOAP URL while
end-to-end tests are not forbidden (clear-text rule); a `trustStorePassword` name on a node with a
StartCLI installation. Warnings: a `trustStorePassword` name without `tls.trustStore`; parameter
names on a node or group whose effective source is environment.

## NodeCredentials (config, extended)

Unchanged record for environment nodes (filled at startup). For AWS nodes `CredentialResolver`
returns `NodeCredentials.deferred(node)` (all values empty, `deferred() == true`); the values come
from `NodeSecrets`.

## StoreLocation (config, new)

`region` + `Optional<String> awsProfile`; key of the cached SDK client.

## ParameterStore port (config, new) and Fetched

`Fetched get(StoreLocation location, List<String> names, Duration timeout)`.
`Fetched` = per name `Value(Secret value, ParameterType type)` or `Failure(FailureKind kind,
String logDetail)`, plus a whole-call failure (`NO_CREDENTIALS`, `UNKNOWN_PROFILE`,
`UNREACHABLE`). `ParameterType` = `SECURE_STRING`, `STRING`, `STRING_LIST`. `FailureKind` =
`NOT_FOUND`, `ACCESS_DENIED`, `UNREACHABLE`, `INVALID_VALUE`, `UNKNOWN_PROFILE`, `NO_CREDENTIALS`.
`logDetail` is the raw store message, used only for the WARN log after scrubbing.

## NodeSecrets (adapter, new; one per node, held by the gateway Slot)

| State | Meaning |
|---|---|
| `held` | Optional `NodeCredentials` (username, password, trust store password, Basic token) |
| `inFlight` | Optional shared fetch (`CompletableFuture`) |
| `failure` | Optional (`ToolError`, Instant) — calls within 30 s fail at once with it |

Operations: `current(Duration bound)` → `NodeCredentials` or `ToolErrorException`;
`reread()` → `Reread(CHANGED | UNCHANGED | FAILED)` (username or password differ = changed);
Each fetch runs on its own virtual thread, so fetches of different nodes never wait for each
other. (The fan-out allowance is owned by `SecretTiming` only.) Environment nodes: `held` set at
startup, `reread()` not supported. Separate cell `e2e` with the same rules for the
end-to-end credentials.

## CredentialGuard (adapter, extended)

States: `UNCONFIRMED` (serialized attempts), `CONFIRMED` (parallel), `PAUSED(until)`, new
`GRACE` (one attempt allowed after a changed re-read). Transitions on `rejected()` with a re-read
hook: `UNCONFIRMED/CONFIRMED` → re-read → `CHANGED` → `GRACE` (slot rebuilds client/ports) or
`UNCHANGED/FAILED` → `PAUSED(now+60 s)`; `GRACE` + `rejected()` → `PAUSED(now+60 s)` (no re-read).
Without a hook: as today.

## SecretLabels (config, new; per node)

Message names per kind (R-15): environment `<NODE_VAR> (or <GROUP_VAR>)`; AWS `Parameter Store
<name> (<region>[, profile <p>])`. `fixCredentials()`: source-specific next step.

## CredentialRules (config, new)

Shared value checks (R-17): `isSafeUsername`, `MIN_SCRUBBED_LENGTH` = 4, non-empty, parameter type
(`STRING_LIST` refused, `STRING` warned).

## CredentialState (domain/model, new) and `GatewayFactory.prepareCredentials` (domain/port)

`READY` | `PENDING`. `default CredentialState prepareCredentials(NodeId, Duration bound)` returns
`READY`; `AdapterGatewayFactory` overrides it (R-9). A failed fetch throws its `ToolErrorException`.

## WritePolicy account (domain/model, unchanged shape)

`Optional<String> account` stays; `Wiring` builds the policy and the `accounts` entry per call from
the node's current credentials (R-16).

## SecretTiming (application, new)

`Duration allowance(NodeId)` — extra per-node fan-out time for a first-use fetch (R-8); built in
`Wiring` from the `SecretPlan`s. Pure value, no AWS type.
