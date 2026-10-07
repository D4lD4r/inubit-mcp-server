# Implementation Plan: Stage Chain and Deployment

**Branch**: `005-stage-chain-deployment` | **Date**: 2026-10-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/005-stage-chain-deployment/spec.md`

## Summary

The server learns the **stage chain** (`deploy.from` per group) and gains one tool,
`deploy_release`, that moves a **release** — every diagram group carrying a tag on the source — into
the next group of the chain. `restore_backup` and `run_e2e_test` are widened to target groups.

**Server-enforced sequence.**

Preview (always, the confirmation cannot be turned off):
1. chain check: the source is the target's `deploy.from`;
2. deploy lock per target group, then the workspace lock;
3. release discovery by a tag export on every source node (research D-1, D-4); consistency check;
4. feature-003 checks on the release;
5. per target node: export of the same artifacts, classification (new, changed, layout-only,
   unchanged, excluded, only on target), errors and warnings, diff files (D-5);
6. code bound to the release and every node's state (D-6), valid 30 minutes by default.

Execute (with the code), node by node:
1. repeat discovery and exports; any change → `CONFLICT` before the first node;
2. per node: re-check, backup, `PENDING` audit, imports (repository mode, modules, workflows split
   by active flag, D-7) with the node's own secrets, verification, rollback on failure and stop;
   group-scoped tag; ledger;
3. after all nodes: workspace commit `deploy <target> ← <source>: <tag>`.

Package-only targets get the same preview and, with the code, owner-only import packages instead of
imports (D-10).

## Technical Context

**Language/Version**: Java 21 (unchanged).

**Primary Dependencies**: unchanged; no new dependency.

**Storage**:
- workspace and git history (003), reports under `.reports/deploy-<auditId>/`;
- backups (004, manifest extended with `kind: DEPLOYMENT`);
- `~/.inubit-mcp/<profile>/deployments/` (ledger, locks) and `~/.inubit-mcp/<profile>/packages/`,
  owner-only;
- the audit log.

**Testing**: JUnit 5, AssertJ; `ScriptedProcessLauncher`; a `DeployHarness` with one `FakeInubit`
per node (source and targets) that also answers tag exports, repository exports and repository
imports; neutralized recordings of the D-1 probes; real git in `@TempDir`; opt-in live test against
an approved non-production test target.

**Target Platform**: macOS/Linux (CLI-based).

**Project Type**: single Maven module, shaded JAR.

**Performance Goals**: SC-007, a preview of 5 diagram groups / 20 workflows / 100 modules for 2
target nodes within 3 minutes plus INUBIT's export time.

**Constraints**: never write to a group outside the chain, never to a package-only group, never
delete; no secret outside memory except in backups and packages (owner-only); every call audited;
bounded results; nodes strictly one after another.

**Scale/Scope**: 1 new tool (2 schemas), 2 widened tools; about 20 new production classes
(`DeployConfig`, `StageChain`, `ReleaseDiscovery`, `ReleasePlanner`, `ArtifactClassifier`,
`LayoutDiff`, `StageValueHeuristics`, `DeployService`, `NodeDeployer`, `PackageWriter`,
`DeploymentLedger`, `DeployLock`, repository import/export runners, `DeployReleaseTool`, …), config,
wiring and docs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ⚠️ First writes outside the development stage | ✅ Deployments exist only along a configured, validated, acyclic chain; the source is not an input. Production: `PACKAGE_ONLY` sends nothing; `EXECUTE` there needs the existing production opt-in on every node. The server code is always required (no CLIENT mode) and bound to the release and every node's state, re-checked before each node. Destructive annotation; inputs validated before any call; StartCLI only with argument arrays and `VALUE`/path rules; the empty group list is allowed only in the tag export. Nothing is deleted; a failing node stops the deployment. |
| II. Secrets Stay Out | ⚠️ Packages must carry the target's secrets | ✅ Secrets only from each target node's fresh export, in memory, in the private import ZIP, in owner-only backups and owner-only packages; never in workspace, reports, ledger, audit, results or logs. Key material is never deployed. Leak tests cover packages and the ledger (D-6, D-10). |
| III. Test-First | ✅ | ✅ Every step has a failing test first on the multi-node harness with recorded probe outputs; failure modes are synthetic; the live test needs an approved non-production target. |
| IV. Use-Case-Oriented Tools | ✅ one tool for US1–US5 | ✅ `deploy_release` is task-shaped (release, target); no raw import or tag passthrough. |
| V. Adapter Isolation | ✅ | ✅ Tag export, repository export and import live in `adapter/cli` and `adapter/archive/v81` behind ports; planning and execution in `application`; `PackageBoundaryTest` extended. |
| VI. Bounded Output | ✅ | ✅ Counts and capped lists in results; diffs, summaries and package paths as files. |
| VII. Observability & Auditability | ✅ | ✅ Group-level records per step plus per-node `PENDING`/final records under one `auditId`; package writes, ledger updates and lock refusals audited. |
| Tech constraints | ✅ | ✅ No new dependency, single artifact, English artifacts. |

**Result**: PASS. Both ⚠️ items are resolved by design (D-2, D-6, D-10). No Complexity Tracking
entry.

## Project Structure

### Documentation (this feature)

```text
specs/005-stage-chain-deployment/
├── plan.md
├── research.md                 # D-1…D-16 (D-1: live probes)
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── configuration-delta.md
│   └── mcp-tools-delta.md
├── checklists/requirements.md
└── tasks.md                    # /speckit-tasks
```

### Source Code

```text
src/main/java/de/dadecker/inubit/mcp/
├── config/                 # DeployConfig, ExcludeRule; GroupConfig.deploy; Defaults.deployConfirmationTtl;
│                           # ConfigValidator.checkChain; ConfigSummary chain block and node lines
├── domain/model/           # StageChain, ReleaseRef, Release, ArtifactClass, PlannedArtifact, NodePlan,
│                           # DeploymentPreview, NodeOutcome, DeploymentResult; ErrorCode (+3)
├── domain/port/            # ArtifactPort (+exportRelease, +exportRepository), ImportPort (+importRepository),
│                           # LedgerPort
├── adapter/cli/            # CliCommand (+--exportTag, --exportRepositoryPath, --importRepositoryPath),
│                           # CliExportRunner.exportRelease/exportRepositoryFile, CliImportRunner repository mode
├── adapter/archive/v81/    # RepositoryArchive (relative entries, D-1), ImportAssembler (active flag parameter),
│                           # LayoutDiff (StyleSheet-only differences)
├── application/            # DeployService, ReleaseDiscovery, ReleasePlanner, NodeDeployer, PackageWriter,
│                           # DeploymentLedger, DeployLock; BackupStore (kind, structured scope);
│                           # DevelopmentGuard (restore/e2e on targets); ImportService restore of deployments
├── mcp/tools/              # DeployReleaseTool
└── Wiring.java             # registration with a chain; widened run_e2e_test condition
src/main/resources/schemas/ # deploy_release.{input,output}.json; restore_backup/run_e2e_test descriptions
src/test/…                  # DeployHarness, FakeInubit (+tags, repository), tests per quickstart table
src/test/resources/fixtures/v8_1/cli/  # export_release_*, export_repository_*, import_repository_* (neutralized)
docs/                       # tools.md (deploy_release, widened tools, 3 codes), setup.md (chain), live-tests.md;
                            # research/spike-development-deployment.md (section 10: D-1 facts)
```

**Structure Decision**: same single module. Stages, each reviewed (Creator/Reviewer):

1. **Foundation**: chain configuration, validation and `--check-config` (US6); error codes; CLI
   options and runners for tag export, repository export and repository import with recorded
   fixtures; `FakeInubit` and `DeployHarness`.
2. **Preview (US1, US2, US5)**: discovery, consistency, classification with layout diff, warnings,
   errors, ledger read, diff files, code.
3. **Execute (US1, US3)**: node deployer with backup, ordered imports, verification, rollback, stop
   rule, tag, ledger, commit; `deploy_release` tool.
4. **Package-only (US4)** and **restore of deployments** (FR-022).
5. **E2E on targets** (FR-027), docs, live test and validation.

## Complexity Tracking

None.
