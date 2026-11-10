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
