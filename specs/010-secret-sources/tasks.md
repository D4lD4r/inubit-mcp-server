---

description: "Task list for configurable secret sources (feature 010)"
---

# Tasks: Configurable Secret Sources

**Input**: Design documents from `/specs/010-secret-sources/`

**Prerequisites**:
- [plan.md](plan.md) and [spec.md](spec.md) (clarifications 2026-10-17 and 2026-10-20, reviews r1–r3)
- [research.md](research.md) (R-1…R-17) and [data-model.md](data-model.md)
- [contracts/configuration-delta.md](contracts/configuration-delta.md) and
  [contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md)
- [quickstart.md](quickstart.md)
- the code of features 001–009 (branch rebased onto `009-inubit-version-detection`)

**Tests**: REQUIRED (Constitution III, TDD). Every task starts with the test(s) it names, observed
failing on an assertion before the production code exists, and ends with `mvn -q -o verify` green.
Each task contains its own tests; there are no separate "write failing tests" tasks.

**Organization**: by user story. US1 (environment variables as an explicit source, P1) and US2
(Parameter Store, P1) need the configuration foundation; US1 comes first because it is the
reference behaviour. US3 (`--check-config`, P2) and US4 (rotation, P3) follow.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on unfinished tasks)
- **[Story]**: US1 environment source, US2 Parameter Store, US3 check, US4 rotation

## Path Conventions

| Short form | Expands to |
|---|---|
| `main/…` | `src/main/java/de/dadecker/inubit/mcp/…` |
| `test/…` | `src/test/java/de/dadecker/inubit/mcp/…` |
| `res/…` | `src/main/resources/…` |

## Safety rules

- **While developing**: no call to AWS and no INUBIT command or network call; all tests are
  offline (fake `ParameterStore`, a local WireMock stub speaking the SSM JSON protocol with static
  test AWS credentials, `FakeProcessLauncher`, WireMock for INUBIT). The only network access
  allowed is the one-time Maven dependency resolution from Maven Central in T001.
- **Secrets**: test values are synthetic (`test-password-1` style); no real AWS account id, role
  ARN, region-specific account data or INUBIT credential in any file.
- **Configuration**: never change `~/.config/inubit-mcp/*`, `~/.aws/*` or the MCP registration.
- **Public repository**: no customer names in commit messages, PR descriptions or files; the
  identifier hooks must pass; never use `--no-verify`.

---

## Phase 1: Setup

- [X] T001 AWS SDK dependency (research R-1): in `pom.xml` import the AWS SDK BOM `2.54.3` and add
  `ssm`, `sso`, `ssooidc`, `sts` and `url-connection-client`; exclude `netty-nio-client`,
  `apache-client` and `apache5-client` from every SDK artifact; keep the shade configuration
  (`ServicesResourceTransformer` already present) and make sure the shaded JAR still starts
  (`MainProcessTest`). Set `software.amazon.awssdk` to `WARN` in `res/logback.xml` (R-14). Add the
  SDK to `THIRD-PARTY-NOTICES.md` (Apache-2.0, with its NOTICE). Resolve the new artifacts once
  online (`mvn -q dependency:resolve`), then build offline. Test first:
  `test/architecture/AwsSdkDependencyTest.java` asserting that
  `software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient` and
  `software.amazon.awssdk.services.ssm.SsmClient` load, and that
  `software.amazon.awssdk.http.apache.ApacheHttpClient`,
  `software.amazon.awssdk.http.apache5.Apache5HttpClient` and
  `software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient` do not (`Class.forName` throws);
  `mvn -q -o clean verify` green. (The shaded-JAR smoke test that builds a real `SsmClient` is in
  T015, where `--check-config` gives a code path without any network call.)

---

## Phase 2: Foundational (blocks all stories)

- [X] T002 Source and name types (data-model, R-3, R-5): `main/config/SecretSourceConfig.java`
  (sealed `Environment(Optional<String> prefix)` | `AwsParameterStore(String region,
  Optional<String> awsProfile)`), `main/config/ParameterNames.java` (optional `username`,
  `password`, `trustStorePassword`, `e2eUsername`, `e2ePassword`), `main/config/StoreLocation.java`
  (`region`, `Optional<String> awsProfile`). Validation: prefix `^[A-Z][A-Z0-9_]{0,63}$`; region
  `^[a-z]{2}(-[a-z]+)+-\d$`; AWS profile `^[A-Za-z0-9_.+=,@-]{1,64}$`; parameter name
  `^/[A-Za-z0-9_.\-/]{1,1010}$`, no `//`, no trailing `/`, no `:` (no version/label selectors), no
  ARN. `toString` of `ParameterNames` shows names (they are not secrets). Also
  `main/config/CredentialRules.java` (research R-17): the value checks moved from
  `CredentialResolver`/`ConfigValidator` (safe username, `MIN_SCRUBBED_LENGTH` = 4, non-empty) plus
  the parameter-type rule; `CredentialResolver` and `ConfigValidator` use it (behaviour unchanged).
  Test first: `test/config/SecretSourceConfigTest.java`, `test/config/ParameterNamesTest.java`
  (valid and invalid values of every pattern), `test/config/CredentialRulesTest.java`.
- [X] T003 `credentials` section at three levels (contracts/configuration-delta.md, R-4):
  `main/config/CredentialsConfig.java` gains `Optional<SecretSourceConfig> source`;
  `main/config/GroupConfig.java` and `NodeConfig.java` gain `CredentialsConfig credentials`
  (`envPrefix` allowed at top level only, `parameters` only on groups and nodes).
  `main/config/ConfigLoader.java`: the `credentials` mapping is no longer stripped at group and node
  level; inside it only `envPrefix` (top), `source` and `parameters` are allowed; unknown keys are
  errors inside `credentials`, inside `source` (only `type`, `prefix`, `region`, `awsProfile`, per
  type) and inside `parameters` — so AWS keys such as `accessKeyId` or `secretAccessKey` are
  refused (FR-004); inside `parameters` the five kind keys are kept and every value must match the
  parameter-name pattern — a non-matching value is an error naming the key path, never the value;
  `username`/`password` keys anywhere else are still stripped and reported as today; the next step of
  that error mentions both ways ("set the value in the environment variable <…> or store it in AWS
  Parameter Store and name it in `credentials.parameters`"). Test first:
  `test/config/ConfigLoaderTest.java` (parameter names loaded at all levels; a pasted password in
  `credentials.parameters.password` refused without echoing it; `credentials.envPrefix` on a group
  refused; unknown key inside `credentials`, `source` and `parameters` refused, incl.
  `accessKeyId`/`secretAccessKey` without echoing values; existing credential-key cases unchanged),
  `test/config/ProfileSectionTest.java` (top-level `source`).
- [X] T004 Effective source and names (R-6): `main/config/SecretPlan.java` per node (effective
  source with its origin `node|group|profile|default`, `CredentialVariables` for environment
  nodes, resolved `ParameterNames` for AWS nodes: node name per kind, else the group name only if
  the node has no own `source`); `ProfileConfig.secretPlans()` and `EffectiveNodeConfig` carry the
  plan (environment prefix = source `prefix` ?? `credentials.envPrefix` ?? default).
  `main/config/ConfigValidator.java` errors: AWS node without `username` or `password` name; only
  one of `e2eUsername`/`e2ePassword`; profile-level environment `prefix` ≠ `credentials.envPrefix`
  (US1-3); a `trustStorePassword` name on a node with a StartCLI installation (error, as for the
  variable today: StartCLI could only get it as a process argument); a `trustStorePassword` name
  without `tls.trustStore` (warning, as today); warning (ignored): `parameters` on a node or group
  whose effective source is environment, including a node whose own `environment` override sits in
  an AWS group; for AWS nodes, configured `e2eUsername`/`e2ePassword` names with an `http://`
  `e2e.soap.baseUrl` and end-to-end tests not forbidden are an error ("the credentials would travel
  in clear text", naming the parameters), like today's rule for the variables. Messages in the profile's terminology, never values. Test first: `test/config/SecretPlanTest.java` (precedence node →
  group → profile → default; group names not inherited by a node with its own source; e2e pair),
  `test/config/ConfigValidatorTest.java` (each error and warning, incl. the two trust store
  checks by name, both ignored-names cases and the clear-text e2e rule for parameter names).
- [X] T005 [P] Port and test double (after T002, parallel to T003) (R-2, data-model): `main/config/ParameterStore.java`
  (`Fetched get(StoreLocation, List<String> names, Duration timeout)`), `main/config/Fetched.java`
  (per name `Value(Secret, ParameterType)` or `Failure(FailureKind, String logDetail)`, plus a
  whole-call failure), `ParameterType` (`SECURE_STRING`, `STRING`, `STRING_LIST`), `FailureKind`
  (`NOT_FOUND`, `ACCESS_DENIED`, `UNREACHABLE`, `INVALID_VALUE`, `UNKNOWN_PROFILE`,
  `NO_CREDENTIALS`); no SDK type. `test/testing/FakeParameterStore.java`: in-memory values per
  location, scriptable delay, per-name and whole-call failures, call counter and recorded names.
  Test first: `test/config/FetchedTest.java` (`toString` never reveals a value) and a small
  self-test of the fake.
  Note (T002 review r1): `main/config/ParameterType.java` already exists from T002 (needed by
  `CredentialRules.checkType`); reuse it, do not create it again.

- [X] T021 Source-aware message names (research R-15): `main/config/SecretLabels.java` per node
  (environment `<NODE_VAR> (or <GROUP_VAR>)`; AWS `Parameter Store <name> (<region>[, profile <p>])`;
  `fixCredentials()` with the source-specific next step) built from the `SecretPlan`; it replaces
  `CredentialVariables` in the `CredentialGuard` constructor, `InubitHttpClient`,
  `CliOutputClassifier` (constructed in `main/adapter/V81PortSet.java` and the slot) and
  `main/adapter/rest/SslContexts.java` (TLS_ERROR texts about the trust store password), with its
  second caller `main/adapter/soap/SoapE2eClient.java` switched mechanically to the node's labels;
  for environment nodes every text stays identical. Test first: `test/adapter/CredentialGuardTest.java`,
  `test/adapter/rest/SslContextsTest.java`, `test/adapter/cli/CliOutputClassifierTest.java` (AWS
  labels name the parameter and say "no restart is needed"; environment texts unchanged).

**Checkpoint**: profiles with the new section load and validate; nothing uses AWS yet.

---

## Phase 3: User Story 1 - Environment variables as an explicit source (Priority: P1)

**Goal**: existing profiles unchanged; an explicit environment source behaves identically
(FR-002, FR-003).

**Independent Test**: all existing tests green without changed expectations; the same profile with
`credentials.source: {type: environment}` reads the same variables and gives the same
`--check-config` output apart from the `secrets: environment` part.

- [X] T006 [US1] Resolver per plan: `main/config/CredentialResolver.java` resolves only nodes whose
  effective source is environment (with the plan's prefix and variables, rules unchanged) and
  returns `NodeCredentials.deferred(node)` for AWS nodes (`NodeCredentials` gains
  `boolean deferred()`; `complete()` false); `Launcher` and `Wiring` (e2e resolver) pass the plans.
  Missing-variable errors are reported only for environment nodes. Test first:
  `test/config/CredentialResolverTest.java` (explicit environment source with and without `prefix`
  equals today; a group-level environment override with its own prefix; an AWS node is deferred and
  produces no missing-variable error), `test/MainTest.java` (an existing profile's startup and
  `--check-config` output byte-for-byte unchanged except the new `secrets:` part, see T008).
  Carry-over (T004 review r1): `EffectiveNodeConfig.credentialVariables()` already follows the plan's
  prefix; the resolver (and Launcher/Wiring) must read `server.secretPlan().variables()` so that an
  environment node or group with its own `source.prefix` is resolved with the same variables. Also
  consider moving the duplicated group-source precedence in
  `ConfigValidator.checkIgnoredParameterNames` into `SecretPlan`.
- [X] T007 [US1] Environment checks only for environment nodes (R-13):
  `ProfileConfig.credentialVariableNames()`, the collision check, `ConfigValidator.checkOtherProfiles`
  `CredentialResolver.unmatchedPasswordVariables`, `ConfigValidator.e2eCredentialVariables` and
  `checkE2eNodeNames` consider only environment nodes. Test first:
  `test/config/ConfigValidatorTest.java` (two profiles whose nodes are all AWS: no shared-variable
  error; a mixed profile still reports collisions among its environment nodes),
  `test/config/CredentialResolverTest.java`.
  Carry-over (T007 review r1): the cross-profile warning "the credential variable prefix X (both read
  the same variables)" in `ConfigValidator.checkOtherProfiles` should appear only when both profiles
  have at least one environment node (both `credentialVariableNames()` non-empty).
- [X] T008 [US1] `--check-config` environment part (contracts/configuration-delta.md, SC-001):
  profiles without any `credentials.source` setting keep today's output byte for byte; as soon as
  `source` is used anywhere in the profile, each node line gains `secrets: environment` (plus the
  existing `username ← …`, `password ← …`, `trustStorePassword ← …` details) and the header line
  `Credential variables: …` is shown only when at least one node uses environment variables.
  Test first: `test/config/ConfigSummaryTest.java` (unchanged output without `source`; the new part
  with `source`), existing `MainTest`/`MigrationGuideExampleTest` expectations unchanged.

**Checkpoint**: US1 complete; behaviour of every existing profile unchanged.

---

## Phase 4: User Story 2 - Fetch secrets from AWS Parameter Store (Priority: P1)

**Goal**: AWS nodes authenticate with values fetched on first use (FR-004 to FR-008, FR-011 to
FR-013, SC-005, SC-006).

**Independent Test**: with the fake store, a tool on an AWS node authenticates with the stored
values; untouched nodes are never fetched; failures name node, parameter and kind; nothing leaks.

- [X] T009 [P] [US2] AWS adapter (R-1, R-2): `main/adapter/aws/SsmParameterStore.java` (+
  `package-info.java`) implements `ParameterStore`: one cached `SsmClient` per `StoreLocation`
  built with `UrlConnectionHttpClient`, the region, and `ProfileCredentialsProvider` (named AWS
  profile) or `DefaultCredentialsProvider`; `GetParameters` with `WithDecryption=true` in batches of
  10; `ApiCallTimeout` = the given timeout; maps the parameter `Type` to `ParameterType`, invalid
  names to `NOT_FOUND`, `AccessDeniedException`/KMS denials to `ACCESS_DENIED`, missing
  credentials to `NO_CREDENTIALS`, an unknown profile to `UNKNOWN_PROFILE`, connection failures,
  throttling and timeouts to `UNREACHABLE`; `logDetail` = the SDK message. A package-private
  constructor takes an endpoint override and a static credentials provider for tests.
  `test/architecture/PackageBoundaryTest.java`: only `adapter/aws` may use `software/amazon/`;
  `adapter/aws` must not reach `mcp` or `application`; add it to the package-existence list. Test
  first: `test/adapter/aws/SsmParameterStoreTest.java` against a WireMock stub of
  `POST / X-Amz-Target: AmazonSSM.GetParameters` (values and types, `InvalidParameters`, batching
  of 12 names, 400 `AccessDeniedException`, throttling, a delay beyond the timeout, unknown
  profile via a temporary `AWS_CONFIG_FILE`/`AWS_SHARED_CREDENTIALS_FILE` set through system
  properties, a credential provider that fails (expired SSO token, failing profile credentials) →
  `NO_CREDENTIALS`, no request when the name list is empty).
- [X] T010 [P] [US2] Lazy node secrets (R-7, R-11, R-14, R-17): `main/adapter/NodeSecrets.java` — for
  environment nodes the startup `NodeCredentials`; for AWS nodes `current(Duration bound)` with
  single-flight fetch of username, password and (if named) trust store password, 10 s store
  timeout plus the same 10 s bound on the shared future (`orTimeout`, a hanging credential-provider
  step → `UNREACHABLE`), waiting at most `min(bound, 10 s)` without cancelling the fetch, 30 s
  back-off after a failure (the recorded `ToolError` returned at once), validation through
  `CredentialRules` (`STRING_LIST` → invalid value; `STRING` → accepted with a WARN "not
  encrypted"), a validation failure counts as a failed fetch, registration of password, trust store
  password and Basic token with the `SecretScrubber` before use; error mapping per
  contracts/mcp-tools-delta.md (`AUTH_FAILED`/`UNREACHABLE`, node, region, profile, parameter,
  kind, next step; never a value or the raw AWS message); one structured log record per attempt
  (INFO/WARN, scrubbed `logDetail`, none within the back-off). (The end-to-end cell is T012.)
  Test first: `test/adapter/NodeSecretsTest.java` (fake store,
  fake clock: first use only; concurrent callers share one fetch; back-off 30 s then retry; a store
  call that never returns ends after 10 s as `UNREACHABLE`; two AWS nodes with a 1 s store delay
  resolve together in about 1 s, not 2 s (one virtual thread per fetch); bound
  shorter than the fetch returns a timeout but the next call gets the stored result; each failure
  kind's error text; `String` warning and `StringList` refusal; validation failure counts as
  failure; scrubber registration; log records via a list appender; SC-005 timing with a 1 s store
  delay: first call ≤ 2 s extra, second call ≤ 50 ms).
  Carry-over (T005 review r1): in `test/testing/FakeParameterStoreTest.java` drop or widen (≥ 2 s)
  the wall-clock upper bounds (lines ~113/115) to avoid flakiness; document in
  `FakeParameterStore`'s Javadoc that an interrupted caller gets `CallFailed(UNREACHABLE,
  "interrupted")` and that the fake never enforces the timeout (NodeSecrets must).
- [X] T011 [US2] Slot integration (R-7, FR-006a): `main/adapter/AdapterGatewayFactory.java` takes
  `Map<NodeId, NodeSecrets>` instead of fixed `NodeCredentials`; before building the client the
  slot awaits `NodeSecrets.current(10 s)` **outside** `lock`/`clientLock` (interruptible wait), then
  builds the probe, the HTTP client and the port set under the lock (the trust store password is
  part of the TLS context); detection (`detectNow`) and every authenticated port go through it;
  tools that do not contact a node never trigger a fetch. `main/Wiring.java` creates the
  `SsmParameterStore` (only if some node is AWS) and one `NodeSecrets` per node, and passes each
  node's `SecretLabels` (T021). Test first: `test/adapter/AdapterGatewayFactoryTest.java` (an AWS
  node's first authenticated call fetches once and authenticates against WireMock with the stored
  values; a fetch failure fails the call before any INUBIT request; configuration-only paths fetch
  nothing; a caller interrupted while another caller's fetch is pending returns at once),
  `test/MainTest.java` (startup with an AWS node and no
  AWS access succeeds; no store call at startup), `test/security/NoSecretLeakTest.java` (AWS source:
  no value in results, logs, errors, audit).
  Carry-over (T010 review r1), in `NodeSecrets`: interrupt the fetch's virtual thread when the
  `orTimeout` bound fires (avoid piling up stuck threads); give the post-timeout `fetch.get()` a small
  grace bound (e.g. 1 s) instead of waiting without limit; log class plus scrubbed message (or the
  throwable at DEBUG) for an unexpected RuntimeException instead of only the class name.
  Carry-over (T011 review r1): give the spin loop `while (store.calls() == 0)` in
  `AdapterGatewayFactoryTest.aCallerInterruptedWhileAnotherCallersFetchIsPendingReturnsAtOnce` the
  same 5 s deadline as the next loop, so a regression fails instead of hanging.
- [X] T012 [US2] End-to-end credentials per source (FR-006a, R-7): `main/adapter/NodeSecrets.java`
  gets the end-to-end cell (same rules as the main fetch: single flight, 10 s, 30 s back-off,
  `CredentialRules`, logging) for the `e2eUsername`/`e2ePassword` parameters; `main/Wiring.java`
  takes the e2e credentials of an AWS node from that cell on the first `run_e2e_test` on that node,
  and builds the node's `SoapE2eClient` with the trust store password from the main cell (for
  environment nodes unchanged). Both fetches happen **before** `e2eClients.computeIfAbsent`, so a
  slow fetch never blocks other entries and a failed fetch is not cached as a client. Failures name
  the parameters. Test first: `test/adapter/NodeSecretsTest.java` (e2e cell), `test/WiringE2eTest.java`
  (fetched only when an e2e test runs; failure text; a failed fetch is retried after the back-off
  instead of a cached broken client; SOAP TLS with a trust store password from the fake store;
  environment unchanged).
- [X] T020 [US2] Audited account per source (research R-16, Constitution VII): `WritePolicy`
  (`main/domain/model/WritePolicy.java`) keeps `Optional<String> account`; `main/Wiring.java` builds
  each node's policy (`policy(...)`) and its entry of the `accounts` function (used by the import,
  deploy, tag and end-to-end audits and the INUBIT check-in comments) at lookup time: environment
  nodes the startup username; AWS nodes the username held by `NodeSecrets` (current after a
  rotation), or `"(Parameter Store <username parameter name>)"` before the first fetch. Test first:
  `test/WiringAuditTest.java` (an executed `restart_process` and an import or tag on an AWS node
  carry the fetched username in their audit records; the check-in comment of an import carries it
  too, never the placeholder; a refusal before any fetch carries the placeholder; environment nodes
  unchanged).
- [X] T013 [US2] Fan-out deadlines (R-8): `main/application/SecretTiming.java`
  (`allowance(NodeId)`: 10 s + 1 s grace for AWS nodes, zero otherwise; built in `Wiring` from the
  plans); `InventoryService.listDeadline`/`itemDeadline` and `DiagnosisService.deadline` add it
  (always for AWS nodes, like the 009 detection and probe allowances). Test first:
  `test/application/InventoryServiceTest.java` and `DiagnosisServiceTest.java` (deadline arithmetic
  with and without an AWS node; a store slower than the request timeout yields that node's error
  or partial result, not a fan-out `TIMEOUT`).
  Carry-over (T013 review r2): move the dangling Javadoc of `DiagnosisService.validate(Runnable)` back
  above its method (the `validateHeld` Javadoc was inserted in between); qualify the tool Javadocs
  `QueryLogsTool` (~:18-20) and `FindProcessesTool` (~:15) ("fails the whole call"): a node whose
  secrets are not fetched yet is validated in its own branch (research R-8 addendum).
- [X] T014 [US2] `get_health` budget (R-9, contracts/mcp-tools-delta.md): in
  `main/application/HealthService.java` `checkServer` calls `gateways.prepareCredentials(server,
  <what is left of the budget>)` before `monitoring()`/`knownGateway()`; `PENDING` → a failed report
  with `TIMEOUT` "credentials not yet available (still fetching from AWS Parameter Store); call
  get_health again shortly", sending nothing to that node, returned within the budget; a thrown
  fetch error → a failed report with that error; `READY` → as today. The port method is added here:
  `main/domain/port/GatewayFactory.java` gets `default CredentialState prepareCredentials(NodeId,
  Duration bound)` returning `READY` (existing fakes unchanged), new
  `main/domain/model/CredentialState.java` (`READY`|`PENDING`), implemented in
  `AdapterGatewayFactory` with `NodeSecrets.current(bound)` (a bound that ran out → `PENDING`, the
  fetch continues; a failed fetch throws). Test first: `test/adapter/AdapterGatewayFactoryTest.java`
  (`prepareCredentials` READY/PENDING/throws, never blocking beyond the bound),
  `test/application/HealthServiceTest.java` (a fake `GatewayFactory` answering `PENDING`: report
  within the budget, no monitoring call; a throwing one: the classified error; a slow fake store end
  to end: the next call reports normally), `test/mcp/tools/GetHealthToolTest.java`
  (timing within `timeout` + 1 s; schema-valid), `test/performance/HealthOverviewPerformanceTest.java`
  stays within 10 s.

**Checkpoint**: US2 complete — a profile can switch to Parameter Store (SC-002).

---

## Phase 5: User Story 3 - See and check where secrets come from (Priority: P2)

  Carry-over (T008 review r1): the `Credential variables:` header shows the profile prefix even when
  every environment node uses its own `source.prefix`; show it only when some environment plan uses
  the profile prefix, or list the distinct prefixes.
- [ ] T015 [US3] `--check-config` for AWS nodes (R-12, FR-009, contracts/configuration-delta.md):
  `main/Launcher.java` creates an `SsmParameterStore` only if some node is AWS and fetches every
  configured parameter of every AWS node grouped per `StoreLocation` (10 s timeout per batch);
  `main/config/ConfigSummary.java` shows per AWS node
  `secrets: awsParameterStore (<region>[, profile <p>])` with `<kind> ← <name>` and `ok` or the
  failure kind; values are validated as at run time and never printed. Exit 1: a configured
  parameter missing, denied or invalid; an unknown AWS profile. Warning, exit unchanged: no AWS
  credentials resolvable, store unreachable or timed out ("resolvability not checked"), `String`
  parameters, ignored names. Test first: `test/config/ConfigSummaryTest.java` (fake store: every
  case, never a value), `test/MainTest.java` (exit codes 1 and 0 with warnings; no store created
  for an environment-only profile), `test/MainProcessTest.java` (shaded-JAR smoke: `--check-config`
  of a profile with an AWS node, with `AWS_EC2_METADATA_DISABLED=true`, empty
  `AWS_CONFIG_FILE`/`AWS_SHARED_CREDENTIALS_FILE` and no AWS variables, builds the real `SsmClient`
  and reports "resolvability not checked" with exit 0 — no network call).

---

## Phase 6: User Story 4 - Rotate a password without restarting (Priority: P3)

- [ ] T016 [US4] Re-read on rejection (R-10, FR-010): `main/adapter/CredentialGuard.java` gets an
  optional re-read hook (AWS nodes only) and a grace state: on `rejected()` outside grace, call
  `NodeSecrets.reread()` once (simultaneous rejections share it); `CHANGED` (username or password
  differ) → the slot discards its client, ports and cached gateway and the guard allows exactly one
  further attempt
  without a pause; `UNCHANGED` or `FAILED` → pause 60 s as today; `rejected()` in grace → pause 60 s
  without re-read. A failed re-read keeps the held values, is logged as a failed fetch (with
  `changed=false`) and does not start the 30 s fetch back-off. The rejected call fails as today;
  end-to-end rejections never re-read; no time-based refresh. Test first:
  `test/adapter/CredentialGuardTest.java` (state machine with a fake hook and fake clock: changed →
  one grace attempt → rejected → pause; unchanged → pause; failed re-read → pause; at most two
  failed logins per 60 s; concurrent rejections share one re-read), `test/adapter/
  AdapterGatewayFactoryTest.java` (end to end with the fake store and WireMock: rotated password
  used by the next call; old client, ports and gateway discarded — also through `forServer`), `test/adapter/NodeSecretsTest.java` (`reread()`
  results and logging).

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T017 [P] `docs/setup.md`: section 4 becomes "Credentials: sources" with the environment
  source (today's text, `credentials.source` optional), the Parameter Store source (YAML from
  contracts/configuration-delta.md, precedence and overrides, parameter-name rules, first use,
  10 s timeout and 30 s back-off, rotation and the two-login bound), the minimal IAM permissions
  (`ssm:GetParameters` on the named parameters, `kms:Decrypt` on their key; example policy with
  placeholder ARNs), the AWS credential chain and AWS profiles, and "Moving from a start script to
  Parameter Store"; section 6 (`--check-config`) shows the `secrets:` part and the new exit-code
  cases. Every quoted value checked against the code. Keep `DevelopmentToolsReferenceTest` and
  `DeploymentReferenceTest` green.
- [ ] T018 [P] `docs/tools.md` (errors from contracts/mcp-tools-delta.md, `get_health` part reason,
  fan-out behaviour, `run_e2e_test`), `README.md` "Safety model" ("Secrets only via environment
  variables" → environment variables or Parameter Store, never in the profile or the
  conversation), `CHANGELOG.md` `[Unreleased]` (feature 010, the new dependency). Every quoted value
  checked against the code.
- [ ] T019 Validate per quickstart.md: `mvn -q -o clean verify`; `mvn -q -o
  -Dtest=NoCustomerIdentifiersTest test` with the local lists (it must run, not skip); record the
  outcome (counts only) in `quickstart.md`. The optional live check only with the person's
  approval.

---

## Dependencies & Execution Order

- T001 first (the dependency is needed by T009; the other tasks do not use it but share
  `pom.xml`). T002 next; then T003 and T005 in parallel; T004 after T003; T021 after T004.
- US1 (T006–T008) needs T004; T006, then T007 (both change `CredentialResolver`), then T008.
- US2: T009 needs T001, T005; T010 needs T005, T021 (not T009); T009 and T010 in parallel; T011
  needs T006, T010, T021; then T012, T020, T013 and T014 one after another (they share
  `Wiring.java` or the slot).
- US3 (T015) needs T009, T008.
- US4 (T016) needs T011.
- Polish (T017–T019) last; T017 and T018 in parallel.

## Parallel Example

```text
Phase 2:  T002 source/name types  →  T003 loader  |  T005 port + FakeParameterStore  →  T004 → T021
Phase 4:  T009 SsmParameterStore  |  T010 NodeSecrets  →  T011 → T012 → T020 → T013 → T014
Phase 7:  T017 setup.md  |  T018 tools.md/README/CHANGELOG
```

## Implementation Strategy

- **MVP**: Phases 1–4 (configuration, explicit environment source, Parameter Store on first use).
  After Phase 3 nothing has changed for existing users; after Phase 4 a profile can move to
  Parameter Store.
- Then `--check-config` (US3), rotation (US4) and the docs.
- Run each task through the implementer → reviewer loop (`/speckit-duo:implement`); every commit
  stays green because each task carries its own tests.
