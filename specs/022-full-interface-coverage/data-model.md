# Data Model: Full Coverage of the StartCLI and REST Interfaces

**Feature**: 022-full-interface-coverage | **Plan**: [plan.md](plan.md) |
**Research**: [research.md](research.md)

Records are Java records in `domain/model` unless noted. "New" means added by this feature;
everything else is reused or extended.

## Coverage

### CoverageEntry (new)

| Field | Type | Rule |
|---|---|---|
| `channel` | `CLI` \| `REST` | |
| `entry` | String | CLI: command + one option (`threads setNumberOfWorkflowThreads`); REST: method + path template (`DELETE /diagrams/{name}`) |
| `kind` | `READ` \| `WRITE` \| `EXPORT` | |
| `area` | `A`…`E` \| `PORTAL` \| `START` | |
| `story` | `US1`…`US7` \| none | none only for `covered`, `excluded` or `undocumented` rows |
| `states` | `Map<MajorMinor, CoverageState>` (the existing version-line type) | exactly 8.1 and 9.0 |
| `waits` | `-`, or one or more of `L-n` and `9.0-pin` joined by `+` (e.g. `L-1+9.0-pin`) | what a `planned` row of a delivered story waits for, one item per reason; `-` on every other row |

Unique key: (`channel`, `entry`).

### CoverageState (new, sealed)

- `Reachable(tool, action)`
- `Covered(tool)`
- `Planned`
- `Undocumented`
- `Excluded(reason)`

Transitions: `Planned` → `Reachable` (in the commit with the recording, D-26), `Planned` →
`Excluded` (live check failed, reason recorded), `Planned` → `Covered`. No transition leaves
`Reachable` or `Covered` except by a spec change.

### InterfaceCoverage (new)

Loaded from `coverage/interface-coverage.tsv`:

- `entries()`
- `state(channel, entry, line)`
- `offered(Set<CoverageKey> entries, line)`: true only when every given entry is `Reachable`
  for that line. Callers pass the entries of a concrete call (`ActionSpec.entries(input)`) or of
  one part (D-2, "gate per call").
- `refusal(Set<CoverageKey> entries, line)`: the first entry that is not `Reachable`, with its
  state, for the error text.

Parsing fails at start on:

- a duplicate key;
- a missing state;
- an unknown state word;
- a reason over 120 characters;
- `planned` without `story`;
- a `waits` value other than `-` on a row that is not `planned` for any version, or an unknown
  `waits` value;
- a `planned` row of a story listed in `delivered-stories` with `waits` = `-`;
- a `waits` value other than `-` on a row whose story is not listed in `delivered-stories`.

Unknown tool names and actions are not checked by the loader; `InterfaceCoverageCheckTest`
checks them against the registered tools (coverage-list check 3).

### ActionSpec (new, application)

Per tool action: `tool`, `action`, `capability` (the tool's `Capability`, for the existing
capability admission of `VersionGate`), `area` (`Area`), `entries(input)` (the coverage keys a
concrete call sends, depending on its variant), `parts` (for multi-part reads: per part its
entries and channel, empty otherwise), `declaredEntries()` (the union of all variants and parts,
for coverage check 6),
`kind` (`READ`, `CHANGE`, `RUNTIME_READ`), `bulk` (boolean), `backup` (`BackupKind` or none),
`restartNotice` (none, always, or by setting).

`Area` = `OPERATION`, `OBJECTS`, `LIBRARIES`, `SETTINGS`, `USERS`, `RUNTIME_DATA`, `READ`.

## Configuration

### AdministrationConfig (new, config; per group)

| Field | Type | Default |
|---|---|---|
| `objects` | Boolean | false |
| `libraries` | Boolean | false |
| `settings` | Boolean | false |
| `users` | Boolean | false |
| `runtimeData` | Boolean | false |

Strict booleans (as `unsupported.*`). `EffectiveNodeConfig.administration()` exposes
`enabled(Area)`; `OPERATION` and `READ` are always true.

### UploadsConfig (new, config; per profile)

| Field | Type | Default / rule |
|---|---|---|
| `folders` | List<Path> | empty → uploads off; absolute, existing, no symbolic link |
| `maxBytes.license` | long | 1 MiB |
| `maxBytes.keystore` | long | 1 MiB |
| `maxBytes.userImport` | long | 10 MiB |
| `maxBytes.ediRules` | long | 50 MiB |
| `maxBytes.metadataImport` | long | 50 MiB |
| `maxBytes.isConnectorInput` | long | 50 MiB |
| `maxBytes.library` | long | 200 MiB |

Each `maxBytes` value must be 1 KiB … 4 GiB.

### UploadFile (new, application)

`path` (real path), `type` (`UploadType`), `size`, `sha256`, and an open `SeekableByteChannel`
owned by the call. Created only by `UploadFiles.open` (D-11).

`privateCopy()` gives a `PrivateUploadCopy(path, sha256)`. The copy is written from the open
channel into an owner-only directory, the file is `rw-------`, and its hash is verified. It is
deleted when the call ends. StartCLI uploads and content checks use only this copy.

### SecretEntryName (new)

A value type over `^[A-Z][A-Z0-9_]{0,47}$`. A name equal to a login kind (`USERNAME`, `PASSWORD`,
`TRUSTSTORE_PASSWORD`, `E2E_USERNAME`, `E2E_PASSWORD`), or ending in `_` plus one, is rejected.

It is resolved by `NodeSecrets.entry(name, bound)` to a `Secret`, credential store only (D-10):

- a resolved name equal to a login credential name of any configured node is refused;
- on a node with an AWS source the call is `NOT_CONFIGURED`.

The `Secret` is registered with `SecretScrubber` and never put into a `toString`, a log or an
audit record.

## Reads

### ServerSettings (new, US1)

One per server:

- `node`;
- `license`: `expiryDate`, `licensedThreads`, `licensee` (masked by D-19). `licensedThreads`
  comes from `/metrics`, the source `get_health` uses (US1 AS7). Only when `/metrics` is not
  licensed does it come from `GET /license`. `sources` maps each field to its channel (FR-009),
  so `licensedThreads` is `REST` in both cases; the endpoint is not shown (orchestrator decision
  during T021). `expiryDate` and `licensee` always come from `GET /license`;
- `workflowThreads`;
- `privilegedWorkflows`: `percentage`, `workflows[]` (≤ 200 names, truncation marker);
- `dbConnectionTolerance`: `faultThreshold`, `dropRatePercent`, `dropIntervalSeconds`;
- `blockingQueueSize`, `version`, `memoryUsedPercent`, `maintenanceMode` (the same `MonitoringPort`
  calls as `get_health`);
- `serverTime`;
- `configuration` (see below);
- `sources`: `Map<field, CLI|REST>`;
- `unavailable`: a list of `UnavailablePart`, using the parts `license`, `threads`,
  `privilegedWorkflows`, `dbConnectionTolerance`, `configuration` and `serverTime`, with the reason
  (for example `FORBIDDEN: role System Administrator`). A failed monitoring read is reported under
  `get_health`'s part names: `status` (healthcheck), `version` (system info), `load` (metrics,
  including `METRICS_NOT_LICENSED`), so FR-013 holds for those facts too (decision during T021).

`ConfigurationView`:

- `sections` (names only) when no section was asked for;
- otherwise `section` (name) and `values`: a list of `{path, value | masked: true}`, bounded by
  `ResultLimiter`, with `truncated`;
- `maskedCount`.

### UserSummary, UserDetail (new, US2)

The shapes of `read_users` (T035), each user flat (`user.login`):

- `UserSummary` (view `users`): `login`, `group` (optional), `additionalGroups[]` (optional),
  `roles[]` (optional), `unavailable[]`. It has no personal field: its schema admits no other
  property.
- `UserDetail` (view `user`): `login`, `group` and `superGroup` (optional together),
  `additionalGroups[]` (optional), `roles[]` (optional), `email` (optional), `personal[]`
  (optional; `PersonalField` `{name, value}` or `{name, masked: true}`, masked by `SecretRules`),
  `unavailable[]`.
- `unavailable[]`: `{part, reason, likelyCause?, nextStep?}` as in `get_server_settings`
  (FR-013). An answer that could not be read leaves out its fields and names its parts: `groups`
  (`group`, `superGroup`, `additionalGroups`; from `user <login>`), `roles` (from `finger <login>`), and in
  `UserDetail` also `email` and `personal` (from the same `finger` answer). The user stays in the
  listing.
- `additionalGroups` (T037a, research "Live recording T024/T028b"): the 9.0 row `Additional user
  group` of `user <login>`, one group per row (only one additional group was recorded); absent
  when the answer has no such row. No 8.1 recording showed it. A `user` answer with any other row
  makes `groups` unavailable instead of dropping it.
- `accountState` is not shown (research "Live recording T028", L-6): no account state shows
  anywhere on 8.1 or 9.0. It is added once a recording shows one.

`LoginEntry`: `login`, `since` (optional).

`MessageSummary`: `id`, `sender`, `time`, `subject`. `MessageText`: `id`, `text` (masked,
bounded), `truncated`.

### ModuleDetail, ProcessMetadata, LibraryEntry, IsConnectorCheck (new, US3)

- `ModuleDetail`: `diagram`, `moduleId` or `moduleName`, `type`, `properties` (`{name, value |
  masked}`), `maskedCount`.
- `ProcessMetadata`: `workflow`, `entries` (`{name, value | masked}`), `maskedCount`.
- `LibraryEntry`: `file`, `type` (`driver` / `plugin`).
- `IsConnectorCheck`: `workflow`, `module`, `valid`, `message` (masked, ≤ 2,000 characters).

## Changes

### ChangePreview (new; generalises WritePreview)

| Field | Content |
|---|---|
| `node` | one server |
| `tool`, `action` | |
| `area` | |
| `target` | object kind and name, path, user, operation id, or `bulk` |
| `before` | the current state as shown (masked) |
| `requested` | the requested state (masked) |
| `affected` | for bulk actions: `count` and up to 50 ids or names, plus `truncated` |
| `effects` | fixed sentences (blocking on export, restart needed, maintenance and readiness) |
| `backup` | what will be stored first, or `none: <reason>` |
| `confirmation` | `ConfirmationChallenge` (code, expiry) or none (`CLIENT` mode) |
| `warnings` | |

`previewState` (not returned) is a canonical string of `before` + `affected` (D-14).

### ChangeResult (new)

| Field | Content |
|---|---|
| `node`, `tool`, `action`, `target` | |
| `outcome` | `EXECUTED`, `NOTHING_TO_DO` or `FAILED` |
| `inubitAnswer` | masked, ≤ 2,000 characters |
| `before`, `after` | the re-read state, or `reReadFailed: <reason>` |
| `affectedCount` | the number INUBIT reports, for bulk actions |
| `backup` | path and manifest id, or none |
| `restartNeeded` | true, false or unknown |
| `auditId` | |

### RuntimeTransfer results (new, US5)

- `RuntimeExport`: `operationId`, `host`, `archivePath`, `fileName`.
- `RuntimeImport`: `operationId`, `blockedCount`.
- `BlockedProcess`: `processId`, `workflow` (optional), `state` (optional), paged.
- `ArchiveValidation`: `accepted`, `problems[]` (masked, ≤ 50, each ≤ 500 characters).

### ExportCondition (new, US5)

Either `{column, equals}` or `{column, in[]}`, validated by D-5. `ExportFilter` holds 1–10
conditions and renders the vendor expression.

### Backup manifest (extended)

`BackupStore.Manifest.Kind.ADMINISTRATION` with:

- `scope`: node, object kind (`WORKFLOW`, `MODULE`, `METADATA`, `REPOSITORY`, `TAG_LIST`), name;
- `auditId`;
- `contents` (the file names).

Retention unchanged.

### AuditRecord (reused)

`capability` = tool name; `inputs` = sanitized inputs. The inputs never hold a secret value, an
upload's content or message text beyond 200 characters; a `secretEntry` is stored by name and an
upload by path, size and SHA-256. Outcomes:

- `CHALLENGE_ISSUED` (preview);
- `PENDING` (forced before sending);
- `EXECUTED` / `FAILED`;
- `REFUSED`.

`NOTHING_TO_DO` is recorded as `EXECUTED` with details `nothing-to-do` (spec edge case).

## Validation rules (FR-020)

| Input | Rule |
|---|---|
| server ids | the existing `target` / `node` patterns; changes take exactly one server |
| workflow, module, group, role, user, tag names | the existing name patterns (`^[A-Za-z0-9_.][A-Za-z0-9_.\- ]{0,199}$`); rename targets `^[A-Za-z0-9_. -]{1,200}$` |
| repository paths | `CliCommand.REPOSITORY_PATH` |
| numbers | threads 1 … licensed; privileged share 0 … 99; tolerance ranges from L-5 |
| enumerations | fixed lists in the input schemas |
| message text | 1 … 1,000 characters, no control characters |
| subject | 1 … 200 characters, no control characters or `'` |
| process ids | `CliCommand.PROCESS_ID` |
| operation id | UUID |
| archive file name | D-4 |
| configuration path | `^[A-Za-z][A-Za-z0-9_.]{0,199}(/[A-Za-z0-9_.]{1,200}){0,9}$` (JSON path of the leaf) |
