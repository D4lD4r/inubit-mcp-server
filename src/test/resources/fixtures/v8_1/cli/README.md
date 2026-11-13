# StartCLI fixtures

Recorded and synthetic StartCLI runs of INUBIT 8.1.17, replayed offline by `FakeProcessLauncher`
(one case = `<case>.stdout`, `<case>.stderr`, `<case>.exit`). The feature-001 to feature-003 cases
(`ps_*`, `kill_*`, `processErrorStart_*`, `export_*`, `version`, `login_failed`, `unreachable`,
`unknown_command`) are described in their specs and, for the synthetic ones, in their
`<case>.README`. This file documents the cases of feature 004 (research D-22, D-24, D-25; tasks
T001); `ImportFixturesTest` checks their shape.

## Sources and neutralization

The recordings come from the spike of 2026-10-06 on a development stage
(`~/.inubit-mcp/<profile>/spike`, private, never committed). The spike scripts captured stdout and
stderr of each run in one file with a trailing `exit=<n> seconds=<s>` line; they were split like
the earlier recordings: the JVM line `Picked up JAVA_TOOL_OPTIONS: …` goes to `.stderr`, everything
else to `.stdout`, the exit code to `.exit`. The import protocol keeps INUBIT's `\r\n` line ends and
column padding byte for byte.

Neutralized (with `tools/neutralize.py`, the local maps and a local one-off map; none of them is
committed):

- the person who ran the spike → user `jdoe` (`Doe, John`, `jdoe@example.test`); the initials in
  the person's check-in comment → `JD`;
- the shared user-group owner → `OWNERS`;
- the server host → `inubit-dev-1.example.test`.

Kept: the test names of the spike (`SPIKE`, `SPIKE_Roundtrip_Claude`, `SPIKE_C_…` modules) — they
are neutral names chosen for the spike —, the line layout, timestamps of the error lines and the
Java stack traces.

## Import (`import --importFile '<zip>' … --returnProtocol`)

| Case | Kind | What it is | Source |
|---|---|---|---|
| `import_created` | recorded | `--importWorkflow --importUser 'jdoe'`: a new workflow with 4 new modules; five `was created.` rows, `Total: 5`, exit 0 | `claude/import-create.txt` |
| `import_modified` | recorded | the same archive again: five `was modified.` rows (every import makes a new version) | `t6/import.txt` |
| `import_created_and_modified` | recorded | structural change: one new module (`SPIKE_C_Assign-03` `was created.`), the workflow and four modules `was modified.`, `Total: 6` | `t7/import.txt` |
| `import_module_only` | recorded | `--importModule --importUser 'jdoe'` with a module-only archive: one `Module […] was modified.` row whose `DIAGRAM/MODULE` column is `/<module>` (no workflow) | `t8/import.txt` |
| `import_workflow_only` | recorded | `--importWorkflow --importUser 'jdoe'` with `import_workflow_only.zip` (D-24): one `Diagram […] was modified.` row; referenced modules got no new version | `p4/import-a.txt` |
| `import_workflow_only.zip` | recorded | the archive of that probe: `archive.properties`, `workflow/workflow.xml` with only the one workflow, an **empty** module index `module/module.xml`; no module files, no `Repository.zip`. INUBIT accepted it (D-24) | `p4/a.zip` (built by the spike script from an export) |
| `import_comment_history.xml` | recorded | `versionHistory.xml` excerpt (versions 13–19 of `SPIKE_Roundtrip_Claude`) of the check-in comment probes (D-11, D-24), see below | `p4/after-c2.zip` |
| `import_nok` | **synthetic** | an `n-NOK` result line (`1-NOK: Import failed.`) and exit 1, no protocol. StartCLI's failure lines follow `<n>-NOK`/`ERROR …`; the exact text of a failed import was not recorded (no failing import was provoked) | written for the tests |
| `import_protocol_mismatch` | **synthetic** | `import_modified` with one extra row `Module [SPIKE_C_Assign-09] was modified.` (an artifact that is not in the archive), `Total: 6`; column widths kept | derived from `import_modified` |
| `import_timeout` | **synthetic** | what is left of a run that was stopped on timeout: the preamble only, no protocol; exit 143 is what `FakeProcessLauncher` reports for a destroyed process. Replay it with `hanging()` so that the runner's timeout fires | written for the tests |

These imports were run with `--importUser` for a user. A later probe (D-26) showed that
`--importUserGroup '<group owner>'` is refused with "Missing user or group!" while
`--importUser '<group owner>'` imports for the user group, so feature 004 always uses
`--importUser`; that probe's output was not recorded as a fixture.

### Check-in comment probes (`import_comment_history.xml`)

All on the workflow-only archive shape, `--importWorkflow --importUser 'jdoe'`:

| Version | `CheckinComment` in the imported archive | Stored by INUBIT |
|---|---|---|
| 13 | — (published in the Workbench) | `JD: Spike` |
| 14 | an export's comment (`JD: Spike###…@@@Deploying User: …@@@Version: 13@@@…@@@`) | `DefaultCommitCommentImport###` + the archive's comment |
| 15, 16 | the export comment of version 14, with `--importWorkflowActive` and then `--importWorkflowInactive` (D-15, D-24) | as 14; `IsActive` followed the flag (the export after version 16 shows `false`; the StartCLI output of these two runs was not kept) |
| 17 | a free text (`REASON-PROBE layout`) | `DefaultCommitCommentImport###` — the text is dropped |
| 18 | a `###` segment without the `@@@` suffix | `DefaultCommitCommentImport###` — dropped |
| 19 | the export shape `DefaultCommitCommentImport###REASON-PROBE three###@@@Deploying User: jdoe@@@Server: x@@@Version: 1@@@Export/Deployment: 06.10.2026 20:00:00@@@` | kept as written (the person-written segment survives) |

## Tag (`tag --tagMove '<tag>' … --tagUser '<owner>'`)

| Case | Kind | What it is | Source |
|---|---|---|---|
| `tag_ok` | recorded | a successful `tag --tagMove '<tag>' … --tagUser 'jdoe'` of the spike's tag probe (the recording keeps the output, not the options of that run): preamble, one empty line, exit 0 — StartCLI prints **no** result line for `tag` | `t9/tag.txt` |

Because `tag` prints nothing, the result of a tag call can only be verified by a history export
(D-16). There is no tag removal (D-26: `--tagDelete` acts owner-wide), so the former synthetic
`tag_delete_ok` case was removed.

## Finger (`finger '<name>'`)

| Case | Kind | What it is | Source |
|---|---|---|---|
| `finger_user` | recorded | a user: `Login: jdoe       Name: Doe, John`, `Email: jdoe@example.test`, `Role: …`, exit 0 | `finger-<user>.txt` |
| `finger_not_registered` | recorded | the shared user-group owner: `The user or group "OWNERS" is not registered in the INUBIT Process Engine.`, stack trace, exit 1. An unknown name gives the identical answer, so `finger` cannot tell a user group from a typo; feature 004 does not use it (D-21, D-25) | `finger-<group owner>.txt` |

## REST: user list (removed)

The neutralized recording of `GET /ibis/rest/user/users` (`../rest/user_users.*`, D-21) was
removed with the owner-kind lookup (D-26): imports name every owner with `--importUser`.

## Repository files and releases (feature 005)

The cases of feature 005 (research D-1, tasks T001); `DeployFixturesTest` checks their shape. The
probes of 2026-10-07 ran in a personal test repository area and a personal test diagram group of
a development stage; their raw outputs stay private (`~/.inubit-mcp/<profile>/spike/p5`, never
committed). **Every case below is synthetic**: built by a local one-off script from `grp-a.zip`
and small synthetic documents so that it reproduces the observed structure (entry names, entry
order, metadata attributes, XML shapes, the `5.3` archive comment of repository archives) with
synthetic names only — owner `jdoe`, diagram group `GRP-01`, `Workflow-*`, `Module-*`, repository
folder `/Root/jdoe/xsd/`, tag `TAG-01`. The result lines are the texts the probes printed; where
a text was only noted, not kept byte for byte, the row says so. The success texts
`1-OK: Repository path exported successfully.` and `1-OK: Imported successfully` and the refusal
`Cannot specify user when repository is tagged.` are also string constants of the 8.1.17 client's
StartCLI command classes.

| Case | What it is |
|---|---|
| `export_release.zip` | `export --exportWorkflowUser 'jdoe' --exportWorkflowType 'technical' --exportWorkflowGroup '' --exportTag 'TAG-01' --exportFile '<zip>'`: only the diagram groups carrying the tag (here `GRP-01` of `grp-a.zip`) in their **tagged** versions. As observed: `<Workflow version="<n>">` and `<Module … version="<n>">` instead of `head`, `@@@Tag: TAG-01@@@` in the export suffix of every `CheckinComment`, `tag="TAG-01"` on every `WorkflowModule`, an extra entry `usertags.xml` (`<IBISTagging version="5.3">…<Tag>TAG-01</Tag>…`) after `workflow/workflow.xml`, and a `Repository.zip` with the tagged versions of the repository files the modules reference. `Module-0005` imports `inubitrepository:/Root/jdoe/xsd/release.xsl` (`xsl:import`); `Repository.zip` holds that file in version `1.0` with `tagName="TAG-01"` while its head is `1.1` (`export_repository.zip`) — the "older tagged version" of D-1. `Workflow-0002` is not in edit mode here |
| `export_release.{stdout,stderr,exit}` | `1-OK: Workflow group exported successfully.`, exit 0 (the text of every workflow export) |
| `export_repository.zip` | `export --exportRepositoryPath '/Root/jdoe/xsd' --exportFile '<zip>'`: a directory entry `Root/jdoe/xsd/`, then per file `<name>.xml` (metadata) and `<name>.dat` (content); archive comment `5.3`. Metadata as observed: `<Property name type="RepositoryFile" anonymousRead versionComment writeable uuid path modificator contentSize modified contentEncoding contentType systemElement contentMD5 [tagName] version><Description>…</Description></Property>`; `contentSize` and `contentMD5` match the `.dat`. `release.xsl` is in version `1.1`, `order.xsd` (not referenced by any module) in `1.0`; no `tagName` (untagged export) |
| `export_repository.{stdout,stderr,exit}` | `1-OK: Repository path exported successfully.`, exit 0 |
| `export_repository_not_found` | a path that does not exist: exit 1, `Internal INUBIT error!` and `Path not found //ibis:Root/jdoe/xsd/no-such-dir`. The probe notes put these lines on stderr; the layout of the error record (`ERROR … CLI`, `InubitException: @Start@IError@@@…@End@…`, stack, `EXECUTION ERROR`) follows the recorded `export-group-missing`, not kept byte for byte |
| `import_repository_ok` | `import --importFile '<zip>' --importRepositoryPath '/Root/jdoe'`: a progress line `Completed = 0 MB / 0 MB` and `1-OK: Imported successfully`, exit 0 — **no** protocol table. The archive entries are relative to the import path (`xsd/release.xsl.xml` + `.dat` for `/Root/jdoe/xsd/release.xsl`); entries in the export shape (`Root/jdoe/…`) would be stored below `/Root/jdoe/Root/jdoe/…` (D-1) |
| `tag_repository_user_refused` | `tag --tagMove '<tag>' --tagUser '<owner>' --tagRepositoryPath '<path>'`: refused with `Cannot specify user when repository is tagged.`, exit 1; error layout as above (reconstructed). The server therefore never combines these options (a repository path is tagged through its diagram group, D-1) |

Further facts of the probes without a fixture: a tag export of a repository path without tagged
files returns the directory entry only; repository imports ignore the archive's `uuid` on create
and its `versionComment`, and every import creates a new version; workflow and module imports
ignore `Repository.zip`. An owner-wide tag export with no tagged diagram group was not probed;
the client knows the message `2-NOK: No workflow group containing workflows for export found.`,
and the server treats that message and an archive without workflows alike as `NOT_FOUND`.

## Repository files on the development stage (feature 008)

The cases of feature 008 (research D-7, D-8, D-15; tasks T001, T002). The probes of 2026-10-13 ran
in the personal test repository area of a development stage; their raw outputs stay private
(`~/.inubit-mcp/<profile>/spike/p8`, never committed). Unlike the feature-005 cases these are
**recorded**: the archives are the probes' repository exports, neutralized by a local one-off
script (not committed):

- the owner in every path and the `modificator` → `jdoe`; the personal test folder → `xsd`, so the
  exported folder is `/Root/jdoe/xsd`; the probes' own subfolder `P8` is kept;
- the `uuid`s INUBIT assigned → `00000000-0000-0000-0000-000000000201` to `…0215`, the same file
  keeping the same value in all three archives; the all-zero `uuid` of `probe.xsl` (sent that way
  by a feature-005 probe and kept by INUBIT) is kept;
- the `Description` text of `probe.xsd` and `probe.xsl` → `Repository probe`.

Kept as recorded: the entry names below the exported folder and their order, the directory entries
(also for a folder that holds only subfolders, such as `P8/`), the entry timestamps, the `5.3`
archive comment, every other metadata attribute and value (`version`, `versionComment`,
`modified`, `contentType`, `anonymousRead`, …), the file contents (the probes' own synthetic
documents, so `contentSize` and `contentMD5` still match the `.dat` entries), and the `.stdout`,
`.stderr` and `.exit` files byte for byte. The archives are written again with deflated entries and
data descriptors, as INUBIT writes them.

The probe order behind the cases: W1 imports one new file per extension into `P8/types` with a
`contentType` each, R3 exports that folder; W3 imports a folder archive (directory entries
`xsd/`, `xsd/P8/`, `xsd/P8/folder/`, `xsd/P8/folder/sub/`; the new `P8/folder/main.xsd` and
`P8/folder/sub/included.xsd`; `probe.xsd` changed from `probe4` to `probe8`), R5 exports
`/Root/jdoe/xsd`; W4 imports two new stylesheets into `P8/nested` and changes `probe.xsl`, W6
restores `probe.xsd` and `probe.xsl` to their content before W3, R9 exports `/Root/jdoe/xsd`
again.

| Case | What it is |
|---|---|
| `export_repository_folder.zip` | R9, `export --exportRepositoryPath '/Root/jdoe/xsd' --exportFile '<zip>'` after all probe imports: 16 files, `probe.xsd` and `probe.xsl` in the folder itself and the others in nested folders (`P8/types`, `P8/untyped`, `P8/folder`, `P8/folder/sub`, `P8/nested`). `P8/folder/main.xsd` includes `sub/included.xsd` relatively (`<xs:include schemaLocation="sub/included.xsd"/>`); `included.xsd` has LF line ends and no trailing newline, as sent. New files: `version="1.0"`, `anonymousRead="false"`, `versionComment="DefaultCommitCommentImport@@@null"`, a `uuid` assigned by INUBIT (neutralized), no `Description`. `probe.xsd` (`1.6`) and `probe.xsl` (`1.3`) are back at their earlier content, one version and one `DefaultCommitCommentImport@@@` more per import |
| `export_repository_folder.{stdout,stderr,exit}` | `1-OK: Repository path exported successfully.`, exit 0 |
| `import_repository_new_files.{stdout,stderr,exit}` | W3, `import --importFile '<zip>' --importRepositoryPath '/Root/jdoe'` of the folder archive: no protocol; the progress text `Completed = 0 MB / 0 MB ` is printed twice, each time after a `\r` and without a line break, so `1-OK: Imported successfully` follows the second one **on the same line**, exit 0. The synthetic `import_repository_ok` puts them on lines of their own |
| `export_repository_after_import.zip` | R5, the export of `/Root/jdoe/xsd` right after W3: `P8/folder/main.xsd` and `P8/folder/sub/included.xsd` byte-identical to what W3 sent, in `version="1.0"`; `probe.xsd` with W3's content in `1.5` (the archive's `version` was ignored); `probe.xsl`, untouched, still `1.1` with its entry timestamp of 2026-10-07; `P8/types` and `P8/untyped` from W1 and W2; no `P8/nested` yet |
| `export_repository_types.zip` | R3, `export --exportRepositoryPath '/Root/jdoe/xsd/P8/types'`: one file per extension (`.wsdl`, `.xml`, `.xsd`, `.xsl`, `.xslt`) with the `contentType` the import sent, stored unchanged (`application/wsdl+xml`, `application/xml`, `application/x-xsd+xml`, `application/xslt+xml` for `.xsl` and `.xslt`). The `.xslt` and `.wsdl` values are the probe's choice; INUBIT's own default for them was not observed. Without a `contentType` (W2, `P8/untyped` in the other two archives) INUBIT stores `application/octet-stream` |

## Client version (`startcli.sh -v`, feature 009)

The variants of the recorded `version` case for feature 009 (research D-15; tasks T001). Both are
**synthetic**, derived from `version.*` by a text change; no StartCLI was run for them.

| Case | Kind | What it is |
|---|---|---|
| `version` | recorded | the 8.1.17 client: preamble, `CLI 8.1.17`, copyright and URL lines, `Supported inubit Process Engine versions from 4.0.1 to 8.1.17.`, exit 0 |
| `version_no_range` | **synthetic** | `version` without the `Supported …` line: `CLI 8.1.17` is the only version line, so the client reports no supported range; stderr and exit 0 unchanged |
| `../../v9_0/cli/version` | **synthetic** | a 9.0 client: `version` with `CLI 9.0.7` and `Supported inubit Process Engine versions from 9.0.0 to 9.0.7.`; stderr and exit 0 unchanged. Kept with the other 9.0 fixtures, see `../../v9_0/README.md` |

## Administration cases (feature 022)

The recordings of the administration commands and endpoints of feature 022 (research D-26,
`quickstart.md` "Live checks"; tasks T002). A coverage-list row becomes `reachable` for 8.1 only
in the commit that adds its recording here, together with its row in the table below. The first
cases come from T014 (below).

- **Name**: `admin-<entry-slug>[-<variant>]`. `<entry-slug>` names the coverage-list entry or the
  group of entries the recording belongs to, lower case with hyphens (`users`, `tags`,
  `settings-change`, …); the optional `<variant>` names the input or outcome (`-empty`,
  `-unknown`, `-refused`, …).
- **CLI cases** (this directory): `<case>.stdout`, `<case>.stderr`, `<case>.exit`, split as
  described in "Sources and neutralization" above. A case of a stdin session also has
  `<case>.stdin`: the lines the session sent, one per line, with the password line replaced by
  `<password>`.
- **REST cases** (`../rest/`): `<case>.http` (status and headers) and the body in the format the
  endpoint answered with (`<case>.json`, `<case>.xml`, …), as the existing REST cases.
- **Neutralization**: with `tools/neutralize.py`, then checked with `tools/check-identifiers.py`;
  the raw outputs are never committed. Every user, workflow, host and value in a case is
  fictitious, and every secret (password, configuration or module value, key material, e-mail
  in a listing) is replaced by a planted test secret.

| Case | Kind | Coverage-list entry | What it is |
|---|---|---|---|
| `admin-settings-session` | recorded | the US1 CLI reads below | one stdin session (`.stdin`): `threads showNumberOfWorkflowThreads`, `privilegedWorkflows showThreadsPercentage`, `privilegedWorkflows showWorkflowList`, `dbconnectiontolerance show`, `date`, `license -td`, `license -ts`, `exit`. Before the first prompt: `Connecting as '<user>' to <cliUrl> …`, `Password: ` followed by a line break, the hostname warning, an empty line, `Connection to inubit Process Engine 8.1.17 established.` and two help lines. Each command is echoed as `CLI\|jdoe@inubit-dev-1.example.test> <command>`; its answer follows up to the next echo. The three root-only commands answer `EXECUTION ERROR` / `Only root user can use this command.` and the session goes on; exit 1 |
| `admin-settings-threads-refused` | recorded | CLI `threads showNumberOfWorkflowThreads` | script mode, account without root: `ERROR … CLI` record, `InubitException: @Start@IError@@@Internal INUBIT error!@End@@Start@Only root user can use this command.…`, stack trace, `EXECUTION ERROR`, `Internal INUBIT error!`, `Only root user can use this command.`, exit 1 |
| `admin-settings-privileged-percentage-refused` | recorded | CLI `privilegedWorkflows showThreadsPercentage` | as `admin-settings-threads-refused` |
| `admin-settings-privileged-list-refused` | recorded | CLI `privilegedWorkflows showWorkflowList` | as `admin-settings-threads-refused` |
| `admin-settings-db-tolerance` | recorded | CLI `dbconnectiontolerance show` | script mode: `Error level: \t\t0%`, an empty line, `Fault threshold: \t20`, `Drop rate: \t\t5%`, `Drop interval (s): \t1800 seconds`, an empty line (tabs and LF as recorded; the session answer ends with a `\r` line instead); exit 0 |
| `admin-settings-date` | recorded | CLI `date` | script mode: `Fri Nov 13 05:52:06 CET 2026` (Java `Date.toString`, server time zone); exit 0 |
| `admin-settings-license-days` | recorded | CLI `license -td` | script mode: a bare number, the days until the license expires (`7300`); exit 0 |
| `admin-settings-license-seconds` | recorded | CLI `license -ts` | script mode: a bare number, the seconds until the license expires (`630699804`, 196 less than in the session run a few minutes earlier); exit 0 |
| `../rest/admin-license` | recorded | REST `GET /license` | `200`, `text/xml;charset=UTF-8` (Accept XML): `<license>` with `LicenseInformation` (edition, customer number, company, contact, key type, request and key ids, generation date `dd.MM.yyyy`, system id), `inubit` (`AllowedIPs`, `AllowedMACs/MAC`, `LicenseType` with `ExpirationDate` `yyyy-MM-dd`, version, counts, flags; `Features` with `Plugins type="…"`) and an XML-DSig `Signature` |
| `../rest/admin-configurations` | recorded | REST `GET /configurations` | `200`, `application/json;charset=UTF-8` (Accept JSON): one compact line, root key `ServerConfiguration`, no `type` or `encrypted` fields; three planted test secrets (see below) |
| `admin-users-session` | recorded | the US2 CLI reads below | one stdin session (`.stdin`): `user '<login>'` and `finger '<login>'` for `jdoe`, `batch-user` and `qa_robot`, then `-who`, `-nwho`, `msglist`, `exit`. Preamble and echo as in `admin-settings-session`. `-who` and `-nwho` answer `Command not found.` and the session goes on; `msglist` answers only its header line; exit 1 |
| `admin-users-user` | recorded | CLI `user <name>` | script mode, `user 'jdoe'`: three rows `User name`, `User group`, `Super group`; labels padded to 13 characters, values padded to the longest value plus one space; each row ends in CRLF, then an empty line (LF); exit 0 |
| `admin-users-user-hyphen` | recorded | CLI `user <name>` | as `admin-users-user`, login with a hyphen (`batch-user`) |
| `admin-users-user-underscore` | recorded | CLI `user <name>` | as `admin-users-user`, login with an underscore (`qa_robot`) |
| `admin-users-finger` | recorded | CLI `finger <login>` | script mode, `finger 'jdoe'`: `Login: ` with the login padded to 15 characters, `Name: Doe, John` (last name, first name), `Email: john.doe@example.test`, `Role: Platform Administrator`; no account state; the last line ends in LF only (CRLF in the session); exit 0 |
| `admin-users-finger-hyphen` | recorded | CLI `finger <login>` | as `admin-users-finger` for `batch-user` (`Name: Service, Batch`) |
| `admin-users-finger-no-name` | recorded | CLI `finger <login>` | as `admin-users-finger` for `qa_robot`, a user without first and last name: the `Login:` line holds only the padded login, no `Name:`; e-mail with a `+` tag; a customer role `XYZ_operator` |
| `admin-users-who-missing-argument` | recorded | CLI `-who` | script mode, `--execCommand -who`: the client takes `-who` as its own option, not as the command: `EXECUTION ERROR`, `Missing argument for option: execCommand`, no connection; exit 1 |
| `admin-users-nwho-missing-argument` | recorded | CLI `-nwho` | as `admin-users-who-missing-argument` for `--execCommand -nwho` |
| `admin-users-msglist-empty` | recorded | CLI `msglist` | script mode, the profile account has no message: only the header `ID   Subject … Sender … Date` (CRLF) and an empty line; exit 0 |
| `../rest/admin-users` | recorded | REST `GET /user/users` | `GET /user/users?type=processEngineUser`, `200`, `application/xml;charset=UTF-8` (Accept XML): one line without a trailing line break, root `UserList` in the default namespace `inubit.com/ibis/external/user` (plus an unused `ns2` prefix), 58 `User` elements with `id`, `email`, `type="ProcessEngineUser"` and, for 26 of them, `firstName` and `lastName`; not sorted by `id`; no account state |

Neutralization of the T014 cases (recorded 2026-11-13 on an 8.1.17 development server with a
non-root account; raw outputs private, never committed):

- the account → `jdoe`, the server host → `inubit-dev-1.example.test`, the trust-store path in
  `Picked up JAVA_TOOL_OPTIONS` → `/home/jdoe/.config/inubit-mcp/dev-truststore.p12`;
- `dbconnectiontolerance` values, the `license -td`/`-ts` numbers (session and script runs) →
  fictitious; `date` and the error-record timestamps are kept;
- license: customer number, company (`Fictitious Licensee 0001`), contact (`John Doe`), request
  and key ids (`00000000-0000-0000-0000-00000000040x`), generation date, system id, MAC
  (`02:00:00:00:00:01`), expiry date, user counts → fictitious; `DigestValue`, `SignatureValue`
  and `Modulus` → synthetic base64 in the recorded layout (76-character lines joined by
  `&#xD;` and a line break), so the signature no longer verifies. The edition name →
  `Synthetic Edition`. Key type, version, unlimited markers and the plugin list are kept. The expiry date keeps the recorded relation to
  `license -td` (see research "Live recording T014");
- configuration: proxy hosts and exceptions, proxy user, `ServerName`, `ServerID`,
  `ClusterKnownNodes` (shortened to four entries), the node name key of `NodeMaintenanceModeMap` and the four login values →
  fictitious; vendor defaults (`localhost` URLs, LDAP and Keycloak placeholders, mime types, SSH
  algorithm lists, log layouts) and plain numeric and boolean settings are kept as recorded;
- planted test secrets in the configuration (the recording has no secret leaf at all):
  `ProxyPassword` after `ProxyUserName` (`synthetic-secret-0401`, by name),
  `SMTPProperties/ErrorPassword` after `ErrorUser` (`synthetic-secret-0402`, by name) and
  `ProcessUserServer/ProcessUserImTenantAdminCredentials/Value` (`synthetic-secret-0403`, by path;
  recorded as `{}`).

T025 replaced the `*-reconstructed` cases of `dbconnectiontolerance show`, `date`, `GET /license`
and `GET /configurations` by these recordings. The three `*-reconstructed` cases of the root-only
reads (`admin-settings-threads-reconstructed`, `admin-settings-privileged-percentage-reconstructed`,
`admin-settings-privileged-list-reconstructed`) stay SYNTHETIC: the profile's account could record
only their refusal, and their coverage rows are `excluded:needs the INUBIT root user` (owner
decision 2026-11-13).

Neutralization of the T028 cases (recorded 2026-11-13 on the same server and account; raw outputs
private, never committed). Every value was replaced in one pass, the same way in every file:

- the profile account → `jdoe` (`Doe, John`, `john.doe@example.test`), the other two logins →
  `batch-user` (`Service, Batch`, `batch.service@external.example.test`) and `qa_robot`
  (`qa.robot+qa_inubit@corp.example.test`, no name, as recorded); the hyphen, the underscore, the
  `+` tag and the missing name are kept from the recorded logins;
- the other 55 users of the REST list → `User<nn>` / `user<nn>` (`<nn>` = position in the list;
  lower case where the recorded login starts lower case), `user<nn>@<domain>`, `First<nn>` /
  `Last<nn>` where the recording has names; the two non-ASCII names keep one non-ASCII character
  (`Läst<nn>`); the six recorded e-mail domains → six fictitious `*.example.test` domains, one to
  one; entry count, attribute sets and order are unchanged;
- the user group → `OPS`, the super group → `headoffice`, INUBIT's role `System Administrator` →
  `Platform Administrator`, the customer role → `XYZ_operator`;
- the server host and URL, the account in the echo and the trust-store path as in T014;
- the column padding of the `user` rows and of the `finger` login was rebuilt for the new values
  with the rules the three recordings show (labels 13, values longest plus one; login 15).
