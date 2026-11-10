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
