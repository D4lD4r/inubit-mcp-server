---

description: "Task list for development on a development stage (feature 004)"
---

# Tasks: Development on a Development Stage

**Input**: Design documents from `/specs/004-development-stage/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md) (clarifications 2026-10-06),
[research.md](research.md) (D-1…D-23), [data-model.md](data-model.md),
[contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md),
[contracts/configuration-delta.md](contracts/configuration-delta.md), [quickstart.md](quickstart.md);
feature 003 code and `docs/research/spike-development-deployment.md`.

**Tests**: REQUIRED (Constitution III, TDD). Every behaviour starts with a test seen failing on an
assertion before the production code exists. `mvn -q verify` green after every task.

**Organization**: by user story; US1 (import) is the core every other write story reuses.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable (different files, no dependency on unfinished tasks)
- **[Story]**: US1 import, US2 restore, US3 activate, US4 tag, US5 SOAP test, US6 configuration

## Path Conventions

| Short form | Expands to |
|---|---|
| `main/…` | `src/main/java/de/dadecker/inubit/mcp/…` |
| `test/…` | `src/test/java/de/dadecker/inubit/mcp/…` |
| `res/…` | `src/main/resources/…` |
| `fixtures/…` | `src/test/resources/fixtures/v8_1/…` |

## Safety rules

- No INUBIT command except read-only exports, `finger` and REST reads while developing; writes
  (`import`, `tag`) only in the opt-in live test on a development node and a **personal** diagram
  group (owner kind USER), and only with the user's approval given for this feature.
- Never production, never shared owners in live runs, never delete artifacts.
- Fixtures: neutralized, synthetic secrets only; pre-commit identifier hook must pass.
- Never change `~/.config/inubit-mcp/*` or the MCP registration; live runs use a temporary copy of the
  profile.

---

## Phase 1: Setup

- [X] T001 Fixtures (D-22): from the local spike recordings (`~/.inubit-mcp/<profile>/spike`:
  `claude/import-create.txt`, `t6/import.txt`, `t7/import*.txt`, `t8/import.txt`, `t9/tag.txt`,
  `finger-*.txt`), neutralize and store as `fixtures/cli/import_created.{stdout,stderr,exit}`,
  `import_modified`, `import_module_only`, `tag_ok`, `tag_delete_ok`, `finger_user`,
  `finger_not_registered`, plus the archive-shape probes of research D-24
  (`~/.inubit-mcp/<profile>/spike/p4`: workflow-only import, activate/deactivate, comment shapes);
  synthesize `import_nok` (an `n-NOK` line), `import_protocol_mismatch` (an extra artifact),
  `import_timeout`; record the real `/user/users` response read-only and neutralize it into
  `fixtures/rest/user_users.xml` (fictitious names and e-mails, same structure); document all in `fixtures/cli/README.md`. Identifier check before commit.
- [X] T002 `test/adapter/cli/ScriptedProcessLauncher.java` with its own test: answers a sequence of
  StartCLI launches by matching the `--execCommand` line (prefix or regex) to a response
  (`stdout/stderr/exit`) and an optional action (e.g. write the export file); records every launch;
  fails the test on an unexpected command (a write that should not happen).
- [X] T003 [P] `main/domain/model/ErrorCode.java`: add `NOT_DEVELOPMENT`, `CONFLICT`,
  `SECRET_UNRESOLVED`, `IMPORT_FAILED`, `VERIFY_MISMATCH`, `E2E_FORBIDDEN` with test updates
  (any exhaustive switch/mapping tests); `PackageBoundaryTest` first: `adapter.soap` exists, may not
  depend on `mcp`/`application`; `application` reaches new adapters only via ports.

## Phase 2: Foundational

- [X] T004 Configuration (D-1, contract): `DevelopmentConfig`, `E2eConfig`, `OwnerKind`,
  profile `owners` in `main/config/*`; node-wins resolution into `EffectiveNodeConfig.development()` →
  `DevelopmentPolicy`; defaults `enabled false`, `confirmation SERVER`, `e2eTests FORBIDDEN`; tests
  first in `test/config/ConfigLoaderTest` and `ConfigValidatorTest`: production + development →
  error; production + e2e FREE/CONFIRM → error; e2e without baseUrl → error; http baseUrl → warning;
  invalid owner kind → error; more than one development-enabled node in a group → error (D-25);
  unknown keys still rejected.
- [X] T005 `main/application/DevelopmentGuard.java` with test first (D-1): admit(node, capability) →
  `DevelopmentPolicy`; group id → `INVALID_INPUT`; unknown → `TARGET_UNKNOWN`; not development →
  `NOT_DEVELOPMENT`; production (defence in depth) → `PRODUCTION_PROTECTED`; CLI unavailable →
  `CLI_UNAVAILABLE`; `e2eTests` checks for `run_e2e_test` → `E2E_FORBIDDEN`. `DevelopmentGuardTest`
  covers every branch.
- [X] T006 `main/application/WriteChallengeRegistry.java` with test first (D-2): issue/redeem bound to
  `(capability, node, inputFingerprint, previewState)`; 22-char code, single use, TTL, max 1000
  pending, swept; changed inputs or capability or node → `CONFIRMATION_INVALID`; changed previewState
  → returned to the caller for a `CONFLICT` decision; expired → `CONFIRMATION_INVALID`.
- [X] T007 `main/adapter/git/GitCli.java` + `VersionHistoryPort` (D-3) with tests first (real git):
  `commitAll(message, trailers)` writes `Server-State: <node>`; `lastServerState(node, path)`,
  `show(rev, path)`, `changedPaths(fromRev, subtree)` (incl. added, modified, deleted, renamed as
  delete+add); `WorkspaceService` export commits now carry the trailer (test in
  `WorkspaceExportTest`); no write method beyond `commitAll`/`restore`.
  **Also (analysis):** trailer names the **group**; without any trailer fall back to the last `export <node>:` subject; no base → `PRECONDITION_FAILED` "export the scope first"; `changedPaths` per artifact path (D-25).
- [X] T008 `main/application/BackupStore.java` (+ `BackupPort` if needed) with test first (D-13):
  write `<auditId>.zip` + `.json` under `~/.inubit-mcp/<profile>/backups` (injectable root),
  `rwx------`/`rw-------`; index has no secrets (assert); `find(auditId)`; retention sweep: older than
  30 days removed unless newest of `(node, owner, scope)`, returns removed refs for auditing.
  **Also (analysis):** backup = manifest `<auditId>.json` (scope, change set, created, intended-state hashes, outcome; no secrets) + one raw ZIP per scope export `<auditId>-<n>.zip` (D-25).
- [X] T009 `main/application/OwnerKindResolver.java` + `main/domain/port/UserDirectoryPort.java` +
  `main/adapter/rest/v81/V81UserDirectory.java` (GET `/user/users?type=processEngineUser` through the
  existing REST client and credential guard) with tests first (D-21, WireMock + fixture XML): profile
  override wins; listed user → `USER`; not listed but owner has artifacts → `USER_GROUP`; neither →
  `PRECONDITION_FAILED` naming `owners.<name>`; REST failure → `PRECONDITION_FAILED`.
  **Also (analysis):** only positive evidence: profile override or REST user list → `USER`; profile `USER_GROUP` → `USER_GROUP`; otherwise `PRECONDITION_FAILED`; writes for `USER_GROUP` owners refused by ONE guard ("not yet verified") with its own test; REST fixture is the neutralized recording of T001 (D-25).

## Phase 3: User Story 1 — Import (Priority: P1) 🎯 MVP

**Goal**: `import_artifacts` with the full server-enforced sequence.

**Independent test**: scripted StartCLI (export → import → export) on fixture diagram groups.

- [X] T010 [P] [US1] `main/application/ChangeSetBuilder.java` with test first (D-4): from base
  (`lastServerState`) and HEAD (after local-changes commit) for a diagram group scope or a module scope;
  NEW vs MODIFIED; modules of changed workflows only if their files changed or are new; deletions →
  `INVALID_INPUT` with the message of D-4; changes outside the scope → `notImported`; nothing changed
  → empty change set (import sends nothing, SC-003).
  **Also (analysis):** base per artifact (`lastServerState(node, path)`); changes below `repository/` → `INVALID_INPUT` (D-24, D-25).
- [X] T011 [P] [US1] `main/adapter/archive/v81/SecretPaths.java` (extracted from `SecretRedactor`,
  which must keep all its tests green) and `SecretValues.java` with tests first (D-6): for every
  synthetic secret form of the feature-003 fixtures, the path derived on the raw archive equals the
  placeholder path written by the redactor; lookup by (artifact, path); values never in `toString`.
- [X] T012 [US1] `main/adapter/archive/v81/ImportAssembler.java` with test first (D-6, D-7, D-11):
  builds the import archive (workflow archive with only changed/new modules, or module-only archive)
  from workspace + `.meta`, replaces placeholders from `SecretValues` (missing → `SECRET_UNRESOLVED`
  naming artifact and path), sets the person-written CheckinComment part to the reason, restores
  withheld key material from the target repository; reading the archive back yields exactly the
  change set (guard); an unchanged artifact is never included.
  **Also (analysis):** CheckinComment in the probed export shape `DefaultCommitCommentImport###<reason>###@@@Deploying User: …@@@Server: …@@@Version: …@@@Export/Deployment: …@@@` (D-11); strip `<CheckoutUser>`; omit `Repository.zip` (D-24); default context for new artifacts and a module-index entry from `index.xml`; name collision with another owner/kind on the target → `PRECONDITION_FAILED` (D-25).
- [X] T013 [US1] `main/adapter/cli/CliCommand.java` (+ `import`, `tag` options), `CliImportRunner.java`,
  `ImportProtocolParser.java` with tests first (D-8, fixtures T001): exact command line incl.
  `--importUser` vs `--importUserGroup`; private temp dir deleted on all paths; protocol parsed into
  entries; NOK → failure; `cliExportTimeout` reused and named on TIMEOUT.
- [X] T014 [US1] `main/adapter/cli/v81/V81ImportAdapter.java` (`ImportPort` via `Gateway`) with test
  first; the v8.1 gateway overrides the default.
- [X] T015 [US1] `main/application/ConflictDetector.java` with test first (D-5): fresh export rendered
  in memory (new read-only `PreparedExport.files()`), compared with `show(base, path)` for every
  change-set artifact; `CheckoutUser` → `CONFLICT (IN_EDIT_MODE)`; diff to
  `.reports/conflict-<auditId>.diff`; returns the raw archive for D-6 and the fingerprint for D-2.
  **Also (analysis):** also compares scope artifacts outside the change set; returns the target's module list for the referenced-module rule (D-25).
- [X] T016 [US1] `main/application/ImportService.java` with tests first in
  `test/application/ImportServiceTest`, `ImportServiceRefusalTest`, `ImportRollbackTest`,
  `ImportConfirmationTest` (D-2, D-5, D-9, D-10, D-12, D-13, D-18, D-20): the full sequence of the plan
  summary; preview (SERVER) returns create/modify/notImported/checkWarnings + code; execute redeems the
  code and re-runs the conflict check; check ERROR / conflict / edit mode / unresolved secret → nothing
  sent (assert via ScriptedProcessLauncher, SC-001); NOK, protocol mismatch, failing or differing
  re-export → rollback from the backup, verified, outcome `FAILED` with `rollback` state, created
  artifacts listed in `createdNotRemoved` (SC-002); success → workspace updated and committed with the
  trailer and auditId; reason in the check-in comment; audit records (PREVIEW/CHALLENGE_ISSUED,
  EXECUTE/PENDING, EXECUTED/FAILED, REFUSED) with the inputs of data-model; backup retention sweep at
  start, audited; SC-007 performance test (20 workflows, 100 modules, scripted StartCLI) < 2 min.
  **Also (analysis):** reason validated (no `###`, `@@@`, control characters); referenced modules must be in the archive or the target's module list; calls `checkPaths` (lock already held); `USER_GROUP` owners refused; rollback built from the backup through `ImportAssembler` with secrets from the target's CURRENT export; failure result `failure{code, step, message}`; after a StartCLI TIMEOUT re-export before deciding; write-back replaces only change-set files; workspace changed between preview and execute → `CONFIRMATION_INVALID`; `DevelopmentAuditTest` covers every tool's audit records; concurrent workspace operation refused (FR-027) (D-25).
  **Also (review of T010–T018, research D-25 addendum):** identity (UIDs, module file name, group
  context) of modified artifacts from the target's fresh export, owner in `UserOrUserGroupName`
  enforced (I1); new workflows must be `IsActive=false` in their file (I2); a module import never
  exports a new module, its existence comes from the owner's module list (I3); name collisions only
  against the owner's own workflows and modules (m5); exact reason check incl. module index entries
  (m2); unexpected failures after sending are `FAILED` results (m6).
- [X] T017 [US1] `test/security/ImportSecretLeakTest.java` (FR-012, SC-004): run imports of all
  feature-003 fixtures; search workspace, history objects, backup index, audit file, tool results and
  captured logs for every synthetic secret value — zero; the import ZIP temp dir no longer exists; the
  values inside the (captured) import archive equal the target's values.
- [X] T018 [US1] Schemas `res/schemas/import_artifacts.{input,output}.json`,
  `main/mcp/tools/ImportArtifactsTool.java` (destructive hints, challenge/result mapping like
  `ProcessControlTool`), registration in `main/Wiring.java` only if a development node exists, with
  tests first (`ImportArtifactsToolTest`, `DevelopmentWiringTest`).

## Phase 4: User Story 2 — Restore (Priority: P1)

- [X] T019 [US2] `ImportService.restore` + `main/mcp/tools/RestoreBackupTool.java` + schemas, tests
  first (D-14): unknown/removed/foreign ref → `NOT_FOUND` without INUBIT contact; conflict check
  against the commit of the referenced call; rollback archive from the backup limited to the call's
  artifacts; verify; commit; created artifacts of the original call reported as not removed.
  **Also (analysis):** conflict base = intended state in the referenced manifest (also for failed calls); restore takes its own backup and has its own rollback; secrets from the target's current export (D-25).

## Phase 5: User Story 3 — Activate (Priority: P2)

- [X] T020 [US3] `ImportService.setActive` + `main/mcp/tools/SetActiveTool.java` + schemas, tests
  first (D-15): only that workflow sent (no module) with `--importWorkflowActive|Inactive`; conflict
  check; verify `IsActive`; commit; result notes the new version.
  **Also (analysis):** archive built from the FRESH server export of that workflow only (D-24); refused if the workspace file has unimported edits (D-25).

## Phase 6: User Story 4 — Tag (Priority: P2)

- [ ] T021 [US4] `main/adapter/cli/v81/V81TagAdapter.java` (`TagPort`) and
  `main/application/TagService.java` + `main/mcp/tools/TagArtifactsTool.java` + schemas, tests first
  (D-16, SC-005): blank/empty group refused before any launch; tag existing anywhere for the owner
  (history export of all groups) → `INVALID_INPUT`; one `tag --tagMove … --tagWorkflowGroup '<g>'
  --tagWorkflowType 'technical' --tagUser '<owner>'` per group; verification by history export; tag on
  anything else → `tag --tagDelete` + `VERIFY_MISMATCH` with `removedAgain: true`; audited.
  **Also (analysis):** new `CliExportRunner.exportHistoryAllGroups(owner)` (`--exportWorkflowGroup ''` via emptyQuoted, type `all`) with tests for the existence pre-check; wildcard-like and blank groups refused; failure uses the result shape `failure{code: VERIFY_MISMATCH}` with `removedAgain` (D-25).

## Phase 7: User Story 5 — SOAP test (Priority: P3)

- [ ] T022 [US5] `main/adapter/soap/SoapE2eClient.java` (`E2ePort`, JDK HttpClient, node TLS via the
  shared builder) with WireMock tests first (D-17): headers incl. `X-Inubit-Mcp-Test-Id`; envelope
  sent unchanged; timeout; TLS errors mapped.
- [ ] T023 [US5] `main/application/E2eTestService.java` + `main/mcp/tools/RunE2eTestTool.java` +
  schemas, tests first: policy FREE/CONFIRM/FORBIDDEN; path validation (no `..`, no scheme/host);
  response file under `.tests/e2e/`; excerpt ≤ 2 KB; correlation by test id in logs within the window,
  else workflow + window marked `TIME_WINDOW_UNCERTAIN`; timeout keeps diagnostics; audited (payload
  hash only); registered only if a node allows e2e.
  **Also (analysis):** envelope path confined to the workspace (real path; not `.git`, `.meta`, `.reports`); non-placeholder `wsse:Password` refused; redirects never followed; optional e2e basic auth only from `<PREFIX>_<GROUP>[_<NODE>]_E2E_USERNAME/_PASSWORD`; excerpt only with `includeExcerpt: true`; `.tests/e2e` files older than 30 days removed (D-25).

## Phase 8: User Story 6 — Configuration check (Priority: P3)

- [ ] T024 [US6] `main/config/ConfigSummary.java` with test first: per node `development: on
  (confirmation …) | off` and `e2e: …`; owner overrides listed; docs example in setup.md verified by
  the existing example test if applicable.

## Phase 9: Polish

- [ ] T025 [P] `docs/tools.md` (5 tools, errors, confirmation, outcomes, rollback semantics incl.
  "created artifacts are not removed"), `docs/setup.md` (development settings, owners, e2e, backups and
  retention, safety), `README.md`, `CHANGELOG.md` (Unreleased); reference test extended to the new tools.
- [ ] T026 [P] `test/live/DevelopmentLiveTest.java` + `docs/live-tests.md` (D-23): opt-in, refused
  unless development node and owner kind USER; scenario export → layout change → import → verify →
  restore → set_active off/on → tag `LIVE-<ts>` → tag removed; prints counts only. Do not run it in
  this task.
- [ ] T027 Validation per quickstart A–B (offline) and the identifier guard; quickstart C/D only with
  the user's approval (recorded in this file).

---

## Dependencies & Execution Order

- Phase 1 → Phase 2 (T004–T009) → US1 (T010–T018) → US2 (T019), US3 (T020) → US4 (T021) → US5
  (T022–T023, can start after Phase 2) → US6 (T024, after T004) → Polish.
- T010 and T011 in parallel; T012 needs T011; T013→T014; T015 needs T007; T016 needs T008–T015.

## Implementation Strategy

1. **MVP** = Phases 1–3 (import with checks, conflicts, backup, secrets, verify, rollback).
2. Restore (P1) right after, then activate, tag, SOAP test, config check, polish.
3. Live acceptance on the personal diagram group with a temporary profile copy, then PR.
