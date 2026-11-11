# Implementation Plan: Download Server Log Files (trace.log, webservice.log)

**Branch**: `021-server-log-download` | **Date**: 2026-11-11 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/021-server-log-download/spec.md`. Base: `main` at
0.9.1; target `[Unreleased]` (next minor).

## Summary

A tool `download_server_log` writes `trace.log` or `webservice.log` of one server verbatim into a
local folder and returns a masked, bounded excerpt with absolute line numbers:

- **trace** through StartCLI's documented `trace --download '<file>'` on the existing StartCLI
  channel; `CliCommand` allows `trace` only with `--download` (D-1). No opt-in.
- **webservice** through the feature-020 helper with a second operation that performs the
  Workbench Cluster Manager download: login, `ClusterClient.getNode()`, `clusterDownload(file,
  node, "webservice.log")` with the file name fixed inside the helper, logout (D-2, D-4, D-5). Only
  for groups with `unsupported.serverLogRead` (D-17).
- Stage 1 is a behaviour-preserving refactor: the helper becomes `ClientHelper` and the shared
  helper adapter classes move to `adapter/cli/helper`; wire ids and 020 fixtures stay (D-3).
- Both channels download into a private temporary directory under one `cliExportTimeout` deadline and a
  per-node StartCLI slot (D-7, D-15); the excerpt is built from the private copy (window = last 50
  MiB from the first whole line, 020 binary rule, line-preserving masking of the whole window
  incl. known credentials, selection, oldest-first dropping, D-9 … D-13); then `LogFileWriter`
  places the file with the `export_archives` folder rules and an atomic hard-link name claim
  (`-2`, `-3`, …) that never replaces a file and never shows partial content (D-7).
- Two capabilities `trace_log_read` / `webservice_log_read`: 8.1 and 9.0 each supported only after
  its recording, other and unknown versions refused, undetectable versions fail with the
  detection error (D-6, D-22, D-23).
- Every call is audited without content or search text; annotated as a local write (D-18, D-20).

## Technical Context

**Language/Version**: Java 21 (server); the helper Java 17 bytecode (`release 17`), JDK classes
only (unchanged rule of 020).

**Primary Dependencies**: unchanged (MCP Java SDK, Jackson, logback); no new dependency. The INUBIT
client installation (proprietary) is used at runtime by StartCLI and, through reflection, by the
helper.

**Storage**: the placed log file in the caller's folder (`rw-------`); per call a private
temporary directory `inubit-mcp-log-*` (deleted at the end of the call and on shutdown); audit
records in the existing audit log.

**Testing**:
- JUnit 5, AssertJ, WireMock (version detection), `ScriptedProcessLauncher` / `FakeProcessLauncher`
  (StartCLI and helper launches, `onLaunch` writes fixture files), in-process helper tests with
  fake vendor classes under `src/test/java/com/inubit/ibis/…` (020 fakes + `ClusterClient`, `Node`,
  `NodeName`, `CurrentUser`), one real child-JVM helper test;
- contract tests per version and log against `src/test/resources/fixtures/server-log/{v8_1,v9_0}/`
  (recorded, neutralized, fictitious log content);
- `@Tag("live")` `ServerLogLiveTest` (opt-in, never production);
- baseline: the 0.9.1 suite green (`mvn -o clean verify`).

**Target Platform**: macOS, Linux (live-checked); Windows by construction (argument arrays,
`CliPaths` Windows rule, `java.exe` for the helper; hard links on NTFS).

**Project Type**: single Maven module, shaded JAR; the helper's compiler execution is renamed.

**Performance Goals**: one call = version check (cached) + one StartCLI run (trace) or one TLS
check + one helper JVM (webservice), bounded by `cliExportTimeout`; excerpt of a 50 MiB window within
20 s on CI (linear masker); line counting of a larger prefix streams at disk speed.

**Constraints**:
- FR-005/FR-006: exactly one StartCLI form; exactly four vendor calls; no caller input reaches a
  file name sent to INUBIT;
- FR-007/SC-004: never replace a file, never content under the final name before it is complete,
  nothing left after an error;
- FR-010/SC-003: masking on the whole window before selection, line numbers exact; no content or
  `contains` in logs or audit;
- FR-013: `cliExportTimeout` per call (built-in 120 s), per-node slot for both
  channels; other StartCLI runs unchanged;
- memory: one excerpt at a time, ≈ 250 MiB peak (D-13); the INUBIT server itself reads
  `trace.log` fully into its heap (vendor behaviour, documented);
- `PackageBoundaryTest`: `application` only via ports (known credentials as
  `Function<String, List<SecretSpan>>`), `Process*` only in `adapter/cli`, the helper references
  only `java/*`;
- customer-neutral artifacts only (FR-018, CONTRIBUTING).

**Scale/Scope**:
- 1 new tool (`download_server_log`), 2 new schemas, `list_nodes.output.json` enum +2;
- new production types ≈ 20 (domain ≈ 8, application 7, adapter 5, tool 1);
- moved/renamed (stage 1): `QueueEntryHelper` → `ClientHelper`, `HelperInstallation`,
  `HelperTrustStore`, `HelperRequest`, `HelperFrames`, `QueueEntryVariant` → `HelperVariant`,
  extracted `HelperFailures`, `OwnerOnlyFolders`;
- changed: `UnsupportedConfig`, `Defaults`, `ConfigLoader`, `ConfigValidator`, `ConfigSummary`,
  `EffectiveNodeConfig`, `Capability`, `CapabilityMatrix`, `VersionGate`, `NodeOverview`,
  `SecretTextMasker`, `TargetFolderRules`, `ArchiveFolderWriter`, `Gateway`, `GatewayFactory`,
  `PortSet` family, `CliCommand`, `CliOutputClassifier`, `CliResources`,
  `CliConcurrencyLimiter` (doc), `QueueEntryHelperClassifier`, `Wiring`, `pom.xml`, docs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.* Constitution v1.1.0.

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ⚠️ the StartCLI `trace` command also switches tracing; the internal download takes any file name; a local file with clear-text content | ✅ <ul><li>Nothing changes on INUBIT: `CliCommand` admits `trace` only with `--download` (the `-d` branch returns before every state-changing branch, bytecode); the helper calls only login, `getNode`, `clusterDownload`, logout, with `webservice.log` as a constant (D-1, D-2, D-4; fake-vendor test records every call); SC-005 checked live.</li><li>Not a state-changing tool on INUBIT, so no write gate (FR-002); annotated `localWrite` (`readOnlyHint: false`, not destructive, not idempotent) so clients confirm the local file (FR-014).</li><li>The unsupported part is off by default (group opt-in, `NOT_CONFIGURED` before any request, production warning, D-17).</li><li>All inputs validated before any call (schema + service, folder rules); nothing reaches a shell (argument arrays, `--execCommand` quoting via `CliCommand`, helper request on stdin).</li></ul> |
| II. Secrets Stay Out | ⚠️ logs contain credentials; StartCLI and the helper need credentials | ✅ <ul><li>Credentials only on stdin of StartCLI / the helper (existing mechanisms, D-16).</li><li>The excerpt is masked on the whole window (020 rules + the profile's known credentials, line-preserving) before selection; `ResultMapper` scrubs again (D-11).</li><li>The log file is clear text by design and is not part of the result (as `export_archives`); the description and docs say so (FR-004).</li><li>No content, excerpt or `contains` in logs or audit; messages are fixed or scrubbed texts (D-14, D-18).</li></ul> |
| III. Test-First | ✅ | ✅ <ul><li>Every stage starts with failing tests (quickstart table).</li><li>Dedicated tests for the single allowed `trace` form, the four vendor calls, the opt-in refusal (no request), each masking rule incl. multi-line and window-start, no secret in argv/env/log/audit, no file after failures.</li><li>Offline only: fakes, scripted StartCLI, fake vendor classes, WireMock; live test opt-in, production refused.</li></ul> |
| IV. Use-Case-Oriented Tools | ✅ "why did it fail" (US1), "what did the connector send" (US2) | ✅ one tool with `log` choice, task-oriented description incl. warnings, typed input schema, documented output shape (contracts/mcp-tools-delta.md). |
| V. Adapter Isolation & Version Compatibility | ⚠️ an internal interface; no recordings yet | ✅ <ul><li>StartCLI (supported) for trace; the CLI-side helper only for what no documented channel offers (static evidence: `downloadFile` answers "Unsupported request" for `webservice.log`).</li><li>Version choice in `CapabilityMatrix` and `HelperVariant`, never in tool logic (D-6).</li><li>Argument arrays, explicit timeout, exit-code + output/frame evaluation, tested parsers (D-1, D-4, D-14).</li><li>8.1 and 9.0 declared supported per log only with recorded, neutralized contract fixtures; recording and matrix flip in one commit; missing recordings → "not yet verified" (D-22, D-23) — no deviation.</li></ul> |
| VI. Bounded, Structured Output | ✅ | ✅ large content goes to a file whose path is returned; excerpt ≤ `tailLines` (2,000) lines and ≤ `maxChars` (100,000) with an explicit truncation marker; scan window ≤ 50 MiB; fixed JSON shape; actionable errors (contracts). |
| VII. Observability & Auditability | ✅ (not state-changing on INUBIT; FR-015 audits anyway) | ✅ audit record per call (`REFUSED` / `PENDING` → `EXECUTED`/`FAILED`) with sanitized inputs; one structured INFO line; content never logged (D-18). |
| Tech constraints: TLS for CLI connections; server verifies pins itself | ✅ existing for StartCLI; helper per 020 D-7 | ✅ trace: StartCLI's TLS handling and pin probe unchanged; webservice: the server verifies and gives the helper only the verified leaf (D-16). |
| Tech constraints: language "Java 21 or newer" | ⚠️ the helper runs in the 8.1 client's JDK 17 | ⚠️ existing justified deviation of 020, unchanged in kind: only `ClientHelper` is compiled with `release 17` → Complexity Tracking. |
| Tech constraints: dependencies minimal, single artifact | ✅ | ✅ no new dependency or plugin; helper stays a resource in the same JAR. |

**Result**: PASS with one justified (carried-over) deviation (helper bytecode Java 17; Complexity
Tracking).

### Open questions for the owner (do not block the plan; defaults chosen)

1. **Timeout setting** (FR-013): decided — `cliExportTimeout` (built-in 120 s) instead of
   `cliTimeout` (30 s), since a log download is a transfer like an export; spec FR-013 changed.
2. **SC-002 reading**: `trace_log_read` needs no opt-in, so `list_nodes` lists it for every node
   with a client and the 6.1 `cliOnly` summary lists it as refused (additive, like
   `partner_management` in 018; golden texts with capability lists change by one name). Default:
   accept the additive change; the alternative (hide the capability) would make `list_nodes`
   inconsistent with the tool list.

## Project Structure

### Documentation (this feature)

```text
specs/021-server-log-download/
├── plan.md
├── research.md                 # D-1…D-25 (+ "Spike findings" after D-23)
├── data-model.md
├── quickstart.md               # offline table, early live tasks, live check, validation record
├── contracts/
│   ├── configuration-delta.md
│   └── mcp-tools-delta.md
├── checklists/requirements.md
├── reviews/                    # spec-r1…r3, plan reviews
└── tasks.md                    # /speckit-tasks
```

### Source Code (repository root)

```text
pom.xml                                   # execution compile-client-helper (release 17), output client-helper/
src/helper/java/de/dadecker/inubit/mcp/helper/
└── ClientHelper.java                     # renamed from QueueEntryHelper; + op=serverLog
src/main/java/de/dadecker/inubit/mcp/
├── config/              # UnsupportedConfig (+serverLogRead), Defaults, ConfigLoader (strict boolean),
│                        # EffectiveNodeConfig (serverLogRead), ConfigValidator (production warning),
│                        # ConfigSummary (group line, cliOnly filter)
├── domain/model/        # Capability (+TRACE_LOG_READ, +WEBSERVICE_LOG_READ), CapabilityMatrix,
│                        # SecretTextMasker (+maskLines), ServerLogKind, LogPresence, LogWindow,
│                        # ExcerptLine, Truncation, LogExcerpt, ServerLogBounds, ServerLogResult
├── domain/port/         # ServerLogPort + DownloadedLog (new), Gateway, GatewayFactory (+default serverLogs)
├── application/         # ServerLogService, ServerLogRequest, ServerLogAudit, LogWindowReader,
│                        # LogFileWriter, OwnerOnlyFolders (extracted), ExcerptPermit (new);
│                        # TargetFolderRules (cloud wording), ArchiveFolderWriter (delegates),
│                        # VersionGate (lines, cliOnlyStep), NodeOverview (names)
├── adapter/             # PortSet, V81PortSet, V81Gateway, AdapterGatewayFactory, StaleReportingPorts,
│                        # RefusingPorts (+ServerLogPort)
├── adapter/cli/         # CliCommand (trace --download), CliTraceRunner (new), CliOutputClassifier
│                        # (FileWasNotFound), CliResources (LOG_PREFIX), CliConcurrencyLimiter (doc)
├── adapter/cli/helper/  # new package: HelperInstallation, HelperTrustStore, HelperRequest (+op),
│                        # HelperFrames (+node, +part log), HelperVariant, HelperFailures (moved/extracted)
├── adapter/cli/queue/   # CliQueueEntryAdapter, QueueEntryHelperClassifier (uses HelperFailures), WfspParser
├── adapter/cli/log/     # new: CliServerLogAdapter, CliWebServiceLogAdapter, WebServiceLogClassifier
├── mcp/tools/           # DownloadServerLogTool (new)
└── Wiring.java          # service, adapters, announcement, allowed(), known-span function
src/main/resources/schemas/download_server_log.{input,output}.json, list_nodes.output.json
src/test/java/…          # tests per quickstart; com/inubit/ibis/server/cluster/… + common/CurrentUser fakes;
                         # live/ServerLogLiveTest
src/test/resources/fixtures/server-log/{v8_1,v9_0,excerpt}/, README.md
docs/setup.md, docs/tools.md, docs/live-tests.md, README.md, SECURITY.md, CHANGELOG.md
```

**Structure Decision**: the same single module. Stages, each ending with a green
`mvn -o -q clean verify` and reviewed:

1. **Refactor (no behaviour change)**: `QueueEntryHelper` → `ClientHelper` (pom execution and
   resource path), shared helper adapter classes → `adapter/cli/helper`, `QueueEntryVariant` →
   `HelperVariant`, `HelperFailures` extracted, `OwnerOnlyFolders` extracted from
   `ArchiveFolderWriter`; 020 and 015 tests green, 020 fixtures untouched (D-3, D-7).
2. **Early live tasks (operator)**: trace recording and webservice spike on 8.1 and 9.0 test
   servers after approval; neutralized fixtures and "Spike findings" in research.md (D-21, D-23).
   May run in parallel with stages 3–5.
3. **Configuration**: `unsupported.serverLogRead` binding, strictness, effective value,
   production warning, summary line (FR-003, FR-016).
4. **Capabilities and gate**: two capabilities, matrix states ("not yet verified" until the
   recordings), unknown map, `VersionGate` lines and steps, `NodeOverview`, `list_nodes` enum,
   `cliOnly` filter, `allowed()`, announcement rule (FR-002, US3).
5. **Domain logic**: `SecretTextMasker.maskLines` (rule by rule, multi-line, window start, known
   spans), `LogExcerpt` (selection, numbers, `contains`, `maxChars`), bounds (pure tests).
6. **Local files**: `LogWindowReader` (50 MiB window, line counting), `LogFileWriter` (claim,
   fallback, failures, races), `TargetFolderRules` wording, `ExcerptPermit`.
7. **Trace channel**: `CliCommand` `trace`, `CliOutputClassifier` rule, `CliResources` prefix,
   `CliTraceRunner`.
8. **Helper and webservice channel**: `ClientHelper` `op=serverLog` with fake vendor classes,
   child-JVM test, `HelperRequest`/`HelperFrames` extension, `WebServiceLogClassifier`,
   `CliWebServiceLogAdapter`, `CliServerLogAdapter` (deadline, slot), port wiring.
9. **Service, audit and tool**: `ServerLogService`, `ServerLogAudit`, `DownloadServerLogTool`,
   schemas, announcement, logging test.
10. **Fixtures and contracts**: recorded 8.1/9.0 cases from stage 2, reconstructed cases,
    catalogue test, contract tests; matrix flipped per recorded (version, log).
11. **Docs**: setup (FR-004 text), tools, live tests, README, SECURITY, CHANGELOG;
    `ServerLogDocsTest`.
12. **Live check** (operator): `ServerLogLiveTest` on 8.1 and 9.0; validation record in
    quickstart.md.

### Touch points with other work

- **020** (helper, adapter classes, masker, classifier): renamed and moved in stage 1 without
  behaviour change; the wire ids `qe=1` / `#QE1:` stay, so `fixtures/queue-entry/` is unchanged.
- **015** (`TargetFolderRules`, `ArchiveFolderWriter`, `ArchiveExportAudit`): folder creation
  extracted and shared; the cloud warning wording becomes a parameter, the archive wording stays.
- **017** (`cliClients`, `select`, `checkLaunch`, StartCLI TLS): reused unchanged.
- **012** (6.1): 6.1 nodes refuse through the matrix and `RefusingPorts`; the 6.1 summary gains
  "downloading the trace log" under refused (owner question 2).

## Complexity Tracking

One justified deviation (carried over from 020) and three deliberate design weights:

| Item | Why needed | Simpler alternative rejected because |
|---|---|---|
| Helper compiled with `release 17` (technology constraint "Java 21 or newer"), now `ClientHelper` | 8.1's client runs on JDK 17; vendor classes must not enter the server JVM | in-JVM class loading mixes vendor state with ours and cannot be killed per call (020 D-1/D-2) |
| Stage 1 rename/move of the 020 helper code | one helper with two operations; shared classes must not live in one operation's package | a second helper class duplicates ~300 lines and a second resource; importing `adapter/cli/queue` from `adapter/cli/log` misleads (D-3) |
| Placement via private copy + hard-link claim + placeholder/atomic-move fallback | FR-007: atomic name claim, never replace, never partial content under the final name, any target path | StartCLI/vendor writing into the folder leaves partial files and needs `CliPaths`-safe paths; `ArchiveFolderWriter`'s direct-write fallback shows partial content (D-7) |
| Process-wide single permit for excerpt building | a 50 MiB window needs ≈ 250 MiB at peak; per-node slots do not bound calls across nodes | unbounded concurrency risks the server heap; a smaller window would contradict FR-009 (D-13) |
