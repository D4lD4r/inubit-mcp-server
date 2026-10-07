# Research: Stage Chain and Deployment (feature 005)

Decisions for the plan. They build on the decisions of feature 003 (`specs/003-artifact-workspace/
research.md`) and feature 004 (`specs/004-development-stage/research.md`, D-1…D-26). Live facts come
from the spike (`docs/research/spike-development-deployment.md`) and from the probes of D-1 below.

## D-1 Live probes for repository files and releases (2026-10-07)

Probed on the development node, in the person's personal repository area and personal test
diagram group only (approved by the user), with StartCLI 8.1.17:

- **Repository export**: `export --exportRepositoryPath /Root/<owner>[/<dir>] --exportFile <zip>`
  returns `Root/<owner>/<path>/<name>.xml` (metadata: `Property type="RepositoryFile"`, `uuid`,
  `path`, `version`, `contentMD5`, `contentSize`, `modified`, `versionComment`, `Description`, and
  `tagName` in a tagged export) and `<name>.dat` (content). A path that does not exist fails with
  `Internal INUBIT error … Path not found` (exit 1).
- **Repository import**: `import --importFile <zip> --importRepositoryPath /Root/<owner>` creates or
  updates files. The archive entries are taken **relative to the import path**: entries in the
  export shape (`Root/<owner>/…`) were stored as `/Root/<owner>/Root/<owner>/…` (one stray probe
  folder remains in the personal area; nothing outside it was touched). There is **no protocol**
  (only `n-OK: Imported successfully`). The `uuid` of the archive is ignored on create and stays
  stable afterwards; `Description` is taken; the `versionComment` of the archive is ignored and the
  server comment grows by a `DefaultCommitCommentImport@@@` prefix on every import; every import
  creates a new version (`1.0` → `1.1` with changed content, → `1.2` unchanged).
- **Workflow and module imports ignore `Repository.zip`**: a module archive and a workflow archive
  that carried a changed repository file in `Repository.zip` were imported (`was modified`), and the
  repository file stayed unchanged. Repository files can only be written in repository mode.
- **References**: a workflow-group export puts into `Repository.zip` the repository files that its
  modules reference by `inubitrepository:/Root/<owner>/<path>` in an `xsl:import`/`xs:import`; a
  string literal with the same URI in an XSLT variable was **not** treated as a reference.
- **Tags**: `tag --tagMove <tag> --tagUser <owner> --tagWorkflowType technical --tagWorkflowGroup
  <group>` also tags the **referenced repository files** of the group (`tagName` in a tagged
  repository export); unreferenced files of the same folder are not tagged. `tag --tagMove <tag>
  --tagRepositoryPath <path>` tags a repository path directly; with `--tagUser` it fails ("Cannot
  specify user when repository is tagged").
- **Release export**: `export --exportWorkflowUser <owner> --exportWorkflowType technical
  --exportWorkflowGroup '' --exportTag <tag>` returns **only** the diagram groups that carry the tag,
  in their tagged versions, with the tagged versions of their modules and of their referenced
  repository files (`Repository.zip` held version 1.0 of a file whose head was 1.1). The check-in
  comments of untagged diagram groups of the owner were **not** touched (a control group's comment
  grew only by its own two exports).

## D-2 Configuration

**Decision**: a group-level record `deploy` (never on nodes; a group is deployed as a whole):

```yaml
defaults:
  deployConfirmationTtl: PT30M   # default 30 min, at most PT2H
groups:
  - name: int
    deploy:
      from: dev                  # required in the record; an existing other group
      mode: EXECUTE              # EXECUTE (default) | PACKAGE_ONLY
      exclude:                   # optional, in addition to "system diagrams are never read"
        - diagramGroup: GRP-SYS
        - name: "CFG_*"          # glob on workflow and module names
        - repositoryPath: "/Root/*/stage/**"   # glob on repository paths
```

Startup rules (`ConfigValidator.checkChain`, errors unless noted): `from` names an existing group
other than itself; the chain is acyclic; `mode: EXECUTE` on a `production: true` group needs
`write.productionOptIn: true` effective on **every** node of that group; every node of a target and
of its source needs a StartCLI installation (`cli.home`); an `exclude` entry has exactly one key and
a non-blank value; `deployConfirmationTtl` is positive and at most `PT2H`. `production` keeps its
existing rules (no `development`, `e2eTests` only `FORBIDDEN`). A target group may also be a
development group.

`--check-config` prints a `Chains:` block with one line per chain, e.g. `dev → int → qa → acc →
prod (package only)`, then per target with exclusions `<group> excludes: diagramGroup GRP-SYS,
name CFG_*, …`; each node of a target group gets `deploy: from dev (EXECUTE)` or `deploy: from acc
(PACKAGE_ONLY)` in its line. A chain that cannot be derived (an error of the chain rules) is not
drawn (`Chains: not shown, see the errors`).

**Alternatives**: node-level `deploy` — rejected: the chain and the release are group concepts;
nodes are deployed one after another (spec).

## D-3 Tool `deploy_release`

**Decision**: one tool, `deploy_release`, registered when at least one group has `deploy`. Input:
`target` (group id; a node id is refused), `tag`, optional `owner` (default `inventory.owner` of the
target), optional `confirmationCode`. Without a code it always returns the preview and a code
(confirmation cannot be turned off, FR-004); with a code it executes (mode EXECUTE) or writes the
package (mode PACKAGE_ONLY). Annotated destructive, non-idempotent. The source is always
`deploy.from` of the target and is not an input (FR-008).

**Alternatives**: separate `preview_deployment` / `execute_deployment` tools — rejected: the 004
tools use the code in the same tool; one tool keeps the surface small (Constitution IV).

## D-4 Release discovery and source consistency

**Decision**: per source node, one release export (D-1: owner-wide `--exportTag` with an empty group
list; new `CliExportRunner.exportRelease(owner, tag)` — the only call in which an empty group list
is allowed, and only together with a validated tag). The export is rendered in memory with the
feature-003 codec (secrets become placeholders; volatile values go to `.meta`). The release =
diagram groups, workflows, modules and repository files found. A tag on no diagram group →
`NOT_FOUND`. Source consistency: the rendered files (without `.meta`) of every source node must have
the same fingerprint (`ConflictDetector.fingerprint`); otherwise `SOURCE_INCONSISTENT` with the
differing paths in `.reports/deploy-<auditId>/source.diff`. If a tagged workflow version is older
than the head of its diagram group on the source (head `Version` of a normal group export vs. the
tagged one), the preview notes it.

**Alternatives**: per-group history exports to find tagged groups — rejected: an owner-wide history
export appends to the check-in comment of every workflow (feature 004 D-26); the tag export does not
(D-1).

## D-5 Target state and classification

**Decision**: per target node, export the release's diagram groups (head, `exportWorkflowGroup`; a
missing group = all new), every release module not contained in those exports
(`exportModule`, missing = new) and every release repository file (`exportRepositoryPath` per file,
missing = new); render them with the codec. Per artifact and node:

| Class | Rule |
|---|---|
| `NEW` | not on the target |
| `UNCHANGED` | rendered files equal (placeholders compared as placeholders) |
| `LAYOUT_ONLY` | a workflow whose only differences are inside `<StyleSheet>` elements (`xPos`, `yPos`, `labelPosition`, …) — deployed (clarification) |
| `CHANGED` | any other difference |
| `EXCLUDED` | matches a `deploy.exclude` rule — never sent |
| `ONLY_ON_TARGET` | in a release diagram group on the target but not in the release — never touched |

Errors that make the node plan not executable: a release workflow name that exists on the target in
**another** diagram group (REST model list of the owner); a module name that exists with another
plugin type; a release workflow referencing a module that is excluded or missing in the release and
missing on the target; a placeholder without a target value (`SECRET_UNRESOLVED`); a key-material
or certificate repository file missing on the target (`SECRET_UNRESOLVED`; key material is never
deployed, one that exists on the target needs nothing); a referenced repository file **outside**
`/Root/<owner>/` (another owner's area, never part of the release: `EXCLUDED` with the warning
`OUTSIDE_OWNER_REPOSITORY`) that is missing on the target (`PRECONDITION_FAILED` naming the path);
a target workflow in edit mode (`CONFLICT`); any ERROR of the feature-003 checks on the release
files (FR-012a).

Warnings: `stage-specific value?` for a changed module or workflow property whose release and target
values differ and whose name or value looks like a host, URL, port or login (names only in the
result, values in the diff file); `shared module` when a changed module is also used by target
workflows outside the release (module usage of feature 001, REST, no export); `changed on the target
outside the chain` (D-8); `tagged version older than head` (D-4).

## D-6 Package, code and preview state

**Decision**: the preview writes per node a diff (`.reports/deploy-<auditId>/<node>.diff`) and a
summary (`<node>.txt`) and returns bounded counts per class, the warnings and errors, the resulting
active flag per workflow, and the diagram groups found. The code (`WriteChallengeRegistry`, new
capability `DEPLOY_RELEASE`, keyed by the **target group**) is issued only if every node plan is
executable; it binds target, tag, owner and the preview state
`release=<fingerprint>;groups=<sorted list>;<node>=<fingerprint>…`. Its lifetime is
`deployConfirmationTtl` (D-2). The execute call repeats discovery and target exports and requires
the identical preview state, otherwise `CONFLICT` before any node is written (FR-014: tag moved on
the source, target changed, new edit mode).

## D-7 Execution per node

**Decision**: nodes in configuration order, one at a time. Per node:

1. re-check the node's fingerprint (just before writing; a difference → `CONFLICT`, stop);
2. backup: the raw target exports of step D-5 plus the raw repository exports → `BackupStore`
   (manifest kind `DEPLOYMENT`, groups, artifact names, repository paths, intended hashes);
3. a `PENDING` audit record for the node (fail closed);
4. imports, each only with the artifacts that are `NEW`, `CHANGED` or `LAYOUT_ONLY`:
   1. repository files: repository mode, `--importRepositoryPath /Root/<owner>`, entries relative to
      it (D-1); key material and certificates are never part of it, nor are files outside
      `/Root/<owner>/` (stage 2 ruling, review #3);
   2. modules whose workflows are not imported: module archive (004 `ImportAssembler`, mode MODULE);
   3. workflows with their new or changed modules: one workflow archive per resulting active flag
      (`--importWorkflowActive` / `--importWorkflowInactive`), at most two;
   each with the target's secret values in memory (004 D-6) and protocol matching (004 D-8; the
   repository import has no protocol and is checked by the verification);
5. verification: re-export as in D-5; every imported artifact must equal the release file, except
   check-in comments (D-9) and the target's secret values; the active flag must be the intended one;
6. on any failure after step 4 started: rollback of the node from its backup (repository files
   re-imported from the backup too), verification of the rollback, stop;
7. on success: tag the release's diagram groups on the node (004 `DiagramGroupTagger`, group-scoped;
   it also tags the referenced repository files, D-1); a tag failure keeps the deployment
   (`tag.applied: false` with retry hint);
8. update the deployment ledger (D-8).

The active flag (clarification): an existing workflow keeps the node's `IsActive`; a new workflow
takes the release's. `ImportAssembler`'s rule "a new workflow must be inactive" (004) becomes a
parameter: 004 callers keep it; deployments pass the intended flag.

**Alternatives**: one archive with all workflows — rejected: the active flag is a per-import option
(spike §5), so mixed flags need two imports.

## D-8 Changes made outside the chain

**Decision**: a deployment ledger `~/.inubit-mcp/<profile>/deployments/ledger.json` (owner-only)
records per node and artifact the fingerprint of the rendered file written by the last verified
deployment of this server. In the preview, a `CHANGED` or `LAYOUT_ONLY` artifact whose current
target fingerprint differs from the ledger entry (or has none) carries the warning `changed on the
target outside the chain`; it is overwritten after confirmation and kept by the backup
(clarification: hybrid work). The ledger holds hashes and names only, never content or secrets.

**Alternatives**: deriving it from backup manifests — rejected: retention removes them after 30 days.

## D-9 Check-in comment

**Decision**: the 004 shape `DefaultCommitCommentImport###<reason>###@@@Deploying User: …@@@`
with the reason `deploy <tag> from <source>`; repository files get no comment (INUBIT ignores it,
D-1). Verification checks the reason segment of imported workflows and modules.

## D-10 Package-only

**Decision**: with the code, a `PACKAGE_ONLY` target writes per node into
`~/.inubit-mcp/<profile>/packages/<auditId>/<node>/` (directories `rwx------`, files `rw-------`):
the import archives of D-7 step 4 (with the node's own secret values — the reason for the private
location), `diff.txt`, `warnings.txt` and `README.md` with the StartCLI commands in the order of
D-7. No import, tag or other writing command is sent to that group; the preview reads every node of
it (clarification). Packages follow the backup retention (30 days, newest per target kept).

## D-11 Locks

**Decision**: a per-target-group file lock `~/.inubit-mcp/<profile>/deployments/<group>.lock`
(the `WorkspaceLock` mechanism: `FileChannel.tryLock`, works across processes of the profile),
taken first, busy → `DEPLOY_LOCKED`; then the workspace lock (busy → `PRECONDITION_FAILED` "busy",
as in 004), held for the preview and the execute call.

## D-12 Workspace history

**Decision**: after a deployment in which every node was verified, the verified rendered state of
the release artifacts on the target is written to `<target group>/<owner>/…` and committed as
`deploy <target> ← <source>: <tag> [<auditId>]` with the trailer `Server-State: <target group>`.
After a partial deployment nothing is committed (the nodes differ); the result says so, and the next
export records the state.

## D-13 Restore after a deployment

**Decision**: `restore_backup` accepts a backup of a deployment for its node: the guard admits a
node that is a development node **or** a node of a target group whose backup manifest kind is
`DEPLOYMENT`; for the latter the preview and server code are always required (FR-022), regardless of
`development.confirmation`. The restore re-imports the backed-up state of the artifacts and
repository files the deployment changed (the same archives as D-7, built from the backup) and
verifies it; created artifacts stay (no delete) and are listed. `BackupStore.Manifest` gains
`kind` and structured scope fields; 004 manifests stay readable (kind `IMPORT`).

## D-14 End-to-end tests on target stages

**Decision**: `run_e2e_test` admits a node of a non-production group that has `deploy` (receives
deployments) or `development.enabled`, governed by its `e2eTests` policy; registration condition
becomes "any such node with `e2eTests` ≠ `FORBIDDEN`". Production stays forbidden (startup rule).

## D-15 Errors and audit

**Decision**: new `ErrorCode`s `CHAIN_VIOLATION`, `SOURCE_INCONSISTENT`, `DEPLOY_LOCKED`; existing
`CONFLICT`, `SECRET_UNRESOLVED`, `IMPORT_FAILED`, `VERIFY_MISMATCH`, `NOT_FOUND`, `PRECONDITION_FAILED`,
`CONFIRMATION_INVALID`, `PRODUCTION_PROTECTED`. Audit: one group-level record per step (capability
`deploy_release`, node = target group id, inputs: tag, source, owner, groups, package hash, code
hash) and per node `PENDING` → `EXECUTED`/`FAILED`/`PACKAGED` records with the shared `auditId`,
backup reference, artifact names (capped) and rollback and tag outcome.

## D-16 Tests and fixtures

**Decision**: a `DeployHarness` with several `FakeInubit` instances (one per node, source and
target) behind the real 8.1 adapters on `ScriptedProcessLauncher`; `FakeInubit` learns tag exports,
repository export/import and per-import active flags. Recorded fixtures from the probes of D-1
(neutralized with `tools/neutralize.py` / synthetic names): repository export, tagged release
export, repository import output, the "Path not found" error. Configuration tests for every
forbidden chain combination; leak tests for packages, ledger and audit; a live test (opt-in) that
deploys a tagged personal test group from the development node into a second chained group only if
the operator names a dedicated test target (never production).

## D-16 addendum: rulings of stage 1 (Foundation, T001–T013)

Smallest design-conforming choices where the tasks met the code (2026-10-07):

- **T001 fixtures**: all five cases are synthetic (the raw probe outputs stay private). The
  success texts `1-OK: Repository path exported successfully.` and `1-OK: Imported successfully`
  and the refusal `Cannot specify user when repository is tagged.` are also string constants of
  the 8.1.17 client's StartCLI command classes; the error layout of `export_repository_not_found`
  and `tag_repository_user_refused` follows the recorded `export-group-missing` (not byte for
  byte). The client also knows `2-NOK: No workflow group containing workflows for export found.`;
  the release export treats it like an archive without workflows (`NOT_FOUND`, not probed).
- **T003**: the three codes are listed in the error table of `docs/tools.md` already, because
  `DevelopmentToolsReferenceTest` requires every code to be documented; T029 describes them with
  `deploy_release`.
- **T005**: the plan's `exportRepositoryFile` is `CliExportRunner.exportRepository(path)` (a file
  or a folder), as in tasks.md; `CliOutputClassifier` maps `Path not found //ibis:Root…` to
  `NOT_FOUND`.
- **T006**: `RepositoryArchive.build(owner, files)` writes a directory entry before the first
  file of each folder (the probed import shape), keeps the exported metadata (it must name the
  same path) and refuses key material with `PRECONDITION_FAILED` as the last guard (the planner
  reports a missing key file as `SECRET_UNRESOLVED`, D-5). `ImportPort.importRepository` returns
  nothing: there is no protocol; the deployer verifies by a repository export.
- **T008**: `ImportAssembler.Request.newWorkflowFlag` (`MUST_BE_INACTIVE` for the 004 constructor,
  `FROM_RELEASE`); `Assembled.active()` reports the intended flag per workflow; with
  `FROM_RELEASE` the archive states it (an absent `IsActive` is inserted after
  `CheckinComment`). `ImportArchivePort` is extended when the deployer needs it (T021).
- **T010**: `FakeInubit` models one diagram group per server; a release of several groups (e.g.
  SC-007, T028) needs an extension. A release export of an unknown tag returns an archive with
  an empty `<Workflows/>`.
- **T011/T013**: `DeployMode` lives in `domain.model` (the derived `StageChain` needs it and the
  domain depends on nothing else); `StageChain.Exclusion` is the domain form of `ExcludeRule`;
  `ProfileConfig.stageChain()` derives the chain. `deployConfirmationTtl` is checked in
  `ConfigValidator` with T011.
- **T012**: missing StartCLI installations are reported in one error listing every node of the
  targets and their sources; `EXECUTE` into production checks the effective
  `write.productionOptIn` of every node, independent of `write.enabled` (the opt-in is the
  production lock of feature 001).
- Identifier lists: a literal `INUBIT_` followed by the group name `int` and `_` matches a local
  rule; tests use neutral variable names (`TARGET_USERNAME`, `HARNESS_USERNAME`).

## D-16 addendum: rulings of stage 2 (review fixes, Preview T014–T020)

- **Review #3**: repository files outside `/Root/<owner>/` that release modules reference are
  never deployed (another owner's area is outside the release): `EXCLUDED` with the warning
  `OUTSIDE_OWNER_REPOSITORY`; missing on a target node → `PRECONDITION_FAILED` naming the path
  (D-5, D-7 updated).
- **Review #1**: `RepositoryArchive.isKeyMaterial` (keys, keystores and certificates by name,
  PEM or DER) is the predicate of the archive builder and, through
  `ReleaseArchivePort.keyMaterial`, of the planner; key material is always `EXCLUDED`, missing on
  the node → `SECRET_UNRESOLVED`. `KeyMaterial` (003/004) is unchanged.
- **Review #7**: the multi-group double is a new `FakeServer` (owner-wide modules, versions,
  repository, tags per diagram group, REST diagram/module lists, `synthetic(5, 4, 20)` for
  SC-007); `FakeInubit` stays the single-group double of feature 004 (the T010 additions moved
  to `FakeServer`). `DeployHarness` has the source nodes `dev/node1`, `dev/node2`.
- **Release shape**: the tag export is normalized by `ReleaseArchivePort.normalize` (no
  `usertags.xml`, `version="head"`, no `tag` attributes or `@@@Tag:` segments) because the
  codec refuses `usertags.xml`; the release is rendered for the *target* group so its paths
  equal the target's renderings.
- **Fingerprints** use `ReleaseArchivePort.canonical` (check-in comments, UIDs, last update and
  edit mode removed): the nodes of a group are imported separately and their comments differ.
  Classification ignores a workflow's `IsActive` in addition (existing workflows keep theirs).
- **Repository files** are excluded by their own rules only (path glob, key material, foreign
  area), also when the module that references them is excluded.
- **Codes**: `deploy_release` is no `DevelopmentGuard.Capability` (that guard admits development
  nodes); `WriteChallengeRegistry` issues and redeems codes for a tool name keyed by the target
  group (`DEPLOY_RELEASE`); feature-004 node codes are unchanged and never confirm a group call.
- **Audit**: `DeployGuard` audits its refusals; `DeployService` audits every later outcome of a
  preview with the call's audit id: `CHALLENGE_ISSUED`, or `REFUSED` for a failure or a preview
  that is not executable (it has no code).
- **Ledger**: no `LedgerPort`; `DeploymentLedger` writes the file itself like `BackupStore`
  (shared `MiniJson`). An unreadable ledger is `PRECONDITION_FAILED` and is never overwritten.
  `NodePlan.artifactStates` carries the per-artifact fingerprints for the comparison.
- **Plan errors** use `PRECONDITION_FAILED` for a name in another diagram group, another plugin
  type, a missing referenced module, a foreign file missing on the node and feature-003 check
  ERRORs; `CONFLICT` for a deployed workflow in edit mode; the assembler's own codes otherwise.
- **Module usage** for `SHARED_MODULE` reads the workflows of the node outside the release with
  `ModuleUsageIndexer` (concurrency 4, budget 60 s); an incomplete usage is a warning too.
- `DeployService.preview` refuses a request with a code; executing it (T021–T023) and the tool
  (T024) are stage 3.

## D-16 addendum: rulings of stage 3 (review fixes of stage 2, Execute T021–T024)

- **Review M1**: a repository file is key material if the release's *or* the node's content is
  one; it is `EXCLUDED`, never diffed, printed or put into the ledger states. **m1**:
  `RepositoryArchive.isKeyMaterial` also recognizes DER private keys (PKCS#8 incl.
  `EncryptedPrivateKeyInfo`, PKCS#1, SEC1) by the shape of an outer SEQUENCE that spans the whole
  content, and PGP private key blocks. **m2**: a referenced path StartCLI cannot take is
  `EXCLUDED` with a `PRECONDITION_FAILED` error of that file only.
- **Review m3**: one ledger per target group (`deployments/<group>.ledger.json`); the deploy lock
  of the group guards every read-modify-write, so deployments into different groups stay
  parallel. **n1**: lock files are `rw-------`. **n2**: an audit record that cannot be written is
  reported once (`INTERNAL`) and never audited again.
- **Review m4**: `ReleasePlanner.nodeState` is the one function for the node state (same
  exports, rendering and fingerprint incl. edit mode and `IsActive`) of the plan, the re-check
  right before a node is written and the verification; it reads no inventory and writes no
  report.
- **Execute start**: discovery, release checks and the plan of every node are repeated (the plan
  gives the classes and flags to deploy; its reports go below the execute call's own audit id);
  the preview state must equal the redeemed one, otherwise `CONFLICT` before the first node.
- **Backups**: one backup per node with its own reference (a UUID; `BackupStore` writes one
  backup per id), kind `DEPLOYMENT`, holding the raw exports of the re-check (groups, modules,
  repository exports). All audit records of the call share its audit id and name the backup.
- **Import order** per node: repository files, then the modules no deployed workflow runs (one
  module archive), then per diagram group one workflow archive per intended flag (inactive
  first) carrying the deployed modules its workflows run first. Rollback archives use plain
  `--importWorkflow` (the backup's flag is in the archive).
- **Rollback** re-imports only existing artifacts and repository files whose state differs from
  the backup, then verifies them; new artifacts and new repository files stay (nothing is
  deleted) and are listed as created. A node whose re-check fails is `NOT_STARTED` with failure
  `CONFLICT` at step `recheck`; the deployment stops there.
- **UNCHANGED** nodes get only the tag: no backup, no node record, no ledger update.
- **Workspace commit**: after local changes are committed (as in feature 004), the whole
  verified rendering of the last node (its group, module and repository renderings) is written
  and committed with `Server-State: <target>`.
- A node whose `PENDING` record cannot be written fails closed (`INTERNAL`, nothing sent to it);
  the call then ends with that error and a group-level `FAILED` record (the outcomes of earlier
  nodes are in the audit log only).
- **Tool**: the description says "with the own secrets of each {node}" (the template rules
  forbid `{node}'s`; the contract's wording differs only there). Lists of the result are capped
  by `resultLimits.maxItems` with `<list>Truncated`; nodes are never cut. `docs/tools.md` has a
  minimal entry and counts sixteen tools; T029 completes it.
