# Tasks: INUBIT 9 Support

**Input**: Design documents from `specs/017-inubit-9-support/` (spec, plan, research D-1…D-23,
data-model, contracts, quickstart, reviews)

**Tests**: mandatory (constitution Principle III, TDD): each implementation task starts with its
failing test in the same task. No test contacts INUBIT; 9.0 behaviour is tested against
`src/test/resources/fixtures/v9_0/` and fake StartCLI clients (`FakeProcessLauncher`).

**Build**: `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-017-tmp` (tmpdir
outside the checkout; never two builds in parallel); Python: `python3 -I -m unittest discover -s
tools/tests`. Paths below are relative to `src/main/java/de/dadecker/inubit/mcp/` (main) and
`src/test/java/de/dadecker/inubit/mcp/` (test) unless they start with `src/`, `docs/`, `tools/`.

**Reviews**: after each phase a `code-review` reviewer checks the phase diff; findings and
decisions go to `specs/017-inubit-9-support/reviews.md` ("Implementation — phase N").

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [ ] T001 Baseline: `mvn -o -q clean verify` with the tmpdir above is green on the branch (merged
  `origin/0.8.0`); note the test count in `reviews.md` as the SC-003 reference

## Phase 2: Foundational — version model and matrix (plan stage 1; blocks every story)

- [ ] T002 [P] `MajorMinor` per data-model.md ("`int major, int minor`; `parse(String)`;
  `of(InubitVersion)` (empty without minor); `text()` = `"M.m"`"; `parse` accepts only
  `^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$`) with tests in `domain/model/MajorMinor.java`,
  test `domain/model/MajorMinorTest.java`
- [ ] T003 [P] `VersionSetting.V9_0 = Line(9, OptionalInt.of(0))`, `parse("V9_0")`; the load error
  text lists "AUTO, V6_1, V8_1, V9_0, V9_X or a full version" (D-9); `V9_X` unchanged; tests in
  `domain/model/VersionSettingTest.java` and `config/ConfigLoaderTest.java`; main
  `domain/model/VersionSetting.java`, `config/ConfigLoader.java`
- [ ] T004 `CapabilityMatrix.production()`: 9.0 entry `READS_UNVERIFIED` → `ALL_SUPPORTED` (same
  states and channels as 8.1, REST and CLI); `+ boolean declares(MajorMinor)`; `V9_X`/unknown
  unchanged (D-9). Replace `CapabilityMatrixTest.readsAreUnverifiedAndWritesUnsupportedOn90` by
  the 9.0 expectations; `declares` true for 6.1, 8.1, 9.0, false for 9.1/7.0 — main
  `domain/model/CapabilityMatrix.java`, test `domain/model/CapabilityMatrixTest.java`
- [ ] T005 D-20 migration: in every test class that uses `9.0.7`/the 9.0 entry as the example of an
  undeclared version (≈31 files, `grep -rln "9\.0\.7" src/test/java`), move the example to
  `9.1.0` with assertions unchanged; tests whose subject is 9.0 itself get the new expectation
  (T004). List every touched class in `reviews.md` (SC-003 permits exactly these changes)
- [ ] T006 [P] `PortSetRegistry`: register `VersionRange(9, 0, 9, 0)` → `V81PortSet::create`, no CLI
  health factory (D-14); test that 9.0.7 selects that range and an `AUTO` slot moving 8.1 → 9.0
  rebuilds its ports without network access — main `adapter/PortSetRegistry.java`, `Wiring.java`
  (registration site), test `adapter/PortSetRegistryTest.java`

**Checkpoint**: build green; code-review phase 2.

## Phase 3: User Story 2 – One StartCLI client per INUBIT version (P1)

**Goal**: `defaults.cliClients` chooses client, JDK and default address by the node's version; a
client check belongs to the client it checked (FR-003…FR-006, FR-010, FR-012, FR-013, FR-015,
FR-017).
**Independent test**: profile with 8.1, 9.0 and `AUTO` groups and two fake clients (8.1/JDK 17,
9.0/JDK 21): a StartCLI-backed tool on each node launches the client and JDK of its version; an
`AUTO` node switching 8.1 → 9.0 uses the 9.0 client without reusing the 8.1 check.

### Configuration (plan stage 2)

- [ ] T007 [US2] `CliClient(Path home, Optional<Path> javaHome)` ("`home` required; path binding as
  `cliHome`"), `Defaults.cliClients: Map<MajorMinor, CliClient>` (empty by default, only in
  `defaults`), `CliClientsDeserializer` ("key pattern `^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$`;
  duplicate key → load error with the path `defaults.cliClients.<key>`; no echo of values other
  than the key"; unknown entry fields fail; no global `STRICT_DUPLICATE_DETECTION`). Tests: quoted
  and unquoted `8.1:`/`9.0:` keys load; `9.00`, `9`, `9.x`, `V9_0` rejected with the path;
  duplicate rejected; missing `home` rejected; `cliClients` under a group and under a node
  rejected (unknown field, path named, entry values not echoed); an undeclared key (`"9.1"`)
  loads — main
  `config/CliClient.java`, `config/CliClientsDeserializer.java`, `config/Defaults.java` (or the
  existing defaults record), test `config/CliClientsConfigTest.java`
- [ ] T008 [US2] `EffectiveNodeConfig`: `CliSources` (fields per data-model.md), `CliChoice` ("`Cli
  cli`, `HomeSource source` (`NODE`, `GROUP`, `VERSION_ENTRY`, `DEFAULTS`, `NONE`),
  `Optional<MajorMinor> entry`, `AddressKind address` (`SERVLET_PATH`, `BASE_URL`, `CONFIGURED`),
  `OptionalInt major`, `boolean versionKnown`"; "`entry` present iff `source == VERSION_ENTRY`"),
  `cliFor(Optional<InubitVersion>)` with the D-3 table (node/group home wins; entry `M.m` only
  for a version with minor and a line that is not `V9_X`-like; JDK node → group → entry
  `javaHome` (only with the entry's home) → `defaults.cliJavaHome`; address explicit `cli.url` →
  servlet path for major < 9 or unknown → `<baseUrl>` for major ≥ 9; major from the version else
  from the line pin), `withCli(Cli)` keeping `cliSources`, `cli()` = `cliFor(<pinned version>)`
  (`AUTO`: version-less). Table-driven tests for every D-3 row, `V9_X` + observed 9.0 never picks
  an entry, unknown version = 0.8.0 values, `baseUrl` with context path — main
  `config/EffectiveNodeConfig.java`, `config/CliChoice.java`, test
  `config/EffectiveNodeConfigCliForTest.java`

### CLI selection (plan stage 3)

- [ ] T009 [US2] `CliRunner`: constructor `+ Function<NodeId, Optional<InubitVersion>> versions`;
  `Selection select(EffectiveNodeConfig, Optional<InubitVersion>)` (server with the chosen `Cli`,
  `CliChoice`, `ClientKey`, address warning D-19); `run`, `runSession`, `execute` and
  `checkAvailable` read the version once and select once; `launchTarget`, Windows refusals (013),
  `javaHome`, trust store and `HandshakeStartCliPinProbe` see the chosen values; debug log of the
  chosen client directory and address kind per launch (no URL). Tests with `FakeProcessLauncher`:
  8.1 node → 8.1 client + servlet URL; 9.0 node → 9.0 client + JDK 21 + `<baseUrl>`; explicit
  `cli.url` kept; `select` idempotent — main `adapter/cli/CliRunner.java`, test
  `adapter/cli/CliRunnerSelectTest.java`
- [ ] T010 [US2] Windows (US2 scenario 7, D-21): with 013's platform fake, a 9.0 entry home
  launches its `startcli.bat`; a UNC entry home and refused characters in an entry path are
  refused as for `cli.home` — test `adapter/cli/CliRunnerWindowsSelectTest.java`; production
  fixes, if any, in `adapter/cli/CliRunner.java` (therefore not parallel to T011/T012)
- [ ] T011 [US2] FR-005 texts (D-8): no client → `CLI_UNAVAILABLE` naming the version and
  "Add `defaults.cliClients."<M.m>".home` (the INUBIT <M.m> client directory) or set cliHome"
  only when the version has a minor and (`cliClients` configured or major ≥ 9), else the 0.8.0
  text byte-identical; client range excludes → the 009 `UNSUPPORTED_VERSION` next step gains
  "or add `defaults.cliClients."<M.m>"`" under the same condition; no-JDK hint "(8.1: JDK 17, 9.0:
  JDK 21)". Tests for both branches of the condition (`TemplateGrammarTest` stays green) — main
  `adapter/cli/CliRunner.java`, `application/VersionGate.java`, tests
  `adapter/cli/CliRunnerMessagesTest.java`, `application/VersionGateTest.java`
- [ ] T012 [US2] `CliOutputClassifier`: `AUTH_FAILED` next step adds "On INUBIT 9 the user must
  exist in the internal user management; users that log in only through OAuth cannot use
  StartCLI" when `CliChoice.major` ≥ 9 (any address kind); 8.1 text unchanged (D-16, FR-013) —
  main `adapter/cli/CliOutputClassifier.java`, `adapter/cli/CliRunner.java`, test
  `adapter/cli/CliOutputClassifierTest.java`
- [ ] T013 [P] [US2] `V61Connection` selects at its start and uses the chosen URL;
  `HandshakeStartCliPinProbe` uses the chosen URL; 6.1 tests unchanged and green — main
  `adapter/cli/v61/V61Connection.java`, `adapter/cli/HandshakeStartCliPinProbe.java`, tests in
  the existing `adapter/cli/v61/` and pin-probe test classes
- [ ] T014 [US2] `CliVersionProbe.probe(node, version, timeout)` selects by the given version and
  returns `ProbedClient`; range pattern `CASE_INSENSITIVE` (D-15); test with
  `src/test/resources/fixtures/v9_0/cli/version.*` (lower-case "inubit") and the 8.1 fixture —
  main `adapter/cli/CliVersionProbe.java`, `domain/port/ClientVersionPort.java`, test
  `adapter/cli/CliVersionProbeTest.java`

### Client check per client (plan stage 4, FR-017)

- [ ] T015 [US2] `ClientKey(String home, String javaHome)` ("`""` = none"; value equality; no URL,
  no credentials), `ProbedClient(ClientKey key, ClientVersion version)`, `NodeVersionState` with
  `clients: Map<ClientKey, Timed<ClientVersion>>`, `clientFailures: Map<ClientKey,
  Timed<ErrorCode>>`, `withClient`, `withClientFailure`, `needsClientProbe(Instant, ClientKey)`,
  `clientFor(ClientKey)` ("a result or failure of one key never touches another key's; the 60 s
  back-off applies per key; A→B→A reuses A") — main `domain/model/ClientKey.java`,
  `domain/model/ProbedClient.java`,   `domain/model/NodeVersionState.java` (signatures as data-model.md: `withClient(ProbedClient,
  Instant)`, `withClientFailure(ClientKey, ErrorCode, Instant)`; existing `Timed`), test
  `domain/model/NodeVersionStateClientsTest.java` (existing `NodeVersionStateTest` stays green)
- [ ] T016 [US2] Port `ClientCheckLookup` (`Optional<ClientCheck> check(NodeId, ClientKey)`,
  `boolean anyCheck(NodeId)`; read-only) with new `ClientCheck` ("a recorded `ClientVersion` or a
  recorded failure code"); `NodeVersions` implements it and gets
  `clientProbed(NodeId, ProbedClient)`, `clientProbeFailed(NodeId, ClientKey, ErrorCode)`,
  `needsClientProbe(NodeId, ClientKey)` — main `domain/port/ClientCheckLookup.java`,
  `domain/model/ClientCheck.java`, `application/NodeVersions.java`, test
  `application/NodeVersionsTest.java`
- [ ] T017 [US2] Launch guard in `CliRunner.run`/`runSession` (D-4; not `execute`, not
  `checkAvailable`, never probes): result for the chosen key excludes the selected version →
  009 `UNSUPPORTED_VERSION`; not evaluable or recorded failure → launch; no check for the key but
  one for another key → `CLI_UNAVAILABLE` "The StartCLI client for <node> changed to the client
  for INUBIT <M.m> during this call; the new client has not been checked yet" / "Call the tool
  again", nothing launched; no check for the node → launch as in 0.8.0. Tests incl. the race
  (version source changes between admission and `run`), a 6.1 session starting before any
  check, and the bypass: with a check recorded for key A only, `CliVersionProbe` (`-v`) and
  `CliClientProbe` (`-h`) for key B still execute, and `checkAvailable` is not guarded — main `adapter/cli/CliRunner.java`, test `adapter/cli/CliRunnerLaunchGuardTest.java`
- [ ] T018 [P] [US2] `CliClientProbe` (012, `-h`) caches per `(NodeId, ClientKey)` — main
  `adapter/cli/CliClientProbe.java`, test `adapter/cli/CliClientProbeTest.java`
- [ ] T019 [US2] Port `ClientSelectionPort` (`current`, `available`, `warning` with the caller's
  version; `anyCandidate(NodeId)`: pinned with minor → only its own choice, pin without minor →
  only version-less sources, `AUTO` → every entry; no node contact, no launch) and
  `CliClientSelector` in `adapter/cli` from `CliRunner.select`, the existence predicate and the
  Windows flag; `warning` = D-19 text for an explicit servlet `cli.url` at major ≥ 9 — main
  `domain/port/ClientSelectionPort.java`, `adapter/cli/CliClientSelector.java`, test
  `adapter/cli/CliClientSelectorTest.java`
- [ ] T020 [US2] `VersionGate`: `withClient` predicate → `ClientSelectionPort` with the gate's
  snapshot; probe with that version, judge only that key's result; add `warning` to the admission
  warnings when the channel includes CLI. Tests: `AUTO` 8.1 → 9.0 probes the 9.0 key and does not
  reuse the 8.1 result; back-off per key; servlet warning on a pinned 9.0 node with explicit
  servlet `cli.url` — main `application/VersionGate.java`, test `application/VersionGateTest.java`
- [ ] T021 [US2] `HealthService` (D-6): select with the version this call observed; re-probe within
  the remaining budget when the key differs from the probed one, else `client` unavailable "the
  StartCLI client for INUBIT <version> on <node> has not been checked yet; call get_health
  again"; test with two overlapping calls reporting their own observation — main
  `application/HealthService.java`, test `application/HealthServiceClientKeyTest.java`
- [ ] T022 [US2] `Wiring`: `CliRunner` gets `NodeVersions::effectiveVersion` and `NodeVersions` as
  `ClientCheckLookup`; `ClientSelectionPort` replaces the static `withClient` set for
  `VersionGate`, `HealthService`, `list_nodes`; tool announcement (`export_artifacts`,
  `export_archives`) uses `anyCandidate` — main `Wiring.java`, tests `StartCliClientWiringTest.java`,
  `ToolAnnouncementTest.java`

### Operator view (plan stage 6)

- [ ] T023 [P] [US2] `JdkRelease.major(Path javaHome, Function<Path, Optional<String>> reader)`
  ("reads `JAVA_VERSION="<n>…"` of `<javaHome>/release`; `"1.8…"` → 8; unreadable → empty") —
  main `config/JdkRelease.java`, test `config/JdkReleaseTest.java`
- [ ] T024 [US2] `ConfigValidator` warnings per contracts/configuration-delta.md: undeclared key,
  missing entry `home`/`javaHome` directory, JDK major ≠ 17/21 for 8.1/9.0 entries and pinned
  nodes (unknown major: no warning), `V9_X` "versionLine V9_X of <node> does not select 9.0
  support; use V9_0 (INUBIT 9.0.x) or AUTO", pinned ≥ 9 node with servlet `cli.url` (FR-010),
  Windows checks (013) over entry paths — main `config/ConfigValidator.java`, test
  `config/ConfigValidatorCliClientsTest.java`
- [ ] T025 [US2] `--check-config`: each distinct client `(home, javaHome)` probed once with `-v`
  (local); entry whose range excludes its key reported; `ConfigSummary` node line `client: <dir>
  (<source>), address: <kind>` (`AUTO`: version-less choice + "chosen at runtime") and one line
  per distinct client with its `-v` result and JDK major; no URL, no credentials — main
  `Launcher.java`, `config/ConfigSummary.java`, tests `config/ConfigSummaryTest.java`,
  `MainTest.java` (check-config cases)
- [ ] T026 [US2] `list_nodes.startCli` (D-18): `NodeSummary.StartCli` (`clientDirectory?`,
  `source`, `versionEntry?`, `address`, `boolean finalChoice` rendered as JSON `"final"`; tests
  assert the model field and the serialized/schema name), `NodeOverview` fills it from the current
  effective version without node contact, `cliAvailable`/allowed capabilities via `available`;
  schema `src/main/resources/schemas/list_nodes.output.json` (additive, `versionLine` description
  lists `V9_0`) — main `domain/model/NodeSummary.java`, `application/NodeOverview.java`,
  `mcp/tools/ListNodesTool.java`, tests `mcp/tools/ListNodesToolTest.java` (incl. its schema
  check), `application/NodeOverviewTest.java`

**Checkpoint**: build green; code-review after T014 (stages 2–3), after T022 (stage 4) and after
T026 (stage 6).

## Phase 4: User Story 1 – Work with INUBIT 9.0 nodes like with 8.1 nodes (P1)

**Goal**: every tool runs on 9.0 as on 8.1 (FR-001, FR-002, FR-004, FR-007, FR-008, FR-011,
FR-014).
**Independent test**: with 9.0 fixtures and a fake 9.0 client, each tool on a node pinned `V9_0`
and on an `AUTO` node detected as 9.0.7 runs without version warning and with the 8.1 result
shape.

- [ ] T027 [US1] `InubitHttpClient`: `enum MediaRange {XML, JSON, ANY}` (`XML` =
  `application/xml, */*;q=0.1`, `JSON` = `application/json, */*;q=0.1`, `ANY` = `*/*`);
  `get`/`post`/`getWithoutCredentials` take it (overloads keep `RESOURCE`/`ENDPOINT`). Test: the
  header sent equals the range (existing `InubitHttpClientTest` stays green) — main
  `adapter/rest/InubitHttpClient.java`, test `adapter/rest/InubitHttpClientAcceptTest.java`
- [ ] T028 [US1] Adapters pass the D-11 range: `/healthcheck`, `/ready`, `/metrics?format=json`,
  `/log/{name}` → JSON; `/system/info`, `/model/models`, `/model/modelByName/{name}` → XML;
  `/model/export/{name}` → ANY; the 8.1 contract tests additionally assert the header — main
  `adapter/rest/v81/{V81VersionDetector,V81MonitoringAdapter,V81MaintenanceProbe,LogEndpoint}.java`,
  `adapter/V81InventoryAdapter.java` (and the model-export caller), tests in their existing test
  classes (`V81VersionDetectorTest`, `V81MonitoringAdapterTest`, `V81MaintenanceProbeTest`,
  `adapter/V81InventoryAdapterTest`)
- [ ] T029 [US1] JSON error bodies (D-12): a JSON body with a string `message` becomes the excerpt
  (scrubbed, length-capped as today); status mapping unchanged; 401 ignores the body — main
  `adapter/rest/InubitHttpClient.java`, test `adapter/rest/InubitHttpClientJsonErrorTest.java`
- [ ] T030 [US1] Red: test-support registry `V90Cases` (the only way the new 9.0 contract tests
  open a fixture; opening a case without its `<case>.origin` fails) and the 9.0 contract tests
  through it, written first and failing for the missing fixtures: REST adapters (detector,
  monitoring, inventory, logs, model export) parse the 9.0 fixtures; errors 401/403/404
  (resource and endpoint)/500/503 map to the 8.1 codes with scrubbed excerpts; no request path
  contains `//`, `/./`, `/../` (D-13); CLI outputs classified as on 8.1 (export, import, tag,
  processErrorStart, kill, login_failed, unreachable, unknown_command, ps); `-h` via
  `CliClientProbe` — tests `testing/V90Cases.java`, `adapter/rest/V90RestContractTest.java`,
  `adapter/cli/V90CliContractTest.java`
- [ ] T031 [US1] Green: documentation-derived 9.0 fixtures (D-10) under
  `src/test/resources/fixtures/v9_0/rest/` (healthcheck, ready, system_info, metrics, log GET/POST,
  model_models, modelByName, model_export, errors 401/403/404/500/503) and `cli/` (`-v`, `-h`,
  processErrorStart, kill, unknown_command, login_failed, unreachable, ps, export, import, tag),
  each with `<case>.origin` = `reconstructed <documentation section>`; the files 012 already
  placed there (`cli/version.*`, `rest/system_info.*`) get their `.origin` (as stated in the
  existing `README.md`) and become `V90Cases` cases, their current readers
  (`CliVersionProbeTest`, `SystemInfoXmlParserTest`, `FindProcessesToolTest`,
  `GetInventoryItemToolTest`) keep working; production fixes only where a T030 test shows a 9.0
  difference; no real customer names (acme, `*.example.test`) — `src/test/resources/fixtures/v9_0/`,
  `README.md` there; T030 tests turn green
- [ ] T032 [US1] Tool level on 9.0: node pinned `V9_0` and `AUTO` node detected as 9.0.7: read
  tools without "not verified" warning; `restart_process`, `kill_process`, development writes,
  `run_e2e_test` admitted; `deploy_release` 9.0 ↔ 9.0 admitted, 8.1 ↔ 9.0 refused (same
  major.minor rule); StartCLI launched with `<baseUrl>` — tests
  `application/V90AdmissionTest.java`, `application/DeployServiceVersionTest.java` (9.0 cases)

**Checkpoint**: build green; code-review phase 4 (plan stage 5).

## Phase 5: User Story 3 – Existing profiles keep working (P2)

**Goal**: FR-009, SC-003: profiles without `cliClients` behave as in 0.8.0.
**Independent test**: every 0.8.0 configuration example loads unchanged with identical effective
StartCLI values; 8.1 and 6.1 suites pass.

- [ ] T033 [US3] Compatibility test: every profile example of `docs/setup.md`, `README.md` and the
  existing config test resources loads; for 8.1, 6.1 and `AUTO` nodes `cli()` (home, javaHome,
  url) equals the 0.8.0 value (golden values computed with the 0.8.0 rule in the test); `V9_X`
  loads (FR-011); explicit `cli.url` on a 9.0-detected node kept (US3 scenario 2) — test
  `config/CliCompatibilityTest.java` (extend `MigrationGuideExampleTest` if it already parses the
  docs)

**Checkpoint**: build green (SC-003 count ≥ T001 minus replaced tests, listed in `reviews.md`);
code-review phase 5.

## Phase 6: Recording and release guard (plan stage 7; US1, SC-006)

- [ ] T034 [US1] `tools/record-fixtures.sh`: `RECORD_INUBIT_LINE=9.0` → output
  `fixtures/v9_0/rest|cli`, CLI URL `<baseUrl>`, each REST case with its D-11 `Accept`; new
  read-only cases `help` (`-h`), `rest_auth_failed` (GET `/system/info` with a dummy password),
  `rest_not_found` (modelByName of a non-existent name); `--list` prints the 9.0 catalogue; every
  recorded case writes `<case>.origin` = `recorded <date> <INUBIT version>`; 8.1 default
  unchanged — `tools/record-fixtures.sh`, tests `tools/RecordFixturesCatalogueTest.java` (and
  `tools/tests/` if the script's Python helpers change)
- [ ] T035 [US1] `fixtures/v9_0/NOT_RECORDABLE.txt` (write successes import, tag,
  processErrorStart, kill; REST 403, 500, 503; each with its reason) and
  `V90FixtureCatalogueTest`: catalogue (`record-fixtures.sh --list` for 9.0) ∪ NOT_RECORDABLE =
  fixture/`.origin` files = `V90Cases` cases (both directions); a missing `.origin` fails; the
  NOT_RECORDABLE set is asserted exactly — test `tools/V90FixtureCatalogueTest.java`
- [ ] T036 [US1] `V90RecordedFixturesTest`: build version from `BuildInfo` is a release (`SemVer`
  without pre-release) and a catalogue case's `.origin` is not `recorded` → fail naming the cases;
  pre-release → skipped with the list — test `tools/V90RecordedFixturesTest.java`

**Checkpoint**: build + Python tests green; code-review phase 6.

## Phase 7: Polish (plan stage 8)

- [ ] T037 [P] Docs: `docs/setup.md` (`cliClients`, default address by major, JDK 17/21, migration
  8.1 → 9.0, `V9_0` vs `V9_X`), `docs/tools.md` (`list_nodes.startCli`, 9.0 support, servlet
  warning, `CLI_UNAVAILABLE` "call again"), `README.md` (supported versions),
  `docs/research/inubit-interfaces.md` §4 (what 017 relies on, D-11/D-12)
- [ ] T038 [P] `CHANGELOG.md`: section `0.8.1 (unreleased)`; `pom.xml` version unchanged (D-23)
- [ ] T039 Full verification (mvn + Python), quickstart offline table checked row by row and the
  validation record filled in `specs/017-inubit-9-support/quickstart.md`; final code-review over
  `git diff origin/0.8.0...HEAD`; findings in `reviews.md`

## Dependencies

- Phase 2 blocks everything.
- US2: T007 → T008 → T009 → T010 → (T011, T012) (all touch `CliRunner.java`, sequential);
  T009 → (T013, T014); T015 → T016 → T017; T015 → T018; T014 + T016 → T019 → T020 → T021 →
  T022; T008 → T023 → T024; T014 + T024 → T025; T019 + T022 → T026.
- US1 (Phase 4): T027 → T028 → T029 (all `InubitHttpClient`/adapters); T030 → T031 (T031 also
  needs T027–T029 for the header/JSON-error cases and T018 for `-h`); T032 needs T009 (address by
  major), T020 (gate on 9.0 clients) and T031.
- US3 (Phase 5) needs T008 and T024.
- Phase 6: T031 → T034 → T035 → T036.
- Phase 7 needs all.

## Parallel opportunities

T002/T003/T006; T013/T014/T018/T023 (different files, once their prerequisites are done);
T027–T030 can start after Phase 2 alongside US2; T037/T038. Builds always sequential.

## Implementation strategy

MVP = Phase 2 + US2 configuration and selection (T007–T014): 9.0 nodes get their own client and
address. Then the per-client check (T015–T022), 9.0 REST (Phase 4), operator view, compatibility
proof, release guard and docs. Each phase ends green and reviewed.
