---

description: "Task list for configurable secret sources (feature 010)"
---

# Tasks: Configurable Secret Sources

**Input**: Design documents from `/specs/010-secret-sources/`

**Prerequisites**:
- [plan.md](plan.md) and [spec.md](spec.md) (clarifications 2026-10-17 and 2026-10-20, reviews r1–r3)
- [research.md](research.md) (R-1…R-14) and [data-model.md](data-model.md)
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

- [ ] T001 AWS SDK dependency (research R-1): in `pom.xml` import the AWS SDK BOM `2.54.3` and add
  `ssm`, `sso`, `ssooidc`, `sts` and `url-connection-client`; exclude `netty-nio-client`,
  `apache-client` and `apache5-client` from every SDK artifact; keep the shade configuration
  (`ServicesResourceTransformer` already present) and make sure the shaded JAR still starts
  (`MainProcessTest`). Set `software.amazon.awssdk` to `WARN` in `res/logback.xml` (R-14). Add the
  SDK to `THIRD-PARTY-NOTICES.md` (Apache-2.0, with its NOTICE). Resolve the new artifacts once
  online (`mvn -q dependency:resolve`), then build offline. Test: `test/architecture/DependencyTest`
  (or the existing dependency check, if any) asserting no Netty/Apache HTTP client class is on the
  runtime classpath; `mvn -q -o clean verify` green.

---

## Phase 2: Foundational (blocks all stories)

- [ ] T002 [P] Source and name types (data-model, R-3, R-5): `main/config/SecretSourceConfig.java`
  (sealed `Environment(Optional<String> prefix)` | `AwsParameterStore(String region,
  Optional<String> awsProfile)`), `main/config/ParameterNames.java` (optional `username`,
  `password`, `trustStorePassword`, `e2eUsername`, `e2ePassword`), `main/config/StoreLocation.java`
  (`region`, `Optional<String> awsProfile`). Validation: prefix `^[A-Z][A-Z0-9_]{0,63}$`; region
  `^[a-z]{2}(-[a-z]+)+-\d$`; AWS profile `^[A-Za-z0-9_.+=,@-]{1,64}$`; parameter name
  `^/[A-Za-z0-9_.\-/]{1,1010}$`, no `//`, no trailing `/`, no `:` (no version/label selectors), no
  ARN. `toString` of `ParameterNames` shows names (they are not secrets). Test first:
  `test/config/SecretSourceConfigTest.java`, `test/config/ParameterNamesTest.java` (valid and
  invalid values of every pattern).
- [ ] T003 `credentials` section at three levels (contracts/configuration-delta.md, R-4):
  `main/config/CredentialsConfig.java` gains `Optional<SecretSourceConfig> source`;
  `main/config/GroupConfig.java` and `NodeConfig.java` gain `CredentialsConfig credentials`
  (`envPrefix` allowed at top level only, `parameters` only on groups and nodes).
  `main/config/ConfigLoader.java`: the `credentials` mapping is no longer stripped at group and node
  level; inside it only `envPrefix` (top), `source` and `parameters` are allowed (unknown keys are
  errors); inside `parameters` the five kind keys are kept and every value must match the
  parameter-name pattern — a non-matching value is an error naming the key path, never the value;
  `username`/`password` keys anywhere else are still stripped and reported as today. Test first:
  `test/config/ConfigLoaderTest.java` (parameter names loaded at all levels; a pasted password in
  `credentials.parameters.password` refused without echoing it; `credentials.envPrefix` on a group
  refused; unknown key inside `credentials` refused; existing credential-key cases unchanged),
  `test/config/ProfileSectionTest.java` (top-level `source`).
- [ ] T004 Effective source and names (R-6): `main/config/SecretPlan.java` per node (effective
  source with its origin `node|group|profile|default`, `CredentialVariables` for environment
  nodes, resolved `ParameterNames` for AWS nodes: node name per kind, else the group name only if
  the node has no own `source`); `ProfileConfig.secretPlans()` and `EffectiveNodeConfig` carry the
  plan (environment prefix = source `prefix` ?? `credentials.envPrefix` ?? default).
  `main/config/ConfigValidator.java` errors: AWS node without `username` or `password` name; only
  one of `e2eUsername`/`e2ePassword`; profile-level environment `prefix` ≠ `credentials.envPrefix`
  (US1-3); warning: `parameters` on an environment node (ignored). Messages in the profile's
  terminology, never values. Test first: `test/config/SecretPlanTest.java` (precedence node →
  group → profile → default; group names not inherited by a node with its own source; e2e pair),
  `test/config/ConfigValidatorTest.java` (each error and the warning).
- [ ] T005 [P] Port and test double (R-2, data-model): `main/config/ParameterStore.java`
  (`Fetched get(StoreLocation, List<String> names, Duration timeout)`), `main/config/Fetched.java`
  (per name `Value(Secret, ParameterType)` or `Failure(FailureKind, String logDetail)`, plus a
  whole-call failure), `ParameterType` (`SECURE_STRING`, `STRING`, `STRING_LIST`), `FailureKind`
  (`NOT_FOUND`, `ACCESS_DENIED`, `UNREACHABLE`, `INVALID_VALUE`, `UNKNOWN_PROFILE`,
  `NO_CREDENTIALS`); no SDK type. `test/testing/FakeParameterStore.java`: in-memory values per
  location, scriptable delay, per-name and whole-call failures, call counter and recorded names.
  Test first: `test/config/FetchedTest.java` (`toString` never reveals a value) and a small
  self-test of the fake.

**Checkpoint**: profiles with the new section load and validate; nothing uses AWS yet.

---

## Phase 3: User Story 1 - Environment variables as an explicit source (Priority: P1)

**Goal**: existing profiles unchanged; an explicit environment source behaves identically
(FR-002, FR-003).

**Independent Test**: all existing tests green without changed expectations; the same profile with
`credentials.source: {type: environment}` reads the same variables and gives the same
`--check-config` output apart from the `secrets: environment` part.

- [ ] T006 [US1] Resolver per plan: `main/config/CredentialResolver.java` resolves only nodes whose
  effective source is environment (with the plan's prefix and variables, rules unchanged) and
  returns `NodeCredentials.deferred(node)` for AWS nodes (`NodeCredentials` gains
  `boolean deferred()`; `complete()` false); `Launcher` and `Wiring` (e2e resolver) pass the plans.
  Missing-variable errors are reported only for environment nodes. Test first:
  `test/config/CredentialResolverTest.java` (explicit environment source with and without `prefix`
  equals today; a group-level environment override with its own prefix; an AWS node is deferred and
  produces no missing-variable error), `test/MainTest.java` (an existing profile's startup and
  `--check-config` output byte-for-byte unchanged except the new `secrets:` part, see T008).
- [ ] T007 [P] [US1] Environment checks only for environment nodes (R-13):
  `ProfileConfig.credentialVariableNames()`, the collision check, `ConfigValidator.checkOtherProfiles`
  and `CredentialResolver.unmatchedPasswordVariables` consider only environment nodes. Test first:
  `test/config/ConfigValidatorTest.java` (two profiles whose nodes are all AWS: no shared-variable
  error; a mixed profile still reports collisions among its environment nodes),
  `test/config/CredentialResolverTest.java`.
- [ ] T008 [US1] `--check-config` environment part (contracts/configuration-delta.md): each node
  line gains `secrets: environment` (plus the existing `username ← …`, `password ← …`,
  `trustStorePassword ← …` details); the header line `Credential variables: …` is shown only when
  at least one node uses environment variables. Update the verbatim example in
  `docs/migration-001-to-002.md` if `MigrationGuideExampleTest` pins it. Test first:
  `test/config/ConfigSummaryTest.java`, `test/config/MigrationGuideExampleTest.java`.

**Checkpoint**: US1 complete; behaviour of every existing profile unchanged.

---

## Phase 4: User Story 2 - Fetch secrets from AWS Parameter Store (Priority: P1)

**Goal**: AWS nodes authenticate with values fetched on first use (FR-004 to FR-008, FR-011 to
FR-013, SC-005, SC-006).

**Independent Test**: with the fake store, a tool on an AWS node authenticates with the stored
values; untouched nodes are never fetched; failures name node, parameter and kind; nothing leaks.

- [ ] T009 [US2] AWS adapter (R-1, R-2): `main/adapter/aws/SsmParameterStore.java` (+
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
  properties, no request when the name list is empty).
- [ ] T010 [US2] Lazy node secrets (R-7, R-11, R-14): `main/adapter/NodeSecrets.java` — for
  environment nodes the startup `NodeCredentials`; for AWS nodes `current(Duration bound)` with
  single-flight fetch of username, password and (if named) trust store password, 10 s store
  timeout, waiting at most `min(bound, 10 s)` without cancelling the fetch, 30 s back-off after a
  failure (the recorded `ToolError` returned at once), validation as today (safe username, password
  ≥ 4 characters, non-empty; `STRING_LIST` → invalid value; `STRING` → accepted with a WARN "not
  encrypted"), a validation failure counts as a failed fetch, registration of password, trust store
  password and Basic token with the `SecretScrubber` before use; error mapping per
  contracts/mcp-tools-delta.md (`AUTH_FAILED`/`UNREACHABLE`, node, region, profile, parameter,
  kind, next step; never a value or the raw AWS message); one structured log record per attempt
  (INFO/WARN, scrubbed `logDetail`, none within the back-off). A second cell for the end-to-end
  credentials with the same rules. Test first: `test/adapter/NodeSecretsTest.java` (fake store,
  fake clock: first use only; concurrent callers share one fetch; back-off 30 s then retry; bound
  shorter than the fetch returns a timeout but the next call gets the stored result; each failure
  kind's error text; `String` warning and `StringList` refusal; validation failure counts as
  failure; scrubber registration; log records via a list appender; SC-005 timing with a 1 s store
  delay: first call ≤ 2 s extra, second call ≤ 50 ms).
- [ ] T011 [US2] Slot integration (R-7, FR-006a, FR-008): `main/adapter/AdapterGatewayFactory.java`
  takes `Map<NodeId, NodeSecrets>` instead of fixed `NodeCredentials`; `Slot.ensureClient()` obtains
  the credentials from `NodeSecrets.current(...)` first (the trust store password is part of the
  TLS context), then builds the probe, the HTTP client and the port set; detection (`detectNow`)
  and every authenticated port go through it; tools that do not contact a node (e.g. `list_nodes`)
  never trigger a fetch. `CredentialGuard.fixCredentials()` returns the next step of the node's
  source (environment: as today; AWS: "Correct the parameter value(s) <names> in Parameter Store; no
  restart is needed"), used by `InubitHttpClient` and `CliOutputClassifier`. `main/Wiring.java`
  creates the `SsmParameterStore` (only if some node is AWS) and one `NodeSecrets` per node.
  Test first: `test/adapter/AdapterGatewayFactoryTest.java` (an AWS node's first authenticated call
  fetches once and authenticates against WireMock with the stored values; a fetch failure fails the
  call before any INUBIT request; `list_nodes`/configuration-only paths fetch nothing; the
  rejected-credentials next step per source), `test/MainTest.java` (startup with an AWS node and no
  AWS access succeeds; no store call at startup), `test/security/NoSecretLeakTest.java` (AWS source:
  no value in results, logs, errors, audit).
- [ ] T012 [P] [US2] End-to-end credentials per source (FR-006a): `main/Wiring.java` takes the e2e
  credentials of an AWS node from its `NodeSecrets` e2e cell on the first `run_e2e_test` on that
  node (environment nodes unchanged); failures name the `e2eUsername`/`e2ePassword` parameters.
  Test first: `test/application/E2eTestServiceTest.java` or `test/WiringE2eTest.java` (fetched only
  when an e2e test runs; failure text; environment unchanged).
- [ ] T013 [US2] Fan-out deadlines (R-8): `main/application/SecretTiming.java`
  (`allowance(NodeId)`: 10 s + 1 s grace for AWS nodes, zero otherwise; built in `Wiring` from the
  plans); `InventoryService.listDeadline`/`itemDeadline` and `DiagnosisService.deadline` add it
  (always for AWS nodes, like the 009 detection and probe allowances). Test first:
  `test/application/InventoryServiceTest.java` and `DiagnosisServiceTest.java` (deadline arithmetic
  with and without an AWS node; a store slower than the request timeout yields that node's error
  or partial result, not a fan-out `TIMEOUT`).
- [ ] T014 [US2] `get_health` budget (R-9, contracts/mcp-tools-delta.md): in
  `main/application/HealthService.java` the authenticated parts of an AWS node wait for its
  secrets at most for what is left of the health budget (through a non-blocking readiness check on
  the gateway, no AWS type in `application`); if not ready, those parts are `unavailable` with
  "credentials not yet available (fetching from Parameter Store)", the unauthenticated parts run,
  and the fetch continues; a failed fetch shows its classified error. Test first:
  `test/application/HealthServiceTest.java` (slow store: budget kept, parts unavailable with the
  text, next call uses the stored secrets; failed fetch text), `test/mcp/tools/GetHealthToolTest.java`
  (timing within `timeout` + 1 s; schema-valid), `test/performance/HealthOverviewPerformanceTest.java`
  stays within 10 s.

**Checkpoint**: US2 complete — a profile can switch to Parameter Store (SC-002).

---

## Phase 5: User Story 3 - See and check where secrets come from (Priority: P2)

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
  for an environment-only profile).

---

## Phase 6: User Story 4 - Rotate a password without restarting (Priority: P3)

- [ ] T016 [US4] Re-read on rejection (R-10, FR-010): `main/adapter/CredentialGuard.java` gets an
  optional re-read hook (AWS nodes only) and a grace state: on `rejected()` outside grace, call
  `NodeSecrets.reread()` once (simultaneous rejections share it); `CHANGED` (username or password
  differ) → the slot discards its client and ports and the guard allows exactly one further attempt
  without a pause; `UNCHANGED` or `FAILED` → pause 60 s as today; `rejected()` in grace → pause 60 s
  without re-read. A failed re-read keeps the held values, is logged as a failed fetch (with
  `changed=false`) and does not start the 30 s fetch back-off. The rejected call fails as today;
  end-to-end rejections never re-read; no time-based refresh. Test first:
  `test/adapter/CredentialGuardTest.java` (state machine with a fake hook and fake clock: changed →
  one grace attempt → rejected → pause; unchanged → pause; failed re-read → pause; at most two
  failed logins per 60 s; concurrent rejections share one re-read), `test/adapter/
  AdapterGatewayFactoryTest.java` (end to end with the fake store and WireMock: rotated password
  used by the next call; old client discarded), `test/adapter/NodeSecretsTest.java` (`reread()`
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
  `pom.xml`). T002 and T005 in parallel; T003 after T002; T004 after T003.
- US1 (T006–T008) needs T004; T007 parallel to T006; T008 after T006.
- US2: T009 needs T001, T005; T010 needs T005 (not T009); T011 needs T006, T010; T012 after T011;
  T013 after T011; T014 after T011.
- US3 (T015) needs T009, T008.
- US4 (T016) needs T011.
- Polish (T017–T019) last; T017 and T018 in parallel.

## Parallel Example

```text
Phase 2:  T002 source/name types  |  T005 port + FakeParameterStore   →  T003 → T004
Phase 4:  T009 SsmParameterStore  |  T010 NodeSecrets                  →  T011 → T012 | T013 | T014
Phase 7:  T017 setup.md  |  T018 tools.md/README/CHANGELOG
```

## Implementation Strategy

- **MVP**: Phases 1–4 (configuration, explicit environment source, Parameter Store on first use).
  After Phase 3 nothing has changed for existing users; after Phase 4 a profile can move to
  Parameter Store.
- Then `--check-config` (US3), rotation (US4) and the docs.
- Run each task through the implementer → reviewer loop (`/speckit-duo:implement`); every commit
  stays green because each task carries its own tests.
</content>
</invoke>
