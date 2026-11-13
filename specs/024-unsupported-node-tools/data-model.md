# Data Model: Unsupported Node Tools

Types for [spec.md](spec.md); decisions in [research.md](research.md). "New" = new type;
"changed" = existing type extended. Every new output field of an existing tool is additive.

## Configuration (`config`)

| Type | Change | Fields / values | Rules |
|---|---|---|---|
| `UnsupportedConfig` | changed | `+ Optional<Boolean> nodeStatusRead, ioLogRead, nodeConfigRead, logLevelWrite` | group-level only; other keys stay unknown keys (D-5) |
| `Defaults` | changed | `+ BUILTIN_NODE_STATUS_READ`, `BUILTIN_IO_LOG_READ`, `BUILTIN_NODE_CONFIG_READ`, `BUILTIN_LOG_LEVEL_WRITE` = `false` | — |
| `ConfigLoader` | changed | `requireStrictUnsupportedOptIn` checks the four keys | non-boolean → "Invalid value at groups[i].unsupported.<key> in <source>: expected true or false" (no echo) |
| `EffectiveNodeConfig` | changed | `+ boolean nodeStatusRead, ioLogRead, nodeConfigRead, logLevelWrite` | from the group only; passed through every copy method and constructor |
| `ConfigValidator` | changed | one production warning per key; `logLevelWrite` without write access on the group → warning | texts in contracts/configuration-delta.md |
| `ConfigSummary` | changed | group line parts per key (only if some group sets it); `cliOnly` filter; section `Temporary log levels:` (only with non-ended leases; read-only) | D-5, D-23 |

## Domain (`domain/model`, `domain/model/node`, `domain/port`)

| Type | Change | Fields / values | Rules |
|---|---|---|---|
| `Capability` | changed | `+ CLUSTER_NODES_READ`, `IO_LOG_READ`, `NODE_CONFIG_READ` (READ), `LOG_LEVEL_WRITE` (WRITE); channels `{CLI}` | labels and `list_nodes` names in research D-4; exhaustive switches |
| `CapabilityMatrix` | changed | 8.1/9.0 per capability `UNSUPPORTED` + "not yet verified" until recorded; unknown map `UNSUPPORTED` | D-4, D-27 |
| `HealthReport` | changed | `+ List<LeaseSummary> temporaryLogLevels` (empty → omitted) | D-23 |
| `NodeStatus` | new | `ACTIVE`, `MAINTENANCE`, `OFFLINE`, `UNKNOWN` | `of(Optional<String> raw)`; unknown text → `UNKNOWN` + raw |
| `ClusterNode` | new | `String name`, `NodeStatus status`, `Optional<String> rawStatus`, `Optional<String> version`, `Optional<String> zone`, `Optional<String> nodeId`, `Optional<Duration> uptime` | name rule `[A-Za-z0-9_.:-]{1,128}`; rawStatus ≤ 64 `[A-Za-z0-9_.-]`; texts ≤ 200 |
| `NodeDetail` | new | `String key`, `String value`, `boolean cut` | key ≤ 200; value masked, ≤ 2,000 |
| `ClusterNodes` | new | `String answeringNode`, `List<ClusterNode> nodes`, `int total`, `boolean truncated`, `Optional<Details> details` where `Details` = `Available(String node, List<NodeDetail> pairs, boolean truncated)` \| `Unavailable(String node, String reason)` | ≤ 200 nodes (`truncated` when more), ≤ 500 pairs or the helper's 256 KiB budget (`Details.truncated`) |
| `IoDirection` | new | `INPUT("Input")`, `OUTPUT("Output")` | — |
| `IoLogEntry` | new | `String entry` (raw), `String moduleName`, `Optional<String> moduleId`, `IoDirection direction`, `int index`, `IoMessageRef ref` | `parse(String processId, String entry)` (D-8); `ref = IoMessageRef.of(entry)` |
| `IoMessageRef` | new | `String value` | `^[A-Za-z0-9_-]{22}$`; `of(entry)` = Base64url(SHA-256(UTF-8 entry))[0..16 bytes] |
| `IoLogListing` | new | `String answeringNode`, `List<IoLogEntry> entries` (all, server order) | `filter(Optional<String> module)`; parse failures kept with a flag |
| `IoLogBounds` | new | `MAX_LISTED = 500`, `MAX_LIST_BYTES = 16 MiB`, `MAX_WORKFLOW_CHARS = 200`, `MAX_MODULE_CHARS = 200` | content limits reused from `QueueEntryBounds` |
| `NodeConfigName` | new | enum of the 20 FR-030 names; `wireName()` | `parse(String)` → `Optional`; the helper has the same list |
| `NodeConfigValue` | new | `NodeConfigName name`, sealed `Present(String value, boolean cut, String type, String displayName, String description, String category, boolean editable, boolean deprecated)` \| `Absent` \| `Failed(ErrorCode code, String message)` | value masked, ≤ 2,000 (`cut` when longer, D-21) |
| `LoggerName` | new | `String value` | `^com\.inubit\.ibis(\.[A-Za-z_][A-Za-z0-9_]*)+$`, ≤ 200 |
| `TemporaryLevel` | new | `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` | — |
| `LeaseState` | new | `ACTIVE`, `RESET`, `RESET_FAILED`, `FORGOTTEN` | `open()` = `ACTIVE` \| `RESET_FAILED` |
| `LeaseOwner` | new | `long pid`, `Optional<Instant> startedAt` | from `ProcessHandle.current()` |
| `LeaseReason` | new | `OTHER_NODE_ANSWERS`, `NODE_UNREACHABLE`, `PRECONDITION`, `CLI_FAILED`, `AUDIT_NOT_WRITABLE`, `SERVER_NOT_CONFIGURED` | fixed texts; `AUDIT_NOT_WRITABLE` = the cleanup's `PENDING` record failed, nothing sent (D-22) |
| `LeaseAttempt` | new | `Instant at`, `AuditOutcome outcome`, `Optional<ErrorCode> code`, `Optional<LeaseReason> reason` | — |
| `Lease` | new | `UUID leaseId`, `long generation`, `NodeId server`, `String inubitNode`, `SortedMap<LoggerName, TemporaryLevel> loggers`, `Instant setAt`, `Instant expiresAt`, `LeaseOwner owner`, `LeaseState state`, `UUID auditId`, `Optional<LeaseAttempt> lastAttempt`, `Optional<Instant> endedAt` | 1–5 per set, union on merge (≤ 50 per lease); `merge(set)`, `reset(at)`, `failed(attempt)`, `forgotten(at)`, `takenOverBy(owner)`; pure; every transition returns generation + 1; `sameGeneration(Lease)` for the compare-and-set of D-16 |
| `LeaseSummary` | new | `UUID leaseId`, `String inubitNode`, `List<(LoggerName, TemporaryLevel)> loggers`, `Instant setAt`, `Instant expiresAt`, `LeaseState state`, `Optional<LeaseAttempt> lastAttempt` | the view of an open lease in `get_health` (`$defs/LeaseSummary`) and the summary; no owner pid, no generation |
| `ClusterNodesResult`, `IoLogResult`, `NodeConfigResult`, `LogLevelResult` (`Challenge` \| `Outcome`) | new | the tool results of section "Results" (`domain/model/node`) | JSON shapes in contracts/mcp-tools-delta.md |
| `ClusterNodePort` | new | `ClusterNodes nodes(Optional<String> details, InubitVersion version, long deadlineNanos)` | whole-call failures as `ToolErrorException` |
| `IoLogPort` | new | `IoLogListing list(String processId, String workflow, InubitVersion v, long deadline)`; `IoLogRead read(String processId, String workflow, IoMessageRef ref, InubitVersion v, long deadline)` | `IoLogRead` = `NoMatch(IoLogListing)` \| `Message(IoLogListing, IoLogEntry, Path file, long size ≥ 0, Runnable deletePrivateDirectory)`; `AutoCloseable` (`Message.close()` runs `deletePrivateDirectory`; `NoMatch` holds no file, the adapter deletes its directory before returning it) |
| `NodeConfigPort` | new | `NodeConfigValues values(List<NodeConfigName> names, InubitVersion v, long deadline)` → answering node + values in request order | — |
| `LogLevelPort` | new | `String answeringNode(InubitVersion v, long deadline)`; `void raise(TemporaryLevel level, LoggerName logger, InubitVersion v, Duration timeout)`; `void resetAll(InubitVersion v, Duration timeout)` | StartCLI forms of D-11 only |
| `LeaseView` | new | `Snapshot leases()` → `List<Lease>` or `Unreadable(reason)` | read-only (for `HealthService`, `ConfigSummary`) |
| `Gateway`, `GatewayFactory` | changed | `+ default` `clusterNodes()`, `ioLog()`, `nodeConfig()`, `logLevel()` / `(NodeId)` | defaults throw `CLI_UNAVAILABLE` (020 D-17 pattern) |

## Application (`application`, `application/nodetools`)

| Type | Change | Responsibility |
|---|---|---|
| `ClusterNodeService` | new | `ClusterNodesResult list(ClusterNodesRequest)`: validate → resolve one server → opt-in → `admitWithDetection(CLUSTER_NODES_READ)` → deadline (`cliTimeout`) → `prepareCredentials` → port → `nodeName` check (`INVALID_INPUT` listing nodes) → masking → result → INFO line |
| `IoLogService` | new | `IoLogResult read(IoLogRequest)`: validate (D-8) → server → opt-in → gate (`IO_LOG_READ`) → deadline (`cliTimeout` listing, `cliExportTimeout` with `message`) → credentials → port `list`/`read` → filter, bounds → content shaping via `EntryText` (020 rules) → result → INFO line |
| `EntryText` | new (extracted from `QueueEntryService.data`) | classify → known credentials → mask → cut; `QueueEntryService` delegates (behaviour unchanged) |
| `NodeConfigService` | new | validate names (allowlist) → server → opt-in → gate (`NODE_CONFIG_READ`) → deadline `cliTimeout` → port → masking (defence in depth) → result |
| `NodeTextMasking` | new | `mask(String)` = `SecretTextMasker.mask(knownSecrets.apply(text))`; bounded cut | D-21 |
| `LogLevelService` | new | `set(SetLevelRequest)`, `reset(ResetLevelRequest)` (node / forget) per D-14/D-15; `cleanup(Lease decided, Trigger)` for one lease (node lock, generation re-check, FR-043 preconditions without write guard, fail-closed `PENDING`, own-node lookup, `trace --reset`, compare-and-set) per D-16 | — |
| `LeaseStore` | new | `read()`, `update(Function<List<Lease>, List<Lease>>, Duration wait)` under the store lock (`OwnerOnlyFileLock` on `log-levels/.lock`); JSON format 1 with `generation`; owner-only atomic replace; unreadable → never overwritten | D-12; implements `LeaseView` |
| `OwnerOnlyFileLock` (`application`) | new (generalized from `KnowledgeLock`) | `Held acquire(Path lockFile, Duration wait, Supplier<ToolErrorException> busy)`: in-process lock per path, then `FileChannel.tryLock` polled every 50 ms; lock file owner-only, never through a link, never deleted | `KnowledgeLock` delegates, texts unchanged (D-12) |
| `ProcessLiveness` | new | `boolean alive(LeaseOwner)`; `LeaseOwner self()` | seam for tests (D-13) |
| `NodeLogLevelLocks` | new | `Held acquire(NodeId server, Duration wait)`: cross-process lock on `log-levels/nodes/<group>.<node>.lock` through `OwnerOnlyFileLock`; held by set, reset and cleanup around the own-node lookup and the StartCLI runs | node lock before store lock, always (D-12) |
| `LeaseScheduler` | new | tick every 30 s (first at start): decisions under the store lock (cleanup, takeover with generation + 1, forget after 7 days, retention), then `LogLevelService.cleanup` per node outside it; `shutdown(Duration max)` idempotent | D-16, D-17 |
| `LogLevelAudit` | new | records of D-22 (tool calls and automatic resets) | — |
| `WriteGuard` | changed | `+ admit(String node, String tool, Capability, Set<Channel>, Predicate<NodeId> optIn, String optInKey)`; `+ admitWithoutContact(String node, String tool, Predicate<NodeId> optIn, String optInKey)` (configuration checks up to the opt-in, no gate, no client check; for forget); the `ProcessAction` overload delegates with byte-identical texts | D-18 |
| `WriteChallengeRegistry` | changed | `+ issue(String tool, NodeId node, String fingerprint, String previewState, Duration ttl)`, `+ redeem(String code, String tool, NodeId node, String fingerprint)` | D-19 |
| `HealthService` | changed | per report `temporaryLogLevels` from `LeaseView` (failure → warning) | D-23 |
| `VersionGate` | changed | `supportedLines`, `cliOnlyStep` for the four capabilities | D-4 |
| `NodeOverview` | changed | names `cluster_nodes_read`, `io_log_read`, `node_config_read`, `log_level_write` | — |

## Adapter (`adapter`)

| Type | Change | Responsibility |
|---|---|---|
| `adapter/cli/CliCommand` | changed | `trace` forms: `traceNodeLevel(TemporaryLevel, LoggerName)` (`--nodelevel` + `--package`), `traceReset()` (`--reset`); `build()` refuses every other `trace` combination | D-11 |
| `adapter/cli/CliOutputClassifier` | changed | `+ classifyTraceLevel(CliResult)`: confirmation form (pinned by the recording), `Missing log level.` → `UNEXPECTED_RESPONSE`, else `classify` | D-11 |
| `adapter/cli/helper/HelperRequest` | changed | `Operation` `+ OwnNode`, `ClusterNodes`, `NodeConfig`, `IoLog`; `encode()` writes `op` for every operation except `QueueEntryRead`; 020/021 encodings byte-identical | D-2 |
| `adapter/cli/helper/HelperFrames` | changed | `+ Member`, `MembersOk`/`MembersError`, `Detail`, `DetailsOk`/`DetailsError`/`DetailsUnknown`, `ConfigOk`/`ConfigAbsent`/`ConfigError`, `IoListOk`/`IoListError`, `IoMatch(Optional<String>)`, part `MESSAGE` | D-2 |
| `adapter/cli/node/NodeHelperRunner` | new | one helper run for a node: variant, selection, `checkLaunch`, TLS check + trust store, private directory (`CliResources` reservation), slot `acquireForHelper(…, budgetSetting)`, `ProcessRun`, `HelperFailures` | D-24 |
| `adapter/cli/node/NodeFramesClassifier` | new | frames → domain values or `ToolErrorException` (D-20); node-name rule | — |
| `adapter/cli/node/CliClusterNodeAdapter` | new | `implements ClusterNodePort` | — |
| `adapter/cli/node/CliIoLogAdapter` | new | `implements IoLogPort`; bounded listing read; message file handed over | — |
| `adapter/cli/node/CliNodeConfigAdapter` | new | `implements NodeConfigPort` | — |
| `adapter/cli/node/CliLogLevelAdapter` | new | `implements LogLevelPort`: `ownNode` via the runner; `trace` runs via `CliRunner.run(…, "cliTimeout", guard)` | — |
| `PortSet`, `V81PortSet`, `V81Gateway`, `AdapterGatewayFactory`, `StaleReportingPorts`, `RefusingPorts` | changed | `+` the four ports (compatibility constructor; 6.1 refusing) | 020 D-17 pattern |

## Helper (`src/helper/java`)

| Type | Change | Responsibility |
|---|---|---|
| `ClientHelper` | changed | `op` values `ownNode`, `clusterNodes`, `nodeConfig` (own constant allowlist), `ioLog` (listing to `listOut`, reference match with SHA-256/Base64url, download to `messageOut`); per-operation keys and API checks; one class, `release 17`, JDK only, reflection only (contracts/helper-protocol-delta.md) |

## Tools (`mcp`)

| Type | Change | Responsibility |
|---|---|---|
| `ListClusterNodesTool`, `ReadIoLogTool`, `GetNodeConfigTool` | new | `ToolHints.readOnly(…, true)`, descriptions (contracts) |
| `SetTemporaryLogLevelTool`, `ResetTemporaryLogLevelTool` | new | `ToolHints.destructive(…)`; challenge or result output |
| `Wiring` | changed | services, adapters, `LeaseStore`, `LeaseScheduler` (started after construction), announcement, `allowed()`, shutdown hook order (lease reset, then CLI cleanup), `resetLeasesOnShutdown()` |
| `Launcher` | changed | `resetLeasesOnShutdown()` before `stopCliWork()`; startup lease lines |
| schemas | new / changed | five tools × input/output; `get_health.output.json` `+ temporaryLogLevels`; `list_nodes.output.json` enum `+4` |

## Lease lifecycle

```text
                set (write-ahead, D-14 step 8)
                         │
                         ▼
      ┌──────────────► ACTIVE ──── merge (another set: union, later expiry, owner := setter)
      │                  │
      │      expiry / shutdown / reset tool / failed set / record failure
      │                  │ own-node check → trace --reset
      │        ┌─────────┴──────────┐
      │        ▼ ok                 ▼ failed (unreachable, other node, precondition)
      │      RESET              RESET_FAILED ── retry every tick and at start ──► RESET
      │        │                    │
      │        │                    ├── reset_temporary_log_level leaseId + forget ──► FORGOTTEN
      │        │                    └── server not configured, expiry + 7 days ─────► FORGOTTEN
      │        ▼                    ▼
      │   removed 7 days after endedAt (RESET, FORGOTTEN)
      │
      └── owner not alive: takeover (owner := this instance), then as above

Every transition increments the lease's generation. A cleanup ends a lease only if the generation
it decided on is unchanged (compare-and-set under the store lock) and holds the node lock from the
own-node lookup to the final update; a set holds the same node lock from the write-ahead record to
its final update. A cleanup whose `PENDING` audit record cannot be written sends nothing and keeps
the state with reason `AUDIT_NOT_WRITABLE` (retried next tick).
```

A set for a server with a `RESET_FAILED` lease is refused before any change. An `ACTIVE` lease
whose server is no longer configured is not reset (no connection data); it ends `FORGOTTEN` 7 days
after its expiry and is a startup warning until then.

## Results (summary; JSON in contracts/mcp-tools-delta.md)

```text
ClusterNodesResult   node, inubitNode, nodes[≤200]{name,status,rawStatus?,version?,zone?,nodeId?,uptime?,uptimeSeconds?},
                     total, truncated, details?{node, available, pairs[≤500]{key,value,cut?}|reason, truncated}, hints, warnings
IoLogResult          node, inubitNode, workflow, processId, total, matching, entries[≤500]{ref,moduleName,moduleId?,direction,index},
                     truncated, message?{ref, moduleName, moduleId?, direction, index, sizeBytes, kind(text|binary|empty|tooLarge),
                     charset?, detected?, textChars?, text?, cut?}, hints, warnings
NodeConfigResult     node, inubitNode, values[]{name, present? (absent when error is set), value?, cut?, type?, displayName?,
                     description?, category?, editable?, deprecated?, error?}, warnings
LogLevelResult       challenge{confirmationCode, expiresAt, preview, message} | result{node, inubitNode, outcome, leaseId?,
                     loggers[]{name, level}, expiresAt?, leaseOpen, message, auditId, warnings}
```
