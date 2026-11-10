# Feature Specification: Read Queue Manager Entries (unsupported, opt-in)

**Feature Branch**: `020-queue-entry-read`

**Created**: 2026-11-10

**Status**: Draft

## Clarifications

### Session 2026-11-10

- Q: How is 9.0 support verified, given no 9.0 ERROR entry was available? → A: An ERROR entry is created on a non-production 9.0 test server and its response is recorded as a contract fixture within this feature (FR-019).
- Decisions taken by the creator in review round 1 (owner may override): opt-in only on a group (`unsupported.queueEntryRead`); payload masking by element/attribute/key name (FR-011); no audit record for reads, content never logged (FR-018); only ERROR entries; text 20,000 chars default, 100,000 max; variables 200 / 2,000 per value / 40,000 total.

**Input**: User description: "Read-only MCP tool `read_queue_entry` that returns the content of one
INUBIT Queue Manager entry: given a target server and a process ID (optionally the queue file name
as `query_logs` with `logType: queueLog` lists it), return the entry's metadata, the data stream
that ended in the queue (size-limited, text only; binary as size + hint) and the workflow
variables. Secrets masked like exports (`${secret:…}`). INUBIT offers no documented API for this:
the tool uses the internal interface of the Workbench's Queue Manager 'View file' action through
the local INUBIT client installation (8.1: Workbench SOAP servlet; 9.0: the Workbench's internal
REST API). The tool selects the implementation from the detected INUBIT version of the server.
Unsupported vendor functionality without stability guarantee: opt-in in the MCP configuration, off
by default, and the documentation of the opt-in flag says so explicitly."

## Background

When a process ends in the INUBIT Queue Manager in state ERROR, the queue row (`query_logs` with
`logType: queueLog`, `find_processes`) only names the entry: workflow, module, process ID, queue
file name, size and the short error text of the row. The actual cause is often only in the data
stream that ended in the queue (for example a SOAP fault returned by a validator) or in the
workflow variables at that moment. Today an operator has to open the Workbench's Queue Manager
and use "View file" to see it; an agent using this MCP server cannot.

INUBIT documents no client interface for this content: the documented REST API returns only the
queue row, and StartCLI's runtime-data export writes an archive on the server and blocks the
exported processes (it changes server state, so it is not an option). The Workbench itself reads
the content through an internal, undocumented interface (8.1: its SOAP servlet; 9.0: an internal
REST API under the Workbench's own path). A read-only spike against a real 8.1.17 server returned
the data stream (same size as the queue row) and the variables of an ERROR entry, and the entry
was unchanged afterwards; on a 9.0.10 server the same request reached the server's error-store
lookup (no ERROR entry existed there to read). A static review of the server code of both
versions found only reads of the error store for this request.

Because the interface is internal to INUBIT, the vendor gives no support and no stability
guarantee for it. This feature therefore exists only behind an explicit opt-in.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See why a process failed (Priority: P1)

An operator (or an agent working for them) sees a process in state ERROR in the Queue Manager
(through `find_processes` or `query_logs`). They ask for the content of that entry and get the
data stream that ended in the queue and the workflow variables, with secrets masked, without
opening the Workbench.

**Why this priority**: This is the whole purpose of the feature; the queue row alone often
contains only a fixed text such as the message of a Throw module.

**Independent Test**: On a server whose group has the opt-in, call the tool with the process ID
of an ERROR entry; the result contains the entry's metadata, the data stream as text (or a binary
hint) and the variables. Testable offline with a fake INUBIT client.

**Acceptance Scenarios**:

1. **Given** a group with the opt-in and one ERROR entry for process ID `1234`, **When** the tool
   is called with that server and process ID, **Then** the result contains the entry metadata
   (workflow, module, module type, state, time, data size, queue file name, error text of the
   row), the data stream as text and the list of variables with their values.
2. **Given** the same entry whose data stream is larger than the text limit, **When** the tool is
   called, **Then** the text is cut at the limit, marked as truncated, and the full size in bytes
   is reported.
3. **Given** an ERROR entry whose data stream is not text (for example a ZIP or PDF), **When** the
   tool is called, **Then** no content is returned for it, only its size, a detected kind where
   recognizable, and a hint that the content is binary.
4. **Given** variables named like credentials (for example `password`, `apiToken`,
   `authorization`) or an HTTP `Authorization` header among the variables, **When** the tool
   returns them, **Then** each such value is replaced by `${secret:variables.<name>}` and never
   appears in the result or the server log.
5. **Given** a configured INUBIT credential of the profile appears literally in the data stream or
   a variable value, **When** the tool returns it, **Then** it is scrubbed the same way as in all
   other tool results.
6. **Given** a data stream that contains a credential in an element, attribute or key named like a
   secret (for example a WS-Security `<wsse:Password>` or a JSON `"password": "…"`), **When** the
   tool returns the stream, **Then** that value is replaced by `${secret:data.<name>}` and the rest
   of the stream is unchanged.
7. **Given** two ERROR entries for process ID `1234` (a sub-workflow and its caller), **When** the
   tool is called without `fileName`, **Then** it fails with `INVALID_INPUT` listing both entries
   (file name, workflow, module, time); **When** it is called again with one of the file names,
   **Then** that entry is read.
8. **Given** an ERROR entry whose variables INUBIT reports as missing while the data stream is
   returned, **When** the tool is called, **Then** the result contains the metadata and the data
   stream and marks the variables as unavailable with the reason.
9. **Given** an entry with 250 variables, **When** the tool is called, **Then** 200 variables are
   returned with a truncation marker naming the total count.
10. **Given** the server does not answer within `cliTimeout`, **When** the tool is called,
    **Then** it fails with `TIMEOUT` and no partial content.

---

### User Story 2 - Opt in deliberately (Priority: P1)

The person who configures the MCP server decides per group (stage) whether this unsupported
functionality may be used. Without that decision, nothing changes for them.

**Why this priority**: The interface is unsupported by the vendor; using it must be a conscious
decision, and the documentation must make the risk explicit.

**Independent Test**: Start the server with a profile without the opt-in: the tool is not
offered. Add the opt-in to one group: the tool is offered, works for that group's servers and is
refused for other groups.

**Acceptance Scenarios**:

1. **Given** a profile in which no group has the opt-in, **When** the server starts, **Then** the
   tool is not offered at all and nothing else changes.
2. **Given** a profile in which group `test` has the opt-in and group `prod` has not, **When** the
   tool is called for a server of `prod`, **Then** it is refused with an actionable error that
   names the missing opt-in, and no request is sent to that server.
3. **Given** the opt-in is set, **When** the server starts or `--check-config` runs, **Then** the
   configuration summary lists, per group, that the unsupported queue-entry read is enabled.
4. **Given** the documentation of the opt-in flag, **When** an operator reads it, **Then** it
   states explicitly that the feature uses unsupported, undocumented INUBIT functionality without
   any support or stability guarantee from the vendor, that it may stop working or behave
   differently after any INUBIT update, and that results must not be relied on for anything but
   diagnosis.
5. **Given** the opt-in on a group with `production: true`, **When** the server starts, **Then**
   the startup log and `--check-config` show it as a warning.

---

### User Story 3 - Right implementation per INUBIT version (Priority: P2)

The operator does not choose how the content is read. The tool uses the implementation that
matches the INUBIT version detected for the addressed server, with the INUBIT client installation
configured for that version.

**Why this priority**: The profile may mix 8.1 and 9.0 servers (for example during a migration);
a wrong choice fails or, worse, reaches an interface that does not exist.

**Independent Test**: With a fake client per version, a call against a server detected as 8.1
uses the 8.1 implementation and the 8.1 client installation; a call against a 9.0 server uses the
9.0 implementation and the 9.0 client installation; a 6.1 or not yet detectable server is refused.

**Acceptance Scenarios**:

1. **Given** a server detected as INUBIT 8.1, **When** the tool is called, **Then** the 8.1
   implementation is used with the client installation configured for 8.1.
2. **Given** a server detected as INUBIT 9.0, **When** the tool is called, **Then** the 9.0
   implementation is used with the client installation configured for 9.0.
3. **Given** a server on a version this feature does not support (6.1, or a version line other
   than 8.1/9.0), **When** the tool is called, **Then** it is refused with `UNSUPPORTED_VERSION`
   naming the detected version and the supported lines.
4. **Given** the version of the server cannot be determined (server unreachable during
   detection), **When** the tool is called, **Then** it fails with the detection error and no
   content request is sent.
5. **Given** no INUBIT client installation is configured or usable for the server's version,
   **When** the tool is called, **Then** it fails with `CLI_UNAVAILABLE` naming the version and
   the configuration to set.

---

### Edge Cases

- **Several entries for one process ID**: candidates are the entries of the process ID in state
  ERROR. Exactly one candidate (without `fileName`) is read even if other entries of the process
  ID are in other states. Several candidates without `fileName`: `INVALID_INPUT` listing them
  (file name, workflow, module, time) so the caller can repeat the call with `fileName`.
- **No entry** for the process ID, or the given file name does not belong to it: `NOT_FOUND`.
- **Entries exist, none in ERROR** (WAITING, RETRY, QUEUED, PROCESSING), or `fileName` names an
  entry that is not in ERROR: refused with `INVALID_INPUT` ("only ERROR entries are supported")
  listing their states; no content request is sent.
- **Content response cannot be parsed as a whole** (for example the unsupported interface
  changed after an INUBIT update): `UNEXPECTED_RESPONSE`, whose message says that the
  unsupported interface may have changed.
- **Content response parsed, one part missing or reported as an error by INUBIT** (data stream or
  variables): the result contains the metadata and marks that part as unavailable with the
  reason (FR-012); not a failure of the whole call.
- **Content request reports that the entry does not exist**: the queue row is read again; row
  gone → `NOT_FOUND` ("the entry left the Queue Manager"); row still there → partial result as in
  the previous case.
- **Empty data stream** (size 0): the result says so; no content field.
- **Text or binary**: decided by FR-009's rule only (signature, NUL byte, share of control
  characters); INUBIT returns no reliable content type or encoding for the entry, so none is
  used. Text is decoded as UTF-8 if valid, otherwise as ISO-8859-1, and the charset used is named
  in the result.
- **Very many or very long variables**: the variable list is bounded (count, length per value and
  total) with an explicit truncation marker.
- **Group is `production: true`**: the group opt-in is the only switch; the configuration summary
  and the startup log name the opt-in on a production group as a warning.
- **Credentials for the server not yet available**: same behaviour as the other read tools of the
  server (error naming the missing secret, no request).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST offer a read-only tool `read_queue_entry` with the inputs
  `target` (required; exactly one server id `<group>/<node>`, a group id is rejected by the input
  schema), `processId` (required; the decimal Queue Manager id with the same pattern as
  `kill_process`, `^[1-9][0-9]{0,18}$`), `fileName` (optional; the queue file name as `query_logs`
  with `logType: queueLog` lists it in `fields.fileName`: 1–255 characters, no `/`, `\`, NUL or
  other control characters) and `maxChars` (optional; integer 1–100,000, default 20,000). There is
  no profile setting for the text limit.
- **FR-002**: The tool MUST be offered only if at least one opted-in node is not pinned to a
  version line this feature does not support and has an INUBIT client installation configured;
  otherwise it is not offered and nothing else changes. A call for a server of a group without the
  opt-in MUST be refused with `NOT_CONFIGURED` naming the missing setting, before any request to
  that server.
- **FR-003**: The opt-in MUST be the group setting `unsupported.queueEntryRead`, boolean, default
  `false`. It is accepted only directly on a group: on a node, in `defaults`, or any other key
  under `unsupported` is a startup error (unknown key) whose message does not echo the value. A
  value other than the boolean `true` or `false` MUST be a startup configuration error.
- **FR-004**: The documentation of the opt-in setting (setup guide and tool reference) MUST state
  explicitly that the feature uses unsupported, undocumented INUBIT functionality, that the vendor
  gives no support and no stability guarantee for it, that it may stop working or behave
  differently with any INUBIT update, that it is meant for diagnosis only, and that business data
  of the entry is returned to the client (masked only as described in FR-011, so credentials in
  unusual places may still appear). The tool description shown to clients MUST carry a short form
  of the same warning.
- **FR-005**: The tool MUST locate the entry through the documented Queue Manager view (the same
  source `find_processes` and `query_logs` use). Candidates are the entries of the process ID in
  state ERROR (with `fileName`: the entry with that file name). Only the content of an entry in
  state ERROR is read; the rules for several, none or non-ERROR entries are those of the edge
  cases above.
- **FR-006**: The tool MUST select the implementation from the detected INUBIT version of the
  addressed server: 8.1 → the Workbench's SOAP interface; 9.0 → the Workbench's internal REST
  interface; each through the INUBIT client installation configured for that version (the same
  configuration StartCLI uses). Other versions MUST be refused with `UNSUPPORTED_VERSION`.
- **FR-007**: The tool MUST use only the read request of the Workbench's "View file" action and
  MUST NOT send any request that changes the entry, the process or the server (no restart, kill,
  delete, modify, export or upload).
- **FR-008**: The result MUST contain the entry metadata: server, process ID, workflow, module,
  module type, state, time the entry entered the Queue Manager, queue file name, data size in
  bytes, and the error text columns of the queue row.
- **FR-009**: The data stream is **binary** if it starts with a known signature (ZIP, GZIP, PDF,
  PNG, JPEG, GIF, Java serialized object) or if its first 8 KiB contain a NUL byte or more than
  10 % control bytes (0x00–0x08, 0x0B, 0x0C, 0x0E–0x1F, 0x7F; bytes 0x80 and above never count);
  the window is the first min(8 KiB, size) bytes. Otherwise it is **text**, decoded as UTF-8 if the
  whole stream is valid UTF-8, otherwise as ISO-8859-1, with the charset named. UTF-16 text is
  classified as binary (NUL bytes) on purpose. Text MUST be returned cut at
  `maxChars` with an explicit truncation marker and the full size in bytes; binary content MUST be
  returned only as size, detected kind (where a signature matched) and a hint, never as content.
- **FR-010**: The result MUST contain the workflow variables of the entry, each with `name`
  (cut at 200 characters), `system` (`true` for INUBIT's own properties: the names `ISFileSize`,
  `ISCurrentTime`, `ISWorkflowName`, `ISModuleName`, `ISModuleId`, `ISProcessId`, `ISUserName`,
  `ISServerName`, `ISNodeName`, `ISGlobalProcessId`, `ISOverwriteSubWorkflowPriority`, and every
  name starting with `_hidden.`; all others `false`), `type` where INUBIT names one, and `value` as
  text. Values are
  classified like the data stream (FR-009); a binary value is shown as size and hint. Bounds: at
  most 200 variables (system variables included), 2,000 characters per value and 40,000 characters for all values together,
  each with an explicit truncation marker. The whole result is therefore at most about 140,000
  characters.
- **FR-011**: Masking, applied to the data stream and to every variable value before the result
  is built:
  - a variable whose name contains (case-insensitive) `password`, `passwd`, `pwd`, `secret`,
    `token`, `apikey`, `api_key`, `credential`, `authorization`, `private`, `keystore` or
    `cookie` gets the value `${secret:variables.<name>}`;
  - in text (data stream and variable values), the value of an XML element or attribute and of a
    JSON key or `name=value` pair whose local name contains (case-insensitive) one of the same
    terms is replaced by `${secret:data.<name>}`, leaving the rest of the text unchanged: for an
    element, the whole content between start and end tag (child elements included); for a JSON
    key whose value is an object or array, the whole value; for `name=value`, the value up to the
    first `&`, `;`, whitespace or line end;
  - an HTTP `Authorization` or `Proxy-Authorization` value and every `Basic`/`Bearer` credential
    in text is replaced by `${secret:authorization}`;
  - the known credentials of the profile are scrubbed as in every other result.
  Masking works on the text even if it is not well-formed XML or JSON.
- **FR-012**: When the content or variables cannot be retrieved while the entry exists, the
  result MUST still contain the metadata and MUST mark each missing part as unavailable with a
  reason.
- **FR-013**: Errors MUST use the existing error codes: `INVALID_INPUT` (several candidates, not
  ERROR, bad input), `NOT_FOUND`, `NOT_CONFIGURED` (group without the opt-in),
  `UNSUPPORTED_VERSION`, `CLI_UNAVAILABLE`, `AUTH_FAILED`, `FORBIDDEN`, `TIMEOUT`, `UNREACHABLE`,
  `TLS_ERROR`, `UNEXPECTED_RESPONSE` (whose message says that the unsupported interface may have
  changed).
- **FR-014**: Requests to the server MUST verify TLS exactly like the server's other client
  connections (trust store or pinned certificate, hostname verification setting of the group).
- **FR-015**: Credentials MUST be passed to the client side the same way StartCLI gets them
  (never on a command line, never in tool parameters).
- **FR-016**: Each call MUST be bounded by the node's `cliTimeout` and MUST count against the
  StartCLI concurrency limit of that server; waiting for a free slot is bounded as for StartCLI,
  then `TIMEOUT`.
- **FR-017**: The configuration summary (startup log and `--check-config`) MUST list per group
  whether the unsupported queue-entry read is enabled; on a `production: true` group it MUST be
  shown as a warning.
- **FR-018**: The data stream and variable values MUST never be logged at any level; logs name
  only the server, process ID, file name, sizes and the outcome. Reads are not audited, like the
  other read tools.
- **FR-019**: Both 8.1 and 9.0 are supported by this feature only with contract tests against
  recorded, neutralized responses of that version for an ERROR entry (data stream and variables)
  (Constitution V). The 8.1 recording comes from the spike server; for 9.0 an ERROR entry is
  created on a non-production 9.0 test server (by the operator, or by the agent only after the
  operator approves that concrete action) and its response is recorded during this feature. The
  feature is not complete before both recordings exist as fixtures. Fallback if no 9.0 ERROR
  entry can be recorded (no 9.0 test server, or the operator declines): the feature ships with a
  9.0 server refused with `UNSUPPORTED_VERSION` ("not yet verified"), the 9.0 code stays, and
  enabling it after the recording is a follow-up change.
- **FR-020**: All repository artifacts of this feature (tests, fixtures, documentation) MUST use
  fictitious names only.

### Key Entities

- **Queue entry**: one row of the Queue Manager in state ERROR — server, process ID, workflow,
  module, module type, state, time, queue file name, data size, error text columns.
- **Entry content**: the data stream of the entry (text with charset, or binary with size and
  kind) and the variables (name, type, value or placeholder, system or user).
- **Opt-in setting**: per group, boolean, default off; documented as unsupported.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For an ERROR entry of an opted-in 8.1 or 9.0 server, an operator gets the data
  stream and variables in one tool call, without opening the Workbench.
- **SC-002**: With no opt-in in the profile, the set of offered tools and the behaviour of every
  existing tool are unchanged (verified by the existing test suite and a tool-list comparison).
- **SC-003**: In 100% of the tested cases, no credential value, masked value or authorization
  value appears in the result or the server log.
- **SC-004**: After a call, the entry is unchanged in the Queue Manager (same state, time and
  size), checked in the live test against a real server.
- **SC-005**: Every refusal (no opt-in, wrong state, unsupported version, missing client) names
  the reason and the next step, and sends no content request to the server.

## Assumptions

- The INUBIT client installation per version (as configured for StartCLI, feature 017) is present
  on the MCP host; the feature reuses it and needs no additional vendor files.
- The INUBIT user of the profile may view the Queue Manager on the server (the same right the
  Workbench needs for "View file"); otherwise the server answers `FORBIDDEN`.
- Only ERROR entries are in scope for this feature. WAITING, RETRY and QUEUED entries use other
  variants of the same internal request and are left for a later feature once they are verified
  on a real server.
- The 9.0 implementation was verified live only up to the server's error-store lookup before
  this feature; the missing 9.0 recording is produced during the feature (FR-019).
- The internal interface may change with any INUBIT update; the feature detects unexpected
  answers and reports them as such instead of guessing.

## Out of Scope

- Any change to queue entries (restart, kill, delete, edit of data or variables) — existing tools
  cover restart and kill.
- Reading entries in states other than ERROR.
- INUBIT 6.1.
- Writing the full content to a file (only the bounded result is returned).
