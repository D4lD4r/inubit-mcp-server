FEATURE: specs/022-full-interface-coverage  ROUND: 1

COVERAGE:
- FR-001 → T006, T007
- FR-002 → T007, T021, T045, T065/T089/T097 (exclusions per live check), T099
- FR-003 → T004, T005, T008
- FR-004 → T027, T038, T047, T065, T073, T089, T097 (one PR per story)
- FR-005 → T044 (export_archives extended), D-1 tool split
- FR-006 → T022, T035, T043, T060, T063, T072, T084, T087, T096, T102
- FR-007 → the same tool tasks (schemas and full-description tests)
- FR-008 → T019, T032, T035, T041, T044
- FR-009 → T021 (`sources`), T043 (`source`). read_users has no channel field: see finding.
- FR-010 → T013, T017, T040, T036
- FR-011 → T030, T034, T036
- FR-012 → T044
- FR-013 → T019, T021, T032
- FR-014 → T049, T064, T072, T088, T096
- FR-015 → T051
- FR-016 → T049, T066
- FR-016a → T071
- FR-017 → T051, T058, T059, T071, T083
- FR-018 → T053, T063, T082
- FR-019 → T092
- FR-019a → T061, T063, T065
- FR-019b → T093 (the swap gap is a finding)
- FR-020 → T067, T079, T091, T095
- FR-021 → T074, T075
- FR-022 → T076, T077, T086, T091, T097
- FR-023 → T052
- FR-024 → T059, T063, T071, T083, T086, T095
- FR-025 → T010, T025, T026, T098
- FR-026 → T054, T055, T068, T078, T090 (gates)
- FR-027 → T027, T038, T047, T065, T073, T089, T097, T099 (research note only in T027/T038)
- US1 AS1–AS7 → T014–T023, T025; US2 AS1–AS6 → T028–T037
- US3 AS1, AS5 → T045; AS2, AS3, AS6 (read), AS8 → T043; AS4, AS6 (export), AS7 → T044
- US4 AS1–AS11 → T059, T064; AS12–AS14 → T063
- US5 AS1–AS7 → T071, T072
- US6 AS1–AS4, AS8–AS10, AS12, AS14–AS17 → T083; AS5–AS7, AS11, AS13 → T086
- US7 AS1–AS10 → T092, T093, T095
- Start options `--backupFile`/`--backupRepository` (Background, FR-016 settings) → (none that implements them if L-16 succeeds): see finding.
- Edge cases → T043 (version), T010 (6.1), T020 (403/503), T059 (nothing to do), T083/T086 (restart), T092 (own access), T075 (uploads), T018 (unknown output), T049 (stage id), T050 (second change). The "CLI login access" right is only partly covered: see finding.

FINDINGS:
- [major] research D-10 / contracts/configuration-delta.md / T076 – Secret-entry names can resolve to another node's login entry in the credential store. An entry resolves to `<PREFIX>_<GROUP>_ENTRY_<NAME>`, and login entries are `<PREFIX>_<GROUP>_<NODE>_<KIND>`. Example: in group `test`, the entry name `NODE1_PASSWORD` becomes `<P>_TEST_ENTRY_NODE1_PASSWORD`. That is the login password of node `node1` in a group named `test-entry`/`test_entry`, or of a node named `entry-node1` in the same group. Rejecting only the exact names `PASSWORD`, `USERNAME` and so on does not stop this, so D-10's claim "login entries can never be named" does not hold (Principle II). – Also reject entry names that end in any login kind (`USERNAME`, `PASSWORD`, `TRUSTSTORE_PASSWORD`, `E2E_USERNAME`, `E2E_PASSWORD`). Additionally refuse a resolved name that equals any credential candidate of any configured node. Add both cases to `NodeSecretsEntryTest`.
- [major] research D-10 / contracts/configuration-delta.md ("no new key") / T076 – The AWS Parameter Store source has no prefix. `ParameterNames` holds explicit full names per kind (see docs/setup.md, "AWS Systems Manager Parameter Store"), so `<prefix>/<group>/entries/<name>` and `<prefix>/<group>/<node>/entries/<name>` cannot be built from the current configuration. – Define where the AWS entry path comes from. One option is a new optional key such as `credentials.parameters.entriesPath`, validated with `ParameterNames.isValidName`. Then update the contract (it would then be a new key), `ConfigLoader`/`ConfigValidator`, and T076/T089 docs. The other option is to state that secret entries need the credential store and refuse them for AWS nodes with `NOT_CONFIGURED`.
- [major] D-11 / T079 / T091 / T093 – For uploads that go through StartCLI (`import --importMetadata <path>`, `import --importUserGroup|--overwriteUserRoles|--importLDAPDetails <path>`), StartCLI opens the file again by path. D-11's single-open-handle protection and T075's "a file swapped after open still sends the checked content" do not apply to them. For user imports, a file swapped after `UserImportCheck` would bypass FR-019b, the import of the login user, its group or its roles. – For CLI-sent uploads, copy the checked bytes from the open handle into a private temp file (0600, private directory, deleted in `finally`) and verify its SHA-256. Pass only that path to StartCLI. Add a swap test for a CLI import to T091/T093.
- [major] D-23 vs T083 / T085–T087 / contracts/mcp-tools-delta.md – No task can deliver `--backupFile`/`--backupRepository` (US6, settings area) if L-16 shows they work. D-23 puts them in `change_server_settings`, T083 gates them in `ObjectAdministrationService` (objects area), and the `change_server_settings` contract has no action for them. Only the "excluded" outcome is covered; a successful L-16 would leave the rows `planned`, and T099 would fail. – Choose the tool and area: settings, per FR-016, i.e. `change_server_settings`. Add an action stub to the contract that is gated by the row. Move the gating from T083 to T085/T086, and list the "reachable" path in T089.
- [major] contracts/coverage-list.md check 3 / T007 / T008 / T021 / T025 – "Every `reachable:` action is an action of that tool's input schema" is undefined for tools without an `action` field: all existing tools, `get_server_settings` (`parts`) and `read_users` (`view`). The examples also disagree: the contract shows `reachable:get_server_settings/license`, while T021/T025 use `get_server_settings/read`. T007 writes `export_archives/workflows` in increment 1, but the `content` enum only arrives in T044, so check 3 would fail on the existing rows until US3. – Define the action key per tool: the `action` enum value, else the `view`/`content` value, else a fixed `default`. Make the TSV examples, T007, T021 and T025 use it, and state how check 3 treats tools without an action enum.
- [major] FR-009 / contracts/mcp-tools-delta.md `read_users` / T034 – `read_users` output has no channel per fact. Its data comes from REST (`GET /user/users`) and StartCLI (`user`, `finger`, `-who`, `msglist`), but nothing returns which channel each fact came from. – Add `source` (or `sources` per field, as in `ServerSettings`) to the `read_users` output contract and assert it in T034/T035. Also check the new `export_archives` forms.
- [major] T083, T095 (task size) – T083 implements 12 actions in two areas, with previews, backups, inventory look-ups and AS1–AS17. T095 implements about 17 actions with OwnAccess and the import checks. Neither fits one implement/review round. – Split T083 into objects area (deletes, rename, metadata import, IS Connector) and libraries area (libraries, plug-ins, EDI, keys). Split T095 into users/groups/roles create-change-delete, password and secret path, imports/LDAP sync, and messages. Each split task gets its own AS list.
- [minor] T015–T018, T029–T031 – They are marked [P], but the parsers (T016, T017, T018, T030, T031) return the records created in T015/T029. – Remove [P] from the parser tasks or make them depend on the record task.
- [minor] T008 – Check 6 uses `ActionSpec`/`ActionCatalog`, which T010 creates later. – Order T008 after T010, or move check 6 into T010.
- [minor] plan.md Constitution Check "Result" – It says "Two items are design weights", while Complexity Tracking lists three. – Align the count.
- [minor] quickstart.md increment table – Increment 1 lists 9.0 settings contract tests, which are blocked (T024/T026). `AdministrationConfigTest` sits in increment 6, but `administration` config arrives in T066 (increment 5). – Align with tasks.md.
- [minor] research D-9 – It says "error when an area is enabled while write is off on every node". The contract and T066 make `runtimeData` a warning. – Update D-9.
- [minor] T047, T065, T073, T089, T097 – FR-027 and D-27 require the research note `docs/research/inubit-interfaces.md` in every increment, but only T027/T038 list it. – Add it to the docs list of each PR task.
- [minor] Edge case "missing right CLI login access" – Only the REST 403 path has a test (T020). – Add a runner test (e.g. in T019) asserting that the StartCLI refusal for a missing CLI right gives `FORBIDDEN` naming the right, not `AUTH_FAILED`.
- [minor] T063, T071, T083, T086, T095 – Unlike T059, they do not name `ChangeLocks` (edge case "second change on the same object"). – Name the lock key per action in each service task and add one `PRECONDITION_FAILED` test.
- [minor] T040 / T043 / T044 – SC-005 has parser-level leak checks only. Every other read tool has a tool-level leak test. – Add `security/ModelDetailsSecretLeakTest` (results, errors, logs, audit), covering the new export forms.
- [minor] T011 – All nine capabilities enter `list_nodes` in the US1 PR, before their tools exist. – Add each capability with its story, or state this deliberately in the plan.
- [minor] data-model `ServerSettings.license.licensedThreads` / T016 – The licensed threads are read from `GET /license`, while US1 AS7 says they come from get_health's source (`/metrics`). – Choose one source for the field and name it in `sources`.
- [minor] data-model "InterfaceCoverage" – The data model says the loader fails on an unknown tool name. T006 does not have this rule; it is in T008 check 3. – Align them.
- [minor] T051 – The node-bound form of `WriteChallengeRegistry` exists, keyed by the `Operation` enum (`issue(Operation, NodeId, …)`). – State that the new tools add `Operation` values, or add a `String tool` node overload. Do not leave it as "if only the group form exists".
- [minor] T044, T059, T080 – These are large: three export forms; seven actions with AS1–AS11; six REST endpoints plus a runner, port and wiring. – Consider splitting them.

VERDICT: CHANGES_REQUESTED

Files reviewed: specs/022-full-interface-coverage/{spec.md,plan.md,research.md,data-model.md,tasks.md,quickstart.md,contracts/*.md}, .specify/memory/constitution.md. Codebase checks: src/main/java/de/dadecker/inubit/mcp/config/CredentialNames.java, config/ParameterNames.java, application/WriteChallengeRegistry.java, docs/setup.md (AWS Parameter Store section), schemas for find_processes/get_health.
