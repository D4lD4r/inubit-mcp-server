# Implementation Plan: Unsupported Node Tools (node status, module I/O logging, node configuration, temporary log level)

**Branch**: `024-unsupported-node-tools` | **Date**: 2026-11-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/024-unsupported-node-tools/spec.md` (approved, reviews
r1–r4). Base: `021-server-log-download` (rebased on `main` once 021 is merged, D-30); target
`[Unreleased]` (next minor).

## Summary

Five new tools, each addressing exactly one server, each behind its own group opt-in
(`unsupported.*`, default off) and refused per version until its recording exists:

- **`list_cluster_nodes`** (US1, `unsupported.nodeStatusRead`): the cluster's view of its nodes
  (name, status incl. `UNKNOWN` for nodes that do not answer, version, zone, node ID, uptime) and,
  with `nodeName`, that node's details masked — through the 020/021 helper (`ClientHelper`) with a
  new operation `clusterNodes` (`getNode()`, `getNodes()`, `getNode(NodeName)`; D-1, D-2, D-7).
- **`read_io_log`** (US2, `unsupported.ioLogRead`): the logged input/output messages of one run's
  System Connector and Message Logger Utility modules (≤ 500 listed, total always) and, with a
  message reference from the listing, one message masked and bounded with the 020 rules — helper
  operation `ioLog` (listing via `getObject … inputOutputMessageDetails`, download via
  `MultipartDownloadThread` type `InputOutputLogging`); the reference is a hash of the listed entry
  matched inside the helper, so no caller text reaches a download name (D-8, D-9).
- **`get_node_config`** (US3, `unsupported.nodeConfigRead`): the 20 allowlisted configuration values
  of the answering node — helper operation `nodeConfig` with its own copy of the allowlist (D-10).
- **`set_temporary_log_level` / `reset_temporary_log_level`** (US4, write tools,
  `unsupported.logLevelWrite`): StartCLI's documented `trace --nodelevel <L> --package '<logger>'`
  and `trace --reset`, behind the write guard, the production opt-in and the node's confirmation
  mode (as `restart_process`), after an own-node lookup (helper `ownNode`); every set records a
  **lease** in an owner-only, locked store in the profile's data directory; a scheduler resets at
  expiry (≤ 1 minute), at shutdown (bounded by `cliTimeout`, in parallel) and at the next start,
  takes over orphaned leases and retries failed resets — also when write tools are off (owner
  decision; Constitution I deviation in Complexity Tracking) (D-11 … D-19).
- `get_health` shows open and failed leases per node; startup log and `--check-config` list leases
  of servers no longer configured (D-23).
- Four capabilities (`cluster_nodes_read`, `io_log_read`, `node_config_read`, `log_level_write`):
  8.1 and 9.0 "not yet verified" until the commit that adds the neutralized recording flips them
  (D-4, D-26 … D-28).

## Technical Context

**Language/Version**: Java 21 (server); the helper Java 17 bytecode (`release 17`), JDK classes
only (unchanged rule of 020/021).

**Primary Dependencies**: unchanged (MCP Java SDK, Jackson, logback); no new dependency. The INUBIT
client installation (proprietary) is used at runtime by StartCLI and, through reflection, by the
helper.

**Storage**: the lease store `~/.inubit-mcp/<profile>/log-levels/leases.json` + `.lock` and
per-node lock files `log-levels/nodes/<group>.<node>.lock` (owner-only, atomic replace, leases with
a generation, cross-process file locks through the lock generalized from `KnowledgeLock`; D-12); per helper call a private temporary directory (I/O
listing and message; deleted at the end of the call and on shutdown); audit records in the existing
audit log (log-level tools only, D-22).

**Testing**:
- JUnit 5, AssertJ, WireMock (version detection), `ScriptedProcessLauncher` / `FakeProcessLauncher`
  (helper and StartCLI launches; `onLaunch` writes `.list`/`.message` fixtures), in-process helper
  tests with fake vendor classes under `src/test/java/com/inubit/ibis/…` (021 fakes extended), one
  real child-JVM test per new helper operation, fake clock and fake `ProcessLiveness` for the lease
  scheduler;
- contract tests per version against `src/test/resources/fixtures/node-tools/{v8_1,v9_0}/`
  (recorded, neutralized, fictitious);
- `@Tag("live")` `NodeToolsLiveTest` (opt-in, never production; the write part only with the
  owner's approval flag);
- baseline: the 021 suite green (`mvn -o clean verify`), count recorded in T001.

**Target Platform**: macOS, Linux (live-checked); Windows by construction (argument arrays, `CliPaths`
Windows rule, `java.exe`, `PrivateFileSystem` ACLs; `ProcessHandle` start instants).

**Project Type**: single Maven module, shaded JAR; no new compiler execution (the helper stays one
class).

**Performance Goals**: `list_cluster_nodes` < 10 s on a reachable server (SC-001: one TLS check +
one helper JVM, ≈ 2–4 s); a log-level set = one helper JVM + one StartCLI JVM per logger (≤ 6
JVMs, each ≤ `cliTimeout`); the scheduler tick every 30 s does no INUBIT work without due leases.

**Constraints**:
- FR-007: exactly the listed vendor calls per helper operation (fake-vendor tests record every
  call); exactly three `trace` forms in `CliCommand` (`--download`, `--nodelevel`+`--package`,
  `--reset`); never `on`, `off`, `--level`, never `setNodeConfigurationProperty`;
- SC-003: the 20-name allowlist enforced in schema, service **and** helper; refusals send nothing;
- FR-021: no caller text in an I/O download name (reference hash matched in the helper);
- FR-022/SC-002: 020 masking and bounds on every message, detail and value; no content in logs or
  audit;
- FR-043/SC-004: lease durable before the change (write-ahead), reset ≤ 1 minute after expiry, at
  shutdown before `stopCliWork`, at start; cleanup resets independent of write settings;
- FR-006: `cliTimeout` for listings, values and log-level runs, `cliExportTimeout` for message
  reads; per-node helper slot (`acquireForHelper`); per-node log-level lock;
- `PackageBoundaryTest`: `application` only via ports, `Process*` only in `adapter/cli`, the helper
  only `java/*`;
- customer-neutral artifacts only (fictitious names; `tools/check-identifiers.py`).

**Scale/Scope**:
- 5 new tools, 10 new schemas, `get_health.output.json` (+ `temporaryLogLevels`),
  `list_nodes.output.json` enum + 4;
- new production types ≈ 45 (domain ≈ 20, application ≈ 11, adapter ≈ 7, tools 5, helper changes);
- changed: `ClientHelper`, `HelperRequest`, `HelperFrames`, `UnsupportedConfig`, `Defaults`,
  `ConfigLoader`, `ConfigValidator`, `ConfigSummary`, `EffectiveNodeConfig`, `Capability`,
  `CapabilityMatrix`, `VersionGate`, `NodeOverview`, `CliCommand`, `CliOutputClassifier`,
  `WriteGuard` (overload), `WriteChallengeRegistry` (overloads), `HealthService`/`HealthReport`,
  `Gateway`, `GatewayFactory`, `PortSet` family, `Wiring`, `Launcher`, docs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.* Constitution v1.1.0.

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ⚠️ a tool that changes a running server; cleanup resets while write access is off; internal interfaces that accept any configuration name | ⚠️ one owner-approved deviation (Complexity Tracking): <ul><li>The log-level tools are write tools: offered only with write access, refused without `write.enabled`, without `write.productionOptIn` on production and without `unsupported.logLevelWrite`; annotated destructive; confirmation as `restart_process` (D-14, D-18, D-19).</li><li>**Deviation**: cleanup resets of recorded leases (`trace --reset`) run also when write tools are off, and the reset also clears other users' temporary levels on that node (owner decision 2026-11-12, spec Clarifications). Bounded: only for nodes with a recorded lease, only `trace --reset`, only after the own-node check, audited, shown in `get_health`.</li><li>The read tools change nothing: exactly the listed vendor calls (fake-vendor tests); the configuration read only for 20 allowlisted names, checked in schema, service and helper (D-10, SC-003); no caller text reaches an I/O download name (D-8).</li><li>Every opt-in off by default, `NOT_CONFIGURED` before any request, production warning (D-5).</li><li>All inputs validated before any call; nothing reaches a shell (argument arrays, `--execCommand` via `CliCommand`, helper request on stdin).</li></ul> |
| II. Secrets Stay Out | ⚠️ node configuration holds passwords; messages carry business data and credentials | ✅ <ul><li>Secret-holding configuration names can never be requested (allowlist ×3).</li><li>Every value, detail and message masked with the 020 rules and the known credentials of every configured node before the cut; `ResultMapper` scrubs again (D-21).</li><li>Credentials only on stdin of StartCLI / the helper; never in argv, results, logs, audit, lease store (D-12).</li><li>The docs say masking does not cover business and personal data in I/O messages (FR-003).</li></ul> |
| III. Test-First | ✅ | ✅ every task starts with failing tests (quickstart table); dedicated tests for each refusal path (nothing sent), the allowlist, the three `trace` forms, the lease lifecycle with a fake clock and fake liveness, the shutdown order, the leak checks; offline only; live tests opt-in, production refused. |
| IV. Use-Case-Oriented Tools | ⚠️ five tools | ✅ each has a spec use case (US1–US4); the I/O listing and read are one tool, the node details are the node list with a name (spec Clarifications); typed schemas and documented outputs (contracts/mcp-tools-delta.md); tools are offered only with their opt-in, so the default tool list is unchanged (SC-005). |
| V. Adapter Isolation & Version Compatibility | ⚠️ internal interfaces; no recordings yet | ✅ <ul><li>StartCLI (documented) for the log level; the helper only where no documented channel exists (D-1).</li><li>Version choice in `CapabilityMatrix` and `HelperVariant`, never in tool logic (D-4).</li><li>8.1 and 9.0 supported per capability only with recorded, neutralized contract fixtures; recording and matrix flip in one commit, owner reviews the fixture diff; missing recordings → "not yet verified" (D-26 … D-28) — no deviation.</li><li>Argument arrays, explicit timeouts, exit-code + frame/output evaluation, tested parsers.</li></ul> |
| VI. Bounded, Structured Output | ✅ | ✅ ≤ 200 nodes, ≤ 500 detail pairs, ≤ 500 I/O entries (total always), message text ≤ `maxChars` (≤ 100,000), values ≤ 2,000 chars, explicit truncation flags; actionable errors (contracts). |
| VII. Observability & Auditability | ⚠️ automatic resets happen without a tool call | ✅ every set, reset, forget and automatic reset (expiry, retry, takeover, shutdown, forget after 7 days) is audited with tool, server, node, lease and outcome; **fail closed for automatic resets too**: if the `PENDING` record cannot be written, no reset is sent, the lease keeps its state with reason `AUDIT_NOT_WRITABLE`, an ERROR is logged and the next tick retries — so every reset that reaches INUBIT is audited (D-16, D-22); one INFO line per read call without values. |
| Tech constraints: TLS for CLI connections; server verifies pins itself | ✅ existing | ✅ StartCLI's TLS handling and pin probe unchanged; helper per 020 D-7 (verified leaf only). |
| Tech constraints: language "Java 21 or newer" | ⚠️ the helper runs in the 8.1 client's JDK 17 | ⚠️ existing justified deviation of 020/021, unchanged in kind → Complexity Tracking. |
| Tech constraints: dependencies minimal, single artifact | ✅ | ✅ no new dependency or plugin. |

**Result**: PASS with two justified deviations (the owner-approved cleanup resets while write
access is off; the carried-over helper bytecode Java 17), both in Complexity Tracking.

### Decisions taken in plan review round 1

- **Orphaned unexpired leases at start** (US4-3 vs FR-043): creator decision (owner may override),
  recorded in research D-16 and by T001 in `reviews/implementation.md` — taken over at once (owner
  := the new instance) and reset at their expiry; an expired or failed one is reset at once. US4-3
  is read as "the next start takes the lease over and resets the node no later than its expiry".
- **Audit when the cleanup `PENDING` record fails**: fail closed (orchestrator decision) — no reset
  is sent, the lease keeps its state with reason `AUDIT_NOT_WRITABLE`, ERROR logged, retried next
  tick (D-16, D-22).
- **Two-instance race**: leases carry a generation (compare-and-set) and a cross-process per-node
  lock covers the own-node lookup and the StartCLI runs of set, reset and cleanup (D-12, D-14,
  D-16).

### Open questions for the owner (do not block the plan; defaults chosen)

1. **Log-level write spike action** (D-28): proposed logger `com.inubit.ibis.cli` at `INFO`,
   reset right after; the owner approves the concrete action per version before T039/T061.

## Project Structure

### Documentation (this feature)

```text
specs/024-unsupported-node-tools/
├── plan.md
├── research.md                 # D-1…D-31 (+ "Spike findings" appended by the live tasks)
├── data-model.md
├── quickstart.md               # offline table, early live tasks, live check, validation record
├── contracts/
│   ├── configuration-delta.md
│   ├── helper-protocol-delta.md
│   └── mcp-tools-delta.md
├── checklists/requirements.md
├── reviews/                    # spec-r1…r4, plan reviews, implementation.md
└── tasks.md
```

### Source Code (repository root)

```text
src/helper/java/de/dadecker/inubit/mcp/helper/
└── ClientHelper.java                     # + op=ownNode|clusterNodes|nodeConfig|ioLog
src/main/java/de/dadecker/inubit/mcp/
├── config/              # UnsupportedConfig (+4 keys), Defaults, ConfigLoader, EffectiveNodeConfig,
│                        # ConfigValidator (production warnings), ConfigSummary (group parts, leases)
├── domain/model/        # Capability (+4), CapabilityMatrix, HealthReport (+temporaryLogLevels)
├── domain/model/node/   # new: ClusterNode, NodeStatus, NodeDetail, ClusterNodes, IoDirection,
│                        # IoLogEntry, IoMessageRef, IoLogListing, IoLogBounds, NodeConfigName,
│                        # NodeConfigValue, LoggerName, TemporaryLevel, Lease, LeaseState,
│                        # LeaseOwner, LeaseAttempt, LeaseReason, LeaseSummary, ClusterNodesResult,
│                        # IoLogResult, NodeConfigResult, LogLevelResult
├── domain/port/         # ClusterNodePort, IoLogPort, NodeConfigPort, LogLevelPort, LeaseView (new);
│                        # Gateway, GatewayFactory (+defaults)
├── application/         # WriteGuard (+overload, +admitWithoutContact), WriteChallengeRegistry
│                        # (+node/tool overloads), HealthService (leases), VersionGate (lines, steps),
│                        # NodeOverview (names), OwnerOnlyFileLock (new, generalized from KnowledgeLock),
│                        # EntryText (extracted from QueueEntryService)
├── application/knowledge/ # KnowledgeLock delegates to OwnerOnlyFileLock (texts unchanged)
├── application/nodetools/ # new: ClusterNodeService, IoLogService, NodeConfigService, LogLevelService,
│                        # LeaseStore, LeaseScheduler, ProcessLiveness, LogLevelAudit,
│                        # NodeTextMasking, NodeLogLevelLocks (cross-process, per node)
├── adapter/             # PortSet, V81PortSet, V81Gateway, AdapterGatewayFactory, StaleReportingPorts,
│                        # RefusingPorts (+4 ports)
├── adapter/cli/         # CliCommand (trace --nodelevel/--package, --reset), CliOutputClassifier SystemProcessLiveness (new, D-13)
│                        # (classifyTraceLevel)
├── adapter/cli/helper/  # HelperRequest (+4 operations), HelperFrames (+frame types)
├── adapter/cli/node/    # new: NodeHelperRunner, NodeFramesClassifier, CliClusterNodeAdapter,
│                        # CliIoLogAdapter, CliNodeConfigAdapter, CliLogLevelAdapter
├── mcp/tools/           # ListClusterNodesTool, ReadIoLogTool, GetNodeConfigTool,
│                        # SetTemporaryLogLevelTool, ResetTemporaryLogLevelTool (new)
├── Launcher.java        # lease reset before stopCliWork; lease lines in the startup log
└── Wiring.java          # services, adapters, scheduler, announcement, allowed(), shutdown hook order
src/main/resources/schemas/{list_cluster_nodes,read_io_log,get_node_config,
    set_temporary_log_level,reset_temporary_log_level}.{input,output}.json,
    get_health.output.json, list_nodes.output.json
src/test/java/…          # tests per quickstart; com/inubit/ibis/… fakes extended; live/NodeToolsLiveTest
src/test/resources/fixtures/node-tools/{v8_1,v9_0}/…, io-message-ref.vectors, README.md
docs/setup.md, docs/tools.md, docs/live-tests.md, README.md, SECURITY.md, CHANGELOG.md
```

**Structure Decision**: the same single module. Stages, each ending with a green
`mvn -o -q clean verify` and reviewed:

1. **Configuration and capabilities**: four opt-ins (strict, effective, production warnings,
   summary), four capabilities "not yet verified", gate lines and steps, `NodeOverview`,
   `list_nodes` enum (FR-002, FR-003, FR-005).
2. **Early live tasks (operator)**: 8.1 and 9.0 read spikes (read-only), then the log-level write
   spikes after the owner's approval of the concrete action; findings in research.md, neutralized
   fixtures (D-28). In parallel to stages 3–9.
3. **Helper protocol**: `HelperRequest`/`HelperFrames` + the helper's new `op` names together
   (020/021 encodings byte-identical), then each operation with fake vendor classes and a child-JVM
   round trip (D-2).
4. **Node tools adapter base**: domain types and ports, `NodeHelperRunner`, `NodeFramesClassifier`,
   port wiring (`PortSet` family), `NodeTextMasking` (D-21, D-24).
5. **US1 node list**: adapter, service, tool, schemas, announcement, contract test; 8.1 flip after
   its recording.
6. **US2 I/O logging**: entry parser and reference, adapter, listing and read in the service, tool,
   contract test; 8.1 flip.
7. **US3 configuration values**: allowlist, adapter, service, tool, contract test; 8.1 flip.
8. **US4 log level**: `CliCommand`/classifier, `CliLogLevelAdapter`, lease domain (generation),
   `OwnerOnlyFileLock` extraction, `LeaseStore` and node locks, liveness, write-guard and challenge
   overloads, audit, `LogLevelService` (set, reset, forget, cleanup of one lease),
   `LeaseScheduler`, shutdown/startup integration, tools, `get_health`, `--check-config`, contract
   test; 8.1 flip after the write recording.
9. **US5 versions**: version selection across tools; 9.0 recordings and flips per capability, or
   the FR-005 fallback.
10. **Docs**: setup, tools, live tests, README, SECURITY, CHANGELOG; `NodeToolsDocsTest`.
11. **Live check** (operator): `NodeToolsLiveTest` on 8.1 and 9.0; validation record.

### Touch points with other work

- **021** (base branch): the helper, `adapter/cli/helper`, `HelperVariant`, `HelperFailures`,
  `CliConcurrencyLimiter.acquireForHelper(…, budgetSetting)`, `CliCommand`'s `trace` (only
  `--download` so far) are extended, not copied; rebase on `main` after 021's merge (D-30).
- **022** (full interface coverage, in progress): whichever merges second adds `trace -nl`,
  `trace -r` (reachable) and `cachemode nodelist` (not used, D-1) to the coverage list and uses
  022's `ChangeGuard` in place of the `WriteGuard` overload; `CliCommand`'s `trace` forms are
  merged (022's area A owns `on`/`off`/`-l`) (D-18; spec Assumptions).
- **020** (`QueueEntryService` content rules, `EntryContentClassifier`, `QueueEntryBounds`):
  reused for message content; the content shaping is extracted into a shared `EntryText` helper
  without behaviour change (020 tests unchanged).
- **009/017** (`VersionGate`, client selection, StartCLI TLS): reused unchanged.
- **012** (6.1): 6.1 nodes refuse every new capability through the matrix and `RefusingPorts`.

## Complexity Tracking

| Item | Why needed | Simpler alternative rejected because |
|---|---|---|
| **Constitution I deviation**: cleanup resets (`trace --reset`) of recorded leases run while write tools are off (read-only mode, `logLevelWrite` or the production opt-in removed), and the reset clears every temporary level on that node, also other users' | owner decision 2026-11-12 (spec Clarifications): otherwise a raised level stays active without limit after the operator switches write access off | refusing cleanup without write access leaves a changed production-like state behind with no timer (INUBIT has none); resetting single loggers is not possible (`trace -r` is per node). Bounded: only for recorded leases, only `trace --reset`, own-node check first, audited, visible in `get_health` and `--check-config` |
| Helper compiled with `release 17` (technology constraint "Java 21 or newer"), carried over from 020/021 | 8.1's client runs on JDK 17; vendor classes must not enter the server JVM | in-JVM class loading mixes vendor state with ours and cannot be killed per call (020 D-1/D-2) |
| Durable lease store with a file lock, owner identity and a background scheduler | FR-043: leases survive restarts and are shared by instances of one profile; resets must happen without a tool call | an in-memory timer loses the lease on a crash; the audit log is not a queryable store; per-lease files cannot be updated atomically together (D-12, D-13) |
| Lease generation (compare-and-set) plus a cross-process per-node lock | two instances of one profile: a reset decided by one must never end a lease another just merged, and two changes on one server must not interleave | an in-process lock does not serialize instances; ending leases by id alone loses a concurrent set (D-12, D-14, D-16); the lock reuses the generalized `KnowledgeLock`, no second implementation |
| Write-ahead lease before the StartCLI runs | a crash between the change and the record must still leave a lease | recording only after success can lose the raise silently (D-14) |
| Message reference = hash of the listed entry, matched in the helper | FR-021: no caller text in a download name, one JVM per read | sending the entry text back from the server needs a second JVM and puts caller-influenced text into the request (D-8) |
