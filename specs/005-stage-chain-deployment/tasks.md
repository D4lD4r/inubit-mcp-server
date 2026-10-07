---

description: "Task list for stage chain and deployment (feature 005)"
---

# Tasks: Stage Chain and Deployment

**Input**: Design documents from `/specs/005-stage-chain-deployment/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md) (clarifications 2026-10-07),
[research.md](research.md) (D-1…D-16), [data-model.md](data-model.md),
[contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md),
[contracts/configuration-delta.md](contracts/configuration-delta.md), [quickstart.md](quickstart.md);
the code of features 003 and 004.

**Tests**: REQUIRED (Constitution III, TDD). Every behaviour starts with a test seen failing on an
assertion before the production code exists. `mvn -q verify` green after every task.

**Organization**: by user story; US1 (preview and execute) is the core the other stories refine.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable (different files, no dependency on unfinished tasks)
- **[Story]**: US1 deploy, US2 chain cannot be bypassed, US3 failing node, US4 package-only,
  US5 stage configuration never moves, US6 configure the chain

## Path Conventions

| Short form | Expands to |
|---|---|
| `main/…` | `src/main/java/de/dadecker/inubit/mcp/…` |
| `test/…` | `src/test/java/de/dadecker/inubit/mcp/…` |
| `res/…` | `src/main/resources/…` |
| `fixtures/…` | `src/test/resources/fixtures/v8_1/…` |

## Safety rules

- While developing: no INUBIT command except read-only exports and REST reads; writes only in the
  opt-in live test, on targets the user approved for this feature, never production, never delete.
- Fixtures: neutralized, synthetic names and secrets only; the identifier hooks must pass (never
  `--no-verify`).
- Never change `~/.config/inubit-mcp/*` or the MCP registration; live runs use a temporary copy of
  the profile.

---

## Phase 1: Setup

- [ ] T001 Fixtures from the D-1 probes (`~/.inubit-mcp/<profile>/spike/p5`): neutralize (synthetic
  names `Workflow-*`, `Module-*`, `GRP-*`, owner `OWNERS`/`jdoe`, repository paths `/Root/<owner>/…`)
  and store as `fixtures/cli/export_release.zip` (tagged release export with a referenced repository
  file in an older version), `export_repository.zip`, `export_repository_not_found.{stdout,stderr,exit}`,
  `import_repository_ok.{stdout,stderr,exit}`, `tag_repository_user_refused.*`; document them in
  `fixtures/cli/README.md`; identifier check before commit.
- [ ] T002 Add section 10 "Repository files and releases (feature 005)" with the D-1 facts to
  `docs/research/spike-development-deployment.md` and a row per fact to its summary table (generic
  names only).

## Phase 2: Foundational

- [ ] T003 [P] `main/domain/model/ErrorCode.java`: `CHAIN_VIOLATION`, `SOURCE_INCONSISTENT`,
  `DEPLOY_LOCKED` with Javadoc; extend `DevelopmentToolsReferenceTest` expectations (docs must list
  them, filled in T029).
- [ ] T004 `main/adapter/cli/CliCommand.java`: allow `--exportTag` (`VALUE`), `--exportRepositoryPath`
  and `--importRepositoryPath` (repository path rule `^/Root(/[A-Za-z0-9_.][A-Za-z0-9_.\- ]{0,199})+$`,
  no `..`); the empty group list `--exportWorkflowGroup ''` only together with `--exportTag`
  (test: refused otherwise).
- [ ] T005 `main/adapter/cli/CliExportRunner.java`: `exportRelease(owner, tag)` (D-1 command;
  "no diagram group carries the tag" = an archive without workflows → `NOT_FOUND`) and
  `exportRepository(path)` ("Path not found" → `NOT_FOUND`); tests on `ScriptedProcessLauncher` with
  the T001 fixtures.
- [ ] T006 `main/adapter/cli/CliImportRunner.java`: repository mode
  `import --importFile '<zip>' --importRepositoryPath '/Root/<owner>'` (success = `n-OK: Imported
  successfully`, no protocol); `main/adapter/archive/v81/RepositoryArchive.java` builds the archive
  with entries **relative** to `/Root/<owner>` (D-1) and refuses key material
  (`KeyMaterial`); tests.
- [ ] T007 Ports: `ArtifactPort` (+`exportRelease`, +`exportRepository`), `ImportPort`
  (+`importRepository`), adapters `V81ArtifactAdapter`/`V81ImportAdapter`; `PackageBoundaryTest`
  stays green.
- [ ] T008 `main/adapter/archive/v81/ImportAssembler.java`: the active flag of a new workflow becomes
  a request parameter (`NewWorkflowFlag.MUST_BE_INACTIVE` for 004 callers — unchanged behaviour —,
  `FROM_RELEASE` for deployments); per-workflow intended flag; tests incl. the 004 regression.
- [ ] T009 `main/adapter/archive/v81/LayoutDiff.java`: decides whether two rendered workflow files
  differ only inside `<StyleSheet>` elements (`xPos`, `yPos`, `labelPosition`, …); tests with a
  moved node, a moved label, a changed edge (not layout-only) and a changed condition.
- [ ] T010 Test harness: `test/application/FakeInubit.java` learns tag exports (tagged versions per
  group, referenced repository files), repository export/import (versions, relative entries) and the
  active-flag option per import; `test/application/DeployHarness.java` with one `FakeInubit` per node
  (source `dev/node1`, targets `int/node1`, `int/node2`, `int/node3`, package-only `prod/node1`)
  behind the real adapters, real git workspace, recording audit, `MutableClock`.

## Phase 3: User Story 6 — Configure the stage chain (P3, needed by all others)

- [ ] T011 [US6] `main/config/DeployConfig.java`, `ExcludeRule.java`; `GroupConfig.deploy`;
  `Defaults.deployConfirmationTtl` ("default `PT30M`", "positive, at most `PT2H`"); `deploy` on a
  node is an unknown key; `ConfigLoaderTest` fixtures `src/test/resources/config/deploy-*.yaml`.
- [ ] T012 [US6] `main/config/ConfigValidator.java` `checkChain`: `from` "an existing group other
  than this one", acyclic (names the cycle), `EXECUTE` on production needs effective
  `write.productionOptIn: true` on every node, every node of target and source has `cli.home`,
  exclude entry "exactly one of `diagramGroup`, `name` (glob), `repositoryPath` (glob); non-blank";
  `ConfigValidatorTest` per rule.
- [ ] T013 [US6] `main/domain/model/StageChain.java` (derived; `render()` →
  `dev → int → qa → prod (package only)`), `main/config/ConfigSummary.java`: `Chains:` block,
  exclusions, per node `deploy: from <group> (EXECUTE | PACKAGE_ONLY)`; `ConfigSummaryTest`.

## Phase 4: User Story 1 + 2 — Preview (P1)

- [ ] T014 [US2] `main/application/DeployGuard.java`: target must be one group id (node id →
  `INVALID_INPUT`, unknown → `TARGET_UNKNOWN`), must have `deploy` (else `CHAIN_VIOLATION` naming the
  configured source or "receives no deployments"); tag `VALUE`, not blank, no `*`/`?`; refusals
  audited; test: no StartCLI launch for any refusal.
- [ ] T015 [US1] `main/application/DeployLock.java` (per target group file lock in
  `~/.inubit-mcp/<profile>/deployments/<group>.lock`, busy → `DEPLOY_LOCKED`; then the workspace
  lock); `DeployLockTest` with `LockHolder` (second JVM).
- [ ] T016 [US1] `main/application/ReleaseDiscovery.java`: tag export per source node, render with
  the codec, fingerprint compare (`SOURCE_INCONSISTENT` with `.reports/deploy-<auditId>/source.diff`),
  `NOT_FOUND` when no group carries the tag, "tagged version older than head" note; tests.
- [ ] T017 [US1] `main/application/ReleasePlanner.java` + `ArtifactClassifier`: per target node
  exports (groups, missing modules, repository files), classes `NEW`/`UNCHANGED`/`LAYOUT_ONLY`/
  `CHANGED`/`EXCLUDED`/`ONLY_ON_TARGET`, resulting active flag ("existing keep the target's, new
  take the release's"), errors (name in another diagram group via REST model list, plugin-type
  clash, missing referenced module, `SECRET_UNRESOLVED` incl. key material, edit mode → `CONFLICT`,
  feature-003 check ERRORs), diff and summary files; tests per class and error.
- [ ] T018 [US5] Warnings in `ReleasePlanner`: `STAGE_SPECIFIC_VALUE` (`StageValueHeuristics`:
  property name or value looks like host, URL, port or login; names only in the result),
  `SHARED_MODULE` (module usage of feature 001 on the target), `OUTSIDE_CHAIN` (ledger, T019);
  exclusions by `diagramGroup`, `name` glob, `repositoryPath` glob; tests incl. "excluded module
  missing on target" → error.
- [ ] T019 [US1] `main/application/DeploymentLedger.java` (`deployments/ledger.json`, owner-only,
  atomic write; node → artifact → fingerprint, auditId, tag, time; no content); tests incl.
  permissions and a leak check.
- [ ] T020 [US1] `main/application/DeployService.java` preview: guard, locks, discovery, checks,
  planning, `WriteChallengeRegistry` capability `DEPLOY_RELEASE` keyed by the target group, preview
  state per D-6, TTL `deployConfirmationTtl`, code only if every plan is executable; audit
  `CHALLENGE_ISSUED`; `DeployServiceTest` previews (US1 AS1, AS3–AS7; US2 AS1–AS4; a target node
  that cannot be read fails the whole preview; first deployment without ledger entries warns with
  "no earlier deployment by this server").

## Phase 5: User Story 1 + 3 — Execute (P1)

- [ ] T021 [US1] `main/application/NodeDeployer.java`: re-check fingerprint, backup
  (`BackupStore` manifest `kind: DEPLOYMENT`, groups, repository paths, tag, source; 004 manifests
  read as `IMPORT`), `PENDING` audit, imports in D-7 order (repository, modules, workflows active /
  inactive) with target secrets and protocol matching; check-in comment of workflows and modules
  with the reason `deploy <tag> from <source>` (FR-019, D-9); verification (content, reason segment,
  active flag, repository files), tag via `DiagramGroupTagger`, ledger update; tests on `DeployHarness`.
- [ ] T022 [US3] Rollback and stop rule in `NodeDeployer`/`DeployService`: rollback from the backup
  incl. repository files, verified; states `DEPLOYED`, `UNCHANGED`, `ROLLED_BACK`,
  `ROLLBACK_FAILED`, `NOT_STARTED`; remaining nodes untouched; `DeployFailureTest` (import NOK on
  node 2 of 3, verify mismatch, rollback failure, `CONFLICT` at the re-check of node 2).
- [ ] T023 [US1] `DeployService` execute: redeem code, repeat discovery and exports and compare with
  the preview state (`CONFLICT` before the first node), nodes in order, result per contract, workspace
  commit `deploy <target> ← <source>: <tag> [<auditId>]` with `Server-State: <target>` only when every
  node is `DEPLOYED`/`UNCHANGED`; group-level and per-node audit records; SC-003 test (redeploy
  imports nothing).
- [ ] T024 [US1] `main/mcp/tools/DeployReleaseTool.java`, `res/schemas/deploy_release.{input,output}.json`
  (contract), `ToolHints.destructive`, terminology rendering; `Wiring`: registered iff a group has
  `deploy`; `DevelopmentWiringTest`, `TerminologyRenderingTest`, `TemplateGrammarTest`.

## Phase 6: User Story 4 — Package-only (P2)

- [ ] T025 [US4] `main/application/PackageWriter.java`: per node
  `~/.inubit-mcp/<profile>/packages/<auditId>/<node>/` (`rwx------`/`rw-------`) with the D-7 archives
  (node's own secrets), `diff.txt`, `warnings.txt`, `README.md` (StartCLI commands in order);
  retention 30 days, newest per target kept; `DeployService` uses it for `PACKAGE_ONLY` (outcome
  `PACKAGED`); `PackageOnlyTest`: no `import`/`tag` launch on that group; `DeploySecretLeakTest`
  (packages, ledger, reports, audit, results, logs).

## Phase 7: Restore and end-to-end on targets

- [ ] T026 [US3] `DevelopmentGuard` + `ImportService` restore: admit a target-group node for a
  backup of kind `DEPLOYMENT`, always with preview and server code; restore covers workflows,
  modules and repository files of that deployment on that node; created artifacts listed;
  `RestoreDeploymentTest`.
- [ ] T027 `DevelopmentGuard` / `Wiring`: `run_e2e_test` on nodes of non-production groups with
  `deploy`, governed by `e2eTests`; registration condition widened; tests.

## Phase 8: Polish & Cross-Cutting

- [ ] T028 Performance check for SC-007 on `DeployHarness` (5 groups, 20 workflows, 100 modules, 2
  nodes; harness overhead well below the budget).
- [ ] T029 Docs: `docs/tools.md` (`deploy_release`, widened `restore_backup`/`run_e2e_test`, three
  error codes), `docs/setup.md` (chain, modes, exclusions, deploy TTL, files), `README.md` tool table
  and safety model, `docs/live-tests.md` (deployment live test), `CHANGELOG.md` `[Unreleased]`.
- [ ] T030 `test/live/DeploymentLiveTest.java` (`@Tag("live")`, env names a chained test target the
  operator approved, never production; **ask the user to approve the live target before running
  it**): tag → preview → execute → redeploy unchanged → restore;
  checks that nothing outside the test group changed.
- [ ] T031 Validation: `mvn -q clean verify`, guard test without `clean` (not skipped), quickstart
  table; live acceptance only after the user approved the test target.

---

## Dependencies & Execution Order

- Phase 1 → Phase 2 → US6 (T011–T013) → Preview (T014–T020) → Execute (T021–T024) → US4 (T025) →
  T026–T027 → Polish.
- Within Phase 2: T003, T009 [P]; T004 → T005/T006 → T007; T008 independent; T010 after T005–T007.
- T018 and T019 can run in parallel after T017; T022 after T021.

## Parallel Example

```text
Phase 2: T003 (ErrorCode) ‖ T008 (ImportAssembler flag) ‖ T009 (LayoutDiff)
Preview: T018 (warnings) ‖ T019 (ledger) after T017
```

## Implementation Strategy

- **MVP**: Setup, Foundational, US6, Preview and Execute (T001–T024): a confirmed deployment into a
  chained non-production group with rollback and stop rule.
- **Increment 2**: package-only (T025).
- **Increment 3**: restore of deployments and end-to-end tests on targets (T026–T027).
- **Finish**: performance, docs, live test, validation (T028–T031).
- Creator/Reviewer per stage: Foundation (T001–T013), Preview (T014–T020), Execute (T021–T024),
  Rest (T025–T031).
