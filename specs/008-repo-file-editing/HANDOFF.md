# Handoff: feature 008 (editing INUBIT repository files), paused 2026-10-14

## Where things stand

- **Branch:** `008-repo-file-editing`. Every finished task is committed, and the working tree is
  clean.
- **Last commit:** `T029+T031: send, verify and write back repository files; interim guard removed`.
- **Build:** `mvn -q verify` is green (about 2,714 tests, 3 opt-in corpus tests skipped).
- **Progress:** 32 of 50 tasks are done (see [tasks.md](tasks.md)). The open tasks:
  - **US1 (MVP):** T032+T033 (failure tests and repository rollback), T034 (tag information) and
    T035 (timing test).
  - **US3:** T036–T039 (repository-path export).
  - **US4:** T040–T043 (restore).
  - **Polish:** T044–T049 (docs, live test, validation).
- **Workflow:** `/speckit-duo:implement <tasks>`. Each task goes implementer → independent reviewer,
  and the review files land in `reviews/`. Run a "write failing tests" task together with its
  implementation task, so every commit stays green: T032+T033 as one unit, then T034, T035, and so
  on.

## Carry-over notes

These are recorded in the task texts:

- **T033:**
  - re-import the previous content of the repository files that changed on the node, from the
    backup's repository exports, before the artifact rollback, and verify it;
  - remove the interim "not rolled back by this version" FAILED override;
  - decide how a TIMEOUT of `importRepository` is handled;
  - stop pointing to `restore_backup` for repository files before US4.
- **T044/T045/T048:** the docs need several updates (listed in those tasks):
  - the transitive closure, including message-type schemas;
  - the `REPOSITORY_REF_MISSING` row;
  - the example line for `repository: …`;
  - the stale "any change below `repository/` is refused" line;
  - the CHANGELOG "Fixed" entry for decoded paths.
- **Open decision:** FR-015a asks for the reason in the workspace history entry, but the import
  commit only carries the audit id. Decide this before T045/T048.

## Outside this feature

- **Bug in released versions:** feature 005's `deploy_release` misreads every repository import as
  failed, because StartCLI prints `n-OK` on the same line as the progress text. It is fixed on this
  branch (T002, `CliOutputClassifier`). A patch release from `main` was offered and not yet decided.
- **Left on the development node** after the approved probes: probe files in the person's test
  folder `P8/` (removable only by hand in the Workbench) and the probe tag on the test diagram
  group. The probe outputs are outside the repository, under `~/.inubit-mcp/<profile>/spike/p8/`.
