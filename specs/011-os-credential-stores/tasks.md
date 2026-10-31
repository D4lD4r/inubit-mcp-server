---

description: "Task list for credentials only from a credential store (feature 011)"
---

# Tasks: Credentials Only from a Credential Store

**Input**: Design documents from `/specs/011-os-credential-stores/`

**Prerequisites**:
- [plan.md](plan.md) and [spec.md](spec.md) (clarifications 2026-10-31, spec reviews r1–r2, plan
  reviews r1–r2)
- [research.md](research.md) (R-1…R-17) and [data-model.md](data-model.md)
- [contracts/configuration-delta.md](contracts/configuration-delta.md) and
  [contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md)
- [quickstart.md](quickstart.md) (scenario numbers `Q<n>` below)
- the code of features 001–010 (0.6.2)

**Tests**: REQUIRED (Constitution III, TDD). Every task starts with the test(s) it names, observed
failing on an assertion before the production code exists, and ends with `mvn -q -o verify` green.
Each task contains its own tests; there are no separate "write failing tests" tasks.

**Organization**: by the plan's stages. The store adapters (US1, US2) and the configuration model
(US3) are the foundation; the switch to lazy credentials for every node (Phase 4) is the breaking
step and removes the environment path. US4 (`--check-config`), US5 (rotation, messages) and US6
(live tests) follow.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on unfinished tasks)
- **[Story]**: US1 macOS Keychain, US2 Windows/Linux stores, US3 removal of environment variables,
  US4 check, US5 rotation, US6 live tests

## Path Conventions

| Short form | Expands to |
|---|---|
| `main/…` | `src/main/java/de/dadecker/inubit/mcp/…` |
| `test/…` | `src/test/java/de/dadecker/inubit/mcp/…` |
| `res/…` | `src/main/resources/…` |

## Safety rules

- **While developing**: no access to a real credential store, AWS or INUBIT. All tests are offline:
  `FakeCredentialStore`, fake `security` / `secret-tool` executables in a temp directory (as the fake
  StartCLI), the `CredApi` seam for Windows, the existing fakes for Parameter Store and INUBIT. The
  only network access allowed is the one-time Maven dependency resolution in T001. The opt-in live
  checks (T020) only with the person's approval and on their machine.
- **Secrets**: test values are synthetic (`test-password-1` style, plus `ä€` for encoding cases); no
  real credential in any file.
- **Configuration**: never change `~/.config/inubit-mcp/*`, `~/.aws/*`, the person's keychain or the
  MCP registration.
- **Public repository**: no customer names in commit messages, PR descriptions or files; the
  identifier hooks must pass; never use `--no-verify`.

---

## Phase 1: Setup

- [X] T001 JNA dependency (research R-1, R-4): in `pom.xml` add `net.java.dev.jna:jna` `5.17.0`
  (core only, not relocated by the shade plugin); add it to `THIRD-PARTY-NOTICES.md` (Apache-2.0
  option of its dual license). Resolve once online, then build offline. Test first:
  `test/architecture/JnaDependencyTest.java` asserting that `com.sun.jna.Native` loads as a class
  without loading the native library (no `Native.load` call), and `MainProcessTest` still starts the
  shaded JAR. Add the boundary rule "`com.sun.jna` only in `adapter.os`" to `PackageBoundaryTest`
  (the process rule stays unchanged, R-2) and list `adapter/os` in `theScanSeesTheExistingDependencies`
  once the package exists (T003).

---

## Phase 2: Foundational — port and adapters (US1, US2)

- [X] T002 Port (R-2, data-model): `main/config/CredentialStore.java` (constant `SERVICE`,
  `StoreKind kind()`, `Lookup read(String account, Duration timeout)`), sealed `Lookup`
  (`Found(Secret)`, `NotFound`, `Failed(FailureKind, String detail)`), `StoreKind` with display
  names; `FailureKind` gains `STORE_NOT_AVAILABLE` and `TIMEOUT`; test double
  `test/config/FakeCredentialStore.java` (values per account, scripted delays and failures, call
  log). Tests first: `CredentialStoreTest` (`Found.toString()` never shows the value), `FakeCredentialStoreTest`.
- [X] T003 [P] `main/adapter/os/StoreGate.java` (+ `package-info.java`): process-wide fair lock,
  `read(Instant deadline, Supplier<Lookup>)` returns `Failed(TIMEOUT)` when the lock is not obtained
  before the deadline (R-8). Test first: `StoreGateTest` (one read at a time across threads; waiting
  counts against the deadline; fairness), Q8.
- [X] T004 [P] [US1] `main/adapter/os/KeychainStore.java` (R-3): `/usr/bin/security
  find-generic-password -s inubit-mcp -a <ACCOUNT> -g` through `ProcessLauncher`, environment `HOME`,
  `USER`, `LANG` only, stdin closed, stderr parsed (`password: "…"`, `password: 0x… "…"`, empty);
  the `password:` line is dropped before anything is logged or put into `Failed.detail`; exit codes
  first (0, 44, 128, 51, 36, 50, other), timeout destroys the process tree. Tests first:
  `KeychainStoreTest` against a fake executable printing recorded outputs (Q5): every outcome, strict
  UTF-8 for the hex form (malformed → `INVALID_VALUE`), arguments never contain a value, a `password:`
  line with an unexpected exit code never reaches the log (log capture), the timeout kills the child.
- [X] T005 [P] [US2] `main/adapter/os/SecretServiceStore.java` (R-5): executable from
  `/usr/bin/secret-tool`, `/usr/local/bin/secret-tool`, then `PATH`; environment allowlist
  (`DBUS_SESSION_BUS_ADDRESS`, `XDG_RUNTIME_DIR`, `DISPLAY`, `WAYLAND_DISPLAY`, `HOME`, `USER`,
  `LANG`, `PATH`); stdout = value (strict UTF-8), never logged; outcomes per R-5. Tests first:
  `SecretServiceStoreTest` against a fake executable (Q7): found, not found, not installed, no D-Bus,
  other stderr, timeout kills the process, no `INUBIT_*` variable reaches the child, stdout absent
  from the log.
- [X] T006 [P] [US2] `main/adapter/os/WindowsCredentialStore.java` + `CredApi` seam (R-4): JNA
  mapping of `CREDENTIALW`, `CredReadW`, `CredFree` (`W` functions only); target
  `inubit-mcp:<ACCOUNT>`, `CRED_TYPE_GENERIC`; blob copied, freed, decoded strict UTF-16LE, byte copy
  wiped; 1168 → not found, 1312 → not available, other → access denied; `LinkageError` → not
  available; read on its own thread, abandoned after the timeout. Tests first:
  `WindowsCredentialStoreTest` through a fake `CredApi` (Q6); the JNA call itself is covered only by
  the Windows live check (T020).
- [X] T007 `main/adapter/os/UnavailableStore.java` and `CredentialStores.forOs(String osName,
  ProcessLauncher)`: macOS → `KeychainStore`, Windows → `WindowsCredentialStore`, Linux →
  `SecretServiceStore`, other → `UnavailableStore` (every read `STORE_NOT_AVAILABLE`); every adapter
  goes through the shared `StoreGate`. Tests first: `CredentialStoresTest` (selection by `os.name`
  values; JNA not loaded on non-Windows), `PackageBoundaryTest` green.

---

## Phase 3: Configuration model (US3)

- [X] T008 [US3] Names and source type (R-6): rename `CredentialVariables` → `CredentialNames`
  (`nodeName`, `groupName`, `candidates`), `EffectiveNodeConfig.credentialVariables()` →
  `credentialNames()`, `SslContexts` updated; `SecretSourceConfig.Environment` →
  `SecretSourceConfig.CredentialStore(Optional<String> prefix)`, YAML `type: credentialStore`, the
  default effective source in `SecretPlan`. `ConfigLoader.checkSource` refuses `type: environment`
  with the removal message of contracts/configuration-delta.md (path, replacement, guide); unknown
  types list both. Tests first: `ConfigLoaderTest` (Q10), `SecretPlanTest` (default source is the
  credential store, overrides replace the whole source), existing naming tests renamed.
- [X] T009 [US3] Prefix keys (FR-005, R-6): `CredentialsConfig.prefix`; `ProfileConfig.credentialPrefix()`
  = `prefix`, else `envPrefix` (deprecation warning), else the default; `prefix` ≠ `envPrefix` →
  error; top-level `source.prefix` keeps 010's rule against the profile-wide prefix; overrides without
  their own `prefix` use `credentialPrefix()`. Tests first: `ProfileConfigTest`, `ConfigValidatorTest`
  (Q12).
- [X] T010 [US3] Name checks and leftover variables (FR-003, FR-005, R-10): the collision checks
  (other profile: error; same `profile.name`: warning; `e2e` node: error) on the
  `CredentialNames` of credential store nodes in `ConfigValidator`; `ProfileConfig.credentialNameSet()`
  (every kind of every node, under the prefix it would use as a credential store node);
  `main/config/LeftoverVariables.java` (one warning, names only; wired in T014). The intra-profile
  collision check and the removal of the "(typo?)" warning move to T014, together with
  `CredentialResolver`;
  `ConfigValidator` texts that name environment variables now name entries and `credentials.prefix`.
  Tests first: `LeftoverVariablesTest` (Q11; AWS nodes included, other profiles' names excluded),
  collision cases in `ConfigValidatorTest` (Q13).

---

## Phase 4: Lazy credentials for every node — the breaking step (US1, US2, US3)

- [X] T011 [US1] `SecretReader` split (R-7): extract the source-neutral cell logic of `NodeSecrets`
  (single flight, 30 s back-off, settle grace, `CredentialRules`, scrubber registration,
  `secret.fetch` record) from a `ParameterReader` holding today's Parameter Store code and texts;
  `NodeSecrets.timeout()` per source. No behaviour change: all existing `NodeSecretsTest` cases stay
  green unchanged, plus a test that the Parameter Store texts are byte-for-byte the same.
- [X] T012 [US1] `EntryReader` and `NodeSecrets.credentialStore(EffectiveNodeConfig, CredentialStore,
  SecretScrubber, Clock)` (R-7, R-11): main read per kind with node-to-group fallback by value (no
  fallback after `Failed` or an invalid value); trust store password only with `tls.trustStore`;
  trust store password with CLI → `AUTH_FAILED`; e2e cell always present, `Optional<E2eCredentials>`,
  one of two → `AUTH_FAILED`, e2e over `http://` → refused before sending; `E2eCredentials` moved to
  `config`; `secret.fetch` with `source`, `store`, `entries`; 60 s deadline of the whole node read
  through `StoreGate`. Tests first: `NodeSecretsCredentialStoreTest` (Q1–Q4, Q8, Q14, Q24 timed with
  25 ms per entry, Q25 log capture).
- [ ] T013 [US1] Wiring switch (R-7, R-9, R-14, R-17): `Wiring` gets a `CredentialStore` seam
  (production `CredentialStores.forOs`), builds `NodeSecrets` for every node; `TestWiring.of(...)`
  takes a store instead of a `CredentialResolution`; `AdapterGatewayFactory.awaitCredentials()` and
  the e2e function use `NodeSecrets.timeout()`; `CredentialGuard` of every authenticated client gets
  the node's `NodeSecrets` (the constructor without it stays for unauthenticated clients); audit
  account fallback "(credential store <account>)". Tests first: `AdapterGatewayFactoryTest` (Q22: a
  single-node call whose fake store answers after 30 s succeeds), `WiringE2eTest`, `WiringAuditTest`
  (Q26), all wiring tests moved to `FakeCredentialStore`.
- [ ] T014 [US3] Remove the environment path (FR-002, R-10, R-15): delete `CredentialResolver`,
  `CredentialResolution` and the deferred `NodeCredentials` placeholder; `Launcher` no longer resolves
  credentials at startup (only `LeftoverVariables`); missing username/password is no longer a startup
  error; move every test fixture that supplied credentials through environment maps to
  `FakeCredentialStore` (`ConfigValidatorTest`, `MainTest`, `ConfigSummaryTest`, …). Tests first:
  `NoEnvironmentCredentialsTest` (Q18: every derived variable set to a distinct value; a full tool
  run; none reaches a request, a StartCLI stdin or a result), `NoSecretLeakTest` extended to the
  credential store (Q19), `MainTest` (server starts without any store access).

---

## Phase 5: Time budgets (US1, US2)

- [ ] T015 `SecretTiming(Map<NodeId, Duration> fetchWait, Predicate<NodeId> held)`: allowance only
  while the node's credentials are not held, for both sources (FR-008); `HealthService` gets a
  `Function<NodeId, String>` with the pending text per node from `Wiring` (Parameter Store text
  unchanged). Tests first: `SecretTimingTest`, `InventoryServiceTest` (Q9; Q23 re-read inside a
  fan-out → branch `TIMEOUT`, next call uses the result), `HealthServiceTest`.

---

## Phase 6: User Story 5 — rotation and messages (Priority: P2)

- [ ] T016 [US5] Messages (FR-010, R-12): `SecretLabels` credential store variant (labels, next steps
  with the system command, "no restart is needed"); the environment variant and "restart the MCP
  client from a shell" deleted; `CliOutputClassifier` login-failure text via `SecretLabels`; error
  codes per `FailureKind` mapping. Re-read for credential store nodes through the guard (Q16). Tests
  first: `SecretLabelsTest`, `CliOutputClassifierTest`, `CredentialGuardTest` (Q16, Q17; changed →
  next call uses it; unchanged → 60 s pause; ≤ 2 failed logins / 60 s; re-read bounded by 60 s).

---

## Phase 7: User Story 4 — `--check-config` (Priority: P2)

- [ ] T017 [US4] `main/config/EntryCheck.java` and `ConfigSummary` (FR-011, R-13): per node and kind
  the found name or both names with `ok | not found | invalid value | access denied | not checked`;
  each distinct account read once; existence-only lookups where the store allows; trust store with
  CLI and e2e over `http://` as errors; first `STORE_NOT_AVAILABLE`/`TIMEOUT` → one warning per store,
  the rest `not checked`; `Credential store entries:` line and `secrets: credentialStore (<store>)` on
  every node line; exit codes. Tests first: `EntryCheckTest` (Q27), `ConfigSummaryTest`, `MainTest`
  (Q15; never a value in the output).

---

## Phase 8: User Story 6 — live tests (Priority: P3)

- [ ] T018 [US6] `LiveTarget` hands the real store (`CredentialStores.forOs`) and `SsmParameterStore`
  to `TestWiring` (R-14); `LiveTargetTest` checks the wiring for both sources (Q20).
- [ ] T019 [US6] `test/live/CredentialStoreLiveTest.java`, Maven profile `live-store`, enabled only
  with `INUBIT_LIVE_STORE=1` (FR-017): expects the documented entries of the fictitious profile
  `livetest`, checks found (value with `ä€`), not found, and records exit codes / texts for R-3 and
  R-5; skipped in the default build (assert in `LiveTestsAreOptInTest` or equivalent).

---

## Phase 9: Polish & Cross-Cutting Concerns

- [ ] T020 Live check execution (SC-001, SC-007): with the person's approval, they run
  `INUBIT_LIVE_STORE=1 mvn -q verify -Plive-store` on macOS (required before the release) and, where
  available, Windows and Linux, plus the manual access-denied steps; record per system the outcome
  and the observed exit codes / texts in `quickstart.md`; adjust R-3/R-5 mappings if they differ (with
  tests).
- [ ] T021 [P] `docs/setup.md` section 4 rewritten (R-16): one general part (sources, first use,
  checking, changing a credential without restart), "Credential store" (entry identity per system;
  create/change/delete commands without the value on a command line; what an access permission
  grants; duplicates; Linux prerequisites `secret-tool` and a Secret Service; the Linux "unlock
  prompt dismissed" caveat if T020 confirms it), Parameter Store unchanged; section 6
  (`--check-config`) and section 7 (registration without a start script). Every quoted value checked
  against the code.
- [ ] T022 [P] `docs/migration-environment-variables.md` (new): from shell exports (per system),
  from the macOS start script (register the JAR directly, entries stay, one dialog per entry at most),
  changed e2e behaviour, `envPrefix` → `prefix`, leftover-variable warning; note in
  `docs/migration-001-to-002.md`; `Format001Detector` hint uses `credentials.prefix`; `Launcher`
  `--help` text. Tests first for the code parts: `Format001DetectorTest`, `LauncherTest` (help text).
- [ ] T023 [P] `docs/tools.md` (errors and next steps from contracts/mcp-tools-delta.md, `get_health`
  pending text, fan-out deadlines), `docs/live-tests.md` (entries for `livetest`, both sources),
  `README.md` (installation steps 4 and 7 without variables, safety summary), `SECURITY.md` (sources),
  `CHANGELOG.md` `[0.7.0]` with a prominent breaking-change note and the systems the live check ran on.
  Every quoted value checked against the code.
- [ ] T024 Validate per quickstart.md: `mvn -q -o clean verify`; `mvn -q -o
  -Dtest=NoCustomerIdentifiersTest test` with the local lists (it must run, not skip); search that
  `System.getenv` appears only where R-10 allows; record the outcome (counts only) in `quickstart.md`.

---

## Dependencies & Execution Order

- T001 first (shares `pom.xml` and `PackageBoundaryTest`). T002 next; then T003–T006 in parallel;
  T007 after T003–T006.
- Phase 3: T008 after T002; T009 and T010 after T008 (T010 after T009, both touch `ConfigValidator`).
- Phase 4: T011 after T002; T012 after T007, T008, T011; T013 after T012; T014 after T010 and T013
  (the build is green after every task; the environment path is removed only in T014).
- T015 after T013. T016 after T013. T017 after T012 and T010. T018 after T014; T019 after T007.
- Polish: T021–T023 in parallel after T017; T020 needs T019 and the person; T024 last.

## Parallel Example

```text
Phase 2:  T003 StoreGate | T004 KeychainStore | T005 SecretServiceStore | T006 WindowsCredentialStore  →  T007
Phase 4:  T011 → T012 → T013 → T014
Phase 9:  T021 setup.md | T022 migration guide | T023 tools/README/SECURITY/CHANGELOG
```

## Implementation Strategy

- **MVP**: Phases 1–4. After Phase 4 every node reads the credential store (or Parameter Store) on
  first use and no environment variable is read; this is the breaking change and must not be released
  without Phases 6, 7 and 9 (messages, `--check-config`, docs and migration guide).
- Then time budgets, rotation and messages, `--check-config`, live tests, docs.
- Run each task through the implementer → reviewer loop; every commit stays green because each task
  carries its own tests.
