# Quickstart: Validating the Queue Entry Read

How to check 020 offline (automated) and live (by the operator, against an 8.1 and a 9.0 test
server).

## Prerequisites

- Branch `020-queue-entry-read` built offline:
  `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-020-tmp` and
  `python3 -I -m unittest discover -s tools/tests`.
- The build must contain the helper:
  `unzip -l target/inubit-mcp-server-*.jar | grep META-INF/inubit-mcp/queue-entry-helper/` lists
  exactly one `QueueEntryHelper.class`; `QueueEntryHelperPackagingTest` checks its class-file
  major version 61 (Java 17).
- Live only: an INUBIT 8.1 test server with its 8.1 client (JDK 17) and a 9.0 test server with its
  9.0 client (JDK 21) on the MCP host, configured as for StartCLI (`defaults.cliClients`); a
  profile whose test group has `unsupported.queueEntryRead: true`; neither group
  `production: true`.

## Offline validation

| Scenario (spec) | Expected |
|---|---|
| no group opts in: tool list and every tool unchanged (US2-1, SC-002) | `ToolAnnouncementTest`, `MainTest` (exact tool list), `ListNodesToolTest` (no `queue_entry_read`), full suite green |
| opt-in on `test`, not on `prod`: tool announced; call for `prod/node1` → `NOT_CONFIGURED`, no request, no detection (US2-2, FR-002) | `ToolAnnouncementTest`, `ReadQueueEntryWiringTest` (zero WireMock requests, no launch) |
| announcement needs opt-in + not pinned to 6.1/other line + client home (FR-002); fallback: `V9_0` pin does not announce | `ToolAnnouncementTest` |
| `unsupported.queueEntryRead`: booleans load; `"true"`, `1`, list → error without value; other key under `unsupported`, key on node or in `defaults` → unknown key (FR-003) | `ConfigLoaderTest` |
| production group with opt-in → warning at startup and in `--check-config`; with an opt-in in the profile every group line ends with `queueEntryRead: off/enabled (unsupported)`; without any opt-in the summary is byte-for-byte unchanged (US2-3, US2-5, FR-017, SC-002) | `ConfigValidatorTest`, `ConfigSummaryTest` (existing golden texts unchanged), `MainTest` |
| docs contain the FR-004 sentences; tool description has the short warning (US2-4, FR-004) | `QueueEntryDocsTest` |
| input checks: `target` group id, `processId`, `fileName` (`/`, `\`, NUL, control chars, 256 chars), `maxChars` 0 / 100001 (FR-001) | `ReadQueueEntryToolTest` (schema), `QueueEntryServiceTest` |
| 6.1 golden texts of `--check-config` unchanged after adding the capability; a 6.1 group with the opt-in lists it as refused (SC-002) | `ConfigSummaryTest`, `MainTest` (T003) |
| queue rows from the recorded 8.1 answers (`log_queueLog_error.json`, `log_queueLog.json`): file name, size, error texts, Error state | `QueueEntryRowsContractTest` |
| missing credentials → the node's usual credential error, no content request | `QueueEntryServiceTest`, `CliQueueEntryAdapterTest` |
| lookup through queueLog: one ERROR row among others read; none → `NOT_FOUND`; rows without ERROR → `INVALID_INPUT` with states; two ERROR rows → `INVALID_INPUT` listing both, then with `fileName` read (US1-7, FR-005, edge cases) | `QueueEntryServiceTest`, `QueueEntryRowParserTest` (WireMock queueLog fixtures) |
| 8.1 node → `SOAP_81` with the 8.1 client and JDK; 9.0 → `REST_90` with the 9.0 client; detected 6.1 / 9.1 / undeclared version → `UNSUPPORTED_VERSION`, no helper launched (US3-1…3, FR-006) | `QueueEntryVersionSelectionTest` (FakeProcessLauncher: command, class path, JDK, variant), `QueueEntryServiceVersionTest`, `CapabilityMatrixTest`, `VersionGateTest` |
| version not detectable: detection fails with `UNREACHABLE` / `TIMEOUT` / `TLS_ERROR` / `AUTH_FAILED` → the call fails with that code and message; during the back-off → the recorded code; no content request, no extra login (US3-4) | `VersionGateTest` (`admitWithDetection`: fresh failure rethrown, back-off replays the recorded code, other callers unchanged), `QueueEntryServiceTest` (no helper launched) |
| no client home / no `lib/ibis.jar` / no JDK / class path separator in a path → `CLI_UNAVAILABLE` naming version and setting (US3-5) | `CliQueueEntryAdapterTest` |
| helper protocol: request on stdin only (argv = class name), password never in argv, env or logs; frames with nonce; vendor noise ignored; forged frame without nonce ignored (FR-015, D-4) | `HelperRequestTest`, `HelperFramesTest`, `QueueEntryHelperTest` (in-process, fake vendor classes), `QueueEntryHelperProcessTest` (real child JVM) |
| error mapping: login rejected, TLS, network, 403, missing file/workflow, API missing, garbage, no `end`, over 1 MiB (FR-013) | `QueueEntryHelperClassifierTest` (fixtures), `CliQueueEntryAdapterTest` |
| TLS: handshake with trust store / pin / hostname setting before launch; trust store holds only the verified leaf; mismatch → `TLS_ERROR`, nothing launched; `http` → no store (FR-014) | `TlsPeerTest`, `HelperTrustStoreTest`, `CliQueueEntryAdapterTest` (`HandshakeStartCliPinProbeTest` unchanged) |
| private temp dir owner-only, removed after success, failure, timeout and shutdown (D-8) | `CliQueueEntryAdapterTest`, `CliResourcesTest` |
| contract 8.1: recorded neutralized `error_entry` → metadata, text, 51 variables, system flags (US1-1, FR-008, FR-010, FR-019) | `QueueEntryV81ContractTest` |
| contract 9.0: recorded `error_entry` (or, under the fallback, refusal "not yet verified") (FR-019) | `QueueEntryV90ContractTest`, `QueueEntryFixtureCatalogueTest` (9.0 `SUPPORTED` ⇔ recorded `.origin`) |
| text cut at `maxChars` with `truncated`, `totalChars`, `sizeBytes`; binary ZIP/PDF/… → size, kind, hint; UTF-16 → binary; Latin-1 → `ISO-8859-1`; empty → `kind: empty` (US1-2, US1-3, FR-009) | `EntryContentClassifierTest`, `QueueEntryServiceTest` |
| masking: credential-named variables, `<wsse:Password>`, attribute, JSON key (string, object), `name=value` quoted and unquoted, header line, `Authorization`, Basic/Bearer, cut-off start tag, self-closing element, known profile credential (US1-4…6, FR-011, SC-003) | `SecretTextMaskerTest` (one test per rule), `QueueEntryServiceTest` (scrubber), `QueueEntryLoggingTest` (no value in the captured log) |
| variables: 250 → 200 + `totalCount`; value > 2,000; 40,000 budget; name > 200; binary value (US1-9, FR-010) | `QueueEntryServiceTest` |
| data > 10 MiB → data not requested, unavailable "too large"; `.wfsp` > 10 MiB → variables unavailable (FR-010) | `QueueEntryServiceTest`, `CliQueueEntryAdapterTest` |
| variables missing while data present → partial result with reason; wfsp malformed → variables unavailable "unexpected format" (US1-8, FR-012) | `QueueEntryV81ContractTest` (`missing_variables`), `WfspParserTest` |
| content "not found": row gone on re-read → `NOT_FOUND`; row still there → partial (edge cases) | `QueueEntryServiceTest` |
| timeout: slot wait and helper share `cliTimeout`; `TIMEOUT`, no partial content, tree killed; helper runs take the per-node StartCLI slot, 8.1/9.0 StartCLI runs unchanged (US1-10, FR-016) | `CliQueueEntryAdapterTest` (`FakeProcessLauncher.hanging()`), `CliConcurrencyLimiterTest` |
| logging: one INFO line with node, process ID, file name, sizes, outcome; no content at any level (FR-018) | `QueueEntryLoggingTest` |
| no write: the helper calls only login, `MultipartDownloadThread`, logout (FR-007) | `QueueEntryHelperTest` (fake vendor client records every call) |
| package boundaries; helper references only `java/*` | `PackageBoundaryTest` |
| fictitious names in every fixture and doc (FR-020) | `NoCustomerIdentifiersTest` with the private lists, `tools/check-identifiers.py` |
| `CliRunner` behaviour after the `ProcessRun` extraction | existing `CliRunnerTest`, `CliRunnerWindowsSelectTest`, `CliExportRunnerTest` green unchanged |

## Live check (operator; read-only; never production)

1. **Prepare** an ERROR entry on each test server and note `processId` (and `fileName` if the
   process has several ERROR entries) from `find_processes` or `query_logs` (`logType:
   queueLog`). 9.0: if no ERROR entry exists, the operator creates one (e.g. a test workflow whose
   Throw module ends in the Queue Manager with a fictitious payload and a variable `apiToken`);
   the agent does this only after the operator approved that concrete action in the chat
   (FR-019).
2. **Run** per server (8.1 and 9.0):

   ```bash
   INUBIT_MCP_PROFILE=acme INUBIT_LIVE_NODE=test/node1 \
   INUBIT_LIVE_QUEUE_ENTRY=1234 \
   mvn verify -Plive -Dtest=QueueEntryLiveTest
   ```

   Optional, to produce the fixture recording: `INUBIT_LIVE_RECORD_DIR=<private directory outside
   the repository>`.
3. **Expect**: `[live] read_queue_entry test/node1 process 1234: data <n> bytes (<kind>), <m>
   variables, entry unchanged`; the test asserts that state, `startTime`, `fileSize` and
   `fileName` of the row are the same before and after (SC-004) and that the captured log holds
   no returned value (SC-003).
4. **Through a client** (optional): call `read_queue_entry` with that target and process ID; the
   result shows the data stream and the variables with `${secret:…}` placeholders; a call for a
   group without the opt-in returns `NOT_CONFIGURED`.
5. **Record** (once per version): neutralize the files from `INUBIT_LIVE_RECORD_DIR` as in
   research D-18 (`tools/synthesize_names.py`, `tools/neutralize.py`, manual replacement of
   business values, `NoCustomerIdentifiersTest`), copy them to
   `src/test/resources/fixtures/queue-entry/v<8_1|9_0>/error_entry.*` and the negative case
   (non-existent file name of the existing workflow) as `not_found_file.*`, with `.origin`
   `recorded <date> <version> neutralized`; the owner reviews the diff; for 9.0 the recording and
   the matrix flip go into one commit (`QueueEntryFixtureCatalogueTest`).
   Then the operator removes the 9.0 test entry in the Workbench.
6. **Fallback** (no 9.0 test server or the operator declines): 9.0 stays refused "not yet
   verified" (FR-019); note it in the validation record below.

## Validation record

| Date | Server (fictitious id) | INUBIT | Result |
|---|---|---|---|
| — | — | 8.1.x | pending |
| — | — | 9.0.x | pending |
