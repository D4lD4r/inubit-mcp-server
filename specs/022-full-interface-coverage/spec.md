# Feature Specification: Full Coverage of the StartCLI and REST Interfaces

**Feature Branch**: `022-full-interface-coverage`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "I would like to add all missing methods of StartCLI and REST, both
reading and writing, to the MCP."

## Background

A comparison of the vendor documentation for INUBIT 8.1 and 9.0 (14 StartCLI pages, the REST API
reference) with the commands and endpoints the server sends today (2026-11-12, summarised in
`docs/backlog.md`, "Further read access to INUBIT") showed that the server uses 8 REST endpoints
and 6 StartCLI commands. Everything else that INUBIT documents is not reachable through any tool.
An operator who needs one of these functions still has to open the Workbench or a shell with
StartCLI.

The missing functions fall into six areas. The table is the scope of this feature; commands are
named as the vendor documents them.

| Area | Missing read access | Missing write access |
|---|---|---|
| A. Server state and settings | `license -td\|-ts`, `GET /license`, `threads showNumberOfWorkflowThreads`, `privilegedWorkflows showThreadsPercentage\|showWorkflowList`, `dbconnectiontolerance show`, `getBlockingQueueSize`, `GET /configurations`, `date`, `lls`, `-sv`, `-pctmem`, `-maintenanceState` | `maintenance on\|off [-eq]`, `POST /ready/{on\|off}`, `threads setNumberOfWorkflowThreads`, setting the share of threads for privileged workflows, `dbconnectiontolerance set\|drop`, `PUT\|POST /configurations`, `PUT /license`, `triggerMidnightTask`, `trace on\|off`, `trace -l <level>`, `debug on\|off` |
| B. Users and groups | `finger <login>`, `user <name>`, `-who`, `-nwho`, `GET /user/users`, `msglist`, `msgread` | `create -cu\|-cug\|-cur`, `user --setGroup\|--setAdditionalGroups\|--addAdditionalGroups\|--removeAdditionalGroups`, `passwd`, `delete --deleteUser\|--deleteUserGroup`, `DELETE /user/users/{name}`, `import --importUserGroup\|--overwriteUserRoles\|--importLDAPDetails\|--syncLDAPUsers`, `msgsend`, `msgdel` |
| C. Libraries, plug-ins, EDI rules, keys | `library --list` | `library --upload\|--delete\|--deleteAll`, `plugin --register\|--deregister`, `PUT /libraries`, `DELETE /libraries/{name}`, `PUT\|POST /edirules`, `PUT /keys` |
| D. Processes and run-time data | `ps -f '<filter>'`, `ps -u`, `-ne`, `-nx`, `-nw`, `-nq` on 8.1 and 9.0, `process listBlocked`, `process validateExport` | `processdelete error`, `process export\|import\|resume\|deleteBlocked` |
| E. Diagrams, modules, repository, tags | `GET /model/nodeByModel/…`, `GET /model/nodeByName/…` (8.1 only), `export --exportMetadata\|--exportMetadataNames`, `export --exportWorkflowGroupExclude`, `GET /process/metadata/{name}`, `GET /isconnector?…type=validateWorkflow`, `fetchXSLT1.0`, reading a repository resource | `delete --deleteWorkflow\|--deleteModule`, `DELETE /diagrams/…`, `DELETE /modules/…`, `DELETE /repository`, `rename …`, `tag --tagSetActive\|--tagRemoveActive\|--tagDelete\|--tagRemove\|--tagDiagram\|--tagRepositoryPath\|--tagWorkflowGroupExclude`, `import --importMetadata`, `POST /isconnector` |
| F. Portal | `GET /process/processes/…`, `GET /process/search`, on 9.0 also `/process/tree`, `…/history`, `/process/tags/{tag}`; `GET /report/reportdata…`, `GET /report/reports…`; the `GET` endpoints under `/task/…` and `/webapp/…` | `PUT /report`; the `POST`, `PUT` and `DELETE` endpoints under `/task/…` and `/webapp/…` |

Three groups of documented functions need no new access:

- **Already covered by a tool with the same effect**: `GET /log/{name}` (the `POST` form is used),
  the `deploy` command and `PUT /model/import` (`deploy_release`, `import_artifacts`), `uname`,
  `uptime` and `free` on 8.1 and 9.0 (`get_health` reads the same facts over REST).
- **Not usable from a server process**: the cache mode (the vendor reserves it for its support
  and calls it dangerous), `validateMigrationBackup` (interactive only), `--encryptString` (takes
  a secret on the command line and prints the result), the offline scripts `backup.sh` and
  `restore.sh` (they need a stopped process engine on the INUBIT host).
- **Broken on the vendor side**: the log `processLog` fails on 8.1.17.

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
server configuration.

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
   group, additional groups, roles and state; a password hash or any other secret field of the
   INUBIT answer is never part of the result.
3. **Given** a login name that does not exist, **When** that user is read, **Then** the result is
   an error that says so and names the listing as the next step.
4. **Given** three users are logged in, **When** the logins are requested, **Then** the result
   names them with the login time INUBIT reports.

---

### User Story 3 - Read processes, run-time data and model details (Priority: P2)

An operator wants to filter the running processes of an 8.1 or 9.0 server by workflow and tag as
the Queue Manager does, see blocked run-time data of an earlier transfer, read one module of a
diagram without exporting the whole diagram, read process metadata, check an IS Connector, list
the installed drivers and plug-ins, and get the list of XSLT 1.0 usages.

**Why this priority**: read only, but each item serves a narrower question than User Stories 1
and 2, and several overlap with existing tools.

**Independent Test**: With recorded outputs, each item returns its documented shape; an item that
an existing tool can answer is reached through that tool. Testable offline.

**Acceptance Scenarios**:

1. **Given** an 8.1 server with processes of several workflows, **When** processes are searched
   with a workflow name and a tag, **Then** only the matching processes are returned, with the
   same fields as the existing process search.
2. **Given** a diagram and the id of one of its modules, **When** that module is read, **Then** the
   result contains the module's properties with secret values masked.
3. **Given** a server with installed drivers and plug-ins, **When** the libraries are listed,
   **Then** the result names each file with its type.
4. **Given** a target folder, **When** the XSLT 1.0 usage is requested, **Then** the vendor's
   report file is written into that folder as a new file and the result names its path and size.
5. **Given** the operation id of an earlier run-time data export, **When** the blocked processes
   are listed, **Then** the result is a bounded page of those processes.

---

### User Story 4 - Operate a server (Priority: P2)

An operator prepares and finishes a maintenance window through the agent: switch the maintenance
mode and the readiness state, delete the processes that are in error state, set or remove the
active tag version, change the trace level for a diagnosis and switch it back, trigger the
midnight task.

**Why this priority**: these are the changes operators make routinely. They are reversible or
limited in effect, and they use the write protection that already exists.

**Independent Test**: With a fake StartCLI and REST server, each change is refused by default,
shows a preview when enabled and is executed only with the confirmation; each step leaves an
audit record. Testable offline.

**Acceptance Scenarios**:

1. **Given** a stage without write enablement, **When** the maintenance mode is to be switched on,
   **Then** the call is refused with the code of the existing write tools and the next step names
   the setting.
2. **Given** a stage with write enablement, **When** the maintenance mode is to be switched on,
   **Then** the first call returns a preview (server, current state, requested state, number of
   running and queued processes) and a confirmation code; the second call with the code switches
   the mode and returns the state read afterwards.
3. **Given** a production stage without the production opt-in, **When** any change of this story
   is requested, **Then** it is refused, whatever the write enablement says.
4. **Given** 40 processes in error state, **When** their deletion is requested, **Then** the
   preview names the number 40 and the workflows concerned; the execution reports how many were
   deleted.
5. **Given** the trace level is `error`, **When** it is set to `debug`, **Then** the result names
   the level before and after, and the tool description says that the level stays until it is
   set back.
6. **Given** the maintenance mode is on, **When** it is to be switched off, **Then** the change
   works although INUBIT answers every other REST request with "service unavailable".

---

### User Story 5 - Transfer and repair run-time data (Priority: P3)

An operator moves the run-time data of running processes from one system to another (export on
the source, import on the target), resumes the blocked processes after the import or deletes
them.

**Why this priority**: rare (migrations), with large files and a multi-step flow, but there is no
other way than the CLI.

**Independent Test**: With a fake StartCLI, an export writes an archive into a named folder, an
import reads one from a named file and returns the operation id; resume and delete need that id
and the confirmation. Testable offline.

**Acceptance Scenarios**:

1. **Given** a source server and a target folder, **When** the run-time data is exported with a
   filter expression, **Then** the archive is written as a new file and the result names path,
   size and the operation id.
2. **Given** an archive file and a target server with write enablement, **When** the import is
   confirmed, **Then** the result names the operation id and the number of blocked processes.
3. **Given** an operation id, **When** the blocked processes are to be deleted, **Then** the
   preview names their number and the execution reports how many were deleted.

---

### User Story 6 - Administer objects, libraries and settings (Priority: P3)

An administrator removes what is no longer needed and changes how a server is set up: delete or
rename a workflow, a module or a repository file, delete a tag, upload or delete a driver or a
plug-in, upload EDI rules, change the number of workflow threads, the database connection
tolerance or a configuration value, upload a new license.

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
   list.
5. **Given** the number of workflow threads is 20 and the license allows 50, **When** it is to be
   set to 80, **Then** the call is refused before any change, with the licensed number in the
   message.
6. **Given** a configuration change, **When** it is previewed, **Then** the preview shows the
   current and the new value of each changed setting, with secret values masked on both sides.

---

### User Story 7 - Administer users and groups (Priority: P4)

An administrator creates a user, a group or a role, changes the groups of a user, sets a
password, deletes a user or a group, or starts the synchronisation with a directory service.

**Why this priority**: user administration is done seldom and usually through the Workbench or a
directory service; it touches credentials, which the constitution keeps out of the conversation.

**Independent Test**: With fakes, a user is created and deleted with preview and confirmation; no
call accepts or returns a password; the server's own login user cannot be changed. Testable
offline.

**Acceptance Scenarios**:

1. **Given** the opt-in for user administration, **When** a user is created, **Then** the preview
   names login, group and roles, and the result confirms the creation without any password.
2. **Given** a password is to be set for a user, **When** the call is made, **Then** the value
   never appears in the call, the result, the audit record or the process list
   [NEEDS CLARIFICATION: where does the new password come from - a named entry of a configured
   secret source, a value generated by the server and stored in the credential store, or are the
   commands that take a secret left out?].
3. **Given** the login user of the MCP server itself, **When** it is to be deleted, moved to
   another group or given a new password, **Then** the call is refused.
4. **Given** a user who owns workflows, **When** the user is to be deleted, **Then** the preview
   names the number of owned workflows and repository entries and what happens to them.

---

### User Story 8 - Use the portal functions (Priority: P5)

A person uses the task list, web applications, reports and the process documentation of the
INUBIT portal through the agent.

**Why this priority**: these functions serve business users of the portal, not the operation of
the platform. They need portal users instead of the administration login.

[NEEDS CLARIFICATION: are the portal functions (Tasks, Webapps, Reports, process documentation;
about 40 endpoints) part of this feature, or does "all" mean the functions for operation and
administration?]

**Independent Test**: With recorded responses, the tasks of a portal user are listed and one
report is read. Testable offline.

**Acceptance Scenarios**:

1. **Given** a configured portal user, **When** the tasks are listed, **Then** the result is a
   bounded page of that user's tasks.
2. **Given** no portal user is configured for a stage, **When** a portal function is requested,
   **Then** the tools of this story are not offered for that stage.

---

### Edge Cases

- A command exists in 8.1 but not in 9.0 or the other way round (`/model/nodeByName`,
  `/process/tree`): the capability is offered only for the versions that have it, and a call for
  another version is refused with the existing version error.
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
  access, the certificate in the keystore): the change is refused.
- A file to upload is missing, outside the allowed folders, a symbolic link or larger than the
  limit: refused before any call to INUBIT.
- A command prints a text the parser does not know (new patch level): the result is an error that
  says the output was not understood, with a masked and bounded excerpt, never a guessed value.
- A stage id is given for a change: refused; a change always names exactly one server.
- Two changes of the same kind run at the same time against one server: the second is refused or
  waits, as with the existing write tools.
- `POST /isconnector` starts a workflow with a payload in a vendor-internal format: if the payload
  cannot be built reliably, the function goes to the exclusion list with that reason.

## Requirements *(mandatory)*

### Functional Requirements

**Coverage**

- **FR-001**: The project MUST keep a coverage list of every StartCLI command and option group and
  every REST endpoint that the vendor documents for INUBIT 8.1 and 9.0. Each entry has exactly one
  state: reachable through a named tool, covered by a named tool with the same effect, or excluded
  with a stated reason.
- **FR-002**: After this feature, every entry of areas A to E of the Background table MUST be
  reachable through a tool, unless it is moved to the exclusion list with a reason that a check
  against a real system gave (for example: the command does not exist in that version, needs an
  interactive terminal, or cannot be made safe).
- **FR-003**: A test MUST fail when a command or endpoint the server can send is missing from the
  coverage list, and when an entry names a tool that does not exist.
- **FR-004**: [NEEDS CLARIFICATION: is this one feature that delivers all areas in the order of
  the user stories, or is it delivered as several features (for example reading first, operation
  second, administration third), each with its own plan, review and release?]

**Tool design**

- **FR-005**: New capabilities MUST be grouped by the task they serve, not offered as one tool per
  command. Where an existing tool serves the same task, it is extended (for example process
  search for the `ps` filters, the health view for license and threads, tagging for the tag
  operations) instead of adding a tool.
- **FR-006**: The feature MUST add at most one read tool and one write tool per area of the
  Background table. The plan justifies each new tool with the user story it serves.
- **FR-007**: Every new tool and every extended tool MUST have a description, a typed input with
  documented limits and a documented result shape, in the same form as the existing tools.

**Reading**

- **FR-008**: Read capabilities MUST work without write enablement and MUST NOT send any command
  form that changes the server. Each read command is allowed with exactly the read options this
  feature names.
- **FR-009**: Read results MUST be bounded and paged like the existing read tools, and MUST name
  the server and the channel (StartCLI or REST) each fact came from.
- **FR-010**: Every text taken from INUBIT MUST pass the existing masking before it is returned.
  Configuration values, module properties and user records MUST additionally mask every field
  whose name marks it as a secret.
- **FR-011**: User listings MUST return login name, group, additional groups, roles and state
  only. E-mail addresses and other personal fields are returned only when the caller asks for one
  named user.
- **FR-012**: A read that produces a file (XSLT 1.0 report, metadata export) MUST write it as a
  new file into a folder the caller names, under the folder rules of `export_archives`, and return
  path and size.
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
  action and target; only a second call with the code executes.
- **FR-016**: The administrative areas (objects and repository, libraries and keys, server
  settings and license, users and groups, run-time data) MUST each need their own opt-in per
  stage, off by default, in addition to the write enablement. The operational changes of User
  Story 4 need the write enablement only.
- **FR-017**: A bulk change (`processdelete error`, deleting all libraries of a type, deleting the
  blocked processes of an operation) MUST show in its preview what and how many objects it will
  affect. The confirmation is valid only while that set is unchanged.
- **FR-018**: Before an object is deleted or replaced that INUBIT can export (workflow, module,
  repository file, tag state), the server MUST store an export in the existing backup location
  and name it in the result. If the backup fails, nothing is deleted.
- **FR-019**: The server MUST refuse every change that would remove its own access: its login
  user, that user's group and roles, and the key material its connection uses.
- **FR-020**: Values MUST be validated before any call to INUBIT: numbers against the limits
  INUBIT reports (threads against the license), names against the patterns of the existing tools,
  enumerations against fixed lists. No free text of the caller reaches a command line unchecked.
- **FR-021**: Files to upload (license, library, EDI rules, keystore, run-time data archive) MUST
  be read from a local path the caller names, within the allowed folders, with a size limit. File
  content never passes through the conversation.
- **FR-022**: The tools of this feature MUST NOT accept a password, key password or other secret
  as a parameter and MUST NOT return one. Commands that need a secret take it by the way settled in the
  clarification of User Story 7.
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

- **Coverage entry**: one documented StartCLI command or REST endpoint with its INUBIT versions,
  its kind (read or write), its area and its state (reachable through, covered by, excluded
  because).
- **Server settings**: the facts of User Story 1 for one server, each with value, source channel
  and, if missing, the reason.
- **User record**: login name, group, additional groups, roles, state; personal fields only for a
  single named user.
- **Change preview**: what a state-changing call will do, the state it was computed from, the
  confirmation code and its expiry.
- **Administration opt-in**: a per-stage setting that enables one administrative area.
- **Change result**: action, target, state before and after, backup (if one was taken), audit id.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100 % of the StartCLI commands and REST endpoints documented for INUBIT 8.1 and 9.0
  appear in the coverage list with exactly one state; no entry is without a state.
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
- **SC-007**: The number of tools grows by at most 12 (one read and one write tool per area).
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
- The administration opt-ins of FR-016 are a decision of the creator; the owner may override it.
- StartCLI commands need a configured INUBIT client for the server's version, as today. A stage
  without a client gets only the REST-based capabilities.
- Reading the license and changing settings over REST needs the role "System Administrator" for
  the login user. The feature reports a missing role; it does not grant roles.
- INUBIT 6.1 stays read-only and gets no new capabilities from this feature.
- `processLog` stays excluded until a version exists on which the endpoint works.
- The start options `--backupFile` and `--backupRepository` are documented only by name. They are
  classified (reachable or excluded) after a check on a non-production system.
- Feature 021 is merged before the implementation of this feature starts; the trace download of
  021 and the trace switches of this feature stay separate capabilities.
- The portal functions (User Story 8) need frontend users, which the profile does not hold today.

## Out of Scope

- A tool per command or per endpoint (Principle IV of the constitution).
- Starting or stopping the process engine, and the offline scripts `backup.sh` and `restore.sh`.
- The cache mode of StartCLI and `validateMigrationBackup`.
- Granting roles or rights to the login user so that a capability works.
- Changes on INUBIT 6.1.
- New access through internal Workbench interfaces.
