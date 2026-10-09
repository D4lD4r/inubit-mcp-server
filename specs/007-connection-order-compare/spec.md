# Feature Specification: Order-Insensitive Comparison of Workflow Connections

**Feature Branch**: `007-connection-order-compare`

**Created**: 2026-10-09

**Status**: Draft

**Input**: User description (summarized; the original report names customer artifacts and is kept
out of this public repository): "Release 0.5.1. `import_artifacts` on a development server failed
twice with `VERIFY_MISMATCH`, and the rollback that followed was reported as `rollback: FAILED`
for the same reason, although its content was correct. Cause: when INUBIT saves a workflow, it
writes the outgoing `<Connection>` children of a `<WorkflowModule>` in a non-deterministic order.
Content, number and attributes are identical; only the order of sibling connections within one
module changes, flipping between two variants. The order of the modules themselves is stable. The
same effect makes exports report unchanged workflows as `MODIFIED`, and without a fix a deployment
of such workflows to the next stage fails as well. Comparisons must ignore the order of these
connections, real differences must still be found, the sorting logic must exist only once, and
what is imported, deployed or stored must stay as it is; stabilizing the export is to be proposed
as a separate decision."

## Context & Findings *(verified before writing this spec)*

- **Evidence.** In the workspace history of the affected diagram group, two consecutive server
  states of one workflow differ only in the check-in comment and in two `<Connection>` blocks of
  one module that swapped places (same `moduleOutId`, `ConnectionId` and `StyleSheet
  labelPosition`). An earlier state is byte-identical to the later one apart from the check-in
  comment, so the order flips between two variants. Both verify reports show the same pattern.
- **Every content comparison is order-sensitive.** All comparisons serialize the XML canonically
  (sorted attributes, uniform indentation, volatile elements removed) and then compare bytes or
  hashes; child elements stay in document order. A changed connection order therefore counts as a
  difference in:
  - **Import:** conflict detection, the post-import verification, the check whether a rollback
    restored the previous state, and the fingerprints that guard preview → confirmation,
    `set_active`, restore and the manifest's intended state.
  - **Deployment:** artifact classification (`CHANGED` instead of `UNCHANGED`; not even
    `LAYOUT_ONLY`), the consistency check of release sources, the re-check before import, the
    post-import verification, the rollback/restore check, and the per-artifact fingerprints of the
    deployment ledger.
  - **Export:** the workspace writer rewrites a file whenever its bytes differ, so a re-export with
    a flipped order is committed and reported as `MODIFIED`.
- **Layering.** The comparisons for import and for releases live in two separate archive adapters
  that already duplicate the list of volatile elements; the application layer only sees their
  ports.

## Clarifications

### Session 2026-10-09

- Q: How should exports avoid pseudo-`MODIFIED`? → A: Option B — keep the existing workspace file
  when the re-export is equivalent in the comparison form; never store a re-sorted form.

Naming in this spec: a diagram group `Group-A` with workflows `Workflow-0001`/`Workflow-0002`;
stages `dev` and `int`. Real names stay out of this repository.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Import succeeds when INUBIT reorders connections (Priority: P1)

As an operator I import a changed diagram group to the development server. INUBIT stores the
workflows and, when the server re-exports them for verification, writes some modules' outgoing
connections in a different order than my workspace has them. The import is reported as executed;
no verification mismatch, no rollback, no "rollback failed".

**Why this priority**: This is the failure that blocks work today: an import that INUBIT carried
out correctly is reported as failed, rolled back, and the rollback itself is reported as failed.

**Independent Test**: Run an import against a test server that swaps two connections of one module
when it stores the workflow: the result is `EXECUTED`, no rollback is attempted, no verify report
is written.

**Acceptance Scenarios**:

1. **Given** a workspace workflow and a server that stores it with two connections of one module
   swapped, **When** the operator imports and confirms, **Then** the result is `EXECUTED` and no
   rollback runs.
2. **Given** the server stores the workflow with a really different connection (other target,
   other id, other label position, or a connection missing or added), **When** the operator imports,
   **Then** the result is still `VERIFY_MISMATCH` with a difference report, as today.
3. **Given** a failed import whose rollback re-export comes back with swapped connections, **When**
   the rollback compares the restored state with the backup, **Then** the rollback is reported as
   succeeded.
4. **Given** the server's rendering of a group changes only in connection order between preview and
   confirmation, **When** the operator confirms, **Then** the confirmation is not refused as
   "changed since the preview".

---

### User Story 2 - Deployment to the next stage succeeds with reordered connections (Priority: P1)

As an operator I deploy a release from `dev` to `int`. Source and target servers, and the
verification after the import, may each present the same workflow with a different connection
order. The deployment classifies such workflows as unchanged where they are unchanged, verifies
successfully and does not roll back.

**Why this priority**: Without it, the workflows that already fail on import cannot be promoted to
the next stage either; a failed deployment rolls back node by node.

**Independent Test**: Run a deployment against test servers where the target re-export swaps two
connections: the deployment is `EXECUTED`, no rollback; classification of a workflow that differs
only in connection order is `UNCHANGED`.

**Acceptance Scenarios**:

1. **Given** a release workflow and a target that stores it with swapped connections, **When** the
   deployment verifies the import, **Then** it is not a `VERIFY_MISMATCH` and no rollback runs.
2. **Given** the release workflow and the target workflow differ only in connection order, **When**
   the deployment is planned, **Then** the workflow is classified `UNCHANGED`; if they differ only
   in connection order and layout, it is `LAYOUT_ONLY`.
3. **Given** two source servers of a stage that present a workflow with different connection order,
   **When** the release is discovered, **Then** the sources are consistent.
4. **Given** the target changes only in connection order between preview and execution, **When** the
   deployment runs, **Then** it is not refused as changed since the preview.
5. **Given** a real difference (as in Story 1, scenario 2), **When** the deployment verifies or
   classifies, **Then** it is detected as today.

---

### User Story 3 - Exports stop reporting unchanged workflows as modified (Priority: P2)

As an operator I export a diagram group that nobody changed. The export reports no `MODIFIED`
workflow and creates no workspace commit just because INUBIT wrote the connections in the other
order.

**Why this priority**: Pseudo-changes hide real ones in the workspace history and in previews, but
they do not block work.

**Independent Test**: Export a group, then export it again from a server that now presents two
connections swapped: the second export reports the workflow as unchanged and the workspace history
gains no commit for it.

**Acceptance Scenarios**:

1. **Given** a workspace workflow and a re-export that differs only in connection order, **When** the
   operator exports, **Then** the workflow is not reported as `MODIFIED` and its workspace file is
   not rewritten.
2. **Given** a re-export with a real difference, **When** the operator exports, **Then** the workflow
   is `MODIFIED` and the new rendering is stored exactly as before.

### Edge Cases

- **Connections of several modules reordered at once**: each module is considered separately; the
  result is still equivalent.
- **Modules themselves reordered**: remains a difference (not observed in practice; deliberately
  not generalized).
- **Other children of a module between connections** (e.g. style sheets, configuration elements):
  they keep their positions; only the slots occupied by connections are reordered among
  connections.
- **Non-numeric or missing `moduleOutId` / `ConnectionId`**, or two connections with equal keys:
  ordered by their normalized content instead, so the order is still deterministic.
- **Connections outside a workflow module** (any other place in the document) and **non-workflow
  files** (modules, XSLT, WSDL, repository files): unchanged behaviour.
- **Unparsable XML**: falls back to the existing byte comparison, as today.
- **Fingerprints recorded by an earlier version** (deployment ledger, manifests, open previews):
  after the upgrade they may have been computed over the order-sensitive form; a one-time
  difference for an unchanged node must not block an operation or produce a false conflict.
  Known limitation: if the server's connection order flipped *after* such a value was recorded,
  neither the new nor the legacy fingerprint matches, and a restore of that old backup is refused
  with `CONFLICT` (safe: nothing is changed). Values recorded by 0.5.1 are not affected.
- **Difference reports** (verify, conflict, deploy reports) for real differences: still show the
  content as the server rendered it, so the operator can find the change.

## Requirements *(mandatory)*

### Functional Requirements

**Comparison form**

- **FR-001**: Every comparison that decides whether two renderings of a workflow have the same
  content MUST treat the order of the direct `<Connection>` children of each `<WorkflowModule>` (a
  child of `<Workflow>`) as irrelevant. This covers at least: import conflict detection,
  post-import verification, the rollback/restore state check, the preview → confirmation guards
  (`import_artifacts`, `set_active`, restore), the manifest's intended state, deployment
  classification (`UNCHANGED`, `LAYOUT_ONLY`, `CHANGED`), release-source consistency, the
  deployment re-check, deployment verification and rollback, and the deployment ledger's
  per-artifact fingerprints.
- **FR-002**: For comparison, the connections of a module MUST be put into one deterministic order:
  by `moduleOutId` numerically, then `ConnectionId` numerically; if a value is not numeric or the
  keys are equal, by the normalized text of the connection element. Other children of the module
  MUST keep their positions; the connections take the slots that connections occupied before.
- **FR-003**: Real differences MUST still be detected: a connection added, removed or replaced; a
  different `moduleOutId`, `ConnectionId` or `StyleSheet labelPosition`; any other content change.
- **FR-004**: A different order of the `<WorkflowModule>` elements themselves MUST remain a
  difference.
- **FR-005**: The ordering MUST be implemented once and used by both the import comparisons and the
  release/deployment comparisons (no duplicated logic). The list of volatile elements that both
  already share SHOULD move to the same place.

**Unchanged content**

- **FR-006**: What is imported, deployed, restored or rolled back MUST remain byte-for-byte what it
  is today; the ordering applies to comparison only.
- **FR-007**: Difference reports for real differences MUST keep showing the renderings as they are
  stored (not the comparison form), as today.

**Export**

- **FR-008**: When a re-exported workflow is equivalent in the comparison form to the file already in
  the workspace, the export MUST keep the existing workspace file unchanged (not rewrite it, not
  commit it, not report it as `MODIFIED`). The workspace file therefore always stays a genuine server
  rendering and is never re-sorted. A workflow with a real difference MUST be stored exactly as the
  server rendered it, as today. (Operator decision 2026-10-09, option B; rejected: storing the
  sorted form — the workspace would no longer hold server renderings and imports would send
  re-sorted content; leaving the export unchanged — pseudo-`MODIFIED` would remain.)

**Compatibility**

- **FR-009**: Fingerprints recorded before the upgrade (ledger, manifests, open previews) MUST NOT
  cause a refused operation or a false conflict for content that differs only in connection order.

**Release**

- **FR-010**: The fix MUST be released as version 0.5.1 with a CHANGELOG entry under "Fixed", and
  installed on the operator's workstation (JAR in the local library folder, launcher updated).

### Key Entities

- **Workflow rendering**: the XML of a workflow as the server returns it or as it is stored in the
  workspace; contains workflow modules, each with connections and other children.
- **Connection**: an outgoing edge of a workflow module, identified by its target (`moduleOutId`)
  and its `ConnectionId`, with optional layout (`StyleSheet labelPosition`).
- **Comparison form**: the normalized rendering used only to decide equality and to compute
  fingerprints; never imported, deployed or stored.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Importing a group whose server re-export differs only in connection order ends
  `EXECUTED` in 100 % of test runs, with no rollback and no verify report.
- **SC-002**: A deployment whose target re-export differs only in connection order ends `EXECUTED`
  with no rollback; such workflows are classified `UNCHANGED` (or `LAYOUT_ONLY` when layout also
  differs).
- **SC-003**: Every real difference listed in FR-003 and a reordering of modules is still reported
  in 100 % of test cases.
- **SC-004**: Imported, deployed and restored content is byte-identical to the content before the
  change in every test that compares it.
- **SC-005**: The affected group from the incident can be imported on the development server
  without a verification mismatch after installing 0.5.1.
- **SC-006**: Re-exporting an unchanged group whose connection order flipped
  produces no `MODIFIED` entry and no workspace commit.

## Assumptions

- The phenomenon is limited to the direct `<Connection>` children of workflow modules; no other
  element order has been observed to change, and the fix deliberately does not generalize.
- The test fixtures use existing neutral fixtures and, where useful, an excerpt of the incident's
  workflow anonymized with the repository's neutralization tooling; no customer value is committed
  (public-repository rule, `NoCustomerIdentifiersTest`).
- Version 0.5.1 is a patch release: no new tool, no configuration change; the MCP server is
  restarted by starting a new Claude session after installation.
- The installed version on the workstation is 0.5.0 (the description mentions 0.4.3, which was
  replaced by 0.5.0 earlier on 2026-10-09).
