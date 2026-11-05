# Feature Specification: Workbench-Importable Workflow Archives

**Feature Branch**: `015-workbench-archives`

**Created**: 2026-11-05

**Status**: Draft

**Input**: GitHub issue #56 ("Export workflows as Workbench-importable archives") and the
operator's decisions of 2026-11-05: a new tool that is read-only for INUBIT, one archive per
technical workflow, secrets in clear text only with an explicit acknowledgement, a target folder
given in the call.

## Context

`export_artifacts` turns what INUBIT exports into readable files in the local workspace: one file
per workflow and module, every secret replaced by a placeholder, recorded in the workspace history.
Those files cannot be imported into the INUBIT Workbench again. An operator who wants a copy that
the Workbench can import — a backup before a risky test, a workflow handed to a colleague, an
attachment for a ticket — has to export it in the Workbench by hand, one workflow at a time.

StartCLI of INUBIT 8.1 cannot export a single workflow; its smallest unit is a diagram group, and
its archive holds every workflow of the group, the modules they use and the owner's repository. The
server already builds smaller archives itself for other tools (the single-workflow archive of
`set_active`, the per-module import archives of `import_artifacts`).

In this spec, "group" means a configured group of the profile (a profile's terminology may call it a
stage) and "diagram group" means INUBIT's diagram group. Checks happen at two moments: the
**static input checks** run before the StartCLI export; the checks that depend on the export run
**after the export and before the first file or folder is written** (FR-017).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Archive the workflows of a diagram group (Priority: P1)

The operator asks for Workbench archives of the workflows of one diagram group on a server and
names a folder on their machine. They get one archive per technical workflow in that folder and a
result that lists each file and says plainly that the files contain secrets in clear text.

**Why this priority**: it is the whole value of the feature: a Workbench-importable copy without
manual Workbench exports.

**Independent Test**: export a diagram group with three workflows from a fake INUBIT server into an
empty temporary folder; three archives appear, each holds exactly its workflow, the modules that
workflow's nodes reference and the repository files it references, with the module and repository
files byte-identical to the server's export; the files and the folders the call created are
readable by the owner only.

**Acceptance Scenarios**:

1. **Given** diagram group `GRP-01` with workflows `Workflow-0001`, `Workflow-0002` and
   `Workflow-0003` on `dev/node1` and an empty folder `/tmp/archives`, **When** the operator calls
   the tool with that diagram group, that folder and the clear-text acknowledgement, **Then** the
   folder holds one archive per workflow, named after the workflow, and the result lists the three
   files with their sizes, the modules (`{name, pluginType}`) and the repository files each
   contains (FR-001, FR-004, FR-006, FR-012).
2. **Given** `Workflow-0001` uses `Module-0002` and `Module-0005`, and `Module-0005` is also used by
   `Workflow-0002`, **When** the archives are written, **Then** the archive of `Workflow-0001`
   holds both modules, the archive of `Workflow-0002` holds `Module-0005` too, and neither holds a
   module its workflow's nodes do not reference (FR-005).
3. **Given** a workflow whose modules reference repository files (directly or through other
   repository files), **When** its archive is written, **Then** the archive holds exactly those
   repository files of the owner's repository, and no other repository file (FR-005).
4. **Given** a module with a password and a stylesheet with a saved test message, **When** its
   workflow is archived, **Then** the archive holds the values exactly as INUBIT exported them
   (no placeholder), and the result, the log and the audit record contain neither value
   (FR-007, FR-013).
5. **Given** the operator names only `Workflow-0002` of the diagram group, **When** the tool runs,
   **Then** only that workflow's archive is written (FR-002).
6. **Given** workflows named `Order: In/Out?.` and `CON`, **When** their archives are written,
   **Then** the files are named `Order_ In_Out_.diagram.zip` and `_CON.diagram.zip`, and the
   result lists each workflow with its file name (FR-004).

---

### User Story 2 - Clear-text secrets only on purpose (Priority: P1)

The archives contain the secrets of the exported workflows in clear text. The tool must make that
a deliberate choice, let the MCP client ask before it runs, and keep the files out of the server's
own directories, out of git working trees and off network paths.

**Why this priority**: an archive with clear-text secrets in the wrong place is a leak; this story
makes the feature safe enough to exist.

**Independent Test**: call the tool without the acknowledgement, with a relative path and with a
path inside the workspace — each is refused by the static input checks before the StartCLI export;
call it with a folder that already holds a file of an archive's name — it is refused after the
export and before the first file or folder is written. In every case nothing is written.

**Acceptance Scenarios**:

1. **Given** a call without the clear-text acknowledgement, **When** the tool runs, **Then** it is
   refused by the static input checks before the StartCLI export, and the message names the
   missing acknowledgement and why it is needed (FR-007).
2. **Given** a target folder that is relative, contains `.` or `..` segments, is or lies inside
   `~/.inubit-mcp`, the configured `workspace` or `auditDirectory`, or a directory with a `.git`
   entry in it or in an ancestor (also through a symbolic link), or (on Windows) is a UNC or
   network path, **When** the tool runs, **Then** it is refused by the static input checks before
   the StartCLI export (FR-008).
3. **Given** a target folder that already holds a file with the name an archive would get (compared
   case-insensitively), **When** the tool runs, **Then** it is refused after the export and before
   the first file or folder is written, the message names the colliding file(s), and no existing
   file is changed or deleted (FR-009).
4. **Given** a target folder that does not exist yet, **When** the tool runs, **Then** the folder
   (and missing parents) is created right before the first archive is written, readable by the
   owner only, and the archives inside are readable by the owner only (FR-010).
5. **Given** a target path that exists and is a file, or a directory the server cannot write to,
   **When** the tool runs, **Then** it is refused with `INVALID_INPUT` by the static input checks
   before the StartCLI export (FR-010).
6. **Given** any successful call, **When** the result is shown, **Then** it states that the files
   contain secrets in clear text and must not be shared or committed as they are (FR-012).
7. **Given** an MCP client lists the tools, **When** it reads this tool's annotations, **Then** the
   tool is not marked read-only (`readOnlyHint: false`, `destructiveHint: false`,
   `idempotentHint: false`, `openWorldHint: true`), so the client asks before calling it (FR-001).

---

### User Story 3 - Traceable and bounded (Priority: P2)

Every archive export is recorded, and a failure leaves no half-written archive behind.

**Why this priority**: the operator must be able to tell later who exported which workflows with
clear-text secrets, why, and where to.

**Independent Test**: run a successful call and calls that fail at the export, at a check after the
export and while writing; the audit log holds each call with the target, the workflows, the folder
and the outcome; the folder never holds a partial archive.

**Acceptance Scenarios**:

1. **Given** a successful call with a `reason`, **When** the audit log is read, **Then** it holds
   the call with the target, owner, account, diagram group, workflow names, target folder, file
   names, reason and outcome — never file content or secret values (FR-013).
2. **Given** StartCLI fails or times out, **When** the tool returns, **Then** the error is the same
   as for `export_artifacts` (code, message, next step), nothing is written, and the failure is
   audited (FR-011, FR-013).
3. **Given** writing the second of three archives fails (e.g. the disk is full), **When** the tool
   returns, **Then** it returns a result (not an error) with the first archive in `archives[]` and
   the second in `failed[]` (`{workflow, code, message}`), the third is not written, no partial
   archive is left in the folder, and the audit outcome is "partially completed" (FR-011, FR-013).
4. **Given** a workflow that references a module the diagram-group export does not contain,
   **When** the tool runs, **Then** the whole call is refused after the export and before the first
   file or folder is written, the message names the workflow and the missing module, and no
   archive is written — also not those of the other workflows (FR-011, FR-017).
5. **Given** the audit record cannot be written (e.g. the audit directory is not writable),
   **When** the tool runs, **Then** it is refused with `INTERNAL` before StartCLI runs and nothing
   is exported or written (FR-013).

### Edge Cases

- A diagram group without technical workflows: the call succeeds with an empty list and writes
  nothing (no folder is created).
- A diagram group with more than 50 technical workflows, called without `workflows`: refused with
  `INVALID_INPUT` after the export and before the first file or folder is written, naming the count
  and pointing to `workflows` (FR-002).
- A named workflow that is not in the diagram group (names are matched case-sensitively): refused
  after the export and before the first file or folder is written, naming the unknown workflow(s).
- A workflow name with characters that are not allowed in a file name on macOS, Linux or Windows,
  trailing dots or spaces, or a Windows reserved base name: the file name is derived by the rules
  of FR-004; a derived file name over 255 bytes is refused with `INVALID_INPUT` naming the workflow;
  two workflows whose file names are equal when compared case-insensitively are refused as a
  collision (FR-004, FR-009). Both refusals happen after the export and before the first file or
  folder is written.
- A workflow that references a module the diagram-group export does not contain (INUBIT exported it
  inconsistently): the whole call is refused after the export and before the first file or folder
  is written, naming the workflow and the missing module (FR-017).
- Two concurrent calls write into the same folder: each checks for collisions after its export, but
  a file the other call creates later is never replaced; the archive that would replace it is
  reported in `failed[]` naming the file, and the remaining workflows of that call are not written
  (FR-009, FR-011).
- A target path that exists and is not a writable directory: refused with `INVALID_INPUT` by the
  static input checks before the StartCLI export (FR-010).
- On Windows, a UNC path (`\\server\share\…`) or a mapped network drive (as far as detectable):
  refused by the static input checks before the StartCLI export (FR-008).
- Key material in the owner's repository that a workflow references: it is included unchanged,
  like every other secret (FR-007).
- The export of the diagram group is larger than the export limit of `export_artifacts`: refused
  with the same message.
- A production group without the opt-in: the tool is announced, and every call is refused by the
  static input checks before the StartCLI export, naming the setting (FR-014).
- A call whose arguments violate the input schema: rejected by the MCP layer and not audited, as
  for the other tools (FR-013).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A new MCP tool MUST export the technical workflows of one diagram group of one target
  (a group's first node, or one node — as `export_artifacts`) and owner (default
  `inventory.owner`) as Workbench-importable archives. It is read-only for INUBIT: it MUST NOT
  change anything on INUBIT and MUST NOT touch the workspace or its history. Because it writes
  local files, its MCP annotations MUST be `readOnlyHint: false`, `destructiveHint: false`,
  `idempotentHint: false` and `openWorldHint: true`, so clients ask before calling it.
- **FR-002**: The input MUST name exactly one diagram group (same name rules as `export_artifacts`)
  and MAY name `workflows`: 1–50 unique workflow names, each following the character rules of
  diagram group names, matched case-sensitively against the diagram group's technical workflows.
  Without `workflows` every technical workflow of the diagram group is archived, at most 50; a
  diagram group with more technical workflows is refused with `INVALID_INPUT` naming the count and
  pointing to `workflows`. The input MAY carry a `reason` (one line, at most 500 characters).
- **FR-003**: The tool MUST obtain the diagram group's content with one StartCLI diagram-group
  export, as `export_artifacts` does, and MUST keep the raw export in memory and in the private
  temporary directory of the export only; nothing of it may reach the workspace.
- **FR-004**: The tool MUST write one archive per selected workflow, named
  `<file name>.diagram.zip`, in the layout of a single-workflow export of the INUBIT 8.1 Workbench
  (the layout INUBIT's own diagram-group export uses, restricted to that workflow). The file name
  is derived from the workflow name deterministically: each of `<`, `>`, `:`, `"`, `/`, `\`, `|`,
  `?`, `*` and each control character becomes `_`; trailing dots and spaces are removed; a Windows
  reserved base name (`CON`, `PRN`, `AUX`, `NUL`, `COM1`–`COM9`, `LPT1`–`LPT9`, in any case) gets
  the prefix `_`. A resulting file name (with `.diagram.zip`) over 255 bytes in UTF-8 MUST be
  refused with `INVALID_INPUT` naming the workflow.
- **FR-005**: Each archive MUST contain exactly: the workflow; every module referenced by the
  workflow's nodes (also nested nodes); every module whose entry in `module/module.xml` binds it to
  the workflow by `WorkflowName` although it is not a node of it (e.g. a connector bound to the
  workflow); and the files of the owner's repository that the workflow
  and these modules reference directly or transitively (the closure the workspace export already
  computes). Modules used by several selected workflows MUST be in each of their archives, so every
  archive can be imported on its own. Modules referenced only by other modules and workflows the
  workflow calls are not included (see Assumptions).
- **FR-006**: Module files and repository files in an archive MUST be byte-identical to INUBIT's
  export. The workflow's element in `workflow/workflow.xml`, its modules' entries in
  `module/module.xml` and `archive.properties` MUST be unchanged as XML (equal after parsing);
  only the other workflows and modules are removed. `Repository.zip` MUST be rebuilt with only the
  referenced entries, each entry's bytes and metadata (timestamps) unchanged, in the original
  order.
- **FR-007**: The archives MUST keep every secret exactly as INUBIT exported it (no placeholder,
  key material included). The call MUST be refused by the static input checks unless its input
  carries an explicit acknowledgement that the files will contain secrets in clear text. The
  acknowledgement documents intent; it does not replace the client's confirmation (FR-001).
- **FR-008**: The target folder MUST be given in the call as an absolute path without `.`/`..`
  segments. It MUST NOT be, or lie inside, `~/.inubit-mcp` (of any profile), the configured
  `workspace` or `auditDirectory`, or any directory that has a `.git` entry in it or in an
  ancestor — checked both on the path as given and after resolving symbolic links. On Windows, UNC
  and network paths (`\\server\share`, mapped network drives as far as detectable) MUST be refused.
  Violations are refused by the static input checks.
- **FR-009**: An archive MUST never replace an existing file, also not one that appears after the
  collision check; the tool MUST NOT overwrite, change or delete any existing file. If any archive
  name already exists in the target folder, or two selected workflows map to the same file name
  (both compared case-insensitively), the call MUST be refused after the export and before the
  first file or folder is written, and the message MUST name the colliding names. A file that
  appears after that check makes the archive fail (reported in `failed[]` naming the file,
  FR-011). The mechanism is left to the plan.
- **FR-010**: A target path that exists and is not a writable directory MUST be refused with
  `INVALID_INPUT` by the static input checks. A target folder that does not exist MUST be created,
  with its missing parents, only right before the first archive is written, readable, writable and
  searchable by the owner only. Every archive MUST be created readable and writable by the owner
  only. Permissions of an existing folder MUST NOT be changed.
- **FR-011**: An archive MUST be written completely or not at all; no partial archive may remain in
  the target folder. Every per-workflow check (FR-002, FR-004, FR-005, FR-009) MUST be decided
  before the first write (FR-017); any failing check refuses the whole call and nothing is written.
  If writing fails midway, the call MUST return a result (not an error) with `archives[]` (the
  archives written completely) and `failed[]` (`{workflow, code, message}` of the archive that
  failed); the remaining workflows are not written. Errors before the write (validation, StartCLI,
  limits) MUST use the error codes and messages of `export_artifacts`.
- **FR-012**: The result MUST list per archive: workflow, file path, size, modules as
  `{name, pluginType}` and repository paths; plus `failed[]` (FR-011), node, owner, diagram group,
  target folder and a prominent statement that the files contain secrets in clear text and must
  not be shared or committed as they are. It MUST NOT contain any file content or secret value.
- **FR-013**: Every call that passes the input schema (refused, failed, partially completed or
  successful) MUST be recorded in the audit log with target, owner, account (as for the other
  tools), diagram group, workflow names, target folder, file names, `reason` and outcome — without
  file content or secret values. Calls that violate the input schema are not audited, as for the
  other tools. The audit record MUST be written before StartCLI runs (for calls that pass the
  static input checks) and its outcome recorded when the call ends; if it cannot be written, the
  call is refused with `INTERNAL` before anything is exported. A partial result (FR-011) is
  recorded with the outcome "partially completed".
- **FR-014**: On a group marked `production` the tool MUST be refused by the static input checks
  unless that group's configuration explicitly allows archives with clear-text secrets (a dedicated
  opt-in, default off, independent of `write.enabled` and `write.productionOptIn`); the refusal
  names the setting. The tool is always announced, also on a production group without the opt-in.
  On other groups no opt-in is needed. `--check-config` shows the setting per group. (Clarified
  2026-11-05: production only with an explicit opt-in.)
- **FR-015**: The tool MUST support INUBIT 8.1 exactly as `export_artifacts` does and MUST report
  `UNSUPPORTED_VERSION` for other versions as the other StartCLI tools do.
- **FR-016**: `docs/tools.md`, `SECURITY.md` and `CHANGELOG.md` MUST describe the tool, the clear-
  text secrets, the acknowledgement, the client confirmation (FR-001) and the folder rules in the
  same change, and state that on Windows the archives inherit the target folder's ACLs.
- **FR-017**: The tool MUST check at two moments. The **static input checks** — acknowledgement
  (FR-007), path rules (FR-008, FR-010), production opt-in (FR-014), workflow-name syntax and the
  limits of the input (FR-002) — run before the StartCLI export. Every check that depends on the
  export — unknown workflow names and the 50-workflow limit (FR-002), file names (FR-004), missing
  modules (FR-005) and collisions (FR-009) — runs after the export and before the first file or
  folder is written.

### Key Entities

- **Workflow archive**: one file per technical workflow; holds the workflow, the modules its nodes
  reference and its referenced repository files as INUBIT exported them (FR-006); owner-only;
  never in the workspace, the server's directories, a git working tree or a network path.
- **Archive export record**: one audit record per call with names, paths, account, reason and
  outcome only.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An archive written by the tool for a workflow of a real INUBIT 8.1 server is imported
  by the INUBIT Workbench without error, and the imported workflow and its modules equal the
  originals (checked once by the operator before the release).
- **SC-002**: With the fake-server fixture of a diagram group with 10 workflows, building and
  writing the 10 archives takes at most 10 s, excluding the StartCLI runtime.
- **SC-003**: No call writes into the workspace, overwrites an existing file or leaves a partial
  archive, also with two concurrent calls into the same folder; tests cover each refusal and each
  failure point.
- **SC-004**: No result, log line, error message or audit record of the tool contains a secret
  value of the fixtures (checked like the existing secret-leak tests).
- **SC-005**: 100 % of the archives and created folders have owner-only permissions on macOS and
  Linux.

## Clarifications

### Session 2026-11-05

- Q: In which unit? → A: one archive per technical workflow (StartCLI exports the diagram group;
  the server builds the per-workflow archives).
- Q: Secrets? → A: in clear text, exactly as exported, only with an explicit acknowledgement.
- Q: Option of `export_artifacts` or a separate tool? → A: a separate read-only tool.
- Q: Where to? → A: a folder given in the call (absolute, outside the workspace).
- Q: Production stages? → A: refused unless the stage explicitly opts in (FR-014).

### Review round 1 decisions

- Q: Tool annotations? → A: `readOnlyHint: false`, `destructiveHint: false`,
  `idempotentHint: false`, `openWorldHint: true`, so clients ask before calling; the
  acknowledgement documents intent and does not replace the client's confirmation; read-only for
  INUBIT stays (FR-001, FR-007).
- Q: When are calls refused? → A: two moments: static input checks before the StartCLI export;
  export-dependent checks after the export and before the first file or folder is written
  (FR-017).
- Q: What does "byte-identical" cover? → A: module and repository files byte-identical; the
  workflow's element, its modules' index entries and `archive.properties` unchanged as XML;
  `Repository.zip` rebuilt with only the referenced entries, bytes, timestamps and order unchanged
  (FR-006).
- Q: A file that appears after the collision check? → A: never replaced; the archive is reported
  as failed naming the file; mechanism left to the plan (FR-009).
- Q: File-name rule? → A: forbidden characters and control characters → `_`, trailing dots and
  spaces removed, Windows reserved base names prefixed with `_`, over 255 bytes refused,
  collisions compared case-insensitively (FR-004, FR-009).
- Q: Per-workflow refusals and partial results? → A: all per-workflow checks decided before the
  first write, any failure refuses the whole call; a write failure midway returns a result with
  `archives[]` and `failed[]`, audited as "partially completed" (FR-011, FR-013).
- Q: Upper bound? → A: at most 50 workflows per call, also without `workflows` (FR-002).
- Q: Modules bound to the workflow by `WorkflowName` but not nodes of it (connectors)? → A:
  included, so the archive is self-contained; SC-001 confirms that the Workbench accepts them.
- Q: Modules referenced by modules, called workflows? → A: modules referenced by the workflow's
  nodes only; neither modules referenced only by other modules nor called workflows are included
  (FR-005, Assumptions).
- Q: Forbidden target folders? → A: `~/.inubit-mcp` (any profile), the configured `workspace` and
  `auditDirectory`, and any directory with a `.git` entry in it or an ancestor, also after
  resolving symbolic links (FR-008).
- Q: Error code for other versions? → A: `UNSUPPORTED_VERSION` (FR-015).
- Q: Workflow name rules? → A: 1–50 unique names, character rules of diagram group names, matched
  case-sensitively (FR-002).
- Q: When is the folder created? → A: right before the first archive is written; an existing
  target path that is not a writable directory is refused before the export (FR-010).
- Q: Audit details? → A: schema violations not audited; no file content or secret values; record
  written before StartCLI, failure → `INTERNAL` refusal; account recorded as for the other tools;
  optional `reason` (one line, ≤ 500 characters) (FR-002, FR-013).
- Q: "Stage" or "group"? → A: the configured term "group"; the tool is always announced and
  refused on a production group without the opt-in (FR-014).
- Q: Out of scope? → A: see "Out of Scope".
- Q: Windows? → A: UNC and network paths refused; the docs state that archives inherit the
  folder's ACLs (FR-008, FR-016).
- Q: How is SC-002 measured? → A: fake-server fixture of a 10-workflow diagram group, excluding
  StartCLI runtime.
- Q: Module listing in the result? → A: `{name, pluginType}` (FR-012).

## Assumptions

- The Workbench of INUBIT 8.1 imports an archive that holds one workflow of a diagram group in the
  layout of INUBIT's diagram-group export (`archive.properties`, `workflow/workflow.xml`,
  `module/module.xml`, `module/<name>.xml`, `Repository.zip`); `set_active` already relies on such
  single-workflow archives for StartCLI imports. SC-001 confirms it for the Workbench.
- A module references another module only in a way the export data the server reads does not show
  (the server reads module usage from the workflow's nodes only); so a module referenced only by
  another module is not included. Should the Workbench import of such an archive fail, the operator
  archives the workflow that uses that module as a node as well.
- A workflow that calls another workflow (e.g. through a Workflow Connector node) does not name the
  called workflow in the data the server reads (the workflow file's nodes, or `WorkflowName` in
  `module/module.xml`, which is empty for Workflow Connectors); the Workflow Connector module is
  included as a node of the calling workflow, the called workflow is not included and not listed
  in the result. The operator archives called workflows separately.
- A file name per workflow is enough to tell the archives apart within one call; the operator
  chooses a new or empty folder per call when they want to keep earlier archives.
- The limits of `export_artifacts` (one StartCLI export, 128 MiB archive) apply unchanged.
- Windows file permissions are left to the folder's inherited ACLs (as for the other files the
  server writes on Windows), documented per FR-016; SC-005 covers macOS and Linux.
- Only technical workflows are archived, as in `export_artifacts`.

## Out of Scope

- INUBIT versions other than 8.1.
- System diagrams and other non-technical diagrams.
- Archives of single modules or of the repository alone.
- Versions, history or tags of workflows.
- Importing these archives (into INUBIT or the Workbench) through the server.
- Encryption or redaction options for the archives.
- Tightening Windows ACLs of the target folder or the archives.
