# Tasks: Read Queue Manager Entries (unsupported, opt-in)

**Input**: Design documents from `specs/020-queue-entry-read/` (spec, plan, research D-1…D-22,
data-model, contracts, quickstart, reviews/)

**Tests**: mandatory (constitution Principle III, TDD): each implementation task starts with its
failing tests in the same task (named in the task) and is done when they pass and the build is
green. No offline test contacts INUBIT; content behaviour is tested against
`src/test/resources/fixtures/queue-entry/`, fake vendor classes (`src/test/java/com/inubit/ibis/…`)
and `FakeProcessLauncher`; the queueLog lookup against WireMock.

**Build**: `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-020-tmp` (tmpdir
outside the checkout; never two builds in parallel); Python: `python3 -I -m unittest discover -s
tools/tests`. Paths below are relative to `src/main/java/de/dadecker/inubit/mcp/` (main) and
`src/test/java/de/dadecker/inubit/mcp/` (test) unless they start with `src/`, `docs/`, `tools/`,
`pom.xml`.

**Secrets (SC-003, Constitution II)**: every task that adds a message, warning, log line, excerpt,
frame or request asserts with `testing/TestSecrets` that a configured password, the one-time
trust-store password and a credential-named payload value never appear in it — named below as
"leak check".

**Customer-neutral (FR-020)**: only fictitious names (`acme`, `globex`, `Workflow-0001`,
`Module-0001`, `Order-0001`, `*.example.test`) in code, tests, fixtures and docs.

**Gates**: tasks marked **[OWNER]** need the owner's decision or review before they are done;
tasks marked **[LIVE]** need a non-production INUBIT test server and are run by or with the
owner. The implementer stops at such a task and reports what is needed.

**Reviews**: each task goes through the implementer → reviewer loop
(`d4ld4r-speckit-duo:implement`); after each phase a code review of the phase diff; findings and
decisions go to `specs/020-queue-entry-read/reviews/implementation.md`.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [X] T001 Baseline: `mvn -o -q clean verify` with the tmpdir above and the Python tests are green
  on the branch; create `specs/020-queue-entry-read/reviews/implementation.md` and note the test
  count (surefire + failsafe) as the SC-002 reference

## Phase 2: Foundational — configuration, capability, gate, process pipeline (plan stages 1, 2, 5; blocks every story)

- [X] T002 [P] Opt-in binding (FR-003, D-15): new `config/UnsupportedConfig.java`
  (`record UnsupportedConfig(Optional<Boolean> queueEntryRead)`, `EMPTY`); `config/GroupConfig.java`
  `+ UnsupportedConfig unsupported` (default `EMPTY`); `config/ConfigLoader.java`
  `requireStrictUnsupportedOptIn` beside `requireStrictArchivesOptIn` ("a present, non-null,
  non-boolean value → `Invalid value at groups[i].unsupported.queueEntryRead in <source>: expected
  true or false`", never echoing the value); `config/EffectiveNodeConfig.java` `+ boolean
  queueEntryRead` resolved from the group only (default `false`) and passed through both
  constructors, `withTimeout`, `withCli`, `entryProbe`; the existing secondary constructor
  (`EffectiveNodeConfig.java:93-102`) keeps its signature and passes `false`; the direct test
  callers `src/test/java/de/dadecker/inubit/mcp/adapter/TestNodeConfig.java`,
  `config/EffectiveNodeConfigCliForTest.java`, `adapter/cli/StartCliPinProbeTest.java` and
  `adapter/cli/CliRunnerTest.java` either use that unchanged secondary constructor or get the
  extra `false` argument (no behaviour change).
  Failing tests first in `config/ConfigLoaderTest.java` (`true`/`false`/empty/`null` load;
  `"true"`, `1`, `0`, `yes`, list, mapping → error without the value; `unsupported.archiveRead` →
  `Unknown key 'archiveRead' at groups[0].unsupported`; `unsupported` on a node and in `defaults`
  → unknown key) and `config/EffectiveNodeConfigTest.java` (value survives every copy method);
  leak check on the error texts
- [X] T003 Capability and matrix (FR-006, FR-019 fallback state, D-6; after T002): `domain/model/Capability.java`
  `+ QUEUE_ENTRY_READ` (kind `READ`, label "reading Queue Manager entries");
  `domain/model/CapabilityMatrix.java`: 8.1 `SUPPORTED`; **9.0 `UNSUPPORTED` with the note "not
  yet verified"** (the fallback state; flipped only by T038); 6.1 unlisted; unknown map
  `QUEUE_ENTRY_READ → UNSUPPORTED`; `Entry` `+ Map<Capability, String> notes`;
  `application/VersionGate.java` (`cliOnlyStep` case; refusal text uses the entry note);
  `application/NodeOverview.java` (`name` = `queue_entry_read`);
  `src/main/resources/schemas/list_nodes.output.json` enum `+ "queue_entry_read"`;
  `config/ConfigSummary.java` `cliOnly` (it loops `Capability.values()`, `ConfigSummary.java:327`)
  lists `QUEUE_ENTRY_READ` only for groups with `queueEntryRead` (T002), so no existing 6.1 text
  changes. Failing tests first: `config/ConfigSummaryTest.java` and `MainTest.java` regression
  "existing 6.1 golden texts unchanged" plus a 6.1 group with the opt-in listing "reading Queue
  Manager entries" as refused, `domain/model/CapabilityMatrixTest.java` (8.1 supported, 9.0 unsupported with note, 9.1
  and unknown unsupported, other capabilities' unknown rule unchanged),
  `application/VersionGateTest.java` (9.0 refusal "… is not yet verified for INUBIT 9.0", 6.1 and
  9.1 `UNSUPPORTED_VERSION` naming the supported lines 8.1, 9.0), `application/NodeOverviewTest.java`
- [X] T004 `VersionGate.admitWithDetection(tool, node, capability, purpose, channels)` (US3-4, D-6,
  owner decision): a detection run in this call that fails is recorded in `NodeVersions` as today
  and then **rethrown with its own code and message**; during the 60 s back-off with a recorded
  `DetectionFailure` and no known version → `ToolError` with the recorded code, message "The
  INUBIT version of <node> could not be detected (<CODE> at <time>); read_queue_entry needs it",
  next step "Fix the cause and retry in a minute, or set versionLine of <node>"; no second
  detection during the back-off; `admit`/`evaluate` unchanged for every other caller — main
  `application/VersionGate.java`; failing tests first in `application/VersionGateTest.java`
  (fresh `UNREACHABLE`, `TIMEOUT`, `TLS_ERROR`, `AUTH_FAILED`, `UNEXPECTED_RESPONSE` passed through;
  back-off replays the code without a detection call; known 8.1 version admits; existing `admit`
  tests unchanged); leak check (depends on T003)
- [X] T005 [P] Domain types and ports (data-model "Domain"): `domain/model/QueueEntryRow.java`
  ("`fileName` 1–255, no `/`, `\`, NUL, control chars; workflow without `@@@` or control chars";
  `errorTexts` `userDefined1..5`, non-empty only, "each ≤ 2,000"), `QueueEntries.java` ("newest
  first, ≤ 100; `truncated()` = `total > rows.size()`"), `QueueEntryRef.java`, `EntryPart.java`,
  `RawEntryContent.java` with `PartResult` (`Read` | `NotRequested` | `NotFound` | `Failed` |
  `TooLarge`; "reasons are fixed or scrubbed texts, never content"), `RawVariable.java`,
  `QueueEntryBounds.java` (`MAX_DATA_BYTES = 10 MiB`, `DEFAULT_MAX_CHARS = 20_000`,
  `MAX_MAX_CHARS = 100_000`, `MAX_VARIABLES = 200`, `MAX_NAME_CHARS = 200`, `MAX_VALUE_CHARS =
  2_000`, `MAX_VALUES_CHARS = 40_000`, `MAX_LISTED_CANDIDATES = 20`), `SystemVariables.java` (the
  FR-010 list `ISFileSize`, `ISCurrentTime`, `ISWorkflowName`, `ISModuleName`, `ISModuleId`,
  `ISProcessId`, `ISUserName`, `ISServerName`, `ISNodeName`, `ISGlobalProcessId`,
  `ISOverwriteSubWorkflowPriority` and prefix `_hidden.`; `ISIN` is not system);
  `domain/port/QueueEntryContentPort.java`; `domain/port/ProcessQueryPort.java`
  `+ default QueueEntries queueEntries(String processId)` that refuses (`UNSUPPORTED_VERSION`
  "Reading Queue Manager entries is not available for this adapter line; nothing was sent"), so
  the existing implementers and test fakes (`RunE2eTestToolTest`, `DiagnosisServiceTest.FakeProcesses`,
  `CapabilityExtensionTest`, `FakeProcessControl`, `WriteRaceToolsTest`, `E2eTestServiceTest`)
  compile unchanged; the real implementation follows in T012. Failing tests first:
  `domain/model/QueueEntryRowTest.java`, `SystemVariablesTest.java`, `QueueEntriesTest.java`,
  `domain/port/ProcessQueryPortTest.java` (default refuses without any call)
- [X] T006 Extract the process pipeline (D-3): new `adapter/cli/ProcessRun.java`
  (`CliResult run(LaunchSpec spec, byte[] stdin, Duration timeout, String processLabel)`: reserve,
  launch, drain stdout/stderr with the 1 MiB bound, write stdin then zero-fill it, wait, tree kill
  with the 2 s grace, `TIMEOUT`/`CLI_UNAVAILABLE`/`INTERNAL` texts with `processLabel`) moved out of
  `adapter/cli/CliRunner.java` (`launchAndWait`, `waitFor`, `writeStdin`, `terminate`,
  `BoundedSink`); `CliRunner` delegates with label "StartCLI" (texts unchanged);
  `CliRunner.javaHome(server)` and `adapter/cli/CliResources.java` `Reservation.process` public.
  Failing tests first in `adapter/cli/ProcessRunTest.java` (`FakeProcessLauncher`: stdin bytes
  delivered and buffer zeroed, label in timeout and launch-failure texts, 1 MiB truncation flag,
  `hanging()` → kill after timeout, `ignoringDestroy()` → forcible kill); regression net: existing
  `CliRunnerTest`, `CliRunnerWindowsSelectTest`, export/import/tag runner tests unchanged and green
- [X] T007 [P] `adapter/cli/CliConcurrencyLimiter.java` `+ Permit acquireForHelper(EffectiveNodeConfig,
  Duration wait)`: the same per-node fair semaphore (2 permits) without the 6.1-only shortcut;
  expiry → `TIMEOUT` with the limiter's text; `acquire` unchanged (StartCLI on 8.1/9.0 still takes
  no slot — FR-016 interpretation, owner decision) — failing tests first in
  `adapter/cli/CliConcurrencyLimiterTest.java` (third helper run on an 8.1 node waits and times out;
  a 6.1 StartCLI run and helper runs share the slots; `acquire` on 8.1 still a no-op)
- [X] T008 [P] `adapter/cli/TlsPeer.java` (`List<X509Certificate> handshake(EffectiveNodeConfig,
  Duration)` with `SslContexts.forServer`: trust store, pin, hostname verification; errors with the
  probe's texts) extracted from `adapter/cli/HandshakeStartCliPinProbe.java`, which now uses it
  (D-7) — failing tests first in `adapter/cli/TlsPeerTest.java` (local TLS server on 127.0.0.1 with a
  self-signed certificate: pin match returns the chain; pin mismatch, unknown CA, hostname mismatch
  → `TLS_ERROR`; closed port → `UNREACHABLE`; slow server → `TIMEOUT`); `HandshakeStartCliPinProbeTest`
  unchanged

**Checkpoint**: build green, test count ≥ T001; code review phase 2.

## Phase 3: User Story 1 – See why a process failed (P1) 🎯 MVP

**Goal**: for an ERROR entry of an opted-in 8.1 node, the service returns metadata, the data stream
(text/binary, bounded) and the variables, masked (FR-005, FR-007…FR-016, FR-018).
**Independent test**: `QueueEntryServiceTest` and `QueueEntryV81ContractTest` with an opted-in
profile (T002), WireMock queueLog rows and `FakeProcessLauncher` replaying a fixture case; no
INUBIT contact.

### Domain logic (plan stage 4)

- [ ] T009 [P] [US1] `domain/model/ContentKind.java`, `BinarySignature.java`,
  `EntryContentClassifier.java` (FR-009, D-10): empty → `EMPTY`; signatures `ZIP` (`50 4B 03 04`,
  `50 4B 05 06`, `50 4B 07 08`), `GZIP` (`1F 8B`), `PDF` (`25 50 44 46 2D`), `PNG`
  (`89 50 4E 47 0D 0A 1A 0A`), `JPEG` (`FF D8 FF`), `GIF` (`GIF87a`, `GIF89a`), `JAVA_SERIALIZED`
  (`AC ED 00 05`); window "first min(8 KiB, size) bytes"; NUL or "more than 10 % control bytes
  (0x00–0x08, 0x0B, 0x0C, 0x0E–0x1F, 0x7F; bytes 0x80 and above never count)" → `BINARY`; else
  UTF-8 if the whole stream is valid (BOM dropped), otherwise ISO-8859-1 — failing tests first in
  `domain/model/EntryContentClassifierTest.java` (each signature, NUL at byte 8191 vs 8192, exactly
  10 % vs 10 % + 1, UTF-16LE → binary, Latin-1 umlaut → ISO-8859-1, 3-byte stream, empty)
- [ ] T010 [P] [US1] `domain/model/SecretTextMasker.java` rules 1–3 (FR-011, D-11): term test
  (lower-cased local name contains `password`, `passwd`, `pwd`, `secret`, `token`, `apikey`,
  `api_key`, `credential`, `authorization`, `private`, `keystore`, `cookie`); placeholder
  `${secret:data.<name>}` (name chars outside `[A-Za-z0-9_.-]` → `_`, cut at 64); rule 1 XML
  element (whole content incl. children and CDATA; nested same-name tags counted; no end tag →
  up to the next `<` or end; self-closing → nothing); rule 2 XML attribute; rule 3 JSON key
  (string inside quotes; object/array as a whole → `"${secret:data.<key>}"`; unterminated → end;
  scalar up to `,` `}` `]` whitespace) — failing tests first in
  `domain/model/SecretTextMaskerTest.java`, one test per rule and edge case (`<wsse:Password>`,
  `<a:apiToken><x>1</x></a:apiToken>`, cut-off `<password>abc`, `<password/>`,
  `pwd="a b"` attribute, `"password": "x"`, `"credentials": {"u":1}`, broken JSON); rest of the
  text byte-identical
- [ ] T011 [US1] `SecretTextMasker` rules 4–7 and merging (D-11): rule 4 `(Proxy-)Authorization:`
  line → `${secret:authorization}`; rule 5 header-style `name: value` line with a secret name →
  to end of line; rule 6 `name=value` (quoted → matching quote, unterminated → end of line; else
  up to the first `&`, `;`, whitespace or line end); rule 7 `Basic <base64 decoding to text with
  ':'>` and `Bearer <token ≥ 16 chars>` → `${secret:authorization}`; ranges on the original text,
  merged (earliest start, then longest, then lower rule number), replaced from the end — main
  `domain/model/SecretTextMasker.java`; failing tests first in `SecretTextMaskerTest.java`
  (`X-Api-Key:`, `Cookie:`, `Authorization: Basic …`, `?user=a&password=b&x=1`, `pwd='a b'`,
  "Bearer of the message" and "Basic configuration" unmasked, attribute matched by rules 2 and 6
  masked once, placeholder injection `<pass${x}word>` normalised) (depends on T010)

### Lookup (plan stage 3)

- [ ] T012 [US1] queueLog lookup (FR-005, FR-008, D-9): new `adapter/rest/v81/QueueEntryRowParser.java`
  (keeps uncut `fileName`, `workflowName`, `fileSize` as long, `userDefined1..5` cut at 2,000; a
  `fileName` over 255 chars or with `/`, `\`, NUL, control chars, or a workflow with `@@@` or
  control chars → `UNEXPECTED_RESPONSE`); `adapter/rest/v81/V81ProcessQueryAdapter.java`
  `queueEntries` (same request as `findByProcessId`: `workflowId EQUAL <id>`, newest first, 100);
  `adapter/StaleReportingPorts.java` `Processes` **overrides and forwards** `queueEntries` (stale on
  `UNEXPECTED_RESPONSE`; without the override the wrapped 8.1 port would hit the refusing
  default); `adapter/RefusingPorts.java` overrides with its 6.1 refusal text;
  `adapter/cli/v61/V61ProcessQueryAdapter.java` keeps the refusing default (T005).
  **Contract test over the recorded 8.1 answers first**: `adapter/rest/v81/QueueEntryRowsContractTest.java`
  serves `src/test/resources/fixtures/v8_1/rest/log_queueLog_error.json` and `log_queueLog.json`
  through WireMock to `V81ProcessQueryAdapter.queueEntries` and asserts `fileName`, `fileSize` as
  `long`, `userDefined1..5` error texts, `ERROR` state/raw state, `workflowName` uncut. Synthetic
  queueLog rows `src/test/resources/fixtures/queue-entry/v8_1/rows_*.json` (`.origin`
  `reconstructed`) only for what the recordings lack: two ERROR rows of one process; ERROR +
  WAITING; no ERROR row; empty; 255-char file name; bad file name; workflow with `@@@`. Further
  failing tests first in `adapter/rest/v81/QueueEntryRowParserTest.java`,
  `adapter/rest/v81/V81ProcessQueryAdapterTest.java` (WireMock request body),
  `adapter/StaleReportingPortsTest.java` (forwarding), `adapter/RefusingPortsTest.java`,
  `adapter/cli/v61/V61ProcessQueryAdapterTest.java` (default refusal, nothing sent)

### Helper (plan stage 6)

- [ ] T013 [P] [US1] Fake vendor client for tests (D-19): `src/test/java/com/inubit/ibis/soap/IBISSoapClient.java`,
  `.../configuration/utils/ConnectionData.java`, `.../client/rest/InubitClientFactory.java`,
  `.../configuration/utils/io/MultipartDownloadThread.java`, `.../utils/I_IBISClientInterface.java`
  with the signatures seen by `javap` (research "Spike facts"), driven by a script object
  (`testing/FakeInubitScript.java`: login result, per-file content or exception text, recorded call
  list); `testing/FakeInubitClient.java` installs a client home whose `lib/` holds a JAR of these
  classes (built at test time) and a `conf/` directory — tests `testing/FakeInubitClientTest.java`
- [ ] T014 [US1] Helper build and protocol skeleton (D-2, D-4): `pom.xml` execution
  `compile-queue-entry-helper` (`<release>17</release>`, `compileSourceRoots` `src/helper/java`,
  `outputDirectory` `${project.build.outputDirectory}/META-INF/inubit-mcp/queue-entry-helper`);
  `src/helper/java/de/dadecker/inubit/mcp/helper/QueueEntryHelper.java` (one top-level class, no
  nested/anonymous classes, JDK only): reads the stdin request (`qe=1`, percent-encoded
  `key=value` lines, `end`), validates it (exit 2 on a malformed request), frame writer
  `#QE1:<nonce> …`, `static int run(InputStream, PrintStream, ClassLoader)`, `main` =
  `System.exit(run(…))`. Test access (also for T015): `QueueEntryHelperTest` loads the helper
  with a `URLClassLoader` over `target/classes/META-INF/inubit-mcp/queue-entry-helper/` (parent =
  the test class loader, so the T013 fake vendor classes are visible) and calls
  `run(InputStream, PrintStream, ClassLoader)` reflectively, passing that loader as the vendor
  loader; `main` and `System.exit` are never called in-process. Failing tests first:
  `adapter/cli/queue/QueueEntryHelperPackagingTest.java`
  (exactly one class file in the resource dir, class-file major version 61, also inside the
  shaded JAR at package time), `adapter/cli/queue/QueueEntryHelperTest.java` (malformed request →
  exit 2 without echoing values), `architecture/PackageBoundaryTest.java` new rule "the helper
  references only `java/*`"
- [ ] T015 [US1] Helper flows by reflection (FR-006, FR-007, FR-014, FR-015, D-4): `SOAP_81`
  (`IBISSoapClient(url + "?isCli", trustStore, trustStorePassword, null, null,
  enableHostnameVerification)`, `loginUserCli`, `logoutUser` in `finally`) and `REST_90`
  (`ConnectionData` setters incl. `setSkipHostnameVerification(!hostnameVerification)`,
  `InubitClientFactory().getRestInstance`, `loginUserCli`, no logout); per requested part
  `MultipartDownloadThread(client, user, fileName[+".wfsp"], objectType, null, null)`,
  `setOutputFile`, `start`, `join`, `exceptionOccured`/`getException`; frames `start`,
  `login ok|error`, `part … ok|error` (exception chain, message cut at 1,000), `api missing`,
  `end`; exit codes 0/1/3/4; vendor classes resolved only through the `ClassLoader` argument
  (`main` passes the system class loader) — main `src/helper/java/.../QueueEntryHelper.java`;
  failing tests first in `adapter/cli/queue/QueueEntryHelperTest.java` (in-process via the
  `URLClassLoader` of T014 with T013 fakes: both
  variants, only login/download/logout called (FR-007), variables-only request, part error with
  `@Start@Unable to download`, missing class → `api missing` exit 4, login failure exit 3,
  password and trust-store password never printed; leak check)
- [ ] T016 [US1] `adapter/cli/queue/QueueEntryHelperProcessTest.java`: the packaged helper runs in
  a real child JVM through `SystemProcessLauncher` with `FakeInubitClient` as client home and the
  D-3 command (`-cp <helper>:<home>/lib/*:<home>/lib/shared/*`), credentials on stdin only (argv =
  JVM options + class name), vendor noise lines on stdout ignored, `System.exit` ends a JVM with a
  lingering non-daemon thread (depends on T014, T015)

### Adapter (plan stage 7)

- [ ] T017 [P] [US1] `adapter/cli/queue/HelperRequest.java` (stdin request of data-model "Helper
  request"; `byte[] encode()`; `toString()` without `user`, `password`, `trustStorePassword`) and
  `adapter/cli/queue/HelperFrames.java` (frames of one nonce; other lines ignored; frame with a
  wrong nonce ignored; unknown frame type, duplicate `end`, missing `end` reported) — failing tests
  first in `adapter/cli/queue/HelperRequestTest.java`, `HelperFramesTest.java`; leak check
- [ ] T018 [P] [US1] `adapter/cli/queue/WfspParser.java` (FR-010, D-13): StAX with the
  `adapter/rest/XmlSupport` secure settings (no DTD, no external entities); root `Properties`,
  `Property` with required `name`, optional `type`, text as value, empty element = empty value;
  file order; any other structure → failure with the fixed reason "unexpected format; the
  unsupported INUBIT interface may have changed" (never the parser message) — failing tests first
  in `adapter/cli/queue/WfspParserTest.java` (types none/Boolean/Integer/Long/XmlDocument with
  escaped XML, DOCTYPE refused, wrong root, `Property` without `name`, 10,000 properties)
- [ ] T019 [US1] `adapter/cli/queue/QueueEntryHelperClassifier.java` (FR-013, D-5): frames + exit code
  → `ToolError` or part results per the D-5 table (`TLS_ERROR`, `UNREACHABLE`, `AUTH_FAILED` with
  the 9.x internal-user-management hint, `FORBIDDEN`, part `NotFound` on `@Start@Unable to
  download` / `MissingWorkflowWithName`, connection failure in a part fails the call, other part
  error → `Failed` with a scrubbed excerpt ≤ 200, `api missing` → `CLI_UNAVAILABLE` naming
  `defaults.cliClients."M.m"`, no `end` / over 1 MiB / exit ≠ frames → `UNEXPECTED_RESPONSE` "the
  unsupported INUBIT interface may have changed") — fixture texts
  `src/test/resources/fixtures/queue-entry/v8_1/{login_failure,tls_failure,not_found_file,api_missing,garbage_frames}.frames|.exit`
  and `v9_0/not_found_file.*`, `v9_0/missing_workflow.*` (spike texts, `.origin`
  `reconstructed spike 2026-11-10`) with `.README`; failing tests first in
  `adapter/cli/queue/QueueEntryHelperClassifierTest.java`; leak check (depends on T017)
- [ ] T020 [US1] `adapter/cli/queue/HelperInstallation.java` and `HelperTrustStore.java` (D-2, D-3,
  D-7, D-8): private temp dir `inubit-mcp-queue-<profile>-<pid>-<random>` via
  `PrivateFileSystem.createTempDirectory` with `helper/`, `vendor/`, `out/`, registered with
  `CliResources`, deleted on every exit path; helper resource copied (missing → `INTERNAL`);
  launch command with the D-3 JVM options, `-Djava.io.tmpdir=<tmp>/vendor`,
  `-Dsecretkey.path=<home>/conf`, working dir = client home, env from `StartCliEnvironment`;
  preconditions → `CLI_UNAVAILABLE` (no home, no `<home>/lib/ibis.jar`, no
  `<javaHome>/bin/java[.exe]`, `:`/`;` class path separator in home or tmp path, containment
  unavailable, no private file support); trust store `<tmp>/trust.p12` with only the verified
  leaf and a one-time 32-byte Base64 password — failing tests first in
  `adapter/cli/queue/HelperInstallationTest.java` (argument arrays POSIX and simulated Windows,
  each precondition), `HelperTrustStoreTest.java` (one entry, loads with the password, file
  owner-only); leak check
- [ ] T021 [US1] `adapter/cli/queue/QueueEntryVariant.java` (`forVersion(MajorMinor)`: 8.1 →
  `SOAP_81`, 9.0 → `REST_90`, else `UNSUPPORTED_VERSION`; `objectType(workflow)` =
  `queue_manager@@@<workflow>@@@error`) and `adapter/cli/queue/CliQueueEntryAdapter.java`
  implementing `QueueEntryContentPort` (FR-006, FR-012, FR-014…FR-016, D-3…D-8, D-14):
  `CliRunner.select(server, version)`, `checkLaunch`, deadline = `cliTimeout` shared by
  `acquireForHelper`, `TlsPeer.handshake` (`https` only) and `ProcessRun`; `CredentialGuard`
  permit accepted/rejected from the login frame; credentials from the node's `NodeSecrets`
  (`current`) like its REST reads — unavailable → that error, nothing launched; request on stdin; files read only if ≤ 10 MiB
  (else `TooLarge`); `WfspParser`; DEBUG log of variant, client home and JDK only — failing tests
  first in `adapter/cli/queue/CliQueueEntryAdapterTest.java` (`FakeProcessLauncher.onLaunch` reads
  the request and writes the case files; success; variables-only; `.wfsp` > 10 MiB → `TooLarge`;
  `hanging()` → `TIMEOUT`, no partial content, temp dir gone; TLS mismatch → nothing launched;
  slot wait + process share one deadline; login failure → guard back-off; leak check on
  argv/env/logs) (depends on T006–T008, T017–T020)
- [ ] T022 [US1] Port plumbing: `domain/port/Gateway.java` `+ default QueueEntryContentPort
  queueEntryContent()` throwing `CLI_UNAVAILABLE` with the node (the `artifacts()`/`imports()`/
  `tags()` pattern, `Gateway.java:60-92`) and `domain/port/GatewayFactory.java` `+ default
  QueueEntryContentPort queueEntryContent(NodeId server)` = `forServer(server).queueEntryContent()`
  (pattern `GatewayFactory.java:86-97`), so existing gateway and factory fakes compile unchanged;
  `adapter/PortSet.java` `+ queueEntryContent` component plus a compatibility constructor with the
  previous seven components that fills in a refusing port, so `StaleReportingPortsTest`,
  `AdapterGatewayFactoryTest`, `CapabilityExtensionTest` compile unchanged;
  `adapter/V81PortSet.java` (builds `CliQueueEntryAdapter` with the node's credentials, guard,
  `CliRunner`, `ProcessRun`, limiter, `PrivateFileSystem`), `adapter/cli/v61/V61PortSet.java`
  (compatibility constructor), `adapter/RefusingPorts.java` (refuses), `adapter/StaleReportingPorts.java`
  (forwards), `adapter/V81Gateway.java` and `adapter/AdapterGatewayFactory.java` (override),
  `Wiring.java` (limiter and `PrivateFileSystem` passed in) — failing tests first in `adapter/AdapterGatewayFactoryTest.java` (8.1 and 9.0 slots get the CLI
  adapter, 6.1 refuses), `adapter/StaleReportingPortsTest.java`, `adapter/RefusingPortsTest.java`

### Service and tool (plan stage 8)

- [ ] T023 [US1] `application/QueueEntryService.java`, part 1 (FR-001, FR-002 refusal, FR-005,
  FR-012, FR-013, D-9): input checks (`processId` `^[1-9][0-9]{0,18}$`; `fileName` "1–255
  characters, no `/`, `\`, NUL or other control characters"; `maxChars` "integer 1–100,000,
  default 20,000"); `TargetResolver.resolveSingleServer`; opt-in → `NOT_CONFIGURED` **before** the
  gate and any request; `gate.admitWithDetection("read_queue_entry", …, QUEUE_ENTRY_READ, READ,
  {REST, CLI})`; `queueEntries`; candidate rules (one ERROR row among others read; none →
  `NOT_FOUND`; no ERROR → `INVALID_INPUT` with states; several → `INVALID_INPUT` listing ≤ 20
  candidates with file name, workflow, module, time; `fileName` given → that row, not ERROR →
  `INVALID_INPUT`, unknown → `NOT_FOUND`; "only N of M rows were read" when truncated); data
  part not requested when `fileSize` > 10 MiB; port call with the admitted version; `NotFound` →
  re-read rows: gone → `NOT_FOUND` "left the Queue Manager", still there → partial; credentials
  not yet available (spec edge case) → the same error as the node's other reads (naming the
  missing secret, from the queueLog lookup's credential resolution), nothing launched — failing
  tests first in `application/QueueEntryServiceTest.java` (fake ports; WireMock-free; US1-7,
  US2-2 zero port calls, re-read both ways, missing credentials → no content-port call); texts per contracts/mcp-tools-delta.md "Errors"; leak check
- [ ] T024 [US1] `QueueEntryService`, part 2 (FR-008…FR-011, FR-018, D-10…D-12, D-16): result
  `domain/model/QueueEntryResult.java` per contracts "Output"; classify; scrub with the injected
  `UnaryOperator<String> knownSecrets` on the full text, then `SecretTextMasker`, then bound;
  variables: credential-named → `${secret:variables.<name>}` (`masked: true`), others classified
  and masked, `system` from `SystemVariables`; bounds "at most 200 variables (system variables
  included), 2,000 characters per value and 40,000 characters for all values together", names cut
  at 200, values past the budget `""` with `truncated: true`, text cut at `maxChars` without
  splitting a surrogate pair; fixed warning; one INFO line (node, process ID, file name, sizes,
  outcome) — failing tests first in `application/QueueEntryServiceTest.java` (US1-1…6, US1-8,
  US1-9, text 20,001 chars, binary ZIP, empty, Latin-1, known credential split across the cut
  position is still scrubbed) and `application/QueueEntryLoggingTest.java` (captured logback output
  of a full call contains no returned value, no password; exactly one INFO line) (depends on
  T009–T011, T023)
- [ ] T025 [US1] `mcp/tools/ReadQueueEntryTool.java` (`ToolHints.readOnly("Read a Queue Manager
  entry", true, true)`, description verbatim from contracts/mcp-tools-delta.md),
  `src/main/resources/schemas/read_queue_entry.input.json` and `.output.json` (contracts), service
  construction in `Wiring.java` with `SecretScrubber::scrub` (registration/announcement follows in
  T030) — failing tests first in `mcp/tools/ReadQueueEntryToolTest.java` (schema rejects a group
  id, bad `processId`, `fileName` with `/`, `\u0000`, 256 chars, `maxChars` 0 and 100001;
  output validates against the output schema; description contains "unsupported", "undocumented",
  "without vendor support", "any INUBIT update", "diagnosis only", "business data")

### Fixtures and 8.1 contract (plan stage 9)

- [ ] T026 [US1] Synthetic content cases in `src/test/resources/fixtures/queue-entry/v8_1/`
  (`binary_zip`, `utf16_text`, `latin1_text`, `secrets_everywhere`, `many_variables` (250),
  `long_values`, `missing_variables`, `cutoff_tag`, `too_large`, each `.data`/`.wfsp`/`.frames`/
  `.exit`/`.rows.json` as needed, `.origin` `reconstructed`, `.README`), `fixtures/queue-entry/README.md`,
  `tools/QueueEntryFixtureCatalogueTest.java` (every case has `.origin`; every file belongs to a
  case; 9.0 `SUPPORTED` in the matrix ⇔ `v9_0/error_entry.origin` starts with `recorded`) and
  `adapter/cli/queue/QueueEntryV81ContractTest.java` running adapter + service over each case
  (FR-009…FR-012, SC-003)
- [ ] T027 [US1] **[OWNER]** 8.1 recording (FR-019, D-18): the agent neutralizes the spike download
  (data stream + `.wfsp` of the 8.1.17 ERROR entry, kept outside the repository) in a private
  directory: `tools/synthesize_names.py collect … --out <private>/.synthetic-map`,
  `tools/neutralize.py --map <private>/.synthetic-map` plus the private `neutralize-map`, then
  replaces every business value by fictitious content of the same shape (structure, escaping,
  variable count, order, types and system names kept); `rows.json` `fileSize` adjusted;
  `NoCustomerIdentifiersTest` with the private lists and `python3 -I tools/check-identifiers.py
  --all` pass. **The owner reviews the diff of `fixtures/queue-entry/v8_1/error_entry.*` before
  it is committed.** `.origin` `recorded 2026-11-10 8.1.17 neutralized` (frames stay
  `reconstructed` until T035); `QueueEntryV81ContractTest` gets the `error_entry` case (US1-1:
  metadata, text, all variables with system flags, masking). Done when the owner approved the
  diff and the build is green

**Checkpoint**: build green; US1 works end to end on the service level; code review phase 3.

## Phase 4: User Story 2 – Opt in deliberately (P1)

**Goal**: the tool exists only for opted-in groups; the configuration view and the docs make the
unsupported status explicit (FR-002…FR-004, FR-017, SC-002, SC-005).
**Independent test**: profile without opt-in → tool list, `list_nodes`, startup summary and
`--check-config` byte-for-byte as before; opt-in on `test` only → tool announced, call for
`prod/node1` → `NOT_CONFIGURED`, zero requests (WireMock).

- [ ] T028 [P] [US2] `config/ConfigValidator.java` `checkQueueEntryReadOptIn`: warning on a
  `production: true` group "unsupported.queueEntryRead is enabled on production {group} %s: Queue
  Manager entries are read through an undocumented INUBIT interface without vendor support"
  (`termWarning`); `config/ConfigSummary.java`: **only if at least one group opts in**, every
  group line ends with `, queueEntryRead: off` / `, queueEntryRead: enabled (unsupported)`;
  (the `cliOnly` filter is already in T003) — failing tests first in
  `config/ConfigValidatorTest.java`, `config/ConfigSummaryTest.java` (existing golden texts
  unchanged byte-for-byte; new golden text with an opt-in), `MainTest.java` (`--check-config` with and without opt-in; startup log
  "Configuration: …" warning) (US2-3, US2-5, FR-017)
- [ ] T029 [P] [US2] `docs/setup.md`: subsection `### Unsupported functions` after `### Workbench
  archives` with the table, the YAML example and the FR-004 text **verbatim** from
  contracts/configuration-delta.md; section 6 one additional summary example with an opt-in
  (existing examples unchanged); overview bullet listing group-only settings — failing test first
  `config/QueueEntryDocsTest.java` (the bold sentences of the FR-004 text are present; setup
  example parses with `ConfigLoader`) (US2-4)
- [ ] T030 [US2] Announcement and `list_nodes` (FR-002, D-6, D-15): `Wiring.java`
  `anyQueueEntryReadNode()` = some node with `queueEntryRead` && `possiblyCapable(node,
  QUEUE_ENTRY_READ)` && `clientSelection.anyCandidate(node)`; register `ReadQueueEntryTool` only
  then; `allowed()` adds `QUEUE_ENTRY_READ` only with opt-in and client — failing tests first in
  `ToolAnnouncementTest.java` (no opt-in → exact 0.8.0 list; opt-in + `AUTO` → announced; opt-in
  only on `V6_1` or `V9_0` (fallback) or without client home → not announced), `MainTest.java`
  (exact tool lists), `mcp/tools/ListNodesToolTest.java` (`queue_entry_read` only for opted-in
  nodes; schema validates), `ReadQueueEntryWiringTest.java` (call for a non-opted-in group →
  `NOT_CONFIGURED` naming `unsupported.queueEntryRead`, zero WireMock requests, no launch) (US2-1,
  US2-2, SC-002, SC-005)

**Checkpoint**: build green; SC-002 comparison recorded in `reviews/implementation.md`; code
review phase 4.

## Phase 5: User Story 3 – Right implementation per INUBIT version (P2)

**Goal**: 8.1 and 9.0 nodes use their own variant and client; other or undetectable versions are
refused before any content request (FR-006, FR-019, US3-1…5).
**Independent test**: one profile with an 8.1 node, a 9.0 node (fake clients per version, JDK
17/21 paths) and a 6.1 node; `FakeProcessLauncher` records the launched JDK, class path and
`variant`.

- [ ] T031 [US3] Version selection end to end (US3-1…5): failing tests first in
  `adapter/cli/queue/QueueEntryVersionSelectionTest.java` and `application/QueueEntryServiceVersionTest.java`
  — 8.1 node → `SOAP_81`, the `defaults.cliClients."8.1"` home and JDK, servlet URL; 9.0 node
  (matrix opened in the test via a test declaration) → `REST_90`, the 9.0 home and JDK, base URL;
  6.1 / 9.1 → `UNSUPPORTED_VERSION` naming version and supported lines, no launch; undetectable
  `AUTO` node → the detection's own code (T004), no launch; no client home for 9.0 →
  `CLI_UNAVAILABLE` naming `defaults.cliClients."9.0"`; an 8.1 client chosen for a 9.0 node
  (`api missing`) → `CLI_UNAVAILABLE`; fixes in `CliQueueEntryAdapter`/`QueueEntryService` only if
  a test fails
- [ ] T032 [US3] 9.0 contract in the fallback state (FR-019): `adapter/cli/queue/QueueEntryV90ContractTest.java`
  asserts, while `v9_0/error_entry` is absent, that a 9.0 node is refused "not yet verified" with
  no launch, and runs the `REST_90` path against the 9.0 spike texts (`not_found_file`,
  `missing_workflow` → partial / `NOT_FOUND` after re-read); `QueueEntryFixtureCatalogueTest`
  covers `v9_0/`

**Checkpoint**: build green; code review phase 5.

## Phase 6: Live check and recordings (gates the feature; FR-019, SC-004)

- [ ] T033 Live test code (offline part): `live/QueueEntryLiveTest.java` (`@Tag("live")`;
  `INUBIT_LIVE_NODE`, `INUBIT_LIVE_QUEUE_ENTRY=<processId>[/<fileName>]`, skipped without them;
  production refused by `LiveTarget`; rows before/after equal in state, `startTime`, `fileSize`,
  `fileName`; captured log contains no returned value; optional `INUBIT_LIVE_RECORD_DIR` writes
  raw `.data`, `.wfsp`, frames, exit code owner-only; with the record dir it also runs one cheap
  negative case directly on the adapter: the existing entry's workflow with the fictitious file
  name `Module-0000(0)@0@00000000` (login succeeds, so no `AUTH_FAILED` back-off; read-only),
  recording the INUBIT "not found" frames; one `[live] read_queue_entry …` line) and
  `docs/live-tests.md` section "Queue entry read (feature 020)" plus a row in the tests table —
  failing tests first in `live/LiveTargetTest.java`-style offline checks of the env parsing and of
  the record-dir permissions (`live/QueueEntryLiveTestSupportTest.java`)
- [ ] T034 **[LIVE] [OWNER]** 8.1 live check (SC-004, quickstart "Live check" steps 1–4): the owner
  names an existing ERROR entry on a non-production 8.1 test server (or approves the agent's
  proposal of one); run `QueueEntryLiveTest` with `INUBIT_LIVE_RECORD_DIR` (records the entry and
  the negative case "non-existent file name of an existing workflow"); result recorded in the
  quickstart validation record (fictitious ids only)
- [ ] T035 **[OWNER]** Neutralize the 8.1 helper frames from T034 as in T027 and replace
  `fixtures/queue-entry/v8_1/error_entry.frames`/`.exit` and the reconstructed
  `fixtures/queue-entry/v8_1/not_found_file.*` by the recorded negative case (`.origin` updated
  to `recorded … neutralized`); `QueueEntryHelperClassifierTest` and the contract test stay green
  with the recorded marker (or the marker rule is corrected first); the owner reviews the diff
  before the commit (depends on T027, T034)
- [ ] T036 **[OWNER]** 9.0 ERROR entry (FR-019, D-20): the agent proposes one concrete trigger
  (which non-production 9.0 test server, which test workflow and Throw module, the fictitious
  payload incl. a variable `apiToken`, how the entry is created and later removed) and **waits for
  the owner's explicit approval of exactly that action**; then the owner or, after approval, the
  agent creates the entry. If the owner declines or no 9.0 test server exists → skip T037/T038
  and do T039
- [ ] T037 **[LIVE] [OWNER]** 9.0 live check and recording, **prepared, not committed** (depends
  on T036): run `QueueEntryLiveTest` against the 9.0 entry with `INUBIT_LIVE_RECORD_DIR` (entry
  plus the negative case); with the 9.0 node opened only through a test declaration of the matrix,
  since production 9.0 stays refused until T038; neutralize as in T027 in the private directory
  into the future `fixtures/queue-entry/v9_0/error_entry.*` and `not_found_file.*` (`.origin`
  `recorded <date> 9.0.x neutralized`); the owner reviews the neutralized files; afterwards the
  owner removes the test entry in the Workbench (the tool never does); result in the quickstart
  validation record
- [ ] T038 [US3] Enable 9.0 in **one commit** (depends on T037): the reviewed recording files of
  T037 under `src/test/resources/fixtures/queue-entry/v9_0/` **and** `domain/model/CapabilityMatrix.java`
  9.0 `QUEUE_ENTRY_READ` → `SUPPORTED` (note removed) together, because
  `QueueEntryFixtureCatalogueTest` requires "9.0 `SUPPORTED` ⇔ recorded `v9_0/error_entry.origin`"
  in every commit; updated first: `QueueEntryV90ContractTest` (recorded `error_entry`, US3-2,
  SC-001), `ToolAnnouncementTest` (`V9_0` pin now announces), `CapabilityMatrixTest`,
  `QueueEntryFixtureCatalogueTest`
- [ ] T039 **[OWNER]** FR-019 fallback (only if T036 is declined or impossible): record the owner's
  decision and date in `quickstart.md` (validation record) and `reviews/implementation.md`; 9.0 stays
  refused "not yet verified"; T040/T041 state it in docs and CHANGELOG; enabling 9.0 later is a
  follow-up change

## Phase 7: Polish

- [ ] T040 [P] `docs/tools.md`: section `## read_queue_entry` (short warning first, link to setup
  "Unsupported functions", inputs, output with variants, errors table, candidate rules, masking
  rules and the residual risk, bounds), capability table row, "Tool announcement" rule,
  `list_nodes` capability name; `README.md` tool list — `QueueEntryDocsTest` extended first (short
  form present in `docs/tools.md`; 9.0 state matches the matrix: supported after T038, "not yet
  verified" under T039)
- [ ] T041 [P] `CHANGELOG.md` `[Unreleased]`: new tool `read_queue_entry` (unsupported INUBIT
  interface, opt-in per group, diagnosis only), `unsupported.queueEntryRead`, `--check-config`
  line only with an opt-in, 9.0 state (verified or "not yet verified"), internal `ProcessRun`
  extraction without behaviour change
- [ ] T042 Full verification: `mvn -B clean verify` and `python3 -I -m unittest discover -s
  tools/tests -v` green; `python3 -I tools/check-identifiers.py --all` and
  `NoCustomerIdentifiersTest` with the private lists green; `unzip -l` of the JAR shows exactly one
  helper class under `META-INF/inubit-mcp/queue-entry-helper/`; quickstart offline table checked
  row by row; final code review over `git diff main...HEAD`; findings in
  `reviews/implementation.md`

## Dependencies

- Phase 2 blocks everything: T002, T005, T007, T008 in parallel; T003 after T002 (cliOnly filter
  reads the opt-in); T004 after T003; T006 before T021.
- US1: T009 ∥ T010 → T011; T012 needs T005; T013 → T014 → T015 → T016; T017 ∥ T018; T017 → T019;
  T020 needs T006; T021 needs T006–T008, T017–T020; T022 needs T021, T012; T023 needs T002, T004,
  T022; T024 needs T009–T011, T023; T025 needs T024; T026 needs T021, T024; T027 needs T026.
- US2: T028 needs T002 and T003; T029 needs T002; T030 needs T025 and T003.
- US3: T031 needs T030; T032 needs T026, T031.
- Phase 6: T033 needs T030; T034 needs T033 → T035 (also T027); T036 → T037 → T038; T039 only
  instead of T036–T038.
- Phase 7: T040, T041 need T038 or T039; T042 needs all others.
- The feature is complete only when T027 and T034/T035 are done and either T038 or T039 (FR-019,
  SC-004).

## Parallel opportunities

T002/T005/T007/T008 (then T003); T009/T010/T012/T013/T017/T018 once Phase 2 is green; T028/T029 alongside
US1; T040/T041. Builds always sequential.

## Implementation strategy

MVP = Phases 2–3 with T030 (announcement): an opted-in 8.1 node returns the content of an ERROR
entry. Then the configuration view and docs of US2, the version proof of US3, the live checks and
the 9.0 decision (recording or fallback), docs and the final verification. Each phase ends green
and reviewed; owner and live gates are reported as soon as they are reached, not batched at the end.
