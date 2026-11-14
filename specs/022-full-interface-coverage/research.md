# Research: Full Coverage of the StartCLI and REST Interfaces

**Feature**: 022-full-interface-coverage | **Date**: 2026-11-12 | **Plan**: [plan.md](plan.md)

Sources: the vendor pages for INUBIT 8.1 and 9.0 (14 StartCLI pages, 85/86 REST pages, retrieved
2026-11-12), `docs/research/inubit-interfaces.md`, `docs/research/spike-development-deployment.md`,
and the code at the base of this branch (0.9.1 + feature 021). Class names refer to
`src/main/java/de/dadecker/inubit/mcp/`.

Every decision has the form Decision / Rationale / Alternatives. The open points that only a test
system can settle are collected as live checks L-1 … L-21 at the end; each names what happens if
the check fails.

## D-1 Tool set (FR-005, FR-006, SC-007)

**Decision**: 9 new tools and 2 extended tools:

| Tool | Story | Kind | Annotation | Replaces / covers |
|---|---|---|---|---|
| `get_server_settings` | US1 | read | readOnly, idempotent | license, threads, privileged workflows, DB connection tolerance, configuration, server time |
| `read_users` | US2 | read | readOnly, idempotent | users, one user, logins, messages |
| `read_model_details` | US3 | read | readOnly, idempotent | module properties, library list (process metadata and the IS Connector check wait for L-19, L-20; D-21) |
| `export_archives` (extended) | US3 | read producing files | localWrite (unchanged) | diagrams of an owner without some groups, metadata registry export |
| `find_processes` (extended) | US3 | read | readOnly (unchanged) | one user-defined Queue Manager column (`ps -u`, D-21) |
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
file-producing reads go to `export_archives`, which already has the folder rules,
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
the variant: with or without `version`, the `rename` kind, `setGroup` and/or
`setAdditionalGroups`, with or without `secretEntry`, the `content` of `export_archives` and
whether `metadataNames` is given.

- **A call** is admitted only when every entry of `entries(input)` is `reachable` for the node's
  version. Otherwise it is refused with `UNSUPPORTED_VERSION`, naming the variant and either
  "not yet verified", "not documented for this version" or the exclusion reason. A sibling
  variant whose entries are reachable stays offered. Examples:
  - `deleteModule` without `version` works when `/modules/{id}/version/{n}` is excluded;
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
  versions. A `planned` row of a story listed in the `delivered-stories` line carries a `waits`
  value naming what it waits for (`L-n`, `9.0-pin` or `9.0-followup`); no other row carries one.
  When all stories are delivered, no row is `planned` and no `waits` value is left (plan review
  round 3).
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
| `ps -u` | covered (8.1; 9.0 once recorded) | `find_processes` `userColumn` (`POST /log/queueLog` field `userDefined<i>`; L-8, owner decisions 2026-11-14, D-21) |
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

**Covered rows before their tool exists** (T007 review, orchestrator decision 2026-11-12): a row
may name only a registered tool (coverage check 3). A "covered" row whose covering tool belongs to
a story not delivered yet therefore stays `planned` with that story, and changes to
`covered:<tool>` (never `reachable`) in the flip task of that story:

| Rows | Becomes | In |
|---|---|---|
| `license -td`, `license -ts` | `covered:get_server_settings` | T025 / T026 (US1) |
| `-nwho` (owner decision 2026-11-13: the `logins` page `total`) | `covered:read_users` | T037 (US2) |
| `-nwho` (owner decision 2026-11-13: the `logins` page `total`) | `covered:read_users` | T037 (US2) |
| `delete --deleteWorkflow`, `delete --deleteModule`, `library --upload`, `library --delete`, `POST /edirules` | `covered:administer_objects` | T091 (US6) |
| `POST /configurations` | `covered:change_server_settings` | T091 (US6) |
| `delete --deleteUser` | `covered:administer_users` | T102 (US7) |

**Entries outside the spec's lists** (classified while building the list, T007):

- `covered:get_health`: `-c`, `maintenance`, `maintenance -s`, `trace` (shows the state), `ps -c`,
  `uname`, `uptime`, `free -i|-k|-m|-p`. `get_health` reads the same facts over REST
  (`maintenanceMode`, `tracingEnabled`, `processCounts`, memory, version).
- `reachable:get_health/default`: `-v` (`get_health` runs `startcli -v`).
- `covered:find_processes`: `-q`, `ps -a|-e|-q|-w|-l|-csv`.
- `covered:deploy_release`: the `deploy --deploy*` options. `covered:query_logs`: `GET /log/{name}`.
  `covered:import_artifacts`: `PUT /model/import`. `covered:export_archives`:
  `GET /task/formProxyServlet` (the spec's "reading a repository resource").
- `reachable:<tool>/default` per option of the commands the server already sends (one tool per
  entry where several send it): `export --exportWorkflowUser|Type|Group` and `--exportFile` →
  `export_archives`; `export --exportModule|ModuleGroup|ModuleUser|RepositoryPath` →
  `export_artifacts`; `export --includeHistory` and `GET /model/export/{name}` →
  `get_inventory_item`; `export --exportTag` → `deploy_release`; the `import` options the server sends
  (`--importFile`, `--importWorkflow`, `--importWorkflowActive`, `--importWorkflowInactive`,
  `--importModule`, `--importUser`, `--importRepositoryPath`, `--returnProtocol`) →
  `import_artifacts` (the other `import` options are `planned` for US6 and US7); `tag --tagMove|WorkflowGroup|WorkflowType|User` → `tag_artifacts`; `kill` →
  `kill_process`; `processErrorStart` → `restart_process`; `GET /model/models` →
  `list_inventory`; `GET /model/modelByName/{name}` → `get_inventory_item`; `GET /system/info` → `get_health`.
- excluded: `-a` (lists the network interfaces of the StartCLI client host, not of the server);
  `--debug` (the client-side trace of an interactive session; the server switch is the
  `debug on|off` row); `backup.sh` and `restore.sh` options (offline scripts, area START).

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

**Decision**: a new `application/ChangeGuard.admit(tool, nodeId, ActionSpec, input)` reuses the
order of `WriteGuard`. Step 4 takes the area from `ActionSpec`. Step 5 passes the call's entries
`ActionSpec.entries(input)` and the capability `ActionSpec.capability()` to
`VersionGate.admitCall`:

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
  processes: the ids from `process listBlocked`); where INUBIT gives no ids, the count. While
  L-21 is open (`rowFormatVerified: false`, D-21), `deleteAllLibraries` uses the count of listed
  lines, and its preview shows the count only, with the sentence that the format of the library
  list is not verified.

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
| metadata import | `export --exportMetadata` (the whole metadata registry; T039) |
| metadata rename | `export --exportMetadata --exportMetadataNames <name>` (the named registry entry; T039) |
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
- **Logins**: one client run, `startcli.sh … --who <url>`, a StartCLI run of its own (T028a).
  `--nwho` is not run: it counts every connected system, not users; owner decision 2026-11-13,
  row `covered:read_users` (the `logins` page `total` is the number of logged-in users). Neither
  is a session command: in the session 8.1 answers `Command not found.`, and `--execCommand -who`
  fails because the client takes `-who` as its own option (T028). `CliSessionCommand.SESSION_OPTIONS` therefore admits neither form; the
  coverage entries keep their keys `-who` / `-nwho`. `--who` shows no login time, only an idle
  time per row (see "Live recording T028a").
- **Messages**: `msglist`, then `msgread <id>`. The text is masked and limited to
  `ItemBounds.MAX_ITEM_CHARS`.

The spec's "cursor" (US2 AS1) is the project's `nextOffset`, the paging form of every other tool.

**Rationale**: REST has no groups or roles; one session per page keeps one JVM start per page.
The logins cost one JVM start per form, because the 8.1 client offers them only as its own options.

## D-21 Model details and file-producing reads (US3, FR-012)

**Decision** (after the 8.1 recording T039; see "Live recording T039" and "Decided after T039"):

- **`read_model_details` actions**:
  - `module`: `GET /model/nodeByModel/{diagram}/{moduleId}` on 8.1 and 9.0.
    - The answer is XML, also for Accept JSON: root `ns4:Node` (`name`, `type`, `id`, optional
      `parentId`), `ns4:Connection targetId= id=` per outgoing connection, then
      `ns2:Properties` with `ns2:Property name= type=` and the value as text.
      `ModuleDetailXmlParser` reads it with the hardening of `XmlSupport`.
    - A property is masked by D-19 when its name is a secret name or its `type` is `Password`
      (also when its value is empty); key material is masked by value.
    - An unknown id is a `500` `text/html` status page whose `@Start@…@End@` message says that
      the module could not be found: `NOT_FOUND`. Any other answer is `UNEXPECTED_RESPONSE` with
      a masked, bounded excerpt.
  - `GET /model/nodeByName/{diagram}/{module}` is not offered: `404` on 8.1.17 (row
    `excluded:` on 8.1), `undocumented` on 9.0. There is no `moduleName` input.
  - `libraries`: `library --list --type driver|plugin`. Only the empty answer is recorded; each
    non-empty line is read as one file name, and the result says that the row format is not
    verified (`rowFormatVerified: false`) until L-21 records a listed library (T079, after the
    test upload; the parser follows in T091).
  - Not built in US3 (owner decision 2026-11-14); the rows stay `planned`:
    - `metadata` (`GET /process/metadata/{name}`): no positive answer recorded, L-19;
    - `checkIsConnector` (`GET /isconnector?…type=validateWorkflow`): the profile's Basic auth
      gets `401`, L-20.

    If L-19 or L-20 records a working form, T046b / T046c build the action; otherwise T104
    applies the fallback.
- **`export_archives` new optional `content`**:
  - `workflows` is the default and today's behaviour.
  - `ownerWithoutGroups` with `excludeGroups[1..20]` gives one archive of the owner's technical
    workflows only (owner decision 2026-11-14): `export --exportWorkflowUser '<owner>'
    --exportWorkflowType 'technical' --exportWorkflowGroup '' --exportWorkflowGroupExclude
    '<g1,…>' --exportFile '<zip>'`.
    Without the type and the empty group INUBIT answers `2-NOK: Missing export option.` It needs
    `acknowledgeClearTextSecrets` like today (the module files hold clear-text secrets).
  - `metadata` exports the metadata registry: `export --exportMetadata`, and with the optional
    `metadataNames[1..50]` also `--exportMetadataNames <n1,…>` (comma separated) for the named
    registry entries only. The names are metadata names, not workflow names; INUBIT accepts names
    that do not exist.
  - The schema uses `if`/`then` per `content`; `diagramGroup` is required only for `workflows`.
  - The check-in comment trace of StartCLI exports is named in the description (FR-008).
- **`xslt10Report` is not offered** (L-7 fallback): `fetchXSLT1.0` does not exist on 8.1.17
  (`Command not found.`). Its rows are `excluded:command not found on INUBIT 8.1.17` on 8.1; 9.0
  stays open until a 9.0 recording (T039a, US3 9.0 follow-up); T046d offers it on 9.0 only if
  the command exists there and the owner decides so.
- **`find_processes` `userColumn`** (`ps -u`, L-8, owner decisions 2026-11-14):
  - optional integer `1..5`; the result shows that one user-defined Queue Manager column;
  - the values come from the REST `queueLog` rows that `find_processes` already reads: the field
    `userDefined<i>` of each row (as `read_queue_entry` reads `userDefined1..5` through
    `QueueEntryRowParser`). No StartCLI run and no join: StartCLI `ps -u` shows the same data cut
    to 15 characters, so its row is `covered:find_processes` (REST first, D-6, Principle V);
  - the value is not cut to StartCLI's 15 characters; it is bounded and masked like the other
    texts of the row (`QueueLogRowParser`: 200 characters with the truncation marker, then the
    row size bound; FR-010);
  - the T039 `ps -u` fixtures stay as the recording that justifies the coverage state;
  - the gate: `VersionGate.admitOption` admits `userColumn` when the row `CLI ps -u` is
    `Covered("find_processes")` or `Reachable` for the node's line (`InterfaceCoverage.servedBy`);
    a missing line (6.1) or `planned` gives `UNSUPPORTED_VERSION` "not yet verified". The
    existing `admitCall` treats `Covered` as "not sent by this server" and is not used here;
  - the row `ps -u` is `covered:find_processes` on 8.1; 9.0 stays `planned` ("not yet
    verified") until the US3 9.0 follow-up flips it (T046a, on the recorded 9.0 row
    `fixtures/queue-entry/v9_0/error_entry.rows.json`); the flip admits 9.0 without a code
    change. 6.1 gets nothing new.

**Rationale**: the recording settles the answer forms; only recorded forms are parsed (FR-025).
The two REST reads without a working form stay planned rather than guessed.

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
| L-7 | `fetchXSLT1.0 -ef`: where the file is written (8.1: the command does not exist, T039; 9.0 open, T039a) | entry excluded (file on the INUBIT host, or command not found) |
| L-8 | `ps -u` output (8.1: answered by T039, covered through the REST `queueLog` field `userDefined<i>`; 9.0 open, T039a) | `covered` by `find_processes`, or excluded |
| L-9 | Can the number of processes a run-time export filter matches be read before the export? | preview without a count, with the sentence that the count is unknown |
| L-10 (W) | `process export <name>`, `validateExport`, `import` with a bare file name | absolute path from the export answer only |
| L-11 (W) | `GET`/`PUT /configurations`: root key, partial document, restart notice | full-document PUT only |
| L-12 | Format of user, group and role import files | imports excluded if the login user cannot be found reliably |
| L-13 | How `finger` / `user` mark a directory-managed user | `syncLdapUsers` always refused |
| L-14 (W) | `POST /isconnector` payload from the documentation, with the authentication L-20 finds | entry excluded (FR-002) |
| L-15 | Whether a role change can remove "CLI login access" through these commands | the rule is documented as not reachable |
| L-16 | `--backupFile`, `--backupRepository` usage text | entries excluded (documented by name only) |
| L-17 | `msgread`: does it mark the message as read? | named in the tool description (FR-008) |
| L-18 (W) | `create -cu` without `--password`: what INUBIT sets | named in the tool description (US7 AS1) |
| L-19 | `GET /process/metadata/{name}` for a workflow that has metadata (positive answer: media type, shape, secret values) | `metadata` not offered; the row `excluded:cannot be built from the documentation: answer form not recordable` (FR-002); applied in Phase 10 (T104) at the latest; a working form is built in T046b |
| L-20 | Authentication of `GET /isconnector`: which user store or credential form INUBIT accepts (the profile's Basic auth gets `401` on 8.1.17) | `checkIsConnector` not offered; the row `excluded:cannot be built from the documentation: authentication not documented` (FR-002); applied in Phase 10 (T104) at the latest; a working form is built in T046c |
| L-21 | `library --list --type driver\|plugin` on a server with an uploaded driver and plug-in: the row format (recorded in T079 after `PUT /libraries`) | the result keeps `rowFormatVerified: false` and reads one file name per line; `deleteAllLibraries` previews the count only (D-14) |

## Live recording T014 (8.1, 2026-11-13)

Read-only recordings on an 8.1.17 development server with the profile's account, which is not the
INUBIT root user: one stdin session with the seven US1 reads and `exit`, the same seven reads once
each in script mode, `GET /license` and `GET /configurations`. Fixtures:
`fixtures/v8_1/cli/admin-settings-*` and `fixtures/v8_1/rest/admin-license.*`,
`admin-configurations.*` (neutralization in `fixtures/v8_1/cli/README.md`).

**L-1 (stdin session on 8.1): works.**

- Without `--execCommand`, the 8.1 client reads the password line from stdin and then one command
  per line; the whole input can be written up front (as `CliRunner.stdinBytes` does).
- Before the first command, stdout holds the client's lines: `JAVA_HOME is set`,
  `Connecting as '<user>' to <cliUrl> with hostname verification OFF` (session only, script mode
  does not print it), `Password: ` followed by a line break (LF, no CR), the hostname-verification warning, an
  empty line, `Connection to inubit Process Engine 8.1.17 established.`, `<TAB> shows command
  list` and `[COMMAND] with option -h or --help shows help for this command` (CRLF).
- Each command is echoed as `CLI|<user>@<host>> <command>` (LF), where `<host>` is the server's
  host name, not the node name of the profile. The answer follows up to the next echo; most
  answer lines end in CRLF. The echo is the only block separator.
- The session was ended with `exit`; its echo `CLI|<user>@<host>> exit` is the last line. A
  session without `exit` was not tried; the runner always ends the session with
  `CliSessionCommand.EXIT`, so the question does not affect the design.
- A refused command does not end the session: the session answers `EXECUTION ERROR` and the
  reason (no stack trace, unlike script mode) and goes on with the next command; the exit code
  of the whole session is then 1. stderr holds only the JVM's `Picked up JAVA_TOOL_OPTIONS` line.
- The output was English with `-Duser.language=en -Duser.country=US`; another locale was not
  recorded.

**L-5 (output forms).**

| Command | Answer (script mode; the session block is the same unless noted) |
|---|---|
| `threads showNumberOfWorkflowThreads` | refused for a non-root account (below) |
| `privilegedWorkflows showThreadsPercentage` | refused for a non-root account |
| `privilegedWorkflows showWorkflowList` | refused for a non-root account |
| `dbconnectiontolerance show` | `Error level: \t\t<n>%`, an empty line, `Fault threshold: \t<n>`, `Drop rate: \t\t<n>%`, `Drop interval (s): \t<n> seconds`; label and value are separated by tabs (one or two), not by a single space as in the reconstructed case. Script mode ends with an empty line, the session block with a line holding only `\r` |
| `date` | `Fri Nov 13 05:52:06 CET 2026`: Java `Date.toString` in the server's time zone |
| `license -td` | a bare integer: the days until the license expires |
| `license -ts` | a bare integer: the seconds until the license expires (it fell by about the elapsed time between the session and the script run) |

- Refusal in script mode: an `ERROR <time> [main] CLI ERROR` record, the line
  `com.inubit.ibis.utils.InubitException: @Start@IError@@@Internal INUBIT error!@End@@Start@Only
  root user can use this command.@@@Only root user can use this command.@End@`, the stack trace,
  then `EXECUTION ERROR`, `Internal INUBIT error!`, `Only root user can use this command.`; exit 1.
- The days of `license -td` (rounded up from `license -ts`) count to a date exactly one month
  after the `ExpirationDate` of `GET /license`. The cause is not known (a month offset in the
  client's calculation is possible); the fixtures keep this relation.

**L-11, read part (`GET /configurations`).**

- The root key is `ServerConfiguration`. The vendor documentation (and the reconstructed case)
  spell it `SeverConfiguration`.
- No leaf has a sibling `type` or `encrypted` field, and the answer holds no secret leaf at all:
  no password field of the proxy, SMTP, LDAP or portal sections appears, and
  `ProcessUserImTenantAdminCredentials` is an empty object. The secret rules still apply; the
  fixture plants three test secrets (two by name, one by path).
- The body is one compact JSON line without a trailing line break. Values mix types: some numbers
  are JSON numbers, others strings (`"25"`, `"100MB"`); lists occur (`ClusterKnownNodes`, SSH
  algorithm lists).
- Some values are Java `toString` output rather than data (`org.dom4j.tree.DefaultElement@…
  [Element: <Tasklists …/>]`, dates as `Sat Oct 31 00:00:00 CET 2026`).
- `NodeMaintenanceModeMap` uses the cluster node names as keys and `ClusterKnownNodes` lists host
  names, so both are server identifiers.
- The answer's content type is `application/json;charset=UTF-8`.

**`GET /license`.** `200` with `text/xml;charset=UTF-8` (not `application/xml`). Root `<license>`
with `LicenseInformation` (`Version` = edition, `CustomerNumber`, `CompanyName`, `ContactPerson`,
`Comment`, `keytype`, `RequestID`, `KeyID`, `LicenseGenerationDate` as `dd.MM.yyyy`,
`SystemID`), `inubit` (`AllowedIPs`, `AllowedMACs/MAC`, `LicenseType` with `ExpirationDate` as
`yyyy-MM-dd`, `Version`, `ConnectorsCount`, `MaxUserCount`, `ConcurrentPortalUser`,
`ThreadCount`, `RuntimeLicense`, `ServiceContract`, `ClientServerMode`; `Features` with
`UserManagement` and `Plugins type="…"` lists of `Plugin`) and an XML-DSig `Signature` whose
base64 values are broken by `&#xD;` and a line break. None of the element names of the
reconstructed case (`licensee`, `expiryDate`, `licensedThreads`) exist.

**Owner decision (2026-11-13).** The profile account is kept; only the refusal is recorded. The
three root-only reads (`threads showNumberOfWorkflowThreads`, `privilegedWorkflows
showThreadsPercentage`, `privilegedWorkflows showWorkflowList`) cannot be recorded with the
profile account. T025 sets their coverage rows to `excluded:needs the INUBIT root user`, and
`get_server_settings` reports those parts unavailable with that reason.

**Applied in T025.** `CliSessionParser.STANDARD` drops the echoed command after each prompt and
accepts the echoed `exit`; `CliSettingsRunner` reads a session that exits with 1 block by block
when a block starts with `EXECUTION ERROR` (a refused command costs only its part); the
`dbconnectiontolerance show` parser accepts the tabs; `LicenseXmlParser` reads
`inubit/LicenseType/ExpirationDate`, `inubit/LicenseType/ThreadCount` and
`LicenseInformation/CompanyName`; `ConfigurationJsonParser` takes `ServerConfiguration` first and
still accepts `SeverConfiguration`. The parts `threads` and `privilegedWorkflows` and their
parsers stay: the parts and their entries stay declared in `ServerSettingsService.SPEC`, which is
where the gate's reason "excluded for INUBIT 8.1: needs the INUBIT root user" comes from, and the
runner reads every declared StartCLI part a coverage list admits. Their `*-reconstructed` fixtures
stay SYNTHETIC; the other `*-reconstructed` cases of US1 are replaced by the recordings. The
refusal for a missing "CLI login access" right is still not recorded (the profile's account has
the right), so `CliOutputClassifier.MISSING_CLI_RIGHT` stays SYNTHETIC.

## Live recording T028 (8.1, 2026-11-13)

Read-only recordings on the same 8.1.17 development server and account as T014: `GET
/user/users?type=processEngineUser`, one stdin session with `user '<login>'` and `finger '<login>'`
for three logins of that list (the profile's account, the first other login with a hyphen, the
first other login with an underscore), `-who`, `-nwho`, `msglist` and `exit`, and the same commands
once each in script mode. Script-mode `msglist` ran first: the session's input is written up
front, so the message id had to be known before the session. Fixtures:
`fixtures/v8_1/cli/admin-users-*` and `fixtures/v8_1/rest/admin-users.*` (neutralization in
`fixtures/v8_1/cli/README.md`). 9.0 was not recorded: the approval covered the 8.1 server
only.

**L-6 (fields, account state): no account state anywhere.**

- `GET /user/users`: `200`, `application/xml;charset=UTF-8`, one line without a trailing line
  break. Root `UserList` in the default namespace `inubit.com/ibis/external/user`, with an unused
  `xmlns:ns2="inubit.com/ibis/external/base"`; the feature-004 recording of the same endpoint had
  `ns2:UserList` with `ns2` bound to the user namespace. Same namespace URI, different prefixes:
  a reader that ignores prefixes reads both. `UserListXmlParser` (T030) matches local names only,
  as `XmlSupport` does everywhere: the endpoint is fixed and only `id` and `type` are read. Each `User` has `id`, `email`,
  `type="ProcessEngineUser"` and, for 26 of 58, `firstName` and `lastName`; no state, group or
  role attribute. The list is not sorted by `id`.
- `user '<login>'`: three rows, `User name`, `User group` (one group) and `Super group` (the
  parent group). Labels are padded to 13 characters, values to the longest value plus one space.
  No row for additional groups appeared; whether one appears for a user with additional groups
  was not recorded (none of the three has one, or the command does not show them). No roles.
- `finger '<login>'`: `Login: ` with the login padded to 15 characters, then `Name: <last name>,
  <first name>` only if the user has both names (the user without names in the REST list has no
  `Name:`); `Email: ` (the same value as the REST `email`); `Role: <role>`, one line per user in
  all three recordings. Whether several roles give several lines was not recorded. Neither
  `finger` nor `user` shows a locked, disabled or directory-managed state (L-13 not answered).
- Single-quoted logins with a hyphen or an underscore work in both modes.
- Line ends: in the session every answer line ends in CRLF and the `user` block ends with an
  empty `\r` line; in script mode the last line of `finger` ends in LF only and the `user` block
  ends with an empty LF line.

**`-who`, `-nwho`: not session commands on 8.1.**

- In the session both answer `Command not found.` (CRLF); the session goes on, and its exit code
  is 1 although no other command failed: an unknown command counts as a failure like a refused
  one (T014).
- In script mode `--execCommand -who` (and `-nwho`) fails before connecting: the client's option
  parser takes `-who` as its own option, so `--execCommand` has no argument: `EXECUTION ERROR`,
  `Missing argument for option: execCommand`, exit 1, no `Password:` line.
- The client's help, `startcli.sh -h` (no server contact), lists `--who` ("List logged in
  users") and `--nwho` ("Number logged in users") as options of the client itself, beside `--maintenance` and `-x`. The logins are
  therefore read with `startcli.sh … --who <url>` as a run of its own, neither in the session nor
  with `--execCommand`. That form was not part of the approval and was not run: L-6 stays open
  for the logins (the output of `--who` and `--nwho` is unknown), and D-20's "logins: `-who`" in
  the session does not work as planned on 8.1.

**L-17 (`msgread`): still open.** `msglist` showed no message for the profile's account in both
modes: only the header `ID   Subject … Sender … Date` (columns 5, 40 and 15 characters wide,
CRLF) and an empty line. `msgread` was skipped, so whether it marks a message as read, and the
row layout of `msglist`, are not recorded.

## Live recording T028a (8.1, 2026-11-13)

Read-only recordings on the same 8.1.17 development server and account as T028, approved by the
owner on 2026-11-13: one client run each of `startcli.sh -u <user> --trustStoreFilePath <store>
--disableHostNameVerification --who <cliUrl>` and the same with `--nwho`, the password on stdin.
Fixtures: `fixtures/v8_1/cli/admin-users-who.*` and `admin-users-nwho.*` (neutralization in
`fixtures/v8_1/cli/README.md`). 9.0 was not recorded.

**L-6 (logins): answered for 8.1.**

- Both forms ask for the password like script mode and print the script-mode preamble:
  `JAVA_HOME is set`, `Password: ` followed by a line break, the hostname-verification warning
  (LF). No `Connecting as` line and no `Connection to inubit Process Engine … established.` line.
  stderr holds only the JVM's `Picked up JAVA_TOOL_OPTIONS` line. Exit code 0 for both.
- `--who` prints a table, not a list of users: the header `SYSTEM-ID`, `SYSTEM TYPE`, `STATE`,
  `VERSION`, `IDLE`, the first four columns 64, 30, 10 and 15 characters wide, the fifth, `IDLE`, unpadded, header
  and rows ending in CRLF, then an empty line (LF). One row per connected system:
  - the three database connections, `SYSTEM-ID` the JDBC URL, `SYSTEM TYPE` `Log DB
    (PostgreSQL)`, `Repository DB (PostgreSQL)` or `Cache DB (PostgreSQL)`, `VERSION` the JDBC
    driver version;
  - `Portal (BPC)` with its URL, and `Process User Server (inubit)` with an empty `SYSTEM-ID`;
  - one `Toolset` row for the client connection: `SYSTEM-ID` `<client host> (<login>)`, `STATE`
    `OK`, `VERSION` the client's version `8.1.17`. Only this row had a `STATE`.
- No login time and no session data (no session id, no start time). `IDLE` is a duration
  `<hours>:<mm>:<ss>s`; the five system rows showed the same value of several days, the client
  row 2 seconds.
- The user's own login is listed: the run's own connection is the `Toolset` row, with the host the
  client ran on and the profile account in parentheses. No other `Toolset` row appeared, so how
  other Workbench or StartCLI connections look was not recorded. The profile account and the
  operating-system user of the client host have the same name in this recording, so whether the
  parentheses hold the INUBIT login or the client's operating-system user is not settled.
- `--nwho` prints a bare number and a line break (LF): `6`, the number of rows `--who` listed,
  systems included. It is therefore not the number of logged-in users.

**Consequence.** D-20 "Logins" and T032 read the logins with a client run of their own, not in the
session; `CliSessionCommand.SESSION_OPTIONS` stays without `-who` / `-nwho`. The coverage entries
keep their keys `-who` / `-nwho`. How `--who` rows become `LoginEntry` values (only `Toolset`
rows, `since` empty) and what `--nwho` is reported as is left to T031 and T034. Decided in T034
and by the owner (2026-11-13): only `Toolset` rows become `LoginEntry` values; `--nwho` is not run,
and its row is `covered:read_users`.

**Open point (T031).** The JDBC URLs in this recording are 60–62 characters, close to the 64-wide
`SYSTEM-ID` column. How `--who` prints a longer `SYSTEM-ID` (cut, or the row shifted) is not
recorded; `WhoParser` treats such a table as unknown output, so the `logins` view then fails as a
whole. A recording from a server with a longer database URL decides whether the parser can read
the overflow form.

## Live recording T024/T028b (9.0, 2026-11-13)

Read-only recordings on the 9.0.10 test node (non-production) with the profile's account, which is
not the INUBIT root user, approved by the owner on 2026-11-13 after the renewed certificate was
confirmed (the leaf fingerprint was checked against the profile's pin and the trust store before
the first request). StartCLI: the profile's 9.0.10 client on Java 21, against the base URL (no
servlet path, D-3). Every command ran once:

- T024 (the commands of T014): one stdin session with the seven US1 reads and `exit`, the same
  seven reads once each in script mode, `GET /license`, `GET /configurations`;
- T028b (the commands of T028 and T028a): `GET /user/users?type=processEngineUser`, `msglist` in
  script mode, one stdin session with `user '<login>'` and `finger '<login>'` for three logins and
  `msglist`, `exit`, the six `user`/`finger` reads in script mode, and one client run each of
  `startcli.sh … --who <url>` and `--nwho <url>`. No login on the 9.0 node has a hyphen; the
  three logins are the profile's account and the two other logins with an underscore (one with a
  capital initial). `-who`/`-nwho` were not sent in the session or with `--execCommand`: the 9.0
  client's help (`startcli.sh -h`, no server contact) lists `--who` and `--nwho` as client options,
  as on 8.1.

Fixtures: `fixtures/v9_0/cli/admin-settings-*`, `admin-users-*` and `fixtures/v9_0/rest/
admin-license.*`, `admin-configurations.*`, `admin-users.*`, each with `.origin`
(`recorded 2026-11-13 9.0.10 neutralized`) and registered in `V90Cases` and `NOT_RECORDABLE.txt`
(neutralization in `fixtures/v9_0/README.md`). The root-only reads were refused; as decided for
8.1, only the refusal is recorded.

**Differences from 8.1.**

| Aspect | 8.1 (T014, T028, T028a) | 9.0 |
|---|---|---|
| StartCLI address | `<baseUrl>/ibis/servlet/IBISSoapServlet` | `<baseUrl>`; the client prints it as `<baseUrl>?isCli` |
| Session preamble | `JAVA_HOME is set`, `Connecting as '<user>' to <cliUrl> with hostname verification OFF`, `Password: `, the hostname warning, an empty line, `Connection to inubit Process Engine 8.1.17 established.`, two help lines (CRLF) | `JAVA_HOME is set`, the warning, a Netty line `ERROR <time> [main      ] DnsServerAddressStreamProviders Unable to load io.netty.resolver.dns.macos.MacOSDnsServerAddressStreamProvider …` (the client ran on macOS; whether it appears on Linux is not recorded), the warning, `Password: `, the warning, `Connecting as '<user>' to <baseUrl>?isCli` (after the password, without "with hostname verification OFF"), an empty line, `Connection to inubit Process Engine 9.0.10 established.`, two help lines; all LF |
| Script-mode, `--who`, `--nwho` preamble | `JAVA_HOME is set`, `Password: `, the warning | `JAVA_HOME is set`, the warning, the Netty line, the warning, `Password: `, the warning; no `Connecting as` line |
| stderr | only `Picked up JAVA_TOOL_OPTIONS` | the same |
| Echo | `CLI\|<user>@<host>> <command>` (LF), `<host>` the server's host name | the same |
| Settings answers in the session | mostly CRLF | LF |
| End of a script-mode answer | as the session block | one more empty line (LF) after every answer |
| Refusal in the session | `EXECUTION ERROR`, the reason; the session goes on; exit 1 | the same lines (LF); the session goes on; **exit 0**: a refused command no longer sets the exit code |
| Refusal in script mode | `ERROR … CLI ERROR` record, `InubitException: @Start@IError@@@…` line, stack trace, `EXECUTION ERROR`, `Internal INUBIT error!`, `Only root user can use this command.`; exit 1 | `EXECUTION ERROR`, then only the `InubitException: @Start@IError@@@…` line; no record, no stack trace; exit 1 |
| `dbconnectiontolerance show` | labels and tabs as in T014; the session block ends with a `\r` line | the same labels and tabs; the session block ends with an empty line |
| `date`, `license -td`, `license -ts` | `Date.toString`, bare integers | the same; the days still count to one month after `ExpirationDate` |
| `GET /license` | `text/xml;charset=UTF-8`, indented by two spaces | `application/xml;charset=UTF-8`, indented by three; `LicenseInformation` has two new elements, `LicenseName` and `Environment`; otherwise the same elements and signature layout |
| `GET /configurations` | `application/json;charset=UTF-8`, root `ServerConfiguration`, one compact line | content type **`application/xml;charset=UTF-8`** although the request accepts JSON and the body is the same compact JSON line; root `ServerConfiguration`; 255 leaves instead of 276: no `GUIThreadPriority`, `GUIThreadsCount`, `PortalDeployment/AllowGenericProxyUrls`, `SystemProperties/com.sun.xml.ws.transport.http.HttpAdapter.dump`, no `TracingCustom` loggers of the FTP and JMS plugins and ActiveMQ; new `MimetypesFileType/application/json`. Still no `type`/`encrypted` field and no secret leaf (`ProcessUserImTenantAdminCredentials` `{}`) |
| `GET /user/users` | one line, root `UserList` in the default namespace, unused `ns2`, no trailing line break | XML declaration with `standalone="yes"`, root `ns2:UserList` (`ns2` = the user namespace, `ns1` = the base namespace; the feature-004 form), one `ns2:User` per line, trailing line break; the same attributes; two users share one e-mail |
| `user '<login>'` | three rows (CRLF); the session block ends with an empty `\r` line | a fourth row `Additional user group` for a user with an additional group; labels padded to the longest label plus two (13, or 23 with the fourth row), values to the longest value plus one; rows CRLF; the session block ends with an empty LF line |
| `finger '<login>'` | `Login:` padded to 15, `Name:` only with both names, `Email:`, one `Role:` line; every line CRLF in the session, the last LF in script mode | the same lines; the `Role:` line ends in LF in both modes |
| `msglist` without messages | the header (CRLF) and an empty line (`\r` in the session) | the header and an empty line, LF only |
| `--who` | `SYSTEM-ID` column 64 wide; a `Toolset` row for the client's own connection | `SYSTEM-ID` column **65** wide; **no `Toolset` row**: the client's own run is not listed; no row has a `STATE`; the other rows and columns as on 8.1 |
| `--nwho` | `6` | `5`: again the number of rows `--who` lists |
| Exit codes | session 1 (refusals, unknown commands), script reads 0, refusals 1, `--who`/`--nwho` 0 | sessions 0, script reads 0, refusals 1, `--who`/`--nwho` 0 |

**L-1 (stdin session on 9.0): works** as on 8.1: the whole input written up front, one echo per
command, the session ended with `exit`.

**L-5 on 9.0:** the forms of `dbconnectiontolerance show`, `date`, `license -td` and `license -ts`
are those of 8.1; the three root-only reads are refused for a non-root account on 9.0 too.

**L-6 on 9.0.** Fields as on 8.1 (REST `id`, `email`, `type`, names; `user` groups; `finger`
role), plus the `Additional user group` row; no account state anywhere (L-13 still not answered).
Logins: `--who` lists the five systems only. The StartCLI run's own connection does not appear,
so on 9.0 `--who` showed no login at all; whether a Workbench connection appears as a `Toolset`
row on 9.0 is not recorded (none was connected).

**`--who` column width (open point of T028a).** The longest `SYSTEM-ID` was 62 characters on 8.1
and 63 on 9.0, and the column 64 and 65 wide: the column is the longest `SYSTEM-ID` plus two,
not a fixed 64, so a longer database URL widens the column instead of being cut (two
recordings; `SYSTEM TYPE` is 30 in both, its longest value plus two; `STATE` 10 and `VERSION` 15
in both).

**L-17 (`msgread`): still open on 9.0.** `msglist` showed no message for the profile's account in
both modes, so `msgread` was not run.

**For T026 and T037a (not applied here).** The session preamble (Netty line, `Connecting as` after
the password, `?isCli`), the session exit code 0 with refusals, the shorter script-mode refusal,
the extra empty line after script-mode answers, the `application/xml` content type of the JSON
configuration, the `Additional user group` row with its wider label padding, and `--who` without
a `Toolset` row and with a 65-wide first column all meet parsers that were written against the 8.1
recordings (`WhoParser` assumes fixed columns of 64, 30, 10 and 15). The contract tests also meet
the 9.0 license shape (`application/xml`, new `LicenseName` and `Environment`, 3-space indent),
the `/user/users` form (XML declaration, `ns2:UserList`, one user per line; the parsers match
local names) and the 21 configuration leaves 9.0 no longer has.

**Decided in T037a (owner decision 2026-11-14).** On 9.0 the `logins` view is refused, not
answered with an empty page: `--who` lists no `Toolset` row, so the row `-who` is
`excluded:<reason>` for 9.0 (a live check that failed, data-model "Transitions") and the gate
refuses the view with that reason before `--who` runs; `WhoParser` keeps its 8.1 columns. `-nwho`
is `covered:read_users` on 9.0 as on 8.1. The `Additional user group` row is read into
`additionalGroups` (one group per row). `msglist` and `msgread` stay `planned`, waiting for L-17
only.

## Live recording T039 (8.1, 2026-11-14)

Read-only recordings on an 8.1.17 non-production server with the profile's account, which is not
the INUBIT root user, approved by the owner on 2026-11-14. Every command ran once:

- REST: `GET /model/nodeByModel/{d}/{id}` with Accept JSON and with Accept XML for a Database
  Connector module, once for an IS Connector module and once with an unknown id `999999`;
  `GET /model/nodeByName/{d}/{m}` (with and without `?user=`); `GET /process/metadata/{wf}` with
  Accept JSON and XML; `GET /isconnector?workflowName&moduleName&type=validateWorkflow` for an IS
  Connector module and for another module;
- StartCLI in script mode: `library --list --type driver|plugin`; `ps -l 5 -u 1`,
  `ps -a -l 3 -u 1`, `ps -l 5 -u`, `ps -a -l 3 -u 1,2`; `fetchXSLT1.0 -ef xslt10-report.txt`;
  `export --exportMetadata`, `export --exportMetadata --exportMetadataNames Example1,Example2`,
  `export --exportWorkflowGroupExclude` twice (without and with type and group, below), each with
  `--exportFile` into the private recording folder;
- one stdin session with `library --list --type driver|plugin`, `ps -a -l 3 -u 1`,
  `fetchXSLT1.0 -ef xslt10-report.txt` and `exit`, and one with the help of `export`, `library`,
  `ps` and `fetchXSLT1.0`.

Fixtures: `fixtures/v8_1/cli/admin-model-*` and `fixtures/v8_1/rest/admin-model-*`
(neutralization and planted values in `fixtures/v8_1/cli/README.md`). The workflow-group archive
holds clear-text module secrets and is not committed. 9.0 was not recorded.

**Module detail (`GET /model/nodeByModel`): XML, not JSON.**

- `200` with `application/xml;charset=UTF-8` for Accept JSON as well as Accept XML; the two bodies
  are identical. One line without a trailing line break, XML declaration with `standalone="yes"`.
- Root `ns4:Node` with `name`, `type` (`twDatabaseConnector`, `twinubitISConfiguration`), `id`
  and, for the IS Connector module only, `parentId`; namespaces `ns2` =
  `inubit.com/ibis/external/base`, `ns4` = `inubit.com/ibis/external/model`, `ns3` =
  `inubit.com/ibis/external/organigram` (unused).
- Children: `ns4:Connection targetId= id=` (one per outgoing connection), then `ns2:Properties`
  with `ns2:Property name= type=` and the value as text. Types seen: `Boolean`, `Integer`,
  `String`, `Password`. In this recording the order is a fixed head (`SCHEDULER`, `InputConnector`,
  `OutputConnector`, `IsActive`, `ListenerConnector`, `Retry…`, `TimeoutInterval`), then the
  others in ASCII order.
- The Database Connector module has a `Password`-typed property `Password` and the plain `String`
  properties `dbURL`, `user`, `driverClass` and `staticQuery`. All of `Password`, `dbURL`, `user`
  and `driverClass` were empty in the recording (the module uses the IS log database), so whether
  a set password is sent in clear text, encrypted or left out is not recorded. The fixture plants
  a clear-text value (the worst case) and a secret found only by its property name.
- An unknown id gives `500`, `text/html;charset=UTF-8`, Tomcat's "Status page" with the
  message `@Start@The module with the id '<id>' in the model '<wf>'could not be
  found!@@@<same>@End@` (HTML-escaped quotes, no space before `could`) and a timestamp "Please look at inubit system log
  at time stamp : <yyyy-MM-dd HH:mm:ss>".

**`GET /model/nodeByName`: absent on 8.1.17.** `404`, `text/html`, the status page "Not Found" /
"The server has not found anything matching the request URI", with and without `?user=`. The
request named the existing diagram and the name of the module that `nodeByModel` returned, so
the 404 is not a "module not found" (that answer is a `500` with an `@Start@…@End@` message, see
above). The vendor documents the endpoint for 8.1, but this 8.1.17 server does not offer it.

**`GET /process/metadata/{wf}`: no positive answer on this server.** `500`, `text/html`, the
status page with `@Start@Workflow details not found for <wf>@@@<same>@End@`, for Accept JSON and
XML and for every workflow tried (a technical workflow with and without `?user=`, and one BPD). Probably because
the server's metadata registry is empty (the metadata export below) no workflow has metadata; the
parameter form was not varied further. The answer for a workflow with metadata could not be
recorded.

**`GET /isconnector`: the profile's Basic auth is refused.** `401`, `text/plain;charset=UTF-8`,
`Authentication failed. Please check the username and password.` (no line break, no
`WWW-Authenticate` header) for both modules, with the same Basic auth that every other REST call
of the recording accepted. The endpoint uses another authentication (another user store or
another credential form); which one is not known.

**`library --list --type driver|plugin`: empty on this server.** Script mode prints the preamble
and one empty line (LF), the session block is one `\r` line; exit 0. No library was uploaded, so
the row format of a listed library is not recorded.

**L-8 (`ps -u`): answered for 8.1.**

- `ps … -u <index>` adds one column `USER_DEFINED_<index>` after `TAG`. Every column except the
  last is its longest value (or its header) plus two characters wide; the last header has one
  trailing space, its values none; rows CRLF, `Total: <n>` CRLF, then an empty line. With no rows
  the header columns are separated by one space and `Total: 0` follows.
- Both recorded values were cut to 13 characters plus `..` (15 in all); whether shorter values
  are shown whole was not recorded.
- Only a single index works: `-u` without an index (the help: "Leave blank to show all") and a
  list `-u 1,2` are refused with `ERROR … CLI`, `InubitException: …@Start@<message key
  cli.process.option.filter.argument.expression.invalidFilterExpression>@@@Invalid filter
  expression!@End@`, stack trace, `EXECUTION ERROR`, `Internal INUBIT error!`, `Invalid filter expression!`; exit 1.
  The help gives the indices `1..5`.
- `ps -h` also names `-csv` ("Use CSV output format instead of a table").

**L-7 (`fetchXSLT1.0 -ef`): the command does not exist on 8.1.17.** Script mode answers
`ERROR … CLI`, `InubitException: @Start@IError@@@Internal INUBIT error!@End@Command not found.`,
the stack trace, `EXECUTION ERROR`, `Internal INUBIT error!`, `Command not found.` (exit 1); in the
session `Command not found.` and the session goes on (exit 1). `fetchXSLT1.0 -h` gives `Command
not found.` too. The command did not run, so no file was written; where its file would be
written cannot be answered on 8.1.17.

**`export --exportMetadata` exports the metadata registry, not workflow metadata.**

- The help: `--exportMetadata` "Export metadata registry", `--exportMetadataNames <arg>` "Metadata
  names to export, comma separated".
- `export --exportMetadata --exportFile <zip>`: `1-OK: Metadata exported successfully.`, exit 0;
  with `--exportMetadataNames Example1,Example2` added: `1-OK: Metadata Names exported
  successfully.` (names that exist nowhere on the server are accepted). Whether
  `--exportMetadataNames` works without `--exportMetadata` was not tried.
- The archive holds `MetadataRegistry.xml` and `MetadataAllocations.xml` (an XML declaration, an
  empty line and an empty root with `version="4.1"`, as the registry is empty) and a ZIP comment
  in properties form: `#<date>`, `Type=MetadataExport`, `User=<login>`, `Version=6.0.0`,
  `Date=<yyyy-MM-ddTHH\:mm\:ss>`. The names are metadata names (registry entries), not workflow
  names.

**`export --exportWorkflowGroupExclude`.**

- With only `--exportWorkflowUser`, `--exportWorkflowGroupExclude` and `--exportFile`:
  `2-NOK: Missing export option.` in the usual refusal form; exit 1.
- With `--exportWorkflowUser '<owner>' --exportWorkflowType 'technical' --exportWorkflowGroup ''
  --exportWorkflowGroupExclude '<g1,…,g6>' --exportFile '<zip>'` (the help names `""` for
  all groups; `''` was used and works): `1-OK: Workflow group exported successfully.`, exit 0. Six groups joined with commas in one
  single-quoted argument work.
- The archive has the layout of a workflow export: `archive.properties` (`sourceVersion`,
  `operationId`), `Repository.zip`, `workflow/workflow.xml` with one `WorkflowGroup` /
  `WorkflowGroupName` per exported group, `module/module.xml` and the module files. The excluded
  groups are absent, the owner's other groups present. Module files hold clear-text secrets, so
  `acknowledgeClearTextSecrets` stays required.

**Consequences for the plan (decided and applied 2026-11-14, see below).**

| Point | Planned | Recorded on 8.1.17 | Consequence |
|---|---|---|---|
| L-7 `fetchXSLT1.0 -ef` | entry excluded if the file is written on the INUBIT host | the command does not exist | L-7 fallback: `xslt10Report` dropped (T044), the row `excluded:` with a reason such as "command not found on INUBIT 8.1.17" instead of "file written on the INUBIT host"; 9.0 open |
| L-8 `ps -u` | `covered:find_processes` or excluded | one user-defined column per call, only a single index | T045 decides between `covered:find_processes` (with one index `1..5`) and `excluded:<reason>` |
| `nodeByName` | 8.1 only, `moduleName` input | `404` on 8.1.17 | the 8.1 row cannot become `reachable`; `moduleName` would be refused on 8.1 as on 9.0 |
| module detail | `ModuleDetailJsonParser` | XML only | an XML parser over `ns2:Property name/type`, masking by name and by `type="Password"` (D-19) |
| `metadata` action | `GET /process/metadata/{name}` | `500` for every workflow; no positive form | the action's answer shape is unrecorded; the not-found page is the only contract |
| `checkIsConnector` | `GET /isconnector` with the profile's REST auth | `401` | the action cannot work with the profile's credentials; excluded or planned until the authentication is known |
| `export_archives` `metadata` | `workflows[1..50]`, `--exportMetadata` per workflow | a registry export, `--exportMetadataNames` names registry entries | the input becomes optional metadata names, not workflows |
| `ownerWithoutGroups` | `excludeGroups[1..20]` | needs `--exportWorkflowType` and `--exportWorkflowGroup ''` as well | the builder adds both |
| `libraries` | `LibraryListParser` | empty answer only | the row format is unrecorded; the parser knows only "no libraries" |

**Decided after T039 (owner, 2026-11-14).**

1. `ps -u`: `find_processes` gets an optional input `userColumn` (integer `1..5`); the result
   shows that one user-defined column. The values come from the REST `queueLog` field
   `userDefined<i>` (second answer of the same day, below). The row `ps -u` becomes
   `covered:find_processes` on 8.1; 9.0 follows the 9.0 rules (`planned` until recorded).
2. `read_model_details` `metadata` (`GET /process/metadata/{name}`) is not built in US3. The row
   stays `planned` and waits for L-19 (a server where a workflow has metadata). The action stays
   documented as planned.
3. `checkIsConnector` (`GET /isconnector`) is not built in US3. The row stays `planned` and waits
   for L-20 (the authentication of `/isconnector`).
4. `libraries` (`library --list --type driver|plugin`) is built. The empty answer is recorded;
   non-empty lines are read as one file name per line, and the result marks the row format as not
   verified (`rowFormatVerified: false`) until L-21 records a listed library.

The other rows of the table are applied without a decision: `ModuleDetailXmlParser` (masking by
secret name and by `type="Password"`), no `moduleName` input and the 8.1 row of
`GET /model/nodeByName` `excluded:` (9.0 stays `undocumented`), `xslt10Report` dropped (L-7
fallback, 9.0 open), `metadata` export of the registry with optional metadata names, and
`ownerWithoutGroups` with `--exportWorkflowType 'technical'` and `--exportWorkflowGroup ''`.
Spec, plan, data model, contracts, quickstart, D-21 and tasks T040–T047 follow these points.

Decided on the open points found while applying (owner, 2026-11-14, second answer):

5. The `userColumn` values come from the REST `queueLog` field `userDefined<i>`, which the rows
   `find_processes` reads on 8.1 already carry uncut (`fixtures/v8_1/rest/log_queueLog.json`;
   `read_queue_entry` reads them). No StartCLI run, no join by process id; `ps -u` is
   `covered:find_processes` because REST brings the same data (REST first, D-6, Principle V).
6. The 9.0 part of US3 is a follow-up PR after the US3 PR, as for US1 and US2: the US3 PR
   delivers 8.1, and 9.0 stays "not yet verified". Its `planned` 9.0 rows carry the `waits`
   value `9.0-followup` until then (`contracts/coverage-list.md`).
7. `ownerWithoutGroups` exports the owner's technical workflows only
   (`--exportWorkflowType 'technical'`), as recorded.
8. If L-19 or L-20 is not settled before Phase 10, its fallback applies (the row `excluded:`
   with the FR-002 reason "cannot be built from the documentation: answer form not recordable"
   or "…: authentication not documented"), so that no row stays `planned` (SC-001); a working
   form is built in T046b / T046c.
