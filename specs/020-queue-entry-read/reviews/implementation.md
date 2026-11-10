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
