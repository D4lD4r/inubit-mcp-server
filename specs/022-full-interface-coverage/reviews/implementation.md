# Implementation log: 022-full-interface-coverage

Findings, decisions and checkpoints of the implementation phase (`d4ld4r-speckit-duo:implement`).

## T001 – Baseline (2026-11-12)

### 1. Feature 021 and the base of this branch

Feature 021 is **not merged** into `main` yet; another session is finishing it.

| Ref | Commit |
|---|---|
| `git merge-base HEAD origin/021-server-log-download` | `baaaf5a6` (T042: configuration view for the server-log opt-in, 2026-11-12) |
| `git merge-base HEAD origin/main` | `5ec65996` (chore(release): 0.9.1 (#83)) |
| `origin/021-server-log-download` at T001 | `2ac5775e` (14 commits past the base) |

This branch is based on the 021 branch at `baaaf5a6`, not on `main`.

Owner decision (2026-11-12): 022 starts in parallel on this branch as it is. The merge of 021 and
the rebase of this branch onto `main` are deferred until before the US1 PR is opened.

After the 021 merge and the rebase, the test count and the `ToolAnnouncementTest` lists below are
taken again before the US1 PR opens (021 still changes that test, e.g. it renames the 8.1 trace
announcement test), and the new values replace the references in sections 2 and 3.

### 2. Build and tests

Branch `022-full-interface-coverage` at `704d2ef3`, before any implementation change.
JDK 25.0.5, Maven 3.10.0, Python 3.15.2, macOS 27.2.

| Check | Command | Result |
|---|---|---|
| Java build | `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-022-tmp` | green (exit 0) |
| Python tools | `python3 -I -m unittest discover -s tools/tests` | green, 441 tests OK |

Test count (from `target/surefire-reports` and `target/failsafe-reports`):

| Suite | Classes | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|
| surefire | 476 | 7843 | 0 | 0 | 58 |
| failsafe | 3 | 4 | 0 | 0 | 0 |
| **total** | 479 | **7847** | 0 | 0 | 58 |

Later checkpoints ("test count ≥ T001") compare against 7847 tests (surefire + failsafe),
0 failures, 0 errors.

Environment note (as in 021): the full build takes longer than 10 minutes, so run it detached
rather than through a wrapper with a 600 s limit (a first wrapped run was killed at that limit
with no failing test).

### 3. Tool list of `ToolAnnouncementTest` (SC-009 reference)

`src/test/java/de/dadecker/inubit/mcp/ToolAnnouncementTest.java` fixes these lists. The test
wires no write tools, so it announces read tools only.

Exact lists (`containsExactly` / `containsExactlyElementsOf`, announced order):

| Test | Configuration | Tools |
|---|---|---|
| `withoutAnOptInTheToolListIsUnchangedByFeature020` | `AUTO`, client home, no opt-in or `queueEntryRead: false` | `list_nodes`, `get_health`, `read_knowledge`, `record_knowledge`, `find_processes`, `query_logs`, `download_server_log`, `list_inventory`, `get_inventory_item`, `find_partners`, `export_artifacts`, `export_archives`, `check_artifacts` |
| `anOptedInAutoNodeWithAClientHomeAnnouncesTheQueueEntryRead` | `AUTO`, client home, `queueEntryRead: true` | `list_nodes`, `get_health`, `read_knowledge`, `record_knowledge`, `find_processes`, `query_logs`, `read_queue_entry`, `download_server_log`, `list_inventory`, `get_inventory_item`, `find_partners`, `export_artifacts`, `export_archives`, `check_artifacts` |
| `aClientHomeWithoutAnyUnsupportedSettingAnnouncesTheDownload` | `V8_1`, client home, `TraceMatrix.admittingEightOne()` | same 13 tools as the first row |
| `onTheProductionMatrixThe81TraceRecordingAnnouncesTheDownload` | `V8_1` and `AUTO`, client home, production matrix | same 13 tools as the first row |
| `theKnowledgeToolsFollowGetHealthEvenWithoutAnyReadTool` | two `V6_1` groups | first four: `list_nodes`, `get_health`, `read_knowledge`, `record_knowledge` |

Constant sets used by the other tests:
- `ALWAYS`: `list_nodes`, `get_health`, `read_knowledge`, `record_knowledge`, `check_artifacts`
- `READS`: `find_processes`, `query_logs`, `list_inventory`, `get_inventory_item`,
  `export_artifacts`, `export_archives`, `find_partners`

Tool count reference (SC-007): `src/main/java` has 22 tool names before this feature, the 14 above
plus the write tools `tag_artifacts`, `import_artifacts`, `set_active`, `deploy_release`,
`restore_backup`, `restart_process`, `kill_process`, `run_e2e_test`.

### 4. Plan defaults in force

The three open questions of `plan.md` ("Open questions for the owner") stay on their defaults
unless the owner overrides them:

1. US5 as rewritten (run-time data export as a change with write enablement and confirmation;
   archives stay on the INUBIT hosts).
2. `get_server_settings` as its own tool (not an `includeSettings` flag on `get_health`).
3. Version deletions offered within `deleteWorkflow` / `deleteModule`, under the objects opt-in
   and with a backup.

## Rebase onto main (2026-11-12, after T009)

Owner request: rebase onto the current `main`. `main` was at `f69e9b6a` (#85, tests kept out of
the real user home); feature 021 (PR #84) is still open. The whole branch (the 021 commits up to
T042 plus the 022 commits T001–T009) was rebased onto `f69e9b6a` without textual conflicts; the
old head is kept as `backup/022-pre-rebase` (local only). Full build afterwards
(`mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-022-tmp`): green, 7948
tests, 0 failures, 0 errors, 58 skipped. When PR #84 is squash-merged, this branch is moved with
`git rebase --onto origin/main <last 021 commit>` so that only the 022 commits are replayed.

## T011 – `server_settings` before its tool (2026-11-12)

T011 adds `SERVER_SETTINGS` in phase 2, before `get_server_settings` exists (T022). Until T025/T026
flip it, every 8.1 and 9.0 node lists `server_settings` under `notUsable` ("not yet verified"), and
a pinned 8.1 node reports `partially_supported`. This is accepted knowingly: phases 1–3 ship in
one PR (US1), so no release from this branch shows the capability without its tool; plan.md's
"capabilities per story" holds per PR. Later stories add their capability in their port task.
