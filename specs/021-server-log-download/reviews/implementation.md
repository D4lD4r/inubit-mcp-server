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
