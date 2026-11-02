# Configuration delta (feature 011)

Against feature 010 ([its delta](../../010-secret-sources/contracts/configuration-delta.md)).
Research R-6, R-10, R-11.

## `credentials` section

| Level | Keys allowed |
|---|---|
| top level | `prefix` (new), `envPrefix` (deprecated: warning "credentials.envPrefix is deprecated; rename it to credentials.prefix"), `source` |
| group | `source`, `parameters` |
| node | `source`, `parameters` |

`credentials.prefix` and `credentials.envPrefix` with different values: error "credentials.prefix
and credentials.envPrefix differ; keep only credentials.prefix".

## `credentials.source`

| `type` | Keys | Notes |
|---|---|---|
| `credentialStore` (new, default) | `type`, `prefix` (optional) | the store of the running system |
| `awsParameterStore` | `type`, `region`, `awsProfile` | unchanged |
| `environment` (removed) | — | error: "Invalid value at <path>.type in <file>: credentials.source.type environment was removed in 0.7.0; credentials come from the credential store of this system (type credentialStore, the default) or AWS Parameter Store (type awsParameterStore); see docs/migration-environment-variables.md" |

A top-level `source.prefix` applies to the nodes inheriting the top-level source and must equal the
profile-wide prefix if one is set (error "credentials.source.prefix and credentials.prefix differ;
set only one of them or make them equal"). Overrides without their own `prefix` use the profile-wide
prefix, else `INUBIT_<PROFILE>`.

## Entry identity

Service `inubit-mcp`, account `<PREFIX>_<GROUP>[_<NODE>]_<KIND>`, kinds `USERNAME`, `PASSWORD`,
`TRUSTSTORE_PASSWORD`, `E2E_USERNAME`, `E2E_PASSWORD`; node entry before group entry by value.

| System | Entry |
|---|---|
| macOS Keychain | generic password, service `inubit-mcp`, account `<ACCOUNT>` |
| Windows Credential Manager | generic credential, target name `inubit-mcp:<ACCOUNT>` (user name ignored) |
| Linux Secret Service | item with attributes `service=inubit-mcp`, `account=<ACCOUNT>` |

## Startup findings

| Finding | Severity |
|---|---|
| derived entry names shared with another profile in the default configuration directory | error (unchanged rule, now on entry names of credential store nodes) |
| same `profile.name` in another file | warning (unchanged) |
| two groups/nodes of this profile derive the same name | error (unchanged) |
| node named `e2e` where its group allows e2e tests | error (unchanged) |
| environment variables with derived names set | warning: "Environment variables are no longer read for INUBIT credentials (since 0.7.0): <names>; move them to the credential store or Parameter Store (docs/migration-environment-variables.md)" |
| missing username/password | no longer a startup error (first-use error; `--check-config` error) |
| short `_PASSWORD` / `_E2E_PASSWORD`, trust store password with CLI, e2e entries over `http://` | no longer startup errors (read time and `--check-config`, R-11) |
| `<PREFIX>_*_PASSWORD` matching no node | removed |

## `--check-config`

`Credential variables: …` line → `Credential store entries: service inubit-mcp, account
<PREFIX>_<GROUP>[_<NODE>]_<KIND> (<store>)`. Every node line: `secrets: credentialStore (<store>)`,
then per kind `username ← <found name> ok` / `password ← <node name> | <group name> not found` / …;
outcomes `ok`, `not found`, `invalid value`, `access denied`, `not checked`. Exit 1 on not found
(required), invalid value, access denied, trust store password with CLI, e2e entries over `http://`;
exit 0 with one warning per store when it cannot be asked (not available, locked without prompt,
timeout).
