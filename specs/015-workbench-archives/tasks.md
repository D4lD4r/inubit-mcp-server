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
- **Live**: the Workbench import (T024) only with the operator's explicit approval, on their machine.

---

## Phase 1: Setup

- [ ] T001 [P] Annotation factory (research R-1): test first in `test/mcp/ToolHintsTest.java`
  (`localWriteIsNeitherReadOnlyNorDestructiveNorIdempotentButOpenWorld`: `readOnly()==false`,
  `destructive()==false`, `idempotent()==false`, `openWorld()==true`, title kept); then add
  `public static ToolHints localWrite(String title)` returning `new ToolHints(title, false, false,
  false, true)` with Javadoc ("writes local files only; read-only for INUBIT; clients ask before
  calling it") in `main/mcp/ToolHints.java`.

- [ ] T002 [P] Audit outcome (research R-12): test first in `test/infra/AuditLogTest.java`
  (a record with outcome `PARTIALLY_COMPLETED` is written as `"outcome":"PARTIALLY_COMPLETED"` and
  read back by the existing line parser of the test); then add `PARTIALLY_COMPLETED` (after
  `FAILED`) to `main/domain/model/AuditOutcome.java` with Javadoc ("feature 015: some archives were
  written, one failed"). Check every `switch` over `AuditOutcome` (`grep -rn "AuditOutcome" main`)
  still compiles and handles it.

---

## Phase 2: Foundational (blocking prerequisites)

- [ ] T003 [P] File names (research R-8, FR-004, FR-009): test first
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

- [ ] T004 [P] Raw ZIP entries (research R-4): test first
  `test/adapter/archive/v81/RawZipTest.java`: reading `fix/grp-b.zip` gives the 23 entries in archive
  order with their bytes and times; reading its nested `Repository.zip` gives 6 entries and the
  archive comment; `write` of the read entries reproduces every entry's name, bytes, time and order
  and the comment (read back with `RawZip`); an entry name with `..`, an absolute name or a drive
  letter, a duplicate name, and inflated content over the bound (test constructor with a small
  bound) → `UNEXPECTED_RESPONSE`. Then package-private `main/adapter/archive/v81/RawZip.java`:
  `static RawZip read(byte[] zip)` (bounds as `ArchiveReader.MAX_BYTES`, name checks as
  `ArchiveReader.checkName`), `List<RawEntry> entries()`, `Optional<RawEntry> entry(String name)`,
  `Optional<String> comment()` (from the end-of-central-directory record), `static byte[]
  write(List<RawEntry> entries, Optional<String> comment)` (`new ZipEntry(header)`, compressed size
  `-1` for `DEFLATED`); record `RawEntry(ZipEntry header, byte[] bytes)` with defensive copies.

- [ ] T005 [P] Shared reference collection (research R-6): test first
  `test/adapter/archive/v81/ArchiveReferencesTest.java`: for `fix/grp-b.zip` the references of
  `Workflow-0006` plus its modules include `/Root/OWNERS/xsd/msg.xsd`, the closure adds
  `/Root/OWNERS/xsd/core.xsd`; for `Workflow-0003` none of them; the references found inside a
  module file's embedded document are included; the existing `WorkspaceWriterTest`,
  `ArchiveCodecTest` and round-trip tests stay green unchanged. Then extract the reference scanning
  of `WorkspaceWriter.render` (`main/adapter/archive/v81/WorkspaceWriter.java:147-245`, the module
  part `:317-331`) into package-private `main/adapter/archive/v81/ArchiveReferences.java`
  (`workflow(…)`, `indexEntry(…)`, `moduleFile(…)` returning `List<RepositoryReference>`, and
  `closure(owner, references, repository, group)` wrapping `RepositoryClosure.of`) and make
  `WorkspaceWriter` call it (no behaviour change).

- [ ] T006 Audit records of the tool (research R-12): test first
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
a three-workflow group from the fake server into an empty temporary folder gives three owner-only
archives whose content follows FR-005/FR-006.

- [ ] T007 [US1] Slicing plans (research R-3, R-5, R-6): test first
  `test/adapter/archive/v81/V81WorkflowArchivesTest.java` (plan part) with a new test helper
  `test/adapter/archive/v81/WorkflowArchiveFixtures.java` that builds variants of `fix/grp-a.zip` /
  `fix/grp-b.zip` with `RawZip` (a module bound by `WorkflowName` that is no node; a nested
  `WorkflowModule` below a `PartnerManagement` node; a node module removed from `module/module.xml`
  and its file; an index entry of plug-in type `Workflow Connector`; a 10-workflow group for T011).
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

- [ ] T008 [US1] Building one archive (research R-7, FR-004, FR-006): test first, extending
  `V81WorkflowArchivesTest` (archive part): entries of `archive("Workflow-0006")` in the export's
  order, only `archive.properties`, `Repository.zip`, `workflow/workflow.xml`, `module/module.xml`
  and the selected `module/<name>.xml`; `archive.properties` and every module file `Arrays.equals`
  the export's bytes; `workflow/workflow.xml` holds one `WorkflowGroup` (original attributes and
  `WorkflowGroupName`) with only that `Workflow` element, `XmlEquality`-equal to the input element;
  `module/module.xml` holds only the selected entries, in original group and position order, without
  empty module groups; `Repository.zip` holds exactly `<path>.xml` and `<path>.dat` of each selected
  file with unchanged bytes, times and order and the archive comment, no other entry (a workflow
  without repository files → a `Repository.zip` without entries); entry times of the outer entries
  kept; the synthetic secret values of `fix/README.md` are present unchanged (no placeholder);
  `ArchiveReader.read` accepts every built archive. Then implement `archive(String)` in
  `main/adapter/archive/v81/V81WorkflowArchives.java` (document builders as
  `ArchiveAssembler.workflowDocument`/`indexDocument`, `XmlNormalizer.normalize`, `RawZip.write`).

- [ ] T009 [US1] Placing archives (research R-11, FR-009, FR-010 success path): test first
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
  contains `posix`. Writes stop at the first failure (detailed failure handling in T018).

- [ ] T010 [US1] Service happy path and export-dependent checks (research R-2, R-13 steps 1, 4–8,
  R-14, FR-002, FR-003, FR-005, FR-012, FR-017): test first
  `test/application/ArchiveExportServiceTest.java` with fakes for `ArtifactPort` (returns fixture
  bytes, counts calls), `AuditPort`, `VersionGate` (admits) and the real `V81WorkflowArchives`:
  Q1 three archives into an empty folder, result lists `workflow`, `file`, absolute `path`, `size`,
  `moduleCount`, `modules`, `missingModuleCount`, `missingModules`, `repositoryFileCount`,
  `repositoryPaths`, per-archive warnings (missing modules: "imports only where these modules
  exist"; Workflow Connector: "the called workflow is not included"), `clearTextSecrets` text of
  contracts/mcp-tools-delta.md, `auditId`; Q5 `workflows: ["Workflow-0002"]` → one archive; group
  target uses its first node; owner defaults to `inventory.owner`, none → `NOT_CONFIGURED`;
  export-dependent refusals after the export and before any folder or file exists, first failure
  wins: unknown names (`INVALID_INPUT` naming them, case-sensitive), more than 50 technical workflows
  without `workflows` (`INVALID_INPUT` naming the count and pointing to `workflows`), a file name over
  255 bytes, two workflows with the same `ArchiveFileNames.key`, an existing file in the folder with
  the same key (message names the colliding names); empty diagram group → success with empty lists
  and no folder created; result lists bounded by `resultLimits.maxItems` over modules, missing
  modules and repository paths of all archives in order, `truncated: true`, counts complete; audit
  `PENDING` before the `ArtifactPort` call and `EXECUTED` after, export-dependent refusal →
  `REFUSED` with the same `auditId`; the workspace directory is untouched; the raw export never
  reaches `ArchiveCodecPort` (not wired). Also `test/application/ArchiveExportPerformanceTest.java`
  (SC-002): the 10-workflow fixture, `ArtifactPort` returning immediately, build and write ≤ 10 s.
  Then `main/application/ArchiveExportService.java` (+ `ArchiveExportRequest` record with fields of
  data-model.md) and `main/domain/model/ArchiveExportResult.java` (+ `WrittenArchive`,
  `FailedArchive`), version gate `gate.admit("export_archives", node, Capability.WORKSPACE_EXPORT,
  Capability.Kind.READ, Set.of(Channel.CLI))` after `PENDING`; admission warnings into the result.

- [ ] T011 [US1] Tool, schemas and wiring (research R-1, R-16, FR-001): test first
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
  `export_artifacts` at `Wiring.java:441-443`) and `test/TestWiring.java` if it builds handlers.

**Checkpoint**: US1 works end to end with the fake server; do not release without US2.

---

## Phase 4: User Story 2 — clear-text secrets only on purpose (Priority: P1)

**Goal**: the acknowledgement, the folder rules, the production opt-in and the cloud-sync warning;
everything refused by the static input checks before StartCLI.

**Independent test**: Q9–Q15, Q23, Q24: each refusal happens before StartCLI (the fake
`ArtifactPort` is never called) and nothing is written.

- [ ] T012 [P] [US2] Folder rules (research R-9, FR-008, FR-010): test first
  `test/application/TargetFolderRulesTest.java` with temporary directories, a fake
  `NetworkPathProbe` and a fake home/environment: refused (`INVALID_INPUT`, message names the rule
  and the path) — relative path; a `.` or `..` element; the folder or a descendant of
  `<home>/.inubit-mcp`, the configured `workspace`, the configured `auditDirectory`; a folder that
  is or lies inside a directory with a `.git` entry (directory or file) in it or in an ancestor; the
  same reached through a symbolic link (link → inside workspace; link → inside a git tree; a
  forbidden root that is itself a link); a missing folder whose nearest existing ancestor is inside
  a git tree; an existing file as target; a non-writable existing folder; a missing folder whose
  nearest existing ancestor is not a writable directory; on a simulated Windows path syntax a UNC
  root (`\\server\share\x`, `\\?\UNC\server\share`) and a drive the probe reports remote.
  Accepted with a warning ("the archives (with clear-text secrets) will be synchronized to the
  cloud"): below `<home>/Library/CloudStorage`, `<home>/Library/Mobile Documents`, or the folders of
  the environment variables `OneDrive`, `OneDriveCommercial`, `OneDriveConsumer`. Accepted plain:
  a new folder below the temporary directory. Then `main/domain/port/NetworkPathProbe.java`
  (`boolean remote(Path absolute)`) and `main/application/TargetFolderRules.java` (order of R-9,
  returns `CheckedFolder(path, exists, nearestExisting, warnings)`).

- [ ] T013 [US2] Remote drives on Windows (research R-10): test first
  `test/adapter/os/WindowsNetworkPathsTest.java` with a fake `DriveTypeApi`: drive type `4`
  (`DRIVE_REMOTE`) → remote, `3` (fixed), `2` (removable) → local; a non-drive path → local; on a
  non-Windows `os.name` the native API is never loaded and the answer is `false`;
  `PackageBoundaryTest` stays green (`com.sun.jna` only in `adapter.os`). Then
  `main/adapter/os/DriveTypeApi.java`, `main/adapter/os/JnaDriveTypeApi.java` (`kernel32`
  `GetDriveTypeW`, loaded lazily as `JnaCredApi.java:65`) and
  `main/adapter/os/WindowsNetworkPaths.java` implementing `NetworkPathProbe`; wire it in
  `main/Wiring.java` (a seam in `TestWiring` uses a fake). Add the manual Windows check (a mapped
  network drive is refused) to `docs/live-tests.md`.

- [ ] T014 [P] [US2] Production opt-in setting (research R-15, FR-014): test first
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

- [ ] T015 [US2] Static input checks in the service (research R-13 steps 1–3, FR-007, FR-014,
  FR-017): test first, extending `test/application/ArchiveExportServiceTest.java`: without
  `acknowledgeClearTextSecrets` or with `false` → `INVALID_INPUT` whose message names the parameter
  and says the archives contain secrets in clear text (Q9); production group without
  `archives.productionOptIn` → `PRODUCTION_PROTECTED` naming `archives.productionOptIn`, also when
  `write.enabled` and `write.productionOptIn` are true; with the opt-in → allowed, also with
  `write.enabled: false`; non-production → no opt-in needed (Q23); every folder rule of T012 refused
  through the service (Q10, Q13); defence-in-depth re-checks of "1–50 unique workflow names, each
  1–200 characters without control characters", `diagramGroup` pattern and "`reason` (one line, at
  most 500 characters)" → `INVALID_INPUT`; first failure wins in the order production → acknowledgement
  → names/limits → folder; each refusal → exactly one `REFUSED` audit record, the fake
  `ArtifactPort` never called, no folder or file created; the cloud-sync warning appears in the
  result's `warnings` (Q15). Then the static checks in `main/application/ArchiveExportService.java`
  using `TargetFolderRules` and `EffectiveNodeConfig.archivesProductionOptIn()` (passed in as a
  `Function<NodeId, Boolean>` / production flag lookup from `Wiring`).

- [ ] T016 [US2] Tool-level guarantees (FR-001, FR-012, US2-6/7): test first, extending
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

- [ ] T017 [US3] Write failures in the writer (research R-11, FR-009, FR-011): test first, extending
  `test/application/ArchiveFolderWriterTest.java` with the `FileOps` seam: a write that fails on the
  second of three archives (disk full) → first written, second in `failed` (`INTERNAL`, message
  without content), third in `notWritten`, no `.part` and no partial file left; a name that appears
  after the collision check → `failed` naming the file, the existing file unchanged, the rest
  `notWritten`; `link` throwing `UnsupportedOperationException` or a `FileSystemException` (not
  `FileAlreadyExistsException`) → fallback `CREATE_NEW` on the final name succeeds; fallback write
  failing → the file the call created is deleted, existing files untouched; deleting the `.part`
  file fails → archive counted as written plus a warning naming the leftover temporary file;
  creating the target folder fails → empty `archives`, the failure in `failed` (first workflow),
  the rest in `notWritten`; a folder created by the call stays (empty) when the first archive fails;
  a directory level that appears concurrently is accepted and its permissions are not changed. Then
  complete `main/application/ArchiveFolderWriter.java`.

- [ ] T018 [US3] Outcomes, partial results and audit precedence (research R-12, R-13, FR-011,
  FR-013): test first, extending `test/application/ArchiveExportServiceTest.java` and a new
  `test/application/ArchiveExportConcurrencyTest.java`: StartCLI failure or timeout from the fake
  `ArtifactPort` → the same `ToolError` code, message and next step as `export_artifacts`, nothing
  written, audit `PENDING` + `FAILED` (Q17); `UNSUPPORTED_VERSION` from the gate → `PENDING` +
  `REFUSED`; a write failure on the second of three → a result (not an error) with `archives[1]`,
  `failed[1]`, `notWritten[1]`, audit `PARTIALLY_COMPLETED` (Q18); failure on the first → result with
  empty `archives`, audit `FAILED`; audit not writable → static refusal becomes `INTERNAL` carrying
  the original code and message, a valid call is refused with `INTERNAL` before the `ArtifactPort`
  call (Q20); the outcome record failing → result returned with a warning naming the `auditId`; the
  audit inputs of a successful call hold `owner`, `diagramGroup`, `targetFolder`, every
  `workflow.<n>`, every `file.<n>`, `acknowledgeClearTextSecrets`, `reason` and never content (Q16);
  two concurrent calls into the same folder with overlapping workflows never replace a file, each
  archive name exists once with complete content, the loser reports `failed` naming the file
  (Q22, SC-003). Then complete `main/application/ArchiveExportService.java` (outcome mapping,
  error wrapping, INFO log line with node, diagram group, counts and `auditId` only; ERROR line on
  audit failure without values).

- [ ] T019 [US3] Secret-leak guarantee (SC-004, FR-001, FR-003, Constitution II): test first
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

- [ ] T020 [P] Tool reference (FR-016): test first
  `test/mcp/tools/ArchiveToolReferenceTest.java` (as `ArtifactToolsReferenceTest`): `docs/tools.md`
  lists `| [\`export_archives\`](#export_archives) |` in the tool table and has a section
  `## \`export_archives\`` naming every parameter of the input schema, `clearTextSecrets`,
  `failed`, `notWritten`, `truncated`, the client confirmation (annotations), the folder rules,
  "inherit the target folder's ACLs" (Windows) and the audit outcomes; `README.md` lists the tool.
  Then write the section in `docs/tools.md` (description, parameters, decision flow with both check
  moments, refusal table of contracts/mcp-tools-delta.md, result example, audit, example prompt) and
  the tool line in `README.md`.

- [ ] T021 [P] Setup and security docs (FR-014, FR-016): test first, extending
  `ArchiveToolReferenceTest`: `docs/setup.md` documents `archives.productionOptIn` (level group,
  default `false`, independence from `write.*`, the no-effect warning, the `--check-config` line) and
  lists `export_archives` and `PARTIALLY_COMPLETED` in the audit-log section; `SECURITY.md` names
  `export_archives`, the clear-text archives, the acknowledgement, the client confirmation, the
  folder rules, the cloud-sync risk with "only the listed sync roots are detected" (not iCloud
  "Desktop & Documents" or sync folders outside those roots) and the inherited Windows ACLs. Then
  edit `docs/setup.md` (new subsection "Workbench archives" after "Write settings", audit-log
  bullets, section 6 example output) and `SECURITY.md` (scope bullet).

- [ ] T022 [P] Changelog (FR-016): test first, extending `ArchiveToolReferenceTest`:
  `CHANGELOG.md` `[Unreleased]` (planned as 0.7.0) has an `### Added` entry for `export_archives`
  naming the clear-text secrets, the acknowledgement, the folder rules and `archives.productionOptIn`.
  Then add the entry (and keep the existing 0.7.0 text of feature 011).

- [ ] T023 Final validation: run `mvn -q -o clean verify` green; confirm in the Surefire report that
  `security/NoCustomerIdentifiersTest.repositoryContainsNoCustomerIdentifiers` **ran and passed**
  (not skipped — the local identifier lists must be present, `docs/release-checks.md`; if it skips,
  stop and ask the operator to provide the lists); `PackageBoundaryTest` green; walk the quickstart
  table Q1–Q29 and tick each test; re-check the plan's Constitution Check; record the run in the
  validation record of `specs/015-workbench-archives/quickstart.md`.

- [ ] T024 Manual Workbench import (SC-001) — **only with the operator's explicit approval, on
  their machine**: ask the operator before any call to a real server. Following quickstart "Manual
  check SC-001": `export_archives` on the development stage for one workflow with modules and
  repository files into a new folder outside every git working tree; the operator imports the archive
  in the INUBIT 8.1 Workbench and compares workflow, modules and repository files; the archive is
  deleted afterwards. Record date, INUBIT version and result (no customer names) in
  `specs/015-workbench-archives/quickstart.md` and in the `[Unreleased]` text of `CHANGELOG.md`; a
  failed import is a release blocker and goes back to research R-5/R-7.

---

## Dependencies & Execution Order

- **Phase 1** (T001, T002): no dependencies.
- **Phase 2**: T003, T004, T005 in parallel; T006 needs T002.
- **US1**: T007 needs T004, T005; T008 needs T007; T009 needs T003; T010 needs T003, T006, T008,
  T009; T011 needs T001, T010.
- **US2**: T012, T013, T014 in parallel after Phase 2 (T013 needs T012's port); T015 needs T010,
  T012, T014; T016 needs T011, T015.
- **US3**: T017 needs T009; T018 needs T015, T017; T019 needs T011, T018.
- **Polish**: T020–T022 in parallel after T019; T023 after all; T024 after T023, with approval.

```text
T001 ─────────────────────────────────────────────┐
T002 → T006 ─────────────────┐                    │
T003 ───────────── T009 ─────┼→ T010 → T011 ──────┼→ T016
T004, T005 → T007 → T008 ────┘          │         │
T012 (→ T013), T014 ─────────────────── T015 ─────┘
T009 → T017 ──→ T018 → T019 → T020 | T021 | T022 → T023 → T024
```

## Parallel Example

```text
Phase 2:  T003 ArchiveFileNames | T004 RawZip | T005 ArchiveReferences
US2:      T012 TargetFolderRules | T014 ArchivesConfig   (T013 after T012)
Polish:   T020 tools.md | T021 setup.md + SECURITY.md | T022 CHANGELOG
```

## Implementation Strategy

- **MVP**: Phases 1–4 (US1 + US2). US1 alone writes clear-text archives without the folder rules
  and the production opt-in, so it must not be released or used on a real server before US2 is
  done.
- Then US3 (failure paths, audit precedence, secret-leak test), then docs and validation.
- The Workbench import (T024) is the last gate before a release.
- Run each task through the implementer → reviewer loop; every commit stays green because each task
  carries its own tests.
