# Quickstart: Editing INUBIT Repository Files (feature 008)

This is a validation guide. Contracts: [configuration-delta.md](contracts/configuration-delta.md) and
[mcp-tools-delta.md](contracts/mcp-tools-delta.md). Decisions: [research.md](research.md).

## Offline (always)

```bash
mvn -q clean verify
mvn -q -Dtest=NoCustomerIdentifiersTest test     # with the local identifier lists; it must run, not skip
```

Expected: all green. In particular:

| Scenario | Proof |
|---|---|
| Switch default and inheritance, explicit `WRITE` on a non-development node or on production → error, inherited `WRITE` → read-only, group without development node → warning (US2) | `ConfigValidatorTest`, `ConfigLoaderTest`, `EffectiveNodeConfigTest` |
| `--check-config` and `list_nodes` show the switch | `ConfigSummaryTest`, `ListNodesToolTest` |
| Read-only node: `REPOSITORY_WRITE_DENIED`, nothing sent, audited, also for a diagram-group import with one referenced file (US2) | `RepositoryImportTest` on `ImportHarness` + `FakeServer`: no `importRepository`/`importArchive` launch; audit `REFUSED` |
| Repository-path import: preview with `modify`, a server code even with `CLIENT`, then backup, import, byte verification, write-back and commit (US1) | `RepositoryImportTest` |
| Diagram-group import includes directly and transitively referenced files (also through an unchanged module), repository files go first, and a repository-only group import with a tag imports only the schema and tags the group | `RepositoryImportTest` (T029), `RepositoryClosureTest` (domain) |
| Module import includes referenced files | `RepositoryImportTest` |
| New file: content type from the table, unknown extension refused; NEW but on the node → `identical` or `modify`+`existing` | `RepositoryImportTest`, `RepositoryMetadataTest` |
| Conflict, deleted file in or out of scope, other owner, key material incl. certificates, `${secret:`, limits, path tricks | `RepositoryChangeSetTest` (`ChangeSetBuilder`), `RepositoryImportTest` |
| Failure after sending: repository files rolled back first, created ones in `createdNotRemoved` | `RepositoryImportFailureTest` (import NOK, verify mismatch, artifact import NOK after repository import) |
| Restore of a development backup with repository files (also repository-only); read-only now → refused; changed again → `CONFLICT`; audit `repository.<n>` entries; deployment backup ignores the switch (US4) | `RepositoryRestoreTest`, existing `RestoreDeploymentTest` unchanged |
| Repository-path export: recursive, limits, case collision, key material withheld, one commit with `Server-State` (US3) | `WorkspaceServiceRepositoryExportTest`, `ArchiveCodecTest` |
| Group export writes the transitive closure | `WorkspaceWriterTest` |
| `check_artifacts` reports missing nested references in repository files | `ArtifactCheckServiceTest` |
| Preview lists other users (cap 20 + report) and writes the repository diff | `RepositoryImportTest` |
| Audit: one `repository.<n>` entry per file, no content | `RepositoryImportTest` reads the JSONL |
| `REPOSITORY_WRITE_DENIED` documented, error order, docs quote the descriptions | `ToolErrorTest`, `DevelopmentToolsReferenceTest`, `ArtifactToolsReferenceTest` |
| SC-001 timing with scripted delays (20 files in 3 folders) | `RepositoryImportTimingTest` (prints the times) |

## Live probes (before implementation, with the person's approval)

Only on the person's **personal test repository area** of the development node. Never touch shared
folders. Record neutralized outputs as fixtures (research D-15).

1. **P-1 content types**: export existing `.xsl`, `.xslt`, `.xsd`, `.wsdl` and `.xml` files of the
   test area, note `contentType`. Import one synthesized new file per extension and confirm by
   re-export that the content type is kept.
2. **P-2 folder import and verification**: import two new files (one in a new subfolder) and one
   changed file, re-export the folder, compare bytes (LF, no trailing newline), re-import the old
   content. Note the durations of one export and one import.
3. **P-3 tag coverage**: tag the personal test diagram group whose module imports schema A, which
   includes schema B. Check whether B carries the tag.

## Live (opt-in, after implementation)

Prerequisites: a profile copy whose development node has `development.repository: WRITE`, the
personal test diagram group and the personal test repository area.

1. `export_artifacts(target: dev, repositoryPaths: ["<test folder>"])` → files below
   `repository/Root/<owner>/<test folder>/`, one commit.
2. Change one schema in the workspace. `import_artifacts(node: dev/node1, repositoryPaths:
   ["<test folder>/a.xsd"], reason: "live test")` → preview with `modify`, `repositoryDiff` and a
   code. Call again with the code → `EXECUTED`, `backupRef`, commit.
3. Set the node to `READ_ONLY` in the copy, restart, repeat with another change →
   `REPOSITORY_WRITE_DENIED`, nothing sent.
4. `restore_backup` with the `backupRef` from step 2 (switch back to `WRITE`) → the previous
   content, verified.
5. Check that nothing outside the test folder changed (export of the test area before and after).

## Validation record (T049)

**2026-10-17, commit `e03d667`: offline validation done. Live steps 1–5 are pending the person's
approval and were not run.**

- `mvn -q clean verify`: green on the first run. 208 test classes, 2775 tests, 0 failures,
  0 errors, 3 skipped. The 3 skipped tests are the opt-in corpus tests (`ArchiveCorpusTest`,
  `StructureCorpusTest`, `XsltCorpusTest`; `INUBIT_MCP_ARCHIVE_CORPUS` / `INUBIT_MCP_XSLT_CORPUS`
  not set). The known intermittent `SystemProcessLauncherTest` failure did not occur, so the build
  was not rerun.
- `NoCustomerIdentifiersTest` ran (not skipped) inside the build with the local identifier lists
  (denylist, neutralize map, synthetic map and allowlist all present): 1107 files scanned, no
  finding.
- `mvn -q -Dtest=NoCustomerIdentifiersTest test` on its own: 20 tests, 0 failures, 0 skipped;
  1109 files scanned (the 1107 git files plus the two jars the build had left in `target/`),
  no finding.
- SC-001 harness timing from `RepositoryImportTimingTest` (20 files in 3 folders, scripted StartCLI
  delays): preview 8834 ms (own share 734 ms), confirmed import 19745 ms (own share 745 ms).
- Live (opt-in) steps 1–5: not run, pending the person's approval. The task stays open until
  they have been run and recorded here.
