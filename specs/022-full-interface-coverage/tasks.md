# Tasks: Full Coverage of the StartCLI and REST Interfaces

**Input**: Design documents from `specs/022-full-interface-coverage/`: spec, plan, research
D-1…D-27 and L-1…L-18, data-model, `contracts/mcp-tools-delta.md`,
`contracts/configuration-delta.md`, `contracts/coverage-list.md`, quickstart, reviews/.

## Conventions

**Tests**: mandatory (constitution Principle III, TDD). Each implementation task starts with the
failing tests it names, observed to fail, and is done when they pass and the build is green.

- No offline test contacts INUBIT. StartCLI runs are replayed by `ScriptedProcessLauncher` /
  `FakeProcessLauncher`; session runs use scripted stdin/stdout pairs; REST runs use WireMock
  (`RestFixtures`).
- Contract tests run against recorded, neutralized fixtures under
  `src/test/resources/fixtures/{v8_1,v9_0}/{cli,rest}/admin-*`. Every 9.0 case has a `.origin`
  and is listed in the `V90Cases` catalogue.

**Build**:
- `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-022-tmp`: tmpdir outside
  the checkout; never two builds in parallel.
- Python: `python3 -I -m unittest discover -s tools/tests`.
- Paths are relative to `src/main/java/de/dadecker/inubit/mcp/` (main) and
  `src/test/java/de/dadecker/inubit/mcp/` (test), unless they start with `src/`, `docs/`,
  `specs/`, `tools/`, `pom.xml`, `README.md`, `SECURITY.md` or `CHANGELOG.md`.

**Secrets (SC-005, Principle II)**: every task that adds a message, warning, result field, log
line, audit record, argv or session line runs a "leak check" with `testing/TestSecrets`. The
check asserts that none of these appears there:
- a configured password;
- a secret-entry value;
- a planted secret value in the INUBIT answer (configuration leaf, module property, `encrypted`
  value, key material, user e-mail in a listing);
- upload content.

**Customer-neutral**: only fictitious names (`acme`, `globex`, `test`, `prod`, `node1`, `node2`,
`*.example.test`) in code, tests, fixtures and docs. No customer or host name in commits or PR
texts. The pre-commit hook blocks customer identifiers; never commit with `--no-verify`.

**Version state** (D-2, D-26): every new coverage row starts `planned` for 8.1 and 9.0, or
`undocumented` where the vendor does not document it. Every new capability is `UNSUPPORTED` with
the note "not yet verified" in `CapabilityMatrix.production()`.
- A row flips to `reachable` only in the commit that adds its neutralized recording, together with
  its contract test.
- Offline tests that need an admitted node use a test coverage file and the non-production
  `CapabilityMatrix` constructor, never the production declarations.

**Gates**:
- **[OWNER]**: needs the owner's decision or review (for example of a neutralized fixture diff)
  before it is done.
- **[LIVE]**: runs against a real INUBIT server.
  - Only non-production test servers.
  - Executed by the operator, or by the agent only after the operator approved that concrete
    action in the chat: server, command, private output folder.
  - **[LIVE-W]** changes the server and needs an approval per command (FR-026).
  - Raw outputs stay in a private directory outside every repository; only neutralized fixtures
    enter the repository.
- **[BLOCKED: Tnnn]**: cannot start before task Tnnn is done.

The implementer stops at a gate and reports what is needed.

**Increments and PRs** (D-27, FR-004): each story phase ends with a PR from this branch to `main`.
- The PR text is short, in English, with no customer name and no AI hint.
- The PR is merged after green CI, then this branch is rebased onto `main` before the next phase.
- Phases 1 and 2 ship with the US1 PR.
- Feature 021 must be merged first.

**Reviews**: each task goes through the implementer → reviewer loop
(`d4ld4r-speckit-duo:implement`). After each phase there is a code review of the phase diff.
Findings and decisions go to `specs/022-full-interface-coverage/reviews/implementation.md`.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [ ] T001 Baseline and record, in this order:
  1. Confirm feature 021 is merged into `main` and this branch is rebased onto it.
  2. Run `mvn -o -q clean verify` with the tmpdir above, plus the Python tests: green.
  3. Create `specs/022-full-interface-coverage/reviews/implementation.md`.
  4. Note there the test count, the exact tool list of `ToolAnnouncementTest` (SC-009 reference)
     and the three plan defaults in force unless the owner overrides them:
     - US5 as rewritten;
     - `get_server_settings` as its own tool;
     - version deletions offered.
- [ ] T002 [P] Fixture layout (D-26): add an "Administration cases (feature 022)" section to
  `src/test/resources/fixtures/v8_1/cli/README.md` and `src/test/resources/fixtures/v9_0/README.md`.
  - Case names: `admin-<entry-slug>[-<variant>]`.
  - CLI cases: `.stdout`/`.stderr`/`.exit`; session cases also `.stdin` (with the password line
    replaced by `<password>`).
  - REST cases: `.http` + body.
  - 9.0 `.origin` formats: `recorded <date> <version> neutralized` / `reconstructed <source>`.
  - Rule: every user, workflow, host and value is fictitious, and every secret is replaced by a
    planted test secret.
  - No case files yet.
- [ ] T003 [P] Private raw-output folder rule: document in `docs/live-tests.md` (new section
  "Recording administration commands") where raw outputs of [LIVE] tasks go
  (`~/inubit-mcp-recordings/022/`, outside every repository). Also document the approval line
  format the operator writes in the chat for a [LIVE-W] command: server, command, expected
  change, how to undo it.

## Phase 2: Foundational — coverage list, REST registry, session, secret rules (blocks every story; ships with the US1 PR)

- [ ] T004 `RestEndpoint` registry (D-8):
  - Create `adapter/rest/RestEndpoint.java`, an enum of method + path template for every endpoint
    the server sends today:
    - `GET /healthcheck`, `GET /ready`, `GET /system/info`, `GET /metrics`;
    - `POST /log/{name}`;
    - `GET /model/models`, `GET /model/modelByName/{name}`, `GET /model/export/{name}`;
    - plus any other found by searching for `/ibis/rest` under `adapter/`.
  - It has `path(String... params)`, which encodes each parameter with
    `InubitHttpClient.encodePathSegment`.
  - Failing test first: `adapter/rest/RestEndpointTest.java` (template filling, encoding, wrong
    parameter count throws).
- [ ] T005 Move every REST adapter to `RestEndpoint` (after T004; no behaviour change):
  - Files: `adapter/rest/v81/*Adapter.java`, `V81MaintenanceProbe.java`,
    `V81VersionDetector.java`, `LogEndpoint.java`, `adapter/V81InventoryAdapter.java`.
  - `InubitHttpClient.get/post` accept a `RestEndpoint` plus parameters (the old string overloads
    are removed).
  - Failing test first: `architecture/RestEndpointRegistryTest.java` scans the compiled classes
    and fails when a class other than `RestEndpoint` holds the constant `/ibis/rest`.
  - Done when all existing REST tests are green unchanged.
- [ ] T006 Coverage domain (D-2, data-model "Coverage"):
  - `domain/model/CoverageEntry.java`;
  - `domain/model/CoverageState.java`: sealed, with `Reachable(tool, action)`, `Covered(tool)`,
    `Planned`, `Undocumented`, `Excluded(reason)`;
  - `domain/model/InterfaceCoverage.java` with `entries()`, `state(channel, entry, line)` and
    `offered(tool, action, line)` (true only when every declared entry is `Reachable` for that
    line);
  - a loader that parses the TSV format of `contracts/coverage-list.md`: comment lines,
    `delivered-stories`, the header `channel entry kind area story v8_1 v9_0`.
  - Loading fails on a duplicate (`channel`, `entry`), a missing version state, an unknown state
    word, a reason over 120 characters, or `planned` without `story`.
  - Failing tests first: `domain/model/InterfaceCoverageTest.java`, one test per failure rule and
    for `offered`.
- [ ] T007 Initial coverage list (after T006): `src/main/resources/coverage/interface-coverage.tsv`
  with every entry of the vendor pages "Command Line Interface" (with subpages) and "REST API"
  (every endpoint page) for 8.1 and 9.0.
  - **Source**: re-download the pages named in `docs/research/inubit-interfaces.md` §6 with curl
    into a private folder, and parse them with `python3 -I` from a script kept outside that
    folder.
  - **Granularity**: per `contracts/coverage-list.md`.
  - **Rows**:
    - Entries the server sends today: `reachable:<tool>/<action>` (for the existing tools, the
      action is the tool's main mode, e.g. `export_archives/workflows`).
    - The rows of D-6: `covered:<tool>`.
    - The portal entries: `excluded:portal function`.
    - The exclusions of the spec Background: `lls`, cache mode, `validateMigrationBackup`,
      `--encryptString`, `processLog`, `--tagDiagram`, `--tagWorkflowGroupExclude`, `inFile`,
      `--filterProcessIds`. Each gets its reason.
    - `GET /model/nodeByName/…` on 9.0: `undocumented`.
    - Every other entry: `planned` with its story from the spec Background table.
  - Header: `delivered-stories:` empty.
  - Also add `domain/model/InterfaceCoverage.production()`, which loads this resource.
  - Failing test first: `InterfaceCoverageResourceTest` asserts that the file loads, has at least
    one row per area A–E, has no `planned` row without a story, and that `lls` is excluded.
- [ ] T008 Coverage checks (FR-003; after T005, T007): `architecture/InterfaceCoverageCheckTest.java`
  implements checks 1–6 of `contracts/coverage-list.md`.
  - Checks 1–3 read `CliCommand.OPTIONS`, the `CliSessionCommand` constants, `RestEndpoint.values()`
    and `TestWiring` tool handlers.
  - Check 6 runs against the registered `ActionSpec`s (empty until T010).
  - Failing first by adding one temporary unlisted `RestEndpoint` value in the test's own fixture
    enum.
- [ ] T009 Generated docs page (after T007): `domain/model/CoverageDocs.java` renders
  `docs/interface-coverage.md`:
  - a source line;
  - counts per state and version;
  - one table per area with columns entry, kind, story, 8.1, 9.0.
  - Test `mcp/InterfaceCoverageDocsTest.java` compares the file with the rendering. With
    `-Dcoverage.write=true` it rewrites the file. The failure message names that command.
  - Generate and commit the page.
- [ ] T010 `ActionSpec` and the coverage gate (D-2, D-12 step 5; after T006):
  - `application/ActionSpec.java`: `tool`, `action`, `Area` (`OPERATION`, `OBJECTS`, `LIBRARIES`,
    `SETTINGS`, `USERS`, `RUNTIME_DATA`, `READ`), `entries`, `kind` (`READ`, `CHANGE`,
    `RUNTIME_READ`), `bulk`, `backup`, `restartNotice`.
  - `application/ActionCatalog.java`: registry by (tool, action).
  - `application/VersionGate.java`: new `admitAction(String tool, NodeId, ActionSpec)`. It runs
    the existing capability admission, then `InterfaceCoverage.offered`. When not offered, it
    gives `UNSUPPORTED_VERSION` with the message naming the action and "not yet verified" (or the
    exclusion reason).
  - Failing tests first: `application/VersionGateActionTest.java` covering `reachable`,
    `planned`, `excluded`, `undocumented` on 9.0, and 6.1 always refused.
- [ ] T011 Capabilities (D-1; after T010): in `domain/model/Capability.java`, add
  - `SERVER_SETTINGS` (READ, REST+CLI);
  - `USER_DIRECTORY` (READ, REST+CLI);
  - `MODEL_DETAILS` (READ, REST+CLI);
  - `SERVER_OPERATION` (WRITE, CLI+REST);
  - `TAG_ADMINISTRATION` (WRITE, CLI);
  - `RUNTIME_TRANSFER` (WRITE, CLI);
  - `OBJECT_ADMINISTRATION` (WRITE, REST+CLI);
  - `SETTINGS_ADMINISTRATION` (WRITE, REST+CLI);
  - `USER_ADMINISTRATION` (WRITE, REST+CLI).

  Also:
  - each gets its `label()`;
  - `CapabilityMatrix.production()`: 8.1 and 9.0 `UNSUPPORTED` + "not yet verified"; 6.1
    unsupported;
  - `application/NodeOverview.java` names;
  - `src/main/resources/schemas/list_nodes.output.json` `CapabilityName` enum +9.

  Failing tests first: `domain/model/CapabilityMatrixTest.java` and
  `mcp/tools/ListNodesToolTest.java` (new names in `notUsable` with "not yet verified").
- [ ] T012 Version-neutral StartCLI session (D-7):
  - `adapter/cli/CliSessionCommand.java`: the 6.1 constants are kept; a builder adds commands
    with the quoting and value rules of `CliCommand` (`VALUE`, `REPOSITORY_PATH`); an allowlist
    map `SESSION_OPTIONS`, empty for 8.1/9.0 until the stories add commands.
  - `adapter/cli/CliSessionParser.java`, extracted from `adapter/cli/v61/V61SessionParser.java`:
    splits blocks by the prompt echo pattern `CLI\|[^@\s]+@\S+>`.
  - `adapter/cli/CliRunner.runSession`: usable for any version, taking a `Set<Secret>` to
    register with `SecretScrubber` before start.
  - Failing tests first:
    - `adapter/cli/CliSessionCommandTest.java`: a rejected option; quoting;
    - `adapter/cli/CliSessionParserTest.java`: blocks, missing prompt → `UNEXPECTED_RESPONSE`;
    - `adapter/cli/CliRunnerSessionTest.java`: the secret is on stdin only, never in `LaunchSpec`
      argv/env, and an echo in stdout is masked; leak check.
  - All 6.1 tests stay green; `fixtures/v6_1` unchanged.
- [ ] T013 `SecretRules` extraction (D-19):
  - `domain/model/SecretRules.java` (public): `isSecretName(String)`, `isSecretPath(String)`,
    `masks(String name, Optional<String> type, boolean encrypted, String value)`. It uses the
    patterns of `adapter/archive/v81/PartnerSecretRules.java` (`SECRET_TYPE`, `SECRET_NAME`) and
    `KeyMaterial`.
  - `PartnerSecretRules` delegates to it.
  - `domain/model/JsonLeafMasker.java` walks a Jackson tree and masks leaves by `SecretRules`,
    returning a masked copy and `maskedCount`.
  - Failing tests first: `domain/model/SecretRulesTest.java` and
    `domain/model/JsonLeafMaskerTest.java` (nested objects, arrays, `encrypted` sibling flag, key
    material value).
  - All 018 tests stay green unchanged.

## Phase 3: User Story 1 – Read the server's state and settings (P1) 🎯 MVP

**Goal**: `get_server_settings` returns license, threads, privileged workflows, DB connection
tolerance, configuration (by section) and server time per server (US1).

**Independent test**: with recorded StartCLI outputs and REST responses, one call gives each fact
as a typed field with its source, and missing parts with their reason (US1 AS1–AS7).

- [ ] T014 [LIVE] [OWNER] [US1] Read recordings for US1 and the session check (L-1, L-5, the read part
  of L-11) on the 8.1 test server:
  - a stdin session running `threads showNumberOfWorkflowThreads`,
    `privilegedWorkflows showThreadsPercentage`, `privilegedWorkflows showWorkflowList`,
    `dbconnectiontolerance show`, `date`, `license -td`, `license -ts`, `exit`;
  - the same commands each once in script mode;
  - `GET /license`;
  - `GET /configurations`.

  Record:
  - prompt and echo format;
  - whether the session needs `exit`;
  - the root key of `/configurations`.

  Neutralize into `fixtures/v8_1/cli/admin-settings-*` and `fixtures/v8_1/rest/admin-license*`,
  `admin-configurations*`, with planted secrets in at least two configuration leaves. The owner
  reviews the fixture diff. Repeat for 9.0 as T024.
- [ ] T015 [P] [US1] Domain records (data-model "ServerSettings"): `domain/model/ServerSettings.java`,
  `ConfigurationView.java`, `LicenseFacts.java`, `DbConnectionTolerance.java`,
  `PrivilegedWorkflows.java` (≤ 200 names, `truncated`).
  - New `UnavailablePart` parts: `license`, `threads`, `privilegedWorkflows`,
    `dbConnectionTolerance`, `configuration`, `serverTime`.
  - Failing tests first: `domain/model/ServerSettingsTest.java` (bounds, parts).
- [ ] T016 [P] [US1] `adapter/rest/v81/LicenseXmlParser.java`:
  - uses `XmlSupport` hardening;
  - reads the expiry date, licensed threads and licensee;
  - the licensee is masked by `SecretRules`, and any element matching `SecretRules` is masked.
  - Failing tests first: `LicenseXmlParserTest.java` with a reconstructed fixture (the real one
    after T014): DTD refused, unknown elements ignored, missing expiry → part unavailable.
- [ ] T017 [P] [US1] `adapter/rest/v81/ConfigurationJsonParser.java`:
  - accepts the root key `SeverConfiguration` and `ServerConfiguration`;
  - the section names are the top-level keys below the root;
  - a section's leaves are flattened to `{path, value | masked}` through `JsonLeafMasker`, with
    `maskedCount`;
  - output is bounded by `ResultLimiter` with `truncated`.
  - Failing tests first: `ConfigurationJsonParserTest.java` (both root keys, sections only, one
    section, unknown section → `NOT_FOUND` naming the listing, masking; leak check).
- [ ] T018 [P] [US1] StartCLI settings parsers in the new package `adapter/cli/admin/`
  (`package-info.java`):
  - `ThreadsParser`;
  - `PrivilegedWorkflowsParser` (percentage and list);
  - `DbToleranceParser` (fault threshold, drop rate %, drop interval s; the vendor example shows
    `Drop rate: 1%` / `Drop interval (s): 180 seconds`);
  - `ServerDateParser`.
  - Each takes one session block; an unknown text gives `UNEXPECTED_RESPONSE` with a masked
    excerpt ≤ 500 characters, never a guess.
  - Failing tests first: one test class per parser with reconstructed cases (recorded after
    T014).
- [ ] T019 [US1] StartCLI commands for settings reads (after T012, T018):
  - `CliSessionCommand.SESSION_OPTIONS` admits `threads showNumberOfWorkflowThreads`,
    `privilegedWorkflows showThreadsPercentage|showWorkflowList`, `dbconnectiontolerance show`,
    `date`;
  - `adapter/cli/admin/CliSettingsRunner.java` runs them in one session bounded by `cliTimeout`
    and maps each block to its parser; a failed block marks only its part unavailable (FR-013).
  - Failing tests first:
    - `adapter/cli/CliSessionCommandTest` (allowed forms; `threads setNumberOfWorkflowThreads`
      rejected);
    - `adapter/cli/admin/CliSettingsRunnerTest.java` (scripted session; one block missing → that
      part unavailable).
- [ ] T020 [US1] Port and wiring:
  - `domain/port/ServerSettingsPort.java` (`license()`, `configuration(Optional<String> section)`,
    `cliSettings(Set<Part>)`);
  - `adapter/rest/v81/V81SettingsAdapter.java` (REST parts);
  - `RestEndpoint` + `GET /license`, `GET /configurations`;
  - added to `domain/port/Gateway.java`, `GatewayFactory.java` (default method),
    `adapter/PortSet.java`, `adapter/RefusingPorts.java`, `adapter/StaleReportingPorts.java`,
    `adapter/V81Gateway.java`, `adapter/AdapterGatewayFactory.java`, `V81PortSet.java`
    (9.0 reuses it).
  - Failing tests first: `adapter/rest/v81/V81SettingsAdapterTest.java` (WireMock: 200; 403 →
    `FORBIDDEN` naming "System Administrator"; 503 → `MAINTENANCE_MODE`) and `WiringAuditTest`.
- [ ] T021 [US1] `application/ServerSettingsService.java`:
  - `read(target, parts, section)`;
  - a stage gives one entry per server (AS5);
  - default parts are all but `configuration`; a section implies `configuration`;
  - version, memory share, blocking queue size and maintenance come from the same `MonitoringPort`
    calls `HealthService` uses, with no StartCLI call for them (AS7, FR-002);
  - each part has its source in `sources`;
  - the action is admitted through `VersionGate.admitAction` with
    `ActionSpec(get_server_settings, read, READ, entries…)`.
  - Failing tests first: `application/ServerSettingsServiceTest.java`:
    - AS1, AS3, AS4 (role missing for license → the other parts returned), AS5, AS7;
    - no `-sv`/`-pctmem`/`getBlockingQueueSize` ever sent (`ScriptedProcessLauncher` records
      argv and stdin).
- [ ] T022 [US1] Tool `mcp/tools/GetServerSettingsTool.java`:
  - schemas `src/main/resources/schemas/get_server_settings.{input,output}.json` per
    `contracts/mcp-tools-delta.md`;
  - `ToolHints.readOnly(title, true)`;
  - registered in `Wiring.toolHandlers()` when `anyPossiblyCapable(SERVER_SETTINGS)`.
  - Failing tests first:
    - `mcp/tools/GetServerSettingsToolTest.java` (full description text, annotations, validation
      errors);
    - `GetServerSettingsOutputSchemaTest.java`;
    - `ToolAnnouncementTest` (new ordered lists).
- [ ] T023 [US1] `security/ServerSettingsSecretLeakTest.java`: planted secrets in license XML,
  configuration leaves, StartCLI output and errors never appear in results, errors, logs (logback
  test appender) or audit; leak check.
- [ ] T024 [LIVE] [OWNER] [US1] The same recordings as T014 on the 9.0 test server, into
  `fixtures/v9_0/{cli,rest}/admin-settings-*` with `.origin`. Blocked until the 9.0 server's
  certificate pin is renewed (feature 021 open point); record that state in `implementation.md`
  while waiting.
- [ ] T025 [US1] Contracts and flip for 8.1 [BLOCKED: T014]:
  - `adapter/rest/v81/SettingsV81ContractTest.java` and
    `adapter/cli/admin/SettingsCliV81ContractTest.java` against the recorded fixtures;
  - flip the US1 rows for `v8_1` to `reachable:get_server_settings/read`, the `SERVER_SETTINGS`
    matrix entry for 8.1 to `SUPPORTED`;
  - regenerate the docs page.
  - Same commit as the fixtures.
  - If L-1 showed no session on 8.1, switch `CliSettingsRunner` to one script call per command
    first and record the D-7 fallback in `research.md`.
- [ ] T026 [US1] 9.0 contracts and flip [BLOCKED: T024]: `SettingsV90ContractTest.java`, rows
  and matrix for `v9_0`, catalogue entries.
- [ ] T027 [US1] Docs and release notes (FR-027):
  - `docs/tools.md`: section `get_server_settings`, the capability list, the error codes used;
  - `docs/setup.md`: the role "System Administrator" for license and configuration;
  - `docs/research/inubit-interfaces.md`: §1.3 and §2.2 now point to the coverage page;
  - `docs/interface-coverage.md` regenerated;
  - `CHANGELOG.md` `[Unreleased]`;
  - `delivered-stories: US1` in the TSV.
  - Failing first: `ArtifactToolsReferenceTest` / `DevelopmentToolsReferenceTest` extended for
    the new tool section.
  - Then open the US1 PR (phases 1–3).

## Phase 4: User Story 2 – Read users, groups and logins (P1)

**Goal**: `read_users` lists users without personal fields, reads one user, the logins and the
messages (US2).

**Independent test**: with recorded outputs, a page of users with groups and roles, one user with
e-mail, the logins and a message, each bounded (US2 AS1–AS6).

- [ ] T028 [LIVE] [OWNER] [US2] L-6, L-17 on 8.1 (and 9.0 when possible):
  - `GET /user/users?type=processEngineUser`;
  - a session with `user <login>` and `finger <login>` for three fictitious-named test users;
  - `-who`, `-nwho`;
  - `msglist`, then `msgread <id>`, then `msglist` again (does the state change?).

  Neutralize into `fixtures/v8_1/{rest,cli}/admin-users-*` (personal fields replaced by
  `*.example.test` values). Record whether `msgread` marks the message as read.
- [ ] T029 [P] [US2] Domain records: `domain/model/UserSummary.java` (no personal field by
  construction), `UserDetail.java`, `LoginEntry.java`, `MessageSummary.java`, `MessageText.java`
  (`ItemBounds.MAX_ITEM_CHARS`, `truncated`).
  - Failing tests first: `domain/model/UserRecordsTest.java`.
- [ ] T030 [P] [US2] `adapter/rest/v81/UserListXmlParser.java`: reads only `id` and `type`.
  `email`, `firstName`, `lastName` and unknown attributes are never read into any object. XML is
  hardened.
  - Failing tests first: `UserListXmlParserTest.java` (fixture with planted e-mails: parsed
    objects and `toString` contain none).
- [ ] T031 [P] [US2] StartCLI parsers `adapter/cli/admin/UserGroupsParser.java` (`user <name>`),
  `FingerParser.java` (login, e-mail, roles, account state if shown), `WhoParser.java`,
  `MessageListParser.java`, `MessageReadParser.java`.
  - Failing tests first: one test class each, with reconstructed cases (recorded after T028).
- [ ] T032 [US2] `adapter/cli/admin/CliUserRunner.java` (after T012, T031):
  - one session per page with `user <login>` + `finger <login>` for ≤ 25 logins;
  - `-who`/`-nwho`;
  - `msglist`, `msgread <id>` (id pattern `^[0-9]{1,10}$`).
  - `SESSION_OPTIONS` admits exactly these read forms.
  - Failing tests first: `CliUserRunnerTest.java` (scripted session of 25 users; login names
    quoted; a block missing → that user's groups `unavailable`) and `CliSessionCommandTest`.
- [ ] T033 [US2] Port and wiring:
  - `domain/port/UserDirectoryPort.java`;
  - `adapter/rest/v81/V81UserAdapter.java`, `RestEndpoint` + `GET /user/users`;
  - the full `Gateway`/`PortSet` wiring set as in T020.
  - Failing tests first: `V81UserAdapterTest.java` and `WiringAuditTest`.
- [ ] T034 [US2] `application/UserReadService.java`:
  - views `users`, `user`, `logins`, `messages`, `message`;
  - logins sorted, `offset` 0–9999, `limit` 1–25 (default 25), `nextOffset`;
  - an unknown login gives `NOT_FOUND` with the next step "list the users" (AS3);
  - personal fields appear only in `user` (FR-011);
  - `ActionSpec`s per view.
  - Failing tests first: `application/UserReadServiceTest.java` (AS1–AS6; a 500-user list gives
    pages; AS5 no e-mail in a listing).
- [ ] T035 [US2] Tool `mcp/tools/ReadUsersTool.java`:
  - schemas `read_users.{input,output}.json`;
  - read-only, idempotent;
  - the description names the `msgread` side effect from T028 (FR-008);
  - announcement when `anyPossiblyCapable(USER_DIRECTORY)`.
  - Failing tests first: `ReadUsersToolTest.java`, the output schema test,
    `ToolAnnouncementTest`.
- [ ] T036 [US2] `security/ReadUsersSecretLeakTest.java`: planted e-mails never appear in `users`,
  and planted secrets in finger output and message text are masked; leak check.
- [ ] T037 [US2] Contracts and flips [BLOCKED: T028]: `UsersV81ContractTest.java` (and
  `UsersV90ContractTest.java` when 9.0 is recorded); rows, matrix and docs page.
- [ ] T038 [US2] Docs and PR:
  - `docs/tools.md` section `read_users`;
  - `docs/research/inubit-interfaces.md`;
  - `CHANGELOG.md`;
  - `delivered-stories: US1, US2`.

  Then open the US2 PR.

## Phase 5: User Story 3 – Processes, run-time data and model details (P2)

**Goal**: `read_model_details` (module, metadata, libraries, IS Connector check). `export_archives`
gains `ownerWithoutGroups`, `metadata` and `xslt10Report`. `ps -f` and the counts are confirmed
`covered` (US3).

**Independent test**: each action returns its documented shape from recordings; the new export
forms write files under the unchanged folder rules (US3 AS1–AS8).

- [ ] T039 [LIVE] [OWNER] [US3] L-7, L-8 and read recordings on 8.1:
  - `GET /model/nodeByModel/{d}/{id}`, `GET /model/nodeByName/{d}/{m}`;
  - `GET /process/metadata/{wf}`;
  - `GET /isconnector?…type=validateWorkflow`;
  - `library --list --type driver|plugin`;
  - `export --exportWorkflowGroupExclude`, `--exportMetadata`, `--exportMetadataNames`;
  - `fetchXSLT1.0 -ef` (where is the file written?);
  - `ps -u` (output form).

  Neutralize into `fixtures/v8_1/{rest,cli}/admin-model-*` with planted secrets in module
  properties and metadata.
- [ ] T040 [P] [US3] Parsers `adapter/rest/v81/ModuleDetailJsonParser.java`,
  `ProcessMetadataParser.java`, `IsConnectorCheckParser.java` (masking through `JsonLeafMasker`;
  message ≤ 2,000 characters) and `adapter/cli/admin/LibraryListParser.java`.
  - Domain records `ModuleDetail`, `ProcessMetadata`, `LibraryEntry`, `IsConnectorCheck`.
  - Failing tests first: one test class each, with a leak check.
- [ ] T041 [US3] `CliCommand` read forms:
  - `library --list --type driver|plugin`;
  - `export --exportWorkflowGroupExclude <g1,g2>` (each group name per `VALUE`, ≤ 20, joined as
    the vendor documents);
  - `export --exportMetadata`, `--exportMetadataNames`;
  - `fetchXSLT1.0 -ef <file>` (the file name is generated by the server, never from the caller).
  - Failing tests first in `adapter/cli/CliCommandTest.java`: allowed forms; `library --delete`
    and `library --deleteAll` still rejected.
- [ ] T042 [US3] Port and wiring:
  - `domain/port/ModelDetailPort.java`;
  - `adapter/rest/v81/V81ModelDetailAdapter.java` (REST) plus `adapter/cli/admin/CliLibraryRunner.java`
    (list);
  - `RestEndpoint` + `GET /model/nodeByModel/{d}/{id}`, `GET /model/nodeByName/{d}/{m}`,
    `GET /process/metadata/{name}`, `GET /isconnector`;
  - the `Gateway`/`PortSet` set.
  - Failing tests first: adapter tests with WireMock and scripted StartCLI.
- [ ] T043 [US3] `application/ModelDetailService.java` and tool `mcp/tools/ReadModelDetailsTool.java`:
  - schemas `read_model_details.{input,output}.json`;
  - actions `module` (`moduleId` | `moduleName`; `moduleName` on 9.0 gives
    `UNSUPPORTED_VERSION`, row `undocumented`), `metadata`, `libraries` (paged), `checkIsConnector`;
  - read-only, idempotent;
  - announcement on `MODEL_DETAILS`.
  - Failing tests first: `ModelDetailServiceTest.java` (AS2, AS3, AS6 read part, AS8),
    `ReadModelDetailsToolTest.java`, `ToolAnnouncementTest`.
- [ ] T044 [US3] `export_archives` `content` forms (D-21):
  - `src/main/resources/schemas/export_archives.input.json`:
    - `content` enum, default `workflows`;
    - `if/then`: `diagramGroup` required only for `workflows`;
    - `excludeGroups[1..20]` only for `ownerWithoutGroups`;
    - `workflows[1..50]` required for `metadata`;
    - `acknowledgeClearTextSecrets` required for `workflows` and `ownerWithoutGroups`.
  - The export archives service (`application/ArchiveExportService.java` or its current name):
    - the three forms through `adapter/cli/CliExportRunner.java` (new methods
      `exportOwnerWithoutGroups`, `exportMetadata`, `fetchXslt10Report`);
    - the same `TargetFolderRules`, `ArchiveFolderWriter`, `archives.productionOptIn` and audit;
    - an `.xlsx` entry for the report;
    - the description names the check-in comment trace (FR-008).
  - If T039 showed that `fetchXSLT1.0` writes on the INUBIT host, drop `xslt10Report` and set its
    row to `excluded:file written on the INUBIT host`.
  - Failing tests first:
    - `ExportArchivesContentTest.java` (each form, folder rules unchanged, production opt-in);
    - `ExportArchivesToolTest` description;
    - `ArchiveToolReferenceTest`;
    - every existing `export_archives` test unchanged (SC-009).
- [ ] T045 [US3] Coverage decisions for D:
  - set `ps -f` rows to `covered:find_processes` and `-ne/-nx/-nw/-nq` to `covered:get_health`
    (D-6);
  - set `ps -u` per T039 (`covered:find_processes` or `excluded:<reason>`).
  - Failing first: `InterfaceCoverageResourceTest` asserts these states.
- [ ] T046 [US3] Contracts and flips [BLOCKED: T039]: `ModelDetailsV81ContractTest.java`,
  `ExportContentV81ContractTest.java`; rows, matrix, docs page; 9.0 when recorded.
- [ ] T047 [US3] Docs and PR:
  - `docs/tools.md` sections `read_model_details` and `export_archives` (new forms);
  - `CHANGELOG.md`;
  - `delivered-stories` + US3.

  Then open the US3 PR.

## Phase 6: User Story 4 – Operate a server (P2)

**Goal**: `operate_server` (maintenance, readiness, trace, trace level, debug, midnight task,
deleting processes in error) and `manage_tags` (active tag of a user; tag removal limited to a
group or path if INUBIT allows it), each previewed, confirmed, audited (US4).

**Independent test**: with fakes, each change is refused by default, shows a preview when
enabled, executes only with the code and leaves audit records (US4 AS1–AS14).

### Shared change infrastructure (first used here; reused by US5–US7)

- [ ] T048 [P] [US4] Domain `domain/model/ChangePreview.java`, `ChangeResult.java` and
  `ChangeOutcome` (`EXECUTED`, `NOTHING_TO_DO`, `FAILED`), per data-model "Changes".
  - `affected` holds ≤ 50 ids with `truncated`; `inubitAnswer` is masked, ≤ 2,000 characters;
    `reReadFailed` holds a reason.
  - Failing tests first: `domain/model/ChangeRecordsTest.java`.
- [ ] T049 [US4] `application/ChangeGuard.java` (D-12):
  - `admit(tool, nodeId, ActionSpec)` in exactly this order:
    1. group id → `INVALID_INPUT`, unknown → `TARGET_UNKNOWN`;
    2. production without opt-in, or production with `CLIENT` → `PRODUCTION_PROTECTED`;
    3. write off → `WRITE_DISABLED`;
    4. area opt-in missing → `NOT_CONFIGURED` with next step `administration.<area>` (skipped for
       `OPERATION`);
    5. `VersionGate.admitAction` → `UNSUPPORTED_VERSION`;
    6. availability → `CLI_UNAVAILABLE` / `AUTH_FAILED`.
  - The area check reads `EffectiveNodeConfig.administration()`, which does not exist yet: add a
    stub `enabled(Area)` that is true only for `OPERATION` and `READ` until T066.
  - Every refusal is audited `REFUSED`.
  - Failing tests first: `application/ChangeGuardTest.java`, one test per step and per code, plus
    the order (production refusal wins over write off).
- [ ] T050 [P] [US4] `application/ChangeLocks.java` (D-17): `try (var lock =
  locks.acquire(node, area, object))`; a key already held gives `PRECONDITION_FAILED`; released in
  `finally`.
  - Failing tests first: `ChangeLocksTest.java` (parallel threads; release after exception).
- [ ] T051 [US4] Confirmation with preview state (D-13, D-14):
  - `application/ChangeChallenges.java` wraps `WriteChallengeRegistry`:
    - `issue(tool, NodeId, fingerprint, previewState, ttl)`, where `fingerprint` is SHA-256 of the
      canonical inputs without `confirmationCode` and `reason`;
    - `redeem(...)`;
    - `confirmOrConflict(redeemedState, rereadState)`: `CONFLICT` with a fresh preview and code in
      the error details.
  - Add the per-tool node binding to `WriteChallengeRegistry` if only the group form exists.
  - `CLIENT` mode is honoured only for `Area.OPERATION`.
  - Failing tests first: `ChangeChallengesTest.java` (wrong tool, node or input →
    `CONFIRMATION_INVALID`; changed state → `CONFLICT`; administration areas ignore `CLIENT`).
- [ ] T052 [US4] `application/ChangeAudit.java`:
  - the records `REFUSED`, `CHALLENGE_ISSUED`, `PENDING` (forced to disk before sending; a
    failure gives `INTERNAL` and sends nothing), `EXECUTED`/`FAILED` with the same audit id;
  - inputs bounded (500 characters), `secretEntry` by name, uploads by path, size and SHA-256,
    message text ≤ 200 characters;
  - `NOTHING_TO_DO` audited as `EXECUTED` with details `nothing-to-do`.
  - Failing tests first: `ChangeAuditTest.java` with `RecordingAuditPort`; leak check.
- [ ] T053 [US4] Backup kind (D-15): `application/BackupStore.java` gains `Manifest.Kind.ADMINISTRATION`:
  - scope node, object kind (`WORKFLOW`, `MODULE`, `METADATA`, `REPOSITORY`, `TAG_LIST`), name;
  - `auditId`, `contents`;
  - `writeTagList(scope, List<TaggedVersion>)` as JSON;
  - the sweep is unchanged.
  - Failing tests first: `BackupStoreAdministrationTest.java` (manifest round trip, private
    files, retention keeps the newest per scope).

### Live checks for US4

- [ ] T054 [LIVE-W] [OWNER] [US4] L-2 and L-3 on the 8.1 test server, each command approved:
  - `maintenance` / `maintenance -s`;
  - `maintenance on -eq false`, `maintenance off`;
  - `GET /ready` and `POST /ready/off`, `POST /ready/on`, including once while maintenance is on;
  - `trace on`, `trace off`, `trace -l <level>` (record the level list from `trace --help`);
  - `debug on`, `debug off`;
  - `triggerMidnightTask` (only if the owner approves running the midnight task);
  - `processdelete error` with exactly one prepared test process in error.

  Neutralize into `fixtures/v8_1/{cli,rest}/admin-operate-*`.
- [ ] T055 [LIVE-W] [OWNER] [US4] L-4 on the 8.1 test server, with approval:
  - tag two fictitious test diagram groups with the same test tag (`tag_artifacts` on a
    development stage);
  - run `tag --tagRemove <tag> --tagWorkflowGroup <g1> --tagWorkflowType <t>` and check whether
    `g2` keeps the tag;
  - the same with `--tagDelete`, and with `--tagRepositoryPath`;
  - `tag --tagSetActive <tag> --tagUser <user>` and `--tagRemoveActive`.

  Record the outcome in `research.md` D-18 and the fixtures `admin-tags-*`.

### operate_server

- [ ] T056 [US4] `CliCommand` operation forms:
  - `maintenance` (no option, `-s`, `on`, `off`, `on -eq true|false`; `-eq` only with `on`);
  - `trace on|off`, `trace -l <level>` (level enum from T054; until then the vendor list
    `error|warn|info|debug` reconstructed and marked in `research.md`), with `trace --download`
    (021) as a separate path and never combined;
  - `debug on|off`;
  - `triggerMidnightTask`;
  - `processdelete error`.
  - Failing tests first in `CliCommandTest.java`: allowed forms; `-eq` with `off` rejected;
    `trace --download` combined with `on` rejected; `processdelete` with any other argument
    rejected.
- [ ] T057 [US4] `adapter/cli/admin/CliOperationRunner.java` and parsers
  `MaintenanceStateParser.java`, `TraceStateParser.java`, `DebugStateParser.java`,
  `MidnightTaskAnswerParser.java`, `ProcessDeleteAnswerParser.java` (the count INUBIT reports);
  `RestEndpoint` + `POST /ready/{state}`.
  - Failing tests first: runner test with scripted runs; parser tests with reconstructed cases;
    leak check.
- [ ] T058 [US4] Port and wiring: `domain/port/ServerOperationPort.java`, adapters, the
  `Gateway`/`PortSet` set. The `processdelete error` preview set comes from
  `ProcessQueryPort` (REST `POST /log/queueLog`, states `ERROR`, all pages up to 10,000 ids; more →
  count only, D-14).
  - Failing tests first: adapter tests, `WiringAuditTest`.
- [ ] T059 [US4] `application/ServerOperationService.java`:
  - the actions of `contracts/mcp-tools-delta.md`;
  - the before state read first (`MAINTENANCE_MODE` from REST counts as "on");
  - `effects` sentences per action, with the fixed texts from the contract;
  - `deleteErrorProcesses`: an empty set gives `NOTHING_TO_DO` (audited); the set is re-read
    before sending (FR-017);
  - the after state re-read (FR-024);
  - `ChangeGuard`, `ChangeLocks`, `ChangeChallenges`, `ChangeAudit`, `WriteLock.handOff`.
  - Failing tests first: `application/ServerOperationServiceTest.java`:
    - AS1–AS11 one test each;
    - `CLIENT` mode on non-production executes on the first call;
    - a changed error set → `CONFLICT`;
    - maintenance off while REST answers 503.
- [ ] T060 [US4] Tool `mcp/tools/OperateServerTool.java`:
  - schemas `operate_server.{input,output}.json`;
  - `ToolHints.destructive(title)`;
  - announced when `anyWriteEnabled`;
  - the description per the contract (midnight task: its effect not documented by the vendor).
  - Failing tests first: `OperateServerToolTest.java`, `ToolHintsTest`, `ToolAnnouncementTest`.

### manage_tags

- [ ] T061 [US4] `CliCommand` tag forms (D-18):
  - `tag` admits `--tagSetActive`, `--tagRemoveActive`, `--tagDelete`, `--tagRemove`,
    `--tagRepositoryPath`;
  - builder methods `tagSetActive(tag, user)`, `tagRemoveActive(user)`, and
    `tagRemoval(kind, tag, Scope)` where `Scope` = group + type | repository path, required;
  - `--tagDiagram` and `--tagWorkflowGroupExclude` are never admitted;
  - `--tagMove` (existing) unchanged.
  - Failing tests first in `CliCommandTest.java`: removal without scope rejected; both forbidden
    options rejected; existing `tag_artifacts` forms unchanged.
- [ ] T062 [US4] `adapter/cli/admin/CliTagAdminRunner.java` and `domain/port/TagAdministrationPort.java`:
  - the active tag of a user is read (from `user <name>` or the form found in T055);
  - the objects and versions carrying a tag in a scope come from the version history read the way
    `tag_artifacts` does (`VersionHistoryParser`), together with the count outside the scope;
  - the `Gateway`/`PortSet` set.
  - Failing tests first: runner and port tests with scripted runs.
- [ ] T063 [US4] `application/TagAdministrationService.java` and tool `mcp/tools/ManageTagsTool.java`:
  - schemas `manage_tags.{input,output}.json`;
  - a missing scope gives `INVALID_INPUT` before any read (AS13);
  - before a removal the backup is the tag list (T053), and nothing is sent if it fails;
  - the result re-reads inside and outside the scope (AS14);
  - `removeTag`/`deleteTag` are offered only when their rows are `reachable`; the description
    says so;
  - destructive; announced when `anyWriteEnabled`.
  - Failing tests first: `TagAdministrationServiceTest.java` (AS12–AS14, backup failure → nothing
    sent), `ManageTagsToolTest.java`.
- [ ] T064 [US4] Cross-cutting safety tests for the two tools:
  - `application/WriteRaceToolsTest.java` +2 (version change → refused, nothing sent);
  - a production refusal test for every action (SC-004);
  - a "write off" refusal test for every action (SC-003);
  - `security/OperationSecretLeakTest.java`.
- [ ] T065 [US4] Contracts, flips, docs and PR [BLOCKED: T054, T055]:
  - `OperateV81ContractTest.java`, `TagAdminV81ContractTest.java`;
  - rows flipped, or `--tagRemove`/`--tagDelete` set to `excluded:acts on the whole owner` per
    T055;
  - matrix, docs page;
  - `docs/tools.md` sections `operate_server`, `manage_tags`;
  - `docs/setup.md`: write enablement, `CLIENT` mode;
  - `CHANGELOG.md`;
  - `delivered-stories` + US4.

  Then open the US4 PR.

## Phase 7: User Story 5 – Transfer and repair run-time data (P3)

**Goal**: `transfer_runtime_data` exports (a change), validates, imports, lists, resumes and
deletes blocked processes; the archives stay on the INUBIT hosts (US5, FR-016a).

**Independent test**: with a fake StartCLI, the export needs write enablement, the opt-in and the
code, and names the operation id and host; the reads need only the opt-in (US5 AS1–AS7).

- [ ] T066 [US5] `administration` configuration (D-9, `contracts/configuration-delta.md`):
  - `config/AdministrationConfig.java` with `objects`, `libraries`, `settings`, `users`,
    `runtimeData` (strict booleans, "default false"), on `GroupConfig` only;
  - `Defaults`;
  - `ConfigLoader` strict booleans;
  - `ConfigValidator`: a warning per area on a production group; an error when an area other than
    `runtimeData` is on but no node of the group has write enabled; a warning for `runtimeData` in
    that case;
  - `ConfigSummary` line `administration: …`;
  - `EffectiveNodeConfig.administration().enabled(Area)` replaces the T049 stub;
  - `list_nodes` shows `administration` per node.
  - Failing tests first: `ConfigLoaderTest`, `ConfigValidatorTest`, `EffectiveNodeConfigTest`,
    `ConfigSummaryTest`, `ListNodesToolTest` (a node-level key is an error; `"yes"` is an error).
- [ ] T067 [P] [US5] `domain/model/ExportCondition.java` and `ExportFilter.java` (D-5):
  - `column` must match `^[A-Za-z][A-Za-z0-9_]{0,63}$`;
  - a value must match `^[^'(),\u0000-\u001F\u007F]{1,200}$`;
  - an `in` list holds 1–50 values; there are 1–10 conditions;
  - `render()` gives `c=v AND c in (a, b)`.
  - Failing tests first: `ExportFilterTest.java` (each rejected character, limits, rendering).
- [ ] T068 [LIVE-W] [OWNER] [US5] L-9 and L-10 on the 8.1 test server, with approval and test processes
  only:
  - `process export test-<date>.zip --filterExpression '<testcol>=<value>'`, with the answer
    recorded;
  - `process listBlocked <op>`;
  - `process validateExport` with the bare name and with the reported absolute path;
  - `process import` on a second test server, or the same one if only one exists;
  - `process resume <op>`, `process deleteBlocked <op>`;
  - whether the match count can be read before the export (L-9).

  Neutralize into `fixtures/v8_1/cli/admin-runtime-*`.
- [ ] T069 [US5] `CliCommand` `process` forms:
  - `export <fileName> [--excludeModel] [--filterExpression '<rendered>']`, with the file name
    pattern `^[A-Za-z0-9][A-Za-z0-9._-]{0,99}\.zip$`;
  - `import <path>`, `validateExport <path>`, where `<path>` is the reported absolute path (from
    an earlier export answer of that server) or the bare name per T068;
  - `listBlocked|resume|deleteBlocked <uuid>`.
  - `--filterProcessIds` and `inFile` are never admitted.
  - Failing tests first in `CliCommandTest.java`.
- [ ] T070 [US5] `adapter/cli/admin/CliRuntimeTransferRunner.java` (bounded by `cliExportTimeout`),
  with parsers:
  - `RuntimeExportAnswerParser` (`The export file is located at: …`, `Operation ID is: …`, the
    host);
  - `BlockedListParser` (paged);
  - `ValidateExportParser` (accepted, problems ≤ 50 × 500 characters);
  - `RuntimeImportAnswerParser`.

  Also `domain/port/RuntimeTransferPort.java` and the `Gateway`/`PortSet` set. A per-server record
  of exported paths (`application/RuntimeExportPaths`, in memory) maps names to reported paths.
  - Failing tests first: parser and runner tests; leak check.
- [ ] T071 [US5] `application/RuntimeReadGuard.java` and `application/RuntimeTransferService.java`:
  - `validate` and `listBlocked` need the `runtimeData` opt-in only, and are audited;
  - `export`, `import`, `resume` and `deleteBlocked` go through `ChangeGuard` with
    `Area.RUNTIME_DATA` and always use a server code;
  - the export preview carries the filter, the count if T068 found a way to read it, and the
    blocking sentence;
  - `resume`/`deleteBlocked` previews list the blocked ids, re-read before sending (FR-017).
  - Failing tests first: `RuntimeTransferServiceTest.java`:
    - AS1–AS7;
    - production without opt-in;
    - reads without write enablement allowed but without the opt-in refused;
    - a changed blocked set → `CONFLICT`.
- [ ] T072 [US5] Tool `mcp/tools/TransferRuntimeDataTool.java`:
  - schemas `transfer_runtime_data.{input,output}.json`;
  - destructive;
  - announced when some group has `administration.runtimeData`;
  - the description says the archive stays on the INUBIT hosts and the operator moves it, and
    gives the order of steps.
  - Failing tests first: `TransferRuntimeDataToolTest.java`, `ToolAnnouncementTest`,
    `WriteRaceToolsTest` +1, the production/write-off refusal tests,
    `security/RuntimeTransferSecretLeakTest.java`.
- [ ] T073 [US5] Contracts, flips, docs and PR [BLOCKED: T068]:
  - `RuntimeTransferV81ContractTest.java`;
  - rows, matrix, docs page;
  - `docs/tools.md` section;
  - `docs/setup.md` (`administration.runtimeData`);
  - `CHANGELOG.md`;
  - `delivered-stories` + US5.

  Then open the US5 PR.

## Phase 8: User Story 6 – Administer objects, libraries and settings (P3)

**Goal**: `administer_objects` (objects and libraries areas) and `change_server_settings`
(settings area), with uploads from configured folders, named secret entries and backups before
every delete, rename or replace (US6).

**Independent test**: with fakes, each change is refused without its area, previews what it
changes, takes a backup where INUBIT can export the object, and sends nothing when the backup
fails (US6 AS1–AS17).

### Shared inputs from outside the conversation

- [ ] T074 [P] [US6] `config/UploadsConfig.java` (D-9) on `ProfileConfig`:
  - `folders` ("empty → uploads off; absolute, existing, no symbolic link"), checked at start and
    not inside `~/.inubit-mcp`;
  - `maxBytes` per type with the defaults license 1 MiB, keystore 1 MiB, userImport 10 MiB,
    ediRules 50 MiB, metadataImport 50 MiB, isConnectorInput 50 MiB, library 200 MiB; each value
    1 KiB … 4 GiB; written as `<n>KiB|MiB|GiB` or bytes;
  - `ConfigSummary` line.
  - Failing tests first: `UploadsConfigTest.java`, `ConfigValidatorTest`, `ConfigSummaryTest`.
- [ ] T075 [US6] `application/UploadFiles.java` and `UploadFile` (D-11):
  - `open(Path, UploadType)` checks, in order:
    1. the path is absolute;
    2. with `NOFOLLOW_LINKS`, a regular file and no symbolic link in any path element;
    3. its real path is inside a configured folder;
    4. its size is ≤ the type's limit;
  - while checking it computes SHA-256 from the same open channel, then rewinds for sending;
  - folders empty → `NOT_CONFIGURED` naming `uploads.folders`; every other failure →
    `INVALID_INPUT` naming the rule.
  - Failing tests first: `UploadFilesTest.java`:
    - link, linked parent, directory, outside the folder, too large, empty folders;
    - a file swapped after `open` still sends the checked content (POSIX; skipped on Windows
      with a reason).
- [ ] T076 [US6] Named secret entries (D-10):
  - `domain/model/SecretEntryName.java` (`^[A-Z][A-Z0-9_]{0,47}$`; the reserved names
    `USERNAME`, `PASSWORD`, `TRUSTSTORE_PASSWORD`, `E2E_USERNAME`, `E2E_PASSWORD` are rejected);
  - `config/CredentialNames.java` adds `<PREFIX>_<GROUP>_<NODE>_ENTRY_<NAME>`, then
    `<PREFIX>_<GROUP>_ENTRY_<NAME>`;
  - `config/ParameterNames.java` adds `<prefix>/<group>/<node>/entries/<name>`, then
    `<prefix>/<group>/entries/<name>`;
  - `adapter/NodeSecrets.java` `entry(SecretEntryName, Duration bound)` registers the value with
    `SecretScrubber` first;
  - missing or unreadable → `NOT_CONFIGURED` naming the entry and the source, never a value.
  - Failing tests first: `SecretEntryNameTest.java`, `NodeSecretsEntryTest.java` (both sources
    faked; a login entry cannot be reached by any name); leak check.
- [ ] T077 [US6] `InubitHttpClient` PUT, DELETE and multipart (D-8):
  - `put(RestEndpoint, params, query, Body)`, `delete(...)`, `post` with JSON or an empty body;
  - `adapter/rest/Multipart.java` streams file parts from `UploadFile`'s channel and adds field
    parts; a secret field (`privateKeyPassword`) is never logged;
  - a 200 answer whose `responsedata` holds an error text is mapped to `UNEXPECTED_RESPONSE` by
    the adapter;
  - the existing status mapping is reused.
  - Failing tests first: `InubitHttpClientWriteTest.java` (WireMock multipart matching, streaming
    of a 50 MiB generated file without loading it into the heap, 401/403/404/503 mapping, the
    secret field not in a logback test appender).

### Live checks for US6

- [ ] T078 [LIVE-W] [OWNER] [US6] L-11 (write), L-14, L-16 and the recordings of the US6 changes on the
  8.1 test server, each approved, against throw-away fictitious objects:
  - `DELETE /diagrams/{n}` and `…/version/{v}`, `DELETE /modules?moduleName=` and
    `…/{id}/version/{v}`, `DELETE /repository`;
  - `rename --renameWorkflow|--renameModule|--renameMetadata|--renameFile|--renameDirectory`
    (are references updated?);
  - `import --importMetadata`;
  - `PUT /libraries`, `DELETE /libraries/{n}`, `library --deleteAll --type plugin` (only a test
    plug-in present);
  - `plugin --register|--deregister` (usage text first);
  - `PUT /edirules`;
  - `PUT /keys` (test keystore, test module);
  - `threads setNumberOfWorkflowThreads` and `privilegedWorkflows setThreadsPercentage` (set back
    afterwards);
  - `dbconnectiontolerance set` (usage, ranges);
  - `PUT /configurations` (one harmless value, set back; partial document accepted?);
  - `PUT /license` only if a test license file exists;
  - `POST /isconnector` (L-14);
  - `--backupFile`/`--backupRepository` usage text (L-16).

  Neutralize into `fixtures/v8_1/{cli,rest}/admin-objects-*`, `admin-libraries-*`,
  `admin-settings-change-*`.

### administer_objects

- [ ] T079 [US6] `CliCommand` object and library forms:
  - `rename --renameWorkflow|--renameModule|--renameMetadata|--renameFile|--renameDirectory
    <old> <new>`, with new names per `^[A-Za-z0-9_. -]{1,200}$`;
  - `import --importMetadata <path>`, where the path is the checked upload's real path;
  - `library --deleteAll --type driver|plugin`;
  - `plugin --register|--deregister` with the vendor's arguments as found in T078.
  - Failing tests first in `CliCommandTest.java`.
- [ ] T080 [US6] Object administration adapter `adapter/rest/v81/V81ObjectAdministrationAdapter.java`:
  - REST `DELETE /diagrams/{name}`, `/diagrams/{name}/version/{n}`, `DELETE /modules`
    (`moduleName`), `/modules/{id}/version/{n}`, `DELETE /repository` (path query),
    `POST /isconnector`;
  - `adapter/cli/admin/CliObjectRunner.java` for rename and metadata import;
  - `RestEndpoint` values;
  - `domain/port/ObjectAdministrationPort.java` and the `Gateway`/`PortSet` set.
  - Failing tests first: adapter and runner tests.
- [ ] T081 [US6] Library administration adapter `adapter/rest/v81/V81LibraryAdministrationAdapter.java`:
  - `PUT /libraries` (multipart `libraryFile`, `libraryType` Driver|Plugin, `libraryFileName`);
  - `DELETE /libraries/{name}`;
  - `PUT /edirules` (`ruleFile`, `ruleFileName`);
  - `PUT /keys` (`keystoreFile`, `owner`, `alias`, `privateKeyPassword` from the entry,
    `propertyName`);
  - `adapter/cli/admin/CliLibraryRunner.java` gets `deleteAll` and the plug-in commands.
  - Failing tests first: adapter tests; leak check (key password).
- [ ] T082 [US6] Administration backups (D-15; after T053):
  - `application/AdministrationBackups.java` takes the backup before every delete, rename or
    replace:
    - workflow and module through `CliExportRunner` with history;
    - metadata through `export --exportMetadata`;
    - repository path, EDI overwrite (only if the target file exists) and the module for a
      keystore through `export --exportRepositoryPath` or the module export;
  - on failure the change is not sent (`FAILED`, nothing sent).
  - `application/ImportService.java` `restore`: accepts an `ADMINISTRATION` manifest only on
    development nodes and only for `WORKFLOW` and `MODULE` contents; other kinds are refused with
    the manifest path for a manual import. The node rules are unchanged.
  - Failing tests first: `AdministrationBackupsTest.java`, `RestoreBackupAdministrationTest.java`
    (development node restores; a non-development node is refused as today; a tag list is
    refused with its path).
- [ ] T083 [US6] `application/ObjectAdministrationService.java`:
  - the actions of the contract, each with its `Area` (`OBJECTS` or `LIBRARIES`);
  - previews: owner, versions, tags, active flag and users of the object from the inventory port
    (AS2, AS17); a repository directory's file count (AS16); upload name, size and SHA-256 (AS3,
    AS7); `deleteAllLibraries` lists every file and the confirmation is valid only for that list
    (AS4); rename old and new name and referencing objects (AS9); an EDI target that exists (AS15);
    plug-in re-read (AS14);
  - `restartNeeded` for libraries;
  - `startIsConnector` and the backup start options are gated by their rows (T078 outcome).
  - Failing tests first: `ObjectAdministrationServiceTest.java`:
    - AS1–AS4, AS8–AS10, AS12, AS14–AS17;
    - the area of each action;
    - backup failure → nothing sent.
- [ ] T084 [US6] Tool `mcp/tools/AdministerObjectsTool.java`:
  - schemas `administer_objects.{input,output}.json`;
  - destructive;
  - announced when some group has `objects` or `libraries`.
  - Failing tests first: `AdministerObjectsToolTest.java`, `ToolHintsTest`, `ToolAnnouncementTest`.

### change_server_settings

- [ ] T085 [US6] `CliCommand`/session forms for settings changes:
  - `threads setNumberOfWorkflowThreads <n>`;
  - `privilegedWorkflows setThreadsPercentage <0..99>`;
  - `dbconnectiontolerance set …` (arguments per T078);
  - `RestEndpoint` + `PUT /configurations`, `PUT /license`;
  - `adapter/rest/v81/V81SettingsAdministrationAdapter.java` and
    `adapter/cli/admin/CliSettingsRunner.java` change methods;
  - `domain/port/SettingsAdministrationPort.java` and the `Gateway`/`PortSet` set.
  - Failing tests first: `CliCommandTest`, adapter tests.
- [ ] T086 [US6] `application/SettingsAdministrationService.java`:
  - threads: 1 … licensed threads (from `GET /license`, fallback `/metrics`), otherwise
    `INVALID_INPUT` naming the licensed number (AS5);
  - privileged share 0 … 99 (AS11);
  - tolerance preview with the shut-down sentence (AS13);
  - `changeConfiguration`: 1–20 changes; a path per the data-model pattern; a masked path needs a
    `secretEntry` and a plain value gives `INVALID_INPUT` (FR-022); preview current/new masked on
    both sides (AS6); the full document is re-read and sent per T078 (full or partial);
  - license preview and re-read (AS7);
  - `restartNeeded`: always for threads and the privileged share, per T078 for the rest;
  - no backup (FR-018: the preview shows the current value).
  - Failing tests first: `SettingsAdministrationServiceTest.java` (AS5, AS6, AS7, AS11, AS13;
    `CONFLICT` when the value changed since the preview; leak check).
- [ ] T087 [US6] Tool `mcp/tools/ChangeServerSettingsTool.java`:
  - schemas `change_server_settings.{input,output}.json`;
  - destructive;
  - announced when some group has `settings`.
  - Failing tests first: tool test, `ToolAnnouncementTest`.
- [ ] T088 [US6] Cross-cutting safety for both tools:
  - `WriteRaceToolsTest` +2;
  - production and write-off refusals per action (SC-003, SC-004);
  - an area-off refusal per action (`NOT_CONFIGURED`);
  - `security/AdministrationSecretLeakTest.java` (configuration, key password, upload content).
- [ ] T089 [US6] Contracts, flips, docs and PR [BLOCKED: T078]:
  - `ObjectsV81ContractTest.java`, `LibrariesV81ContractTest.java`,
    `SettingsChangeV81ContractTest.java`;
  - rows (excluded per T078 where the check failed), matrix, docs page;
  - `docs/tools.md` sections;
  - `docs/setup.md` (`administration.objects|libraries|settings`, `uploads`, secret entries per
    source with examples);
  - `SECURITY.md` (uploads, secret entries);
  - `CHANGELOG.md`;
  - `delivered-stories` + US6.

  Then open the US6 PR.

## Phase 9: User Story 7 – Administer users and groups (P4)

**Goal**: `administer_users` creates, changes and deletes users, groups and roles, sets passwords
from named entries, imports from files and the directory service, sends and deletes messages,
and never touches the server's own access (US7).

**Independent test**: with fakes, a user is created and deleted with preview and code; no call
takes or returns a password; the login user cannot be changed (US7 AS1–AS10).

- [ ] T090 [LIVE-W] [OWNER] [US7] L-12, L-13, L-15, L-18 on the 8.1 test server, with approval and
  fictitious test users only:
  - `create -cu` with and without `--password` in a session (does the session hide it from
    `ps`?), `-cug`, `-cur --basedon`;
  - `user --setGroup|--setAdditionalGroups|--addAdditionalGroups|--removeAdditionalGroups`;
  - `passwd --password` in a session;
  - `DELETE /user/users/{name}` with both reference parameters;
  - `delete --deleteUserGroup`;
  - an export of a user, group or role file to learn the import format (L-12);
  - `finger` of a directory-managed user if one exists (L-13);
  - `msgsend`, `msgdel`.

  Neutralize into `fixtures/v8_1/{cli,rest}/admin-users-change-*` (session `.stdin` with the
  password replaced).
- [ ] T091 [US7] Session and command forms (D-7, D-25):
  - `CliSessionCommand.SESSION_OPTIONS` admits `create -cu …--password`, `passwd --password`
    (session only; the value comes from a `Secret` and is registered with the scrubber);
  - `CliCommand` admits the forms without a secret: `create -cu` (no password), `-cug`, `-cur`,
    `user --set…|--add…|--remove…`, `delete --deleteUserGroup`,
    `import --importUserGroup|--overwriteUserRoles|--importLDAPDetails <path>`,
    `--syncLDAPUsers`, `msgsend -s '<subject>' '<text>'`, `msgdel <id>`.
  - `CliCommand` rejects `--password` in every form.
  - Failing tests first: `CliCommandTest`, `CliSessionCommandTest`, and
    `security/PasswordNeverOnCommandLineTest.java` (every recorded `LaunchSpec` of the user tests:
    argv and env contain no entry value).
- [ ] T092 [P] [US7] `application/OwnAccess.java` (D-16):
  - reads the login user's groups and roles (`CliUserRunner`) at preview and again before
    sending;
  - refuses deleting, regrouping or re-passwording the login user, removing its groups, and
    deleting its primary group or a role it holds;
  - the CLI-access right rule per T090 (L-15).
  - Failing tests first: `OwnAccessTest.java` (each refusal; a change between preview and send →
    refused).
- [ ] T093 [P] [US7] `adapter/cli/admin/UserImportFileParser.java` (format per T090):
  - finds logins, groups and roles in an import file (hardened XML, or the found format);
  - an unparsable file gives `INVALID_INPUT`.

  `application/UserImportCheck.java` refuses a file naming the login user, its group or one of
  its roles, and names which (AS6). `--syncLDAPUsers` is refused unless the login user is shown as
  internal, and also when this cannot be determined (AS7, FR-019b).
  - Failing tests first: parser and check tests.
- [ ] T094 [US7] Adapter and port:
  - `adapter/rest/v81/V81UserAdministrationAdapter.java`: `DELETE /user/users/{name}` with
    `userReferences` and `userRepoReferences`, both explicit;
  - `adapter/cli/admin/CliUserAdministrationRunner.java` for the CLI forms and the session
    password path;
  - `domain/port/UserAdministrationPort.java` and the `Gateway`/`PortSet` set.
  - Failing tests first: adapter tests; leak check.
- [ ] T095 [US7] `application/UserAdministrationService.java`:
  - the actions of the contract under `Area.USERS`;
  - `createUser` takes an optional `secretEntry`; without it, the description names what T090
    found (AS1);
  - `setPassword` requires `secretEntry` (AS2, AS3);
  - `deleteUser` requires both choices and re-reads the list, because INUBIT answers 200 even for
    an unknown user (AS5);
  - message text 1–1,000 characters with no control characters, subject 1–200 characters with no
    control characters or `'` (AS8);
  - previews of groups and roles (AS9, AS10);
  - `OwnAccess` and `UserImportCheck` before every send.
  - Failing tests first: `UserAdministrationServiceTest.java` (AS1–AS10).
- [ ] T096 [US7] Tool `mcp/tools/AdministerUsersTool.java`:
  - schemas `administer_users.{input,output}.json`, with no password field in either;
  - destructive;
  - announced when some group has `users`.
  - Failing tests first: tool test, `ToolAnnouncementTest`, `WriteRaceToolsTest` +1, the
    refusal tests per action, `security/UserAdminSecretLeakTest.java`.
- [ ] T097 [US7] Contracts, flips, docs and PR [BLOCKED: T090]:
  - `UserAdminV81ContractTest.java`;
  - rows (`passwd`/`create -cu --password` set to `excluded:secret only on a visible command line`
    if T090 showed the session cannot hide it), matrix, docs page;
  - `docs/tools.md`;
  - `docs/setup.md` (`administration.users`);
  - `SECURITY.md`;
  - `CHANGELOG.md`;
  - `delivered-stories` + US7.

  Then open the US7 PR.

## Phase 10: Polish & cross-cutting concerns

- [ ] T098 9.0 recordings for US2–US7 [LIVE] [OWNER] (after the 9.0 pin is renewed):
  - run the read checks of T028, T039 and the approved write checks of T054, T055, T068, T078,
    T090 on the 9.0 test server;
  - fixtures with `.origin`, catalogue entries;
  - `*V90ContractTest` per story;
  - the `v9_0` rows flipped.
- [ ] T099 Coverage completion (SC-001, FR-027): `InterfaceCoverageCheckTest` with all seven
  stories delivered. No `planned` row and no `# waits` comment are left: every row is
  `reachable`, `covered`, `undocumented` or `excluded`. Remove the backlog entry "Further read
  access to INUBIT" from `docs/backlog.md`.
- [ ] T100 [P] `live/AdministrationLiveTest.java` (`@Tag("live")`, opt-in, refused for production
  groups):
  - reads of US1–US3;
  - one reversible change per change tool (trace level set and back) on a test server;
  - `docs/live-tests.md`.
- [ ] T101 [P] `README.md` tool list and capability overview; `SECURITY.md` summary of the new
  protections (areas, uploads, secret entries, own-access rule).
- [ ] T102 Final validation:
  - all quickstart tests green;
  - the smoke steps 1–7 of `quickstart.md` run on the test servers and noted in its validation
    record;
  - the SC-001…SC-009 checklist written into `reviews/implementation.md`;
  - the tool count compared with the T001 reference (+9, SC-007).

## Dependencies

- **Phase 1 → Phase 2 → Phase 3 (US1)**: Phase 2 blocks every story.
- **Stories**: in priority order, one PR each (FR-004). The code dependencies are:
  - US2 needs T012 (session);
  - US3 needs T011;
  - US4 introduces the change infrastructure T048–T053, used by US5, US6 and US7;
  - US5 introduces the `administration` config (T066), used by US6 and US7;
  - US6 introduces uploads (T074–T075), secret entries (T076) and the REST write methods (T077),
    used by US7 (imports, passwords);
  - US7 needs `CliUserRunner` (T032) from US2.
- **Live tasks**: T014, T028, T039, T054, T055, T068, T078, T090 and T024/T098 can run as soon as
  the operator approves them, in parallel with the offline tasks. Only the contract and flip tasks
  (T025, T026, T037, T046, T065, T073, T089, T097, T098) wait for them.
- **PR merges**: a PR may merge with rows still `planned` while its live check is open (the
  action is then not offered). Phase 10 needs all of them.

## Parallel opportunities

- **Phase 2**: T004→T005 and T006→T007→T008/T009 run in parallel; T012 and T013 are independent
  of both.
- **US1**: T015, T016, T017, T018 [P] after Phase 2; T014 (live) at any time.
- **US2**: T029, T030, T031 [P].
- **US3**: T040 [P] beside T041.
- **US4**: T048, T050 [P]; then `operate_server` (T056–T060) and `manage_tags` (T061–T063) in
  parallel after T049–T053.
- **US5**: T066 and T067 [P].
- **US6**: T074 [P]; then `administer_objects` (T079–T084) and `change_server_settings`
  (T085–T087) in parallel after T075–T077.
- **US7**: T092, T093 [P].

Never run two Maven builds at the same time in the same checkout.

## Implementation strategy

- **MVP**: Phases 1–3 (US1). The coverage list and its tests, the REST registry, the session, the
  secret rules and `get_server_settings` on 8.1. This is the first PR; it already shows every
  open entry on `docs/interface-coverage.md`.
- **Next increments**: US2 and US3 (reads, no risk), then US4 (first changes, introduces the
  shared change infrastructure), US5, US6, US7. Each increment ends with its PR, is merged after
  green CI and can be released on its own.
- **Live checks** run early and in parallel. An increment does not wait for its 9.0 recordings:
  9.0 rows flip in T026 and T098 once the pin question is settled.
- **Stop points**: after each phase, a code review of the phase diff and an update of
  `reviews/implementation.md`. Push after each finished task.
