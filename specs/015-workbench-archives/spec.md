# Feature Specification: Workbench-Importable Workflow Archives

**Feature Branch**: `015-workbench-archives`

**Created**: 2026-11-05

**Status**: Draft

**Input**: GitHub issue #56 ("Export workflows as Workbench-importable archives") and the
operator's decisions of 2026-11-05: a new read-only tool, one archive per technical workflow,
secrets in clear text only with an explicit acknowledgement, a target folder given in the call.

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

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Archive the workflows of a diagram group (Priority: P1)

The operator asks for Workbench archives of the workflows of one diagram group on a server and
names a folder on their machine. They get one archive per technical workflow in that folder and a
result that lists each file and says plainly that the files contain secrets in clear text.

**Why this priority**: it is the whole value of the feature: a Workbench-importable copy without
manual Workbench exports.

**Independent Test**: export a diagram group with three workflows from a fake INUBIT server into an
empty temporary folder; three archives appear, each holds exactly its workflow, the modules that
workflow uses and the repository files it references, with every value as the server exported it;
the files and the folders the call created are readable by the owner only.

**Acceptance Scenarios**:

1. **Given** diagram group `GRP-01` with workflows `Workflow-0001`, `Workflow-0002` and
   `Workflow-0003` on `dev/node1` and an empty folder `/tmp/archives`, **When** the operator calls
   the tool with that group, that folder and the clear-text acknowledgement, **Then** the folder
   holds one archive per workflow, named after the workflow, and the result lists the three files
   with their sizes and the modules and repository files each contains (FR-001, FR-004, FR-006,
   FR-012).
2. **Given** `Workflow-0001` uses `Module-0002` and `Module-0005`, and `Module-0005` is also used by
   `Workflow-0002`, **When** the archives are written, **Then** the archive of `Workflow-0001`
   holds both modules, the archive of `Workflow-0002` holds `Module-0005` too, and neither holds a
   module its workflow does not use (FR-005).
3. **Given** a workflow whose modules reference repository files (directly or through other
   repository files), **When** its archive is written, **Then** the archive holds exactly those
   repository files of the owner's repository, and no other repository file (FR-005).
4. **Given** a module with a password and a stylesheet with a saved test message, **When** its
   workflow is archived, **Then** the archive holds the values exactly as INUBIT exported them
   (no placeholder), and the result, the log and the audit record contain neither value
   (FR-007, FR-013).
5. **Given** the operator names only `Workflow-0002` of the group, **When** the tool runs,
   **Then** only that workflow's archive is written (FR-002).

---

### User Story 2 - Clear-text secrets only on purpose (Priority: P1)

The archives contain the secrets of the exported workflows in clear text. The tool must make that
a deliberate choice and keep the files away from places where they would be versioned or shared by
accident.

**Why this priority**: an archive with clear-text secrets in the wrong place is a leak; this story
makes the feature safe enough to exist.

**Independent Test**: call the tool without the acknowledgement, with a relative path, with a path
inside the workspace and with a folder that already holds a file of the same name; each call is
refused before StartCLI runs and nothing is written.

**Acceptance Scenarios**:

1. **Given** a call without the clear-text acknowledgement, **When** the tool runs, **Then** it is
   refused before anything is exported, and the message names the missing acknowledgement and why
   it is needed (FR-007).
2. **Given** a target folder that is relative, contains `..` segments, or lies inside the workspace
   (also through a symbolic link), **When** the tool runs, **Then** it is refused before anything
   is exported (FR-008).
3. **Given** a target folder that already holds a file with the name an archive would get,
   **When** the tool runs, **Then** it is refused before anything is exported, the message names
   the colliding file(s), and no existing file is changed or deleted (FR-009).
4. **Given** a target folder that does not exist yet, **When** the tool runs, **Then** the folder
   (and missing parents) is created readable by the owner only, and the archives inside are
   readable by the owner only (FR-010).
5. **Given** any successful call, **When** the result is shown, **Then** it states that the files
   contain secrets in clear text and must not be shared or committed as they are (FR-012).

---

### User Story 3 - Traceable and bounded (Priority: P2)

Every archive export is recorded, and a failure leaves no half-written archive behind.

**Why this priority**: the operator must be able to tell later who exported which workflows with
clear-text secrets, and where to.

**Independent Test**: run a successful call and calls that fail at the export, while building an
archive and while writing; the audit log has one record per call with the target, the workflows and
the folder; the folder never holds a partial archive.

**Acceptance Scenarios**:

1. **Given** a successful call, **When** the audit log is read, **Then** it holds one record with
   the target, owner, diagram group, workflow names, target folder and file names — never content
   or secret values (FR-013).
2. **Given** StartCLI fails or times out, **When** the tool returns, **Then** the error is the same
   as for `export_artifacts` (code, message, next step), nothing is written, and the failure is
   audited (FR-011, FR-013).
3. **Given** writing the second of three archives fails (e.g. the disk is full), **When** the tool
   returns, **Then** no partial archive is left in the folder, the result names the archives that
   were written completely and the one that failed (FR-011).

### Edge Cases

- A diagram group without technical workflows: the call succeeds with an empty list and writes
  nothing (no empty folder is created).
- A named workflow that is not in the diagram group: refused before anything is written, naming the
  unknown workflow(s).
- A workflow name with characters that are not allowed in a file name on macOS, Linux or Windows:
  the file name replaces them deterministically; two workflows that map to the same file name are
  refused as a collision (FR-004, FR-009).
- A workflow that references a module the group archive does not contain (INUBIT exported it
  inconsistently): refused for that workflow with the missing module's name; the other archives
  are written.
- Key material in the owner's repository that a workflow references: it is included unchanged,
  like every other secret (FR-007).
- The export of the diagram group is larger than the export limit of `export_artifacts`: refused
  with the same message.
- A production stage without the opt-in: refused before anything is exported, naming the setting
  (FR-014).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A new read-only MCP tool MUST export the technical workflows of one diagram group of
  one target (a group's first node, or one node — as `export_artifacts`) and owner (default
  `inventory.owner`) as Workbench-importable archives. It MUST NOT change anything on INUBIT and
  MUST NOT touch the workspace or its history.
- **FR-002**: The input MUST name exactly one diagram group (same name rules as `export_artifacts`)
  and MAY name 1–50 workflows of it; without workflow names every technical workflow of the group
  is archived.
- **FR-003**: The tool MUST obtain the group's content with one StartCLI diagram-group export, as
  `export_artifacts` does, and MUST keep the raw export in memory and in the private temporary
  directory of the export only; nothing of it may reach the workspace.
- **FR-004**: The tool MUST write one archive per selected workflow, named after the workflow
  (`<workflow name>.diagram.zip`, characters not allowed in file names on macOS, Linux and Windows
  replaced by `_`), in the layout of a single-workflow export of the INUBIT 8.1 Workbench (the
  layout INUBIT's own diagram-group export uses, restricted to that workflow).
- **FR-005**: Each archive MUST contain exactly: the workflow; every module its nodes run; and the
  files of the owner's repository it references directly or transitively (the closure the
  workspace export already computes). Modules used by several selected workflows MUST be in each of
  their archives, so every archive can be imported on its own.
- **FR-006**: Every workflow, module and repository file in an archive MUST be byte-identical to
  INUBIT's export, apart from the entries that restricting the archive to one workflow requires
  (the archive's own index and metadata).
- **FR-007**: The archives MUST keep every secret exactly as INUBIT exported it (no placeholder,
  key material included). The call MUST be refused before any export unless its input carries an
  explicit acknowledgement that the files will contain secrets in clear text.
- **FR-008**: The target folder MUST be given in the call as an absolute path without `.`/`..`
  segments. It MUST NOT be, or lie inside, the workspace or the server's state directory, also
  after resolving symbolic links; otherwise the call is refused before any export.
- **FR-009**: The tool MUST NOT overwrite, change or delete any existing file. If any archive name
  already exists in the target folder, or two selected workflows map to the same file name, the
  call MUST be refused before any export and the message MUST name the colliding names.
- **FR-010**: A target folder that does not exist MUST be created with its missing parents,
  readable, writable and searchable by the owner only. Every archive MUST be created readable and
  writable by the owner only. Permissions of an existing folder MUST NOT be changed.
- **FR-011**: An archive MUST be written completely or not at all (written to a temporary name in
  the target folder, then renamed). If a write fails, the temporary file MUST be removed, the
  remaining workflows are not written, and the result MUST name the complete archives and the
  failed one. Errors before the write (validation, StartCLI, limits) MUST use the error codes and
  messages of `export_artifacts`.
- **FR-012**: The result MUST list per archive: workflow, file path, size, module names and
  repository paths; plus node, owner, diagram group, target folder and a prominent statement that
  the files contain secrets in clear text and must not be shared or committed as they are. It MUST
  NOT contain any file content or secret value.
- **FR-013**: Every call (refused, failed or successful) MUST be recorded in the audit log with
  target, owner, diagram group, workflow names, target folder and file names, never content or
  secret values; the log MUST name the call and its outcome without values.
- **FR-014**: On a stage marked `production` the tool MUST be refused before any export unless that
  stage's configuration explicitly allows archives with clear-text secrets (a dedicated opt-in,
  default off, independent of `write.enabled` and `write.productionOptIn`); the refusal names the
  setting. On other stages no opt-in is needed. `--check-config` shows the setting per stage.
  (Clarified 2026-11-05: production only with an explicit opt-in.)
- **FR-015**: The tool MUST support INUBIT 8.1 exactly as `export_artifacts` does and MUST report
  `UNSUPPORTED` for other versions as the other StartCLI tools do.
- **FR-016**: `docs/tools.md`, `SECURITY.md` and `CHANGELOG.md` MUST describe the tool, the clear-
  text secrets, the acknowledgement and the folder rules in the same change.

### Key Entities

- **Workflow archive**: one file per technical workflow; holds the workflow, its modules and its
  referenced repository files exactly as INUBIT exported them; owner-only; never in the workspace.
- **Archive export record**: one audit record per call with names and paths only.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An archive written by the tool for a workflow of a real INUBIT 8.1 server is imported
  by the INUBIT Workbench without error, and the imported workflow and its modules equal the
  originals (checked once by the operator before the release).
- **SC-002**: Exporting the archives of a diagram group with 10 workflows takes at most 10 s more
  than `export_artifacts` of the same group.
- **SC-003**: No call writes into the workspace, overwrites an existing file or leaves a partial
  archive; tests cover each refusal and each failure point.
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

## Assumptions

- The Workbench of INUBIT 8.1 imports an archive that holds one workflow of a diagram group in the
  layout of INUBIT's diagram-group export (`archive.properties`, `workflow/workflow.xml`,
  `module/module.xml`, `module/<name>.xml`, `Repository.zip`); `set_active` already relies on such
  single-workflow archives for StartCLI imports. SC-001 confirms it for the Workbench.
- A file name per workflow is enough to tell the archives apart within one call; the operator
  chooses a new or empty folder per call when they want to keep earlier archives.
- The limits of `export_artifacts` (one StartCLI export, 128 MiB archive) apply unchanged.
- Windows file permissions are left to the folder's inherited ACLs (as for the other files the
  server writes on Windows); SC-005 covers macOS and Linux.
- Only technical workflows are archived, as in `export_artifacts`.
