# Tasks: Unsupported Node Tools (node status, module I/O logging, node configuration, temporary log level)

**Input**: Design documents from `specs/024-unsupported-node-tools/` (spec, plan, research D-1…D-31,
data-model, contracts/mcp-tools-delta.md, contracts/configuration-delta.md,
contracts/helper-protocol-delta.md, quickstart, reviews/)

**Tests**: mandatory (constitution Principle III, TDD): each implementation task starts with its
failing tests in the same task (named in the task), observed to fail, and is done when they pass
and the build is green. No offline test contacts INUBIT: helper and StartCLI runs are replayed by
`ScriptedProcessLauncher` / `FakeProcessLauncher` (`onLaunch` reads the request from stdin and
writes the `.list`/`.message` fixtures), the helper runs in-process against fake vendor classes
under `src/test/java/com/inubit/ibis/…` and once per operation in a child JVM, version detection
against WireMock, the lease scheduler with a fake clock and a fake `ProcessLiveness`.

**Build**: `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-024-tmp` (tmpdir
outside the checkout; never two builds in parallel; the full build takes longer than 10 minutes, run
it detached); Python: `python3 -I -m unittest discover -s tools/tests`. Paths below are relative to
`src/main/java/de/dadecker/inubit/mcp/` (main) and `src/test/java/de/dadecker/inubit/mcp/` (test)
unless they start with `src/`, `docs/`, `specs/`, `tools/`, `README.md`, `SECURITY.md` or
`CHANGELOG.md`.

**Secrets (SC-002, Constitution II)**: every task that adds a message, warning, hint, log line,
result field, audit record, lease record, frame, request or argv asserts with `testing/TestSecrets`
that a configured password, the one-time trust-store password, a credential-named value of a
message/detail/configuration value and the known credentials of another configured node never
appear in it — named below as "leak check".

**Nothing sent (FR-002, SC-003, SC-005)**: every refusal path is asserted with zero WireMock
requests and zero launches ("nothing sent").

**Customer-neutral**: only fictitious names (`acme`, `globex`, `test`, `prod`, `legacy`, `node1`,
`node2`, `*.example.test`, `Orders-Inbound`, `Module-0003`) in code, tests, fixtures and docs; the
non-production test servers are named only "the non-production 8.1/9.0 test server". The pre-commit
hook blocks customer identifiers; never commit with `--no-verify`; `python3 -I
tools/check-identifiers.py --all` must exit 0.

**Version state during implementation (FR-005, D-4, D-27)**: `CapabilityMatrix.production()`
declares the four capabilities on 8.1 **and** 9.0 as `UNSUPPORTED` with the note "not yet verified"
(T004) until the commit that adds the recorded fixture of that (version, capability) flips it
(T025, T032, T038, T058, T062–T065). Offline tests that need an admitted node use a test
declaration of the matrix (the non-production `CapabilityMatrix` constructor, as 020's
`adapter/cli/queue/QueueEntryVersionProfile.java`), never the production declaration.

**Gates**:
- **[OWNER]**: needs the owner's decision or review (e.g. of a neutralized fixture diff) before it
  is done.
- **[LIVE]**: runs against a real INUBIT server: **non-production test servers only**, executed by
  the operator, or by the agent **only after the operator approved that concrete action in the
  chat** (server, program or command, private output folder). Read spikes are covered by the
  owner's standing permission for read-only checks on the non-production test servers but are
  still proposed concretely first; the log-level write spikes (T039, T061) need the owner's
  approval of **exactly that action** (server, logger, level, the two StartCLI runs). Raw outputs
  stay owner-only in a private directory outside every repository; only neutralized fixtures with
  **fictitious names and content** enter the repository.
- **[BLOCKED: Tnnn]**: cannot start before task Tnnn (a recording) is done. All other tasks run
  offline and do not wait for recordings.
The implementer stops at a gate and reports what is needed.

**Reviews**: each task goes through the implementer → reviewer loop
(`d4ld4r-speckit-duo:implement`); after each phase a code review of the phase diff; findings and
decisions go to `specs/024-unsupported-node-tools/reviews/implementation.md`.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [X] T001 Baseline (D-30): if `021-server-log-download` is merged into `main`, rebase this branch on
  `main` first (else stay on the 021 base and note it); `mvn -o -q clean verify` with the tmpdir
  above and the Python tests are green; create
  `specs/024-unsupported-node-tools/reviews/implementation.md` and note there the test count
  (surefire + failsafe) and the exact tool lists of `MainTest` / `ToolAnnouncementTest` as the
  SC-005 reference; the owner decisions in force (cleanup resets also with write tools off —
  Constitution I deviation, plan Complexity Tracking; plan open question 1 at its default
  unless the owner overrides; the decisions of plan review round 1 (lease generation +
  cross-process node lock; fail-closed audit for cleanup resets; the **creator decision** of research
  D-16 that orphaned unexpired leases are taken over at once and reset at their expiry, the reading
  of US4-3 — owner may override)); the state of feature 022 (merged or not → which guard T047 uses and
  whether T074 runs here); and ask the operator **now** for the read spikes T018 and T060 (they run
  in parallel to the offline work)
- [X] T002 [P] Fixture root (D-26): `src/test/resources/fixtures/node-tools/README.md` describing the
  layout `{v8_1,v9_0}/{cluster-nodes,io-log,node-config,log-level}/<case>.<ext>` (helper cases:
  `.frames`, `.exit`, io-log also `.list`, `.message`; StartCLI cases: `.stdout`, `.stderr`,
  `.exit`; every case `.origin`), the `.origin` formats "`recorded <date> <version> neutralized`" /
  "`reconstructed <source>`", the rule "names and content are always fictitious", the
  neutralization steps of D-26 and the vector file `io-message-ref.vectors` (format: `<entry>\t<ref>`
  per line); no case files yet

## Phase 2: Foundational — configuration, capabilities, domain types, helper protocol, adapter base (plan stages 1, 3, 4; blocks every story)

### Configuration and capabilities (plan stage 1)

- [X] T003 [P] Four opt-ins (FR-002, D-5): `config/UnsupportedConfig.java` `+ nodeStatusRead,
  ioLogRead, nodeConfigRead, logLevelWrite`; `config/Defaults.java` `BUILTIN_NODE_STATUS_READ`,
  `BUILTIN_IO_LOG_READ`, `BUILTIN_NODE_CONFIG_READ`, `BUILTIN_LOG_LEVEL_WRITE` = `false`;
  `config/ConfigLoader.java` `requireStrictUnsupportedOptIn` for the four keys;
  `config/EffectiveNodeConfig.java` `+` the four booleans (group only, every copy method and
  constructor); `config/GroupConfig.java` javadoc of `unsupported`. Failing tests first in
  `config/ConfigLoaderTest.java` (each key `true`/`false`/empty/`null` loads; `"true"`, `1`, `yes`,
  list, mapping → "Invalid value at groups[0].unsupported.<key> in <source>: expected true or
  false" without the value; an unknown key under `unsupported` and a key on a node or in `defaults`
  stay unknown-key errors; keys independent of each other and of `queueEntryRead`/`serverLogRead`)
  and `config/EffectiveNodeConfigTest.java` (group value reaches every node; copies keep it)
- [X] T004 [P] Capabilities (FR-005, D-4): `domain/model/Capability.java` `+ CLUSTER_NODES_READ`,
  `IO_LOG_READ`, `NODE_CONFIG_READ` (READ, `{CLI}`), `LOG_LEVEL_WRITE` (WRITE, `{CLI}`) with the
  labels of D-4; `domain/model/CapabilityMatrix.java` 8.1 and 9.0 `UNSUPPORTED` + note "not yet
  verified" for all four (flipped only by T025, T032, T038, T058, T062–T065), unknown map
  `UNSUPPORTED`; `application/VersionGate.java` `supportedLines` and `cliOnlyStep` (D-4 texts);
  `application/NodeOverview.java` names `cluster_nodes_read`, `io_log_read`, `node_config_read`,
  `log_level_write`; `src/main/resources/schemas/list_nodes.output.json` enum `+` the four names.
  Failing tests first in `domain/model/CapabilityMatrixTest.java` (states per version incl. 6.1,
  9.1, unknown; notes), `application/VersionGateTest.java` (refusal texts "… is not yet verified
  for INUBIT 8.1", 6.1 → `UNSUPPORTED_VERSION` naming the supported lines),
  `application/NodeOverviewTest.java` (names; a capability listed only for opted-in groups with a
  client home — the `allowed` rule is wired in T023/T030/T036/T054)
- [X] T005 Validation and summary (FR-003, D-5; after T003 and T004): `config/ConfigValidator.java`
  production warnings per key and the `logLevelWrite`-without-write-access warning (texts of
  contracts/configuration-delta.md "Validation", terminology-aware); `config/ConfigSummary.java`
  group line parts in the order `queueEntryRead`, `serverLogRead`, `nodeStatusRead`, `ioLogRead`,
  `nodeConfigRead`, `logLevelWrite` (only keys some group sets); the `cliOnly` filter for the four
  capabilities moved to T004 (orchestrator decision, build green per commit). Failing tests first in `config/ConfigValidatorTest.java`
  (each warning on `production: true`; none on a non-production group; the no-write warning) and
  `config/ConfigSummaryTest.java` (no key set → every golden text unchanged (SC-005); one key set →
  every group line gains that part only; 6.1 `cliOnly` lists a capability only for opted-in groups)

### Domain types used by the helper protocol (plan stage 4; D-7, D-8, D-10, D-11, D-24)

- [X] T006 [P] Node domain types (pure; D-7, D-10, D-11): `domain/model/node/NodeStatus.java`
  (`of(Optional<String>)`), `ClusterNode.java`, `NodeDetail.java`, `ClusterNodes.java` (with
  `Details` = `Available` | `Unavailable`), `NodeConfigName.java`,
  `NodeConfigValue.java` (`Present` | `Absent` | `Failed`), `LoggerName.java`,
  `TemporaryLevel.java`, `package-info.java`. Failing tests first in
  `domain/model/node/NodeStatusTest.java`, `ClusterNodeTest.java` (name rule, raw status rule,
  bounds), `NodeConfigNameTest.java` (exactly the 20 names, case-sensitive parse),
  `LoggerNameTest.java` (`com.inubit.ibis.acme.Orders` ok; `com.inubit.ibis`, `com.inubit.ibisx.a`,
  `org.acme.A`, `com.inubit.ibis.a$B`, `com.inubit.ibis..a`, > 200 chars, a quote, a space → refused)
- [X] T007 [P] I/O logging domain (pure; D-8): `domain/model/node/IoDirection.java`,
  `IoMessageRef.java` (`of(String entry)` SHA-256/Base64url 16 bytes; pattern),
  `IoLogEntry.java` (`parse(String processId, String entry)`: module part split at the last `(` of
  a part ending in `)`; unparsable module part kept raw with a flag; foreign process id →
  exception), `IoLogListing.java` (`filter(Optional<String> module)` on `moduleName` or `moduleId`
  equality), `IoLogBounds.java`; creates `src/test/resources/fixtures/node-tools/io-message-ref.vectors`
  (≥ 5 fictitious entries incl. non-ASCII module names, `<entry>\t<ref>` per line; T013's helper
  test reuses it). Failing tests first in `domain/model/node/IoMessageRefTest.java`
  (every line of `io-message-ref.vectors`),
  `IoLogEntryTest.java` (`Orders(acme)(Module-0003)`, names with spaces and non-ASCII, missing id,
  `Input.0`/`Output.x` refused, foreign process id), `IoLogListingTest.java`

### Helper protocol (plan stage 3; D-2, contracts/helper-protocol-delta.md)

- [X] T008 Request side and `ownNode` (D-2, D-3; after T006, T007): `adapter/cli/helper/HelperRequest.java` sealed
  `Operation` `+ OwnNode`, `ClusterNodes(Optional<String> details)`, `NodeConfig(List<NodeConfigName>
  names)` (the enum of T006), `IoLog(String processId, String workflow,
  Optional<IoMessageRef> ref, Path listOut, Optional<Path> messageOut)` (`IoMessageRef` of T007); `encode()` writes `op=<name>` as the second line
  for every operation except `QueueEntryRead`; `toString` without user and passwords.
  `src/helper/java/de/dadecker/inubit/mcp/helper/ClientHelper.java`: accepts the four `op` values
  with their keys and rules (contracts table; malformed → exit 2 before the login) and implements
  `ownNode` (login, `ClusterClient`, `getNode()` → `node ok`/`node error`, logout on 8.1); the
  other three valid operations answer exit 2 until T011–T013. Failing tests first in
  `adapter/cli/helper/HelperRequestTest.java` (exact encoding of each new operation; **the 020
  `queueEntry` and 021 `serverLog` encodings byte-identical** to their existing expected texts;
  round trips through the real helper class for `queueEntry`, `serverLog`, `ownNode`) and
  `adapter/cli/helper/ClientHelperTest.java` (`ownNode` frame order `start`, `login ok`, `node
  ok`, `end`; vendor calls exactly login, `getNode`, logout (fake call recorder); every key rule
  of the four operations → exit 2 and no vendor call; a key of another operation → exit 2; leak
  check on frames); `git diff --stat src/test/resources/fixtures/queue-entry
  src/test/resources/fixtures/server-log` empty
- [X] T009 [P] Frames (D-2): `adapter/cli/helper/HelperFrames.java` `+ Member`, `MembersOk`,
  `MembersError`, `Detail` (with `cut`), `DetailsOk`, `DetailsError`, `DetailsUnknown`, `ConfigOk`,
  `ConfigAbsent`, `ConfigError`, `IoListOk`, `IoListError`, `IoMatch(Optional<String>)`, part
  `MESSAGE` in `PartOk`/`PartError`; token counts and `-` handling per frame; unknown or malformed
  frame → protocol error. Failing tests first in `adapter/cli/helper/HelperFramesTest.java` (each
  frame parsed; wrong token count, unknown frame, foreign nonce, frames after `end` → protocol
  error; the 1 MiB cap unchanged; 020/021 frames unchanged)
- [X] T010 [P] Fake vendor classes (D-27) in the test tree `src/test/java/com/inubit/ibis/…`:
  `server/cluster/ClusterClient.java` `+ getNodes()`, `getNode(NodeName)`,
  `getNodeConfigurationProperty(NodeName, String)` and — throwing a test failure when called —
  `setNodeConfigurationProperty`, `changeTemporaryLogLevel`, `resetTemporaryLogLevel`,
  `startGarbageCollector`; new `server/cluster/data/NodeList.java`, `Node.java` `+` the fields and
  getters of research "Static facts" and nested `Node.Status` (`ACTIVE`, `MAINTENANCE`, `OFFLINE`),
  `server/datafacade/NodeInformation.java`, `utils/property/type/MapProperty.java`,
  `utils/property/type/IBISProperty.java` (getters of D-10), `utils/I_IBISClientInterface.java`
  `+ getObject(String, String, String)`; a scripted fake (answers per test) and a call recorder
  shared with the 021 fakes; fictitious data only. Failing test first:
  `adapter/cli/helper/FakeVendorClassesTest.java` (recorder lists calls in order; forbidden
  methods fail the test)
- [X] T011 `ClientHelper` op `clusterNodes` (D-2, D-7; after T008, T010): `getNodes()` → `member`
  frames for the first 200 (`-` for `null`, status `name()`, uptime `-` without status, tokens
  cut at 200 chars and 600 percent-encoded bytes), `members ok
  <total>` / `members error`; `details`: not a
  member → `details unknown` without a request; member → `getNode(NodeName.nodeName(name))`,
  node information map flattened (`<outer>.<inner>`), ≤ 500 `detail` frames, key ≤ 200 and value ≤
  4,000 chars with `cut`, encoded `detail` frames together ≤ 256 KiB; `details ok <n> <truncated>` /
  `details error`; API check of the operation. Failing tests
  first in `adapter/cli/helper/ClientHelperTest.java` (frame sequence for two members, one without
  answer; status `name()` written unchanged, `-` for `null` — the unknown-status classification
  is covered by the T019 fixture `unknown_status`; uptime `-` without status; details of a member; `details unknown` sends no
  `getNode(NodeName)`; nested map flattened; cut marker; **total budget**: node information whose
  `detail` frames exceed 256 KiB → the helper stops before the frame that would exceed it and
  writes `details ok <n> true` (also at 500 entries), the whole stdout stays below 1 MiB; more than
  200 members → 200 `member` frames and `members ok <total>`; member tokens cut at 200 chars and
  600 encoded bytes, non-ASCII members and details keep the whole stdout below 1 MiB; vendor calls exactly login, `getNode`,
  `getNodes`, [`getNode(NodeName)`], logout; `api missing` for a missing `getNodes`; leak check)
- [X] T012 `ClientHelper` op `nodeConfig` (D-10; after T011, same file): a constant list of the 20
  names inside the helper; per requested name `getNodeConfigurationProperty(<own NodeName>, name)`
  → `config … ok` (type simple name, flags, texts cut at 4,000 chars and 12,000 percent-encoded
  bytes, so 20 frames stay below 1 MiB), `absent` for `null`, `error` per name;
  API check. Failing tests first in `adapter/cli/helper/ClientHelperTest.java` (each allowlisted
  name passed; `Database`, `ClusterPassword`, `initialRootUserPassword`, `IdentityProvider`,
  `IdentityProviderConfiguration` and any other text → exit 2 and **no vendor call at all**
  (SC-003); the `NodeName` passed is the one `getNode()` returned; one failing name does not stop
  the others; `setNodeConfigurationProperty` never called; leak check) and new
  `adapter/cli/helper/NodeConfigAllowlistTest.java` (the helper's list — read reflectively — equals
  `NodeConfigName.values()` and contains none of the secret-holding names of D-10)
- [X] T013 `ClientHelper` op `ioLog` (D-2, D-8, D-9; after T012, same file): `getObject("<processId>@@@<workflow>",
  user, "inputOutputMessageDetails")`, split at `###` (empty answer → zero entries), entries written
  to `listOut` (UTF-8, one per line) → `io list ok <n>` / `io list error`; with `ref`: SHA-256 →
  Base64url (16 bytes) per entry, `io match <entry>` + `MultipartDownloadThread(client, user,
  "<workflow>###<entry>", "InputOutputLogging", null, additionalUserGroup)` to `messageOut` →
  `part message ok <bytes>` / `part message error`, or `io match none` without a download (the
  vector file of T007 is reused, not changed). Failing tests first in `adapter/cli/helper/ClientHelperTest.java` (the
  object name and type sent; listing file content and order; empty answer; every vector's ref; the
  download name built only from the workflow and the listed entry; `io match none` → no download
  thread created; 0-byte download → `part message ok 0`; vendor calls exactly as the contract;
  leak check)
- [X] T014 Child-JVM round trips (after T013): `adapter/cli/helper/ClientHelperProcessTest.java`
  `+` one real child-JVM run per new operation against the fake vendor classes on the class path
  (frames parsed by `HelperFrames`, exit 0, files written); `ClientHelperPackagingTest` still finds
  exactly one class file (major 61)

### Ports and adapter base (plan stage 4; D-21, D-24)

- [X] T015 Ports and port wiring (D-24; after T006, T007): `domain/port/ClusterNodePort.java`,
  `IoLogPort.java` (+ `IoLogRead` = `NoMatch` | `Message`, `AutoCloseable`), `NodeConfigPort.java`,
  `LogLevelPort.java` (`LeaseView` follows in T045, after `Lease`); `domain/port/Gateway.java` / `GatewayFactory.java`
  default methods throwing `CLI_UNAVAILABLE`; `adapter/PortSet.java` (compatibility constructor),
  `V81PortSet.java`, `V81Gateway.java`, `AdapterGatewayFactory.java`, `StaleReportingPorts.java`
  (forward), `RefusingPorts.java` (6.1 refusal). Failing tests first in
  `adapter/AdapterGatewayFactoryTest.java` and `adapter/RefusingPortsTest.java` (new ports reach the
  version's adapter; 6.1 refuses with the gate text; defaults throw `CLI_UNAVAILABLE`);
  `architecture/PackageBoundaryTest.java` green with the new packages
- [X] T016 Helper runner and shared classification (D-3, D-20, D-24; after T008, T009, T015):
  `adapter/cli/node/NodeHelperRunner.java` (variant via `HelperVariant.forVersion`,
  `CliRunner.select` + `checkLaunch`, TLS check + one-time trust store, private directory through
  `CliResources` with prefix `inubit-mcp-node-`, slot `CliConcurrencyLimiter.acquireForHelper(server,
  wait, budgetSetting)`, `ProcessRun` with the request on stdin, `HelperFailures` for launch,
  login, TLS, network, 403, API missing, protocol problems; the deadline comes from the caller),
  `adapter/cli/node/NodeFramesClassifier.java` (shared part: `node ok` name rule 1–128
  `[A-Za-z0-9_.:-]`, `node error` → `UNEXPECTED_RESPONSE`, exit code ≠ frames, missing `end`),
  `adapter/cli/CliResources.java` (`+` the prefix; stale sweep includes it),
  `adapter/cli/node/package-info.java`. Failing tests first in
  `adapter/cli/node/NodeHelperRunnerTest.java` (`FakeProcessLauncher`: argv = class name only,
  password only on stdin, budget setting named in the slot-wait and process `TIMEOUT`, private
  directory deleted on success, failure, timeout and shutdown; no client home → `CLI_UNAVAILABLE`
  naming `defaults.cliClients."8.1"`; leak check) and `NodeFramesClassifierTest.java`
- [X] T017 [P] Content shaping shared with 020 (D-9, D-21): extract `application/EntryText.java`
  from `application/QueueEntryService.java` (`data(…)` and the `mask` step: classify →
  `knownSecrets` → `SecretTextMasker.mask` → `QueueEntryBounds.cut`), `QueueEntryService` delegates
  without behaviour change; new `application/nodetools/NodeTextMasking.java` (`mask(String)` and
  `maskAndCut(String, int)` with the cut marker `…[cut]`). Failing tests first in
  `application/EntryTextTest.java` and `application/nodetools/NodeTextMaskingTest.java` (a profile
  password of another node, `Authorization: Basic …`, a `<password>` element, a JSON secret value
  masked before the cut; never cut inside a surrogate pair; leak check); `QueueEntryServiceTest`
  and the 020 contract tests unchanged and green

**Checkpoint**: build green; 020/021 fixtures byte-identical; tool list unchanged (no tool wired
yet); code review of Phase 2.

## Phase 3: User Story 1 – See the cluster's view of its nodes (P1) 🎯 MVP

**Goal**: `list_cluster_nodes` returns every node the cluster knows (incl. `UNKNOWN` for nodes
without answer) and, with `nodeName`, that node's masked details, for groups with
`unsupported.nodeStatusRead` (FR-010, FR-011).
**Independent test**: with a fake INUBIT client per version (helper frames replayed), a group with
the opt-in gets the list and one node's details; a group without it is refused before any request.

- [ ] T018 [US1] **[LIVE] [OWNER]** 8.1 read spike (D-28 step 1, quickstart "Early live tasks" 1; may
  start any time after T001, in parallel to Phase 2): the agent proposes the concrete action (the
  non-production 8.1 test server of the profile, the throw-away program **outside the repository**
  performing exactly the vendor calls of contracts/helper-protocol-delta.md for `ownNode`,
  `clusterNodes` with `details` of the answering node, `nodeConfig` for the 20 allowlisted names
  only, and `ioLog` listing + one download for a run the owner names, the private output
  directory) and waits for the operator's go; if the owner provides a user without the
  `Configuration` right, the node list and one configuration value are read with it as well. Raw
  output stays owner-only outside every repository. The neutral facts (frame-equivalent shapes,
  node-information keys, `getModuleNameAndId` form, whether the I/O listing keys on the Queue
  Manager id or the log's process id, the 0-byte case if seen, the permission-check confirmation;
  no names, no values) are appended to `specs/024-unsupported-node-tools/research.md` "Spike
  findings"; deviations from D-2/D-7/D-8/D-10 are listed for the owner before the affected flip
  *Status 2026-11-13*: ownNode, clusterNodes (+ details) and nodeConfig done (research.md "Spike
  findings" T018); the ioLog part is blocked: the configured account gets `NotAllowedAccess` for every
  listing – needs an account with the right or the owner naming one.
- [X] T019 [US1] Reconstructed cluster-nodes fixtures and catalogue (D-26, D-27; after T011):
  `src/test/resources/fixtures/node-tools/v8_1/cluster-nodes/{ok,single,details,details_truncated,details_unknown,details_error,unknown_status,api_missing,garbage}.{frames,exit,origin}` (T025: `ok` recorded, the former `ok` kept as `no_answer`)
  (all `reconstructed <source>`, fictitious `node1`/`node2`, `zone-a`, `/opt/inubit/…`,
  `10.0.0.11:7800`); new `tools/NodeToolsFixtureCatalogueTest.java`: every case has a valid
  `.origin`, every file belongs to a case, and for every (version, capability):
  `CapabilityMatrix.production()` `SUPPORTED` ⇔ the required `recorded` cases of D-27 exist
- [X] T020 [US1] `adapter/cli/node/CliClusterNodeAdapter.java` + member/details rules in
  `NodeFramesClassifier` (D-7, D-20; after T016, T019): `implements ClusterNodePort`; frames →
  `ClusterNodes` (status mapping, raw status rule, uptime ms → `Duration`, texts ≤ 200, details
  `Available`/`Unavailable` with scrubbed reason ≤ 200, `details unknown` → `INVALID_INPUT`
  carrying the member names); `members error`, invalid name or status → `UNEXPECTED_RESPONSE`.
  Failing tests first in `adapter/cli/node/CliClusterNodeAdapterTest.java` (every v8_1 case via
  `FakeProcessLauncher`; request on stdin has `op=clusterNodes` and `details` only when asked;
  `cliTimeout` named as budget; leak check)
- [X] T021 [US1] `application/nodetools/ClusterNodeService.java` (FR-010, FR-011, D-7, contracts
  decision flow; after T017, T020): validate (`target`, `nodeName` rule) → `resolveSingleServer` →
  opt-in `nodeStatusRead` (`NOT_CONFIGURED`, text of contracts) → `admitWithDetection(…,
  CLUSTER_NODES_READ, READ, {CLI})` → deadline now + `cliTimeout` → `GatewayFactory.prepareCredentials`
  (`PENDING` → `TIMEOUT` naming `cliTimeout`) → port → `nodeName` not listed → `INVALID_INPUT`
  "… known nodes: …" (≤ 20 names, "and n more") → details masked with `NodeTextMasking` (value ≤
  2,000, ≤ 500 pairs) → result (≤ 200 nodes, `total`, warnings incl. the details warning) → one
  INFO line without values. Failing tests first in
  `application/nodetools/ClusterNodeServiceTest.java` (US1-1…US1-6: listing, `UNKNOWN` node kept,
  details + warning, no opt-in → `NOT_CONFIGURED` and nothing sent, single node, unknown
  `nodeName`; detection failure → its code, nothing sent; `details error` → list returned with
  `available: false`; a credential in a detail value masked; leak check incl. the log line)
- [X] T022 [US1] Tool and schemas (FR-001, FR-003, D-25; after T021):
  `mcp/tools/ListClusterNodesTool.java` (`ToolHints.readOnly("List the INUBIT cluster's nodes",
  true)`, description of contracts), `src/main/resources/schemas/list_cluster_nodes.{input,output}.json`.
  Failing tests first in `mcp/tools/ListClusterNodesToolTest.java` (schema: group id, bad
  `nodeName`, extra property refused; output example of contracts validates; description key phrases)
- [X] T023 [US1] Wiring (FR-002, SC-005, D-6; after T022): `Wiring.java` service, adapter,
  announcement (opt-in + `possiblyCapable` + client candidate), `allowed()`
  (`CLUSTER_NODES_READ` with `withClient && nodeStatusRead`). Failing tests first in
  `ToolAnnouncementTest.java` (announced only with the opt-in and a client home; not for a node
  pinned to 6.1; not with the production matrix before T025), new `ListClusterNodesWiringTest.java`
  (a group without the opt-in → `NOT_CONFIGURED`, zero WireMock requests, zero launches; with the
  test matrix a full call through the wired server), `MainTest.java` (without opt-ins the tool lists
  equal the T001 reference)
- [X] T024 [US1] Contract test (D-27; after T023): `adapter/cli/node/NodeToolsV81ContractTest.java`
  runs `CliClusterNodeAdapter` + `ClusterNodeService` per `v8_1/cluster-nodes` case (test matrix):
  `ok` → two nodes, one `UNKNOWN`; `single`; `details` → masked pairs + warning;
  `details_truncated` (`details ok <n> true`) → `details.truncated: true` + hint; `details_unknown`
  → `INVALID_INPUT`; `details_error` → `available: false`; `unknown_status` → `UNKNOWN` + raw;
  `api_missing` → `CLI_UNAVAILABLE`; `garbage` → `UNEXPECTED_RESPONSE`; edge cases maintenance
  (detection), credentials missing (nothing launched), TLS failure
- [X] T025 [US1] **[OWNER]** **[BLOCKED: T018]** Enable 8.1 `cluster_nodes_read` in **one commit**
  (FR-005, D-26, D-27): the agent neutralizes the T018 `clusterNodes` output in the private
  directory (every name, address, path, version string, id → fictitious; `tools/neutralize.py --map
  <private>/.synthetic-map` plus the private `neutralize-map`; `NoCustomerIdentifiersTest` with the
  private lists and `python3 -I tools/check-identifiers.py --all` pass) into
  `src/test/resources/fixtures/node-tools/v8_1/cluster-nodes/ok.*` (and further recorded cases if
  T018 saw them), `.origin` "`recorded <date> 8.1.x neutralized`". **The owner reviews the diff of
  the fixture files before the commit.** In the same commit `domain/model/CapabilityMatrix.java`
  8.1 `CLUSTER_NODES_READ` → `SUPPORTED` (note removed); updated first:
  `NodeToolsV81ContractTest` (recorded `ok`), `CapabilityMatrixTest`,
  `NodeToolsFixtureCatalogueTest`, `ToolAnnouncementTest`, `MainTest` (production matrix now
  announces for opted-in 8.1 / `AUTO` nodes). If T018 contradicts D-2/D-7, fix the helper or
  adapter test-first before the flip

**Checkpoint**: build green; US1 works end to end offline (and on the production matrix after
T025); code review of Phase 3.

## Phase 4: User Story 2 – Read the logged input and output of a process run (P1)

**Goal**: `read_io_log` lists a run's logged messages and returns one masked and bounded, for
groups with `unsupported.ioLogRead` (FR-020 … FR-023).
**Independent test**: with a fake INUBIT client, a run with logged messages is listed and one
message is read masked and bounded; an unlisted reference is refused before any download.

- [X] T026 [US2] Reconstructed io-log fixtures (D-26; after T013, T019):
  `src/test/resources/fixtures/node-tools/v8_1/io-log/{listing,empty_listing,read_text,read_latin1,read_binary,read_empty,no_match,foreign_process,list_error}.{frames,exit,list,message,origin}`
  (fictitious workflow `Orders-Inbound`, process `4711`, modules `Order Connector(Module-0003)`,
  `Audit Logger(Module-0007)`; `read_text.message` with a `<password>` element and an
  `Authorization: Basic …` line; `read_binary.message` a GZIP header; `read_latin1.message`
  ISO-8859-1 bytes); `NodeToolsFixtureCatalogueTest` extended
- [X] T027 [US2] `adapter/cli/node/CliIoLogAdapter.java` + io rules in `NodeFramesClassifier`
  (D-8, D-9, D-20; after T007, T016, T026): `implements IoLogPort`; request `op=ioLog` with
  `listOut`/`messageOut` in the private directory; listing file read bounded (≤ 16 MiB, else
  `UNEXPECTED_RESPONSE`) and parsed with `IoLogEntry.parse`; `io match none` → `NoMatch`; `part
  message ok <n>` → `Message` with the file (size must equal `<n>`); `io list error` whose message is
  INUBIT's `NotAllowedAccess` → `FORBIDDEN` (T018 spike: the listing checks a right of the account;
  research D-20 and contracts updated in this task); other `io list error`, `part message
  error`, a foreign process id → `UNEXPECTED_RESPONSE`; the `IoLogRead` closes the private
  directory. Failing tests first in `adapter/cli/node/CliIoLogAdapterTest.java` (every v8_1 case;
  the request never contains a caller-provided entry text, only `ref`; budget `cliTimeout` for a
  listing and `cliExportTimeout` for a read; private directory deleted after close, failure,
  timeout; leak check)
- [X] T028 [US2] `application/nodetools/IoLogService.java` listing (FR-020, D-8; after T017, T027):
  validate (`workflow` rule incl. `@@@`/`###`, `processId`, `module`, `maxChars`, `message`) →
  server → opt-in `ioLogRead` → `admitWithDetection(…, IO_LOG_READ, READ, {CLI})` → deadline
  `cliTimeout` → credentials → port `list` → `module` filter → ≤ 500 entries, `total`, `matching`,
  `truncated`, hints (empty listing text of contracts; unparsable entries) → INFO line. Failing
  tests first in `application/nodetools/IoLogServiceTest.java` (US2-1, US2-4, US2-6, US2-7; filter by
  name and by id; 501 entries → 500 + `truncated`; every input refusal → nothing sent; leak check)
- [X] T029 [US2] `IoLogService` message read (FR-021 … FR-023, D-9; after T028): with `message`:
  deadline `cliExportTimeout`, port `read`; `NoMatch` → `INVALID_INPUT` (contracts text, US2-3);
  `Message` → `EntryText` (> 10 MiB → `tooLarge`, binary → `detected`, text UTF-8 / ISO-8859-1,
  masked, cut at `maxChars`, `textChars`), 0 bytes → `kind: empty` + hint (US2-5); result fields of
  contracts; INFO line with ref and size only. Failing tests first in `IoLogServiceTest.java`
  (US2-2, US2-3, US2-5; the `<password>` element and a configured password of another node masked
  before the cut; Latin-1; binary; > 10 MiB generated; `maxChars` 1 and 100,000; leak check incl.
  the log line)
- [X] T030 [US2] Tool, schemas and wiring (FR-001, FR-003, D-6, D-25; after T029):
  `mcp/tools/ReadIoLogTool.java` (`ToolHints.readOnly("Read INUBIT module input/output logging",
  true)`, description of contracts), `src/main/resources/schemas/read_io_log.{input,output}.json`,
  `Wiring.java` announcement and `allowed()` (`IO_LOG_READ` with `withClient && ioLogRead`).
  Failing tests first in `mcp/tools/ReadIoLogToolTest.java` (schema: separators and control
  characters in `workflow`, bad `message`, `maxChars` 0/100001 refused; description phrases incl.
  "business and personal data" and "a right of the configured user" (T018 spike: the listing is refused without it; the outdated "no right beyond the login" statements for `read_io_log` in spec, research D-25, contracts are corrected in this task)), `ToolAnnouncementTest.java`, new
  `ReadIoLogWiringTest.java` (no opt-in → nothing sent), `MainTest.java` (unchanged without
  opt-ins; runs after T023 — same file)
- [X] T031 [US2] Contract test (after T030): `NodeToolsV81ContractTest` `+` every `v8_1/io-log` case
  (listing shape, empty-listing hint, text masked and cut, Latin-1, binary, empty, `no_match` →
  `INVALID_INPUT`, foreign process → `UNEXPECTED_RESPONSE`, `list_error`)
- [ ] T032 [US2] **[OWNER]** **[BLOCKED: T018]** Enable 8.1 `io_log_read` in **one commit**: as
  T025 for the T018 `ioLog` output: the recorded listing and message are replaced by fictitious
  ones of the same shape and size (entries, module names and ids, message content), recorded sizes
  adjusted; `v8_1/io-log/listing.*` and `read_text.*` (plus `read_empty.*` if seen), `.origin`
  `recorded …`; **the owner reviews the fixture diff before the commit**; in the same commit 8.1
  `IO_LOG_READ` → `SUPPORTED` with the same test updates as T025. If T018 shows that the listing
  keys on another process id than `query_logs` returns, stop and ask the owner (spec gap)

**Checkpoint**: build green; US1 and US2 work independently; code review of Phase 4.

## Phase 5: User Story 3 – Read harmless node configuration values (P2)

**Goal**: `get_node_config` returns the allowlisted values of the addressed node; no other name is
ever sent (FR-030 … FR-032, SC-003).
**Independent test**: with a fake INUBIT client, each listed name returns its value; a name outside
the list is refused before any request; the fake records that only listed names were sent.

- [X] T033 [US3] Reconstructed node-config fixtures (D-26; after T012, T026 — same catalogue test):
  `src/test/resources/fixtures/node-tools/v8_1/node-config/{all,absent,one_error,api_missing}.{frames,exit,origin}` (T038: `all` recorded; the former reconstructed `all` is `secret_value`)
  (fictitious values: `CLUSTER`, `acme-cluster`, `zone-a`, `node1`, `java:/comp/env/jdbc/IBISDB`, …;
  `absent` in the form 8.1.17 really answers a property the node lacks (T018 spike: `config <name> error
  com.inubit.ibis.utils.InubitException` wrapping `Cannot invoke "Object.getClass()" because "o" is null`;
  `noOfRetries`/`retryInterval` exist on 8.1); one value containing a credential-looking
  text to prove the defence-in-depth masking); `NodeToolsFixtureCatalogueTest` extended
- [X] T034 [US3] `adapter/cli/node/CliNodeConfigAdapter.java` + config rules in
  `NodeFramesClassifier` (D-10; after T027 — same `NodeFramesClassifier` — and T033): `implements NodeConfigPort`; request `names` only
  from `NodeConfigName`; frames → `NodeConfigValue` in request order; a `config <name> error` with exactly the 8.1.17
  missing-property shape (InubitException wrapping the `"o" is null` NPE; T018) → `Absent`, any other
  `config error` → the per-name failure; research D-10 updated in this task; a frame for a name not
  requested → `UNEXPECTED_RESPONSE`. Failing tests first in
  `adapter/cli/node/CliNodeConfigAdapterTest.java` (every v8_1 case; the request's `names` equals
  the requested enum values; leak check)
- [X] T035 [US3] `application/nodetools/NodeConfigService.java` (FR-030 … FR-032; after T017,
  T034): validate names (unknown → `INVALID_INPUT` listing the allowed names, the refused text never
  echoed; duplicates refused) → server → opt-in `nodeConfigRead` → `admitWithDetection(…,
  NODE_CONFIG_READ, READ, {CLI})` → deadline `cliTimeout` → credentials → port (values already masked and cut to 2,000 with
  `cut` by the T034 adapter; the service does not mask or cut again) → result (no names → all 20 in list order) → INFO line. Failing tests
  first in `application/nodetools/NodeConfigServiceTest.java` (US3-1…US3-5; `Database`,
  `ClusterPassword`, `initialRootUserPassword`, `IdentityProvider`, an empty string, a 10,000-char
  text, a name with different case → `INVALID_INPUT` and **nothing sent** (SC-003); masked value;
  one failed name; leak check)
- [X] T036 [US3] Tool, schemas and wiring (after T035): `mcp/tools/GetNodeConfigTool.java`
  (`ToolHints.readOnly("Read INUBIT node configuration values", true)`),
  `src/main/resources/schemas/get_node_config.{input,output}.json` (the `enum` equals
  `NodeConfigName`), `Wiring.java` announcement and `allowed()` (`NODE_CONFIG_READ`). Failing tests
  first in `mcp/tools/GetNodeConfigToolTest.java` (schema enum ⇔ `NodeConfigName.values()`; a
  non-listed name fails the schema; description lists the 20 names), `ToolAnnouncementTest.java`,
  new `GetNodeConfigWiringTest.java` (no opt-in → nothing sent), `MainTest.java` (after T030)
- [X] T037 [US3] Contract test (after T036): `NodeToolsV81ContractTest` `+` every
  `v8_1/node-config` case (`all` reconstructed until T038; `absent` in the 8.1.17 NPE form → absent; `one_error`; `api_missing`)
- [X] T038 [US3] **[OWNER]** **[BLOCKED: T018]** Enable 8.1 `node_config_read` in **one commit**:
  as T025 for the T018 `nodeConfig` output (values replaced by fictitious ones of the same type;
  display names, descriptions and categories kept only if they are generic vendor texts, else
  replaced); `v8_1/node-config/all.*` recorded; **owner reviews the fixture diff**; 8.1
  `NODE_CONFIG_READ` → `SUPPORTED`, same test updates as T025

**Checkpoint**: build green; US1–US3 independent; code review of Phase 5.

## Phase 6: User Story 4 – Raise a log level on one node for a limited time (P2)

**Goal**: `set_temporary_log_level` / `reset_temporary_log_level` as write tools with leases that
are reset at expiry, shutdown and start, also with write tools off (FR-040 … FR-045, SC-004).
**Independent test**: with a fake StartCLI, a fake INUBIT client, a fake clock and a fake
liveness, a set raises the levels and records a lease; expiry, shutdown or the next start resets
the node; refusals happen before any change; two fake instances on one store never end a lease
the other just changed.

- [ ] T039 [US4] **[LIVE] [OWNER]** 8.1 log-level write spike (D-28 step 2; may start after T001): the
  agent proposes the **concrete action** — the non-production 8.1 test server, the logger
  `com.inubit.ibis.cli` at `INFO` (or the owner's choice), StartCLI `trace --nodelevel INFO
  --package 'com.inubit.ibis.cli'` followed right after by `trace --reset` with the configured 8.1
  client, the private output folder — and **waits for the owner's explicit approval of exactly
  that action**; get_health `systemInfo` (tracing state and level) before and after; no other
  `trace` form is ever run. Records stdout, stderr, exit code of both runs owner-only outside the
  repository; appends the neutral facts (the confirmation form of `CommandResult("OK")`, whether
  the long option names are accepted, exit codes, persistent trace state unchanged) to research.md
  "Spike findings"; then aligns spec.md FR-007 and the trace forms named in the contracts' tool
  descriptions with the confirmed spelling (T040 review)
- [X] T040 [P] [US4] `adapter/cli/CliCommand.java` `trace` forms (FR-007, D-11):
  `traceNodeLevel(TemporaryLevel, LoggerName)` → `trace --nodelevel <L> --package '<logger>'`,
  `traceReset()` → `trace --reset`; options `--nodelevel` (value from the enum only), `--package`
  (a `LoggerName` only, quoted), `--reset` (flag); `build()` refuses for `trace` every combination
  other than the three forms (`--download` alone, `--nodelevel`+`--package`, `--reset` alone).
  Failing tests first in `adapter/cli/CliCommandTest.java` (exact `--execCommand` texts, POSIX and
  Windows quoting; refused before any launch: `on`, `off`, `--level`, `-l`, `-nl`/`-p`/`-r` raw,
  `--nodelevel` without `--package` and vice versa, `--reset` with another option, `--download`
  with `--reset`, a positional argument, a process id; the 021 `traceDownload` text unchanged)
- [X] T041 [P] [US4] StartCLI classification and fixtures (D-11, D-20):
  `adapter/cli/CliOutputClassifier.java` `+ classifyTraceLevel(CliResult)` (success form
  reconstructed as `1-OK:` until T039 pins it; `Missing log level.` → `UNEXPECTED_RESPONSE`;
  otherwise `classify`); `src/test/resources/fixtures/node-tools/v8_1/log-level/{set_ok,reset_ok,missing_level,login_failure,forbidden}.{stdout,stderr,exit,origin}`
  (reconstructed); `NodeToolsFixtureCatalogueTest` extended (after T033, same test file). Failing
  tests first in `adapter/cli/CliOutputClassifierTest.java` (each case; the export and
  trace-download contexts unchanged; leak check)
- [X] T042 [US4] `adapter/cli/node/CliLogLevelAdapter.java` (D-1, D-3, D-11; after T016, T040,
  T041): `implements LogLevelPort`: `answeringNode` via `NodeHelperRunner` (`op=ownNode`), `raise`
  and `resetAll` via `CliRunner.run(…, CliCommand.traceNodeLevel/traceReset, timeout, "cliTimeout",
  guard)` (017 client choice, pin probe, password on stdin) + `classifyTraceLevel`. Failing tests
  first in `adapter/cli/node/CliLogLevelAdapterTest.java` (`ScriptedProcessLauncher`: argv and
  `--execCommand` texts; password only on stdin; one run per call; each v8_1/log-level case → its
  code; `ownNode` frames → name; leak check)
- [X] T043 [P] [US4] Lease domain (pure; D-12 … D-14, data-model "Lease lifecycle"):
  `domain/model/node/LeaseState.java`, `LeaseOwner.java`, `LeaseReason.java` (incl.
  `AUDIT_NOT_WRITABLE`), `LeaseAttempt.java`, `Lease.java` (`generation`; `merge`, `executed`,
  `reset`, `failed`, `forgotten`, `takenOverBy`, each returning generation + 1;
  `sameGeneration`; validation of every field), `LeaseSummary.java` (no owner, no generation).
  Failing tests first in `domain/model/node/LeaseTest.java` (merge: union, last level per logger,
  later expiry, owner := setter; expiry from the executed time; every transition increments the
  generation; transitions allowed only per the lifecycle; invalid server id, node name, logger,
  UUID refused) and `LeaseSummaryTest.java`
- [ ] T044 [P] [US4] Generalize the lock of feature 019 (D-12): new `application/OwnerOnlyFileLock.java`
  (`Held acquire(Path lockFile, Duration wait, Supplier<ToolErrorException> busy)`: in-process
  `ReentrantLock` per lock-file path, then `FileChannel.tryLock` polled every 50 ms until the wait
  covering both steps runs out; lock file created owner-only through `PrivateFileSystem`, never
  opened through a link, never deleted), moved out of `application/knowledge/KnowledgeLock.java`,
  which now delegates with byte-identical texts; no second lock implementation. Failing tests first
  in new `application/OwnerOnlyFileLockTest.java` (two threads; two independent instances on the
  same path serialize; a held lock times out with the caller's error; a symlink at the lock path
  refused; owner-only mode); `application/knowledge/KnowledgeLockTest.java` unchanged and green
- [ ] T045 [US4] `application/nodetools/LeaseStore.java` and `NodeLogLevelLocks.java` (D-12; after
  T043, T044): store JSON format 1 (with `generation`) at
  `<home>/.inubit-mcp/<profile>/log-levels/leases.json`, store lock `OwnerOnlyFileLock` on
  `log-levels/.lock`, owner-only through `PrivateFileSystem`, temporary file + atomic move, links
  refused, unreadable/invalid file → `Unreadable` and never overwritten, retention removal (ended +
  7 days), new `domain/port/LeaseView.java` (read-only snapshot `List<Lease>` or `Unreadable`,
  for `HealthService` and `ConfigSummary`) which `LeaseStore` implements, created only on the
  first write; `NodeLogLevelLocks` =
  `OwnerOnlyFileLock` on `log-levels/nodes/<group>.<node>.lock` (cross-process, per server; busy →
  `PRECONDITION_FAILED` "another log-level change for test/node1 is running"). Failing tests first
  in `application/nodetools/LeaseStoreTest.java` (round trip incl. generation; `rw-------`/`rwx------`;
  concurrent `update` from two threads and from **two `LeaseStore` instances on the same directory**
  serialized, no update lost; a corrupt file → `Unreadable`, file unchanged after an update attempt;
  a symlink refused; retention; read without a file → empty, no file created) and
  `NodeLogLevelLocksTest.java` (two instances on one directory exclude each other per node, not
  across nodes; wait bound; the node lock is always taken before the store lock — a test holding
  the store lock never waits for a node lock)
- [ ] T046 [P] [US4] `application/nodetools/ProcessLiveness.java` (+ `SystemProcessLiveness`) (D-13):
  `self()` from `ProcessHandle.current()`; `alive(owner)` per D-13 (pid alive and start instants
  equal within 1 s when both known). Failing tests first in
  `application/nodetools/ProcessLivenessTest.java` (self alive; a finished child process not alive;
  same pid with another start time not alive; unknown start time → pid decides)
- [ ] T047 [P] [US4] Write guard and challenge overloads (FR-040, FR-044, D-15, D-18, D-19): if T001
  recorded 022 as merged, use its `ChangeGuard` instead of the overloads and note it; else
  `application/WriteGuard.java` `+ admit(String node, String tool, Capability capability,
  Set<Channel> channels, Predicate<NodeId> optIn, String optInKey)` (order: target, production
  lock, `CLIENT` on production, `write.enabled`, opt-in → `NOT_CONFIGURED`, gate, client check;
  texts name the tool) and `+ admitWithoutContact(String node, String tool, Predicate<NodeId>
  optIn, String optInKey)` (the same configuration checks up to the opt-in; **no version gate, no
  client check**; for forget); the `ProcessAction` overload delegating with byte-identical texts;
  `application/WriteChallengeRegistry.java` `+ issue(String tool, NodeId node, …)` and `+
  redeem(String code, String tool, NodeId node, String fingerprint)`. Failing tests first in
  `application/WriteGuardTest.java` (every existing restart/kill expectation unchanged; the new
  overload's order and texts; `NOT_CONFIGURED` only after the write checks; `admitWithoutContact`
  refuses production without opt-in, `write.enabled: false` and a missing opt-in, and admits a
  node pinned to 6.1 or without client home without calling the gate or the gateway (fake gate and
  gateways record zero calls); nothing sent) and `application/WriteChallengeRegistryTest.java` (a
  node/tool code does not confirm another tool, node, fingerprint or an `Operation` code; single
  use; expiry)
- [ ] T048 [P] [US4] `application/nodetools/LogLevelAudit.java` (D-22, Constitution VII): tool
  records (`PREVIEW`/`EXECUTE`, `CHALLENGE_ISSUED`, `PENDING`, `EXECUTED`, `FAILED`, `REFUSED`;
  inputs in fixed order; code as SHA-256 prefix; details) and automatic-reset records (`trigger`,
  `leaseId`, `PENDING` then outcome and reason; a failed `PENDING` write is reported to the caller,
  never swallowed). Failing tests first in `application/nodetools/LogLevelAuditTest.java` (field
  order; no logger values beyond names and levels; `PENDING` write failure surfaces as an
  exception; leak check)
- [ ] T049 [US4] `application/nodetools/LogLevelService.set` (FR-040 … FR-043, FR-045, D-14, D-19;
  after T042, T045–T048): the 12 steps of D-14 (input checks, write guard + opt-in + gate, **node
  lock** from step 3 to step 12, `RESET_FAILED` refusal, own-node lookup + other-node refusal,
  confirmation, `PENDING`, write-ahead lease (generation + 1), StartCLI runs per logger, lease
  update with expiry from the executed change, immediate reset on a failed run or a failed update,
  `EXECUTED` + result). Failing tests first in
  `application/nodetools/LogLevelServiceSetTest.java` (fake port, fake clock, fake store: US4-1
  incl. expiry counted from the change and the result texts; US4-5 every input refusal → nothing
  sent; US4-6 other node → refused naming both, nothing changed; US4-7 write disabled / production /
  no opt-in → existing refusal or `NOT_CONFIGURED`, nothing sent; US4-11 `RESET_FAILED` → refused;
  `SERVER` confirmation: challenge (node lock released), redeem, changed answering node or lease
  generation between preview and execution → `PRECONDITION_FAILED`; `CLIENT` → first call; merge
  of two sets (edge case); node lock held by another instance → `PRECONDITION_FAILED`, nothing
  sent; a failed 2nd run → reset at once, lease `RESET`, `FAILED`; store update failure after the
  change → reset at once, `INTERNAL`; `PENDING` unwritable → `INTERNAL`, nothing sent; write-ahead
  failure → nothing sent; leak check)
- [ ] T050 [US4] `LogLevelService.reset` and forget (FR-044, D-15; after T049): `node` form (write
  guard, node lock, own-node lookup, other-node refusal, confirmation, `PENDING`, `trace --reset`,
  lease `RESET`, result with `leaseOpen`), `leaseId` + `forget` form through
  `WriteGuard.admitWithoutContact` (no request; state must be `RESET_FAILED`; unconfigured server
  refused; after the confirmation the node lock and a compare-and-set on the generation the
  confirmation saw; `FORGOTTEN`; warning), every other input combination → `INVALID_INPUT`. Failing tests
  first in `application/nodetools/LogLevelServiceResetTest.java` (US4-4, US4-8, US4-9; **US4-6**
  another node answers than the open (or failed) lease names → refused naming both, no `trace
  --reset`; **US4-7** for reset and for forget: `write.enabled: false`, production without
  `write.productionOptIn`, no `logLevelWrite` → existing refusal or `NOT_CONFIGURED`, nothing
  sent; forget of an `ACTIVE` lease, unknown id, unconfigured server → `INVALID_INPUT`; forget sends
  nothing (zero launches, zero gate calls); forget while another instance holds the node lock →
  `PRECONDITION_FAILED`, lease unchanged; lease changed between the confirmation and the forget
  (generation) → `PRECONDITION_FAILED`, nothing forgotten; reset failure keeps the lease and records `lastAttempt`;
  leak check)
- [ ] T051 [US4] `LogLevelService.cleanup` for one lease (FR-043, D-16 "Cleanup of one lease",
  D-22; after T050): node lock (busy → skipped), generation re-check under the store lock (changed
  or no longer open → nothing sent), cleanup preconditions **without** write guard, fail-closed
  `PENDING` audit, own-node lookup, `trace --reset` within one `cliTimeout` deadline,
  compare-and-set to `RESET` / `RESET_FAILED` with reason, final audit. Failing tests first in
  `application/nodetools/LogLevelServiceCleanupTest.java` (fake port, store, audit, clock: expired
  lease → reset, `RESET`, audit `PENDING` + `EXECUTED`; **write tools off** (`write.enabled:
  false`, opt-in removed, production opt-in removed) → cleanup still runs (owner decision); missing
  client or credentials → `RESET_FAILED` `PRECONDITION`, nothing sent, **one audit record
  `REFUSED` with the reason** (and, if that record cannot be written, an ERROR and reason
  `AUDIT_NOT_WRITABLE`); compare-and-set failing in the final step → outcome not written, final
  audit record "lease changed meanwhile"; other node answers →
  `RESET_FAILED` `OTHER_NODE_ANSWERS`, no StartCLI run; reset failure → `RESET_FAILED`
  `NODE_UNREACHABLE`; **audit `PENDING` not writable → nothing sent, state unchanged with reason
  `AUDIT_NOT_WRITABLE`, ERROR logged**; **set during the reset decision**: a set merges into the
  lease after the cleanup was decided (generation g → g + 1) → the cleanup sends nothing and the
  lease stays `ACTIVE` with the new expiry and loggers; a set holding the node lock → the cleanup
  is skipped without contacting INUBIT; leak check)
- [ ] T052 [US4] `application/nodetools/LeaseScheduler.java` (FR-043 … FR-045, SC-004, D-16; after
  T046, T051): tick every 30 s (first at start); decisions under the store lock (cleanup, takeover
  with generation + 1 and audit, `FORGOTTEN` 7 days after expiry for unconfigured servers with
  audit, retention), cleanups outside it per node in parallel; busy store lock → tick skipped;
  `shutdown(Duration)` idempotent. Failing tests first in
  `application/nodetools/LeaseSchedulerTest.java` (fake clock, fake liveness, fake port: US4-2
  reset within one tick after expiry; US4-3 and the creator decision: orphaned expired lease reset
  at start, orphaned unexpired lease taken over at once and reset at its expiry; `RESET_FAILED`
  retried **60 s after its last attempt** (not on the 30 s tick in between; at most two audit
  records per minute and lease); `AUDIT_NOT_WRITABLE` and precondition refusals retried after 60 s
  as well; US4-10 unconfigured server →
  `FORGOTTEN` after 7 days, audited, startup warning until then; a live other owner's lease
  untouched; ended leases removed after 7 days) and new
  `application/nodetools/LeaseTwoInstanceTest.java` (**two fake instances**, each with its own
  `LeaseStore`, `NodeLogLevelLocks`, `LogLevelService`, scheduler and `ProcessLiveness` identity, on
  the same directory: instance A sets, instance B's tick sees A alive and leaves the lease; A
  merges a set while B decides a takeover after A "died" → B's cleanup sends nothing for the
  changed generation and ends the lease only on its next tick at the new expiry; both ticks at once
  → exactly one `trace --reset` per expiry; no lease lost or duplicated)
- [ ] T053 [US4] Shutdown and startup integration (FR-043, FR-045, D-17; after T052 and T036 — same `Wiring.java`/`MainTest.java`): `Wiring.java`
  creates `LeaseStore`, `NodeLogLevelLocks`, `LeaseScheduler` (started after construction),
  `resetLeasesOnShutdown()` (idempotent; `cleanup` with trigger `shutdown` for own open leases,
  parallel, bounded by the largest `cliTimeout`), the shutdown hook runs it **before**
  `cliResources.close()`; `Launcher.java` calls it after stdin closed **before**
  `wiring.stopCliWork()` and logs the startup lease lines (INFO count, WARN per `RESET_FAILED` lease
  and per lease of an unconfigured server). Failing tests first in new
  `LeaseShutdownWiringTest.java` (stdin closed → resets of own `ACTIVE` leases happen before CLI
  work is stopped; parallel; bounded by the largest `cliTimeout`; a hanging reset leaves the lease
  `ACTIVE`; audit not writable at shutdown → nothing sent, lease kept; the hook path does the same
  once), `MainTest.java` (startup log lines with a seeded store; without a store file no lease line
  and no file created)
- [ ] T054 [US4] Tools, schemas and wiring (FR-001, FR-003, FR-040, D-6, D-25; after T053, same
  `Wiring.java` and `MainTest.java`): `mcp/tools/SetTemporaryLogLevelTool.java`,
  `ResetTemporaryLogLevelTool.java` (`ToolHints.destructive(…)`, descriptions of contracts),
  `src/main/resources/schemas/set_temporary_log_level.{input,output}.json`,
  `reset_temporary_log_level.{input,output}.json` (`oneOf` of the two reset forms),
  `Wiring.java` announcement (effective write access + `logLevelWrite` + capable + client) and
  `allowed()` (`LOG_LEVEL_WRITE`). Failing tests first in
  `mcp/tools/SetTemporaryLogLevelToolTest.java`, `ResetTemporaryLogLevelToolTest.java` (schemas:
  logger pattern, 6 loggers, duplicate, level, duration 0/61, reset combinations; output examples
  validate; description phrases: the FR-003 sentence "unsupported, undocumented INUBIT
  functionality", "without vendor support or stability guarantee", "for diagnosis only", and
  "every temporary log level on that node, also those other users set"),
  `ToolAnnouncementTest.java` (not offered without write access or without the opt-in; offered with
  both), new `LogLevelWiringTest.java` (write disabled → `WRITE_DISABLED`, nothing sent),
  `MainTest.java` (after T036)
- [ ] T055 [US4] `get_health` leases (FR-045, D-23; after T054, same `Wiring.java`):
  `domain/model/HealthReport.java` `+ temporaryLogLevels` (`LeaseSummary`), `application/HealthService.java`
  reads `LeaseView` (wait ≤ 2 s; failure → report warning), `Wiring.java` passes the `LeaseStore`
  (as `LeaseView`) into `HealthService`, `src/main/resources/schemas/get_health.output.json`
  `+ $defs/LeaseSummary`. Failing tests first in `application/HealthServiceTest.java` (open and
  failed leases shown per node, ended ones not; shown with write tools off; unreadable store →
  warning, no error; absent field without leases), `mcp/tools/GetHealthToolTest.java` (output
  validates; golden output without leases unchanged) and a wired test in `LogLevelWiringTest.java`
  (a seeded store's lease appears in `get_health` through the real `Wiring`, also with
  `write.enabled: false`)
- [ ] T056 [US4] `--check-config` and summary leases (FR-045, D-23; after T055, same `Launcher.java`
  and `MainTest.java`): `config/ConfigSummary.java` section `Temporary log levels:`
  (contracts/configuration-delta.md; read-only; unconfigured servers with the forget date;
  unreadable store warning), `Launcher.java` `--check-config` passes the store read-only. Failing
  tests first in `config/ConfigSummaryTest.java` (no store → no section, golden texts unchanged;
  seeded store → lines; unconfigured server; unreadable store) and `MainTest.java` `--check-config`
  (no reset, no takeover, store file unchanged)
- [ ] T057 [US4] Contract test (after T056): `NodeToolsV81ContractTest` `+` every `v8_1/log-level`
  case through `CliLogLevelAdapter` + `LogLevelService` (test matrix): `set_ok` → lease and result;
  `reset_ok`; `missing_level` → `UNEXPECTED_RESPONSE` and an immediate reset; `login_failure` →
  `AUTH_FAILED`; `forbidden` → `FORBIDDEN`
- [ ] T058 [US4] **[OWNER]** **[BLOCKED: T039, T025]** Enable 8.1 `log_level_write` in **one
  commit**: neutralize the T039 StartCLI outputs (paths, host names, logger → `com.inubit.ibis.acme`)
  into `v8_1/log-level/set_ok.*` and `reset_ok.*` (`recorded …`); if the confirmation form or the
  long option names differ from T040/T041, fix them test-first before the flip; the own-node lookup
  is covered by the recorded `ownNode`/`clusterNodes` frames of T025; **owner reviews the fixture
  diff**; 8.1 `LOG_LEVEL_WRITE` → `SUPPORTED`, same test updates as T025

**Checkpoint**: build green; US4 end to end offline incl. cleanup, scheduler, two instances,
shutdown and startup; code review of Phase 6.

## Phase 7: User Story 5 – Right implementation per INUBIT version (P3)

**Goal**: every tool uses the client of the node's version on 8.1 and 9.0 and is refused on 6.1,
versions above 9.0, undetectable versions and "not yet verified" versions (FR-005).
**Independent test**: one profile with 8.1, 9.0 and 6.1 nodes; each tool uses the matching client
on 8.1 and 9.0 and is refused on 6.1 and unknown versions before any request.

- [ ] T059 [US5] Version selection across the five tools (US5-1 … US5-3; after T054):
  new `application/nodetools/NodeToolsVersionSelectionTest.java` (test matrix: 8.1 → 8.1 client and
  `SOAP_81`, 9.0 → 9.0 client and `REST_90` for the helper and the 9.0 StartCLI for the log level;
  6.1, 9.1, undeclared → `UNSUPPORTED_VERSION` naming the supported lines, nothing sent; an
  undetectable version → the detection's code, nothing sent; production matrix → "not yet verified"
  for every unflipped (version, capability)); fixes in adapters or services test-first
- [ ] T060 [US5] **[LIVE] [OWNER]** 9.0 read spike (as T018 against the non-production 9.0 test
  server with the 9.0 client on Java 21; the throw-away runner compares the version get_health
  reported just before with 9.0 and refuses a mismatch); findings appended to "Spike findings"
- [ ] T061 [US5] **[LIVE] [OWNER]** 9.0 log-level write spike (as T039 for 9.0; the owner approves
  exactly that action)
- [ ] T062 [US5] **[OWNER]** **[BLOCKED: T060]** Enable 9.0 `cluster_nodes_read` in **one commit**:
  neutralize as T025 into `v9_0/cluster-nodes/ok.*`; new `adapter/cli/node/NodeToolsV90ContractTest.java`
  (all v9_0 cluster-nodes cases incl. reconstructed ones and the REST client's stdout noise outside
  frames); **owner reviews the fixture diff**; 9.0 `CLUSTER_NODES_READ` → `SUPPORTED`; catalogue,
  matrix, announcement and `MainTest` updated first
- [ ] T063 [US5] **[OWNER]** **[BLOCKED: T060, T062]** Enable 9.0 `io_log_read` in **one commit** (as
  T032 for `v9_0/io-log`; `NodeToolsV90ContractTest` extended)
- [ ] T064 [US5] **[OWNER]** **[BLOCKED: T060, T062]** Enable 9.0 `node_config_read` in **one
  commit** (as T038 for `v9_0/node-config`; `noOfRetries`/`retryInterval` present on 9.0)
- [ ] T065 [US5] **[OWNER]** **[BLOCKED: T061, T062]** Enable 9.0 `log_level_write` in **one commit**
  (as T058 for `v9_0/log-level`)
- [ ] T066 [US5] **[OWNER]** FR-005 fallback (only for a 9.0 capability whose recording could not be
  made): it stays `UNSUPPORTED` "not yet verified"; `NodeToolsVersionSelectionTest` and the docs
  state it; the owner confirms; noted in `reviews/implementation.md`

**Checkpoint**: build green; version behaviour proven per capability; code review of Phase 7.

## Phase 8: Polish & cross-cutting concerns

### Live check (gates the feature; SC-001, SC-002, SC-004; D-29)

- [ ] T067 Live test code (offline part; after T054): `live/NodeToolsLiveTest.java` (`@Tag("live")`,
  `-Plive`, production refused by `live/LiveTarget.java`; `INUBIT_LIVE_NODE`, optional
  `INUBIT_LIVE_IO_PROCESS=<workflow>/<processId>`, `INUBIT_LIVE_LOG_LEVEL=approved` for the write
  part; skipped without them; read tools through the wired server: node list < 10 s with the
  answering node, details masked, configuration values, the I/O listing and one message; write
  part: set one logger at `INFO` for 1 minute, `get_health` shows the lease, the automatic reset
  within 2 minutes, lease `RESET`, audit records present; prints `[live] …` lines without values),
  `live/NodeToolsLiveTestSupport.java`; `docs/live-tests.md` section "Unsupported node tools
  (feature 024)" plus a row in the tests table. Failing tests first in
  `live/NodeToolsLiveTestSupportTest.java` (env parsing; the write part never runs without the
  flag; production refused)
- [ ] T068 **[LIVE] [OWNER]** 8.1 live check (quickstart "Live check" steps 1–4; after T025, T032,
  T038, T058, T067): after the operator approved the concrete run (non-production 8.1 test node of
  an opted-in test group with write access in a private copy of the profile; the write part only
  with the owner's approval of that run), `mvn -Plive -Dtest=NodeToolsLiveTest verify`; by hand:
  the persistent trace state unchanged (get_health `systemInfo`), the MCP server log contains no
  value or message text; result rows in the quickstart validation record (fictitious ids only)
- [ ] T069 **[LIVE] [OWNER]** 9.0 live check (as T068 for every 9.0 capability enabled; after
  T062–T065 or T066, T067)

### Documentation (D-31)

- [ ] T070 [P] `docs/setup.md` (FR-003, FR-042, FR-043; after T005): `### Unsupported functions` rows
  and example for the four keys and the verbatim text of contracts/configuration-delta.md
  (incl. the cleanup-reset exception and the "address one node directly" precondition); the
  validation texts; section 6 summary examples with the new group parts and the `Temporary log
  levels:` section. Failing test first: new `config/NodeToolsDocsTest.java` (the bold sentences and
  the production-warning texts present)
- [ ] T071 [P] `docs/tools.md` (FR-003, D-25, D-31; after T054 and the flips or T066): sections
  `## list_cluster_nodes`, `## read_io_log`, `## get_node_config`, `## set_temporary_log_level`,
  `## reset_temporary_log_level` (each starting with the tool description and a link to setup
  "Unsupported functions"; inputs with limits; outputs and variants; bounds; masking and its
  limits; errors tables of the contract; "only the exactly named logger changes"; "the reset
  restores every temporary level on that node, also other users'"; leases, automatic resets and
  `get_health` `temporaryLogLevels`); capability table rows (8.1 / 9.0 state as in the matrix);
  announcement rules; `list_nodes` names. `NodeToolsDocsTest` extended first (short forms in the
  tool descriptions and docs; the documented 8.1/9.0 states match `CapabilityMatrix.production()`;
  the 20 configuration names listed equal `NodeConfigName`)
- [ ] T072 `README.md` tool table rows for the five tools and announcement rows; `SECURITY.md`: I/O
  messages (business and personal data; masking limits), the lease store (owner-only; names and
  times only), the cleanup resets while write access is off (owner-approved exception), the
  answering-node lookup through an unsupported interface; `NodeToolsDocsTest` asserts the README
  rows (after T071, same test file)
- [ ] T073 [P] `CHANGELOG.md` `[Unreleased]`: Added — the five tools (unsupported, opt-in per group,
  diagnosis only; the log-level tools as write tools with automatic reset), the four
  `unsupported.*` keys, `get_health` `temporaryLogLevels`, `list_nodes` names, the summary parts
  and the `Temporary log levels:` section; the 8.1 / 9.0 state per capability; Changed (internal) —
  `EntryText` extracted from `read_queue_entry`, `WriteGuard`/`WriteChallengeRegistry` overloads
- [ ] T074 Feature 022 integration (D-18; only if 022 is merged before this feature, else noted in
  `reviews/implementation.md` as 022's task): rebase; `src/main/resources/coverage/interface-coverage.tsv`
  rows `trace -nl` (reachable, `set_temporary_log_level`), `trace -r` (reachable,
  `reset_temporary_log_level`), `cachemode nodelist` (not used: answering node via the helper);
  `CliCommand` `trace` forms merged with 022's area A (never offered by these tools); the log-level
  guard on 022's `ChangeGuard`; 022's coverage tests and `docs/interface-coverage.md` regenerated

### Full verification

- [ ] T075 Full verification (after all other tasks): `mvn -B clean verify
  -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-024-tmp` and `python3 -I -m unittest discover -s
  tools/tests -v` green; `python3 -I tools/check-identifiers.py --all` (exit 0) and
  `NoCustomerIdentifiersTest` with the private lists green; `unzip -l target/inubit-mcp-server-*.jar`
  shows exactly one `ClientHelper.class`; `git diff --stat main...HEAD --
  src/test/resources/fixtures/queue-entry src/test/resources/fixtures/server-log` empty; SC-005
  against the T001 reference (tool lists differ only by tools whose opt-in is set; no test
  removed); quickstart offline table checked row by row; final code review over `git diff
  main...HEAD`; findings in `reviews/implementation.md`

## Dependencies

- **Phase 1**: T001 first; T002 any time after T001.
- **Phase 2 blocks every story.** T003 ∥ T004 → T005 (needs both). T006 ∥ T007 → T008 (the
  request types use `NodeConfigName` and `IoMessageRef`); T008 → T011 → T012 → T013 → T014 (same
  helper file); T009 ∥ T010 ∥ T008 (T011 needs T010). T015 needs T006, T007; T016 needs T008, T009,
  T015. T017 any time after T001.
- **US1** (after Phase 2): T019 (needs T011) → T020 → T021 → T022 → T023 → T024; T025 **blocked by
  T018** and needs T024.
- **US2** (after Phase 2 and US1's T019/T023): T026 (needs T013, T019; same catalogue test) →
  T027 (same `NodeFramesClassifier` as T020) → T028 → T029 → T030 (after T023: `MainTest.java`,
  `Wiring.java`) → T031; T032 **blocked by T018**, needs T031.
- **US3** (after US2's T026/T027/T030): T033 (needs T012; after T026, same catalogue test) → T034
  (after T027, same `NodeFramesClassifier`) → T035 → T036 (after T030) → T037; T038 **blocked by
  T018**, needs T037.
- **US4** (after Phase 2): T040 ∥ T043 ∥ T044 ∥ T046 ∥ T047 ∥ T048; T041 after T033 (catalogue test);
  T042 needs T016, T040, T041; T045 needs T043, T044; T049 needs T042, T045–T048; then strictly
  T049 → T050 → T051 → T052 → T053 → T054 → T055 → T056 → T057 (T053–T056 share `Wiring.java`,
  `Launcher.java` and `MainTest.java`; T053 after T036, the last US3 task touching them); T058 **blocked by T039** and needs
  T057 and T025.
- **US5** (after US1–US4 code): T059 needs T054; T062 **blocked by T060**, needs T059 and T025;
  T063/T064 need T062 and T032/T038; T065 **blocked by T061**, needs T062 and T058; T066 only
  instead of a 9.0 flip.
- **Recordings** T018, T039, T060, T061 need only T001 and the operator's approval (T039/T061: of
  the concrete write action); they run in parallel to the offline work. Until the flips the
  corresponding (version, capability) stays "not yet verified" in the production matrix.
- **Phase 8**: T067 needs T054; T068 needs T025, T032, T038, T058, T067; T069 needs (T062–T065 or
  T066), T067; T070 needs T005; T071 needs T054 and the flips or T066; T072 needs T071; T073 needs
  the flips or T066; T074 only if 022 merged first; T075 needs all others.
- Story completion order: US1 (MVP) → US2 → US3 → US4 → US5. The feature is complete only when
  T025, T032, T038, T058 and T068 are done and, per 9.0 capability, either its flip (T062–T065) or
  T066 (FR-005, SC-001, SC-004).

## Parallel opportunities

- **Phase 2**: T003 ∥ T004 ∥ T006 ∥ T007 ∥ T009 ∥ T010 ∥ T017; then the helper chain T008 → T014
  alongside T015 → T016.
- **US1**: the spike T018 alongside everything; T019's fixtures alongside T020's test writing.
- **US2/US3**: only the services T028 ∥ T035 and the spikes run in parallel; fixtures, classifier
  rules, tools and wiring are sequential (shared `NodeToolsFixtureCatalogueTest`,
  `NodeFramesClassifier`, `Wiring.java`, `MainTest.java`).
- **US4**: T040 ∥ T043 ∥ T044 ∥ T046 ∥ T047 ∥ T048; the spike T039 alongside everything after the
  owner's approval; T049–T057 sequential.
- **US5**: T060 ∥ T061 (operator) alongside US1–US4 work.
- **Polish**: T070 ∥ T071 ∥ T073, then T072.
- Builds always sequential (one `mvn` at a time, shared tmpdir).

## Implementation strategy

- **MVP = Phase 1, Phase 2, Phase 3 (US1, node list)**: on an 8.1 node of an opted-in group (test
  matrix offline; production after T025) `list_cluster_nodes` shows the cluster's view.
- **Increment 2 = US2** (I/O logging), **increment 3 = US3** (configuration values), each enabled
  for 8.1 after its part of the read spike.
- **Increment 4 = US4** (log level with leases): enabled for 8.1 after the approved write spike.
- **Increment 5 = US5**: 9.0 per capability, or the FR-005 fallback.
- **Finish**: live checks, docs, full verification.
- Ask the operator for the read spikes (T018, T060) right after T001 and propose the write spikes
  (T039, T061) as soon as the concrete action can be named, so no recording blocks the offline
  work; each phase ends green and reviewed; owner and live gates are reported when reached, not
  batched at the end.
