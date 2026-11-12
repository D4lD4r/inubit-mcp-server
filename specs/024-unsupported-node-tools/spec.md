# Feature Specification: Unsupported Node Tools (node status, module I/O logging, node configuration, temporary log level)

**Feature Branch**: `024-unsupported-node-tools`

**Created**: 2026-11-12

**Status**: Draft

## Background

Features 020 and 021 opened the INUBIT client's internal interfaces for read-only diagnosis behind a
per-group opt-in (`unsupported.*`). The owner asked to adopt the further candidates found while
researching 021 (2026-11-12). Static analysis of the 8.1.17 and 9.0.10 client installations showed:

- **Node status**: the Workbench's Cluster Manager shows every node the cluster knows, including
  nodes that do not answer (only their name, no status), with status (`ACTIVE`, `MAINTENANCE`,
  `OFFLINE`), INUBIT version, zone, node ID and uptime; for one node also a details block (server,
  JDK and operating system, processors, maximum heap, cluster, node and zone name, internal
  addresses and file paths). `get_health` only reports what each configured server address says
  about itself; it does not show the cluster's view or nodes that are known but not configured or
  not answering.
- **Module input/output logging**: for a process run, the server lists the logged input and output
  messages of its System Connector and Message Logger Utility modules (an entry per module,
  direction and index) and streams one message on request. A message that no longer exists is
  delivered as an empty stream. The server checks neither group nor right beyond the login.
- **Node configuration value**: the server returns any node configuration property by name,
  including properties that hold secrets (the database connection with its password, the cluster
  password, the initial root user password, identity provider settings).
- **Temporary log level**: StartCLI's documented `trace` command raises the log level of a named
  logger on the connected node "temporarily" and resets all temporary levels. The change lives in
  the node's memory only, has no timer, applies to the exactly named logger, and the reset restores
  every temporary level on that node, also those set by other users. An unknown level text is taken
  as `DEBUG`; names outside the INUBIT packages can leave an extra log output behind that the reset
  does not remove. The persistent, cluster-wide trace settings of the same command are not touched.
- In both versions the server checks only the login for the node status and configuration reads
  (the right check of the Cluster Manager's server side is not enforced); a live check confirms
  this for a user without the configuration right.
- Test and watch-mode results (owner decision: out of scope) have no stateless read path: their
  files are only known from session-bound events after a test run or with watch mode on, both of
  which change the server.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See the cluster's view of its nodes (Priority: P1)

An operator wants to know which INUBIT nodes the cluster of a server knows, which of them answer,
which are in maintenance, which version they run and how long they have been up, and for one node
the details the Workbench shows (JDK, operating system, memory, addresses).

**Why this priority**: it answers "is the cluster complete and healthy" in one call, including
nodes the MCP profile does not configure, and it carries no risk (read only, no secrets).

**Independent Test**: with a fake INUBIT client per version, a group with the opt-in gets the list
of nodes and the details of one node; a group without it is refused before any request.

**Acceptance Scenarios**:

1. **Given** a group with `unsupported.nodeStatusRead: true` and a reachable server, **When** the
   tool is called for that server, **Then** the result lists every node the cluster knows with
   name, status, version, zone, node ID and uptime, and names the node that answered.
2. **Given** the cluster knows a node that does not answer, **When** the tool is called, **Then**
   that node is listed with its name and the status `UNKNOWN` (the answer carries no status), not
   left out.
3. **Given** a node name from the list, **When** the tool is called with it, **Then** the result
   also contains that node's details, and the result says that the details hold internal addresses
   and file paths.
4. **Given** a group without the opt-in, **When** the tool is called, **Then** it is refused with
   `NOT_CONFIGURED` naming `unsupported.nodeStatusRead`, and nothing is sent to INUBIT.
5. **Given** a single-node server, **When** the tool is called, **Then** it lists that one node.

---

### User Story 2 - Read the logged input and output of a process run (Priority: P1)

An operator diagnosing a failed or suspicious run of a process wants to see what a System Connector
or Message Logger Utility module received and sent in that run, as the Workbench's input/output
logging view shows it, without opening the Workbench.

**Why this priority**: it is the most frequent diagnosis need after the Queue Manager entry (020)
and the logs (021), and today it needs the Workbench.

**Independent Test**: with a fake INUBIT client, a run with logged messages is listed and one
message is read masked and bounded; a request for a message the listing did not return is refused
before any download.

**Acceptance Scenarios**:

1. **Given** a group with `unsupported.ioLogRead: true`, a process name and a process ID (as
   `query_logs` returns them), **When** the listing is requested, **Then** the result lists every
   logged message of that run with module, direction (`INPUT`/`OUTPUT`) and index, in the order
   the server returns them.
2. **Given** one message from that listing, **When** it is read, **Then** the result contains its
   text masked like a Queue Manager entry (020 rules) and cut at the requested maximum, its full
   size, and for binary content only the size and the detected type.
3. **Given** a message that is not in the server's listing for that run (a typo, another run, a
   built name), **When** it is requested, **Then** the call is refused with `INVALID_INPUT`, and
   no download is attempted.
4. **Given** a run without logged messages, **When** the listing is requested, **Then** the result
   is an empty list with a hint that input/output logging may be off for the process or its
   modules, or that the messages were already removed.
5. **Given** a listed message the server delivers as empty, **When** it is read, **Then** the
   result says the message is empty or no longer exists (the answer cannot tell them apart).
6. **Given** a group without the opt-in, **When** either is requested, **Then** it is refused with
   `NOT_CONFIGURED` naming `unsupported.ioLogRead`, and nothing is sent.

---

### User Story 3 - Read harmless node configuration values (Priority: P2)

An operator wants to check one node's configuration values that matter for diagnosis (operation
mode, cluster and zone name, local maintenance mode, cluster manager and debug switches, timeouts,
data source names, the cluster bind address) without access to the server's files.

**Why this priority**: useful but narrower than stories 1 and 2; the risk (secrets) is contained by
a fixed list.

**Independent Test**: with a fake INUBIT client, each listed name returns its value; a name outside
the list is refused before any request; the fake records that only listed names were ever sent.

**Acceptance Scenarios**:

1. **Given** a group with `unsupported.nodeConfigRead: true` and a name from the fixed list,
   **When** the value is requested for a node, **Then** the result contains the name, value, type,
   display name, description and whether it is editable or deprecated.
2. **Given** a name outside the fixed list (including the database connection, cluster password,
   initial root password and identity provider settings, or any other text), **When** it is
   requested, **Then** the call is refused with `INVALID_INPUT` listing the allowed names, and
   nothing is sent.
3. **Given** no name, **When** the tool is called, **Then** it returns all values of the fixed list
   for that node.
4. **Given** a name on the list that the node does not know (e.g. a 9.0-only name on 8.1),
   **When** it is requested, **Then** the result says the node has no such value.
5. **Given** a group without the opt-in, **When** it is called, **Then** it is refused with
   `NOT_CONFIGURED` naming `unsupported.nodeConfigRead`.

---

### User Story 4 - Raise a log level on one node for a limited time (Priority: P2)

An operator reproducing a problem wants more detail from specific INUBIT components on one node for
a few minutes, then download the trace log (021), and be sure the level goes back afterwards even if
nobody remembers to reset it.

**Why this priority**: valuable for diagnosis, but it changes a running server; it needs the write
guards and a guaranteed reset.

**Independent Test**: with a fake StartCLI and a fake clock, a set call raises the level for the
named loggers on the addressed node, records a lease, and the lease's expiry, a server shutdown or
the next start resets the node; refusals happen before any change.

**Acceptance Scenarios**:

1. **Given** a group with write access (and on production the production write opt-in) and
   `unsupported.logLevelWrite: true`, a node, one to five logger names below `com.inubit.ibis`, a
   level from `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` and a duration, **When** the set is
   confirmed (two-step confirmation as `restart_process`), **Then** the levels are raised on that
   node, a lease with its expiry is recorded, and the result names the node, loggers, level and
   expiry and says that the reset restores every temporary level on that node (also other users')
   and that levels can be lost earlier (restart, log configuration reload).
2. **Given** a running lease, **When** its time is up, **Then** the MCP server resets the temporary
   levels of that node and records the reset; the operator can see in the lease state that it
   happened.
3. **Given** a running lease, **When** the MCP server shuts down, **Then** it resets the node first;
   **Given** a lease left from a previous run (crash), **When** the server starts, **Then** it
   resets that node and records it.
4. **Given** a running lease, **When** the reset tool is called for the node, **Then** the node is
   reset and the lease ends.
5. **Given** a logger name outside `com.inubit.ibis`, an invalid level, more than five names, no
   duration or a duration over 60 minutes, **When** the set is requested, **Then** it is refused
   with `INVALID_INPUT` before any request.
6. **Given** the node that answers is not the requested node (for example behind a load balancer),
   **When** a set or reset is requested, **Then** it is refused before any change, naming both
   nodes.
7. **Given** a group without write access, without the production write opt-in on production, or
   without `unsupported.logLevelWrite`, **When** the set is requested, **Then** it is refused with
   the existing write refusal or `NOT_CONFIGURED`, and nothing is sent.

---

### User Story 5 - Right implementation per INUBIT version (Priority: P3)

The operator works with 8.1 and 9.0 servers; each tool must use the client of the node's version
and be refused on versions it is not verified for.

**Independent Test**: one profile with 8.1, 9.0 and 6.1 nodes; each tool uses the matching client
on 8.1 and 9.0 and is refused on 6.1 and unknown versions before any request.

**Acceptance Scenarios**:

1. **Given** an 8.1 or 9.0 node, **When** any tool is called, **Then** it uses the client
   installation configured for that version.
2. **Given** a 6.1 node, an INUBIT version above 9.0 or a version that cannot be detected,
   **When** any tool is called, **Then** it is refused before any request (`UNSUPPORTED_VERSION`
   or the detection's own error).
3. **Given** a version whose capability has no recorded contract test yet, **When** the tool is
   called, **Then** it is refused as "not yet verified" (as 021).

### Edge Cases

- A cluster node answers with a status the client does not know: shown as `UNKNOWN` with the raw
  text.
- The node details are missing or unreadable: the list is still returned; the details are reported
  as unavailable with the reason.
- The I/O log listing is very long: the result is bounded (at most 500 entries, the total count is
  always reported).
- A logged message is binary or not valid UTF-8: as `read_queue_entry` (020): binary by size and
  type; text decoded as ISO-8859-1 when not valid UTF-8.
- Credentials not available, TLS failure, maintenance mode: the node's usual errors, nothing sent.
- The log-level set succeeds on the node but recording the lease fails: the MCP server resets the
  node at once and reports the call as failed.
- An automatic reset fails (node unreachable): the lease stays open, the failure is logged and
  audited, and the reset is retried until it succeeds or the lease is older than 24 hours; the
  state shows it.
- Two log-level sets for the same node: the second extends the lease to the later expiry; one
  reset ends both (the reset is per node).
- The MCP server runs in read-only mode: the log-level tools are not offered.

## Requirements *(mandatory)*

### Functional Requirements

**General**

- **FR-001**: Every tool of this feature MUST be offered only when at least one node has the INUBIT
  client installation configured and its group sets the tool's opt-in; each opt-in is a group
  setting, boolean, default `false`: `unsupported.nodeStatusRead`, `unsupported.ioLogRead`,
  `unsupported.nodeConfigRead`, `unsupported.logLevelWrite`. A call for a server whose group lacks
  the opt-in MUST be refused with `NOT_CONFIGURED` before any request.
- **FR-002**: The documentation (setup guide, tool reference) and every tool description MUST state
  that the tool uses unsupported, undocumented INUBIT functionality, without vendor support or
  stability guarantee, for diagnosis only. The configuration summary MUST list each opt-in per
  group; an opt-in on a `production: true` group MUST be a startup warning.
- **FR-003**: Each tool MUST work on single-node servers and on cluster nodes, address exactly the
  configured server, and name the INUBIT node that answered.
- **FR-004**: Each tool MUST be supported on INUBIT 8.1 and 9.0 only with contract tests against
  recorded, neutralized answers of that version; until then that version is refused "not yet
  verified". 6.1, versions above 9.0 and undetectable versions MUST be refused before any request.
- **FR-005**: Connections, credentials, TLS checks, timeouts (`cliTimeout` for reads,
  `cliExportTimeout` for message downloads), concurrency limits, error codes and audit records MUST
  follow the existing tools (020, 021). Credentials MUST never appear in command lines, results,
  logs or audit records.
- **FR-006**: Only these INUBIT requests MAY be sent: login, the node list, one node's details, one
  node configuration value per allowlisted name, the I/O message listing of one run, the download
  of one listed message, StartCLI `trace -nl <level> -p <logger>` and `trace -r`, logout. No other
  `trace` form (`on`, `off`, `-l`) and no configuration write MAY ever be sent.

**Node status (US1)**

- **FR-010**: The tool MUST return every node the cluster knows with name, status (`ACTIVE`,
  `MAINTENANCE`, `OFFLINE`, or `UNKNOWN` when the answer carries none), INUBIT version, zone, node ID
  and uptime (as a duration), and the node that answered.
- **FR-011**: With a node name, the result MUST additionally contain that node's details as
  name/value pairs and a warning that they contain internal addresses and file paths. The node name
  MUST match a node of the list or be refused with `INVALID_INPUT`.

**Module I/O logging (US2)**

- **FR-020**: The listing MUST take a process name and a process ID, both checked against the
  patterns `query_logs` returns, and return each logged message with module name, module ID,
  direction and index, plus the total count; at most 500 entries, the rest only counted.
- **FR-021**: Reading MUST take the same run plus one message reference from the listing; the tool
  MUST fetch the listing again and refuse a reference that is not in it with `INVALID_INPUT`; the
  download name MUST be built only from the listed entry and the process name, never from other
  caller text.
- **FR-022**: The message content MUST be masked and bounded with the rules and limits of
  `read_queue_entry` (020): known credentials of every configured node, secret-named values,
  `maxChars` 1–100,000 (default 20,000) on the masked text, full size always reported, binary
  content only as size and detected type.
- **FR-023**: An empty answer MUST be reported as "empty or no longer available", not as an error.

**Node configuration value (US3)**

- **FR-030**: The tool MUST accept only names from a fixed list: `OperationMode`, `ClusterName`,
  `ZoneName`, `NodeName`, `LocalMaintenanceMode`, `ActivateClusterManager`, `ClusterDebug`,
  `ContainerShutdownOnNodeFailure`, `RegisterMBeans`, `RegisterInternalMBeans`,
  `RuntimeDataCacheXMXPercentage`, `socketTimeoutInMillisecs`, `indexEnabled`, `iterationCount`,
  the three data source location names, `ClusterBindAddress`, and on 9.0 `noOfRetries`,
  `retryInterval`. Any other name MUST be refused with `INVALID_INPUT` before any request.
- **FR-031**: The result MUST contain per name the value, type, display name, description, category
  and the editable/deprecated flags, or "not present on this node"; values MUST still pass the
  credential masking (defence in depth).
- **FR-032**: No configuration value MAY ever be changed.

**Temporary log level (US4)**

- **FR-040**: The set tool MUST be a write tool: offered only when write tools are offered, subject
  to `write.enabled`, `write.productionOptIn` on production groups, the node's confirmation mode
  (two-step confirmation with a preview, as `restart_process`) and `unsupported.logLevelWrite`.
- **FR-041**: Inputs MUST be checked before any request: one to five logger names, each matching
  `com.inubit.ibis` followed by one or more dot-separated Java identifiers; level one of `TRACE`,
  `DEBUG`, `INFO`, `WARN`, `ERROR`; duration 1–60 minutes, default 10.
- **FR-042**: Before a set or reset, the tool MUST ask the addressed server which node answers and
  refuse when it is not the node of the lease or the configured node name (if configured).
- **FR-043**: After a successful set the MCP server MUST record a lease (node, loggers, level,
  expiry, caller audit id) durably in its state directory, owner-only, and MUST reset the node with
  `trace -r` when the lease expires, before it shuts down, and at startup for leases left from an
  earlier run. A reset that fails MUST be retried (at least every minute) until it succeeds or the
  lease is 24 hours past its expiry, and MUST be audited each time.
- **FR-044**: The reset tool MUST reset the addressed node and end its lease; it MUST be offered
  wherever the set tool is and MUST also reset a node without a lease (cleaning up an earlier
  change).
- **FR-045**: Results MUST state that the reset restores every temporary level on the node, also
  those of other users, that the levels can be lost earlier (restart, log configuration reload),
  and the expiry time. A tool or field MUST show the open leases of the profile.

### Key Entities

- **Cluster node**: name, status, version, zone, node ID, uptime; details as name/value pairs.
- **I/O message reference**: process name, process ID, module name and ID, direction, index.
- **Node configuration value**: name, value, type, display name, description, category, flags.
- **Log-level lease**: node, loggers, level, set time, expiry, state (`ACTIVE`, `RESET`,
  `RESET_FAILED`), last reset attempt.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An operator sees all nodes of a cluster with their status in one call, in under
  10 seconds on a reachable server, including nodes that do not answer.
- **SC-002**: The logged input and output of a run's modules can be listed and read without the
  Workbench; no secret of a configured node appears unmasked in any result (100% of tested cases).
- **SC-003**: No configuration name outside the fixed list is ever sent to INUBIT (verified by
  tests on every refusal path).
- **SC-004**: Every raised log level is reset within one minute after its lease expires while the
  MCP server runs, and at the latest at the next start of the MCP server; no persistent trace
  setting is ever changed.
- **SC-005**: With no opt-in set, no request of this feature is ever sent and the existing tools
  behave unchanged (existing test suite passes; the tool list differs only by tools whose opt-in is
  set).

## Assumptions

- The client installation, credentials and TLS settings of features 017, 020 and 021 are reused.
- The I/O logging must be switched on for a process and its modules in INUBIT; the tool only reads
  what INUBIT logged and keeps (INUBIT deletes old messages by its own retention).
- Process name and process ID come from `query_logs`; the tool does not search runs.
- The log-level change affects only the exactly named logger (static analysis; confirmed live);
  the documentation says so.
- Read-only live checks on the non-production 8.1 and 9.0 test servers are allowed; the log-level
  write check on a test server needs the owner's approval of the concrete action.

## Out of Scope

- Test and watch-mode results, starting test runs, watch mode, `ProcessingData`.
- Cache operations, garbage collection, changing node configuration values.
- Re-running a process from the I/O logging view.
- Persistent trace settings (`trace on|off|-l`).
