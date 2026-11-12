# Research: Download Server Log Files (trace.log, webservice.log)

**Feature**: 021-server-log-download | **Date**: 2026-11-11 | **Spec**: [spec.md](spec.md)

Base: `main` at 0.9.1 (features 015, 017, 020 merged). Sources: `javap -c -p -v` of `lib/ibis.jar`
of the 8.1.17 and 9.0.10 client installations (no live call against INUBIT was made for this
research), and the code of this branch. Paths below are relative to
`src/main/java/de/dadecker/inubit/mcp/` unless they start with `src/`, `docs/`, `specs/` or
`pom.xml`.

### Static facts this research builds on

Identical in 8.1.17 and 9.0.10 unless a column says otherwise.

| Topic | Finding (bytecode) |
|---|---|
| StartCLI `trace` (`com.inubit.ibis.cli.cmds.TraceCommand`) | options `d`/`download` (arg `file`, "Download trace log and store to file."), `p`/`package`, `l`/`level`, `nl`/`nodelevel`, `r`/reset, positional `on`/`off`. `execute` tests `d` **first** and returns its result (`goto` to the end), so `-d` never reaches another branch. No args → `getTracingState()` (reads `ServerConfiguration.getTracingIsActive()`, read-only); a positional arg → `setTracingActive` (changes the server). |
| `trace -d <file>` | `new FileOutputStream(file)` **before** the download, then `I_IBISClientInterface.downloadFile(user, "trace.log", "tracelog", out)`, flush, close; result `CommandResult("OK", "Trace data (<n> MB) stored to: <absolute path>")`; an exception → `CommandResult(Throwable)`. A failed download therefore leaves an empty or partial file at `<file>` on the client side. |
| Server, trace (`LoggingDownloadServiceRequestHandler.handleDownload`) | `trace.log` → `DefaultUploadDownloadUtil.getFileContent(<log dir>/trace.log)` = `IBISFileHandler.readFileAsBytes` (the whole file in the server's heap). A missing file → `InubitException("FileWasNotFound", …, <absolute path>)`; another `IOException` → "Cannot read file [<path>]!". |
| Server, `webservice.log` through `downloadFile` | `webservice.log` is in the handler's `canHandleRequest` list, but `handleDownload` has **no** branch for it (only `user.log`, `system.log`, `trace.log` and module logs) → "Unsupported request […]!". The documented download path does not serve the web service log. |
| `com.inubit.ibis.server.cluster.ClusterClient` | `ClusterClient(I_IBISClientInterface client, String user, String clientUID, String additionalUserGroup)`; `Node getNode()` (no args) sends `GetNodeRequest` through `client.getObject(marshall(request), user, "Cluster###" + clientUID)`; `MultipartDownloadThread clusterDownload(File out, NodeName node, String fileName)` builds `ClusterDownloadRequest{nodeName, fileName}` and returns `new MultipartDownloadThread(client, user, marshall(request), "ClusterDownload", clientUID, additionalUserGroup)` with `setOutputFile(out)`, **not started**; also `clusterDownloadTrace(File, NodeName)` (= `clusterDownload(…, "trace.log")`). |
| How StartCLI and the Workbench build it | `cli.SessionImpl`: `new ClusterClient(getIBISClient(), getUser(), CurrentUser.getClientUID(), CurrentUser.getUsedAdditionalUserGroup())`; `CurrentUser.getClientUID()` creates a random UID lazily. StartCLI's cache shell (`cli.cache.TRACECommand`, `trace <NODENAME> [-d <FILENAME>]`) and the Workbench's `NodeTraceLogPanel` use `clusterDownload*`. |
| `Node` / `NodeName` | `Node.getNodeName()` → `NodeName`; `NodeName.getName()`, `NodeName.nodeName(String)`. |
| Server, `GetNodeRequest` (`ClusterService`) | `checkConfigurationPermission()` (permission `Configuration`), then `getNode(request.getNodeName())`; a `null` node name → the answering node itself. |
| Server, `ClusterDownload` (`MultiTransferDownloadServiceRequestHandler.buildClusterDownloadHandler`) | requires `DataFacade instanceof CacheDataFacade`, else `InubitException("Server not in cluster mode.")` (the task brief's reading of `DataFacadeFactory`: a `CacheDataFacade` for `SINGLE` and `CLUSTER`, so single-node servers qualify; to be confirmed live, D-23). No permission check in the handler beyond the session. |
| Server, `ClusterDownloadHandler` | a `null` node name in the request → the answering node's own name; `init` runs `ClusterDownloadCallable` on that node: `new File(IBISLogDirectory, fileName)`, `isFile()` false → returns `null` → `init` throws `InubitException("File <fileName> not found on <node>")`; otherwise the file is copied into the cache `BinaryStore` and streamed. The file name is **not validated** (`LOGFILE` enum `TRACE "trace.log"`, `WEB_SERVICE "webservice.log"` exists but is not enforced). |
| 9.0 client object | `InubitClientFactory.getRestInstance(ConnectionData)` → `InubitRestClient`, which implements `getObject(String, String, String)` through its `GetObjectHandler` (internal REST); `MultipartDownloadThread` over this client was verified live on 9.0 by feature 020. |

---

## D-1 trace.log through the existing StartCLI channel

**Decision**: `log: trace` runs StartCLI with `--execCommand "trace --download '<tmp>/trace.log'"`
through `CliRunner.run` (argument array, credentials on stdin, `CliOutputClassifier`, 017 client
choice, pin probe), in a new adapter `adapter/cli/CliTraceRunner` built like
`CliExportRunner.export` (`adapter/cli/CliExportRunner.java:392-437`):

- `CliCommand` (`adapter/cli/CliCommand.java:58-71`) gets the command `trace` with exactly one
  allowed option `--download` (a value option, only through `Builder.path`), and a factory
  `CliCommand.traceDownload(Path file, boolean windows)`. `build()` refuses `trace` without
  `--download`; no other option, flag, positional argument or process id is accepted for it
  (FR-005: never `on`, `off`, `-l`, `-nl`, `-p`, `-r`). The long form `--download` is the one
  `TraceCommand` declares (`withLongOpt("download")`).
- The file is `<private dir>/trace.log` (D-7); the path must pass `CliPaths.passable` (existing
  `checkPreconditions`, `CliExportRunner.java:370-390`: temp root usable, credentials complete).
- Success: exit code 0, no error marker or error classification of
  `CliOutputClassifier.classifyTraceDownload`, **and** exactly one stdout line matching
  `^Trace data \(\S+ MB\) stored to: .+$` **and** a regular file at the requested path. The path
  in the message is never used or returned. Anything else → D-14. *(Corrected after the 8.1.17
  recording, see "Spike findings": StartCLI prints this line **without** an `n-OK:` prefix, so
  `CliOutputClassifier.classify`'s "success = at least one `n-OK` message" does not apply.)*

**Rationale**: FR-005 requires the documented command through the existing channel; the channel
already provides argument arrays, `--execCommand` quoting, timeouts, exit-code evaluation, a
tested parser and the login back-off (Principle V). The `-d` branch returns before every
state-changing branch, so the single allowed form cannot change tracing or log levels.

**Alternatives rejected**: the helper with `downloadFile(user, "trace.log", "tracelog", out)` (the
same server request without StartCLI, but FR-005 names StartCLI and the helper is the unsupported
path); `ClusterClient.clusterDownloadTrace` (internal interface, would need the opt-in for a log
that has a supported path); StartCLI's cache shell `trace <NODENAME> -d` (interactive mode, needs
the INUBIT node name, cluster-oriented, not the documented command).

## D-2 webservice.log through the INUBIT client helper (Cluster Manager download)

**Decision**: `log: webservice` is read by the feature-020 helper (D-3) with a new operation that
does exactly what StartCLI's session and the Workbench's Cluster Manager do:

1. create the client of the variant (8.1 `IBISSoapClient`, 9.0 `ConnectionData` +
   `InubitClientFactory.getRestInstance`; unchanged from 020) and `loginUserCli(user, password)`;
2. `cluster = new ClusterClient(client, user, CurrentUser.getClientUID(),
   CurrentUser.getUsedAdditionalUserGroup())` (as `cli.SessionImpl`);
3. `node = cluster.getNode().getNodeName()` (own node lookup, D-5);
4. `thread = cluster.clusterDownload(new File(logOut), node, "webservice.log")`; `start()`,
   `join()`, `exceptionOccured()`/`getException()`;
5. `logoutUser(user)` on 8.1 in a `finally` (as 020).

The file name `webservice.log` is a **constant in the helper**; the request carries only the log
kind `webservice` (D-4). No other vendor method is called (FR-006: login, own node lookup,
download, logout).

**Rationale**: the only interface that delivers `webservice.log` (the documented download answers
"Unsupported request", static facts); the helper already isolates the vendor classes in their own
JVM with the right JDK, verified TLS and credentials on stdin (020 D-1, D-4, D-7). The server does
not validate the file name, so keeping the name out of every input closes the path-traversal risk
the spec names.

**Alternatives rejected**: `downloadFile(user, "webservice.log", …)` (no handler branch); building
`ClusterDownloadRequest` and `MultipartDownloadThread` by hand (duplicates vendor marshalling, more
reflection surface); passing `null` as client UID and user group (020 did so for
`MultipartDownloadThread`, but `getObject` appends `"Cluster###" + clientUID`; mirroring
StartCLI is the verified client behaviour — the spike confirms, D-23).

## D-3 One helper with two operations; shared helper code moves to `adapter/cli/helper`

**Decision**:
- The helper class is **renamed** `QueueEntryHelper` → `ClientHelper` (same package
  `de.dadecker.inubit.mcp.helper`, same rules of 020 D-2: one top-level class, no nested or
  anonymous classes, JDK classes only, reflection only, `release 17`). It gets a request key `op`
  with the values `queueEntry` (the 020 read, unchanged) and `serverLog` (D-2).
- `pom.xml`: execution `compile-queue-entry-helper` → `compile-client-helper`, output
  `META-INF/inubit-mcp/client-helper/`. The packaging test asserts exactly one class file
  (`ClientHelper.class`, major 61); `PackageBoundaryTest` follows the rename.
- The protocol ids stay: request header `qe=1`, frame prefix `#QE1:` (protocol 1). So the recorded
  020 fixtures (`fixtures/queue-entry/**.frames`) stay byte-identical.
- Adapter classes used by both operations move from `adapter/cli/queue` to a new package
  `adapter/cli/helper`: `HelperInstallation`, `HelperTrustStore`, `HelperRequest` (generalized,
  D-4), `HelperFrames` (new frame types, D-4), and the login/connection part of
  `QueueEntryHelperClassifier` as `HelperFailures` (shared mapping of login, TLS, network, 403,
  API-missing, protocol problems). `adapter/cli/queue` keeps the queue-specific classes;
  `adapter/cli/log` gets the new log adapter.
- This is **stage 1** of the implementation: a behaviour-preserving refactor with the 020 tests
  (moved and renamed with their classes) as the regression net, before any 021 behaviour.

**Rationale**: a second helper class would duplicate ~300 lines (request parsing, percent coding,
frames, client creation, error chains) that must stay identical, and the 020 packaging rule
("exactly one class") would need a second resource path; a helper named after one of its two
operations and shared classes living in the other operation's package would mislead readers. The
rename and move are mechanical and covered by the existing tests.

**Alternatives rejected**: `ServerLogHelper` as a second class (duplication, two resources);
extending `QueueEntryHelper` in place without rename and importing `adapter/cli/queue` from
`adapter/cli/log` (misleading names, cross-feature dependency); a generic "call any vendor
method" helper (an unbounded interface to an unsupported API, against FR-006).

## D-4 Helper protocol, operation `serverLog`

**Decision** (020 D-4 unchanged for the queue-entry read; the new key `op` is optional):

- **stdin** (percent-encoded values, as 020):

  ```
  qe=1
  op=serverLog
  nonce=<32 hex>
  variant=SOAP_81 | REST_90
  url=<cli url of the selection>
  trustStore=<path or empty>
  trustStorePassword=<one-time password or empty>
  hostnameVerification=true | false
  log=webservice
  logOut=<tmp>/out/webservice.log
  user=<INUBIT user>
  password=<INUBIT password>
  end
  ```

  `op=queueEntry` keeps its 020 keys (`objectType`, `fileName`, `parts`, `dataOut`,
  `variablesOut`). Keys of the other operation are a malformed request (exit 2). `log` accepts only
  `webservice`; the helper maps it to the constant file name.
- **`op` is optional in the helper, default `queueEntry`**: a request without `op` is a 020
  queue-entry request; `op=queueEntry` is accepted as well; any other value, a repeated `op` or a
  missing operation key is a malformed request (exit 2). The server's encoder writes `op` **only
  for `serverLog`** (as the second line, after `qe=1`); a queue-entry request is encoded exactly as
  in 020. So the 020 exact-encoding test, the round trip through the real helper, hand-built 020
  test requests and `fixtures/queue-entry/` stay unchanged, and the wire id `qe=1` needs no bump
  (the helper and the server always ship in the same JAR).
- **Order of the change**: the helper learns the optional `op` (accepting only `queueEntry`) in the
  same step as the encoder and `HelperRequest`'s sealed `Operation`, so that step ends green with
  the 020 round trip unchanged; the `serverLog` operation and its round trip through the real
  helper follow in the next step. Both operations keep a named round-trip test in
  `HelperRequestTest`.
- **frames** added: `node ok <nodeName>`, `node error <exceptionChain> <message>`,
  `part log ok <bytes>`, `part log error <exceptionChain> <message>`. `start`, `login …`,
  `api missing …`, `end` as in 020. Order for `serverLog`: `start`, `login ok`, `node ok`,
  `part log …`, `end`; after `node error` the helper writes `end` without a download.
- **API check** before `start` (`api missing <what>`, exit 4): `ClusterClient` with its
  four-argument constructor, `getNode()`, `clusterDownload(File, NodeName, String)`;
  `Node.getNodeName()`; `NodeName.getName()`; `CurrentUser.getClientUID()`,
  `getUsedAdditionalUserGroup()`; the 020 members of the variant and `MultipartDownloadThread`
  (`start`/`join` via `Thread`, `exceptionOccured`, `getException`).
- `HelperRequest` becomes a record with the shared connection fields plus a sealed
  `Operation` (`QueueEntryRead(objectType, fileName, dataOut, variablesOut)` |
  `ServerLogRead(kind, logOut)`); `toString` still leaves out user and passwords.

**Rationale**: the 020 framing, nonce and file hand-off carry over; the log never passes stdout.

## D-5 The node the web service log comes from

**Decision**: the helper asks `getNode()` (the answering node) and passes **that** `NodeName`
explicitly to `clusterDownload`; the result reports `nodeName.getName()` as `inubitNode`. The
INUBIT node name is checked in the adapter (1–128 chars of `[A-Za-z0-9_.:-]`, else
`UNEXPECTED_RESPONSE`), because it appears in the result and the log line.

**Rationale**: `getNode` and the download are separate HTTP requests; behind a load balancer they
may reach different nodes. With the explicit name the server runs the copy on the named node
through its distributed executor, so the file and the reported name always belong together
(edge case "Load-balanced server address"). A directly addressed node (the documented setup)
answers for itself in both requests.

**Alternatives rejected**: `null` as node name (the server substitutes the answering node, so a
second, possibly different node could deliver the file while the result names another);
`getNodes()` and choosing by host name (needs mapping rules INUBIT does not document).

## D-6 Version → implementation, capabilities and client installation (FR-002, US3)

**Decision**:
- Two new capabilities (`domain/model/Capability.java`), kind `READ`, channel `CLI`:
  `TRACE_LOG_READ` (label "downloading the trace log", `list_nodes` name `trace_log_read`) and
  `WEBSERVICE_LOG_READ` (label "downloading the web service log", `webservice_log_read`).
  Exhaustive switches: `Capability.label`, `VersionGate.cliOnlyStep` (both: "download the log in
  the Workbench (Administration, Server Log)"), `NodeOverview.name`.
- `CapabilityMatrix.production()`: per (version, log), 8.1 and 9.0 alike, `SUPPORTED` in the
  commit that adds its recording, before that `UNSUPPORTED` with the note "not yet verified" (020
  D-6/D-20 mechanism, per log); 6.1 unlisted (= `UNSUPPORTED`); the unknown-version map gets both
  → `UNSUPPORTED`. A
  detected 6.1, 9.1, other or undeclared version is refused with `UNSUPPORTED_VERSION` naming the
  version and the supported lines (`VersionGate.supportedLines` extended to both).
- **8.1 needs its recordings as much as 9.0** (FR-017): the recordings are early tasks (D-23), so
  the matrix is flipped per version and log in the commit that adds the recording (catalogue
  test, D-22); the end state after all four recordings is both logs `SUPPORTED` on 8.1 and 9.0.
- Undetectable version (US3-3): `VersionGate.admitWithDetection(tool, node, capability, READ,
  {CLI})` (020), so the detection error's own code and message are reported and nothing is sent.
- Admission per call: `log: trace` → `TRACE_LOG_READ`; `log: webservice` → opt-in first
  (`NOT_CONFIGURED`, D-17), then `WEBSERVICE_LOG_READ`. Channel `CLI` includes the StartCLI client
  check of 017 for both logs (range, mismatch warning).
- Implementation choice: trace → `CliTraceRunner` (the 017 client choice inside `CliRunner`);
  webservice → `adapter/cli/log/CliWebServiceLogAdapter` with `HelperVariant.forVersion`
  (`QueueEntryVariant` renamed and moved to `adapter/cli/helper`: 8.1 → `SOAP_81`, 9.0 →
  `REST_90`, else `UNSUPPORTED_VERSION`), `CliRunner.select(server, version)` + `checkLaunch` (020
  D-6). No client home → `CLI_UNAVAILABLE` with the 017 text naming `defaults.cliClients."M.m"`.
- `Wiring.allowed` (`Wiring.java:808-831`): `TRACE_LOG_READ` with `withClient`;
  `WEBSERVICE_LOG_READ` with `withClient && serverLogRead`. `list_nodes.output.json` enum gains
  both names (additive).

**Rationale**: the two logs use different channels, different opt-in rules and may get their 9.0
verification at different times; one capability per log keeps refusals, `list_nodes` and the
fallback exact with the existing mechanism instead of a per-log exception inside the adapter. The
version decision stays in the matrix and the adapters (Principle V).

**Alternatives rejected**: `LOG_DIAGNOSIS` (REST-only, unknown versions "warn", wrong label); one
capability `SERVER_LOG_READ` with a per-log refusal in the adapter (`list_nodes` would show 9.0 as
supported while one log is refused, and the opt-in could not shape `list_nodes` as in 020).

## D-7 Download flow and placing the file (FR-007, FR-008, FR-011)

**Decision**:
1. **Before anything is sent**: input checks, target, opt-in, the folder rules
   (`TargetFolderRules.check`, `application/TargetFolderRules.java:124`), the version gate. A
   folder rule failure is `INVALID_INPUT`; nothing is created.
2. **Download into a private temporary directory**: `inubit-mcp-log-<profile>-<pid>-<random>`
   below `java.io.tmpdir` (`CliResources.reserve()` → `PrivateFileSystem.createTempDirectory`,
   owner-only; new prefix `CliResources.LOG_PREFIX`, included in the stale sweep). Trace: StartCLI
   writes `<dir>/trace.log`; webservice: the helper writes `<dir>/out/webservice.log`. The adapter
   returns an `AutoCloseable` `DownloadedLog(path, size, inubitNode)`; closing it deletes the
   directory (also on failure, timeout, interruption and shutdown, as `CliExportRunner`).
3. **Excerpt** (D-9 … D-13) from the private copy, so no IO error after placing can leave a file
   behind.
4. **Place** with the new `application/LogFileWriter`:
   - the folder and its missing parents are created now (owner-only, existing permissions never
     changed; the folder-creation code of `ArchiveFolderWriter`, `ArchiveFolderWriter.java:300`,
     is extracted into a shared package-private `OwnerOnlyFolders` used by both writers);
   - the private copy is streamed into `.<base>.<random>.part` in the target folder
     (`CREATE_NEW`, `rw-------` on POSIX), `force(true)`;
   - the name is claimed with `Files.createLink(<folder>/<base>.log, part)`; on
     `FileAlreadyExistsException` the next name `<base>-2.log`, `-3`, … up to `-999` (then
     `INVALID_INPUT` "no free file name"); the kernel refuses an existing name atomically, so
     concurrent calls never share a name and no file is replaced; then the `.part` name is deleted;
   - **without hard links** (`UnsupportedOperationException`, or a `FileSystemException` other than
     `FileAlreadyExistsException`, e.g. FAT/exFAT): the name is claimed by creating an empty
     placeholder with `CREATE_NEW` (same suffix loop), then `Files.move(part, placeholder,
     ATOMIC_MOVE, REPLACE_EXISTING)` replaces only that placeholder of this call; if atomic moves
     are unsupported too, placeholder and part are deleted and the call fails with
     `INVALID_INPUT` "the file system of the target folder supports neither hard links nor atomic
     renames; choose a local folder";
   - on any failure every file this call created is deleted; a folder it created stays (FR-011).
5. `<base>` = `<group>-<node>-<log>-<yyyyMMdd-HHmmss>` with the UTC instant at which the download
   finished (`downloadedAt` in the result is the same instant). Group and node ids are
   `[a-z0-9-]` (`domain/model/GroupId.java:9`), so the name needs no encoding.
6. The cloud-sync rule of `TargetFolderRules` gets its wording per caller (`check(folder,
   subject)`): "the log file (with clear-text content) will be synchronized to the cloud" here,
   unchanged for `export_archives`.

**Rationale**: StartCLI and the vendor download open their output file before the transfer and
leave partial files on failure; letting them write only into a private directory keeps the target
folder clean (SC-004) and works for any target path (StartCLI paths must match `CliPaths`). The
hard-link claim is the one already used by `export_archives`; the placeholder fallback avoids
writing content under the final name, which `ArchiveFolderWriter`'s direct `CREATE_NEW` fallback
would do (FR-007 "a half-written file never appears under the final name"; an empty placeholder
of this call is a reservation, not content).

**Alternatives rejected**: StartCLI writing into the target folder (partial files, umask
permissions, path rule of `CliPaths`); `Files.move` from the temp directory (crosses file systems,
not atomic, may replace); reusing `ArchiveFolderWriter.write` as is (holds the content in memory,
writes content under the final name without hard links).

## D-8 Missing versus empty, per channel (spec edge case)

**Decision** (static evidence; confirmed or corrected by the recordings, D-23):

| Channel | Missing file | Empty file |
|---|---|---|
| trace (StartCLI) | server throws `InubitException` with key `FileWasNotFound` → StartCLI prints it as an error result; classifier rule "marker `FileWasNotFound`" → `NOT_FOUND` "trace.log does not exist on <node>" | `OK`, 0 bytes → result with `excerpt.kind: empty` and the hint "The log is empty" |
| webservice (helper) | server throws "File webservice.log not found on <node>" → `part log error` whose message contains `not found on` → `NOT_FOUND` with the spec text (web service logging can be enabled in the server configuration) | `part log ok 0` → empty, hint "The log is empty; web service logging may be disabled" |

The service supports the second branch of the spec as well: an adapter can report presence
`UNKNOWN` for a 0-byte answer; then the hint is "The log is empty or does not exist on the server"
and, for the web service log, "The log is empty or does not exist on the server; web service
logging may be disabled" (US2-4: the result says that web service logging may be disabled).
It is used only if a recording shows that a version answers a missing file like an empty one; the
contract test of that version pins the choice.

**Rationale**: both server paths distinguish the cases in the 8.1.17 and 9.0.10 bytecode; the
client-side text is not known statically.

## D-9 The scanned window (FR-009 step 1)

**Decision** (`application/LogWindowReader`, file IO on the private copy):
- `size ≤ 50 MiB` (52,428,800 bytes): window = whole file, `linesBefore = 0`.
- Otherwise `start = size − 50 MiB`; the window begins at the first position `p ≥ start` with
  `byte[p − 1] == '\n'` (a line that begins inside the last 50 MiB; a partial first line is not
  part of it); `linesBefore` = number of `\n` bytes in `[0, p)`, counted by streaming the prefix
  in 64 KiB blocks (never held in memory). No such `p` (no line break in the last 50 MiB) → no
  excerpt, hint "No whole line in the last 50 MiB of the log".
- Line terminators: `\n`; a `\r` directly before it belongs to the terminator; a lone `\r` is part
  of the line. A final segment without terminator is a line; the empty segment after a final
  `\n` is not.
- The result reports `window.startLine = linesBefore + 1`, `window.lines`, `window.bytes` and
  `window.wholeFile`; `wholeFile: false` adds the hint "Only the last 50 MiB were scanned".

## D-10 Text or binary, charset (FR-009, edge cases)

**Decision**: `EntryContentClassifier.classify(windowBytes)` of 020 unchanged
(`domain/model/EntryContentClassifier.java:44`): signature at the window start, `00` or > 10 %
control bytes in the first 8 KiB → binary (no excerpt, `sizeBytes`, `detected`, hint "Binary
content is not returned"); else UTF-8 if the whole window is valid UTF-8 (BOM dropped), else
ISO-8859-1, named in `excerpt.charset`. The downloaded file is never re-encoded (FR-008).
A log being written while downloaded may end in an incomplete UTF-8 sequence; the window then
decodes as ISO-8859-1 (documented; no special case).

## D-11 Line-preserving masking of the whole window (FR-010)

**Decision**: `SecretTextMasker` (020, `domain/model/SecretTextMasker.java`) gets a second entry
point `maskLines(String window, List<SecretSpan> known)`:
- the same rules 1–7 on ranges of the original text, plus the **known-credential spans** of the
  profile (`ConfiguredSecrets.occurrences`, which `adapter/ProfileSecrets` answers from
  `SecretScrubber.occurrences`, `infra/SecretScrubber.java:89`; replacement `***`) as ranges of
  their own, merged with the others by the existing order (earliest start, longest, lower rule
  number; known credentials as rule 0);
- **replacement keeps lines**: each merged region `[start, end)` becomes its replacement followed
  by every `\r` and `\n` of the region in their original order. So the token stays on the first
  line, every line the region covers completely becomes empty, the unmasked text of a partly
  covered line stays, and line N of the masked window is line N of the window (US1-8);
- **end tag without start tag** (outer pass only, not inside CDATA): an end tag
  `</(prefix:)?name>` of a secret local name with no open start tag of that name before it in the
  window adds the range `[0, start of that end tag)` with the element's placeholder (US1-9; the
  end tag itself stays, the lines up to it are masked as above);
- a secret JSON value opened before the window is not detected (documented limit, FR-010).

`mask(String)` (020) keeps its exact behaviour; both entry points share the range collection.

**Known credentials of the whole profile** (FR-010 "the known credentials of the profile"):
`ServerLogService` gets the feature-019 port `domain/port/ConfiguredSecrets` (the same
`ProfileSecrets` instance `KnowledgeService` uses, with `ProfileSecrets.timeout()` as
`prepareBound`). After the download and before the excerpt it calls `prepare(min(prepareBound,
remaining call deadline))`, which starts or joins the credential reads of **every** node, so a
password of a node not yet contacted is known before masking; `LogExcerpt.build` then gets
`ConfiguredSecrets::occurrences` as its pure `Function<String, List<SecretSpan>>`.
`SecretScrubber.global()::occurrences` alone would miss the credentials of nodes whose reads have
not run yet. If `prepare` answers `NotReady`, the call fails closed for the excerpt only, as 019
option A does for notes: the file is written, the result carries `"excerpt": {"kind": "withheld"}`
and the hint "The excerpt is withheld: the credentials of <node> (<store>: <state>) have not been
read yet, so they could not be masked; every excerpt is withheld until this source can be read;
the file is written" (names and states only, never a value; FR-010, US1-10: the call counts as
successful, audit `EXECUTED`; no `ExcerptPermit` is taken). This applies only to credentials
read for masking; the addressed server's own login credentials are prepared before the download
and fail the call (D-15). The port lives in `domain/port`, so `PackageBoundaryTest` (no `application` → `infra`)
stays green; `ResultMapper` scrubs the result again as for every tool.

**Rationale**: masking before selection (spec FR-009 order) is the only way a value spanning lines
or the window start cannot leak through a selected line; keeping the terminators keeps the
absolute line numbers exact without a second mapping.

## D-12 Selection, line numbers, `contains`, `maxChars` (FR-009 steps 3–5)

**Decision** (`domain/model/LogExcerpt.build`, pure):
- Lines of the masked window, numbered `linesBefore + i + 1`.
- Without `contains`: the last `tailLines` lines. With `contains`: a line matches if it contains
  the text case-insensitively as plain text (`String.regionMatches(true, …)` over the line; no
  pattern); `matchingLines` = all matches in the window; the last `tailLines` of them are selected.
  No match → empty `lines`, hint "No line of the scanned window contains the search text".
- `maxChars` counts the UTF-16 chars of the selected line texts (numbers and JSON overhead not
  counted). Over the limit: whole lines are dropped from the oldest until the rest fits
  (`truncated.droppedLines`); if the newest line alone is longer than `maxChars` it is cut to
  `maxChars` chars without splitting a surrogate pair (`truncated.cutLines = 1`, its `cut: true`).
- `contains` is validated in the service (1–200 chars, no `\p{Cc}`) as well as in the schema.

## D-13 Memory and time bounds of the excerpt

**Decision**: one excerpt is built at a time per MCP server process (`application/ExcerptPermit`,
a fair semaphore with one permit). The wait for the permit is bounded by what is left of the
call's `cliExportTimeout` deadline (D-15, FR-013): if the permit is not free in time the call
ends with `TIMEOUT` "The <log> log download from <node> did not finish within cliExportTimeout
(<n> s)", the next step naming `cliExportTimeout`; the private directory is deleted, nothing is
written to the target folder, the audit record is `FAILED`.
Peak memory of one excerpt ≈ 50 MiB window bytes + decoded window + masked window (Latin-1 text 1
byte/char, other text 2 bytes/char) ≈ 250 MiB worst case; the window bytes are released before
masking. A 50 MiB synthetic log must build its excerpt within 20 s on the CI machine
(`LogExcerptPerformanceTest`, generous bound; the masker is linear). `docs/tools.md` states the
memory need. The line count of a large prefix streams and needs no memory.

**Rationale**: two concurrent calls on different nodes must not double the peak of a 50 MiB scan;
the StartCLI slots bound calls per node only.

## D-14 Error mapping (FR-011)

**Decision**:

| Observation | Code |
|---|---|
| schema/service input check, folder rule, no free name, file system without links or atomic rename | `INVALID_INPUT` |
| unknown target | `TARGET_UNKNOWN` |
| webservice without opt-in (before detection, nothing sent) | `NOT_CONFIGURED` |
| version gate (6.1, other line, undeclared, "not yet verified") | `UNSUPPORTED_VERSION` |
| detection failure (`admitWithDetection`) | the detection's own code |
| no client home, no `ibis.jar`/JDK, launch failure, temp root not passable, vendor API missing | `CLI_UNAVAILABLE` |
| trace: classifier as for every StartCLI run (`LoginFailure` → `AUTH_FAILED`, TLS, network, `AccessDenied` → `FORBIDDEN`; maintenance comes from the detection before launch, StartCLI has no mapped maintenance text); new rule `FileWasNotFound` → `NOT_FOUND`; success without the `Trace data (` message or without the file → `UNEXPECTED_RESPONSE` | as named |
| webservice: `HelperFailures` (020 D-5) for launch, timeout, login, TLS, network, 403, API missing, protocol problems; `node error` with an access-denied text or HTTP 403 → `FORBIDDEN` "… needs the right the Workbench's Cluster Manager needs (`Configuration`)"; `part log error` with `not found on` → `NOT_FOUND`; `Server not in cluster mode.` or any other `node`/`part` error → `UNEXPECTED_RESPONSE` "the unsupported INUBIT interface may have changed" (scrubbed excerpt ≤ 200 chars) | as named |
| `cliExportTimeout` exceeded (slot wait, TLS check, process, excerpt permit wait) | `TIMEOUT` naming `cliExportTimeout` (no file, process tree killed) |
| known credentials of some node not read within the bound | no error: file written, excerpt `withheld` with a hint (D-11) |
| credentials not resolvable | the node's usual credential code (`SECRET_UNRESOLVED`, `AUTH_FAILED`, …) |
| audit `PENDING` record not writable; helper resource missing | `INTERNAL` |

Every message passes `SecretScrubber` (`ResultMapper`) and the `ToolError` excerpt bound. Markers
without a recording (trace missing file, 403 texts) are fixtures marked `reconstructed`.

## D-15 Timeout and concurrency (FR-013)

**Decision**: one deadline per call, set by `ServerLogService` at the start of the download
phase (after the version gate) = now + node `cliExportTimeout`, passed to
`ServerLogPort.download(kind, version, deadlineNanos)`. It bounds every wait of the call:
- in the service, before the download: the server's **login credentials** (FR-013). Without this
  step the first wait would be `GatewayFactory.serverLogs(node)` → `AdapterGatewayFactory`
  `Slot.gateway()` → `awaitCredentials()` (`adapter/AdapterGatewayFactory.java:343`), which waits
  `secrets.timeout()` of the credential source (up to 60 s), not the call deadline. So right after
  setting `deadlineNanos` the service calls `GatewayFactory.prepareCredentials(node, remaining)`
  (`domain/port/GatewayFactory.java:189`, as `HealthService` does for `get_health`, 010 R-9):
  `READY` → `serverLogs(node)` finds the credentials held and `awaitCredentials()` returns at once;
  `PENDING` (the fetch outlived the bound and continues for the next call) → `TIMEOUT` naming
  `cliExportTimeout`, the port is not called, audit `FAILED`; a failed fetch is thrown by
  `prepareCredentials` and passes through with its own classified code (the node's usual
  credential code, D-14; `NodeSecrets` classifies today as `AUTH_FAILED` or `UNREACHABLE`), the
  port is not called, audit `FAILED`;
- in the adapter: the slot wait, the TLS check (helper only, `min(server.timeout(), remaining)`)
  and the process (`remaining`). Both channels take a slot of the per-node fair semaphore
  (`CliConcurrencyLimiter.acquireForHelper`, `adapter/cli/CliConcurrencyLimiter.java:60`, 2
  permits, documented as "helper and log downloads"); other StartCLI runs on 8.1/9.0 stay without
  a slot (existing tools unchanged). The slot-wait `TIMEOUT` of `CliConcurrencyLimiter.take`
  today says "raise cliTimeout"; a new overload `acquireForHelper(server, wait, budgetSetting)`
  names the setting the caller's budget comes from (`cliExportTimeout` here; the existing callers
  keep `cliTimeout` and their texts byte-identical);
- in the service, after the download: the credential wait for masking (`ConfiguredSecrets.prepare`,
  bounded by `min(prepareBound, remaining)`, D-11) and, only if it answered `Ready`, the
  `ExcerptPermit` wait (`remaining`, D-13); `NotReady` takes no permit (excerpt withheld).

Every `TIMEOUT` names `cliExportTimeout` in its next step and adds "large logs need a larger
cliExportTimeout"; process tree killed, private directory deleted, nothing in the target folder.

Once the permit is held, building the excerpt and placing the file run to their end and are not
cut off by the deadline: both are local, bounded work (≤ 20 s for a 50 MiB window on CI, D-13;
placement = one local copy of ≤ the downloaded size plus a link), and interrupting a placement
between the claim and the clean-up would risk a leftover file (SC-004). So a call ends at most this
bounded local work after its deadline; every wait on INUBIT, on another call or on a credential
store is inside the deadline (FR-013).

**Note**: `cliExportTimeout` (built-in 120 s, the budget of the StartCLI exports) is used instead of
`cliTimeout` (built-in 30 s), because a log download is a transfer like an export; decided after the
plan phase and changed in spec FR-013.


**Version check**: `VersionGate.admitWithDetection(…, {CLI})` runs before the call deadline
starts. It has two bounded parts, both as for every StartCLI-based tool:
- the version detection of an `AUTO` node (none for a pinned `versionLine`), bounded by the
  node's REST `timeout`, its result cached (spec FR-013);
- because the call's channels contain `CLI`, the 017 client check of `VersionGate.judge`
  (`application/VersionGate.java:346-358`): if the StartCLI client chosen for the version is
  available and `NodeVersions.needsClientProbe` says no result of that client is known and no
  failed probe of it lies within the last 60 s, `probeClient` runs the client probe bounded by the
  node's `cliTimeout` (`VersionGate.java:444-446`, `cliTimeouts.apply(node)`); its result is
  recorded and reused by later calls (a failed probe gives a warning and is not repeated for
  60 s). So the probe runs at most once per client and back-off window, not on every call.

Neither part is inside the `cliExportTimeout` deadline; the probe adds at most `cliTimeout`
before the deadline starts, and only on a call that finds no usable probe result. The spec
needs no change: FR-013 bounds the version check "as for every tool", which includes the 017
probe. The remaining time passed to `GatewayFactory.prepareCredentials`,
`ConfiguredSecrets.prepare` and `ExcerptPermit.tryAcquire` is clamped to `Duration.ZERO`.

## D-16 TLS and credentials (FR-012)

**Decision**: trace uses StartCLI's existing TLS handling unchanged (trust store arguments, the
pin probe of feature 014 before the run, 017 `JAVA_TOOL_OPTIONS` handling where merged) and the
password on stdin. Webservice uses 020 D-7 unchanged: the server verifies the certificate itself
(`TlsPeer.handshake` with trust store, pin, hostname setting) and gives the helper a one-time
PKCS12 store with only the verified leaf; credentials only on stdin; `http` without store.

## D-17 Opt-in setting, announcement, summary (FR-002, FR-003, FR-016)

**Decision** (020 D-15 mirrored):
- `UnsupportedConfig(Optional<Boolean> queueEntryRead, Optional<Boolean> serverLogRead)`;
  `EffectiveNodeConfig` `+ boolean serverLogRead` (group only, default
  `Defaults.BUILTIN_SERVER_LOG_READ = false`, passed through every copy method).
- `ConfigLoader.requireStrictUnsupportedOptIn` checks both keys (same message form, value never
  echoed); other keys under `unsupported`, the key on a node or in `defaults` stay unknown keys.
- `ConfigValidator.checkServerLogReadOptIn`: warning on a `production: true` group
  "unsupported.serverLogRead is enabled on production {group} %s: the web service log is read
  through an undocumented INUBIT interface without vendor support".
- `ConfigSummary`: only if some group has `serverLogRead: true`, every group line ends with
  `, serverLogRead: off` / `, serverLogRead: enabled (unsupported)` (after the `queueEntryRead`
  part if that one is shown); `cliOnly` lists `WEBSERVICE_LOG_READ` only for opted-in groups and
  lists `TRACE_LOG_READ` like every other capability.
- Announcement (`Wiring.toolHandlers`): `download_server_log` iff some node is
  `possiblyCapable(node, TRACE_LOG_READ)` and `clientSelection.anyCandidate(node)`, or has
  `serverLogRead`, is `possiblyCapable(node, WEBSERVICE_LOG_READ)` and has a client candidate
  (FR-002). Offered in read-only mode; not subject to `write.*` or production write opt-ins.

**Consequence for SC-002**: `list_nodes` shows `trace_log_read` for every node with a client
(additive, as `partner_management` was added by 018), and the 6.1 `cliOnly` summary lists
"downloading the trace log" as refused. Golden texts that list capabilities change additively;
without `serverLogRead` nothing about the web service log appears. Owner question in plan.md.

## D-18 Audit and logging (FR-015)

**Decision**: `application/ServerLogAudit`, built like `ArchiveExportAudit`
(`application/ArchiveExportAudit.java`): capability `download_server_log`, step `EXECUTE`, one
`auditId` per call. A refusal after the schema and before the `PENDING` record (input, target,
opt-in, folder rule) is one `REFUSED` record; a refusal by the version gate, which runs after
`PENDING` (contract decision flow), closes the same `auditId` with `REFUSED`; otherwise a `PENDING` record precedes the download ("no audit record, no
download": an unwritable `PENDING` → `INTERNAL`, nothing sent), and the final record is
`EXECUTED` or `FAILED` (with the error code). Inputs in fixed order: `log`, `targetFolder`,
`tailLines`, `maxChars`, `containsGiven` (`true`/`false`; the text is never audited); details:
`file`, `sizeBytes`, `inubitNode` (webservice). A final record that cannot be written becomes a
result warning (as `export_archives`).
Logging: one `INFO` line per call `download_server_log on {node}: log {log}, file {name|-},
{n|-} bytes, window {lines|-}, outcome {OK|<code>}`; adapter `DEBUG` for variant, client home,
JDK. Never the log content, excerpt lines, `contains`, helper or StartCLI output; a test captures
the logback output of a full call with secret-looking content (SC-003).

## D-19 Domain, ports and packages

**Decision**:
- `domain/model`: `ServerLogKind` (`TRACE("trace")`, `WEBSERVICE("webservice")`),
  `LogPresence` (`PRESENT`, `UNKNOWN`), `LogExcerpt` (+ `ExcerptLine`, `Truncation`),
  `ServerLogBounds`, `ServerLogResult`; `Capability`/`CapabilityMatrix` changes (D-6);
  `SecretTextMasker.maskLines` (D-11).
- `domain/port/ServerLogPort`: `DownloadedLog download(ServerLogKind kind, InubitVersion version,
  long deadlineNanos)` (the call deadline of D-15 as a `System.nanoTime()` value, as the 020
  adapters keep theirs) where `DownloadedLog` (record `Path file`, `long size`, `Optional<String> inubitNode`,
  `LogPresence presence`, `AutoCloseable`). `Gateway.serverLogs()` / `GatewayFactory.serverLogs(NodeId)`
  default methods throwing `CLI_UNAVAILABLE` (020 D-17 pattern); `PortSet` gets the component with
  a compatibility constructor; `V81PortSet`, `V81Gateway`, `AdapterGatewayFactory`,
  `StaleReportingPorts` (forward), `RefusingPorts` override.
- Adapter: `adapter/cli/CliTraceRunner` (trace) and `adapter/cli/log/CliWebServiceLogAdapter`
  (helper), combined by `adapter/cli/log/CliServerLogAdapter implements ServerLogPort`
  (dispatch by kind; the slot lives here, the deadline comes from the service).
- `ServerLogResult.excerpt` is `Optional<LogExcerpt>`: empty means withheld (D-11) and is written
  as `"excerpt": {"kind": "withheld"}`; the 020 `ContentKind` stays unchanged.
- Application: `ServerLogService` (validation, target, opt-in, folder rules, audit, gate, call
  deadline, port, known credentials via `ConfiguredSecrets`, excerpt, placement, result, log line), `LogWindowReader`, `LogFileWriter`, `OwnerOnlyFolders`,
  `ExcerptPermit`, `ServerLogAudit`.
- Tool: `mcp/tools/DownloadServerLogTool`; schemas `download_server_log.{input,output}.json`.

## D-20 Tool annotations and description (FR-004, FR-014)

**Decision**: `ToolHints.localWrite("Download an INUBIT server log")` (`readOnlyHint: false`,
`destructiveHint: false`, `idempotentHint: false`, `openWorldHint: true`), as `export_archives`.
Description text in contracts/mcp-tools-delta.md; `ServerLogDocsTest` asserts its key phrases and
the FR-004 sentences in `docs/setup.md` and `docs/tools.md`.

## D-21 Fixtures: fictitious log content, neutralized recordings (FR-017, FR-018)

**Decision**: new fixture root `src/test/resources/fixtures/server-log/{v8_1,v9_0}/` with a
`README.md` and one `<case>.origin` per case (`recorded <date> <version> neutralized` or
`reconstructed <source>`), checked by `ServerLogFixtureCatalogueTest`.
- Trace cases: `<case>.stdout`, `<case>.stderr`, `<case>.exit` (StartCLI output; replayed by
  `FakeProcessLauncher`/`ScriptedProcessLauncher`), `<case>.log` (the file StartCLI "writes").
  Cases: `ok` (recorded), `empty` (recorded if the test server allows, else reconstructed),
  `missing` (reconstructed: `FileWasNotFound`), `login_failure`, `forbidden` (reconstructed).
- Webservice cases: `<case>.frames`, `<case>.exit`, `<case>.log`. Cases: `ok` (recorded),
  `missing` (recorded where web service logging is off, else reconstructed from
  `File webservice.log not found on node1`), `forbidden`, `not_cluster_mode`, `api_missing`,
  `garbage_frames` (reconstructed).
- **Log content is always fictitious**: the recorded `.log` is replaced by a fictitious log of the
  same shape before it enters the repository (timestamps, levels, `acme` classes, `node1`,
  `*.example.test`), and the recorded size/MB values in `.stdout` and frames are adjusted.
  Recorded stdout/frames are neutralized as in 020 D-18 (`tools/neutralize.py`, the private map,
  `NoCustomerIdentifiersTest`, `tools/check-identifiers.py`, owner review of the diff).
- Synthetic excerpt fixtures (each with `.README`) under `fixtures/server-log/excerpt/`:
  `secrets_multiline` (US1-8), `secrets_window_start` (US1-9), `secrets_everywhere` (every rule),
  `latin1`, `binary_gzip`, `long_lines`, `no_newline`, `crlf`; the 60 MiB case of US1-7 is
  generated in the test, not stored.

## D-22 Contract tests per version (Constitution V, FR-017)

**Decision**: `ServerLogV81ContractTest` and `ServerLogV90ContractTest` run the full
`CliServerLogAdapter` per log with the recorded cases replayed by `FakeProcessLauncher.onLaunch`
(StartCLI: write `<case>.log` to the `--download` path, print `.stdout`; helper: read the request
from stdin, copy `<case>.log` to `logOut`, replay `.frames`/`.exit`), then the service builds the
result. The helper's `serverLog` operation is tested in-process against fake vendor classes in
the test tree (`com/inubit/ibis/server/cluster/ClusterClient`, `…/data/Node`,
`…/cache/NodeName`, `com/inubit/ibis/common/CurrentUser`, plus the 020 fakes), recording every
vendor call (FR-006: login, `getNode`, `clusterDownload`, logout only; file name always
`webservice.log`), and once in a real child JVM. `ServerLogFixtureCatalogueTest` asserts:
matrix `SUPPORTED` for (version, log) ⇔ a `recorded` `ok.origin` of that version and log.

## D-23 Live spike and recordings (early tasks; operator approval; never production)

**Decision**: two early tasks, before the adapters are built, each run only after the operator
approved the concrete action in the chat, on non-production 8.1 and 9.0 test servers:
1. **Trace recording** (supported, read-only): the operator (or the agent after approval) runs
   StartCLI `trace -d <private dir>/trace.log` once per version with the configured client;
   stdout, stderr and exit code are kept in a private directory outside the repository; the trace
   state shown in the Workbench (Administration → trace settings) is compared before and after
   (SC-005).
2. **Webservice spike** (unsupported, read-only): a throw-away program outside the repository
   (like the 020 spike) performs exactly D-2 steps 1–5 per version and records: whether
   `CurrentUser.getClientUID()` works in a plain JVM, `getNode()` over the 9.0 REST client, the
   node name, the client-side exception chain and text for a missing file (only if the test
   server has no `webservice.log`; no other file name is ever sent), the 0-byte case if it
   occurs, and whether a single-node server answers `Server not in cluster mode.`.
The findings go into this file ("Spike findings", appended) and into the fixtures (D-21). If a
9.0 step cannot be recorded, that log stays refused on 9.0 "not yet verified" (FR-017); if an 8.1
step cannot be recorded, the feature does not ship that log.

## D-24 Live check (docs/live-tests.md)

**Decision**: `live/ServerLogLiveTest` (`@Tag("live")`, `-Plive`, production refused by
`LiveTarget`): `INUBIT_LIVE_NODE`, `INUBIT_LIVE_LOG_FOLDER=<private folder outside the repository>`;
calls `download_server_log` for `trace` and (if the group opts in) `webservice` through the wired
server; asserts the file exists with the reported size and `rw-------`, the result shape, no file
left in the folder after a forced failure (an unreachable port override), and that the captured
server log contains no excerpt line. Optional `INUBIT_LIVE_RECORD_DIR` keeps raw StartCLI output
and helper frames for D-21. Prints one `[live] download_server_log <node> <log>: <n> bytes,
window <lines>` line. `docs/live-tests.md` gets the section "Server log download (feature 021)".

## D-25 Documentation

**Decision**: `docs/setup.md` `### Unsupported functions` gets the `serverLogRead` row, example and
FR-004 text (contracts/configuration-delta.md); `docs/tools.md` new section
`## download_server_log` (inputs, result, excerpt rules, masking limits, folder rules by
reference to `export_archives`, errors, memory need, "address cluster nodes directly", "the
server reads trace.log fully into its memory"), the capability table rows, the announcement
rule; `README.md` tool list; `CHANGELOG.md` `[Unreleased]`; `SECURITY.md` gets the log file
among the clear-text outputs.

## Spike findings

### 8.1 trace recording (D-23 step 1, T021, 2026-11-11 UTC)

Setting: one non-production INUBIT 8.1 test server of the profile, operator-approved; the
profile's 8.1 StartCLI client on Java 17; run once through the project's `CliRunner.run` (argument
array, password on stdin, the profile's trust store, hostname verification off as the profile
sets it) with the token list D-1 specifies for `traceDownload` (built by reflection in the throwaway program; the factory did not exist yet): `trace --download
'<private dir>/trace.log'`. No other `trace` form was run. Raw output and the file stay owner-only
in a private directory outside the repository.

- **Exit code** `0`; wall-clock about 3 s including the JVM start.
- **stdout**, 4 lines, in this order: `JAVA_HOME is set`, the `Password: ` prompt, the
  `SECURITY WARNING: Hostname verification is disabled. …` line (only because the profile disables
  it), then `Trace data (<n> MB) stored to: <path>`. `<path>` is the requested absolute path,
  echoed verbatim without quotes. The line matches D-1's
  `^Trace data \(\S+ MB\) stored to: .+$`.
- **`<n>`** is an integer: the file size in MiB (2^20 bytes), rounded (`71` for a file of about
  74.4 million bytes, which would be `74` in 10^6-byte MB).
- **stderr**, 1 line: the JVM's `Picked up JAVA_TOOL_OPTIONS: …` line (the runner's own language
  and trust-store options); nothing from StartCLI.
- **File**: a regular file at the requested path, plain ASCII text, size in the order of 10^7 bytes
  (about 71 MiB). StartCLI creates it with the process umask (`rw-r--r--` here), not owner-only:
  only the private directory protects it until it is moved or rewritten.
- **Empty file**: not observed (the server's trace log was not empty).
- **Trace state (SC-005)**: get_health systemInfo replaced the Workbench view of D-23
  (Administration → trace settings). Before the run (23:35 UTC): tracing enabled, level `info`;
  after the run (23:39 UTC): `tracingEnabled=true`, `TracingLevel=info` — unchanged (SC-005).
  All times 2026-11-11 UTC.
