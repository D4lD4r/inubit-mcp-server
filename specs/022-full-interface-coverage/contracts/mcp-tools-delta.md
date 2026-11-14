# Contract: MCP Tools Delta

**Feature**: 022-full-interface-coverage. Schemas go to
`src/main/resources/schemas/<tool>.{input,output}.json`; descriptions are asserted in full by
each tool's test, as for the existing tools. Types and validation: [data-model.md](../data-model.md).

## Common rules

- **Targets**: read tools take `target` (a stage or a server). Change tools take `node`, exactly
  one server; a stage id gives `INVALID_INPUT` (FR-014).
- **Change inputs**:
  - `action` (enum);
  - the action's fields;
  - `reason` (`^[^\r\n]{1,500}$`, required);
  - `confirmationCode` (`^[A-Za-z0-9_-]{22}$`, second call only).
- **Two calls**: the first call returns a `ChangePreview` with the code. The second call, with the
  same inputs and the code, returns a `ChangeResult`.
- **`CLIENT` confirmation**: allowed only for `operate_server` and `manage_tags` on
  non-production stages. The first call returns the `ChangeResult` directly (D-13).
- **Errors** (`ToolError`, existing shape): `INVALID_INPUT`, `TARGET_UNKNOWN`,
  `PRODUCTION_PROTECTED`, `WRITE_DISABLED`, `NOT_CONFIGURED` (area opt-in, upload folders, secret
  entry), `UNSUPPORTED_VERSION` (note "not yet verified" while the coverage row is `planned`),
  `CLI_UNAVAILABLE`, `AUTH_FAILED`, `FORBIDDEN` (missing INUBIT role, named), `NOT_FOUND`
  (unknown user, object, message or operation id),
  `CONFIRMATION_INVALID`, `CONFLICT` (state changed; details carry the new preview),
  `PRECONDITION_FAILED` (a second change on the same object), `MAINTENANCE_MODE`,
  `UNEXPECTED_RESPONSE` (masked excerpt), `TIMEOUT`. No new error code.
- **Annotations**: `destructive(title)` for every change tool (`readOnlyHint: false`,
  `destructiveHint: true`, `idempotentHint: false`). `readOnly(title, true)` for the three read
  tools. `export_archives` stays `localWrite`.
- **Announcement**: a tool is announced only when at least one node could use it:
  - `get_server_settings`, `read_users`, `read_model_details`: some node is 8.1/9.0 or `AUTO`;
  - `operate_server`, `manage_tags`: some node has write enabled;
  - `transfer_runtime_data`: some group has `administration.runtimeData`;
  - `administer_objects`: some group has `objects` or `libraries`;
  - `change_server_settings`: some group has `settings`;
  - `administer_users`: some group has `users`.

  `ToolAnnouncementTest` gets the new ordered lists.

## `get_server_settings` (US1)

- **Input**:
  - `target` (required);
  - `parts` (optional subset of `license`, `threads`, `privilegedWorkflows`,
    `dbConnectionTolerance`, `configuration`, `serverTime`; default: all but `configuration`);
  - `configurationSection` (optional, implies `configuration`).
- **Output**: `{ settings: [ServerSettings], serverUpdate? }`. Each `ServerSettings` carries
  `unavailable` for parts that failed (FR-013). A stage target gives one entry per server
  (US1 AS5).
- **Description**:
  - reads only, needs the role "System Administrator" for license and configuration;
  - facts `get_health` already shows are taken from the same source;
  - without a section the configuration is listed by section names.

## `read_users` (US2)

- **Input**:
  - `target` (one server);
  - `view`: `users` | `user` | `logins` | `messages` | `message`;
  - `login` (for `user`);
  - `messageId` (for `message`);
  - `offset` (0–9999), `limit` (1–25, default 25).

  `messages`, `message` and `messageId` are offered once L-17 is recorded (owner decision
  2026-11-13).
- **Output**: `{ node, view, sources, page?: Page<UserSummary | LoginEntry | MessageSummary>,
  user?: UserDetail, message?: MessageText, warnings }`. `sources` maps each fact to its channel
  (FR-009):
  - `logins`: REST (`GET /user/users`);
  - `groups`, `roles`, `email`, `personal`, `loggedIn`: CLI;
  - `accountState` once a recording shows one (L-6), `messages` once L-17 is recorded: CLI.

  The user shapes are in data-model.md "UserSummary, UserDetail": flat (`user.login`), without
  `accountState`; `additionalGroups` in both views when INUBIT shows them (9.0, T037a). A user
  whose group or roles could not be read is still listed; the missing part is under its
  `unavailable` (`{part, reason}`, as in `get_server_settings`; FR-013).
- **Rules**:
  - `users` never contains `email` or other personal fields (FR-011, AS5);
  - an unknown login gives `NOT_FOUND`, with the next step "list the users" (AS3);
  - `logins` on 9.0 is refused with `UNSUPPORTED_VERSION` and the exclusion reason of the row
    `-who`, never answered with an empty page: the 9.0 `--who` lists no client connection (owner
    decision 2026-11-14);
  - the description names the `msgread` side effect found by L-17 once the message views are
    offered; until then it says that the messages are not offered yet.

## `read_model_details` (US3)

- **Input**:
  - `target` (one server);
  - `action`: `module` | `libraries`;
  - fields per action:
    - `module`: `diagram` + `moduleId` (`^[0-9]{1,10}$`);
    - `libraries`: `libraryType` (optional, `driver` | `plugin`; without it both types are listed
      in one stdin session), `offset` (0–9999), `limit` (1–100, default 100).
- **Output**: `ModuleDetail` or `Page<LibraryEntry>` with `rowFormatVerified`, with `node` and
  `source` (`REST` for `module`, `CLI` for `libraries`).
- **Rules** (research D-21, "Live recording T039"):
  - `module` reads `GET /model/nodeByModel/{diagram}/{moduleId}` as XML; an unknown id gives
    `NOT_FOUND` (the `500` page with the "could not be found" message), any other answer
    `UNEXPECTED_RESPONSE` with a masked excerpt;
  - there is no `moduleName` input: `GET /model/nodeByName` is absent on 8.1.17 (`excluded:`)
    and `undocumented` on 9.0;
  - `libraries` carries `rowFormatVerified: false` until L-21 records a listed library; the
    description says that each line is read as one file name until then.
- **Planned, not offered in US3** (owner decision 2026-11-14): `metadata` (`workflow`;
  `GET /process/metadata/{name}`, L-19) and `checkIsConnector` (`workflow` + `module`;
  `GET /isconnector?…type=validateWorkflow`, L-20). If their live check records a working form,
  T046b / T046c add them to the `action` enum with their output shapes (`ProcessMetadata`,
  `IsConnectorCheck`); otherwise their rows are excluded in T104 with the FR-002 reasons
  "cannot be built from the documentation: answer form not recordable" and "…: authentication
  not documented".

## `export_archives` (extended, US3)

- **New optional input**: `content`:
  - `workflows` (default, unchanged behaviour);
  - `ownerWithoutGroups` (`excludeGroups[1..20]` required, `diagramGroup` not allowed): the
    owner's technical workflows only (owner decision 2026-11-14), without the named groups
    (`--exportWorkflowType 'technical' --exportWorkflowGroup '' --exportWorkflowGroupExclude
    '<g1,…>'`);
  - `metadata` (`metadataNames[1..50]` optional, `diagramGroup` not allowed): the metadata
    registry, whole or only the named registry entries (`--exportMetadataNames`, comma
    separated). The names are metadata names, not workflow names.
- **Unchanged**: `targetFolder`, the folder rules, `archives.productionOptIn` and
  `acknowledgeClearTextSecrets` (required for `workflows` and `ownerWithoutGroups`).
- **Output**: the existing archive list, plus `source: "CLI"` (FR-009; all three forms use
  StartCLI).
- **Description**: adds the two forms, that `ownerWithoutGroups` exports technical workflows only,
  that `metadata` exports registry entries and accepts unknown names, and the check-in comment
  trace (FR-008).
- **Not offered**: `xslt10Report` (`fetchXSLT1.0 -ef`): the command is not found on INUBIT
  8.1.17 (L-7 fallback, T039); 9.0 is open until recorded (T039a), and T046d adds it for 9.0
  only if the command exists there and the owner decides so.

## `find_processes` (extended, US3)

- **New optional input**: `userColumn` (integer 1–5): show that one user-defined Queue Manager
  column (the data of `ps -u <index>`; owner decisions 2026-11-14).
- **Output**: unchanged without `userColumn`. With it, `userColumn: {index}` and per process
  `userColumnValue` (optional; absent when blank): the REST `queueLog` field
  `userDefined<index>` of the row, not cut to StartCLI's 15 characters, bounded like the row's
  other texts and masked (FR-010).
- **Rules**:
  - 8.1: no extra call; the value comes from the `POST /log/queueLog` rows the search already
    reads (REST first, D-6). `ps -u` is `covered:find_processes`;
  - the gate is `VersionGate.admitOption("find_processes", node, CLI ps -u)`: admitted when the
    row is `Covered("find_processes")` or `Reachable` for the node's line; a missing line (6.1)
    or a `planned` row (9.0 until the US3 9.0 follow-up) gives `UNSUPPORTED_VERSION` "not yet
    verified", an `excluded` row its reason. A server that is not admitted gets `userColumn`
    reported unavailable in the warnings with that error; a call for one such server alone is
    refused with it. Flipping the 9.0 row admits 9.0 without a code change;
  - the annotation (read-only) and every other input are unchanged (SC-009).

## `operate_server` (US4)

- **Actions**:
  - `setMaintenance {on: boolean, executeQueued?: boolean}` (`executeQueued` only with `on`);
  - `setReadiness {ready: boolean}`;
  - `setTrace {on: boolean}`;
  - `setTraceLevel {level: enum}`;
  - `setDebug {on: boolean}`;
  - `triggerMidnightTask {}`;
  - `deleteErrorProcesses {}`.
- **Preview**: `before` / `requested` per action. `deleteErrorProcesses` gives `affected` (count,
  ids, workflows). `effects`:
  - maintenance: no processes run, REST answers "service unavailable", the meaning of `-eq`;
  - readiness: stays NOT READY while maintenance is on;
  - level, trace and debug: stay until set back;
  - midnight task: runs now instead of at its start time, its effect not documented by the
    vendor.
- **Result**: `ChangeResult` with the re-read state. `deleteErrorProcesses` gives
  `NOTHING_TO_DO` when the set is empty (edge case).

## `manage_tags` (US4)

- **Actions**:
  - `setActiveTag {user, tag}`;
  - `removeActiveTag {user}`;
  - `removeTag {tag, scope}`;
  - `deleteTag {tag, scope}`.
- **Scope**: `scope` = `{diagramGroup, diagramType}` | `{repositoryPath}`, required. A missing
  scope gives `INVALID_INPUT` before any read (AS13).
- **Preview**:
  - active tag: the user's current and new active tag;
  - removal: `affected` = the objects and versions carrying the tag in the scope, plus
    `outsideScopeCount`; `backup` = the tag list.
- **Result**: the re-read active tag, or the re-read lists inside and outside the scope.
- **Description**: `removeTag` and `deleteTag` appear only once L-4 has made them reachable;
  otherwise the description says that they are not offered and why.

## `transfer_runtime_data` (US5)

- **Change actions**:
  - `export {fileName, conditions?, excludeModel?}`;
  - `import {fileName}`;
  - `resume {operationId}`;
  - `deleteBlocked {operationId}`.
- **Read actions** (no write enablement, no code):
  - `validate {fileName}`;
  - `listBlocked {operationId, offset, limit}`.
- **Preview**:
  - `export`: the filter, the count if known (L-9), and the blocking sentence;
  - `resume` and `deleteBlocked`: the blocked count and ids (D-14);
  - `import`: the file and the host.
- **Result**:
  - `export` → `RuntimeExport`;
  - `import` → `RuntimeImport`;
  - `resume` and `deleteBlocked` → `ChangeResult` with `affectedCount` and the re-read list.
- **Description**: the archive stays on the INUBIT hosts and the operator moves it; the order
  export → import → resume (target) → deleteBlocked (source).

## `administer_objects` (US6, areas objects and libraries)

**Objects area**:

| Action | Fields |
|---|---|
| `deleteWorkflow` | `name`, `version?` |
| `deleteModule` | `name`, `version?` |
| `deleteRepositoryPath` | `path` |
| `rename` | `kind`, `name`, `newName` |
| `importMetadata` | `file` |
| `startIsConnector` | `workflow`, `module`, `file` |

**Libraries area**:

| Action | Fields |
|---|---|
| `uploadLibrary` | `file`, `libraryType` |
| `deleteLibrary` | `name`, `libraryType` |
| `deleteAllLibraries` | `libraryType` |
| `registerPlugin` / `deregisterPlugin` | the vendor's arguments |
| `deployEdiRules` | `file`, `ruleFileName` |
| `deployKeys` | `file`, `module`, `owner`, `alias`, `propertyName`, `secretEntry` |

- **Preview**:
  - deletes name owner, versions, tags, the active flag and the users of the object (as far as the
    inventory reads them);
  - `backup` per D-15;
  - uploads name the file, its size and SHA-256;
  - `deleteAllLibraries` lists every file (AS4); while L-21 is open (`rowFormatVerified:
    false`) it shows the count only, with the sentence that the format of the library list is
    not verified (D-14).
- **Result**: `ChangeResult` with `restartNeeded` for libraries.

## `change_server_settings` (US6, area settings)

- **Actions**:
  - `setWorkflowThreads {count}`;
  - `setPrivilegedShare {percent}`;
  - `setDbConnectionTolerance {faultThreshold, dropRatePercent, dropIntervalSeconds}`;
  - `changeConfiguration {changes: [{path, value | secretEntry}]}`;
  - `uploadLicense {file}`;
  - `setBackupStartOption {option: backupFile | backupRepository, …}`: its arguments are defined
    once L-16 shows the usage. It is offered only when its rows are `reachable`; otherwise the
    rows are excluded and the action is absent from the schema.
- **Validation**:
  - the thread count must not exceed the licensed threads (`INVALID_INPUT` with the number,
    AS5);
  - a percentage outside 0 … 99 gives `INVALID_INPUT` (AS11);
  - a masked path with a plain value gives `INVALID_INPUT` (FR-022).
- **Preview**: current and new value per setting, masked on both sides (AS6). The license preview
  shows the current expiry and threads (AS7). The tolerance preview carries the shut-down sentence
  (AS13).
- **Result**: the re-read values, `restartNeeded`.

## `administer_users` (US7, area users)

- **Actions**:
  - `createUser {login, group, role, processRole?, email?, secretEntry?}`;
  - `createGroup {name, parent?}`;
  - `createRole {name, basedOn?}`;
  - `setGroups {login, group?, additionalGroups?}`;
  - `addGroups {login, groups}` and `removeGroups {login, groups}`;
  - `setPassword {login, secretEntry}`;
  - `deleteUser {login, removeDiagramReferences, removeRepositoryReferences}` (both required);
  - `deleteGroup {name}`;
  - `importGroups {file}`, `overwriteRoles {file}`, `importLdapDetails {file}`;
  - `syncLdapUsers {}`;
  - `sendMessage {recipients, subject, text}`;
  - `deleteMessage {messageId}`.
- **Refusals**:
  - a change that touches the login user, its groups or its roles (FR-019, D-16);
  - an import file containing them (naming which);
  - `syncLdapUsers` when the login user is directory-managed or this cannot be determined;
  - text longer than 1,000 characters or containing control characters.
- **Result**: no password field anywhere. `deleteUser` re-reads the list (AS5). The audit names
  the `secretEntry`.
