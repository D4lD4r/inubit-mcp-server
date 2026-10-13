FEATURE: /Users/dadecker/Development/Privat/inubit-mcp-server/specs/008-repo-file-editing  ROUND: 3

Both round-2 majors resolved: T004 no longer adds the per-node switch helper (T009 adds it to ImportHarness with `DevelopmentPolicy.repositoryWrite`); T028 and T039 update their quoted descriptions in docs/tools.md in the same task, T045 narrowed to the remaining prose. Round-2 minors resolved: PRECONDITION_FAILED contract row aligned with D-4; P-3/T001 and T004 cover the transitive export case and T004 follows the observed behaviour; T002 names the consuming tests (CliRepositoryImportTest and CliDeployExportsTest exist); T011 adds a compatibility constructor; T029 covers US1-AS4 (module scope); T025 covers CLIENT with all-identical files and always redeeming a presented code. Only the FR-017 spec amendment remains open (see first finding).

Code references spot-checked against c9b70fa and accurate: ChangeSetBuilder.java:134-139 (repository refusal), ImportService.java:307 (scope put), :371-373 (server decision and redeem before `detector.detect`), :1761 (`group == !request.modules().isEmpty()`); `RepositoryArchive.isKeyMaterial` includes `KeyMaterial` plus certificates; `ImportScope` is only built via its own factories, so a new component touches no outside call sites; each new port method has exactly one implementor (ArchiveCodec, WorkspaceInspector, V81ReleaseArchives); no reference test pins `list_nodes` output fields or input properties in docs/tools.md, so T017 stays green before T045.

COVERAGE:
- FR-001 → T009, T024, T042
- FR-002 → T009, T014, T015
- FR-003 → T009, T014, T015
- FR-004 → T024, T042
- FR-005 → T010, T020, T028, T036, T039 (stricter refusal recorded as deliberate deviation in D-4)
- FR-006 → T016, T017
- FR-007 → T040 (new RestoreDeploymentTest case); deploy_release unchanged
- FR-008 → T006, T007, T018, T019
- FR-009 → T036, T037, T038, T039
- FR-010 → T010, T020, T021, T028
- FR-011 → T008, T020, T021, T026, T029, T031
- FR-011a → T020, T029, T031
- FR-012 → T022, T025, T026
- FR-013 → T022, T023
- FR-014 → T022, T024, T025
- FR-015 → T029, T031, T032, T033
- FR-015a → T027, T029
- FR-016 → T029, T031
- FR-017 → T020, T021 (PRECONDITION_FAILED as reinterpreted in D-4 – see minor)
- FR-018 → T040, T041, T042, T043
- FR-019 → T027, T040, T042
- FR-020 → T020 (other owner), T012/T031 (RepositoryArchive.build), no delete path by design
- FR-021 → T005, T028, T039, T044, T045, T046, T047, T048
- US1: AS1 → T022,T025,T026 | AS2 → T029,T031 | AS3 → T020,T029,T032 | AS4 → T020,T029 | AS5 → T029 | AS6 → T022,T029 | AS7 → T025 | AS8 → T022,T023
- US2: AS1,AS2 → T024 | AS3 → T009,T014 | AS4 → T014 | AS5 → T016,T017 | AS6 → T014
- US3: AS1–AS4 → T036 (+T037–T039)
- US4: AS1–AS5 → T040 (implemented by T041–T043)
- Edge cases: Only identical → T024,T025 | Shared use → T026 | Reference chains → T008,T020 | Deletion/key material/size/unknown type → T020 | Path tricks → T010,T020 | Lock → existing WorkspaceLock (unchanged)
- SC-001 → T035, T001 (P-2) | SC-002/003 → T010, T020, T024, T042 | SC-004 → T032, T040 | SC-005 → T044 | SC-006 → T012, T020, T027

FINDINGS:
- [minor] research.md D-4 "FR-017 'changed file without a verified base'" / spec.md FR-017 + edge case "No verified base" – Carried over from round 2: D-4 still only "recommends" a clarifying spec amendment and no task performs it; spec.md:269-271/:386 still say a changed file without a verified base is refused, while the plan treats it as NEW. – Amend FR-017 and the edge case in spec.md now (or add the amendment to T003/T049).
- [minor] research.md D-4 / T020 – "more than 50 files that are NEW or MODIFIED in the workspace" is counted before the node read, while FR-017 and edge "Size" say "more than 50 repository files to send (`identical` files do not count)". A change set of 60 NEW files of which 15 are identical on the node is refused although the spec allows it. This is stricter than the spec but, unlike FR-005, not recorded as a deliberate deviation. – Either record it in D-4 as a deliberate deviation (and in the docs and the INVALID_INPUT message), or keep a pre-read guard only on MODIFIED files plus a hard cap and enforce the 50-file limit on `create`+`modify` after the RepositoryStep read (T022/T024).
- [minor] research.md D-8 step 2 – names `ReleaseArchivePort.repositoryArchiveFromFiles(owner, List<RepositoryFile>)`, while data-model.md and T012 name `List<RepositoryUpload>`. – Align D-8 to `List<RepositoryUpload>`.
- [minor] tasks.md "Parallel Example" ("After T018: US3 (T036–T039) ‖ US1 … (T020–T028)") – T028 and T039 both edit docs/tools.md (quoted descriptions) and both add `repositoryPaths` to the shared request-validation pattern; when run in parallel, the edits touch different sections but may still collide on merge. – Note that T039 rebases onto T028 (or vice versa) for docs/tools.md, or order T039 after T028.

VERDICT: APPROVED
