# Configuration delta (feature 010)

Additive. A profile without any new key behaves exactly as before (FR-002, SC-001). Research R-3
to R-6, R-12, R-13.

## `credentials` section

| Level | Keys | Notes |
|---|---|---|
| top | `envPrefix` (existing), `source` (new) | `envPrefix` stays the default prefix of every environment source without its own `prefix` |
| group | `source`, `parameters` (new) | the section is new at this level |
| node | `source`, `parameters` (new) | the section is new at this level |

### `source`

```yaml
source:
  type: environment            # or awsParameterStore
  prefix: INUBIT_ACME          # environment only, optional
---
source:
  type: awsParameterStore
  region: eu-central-1         # required
  awsProfile: inubit           # optional; a profile of the local AWS configuration
```

- Effective source per node: node → group → profile → `environment` (default).
- An override replaces the whole source including its settings.
- No AWS access keys or other secrets anywhere in the profile (unchanged rule; keys containing
  `password` are still refused outside `credentials.parameters`).

### `parameters` (AWS source only)

```yaml
parameters:
  username: /inubit/prod/username            # required for every AWS node (node or group level)
  password: /inubit/prod/password            # required
  trustStorePassword: /inubit/prod/ts-pass   # optional
  e2eUsername: /inubit/prod/e2e-user         # optional, together with e2ePassword
  e2ePassword: /inubit/prod/e2e-pass
```

- Full hierarchical names: `^/[A-Za-z0-9_.\-/]{1,1010}$`, no `//`, no trailing `/`; no version or
  label selectors, no ARNs.
- A node-level name wins for its kind; the group's names apply only to nodes without their own
  `source`.
- A value that is not a valid name is an error naming the key path, never the value.

## Startup errors and warnings (also in `--check-config`)

| Case | Result |
|---|---|
| AWS node without `username` or `password` name | error: `<node>: no Parameter Store name for the password (set credentials.parameters.password on the {node} or its {group})` |
| only one of `e2eUsername` / `e2ePassword` | error naming the node and the missing key |
| profile-level `environment` source with `prefix` ≠ `credentials.envPrefix` | error naming both values' keys |
| invalid `region`, `awsProfile` or parameter name | error naming the key path |
| `trustStorePassword` name on a node with a StartCLI installation | error (StartCLI could only get it as a process argument), as today for the variable |
| `trustStorePassword` name without `tls.trustStore` | warning, as today for the variable |
| AWS node with `e2eUsername`/`e2ePassword` names and an `http://` `e2e.soap.baseUrl` (end-to-end tests not forbidden) | error: the credentials would travel in clear text (names the parameters), as today for the variables |
| `parameters` on a node or group whose effective source is `environment` (incl. a node overriding an AWS group with `environment`) | warning: ignored |
| unknown `type` or unknown key inside `credentials` | error |

The cross-profile shared-variable check, the collision check and the unmatched
`<PREFIX>_*_PASSWORD` warning consider only environment nodes.

## `--check-config` output

Profiles without any `credentials.source` setting keep today's output unchanged (SC-001). As soon
as `source` is used anywhere in the profile, every node line shows its source, after `cli:` and the
009 parts:

```
  dev/node1   …, secrets: environment, username ← INUBIT_ACME_DEV_USERNAME, password ← INUBIT_ACME_DEV_PASSWORD
  prod/node1  …, secrets: awsParameterStore (eu-west-1, profile prod-account), username ← /inubit/prod/username ok, password ← /inubit/prod/node1/password ok
```

- AWS parameters are read and validated, never printed; per name `ok` or the failure kind
  (`not found`, `access denied`, `invalid value`, `not encrypted` warning).
- Exit 1: a configured parameter missing, denied or invalid; an unknown AWS profile.
- Exit unchanged with a warning: no AWS credentials resolvable (incl. an expired login), store
  unreachable or timed out ("resolvability not checked"), `String` parameters, ignored names.
- The environment-variable details stay as today for environment nodes.
