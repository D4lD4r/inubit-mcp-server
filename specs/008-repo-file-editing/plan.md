# Implementation Plan: Editing INUBIT Repository Files on a Development Stage

**Branch**: `008-repo-file-editing` | **Date**: 2026-10-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/008-repo-file-editing/spec.md`

## Summary

The development tools learn to write INUBIT repository files (schemas, stylesheets, WSDLs). The
switch for this is `development.repository: READ_ONLY | WRITE` per development node; the default is
`READ_ONLY`. No new tool is added. Instead:

- `export_artifacts` gets a `repositoryPaths` scope;
- `import_artifacts` gets a `repositoryPaths` scope and includes referenced repository files in
  diagram-group and module imports, following references transitively;
- `restore_backup` also restores repository files of development calls.

The building blocks of feature 005 (repository export and import through StartCLI, `RepositoryArchive`,
byte-identical verification) are reused. The feature-004 import pipeline is extended at the places
where it ignores repository files today. All repository logic lives in one new collaborator,
`RepositoryStep`.

**Server-enforced sequence of an import with repository files.**

Preview:

1. Scope validation and the development guard (unchanged). Then build the change set:
   - repository files are classified instead of refused;
   - the closure decides what is included (D-3, D-4);
   - local refusals happen without contacting the node (D-4);
   - a read-only pre-check runs (D-5).
2. Feature-003 checks, including nested repository references (D-3).
3. Node state:
   - artifact exports as before;
   - one repository export per parent folder;
   - classification into `create`, `modify`/`existing`, `identical` or `CONFLICT` (D-5).
4. Switch check: if anything is to be sent and the node is `READ_ONLY`, refuse with
   `REPOSITORY_WRITE_DENIED`.
5. Challenge with a **server-issued code, always** (D-6), bound to the inputs, the workspace hash
   and the node fingerprint, which includes the repository entries. It carries
   `repositoryReferences` and `repositoryDiff`.

Execute (with the code):

1. Re-check as in feature 004, then backup with the repository exports (D-8), then the `PENDING`
   audit record.
2. `importRepository`, then `importArchive` if there are workflows or modules, then verification:
   repository files byte for byte, artifacts as before.
3. Write-back and commit with `Server-State`, then the optional tag (D-12).
4. On failure: roll back the repository files from the backup, then the artifacts. Created files
   are listed in `createdNotRemoved`.

## Technical Context

**Language/Version**: Java 21 (unchanged).

**Primary Dependencies**: unchanged, no new dependency (MCP Java SDK, Jackson YAML, Saxon-HE,
logback).

**Storage**:
- the workspace and its git history (feature 003);
- backups (feature 004), with the manifest persisting `repositoryPaths` for `IMPORT` too;
- reports under `<workspace>/.reports/`;
- the audit log.

**Testing**:
- JUnit 5 and AssertJ;
- `ImportHarness` with `FakeServer`, extended with content types, folder exports and transitive
  references;
- `ScriptedProcessLauncher` with neutralized recordings of probes P-1 to P-3;
- real git in `@TempDir`;
- an opt-in live test on the personal test repository area only.

**Target Platform**: macOS and Linux, because this needs the CLI (unchanged).

**Project Type**: a single Maven module, built as a shaded JAR.

**Performance Goals**: SC-001, a confirmed import of up to 20 files in at most 3 folders within
2 minutes. Each operation needs one StartCLI call per folder (D-5) instead of one per file; P-2
measures the real duration.

**Constraints**:
- writes only on development nodes with an effective `WRITE`;
- never delete anything;
- never write below another owner's root;
- never import key material, certificates included;
- the server code is always required for repository writes;
- results are bounded (references capped at 20 plus a report file, diffs as files);
- the reason is not verified on repository files, because INUBIT ignores their version comment.

**Scale/Scope**:
- 3 widened tools and 1 widened field in `list_nodes`;
- about 7 new production classes: `RepositoryMode`, `RepositoryReferences`, `RepositoryClosure`
  and `RepositoryPaths` (both domain), `RepositoryStep`, `RepositoryMetadata`, `RepositoryUpload`;
- extensions in `ChangeSetBuilder`, `ConflictDetector`, `ImportService`, `BackupStore`,
  `WorkspaceService`, `WorkspaceWriter`, `ArchiveCodec`, `ArtifactCheckService`,
  `ConfigValidator`, `ConfigSummary`, `EffectiveNodeConfig`, `DevelopmentGuard`, the schemas and
  the docs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ⚠️ A new kind of write: shared files with a wide blast radius | ✅ <ul><li>Off by default.</li><li>Effective only on development nodes, never in production (startup error).</li><li>A server-issued code is always required (D-6), bound to the content and to the node state.</li><li>The preview shows the other users and a diff (D-5).</li><li>`import_artifacts` and `restore_backup` stay annotated as destructive.</li><li>Inputs are validated before any StartCLI call: path pattern, `.`/`..`, owner root, limits.</li><li>StartCLI runs only with argument arrays and the existing `repositoryPath` builder rule.</li><li>Nothing is deleted; created files are reported.</li></ul> |
| II. Secrets Stay Out | ⚠️ Repository files may hold key material | ✅ <ul><li>Key material is refused by the union of both detectors, certificates included (D-4).</li><li>`${secret:` placeholders are refused.</li><li>Withholding on export is unchanged.</li><li>Audit, reports and results carry paths and hashes only (D-11).</li></ul> |
| III. Test-First | ✅ | ✅ <ul><li>Every step starts with a failing test on `ImportHarness`/`FakeServer` and the recorded probe outputs.</li><li>The switch rules, refusals and secret rules have dedicated tests (quickstart table).</li><li>The live test is opt-in and limited to the personal test area.</li></ul> |
| IV. Use-Case-Oriented Tools | ✅ no new tool | ✅ The scope is added to the existing task-shaped tools. There is no raw repository passthrough. |
| V. Adapter Isolation | ✅ | ✅ <ul><li>The scanner, metadata synthesis and archive building live in `adapter/archive/v81` behind `ArtifactInspectorPort`, `ReleaseArchivePort` and `ArchiveCodecPort`.</li><li>The CLI options already exist in `adapter/cli`.</li><li>The logic lives in `application/RepositoryStep`.</li><li>The closure and the path rule are pure domain classes, so `adapter/archive` never reaches `application`.</li><li>`PackageBoundaryTest` stays green.</li></ul> |
| VI. Bounded Output | ✅ | ✅ <ul><li>Up to 50 files per import and 20 paths per export.</li><li>An export writes at most 500 files and 100 MiB.</li><li>References are capped at 20 plus a report file; diffs go to files.</li></ul> |
| VII. Observability & Auditability | ✅ | ✅ <ul><li>Every repository import and restore is audited.</li><li>There is one `repository.<n>` entry per file (operation, path, hash).</li><li>Refusals name the switch.</li><li>The `PENDING` record comes before any send (fail closed, unchanged).</li></ul> |
| Tech constraints | ✅ | ✅ No new dependency, a single artifact, artifacts in English, user docs updated in the same change. |

**Result**: PASS. Both ⚠️ items are resolved by the design (D-4, D-5, D-6, D-11). There is no
Complexity Tracking entry.

## Project Structure

### Documentation (this feature)

```text
specs/008-repo-file-editing/
├── plan.md
├── research.md                 # D-1…D-15, probes P-1…P-3
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── configuration-delta.md
│   └── mcp-tools-delta.md
├── checklists/requirements.md
├── reviews/                    # spec-r1.md, spec-r2.md (+ plan reviews)
└── tasks.md                    # /speckit-tasks
```

### Source Code

```text
src/main/java/de/dadecker/inubit/mcp/
├── config/                 # RepositoryMode (new); DevelopmentConfig.repository; Defaults built-in;
│                           # EffectiveNodeConfig.Development.repositoryWrite; ConfigValidator.checkRepositorySwitch;
│                           # ConfigSummary node line
├── domain/model/           # ImportScope.repositoryPaths; ChangeSet.repository; DevelopmentPolicy/NodeSummary.repositoryWrite;
│                           # ImportPreview (+repositoryReferences, repositoryDiff); WriteOutcome tag.repositoryFiles;
│                           # RepositoryReference, RepositoryUpload, RepositoryClosure, RepositoryPaths (new);
│                           # ErrorCode.REPOSITORY_WRITE_DENIED
├── domain/port/            # ArtifactInspectorPort.repositoryReferences; ReleaseArchivePort.repositoryArchiveFromFiles;
│                           # ArchiveCodecPort.prepareRepository
├── adapter/archive/v81/    # RepositoryReferences (new), RepositoryMetadata (new); WorkspaceWriter transitive closure +
│                           # case-collision check; ArchiveCodec.prepareRepository; V81ReleaseArchives
├── application/            # RepositoryStep (new); ReleasePlanner uses RepositoryPaths; ChangeSetBuilder classification; ConflictDetector
│                           # fingerprint incl. repository; ImportService (scope, switch, forced SERVER, send/verify/
│                           # rollback/write-back/restore delegation); BackupStore manifest; DevelopmentGuard.forRepositoryWrite;
│                           # WorkspaceService repository export; ArtifactCheckService nested references
├── mcp/tools/              # ImportArtifactsTool, ExportArtifactsTool (repositoryPaths); descriptions
└── Wiring.java             # unchanged registration; passes the new ports
src/main/resources/schemas/ # import_artifacts / export_artifacts / restore_backup / list_nodes input+output
src/test/…                  # FakeServer (+contentType, folder export, transitive refs), ImportHarness, tests per quickstart table
src/test/resources/fixtures/v8_1/cli/  # export_repository_folder.*, import_repository_new_files.*, export_repository_after_import.*
docs/                       # setup.md (development.repository, workspace closure), tools.md (scopes, fields, code),
                            # live-tests.md (repository section); research/spike-development-deployment.md (section 11: P-1…P-3)
CHANGELOG.md                # [Unreleased]: feature 008
```

**Structure Decision**: the same single module. The stages below are each reviewed
(Creator/Reviewer), and each ends with a green `mvn verify`:

1. **Probes and fixtures**: run P-1 to P-3 with the person's approval and record the neutralized
   fixtures. Extend `FakeServer`.
2. **Configuration (US2)**: `RepositoryMode`, resolution, `checkRepositorySwitch`, `--check-config`,
   `list_nodes`, the error code.
3. **References (FR-008, FR-011)**: scanner, closure, transitive export in `WorkspaceWriter`, nested
   references in `check_artifacts`.
4. **Repository-path export (US3)**: `export_artifacts` scope, `prepareRepository`, limits, case
   collisions.
5. **Import preview (US1, US2)**: `ImportScope`, change-set classification and refusals,
   `RepositoryStep` node state, conflicts, switch check, forced server code, preview fields, audit.
6. **Import execute (US1)**: backup, repository import, verification, rollback, write-back, tag
   information, manifest fix.
7. **Restore (US4)**: restore set, conflict check, switch, deployment path unchanged.
8. **Docs, live test and validation**: setup.md, tools.md, live-tests.md, CHANGELOG, timing test,
   quickstart run.

## Complexity Tracking

None.
