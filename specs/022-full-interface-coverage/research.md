# Research: Full Coverage of the StartCLI and REST Interfaces

**Feature**: 022-full-interface-coverage | **Date**: 2026-11-12 | **Plan**: [plan.md](plan.md)

Sources: the vendor pages for INUBIT 8.1 and 9.0 (14 StartCLI pages, 85/86 REST pages, retrieved
2026-11-12), `docs/research/inubit-interfaces.md`, `docs/research/spike-development-deployment.md`,
and the code at the base of this branch (0.9.1 + feature 021). Class names refer to
`src/main/java/de/dadecker/inubit/mcp/`.

Every decision has the form Decision / Rationale / Alternatives. The open points that only a test
system can settle are collected as live checks L-1 … L-18 at the end; each names what happens if
the check fails.

## D-1 Tool set (FR-005, FR-006, SC-007)

**Decision**: 9 new tools and 1 extended tool:

| Tool | Story | Kind | Annotation | Replaces / covers |
|---|---|---|---|---|
| `get_server_settings` | US1 | read | readOnly, idempotent | license, threads, privileged workflows, DB connection tolerance, configuration, server time |
| `read_users` | US2 | read | readOnly, idempotent | users, one user, logins, messages |
| `read_model_details` | US3 | read | readOnly, idempotent | module properties, process metadata, library list, IS Connector check |
| `export_archives` (extended) | US3 | read producing files | localWrite (unchanged) | diagrams of an owner without some groups, metadata export, XSLT 1.0 report |
| `operate_server` | US4 | write | destructive | maintenance, readiness, trace, debug, midnight task, deleting processes in error |
| `manage_tags` | US4 | write | destructive | active tag of a user, tag removal/deletion in a group or path |
| `transfer_runtime_data` | US5 | write + reads | destructive | export, validate, import, list, resume, delete blocked |
| `administer_objects` | US6 | write | destructive | objects area and libraries area |
| `change_server_settings` | US6 | write | destructive | settings area |
| `administer_users` | US7 | write | destructive | users area |

Each tool takes an `action` enumeration; every action belongs to the tool's story and its area.
`administer_objects` checks the opt-in of the action's area (`objects` or `libraries`).

**Rationale**: one tool per task, and one tool per protection, so a model reads one description
per decision. The run-time data reads stay with the tool of the flow they serve (FR-016a). The
three file-producing reads go to `export_archives`, which already has the folder rules,
`archives.productionOptIn`, the local-write annotation and the audit they need (FR-012), so no
second tool repeats those rules.

**Alternatives**: extending `get_health` with license and settings (the spec's example in FR-005)
was rejected: `get_health` runs on every server of a stage as the routine status check, needs no
StartCLI and no "System Administrator" role; adding StartCLI runs and the license would slow it
and make it fail for a login user without that role. Separate tools per area (11 or more) exceed
FR-006. One tool for all reads was rejected: its description would mix three stories.

## D-2 Coverage list as runtime data (FR-001, FR-003, SC-001)

**Decision**: the coverage list is a resource `src/main/resources/coverage/interface-coverage.tsv`
loaded at start by `domain/model/InterfaceCoverage`. One row per entry:
`channel` (`CLI` / `REST`), `entry` (`license -td`, `GET /license`), `kind`
(`READ` / `WRITE` / `EXPORT`), `area` (A–E, `portal`, `start`), `story` (`US1`…`US7` or `-`), and
one state per version (`v8_1`, `v9_0`). A state is one of `reachable:<tool>/<action>`,
`covered:<tool>`, `planned`, `undocumented`, `excluded:<reason>`. `docs/interface-coverage.md` is
generated from the file by a test, which fails if they differ.

**Action key** (plan review round 1): the `<action>` part of `reachable:<tool>/<action>` is,
per tool:

- the value of its `action` input, if it has one;
- otherwise the value of `view` (`read_users`) or `content` (`export_archives`, from US3 on);
- otherwise the fixed key `default` (every existing tool, `get_server_settings`, and
  `export_archives` until US3).

When US3 adds `content`, its rows change from `export_archives/default` to
`export_archives/workflows`.

**Gate per call** (plan review round 2): an action does not have one fixed entry set. Its
`ActionSpec` gives `entries(input)`, the coverage entries a concrete call sends. These depend on
the variant: `moduleId` or `moduleName`, with or without `version`, the `rename` kind,
`setGroup` and/or `setAdditionalGroups`, with or without `secretEntry`.

- **A call** is admitted only when every entry of `entries(input)` is `reachable` for the node's
  version. Otherwise it is refused with `UNSUPPORTED_VERSION`, naming the variant and either
  "not yet verified", "not documented for this version" or the exclusion reason. A sibling
  variant whose entries are reachable stays offered. Examples:
  - `module` by `moduleId` works on 9.0 while `moduleName` is `undocumented`;
  - `createUser` without a password works when `create -cu --password` is excluded.
- **Multi-part reads** (`get_server_settings`; `read_users` per view) are not refused for one
  part. Each part has its own entries and channel. A part whose entries are not reachable, or
  whose channel is not available on the node (no StartCLI client), is reported under
  `unavailable` with its reason: "not yet verified", the exclusion reason or `CLI_UNAVAILABLE`.
  The call is refused only when no requested part can be read (FR-013, spec Assumptions: a stage
  without a client gets the REST parts). `CapabilityMatrix` stays at the level of a
capability per tool: a tool's capability is `SUPPORTED` for a version once at least one of its
actions is reachable there.

Tests (FR-003):

- `InterfaceCoverageCheckTest` (`architecture/`): every command and option in `CliCommand.OPTIONS`, every
  `CliSessionCommand`, and every `RestEndpoint` value appears in the list. Every
  `reachable` / `covered` tool exists in `Wiring.toolHandlers()`. Every row has a state for both
  versions. No row is `planned` once `story` is delivered: the release check reads a
  `delivered-stories` line in the file.
- `InterfaceCoverageDocsTest`: the generated docs page matches.

**Rationale**: one source for docs, tests and the runtime gate. A recording commit flips one row,
as feature 021 flipped the matrix (constitution, Principle V).

**Alternatives**: a Markdown table only (cannot gate actions, drifts); one `Capability` per
action (some 60 enum values, `list_nodes` would become unreadable).

## D-3 The run-time data export is a change (FR-016a, spec "Planning")

**Decision**: `process export` runs under FR-014/FR-015 with the run-time data opt-in, like the
import. The preview names the filter, the number of processes the filter matches as far as
the server can count them (see L-9), and the sentence that the matching processes stay blocked on
the source until `resume` or `deleteBlocked`.

**Rationale**: the vendor page states that processes marked for export are blocked and no longer
executed. FR-016a said that a change of the source makes the export a write.

**Alternatives**: keeping it a read with only the opt-in would let an agent stop processes on
production without the write enablement.

## D-4 Run-time data archives stay on the INUBIT hosts

**Decision**: the export takes a file name (`^[A-Za-z0-9][A-Za-z0-9._-]{0,99}\.zip$`), never a
path. INUBIT writes it to `<install>/ibis_root/ibis_data/backup` of the server it runs on. Import
and validation take a file name the same way. The server builds the path INUBIT expects:

- the absolute path reported by an earlier export of that server, if there is one;
- otherwise the name relative to the server's backup folder, if INUBIT accepts that (L-10).

The result of an export names the host and the path from INUBIT's answer
(`The export file is located at: …`) and the operation id.

**Rationale**: StartCLI runs these commands on the INUBIT server; the archive is never on the MCP
machine. A path from the caller would reach a command line on the INUBIT host.

**Alternatives**: copying the archive through the server (no documented download for that
folder); an upload folder for the archive (the file does not pass through the MCP machine).

## D-5 Run-time data export filter grammar (FR-020)

**Decision**: input `conditions`: a list of 1–10 entries `{column, equals}` or `{column, in[]}`.

- `column` must match `^[A-Za-z][A-Za-z0-9_]{0,63}$`.
- A value must match `^[^'(),\u0000-\u001F\u007F]{1,200}$` (no quote, no parentheses, no comma,
  no control character); an `in` list holds 1–50 values.

The server builds `'c1=v1 AND c2 in (a, b)'`, single-quoted as the vendor requires, through
`CliCommand.Builder`. `inFile` and `--filterProcessIds` are not offered (spec, exclusions).
`excludeModel` is an optional boolean for `--excludeModel`.

**Rationale**: the vendor grammar is `AND` plus `=` and `in`; the excluded characters are its
separators. Structured fields keep free text off the command line.

**Alternatives**: a raw expression checked by a parser (FR-020 forbids raw expressions).

## D-6 Channel per entry: REST first, nothing read twice (FR-002, Principle V)

**Decision**:

| Entry | State | Through |
|---|---|---|
| `license -td`, `license -ts` | covered | `GET /license` (`get_server_settings`) |
| `getBlockingQueueSize`, `-sv`, `-pctmem`, `-maintenanceState` | covered | `get_health` (`/metrics`, `/system/info`, maintenance probe); `get_server_settings` takes these facts from the same `MonitoringPort` calls |
| `-ne`, `-nx`, `-nw`, `-nq` | covered | `get_health` (`processCounts`) |
| `ps -f 'workflow=… AND tag=…'` | covered | `find_processes` (`POST /log/queueLog` filters `workflow`, `tag`) |
| `ps -u` | planned → L-8 | — |
| `delete --deleteWorkflow` | covered | `DELETE /diagrams/{name}` |
| `delete --deleteModule` | covered | `DELETE /modules?moduleName=` |
| `delete --deleteUser` | covered | `DELETE /user/users/{name}` |
| `library --upload`, `library --delete` | covered | `PUT /libraries`, `DELETE /libraries/{name}` |
| `library --list`, `library --deleteAll` | reachable | StartCLI (no REST form) |
| `POST /edirules` | covered | `PUT /edirules` (same effect, documented together) |
| `POST /configurations` | covered | `PUT /configurations` |
| `user <name>` | reachable | StartCLI (groups); `finger <login>` for the roles |
| `GET /user/users` | reachable | the login list of `read_users` |

The other entries have one channel only.

**Rationale**: the constitution prefers REST. `get_health` already reads these facts, and FR-002
forbids reading them twice.

**Alternatives**: none; the rule is fixed by the constitution.

## D-7 StartCLI on 8.1/9.0: script mode and a stdin session

**Decision**: two forms.

- **Script mode** (`--execCommand`, as today) is used for every command without a secret.
- **Stdin session**: StartCLI is started without `--execCommand`. The commands go to stdin one
  per line, after the login password, ending with `exit`, as `V61Connection.session` does for
  6.1. The session form is used for:
  - commands that carry a secret: `passwd --password`, `create -cu … --password` (FR-022);
  - batches of reads for one page: `user <name>` and `finger <name>` for up to 25 users, and the
    settings reads of US1 in one run.

`CliSessionCommand` becomes version-neutral. The 6.1 constants stay, and the new commands are
added with the same allowlist and quoting rules as `CliCommand`. `CliRunner.runSession` is reused.
`V61SessionParser.blocks` is generalized into a session parser that splits the output by the
`CLI|<user>@<host>>` prompt echo (format checked by L-1). A secret value is registered with
`SecretScrubber` before the session starts, so an echo of the command is masked.

**Rationale**: `--execCommand` puts the whole command on the process's command line, which other
processes can read. Principle II allows stdin "where the INUBIT CLI permits it". One JVM start per
user would make a page of 25 users take minutes.

**Alternatives**: `--encryptedPassword` (needs `--encryptString` first, which puts the clear value
on a command line; excluded); excluding the password commands outright (only if L-1 fails).

**If L-1 fails** (no stdin session on 8.1/9.0): `passwd` and `create -cu --password` are excluded
with the reason "the secret can only be passed on a visible command line" (FR-022). User listings
then run one script call per user, with a page size of 10.

## D-8 REST client: new methods and an endpoint registry

**Decision**:

- `adapter/rest/InubitHttpClient` gains:
  - `put(path, query, JsonBody | Multipart, …)`;
  - `delete(path, query, …)`;
  - `post` with JSON and with an empty body;
  - multipart parts streamed from a file (`Multipart.file(name, Path, contentType)`) or a field.
- Response bodies keep the 64 MiB cap. Uploads are bounded by D-11, not by the cap.
- A new enum `adapter/rest/RestEndpoint` names every method and path the server sends, existing
  ones included (`GET /healthcheck`, …, `POST /log/{name}`).
- Adapters pass a `RestEndpoint` and the path parameters. A new test in
  `architecture/PackageBoundaryTest` style (`RestEndpointRegistryTest`) fails when a class outside
  `RestEndpoint` contains the literal `/ibis/rest`.
- Error mapping stays in `statusError`. A 200 answer with an error text in `responsedata` (vendor
  pattern for PUT) is mapped by the adapter.

**Rationale**: FR-003 needs a list of what the server can send. Today the paths are literals
spread over the adapters.

**Alternatives**: scanning the bytecode for strings (fragile).

## D-9 Configuration (FR-016, FR-021)

**Decision**:

- **Per group** (`GroupConfig`, like `archives` and `unsupported`): a new block
  `administration: {objects, libraries, settings, users, runtimeData}`. The values are strict
  booleans, default `false`. `ConfigValidator` gives a warning for every area enabled on a
  production group. When an area is enabled while `write.enabled` is false on every node of the
  group, it gives an error, because the opt-in would have no effect. The exception is
  `runtimeData`, which gives a warning, because its two reads need no write enablement. Pattern and touched files as for
  `unsupported.serverLogRead` (`Defaults`, `ConfigLoader`, `ConfigValidator`, `ConfigSummary`,
  `EffectiveNodeConfig`, `Wiring`, docs).
- **Per profile** (`ProfileConfig`): `uploads: {folders: [...], maxBytes: {license, keystore,
  userImport, ediRules, metadataImport, isConnectorInput, library}}`. `folders` is empty by
  default. Each folder must be absolute, existing and not a symbolic link, checked at start. Size
  defaults per FR-021 (a value is 1 KiB … 4 GiB).
- `list_nodes` shows for each node which areas are enabled. `--check-config` prints them.

**Rationale**: the administration areas are a property of the stage, like the archive opt-in;
uploads are local to the MCP machine and so belong to the profile.

**Alternatives**: one flag for all areas (the spec wants one per area); node-level opt-ins (the
existing opt-ins are group-only, and a stage is the unit of risk).

## D-10 Named secret entries (FR-022, Principle II)

**Decision**: inputs that need a secret take `secretEntry` (`^[A-Z][A-Z0-9_]{0,47}$`). The server
resolves it through the node's credential store, in its own namespace
`<PREFIX>_<GROUP>_<NODE>_ENTRY_<NAME>`, then `<PREFIX>_<GROUP>_ENTRY_<NAME>`.

Two rules keep the login entries out of reach (plan review round 1):

- a name equal to a login kind, or ending in `_` plus a login kind (`USERNAME`, `PASSWORD`,
  `TRUSTSTORE_PASSWORD`, `E2E_USERNAME`, `E2E_PASSWORD`), is rejected (`INVALID_INPUT`);
- a resolved name that equals any credential candidate of any configured node
  (`CredentialNames` for every group and node of the profile) is refused (`INVALID_INPUT`), so a
  group or node id containing `ENTRY` cannot make an entry name collide with a login entry.

**AWS Parameter Store**: `ParameterNames` holds a full name per kind and has no prefix from which
an entry path could be built. Secret entries are therefore offered only on nodes whose secrets come
from the credential store. On a node with an AWS source, every action that needs a `secretEntry`
is refused with `NOT_CONFIGURED`, naming the credential store as the requirement. An AWS form (an
optional entries path) is a backlog item, not part of this feature.

The value is registered with `SecretScrubber` before it is used. It is read in `NodeSecrets`
(new `entry(name, bound)`) and is never stored in a record that is logged or audited. The audit
record names the entry. A missing or unreadable entry is refused with `NOT_CONFIGURED` before any
call to INUBIT (US7 AS3).

A configuration value that the rules of D-19 mask is changed only with a `secretEntry`; a plain
value for it is `INVALID_INPUT` (FR-022).

**Rationale**: a separate namespace prevents a caller from using a tool to copy the server's own
login password into an INUBIT setting. It reuses the sources of features 010/011 (spec
assumption).

**Alternatives**: free entry names (a caller could name the login entry); a new kind of source
(out of scope).

## D-11 Upload files (FR-021)

**Decision**: `application/UploadFiles.open(Path, UploadType)` returns an `UploadFile(path, size,
sha256, type)`. Before any call to INUBIT it checks, in this order:

1. the path is absolute;
2. `NOFOLLOW_LINKS`: the file is a regular file and not a symbolic link, and no path element is a
   link;
3. its real path is inside one of `uploads.folders`;
4. its size is ≤ `maxBytes` of its type.

The file is hashed while it is checked. Then:

- **REST uploads** are sent as a stream from the checked file handle, opened once, so a swap after
  the check cannot change what is sent.
- **StartCLI uploads** (`import --importMetadata`, `--importUserGroup`, `--overwriteUserRoles`,
  `--importLDAPDetails`) need a path, and StartCLI opens the file again. Here the checked bytes
  are copied from the open handle into a private file (owner-only directory through
  `OwnerOnlyFolders`, file `rw-------`). The copy's SHA-256 is verified against the check, and
  only that path is passed to StartCLI. Every content check (for example FR-019b in D-16) reads
  the private copy, never the caller's path. The copy is deleted in `finally` and on shutdown.

Refusals use `INVALID_INPUT` with the rule that failed. The content never enters a result or a
log.

**Rationale**: the spec edge case lists exactly these refusals. One open handle avoids a gap
between the check and the read.

**Alternatives**: for REST uploads, a private copy first was rejected: it doubles the I/O for
200 MiB libraries without a gain over the open handle. For StartCLI uploads the private copy is
the decision above, because StartCLI opens the file again by path.

## D-12 Guards and protection order (FR-014, FR-015, FR-016)

**Decision**: a new `application/ChangeGuard.admit(tool, nodeId, Area, actionEntries)` reuses the
order of `WriteGuard`:

1. group id → `INVALID_INPUT`, unknown → `TARGET_UNKNOWN`;
2. production without opt-in, or production with `CLIENT` → `PRODUCTION_PROTECTED`;
3. write not enabled → `WRITE_DISABLED`;
4. **area opt-in missing → `NOT_CONFIGURED`**, next step naming `administration.<area>` (not for
   US4);
5. version gate and coverage gate (D-2) → `UNSUPPORTED_VERSION`;
6. channel availability → `CLI_UNAVAILABLE` / `AUTH_FAILED`.

`Area` = `OPERATION` (US4, no opt-in), `OBJECTS`, `LIBRARIES`, `SETTINGS`, `USERS`,
`RUNTIME_DATA`. Every refusal is audited as `REFUSED`.

The run-time data reads (`listBlocked`, `validateExport`) use `RuntimeReadGuard`: the opt-in plus
steps 1, 5 and 6, no write enablement, audited.

**Rationale**: the same order as the existing write tools, so `WriteRaceToolsTest` and the
production tests extend naturally. `NOT_CONFIGURED` is the code already used for a missing
opt-in (features 020 and 021).

**Alternatives**: a new error code `AREA_DISABLED` (one more code to document for the same
meaning).

## D-13 Confirmation (FR-015)

**Decision**: every change uses `WriteChallengeRegistry.issue(tool, NodeId, inputFingerprint,
previewState, ttl)`. This is a new overload keyed by the tool name (`String`) and a node, next to
the existing `issue(Operation, NodeId, …)` (keyed by `DevelopmentGuard.Operation`) and
`issue(String tool, GroupId, …)` (deployments). The new tools add no `Operation` values, because
`Operation` belongs to the development guard. The fingerprint is SHA-256 over the canonical inputs, without
`confirmationCode` and `reason`. `previewState` is the state the preview showed (D-14). The
validity is `confirmationTtl`.

- US4 follows the node's `write.confirmation`. With `CLIENT` (not allowed on production), the first
  call executes, as for `restart_process`.
- The administrative areas and US5 always issue a server code, as `forRepositoryWrite` does.

**Rationale**: the registry already binds tool, node and input. `ConfirmationRegistry` is specific
to process ids.

## D-14 Preview state and re-read (FR-017, FR-024, edge case "state changed")

**Decision**: every action defines its `previewState` as a canonical string:

- a single object: name, version, active flag, checksum of the backup export, or the current
  value;
- a bulk set: the sorted id list (`processdelete error`: the process ids in state `ERROR` from
  `POST /log/queueLog`; libraries of a type: the file names from `library --list`; blocked
  processes: the ids from `process listBlocked`); where INUBIT gives no ids, the count.

On redeem, the server reads the state again right before sending. A different state is
`CONFLICT` with a new preview and a new code in the error details. After execution the affected
state is read again and returned (`after`), or the result says `reReadFailed` with the reason.

**Rationale**: FR-017 defines "differs" this way; `WriteChallengeRegistry.redeem` already returns
the stored state for this comparison.

## D-15 Backups (FR-018)

**Decision**: `BackupStore` gets `Manifest.Kind.ADMINISTRATION` with `scope` (node, object kind,
name) and the audit id.

| Change | Backup |
|---|---|
| delete, rename, delete a version of a workflow or module | StartCLI export (`CliExportRunner.exportWorkflowGroup` / `exportModule`, history included) |
| metadata import, metadata rename | `export --exportMetadata` of the named workflows |
| repository file or directory delete or rename, EDI rule overwrite | `export --exportRepositoryPath` |
| keystore to a module | export of the module |
| tag removal or deletion | JSON list of the objects and versions carrying the tag (from the version history, `VersionHistoryParser`) |

If the backup fails, nothing is sent (FR-018). Retention and sweep are unchanged (30 days, newest
per scope kept).

`restore_backup` accepts an `ADMINISTRATION` manifest only on development nodes and only for
workflow and module exports, through the existing import path. Its node rules do not change.
Other administration backups are listed in the result with their path for a manual import.

**Rationale**: reuses the export runners and the manifest. The spec forbids widening
`restore_backup` to other stages.

## D-16 The server's own access (FR-019, FR-019b)

**Decision**: `application/OwnAccess` reads the login user (`WritePolicy.account`), its group and
additional groups (`user <login>`) and its roles (`finger <login>`). It does so at preview time
and again right before sending. It refuses:

- deleting, regrouping or re-passwording the login user;
- removing one of its groups;
- deleting its primary group or a role it holds;
- removing "CLI login access" from its role (if a change can express that; see L-15).

File imports (`--importUserGroup`, `--overwriteUserRoles`, `--importLDAPDetails`) are parsed
before sending. The file format comes from L-12; an unparsable file is refused. A file that names
the login user, its group or one of its roles is refused, naming which.

`--syncLDAPUsers` is refused unless the login user's record shows an internal user (L-13); when
this cannot be determined, it is refused.

**Rationale**: the spec's rules; reading twice closes the gap between preview and execution.

## D-17 Change locks (edge case "second change")

**Decision**: `application/ChangeLocks` holds a set of keys `node|area|object` (object = name, path,
operation id, or `*` for a bulk action). A key already taken is `PRECONDITION_FAILED`; the key is
released in `finally`. This generalises the key set of `ProcessControlService`.

Every change also runs inside `WriteLock.handOff(permit, …)`, so a version change between
admission and sending refuses it, as for the existing write tools (`WriteRaceToolsTest`).

## D-18 Tags (FR-019a, US4 AS12–AS14)

**Decision**: `manage_tags` actions:

- `setActiveTag {user, tag}` and `removeActiveTag {user}` (`tag --tagSetActive|--tagRemoveActive
  --tagUser`);
- `removeTag` and `deleteTag` with exactly one scope: `{diagramGroup, diagramType}`
  (`--tagWorkflowGroup` + `--tagWorkflowType`) or `{repositoryPath}` (`--tagRepositoryPath`).

`CliCommand.OPTIONS["tag"]` gains `--tagSetActive`, `--tagRemoveActive`, `--tagDelete`,
`--tagRemove` and `--tagRepositoryPath`. A builder rule rejects `--tagDelete` / `--tagRemove`
without a scope option. `--tagDiagram` and `--tagWorkflowGroupExclude` are never admitted
(`CliCommandTest`).

`removeTag` and `deleteTag` start as `planned` and become `reachable` only if L-4 shows that
INUBIT limits them to the scope; otherwise they are `excluded:acts-on-whole-owner`. The tool then
offers only the active-tag actions.

Before a removal the preview lists the objects and versions carrying the tag in the scope (version
history export, as `tag_artifacts` reads it) and the count outside the scope that must stay; the
result re-reads both.

## D-19 Secret rules for configuration, modules and users (FR-010)

**Decision**: the feature-018 rules move from `adapter/archive/v81/PartnerSecretRules` into a
public `domain/model/SecretRules`:

- `isSecretName(String)`;
- `isSecretValue(type, encryptedFlag, value)`;
- key-material detection through `KeyMaterial`.

`PartnerSecretRules` delegates to it and its tests stay unchanged. The configuration JSON, module
properties and user records are walked leaf by leaf; a leaf is masked when its name or path, its
`encrypted` flag or its value matches. Results carry `maskedCount`. `ResultMapper` scrubs known
values again.

**Rationale**: FR-010 asks for one rule. The 018 rules are the strictest ones the project has.

## D-20 Users and logins read (US2, FR-011)

**Decision**:

- **Listing**: `GET /user/users?type=processEngineUser` gives the logins, sorted by login and paged
  with `offset` / `limit` (≤ 25). The parser reads only `id` and `type`; `email`, `firstName` and
  `lastName` are dropped while parsing (FR-011, SC-005). For the logins of the page one session
  (D-7) runs `user <login>` (groups) and `finger <login>` (roles).
- **One user**: the same session, plus `email` from `finger` / REST. An account state is reported
  only if INUBIT shows one (L-6).
- **Logins**: `-who` (names, login time) and `-nwho`.
- **Messages**: `msglist`, then `msgread <id>`. The text is masked and limited to
  `ItemBounds.MAX_ITEM_CHARS`.

The spec's "cursor" (US2 AS1) is the project's `nextOffset`, the paging form of every other tool.

**Rationale**: REST has no groups or roles; one session per page keeps one JVM start per page.

## D-21 Model details and file-producing reads (US3, FR-012)

**Decision**:

- **`read_model_details` actions**:
  - `module`: `GET /model/nodeByModel/{diagram}/{moduleId}` on 8.1 and 9.0, and
    `GET /model/nodeByName/{diagram}/{module}` on 8.1 only (9.0: `undocumented`).
  - `metadata`: `GET /process/metadata/{name}`.
  - `libraries`: `library --list --type driver|plugin`.
  - `checkIsConnector`: `GET /isconnector?workflowName&moduleName&type=validateWorkflow`.
  - Properties and metadata are masked by D-19.
- **`export_archives` new optional `content`**:
  - `workflows` is the default and today's behaviour.
  - `ownerWithoutGroups` with `excludeGroups[1..20]` (`export --exportWorkflowGroupExclude`) gives
    one archive for the owner. It needs `acknowledgeClearTextSecrets` like today.
  - `metadata` with `workflows[1..50]` (`export --exportMetadata`; `--exportMetadataNames` lists
    the names first).
  - `xslt10Report` (`fetchXSLT1.0 -ef`).
  - The schema uses `if`/`then` per `content`; `diagramGroup` is required only for `workflows`.
  - The check-in comment trace of StartCLI exports is named in the description (FR-008).
- Where `fetchXSLT1.0 -ef` writes its file is checked by L-7. If the file is written on the
  INUBIT host, the entry becomes `excluded:file-written-on-the-inubit-host`.

## D-22 Operating a server (US4)

**Decision**: `operate_server` actions:

| Action | Command | Notes |
|---|---|---|
| `setMaintenance` | `maintenance on\|off [-eq true\|false]` | `-eq` only with `on`; the state is read before and after with `maintenance -s` |
| `setReadiness` | `POST /ready/{on\|off}` | before and after from `GET /ready`; the preview carries the vendor sentence on maintenance mode |
| `setTrace` | `trace on\|off` | |
| `setTraceLevel` | `trace -l <level>` | levels from the vendor's fixed list, L-3 |
| `setDebug` | `debug on\|off` | |
| `triggerMidnightTask` | `triggerMidnightTask` | the configured start time is shown if `GET /configurations` holds it |
| `deleteErrorProcesses` | `processdelete error` | set and re-read by D-14 |

`CliCommand.OPTIONS["trace"]` keeps `--download` (feature 021) and gains `on`, `off` and `-l`. The
`trace --download` path stays a separate builder method.

Switching maintenance off uses StartCLI over the SOAP servlet, which the vendor does not list
among the answers blocked in maintenance mode (L-2). The read before the change accepts
`MAINTENANCE_MODE` from REST as "on".

## D-23 Settings changes (US6, settings area)

**Decision**: `change_server_settings` actions:

| Action | Command | Check |
|---|---|---|
| `setWorkflowThreads` | `threads setNumberOfWorkflowThreads n` | 1 … the licensed threads from `/metrics` (`licensedThreads`, the source `get_health` uses), fallback `GET /license` when `/metrics` is not licensed; refused otherwise |
| `setPrivilegedShare` | `privilegedWorkflows setThreadsPercentage p` | 0 … 99, as documented |
| `setDbConnectionTolerance` | `dbconnectiontolerance set …` | fault threshold, drop rate, drop interval; ranges from L-5 |
| `changeConfiguration` | `PUT /configurations` | `changes[1..20]` of `{path, value \| secretEntry}`; the server reads the full configuration, applies the changes and sends the full document back (L-11 checks whether a partial document is accepted) |
| `uploadLicense` | `PUT /license` | multipart `licenseFile` |
| `setBackupStartOption` | `--backupFile`, `--backupRepository` | settings area; arguments and checks per L-16; the action is offered only when its rows are `reachable`, and the rows are excluded if L-16 fails |

Every result says whether a restart is needed: threads and the privileged share always need one
(vendor), other settings per L-11.

The `SeverConfiguration` root key of the documented GET answer (sic) and `ServerConfiguration` of
PUT are both accepted by the parser. The PUT uses what GET returned (L-11).

## D-24 Objects and libraries (US6)

**Decision**: `administer_objects` actions:

- **Objects area**:
  - `deleteWorkflow {name[, version]}` → `DELETE /diagrams/{name}` or `/diagrams/{name}/version/{n}`;
  - `deleteModule {name[, version]}` → `DELETE /modules?moduleName=` or
    `/modules/{id}/version/{n}`, with the id from the inventory;
  - `deleteRepositoryPath` → `DELETE /repository`;
  - `rename` (kind workflow, module, metadata, file or directory) → `rename --rename…`, names
    checked against `^[A-Za-z0-9_. -]{1,200}$`;
  - `importMetadata` → `import --importMetadata <upload>`;
  - `startIsConnector` → `POST /isconnector`, `planned` until L-14.
- **Libraries area**:
  - `uploadLibrary` → `PUT /libraries`;
  - `deleteLibrary` → `DELETE /libraries/{name}`;
  - `deleteAllLibraries {type}` → `library --deleteAll --type`;
  - `registerPlugin` / `deregisterPlugin` → `plugin --register|--deregister`, with the arguments
    the vendor documents;
  - `deployEdiRules` → `PUT /edirules`;
  - `deployKeys` → `PUT /keys` with `privateKeyPassword` from `secretEntry` (multipart field over
    TLS, never logged).

The preview of a delete names the users of the object as far as the inventory can read them
(`list_inventory`, `get_inventory_item`).

The version deletions (`…/version/{n}`) are offered because the coverage list must give every
documented entry a state, and they need no protection beyond the objects area and the backup.

## D-25 User administration (US7)

**Decision**: `administer_users` actions:

- `createUser` (`create -cu` with group, role, optional `secretEntry` for `--password`; session
  form);
- `createGroup` (`-cug` with parent) and `createRole` (`-cur --basedon`);
- `setGroups`, `addGroups`, `removeGroups` (`user --set…|--add…|--remove…`);
- `setPassword` (`passwd --password`, session form);
- `deleteUser` (`DELETE /user/users/{name}` with both reference choices required; the result
  re-reads the list, because INUBIT always answers 200);
- `deleteGroup` (`delete --deleteUserGroup`);
- `importGroups`, `overwriteRoles`, `importLdapDetails` (file from an upload folder, D-16);
- `syncLdapUsers`;
- `sendMessage` (`msgsend -s <subject> <text>`, text ≤ 1,000 characters, no control characters,
  one argument) and `deleteMessage` (`msgdel <id>`).

Group lists for `--set…` are joined with commas, as documented. Each name is checked against the
user-name pattern, which excludes commas.

## D-26 Versions, recordings and live checks (FR-025, FR-026)

**Decision**: every row of the coverage list starts `planned` for 8.1 and 9.0, or `undocumented`
for a version the vendor does not document. It becomes `reachable` only in the commit that adds
the neutralized recording of that version (fixtures `src/test/resources/fixtures/{v8_1,v9_0}/
{cli,rest}/admin-<entry>.*` with `.origin`, through `tools/neutralize.py` and the 9.0 catalogue).

State-changing commands are recorded only on a non-production server, after the operator has
approved the command (FR-026, `quickstart.md`). The 9.0 recordings wait for the decision on the
changed certificate of the 9.0 test server (feature 021).

## D-27 Delivery (FR-004)

**Decision**: seven increments, one pull request each from this branch to `main`, merged in story
order. After each squash merge, this branch is rebased onto `main` before the next story starts.

- **Increment 1** carries the shared parts that US1 needs: the coverage list, `RestEndpoint`,
  `InterfaceCoverage` with its gate, and the session generalisation.
- **Increment 4 (US4)** brings `ChangeGuard`, `ChangeLocks` and the preview-state helpers.
- **Increment 6 (US6)** brings uploads, secret entries and `ADMINISTRATION` backups. Tags (US4)
  need the backup kind, so `BackupStore.Kind.ADMINISTRATION` moves forward into increment 4.

Each increment updates `docs/tools.md`, `docs/setup.md`, the research note, the coverage list and
`CHANGELOG.md` (FR-027). Feature 021 must be merged before increment 1 is opened.

## Live checks

Each check runs on a non-production server of 8.1 and of 9.0. Read checks need no approval; a
check marked (W) changes the server and runs only after the operator's approval for that command
(FR-026).

| Id | Check | If it fails |
|---|---|---|
| L-1 | Stdin session on 8.1/9.0: prompt format, command echo, `exit` | D-7 fallback: password commands excluded, user pages of 10 |
| L-2 (W) | `maintenance on/off`, `-s`, `-eq`; StartCLI and `POST /ready` while maintenance is on | readiness during maintenance reported as not switchable |
| L-3 (W) | `trace on/off`, `trace -l` levels, `debug on/off` outputs | entry excluded with the reason found |
| L-4 (W) | `tag --tagRemove/--tagDelete` with a group or path scope: is another group's tag kept? | `removeTag` / `deleteTag` excluded (acts on the whole owner) |
| L-5 | Output of `threads`, `privilegedWorkflows`, `dbconnectiontolerance show`, `license -td`, `date` | parser per recorded form |
| L-6 | `user <name>`, `finger`, `-who`, `GET /user/users`: fields, account state | no account state in the result |
| L-7 | `fetchXSLT1.0 -ef`: where the file is written | entry excluded (file on the INUBIT host) |
| L-8 | `ps -u` output | `covered` by `find_processes`, or excluded |
| L-9 | Can the number of processes a run-time export filter matches be read before the export? | preview without a count, with the sentence that the count is unknown |
| L-10 (W) | `process export <name>`, `validateExport`, `import` with a bare file name | absolute path from the export answer only |
| L-11 (W) | `GET`/`PUT /configurations`: root key, partial document, restart notice | full-document PUT only |
| L-12 | Format of user, group and role import files | imports excluded if the login user cannot be found reliably |
| L-13 | How `finger` / `user` mark a directory-managed user | `syncLdapUsers` always refused |
| L-14 (W) | `POST /isconnector` payload from the documentation | entry excluded (FR-002) |
| L-15 | Whether a role change can remove "CLI login access" through these commands | the rule is documented as not reachable |
| L-16 | `--backupFile`, `--backupRepository` usage text | entries excluded (documented by name only) |
| L-17 | `msgread`: does it mark the message as read? | named in the tool description (FR-008) |
| L-18 (W) | `create -cu` without `--password`: what INUBIT sets | named in the tool description (US7 AS1) |
