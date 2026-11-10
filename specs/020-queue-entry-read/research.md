# Research: Read Queue Manager Entries (unsupported, opt-in)

**Feature**: 020-queue-entry-read | **Date**: 2026-11-10 | **Spec**: [spec.md](spec.md)

Base: `main` at 0.8.0 (features 009, 012, 013, 016, 017 merged). Sources: the spike of
2026-11-10 (two throw-away programs, one per version, run read-only against an 8.1.17 and a 9.0.10
test server; not part of the repository), `javap` of the 8.1.17 and 9.0.10 client installations,
and the code of this branch. Paths below are relative to `src/main/java/de/dadecker/inubit/mcp/`
unless they start with `src/`, `docs/`, `tools/` or `pom.xml`.

### Spike facts this research builds on

| | 8.1.17 | 9.0.10 |
|---|---|---|
| JDK of the client | 17 | 21 |
| Client object | `new com.inubit.ibis.soap.IBISSoapClient(url + "?isCli", trustStoreFile, trustStorePassword, keyStoreFile, keyStorePassword, enableHostnameVerification)` (the parameter names come from the class's `LocalVariableTable`) | `ConnectionData` (`setServerURL(baseUrl)`, `setUserName`, `setPassword`, `setServerAuth(true)`, `setServerTrustStoreFile(File)`, `setServerTrustStorePassword`, `setSkipHostnameVerification`) → `new com.inubit.ibis.client.rest.InubitClientFactory().getRestInstance(cd)` |
| Login | `loginUserCli(user, password)`; `logoutUser(user)` at the end | `loginUserCli(user, password)`; no `logoutUser` in the interface; non-daemon threads, so the JVM must `System.exit` |
| Read | `new com.inubit.ibis.configuration.utils.io.MultipartDownloadThread(client, user, fileName, "queue_manager@@@" + workflow + "@@@error", null, null)`, `setOutputFile(file)`, `start()`, `join()`, `exceptionOccured()`/`getException()`; variables with `fileName + ".wfsp"` | same; transport `POST /ibis/inubit/file/downloadFile` (internal) |
| Result | data stream size = queue row `fileSize`; variables: `<Properties version="4.1">` with one `<Property name=".." [type=".."]>value</Property>` per variable (types seen: none = text, `Boolean`, `Integer`, `Long`, `XmlDocument` with escaped XML as text); entry unchanged afterwards | no ERROR entry existed; a missing file answered `@Start@Unable to download@@@…@End@<owner>/workflow/<workflow>/error/<file>`, a missing workflow `…@Start@MissingWorkflowWithName@@@Missing workflow: X@@@X@End@` |
| Trust store | file path, `FileNotFoundException` is **silently ignored** ("Ignore error during SSL initialization", bytecode of the constructor) | a trust store **file** is required; the spike built a temporary PKCS12 from the server certificate after checking its SHA-256 against the configured pin |
| Server side (`javap`, both) | `QueueManagerDownloadHandler` → `ExecutionDataFacade.getErrorInputStream` (error); variables serialised in memory by `IBISPropertyHandler.save`; no write, move or delete call | same handler chain behind `InubitFileController` → `DownloadFileService` |

Running the spike as a single-file source program failed (a vendor `FileSystemProvider` on the
class path crashed `javac`), so the helper must be precompiled.

---

## D-1 A helper process on the client's class path, not code in the server JVM

**Decision**: a small helper class (`QueueEntryHelper`) runs in its own JVM with the class path
`<helper dir>` + `<clientHome>/lib/*` + `<clientHome>/lib/shared/*` (the class path of
`bin/startcli.sh` of both installations) and the JDK of the client chosen for the node's version.
It touches the vendor classes **only by reflection** (`Class.forName`, `getConstructor`,
`getMethod`), so it compiles without any vendor jar.

**Rationale**: the vendor jars are proprietary, not in Maven and differ per version; loading them
into the server JVM (Java 21, shaded JAR, logback) would mix their log4j/netty stacks and static
state with ours and could not be unloaded; the 8.1 client needs JDK 17 behaviour. A separate JVM is
what StartCLI does today (Principle V: CLI access through the CLI adapter, argument arrays,
timeouts, exit codes).

**Alternatives rejected**: a `URLClassLoader` inside the server (shared JVM state, no per-call
kill, JDK mismatch); re-implementing the SOAP / internal REST wire protocol (undocumented,
proprietary multipart format, two implementations to keep in sync with the vendor); driving the
Workbench UI (absurd); StartCLI `process export` (changes server state, excluded by the spec).

## D-2 Helper packaging: Java 17 bytecode, compiled by a second compiler execution, shipped as a resource

**Decision**:
- Source in its own root `src/helper/java/de/dadecker/inubit/mcp/helper/QueueEntryHelper.java`,
  JDK classes only, **one top-level class without nested or anonymous classes** (lambdas are
  fine; they produce no class file).
- `pom.xml`: a second `maven-compiler-plugin` execution `compile-queue-entry-helper` in phase
  `compile` with `<release>17</release>`, `<compileSourceRoots>` = `src/helper/java` and
  `<outputDirectory>${project.build.outputDirectory}/META-INF/inubit-mcp/queue-entry-helper</outputDirectory>`
  (the pattern Maven documents for multi-release JARs). No new plugin, no new dependency.
- The class file therefore travels inside the shaded JAR at
  `META-INF/inubit-mcp/queue-entry-helper/de/dadecker/inubit/mcp/helper/QueueEntryHelper.class`.
  It is **not** loadable as a class of the server (wrong path for its package) and not under
  `META-INF/versions/` (the JAR is `Multi-Release: true`).
- At each call the adapter copies that resource into the call's private temporary directory
  (D-8) as `<tmp>/helper/de/dadecker/inubit/mcp/helper/QueueEntryHelper.class` and puts
  `<tmp>/helper` first on the helper's class path. A missing resource is `INTERNAL` ("the build is
  incomplete").
- Tests: `QueueEntryHelperPackagingTest` asserts that the resource directory holds exactly that
  one class file and that its class-file major version is 61 (Java 17); `PackageBoundaryTest`
  gets a rule that the helper class references only `java/*` types (vendor classes appear only as
  strings).

**Rationale**: 8.1 runs on JDK 17, so the helper's bytecode must be 17; one class keeps the
extraction a fixed single file; a resource avoids a second artifact and keeps "a single executable
artifact" (constitution, technology constraints).

**Alternatives rejected**: a separate Maven module or attached JAR (more build surface, a second
artifact to distribute); compiling the helper at runtime (needs a JDK compiler, failed in the
spike); `--release 17` for the whole project (the server needs Java 21); a copy step with
`maven-resources-plugin` to rename `.class` files (unnecessary with `outputDirectory`).

## D-3 Process launch: reuse the CLI adapter's pieces, extract its private pipeline

**Decision**: no second runner beside `CliRunner`; the generic part of its private pipeline is
extracted into a public class `adapter/cli/ProcessRun` and used by both:

| Need | Reused as is | Changed |
|---|---|---|
| client home and JDK per version | `CliRunner.select(server, version)` → `Selection` (`adapter/cli/CliRunner.java:280-287`), `selection.server().cli().home()/javaHome()/url()`; `EffectiveNodeConfig.cliFor` (`config/EffectiveNodeConfig.java:286-298`) | — |
| launch guard of 017 | `CliRunner.checkLaunch(Selection)` (`CliRunner.java:522-535`) | — |
| JDK fallback to the server's `JAVA_HOME` | `CliRunner.javaHome(server)` (`CliRunner.java:884-890`, package-private) | made public |
| environment of the child | `StartCliEnvironment.posix/windows` (`adapter/cli/StartCliEnvironment.java:60-102`; allow-listed variables, `JAVA_HOME`, `JAVA_TOOL_OPTIONS` with English locale) | — |
| launching | `ProcessLauncher.LaunchSpec(command, env, workingDirectory)` ("a process other than StartCLI", `adapter/cli/ProcessLauncher.java:46`), `SystemProcessLauncher` (no shell, cleared env, tree-aware destroy) | — |
| stdin, bounded stdout/stderr, timeout, tree kill | `CliRunner.launchAndWait/waitFor/writeStdin/terminate/BoundedSink` (`CliRunner.java:917-1145`, private) | **extracted** into `ProcessRun.run(LaunchSpec, byte[] stdin, Duration timeout, String processLabel)` → `CliResult`; `CliRunner` delegates; the texts keep "StartCLI" for StartCLI and say "the queue-entry helper" for the helper (`processLabel`); existing `CliRunnerTest` cases are the regression net |
| shutdown kills running children and deletes temp dirs | `CliResources.reserve()` / `Reservation.process` (`adapter/cli/CliResources.java:164,189`) | `Reservation.process` made public |
| Windows containment | `ProcessContainment.state()` (`adapter/cli/ProcessContainment.java:17-31`) | — |
| login back-off shared with REST | `CredentialGuard.acquire()` → `Permit.accepted()/rejected()` (`adapter/CredentialGuard.java:130,255-291`) | — |

Not reused: `CliRunner.launchTarget` (requires `bin/startcli.*`), `CliOutputClassifier` /
`CliOutput` (StartCLI markers and `n-OK`), `WindowsCommandLine` and `refuseWindowsCredentials`
(only needed because `startcli.bat` runs through `cmd.exe`; the helper starts `java(.exe)`
directly), `ScriptedProcessLauncher` (matches `--execCommand`).

Launch command (argument array, no shell):

```
<javaHome>/bin/java[.exe] -Xmx512m -Djdk.tls.client.protocols=TLSv1.2,TLSv1.3
  -Dsecretkey.path=<clientHome>/conf -Djava.io.tmpdir=<tmp>/vendor
  --add-opens=… (the fixed list of bin/startcli.sh of 8.1.17 / 9.0.10)
  -cp <tmp>/helper<sep><clientHome>/lib/*<sep><clientHome>/lib/shared/*
  de.dadecker.inubit.mcp.helper.QueueEntryHelper
```

Working directory: the client home (as StartCLI). The java launcher expands `lib/*` itself.
Preconditions, each `CLI_UNAVAILABLE` naming the node, the version and the setting to fix:
home configured, `<home>/lib/ibis.jar` readable, `<javaHome>/bin/java[.exe]` executable, no class
path separator (`:` POSIX, `;` Windows) in the home or the temporary directory path, containment
bound. Windows works by construction (direct `java.exe`); it is covered by argument-array unit
tests, the live check is optional.

**Rationale**: one place for process handling keeps the timeout, kill and output-bound semantics
identical (Principle V: explicit timeouts, exit-code evaluation); the extraction is behaviour
preserving and covered by the existing suite.

**Alternatives rejected**: copying ~120 lines of `CliRunner` (two diverging copies of the kill
logic); routing the helper through `CliRunner.execute` (always prepends the StartCLI script).

## D-4 Helper protocol: request on stdin, framed results on stdout, content in files

**Decision**:

- **argv** carries nothing but the class name (D-3). No URL, user, workflow or file name is
  visible in the process list.
- **stdin**: one request, UTF-8, one `key=value` per line, every value percent-encoded (RFC 3986
  unreserved characters kept), ended by `end`; the server writes it in one buffer, flushes,
  closes stdin and zero-fills the buffer (as `CliRunner.writeStdin` does with the password):

  ```
  qe=1
  nonce=<32 hex chars, random per call>
  variant=SOAP_81 | REST_90
  url=<cli url of the selection>
  trustStore=<path of the call's trust store, empty for http>
  trustStorePassword=<one-time password of that store>
  hostnameVerification=true | false
  objectType=queue_manager@@@<workflow>@@@error
  fileName=<queue file name>
  parts=data,variables | variables
  dataOut=<tmp>/out/data.bin
  variablesOut=<tmp>/out/variables.wfsp
  user=<INUBIT user>
  password=<INUBIT password>
  end
  ```

- **stdout**: everything the vendor libraries print (log4j, netty) is ignored; only lines that
  start with `#QE1:<nonce> ` are frames, so vendor output cannot forge one. Tokens after the
  prefix are space-separated and percent-encoded:

  | Frame | Meaning |
  |---|---|
  | `start <variant>` | request read, vendor classes found |
  | `login ok` | login succeeded |
  | `login error <exceptionChain> <message>` | login failed (chain = `class1>class2>…`, messages cut at 1,000 chars) |
  | `part <data\|variables> ok <bytes>` | file written |
  | `part <data\|variables> error <exceptionChain> <message>` | the download of that part failed |
  | `api missing <what>` | a vendor class or method was not found (reflection) |
  | `end` | the helper finished normally |

- **Content** never goes through stdout: `MultipartDownloadThread.setOutputFile` writes the data
  stream and the variables into `<tmp>/out/` (D-8); the adapter reads them (D-11).
- **Exit codes**: 0 after `end`; 2 malformed request; 3 login failed; 4 vendor API missing; 1
  anything else. The helper always ends with `System.exit` (9.0 leaves non-daemon threads) and
  calls `logoutUser` (8.1, by reflection, if present) in a `finally`.
- The helper never prints the request, the password or file content; it is unit-tested through a
  `static int run(InputStream, PrintStream, ClassLoader)` entry point (`main` only adds
  `System.exit`).

**Rationale**: credentials on stdin, never on a command line (FR-015, Principle II); framing makes
vendor log noise harmless; files keep binary content out of a text channel and bound memory.

**Alternatives rejected**: JSON on stdin/stdout (no JSON library may be assumed on the client's
class path, and the helper must stay JDK-only); content Base64 on stdout (doubles the size through
the 1 MiB output bound); request in argv or environment (visible to other processes).

## D-5 Error mapping (FR-013)

**Decision**: the helper only reports; `QueueEntryHelperClassifier` (Java 21, adapter) maps:

| Observation | Result |
|---|---|
| launch `IOException` | `CLI_UNAVAILABLE` "the queue-entry helper could not be started" |
| no frame within `cliTimeout` (D-14) | `TIMEOUT`, no partial content, process tree killed |
| stdout over the 1 MiB bound, no `end` frame, unknown frame, exit code not matching the frames | `UNEXPECTED_RESPONSE` "… unexpected answer; the unsupported INUBIT interface may have changed after an update" |
| `api missing` | `CLI_UNAVAILABLE` "the INUBIT client installation at <home> (chosen for INUBIT M.m) lacks <what>; check `defaults.cliClients."M.m"`" |
| `login error` with an SSL/certificate class or `PKIX` in the chain | `TLS_ERROR` |
| `login error` with `ConnectException`, `UnknownHostException`, `NoRouteToHostException`, `SocketTimeoutException` | `UNREACHABLE` |
| `login error` with `LoginFailure`, "password does not match" or HTTP 401 | `AUTH_FAILED` (`CredentialGuard` permit rejected; on 9.x the next step names the internal user management as 017 does) |
| HTTP 403 / `AccessDenied` in a login or part error | `FORBIDDEN` |
| `part … error` with `@Start@Unable to download` or `MissingWorkflowWithName` | part **not found** (D-9) |
| `part … error` with a TLS or network class (as above) | the call fails with that code (connection problem, not an entry problem) |
| other `part … error` | that part unavailable, reason "INUBIT reported an error: <scrubbed excerpt ≤ 200 chars>" (FR-012) |
| `login ok` | `CredentialGuard` permit accepted |

`MAINTENANCE_MODE`, `TARGET_UNKNOWN` and the REST failures come from the steps before the helper
(D-6, D-9), unchanged. `SECRET_UNRESOLVED` has no producer in read tools; the tool inherits the
credential errors of the node's other reads (`AUTH_FAILED`, `UNREACHABLE`, `TIMEOUT` from
`adapter/NodeSecrets.java`). Every message and excerpt passes `SecretScrubber` (via
`ResultMapper`) and is cut to the `ToolError` excerpt bound (500). The markers are pinned by
contract tests against the recorded texts (D-19); markers without a recording (8.1 missing file,
403) are fixtures with `.origin reconstructed`.

## D-6 Version → implementation, capability and client installation (FR-006, US3)

**Decision**:
- New capability `Capability.QUEUE_ENTRY_READ` (kind `READ`, label "reading Queue Manager
  entries", `list_nodes` name `queue_entry_read`).
- `CapabilityMatrix.production()`: 8.1 `SUPPORTED`; 9.0 `SUPPORTED` once the 9.0 recording exists,
  otherwise `UNSUPPORTED` with the entry note "not yet verified" (FR-019 fallback, D-20); 6.1
  unlisted (= `UNSUPPORTED`); the **unknown-version map** gets `QUEUE_ENTRY_READ → UNSUPPORTED`
  instead of the general `UNVERIFIED` for reads. So a detected but unsupported version (6.1, 9.1,
  7.x, any undeclared one) is refused by `VersionGate` with `UNSUPPORTED_VERSION`, naming the
  detected version and the supported lines; nothing is sent.
- **Version not detectable (US3-4, owner decision 2026-11-10: follow the spec literally)**: the
  call fails with the **detection error's own code and message** (`UNREACHABLE`, `TIMEOUT`,
  `TLS_ERROR`, `AUTH_FAILED`, `UNEXPECTED_RESPONSE`, …), not `UNSUPPORTED_VERSION`; no content
  request. `VersionGate` gets a variant `admitWithDetection(tool, node, capability, purpose,
  channels)` used only by this tool:
  - a detection run in this call that fails: the failure is reported to `NodeVersions` as today
    (back-off starts) and then **rethrown** instead of being judged as an unknown version
    (today only `TLS_ERROR` is rethrown, `application/VersionGate.java:366-374`);
  - no detection in this call because of the 60 s back-off, no known version, and a recorded
    `DetectionFailure` (`domain/model/NodeVersionState.java:60`, code only): the call fails with
    the recorded code, message "The INUBIT version of test/node1 could not be detected
    (<CODE> at <time>); read_queue_entry needs it", next step "Fix the cause and retry in a
    minute, or set versionLine of test/node1"; the back-off is respected (no extra login attempt,
    `CredentialGuard` lockout protection);
  - every other caller keeps today's behaviour (unknown version → warning or refusal).
- The `Entry` of `CapabilityMatrix` gets an optional per-capability note used in the refusal text
  (only the fallback uses it).
- The service calls `gate.admitWithDetection("read_queue_entry", node, QUEUE_ENTRY_READ, READ,
  Set.of(REST, CLI))` and passes the admitted version (`Admission.version`) to the port.
- Implementation choice inside the 8.1 port set (which also serves 9.0,
  `adapter/PortSetRegistry.java:106-108`): `QueueEntryVariant.forVersion(MajorMinor)` — 8.1 →
  `SOAP_81`, 9.0 → `REST_90`, anything else → `UNSUPPORTED_VERSION` (second line of defence);
  6.1 nodes get `RefusingPorts` (`adapter/RefusingPorts.java:44-51`).
- Client installation: `CliRunner.select(server, admittedVersion)` (the 017 choice: node/group
  `cli.home` → `defaults.cliClients."M.m"` → `defaults.cliHome`, JDK likewise), then
  `checkLaunch`. No client home → `CLI_UNAVAILABLE` with the 017 text naming
  `defaults.cliClients."M.m"`.
- `list_nodes`: `QUEUE_ENTRY_READ` is in a node's allowed set (`Wiring.allowed`,
  `Wiring.java:707-726`) only if its group has the opt-in and a client home is configured (the
  `withClient` rule of `workspace_export`), so profiles without the opt-in list
  exactly what they list today; `ConfigSummary.cliOnly` (`config/ConfigSummary.java:320-332`)
  skips it for groups without the opt-in for the same reason. `list_nodes.output.json` gains the
  enum value `queue_entry_read` (additive).

**Rationale**: the version decision stays in the matrix and the adapters (Principle V, no version
branching in tool logic); refusing unknown versions instead of warning follows the spec (US3-3/4)
because the interface is internal and unverified there.

**Alternatives rejected**: a separate port set range for the helper (the 8.1 set already serves
9.0; a variant table is smaller); `PROCESS_DIAGNOSIS` as capability (its unknown-version rule
is "warn", and its label would be wrong in refusals); detecting the variant inside the helper
(version logic belongs in tested Java 21 adapter code).

## D-7 TLS: verify in the server, give the helper only the verified certificate (FR-014)

**Decision**: before every helper launch over `https` the adapter performs one TLS handshake to
the host and port of the selection's `cli.url` with
`SslContexts.forServer(labels, tls, trustStorePassword)` (`adapter/rest/SslContexts.java:41`): the
node's trust store (or the JDK default), its pin (`PinningTrustManager`) and its hostname
verification — exactly the verification of the node's REST and StartCLI connections (the pin
probe, `adapter/cli/HandshakeStartCliPinProbe.java:37-64`, does the same handshake). The accepted
peer chain is captured (`SSLSession.getPeerCertificates()`); its leaf certificate is written as
the only trusted entry into a new PKCS12 store `<tmp>/trust.p12` protected by a random one-time
password (32 bytes, Base64), which goes to the helper on stdin (D-4). The helper passes the store
to the vendor client (8.1: `trustStoreFile` + `trustStorePassword`; 9.0:
`setServerTrustStoreFile` + `setServerTrustStorePassword`) and sets hostname verification from
the group (8.1 `enableHostnameVerification = !tls.disableHostnameVerification`; 9.0
`setSkipHostnameVerification(tls.disableHostnameVerification)`).

- The handshake is refactored out of `HandshakeStartCliPinProbe` into a package-level
  `TlsPeer.handshake(server, timeout)` → peer chain; the probe keeps its behaviour and texts.
- Errors: `TLS_ERROR` / `UNREACHABLE` / `TIMEOUT` with the probe's texts, "the queue-entry helper
  was not started".
- If the server presents a different certificate to the helper than to the check, the helper's
  login fails with `TLS_ERROR` (fail closed).
- `http` URLs: no handshake, no trust store (as every other connection of such a node).
- The store lives in the call's private directory and is deleted with it (D-8).

**Rationale**: the 8.1 client silently ignores a missing trust store file and the 9.0 client
ignores StartCLI's trust store argument (commit `b35f166` on the unmerged branch
`017-live-check`: the 9.0 StartCLI ignored `--trustStoreFilePath` and needed
`javax.net.ssl.trustStore` in `JAVA_TOOL_OPTIONS`); relying on vendor verification would make
FR-014 depend on undocumented behaviour. With the server verifying and the helper trusting only
the verified leaf, the vendor client can at most fail, never accept a different server. The
`Constitution` technology constraint ("where the server cannot rely on the CLI client to verify a
pinned certificate, it MUST verify the certificate itself before the CLI connects") is met for
every mode, not only pins.

**Alternatives rejected**: passing the configured trust store file directly (8.1 ignores a missing
file; trust stores without a password and the 9.0 `setServerTrustStorePassword` path are
unverified; pins without a trust store would not work at all); depending on `b35f166`
(`JAVA_TOOL_OPTIONS` trust store for StartCLI — that fix concerns StartCLI and is not needed here;
020 neither depends on nor conflicts with it).

## D-8 Private temporary directory per call

**Decision**: each call creates `inubit-mcp-queue-<profile>-<pid>-<random>` below
`java.io.tmpdir` through `PrivateFileSystem.createTempDirectory` (owner-only on POSIX, protected
ACL on Windows, `domain/port/PrivateFileSystem.java:51`) with `helper/`, `vendor/` (the helper
JVM's `java.io.tmpdir`) and `out/`. It is registered with `CliResources` like the export
directories of `CliExportRunner` (`adapter/cli/CliExportRunner.java:43-50`) and deleted
recursively when the call ends — success, failure, timeout or interruption — and on shutdown.
A file system without private-file support → `CLI_UNAVAILABLE` (nothing launched).

## D-9 Locating the entry and the candidate rules (FR-005, edge cases)

**Decision**:
- New method on `ProcessQueryPort`: `QueueEntries queueEntries(String processId)` — the same
  request as `findByProcessId` (`adapter/rest/v81/V81ProcessQueryAdapter.java:90-101`: queueLog,
  `workflowId EQUAL <id>`, newest first, at most `MAX_ROWS_PER_PROCESS` = 100) but parsed by a new
  `QueueEntryRowParser` that keeps what `QueueLogRowParser` drops: `fileName` (uncut; a value
  over 255 characters or with `/`, `\`, NUL or control characters → `UNEXPECTED_RESPONSE`),
  `fileSize` (long), `userDefined1..5` (each cut at 2,000 = `ItemBounds.MAX_MESSAGE_CHARS`) and
  the uncut `workflowName`. Implemented in `V81ProcessQueryAdapter`; `V61ProcessQueryAdapter`,
  `RefusingPorts` refuse; `StaleReportingPorts` forwards.
- Candidates: rows with `ProcessState.ERROR`.
  - `fileName` given: the row with exactly that `fileName` among all rows of the process ID; none
    → `NOT_FOUND`; not ERROR → `INVALID_INPUT` "only ERROR entries are supported" with its state.
  - no `fileName`: no rows → `NOT_FOUND` (text as `ProcessControlService.currentRow`,
    `application/ProcessControlService.java:324-329`, "nothing was read"); rows but no ERROR row →
    `INVALID_INPUT` listing the states (raw state per row); exactly one ERROR row → read it, even
    if other rows exist; several → `INVALID_INPUT` listing each candidate (`fileName`, workflow,
    module, time) and "repeat the call with fileName"; the list is bounded to 20 candidates with
    "and N more".
  - 100 rows with more available (`total > rows`): the decision uses the rows read; the messages
    say "only N of M rows were read" (as `ProcessControlService`).
- Workflow and file name go into `objectType` / `fileName` only if they contain no `@@@`, no
  control character, no `/` or `\` (file name); otherwise `UNEXPECTED_RESPONSE` before the launch.
- **Re-read after "not found"** (D-5): if a requested part is reported not found, the service
  reads the rows again; the row (same `fileName`) gone → `NOT_FOUND` "the entry left the Queue
  Manager (restarted, killed or deleted meanwhile)"; still there → partial result with the part
  unavailable, reason "INUBIT did not find the content of this entry".

**Rationale**: the documented queueLog is the only source with state, file name and size; reusing
the request keeps one query shape (FR-005); a dedicated parser avoids widening `ProcessInstance`
for every other tool. `LogPort.query` was rejected because its `fields` are cut at 200 characters
(`adapter/rest/v81/LogEntryMapper.java:43-97`) and a 255-character file name would be corrupted.

## D-10 Text or binary, charset (FR-009)

**Decision** (`EntryContentClassifier`, domain, pure):
1. size 0 → `EMPTY` (no content field).
2. Signature at offset 0 → `BINARY` with `detected`: `ZIP` (`50 4B 03 04`, `50 4B 05 06`,
   `50 4B 07 08`), `GZIP` (`1F 8B`), `PDF` (`25 50 44 46 2D`), `PNG` (`89 50 4E 47 0D 0A 1A 0A`),
   `JPEG` (`FF D8 FF`), `GIF` (`GIF87a`, `GIF89a`), `JAVA_SERIALIZED` (`AC ED 00 05`).
3. Window = first `min(8192, size)` bytes: any `00` → `BINARY`; control bytes (`00–08`, `0B`, `0C`,
   `0E–1F`, `7F`) more than 10 % of the window → `BINARY` (no `detected`). Bytes ≥ `80` never count.
4. Otherwise `TEXT`: the whole stream is decoded with a `REPORT`ing UTF-8 decoder; valid → `UTF-8`
   (a leading BOM is dropped), else `ISO-8859-1`; the charset is named in the result. UTF-16 is
   binary by rule 3 (intended).

Binary results carry `sizeBytes`, `detected` (if a signature matched) and the hint "binary
content is not returned". Variable values use the same function on their UTF-8 bytes.

## D-11 Masking (FR-011) and the known-credential scrubber

**Decision**: order per text (data stream and each variable value): decode → **scrub known
credentials** → **mask by name** → **bound** (D-12). Scrubbing and masking work on the full text
(≤ 10 MiB), before any cut, so no credential is split by a cut.

- Known credentials: the service receives `UnaryOperator<String> knownSecrets` from `Wiring`
  (`infra/SecretScrubber::scrub`, every spelling of every registered credential → `***`,
  `infra/SecretScrubber.java:68`); `application` cannot depend on `infra`, a
  `java.util.function` type keeps `PackageBoundaryTest` green. `ResultMapper` scrubs the result
  again as for every tool.
- Name masking (`SecretTextMasker`, domain, pure, no XML/JSON parser, works on broken text):
  - **term test**: `secretName(n)` = the lower-cased local name (after the last `:`) contains one
    of `password`, `passwd`, `pwd`, `secret`, `token`, `apikey`, `api_key`, `credential`,
    `authorization`, `private`, `keystore`, `cookie`.
  - **placeholder**: `${secret:data.<name>}` with `<name>` = the local name, characters outside
    `[A-Za-z0-9_.-]` replaced by `_`, cut at 64 (no placeholder injection).
  - The rules below each yield ranges on the **original** text; overlapping ranges are merged (the
    earliest start wins, then the longest, then the lower rule number), then replaced from the
    end. So a value matched by two rules (e.g. an attribute that also looks like `name="…"`) is
    masked once, and an `Authorization` header gets `${secret:authorization}` (rule 4 before 5).
  1. **XML element**: start tag `<(prefix:)?name( attrs)?>` (not `/>`) with a secret local name →
     the range from after `>` to the matching end tag `</(same qname)\s*>`, counting nested start
     tags of the same qualified name; no matching end tag → up to the next `<` or the end of the
     text. Self-closing elements: nothing. CDATA inside is part of the range.
  2. **XML attribute**: inside any start tag, `qname\s*=\s*("…"|'…')` with a secret local name →
     the value between the quotes.
  3. **JSON key**: `"key"\s*:\s*` with a secret key → a string literal (escapes respected) is
     replaced inside its quotes; an object or array is replaced as a whole by
     `"${secret:data.<key>}"` (brackets counted outside strings; unterminated → end of text); a
     scalar up to the next `,`, `}`, `]`, whitespace or line end.
  4. **Authorization header**: a line `^\s*(Proxy-)?Authorization\s*:\s*(.*)$` (case-insensitive)
     → the value to the end of the line becomes `${secret:authorization}`.
  5. **Header-style line**: `^\s*([A-Za-z0-9][A-Za-z0-9_.-]*)\s*:\s*(.*)$` (multi-line) with a
     secret name → the value to the end of the line.
  6. **`name=value`**: `(?<![A-Za-z0-9_.-])([A-Za-z0-9_.:-]+)=` with a secret local name → a value
     starting with `"` or `'` up to the matching quote (unterminated → end of line), otherwise up to
     the first `&`, `;`, whitespace or line end.
  7. **Basic / Bearer**: `\bBasic\s+([A-Za-z0-9+/]{4,}={0,2})` whose Base64 decodes to text with a
     `:` (user:password), and `\bBearer\s+([A-Za-z0-9._~+/-]{16,}=*)` → `${secret:authorization}`.
     The minimum lengths avoid masking prose such as "Bearer of".
- **Variables**: a variable whose name passes the term test gets the value
  `${secret:variables.<name>}` (same name normalisation); its content is never classified or
  returned. Other values go through the text pipeline. Names themselves are scrubbed, not masked.
- Known gap documented with FR-004: credentials under neutral names (e.g. an INUBIT
  `<Property name="password">` element inside a data stream, whose element name is `Property`)
  are not masked; the setup guide says so.

**Rationale**: a range-based scanner implements every bullet of FR-011 including cut-off and
broken text without parsers that would reject such text; merging ranges makes the rules
order-independent and testable one by one.

## D-12 Bounds and the 10 MiB limit (FR-009, FR-010)

**Decision**:
- Data stream: the row's `fileSize` > 10 MiB (10,485,760) → the data part is not requested
  (`parts=variables`) and reported unavailable "too large (<n> bytes; limit 10 MiB)". A
  downloaded file larger than 10 MiB (row and file disagree) → same, the file is not read.
- Variables: the `.wfsp` file > 10 MiB → unavailable "too large".
- Text cut at `maxChars` (default 20,000, 1–100,000) UTF-16 units without splitting a surrogate
  pair (`RowValues.cut` semantics, `adapter/rest/v81/RowValues.java:60-81`, reimplemented in the
  domain), with `truncated: true`, `returnedChars`, `totalChars` and `sizeBytes`.
- Variables in file order: at most 200 (system ones included; `variablesTruncated: true`,
  `totalCount`); names cut at 200 (`nameTruncated`); each value cut at
  `min(2,000, remaining of 40,000)` (`truncated: true`, `totalChars`); once the 40,000 are used up,
  further values are returned with an empty `value` and `truncated: true`. All counts apply to
  the masked text; names do not count.

## D-13 Variables file and system variables (FR-010)

**Decision**: `WfspParser` (adapter, INUBIT format) reads the `.wfsp` with the secure parser
settings of `adapter/rest/XmlSupport` (no DTD, no external entities), streaming (StAX):
root `Properties` (any `version`), children `Property` with required `name`, optional `type`, the
text content as value (an empty element = empty value). Any other structure → the variables part
unavailable "unexpected format; the unsupported INUBIT interface may have changed"; the parser's
own message is never used (it may quote content). Result: `RawVariable(name, Optional type,
String value)` in file order. `system` is decided in the domain by the fixed list of FR-010 and
the prefix `_hidden.`.

## D-14 Timeout and concurrency (FR-016)

**Decision**: one deadline per call = start of the helper phase + node `cliTimeout`, shared by
(1) the slot wait, (2) the TLS check (bounded by `min(server.timeout(), remaining)`), (3) the
helper process (`remaining`). The REST lookups before and after keep the node's REST `timeout` as
every read tool. Slot: `CliConcurrencyLimiter` gets `acquireForHelper(server, Duration wait)` that
uses the same per-node fair semaphore (2 permits, `adapter/cli/CliConcurrencyLimiter.java:27-69`)
**without** the 6.1-only shortcut; expiry → `TIMEOUT` with the limiter's text. `TIMEOUT` returns
no partial content (US1-10).

**Interpretation of FR-016 (owner decision 2026-11-10)**: "counts against the StartCLI
concurrency limit of that server" means that every helper run takes a slot of the server's
per-node StartCLI semaphore (`acquireForHelper`). Today only 6.1 StartCLI runs take that
semaphore (`adapter/cli/v61/V61Connection.java:122`); StartCLI runs on 8.1/9.0 nodes stay as they
are, without a slot (SC-002: existing tools unchanged). On 8.1/9.0 the limit therefore bounds the
helper runs per node to 2; making 8.1/9.0 StartCLI runs take slots too is out of scope.

## D-15 Opt-in setting (FR-002, FR-003, FR-017) and tool announcement

**Decision** (mirrors `archives.productionOptIn`, `config/ArchivesConfig.java:14`):
- `GroupConfig` gets the component `UnsupportedConfig unsupported` (default `EMPTY`);
  `record UnsupportedConfig(Optional<Boolean> queueEntryRead)`. `NodeConfig` and `Defaults` do not
  have it, so `unsupported` on a node or in `defaults`, and any other key under `unsupported`, fail
  as "Unknown key '<key>' at <path>" through `FAIL_ON_UNKNOWN_PROPERTIES`
  (`config/ConfigLoader.java:112,357-360`; the value is never echoed).
- Strict boolean: `ConfigLoader.requireStrictUnsupportedOptIn` beside
  `requireStrictArchivesOptIn` (`ConfigLoader.java:344,602-614`): a present, non-null, non-boolean
  value (`"true"`, `1`, `yes`) → "Invalid value at groups[i].unsupported.queueEntryRead in
  <source>: expected true or false". `null` = default `false`.
- `EffectiveNodeConfig` gets `boolean queueEntryRead` (resolved from the group only; every copy
  method passes it through, like `archivesProductionOptIn`, `EffectiveNodeConfig.java:56,496`).
- `ConfigValidator.checkQueueEntryReadOptIn`: warning on a `production: true` group:
  "unsupported.queueEntryRead is enabled on production {group} %s: Queue Manager entries are read
  through an undocumented INUBIT interface without vendor support". Startup logs it
  (`Launcher.java:486`), `--check-config` lists it under "Warnings:".
- `ConfigSummary` (owner decision 2026-11-10): only if **at least one group of the profile** has
  `unsupported.queueEntryRead: true`, every group line (`ConfigSummary.java:137-141`) ends with
  `, queueEntryRead: off` or `, queueEntryRead: enabled (unsupported)` (FR-017 "per group").
  Without any opt-in the startup summary and `--check-config` output are byte-for-byte those of
  0.8.0 (SC-002; no golden text of an existing profile changes).
- Tool announcement (`Wiring.toolHandlers`, `Wiring.java:562-603`): `read_queue_entry` is
  announced iff some node has `queueEntryRead`, `possiblyCapable(node, QUEUE_ENTRY_READ)`
  (`Wiring.java:747-755`; a node pinned to 6.1, a line other than 8.1/9.0, or — under the
  fallback — 9.0 does not count) and `clientSelection.anyCandidate(node)` (a client home is
  configured, installed or not, `domain/port/ClientSelectionPort.java:20-26`).
- Call-time refusal for a group without the opt-in: `NOT_CONFIGURED` before target-version
  detection or any request (contracts/mcp-tools-delta.md).

**Rationale**: the same binding, strictness and summary style as the existing group opt-in;
announcement mirrors the exports' client rule.

## D-16 Logging and audit (FR-018)

**Decision**: one `INFO` line per call from the service: `read_queue_entry on {node}: process {id},
file {fileName}, data {n|-} bytes, variables {n|-}, outcome {OK|PARTIAL|<code>}`. The adapter
logs at `DEBUG` the chosen variant, client home and JDK (no URL). Neither ever logs the request,
the helper's stdout/stderr, the data stream or a variable value; helper output is only parsed for
frames and discarded. Exceptions carrying content are not created (fixed reason texts). A test
captures the logback output of a full call with secret-looking content and asserts that none of
the content appears. No audit record (reads are not audited; `export_archives` audits because it
writes clear-text secrets to disk, which this tool does not).

## D-17 Domain and port shape

**Decision**:
- `domain/port/QueueEntryContentPort`: `RawEntryContent read(QueueEntryRef ref, InubitVersion
  version, Set<EntryPart> parts)` — added to `PortSet`, `Gateway`, `GatewayFactory`,
  `AdapterGatewayFactory`, `V81Gateway`, `StaleReportingPorts`, `RefusingPorts`, `V61PortSet`.
- Adapter `adapter/cli/queue/CliQueueEntryAdapter` (variant, selection, TLS, temp dir, helper run,
  classification, file reads, `WfspParser`), built in `V81PortSet.create` with the node's
  credentials, `CredentialGuard`, `CliRunner`, `ProcessRun`, limiter and `PrivateFileSystem`.
- Application `QueueEntryService` (input validation, opt-in, target, gate, lookup, candidates,
  port call, re-read, classification, masking, bounds, result, log line); tool
  `mcp/tools/ReadQueueEntryTool`.

## D-18 Fixtures: neutralizing the 8.1 recording

**Decision**: new fixture root `src/test/resources/fixtures/queue-entry/` with `v8_1/` and
`v9_0/`, a `README.md` and one `<case>.origin` per case (first line `recorded <date> <version>
neutralized` or `reconstructed <source>`), checked by `QueueEntryFixtureCatalogueTest` (every case
has `.origin`; every file belongs to a case). It is a separate root so the 017 closed world of
`fixtures/v9_0` (`V90FixtureCatalogueTest`) stays untouched.

Case files: `<case>.data` (data stream bytes), `<case>.wfsp`, `<case>.frames` (helper stdout incl.
vendor noise lines), `<case>.exit`, `<case>.rows.json` (the queueLog answer of the lookup).

8.1 `error_entry` comes from the spike download (data stream and `.wfsp` of a real ERROR entry,
8.1.17, 2026-11-10), neutralized **outside the repository** before it is copied in:
1. copy the two files to a private directory; run `tools/synthesize_names.py collect --keep
   <owners> --out <private>/.synthetic-map <dir>` and `tools/neutralize.py --map
   <private>/.synthetic-map <dir>` plus the private `neutralize-map` (customer names → `acme`,
   `Workflow-0001`, `Module-0001`, `*.example.test`);
2. replace every business value by hand with fictitious content of the same shape (element
   structure, XML escaping of `XmlDocument` values, variable count, order, types and system
   variable names are kept; payload text becomes e.g. a fictitious validator SOAP fault with
   `Order-0001`);
3. adjust `rows.json` `fileSize` to the neutralized size;
4. run `NoCustomerIdentifiersTest` with the private lists and `tools/check-identifiers.py`; the
   owner reviews the diff before the commit.
The helper frames for 8.1 are re-recorded by the live check (D-21) and neutralized the same way;
until then `error_entry.frames` is `reconstructed` from the helper protocol.

Synthetic cases (each with `.README`): `binary_zip`, `utf16_text`, `latin1_text`,
`secrets_everywhere` (every FR-011 bullet), `many_variables` (250), `long_values`,
`missing_variables`, `cutoff_tag`, `too_large` (size only), `not_found_file` (9.0 text recorded;
8.1 reconstructed), `login_failure`, `tls_failure`, `api_missing`, `garbage_frames`.

## D-19 Contract tests per version (Constitution V, FR-019)

**Decision**: `QueueEntryV81ContractTest` and `QueueEntryV90ContractTest` run the full adapter
(`CliQueueEntryAdapter`) with `FakeProcessLauncher.onLaunch` (`src/test/java/.../adapter/cli/
FakeProcessLauncher.java:99-132`) that reads the request from the stdin bytes, copies the case's
`.data`/`.wfsp` to `dataOut`/`variablesOut` and replays `.frames`/`.exit`; then the service turns
it into the result and the tests assert metadata, text, variables, masking and bounds. The helper
itself is tested in-process (`run(...)`) against fake vendor classes in the test tree
(`src/test/java/com/inubit/ibis/…`: `IBISSoapClient`, `ConnectionData`,
`client.rest.InubitClientFactory`, `MultipartDownloadThread`, `utils.I_IBISClientInterface`)
that write the fixture files and throw the recorded exception texts; one test launches it in a
real child JVM through `SystemProcessLauncher` with the test classes as client `lib/` (a
`FakeInubitClient` installer, like `testing/FakeStartCli`).

## D-20 The 9.0 recording and its fallback (FR-019)

**Decision**:
- Production of the recording: on a **non-production** 9.0 test server the operator creates one
  ERROR entry (e.g. a test workflow whose Throw module ends in the Queue Manager with a fictitious
  payload and a variable named `apiToken`). Alternatively the agent proposes a concrete trigger
  (server, workflow, payload, how the entry is produced and removed) and runs it only after the
  operator approves exactly that action in the chat (owner decision 2026-11-10). Then the live check (D-21) runs
  with `INUBIT_LIVE_RECORD_DIR` and the result is neutralized as in D-18 into
  `fixtures/queue-entry/v9_0/error_entry.*` with `.origin` `recorded <date> 9.0.x neutralized`.
- Afterwards the operator removes the entry in the Workbench (the tool never does).
- The 9.0 matrix state flips to `SUPPORTED` in the same change that adds the recording; the
  contract test asserts that `SUPPORTED` for 9.0 and a `recorded` 9.0 `error_entry.origin` go
  together (`QueueEntryFixtureCatalogueTest`).
- **Fallback** (no 9.0 test server or the operator declines): 9.0 stays `UNSUPPORTED` with the
  note "not yet verified" (`UNSUPPORTED_VERSION` text "reading Queue Manager entries is not yet
  verified for INUBIT 9.0"); the `REST_90` variant and its fake-client tests stay; FR-002 treats
  9.0 as unsupported (a `V9_0` pin does not announce the tool); CHANGELOG and docs say so;
  enabling 9.0 after a later recording is a follow-up change.

## D-21 Live check (docs/live-tests.md)

**Decision**: `live/QueueEntryLiveTest` (`@Tag("live")`, runs only with `-Plive`, production
refused by `LiveTarget`):
- `INUBIT_LIVE_NODE=<group>/<node>` (group with `unsupported.queueEntryRead: true`),
  `INUBIT_LIVE_QUEUE_ENTRY=<processId>[/<fileName>]` (an existing ERROR entry; skipped without).
- Reads the queue rows (REST) before, calls `read_queue_entry` through the wired server, reads
  the rows again: same state, `startTime`, `fileSize` and `fileName` (SC-004); the result has
  metadata, data (text or binary hint) and variables; the captured server log contains none of
  the returned values.
- Optional `INUBIT_LIVE_RECORD_DIR=<private dir outside the repository>`: writes the raw
  `.data`, `.wfsp`, helper frames and exit code there (owner-only) for D-18/D-20.
- Prints one `[live] read_queue_entry <node> process <id>: data <n> bytes (<kind>), <m> variables,
  entry unchanged` line.
- `docs/live-tests.md` gets a section "Queue entry read (feature 020)" and a row in the tests
  table; run against an 8.1 and a 9.0 test server (quickstart.md).

## D-22 Documentation

**Decision**: `docs/setup.md` new subsection `### Unsupported functions` after `### Workbench
archives` with the FR-004 text (contracts/configuration-delta.md); `docs/tools.md` new section
`## read_queue_entry`, a row in the capability table and the tool-announcement rule;
`README.md` tool list; `CHANGELOG.md` `[Unreleased]`. `ArchiveSetupSecurityDocsTest`-style test
`QueueEntryDocsTest` asserts that the setup guide and the tool reference contain the mandatory
warning sentences of FR-004 and that the tool description contains its short form.
