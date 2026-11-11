# Data Model: Download Server Log Files (trace.log, webservice.log)

Types for [spec.md](spec.md); decisions in [research.md](research.md). "New" = new type;
"changed" = existing type extended; "moved" = same behaviour, new package or name (stage 1
refactor, D-3). Every new output field is additive.

## Configuration (`config`)

| Type | Change | Fields / values | Rules |
|---|---|---|---|
| `UnsupportedConfig` | changed | `+ Optional<Boolean> serverLogRead` | group-level only; other keys stay unknown keys (D-17) |
| `Defaults` | changed | `+ BUILTIN_SERVER_LOG_READ = false` | — |
| `ConfigLoader` | changed | `requireStrictUnsupportedOptIn` checks `queueEntryRead` and `serverLogRead` | present, non-null, non-boolean → "Invalid value at groups[i].unsupported.serverLogRead in <source>: expected true or false" (no echo) |
| `EffectiveNodeConfig` | changed | `+ boolean serverLogRead` | from the group only; passed through `withTimeout`, `withCli`, `entryProbe` and both constructors |
| `ConfigValidator` | changed | `+ checkServerLogReadOptIn` | warning on a `production: true` group (contracts/configuration-delta.md) |
| `ConfigSummary` | changed | only if some group opts in: every group line `+ ", serverLogRead: off"` / `", serverLogRead: enabled (unsupported)"`; `cliOnly` lists `WEBSERVICE_LOG_READ` only for opted-in groups | no other line changes |

## Domain (`domain/model`, `domain/port`)

| Type | Change | Fields / values | Rules |
|---|---|---|---|
| `Capability` | changed | `+ TRACE_LOG_READ` ("downloading the trace log"), `+ WEBSERVICE_LOG_READ` ("downloading the web service log"); kind `READ`, channels `{CLI}` | exhaustive switches: `label`, `VersionGate.cliOnlyStep`, `VersionGate.supportedLines`, `NodeOverview.name` |
| `CapabilityMatrix` | changed | per (version, log): `SUPPORTED` once recorded, else `UNSUPPORTED` + note "not yet verified"; unknown map: both `UNSUPPORTED` | D-6, D-22 |
| `ServerLogKind` | new | `TRACE("trace", "trace.log")`, `WEBSERVICE("webservice", "webservice.log")` | `wireName` = tool input; `fileName` used only for messages and the helper constant check |
| `LogPresence` | new | `PRESENT`, `UNKNOWN` | `UNKNOWN` only for a 0-byte answer of a channel that cannot tell missing from empty (D-8) |
| `DownloadedLog` | new | `Path file`, `long size`, `Optional<String> inubitNode`, `LogPresence presence`; `AutoCloseable` (`close()` deletes the private directory) | `inubitNode` 1–128 chars of `[A-Za-z0-9_.:-]` (checked by the adapter) |
| `ServerLogPort` | new | `DownloadedLog download(ServerLogKind kind, InubitVersion version)` | whole-call failures as `ToolErrorException` (D-14); no file outside the private directory |
| `Gateway`, `GatewayFactory` | changed | `+ default ServerLogPort serverLogs()` / `serverLogs(NodeId)` | defaults throw `CLI_UNAVAILABLE` (020 D-17 pattern) |
| `ExcerptLine` | new | `long number`, `String text`, `boolean cut` | `number` ≥ 1, absolute in the file |
| `LogWindow` | new | `byte[] bytes`, `long linesBefore`, `boolean wholeFile`, `long fileSize` | `bytes.length` ≤ 50 MiB; starts at a line start (D-9); `startLine()` = `linesBefore + 1` |
| `LogExcerpt` | new | `ContentKind kind`, `Optional<BinarySignature> detected`, `Optional<Charset> charset`, window figures, `Selection selection` (`TAIL` \| `CONTAINS`), `OptionalLong matchingLines`, `List<ExcerptLine> lines`, `Optional<Truncation> truncated`, `List<String> hints` | built by `LogExcerpt.build(LogWindow, int tailLines, Optional<String> contains, int maxChars, Function<String, List<SecretSpan>> known)`; pure |
| `Truncation` | new | `int droppedLines`, `int cutLines` | `cutLines` ∈ {0, 1} |
| `SecretTextMasker` | changed | `+ String maskLines(String window, List<SecretSpan> known)` | rules 1–7 + known spans; replacement keeps every `\r`/`\n` of a region; orphan end tag → range from 0 (D-11); `mask(String)` unchanged |
| `ServerLogBounds` | new | `WINDOW_BYTES = 52_428_800`, `DEFAULT_TAIL_LINES = 200`, `MAX_TAIL_LINES = 2_000`, `DEFAULT_MAX_CHARS = 20_000`, `MAX_MAX_CHARS = 100_000`, `MAX_CONTAINS_CHARS = 200`, `MAX_NAME_SUFFIX = 999` | — |
| `ServerLogResult` | new | see "Result" | JSON shape in contracts/mcp-tools-delta.md |

## Application (`application`)

| Type | Change | Responsibility |
|---|---|---|
| `ServerLogService` | new | `ServerLogResult download(ServerLogRequest)`: validate → resolve one server → opt-in (webservice) → folder rules → audit `PENDING` → `VersionGate.admitWithDetection("download_server_log", node, capability, READ, {CLI})` → `ServerLogPort.download` (try-with-resources) → `ExcerptPermit` → `LogWindowReader` → `LogExcerpt.build` → `LogFileWriter.place` → final audit → INFO log line |
| `ServerLogRequest` | new | `String target`, `String log`, `String targetFolder`, `OptionalInt tailLines`, `Optional<String> contains`, `OptionalInt maxChars`, `Optional<String> mcpClient` | `toString` without `contains` |
| `LogWindowReader` | new | `LogWindow read(Path file, long size)`: start at the first line beginning within the last 50 MiB; streams the prefix to count `\n` (64 KiB blocks) | D-9 |
| `ExcerptPermit` | new | process-wide fair semaphore, 1 permit | D-13 |
| `LogFileWriter` | new | `Placed place(TargetFolderRules.CheckedFolder folder, String baseName, Path source)` → `Placed(Path file, long size)`; `.part` + hard-link claim with `-2` … `-999`; placeholder + atomic-move fallback; deletes what it created on failure; `FileOps` seam for tests | D-7 |
| `OwnerOnlyFolders` | new (extracted) | creates a folder and its missing parents `rwx------`, never changes existing permissions; refuses a concurrently created symbolic link | extracted from `ArchiveFolderWriter.createFolder`; `ArchiveFolderWriter` delegates (behaviour unchanged) |
| `TargetFolderRules` | changed | `+ check(String targetFolder, String cloudSubject)`; `check(String)` keeps the archive wording | D-7 step 6 |
| `ServerLogAudit` | new | one `auditId` per call; `REFUSED` / `PENDING` → `EXECUTED` \| `FAILED`; inputs `log`, `targetFolder`, `tailLines`, `maxChars`, `containsGiven`; details `file`, `sizeBytes`, `inubitNode` | D-18; never the `contains` text or content |
| `VersionGate` | changed | `supportedLines` and `cliOnlyStep` for the two capabilities | D-6 |
| `NodeOverview` | changed | `name(TRACE_LOG_READ) = "trace_log_read"`, `name(WEBSERVICE_LOG_READ) = "webservice_log_read"` | — |

## Adapter (`adapter`)

| Type | Change | Responsibility |
|---|---|---|
| `adapter/cli/CliCommand` | changed | command `trace`, option `--download` (path only); `traceDownload(Path, boolean windows)`; `build()` refuses `trace` without `--download` | FR-005 |
| `adapter/cli/CliTraceRunner` | new | precondition check, reservation + private dir (`LOG_PREFIX`), `CliRunner.run(…, traceDownload(dir/trace.log), remaining, "cliExportTimeout", guard)`, classification, `Trace data (` check, file check → `DownloadedLog` | D-1 |
| `adapter/cli/CliOutputClassifier` | changed | `+` marker rule `FileWasNotFound` → `NOT_FOUND` (trace context only) | D-8 |
| `adapter/cli/CliResources` | changed | `+ LOG_PREFIX = "inubit-mcp-log-"`, `logPrefix()`; stale sweep includes it | D-7 |
| `adapter/cli/CliConcurrencyLimiter` | changed | javadoc: `acquireForHelper` also serves log downloads (behaviour unchanged) | D-15 |
| `adapter/cli/helper/HelperInstallation` | moved (from `queue`) | resource `META-INF/inubit-mcp/client-helper/…/ClientHelper.class`, `MAIN_CLASS` | D-3 |
| `adapter/cli/helper/HelperTrustStore` | moved | — | — |
| `adapter/cli/helper/HelperVariant` | moved + renamed (`QueueEntryVariant`) | `SOAP_81`, `REST_90`, `forVersion` | — |
| `adapter/cli/helper/HelperRequest` | moved + changed | shared connection fields + sealed `Operation`: `QueueEntryRead(objectType, fileName, Optional<Path> dataOut, Path variablesOut)` \| `ServerLogRead(ServerLogKind kind, Path logOut)` (`kind` only `WEBSERVICE`); `encode()` writes `op` | D-4 |
| `adapter/cli/helper/HelperFrames` | moved + changed | `+ NodeOk(String name)`, `NodeError(chain, message)`, part `LOG` in `PartOk`/`PartError` | D-4 |
| `adapter/cli/helper/HelperFailures` | new (extracted) | login/TLS/network/403/API-missing/protocol mapping shared by both classifiers | D-14 |
| `adapter/cli/queue/QueueEntryHelperClassifier` | changed | delegates the shared part to `HelperFailures`; queue-specific part unchanged | — |
| `adapter/cli/log/WebServiceLogClassifier` | new | `node …` and `part log …` frames → outcome or `ToolErrorException` (D-14) | — |
| `adapter/cli/log/CliWebServiceLogAdapter` | new | variant, selection, `checkLaunch`, TLS check + trust store, private dir, helper run via `ProcessRun`, classification → `DownloadedLog` | D-2, D-16 |
| `adapter/cli/log/CliServerLogAdapter` | new | `implements ServerLogPort`; deadline (`cliExportTimeout`) and slot (`acquireForHelper`) for both kinds; dispatches to `CliTraceRunner` / `CliWebServiceLogAdapter` | D-15 |
| `PortSet`, `V81PortSet`, `V81Gateway`, `AdapterGatewayFactory`, `StaleReportingPorts`, `RefusingPorts` | changed | `+ ServerLogPort` (compatibility constructor; 6.1 refusing) | 020 D-17 pattern |

## Helper (`src/helper/java`)

| Type | Change | Responsibility |
|---|---|---|
| `de.dadecker.inubit.mcp.helper.ClientHelper` | renamed from `QueueEntryHelper` + changed | `op=queueEntry` (020, unchanged) and `op=serverLog` (login, `ClusterClient`, `getNode`, `clusterDownload(file, node, "webservice.log")`, logout); one top-level class, `release 17`, JDK only, reflection only |

## Tool (`mcp`)

| Type | Change | Responsibility |
|---|---|---|
| `mcp/tools/DownloadServerLogTool` | new | name `download_server_log`, description (contracts), `ToolHints.localWrite`, maps arguments to `ServerLogRequest` |
| `Wiring` | changed | service and adapters, announcement rule, `allowed()`, known-span function, `TargetFolderRules`/`AuditPort` reuse |
| `schemas/download_server_log.{input,output}.json` | new | contracts |
| `schemas/list_nodes.output.json` | changed | enum `+ trace_log_read`, `+ webservice_log_read` |

## Result (`ServerLogResult`)

```text
ServerLogResult
├── node            NodeId
├── log             ServerLogKind
├── inubitNode      Optional<String>          (webservice only)
├── file            Path                      (absolute)
├── sizeBytes       long
├── downloadedAt    Instant                   (UTC, second precision, = file name)
├── excerpt         LogExcerpt
├── hints           List<String>
└── warnings        List<String>
```

## State and lifecycle

```text
call ──► checks (input, target, opt-in, folder) ──► audit PENDING ──► version gate
        │ refusal: audit REFUSED, nothing sent       │ refusal: audit REFUSED
        ▼                                            ▼
   private dir created ──► download (slot + cliExportTimeout) ──► DownloadedLog (private copy)
        │ failure: dir deleted, audit FAILED                  │
        ▼                                                     ▼
   excerpt (ExcerptPermit) ──► place (.part → link/claim) ──► audit EXECUTED ──► dir deleted
        │ failure: created files deleted, dir deleted, audit FAILED
```

No state survives a call besides the placed file, the audit records and, for a created target
folder, the folder itself.
