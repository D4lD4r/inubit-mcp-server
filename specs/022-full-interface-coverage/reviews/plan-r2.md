FEATURE: specs/022-full-interface-coverage  ROUND: 2

Round 1 follow-up: all 7 majors and the 13 minors from plan-r1 are fixed. In detail:
- Secret-entry suffix and collision rules: D-10, T077, configuration-delta.
- AWS nodes refused with `NOT_CONFIGURED`, plus a backlog entry.
- Verified private copy for StartCLI uploads, with swap tests: D-11, T076, T095, T099.
- `setBackupStartOption` in `change_server_settings`: T087, T088, T091.
- Action-key rule: D-2, the contract, T007, T008.
- `read_users` `sources`: T034.
- Old T083 split into T084/T085, old T095 split into T097–T100.
- The minors: [P] markers, T008/T010 order, the "three design weights" count, the quickstart table, D-9, the research-note docs in every PR task, the CLI-right test in T019, lock keys per service, `ModelDetailsSecretLeakTest`, capabilities added per story, the `licensedThreads` source, the loader rule, and the `WriteChallengeRegistry` overload.

Fixing them exposed one new major issue in the coverage gate (see findings).

COVERAGE:
- FR-001 → T006, T007
- FR-002 → T007, T045, T066/T074/T091/T102 (exclusions from the live checks), T104
- FR-003 → T004, T005, T008, T010 (check 6)
- FR-004 → T027, T038, T047, T066, T074, T091, T102 (one PR per story), check 5 in T008
- FR-005 → T044 (export_archives extended), D-1
- FR-006 → T001, T107 (tool count); D-1 (9 tools + 1 extension)
- FR-007 → T022, T035, T043, T044, T061, T064, T073, T086, T089, T101
- FR-008 → T019, T032, T041, T035 (msgread), T044 (check-in trace)
- FR-009 → T021, T034, T043, T044
- FR-010 → T013, T017, T040, T036
- FR-011 → T030, T034, T036
- FR-012 → T044
- FR-013 → T019, T021 (but see finding 1)
- FR-014 → T049, T065, T073, T090, T101
- FR-015 → T051
- FR-016 → T049, T067, T085 (area per action), T090
- FR-016a → T072
- FR-017 → T051, T060, T072, T085
- FR-018 → T053, T064, T083, T084, T085 (keystore backup not named: finding)
- FR-019 → T094, T097, T098
- FR-019a → T062, T063, T064, T066
- FR-019b → T095, T099
- FR-020 → T041, T068, T088, T100
- FR-021 → T075, T076
- FR-022 → T077, T088, T093, T098
- FR-023 → T052
- FR-024 → T059, T060, T064, T072, T084, T085, T088, T097
- FR-025 → T010, T025, T026, T103
- FR-026 → T054, T055, T069, T079, T092 (gates)
- FR-027 → T027, T038, T047, T066, T074, T091, T102, T104
- US1 AS1–AS7 → T015–T023, T025
- US2 AS1–AS6 → T029–T037
- US3: AS1, AS5 → T045; AS2, AS3, AS6 (read), AS8 → T043; AS4, AS6 (export), AS7 → T044
- US4: AS1–AS3, AS5–AS11 → T059; AS4 → T060; AS12–AS14 → T064
- US5 AS1–AS7 → T072, T073
- US6: AS1, AS2, AS9, AS10, AS12, AS16, AS17 → T084; AS3, AS4, AS8, AS14, AS15 → T085; AS5–AS7, AS11, AS13 → T088
- US7: AS1, AS4, AS5, AS9, AS10 → T097; AS1 (with entry), AS2–AS4 → T098; AS6, AS7 → T095, T099; AS8 → T100
- Start options `--backupFile` / `--backupRepository` → T079, T087, T088, T091
- Edge cases:
  - version differences → T043, T010
  - 6.1 → T010
  - missing role or CLI right → T019, T020
  - state changed before confirmation → T051, T060, T085, T088
  - maintenance mode → T020, T059
  - nothing to do → T060
  - restart needed → T085, T088
  - own access → T094
  - upload refusals → T076
  - unknown output → T018
  - stage id for a change → T049
  - second change on the same object → T050 and the lock keys in each service task
  - IS Connector payload → T079, T084

FINDINGS:
- [major] research D-2 / data-model `InterfaceCoverage.offered` + `ActionSpec` / T010, T021, T043, T049 – The coverage gate works per whole action: an action is offered only when every entry it declares is `Reachable`. Several actions send different entries depending on their inputs or parts, so one entry that is `undocumented` or `excluded` blocks the whole action:
  - `read_model_details/module` declares `nodeByModel` and `nodeByName`. `nodeByName` is `undocumented` on 9.0, so `module` by `moduleId` is refused on 9.0 too, against US3 AS2 and FR-025. T043 promises that only `moduleName` is refused, but no mechanism does that.
  - `get_server_settings/default` declares every US1 entry. If L-5 excludes one of them (for example `date`), the whole tool is refused forever, against FR-013 ("the other items MUST still be returned"). A node without a StartCLI client is refused at step 6, although the Assumptions say it still gets the REST parts.
  - `deleteWorkflow` / `deleteModule` with an optional `version`, `rename` with five `--rename…` kinds, `setGroups` (`--setGroup` and `--setAdditionalGroups`), and `createUser` with or without `secretEntry`. Under the D-7 fallback (L-1 fails), `create -cu --password` is excluded, so `createUser` without a password is refused too.

  Expected change: gate on the entries a concrete call sends. Give `ActionSpec` the entries per variant or per part, for example `entries(input)`, or let `VersionGate.admitAction` take the entries of the call. For multi-part reads (`get_server_settings`), report a part that is not reachable, or has no client, under `unavailable` with "not yet verified", the exclusion reason or `CLI_UNAVAILABLE`, instead of refusing the call. Update D-2, data-model (`offered`, `ActionSpec`) and T010 (tests: a variant refused while its sibling is offered), and the gate wording in T021, T043, T049, T084, T088 and T097/T098.
- [minor] research D-23 vs data-model `ServerSettings` / T088 – D-23 checks the thread limit against `GET /license` with `/metrics` as fallback. The data model and T088 use `/metrics` first and `GET /license` as fallback. – Align D-23.
- [minor] research D-11 "Alternatives" – It still rejects "copying to a private temp file first", while the decision now uses a verified private copy for StartCLI uploads. – Say that the alternative was rejected for REST uploads only.
- [minor] T085 – `deployKeys` replaces the module's keystore. FR-018 and D-15 require a module backup first, and T083 provides it, but T085 names only the EDI backup. – Add the module backup before `deployKeys` and a test that a failed backup sends nothing.
- [minor] T094 / T095 [P] – `UserImportCheck` (T095) needs the login user's group and roles, which `OwnAccess` (T094) reads. – Make T095 depend on T094, or state that `UserImportCheck` reads them through `CliUserRunner` itself.
- [minor] tasks.md "Parallel opportunities" – These tracks are called parallel but edit the same files:
  - `operate_server` (T056–T061) and `manage_tags` (T062–T064) both change `CliCommand.java`, `CliCommandTest.java` and the `Gateway`/`PortSet` set;
  - `administer_objects` (T080–T086) and `change_server_settings` (T087–T089) both change `CliCommand.java` and the `Gateway`/`PortSet` set.

  Expected change: mark the shared files as serial points, or order T062 after T056 and T087 after T080.
- [minor] Test names differ between the documents:
  - The coverage checks are called `InterfaceCoverageTest` in the contract and research but `InterfaceCoverageCheckTest` in data-model and T008. `InterfaceCoverageTest` is also the name of the domain test in T006.
  - quickstart names tests no task creates: `ConfigurationMaskingTest`, `UserListParserTest`, `UserSessionParserTest`, `ModuleDetailParserTest`, `RuntimeExportParserTest`, `SecretEntryTest`, `AdministrationBackupTest`, `UserImportFileTest`.
  - The plan and quickstart rely on a `ProductionRefusalTest` that does not exist in `src/test`, and T065/T090 do not name the file to create.

  Expected change: use the task names everywhere and name the production-refusal test file in T065.
- [minor] plan.md Structure Decision, increment 6 – It lists "the opt-ins `objects`, `libraries` and `settings`", but T067 (US5) adds all five areas. The `settings` comment in `contracts/configuration-delta.md` also leaves out the backup start options. – Align both.
- [minor] plan.md Project Structure – Types the tasks create are missing: `V81LibraryAdministrationAdapter`, `CliObjectRunner`, `CliUserAdministrationRunner`, `V81UserAdministrationAdapter`, `AdministrationBackups`, `ChangeChallenges`, `UserImportCheck`, `RuntimeExportPaths`, `JsonLeafMasker`. – Add them.

VERDICT: CHANGES_REQUESTED

Files reviewed:
- Feature folder (specs/022-full-interface-coverage/): spec.md, plan.md, research.md, data-model.md, tasks.md, quickstart.md, contracts/coverage-list.md, contracts/mcp-tools-delta.md, contracts/configuration-delta.md, reviews/plan-r1.md
- .specify/memory/constitution.md

Code checked under src/main/java/de/dadecker/inubit/mcp/:
- config/CredentialNames.java
- application/WriteChallengeRegistry.java (the `issue`/`redeem` overloads)
- application/WriteGuard.java (refusal order)
- adapter/rest/InubitHttpClient.java (`get`/`post` string overloads, `encodePathSegment`)
- that these exist: OwnerOnlyFolders, NodeSecrets, CliExportRunner, VersionHistoryParser, ArchiveExportService, CliSessionCommand, V61SessionParser
- test helpers that exist: TestWiring, ScriptedProcessLauncher, RecordingAuditPort, TestSecrets, WriteRaceToolsTest, ToolAnnouncementTest, WiringAuditTest; ProductionRefusalTest does not exist.
