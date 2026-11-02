# Research: Read-Only INUBIT 6.1 Support via StartCLI Only

Phase 0 output of `/speckit-plan` for [spec.md](spec.md). Facts about INUBIT 6.1 come from the
vendor documentation of inubit 6.1 (online help and manual, December 2016; kept locally outside
the repository, never committed). Facts about this code base were verified in the source on
2026-10-31 (paths below). Items marked **REC** are decided by the read-only recordings of FR-020
(see D-16); each has a default that the offline work is built on and a defined alternative.

---

## D-1 Identifying a 6.1 node

- **Decision**: add the line value `V6_1` to `VersionSetting` (`Line(6, OptionalInt.of(1))`, next
  to `V8_1`/`V9_X`), accepted by `VersionSetting.parse`, the `VersionSettingDeserializer`
  (`config/ConfigLoader.java:759-776`) and the error texts (`ConfigLoader.java:350-352`,
  `:690-691`: "expected AUTO, V6_1, V8_1, V9_X or a full version such as \"8.1.17\""). Concrete
  pins need no parser change: `InubitVersion`'s regex already reads `"6.1.0.89"` and
  `"6.1.0.89-HF1"` as 6.1.0 and keeps the full text in `raw` (`domain/model/InubitVersion.java:23`);
  tests pin this behaviour (FR-002).
- **Rationale**: the clarified rule is "pin only" (FR-002); a line value mirrors `V8_1`.
  `NodeVersionState.effectiveVersion()` already turns a `Line` with minor into `major.minor`.
- **Alternatives**: a separate `inubitGeneration` key — rejected, the version setting is the one
  place that selects adapters (Constitution V).

## D-2 Splitting the capability "diagnosis"

- **Decision**: replace `Capability.DIAGNOSIS` by `PROCESS_DIAGNOSIS` and `LOG_DIAGNOSIS` (both
  `READ`, default channel `REST`). Labels (`VersionGate.label()`, `:423-434`): "process diagnosis",
  "log diagnosis". `list_nodes` names (`NodeOverview.name()`, `:214-225`, schema enum
  `CapabilityName` in `list_nodes.output.json:166-168`): `process_diagnosis`, `log_diagnosis`.
  `DiagnosisService` admits `PROCESS_DIAGNOSIS` for `find_processes` and `LOG_DIAGNOSIS` for
  `query_logs` (`:252-255`); `Wiring.allowed()` (`:550-570`) lists both. Every existing matrix
  entry declares both with the old state of `DIAGNOSIS`.
- **Rationale**: FR-004; a per-tool exception inside one capability would hide the difference
  from `list_nodes`.
- **Alternatives**: keep `diagnosis` on 8.1 — rejected by the user (clarification round 1).

## D-3 The 6.1 matrix entry, CLI-only channels, no 8.1 fallback

- **Decision**:
  - `CapabilityMatrix.Entry` gains two optional parts: per-capability **channels** that override
    the capability's default channels, and a **`cliOnly`** flag.
  - Production gets `Entry(6.1–6.1, states, channels {MONITORING: CLI, PROCESS_DIAGNOSIS: CLI},
    cliOnly = true)`. Until the recordings of a capability are in the repository (FR-005, D-15),
    its state in `production()` is `UNSUPPORTED`; the task that replaces the reconstructed
    fixtures flips it to `SUPPORTED`. Tests of the offline work use a test matrix with both
    `SUPPORTED` (the pattern of `CapabilityExtensionTest`).
  - `VersionGate.judge` (`:201-226`): on a `cliOnly` entry every capability that is not
    `SUPPORTED` is refused with `UNSUPPORTED_VERSION` — including `MONITORING` (today an
    unsupported monitoring only warns and runs the 8.1 REST adapter) and reads (today `UNVERIFIED`
    reads run with a warning). The channels used for the client check (`:219-226`) come from the
    entry, so `get_health` and `find_processes` on 6.1 run the StartCLI client check.
- **Rationale**: without a 6.1 entry a pinned 6.1 node falls into "unknown/other" =
  `READS_UNVERIFIED` and would run 8.1 REST reads (violates FR-001). Declaring the channels in the
  domain matrix keeps the gate free of version strings (FR-016).
- **Alternatives**: a separate "CLI-only node" flag in the configuration — rejected (D-1).

## D-4 Selecting the 6.1 adapters

- **Decision**: register a 6.1 port set in `PortSetRegistry` (`adapter/PortSetRegistry.java:67`,
  `register(VersionRange, Factory)`, already used by `CapabilityExtensionTest:180`):
  `V61PortSet` in a new package `adapter/cli/v61` with `V61ProcessQueryAdapter` (implements
  `ProcessQueryPort`); every other port of the set (`logs`, `inventory`, `processControl`,
  `artifacts`, `imports`, `tags`) is a `RefusingPorts` stub that throws `UNSUPPORTED_VERSION`
  (never reached, because the gate refuses first; the stub is the second line of defence).
  Monitoring is not part of `PortSet` (`adapter/PortSet.java`) and `Slot.monitoring()`
  (`AdapterGatewayFactory.java:315`) always builds the 8.1 REST adapter; a new port
  `CliHealthPort` (D-6) is selected next to it through the registry
  (`register(range, factory, cliHealthFactory)`), and `Slot.cliHealth()` returns it for a node
  whose effective version selects it.
- **Rationale**: the existing hook selects by `effectiveVersion` (the pin for pinned nodes).
  Today `Slot.ports()` (`AdapterGatewayFactory.java:331`) calls `ensureClient()` unconditionally;
  for a registry entry whose factory needs no HTTP client (the 6.1 factory) `ports()` skips it, so
  no REST client is built for a 6.1 node (it would open no connection anyway, but the 6.1 path must
  not depend on it).
- **Alternatives**: a `V61Gateway` beside `V81Gateway` — larger change to `GatewayFactory` for no
  gain; `Gateway.AdapterLine` stays `V8_1`.

## D-5 One StartCLI session per call (REC)

- **Decision (default)**: the 6.1 adapters run StartCLI in **interactive mode**, one process per
  tool call and node: arguments `[startcli.sh, -u, <user>, (client trust options, D-8), <cli.url>]`,
  standard input: the password line, then the commands, then `exit`. `CliRunner` gets
  `runSession(server, user, password, List<CliSessionCommand>, timeout, guard)` beside today's
  `--execCommand` run (`CliRunner.java:86-133`); environment allowlist, output cap, timeout,
  credential guard, login-failure and network/TLS classification are shared
  (`CliOutputClassifier`). A `V61SessionParser` splits the output into one block per command at
  the CLI prompt.
  - Health session commands: `uname` (server version), `maintenance` (status), `free -m`
    (memory), `ps -c -e`, `ps -c -w`, `ps -c -q` (counts) and the Processing count; the alive
    check is the successful login plus `uptime`.
  - Process session: one `ps` command per listing (D-10), each with `-l 10000`.
- **Alternative (if the recordings show that the interactive mode cannot be driven through
  standard input)**: this changes the design of D-5/D-6/D-10 and requires a plan and tasks update
  with new tasks (not absorbed into the recording tasks). The alternative must also keep the
  password on standard input (the documented password prompt; never `-p`, FR-011); if a direct
  option run cannot read it from stdin, US1/US2 are blocked. Direct options in at most three runs per `get_health` (`-c`; `-sv -pctmem`;
  `-ne -nx -nw -nq` with `-maintenance`), and one run per listing for `find_processes` (at most
  four, and with FR-015 at most two at once). Both satisfy FR-009's "at most three processes" for
  health; the alternative needs the per-node limit of D-9 for listings.
- **Rationale**: 6.1 has no `--execCommand` (not documented); one process saves two to three JVM
  starts (about 2–3 s each) and one login per call, which keeps SC-001/SC-002 and the credential
  guard simple. The 6.1 documentation describes the interactive mode with the password prompt
  ("If you do not specify a password, you are prompted").

## D-6 Health on 6.1

- **Decision**:
  - New port `CliHealthPort.snapshot(Duration budget) → CliHealthSnapshot` (alive, server version
    text, maintenance, memory used/free/max, counts per state) implemented by `V61HealthAdapter`
    with one session (D-5).
  - `HealthService.checkServer` (`:157-236`) asks the gate for the monitoring channel of the node
    (from the matrix entry, D-3) and, for CLI, runs `checkServerViaCli`: TLS pre-check (D-7), then
    the snapshot in parallel with the client probe of feature 009, assembled into the existing
    `HealthReport`.
  - Report changes (additive): `HealthReport` gains optional `processCounts`
    (`error`, `processing`, `waiting`, `queued`); `LoadFigures`' thread and queue fields become
    optional (absent on 6.1); `UnavailablePart` (`domain/model/UnavailablePart.java:33`, schema
    `get_health.output.json:36-37`) gains the parts `threads` and `blockingQueue`; reason code
    `NOT_AVAILABLE_ON_6_1` for `ready`, `threads`, `blockingQueue`.
  - `status`: `OK` when the alive check succeeds, `ERROR` when it fails, `UNKNOWN` otherwise
    (FR-008). The version from `uname` goes to `versions.observed()` only when the recordings
    confirm it is the server version (FR-006, REC); otherwise it is reported as the client's.
- **Budget** (FR-009): a constant `CLI_ONLY_SINGLE_NODE_BUDGET = 15 s` for a call that addresses
  one CLI-only node; when a call addresses several nodes, CLI-only nodes get
  `OVERVIEW_BUDGET − FAN_OUT_GRACE` (10 s − 0.9 s) and the fan-out deadline becomes the maximum of
  the per-node deadlines; unfinished CLI parts are `TIMEOUT`. 8.1/9.x nodes keep their
  `timeout + 0.9 s` (`HealthService.java:97-160`).
- **Rationale**: the REST-shaped `MonitoringPort` (healthcheck/ready/systemInfo/metrics) does not
  fit one CLI session; branching on the channel (not on a version string) keeps FR-016.

## D-7 Server-side TLS check before StartCLI

- **Decision**: new `adapter/rest/TlsHandshakeCheck.verify(host, port, Tls, timeout)`: builds the
  context with `SslContexts.forServer` (`adapter/rest/SslContexts.java:41`, `PinningTrustManager`),
  opens an `SSLSocket` with SNI and, unless `disableHostnameVerification` with a pin, the endpoint
  identification algorithm `HTTPS` (the socket path of `PinningTrustManager` checks host names only
  with it), completes the handshake and closes; no byte of HTTP is sent. Target: host and port of
  the effective StartCLI URL (`EffectiveNodeConfig.cli().url()`). Runs once per tool call and node,
  before the first StartCLI run; failure → `TLS_ERROR`, no StartCLI process (FR-012).
- **Rationale**: the 6.1 client documents no trust-store option, so the pin (feature 006) must be
  checked by the server (clarification round 1). The connect timeout is the node's `timeout`.

## D-8 What reaches the client: trust store, host names, option detection

- **Scope**: everything in this decision applies only to nodes whose matrix entry is `cliOnly`
  (6.1). For 8.1/9.x nodes nothing changes: no `-h` probe, the argument array of
  `CliRunner.runUnguarded` (`:86-100`) stays as today (always `--trustStoreFilePath` when a trust
  store is configured, `--disableHostNameVerification` when set), and `--check-config` shows no
  client options (FR-018).
- **Decision**:
  - A local probe runs `startcli.sh -h` (no URL, no credentials, no stdin; like `CliVersionProbe`,
    `adapter/cli/CliVersionProbe.java:53-86`) and records `CliClientOptions`:
    `trustStoreFilePath`, `disableHostNameVerification`, `execCommand` present or absent, and the
    Java feature version of the client runtime. An unreadable or failed help output means
    "absent" (fail closed, FR-012a). `--check-config` runs it too and reports it.
  - Ownership and order: the options live in an adapter-side per-node cache inside
    `CliClientProbe` (`ClientVersionPort` leaves caching to the caller, and `NodeVersions` is in the
    application layer). `V61Connection` obtains them synchronously before its refusals and before
    building arguments — from the cache, or by running `-h` locally when missing (bounded by the
    node's `cliTimeout`, within the call's budget). Only the `-v` version probe of feature 009 runs
    in parallel with the health session. A failed `-h` is not cached and is retried at most once
    per 60 s, like the version probe.
  - Trust store: the client's own option when present (today's `--trustStoreFilePath`); otherwise
    `-Djavax.net.ssl.trustStore=<path>` and `-Djavax.net.ssl.trustStoreType=<type>` appended to the
    allowlisted `JAVA_TOOL_OPTIONS` (`CliRunner.java:53`). `JAVA_TOOL_OPTIONS` is split at
    whitespace without quoting on Java 7/8, so a trust-store path containing whitespace is refused
    on this route with `TLS_ERROR` and the next step "use a trust-store path without spaces"
    (`--check-config` warns). No password ever: a trust-store password
    on a node with a configured CLI is already a configuration error
    (`ConfigValidator.checkCredentials`, `:909-920`), which stays.
  - Host names: `--disableHostNameVerification` only when the client has it and the node has
    `disableHostnameVerification` with a pin; otherwise such a node is refused with `TLS_ERROR`
    naming the missing option (before D-7).
  - Ownership: `CliRunner` only builds arguments and environment from the `CliClientOptions` it is
    given; every refusal of this decision is made by `V61Connection` (the 6.1 connection sequence:
    StartCLI available → option and format refusals → concurrency permit → handshake (D-7) →
    session), so a node without StartCLI answers `CLI_UNAVAILABLE` without any contact.
  - Format: a PKCS12 trust store with a client runtime below Java 8 is refused with `TLS_ERROR`
    and the next step "convert the trust store to JKS". The runtime version is read locally from
    `<runtime>/release`, where the runtime is `cli.javaHome` or, as today, the parent's
    `JAVA_HOME`; if it cannot be read and the trust store is PKCS12, fail closed (refused, next step
    "set cliJavaHome or convert the trust store to JKS").
- **Rationale**: FR-012a; Constitution II.

## D-9 Concurrency per 6.1 node

- **Decision**: a `CliConcurrencyLimiter` keyed by node with `Semaphore(2)` for nodes whose
  matrix entry is `cliOnly`; `tryAcquire(cliTimeout)`, else `TIMEOUT`. The local probes do not
  take a permit. The credential guard (`adapter/CredentialGuard.java:74`, `Semaphore(1)` while
  unconfirmed, 60 s pause after a rejection) applies on top.
- **Rationale**: FR-015; no limit exists today (8.1 nodes are unaffected).

## D-10 Process listing on 6.1 (REC)

- **Decision**:
  - Listings per FR-010: no state or `ACTIVE` included → `ps -a`; otherwise one `ps -e` / `ps -w`
    / `ps -q` per state; all in the same session (D-5); each with `-l 10000`.
  - `V61PsParser`: columns by the header `NODE UID PID PRIO STATE DATE WORKFLOW MODULE` (positions
    from the header line; German header names from the recordings), the trailing
    `Total: <n>`/German equivalent; a module text `Name(id)` is kept as the module name like 8.1
    shows it. Unknown header or line → `UNEXPECTED_RESPONSE`.
  - Raw states: German state names are translated to the English raw names before
    `ProcessState.fromRaw` (`domain/model/ProcessState.java:26-31`); `rawState` keeps the original;
    anything else → `OTHER`.
  - Dates: ISO local date-time (documented example `2014-07-24T12:38:06`) or the German
    `dd.MM.uuuu HH:mm:ss`, read as Europe/Berlin with the rules of `InubitDates`
    (`adapter/cli/v81/InubitDates.java:19`, made available to `v61` through a shared
    `adapter/cli/CliDates`).
  - `V61ProcessQueryAdapter.find` filters (workflow equal, time range on `since`, hanging as
    `since ≤ now − threshold` for non-final states), sorts newest first, pages, and sets
    `Page.truncated`/`totalIsLowerBound` when any listing returned 10,000 rows
    (`domain/model/Page.java`). `validate` refuses `tag` and, depending on REC, `ACTIVE` or the
    time inputs with `UNSUPPORTED_VERSION` (it runs before the gate in `DiagnosisService:188-193`,
    so it carries the code itself). The per-node deadline in `DiagnosisService` (`:295-298`, today
    `3 × timeout + 0.9 s` for "guard wait, detection, query") is derived from the node's
    process-diagnosis channel taken from the gate: for CLI it is options probe on a first call
    (`cliTimeout`) + limiter wait (`cliTimeout`) + handshake (`timeout`) + session (`cliTimeout`) +
    grace (+ the AWS allowance), so the node's `cliTimeout` governs 6.1 diagnosis; the credential
    guard wait is bounded by the limiter wait as today. Because `DiagnosisService.validateHeld`/`validate` (`:262-286`)
    swallow every error but `INVALID_INPUT` for nodes whose credentials are held, two changes make
    the refusal surface per node: `DiagnosisService` keeps an `UNSUPPORTED_VERSION` from
    validation and returns it as that node's result entry, and `find()` repeats the input check
    (second line of defence). `findByProcessId` throws `UNSUPPORTED_VERSION`.
  - `__NotAssigned__` → no `inubitNode`.
  - Output bound (follow-up from T032): a reconstructed row with a module is about 130 bytes, so
    one 10,000-row listing is about 1.3 MB and exceeds the 1 MiB bound of `CliRunner`
    (`MAX_OUTPUT_BYTES`); a session with up to three listings needs several MB. A cut session is
    `UNEXPECTED_RESPONSE`, so a node with many instances could not be listed at all. Decision:
    `CliRunner.runSession` takes the output bound per call; process sessions get 4 MiB per
    listing, health and `--execCommand` runs keep 1 MiB. The cut message names the bound that
    was exceeded. The cap of `-l 10000` and paging stay unchanged.
  - The 6.1 result entry carries the warning "logs of INUBIT 6.1 cannot be read with query_logs;
    read them in the Workbench" (FR-010; `FindProcessesTool.java:22-24` keeps its description). The
    text comes from the gate admission (`VersionGate` returns version notes with an admission, as
    it returns warnings for unverified reads today), not from `DiagnosisService` (FR-016).
- **REC questions**: is `DATE` the time of entering the state; does `ps -a` list Processing
  instances; which rows does `-l` drop (recorded with `-l 5`); German labels; does `ps -w` list `Retry` rows (if not, a `WAITING` query also reads
  them from `ps -a`); how does the client print a value longer than its column. If `DATE` is not the
  state-entry time, a follow-up task makes `ProcessInstance.since` optional
  (`domain/model/ProcessInstance.java:25-40`, required in `find_processes.output.json:85-92`),
  refuses the time inputs, keeps the listing order with the note "not sorted by time" and makes
  the sort in `DiagnosisService.findProcesses` (`:204`, by `since`) tolerate a missing `since`
  (FR-010).

## D-11 Refusals, contradiction and the `AUTO` hint

- **Decision**:
  - Monitoring admission before contact: today `HealthService.checkServer` (`:176-225`) calls
    `gateways.monitoring()` and sends its REST requests before `gate.admitMonitoring()`, which
    never refuses. A new `VersionGate.monitoringAccess(node)` is called first: for a `cliOnly`
    entry it returns the refusal (capability not `SUPPORTED`) or "CLI"; for every other node it
    returns "REST" and the existing non-refusing path continues unchanged. So `get_health` on a
    6.1 node never sends a REST request, also before the CLI path exists.
  - Contradicted 6.1 pin: `VersionGate.judge` refuses every capability except `MONITORING` on a
    `cliOnly` entry while `NodeVersionState.contradicts()` (`:188`) holds; today only writes are
    refused (`VersionGate.java:206`). `get_health` keeps running and clears it (feature 009).
  - `check_artifacts`: its module lookup is admitted as `INVENTORY` (`ArtifactCheckService.java:528`)
    and is refused for 6.1 before any contact; the existing path turns it into
    `MODULE_UNVERIFIED` findings and the offline checks run (FR-006a). The text comes from the
    gate's refusal (the version-specific next step lives in `VersionGate`), not from
    `ArtifactCheckService` (FR-016).
  - `AUTO` hint: `NodeVersions.detectionFailed` stores only an `ErrorCode`; a detection answered
    with HTTP 404 is `UNEXPECTED_RESPONSE` like other causes (`InubitHttpClient.java:85-94`,
    `V81VersionDetector.java:28-35`). The detector marks the 404 case
    (`DetectionFailure(code, endpointMissing)`), `NodeVersions` keeps the flag, and
    `VersionGate.unknown()` (`:405`) appends "if <node id> runs INUBIT 6.1, pin V6_1" only then
    (FR-003).
- **Rationale**: FR-003, FR-006, FR-006a, FR-007.

## D-12 Tool announcement

- **Decision**: `Wiring.toolHandlers()` (`:431-466`) also filters read tools with
  `possiblyCapable(server, capability)` (`:585-593`): `find_processes` (process diagnosis),
  `query_logs` (log diagnosis), `list_inventory`/`get_inventory_item` (inventory) and
  `export_artifacts` (workspace export, with `cli.home`) are announced only if some node may run
  them. `list_nodes`, `get_health` and `check_artifacts` stay always announced: `list_nodes` needs no
  node, `get_health` reports a refusal per node (useful even when every node refuses), and
  `check_artifacts` runs offline. `Wiring` takes the matrix as an injectable dependency (today the
  static `MATRIX = CapabilityMatrix.production()`, `:138`) so the announcement can be tested with a
  test matrix. `AUTO` and 8.1/9.x nodes stay
  "possibly capable" as today, so existing profiles announce the same tools.
- **Rationale**: US3-4 (tool list rule of feature 009, so far applied to write tools only).

## D-13 `list_nodes` and `--check-config`

- **Decision**:
  - `list_nodes` version block gains `access`: `rest_and_cli` or `cli_only` (from the matrix
    entry), and shows `V6_1` as `versionLine`; the usable capabilities come from the matrix as
    today (`NodeOverview.version()`, `:63-113`). Schema: additive field, enum `CapabilityName`
    with the split names.
  - `--check-config` (`ConfigSummary.java:100-125`) prints for a 6.1 node
    `inubit: V6_1 (INUBIT 6.1, StartCLI only; available: monitoring, process diagnosis; refused:
    …)`, the client options of D-8 and the warnings of FR-012a and FR-014; a 6.1 node without
    `cliHome` gets a new warning, a configured `cliHome` without `bin/startcli.sh` the existing one
    (`ConfigValidator.checkCli`); 8.1/9.x nodes without `cliHome` stay without finding. No INUBIT contact
    (`Launcher.java:183-249` runs local probes only).
- **Rationale**: FR-004, FR-012a, FR-014; spec US3-5, US5-9.

## D-14 Refusal logging

- **Decision**: no change needed: `VersionGate.refuse()` (`:374-381`) logs every refusal as a
  structured INFO record and write refusals are audited through the existing guards (FR-019).
  Tests assert the log record for a 6.1 refusal.

## D-15 Fixtures, reconstructed and recorded

- **Decision**:
  - New folder `src/test/resources/fixtures/v6_1/cli/` with the case layout of 8.1
    (`<case>.stdout/.stderr/.exit`) and a `README.md`. Until the recordings exist, cases are
    reconstructed from the documentation and listed in `RECONSTRUCTED.txt`
    (case name → capability).
  - `FakeProcessLauncher.replaying` gets a version parameter (today the path
    `/fixtures/v8_1/cli/` is hard-coded, `:65`).
  - Guard test `V61RecordedFixturesTest`: for every capability that `CapabilityMatrix.production()`
    declares `SUPPORTED` on 6.1, no case of that capability is listed in `RECONSTRUCTED.txt`
    (FR-005, FR-017).
  - Recordings are neutralized with `tools/neutralize.py` and checked by
    `NoCustomerIdentifiersTest`.
- **Rationale**: Constitution III ("recorded … CLI outputs for each supported INUBIT version").

## D-16 Recording procedure (live, by hand)

- **Decision**: `tools/record-fixtures.sh` gets 6.1 catalogue cases (`v61_help`, `v61_version`,
  `v61_health_session`, `v61_ps_all`, `v61_ps_error`, `v61_ps_waiting`, `v61_ps_queued`,
  `v61_ps_limit5`, and for US4 `v61_export_group`, `v61_export_repository`), StartCLI only, no
  curl/REST step. The script's group allowlist (`RECORD_ALLOWED_GROUPS`) stays; for a node
  classified production the person confirms node and classification in the chat and sets the
  allowlist for that run only (FR-020). The steps are in [quickstart.md](quickstart.md).
- **Rationale**: reuses the existing safeguards (catalogue, allowlist, stdin password,
  allowlisted environment).

## D-17 Export (US4)

- **Decision**: not designed in this plan beyond its gate: workspace export stays `UNSUPPORTED`
  on 6.1 (FR-023). After the sample exports exist, a research addendum compares the 6.1 archive
  with the 8.1 format of `adapter/archive/v81` and decides between reusing the 8.1 reader with a
  6.1 profile and a `adapter/archive/v61` reader; the US4 tasks are blocked on it.
  `ArtifactPort.exportModule` on 6.1 refuses with `UNSUPPORTED_VERSION` (FR-021).
- **Rationale**: the archive format is undocumented; designing it blind would risk the secret
  replacement (SC-007).

## D-18 Documentation and changelog

- **Decision**: in the same change: `docs/setup.md` (versionLine table and text :225-310, client
  check :312-334, TLS :1150, check-config :1318), `docs/tools.md` (version table :98-118, capability
  names :122, warnings :163-171, `UNSUPPORTED_VERSION` :173-194, announcement :198-204,
  `list_nodes` :336-395, `get_health` :411-452, `find_processes` :514, `check_artifacts` :1080),
  `README.md` (supported versions), `docs/research/inubit-interfaces.md` (a 6.1 section with the
  recorded formats), `CHANGELOG.md` `[Unreleased]` with the deliberate changes of SC-005.
