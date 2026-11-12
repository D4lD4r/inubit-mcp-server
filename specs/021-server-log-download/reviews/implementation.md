# Implementation log: 021-server-log-download

Findings, decisions and checkpoints of the implementation phase (`d4ld4r-speckit-duo:implement`).

## T001 – Baseline (2026-11-11)

Branch `021-server-log-download` at `5c19cc8c`, before any implementation change (0.9.1 suite).
JDK 25.0.5, Maven 3.10.0, Python 3.15.2, macOS.

| Check | Command | Result |
|---|---|---|
| Java build | `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-021-tmp` | green (exit 0) |
| Python tools | `python3 -I -m unittest discover -s tools/tests` | green, 441 tests OK |

Test count (SC-002 reference, from `target/surefire-reports` and `target/failsafe-reports`):

| Suite | Classes | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|
| surefire | 453 | 7244 | 0 | 0 | 58 |
| failsafe | 3 | 4 | 0 | 0 | 0 |
| **total** | 456 | **7248** | 0 | 0 | 58 |

Later checkpoints ("test count ≥ T001") compare against 7248 tests (surefire + failsafe),
0 failures, 0 errors.

Environment notes (as in 020): run Maven with `/opt/homebrew/bin` first on `PATH`; the tmpdir must
lie outside the checkout. The full build takes longer than 10 minutes, so run it detached rather
than through a wrapper with a 600 s limit.

### 0.9.1 tool list (SC-002 reference)

`MainTest` asserts these `tools/list` names; SC-002 allows the 021 build to differ from them only by
the new tool (`download_server_log`, contract `contracts/mcp-tools-delta.md`).

| `MainTest` test | Configuration | Tools (in announced order where the test fixes the order) |
|---|---|---|
| `withoutAnOptInTheToolListIsUnchangedByFeature020` | client home on `dev/node1`, no opt-in / `queueEntryRead: false` | `list_nodes`, `get_health`, `read_knowledge`, `record_knowledge`, `find_processes`, `query_logs`, `list_inventory`, `get_inventory_item`, `find_partners`, `export_artifacts`, `export_archives`, `check_artifacts` (exact order) |
| `anOptedInGroupWithAClientHomeAnnouncesReadQueueEntry` | client home, `queueEntryRead: true` | `list_nodes`, `get_health`, `read_knowledge`, `record_knowledge`, `find_processes`, `query_logs`, `read_queue_entry`, `list_inventory`, `get_inventory_item`, `find_partners`, `export_artifacts`, `export_archives`, `check_artifacts` (exact order) |
| `serverModeServesMcpOnStdioCreatesTheAuditDirectoryAndExitsOnEof` | no CLI installation, write disabled | `list_nodes`, `get_health`, `read_knowledge`, `record_knowledge`, `find_processes`, `query_logs`, `list_inventory`, `get_inventory_item`, `find_partners`, `check_artifacts` (any order) |

### Owner decisions in force

| # | Decision | Source |
|---|---|---|
| 1 | Download timeout = `cliExportTimeout` (built-in 120 s, `config/Defaults.java` `BUILTIN_CLI_EXPORT_TIMEOUT`), not `cliTimeout` (30 s) | spec FR-013, research D-15, plan open question 1 (decided) |
| 2 | Plan open question 2 at its default: `trace_log_read` needs no opt-in, so `list_nodes` lists it additively for every node with a client and the 6.1 `cliOnly` summary lists it as refused (golden texts with capability lists change by one name), unless the owner overrides | plan "Open questions for the owner" 2 |

## Orchestrator notes

- T007: the refusal messages of the other folder rules still say "Archives with clear-text secrets…"
  (kept identical by T007's done criterion). When the log caller is wired (T027/T028), check that
  a log refusal does not talk about archives; if it does, add a subject for those messages too.

## Checkpoint stage 1 (after T007, commit fdb82ee8)

- `mvn -o -q clean verify` (tmpdir outside the checkout): exit 0; 7309 tests (surefire + failsafe),
  0 failures, 0 errors, 58 skipped (baseline 7248; +61 from T003–T007).
- `git diff --stat main -- src/test/resources/fixtures/queue-entry`: empty.
- Code review T003–T007: one independent task review each, all APPROVED in round 1
  (`reviews/T003-r1.md` … `reviews/T007-r1.md`); minors: T004 (public HelperRequestTest fixture,
  revisit in T036), T005 (Login.REJECTED inferred from AUTH_FAILED), T007 (import style).
- T008 minor: `config/GroupConfig.java:17` javadoc of `unsupported` still names only
  `queueEntryRead`; fold into T042 (configuration view) or the docs pass.
- T009 open point for T031 (wiring): `NodeOverview.refusal` does not use matrix notes, so
  `list_nodes` would say "… does not support downloading the trace log yet" instead of "not yet
  verified" while a (version, log) pair is unrecorded. Decide in T031 whether to show the note.
- T014: the global `*.log` rule in `.gitignore` had kept the T013 `.log` fixtures out of commit
  1114bd18; T014 adds them. The orchestrator widened the exception to
  `!src/test/resources/fixtures/server-log/**/*.log` so the recorded `v8_1`/`v9_0` `.log`
  fixtures of T021/T033/T048/T049 are not silently ignored either.
- T015 minors for later tasks: (1) T018 — release the decoded window text before `maskLines`
  (read figures into locals) and confirm the D-13 memory bound; (2) T030 (output mapping) — the
  no-whole-line variant has no `charset` and two hints; render it deliberately and align
  contracts/mcp-tools-delta.md:166.
- T016 minors (test-only, LogWindowReaderTest): add a test for '\n' only at `size - 1`, and assert
  `linesBefore() == 0` in the no-line-break test; handed to T018 (same package).
- T018 minor for T028: `ServerLogService` must not keep its `LogWindow` reference while
  `LogExcerpt.build` runs (pass it directly), so the 50 MiB window bytes can be collected before
  masking (D-13).

## Checkpoint phase 2 (after T020, commit ad63b811)

- `mvn -o -q clean verify`: exit 0; 7467 tests, 0 failures, 0 errors, 58 skipped.
- Code review of phase 2: T008–T020 each reviewed independently, all APPROVED in round 1
  (`reviews/T008-r1.md` … `reviews/T020-r1.md`); open minors carried forward are listed above.
- T023/T025 (checked against the T021 recording, structure only): the real 8.1.17 stdout of
  `trace --download` has 4 lines and **no `n-OK:` prefix** on any line; the success line is
  `Trace data (<n> MB) stored to: <path>`. `CliOutputClassifier.classify` reports success only for
  an `n-OK` line, so T024's recorded fixture must keep the real shape and T025 must recognise
  success from the exit code + the `Trace data … stored to:` line + the written file, not from
  `n-OK`. T023's success test uses `1-OK: Trace data …`, which is a reconstruction.
- T028 minor for T030: merge `LogExcerpt.hints()` into the top-level `hints` of the tool output
  (contract "Output" has no `excerpt.hints`) and assert at least one variant text, e.g. "No line
  of the scanned window contains the search text".
