# Data Model: Read Queue Manager Entries (unsupported, opt-in)

Types for [spec.md](spec.md); decisions in [research.md](research.md). "New" = new type;
"changed" = existing type extended. Every new output field is additive; nothing changes for a
profile without the opt-in.

## Configuration (`config`)

| Type | Change | Fields / values | Rules |
|---|---|---|---|
| `UnsupportedConfig` | new | `Optional<Boolean> queueEntryRead`; `EMPTY` | only as `GroupConfig.unsupported`; other keys → unknown key (D-15) |
| `GroupConfig` | changed | `+ UnsupportedConfig unsupported` (default `EMPTY`) | group-level only; `NodeConfig`, `Defaults` unchanged (so the key is unknown there) |
| `ConfigLoader` | changed | `+ requireStrictUnsupportedOptIn(groups, source)` | present, non-null, non-boolean → "Invalid value at groups[i].unsupported.queueEntryRead in <source>: expected true or false" (no echo) |
| `EffectiveNodeConfig` | changed | `+ boolean queueEntryRead` | from the group only, default `false`; passed through `withTimeout`, `withCli`, `entryProbe` and both constructors |
| `ConfigValidator` | changed | `+ checkQueueEntryReadOptIn` | warning on a `production: true` group (text in contracts/configuration-delta.md) |
| `ConfigSummary` | changed | only if some group of the profile opts in: every group line `+ ", queueEntryRead: off"` / `", queueEntryRead: enabled (unsupported)"`; without any opt-in the output is byte-for-byte that of 0.8.0; `cliOnly` lists `QUEUE_ENTRY_READ` only for opted-in groups | no other line changes |

## Domain (`domain/model`, `domain/port`)

| Type | Change | Fields / values | Rules |
|---|---|---|---|
| `Capability` | changed | `+ QUEUE_ENTRY_READ` (kind `READ`, label "reading Queue Manager entries") | exhaustive switches: `label`, `VersionGate.cliOnlyStep`, `NodeOverview.name` (`queue_entry_read`) |
| `CapabilityMatrix` | changed | 8.1 `SUPPORTED`; 9.0 `SUPPORTED` with recording, else `UNSUPPORTED` + note; unknown map `QUEUE_ENTRY_READ → UNSUPPORTED`; `Entry` `+ Map<Capability, String> notes` | D-6, D-20 |
| `QueueEntryRow` | new | `String processId`, `Optional<String> globalProcessId`, `ProcessState state`, `String rawState`, `Instant since`, `String workflow`, `Optional<String> module`, `Optional<String> moduleType`, `String fileName`, `long fileSize`, `SortedMap<String,String> errorTexts` (`userDefined1..5`, non-empty only, each ≤ 2,000) | `fileName` 1–255, no `/`, `\`, NUL, control chars; workflow without `@@@` or control chars; else the adapter throws `UNEXPECTED_RESPONSE` |
| `QueueEntries` | new | `List<QueueEntryRow> rows`, `long total` | newest first, ≤ 100; `truncated()` = `total > rows.size()` |
| `ProcessQueryPort` | changed | `+ QueueEntries queueEntries(String processId)` | same request as `findByProcessId` |
| `EntryPart` | new | `DATA`, `VARIABLES` | — |
| `QueueEntryRef` | new | `NodeId node`, `String workflow`, `String fileName`, `long fileSize` | built from a `QueueEntryRow` only |
| `RawEntryContent` | new | `PartResult<byte[]> data`, `PartResult<List<RawVariable>> variables` | `PartResult` = `Read(T)` \| `NotRequested(String reason)` \| `NotFound` \| `Failed(String reason)` \| `TooLarge(long bytes)`; reasons are fixed or scrubbed texts, never content |
| `RawVariable` | new | `String name`, `Optional<String> type`, `String value` | file order |
| `QueueEntryContentPort` | new | `RawEntryContent read(QueueEntryRef ref, InubitVersion version, Set<EntryPart> parts)` | throws `ToolErrorException` for whole-call failures (D-5); no partial content on `TIMEOUT` |
| `ContentKind` | new | `EMPTY`, `TEXT`, `BINARY` | D-10 |
| `BinarySignature` | new | `ZIP`, `GZIP`, `PDF`, `PNG`, `JPEG`, `GIF`, `JAVA_SERIALIZED` | magic bytes in D-10 |
| `EntryContentClassifier` | new | `Classified classify(byte[])` → `kind`, `Optional<BinarySignature> detected`, `Optional<Charset> charset`, `Optional<String> text` | pure; window `min(8192, size)`; 10 % control-byte rule |
| `SecretTextMasker` | new | `String mask(String text)`; `boolean secretName(String name)`; `String placeholder(String scope, String name)` | pure; rules 1–7 of D-11 on ranges of the original text, merged |
| `SystemVariables` | new | `boolean isSystem(String name)` | the FR-010 list or prefix `_hidden.` |
| `QueueEntryBounds` | new | `MAX_DATA_BYTES = 10 MiB`, `DEFAULT_MAX_CHARS = 20_000`, `MAX_MAX_CHARS = 100_000`, `MAX_VARIABLES = 200`, `MAX_NAME_CHARS = 200`, `MAX_VALUE_CHARS = 2_000`, `MAX_VALUES_CHARS = 40_000`, `MAX_LISTED_CANDIDATES = 20` | — |
| `QueueEntryResult` | new | see "Result" below | JSON shape in contracts/mcp-tools-delta.md |

## Application (`application`)

| Type | Change | Responsibility |
|---|---|---|
| `QueueEntryService` | new | `QueueEntryResult read(String target, String processId, Optional<String> fileName, OptionalInt maxChars)`: validate input → resolve one server (`TargetResolver.resolveSingleServer`) → opt-in (`NOT_CONFIGURED`) → `VersionGate.admit("read_queue_entry", …, QUEUE_ENTRY_READ, READ, {REST, CLI})` → `queueEntries` → candidate rules (D-9) → parts (10 MiB pre-check) → `QueueEntryContentPort.read` → re-read on `NotFound` → classify, scrub (`UnaryOperator<String> knownSecrets`), mask, bound → result; one INFO log line (D-16) |
| `NodeOverview` | changed | `name(QUEUE_ENTRY_READ) = "queue_entry_read"` |
| `VersionGate` | changed | `cliOnlyStep` case; refusal text uses the matrix note when present; `+ admitWithDetection(…)`: a failed detection (fresh: rethrown after it was recorded; in back-off: the recorded `DetectionFailure` code) fails the call with that code instead of judging the version unknown (US3-4, D-6) |

## Adapter (`adapter`)

| Type | Change | Responsibility |
|---|---|---|
| `adapter/cli/ProcessRun` | new (extracted) | `CliResult run(LaunchSpec spec, byte[] stdin, Duration timeout, String processLabel)`: reserve, launch, drain stdout/stderr (1 MiB bound), write and zero-fill stdin, wait, tree kill, `TIMEOUT`/`CLI_UNAVAILABLE`/`INTERNAL` texts with `processLabel`; used by `CliRunner` and the helper |
| `CliRunner` | changed | delegates to `ProcessRun`; `javaHome(server)` public |
| `CliResources.Reservation` | changed | `process(LaunchedProcess)` public |
| `CliConcurrencyLimiter` | changed | `+ Permit acquireForHelper(EffectiveNodeConfig, Duration wait)` (same per-node StartCLI semaphore, no 6.1 shortcut; 8.1/9.0 StartCLI runs still take no slot, FR-016 interpretation D-14) |
| `HandshakeStartCliPinProbe` / `adapter/cli/TlsPeer` | changed / new | `List<X509Certificate> handshake(EffectiveNodeConfig, Duration)`; the probe keeps its behaviour |
| `adapter/cli/queue/QueueEntryVariant` | new | `SOAP_81`, `REST_90`; `forVersion(MajorMinor)`; `objectType(workflow)` = `queue_manager@@@<workflow>@@@error` |
| `adapter/cli/queue/HelperRequest` | new | fields of the stdin request (D-4); `byte[] encode()`; `toString()` without user, password, trust-store password |
| `adapter/cli/queue/HelperFrames` | new | parses stdout frames for one nonce: `start`, `login ok/error`, `part … ok/error`, `api missing`, `end`; ignores other lines |
| `adapter/cli/queue/QueueEntryHelperClassifier` | new | frames + exit code → `ToolError` or part results (D-5) |
| `adapter/cli/queue/HelperTrustStore` | new | writes `<tmp>/trust.p12` with the verified leaf and a one-time password |
| `adapter/cli/queue/HelperInstallation` | new | copies the helper class resource; builds the class path and the launch command (D-2, D-3) |
| `adapter/cli/queue/WfspParser` | new | `.wfsp` → `List<RawVariable>` (StAX, no DTD); fixed failure reason |
| `adapter/cli/queue/CliQueueEntryAdapter` | new | implements `QueueEntryContentPort` for one node |
| `adapter/rest/v81/QueueEntryRowParser` | new | queueLog row → `QueueEntryRow` |
| `V81ProcessQueryAdapter` | changed | `queueEntries` |
| `V61ProcessQueryAdapter`, `RefusingPorts` | changed | refuse `queueEntries` / `read` |
| `PortSet`, `V81PortSet`, `V61PortSet`, `StaleReportingPorts`, `V81Gateway`, `AdapterGatewayFactory` | changed | `+ QueueEntryContentPort queueEntries` |
| `Gateway`, `GatewayFactory` (`domain/port`) | changed | `+ queueEntryContent(NodeId)` |

## Helper (`src/helper/java`, Java 17 bytecode, JDK only)

| Type | Fields / behaviour |
|---|---|
| `de.dadecker.inubit.mcp.helper.QueueEntryHelper` | `main(String[])` → `System.exit(run(System.in, System.out, loader))`; `static int run(InputStream, PrintStream, ClassLoader)`: read request → frames `start` → login (variant by reflection) → `login ok/error` → per requested part `MultipartDownloadThread` to its output file → `part … ok/error` → `logoutUser` if the method exists → `end`; exit 0/1/2/3/4 (D-4) |

### Helper request (stdin)

| Key | Value | Secret |
|---|---|---|
| `qe` | `1` (protocol version) | no |
| `nonce` | 32 hex chars per call | no |
| `variant` | `SOAP_81` \| `REST_90` | no |
| `url` | selection `cli.url` (8.1: servlet path; 9.0: base URL) | no |
| `trustStore` | `<tmp>/trust.p12` or empty (`http`) | no |
| `trustStorePassword` | one-time Base64 password | yes (never logged) |
| `hostnameVerification` | `true` \| `false` | no |
| `objectType` | `queue_manager@@@<workflow>@@@error` | no |
| `fileName` | queue file name | no |
| `parts` | `data,variables` \| `variables` | no |
| `dataOut`, `variablesOut` | paths below `<tmp>/out/` | no |
| `user`, `password` | node credentials | password yes |

### Helper frames (stdout)

`#QE1:<nonce> <type> <tokens…>`, tokens percent-encoded; types `start`, `login`, `part`, `api`,
`end` (D-4). Lines without the prefix are ignored and never logged.

## Result (`QueueEntryResult`)

| Field | Content |
|---|---|
| `node` | `<group>/<node>` |
| `entry` | `processId`, `globalProcessId?`, `workflow`, `module?`, `moduleType?`, `state` (`ERROR`), `rawState`, `since` (ISO-8601), `fileName`, `sizeBytes`, `errorTexts` (`userDefined1..5` present ones) |
| `data` | `{available: false, reason}` \| `{available: true, kind: "empty", sizeBytes: 0}` \| `{available: true, kind: "text", charset, sizeBytes, text, totalChars, returnedChars, truncated}` \| `{available: true, kind: "binary", sizeBytes, detected?, hint}` |
| `variables` | `{available: false, reason}` \| `{available: true, totalCount, returnedCount, truncated, items: [{name, nameTruncated?, system, type?, kind, value?, sizeBytes?, detected?, masked, totalChars?, truncated?}]}` |
| `warnings` | always `["Read through an unsupported, undocumented INUBIT interface; diagnosis only"]` plus gate warnings |

State: the result is computed per call; nothing is cached or stored.
