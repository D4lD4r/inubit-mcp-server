# Implementation log: 024-unsupported-node-tools

Findings, decisions and checkpoints of the implementation phase (`d4ld4r-speckit-duo:implement`).

## T001 – Baseline (2026-11-12)

Branch `024-unsupported-node-tools` at `3ed19f71`, before any implementation change.
JDK 25.0.5, Maven 3.10.0, Python 3.15.2, macOS.

**Base (D-30)**: `021-server-log-download` (PR #84) is **not** merged into `main`, so this branch
stays on the 021 base; no rebase. Rebase on `main` once 021 is merged. At the time of the baseline:

- `origin/021-server-log-download` has two commits not in this branch (`b8ebde14`, `dbeb4a52`,
  test portability to Windows); they arrive with the rebase on `main`.
- `main` has one commit not in this branch (`ae4b425e`, #86, home guard test fix).

| Check | Command | Result |
|---|---|---|
| Java build | `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-024-tmp` | green (exit 0) |
| Python tools | `python3 -I -m unittest discover -s tools/tests` | green, 441 tests OK |

Test count (SC-005 reference, from `target/surefire-reports` and `target/failsafe-reports`):

| Suite | Classes | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|
| surefire | 482 | 7980 | 0 | 0 | 58 |
| failsafe | 3 | 4 | 0 | 0 | 0 |
| **total** | 485 | **7984** | 0 | 0 | 58 |

Later checkpoints ("test count ≥ T001", "no test removed") compare against 7984 tests (surefire +
failsafe), 0 failures, 0 errors.

Environment notes (as in 020/021): run Maven with `/opt/homebrew/bin` first on `PATH`; the tmpdir
must lie outside the checkout. The full build takes longer than 10 minutes, so run it detached.

### Tool lists (SC-005 reference)

SC-005: with no opt-in of this feature set, the tool list may differ from these lists only by tools
whose opt-in is set. Lists marked "exact order" are asserted with `containsExactly` /
`containsExactlyElementsOf`.

Shorthand used below:

- **L13** = `list_nodes`, `get_health`, `read_knowledge`, `record_knowledge`, `find_processes`,
  `query_logs`, `download_server_log`, `list_inventory`, `get_inventory_item`, `find_partners`,
  `export_artifacts`, `export_archives`, `check_artifacts` (exact order)
- **L14** = L13 with `read_queue_entry` between `query_logs` and `download_server_log` (exact order)

`MainTest`:

| Test | Configuration | Tools |
|---|---|---|
| `serverModeServesMcpOnStdioCreatesTheAuditDirectoryAndExitsOnEof` | no CLI installation, write disabled | `list_nodes`, `get_health`, `read_knowledge`, `record_knowledge`, `find_processes`, `query_logs`, `list_inventory`, `get_inventory_item`, `find_partners`, `check_artifacts` (any order) |
| `withoutAnOptInTheToolListIsUnchangedByFeature020` | client home on `dev/node1`, no opt-in / `queueEntryRead: false` | L13 |
| `anOptedInGroupWithAClientHomeAnnouncesReadQueueEntry` | client home, `queueEntryRead: true` | L14 |
| `sinceThe81TraceRecordingAnEightOneAndAnAutoNodeAnnounceTheDownload` | client home, `V8_1` and `AUTO`; without client home | L13 / L13 / no `download_server_log` |

`ToolAnnouncementTest` (exact lists):

| Test | Configuration | Tools |
|---|---|---|
| `withoutAnOptInTheToolListIsUnchangedByFeature020` | `AUTO`, client home, no opt-in / `queueEntryRead: false`, production matrix | L13 |
| `anOptedInAutoNodeWithAClientHomeAnnouncesTheQueueEntryRead` | `AUTO`, client home, `queueEntryRead: true` | L14 |
| `theKnowledgeToolsFollowGetHealthEvenWithoutAnyReadTool` | two `V6_1` groups, first four names | `list_nodes`, `get_health`, `read_knowledge`, `record_knowledge` |
| `aClientHomeWithoutAnyUnsupportedSettingAnnouncesTheDownload` | `V8_1`, client home, `TraceMatrix.admittingEightOne()` | L13 |
| `onTheProductionMatrixThe81And90RecordingsAnnounceTheDownload` | `V8_1` / `AUTO` with client home; `V8_1` + `serverLogRead: true`; `V9_0` with 9.0 client, with and without `serverLogRead: true` | L13 in all five cases |
| `anOptedInNodeWithAClientHomeAnnouncesTheDownloadForTheWebServiceLog` | `V8_1`, client home, `serverLogRead: true`, `WebServiceMatrix.admittingEightOneWithoutTrace()` | L13 |

`ToolAnnouncementTest` (membership only): `ALWAYS` = `list_nodes`, `get_health`, `read_knowledge`,
`record_knowledge`, `check_artifacts`; `READS` = `find_processes`, `query_logs`, `list_inventory`,
`get_inventory_item`, `export_artifacts`, `export_archives`, `find_partners`; 6.1-only profiles
announce no read tool (production matrix) or only `find_processes` (flipped matrix); no write tool
(`restart_process`, `kill_process`, `import_artifacts`) in read-only or production groups without
opt-in.

### Decisions in force

| # | Decision | By | Source |
|---|---|---|---|
| 1 | Cleanup resets (`trace --reset`) of recorded leases run also when write tools are off (read-only mode, `logLevelWrite` or the production opt-in removed); the reset also clears other users' temporary levels on that node. Constitution I deviation, bounded (recorded leases only, own-node check first, audited, shown in `get_health` and `--check-config`) | owner, 2026-11-12 | spec Clarifications; plan Constitution Check and Complexity Tracking |
| 2 | Plan open question 1 at its default: the log-level write spike uses logger `com.inubit.ibis.cli` at `INFO`, reset right after; the owner approves the concrete action per version before T039/T061, unless the owner overrides | default, owner may override | plan "Open questions for the owner" 1, research D-28 |
| 3 | Leases carry a generation (compare-and-set) and a cross-process per-node lock (generalized `KnowledgeLock`) covers the own-node lookup and the StartCLI runs of set, reset and cleanup | plan review round 1 | plan "Decisions taken in plan review round 1"; research D-12, D-14, D-16 |
| 4 | Fail-closed audit for cleanup resets: if the `PENDING` audit record cannot be written, no reset is sent, the lease keeps its state with reason `AUDIT_NOT_WRITABLE`, ERROR logged, retried next tick | plan review round 1 (orchestrator) | plan "Decisions taken in plan review round 1"; research D-16, D-22 |
| 5 | **Creator decision**: an orphaned lease (owner no longer running) is taken over at once at startup and on every tick; expired or failed → reset at once, otherwise reset at its expiry by this instance. US4-3 is read as "the next start takes the lease over and resets the node no later than its expiry" | creator, owner may override | research D-16; plan "Decisions taken in plan review round 1" |

### Feature 022

`022-full-interface-coverage` is **not** merged into `main` (no pull request yet, as of the baseline). Consequences (plan "Touch points", research D-18):

- T047 adds the `WriteGuard` overload (022's `ChangeGuard` is not available).
- T074 does not run here; whichever feature merges second adds `trace -nl`, `trace -r` and
  `cachemode nodelist` to the coverage list, switches to `ChangeGuard` and merges the `CliCommand`
  `trace` forms.

### Live spikes

The orchestrator asked the operator on 2026-11-12 for the read spikes T018 (8.1) and T060 (9.0)
(a run with input/output logging to name); they run in parallel to the offline work. The agent proposes the concrete action per spike and waits for the operator's go.

## T074 – Feature 022 integration, partial (2026-11-14)

`main` now holds feature 022 user stories 1 and 2 (#87, #88, #89); this branch merges it.

- Coverage list: rows `trace --nodelevel`, `trace --package`, `trace --reset` and
  `cachemode nodelist` (excluded, not used: the answering node comes from the helper).
  The `trace` rows are written as the server sends them, like `trace --download`: check 1 of
  `InterfaceCoverageCheckTest` needs a row per `CliCommand.OPTIONS` pair; the vendor's short
  forms are `-nl`, `-p` and `-r`. They are `planned` (story US4, 022's area A writes), not yet
  `reachable`: check 3 accepts `reachable` only for a registered tool, and the log-level tools are
  not announced while `log_level_write` is "not yet verified" on 8.1 and 9.0. The commit that
  verifies `log_level_write` on a line makes the three rows `reachable` there
  (`set_temporary_log_level`, `reset_temporary_log_level`). `docs/interface-coverage.md`
  regenerated (`mvn -q -Dcoverage.write=true test -Dtest=InterfaceCoverageDocsTest`).
- `CliCommand`: 022 has not changed it on `main`; its area A forms (`trace on|off`, `trace -l`)
  are still `planned` (US4), so nothing to merge yet. The `trace` validation admits only
  `--download`, `--nodelevel` + `--package` and `--reset`.
- **Open, 022's task**: `ChangeGuard` is not on `main` (it comes with 022's write stories). The
  log-level tools keep the `WriteGuard` overload (T047); switching them to `ChangeGuard` falls to
  022 when it adds it.
