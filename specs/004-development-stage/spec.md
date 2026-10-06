# Feature Specification: Development on a Development Stage

**Feature Branch**: `004-development-stage`

**Created**: 2026-10-06

**Status**: Draft

**Input**: User description: "Feature 004 — Development on a development stage: import, activate,
tag, restore and end-to-end test INUBIT artifacts from the feature-003 workspace. Configuration per
group/node (`development.enabled`, `development.confirmation` with SERVER as default, `e2eTests`
FREE | CONFIRM | FORBIDDEN, `e2e.soap.baseUrl`; none of it on production groups). `import_artifacts`
with server-enforced steps: check, conflict detection (incl. Workbench edit mode, re-checked right
before the import), backup outside git, only changed artifacts, secrets restored from the target in
memory, guard, import with protocol matching, verify by re-export, rollback on any failure, reason as
check-in comment. `set_active`, `tag_artifacts` (per diagram group, never owner-wide, no moving),
`restore_backup`, `run_e2e_test` for SOAP. Every tool audited, destructive, offered only with a
development node; bounded results. Respect the INUBIT 8.1.17 spike facts. Out of scope: deployment
to other stages, AS2 tests, deleting artifacts, creating credential-bearing connectors. Offline tests
against a fake StartCLI; opt-in live tests only on development groups and dedicated test artifacts."

## Overview

Feature 003 lets the AI assistant export INUBIT artifacts into a local workspace, edit them as files
and check them. This feature closes the loop on a **development stage**: the assistant brings its
changes back into INUBIT, switches workflows on or off, marks a tested state with a tag, undoes a
change, and sends a test message through a SOAP workflow to see what INUBIT does with it.

Writing to INUBIT is risky in ways the spike made concrete:

- INUBIT does **not validate** what it imports. A workflow that references a missing module is
  accepted — and afterwards the whole diagram group can no longer be exported.
- An import and a person publishing from the Workbench **overwrite each other silently**; nothing in
  INUBIT protects a workflow that someone is editing.
- Every import creates a **new version**, even without changes.
- Tags can only be set for a **whole diagram group**, and a careless call tags everything an owner has.
- INUBIT's encryption does not depend on the installation, so a secret value copied from one place to
  another silently works there.

So the server — not the assistant — enforces a fixed sequence for every write: check, detect
conflicts, back up, write only what changed, verify, and roll back if anything goes wrong. Every write
is audited, and by default each one needs a confirmation code returned by a preview.

This is feature 2 of 3 of the development and deployment design (003 workspace, **004 development on
a development stage**, 005 deployment along the stage chain).

**Actors**

- **Developer/Operator**: asks the assistant for changes on the development stage; works in the
  Workbench in parallel; approves previews when confirmation is required.
- **AI assistant**: edits workspace files (feature 003), then calls the tools of this feature.
- **Colleagues**: other people working in the Workbench on the same development stage.
- **Operator responsible for the configuration**: decides which groups count as development stages
  and whether end-to-end tests are allowed there.

**Terminology**

- **Development group/node**: a configured group or node with development enabled.
- **Base export**: the workspace state (history entry of feature 003) that an edit started from.
- **Preview / confirmation code**: the existing two-step confirmation of the server: a first call
  returns what would happen and a one-time code; a second call with the code executes it.
- **Backup**: the state of the affected artifacts on the server right before a write, kept outside the
  workspace history.
- **Edit mode**: a workflow a person has opened for editing in the Workbench ("Edit") and not yet
  published.

## Clarifications

### Session 2026-10-06

- Q: What does one `import_artifacts` call cover? → A: All changed workflows of one diagram group of one owner together with their changed or new modules; alternatively changed single modules without a workflow, as a module import.
- Q: How does the server know whether an owner is a user or a user group? → A: Automatically through a read-only lookup in INUBIT's user administration; an optional configuration entry per owner overrides the lookup; if the result is ambiguous or the lookup fails, nothing is imported and the result says why.
- Q: How long are backups kept? → A: 30 days; the newest backup per owner and diagram group (or module set) is always kept; older ones are removed at the next writing call, with an audit record.
- Q: How does the end-to-end test find the process instances of its own message? → A: A unique test id is sent as a SOAP/HTTP header and searched in logs and process data; without a match, the time window of the test serves as fallback and the correlation is marked as uncertain.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Bring an edited workflow or module back into INUBIT (Priority: P1)

The assistant has changed a mapping and inserted a step into a workflow in the workspace. The
developer asks it to "import the change to dev". The server checks the files, makes sure nobody
changed or opened the affected artifacts on the server since the export, backs them up, imports only
what changed, and verifies the result by exporting again. The developer sees which artifacts were
created or modified, the new state in the workspace history and a reference to the backup.

**Why this priority**: it is the core of developing with the assistant; everything else supports it.

**Independent Test**: against a fake INUBIT command line with recorded import protocols: import an
edited fixture workflow and verify the sequence of calls, the archive content, the result and the
history entry.

**Acceptance Scenarios**:

1. **Given** a development node and a workspace with an edited workflow and module of one diagram
   group, **When** the assistant imports with a reason, **Then** only the changed workflow and the
   changed module are sent, the result lists them as modified, the re-export equals the intended state,
   and the reason appears in the check-in comment of the new version.
2. **Given** an edited workflow with an edge to a non-existent node, **When** the assistant imports,
   **Then** nothing is sent to INUBIT and the result lists the check errors.
3. **Given** that a colleague changed the same workflow on the server after the export, **When** the
   assistant imports, **Then** nothing is sent, the result says CONFLICT and points to a file with the
   difference.
4. **Given** that a colleague has the workflow in edit mode, **When** the assistant imports, **Then**
   nothing is sent and the result says CONFLICT naming the edit mode (not the person's private data
   beyond the user name INUBIT reports).
5. **Given** a module with a password placeholder in the workspace, **When** it is imported, **Then**
   the value currently on the server is used; **given** the server has no value for it, **Then** the
   import is refused with SECRET_UNRESOLVED.
6. **Given** that the import fails, or the re-export after it fails or differs, **When** this happens,
   **Then** the server re-imports the backup of the affected artifacts and reports what happened and
   whether the rollback succeeded.
7. **Given** confirmation by server (the default), **When** the assistant calls the import, **Then** the
   first call only returns a preview (what will be sent, what changes) and a code; the second call with
   the code repeats the conflict check right before importing.

---

### User Story 2 - Undo a change (Priority: P1)

An import turned out wrong. The developer asks the assistant to "restore the state before the last
import". The server re-imports the backup of exactly the artifacts that import changed, with the same
safety steps.

**Why this priority**: writing without a reliable way back is not acceptable.

**Independent Test**: import, then restore by reference; the re-export equals the state before the
import.

**Acceptance Scenarios**:

1. **Given** a successful import with a backup reference, **When** the assistant restores it, **Then**
   only the artifacts of that import are re-imported and the re-export equals the backed-up state.
2. **Given** that a colleague changed one of those artifacts after the import, **When** the assistant
   restores, **Then** nothing is sent and the result says CONFLICT.
3. **Given** an unknown or foreign backup reference, **When** the assistant restores, **Then** the call
   is refused without contacting INUBIT.

---

### User Story 3 - Switch a workflow on or off (Priority: P2)

The developer asks the assistant to activate a workflow after a successful change, or to deactivate one
during testing.

**Why this priority**: needed for testing changes, but less frequent than importing.

**Independent Test**: activate and deactivate a fixture workflow through the fake command line; only
that workflow is sent.

**Acceptance Scenarios**:

1. **Given** an inactive workflow on a development node, **When** the assistant activates it, **Then**
   only that workflow (no module) is re-imported as active, the re-export shows it active, and the
   result notes that INUBIT created a new version.
2. **Given** a workflow in edit mode or changed since the export, **When** the assistant changes its
   active flag, **Then** the call is refused with CONFLICT.

---

### User Story 4 - Mark a tested state with a tag (Priority: P2)

After a successful test, the developer asks the assistant to tag the diagram group "REL-2026-10-06".
Feature 005 will deploy exactly that tagged state.

**Why this priority**: the tag is the hand-over to deployment; it must never hit more than intended.

**Independent Test**: tag a fixture diagram group through the fake command line; verify the exact call
and that the server checks afterwards which artifacts carry the tag.

**Acceptance Scenarios**:

1. **Given** a diagram group of an owner on a development node, **When** the assistant tags it, **Then**
   the tag is set for exactly that owner and diagram group, and the result lists how many workflows and
   modules carry it.
2. **Given** an empty or blank diagram group, **When** the assistant tags, **Then** the call is refused
   before anything is sent (INUBIT would tag everything of the owner).
3. **Given** a tag name that already exists for that owner, **When** the assistant tags, **Then** the
   call is refused (no moving of tags).
4. **Given** that the verification finds the tag on artifacts outside the requested diagram group,
   **When** this happens, **Then** the server removes the tag again and reports the failure.

---

### User Story 5 - Send a SOAP test message and see the outcome (Priority: P3)

The developer asks the assistant to "send this order to the order service on dev and show what
happened". The server posts the SOAP envelope from the workspace to the configured endpoint, records
the response, and collects the process instances, errors and log entries INUBIT produced in the same
time window.

**Why this priority**: end-to-end confirmation is valuable but depends on the endpoints being set up;
SOAP first, AS2 later.

**Independent Test**: against a simulated SOAP endpoint and recorded process and log responses.

**Acceptance Scenarios**:

1. **Given** a node with end-to-end tests allowed freely and an envelope file, **When** the assistant
   runs the test, **Then** the response (status, size, a bounded excerpt or a file path) and the process
   instances, errors and log entries found by the test id are returned.
2. **Given** a node where tests need confirmation, **When** the assistant runs the test, **Then** the
   first call returns a preview naming endpoint and payload size and a code; only the call with the code
   sends the message.
3. **Given** a node where tests are forbidden (the default, and always on production), **When** the
   assistant runs the test, **Then** the call is refused with E2E_FORBIDDEN and nothing is sent.

4. **Given** that nothing can be found by the test id, **When** the test completes, **Then** the
   instances of the target workflow in the test's time window are returned, marked as uncertain.
---

### User Story 6 - Configure development stages (Priority: P3)

The operator marks the development group in the profile, chooses whether writes need server
confirmation and whether end-to-end tests are allowed, and sees the result in the configuration check.

**Independent Test**: startup with valid and forbidden combinations.

**Acceptance Scenarios**:

1. **Given** no development settings, **When** the server starts, **Then** none of the tools of this
   feature is offered and nothing can be written.
2. **Given** development enabled on a production group, **When** the server starts, **Then** it reports
   a configuration error and does not start.
3. **Given** end-to-end tests other than forbidden on a production group, **When** the server starts,
   **Then** it reports a configuration error.
4. **Given** valid settings, **When** the configuration check runs, **Then** it shows per node whether
   development is enabled, the confirmation mode and the end-to-end test policy.

---

### Edge Cases

- The assistant imports while an export or check of feature 003 is running on the same workspace:
  refused at once (one workspace operation at a time).
- The import succeeds but the re-export fails because INUBIT broke the diagram group (the spike's
  missing-module case): this is a failure; the server rolls back and verifies the rollback.
- The rollback itself fails: the result says so explicitly, names the backup reference and keeps the
  backup; nothing is deleted.
- A backup older than 30 days that is not the newest of its diagram group has been removed: a restore of
  it is refused with a clear message; the newest backup of each diagram group is never removed.
- The confirmation code has expired or belongs to another call: refused, nothing sent.
- The workspace has uncommitted edits in the affected files: they are first recorded as local changes
  (feature 003 behaviour), then imported.
- A new workflow or module (not yet on the server) is imported: allowed; it is created; its name must
  not collide with an existing artifact of another kind or owner.
- A workflow references a module that is neither in the archive nor on the server: blocked by the check
  before anything is sent.
- The same diagram group is open in the Workbench by the developer themself: still a conflict; the
  developer publishes or discards first.
- A SOAP endpoint does not answer within the time limit: the test reports a timeout and still returns
  what the server saw in that window.
- The import protocol of INUBIT lists artifacts that were not in the archive, or misses some that were:
  treated as failure and rolled back.
- Process instances of other traffic in the same time window: with a match on the test id, only those
  are shown; without one, only instances of the tested workflow in the window are shown, marked as
  uncertain.

## Requirements *(mandatory)*

### Functional Requirements

**Configuration**

- **FR-001**: The profile MUST allow marking groups and nodes as development stages (default: not); the
  tools of this feature MUST be offered only if at least one node is a development stage, and MUST
  refuse every other node with NOT_DEVELOPMENT.
- **FR-002**: The profile MUST allow choosing per group or node whether writes need server
  confirmation (default) or client confirmation.
- **FR-003**: The profile MUST allow an end-to-end test policy per group or node: free, confirm, or
  forbidden (default), and per node the base address of its SOAP endpoints.
- **FR-004**: Development stages and any end-to-end policy other than forbidden MUST be rejected on
  production groups at startup.
- **FR-005**: The configuration check MUST show the development, confirmation and end-to-end settings
  per node.

**Import**

- **FR-006**: The assistant MUST be able to import, with a mandatory reason, from the workspace of a
  development group to one of its nodes either (a) all changed workflows of **one diagram group** of
  one owner together with their changed or new modules, or (b) changed single modules of one owner
  without a workflow (module import). Changes outside that scope stay in the workspace and are listed
  in the result as not imported.
- **FR-007**: Before sending anything, the server MUST run the checks of feature 003 on the affected
  files; any ERROR finding MUST abort the import.
- **FR-008**: Before sending anything, the server MUST export the affected artifacts from the node and
  compare them with the base export; any difference or any workflow in edit mode MUST abort with
  CONFLICT, writing the difference to a workspace file whose path is returned.
- **FR-009**: With server confirmation, the first call MUST only return a preview (artifacts to be
  created and modified, check warnings) and a one-time code; the call with the code MUST repeat the
  conflict check immediately before importing.
- **FR-010**: Before importing, the server MUST keep the exported state of the affected artifacts as a
  backup outside the workspace history, readable only by the current user, referenced by the audit
  record of the call. Backups MUST be kept for 30 days; the newest backup per owner and diagram group
  (or module set) MUST always be kept; older backups MUST be removed at the next writing call of this
  feature and the removal recorded in the audit log. Restoring a removed backup MUST be refused with a
  clear message.
- **FR-011**: The import archive MUST contain only the changed artifacts: changed workflows with only
  their changed or new modules, or changed modules alone; unchanged artifacts MUST NOT be sent.
- **FR-012**: Secret placeholders MUST be replaced by the value currently on the target node, in memory
  only; a placeholder without a value on the target MUST abort with SECRET_UNRESOLVED. Secret values
  MUST NOT reach the workspace, logs, results or audit.
- **FR-013**: Before sending, the server MUST verify that the archive contains exactly the intended
  artifacts and nothing else.
- **FR-014**: The import MUST target the artifacts' owner. Whether the owner is a user or a user group
  MUST be determined by a read-only lookup in INUBIT's user administration, unless the profile names the
  owner's kind explicitly (which takes precedence); an ambiguous or failed determination MUST abort the
  call before anything is sent. The server MUST verify that INUBIT's import protocol names exactly the
  sent artifacts.
- **FR-015**: After the import, the server MUST export again and compare with the intended state; a
  failing export or a difference is a failure.
- **FR-016**: On any failure after the first artifact was sent, the server MUST re-import the backup of
  the affected artifacts, verify it, and report what failed and whether the rollback succeeded; the
  backup MUST be kept.
- **FR-017**: The reason MUST appear in the check-in comment of the new versions.
- **FR-018**: After a successful import the workspace MUST be updated to the verified server state and
  recorded as one history entry.

**Restore, activate, tag**

- **FR-019**: The assistant MUST be able to restore the backup of a previous call of this feature,
  limited to the artifacts that call changed, with the same check, conflict, verify and rollback steps;
  unknown references MUST be refused.
- **FR-020**: The assistant MUST be able to activate or deactivate one workflow; only that workflow MUST
  be sent, and the conflict check applies.
- **FR-021**: The assistant MUST be able to tag the current versions of one or more diagram groups of an
  owner; the request MUST be limited to those diagram groups (never owner-wide); empty or blank group
  names MUST be refused before anything is sent; an existing tag name MUST NOT be moved; afterwards the
  server MUST verify which artifacts carry the tag and remove it again if it reached anything outside
  the request.

**End-to-end test (SOAP)**

- **FR-022**: The assistant MUST be able to send a SOAP envelope file from the workspace to a path
  below the node's configured SOAP base address, governed by the node's end-to-end policy.
- **FR-023**: Each test MUST carry a unique test id as a SOAP/HTTP header; the result MUST contain the
  response status, a bounded excerpt or a workspace file with the response, and the process instances,
  errors and log entries found by that id. If nothing is found by the id, the process instances, errors
  and log entries of the target workflow within the test's time window MUST be returned instead and the
  correlation marked as uncertain.
- **FR-024**: The test MUST use a time limit and report a timeout without losing the collected
  diagnostics.

**General**

- **FR-025**: Every call of the tools of this feature — refused, previewed, executed or failed — MUST be
  recorded in the audit log with node, tool, step, sanitized inputs (reason, artifact names, backup
  reference), outcome and confirmation code hash; never content or secrets.
- **FR-026**: The tools MUST be marked as destructive and not idempotent.
- **FR-027**: Workspace operations (export, check, import, restore, activate) MUST NOT run concurrently.
- **FR-028**: Results MUST be bounded; differences, protocols and responses MUST be written to workspace
  files whose paths are returned.
- **FR-029**: Nothing in this feature may delete an artifact or write to a node that is not a
  development stage.

### Key Entities

- **Development settings**: per group/node — enabled, confirmation mode, end-to-end policy, SOAP base
  address; per profile optionally the kind (user or user group) of named owners.
- **Change set**: the changed and new workflows and modules of one owner and group, derived from the
  workspace against its base export.
- **Backup**: the server state of the affected artifacts before a write; referenced by the audit
  record; kept outside the history.
- **Write call**: one tool call with its preview, code, steps, outcome and rollback outcome.
- **End-to-end run**: endpoint, envelope, response, time window, correlated instances, errors and log
  entries.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In 100 % of the offline scenarios, nothing is sent to INUBIT when the check finds an
  error, a conflict exists, a workflow is in edit mode or a secret is unresolved.
- **SC-002**: In 100 % of the offline failure scenarios (failing import, failing or differing re-export,
  mismatching protocol), the affected artifacts end in their backed-up state or the result names the
  failed rollback explicitly.
- **SC-003**: An import sends only changed artifacts: re-importing an unchanged workspace sends nothing
  and creates no version.
- **SC-004**: No secret value appears in the workspace, history, backups' references, audit, results or
  logs (verified for every synthetic secret form of feature 003).
- **SC-005**: A tag call can never mark more than the requested diagram groups: verified for empty,
  blank and wildcard-like input and for the post-check.
- **SC-006**: On the live development stage (opt-in), a change made by the assistant is imported,
  verified, restored and re-imported on dedicated test artifacts with no change to any other artifact.
- **SC-007**: An import of a diagram group with up to 20 workflows and 100 modules completes within 2
  minutes plus INUBIT's own export and import time.

## Assumptions

- Feature 003 is in place: workspace, base exports, checks, redaction and the archive rebuild.
- The INUBIT 8.1 command line of a local client installation is available; INUBIT 8.1 is the supported
  version, as before.
- The owner of the artifacts is a user or a user group; INUBIT's user administration can be read to
  tell which (the import addresses users and user groups differently).
- One call works on one diagram group (or a set of single modules) of one owner of one group on one node.
- Feature 005 (deployment) builds on the tags of this feature.
- AS2 end-to-end tests, deletion of artifacts, and creating connectors that need credentials are out of
  scope; a person does the latter in the Workbench.
- The development stage's outbound connectors do not reach productive partners (the operator's
  responsibility, reflected in the end-to-end policy).
