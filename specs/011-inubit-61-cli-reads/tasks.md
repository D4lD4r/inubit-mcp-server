---

description: "Task list for read-only INUBIT 6.1 support via StartCLI only (feature 011)"
---

# Tasks: Read-Only INUBIT 6.1 Support via StartCLI Only

**Input**: Design documents from `/specs/011-inubit-61-cli-reads/`

**Prerequisites**:
- [plan.md](plan.md) and [spec.md](spec.md) (clarifications 2026-10-31, spec reviews r1–r3)
- [research.md](research.md) (D-1…D-18) and [data-model.md](data-model.md)
- [contracts/configuration-delta.md](contracts/configuration-delta.md) and
  [contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md)
- [quickstart.md](quickstart.md)
- constitution v1.1.0; the code of features 001–010 (branch based on `main`)

**Tests**: REQUIRED (Constitution III, TDD). Every task starts with the test(s) it names, observed
failing on an assertion before the production code exists, and ends with `mvn -q -o verify` green.
Each task therefore contains its own tests; there are no separate "write failing tests" tasks.
Tasks marked "verification task" cover behaviour earlier tasks built, so their tests may pass at
once.

**Organization**: by user story after a shared foundation. US3 (refusals, the safety property)
comes first because every later 6.1 path relies on the gate rules; US5 (connection safety) comes
before US1/US2 because both run StartCLI against the node. The recordings (Phase 8) are a manual,
approved step; US4 (export, P3) is blocked on them and on a research addendum.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on unfinished tasks)
- **[Story]**: US1 health, US2 processes, US3 refusals, US4 export, US5 connection safety

## Path Conventions

| Short form | Expands to |
|---|---|
| `main/…` | `src/main/java/de/dadecker/inubit/mcp/…` |
| `test/…` | `src/test/java/de/dadecker/inubit/mcp/…` |
| `res/…` | `src/main/resources/…` |
| `fixtures/…` | `src/test/resources/fixtures/…` |

## Safety rules

- **While developing**: run no INUBIT command and no network call against any INUBIT server; all
  tests are offline (WireMock, `FakeProcessLauncher`/`ScriptedProcessLauncher`, a local TLS test
  server from `TestCertificates`). The only live step is Phase 8, run by hand after the person's
  approval in the chat.
- **Fixtures**: reconstructed 6.1 cases are neutral (`legacy/node1`, owner `jdoe`, host
  `inubit-legacy-1.example.test`) and listed in `RECONSTRUCTED.txt`; recorded cases are
  neutralized with `tools/neutralize.py`. The identifier hooks must pass; never use `--no-verify`.
- **Vendor documentation**: stays in the git-excluded `vendor-docs/`; never copy its text into
  the repository beyond facts (option names, column names, formats).
- **Configuration**: never change `~/.config/inubit-mcp/*` or the MCP registration.
- **Public repository**: no customer names in commit messages, PR descriptions or files.

---

## Phase 1: Setup

- [X] T001 [P] Fixture scaffold (research D-15): create `fixtures/v6_1/cli/README.md` (case
  layout as `fixtures/v8_1/cli/README.md`, source "reconstructed from the vendor documentation of
  inubit 6.1 until recorded", neutral names) and `fixtures/v6_1/cli/RECONSTRUCTED.txt` (format
  `<case> <capability>` per line, `#` comments; later tasks only append lines). Test first:
  `test/adapter/cli/V61FixturesShapeTest` fails while the folder or file is missing and checks that
  every case listed has its three files.
- [X] T002 [P] Make `FakeProcessLauncher.replaying` take the fixture version (`v8_1` default,
  `v6_1`) instead of the hard-coded `/fixtures/v8_1/cli/` (`test/adapter/cli/FakeProcessLauncher.java:65`);
  same for `ScriptedProcessLauncher.replying` if it resolves cases. Test first: a
  `FakeProcessLauncherTest` case replaying a `v6_1` case.

## Phase 2: Foundational (blocks every user story)

- [X] T003 `V6_1` and concrete 6.1 pins (FR-002, research D-1): add `VersionSetting.Line.V6_1`;
  accept `"V6_1"` in `VersionSetting.parse` and `ConfigLoader.VersionSettingDeserializer`
  (`main/config/ConfigLoader.java:759-776`); error texts at `ConfigLoader.java:350-352` and
  `:690-691` name `V6_1`. Tests first: `VersionSettingTest` (`V6_1` → 6.1; `"6.1.0.89"` and
  `"6.1.0.89-HF1"` → 6.1.0 with raw kept; `"6.1"` refused), `ConfigLoaderTest` (error text),
  `NodeVersionStateTest` (`effectiveVersion()` of `V6_1` = 6.1).
- [X] T004 Split the capability "diagnosis" (FR-004, research D-2): replace `Capability.DIAGNOSIS`
  by `PROCESS_DIAGNOSIS` and `LOG_DIAGNOSIS` in `main/domain/model/Capability.java`; update
  `VersionGate.label()` ("process diagnosis", "log diagnosis"), `NodeOverview.name()`
  (`process_diagnosis`, `log_diagnosis`), `res/schemas/list_nodes.output.json` enum
  `CapabilityName`, `DiagnosisService` admissions (`find_processes` → process, `query_logs` → log),
  `Wiring.allowed()`, and every matrix entry (both with the former `DIAGNOSIS` state). Tests
  first: `CapabilityMatrixTest`, `VersionGateTest` (message texts), `ListNodesToolTest` and
  `NodeOverviewTest` (names), `DiagnosisServiceTest` (admitted capability per tool); adjust the
  tests that asserted `diagnosis` (deliberate change, SC-005).
- [X] T005 Matrix channels and `cliOnly` (research D-3): extend `CapabilityMatrix.Entry` with
  per-capability channels and a `cliOnly` flag; add `channels(version, capability)` and
  `isCliOnly(version)`; add the production entry 6.1 with `MONITORING`/`PROCESS_DIAGNOSIS` channel
  CLI, `cliOnly`, and every capability `UNSUPPORTED` (FR-005). Add `NodeAccess` (`REST_AND_CLI`,
  `CLI_ONLY`). Tests first: `CapabilityMatrixTest` (6.1 states and channels; 8.1/9.0 unchanged;
  unknown versions still `READS_UNVERIFIED`; entry with channels round-trips).
- [X] T006 Gate rules for CLI-only versions (FR-005, FR-006, FR-007, FR-013, research D-3, D-11):
  in `main/application/VersionGate.java` `judge` (`:201-226`), on a `cliOnly` entry refuse every
  capability that is not `SUPPORTED` with `UNSUPPORTED_VERSION` — including reads (no 8.1
  fallback, no warning path); take the channels for the client check from the matrix entry; on a
  contradicted 6.1 pin refuse every capability except `MONITORING`. Tests first in
  `VersionGateTest` with a test matrix (6.1 `SUPPORTED`) and the production matrix (6.1
  `UNSUPPORTED`): refusal before any detection, probe or launch; a client whose declared range
  excludes 6.1 refuses process diagnosis on a 6.1 node (FR-013, channel CLI from the entry); the
  refusal is logged (FR-019, assert the log record).
- [X] T007 Monitoring admission before contact (FR-001, research D-11): add
  `VersionGate.monitoringAccess(node)` → refusal | `CLI` for `cliOnly` entries, `REST` otherwise
  (the existing non-refusing `admitMonitoring` path stays for REST); `HealthService.checkServer`
  (`:176-225`) calls it before `gateways.monitoring()` and returns the node with the refusal as
  `error` when refused. Tests first: `HealthServiceTest` — 6.1 node with the production matrix:
  report with `UNSUPPORTED_VERSION`, WireMock 0 requests, no launch; 8.1 and `AUTO` nodes
  unchanged.

**Checkpoint**: 6.1 nodes are recognized and refused everywhere, `get_health` included, without
any contact; 8.1 output differs only in the capability names.

---

## Phase 3: User Story 3 - Unavailable tools on 6.1 are refused clearly (Priority: P1)

**Goal**: every tool, capability or input 6.1 cannot serve is refused per node before any
contact, with node, version, capability and next step.

**Independent Test**: call every tool on a pinned 6.1 node with the production matrix; confirm
`UNSUPPORTED_VERSION`, the next step, and no launch, handshake or REST request (SC-003).

- [X] T008 [US3] Refusal texts and next steps (FR-007, contracts/mcp-tools-delta): extend
  `VersionGate.refuse()`/`name()` so a 6.1 refusal reads `INUBIT 6.1 (versionLine V6_1) does not
  support <capability>; <next step>; nothing was sent`, with the next steps of the contract table
  (logs, inventory incl. "modules of INUBIT 6.1 cannot be looked up", export, writes,
  contradiction). An admission on a 6.1 node carries version notes (process diagnosis: "logs of
  INUBIT 6.1 cannot be read with query_logs; read them in the Workbench"), used by T033. Test
  first: `VersionGateTest` per capability and for the admission notes.
- [X] T009 [P] [US3] Per-tool refusal matrix (SC-003): `test/application/V61RefusalTest` calls
  every tool (`get_health` and `find_processes` with the production matrix, `query_logs`,
  `list_inventory`, `get_inventory_item`, `export_artifacts`, `restart_process`, `kill_process`,
  `import_artifacts`, `restore_backup`, `set_active`, `tag_artifacts`, `deploy_release`,
  `run_e2e_test`) on a 6.1 node that has write, development, deploy and e2e settings, with a
  launcher and WireMock that fail on any use; a mixed group 8.1 + 6.1 for `query_logs` returns
  per-node results (US3-3). Fix any path that is not refused before contact. (Verification task
  where T006/T007 already cover the path.)
- [X] T010 [P] [US3] `check_artifacts` on 6.1 (FR-006a): the module lookup refusal for a 6.1 node
  gives `MODULE_UNVERIFIED` findings carrying the gate's refusal text (from T008; no version text
  in `ArtifactCheckService`, FR-016); offline checks still run; also while the 6.1 pin is
  contradicted. Test first: `ArtifactCheckServiceTest`.
- [X] T011 [P] [US3] `AUTO` 404 hint (FR-003, research D-11): `V81VersionDetector` reports a
  detection answered with HTTP 404 as `endpointMissing`; `NodeVersions`/`NodeVersionState` keep the
  flag; `VersionGate.unknown()` (`:405`) appends `; if this node runs INUBIT 6.1, pin V6_1` only
  then. Tests first: `V81VersionDetectorTest` (404 vs. 401 vs. 500), `VersionGateTest` (hint only
  for 404), `NodeVersionsTest`.
- [X] T012 [P] [US3] Tool announcement for read tools (research D-12): in `main/Wiring.java`
  `toolHandlers()` (`:431-466`) announce `find_processes`, `query_logs`, `list_inventory`,
  `get_inventory_item` and `export_artifacts` only if some node is `possiblyCapable` for their
  capability (`:585-593`); `list_nodes`, `get_health`, `check_artifacts` stay always announced.
  `Wiring` takes the capability matrix as an injectable dependency instead of the static
  `MATRIX` (`:138`). Tests first: `ToolAnnouncementTest` — profile of 6.1 nodes only before and after the flip (test
  matrix), mixed profile and `AUTO` profile (announced as today).
- [X] T013 [US3] `list_nodes` for 6.1 (US3-5, research D-13): `NodeOverview.version()` adds
  `access` (`cli_only`/`rest_and_cli`) and lists usable/not usable capabilities of the 6.1 entry;
  `versionLine` shows `V6_1`; schema `res/schemas/list_nodes.output.json` gets `access`. Test
  first: `ListNodesToolTest` (6.1 node, 8.1 node, no contact).
- [X] T014 [US3] `--check-config` 6.1 line (FR-014, research D-13,
  contracts/configuration-delta): `ConfigSummary` prints `inubit: V6_1 (INUBIT 6.1, StartCLI only;
  available: …; refused: …)`; `ConfigValidator` adds a warning for a 6.1 node without `cliHome`
  ("INUBIT 6.1 is reached through StartCLI only, but no cliHome is configured; its capabilities
  report CLI_UNAVAILABLE"); a configured `cliHome` without `bin/startcli.sh` keeps the existing
  warning; 8.1 nodes without `cliHome` get no finding; 8.1 lines unchanged apart from the
  capability names. Tests first: `ConfigValidatorTest` (both 6.1 cases, 8.1 without CLI),
  `ConfigSummaryTest`, `LauncherTest` (no INUBIT contact).

**Checkpoint**: US3 is complete and safe on its own; no 6.1 capability is usable yet.

---

## Phase 4: User Story 5 - Connections to a 6.1 node are as safe as to an 8.1 node (Priority: P1)

**Goal**: StartCLI against a 6.1 node only after the server verified the certificate; the
password only on stdin; the client verifies too, without a trust-store password; at most two
concurrent processes. Nothing of this phase changes 8.1/9.x nodes (research D-8, FR-018).

**Independent Test**: with fake launchers and a local TLS test server, confirm the pin check, the
password channel, the client options, the configuration warnings and the process limit.

- [X] T015 [P] [US5] `TlsHandshakeCheck` (FR-012, research D-7): `main/adapter/rest/TlsHandshakeCheck.java`
  — handshake only to host:port with `SslContexts.forServer`, SNI, endpoint identification `HTTPS`
  unless `disableHostnameVerification` with a pin, node `timeout` as connect timeout; result ok or
  `TLS_ERROR` with cause (pin mismatch, untrusted, host name; unreachable → `UNREACHABLE`). Tests
  first: `TlsHandshakeCheckTest` against `TestCertificates.httpsWireMock` — trusted, pin match,
  pin mismatch, untrusted, wrong host name, host name skipped with pin; WireMock records 0 HTTP
  requests.
- [X] T016 [P] [US5] `CliSessionCommand` and `CliRunner.runSession` (FR-011, research D-5):
  allowlisted commands (`uname`, `uptime`, `maintenance`, `free -m`, `ps` with the options of
  D-10, `exit`); `runSession` builds `[startcli.sh, -u, <user>, <cli.url>]` (trust arguments are
  added in T018), writes the password line, the commands and `exit` to stdin, and
  shares environment allowlist, timeout, output cap, credential guard and classification with
  today's run (`main/adapter/cli/CliRunner.java:86-204`). Tests first: `CliRunnerTest` — argv
  contains no password and no command text, stdin order, environment contains no password, login
  failure → `AUTH_FAILED` and guard `rejected()`, timeout → `TIMEOUT`, output cap; the 8.1
  `--execCommand` argument array is unchanged (regression test); `CliSessionCommandTest` (no free
  text accepted).
- [X] T017 [P] [US5] `CliClientProbe` with client options for 6.1 nodes (FR-012a, research D-8):
  a new class `main/adapter/cli/CliClientProbe.java` beside `CliVersionProbe` (which stays the `-v`
  probe behind the unchanged `ClientVersionPort`); for `cliOnly` nodes only, a `-h` run and the Java version from
  `<runtime>/release` (`cli.javaHome`, else the parent's `JAVA_HOME`); result `CliClientOptions`;
  unreadable output = options absent (fail closed); kept in an adapter-side per-node cache inside
  `CliClientProbe` with `optionsFor(node, timeout)` that returns the cached options or runs `-h`
  synchronously (a failed run is not cached and retried at most once per 60 s); `Launcher` uses
  `optionsFor` for `--check-config` (T021). 8.1 nodes keep exactly one `-v` run. Fixture: `fixtures/v6_1/cli/help` (reconstructed; append to
  `RECONSTRUCTED.txt`). Tests first: `CliClientProbeTest` (6.1 options, unreadable help, 8.1 node:
  no `-h` launch).
- [X] T018 [US5] Client trust arguments (FR-012a, research D-8): `CliRunner.runSession` takes the
  `CliClientOptions` and passes the trust store by `--trustStoreFilePath` when the given options have it, otherwise
  `-Djavax.net.ssl.trustStore=<path> -Djavax.net.ssl.trustStoreType=<type>` appended to
  `JAVA_TOOL_OPTIONS`; `--disableHostNameVerification` only when the options have it and the node
  has `disableHostnameVerification` with a pin. `CliRunner` makes no refusal (owner: T020). Tests
  first: `CliRunnerTest` for each argument/environment case, never a trust-store password,
  whitespace in a path never reaches `JAVA_TOOL_OPTIONS` (T020 refuses it first); the
  existing trust-store-password configuration error stays (`ConfigValidatorTest`, US5-5).
- [X] T019 [P] [US5] `CliConcurrencyLimiter` (FR-015, research D-9): per-node `Semaphore(2)` for
  `cliOnly` nodes, `tryAcquire(cliTimeout)` else `TIMEOUT`; the local probes take no permit.
  Tests first: `CliConcurrencyLimiterTest` — five parallel acquisitions with held permits: at most
  two succeed, the rest `TIMEOUT` after the bound; 8.1 nodes get no limiter.
- [X] T020 [US5] `V61Connection` (FR-012, FR-012a, research D-7/D-8): in `main/adapter/cli/v61`,
  per tool call and node: `CliRunner.checkAvailable` (missing StartCLI → `CLI_UNAVAILABLE`, no
  contact) → client options from `CliClientProbe.optionsFor` (synchronously, cached or `-h` run
  locally) → option and format refusals (missing host-name option with
  `disableHostnameVerification`; PKCS12 with a runtime below Java 8 or an unreadable runtime
  version; a trust-store path with whitespace when the client has no trust-store option; all
  `TLS_ERROR` with their next steps) → limiter permit → `TlsHandshakeCheck` against the effective StartCLI URL (only for `https://`) →
  `runSession`. `http://` follows `allowInsecureHttp` on the effective StartCLI URL. Tests first:
  `V61ConnectionTest` — order of the steps; pin mismatch: `TLS_ERROR`, no launch; overridden
  StartCLI URL: the check targets its host and port (US5-6); `http://` with `allowInsecureHttp`:
  no handshake; five parallel calls with a hanging session: at most two launches (US5-10); first
  call with options not yet cached, `disableHostnameVerification` + pin and a client that has the
  option → admitted and `--disableHostNameVerification` passed; `cli.javaHome` absent with an
  unreadable `JAVA_HOME` runtime and a PKCS12 trust store → refused; path with spaces → refused.
- [X] T021 [US5] `--check-config` warnings (FR-012a, US5-8, contracts/configuration-delta):
  missing host-name option or options unknown, unreadable trust-store format or runtime version,
  trust-store path with whitespace; client options in
  the client part, for 6.1 nodes only; a 6.1 node with an `http://` StartCLI URL with and without
  `allowInsecureHttp` (existing rule). Tests first: `ConfigValidatorTest`, `ConfigSummaryTest`,
  `LauncherTest` (local probes only; 8.1 node lines unchanged).

**Checkpoint**: a 6.1 node can be reached safely; no 6.1 capability is usable in production yet.

---

## Phase 5: User Story 1 - Check health and version of a 6.1 node (Priority: P1)

**Goal**: `get_health` answers for a 6.1 node from one StartCLI session.

**Independent Test**: with reconstructed session fixtures and a test matrix (6.1 monitoring
`SUPPORTED`), call `get_health`; confirm fields, `NOT_AVAILABLE_ON_6_1` parts, budgets and no REST
request.

- [X] T022 [P] [US1] Reconstructed health fixtures (research D-5, D-15):
  `fixtures/v6_1/cli/health_session_ok`, `health_session_maintenance`, `health_session_de`
  (German labels), `health_session_alive_failed`, `login_failed`, `unreachable`; append them to
  `RECONSTRUCTED.txt` as `monitoring`. Covered by `V61FixturesShapeTest`.
- [X] T023 [P] [US1] Report model (FR-008, data-model): `ProcessCounts`, `MemoryFigures`,
  `CliHealthSnapshot`; `HealthReport.processCounts`; optional thread/queue fields in
  `LoadFigures`; `UnavailablePart` parts `threads`, `blockingQueue` and reason
  `NOT_AVAILABLE_ON_6_1`; schema `res/schemas/get_health.output.json`. Tests first:
  `HealthReportTest`, `UnavailablePartTest`, schema test; 8.1 reports serialize as before.
- [X] T024 [US1] `V61SessionParser` and `V61HealthParser` (FR-017, research D-5/D-6): split the
  session output at the prompt into command blocks; parse `uname`, `uptime`, `maintenance`,
  `free -m` and the counts; English and German; unknown output → `UNEXPECTED_RESPONSE` (never
  empty). Tests first: `V61HealthParserTest` over the T022 fixtures and malformed variants.
- [X] T025 [US1] Selection plumbing (research D-4): `CliHealthPort` in `main/domain/port`;
  `PortSetRegistry.register(range, factory, cliHealthFactory)`; `Slot.cliHealth()` and
  `GatewayFactory.cliHealth/access` in `AdapterGatewayFactory`; `Slot.ports()` skips
  `ensureClient()` for a factory that needs no HTTP client; `RefusingPorts`; `V61PortSet`
  registered for 6.1 in `Wiring` (process query added in T032). Tests first: `PortSetRegistryTest`
  (6.1 selected for `V6_1` and `"6.1.0"`), `AdapterGatewayFactoryTest` (no REST client built for a
  6.1 node), `RefusingPortsTest`.
- [X] T026 [US1] `V61HealthAdapter` (research D-6): one session through `V61Connection` (T020)
  with the health commands; maps to `CliHealthSnapshot` (alive, version text and whether it is the
  server's, maintenance, memory, counts, missing parts). Tests first: `V61HealthAdapterTest` over
  the T022 fixtures (exactly one launch per snapshot).
- [X] T027 [US1] `HealthService` CLI path and budgets (FR-008, FR-009, research D-6): for the `CLI`
  access of T007, `checkServerViaCli` runs the snapshot (whose connection obtains the client
  options first, T020) in parallel with the `-v` client probe of feature 009;
  `status` rule; maintenance warning; version observed only when marked as server version
  (FR-006); budgets: 15 s for a single CLI-only node, `10 s − 0.9 s` per CLI-only node in an
  overview, unfinished parts `TIMEOUT`. Tests first: `HealthServiceTest` (fields, status
  `OK`/`ERROR`/`UNKNOWN`, `CLI_UNAVAILABLE`, credential guard US1-6, contradiction US1-7 and its
  clearing — a later `get_health` observing 6.1 again admits `find_processes` (FR-006) —, WireMock
  0 requests, at most three StartCLI launches per call besides the probe — FR-009),
  `HealthOverviewPerformanceTest` with a 6.1 node (≤ 10 s, hanging 6.1 session → `TIMEOUT`), new
  `V61HealthTimingTest` (≤ 15 s).
- [X] T028 [US1] `get_health` end to end (US1 scenarios): `GetHealthToolTest` with a 6.1 node and
  a mixed group (verification task); output matches contracts/mcp-tools-delta.

**Checkpoint**: US1 works with the test matrix; production still refuses until Phase 8.

---

## Phase 6: User Story 2 - Find failed and hanging processes on a 6.1 node (Priority: P1)

**Goal**: `find_processes` answers for a 6.1 node from `ps` listings.

**Independent Test**: with reconstructed `ps` fixtures and a test matrix (6.1 process diagnosis
`SUPPORTED`), call `find_processes` with states, hanging, workflow, time range and paging.

- [ ] T029 [P] [US2] Reconstructed `ps` fixtures (research D-10, D-15): `fixtures/v6_1/cli/`
  `ps_session_all`, `ps_session_error`, `ps_session_waiting_queued`, `ps_session_empty`
  (`Total: 0`), `ps_session_de`, `ps_session_unassigned` (`__NotAssigned__`, missing module); a
  10,000-row listing is generated by the test from a template; append the cases to
  `RECONSTRUCTED.txt` as `process_diagnosis`. Covered by `V61FixturesShapeTest`.
- [ ] T030 [P] [US2] `CliDates` (research D-10): move the zone-less date parsing of
  `main/adapter/cli/v81/InubitDates.java` to `main/adapter/cli/CliDates.java` (Europe/Berlin, DST
  rule unchanged) and add the ISO local form; `v81` uses it. Tests first: `CliDatesTest` (both
  forms, DST overlap/gap); existing `InubitDates` tests move.
- [ ] T031 [US2] `V61PsParser` (FR-010, FR-017): columns by header positions (English and German),
  module text kept, `Total:` line, German state names mapped before `ProcessState.fromRaw`, other
  raw states → `OTHER`, `__NotAssigned__` → no `inubitNode`; unknown header or line →
  `UNEXPECTED_RESPONSE`. Tests first: `V61PsParserTest` over the T029 fixtures and malformed
  variants.
- [ ] T032 [US2] `V61ProcessQueryAdapter` (FR-010, research D-10): listings per state rule
  (no state or `ACTIVE` → `ps -a`, else one per state, in one session through `V61Connection`,
  each `-l 10000`); filters (workflow, time range, hanging as `since ≤ now − threshold` for
  non-final states); newest first; paging (`offset + limit ≤ 10,000`); `truncated` and
  `totalIsLowerBound` when any listing hit the cap; `validate` and `find` both refuse `tag` with
  `UNSUPPORTED_VERSION`; `findByProcessId` refuses. Added to `V61PortSet`. Tests first:
  `V61ProcessQueryAdapterTest` (US2-1…US2-7; exactly one launch per call).
- [ ] T033 [US2] `DiagnosisService` for 6.1 (FR-007, FR-010, FR-015, research D-10): an
  `UNSUPPORTED_VERSION` from validation is kept and returned as that node's result entry, also for
  nodes whose credentials are held (`validateHeld`/`validate`, `:262-286`); the 6.1 result entry
  carries the warning "logs of INUBIT 6.1 cannot be read with query_logs; read them in the
  Workbench", taken from the gate admission's version notes (`VersionGate`, FR-016); the per-node
  deadline (`:295-298`) follows the process-diagnosis channel from the gate — for CLI: `cliTimeout`
  (options probe on a first call) + `cliTimeout` (limiter) + `timeout` (handshake) + `cliTimeout`
  (session) + grace (+ AWS allowance). Tests
  first: a 6.1 session longer than `3 × timeout` but shorter than `cliTimeout` is answered, not cut
  off; `DiagnosisServiceTest` and `FindProcessesToolTest` — held-credentials
  6.1 node with `tag` in a mixed 8.1 + 6.1 group: 6.1 entry refused, 8.1 entry results, no launch;
  WireMock 0 requests for the 6.1 node; timing ≤ 15 s for 100 instances (SC-002).

**Checkpoint**: US2 works with the test matrix.

---

## Phase 7: Guard for recorded fixtures

- [ ] T034 `V61RecordedFixturesTest` (FR-005, research D-15): for every capability that
  `CapabilityMatrix.production()` declares `SUPPORTED` on 6.1, no case of that capability is
  listed in `fixtures/v6_1/cli/RECONSTRUCTED.txt`. Test first: the test itself, plus a case with a
  test-double matrix that flips a capability while its cases are still listed (must fail).

---

## Phase 8: Recordings (manual, approved, read-only; FR-020)

**Blocked on**: the person's approval in the chat and a reachable 6.1 node; for a node classified
production also the person's confirmation of node and classification. If the recordings show
that the interactive mode cannot be driven through stdin (research D-5), stop and update plan and
tasks with new tasks for the direct-option alternative before T037.

- [ ] T035 Catalogue cases (research D-16): add `v61_help`, `v61_version`, `v61_health_session`,
  `v61_ps_all`, `v61_ps_error`, `v61_ps_waiting`, `v61_ps_queued`, `v61_ps_limit5` to
  `tools/record-fixtures.sh` — StartCLI only, no curl/REST step, session commands from the
  allowlist, password via stdin, allowlisted environment. Test first: new
  `test/tools/RecordFixturesCatalogueTest` reads the script and asserts for every `v61_*` case: no
  `curl`, no `-p`, the password only via stdin, the group allowlist check applies.
- [ ] T036 Record and neutralize (live, by hand, after approval): run the cases of T035 per
  quickstart "Live recordings"; neutralize with `tools/neutralize.py`; run
  `NoCustomerIdentifiersTest` (must run, not be skipped); write the 6.1 section of
  `docs/research/inubit-interfaces.md` answering the REC questions of research D-5, D-6, D-10.
- [ ] T037 [US1] Recorded health fixtures: replace the reconstructed health cases (and `help`),
  remove them from `RECONSTRUCTED.txt`, adjust `V61SessionParser`/`V61HealthParser`/
  `CliClientProbe` to the recorded formats; set whether `uname` reports the server version
  (research D-6). Tests first: the recorded cases in `V61HealthParserTest`, `CliClientProbeTest`.
- [ ] T038 [US2] Recorded `ps` fixtures: replace the reconstructed `ps` cases, remove them from
  `RECONSTRUCTED.txt`, adjust `V61PsParser` to the recorded formats and document the rows dropped
  at `-l` from `v61_ps_limit5`. Tests first: the recorded cases in `V61PsParserTest`,
  `V61ProcessQueryAdapterTest`.
- [ ] T039 [US2] Conditional REC outcomes (research D-10; skip the parts the recordings do not
  require): if `DATE` is not the state-entry time — make `ProcessInstance.since`/`timeInState`
  optional (schema `find_processes.output.json`), refuse `since`, `until`, `hangingOnly`,
  `hangingThresholdMinutes` with `UNSUPPORTED_VERSION`, keep the listing order with the note "not
  sorted by time", and make the sort in `DiagnosisService.findProcesses` (`:204`) tolerate a
  missing `since`; if `ps -a` lists no Processing rows — refuse `ACTIVE`. Tests first:
  `V61ProcessQueryAdapterTest`, `DiagnosisServiceTest`, schema test.
- [ ] T040 Flip the 6.1 capabilities: set `MONITORING` and `PROCESS_DIAGNOSIS` to `SUPPORTED` for
  6.1 in `CapabilityMatrix.production()`; `V61RecordedFixturesTest` green; the refusal tests of
  T009 that used the production matrix for `get_health`/`find_processes` switch to "admitted".
  Tests first: `CapabilityMatrixTest`, `V61RecordedFixturesTest`.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [ ] T041 [P] Docs (research D-18): `docs/setup.md` (versionLine `V6_1`, 6.1 node example,
  client options and trust store, TLS pre-check, check-config line), `docs/tools.md` (version
  table with a 6.1 column, capability names, warnings and refusal texts, `list_nodes` `access`,
  `get_health` 6.1 example, `find_processes` 6.1 notes, announcement, `check_artifacts`),
  `README.md` (supported versions: 8.1; 6.1 read-only via StartCLI). Documentation-only; the docs
  reference tests (`DevelopmentToolsReferenceTest`, `DeploymentReferenceTest`) stay green.
- [ ] T042 [P] `CHANGELOG.md` `[Unreleased]`: feature 011 with the deliberate changes of SC-005
  (capability names in `list_nodes`/`--check-config`; the 404 hint; read tools not announced when
  no node can run them).
- [ ] T043 Quickstart validation: run the offline table of quickstart.md, record the results in
  its "Validation record"; if the person approves, the optional live smoke check. Full
  `mvn -q -o clean verify` green, `NoCustomerIdentifiersTest` run (20 tests, 0 skipped).

**Checkpoint**: the feature is complete (FR-023); US4 may follow.

---

## Phase 10: User Story 4 - Export workflows and repository files of a 6.1 node (Priority: P3)

**Blocked on**: sample exports (FR-020) and the research addendum of D-17. Until done, workspace
export stays `UNSUPPORTED` on 6.1 (FR-023). If no samples can be obtained, these tasks move to a
later feature.

- [ ] T044 [US4] Record `v61_export_group` and `v61_export_repository` (catalogue cases added to
  `tools/record-fixtures.sh` and `RecordFixturesCatalogueTest` like T035; live, by hand, after
  approval), neutralize, and write the research addendum comparing the 6.1 archive with
  `adapter/archive/v81` (decision: reuse with a 6.1 profile or a `adapter/archive/v61` reader).
- [ ] T045 [US4] Export on 6.1 (FR-021, FR-022): implement per the addendum — workflows by diagram
  group, repository files by path, module-only requests refused with `UNSUPPORTED_VERSION`,
  unknown archive parts fail closed (nothing written). Tests first: export tests over the recorded
  archives, secret-leak tests of features 003/004 extended to them (SC-007); then flip
  `WORKSPACE_EXPORT` to `SUPPORTED` for 6.1 and update `RECONSTRUCTED.txt`/`V61RecordedFixturesTest`,
  docs and CHANGELOG.

---

## Dependencies & Execution Order

- **Phase 1** (T001, T002): no dependencies; parallel.
- **Phase 2** (T003 → T004 → T005 → T006 → T007): sequential (shared domain types, gate, health
  admission).
- **Phase 3 US3** (T008–T014): after Phase 2; T009–T012 parallel after T008; T013, T014 after T008.
- **Phase 4 US5** (T015–T021): after Phase 2; T015, T016, T017, T019 parallel (T016 builds no
  trust arguments, so it does not need `CliClientOptions`); T018 after T016 and T017; T021 also
  after T014 and T025 also after T012 (shared files); T020 after T015, T018, T019; T021 after T017, T020.
- **Phase 5 US1** (T022–T028): after T020; T022 and T023 parallel; T024 after T022; T025 after
  T023; T026 after T024, T025; T027 after T026; T028 last.
- **Phase 6 US2** (T029–T033): after T020 and T025; T029 and T030 parallel; T031 after T029, T030;
  T032 after T031; T033 after T032. US1 (from T026) and US2 can run in parallel.
- **`RECONSTRUCTED.txt`**: T017, T022 and T029 only append lines; when run in parallel, merge the
  appended lines.
- **Phase 7** (T034): after T005; best after T028 and T033.
- **Phase 8** (T035–T040): T035 any time after T016; T036 blocked on approval; T037 after T036,
  T028; T038 after T036, T033; T039 after T038; T040 after T034, T037, T038, T039.
- **Phase 9** (T041–T043): after T040 (T041, T042 can start earlier and be finished then).
- **Phase 10 US4** (T044, T045): after Phase 9; blocked on sample exports.

### Parallel examples

- After T008: `T009`, `T010`, `T011`, `T012` (US3); after Phase 2 also `T015`, `T016`, `T017`,
  `T019` (US5).
- After T020 and T025: US1 (`T026`…) and US2 (`T029`, `T030`) in parallel.

## Implementation Strategy

1. **MVP = Phases 1–4**: 6.1 nodes are recognized, refused everywhere without contact
   (`get_health` included) and reachable safely; no behaviour change for 8.1 beyond the
   capability names.
2. **Increment = Phases 5–7**: health and process listing for 6.1, proven with reconstructed
   fixtures and a test matrix; production still refuses.
3. **Activation = Phase 8**: recordings (approved), fixtures replaced, REC outcomes applied,
   capabilities flipped.
4. **Release = Phase 9**: docs, CHANGELOG, validation.
5. **Later = Phase 10**: export, only with sample exports.
