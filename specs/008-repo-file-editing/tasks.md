---

description: "Task list for editing INUBIT repository files on a development stage (feature 008)"
---

# Tasks: Editing INUBIT Repository Files on a Development Stage

**Input**: Design documents from `/specs/008-repo-file-editing/`

**Prerequisites**:
- [plan.md](plan.md) and [spec.md](spec.md) (clarifications 2026-10-13, reviews r1/r2)
- [research.md](research.md) (D-1…D-15, probes P-1…P-3) and [data-model.md](data-model.md)
- [contracts/configuration-delta.md](contracts/configuration-delta.md) and
  [contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md)
- [quickstart.md](quickstart.md)
- the code of features 003–005

**Tests**: REQUIRED (Constitution III, TDD). Every behaviour starts with a test that you have seen
fail on an assertion before the production code exists. `mvn -q verify` must be green after every
task.

**Organization**: by user story. US2 (the switch) comes first because it is the precondition of US1
(the import). Both are P1. US3 (repository-path export) and US4 (restore) follow.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on unfinished tasks)
- **[Story]**: US1 import edited repository files, US2 configure the switch, US3 pull repository
  files into the workspace, US4 undo a repository change

## Path Conventions

| Short form | Expands to |
|---|---|
| `main/…` | `src/main/java/de/dadecker/inubit/mcp/…` |
| `test/…` | `src/test/java/de/dadecker/inubit/mcp/…` |
| `res/…` | `src/main/resources/…` |
| `fixtures/…` | `src/test/resources/fixtures/v8_1/…` |

## Safety rules

- **While developing**: run no INUBIT command except read-only exports and REST reads.
- **Probes P-1 to P-3 and the live test**: write only to the person's **personal test repository
  area** and personal test diagram group on the development node, and only after the person has
  approved the concrete commands in chat. Never touch shared repository folders, never production,
  never delete anything.
- **Fixtures**: neutralized, with synthetic names (`/Root/jdoe/…`, `Workflow-*`, `Module-*`,
  `GRP-*`, owner `OWNERS`/`jdoe`) and synthetic secrets only. The identifier hooks must pass; never
  use `--no-verify`.
- **Configuration**: never change `~/.config/inubit-mcp/*` or the MCP registration. Live runs use a
  temporary copy of the profile.
- **Public repository**: no customer names in commit messages, PR descriptions or files.

---

## Phase 1: Setup

- [X] T001 Run probes P-1, P-2 and P-3 from research.md (D-7, D-8, D-12) against the personal test
  repository area of the development node:
  - Show the person the exact StartCLI commands (export and import paths, file names) and wait for
    an explicit "yes" before any import.
  - Store the raw outputs only in `~/.inubit-mcp/<profile>/spike/p8/`, owner-only.
  - Record:
    - the `contentType` per extension (`.xsl`, `.xslt`, `.xsd`, `.wsdl`, `.xml`);
    - whether a synthesized new file keeps its content type;
    - folder import with a new subfolder;
    - byte identity of an LF-only file without a trailing newline;
    - the duration of one repository export and one import;
    - whether a group tag covers a repository file referenced only by another repository file;
    - whether a diagram-group export puts such an indirectly referenced file into `Repository.zip`.
- [X] T002 Neutralize the T001 outputs into
  `fixtures/cli/export_repository_folder.{zip,stdout,stderr,exit}` (nested folders, one `.xsd`
  including another relatively), `import_repository_new_files.{stdout,stderr,exit}`,
  `export_repository_after_import.zip` and `export_repository_types.zip` (one file per extension).
  Document them in `fixtures/cli/README.md` (new section "Repository files on the development stage
  (feature 008)"). Consume them in contract tests:
  - `test/adapter/archive/v81/V81ReleaseArchivesTest.java` reads the nested folder export and
    `export_repository_after_import.zip`;
  - `test/adapter/cli/CliRepositoryImportTest.java` replays `import_repository_new_files.*`, and
    `test/adapter/cli/CliDeployExportsTest.java` replays `export_repository_folder.*`, both on
    `ScriptedProcessLauncher`.

  Run the identifier check before committing.
- [X] T003 Add section 11 "Repository files on the development stage (feature 008)" with the
  P-1…P-3 facts to `docs/research/spike-development-deployment.md`, plus one row per fact in its
  summary table (generic names only). Finalize the content-type table in `research.md` D-7 and in
  the `INVALID_INPUT` row of `contracts/mcp-tools-delta.md`, and decide the D-12 warning (keep it
  or drop it) from P-3.
- [X] T004 Extend `test/application/FakeServer.java`:
  - `importRepository` stores the imported `contentType` and `Description`;
  - `exportRepository(path)` also exports a folder recursively (empty result = not found);
  - its group export includes repository files referenced transitively (`inubitrepository:` and
    relative `href`/`schemaLocation` in repository files);
  - `metadata()` emits the stored `contentType`.

  The transitive group export follows what probe P-3 (T001) observed: INUBIT puts files that only
  another repository file references into `Repository.zip`, at head and by tag, so FakeServer does
  too (spike section 11, research D-3).

  Add a helper to `test/application/ImportHarness.java` that writes a repository file into the
  workspace at `<group>/<owner>/repository/Root/<owner>/<path>` with `.meta`. The per-node switch
  helper comes in T009. Self-tests go in `test/application/FakeServerTest.java`.

## Phase 2: Foundational (blocks all stories)

- [X] T005 [P] `main/domain/model/ErrorCode.java`: add `REPOSITORY_WRITE_DENIED` after
  `DEPLOY_LOCKED` and before `INTERNAL`, with Javadoc ("the node's `development.repository` is
  `READ_ONLY`; revert the files or ask for `WRITE`"). Update the exact order in
  `test/domain/model/ToolErrorTest.java`. Add the `REPOSITORY_WRITE_DENIED` row to the code table in
  `docs/tools.md` in the same task so that `DevelopmentToolsReferenceTest` stays green.
- [X] T006 [P] Test first in `test/adapter/archive/v81/RepositoryReferencesTest.java`, then
  `main/domain/model/RepositoryReference.java` (`from: WorkspacePath`, `to: String` repository path,
  `kind: URI | RELATIVE`), `ArtifactInspectorPort.repositoryReferences(WorkspacePath, byte[])` and
  `main/adapter/archive/v81/RepositoryReferences.java`. Cases:
  - `inubitrepository:` URIs in a workflow, `module.xml` and an embedded document;
  - in repository files only, relative `href`/`schemaLocation` of `xsl:import`, `xsl:include`,
    `xs:import`, `xs:include` and `xs:redefine`, resolved against the file's repository path
    (including `../`);
  - `%XX` decoding;
  - a URI as a string literal in an `xsl:variable` is **not** a reference (spike section 10);
  - unparsable XML gives no references and no exception.
- [X] T007 Refactor `main/adapter/archive/v81/WorkspaceInspector.java` and
  `main/application/ArtifactCheckService.java` to use the T006 scanner, which removes the duplicate
  `inubitrepository:` regex. The existing tests must stay green without changes.
- [X] T008 Test first in `test/domain/model/RepositoryClosureTest.java`, then
  `main/domain/model/RepositoryClosure.java`, a pure-Java class in the domain so that both
  `application` and `adapter/archive/v81` may use it (`PackageBoundaryTest`, research D-3):
  - input: start references and a function `path → Optional<List<RepositoryReference>>`;
  - a worklist over these references;
  - each path visited once, so a cycle ends;
  - `included: SortedSet<String>` only below `/Root/<owner>/`;
  - `outsideOwner` lists references into `/Root/<other>/…` without following them;
  - `referencedBy: Map<String, SortedSet<String>>`;
  - a reference to a file missing from the workspace is kept as a name and not followed.
- [X] T009 [P] Configuration core (D-1): test first in `test/config/ConfigLoaderTest.java` and
  `test/config/EffectiveNodeConfigTest.java`. This task also touches
  `main/application/DevelopmentGuard.java`, whose `admitDeploymentRestore`/`forDeploymentRestore`
  construct `DevelopmentPolicy`.
  - `main/config/RepositoryMode.java` (`READ_ONLY`, `WRITE`).
  - `DevelopmentConfig.repository: Optional<RepositoryMode>`.
  - `Defaults.BUILTIN_DEVELOPMENT_REPOSITORY = READ_ONLY`.
  - `EffectiveNodeConfig.Development.repositoryWrite`, which is "`true` only if resolved `WRITE`
    (node, then group, then defaults, then built-in) **and** `enabled` **and** not `production`".
    Keep `withTimeout` in sync.
  - `DevelopmentPolicy.repositoryWrite`. Update every test constructor (DevelopmentGuardTest,
    ImportHarness, E2eTestServiceTest, TagServiceTest, RunE2eTestToolTest, TagArtifactsToolTest,
    adapter/TestNodeConfig), and add the per-node switch helper to `ImportHarness`.
  - An unknown value is a loader error that lists `READ_ONLY, WRITE`.
- [X] T010 [P] Test first in `test/domain/model/RepositoryPathsTest.java`, then
  `main/domain/model/RepositoryPaths.java` (`passable(fullPath)`, `full(owner, relative)`,
  `relativeValid(relative)`). The StartCLI rule `^/Root(/[A-Za-z0-9_.][A-Za-z0-9_.\- ]{0,199})+$`
  without `.`/`..` segments moves here from `ReleasePlanner.repositoryPathPassable`, which then
  delegates; its tests stay green. Paths with `%`, an empty segment or a leading `/` are refused
  outright, a deliberate, stricter deviation from FR-005's normalization (research D-4). Then
  `main/domain/model/ImportScope.java`:
  - the third alternative `repositoryPaths: List<String>`, where "exactly one of `diagramGroup`,
    `modules`, `repositoryPaths`" holds;
  - 1–50 paths, unique, "relative to `/Root/<owner>`, normalized, no empty path and no root"
    (FR-005), each segment matching `^[A-Za-z0-9_.][A-Za-z0-9_.\- ]{0,199}$`, with no `.`/`..` and
    no leading `/`;
  - `describe()` gives `repository paths a, b`.

  Tests in `test/domain/model/ImportScopeTest.java`.
- [X] T011 [P] `main/domain/model/ChangeSet.java`: add `repository: List<ChangedArtifact>` (kind
  `REPOSITORY_FILE`, `ref.name` = full path `/Root/<owner>/…`, exactly one `REPOSITORY` path).
  `isEmpty`, `artifacts`, `paths`, `created`, `modified` and `existing` must include it. Add a
  compatibility constructor without `repository` (an empty list), so the call sites in
  `ImportService.java`, `ConflictDetector.java` and `ChangeSetBuilder.java` stay untouched until
  T021. Tests in `test/domain/model/ChangeSetTest.java`.
- [X] T012 [P] Archive side (D-4, D-7, D-8): test first in
  `test/adapter/archive/v81/RepositoryMetadataTest.java` and `V81ReleaseArchivesTest.java`.
  - `main/domain/model/RepositoryUpload.java`: `path`, `content`, and
    `metadata: Optional<String>`.
  - `ReleaseArchivePort.repositoryArchiveFromFiles(owner, List<RepositoryUpload>)`, which uses
    `RepositoryArchive.build`.
  - Key material uses the existing `ReleaseArchivePort.keyMaterial(path, content)`
    (= `RepositoryArchive.isKeyMaterial`, which already includes `KeyMaterial`). Add a test that a
    `.cer`/`.pem` file counts.
  - `main/adapter/archive/v81/RepositoryMetadata.forNewFile(path)`: a synthesized
    `<Property name type="RepositoryFile" path contentType><Description/></Property>` with the
    content type from the T003 table. Any other extension gives `INVALID_INPUT` naming the supported
    extensions.
  - An exported file keeps its `.meta` metadata unchanged except `contentSize`/`contentMD5`, and an
    upload with the node's metadata (EXISTING) keeps that metadata the same way.
- [X] T013 (after T009) `main/application/DevelopmentGuard.java`: `forRepositoryWrite(policy)` returns the
  policy with `confirmation = SERVER`, like `forDeploymentRestore` (D-6). Tests in
  `test/application/DevelopmentGuardTest.java`.

**Checkpoint**: Foundation reviewed (Creator/Reviewer). `mvn -q verify` is green and
`PackageBoundaryTest` passes.

---

## Phase 3: User Story 2: Switch repository writes on for a development stage (Priority: P1)

**Goal**: the administrator controls repository writes per development node, and the switch is
visible in `--check-config` and `list_nodes`.

**Independent Test**:
- Load profiles with the switch in each placement and check the startup errors and warnings.
- Check the `--check-config` line and the `list_nodes` field.
- The refusal of an import on a read-only node is proven in T024.

- [X] T014 [US2] Write failing tests in `test/config/ConfigValidatorTest.java` (D-2):
  - an explicit `development.repository: WRITE` on a node that is not a development stage is a
    startup error;
  - `WRITE` on a `production: true` group, or on any of its nodes, is a startup error;
  - `defaults.development.repository: WRITE` with one development node and other nodes is valid,
    with the development node `WRITE` and the others read-only;
  - `WRITE` on a non-production group without a development-enabled node is a warning ("has no
    effect");
  - a node with `READ_ONLY` beats a group with `WRITE`;
  - messages use `{group}`/`{node}` terminology (`MessageTerminologyTest`).
- [X] T015 [US2] Implement `ConfigValidator.checkRepositorySwitch` on the raw
  `ProfileConfig.groups()` / `defaults()` with `Findings.termError`/`termWarning`, called from
  `validate` in `main/config/ConfigValidator.java`.
- [X] T016 [P] [US2] `main/config/ConfigSummary.java`: append `, repository: write` or
  `, repository: read-only` after `development: on (confirmation …)` on development nodes only. The
  literal `development: on (confirmation SERVER)` stays. Test in `test/config/ConfigSummaryTest.java`.
- [X] T017 [P] [US2] `main/domain/model/NodeSummary.java`: add `repositoryWrite: boolean`, filled in
  `EffectiveNodeConfig.summary()`. In `res/schemas/list_nodes.output.json` add the required
  `repositoryWrite` after `confirmationMode`. Update the field order in
  `test/mcp/tools/ListNodesToolTest.java`.

**Checkpoint**: US2 configuration complete. Enforcement follows in T024 and T040.

---

## Phase 4: User Story 1: Bring an edited repository file into the development stage (Priority: P1) 🎯 MVP

**Goal**: new and changed repository files reach a development node with `WRITE`. This works on
their own, with a diagram group, or with modules, always after a preview with a server-issued code,
with backup, byte-identical verification, rollback and write-back.

**Independent Test**: on `ImportHarness` with `FakeServer`, change one exported repository file of
the test owner, import it with `repositoryPaths`, and confirm all of the following:
- the node holds the new content as a new version;
- the workspace history has one `import` entry with `Server-State`;
- nothing else on the node changed.

### References in export and check

- [X] T018 [US1] Write a failing test in `test/adapter/archive/v81/WorkspaceWriterTest.java`, then
  in `main/adapter/archive/v81/WorkspaceWriter.java` replace the substring test with the T006/T008
  closure within the export's `Repository.zip` (D-3):
  - a schema included only by another schema is written (INUBIT's group export carries it in
    `Repository.zip`, P-3);
  - a URI string literal no longer forces a file;
  - two repository paths that differ only in letter case give `INVALID_INPUT` naming both (D-10).
- [X] T019 [P] [US1] Write a failing test in `test/application/ArtifactCheckServiceTest.java`, then
  make `main/application/ArtifactCheckService.java` scan repository files with the T006 scanner. A
  missing nested reference gives `REPOSITORY_REF_MISSING`; a nested reference into another owner's
  root gives `REPOSITORY_REF_UNVERIFIED` (FR-008).

### Change set

- [X] T020 [US1] Write failing tests in `test/application/RepositoryChangeSetTest.java` for
  `ChangeSetBuilder` (D-4).

  Which files are included, by scope:
  - **`repositoryPaths`**: every changed file at or below the paths.
  - **`diagramGroup`**: every changed file in the closure of every module the group's workspace
    workflows use. This includes a file only an **unchanged** module references, and a file reached
    only through another repository file.
  - **`modules`**: the closure of the listed modules.
  - Other changed repository files go to `notImported`.
  - A diagram group with only repository changes is a valid change set (FR-011a).

  Must be refused with `INVALID_INPUT`, without any CLI launch:
  - a deleted file in scope (a deletion out of scope goes to `notImported`);
  - a file below `repository/Root/<other owner>/`;
  - key material by `ReleaseArchivePort.keyMaterial` (a `.p12` and a `.cer`);
  - content containing `${secret:`;
  - a file over 10 MiB;
  - more than 50 MODIFIED files, or more than 200 NEW+MODIFIED files (hard cap, research D-4);
  - a path that `RepositoryPaths.passable` refuses;
  - `tag` with `repositoryPaths`.

  Must be refused with `PRECONDITION_FAILED` ("export the path first"): a repository file with a
  `Server-State` base whose `.meta` record is missing or unreadable. A file without any
  `Server-State` commit is NEW and must **not** be refused (research D-4, FR-017).
- [X] T021 [US1] Implement the classification in `main/application/ChangeSetBuilder.java`. It
  replaces the refusal at lines 134-139 and uses `RepositoryClosure`, the existing
  `LocalChange.serverState` base, `RepositoryPaths` and `ReleaseArchivePort.keyMaterial`.
  Carry-over from earlier reviews:
  - pass `changes.repository()` through every place that rebuilds a `ChangeSet` with the
    compatibility constructors (`ConflictDetector.java:303`, `ImportService.java:526/881/1353`,
    `ChangeSetBuilder.java:176`), so repository files are never dropped (T011 review);
  - `RepositoryPaths.full(owner, relative)` does not validate `owner`; validate it, or check
    `passable` on the result, before relying on it (T010 review);
  - the scanner decodes `%2F` inside a segment into a hidden `/`, which the closure cannot detect;
    refuse such references in `RepositoryReferences` (T006/T008 reviews).

### Node state, conflicts, switch and preview

- [X] T022 [US1] Write failing tests in `test/application/RepositoryStepTest.java`, then add the
  node-state part of `main/application/RepositoryStep.java` (D-5).
  - Exports: one `exportRepository` per **distinct parent folder** (assert the CLI launch count). If
    a folder exceeds the `RepositoryArchive` read limits ("64 MiB per entry, 128 MiB in total"), fall
    back to per-file exports. `NOT_FOUND` on a folder means every file below it is absent.
  - Classification, byte for byte:
    - NEW, absent on the node → `create`;
    - NEW, identical on the node → `identical`;
    - NEW, other content → `modify` + `existing`;
    - MODIFIED with node == base → `modify`;
    - MODIFIED with node != base → `CONFLICT` with `.reports/conflict-<auditId>.diff`.
  - `fingerprint` is the sorted `repository:<path>=sha256` entries.
  - Interim guard (T020+T021): `ImportService.locked` refuses every change set with repository
    files right after `builder.build`, with the test
    `ImportServiceRefusalTest.aReferencedRepositoryFileIsRefusedUntilRepositoryFilesCanBeSent`.
    When the preview path works (T022–T026), narrow the guard so that only the **execute** step
    refuses repository files. T031 removes it together with its test once repository files are
    actually sent. Never let workflows or modules be sent without their repository files.
  - `WorkspaceInspector.repositoryMetadata` (T021) returns the `.meta` metadata for the
    `RepositoryUpload` of MODIFIED files; a `.meta` record holding JSON `null` must count as
    unreadable, i.e. PRECONDITION_FAILED and not INTERNAL (T020+T021 review).
  - More than 50 files to send (`create` + `modify`; `identical` files do not count, FR-017) is
    `INVALID_INPUT` before anything is sent: 60 NEW files of which 15 are identical on the node pass.
  - A `create` file (absent on the node) with an unsupported extension is `INVALID_INPUT` before
    anything is sent, naming the supported extensions. A NEW file with an unsupported extension
    that the node already holds is `modify` + `existing` and is not refused.
  - An EXISTING file carries the node's metadata from the node-state export (content type,
    description) into its `RepositoryUpload` (research D-7).
- [X] T023 [US1] Include the repository fingerprint in `main/application/ConflictDetector.java`
  (the server fingerprint) and the repository contents in `ImportService.workspaceState`, so that a
  change on the node between preview and execute gives `CONFLICT` and a change in the workspace
  gives `CONFIRMATION_INVALID` (feature-004 contract, docs/tools.md; T023 review). Test in
  `test/application/RepositoryImportTest.java`.
- [X] T024 [US1] Switch enforcement (FR-004, D-5). Write failing tests in
  `test/application/RepositoryImportTest.java`:
  - On a `READ_ONLY` node, a change set with a MODIFIED repository file is refused with
    `REPOSITORY_WRITE_DENIED` **before any CLI launch**.
  - A NEW file that is `create`/`modify` after the node read is refused before anything is sent.
  - A diagram-group import with changed workflows and one referenced changed file is refused as a
    whole, naming the paths, and nothing goes to `notImported` instead.
  - An all-`identical` change set is not refused.
  - Every refusal is audited `REFUSED`, with the reason naming `development.repository`.

  Then implement the checks in `main/application/ImportService.java`, delegating to `RepositoryStep`.
- [X] T025 [US1] Server code always required (FR-012, D-6). Write a failing test: a node with
  `development.confirmation: CLIENT` and `WRITE` still gets a challenge for a change set with a
  repository file to send, and the import runs only with the code. A call without repository files
  keeps `CLIENT`. The second call on the CLIENT node is redeemed and bound: a changed workspace or
  node state gives `CONFLICT`. Then use `DevelopmentGuard.forRepositoryWrite` in
  `main/application/ImportService.java`. If the change set has repository files, the mode decision
  and the `redeem` move after the `RepositoryStep` node-state read, while the server-fingerprint and
  workspace-hash checks stay (research D-6). Add `repositoryPaths` to `inputFingerprint`. Repository
  files are left out of the artifact archive assembled for the preview, and the assembly is skipped
  for a repository-only change set (`ImportAssembler.guard`).
  Since T023 a repository-only change set (FR-011a) reaches the preview's artifact-archive assembly;
  add a test that it gets a preview, not INTERNAL (T023 review).

  Also assert the following:
  - On a CLIENT node, changed workflows plus repository files that are all `identical` keep
    `CLIENT`: no challenge, executed at once.
  - A presented code is **always** redeemed when the change set contains repository files, so a
    server-issued code is never silently ignored. If no repository file is left to send after the
    node read, the redeemed code still binds inputs and state, and the call proceeds without a
    repository import.
- [X] T026 [US1] Preview fields (FR-012, D-5).
  - Repository paths appear in `create`, `modify`, `existing` and `identical` as `/Root/<owner>/…`.
  - `repositoryReferences`: "up to 20 workspace artifacts outside the scope … with a truncation
    marker", with the full list in `.reports/repository-references-<auditId>.txt`.
  - `repositoryDiff`: `.reports/repository-<auditId>.diff`, one line diff per `modify` file between
    the node and the workspace.

  - Warnings in the preview and the result: each reference outside the owner's repository root
    that was not followed (`RepositoryClosure.outsideOwner`), and the note that references on the
    node which the workspace does not know cannot be listed (edge case "Shared use").

  Change `main/domain/model/ImportPreview.java` and `res/schemas/import_artifacts.output.json`
  (`additionalProperties: false`; the new fields are optional). Test in
  `test/application/RepositoryImportTest.java`, asserting both warnings.
- [ ] T027 [US1] Audit (FR-019, D-11).
  - One input `repository.<n>` = `<create|modify|existing|identical> /Root/<owner>/<path>
    sha256:<16 hex>` per file, each within `MAX_AUDITED_INPUT` = 500.
  - `scope` = `repository paths …`.
  - No content.

  Implement in `ImportService.Call.inputs`. The test reads the JSONL in
  `test/application/RepositoryImportTest.java`.
- [ ] T028 [US1] `main/mcp/tools/ImportArtifactsTool.java` and
  `res/schemas/import_artifacts.input.json`:
  - `repositoryPaths` (array, 1–50, unique, pattern
    `^[A-Za-z0-9_.][A-Za-z0-9_.\- ]{0,199}(/[A-Za-z0-9_.][A-Za-z0-9_.\- ]{0,199})*$`);
  - `oneOf` of `diagramGroup | modules | repositoryPaths`;
  - extend the `DESCRIPTION` with repository files, and update its quotation in `docs/tools.md` in
    the same task (`DevelopmentToolsReferenceTest`);
  - request plumbing in `main/application/ImportService.java`: `ImportRequest.repositoryPaths`,
    `validate` (today `group == !modules.isEmpty()` at line 1761 becomes "exactly one of three"),
    `scope(...)`, and `call.requested.put("scope", …)` (line 307) with `repository paths …`.
  - Test that a changed `repositoryPaths` between preview and confirmation gives
    `CONFIRMATION_INVALID` (`inputFingerprint` already includes them since T025; T025 review).

  Tests in `test/mcp/tools/ImportArtifactsToolTest.java` and `test/application/RepositoryImportTest.java`.

### Execute, verify, roll back, write back

- [ ] T029 [US1] Write failing tests in `test/application/RepositoryImportTest.java` (D-8):
  - the backup ZIPs contain the node's repository folder exports, and the IMPORT manifest has
    `repositoryPaths`;
  - `PENDING` is written before the first send;
  - `importRepository` runs **before** `importArchive`;
  - a repository-only call does not launch `importArchive`;
  - verification by re-export is byte-identical;
  - the reason is not checked on repository files (FR-015a);
  - the write-back rewrites only the **sent** files and their `.meta` from the re-export, not the
    other files of the exported folder;
  - there is exactly one `import <node>: … [auditId]` commit with `Server-State: <group>`;
  - a new file has the T003 content type on the node, and an `existing` file keeps the node's
    content type and description;
  - US1-AS4: a module-scope import of a changed module that references a changed stylesheet puts
    the stylesheet into the same preview and confirmation, and runs `importRepository` before
    `importArchive` in `MODULE` mode;
  - US1-AS5 / FR-011a: a diagram-group import with only a changed referenced schema and a `tag`
    imports just the schema (no `importArchive`), then runs `DiagramGroupTagger`, and the result
    has `tag.applied: true`.
- [ ] T030 [US1] `main/application/BackupStore.java`:
  - `ManifestJson` writes and reads `repositoryPaths` for `IMPORT` too;
  - `ImportService.updateManifest` uses `Manifest.with(…)` and keeps `kind`/`repositoryPaths`,
    which is a regression test for the field loss;
  - `intendedState` gets `repository:<path>` keys.

  Tests in `test/application/BackupStoreTest.java`.
- [ ] T031 [US1] Implement execute in `main/application/RepositoryStep.java` and
  `main/application/ImportService.java`:
  - backup with the repository exports;
  - `importRepository` with `repositoryArchiveFromFiles` (workspace content plus `.meta` metadata, or
    `RepositoryMetadata.forNewFile` for `create` files, or the node's metadata for `existing` files);
  - then `importArchive` if there are workflows or modules;
  - byte verification (`VERIFY_MISMATCH` with `.reports/verify-<auditId>.diff`);
  - write-back of the sent files only, and the commit;
  - repository files never enter the artifact archive, and a repository-only call skips assembly
    and `importArchive`;
  - the tag step also runs for a repository-only diagram-group import.
  - remove the interim execute guard and its test
    (`ImportServiceRefusalTest.aReferencedRepositoryFileIsRefusedUntilRepositoryFilesCanBeSent`);
    handle the `null` artifact archive of a repository-only change set (skip `importArchive`);
    re-assert in `RepositoryImportTest.aClientNodeGetsAServerCodeForARepositoryFileToSendAndRunsOnlyWithIt`
    that the import really runs with the code (`importRepository`, then `importArchive`) and nothing
    without it, and cover the execute side of `aRepositoryOnlyChangeSetGetsAPreview` (T025 notes).
  - assert that the result after an actual repository import carries the outside-owner warning and
    the shared-use note (T026 reviews).
- [ ] T032 [US1] Write failing tests in `test/application/RepositoryImportFailureTest.java`:
  - the repository import reports NOK;
  - a repository verify mismatch;
  - the artifact import fails after a successful repository import.

  In each case the modified repository files are re-imported from the backup **before** the artifact
  rollback and verified, created files are listed in `createdNotRemoved`, and `rollback` is
  `SUCCEEDED`. A failing repository rollback gives `rollback: FAILED` with the paths. The manifest
  gets `FAILED`.
- [ ] T033 [US1] Implement the repository rollback in `main/application/RepositoryStep.java`, called
  first from `ImportService.rollback`.
- [ ] T034 [US1] Tag information (D-12, from the P-3 result):
  - `WriteOutcome.tag.repositoryFiles` lists the imported paths in the closure of the group's
    modules, both those a module references directly and those only another repository file
    references (P-3: INUBIT's group tag covers both);
  - an indirectly included path gives no warning;
  - add `repositoryFiles` to `res/schemas/import_artifacts.output.json`.

  Test in `test/application/RepositoryImportTest.java`.
- [ ] T035 [US1] `test/application/RepositoryImportTimingTest.java`: 20 files in 3 folders with
  scripted StartCLI delays from P-2. Assert 3 exports for the preview, 1 repository import and
  3 exports for the verification, and print the times (SC-001).

**Checkpoint**: MVP: US2 + US1, reviewed, `mvn -q verify` green.

---

## Phase 5: User Story 3: Pull further repository files into the workspace (Priority: P2)

**Goal**: `export_artifacts` exports repository files and folders by their path, as the base for
editing and creating.

**Independent Test**: export a repository folder of the test owner that no module references.
Confirm that its files appear below `repository/Root/<owner>/…`, key material is not written, and
the export is one history entry with `Server-State`.

- [ ] T036 [US3] Write failing tests in
  `test/application/WorkspaceServiceRepositoryExportTest.java` (D-10):
  - recursive folder export;
  - if one of several paths is missing, the whole call is `NOT_FOUND` naming every missing path,
    nothing is written, and the workspace is unchanged;
  - an absolute, empty, root or `..` path is `INVALID_INPUT`;
  - "at most 500 files and 100 MiB": a larger scope is `INVALID_INPUT` before anything is written,
    naming the count and the size;
  - a case collision is `INVALID_INPUT`;
  - a keystore is withheld with a warning, and a `.cer` is written;
  - uncommitted edits are first committed as `local changes: <n> files`;
  - the commit `export <node>: repository <paths> (<n> files)` has `Server-State: <group>`;
  - workspace files outside the exported paths are untouched.
- [ ] T037 [US3] `ArchiveCodecPort.prepareRepository(group, owner, archives)` and
  `main/adapter/archive/v81/ArchiveCodec.java` with the `WorkspaceWriter` repository branch. The
  scope is "the exported paths and their `.meta` mirrors only". Test in
  `test/adapter/archive/v81/ArchiveCodecTest.java`.
- [ ] T038 [US3] `main/application/WorkspaceService.java`: the repository scope
  (`exportRepository("/Root/<owner>/<path>")` per path), the limits and the commit. `ExportRequest`
  requires "exactly one of diagramGroups, modules or repositoryPaths".
- [ ] T039 [US3] `main/mcp/tools/ExportArtifactsTool.java` and
  `res/schemas/export_artifacts.input.json`: `repositoryPaths` (array, 1–20, unique, the T028
  pattern) and the `DESCRIPTION` addition, with its quotation in `docs/tools.md` updated in the same
  task (`ArtifactToolsReferenceTest`). Test in `test/mcp/tools/ExportArtifactsToolTest.java`.

**Checkpoint**: US3 complete and independently testable.

---

## Phase 6: User Story 4: Undo a repository change (Priority: P2)

**Goal**: `restore_backup` of a development call restores the previous content of the repository
files that call modified.

**Independent Test**: import a changed repository file, restore its backup, and confirm that the
node's content equals the content before the import byte for byte.

- [ ] T040 [US4] Write failing tests in `test/application/RepositoryRestoreTest.java` (D-9):
  - (→ T041) two modified files get their previous content back; a backup of a repository-only call
    (scope `repository paths …`) is restorable; a created file stays and is listed in
    `createdNotRemoved`; a file changed again on the node gives `CONFLICT`, and nothing is sent;
  - (→ T042) a node switched to `READ_ONLY` since the import refuses the **whole** restore with
    `REPOSITORY_WRITE_DENIED`, naming the paths; the server code is required even with `CLIENT`;
    audit (FR-019): the refused, previewed and executed records carry one `repository.<n>` input per
    file (operation, path, hash) and no content;
  - (→ T043) the re-import is verified byte for byte, a failing restore rolls back and reports
    `rollback`, and the restore preview's `modify` lists the repository paths;
  - a `DEPLOYMENT` backup on a node that is not a development stage restores as in feature 005
    regardless of the switch (a new case in `test/application/RestoreDeploymentTest.java`).
- [ ] T041 [US4] Restore set, scope and conflict check in `main/application/ImportService.java`:
  - `restoreSet` takes the manifest's `repositoryPaths` that existed before the call, with their
    content from the backup's repository exports (`ReleaseArchivePort.repositoryExport` /
    `repositoryFiles`), also when the backup holds repository files only;
  - the scope is rebuilt from a `repository paths …` manifest scope;
  - `requireStateLeftBy` compares the `repository:<path>` fingerprints of `intendedState` with a
    per-folder export through `RepositoryStep`.
- [ ] T042 [US4] Switch, forced server code and audit for restores in
  `main/application/ImportService.java`: the `REPOSITORY_WRITE_DENIED` check (whole restore),
  `DevelopmentGuard.forRepositoryWrite`, and the `repository.<n>` audit inputs of T027 for the
  restore call.
- [ ] T043 [US4] Execute, verify and roll back restores through `main/application/RepositoryStep.java`
  (repository files first, then artifacts). `res/schemas/restore_backup.output.json` lets the
  `modify` list carry repository paths (a description change only).

**Checkpoint**: all user stories complete.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T044 [P] `docs/setup.md`:
  - "Development settings": the `development.repository` row (levels, default `READ_ONLY`, effective
    only on development stages, the inheritance rules, errors and the warning), a YAML example and
    the `--check-config` line with `repository: write`. The example node line (today
    `Node dev/node1: read-only, development: on (confirmation SERVER), e2e: …`) gains
    `, repository: read-only|write` before `, e2e:`, and the prose before it mentions the new part
    (T016 notes).
  - "Artifact workspace": the transitive repository closure of exports (a behaviour change) and the
    repository-path scope with its limits.
- [ ] T045 [P] `docs/tools.md`:
  - `list_nodes` field `repositoryWrite`;
  - `export_artifacts` `repositoryPaths` (input table, limits; the quoted description exists since T039);
  - `import_artifacts` (the quoted description exists since T028): the scopes table, refusals, the always-required server code,
    `repositoryReferences`, `repositoryDiff`, `tag.repositoryFiles`, execution order, rollback;
  - `restore_backup` with repository files;
  - the next step for `REPOSITORY_WRITE_DENIED` in the code table (the row exists since T005);
  - the `REPOSITORY_REF_MISSING` row (today "an `inubitrepository:` reference") also covers relative
    imports and includes in repository files (T019 notes);
  - the shared-use risk.

  `ArtifactToolsReferenceTest` and `DevelopmentToolsReferenceTest` must be green.
- [ ] T046 [P] `README.md`: "Safety model" (repository writes per development node, off by
  default, server code always) and the tools table wording for `import_artifacts`/`export_artifacts`.
- [ ] T047 [P] `docs/live-tests.md`: the repository section (prerequisites, the steps from
  quickstart.md "Live", personal test area only) and an opt-in repository case in
  `test/live/DevelopmentLiveTest.java`, which is skipped without the live configuration and never
  targets production.
- [ ] T048 [P] `CHANGELOG.md` `[Unreleased]`: feature 008, with the behaviour change of exports
  (transitive repository closure), the new key `development.repository`, the new scopes, the new code
  and the backup manifest fix. Under "Fixed": `check_artifacts` decodes percent-escaped repository
  paths before the existence check (no false `REPOSITORY_REF_MISSING` for e.g. `a%20b.xsd`), and its
  messages show the decoded path (T007 review r2).
- [ ] T049 Validate per quickstart.md:
  - `mvn -q clean verify`;
  - `mvn -q -Dtest=NoCustomerIdentifiersTest test` with the local lists (it must run, not skip);
  - with the person's approval, the live steps 1–5 on the personal test area;
  - record the outcome in `quickstart.md`.

---

## Dependencies & Execution Order

- Phase 1 (T001 → T002 → T003; T004 can start in parallel with T001) → Phase 2 → US2 (T014–T017) →
  US1 (T018–T035) → US3 (T036–T039) → US4 (T040–T043) → Polish (T044–T049).
- Within Phase 2:
  - T005, T009, T010, T011 and T012 [P]; T013 after T009;
  - T006 → T007 and T006 → T008;
  - T012 needs T003 (the content-type table).
- Within US1:
  - T018 ‖ T019 after T008;
  - T020 → T021 → T022 → T023 → T024 → T025 → T026 → T027 → T028;
  - T029 → T030 → T031 → T032 → T033 → T034 → T035.
- US3 depends only on Phase 2 and T018 (the case-collision check), not on the US1 import. It can run
  in parallel with US1 after T018.
- US4 depends on T030–T033 (manifest, repository rollback).

## Parallel Example

```text
Phase 2: T005 (ErrorCode) ‖ T009 (config core) ‖ T010 (ImportScope) ‖ T011 (ChangeSet) ‖ T012 (archives); then T013 (guard)
US2:     T016 (ConfigSummary) ‖ T017 (list_nodes) after T015
US1:     T018 (WorkspaceWriter closure) ‖ T019 (check nested refs)
After T018: US3 (T036–T039) ‖ US1 change set and preview (T020–T028); T028 and T039 both edit
            docs/tools.md and the request validation: run T039 after T028, or rebase it onto T028
Polish:  T044 ‖ T045 ‖ T046 ‖ T047 ‖ T048
```

## Implementation Strategy

- **MVP**: Setup, Foundational, US2 and US1 (T001–T035). A confirmed import of edited and new
  repository files into a development node with `WRITE`, on their own or with a diagram group or
  modules, with backup, verification and rollback. Refused on read-only nodes.
- **Increment 2**: US3 repository-path export (T036–T039), so unreferenced files can be edited.
- **Increment 3**: US4 restore (T040–T043).
- **Finish**: docs, live test, validation (T044–T049).
- **Creator/Reviewer per stage**:
  - Probes and Foundation (T001–T013)
  - US2 (T014–T017)
  - US1 preview (T018–T028)
  - US1 execute (T029–T035)
  - US3 (T036–T039)
  - US4 (T040–T043)
  - Polish (T044–T049)
