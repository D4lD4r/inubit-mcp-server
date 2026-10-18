---

description: "Task list for INUBIT version detection and version-dependent capabilities (feature 009)"
---

# Tasks: INUBIT Version Detection and Version-Dependent Capabilities

**Input**: Design documents from `/specs/009-inubit-version-detection/`

**Prerequisites**:
- [plan.md](plan.md) and [spec.md](spec.md) (clarifications 2026-10-17/18, reviews r1–r4)
- [research.md](research.md) (D-1…D-15) and [data-model.md](data-model.md)
- [contracts/configuration-delta.md](contracts/configuration-delta.md) and
  [contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md)
- [quickstart.md](quickstart.md)
- the code of features 001–008 (branch based on `008-repo-file-editing`)

**Tests**: REQUIRED (Constitution III, TDD). Every task starts with the test(s) it names, observed
failing on an assertion before the production code exists, and ends with `mvn -q verify` green.
Each task therefore contains its own tests; there are no separate "write failing tests" tasks.

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

- [ ] T001 [P] Fixtures (research D-15): add `fixtures/v9_0/rest/system_info.xml` and
  `system_info.http` (same document shape as `fixtures/v8_1/rest/system_info.xml`, `Version` =
  `9.0.7`) with a `fixtures/v9_0/README.md` stating that the file is synthetic until a recorded 9.0
  response exists; add CLI variants `fixtures/v8_1/cli/version_no_range.{stdout,stderr,exit}` (only
  the `CLI 8.1.17` line) and `fixtures/v9_0/cli/version.{stdout,stderr,exit}` (`CLI 9.0.7`,
  "Supported inubit Process Engine versions from 9.0.0 to 9.0.7."). Document the CLI variants in
  `fixtures/v8_1/cli/README.md`. Self-test: a parse test in
  `test/adapter/rest/v81/SystemInfoXmlParserTest.java` reads the 9.0 fixture's version.

---

## Phase 2: Foundational (blocks all stories)

- [ ] T002 [P] `main/domain/model/InubitVersion.java` (data-model): record `(int major, int minor,
  OptionalInt patch, String raw)`; `parse(String)` → `Optional`, leading `major.minor[.patch]`,
  suffix kept in `raw` and ignored for matching, no parsable major.minor → empty; ordering major,
  minor, patch (absent = 0); `sameMinor`. Test first: `test/domain/model/InubitVersionTest.java`
  (`8.1.17`, `8.1`, `8.1.17-HF3`, `9.0.7 build 42`, `x`, empty, ordering `8.1.9` < `8.1.17`).
- [ ] T003 [P] `main/domain/model/Capability.java` (enum with `kind` READ/WRITE and channels per the
  spec's tool classification: MONITORING, DIAGNOSIS, INVENTORY, WORKSPACE_EXPORT,
  DEVELOPMENT_WRITE, DEPLOYMENT, E2E_TEST, PROCESS_CONTROL), `SupportState.java`
  (SUPPORTED/UNVERIFIED/UNSUPPORTED), `VersionRange.java` (inclusive major.minor bounds) and
  `CapabilityMatrix.java` with the production declaration of research D-3 (8.1 all SUPPORTED;
  9.0, any other known and unknown versions: READ → UNVERIFIED, WRITE → UNSUPPORTED) and a
  constructor for other declarations. Test first: `test/domain/model/CapabilityMatrixTest.java`
  (every capability × 8.1.17, 8.1, 9.0.7, 10.0, unknown).
- [ ] T004 [P] `main/domain/model/ClientVersion.java` (data-model): `version`, optional
  `supportedFrom`/`supportedTo`; `compare(server)` → MATCH/MISMATCH/NOT_COMPARABLE on
  major.minor.patch; `compatibleWith(serverVersion | line)` → COMPATIBLE / INCOMPATIBLE /
  NOT_EVALUABLE with the range reduced to inclusive major.minor bounds, a major-only line
  compatible only if some minor of that major lies in the range (research D-8). Test first:
  `test/domain/model/ClientVersionTest.java` (8.1.17 client with 8.1.17, 8.1.18 → compatible +
  mismatch, 9.0.7 → incompatible, `V9_X` → incompatible, no range → not evaluable, unknown server
  → not evaluable).
- [ ] T005 Configuration `versionLine` (research D-2, contracts/configuration-delta.md): replace
  `main/config/VersionLine.java` by a `VersionSetting` value (sealed `Auto` | `Line(major,
  OptionalInt minor)` | `Pinned(InubitVersion)` with `text()`), parsed by a custom deserializer
  registered in `main/config/ConfigLoader.java` (accepts strings; `AUTO`, `V8_1`, `V9_X`, or a
  version with at least major.minor.patch, suffix allowed); a YAML number (`8.1`) or a version
  without patch is the loader error "Invalid versionLine at <path>: expected AUTO, V8_1, V9_X or a
  full version such as \"8.1.17\"". Adapt `GroupConfig`, `NodeConfig`, `EffectiveNodeConfig`
  (resolution node → group → `Auto` unchanged) and every use of `VersionLine` (incl.
  `AdapterGatewayFactory`, `TestNodeConfig`, `live/LiveTarget`). Behaviour stays as today in this
  task. Test first: `test/config/ConfigLoaderTest.java` (all four kinds, `"9.0.7-HF1"`, `8.1`
  unquoted, `"8.1"`, `"V10"`), `test/config/EffectiveNodeConfigTest.java`.
- [ ] T006 `main/application/NodeVersions.java` + `main/domain/model/NodeVersionState.java`
  (research D-4, data-model): per-node setting, detection (version+time) or failure (code+time),
  `get_health` observation, `stale`, `pinContradiction`, client result or failure; derived
  `effectiveVersion()` (most recent of detection/observation; pin without observation; on `Auto`
  an observation replaces the detection), `needsDetection(purpose, now, bound)` (`Auto` and no
  version, or WRITE and stale/older than the bound; never within 60 s after a failure),
  contradiction set/cleared by observations (FR-012), client failure retry after 60 s (FR-002).
  Thread-safe. Uses an injected `Clock`. Test first: `test/application/NodeVersionsTest.java`.
- [ ] T007 Detection port and reporting (research D-4, D-5, D-10): add `GatewayFactory.detect(NodeId)`
  in `main/domain/port/GatewayFactory.java`, implemented in `main/adapter/AdapterGatewayFactory.java`
  by the existing `Slot.detect()` (one `/system/info` request, 60 s back-off unchanged); add a
  domain callback (e.g. `VersionObserver` port: `detected`, `detectionFailed`, `observed`,
  `stale`) that the `Slot` and the `SystemInfoListener` call, implemented by `NodeVersions`. Mark
  stale on `UNEXPECTED_RESPONSE`, HTTP 406 and HTTP 404 not mapped to `NOT_FOUND` seen by the
  node's client. Wire it in `main/Wiring.java`. Test first: `test/adapter/AdapterGatewayFactoryTest.java`
  (detect once, back-off, observer calls, stale triggers) and `test/application/NodeVersionsTest.java`.
- [ ] T008 `main/application/VersionGate.java` (research D-5, data-model `Admission`):
  `admit(NodeId, Capability, Purpose)`: detection when `NodeVersions.needsDetection` (via
  `GatewayFactory.detect`), matrix lookup, pin contradiction (writes refused), the client check for
  CLI channels (uses the client result if present; the probe itself comes in T018), then admit /
  admit with the "unverified version" warning / refuse with `UNSUPPORTED_VERSION` and the FR-005
  texts of contracts/mcp-tools-delta.md (node, version, capability; client version and range when
  the client is the cause; next step never suggests a pin the node is not known to run). Logs every
  refusal (FR-015). Test first: `test/application/VersionGateTest.java` (8.1 → admit; 9.0 read →
  warning; 9.0 write → refused, only the detection request; unknown after failed detection → write
  refused, read warning; contradiction → write refused; stale or > 10 min before a write →
  re-detect; read uses cache; test-only matrix marking a read UNSUPPORTED → read refused).

**Checkpoint**: foundation ready; no tool behaviour has changed yet.

---

## Phase 3: User Story 2 - Unsupported tools are refused clearly (Priority: P1)

**Goal**: every write tool runs only on a known, `SUPPORTED` version; reads on unverified versions
carry a warning (FR-005, FR-008, FR-009).

**Independent Test**: with the 9.0 fixture, each write tool is refused with `UNSUPPORTED_VERSION`
and only the detection request reaches WireMock; each read tool runs with `warnings`.

- [ ] T009 [US2] Development writes: call `VersionGate.admit(node, DEVELOPMENT_WRITE | E2E_TEST,
  WRITE)` in `main/application/DevelopmentGuard.java` after the configuration checks and before
  `cli.check`, for import, restore of development backups, set_active, tag and run_e2e_test;
  refusals are audited through each service's existing `Call.refuse` (`ImportService`,
  `TagService`, `E2eTestService`). Test first: `test/application/ImportServiceVersionTest.java`
  (import, restore, set_active on 9.0 / `V9_X` / failed detection: refused, audit `REFUSED`,
  only the detection request), `test/application/TagServiceTest.java`,
  `test/application/E2eTestServiceTest.java` (new cases).
- [ ] T010 [US2] Process control: call the gate (`PROCESS_CONTROL`, WRITE) in
  `main/application/WriteGuard.java` before `checkAvailable`, so restart/kill are refused before
  the REST state read. Test first: `test/application/ProcessControlServiceTest.java` (new cases;
  no REST request other than the detection).
- [ ] T011 [US2] Deployment-backup restores: `ImportService.admitDeploymentRestore` calls the gate
  with `DEPLOYMENT` (WRITE) for the backup's node. Test first:
  `test/application/RestoreDeploymentTest.java` (new case: 9.0 target node refused).
- [ ] T012 [P] [US2] Read warnings (research D-6): add optional `warnings` (string array, only when
  non-empty) to `res/schemas/find_processes.output.json`, `query_logs.output.json`,
  `list_inventory.output.json`, `get_inventory_item.output.json` and their result records; call the
  gate (DIAGNOSIS / INVENTORY, READ) in `main/application/DiagnosisService.java` and
  `main/application/InventoryService.java` and pass its warnings into the results (per node in
  group results). Test first: `test/application/DiagnosisServiceTest.java`,
  `test/application/InventoryServiceTest.java` (9.0 node → warning; 8.1 → no field), schema tests
  in the tool tests.
- [ ] T013 [P] [US2] Workspace export and monitoring: the gate (WORKSPACE_EXPORT, READ) in
  `main/application/WorkspaceService.java` export (warnings into the existing `warnings`), and in
  `main/application/HealthService.java` the gate's warning replaces today's "unsupported … the 8.1
  adapters are used" text (monitoring is never refused). Test first:
  `test/application/WorkspaceExportTest.java` (new case), `test/application/HealthServiceTest.java`
  (adjusted 9.0.2 expectations).
- [ ] T014 [US2] Tool announcement (research D-12, FR-007): the conditions in
  `main/Wiring.java` (`anyDevelopmentNode`, `anyWriteEnabled`, `anyE2eNode`, deployment targets)
  ignore nodes pinned to a version whose matrix state for the capability is `UNSUPPORTED`; `AUTO`
  nodes count as capable. Test first: `test/DevelopmentWiringTest.java`,
  `test/mcp/ProcessControlToolsTest.java` (a profile whose only development / write nodes are
  `V9_X` announces none of those tools; one 8.1 node more announces them).

**Checkpoint**: US2 complete — the safety property holds for every write tool.

---

## Phase 4: User Story 1 - See versions and capabilities per node (Priority: P1)

**Goal**: `list_nodes` and `get_health` show server and client versions, status and usable
capabilities (FR-001, FR-002, FR-004, FR-011).

**Independent Test**: `list_nodes` before `get_health` shows `not_yet_detected` with zero requests;
after `get_health` it shows the detected version, the client version and `supported`.

- [ ] T015 [US1] `get_health` observation (FR-001): `HealthService` reports the version it reads
  for every node (pinned too) to `NodeVersions` as an observation; an observed major.minor
  different from a pin sets the contradiction and adds a warning. Test first:
  `test/application/HealthServiceTest.java` (pinned 8.1 node observing 9.0.7 → warning +
  contradiction; later 8.1.17 → cleared).
- [ ] T016 [US1] Client probe port (research D-7): `main/domain/port/ClientVersionPort.java`
  (`probe(NodeId, Duration)` → `ClientVersion`), implemented by
  `main/adapter/cli/CliVersionProbe.java` (returns the domain `ClientVersion`; replaces
  `mismatchWarning` by `ClientVersion.compare`); wired in `main/Wiring.java`. Test first:
  `test/adapter/cli/CliVersionProbeTest.java` (version with range, `version_no_range`, 9.0 variant,
  timeout → `TIMEOUT`, no installation → `CLI_UNAVAILABLE`).
- [ ] T017 [US1] Client probe in `get_health` (FR-002): `HealthService.checkServer` submits the
  probe as a fourth parallel future bounded by the node's health deadline; the report gains
  `client` (`version`, `supportedFrom`, `supportedTo`, `comparison`); a timeout or failure gives
  the new part `client` in `unavailable` (`not yet detected (timeout)` or the cause) and is
  recorded in `NodeVersions` (not cached, retry after 60 s). Add `client` to
  `main/domain/model/UnavailablePart.java` and `res/schemas/get_health.output.json` (part enum,
  report `client`). Test first: `test/application/HealthServiceTest.java`,
  `test/mcp/tools/GetHealthToolTest.java` (timing within `timeout` + 1 s with a probe that hangs),
  `test/performance/HealthOverviewPerformanceTest.java` stays within 10 s.
- [ ] T018 [US1] Client check outside `get_health` (FR-002, FR-011): `VersionGate` runs the probe
  before the first CLI-channel capability on a node when no client result is known (bounded by
  `cliTimeout`, not counted against the tool's own timeout); `INCOMPATIBLE` refuses with the client
  version and range; `NOT_EVALUABLE` (probe failed/timed out, no range, server unknown) and
  `MISMATCH` admit with a warning; a failed probe is retried at most once per 60 s. Test first:
  `test/application/VersionGateTest.java` (8.1.17 client + 8.1.18 server → warning; + 9.0.7 →
  refused; `V9_X` → refused; timeout → warning, retried after 60 s with a fake clock).
- [ ] T019 [US1] `list_nodes` (research D-9, contracts/mcp-tools-delta.md): `NodeSummary` gains the
  `version` block (data-model: `setting`, `server`, `client`, `status`, `capabilities`
  `usable`/`notUsable` with reasons, `warnings`; "usable" = `SUPPORTED`, or `UNVERIFIED` for reads
  and not refused by the client check); `versionLine` becomes the configured text (string).
  `ListNodesTool` takes a `Supplier<List<NodeSummary>>` combining the startup configuration
  summaries with `NodeVersions` at call time; it never contacts a node. Update
  `res/schemas/list_nodes.output.json` (`versionLine` string, `version` object,
  `additionalProperties: false` kept) and the tool description (next step `get_health` for
  `not_yet_detected`). Test first: `test/mcp/tools/ListNodesToolTest.java` (zero WireMock requests;
  `not_yet_detected`; after a detection `supported`; 9.0 node with an 8.1 client
  `partially_supported` with REST reads usable and CLI/write capabilities not usable; failed
  detection `unknown` with reason and time).

**Checkpoint**: US1 complete — the MVP (US2 + US1) is usable.

---

## Phase 5: User Story 4 - Pin the version per node (Priority: P3)

**Goal**: concrete and line pins work end to end, `--check-config` shows them and the client
(FR-011a, FR-012).

**Independent Test**: a node pinned to `"8.1.17"` triggers no detection request, `--check-config`
shows the client against the pin, and a contradicting observation refuses writes.

- [ ] T020 [US4] Pinned nodes in the gate: no detection request for `Line`/`Pinned` (FR-001);
  `V8_1`/a concrete pin give the matrix entry of their major.minor; `V9_X` gives major 9 (writes
  refused, reads unverified); the contradiction from T015 refuses writes. Test first:
  `test/application/VersionGateTest.java` (pinned `"8.1.17"`: zero detection requests; `V9_X` write
  refused; contradiction refused and cleared).
- [ ] T021 [US4] `--check-config` (research D-13, contracts/configuration-delta.md): `main/Launcher.java`
  creates a `CliRunner` for the check (no credentials, no server URL) and passes per-node client
  results to `main/config/ConfigSummary.java`; the node line gains `, inubit: <setting>` and, with a
  StartCLI installation, `, client: <version> (<from>–<to>)`, with a concrete pin also
  `, client vs pin: match|mismatch`; a failing probe is a warning. Replace the `V9_X` warning text
  in `main/config/ConfigValidator.java` with "versionLine V9_X: write tools are refused on this node
  (INUBIT 9.x is not supported for writes yet); read tools run unverified". No INUBIT contact.
  Test first: `test/config/ConfigSummaryTest.java`, `test/config/ConfigValidatorTest.java`,
  `test/MainTest.java` (V9_X text on stderr), `test/MainProcessTest.java` if it pins the text.

---

## Phase 6: `deploy_release` across versions (FR-014a; part of US2's safety property)

- [ ] T022 [US2] `main/application/DeployService.java` (research D-11): after `guard.admit` and
  before discovery, in preview and in execute, apply the gate's detection rule (WRITE) to every
  source and target node, then refuse the whole deployment through `DeployGuard.refuse` (audited)
  if any node is unknown, any target is not `SUPPORTED` for `DEPLOYMENT`, sources differ from each
  other or from any target in major.minor (`V8_1`/a concrete pin give major.minor; `V9_X` counts as
  unknown), naming every node and version. Test first:
  `test/application/DeployServiceVersionTest.java` (8.1.17 → 8.1.18 allowed; 8.1 → 9.0 refused;
  unknown source refused; unknown target refused; differing sources refused; version changed
  between preview and execute → refused at execute; nothing sent on refusal).

---

## Phase 7: User Story 3 - Extend the matrix (Priority: P2)

**Goal**: prove that a new version needs only an adapter and a matrix entry (SC-004).

- [ ] T023 [US3] `test/application/CapabilityExtensionTest.java`: with a test-only
  `CapabilityMatrix` declaring a fictitious range (e.g. 7.9) with a subset of capabilities and a
  test-only adapter selected for it through the gateway factory's extension point, a node of that
  version gets exactly the declared tools (others refused), an 8.1 node is unchanged, a 9.0 node
  behaves as in US2 — without touching any tool or service class. If an extension point is missing
  for this, add the smallest one in `main/adapter/AdapterGatewayFactory.java` (constructor
  parameter, no behaviour change for production).

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T024 [P] `docs/setup.md`: `versionLine` values incl. concrete pins and quoting, when the
  version is detected, the deliberate behaviour changes (writes refused after a failed detection
  and on `V9_X`), the client probe and `--check-config` line.
- [ ] T025 [P] `docs/tools.md`: the tool classification table; `UNSUPPORTED_VERSION` (now returned;
  replace "reserved … not returned by this version") with its variants; `list_nodes` `version`
  block; `get_health` `client` part and fields; `warnings` on the four read tools; write-tool and
  `deploy_release` refusals; tool announcement rule. Keep `DevelopmentToolsReferenceTest`,
  `ArtifactToolsReferenceTest` and `DeploymentReferenceTest` green.
- [ ] T026 [P] `README.md` "Safety model": writes only on verified INUBIT versions; `CHANGELOG.md`
  `[Unreleased]`: feature 009 with the deliberate behaviour changes.
- [ ] T027 Validate per quickstart.md: `mvn -q clean verify`; `mvn -q -Dtest=NoCustomerIdentifiersTest
  test` with the local lists (it must run, not skip); record the outcome in `quickstart.md`. The
  optional live check (read-only `get_health`/`list_nodes`) only with the person's approval.

---

## Dependencies & Execution Order

- T001 and T002–T004 can run in parallel; T005 needs T002; T006 needs T002–T005; T007 needs T006;
  T008 needs T003, T004, T006, T007.
- US2 (T009–T014) needs T008. T012 and T013 are parallel to each other and to T009–T011.
- US1 (T015–T019) needs T008; T016 before T017 and T018; T019 needs T015 and T017/T018.
- US4 (T020–T021) needs T015 (contradiction) and T016 (probe for `--check-config`).
- T022 needs T008 (and T020 for pins). US3 (T023) needs T008.
- Polish (T024–T027) last.

## Parallel Example

```text
Phase 2:  T002 InubitVersion  |  T003 Capability/Matrix  |  T004 ClientVersion   (then T005 → T006 → T007 → T008)
Phase 3:  T009 development writes  →  T010  →  T011     with   T012 read warnings  |  T013 export/health
Phase 8:  T024 setup.md  |  T025 tools.md  |  T026 README/CHANGELOG
```

## Implementation Strategy

- **MVP**: Phases 1–4 (foundation, US2 refusals, US1 visibility). After Phase 3 the safety
  property already holds; after Phase 4 the person can see why.
- Then US4 (pins and `--check-config`), the deployment check, US3 (extensibility proof) and the
  docs.
- Run each task through the implementer → reviewer loop (`/speckit-duo:implement`); every commit
  stays green because each task carries its own tests.
