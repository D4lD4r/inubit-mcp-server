# Research: Unsupported Node Tools (node status, module I/O logging, node configuration, temporary log level)

**Feature**: 024-unsupported-node-tools | **Date**: 2026-11-12 | **Spec**: [spec.md](spec.md)

Base: branch `021-server-log-download` (not yet merged; 0.9.1 + features 020 and 021). Sources:
`javap -c -p -v` of `lib/ibis.jar` of the 8.1.17 and 9.0.10 client installations (no live call
against INUBIT was made for this research), feature 020/021 research and spike findings, and the
code of this branch. Paths below are relative to `src/main/java/de/dadecker/inubit/mcp/` unless
they start with `src/`, `docs/`, `specs/`, `tools/` or `pom.xml`.

### Static facts this research builds on

Identical in 8.1.17 and 9.0.10 unless a row says otherwise (9.0 differs only in the logger field
of the classes, `IBISTracer` → slf4j `Logger`).

| Topic | Finding (bytecode) |
|---|---|
| `com.inubit.ibis.server.cluster.ClusterClient` | Same public API in both versions: four-argument constructor `(I_IBISClientInterface, String user, String clientUID, String additionalUserGroup)`; `NodeList getNodes()`; `Node getNode()`; `Node getNode(NodeName)`; `IBISProperty getNodeConfigurationProperty(NodeName, String)`; `setNodeConfigurationProperty(NodeName, IBISProperty)`; `changeTemporaryLogLevel(String, org.apache.logging.log4j.Level)`; `resetTemporaryLogLevel()`; `startGarbageCollector()`; cache methods (`getCaches`, `clearCache`, `removeCacheEntry`, …); `clusterDownload*`, `downloadData`, `uploadData`. Every call goes through `client.getObject(<marshalled request>, user, "Cluster###" + clientUID)` (021 static facts). This feature calls only `getNode()`, `getNodes()`, `getNode(NodeName)` and `getNodeConfigurationProperty`. |
| `NodeList` | a `List<Node>` (`Iterable`, `size`, `get(int)`, `getByName(String)`). |
| `Node` | fields `NodeName nodeName`, `Node$Status status`, `String version`, `String zoneName`, `long uptime` (milliseconds), `String nodeID`, `byte[] nodeInformation`; getters `getNodeName`, `getStatus`, `getVersion`, `getZoneName`, `getUptime`, `getNodeID`, `getNodeInformation()` → `NodeInformation` (deserialized from the bytes). `Node$Status` = `ACTIVE`, `MAINTENANCE`, `OFFLINE`. |
| Server, `getNodes` (`ClusterService`) | asks every known node through the distributed executor; a node that does not answer is still listed with only its name (status `null`, no version, zone, node ID, uptime; no node information). |
| Server, `getNode(NodeName)` / `getNode()` | the named node's `Node` with its `NodeInformation`; `null` → the answering node itself. |
| `NodeInformation` | wraps a `MapProperty nodeInformationMap` (server, JDK, operating system, processors, maximum heap, cluster/node/zone names, `InternalNodeAddress`, file paths); `MapProperty.getPropertyNames()` and `get(String)` → `IBISProperty`, `getStringValue()`. |
| Server, `getNodeConfigurationProperty(NodeName, name)` | `getNodeConfiguration(nodeName)`: `null` → the session's own `NodeConfiguration`, else runs `ClusterNodeConfigurationCallable` on the named node; then `NodeConfiguration.getProperties().get(name)`. Any name is accepted, including `Database` (driver, JDBC URL, user, **password**), `ClusterPassword`, `initialRootUserPassword`, `IdentityProvider`, `IdentityProviderConfiguration`. An unknown name yields `null`. |
| Node configuration names | 8.1.17 and 9.0.10: `OperationMode`, `DataSourceLocation`, `RepositoryDataSourceLocation`, `LogsDBDataSourceLocation`, `socketTimeoutInMillisecs`, `Database`, `RuntimeDataBackupStore`, `ClusterCacheTaskDataModesConfig`, `ClusterBindAddress`, `ClusterName`, `ClusterPassword`, `ZoneName`, `NodeName`, `RuntimeDataCacheXMXPercentage`, `indexEnabled`, `MaxEntriesInMemoryLimit`, `ContainerShutdownOnNodeFailure`, `LocalMaintenanceMode`, `RegisterMBeans`, `RegisterInternalMBeans`, `ClusterDebug`, `IdentityProvider`, `IdentityProviderConfiguration`, `iterationCount`, `initialRootUserPassword`, `ActivateClusterManager`; 9.0.10 adds `noOfRetries` and `retryInterval`. |
| `IBISProperty` | `getName()`, `getStringValue()`, `getType()` (`Class`), `getDisplayName()`, `getShortDescription()`, `getCategory()`, `isEditable()`, `isDeprecated()`, `toElement()` (XML with the value). |
| Server permission check (`ClusterService`) | `checkConfigurationPermission()` calls `session.hasPermission("Configuration")` and **discards the result** (`pop`); `checkRootPermission()` refuses non-root sessions only for root operations. So the node list, node details and configuration reads need only a login. This corrects 021's statement that the Cluster Manager download needs the `Configuration` right (static; the live spike confirms with a user without that right). |
| Server, `changeTemporaryLogLevel(name, level)` | `LogManager.getLogger(name)`; if its level equals `level`: nothing ("already applied"); else stores the previous level in the static map `normalLogLevels` (first change only), `setLevel(level)`, and adds the INUBIT log appender to the logger if it lacks it. Exactly the named logger; no timer; in the node's memory only. |
| Server, `resetTemporaryLogLevel()` | restores every entry of `normalLogLevels` on **that node** (all users' temporary levels). An appender added by a change is not removed. |
| StartCLI `trace` (`com.inubit.ibis.cli.cmds.TraceCommand`) | options `d`/`download`, `r`/`reset` ("Resets all temporary log levels"), `l`/`level` (persistent, cluster-wide), `p`/`package` ("Class or package name for temporary log level change"), `nl`/`nodelevel` ("Temporarily change log level on the connected node …"), positional `on`/`off`. `execute` order: `d` → `downloadTrace`; else `r` → `ClusterClient.resetTemporaryLogLevel()`, `CommandResult("OK")`; else `l` → persistent level (`ServerConfiguration.setProperty`); else `p` or `nl` → `changeTemporaryLogLevel`: package = `-p` value or `com.inubit.ibis`, `-nl` required ("Missing log level."), `Level.toLevel(text)` (unknown text → `DEBUG`), `CommandResult("OK")`; else no args → state, positional → `on`/`off`. Documented in the StartCLI help of both versions. |
| StartCLI cache shell (`cli.cache.*`) | `cachemode` enters an interactive shell; `NODELISTCommand` (`nodelist`) prints a table NAME, ID, ZONE, VERSION, STATUS, UPTIME of `getNodes()` — it does **not** mark the answering node and has no details. |
| I/O logging listing (`IBISController.getObject` type `inputOutputMessageDetails` → `IBISWorkflowEngine.getInputOutputMessageDetails(objectName)`) | object name `<processId>@@@<workflowName>`; `getWorkflowData(workflowName)` (unknown → empty answer); for each module of the workflow whose plug-in group is `System Connector` (case-insensitive) or whose plug-in is `Message Logger Utility`: entries `<processId>@@@<moduleName>(<moduleId>)@@@Input.<n>` and `…@@@Output.<n>` for n = 1, 2, … while `ExecutionDataFacade.existsInOutMessage(<logDirectory>/<entry>)`; joined by `###`. `getModuleNameAndId()` = `<moduleName>(<moduleId>)` (concat recipe `\u0001(\u0001)`). |
| I/O logging download (`MultiTransferDownloadServiceRequestHandler` → `InputOutputLoggingDownloadHandler`) | download type `InputOutputLogging`, raw name `<workflowName>###<entry>`: split at the first `###`, `getWorkflowData(workflowName).getLogDirectory()` + separator + entry → `getInOutMessageStream` / `getInOutMessageSize`; a missing message → empty stream ("i/o logging message not found. DL: no data"); unknown workflow or no `###` → `InubitException("Unable to download", …)`. No group or right check beyond the session. Client side as the Workbench's I/O view: `new MultipartDownloadThread(client, user, "<workflowName>###<entry>", "InputOutputLogging", null, additionalUserGroup)` (the 020 six-argument constructor). |
| 9.0 client object | `InubitClientFactory.getRestInstance(ConnectionData)` implements `getObject` through its internal REST handler; `ClusterClient` over it was verified live by 021 (`getNode()` on the 9.0 test server). |

---

## D-1 Channel per tool

**Decision**:

| Tool | Channel | Vendor calls (FR-007) |
|---|---|---|
| `list_cluster_nodes` | 020/021 helper (`ClientHelper`), new operation `clusterNodes` (D-2) | login, `getNode()`, `getNodes()`, with `nodeName` also `getNode(NodeName)`, logout (8.1) |
| `read_io_log` | helper, new operation `ioLog` | login, `getNode()`, `getObject(<processId>@@@<workflow>, user, "inputOutputMessageDetails")`, with a message reference also one `MultipartDownloadThread` of type `InputOutputLogging`, logout |
| `get_node_config` | helper, new operation `nodeConfig` | login, `getNode()`, `getNodeConfigurationProperty(<own node>, <allowlisted name>)` per name, logout |
| `set_temporary_log_level`, `reset_temporary_log_level`, automatic resets | own-node lookup: helper operation `ownNode` (login, `getNode()`, logout); the change: **StartCLI** `trace --nodelevel <LEVEL> --package '<logger>'` (one run per logger) and `trace --reset` through the existing `CliRunner` | as named; never `trace` `on`, `off`, `--level`/`-l` |

**Rationale**: the node list with status, the node details, the configuration values and the I/O
logging have no documented channel (no REST endpoint, no StartCLI command besides the interactive
`nodelist`), so the unsupported helper is the only path; it already isolates the vendor classes in
a child JVM of the right JDK with verified TLS and credentials on stdin (020 D-1/D-7). The log
level has a documented channel — StartCLI `trace -nl`/`-r` — which the spec names (FR-007) and
Constitution V prefers. The own-node lookup must name the node: `nodelist` lists all nodes without
marking the answering one, so it cannot answer "which node answers" on a cluster; the helper's
`getNode()` can, and 021 verified it on both versions.

**Alternatives rejected**: StartCLI `cachemode` + `nodelist` for the node list (interactive shell,
table text to parse, no answering node, no details, no node information); the helper's
`ClusterClient.changeTemporaryLogLevel`/`resetTemporaryLogLevel` for the log level (the same server
requests in one JVM, but an internal path where a supported one exists, and the spec names
StartCLI); one helper operation "call any `ClusterClient` method" (unbounded surface on an
unsupported API, against FR-007).

## D-2 Helper protocol extension (operations `ownNode`, `clusterNodes`, `nodeConfig`, `ioLog`)

**Decision** (020 D-4 / 021 D-4 unchanged for `queueEntry` and `serverLog`):

- **Wire ids stay**: request header `qe=1`, frame prefix `#QE1:` (protocol 1). The server's encoder
  writes `op` only for operations other than `queueEntry` (as 021), so every 020 and 021 request
  is encoded byte-identically and `fixtures/queue-entry/**` and `fixtures/server-log/**` stay
  unchanged (asserted by `HelperRequestTest` exact-encoding tests and `git diff --stat`).
- **stdin keys** (percent-encoded, as 020): the shared keys `qe`, `op`, `nonce`, `variant`, `url`,
  `trustStore`, `trustStorePassword`, `hostnameVerification`, `user`, `password`, plus per
  operation:

  | `op` | keys | rules |
  |---|---|---|
  | `ownNode` | — | — |
  | `clusterNodes` | `details` | empty, or 1–128 chars of `[A-Za-z0-9_.:-]` |
  | `nodeConfig` | `names` | comma-separated, 1–20 names, each from the helper's **own constant allowlist** (D-10); anything else → malformed (exit 2), nothing sent |
  | `ioLog` | `processId`, `workflow`, `ref`, `listOut`, `messageOut` | `processId` `^[0-9A-Za-z_-]{1,64}$`; `workflow` 1–200 chars, no control character, no `@@@`, no `###` (D-8); `ref` empty or `^[A-Za-z0-9_-]{22}$`; `listOut` a path; `messageOut` a path, required iff `ref` is set |

  Keys of another operation, a missing or repeated key, or a value outside its rule → malformed
  request (exit 2) before the login.
- **frames** added (tokens percent-encoded; empty token `-`):
  - `node ok <name>` / `node error <chain> <message>` (from 021; now the first frame after
    `login ok` of every new operation; after `node error` the helper writes `end` without further
    calls);
  - `clusterNodes`: per element of `getNodes()`, for the first 200 only, one `member <name>
    <status> <version> <zone> <nodeId> <uptimeMs>` (`-` for `null`; `status` is the enum
    constant's `name()`, any text; `uptimeMs` `-` when `status` is `null`, as a member without
    answer has the primitive uptime 0; every token cut, whole characters only, at 200 characters
    and at 600 bytes of its percent-encoded form), then `members ok <count>`
    (`count` = all elements) or `members error <chain> <message>`; with `details`: if the name is
    not among the members → `details unknown` (no request); else per flattened node-information
    entry `detail <key> <value>` (at most 500 entries, key ≤ 200 and value ≤ 4,000 characters,
    longer ones cut by the helper with the token `cut` appended; **total budget 256 KiB** of
    encoded `detail` frames — when the next frame would exceed it, the helper stops) and
    `details ok <count> <truncated>` (`truncated` = `true` when the entry cap or the budget
    stopped it), or `details error <chain> <message>`. The byte bound per member token never cuts
    200 ASCII characters (≤ 3 encoded bytes each) but does cut non-ASCII ones (up to 9 bytes per
    character), so a `member` frame is about 3,070 bytes at most and 200 of them ≤ 600 KiB; with the budget, a
    `clusterNodes` answer stays below the 1 MiB stdout cap of 020;
  - `nodeConfig`: per name `config <name> ok <type> <editable> <deprecated> <displayName>
    <category> <description> <value>` (`type` = simple class name of `getType()`, flags
    `true`/`false`, value = `getStringValue()`; the texts cut, whole characters only, at 4,000
    characters and at 12,000 bytes of their percent-encoded form, so 4,000 ASCII characters are
    never cut, and 20 frames of four texts ≈ 960 KB stay below the 1 MiB stdout cap of 020),
    `config <name> absent` (`null` answer) or `config <name> error <chain> <message>`;
  - `ioLog`: the listing is written to `listOut` (UTF-8, one entry per line, the order of the
    answer), then `io list ok <count>` or `io list error <chain> <message>`; with `ref`: the helper
    computes the reference of every listed entry (D-8) and either writes `io match <entry>` and
    downloads it to `messageOut` → `part message ok <bytes>` / `part message error <chain>
    <message>`, or writes `io match none` without a download.
- **Order** per operation: `start`, `login ok`, `node …`, operation frames, `end`. Exit codes as
  020 (0 after `end`; 1 other; 2 malformed; 3 login failed; 4 API missing).
- **API check** before `start` (`api missing <what>`, exit 4), per operation: the 021 members
  (`ClusterClient` four-argument constructor, `getNode()`, `Node.getNodeName()`,
  `NodeName.getName()`, `CurrentUser.getClientUID()`/`getUsedAdditionalUserGroup()`) plus
  `clusterNodes`: `getNodes()`, `getNode(NodeName)`, `NodeName.nodeName(String)`, the `Node`
  getters, `Node.getNodeInformation()`, `NodeInformation.getNodeInformationMap()`,
  `MapProperty.getPropertyNames()`/`get(String)`, `IBISProperty.getStringValue()`;
  `nodeConfig`: `getNodeConfigurationProperty(NodeName, String)` and the `IBISProperty` getters;
  `ioLog`: `I_IBISClientInterface.getObject(String, String, String)` and the 020
  `MultipartDownloadThread` members.
- `HelperRequest`'s sealed `Operation` gains `OwnNode()`, `ClusterNodes(Optional<String>
  details)`, `NodeConfig(List<NodeConfigName> names)`, `IoLog(String processId, String workflow,
  Optional<IoMessageRef> ref, Path listOut, Optional<Path> messageOut)`; `HelperFrames` the frame
  types above; `toString` still leaves out user and passwords.
- **Order of the change** (as 021 D-4): the helper first learns the new `op` names together with
  the encoder (round trips of all six operations named in `HelperRequestTest`), then each operation
  in its own step.

**Rationale**: one helper, one framing, one nonce mechanism; the listing of a long run goes to a
file because frames are capped at 1 MiB (020); a reference is matched inside the helper, so no
caller text reaches a download name (FR-021) and the read needs one JVM, not two.

**Alternatives rejected**: a second helper class (020/021 packaging rule "exactly one class");
the listing as frames (cap); passing the listed entry text back from the server to the helper (a
second JVM run and caller-controlled text in the request).

## D-3 The answering node (FR-004, FR-042)

**Decision**: every new helper operation asks `getNode()` first and reports its name in
`node ok`; the result of every tool names it as `inubitNode`. The name is checked in the adapter
(1–128 chars of `[A-Za-z0-9_.:-]`, else `UNEXPECTED_RESPONSE`, as 021 D-5). `nodeConfig` passes that
`NodeName` explicitly to `getNodeConfigurationProperty`, so name and values belong together (021
D-5 rationale). `clusterNodes` with `details` passes the requested member's name.
For the log level, the own-node lookup (`ownNode`) runs in its own helper process right before the
StartCLI run(s) of a set or reset, and before every automatic reset (FR-042). Behind a load
balancer the lookup and the StartCLI run may reach different nodes; the docs state the
precondition "a server entry addresses one node directly" and this remaining risk (FR-042).

**Rationale**: FR-004 and FR-042 need the node name; `getNode()` is the verified way (021 spikes).

**Alternatives rejected**: `null` as node name for the configuration read (the server substitutes
the answering node, so the reported name could belong to another request); a second lookup after
the change (doubles the JVM starts; does not close the race either).

## D-4 Capabilities, matrix and version selection (FR-005, US5)

**Decision**:
- Four new capabilities (`domain/model/Capability.java`), channel `CLI`:

  | Capability | Kind | Label | `list_nodes` name |
  |---|---|---|---|
  | `CLUSTER_NODES_READ` | READ | "reading the cluster's node list" | `cluster_nodes_read` |
  | `IO_LOG_READ` | READ | "reading module input/output logging" | `io_log_read` |
  | `NODE_CONFIG_READ` | READ | "reading node configuration values" | `node_config_read` |
  | `LOG_LEVEL_WRITE` | WRITE | "changing temporary log levels" | `log_level_write` |

  Exhaustive switches: `Capability.label`, `VersionGate.cliOnlyStep` (reads: "use the Workbench's
  Cluster Manager" / "use the Workbench's input/output logging view"; write: "use StartCLI trace
  -nl/-r by hand"), `VersionGate.supportedLines`, `NodeOverview.name`;
  `list_nodes.output.json` enum + the four names.
- `CapabilityMatrix.production()`: per (version, capability) 8.1 and 9.0 `UNSUPPORTED` with the
  note "not yet verified" until the commit that adds its recorded fixture flips it (D-27); 6.1
  unlisted (= `UNSUPPORTED`); the unknown-version map → `UNSUPPORTED` for all four.
- Admission: reads → `VersionGate.admitWithDetection(tool, node, capability, READ, {CLI})` (020),
  so an undetectable version fails with the detection's own error and nothing is sent; writes →
  the write guard (D-18) with `gate.admit(tool, node, LOG_LEVEL_WRITE, WRITE, {CLI})`; channel
  `CLI` includes the 017 client check.
- Implementation choice: `HelperVariant.forVersion` (8.1 → `SOAP_81`, 9.0 → `REST_90`, else
  `UNSUPPORTED_VERSION`) and `CliRunner.select(server, version)` + `checkLaunch` for the helper;
  `CliRunner.run` with the 017 client choice for StartCLI. No client home → `CLI_UNAVAILABLE` with
  the 017 text naming `defaults.cliClients."M.m"`.
- `Wiring.allowed`: each capability with `withClient && <its opt-in>`.

**Rationale**: four independent opt-ins and four recordings per version → one capability each, so
refusals, `list_nodes` and the fallback stay exact with the existing mechanism (021 D-6).

**Alternatives rejected**: one `NODE_TOOLS` capability (one opt-in state for four tools; a 9.0 read
recorded before the write would enable both).

## D-5 Opt-in settings, validation, summary (FR-002, FR-003)

**Decision** (020 D-15 / 021 D-17 mirrored):
- `UnsupportedConfig(Optional<Boolean> queueEntryRead, serverLogRead, nodeStatusRead, ioLogRead,
  nodeConfigRead, logLevelWrite)`; `Defaults.BUILTIN_NODE_STATUS_READ` … `= false`;
  `EffectiveNodeConfig` `+ boolean nodeStatusRead, ioLogRead, nodeConfigRead, logLevelWrite`
  (group only, passed through every copy method).
- `ConfigLoader.requireStrictUnsupportedOptIn` checks the four new keys (same message form, value
  never echoed); other keys under `unsupported`, the keys on a node or in `defaults` stay unknown
  keys.
- `ConfigValidator`: one production warning per opt-in on a `production: true` group, e.g.
  "unsupported.ioLogRead is enabled on production {group} prod: module input and output messages
  are read through an undocumented INUBIT interface without vendor support; they can contain
  business and personal data". `logLevelWrite` on a group without `write.enabled` on any node is a
  warning "unsupported.logLevelWrite has no effect on {group} test: write access is off" (the tools
  are not offered there; cleanup resets of recorded leases still run).
- `ConfigSummary`: only if some group sets the key, every group line ends with
  `, <key>: off` / `, <key>: enabled (unsupported)` in the order `queueEntryRead`, `serverLogRead`,
  `nodeStatusRead`, `ioLogRead`, `nodeConfigRead`, `logLevelWrite`; the `cliOnly` filter lists a
  new capability only for opted-in groups (020 rule).

## D-6 Announcement (FR-002, FR-040, SC-005)

**Decision** (`Wiring.toolHandlers`):
- `list_cluster_nodes` / `read_io_log` / `get_node_config`: iff some node has the opt-in, is
  `possiblyCapable` for the capability and `clientSelection.anyCandidate(node)`; offered in
  read-only mode, independent of `write.*`.
- `set_temporary_log_level` and `reset_temporary_log_level` (together): iff some node has
  `effectiveWriteEnabled()`, `logLevelWrite`, is `possiblyCapable(LOG_LEVEL_WRITE)` and has a
  client candidate.
- Without any opt-in the tool list is byte-identical to the 021 build (`MainTest`,
  `ToolAnnouncementTest`; SC-005). The lease scheduler (D-15) is created in every profile but finds
  nothing to do without a lease store file; it never creates one.

## D-7 `list_cluster_nodes` result (FR-010, FR-011, US1)

**Decision**:
- Status mapping: `ACTIVE`, `MAINTENANCE`, `OFFLINE` as named; `-` (`null`) → `UNKNOWN` without
  `rawStatus`; any other text → `UNKNOWN` with `rawStatus` (≤ 64 chars, `[A-Za-z0-9_.-]`, else
  `UNEXPECTED_RESPONSE`). `version`, `zone`, `nodeId` ≤ 200 chars each; `uptime` from `uptimeMs` as
  `uptimeSeconds` (integer) and `uptime` (ISO-8601 duration, seconds precision); absent for a node
  without answer.
- Order as returned; at most 200 nodes listed, `total` always and a top-level `truncated`
  (`true` when more than 200 nodes were returned; cap documented, clusters are far smaller).
- `nodeName`: the schema allows 1–128 chars of `[A-Za-z0-9_.:-]`; the helper checks it against
  `getNodes()` and answers `details unknown` → `INVALID_INPUT` naming the listed nodes (at most 20,
  "and n more"). The name never reaches INUBIT unless it is a listed member.
- Details: `[{key, value}]` in the received order; values masked (D-21), key ≤ 200 and value
  ≤ 2,000 characters after masking (cut marker `…[cut]`), at most 500 pairs; `details.truncated`
  is `true` when the helper's entry cap or 256 KiB budget stopped the pairs (D-2);
  warning "The node details contain internal addresses and file paths of the INUBIT
  installation". `details error` → the list is returned with `details: {"available": false,
  "reason": "<scrubbed, ≤ 200 chars>"}` (edge case).
- `members error` → `UNEXPECTED_RESPONSE` (whole call).

## D-8 `read_io_log` listing and message reference (FR-020, FR-021, US2)

**Decision**:
- Input checks before anything: `processId` `^[0-9A-Za-z_-]{1,64}$` (the `query_logs` pattern);
  `workflow` 1–200 chars without control characters and without the separators `@@@` and `###`
  (they would change the object name); `module` 1–200 chars, no control characters; `message`
  `^[A-Za-z0-9_-]{22}$`; `maxChars` 1–100,000, default 20,000.
- Entry parsing (`IoLogEntry.parse`, pure): `^<processId>@@@(?<module>.+)@@@(?<dir>Input|Output)
  \.(?<index>[1-9][0-9]{0,8})$`; module part `<moduleName>(<moduleId>)` split at the **last** `(`
  of a part ending in `)`; an entry that does not parse is listed with `moduleName` = the raw module
  part, `moduleId` absent and a hint (never dropped silently); an entry whose process id differs
  from the request → `UNEXPECTED_RESPONSE`.
- **Message reference** = the first 16 bytes of SHA-256 over the UTF-8 bytes of the full entry,
  Base64url without padding (22 chars). Computed identically in `IoMessageRef.of(entry)` (server)
  and in the helper (JDK `MessageDigest`, `Base64.getUrlEncoder().withoutPadding()`); a shared
  test-vector file pins both. The caller's reference is only compared, never sent as a name; the
  download name is `<workflow>###<listed entry>` built in the helper (FR-021).
- Result: `entries` in server order, filtered by `module` (equals `moduleName` or `moduleId`
  exactly), at most 500 (`IoLogBounds.MAX_LISTED`); `total` (all entries) and `matching` (after the
  filter) always; `truncated` when more matched than listed. Empty listing → hint "No logged
  message for this run: input/output logging may be off for the process or its modules, the
  messages may already be removed, or the run may have executed on another cluster node" (US2-4).
- The listing file is read bounded (≤ 16 MiB, else `UNEXPECTED_RESPONSE`).

## D-9 `read_io_log` message read (FR-021 … FR-023)

**Decision**: one helper run lists and, if the reference matches, downloads (D-2). `io match
none` → `INVALID_INPUT` "message <ref> is not in the listing of this run" with next step "call
read_io_log without message and use a listed reference" (US2-3; no download happened). Content
(020 rules, `QueueEntryService.data` reused through an extracted `EntryText` helper):
- size > `QueueEntryBounds.MAX_DATA_BYTES` (10 MiB) → no content, `sizeBytes` and hint "too large
  to show (limit 10 MiB)" (the file is deleted unread);
- `EntryContentClassifier.classify(bytes)`: binary → `kind: binary`, `detected` type, size only;
  text → UTF-8, else ISO-8859-1;
- text → known credentials replaced (`scrubber::scrub`, as 020), `SecretTextMasker.mask`, then cut
  at `maxChars` (`QueueEntryBounds.cut`, never inside a surrogate pair); `sizeBytes` and
  `textChars` (masked, before the cut) always reported;
- 0 bytes → `kind: empty` with hint "The message is empty or no longer available" (FR-023,
  US2-5); not an error.
The private directory (listing and message) is deleted at the end of the call, on failure, timeout
and shutdown (`CliResources` reservation, as 020).

## D-10 `get_node_config` (FR-030 … FR-032, US3)

**Decision**:
- `domain/model/node/NodeConfigName`: an enum of exactly the 20 allowlisted names of FR-030 (wire
  name = INUBIT name, case-sensitive). The input schema has the same `enum`; the service repeats
  the check (`INVALID_INPUT` listing the allowed names; the refused text is never echoed). No name
  → all 20.
- The helper carries its own constant copy of the 20 names and refuses any other (exit 2) — a
  second line of defence; `NodeConfigAllowlistTest` asserts both lists are equal and contain none
  of `Database`, `ClusterPassword`, `initialRootUserPassword`, `IdentityProvider`,
  `IdentityProviderConfiguration`, `RuntimeDataBackupStore`, `ClusterCacheTaskDataModesConfig`,
  `MaxEntriesInMemoryLimit` (SC-003).
- Result per name: `present: true` with `value` (masked, D-21, ≤ 2,000 chars), `type`,
  `displayName`, `description`, `category`, `editable`, `deprecated`; or `present: false` ("not
  present on this node", e.g. `noOfRetries` on 8.1, US3-4); `config error` for one name → that
  entry `error: {code, message}` and the others still returned.
- Never `setNodeConfigurationProperty` (FR-032): the helper's API map does not contain it; a
  fake-vendor test records every call.

## D-11 Log-level inputs and the StartCLI command (FR-041, FR-007)

**Decision**:
- `LoggerName`: `^com\.inubit\.ibis(\.[A-Za-z_][A-Za-z0-9_]*)+$`, ≤ 200 chars (within
  `CliCommand.VALUE`, so no `$` inner-class names); 1–5 distinct names (a duplicate →
  `INVALID_INPUT`). `TemporaryLevel` enum `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` (so
  `Level.toLevel`'s `DEBUG` fallback is never reached). `durationMinutes` 1–60, default 10.
- `CliCommand`: the command `trace` accepts exactly three forms, each only through its factory:
  `traceDownload(Path, windows)` (021), `traceNodeLevel(TemporaryLevel, LoggerName)` →
  `trace --nodelevel DEBUG --package 'com.inubit.ibis.acme'` and `traceReset()` → `trace --reset`.
  `build()` refuses every other combination: `--level`, `on`, `off`, positional arguments,
  `--nodelevel` without `--package`, `--package` without `--nodelevel`, `--reset` with anything,
  two forms mixed. The long option names are those `TraceCommand` declares (`nodelevel`,
  `package`, `reset`); the spike (D-28) confirms StartCLI parses them (else the short forms `-nl`,
  `-p`, `-r`, decided test-first before the flip).
- One StartCLI run per logger (commons-cli keeps one `-p` value), sequential, each bounded by the
  node's `cliTimeout`; a reset is one run.
- Success = exit code 0, no error marker or classification of
  `CliOutputClassifier.classifyTraceLevel` **and** the confirmation form pinned by the recording
  (the `CommandResult("OK")` output; reconstructed as `1-OK:` until the write spike T039 records it, as 021's T023
  did). `Missing log level.`, any `Exception`/error marker → `UNEXPECTED_RESPONSE` naming StartCLI.

## D-12 Lease store and locks (FR-043, FR-045)

**Decision**: `application/nodetools/LeaseStore`, one file per profile:
`~/.inubit-mcp/<profile>/log-levels/leases.json` (the profile's data directory, as
`DeploymentLedger`/`BackupStore`), store lock file `~/.inubit-mcp/<profile>/log-levels/.lock`,
per-node lock files `~/.inubit-mcp/<profile>/log-levels/nodes/<group>.<node>.lock`.

```json
{
  "format": 1,
  "leases": [
    {
      "leaseId": "3f2b8a1e-0c4d-4e7a-9b1f-2a6c5d8e9f01",
      "generation": 3,
      "server": "test/node1",
      "inubitNode": "node1",
      "loggers": { "com.inubit.ibis.acme.Orders": "DEBUG" },
      "setAt": "2026-11-12T08:15:30Z",
      "expiresAt": "2026-11-12T08:25:30Z",
      "owner": { "pid": 4711, "startedAt": "2026-11-12T07:58:02.114Z" },
      "state": "ACTIVE",
      "auditId": "8c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
      "lastAttempt": { "at": "2026-11-12T08:26:01Z", "outcome": "FAILED", "code": "UNREACHABLE", "reason": "NODE_UNREACHABLE" },
      "endedAt": null
    }
  ]
}
```

- Names, levels, ids and times only; never credentials or log content (`Lease` validates each
  field: server id pattern, node-name rule of D-3, `LoggerName`, UUIDs).
- **Generation**: every update of a lease (merge, executed, takeover, attempt, end) increments
  `generation` (starts at 1). A reset decided on generation `g` may end the lease only if it still
  has generation `g` (compare-and-set under the store lock, D-16); otherwise the outcome is not
  written and the lease is left for the next tick.
- Owner-only through `PrivateFileSystem` (directory `rwx------`, files `rw-------`; Windows ACL);
  written to a private temporary file in the same directory and moved atomically (as
  `DeploymentLedger`); a symbolic link at either path is refused.
- **Locks — one implementation**: the lock of feature 019 (`application/knowledge/KnowledgeLock`:
  in-process `ReentrantLock` per lock-file path, then `FileChannel.tryLock` polled every 50 ms up
  to the caller's wait, lock file created owner-only, never followed through a link, never deleted)
  is generalized into `application/OwnerOnlyFileLock` (lock-file path, wait, the caller's busy
  error); `KnowledgeLock` delegates to it with byte-identical texts (`KnowledgeLockTest`
  unchanged). Two uses:
  - the **store lock** (`.lock`): held for every read-modify-write of `leases.json`, never while
    a helper or StartCLI process runs (short);
  - the **node lock** (`nodes/<group>.<node>.lock`, `NodeLogLevelLocks`): a cross-process lock per
    server, held by a set, a reset and a cleanup reset **around the own-node lookup and the
    StartCLI run(s)** including the write-ahead lease and the final lease update; so across all
    instances of the profile at most one change runs per server at a time. Order is always node
    lock, then store lock (no deadlock).
- An unreadable or invalid file is **never overwritten**: sets refuse `PRECONDITION_FAILED`
  ("the lease store … cannot be read; fix or move it"), resets with `node` still run (they need no
  record) and report that no lease could be updated, the scheduler logs a WARN once per hour,
  `get_health` and `--check-config` show a warning.
- Retention: `RESET` and `FORGOTTEN` leases are removed 7 days after `endedAt` (FR-045).
- No configuration setting; the files are created only by the first set (the node lock file of a
  reset or cleanup only when that runs).

**Rationale**: durable across restarts and instances, readable by `--check-config`, same safety
rules as the existing owner-only state files; the generation plus the node lock close the
two-instance race (a reset decided by one instance while another merges a set).

**Alternatives rejected**: one file per lease (no atomic multi-lease update for takeovers); the
audit log as the store (append-only, not queryable under a lock); memory only (FR-043: durable);
an in-process node lock only (does not serialize two instances); a second lock implementation next
to `KnowledgeLock` (duplicate subtle code).

## D-13 Owner identity and takeover

**Decision**: `LeaseOwner(long pid, Optional<Instant> startedAt)` from `ProcessHandle.current()`
(`info().startInstant()`, millisecond precision). Alive check (`ProcessLiveness` seam):
`ProcessHandle.of(pid)` present and alive, and — when both the recorded and the live start time
are known — equal within one second; when either is unknown, the pid alone decides (documented
limitation: pid reuse can delay a takeover until that process ends). A set that merges into an
existing lease makes the setting instance the owner (FR-043). Takeover writes the new owner under
the store lock and is audited (`trigger: takeover`). Same-host assumption documented (a profile
directory shared between machines is not supported).

## D-14 Set flow (FR-040 … FR-043, US4)

**Decision** (`application/nodetools/LogLevelService.set`), nothing is sent before step 5:

1. Input checks (schema + service, D-11; `confirmationCode` `^[A-Za-z0-9_-]{22}$`) →
   `INVALID_INPUT`.
2. Write guard (D-18): single server, production lock, `write.enabled`, then
   `unsupported.logLevelWrite` (`NOT_CONFIGURED`), version gate (`LOG_LEVEL_WRITE`), client
   available, credentials (`AUTH_FAILED`/`SECRET_UNRESOLVED`).
3. **Node lock** (cross-process, D-12; wait ≤ `cliTimeout`, else `PRECONDITION_FAILED` "another
   log-level change for test/node1 is running"), held until step 12.
4. Lease store: a `RESET_FAILED` lease of this server → `PRECONDITION_FAILED` naming lease id,
   reason and time (US4-11); unreadable store → `PRECONDITION_FAILED`.
5. Own-node lookup (helper `ownNode`, ≤ `cliTimeout`) → `inubitNode`. An `ACTIVE` lease of this
   server naming another node → `PRECONDITION_FAILED` naming both (US4-6).
6. Confirmation (D-19): `SERVER` without code → challenge (nothing changed; audited
   `CHALLENGE_ISSUED`; node lock released); with code → redeem; a changed `inubitNode` or
   open-lease fingerprint (id, state, generation) since the preview → `PRECONDITION_FAILED` naming
   previewed and current state. `CLIENT` → step 7 at once.
7. Audit `PENDING` (`EXECUTE`), forced to disk; failure → `INTERNAL`, nothing sent (R-14).
8. **Write-ahead lease** under the store lock: merge into the server's `ACTIVE` lease (union of
   loggers, the new level per named logger, `expiresAt = max(old, now + duration)`, owner = self,
   generation + 1) or a new lease (`ACTIVE`, generation 1, `expiresAt = now + duration`). Failure
   → `INTERNAL`, nothing sent, audit `FAILED`.
9. StartCLI `trace --nodelevel <L> --package '<logger>'` per logger, sequential.
10. All succeeded → lease update (generation + 1): `setAt` = end of the last run, `expiresAt =
    max(previous expiry of a merged lease, setAt + duration)` (counted from the executed change,
    US4-1). If this update fails → `trace --reset` at once, lease marked `RESET` (or
    `RESET_FAILED` if the reset fails — the next tick retries), result `INTERNAL` "the levels were
    raised but the lease could not be recorded; the node was reset" (edge case).
11. A run failed → `trace --reset` at once (the reset is per node: it also ends a merged lease;
    the result says so); lease `RESET` or `RESET_FAILED`; audit `FAILED` with the code.
12. Audit `EXECUTED` (details `leaseId`, `inubitNode`, `loggers`, `expiresAt`), node lock
    released, result (D-20, contracts).

**Rationale**: the write-ahead record makes a crash between the change and the record a recorded
lease (expiry at most a few seconds early, which only shortens the raise); the expiry after the
change satisfies US4-1; the node lock keeps a cleanup reset of another instance from running
between steps 8 and 10, and the generation keeps a reset decided before step 8 from ending the
merged lease (D-16).

## D-15 Reset and forget flows (FR-044)

**Decision**:
- **`node`**: write guard as D-14 step 2 (US4-7); node lock; own-node lookup; an `ACTIVE` or
  `RESET_FAILED` lease of the server naming another node → `PRECONDITION_FAILED` naming both
  (US4-6); confirmation (preview: the open lease or "no lease is open for test/node1");
  `PENDING`; `trace --reset`; success → the lease (if any) `RESET` with `endedAt` (generation + 1),
  audit `EXECUTED`; result names `inubitNode` and whether a lease was open (US4-4, US4-8); failure
  → lease unchanged except `lastAttempt`, audit `FAILED`.
- **`leaseId` + `forget: true`**: unknown id → `INVALID_INPUT`; lease server no longer configured
  → `INVALID_INPUT` "… ends automatically 7 days after its expiry (on <date>)"; the FR-040 guards
  against that server's group through a separate guard method
  `WriteGuard.admitWithoutContact(node, tool, optIn, optInKey)` (target, production lock, `CLIENT`
  on production, `write.enabled`, `logLevelWrite`; **no version gate, no client check, no own-node
  lookup**: nothing is sent); state ≠ `RESET_FAILED` → `INVALID_INPUT` naming the state;
  confirmation; then the **node lock** (as set and reset, D-12) and, under the store lock, a
  compare-and-set on the generation the confirmation saw (changed → `PRECONDITION_FAILED`, nothing
  forgotten); audit; lease `FORGOTTEN` (generation + 1); result warning "the level may still be
  raised on node1 until its restart" (US4-9).
- Any other input combination (`node` with `leaseId`, `forget` without `leaseId`, `leaseId`
  without `forget: true`) → `INVALID_INPUT` (the schema's `oneOf` and the service).

## D-16 Cleanup and lease scheduler: expiry, takeover, retries (FR-043 … FR-045, SC-004)

**Decision**: two parts.

**Cleanup of one lease** (`LogLevelService.cleanup(Lease decided, Trigger trigger)`), used by the
scheduler and the shutdown:
1. Node lock (cross-process, D-12; wait ≤ 2 s, busy → skip, next tick).
2. Under the store lock: re-read the lease; if its generation differs from the decided one, or it
   is no longer open → stop without sending anything (a set or another instance changed it; the
   next tick decides again).
3. **Cleanup preconditions** (FR-043: node configured, client installation for its version,
   credentials, version supported by `LOG_LEVEL_WRITE` and detectable — **not** `write.enabled`,
   the production opt-in or `logLevelWrite`); not met → `RESET_FAILED` `PRECONDITION` and one
   audit record `REFUSED` with the reason (nothing is sent, so no `PENDING`; if that record cannot
   be written, an ERROR is logged and the reason becomes `AUDIT_NOT_WRITABLE`).
4. Audit `PENDING` (`reset_temporary_log_level`, `trigger`); **fail closed**: if it cannot be
   written, nothing is sent, the lease keeps its state with `lastAttempt` reason
   `AUDIT_NOT_WRITABLE`, an ERROR is logged, and the next tick retries (D-22).
5. Own-node lookup → another node than the lease names → `RESET_FAILED` `OTHER_NODE_ANSWERS`,
   audit `REFUSED`, nothing sent to StartCLI; same node → `trace --reset`. One deadline of
   `cliTimeout` for both processes.
6. Under the store lock: compare-and-set on the generation read in step 2. It is unchanged in
   practice — set, reset and forget all hold the node lock, and a takeover by another instance
   needs this instance to be gone — but if it differs, the outcome is not written, the final audit
   record says "lease changed meanwhile", and the next tick decides again. Otherwise → `RESET` with
   `endedAt`, or `RESET_FAILED` with `lastAttempt` (`code` and
   reason `NODE_UNREACHABLE`, `CLI_FAILED`, …); final audit record.

**Scheduler** (`application/nodetools/LeaseScheduler`), one daemon thread
(`inubit-mcp-log-level-leases`), `scheduleWithFixedDelay(tick, 0, 30 s)`; the first tick runs at
startup without blocking the MCP initialization. A tick, under the store lock (wait ≤ 2 s; busy →
skip): per lease decide `cleanup` (own `ACTIVE` expired; own `RESET_FAILED` or a lease whose last
attempt was refused or not audited, **at most once per 60 s** after its `lastAttempt` — FR-043's
"at least every minute"), `takeover` (owner
not alive, state `ACTIVE` or `RESET_FAILED`: owner := self, generation + 1, audited `trigger:
takeover`; then `cleanup` if expired or failed, otherwise at its expiry by this instance),
`forget` (server not configured and `expiresAt + 7 days` passed → `FORGOTTEN`, audited), `remove`
(ended and `endedAt + 7 days` passed); write these decisions; then run the cleanups outside the
store lock, per node in parallel (bounded pool, one per node).

**Creator decision (owner may override; spec US4-3 read this way)**: an orphaned lease (owner no
longer running) is taken over at once at startup and on every tick; if it is expired or failed it is
reset at once, otherwise this instance resets it at its expiry. US4-3 ("a lease left from an earlier
run … the server starts, then it resets that node") is read as "the next start takes the lease over
and resets the node no later than its expiry", which is FR-043's takeover rule; resetting earlier
than the expiry would cut short a raise the operator asked for. T001 records this in
`reviews/implementation.md`.

Leases of servers that are no longer configured are not reset (no connection data) and end as
`FORGOTTEN` after 7 days (FR-044); until then they are a startup WARN and listed by
`--check-config`.

**Rationale**: a 30-second tick keeps every reset within one minute after expiry (SC-004);
decisions under the store lock and network work outside it keep that lock short; the node lock and
the generation make a decision of one instance safe against a set of another (two-instance edge
case).

## D-17 Shutdown and startup (FR-043, US4-3)

**Decision**:
- `Launcher` (stdin closed): `wiring.resetLeasesOnShutdown()` **before** `wiring.stopCliWork()`
  (which kills StartCLI work and refuses later CLI calls).
- The JVM shutdown hook (SIGTERM) runs the same method first, then `cliResources.close()`; the
  method is idempotent (first caller wins).
- It stops the scheduler, then runs `cleanup` (D-16, trigger `shutdown`, the same fail-closed audit
  rule) for every `ACTIVE` or `RESET_FAILED` lease owned by this instance, all nodes in parallel,
  and waits at most `max(cliTimeout of those nodes)` in total; leases not reset by then stay as
  they are (the next start or another instance takes them over).
- Startup: the scheduler's first tick (D-16, including the creator decision on orphaned leases) is
  the startup cleanup; `Launcher` logs one INFO "n temporary log level lease(s) open" and a WARN per
  `RESET_FAILED` lease and per lease of an unconfigured server (FR-045).

## D-18 Write guard for the log-level tools (FR-040), and feature 022

**Decision**: `WriteGuard` gets an overload `admit(String node, String tool, Capability
capability, Set<Channel> channels, Predicate<NodeId> optIn, String optInKey)` with the same order
as for restart/kill (target, production lock, `CLIENT` on production, `write.enabled`), then the
opt-in (`NOT_CONFIGURED` naming `unsupported.logLevelWrite`), the version gate and the client
check; texts name the tool instead of "restart and kill". A second method
`admitWithoutContact(String node, String tool, Predicate<NodeId> optIn, String optInKey)` runs the
same configuration checks up to the opt-in only (no gate, no client check) for the forget path,
which sends nothing (D-15). The `ProcessAction` overload delegates and keeps its texts
byte-identical (`WriteGuardTest` unchanged).
**Feature 022**: if 022 is merged before this step, its `ChangeGuard` is used in place of the
overload and `src/main/resources/coverage/interface-coverage.tsv` gets the rows `trace -nl`
(reachable, `set_temporary_log_level`), `trace -r` (reachable, `reset_temporary_log_level`) and
`cachemode nodelist` (not used: the own-node lookup goes through the helper, D-1); `CliCommand`'s
`trace` forms are merged with 022's (`on`/`off`/`-l` belong to 022's area A, never to these
tools). Otherwise 022 does the same when it merges second (spec Assumptions).

## D-19 Confirmation (FR-040)

**Decision**: the node's confirmation mode (`WritePolicy.confirmation()`, `confirmationTtl`), as
`restart_process`. `WriteChallengeRegistry` gets node-keyed overloads for a tool name
(`issue(String tool, NodeId node, String inputFingerprint, String previewState, Duration ttl)` and
the matching `redeem`; keys `node:<id>` as the existing `Operation` overloads); the existing
overloads stay. Input fingerprint = SHA-256 of the canonical inputs without the code (tool, node,
sorted loggers with level, duration / `leaseId` + `forget`); preview state = `inubitNode` + the
open lease's id and state. Challenge and preview shape as `restart_process` (contracts).

**Alternatives rejected**: `ConfirmationRegistry` (bound to `ProcessAction`, process id and a
Queue Manager row).

## D-20 Error mapping

| Observation | Code |
|---|---|
| schema/service input check; `nodeName` not listed; message reference not listed; allowlist; forget of a non-`RESET_FAILED` lease or of an unconfigured server's lease | `INVALID_INPUT` |
| unknown target / group id | `TARGET_UNKNOWN` / `INVALID_INPUT` (existing texts) |
| read tool without its opt-in; log-level tool without `logLevelWrite` (after the write checks) | `NOT_CONFIGURED` |
| production lock / `write.enabled` | `PRODUCTION_PROTECTED` / `WRITE_DISABLED` (existing texts, tool named) |
| version gate ("not yet verified", 6.1, other line) | `UNSUPPORTED_VERSION` |
| detection failure (reads) | the detection's own code |
| no client home, `ibis.jar`/JDK missing, launch failure, vendor API missing (`api missing`) | `CLI_UNAVAILABLE` |
| login, TLS, network, 403 (`HelperFailures`; StartCLI classifier) | `AUTH_FAILED`, `TLS_ERROR`, `UNREACHABLE`, `FORBIDDEN` |
| `node error`, `members error`, `io list error`, `part message error`, garbage frames, exit code ≠ frames, invalid node name, unexpected entry format, StartCLI without the confirmation | `UNEXPECTED_RESPONSE` "… the unsupported INUBIT interface may have changed after an INUBIT update" (scrubbed excerpt ≤ 200 chars) |
| `cliTimeout` / `cliExportTimeout` exceeded | `TIMEOUT` naming the setting |
| `RESET_FAILED` lease on set; other node answers; another change running; lease store unreadable; preview state changed | `PRECONDITION_FAILED` |
| invalid/expired confirmation code | `CONFIRMATION_INVALID` |
| audit `PENDING` not writable; write-ahead lease not writable; lease update failed after the change; helper resource missing | `INTERNAL` |

Every message passes `SecretScrubber` (`ResultMapper`) and the `ToolError` excerpt bound.

## D-21 Masking (FR-011, FR-022, FR-031, SC-002)

**Decision**: one application helper `NodeTextMasking.mask(String)` =
`SecretTextMasker.mask(knownSecrets.apply(text))` with the `scrubber::scrub` function 020 uses
(known credentials of every configured node), applied to every node-detail value, every
configuration value (defence in depth) and every message text before the cut. Detail keys and
configuration names are not masked (fixed vocabulary) but bounded. No value, detail or message
text appears in a log line or audit record.

## D-22 Audit and logging (FR-006, FR-043, Constitution VII)

**Decision**:
- Read tools: no audit record (as `read_queue_entry`, 020); one INFO line per call
  (`list_cluster_nodes on test/node1: 3 node(s), details node2|-, outcome OK|<code>`,
  `read_io_log on test/node1: process <id>, listing <n>|message <ref> <bytes>, outcome …`,
  `get_node_config on test/node1: <n> name(s), outcome …`); never values or content.
- Log-level tools: `application/nodetools/LogLevelAudit` like `ProcessControlService`: capability =
  tool name, steps `PREVIEW` (`CHALLENGE_ISSUED`, `REFUSED`) and `EXECUTE` (`PENDING` →
  `EXECUTED`/`FAILED`, `REFUSED`); inputs in fixed order `node`/`leaseId`, `loggers` (names and
  levels), `durationMinutes`, `forget`, `confirmationCode` (SHA-256 prefix); details `leaseId`,
  `inubitNode`, `expiresAt`, `leaseOpen`.
- Automatic resets: capability `reset_temporary_log_level`, inputs `trigger`
  (`expiry`|`retry`|`takeover`|`startup`|`shutdown`|`forget-after-7-days`), `leaseId`; `PENDING`
  before anything is sent, then `EXECUTED`/`FAILED`/`REFUSED` (other node) with the reason.
  **Fail closed, as every other state change (R-14)**: if the `PENDING` record cannot be written,
  no reset is sent, the lease stays `ACTIVE`/`RESET_FAILED` with `lastAttempt` reason
  `AUDIT_NOT_WRITABLE`, an ERROR is logged, and the next tick retries. So every reset that reaches
  INUBIT has an audit record (FR-043).
- **Audit volume** of retries: the retry interval is 60 s (D-16), so a lease that cannot be reset
  produces at most two records per minute (`PENDING` + `FAILED`, or one `REFUSED` for a failed
  precondition or another answering node) — at most 2,880 records per day and lease — until the
  reset succeeds, the lease is forgotten with the tool, or it ends `FORGOTTEN` (unconfigured
  server, 7 days after expiry). Each record is a few hundred bytes; the startup warning and
  `get_health` show the failing lease so the operator can act.
- Account: `WritePolicy.account()` of the server.

## D-23 Lease visibility: `get_health`, startup log, `--check-config` (FR-045)

**Decision**:
- `HealthReport` gets an optional `temporaryLogLevels` list of `LeaseSummary` (additive; schema
  `$defs/LeaseSummary`):
  per `ACTIVE` or `RESET_FAILED` lease of the node: `leaseId`, `inubitNode`, `loggers`
  (`[{name, level}]`), `setAt`, `expiresAt`, `state`, `lastAttempt` (`at`, `outcome`, `code`,
  `reason`). Read through a `LeaseView` port (read-only snapshot, lock wait ≤ 2 s; failure → a
  warning in the report, never an error). Shown whether write tools are on or off; absent when the
  node has none.
- `--check-config` and the startup summary: a section `Temporary log levels:` only if the store has
  a non-ended lease: one line per lease (`test/node1 (INUBIT node node1): 2 logger(s) at DEBUG
  until 2026-11-12T08:25:30Z, ACTIVE`), leases of unconfigured servers with "(server no longer
  configured; forgotten automatically on <date>)", and an unreadable store as a warning.
  `--check-config` only reads; it never resets or takes over.

## D-24 Domain, ports and packages

**Decision**:
- `domain/model/node/` (new package): `ClusterNode`, `NodeStatus`, `NodeDetail`, `ClusterNodes`,
  `IoDirection`, `IoLogEntry`, `IoMessageRef`, `IoLogListing`, `IoLogBounds`, `NodeConfigName`,
  `NodeConfigValue`, `LoggerName`, `TemporaryLevel`, `Lease`, `LeaseState`, `LeaseOwner`,
  `LeaseAttempt`, `LeaseReason`, `LeaseSummary`, and the result records `ClusterNodesResult`,
  `IoLogResult`, `NodeConfigResult`, `LogLevelResult` (`Challenge` | `Outcome`).
- `domain/port/`: `ClusterNodePort` (`nodes(Optional<String> details, version, deadlineNanos)`),
  `IoLogPort` (`list(processId, workflow, version, deadline)`, `read(…, IoMessageRef ref, …)` →
  `IoLogRead` with a private file, `AutoCloseable`), `NodeConfigPort` (`values(names, version,
  deadline)`), `LogLevelPort` (`answeringNode`, `raise(level, logger)`, `resetAll`), `LeaseView`
  (read-only, for `HealthService`); `Gateway`/`GatewayFactory` default methods throwing
  `CLI_UNAVAILABLE` (020 D-17 pattern); `PortSet` component with a compatibility constructor;
  `V81PortSet`, `V81Gateway`, `AdapterGatewayFactory`, `StaleReportingPorts` (forward),
  `RefusingPorts` override.
- `adapter/cli/node/` (new): `NodeHelperRunner` (variant, selection, `checkLaunch`, TLS check +
  one-time trust store, private directory, slot `acquireForHelper`, `ProcessRun`, `HelperFailures`;
  modelled on `CliWebServiceLogAdapter`), `NodeFramesClassifier`, `CliClusterNodeAdapter`,
  `CliIoLogAdapter`, `CliNodeConfigAdapter`, `CliLogLevelAdapter` (own node via the runner,
  StartCLI via `CliRunner`).
- `application/nodetools/` (new): `ClusterNodeService`, `IoLogService`, `NodeConfigService`,
  `LogLevelService`, `LeaseStore`, `LeaseScheduler`, `ProcessLiveness`, `LogLevelAudit`,
  `NodeTextMasking`, `NodeLogLevelLocks`; `application/OwnerOnlyFileLock` (generalized from
  `KnowledgeLock`, D-12).
- `mcp/tools/`: `ListClusterNodesTool`, `ReadIoLogTool`, `GetNodeConfigTool`,
  `SetTemporaryLogLevelTool`, `ResetTemporaryLogLevelTool`; schemas `<tool>.{input,output}.json`.
- `PackageBoundaryTest`: `application` only via ports; `Process*` only in `adapter/cli`; the
  helper references only `java/*`.

## D-25 Tool annotations and descriptions (FR-003)

**Decision**: reads `ToolHints.readOnly(<title>, true)` (open world); writes
`ToolHints.destructive(<title>)` (as `restart_process`). Descriptions in
contracts/mcp-tools-delta.md; `NodeToolsDocsTest` asserts their key phrases ("unsupported",
"undocumented", "without vendor support", "diagnosis only"; for `read_io_log` "business and
personal data" and "no right beyond the login"; for the two log-level tools also "every temporary
log level on that node, also those other users set") and the FR-003 sentences in `docs/setup.md` and
`docs/tools.md`. The set and reset descriptions carry the same FR-003 sentence as the reads
("UNSUPPORTED: uses unsupported, undocumented INUBIT functionality without vendor support or
stability guarantee, for diagnosis only"), because the answering-node lookup goes through the
internal interface and the tools exist for diagnosis.

## D-26 Fixtures: neutralized recordings, fictitious content (Constitution III/V)

**Decision**: fixture root `src/test/resources/fixtures/node-tools/{v8_1,v9_0}/{cluster-nodes,io-log,node-config,log-level}/`
with `README.md` and per case `<case>.origin` (`recorded <date> <version> neutralized` or
`reconstructed <source>`), checked by `NodeToolsFixtureCatalogueTest`.
- Helper cases: `<case>.frames`, `<case>.exit`, for `io-log` also `<case>.list` and
  `<case>.message`. StartCLI cases (log level): `<case>.stdout`, `<case>.stderr`, `<case>.exit`.
- Cases: cluster-nodes `ok` (recorded in T025: one `ACTIVE`, eleven `OFFLINE`), `no_answer` (two
  members, one without answer), `single`, `details`, `details_truncated`, `details_unknown`,
  `details_error`, `unknown_status`, `api_missing`, `garbage`; io-log `listing`, `empty_listing`, `read_text`, `read_latin1`, `read_binary`,
  `read_empty`, `no_match`, `foreign_process`, `list_error`; node-config `all`, `absent`,
  `one_error`, `api_missing`; log-level `set_ok`, `reset_ok`, `missing_level`, `login_failure`,
  `forbidden`.
- **Neutralization**: every recorded name, address, path, process, module, logger and value is
  replaced by fictitious ones (`acme`, `globex`, `node1`, `node2`, `*.example.test`,
  `/opt/inubit/...`), message content replaced by fictitious content of the same shape and size;
  `tools/neutralize.py` with the private map, `NoCustomerIdentifiersTest` with the private lists and
  `python3 -I tools/check-identifiers.py --all`; **the owner reviews the diff of the fixture files
  before the commit** (as 021 T033/T046).
- A shared vector file `fixtures/node-tools/io-message-ref.vectors` pins the reference algorithm
  for the server class and the helper.

## D-27 Contract tests per version (Constitution V, FR-005)

**Decision**: `NodeToolsV81ContractTest` / `NodeToolsV90ContractTest` replay each case through the
real adapter and service (`FakeProcessLauncher.onLaunch` reads the request from stdin, writes
`.list`/`.message` to the requested paths, replays `.frames`/`.exit`; StartCLI cases replay
`.stdout`/`.stderr`/`.exit`), with the test matrix declaration. `NodeToolsFixtureCatalogueTest`:
matrix `SUPPORTED` for (version, capability) ⇔ a `recorded` `.origin` of that version and tool
directory (`ok` for cluster-nodes, `listing` and `read_text` for io-log, `all` for node-config,
`set_ok` **and** `reset_ok` for log-level). The helper operations are tested in-process against
fake vendor classes under `src/test/java/com/inubit/ibis/…` (extended: `ClusterClient.getNodes`,
`getNode(NodeName)`, `getNodeConfigurationProperty`, `NodeList`, `Node$Status`, `NodeInformation`,
`MapProperty`, `IBISProperty`, `I_IBISClientInterface.getObject`), recording every vendor call,
and once per operation in a real child JVM.

## D-28 Live spikes and recordings (owner approval; never production)

**Decision**: early tasks, in parallel to the offline work, on the non-production 8.1 and 9.0
test servers of the profile (owner's standing permission for read-only checks there):
1. **Read spike** per version (read-only): a throw-away program outside the repository performs
   `ownNode`, `clusterNodes` (with `details` of the answering node), `nodeConfig` for the 20
   allowlisted names only, and `ioLog` listing + one download for a run the owner names (a run of
   a process with I/O logging on); also with a user **without** the `Configuration` right if the
   owner provides one (confirms the discarded permission check). Records frames-equivalent output,
   node-information keys, the `getModuleNameAndId` form, the empty-message behaviour if it occurs,
   and whether the I/O listing keys on the Queue Manager id or the log's process id.
2. **Log-level write spike** per version (changes a running test server): only after the owner
   approved the **concrete action** in the chat — server, logger (proposed:
   `com.inubit.ibis.cli` at `INFO`), the two StartCLI runs `trace --nodelevel INFO --package
   'com.inubit.ibis.cli'` then `trace --reset` right after, and the private output folder. Records
   stdout/stderr/exit of both runs (the `CommandResult("OK")` form), confirms that only the named
   logger changed and that the persistent trace state is unchanged (get_health `systemInfo`
   before/after, SC-004).
Raw outputs stay owner-only outside the repository; findings go to "Spike findings" (appended) and
neutralized into the fixtures (D-26). A 9.0 capability without its recording stays "not yet
verified" (FR-005); an 8.1 capability without its recording does not ship.

## D-29 Live check

**Decision**: `live/NodeToolsLiveTest` (`@Tag("live")`, `-Plive`, production refused by
`LiveTarget`): `INUBIT_LIVE_NODE`, optional `INUBIT_LIVE_IO_PROCESS` (`<workflow>/<processId>`),
`INUBIT_LIVE_LOG_LEVEL=approved` (the write part runs only with it, the owner's approval of that
run); calls the read tools through the wired server; with the write flag: set one logger at `INFO`
for 1 minute, checks `get_health` shows the lease, waits for the automatic reset (≤ 2 minutes),
checks the lease is `RESET` and the audit records exist. Prints `[live] …` summary lines without
values. `docs/live-tests.md` section "Unsupported node tools (feature 024)".

## D-30 Feature 021 base

**Decision**: this branch is based on `021-server-log-download`; once 021 is merged, rebase on
`main` before the implementation starts (T001). The shared helper classes of 021
(`adapter/cli/helper`) are extended, not copied.

## D-31 Documentation

**Decision**: `docs/setup.md` `### Unsupported functions`: rows and examples for the four keys, the
FR-003 texts (contracts/configuration-delta.md), the cleanup-reset deviation, the "address one
node directly" precondition; `docs/tools.md`: sections `## list_cluster_nodes`, `## read_io_log`,
`## get_node_config`, `## set_temporary_log_level`, `## reset_temporary_log_level` (inputs,
results, bounds, errors, capability rows, announcement rule), `get_health`'s `temporaryLogLevels`;
`README.md` tool table and announcement rows; `SECURITY.md`: I/O messages (business/personal data,
masking limits), the lease store file, the cleanup resets while write tools are off; `CHANGELOG.md`
`[Unreleased]`; `docs/live-tests.md` (D-29).

## Spike findings

### T018 – 8.1 read spike (2026-11-13, non-production 8.1.17 test node, SOAP_81 helper of this branch)

Raw output stays owner-only outside every repository. Neutral facts only:

- **ownNode**: `start`, `login ok`, `node ok <name>`, `end`; exit 0; about 1.7 s. The name follows the node-name rule.
- **clusterNodes**: 12 `member` frames, `members ok 12`; exit 0. The cluster keeps nodes that no longer answer: 1 `ACTIVE` (the answering node, version = the detected version, uptime > 0) and 11 `OFFLINE`. Every `OFFLINE` member has version `-` and uptime `0` (not `-`: the status is not `null`, so D-7's "`uptimeMs` `-` only for a `null` status" holds). Zone and node ID are `-` for all 12 members, the `ACTIVE` one included. No `null` status was seen (`UNKNOWN` stays reconstructed). All names follow the node-name rule.
- **clusterNodes with `details`** (the answering node): the same members, then 23 `detail` frames and `details ok 23 false`; no value cut. Keys: `ServerVersion`, `ServerName`, `ClusterName`, `NodeName`, `InternalNodeAddress`, `ZoneName`, `ServerIbisRoot`, `ServerOSName`, `ServerOSArch`, `ServerOSVersion`, `ServerProcessors`, `ServerJDKName`, `ServerJDKVersion`, `ServerJDKSpec`, `ServerJDKVendor`, `ServerJDKLocale`, `ServerJDKTimezone`, `ServerJDKCharset`, `ServerXMX`, `ServerLibraryPath`, `ServerXMLSaxDriver`, `ServerXMLParserVersion`, `ServerXMLProcessorVersion`. `InternalNodeAddress`, `ZoneName` and `ServerLibraryPath` were `-` (`null`) on this single node. `NodeName` equals the `node ok` name.
- **nodeConfig** (all 20 allowlisted names in one call): exit 0, about 4.6 s. 11 names answer `ok` (`ClusterName`, `NodeName`: String; `LocalMaintenanceMode`, `ActivateClusterManager`, `ClusterDebug`, `indexEnabled`: Boolean; `RuntimeDataCacheXMXPercentage`, `iterationCount`, `noOfRetries`: Integer; `retryInterval`: Long; `DataSourceLocation`: String), all `editable true`, `deprecated false`. **Deviation from D-10**: the other 9 names (`OperationMode`, `ZoneName`, `ContainerShutdownOnNodeFailure`, `RegisterMBeans`, `RegisterInternalMBeans`, `socketTimeoutInMillisecs`, `LogsDBDataSourceLocation`, `RepositoryDataSourceLocation`, `ClusterBindAddress`) do not answer `null` (`absent`) but `config <name> error com.inubit.ibis.utils.InubitException …`, the same message for all nine: an internal INUBIT error wrapping `Cannot invoke "Object.getClass()" because "o" is null` – the server fails on a property the node does not have. `absent` was not seen. **Also**: `noOfRetries` and `retryInterval`, listed as 9.0-only, exist on 8.1.17.
- **ioLog listing** (three real runs of the last hour, by the log's process ID and by the global process ID, plus a workflow name that does not exist): every call answered `io list error com.inubit.ibis.utils.InubitException` with the INUBIT message `@Start@NotAllowedAccess@@@The user "<user>" is not allowed to access the requested data.@@@<user>@End@`, for the non-existing workflow as well. **Deviation from D-2/D-8**: the listing is checked against a right of the account (not per workflow); the configured account of the test node lacks it. Which right is not known from the client. Not determined therefore: the `getModuleNameAndId` form, whether the listing keys on the Queue Manager id or the log's process id, the 0-byte case, the download.
- The configured account's `Configuration` right was not checked separately (no account without it was provided).
- stderr of every run: one JVM notice line about the project's own `JAVA_TOOL_OPTIONS`; stdout had only frames.
