# Implementation Plan: Full Coverage of the StartCLI and REST Interfaces

**Branch**: `022-full-interface-coverage` | **Date**: 2026-11-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/022-full-interface-coverage/spec.md`. Base: the
head of `021-server-log-download` (0.9.1 + feature 021). Feature 021 is merged before increment 1
is opened. Target `[Unreleased]`; each increment can be released on its own.

## Summary

The feature makes every documented StartCLI command and REST endpoint of INUBIT 8.1 and 9.0 for
operation and administration either reachable through a tool, covered by one, or excluded with a
reason. It is delivered in seven increments, one per user story:

- **Coverage list as data** (D-2): a TSV resource lists every documented entry with one state per
  version. It produces `docs/interface-coverage.md`, is checked by tests against everything the
  server can send (FR-003), and gates each tool action at run time. An action is offered for a
  version only once its entries are recorded there (FR-025).
- **9 new tools plus `export_archives` extended** (D-1, ≤ 10 per FR-006):
  - three reads: `get_server_settings`, `read_users`, `read_model_details`;
  - two operational changes: `operate_server`, `manage_tags`;
  - `transfer_runtime_data`;
  - three administration tools, one per protection: `administer_objects`,
    `change_server_settings`, `administer_users`.
- **REST first** (D-6, D-8): a `RestEndpoint` registry, plus PUT, DELETE and multipart in
  `InubitHttpClient`. StartCLI is used only where REST has no form. Facts `get_health` already reads
  are not read again.
- **StartCLI stdin session for 8.1/9.0** (D-7): generalised from 6.1, for the password commands
  (never on a visible command line) and for batched reads.
- **Protection** (D-12 … D-17):
  - `ChangeGuard` keeps the order of `WriteGuard` and adds the per-group `administration.<area>`
    opt-in (`NOT_CONFIGURED`);
  - `WriteChallengeRegistry` with preview-state comparison and a re-read before sending (FR-017);
  - `ADMINISTRATION` backups in `BackupStore` (FR-018);
  - `OwnAccess` for the login user (FR-019, FR-019b);
  - `ChangeLocks`;
  - audit as for `restart_process`.
- **Inputs from outside the conversation**:
  - upload files only from `uploads.folders`, checked and streamed from one open handle (REST),
    or from a verified private copy (StartCLI), so a swapped file is never sent (D-11);
  - secrets only by `secretEntry` name, in their own namespace of the node's credential store.
    Names that end in a login kind or resolve to any login entry are refused; nodes with an AWS
    source have no secret entries in this feature (D-10).
- **Capabilities per story**: each story adds its own capability (matrix, label, `list_nodes`
  name) with its tool, so `list_nodes` never names a capability whose tool is not delivered yet.
- **Spec corrections from the vendor pages** (spec "Planning"): the run-time data export blocks
  processes and is a change; the archives stay on the INUBIT hosts; the export filter works on
  user-defined columns; `lls` is excluded.

## Technical Context

**Language/Version**: Java 21 (unchanged; the 020/021 helper stays at `release 17` and is not
touched).

**Primary Dependencies**:

- unchanged: MCP Java SDK, Jackson, logback;
- multipart bodies are built with `java.net.http` and a small `Multipart` body publisher
  (streaming from a file channel);
- XML (license, user list) with the JDK parser and the hardening in `XmlSupport`;
- no new dependency.

**Storage**:

- the coverage list, a classpath resource;
- `ADMINISTRATION` backups in `<home>/.inubit-mcp/<profile>/backups` (existing store, 30-day
  retention);
- audit records in the existing audit log;
- file-producing reads in the caller's folder (`export_archives` rules).

**Testing**:

- JUnit 5, AssertJ;
- WireMock for REST (8.1/9.0 fixtures, now also PUT, DELETE and multipart matching);
- `FakeProcessLauncher` / `ScriptedProcessLauncher` for StartCLI, now with session scripts;
- contract tests per version against recorded, neutralized fixtures (`.origin` for 9.0);
- secret-leak tests per tool that plant known values in every INUBIT answer (SC-005);
- an opt-in `@Tag("live")` `AdministrationLiveTest`, never against production.

**Target Platform**: macOS, Linux, Windows (argument arrays; uploads and links checked with
`NOFOLLOW_LINKS` on every OS).

**Project Type**: single Maven module, shaded JAR.

**Performance Goals**:

- one read call: one REST round trip per REST fact, plus at most one StartCLI run per server (a
  session for batched reads);
- a user page of 25 users: one REST call plus one session;
- every StartCLI run is bounded by `cliTimeout` (reads, simple changes) or `cliExportTimeout`
  (backups, run-time data);
- an upload is limited only by its size limit and the HTTP timeout.

**Constraints**:

- FR-006/SC-007: at most 10 tools; the plan uses 9 new tools and 1 extension.
- FR-008: reads send only read forms. `CliCommand` and `CliSessionCommand` admit only the options
  the coverage list names (`CliCommandTest`).
- FR-014/SC-003/SC-004: every change goes through `ChangeGuard`; production needs its opt-in;
  `WriteRaceToolsTest` and the new `ChangeToolsProductionRefusalTest` (created in T065) grow by
  every new change tool.
- FR-022/Principle II: no secret in an input, result, argv, environment, log or audit record. The
  `PasswordNeverOnCommandLineTest` inspects every recorded `LaunchSpec`.
- `PackageBoundaryTest`: tools only through `application`, `application` only through ports,
  HTTP only in `adapter/rest`, `Process*` only in `adapter/cli`.
- Customer-neutral artifacts only (`NoCustomerIdentifiersTest`, CONTRIBUTING); no customer or host
  name in commits or PR texts.

**Scale/Scope**:

- 9 new tools, `export_archives` extended;
- 18 new schemas, and changed schemas for `export_archives` and `list_nodes`;
- about 70 new production types:
  - domain ≈ 20 (coverage, settings, users, model details, change preview and result, export
    filter, secret rules);
  - application ≈ 22 (services per tool, `ChangeGuard`, `RuntimeReadGuard`, `ChangeLocks`,
    `OwnAccess`, `UploadFiles`, preview-state helpers, audits);
  - adapter ≈ 20 (REST adapters and parsers, StartCLI runners and parsers, session parser,
    `RestEndpoint`, `Multipart`);
  - tools 9;
- ≈ 200 coverage rows.

**Changed**:

- `CliCommand`, `CliSessionCommand`, `CliRunner` (session for 8.1/9.0), `V61SessionParser`
  (generalised), `InubitHttpClient`, all REST adapters (to `RestEndpoint`);
- `Capability`, `CapabilityMatrix`, `NodeOverview`, `VersionGate` (coverage gate);
- `GatewayFactory`, `PortSet`, `RefusingPorts`, `StaleReportingPorts`, `V81Gateway`,
  `AdapterGatewayFactory`;
- `BackupStore` (`ADMINISTRATION`), `ImportService.restore` (accepts it on development nodes);
- `NodeSecrets` (`entry`), `CredentialNames` / `ParameterNames` (entry namespace);
- `PartnerSecretRules` (delegates to `SecretRules`);
- `ExportArchivesTool` and its service;
- `GroupConfig`, `ProfileConfig`, `Defaults`, `ConfigLoader`, `ConfigValidator`, `ConfigSummary`,
  `EffectiveNodeConfig`;
- `Wiring`;
- docs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.* Constitution v1.1.0.

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ⚠️ About 60 new changes, some irreversible (deletes, process deletion, tag removal, license and configuration), on operational stages. | ✅ <ul><li>Every change tool is off unless write is enabled for the stage. The administration areas and run-time data each need their own group opt-in, off by default (D-9, D-12). Production needs its opt-in, and `CLIENT` confirmation is refused there.</li><li>All change tools are annotated `destructive`, not idempotent (contracts).</li><li>Every change is previewed and confirmed with a server code (administration and US5 always). The state is re-read before sending, and a change is refused as `CONFLICT` (D-13, D-14).</li><li>A backup is taken before every delete, rename or replace of an exportable object, and nothing is sent if the backup fails (D-15).</li><li>The server's own access is protected (D-16).</li><li>All inputs are validated before any call: schemas, `CliCommand` allowlist, structured filters, upload rules (D-5, D-11). Argument arrays only; nothing reaches a shell.</li><li>Owner-wide tag forms are never admitted (D-18).</li></ul> |
| II. Secrets Stay Out | ⚠️ Password and key commands; configuration, module properties and user records contain secrets. | ✅ <ul><li>Secrets only by `secretEntry` name, in their own namespace, so the login entries cannot be named (D-10).</li><li>StartCLI password commands only in the stdin session, never in argv (D-7). If L-1 fails they are excluded.</li><li>`PUT /keys` gets the key password in the TLS body.</li><li>Configuration, module and user values are masked with the one 018 rule set moved to `SecretRules` (D-19). `ResultMapper` scrubs again, and entry values are registered with `SecretScrubber` first.</li><li>Personal fields are dropped while parsing user listings (FR-011).</li><li>Upload content never enters results, logs or audit (D-11).</li><li>Secret-leak tests per tool.</li></ul> |
| III. Test-First | ✅ | ✅ Every increment starts with failing tests (quickstart table). Safety and scrubbing rules have dedicated tests. Unit tests are offline with fakes and WireMock. Contract tests per version come from recordings. The live test is opt-in and refused on production. |
| IV. Use-Case-Oriented Tools | ⚠️ "All methods" invites one tool per command. | ✅ Nine task tools with `action` enums, one per story and protection, plus one extension. Each has a description, a typed schema and an output shape (contracts). The tool count stays ≤ 10 (FR-006). Every tool states its use case in the spec. |
| V. Adapter Isolation & Version Compatibility | ⚠️ About 200 entries, none recorded yet; 9.0 differs from 8.1 in places (`nodeByName`). | ✅ <ul><li>REST first, StartCLI only without a REST form (D-6).</li><li>New ports behind `GatewayFactory` and `PortSet`; tool logic has no HTTP or process details.</li><li>Version differences live in the coverage list and the adapters, never in tool logic (D-2).</li><li>An entry is offered for a version only after its recording; 6.1 gets nothing new.</li><li>CLI: argument arrays, explicit timeouts, exit code and `n-OK` evaluation, tested parsers. The session form needs L-1.</li></ul> |
| VI. Bounded, Structured Output | ✅ | ✅ Paged lists (`offset`/`nextOffset`, ≤ 25 users, ≤ 100 items); configuration by section; texts ≤ `ItemBounds`; previews list ≤ 50 ids with a count; files through `export_archives`. Errors use `ToolError` with cause and next step. |
| VII. Observability & Auditability | ✅ | ✅ Every change, executed or refused, and the run-time data reads are audited: `REFUSED`, `CHALLENGE_ISSUED`, `PENDING` (forced before sending), then `EXECUTED`/`FAILED`. Inputs are sanitized; secret entries appear by name and uploads by path, size and hash (FR-023). |
| Tech constraints: TLS, pins | ✅ | ✅ No certificate pin is changed by this feature (FR-019). `PUT /keys` goes to module keystores only. StartCLI TLS handling (feature 017) is unchanged. |
| Tech constraints: minimal dependencies, single artifact | ✅ | ✅ No new dependency; multipart is built on the JDK HTTP client. |

**Result**: PASS, no deviation. Three items are design weights, not deviations (Complexity
Tracking).

### Open questions for the owner (do not block the plan; defaults chosen)

1. **US5 changed by the vendor facts** (spec "Planning", D-3, D-4). The run-time data export is a
   change with write enablement and confirmation, and the archives stay on the INUBIT hosts.
   - Default: as written.
   - Alternative: drop US5 from this feature (the flow needs the operator on both hosts anyway).
2. **`get_server_settings` as a new tool** instead of extending `get_health` (D-1).
   - Default: new tool, because `get_health` stays fast and needs no admin role.
   - Alternative: an `includeSettings` flag on `get_health`, saving one tool.
3. **Version deletions** (`DELETE /diagrams/{name}/version/{n}`, `/modules/{id}/version/{n}`) are
   offered within `deleteWorkflow` / `deleteModule` (D-24).
   - Default: offered, under the objects opt-in and with a backup.
   - Alternative: excluded.

## Project Structure

### Documentation (this feature)

```text
specs/022-full-interface-coverage/
├── plan.md
├── research.md                 # D-1…D-27, live checks L-1…L-18
├── data-model.md
├── quickstart.md               # offline table per increment, live checks, smoke, validation record
├── contracts/
│   ├── mcp-tools-delta.md
│   ├── configuration-delta.md
│   └── coverage-list.md
├── checklists/requirements.md
├── reviews/                    # spec-r1…r3, plan reviews
└── tasks.md                    # /speckit-tasks
```

### Source Code (repository root)

```text
src/main/java/de/dadecker/inubit/mcp/
├── config/              # AdministrationConfig, UploadsConfig (new); GroupConfig, ProfileConfig, Defaults,
│                        # ConfigLoader, ConfigValidator, ConfigSummary, EffectiveNodeConfig,
│                        # CredentialNames/ParameterNames (entry namespace)
├── domain/model/        # InterfaceCoverage, CoverageEntry, CoverageState, SecretRules, ServerSettings,
│                        # ConfigurationView, UserFacts, PersonalField, LoginEntry, Message*, ModuleDetail,
│                        # ProcessMetadata, LibraryEntry, IsConnectorCheck, ChangePreview, ChangeResult,
│                        # ExportCondition, ExportFilter, Runtime* (new); Capability, CapabilityMatrix
├── domain/port/         # ServerSettingsPort, UserDirectoryPort, ModelDetailPort, ServerOperationPort,
│                        # TagAdministrationPort, RuntimeTransferPort, ObjectAdministrationPort,
│                        # SettingsAdministrationPort, UserAdministrationPort (new); Gateway, GatewayFactory
├── application/         # ServerSettingsService, UserReadService, ModelDetailService, ServerOperationService,
│                        # TagAdministrationService, RuntimeTransferService, ObjectAdministrationService,
│                        # SettingsAdministrationService, UserAdministrationService, ChangeGuard,
│                        # RuntimeReadGuard, ChangeLocks, ChangeChallenges, OwnAccess, UserImportCheck,
│                        # UploadFiles, ActionSpec, ActionCatalog, ChangeAudit, AdministrationBackups,
│                        # RuntimeExportPaths (new); BackupStore, ImportService (restore), VersionGate, NodeOverview,
│                        # ExportArchives service
├── adapter/             # PortSet, V81PortSet, V81Gateway, AdapterGatewayFactory, StaleReportingPorts,
│                        # RefusingPorts, NodeSecrets (entry)
├── adapter/rest/        # RestEndpoint, Multipart (new); InubitHttpClient (put, delete, multipart)
├── adapter/rest/v81/    # V81SettingsAdapter, V81UserAdapter, V81ModelDetailAdapter,
│                        # V81ObjectAdministrationAdapter, V81LibraryAdministrationAdapter,
│                        # V81SettingsAdministrationAdapter, V81UserAdministrationAdapter + parsers
│                        # (LicenseXmlParser, ConfigurationJsonParser, UserListXmlParser,
│                        # ModuleDetailJsonParser, ProcessMetadataParser, IsConnectorCheckParser,
│                        # JsonLeafMasker) (new)
├── adapter/cli/         # CliCommand (+commands), CliSessionCommand (version-neutral), CliRunner,
│                        # CliSessionParser (from V61SessionParser), CliOutputClassifier (new forms)
├── adapter/cli/admin/   # new: CliSettingsRunner, CliUserRunner, CliOperationRunner, CliTagAdminRunner,
│                        # CliRuntimeTransferRunner, CliLibraryRunner, CliObjectRunner,
│                        # CliUserAdministrationRunner + parsers (Threads, Privileged, UserImportFile,
│                        # DbTolerance, Finger, UserGroups, Who, MsgList, RuntimeExportAnswer, BlockedList)
├── adapter/archive/v81/ # PartnerSecretRules delegates to SecretRules
├── mcp/tools/           # GetServerSettingsTool, ReadUsersTool, ReadModelDetailsTool, OperateServerTool,
│                        # ManageTagsTool, TransferRuntimeDataTool, AdministerObjectsTool,
│                        # ChangeServerSettingsTool, AdministerUsersTool (new); ExportArchivesTool
└── Wiring.java          # services, ports, announcement conditions
src/main/resources/coverage/interface-coverage.tsv (new)
src/main/resources/schemas/<9 new tools>.{input,output}.json, export_archives.input.json, list_nodes.output.json
src/test/java/…          # tests per quickstart; live/AdministrationLiveTest
src/test/resources/fixtures/{v8_1,v9_0}/{cli,rest}/   # admin-* cases, .origin for 9.0
docs/interface-coverage.md (generated), docs/tools.md, docs/setup.md, docs/research/inubit-interfaces.md,
docs/backlog.md (entry removed at the end), docs/live-tests.md, README.md, SECURITY.md, CHANGELOG.md
```

**Structure Decision**: the same single module. Seven increments; each ends with a green
`mvn -o -q clean verify`, a review, and a pull request to `main` (D-27). Live checks run in
parallel as the operator can approve them; an increment can merge with rows still `planned` while
their live check is open, with the action not offered, as FR-004 allows.

1. **Increment 1 – US1 + coverage foundation**:
   - `RestEndpoint` and the move of every adapter to it (no behaviour change, existing tests
     green);
   - the coverage list with all ≈ 200 rows (existing entries `reachable`, new ones `planned`,
     exclusions with reason), `InterfaceCoverage`, the coverage gate in `VersionGate`, the docs
     page and its tests;
   - the version-neutral session (`CliSessionCommand`, `CliSessionParser`);
   - `SecretRules` extracted (018 tests unchanged);
   - `get_server_settings` (license XML, configuration JSON, threads, privileged, DB tolerance,
     date);
   - L-1, L-5, L-11 (read part) recordings.
2. **Increment 2 – US2**: `read_users` (REST list without personal fields, session per page,
   logins, messages); L-6, L-17.
3. **Increment 3 – US3**: `read_model_details`, the `export_archives` `content` forms; L-7, L-8.
4. **Increment 4 – US4**:
   - `ChangeGuard`, `ChangeLocks`, `ChangePreview`/`ChangeResult`, `ChangeAudit`, preview-state
     helpers, `BackupStore.Kind.ADMINISTRATION` (tag lists);
   - `operate_server` and `manage_tags`;
   - `CliCommand` `trace on|off|-l`, `debug`, `maintenance`, `triggerMidnightTask`,
     `processdelete error`, the new `tag` options;
   - L-2, L-3, L-4 (approved).
5. **Increment 5 – US5**: `ExportFilter`, `RuntimeReadGuard`, `transfer_runtime_data`, the
   `administration.runtimeData` opt-in (the `administration` block starts here); L-9, L-10
   (approved).
6. **Increment 6 – US6**:
   - the enforcement of the opt-ins `objects`, `libraries` and `settings` (the `administration`
     block itself arrives in increment 5);
   - `UploadsConfig` and `UploadFiles`;
   - secret entries (`NodeSecrets.entry`, namespaces);
   - `InubitHttpClient` PUT, DELETE and multipart;
   - `administer_objects` and `change_server_settings`;
   - the `ADMINISTRATION` backups for objects and the `restore_backup` acceptance;
   - L-11 (write), L-14, L-16.
7. **Increment 7 – US7**: the `users` opt-in, `OwnAccess`, the user import file parser,
   `administer_users` with the session password path; L-12, L-13, L-15, L-18. At the end, no
   `planned` row is left (SC-001), and the backlog entry is removed (FR-027).

Docs (`docs/tools.md`, `docs/setup.md`, the research note, `CHANGELOG.md`, the generated coverage
page) change in the increment that changes behaviour (FR-027).

### Touch points with other work

- **021** (`trace --download`, `CliTraceRunner`, `ClientHelper`): `trace` keeps `--download` as
  its own builder path; the switches `on|off|-l` are separate options. The helper is not touched.
- **020** (`unsupported.*` pattern, `SecretTextMasker`): the `administration` block follows the
  same config pattern.
- **018** (`PartnerSecretRules`): the rules move to `SecretRules` without behaviour change; the
  018 tests are the guard.
- **015** (`export_archives`): extended with `content`; the default behaviour, folder rules and
  production opt-in are unchanged (`ArchiveToolReferenceTest`).
- **012** (6.1 session): `CliSessionCommand` and the session parser are generalised; the 6.1
  fixtures and tests stay unchanged.
- **010/011** (secret sources) and **017** (clients): reused; secret entries get a namespace of
  their own.
- **004/005** (`BackupStore`, `restore_backup`): new manifest kind; the restore rules for nodes are
  unchanged.

## Complexity Tracking

No constitution deviation. Three design weights:

| Item | Why needed | Simpler alternative rejected because |
|---|---|---|
| Coverage list as runtime data that gates actions (D-2) | FR-001/FR-003/FR-025 need one source for docs, tests and the "only after recording" rule across ≈ 200 entries | a Markdown table cannot gate actions and drifts; one `Capability` per action makes the matrix and `list_nodes` unreadable |
| StartCLI stdin session on 8.1/9.0 (D-7) | Principle II forbids secrets on a visible command line, and user pages need one JVM start, not 25 | `--execCommand` exposes passwords; `--encryptedPassword` needs `--encryptString` with the clear value on a command line |
| Action enums in nine tools instead of one tool per area or command (D-1) | FR-006 caps tools at 10; Principle IV wants tools per task | one tool per command (≈ 60) degrades tool selection; one tool per opt-in area alone would mix US4 operations into administration and break FR-006's "one story per tool" |
