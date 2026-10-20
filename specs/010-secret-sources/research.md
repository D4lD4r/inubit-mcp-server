# Research: Configurable Secret Sources (feature 010)

Decisions R-1 … R-17. Each: decision, rationale, alternatives considered.

## R-1 AWS access through the AWS SDK for Java v2

- **Decision**: use the AWS SDK for Java v2 (BOM `2.54.3`): `ssm` for `GetParameters`, `sso` and
  `ssooidc` (SSO-based profiles of the shared AWS configuration), `sts` (web-identity and
  assume-role profiles) and `url-connection-client` as the only HTTP client. Exclude
  `netty-nio-client`, `apache-client` and `apache5-client` from every SDK artifact; the SDK client is
  built with `UrlConnectionHttpClient` explicitly. The credential chain is
  `DefaultCredentialsProvider` (environment, system properties, web identity, shared configuration
  and SSO, container, instance role), or `ProfileCredentialsProvider` when the source names an AWS
  profile (FR-005).
- **Rationale**: the standard credential chain (FR-005) — shared config and credentials files, SSO
  token cache, container and instance metadata, web identity — plus SigV4 signing is security code
  that must behave exactly like every other AWS tool on the machine; re-implementing it is a larger
  risk than one well-known dependency (Constitution tech constraints: each runtime dependency
  justified). `url-connection-client` keeps the added size small (no Netty, no Apache HttpClient).
  The BOM version is in the local Maven repository already; `url-connection-client`, `sso` and
  `ssooidc` of that version need one online resolution from Maven Central (not INUBIT) before the
  offline build works again.
- **Alternatives**: hand-written SigV4 over `java.net.http` with environment/profile keys only —
  rejected (no SSO, no container/instance roles, security-sensitive); AWS CLI as a subprocess
  (`aws ssm get-parameters`) — rejected (extra installation, process and output parsing, secrets on
  stdout of a child process); AWS CRT client — rejected (native code, size).

## R-2 Where the code lives (layers, PackageBoundaryTest)

- **Decision**:
  - `config`: `SecretSourceConfig` (sealed: `Environment`, `AwsParameterStore`), `ParameterNames`,
    `SecretPlan` (per node: effective source, variable names or parameter names), the port
    `ParameterStore` (`get(StoreLocation, List<String> names, Duration timeout)` → `Fetched`, no
    SDK type), loading/validation/summary. `config` already owns `CredentialResolver`,
    `NodeCredentials` and `infra.Secret` use.
  - `adapter/aws` (new, `package-info.java`): `SsmParameterStore implements ParameterStore`, the
    only package that may use `software.amazon.awssdk`.
  - `adapter` (root): `NodeSecrets` (per-node lazy credentials: shared fetch, 30 s back-off, re-read)
    and the extended `CredentialGuard`.
  - No secret value or secret type crosses into `domain`/`application`. The only domain additions
    are the value-free `CredentialState` enum and `GatewayFactory.prepareCredentials` (R-9); the
    application changes are the fan-out deadlines (R-8), `HealthService` (R-9) and `SecretTiming`.
  - `PackageBoundaryTest`: new rule "only `adapter/aws` uses `software/amazon/`", `adapter/aws`
    must not reach `mcp`/`application`, and `adapter/aws` is added to the package-existence list.
- **Rationale**: keeps AWS behind one adapter (Constitution V), keeps secrets out of the layers
  that build tool results (Constitution II).
- **Alternatives**: a domain port `SecretSource` — rejected (`domain` may not reference
  `infra.Secret`; adding a domain secret type only for this would spread secrets inward).

## R-3 Profile YAML shape

- **Decision**:

  ```yaml
  credentials:                      # top level (exists)
    envPrefix: INUBIT_ACME          # unchanged; default prefix of every environment source
    source:                         # new, optional; default: environment
      type: awsParameterStore       # environment | awsParameterStore
      region: eu-central-1          # awsParameterStore: required
      awsProfile: inubit            # awsParameterStore: optional
  groups:
    - name: prod
      credentials:                  # new on groups and nodes
        source: { type: awsParameterStore, region: eu-west-1, awsProfile: prod-account }
        parameters:
          username: /inubit/prod/username
          password: /inubit/prod/password
          trustStorePassword: /inubit/prod/truststore-password
          e2eUsername: /inubit/prod/e2e-username
          e2ePassword: /inubit/prod/e2e-password
      nodes:
        - name: node1
          credentials:
            parameters: { password: /inubit/prod/node1/password }
  ```

  `environment` sources take an optional `prefix`; without it `credentials.envPrefix` or the
  default `INUBIT_<PROFILE>` applies (FR-002/FR-003).
- **Rationale**: one `credentials` section name at every level, matching the existing top-level
  section; kinds named like the existing variable kinds.
- **Alternatives**: a separate top-level `secretSources` map referenced by name — rejected for now
  (more indirection than two sources need); keys such as `passwordParameter` — rejected (still hit
  the loader's "password" rule, see R-4).

## R-4 The loader's credential-key stripping

- **Decision**: `ConfigLoader` keeps stripping `username`, `credentials` and any key containing
  `password` everywhere except in known places: the `credentials` mapping at top, group and node
  level, and inside it only `envPrefix` (top level), `source` and `parameters`. Inside
  `parameters`, the five kind keys are allowed and their values MUST match the parameter-name
  pattern (R-5); a value that does not match is a configuration error that names the key path,
  never the value (a real password pasted there is rejected and not echoed). Unknown keys inside
  `credentials` are errors.
- **Rationale**: keeps today's protection against secrets in the YAML (Constitution II) while
  allowing parameter names.
- **Alternatives**: exempting the whole `credentials` subtree — rejected (would let a pasted
  password through).

## R-5 Parameter names

- **Decision**: full hierarchical names only: `^/[A-Za-z0-9_.\-/]{1,1010}$`, no `//`, no trailing
  `/`. Version (`name:3`) and label selectors and ARNs are refused (spec FR-004).
- **Rationale**: matches Parameter Store's naming rules for hierarchical names; one form keeps
  IAM policies and `--check-config` output predictable.

## R-6 Effective source and name resolution

- **Decision**: per node, at load time: source = node `credentials.source` ?? group
  `credentials.source` ?? profile `credentials.source` ?? `environment` (prefix from
  `credentials.envPrefix` or default). Parameter names per kind = node `parameters.<kind>` ?? group
  `parameters.<kind>` — the group's names only if the node has no own `source` (spec FR-004).
  Configuration errors at startup (and in `--check-config`): AWS node without `username` or
  `password` name; only one of `e2eUsername`/`e2ePassword`; profile-level `environment` source with
  a `prefix` different from `credentials.envPrefix`; invalid region, AWS profile name or parameter
  name. Warning: `parameters` on a node whose effective source is `environment`.
- **Rationale**: spec FR-001/FR-004/FR-006, US1-3.

## R-7 First use, caching, back-off, single flight (`NodeSecrets`)

- **Decision**: one `NodeSecrets` per node in the gateway `Slot`. For environment nodes it holds
  the startup-resolved `NodeCredentials`. For AWS nodes `current(bound)`:
  - returns the held credentials if present;
  - within 30 s after a failed fetch, throws the recorded `ToolError` at once (FR-011);
  - otherwise joins the in-flight fetch or starts one (`CompletableFuture`, single flight) that
    calls `ParameterStore.get(location, [username, password, trustStorePassword?], 10 s)` and waits
    at most `min(bound, 10 s)`; a fetch that outlives the caller's bound is not cancelled — its
    result is stored for the next call (needed for `get_health`, R-9).
  - the shared future itself is bounded by 10 s (`orTimeout`), so a hanging credential-provider
    step (instance metadata, SSO refresh) that the SDK's `ApiCallTimeout` does not cover is
    recorded as `UNREACHABLE` and starts the back-off;
  - validation through `config/CredentialRules` (R-17) — the same checks as for environment
    variables; `STRING_LIST` → `INVALID_VALUE`; `STRING` → accepted, WARN log "not encrypted"; a
    validation failure is a failed fetch (FR-011).
  - registers password, trust store password and Basic token with the `SecretScrubber` before
    anything else sees them.
  The end-to-end credentials use a second cell with the same rules, fetched only by
  `run_e2e_test` (FR-006a); the SOAP end-to-end client of an AWS node takes its trust store
  password from the main cell.
  The slot builds the HTTP client (`ensureClient`) only after `current()` returned, because the
  trust store password is part of the TLS context. The slot awaits `NodeSecrets.current(10 s)`
  **before** taking `clientLock` (the wait is interruptible, so a fan-out branch interrupted at its
  deadline returns at once even while another caller's fetch is pending); only the client
  construction runs under the lock. Only callers that are allowed to wait reach
  `ensureClient()` on an AWS node with pending secrets: fan-out branches (bounded by R-8) and
  single-node tools. `get_health` asks first without blocking (R-9).
- **Rationale**: FR-006a, FR-011, SC-006; the slot already serializes client creation.
- **Alternatives**: fetching every node at startup — rejected by the spec (Q1); per-call fetch
  without a cache — rejected (latency, AWS cost, throttling).

## R-8 Fan-out deadlines include the fetch

- **Decision**: `application/SecretTiming.allowance(NodeId)` returns 10 s + 1 s grace for a node
  whose effective source is AWS (always, whether or not its secrets are already held — the same
  rule as the detection and probe allowances of feature 009), zero for environment nodes.
  `InventoryService` (`listDeadline`, `itemDeadline`) and `DiagnosisService.deadline` add it. The
  fetch timeout is not counted against the tool's own request timeouts.
- **Rationale**: the lesson of 009 T019: anything a per-node branch may wait for must be in its
  deadline, otherwise a slow store turns a partial result into a fan-out `TIMEOUT`.

## R-9 `get_health` keeps its time budget

- **Decision**: the domain port `GatewayFactory` gets
  `default CredentialState prepareCredentials(NodeId server, Duration bound)` (domain enum
  `READY` | `PENDING`; a failed fetch throws its classified `ToolErrorException`). The default
  returns `READY`, so existing fakes stay unchanged; `AdapterGatewayFactory` implements it with
  `NodeSecrets.current(bound)` and maps a bound that ran out to `PENDING` (the fetch continues).
  `HealthService.checkServer` calls it first with what is left of the health budget, before
  `monitoring()`/`knownGateway()`:
  - `READY` → as today;
  - `PENDING` → the node's report is a failed report with `TIMEOUT` "credentials not yet
    available (still fetching from AWS Parameter Store); call get_health again shortly" — no
    healthcheck or readiness call, because the TLS client (whose trust store password may itself
    come from Parameter Store) is built only after the secrets arrive; the report is returned
    within the budget;
  - a failed fetch → a failed report with the classified error (R-11).
  The next call uses the fetched secrets.
- **Rationale**: feature 001 SC-002 (5 s per environment) must still hold; spec Q1 says
  `get_health` without a target fetches every node. Building an unauthenticated client before the
  secrets arrive was rejected: with a trust store password in Parameter Store there is no TLS
  context yet, and two client lifecycles per node would complicate the slot.

## R-10 Rotation: re-read on rejection (`CredentialGuard` + `NodeSecrets`)

- **Decision**: `CredentialGuard` gets an optional re-read hook (only for AWS nodes). On
  `rejected()`:
  1. If the guard is in its single grace attempt, pause 60 s (no re-read).
  2. Otherwise call `NodeSecrets.reread()` once (simultaneous rejections share it): `CHANGED`
     (username or password differ) → the slot discards its client and ports, the guard enters the
     grace state (exactly one further attempt, no pause); `UNCHANGED` or `FAILED` → pause 60 s as
     today. A failed re-read keeps the held values and is logged as a failed fetch, without the
     30 s fetch back-off.
  The rejected call itself fails as today (`AUTH_FAILED`). End-to-end logins never trigger a
  re-read. Upper bound: two failed logins per node per 60 s (FR-010).
  The next step of `AUTH_FAILED` for an AWS node says "Correct the parameter value(s) <names> in
  Parameter Store; no restart is needed" instead of the environment-variable text (FR-008).
- **Rationale**: spec Clarifications (2026-10-20) and FR-010; the guard already owns the lockout
  state, so the bound stays in one class.

## R-11 Errors

- **Decision**: no new `ErrorCode`. Mapping per `FailureKind`:
  - `NOT_FOUND`, `ACCESS_DENIED`, `INVALID_VALUE`, `UNKNOWN_PROFILE`, `NO_CREDENTIALS` →
    `AUTH_FAILED` (the node cannot authenticate; an administrator must fix the source);
  - `UNREACHABLE` (connection failure, throttling, 10 s timeout) → `UNREACHABLE`.
  Message: "<node>: INUBIT credentials from AWS Parameter Store (<region>[, profile <p>]) are
  unavailable: <kind> for <parameter>"; `likelyCause` per kind; `nextStep` per kind (create the
  parameter / grant `ssm:GetParameters` and `kms:Decrypt` on it / store it as SecureString / log in
  to the AWS profile / check network access to `ssm.<region>.amazonaws.com`). Never a value, never
  the raw AWS message (it may contain account ids and role ARNs); that goes to the WARN log after
  scrubbing (FR-008, FR-013).
- **Rationale**: agents already handle `AUTH_FAILED`/`UNREACHABLE`; `SECRET_UNRESOLVED` means a
  placeholder on the target (feature 004) and must not be reused.

## R-12 `--check-config`

- **Decision**: `Launcher` builds an `SsmParameterStore` for the check only if some node has an
  AWS source, fetches every configured parameter of every AWS node (grouped per `StoreLocation`,
  `GetParameters` takes up to 10 names per call), validates as at run time, never prints values.
  Profiles without any `credentials.source` setting keep today's output byte for byte (SC-001).
  As soon as a profile uses `source` anywhere, every node line shows its source:
  `secrets: environment` with today's variable details, or
  `secrets: awsParameterStore (eu-central-1, profile inubit) username ← /…, password ← /…[,
  trustStorePassword ← /…][, e2e ← /…, /…]` followed by `ok` / the failure kind per name.
  Errors (exit 1): missing, denied or invalid parameter (required or configured optional),
  unknown AWS profile. Warning (exit unchanged): no AWS credentials resolvable or store
  unreachable/timeout ("resolvability not checked"), `STRING` parameter, ignored parameter names
  on environment nodes.
- **Rationale**: spec FR-009, US3.

## R-13 Environment-variable checks restricted to environment nodes

- **Decision**: `ProfileConfig.credentialVariableNames()`, the collision check, the cross-profile
  shared-variable check and the unmatched `<PREFIX>_*_PASSWORD` warning consider only nodes whose
  effective source is `environment`. A profile whose nodes are all AWS derives no variable names.
- **Rationale**: spec edge case "two profiles read the same parameter: allowed".

## R-14 Logging

- **Decision**: one structured stderr record per fetch attempt (`secret.fetch`, fields node,
  source, region, profile, parameter names, outcome, durationMs, changed for re-reads): INFO on
  success, WARN on failure (with the scrubbed raw store message), none for calls refused within the
  30 s back-off (FR-013). The AWS SDK's own logging is set to WARN in `logback.xml` so that it never
  logs request details.
- **Rationale**: Constitution VII and II.
</content>
</invoke>

## R-15 Source-aware names in messages (`SecretLabels`)

- **Decision**: `config/SecretLabels` per node gives the human-readable name of each kind for
  messages: environment → `<NODE_VAR> (or <GROUP_VAR>)` as today; AWS → `Parameter Store <name>
  (<region>[, profile <p>])`. `CredentialGuard` (constructor takes `SecretLabels` instead of
  `CredentialVariables`), `InubitHttpClient`, `CliOutputClassifier` (built in `V81PortSet` and the
  slot), `SslContexts` (TLS_ERROR texts about the trust store password) and `SoapE2eClient` use
  it. `fixCredentials()` gives the source-specific next step (FR-008).
- **Rationale**: FR-008 requires the source and the exact name in every secret-related error,
  including TLS errors caused by a wrong trust store password.

## R-16 Audited account of AWS nodes

- **Decision**: `WritePolicy` (a record in `domain/model`) keeps `Optional<String> account`.
  `Wiring` builds each node's policy and its entry of the `accounts` function (used by the import,
  deploy, tag and end-to-end audits and the INUBIT check-in comments) **at lookup time** from the
  node's credentials: environment nodes the startup username (unchanged); AWS nodes the username
  held by `NodeSecrets` (the current one after a rotation), or
  `"(Parameter Store <username parameter name>)"` before the first fetch — which only appears on
  records written before any authenticated call (for example a refusal before anything was sent).
- **Rationale**: Constitution VII (who acted must be traceable); every write authenticates before
  it runs, so executed writes always carry the real username.

## R-17 One place for value checks (`CredentialRules`)

- **Decision**: `config/CredentialRules` holds the checks used today by `CredentialResolver` and
  `ConfigValidator` (safe username: no leading `-`, no `:`, no control characters; password at
  least `MIN_SCRUBBED_LENGTH` = 4; non-empty) plus the parameter-type rule (`STRING_LIST` refused,
  `STRING` warned). `CredentialResolver`, `NodeSecrets` and the `--check-config` path use it.
- **Rationale**: spec FR-007/FR-009 ("the same validity checks"); no divergent copies.
