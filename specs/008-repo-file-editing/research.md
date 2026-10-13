# Research: Editing INUBIT Repository Files on a Development Stage (feature 008)

Decisions for [plan.md](plan.md). The code locations come from an analysis of `main` at `c9b70fa`.
Facts about INUBIT come from the spike
([spike sections 10 and 11](../../docs/research/spike-development-deployment.md)). Section 11
holds the results of the live probes P-1 to P-3 (task T001), which the decisions below name where
they depend on them.

## Starting point (verified in the code)

- `ChangeSetBuilder.build` refuses **every** changed file below `repository/` of the owner with
  `INVALID_INPUT` (lines 134-139). This happens for every scope, so an unrelated repository edit
  blocks all imports of that owner today.
- `ConflictDetector.key` returns `null` for repository paths. Repository files are therefore not
  part of the server fingerprint, the write-back, the verification or the rollback of a development
  call.
- `ImportAssembler.guard` refuses repository files inside a workflow or module archive, and
  `Repository.zip` stays empty. Repository files can only be written by `ImportPort.importRepository`
  (repository mode, spike section 10).
- Feature 005 already provides:
  - `ArtifactPort.exportRepository(path)` and `ImportPort.importRepository(archive, owner)`;
  - `RepositoryArchive.build` for entries relative to `/Root/<owner>`, with path, metadata and
    key-material checks;
  - `ArtifactClassifier.repository`, which compares byte for byte;
  - the backup and rollback pattern in `NodeDeployer`, with the repository import first and
    byte-identical verification.

  These building blocks are private to `ReleasePlanner` and `NodeDeployer` or take only exports as
  input.
- `ArchiveAssembler.repository` already rebuilds `RepositoryFile`s from workspace content plus the
  `.meta` metadata, and recomputes `contentMD5` and `contentSize`.
- The workspace layout is `<group>/<owner>/repository/Root/<owner>/<path>` with `.meta/<same>.json`
  holding the normalized `<Property type="RepositoryFile">` element.
- `WorkspaceWriter.render` writes a repository file only if its path occurs as a substring in the
  text of an exported workflow, module or embedded document. This is **not transitive**: repository
  file contents are not scanned.
- Key material is recognised differently in two places:
  - The export side (`SecretRedactor`, using `KeyMaterial`) withholds keystores and private keys
    only.
  - `RepositoryArchive.isKeyMaterial` (used by deployments) also refuses certificates.

  So a `.cer` or `.pem` file can be in the workspace but can never be imported.

## D-1 Configuration key and values

**Decision**:
- The switch is `development.repository` with the values `READ_ONLY` (default) and `WRITE`, on
  `defaults`, group and node (the node wins). It is a new component of `DevelopmentConfig`, resolved
  in `EffectiveNodeConfig` like `development.confirmation`.
- `--check-config` and the docs show it as `repository: read-only` / `repository: write`.
- The spec's FR-002 was amended to name the key and the values.

**Rationale**:
- Every configuration enum is upper case and bound by constant name (`ConfirmationMode`,
  `E2ePolicy`, `DeployMode`), and the loader lists the allowed constants in its error text.
- The switch only has an effect on development stages, so it belongs to the `development` block.

**Alternatives considered**:
- A top-level `repository:` key with lower-case values `read-only`/`write`. Rejected: it needs a
  custom deserializer and mixes case styles in one file.
- `development.repositoryWrite: true|false`. Rejected: a boolean cannot later grow a third mode
  without a breaking change.

## D-2 Explicit versus inherited `WRITE` (FR-003)

**Decision**: `ConfigValidator` gets `checkRepositorySwitch` on the **raw** configuration
(`ProfileConfig.groups()`, `defaults()`), like `checkStructure` and `checkChain` do.
- **Startup error** for:
  - `development.repository: WRITE` set on a node whose effective `development.enabled` is false;
  - `WRITE` set on a group with `production: true`, or on any node of such a group;
  - an unknown value. The loader already reports this.
- **No error** when `WRITE` reaches a non-development node only through inheritance from
  `defaults` or its group. `EffectiveNodeConfig` resolves such a node to `READ_ONLY`: the effective
  value is `WRITE` only if `development.enabled && !production`.
- **Warning** (not fatal) when `WRITE` is set on a non-production group that has no
  development-enabled node. This answers the open minor finding of spec review round 2.

**Rationale**:
- This is what the spec decides (FR-003).
- `EffectiveNodeConfig` has already merged the levels, so the explicit placement is only visible
  in the raw records.
- It deliberately differs from `development.enabled`, where an inherited `true` on a production
  group is an error (`ConfigValidatorTest.developmentInheritedFromTheDefaultsByAProductionGroupIsAnError`).
  An inherited repository switch on a non-development node has no effect, so there is nothing to
  protect against.

**Alternatives considered**: treat an inherited value like an explicit one. Rejected by the spec:
`defaults: WRITE` would fail in almost every profile (spec review round 1).

## D-3 Reference closure: one scanner for export, import and check (FR-008, FR-011)

**Decision**:
- A new `RepositoryReferences` scanner in `adapter/archive/v81` behind `ArtifactInspectorPort`
  (`repositoryReferences(WorkspacePath, byte[])`).
- A closure `RepositoryClosure` as a pure-Java class in `domain/model`. It takes the start
  references and a function from a repository path to the references of that file (empty if the
  file is not available).
- `application` (import, check) supplies the function through the port. `adapter/archive/v81`
  (`WorkspaceWriter`) supplies the scanner directly. `PackageBoundaryTest` forbids
  `adapter.archive → application`; the domain is reachable from both (plan review round 1).

The scanner finds:
- `inubitrepository:` URIs in workflows, `module.xml`, embedded documents and repository files.
  It reuses the pattern of `WorkspaceInspector` and replaces the duplicate in
  `ArtifactCheckService`.
- In **repository files only**, relative `href` / `schemaLocation` of `xsl:import`, `xsl:include`,
  `xs:import`, `xs:include` and `xs:redefine`, resolved against the file's own repository path.
  This is how Saxon and the JAXP validator resolve them through `WorkspaceUriResolver`.

The closure follows these references with a worklist, visits each path once (so cycles are no
problem), and stops at the owner's repository root. A reference into `/Root/<other>/…` is not
followed and gives the warning `REPOSITORY_REF_OUTSIDE_OWNER`.

It is used in three places:
1. **Import** (FR-011): the modules of the scope define the start set. For a diagram group that is
   every module the group's workspace workflows reference, changed or not; for a module scope it is
   the listed modules.
2. **Export** (`WorkspaceWriter.render`): the repository files written by a diagram-group or
   module export become the transitive closure within the export's `Repository.zip`, no longer only
   files named in artifact texts. Without this, a schema imported only by another schema would
   never reach the workspace, and the import could not have a base for it.
   Probe P-3 (spike section 11) showed that INUBIT's own diagram-group export, at head and by tag,
   puts indirectly referenced files into `Repository.zip`: files that only another repository file
   references, by a relative `xsl:import` or by an `inubitrepository:` `xsl:import`. The closure
   therefore writes what INUBIT exports and only drops what no structural reference reaches.
   Chains deeper than one level and schema `xs:include`/`xs:import` as the indirect reference were
   not probed; the closure treats them the same way.
3. **Check** (FR-008): `check_artifacts` scans repository files too. A nested reference that is
   missing gives the existing `REPOSITORY_REF_MISSING` / `REPOSITORY_REF_UNVERIFIED`.

**Rationale**:
- With one definition of "referenced", what is exported, what is checked and what is imported
  always agree, and they match what runs in INUBIT.
- The substring test in `WorkspaceWriter` is replaced by a real reference parse. A string literal
  that is no reference is no longer treated as one, which matches spike section 10.

**Alternatives considered**:
- A substring test over repository contents. Rejected: it misses relative includes and keeps the
  string-literal false positives.
- Following only `inubitrepository:` URIs. Rejected: XSDs normally use relative `xs:include`.

**Two uses of the scanner (decision during T007 review)**: the closure (export, import) follows only
**structural** references: `xsl:import`/`xsl:include`/`xs:import`/`xs:include`/`xs:redefine`, and
`inubitrepository:` URIs outside XSLT and schema documents. These are what INUBIT's export carries
(spike section 10). `check_artifacts` keeps its previous, wider coverage:
- every `inubitrepository:` URI in modules, embedded documents and workflow texts, including
  `document('inubitrepository:…')` calls and URIs in `xsl:variable`;
- raw-text scanning of documents that are not well-formed XML.

The check is a superset of the closure, so a missing structural reference is always reported, and a
missing runtime lookup file still is too. The scanner offers both modes from one pattern and one
decoding.

**Open question (T018 review)**: module index entries name schemas without a scheme, e.g.
`<InputMessageTypeSchema>/Root/<owner>/xsd/msg.xsd</InputMessageTypeSchema>` (fixture
`export_modules_sample.zip`). Neither the old substring test nor the scanner treats these as
references, so such a schema is not part of the closure for export, check or import. It can still
be exported and imported through the repository-path scope. Whether INUBIT's group export carries
message-type schemas in `Repository.zip`, and whether the closure should start from them, is
undecided and needs the person's decision (and possibly a read-only probe).

**Effect on feature 003**: an export can now write more repository files than before, namely the
indirectly referenced ones. This is a behaviour change. It is documented in `docs/setup.md` and
the CHANGELOG and covered by `WorkspaceWriterTest`.

## D-4 Repository changes in the change set (FR-010, FR-011, FR-017)

**Decision**:
- `ImportScope` gets a third alternative, `repositoryPaths: List<String>`: 1–50 paths relative to
  the owner's root (FR-005). Exactly one of `diagramGroup`, `modules` or `repositoryPaths` is
  allowed.
- `ChangeSet` gets `repository: List<ChangedArtifact>` with `ArtifactRef.Kind.REPOSITORY_FILE`.
  `isEmpty`, `artifacts`, `paths`, `created`, `modified` and `existing` include it.
- `ChangeSetBuilder` replaces the refusal with a classification:
  - **In scope**:
    - for `repositoryPaths`, every changed file at or below one of the paths;
    - for `diagramGroup` and `modules`, every changed file in the closure (D-3).
  - **Out of scope**: changed files go to `notImported`. Deleted files out of scope go to
    `notImported` too.
  - **Base**: the newest `Server-State: <group>` commit that touched the path (the existing
    `LocalChange.serverState`), meaning the last verified state from an export or a development
    call (FR-013). No base means NEW. A MODIFIED file without a base is not possible.
- Refusals before anything is read from the node:
  - `INVALID_INPUT` for:
    - a deleted file in scope;
    - a file below `repository/Root/<other owner>/`;
    - a path that the StartCLI rule refuses. The rule moves from the private
      `ReleasePlanner.repositoryPathPassable` into a new `domain/model/RepositoryPaths.passable`,
      used by `ReleasePlanner`, `ChangeSetBuilder` and `ImportScope`. The domain stays free of
      `adapter.cli`;
    - key material by the existing `ReleaseArchivePort.keyMaterial` (= `RepositoryArchive.isKeyMaterial`,
      which already includes `KeyMaterial` and also catches certificates);
    - content containing `${secret:`;
    - a file over 10 MiB;
    - more than 50 MODIFIED files (these are always sent), or more than 200 NEW+MODIFIED files (a
      hard cap that bounds the node read);
    - `tag` together with a `repositoryPaths` scope.
  - After the node read (D-5), before anything is sent, these are `INVALID_INPUT`:
    - more than 50 files to send (`create` + `modify`; `identical` files do not count, FR-017);
    - a `create` file (absent on the node) whose extension is not in the content-type table (D-7).
      A NEW file the node already holds (`modify` + `existing`) is not refused for its extension.
  - `PRECONDITION_FAILED` ("export the path first") for a repository file whose content has a
    `Server-State` base but whose `.meta` record is missing or unreadable. Without the exported
    metadata the archive cannot be rebuilt faithfully.

**FR-017 "changed file without a verified base"**: a workspace file whose history has no
`Server-State` commit is by definition NEW. It follows the `create` / `modify`+`existing` path of
FR-012, and the preview's `repositoryDiff` shows what would be overwritten. A MODIFIED file without
a base cannot occur. The only reachable "base missing" condition is the incomplete base above (the
`.meta` record lost), and that is what yields `PRECONDITION_FAILED`. The spec wording of FR-017 and
of the edge case "No verified base" were amended accordingly; the amendment was
applied to the spec on 2026-10-13.

**FR-005 deviation (deliberate, stricter)**: the spec allows normalizing a path and refuses it only
if it leaves the root afterwards. The plan refuses every path with `.`/`..`, `%`, an empty segment
or a leading `/` outright. These forms are never needed and only add ways to slip past the
checks.

**Rationale**: these are the existing feature-004 structures, extended rather than duplicated.
The union of the two key-material detectors closes the gap found in the analysis.

**Alternatives considered**: a separate tool for repository imports. Rejected by Constitution IV
and by the spec, which says no new tool.

## D-5 Node state, conflicts and preview (FR-012, FR-013, FR-014)

**Decision**: a new collaborator `RepositoryStep` in `application` holds all repository-specific
logic, so the 2,000-line `ImportService` only delegates. It takes over the repository parts of
`ReleasePlanner.nodeState` and `NodeDeployer` as shared package-private helpers:

1. **Read the node state.**
   - One `exportRepository` call per **distinct parent folder** of the change set's files, and one
     per folder for the verification and the rollback.
   - Spike section 10 shows that an export returns every file below the path. If a folder export
     exceeds the `RepositoryArchive` read limits (64 MiB per entry, 128 MiB in total), the step
     falls back to one export per file.
   - `NOT_FOUND` on a folder means every file below it is absent.
2. **Classify each file.**
   - NEW in the workspace and absent on the node → `create`.
   - NEW and identical on the node → `identical`; it is not sent.
   - NEW with other content on the node → `modify` and additionally `existing`.
   - MODIFIED, with the node equal to its base → `modify`.
   - MODIFIED with the node different from its base → `CONFLICT`, with
     `.reports/conflict-<auditId>.diff` written by the existing `LineDiff`.
   - Only content counts. The comparison is byte for byte, like `ArtifactClassifier.repository`.
3. **Fingerprint.** A sorted list of `repository:<path>=sha256(content)` (the `NodePlan.key` form)
   joins the server fingerprint of `ConflictDetector`, so the confirmation code is bound to it.
   The workspace hash (`workspaceState`) includes the repository contents.
4. **Preview.**
   - The repository paths appear in the existing lists `create`, `modify`, `existing` and
     `identical` as full repository paths (`/Root/<owner>/…`). These cannot be mistaken for
     artifact names.
   - New field `repositoryReferences`: per file, up to 20 workspace artifacts outside the scope that
     reference it, with a truncation flag. The full list goes to
     `.reports/repository-references-<auditId>.txt`.
   - New field `repositoryDiff`: `.reports/repository-<auditId>.diff`, a line diff per `modify`
     file between the node and the workspace. This answers the open minor finding of spec review
     round 2, and matters most for `existing` files, which have no base.
5. **Switch check** (FR-004), done after the node state is read and before anything is sent. If any
   file is `create` or `modify` and the node's effective switch is `READ_ONLY`, the call is refused
   with `REPOSITORY_WRITE_DENIED`, listing the paths, and the refusal is audited. Reading is always
   allowed, and an all-`identical` change set is not refused (FR-004 after review round 2). To
   avoid a wasted node read, a cheap **pre-check** runs right after `ChangeSetBuilder`: on a
   `READ_ONLY` node, a change set with repository files that are MODIFIED in the workspace (which
   are never identical to their base) is refused at once.

**Rationale**: putting everything repository-specific in one class keeps `ImportService` readable
and lets the repository logic be tested on its own with `FakeServer`.

**Durations (P-2, spike section 11)**: one repository export took 2.4–2.7 s, one repository import
2.3–2.8 s, a diagram-group export 2.9–4.1 s and a `tag` 3.8 s, all with small files. For SC-001
(20 files in 3 folders) the repository part needs 3 exports for the preview, 1 import and 3 exports
for the verification: 7 calls, about 17–19 s. A diagram-group import adds the feature-004 artifact
exports, import and optional tag, a few calls of about 3–4 s each. This leaves a wide margin
within the 2 minutes; files of up to 1 MiB were not timed.

**Alternatives considered**:
- One export per file. Rejected: StartCLI starts a JVM per call. For 20 files that is 20 exports
  for the preview, 1 import and 20 exports for the verification, 41 calls or about 100–110 s by the
  P-2 durations, which would use up almost all of SC-001's 2 minutes.
- One export of the owner root. Rejected: it can be very large (Constitution VI) and runs into the
  archive limits.

## D-6 Confirmation always issued by the server for repository writes (FR-012, FR-018)

**Decision**: `DevelopmentGuard` gets `forRepositoryWrite(policy)`, which returns the policy with
`confirmation = SERVER`, like `forDeploymentRestore`. `ImportService` uses it once the change set
(import) or the restore set (restore) contains a repository file with `create` or `modify`. The
input fingerprint additionally contains `repositoryPaths`.

**Order in `ImportService.locked`**: today the SERVER/CLIENT decision (line 371) and `redeem`
(line 372) come before `detector.detect`. Whether a repository file is to be sent is only known
after the `RepositoryStep` node-state read, because a NEW file may turn out `identical`.
- If the change set contains repository files, the mode decision and the `redeem` move after that
  read. The checks of the server fingerprint and the workspace hash against the previewed state
  stay.
- Without repository files, the order of feature 004 is unchanged.

**Rationale**: the existing mechanism is reused, and the binding stays exactly as in feature 004:
inputs, workspace hash and server fingerprint.

## D-7 Content type and metadata of new files (assumption in the spec)

**Decision**:
- A NEW file gets a synthesized `<Property name="<file>" type="RepositoryFile" path="<repository
  path>" contentType="<type>"><Description/></Property>`, built by a new
  `RepositoryMetadata.forNewFile` in `adapter/archive/v81`. `RepositoryArchive.importMetadata`
  then adds `contentSize` and `contentMD5`. This is the form probe P-1 sent and INUBIT accepted.
- The content type comes from a fixed table (final after P-1, spike section 11):

  | Extension | Content type | Source |
  |---|---|---|
  | `.xsd` | `application/x-xsd+xml` | INUBIT's type on existing files no probe created (P-1); fixture `export_repository.zip` |
  | `.xsl` | `application/xslt+xml` | fixture `export_repository.zip`; kept by INUBIT on import (P-1) |
  | `.xslt` | `application/xslt+xml` | same language as `.xsl`; kept by INUBIT on import (P-1) |
  | `.wsdl` | `application/wsdl+xml` | the registered media type of WSDL; kept by INUBIT on import (P-1) |
  | `.xml` | `application/xml` | INUBIT's type on an existing file no probe created (P-1) |

  Why this table:
  - INUBIT stores the type that is sent and derives nothing from the extension. A file sent
    without a type becomes `application/octet-stream` (P-1). So every created file needs a type
    from the table, and every other file is sent with its metadata (`.meta` or the node's, below).
  - `.xsd`, `.xsl` and `.xml` use the types INUBIT already has on such files.
  - For `.xslt` and `.wsdl` INUBIT's own default (for example from a Workbench upload) was not
    observed, because no such file existed. `.xslt` gets the `.xsl` type because both hold XSLT.
    `.wsdl` gets the registered media type. The spec requires both extensions (assumption "at least
    `.xsd`, `.xsl`, `.xslt`, `.wsdl`, `.xml`"). If a Workbench-created file shows another type, only
    the type of files this server creates differs; references go by path, not by type.
- Every other extension is refused for a `create` file (FR-017), after the node read, and the error
  names the supported extensions.
- **`anonymousRead` is not synthesized.** INUBIT gives a new file without it `anonymousRead="false"`
  (all 14 files the probes created), while the existing probe files have `"true"`, which the
  feature-005 probe archives had sent.
  - Without the attribute a created file gets INUBIT's own default for imports. The server does not
    choose a wider setting than INUBIT does; the meaning of `anonymousRead` was not probed.
  - A MODIFIED or EXISTING file keeps the value from its metadata (below).
  - Anyone who needs `"true"` on a new file sets it in the Workbench. The next export or write-back
    brings it into `.meta`.
- Other values INUBIT sets on a created file and that need no synthesis (P-1, P-2):
  - `version="1.0"`;
  - `versionComment="DefaultCommitCommentImport@@@null"`;
  - its own `uuid`.

  INUBIT drops the empty `<Description/>`. The `.meta` written back from the re-export records
  these values.
- A MODIFIED file keeps its `.meta` metadata (`contentType`, `Description`, `uuid`, `anonymousRead`;
  INUBIT ignores `uuid` and `version`, spike sections 10 and 11).
- An EXISTING file (new in the workspace, already on the node) has no `.meta` record. It takes the
  metadata of the node's file from the node-state export (D-5), so it keeps the node's content type
  and description whatever its extension (spec FR-017, clarification of 2026-10-13).
- After a verified import, `.meta` is rewritten from the node's re-export.

**Probe P-1** (run in T001, results in spike section 11): in the person's personal test repository
area, export existing repository files of each type (or create one per extension in the Workbench and
export it), and record INUBIT's `contentType` for `.xslt`, `.wsdl` and `.xml`. Then import one
synthesized new file per extension and check, by re-export, that the content type is kept.
- **Result**: the sent type is kept for all five extensions (`export_repository_types.zip`).
  Without a type INUBIT stores `application/octet-stream`.
- `.xml` and `.xsd` were observed on existing files. No `.xslt` or `.wsdl` file existed, so the
  table above decides them.

## D-8 Execute, verify, roll back and write back (FR-015, FR-015a, FR-016)

**Decision**, as an extension of `ImportService.execute`/`send`:

1. **Backup.**
   - The node's repository exports for the changed files go into the same backup ZIPs as the
     artifact exports.
   - The IMPORT manifest now also persists `repositoryPaths`. `ManifestJson` writes and reads them
     for both kinds.
   - `updateManifest` keeps all fields via `Manifest.with(…)` instead of the 10-argument
     constructor. That constructor currently drops `kind` and `repositoryPaths`, which is a latent
     bug.
   - `intendedState` gets `repository:<path>` keys.
2. **Send.**
   - One `importRepository` call with an archive built from the workspace by `RepositoryStep`, via
     a new `ReleaseArchivePort.repositoryArchiveFromFiles(owner, List<RepositoryUpload>)` that wraps
     `RepositoryArchive.build`.
   - Then, if there are workflows or modules, the existing `importArchive`. Repository files are
     never put into the artifact archive (`ImportAssembler.guard` refuses them), and for a
     repository-only change set the artifact archive is neither assembled (preview, line 411) nor
     imported.
   - This is the order of feature 005: files first, so modules bind to the new content.
   - A call with repository files only skips `importArchive`.
3. **Verify.**
   - Re-export per folder. Every sent file must be byte-identical to the workspace content. This is
     the deployment rule; feature 005's live test and P-2 (an LF-only file without a trailing
     newline included) show byte identity for imported content.
   - Only the content is compared, never the metadata. Every import creates a new version, also
     for unchanged content or a restore of earlier content, and adds one
     `DefaultCommitCommentImport@@@` to `versionComment`; a sent `version` is ignored (P-2, spike
     sections 10 and 11). The re-exported metadata therefore never equals what was sent. This is
     also why `identical` files are not sent (FR-017): sending them would only add versions.
   - The reason is not checked on repository files, because INUBIT ignores their version comment
     (FR-015a).
   - A mismatch is `VERIFY_MISMATCH` with `.reports/verify-<auditId>.diff`.
4. **Rollback.**
   - On any failure after sending, re-import the previous content of the modified files from the
     backup exports. This happens before the artifact rollback, so the artifacts are restored
     against the old files.
   - Verify by re-export, by content only as in step 3. The rolled-back files get a new version on
     the node even though their content equals the backup (P-2).
   - Created files stay and are listed in `createdNotRemoved`, because an import cannot delete.
5. **Write back.**
   - Only the **sent** repository files and their `.meta` records are rewritten from the re-export.
     The folder export also returns unrelated files of the folder; those are ignored.
   - The single `import <node>: … [auditId]` commit with `Server-State: <group>` also covers them,
     which makes the commit the new base (FR-016).
   - The rewritten `.meta` differs from the previous record after every import (`version`,
     `versionComment`, `modified`), even for unchanged content. The same holds on the node after a
     rollback or a restore (D-9): the content is back, the metadata is not. No step compares
     `.meta` records. After a rollback the workspace keeps its previous `.meta`, whose `version` is
     then older than the node's. That is harmless, because INUBIT ignores a sent `version`.

**Probe P-2** (run in T001, results in spike section 11): on the personal test repository area,
1. Import a folder of two new files and one changed file from a synthesized archive (with folder
   entries for a new subfolder).
2. Re-export the folder, then compare bytes for an LF-only file without a trailing newline.
3. Re-import the previous content.
4. Measure the duration of one repository export and one import for the SC-001 estimate.

**Result**:
- New folders and subfolders are created from the archive.
- The re-export is byte-identical, the LF-only file without a trailing newline included.
- Every import, the restore included, creates a new version (step 3 above).
- The durations are in D-5.

## D-9 Restore (FR-018)

**Decision**:
- `ImportService.restoreSet` takes the manifest's `repositoryPaths` that existed before the call.
  Their previous content comes from the backup's repository exports, read with
  `ReleaseArchivePort.repositoryExport`/`repositoryFiles`.
- Created files are not restorable and are listed in `createdNotRemoved` (`manifest.created`).
- `requireStateLeftBy` compares the `repository:<path>` fingerprints of `intendedState` with a fresh
  per-folder export. A difference is `CONFLICT`.
- The switch check (`REPOSITORY_WRITE_DENIED`, the whole restore refused) and `forRepositoryWrite`
  apply.
- A DEPLOYMENT backup still goes to `NodeDeployer.restore` unchanged, without the switch (FR-018).

## D-10 Repository-path export (FR-009, US3)

**Decision**:
- `export_artifacts` gets `repositoryPaths` (1–20, relative, FR-005) as a third, exclusive scope.
- `WorkspaceService` runs one `exportRepository("/Root/<owner>/<path>")` per path.
- A new `ArchiveCodec.prepareRepository(group, owner, archives)` renders the files with the
  `WorkspaceWriter` repository branch. That branch keeps withholding key material by `KeyMaterial`,
  as today. A certificate is written, as by every export; the import refuses it.
- Before anything is written, the files are counted against the limits of 500 files and 100 MiB.
  A larger scope is `INVALID_INPUT`, naming the count and the size.
- The scope `prepared.scope()` is the exported paths and their `.meta` mirrors only. It is not the
  owner root, so other workspace files are never touched. Files in the workspace that are missing on
  the node stay (feature 003: overwritten, never deleted).
- Commit: `export <node>: repository <paths> (<n> files)` with `Server-State: <group>`. This commit
  is the base for later imports.
- If any path is `NOT_FOUND`, the whole call fails with `NOT_FOUND` naming every missing path, and
  nothing is written. All exports run into the private temporary directory first, and rendering
  starts only when every path was found (spec FR-009, US3-3).
- No switch is needed (FR-009).

**Case collisions**: two repository paths that differ only in letter case would overwrite each
other on a case-insensitive file system. The export refuses them with `INVALID_INPUT`, naming both.
This answers the open minor finding of spec review round 2. The same check applies when
`WorkspaceWriter` renders a diagram-group export.

## D-11 Audit (FR-019)

**Decision**:
- `Call.inputs` gets one key per repository file: `repository.<n>` =
  `<create|modify|existing|identical> /Root/<owner>/<path> sha256:<16 hex>`. Each value stays
  within the existing 500-character limit (`MAX_AUDITED_INPUT`; a longer path is cut with "…", the
  hash is kept). One key per file: at most 50 files are sent, but identical files and refused
  oversized change sets are listed too, so 50 is the normal case, not a cap (T027 review).
- `scope` shows `repository paths a, b`.
- The matching rule is gone, because there is only the switch. The refusal reason names the switch:
  `REPOSITORY_WRITE_DENIED: development.repository is READ_ONLY on <node>`.
- No content is recorded.

**Alternatives considered**: one long value. Rejected: it would be cut at 500 characters and lose
paths.

## D-12 Tag information (spec edge case "Tag")

**Decision**:
- The tag verification stays as in feature 004.
- `result.tag` gets an optional `repositoryFiles` list: the imported repository paths in the
  closure (D-3) of the group's modules, whether a module references them directly or only another
  repository file does. Spike sections 10 and 11 show that INUBIT's group tag covers both.
- No warning for indirectly referenced files. The warning planned before P-3 is dropped.

**Probe P-3** (run in T001, results in spike section 11): check whether a group-scoped tag also
tags a repository file that only another repository file references, and whether a diagram-group
export puts such a file into `Repository.zip` (D-3). If it does, drop the warning and list every
included path.

**Result**: yes to both.
- The group's modules referenced one stylesheet. That stylesheet imported a second one by a
  relative `xsl:import` and a third one by an `inubitrepository:` `xsl:import`.
- After the group tag, a repository export by tag returned all three files with `tagName`.
- The diagram-group export, at head and by tag, held all three in `Repository.zip`.
- Deeper chains and schema includes as the indirect reference were not probed. The list follows
  the closure for them as well; it is information, not verified (spec edge case "Tag").

## D-13 `list_nodes` and `--check-config` (FR-006)

**Decision**:
- `NodeSummary` gets `repositoryWrite: boolean` (effective value), and `list_nodes.output.json`
  requires it. `ListNodesToolTest` pins the field order.
- `ConfigSummary` prints `, repository: write` or `, repository: read-only` after
  `development: on (confirmation SERVER)`, only on development nodes. The literal that
  `DevelopmentToolsReferenceTest` checks stays intact.

## D-14 Error code

**Decision**: a new `ErrorCode.REPOSITORY_WRITE_DENIED`, placed after `DEPLOY_LOCKED` and before
`INTERNAL`. Update `ToolErrorTest` (exact order) and `docs/tools.md` (`DevelopmentToolsReferenceTest`
requires every code). `REPOSITORY_REF_OUTSIDE_OWNER` is a warning text, not a code.

## D-15 Live tests and fixtures

**Decision**:
- Probes P-1 to P-3 run with the person's approval on the personal test repository area of the
  development node only, as in spike section 10.
- Their outputs are neutralized and recorded as fixtures:
  - `export_repository_folder.*`
  - `import_repository_new_files.*`
  - `export_repository_after_import.*`
- `FakeServer` learns:
  - storing `contentType` on import;
  - transitive references for its own export;
  - folder exports.
- `docs/live-tests.md` gets a repository section: export a test folder, change a file, import it
  with a preview and a code, restore it, all on the test area only.
