# Implementation log: 020-queue-entry-read

Findings, decisions and checkpoints of the implementation phase (`d4ld4r-speckit-duo:implement`).

## T001 – Baseline (2026-11-10)

Branch `020-queue-entry-read` at `babf4a3`, before any implementation change.
JDK 25.0.5, Maven 3.10.0, macOS.

| Check | Command | Result |
|---|---|---|
| Java build | `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-020-tmp` | green (exit 0) |
| Python tools | `python3 -I -m unittest discover -s tools/tests` | green, 411 tests OK |

Test count (SC-002 reference, from `target/surefire-reports` and `target/failsafe-reports`):

| Suite | Classes | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|
| surefire | 362 | 5495 | 0 | 0 | 52 |
| failsafe | 1 | 2 | 0 | 0 | 0 |
| **total** | 363 | **5497** | 0 | 0 | 52 |

Later checkpoints ("test count ≥ T001") compare against 5497 tests (surefire + failsafe), 0 failures,
0 errors.

## Phase 2 checkpoint (T002–T008, 2026-11-10)

Tasks T002–T008 approved in round 1 each (reviews `T002-r1.md` … `T008-r1.md`; minor findings only).

| Check | Command | Result |
|---|---|---|
| Java build | `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=<tmp outside the checkout>` | green (exit 0) |
| Python tools | `python3 -I -m unittest discover -s tools/tests` | green, 411 tests OK |

Test count: surefire 368 classes, 5580 tests, 0 failures, 0 errors, 52 skipped; failsafe 2 tests;
total 5582 ≥ 5497 (T001).

Phase 2 code review over `git diff d4fef07 6090947`: no findings, APPROVED.

Environment notes: run Maven with `/opt/homebrew/bin` first on `PATH` (a bundled git without
templates makes `GitCliTest` fail); the tmpdir must lie outside the checkout
(`DeploySecretLeakTest` scans the checkout's history).

## Phase 3 checkpoint (T009–T026, 2026-11-10)

Tasks T009–T026 approved (reviews `T009-r1.md` … `T026-r1.md`, multi-round where noted). T027
(8.1 recording) is **[OWNER]**-gated and open: the neutralization diff needs the owner's review.

| Check | Command | Result |
|---|---|---|
| Java build | `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=<tmp outside the checkout>` | green (exit 0) |
| Python tools | `python3 -I -m unittest discover -s tools/tests` | green, 411 tests OK |

Test count: surefire 389 classes, 6211 tests, 0 failures, 0 errors, 52 skipped; failsafe 2
classes, 3 tests; total 6214 ≥ 5582 (phase 2).

Phase 3 code review over `git diff 95cb080 232a1d2`: no findings.

## Phase 4 checkpoint (T028–T030, 2026-11-10)

Tasks T028–T030 approved (T028 and T030 in round 2, T029 in round 1).

| Check | Command | Result |
|---|---|---|
| Java build | `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=<tmp outside the checkout>` | green (exit 0) |
| Python tools | `python3 -I -m unittest discover -s tools/tests` | green, 411 tests OK |

Test count: surefire 391 classes, 6238 tests, 0 failures, 0 errors, 52 skipped; failsafe 2
classes, 3 tests; total 6241 ≥ 6214 (phase 3).

SC-002 comparison: see "T030 notes" (v0.8.0 vs HEAD, same scripted stdio session; `initialize` and
`list_nodes` data byte-identical, tool names and order identical, only the planned additive
`list_nodes` output-schema enum value differs).

Phase 4 code review over `git diff a0d7c86 cab7dd9`: no findings.

## Phase 5 checkpoint (T031–T032, 2026-11-10)

Tasks T031 and T032 approved in round 1 (minor findings fixed by the orchestrator). Both are
test-only; no production code changed in phase 5.

| Check | Command | Result |
|---|---|---|
| Java build | `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=<tmp outside the checkout>` | green (exit 0) |
| Python tools | `python3 -I -m unittest discover -s tools/tests` | green, 411 tests OK |

Test count: surefire 394 classes, 6267 tests, 0 failures, 0 errors, 52 skipped; failsafe 2
classes, 3 tests; total 6270 ≥ 6241 (phase 4).

Phase 5 code review over `git diff fe08f41 e7f4ef9`: one finding, recorded here for T038.
**T038 (opening 9.0) must also update, in the same commit**, besides the tests tasks.md lists:
`application/QueueEntryServiceVersionTest.productionStillRefusesNineZeroWithoutALaunch` (asserts
the shipped matrix refuses 9.0 with "not yet verified for INUBIT 9.0") and
`application/VersionGateTest` around line 1312 (same text). Otherwise the single T038 commit that
flips the matrix and adds the recorded `v9_0/error_entry` (required together by
`QueueEntryFixtureCatalogueTest`) cannot be green.

## Phase 6 checkpoint, offline part (T033, 2026-11-10)

T033 approved in round 2 (two minors fixed by the orchestrator after approval). Test-only plus
documentation; no file under `src/main` changed. T034–T039 are owner-gated (live steps and the
9.0 decision) and were not started; T040–T042 depend on T038 or T039.

| Check | Command | Result |
|---|---|---|
| Java build | `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=<tmp outside the checkout>` | green (exit 0) |
| Python tools | `python3 -I -m unittest discover -s tools/tests` | green, 411 tests OK |

Test count: surefire 395 classes, 6308 tests, 0 failures, 0 errors, 52 skipped; failsafe 2
classes, 3 tests; total 6311 ≥ 6270 (phase 5). The live test itself is excluded (`live` tag).

Phase 6 code review over `git diff 1b8ccd7 0e5fa34`: no significant findings (production wiring
unchanged, record-directory guard holds, no leaks in messages, no vacuous checks, docs agree).

## T010 – residual masking gaps (for T040 docs)

Accepted in review (minor, not required by D-11): JSON embedded as an escaped string
(`{"payload": "{\"password\": \"s\"}"}`) and JSON keys written with `\u` escapes are not masked by
rule 3; the known-credential scrubber still applies. To be listed with the masking residual risk in
`docs/tools.md` (T040).

## T011 – masking decisions and residual gaps

- Implementer decisions accepted in review: `-` counts as `_` in the term test (so `X-Api-Key`
  is a secret name, as the spec's own example requires); rules 4/5 allow only spaces and tabs
  around the colon; rule 7 replaces only the token (`Basic ${secret:authorization}`); unquoted
  rule 6 values may over-mask past `<`, `>` or `"` (safe side).
- Residual gap (minor finding T011-r1): a rule 6 quoted value with a backslash-escaped quote of
  the same kind (`password="a\"b"`) is masked only up to the escaped quote. To be listed with the
  masking residual risk in `docs/tools.md` (T040), unless fixed earlier.

## T012 – notes for later tasks

- Synthetic queueLog rows are named `fixtures/queue-entry/v8_1/rows_<case>.json` + `.origin`
  (as T012 asks); the T026 catalogue test must accept these names next to `<case>.rows.json`.
- `module`, `moduleType`, `globalProcessId` are not cut by the parser (only `userDefined1..5`);
  bounds on output fields belong to the result mapping (T024).

## T013 notes (binding for T015, T034)

- The fake 9.0 client (`InubitClientFactory`) is a private nested class: the helper must look up vendor client methods on `I_IBISClientInterface`, never on `client.getClass()`.
- `MultipartDownloadThread` constructor parameter types are not known from the spike (only argument values). The helper must select the six-argument constructor by parameter count (not by exact `getConstructor` types); the 8.1 live check (T034) confirms the real signature.

## T014 notes

- Deviation from task text: the "helper inside the shaded JAR" assertion lives in the Failsafe test `QueueEntryHelperPackagingIT` (pattern of `JarVersionIT`), because the shaded JAR does not exist during Surefire. `QueueEntryHelperPackagingTest` checks `target/classes` (exactly one class, major 61).
- Skeleton: a well-formed request returns exit 1 without frames until T015 adds the flows. Implementer-chosen validation: all 14 keys exactly once, nonce hex (any case), required non-empty fields, `dataOut` required when `parts` contains `data`, strict RFC 3986 percent-encoding with valid UTF-8, malformed request prints nothing (exit 2), I/O error reading the request gives exit 1.

## T015 notes

- Empty frame tokens (finding T015-r1): the helper never writes an empty token; the frame writer
  writes it as `-` (recorded in data-model "Helper frames"). This covers a single exception
  without a message (`login error java.io.IOException -`) and a download thread reporting
  `exceptionOccured()` with `getException() == null` (`part variables error - -`). T018 decodes
  tokens as usual; `-` as chain means "no exception", `-` as message means "no message".
- Throwables (finding T015-r1): a `LinkageError` thrown while the vendor classes are looked up
  (before `start`) stays `api missing`, exit 4. After `start`, a `LinkageError` while the client is
  created or logs in (e.g. `ExceptionInInitializerError` of `IBISSoapClient`,
  `InubitClientFactory`, `ConnectionData`) is `login error <chain> <messages>`, exit 3, as any
  other login failure; one while a download thread is created is that part's `part … error`.
  Any other `Throwable` gives exit 1 without a frame or output; `run` never throws, and `main`
  calls `System.exit` in a `finally`, so nothing reaches stderr from the helper itself.
- Masking: the password and the trust-store password are masked longest first, so one contained
  in the other leaves no piece unmasked.
- Test support: `FakeInubitScript.initializerFails(simpleName)` (static initializers of the fake
  `IBISSoapClient`, `InubitClientFactory`, `MultipartDownloadThread` call
  `FakeInubitScript.initializing`; in-process only, through a class loader that defines a fresh
  copy) and `fileFailsWithoutException(fileName)`.
- Unverified: on 8.1 over `http` the helper passes `null` trust store and trust-store password to
  `IBISSoapClient`; T034 must confirm live that the 8.1 client accepts this.
- Open (T015-r2 minor, for T034/T040): an `Error` thrown inside the vendor download thread's `run()` would bypass `exceptionOccured()`, so the helper reports `part … ok` for a missing/partial file. Depends on unverified vendor behaviour; a possible fix is an uncaught-exception handler set before `start()`.

## T016 notes

- `QueueEntryHelperProcessTest` copies the helper class from the class-path resource
  `META-INF/inubit-mcp/queue-entry-helper/` into `<tmp>/helper` and builds the D-3 command itself
  (`-Xmx512m`, TLS protocols, `-Dsecretkey.path=<home>/conf`, `-Djava.io.tmpdir=<tmp>/vendor`,
  `-cp <helper><sep><home>/lib/*<sep><home>/lib/shared/*`, class name; working dir = client home;
  env from `StartCliEnvironment`). The `--add-opens` list is left out: it belongs to T020
  (`HelperInstallation`), whose tests own the exact argument array.
- No helper bug found. Mutation check: without `System.exit` in `main` both lingering-thread
  cases fail with `TIMEOUT` (the child JVM does not end).
- For T020 (from T016-r1): once `HelperInstallation` exists, run at least one child-JVM case with its real argument array (incl. `--add-opens`), either here or in T020's tests.

## T018 notes

- Result contract: `WfspParser.parse(byte[])` never throws for any content. It returns
  `PartResult.Read` with the variables in file order (`List.copyOf`, unmodifiable) or
  `PartResult.Failed` with the fixed `UNEXPECTED_FORMAT` reason; the StAX message is never used,
  as it may quote content. The null-argument check is the only exception.
- Single root: StAX refuses a document with no root or a second root element, so the parser
  keeps no `rootSeen` flag (removed in T018-r2; proven by tests and a mutation check).
- Size limit: the parser reads whatever bytes it gets; capping the `.wfsp` size is T021's job.
- Invalid UTF-8 makes the JDK print a content-free `[Fatal Error] :-1:-1: <byte-position message>`
  line to stderr (same as the existing `XmlTree.parse`). Stderr is the log channel (Constitution
  VII), so the protocol is unaffected. Candidate for later cleanup (e.g. a silent error handler).

## T020 notes

- Orchestrator decision (T020-r1 minor): queue directories may hold entry content (`out/`), so
  `CliResources.sweepStale` sweeps stale `inubit-mcp-queue-<profile>-<pid>-<random>` directories
  at startup exactly like export directories: only this profile, only real directories (no links
  or files), only if owned by the current user (an unreadable owner counts as foreign), never the
  current process's and never one whose pid is alive. There are no legacy (feature 001) queue
  names. The startup log line now says "temporary directories".
- Texts (T020-r1 minors): after the shutdown `HelperInstallation` reports "The MCP server is
  shutting down; the queue-entry helper was not started for <node>" (`INTERNAL`, D-3
  `processLabel`); a failure while copying the helper class has its own `CLI_UNAVAILABLE` text
  ("could not be copied into its private temporary directory"), separate from the private-directory
  failure.

## T021 notes

- Deadline clamp (from T007): `CliQueueEntryAdapter.remaining(deadline)` clamps at zero with
  `Duration.isNegative()` → `Duration.ZERO`; `Duration` has no `max`, so there is no
  `max(ZERO, left)` form.
- Test double: the adapter tests use a new `FakeProcessLauncher.responding(Function<byte[],
  Answer>)` instead of `onLaunch` as tasks.md names it. The helper reads the request (with the
  nonce and the output paths) from stdin, which is only complete once stdin is closed, after the
  launch; `onLaunch` only sees the `LaunchSpec`. The responder gets the stdin bytes, writes the
  case files and answers with frames carrying the request's nonce.
- Data-only reads: the helper protocol (D-4) always fetches the variables; a read of the data
  alone still runs the variables part and the adapter reports the variables as `NotRequested`.
- Reported length vs. file (T021-r1 minor, D-12 "row and file disagree → not read"): a part the
  helper reports as `Written(bytes)` is checked in this order: a file over 10 MiB is `TooLarge`
  whatever length was reported (nothing of it is read); otherwise a file that does not have the
  reported length — including a missing file with a reported length > 0 — fails the part with
  the fixed, content-free `LENGTH_MISMATCH` text. A missing file reported as 0 stays an empty
  part (empty data stream; for the variables the `WfspParser` failure).
- Guard wait (T021-r1 minor, D-14): `CredentialGuard` offers only the unbounded `acquire()`
  (also used by `CliRunner` and `InubitHttpClient`), so no bounded acquire was added and the
  guard's semantics are unchanged. The wait (another attempt's login while the credentials are
  unconfirmed, or a credential re-read) can therefore lie outside the single `cliTimeout`
  deadline while the StartCLI slot is held; it ends with that other attempt, itself bounded by
  its deadline, or the re-read. The run then gets only the remaining deadline. The permit is
  still taken after slot and handshake and settled only by the login result. Documented in the
  `CliQueueEntryAdapter` class Javadoc.
- Deadline passed during the guard wait (T021-r2 minor, D-14): if nothing of the deadline is left
  once `guard.acquire()` returns, the helper is not launched and no request (no credentials) is
  written; the call ends in `TIMEOUT` "cliTimeout (<n>) on <node> passed before the login; the
  queue-entry helper was not started" (the "was not started" form of the contract's pre-launch
  texts). The permit is closed without `accepted()`/`rejected()`, exactly as `CliRunner.guarded`
  leaves it when the launch throws, so it counts as no login result.

## T022 notes

- Credentials reach `CliQueueEntryAdapter` as `node::credentials` (the held credentials the node's guard is bound to); `Slot.ports()` already awaits `NodeSecrets.current` before any port exists, and the port set is rebuilt on rotation.
- The 7-port `PortSet` compatibility constructor refuses queue-entry reads with `RefusingPorts.refusal()`, whose text names INUBIT 6.1. Production never uses it for 8.1/9.0 (`Wiring` uses `v81Default(host, processRun, limiter).withV90()`), but test registries built with `v81Default()` / `V81PortSet.create(node)` give that 6.1 wording — later tests (T023+) must not assert on it for 8.1/9.0 nodes.
- Process deviation: tests were written first but not run red; evidence of their strength: they do not compile without the new API, and two deliberate mutations (plain 8.1 set in `withV90`, separate guard) failed.

## T023 notes

- Seam for T024: part 1 is the package-private `QueueEntryService.readRaw(target, processId,
  fileName, maxChars)` returning `RawEntryRead(node, row, content, maxChars, warnings)` (the
  selected row, the unshaped `RawEntryContent`, the validated text limit, the gate's warnings).
  T024 adds the public `read(…)` → `QueueEntryResult` on top of it, plus `knownSecrets` in the
  constructor and the INFO line; the T023 tests keep calling `readRaw`.
- A data stream not requested for its size (> 10 MiB) is returned as `PartResult.TooLarge(fileSize)`
  (the row's size), so T024 renders every unavailable part from one shape.
- Texts not fixed by the contract (implementer's choice): `fileName` unknown → `NOT_FOUND` "Queue
  entry <file> of process <id> is not in the Queue Manager of <node>; nothing was read"; the
  non-ERROR state list is bounded like the candidate list (20, "and N more"); the "only N of M
  rows were read" suffix ends every candidate message when the row cap was hit (", … and none is
  in ERROR" for the state list, as `ProcessControlService`).
- `NOT_CONFIGURED` next step (review T023-r1, orchestrator decision): "… and restart the MCP
  client (…)", the wording of the existing opt-in refusals (`WriteGuard`, `DevelopmentGuard`,
  `ArchiveExportService`); contracts/mcp-tools-delta.md "Errors" changed from "restart the server"
  to match, so contract, code and test agree and the bare level word "server" stays out of the
  message (`MessageTerminologyTest`). No other feature document carried the old wording.
- Re-read after `NotFound`: "still there" = a row with the same `fileName` among the re-read rows
  (any state); a failure of the re-read itself passes through as the call's error.
- The service takes `Predicate<NodeId>` (opt-in), `Function<NodeId, ProcessQueryPort>` and
  `Function<NodeId, QueueEntryContentPort>` (the `ArchiveExportService` style), so its tests need no
  `GatewayFactory` fake and never see the 6.1-worded refusal of the 7-port `PortSet` (T022 note).
- Mutation check: opt-in after the gate, no `TooLarge` substitution, no re-read check, data always
  requested, unbounded lists — each fails at least one test.

## T024 notes

- FR-010 "size and hint" for a binary variable (review T024-r1, finding 1): read as `kind: binary`
  plus `sizeBytes` and `detected`, the shape of contracts/mcp-tools-delta.md and data-model.md,
  which carry no separate `hint` field; `kind: binary` is the hint. The owner may add a `hint`
  field to contract and data model later (before T025 renders the tool).
- WS-Security (finding 2, for the D-22 docs task): a real envelope nests `wsse:Password` in
  `wsse:UsernameToken`, a secret-named element (term `token`), so FR-011 rule 1 masks the whole
  token: the result shows `${secret:data.UsernameToken}` and hides the `wsse:Username` too. The
  contract example (`${secret:data.Password}`) only fits a simplified fragment. US1-6 test fixture:
  the SOAP case uses `wsse:Username`/`wsse:Password` directly in `wsse:Security` (the spec's
  example names only `wsse:Password`); a second case asserts the `UsernameToken` placeholder.
- Credential-named variables (finding 4): fail-safe, the term test runs on the whole variable
  name, not only the local name after the last `:` (`SecretTextMasker.secretVariableName`), so
  `token:id` and `password:old` become placeholders. `secretName` (local name only) stays as is
  for the masking rules on XML/JSON/header names.
- INFO line (finding 3): once a row is selected, its node and file name are logged even when a
  later step (content port, re-read, shaping) fails and no `fileName` was passed; the failure's
  message and content never reach the line.

## T026 notes

- Lookup in the contract test: `QueueEntryV81ContractTest` answers the entry lookup with a fake
  `ProcessQueryPort` fed from `<case>.rows.json` through the real `QueueEntryRowParser`
  (`QueueEntryRowFixtures.queueEntries`, now public); the helper is a `FakeProcessLauncher`
  replaying `.frames`/`.exit` and writing `.data`/`.wfsp` to the requested output paths. No helper
  process, no WireMock, no network.
- Catalogue rules (`QueueEntryFixtureCatalogueTest`): every file under `fixtures/queue-entry/`
  except the root `README.md` belongs to a case in `v8_1/` or `v9_0/`; every case has an `.origin`
  whose first line starts with `recorded` or `reconstructed`; `.frames` and `.exit` come as a pair
  and every frame carries the fixed nonce. A `.README` is required for every case except the
  `rows_<case>.json` lookup answers (T012 did not require one; their `.origin` explains them), so
  T027's `error_entry` needs one.
- The contract test's case list must equal the `v8_1/*.rows.json` cases on disk, so T027 adds
  `error_entry` there (with its expectations) and cannot be forgotten.
- 9.0 rule: `CapabilityMatrix.production()` has 9.0 `QUEUE_ENTRY_READ` unsupported and
  `v9_0/error_entry.origin` does not exist, so the rule holds; both directions are tested on a
  temporary copy.
- `too_large` is represented by the row's `fileSize` (10,485,761) only; no 10 MiB file is in the
  repository, the data stream is never requested. The adapter's own check of an oversized written
  file stays covered by `CliQueueEntryAdapterTest`.
- `.gitattributes`: `.data` and `.wfsp` under `fixtures/queue-entry/` are `-text`, as the frames
  name their exact byte lengths (`secrets_everywhere.data` has CRLF lines).
- Masking results asserted as the masker produces them (FR-011): a JSON object value becomes a
  quoted placeholder (`"credentials": "${secret:data.credentials}"`), a Bearer token under a
  non-secret key keeps its scheme (`"auth": "Bearer ${secret:authorization}"`), the configured
  password is `***`.
- The fixtures were generated once by a script outside the repository (not committed); the
  `.origin` of each case states `reconstructed synthetic content …`.

## T028 notes

- Summary suffix condition (FR-017, contract followed literally): the `, queueEntryRead: …` suffix
  appears on every group line once **any group of the profile** has
  `unsupported.queueEntryRead: true`, read from `ProfileConfig.groups()` with the same default as
  `EffectiveNodeConfig` (`Defaults.BUILTIN_QUEUE_ENTRY_READ`), not from `resolvableNodes()`. An
  opted-in group without a resolvable node (e.g. a node without `baseUrl`) has no group line of
  its own but still switches the suffix on for the others (`ConfigSummaryTest`).
- The per-line value (`off` / `enabled (unsupported)`) is taken from the first resolvable node of
  the group, since the setting is group-level only (research: no node-level key).
- An explicit `queueEntryRead: false` is the same as no opt-in: the summary stays byte-for-byte
  that of 0.8.0 and no existing golden text changed.
- The production warning (`ConfigValidator.checkQueueEntryReadOptIn`) is a terminology warning
  (`termWarning`, `{group}` follows the profile's terminology); it appears under `Warnings:` in
  `--check-config` and as `Configuration: …` in the startup log, never as an error (exit 0).

## T029 notes

- `docs/setup.md` `### Unsupported functions` also carries the contract's client-installation
  paragraph and three short validation bullets (true/false only; `unsupported` on a node or in
  `defaults` is an unknown key; `--check-config` shows the state). The section 6 example is checked
  against the real `ConfigSummary` output by `QueueEntryDocsTest`.
- The verbatim FR-004 text links `tools.md#read_queue_entry`, which has no target yet. **T040 must
  add exactly this anchor** (`## read_queue_entry` in `docs/tools.md`), ideally with a test assertion.

## T030 notes

- Announcement: `Wiring.toolHandlers()` adds `read_queue_entry` only when some node satisfies all
  three conditions: it is opted in (`unsupported.queueEntryRead: true`), `possiblyCapable(…,
  QUEUE_ENTRY_READ)` holds, and a client home is configured
  (`clientSelection.anyCandidate`). The tool comes directly after `query_logs`, next to the other
  read-only tools. `allowed()` adds `QUEUE_ENTRY_READ` only with the opt-in **and** an installed
  client (`withClient`, the same rule as `workspace_export`).
- Under the 9.0 fallback (the matrix marks 9.0 as UNSUPPORTED), an opted-in node pinned to `V9_0`
  does not announce the tool. In `list_nodes` it lists `queue_entry_read` under `notUsable`, with
  the matrix reason. An opted-in `AUTO` node whose version has not been detected lists it under
  `notUsable`, with the same "version unknown" reason as the other capabilities. A node that is
  not opted in never mentions it.
- SC-002 / SC-005: the no-opt-in tool list and `list_nodes` output were compared with 0.8.0 in
  three ways:
  1. **Source.** `git show v0.8.0:…/Wiring.java` against HEAD: `toolHandlers()` differs only by
     the new opt-in block. `git diff --stat v0.8.0 HEAD -- src/main/resources/` shows one changed
     line in `list_nodes.output.json` (the `CapabilityName` enum gains `"queue_entry_read"`, added
     by T003 as `contracts/mcp-tools-delta.md` requires). The two `read_queue_entry` schemas are
     new. No other tool description or schema changed.
  2. **Tests.** `ToolAnnouncementTest.withoutAnOptInTheToolListIsExactlyThatOfZeroEightZero` and
     `MainTest.withoutAnOptInTheToolListIsExactlyThatOfZeroEightZero` (real stdio `tools/list`)
     assert the exact 0.8.0 name list and order, both without the key and with
     `queueEntryRead: false`.
     `ListNodesToolTest.queueEntryReadIsListedOnlyForNodesOfOptedInGroupsWithAClient` checks
     that a group that is not opted in keeps exactly the 0.8.0 `usable` list and an empty
     `notUsable` list. The existing `list_nodes`, ConfigSummary and `--check-config` goldens are
     unchanged.
  3. **Runtime, byte for byte (done once, not committed).** I built `git archive v0.8.0` and HEAD
     outside the checkout. Each build was driven in-process through `Launcher.Context` with a
     fake credential store, a fake StartCLI home and `updateCheck.enabled: false`. The run sent
     `initialize`, `tools/list` and `tools/call list_nodes` over the same 4-group profile (AUTO
     dev with write/development, V8_1 test with deploy, V9_0 next, V6_1 legacy). There was no
     network: nodes were `*.example.test`, and `list_nodes` makes no requests. Results:
     - The `initialize` and `list_nodes` responses are byte-identical to 0.8.0.
     - The `tools/list` response has the same 16 names in the same order. It differs only by the
       19 bytes of `,"queue_entry_read"` in the `list_nodes` output schema enum. Removing that
       value gives the 0.8.0 response exactly.
     - With `queueEntryRead: false` on one group, the output is byte-identical to the run without
       the key.
     - With an opt-in on dev, `read_queue_entry` appears after `query_logs`.
- That one additive enum value in the announced output schema is the only difference from 0.8.0
  on the no-opt-in path. It is contract-mandated (`contracts/mcp-tools-delta.md`:
  "`list_nodes.output.json` enum + `"queue_entry_read"`"; one static schema for every profile)
  and changes neither the tool list nor any `list_nodes` data.
- Orchestrator decision on SC-002 (round 2): the byte-for-byte guarantee of plan.md:64 covers
  the tool names and their order, the `list_nodes` data, the startup summary and
  `--check-config`. The static `list_nodes` output schema gains the additive `queue_entry_read`
  enum value as planned (D-6, the contract, plan.md:80, T003); T003 stays as it is. **Flagged
  for the owner in the final report.**
- Per-part rule in `list_nodes` (round 2): `read_queue_entry` asks the gate for REST and
  StartCLI in one call (`QueueEntryService`), so a known incompatible StartCLI client refuses
  every call. `Capability.admittedPerChannel()` marks the capabilities whose tools ask the gate
  per channel (only `INVENTORY`); `NodeOverview` keeps `"<name> (StartCLI parts)"` for those.
  `queue_entry_read` is then listed only under `notUsable`, with the client reason, never under
  `usable`. `inventory` and every other capability behave as before (existing tests unchanged).
  Test: `ListNodesToolTest.anIncompatibleClientMakesQueueEntryReadNotUsableAsAWhole`. Before the
  fix the output-schema validation of `list_nodes` even rejected the result, because
  `"queue_entry_read (StartCLI parts)"` is not an allowed value.

## T031 notes

- Result: every T031 test was **green on its first run**. No production code changed
  (`CliQueueEntryAdapter`, `QueueEntryService`, `CapabilityMatrix` untouched). The first run
  compiled and passed with no test edits in between. US3 is already carried by T004 (detection
  codes), T021 (`QueueEntryVariant`, `cliFor(version)` per call), T022 (version through the
  port), T023 (gate before lookup) and T030 (9.0 admission).
- Shared fixture `adapter/cli/queue/QueueEntryVersionProfile` (test, public) holds one profile
  with an `8.1` client plus JDK 17 and a `9.0` client plus JDK 21 under `defaults.cliClients`.
  Helper homes live in a private temp root. It also provides fake helpers (`helperReading`
  echoes the requested `variant`; `helperLacking` sends one `api missing` frame, exit 4).
- `QueueEntryVersionSelectionTest` (adapter, 8 cases) calls the real `CliQueueEntryAdapter`
  with a given version:
  - 8.1 (AUTO and V8_1): SOAP_81, 8.1 home/JDK, servlet address.
  - 9.0 (AUTO and V9_0): REST_90, 9.0 home/JDK, base address.
  - 6.1.5 and 9.1.0: UNSUPPORTED_VERSION, before credentials, with no launch.
  - No `"9.0"` entry: CLI_UNAVAILABLE naming `defaults.cliClients."9.0"`, no launch.
  - `"9.0"` pointing to the 8.1 home: CLI_UNAVAILABLE after one REST_90 launch.
- `QueueEntryServiceVersionTest` (application, 14 cases) runs the same bullets end to end
  through the real `VersionGate` (`FakeVersionDetection.gateWithoutClients`, so no client
  probe) with one real adapter and one `FakeProcessLauncher` per node. It adds:
  - both versions read on one service;
  - 6.1 (pinned) and 9.1.0 (detected) refused with the exact gate texts ("supported: 8.1,
    9.0"), with no lookup and no launch;
  - an undetectable AUTO node failing with the detection's own code (UNREACHABLE, TIMEOUT,
    TLS_ERROR, AUTH_FAILED, UNEXPECTED_RESPONSE), with no lookup and no launch;
  - the back-off replay within 60 s with no second detection.
  Undetectable versions are covered in the service test only, because the adapter never sees
  them.
- How 9.0 is opened: the test-local constant `QueueEntryServiceVersionTest.OPENED` is a new
  `CapabilityMatrix(...)` declaration. It copies the production 6.1 entry, supports every
  capability on 8.1 and 9.0, and treats unknown versions as production does. It is passed only
  to that service's gate. `CapabilityMatrix.production()` is not modified.
  `productionStillRefusesNineZeroWithoutALaunch` asserts that production still refuses 9.0
  ("not yet verified"), with no lookup and no launch, and still reports QUEUE_ENTRY_READ
  UNSUPPORTED for 9.0.10.
- Every error passes `TestSecrets.assertNoLeak`; every launch spec is checked for secrets; the
  helper temp root is empty after each case. No server is contacted.

## T032 notes

- Result: `QueueEntryV90ContractTest` was **green on its first run**; no production code changed
  (`CapabilityMatrix.production()` still refuses 9.0 "not yet verified"). The only red test was
  the new catalogue rule (see below), which is test code.
- `QueueEntryV90ContractTest` (6 cases) runs the real `QueueEntryService` and
  `CliQueueEntryAdapter` on the T031 profile (`QueueEntryVersionProfile`, node `test/n90`
  detected as 9.0.10, 9.0 client and JDK 21). A `FakeProcessLauncher` replays
  `v9_0/<case>.frames`/`.exit` with the request's nonce. The row is built in the test, because
  `v9_0/` has no `.rows.json`; its file name is the one the `not_found_file` frames name.
  - Fallback: the test asserts that `v9_0/error_entry.origin` is absent. With the production
    matrix, the call is refused with `UNSUPPORTED_VERSION` "not yet verified for INUBIT 9.0".
    There is one detection, no lookup and no launch, and the log line ends with
    `outcome UNSUPPORTED_VERSION`. T038 must replace this test, because its first assertion
    fails as soon as the recording arrives.
  - Meaning of "partial / NOT_FOUND after re-read" (spec edge case, research D-5, T023 note):
    both reconstructed cases report **both** parts `NotFound`, so the service reads the rows a
    second time. If the row (same `fileName`) is still there, the result is partial. Data and
    variables are both unavailable with the reason "INUBIT did not find the content of this
    entry", and the log says `outcome PARTIAL`. If the row is gone, the call fails with
    `NOT_FOUND` and the exact contract text "… left the Queue Manager of test/n90 (restarted,
    killed or deleted meanwhile)", and the log says `outcome NOT_FOUND`. Each case runs both
    ways; there are two row reads and exactly one `REST_90` request to the base address
    (`objectType`, `fileName`, `user`).
  - Leak check: `TestSecrets.assertNoLeak` runs on the result JSON, every error and every log
    line, and the launch spec is checked for secrets. INUBIT's own texts from the frames
    (`Unable to download`, `MissingWorkflowWithName`, `IOException`, `acme/workflow`) never reach
    a result, an error or the log. The temporary root is empty after each case.
  - For T038: `CASES` must equal the `v9_0/*.frames` cases on disk (the same rule as
    `QueueEntryV81ContractTest` in T026). The not-found tests name their cases in a
    `@ValueSource`, so T038 adds `error_entry` to `CASES` together with its own test and does not
    run the not-found expectations on it. A recorded `not_found_file` keeps its name and stays
    covered.
- Opened matrix: the T031 test constant moved into the shared fixture as
  `QueueEntryVersionProfile.OPENED`, and `QueueEntryServiceVersionTest.OPENED` now refers to it,
  so both tests open 9.0 with one test-only declaration. The production matrix is unchanged.
- `QueueEntryFixtureCatalogueTest` already covered `v9_0/` from T026: the closed file layout,
  `.origin`/`.README`, the frames/exit pair, the fixed nonce, and the 9.0 rule (matrix ⇔ recorded
  `error_entry`). T032 adds one missing rule: every `start` frame names the helper variant of its
  directory (`SOAP_81` in `v8_1/`, `REST_90` in `v9_0/`). This keeps 8.1 frames out of a 9.0
  recording (T037/T038) and the other way round. The rule is tested in both directions on a
  temporary copy, and `v9_0/missing_workflow` is now listed among the expected cases.
  `fixtures/queue-entry/README.md` states the rule and the V90 case-list rule.

## T033 notes

- Files: `live/QueueEntryLiveTest` (`@Tag("live")`, `@Timeout(600)`), its testable logic
  `live/QueueEntryLiveTestSupport`, and the offline test `live/QueueEntryLiveTestSupportTest`
  (26 cases in round 1, 39 in round 2, written first; the red run was the missing support
  class). Three small test-tree changes: `LiveTarget.wiring(ProcessLauncher)` (the production
  wiring with a given launcher), `QueueEntryVersionProfile.adapter` now takes a `ProcessLauncher`
  instead of a `FakeProcessLauncher`, so the recorder can wrap the fake helper, and
  `GitFreeTempDir.insideGitTree` is public (round 2). Existing callers are unchanged.
- Exclusion: the default build excludes the class through `surefire.excludedGroups=live,live-store`
  (pom), just like the other live tests. Without `INUBIT_LIVE_NODE` it is skipped by
  `LiveTarget.resolve()`; without `INUBIT_LIVE_QUEUE_ENTRY` it is skipped by
  `QueueEntryLiveTestSupport.entry`. A node whose group lacks `unsupported.queueEntryRead: true`
  fails before anything is contacted.
- `INUBIT_LIVE_QUEUE_ENTRY=<processId>[/<fileName>]`: the value is split at the first `/` and is
  not stripped. The processId follows the input-schema pattern; the fileName follows
  `QueueEntryRow.isValidFileName`. A test checks that this matches the schema's `fileName`
  pattern on a list of candidates (C0/C1 controls, NBSP, 255/256 characters). Messages name the
  variable and its form but never echo the file name.
- `INUBIT_LIVE_RECORD_DIR` (round 2, review finding 1): the value is resolved to a real path
  (the nearest existing ancestor with `toRealPath()`, so links and the letter case on a
  case-insensitive file system are resolved, plus the remaining components, normalized). A
  relative path, one with `..` segments (round-2 review minor: after a link, `..` is the link
  target's parent, so a lexical normalize could hide a path into the repository; refused
  outright), one inside the repository (`user.dir`, also `toRealPath()`) and one inside any
  git working tree (`GitFreeTempDir.insideGitTree`, now public instead of a copy) are refused,
  and the real path is used. Offline tests: a link to the repository and to its parent, a
  case variant (skipped where the file system is case-sensitive; it runs on macOS), a clone and
  a worktree `.git` file, and a normal directory; the test class uses `GitFreeTempDir` as its
  `@TempDir` factory so its temporary directory is never inside a git tree. The per-run directory
  `queue-entry-…` is created **before** the server is contacted with `OsPrivateFileSystem`
  (POSIX `rwx------`/`rw-------`; on Windows an owner+SYSTEM access list), and the files are
  created with `createFile` and then written. If the file system supports neither (an
  `UnsupportedOperationException`), the test fails with "nothing is recorded" and creates
  nothing. A fake file system tests this, because the host always supports one of the two.
- Recording (`HelperRecorder`): it wraps the launcher and records only launches whose last
  argument is `HelperInstallation.MAIN_CLASS`. It copies stdout (the frames, including vendor
  noise before them, like the fixture `.frames`) while it is read. When `waitFor` first returns
  true, it snapshots the exit code and the files `out/data.bin` and `out/variables.wfsp`. The out
  directory is the sibling of the helper's `-Djava.io.tmpdir=<call dir>/vendor`. This happens
  before the adapter closes the installation and deletes the directory. Stdin (the request with
  the password) is never read. The layout and file names copy `HelperInstallation` and
  `CliQueueEntryAdapter`. `theRecorderKeepsTheHelpersRawOutputButNotItsRequest` runs the **real**
  adapter on the T031 profile, so a layout change fails offline.
- The case files are `error_entry.frames|.exit|.data|.wfsp`, plus `not_found_file.*` from the
  negative case. A part the helper did not write gets no file. `.exit` is `<code>\n`, like the
  fixtures. `.rows.json` is not recorded, because D-21 does not ask for it; T034/T035 build the
  row by hand as in T032. The `error_entry` files are written before the result is asserted, so
  a failing read is still recorded if the helper ran.
- Negative case: it uses `gateways().queueEntryContent(node).read(...)` with the workflow of the
  row read and `Module-0000(0)@0@00000000`, the size 0, both parts, and `detect(node)`. The
  outcome is not asserted. It is summarised as part classes (e.g. `data NotFound, variables
  NotFound`) or `error <code>` at the end of the `[live]` line. The test asserts that exactly one
  new helper run happened.
- SC-004 check: the row is selected by the `entry.fileName` the tool returned. If it is missing
  from the rows read before, the test fails. In the rows read after, the row with the same
  `fileName` must keep `state` (and `rawState`), `startTime` (`since`) and `fileSize`. If no row
  with that `fileName` remains, the message is "fileName: … left the Queue Manager or was
  renamed". Messages list field names only.
- SC-003 log check: during the test, logger `de.dadecker.inubit` runs at `DEBUG` with a
  `ListAppender` and additivity off, so DEBUG lines do not reach the console. The values checked
  are the stripped lines of the data text with at least 10 characters (`MIN_CHECKED_CHARS`) and
  of the text values of non-system, unmasked variables: from 10 characters as substrings, from 4
  (`MIN_TOKEN_CHARS`) to 9 as whole tokens (round 2, finding 3; `Checked.occursIn`: not next to a
  letter, digit or underscore); shorter lines such as `</order>` occur in unrelated log text.
  The check covers formatted messages and logged stack traces, and the failure shows only a
  count.
- Masked values (round 2, finding 2): `maskedValues` parses the raw `.wfsp` of the read's helper
  run with `WfspParser.parse` and pairs it with the result's items by position (the service
  lists the variables in file order; the variable count must equal `totalCount`, so names that
  are cut or scrubbed do not matter). The stripped original value of every item reported
  `masked: true` must occur in neither the log nor any string of the result; a blank value is
  skipped, a value of 1 to 3 characters is matched as a token, a longer one as a substring.
  Masked items without a parseable, matching `.wfsp` fail. `assertNoKnownSecret` checks every
  log line and every string of the result with `SecretScrubber.containsSecret` of the live
  scrubber (any length). Offline cases cover both checks.
- No helper run (round 2, finding 4): `readRun` fails with a clear message when the
  `read_queue_entry` call started no helper (checked after the error result is shown), so the
  `[live]` line never says `recorded` without `error_entry`.
- Docs: `docs/live-tests.md` has the section "Queue entry read (feature 020)" with the
  subsection on recording, a tests-table row, and `QueueEntryLiveTest` in the single-test list.
- For T034: run the quickstart command once without and once with `INUBIT_LIVE_RECORD_DIR`.
  The T013/T015 notes still apply: confirm the 8.1 `MultipartDownloadThread` constructor and
  that the 8.1 client accepts a null trust store over plain HTTP. If the negative case is refused
  before a launch (for example a classification before the helper), the assertion "helper runs
  of the not-found case" fails. That is a finding to note, not a reason to loosen the test.
- Round-2 review minors (fixed after approval): `..` segments in `INUBIT_LIVE_RECORD_DIR` are
  refused (offline case `<temp>/missing/../link-into-repo/records` and four more; the two older
  tests that used `..` paths now use plain ones); a new case with `totalCount` 4 but 3 returned
  items and a 4-variable `.wfsp` pins that the raw file is matched against `totalCount`.

## T034-F1 – rows without queue file (live finding)

- Finding (T034 live check on two 8.1 test servers): the REST queueLog
  (`POST /ibis/rest/log/queueLog?format=json`) returns some rows without a `fileName` field
  (the field is absent; `fileSize` 0, state Error), about 15 of 230 ERROR rows. Such processes
  usually also have rows with a file name, sometimes only rows without one. `QueueEntryRowParser`
  threw `UNEXPECTED_RESPONSE` ("has a file name that cannot be used", likely cause "another
  format") for the whole lookup as soon as one row of the process lacked a file name, so
  `read_queue_entry` failed even for the readable entries of that process, with a misleading
  cause.
- Decision (orchestrator): a row whose `fileName` is absent, JSON null, empty or blank, with
  `workflowId` and `fileSize` 0 (a whole number), is a row without queue file. It does not fail
  the lookup, is never a candidate and is never selected. A row without `fileName` but with
  `fileSize` > 0, without a numeric `fileSize` or without `workflowId` stays
  `UNEXPECTED_RESPONSE` for the whole lookup, without row values (round 2, review T034-F1-r1).
  A present but unusable `fileName` (too long, `/`, `\`, control characters) stays
  `UNEXPECTED_RESPONSE`. `QueueEntryRow.fileName` stays a required valid string.
- Model: `QueueEntries` gained `List<ProcessState> withoutQueueFile` (the states of the rows
  without queue file; a two-argument constructor keeps the old call sites).
  Rows with and without file together are at most 100 and at most `total`; `rowsRead()` counts
  both, and `truncated()` is `total > rowsRead()` (states rather than an ERROR count, so a
  WAITING row without file does not look like a hit row cap). The "only N of M rows were read"
  suffix uses `rowsRead()`.
- Parser: `QueueEntryRowParser.parseRows(server, rows, total)` partitions the rows, parses the
  rows with a file name as before and, for the others, checks `workflowId` and `fileSize` 0
  and keeps only the state (status handling as before: a missing status is OTHER); `total` is
  raised to the rows read as the adapter did before. `V81ProcessQueryAdapter` and
  `QueueEntryRowFixtures.queueEntries` use it. There is no separate 9.0 row path: the 9.0
  port set uses the same 8.1 REST adapter, so both lines behave alike.
- Service: selection among rows with a file is unchanged. Without `fileName`, if no row with a
  file is in ERROR but rows without file are, the call is refused with `INVALID_INPUT` before
  any helper launch: `Process <id> on <node> has <n> ERROR entr(y|ies) in the Queue Manager
  without a queue file (fileSize 0); there is no content to read`, next step `Read their error
  texts with query_logs (logType queueLog) for process <id>`. The message quotes only the
  process id and node. Rows without file are ignored beside ERROR rows with a file (one → read,
  several → the existing candidate list, without them). A given `fileName` not among the rows
  with a file stays `NOT_FOUND`. If no row at all is in ERROR, the existing not-in-ERROR
  `INVALID_INPUT` lists the rows with file as before and the rows without file as
  `<STATE> (without queue file)` (only their normalized state is kept), e.g. `read_queue_entry
  reads only ERROR entries; process 1234 on test/node1 has: Waiting (Module-0002(…)@1234@…),
  QUEUED (without queue file)`. `NOT_FOUND` "not (or no longer) in the Queue Manager" only when
  no row was read at all.
- Tests: `QueueEntryRowParserTest` (absent, null, empty and blank `fileName` kept by state
  beside a row with file; only `workflowId` and `fileSize` 0 needed, a missing status is OTHER;
  `fileSize` 1 or 19218, missing, null, text or fraction, and a missing or blank `workflowId`
  each fail the whole lookup; total raised to the rows read; a
  present `fileName` with `/` still fails the whole lookup; the old "missing or empty file name
  is `UNEXPECTED_RESPONSE`" case was replaced), `QueueEntriesTest` (states kept as an immutable
  copy, counted as read, against the cap and the total), `QueueEntryServiceTest` (only ERROR
  rows without file → `INVALID_INPUT`, plural and singular, no content call, leak check; one
  ERROR row with file beside one without → read; two with file plus one without → candidate list
  of 2; given `fileName` among rows without file only → `NOT_FOUND`; only non-ERROR rows
  without file, alone or beside a non-ERROR row with file → not-in-ERROR `INVALID_INPUT` listing
  them).
- Docs: spec.md edge case "Entries without queue file", contracts errors table (new
  `INVALID_INPUT` row; `UNEXPECTED_RESPONSE` row clarified), data-model.md (`QueueEntries`,
  `QueueEntryRow`, `QueueEntryRowParser`). T040 must document this case in `docs/tools.md`
  (`read_queue_entry` errors).
