# Data Model: Stage Chain and Deployment (feature 005)

Additions to the models of features 003 and 004. Decisions: [research.md](research.md).

## Configuration

| Entity | Fields | Rules |
|---|---|---|
| `DeployConfig` (component of `GroupConfig`) | `from: String`, `mode: EXECUTE \| PACKAGE_ONLY` (default `EXECUTE`), `exclude: List<ExcludeRule>` | group-level only; validated by `ConfigValidator.checkChain` (D-2) |
| `ExcludeRule` | exactly one of `diagramGroup: String`, `name: Glob`, `repositoryPath: Glob` | non-blank; globs use `*` (one segment) and `**` (any) |
| `Defaults.deployConfirmationTtl` | `Duration`, default `PT30M` | positive, at most `PT2H` |
| `StageChain` (derived) | `targets: Map<GroupId, ChainLink>`; `ChainLink(source: GroupId, mode, exclude)` | acyclic; `render()` gives `dev → int → … (package only)` |

## Release and plans

| Entity | Fields | Notes |
|---|---|---|
| `ReleaseRef` | `target: GroupId`, `source: GroupId`, `owner`, `tag` | `tag` matches the CLI `VALUE` pattern, not blank, no wildcard |
| `Release` | `ref`, `diagramGroups: SortedSet<String>`, `files: SortedMap<WorkspacePath, Content>` (rendered, placeholders), `fingerprint`, `olderThanHead: List<String>` | rendered from the tag export of each source node; equal on all source nodes (D-4) |
| `ArtifactClass` | `NEW`, `UNCHANGED`, `LAYOUT_ONLY`, `CHANGED`, `EXCLUDED`, `ONLY_ON_TARGET` | D-5 |
| `PlannedArtifact` | `ref: ArtifactRef` (workflow, module or repository file), `class`, `activeFlag?` (workflows: resulting flag), `outsideChain: boolean` | |
| `NodePlan` | `node`, `artifacts: List<PlannedArtifact>`, `warnings: List<Warning>`, `errors: List<PlanError>`, `targetFingerprint`, `diffFile`, `summaryFile` | executable iff `errors` is empty |
| `Warning` | `kind: STAGE_SPECIFIC_VALUE \| SHARED_MODULE \| OUTSIDE_CHAIN \| OLDER_THAN_HEAD`, `artifact`, `detail` (names only) | values only in the diff file |
| `PlanError` | `code: ErrorCode` (`CONFLICT`, `SECRET_UNRESOLVED`, `PRECONDITION_FAILED`, …), `artifact`, `message` | |
| `DeploymentPreview` | `release`, `plans: List<NodePlan>`, `previewState` (D-6), `code?`, `expiresAt?` | a code only if every plan is executable |

## Execution

| Entity | Fields | Notes |
|---|---|---|
| `NodeImportSet` | `repository: List<RepositoryFile>`, `modules: List<ArtifactRef>`, `workflowsActive`, `workflowsInactive` | each non-empty part becomes one import (D-7) |
| `NodeOutcome` | `node`, `state: DEPLOYED \| ROLLED_BACK \| ROLLBACK_FAILED \| NOT_STARTED \| PACKAGED \| UNCHANGED`, `backupRef?`, `imported: List<String>`, `created: List<String>`, `tag: TagOutcome?`, `failure?: {code, step, message}`, `packageDir?` | |
| `DeploymentResult` | `auditId`, `target`, `source`, `tag`, `outcome: EXECUTED \| FAILED \| PACKAGED`, `nodes: List<NodeOutcome>`, `commit?`, `reports`, `warnings` | bounded by `resultLimits` |

State per node:

```text
NOT_STARTED ──(fingerprint ok, backup, PENDING)──▶ importing ──(verified)──▶ DEPLOYED ──▶ tag (applied | failed)
                         │                              │
                         └──(CONFLICT: stop)            └──(failure)──▶ rollback ──▶ ROLLED_BACK | ROLLBACK_FAILED (stop)
UNCHANGED: nothing to import (only the tag)        PACKAGED: package-only target
```

## Persistence

| Entity | Location | Content |
|---|---|---|
| Backup (extended) | `~/.inubit-mcp/<profile>/backups/<auditId>-<n>.zip` + `<auditId>.json` | manifest gains `kind: IMPORT \| DEPLOYMENT`, `groups`, `repositoryPaths`, `tag`, `source`; 004 manifests read as `IMPORT` |
| `DeploymentLedger` | `~/.inubit-mcp/<profile>/deployments/ledger.json` | `node → artifact path → {fingerprint, auditId, tag, at}`; no content, no secrets (D-8) |
| Deploy lock | `~/.inubit-mcp/<profile>/deployments/<group>.lock` | OS file lock (D-11) |
| Package | `~/.inubit-mcp/<profile>/packages/<auditId>/<node>/` | import archives (secrets of the node), `diff.txt`, `warnings.txt`, `README.md`; owner-only (D-10) |
| Reports | `<workspace>/.reports/deploy-<auditId>/` | `source.diff`, `<node>.diff`, `<node>.txt`, verify and rollback diffs; placeholders only |
| History | workspace git | `deploy <target> ← <source>: <tag> [<auditId>]`, trailer `Server-State: <target>` (D-12) |
