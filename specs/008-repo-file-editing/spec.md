# Feature Specification: Editing INUBIT Repository Files on a Development Stage

**Feature Branch**: `008-repo-file-editing`

**Created**: 2026-10-13

**Status**: Draft

**Input**: User description (translated from German): "I want the MCP server to be able to edit
repository files as well. The permissions shall be configurable."

## Context & Findings *(verified before writing this spec)*

- **Repository files are already in the workspace, but read-only in effect.** `export_artifacts`
  (feature 003) writes the INUBIT repository files that the exported modules reference
  (`inubitrepository:` in `xsl:import`/`xs:import`) to `<group>/<owner>/repository/<path>`, and
  `check_artifacts` resolves stylesheet and schema imports from there. Repository files that no
  exported module references are not exported.
- **The development stage refuses repository changes.** `import_artifacts` (feature 004) refuses
  any change below `repository/` with `INVALID_INPUT`; this was a deliberate scope cut of feature
  004 (research D-24). A changed shared schema or imported stylesheet can therefore only reach
  INUBIT through a person in the Workbench.
- **Deployments already carry repository files.** `deploy_release` (feature 005, FR-015a) deploys
  the new and changed repository files a release references, never key material, never outside the
  owner's repository root (`OUTSIDE_OWNER_REPOSITORY`).
- **How INUBIT takes repository files is known** (spike, section 10): repository files can only be
  written in INUBIT's repository import mode (a `Repository.zip` inside a workflow or module import
  is ignored); entries are taken relative to the owner's repository root; every import creates a new
  version, also with unchanged content; an import cannot delete a file; a missing path is a
  recognizable "not found"; a group-scoped tag also tags the repository files the group references.

## Clarifications

### Session 2026-10-13

- Q: Does "repository files" mean the INUBIT repository or the files of the local git workspace?
  → A: The INUBIT repository of a development node (schemas, stylesheets, WSDLs below
  `/Root/<owner>/…`).
- Q: How fine-grained are the permissions? → A: One switch per development node:
  `repository: read-only` (default) or `repository: write`. With `write`, every repository path
  below the addressed owner's repository root may be changed and new files may be created there; no
  path patterns, operations or file-type rules.
- Q: Do the repository permissions also restrict deployments along the stage chain? → A: No. They
  govern development writes only; `deploy_release` keeps the behaviour of feature 005.
- Q: Should every write to an INUBIT repository always need a server-issued confirmation code, even
  on a node with `development.confirmation: CLIENT`? → A: Yes. Any call whose change set contains a
  repository file always returns a preview and a server-issued code first, whatever
  `development.confirmation` says; workflows and modules in the same call follow it too.
- Q: On a node with `repository: read-only`, should a diagram-group import whose change set contains
  a changed repository file be refused as a whole, or import workflows and modules without the
  repository file? → A: Refuse the whole call with `REPOSITORY_WRITE_DENIED`, naming the repository
  paths; nothing is imported partially and nothing is moved to `notImported`.
- Q: Is a repository file without a verified base from the node refused, or treated as new? →
  A: Treated as new (`create`, or `modify` + `existing` with the preview diff). Only a file whose base
  exists but whose exported metadata is missing or unreadable is refused with `PRECONDITION_FAILED`
  (from plan review, research D-4).
- Q: How are the open points of spec review round 3 settled? → A: (1) An explicit `WRITE` on a
  non-production group without a development-stage node is valid but warned about at startup and in
  `--check-config`. (2) Repository paths differing only in letter case are refused with
  `INVALID_INPUT` by every export. (3) A file the node already holds (`modify`/`existing`) keeps the
  node's content type and description; only a file to be created needs a supported extension.
  (4) If any of several export paths is missing on the node, the whole export fails with `NOT_FOUND`
  naming them and writes nothing. (5) Acceptance scenarios were added for FR-008, FR-010, FR-012
  (reference list), FR-015 (rollback order) and FR-019 (audit).
- Q: Should a diagram-group import also include changed repository files that the group's modules
  reach only through other repository files (e.g. a schema importing a schema)? → A: Yes. Changed
  repository files reachable through any chain of repository references are included
  (transitively), within the owner's repository root.
- Q: Does a module import include the changed repository files its modules reference? → A: Yes,
  like a diagram-group import: referenced repository files (also through reference chains) go into
  the same call, under the same switch check and server-issued confirmation.
- Q: Which modules of a diagram-group import determine the included repository files? → A: Every
  module of the diagram group in the workspace, changed or not; a diagram-group import with only
  changed repository files is valid and imports just them (and tags the group if asked).

## Overview

The AI assistant can already edit workflows and modules in the workspace and bring them back into a
development stage. Shared repository files — XML schemas, stylesheets imported by several mappings,
WSDLs — are the one kind of artifact it can read and check but not bring back. This feature closes
that gap, behind a switch an administrator sets per development stage:

- **Edit and create**: the assistant edits repository files in the workspace or creates new ones
  next to them, and can pull further repository files or folders into the workspace by their path.
- **Import**: new and changed repository files go into one development node — on their own or
  together with the diagram group whose modules use them — with the same preview, confirmation,
  conflict detection, backup, verification and rollback as every other development write.
- **Permission**: nothing is written to an INUBIT repository unless the profile switches repository
  writes on for that development node. Every repository write on a node where the switch is off is
  refused before anything is sent.

**Actors**

- **Developer/Operator**: asks the assistant to change a shared schema or stylesheet, reviews the
  preview and approves the write.
- **AI assistant**: edits repository files in the workspace, checks them, imports them.
- **Administrator**: maintains the profile configuration, decides on which development nodes
  repository writes are allowed.

**Terminology**: profile, group, node, owner, workspace, development stage, confirmation code and
backup as in features 002–005. A **repository path** is the path of a file or folder in the INUBIT
repository, for example `/Root/<owner>/schemas/order.xsd`; the owner's **repository root** is
`/Root/<owner>`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Bring an edited repository file into the development stage (Priority: P1)

The developer asks the assistant to add an optional element to a shared order schema. The assistant
edits the schema below `repository/` in the workspace, runs `check_artifacts`, and imports it into
the development node. The preview lists the repository file as `modify`; after the developer
approves, the server backs up the current version, imports the file, verifies it byte for byte by
re-export and records the verified state in the workspace history.

**Why this priority**: this is the capability that is missing today; without it every schema or
shared-stylesheet change needs a manual Workbench step.

**Independent Test**: on a test development node with repository writes switched on, change one
exported repository file of a test owner, import it, and confirm that the node holds the new content
as a new version, the workspace history has one entry for it, and nothing else on the node changed.

**Acceptance Scenarios**:

1. **Given** a development node with `repository: write` and an edited workspace file
   `repository/schemas/order.xsd`, **When** the assistant imports the repository path
   `schemas/order.xsd`, **Then** the server returns a preview listing the file as `modify` and a
   confirmation code, and sends nothing.
2. **Given** that preview, **When** the call is repeated with the code, **Then** the file is backed
   up, imported, verified by re-export, the result names the backup reference, and the workspace
   history records the verified state.
3. **Given** an edited repository file referenced by a module of diagram group `GRP-01` and changed
   workflows of that group, **When** the assistant imports `GRP-01`, **Then** the repository file is
   part of the same preview and import, imported before the modules and workflows, and the whole
   call is rolled back together on a failure. A changed schema that only that repository file
   imports (not a module) is included in the same way, and so is a changed repository file that only
   an unchanged module of `GRP-01` references.
4. **Given** a changed module imported on its own (module scope) that references a changed
   stylesheet in the repository, **When** it is imported, **Then** the stylesheet is part of the same
   preview, confirmation and import, imported before the module.
5. **Given** diagram group `GRP-01` with no changed workflow or module but a changed schema that one
   of its modules references, **When** the assistant imports `GRP-01` with a `tag`, **Then** only the
   schema is imported and the group is tagged.
6. **Given** a new workspace file `repository/schemas/invoice.xsd`, **When** it is imported, **Then**
   the preview lists it as `create`, and after confirmation the node holds it at
   `/Root/<owner>/schemas/invoice.xsd`.
7. **Given** a development node with `development.confirmation: CLIENT` and `repository: write`,
   **When** the assistant imports a diagram group whose change set contains a changed repository
   file, **Then** the first call still returns only a preview with a server-issued code and sends
   nothing; the import runs only when the code comes back.
8. **Given** the repository file changed on the node since its last verified state, **When** it is imported,
   **Then** the call is refused with `CONFLICT`, nothing is sent, and the differences are written to
   a report file in the workspace.
9. **Given** a new workspace schema that imports a schema missing from the workspace, **When** the
   assistant runs `check_artifacts` on it, **Then** the missing reference is reported as an error
   before any import.
10. **Given** changed files below `schemas/` and `stylesheets/`, **When** the assistant imports the
    repository path `schemas`, **Then** only the files below `schemas/` are in the preview and the
    import, and the changed stylesheets are listed in `notImported`.
11. **Given** a changed schema that 25 workspace artifacts outside the scope reference, **When** the
    preview is returned, **Then** it names 20 of them with a truncation marker, and a report file in
    the workspace lists all 25.
12. **Given** an import of a changed schema together with changed modules, where the module import
    fails after the schema was imported, **When** the server rolls back, **Then** the schema gets its
    previous content back before the modules are restored, both are verified, and the result reports
    the rollback.
13. **Given** a preview and an executed import of repository files, and a failed one, **When** the
    audit log is read, **Then** each call has its records (previewed, pending, executed or failed),
    each listing every repository path with its operation and content hash, and no content.

---

### User Story 2 - Switch repository writes on for a development stage (Priority: P1)

The administrator switches repository writes on for the development node of group `dev` and leaves
every other node read-only. The configuration check and `list_nodes` show the switch per node.
Every repository write on a node where it is off is refused and audited.

**Why this priority**: repository files are shared by many diagram groups; a change hits every
workflow that uses the file. The write capability must not exist without an explicit switch, so
this story ships together with Story 1.

**Independent Test**: configure two development nodes, one with `repository: write` and one
without; attempt the same repository import on both; confirm that only the node with the switch
gets a preview, the other refuses before anything is sent, and the refusal is audited.

**Acceptance Scenarios**:

1. **Given** a development node without the switch (default `repository: read-only`), **When** the
   assistant imports a changed repository file, **Then** the call is refused with
   `REPOSITORY_WRITE_DENIED`, nothing is sent to the node, and the refusal is audited.
2. **Given** a diagram-group import on a read-only node whose change set contains changed workflows
   and one changed referenced repository file, **When** it is imported, **Then** the whole call is
   refused with `REPOSITORY_WRITE_DENIED` naming the repository paths (nothing is imported
   partially).
3. **Given** `repository: write` set explicitly on a node that is not a development stage, or on a
   production group or one of its nodes, or an unknown value, **When** the server starts or
   `--check-config` runs, **Then** it reports a configuration error naming the setting.
4. **Given** `repository: write` in `defaults` and a profile with one development node and several
   other nodes, **When** the configuration is loaded, **Then** it is valid, the development node is
   `repository: write`, and every other node is `repository: read-only`.
5. **Given** a valid configuration, **When** `--check-config` runs or the assistant calls
   `list_nodes`, **Then** each development node shows `repository: write` or
   `repository: read-only`.
6. **Given** `repository: write` on the group `dev` and `repository: read-only` on its node, **When**
   the configuration is loaded, **Then** the node's value wins.
7. **Given** `repository: write` set explicitly on a non-production group without a development-stage
   node, **When** the server starts or `--check-config` runs, **Then** the configuration is valid,
   every node of the group is `repository: read-only`, and a warning says that the setting has no
   effect there.

---

### User Story 3 - Pull further repository files into the workspace (Priority: P2)

The developer wants to change a shared stylesheet that no exported module references yet, or
create a new schema next to existing ones. The assistant exports the repository folder by its path
into the workspace, edits or adds files, and imports them as in Story 1.

**Why this priority**: today only referenced repository files reach the workspace; without this
story, unreferenced files can be neither edited nor safely extended.

**Independent Test**: export a repository folder of a test owner that no exported module
references; confirm that its files appear below `repository/`, key material is not written, and the
export is one history entry.

**Acceptance Scenarios**:

1. **Given** a node with the CLI, **When** the assistant exports the repository path
   `stylesheets/common` of the owner, **Then** every file below it is written to
   `<group>/<owner>/repository/stylesheets/common/…` and committed as one history entry.
2. **Given** that folder contains a keystore, **When** it is exported, **Then** the keystore is not
   written and the result warns about it, as for every export.
3. **Given** a list of paths of which at least one does not exist on the node, **When** it is
   exported, **Then** the call returns `NOT_FOUND` naming the missing paths, nothing is written,
   and the workspace is unchanged.
4. **Given** a path outside the owner's repository root, **When** it is exported, **Then** the call
   is refused with `INVALID_INPUT`.
5. **Given** a folder holding two files whose paths differ only in letter case, **When** it is
   exported, **Then** the call is refused with `INVALID_INPUT` naming both paths, and nothing is
   written.

---

### User Story 4 - Undo a repository change (Priority: P2)

A changed schema breaks a mapping in another diagram group. The developer asks the assistant to
undo the import; `restore_backup` with the backup reference re-imports the previous content of the
repository files that call changed.

**Why this priority**: shared files have a wide blast radius; a fast, verified way back is part of
making the write acceptable.

**Independent Test**: import a changed repository file, restore its backup, and confirm that the
node's content equals the content before the import byte for byte.

**Acceptance Scenarios**:

1. **Given** a successful import that modified two repository files, **When** its backup is
   restored, **Then** both files get their previous content back (as new versions), verified by
   re-export.
2. **Given** an import that created a repository file, **When** its backup is restored, **Then** the
   created file stays on the node (an import cannot delete it) and is listed in `createdNotRemoved`.
3. **Given** a node whose switch was turned to `repository: read-only` since an import that changed
   workflows and repository files, **When** the backup of that import is restored, **Then** the whole
   restore is refused with `REPOSITORY_WRITE_DENIED` naming the repository paths, and nothing is
   restored.
4. **Given** a repository file the import changed was changed again on the node afterwards, **When**
   its backup is restored, **Then** the restore is refused with `CONFLICT` and nothing is sent.
5. **Given** the backup of a `deploy_release` call on a node of a target group that is not a
   development stage, **When** it is restored, **Then** its repository files are restored as in
   feature 005, without regard to the repository switch.

---

### Edge Cases

- **Shared use**: a changed repository file that workspace artifacts outside the import scope also
  reference is imported, and the preview and result warn and list those artifacts; references on
  the node that the workspace does not know cannot be listed, and the warning says so.
- **Reference chains**: a changed schema that only another schema imports is part of a
  diagram-group or module import like a directly referenced one; cycles in the chain are followed once; a
  reference into another owner's root is not followed and gives a warning.
- **Deletion**: a repository file deleted in the workspace within the import scope or among the
  call's referenced repository files is refused with `INVALID_INPUT` ("deleting repository files is
  not supported"); a deletion elsewhere is listed in `notImported`; nothing is ever deleted in
  INUBIT.
- **Key material and secrets**: a changed or new repository file that is key material (keystore,
  certificate, private key, as recognised by the export rules of features 003 and 005) or contains a `${secret:…}` placeholder is refused with `INVALID_INPUT`;
  key material is never written to an INUBIT repository by this server.
- **Unchanged files**: repository files whose content equals the node's are never sent (every import
  creates a new version).
- **New, but on the node already**: a file new in the workspace that the node holds with identical
  content is listed as `identical` and not sent; with other content it is updated as a new version,
  listed in `modify` and additionally in `existing`.
- **Path tricks**: `..` segments, percent-encoded separators and doubled slashes are normalized
  before the path is checked; a path that leaves the owner's repository root after normalization is
  refused with `INVALID_INPUT`.
- **Another owner**: a repository file below another owner's root (`/Root/<other>/…`) is never
  written, even with `repository: write`.
- **Size**: more than 50 repository files to send in one call (`identical` files do not count), or a
  single file over 10 MiB, is refused with `INVALID_INPUT` before anything is sent; a repository
  export of more than 500 files or 100 MiB is refused before anything is written.
- **Unknown file type**: a repository file to be created (absent on the node) whose extension has no
  documented content type is refused with `INVALID_INPUT`, naming the supported extensions; a file
  the node already holds keeps the node's content type and description.
- **Case collision**: repository paths that differ only in letter case are refused with
  `INVALID_INPUT` by every export that writes them, naming the paths.
- **No verified base**: a repository file without any verified state from that node in the
  workspace history (export or development call) counts as new. It is listed as `create`, or as
  `modify` and `existing` if the node already holds other content, and the preview's diff shows what
  would be overwritten. A file whose verified state exists but whose exported metadata in the
  workspace is missing or unreadable is refused with `PRECONDITION_FAILED` ("export the path first"),
  because it cannot be sent faithfully.
- **Reason**: INUBIT ignores version comments of repository imports, so the call's reason is kept in
  the audit and the workspace history only, not in the repository file's version comment.
- **Tag**: an import of repository files alone with a `tag` is refused (`INVALID_INPUT`), as for
  modules; a diagram-group import with a tag tags the referenced repository files through INUBIT's
  group tag. The tag verification stays as in feature 004 (workflows and modules); which repository
  files INUBIT's group tag covered is reported in the result's `tag` as information, not verified.
- **Configuration change**: the switch is read at startup; a changed configuration takes effect
  after the MCP client restarts the server.
- **Only identical repository files**: a change set whose repository files are all `identical`
  sends no repository file and keeps the node's `development.confirmation`.
- **Lock**: a repository import is a workspace operation; a second workspace operation running at the
  same time is refused with `PRECONDITION_FAILED`.

## Requirements *(mandatory)*

### Functional Requirements

**Permission**

- **FR-001**: Writing to the INUBIT repository of a node through the development tools
  (`import_artifacts`, `restore_backup` of a development call) MUST be disabled unless the
  configuration switches it on for that node with `repository: write`. The default MUST be
  `repository: read-only`, also on a development stage.
- **FR-002**: The switch MUST be configurable on `defaults`, on a group and on a node (node wins over
  group, group over `defaults`) and MUST accept exactly two values: the configuration key is
  `development.repository` with `READ_ONLY` (shown as `repository: read-only`) and `WRITE` (shown as
  `repository: write`), in line with the other upper-case settings (plan research D-1). With
  `write`, the server MUST allow changing existing files and creating new files anywhere below the
  addressed owner's repository root of that node.
- **FR-003**: The switch MUST take effect only on development-stage nodes. A `write` that a node
  which is not a development stage merely inherits (from `defaults` or its group) MUST be treated as
  `read-only` without an error. A `write` set explicitly on a node that is not a development stage,
  or anywhere on or inside a production group, and an unknown value MUST be a startup error naming
  the setting. A `write` set explicitly on a non-production group without a development-stage node
  MUST produce a warning at startup and in `--check-config` that the setting has no effect there.
- **FR-004**: Before anything is sent to a node, the server MUST check the node's effective switch;
  if the change set contains at least one repository file to send (`create` or `modify`) and the
  switch is `read-only`, the whole call MUST
  be refused with `REPOSITORY_WRITE_DENIED`, listing the repository paths of the change set. This
  also applies to a diagram-group or module import whose change set contains a referenced
  repository file (FR-011): the server MUST NOT import its workflows and modules without that file, and MUST NOT move
  the file to `notImported` instead; the refusal names the repository paths so the developer can
  revert them in the workspace or have the switch turned on.
- **FR-005**: Repository paths in tool inputs MUST be given relative to the addressed owner's
  repository root (for example `schemas/order.xsd`); an absolute path (`/Root/…`), an empty path and
  the root itself MUST be refused with `INVALID_INPUT`. Paths MUST be normalized before they are
  checked (segments, encoding), and a path that leaves the owner's repository root after
  normalization MUST be refused with `INVALID_INPUT` regardless of the switch.
- **FR-006**: `--check-config` and `list_nodes` MUST show each development node's effective value,
  `repository: write` or `repository: read-only`.
- **FR-007**: The switch MUST govern development writes (`import_artifacts`, `restore_backup`) only;
  deployments along the stage chain MUST keep the behaviour of feature 005.

**Workspace and export**

- **FR-008**: The assistant MUST be able to edit exported repository files and create new files
  below `<group>/<owner>/repository/` in the workspace; `check_artifacts` MUST check them as it
  checks exported ones.
- **FR-009**: `export_artifacts` MUST accept 1–20 repository paths (files or folders, relative to
  the owner's repository root, FR-005) as an export scope of its own, write every file below them
  (folders recursively) into the workspace and commit one history entry, with the same secret and
  key-material rules and the same recording of uncommitted local changes as every export (feature
  003). One repository export MUST write at most 500 files and 100 MiB; a larger scope MUST be
  refused with `INVALID_INPUT` before anything is written, naming the counted size and asking for
  narrower paths. If any given path does not exist on the node, the whole call MUST fail with
  `NOT_FOUND` naming the missing paths and write nothing. Repository paths that differ only in
  letter case MUST be refused with `INVALID_INPUT` naming them, before anything is written, because
  they would overwrite each other on a case-insensitive file system; this applies to every export
  that writes repository files. Exporting MUST NOT need the switch.

**Import**

- **FR-010**: `import_artifacts` MUST accept repository paths (1–50 files or folders) as a scope of
  its own and import the new and changed repository files below them; changed repository files
  outside the scope MUST stay in the workspace and be listed in `notImported`.
- **FR-011**: A diagram-group import MUST include the new and changed repository files referenced
  by any module of the diagram group in the workspace (changed or not), and a module import MUST
  include those referenced by its modules — directly or through any chain of references from one
  repository file to another (transitively, as `check_artifacts` resolves them). They MUST be
  imported before the modules and workflows, and the whole call MUST be one unit for the switch
  check (FR-004), the confirmation (FR-012), backup, verification and rollback. References that leave
  the owner's repository root MUST NOT be followed and MUST be reported as a warning. Other changed
  repository files MUST be listed in `notImported`.
- **FR-011a**: A diagram-group import whose change set contains only repository files (no changed
  workflow or module) MUST be valid: it imports just those files and, if a `tag` is given, tags the
  diagram group as in feature 004.
- **FR-012**: The preview MUST list the repository files of the change set in the lists `create`,
  `modify` and `identical`; a file that is new in the workspace but already on the node with other
  content is listed in `modify` and additionally in `existing` (as for workflows and modules). For
  each file it MUST name up to 20 workspace artifacts outside the scope that reference it, with a
  truncation marker and the full list in a report file in the workspace. For every repository file
  in `modify` (including the `existing` ones), the preview MUST write a content diff between the
  node's content and the workspace content to a report file in the workspace and return its path.
  The confirmation code MUST be bound to these files and their content. A call whose change set contains at least one
  repository file to send (`create` or `modify`) MUST always first return this preview with a
  server-issued confirmation code, also on a node with `development.confirmation: CLIENT`; the whole
  call, including its workflows and modules, then runs only with that code. Calls without repository
  files to send keep the node's `development.confirmation`.
- **FR-013**: A repository file whose content changed on the node since its last verified state in
  the workspace history (export or development call on that node) MUST make the call fail with
  `CONFLICT` before anything is sent, with the differences written to a report file in the
  workspace. Only content counts; version, version comment, modification date and description are
  no conflict.
- **FR-014**: Only new and changed repository files MUST be sent; files equal to the node's content
  MUST NOT be sent.
- **FR-015**: Before writing, the server MUST back up the current content of every repository file
  it will modify; after writing it MUST verify every sent file byte for byte by re-export (as
  deployments do, feature 005), and on any failure restore the previous content from the backup,
  verify the rollback and report files it created and could not remove in `createdNotRemoved`.
- **FR-015a**: The call's `reason` MUST be recorded for repository files in the audit and in the
  workspace history entry. INUBIT's repository import ignores the version comment of the archive
  (spike, section 10), so the reason is not written to, and not verified in, the version comment of
  repository files. The history entry carries it as a `Reason: <reason>` trailer next to
  `Server-State` (decided 2026-10-16; for every history entry of a call that has a reason, not only
  those with repository files, so the format stays uniform).
- **FR-016**: After a verified import, the workspace MUST record the verified state as one history
  entry, as for workflows and modules.
- **FR-017**: The server MUST refuse with `INVALID_INPUT`: a deleted repository file within the
  import scope or among the referenced repository files of the call (deletions elsewhere are listed
  in `notImported`); a changed or new file that is key material as recognised by the export rules of
  features 003 and 005; a file containing a `${secret:…}` placeholder; more than 50 repository files
  to send (`create` or `modify`; `identical` files do not count); a file over 10 MiB; a file to be
  created (absent on the node) whose extension has no documented content type — a file the node
  already holds keeps the node's content type and is not refused for its extension; and a
  repository-only import with a tag. It MUST
  refuse with `PRECONDITION_FAILED` a changed file whose verified base from that node exists but
  whose exported metadata in the workspace is missing or unreadable. A file without any verified
  base from that node counts as new (FR-012: `create`, or `modify` and `existing`).
- **FR-018**: `restore_backup` of a development call MUST re-import the previous content of the
  repository files that call changed, under the same switch check (FR-004) and the same
  always-required server-issued confirmation (FR-012), with the conflict check against the state the
  referenced call left, verification and rollback on failure; files that call created MUST stay and
  be listed in `createdNotRemoved`. Restoring a `deploy_release` backup MUST keep the behaviour of
  feature 005, regardless of the switch.

**Audit and safety**

- **FR-019**: Every repository import and restore — refused, previewed, executed or failed — MUST be
  audited with the repository paths, their content hashes and the operation per file, never the
  content.
- **FR-020**: Nothing in an INUBIT repository MUST ever be deleted by the server, and no repository
  file below another owner's root MUST ever be written.
- **FR-021**: User documentation (configuration, tool reference) MUST describe the switch, the new
  scopes, error codes and the shared-use risk in the same change.

### Key Entities

- **Repository file**: a file in the INUBIT repository of a node, identified by its repository path;
  has content, a content type, a description and a version history on the node; mirrored in the
  workspace below `<group>/<owner>/repository/` at its full repository path (feature 003 layout,
  e.g. `<group>/<owner>/repository/Root/<owner>/schemas/order.xsd`; the workspace paths in the
  scenarios above abbreviate this).
- **Repository switch**: part of the profile configuration for a development node (or its group or
  `defaults`); `read-only` (default) or `write`.
- **Repository change set**: the new and changed repository files of one import call, each in one
  of the lists `create`, `modify` or `identical` (a `modify` of a file new in the workspace also in
  `existing`), with the workspace artifacts that reference it.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer gets an edited shared schema into the development stage in one previewed,
  confirmed exchange with the assistant, without opening the Workbench; for up to 20 files the
  confirmed import finishes within 2 minutes on the development node used for the live probes, with
  files of up to 1 MiB each.
- **SC-002**: With the default configuration, 0 repository writes are possible through the
  development tools (`import_artifacts`, `restore_backup` of a development call); every attempt is
  refused and audited (verified by tests for each write path).
- **SC-003**: 100% of attempted repository writes on read-only nodes, on another owner's root or
  through path tricks are refused before anything reaches the node.
- **SC-004**: After a failed import or a restore, every affected repository file holds its previous
  content byte for byte in 100% of the tested failure points.
- **SC-005**: An administrator can switch repository writes on for one development node and confirm
  it with the configuration check in under 5 minutes using the documentation alone.
- **SC-006**: No key material, secret value or `${secret:…}` placeholder reaches an INUBIT repository,
  a tool result or a log through this feature (verified by dedicated tests).

## Assumptions

- Repository writes are offered only on development stages (feature 004); production groups never
  allow them. Deployments keep the behaviour of feature 005.
- Deleting repository files is out of scope, in line with "nothing is ever deleted in INUBIT".
- The switch deliberately has no path or file-type rules; an administrator who wants to keep a
  repository area out of reach keeps the switch off on that node.
- Limits of 50 repository files per call and 10 MiB per file are defaults chosen to keep previews
  and backups bounded; the plan may adjust them with justification.
- A file that is absent on the node (`create`) gets its content type from its file extension through
  a documented list (at least `.xsd`, `.xsl`, `.xslt`, `.wsdl`, `.xml`); other extensions are refused
  (FR-017). Its description is empty. A file the node already holds (`modify`, including `existing`)
  keeps the node's content type and description, whatever its extension; exported files keep those
  of their export.
- How INUBIT imports repository files is taken from the spike (section 10); any open detail of
  backup and rollback in repository mode is probed live on a personal test repository area before
  implementation, never on shared folders.
- Existing tools are extended (`export_artifacts`, `import_artifacts`, `restore_backup`); no new tool
  is added, keeping the tool set small.
