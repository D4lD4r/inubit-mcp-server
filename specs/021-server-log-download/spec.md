# Feature Specification: Download Server Log Files (trace.log, webservice.log)

**Feature Branch**: `021-server-log-download`

**Created**: 2026-11-11

**Status**: Draft

## Clarifications

### Session 2026-11-11

- The owner requires both log files on single-node servers and on cluster nodes, for INUBIT 8.1
  and 9.0 (the owner's installations run without a cluster).
- Decisions taken by the creator (owner may override): the file is written into a local folder
  the caller names, with the folder rules of `export_archives`, never replacing a file; the file
  is a verbatim copy, only the excerpt in the result is masked; trace.log needs no opt-in,
  webservice.log needs the group opt-in `unsupported.serverLogRead`; every call is audited
  without content.
- Decisions taken by the creator after spec review round 1 (owner may override): masking runs on
  the whole scanned window before lines are selected, and `contains` searches the masked text;
  line numbers are absolute in the file; a too long excerpt drops its oldest lines; a binary window
  gives no excerpt (rule of feature 020); trace.log stays without opt-in also on production groups
  (it is supported functionality, like the StartCLI exports that run there), but the tool
  description and the documentation say that it may contain business data in clear text.

**Input**: User description: "Download of trace.log and download of webservice.log as MCP tools,
probably behind the unsupported opt-in."

## Background

An INUBIT server writes two log files that the documented REST log interface (`query_logs`) does
not cover:

- **trace.log**: the technical trace of the process engine (stack traces, connector errors,
  module output at debug level). It is the first place to look when a process fails without a
  useful Queue Manager text.
- **webservice.log**: the message log of the web service connectors (requests and responses of
  SOAP calls when web service logging is enabled). It shows what was actually sent or received.

Today an operator opens the Workbench (Administration → Server Log, or the Cluster Manager) to see
them; an agent using this MCP server cannot.

Research on the 8.1.17 and 9.0.10 client installations (2026-11-11) found:

- **trace.log** can be downloaded with StartCLI's own command `trace -d <file>` (long form `trace --download <file>`; "Download trace
  log and store to file"), in 8.1 and 9.0. This is supported vendor functionality, reached through
  the StartCLI channel the server already uses. The same `trace` command also switches tracing and
  log levels; those forms change the server and are never used.
- **webservice.log** has no documented interface. The Workbench's Cluster Manager reads it through
  an internal download of the log file of one node. The server prepares that download in its
  cache store for single-node servers as well as for cluster nodes, so the same path serves both.
  Because the interface is internal, the vendor gives no support and no stability guarantee for it;
  this part therefore exists only behind an explicit opt-in, like `read_queue_entry` (feature 020).
- The internal download takes the file name from the client without checking it on the server.
  The MCP server therefore sends only the fixed names of these two files, never a name from the
  caller.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Get the trace log of a server (Priority: P1)

An operator (or an agent working for them) investigates a failed process or a connector error and
asks for the trace log of the server where it happened. They get the file in a local folder and
the most recent lines (or the lines matching a search text) in the result, without opening the
Workbench.

**Why this priority**: trace.log is the main diagnosis source beyond the Queue Manager and the
database logs; it uses supported functionality and needs no opt-in.

**Independent Test**: With a fake StartCLI, call the tool with `log: trace` for a server: the file
appears in the target folder with the downloaded content, the result names path, size and the
masked excerpt. Testable offline.

**Acceptance Scenarios**:

1. **Given** a server with a configured StartCLI client, **When** the tool is called with
   `log: trace` and a target folder, **Then** the trace log of that server is written as a new
   file into the folder and the result contains the server, the log, the absolute file path, the
   size in bytes and the last 200 lines as excerpt (fewer, with a truncation marker, if they
   exceed `maxChars`, FR-009 step 5).
2. **Given** the same call with `contains: "OutOfMemoryError"`, **When** the tool runs, **Then** the
   excerpt contains only the last matching lines (at most `tailLines`), each with its line number
   in the file, and the result says how many lines matched in total.
3. **Given** the trace log contains a line `Authorization: Basic …` or a configured INUBIT
   password of the profile, **When** the excerpt is built, **Then** those values are masked in the
   excerpt; the file itself is the verbatim download.
4. **Given** a trace log whose last 200 lines have 60,000 characters in total and the default
   `maxChars` of 20,000, **When** the tool is called, **Then** the excerpt holds only the newest
   lines that fit, the oldest are dropped, and the truncation marker names how many lines were
   dropped.
5. **Given** a file with the generated name already exists in the folder, **When** the tool is
   called, **Then** the existing file is not replaced (the call picks a free name as in FR-007).
6. **Given** a profile in which no group has any `unsupported` setting, **When** the server starts,
   **Then** the tool is offered (for `log: trace`) wherever a StartCLI client is configured.
7. **Given** a trace log of 60 MiB, **When** the tool is called, **Then** the excerpt comes from the
   window starting at the first whole line within the last 50 MiB, the result says that the window
   is smaller than the file, and each excerpt line carries its line number in the whole file.
8. **Given** a log in which a `<password>` element spans lines 10–12 and line 13 follows, **When**
   the excerpt is built, **Then** the value is replaced by the mask token on line 10, line 11 is
   empty, line 12 keeps `</password>` and the text after it, and line 13 keeps number 13.
9. **Given** a window whose first lines continue a `<password>` element opened before the window
   and close it with `</password>`, **When** the excerpt is built, **Then** every line from the start
   of the window up to the end tag is masked; the line with the end tag keeps the end tag and the
   text after it.
10. **Given** the credential store of another configured node has not answered within the call's
    deadline, **When** the tool is called, **Then** the file is written, the result contains no log
    line, says that the excerpt was withheld and names that node and its credential source, and the
    audit record shows the call as successful.

---

### User Story 2 - Get the web service log of a server (Priority: P1)

An operator wants to see what a web service connector really sent or received. Their group has
the opt-in for the unsupported log read. They ask for the web service log and get it the same way
as the trace log.

**Why this priority**: it is the only source for the actual SOAP messages; it is the second half
of the request.

**Independent Test**: With a fake INUBIT client per version, call the tool with
`log: webservice` for a server of an opted-in group: the file appears with the downloaded content;
for a group without the opt-in the call is refused before any request.

**Acceptance Scenarios**:

1. **Given** a group with `unsupported.serverLogRead: true` and a single-node 8.1 server,
   **When** the tool is called with `log: webservice`, **Then** the web service log of that server
   is written into the folder and the result is shaped as in User Story 1, plus the INUBIT node name
   the file came from.
2. **Given** the same for a node of a cluster, **When** the tool is called, **Then** the web service
   log of exactly the node the configured server address connects to is returned, and the result
   names that node.
3. **Given** a group without the opt-in, **When** the tool is called with `log: webservice`,
   **Then** it is refused with `NOT_CONFIGURED` naming the missing setting, and no request is sent
   to the server.
4. **Given** web service logging is disabled on the server or the file does not exist yet,
   **When** the tool is called, **Then**, where the interface tells a missing file apart (edge case
   "Missing versus empty"), it fails with `NOT_FOUND` saying that the server has no web service log
   and that web service logging can be enabled in the server configuration, and no file is written;
   otherwise the result reports an empty log with the hint that web service logging may be
   disabled.
5. **Given** the documentation of the opt-in, **When** an operator reads it, **Then** it states that
   the web service log is read through unsupported, undocumented INUBIT functionality without
   vendor support or stability guarantee, that it may stop working after any INUBIT update, and that
   the log contains message content in clear text.
6. **Given** the opt-in on a group with `production: true`, **When** the server starts, **Then** the
   startup log and `--check-config` show it as a warning.

---

### User Story 3 - Right implementation per INUBIT version (Priority: P2)

The operator does not choose how a log is read. The tool uses the channel and the INUBIT client
installation that match the detected version of the addressed server.

**Why this priority**: profiles mix 8.1 and 9.0 servers (for example during a migration); a wrong
client fails or reaches an interface that does not exist.

**Independent Test**: With fakes per version, a call against an 8.1 server uses the 8.1 client, a
call against a 9.0 server the 9.0 client; a 6.1 or not yet detectable server is refused.

**Acceptance Scenarios**:

1. **Given** a server detected as 8.1 or 9.0, **When** the tool is called for either log, **Then**
   the client installation configured for that version is used.
2. **Given** a server on INUBIT 6.1 or another version line, **When** the tool is called, **Then**
   it is refused with `UNSUPPORTED_VERSION` naming the detected version and the supported lines.
3. **Given** the version cannot be determined (server unreachable during detection), **When** the
   tool is called, **Then** it fails with the error of the version detection (`UNREACHABLE`,
   `TLS_ERROR`, `AUTH_FAILED` or `TIMEOUT`, as the other tools report it) and no download request
   is sent.
4. **Given** no client installation is configured or usable for the server's version, **When** the
   tool is called, **Then** it fails with `CLI_UNAVAILABLE` naming the version and the setting.

---

### Edge Cases

- **Group id as target**: rejected by the input schema; a log belongs to one server.
- **Very large log**: the download is not size-limited by the tool; it is bounded by the node's
  `cliExportTimeout` (then `TIMEOUT`, and no file is left behind). The excerpt is always bounded (FR-009).
- **Empty log** (size 0): the file is written, the result says the log is empty, no excerpt.
- **Missing versus empty**: where INUBIT answers a missing file differently from an empty one,
  a missing file is `NOT_FOUND`; where the interface cannot tell them apart, the answer is treated
  as an empty log and the result says that the file may also be missing on the server (the plan
  determines per channel and version which case applies, from the recordings of FR-017).
- **Binary or non-UTF-8 content**: the scanned window is classified with the rule of feature 020
  (FR-009 there); a binary window gives no excerpt, only the size and a hint. Text is decoded as
  UTF-8 if valid, otherwise as ISO-8859-1, with the charset named; the file is never re-encoded.
- **Rotated files** (`trace.log.1`, …): not reachable through these interfaces; out of scope.
- **Load-balanced server address in a cluster**: the log comes from whichever node answers;
  for the web service log the result names the node INUBIT reported, for the trace log it names the
  configured server. The documentation says to address cluster nodes directly.
- **Download fails half-way**: no partial file is left under the final name.
- **`contains` matches nothing**: the file is written, the excerpt is empty and the result says
  that no line matched.
- **Server in maintenance mode, credentials not available, TLS failure**: same behaviour as the
  other StartCLI-based tools of the server.
- **The internal download answers in an unexpected way** (for example after an INUBIT update):
  `UNEXPECTED_RESPONSE`, whose message says that the unsupported interface may have changed.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST offer a tool `download_server_log` with the inputs `target`
  (required; exactly one server id `<group>/<node>`), `log` (required; `trace` or `webservice`),
  `targetFolder` (required; absolute path of a local folder), `tailLines` (optional; integer
  1–2,000, default 200), `contains` (optional; 1–200 characters without control characters, matched
  case-insensitively as plain text, not as a pattern) and `maxChars` (optional; integer 1–100,000,
  default 20,000).
- **FR-002**: The tool MUST be offered when at least one node has a StartCLI client configured and
  is not pinned to an unsupported version line. `log: webservice` MUST be refused with
  `NOT_CONFIGURED` for a server whose group lacks the opt-in, before any request to that server.
  The tool changes nothing on INUBIT and is therefore not subject to the write-access settings or
  the production write opt-in; it is offered in read-only mode.
- **FR-003**: The opt-in MUST be the group setting `unsupported.serverLogRead`, boolean, default
  `false`, accepted only directly on a group, with the same validation rules as
  `unsupported.queueEntryRead` (unknown key elsewhere, non-boolean value is a startup error whose
  message does not echo the value). The two opt-ins are independent.
- **FR-004**: The documentation of the opt-in (setup guide and tool reference) and the tool
  description MUST state the warning of User Story 2, scenario 5. The tool description MUST also
  say that the log file is written in clear text, that both logs may contain business data and
  credentials, and that trace.log uses supported StartCLI functionality.
- **FR-005**: `log: trace` MUST be read only with StartCLI's documented trace download
  (`trace --download <file>`, the long form of `-d`) through the existing StartCLI channel (argument array, timeout, exit-code
  evaluation, tested output parser). The tool MUST NOT send any other form of the `trace` command
  (no `on`, `off`, log-level or reset options).
- **FR-006**: `log: webservice` MUST be read only with the internal log download the Workbench's
  Cluster Manager uses, through the INUBIT client installation of the server's version, for the
  node the server address connects to. The request MUST carry only the fixed file name of the web
  service log; no caller input may reach the file name. No other request than the one the download
  needs (login, own node lookup, download, logout) may be sent.
- **FR-007**: The file MUST be written into `targetFolder` under the name
  `<group>-<node>-<log>-<UTC time yyyyMMdd-HHmmss>.log`; if that name exists, `-2`, `-3`, … is
  appended before `.log`. The name MUST be claimed atomically (create-new), so concurrent calls
  never share a name. An existing file MUST never be replaced, a half-written file never
  appear under the final name, and the folder rules of `export_archives` (absolute path, no network
  path, not inside the workspace, the server's directories or a git working tree; created
  owner-only if missing) MUST apply. File permissions follow `export_archives` (POSIX `rw-------`).
- **FR-008**: The file MUST be the verbatim content INUBIT delivered (no masking, no re-encoding).
- **FR-009**: The result MUST contain the server, the log, the INUBIT node name where known, the
  absolute file path, the size in bytes, the time of the download (UTC, ISO-8601, the same instant
  as in the file name), and an excerpt built in this
  order:
  1. The **scanned window** starts at the first line that begins within the last 50 MiB of the
     file (the whole file if smaller); a partial first line is not part of the window. The result
     says when the window is smaller than the file. A window classified as binary (rule of feature
     020) gives no excerpt (edge case above).
  2. The window is **masked** as a whole (FR-010).
  3. Lines are **selected** from the masked window: without `contains` the last `tailLines` lines;
     with `contains` the last `tailLines` lines of the masked window that contain the search text
     (case-insensitive plain text), so a text that occurs only inside a masked value never matches.
     The result gives the number of matching lines in the scanned window.
  4. Each excerpt line carries its **line number, absolute in the downloaded file** (lines before
     the window are counted, not searched).
  5. If the selected lines exceed `maxChars`, whole lines are **dropped from the beginning** (oldest
     first) until the rest fits; a single line longer than `maxChars` is cut at its end; the
     truncation marker names how many lines were dropped or cut.
- **FR-010**: Masking MUST apply the text rules of `read_queue_entry` (FR-011 of feature 020:
  secret-named XML elements and attributes, JSON keys, `name=value` pairs and header-style lines,
  `Authorization` and `Basic`/`Bearer` credentials) and scrub the known credentials of the profile,
  on the whole scanned window before lines are selected (FR-009). Masking MUST keep every line
  break of the window: a masked value spanning several lines is replaced by the mask token on its
  first line and the other lines it covered remain as empty lines, so line N of the masked window is
  line N of the window; a line the masked value covers only in part keeps its unmasked text, only
  lines it covers completely become empty. If the window contains the end tag of a secret-named element whose start
  tag is not in the window (it was opened before the window), every line from the start of the
  window up to and including that end tag is masked the same way: when in doubt, content is left
  out rather than shown. A secret-named JSON value (object or array) that opens before the window
  is not detected; the documentation names this limit. If the known credentials of every configured
  node cannot be read within the remaining `cliExportTimeout` of the call (FR-013) — for example a
  credential store of another node that has not answered — the excerpt MUST be withheld rather than
  shown with possibly unscrubbed values: the file is still written, and instead of the excerpt the
  result says that it was withheld, names the node and credential source that is not ready, and
  says that every excerpt is withheld until that source can be read. This applies only to
  credentials read for masking; if the addressed server's own login credentials cannot be read,
  the call fails with `SECRET_UNRESOLVED` or `TIMEOUT` (FR-011).
- **FR-011**: Errors MUST use the existing error codes: `INVALID_INPUT` (bad input, folder rules),
  `NOT_FOUND` (log file does not exist on the server), `NOT_CONFIGURED`, `UNSUPPORTED_VERSION`,
  `CLI_UNAVAILABLE`, `AUTH_FAILED`, `FORBIDDEN` (the user lacks the right the Workbench needs),
  `TIMEOUT`, `UNREACHABLE`, `TLS_ERROR`, `UNEXPECTED_RESPONSE`, `TARGET_UNKNOWN`,
  `SECRET_UNRESOLVED`, `MAINTENANCE_MODE`, as the other StartCLI-based tools use them. On every
  error no file of the call remains in the target folder; a folder the call created stays (empty).
- **FR-012**: Connections MUST verify TLS like the server's other StartCLI connections; credentials
  MUST reach the client side the way StartCLI gets them (never on a command line, never in tool
  parameters).
- **FR-013**: Every wait and transfer of a call (a StartCLI concurrency slot, the server's login
  credentials, the version check, the download, a free slot for building the excerpt) MUST be
  bounded by the node's `cliExportTimeout`; exceeding it is `TIMEOUT` and no file remains (FR-011).
  Credentials read only for masking follow FR-010. The call MUST count against the StartCLI
  concurrency limit of that server. Building the excerpt (bounded by the 50 MiB window, FR-009) and
  moving the downloaded file to its final name are completed even if the deadline passes
  meanwhile, so that no partial file is left behind.
- **FR-014**: The tool MUST be annotated as not read-only for the local machine
  (`readOnlyHint: false`, `destructiveHint: false`, `idempotentHint: false`,
  `openWorldHint: true`), so that clients ask before it writes a file with clear-text content. It
  changes nothing on INUBIT.
- **FR-015**: Each call MUST produce an audit record (timestamp, tool, group and server, log,
  `tailLines`, `maxChars`, whether `contains` was given, file path, size, outcome). The `contains`
  text itself is not audited (it may be a guessed secret). Log content is never written to the
  audit log or the server log at any level.
- **FR-016**: The configuration summary (startup log and `--check-config`) MUST list per group
  whether the unsupported server-log read is enabled; on a `production: true` group as a warning.
- **FR-017**: Both versions are supported by this feature only with contract tests against
  recorded, neutralized outputs of that version (Constitution V): for 8.1 and 9.0 the StartCLI
  output of `trace --download` and the answer of the internal download, recorded read-only on
  non-production servers. Recordings MUST NOT contain real log content; the log content of the
  fixtures is fictitious. If a 9.0 recording cannot be made, 9.0 is refused with
  `UNSUPPORTED_VERSION` ("not yet verified") for the affected log, as in feature 020.
- **FR-018**: All repository artifacts of this feature MUST use fictitious names only.

### Key Entities

- **Server log**: one of the two log files of one INUBIT node — kind (`trace`, `webservice`),
  node, size, content.
- **Downloaded log file**: the local copy — path, size, time of download.
- **Excerpt**: the masked, bounded tail or search result of the downloaded file.
- **Opt-in setting**: per group, boolean, default off, only for the web service log.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For an 8.1 and a 9.0 server without a cluster, an operator gets trace.log and (with
  the opt-in) webservice.log as a local file in one tool call each, without opening the Workbench
  (9.0 subject to FR-017).
- **SC-002**: With no `serverLogRead` opt-in, the web service log is never requested; the behaviour
  of every existing tool is unchanged (existing test suite passes, tool list differs only by the new
  tool).
- **SC-003**: In 100% of the tested cases, no credential value, masked value or authorization value
  appears in the result, the audit log or the server log.
- **SC-004**: In 100% of the tested cases, no existing file in the target folder is replaced and no
  partial file remains after a failure.
- **SC-005**: After a call, tracing state and log levels of the server are unchanged, checked in
  the live test.

## Assumptions

- The INUBIT client installation per version (as configured for StartCLI, feature 017) is present
  on the MCP host; the web service log reuses the helper mechanism of feature 020.
- The INUBIT user of the profile has the right StartCLI needs (System Administrator or "CLI login
  access") and may use the Cluster Manager's log view; otherwise the server answers `FORBIDDEN`.
- The server keeps a temporary copy of the web service log in its cache store while the download
  runs, as it does for the Workbench; this is not considered a change of the server.
- The internal interface may change with any INUBIT update; the feature reports unexpected answers
  instead of guessing.

## Out of Scope

- Rotated log files, `system.log`/`user.log` (covered by `query_logs`), the Workbench's own logs.
- Changing tracing or log levels (a possible later feature through StartCLI `trace`).
- Module input/output logging and test or watch-mode data (later features).
- INUBIT 6.1.
