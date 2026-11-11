# Tasks: Download Server Log Files (trace.log, webservice.log)

**Input**: Design documents from `specs/021-server-log-download/` (spec, plan, research D-1…D-25,
data-model, contracts/mcp-tools-delta.md, contracts/configuration-delta.md, quickstart, reviews/)

**Tests**: mandatory (constitution Principle III, TDD): each implementation task starts with its
failing tests in the same task (named in the task), observed to fail, and is done when they pass
and the build is green. No offline test contacts INUBIT: StartCLI runs are replayed by
`ScriptedProcessLauncher` / `FakeProcessLauncher` (`onLaunch` writes the fixture `.log`), the
helper runs in-process against fake vendor classes under `src/test/java/com/inubit/ibis/…` and
once in a child JVM, version detection against WireMock; content behaviour is tested against
`src/test/resources/fixtures/server-log/`.

**Build**: `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-021-tmp` (tmpdir
outside the checkout; never two builds in parallel); Python: `python3 -I -m unittest discover -s
tools/tests`. Paths below are relative to `src/main/java/de/dadecker/inubit/mcp/` (main) and
`src/test/java/de/dadecker/inubit/mcp/` (test) unless they start with `src/`, `docs/`, `specs/`,
`tools/`, `pom.xml`, `README.md`, `SECURITY.md` or `CHANGELOG.md`.

**Secrets (SC-003, Constitution II)**: every task that adds a message, warning, hint, log line,
excerpt, audit record, frame, request or argv asserts with `testing/TestSecrets` that a configured
password, the one-time trust-store password, a credential-named value of the log content and the
`contains` text never appear in it — named below as "leak check".

**Customer-neutral (FR-018)**: only fictitious names (`acme`, `globex`, `test`, `prod`, `legacy`,
`node1`, `node2`, `*.example.test`) in code, tests, fixtures and docs. Fixture log content is
always fictitious (FR-017): timestamps, levels, `acme` classes, `node1`, `*.example.test`. The
pre-commit hook blocks customer identifiers; never commit with `--no-verify`.

**Version state during implementation (FR-017, D-6, D-22)**: `CapabilityMatrix.production()`
declares both capabilities on 8.1 **and** 9.0 as `UNSUPPORTED` with the note "not yet verified"
(T009) until the commit that adds the recorded fixture of that (version, log) flips it (T033,
T046, T051, T052). Offline tests that need an admitted 8.1 or 9.0 node use a test declaration of
the matrix (the non-production `CapabilityMatrix` constructor, as 020's
`adapter/cli/queue/QueueEntryVersionProfile.java`), never the production declaration.

**Gates**:
- **[OWNER]**: needs the owner's decision or review (e.g. of a neutralized fixture diff) before it
  is done.
- **[LIVE]**: runs against a real INUBIT server: **read-only, non-production test servers only**,
  executed by the operator, or by the agent **only after the operator approved that concrete
  action in the chat** (server, command, private output folder). Raw outputs and downloaded log
  files stay in a private directory outside every repository; only neutralized fixtures with
  **fictitious log content and names** enter the repository (FR-017, FR-018).
- **[BLOCKED: Tnnn]**: cannot start before task Tnnn (a recording) is done. All other tasks run
  offline and do not wait for recordings.
The implementer stops at a gate and reports what is needed.

**Reviews**: each task goes through the implementer → reviewer loop
(`d4ld4r-speckit-duo:implement`); after each phase a code review of the phase diff; findings and
decisions go to `specs/021-server-log-download/reviews/implementation.md`.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [X] T001 Baseline: `mvn -o -q clean verify` with the tmpdir above and the Python tests are green
  on the branch (0.9.1 suite); create `specs/021-server-log-download/reviews/implementation.md`
  and note there the test count (surefire + failsafe) and the exact 0.9.1 tool list of
  `MainTest` as the SC-002 reference, plus the owner decisions in force: timeout =
  `cliExportTimeout` (built-in 120 s, `config/Defaults.java` `BUILTIN_CLI_EXPORT_TIMEOUT`; spec
  FR-013, D-15) and plan open question 2 (additive `trace_log_read` in `list_nodes` and the 6.1
  `cliOnly` summary) at its default unless the owner overrides
- [X] T002 [P] Fixture root (D-21): `src/test/resources/fixtures/server-log/README.md` describing
  the layout `{v8_1,v9_0}/{trace,webservice}/<case>.<ext>` (trace: `.stdout`, `.stderr`, `.exit`,
  `.log`, `.origin`; webservice: `.frames`, `.exit`, `.log`, `.origin`) and
  `excerpt/<case>.{log,README}`; the `.origin` formats "`recorded <date> <version> neutralized`" /
  "`reconstructed <source>`"; the rule "log content is always fictitious; recorded sizes and MB
  values are adjusted to the fictitious content"; the neutralization steps of D-21 (no case files
  yet)

## Phase 2: Foundational — refactor, configuration, capabilities, excerpt, local files (plan stages 1, 3–6; blocks every story)

### Stage 1: behaviour-preserving refactor (D-3, D-7)

- [X] T003 Rename the helper (D-3): `pom.xml` execution `compile-queue-entry-helper` →
  `compile-client-helper`, `outputDirectory`
  `${project.build.outputDirectory}/META-INF/inubit-mcp/client-helper` (`<release>17</release>`
  unchanged); `git mv src/helper/java/de/dadecker/inubit/mcp/helper/QueueEntryHelper.java
  src/helper/java/de/dadecker/inubit/mcp/helper/ClientHelper.java` (class rename only; request
  header `qe=1` and frame prefix `#QE1:` unchanged; no `op` key yet);
  `adapter/cli/queue/HelperInstallation.java` resource path and `MAIN_CLASS`; tests renamed with
  `git mv` in `adapter/cli/queue/`: `QueueEntryHelperTest` → `ClientHelperTest`,
  `QueueEntryHelperProcessTest` → `ClientHelperProcessTest`, `QueueEntryHelperPackagingTest` →
  `ClientHelperPackagingTest`, `QueueEntryHelperPackagingIT` → `ClientHelperPackagingIT`;
  `architecture/PackageBoundaryTest.java` helper rule follows the rename (the helper references
  only `java/*`). Failing test first: `ClientHelperPackagingTest` asserts exactly one class file
  `META-INF/inubit-mcp/client-helper/de/dadecker/inubit/mcp/helper/ClientHelper.class`, major
  version 61, and no `META-INF/inubit-mcp/queue-entry-helper/` entry. Done when every 020 test is
  green and `git diff --stat src/test/resources/fixtures/queue-entry` is empty
- [X] T004 Move the shared helper adapter classes (D-3; after T003): new package
  `adapter/cli/helper/` (`package-info.java`) receives `HelperInstallation.java`,
  `HelperTrustStore.java`, `HelperRequest.java`, `HelperFrames.java` and
  `QueueEntryVariant.java` renamed `HelperVariant.java` (`SOAP_81`, `REST_90`, `forVersion`);
  tests moved with them to `adapter/cli/helper/` (`HelperInstallationTest`,
  `HelperTrustStoreTest`, `HelperRequestTest`, `HelperFramesTest`, `QueueEntryVariantTest` →
  `HelperVariantTest`, `ClientHelperTest`, `ClientHelperProcessTest`,
  `ClientHelperPackagingTest`, `ClientHelperPackagingIT`); imports updated in
  `adapter/cli/queue/CliQueueEntryAdapter.java`, `adapter/cli/queue/QueueEntryHelperClassifier.java`,
  `Wiring.java`, `adapter/cli/queue/QueueEntryVersionProfile.java` (test) and the other 020
  tests. Failing test first in `architecture/PackageBoundaryTest.java`: `adapter/cli/queue` and
  `adapter/cli/log` may depend on `adapter/cli/helper` but not on each other. No behaviour change;
  `QueueEntryV81ContractTest`, `QueueEntryV90ContractTest`, `QueueEntryVersionSelectionTest`
  green; `fixtures/queue-entry/` byte-identical
- [X] T005 Extract `adapter/cli/helper/HelperFailures.java` (D-3, D-14; after T004): the shared
  mapping of launch failure, timeout, login (`AUTH_FAILED` with the shared `CredentialGuard`
  back-off), TLS, network, HTTP 403 (`FORBIDDEN`), `api missing` (`CLI_UNAVAILABLE`) and protocol
  problems (no `end`, unknown frame, output over 1 MiB, exit code ≠ frames →
  `UNEXPECTED_RESPONSE`) out of `adapter/cli/queue/QueueEntryHelperClassifier.java`, which
  delegates; the operation-specific subject of a message is a parameter, so the queue texts stay
  byte-identical. Failing tests first in `adapter/cli/helper/HelperFailuresTest.java`
  (characterization of every shared case with the queue subject and with a log subject);
  `adapter/cli/queue/QueueEntryHelperClassifierTest.java` unchanged and green; leak check
- [X] T006 [P] Extract `application/OwnerOnlyFolders.java` from `application/ArchiveFolderWriter.java`
  (`createFolder`, around line 300; D-7): "creates a folder and its missing parents `rwx------`,
  never changes existing permissions; refuses a concurrently created symbolic link";
  `ArchiveFolderWriter` delegates (behaviour unchanged). Failing tests first in
  `application/OwnerOnlyFoldersTest.java` (new parents owner-only on POSIX, existing folder's
  permissions untouched, symlink created between check and creation → refused);
  `application/ArchiveFolderWriterTest.java` and `application/ArchiveExportServiceTest.java`
  unchanged and green
- [X] T007 [P] `application/TargetFolderRules.java` `+ check(String targetFolder, String
  cloudSubject)` (D-7 step 6); `check(String)` delegates with the archive wording unchanged; the
  log caller passes "the log file (with clear-text content) will be synchronized to the cloud".
  Failing tests first in `application/TargetFolderRulesTest.java` (archive wording byte-identical;
  log wording in the cloud-sync warning; every other rule — absolute path, no network path, not
  inside the workspace, the server's directories or a git working tree — identical for both
  callers)

**Checkpoint (stage 1)**: build green; `git diff --stat main -- src/test/resources/fixtures/queue-entry`
empty; code review of T003–T007 recorded in `reviews/implementation.md`.

### Configuration, capabilities, ports (plan stages 3–4)

- [X] T008 [P] Opt-in binding (FR-003, D-17): `config/UnsupportedConfig.java` → `record
  UnsupportedConfig(Optional<Boolean> queueEntryRead, Optional<Boolean> serverLogRead)` (`EMPTY`
  unchanged in meaning); `config/Defaults.java` `+ BUILTIN_SERVER_LOG_READ = false`;
  `config/ConfigLoader.java` `requireStrictUnsupportedOptIn` checks `queueEntryRead` and
  `serverLogRead` ("present, non-null, non-boolean → `Invalid value at
  groups[i].unsupported.serverLogRead in <source>: expected true or false`", never echoing the
  value); `config/EffectiveNodeConfig.java` `+ boolean serverLogRead` "from the group only;
  passed through `withTimeout`, `withCli`, `entryProbe` and both constructors"; the secondary
  constructor keeps its signature and passes `false`; direct test callers
  (`adapter/TestNodeConfig.java`, `config/EffectiveNodeConfigCliForTest.java`, …) compile
  unchanged or get the extra `false`. Failing tests first in `config/ConfigLoaderTest.java`
  (`true` / `false` / empty / `null` load, `null`/empty = `false`; `"true"`, `1`, `0`, `yes`, a
  list, a mapping → the error above without the value; `unsupported.traceRead` → `Unknown key
  'traceRead' at groups[0].unsupported in <source>`; `unsupported` on a node → `Unknown key
  'unsupported' at groups[0].nodes[0] in <source>`, in `defaults` → unknown key;
  `queueEntryRead: true` with `serverLogRead: false` and vice versa independent) and
  `config/EffectiveNodeConfigTest.java` (value survives every copy method); leak check on the
  error texts
- [X] T009 Capabilities and matrix (FR-002, FR-017, D-6; after T008): `domain/model/Capability.java`
  `+ TRACE_LOG_READ` (kind `READ`, channels `{CLI}`, label "downloading the trace log") and
  `+ WEBSERVICE_LOG_READ` (kind `READ`, channels `{CLI}`, label "downloading the web service
  log"); `domain/model/CapabilityMatrix.java` `production()`: 8.1 and 9.0 both capabilities
  `UNSUPPORTED` with the note "not yet verified" (flipped only by T033, T046, T051, T052); 6.1
  unlisted (= `UNSUPPORTED`); unknown-version map both `UNSUPPORTED`;
  `application/VersionGate.java` `supportedLines` (8.1, 9.0 for both) and `cliOnlyStep` (both:
  "download the log in the Workbench (Administration, Server Log)"); `application/NodeOverview.java`
  `name(TRACE_LOG_READ) = "trace_log_read"`, `name(WEBSERVICE_LOG_READ) = "webservice_log_read"`;
  `src/main/resources/schemas/list_nodes.output.json` enum `+ "trace_log_read",
  "webservice_log_read"`; `config/ConfigSummary.java` `cliOnly`: `TRACE_LOG_READ` listed like
  every other capability ("downloading the trace log" refused for every 6.1 setting),
  `WEBSERVICE_LOG_READ` only for groups with `serverLogRead` (020 filter rule). Failing tests
  first: `domain/model/CapabilityMatrixTest.java` (8.1 and 9.0 unsupported with note, 9.1 and
  unknown unsupported, other capabilities unchanged), `application/VersionGateTest.java` (8.1 /
  9.0 refusal "… is not yet verified for INUBIT 8.1" / "9.0"; 6.1.3 and 9.1 →
  `UNSUPPORTED_VERSION` naming the version and "supported: 8.1, 9.0"),
  `application/NodeOverviewTest.java`, `config/ConfigSummaryTest.java` and `MainTest.java`
  (6.1 golden texts change by exactly the additive name "downloading the trace log"; no web
  service name without an opt-in; a 6.1 group with `serverLogRead: true` lists "downloading the
  web service log" as refused); update the `docs/setup.md` section 6 summary examples to the new
  golden texts (configuration-delta "Capability lists")
- [X] T010 [P] Domain types (data-model "Domain"; D-19): `domain/model/ServerLogKind.java`
  (`TRACE("trace", "trace.log")`, `WEBSERVICE("webservice", "webservice.log")`; `wireName` = tool
  input, `fileName` "used only for messages and the helper constant check"; `fromWire` rejects
  anything else), `LogPresence.java` (`PRESENT`, `UNKNOWN`; "`UNKNOWN` only for a 0-byte answer
  of a channel that cannot tell missing from empty"), `ExcerptLine.java` (`long number`, `String
  text`, `boolean cut`; "`number` ≥ 1, absolute in the file"), `LogWindow.java` (`byte[] bytes`,
  `long linesBefore`, `boolean wholeFile`, `long fileSize`; "`bytes.length` ≤ 50 MiB; starts at
  a line start; `startLine()` = `linesBefore + 1`"), `Truncation.java` (`int droppedLines`, `int
  cutLines`; "`cutLines` ∈ {0, 1}"), `ServerLogBounds.java` (`WINDOW_BYTES = 52_428_800`,
  `DEFAULT_TAIL_LINES = 200`, `MAX_TAIL_LINES = 2_000`, `DEFAULT_MAX_CHARS = 20_000`,
  `MAX_MAX_CHARS = 100_000`, `MAX_CONTAINS_CHARS = 200`, `MAX_NAME_SUFFIX = 999`),
  `ServerLogResult.java` (`node`, `log`, `Optional<String> inubitNode` (webservice only), `Path
  file` (absolute), `long sizeBytes`, `Instant downloadedAt` (UTC, second precision, = file
  name), `Optional<LogExcerpt> excerpt` ("empty = withheld, D-11"), `List<String> hints`, `List<String> warnings`; `LogExcerpt` itself
  in T015). Failing tests first in `domain/model/ServerLogKindTest.java`,
  `domain/model/LogWindowTest.java`, `domain/model/TruncationTest.java`,
  `domain/model/ExcerptLineTest.java` (invariants above rejected with `IllegalArgumentException`)
- [ ] T011 Port plumbing (D-19, 020 D-17 pattern; after T010): `domain/port/ServerLogPort.java`
  (`DownloadedLog download(ServerLogKind kind, InubitVersion version, long deadlineNanos)`;
  "`deadlineNanos` is the call deadline as a `System.nanoTime()` value set by the service (D-15);
  whole-call failures as `ToolErrorException`; no file outside the private directory"), `domain/port/DownloadedLog.java`
  (record `Path file`, `long size`, `Optional<String> inubitNode`, `LogPresence presence`,
  `AutoCloseable` whose `close()` deletes the private directory; "`inubitNode` 1–128 chars of
  `[A-Za-z0-9_.:-]`"); `domain/port/Gateway.java` `+ default ServerLogPort serverLogs()` and
  `domain/port/GatewayFactory.java` `+ default ServerLogPort serverLogs(NodeId)`, both throwing
  `CLI_UNAVAILABLE`; `adapter/PortSet.java` component with a compatibility constructor;
  `adapter/V81PortSet.java`, `adapter/V81Gateway.java`, `adapter/AdapterGatewayFactory.java`
  pass it; `adapter/StaleReportingPorts.java` forwards `serverLogs` (as `queueEntryContent`);
  `adapter/RefusingPorts.java` overrides with its 6.1 refusal text. Failing tests first in
  `adapter/StaleReportingPortsTest.java` (forwarding), `adapter/RefusingPortsTest.java` (6.1
  refusal, nothing sent), `adapter/AdapterGatewayFactoryTest.java` (default without a
  configured adapter → `CLI_UNAVAILABLE`)
- [ ] T012 [P] `adapter/cli/CliResources.java` `+ LOG_PREFIX = "inubit-mcp-log-"`, `logPrefix()`,
  stale sweep includes it (D-7 step 2); `adapter/cli/CliConcurrencyLimiter.java` (D-15):
  javadoc `acquireForHelper` serves "helper and log downloads"; `+ acquireForHelper(server, wait,
  String budgetSetting)` whose slot-wait `TIMEOUT` next step names `budgetSetting` ("Retry later;
  check get_health; raise <budgetSetting> if the INUBIT server is just slow"); the existing
  two-argument `acquireForHelper` and `acquire` delegate with `cliTimeout` (020 texts
  byte-identical). Failing tests first in `adapter/cli/CliResourcesTest.java` (reservation with
  the log prefix creates `inubit-mcp-log-<profile>-<pid>-<random>` owner-only below
  `java.io.tmpdir`; the stale sweep removes a stale `inubit-mcp-log-*` directory of a dead pid and
  keeps one of the running process) and `adapter/cli/CliConcurrencyLimiterTest.java` (slot wait
  with `cliExportTimeout` → next step names `cliExportTimeout`, not `cliTimeout`; the existing
  helper and StartCLI texts unchanged)

### Excerpt and local files (plan stages 5–6; shared by both logs)

- [ ] T013 [P] `domain/model/SecretTextMasker.java` `+ String maskLines(String window,
  List<SecretSpan> known)`, part 1 (FR-010, D-11): the same rules 1–7 as `mask(String)` on ranges
  of the original text, plus the known-credential spans as rule 0 (replacement `***`), merged by
  the existing order (earliest start, then longest, then lower rule number); **replacement keeps
  lines**: each merged region `[start, end)` becomes its replacement followed by every `\r` and
  `\n` of the region in original order, so the token stays on the first line, every line the
  region covers completely becomes empty, the unmasked text of a partly covered line stays, and
  line N of the masked window is line N of the window; both entry points share the range
  collection; `mask(String)` keeps its exact behaviour. Synthetic fixtures (each with `.README`):
  `src/test/resources/fixtures/server-log/excerpt/secrets_multiline.log` (a `<password>` element
  spanning lines 10–12, line 13 after it), `secrets_everywhere.log` (every rule 1–7 once,
  `Authorization: Basic …`, `Bearer …`, a profile password), `crlf.log`. Failing tests first in
  `domain/model/SecretTextMaskerLinesTest.java`: one test per rule; known span masked (US1-3);
  US1-8 (the value replaced by the mask token on line 10, line 11 empty, line 12 equals
  `</password>` followed by the text after it on that line, line 13 keeps number 13 and its
  text); the line count of the output equals the input for every
  fixture; CRLF preserved; `domain/model/SecretTextMaskerTest.java` unchanged and green
- [ ] T014 `SecretTextMasker.maskLines` part 2 (FR-010, D-11, US1-9; after T013): **end tag without
  start tag** (outer pass only, not inside CDATA): an end tag `</(prefix:)?name>` of a secret
  local name with no open start tag of that name before it in the window adds the range `[0,
  start of that end tag)` with the element's placeholder; the end tag itself stays; the lines up
  to it are masked line-preservingly as in T013. A secret JSON value (object or array) opened
  before the window is **not** detected (documented limit). Fixture
  `src/test/resources/fixtures/server-log/excerpt/secrets_window_start.{log,README}`. Failing
  tests first in `domain/model/SecretTextMaskerLinesTest.java`: US1-9 (every line from the window
  start up to the end tag masked: the placeholder on the first line, every following line
  before the end-tag line empty; the end-tag line keeps the end tag and the text after it, the
  part before the end tag masked; line count unchanged); orphan end tag inside CDATA ignored; a matched
  start/end pair not affected; the JSON limit pinned by a test that documents it
- [ ] T015 `domain/model/LogExcerpt.java` (+ `Selection` `TAIL` | `CONTAINS`) (FR-009 steps 2–5,
  D-10, D-12; after T010, T014): `LogExcerpt.build(LogWindow, int tailLines, Optional<String>
  contains, int maxChars, Function<String, List<SecretSpan>> known)`, pure; fields `ContentKind
  kind`, `Optional<BinarySignature> detected`, `Optional<Charset> charset`, window figures
  (`startLine`, `lines`, `bytes`, `wholeFile`), `Selection selection`, `OptionalLong
  matchingLines`, `List<ExcerptLine> lines`, `Optional<Truncation> truncated`, `List<String>
  hints`. Classification by `EntryContentClassifier.classify(windowBytes)` (020, unchanged):
  binary → no excerpt, `detected` if a signature matched, hint "Binary content is not returned";
  else UTF-8 if the whole window is valid UTF-8 (BOM dropped), else ISO-8859-1, named in
  `charset`; size 0 → `kind: empty`. Then mask the **whole** window (`maskLines`), then select.
  Line terminators (D-9): `\n`; a `\r` directly before it belongs to the terminator; a lone `\r`
  is part of the line; a final segment without terminator is a line; the empty segment after a
  final `\n` is not. Numbers `linesBefore + i + 1`. Without `contains`: the last `tailLines`
  lines. With `contains`: a line matches if it contains the text case-insensitively as plain text
  (`String.regionMatches(true, …)`, no pattern); `matchingLines` = all matches in the window; the
  last `tailLines` of them selected; no match → empty `lines`, hint "No line of the scanned window
  contains the search text". `maxChars` counts the UTF-16 chars of the selected line texts
  (numbers and JSON overhead not counted); over the limit whole lines are dropped from the oldest
  until the rest fits (`droppedLines`); if the newest line alone is longer than `maxChars` it is
  cut to `maxChars` chars without splitting a surrogate pair (`cutLines = 1`, its `cut: true`).
  Window hints: `wholeFile: false` → "Only the last 50 MiB were scanned; line numbers count the
  whole file"; a non-empty file whose window has no whole line → `kind: text`, `lines: 0`, hint
  "No whole line in the last 50 MiB of the log". Fixtures (each with `.README`) under
  `src/test/resources/fixtures/server-log/excerpt/`: `latin1.log`, `binary_gzip.log`,
  `long_lines.log`, `no_newline.log` (last line without terminator). Failing tests first in
  `domain/model/LogExcerptTest.java`: US1-1 (last 200 lines of 1,000, numbers 801–1000); US1-2
  (`contains: "OutOfMemoryError"`, case-insensitive, last matches only, absolute numbers,
  `matchingLines`); US1-4 (200 lines with 60,000 chars and `maxChars` 20,000 → only the newest
  lines that fit, `droppedLines` = the rest); one line over `maxChars` → cut, `cutLines: 1`;
  surrogate pair at the cut point; a text occurring only inside a masked value never matches;
  `linesBefore` 412,876 → first number 412,877; binary, Latin-1, empty, no-newline, CRLF cases;
  leak check (no masked value in any line)
- [ ] T016 [P] `application/LogWindowReader.java` (FR-009 step 1, D-9; after T010): `LogWindow
  read(Path file, long size)`; `size ≤ 50 MiB (52,428,800 bytes)` → whole file, `linesBefore =
  0`; otherwise `start = size − 52,428,800` and the window begins at the first position `p ≥
  start` with `byte[p − 1] == '\n'` (a partial first line is not part of it); `linesBefore` =
  number of `\n` bytes in `[0, p)`, counted by streaming the prefix in 64 KiB blocks (never held
  in memory); no such `p` → an empty window flagged as "no whole line". Failing tests first in
  `application/LogWindowReaderTest.java` with files generated in `@TempDir`: US1-7 (a 60 MiB log:
  window starts at the first whole line within the last 50 MiB, `wholeFile: false`,
  `startLine` equals a naive line count + 1); sizes exactly 52,428,800 (whole) and 52,428,801;
  `\n` exactly at `start − 1`; no line break in the last 50 MiB; empty file
- [ ] T017 [P] `application/ExcerptPermit.java` (D-13, D-15): process-wide fair semaphore with one
  permit; `Optional<Held> tryAcquire(Duration wait)` with `Held` `AutoCloseable` (release once);
  the caller passes what is left of the call's `cliExportTimeout` deadline and maps an empty result
  to `TIMEOUT` (T028). Failing tests first in `application/ExcerptPermitTest.java` (a second
  acquirer waits until the first releases; FIFO order; release on exception; a wait longer than
  `wait` → empty, the permit untouched; `Duration.ZERO` with a free permit → held; interrupted
  wait → empty with the interrupt flag kept) Callers clamp the remaining time to `Duration.ZERO` before `tryAcquire` (never negative); test `tryAcquire(Duration.ZERO)` takes a free permit and refuses a busy one.
- [ ] T018 `application/LogExcerptPerformanceTest.java` (D-11, D-13; after T015, T016): a 50 MiB
  synthetic log generated in `@TempDir` (secret-looking lines every 1,000 lines, two configured
  passwords occurring in it) is read by `application/LogWindowReader` and its excerpt built by
  `LogExcerpt.build` with the **real** known-span function — an `infra/SecretScrubber` with the two
  passwords registered, passed as `scrubber::occurrences` exactly as `adapter/ProfileSecrets`
  delegates to it — within 20 s on the CI machine (the bound covers window read, scrubber scan,
  masking and selection); both passwords absent from the excerpt; fix a non-linear masker or
  scrubber path if it fails
- [ ] T019 [P] `application/LogFileWriter.java` (FR-007, FR-008, FR-011, SC-004, D-7 step 4;
  after T006, T007): `Placed place(TargetFolderRules.CheckedFolder folder, String baseName, Path
  source)` → `Placed(Path file, long size)`; creates the folder and missing parents with
  `OwnerOnlyFolders` (T006); streams the source into `.<base>.<random>.part` in the folder
  (`CREATE_NEW`, `rw-------` on POSIX, `force(true)`); claims the name with
  `Files.createLink(<folder>/<base>.log, part)`, on `FileAlreadyExistsException` the next name
  `<base>-2.log`, `-3`, … up to `-999` (then `INVALID_INPUT` "no free file name"); deletes the
  `.part` name; **without hard links** (`UnsupportedOperationException`, or a
  `FileSystemException` other than `FileAlreadyExistsException`) claims the name with an empty
  placeholder (`CREATE_NEW`, same suffix loop) and `Files.move(part, placeholder, ATOMIC_MOVE,
  REPLACE_EXISTING)`; if atomic moves are unsupported too, placeholder and part are deleted and
  the call fails with `INVALID_INPUT` "The file system of the target folder supports neither hard
  links nor atomic renames; choose a local folder"; on any failure every file this call created
  is deleted, a folder it created stays; `FileOps` seam for tests. Failing tests first in
  `application/LogFileWriterTest.java`: name `<base>.log`; existing name → `-2` (US1-5); `-2` …
  `-999` taken → `INVALID_INPUT`; 20 concurrent placements of the same base get 20 distinct names
  and replace nothing (race); `rw-------`; created folder `rwx------`, existing folder's
  permissions unchanged; injected failures (copy, `force`, link, `.part` delete, move) leave no
  file of the call; placeholder fallback; neither links nor atomic moves; bytes identical to the
  source for UTF-8, Latin-1 and binary (FR-008); `application/ArchiveFolderWriterTest.java`
  unchanged
- [ ] T020 [P] `application/ServerLogAudit.java` (FR-015, D-18), built like
  `application/ArchiveExportAudit.java`: capability `download_server_log`, step `EXECUTE`, one
  `auditId` per call; `REFUSED`; `PENDING` → `EXECUTED` | `FAILED` (with the error code); inputs
  in fixed order `log`, `targetFolder`, `tailLines`, `maxChars`, `containsGiven` (`true` /
  `false`; the text is never audited); details `file`, `sizeBytes`, `inubitNode` (webservice); an
  unwritable final record becomes a result warning (as `export_archives`). Failing tests first in
  `application/ServerLogAuditTest.java` (record shapes and order; leak check: a `contains` value
  `s3cr3t-guess` and log content never in any record)

**Checkpoint**: build green; code review of phase 2.

## Phase 3: User Story 1 – Get the trace log of a server (P1) 🎯 MVP

**Goal**: `download_server_log` with `log: trace` writes the trace log of one 8.1 server verbatim
into the named folder and returns path, size, time and the masked, bounded excerpt (FR-001,
FR-005, FR-007…FR-015).
**Independent test**: with a fake StartCLI (`ScriptedProcessLauncher` writing a fixture
`trace.log`), a profile without any `unsupported` setting and a test matrix declaration admitting
8.1: the tool is announced, the file appears in the target folder with the downloaded bytes, the
result names path, size and the masked excerpt (US1-1…US1-9); no INUBIT contact.

- [ ] T021 [US1] **[LIVE] [OWNER]** 8.1 trace recording (D-23 step 1, quickstart "Early live
  tasks" 1; may start any time after T001, in parallel to Phase 2): the agent proposes the
  concrete action (which non-production 8.1 test server of the profile, the command StartCLI
  `trace -d <private dir>/trace.log` with the configured 8.1 client, the private directory
  outside every repository) and **waits for the operator's explicit approval of exactly that
  action**; then the operator, or the agent after approval, runs it once (read-only; never
  production; never any other form of `trace`); stdout, stderr, exit code and the downloaded file
  are kept owner-only in the private directory, never in the repository; the trace state shown in
  the Workbench is noted before and after and must be equal (SC-005). The neutral facts (message
  form `Trace data (<n> MB) stored to: …`, exit code, empty-file behaviour if observed; no log
  content, no names) are appended to `specs/021-server-log-download/research.md` "Spike findings"
- [ ] T022 [P] [US1] `adapter/cli/CliCommand.java` (FR-005, D-1): command `trace` with exactly one
  allowed option `--download` (a value option, only through `Builder.path`) and the factory
  `CliCommand.traceDownload(Path file, boolean windows)`; `build()` refuses `trace` without
  `--download` and with any other option, flag, positional argument or process id. Failing tests
  first in `adapter/cli/CliCommandTest.java`: the exact `--execCommand` text `trace --download
  '<path>'` (POSIX and Windows quoting); refused before any launch: `on`, `off`, `-l`, `-nl`,
  `-p`, `-r`, a raw `-d`, a process id, a second option, a missing `--download` (SC-005)
- [ ] T023 [P] [US1] `adapter/cli/CliOutputClassifier.java` (D-8, D-14): marker rule
  `FileWasNotFound` → `NOT_FOUND` "trace.log does not exist on <node>", next step "Check the trace
  settings of the server in the Workbench" — in the trace context only. Failing tests first in
  `adapter/cli/CliOutputClassifierTest.java` (marker in the trace context → `NOT_FOUND`; the same
  marker in an export context → classification unchanged; existing rules unchanged; leak check) Mechanism: a trace-specific entry point `CliOutputClassifier.classifyTraceDownload(CliResult)` applies the `FileWasNotFound` → `NOT_FOUND` rule and otherwise delegates to `classify(CliResult)`; `CliTraceRunner` (T025) calls only `classifyTraceDownload`; the test asserts that `classify(CliResult)` (export context) still maps the same output as before.
- [ ] T024 [US1] Reconstructed trace fixtures and catalogue (D-21, D-22):
  `src/test/resources/fixtures/server-log/v8_1/trace/{ok,empty,missing,login_failure,forbidden}.{stdout,stderr,exit,log,origin}`,
  all `.origin` "`reconstructed <source>`" (sources: research "Static facts" for `ok`/`empty`/
  `missing` (`FileWasNotFound`), the recorded StartCLI login-failure texts of the existing
  `fixtures/v8_1/` CLI cases, the 403 texts); `ok.log` a fictitious trace log (`acme` classes,
  `node1`, `*.example.test`, one `Authorization: Basic …` line and one `<password>` element);
  `ok.stdout` "Trace data (<n> MB) stored to: …" with `<n>` matching `ok.log`; new
  `tools/ServerLogFixtureCatalogueTest.java`: every case has an `.origin` of a valid format; every
  file belongs to a case; for every (version, log): `CapabilityMatrix.production()` `SUPPORTED` ⇔
  `<version>/<log>/ok.origin` starts with `recorded` (D-22)
- [ ] T025 [US1] `adapter/cli/CliTraceRunner.java` (FR-005, FR-008, FR-012, D-1, D-7, D-14; after
  T012, T022–T024): precondition check as `CliExportRunner.checkPreconditions`
  (`CliExportRunner.java:370-390`: temp root usable and `CliPaths.passable`, credentials
  complete); reservation and private directory with `LOG_PREFIX`; `CliRunner.run(…,
  traceDownload(<dir>/trace.log), remaining, "cliExportTimeout", guard)` (017 client choice, pin
  probe, password on stdin); success = StartCLI's success classification **and** one `n-OK`
  message matching `^Trace data \(\S+ MB\) stored to: .+$` **and** a regular file at the
  requested path (the path in the message is never used or returned); otherwise
  `UNEXPECTED_RESPONSE` "download_server_log got an unexpected answer from <node>; … StartCLI did
  not confirm the trace download", next step "Download the log in the Workbench; report the INUBIT
  version"; returns `DownloadedLog(path, size, Optional.empty(), PRESENT)` whose `close()` deletes
  the directory (also on failure, timeout, interruption, shutdown). Failing tests first in
  `adapter/cli/CliTraceRunnerTest.java` (`ScriptedProcessLauncher` / `FakeProcessLauncher.onLaunch`
  writes `<case>.log` to the `--download` path and prints `<case>.stdout`): argv array and the
  `--execCommand` text; password only on stdin (not in argv or environment); `cliExportTimeout`
  named as budget; the 8.1 client chosen per 017; every v8_1/trace case → its code (`ok` result,
  `empty` 0 bytes, `missing` → `NOT_FOUND`, `login_failure` → `AUTH_FAILED`, `forbidden` →
  `FORBIDDEN`); success text missing or no file → `UNEXPECTED_RESPONSE`; no client home →
  `CLI_UNAVAILABLE` naming `defaults.cliClients."8.1"`; edge case "maintenance mode, credentials
  not available, TLS failure" with the existing StartCLI texts and mechanisms: credentials not
  resolvable → `SECRET_UNRESOLVED` (the node's usual credential error) with **nothing launched**,
  StartCLI maintenance answer → `MAINTENANCE_MODE`, TLS failure (pin probe / StartCLI TLS text) →
  `TLS_ERROR`, each with the private directory deleted; private directory gone after success and
  failure; leak check
- [ ] T026 [US1] `adapter/cli/log/CliServerLogAdapter.java` + `adapter/cli/log/package-info.java`
  (FR-013, D-15, D-19; after T011, T012, T025): `implements ServerLogPort`; uses the call deadline
  it gets from the service (`deadlineNanos` = start of the download phase + the node's
  `cliExportTimeout`), shared by the slot wait (`CliConcurrencyLimiter.acquireForHelper(server,
  remaining, "cliExportTimeout")`, 2 permits per node) and the process (`remaining`); exceeded →
  `TIMEOUT` "The trace log download from <node> did not finish within cliExportTimeout (<n> s)",
  next step naming `cliExportTimeout` plus "large logs need a larger cliExportTimeout"; process
  tree killed, private directory deleted; dispatch `TRACE` → `CliTraceRunner`;
  `WEBSERVICE` is wired in T041 (until then the adapter throws `INTERNAL` "web service log channel
  not wired", unreachable because `allowed()` does not admit `WEBSERVICE_LOG_READ` before T043);
  `adapter/AdapterGatewayFactory.java` creates it for 8.1/9.0 nodes. Failing tests first in
  `adapter/cli/log/CliServerLogAdapterTest.java` (`FakeProcessLauncher.hanging()` → `TIMEOUT`
  within the budget, slot released, nothing left; both permits held by others → slot wait ends
  in `TIMEOUT` whose next step names `cliExportTimeout` and does not contain "raise cliTimeout";
  a deadline already passed → `TIMEOUT` without launch; a trace download takes a slot) and
  `adapter/cli/CliConcurrencyLimiterTest.java` (other StartCLI runs on 8.1/9.0 still take no slot)
- [ ] T027 [US1] `application/ServerLogService.java` part 1 + `application/ServerLogRequest.java`
  — decision flow (contracts/mcp-tools-delta.md "Decision flow" steps 1–7, FR-001, FR-002,
  FR-011, D-7 step 1, D-14, D-18; after T009, T011, T019, T020, T026): `ServerLogRequest`
  (`target`, `log`, `targetFolder`, `OptionalInt tailLines`, `Optional<String> contains`,
  `OptionalInt maxChars`, `Optional<String> mcpClient`; `toString` without `contains`); the
  service repeats every schema check: `target` "exactly one server id `<group>/<node>`" (a group
  id → the `TargetResolver.resolveSingleServer` text, `INVALID_INPUT`; unknown → `TARGET_UNKNOWN`),
  `log` ∈ {`trace`, `webservice`}, `targetFolder` 1–4,096 chars, `tailLines` "integer 1–2,000,
  default 200", `contains` "1–200 characters without control characters" (`\p{Cc}`; never
  echoed), `maxChars` "integer 1–100,000, default 20,000" — `INVALID_INPUT` naming the field, e.g.
  `Invalid tailLines 0 (1–2000)`; `log: webservice` on a group without `serverLogRead` →
  `NOT_CONFIGURED` "download_server_log for the web service log is not enabled for {group} <g>:
  unsupported.serverLogRead is not set", next step "Set unsupported.serverLogRead: true on {group}
  <g> and restart the MCP client (unsupported INUBIT functionality, see docs/setup.md "Unsupported
  functions")" **before** detection or any request; folder rules
  `TargetFolderRules.check(targetFolder, <log cloud subject>)` (`INVALID_INPUT`, nothing
  created); refusals of these steps audited `REFUSED`; then audit `PENDING` (unwritable →
  `INTERNAL`, nothing sent); `VersionGate.admitWithDetection("download_server_log", node,
  TRACE_LOG_READ | WEBSERVICE_LOG_READ, READ, {CLI})`: `UNSUPPORTED_VERSION` closes the call's
  `auditId` with `REFUSED`, a detection error is passed through with its own code and message and
  closes it with `FAILED`; then the call deadline `deadlineNanos = System.nanoTime() + node
  cliExportTimeout` (start of the download phase, D-15; `LongSupplier` seam for tests); then the
  server's login credentials: `GatewayFactory.prepareCredentials(node, remaining)`
  (`domain/port/GatewayFactory.java:189`, `remaining` = what is left of the call deadline, clamped
  to `Duration.ZERO`) **before** `GatewayFactory.serverLogs(node)`, so that the credential wait in
  `AdapterGatewayFactory` `Slot.gateway()` → `awaitCredentials()` (bounded only by the credential
  source's own `timeout()`, up to 60 s) finds them held and does not wait outside the call deadline
  (FR-013 "the server's login credentials"): `PENDING` → `TIMEOUT` "The <log> log download from
  <node> did not finish within cliExportTimeout (<n> s): the login credentials of <node> were not
  read in time", next step naming `cliExportTimeout`, the port not called, audit `FAILED`; a failed
  fetch passes through with its own classified code (the node's usual credential code, D-14; today
  `AUTH_FAILED` or `UNREACHABLE` from `NodeSecrets`), the port not called, audit `FAILED`; `READY`
  → `GatewayFactory.serverLogs(node)` and `ServerLogPort.download(kind, version, deadlineNanos)` in
  try-with-resources. Failing tests
  first in `application/ServerLogServiceTest.java` (fake port recording calls and the deadline it
  got; fake `GatewayFactory` recording the `prepareCredentials` bound; fake audit): the port gets a
  deadline equal to the seam's time + `cliExportTimeout`; `prepareCredentials` is called before
  `serverLogs` with a bound equal to the remaining deadline (= `cliExportTimeout` at the seam's
  time); `prepareCredentials` answering `PENDING` → `TIMEOUT` naming `cliExportTimeout`, port not
  called, audit `FAILED`; `prepareCredentials` throwing `AUTH_FAILED` → `AUTH_FAILED` passed
  through, port not called, audit `FAILED`; each
  invalid input → `INVALID_INPUT`, zero port calls, nothing created; folder rules (relative,
  `..`, network path, workspace, config dir, git working tree); webservice without opt-in →
  `NOT_CONFIGURED`, no detection, no port call (US2-3 on service level); undetectable version →
  the detection's code (`UNREACHABLE`, `TLS_ERROR`, `AUTH_FAILED`, `TIMEOUT`, `MAINTENANCE_MODE`,
  `SECRET_UNRESOLVED`), port not called, audit `FAILED` (US3-3, edge case "maintenance mode,
  credentials not available, TLS failure"); unwritable `PENDING` → `INTERNAL`, port not called; audit sequence per path; leak
  check (the `contains` text never in a message, audit record or `toString`)
- [ ] T028 [US1] `ServerLogService` part 2 — excerpt, placement, result (FR-007…FR-010, FR-015,
  D-7 steps 3–5, D-8, D-11, D-13, D-15, D-18; after T015–T019, T027): after the download:
  `ConfiguredSecrets.prepare(min(prepareBound, remaining))` (`domain/port/ConfiguredSecrets`, the
  `ProfileSecrets` instance feature 019's `KnowledgeService` uses; `prepareBound` =
  `ProfileSecrets.timeout()` passed in by `Wiring` as for 019; `remaining` = what is left of the
  call deadline) → `ExcerptPermit.tryAcquire(remaining)` (empty → `TIMEOUT` "The <log> log download from <node>
  did not finish within cliExportTimeout (<n> s)", next step naming `cliExportTimeout`; nothing
  in the folder, private directory deleted, audit `FAILED`) → `LogWindowReader.read` →
  `LogExcerpt.build` with `ConfiguredSecrets::occurrences` as known-span function (D-11); if
  `prepare` answered `NotReady`, no permit is taken and no excerpt is built: `"excerpt": {"kind":
  "withheld"}` with the
  hint "The excerpt is withheld: the credentials of <node> (<store>: <state>) have not been read
  yet, so they could not be masked; every excerpt is withheld until this source can be read; the
  file is written" (fail closed, 019 option A; the pending list names nodes and stores, never a
  value; FR-010) → `LogFileWriter.place(folder,
  "<group>-<node>-<log>-<yyyyMMdd-HHmmss>", privateCopy)` with the UTC instant at which the
  download finished (injected `Clock`) → `ServerLogResult` (`downloadedAt` ISO-8601 UTC = the
  instant in the file name) → final audit `EXECUTED` (details `file`, `sizeBytes`, `inubitNode`) or
  `FAILED` → the private directory closed. Hints: empty → "The log is empty" (webservice:
  "The log is empty; web service logging may be disabled"); presence `UNKNOWN` → "The log is empty
  or does not exist on the server" (webservice: "The log is empty or does not exist on the server;
  web service logging may be disabled", US2-4 second branch). Warnings: "The log file contains
  clear-text content" always;
  "Read through an unsupported, undocumented INUBIT interface; diagnosis only" for webservice
  only; the cloud-sync warning of the folder rules; version and client warnings of the gate; an
  audit warning. Any failure after the download → no file of the call in the folder, private
  directory deleted, audit `FAILED`. Failing tests first in `application/ServerLogServiceTest.java`:
  US1-1 result shape (node, log, absolute file, size, `downloadedAt`, excerpt of the last 200
  lines); file bytes equal the download for UTF-8, Latin-1 and binary content (FR-008); US1-5
  existing name → `-2`; empty log → file written, `kind: empty` + hint; 0 bytes with presence
  `UNKNOWN` → the trace hint for `trace`, the hint with "web service logging may be disabled" for
  `webservice` (US2-4); `contains` without match → file written, empty excerpt + hint; injected
  placement failure → no file, `FAILED`; masking of a known profile password of **another** node
  in the excerpt while the file keeps it (US1-3); a fake `ConfiguredSecrets` answering `NotReady`
  → file written, `kind: withheld` + hint naming node and store and containing "every excerpt is
  withheld until this source can be read", no log line in the result, no permit taken, audit
  `EXECUTED` (US1-10); the
  permit held by another call longer than the remaining deadline → `TIMEOUT` naming
  `cliExportTimeout`, no file in the folder, private directory deleted, audit `FAILED`; the
  `prepare` bound never exceeds the remaining deadline; leak check The remaining time after the download is clamped to `Duration.ZERO`; test "deadline already passed after download": `ConfiguredSecrets.prepare(Duration.ZERO)` is called, the permit is still taken if free, and the file is placed.
- [ ] T029 [US1] `ServerLogService` INFO line and adapter DEBUG lines (FR-015, SC-003, D-18; after
  T028): failing test first in `application/ServerLogLoggingTest.java` (captured logback output),
  then the implementation in `application/ServerLogService.java` (exactly one `INFO` line per call,
  success and every failure path after the input checks, `download_server_log on {node}: log
  {log}, file {name|-}, {n|-} bytes, window {lines|-}, outcome {OK|<code>}`),
  `adapter/cli/CliTraceRunner.java` and `adapter/cli/log/CliServerLogAdapter.java` (`DEBUG` only
  for client home and JDK; the webservice adapter adds its variant line in T041). Tests: one INFO
  line for success, for `NOT_FOUND` and for `TIMEOUT` with the fields above; a full call with
  secret-looking log content and a `contains` text leaves no excerpt line, no `contains` text, no
  StartCLI output and no masked value in the output at any level; leak check
- [ ] T030 [P] [US1] Tool and schemas (FR-001, FR-004, FR-014, D-20; after T027):
  `mcp/tools/DownloadServerLogTool.java` (name `download_server_log`, the description
  **verbatim** from contracts/mcp-tools-delta.md "Description", `ToolHints.localWrite("Download an
  INUBIT server log")` → `readOnlyHint: false`, `destructiveHint: false`, `idempotentHint:
  false`, `openWorldHint: true`; maps arguments to `ServerLogRequest`);
  `src/main/resources/schemas/download_server_log.input.json` **verbatim** from the contract
  (`target` pattern `^[a-z0-9][a-z0-9-]{0,31}/[a-z0-9][a-z0-9-]{0,31}$`; `log` enum `trace`,
  `webservice`; `targetFolder` 1–4096; `tailLines` 1–2000 default 200; `contains` 1–200 with
  pattern `^[^\u0000-\u001F\u007F-\u009F]{1,200}$`; `maxChars` 1–100000 default 20000;
  `additionalProperties: false`) and `download_server_log.output.json` (fields and variants of
  the contract "Output", including `kind: withheld`). Failing tests first in
  `mcp/tools/DownloadServerLogToolTest.java` (schema rejects a group id, another `log`,
  `tailLines` 0 / 2001, `contains` empty / 201 chars / a control char, `maxChars` 0 / 100001,
  unknown properties; annotations; description contains "clear text", "business data and
  credentials", "supported trace download", "unsupported", "undocumented", "without vendor
  support", "any INUBIT update", "never replaced"; a result of every contract variant validates
  against the output schema; arguments → `ServerLogRequest` with a fake service) The output field `excerpt.returnedLines` is `lines.size()`, set during JSON mapping, and is part of the output schema.
- [ ] T031 [US1] Wiring, announcement and admission of the trace log (FR-002, D-11, D-17; after
  T029, T030): `Wiring.java`: service, `CliServerLogAdapter`, the existing feature-019
  `ProfileSecrets` instance as `ConfiguredSecrets` with its `timeout()` as `prepareBound` (the
  local variable becomes a field shared by `KnowledgeService` and `ServerLogService`),
  `TargetFolderRules`/`AuditPort` reuse; announcement branch 1: some node `possiblyCapable(node,
  TRACE_LOG_READ)` && `clientSelection.anyCandidate(node)`; `allowed()` adds `TRACE_LOG_READ`
  with `withClient`; offered in read-only mode, independent of `write.*`, `archives.*` and
  production write opt-ins. Failing tests first: `ToolAnnouncementTest.java` (test matrix
  admitting 8.1: profile without any `unsupported` setting and a client home → announced (US1-6),
  also in read-only mode; no client home anywhere or every node pinned to 6.1 → not announced;
  production matrix before T033 → tool list unchanged); `MainTest.java` (exact tool lists:
  0.9.1 list, plus `download_server_log` only where announced; SC-002);
  `mcp/tools/ListNodesToolTest.java` (`trace_log_read` in `usable`/`notUsable` for nodes with a
  client home; schema validates); `DownloadServerLogWiringTest.java` (trace end to end through
  the wired server with `FakeProcessLauncher`: file in the folder, result as contract; a
  password of another configured node in the log is masked in the excerpt)
- [ ] T032 [US1] 8.1 trace contract over the reconstructed cases (FR-017, D-22; after T031):
  `adapter/cli/log/ServerLogV81ContractTest.java` runs `CliServerLogAdapter` + `ServerLogService`
  per `v8_1/trace` case (test matrix declaration): `ok` → result shape, `sizeBytes` = `ok.log`,
  masked excerpt; `empty` → `kind: empty`; `missing` → `NOT_FOUND` text; `login_failure` →
  `AUTH_FAILED`; `forbidden` → `FORBIDDEN`; edge case "maintenance mode, credentials not
  available, TLS failure": `SECRET_UNRESOLVED` (nothing launched), `MAINTENANCE_MODE`,
  `TLS_ERROR`; after each failure no file in the folder (SC-004), private directory deleted and
  audit `FAILED` with the code
- [ ] T033 [US1] **[OWNER]** **[BLOCKED: T021]** Enable 8.1 trace in **one commit** (FR-017,
  D-21, D-22): the agent neutralizes the T021 output in the private directory: the recorded
  `trace.log` is replaced by a fictitious log of the same shape (timestamps, levels, `acme`
  classes, `node1`, `*.example.test`), the MB value in `.stdout` adjusted to its size,
  `tools/neutralize.py --map <private>/.synthetic-map` plus the private `neutralize-map`,
  `NoCustomerIdentifiersTest` with the private lists and `python3 -I tools/check-identifiers.py
  --all` pass; result `src/test/resources/fixtures/server-log/v8_1/trace/ok.*` (and `empty.*`
  if T021 observed it), `.origin` "`recorded <date> 8.1.x neutralized`". **The owner reviews the
  diff of the fixture files before the commit.** In the same commit
  `domain/model/CapabilityMatrix.java` 8.1 `TRACE_LOG_READ` → `SUPPORTED` (note removed); updated
  first: `ServerLogV81ContractTest` (recorded `ok`), `CapabilityMatrixTest`,
  `ServerLogFixtureCatalogueTest`, `ToolAnnouncementTest` and `MainTest` (production matrix now
  announces for 8.1 / `AUTO` nodes with a client home). If the recorded message form differs from
  D-1, fix the `CliTraceRunner` rule test-first before the flip

**Checkpoint**: build green; US1 works end to end (offline, and on the production matrix after
T033); code review phase 3.

## Phase 4: User Story 2 – Get the web service log of a server (P1)

**Goal**: for a group with `unsupported.serverLogRead`, `log: webservice` writes `webservice.log`
of the node the server address connects to and names that INUBIT node; without the opt-in the
call is refused before any request; the configuration view and the docs make the unsupported
status explicit (FR-002…FR-004, FR-006, FR-016).
**Independent test**: with fake vendor classes / a fake INUBIT client per version and
`FakeProcessLauncher` replaying helper frames: an opted-in 8.1 node → file, result with
`inubitNode`; a group without the opt-in → `NOT_CONFIGURED`, zero WireMock requests, zero launches.

- [ ] T034 [US2] **[LIVE] [OWNER]** 8.1 webservice spike (D-23 step 2, quickstart "Early live
  tasks" 2; may start any time after T001, in parallel to Phases 2–3): the agent proposes the
  concrete action (which non-production 8.1 test server, the throw-away program **outside the
  repository** performing exactly D-2 steps 1–5 — login, `ClusterClient` with
  `CurrentUser.getClientUID()` / `getUsedAdditionalUserGroup()`, `getNode()`,
  `clusterDownload(<private dir>/webservice.log, node, "webservice.log")`, logout — and the
  private output directory) and **waits for the operator's explicit approval of exactly that
  action**; read-only; never production; no file name other than `webservice.log` is ever sent.
  Recorded (owner-only, private directory): whether `CurrentUser.getClientUID()` works in a plain
  JVM, the node name, the frames-equivalent output, size, the exception chain and text for a
  missing file (only if the test server has no `webservice.log`), the 0-byte case if it occurs,
  and whether a single-node server answers `Server not in cluster mode.`. Neutral findings
  appended to `specs/021-server-log-download/research.md` "Spike findings". **If the single-node
  server answers `Server not in cluster mode.` or `getNode()` fails, stop US2 and report to the
  owner (D-2/D-5 do not hold)**
- [ ] T035 [P] [US2] Fake vendor classes (D-22): `src/test/java/com/inubit/ibis/server/cluster/ClusterClient.java`
  (four-argument constructor `(I_IBISClientInterface, String user, String clientUID, String
  additionalUserGroup)`, `Node getNode()`, `MultipartDownloadThread clusterDownload(File, NodeName,
  String)` returning a **not started** thread), `Node` (`getNodeName()`), `NodeName` (`getName()`,
  static `nodeName(String)`), `src/test/java/com/inubit/ibis/common/CurrentUser.java`
  (`getClientUID()`, `getUsedAdditionalUserGroup()`), in the packages `javap` shows in the 8.1.17
  `lib/ibis.jar` (research "Static facts": `…/data/Node`, `…/cache/NodeName`; confirm the full
  package names with `javap` before creating the files); `testing/FakeInubitScript.java` extended
  (node name, `getNode` failure, download content or exception text, recorded call list with
  arguments); `testing/FakeInubitClient.java` packs them into the fake client JAR. Failing tests
  first in `testing/FakeInubitClientTest.java`
- [ ] T036 [P] [US2] Helper request, frames and the optional `op` key (D-4; after T004, T010):
  `adapter/cli/helper/HelperRequest.java` becomes a record with the shared connection fields plus
  a sealed `Operation` (`QueueEntryRead(objectType, fileName, Optional<Path> dataOut, Path
  variablesOut)` | `ServerLogRead(ServerLogKind kind, Path logOut)`, "`kind` only `WEBSERVICE`");
  `encode()` writes **no `op` line for `QueueEntryRead`** (the 020 request stays byte-identical)
  and `op=serverLog` as second line for `ServerLogRead`, then exactly the keys `nonce` (32 hex),
  `variant`, `url`, `trustStore`, `trustStorePassword`, `hostnameVerification`, `log=webservice`,
  `logOut`, `user`, `password`, `end` (percent-encoded values) and **no file name key**;
  `toString` leaves out user and passwords; `adapter/cli/helper/HelperFrames.java` `+
  NodeOk(String name)`, `NodeError(chain, message)`, part `LOG` in `PartOk` / `PartError` (`node ok
  <nodeName>`, `node error <exceptionChain> <message>`, `part log ok <bytes>`, `part log error
  <exceptionChain> <message>`); `CliQueueEntryAdapter` builds `QueueEntryRead`;
  `src/helper/java/de/dadecker/inubit/mcp/helper/ClientHelper.java` accepts the **optional** key
  `op` (absent = `queueEntry`; `op=queueEntry` reads exactly the 020 keys; any other value,
  `serverLog` included until T037, → malformed request, exit 2; a repeated `op` → exit 2). Failing
  tests first in `adapter/cli/helper/HelperRequestTest.java` (the 020 exact-encoding test
  `encodesEveryKeyOnceInProtocolOrderWithPercentEncodedValues` unchanged; the `serverLog` stdin
  block of D-4 exactly; leak check of `toString`; the existing round trip through the real helper
  `theHelperAcceptsTheEncodedRequestAndReceivesEveryValueUnchanged` unchanged and green),
  `adapter/cli/helper/ClientHelperTest.java` (a hand-built 020 request with `op=queueEntry` behaves
  exactly as without it; `op=serverLog`, `op=other`, `op` twice → exit 2; every 020 test unchanged)
  and `adapter/cli/helper/HelperFramesTest.java` (new frames; every 020
  `fixtures/queue-entry/**.frames` parses as before)
- [ ] T037 [US2] `ClientHelper` operation `serverLog` (FR-006, D-2, D-4, D-5; after T035, T036;
  proceeds on the static facts; if T034 contradicts them, rework per the findings with the owner
  informed): `src/helper/java/de/dadecker/inubit/mcp/helper/ClientHelper.java` admits
  `op=serverLog` with exactly its keys (keys of the other operation → malformed request, exit 2);
  `log` accepts only `webservice` and maps it to the **constant** `webservice.log`; API check before
  `start` (`api missing <what>`, exit 4): `ClusterClient` four-argument constructor, `getNode()`,
  `clusterDownload(File, NodeName, String)`, `Node.getNodeName()`, `NodeName.getName()`,
  `CurrentUser.getClientUID()`, `getUsedAdditionalUserGroup()`, the 020 members of the variant and
  `MultipartDownloadThread` (`start`/`join` via `Thread`, `exceptionOccured`, `getException`);
  flow: client of the variant + `loginUserCli` → `new ClusterClient(client, user,
  CurrentUser.getClientUID(), CurrentUser.getUsedAdditionalUserGroup())` → `getNode().getNodeName()`
  → `node ok <name>` (or `node error …` then `end` without download) → `clusterDownload(new
  File(logOut), node, "webservice.log")`, `start()`, `join()` → `part log ok <bytes>` / `part log
  error …` → `end`; `logoutUser(user)` in `finally` on 8.1; frame order `start`, `login ok`,
  `node ok`, `part log …`, `end`. Rules of 020 D-2 unchanged: one top-level class, no nested or
  anonymous classes, JDK classes only, reflection only, `release 17`. Failing tests first in
  `adapter/cli/helper/ClientHelperTest.java` (in-process with the fakes): the recorded vendor
  calls are exactly login, `getNode`, `clusterDownload(…, "webservice.log")`, logout, with the
  `NodeName` from `getNode` passed explicitly; a `serverLog` request with a `fileName` key or
  `log=trace` → exit 2; each `api missing` member → exit 4; `node error` → no download; download
  exception → `part log error`; every 020 queue test unchanged; leak check of the frames; and in
  `adapter/cli/helper/HelperRequestTest.java` the named round trip
  `theHelperAcceptsTheEncodedServerLogRequestAndReceivesEveryValueUnchanged` (encoded
  `ServerLogRead` through the real helper, values received unchanged, next to the unchanged 020
  round trip)
- [ ] T038 [US2] `adapter/cli/helper/ClientHelperProcessTest.java` (after T037): the packaged
  helper runs `op=serverLog` in a real child JVM against the fake client home and writes
  `logOut`; `ClientHelperPackagingTest` still finds exactly one `ClientHelper.class` (major 61);
  `architecture/PackageBoundaryTest.java` (helper references only `java/*`) green
- [ ] T039 [P] [US2] `adapter/cli/log/WebServiceLogClassifier.java` (D-5, D-8, D-14; after T005,
  T036): frames + exit code → outcome or `ToolErrorException`; shared cases through
  `HelperFailures` (log subject); `node ok` name "1–128 chars of `[A-Za-z0-9_.:-]`", else
  `UNEXPECTED_RESPONSE`; `node error` with an access-denied text or HTTP 403 → `FORBIDDEN` "<node>
  refused the web service log download for the configured user", next step "Give the user the
  right the Workbench's Cluster Manager needs" (`Configuration`); `part log error` whose message
  contains `not found on` → `NOT_FOUND` "<node> has no web service log (webservice.log on INUBIT
  node <name>)", next step "Enable web service logging in the server configuration, then retry";
  `Server not in cluster mode.` or any other `node`/`part` error → `UNEXPECTED_RESPONSE`
  "download_server_log got an unexpected answer from <node>; the unsupported INUBIT interface may
  have changed after an INUBIT update" with a scrubbed excerpt ≤ 200 chars; `part log ok 0` →
  size 0, `PRESENT`. Failing tests first in `adapter/cli/log/WebServiceLogClassifierTest.java`
  (each case; name of 129 chars and with `/` rejected; leak check)
- [ ] T040 [P] [US2] Reconstructed webservice fixtures (D-21; after T024):
  `src/test/resources/fixtures/server-log/v8_1/webservice/{ok,empty,missing,forbidden,not_cluster_mode,api_missing,garbage_frames}.{frames,exit,log,origin}`,
  `.origin` "`reconstructed <source>`" (`missing` from the server text `File webservice.log not
  found on node1`); `ok.log` a fictitious SOAP log (`acme` services, `partner.example.test`, one
  `<wsse:Password>` element, one `Authorization: Basic …` header); `ok.frames` size and node name
  `node1` consistent with `ok.log`; `ServerLogFixtureCatalogueTest` covers them
- [ ] T041 [US2] `adapter/cli/log/CliWebServiceLogAdapter.java` (FR-006, FR-012, D-2, D-6, D-16;
  after T026, T037–T040): `HelperVariant.forVersion` (8.1 → `SOAP_81`, 9.0 → `REST_90`, else
  `UNSUPPORTED_VERSION`); `CliRunner.select(server, version)` + `checkLaunch` (no client home →
  `CLI_UNAVAILABLE` with the 017 text naming `defaults.cliClients."M.m"`; no `lib/ibis.jar` or JDK
  → `CLI_UNAVAILABLE`); TLS check `TlsPeer.handshake` with `min(server.timeout(), remaining)` and
  a one-time PKCS12 store with only the verified leaf (`HelperTrustStore`), `http` without store;
  private directory with `LOG_PREFIX`, `logOut` = `<dir>/out/webservice.log`; helper run via
  `ProcessRun` (argv = JDK, class path, class name only; request on stdin); classification →
  `DownloadedLog(path, size, Optional.of(name), PRESENT)`; `DEBUG` line for variant, client home
  and JDK (D-18); `CliServerLogAdapter` dispatches `WEBSERVICE` here (the temporary `INTERNAL` of
  T026 removed), inside the same service-given deadline and slot. Failing tests first in `adapter/cli/log/CliWebServiceLogAdapterTest.java`
  (`FakeProcessLauncher.onLaunch` reads the stdin request, copies `<case>.log` to `logOut`,
  replays `.frames`/`.exit`): argv without secrets, nothing secret in the environment; request
  with `op=serverLog`, `log=webservice` and no file name; each v8_1/webservice case → its code;
  invalid node name → `UNEXPECTED_RESPONSE`; no home / no `ibis.jar` / no JDK / `api missing` →
  `CLI_UNAVAILABLE`; edge case "maintenance mode, credentials not available, TLS failure":
  credentials not resolvable → `SECRET_UNRESOLVED` with **nothing launched** and no TLS check,
  `TlsPeer.handshake` failure (untrusted certificate, pin mismatch) → `TLS_ERROR` with nothing
  launched, a maintenance answer (020 `HelperFailures` mapping) → `MAINTENANCE_MODE`; private
  directory deleted after success and failure; in `adapter/cli/log/CliServerLogAdapterTest.java`:
  webservice takes the slot, TLS check and process share the `cliExportTimeout` deadline, slot-wait
  `TIMEOUT` names `cliExportTimeout`, `TIMEOUT` leaves nothing; in
  `application/ServerLogLoggingTest.java` a webservice call: the variant `DEBUG` line, no request
  line, no frame and no helper output at any level; leak check
- [ ] T042 [US2] Configuration view (FR-016, US2-6, D-17; after T009, which changes the same
  `ConfigSummary.java`, `ConfigSummaryTest.java` and `MainTest.java`; not concurrently with T031 or
  T033, which also change `MainTest.java`):
  `config/ConfigValidator.java` `checkServerLogReadOptIn`: warning on a `production: true` group
  "unsupported.serverLogRead is enabled on production {group} %s: the web service log is read
  through an undocumented INUBIT interface without vendor support" (`termWarning`);
  `config/ConfigSummary.java`: **only if at least one group has `serverLogRead: true`**, every group
  line ends with `, serverLogRead: off` / `, serverLogRead: enabled (unsupported)`, after the
  archive state and after the `queueEntryRead` state if that one is shown. Failing tests first in
  `config/ConfigValidatorTest.java`, `config/ConfigSummaryTest.java` (golden texts without the
  opt-in unchanged byte-for-byte; `Group test: archives: allowed, serverLogRead: enabled
  (unsupported)` / `Group prod (production): archives: refused (no archives.productionOptIn),
  serverLogRead: off`; order with both opt-ins), `MainTest.java` (`--check-config` "Warnings:"
  and the startup log `Configuration: …` warning)
- [ ] T043 [US2] Announcement and admission of the web service log (FR-002, D-6, D-17; after T031,
  T041): `Wiring.java` announcement branch 2: some node with `serverLogRead` &&
  `possiblyCapable(node, WEBSERVICE_LOG_READ)` && `clientSelection.anyCandidate(node)`; `allowed()`
  adds `WEBSERVICE_LOG_READ` with `withClient && serverLogRead`. Failing tests first in
  `ToolAnnouncementTest.java` (opt-in only on a group pinned to 6.1 or without client home → this
  branch does not announce), `mcp/tools/ListNodesToolTest.java` (`webservice_log_read` only for
  nodes of opted-in groups with a client home; never without an opt-in),
  `DownloadServerLogWiringTest.java`: no opt-in + `log: webservice` → `NOT_CONFIGURED` naming
  `unsupported.serverLogRead`, zero WireMock requests, zero launches (US2-3); opted-in single-node
  8.1 (test matrix) → file, `inubitNode: node1`, unsupported and clear-text warnings (US2-1); a
  cluster node whose helper reports `node2` → result names `node2` (US2-2)
- [ ] T044 [US2] 8.1 webservice contract over the reconstructed cases (FR-017, D-22; after T043):
  `adapter/cli/log/ServerLogV81ContractTest.java` webservice part per `v8_1/webservice` case (test
  matrix declaration): `ok` → result with `inubitNode`, masked `<wsse:Password>` and
  `Authorization`; `empty` → hint "The log is empty; web service logging may be disabled";
  `missing` → `NOT_FOUND` with the spec text, no file written (US2-4); `forbidden` → `FORBIDDEN`;
  `not_cluster_mode` and `garbage_frames` → `UNEXPECTED_RESPONSE`; `api_missing` →
  `CLI_UNAVAILABLE`; edge case "maintenance mode, credentials not available, TLS failure":
  `SECRET_UNRESOLVED` and `TLS_ERROR` (nothing launched), `MAINTENANCE_MODE`; after each failure
  no file in the folder, private directory deleted and audit `FAILED` with the code
- [ ] T045 [P] [US2] `docs/setup.md` (FR-004, US2-5, D-25; after T009 and T042, which changes the section 6
  examples of the same file): subsection `### Unsupported functions`
  gets the `unsupported.serverLogRead` table row, the YAML example and the FR-004 text
  **verbatim** from contracts/configuration-delta.md "Documentation text", after the
  `queueEntryRead` text; the sentence on the download budget names `cliExportTimeout` with its
  built-in value **120 s** (`Defaults.BUILTIN_CLI_EXPORT_TIMEOUT`); section 6 gets one summary
  example with a `serverLogRead` opt-in (existing examples unchanged). Failing test first
  `config/ServerLogDocsTest.java`: the bold sentences of the FR-004 text are in `docs/setup.md`
  ("uses unsupported INUBIT functionality", "No vendor support, no stability guarantee", "any",
  "Diagnosis only", "Clear-text content", "in clear text", "Read-only on INUBIT", "Cluster
  nodes"); the YAML example parses with `ConfigLoader` Also depends on T042 (the summary line format); the example is copied verbatim from the `ConfigSummaryTest` golden text.
- [ ] T046 [US2] **[OWNER]** **[BLOCKED: T034]** Enable 8.1 webservice in **one commit** (FR-017,
  D-21–D-23): the agent neutralizes the T034 output as in T033 (fictitious SOAP log content of the
  same shape, sizes in the frames adjusted, node name → `node1`, identifier checks green) into
  `src/test/resources/fixtures/server-log/v8_1/webservice/ok.*` (and `missing.*` / `empty.*` where
  recorded), `.origin` "`recorded <date> 8.1.x neutralized`"; **the owner reviews the diff before
  the commit**; in the same commit `CapabilityMatrix` 8.1 `WEBSERVICE_LOG_READ` → `SUPPORTED`;
  updated first: `ServerLogV81ContractTest`, `CapabilityMatrixTest`,
  `ServerLogFixtureCatalogueTest`, `ToolAnnouncementTest`. If the recording shows that 8.1 answers
  a missing file like an empty one, the adapter reports `LogPresence.UNKNOWN` for 0 bytes and the
  contract test pins it (D-8). If an 8.1 step cannot be recorded, the feature does not ship the
  web service log (D-23): the owner's decision goes to `quickstart.md` and
  `reviews/implementation.md`

**Checkpoint**: build green; SC-002 comparison (tool list and test count vs T001) recorded in
`reviews/implementation.md`; code review phase 4.

## Phase 5: User Story 3 – Right implementation per INUBIT version (P2)

**Goal**: 8.1 and 9.0 servers use the channel and client installation of their version; 6.1,
other lines and undetectable versions are refused before any request; 9.0 is enabled per log only
with its recording (FR-002, FR-017, US3-1…4).
**Independent test**: one profile with an 8.1 node, a 9.0 node (fake clients per version, JDK
17/21 paths) and a 6.1 node; `FakeProcessLauncher` records the launched JDK, class path and the
helper's `variant`.

- [ ] T047 [US3] Version selection end to end (US3-1…US3-4, D-6): new
  `adapter/cli/log/ServerLogVersionProfile.java` (test support like `QueueEntryVersionProfile`)
  and failing tests first in `adapter/cli/log/ServerLogVersionSelectionTest.java`: 8.1 trace →
  the `defaults.cliClients."8.1"` StartCLI client; 9.0 trace → the 9.0 client; 8.1 webservice →
  `SOAP_81`, 8.1 home and JDK, servlet URL; 9.0 webservice → `REST_90`, 9.0 home and JDK, base URL
  (9.0 admitted through a test matrix declaration); 6.1 and 9.1 → `UNSUPPORTED_VERSION` naming the
  detected version and "supported: 8.1, 9.0", no launch; undetectable `AUTO` node → the
  detection's own code (`UNREACHABLE`, `TLS_ERROR`, `AUTH_FAILED`, `TIMEOUT`), no launch; no 9.0
  client home → `CLI_UNAVAILABLE` naming `defaults.cliClients."9.0"`; an 8.1 client chosen for a
  9.0 node (webservice, `api missing` `ClusterClient`) → `CLI_UNAVAILABLE`; production matrix 9.0
  before T051/T052 → "… is not yet verified for INUBIT 9.0"; fixes in the adapters or the service
  only if a test fails
- [ ] T048 [US3] 9.0 contract in the fallback state (FR-017, D-22; after T047):
  reconstructed `src/test/resources/fixtures/server-log/v9_0/{trace,webservice}/<case>.*` (the
  v8_1 case sets, `.origin` "`reconstructed <source>`", fictitious content);
  `adapter/cli/log/ServerLogV90ContractTest.java`: while `v9_0/<log>/ok.origin` is not recorded,
  the production matrix refuses a 9.0 node "not yet verified" with no launch; with a test
  declaration the 9.0 StartCLI and `REST_90` paths run over the reconstructed cases;
  `ServerLogFixtureCatalogueTest` covers `v9_0/`
- [ ] T049 [US3] **[LIVE] [OWNER]** 9.0 trace recording (D-23 step 1; as T021 for a
  non-production 9.0 test server with the 9.0 client; may start any time after T001): proposal,
  explicit approval of exactly that action, read-only run, outputs only in the private directory,
  trace state equal before and after (SC-005), neutral findings in research.md "Spike findings"
- [ ] T050 [US3] **[LIVE] [OWNER]** 9.0 webservice spike (D-23 step 2; as T034 with the 9.0
  `REST_90` client: `getNode()` over the REST client, `CurrentUser.getClientUID()`, node name,
  missing / 0-byte cases where they occur, `Server not in cluster mode.` on a single node): proposal,
  explicit approval, read-only, never production, outputs only in the private directory, neutral
  findings in research.md "Spike findings"
- [ ] T051 [US3] **[OWNER]** **[BLOCKED: T049]** Enable 9.0 trace in **one commit**: neutralize as
  in T033 into `src/test/resources/fixtures/server-log/v9_0/trace/ok.*` (`.origin` "`recorded
  <date> 9.0.x neutralized`"), owner reviews the diff, and `CapabilityMatrix` 9.0
  `TRACE_LOG_READ` → `SUPPORTED` in the same commit; updated first: `ServerLogV90ContractTest`
  (recorded `ok`, US3-1, SC-001), `CapabilityMatrixTest`, `ServerLogFixtureCatalogueTest`,
  `ToolAnnouncementTest` (`V9_0` pin now announces), `ServerLogVersionSelectionTest`
- [ ] T052 [US3] **[OWNER]** **[BLOCKED: T050]** Enable 9.0 webservice in **one commit**: as T046
  for `v9_0/webservice/ok.*` and 9.0 `WEBSERVICE_LOG_READ` → `SUPPORTED`; updated first:
  `ServerLogV90ContractTest`, `CapabilityMatrixTest`, `ServerLogFixtureCatalogueTest`,
  `ToolAnnouncementTest`
- [ ] T053 [US3] **[OWNER]** FR-017 fallback (only for a 9.0 log whose recording T049 / T050
  cannot be made or is declined): record the owner's decision and date in
  `specs/021-server-log-download/quickstart.md` (validation record) and `reviews/implementation.md`;
  that (9.0, log) stays refused `UNSUPPORTED_VERSION` "not yet verified"; T057 and T059 state it in
  the docs and the CHANGELOG; enabling it later is a follow-up change

**Checkpoint**: build green; code review phase 5.

## Phase 6: Polish & cross-cutting concerns

### Live check (gates the feature; SC-001, SC-005; D-24)

- [ ] T054 Live test code (offline part; after T043): `live/ServerLogLiveTest.java` (`@Tag("live")`,
  `-Plive`, production refused by `live/LiveTarget.java`; `INUBIT_LIVE_NODE`,
  `INUBIT_LIVE_LOG_FOLDER=<private folder outside every repository>`, skipped without them; calls
  `download_server_log` for `trace` and, if the group opts in, `webservice` through the wired
  server; asserts the file exists with the reported size and `rw-------`, the result shape, no
  file of the call left after a forced failure (unreachable port override), and that the captured
  server log contains no excerpt line; optional `INUBIT_LIVE_RECORD_DIR` keeps raw StartCLI output
  and helper frames owner-only; prints one `[live] download_server_log <node> <log>: <n> bytes,
  window <lines>` line), `live/ServerLogLiveTestSupport.java`; `docs/live-tests.md` section
  "Server log download (feature 021)" plus a row in the tests table. Failing tests first in
  `live/ServerLogLiveTestSupportTest.java` (env parsing, folder inside a repository refused,
  record-dir permissions)
- [ ] T055 **[LIVE] [OWNER]** 8.1 live check (quickstart "Live check" steps 1–3; after T033, T046,
  T054): after the operator approved the concrete run (non-production 8.1 test node of an
  opted-in test group, private folder), `mvn -Plive -Dtest=ServerLogLiveTest verify`; by hand:
  files `rw-------`, sizes equal the results, the Workbench shows the same trace state as before
  (SC-005), the MCP server log contains no excerpt line; result rows in the quickstart validation
  record (fictitious ids only)
- [ ] T056 **[LIVE] [OWNER]** 9.0 live check (quickstart "Live check" step 4; after T051/T052 or
  T053, T054): as T055 against a non-production 9.0 test node for every 9.0 log enabled; rows in
  the validation record

### Documentation (D-25)

- [ ] T057 [P] `docs/tools.md` (FR-004, D-20, D-25; after T045, T046 and T051/T052 or T053): new section
  `## download_server_log` starting with the tool description of contracts/mcp-tools-delta.md and
  a link to setup "Unsupported functions"; inputs with their limits ("`tailLines` 1–2,000, default
  200", "`contains` 1–200 characters, no control characters, plain text, case-insensitive, matched
  against the masked lines", "`maxChars` 1–100,000, default 20,000"); output and its variants;
  excerpt rules (window = first whole line within the last 50 MiB, masking of the whole window
  before selection, absolute line numbers, oldest lines dropped first); masking rules and the
  limit "a secret-named JSON value opened before the window is not detected"; the known
  credentials of every configured node are masked, and while some are not read yet the excerpt is
  withheld (`kind: withheld`, file still written); folder rules by
  reference to `export_archives` and the file name `<group>-<node>-<log>-<yyyyMMdd-HHmmss>[-n].log`
  (`-2` … `-999`, never replaced); errors table of the contract; memory need (≈ 250 MiB for one
  excerpt, one at a time; waiting for it counts against `cliExportTimeout`); "address cluster
  nodes directly"; "the server reads trace.log fully into its memory"; `cliExportTimeout` for large
  logs; capability table rows (8.1 / 9.0 state as in the
  matrix); "Tool announcement" rule; `list_nodes` names `trace_log_read`, `webservice_log_read`.
  `config/ServerLogDocsTest.java` extended first (short form in `docs/tools.md` and in the tool
  description; the documented 9.0 state matches `CapabilityMatrix.production()`)
- [ ] T058 `README.md` tool table row for `download_server_log` and announcement row (trace:
  a node with a StartCLI installation not pinned to a refusing version; webservice: additionally
  `unsupported.serverLogRead`); `SECURITY.md`: the downloaded log file among the clear-text
  outputs (business data and credentials; only the excerpt is masked); `ServerLogDocsTest`
  asserts the README row (after T057, which extends the same `config/ServerLogDocsTest.java`)
- [ ] T059 [P] `CHANGELOG.md` `[Unreleased]`: Added — `download_server_log` (trace through StartCLI's
  supported trace download; webservice through an unsupported, undocumented INUBIT interface,
  opt-in per group, diagnosis only), `unsupported.serverLogRead`, `list_nodes` capability names
  `trace_log_read` / `webservice_log_read`, `--check-config` line only with an opt-in, the 6.1
  summary's additive "downloading the trace log"; the 8.1 / 9.0 state per log (verified or "not
  yet verified"); Changed (internal, no behaviour change) — helper renamed `ClientHelper`, shared
  helper classes moved to `adapter/cli/helper`

### Full verification

- [ ] T060 Full verification (after all other tasks): `mvn -B clean verify
  -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-021-tmp` and `python3 -I -m unittest discover -s
  tools/tests -v` green; `python3 -I tools/check-identifiers.py --all` and
  `NoCustomerIdentifiersTest` with the private lists green; `unzip -l target/inubit-mcp-server-*.jar`
  shows exactly one `ClientHelper.class` under `META-INF/inubit-mcp/client-helper/` and no
  `queue-entry-helper/` entry; `git diff --stat main...HEAD -- src/test/resources/fixtures/queue-entry`
  empty; SC-002 against the T001 reference (tool list differs only by `download_server_log`; no
  test removed); quickstart offline table checked row by row; final code review over `git diff
  main...HEAD`; findings in `reviews/implementation.md`

## Dependencies

- **Phase 1**: T001 first; T002 any time after T001.
- **Phase 2 blocks every story.** Stage 1: T003 → T004 → T005; T006 ∥ T007 (∥ T003–T005). Then
  T008 → T009; T010 → T011; T012, T013, T017, T020 parallel after the stage-1 checkpoint; T016
  after T010; T013 → T014 → T015 (also needs T010); T018 after T015, T016; T019 after T006, T007.
- **US1** (after Phase 2): T022 ∥ T023 → T024 → T025 → T026 (also needs T012) → T027 → T028 →
  T029 → T031 → T032; T030 after T027, parallel to T028–T029; T031 needs T029 and T030; T033
  **blocked by T021** (recording) and needs T032.
- **US2** (after Phase 2; T043 needs US1's T031): T035 ∥ T036 (T036 needs T004) → T037 → T038;
  T039 needs T005, T036; T040 needs T024; T041 needs T026, T029, T037–T040; T042 needs T009 and
  runs sequentially with T031 and T033 (all three change `MainTest.java`); T043 needs T031, T041;
  T044 needs T043; T045 needs T009 and T042 (same `docs/setup.md`); T046 **blocked by T034** and needs
  T044.
- **US3** (after US1 and US2 code): T047 needs T043; T048 needs T047; T051 **blocked by T049**,
  T052 **blocked by T050**, both need T048; T053 only instead of T051 and/or T052.
- **Recordings** T021, T034, T049, T050 need only T001 and the operator's approval; they run in
  parallel to the offline work. Until T033 / T046 / T051 / T052 the corresponding (version, log)
  stays "not yet verified" in the production matrix.
- **Phase 6**: T054 needs T043; T055 needs T033, T046, T054; T056 needs (T051/T052 or T053), T054;
  T057 and T059 need T046 and (T051/T052 or T053), T057 also T045; T058 needs T057 (same
  `ServerLogDocsTest.java`); T060 needs all others.
- Story completion order: US1 (MVP) → US2 → US3. The feature is complete only when T033, T046 and
  T055 are done and, per 9.0 log, either T051/T052 or T053 (FR-017, SC-001, SC-005).

## Parallel opportunities

- **Phase 2**: T006 and T007 alongside the stage-1 chain T003 → T005; after the checkpoint T008,
  T010, T012, T013, T017, T020 together (then T009, T011, T014, T016, T019).
- **US1**: T022 ∥ T023; T030 alongside T028–T029; the recording T021 alongside everything else.
- **US2**: T035 ∥ T036 ∥ T040; after T036: T037 ∥ T039; T045 (after T009 and T042) alongside US1 or US2
  work that does not touch `docs/setup.md`; T042 is not parallel (shares `MainTest.java` with T031
  and T033); the spike T034 alongside everything else.
- **US3**: T049 ∥ T050 (operator) alongside US1/US2 work.
- **Polish**: T057 ∥ T059, then T058.
- Builds always sequential (one `mvn` at a time, shared tmpdir).

## Implementation strategy

- **MVP = Phase 1, Phase 2, Phase 3 (US1, trace log)**: on an 8.1 node (test matrix offline; production
  after the recording T033) `download_server_log` with `log: trace` writes the trace log and returns
  the masked excerpt; no opt-in needed.
- **Increment 2 = US2**: the web service log behind `unsupported.serverLogRead`, configuration view,
  setup docs; enabled for 8.1 after the spike T034 and T046.
- **Increment 3 = US3**: version proof end to end; 9.0 enabled per log with its recording, or the
  FR-017 fallback.
- **Finish**: live checks, tools/README/SECURITY/CHANGELOG, full verification.
- Ask the operator for the four recording actions (T021, T034, T049, T050) **early** — right after
  T001 — so they never block the offline work; each phase ends green and reviewed; owner and live
  gates are reported as soon as they are reached, not batched at the end.
