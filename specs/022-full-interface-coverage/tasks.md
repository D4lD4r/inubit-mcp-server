# Tasks: Full Coverage of the StartCLI and REST Interfaces

**Input**: Design documents from `specs/022-full-interface-coverage/`: spec, plan, research
D-1…D-27 and L-1…L-21, data-model, `contracts/mcp-tools-delta.md`,
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

- [X] T001 Baseline and record, in this order:
  1. Confirm feature 021 is merged into `main` and this branch is rebased onto it.
  2. Run `mvn -o -q clean verify` with the tmpdir above, plus the Python tests: green.
  3. Create `specs/022-full-interface-coverage/reviews/implementation.md`.
  4. Note there the test count, the exact tool list of `ToolAnnouncementTest` (SC-009 reference)
     and the three plan defaults in force unless the owner overrides them:
     - US5 as rewritten;
     - `get_server_settings` as its own tool;
     - version deletions offered.
- [X] T002 [P] Fixture layout (D-26): add an "Administration cases (feature 022)" section to
  `src/test/resources/fixtures/v8_1/cli/README.md` and `src/test/resources/fixtures/v9_0/README.md`.
  - Case names: `admin-<entry-slug>[-<variant>]`.
  - CLI cases: `.stdout`/`.stderr`/`.exit`; session cases also `.stdin` (with the password line
    replaced by `<password>`).
  - REST cases: `.http` + body.
  - 9.0 `.origin` formats: `recorded <date> <version> neutralized` / `reconstructed <source>`.
  - Rule: every user, workflow, host and value is fictitious, and every secret is replaced by a
    planted test secret.
  - No case files yet.
- [X] T003 [P] Private raw-output folder rule: document in `docs/live-tests.md` (new section
  "Recording administration commands") where raw outputs of [LIVE] tasks go
  (`~/inubit-mcp-recordings/022/`, outside every repository). Also document the approval line
  format the operator writes in the chat for a [LIVE-W] command: server, command, expected
  change, how to undo it.

## Phase 2: Foundational — coverage list, REST registry, session, secret rules (blocks every story; ships with the US1 PR)

- [X] T004 `RestEndpoint` registry (D-8):
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
- [X] T005 Move every REST adapter to `RestEndpoint` (after T004; no behaviour change):
  - Files: `adapter/rest/v81/*Adapter.java`, `V81MaintenanceProbe.java`,
    `V81VersionDetector.java`, `LogEndpoint.java`, `adapter/V81InventoryAdapter.java`.
  - `InubitHttpClient.get/post` accept a `RestEndpoint` plus parameters (the old string overloads
    are removed).
  - Failing test first: `architecture/RestEndpointRegistryTest.java` scans the compiled classes
    and fails when a class other than `RestEndpoint` holds the constant `/ibis/rest`.
  - Done when all existing REST tests are green unchanged.
- [X] T006 Coverage domain (D-2, data-model "Coverage"):
  - `domain/model/CoverageEntry.java`;
  - `domain/model/CoverageState.java`: sealed, with `Reachable(tool, action)`, `Covered(tool)`,
    `Planned`, `Undocumented`, `Excluded(reason)`;
  - `domain/model/InterfaceCoverage.java` with `entries()`, `state(channel, entry, line)` and
    `offered(Set<CoverageKey>, line)` (true only when every given entry is `Reachable` for that
    line; callers pass the entries of a concrete call or part, D-2);
  - a loader that parses the TSV format of `contracts/coverage-list.md`: comment lines,
    `delivered-stories`, the header `channel entry kind area story v8_1 v9_0 waits`.
  - Loading fails on a duplicate (`channel`, `entry`), a missing version state, an unknown state
    word, a reason over 120 characters, `planned` without `story`, an unknown `waits` value, a
    `waits` value other than `-` on a row not `planned` or on a row of a story not yet delivered,
    and a `planned` row of a delivered story with `waits` = `-`; `waits` items are joined by `+`
    (`L-1+9.0-pin`) (data-model `CoverageEntry`).
  - Failing tests first: `domain/model/InterfaceCoverageTest.java`, one test per failure rule and
    for `offered`.
- [X] T007 Initial coverage list (after T006): `src/main/resources/coverage/interface-coverage.tsv`
  with every entry of the vendor pages "Command Line Interface" (with subpages) and "REST API"
  (every endpoint page) for 8.1 and 9.0.
  - **Source**: re-download the pages named in `docs/research/inubit-interfaces.md` §6 with curl
    into a private folder, and parse them with `python3 -I` from a script kept outside that
    folder.
  - **Granularity**: per `contracts/coverage-list.md`.
  - **Rows**:
    - Entries the server sends today: `reachable:<tool>/default`. Every existing tool uses the
      action key `default` (research D-2), including `export_archives` until T044.
    - The rows of D-6: `covered:<tool>` where the tool exists today; rows whose covering tool
      belongs to a later story stay `planned` with that story (research D-6, "Covered rows before
      their tool exists").
    - The portal entries: `excluded:portal function`.
    - The exclusions of the spec Background: `lls`, cache mode, `validateMigrationBackup`,
      `--encryptString`, `processLog`, `--tagDiagram`, `--tagWorkflowGroupExclude`, `inFile`,
      `--filterProcessIds`. Each gets its reason.
    - `GET /model/nodeByName/…` on 9.0: `undocumented`.
    - Every other entry: `planned` with its story from the spec Background table.
  - Header: `delivered-stories:` empty; every row `waits` = `-`.
  - Also add `domain/model/InterfaceCoverage.production()`, which loads this resource.
  - From the T006 review: each `delivered-stories` token is checked against `US[1-7]` (failure
    with the line number, one test), and `failsOnAnUnknownChannelKindAreaStoryOrAnEmptyEntry` in
    `InterfaceCoverageTest` asserts the expected message part per case (`channel`, `kind`,
    `area`, `story`, `entry`).
  - Failing test first: `InterfaceCoverageResourceTest` asserts that the file loads, has at least
    one row per area A–E, has no `planned` row without a story, and that `lls` is excluded.
- [X] T008 Coverage checks (FR-003; after T005, T007): `architecture/InterfaceCoverageCheckTest.java`
  implements checks 1–5 of `contracts/coverage-list.md`. Check 6 is added in T010.
  - Checks 1–3 read `CliCommand.OPTIONS`, the `CliSessionCommand` constants, `RestEndpoint.values()`
    and `TestWiring` tool handlers.
  - Check 3 applies the action-key rule: the value of `action`, `view` or `content` in the
    tool's input schema, or `default` when the schema has none of them.
  - Failing first by adding one temporary unlisted `RestEndpoint` value in the test's own fixture
    enum.
- [X] T009 Generated docs page (after T007): `domain/model/CoverageDocs.java` renders
  `docs/interface-coverage.md`:
  - a source line;
  - counts per state and version;
  - one table per area with columns entry, kind, story, 8.1, 9.0.
  - Test `mcp/InterfaceCoverageDocsTest.java` compares the file with the rendering. With
    `-Dcoverage.write=true` it rewrites the file. The failure message names that command.
  - Generate and commit the page.
- [X] T010 `ActionSpec` and the coverage gate (D-2, D-12 step 5; after T006, T008):
  - `application/ActionSpec.java`: `tool`, `action`, `Area` (`OPERATION`, `OBJECTS`, `LIBRARIES`,
    `SETTINGS`, `USERS`, `RUNTIME_DATA`, `READ`), `capability` (the tool's `Capability`, passed to
    the existing capability admission), `entries(input)` (the entries a concrete call
    sends, by variant), `parts` (per part: entries and channel, for multi-part reads),
    `declaredEntries()` (the union), `kind` (`READ`, `CHANGE`, `RUNTIME_READ`), `bulk`, `backup`,
    `restartNotice` (research D-2, "gate per call").
  - `domain/model/InterfaceCoverage.java` gains `refusal(Set<CoverageKey>, line)`, the first
    entry that is not `Reachable` with its state, for the error text.
  - `application/ActionCatalog.java`: registry by (tool, action).
  - `application/VersionGate.java`:
    - new `admitCall(String tool, NodeId, ActionSpec, Set<CoverageKey> callEntries)`: runs the
      existing capability admission, then `InterfaceCoverage.offered(callEntries)`; when not
      offered, `UNSUPPORTED_VERSION` naming the variant and "not yet verified", "not documented
      for this version" or the exclusion reason;
    - new `admitParts(String tool, NodeId, ActionSpec, Set<Part>)`: returns the admitted parts and,
      for each other part, an `UnavailablePart` with that reason or `CLI_UNAVAILABLE` (no client
      for a CLI part). It refuses only when no requested part is admitted.
  - Failing tests first: `application/VersionGateActionTest.java`:
    - `reachable`, `planned`, `excluded`, `undocumented` on 9.0; 6.1 always refused;
    - a variant refused while its sibling variant is admitted (`moduleName` vs `moduleId` on
      9.0);
    - a part not reachable → `unavailable` with "not yet verified" while the others are admitted;
    - a node without a StartCLI client → CLI parts `unavailable` with `CLI_UNAVAILABLE`, REST parts
      admitted;
    - no part admitted → refused.
  - Also add check 6 of `contracts/coverage-list.md` to `architecture/InterfaceCoverageCheckTest.java`:
    every registered `ActionSpec` declares at least one row, and every declared row exists. It
    passes with the empty catalogue and gains entries with each story. From the T008 review:
    derive the test's `ALL_STORIES` from one shared source with `CoverageEntry`'s story pattern
    (e.g. a public `CoverageEntry.STORIES`).
- [X] T011 Capability for US1 and the pattern for later stories (D-1; after T010):
  - In `domain/model/Capability.java` add `SERVER_SETTINGS` (READ, REST+CLI) with its `label()`.
  - In `CapabilityMatrix.production()`: 8.1 and 9.0 `UNSUPPORTED` + "not yet verified"; 6.1
    unsupported.
  - Add its name to `application/NodeOverview.java` and to the `CapabilityName` enum in
    `src/main/resources/schemas/list_nodes.output.json`.
  - Each later story adds its own capability the same way, in its port task (T033, T042, T058,
    T063, T071, T081, T087, T096): `USER_DIRECTORY`, `MODEL_DETAILS`, `SERVER_OPERATION`,
    `TAG_ADMINISTRATION`, `RUNTIME_TRANSFER`, `OBJECT_ADMINISTRATION` (objects and libraries),
    `SETTINGS_ADMINISTRATION`, `USER_ADMINISTRATION`. This way `list_nodes` never names a
    capability whose tool is not delivered.
  - From the T010 review (cleanup): `TestWiring` imports `ActionCatalog` instead of the fully
    qualified name; the `admitCall` refusal for a node without a known version names the action
    key as given (not capitalized), with a test for the AUTO-undetected case.
  - Failing tests first: `domain/model/CapabilityMatrixTest.java` and
    `mcp/tools/ListNodesToolTest.java` (`server_settings` in `notUsable` with "not yet verified").
- [X] T012 Version-neutral StartCLI session (D-7):
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
- [X] T013 `SecretRules` extraction (D-19):
  - `domain/model/SecretRules.java` (public): `isSecretName(String)`, `isSecretPath(String)`,
    `masks(String name, Optional<String> type, boolean encrypted, String value)`. It uses the
    patterns of `adapter/archive/v81/PartnerSecretRules.java` (`SECRET_TYPE`, `SECRET_NAME`) and
    `KeyMaterial`.
  - `PartnerSecretRules` delegates to it.
  - `adapter/rest/v81/JsonLeafMasker.java` (package-private; `domain` may not use Jackson,
    `PackageBoundaryTest`) walks a Jackson tree and masks leaves by `SecretRules`,
    returning a masked copy and `maskedCount`.
  - Failing tests first: `domain/model/SecretRulesTest.java` and
    `adapter/rest/v81/JsonLeafMaskerTest.java` (nested objects, arrays, `encrypted` sibling flag, key
    material value).
  - All 018 tests stay green unchanged.

## Phase 3: User Story 1 – Read the server's state and settings (P1) 🎯 MVP

**Goal**: `get_server_settings` returns license, threads, privileged workflows, DB connection
tolerance, configuration (by section) and server time per server (US1).

**Independent test**: with recorded StartCLI outputs and REST responses, one call gives each fact
as a typed field with its source, and missing parts with their reason (US1 AS1–AS7).

- [X] T014 [LIVE] [OWNER] [US1] Read recordings for US1 and the session check (L-1, L-5, the read part
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
- [X] T015 [P] [US1] Domain records (data-model "ServerSettings"): `domain/model/ServerSettings.java`,
  `ConfigurationView.java`, `LicenseFacts.java`, `DbConnectionTolerance.java`,
  `PrivilegedWorkflows.java` (≤ 200 names, `truncated`).
  - New `UnavailablePart` parts: `license`, `threads`, `privilegedWorkflows`,
    `dbConnectionTolerance`, `configuration`, `serverTime`.
  - The new part names (`license`, `threads`, `privilegedWorkflows`, `dbConnectionTolerance`,
    `configuration`, `serverTime`) are added to `UnavailablePart.PARTS`, which `Part` requires
    (T010 review).
  - Failing tests first: `domain/model/ServerSettingsTest.java` (bounds, parts).
- [X] T016 [US1] `adapter/rest/v81/LicenseXmlParser.java` (after T015):
  - uses `XmlSupport` hardening;
  - reads the expiry date, licensed threads and licensee (the licensed threads are used only when
    `/metrics` is not licensed; data-model `ServerSettings`);
  - the licensee is masked by `SecretRules`, and any element matching `SecretRules` is masked.
  - From the T015 review (cleanup): `UnavailablePart`'s error message says "unknown part"
    (adjust `UnavailablePartTest`), and `ServerSettings`' Javadoc gets the missing `@param` lines
    for `node`, `privilegedWorkflows` and `dbConnectionTolerance`.
  - Failing tests first: `LicenseXmlParserTest.java` with a reconstructed fixture (the real one
    after T014): DTD refused, unknown elements ignored, missing expiry → part unavailable.
- [X] T017 [US1] `adapter/rest/v81/ConfigurationJsonParser.java` (after T015):
  - accepts the root key `SeverConfiguration` and `ServerConfiguration`;
  - the section names are the top-level keys below the root;
  - a section's leaves are flattened to `{path, value | masked}` through `JsonLeafMasker`, with
    `maskedCount`;
  - output is bounded by `ResultLimiter` with `truncated`.
  - From the T013 review: one key-material rule only (FR-010): `SecretPaths.isKeyMaterial` keeps
    only its placeholder and `documentName` checks and calls `SecretRules` for the value, so
    partner masking and configuration masking cannot diverge (018 tests unchanged). Check the
    recorded configuration JSON (T014) for sibling type fields; if it has them, `JsonLeafMasker`
    passes them as the `type` argument of `SecretRules.masks`.
  - From the T015 review: `ConfigurationView.Leaf.masked()` means "value empty", so a JSON
    `null` or absent value is shown as a value (e.g. `""` or `"null"`), never as
    `Optional.empty()`; say so in the `Leaf` Javadoc and test it.
  - Failing tests first: `ConfigurationJsonParserTest.java` (both root keys, sections only, one
    section, unknown section → `NOT_FOUND` naming the listing, masking; leak check).
- [X] T018 [US1] StartCLI settings parsers in the new package `adapter/cli/admin/`
  (`package-info.java`; after T015):
  - `ThreadsParser`;
  - `PrivilegedWorkflowsParser` (percentage and list);
  - `DbToleranceParser` (fault threshold, drop rate %, drop interval s; the vendor example shows
    `Drop rate: 1%` / `Drop interval (s): 180 seconds`);
  - `ServerDateParser`.
  - Each takes one session block; an unknown text gives `UNEXPECTED_RESPONSE` with a masked
    excerpt ≤ 500 characters, never a guess.
  - Failing tests first: one test class per parser with reconstructed cases (recorded after
    T014).
- [X] T019 [US1] StartCLI commands for settings reads (after T012, T018):
  - `CliSessionCommand.SESSION_OPTIONS` admits `threads showNumberOfWorkflowThreads`,
    `privilegedWorkflows showThreadsPercentage|showWorkflowList`, `dbconnectiontolerance show`,
    `date`;
  - `adapter/cli/admin/CliSettingsRunner.java` runs them in one session bounded by `cliTimeout`
    and maps each block to its parser; a failed block marks only its part unavailable (FR-013).
  - From T012: `InterfaceCoverageCheckTest` check 1 also reads `CliSessionCommand.SESSION_OPTIONS`
    (every admitted command/option has a row), now that it gets entries.
  - Failing tests first:
    - `adapter/cli/CliSessionCommandTest` (allowed forms; `threads setNumberOfWorkflowThreads`
      rejected);
    - `adapter/cli/admin/CliSettingsRunnerTest.java` (scripted session; one block missing → that
      part unavailable; the StartCLI refusal for a missing "CLI login access" right gives
      `FORBIDDEN` naming the right, not `AUTH_FAILED` (edge case)).
- [X] T020 [US1] Port and wiring:
  - `domain/port/ServerSettingsPort.java` (`license()`, `configuration(Optional<String> section)`,
    `cliSettings(Set<Part>)`);
  - `adapter/rest/v81/V81SettingsAdapter.java` (REST parts);
  - `RestEndpoint` + `GET /license`, `GET /configurations`;
  - added to `domain/port/Gateway.java`, `GatewayFactory.java` (default method),
    `adapter/PortSet.java`, `adapter/RefusingPorts.java`, `adapter/StaleReportingPorts.java`,
    `adapter/V81Gateway.java`, `adapter/AdapterGatewayFactory.java`, `V81PortSet.java`
    (9.0 reuses it).
  - From the T017 review: `ConfigurationJsonParser.parse` takes `maxValues`/`maxChars`; the port
    passes the `ResultLimiter` limits of the call (e.g. `configuration(section, limits)`), and the
    parser rejects `maxValues < 1` like `ResultLimiter.validate` and cuts a `Leaf.path` longer than
    `ItemBounds.MAX_FIELD_CHARS` with the truncation marker.
  - Failing tests first: `adapter/rest/v81/V81SettingsAdapterTest.java` (WireMock: 200; 403 →
    `FORBIDDEN` naming "System Administrator"; 503 → `MAINTENANCE_MODE`) and `WiringAuditTest`.
- [X] T021 [US1] `application/ServerSettingsService.java`:
  - `read(target, parts, section)`;
  - a stage gives one entry per server (AS5);
  - default parts are all but `configuration`; a section implies `configuration`;
  - version, memory share, blocking queue size and maintenance come from the same `MonitoringPort`
    calls `HealthService` uses, with no StartCLI call for them (AS7, FR-002);
  - each part has its source in `sources`;
  - admitted through `VersionGate.admitParts` with `ActionSpec(get_server_settings, default, READ)`
    and one part per fact group, each with its entries and channel; a part that is not reachable,
    or a CLI part on a node without a client, goes to `unavailable` (FR-013); the call is refused
    only when no part is admitted;
  - `licensedThreads` comes from `/metrics`, and from `GET /license` only when `/metrics` is not
    licensed; `sources` names which.
  - From the T010 review: decide and test what happens when the node's StartCLI client is
    incompatible with the server version (`checkClient` → INCOMPATIBLE): the CLI parts go to
    `unavailable` with that reason and the REST parts are returned (FR-013), instead of
    `admitParts` refusing the whole call.
  - From the T020 review: two configuration keys longer than `ItemBounds.MAX_FIELD_CHARS` with a
    common prefix are cut to the same `Leaf.path`; state this in the `Leaf` Javadoc (the value
    order stays the document order).
  - Failing tests first: `application/ServerSettingsServiceTest.java`:
    - AS1, AS3, AS4 (role missing for license → the other parts returned), AS5, AS7;
    - one part `excluded` in a test coverage file → the other parts returned;
    - node without a StartCLI client → the REST parts returned, the CLI parts `unavailable`;
    - no `-sv`/`-pctmem`/`getBlockingQueueSize` ever sent (`ScriptedProcessLauncher` records
      argv and stdin).
- [X] T022 [US1] Tool `mcp/tools/GetServerSettingsTool.java`:
  - schemas `src/main/resources/schemas/get_server_settings.{input,output}.json` per
    `contracts/mcp-tools-delta.md`;
  - `ToolHints.readOnly(title, true)`;
  - registered in `Wiring.toolHandlers()` when `anyPossiblyCapable(SERVER_SETTINGS)`.
  - From the T021 review (comments only): the `ServerSettingsService` class Javadoc says the three
    monitoring reads run on every call (the result always carries version, memory share, blocking
    queue size and maintenance mode); the `VersionGate.admitParts` comment says "a refusal that only
    the StartCLI channel adds" instead of naming only the incompatible client.
  - Failing tests first:
    - `mcp/tools/GetServerSettingsToolTest.java` (full description text, annotations, validation
      errors);
    - `GetServerSettingsOutputSchemaTest.java`;
    - `ToolAnnouncementTest` (new ordered lists).
- [X] T023 [US1] `security/ServerSettingsSecretLeakTest.java`: planted secrets in license XML,
  configuration leaves, StartCLI output and errors never appear in results, errors, logs (logback
  test appender) or audit; leak check.
- [X] T024 [LIVE] [OWNER] [US1] The same recordings as T014 on the 9.0 test server, into
  `fixtures/v9_0/{cli,rest}/admin-settings-*` with `.origin`. Blocked until the 9.0 server's
  certificate pin is renewed (feature 021 open point); record that state in `implementation.md`
  while waiting.
  - Unblocked 2026-11-13: the owner confirmed the new certificate of the 9.0 test server as
    legitimate; its pin was renewed with `inubit-cert-check` and every dependent 9.0 step was
    approved (owner, 2026-11-13).
- [X] T025 [US1] Contracts and flip for 8.1 [BLOCKED: T014]:
  - `license -td` and `license -ts` change from `planned` to `covered:get_server_settings` for
    `v8_1` (research D-6), never to `reachable`;
  - `adapter/rest/v81/SettingsV81ContractTest.java` and
    `adapter/cli/admin/SettingsCliV81ContractTest.java` against the recorded fixtures;
  - flip the US1 rows for `v8_1` to `reachable:get_server_settings/default` (a row's `waits` goes
    back to `-` once no version is `planned`), the `SERVER_SETTINGS`
    matrix entry for 8.1 to `SUPPORTED`;
  - regenerate the docs page.
  - Same commit as the fixtures.
  - If L-1 showed no session on 8.1, switch `CliSettingsRunner` to one script call per command
    first and record the D-7 fallback in `research.md`.
  - From the T012 review: align `CliSessionParser` with the recorded L-1 form. If the client
    echoes each command after the prompt, drop the echoed command line from each block and accept
    the echoed `exit`; otherwise remove the echo from `CliRunnerSessionTest`'s client model. Note
    the recorded behaviour in the parser Javadoc.
  - From the T016 review: with the recorded license format, align `LicenseXmlParser`'s element
    names; a `licensedThreads` value that is not a positive whole number leaves only the threads
    empty instead of failing the whole `license` part (the threads are only the `/metrics`
    fallback); rename or drop the `doesNotContain` assertion on `facts.toString()` in
    `LicenseXmlParserTest`.
  - From the T013/T017 reviews: check the recorded `/configurations` answer for sibling type
    fields or `encrypted` flags; if there are any, `JsonLeafMasker` passes the type to
    `SecretRules.masks`; replace the SYNTHETIC `admin-configurations-reconstructed` and
    `admin-license-reconstructed` cases by the recordings.
  - From the T018 review: replace the five `admin-settings-*-reconstructed` CLI cases with the
    recordings and align the forms of `ThreadsParser`, `PrivilegedWorkflowsParser`,
    `DbToleranceParser` and `ServerDateParser` (check whether StartCLI output is localized).
  - From the T019 review: anchor `CliOutputClassifier.MISSING_CLI_RIGHT` to the recorded refusal
    code or text for a missing "CLI login access" right (L-1), and add classifier tests for that
    text and for the recorded wrong-credentials text (the guard must still pause on the latter).
- [X] T026 [US1] 9.0 contracts and flip: `SettingsV90ContractTest.java`, rows
  and matrix for `v9_0`, catalogue entries.
  - the same for the `v9_0` column of `license -td` / `license -ts` (`covered:get_server_settings`);
- [X] T027 [US1] Docs and release notes (FR-027):
  - `docs/tools.md`: section `get_server_settings`, the capability list, the error codes used;
  - `docs/setup.md`: the role "System Administrator" for license and configuration;
  - `docs/research/inubit-interfaces.md`: §1.3 and §2.2 now point to the coverage page;
  - `docs/interface-coverage.md` regenerated;
  - `CHANGELOG.md` `[Unreleased]`;
  - `delivered-stories: US1` in the TSV; every US1 row still `planned` gets its `waits` value,
    one item per reason joined by `+` (`9.0-pin` for 9.0 until T026, `L-n` for a live check,
    e.g. `L-1+9.0-pin`).
  - Failing first: `ArtifactToolsReferenceTest` / `DevelopmentToolsReferenceTest` extended for
    the new tool section.
  - Then open the US1 PR (phases 1–3).

## Phase 4: User Story 2 – Read users, groups and logins (P1)

**Goal**: `read_users` lists users without personal fields, reads one user, the logins and the
messages (US2).

**Independent test**: with recorded outputs, a page of users with groups and roles, one user with
e-mail, the logins and a message, each bounded (US2 AS1–AS6).

- [X] T028 [LIVE] [OWNER] [US2] L-6, L-17 on 8.1 (and 9.0 when possible):
  - `GET /user/users?type=processEngineUser`;
  - a session with `user <login>` and `finger <login>` for three fictitious-named test users;
  - `-who`, `-nwho`;
  - `msglist`, then `msgread <id>`, then `msglist` again (does the state change?).

  Neutralize into `fixtures/v8_1/{rest,cli}/admin-users-*` (personal fields replaced by
  `*.example.test` values). Record whether `msgread` marks the message as read.
- [X] T028a [LIVE] [OWNER] [US2] L-6 logins on 8.1 (added 2026-11-13 after T028: `-who`/`-nwho`
  are StartCLI client options, not session commands):
  - record `startcli.sh … --who <url>` and `startcli.sh … --nwho <url>` once each, read-only, on
    the T028 server (owner approval 2026-11-13);
  - neutralized fixtures `fixtures/v8_1/cli/admin-users-who.*` and `admin-users-nwho.*`, README
    rows, research section "Live recording T028a";
  - D-20 "Logins" and T032 changed to one client run per form (not in the session,
    `SESSION_OPTIONS` without `-who`/`-nwho`); the coverage entries keep their keys `-who`/`-nwho`.
  - L-17 stays open by owner decision (2026-11-13): the message reads stay `planned`, no
    `msgread` parser before a recording.
- [X] T029 [P] [US2] Domain records: `domain/model/UserSummary.java` (no personal field by
  construction), `UserDetail.java`, `LoginEntry.java`, `MessageSummary.java`, `MessageText.java`
  (`ItemBounds.MAX_ITEM_CHARS`, `truncated`).
  - Failing tests first: `domain/model/UserRecordsTest.java`.
  - Replaced in T035: `UserSummary` and `UserDetail` were removed; the tool maps `UserFacts` to the
    flat user of data-model.md, and `PersonalField` has its own file.
- [X] T030 [US2] `adapter/rest/v81/UserListXmlParser.java` (after T029): reads only `id` and `type`.
  `email`, `firstName`, `lastName` and unknown attributes are never read into any object. XML is
  hardened.
  - Failing tests first: `UserListXmlParserTest.java` (fixture with planted e-mails: parsed
    objects and `toString` contain none).
- [X] T031 [US2] StartCLI parsers (after T029) `adapter/cli/admin/UserGroupsParser.java` (`user <name>`),
  `FingerParser.java` (login, e-mail, roles, account state if shown), `WhoParser.java`.
  - `MessageListParser.java` and `MessageReadParser.java` wait for an L-17 recording (owner
    decision 2026-11-13: `msglist` was empty in T028, so its row layout and the form of `msgread`
    are not recorded).
  - Failing tests first: one test class each, with reconstructed cases (recorded after T028).
  - `WhoParser` reads the system table of `--who` (research "Live recording T028a"): only
    `Toolset` rows become a `LoginEntry`, `since` stays empty on 8.1; `--nwho` counts all rows.
- [X] T032 [US2] `adapter/cli/admin/CliUserRunner.java` (after T012, T031):
  - one session per page with `user <login>` + `finger <login>` for ≤ 25 logins;
  - logins: one client run per form, `startcli.sh … --who <url>` and `startcli.sh … --nwho <url>`
    (T028a), not in the session and not with `--execCommand`;
  - `msglist`, `msgread <id>` (id pattern `^[0-9]{1,10}$`): wait for an L-17 recording (owner
    decision 2026-11-13), not built in this task.
  - `SESSION_OPTIONS` admits exactly the session read forms above (`user`, `finger`; `msglist`,
    `msgread` only after L-17), without `-who`/`-nwho`; the coverage entries keep their keys
    `-who`/`-nwho`.
  - Failing tests first: `CliUserRunnerTest.java` (scripted session of 25 users; login names
    quoted; a block missing → that user's groups `unavailable`) and `CliSessionCommandTest`.
- [X] T033 [US2] Port and wiring:
  - `domain/port/UserDirectoryPort.java`;
  - `adapter/rest/v81/V81UserAdapter.java`, `RestEndpoint` + `GET /user/users`;
  - the full `Gateway`/`PortSet` wiring set as in T020.
  - adds the capability `USER_DIRECTORY` the way T011 added `SERVER_SETTINGS` (label, matrix "not yet
    verified", `NodeOverview`, `list_nodes` enum), with `CapabilityMatrixTest` and `ListNodesToolTest`;
  - From the T020 review: `V81PortSet.create` builds the full `PortSet` in one step (or through a
    small copy method on `PortSet`) instead of copying an 8-port set, before the user-directory
    port is added.
  - Failing tests first: `V81UserAdapterTest.java` and `WiringAuditTest`.
- [X] T034 [US2] `application/UserReadService.java`:
  - views `users`, `user`, `logins`; `messages`, `message` wait for an L-17 recording (owner
    decision 2026-11-13);
  - logins sorted, `offset` 0–9999, `limit` 1–25 (default 25), `nextOffset`;
  - an unknown login gives `NOT_FOUND` with the next step "list the users" (AS3);
  - personal fields appear only in `user` (FR-011);
  - `ActionSpec`s per view.
  - `loggedIn` lists the `Toolset` rows of `--who` (research "Live recording T028a"), without a
    login time on 8.1; the `--nwho` count of all systems is not reported as a user count.
  - `sources` per fact (FR-009): `logins` REST; `groups`, `roles`, `accountState`, `email`,
    `loggedIn` CLI (`messages` CLI after L-17); asserted per view;
  - Failing tests first: `application/UserReadServiceTest.java` (AS1–AS6; a 500-user list gives
    pages; AS5 no e-mail in a listing).
- [X] T035 [US2] Tool `mcp/tools/ReadUsersTool.java`:
  - schemas `read_users.{input,output}.json`;
  - read-only, idempotent;
  - the description names the `msgread` side effect (FR-008) once an L-17 recording shows it;
    until then the tool offers no message view;
  - announcement when `anyPossiblyCapable(USER_DIRECTORY)`.
  - From the T029 review: `UserDetail` nests its `UserSummary`; the output schema shows a user
    flat (`user.login`, not `user.summary.login`), mapped in the tool layer.
  - From the T029 notes: if a user's groups can be `unavailable` (missing session block), decide
    whether `UserSummary.group` becomes optional or the user is listed with its groups unavailable.
  - From the T034 review: the `user` view reports the source `personal` (CLI); add it to
    `read_users.output.json` and to the contract's `sources` list. Register `UserReadService.SPECS`
    in the Wiring `ActionCatalog` with the tool.
  - Failing tests first: `ReadUsersToolTest.java`, the output schema test,
    `ToolAnnouncementTest`.
- [X] T036 [US2] `security/ReadUsersSecretLeakTest.java`: planted e-mails never appear in `users`,
  and planted secrets in finger output are masked (message text after L-17); leak check.
  - From the T034 review: plant e-mails in a failing `finger` block and in a whole-session failure
    and check end to end that no listing shows them.
- [X] T037 [US2] Contracts and flips [BLOCKED: T028]: `UsersV81ContractTest.java` (and
  `UsersV90ContractTest.java` when 9.0 is recorded); rows, matrix and docs page.
  - From the T034 review: `logins` does not read `--nwho` (it counts every connected system), so
    the `CLI -nwho` row cannot become `reachable:read_users/logins`. Owner decision 2026-11-13:
    `covered:read_users` (the `logins` page `total` is the number of logged-in users); remove
    `connectedSystems()` from the port, the adapter and the runner.
- [X] T038 [US2] Docs and PR:
  - `docs/tools.md` section `read_users`;
  - `docs/research/inubit-interfaces.md` (from the T037 review: `-who`/`-nwho` are client options
    `--who`/`--nwho`; `--who` is read by `read_users` `logins`, `--nwho` is covered; also
    `docs/backlog.md`);
  - `CHANGELOG.md`;
  - `delivered-stories: US1, US2`, with `waits` set on every US2 row still `planned`.

  Then open the US2 PR.

- [X] T028b [LIVE] [OWNER] [US2] The T028/T028a recordings on the 9.0 test server (added
  2026-11-13 after the pin renewal, owner approval): `GET /user/users?type=processEngineUser`,
  a session with `user`/`finger` for three logins, `--who`, `--nwho`, `msglist` (and `msgread`
  for one message if the account has one, L-17), into `fixtures/v9_0/{cli,rest}/admin-users-*`
  with `.origin`.
- [X] T037a [US2] 9.0 contracts and flip (after T028b): `UsersV90ContractTest` (REST and CLI), the
  `v9_0` column of the US2 rows (`-nwho` `covered:read_users`), `USER_DIRECTORY` on 9.0, catalogue
  entries, `waits`.
- [X] T038a [US1] [US2] Docs and PR for 9.0 (after T026, T037a): `docs/tools.md`, `CHANGELOG.md`,
  coverage page; then open the PR.

## Phase 5: User Story 3 – Processes, run-time data and model details (P2)

**Goal**: `read_model_details` (module, libraries). `export_archives` gains `ownerWithoutGroups`
(technical workflows only) and `metadata` (the metadata registry). `find_processes` gains
`userColumn` from the REST `queueLog` rows (`ps -u` covered). `ps -f` and the counts are
confirmed `covered`. Process metadata and the IS Connector check stay `planned` (L-19, L-20);
`xslt10Report` is not offered (L-7). Research D-21 and "Decided after T039" (owner, 2026-11-14).
The US3 PR delivers 8.1; 9.0 stays "not yet verified" and follows in a separate PR (T039a,
T046a, T047a), as for US1 and US2.

**Independent test**: each action returns the shape recorded in T039; the new export forms write
files under the unchanged folder rules (US3 AS1–AS8; the planned parts of AS6 and AS8 are not
offered).

- [X] T039 [LIVE] [OWNER] [US3] L-7, L-8 and read recordings on 8.1:
  - `GET /model/nodeByModel/{d}/{id}`, `GET /model/nodeByName/{d}/{m}`;
  - `GET /process/metadata/{wf}`;
  - `GET /isconnector?…type=validateWorkflow`;
  - `library --list --type driver|plugin`;
  - `export --exportWorkflowGroupExclude`, `--exportMetadata`, `--exportMetadataNames`;
  - `fetchXSLT1.0 -ef` (where is the file written?);
  - `ps -u` (output form).

  Neutralize into `fixtures/v8_1/{rest,cli}/admin-model-*` with planted secrets in module
  properties and metadata.
- [ ] T040 [P] [US3] Parsers (research D-21, "Live recording T039"):
  - `adapter/rest/v81/ModuleDetailXmlParser.java`: `ns4:Node` (`name`, `type`, `id`,
    `parentId`), `ns4:Connection` (`id`, `targetId`), `ns2:Properties/ns2:Property name= type=`
    with the value as text, hardened through `XmlSupport`. A property is masked through
    `SecretRules` by its name, by `type="Password"` (also when empty) and by key-material values
    (D-19); `maskedCount`; values ≤ `ItemBounds.MAX_ITEM_CHARS`. The `500` `text/html` page
    with `@Start@The module with the id '<id>' … could not be found!@…@End@` (HTML-escaped
    quotes) is `NOT_FOUND`; any other body `UNEXPECTED_RESPONSE` with a masked excerpt.
  - `adapter/cli/admin/LibraryListParser.java`: the answer after the preamble; blank and
    `\r`-only lines are skipped, every other line is one file name; a line over 255 characters
    or with a control character is `UNEXPECTED_RESPONSE`. The parsed list carries
    `rowFormatVerified = false` (L-21).
  - Domain records `ModuleDetail`, `LibraryEntry` (data-model). No `ProcessMetadata` or
    `IsConnectorCheck` (parked until L-19, L-20).
  - Failing tests first: `ModuleDetailXmlParserTest.java` (the recorded Database Connector and
    IS Connector modules, the planted clear-text password and the secret found only by its
    name, the not-found page, an Accept-JSON request answered with XML), `LibraryListParserTest`
    (the recorded empty answers in script and session form; reconstructed non-empty lines),
    each with a leak check.
- [ ] T040a [P] [US3] The user column in the process rows (research D-21; files apart from T040
  and T041):
  - `domain/model/ProcessQuery.java` gains the optional `userColumn` (1–5),
    `domain/model/ProcessInstance.java` the optional `userColumnValue`;
  - `adapter/rest/v81/QueueLogRowParser.java`: with a requested user column `i`, also reads
    `userDefined<i>` of the row (blank → absent) into `ProcessInstance`, as one more optional
    text under the existing bounds (200 characters with the truncation marker, then the row size
    bound) and the existing masking; without a requested column the row is parsed as today.
    `QueueEntryRowParser` (`read_queue_entry`, `userDefined1..5`) is the model and stays
    unchanged.
  - Failing tests first: `QueueLogRowParserTest` (`userDefined1` of
    `fixtures/v8_1/rest/log_queueLog.json`, a blank value absent, a long value cut, the row bound
    kept; without a column the rows unchanged), `ProcessQuery` validation (0 and 6 refused), with
    a leak check.
- [ ] T041 [US3] `CliCommand` read forms (only the forms recorded in T039):
  - `library --list --type driver|plugin`;
  - `export --exportWorkflowUser <owner> --exportWorkflowType 'technical' --exportWorkflowGroup ''
    --exportWorkflowGroupExclude <g1,…> --exportFile <zip>` (each group name per `VALUE`, 1–20,
    joined with commas into one argument);
  - `export --exportMetadata --exportFile <zip>`, optionally with `--exportMetadataNames
    <n1,…>` (1–50 names per `VALUE`, joined with commas);
  - no `fetchXSLT1.0` form (not found on 8.1.17, L-7).
  - Failing tests first in `adapter/cli/CliCommandTest.java`: the allowed forms;
    `--exportWorkflowGroupExclude` without `--exportWorkflowType` and `--exportWorkflowGroup`
    rejected; `ps` (no `ps` form: `userColumn` comes from REST), `fetchXSLT1.0`, `library
    --delete` and `library --deleteAll` still rejected.
- [ ] T042 [US3] Port and wiring:
  - `domain/port/ModelDetailPort.java` (`module`, `libraries`);
  - `adapter/rest/v81/V81ModelDetailAdapter.java` (REST, `Accept: application/xml`) plus
    `adapter/cli/admin/CliLibraryRunner.java` (list: one script-mode run per type, or one stdin
    session for both types);
  - `RestEndpoint` + `GET /model/nodeByModel/{d}/{id}` only (no `nodeByName`,
    `/process/metadata`, `/isconnector`);
  - the `Gateway`/`PortSet` set.
  - adds the capability `MODEL_DETAILS` the way T011 added `SERVER_SETTINGS` (label, matrix "not yet
    verified", `NodeOverview`, `list_nodes` enum), with `CapabilityMatrixTest` and `ListNodesToolTest`;
  - Failing tests first: adapter tests with WireMock (the T039 bodies and media types) and
    scripted StartCLI (the T039 outputs).
- [ ] T043 [US3] `application/ModelDetailService.java` and tool `mcp/tools/ReadModelDetailsTool.java`:
  - schemas `read_model_details.{input,output}.json`;
  - actions `module` (`diagram` + `moduleId`) and `libraries` (`libraryType` optional, paged);
    no `moduleName`, `metadata` or `checkIsConnector` (owner decision 2026-11-14); each call is
    admitted with `VersionGate.admitCall` and its own entries (`module` on 9.0 refused "not yet
    verified" until T046a);
  - `libraries` returns `rowFormatVerified: false`, and the description says that each line is
    read as one file name until a listed library is recorded (L-21);
  - read-only, idempotent;
  - announcement on `MODEL_DETAILS`.
  - Failing tests first: `ModelDetailServiceTest.java` (AS2, AS3; an unknown module id gives
    `NOT_FOUND`), `ReadModelDetailsToolTest.java` (the action enum has exactly `module` and
    `libraries`), `ToolAnnouncementTest`.
  - Also `security/ModelDetailsSecretLeakTest.java`: planted secrets in module properties (by
    name and by `type="Password"`) never appear in results, errors, logs or audit. The new
    `export_archives` forms are added to it in T044, the user column in T045b.
- [ ] T044 [US3] `export_archives` `content` forms (D-21):
  - `src/main/resources/schemas/export_archives.input.json`:
    - `content` enum `workflows` | `ownerWithoutGroups` | `metadata`, default `workflows`;
    - `if/then`: `diagramGroup` required only for `workflows`;
    - `excludeGroups[1..20]` required for `ownerWithoutGroups`, allowed only there;
    - `metadataNames[1..50]` optional for `metadata`, allowed only there;
    - `acknowledgeClearTextSecrets` required for `workflows` and `ownerWithoutGroups`.
  - The export archives service (`application/ArchiveExportService.java`):
    - the two forms through `adapter/cli/CliExportRunner.java` (new methods
      `exportOwnerWithoutGroups` with `--exportWorkflowType 'technical'` and
      `--exportWorkflowGroup ''`, and `exportMetadata` with optional names);
    - the same `TargetFolderRules`, `ArchiveFolderWriter`, `archives.productionOptIn` and audit;
    - the description names the check-in comment trace (FR-008), that `ownerWithoutGroups`
      exports the owner's technical workflows only (owner decision 2026-11-14), and that
      `metadata` exports registry entries and accepts names that do not exist;
    - the output carries `source: "CLI"` (FR-009).
  - In the coverage list, the existing `export_archives` rows change from `default` to
    `workflows`, and the new rows are named `ownerWithoutGroups` and `metadata` (research D-2,
    action key); `fetchXSLT1.0` and `fetchXSLT1.0 --exportFilename` become
    `excluded:command not found on INUBIT 8.1.17` on 8.1 (L-7 fallback); their 9.0 states stay
    `planned` until T046a.
  - Failing tests first:
    - `ExportArchivesContentTest.java` (each form with the T039 answers `1-OK: Workflow group
      exported successfully.`, `1-OK: Metadata exported successfully.`, `1-OK: Metadata Names
      exported successfully.`, and `2-NOK: Missing export option.`; folder rules unchanged;
      production opt-in);
    - `ExportArchivesToolTest` description;
    - `ArchiveToolReferenceTest`;
    - every existing `export_archives` test unchanged (SC-009);
    - `ModelDetailsSecretLeakTest` extended with the two forms (errors, logs, audit).
- [ ] T045 [P] [US3] The `userColumn` gate (research D-21; owner decisions 2026-11-14):
  - `domain/model/InterfaceCoverage.servedBy(CoverageKey, MajorMinor, String tool)`: true when
    the row is `Covered(tool)` or `Reachable(tool, …)` for that line;
  - `application/VersionGate.admitOption(String tool, NodeId, CoverageKey)`: admits `userColumn`
    when the row `CLI ps -u` is `Covered("find_processes")` or `Reachable` for the node's line. A
    missing line (6.1) or a `planned` row gives `UNSUPPORTED_VERSION` "not yet verified";
    `excluded` gives its reason. `admitCall`/`refusal` stay as they are (they treat `Covered` as
    "not sent by this server"). No version check in `DiagnosisService` (Principle V); T046a's row
    flip is enough to admit 9.0.
  - Failing tests first: `InterfaceCoverageTest` (`servedBy`: covered by the named tool,
    reachable, covered by another tool, planned, missing line); `VersionGateActionTest`
    (`admitOption` with a test coverage file: 8.1 admitted, 9.0 `planned` refused "not yet
    verified", 6.1 refused, and the 9.0 row flipped to `covered:find_processes` admits 9.0).
- [ ] T045a [US3] `waits` tokens (FR-004): `domain/model/CoverageEntry.WAITS_ITEM` admits
  `L-1` … `L-21`, `9.0-pin` and the new `9.0-followup` (`contracts/coverage-list.md`).
  - Failing test first in `InterfaceCoverageTest`: `L-21` and `9.0-followup` accepted, `L-22`
    and an unknown token refused.
- [ ] T045b [US3] `ps -u` through `find_processes` `userColumn` (after T040a and T045; owner
  decisions 2026-11-14: the values come from the REST `queueLog` field `userDefined<i>`, no
  StartCLI run) and the coverage decisions for D:
  - `src/main/resources/schemas/find_processes.{input,output}.json`: `userColumn` (integer 1–5);
    output `userColumn: {index}` and `userColumnValue` per process;
  - `application/DiagnosisService.java` and `adapter/rest/v81/V81ProcessQueryAdapter.java`: per
    server, `admitOption` (T045) first; an admitted server's requested column goes with the
    `ProcessQuery` to the `queueLog` read the search already makes (T040a): no extra call, no
    StartCLI run, no join. A server that is not admitted gets `userColumn` reported unavailable
    in the warnings with that error (a call for that server alone is refused);
  - `FindProcessesTool` description names the column and its REST source; every other input and
    the annotation unchanged (SC-009);
  - `ps -f` (`covered:find_processes`) and `-ne/-nx/-nw/-nq` (`covered:get_health`) are already
    set by T007; add assertions for them to `InterfaceCoverageResourceTest`;
  - set `ps -u` to `covered:find_processes` on 8.1 (the same data comes through REST; the T039
    `ps -u` fixtures are the recording behind this state); 9.0 stays `planned` until T046a.
  - Failing tests first: the `ps -u` assertion in `InterfaceCoverageResourceTest` (red while the
    row is `planned`); `FindProcessesToolTest` / `DiagnosisServiceTest` (`userColumn` shown from
    the recorded `queueLog` rows; a stage with an 8.1 and a 9.0 server (and one with a 6.1
    server): the 8.1 processes carry the column, the other server's `userColumn` is in the
    warnings with `UNSUPPORTED_VERSION`, and its processes are still listed; 9.0 or 6.1 alone
    refused; without `userColumn` unchanged); `ModelDetailsSecretLeakTest` with planted values in
    `userDefined<i>`.
- [ ] T046 [US3] Contracts and flips: `ModelDetailsV81ContractTest.java`,
  `ExportContentV81ContractTest.java`, only from the T039 fixtures (`admin-model-node-by-model*`,
  `admin-model-library-*`, `admin-model-session`, `admin-model-export-*`), and a `userColumn`
  case on the recorded `fixtures/v8_1/rest/log_queueLog.json` (beside `QueueEntryRowsContractTest`);
  rows, matrix, docs page (8.1 only; 9.0 in the follow-up):
  - 8.1 `reachable:read_model_details/module` (`GET /model/nodeByModel/{model}/{node}`),
    `reachable:read_model_details/libraries` (`library --list`, `library --type`),
    `reachable:export_archives/ownerWithoutGroups` (`export --exportWorkflowGroupExclude`),
    `reachable:export_archives/metadata` (`export --exportMetadata`,
    `export --exportMetadataNames`);
  - 8.1 `GET /model/nodeByName/{diagram}/{module}`: `excluded:absent on INUBIT 8.1.17 (404)`; 9.0
    stays `undocumented`;
  - `GET /process/metadata/{name}` and `GET /isconnector` stay `planned` (story US3).
- [ ] T047 [US3] Docs and PR (after T045a, T045b and T046; not after T039a or T046a):
  - `docs/tools.md` sections `read_model_details`, `export_archives` (new forms) and
    `find_processes` (`userColumn`);
  - `docs/research/inubit-interfaces.md` (`nodeByName` and `fetchXSLT1.0` absent on 8.1.17, the
    metadata registry export, `/isconnector` refusing the REST login, `ps -u` covered through the
    `queueLog` field `userDefined<i>`);
  - `CHANGELOG.md`;
  - `delivered-stories` + US3, with `waits` set on every US3 row still `planned`:
    `GET /process/metadata/{name}` `L-19`, `GET /isconnector` `L-20`, and `9.0-followup` on
    every 9.0 state still `planned` (joined with `+`, e.g. `L-19+9.0-followup`).
  - L-19 and L-20: a working form is built in T046b/T046c; if they are not settled before
    Phase 10, their fallbacks apply in T104 (rows `excluded:` with the FR-002 reasons named
    there), so that no row stays `planned` (SC-001).

  Then open the US3 PR.

### US3 9.0 follow-up (separate PR after the US3 PR, as for US1 and US2)

- [ ] T039a [LIVE] [OWNER] [US3] The T039 reads on the 9.0 test server, after the US3 PR (owner
  approval; the pin is current since T028b), into `fixtures/v9_0/{rest,cli}/admin-model-*` with
  `.origin` and `V90Cases` entries:
  - `GET /model/nodeByModel/{d}/{id}` (a module with a password property, and an unknown id);
  - `library --list --type driver|plugin`;
  - no new `queueLog` recording for L-8 on 9.0: the recorded 9.0 row with `userDefined1` set
    (`fixtures/queue-entry/v9_0/error_entry.rows.json`, with `.origin`) serves the `userColumn`
    case;
  - `export --exportMetadata` (with and without `--exportMetadataNames`) and
    `export --exportWorkflowUser … --exportWorkflowType 'technical' --exportWorkflowGroup ''
    --exportWorkflowGroupExclude …` (the archive stays private: clear-text secrets);
  - `fetchXSLT1.0 -h` (L-7 on 9.0: does the command exist?);
  - if a workflow with metadata or another `/isconnector` login is at hand: L-19, L-20.

  Research section "Live recording T039a". If `fetchXSLT1.0` exists on 9.0, the owner decides
  whether `xslt10Report` comes back for 9.0 (T046d). The 9.0 rows of `fetchXSLT1.0` and
  `fetchXSLT1.0 --exportFilename` otherwise get, in T046a:
  - command absent: `excluded:command not found on INUBIT <recorded 9.0 version>`;
  - command present but writing on the INUBIT host: `excluded:file written on the INUBIT host`;
  - command usable but the owner declines: `excluded:` with the owner's reason (for example
    `not offered by owner decision <date>`).
- [ ] T046a [US3] 9.0 contracts and flips (after T039a): `ModelDetailsV90ContractTest`,
  `ExportContentV90ContractTest` from the 9.0 fixtures and the `userColumn` case on
  `fixtures/queue-entry/v9_0/error_entry.rows.json`; the `v9_0` column of the US3 rows (`ps -u`
  `covered:find_processes`, which admits `userColumn` on 9.0 through T045's `admitOption`
  without a code change; `fetchXSLT1.0` per T039a and T046d), `MODEL_DETAILS` on 9.0,
  catalogue entries, and `9.0-followup` removed from every US3 `waits` value.
- [ ] T046b [LIVE] [OWNER] [US3] `metadata` (`GET /process/metadata/{name}`), only if L-19
  records a working form (owner approval of the recording as usual); otherwise the fallback in
  T104 applies:
  - neutralized fixtures `fixtures/{v8_1,v9_0}/rest/admin-model-metadata*` with planted secrets;
  - `adapter/rest/v81/ProcessMetadataParser.java` and the record `ProcessMetadata` per the
    recorded shape (masked by D-19); `RestEndpoint` + `GET /process/metadata/{name}`;
    `ModelDetailPort.metadata`; the action `metadata` (`workflow`) in
    `read_model_details.{input,output}.json` and `ModelDetailService`;
  - the row flipped to `reachable:read_model_details/metadata` with its `waits` cleared.
  - Failing tests first: `ProcessMetadataParserTest`, `ModelDetailServiceTest`,
    `ReadModelDetailsToolTest` (enum), `ModelDetailsSecretLeakTest` (planted metadata values),
    the contract test of the version recorded.
- [ ] T046c [LIVE] [OWNER] [US3] `checkIsConnector` (`GET /isconnector?…type=validateWorkflow`),
  only if L-20 records a working authentication and answer (owner approval of the recording as
  usual); otherwise the fallback in T104 applies:
  - neutralized fixtures `fixtures/{v8_1,v9_0}/rest/admin-model-isconnector*`;
  - the credential form L-20 found (configuration, if any, per `contracts/configuration-delta.md`),
    `adapter/rest/v81/IsConnectorCheckParser.java` and the record `IsConnectorCheck` (message
    masked, ≤ 2,000 characters); `RestEndpoint` + `GET /isconnector`;
    `ModelDetailPort.checkIsConnector`; the action `checkIsConnector` (`workflow`, `module`) in
    the schemas and `ModelDetailService`;
  - the row flipped to `reachable:read_model_details/checkIsConnector` with its `waits` cleared;
    T079 (L-14) uses the same authentication.
  - Failing tests first: `IsConnectorCheckParserTest`, adapter test (the authentication sent),
    `ModelDetailServiceTest`, `ReadModelDetailsToolTest` (enum), `ModelDetailsSecretLeakTest`
    (planted message and credential), the contract test.
- [ ] T046d [OWNER] [US3] `xslt10Report` on 9.0, only if T039a (L-7 on 9.0) shows that
  `fetchXSLT1.0` exists and writes its file where the MCP server can read it, and the owner
  decides to offer it; otherwise its 9.0 rows are excluded in T046a with the reasons named in
  T039a:
  - neutralized fixtures `fixtures/v9_0/cli/admin-model-fetchxslt*` with `.origin`;
  - `CliCommand` `fetchXSLT1.0 -ef <file>` (the file name generated by the server);
    `CliExportRunner.fetchXslt10Report`; `content: xslt10Report` in
    `export_archives.input.json` (no further field), offered on 9.0 only through its row;
  - the 9.0 rows flipped to `reachable:export_archives/xslt10Report`.
  - Failing tests first: `CliCommandTest`, `ExportArchivesContentTest` (the report file, folder
    rules, production opt-in), `ModelDetailsSecretLeakTest`, the 9.0 contract test.
- [ ] T047a [US3] Docs and PR for 9.0 (after T046a and, where it applies, T046d):
  - `docs/tools.md`, `CHANGELOG.md`, coverage page;
  - `docs/research/inubit-interfaces.md`: the 9.0 facts of T039a (module detail form, library
    list, the metadata registry export, `fetchXSLT1.0` on 9.0 and its row state);
  - T046b / T046c are part of this PR only if their recording exists when T046a is done.
    Otherwise each ships later in its own PR with its own `docs/tools.md` and `CHANGELOG.md`
    step, or its row falls back in T104.

  Then open the PR.

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
  - `admit(tool, nodeId, ActionSpec, input)` in exactly this order:
    1. group id → `INVALID_INPUT`, unknown → `TARGET_UNKNOWN`;
    2. production without opt-in, or production with `CLIENT` → `PRODUCTION_PROTECTED`;
    3. write off → `WRITE_DISABLED`;
    4. area opt-in missing → `NOT_CONFIGURED` with next step `administration.<area>` (skipped for
       `OPERATION`);
    5. `VersionGate.admitCall` with the entries of this call (`ActionSpec.entries(input)`) →
       `UNSUPPORTED_VERSION` naming the variant;
    6. availability → `CLI_UNAVAILABLE` / `AUTH_FAILED`.
  - The area check reads `EffectiveNodeConfig.administration()`, which does not exist yet: add a
    stub `enabled(Area)` that is true only for `OPERATION` and `READ` until T067.
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
  - `WriteChallengeRegistry` gets a new overload `issue(String tool, NodeId, …)` / `redeem(String
    tool, NodeId, …)` next to `issue(Operation, NodeId, …)` and the group form. No `Operation`
    values are added (research D-13).
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
  - adds the capability `SERVER_OPERATION` the way T011 added `SERVER_SETTINGS` (label, matrix "not yet
    verified", `NodeOverview`, `list_nodes` enum), with `CapabilityMatrixTest` and `ListNodesToolTest`;
  - Failing tests first: adapter tests, `WiringAuditTest`.
- [ ] T059 [US4] `application/ServerOperationService.java`, switch actions:
  - `setMaintenance`, `setReadiness`, `setTrace`, `setTraceLevel`, `setDebug`,
    `triggerMidnightTask`, per `contracts/mcp-tools-delta.md`;
  - the before state read first (`MAINTENANCE_MODE` from REST counts as "on");
  - `effects` sentences per action, with the fixed texts from the contract;
  - the after state re-read (FR-024);
  - lock key `node|OPERATION|<action>`;
  - `ChangeGuard`, `ChangeLocks`, `ChangeChallenges`, `ChangeAudit`, `WriteLock.handOff`.
  - Failing tests first: `application/ServerOperationServiceTest.java`:
    - AS1–AS3 and AS5–AS11 one test each;
    - `CLIENT` mode on non-production executes on the first call;
    - maintenance off while REST answers 503;
    - a second call on the same key → `PRECONDITION_FAILED`.
- [ ] T060 [US4] `application/ServerOperationService.java`, `deleteErrorProcesses` (after T059):
  - the preview lists the count, ≤ 50 ids and the workflows concerned (AS4);
  - an empty set gives `NOTHING_TO_DO` (audited);
  - the set is re-read right before sending, and a different set gives `CONFLICT` with a new
    preview (FR-017);
  - the result names the count INUBIT reports;
  - lock key `node|OPERATION|deleteErrorProcesses`.
  - Failing tests first: `application/ServerOperationDeleteErrorsTest.java` (AS4, empty set,
    changed set, more than 10,000 ids → count only).
- [ ] T061 [US4] Tool `mcp/tools/OperateServerTool.java`:
  - schemas `operate_server.{input,output}.json`;
  - `ToolHints.destructive(title)`;
  - announced when `anyWriteEnabled`;
  - the description per the contract (midnight task: its effect not documented by the vendor).
  - Failing tests first: `OperateServerToolTest.java`, `ToolHintsTest`, `ToolAnnouncementTest`.

### manage_tags

- [ ] T062 [US4] `CliCommand` tag forms (D-18):
  - `tag` admits `--tagSetActive`, `--tagRemoveActive`, `--tagDelete`, `--tagRemove`,
    `--tagRepositoryPath`;
  - builder methods `tagSetActive(tag, user)`, `tagRemoveActive(user)`, and
    `tagRemoval(kind, tag, Scope)` where `Scope` = group + type | repository path, required;
  - `--tagDiagram` and `--tagWorkflowGroupExclude` are never admitted;
  - `--tagMove` (existing) unchanged.
  - Failing tests first in `CliCommandTest.java`: removal without scope rejected; both forbidden
    options rejected; existing `tag_artifacts` forms unchanged.
- [ ] T063 [US4] `adapter/cli/admin/CliTagAdminRunner.java` and `domain/port/TagAdministrationPort.java`:
  - the active tag of a user is read (from `user <name>` or the form found in T055);
  - the objects and versions carrying a tag in a scope come from the version history read the way
    `tag_artifacts` does (`VersionHistoryParser`), together with the count outside the scope;
  - the `Gateway`/`PortSet` set.
  - adds the capability `TAG_ADMINISTRATION` the way T011 added `SERVER_SETTINGS` (label, matrix "not yet
    verified", `NodeOverview`, `list_nodes` enum), with `CapabilityMatrixTest` and `ListNodesToolTest`;
  - Failing tests first: runner and port tests with scripted runs.
- [ ] T064 [US4] `application/TagAdministrationService.java` and tool `mcp/tools/ManageTagsTool.java`:
  - schemas `manage_tags.{input,output}.json`;
  - a missing scope gives `INVALID_INPUT` before any read (AS13);
  - before a removal the backup is the tag list (T053), and nothing is sent if it fails;
  - the result re-reads inside and outside the scope (AS14);
  - `removeTag`/`deleteTag` are offered only when their rows are `reachable`; the description
    says so;
  - destructive; announced when `anyWriteEnabled`.
  - lock key `node|OPERATION|tag:<tag>@<scope>` or `node|OPERATION|activeTag:<user>`; a second
    call on the same key gives `PRECONDITION_FAILED` (test);
  - Failing tests first: `TagAdministrationServiceTest.java` (AS12–AS14, backup failure → nothing
    sent), `ManageTagsToolTest.java`.
- [ ] T065 [US4] Cross-cutting safety tests for the two tools:
  - `application/WriteRaceToolsTest.java` +2 (version change → refused, nothing sent);
  - new `application/ChangeToolsProductionRefusalTest.java`: a production refusal test for every
    action of every change tool, parameterized over `ActionCatalog` (SC-004); later stories add
    their tools to it;
  - a "write off" refusal test for every action, in the same class (SC-003);
  - `security/OperationSecretLeakTest.java`.
- [ ] T066 [US4] Contracts, flips, docs and PR [BLOCKED: T054, T055]:
  - `OperateV81ContractTest.java`, `TagAdminV81ContractTest.java`;
  - rows flipped, or `--tagRemove`/`--tagDelete` set to `excluded:acts on the whole owner` per
    T055;
  - matrix, docs page;
  - `docs/tools.md` sections `operate_server`, `manage_tags`;
  - `docs/setup.md`: write enablement, `CLIENT` mode;
  - `docs/research/inubit-interfaces.md`;
  - `CHANGELOG.md`;
  - `delivered-stories` + US4, with `waits` set on every US4 row still `planned`.

  Then open the US4 PR.

## Phase 7: User Story 5 – Transfer and repair run-time data (P3)

**Goal**: `transfer_runtime_data` exports (a change), validates, imports, lists, resumes and
deletes blocked processes; the archives stay on the INUBIT hosts (US5, FR-016a).

**Independent test**: with a fake StartCLI, the export needs write enablement, the opt-in and the
code, and names the operation id and host; the reads need only the opt-in (US5 AS1–AS7).

- [ ] T067 [US5] `administration` configuration (D-9, `contracts/configuration-delta.md`):
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
- [ ] T068 [P] [US5] `domain/model/ExportCondition.java` and `ExportFilter.java` (D-5):
  - `column` must match `^[A-Za-z][A-Za-z0-9_]{0,63}$`;
  - a value must match `^[^'(),\u0000-\u001F\u007F]{1,200}$`;
  - an `in` list holds 1–50 values; there are 1–10 conditions;
  - `render()` gives `c=v AND c in (a, b)`.
  - Failing tests first: `ExportFilterTest.java` (each rejected character, limits, rendering).
- [ ] T069 [LIVE-W] [OWNER] [US5] L-9 and L-10 on the 8.1 test server, with approval and test processes
  only:
  - `process export test-<date>.zip --filterExpression '<testcol>=<value>'`, with the answer
    recorded;
  - `process listBlocked <op>`;
  - `process validateExport` with the bare name and with the reported absolute path;
  - `process import` on a second test server, or the same one if only one exists;
  - `process resume <op>`, `process deleteBlocked <op>`;
  - whether the match count can be read before the export (L-9).

  Neutralize into `fixtures/v8_1/cli/admin-runtime-*`.
- [ ] T070 [US5] `CliCommand` `process` forms:
  - `export <fileName> [--excludeModel] [--filterExpression '<rendered>']`, with the file name
    pattern `^[A-Za-z0-9][A-Za-z0-9._-]{0,99}\.zip$`;
  - `import <path>`, `validateExport <path>`, where `<path>` is the reported absolute path (from
    an earlier export answer of that server) or the bare name per T069;
  - `listBlocked|resume|deleteBlocked <uuid>`.
  - `--filterProcessIds` and `inFile` are never admitted.
  - Failing tests first in `CliCommandTest.java`.
- [ ] T071 [US5] `adapter/cli/admin/CliRuntimeTransferRunner.java` (bounded by `cliExportTimeout`),
  with parsers:
  - `RuntimeExportAnswerParser` (`The export file is located at: …`, `Operation ID is: …`, the
    host);
  - `BlockedListParser` (paged);
  - `ValidateExportParser` (accepted, problems ≤ 50 × 500 characters);
  - `RuntimeImportAnswerParser`.

  Also `domain/port/RuntimeTransferPort.java` and the `Gateway`/`PortSet` set. A per-server record
  of exported paths (`application/RuntimeExportPaths`, in memory) maps names to reported paths.
  - adds the capability `RUNTIME_TRANSFER` the way T011 added `SERVER_SETTINGS` (label, matrix "not yet
    verified", `NodeOverview`, `list_nodes` enum), with `CapabilityMatrixTest` and `ListNodesToolTest`;
  - Failing tests first: parser and runner tests; leak check.
- [ ] T072 [US5] `application/RuntimeReadGuard.java` and `application/RuntimeTransferService.java`:
  - `validate` and `listBlocked` need the `runtimeData` opt-in only, and are audited;
  - `export`, `import`, `resume` and `deleteBlocked` go through `ChangeGuard` with
    `Area.RUNTIME_DATA` and always use a server code;
  - the export preview carries the filter, the count if T069 found a way to read it, and the
    blocking sentence;
  - `resume`/`deleteBlocked` previews list the blocked ids, re-read before sending (FR-017).
  - lock key `node|RUNTIME_DATA|<operationId>` (export: `node|RUNTIME_DATA|export:<fileName>`);
    a second call on the same key gives `PRECONDITION_FAILED` (test);
  - Failing tests first: `RuntimeTransferServiceTest.java`:
    - AS1–AS7;
    - production without opt-in;
    - reads without write enablement allowed but without the opt-in refused;
    - a changed blocked set → `CONFLICT`.
- [ ] T073 [US5] Tool `mcp/tools/TransferRuntimeDataTool.java`:
  - schemas `transfer_runtime_data.{input,output}.json`;
  - destructive;
  - announced when some group has `administration.runtimeData`;
  - the description says the archive stays on the INUBIT hosts and the operator moves it, and
    gives the order of steps.
  - Failing tests first: `TransferRuntimeDataToolTest.java`, `ToolAnnouncementTest`,
    `WriteRaceToolsTest` +1, the tool's actions added to `ChangeToolsProductionRefusalTest`,
    `security/RuntimeTransferSecretLeakTest.java`.
- [ ] T074 [US5] Contracts, flips, docs and PR [BLOCKED: T069]:
  - `RuntimeTransferV81ContractTest.java`;
  - rows, matrix, docs page;
  - `docs/tools.md` section;
  - `docs/setup.md` (`administration.runtimeData`);
  - `docs/research/inubit-interfaces.md`;
  - `CHANGELOG.md`;
  - `delivered-stories` + US5, with `waits` set on every US5 row still `planned`.

  Then open the US5 PR.

## Phase 8: User Story 6 – Administer objects, libraries and settings (P3)

**Goal**: `administer_objects` (objects and libraries areas) and `change_server_settings`
(settings area), with uploads from configured folders, named secret entries and backups before
every delete, rename or replace (US6).

**Independent test**: with fakes, each change is refused without its area, previews what it
changes, takes a backup where INUBIT can export the object, and sends nothing when the backup
fails (US6 AS1–AS17).

### Shared inputs from outside the conversation

- [ ] T075 [P] [US6] `config/UploadsConfig.java` (D-9) on `ProfileConfig`:
  - `folders` ("empty → uploads off; absolute, existing, no symbolic link"), checked at start and
    not inside `~/.inubit-mcp`;
  - `maxBytes` per type with the defaults license 1 MiB, keystore 1 MiB, userImport 10 MiB,
    ediRules 50 MiB, metadataImport 50 MiB, isConnectorInput 50 MiB, library 200 MiB; each value
    1 KiB … 4 GiB; written as `<n>KiB|MiB|GiB` or bytes;
  - `ConfigSummary` line.
  - Failing tests first: `UploadsConfigTest.java`, `ConfigValidatorTest`, `ConfigSummaryTest`.
- [ ] T076 [US6] `application/UploadFiles.java` and `UploadFile` (D-11):
  - `open(Path, UploadType)` checks, in order:
    1. the path is absolute;
    2. with `NOFOLLOW_LINKS`, a regular file and no symbolic link in any path element;
    3. its real path is inside a configured folder;
    4. its size is ≤ the type's limit;
  - while checking it computes SHA-256 from the same open channel, then rewinds for sending (REST
    uploads stream from this channel);
  - `privateCopy()` for StartCLI uploads: copies the checked bytes from the open channel into an
    owner-only directory (`OwnerOnlyFolders`), file `rw-------`, verifies the copy's SHA-256
    against the check, returns `PrivateUploadCopy(path, sha256)`, and deletes it when the call
    ends and on shutdown;
  - folders empty → `NOT_CONFIGURED` naming `uploads.folders`; every other failure →
    `INVALID_INPUT` naming the rule.
  - Failing tests first: `UploadFilesTest.java`:
    - link, linked parent, directory, outside the folder, too large, empty folders;
    - a file swapped after `open` still sends the checked content (POSIX; skipped on Windows
      with a reason);
    - a file swapped after `open` and before `privateCopy()` gives a copy with the checked
      content, or a refusal on a hash mismatch, never the swapped content;
    - the copy is deleted after success, after failure and after an exception.
- [ ] T077 [US6] Named secret entries (D-10):
  - `domain/model/SecretEntryName.java`: `^[A-Z][A-Z0-9_]{0,47}$`. A name equal to a login kind
    (`USERNAME`, `PASSWORD`, `TRUSTSTORE_PASSWORD`, `E2E_USERNAME`, `E2E_PASSWORD`), or ending in
    `_` plus one, is rejected.
  - `config/CredentialNames.java` adds `<PREFIX>_<GROUP>_<NODE>_ENTRY_<NAME>`, then
    `<PREFIX>_<GROUP>_ENTRY_<NAME>`, and `allLoginCandidates(ProfileConfig)`, the login names of
    every group and node of the profile.
  - `adapter/NodeSecrets.java` `entry(SecretEntryName, Duration bound)`, credential store only:
    - a resolved name contained in `allLoginCandidates` is refused with `INVALID_INPUT`;
    - on a node with an AWS Parameter Store source, `NOT_CONFIGURED` naming the credential store
      as the requirement (research D-10; no `ParameterNames` change);
    - the value is registered with `SecretScrubber` first.
  - Missing or unreadable → `NOT_CONFIGURED` naming the entry and the source, never a value.
  - Failing tests first:
    - `SecretEntryNameTest.java` (reserved names and suffixes, e.g. `NODE1_PASSWORD`);
    - `NodeSecretsEntryTest.java`:
      - group `test` + node `entry-node1`, and group `test-entry` + node `node1`: no entry name
        reaches either login entry;
      - AWS node refused;
      - missing entry;
      - leak check.
- [ ] T078 [US6] `InubitHttpClient` PUT, DELETE and multipart (D-8):
  - from the T005 review: `get`, `post`, `put` and `delete` assert that `endpoint.method()` matches
    the verb (an `IllegalArgumentException` otherwise), and the client tests that send GET to
    `RestEndpoint.LOG` use a GET endpoint (e.g. `MODEL_EXPORT`) instead; rewrap the migrated calls
    in `InubitHttpClientAcceptTest` evenly;
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

- [ ] T079 [LIVE-W] [OWNER] [US6] L-11 (write), L-14, L-16, L-21 and the recordings of the US6
  changes on the 8.1 test server, each approved, against throw-away fictitious objects:
  - `DELETE /diagrams/{n}` and `…/version/{v}`, `DELETE /modules?moduleName=` and
    `…/{id}/version/{v}`, `DELETE /repository`;
  - `rename --renameWorkflow|--renameModule|--renameMetadata|--renameFile|--renameDirectory`
    (are references updated?);
  - `import --importMetadata`;
  - `PUT /libraries`, then `library --list --type driver|plugin` with the uploaded test driver
    and plug-in present (L-21: the row format), then `DELETE /libraries/{n}`,
    `library --deleteAll --type plugin` (only a test plug-in present);
  - `plugin --register|--deregister` (usage text first);
  - `PUT /edirules`;
  - `PUT /keys` (test keystore, test module);
  - `threads setNumberOfWorkflowThreads` and `privilegedWorkflows setThreadsPercentage` (set back
    afterwards);
  - `dbconnectiontolerance set` (usage, ranges);
  - `PUT /configurations` (one harmless value, set back; partial document accepted?);
  - `PUT /license` only if a test license file exists;
  - `POST /isconnector` (L-14), with the authentication L-20 found (T039: the profile's REST
    login gets `401` on `/isconnector`);
  - `--backupFile`/`--backupRepository` usage text (L-16).

  Neutralize into `fixtures/v8_1/{cli,rest}/admin-objects-*`, `admin-libraries-*`,
  `admin-settings-change-*`.

### administer_objects

- [ ] T080 [US6] `CliCommand` object and library forms:
  - `rename --renameWorkflow|--renameModule|--renameMetadata|--renameFile|--renameDirectory
    <old> <new>`, with new names per `^[A-Za-z0-9_. -]{1,200}$`;
  - `import --importMetadata <path>`, where the path is only ever a `PrivateUploadCopy` path
    (T076), never the caller's path;
  - `library --deleteAll --type driver|plugin`;
  - `plugin --register|--deregister` with the vendor's arguments as found in T079.
  - Failing tests first in `CliCommandTest.java`.
- [ ] T081 [US6] Object administration adapter `adapter/rest/v81/V81ObjectAdministrationAdapter.java`:
  - REST `DELETE /diagrams/{name}`, `/diagrams/{name}/version/{n}`, `DELETE /modules`
    (`moduleName`), `/modules/{id}/version/{n}`, `DELETE /repository` (path query),
    `POST /isconnector`;
  - `adapter/cli/admin/CliObjectRunner.java` for rename and metadata import;
  - `RestEndpoint` values;
  - `domain/port/ObjectAdministrationPort.java` and the `Gateway`/`PortSet` set.
  - adds the capability `OBJECT_ADMINISTRATION` the way T011 added `SERVER_SETTINGS` (label, matrix "not yet
    verified", `NodeOverview`, `list_nodes` enum), with `CapabilityMatrixTest` and `ListNodesToolTest`;
  - Failing tests first: adapter and runner tests.
- [ ] T082 [US6] Library administration adapter `adapter/rest/v81/V81LibraryAdministrationAdapter.java`:
  - `PUT /libraries` (multipart `libraryFile`, `libraryType` Driver|Plugin, `libraryFileName`);
  - `DELETE /libraries/{name}`;
  - `PUT /edirules` (`ruleFile`, `ruleFileName`);
  - `PUT /keys` (`keystoreFile`, `owner`, `alias`, `privateKeyPassword` from the entry,
    `propertyName`);
  - `adapter/cli/admin/CliLibraryRunner.java` gets `deleteAll` and the plug-in commands.
  - `OBJECT_ADMINISTRATION` (added in T081) covers the libraries area too;
  - Failing tests first: adapter tests; leak check (key password).
- [ ] T083 [US6] Administration backups (D-15; after T053):
  - `application/AdministrationBackups.java` takes the backup before every delete, rename or
    replace:
    - workflow and module through `CliExportRunner` with history;
    - metadata through `export --exportMetadata` (the registry; for a rename limited to the
      named entry with `--exportMetadataNames`, T039);
    - repository path, EDI overwrite (only if the target file exists) and the module for a
      keystore through `export --exportRepositoryPath` or the module export;
  - on failure the change is not sent (`FAILED`, nothing sent).
  - `application/ImportService.java` `restore`: accepts an `ADMINISTRATION` manifest only on
    development nodes and only for `WORKFLOW` and `MODULE` contents; other kinds are refused with
    the manifest path for a manual import. The node rules are unchanged.
  - Failing tests first: `AdministrationBackupsTest.java`, `RestoreBackupAdministrationTest.java`
    (development node restores; a non-development node is refused as today; a tag list is
    refused with its path).
- [ ] T084 [US6] `application/ObjectAdministrationService.java`, objects area (`Area.OBJECTS`):
  - actions `deleteWorkflow`, `deleteModule` (with optional `version`), `deleteRepositoryPath`,
    `rename`, `importMetadata`, `startIsConnector`;
  - previews:
    - owner, versions, tags, active flag and users of the object from the inventory port
      (AS2, AS17);
    - a repository directory's file count (AS16);
    - rename: old and new name and the referencing objects (AS9);
    - metadata import: the metadata it replaces (AS12);
  - the backup before every delete, rename or import (T083), and nothing is sent if it fails;
  - each call admitted with `VersionGate.admitCall` and the entries of its variant (with or
    without `version`, the `rename` kind), so an excluded variant does not block the others;
  - `startIsConnector` is offered only when its row is `reachable` (T079, L-14);
  - the metadata import file goes to StartCLI only as a `PrivateUploadCopy`;
  - lock key `node|OBJECTS|<kind>:<name>`.
  - Failing tests first: `ObjectAdministrationServiceTest.java`:
    - AS1, AS2, AS9, AS10, AS12, AS16, AS17;
    - backup failure → nothing sent;
    - a second call on the same object → `PRECONDITION_FAILED`.
- [ ] T085 [US6] `application/ObjectAdministrationService.java`, libraries area
  (`Area.LIBRARIES`; after T084):
  - actions `uploadLibrary`, `deleteLibrary`, `deleteAllLibraries`, `registerPlugin`,
    `deregisterPlugin`, `deployEdiRules`, `deployKeys`;
  - previews:
    - upload name, size and SHA-256 (AS3);
    - `deleteAllLibraries` lists every file, and the confirmation is valid only for that list
      (AS4, FR-017); while L-21 is open (`rowFormatVerified: false`) it shows the count only, as
      D-14 does where INUBIT gives no ids, with the sentence that the list format is not
      verified, and the confirmation is bound to that count;
    - keys: module, owner and alias, with the key password from `secretEntry` (AS8); the module is
      backed up first (T083, FR-018), and nothing is sent if that fails (test);
    - an EDI target that exists is backed up first (AS15);
    - plug-ins re-read after (AS14);
  - `restartNeeded` for libraries;
  - lock key `node|LIBRARIES|<name>` or `node|LIBRARIES|*:<type>`.
  - Failing tests first: `LibraryAdministrationServiceTest.java`:
    - AS3, AS4, AS8, AS14, AS15;
    - the area of each action (objects opt-in alone does not admit them);
    - a changed library list → `CONFLICT`;
    - with `rowFormatVerified: false` the preview names the count and the sentence, no file names;
    - a second call on the same key → `PRECONDITION_FAILED`;
    - leak check (key password).
- [ ] T086 [US6] Tool `mcp/tools/AdministerObjectsTool.java`:
  - schemas `administer_objects.{input,output}.json`;
  - destructive;
  - announced when some group has `objects` or `libraries`.
  - Failing tests first: `AdministerObjectsToolTest.java`, `ToolHintsTest`, `ToolAnnouncementTest`.

### change_server_settings

- [ ] T087 [US6] `CliCommand`/session forms for settings changes:
  - `threads setNumberOfWorkflowThreads <n>`;
  - `privilegedWorkflows setThreadsPercentage <0..99>`;
  - `dbconnectiontolerance set …` (arguments per T079);
  - `RestEndpoint` + `PUT /configurations`, `PUT /license`;
  - the `--backupFile` / `--backupRepository` forms, only if T079 (L-16) found a usable form;
  - `adapter/rest/v81/V81SettingsAdministrationAdapter.java` and
    `adapter/cli/admin/CliSettingsRunner.java` change methods;
  - `domain/port/SettingsAdministrationPort.java` and the `Gateway`/`PortSet` set.
  - adds the capability `SETTINGS_ADMINISTRATION` the way T011 added `SERVER_SETTINGS` (label, matrix "not yet
    verified", `NodeOverview`, `list_nodes` enum), with `CapabilityMatrixTest` and `ListNodesToolTest`;
  - Failing tests first: `CliCommandTest`, adapter tests.
- [ ] T088 [US6] `application/SettingsAdministrationService.java`:
  - threads: 1 … licensed threads (from `/metrics`, fallback `GET /license`), otherwise
    `INVALID_INPUT` naming the licensed number (AS5);
  - privileged share 0 … 99 (AS11);
  - tolerance preview with the shut-down sentence (AS13);
  - `changeConfiguration`: 1–20 changes; a path per the data-model pattern; a masked path needs a
    `secretEntry` and a plain value gives `INVALID_INPUT` (FR-022); preview current/new masked on
    both sides (AS6); the full document is re-read and sent per T079 (full or partial);
  - license preview and re-read (AS7);
  - `restartNeeded`: always for threads and the privileged share, per T079 for the rest;
  - no backup (FR-018: the preview shows the current value);
  - each call admitted with `VersionGate.admitCall` and its own entries;
  - `setBackupStartOption` (`--backupFile`, `--backupRepository`, settings area): offered only
    when its rows are `reachable` after T079 (L-16), with the arguments and checks found there;
    otherwise the rows are `excluded` and the action is left out of the schema.
  - lock key `node|SETTINGS|<action>`; a second call gives `PRECONDITION_FAILED` (test);
  - Failing tests first: `SettingsAdministrationServiceTest.java` (AS5, AS6, AS7, AS11, AS13;
    `CONFLICT` when the value changed since the preview; leak check).
- [ ] T089 [US6] Tool `mcp/tools/ChangeServerSettingsTool.java`:
  - schemas `change_server_settings.{input,output}.json`;
  - destructive;
  - announced when some group has `settings`.
  - Failing tests first: tool test, `ToolAnnouncementTest`.
- [ ] T090 [US6] Cross-cutting safety for both tools:
  - `WriteRaceToolsTest` +2;
  - both tools' actions added to `ChangeToolsProductionRefusalTest` (SC-003, SC-004);
  - an area-off refusal per action (`NOT_CONFIGURED`);
  - `security/AdministrationSecretLeakTest.java` (configuration, key password, upload content).
- [ ] T091 [US6] Contracts, flips, docs and PR [BLOCKED: T079]:
  - the covered rows of research D-6 ("Covered rows before their tool exists") change from
    `planned` to `covered:administer_objects` / `covered:change_server_settings`, never to
    `reachable`;
  - `ObjectsV81ContractTest.java`, `LibrariesV81ContractTest.java`,
    `SettingsChangeV81ContractTest.java`;
  - L-21 (from T079): `LibraryListParser` and its tests follow the recorded row format, the
    `admin-libraries-list-*` fixtures join the empty T039 ones, and `rowFormatVerified` becomes
    `true` (the `read_model_details` description and the `deleteAllLibraries` preview then list
    the file names); failing first: `LibraryListParserTest` on the recorded non-empty list;
  - rows (excluded per T079 where the check failed; `startIsConnector` and `setBackupStartOption`
    flipped to `reachable:<tool>/<action>` with their contract tests where L-14 or L-16
    succeeded), matrix, docs page;
  - `docs/tools.md` sections;
  - `docs/setup.md` (`administration.objects|libraries|settings`, `uploads`, secret entries in
    the credential store with an example, and that AWS Parameter Store nodes have none);
  - `docs/backlog.md`: new entry "Secret entries for nodes with an AWS Parameter Store source"
    (research D-10);
  - `SECURITY.md` (uploads, secret entries);
  - `docs/research/inubit-interfaces.md`;
  - `CHANGELOG.md`;
  - `delivered-stories` + US6, with `waits` set on every US6 row still `planned`.

  Then open the US6 PR.

## Phase 9: User Story 7 – Administer users and groups (P4)

**Goal**: `administer_users` creates, changes and deletes users, groups and roles, sets passwords
from named entries, imports from files and the directory service, sends and deletes messages,
and never touches the server's own access (US7).

**Independent test**: with fakes, a user is created and deleted with preview and code; no call
takes or returns a password; the login user cannot be changed (US7 AS1–AS10).

- [ ] T092 [LIVE-W] [OWNER] [US7] L-12, L-13, L-15, L-18 on the 8.1 test server, with approval and
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
- [ ] T093 [US7] Session and command forms (D-7, D-25):
  - `CliSessionCommand.SESSION_OPTIONS` admits `create -cu …--password`, `passwd --password`
    (session only; the value comes from a `Secret` and is registered with the scrubber);
  - `CliCommand` admits the forms without a secret: `create -cu` (no password), `-cug`, `-cur`,
    `user --set…|--add…|--remove…`, `delete --deleteUserGroup`,
    `import --importUserGroup|--overwriteUserRoles|--importLDAPDetails <path>` (the path is only
    ever a `PrivateUploadCopy` path from T076),
    `--syncLDAPUsers`, `msgsend -s '<subject>' '<text>'`, `msgdel <id>`.
  - `CliCommand` rejects `--password` in every form.
  - From T012: add a value form that takes a `Secret` (its text holds a placeholder, so
    `CliSessionCommand.text()`/`toString()` never contain the value), the Windows non-ASCII refusal
    for every extra secret (as `refuseWindowsCredentials` does for the login password), and state
    in `CliRunner.runSession`'s Javadoc that production callers pass `SecretScrubber.global()`
    (log masking only uses it). A session that carries secrets and whose output was truncated at
    `maxOutputBytes` returns no output text (a cut echo could leave a prefix of a secret).
  - Failing tests first: `CliCommandTest`, `CliSessionCommandTest`, and
    `security/PasswordNeverOnCommandLineTest.java` (every recorded `LaunchSpec` of the user tests:
    argv and env contain no entry value).
- [ ] T094 [P] [US7] `application/OwnAccess.java` (D-16):
  - reads the login user's groups and roles (`CliUserRunner`) at preview and again before
    sending;
  - refuses deleting, regrouping or re-passwording the login user, removing its groups, and
    deleting its primary group or a role it holds;
  - the CLI-access right rule per T092 (L-15).
  - Failing tests first: `OwnAccessTest.java` (each refusal; a change between preview and send →
    refused).
- [ ] T095 [US7] `adapter/cli/admin/UserImportFileParser.java` (format per T092; after T094,
  whose `OwnAccess` gives the login user's group and roles to `UserImportCheck`):
  - finds logins, groups and roles in an import file (hardened XML, or the found format);
  - an unparsable file gives `INVALID_INPUT`.

  `application/UserImportCheck.java` refuses a file naming the login user, its group or one of
  its roles, and names which (AS6). It reads only the `PrivateUploadCopy` that is then passed to
  StartCLI, never the caller's path. `--syncLDAPUsers` is refused unless the login user is shown
  as internal, and also when this cannot be determined (AS7, FR-019b).
  - Failing tests first: parser and check tests, plus a swap test: the caller's file is replaced
    after the check by one naming the login user, and StartCLI still receives the checked copy
    (`ScriptedProcessLauncher` records the path and the test reads its content).
- [ ] T096 [US7] Adapter and port:
  - `adapter/rest/v81/V81UserAdministrationAdapter.java`: `DELETE /user/users/{name}` with
    `userReferences` and `userRepoReferences`, both explicit;
  - `adapter/cli/admin/CliUserAdministrationRunner.java` for the CLI forms and the session
    password path;
  - `domain/port/UserAdministrationPort.java` and the `Gateway`/`PortSet` set.
  - adds the capability `USER_ADMINISTRATION` the way T011 added `SERVER_SETTINGS` (label, matrix "not yet
    verified", `NodeOverview`, `list_nodes` enum), with `CapabilityMatrixTest` and `ListNodesToolTest`;
  - Failing tests first: adapter tests; leak check.
- [ ] T097 [US7] `application/UserAdministrationService.java`, users, groups and roles
  (`Area.USERS`):
  - actions `createUser` (without password here), `createGroup`, `createRole`, `setGroups`,
    `addGroups`, `removeGroups`, `deleteUser`, `deleteGroup`;
  - each call admitted with `VersionGate.admitCall` and its own entries, so `createUser` without
    a password stays offered when `create -cu --password` is excluded, and `setGroups` sends
    only the options given;
  - `createUser` without `secretEntry`: the description names what T092 (L-18) found (AS1);
  - `deleteUser` requires both reference choices and re-reads the list, because INUBIT answers
    200 even for an unknown user (AS5);
  - previews of groups and roles before and after (AS9, AS10);
  - `OwnAccess` before every send (AS4, FR-019);
  - lock key `node|USERS|user:<login>` or `node|USERS|group:<name>` / `role:<name>`.
  - Failing tests first: `UserAdministrationServiceTest.java`:
    - AS1 (no password), AS4, AS5, AS9, AS10;
    - a second call on the same key → `PRECONDITION_FAILED`.
- [ ] T098 [US7] Passwords from named entries (after T097):
  - `setPassword {login, secretEntry}` and `createUser` with an optional `secretEntry`, through
    the session password path of T093 and T096;
  - a missing or unreadable entry, or an AWS node, is refused before any call to INUBIT
    (AS3, D-10);
  - the audit names the entry, never the value (AS2);
  - the login user's password is never changed (AS4).
  - Failing tests first: `UserPasswordServiceTest.java` (AS1 with entry, AS2, AS3, AS4) and
    `PasswordNeverOnCommandLineTest` cases for both actions; leak check.
- [ ] T099 [US7] Imports and directory synchronisation (after T095, T097):
  - `importGroups`, `overwriteRoles`, `importLdapDetails` from an upload folder (`userImport`
    limit), checked by `UserImportCheck` on the `PrivateUploadCopy` (AS6, FR-019b);
  - `syncLdapUsers`, refused unless the login user is shown as internal (AS7);
  - lock key `node|USERS|import`.
  - Failing tests first: `UserImportServiceTest.java` (AS6 for each of login, group and role; AS7
    both ways; undeterminable → refused; swap after check → the checked copy sent).
- [ ] T100 [US7] Messages (after T097):
  - `sendMessage {recipients, subject, text}`, where the text is 1–1,000 characters with no
    control characters, the subject 1–200 characters with no control characters or `'`, and the
    text is passed as one argument (AS8, FR-020);
  - `deleteMessage {messageId}`;
  - the preview names the recipients and the text;
  - the audit holds at most 200 characters of the text.
  - Failing tests first: `UserMessageServiceTest.java` (AS8, each limit, quoting of the text as
    one argument).
- [ ] T101 [US7] Tool `mcp/tools/AdministerUsersTool.java`:
  - schemas `administer_users.{input,output}.json`, with no password field in either;
  - destructive;
  - announced when some group has `users`.
  - Failing tests first: tool test, `ToolAnnouncementTest`, `WriteRaceToolsTest` +1, the
    tool's actions added to `ChangeToolsProductionRefusalTest`, `security/UserAdminSecretLeakTest.java`.
- [ ] T102 [US7] Contracts, flips, docs and PR [BLOCKED: T092]:
  - `delete --deleteUser` changes from `planned` to `covered:administer_users` (research D-6), never
    to `reachable`;
  - `UserAdminV81ContractTest.java`;
  - rows (`passwd`/`create -cu --password` set to `excluded:secret only on a visible command line`
    if T092 showed the session cannot hide it), matrix, docs page;
  - `docs/tools.md`;
  - `docs/setup.md` (`administration.users`);
  - `SECURITY.md`;
  - `docs/research/inubit-interfaces.md`;
  - `CHANGELOG.md`;
  - `delivered-stories` + US7, with `waits` set on every US7 row still `planned`.

  Then open the US7 PR.

## Phase 10: Polish & cross-cutting concerns

- [ ] T103 9.0 recordings for US2–US7 [LIVE] [OWNER] (after the 9.0 pin is renewed):
  - run the read checks of T028 and the approved write checks of T054, T055, T069, T079, T092 on
    the 9.0 test server (US3 9.0: T039a, T046a, unless already done in the US3 follow-up PR);
  - fixtures with `.origin`, catalogue entries;
  - `*V90ContractTest` per story;
  - the `v9_0` rows flipped.
- [ ] T104 Coverage completion (SC-001, FR-027): `InterfaceCoverageCheckTest` with all seven stories
  delivered. No `planned` row and no `waits` value other than `-` are left: every row is
  `reachable`, `covered`, `undocumented` or `excluded`. A live check still open here gets its
  fallback from research "Live checks" (L-19 and L-20: the rows `GET /process/metadata/{name}` and
  `GET /isconnector` become `excluded:cannot be built from the documentation: answer form not
  recordable` and `excluded:cannot be built from the documentation: authentication not documented`,
  FR-002). Remove the backlog entry "Further read access to INUBIT" from `docs/backlog.md`.
- [ ] T105 [P] `live/AdministrationLiveTest.java` (`@Tag("live")`, opt-in, refused for production
  groups):
  - reads of US1–US3;
  - one reversible change per change tool (trace level set and back) on a test server;
  - `docs/live-tests.md`.
- [ ] T106 [P] `README.md` tool list and capability overview; `SECURITY.md` summary of the new
  protections (areas, uploads, secret entries, own-access rule).
- [ ] T107 Final validation:
  - all quickstart tests green;
  - the smoke steps 1–8 of `quickstart.md` run on the test servers and noted in its validation
    record;
  - the SC-001…SC-009 checklist written into `reviews/implementation.md`;
  - the tool count compared with the T001 reference (+9, SC-007).

## Dependencies

- **Phase 1 → Phase 2 → Phase 3 (US1)**: Phase 2 blocks every story.
- **Stories**: in priority order, one PR each (FR-004). The code dependencies are:
  - US2 needs T012 (session);
  - US3 needs T011;
  - US4 introduces the change infrastructure T048–T053, used by US5, US6 and US7;
  - US5 introduces the `administration` config (T067), used by US6 and US7;
  - US6 introduces uploads (T075–T076, including the private copy for StartCLI uploads), secret entries (T077) and the REST write methods (T078),
    used by US7 (imports, passwords);
  - US7 needs `CliUserRunner` (T032) from US2.
- **Live tasks**: T014, T028, T039, T039a, T046b, T046c, T054, T055, T069, T079, T092 and T024/T103
  can run as soon as the operator approves them, in parallel with the offline tasks. Only the
  contract and flip tasks (T025, T026, T037, T046, T046a–T046d, T066, T074, T091, T102, T103) wait
  for them.
- **PR merges**: a PR may merge with rows still `planned` while its live check is open (the
  action is then not offered). Phase 10 needs all of them.

## Parallel opportunities

- **Phase 2**: T004→T005 and T006→T007→T008/T009 run in parallel; T012 and T013 are independent
  of both.
- **US1**: T015 first; then T016, T017 and T018 side by side (different files, all use T015's
  records); T014 (live) at any time.
- **US2**: T029 first; then T030 and T031 side by side.
- **US3**: T040, T040a and T045 [P] beside T041 (separate files: the module and library parsers;
  `ProcessQuery`, `ProcessInstance`, `QueueLogRowParser`; `InterfaceCoverage`, `VersionGate`;
  `CliCommand`). T045a not beside T045 (both change `InterfaceCoverageTest`). T045b after T040a
  and T045.
- **US4**: T048, T050 [P]. Then `operate_server` (T056–T061) and `manage_tags` (T062–T064)
  after T049–T053, with serial points: T062 after T056 (`CliCommand.java`, `CliCommandTest.java`)
  and T063 after T058 (the `Gateway`/`PortSet` set).
- **US5**: T067 and T068 [P].
- **US6**: T075 [P]. Then `administer_objects` (T080–T086) and `change_server_settings`
  (T087–T089) after T076–T078, with serial points: T087 after T080 and T082 (`CliCommand.java`,
  `CliCommandTest.java`, the `Gateway`/`PortSet` set).
- **US7**: T094, then T095.

Never run two Maven builds at the same time in the same checkout.

## Implementation strategy

- **MVP**: Phases 1–3 (US1). The coverage list and its tests, the REST registry, the session, the
  secret rules and `get_server_settings` on 8.1. This is the first PR; it already shows every
  open entry on `docs/interface-coverage.md`.
- **Next increments**: US2 and US3 (reads, no risk), then US4 (first changes, introduces the
  shared change infrastructure), US5, US6, US7. Each increment ends with its PR, is merged after
  green CI and can be released on its own.
- **Live checks** run early and in parallel. An increment does not wait for its 9.0 recordings:
  9.0 rows flip in T026 and T103 once the pin question is settled.
- **Stop points**: after each phase, a code review of the phase diff and an update of
  `reviews/implementation.md`. Push after each finished task.
