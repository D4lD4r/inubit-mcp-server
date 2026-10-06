# Feature Specification: Artifact Workspace

**Feature Branch**: `003-artifact-workspace`

**Created**: 2026-10-06

**Status**: Draft

**Input**: User description: "Feature 003 — Artifact workspace: export INUBIT artifacts into an
editable, diff-friendly workspace with a local git mirror, and check them locally. Authoritative
design: docs/design/2026-10-05-development-and-deployment.md (sections 1-5, 9, 10, 11) and spike
findings docs/research/spike-development-deployment.md. Scope: (1) a `workspace` setting, validated at
startup and shown by --check-config; (2) one local git repository per profile, never pushed, one
directory per configured group; (3) a read-only tool `export_artifacts` that exports diagram groups or
single modules into the workspace, split into one file per workflow and module with embedded content as
real files, normalized for readable diffs and lossless for a later re-import, then commits; (4) secrets
never reach the workspace, git, tool results or logs — they are replaced by placeholders; (5) a tool
`check_artifacts` with structure checks INUBIT itself does not perform, XSLT runs with the XSLT
processor generation INUBIT uses (with stand-ins for INUBIT's extension functions) and XML/XSD
validation. Out of scope: import, activation, tagging, restore, end-to-end tests, deployment, backups.
Offline round-trip and secret tests on anonymized real exports; live tests opt-in and read-only. The
server stays read-only by default."

## Overview

Today the AI assistant can only look at INUBIT systems: health, failed processes, logs and an inventory
of diagrams and modules. To let it *develop* — change a mapping, add a step to a workflow, fix a
branch condition — it first needs the artifacts as files it can read and edit, and a way to see
exactly what changed. This feature provides that foundation, without changing anything on an INUBIT
system yet:

- **Export**: the assistant pulls diagram groups or single modules from a development stage into a
  local **workspace**. Each workflow, each module and each embedded document (stylesheet, WSDL, schema)
  becomes its own readable file.
- **History**: every export is recorded in a local version history, so a second export shows precisely
  what someone changed in the Workbench in the meantime, and edits by the assistant show up as ordinary
  diffs.
- **Check**: before anything is imported in a later feature, the assistant can check its edits locally:
  structural consistency of workflows (which INUBIT does not check on import, and whose violation can
  make a whole diagram group unexportable), stylesheet runs against sample input, and XML/XSD
  validation.

Secrets in the exported artifacts — passwords (also in encrypted form), private keys and saved test
values — never appear in the workspace, the history, tool results or logs.

This is the first of three features (003 workspace, 004 development on a development stage, 005
deployment along the stage chain) described in the umbrella design. The workspace format defined here
must allow feature 004 to rebuild an import archive that INUBIT treats as identical to the original.

**Actors**

- **Developer/Operator**: asks the AI assistant to work on INUBIT artifacts; reviews the diffs; runs
  the Workbench in parallel.
- **AI assistant**: exports artifacts, edits the files with its own file tools, runs checks.
- **Maintainer**: builds features 004 and 005 on top of the workspace format.

**Terminology used in this specification**

- **Profile**, **group**, **node**: as in feature 002 (one configuration per customer; a group is a
  named set of INUBIT servers, e.g. a stage; a node is one server). Display names come from the
  profile's terminology.
- **Diagram group**: INUBIT's own grouping of diagrams (a folder in the Workbench), owned by a user or
  user group. Not the configured group.
- **Owner**: the INUBIT user or user group that owns a diagram group (the existing `inventory.owner`
  setting, or an explicitly given owner).
- **Artifact**: a workflow (technical diagram), a module, or a repository file.
- **Workspace**: the local directory tree with one sub-tree per configured group and a version
  history.
- **Placeholder**: the text that stands in the workspace where an exported secret was.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Export a diagram group as readable files (Priority: P1)

The developer asks the assistant to "get the diagram group GRP-41 from dev". The assistant exports it
from the development stage. The workspace now holds one file per workflow of that group, one file per
module those workflows use, every embedded stylesheet, WSDL or schema as a file of its own, and the
repository files the workflows reference. The result tells the assistant which files were added or
changed and which history entry records the export. Exporting the same unchanged group again changes
nothing.

**Why this priority**: every later capability (checking, importing, deploying) works on these files.

**Independent Test**: export a recorded diagram group through a fake INUBIT command line; verify the
file tree, the history entry and that a second export of the same data yields no change.

**Acceptance Scenarios**:

1. **Given** a development group with an owner and a diagram group of three workflows using eight
   modules, **When** the assistant exports that diagram group, **Then** the workspace of that group
   contains three workflow files, eight module files, one file per embedded document and the referenced
   repository files, and the result lists these paths and the history entry.
2. **Given** an export of a diagram group, **When** the same unchanged diagram group is exported again,
   **Then** no file changes, no history entry is created and the result says that nothing changed.
3. **Given** an export, **When** a person changed one stylesheet in the Workbench and the assistant
   exports again, **Then** exactly that stylesheet file changes and the history entry's diff shows only
   that change.
4. **Given** a group with two nodes, **When** the assistant exports without naming a node, **Then** the
   first node is used and the result names it; **When** it names the second node, **Then** that node is
   used.
5. **Given** a workflow that a person currently has in edit mode in the Workbench, **When** it is
   exported, **Then** the workflow file shows who has it in edit mode.

---

### User Story 2 - Secrets never leave INUBIT (Priority: P1)

The exported artifacts contain connector passwords, AS2 and WS-Security keystores and saved test
values. The developer expects none of them on disk, in the history, in the conversation or in logs —
including encrypted values, because INUBIT's encryption does not depend on the installation and an
encrypted value can be used elsewhere.

**Why this priority**: a single leaked secret is a security incident; Constitution II is
non-negotiable.

**Independent Test**: export recorded artifacts that contain every known secret form; search the
workspace, the history, the tool result and the log output for the original values.

**Acceptance Scenarios**:

1. **Given** modules with password properties in every observed form (encrypted with different
   prefixes, older encodings, plain text, with and without the "encrypted" marker), **When** they are
   exported, **Then** each value is replaced by a placeholder naming the artifact and the property, and
   none of the original values appears anywhere in the workspace or its history.
2. **Given** a module with an embedded keystore holding a private key, **When** it is exported,
   **Then** the keystore is replaced by a placeholder; a plain certificate without a private key may
   stay.
3. **Given** a workflow with a password literal, a password-typed variable with a default value and a
   stylesheet module with saved test values, **When** they are exported, **Then** all of them are
   replaced by placeholders.
4. **Given** any export or check, **When** the result or a log line is produced, **Then** it contains
   no secret value.

---

### User Story 3 - Check the structure of an edited workflow (Priority: P2)

The assistant has inserted a step into a workflow file and rewired a branch. Before anyone imports it,
it asks for a check. The check reports every edge that points to a node that does not exist, every
branch condition that does not belong to an outgoing edge, every node id that collides with a
connection id, every module that exists neither in the workspace nor on the server, and every variable
or repository reference that does not resolve. An unchanged export passes without findings.

**Why this priority**: INUBIT stores such broken workflows without complaint, and a missing module
makes the whole diagram group unexportable; the check is the only safety net before feature 004 can
import anything.

**Independent Test**: run the check on workspace fixtures with one deliberate defect each and on an
unchanged export.

**Acceptance Scenarios**:

1. **Given** a workflow with an edge to a non-existent node, **When** it is checked, **Then** the
   result names the workflow, the source node and the missing target.
2. **Given** a Demultiplexer whose condition refers to a node that is not one of its outgoing edges,
   **When** it is checked, **Then** the finding names the condition and the node.
3. **Given** a node whose id equals a connection id in the same workflow, **When** it is checked,
   **Then** the collision is reported.
4. **Given** a node referencing a module that is neither in the workspace nor on the server, **When**
   it is checked, **Then** this is an error; **given** the module exists only on the server (e.g. a
   shared library module), **Then** it is not an error.
5. **Given** an unchanged export of real workflows, **When** it is checked, **Then** there are no
   structural errors.

---

### User Story 4 - Run a stylesheet locally (Priority: P2)

The assistant changes a mapping and wants to see its effect. It runs the stylesheet of that module
against a sample input file in the workspace and gets the output file to compare with the expected
result. Stylesheets that call INUBIT's own extension functions run as well, with local stand-ins for
those functions. A stylesheet that cannot be run faithfully outside INUBIT is reported as "not testable
locally" — never as passed.

**Why this priority**: most changes are mapping changes; a quick local run catches most mistakes
before an import.

**Independent Test**: run fixture stylesheets (plain, with INUBIT extension functions, with an unknown
extension, with a syntax error) against sample inputs.

**Acceptance Scenarios**:

1. **Given** a stylesheet module and an input file, **When** the assistant runs the check, **Then** the
   transformation output is written to the workspace's test area and its path is returned.
2. **Given** a stylesheet that uses INUBIT extension functions for which stand-ins exist, **When** it
   runs, **Then** it produces output, and the result notes that stand-ins were used.
3. **Given** a stylesheet that needs an unknown extension or a capability only available inside
   INUBIT, **When** it is checked, **Then** the result says "not testable locally" with the reason.
4. **Given** a stylesheet with a syntax error, **When** it is checked, **Then** the error and its
   location are reported.
5. **Given** a stylesheet that imports a repository file, **When** it runs, **Then** the repository
   file from the workspace is used.

---

### User Story 5 - Validate XML documents (Priority: P3)

The assistant edits a configuration document or a sample message and validates it: well-formedness
always, and validation against a schema that is embedded in a module or stored in the repository when
one is given or referenced.

**Why this priority**: useful, but less frequent than mapping and structure changes.

**Independent Test**: validate fixture documents against fixture schemas.

**Acceptance Scenarios**:

1. **Given** a document that is not well-formed, **When** it is checked, **Then** the error and its
   line are reported.
2. **Given** a document and a schema from the workspace, **When** it is checked, **Then** every schema
   violation is reported with its location; a valid document passes.

---

### User Story 6 - Configure and inspect the workspace (Priority: P3)

The operator relies on the default workspace location or sets another one in the profile. At startup
and in the configuration check they see where the workspace is and whether it is usable.

**Why this priority**: needed, but small.

**Independent Test**: start with default, custom, unusable and shared-with-another-profile workspace
settings.

**Acceptance Scenarios**:

1. **Given** no workspace setting, **When** the server starts, **Then** the workspace is in the default
   location for the profile and the configuration check shows it.
2. **Given** a workspace location that cannot be created or written, **When** the server starts,
   **Then** it reports a configuration error naming the location.
3. **Given** two profiles configured with the same workspace location, **When** the configuration check
   runs, **Then** it reports an error.

---

### Edge Cases

- An export of a diagram group whose workflows reference library modules owned by someone else: the
  modules are not part of the export; the workflow files reference them; the structure check accepts
  them if they exist on the server.
- An export that times out or fails on the INUBIT side (e.g. a diagram group made unexportable by a
  broken workflow): no file in the workspace changes, no history entry is created, and the error says
  which export failed and why.
- Two exports into the same workspace at the same time: they are serialized; neither leaves a half-
  written tree.
- A module name that differs from another only in upper/lower case: both are kept apart, or the export
  stops with a clear error; files are never silently overwritten.
- Characters in workflow, module or diagram group names that are not valid in file names: they are
  encoded so that the original name can be restored exactly.
- A workflow removed from a diagram group in the Workbench since the last export: a new export of that
  diagram group removes its file and records the removal in the history.
- An unknown secret-like property type in a future INUBIT version: the export does not fail, and the
  value is treated as a secret (placeholder) when its type marks it as a password; unknown embedded
  binary content is kept unchanged.
- A very large export (hundreds of modules): the tool result stays bounded; the full list of changed
  paths is written to a file in the workspace and the result returns counts and that path.
- The workspace was edited by hand (uncommitted changes) before an export: the export does not discard
  those edits silently; it refuses and lists the affected files.
- A node of a group without a configured command-line installation: the export reports that the
  command line is unavailable.

## Requirements *(mandatory)*

### Functional Requirements

**Configuration**

- **FR-001**: The profile MUST accept a workspace location; without it the location MUST default to a
  per-profile directory in the user's home (`~/.inubit-mcp/<profile>/workspace`).
- **FR-002**: At startup the server MUST create the workspace if missing, with access for the current
  user only, and MUST report a configuration error if it cannot be created, read or written.
- **FR-003**: Two profiles in the default configuration directory MUST NOT share a workspace location;
  this MUST be reported as a configuration error.
- **FR-004**: The configuration check MUST show the workspace location and its state.
- **FR-005**: Without any use of the new tools the server MUST behave exactly as before; the new tools
  MUST NOT change anything on an INUBIT system.

**Version history**

- **FR-006**: The workspace MUST keep a local version history (a git repository) that records every
  export as one entry with a message naming the group, node, diagram groups or modules and the number
  of changed files.
- **FR-007**: The server MUST NOT configure any remote for the history and MUST NOT transmit it
  anywhere.
- **FR-008**: The workspace MUST hold one sub-tree per configured group, so that the same artifact on
  two groups can be compared as two files.

**Export**

- **FR-009**: The assistant MUST be able to export one or more diagram groups of an owner from one
  group or node; the owner defaults to the configured inventory owner.
- **FR-010**: The assistant MUST be able to export single modules of an owner.
- **FR-011**: Without an explicit node, the export MUST use the first node of the group and name it in
  the result.
- **FR-012**: The export MUST write one file per workflow, one file per module used by the exported
  workflows (or per exported module), each embedded document (stylesheet, WSDL, schema, configuration
  document, other embedded XML) as a separate file in its natural format, and every repository file
  referenced by the exported artifacts.
- **FR-013**: Files MUST be normalized so that exporting unchanged artifacts twice produces no change:
  a canonical layout; values that INUBIT changes on every export or import (the export signature in the
  check-in comment, export metadata, internal unique ids) MUST be kept in a separate metadata area of
  the workspace that is not part of the reviewed content.
- **FR-014**: Information that matters for review MUST stay in the reviewed content, in particular who
  has a workflow in edit mode, the active flag, layout positions and the check-in comment written by a
  person.
- **FR-015**: The transformation MUST be lossless: from the workspace and its metadata area it MUST be
  possible to rebuild an archive whose content INUBIT treats as identical to the exported one
  (verified offline against recorded real exports); embedded documents MUST be stored back in the way
  INUBIT writes them.
- **FR-016**: Names of workflows, modules and diagram groups MUST map to file paths reversibly; names
  that would collide on a case-insensitive file system MUST NOT overwrite each other.
- **FR-017**: A new export of a diagram group MUST remove the files of artifacts that are no longer in
  it.
- **FR-018**: If the export fails or the received archive cannot be processed, the workspace MUST stay
  unchanged and no history entry MUST be created.
- **FR-019**: If the workspace contains changes that are not recorded in the history, an export into
  the affected files MUST be refused with a list of those files.
- **FR-020**: Exports into the same workspace MUST NOT run concurrently.
- **FR-021**: The export result MUST list the added, changed and removed paths and the history entry;
  for large exports it MUST return counts and a path to a file in the workspace with the full list
  (bounded output).

**Secrets**

- **FR-022**: Every password-typed property MUST be replaced by a placeholder, whatever its value format
  and whether or not it is marked as encrypted.
- **FR-023**: Embedded keystores that contain private keys, password literals in workflows, default
  values of password-typed workflow variables and saved test values of stylesheet modules MUST be
  replaced by placeholders.
- **FR-024**: A placeholder MUST identify the artifact and the property path, so that a later feature
  can restore the value from the target system; it MUST NOT contain or be derived from the value.
- **FR-025**: No secret value MUST be written to the workspace, its metadata area, its history, a
  temporary file that outlives the export, a tool result, an error message or a log.
- **FR-026**: Raw exports received from INUBIT MUST be processed in a location only the current user can
  access and MUST be deleted when the export ends, also on failure.

**Checks**

- **FR-027**: The assistant MUST be able to check one or more workspace files; a workflow check MUST
  report: edges to non-existent nodes; branch conditions that do not match an outgoing edge; node ids
  that collide with other node or connection ids of the same workflow; referenced modules that exist
  neither in the workspace nor on the server; unresolved variable references; unresolved repository
  references.
- **FR-028**: Module existence on the server MUST be determined read-only; if the server cannot be
  reached, the finding MUST say that the existence could not be verified instead of passing or failing.
- **FR-029**: The assistant MUST be able to run a stylesheet of the workspace against an input file of
  the workspace with an XSLT processor of the generation INUBIT uses; the output MUST be written to a
  test area of the workspace and returned as a path.
- **FR-030**: Calls to INUBIT's own XSLT extension functions found in real stylesheets MUST be served by
  local stand-ins that behave like the originals for typical input; the result MUST say when stand-ins
  were used.
- **FR-031**: A stylesheet that needs an unknown extension or a capability not available locally MUST
  be reported as "not testable locally" with the reason, never as passed.
- **FR-032**: Stylesheet imports and includes that refer to the INUBIT repository MUST be resolved from
  the workspace.
- **FR-033**: The assistant MUST be able to check XML documents for well-formedness and against a schema
  from the workspace; findings MUST include the location.
- **FR-034**: Check results MUST be structured per artifact and finding (severity, location, message),
  bounded in size, and MUST NOT contain secret values.
- **FR-035**: Checks MUST NOT change any file outside the workspace's test area and MUST NOT change
  anything on an INUBIT system.

**General**

- **FR-036**: Both tools MUST use the profile's terminology in descriptions and messages, validate
  their inputs before any call to INUBIT, and report failures as actionable errors (what failed, likely
  cause, next step).
- **FR-037**: Both tools MUST be offered whenever the profile has at least one node with a command-line
  installation (export) or always (checks on existing files); they are read-only and need no write
  permission.

### Key Entities

- **Workspace**: per profile; a directory tree with a version history, one sub-tree per group, a
  metadata area and a test area.
- **Workflow file**: one workflow of a diagram group with its nodes, edges, conditions, variables and
  layout; identified by owner, diagram group and name.
- **Module file**: the configuration of one module, with references to its embedded documents;
  identified by plugin type and name.
- **Embedded document**: a stylesheet, WSDL, schema or other document stored inside a module, kept as a
  separate file.
- **Repository file**: a file of the owner's INUBIT repository referenced by artifacts.
- **Metadata record**: the volatile values of an artifact needed for a lossless rebuild.
- **Placeholder**: a stand-in for a secret value, naming artifact and property path.
- **History entry**: one recorded export with its message and changed paths.
- **Check finding**: severity, artifact, location and message of one check result.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Exporting the same unchanged diagram group twice produces zero changed files in 100 % of
  the recorded real exports used as test data.
- **SC-002**: For 100 % of the recorded real exports, rebuilding an archive from the workspace yields
  content identical to the export (apart from the values INUBIT itself changes on every export).
- **SC-003**: No original secret value of the test data is found anywhere in the workspace, its
  history, tool results or logs (0 occurrences across all known secret forms).
- **SC-004**: Each structural defect type observed in the spike (dangling edge, missing module, id
  collision) is detected in 100 % of the defect fixtures, and unchanged real workflows produce no
  structural errors.
- **SC-005**: At least 95 % of the stylesheets in the recorded real exports can be run locally (with
  stand-ins where needed); every other stylesheet is reported as "not testable locally" or as a genuine
  error, none as passed.
- **SC-006**: Exporting a diagram group with up to 100 modules completes, including history entry, in
  under one minute plus the time INUBIT needs to produce the export.
- **SC-007**: A person reviewing a change made in the Workbench can see it in the history as a diff of
  only the affected files.

## Assumptions

- The command line of a local INUBIT client installation is available, as for the existing inventory
  tools; INUBIT 8.1 is the supported version, and format differences are isolated in the
  version-specific part, as today.
- A git installation is available on the machine running the server.
- The workspace is used by one person; it is not a shared repository and not the source of any
  deployment (the development stage is the source of truth).
- Library modules shared across owners are identified by name; their content is not exported unless
  requested as single modules.
- The stand-ins for INUBIT's extension functions cover the functions found in real stylesheets by the
  spike; further functions are added when they are found.
- Writing to INUBIT (import, activation, tagging, restore), backups, end-to-end tests and deployment
  are features 004 and 005.
- Script modules were not found in real exports and are out of scope.
