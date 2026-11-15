# Feature Specification: Full Coverage of the StartCLI and REST Interfaces

**Feature Branch**: `022-full-interface-coverage`

**Created**: 2026-11-12

**Status**: Draft

## Clarifications

### Session 2026-11-12

- The owner decided: "all" means the functions for operation and administration. The portal
  functions (Tasks, Webapps, Reports, process documentation) are not part of the feature; they
  are entered in the coverage list as excluded.
- The owner decided: this is one feature that delivers all areas, story by story in the order of
  the priorities.
- The owner decided: a new password or key password comes from a named entry of a configured
  secret source. A tool takes the name of the entry, never the value.
- Decisions taken by the creator (owner may override): every user story is mergeable and
  releasable on its own, through its own pull request from the feature branch, so that reading is
  available before the changes are finished; the administrative areas each need their own opt-in
  per stage in addition to the write enablement (FR-016); a command that can take its secret only
  on a command line visible to other processes is excluded (FR-022).

### Spec review round 1, 2026-11-12

Decisions taken by the creator (owner may override):

- Every entry of the Background table belongs to exactly one user story; entries no story covered
  were assigned to one.
- The tool limit is a total of 10 new tools; each serves exactly one user story (FR-006).
- Where REST and StartCLI offer the same function for a version, REST is used (constitution,
  Principle V); facts that `get_health` already returns are not read again (FR-002).
- A bulk change re-reads its set right before sending and is refused with a new preview if the
  set changed (FR-017).
- The run-time data export does not change the source; it is governed like `export_archives`
  plus the run-time data opt-in, not by the write enablement (FR-016a). If a check on a test
  system shows that it changes the source, it becomes a write under FR-014.
- Filters are structured fields, never a raw expression (FR-020).
- Upload files come only from folders listed in a new profile setting, empty by default, with a
  size limit per file type (FR-021).
- `PUT /keys` deploys keys to a module keystore, not to the server's own TLS certificate; the
  certificate pin of feature 006 is not touched by this feature (FR-019).
- The administrative areas always use the server-side preview, even where a stage uses
  `write.confirmation: CLIENT` (FR-015).

Owner's answers after review round 1:

- Tags on operational stages get a tool of their own with write enablement and production opt-in;
  every tag operation names an explicit diagram group or repository path, and the owner-wide
  forms are excluded (FR-019a). `tag_artifacts` stays as it is.
- File-based user, group and role imports are refused when the file contains the server's login
  user, its group or its role; a directory synchronisation is allowed only when the login user is
  an internal user of INUBIT (FR-019b).

### Spec review round 2, 2026-11-12

Facts from the project's own spike (`docs/research/spike-development-deployment.md`) applied to
the owner's tag decision; decisions taken by the creator (owner may override):

- `--tagSetActive` sets the *active tag of a user*, not the active version of a workflow; the
  tag scenarios say so. `--tagDiagram` is ignored by INUBIT, `--tagWorkflowGroupExclude` is an
  owner-wide form, and tag removal (`--tagDelete`, `--tagRemove`) was seen working only
  owner-wide: the first two are excluded; removal is offered only if a check on a test system
  shows that INUBIT limits it to the named diagram group or repository path (FR-019a).
- All tag operations belong to User Story 4 and one tool, protected as the owner decided (write
  enablement and production opt-in), without an administration opt-in.
- Every write entry names its protection in the Background table; the administration opt-ins
  have a fixed list of entries (FR-016).
- Rename, metadata import and keystore deployment take a backup first (FR-018); EDI rule files
  are repository files and are backed up as such.

### Planning, 2026-11-12

Facts from the vendor pages, found while planning; decisions taken by the creator (owner may
override), details in `research.md` (D-3, D-4, D-5):

- `process export` blocks the exported processes on the source until they are resumed or deleted
  ("Processes, whose run time data are marked for exporting are blocked"). Under FR-016a's own
  rule the export is therefore a change under FR-014 with the run-time data opt-in.
- The run-time data archive is written on the INUBIT host (its `ibis_data/backup` folder), and
  `process import` and `process validateExport` read an archive by a path on the INUBIT host. The
  server cannot move a file between INUBIT hosts; the archive is named by its file name in the
  backup folder of the server, and the operator moves it. Upload folders do not apply to it.
- The export filter works on the user-defined Queue Manager columns (`<column>=<value>`,
  `<column> in (…)`, joined by `AND`), not on workflow and tag; `inFile` and
  `--filterProcessIds` read a file on the INUBIT host and are not offered.
- `lls` lists the local directory of the StartCLI client, which is the MCP server's own machine,
  not the server's log files; it is excluded.

### Live recording T039, 2026-11-14

Facts from the 8.1 recordings of User Story 3 and the owner's decisions, details in `research.md`
("Live recording T039", "Decided after T039", D-21):

- The owner decided: the user-defined Queue Manager columns of `ps -u` are shown by the process
  search, one column per call, from the REST rows it already reads (`covered`); the export of an
  owner without some groups holds technical workflows only; the 9.0 part of User Story 3 comes
  in a follow-up pull request; reading a workflow's metadata and the IS Connector check are not
  built until a check on a test system records a working form; the library list is built from
  the empty answer and says that its row format is not verified.
- The owner decided: if those two checks are not settled before the end of the feature, their
  entries are excluded with the FR-002 reasons "cannot be built from the documentation: answer
  form not recordable" (workflow metadata) and "cannot be built from the documentation:
  authentication not documented" (IS Connector check), so that no entry stays planned
  (SC-001).
- `GET /model/nodeByName` and `fetchXSLT1.0` do not exist on 8.1.17; they are excluded on 8.1.
  The module detail is XML; `export --exportMetadata` exports the metadata registry.

**Input**: User description: "I would like to add all missing methods of StartCLI and REST, both
reading and writing, to the MCP."

## Background

A comparison of the vendor documentation for INUBIT 8.1 and 9.0 (14 StartCLI pages, the REST API
reference) with the commands and endpoints the server sends today (2026-11-12, summarised in
`docs/backlog.md`, "Further read access to INUBIT") showed that the server uses 8 REST endpoints
and 6 StartCLI commands. Everything else that INUBIT documents is not reachable through any tool.
An operator who needs one of these functions still has to open the Workbench or a shell with
StartCLI.

The missing functions for operation and administration fall into five areas. The table is the
scope of this feature; commands are named as the vendor documents them, and each row belongs to
exactly one user story.

| Area | Kind | Entries | Story | Protection |
|---|---|---|---|---|
| A. Server state and settings | read | `license -td\|-ts`, `GET /license`, `threads showNumberOfWorkflowThreads`, `privilegedWorkflows showThreadsPercentage\|showWorkflowList`, `dbconnectiontolerance show`, `getBlockingQueueSize`, `GET /configurations`, `date`, `-sv`, `-pctmem`, `-maintenanceState` | US1 | none (read) |
| A. Server state and settings | write | `maintenance on\|off [-eq true\|false]`, `POST /ready/{on\|off}`, `trace on\|off`, `trace -l <level>`, `debug on\|off`, `triggerMidnightTask` | US4 | write enablement |
| A. Server state and settings | write | `threads setNumberOfWorkflowThreads`, setting the share of threads for privileged workflows, `dbconnectiontolerance set`, `PUT\|POST /configurations`, `PUT /license` | US6 | write enablement + settings opt-in |
| B. Users and groups | read | `finger <login>`, `user <name>`, `-who`, `-nwho`, `GET /user/users`, `msglist`, `msgread` | US2 | none (read) |
| B. Users and groups | write | `create -cu\|-cug\|-cur`, `user --setGroup\|--setAdditionalGroups\|--addAdditionalGroups\|--removeAdditionalGroups`, `passwd`, `delete --deleteUser\|--deleteUserGroup`, `DELETE /user/users/{name}`, `import --importUserGroup\|--overwriteUserRoles\|--importLDAPDetails\|--syncLDAPUsers`, `msgsend`, `msgdel` | US7 | write enablement + users opt-in |
| C. Libraries, plug-ins, EDI rules, keys | read | `library --list` | US3 | none (read) |
| C. Libraries, plug-ins, EDI rules, keys | write | `library --upload\|--delete\|--deleteAll`, `plugin --register\|--deregister`, `PUT /libraries`, `DELETE /libraries/{name}`, `PUT\|POST /edirules`, `PUT /keys` | US6 | write enablement + libraries opt-in |
| D. Processes and run-time data | read | `ps -f '<filter>'`, `ps -u`, `-ne`, `-nx`, `-nw`, `-nq` on 8.1 and 9.0 | US3 | none (read) |
| D. Processes and run-time data | read | `process listBlocked`, `process validateExport` | US5 | run-time data opt-in (FR-016a) |
| D. Processes and run-time data | write | `processdelete error` | US4 | write enablement |
| D. Processes and run-time data | write | `process export [--excludeModel] [--filterExpression]`, `process import\|resume\|deleteBlocked` | US5 | write enablement + run-time data opt-in |
| E. Diagrams, modules, repository, tags | read | `GET /model/nodeByModel/…`, `GET /model/nodeByName/…` (documented for 8.1 only; absent on 8.1.17, T039), `export --exportMetadata\|--exportMetadataNames`, `export --exportWorkflowGroupExclude`, `GET /process/metadata/{name}`, `GET /isconnector?…type=validateWorkflow`, `fetchXSLT1.0` (absent on 8.1.17, T039) | US3 | none (read); files under the export rules (FR-012) |
| E. Diagrams, modules, repository, tags | write | `tag --tagSetActive\|--tagRemoveActive`, `tag --tagDelete\|--tagRemove` with `--tagWorkflowGroup`/`--tagWorkflowType` or `--tagRepositoryPath` (removal only if FR-019a's check allows it) | US4 | write enablement |
| E. Diagrams, modules, repository, tags | write | `delete --deleteWorkflow\|--deleteModule`, `DELETE /diagrams/…`, `DELETE /modules/…`, `DELETE /repository`, `rename --renameWorkflow\|--renameModule\|--renameMetadata\|--renameFile\|--renameDirectory`, `import --importMetadata`, `POST /isconnector` | US6 | write enablement + objects opt-in |
| Start options | write | `--backupFile`, `--backupRepository` (documented by name only) | US6 | write enablement + settings opt-in |


Four groups of documented functions get no new access:

- **Already covered by a tool with the same effect**: `GET /log/{name}` (the `POST` form is used),
  the `deploy` command and `PUT /model/import` (`deploy_release`, `import_artifacts`), `uname`,
  `uptime` and `free` on 8.1 and 9.0 (`get_health` reads the same facts over REST), reading a
  repository resource (the only documented endpoint is the portal's form proxy under `/task/…`;
  repository files are read with `export_archives`), setting or moving a tag (`tag --tagMove`:
  `tag_artifacts` on development stages, `deploy_release` on the stage it deploys to; the tag
  tool of this feature does not set tags).
- **Not usable from a server process**: the cache mode (the vendor reserves it for its support
  and calls it dangerous), `validateMigrationBackup` (interactive only), `--encryptString` (takes
  a secret on the command line and prints the result), the offline scripts `backup.sh` and
  `restore.sh` (they need a stopped process engine on the INUBIT host), `lls` (lists the local
  directory of the StartCLI client, not of the server), the export filter forms `inFile` and
  `--filterProcessIds` (they read a file on the INUBIT host).
- **Broken on the vendor side**: the log `processLog` fails on 8.1.17.
- **Tag forms that act on the whole owner** (owner's decision, see Clarifications):
  `--tagDiagram` (ignored by INUBIT, so the command tags every diagram of the owner; seen in the
  project's spike), `--tagWorkflowGroupExclude` (all groups but some), and any `tag` call without
  a diagram group or repository path.
- **Portal functions** (owner's decision, see Clarifications): the process documentation
  (`/process/processes/…`, `/process/search`, on 9.0 also `/process/tree`, `…/history`,
  `/process/tags/{tag}`), the reports (`/report/…`) and all endpoints under `/task/…` and
  `/webapp/…`. They serve business users of the portal and need portal users instead of the
  administration login. `GET /process/metadata/{name}` stays in area E because it describes a
  workflow for operation.

None of the missing commands has been run against a real system for this feature. Their output
formats, exit codes and required roles are known from the documentation only.

The project constitution sets the frame: write access is off by default and protected on
production (Principle I), secrets never pass through tool parameters or results (Principle II),
tools are built around tasks and are not a 1:1 mirror of commands (Principle IV), results are
bounded (Principle VI), every change is audited (Principle VII).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Read the server's state and settings (Priority: P1)

An operator (or an agent working for them) wants to know how a server is set up without opening
the Workbench: when the license expires and how many threads it allows, how many workflow threads
are configured and how many are reserved for privileged workflows, which workflows are
privileged, the tolerance for database connection errors, the size of the blocking queue, and the
server configuration; and the small facts StartCLI shows about the server (server time, version
string, memory share, maintenance state).

**Why this priority**: read only, no risk, and these are the facts asked for most often in support
cases. It needs no write enablement.

**Independent Test**: With recorded StartCLI outputs and REST responses, ask for the settings of
one server: the result contains each fact as a named field with its source. Testable offline.

**Acceptance Scenarios**:

1. **Given** a server with a configured StartCLI client, **When** the settings of that server are
   requested, **Then** the result contains the license expiry date, the licensed threads, the
   configured workflow threads, the share and list of privileged workflows, the database
   connection tolerance and the blocking queue size, each as a typed field.
2. **Given** the server configuration contains passwords or other secret values, **When** the
   configuration is requested, **Then** every secret value is replaced by the mask token and the
   result says how many values were masked.
3. **Given** a server configuration larger than the result limit, **When** it is requested without
   a section name, **Then** the result lists the section names only; a call that names a section
   returns that section within the limit.
4. **Given** the login user lacks the role an item needs, **When** the settings are requested,
   **Then** the other items are returned and the missing one is reported with the role it needs.
5. **Given** a stage id as target, **When** the settings are requested, **Then** the result
   contains one entry per server of that stage.
6. **Given** a server with a configured StartCLI client, **When** the settings are requested,
   **Then** the result also contains the server time, the version string and the memory share in
   use; `-maintenanceState` is covered by the maintenance mode `get_health` reads (AS7).
7. **Given** `get_health` already returns the licensed threads, the maximum blocking queue size
   and the maintenance mode over REST, **When** the settings are requested, **Then** these facts
   come from the same REST source and are not read a second time over StartCLI.

---

### User Story 2 - Read users, groups and logins (Priority: P1)

An operator wants to know which users exist on a server, which groups and roles one user has, and
who is logged in right now, for example to check why a login fails or before a maintenance
window.

**Why this priority**: read only and frequently needed; it is the base for the user changes in
User Story 7.

**Independent Test**: With recorded outputs, list the users of a server, read one user and list
the logins: the results are bounded pages with stable fields. Testable offline.

**Acceptance Scenarios**:

1. **Given** a server with 500 users, **When** the users are listed, **Then** the result is a page
   with the login name, group, additional groups and roles of each user and a cursor for the next
   page.
2. **Given** a login name, **When** that user is read, **Then** the result contains the user's
   group, additional groups, roles and the account state INUBIT reports (for example locked or
   inactive; absent if INUBIT reports none), plus the personal fields INUBIT reports for that
   user (for example the e-mail address); a password hash or any other secret field of the INUBIT
   answer is never part of the result.
3. **Given** a login name that does not exist, **When** that user is read, **Then** the result is
   an error that says so and names the listing as the next step.
4. **Given** three users are logged in, **When** the logins are requested, **Then** the result
   names them with the login time INUBIT reports.
5. **Given** a listing of users, **When** it is returned, **Then** it contains no e-mail address
   or other personal field of any user.
6. **Given** messages for the login user on the server, **When** the messages are listed, **Then**
   the result is a bounded page with id, sender, time and subject; reading one message by id
   returns its text, masked and bounded.

---

### User Story 3 - Read processes, run-time data and model details (Priority: P2)

An operator wants to filter the running processes of an 8.1 or 9.0 server by workflow and tag as
the Queue Manager does and see one of its user-defined columns, count them by state, read one
module of a diagram without exporting the whole diagram, export the metadata registry, export
diagrams while leaving out some groups, and list the installed drivers and plug-ins. Reading a
workflow's metadata and checking an IS Connector wait for a check on a test system; the list of
XSLT 1.0 usages does not exist on 8.1.17 (T039).

**Why this priority**: read only, but each item serves a narrower question than User Stories 1
and 2, and several overlap with existing tools.

**Independent Test**: With recorded outputs, each item returns its documented shape; an item that
an existing tool can answer is reached through that tool. Testable offline.

**Acceptance Scenarios**:

1. **Given** an 8.1 server with processes of several workflows, **When** processes are searched
   with a workflow name and a tag, **Then** only the matching processes are returned, with the
   same fields as the existing process search.
2. **Given** a diagram and the id of one of its modules, **When** that module is read, **Then** the
   result contains the module's type and properties with secret values masked, by secret name and
   by the property type `Password`; an unknown id gives an error that says the module was not
   found (T039).
3. **Given** a server with installed drivers and plug-ins, **When** the libraries are listed,
   **Then** the result names each file with its type; without libraries the list is empty. Until
   a listed library is recorded, each line is read as one file name and the result says that the
   row format is not verified (T039).
4. **Given** an 8.1 server, **When** the XSLT 1.0 usage is asked for, **Then** no tool offers it,
   and the coverage list names `fetchXSLT1.0` excluded on 8.1 because the command is not found on
   INUBIT 8.1.17; 9.0 is decided by its recording (T039).
5. **Given** an 8.1 or 9.0 server, **When** the process counts are requested, **Then** the result
   names the number of processes in error, executing, waiting and queued state; counts that the
   existing process search or `get_health` already returns come from there (FR-002). The process
   search takes one user-defined column index (1–5) and shows that column for each process from
   the same REST source it already reads; `ps -u` is covered by the process search (T039).
6. **Given** a server's metadata registry, **When** it is exported, whole or limited to named
   metadata entries, **Then** the archive is written as a new file into a folder the caller names
   (FR-012). Reading the metadata of one workflow is not offered: its entry stays planned until
   its check records a positive answer, otherwise it is excluded at the end of the feature
   (Clarifications, T039).
7. **Given** an owner with five diagram groups, **When** diagrams are exported while two groups
   are left out, **Then** the archive holds the technical workflows of the other three groups only
   and is written under the rules of `export_archives`, including `archives.productionOptIn`
   (FR-012; technical workflows only, T039).
8. **Given** a workflow with an IS Connector module, **When** the connector is checked, **Then**
   the result says whether INUBIT considers the workflow valid for remote calls, with INUBIT's
   message. Not offered until the authentication of `/isconnector` is known (INUBIT 8.1.17
   refuses the profile's REST login there, `401`): the entry stays planned until its check,
   otherwise it is excluded at the end of the feature (Clarifications, T039).

---

### User Story 4 - Operate a server (Priority: P2)

An operator prepares and finishes a maintenance window through the agent: switch the maintenance
mode and the readiness state, delete the processes that are in error state, set or remove the
active tag of a user and remove a tag from a diagram group or repository path, change the trace
level or switch tracing and debug output on for a diagnosis and back off, trigger the midnight
task.

As the vendor documents them: in **maintenance mode** the process engine executes no processes
(queued processes only if `-eq true` is set) and the REST API answers every request with "service
unavailable"; the **readiness state** is what the readiness probe reports to a load balancer
(READY or NOT READY) without stopping the engine, and it is always NOT READY while maintenance
mode is on. Both are offered; `-eq` is offered as an optional part of switching maintenance mode
on. The **active tag** of a user (`--tagSetActive`) names the tag whose versions INUBIT uses for
that user; it does not activate or deactivate a workflow. The **midnight task** is the daily task
INUBIT otherwise starts at its configured start time; the vendor documents its settings together
with the system log configuration and does not document what the task does, so the tool
description says that; triggering it runs it at once.

**Why this priority**: these are the changes operators make routinely and they use the write
protection that already exists. Most are reversible; deleting processes in error state and
removing a tag are not, and are previewed and backed up (FR-017, FR-018).

**Independent Test**: With a fake StartCLI and REST server, each change is refused by default,
shows a preview when enabled and is executed only with the confirmation; each step leaves an
audit record. Testable offline.

**Acceptance Scenarios**:

1. **Given** a stage without write enablement, **When** the maintenance mode is to be switched on,
   **Then** the call is refused with the code of the existing write tools and the next step names
   the setting.
2. **Given** a stage with write enablement and `write.confirmation: SERVER`, **When** the
   maintenance mode is to be switched on, **Then** the first call returns a preview (server, current state, requested state, number of
   running and queued processes) and a confirmation code; the second call with the code switches
   the mode and returns the state read afterwards.
3. **Given** a production stage without the production opt-in, **When** any change of this story
   is requested, **Then** it is refused, whatever the write enablement says.
4. **Given** 40 processes in error state, **When** their deletion is requested, **Then** the
   preview names the number 40 and the workflows concerned; the execution re-reads the set right
   before sending and reports the number INUBIT names as deleted (FR-017).
5. **Given** the trace level is `error`, **When** it is set to `debug`, **Then** the result names
   the level before and after, and the tool description says that the level stays until it is
   set back.
6. **Given** the maintenance mode is on, **When** it is to be switched off, **Then** the change
   works although INUBIT answers every other REST request with "service unavailable".
7. **Given** maintenance mode is to be switched on with queued processes still executing,
   **When** the preview is shown, **Then** it names the `-eq` value that will be sent and what it
   means.
8. **Given** debug output is off, **When** it is switched on, **Then** the result names the state
   before and after, and the description says that it stays on until it is switched off.
9. **Given** the readiness state is READY, **When** it is to be set to NOT READY, **Then** the
   preview names the current and the requested state and says that readiness stays NOT READY
   while maintenance mode is on; the result re-reads the state.
10. **Given** tracing is off, **When** it is switched on, **Then** the result names the state
    before and after, as for debug output (AS8).
11. **Given** the midnight task is to be triggered, **When** the preview is shown, **Then** it says
   that the daily task runs at once instead of at its configured start time, and names that start
   time if INUBIT reports it; the result names INUBIT's answer and the time it was sent.
12. **Given** a test stage with write enablement, **When** the active tag of a user is set,
    **Then** the call names the user and the tag, the preview names the user's current active
    tag and the new one, and the result re-reads it (FR-019a).
13. **Given** a call to remove a tag without a diagram group or repository path, **When** it is
    made, **Then** it is refused with `INVALID_INPUT` before anything is read.
14. **Given** the check on a test system showed that INUBIT limits tag removal to the named
    diagram group, **When** a tag is removed from one group, **Then** the preview names every
    object and version in that group that carries the tag, the backup holds that list (FR-018),
    and the same tag in other groups stays (the result re-reads both). If the check showed that
    removal acts on the whole owner, tag removal is not offered and its entries are excluded with
    that reason.

---

### User Story 5 - Transfer and repair run-time data (Priority: P3)

An operator moves the run-time data of running processes from one system to another (export on
the source, import on the target), checks an export archive, lists the processes the import left
blocked, and resumes or deletes them.

**Why this priority**: rare (migrations), with large files and a multi-step flow, but there is no
other way than the CLI.

The archive stays on the INUBIT hosts: the export writes it into the backup folder of the source
server, and the import and the validation read it from the backup folder of the server they run
on. Moving the file between the hosts is the operator's step; the server names the file and the
host.

**Independent Test**: With a fake StartCLI, an export names the archive file and returns the
operation id and the host; an import names an archive file of the target's backup folder and
returns the operation id; every change needs the confirmation. Testable offline.

**Acceptance Scenarios**:

1. **Given** a source server with write enablement and the run-time data opt-in, **When** the
   run-time data is exported with a file name and a filter given as structured conditions on the
   user-defined Queue Manager columns (equals one value or is one of several values, all
   conditions joined), **Then** the preview says that the matching processes are blocked on the
   source until they are resumed or deleted; after the confirmation the result names the
   archive's path on the INUBIT host, the host and the operation id.
2. **Given** a production source without the production opt-in, **When** the export is requested,
   **Then** it is refused as every change is (FR-014).
3. **Given** an archive file name in the backup folder of a server, **When** it is validated,
   **Then** the result says whether INUBIT accepts it and names the problems INUBIT reports,
   without importing it.
4. **Given** an archive file name in the backup folder of a target server with write enablement
   and the run-time data opt-in, **When** the import is confirmed, **Then** the result names the
   operation id and the number of blocked processes.
5. **Given** a stage with the run-time data opt-in and the operation id of an earlier run-time
   data import on that server, **When** the
   blocked processes are listed, **Then** the result is a bounded page of those processes.
6. **Given** that operation id, **When** the blocked processes are to be resumed, **Then** the
   preview names their number; the execution reports how many were resumed and re-reads the
   list.
7. **Given** that operation id, **When** the blocked processes are to be deleted, **Then** the
   preview names their number and the execution reports how many were deleted.

---

### User Story 6 - Administer objects, libraries and settings (Priority: P3)

An administrator removes what is no longer needed and changes how a server is set up: delete or
rename a workflow, a module or a repository file, import metadata, upload or delete
a driver or a plug-in, register a plug-in, upload EDI rules, deploy keys to a module keystore,
start a workflow through an IS Connector, change the number of workflow threads, the share of
threads for privileged workflows, the database connection tolerance or a configuration value,
upload a new license.

**Why this priority**: these changes are rare and mostly irreversible. A mistake stops business
processes, so they need the strictest protection of this feature.

**Independent Test**: With fakes, each change is refused unless its area is enabled for the
stage, previews what it will change, and where INUBIT can export the object a backup exists
before the deletion. Testable offline.

**Acceptance Scenarios**:

1. **Given** a stage with write enablement but without the administration opt-in for objects,
   **When** a workflow is to be deleted, **Then** the call is refused and the next step names the
   opt-in.
2. **Given** the opt-in is set, **When** a workflow is to be deleted, **Then** the preview names
   the workflow, its owner, its versions and tags and whether it is active; the execution first
   stores an export of the workflow in the backup location and then deletes it; the result names
   the backup.
3. **Given** a library file in an allowed local folder, **When** it is uploaded as a driver,
   **Then** the result names the file, its size and checksum, and says whether a restart of the
   process engine is needed for it to take effect.
4. **Given** all libraries of one type are to be deleted, **When** the preview is requested,
   **Then** it lists every file that will be deleted; the confirmation is valid only for that
   list. Until the format of the library list is recorded with libraries present, the preview
   names only their number and says that the list format is not verified, and the confirmation
   is valid only for that number (FR-017).
5. **Given** the number of workflow threads is 20 and the license allows 50, **When** it is to be
   set to 80, **Then** the call is refused before any change, with the licensed number in the
   message.
6. **Given** a configuration change, **When** it is previewed, **Then** the preview shows the
   current and the new value of each changed setting, with secret values masked on both sides.
7. **Given** a license file in an allowed upload folder, **When** it is uploaded, **Then** the
   preview shows the current expiry date and licensed threads and the file's name, size and
   checksum; the result shows the expiry date and licensed threads read after the upload.
8. **Given** a keystore file in an allowed upload folder and a named entry of a secret source for
   its private key password, **When** it is deployed to a module, **Then** the preview names the
   module, its owner and the alias; the password is passed as FR-022 requires.
9. **Given** a workflow to be renamed, **When** the preview is shown, **Then** it names the old
   and the new name (checked against INUBIT's documented characters: letters, digits, `-`, `_`,
   `.` and space) and the objects that reference it as far as the server can read them; the
   execution first stores a backup of the object (FR-018). The tool description says whether
   INUBIT updates references, as a check on a test system showed. The same holds for a module,
   metadata, a repository file or a repository directory.
10. **Given** a workflow with an IS Connector and an input file in an allowed upload folder,
    **When** the workflow is started through the connector, **Then** the result names INUBIT's
    answer; if a check on a test system shows the call cannot be built from the documentation,
    the entry is excluded with that reason (FR-002).
11. **Given** the share of threads for privileged workflows is to be set to 120 %, **When** the
    call is made, **Then** it is refused before any change.
12. **Given** a metadata import file in an allowed upload folder, **When** it is previewed,
    **Then** the preview lists the metadata it replaces; the execution first stores an export of
    that metadata (FR-018).
13. **Given** the database connection tolerance (fault threshold, drop rate, drop interval),
    **When** a new value is previewed, **Then** the preview shows current and new values and says
    that INUBIT shuts the node down when the fault threshold is exceeded within the drop
    interval.
14. **Given** an uploaded plug-in library, **When** it is registered (with the names and classes
    the vendor documents) or deregistered, **Then** the preview names the plug-in and the result
    re-reads the library list.
15. **Given** an EDI rule file in an allowed upload folder, **When** it is deployed, **Then** the
    preview names the repository path INUBIT writes it to and whether a file with that name
    already exists there; an existing file is backed up first (FR-018).
16. **Given** a repository file or directory to delete, **When** the preview is shown, **Then** it
    names the path and, for a directory, the number of files in it; a backup is stored first.
17. **Given** a module to delete, **When** the preview is shown, **Then** it names the workflows
    that use the module as far as the server can read them; a backup is stored first.

---

### User Story 7 - Administer users and groups (Priority: P4)

An administrator creates a user, a group or a role, changes the groups of a user, sets a
password, deletes a user or a group, imports users, groups or roles from a file or a directory
service, or sends and deletes messages to logged-in users.

**Why this priority**: user administration is done seldom and usually through the Workbench or a
directory service; it touches credentials, which the constitution keeps out of the conversation.

**Independent Test**: With fakes, a user is created and deleted with preview and confirmation; no
call accepts or returns a password; the server's own login user cannot be changed. Testable
offline.

**Acceptance Scenarios**:

1. **Given** the opt-in for user administration, **When** a user is created, **Then** the preview
   names login, group and roles, and the result confirms the creation without any password. The
   call may name a secret-source entry for the initial password (FR-022); without one, the user
   is created as INUBIT creates it without `--password`, which a check on a test system records
   and the tool description names.
2. **Given** a secret source that holds an entry for the new password, **When** the password of
   a user is set with the name of that entry, **Then** the server reads the value from the secret
   source and passes it to INUBIT; the value never appears in the call, the result, the audit
   record or the process list, and the audit record names the entry.
3. **Given** the named entry does not exist or cannot be read, **When** the password is to be
   set, **Then** the call is refused before any call to INUBIT and the error names the entry and
   the secret source, not a value.
4. **Given** the login user of the MCP server itself, **When** it is to be deleted, moved to
   another group or given a new password, **Then** the call is refused.
5. **Given** a user who owns workflows, **When** the user is to be deleted, **Then** the call
   names for each of INUBIT's two documented choices (remove the user's references in diagrams
   and modules; remove them in the repository) yes or no, with no default; the preview names the number of owned workflows
   and repository entries and which of them will be cleaned up. INUBIT answers success even for
   an unknown user, so the result comes from re-reading the user list (FR-024).
6. **Given** a user, group or role import file that contains the server's login user, its group
   or its role, **When** the import is requested, **Then** it is refused before any call to
   INUBIT and the error names which of the three the file contains (FR-019b).
7. **Given** the server's login user is managed by a directory service, **When** a directory
   synchronisation is requested, **Then** it is refused; **Given** the login user is an internal
   INUBIT user, **Then** the synchronisation is previewed and executed as other changes.
8. **Given** a message text, **When** it is sent to the logged-in users, **Then** the preview names
   the recipients and the text; deleting a message names it by id. A text longer than 1,000
   characters or with control characters is refused (FR-020).
9. **Given** a new user group with a parent group, or a new role based on an existing role,
   **When** it is created, **Then** the preview names the parent group or base role and the
   result re-reads the group or role list.
10. **Given** a user with additional groups, **When** an additional group is added or removed,
    **Then** the preview names the groups before and after; removing the login user's own groups
    is refused (FR-019).

### Edge Cases

- A command exists in 8.1 but not in 9.0 or the other way round (`/model/nodeByName` is gone
  from the 9.0 documentation), or is documented but absent on a recorded server
  (`/model/nodeByName` and `fetchXSLT1.0` on 8.1.17, T039): the capability is offered only for
  the versions that have it; an absent entry is excluded for that version with the reason found,
  and a call for another version is refused with the existing version error.
- INUBIT 6.1: no new write access. New read access on 6.1 is not part of this feature.
- The login user lacks the role "System Administrator" or the right "CLI login access": the
  refusal names the missing role and is not reported as a login failure.
- A change is confirmed after the state shown in the preview has changed (another person deleted
  the object, the list of libraries differs): the execution is refused and a new preview is
  needed.
- Maintenance mode is on: REST calls fail with "service unavailable"; read results say that the
  server is in maintenance mode, and switching it off still works.
- A bulk change finds nothing to do (no process in error state): the result says so, nothing is
  executed, and the call is still audited.
- A change needs a restart of the process engine to take effect (libraries, some configuration
  values): the result says so; the server cannot restart the engine.
- The server's own access depends on the change (own user, own group, the role that grants CLI
  access): the change is refused (FR-019).
- A file to upload is missing, outside the allowed upload folders, a symbolic link, not a regular
  file or larger than the limit for its type: refused before any call to INUBIT (FR-021).
- A command prints a text the parser does not know (new patch level): the result is an error that
  says the output was not understood, with a masked and bounded excerpt, never a guessed value.
- A stage id is given for a change: refused; a change always names exactly one server.
- A second state-changing call against the same server and the same object (or the same bulk
  set) while one runs: refused with `PRECONDITION_FAILED`, as for `restart_process`.
- `POST /isconnector` starts a workflow with a payload in a vendor-internal format: if a check on
  a test system shows that the payload cannot be built from the documentation, the entry goes to
  the exclusion list with that reason.

## Requirements *(mandatory)*

### Functional Requirements

**Coverage**

- **FR-001**: The project MUST keep a coverage list of the StartCLI commands and REST endpoints
  documented on the vendor's pages "Command Line Interface" (administration guide, with its
  subpages) and "REST API" (with every endpoint page) for INUBIT 8.1 and 9.0, as retrieved on
  2026-11-12. An entry is one StartCLI command with one option (or one set of mutually exclusive
  values of an option, such as `on|off`), or one HTTP method with one path. Each entry has one
  state per INUBIT version: reachable through a named tool, covered by a named tool with the same
  effect, planned (with the number of the user story), not documented for that version, or
  excluded with a stated reason.
- **FR-002**: When the feature is complete, every entry of the Background table MUST be reachable
  through a tool, or covered by one, or excluded. An exclusion reason comes from the documentation
  (the command is interactive only, the portal functions) or from a check on a test system (the
  command does not exist in that version, cannot be built from the documentation, or cannot be
  made safe). Where REST and StartCLI offer the same function for a version, the capability uses
  REST and the StartCLI entry gets the state "covered by" that tool. Facts that `get_health`
  already returns are not fetched again by another channel.
- **FR-003**: A test MUST fail when a command or endpoint the server can send is missing from the
  coverage list, and when an entry names a tool that does not exist.
- **FR-004**: The feature MUST be delivered story by story in the order of the priorities (User
  Story 1 first). Each user story MUST be usable, mergeable and releasable without the later
  ones; the coverage list shows at every point which entries are reachable and which are still
  open.

**Tool design**

- **FR-005**: New capabilities MUST be grouped by the task they serve, not offered as one tool per
  command. Where an existing tool serves the same task, it is extended (for example process
  search for the `ps` filters, the health view for license and threads) instead of adding a tool.
  An extended tool keeps the protection it has; a capability that needs a different protection
  (for example a change on an operational stage added to a development tool) gets a tool of its
  own.
- **FR-006**: The feature MUST add at most 10 tools in total. Each new tool serves exactly one
  user story, and the plan names that story for each tool.
- **FR-007**: Every new tool and every extended tool MUST have a description, a typed input with
  documented limits and a documented result shape, in the same form as the existing tools.

**Reading**

- **FR-008**: Read capabilities MUST work without write enablement and MUST NOT send any command
  form that changes the server, apart from side effects INUBIT itself attaches to a read, which
  the tool description names (the check-in comment trace of StartCLI exports, as for
  `export_archives`). Whether `msgread` marks a message as read is checked on a test system and
  named the same way. Each read command is allowed with exactly the read options this
  feature names.
- **FR-009**: Read results MUST be bounded and paged like the existing read tools, and MUST name
  the server and the channel (StartCLI or REST) each fact came from.
- **FR-010**: Every text taken from INUBIT MUST pass the existing masking before it is returned.
  Configuration values, module properties and user records MUST additionally mask every value
  that the secret rules of feature 018 (FR-008 there: secret types, `encrypted` flags, the secret
  name pattern, key material) would mask; there is one rule, not a second one.
- **FR-011**: User listings MUST return login name, group, additional groups, roles and the
  account state INUBIT reports only. E-mail addresses and other personal fields are returned only when the caller asks for one
  named user.
- **FR-012**: A read that produces a file (metadata registry export, diagram export with
  excluded groups) MUST write it as a new file into a folder the caller names, under the folder
  rules of `export_archives` including `archives.productionOptIn`, and return path and size.
- **FR-013**: When a read item is not available (missing role, missing license, command unknown
  in that version), the other items of the same call MUST still be returned and the missing one
  reported with its reason.

**Writing**

- **FR-014**: Every state-changing capability of this feature MUST be refused unless write access
  is enabled for the stage, MUST be refused on a production stage without the production opt-in,
  MUST target exactly one server, and MUST be marked as destructive and not idempotent for the
  client.
- **FR-015**: Every state-changing capability MUST use the existing two-step confirmation: the
  first call returns a preview of what will change and a one-time code bound to that server,
  action and target; only a second call with the code executes. The operational changes of User
  Story 4 follow the stage's `write.confirmation` setting as `restart_process` does (`CLIENT` is
  not allowed on production); the administrative areas of FR-016 always use the server-side
  preview, whatever that setting says.
- **FR-016**: The administrative areas MUST each need their own opt-in per stage, off by default,
  in addition to the write enablement. The column "Protection" of the Background table assigns
  every write entry: **objects** (delete, rename, metadata import, repository delete, IS Connector
  start), **libraries** (libraries, plug-ins, EDI rules, module keys), **settings** (threads,
  privileged share, database connection tolerance, configuration, license, backup start
  options), **users** (users, groups, roles, imports, messages), **run-time data** (run-time data
  import, resume, delete). The operational changes of User Story 4, the tag operations included,
  need the write enablement only.
- **FR-016a**: The run-time data export (User Story 5) blocks the exported processes on the source
  (vendor documentation), so it is a change under FR-014 and FR-015 with the run-time data opt-in,
  like the import. The archive is named by a file name only (no path): the export writes it into
  the backup folder of the source server, and the import and the validation read it from the
  backup folder of the server they run on. Validating an archive (`process validateExport`) and
  listing blocked processes (`process listBlocked`) are reads; they run on one server the caller
  names, need that stage's run-time data opt-in but not the write enablement, and are audited. If
  a check on a test system shows that the validation changes the server, it is governed by FR-014
  instead.
- **FR-017**: A bulk change (`processdelete error`, deleting all libraries of a type, resuming or
  deleting the blocked processes of an operation) MUST show in its preview what and how many
  objects it will affect. The execution re-reads the set right before sending; if it differs from
  the preview (a different set of ids, or a different count where INUBIT reports no ids),
  the call is refused with a new preview. The result names the number INUBIT reports as
  affected, which may include objects that entered the set after the re-read.
- **FR-018**: Before an object that INUBIT can export (workflow, module, metadata, repository file
  or directory) is deleted, renamed or replaced, the server MUST store an export in the existing
  backup location; replacing includes a metadata import, an EDI rule file deployed over an
  existing repository file, and a keystore deployed to a module (the module is backed up). The
  backup keeps the existing retention (30 days, the newest of its scope kept), and the result
  names it. If the backup fails, nothing is changed. Before a tag is removed or deleted, the backup holds the list
  of objects and versions that carried it. `restore_backup` restores these backups only where its
  own rules allow it (development nodes); this feature does not widen `restore_backup`, and on
  other stages the backup is imported by hand. Objects INUBIT cannot export (libraries, license,
  configuration values, users) get no backup; their preview shows the current state instead
  (file name and checksum, expiry date, current value).
- **FR-019**: The server MUST refuse every change that would remove its own access: deleting,
  regrouping or re-passwording its login user, deleting that user's group, and removing the role
  or right that grants it CLI access. `PUT /keys` deploys keys to a module keystore and does not
  touch the server's own TLS certificate; this feature changes no certificate pin (feature 006
  stays the only way to rotate a pin).
- **FR-019a**: All tag operations of this feature (setting or removing the active tag of a user,
  removing or deleting a tag) MUST be offered by one tool of their own for User Story 4, governed
  by FR-014 (write enablement, production opt-in), not by the development-stage rules of
  `tag_artifacts`, which is not changed. A tag removal or deletion MUST name an explicit diagram
  group (with diagram type) or repository path; a call without one is refused before anything is
  read. Tag removal is offered only if a check on a test system shows that INUBIT limits it to the
  named diagram group or repository path; otherwise its entries are excluded with that reason.
  `--tagDiagram` and `--tagWorkflowGroupExclude` are excluded (Background).
- **FR-019b**: A file-based import of users, groups or roles MUST be refused when the file
  contains the server's login user, its group or any of its roles; the server reads the file
  before sending it. (`--overwriteUserRoles` replaces the rights of the roles that are in the
  file, as the vendor documents it, so a role missing from the file is not changed.) A directory
  synchronisation MUST be refused when the user record INUBIT reports for the login user marks it
  as managed by the directory service, and also when this cannot be determined; it is allowed
  when the login user is an internal INUBIT user.
- **FR-020**: Values MUST be validated before any call to INUBIT: numbers against the limits
  INUBIT reports (threads against the license), names against the patterns of the existing tools,
  enumerations against fixed lists. Filters (the `ps` filter, the run-time data export filter) are
  given as structured fields; the server builds the filter expression from validated values only
  and never accepts a raw expression. A message text (`msgsend`) is at most 1,000 characters
  without control characters and is passed as one argument. No free text of the caller reaches a
  command line unchecked.
- **FR-021**: Files to upload (license, library or plug-in, EDI rules, keystore, metadata import
  file, user, group or role import file, IS Connector input) MUST be read from a local path the
  caller names.
  The path must be a regular file, not a symbolic link, inside one of the upload folders listed
  in a new profile setting; the list is empty by default, so uploads are off until it is set.
  Each file type has a size limit with a default (license and keystore 1 MiB; user, group or role
  import file 10 MiB; EDI rules, metadata import file and IS Connector input 50 MiB; library or
  plug-in 200 MiB) that the profile can lower or raise. The run-time data archive is not
  uploaded (FR-016a). File content never passes through the conversation.
- **FR-022**: The tools of this feature MUST NOT accept a password, key password or other secret
  as a parameter and MUST NOT return one. A command that needs a secret (setting a password,
  creating a user with a password, uploading a keystore) takes the name of an entry of a
  configured secret source; the server reads the value and passes it to INUBIT in a way that no
  other process can see. A missing or unreadable entry is refused before any call to INUBIT. If
  INUBIT accepts the secret of a command only on a visible command line, that command is excluded
  with this reason. A configuration value that FR-010 masks can be changed only through a named
  secret-source entry; a plain value for it is refused.
- **FR-023**: Every state-changing call, executed or refused, MUST leave one audit record with
  time, stage and server, tool, action, cleaned parameters, the reason the caller gave and the
  outcome.
- **FR-024**: After an executed change the server MUST read the affected state again and return
  it, or say that the re-read failed.

**Versions and evidence**

- **FR-025**: Every capability MUST be offered for INUBIT 8.1 and 9.0 where the vendor documents
  it for that version. A capability is declared supported for a version only after its command
  output or response has been recorded on a non-production system of that version.
- **FR-026**: Recordings of state-changing commands MUST be made only on a non-production system
  and only with the operator's approval for that command.
- **FR-027**: The tool reference, the setup guide and the interface research note MUST be updated
  in the same change that adds a capability; the backlog entry "Further read access to INUBIT" is
  removed when its candidates are delivered.

### Key Entities

- **Coverage entry**: one documented StartCLI command with one option, or one HTTP method with
  one path; its kind (read or write), its area, its user story and its state per INUBIT version
  (reachable through, covered by, planned, not documented, excluded because).
- **Server settings**: the facts of User Story 1 for one server, each with value, source channel
  and, if missing, the reason.
- **User record**: login name, group, additional groups, roles, account state; personal fields
  only for a single named user.
- **Change preview**: what a state-changing call will do, the state it was computed from, the
  confirmation code and its expiry.
- **Administration opt-in**: a per-stage setting that enables one administrative area.
- **Upload folder list**: a profile setting naming the local folders upload files may come from,
  with the size limit per file type.
- **Change result**: action, target, state before and after, backup (if one was taken), audit id.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100 % of the entries of the documentation pages named in FR-001 appear in the
  coverage list with one state per INUBIT version; when the feature is complete, no entry is
  without a state or still "planned".
- **SC-002**: An operator gets each fact of User Stories 1 and 2 (license expiry, configured
  threads, users of a server, logins) with one request to the agent and without opening the
  Workbench or a shell.
- **SC-003**: With a profile that enables no write access, none of the new state-changing
  capabilities can be executed; this is shown by a test for each of them.
- **SC-004**: On a production stage without the production opt-in, 0 of the new state-changing
  capabilities can be executed, whatever else is configured.
- **SC-005**: In tests that plant known secret values in every INUBIT answer the new capabilities
  read, 0 of these values appear in a result, an error text, a log line or an audit record.
- **SC-006**: Every new result stays within the size limits of the existing tools; no call
  returns an unbounded list or text.
- **SC-007**: The number of tools grows by at most 10, each serving one user story.
- **SC-008**: Every executed or refused change can be found afterwards by its audit record with
  who, where, what and why.
- **SC-009**: The tools that exist before this feature behave as before; their tests pass
  unchanged except where a tool is deliberately extended.

## Assumptions

- "All missing methods" means every function the vendor documents for StartCLI and the REST API of
  INUBIT 8.1 and 9.0. Internal interfaces of the Workbench are not part of this feature; they
  stay with the `unsupported` opt-ins of features 020 and 021.
- The existing write protection is reused as it is: write enablement per stage, production
  opt-in, two-step confirmation, audit records.
- StartCLI commands need a configured INUBIT client for the server's version, as today. A stage
  without a client gets only the REST-based capabilities.
- Reading the license and changing settings over REST needs the role "System Administrator" for
  the login user. The feature reports a missing role; it does not grant roles.
- INUBIT 6.1 stays read-only and gets no new capabilities from this feature.
- `processLog` stays excluded until a version exists on which the endpoint works.
- The start options `--backupFile` and `--backupRepository` are documented only by name. They
  belong to User Story 6 and are classified (reachable or excluded) after a check on a
  non-production system.
- Secret sources are those of features 010 and 011; this feature adds no new kind of source.
- Feature 021 is merged before the implementation of this feature starts; the trace download of
  021 and the trace switches of this feature stay separate capabilities.

## Out of Scope

- A tool per command or per endpoint (Principle IV of the constitution).
- The portal functions: Tasks, Webapps, Reports and the process documentation.
- Starting or stopping the process engine, and the offline scripts `backup.sh` and `restore.sh`.
- The cache mode of StartCLI and `validateMigrationBackup`.
- Granting roles or rights to the login user so that a capability works.
- Changes on INUBIT 6.1.
- New access through internal Workbench interfaces.
