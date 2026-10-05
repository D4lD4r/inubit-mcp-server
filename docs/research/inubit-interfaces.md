# INUBIT Remote Interfaces — Research Notes (8.1, with 9.0 differences)

Source: official Virtimo documentation, retrieved 2026-10-01. Baseline in use: INUBIT 8.1.17 (verified 2026-10-01 against a non-production installation)
(latest documented 8.1 patch: 8.1.18; latest 9.x: 9.0.8). Everything below is as documented;
items marked **UNVERIFIED** must be confirmed against a real system before relying on them.

URL note: `https://docs.virtimo.net/en/inubit/<ver>/...` redirects to
`https://docs.virtimo.net/en/inubit-docs/<ver>/...`.

## 1. REST API (8.1)

### 1.1 General

- Base URL: `https://<server>:<port>/ibis/rest/<resource>` — one API, no separate admin API.
- Resource groups: Data Models, EDI Rules, Library, Monitoring, Process Documentation, Reports,
  Repository, Server, System Connectors (IS Connector), Tasks, Users & Roles, Webapps.
- Authentication: HTTP Basic per request; no login/session endpoint.
  - `?userType=processEngineUser` forces validation against INUBIT user management.
  - External frontend users (needed for Tasks, Webapps, Models, Reports): Basic or
    `X-AUTH-TOKEN: Bearer <token>` (token only with BPC/Keycloak as Process User Server);
    `?userType=processUser`.
  - Unauthenticated: `/healthcheck`, `/ready`.
  - Many write/delete endpoints require role `System Administrator`.
- Response format: per endpoint XML **or** JSON — no uniform format.
- Errors: 401 returns a Tomcat HTML page; 500 server error; 503 for all endpoints in maintenance
  mode; log endpoint returns 404 with details on invalid parameters (since 8.1.6).
- **No OpenAPI/Swagger in 8.1** (arrives in 9.0.2).

### 1.2 Endpoints (relative to `/ibis/rest`)

**Server / Monitoring / Library / Repository / Users**

| Method | Path | Auth | Parameters | Response |
|---|---|---|---|---|
| GET | `/healthcheck` | none | – | JSON `{status OK/ERROR, maintenancemode 0/1, timestamp}` |
| GET | `/ready` | none | – | JSON `{status READY/NOT READY, message}`; 200 / 503 |
| POST | `/ready/{on\|off}` | SysAdmin | – | JSON |
| GET | `/metrics` | auth + "inubit Metrics" license | `format=json\|xml\|prometheus` | memory, threads, blocking queue, maintenance, per-user/group status counts; Prometheus names `is_*` |
| GET | `/log/{logName}` | auth | `logName`: systemLog, queueLog, processLog, keyManagerLog, connectionLog, schedulerLog, webserviceManager, auditLog. Query: `startIndex`, `noOfItems`, `sorting` (`field:ASC\|DESC`,…), `filtering` (`field:value`; dates `lastDay\|last7Days\|last30Days`), `lang`, `format=json\|xml` | JSON/XML |
| POST | `/log/{logName}` | auth | XML `<logRequest>` with filtering (EQUAL, LIKE, LESSER, GREATER, BETWEEN) and sorting | JSON `{<logName>:{total,success,count,row[]}}` / XML |
| GET | `/system/info` | auth | `user` (cluster only) | XML `SystemInformationList` (version, memory, JDK, OS, tracing, …) |
| GET | `/license` | SysAdmin | – | XML |
| PUT | `/license` | SysAdmin | multipart `licenseFile` | JSON |
| GET | `/configurations` | auth | – | large JSON (doc example root key misspelled `SeverConfiguration`) |
| PUT/POST | `/configurations` | SysAdmin | JSON `{"ServerConfiguration":{…}}` | JSON |
| PUT | `/keys` | SysAdmin | multipart keystore + module/alias data | JSON |
| PUT | `/libraries` | SysAdmin | multipart `libraryFile`, `libraryType=Driver\|Plugin`, `libraryFileName` | JSON |
| DELETE | `/libraries/{name}?libraryType=` | SysAdmin | – | JSON |
| PUT/POST | `/edirules` | SysAdmin | multipart `ruleFile`, `ruleFileName` | JSON |
| DELETE | `/repository?repositoryFilePath=` | SysAdmin | – | JSON |
| GET | `/user/users?type=processEngineUser\|processUser` | auth | – | XML `UserList` |
| DELETE | `/user/users/{username}` | SysAdmin | `userReferences`, `userRepoReferences` | JSON |

**Data Models** (read supports Process Maps, BPDs, Business Object, Organization, System
diagrams, Technical Workflows)

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/model/models` | auth | optional `businessUser`, `user`, `views`, `deep`, `version`, `tag`; XML `ModelList` |
| GET | `/model/modelByName/{diagramName}` | auth | XML; encode spaces as `%20` |
| GET | `/model/nodeByModel/{modelName}/{nodeId}` | auth | XML |
| GET | `/model/nodeByName/{diagramName}/{moduleName}` | auth | XML; removed from 9.0 docs |
| GET | `/model/export/{diagramName}` | auth | ZIP stream (`.diagram.zip`) |
| PUT | `/model/import` | auth | XML `<Protocol>`; **UNVERIFIED** how the ZIP is transmitted |
| DELETE | `/diagrams/{id}`, `/diagrams/{id}/version/{v}` | SysAdmin | version delete since 8.1.6 |
| DELETE | `/modules?moduleName=`, `/modules/{id}`, `/modules/{id}/version/{v}` | SysAdmin | |

**Process Documentation** (GET, external frontend credentials)

`/process/processes?diagramType=…`, `/process/processes/{type}`, `/process/processes/{type}/{group}`,
`/process/processes/{type}/{group}/{name}` (PNG), `/process/search?searchText=` (JSON),
`/process/metadata/{diagramName}` (XML `IBISWorkflow`).

**Reports**: `GET /report/reportdata[/{id}]` (JSON), `GET /report/reports[/{folder}/{sub}]` (XML),
`PUT /report` (SysAdmin, multipart).

**System Connectors**: `GET /isconnector?workflowName&moduleName&type=validateWorkflow`;
`POST /isconnector` executes a remote workflow, but the payload is XStream-serialized CXF
attachments (Java-specific).

**Tasks** (`/task/...`) and **Webapps** (`/webapp/...`): portal/human-task oriented — task lists,
filters, claim/submit, ad-hoc processes. Low priority for an operations-focused MCP server.

### 1.3 REST gaps in 8.1

No documented REST endpoints for: Queue Manager process control (restart/kill/list running
processes), workflow activation/publishing, switching maintenance mode, user creation, tag
management, generic Technical Workflow start (except IS Connector or user-defined REST Connector
listeners under `/ibis/rest/rc/...`). → covered by the CLI.

## 2. CLI — StartCLI (8.1)

### 2.1 Invocation

- Scripts `startcli.sh` / `startcli.bat` in `<SUITE-INSTALL-DIR>/inubit/server/process_engine/bin`
  or `<SUITE-INSTALL-DIR>/inubit/client/bin/`. Part of the INUBIT installation; no standalone JAR
  documented. Bundled JDK: Temurin 17 (8.1), JDK 21 (9.0).
  → **The MCP server host needs a local INUBIT client installation for CLI features.**
- Requires role System Administrator or the right "CLI login access".
- Script mode: `startcli.sh [-u <user>] [-p <password>] --execCommand "<command> <opts> <args>" [URL]`
  — command in double quotes; exits after each call.
  - Default user `root`; default URL `https://<server>:<port>/ibis/servlet/IBISSoapServlet`.
  - Without `-p` the CLI prompts for the password interactively. No env-var or password-file
    option is documented. → `-p` exposes the password in the process list (conflicts with
    Constitution Principle II); **UNVERIFIED** whether the prompt can be fed via stdin.
- Direct option mode: `startcli [-u] [-p] -<option> [URL]` (e.g. `-who`, `-ne`, `-sv`).
- Transport: the CLI talks to the SOAP servlet `/ibis/servlet/IBISSoapServlet`, not to REST.
- TLS: `--trustStoreFilePath`, `--keyStoreFilePath` + `--keyStoreFilePwd`,
  `--disableHostNameVerification` (8.1.1+). Hostname verification on by default.
- Other options: `-h`, `-v`, `-a`, `-c/--check` (server alive), `--debug`,
  `--encryptString <s>`, `--backupFile`, `--backupRepository`; full option list truncated in docs.
- Output: human-readable text; only `ps -csv` gives CSV. Some commands print fixed phrases
  (e.g. "Operation ID is: <uuid>").
- **Exit codes: not documented** → must be determined empirically.
- Cache mode: support-only, dangerous — must never be exposed.

### 2.2 Commands

| Area | Commands |
|---|---|
| Users & groups | `create -cu/-cug/-cur …`, `finger <login>`, `user [--setGroup …]`, `passwd`, `delete --deleteUser/--deleteUserGroup` |
| Objects & processes | `delete --deleteWorkflow/--deleteModule`, `rename …`, `processErrorStart <pid>` (restart process in Error), `kill <pid>`, `processdelete error`, `tag --tagMove/--tagDelete/--tagSetActive/…` |
| Export/import/deploy | `export` (diagrams, metadata, repository, modules; `--exportTag`, `--includeHistory`), `import` (`--importWorkflow[Active]`, `--importModule`, `--returnProtocol`, …), `deploy` (staging: `--deployWorkflowArchive`, `--deploySystemName`, `--deployConfiguration`, merged properties/archive) |
| Runtime data | `process export|import|listBlocked|resume|deleteBlocked|validateExport` |
| Libraries & plug-ins | `library --upload/--list/--delete/--deleteAll -t driver|plugin`, `plugin --register/--deregister` |
| Configuration & ops | `threads`, `privilegedWorkflows`, `maintenance [on|off|-s|-eq]`, `triggerMidnightTask`, `dbconnectiontolerance`, `trace`, `debug`, `getBlockingQueueSize`, `validateMigrationBackup` (interactive only), `fetchXSLT1.0` |
| Diagnosis & monitoring | `ps -a|-c|-csv|-e|-q|-w|-l n|-u|-f 'workflow=… AND tag=…'` (columns NODE, UID, PID, PRIO, STATE, DATE, WORKFLOW, MODULE, TAG), direct options `-who -nwho -ne -nx -nw -nq -q -sv -pctmem -maintenanceState`, `uname`, `uptime`, `date`, `free`, `lls`, `msgsend/msglist/msgread/msgdel`, `license -td|-ts` |

Offline scripts (engine stopped, not StartCLI): `backup.sh`, `restore.sh`,
`start_process_engine.sh`, `stop_process_engine.sh`. The CLI has no start/stop command.

## 3. Other interfaces

- SOAP servlet `/ibis/servlet/IBISSoapServlet`: used by Workbench/CLI in 8.1, not a documented
  public API; removed in 9.0.
- JMX: MBeans `IBIS/Server`, `IBIS/DataStore.Binary`; no full reference.
- REST Connector listeners `/ibis/rest/rc/...`: user-defined, per module.

## 4. Differences in 9.0 relevant to this project

- SOAP API removed (9.0.0), SOAP servlet removed (9.0.2). CLI URL is just
  `https://host:port`, given as the last parameter. (9.0 CLI pages still show the old URL —
  trust the breaking-changes page.)
- Workbench/CLI auth via OAuth (Keycloak); Basic auth deprecated fallback; CLI supports
  browserless OAuth; internal user/password still works.
- CLI help requires explicit `-h/--help`.
- REST: `Accept` header mandatory (default XML; JSON on many endpoints; JSON to XML-only endpoint
  → 406, use `*/*`). Errors as JSON `{statusCode, message, path, timestamp}`.
- REST URL strictness: `/./`, `/../`, `//` rejected (400); trailing-slash handling changed in
  9.0.2 / 9.0.7.
- `userType=processUser` removed on several endpoints; `/user/users` takes `userType`.
- New: `GET /process/tree`, `/process/processes/{type}/{group}/{name}/history`,
  `/process/tags/{tag}`, diagram image by version, JSON output on process endpoints.
- OpenAPI (9.0.2+, opt-in via `-Dfeature.enable.openapi.support=true`, anonymous):
  `/ibis/swagger-ui/index.html`, `/ibis/v3/api-docs`.

## 5. Open points to verify against a real 8.1.17 system

1. CLI exit codes per command (success, auth failure, unknown command, server unreachable).
2. Whether StartCLI accepts the password via stdin when `-p` is omitted.
3. Wire format of `PUT /model/import`.
4. Full StartCLI option list (`startcli.sh -h`).
5. Actual JSON/XML shapes of `/log/processLog`, `/metrics`, `/system/info` (for fixtures).
6. Whether `/metrics` is licensed on a given installation.

## 6. Sources

- REST overview: https://docs.virtimo.net/en/inubit-docs/8.1/rest-api/rest-api.html
- REST auth: https://docs.virtimo.net/en/inubit-docs/8.1/rest-api/rest-authentication.html
- REST endpoint pages: `https://docs.virtimo.net/en/inubit-docs/8.1/rest-api-<group>/<page>.html`
- CLI: https://docs.virtimo.net/en/inubit-docs/8.1/administration-guide/command-line-interface.html
  and subpages under `.../8.1/administration-guide/cli/`
- Backup/restore: https://docs.virtimo.net/en/inubit-docs/8.1/administration-guide/backup-and-restore.html
- 8.1 changelog / breaking changes: https://docs.virtimo.net/en/inubit-docs/8.1/changelog.html,
  https://docs.virtimo.net/en/inubit-docs/8.1/breaking-changes.html
- 9.0: https://docs.virtimo.net/en/inubit-docs/9.0/rest-api/rest-api.html,
  https://docs.virtimo.net/en/inubit-docs/9.0/administration-guide/command-line-interface.html,
  https://docs.virtimo.net/en/inubit-docs/9.0/breaking-changes.html,
  https://docs.virtimo.net/en/inubit-docs/9.0/changelog.html
