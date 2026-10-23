FEATURE: /Users/dadecker/Development/Privat/inubit-mcp-server/specs/008-repo-file-editing  ROUND: 2

Round-1 majors resolved: closure moved to `domain/model/RepositoryClosure` (PackageBoundaryTest permits adapter.archive → domain); T013 after T009; restore audit added (T040/T042); US1-AS5 in T029; PRECONDITION_FAILED trigger defined in D-4 (base exists but `.meta` record missing – reachable, since `.meta` lies at the workspace root and is not reported by `localChanges`); T041 split into T041/T042/T043. Nearly all minors addressed.

COVERAGE:
- FR-001 → T009, T024, T042
- FR-002 → T009, T014, T015
- FR-003 → T009, T014, T015
- FR-004 → T024, T042
- FR-005 → T010, T020, T028, T036, T039 (stricter refusal recorded as deliberate deviation in D-4)
- FR-006 → T016, T017
- FR-007 → T040 (RestoreDeploymentTest case); deploy_release unchanged
- FR-008 → T006, T007, T018, T019
- FR-009 → T036, T037, T038, T039
- FR-010 → T010, T020, T021, T028
- FR-011 → T008, T020, T021, T026 (outside-owner + unknown-reference warnings), T029, T031
- FR-011a → T020, T029, T031
- FR-012 → T022, T025, T026
- FR-013 → T022, T023
- FR-014 → T022, T024
- FR-015 → T029, T031, T032, T033
- FR-015a → T027, T029
- FR-016 → T029, T031
- FR-017 → T020, T021 (PRECONDITION_FAILED as reinterpreted in D-4 – see minor)
- FR-018 → T040, T041, T042, T043
- FR-019 → T027, T040/T042
- FR-020 → T020 (other owner), T012/T031 (RepositoryArchive.build), no delete path by design
- FR-021 → T005, T044, T045, T046, T047, T048
- US1: AS1 → T022,T025,T026 | AS2 → T029,T031 | AS3 → T020,T029,T032 | AS4 → T020 only (module scope end-to-end not explicit – minor) | AS5 → T029 | AS6 → T022,T029 | AS7 → T025 | AS8 → T022,T023
- US2: AS1,AS2 → T024 | AS3 → T009,T014 | AS4 → T014 | AS5 → T016,T017 | AS6 → T014
- US3: AS1..4 → T036
- US4: AS1..5 → T040
- SC-001 → T035, T001 (P-2) | SC-002/003 → T010, T020, T024, T042 | SC-004 → T032, T040 | SC-005 → T044 | SC-006 → T012, T020, T027

FINDINGS:
- [major] T004 vs T009 – T004 (Phase 1) adds an `ImportHarness` helper to "set `development.repository` per node", but `ImportHarness` constructs `new DevelopmentPolicy(...)` directly (ImportHarness.java:162-164) and the `repositoryWrite` field / `RepositoryMode` only arrive in T009 (Phase 2), which also edits `ImportHarness`. T004 cannot compile; its "mvn verify green" done criterion is unachievable. – Move the per-node switch helper from T004 into T009 (or T024 where first needed); keep T004 to the FakeServer changes and the workspace-file helper.
- [major] T028 / T039 vs T045 – T028 extends `ImportArtifactsTool.DESCRIPTION` and T039 `ExportArtifactsTool.DESCRIPTION`, but `DevelopmentToolsReferenceTest.theDescriptionsAreQuotedAsRenderedAndListed` and `ArtifactToolsReferenceTest.theDescriptionsAreQuotedAsRendered` require docs/tools.md to quote both texts exactly as rendered; the quotes are only updated in T045 (end of the feature), so the build is red from T028/T039 on, violating "green after every task". – Update the quoted description in docs/tools.md within T028 and T039 (as T005 does for the error code) and narrow T045 to the remaining prose.
- [minor] contracts/mcp-tools-delta.md "Refusals" table – The `PRECONDITION_FAILED` row still says "a changed path without a base from this node", while research D-4 now defines the only reachable trigger as a file with a `Server-State` base whose `.meta` record is missing or unreadable. – Align the contract row with D-4 (T045 copies this table into the docs).
- [minor] D-4 / spec FR-017 + edge case "No verified base" – D-4 recommends a clarifying spec amendment, but no task performs it. – Add the amendment of FR-017 and the edge case to T003 or T049 (or do it now before implementation).
- [minor] T001 / T004 / D-3 – T004 makes FakeServer's diagram-group export include repository files referenced only through other repository files, but spike section 10 only proves directly referenced files (docs/research/spike-development-deployment.md:216) and no probe checks the transitive case; T018's test and the D-3 rationale rest on this unverified assumption. – Add the check to P-3/T001 and make T004 follow the observed behaviour; if INUBIT does not export them, state in D-3/docs that nested files reach the workspace via the US3 export.
- [minor] T002 fixtures / Constitution III – No task names the contract test that consumes `export_repository_folder.*` and `import_repository_new_files.*` (only `export_repository_types.zip` is implicitly used in T012). – Name the adapter test that parses these recordings (e.g. a nested-folder `RepositoryArchive` read in V81ReleaseArchivesTest, `CliExportRunner`/`CliImportRunner` tests with ScriptedProcessLauncher).
- [minor] T011 [P] – Adding a component to the `ChangeSet` record changes its call sites in ImportService.java, ConflictDetector.java and ChangeSetBuilder.java; T011 does not list them, while T010/T012 run in parallel. – List these files in T011 or state that T011 adds a compatibility constructor so the call sites stay untouched.
- [minor] US1-AS4 – Module scope is only covered at change-set level (T020); no task asserts a module-scope import end to end (repository file in the same preview/confirmation, `importRepository` before `importArchive` in MODULE mode). – Add a module-scope case to T029.
- [minor] T025 / edge "Only identical repository files" – Not asserted: a CLIENT node with changed workflows and repository files that are all `identical` keeps CLIENT; and a code presented while, after the node read, no repository file is left to send. – Add both cases to T025, and state whether a presented code is always redeemed when the change set contains repository files (so a server-issued code is never silently ignored).

VERDICT: CHANGES_REQUESTED
