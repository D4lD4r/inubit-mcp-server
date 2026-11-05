# Research: Workbench-Importable Workflow Archives (feature 015)

Decisions R-1 … R-17. Each: decision, rationale, alternatives considered. Code references are
`file:line` below `src/main/java/de/dadecker/inubit/mcp/` unless a path says otherwise. The spec
left two points to the plan (round-3 review findings 7 and 8): names (R-1, R-15) and the audit shape
with its error precedence (R-12, R-13).

## R-1 Tool name, parameters, annotations

- **Decision**: tool **`export_archives`**. Parameters:

  | Parameter | Schema | Notes |
  |---|---|---|
  | `target` | as `export_artifacts` (`^[a-z0-9][a-z0-9-]{0,31}(/[a-z0-9][a-z0-9-]{0,31})?$`), required | a group id uses its first node, as `export_artifacts` (`mcp/tools/ExportArtifactsTool.java:81`) |
  | `owner` | as `export_artifacts`, optional | default `inventory.owner` of the node (`ExportArtifactsTool.java:82-89`) |
  | `diagramGroup` | one string, pattern of the `diagramGroups` items of `export_artifacts`, required | singular: the spec allows exactly one diagram group (FR-002) |
  | `workflows` | array, `minItems 1`, `maxItems 50`, `uniqueItems`, items 1–200 chars without control characters (`^[^\u0000-\u001F\u007F]{1,200}$`) | optional; matched case-sensitively |
  | `targetFolder` | string, 1–4096 chars, required | absolute path, checked by the tool (R-9) |
  | `acknowledgeClearTextSecrets` | boolean, optional | must be `true`; checked by the tool, not by the schema (see rationale) |
  | `reason` | string, 1–500 chars, no line break (`^[^\r\n]{1,500}$`), optional | stored in the audit log |

  Annotations through a new factory `ToolHints.localWrite(title)` =
  `new ToolHints(title, false, false, false, true)` (the record already allows it,
  `mcp/ToolHints.java:17-22`; today only `readOnly(…)` and `destructive(…)` exist,
  `ToolHints.java:25-41`).
- **Rationale**: the name pairs with `export_artifacts` and says what is produced; `diagramGroup`
  (singular) follows `set_active`'s `diagramGroup`. The acknowledgement is not `required` /
  `const: true` in the schema: a schema violation is rejected by the SDK **without audit** and with
  a generic message, while US2-1 requires a refusal by the static input checks whose message names
  the acknowledgement and why — so the tool checks it. Limits that the schema can express
  (`maxItems`, lengths, patterns) are also in the schema, as for every other tool; the service
  re-checks them (defence in depth, audited `INVALID_INPUT`).
- **Alternatives**: `export_workbench_archives` (longer, no gain); a flag on `export_artifacts`
  (rejected by the spec's clarification "a separate read-only tool"); `const: true` in the schema
  (loses the audit and the explanatory message).

## R-2 Obtaining the export: reuse `ArtifactPort.exportWorkflowGroup`

- **Decision**: `ArchiveExportService` calls `ArtifactPort.exportWorkflowGroup(owner, diagramGroup)`
  (`domain/port/ArtifactPort.java:22`) of the resolved node, after the version gate
  `gate.admit("export_archives", node, Capability.WORKSPACE_EXPORT, Kind.READ, Set.of(Channel.CLI))`
  (the same capability as `export_artifacts`, `application/WorkspaceService.java:216-217`,
  `domain/model/Capability.java:15`). The returned `byte[]` goes straight to
  `WorkflowArchivePort.slice` (R-3); it never reaches `ArchiveCodec`.
- **Rationale**: the port already returns the raw archive in memory
  (`adapter/cli/v81/V81ArtifactAdapter.java:28-33` → `adapter/cli/CliExportRunner.java:164-172`);
  StartCLI writes it into a fresh owner-only temporary directory that is deleted on every exit path
  (`CliExportRunner.java:45-51`, `:394-439`) and bounded to 128 MiB
  (`CliExportRunner.java:76-78`) — exactly FR-003 and the "same limits" assumption. Errors, codes
  and messages are therefore the ones of `export_artifacts` (FR-011, FR-015). The bytes are "read,
  redacted and dropped" only inside `ArchiveCodec.prepare` (`adapter/archive/v81/ArchiveCodec.java:40-50`),
  which this tool does not call; `WorkspaceService.export` (`WorkspaceService.java:214-245`) is not
  used either, so the workspace, its lock and its history are untouched (FR-001).
- **Alternatives**: a new `ArtifactPort` method (nothing to add); routing through
  `WorkspaceService` (would take the workspace lock and touch its history); a new capability
  `ARCHIVE_EXPORT` (would change `list_nodes` and the matrix for no difference: same channel and
  versions, FR-015).

## R-3 Where the slicing lives: port `WorkflowArchivePort`, adapter `V81WorkflowArchives`

- **Decision**: new `domain/port/WorkflowArchivePort`:

  ```text
  SlicedExport slice(byte[] diagramGroupExport, GroupId group, String owner, String diagramGroup)
  SlicedExport:  List<String> workflows()                 // technical workflows, export order
                 WorkflowArchivePlan plan(String workflow) // names only, no content
                 byte[] archive(String workflow)          // the ZIP, built on demand
  ```

  The 8.1 implementation `adapter/archive/v81/V81WorkflowArchives` parses once (R-4) and computes
  every plan in `slice` (so every analysis failure happens before the first write, FR-017); the
  ZIP of one workflow is built only when the writer asks for it and dropped after it is written.
- **Rationale**: Constitution V keeps the 8.1 layout in `adapter/archive/v81`; the application layer
  may reach adapters only through ports (`architecture/PackageBoundaryTest`
  `theApplicationReachesAdaptersOnlyThroughThePorts`). Building on demand keeps memory at the raw
  export (≤ 128 MiB) plus one archive instead of up to 50.
- **Alternatives**: extend `ArchiveCodecPort` (its contract is "turn exports into workspace files",
  `domain/port/ArchiveCodecPort.java`); reuse `ImportArchivePort.assemble` as `set_active` does
  (`application/ImportService.java:672`) — it builds from the *rendered workspace files* plus
  re-inserted secrets, so module files are re-serialized, not byte-identical (FR-006).

## R-4 Reading the export twice: raw entries (`RawZip`) and the model (`ArchiveReader`)

- **Decision**: new package-private `RawZip` in `adapter/archive/v81` reads a ZIP into an ordered
  list of `RawEntry(ZipEntry header, byte[] bytes)` (name, time, extra, comment, method kept) plus
  the archive comment (read from the end-of-central-directory record, because `ZipInputStream`
  does not expose it). It applies the same bounds and name checks as `ArchiveReader`
  (`adapter/archive/v81/ArchiveReader.java:61` 128 MiB, `:193-211` no zip slip, duplicate entry →
  `UNEXPECTED_RESPONSE`). The model comes from `ArchiveReader.read` (`ArchiveReader.java:85`),
  unchanged. `RawZip.write(entries, comment)` writes copies of the headers
  (`new ZipEntry(header)`, compressed size reset to `-1` for deflated entries) with the original
  bytes in the original order.
- **Rationale**: `ExportArchive` keeps parsed XML only — "the raw ZIP is not kept"
  (`adapter/archive/v81/ExportArchive.java:13-15`) — so byte identity (FR-006) needs the raw
  entries. The existing writers use `new ZipEntry(name)` and lose entry times
  (`ArchiveAssembler.java:351-366`, `V81ReleaseArchives.java:350-361`), which FR-006 forbids for
  `Repository.zip`. The fixtures keep entry times and the nested archive comment
  (`src/test/resources/fixtures/v8_1/artifacts/README.md:53`).
- **Alternatives**: copying compressed bytes verbatim (needs a hand-written ZIP writer; byte
  identity is required for the *files*, not for their compressed form); `java.util.zip.ZipFile`
  (needs a file on disk, which FR-003 forbids outside the private temporary directory).

## R-5 Which modules an archive holds

- **Decision**: for workflow *W* the module names are
  1. `ModuleName` of **every** `WorkflowModule` element below *W*'s `Workflow` element, at any depth
     (as `WorkspaceInspector` walks nodes, `adapter/archive/v81/WorkspaceInspector.java:32-33`,
     `:73`), without a `moduleType` filter;
  2. plus every module index entry (`module/module.xml`) whose `WorkflowName` equals *W*
     (bound modules, e.g. connectors).

  A name of (1) without an index entry in the export is a **missing module** (`{name}`; FR-005);
  it is not an error. Every index entry of a selected name is kept (a module can have more than one
  entry; `WorkspaceWriter` groups them by name, `WorkspaceWriter.java:181-183`), and its module file
  `module/<lower-case name>.xml` if the export has one. Plug-in type = `ModuleGroupName` of the
  entry. A node whose module's plug-in type is `Workflow Connector` gives the per-archive warning
  of FR-012. **Unverified**: the type string `Workflow Connector` comes from a REST log fixture
  (`src/test/resources/fixtures/v8_1/rest/log_queueLog_error.json:10`), not from a recorded
  `module/module.xml` (no recorded export has a Workflow Connector); it is kept in one constant
  of `V81WorkflowArchives` and confirmed against a real export's `ModuleGroupName` in the manual
  check T026 (a different string there is fixed before the release).
- **Rationale**: FR-005 says "also nested nodes". The two existing helpers are narrower:
  `V81ReleaseArchives.modulesOf` reads only the root's direct children
  (`adapter/archive/v81/V81ReleaseArchives.java:153-162`) and `WorkspaceWriter.moduleNames` only
  direct `technical` children (`WorkspaceWriter.java:356-365`); a `PartnerManagement` node's
  children (fixture `Workflow-0005`) would be lost. The missing-module rule mirrors the workspace
  export's warning (`WorkspaceWriter.java:225-229`).
- **Alternatives**: reuse `modulesOf` (misses nested nodes); follow module-to-module references
  (out of scope per the Assumptions).

## R-6 Which repository files: the workspace closure on the unredacted XML

- **Decision**: extract the reference collection of `WorkspaceWriter.render`
  (`adapter/archive/v81/WorkspaceWriter.java:147-245`: workflow XML `:172`, index entries `:193`,
  module files and their embedded documents `:201`, `:317-331`) into a package-private
  `ArchiveReferences` used by both `WorkspaceWriter` and `V81WorkflowArchives`. For one workflow it
  scans the workflow element, the selected index entries and module files (each serialized with
  `XmlNormalizer.normalize(Element)`) with `RepositoryReferences.scan`
  (`adapter/archive/v81/RepositoryReferences.java:69`), then
  `RepositoryClosure.of(owner, references, lookup)` (`domain/model/RepositoryClosure.java:57`)
  with a lookup into the export's `Repository.zip` that scans each reached file. The archive holds
  the reached paths that exist in `Repository.zip` (inside the owner's root); references outside
  the owner are not followed (as today).
  The shared functions take the bytes to scan plus the reference source (`WorkspacePath from`, as
  `RepositoryReferences.scan(WorkspacePath, byte[])`), not the redacted model types: each caller
  serializes its own input (`WorkspaceWriter` its redacted elements, `V81WorkflowArchives` the
  unredacted elements of the export), so the same functions serve both.
- **Rationale**: FR-005 asks for "the closure the workspace export already computes"; sharing one
  extraction makes it equal by construction, and `WorkspaceWriter`'s existing tests guard the
  extraction. Scanning the unredacted elements finds every reference the redacted ones show and
  also those inside values the workspace replaces by placeholders (e.g. a key-store location),
  which the Workbench import needs (edge case "key material … included unchanged").
- **Alternatives**: render each workflow into workspace files and read the paths back (needs a
  redaction pass and drops withheld key material); a new reference scanner (two rules to keep in
  step).

## R-7 Building one archive (FR-004, FR-006)

- **Decision**: entries in the original entry order, restricted to:
  - `archive.properties` — raw bytes copied;
  - `workflow/workflow.xml` — rebuilt as `ArchiveAssembler.workflowDocument` does
    (`adapter/archive/v81/ArchiveAssembler.java:195-209`) with *W*'s `WorkflowGroup` (its
    attributes and `WorkflowGroupName`) and only *W*'s unchanged `Workflow` element, serialized with
    `XmlNormalizer.normalize` (`XmlNormalizer.java:68`);
  - `module/module.xml` — rebuilt as `ArchiveAssembler.indexDocument` (`:211-229`) with only the
    selected entries in their original group and position order; module groups without a selected
    entry are dropped;
  - `module/<name>.xml` of each selected module — raw bytes copied;
  - `Repository.zip` — rebuilt by `RawZip` from the raw inner entries: for each selected file its
    `<path>.xml` metadata and `<path>.dat` content entry (the layout of
    `adapter/archive/v81/RepositoryArchive.java:7-12`), plus any directory entry of the original
    that is an ancestor of a kept file; each kept entry's header and bytes unchanged, original
    order, archive comment kept; no other entry. A workflow without repository files gets a
    `Repository.zip` without entries (as `grp-a.zip` has one).

  Tests compare: copied entries with `Arrays.equals`; the two XML entries with `XmlEquality`
  (`adapter/archive/v81/XmlEquality.java:4-22`) against the corresponding elements of the input;
  `Repository.zip` entry by entry (name, bytes, time, order).
- **Rationale**: FR-006 requires "unchanged as XML (equal after parsing)" for the two index
  documents — exactly what `XmlNormalizer` preserves; `set_active` already ships single-workflow
  archives in this shape (Assumptions). The recorded fixtures have no directory entries in
  `Repository.zip` (`fixtures/v8_1/artifacts/README.md:53`), so "plus the directory entries of
  their folders" (FR-006) is read as: a directory entry is kept only if the original
  `Repository.zip` has it and it is an ancestor of a kept file; no directory entry is synthesized,
  and a directory entry that is no ancestor of a kept file is dropped (spec Clarifications,
  "Directory entries of `Repository.zip`"). A test-built fixture variant with directory entries
  (one ancestor of a kept file, one not) covers both cases (T008).
- **Alternatives**: textual cutting of `workflow.xml` (fragile with namespaces and escaping);
  synthesizing directory entries (would add entries INUBIT's export does not have).

## R-8 File names (FR-004) and comparison key (FR-009)

- **Decision**: `domain/model/ArchiveFileNames` (JDK only):
  `fileName(workflow)` applies, in order: each of `< > : " / \ | ? *` and each control character
  (`U+0000–U+001F`, `U+007F`) → `_`; strip trailing `.` and space; empty → `_`; leading `.` → `_`;
  base (part before the first `.`) equal, ignoring case, to `CON PRN AUX NUL COM1–COM9 LPT1–LPT9`
  → prefix `_`; append `.diagram.zip`; more than 255 UTF-8 bytes → `INVALID_INPUT` naming the
  workflow. `key(name)` = `Normalizer.normalize(name, NFC)` then
  `toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT)` (full case folding approximation that maps
  `ß`/`SS`, `ﬁ`/`FI` and Greek final sigma together; Java has no `String.caseFold`).
- **Rationale**: a pure function in the domain is testable with the spec's examples
  (`Order: In/Out?.` → `Order_ In_Out_.diagram.zip`, `CON` → `_CON.diagram.zip`,
  `con.backup` → `_con.backup.diagram.zip`). The key is used for the collision checks against the
  folder listing and between selected workflows.
- **Alternatives**: `toLowerCase` only (misses `ß` vs `SS` on case-insensitive volumes); ICU4J
  case folding (new dependency).

## R-9 Target folder rules (FR-008, FR-010)

- **Decision**: `application/TargetFolderRules.check(String targetFolder)` → `CheckedFolder(path,
  exists, nearestExisting, warnings)` or `INVALID_INPUT`, in this order:
  1. Windows flag set: UNC and drive roots are recognised from the raw string, before the
     `isAbsolute` check (so the rule is the same on a non-Windows host, where `\\server\share\x`
     and `Z:\x` are relative paths): a string starting with `\\` (UNC, also `\\?\UNC\`) → refused
     (network-path rule, contracts/mcp-tools-delta.md "Folder rules" 2); a drive root `<X>:\`
     whose letter `X`, taken from the raw string, the probe reports remote
     (`remoteDrive('X')`, R-10) → refused (same rule);
  2. `Path.of` succeeds, `isAbsolute()`, no `.`/`..` name element (checked on the string's
     elements before any normalization);
  3. nearest existing ancestor *A* (the folder itself if it exists); *A* must be a directory
     (`Files.isDirectory`, following links) and writable (`Files.isWritable`); an existing target
     that is not a directory → refused;
  4. forbidden roots — `<user.home>/.inubit-mcp`, the configured `workspace` and
     `auditDirectory`, and the **two configuration roots**: the default configuration directory as
     `ConfigLoader.defaultDirectory()` computes it (`config/ConfigLoader.java:185-194`:
     `~/.config/inubit-mcp`, on Windows `%APPDATA%\inubit-mcp`, without `APPDATA`
     `<home>\AppData\Roaming\inubit-mcp`) and the parent folder of the configuration file in use,
     `LoadedConfig.source().toAbsolutePath().getParent()` (`config/LoadedConfig.java:13`; it
     differs from the default directory when `--config` names a file elsewhere) — compared with
     `startsWith` on (a) the given path normalized and (b) `A.toRealPath()` joined with the
     not-yet-existing rest, against each root as given and as `toRealPath()` when it exists (so a
     configuration root that is itself a symbolic link, and a target reached through one, are both
     caught);
  5. `.git`: any directory from (a) and from (b) up to the file-system root that contains an entry
     named `.git` (`Files.exists(dir.resolve(".git"), NOFOLLOW_LINKS)`, file or directory) →
     refused;
  6. cloud-sync roots — macOS `<home>/Library/CloudStorage`, `<home>/Library/Mobile Documents`;
     Windows the values of `OneDrive`, `OneDriveCommercial`, `OneDriveConsumer` (environment map
     passed in by `Wiring`) — on (a) or (b): call-level warning, no refusal.

  The workspace and audit roots come from the `ProfileConfig` (`config/ConfigLoader.java:372`,
  `:385` defaults; `infra/AuditLog.java:108`). The configuration roots are not part of
  `ProfileConfig` (`Launcher.serve`, `Launcher.java:258`, receives only `loaded.config()`), so
  they are plumbed as a new record `config/ConfigLocation(Path defaultDirectory, Path source)`
  (`roots()` = the default directory and the source's parent folder, each once):
  `Launcher.java:214` passes `new ConfigLocation(loader.defaultDirectory(), loaded.source())` to
  `serve`, `serve` passes it to `new Wiring(…)` (`Launcher.java:277`), every `Wiring`
  constructor (`Wiring.java:180`, `:190`, `:201`) takes it as its last parameter, and `Wiring`
  hands `configLocation.roots()` to `TargetFolderRules` as `List<Path> configurationRoots`
  (`application` may not depend on `config`: `PackageBoundaryTest` allows it only `java/`,
  `domain/`, `application/`, `org/slf4j/`). Test callers: the four factories of `TestWiring`
  (`TestWiring.java:32`, `:40`, `:48`, `:60`) keep their signatures and pass a fixed
  `TEST_CONFIG_LOCATION` with `defaultDirectory` = `<tmpdir>/inubit-mcp-test-config` and
  `source` = `<tmpdir>/inubit-mcp-test-config/config.yaml` (`<tmpdir>` = `java.io.tmpdir`), plus
  one new factory with a distinct name, `TestWiring.withConfigLocation(…)` (an overload of `of`
  would be ambiguous with `of(…, ParameterStore)`); `WiringE2eTest.java:124`, `WiringAuditTest.java:147` and
  `MessageTerminologyTest.java:125` pass a location below their temporary directory
  (`MessageTerminologyTest` its `loaded.source()`). Messages name the rule and the offending
  path element, never file contents.
- **Rationale**: the order puts cheap syntax first and gives one deterministic message. Checking
  both the given and the resolved form covers symbolic links in either direction (FR-008).
  "`~/.inubit-mcp` of any profile" is the directory itself, which contains every profile's
  directories.
- **Alternatives**: `toRealPath` only (misses a forbidden path reached through a link whose
  resolved target is elsewhere — the given form must be checked too); `Files.isSameFile` per
  ancestor (slower, same result).

## R-10 Network paths on Windows: port `NetworkPathProbe`

- **Decision**: `domain/port/NetworkPathProbe { boolean remoteDrive(char letter); }`. The port
  takes the drive letter, not a `Path`: on the Ubuntu CI host `Z:\x` is a relative path without
  a root, so `TargetFolderRules` (with the Windows flag) extracts the letter from the raw string
  (`<X>:\` or `<X>:/` at its start) before the `isAbsolute` check and asks the probe with it.
  Production: `adapter/os/WindowsNetworkPaths` calls `GetDriveTypeW("<X>:\\")` through a JNA
  mapping of `kernel32` (`DriveTypeApi` / `JnaDriveTypeApi`, the pattern of
  `adapter/os/JnaCredApi.java:27`, `:65`) and answers `DRIVE_REMOTE (4)`; on other systems
  `remoteDrive` is always `false` (no native load). UNC paths are refused by `TargetFolderRules`
  from the raw string alone. Tests use a fake probe; the JNA call itself is covered only by the
  manual Windows check in `docs/live-tests.md` (as feature 011 did for `CredReadW`).
- **Rationale**: Java has no public "remote volume" API (`FileStore.type()` reports the server's
  file system, e.g. `NTFS`); JNA core is already a dependency limited to `adapter/os`
  (`PackageBoundaryTest.onlyTheOsAdapterUsesJna`); "as far as detectable" (FR-008) covers drives
  mapped by other means (e.g. `subst` of a UNC path reports `DRIVE_REMOTE` too, a `subst` of a
  local folder does not and is local).
- **Alternatives**: `net use` subprocess (process start outside `adapter/cli`, slow); ignoring
  mapped drives (FR-008 asks to refuse them).

## R-11 Placing a file without ever replacing one (FR-009, FR-010, FR-011)

- **Decision**: `application/ArchiveFolderWriter`, per archive:
  1. temporary file `.<file name>.<random>.part` in the target folder, created with `CREATE_NEW`
     (and on POSIX `rw-------` as a creation attribute, then `setPosixFilePermissions` against the
     umask); bytes written, `FileChannel.force(true)`, closed;
  2. `Files.createLink(final, temporary)` — `link(2)` / `CreateHardLinkW` fail atomically with
     `FileAlreadyExistsException` when the name exists, also if another call created it after the
     collision check → `failed[]` entry naming the file;
  3. the temporary name is deleted; if that fails, the archive still counts as written and a
     **call-level** warning (the result's `warnings`, not the archive's) names the leftover
     temporary file (it holds the same secrets);
  4. if hard links are unsupported (`UnsupportedOperationException`, or `FileSystemException` other
     than `FileAlreadyExistsException` on `createLink`, e.g. FAT/exFAT): the temporary file is
     deleted and the archive is written with `CREATE_NEW` on the final name directly; on an
     `IOException` the file this call created is deleted (it never existed before).

  Any other failure deletes the temporary file and reports the archive in `failed[]` with
  `INTERNAL`; the remaining workflows go to `notWritten[]`. The target folder and missing parents
  are created right before the first archive, one level at a time from the nearest existing
  ancestor with `createDirectory` (`rwx------`, then `setPosixFilePermissions`); a level that
  appears concurrently (`FileAlreadyExistsException` and a directory) is accepted and left as it
  is. Permissions of existing folders are never changed. POSIX attributes only when the default
  file system supports `posix` (as `infra/AuditLog.java:84-85`; unlike
  `application/PackageWriter.java:321-326`, `:340-344`, which assume POSIX). A `FileOps` seam
  (create, write+force, link, delete, createDirectory) lets tests inject failures, as
  `AuditLog.ChannelOpener` does.
- **Rationale**: the final name appears only complete (no partial archive, also not after a crash
  in step 1, which leaves only a hidden `.part` file); the never-replace guarantee comes from the
  kernel, not from a check-then-act race. `Files.move` without `REPLACE_EXISTING` checks and then
  renames (not atomic), and `ATOMIC_MOVE` may replace an existing target ("implementation
  specific"; `rename(2)` replaces).
- **Alternatives**: `CREATE_NEW` on the final name only (a partial file is visible while writing
  and stays after a crash); a file lock on the folder (not honoured by other programs).

## R-12 Audit shape (FR-013; review round 3, finding 7)

- **Decision**: the write-tool pattern (`docs/tools.md:1006-1012`,
  `domain/model/AuditOutcome.java`): `capability` `export_archives`, `step` `EXECUTE`, one
  `auditId` per call.
  - static refusal → one record `REFUSED`;
  - otherwise a `PENDING` record, forced to disk (`AuditPort.append`, `domain/port/AuditPort.java`)
    **before** the version gate (whose StartCLI client probe may run StartCLI) and the export;
    then the outcome record with the same `auditId`:
    `EXECUTED` (every selected archive written, also zero workflows), `REFUSED` (version gate,
    export-dependent check), `FAILED` (StartCLI, version-gate probe or slicing error: the call
    ends with a tool error before any write), new **`PARTIALLY_COMPLETED`** for **every**
    write-failure result of FR-011 — `failed[]` not empty, however many archives were written,
    also zero (the target folder could not be created, or the first archive failed), as FR-013
    records "a partial result (FR-011)" as "partially completed";
  - `inputs` (insertion order): `owner`, `diagramGroup`, `targetFolder`, `workflow.<n>` (one key
    per requested or, without `workflows`, selected workflow, as `repository.<n>` in
    `application/ImportService.java:2398-2410`), `file.<n>` (names only, once known),
    `acknowledgeClearTextSecrets`, `reason`; each value bounded to 500 chars
    (`ImportService.java:152-153`); `account` as for the other tools (`Wiring.java:302-303`);
    the record's `reason` field is the outcome text (counts and codes, never content).
  - a failed outcome record: the result still returns, with a call-level warning naming the
    `auditId` (as `application/ProcessControlService.java:290-302`).
- **Rationale**: the same reading for operators and the same `AuditLog`; "partially completed" is
  a distinct outcome the spec names, so a new enum value is clearer than `FAILED` with a text.
  `AuditLog` serializes `outcome` by name, so the new value needs no format change.
- **Alternatives**: one record updated at the end (the log is append-only, `infra/AuditLog.java`);
  `FAILED` with a "partial" reason (not filterable).

## R-13 Order of the checks and which error wins

- **Decision**: `ArchiveExportService.export(request, context)`:
  1. resolve `target` (`TARGET_UNKNOWN` / `INVALID_INPUT` as `TargetResolver`), owner
     (`NOT_CONFIGURED` as `ExportArtifactsTool.java:82-89`);
  2. static input checks, first failure wins: production opt-in (`PRODUCTION_PROTECTED`, naming
     `archives.productionOptIn`) → acknowledgement (`INVALID_INPUT`) → `diagramGroup`, `workflows`
     and `reason` syntax and limits (`INVALID_INPUT`) → folder rules (R-9, `INVALID_INPUT`);
  3. any failure in 1–2 → `REFUSED` record, then the error. **If that record cannot be written,
     `INTERNAL` wins** and its message carries the original code and message ("the call was
     refused anyway (INVALID_INPUT: …); nothing was exported or written") — the existing refusal
     pattern (`application/ProcessControlService.java:486-505`, `ImportService.java:2483-2500`);
  4. `PENDING`; if it cannot be written → `INTERNAL`, nothing exported (US3-5);
  5. version gate (`UNSUPPORTED_VERSION`) → StartCLI export (`export_artifacts` codes) → slice
     (`UNEXPECTED_RESPONSE` for an unusable archive);
  6. export-dependent checks, all decided before the first write, first failure wins: unknown
     workflow names (`INVALID_INPUT`, naming them; the spec's edge case says "refused", and
     `NOT_FOUND` stays reserved for StartCLI's missing diagram group) → more than 50 without `workflows`
     (`INVALID_INPUT`, count and hint) → file name over 255 bytes (`INVALID_INPUT`) → collisions
     between selected workflows, then with the folder listing (`INVALID_INPUT`, colliding names);
  7. write (R-11); failures go into the result (FR-011), audited `PARTIALLY_COMPLETED` (R-12),
     also when no archive was written;
  8. outcome record; INFO log line with node, diagram group, counts and `auditId`.

  Errors of 5–6 are returned as tool errors after the outcome record `REFUSED`/`FAILED`; if that
  record cannot be written, the original error is returned with a sentence naming the `auditId` of
  the `PENDING` record (the action itself already ran or was refused; nothing more to protect).
- **Rationale**: target and owner must be known for the audit record and the opt-in; the static
  checks are pure and fast; the `INTERNAL` precedence follows the existing tools, so an operator
  sees one rule everywhere ("no audit → INTERNAL").
- **Alternatives**: static error wins and the audit failure is only logged (inconsistent with
  every write tool and with FR-013's "if it cannot be written, the call is refused with
  `INTERNAL`").

## R-14 Result shape and bounds (FR-012)

- **Decision**: see contracts/mcp-tools-delta.md. `ArchiveExportService` fills module, missing-module
  and repository-path lists archive by archive in output order until the sum reaches
  `resultLimits.maxItems` (`application/ResultLimiter.java:65`); later items are dropped and
  `truncated: true` is set; `moduleCount`, `repositoryFileCount` and `missingModuleCount` stay
  complete. The clear-text statement is a fixed field `clearTextSecrets` (text) at the top of the
  result. `auditId` is part of the result.
- **Rationale**: the budget rule of round 3 (missing modules included); a fixed field cannot be
  overlooked by a client that summarizes warnings.
- **Alternatives**: a manifest file (forbidden by FR-012); per-archive limits (uneven, harder to
  explain).

## R-15 Production opt-in setting (FR-014; review round 3, finding 8)

- **Decision**: group-level block **`archives`** with **`archives.productionOptIn`** (boolean,
  default `false`), new record `config/ArchivesConfig(Optional<Boolean> productionOptIn)` as a
  component of `GroupConfig` (`config/GroupConfig.java:17-35`), resolved into
  `EffectiveNodeConfig.archivesProductionOptIn()`. Only on groups (not `defaults`, not nodes):
  the opt-in is a per-stage decision. Set on a group without `production: true` → startup warning
  "has no effect" (as `development.repository: WRITE` without a development node,
  `docs/setup.md:589-590`). `--check-config` appends to each group header
  (`config/ConfigSummary.java:103-108`) the wording of contracts/configuration-delta.md:
  `archives: allowed` / `archives: refused (no archives.productionOptIn)` /
  `archives: allowed (archives.productionOptIn)`.
- **Rationale**: the name mirrors `write.productionOptIn` (`config/WriteConfig.java:5-8`,
  `docs/setup.md:389`) so the operator recognizes the pattern, while the separate block keeps it
  independent of `write.*` (FR-014). Jackson binds the YAML to the records with
  `FAIL_ON_UNKNOWN_PROPERTIES` (`config/ConfigLoader.java:109`), so a misspelled key stays a
  startup error.
- **Alternatives**: `archives.clearTextOnProduction` (the proposal; describes the effect but breaks
  the `productionOptIn` naming of the other production switch); a key under `write` (FR-014
  demands independence from `write.*`).

## R-16 Announcement

- **Decision**: `export_archives` is announced exactly when `export_artifacts` is: at least one node
  has `cliHome` (`Wiring.java:441-443`); production groups without the opt-in do not suppress it
  (FR-014). Without any CLI it is not announced (the tool could never succeed).
- **Rationale**: the spec's "always announced" is about production; the CLI condition is the one
  every StartCLI read tool shares.
- **Alternatives**: always announced (a tool that can only fail confuses tool selection,
  Constitution IV).

## R-17 Tests, fixtures and the live check

- **Decision**: fixtures are built in test code (`WorkflowArchiveFixtures`) from `grp-a.zip` /
  `grp-b.zip` with `RawZip`: a 10-workflow group (SC-002), a bound module, a nested node, a missing
  module, a Workflow Connector node (type string unverified, R-5), a repository chain (`msg.xsd` →
  `core.xsd`), a `Repository.zip` with directory entries (one ancestor of a kept file, one not;
  R-7) and secret-bearing
  modules (the synthetic secrets of `fixtures/v8_1/artifacts/README.md`). The secret-leak test
  `security/ArchiveExportSecretLeakTest` runs the tool end to end with the production adapters and
  the scripted StartCLI launcher and asserts that no synthetic secret occurs in results, errors,
  logs or audit records, that the archives *do* contain them, and that the private export
  directory is gone. SC-001 is a manual task: the operator exports one archive from the
  development stage and imports it in the Workbench, and confirms the `Workflow Connector` type
  string against a real `module/module.xml` (R-5); it runs only with their approval.
- **Rationale**: no new recorded file means no new neutralization work and no risk of customer
  identifiers; the identifier check (`security/NoCustomerIdentifiersTest`) must run, not skip, in
  the final validation (it skips without the local lists, `NoCustomerIdentifiersTest.java:29`,
  `:53`).
- **Alternatives**: a new recording (needs a real server and the neutralization tools).
