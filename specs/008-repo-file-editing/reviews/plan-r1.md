FEATURE: /Users/dadecker/Development/Privat/inubit-mcp-server/specs/008-repo-file-editing  ROUND: 1

COVERAGE:
- FR-001 → T009, T024, T040
- FR-002 → T009, T014
- FR-003 → T009, T014, T015
- FR-004 → T024, T040
- FR-005 → T010, T020, T028, T036, T039 (refusal instead of normalization – see minor)
- FR-006 → T016, T017
- FR-007 → T040 (deployment restore); deploy_release itself unchanged
- FR-008 → T006, T007, T019
- FR-009 → T036, T037, T038, T039
- FR-010 → T010, T020, T021, T028
- FR-011 → T006, T008, T020, T021, T029, T032 (outside-owner warning in import result untasked – minor)
- FR-011a → T020 (change set only) – tag half untasked (major)
- FR-012 → T022, T023, T025, T026
- FR-013 → T022, T023
- FR-014 → T022, T024
- FR-015 → T029, T030, T031, T032, T033
- FR-015a → T029
- FR-016 → T029, T031
- FR-017 → T012, T020, T021 (PRECONDITION_FAILED clause has no reachable trigger – major)
- FR-018 → T040, T041
- FR-019 → T024, T027 (import only; restore uncovered – major)
- FR-020 → T012, T020
- FR-021 → T005, T042, T043, T044
- US1-AS1 → T025, T026 | AS2 → T029, T031 | AS3 → T020, T029, T032 | AS4 → T020, T029 | AS5 → T020 only, "group is tagged" missing (major) | AS6 → T022, T029, T012 | AS7 → T025 | AS8 → T022
- US2-AS1..2 → T024 | AS3 → T009, T014 | AS4 → T014 | AS5 → T016, T017 | AS6 → T014
- US3-AS1..4 → T036
- US4-AS1..5 → T040
- SC-001 → T035, T001 (P-2) | SC-002/003 → T024, T040, T010, T020 | SC-004 → T032, T040 | SC-005 → T042 | SC-006 → T020, T027

FINDINGS:
- [major] T018 / T008 / plan "Constitution Check" V – T018 makes `adapter/archive/v81/WorkspaceWriter` use the T008 closure, but `RepositoryClosure` is planned in `application`; `PackageBoundaryTest.theWorkspaceAdaptersReachNeitherTheMcpLayerNorTheApplication` forbids `adapter.archive` → `application`, so the plan's "PackageBoundaryTest stays green" does not hold for this design. – Place the closure where both callers may reach it without breaking the boundary (e.g. a pure-Java class in `domain` operating over `ArtifactInspectorPort`/`RepositoryReference`, or compute it in the adapter and expose it via `ArtifactInspectorPort`); update plan Source Code, data-model.md (`RepositoryClosure` row), T008 and T018.
- [major] T009 / T013 [P] – Both are marked parallel but touch the same files: T009 adds `DevelopmentPolicy.repositoryWrite` and must update every `new DevelopmentPolicy(...)`, including `DevelopmentGuard.admitDeploymentRestore`/`forDeploymentRestore` (`main/application/DevelopmentGuard.java:130,139`) and `DevelopmentGuardTest`; T013 edits `DevelopmentGuard.java`/`DevelopmentGuardTest.java` and needs the new field. – Drop [P] on T013 and make it depend on T009 (list `DevelopmentGuard.java` among T009's files).
- [major] FR-019 / T027 / T040 – FR-019 and the contract ("For import_artifacts and restore_backup, one key per repository file") require audit records for repository restores with paths, hashes and operation per file; T027 covers only the import, T040 has no audit case. – Add a restore audit case (refused, previewed, executed; `repository.<n>` inputs, no content) to T040 and the implementation to the restore task(s).
- [major] US1-AS5 / FR-011a – No task tests a diagram-group import with only repository changes plus `tag` (only the schema is imported, the group is tagged): T020 only checks the change set is valid, T029 only that `importArchive` is not launched, T034 only `tag.repositoryFiles`; quickstart.md claims `RepositoryImportTest` proves it, but no task writes it. – Add the case to T029 or T034: repository-only group import with `tag` runs `DiagramGroupTagger` after the repository import and the result `tag.applied` is true.
- [major] T020 / research D-4 vs spec FR-017/FR-012 – T020 demands a PRECONDITION_FAILED test for "a changed path without a Server-State base", but D-4 itself states "No base means NEW. A MODIFIED file without a base is not possible", and FR-012 / the "New, but on the node already" edge case route a NEW file that differs on the node to `modify` + `existing`. As designed, the refusal has no reachable trigger, so the T020 case cannot be built and its done criterion is undefined. – Resolve in research D-4: either name the concrete condition that yields PRECONDITION_FAILED and how the server detects it, or record that FR-017's no-base clause is subsumed by the `existing` path (with `repositoryDiff` as mitigation), drop the T020 case, and flag the spec amendment.
- [major] T041 – One task holds restore set (repository files from manifest + backup exports, incl. repository-only backups where `restoreSet` currently returns empty), scope reconstruction from a `repository paths …` manifest scope, `requireStateLeftBy` with per-folder exports, switch check, forced server code, execute/rollback via `RepositoryStep`, and the output schema – more than one implement/review round, while US1 splits the same parts into T022–T033. – Split T041 (e.g. (a) restore set + scope + conflict check, (b) switch + `forRepositoryWrite` + audit, (c) execute/rollback/verify + schema), each mapped to its T040 test cases.
- [minor] T020 / T021 / research D-4 – The refusal "a path that `CliCommand.repositoryPathPassable` refuses" points to `adapter.cli`, which `application/ChangeSetBuilder` must not reference (PackageBoundaryTest). – Name the application-side copy `ReleasePlanner.repositoryPathPassable` (`main/application/ReleasePlanner.java:566`) or move it to a shared application/domain helper.
- [minor] T025 – In `ImportService.locked` the `server` decision (line 371) and `redeem` (line 372) happen before `detector.detect`; forced SERVER depends on the node state (a NEW file may turn out `identical`). – State that on the forced path the decision and the `redeem` move after the `RepositoryStep` node-state read, and that the server-fingerprint check still applies there; add a test that a CLIENT node's second call with the code is redeemed and bound.
- [minor] T024–T026 / T031 – The preview path already runs `d.archives().assemble(build(changes, … files(changes) …))` (line 411); `ImportAssembler.guard` refuses repository entries and a repository-only change set has no artifacts. – State in T025/T026 (preview) and T031 that repository files are excluded from the artifact archive and assembly is skipped for repository-only change sets.
- [minor] ImportService request plumbing – No task names the changes to `ImportRequest`, `ImportService.validate` (`group == !modules.isEmpty()` check, line 1761), `scope(...)` and `call.requested.put("scope", …)` (line 307) for `repositoryPaths`. – Assign explicitly (e.g. to T024 or T028).
- [minor] FR-005 / T010 – Spec says paths are normalized (segments, encoding, doubled slashes) and refused only if they leave the root after normalization; the plan refuses every `.`/`..`, `%` and empty segment outright. Stricter and acceptable for safety. – Record it as a deliberate deviation in research D-4/D-10 (and the docs task) so it does not look like a gap.
- [minor] FR-011 / Edge "Shared use" / T026 – Nothing asserts (a) the warning for references outside the owner's root in the import preview/result (T008 only computes `outsideOwner`), or (b) the warning that node-side references unknown to the workspace cannot be listed. – Add both asserts to T026.
- [minor] T029 / T031 write-back – The verification re-export is per folder and returns every file in the folder. – Make explicit that the write-back rewrites only the sent files and their `.meta`, not every file of the exported folder.
- [minor] T012 / research D-4 – `RepositoryArchive.isKeyMaterial` already includes `KeyMaterial.hasKeyName`/`isKeyMaterial` (`RepositoryArchive.java:79`), so the "union" is just `isKeyMaterial`. – Simplify the description of `keyMaterialAny` (delegate to `isKeyMaterial`); no behaviour change.

VERDICT: CHANGES_REQUESTED
