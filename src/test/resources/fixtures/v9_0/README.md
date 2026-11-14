# INUBIT 9.0 fixtures

**Mostly documentation-derived ("reconstructed").** Apart from the administration recordings of
feature 022 (`admin-*`, recorded 2026-11-13 and 2026-11-14 on INUBIT 9.0.10), no INUBIT 9.0 node has been
recorded (feature 017, research D-10). Every other case was derived from the recorded 8.1.17 fixtures (`../v8_1/`) and
the documented 9.0 differences (`docs/research/inubit-interfaces.md` §4). Tests open these files
only through `de.dadecker.inubit.mcp.testing.V90Cases`, which refuses a case without its
`<case>.origin` file.

`<case>.origin` states the provenance in its first line:

- `reconstructed <source> …` — derived from documentation and 8.1 recordings; not verified
  against a 9.0 node.
- `recorded <date> <version>` — written by `tools/record-fixtures.sh` with
  `RECORD_INUBIT_LINE=9.0` against a real 9.0 node.

`NOT_RECORDABLE.txt` lists the cases the recorder does not capture (writes, server states that
cannot be provoked safely, or administration reads recorded by hand in feature 022), each with its
reason. `V90RecordedFixturesTest` lists the
recordable cases that are still `reconstructed`; it never fails the build.

| Case | Derived from | 9.0 change |
|---|---|---|
| `rest/system_info` | `../v8_1/rest/system_info.*` | `Version` = `9.0.7`, `ServerJDKVersion` = `21.0.5 - 64 bit`, `ServerJDKSpec` = `21` (9.0 bundles JDK 21) |
| `rest/healthcheck`, `rest/ready`, `rest/metrics`, `rest/log_systemLog`, `rest/log_systemLog_filtered`, `rest/model_models_owner`, `rest/modelByName_sample`, `rest/model_export_sample` | the 8.1 case of the same name | none |
| `rest/rest_auth_failed` (401), `rest/rest_forbidden` (403), `rest/rest_not_found` (404), `rest/rest_internal_error` (500), `rest/rest_unavailable` (503) | §4: JSON error body `{statusCode, message, path, timestamp}` | the message texts are invented |
| `cli/version` | `../v8_1/cli/version.*` | `CLI 9.0.7`, `Supported inubit Process Engine versions from 9.0.0 to 9.0.7.` |
| `cli/help` | §2.1 option list, layout of `../v6_1/cli/help.stdout` | 9.0 shows the help only with `-h`/`--help` |
| `cli/login_failed`, `cli/unreachable`, `cli/processErrorStart_unknown`, `cli/kill_unknown`, `cli/unknown_command` | the 8.1 case of the same name | `ibis.jar:9.0.7` in the stack frames; no lines of the SOAP client (removed in 9.0) |
| `cli/ps_all`, `cli/export_history_sample`, `cli/export_modules_sample`, `cli/import_created`, `cli/tag_ok`, `cli/processErrorStart_ok`, `cli/kill_ok` | the 8.1 case of the same name | none |

## Recording

`RECORD_INUBIT_LINE=9.0 tools/record-fixtures.sh --list` prints the catalogue (`V90_CASES` of the
script); `RECORD_INUBIT_LINE=9.0 tools/record-fixtures.sh <group> <node> <case>` records one case
of a non-production 9.0 node into this directory (StartCLI against `<baseUrl>`, each REST case
with the `Accept` of its adapter) and writes `<case>.origin` = `recorded <date> <version>`. The
StartCLI client is `RECORD_CLI_HOME`, else the `defaults.cliClients."9.0"` entry, else
`defaults.cliHome` (JDK: `RECORD_CLI_JAVA_HOME`, else the entry's `javaHome`, else
`defaults.cliJavaHome`). Every CLI case first checks that this client reports `CLI 9.0.*`
(`startcli -v`), every case with a node that the node reports 9.0; otherwise nothing is recorded.
`V90FixtureCatalogueTest` keeps catalogue ∪ `NOT_RECORDABLE.txt` = the files here = `V90Cases`.

## Administration cases (feature 022)

The 9.0 counterparts of the feature-022 administration cases (research D-26; tasks T002). A
coverage-list row becomes `reachable` for 9.0 only in the commit that adds its recording here,
together with its row in the table below. The first cases come from T024 and T028b (below), recorded
after the owner confirmed the renewed certificate of the 9.0 test server (2026-11-13); the US3
cases (`admin-model-*`) come from T039a (2026-11-14).

- **Name**: `admin-<entry-slug>[-<variant>]`, the same name as the 8.1 case of the same command
  or endpoint (see "Administration cases (feature 022)" in `../v8_1/cli/README.md`).
- **CLI cases** (`cli/`): `<case>.stdout`, `<case>.stderr`, `<case>.exit`; a case of a stdin
  session also has `<case>.stdin`, the lines the session sent, with the password line replaced by
  `<password>`.
- **REST cases** (`rest/`): `<case>.http` (status and headers) and the body in the format the
  endpoint answered with (`<case>.json`, `<case>.xml`, …).
- **`<case>.origin`**, first line, one of:
  - `recorded <date> <version> neutralized` — recorded against a non-production 9.0 node, then
    neutralized; `tools/record-fixtures.sh` writes `recorded <date> <version>`, and the person who
    runs `tools/neutralize.py` appends ` neutralized` (for feature-022 cases this form is required);
  - `reconstructed <source>` — derived from `<source>` (documentation or the 8.1 recording);
    not verified against a 9.0 node.
- **Catalogue**: every case is listed in `V90_CASES` of `tools/record-fixtures.sh` or in
  `NOT_RECORDABLE.txt` (a case the recorder does not capture, with its reason), and is opened
  through `V90Cases`, as `V90FixtureCatalogueTest` requires.
- **Neutralization**: with `tools/neutralize.py`, then checked with `tools/check-identifiers.py`;
  the raw outputs are never committed. Every user, workflow, host and value in a case is
  fictitious, and every secret (password, configuration or module value, key material, e-mail
  in a listing) is replaced by a planted test secret.

| Case | `.origin` | Coverage-list entry | What it is |
|---|---|---|---|
| `cli/admin-settings-session` | `recorded 2026-11-13 9.0.10 neutralized` | the US1 CLI reads below | one stdin session (`.stdin`): `threads showNumberOfWorkflowThreads`, `privilegedWorkflows showThreadsPercentage`, `privilegedWorkflows showWorkflowList`, `dbconnectiontolerance show`, `date`, `license -td`, `license -ts`, `exit`. Before the first prompt: `JAVA_HOME is set`, the hostname warning, the client's Netty `ERROR … DnsServerAddressStreamProviders …` line (the client ran on macOS), the warning, `Password: ` followed by a line break, the warning, `Connecting as 'jdoe' to https://inubit-dev-2.example.test:8443?isCli`, an empty line, `Connection to inubit Process Engine 9.0.10 established.` and two help lines; every line ends in LF. Each command is echoed as `CLI\|jdoe@inubit-dev-2.example.test> <command>`. The three root-only commands answer `EXECUTION ERROR` / `Only root user can use this command.` and the session goes on; exit 0 (8.1: 1) |
| `cli/admin-settings-threads-refused` | `recorded 2026-11-13 9.0.10 neutralized` | CLI `threads showNumberOfWorkflowThreads` | script mode, account without root: the preamble, then only `EXECUTION ERROR` and `com.inubit.ibis.utils.InubitException: @Start@IError@@@Internal INUBIT error!@End@@Start@Only root user can use this command.@@@Only root user can use this command.@End@` (no error record, no stack trace); exit 1 |
| `cli/admin-settings-privileged-percentage-refused` | `recorded 2026-11-13 9.0.10 neutralized` | CLI `privilegedWorkflows showThreadsPercentage` | as `admin-settings-threads-refused` |
| `cli/admin-settings-privileged-list-refused` | `recorded 2026-11-13 9.0.10 neutralized` | CLI `privilegedWorkflows showWorkflowList` | as `admin-settings-threads-refused` |
| `cli/admin-settings-db-tolerance` | `recorded 2026-11-13 9.0.10 neutralized` | CLI `dbconnectiontolerance show` | script mode: `Error level: \t\t0%`, an empty line, `Fault threshold: \t12000`, `Drop rate: \t\t75%`, `Drop interval (s): \t60 seconds`, then two empty lines (LF; the session block ends with one); exit 0 |
| `cli/admin-settings-date` | `recorded 2026-11-13 9.0.10 neutralized` | CLI `date` | script mode: `Fri Nov 13 14:38:21 CET 2026` and an empty line; exit 0 |
| `cli/admin-settings-license-days` | `recorded 2026-11-13 9.0.10 neutralized` | CLI `license -td` | script mode: a bare number (`7210`) and an empty line; exit 0 |
| `cli/admin-settings-license-seconds` | `recorded 2026-11-13 9.0.10 neutralized` | CLI `license -ts` | script mode: a bare number (`622887694`, 15 less than in the session) and an empty line; exit 0 |
| `rest/admin-license` | `recorded 2026-11-13 9.0.10 neutralized` | REST `GET /license` | `200`, `application/xml;charset=UTF-8` (8.1: `text/xml`): `<license>` as on 8.1, indented by three spaces, with two new `LicenseInformation` elements, `LicenseName` and `Environment` (`Test`); `LicenseType/Version` `9.0`; XML-DSig `Signature` in the 8.1 layout |
| `rest/admin-configurations` | `recorded 2026-11-13 9.0.10 neutralized` | REST `GET /configurations` | `200`, `application/xml;charset=UTF-8` although the request accepts JSON and the body is JSON: one compact line, root key `ServerConfiguration`, no `type` or `encrypted` fields; three planted test secrets (see below) |
| `cli/admin-users-session` | `recorded 2026-11-13 9.0.10 neutralized` | the US2 CLI reads below | one stdin session (`.stdin`): `user '<login>'` and `finger '<login>'` for `jdoe`, `Ops_viewer` and `qa_robot`, then `msglist`, `exit`. Preamble and echo as in `admin-settings-session`. `user 'jdoe'` has a fourth row `Additional user group`; `msglist` answers only its header line; exit 0 |
| `cli/admin-users-user` | `recorded 2026-11-13 9.0.10 neutralized` | CLI `user <name>` | script mode, `user 'jdoe'`: four rows `User name`, `User group`, `Super group`, `Additional user group`; labels padded to the longest label plus two (23), values to the longest value plus one; each row ends in CRLF, then two empty lines (LF); exit 0 |
| `cli/admin-users-user-capitalized` | `recorded 2026-11-13 9.0.10 neutralized` | CLI `user <name>` | as `admin-users-user` for `Ops_viewer` (capital initial, underscore; no additional group, so labels padded to 13) |
| `cli/admin-users-user-underscore` | `recorded 2026-11-13 9.0.10 neutralized` | CLI `user <name>` | as `admin-users-user-capitalized` for `qa_robot` |
| `cli/admin-users-finger` | `recorded 2026-11-13 9.0.10 neutralized` | CLI `finger <login>` | script mode, `finger 'jdoe'`: `Login: ` with the login padded to 15 characters, `Name: Doe, John`, `Email: john.doe@example.test`, `Role: Platform Administrator` (LF), then an empty line; exit 0 |
| `cli/admin-users-finger-capitalized` | `recorded 2026-11-13 9.0.10 neutralized` | CLI `finger <login>` | as `admin-users-finger` for `Ops_viewer`, a user without names (no `Name:`), role `XYZ_maintainer`; its e-mail `ops.viewer@corp.example.test` is shared with another user of the REST list, as recorded |
| `cli/admin-users-finger-no-name` | `recorded 2026-11-13 9.0.10 neutralized` | CLI `finger <login>` | as `admin-users-finger-capitalized` for `qa_robot` (`qa.robot+qa_inubit@corp.example.test`, `XYZ_operator`) |
| `cli/admin-users-msglist-empty` | `recorded 2026-11-13 9.0.10 neutralized` | CLI `msglist` | script mode, the profile account has no message: only the header `ID   Subject … Sender … Date` (CRLF) and two empty lines; exit 0 |
| `cli/admin-users-who` | `recorded 2026-11-13 9.0.10 neutralized` | CLI `-who` | a client run of its own, `startcli.sh … --who <url>`, password on stdin (`.stdin`): the script-mode preamble (with the Netty line), then the table with the 8.1 header; `SYSTEM-ID` 65 characters wide (8.1: 64; the longest `SYSTEM-ID` is one character longer); three JDBC URLs, `Portal (BPC)`, `Process User Server (inubit)`; **no `Toolset` row** for the client's own connection and no `STATE` in any row; header and rows end in CRLF, then an empty line; exit 0 |
| `cli/admin-users-nwho` | `recorded 2026-11-13 9.0.10 neutralized` | CLI `-nwho` | as `admin-users-who` for `--nwho`: the preamble, then `5` (the number of rows `--who` lists), LF; exit 0 |
| `rest/admin-users` | `recorded 2026-11-13 9.0.10 neutralized` | REST `GET /user/users` | `GET /user/users?type=processEngineUser`, `200`, `application/xml;charset=UTF-8`: XML declaration with `standalone="yes"`, root `ns2:UserList` with `ns2` bound to `inubit.com/ibis/external/user` and `ns1` to `…/base` (8.1: default namespace), one `ns2:User` per line indented by four spaces, trailing line break; 55 users with `id`, `email`, `type="ProcessEngineUser"` and, for 29, `firstName` and `lastName`; not sorted by `id`; no account state |
| `rest/admin-model-node-by-model` | `recorded 2026-11-14 9.0.10 neutralized` | REST `GET /model/nodeByModel/{d}/{id}` | the Database Connector module of the 8.1 case (same id, connection and property order), Accept XML: `200`, `application/xml;charset=UTF-8`, XML declaration with `standalone="yes"`, root `ns2:Node` with `ns2` = `inubit.com/ibis/external/model` and `ns1` = `…/base` (8.1: `ns4`/`ns2` and an unused `ns3`), indented by four spaces per level, one element per line, trailing line break; 37 recorded `ns1:Property name= type=` (`Boolean`, `Integer`, `String`, `Password`) plus the planted `proxyPassword` (see below) |
| `rest/admin-model-node-by-model-json-refused` | `recorded 2026-11-14 9.0.10 neutralized` | REST `GET /model/nodeByModel/{d}/{id}` | the same request with Accept `application/json`: `406`, `application/json;charset=UTF-8`, one line `{"statusCode":406,"message":"The requested media type is not acceptable.","path":…,"timestamp":…}` without a trailing line break (8.1 answered XML with `200`) |
| `rest/admin-model-node-by-model-is-connector` | `recorded 2026-11-14 9.0.10 neutralized` | REST `GET /model/nodeByModel/{d}/{id}` | the IS Connector module of the 8.1 case (`type="twinubitISConfiguration"`, `parentId`), one `ErrorSuppression` property; `200`, `application/xml;charset=UTF-8`, layout as `admin-model-node-by-model` |
| `rest/admin-model-node-by-model-missing` | `recorded 2026-11-14 9.0.10 neutralized` | REST `GET /model/nodeByModel/{d}/{id}` | unknown module id `999999`: `500`, `application/xml` (no charset), one line without a trailing line break, `<ApiError>` with `statusCode` `500`, the generic `message` `Error while getting node by model`, `path` and `timestamp` (8.1: the `text/html` status page saying the module could not be found) |
| `rest/admin-model-node-by-name-not-found` | `recorded 2026-11-14 9.0.10 neutralized` | REST `GET /model/nodeByName/{d}/{m}` | the existing diagram and the name of the module `nodeByModel` returned: `404`, `application/xml;charset=UTF-8`, `<ApiError>` `Resource Not Found` (form as `admin-model-node-by-model-missing`) |
| `rest/admin-model-metadata-not-found` | `recorded 2026-11-14 9.0.10 neutralized` | REST `GET /process/metadata/{wf}` | the workflow of the module cases: `500`, `application/xml`, `<ApiError>` `An unexpected error occurred for workflow: Workflow-0301` (8.1: `Workflow details not found for …`); the metadata registry is empty (`cli/admin-model-export-metadata.zip`), so no positive answer is recorded |
| `rest/admin-model-isconnector-refused` | `recorded 2026-11-14 9.0.10 neutralized` | REST `GET /isconnector?…type=validateWorkflow` | `401`, `application/xml;charset=UTF-8`, `<ApiError>` `Authentication failed: The credentials entered are incorrect.` for the Basic auth every other REST call accepts; the recorded answer also had `WWW-Authenticate: Basic realm="inubit REST interface"` (not kept: `.http` holds status and content type only; 8.1: `text/plain`, no `WWW-Authenticate`) |
| `cli/admin-model-library-driver` | `recorded 2026-11-14 9.0.10 neutralized` | CLI `library --list --type driver` | script mode: the preamble, then two empty lines (the empty answer and the extra 9.0 script-mode line; no library uploaded); exit 0 |
| `cli/admin-model-library-plugin` | `recorded 2026-11-14 9.0.10 neutralized` | CLI `library --list --type plugin` | as `admin-model-library-driver` |
| `cli/admin-model-fetchxslt-not-found` | `recorded 2026-11-14 9.0.10 neutralized` | CLI `fetchXSLT1.0` | script mode, `fetchXSLT1.0 -h`: the preamble, `EXECUTION ERROR` and `com.inubit.ibis.utils.InubitException: @Start@IError@@@Internal INUBIT error!@End@Command not found.` (no record, no stack trace, no empty line after); exit 1: the command does not exist on 9.0.10 |
| `cli/admin-model-export-metadata` | `recorded 2026-11-14 9.0.10 neutralized` | CLI `export --exportMetadata` | `export --exportMetadata --exportFile '<zip>'`: the preamble, `1-OK: Metadata exported successfully.` and an empty line; exit 0. `.zip`: `MetadataRegistry.xml` and `MetadataAllocations.xml`, each an XML declaration, an empty line and an empty root with `version="4.1"` (`<MetadataRegistry version="4.1"/>`, 8.1 with a space before `/>`); the ZIP comment `#<date>`, `Date=…`, `Type=MetadataExport`, `User=jdoe`, `Version=6.0.0` (8.1: `Date=` last) |
| `cli/admin-model-export-metadata-names` | `recorded 2026-11-14 9.0.10 neutralized` | CLI `export --exportMetadataNames` | `export --exportMetadata --exportMetadataNames 'Example1,Example2' --exportFile '<zip>'` (single-quoted, the form the server sends; 8.1 recorded it unquoted): `1-OK: Metadata Names exported successfully.` and an empty line; exit 0. `.zip` as in `admin-model-export-metadata` (the names exist nowhere on the server) |
| `cli/admin-model-export-group-exclude` | `recorded 2026-11-14 9.0.10 neutralized` | CLI `export --exportWorkflowGroupExclude` | `--exportWorkflowUser '<owner>' --exportWorkflowType 'technical' --exportWorkflowGroup '' --exportWorkflowGroupExclude '<g1,…,g6>' --exportFile '<zip>'` (the T041 form): `1-OK: Workflow group exported successfully.` and an empty line; exit 0. The archive is not committed (it holds clear-text module secrets) |
| `cli/admin-model-session` | `recorded 2026-11-14 9.0.10 neutralized` | the US3 CLI reads | one stdin session (`.stdin`): `library --list --type driver`, `library --list --type plugin`, `fetchXSLT1.0 -h`, `exit`. Preamble and echo as in `admin-settings-session`. Each `library` block is one empty LF line; `fetchXSLT1.0 -h` answers `Command not found.` and the session goes on; exit 0 (8.1: 1) |

Neutralization of the T024 and T028b cases (recorded 2026-11-13 on the 9.0.10 test node with the
profile's non-root account, every command once; raw outputs private, never committed). Every
value was replaced in one pass, the same way in every file; where the same user or value appears in
the 8.1 cases, it has the same fictitious value there:

- the account → `jdoe`, the server host → `inubit-dev-2.example.test` (a node of its own), the
  trust-store path → `/home/jdoe/.config/inubit-mcp/dev-truststore.p12` (as on 8.1);
- `dbconnectiontolerance` values and the `license -td`/`-ts` numbers → fictitious; the days still
  count to one month after the fictitious `ExpirationDate` (`2046-07-10`), as recorded; `date` and
  the Netty line's timestamp are kept;
- license: as on 8.1 (`Fictitious Licensee 0001`, `John Doe`, customer number `900`, synthetic
  base64 signature in the recorded layout, edition → `Synthetic Edition`), other ids for the other
  license (`…002401`, `…002402`, system id `00900003`, MAC `02:00:00:00:00:02`), `LicenseName` →
  `Fictitious Licensee 0001 (Integration System 2)`; `Environment`, key type, versions, unlimited
  markers and the plugin list are kept;
- configuration: as on 8.1 (proxy values, logins → `jdoe`), `ServerName`/`ServerID` →
  `inubit-dev-2.example.test`, `ClusterKnownNodes` shortened to four fictitious entries ending with
  the maintenance-map node `ip-192-0-2-11`; planted test secrets `ProxyPassword`
  (`synthetic-secret-2401`), `SMTPProperties/ErrorPassword` (`synthetic-secret-2402`) and
  `ProcessUserServer/ProcessUserImTenantAdminCredentials/Value` (`synthetic-secret-2403`,
  recorded as `{}`);
- users: the 50 users the 8.1 list also has keep their 8.1 login and number (`jdoe`, `qa_robot`,
  `User<nn>`/`user<nn>`, `First<nn>`/`Last<nn>`) and the 8.1 domain map (44 of them are identical
  to their 8.1 entry); of the five new ones four get `<nn>` 60–63 and the fifth is the picked one
  (no login with a hyphen exists on 9.0), `Ops_viewer`; the user group →
  `OPS`, the super group → `headoffice`, the additional group → `ProcessPortal`, `System
  Administrator` → `Platform Administrator`, the customer roles → `XYZ_operator` (as on 8.1) and
  `XYZ_maintainer`; padding rebuilt with the recorded rules;
- `--who`: the database host → `inubit-db-002.example.test` and the database names →
  `inubitlogdb` / `inubitcachedb` (same lengths, so the URLs and the column keep their recorded
  widths), `IDLE` → `52:07:13s`; the `nwho` count is kept.

Neutralization of the T039a cases (`admin-model-*`; recorded 2026-11-14 on the 9.0.10 test node
with the profile's non-root account, every command once; raw outputs private, never committed),
with the values of T024/T028b and of the 8.1 T039 cases (`../v8_1/cli/README.md`):

- the account → `jdoe`, the server host → `inubit-dev-2.example.test`, the trust-store path →
  `/home/jdoe/.config/inubit-mcp/dev-truststore.p12`, `User=` in the comment of the two
  metadata archives → `jdoe` (the archives were rebuilt with the recorded entries and comment);
- the workflow → `Workflow-0301`, the modules → `Demo-DB-Probe` and `Demo-IS-Probe` (also in the
  ApiError `path` and `message`); the ids, property names, types, order and the boolean and
  integer values are kept; the `staticQuery` text → `select version from demo_versions`;
- every time of day (Netty lines, ApiError and JSON `timestamp`, ZIP comment and entry times)
  moved back twelve hours; date, minutes, seconds and milliseconds kept;
- the `export` options: the owner and the six excluded groups are named only as placeholders
  (`<owner>`, `<g1,…,g6>`) in the table above; `Example1,Example2` are the names the recording
  used;
- planted values in `rest/admin-model-node-by-model.xml`, as on 8.1 (the recorded module, which
  uses the IS log database, had `Password`, `dbURL`, `driverClass` and `user` empty):
  `Password` (type `Password`) → `synthetic-secret-3901`; one added property `proxyPassword`
  (type `String`, in the recorded alphabetical order before `staticQuery`, indented like its
  neighbours) → `synthetic-secret-3902`, a secret found only by its name; `dbURL` →
  `jdbc:postgresql://inubit-db-01.example.test:5432/demodb`, `driverClass` →
  `org.postgresql.Driver`, `user` → `demo_reader` (no secrets). Whether INUBIT sends a set
  password in clear text, encrypted or not at all is not recorded; the plant assumes clear text.
