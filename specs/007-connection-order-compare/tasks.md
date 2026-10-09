---

description: "Task list for feature 007: order-insensitive comparison of workflow connections (0.5.1)"
---

# Tasks: Order-Insensitive Comparison of Workflow Connections

**Input**: Design documents from `specs/007-connection-order-compare/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/ports.md](contracts/ports.md),
[quickstart.md](quickstart.md)

**Tests**: Required (constitution Principle III and the operator's request): every test task is
written and observed to **fail** before the implementation task that follows it.

**Organization**: US1 import, US2 deployment, US3 export. The shared rule and the port changes
(Phase 2) serve all three.

## Ground rules for every task

- Paths are relative to the repository root; Java sources under
  `src/main/java/de/dadecker/inubit/mcp/`, tests under `src/test/java/de/dadecker/inubit/mcp/`.
- **Public repository**: no customer value in any file or commit message. Use neutral names
  (`GRP-01`, `Workflow-0001`, `Module-0002`, …) as the existing fixtures do. Run
  `mvn -q -o test -Dtest=NoCustomerIdentifiersTest -Dsurefire.failIfNoSpecifiedTests=false` after each
  phase that adds test resources.
- **FR-006**: never change what is imported, deployed, restored or written as content; the rule is
  for comparison, fingerprints and the workspace keep-rule only. **FR-007**: difference reports keep
  the stored renderings.
- The ordering rule is exactly contract P-1: connections with plain-decimal `moduleOutId` **and**
  `ConnectionId` first, by (`moduleOutId`, `ConnectionId`) numerically (`BigInteger`); then all
  others; ties and the second group by the `XmlNormalizer` serialization of the connection
  (UTF-8, unsigned byte order). Only direct `Connection` children of a `WorkflowModule` whose parent
  is `Workflow`; other children keep their positions; `WorkflowModule` order stays significant.
- No commits or pushes unless the operator asks.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup

- [X] T001 Set the version in `pom.xml` to `0.5.1` (project version only)
- [X] T002 [P] Add the neutral incident excerpt `src/test/resources/fixtures/v8_1/connection-order/workflow-a.xml` and `workflow-b.xml` (root `Workflow`, as a workspace file): one `Workflow-0001` with two `WorkflowModule` elements (`Module-0010`, `Module-0011`), each with other children (`ModuleId`, `StyleSheet xPos/yPos`) and two `Connection` children — in `workflow-a.xml` `moduleOutId="315"`/`ConnectionId` 63/`labelPosition="49.705882352941174"` before `moduleOutId="171"`/`ConnectionId` 174/`labelPosition="60.833333333333336"`, in `workflow-b.xml` the two swapped; otherwise byte-identical; neutral names only

---

## Phase 2: Foundational (shared rule and ports)

**⚠️ CRITICAL**: blocks all user stories

### Tests

- [X] T003 Write `adapter/archive/v81/WorkflowComparisonTest.java`: two connections swapped → same `normalize(connectionsOrdered(…))`; three connections in all 6 permutations → one result; other children between connections keep their positions (connection slots only); non-numeric `moduleOutId` or missing `ConnectionId` → sorted after numeric ones, by normalized text, deterministic for every permutation; equal keys ordered by normalized text; idempotent; connections of several modules ordered per module; a `WorkflowModule` swap stays different; a `Connection` that is not a direct child of a `WorkflowModule` below `Workflow` is not reordered; archive shape (root `IBISWorkflow/…/WorkflowGroup/Workflow`) handled; `reviewed(root, withoutActive)` drops exactly `CheckinComment`, `LastUpdate`, `WorkflowUId`, `ModuleUId`, `CheckoutUser` (and `IsActive` of a workflow with `withoutActive`)
- [X] T004 [P] Extend `adapter/archive/v81/V81ImportArchivesTest.java`: the T002 pair and a `grp-a.zip` workflow with the two connections of `Module-0002` swapped are `equivalent`; negative: other `moduleOutId`, other `ConnectionId`, other `labelPosition`, one connection missing, one added, `WorkflowModule` order swapped → not equivalent; `connectionOrdered(path, file)` returns equal bytes for the swapped pair and keeps volatile elements (`LastUpdate` still present), returns an unchanged copy for `.xsl`, non-XML and unparsable input
- [X] T005 [P] Extend `adapter/archive/v81/V81ReleaseArchivesTest.java`: swapped pair → `equivalent` true, `canonical` byte-identical, `layoutOnly` false (no difference left); swapped + different `labelPosition` → `equivalent` false, `layoutOnly` true; same negatives as T004 → `equivalent` false and `layoutOnly` false (except label position); `legacyCanonical` equals the pre-change `canonical` (order kept)

### Implementation

- [X] T006 Create `adapter/archive/v81/WorkflowComparison.java` (package-private final, no instances) with the volatile set, `reviewed(Element root, boolean withoutActive)` and `connectionsOrdered(Element)` per contract P-1, until T003 passes
- [X] T007 Switch `adapter/archive/v81/V81ImportArchives.java` to `WorkflowComparison` (remove its `VOLATILE` and `reviewed`); `equivalent` compares `normalize(connectionsOrdered(reviewed(root, false)))`; add `connectionOrdered(path, file)` and declare it in `domain/port/ImportArchivePort.java` with Javadoc per contract P-2, until T004 passes
- [X] T008 Switch `adapter/archive/v81/V81ReleaseArchives.java` to `WorkflowComparison` (remove its `VOLATILE` and `reviewed`); apply `connectionsOrdered` in `canonical`, `equivalent`, `layoutOnly`; add `legacyCanonical(path, file)` (today's canonical) and declare it `@Deprecated` in `domain/port/ReleaseArchivePort.java` ("only for fingerprints recorded by ≤ 0.5.0; remove after 0.5.x"), until T005 passes; adjust any test fake implementing the two ports (search `implements ImportArchivePort|ReleaseArchivePort` in `src/test`)

**Checkpoint**: `mvn -q -o test` green; the rule exists once

---

## Phase 3: User Story 1 — Import succeeds when INUBIT reorders connections (P1) 🎯 MVP

**Goal**: no false `VERIFY_MISMATCH`, no needless rollback, no false `rollback: FAILED`, no false
confirm/restore conflicts.

**Independent Test**: `ImportRollbackTest` scenario with a swapped re-export → `EXECUTED`.

### Tests

- [X] T009 [US1] Extend `application/ImportRollbackTest.java`: `FakeInubit.tamperNextImport` swapping the two `Connection` blocks of `Workflow-0001`/`Module-0002` (match the indented normalized text) → result `EXECUTED`, no rollback, no `.reports/verify-*` file; a real change via tamper (other `ConnectionId`) → `VERIFY_MISMATCH` with report as today; a failing import whose rollback re-export comes back with swapped connections → rollback `SUCCEEDED` (not `FAILED`)
- [X] T010 [P] [US1] Extend `application/ImportConfirmationTest.java`: between preview and confirm the server's group changes only in connection order (`FakeInubit.changeWorkflows` swapping the two blocks) → confirm is not refused; a real change in between → still `CONFLICT`
- [X] T011 [P] [US1] Extend `application/SetActiveTest.java` and `application/RestoreServiceTest.java`: a connection-order flip between preview and confirm does not refuse `set_active`/`restore_backup`; a backup manifest whose `intendedState` was computed the legacy way (over the plain renderings, order differing from the current server) restores without `CONFLICT`; a real change since the call → `CONFLICT` as today

### Implementation

- [X] T012 [US1] In `application/ConflictDetector.java` and `application/ImportService.java`, compute every `ConflictDetector.fingerprint` input over `ImportArchivePort.connectionOrdered` renderings (detect, `set_active`, `requireStateLeftBy`, `Fresh`, `updateManifest`); keep `ConflictDetector.fingerprint` itself unchanged, until T009–T010 pass
- [X] T013 [US1] In `ImportService.requireStateLeftBy`, accept a recorded `intendedState` value equal to the current or the legacy fingerprint (contract P-5), until T011 passes

**Checkpoint**: US1 complete

---

## Phase 4: User Story 2 — Deployment succeeds with reordered connections (P1)

**Goal**: correct classification, consistent sources, verification and rollback without false
mismatches; old recorded fingerprints still accepted.

**Independent Test**: `DeployFailureTest`-style run with a swapped target re-export → `EXECUTED`.

### Tests

- [X] T014 [P] [US2] Extend `application/ReleasePlannerTest.java`: release vs target differing only in connection order → `UNCHANGED`; order + label position → `LAYOUT_ONLY`; other `moduleOutId` → `CHANGED`
- [X] T015 [P] [US2] Extend `application/ReleaseDiscoveryTest.java`: two source nodes presenting a workflow with swapped connections → consistent (no `SOURCE_INCONSISTENT`), same release fingerprint as without the swap
- [X] T016 [P] [US2] Extend `application/DeployFailureTest.java` (pattern `aVerificationMismatchOnNode2IsRolledBackAndVerified`): `FakeServer.tamperNextImport` swapping two connections → `EXECUTED`, no rollback; and `application/DeployExecuteTest.java`: a target change only in connection order between preview and execution (`FakeServer.publishWorkflow` swapping) → not refused
- [X] T017 [P] [US2] Extend `application/RestoreDeploymentTest.java` and `application/DeployServiceTest.java`: a deploy backup manifest and a ledger written with legacy (order-sensitive) artifact states → restore not refused, no `OUTSIDE_CHAIN` warning for content differing only in order; a real outside change → refusal/warning as today

### Implementation

- [X] T018 [US2] Make T014–T016 pass (they should mostly pass after T008; fix any remaining order-sensitive comparison found in `application/ReleasePlanner.java`, `ReleaseDiscovery.java`, `ArtifactClassifier.java`, `NodeDeployer.java`)
- [X] T019 [US2] In `application/NodeDeployer.java` (restore `intendedState` check) and `application/DeployService.java` (`OUTSIDE_CHAIN`), accept a recorded value equal to the current or the legacy fingerprint (legacy via `ReleaseArchivePort.legacyCanonical`, contract P-5), until T017 passes

**Checkpoint**: US1 + US2 complete

---

## Phase 5: User Story 3 — Exports stop reporting unchanged workflows as modified (P2)

**Goal**: an existing workspace workflow that differs only in connection order is kept (decision B),
in all three write paths.

**Independent Test**: `WorkspaceExportTest` re-export with swapped connections → unchanged, no commit.

### Tests

- [X] T020 [P] [US3] Extend `adapter/archive/v81/WorkspaceWriterTest.java`: writing a rendering that differs from the existing file only in connection order keeps the existing bytes; a real difference is written exactly; a new file is written
- [X] T021 [P] [US3] Extend `application/WorkspaceExportTest.java` (pattern `anUnchangedReExportRecordsNothing`, `ExportHarness.rewrite("grp-a.zip", "workflow/workflow.xml", …)` swapping the two connections of `Module-0002`): second export `unchanged()`, snapshot equal, log size 1; with a real change → `MODIFIED`
- [X] T022 [P] [US3] Tests for the write-back paths: in `application/ImportServiceTest.java` (or `ImportRollbackTest.java`) a workflow that the server already has and that differs from the workspace only in connection order (written back as identical) keeps its workspace file and adds no change for it — an actually imported workflow is rewritten as today, because its rendering carries the new check-in comment; in `application/DeployExecuteTest.java` a deployment whose verified rendering has swapped connections does not rewrite the workspace workflow file

### Implementation

- [X] T023 [US3] Keep-rule in `adapter/archive/v81/WorkspaceWriter.write()` next to the existing `onlyTheCommentHistoryGrew` skip, using `WorkflowComparison` (contract P-6), until T020–T021 pass
- [X] T024 [US3] Keep-rule in `application/ImportService.writeBack` and `application/DeployService.commit` using `ImportArchivePort.connectionOrdered`, until T022 passes

**Checkpoint**: all stories complete

---

## Phase 6: Polish, release build and local install

- [X] T025 [P] `CHANGELOG.md`: section `## [0.5.1] - <date>` with "### Fixed" (connection order ignored in import/deploy/export comparisons and fingerprints; real differences still found; recorded fingerprints of ≤ 0.5.0 still accepted; exports keep unchanged files), compare links `[Unreleased]` → `v0.5.1...HEAD`, `[0.5.1]` → `v0.5.0...v0.5.1`; neutral wording
- [X] T026 [P] `docs/tools.md`: one paragraph in the import/deploy comparison section that the order of a workflow module's outgoing connections is ignored when comparing, and that exports keep a workspace file that differs only in that order
- [X] T027 Run `mvn -q clean verify`, then `mvn -q -Dtest=NoCustomerIdentifiersTest test` without `clean` (scans the JAR; must run, not skip)
- [X] T028 Review the implementation against spec FR-001…FR-010 and SC-001…SC-006; list gaps as new tasks
- [X] T029 Local install (operator workstation, outside the repository): copy `target/inubit-mcp-server-0.5.1.jar` to `~/.local/lib/`; back up and update the JAR name in `~/.local/bin/inubit-mcp-<profile>` and in `x-cert-check.serverJar` of `~/.config/inubit-mcp/<profile>.yaml`; verify `--version` 0.5.1, `--check-config` OK, `inubit-cert-check --profile <profile> --check` exit 0, and `get_health` via the start script; keep the 0.5.0 JAR until the operator confirms
- [X] T030 Tell the operator to start a new Claude session; then run quickstart §2 (import of the affected group on the development server) and §3 (two exports) together with the operator
- [X] T031 Ask the operator whether to commit, open a PR and publish a GitHub release 0.5.1

---

## Dependencies & Execution Order

- Phase 1 → Phase 2 → US1 → US2 → US3 → Phase 6. US2 and US3 depend only on Phase 2 and could
  run after it in any order; US1 first as MVP.
- T013 needs T012; T019 needs T008; T024 needs T007.
- T029 after T027; T030 needs the operator.

### Parallel opportunities

- T002 with T001. Phase 2: T004 ∥ T005 (different test files), after T003.
- US1: T010 ∥ T011 after T009. US2: T014–T017 in parallel. US3: T020–T022 in parallel.
- Phase 6: T025 ∥ T026.

## Implementation Strategy

1. MVP: Phases 1–3 fix the blocking import failure on the development server.
2. US2 prevents the same failure on the way to the next stage.
3. US3 removes pseudo-`MODIFIED` noise.
4. Build, install, validate live; release on request.
