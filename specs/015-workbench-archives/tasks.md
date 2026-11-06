---

description: "Task list for Workbench-importable workflow archives (feature 015)"
---

# Tasks: Workbench-Importable Workflow Archives

**Input**: Design documents from `/specs/015-workbench-archives/`

**Prerequisites**:
- [plan.md](plan.md) and [spec.md](spec.md) (clarifications 2026-11-05, spec reviews r1–r3)
- [research.md](research.md) (R-1…R-17) and [data-model.md](data-model.md)
- [contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md) and
  [contracts/configuration-delta.md](contracts/configuration-delta.md)
- [quickstart.md](quickstart.md) (scenario numbers `Q<n>` below)
- the code of features 001–011

**Tests**: REQUIRED (Constitution III, TDD). Every task starts with the test(s) it names, observed
failing on an assertion before the production code exists, and ends with `mvn -q -o verify` green.
Each task contains its own tests; there are no separate "write failing tests" tasks.

**Organization**: setup and foundation (names, raw ZIP, references, audit shape), then the user
stories in priority order: US1 (archives of a diagram group, P1, MVP core), US2 (clear-text secrets
only on purpose, P1 — must ship together with US1), US3 (traceable and bounded, P2).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on unfinished tasks)
- **[Story]**: US1 archives of a diagram group, US2 clear-text secrets only on purpose, US3 traceable
  and bounded

## Path Conventions

| Short form | Expands to |
|---|---|
| `main/…` | `src/main/java/de/dadecker/inubit/mcp/…` |
| `test/…` | `src/test/java/de/dadecker/inubit/mcp/…` |
| `res/…` | `src/main/resources/…` |
| `fix/…` | `src/test/resources/fixtures/v8_1/artifacts/…` |

## Safety rules

- **While developing**: no access to INUBIT. All tests are offline: the recorded fixtures
  `fix/grp-a.zip` and `fix/grp-b.zip`, variants built from them in test code
  (`test/adapter/archive/v81/WorkflowArchiveFixtures.java`, research R-17), the scripted StartCLI
  launcher, JUnit temporary directories as target folders. No test writes outside its temporary
  directory.
- **Names**: only the fictitious names of the fixtures (`GRP-01`, `GRP-02`, `Workflow-0001`…,
  `Module-0001`…, owner `OWNERS`, user `jdoe`, hosts `*.example.test`). No customer name in any file,
  commit message or PR text; the identifier hooks must pass; never use `--no-verify`.
- **Secrets**: only the synthetic secret values of `fix/README.md`. No archive byte, secret value or
  file content in a log line, error, result or audit record (FR-012, FR-013, SC-004).
- **Configuration**: never change `~/.config/inubit-mcp/*` or the MCP registration.
- **Live**: the Workbench import (T026) only with the operator's explicit approval, on their machine.

---

## Phase 1: Setup

- [X] T001 [P] Annotation factory (research R-1): test first in `test/mcp/ToolHintsTest.java`
  (`localWriteIsNeitherReadOnlyNorDestructiveNorIdempotentButOpenWorld`: `readOnly()==false`,
  `destructive()==false`, `idempotent()==false`, `openWorld()==true`, title kept); then add
  `public static ToolHints localWrite(String title)` returning `new ToolHints(title, false, false,
  false, true)` with Javadoc ("writes local files only; read-only for INUBIT; clients ask before
  calling it") in `main/mcp/ToolHints.java`.

- [X] T002 [P] Audit outcome (research R-12): test first in `test/infra/AuditLogTest.java`
  (a record with outcome `PARTIALLY_COMPLETED` is written as `"outcome":"PARTIALLY_COMPLETED"` and
  read back by the existing line parser of the test); then add `PARTIALLY_COMPLETED` (after
  `FAILED`) to `main/domain/model/AuditOutcome.java` with Javadoc ("feature 015: writing failed:
  some or no archives were written, one failed, the rest not attempted"). Check every `switch` over `AuditOutcome` (`grep -rn "AuditOutcome" main`)
  still compiles and handles it.

---

## Phase 2: Foundational (blocking prerequisites)

- [X] T003 [P] File names (research R-8, FR-004, FR-009): test first
  `test/domain/model/ArchiveFileNamesTest.java` with the spec's cases — `Order: In/Out?.` →
  `Order_ In_Out_.diagram.zip`; `CON` → `_CON.diagram.zip`; `con.backup` → `_con.backup.diagram.zip`;
  `COM1`, `lpt9.x` prefixed, `COM10` not; a control character → `_`; trailing dots and spaces removed;
  `...` → `_`; `.hidden` → `_hidden`; each of `< > : " / \ | ? *` → `_`; a name whose file name
  (with `.diagram.zip`) is 256 UTF-8 bytes → `ToolErrorException` `INVALID_INPUT` naming the
  workflow, 255 bytes accepted (multi-byte characters counted in bytes); `key("Straße")` equals
  `key("STRASSE")`, `key` of NFD `é` equals NFC `é`. Then `main/domain/model/ArchiveFileNames.java`
  (JDK only): `fileName(String workflow)` in this order: "each of `<`, `>`, `:`, `"`, `/`, `\`, `|`,
  `?`, `*` and each control character becomes `_`; trailing dots and spaces are removed; an empty
  result becomes `_`; a leading `.` becomes `_`; a name whose part before the first dot is a Windows
  reserved name (`CON`, `PRN`, `AUX`, `NUL`, `COM1`–`COM9`, `LPT1`–`LPT9`, in any case) gets the
  prefix `_`"; "over 255 bytes in UTF-8 MUST be refused with `INVALID_INPUT` naming the workflow";
  `key(String)` = `Normalizer.normalize(s, NFC).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT)`.

- [X] T004 [P] Raw ZIP entries (research R-4): test first
  `test/adapter/archive/v81/RawZipTest.java`: reading `fix/grp-b.zip` gives the 23 entries in archive
  order with their bytes and times; reading its nested `Repository.zip` gives 6 entries and no
  archive comment (the comment "5.3" is read from `fix/grp-a.zip`'s nested `Repository.zip`, 0
  entries — corrected after T004's review); `write` of the read entries reproduces every entry's name, bytes, time and order
  and the comment (read back with `RawZip`); an entry name with `..`, an absolute name or a drive
  letter, a duplicate name, and inflated content over the bound (test constructor with a small
  bound) → `UNEXPECTED_RESPONSE`. Then package-private `main/adapter/archive/v81/RawZip.java`:
  `static RawZip read(byte[] zip)` (bounds as `ArchiveReader.MAX_BYTES`, name checks as
  `ArchiveReader.checkName`), `List<RawEntry> entries()`, `Optional<RawEntry> entry(String name)`,
  `Optional<String> comment()` (from the end-of-central-directory record), `static byte[]
  write(List<RawEntry> entries, Optional<String> comment)` (`new ZipEntry(header)`, compressed size
  `-1` for `DEFLATED`); record `RawEntry(ZipEntry header, byte[] bytes)` with defensive copies.

- [X] T005 [P] Shared reference collection (research R-6): test first
  `test/adapter/archive/v81/ArchiveReferencesTest.java`: for `fix/grp-b.zip` the references of
  `Workflow-0006` plus its modules include `/Root/OWNERS/xsd/msg.xsd`, the closure adds
  `/Root/OWNERS/xsd/core.xsd`; for `Workflow-0003` none of them; the references found inside a
  module file's embedded document are included; the existing `WorkspaceWriterTest`,
  `ArchiveCodecTest` and round-trip tests stay green unchanged. Then extract the reference scanning
  of `WorkspaceWriter.render` (`main/adapter/archive/v81/WorkspaceWriter.java:147-245`, the module
  part `:317-331`) into package-private `main/adapter/archive/v81/ArchiveReferences.java`
  (`workflow(WorkspacePath from, byte[] content)`, `indexEntry(WorkspacePath from, byte[] content)`,
  `moduleFile(WorkspacePath from, byte[] content)` returning `List<RepositoryReference>` — the bytes
  to scan plus the reference source, not the redacted model types, so `V81WorkflowArchives` can
  pass the unredacted XML — and `closure(owner, references, repository, group)` wrapping
  `RepositoryClosure.of`) and make `WorkspaceWriter` call it with its own serialized elements (no
  behaviour change).

- [X] T006 Audit records of the tool (research R-12): test first
  `test/application/ArchiveExportAuditTest.java` against an in-memory `AuditPort`: `refused(error)`
  appends one record with `capability` `export_archives`, `step` `EXECUTE`, outcome `REFUSED`,
  reason `<CODE>: <message>`; `pending()` then `outcome(EXECUTED|REFUSED|FAILED|PARTIALLY_COMPLETED,
  text)` append two records with the same `auditId`; inputs in this order: `owner`, `diagramGroup`,
  `targetFolder`, `workflow.<n>`, `file.<n>`, `acknowledgeClearTextSecrets`, `reason`, each value
  cut to 500 chars; `account` and `mcpClient` set; a throwing `AuditPort` makes `refused` return
  `INTERNAL` whose message contains the original code and message and "nothing was exported or
  written", and `pending` throw `INTERNAL` ("The audit record could not be written; nothing was
  exported", next step names `auditDirectory`); a failing `outcome` returns a warning text naming
  the `auditId` instead of throwing. Then `main/application/ArchiveExportAudit.java` (one instance
  per call; pattern of `ProcessControlService.Call.refuse`, `main/application/ProcessControlService.java:486-505`).

---

## Phase 3: User Story 1 — archives of a diagram group (Priority: P1) 🎯 MVP core

**Goal**: one Workbench-importable archive per technical workflow in a named folder, with a result
listing files, sizes, modules and repository files.

**Independent test**: Q1–Q8, Q19 (content), Q21, Q25, Q27 of [quickstart.md](quickstart.md):
three workflows of `fix/grp-b.zip` (`workflows` = `Workflow-0004`, `Workflow-0005`,
`Workflow-0006`) from the fake server into an empty temporary folder give three owner-only
archives whose content follows FR-005/FR-006.

- [X] T007 [US1] Slicing plans (research R-3, R-5, R-6): test first
  `test/adapter/archive/v81/V81WorkflowArchivesTest.java` (plan part) with a new test helper
  `test/adapter/archive/v81/WorkflowArchiveFixtures.java` that builds variants of `fix/grp-a.zip` /
  `fix/grp-b.zip` with `RawZip` (a module bound by `WorkflowName` that is no node; a nested
  `WorkflowModule` below a `PartnerManagement` node; a node module removed from `module/module.xml`
  and its file; an index entry of plug-in type `Workflow Connector`, a string not yet verified
  against a recorded `module/module.xml` (R-5, kept in one constant, confirmed in T026); a
  10-workflow group for the performance test of T010).
  Asserts: `workflows()` = the technical workflows of the diagram group in export order (`GRP-01` →
  `Workflow-0001`, `Workflow-0002`); `plan(w).modules()` = `{name, pluginType}` of every node module
  at any depth plus every index entry with `WorkflowName` = `w`, each once, export order; a module of
  two workflows is in both plans; a node module not in the export is in `missingModules` and not in
  `modules`; `workflowConnector` true only with a `Workflow Connector` node;
  `repositoryPaths` = the owner-root closure of R-6 (`Workflow-0006` → `msg.xsd`, `core.xsd`;
  the key-store location of `Module-0018` → `/Root/OWNERS/keys/fixture-client.p12`); an unreadable
  archive → `UNEXPECTED_RESPONSE`. Then `main/domain/port/WorkflowArchivePort.java` (+ nested
  `SlicedExport` with `workflows()`, `plan(String)`, `archive(String)`),
  `main/domain/model/WorkflowArchivePlan.java` (+ `ModuleEntry(name, pluginType)`; fields
  `workflow`, `modules`, `missingModules`, `repositoryPaths`, `workflowConnector`) and
  `main/adapter/archive/v81/V81WorkflowArchives.java` (`slice` parses once with `ArchiveReader`
  and `RawZip`, computes every plan eagerly; `archive` may throw `UnsupportedOperationException`
  until T008).

- [X] T008 [US1] Building one archive (research R-7, FR-004, FR-006): test first, extending
  `V81WorkflowArchivesTest` (archive part) and `WorkflowArchiveFixtures` (a variant of
  `fix/grp-b.zip` whose inner `Repository.zip` has two directory entries: `Root/OWNERS/xsd/`, an
  ancestor of the kept `msg.xsd`/`core.xsd` files of `Workflow-0006`, and one that is no ancestor of
  a kept file, e.g. `Root/OWNERS/unused/`): entries of `archive("Workflow-0006")` in the export's
  order, only `archive.properties`, `Repository.zip`, `workflow/workflow.xml`, `module/module.xml`
  and the selected `module/<name>.xml`; `archive.properties` and every module file `Arrays.equals`
  the export's bytes; `workflow/workflow.xml` holds one `WorkflowGroup` (original attributes and
  `WorkflowGroupName`) with only that `Workflow` element, `XmlEquality`-equal to the input element;
  `module/module.xml` holds only the selected entries, in original group and position order, without
  empty module groups; `Repository.zip` holds exactly `<path>.xml` and `<path>.dat` of each selected
  file with unchanged bytes, times and order and the original's archive comment (if any), and no
  other entry except
  ancestor directory entries the original has (a workflow without repository files → a
  `Repository.zip` without entries); on the directory-entry variant the ancestor directory entry is
  kept with its unchanged header, time and position among the kept entries and the other directory
  entry is dropped, and no directory entry is synthesized; entry times of the outer entries
  kept; the synthetic secret values of `fix/README.md` are present unchanged (no placeholder);
  `ArchiveReader.read` accepts every built archive. Then implement `archive(String)` in
  `main/adapter/archive/v81/V81WorkflowArchives.java` (document builders as
  `ArchiveAssembler.workflowDocument`/`indexDocument`, `XmlNormalizer.normalize`, `RawZip.write`).

- [X] T009 [US1] Placing archives (research R-11, FR-009, FR-010 success path): test first
  `test/application/ArchiveFolderWriterTest.java` (success part): a missing target folder and two
  missing parents are created right before the first archive, each `rwx------` on POSIX (assume
  `posix` view, else skip that assertion only); an existing folder keeps its permissions; each archive
  is `rw-------`, has the expected bytes, and no `.part` file remains; `write` of a name that exists
  (created between check and write by the test) leaves the existing file byte-for-byte unchanged and
  reports it (`FileAlreadyExistsException` path); no folder is created when there is nothing to
  write. Then `main/application/ArchiveFolderWriter.java` with the package-private `FileOps` seam
  (`createTemporary`, `writeAndForce`, `link`, `delete`, `createDirectory`), temporary name
  `.<file name>.<random>.part` created `CREATE_NEW`, `force(true)`, `Files.createLink(final,
  temporary)`, temporary name deleted; directories created level by level from the nearest existing
  ancestor; POSIX attributes only when `FileSystems.getDefault().supportedFileAttributeViews()`
  contains `posix`. The writer's result is defined here in full, as the nested record
  `ArchiveFolderWriter.Result(written, failed, notWritten, warnings)`: `written` the placed archives
  (file name, size), `failed` at most one entry (workflow, file name, code, message without
  content), `notWritten` the workflows after it, `warnings` the call-level warnings; the success
  test asserts the three empty parts, so T019 only adds behaviour, no field. Writes stop at the
  first failure (detailed failure handling in T019).

- [X] T010 [US1] Service happy path and export-dependent checks (research R-2, R-13 steps 1, 4–8,
  FR-002, FR-003, FR-005, FR-012, FR-017): test first
  `test/application/ArchiveExportServiceTest.java` with fakes for `ArtifactPort` (returns fixture
  bytes, counts calls), `AuditPort`, `VersionGate` (admits) and the real `V81WorkflowArchives`:
  Q1 three archives into an empty folder (source: `fix/grp-b.zip`, diagram group `GRP-02`, which
  has four workflows, with `workflows: ["Workflow-0004", "Workflow-0005", "Workflow-0006"]`),
  result lists `workflow`, `file`, absolute `path`, `size`,
  `moduleCount`, `modules`, `missingModuleCount`, `missingModules`, `repositoryFileCount`,
  `repositoryPaths`, per-archive warnings (missing modules: "imports only where these modules
  exist"; Workflow Connector: "the called workflow is not included"), `clearTextSecrets` text of
  contracts/mcp-tools-delta.md, `auditId`; Q5 `workflows: ["Workflow-0002"]` → one archive; group
  target uses its first node; owner defaults to `inventory.owner`, none → `NOT_CONFIGURED`; an
  unknown target → `TARGET_UNKNOWN`; each of these two refusals → exactly one `REFUSED` audit record
  (no `PENDING`), the fake `ArtifactPort` never called, no folder or file created (Q31, R-13 step 3);
  export-dependent refusals after the export and before any folder or file exists, first failure
  wins: unknown names (`INVALID_INPUT` naming them, case-sensitive), more than 50 technical workflows
  without `workflows` (`INVALID_INPUT` naming the count and pointing to `workflows`), a file name over
  255 bytes, two workflows with the same `ArchiveFileNames.key`, an existing file in the folder with
  the same key (message names the colliding names); empty diagram group → success with empty lists
  and no folder created; audit `PENDING` before the `ArtifactPort` call and `EXECUTED` after, export-dependent refusal →
  `REFUSED` with the same `auditId`; the workspace directory is untouched; the raw export never
  reaches `ArchiveCodecPort` (not wired). Also `test/application/ArchiveExportPerformanceTest.java`
  (SC-002): the 10-workflow fixture, `ArtifactPort` returning immediately, build and write ≤ 10 s.
  Then `main/application/ArchiveExportService.java` (+ `ArchiveExportRequest` record with fields of
  data-model.md) and `main/domain/model/ArchiveExportResult.java` (+ `WrittenArchive`,
  `FailedArchive`), version gate `gate.admit("export_archives", node, Capability.WORKSPACE_EXPORT,
  Capability.Kind.READ, Set.of(Channel.CLI))` after `PENDING`; admission warnings into the result.
  **Interim, until T017**: `targetFolder` is taken as a plain `Path.of(targetFolder)` that must be
  absolute (else `INVALID_INPUT`), without the folder rules of R-9 (`TargetFolderRules` comes in
  T013 and replaces this in T017); the writer of T009 gets this path and creates missing levels
  itself. Tests of this task use JUnit temporary directories only. Result lists are complete and `truncated` is
  always `false` until T011.

- [X] T011 [US1] Result bounding (research R-14, FR-012, Q25): test first, extending
  `test/application/ArchiveExportServiceTest.java` with `resultLimits.maxItems` set small (e.g. 3)
  and the three workflows of Q1 (`fix/grp-b.zip` with `workflows` = `Workflow-0004`,
  `Workflow-0005`, `Workflow-0006`): the module, missing-module and repository-path lists are
  filled archive by archive in output order (per archive `modules`, then `missingModules`, then
  `repositoryPaths`) until their sum reaches `maxItems`, later items are dropped and
  `truncated: true`; `moduleCount`, `missingModuleCount` and `repositoryFileCount` stay complete;
  `archives`, `failed`, `notWritten` and `warnings` are not cut; a sum exactly at `maxItems` gives
  `truncated: false`; a large `maxItems` gives the lists of T010 unchanged. Then the bounding step
  in `main/application/ArchiveExportService.java` (limit `ResultLimiter.maxItems()`,
  `application/ResultLimiter.java:64-66`, from `resultLimits.maxItems`) and the `truncated` flag of
  `main/domain/model/ArchiveExportResult.java`.

- [X] T012 [US1] Tool, schemas and wiring (research R-1, R-16, FR-001): test first
  `test/mcp/tools/ExportArchivesToolTest.java` through `McpTestClient`: `tools/list` shows
  `export_archives` with the description of contracts/mcp-tools-delta.md and the annotations
  `readOnlyHint:false`, `destructiveHint:false`, `idempotentHint:false`, `openWorldHint:true`; the
  input schema rejects (not audited, handler not called) an unknown property, `workflows` with 0 or 51
  items or duplicates, a workflow name of 201 chars or with a control character, a `reason` of 501
  chars or with a line break; a successful call returns structured content valid against the output
  schema; `test/ExportArchivesWiringTest.java`: offered exactly when a node has `cliHome` (as
  `export_artifacts`), also when the only group is production without opt-in. Then
  `main/mcp/tools/ExportArchivesTool.java` (`ToolHints.localWrite("Export Workbench archives of
  workflows")`, `handle(arguments, context)` passing `CallContext` for `mcpClient`),
  `res/schemas/export_archives.input.json` and `res/schemas/export_archives.output.json` exactly as
  contracts/mcp-tools-delta.md, `main/Wiring.java` (service with `config.workspace()`,
  `config.auditDirectory()`, `user.home`, environment, `new V81WorkflowArchives()`,
  `gateways::artifacts`, the shared `AuditLog`, accounts as `Wiring.java:302-303`; registered next to
  `export_artifacts` at `Wiring.java:441-443`; the `Wiring` constructor signatures stay unchanged in
  this task, the configuration location is added in T014). `test/TestWiring.java` needs no change
  here: its four `new Wiring(` calls (`TestWiring.java:32`, `:40`, `:48` in the three `of`
  factories, `:60` in `withParameterStoreSupplier`; the review counted five, the code has four) go
  through the existing constructors, which now build the service and register the tool, so
  `TestWiring.toolHandlers()` offers `export_archives` from every factory;
  `ExportArchivesWiringTest` uses `TestWiring.of(config, credentials, scrubber, exists, windows)` as
  `ExportArtifactsWiringTest.java:40` does. The other direct callers (`WiringE2eTest.java:124`,
  `WiringAuditTest.java:147`, `MessageTerminologyTest.java:125`) and `Launcher.java:277` are
  unchanged here as well.

**Checkpoint**: US1 works end to end with the fake server; do not release without US2.

---

## Phase 4: User Story 2 — clear-text secrets only on purpose (Priority: P1)

**Goal**: the acknowledgement, the folder rules, the production opt-in and the cloud-sync warning;
everything refused by the static input checks before StartCLI.

**Independent test**: Q9–Q15, Q23, Q24: each refusal happens before StartCLI (the fake
`ArtifactPort` is never called) and nothing is written.

- [X] T013 [P] [US2] Folder rules (research R-9, FR-008, FR-010): test first
  `test/application/TargetFolderRulesTest.java` with temporary directories, a fake
  `NetworkPathProbe` and a fake home/environment: refused (`INVALID_INPUT`, message names the rule
  and the path) — relative path; a `.` or `..` element; the folder or a descendant of
  `<home>/.inubit-mcp`, the configured `workspace`, the configured `auditDirectory`; the folder or
  a descendant of the default configuration directory in POSIX form (`new ConfigLoader(env, home,
  false).defaultDirectory()` = `<home>/.config/inubit-mcp`) and in APPDATA form (`new
  ConfigLoader(Map.of("APPDATA", <tmp>/AppData/Roaming), home, true).defaultDirectory()` =
  `<tmp>/AppData/Roaming/inubit-mcp`; without `APPDATA` `<home>/AppData/Roaming/inubit-mcp`); the
  folder or a descendant of the folder of a configuration file loaded from elsewhere
  (`configurationRoots` = `List.of(<default>, <tmp>/elsewhere)` refuses `<tmp>/elsewhere` and
  `<tmp>/elsewhere/sub`, accepts the sibling `<tmp>/elsewhere-2`); a folder that
  is or lies inside a directory with a `.git` entry (directory or file) in it or in an ancestor; the
  same reached through a symbolic link (link → inside workspace; link → inside a git tree; link →
  inside the default configuration directory; link → inside the configuration file's folder; a
  forbidden root that is itself a link, among them a configuration root); a missing folder whose nearest existing ancestor is inside
  a git tree; an existing file as target; a non-writable existing folder; a missing folder whose
  nearest existing ancestor is not a writable directory; with the Windows flag set (on the Ubuntu
  CI host, where these strings are relative paths) a UNC root (`\\server\share\x`,
  `\\?\UNC\server\share`) and a drive path `Z:\x` for which the fake probe, asked with `'Z'`,
  reports remote — each refused by
  the network-path rule (the test asserts that rule name in the message, not the "relative"
  rule).
  Accepted with a warning ("the archives (with clear-text secrets) will be synchronized to the
  cloud"): below `<home>/Library/CloudStorage`, `<home>/Library/Mobile Documents`, or the folders of
  the environment variables `OneDrive`, `OneDriveCommercial`, `OneDriveConsumer`. Accepted plain:
  a new folder below the temporary directory. Then
  `main/domain/port/NetworkPathProbe.java` (`boolean remoteDrive(char letter)`; the rules extract
  the letter from the raw string, since `Z:\x` has no root on the CI host) and
  `main/application/TargetFolderRules.java` (constructor with home, `workspace`,
  `auditDirectory`, `List<Path> configurationRoots`, environment map, `NetworkPathProbe`, Windows
  flag — no `config` type, `PackageBoundaryTest`; order of R-9: with the Windows flag UNC and
  drive roots recognised from the raw string before the `isAbsolute` check, every forbidden root
  including both configuration roots checked on the given and the resolved path; returns
  `CheckedFolder(path, exists, nearestExisting, warnings)`). Messages use the rule names of
  contracts/mcp-tools-delta.md "Folder rules".

- [X] T014 [US2] Configuration location plumbing (research R-9): test first
  `test/config/ConfigLocationTest.java` (`roots()` is the default directory and the source's
  absolute parent, one entry when they are equal), `test/MainTest.java` (a server started by
  `Launcher` with `--config <tmp>/elsewhere/profile.yaml` and a `toolHandlers` function,
  `Launcher.java:150`, that captures the `Wiring`: its `configLocation()` has `source`
  `<tmp>/elsewhere/profile.yaml` and `defaultDirectory` `loader.defaultDirectory()`, so
  `LoadedConfig.source()` reached `Wiring`) and `test/ExportArchivesWiringTest.java` (a `Wiring`
  from `TestWiring.withConfigLocation(…)` carries the given location; one from the existing
  factories carries `TEST_CONFIG_LOCATION`). Then:
  - `main/config/ConfigLocation.java`: record `(Path defaultDirectory, Path source)` with
    `roots()` (data-model.md);
  - `main/Launcher.java`: `serve(loaded.config(), new ConfigLocation(loader.defaultDirectory(),
    loaded.source()), report.warnings())` at `:214`; `serve` (`:258`) passes it to `new Wiring(…)`
    at `:277` as the new last argument;
  - `main/Wiring.java`: each constructor (`:180`, `:190`, `:201`) takes `ConfigLocation` as its
    last parameter (the two short ones pass it on); the full one keeps it in a field with a
    package-private accessor `configLocation()` (T017 hands `configLocation.roots()` to
    `TargetFolderRules`);
  - `test/TestWiring.java`: the four `new Wiring(` calls (`:32`, `:40`, `:48`, `:60`) pass a
    constant `TEST_CONFIG_LOCATION` = `new ConfigLocation(<tmpdir>/inubit-mcp-test-config,
    <tmpdir>/inubit-mcp-test-config/config.yaml)` (`<tmpdir>` = `java.io.tmpdir`; never created),
    so the factories' signatures and their 13 calling test classes stay unchanged; a new factory
    with a distinct name, `withConfigLocation(config, credentials, scrubber, exists, windows,
    launcher, environment, ConfigLocation)` (an overload of `of` would be ambiguous with
    `of(…, ParameterStore)`);
  - the other direct callers pass a location below their temporary directory:
    `test/WiringE2eTest.java:124`, `test/WiringAuditTest.java:147`,
    `test/MessageTerminologyTest.java:125` (`new ConfigLocation(<tmp>, loaded.source())`).
  No tool behaviour changes in this task.

- [X] T015 [US2] Remote drives on Windows (research R-10): test first
  `test/adapter/os/WindowsNetworkPathsTest.java` with a fake `DriveTypeApi`: drive type `4`
  (`DRIVE_REMOTE`) → remote, `3` (fixed), `2` (removable) → local, the API asked for `"<X>:\\"`
  (`remoteDrive('z')` asks for `Z:\`); a character that is not an ASCII letter → local without
  loading the native API; with
  the Windows flag `false` the native API is never loaded and the answer is `false`;
  `PackageBoundaryTest` stays green (`com.sun.jna` only in `adapter.os`). Then
  `main/adapter/os/DriveTypeApi.java`, `main/adapter/os/JnaDriveTypeApi.java` (`kernel32`
  `GetDriveTypeW`, loaded lazily as `JnaCredApi.java:65`) and
  `main/adapter/os/WindowsNetworkPaths.java` implementing `NetworkPathProbe`; wire it in
  `main/Wiring.java`: a `NetworkPathProbe` field built in the full constructor (`Wiring.java:201`)
  as `new WindowsNetworkPaths(windows, JnaDriveTypeApi::new)` from the constructor's existing
  `windows` flag, handed to `TargetFolderRules` in T017. `test/TestWiring.java` needs no change:
  with `windows` `false` the probe answers `false` without loading JNA, and with `true` on a
  non-Windows build machine the rules ask the probe only for a target string starting with a
  drive letter, for which alone the native API is loaded; service tests use a fake
  `NetworkPathProbe` directly. Add the manual Windows check (a mapped network drive is refused) to
  `docs/live-tests.md`.

- [X] T016 [P] [US2] Production opt-in setting (research R-15, FR-014): test first
  `test/config/ConfigLoaderTest.java` (`archives: {productionOptIn: true}` on a group is parsed;
  `archives` on a node or in `defaults`, an unknown key below `archives`, a non-boolean value →
  startup error naming the key), `test/config/ConfigValidatorTest.java` (warning "archives.productionOptIn
  has no effect on group <name> (not production)" on a non-production group),
  `test/config/EffectiveNodeConfigTest.java` (`archivesProductionOptIn()` = group value, default
  `false`), `test/config/ConfigSummaryTest.java` (`--check-config` group lines of
  contracts/configuration-delta.md: "archives: allowed", "archives: refused (no
  archives.productionOptIn)", "archives: allowed (archives.productionOptIn)"). Then
  `main/config/ArchivesConfig.java` (`Optional<Boolean> productionOptIn`, `EMPTY`), the new
  component `archives` of `main/config/GroupConfig.java` (default `ArchivesConfig.EMPTY`; update the
  five `new GroupConfig(` calls in `test/config/EffectiveNodeConfigTest.java`,
  `test/config/ConfigLoaderTest.java` and `test/live/LiveTarget.java`),
  `main/config/EffectiveNodeConfig.java`, `main/config/ConfigValidator.java`,
  `main/config/ConfigSummary.java`.

- [X] T017 [US2] Static input checks in the service (research R-13 steps 1–3, FR-007, FR-014,
  FR-017): test first, extending `test/application/ArchiveExportServiceTest.java`: without
  `acknowledgeClearTextSecrets` or with `false` → `INVALID_INPUT` whose message names the parameter
  and says the archives contain secrets in clear text (Q9); production group without
  `archives.productionOptIn` → `PRODUCTION_PROTECTED` naming `archives.productionOptIn`, also when
  `write.enabled` and `write.productionOptIn` are true; with the opt-in → allowed, also with
  `write.enabled: false`; non-production → no opt-in needed (Q23); every folder rule of T013 refused
  through the service (Q10, Q13); defence-in-depth re-checks of "1–50 unique workflow names, each
  1–200 characters without control characters", `diagramGroup` pattern and "`reason` (one line, at
  most 500 characters)" → `INVALID_INPUT`; first failure wins in the order production → acknowledgement
  → names/limits → folder; each refusal → exactly one `REFUSED` audit record, the fake
  `ArtifactPort` never called, no folder or file created; the cloud-sync warning appears in the
  result's `warnings` (Q15). Configuration location end to end (research R-9, plumbing of T014):
  extend `test/ExportArchivesWiringTest.java` (through `TestWiring.withConfigLocation(…)`: a call
  into the configuration file's folder and one into the default configuration directory →
  `INVALID_INPUT` naming the rule, before StartCLI) and `test/MainTest.java` (the server of T014
  started with `--config <tmp>/elsewhere/profile.yaml`: `export_archives` into
  `<tmp>/elsewhere/sub` → `INVALID_INPUT` naming the configuration file's folder). Then the static
  checks in `main/application/ArchiveExportService.java` using `TargetFolderRules` (replacing
  T010's interim `Path.of`) and `EffectiveNodeConfig.archivesProductionOptIn()` (passed in as a
  `Function<NodeId, Boolean>` / production flag lookup from `Wiring`); in `main/Wiring.java` the
  full constructor builds `TargetFolderRules(user.home, config.workspace(),
  config.auditDirectory(), configLocation.roots(), environment, probe of T015, windows)` and hands
  it and the opt-in lookup to the service.

- [X] T018 [US2] Tool-level guarantees (FR-001, FR-012, US2-6/7): test first, extending
  `test/mcp/tools/ExportArchivesToolTest.java`: a call without the acknowledgement returns a tool
  error (`isError`) with `INVALID_INPUT` and is audited (unlike a schema violation); every successful
  result carries `clearTextSecrets` with the fixed text; the description contains "IN CLEAR TEXT"
  and "acknowledgeClearTextSecrets"; `ToolHints` of the handler equal `ToolHints.localWrite(…)`. Then
  adjust `main/mcp/tools/ExportArchivesTool.java` (description constant, result mapping) as needed.

**Checkpoint**: US1 + US2 = the shippable MVP.

---

## Phase 5: User Story 3 — traceable and bounded (Priority: P2)

**Goal**: every failure point is audited and leaves no partial archive; results report what was and
was not written.

**Independent test**: Q16–Q20, Q22: a successful call, a StartCLI failure, a failure after the
export and a write failure are each audited, and the folder never holds a partial archive.

- [X] T019 [P] [US3] Write failures in the writer (research R-11, FR-009, FR-011): test first, extending
  `test/application/ArchiveFolderWriterTest.java` with the `FileOps` seam: a write that fails on the
  second of three archives (disk full) → first written, second in `failed` (`INTERNAL`, message
  without content), third in `notWritten`, no `.part` and no partial file left; a name that appears
  after the collision check → `failed` naming the file, the existing file unchanged, the rest
  `notWritten`; `link` throwing `UnsupportedOperationException` or a `FileSystemException` (not
  `FileAlreadyExistsException`) → fallback `CREATE_NEW` on the final name succeeds; fallback write
  failing → the file the call created is deleted, existing files untouched; deleting the `.part`
  file fails → archive counted as written plus a call-level warning (the writer's call-level
  warnings, mapped to the result's `warnings`, not to the archive's `warnings`) naming the leftover
  temporary file;
  creating the target folder fails → empty `archives`, the failure in `failed` (first workflow),
  the rest in `notWritten`; a folder created by the call stays (empty) when the first archive fails;
  a directory level that appears concurrently is accepted and its permissions are not changed. Then
  complete `main/application/ArchiveFolderWriter.java` (behaviour only: the result record of T009
  keeps its fields).

- [X] T020 [US3] Outcomes, partial results and audit precedence (research R-12, R-13, FR-011,
  FR-013): test first, extending `test/application/ArchiveExportServiceTest.java` and a new
  `test/application/ArchiveExportConcurrencyTest.java`: StartCLI failure or timeout from the fake
  `ArtifactPort` → the same `ToolError` code, message and next step as `export_artifacts`, nothing
  written, audit `PENDING` + `FAILED` (Q17); `UNSUPPORTED_VERSION` from the gate → `PENDING` +
  `REFUSED`; a write failure on the second of three → a result (not an error) with `archives[1]`,
  `failed[1]`, `notWritten[1]`, audit `PARTIALLY_COMPLETED` (Q18); failure on the first archive →
  result with empty `archives`, audit `PARTIALLY_COMPLETED`; creating the target folder fails →
  result with empty `archives`, audit `PARTIALLY_COMPLETED` (FR-013: every write-failure result is
  "partially completed", also with zero archives written); the writer's leftover-`.part` warning →
  in the result's call-level `warnings`, the archive in `archives`, audit `EXECUTED`; the fake
  `ArtifactPort` throws the `UNEXPECTED_RESPONSE` of the 128 MiB limit
  (`CliExportRunner.MAX_ARCHIVE_BYTES`, the error `export_artifacts` returns) → passed through
  unchanged (same code, message and next step), nothing sliced or written, audit `PENDING` +
  `FAILED`; audit not writable → static refusal becomes `INTERNAL` carrying
  the original code and message, a valid call is refused with `INTERNAL` before the `ArtifactPort`
  call (Q20); the outcome record failing → result returned with a warning naming the `auditId`; the
  audit inputs of a successful call hold `owner`, `diagramGroup`, `targetFolder`, every
  `workflow.<n>`, every `file.<n>`, `acknowledgeClearTextSecrets`, `reason` and never content (Q16);
  two concurrent calls into the same folder with overlapping workflows never replace a file, each
  archive name exists once with complete content, the loser reports `failed` naming the file
  (Q22, SC-003); to force the race, a test `FileOps` (the seam of T009) holds each call's first
  placement on a shared `CountDownLatch(2)` (count down, then await with a timeout), so both calls
  have passed the collision check against the still-empty folder before either places a file (a
  latch in the fake `ArtifactPort` would only align the exports, R-13 step 6). Then complete
  `main/application/ArchiveExportService.java` (outcome mapping, error wrapping, INFO log line
  with node, diagram group, counts and `auditId` only; ERROR line on audit failure without
  values).

- [X] T021 [US3] Secret-leak guarantee (SC-004, FR-001, FR-003, Constitution II): test first
  `test/security/ArchiveExportSecretLeakTest.java` modelled on `test/security/SecretLeakTest.java`:
  every fixture diagram group exported through `export_archives` with the production adapters
  (`TestWiring`, scripted StartCLI launcher returning the fixtures) into a temporary folder; no
  synthetic secret value of `fix/README.md` (also not base64 of the key-store files) occurs in tool
  results, tool errors (a refused and a failed call included), captured log output at DEBUG, or the
  audit files; the archives themselves do contain the values; the private export directory is gone;
  the workspace has no new file and no new history entry. Fix whatever the test finds in
  `main/application/ArchiveExportService.java` or `main/mcp/tools/ExportArchivesTool.java` (e.g. a
  log argument).

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T022 [P] Tool reference (FR-016): test first, own test class
  `test/mcp/tools/ArchiveToolReferenceTest.java` (as `ArtifactToolsReferenceTest`): `docs/tools.md`
  lists `| [\`export_archives\`](#export_archives) |` in the tool table and has a section
  `## \`export_archives\`` naming every parameter of the input schema, `clearTextSecrets`,
  `failed`, `notWritten`, `truncated`, the client confirmation (annotations), the five folder rules
  in the wording of contracts/mcp-tools-delta.md "Folder rules" (including "configuration
  directory" and "folder of the configuration file in use"), "inherit the target folder's ACLs"
  (Windows) and the audit outcomes with `PARTIALLY_COMPLETED` for every write failure; `README.md`
  lists the tool.
  Then write the section in `docs/tools.md` (description, parameters, decision flow with both check
  moments, refusal table of contracts/mcp-tools-delta.md, result example, audit, example prompt) and
  the tool line in `README.md`.

- [ ] T023 [P] Setup and security docs (FR-014, FR-016): test first, own test class
  `test/mcp/tools/ArchiveSetupSecurityDocsTest.java`: `docs/setup.md` documents `archives.productionOptIn` (level group,
  default `false`, independence from `write.*`, the no-effect warning, the `--check-config` line) and
  lists `export_archives` and `PARTIALLY_COMPLETED` in the audit-log section; `SECURITY.md` names
  `export_archives`, the clear-text archives, the acknowledgement, the client confirmation, the
  folder rules (wording of contracts/mcp-tools-delta.md "Folder rules", including the
  configuration directory and the folder of the configuration file in use), the cloud-sync risk with "only the listed sync roots are detected" (not iCloud
  "Desktop & Documents" or sync folders outside those roots) and the inherited Windows ACLs. Then
  edit `docs/setup.md` (new subsection "Workbench archives" after "Write settings", audit-log
  bullets, section 6 example output) and `SECURITY.md` (scope bullet).

- [ ] T024 [P] Changelog (FR-016): test first, own test class
  `test/mcp/tools/ArchiveChangelogTest.java`: `CHANGELOG.md` `[Unreleased]` (planned as 0.7.0) has
  an `### Added` entry for `export_archives` naming the clear-text secrets, the acknowledgement, the
  client confirmation (clients ask before calling it), the folder rules (including the
  configuration directory), the sentence that on Windows the archives inherit the target folder's
  ACLs, and `archives.productionOptIn`.
  Then add the entry (and keep the existing 0.7.0 text of feature 011).

- [ ] T025 Final validation: run `mvn -q -o clean verify` green; confirm in the Surefire report that
  `security/NoCustomerIdentifiersTest.repositoryContainsNoCustomerIdentifiers` **ran and passed**
  (not skipped — the local identifier lists must be present, `docs/release-checks.md`; if it skips,
  stop and ask the operator to provide the lists); `PackageBoundaryTest` green; walk the quickstart
  table Q1–Q29 and Q31 and tick each test; re-check the plan's Constitution Check; record the run in
  the validation record of `specs/015-workbench-archives/quickstart.md`.

- [ ] T026 Manual Workbench import (SC-001) — **only with the operator's explicit approval, on
  their machine**: ask the operator before any call to a real server. Following quickstart "Manual
  check SC-001": `export_archives` on the development stage for one workflow with modules and
  repository files into a new folder outside every git working tree; the operator imports the archive
  in the INUBIT 8.1 Workbench and compares workflow, modules and repository files; the archive is
  deleted afterwards. Record date, INUBIT version and result (no customer names) in
  `specs/015-workbench-archives/quickstart.md` and in the `[Unreleased]` text of `CHANGELOG.md`; a
  failed import is a release blocker and goes back to research R-5/R-7. Also confirm the plug-in
  type string `Workflow Connector` (research R-5, unverified): in a real diagram-group export with
  a Workflow Connector node, the `ModuleGroupName` of that module's entry in `module/module.xml`
  equals the constant of `V81WorkflowArchives` (record only the string, no names); a different
  string is fixed in the constant and its T007 test before the release.

---

## Dependencies & Execution Order

- **Phase 1** (T001, T002): no dependencies.
- **Phase 2**: T003, T004, T005 in parallel; T006 needs T002.
- **US1**: T007 needs T004, T005; T008 needs T007; T009 needs T003; T010 needs T003, T006, T008,
  T009; T011 needs T010 (same service and test class); T012 needs T001, T011.
- **US2**: T013 and T016 in parallel after Phase 2 (also alongside T007–T012); T014 needs T012
  (it edits `main/Wiring.java`, `test/TestWiring.java` and `test/ExportArchivesWiringTest.java`
  after T012) and T013 (it follows the folder rules whose roots it plumbs); T015 needs T013 (its
  port) and T014 (both edit `main/Wiring.java`); T017 needs T011, T012, T013, T014, T015, T016 (it
  edits the service and test class of T010/T011, `Wiring`, `ExportArchivesWiringTest` and
  `MainTest` after T014/T015); T018 needs T012, T017.
- **US3**: T019 needs T009 (may run alongside US2); T020 needs T017, T019; T021 needs T012, T020.
- **Polish**: T022, T023, T024 in parallel after T021 (each has its own test class and edits its
  own documents); T025 after all; T026 after T025, with approval.

```text
T002 → T006 ──────────────┐
T003 → T009 ──────────────┼→ T010 → T011 → T012 (+T001) ─┬→ T014 (+T013) → T015 ─┐
T004, T005 → T007 → T008 ─┘                              │                       ├→ T017 (+T016) → T018
                                                         └───────────────────────┘
T009 → T019 ─→ T020 (+T017) → T021 → T022 | T023 | T024 → T025 → T026
```

## Parallel Example

```text
Phase 2:  T003 ArchiveFileNames | T004 RawZip | T005 ArchiveReferences
US1/US2:  T013 TargetFolderRules | T016 ArchivesConfig | T019 ArchiveFolderWriter failures —
          alongside the T007 → T012 chain (then T014 ConfigLocation plumbing after T012 and T013,
          T015 after T014; T014 and T015 are not [P]: both edit `main/Wiring.java`)
Polish:   T022 tools.md + README (ArchiveToolReferenceTest) | T023 setup.md + SECURITY.md
          (ArchiveSetupSecurityDocsTest) | T024 CHANGELOG (ArchiveChangelogTest)
```

## Implementation Strategy

- **MVP**: Phases 1–4 (US1 + US2). US1 alone writes clear-text archives without the folder rules
  and the production opt-in, so it must not be released or used on a real server before US2 is
  done.
- Then US3 (failure paths, audit precedence, secret-leak test), then docs and validation.
- The Workbench import (T026) is the last gate before a release.
- Run each task through the implementer → reviewer loop; every commit stays green because each task
  carries its own tests.
