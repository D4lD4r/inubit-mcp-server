---

description: "Task list for INUBIT version detection and version-dependent capabilities (feature 009)"
---

# Tasks: INUBIT Version Detection and Version-Dependent Capabilities

**Input**: Design documents from `/specs/009-inubit-version-detection/`

**Prerequisites**:
- [plan.md](plan.md) and [spec.md](spec.md) (clarifications 2026-10-17/18, reviews r1–r4)
- [research.md](research.md) (D-1…D-16) and [data-model.md](data-model.md)
- [contracts/configuration-delta.md](contracts/configuration-delta.md) and
  [contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md)
- [quickstart.md](quickstart.md)
- the code of features 001–008 (branch based on `008-repo-file-editing`)

**Tests**: REQUIRED (Constitution III, TDD). Every task starts with the test(s) it names, observed
failing on an assertion before the production code exists, and ends with `mvn -q verify` green.
Each task therefore contains its own tests; there are no separate "write failing tests" tasks. The
one exception is marked "verification task": its tests cover behaviour that earlier tasks already
built, so they may pass at once.

**Organization**: by user story. Both P1 stories need the foundation (domain types, version state,
gate). US2 (refusals, the safety property) comes before US1 (visibility) because US1's
`list_nodes` status uses the same gate rules. US4 (pins, P3) and US3 (extensibility, P2) follow.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on unfinished tasks)
- **[Story]**: US1 see versions and capabilities, US2 refusals, US3 extend the matrix, US4 pins

## Path Conventions

| Short form | Expands to |
|---|---|
| `main/…` | `src/main/java/de/dadecker/inubit/mcp/…` |
| `test/…` | `src/test/java/de/dadecker/inubit/mcp/…` |
| `res/…` | `src/main/resources/…` |
| `fixtures/…` | `src/test/resources/fixtures/…` |

## Safety rules

- **While developing**: run no INUBIT command and no network call against any INUBIT server; all
  tests are offline (WireMock, `FakeProcessLauncher`, fixtures). No live step is part of this
  feature's tasks.
- **Fixtures**: synthetic and neutral (`dev/node1`, `next/node1`, owner `jdoe`); the 9.0 fixture is
  synthetic and marked so. The identifier hooks must pass; never use `--no-verify`.
- **Configuration**: never change `~/.config/inubit-mcp/*` or the MCP registration.
- **Public repository**: no customer names in commit messages, PR descriptions or files.

---

## Phase 1: Setup

- [X] T001 [P] Fixtures (research D-15): add `fixtures/v9_0/rest/system_info.xml` and
  `system_info.http` (same document shape as `fixtures/v8_1/rest/system_info.xml`, `Version` =
  `9.0.7`) with a `fixtures/v9_0/README.md` stating that the file is synthetic until a recorded 9.0
  response exists; add CLI variants `fixtures/v8_1/cli/version_no_range.{stdout,stderr,exit}` (only
  the `CLI 8.1.17` line) and `fixtures/v9_0/cli/version.{stdout,stderr,exit}` (`CLI 9.0.7`,
  "Supported inubit Process Engine versions from 9.0.0 to 9.0.7."). Document the CLI variants in
  `fixtures/v8_1/cli/README.md`. Self-test: a parse test in
  `test/adapter/rest/v81/SystemInfoXmlParserTest.java` reads the 9.0 fixture's version.

---

## Phase 2: Foundational (blocks all stories)

- [X] T002 `main/domain/model/InubitVersion.java` (data-model): record `(int major, int minor,
  OptionalInt patch, String raw)`; `parse(String)` → `Optional`, leading `major.minor[.patch]`,
  suffix kept in `raw` and ignored for matching, no parsable major.minor → empty; ordering major,
  minor, patch (absent = 0); `sameMinor`. Test first: `test/domain/model/InubitVersionTest.java`
  (`8.1.17`, `8.1`, `8.1.17-HF3`, `9.0.7 build 42`, `x`, empty, ordering `8.1.9` < `8.1.17`).
- [X] T003 [P] (after T002) `main/domain/model/Capability.java` (enum with `kind` READ/WRITE and
  channels per the spec's tool classification: MONITORING, DIAGNOSIS, INVENTORY (REST and CLI
  parts), WORKSPACE_EXPORT, DEVELOPMENT_WRITE, DEPLOYMENT, E2E_TEST, PROCESS_CONTROL),
  `Channel.java` (REST, CLI, SOAP), `SupportState.java` (SUPPORTED/UNVERIFIED/UNSUPPORTED),
  `VersionRange.java` (inclusive major.minor bounds) and `CapabilityMatrix.java` with the
  production declaration of research D-3 (8.1 all SUPPORTED; 9.0, any other known and unknown
  versions: READ → UNVERIFIED, WRITE → UNSUPPORTED; a major-only line is looked up as unknown) and a
  constructor for other declarations. Test first: `test/domain/model/CapabilityMatrixTest.java`
  (every capability × 8.1.17, 8.1, 9.0.7, 10.0, unknown, line 9).
- [X] T004 [P] (after T002) `main/domain/model/ClientVersion.java` (data-model): `version`,
  optional `supportedFrom`/`supportedTo`; `compare(server)` → MATCH/MISMATCH/NOT_COMPARABLE on
  major.minor.patch; `compatibleWith(serverVersion | line)` → COMPATIBLE / INCOMPATIBLE /
  NOT_EVALUABLE with the range reduced to inclusive major.minor bounds, a major-only line
  compatible only if some minor of that major lies in the range (research D-8). Test first:
  `test/domain/model/ClientVersionTest.java` (8.1.17 client with 8.1.17, 8.1.18 → compatible +
  mismatch, 9.0.7 → incompatible, `V9_X` → incompatible, no range → not evaluable, unknown server
  → not evaluable).
- [X] T005 Configuration `versionLine` (research D-2, contracts/configuration-delta.md): replace
  `main/config/VersionLine.java` by the domain value `main/domain/model/VersionSetting.java`
  (sealed `Auto` | `Line(major, OptionalInt minor)` | `Pinned(InubitVersion)` with `text()`; it
  must live in `domain/model` because `NodeVersions` (application) holds it and
  `PackageBoundaryTest` forbids application → config); only its Jackson deserializer stays in
  `main/config/` and is registered in `main/config/ConfigLoader.java` (accepts strings; `AUTO`,
  `V8_1`, `V9_X`, or a version with at least major.minor.patch, suffix allowed); a YAML number
  (`8.1`) or a version without patch is the loader error "Invalid versionLine at <path>: expected
  AUTO, V8_1, V9_X or a full version such as \"8.1.17\"". Adapt `GroupConfig`, `NodeConfig`,
  `EffectiveNodeConfig` (resolution node → group → `Auto` unchanged), `Defaults.java`
  (`BUILTIN_VERSION_LINE`), `ConfigValidator`, `AdapterGatewayFactory` and the test users
  (`TestNodeConfig`, `GetHealthToolTest`, `ListNodesToolTest`, `ProcessControlToolsTest`,
  `DiagnosisToolFixture`, `InventoryToolFixture`, `AdapterGatewayFactoryTest`,
  `CliVersionProbeTest`, `HealthServiceTest`). `NodeSummary.versionLine` becomes the configured
  text, so `res/schemas/list_nodes.output.json` changes `versionLine` from the enum to a string in
  this task. Behaviour stays as today otherwise. Test first: `test/config/ConfigLoaderTest.java` (all four kinds,
  `"9.0.7-HF1"`, `8.1` unquoted, `"8.1"`, `"V10"`), `test/config/EffectiveNodeConfigTest.java`.
- [X] T006 `main/application/NodeVersions.java` + `main/domain/model/NodeVersionState.java`
  (research D-4, data-model): per-node setting, detection (version+time) or failure (code+time),
  `get_health` observation, `stale`, `pinContradiction`, client result or failure; derived
  `effectiveVersion()` (most recent of detection/observation; pin without observation; on `Auto`
  an observation replaces the detection), `needsDetection(purpose, now, bound)` with the node's
  `inventory.cacheTtl` as bound (`Auto` and no version, or WRITE and stale/older than the bound;
  never within 60 s after a failure — `NodeVersions` owns this back-off for the gate),
  `writableVersion(now, bound)` (on `Auto`, a stale or over-bound version whose re-detection failed
  or is suppressed by the back-off counts as unknown for writes; reads keep `effectiveVersion()`; on
  `Auto` a later observation clears `stale`, restarts the bound and supersedes a failed
  re-detection), contradiction
  set/cleared by observations (FR-012; a patch-only difference is no contradiction), client result
  kept for the process lifetime (a version without a range is a success with an empty range),
  only a failed or timed-out probe retried after 60 s (FR-002). Thread-safe, injected
  `Clock`. Test first: `test/application/NodeVersionsTest.java` (incl. detected 8.1, stale,
  re-detection fails → no writable version while a read still sees 8.1; then a successful
  observation within 60 s → writable again).
- [X] T007 Detection port, reporting and stale triggers (research D-4, D-5, D-10):
  - `GatewayFactory.detect(NodeId)` in `main/domain/port/GatewayFactory.java`, implemented in
    `main/adapter/AdapterGatewayFactory.java`: one `/system/info` request that bypasses the slot's
    success cache (re-detection), result reported to the observer; the slot's `fallbackUntil`
    stays only for adapter selection;
  - a domain port `main/domain/port/VersionObserver.java` (`detected`, `detectionFailed`,
    `observed`, `stale`, and the read `effectiveVersion(NodeId)` used by T024), implemented by
    `NodeVersions`; the `Slot` reports its AUTO detections and failures (the `SystemInfoListener`
    keeps feeding only the slot's AUTO detection); observations are reported by `HealthService`
    alone (T016);
  - `StaleReportingPorts` in `main/adapter/` wrapping every port the factory hands out: a
    `ToolErrorException` with `UNEXPECTED_RESPONSE` (from the client or a v81 parser) reports
    `stale(node)` and is rethrown;
  - `main/adapter/rest/InubitHttpClient.java`: a per-request `ResourceKind` (`RESOURCE` default =
    today; `ENDPOINT`): a 404 on an `ENDPOINT` request becomes `UNEXPECTED_RESPONSE` ("endpoint
    not found; the node may run another INUBIT version"); the v81 REST adapters pass `ENDPOINT`
    for `/system/info`, `/system/healthcheck`, the log, queue-log and model list endpoints;
  - wire it in `main/Wiring.java`.
  Test first: `test/adapter/AdapterGatewayFactoryTest.java` (detect once and again after the
  cache, back-off, detection/failure observer calls, a parser `UNEXPECTED_RESPONSE` marks stale, a 406 marks stale),
  `test/adapter/rest/InubitHttpClientTest.java` (endpoint 404 → `UNEXPECTED_RESPONSE`, resource
  404 → `NOT_FOUND`).
- [X] T008 `main/application/VersionGate.java` (research D-5, data-model `Admission`):
  `admit(NodeId, Capability, Purpose, Set<Channel>)`: detection when
  `NodeVersions.needsDetection` (via `GatewayFactory.detect`; never for `MONITORING`), matrix
  lookup, pin contradiction (writes refused), the client check only when `CLI` is among the
  channels (uses the client result if present; the probe itself comes in T019), then admit / admit
  with the "unverified version" warning / refuse with `UNSUPPORTED_VERSION` and the FR-005 texts of
  contracts/mcp-tools-delta.md (node, version, capability; client version and range when the
  client is the cause; next step never suggests a pin the node is not known to run; the gate builds
  its own `nextStep` and never forwards `V81VersionDetector`'s). Logs every
  refusal (FR-015). Test first: `test/application/VersionGateTest.java` (8.1 → admit; 9.0 read →
  warning; 9.0 write → refused, only the detection request; unknown after failed detection → write
  refused, read warning; contradiction → write refused; stale or older than the node's
  `inventory.cacheTtl` before a write → re-detect; stale and the re-detection fails → write refused
  with the detection-failure text, a second write within 60 s refused without a request, a read
  still admitted on the cached version; read uses the cache; REST channel on 9.0 with an
  8.1 client → admitted with warning, CLI channel → refused; test-only matrix marking a read
  UNSUPPORTED → read refused; MONITORING never refused even when a test-only matrix declares it
  UNSUPPORTED; pinned `"8.1.17"`: zero detection requests; `V9_X`: write refused, read warning;
  FR-016: no refusal or warning text contains the node's baseUrl, the username or the password).
  Client results in this and the following tests are seeded through the `NodeVersions` API (T006);
  the probe itself is wired in T019.
  Carry-over from T007 (review r1): the gate calls `GatewayFactory.detect` only for `AUTO` nodes
  (never for pinned ones, FR-001); a `TLS_ERROR` from `detect` is not a detection attempt (no
  back-off) and is passed through as the call's error. Note for the docs (T027): the healthcheck
  (`/ibis/rest/healthcheck`) stays a `RESOURCE` request — any answer means reachable (feature 001),
  so a 404 there is no stale trigger.

**Checkpoint**: foundation ready; no tool behaviour has changed yet.

---

## Phase 3: User Story 2 - Unsupported tools are refused clearly (Priority: P1)

**Goal**: every write tool runs only on a known, `SUPPORTED` version; reads on unverified versions
carry a warning (FR-005, FR-008, FR-009).

**Independent Test**: with the 9.0 fixture, each write tool is refused with `UNSUPPORTED_VERSION`
and only the detection request reaches WireMock; each read tool runs with `warnings`.

The tasks of this phase change constructors that `main/Wiring.java` and test fixtures build; they
run one after the other (no [P]).

- [X] T009 [US2] Development writes: rename `DevelopmentGuard.Capability` to
  `DevelopmentGuard.Operation` (all users of `DevelopmentGuard.Capability`, among them
  `ImportService`, `TagService`, `E2eTestService`, `WriteChallengeRegistry` and their tests) and map `RUN_E2E_TEST` → `E2E_TEST` (channels SOAP+REST), every
  other operation → `DEVELOPMENT_WRITE` (CLI+REST); call `VersionGate.admit(…, WRITE, channels)` in
  `main/application/DevelopmentGuard.java` after the configuration checks and before `cli.check`,
  for import, restore of development backups, set_active, tag and run_e2e_test; refusals are
  audited through each service's existing `Call.refuse` (`ImportService`, `TagService`,
  `E2eTestService`); admission warnings travel in a new `warnings` list on
  `main/domain/model/DevelopmentPolicy.java` and go into each result's `warnings`. Wire the gate in
  `main/Wiring.java` and the fixtures building `DevelopmentGuard`. Test first:
  `test/application/ImportServiceVersionTest.java` (import, restore, set_active on 9.0 / `V9_X` /
  failed detection: refused, audit `REFUSED`, only the detection request; with a seeded 8.1.17
  client at an 8.1.18 server → executed with the mismatch warning in `warnings`), `test/application/TagServiceTest.java`,
  `test/application/E2eTestServiceTest.java` (new cases).
  Carry-over from T008 (review r1): the contract's refusal message names the tool
  ("import_artifacts needs development writes, which INUBIT 9.0.7 …"); the gate has no tool
  parameter, so the call sites of T009–T014 and T023 prefix the gate's message with the tool name
  (or pass it through a small overload added to `VersionGate`); assert it in the tests.
- [X] T010 [US2] Process control: call the gate (`PROCESS_CONTROL`, WRITE, CLI+REST) in
  `main/application/WriteGuard.java` before `checkAvailable`, so `restart_process` and
  `kill_process` are refused before the REST state read; add an optional `warnings` field to
  `res/schemas/restart_process.output.json`, `kill_process.output.json` and their result records
  for admission warnings, carried by a new `warnings` list on `main/domain/model/WritePolicy.java`.
  Wire it in `main/Wiring.java`. Test first: `test/application/ProcessControlServiceTest.java`
  (both tools on 9.0: refused, audit `REFUSED`, no REST request other than the detection; with a
  seeded client mismatch → executed with the warning in `warnings`). The probe-timeout case of
  US2-5 is tested in T019.
- [X] T011 [US2] Deployment-backup restores: `DevelopmentGuard.admitDeploymentRestore`
  (`main/application/DevelopmentGuard.java`:118, called from `ImportService.admitRestore`) calls the
  gate with `DEPLOYMENT` (WRITE, channels `{CLI, REST}`) for the backup's node; its admission
  warnings go into the `DevelopmentPolicy.warnings` it builds (:130) and from there into the
  restore result's `warnings`. Test first:
  `test/application/RestoreDeploymentTest.java` (9.0 target node refused, audit `REFUSED`; a seeded
  client mismatch → restore executed with the warning in `warnings`).
  Open note (T011 review r1): on a node that is also a development stage, a deployment backup is
  admitted through `DevelopmentGuard.admit` (DEVELOPMENT_WRITE), not DEPLOYMENT; no effect with the
  production matrix (same states), revisit when a matrix distinguishes the two.
- [X] T012 [US2] Read warnings (research D-5, D-6): add optional `warnings` (string array, only
  when non-empty) to `res/schemas/find_processes.output.json`, `query_logs.output.json`,
  `list_inventory.output.json`, `get_inventory_item.output.json` and their result records; call the
  gate in `main/application/DiagnosisService.java` (DIAGNOSIS, `{REST}`) and
  `main/application/InventoryService.java` (`list_inventory` diagrams `{REST}`; modules `{CLI}`,
  refused as a whole when the client is incompatible; `get_inventory_item` `{REST}` first, then
  `{CLI}` before the history, whose refusal becomes the existing `unavailable` history part with
  `UNSUPPORTED_VERSION`) and pass warnings into the results (per node in group results). Update
  `DiagnosisToolFixture` and `InventoryToolFixture` (they use `AUTO`: add the system_info stub or
  pin `V8_1` where detection is not the subject) and `main/Wiring.java`. Test first:
  `test/application/DiagnosisServiceTest.java`, `test/application/InventoryServiceTest.java` (9.0
  node → warning; 8.1 → no field; 9.0 with an 8.1 client: diagrams listed with warning, modules
  refused, item history `unavailable` with `UNSUPPORTED_VERSION`; FR-006: a group call where one
  node is refused by a test-only matrix returns that node's error and the others' results).
- [X] T013 [US2] Workspace export and monitoring: the gate (WORKSPACE_EXPORT, READ, `{CLI}`) in
  `main/application/WorkspaceService.java` export (warnings into the existing `warnings`); in
  `main/application/HealthService.java` the gate (MONITORING) never detects and never refuses — its
  warning is derived from the version `get_health`'s own `/system/info` read returns (the same
  read T016 reports as the observation) and replaces today's "unsupported … the 8.1 adapters are
  used" text: the version warnings cached by `main/adapter/AdapterGatewayFactory.java` (:268-269,
  `configured()`/`detected()`) and its `fallbackWarning` (:345-347, "… assuming 8.1") are dropped,
  so `HealthService.gatewayWarnings` no longer carries a version text; the unknown-version warning
  reads "the INUBIT version of <node> is unknown (<code> at <time>); results are unverified". Wire both in `main/Wiring.java`. Test first:
  `test/application/WorkspaceExportTest.java` (new case), `test/application/HealthServiceTest.java`
  (adjusted 9.0.2 expectations; exactly one `/system/info` request per `get_health` on an `AUTO`
  node; no duplicate version warning; a failed `AUTO` detection → exactly one version warning and no
  "assuming 8.1") and `test/adapter/AdapterGatewayFactoryTest.java` (the assertions on the old texts,
  around :218 and :451, adjusted).
- [X] T014 [US2] `check_artifacts` module lookup (FR-001, FR-011): in
  `main/application/ArtifactCheckService.java` (:513) call the gate (INVENTORY, READ, `{CLI}`)
  before `listModules`; a refusal skips the lookup and is reported as the existing "module could not
  be looked up" finding with the reason; an admission warning (unverified version, client
  mismatch) becomes a finding with severity `WARNING` (`check_artifacts` has no `warnings` field);
  the first call on an `AUTO` node detects. Wire it in `main/Wiring.java`. Test first:
  `test/application/ArtifactCheckServiceTest.java` (9.0 node with an 8.1 client: lookup skipped with
  the reason, no StartCLI run; 8.1: unchanged).
- [X] T015 [US2] Tool announcement (research D-12, FR-007): the conditions in `main/Wiring.java`
  (`anyDevelopmentNode`, `anyWriteEnabled`, `anyE2eNode`, deployment targets) ignore nodes pinned to
  a version whose matrix state for the capability is `UNSUPPORTED`; `AUTO` nodes count as capable.
  Test first: `test/DevelopmentWiringTest.java`, `test/mcp/ProcessControlToolsTest.java` (a profile
  whose only development / write nodes are `V9_X` announces none of those tools; one 8.1 node more
  announces them; a profile whose every `EXECUTE` deployment target is `V9_X` announces neither
  `deploy_release` nor the deployment `restore_backup`).

**Checkpoint**: US2 complete — the safety property holds for every write tool.

---

## Phase 4: User Story 1 - See versions and capabilities per node (Priority: P1)

**Goal**: `list_nodes` and `get_health` show server and client versions, status and usable
capabilities (FR-001, FR-002, FR-004, FR-011).

**Independent Test**: `list_nodes` before `get_health` shows `not_yet_detected` with zero requests;
after `get_health` it shows the detected version, the client version and `supported`.

- [X] T016 [US1] `get_health` observation (FR-001, FR-012): `HealthService` is the single owner of
  observations and reports the version it reads for every node (pinned too) through
  `VersionObserver.observed`; an observed major.minor
  different from a pin sets the contradiction and adds a warning; a patch-only difference only
  warns. Test first: `test/application/HealthServiceTest.java` (pinned `V8_1` node observing 9.0.7 →
  warning + contradiction; later 8.1.17 → cleared; pinned `"8.1.17"` observing 8.1.18 → warning, no
  contradiction).
- [ ] T017 [US1] Client probe port (research D-7): `main/domain/port/ClientVersionPort.java`
  (`probe(NodeId, Duration)` → `ClientVersion`), implemented by
  `main/adapter/cli/CliVersionProbe.java` (returns the domain `ClientVersion`; replaces
  `mismatchWarning` by `ClientVersion.compare`); wired in `main/Wiring.java`. Test first:
  `test/adapter/cli/CliVersionProbeTest.java` (version with range, `version_no_range`, 9.0 variant,
  timeout → `TIMEOUT`, no installation → `CLI_UNAVAILABLE`; FR-016: a failure's StartCLI output
  excerpt is scrubbed by `SecretScrubber` and contains no credential or URL).
- [ ] T018 [US1] Client probe in `get_health` (FR-002): `HealthService.checkServer` submits the
  probe as a fourth parallel future bounded by the node's health deadline unless `NodeVersions`
  already holds a successful client result (reused for the process lifetime); the report gains
  `client` (`version`, `supportedFrom`, `supportedTo`, `comparison`); successes and failures are
  recorded in `NodeVersions` (failures not cached, retry after 60 s); a timeout or failure gives the
  new part `client` in `unavailable` (`not yet detected (timeout)` or the cause). Add `client` to
  `main/domain/model/UnavailablePart.java` and `res/schemas/get_health.output.json` (part enum,
  report `client`). Test first: `test/application/HealthServiceTest.java` (second `get_health` runs
  no probe; an 8.1.17 client at an 8.1.18 server → `comparison: mismatch` plus the warning; a node without a StartCLI installation reports the client as `not_available` and runs
  no probe; FR-016: report and `unavailable` texts contain no baseUrl, username or password),
  `test/mcp/tools/GetHealthToolTest.java` (timing within `timeout` + 1 s with a probe that hangs),
  `test/performance/HealthOverviewPerformanceTest.java` stays within 10 s.
- [ ] T019 [US1] Client check outside `get_health` (FR-002, FR-011): `VersionGate` runs the probe
  before the first `CLI`-channel admission on a node when no client result is known (bounded by
  `cliTimeout`, not counted against the tool's own timeout); `INCOMPATIBLE` refuses with the client
  version and range; `NOT_EVALUABLE` (probe failed/timed out, no range, server unknown) and
  `MISMATCH` admit with a warning; a failed probe is retried at most once per 60 s. Test first:
  `test/application/VersionGateTest.java` (8.1.17 client + 8.1.18 server → warning; + 9.0.7 →
  refused; `V9_X` → refused; timeout → warning, retried after 60 s with a fake clock) and
  `test/application/ProcessControlServiceTest.java` (US2-5 end to end: a probe timeout → restart
  executed with the warning in `warnings`; the next call more than 60 s later probes again).
- [ ] T020 [US1] `list_nodes` (research D-9, contracts/mcp-tools-delta.md): `NodeSummary` gains the
  `version` block (data-model: `setting`, `server`, `client`, `status`, `capabilities`
  `usable`/`notUsable` with reasons, `warnings`; "usable" = `SUPPORTED`, or `UNVERIFIED` for reads
  and not refused by the client check; a mixed-channel capability whose StartCLI part is refused is
  usable and additionally listed as `"inventory (StartCLI parts)"` in `notUsable`); `versionLine`
  becomes the configured text (string). `main/mcp/tools/ListNodesTool.java` takes a
  `Supplier<List<NodeSummary>>` combining the startup configuration summaries with `NodeVersions`
  at call time; it never contacts a node. Update `res/schemas/list_nodes.output.json`
  (`version` object, `additionalProperties: false` kept), the tool
  description (next step `get_health` for `not_yet_detected`) and `main/Wiring.java`. Test first:
  `test/mcp/tools/ListNodesToolTest.java` (zero WireMock requests; `not_yet_detected`; after a
  detection `supported`; 9.0 node with an 8.1 client `partially_supported` with REST reads usable,
  `inventory (StartCLI parts)`, `workspace_export` and write capabilities not usable; failed detection `unknown` with
  reason and time; a pin contradicted by `get_health` shown in `version.warnings` and the write
  capabilities not usable; a node without a StartCLI installation shows the client as
  `not_available`; an 8.1.17 client at an 8.1.18 server shows `mismatch` with the StartCLI-backed
  capabilities still usable (US1-4); FR-016: no baseUrl, username or password in any `version`
  field). The
  `versionLine` schema change was done in T005.

**Checkpoint**: US1 complete — the MVP (US2 + US1) is usable.

---

## Phase 5: User Story 4 - Pin the version per node (Priority: P3)

**Goal**: concrete and line pins work end to end, `--check-config` shows them and the client
(FR-011a, FR-012).

**Independent Test**: a node pinned to `"8.1.17"` triggers no detection request, `--check-config`
shows the client against the pin, and a contradicting observation refuses writes.

- [ ] T021 [US4] Verification task (no production change expected; the behaviour comes from T003,
  T006 and T008): pinned nodes end to end through a tool — no detection request for
  `Line`/`Pinned` (FR-001); `V8_1`/a concrete pin give the matrix entry of their major.minor;
  `V9_X` is looked up as unknown (writes refused, reads unverified); the contradiction from T016
  refuses writes. Tests: `test/application/ImportServiceVersionTest.java` (pinned `"8.1.17"`: import
  executed with zero detection requests; `V9_X`: refused; a pin contradicted by `get_health` →
  refused, a later matching `get_health` → executed). If a case fails, fix it in the class
  responsible and name the fix in the commit.
- [ ] T022 [US4] `--check-config` (research D-13, contracts/configuration-delta.md):
  `main/Launcher.java` creates a `CliRunner` for the check (no credentials, no server URL) and passes
  per-node client results to `main/config/ConfigSummary.java`; the node line gains
  `, inubit: <setting>` and, with a StartCLI installation, `, client: <version> (<from>–<to>)`, with
  a concrete pin also `, client vs pin: match|mismatch`; a failing probe is a warning. Replace the
  `V9_X` warning text in `main/config/ConfigValidator.java` with "versionLine V9_X: write tools are
  refused on this node (INUBIT 9.x is not supported for writes yet); read tools run unverified". No
  INUBIT contact. Test first: `test/config/ConfigSummaryTest.java`,
  `test/config/ConfigValidatorTest.java`, `test/MainTest.java` (V9_X text on stderr),
  `test/MainProcessTest.java` if it pins the text.

---

## Phase 6: `deploy_release` across versions (FR-014a; part of US2's safety property)

- [ ] T023 [US2] Version check in `main/application/DeployGuard.java` (research D-11): in `check()`,
  where `Admitted` has `targetNodes` and `sourceNodes` and the audited `refuse` is available, call
  `VersionGate.evaluate(node, DEPLOYMENT, WRITE, {CLI, REST})` (non-throwing variant of `admit`,
  added to `main/application/VersionGate.java`) for every source and target node (detection,
  matrix, pin contradiction, client check incl. the probe), collect all results, then refuse the
  whole
  deployment if any node is unknown, any target is not `SUPPORTED` for `DEPLOYMENT`, sources differ
  from each other or from any target in major.minor (`V8_1`/a concrete pin give major.minor;
  `V9_X` counts as unknown) with one message naming every source and target node with its version
  and, per refused node, its cause. `DeployGuard` gets the gate as a
  constructor parameter (`main/Wiring.java` and the DeployGuard fixtures change); `Admitted` gains
  the admission `warnings`, which `DeployService` puts into a new optional top-level `warnings` of
  `DeploymentPreview`, mapped by `main/mcp/tools/DeployReleaseTool.java` (which builds the preview
  output by hand, :100) and declared in `res/schemas/deploy_release.output.json` as strings (unlike
  the per-plan `warnings` objects), and into the execute result's existing `warnings`.
  `DeployService` already calls the guard in preview and in execute. Test first:
  `test/application/DeployServiceVersionTest.java` (8.1.17 → 8.1.18 allowed; 8.1 → 9.0 refused;
  unknown source refused; unknown target refused; differing sources refused; version changed
  between preview and execute → refused at execute; a client whose range excludes a node's
  major.minor → refused; an 8.1.17 client at an 8.1.18 server → warning in preview and result;
  8.1 → 9.0 refused with one message naming every node, version and cause; nothing sent on
  refusal; audit `REFUSED`) and `test/mcp/tools/DeployReleaseToolTest.java` (the preview's
  top-level `warnings` appears in the tool output).

---

## Phase 7: User Story 3 - Extend the matrix (Priority: P2)

**Goal**: a new version needs only a port set and a matrix entry (FR-013, FR-014, SC-004).

- [ ] T024 [US3] Port-set selection by effective version (research D-16):
  `main/adapter/AdapterGatewayFactory.java` gets a `PortSetRegistry` constructor parameter (version
  ranges → port-set factories, plus a default); `Slot.ensureClient()` keeps the lazy client, but
  the port set is chosen from the registry by the node's effective version (read through the
  domain port `VersionObserver.effectiveVersion`, T007)
  when ports are first requested after the gate's admission, and rebuilt when a re-detection moves
  the node to another range. Production registers only the 8.1 port set as the default for every
  version (no behaviour change). Wire it in `main/Wiring.java`. Test first:
  `test/adapter/AdapterGatewayFactoryTest.java` (default used for 8.1, 9.0 and unknown; a
  registered range selected after detection; rebuilt after a re-detection into another range).
- [ ] T025 [US3] `test/application/CapabilityExtensionTest.java`: with a test-only
  `CapabilityMatrix` declaring a fictitious range (e.g. 7.9) with a subset of capabilities and a
  test-only port set registered for it (T024), a node of that version gets exactly the declared
  tools through that port set (others refused, except `get_health`, which is never refused and only
  warns), `list_nodes` shows exactly the declared capabilities as usable (US3-2), an 8.1 node is
  unchanged, a 9.0 node behaves as in US2 — without touching any tool or service class.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T026 [P] `docs/setup.md`: `versionLine` values incl. concrete pins and quoting, when the
  version is detected, the deliberate behaviour changes (writes refused after a failed detection
  and on `V9_X`), the client probe and `--check-config` line.
- [ ] T027 [P] `docs/tools.md`: the tool classification table; `UNSUPPORTED_VERSION` (now returned;
  replace "reserved … not returned by this version") with its variants; `list_nodes` `version`
  block; `get_health` `client` part and fields; `warnings` on the four read tools and on
  restart/kill; write-tool and `deploy_release` refusals; `check_artifacts` lookup behaviour; tool
  announcement rule. Keep `DevelopmentToolsReferenceTest`, `ArtifactToolsReferenceTest` and
  `DeploymentReferenceTest` green.
- [ ] T028 [P] `README.md` "Safety model": writes only on verified INUBIT versions; `CHANGELOG.md`
  `[Unreleased]`: feature 009 with the deliberate behaviour changes.
- [ ] T030 Gate texts in the profile's terminology (found in the T010 review): the refusal and
  warning texts built by `main/application/VersionGate.java` (and the `list_nodes` version reasons
  of T020) use the profile's display names for the node and group levels like every other message
  (feature 002), instead of the plain word "node". Test first:
  `test/MessageTerminologyTest.java` (a version refusal on a profile with custom terminology uses
  the custom names; remove the `versionLine: V8_1` workaround added in T010 where it only served to
  avoid the gate's texts) and `test/TerminologyRenderingTest.java` if it covers messages.
- [ ] T029 Validate per quickstart.md: `mvn -q clean verify`; `mvn -q -Dtest=NoCustomerIdentifiersTest
  test` with the local lists (it must run, not skip); record the outcome in `quickstart.md`. The
  optional live check (read-only `get_health`/`list_nodes`) only with the person's approval.

---

## Dependencies & Execution Order

- T001 runs in parallel with everything in Phase 2. T002 first; T003 and T004 after T002 (parallel
  to each other); T005 after T002; T006 after T003–T005; T007 after T006; T008 after T003, T004,
  T006, T007.
- US2 (T009–T015) needs T008 and runs in order (shared constructors and `Wiring.java`).
- US1 (T016–T020) needs T008; T017 before T018 and T019; T020 after T016, T018, T019.
- US4 (T021–T022) needs T016 (contradiction) and T017 (probe for `--check-config`).
- T023 needs T008 and T019 (client check incl. the probe), and T021 for pins. T024 needs T006/T008;
  T025 needs T024 and T020 (it asserts `list_nodes`).
- Polish (T026–T029) last.

## Parallel Example

```text
Phase 2:  T002 InubitVersion  →  T003 Capability/Matrix  |  T004 ClientVersion  (T001 fixtures alongside)  →  T005 → T006 → T007 → T008
Phase 8:  T026 setup.md  |  T027 tools.md  |  T028 README/CHANGELOG
```

## Implementation Strategy

- **MVP**: Phases 1–4 (foundation, US2 refusals, US1 visibility). After Phase 3 the safety
  property already holds; after Phase 4 the person can see why.
- Then US4 (pins and `--check-config`), the deployment check, US3 (port-set selection and the
  extensibility proof) and the docs.
- Run each task through the implementer → reviewer loop (`/speckit-duo:implement`); every commit
  stays green because each task carries its own tests.
