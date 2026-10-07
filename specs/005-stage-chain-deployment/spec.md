# Feature Specification: Stage Chain and Deployment

**Feature Branch**: `005-stage-chain-deployment`

**Created**: 2026-10-07

**Status**: Draft

**Input**: User description: "Feature 005: stage chain and deployment. Implements section 7 (and the
chain parts of sections 3, 8, 9) of the development and deployment design, building on features 003
(workspace, export, check) and 004 (import, restore, set_active, tag on a development stage; tags
exist only per whole diagram group, the owner is always addressed as importing user). Configuration of
the stage chain per group (`deploy.from`, `deploy.mode` EXECUTE | PACKAGE_ONLY, `deploy.exclude`) with
startup rules and the chain in the configuration check; a deploy tool that promotes a release — a tag
on one or more diagram groups of the source group — from the target's source group into the target
group, node by node, with a server-enforced preview and confirmation, backup, import of only new and
changed artifacts with the target's own secrets, verification, rollback per node, stop on the first
failing node, the same tag on the target afterwards, one deployment per target group at a time, a
workspace history entry; system diagrams and excluded artifacts never deployed; stage-specific values
reported, never changed; package-only mode for production. Universal server, installation-specific
chain. Out of scope: deleting artifacts, skipping stages, setting credentials, AS2 tests."

## Overview

Feature 004 lets the assistant develop on a development stage and mark a tested state of a diagram
group with a tag. This feature moves such a tested state — a **release** — up the **stage chain** that
the operator configured, one stage at a time, until the stage before production. For production it
prepares a reviewed package that a person imports.

A deployment is risky in other ways than development:

- The target stages are shared and used for testing by other people and partner systems; a wrong
  deployment disturbs more than one developer.
- Artifacts are matched **by name**; an import silently overwrites what is on the target and creates
  a new version even when nothing changed.
- Secret values are encrypted independently of the installation, and some differ between stages
  (spike: 10 of 212 shared password properties). Carrying the source's values would silently
  overwrite the target's.
- A group can have several nodes that are not synchronised by INUBIT; each is deployed separately.

So the server, not the assistant, enforces the chain, freezes what will be deployed, shows the
difference per node before anything is written, always requires the server-issued confirmation code,
backs up, imports only what differs, verifies and rolls back per node, and audits everything.

This is feature 3 of 3 of the development and deployment design (003 workspace, 004 development on a
development stage, **005 deployment along the stage chain**).

**Actors**

- **Developer/Operator**: asks the assistant to deploy a release to the next stage, reviews the
  preview and approves it with the confirmation code.
- **AI assistant**: calls the deployment tool; cannot bypass the chain, the confirmation or the
  production rules.
- **Release manager / production operator**: receives the package for production and imports it
  themself.
- **Operator responsible for the configuration**: describes the stages, their order and what must
  never be deployed.

**Terminology**

- **Stage chain**: the order of groups given by each group's source (`deploy.from`), e.g. `dev → int
  → qa → acc → prod`. A group without a source never receives deployments.
- **Source / target**: the target is the group being deployed into; its source is the only group it
  may receive from.
- **Release**: the versions of one or more named diagram groups of one owner that carry a given tag on
  the source, together with their modules and repository files.
- **Package**: the frozen content of a release as the preview saw it; what the execute step imports
  (mode EXECUTE) or what is handed to a person (mode PACKAGE_ONLY).
- **Exclusion**: an artifact of the release that is never deployed (system diagrams by default, plus
  the configured rules).

## Clarifications

### Session 2026-10-07

- Q: Are layout-only differences deployed? → A: Yes. A workflow that differs from the release only in layout is imported like a changed one (the target ends up exactly like the release); the preview marks it as layout-only.
- Q: What happens when an artifact was changed directly on the target since it was last deployed (e.g. a hotfix)? → A: Warn and overwrite. Development and deployment stay hybrid: people keep working and deploying in the Workbench, so the server cannot assume that every change went through it. The preview marks such artifacts; after confirmation they are overwritten, and the backup keeps the target's state.
- Q: Where does the active flag of deployed workflows come from? → A: Existing workflows keep the active flag they have on the target; new workflows take the flag of the release (as on the source).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Deploy a tested release to the next stage (Priority: P1)

The developer tagged diagram group "ORDERS" on the development stage with "REL-2026-10-07" after
testing. They ask the assistant to "deploy REL-2026-10-07 of ORDERS to int". The server checks that
int receives from dev, exports the tagged state from dev and the same artifacts from every int node,
and returns a preview per node: which workflows and modules are new, which change, which differ only
in layout, which are excluded, and warnings. The developer approves; the server deploys node by node
with backup, verification and rollback, and sets the same tag on int.

**Why this priority**: it is the core of the feature; every other story refines or protects it.

**Independent Test**: against a fake INUBIT command line with recorded exports for a source group with
one node and a target group with two nodes: preview, execute with the code, and check the sequence of
calls per node, the archives, the tag call, the result and the workspace history entry.

**Acceptance Scenarios**:

1. **Given** a target whose source is the development group and a tag on diagram group ORDERS there,
   **When** the assistant asks for a deployment, **Then** nothing is written and the result is a
   preview per target node (new, changed, layout-only, unchanged, excluded, warnings), the paths of the
   difference files and a one-time code.
2. **Given** a valid preview, **When** the assistant calls again with the code, **Then** each target
   node in turn is backed up, receives only the new and changed artifacts of the release, is verified
   by re-export, and gets the release tag on ORDERS; the result lists the outcome per node.
3. **Given** a workflow that differs on a target node only in layout, **When** the release is
   deployed, **Then** it is imported and the preview marks it as layout-only.
4. **Given** a workflow that is active on the target and inactive on the source, **When** the release
   is deployed, **Then** it stays active on the target; a workflow new on the target takes the flag of
   the release.
5. **Given** an artifact changed on the target in the Workbench since the last deployment, **When** the
   release is previewed, **Then** it is marked "changed on the target outside the chain" with its
   difference, and the deployment overwrites it only after confirmation; the backup keeps it.
6. **Given** a release whose artifacts are all equal on a target node, **When** it is deployed,
   **Then** nothing is imported on that node (no new version) and the node only gets the tag.
7. **Given** a module with a password property, **When** the release is deployed, **Then** each node
   keeps its own value; **given** a node has no value for a password property of the release, **Then**
   the preview reports SECRET_UNRESOLVED for that node and the deployment cannot be executed.
8. **Given** a successful deployment, **When** it completes, **Then** the workspace records the
   verified state of the target group as one history entry "deploy <target> ← <source>: <tag>" and the
   audit log holds the call with every node's result.

---

### User Story 2 - The chain cannot be bypassed (Priority: P1)

The assistant asks to deploy a release from dev directly to acc, or to deploy into a group that has no
source. The server refuses before contacting INUBIT.

**Why this priority**: skipping a stage means deploying something that was never tested where it should
have been; the server must make that impossible.

**Independent Test**: configuration with a chain of three groups; every forbidden combination is
refused without any call to the fake command line.

**Acceptance Scenarios**:

1. **Given** the chain `dev → int → qa`, **When** the assistant asks to deploy into qa from dev,
   **Then** the call is refused with CHAIN_VIOLATION naming the allowed source (int).
2. **Given** a group without a source (e.g. the development group), **When** the assistant asks to
   deploy into it, **Then** the call is refused with CHAIN_VIOLATION.
3. **Given** a tag that does not exist on the source for a named diagram group, **When** the
   assistant asks for a preview, **Then** the call fails with a clear message and nothing is written.
4. **Given** a source group with two nodes whose tagged content differs, **When** the assistant asks
   for a preview, **Then** the call fails with SOURCE_INCONSISTENT naming the differing artifacts.

---

### User Story 3 - A failing node stops the deployment safely (Priority: P1)

The deployment to the first int node succeeds; on the second node the verification shows a difference.
The server rolls that node back from its backup, does not touch any further node, and reports the state
of every node with a way back for the nodes already deployed.

**Why this priority**: with several nodes a half-done deployment is the most likely bad outcome; the
operator must know exactly where each node stands.

**Independent Test**: fake command line that fails the import or verification on the second of three
target nodes.

**Acceptance Scenarios**:

1. **Given** three target nodes and a failure on the second, **When** the deployment runs, **Then**
   node 1 is deployed and tagged, node 2 is rolled back to its backup and verified, node 3 is untouched,
   and the result lists exactly that, with the backup reference of node 1 for a restore.
2. **Given** that the rollback on node 2 also fails, **When** this happens, **Then** the result says so
   explicitly, keeps the backup and names its reference; nothing is deleted.
3. **Given** a deployed node with a backup reference, **When** the assistant restores it, **Then** the
   restore also needs a preview and the server-issued code (also if the group's confirmation would
   otherwise allow client confirmation) and only the artifacts the deployment changed on that node are
   re-imported.
4. **Given** that a target node changed between the preview and the execute call (another import, an
   artifact in edit mode), **When** the execute call reaches that node, **Then** it is not written, the
   deployment stops there with CONFLICT, and the result lists the state of every node.

---

### User Story 4 - Prepare a package for production (Priority: P2)

Production is configured as package-only. The assistant prepares the release for production; the server
produces the same per-node preview as for any other stage, but instead of importing it writes the import
archive, a difference report and the warnings into a protected location for the person who imports it.

**Why this priority**: production must stay in human hands, but the package should be as well checked
as any automated deployment.

**Independent Test**: profile with a production group in package-only mode; the call writes the package
files and never runs an import on any node.

**Acceptance Scenarios**:

1. **Given** a production target in package-only mode, **When** the assistant prepares the release,
   **Then** no import or tag command is ever sent to a production node, and the result names the package
   per node (archive, difference report, warnings) and the artifacts excluded.
2. **Given** the package, **When** a person inspects it, **Then** it contains only the new and changed
   artifacts of the release with the production node's own secret values, and the files are readable
   only by the current user.
3. **Given** a production group configured for direct deployment without the production opt-in, **When**
   the server starts, **Then** it reports a configuration error and does not start.

---

### User Story 5 - Stage configuration never moves (Priority: P2)

System diagrams and the artifacts the operator excluded (for example a diagram group holding the
stage's connection settings) are part of what is tagged on the source but are never deployed. Values
that look stage-specific (host names, URLs, logins) in what is deployed are reported, not changed.

**Why this priority**: the installation keeps stage-specific settings in system diagrams and runtime
properties; overwriting them would break the target silently.

**Independent Test**: a release containing an excluded diagram group, a module matching an exclusion
pattern and a module whose URL differs between source and target.

**Acceptance Scenarios**:

1. **Given** an exclusion for a diagram group, **When** a release including that group is previewed,
   **Then** the preview lists it as excluded and the execute step never sends it.
2. **Given** a changed module whose endpoint URL differs from the target's current value, **When** the
   release is previewed, **Then** the preview lists a warning "stage-specific value?" with artifact and
   property name (no secret values) and the deployment, once confirmed, imports the release unchanged.
3. **Given** a deployed workflow that references a module that is excluded and does not exist on the
   target, **When** the release is previewed, **Then** the preview reports an error for that node and
   the deployment cannot be executed.

---

### User Story 6 - Configure the stage chain (Priority: P3)

The operator describes the chain in the profile and checks it with the configuration check.

**Independent Test**: startup with valid and forbidden chain configurations.

**Acceptance Scenarios**:

1. **Given** no deployment settings, **When** the server starts, **Then** the deployment tool is not
   offered and the server behaves as before.
2. **Given** a source that names an unknown group, a group as its own source, or a cycle, **When** the
   server starts, **Then** it reports a configuration error naming the groups and does not start.
3. **Given** a valid chain, **When** the configuration check runs, **Then** it prints the chain (e.g.
   `dev → int → qa → acc → prod (package only)`), the exclusions and, per node, whether it can receive
   deployments.

---

### Edge Cases

- The tag was moved on the source between preview and execute: the frozen package no longer matches
  the source; the execute call is refused with CONFLICT before any node is written.
- Two deployments into the same target group at the same time, also from two server processes of the
  same profile (e.g. two clients): the second is refused with DEPLOY_LOCKED; a deployment into another
  target group may run in parallel only if it does not share the workspace operation lock.
- A workflow of the release exists on the target under the same name in a **different** diagram
  group: reported as an error for that node in the preview (INUBIT matches by name and would move it).
- A workflow or module exists on the target but not in the release: listed as "only on target"; it is
  never deleted or changed.
- A module used by the release is also used by a workflow on the target outside the release: listed as
  a warning ("shared module, also used by …") because the deployment changes it for both.
- The release contains a workflow in edit mode on the source: irrelevant (the tagged version is
  exported); a target workflow in edit mode: CONFLICT for that node.
- The confirmation code has expired or belongs to another preview: refused, nothing written.
- A target node is not reachable during the preview: the preview fails for the whole deployment (no
  partial preview can be confirmed).
- The re-export after an import fails because INUBIT broke the diagram group (spike: missing module):
  failure, rollback, verification of the rollback.
- The release is large: preview details are written to workspace files; the result stays bounded.
- The target group is the development group of feature 004 (a chain may deploy into a group that also
  has development enabled): allowed; the conflict rules of this feature apply.

## Requirements *(mandatory)*

### Functional Requirements

**Configuration**

- **FR-001**: The profile MUST allow each group to name exactly one source group (`deploy.from`); a
  group without a source MUST NOT receive deployments. The source MUST be an existing other group and
  the chain MUST be free of cycles; violations MUST be configuration errors at startup.
- **FR-002**: The profile MUST allow per target group the mode `EXECUTE` (default) or `PACKAGE_ONLY`.
  `EXECUTE` on a production group MUST additionally require the existing production write opt-in;
  without it the server MUST NOT start.
- **FR-003**: The profile MUST allow per target group exclusion rules by diagram group and by
  workflow or module name pattern, in addition to the default exclusion of system diagrams.
- **FR-004**: Deployments MUST always use the server-issued two-step confirmation; there MUST NOT be a
  setting to turn it off.
- **FR-005**: The configuration check MUST print the chain, the mode of each target, its exclusions,
  and per node whether it can receive deployments.
- **FR-006**: The deployment tool MUST be offered only if at least one group has a source, and MUST
  have a stated use case and documented input and output (Constitution IV).

**Preview**

- **FR-007**: The assistant MUST be able to request a deployment by naming the target group, the tag
  and one or more diagram groups of one owner (default: the profile's inventory owner); empty, blank or
  wildcard-like diagram group names MUST be refused before anything is contacted.
- **FR-008**: The server MUST refuse with CHAIN_VIOLATION, before contacting INUBIT, any target without
  a source; the source is always the target's configured source and cannot be chosen by the caller.
- **FR-009**: The server MUST export the tagged state of the named diagram groups from every node of
  the source group; if their content differs, the call MUST fail with SOURCE_INCONSISTENT naming the
  differing artifacts. A tag missing on any named diagram group MUST fail the call.
- **FR-010**: The server MUST export the same artifacts (by name) from every target node and classify
  each artifact per node as new, changed, layout-only, unchanged, only on target, or excluded, and
  collect warnings (stage-specific values, shared modules, secrets without counterpart). Layout-only
  differences MUST be marked in the preview and deployed like changed artifacts.
- **FR-011**: The preview MUST freeze the package (content hash of the release and of each target
  node's state), write the differences per node to workspace files, and return a bounded summary, the
  file paths and a one-time code bound to target, tag, diagram groups, owner, package hash and target
  states.
- **FR-012**: A secret placeholder of the release without a value on a target node MUST be reported as
  SECRET_UNRESOLVED for that node; such a preview MUST NOT be executable. A workflow of the release
  referencing a module that is neither deployed nor present on the target MUST be reported as an error
  that makes the preview not executable.
- **FR-013**: Changes made on the target outside this server (for example a hotfix in the Workbench or a
  manual deployment) MUST NOT block a deployment: the preview MUST mark every changed artifact whose
  target version was not written by a deployment of this server with the warning "changed on the
  target outside the chain" and show its difference; after confirmation it is overwritten, and the
  backup keeps the target's state. A target workflow in edit mode MUST still be a CONFLICT.

**Execute**

- **FR-014**: With a valid code, the server MUST deploy the target nodes one after another. Before
  writing a node it MUST re-check that the source still holds the frozen release and the node still
  holds the state of the preview; any difference or a target workflow in edit mode MUST stop the
  deployment at that node with CONFLICT.
- **FR-015**: Per node, the server MUST back up the affected artifacts (as in feature 004), import only
  the new and changed artifacts of the release unchanged except for secret values, which come from that
  node in memory only, verify by re-export, and on any failure roll the node back from its backup and
  verify the rollback.
- **FR-016**: A deployed workflow that already exists on the target node MUST keep that node's active
  flag; a new workflow MUST take the active flag of the release. The preview MUST show the resulting
  flag per workflow.
- **FR-017**: If a node fails, the server MUST NOT touch the remaining nodes; the result MUST list the
  state of every node (deployed, rolled back, rollback failed, not started) and the backup reference
  of every node that was written.
- **FR-018**: After a node is verified, the server MUST set the release tag on the deployed diagram
  groups of that node, scoped to those diagram groups as in feature 004 (never owner-wide; an existing
  tag name is reused), and verify it; a tag failure MUST keep the deployment and be reported with a
  retry hint.
- **FR-019**: The check-in comment of the new versions MUST name the release tag and the source group.
- **FR-020**: After the deployment the workspace MUST record the verified state of the target group as
  one history entry "deploy <target> ← <source>: <tag>"; secret values MUST NOT reach the workspace.
- **FR-021**: Only one deployment per target group MAY run at a time, also across several server
  processes of the same profile; a second one MUST be refused with DEPLOY_LOCKED.
- **FR-022**: The backup restore of feature 004 MUST work for nodes written by a deployment; on a target
  that is not a development stage it MUST always need a preview and the server-issued code.

**Package-only**

- **FR-023**: For a target in mode `PACKAGE_ONLY` the server MUST run the same preview and, with the
  code, write per node an import archive (only new and changed artifacts, the node's own secret
  values), a difference report and the warnings to a location readable only by the current user, and
  return their paths; it MUST NOT send any import, tag or other writing command to that group.

**General**

- **FR-024**: Every deployment call — refused, previewed, executed, failed, packaged — MUST be audited
  with target, source, tag, diagram groups, per-node result, backup references and affected artifact
  names; never content or secret values.
- **FR-025**: The deployment tool MUST be marked as destructive and not idempotent; results MUST be
  bounded with details in files.
- **FR-026**: Nothing in this feature may delete an artifact, change a value of the release, skip a
  stage, or write to a production group in mode `PACKAGE_ONLY`.

**End-to-end tests on target stages**

- **FR-027**: The SOAP end-to-end test of feature 004 MUST also be available on nodes of non-production
  groups that receive deployments, governed by their end-to-end policy (free, confirm, forbidden by
  default), so a deployed release can be tested on the target; production MUST stay forbidden.

### Key Entities

- **Deployment settings**: per group — source group, mode, exclusion rules.
- **Stage chain**: derived from the settings; validated at startup and printed by the configuration
  check.
- **Release**: target, source, owner, tag, diagram groups; the tagged content of the source with its
  content hash.
- **Node plan**: per target node — classification of each artifact, warnings, errors, the node's state
  hash, difference files.
- **Package**: the frozen release plus the node plans; bound to the confirmation code.
- **Deployment run**: per node — backup reference, import, verification, rollback, tag outcome; the
  overall result and the audit record.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In 100 % of the offline scenarios, no writing command reaches any node when the chain is
  violated, the source is inconsistent, a secret is unresolved, a referenced module would be missing,
  the code is missing or invalid, or the target is in package-only mode.
- **SC-002**: In 100 % of the offline failure scenarios, every node ends in one of the states deployed,
  rolled back, rollback failed (named) or not started, and the result lists each node's state
  correctly.
- **SC-003**: Re-deploying an already deployed release imports nothing and creates no new version on
  any node.
- **SC-004**: No secret value of the source reaches any target node, the workspace, a package of
  another node, the audit, results or logs (verified for every synthetic secret form of feature 003).
- **SC-005**: Excluded artifacts and system diagrams are never sent, verified for every exclusion rule
  kind.
- **SC-006**: On the live stages (opt-in), a tagged test diagram group is deployed from the development
  group to the next group's test artifacts, verified and restored, with no change to any other
  artifact.
- **SC-007**: The preview of a release with up to 5 diagram groups, 20 workflows and 100 modules for a
  target group with 2 nodes completes within 3 minutes plus INUBIT's own export time.

## Assumptions

- Features 003 and 004 are in place: workspace, checks, redaction, archive rebuild, backup, rollback,
  verification and group-scoped tagging.
- Artifacts are stage-independent; stage-specific settings live in system diagrams or are set at
  runtime. INUBIT's own deployment mechanism for stage-specific properties is not used.
- The server can read (export) every node of every chained group, including production, with the
  credentials configured for it; writing needs the settings of this feature.
- The release is tagged on the source by feature 004 (development group) or by a previous deployment
  (other groups); a tag set by a person in the Workbench works the same way.
- The owner of the artifacts is the same on every stage (default: the profile's inventory owner).
- Work stays hybrid: people keep developing, tagging and deploying in the Workbench; the server neither
  requires nor assumes that every change went through it.
- Deleting artifacts, skipping stages, setting credentials and AS2 end-to-end tests are out of scope.
- Several nodes of a group are deployed one after another, never in parallel.
