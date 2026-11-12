# Quickstart: Validating the Server Log Download

How to check 021 offline (automated) and live (by the operator, against an 8.1 and a 9.0 test
server).

## Prerequisites

- Branch `021-server-log-download` built offline:
  `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-021-tmp` and
  `python3 -I -m unittest discover -s tools/tests`.
- The build contains the renamed helper:
  `unzip -l target/inubit-mcp-server-*.jar | grep META-INF/inubit-mcp/client-helper/` lists exactly
  one `ClientHelper.class` (major version 61, `ClientHelperPackagingTest`); no
  `queue-entry-helper/` entry remains.
- Live only: an INUBIT 8.1 test server with its 8.1 client (JDK 17) and a 9.0 test server with its
  9.0 client (JDK 21) on the MCP host (`defaults.cliClients`); a profile whose test group has
  `unsupported.serverLogRead: true`; neither group `production: true`; a private folder outside
  every repository for the log files (e.g. `~/inubit-logs`).

## Offline validation

| Scenario (spec) | Expected |
|---|---|
| stage 1 refactor: helper renamed, shared classes moved; every 020 test green, 020 fixtures byte-identical | moved `QueueEntryHelperTest` → `ClientHelperTest` etc., `QueueEntryV81ContractTest`, `QueueEntryV90ContractTest`, `PackageBoundaryTest`, `git diff --stat src/test/resources/fixtures/queue-entry` empty |
| no group opts in: tool announced where a client is configured (US1-6, FR-002); call with `log: webservice` → `NOT_CONFIGURED`, no detection, no launch (US2-3) | `ToolAnnouncementTest`, `DownloadServerLogWiringTest` (zero WireMock requests, zero launches) |
| no client home anywhere, or every node pinned to 6.1 → tool not announced; existing tool list otherwise unchanged (SC-002) | `ToolAnnouncementTest`, `MainTest` |
| `unsupported.serverLogRead`: booleans load; `"true"`, `1`, list → error without value; independent of `queueEntryRead` (FR-003) | `ConfigLoaderTest` |
| production group with the opt-in → warning at startup and in `--check-config`; summary group lines only with an opt-in in the profile (US2-6, FR-016) | `ConfigValidatorTest`, `ConfigSummaryTest`, `MainTest` |
| docs contain the FR-004 sentences; tool description has the short form (US2-5, FR-004) | `ServerLogDocsTest` |
| input checks: group id, `log`, `targetFolder` (relative, `..`, network, workspace, config dir, git tree), `tailLines` 0 / 2001, `contains` empty / 201 chars / control char, `maxChars` 0 / 100001 (FR-001, FR-007) | `DownloadServerLogToolTest` (schema), `ServerLogServiceTest`, `TargetFolderRulesTest` (log wording of the cloud warning) |
| trace command: only `trace --download '<path>'`; `on`, `off`, `-l`, `-nl`, `-p`, `-r`, a process id, a second option, a missing `--download` → refused before launch (FR-005, SC-005) | `CliCommandTest` |
| trace via StartCLI: argv, `--execCommand` text, password on stdin only, `cliExportTimeout` named, client per version (US1-1, US3-1, FR-012) | `CliTraceRunnerTest` (`ScriptedProcessLauncher`), `ServerLogVersionSelectionTest` |
| webservice via helper: request on stdin with `op=serverLog`, `log=webservice`, no file name; argv = class name; vendor calls exactly login, `getNode`, `clusterDownload(…, "webservice.log")`, logout (US2-1, FR-006) | `HelperRequestTest`, `ClientHelperTest` (fake vendor classes record every call), `ClientHelperProcessTest` (child JVM) |
| helper `op` optional (default `queueEntry`): the 020 request is encoded byte-identically without `op`; a hand-built request with `op=queueEntry` behaves as without; unknown or repeated `op` → exit 2; named round trips through the real helper for both operations (D-4) | `HelperRequestTest`, `ClientHelperTest` |
| helper `node ok` name taken as `inubitNode`; invalid name → `UNEXPECTED_RESPONSE` (US2-2, D-5) | `WebServiceLogClassifierTest`, `CliWebServiceLogAdapterTest` |
| 8.1 → 8.1 client/`SOAP_81`; 9.0 → 9.0 client/`REST_90`; 6.1, 9.1, undeclared → `UNSUPPORTED_VERSION` naming the supported lines; 9.0 "not yet verified" while unrecorded (US3-1, US3-2, FR-017) | `CapabilityMatrixTest`, `VersionGateTest`, `ServerLogVersionSelectionTest` |
| version not detectable → detection error's code, nothing sent (US3-3) | `ServerLogServiceTest` (`admitWithDetection`) |
| no client / `ibis.jar` / JDK → `CLI_UNAVAILABLE` naming version and setting (US3-4) | `CliTraceRunnerTest`, `CliWebServiceLogAdapterTest` |
| error mapping per channel: login, TLS, network, 403, missing file (`FileWasNotFound`; `not found on`), `Server not in cluster mode.`, no `Trace data (` message, garbage frames (FR-011, D-14) | `CliOutputClassifierTest`, `WebServiceLogClassifierTest`, contract tests |
| timeout: login-credential wait (`GatewayFactory.prepareCredentials` before `serverLogs`, bound = remaining deadline; `PENDING` → `TIMEOUT`, port not called, audit `FAILED`) + slot wait + TLS + process + credential wait for masking + excerpt-permit wait share the call's `cliExportTimeout` deadline; every `TIMEOUT` (slot wait included) names `cliExportTimeout`, never "raise cliTimeout"; process tree killed, private dir deleted, nothing in the target folder; both channels take the per-node slot (FR-013, SC-004, D-15) | `CliServerLogAdapterTest` (`FakeProcessLauncher.hanging()`), `CliConcurrencyLimiterTest`, `ServerLogServiceTest` (permit held elsewhere; `prepareCredentials` answering `PENDING`), `ExcerptPermitTest` |
| maintenance mode, credentials not available, TLS failure per channel: `MAINTENANCE_MODE` (from the detection before launch, not from StartCLI), the node's usual credential error (`AUTH_FAILED`, nothing launched), `TLS_ERROR`; no file, audit `FAILED` (edge case, FR-011) | `CliTraceRunnerTest`, `CliWebServiceLogAdapterTest`, `ServerLogServiceTest` (detection), `ServerLogV81ContractTest` |
| placement: name `<group>-<node>-<log>-<UTC>.log`; existing name → `-2`; concurrent calls get different names; `rw-------`; folder created `rwx------`; no hard links → placeholder + atomic move; neither → `INVALID_INPUT`; injected failures leave no file (US1-5, FR-007, SC-004) | `LogFileWriterTest` (`FileOps` seam, races), `ArchiveFolderWriterTest` unchanged (after `OwnerOnlyFolders` extraction) |
| verbatim file: bytes equal the download, also for Latin-1 and binary content (FR-008) | `ServerLogServiceTest` |
| excerpt: last 200 lines; `contains` → last matching lines with absolute numbers and `matchingLines` (US1-1, US1-2) | `LogExcerptTest` |
| `maxChars` 20,000 with 60,000 chars in 200 lines → oldest dropped, `droppedLines`; one line > `maxChars` → cut, `cutLines: 1` (US1-4) | `LogExcerptTest` |
| 60 MiB log (generated) → window from the first whole line in the last 50 MiB, `wholeFile: false`, numbers count the whole file; no line break in the last 50 MiB → hint (US1-7, D-9) | `LogWindowReaderTest` |
| masking: `Authorization: Basic …`, profile password (also of another configured node, prepared through `ConfiguredSecrets`), every 020 rule, on the whole window before selection; `contains` never matches inside a masked value (US1-3, FR-010, SC-003, D-11) | `SecretTextMaskerLinesTest`, `LogExcerptTest`, `ServerLogServiceTest`, `DownloadServerLogWiringTest` |
| credentials of some node not read yet → file written, `excerpt.kind: withheld` + hint naming node and store and saying that every excerpt is withheld until this source can be read, no log line returned, no excerpt permit taken, audit `EXECUTED` (US1-10, FR-010, D-11) | `ServerLogServiceTest` (fake `ConfiguredSecrets` answering `NotReady`) |
| multi-line `<password>` lines 10–12 → mask token on 10, 11 empty, 12 keeps `</password>` and the text after it, 13 keeps number 13 (US1-8) | `SecretTextMaskerLinesTest` (`secrets_multiline`) |
| end tag without start tag at the window start → every line from the window start up to the end tag masked (placeholder on the first line, the lines before the end-tag line empty); the end-tag line keeps the end tag and the text after it (US1-9) | `SecretTextMaskerLinesTest` (`secrets_window_start`) |
| `mask(String)` of 020 unchanged | `SecretTextMaskerTest` unchanged |
| binary window → no excerpt, size + hint; Latin-1 → `ISO-8859-1`; empty → `kind: empty` + hint; webservice empty, also with presence `UNKNOWN` → hint "web service logging may be disabled" (edge cases, US2-4) | `LogExcerptTest`, `ServerLogServiceTest` |
| `contains` without match → file written, empty excerpt, hint | `ServerLogServiceTest` |
| audit: `REFUSED` for a refusal, `PENDING` + `EXECUTED`/`FAILED` otherwise, inputs without `contains` text, details with file and size; unwritable `PENDING` → `INTERNAL`, nothing sent (FR-015) | `ServerLogAuditTest`, `ServerLogServiceTest` |
| logging: one INFO line; no excerpt line, no `contains`, no StartCLI/helper output at any level (FR-015, SC-003) | `ServerLogLoggingTest` (captured logback) |
| 50 MiB synthetic log: window read + scrubber scan of configured passwords + masking + selection within 20 s; one excerpt at a time (D-13) | `LogExcerptPerformanceTest` (`application`), `ExcerptPermitTest` |
| contract 8.1 / 9.0: recorded neutralized StartCLI output and helper frames with fictitious log content → result shape, sizes, node name, missing/empty mapping (FR-017) | `ServerLogV81ContractTest`, `ServerLogV90ContractTest`, `ServerLogFixtureCatalogueTest` (`SUPPORTED` ⇔ recorded `ok.origin`) |
| package boundaries; helper references only `java/*` | `PackageBoundaryTest` |
| fictitious names in every fixture and doc (FR-018) | `NoCustomerIdentifiersTest` with the private lists, `tools/check-identifiers.py` |

## Early live tasks (operator approval; read-only; never production)

Run before the adapters are built (research D-23); each step only after the operator approved the
concrete action in the chat.

1. **Trace recording**, per version: note the trace state in the Workbench; run StartCLI
   `trace -d <private dir>/trace.log` with the configured client; keep stdout, stderr and exit
   code in a private directory outside the repository; check the trace state again (SC-005).
2. **Webservice spike**, per version: the throw-away program performs login, `getNode`,
   `clusterDownload(<private dir>/webservice.log, node, "webservice.log")`, logout, and records
   the frames-equivalent output (exception chains and texts, node name, size). A missing-file
   answer is recorded only where the test server has no `webservice.log`.
3. Neutralize (research D-21): replace the log content by fictitious content, adjust sizes, run
   the identifier checks; the owner reviews the diff; append the findings to research.md
   ("Spike findings").

## Live check (operator; read-only; never production)

1. Configure `INUBIT_LIVE_NODE=test/node1` (8.1), `INUBIT_LIVE_LOG_FOLDER=~/inubit-logs/021-live`.
2. `mvn -Plive -Dtest=ServerLogLiveTest verify` → `[live] download_server_log test/node1 trace:
   <n> bytes, window <lines>` and, with the opt-in, the same for `webservice` with the INUBIT node
   name.
3. Check by hand: the files exist with `rw-------`; their sizes match the results; the Workbench
   (or `get_health` with `includeSystemInfo`) shows the same trace state as before (SC-005); the server log of the MCP server contains no
   excerpt line.
4. Repeat with a 9.0 node. Record the outcome below.

## Validation record

| Date | Version | Log | Result | Notes |
|---|---|---|---|---|
| 2026-11-12 | 8.1.17 | trace | pass | T055 on the non-production 8.1 test node (`test/node1` here); about 79 MiB, window 340,215 lines; file `rw-------`, size equal to the result; trace state unchanged before and after (get_health systemInfo: tracing enabled, level info; SC-005); captured server log without excerpt lines; forced failure left the folder unchanged |
| 2026-11-12 | 8.1.17 | webservice | pass | T055, same node and run, opt-in set in a private copy of the profile (the installed release 0.9.1 rejects the new key); about 21.4 MiB, window 382,917 lines, INUBIT node named in the result; file `rw-------`, size equal to the result; captured server log without excerpt lines |
| | 9.0.x | trace | | |
| | 9.0.x | webservice | | |
