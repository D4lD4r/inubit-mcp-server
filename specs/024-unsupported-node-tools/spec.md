# Feature Specification: Unsupported Node Tools (node status, module I/O logging, node configuration, temporary log level)

**Feature Branch**: `024-unsupported-node-tools`

**Created**: 2026-11-12

**Status**: Approved (spec reviews r1–r4)

## Clarifications

### Session 2026-11-12

- The owner chose the tools: node status, module input/output logging, node configuration values
  (own tool with a fixed allowlist) and a temporary log level with a mandatory duration and an
  automatic reset; test and watch-mode data, cache operations and garbage collection are out of
  scope.
- Decisions taken by the creator (owner may override): the I/O listing and the I/O read are one
  tool (the read is the listing call with a message reference, as the node details are the node
  list call with a node name); the reset of temporary log levels is a write tool with the same
  guards as the set; open leases are shown in `get_health`; every tool addresses exactly one
  server (no stage fan-out).
- The owner decided (2026-11-12): cleanup resets of recorded leases run even when write tools are
  off (read-only mode, opt-in or production opt-in removed). Reason: otherwise a raised level would
  stay active without limit. This is a deliberate deviation from Constitution I (a state change
  while write access is off; `trace --reset` also clears other users' temporary levels on that
  node), recorded in the plan's Complexity Tracking.

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
  delivered as an empty stream. The server checks no group; on 8.1.17 the listing needs a right
  of the configured user and is refused with `NotAllowedAccess` without it (`FORBIDDEN`; spike
  T018).
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
  this for a user without the configuration right. This also corrects the 021 research statement
  that the cluster download needs the configuration right.
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

1. **Given** a group with `unsupported.nodeStatusRead: true` and a reachable server, **When**
   `list_cluster_nodes` is called for that server, **Then** the result lists every node the cluster
   knows with name, status, version, zone, node ID and uptime, and names the node that answered.
2. **Given** the cluster knows a node that does not answer, **When** the tool is called, **Then**
   that node is listed with its name and the status `UNKNOWN`, without version, zone, node ID and
   uptime, not left out.
3. **Given** a node name from the list, **When** the tool is called with it, **Then** the result
   also contains that node's details, and the result says that the details hold internal addresses
   and file paths.
4. **Given** a group without the opt-in, **When** the tool is called, **Then** it is refused with
   `NOT_CONFIGURED` naming `unsupported.nodeStatusRead`, and nothing is sent to INUBIT.
5. **Given** a single-node server, **When** the tool is called, **Then** it lists that one node.
6. **Given** a `nodeName` that is not in the cluster's list, **When** the tool is called, **Then**
   it is refused with `INVALID_INPUT` naming the known nodes.

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
   `query_logs` returns them), **When** `read_io_log` is called without a message reference,
   **Then** the result lists every logged message of that run with module name, module ID,
   direction (`INPUT`/`OUTPUT`), index and a message reference, in the order the server returns
   them.
2. **Given** one message reference from that listing, **When** `read_io_log` is called with it,
   **Then** the result contains its text masked like a Queue Manager entry (020 rules) and cut at
   the requested maximum, its full size, and for binary content only the size and the detected
   type.
3. **Given** a message reference that is not in the server's listing for that run (a typo, another
   run, a built name), **When** it is requested, **Then** the call is refused with `INVALID_INPUT`,
   and no download is attempted.
4. **Given** a run without logged messages, **When** the listing is requested, **Then** the result
   is an empty list with a hint that input/output logging may be off for the process or its
   modules, that the messages may already be removed, or that the run may have executed on another
   cluster node.
5. **Given** a listed message the server delivers as empty, **When** it is read, **Then** the
   result says the message is empty or no longer exists (the answer cannot tell them apart).
6. **Given** a group without the opt-in, **When** the tool is called, **Then** it is refused with
   `NOT_CONFIGURED` naming `unsupported.ioLogRead`, and nothing is sent.
7. **Given** `maxChars` outside 1–100,000, **When** the tool is called, **Then** it is refused with
   `INVALID_INPUT` before any request.

---

### User Story 3 - Read harmless node configuration values (Priority: P2)

An operator wants to check the configuration values of the addressed node that matter for
diagnosis (operation mode, cluster and zone name, local maintenance mode, cluster manager and debug
switches, timeouts, data source locations, the cluster bind address) without access to the
server's files.

**Why this priority**: useful but narrower than stories 1 and 2; the risk (secrets) is contained by
a fixed list.

**Independent Test**: with a fake INUBIT client, each listed name returns its value; a name outside
the list is refused before any request; the fake records that only listed names were ever sent.

**Acceptance Scenarios**:

1. **Given** a group with `unsupported.nodeConfigRead: true` and a name from the fixed list,
   **When** `get_node_config` is called, **Then** the result contains, for the addressed node, the
   name, value, type, display name, description, category and whether it is editable or
   deprecated.
2. **Given** a name outside the fixed list (including the database connection, cluster password,
   initial root password and identity provider settings, or any other text), **When** it is
   requested, **Then** the call is refused with `INVALID_INPUT` listing the allowed names, and
   nothing is sent.
3. **Given** no name, **When** the tool is called, **Then** it returns all values of the fixed list
   for the addressed node.
4. **Given** a name on the list that the node does not know (e.g. `noOfRetries` on 8.1), **When**
   it is requested, **Then** the result says the node has no such value.
5. **Given** a group without the opt-in, **When** it is called, **Then** it is refused with
   `NOT_CONFIGURED` naming `unsupported.nodeConfigRead`, and nothing is sent.

---

### User Story 4 - Raise a log level on one node for a limited time (Priority: P2)

An operator reproducing a problem wants more detail from specific INUBIT components on one node for
a few minutes, then download the trace log (021), and be sure the level goes back afterwards even if
nobody remembers to reset it.

**Why this priority**: valuable for diagnosis, but it changes a running server; it needs the write
guards and a guaranteed reset.

**Independent Test**: with a fake StartCLI, a fake INUBIT client and a fake clock, a set call raises
the level for the named loggers on the addressed node and records a lease; the lease's expiry, a
server shutdown or the next start resets the node; refusals happen before any change.

**Acceptance Scenarios**:

1. **Given** a group with write access (and on production the production write opt-in) and
   `unsupported.logLevelWrite: true`, a node, one to five logger names below `com.inubit.ibis`, a
   level from `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` and a duration (default 10 minutes),
   **When** `set_temporary_log_level` is confirmed according to the node's confirmation mode (as
   `restart_process`), **Then** the levels are raised on that node, a lease with its expiry
   (counted from the executed change, not from the preview) is recorded, and the result names the
   node, the INUBIT node that answered, the loggers, level and expiry and says that the reset
   restores every temporary level on that node (also other users') and that levels can be lost
   earlier (restart, log configuration reload).
2. **Given** a running lease, **When** its time is up, **Then** the MCP server resets the temporary
   levels of that node, records and audits the reset, and `get_health` for that node no longer
   shows the lease as open.
3. **Given** a running lease, **When** the MCP server shuts down, **Then** it tries to reset the
   node first (bounded in time); **Given** a lease left from an earlier run whose server process
   no longer runs, **When** the server starts, **Then** it resets that node and records it.
4. **Given** a running lease, **When** `reset_temporary_log_level` is confirmed for the node,
   **Then** the node is reset and the lease ends.
5. **Given** a logger name outside `com.inubit.ibis`, an invalid level, more than five names or a
   duration outside 1–60 minutes, **When** the set is requested, **Then** it is refused with
   `INVALID_INPUT` before any request.
6. **Given** a set or reset for a node, **When** the INUBIT node that answers is not the node
   recorded in the open lease for that server, **Then** the call is refused before any change,
   naming both nodes.
7. **Given** a group without write access, without the production write opt-in on production, or
   without `unsupported.logLevelWrite`, **When** the set or the reset is requested, **Then** it is
   refused with the existing write refusal or `NOT_CONFIGURED`, and nothing is sent.
8. **Given** no lease for the node, **When** the reset is confirmed, **Then** `trace --reset` is
   sent, an audit record is written, and the result says no lease was open and names the INUBIT
   node that answered.
9. **Given** a `RESET_FAILED` lease with the reason "other node answers", **When** the reset is
   confirmed with its lease ID and `forget: true`, **Then** the lease ends as `FORGOTTEN` without
   any request, audited, and the result says the level may still be raised on that node.
10. **Given** a lease whose server entry was removed from the configuration, **When** 7 days have
    passed after its expiry, **Then** it ends as `FORGOTTEN`, audited; until then the startup log
    and `--check-config` show it.
11. **Given** a node whose lease is `RESET_FAILED`, **When** a new set is requested, **Then** it is
    refused before any change, naming the failed reset.

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
  status text.
- The node details are missing or unreadable: the list is still returned; the details are reported
  as unavailable with the reason.
- The I/O log listing is very long: the result lists at most 500 entries and always reports the
  total; an optional module filter narrows it; a read accepts any reference of the full server
  listing.
- A logged message is binary or not valid UTF-8: as `read_queue_entry` (020): binary by size and
  type; text decoded as ISO-8859-1 when not valid UTF-8.
- Credentials not available, TLS failure, maintenance mode: the node's usual errors, nothing sent.
- The log-level set succeeds on the node but recording the lease fails: the MCP server resets the
  node at once and reports the call as failed.
- An automatic reset fails (node unreachable): the lease becomes `RESET_FAILED`, the failure is
  logged and audited, and the reset is retried at least every minute while the server runs and at
  every start, until it succeeds, the reset tool forgets it, or it ends
  automatically as `FORGOTTEN` (FR-044).
- Two sets for the same node: the lease keeps the union of the loggers, the last level per logger
  and the later expiry; one reset ends it (the reset is per node).
- Two MCP server instances with the same profile: each lease names its owner instance; another
  instance takes over only leases whose owner instance no longer runs; lease updates are
  serialized.
- The MCP server runs with write tools off (read-only mode, or the opt-in removed): the log-level
  tools are not offered, but cleanup resets of recorded leases still run (owner decision); a lease
  that cannot be reset is a startup warning.

## Requirements *(mandatory)*

### Functional Requirements

**General**

- **FR-001**: The system MUST offer these tools; the `target` or `node` of every tool is exactly one
  server id `<group>/<node>` (a group id is rejected by the input schema): `list_cluster_nodes`
  (inputs `target`; optional `nodeName`), `read_io_log` (inputs `target`; `workflow`; `processId`;
  optional `message`: a reference from the listing; optional `module`: filter for the listing;
  optional `maxChars`), `get_node_config` (inputs `target`; optional `names`: list of allowlisted
  names), `set_temporary_log_level` (inputs `node`; `loggers`: 1–5 names; `level`; optional
  `durationMinutes`; optional `confirmationCode`) and `reset_temporary_log_level` (inputs exactly
  one of `node` or `leaseId`; `forget: true` only with and always with `leaseId`; optional
  `confirmationCode`; other combinations are refused with `INVALID_INPUT`).
- **FR-002**: Each tool MUST be offered only when at least one node has the INUBIT client
  installation configured and its group sets the tool's opt-in; each opt-in is a group setting,
  boolean, default `false`: `unsupported.nodeStatusRead` (`list_cluster_nodes`),
  `unsupported.ioLogRead` (`read_io_log`), `unsupported.nodeConfigRead` (`get_node_config`),
  `unsupported.logLevelWrite` (both log-level tools). A call for a server whose group lacks the
  opt-in MUST be refused with `NOT_CONFIGURED` before any request.
- **FR-003**: The documentation (setup guide, tool reference) and every tool description MUST state
  that the tool uses unsupported, undocumented INUBIT functionality, without vendor support or
  stability guarantee, for diagnosis only; for `read_io_log` also that messages can contain
  business and personal data that masking does not cover and that INUBIT requires a right of the
  configured user for the listing (`FORBIDDEN` without it; spike T018). The configuration summary
  MUST list each opt-in per group; an opt-in on a `production: true` group MUST be a startup
  warning.
- **FR-004**: Each tool MUST work on single-node servers and on cluster nodes, address exactly the
  configured server, and name the INUBIT node that answered.
- **FR-005**: Each tool MUST be supported on INUBIT 8.1 and 9.0 only with contract tests against
  recorded, neutralized answers of that version; until then that version is refused "not yet
  verified". 6.1, versions above 9.0 and undetectable versions MUST be refused before any request.
- **FR-006**: Connections, credentials, TLS checks, timeouts (`cliTimeout` for listings, values and
  log-level changes, `cliExportTimeout` for message reads), concurrency limits, error codes and
  audit records MUST follow the existing tools (020, 021). Credentials MUST never appear in
  command lines, results, logs or audit records.
- **FR-007**: Only these INUBIT requests MAY be sent: login, the node list, one node's details, the
  node configuration value of an allowlisted name, the I/O message listing of one run, the
  download of one listed message, StartCLI `trace --nodelevel <level> --package '<logger>'` and
  `trace --reset` (the long forms of the vendor's `-nl`, `-p` and `-r`, confirmed on 8.1 by the
  write spike), the question which node answers (the own-node lookup of the node status, or
  StartCLI `cachemode` `nodelist`, both read-only), logout. No other `trace` form (`on`, `off`,
  `--level`) and no configuration write MAY ever be sent.

**Node status (US1)**

- **FR-010**: `list_cluster_nodes` MUST return every node the cluster knows with name, status
  (`ACTIVE`, `MAINTENANCE`, `OFFLINE`, or `UNKNOWN` when the answer carries none or an unknown one,
  with the raw status text when there is one), INUBIT version, zone, node ID and uptime (as a
  duration; absent for a node that does not answer), and the node that answered.
- **FR-011**: With `nodeName`, the result MUST additionally contain that node's details as
  name/value pairs, masked with the credential masking, and a warning that they contain internal
  addresses and file paths. `nodeName` MUST match a node of the list or be refused with
  `INVALID_INPUT`.

**Module I/O logging (US2)**

- **FR-020**: Without `message`, `read_io_log` MUST check `workflow` and `processId` against the
  patterns `query_logs` returns and return the run's logged messages with module name, module ID,
  direction, index and a message reference, plus the total count; at most 500 entries (optionally
  only those whose module name or module ID equals `module` exactly), the rest only counted.
- **FR-021**: With `message`, the tool MUST fetch the run's full listing again and refuse a
  reference that is not in it with `INVALID_INPUT`; the download name MUST be built only from the
  listed entry and the process name, never from other caller text.
- **FR-022**: The message content MUST be masked and bounded with the rules and limits of
  `read_queue_entry` (020): known credentials of every configured node, secret-named values,
  `maxChars` 1–100,000 (default 20,000) on the masked text, full size always reported, binary
  content only as size and detected type.
- **FR-023**: An empty answer MUST be reported as "empty or no longer available", not as an error.

**Node configuration value (US3)**

- **FR-030**: `get_node_config` MUST read from the addressed server only and accept only names from
  this fixed list, the same for 8.1 and 9.0: `OperationMode`, `ClusterName`, `ZoneName`,
  `NodeName`, `LocalMaintenanceMode`, `ActivateClusterManager`, `ClusterDebug`,
  `ContainerShutdownOnNodeFailure`, `RegisterMBeans`, `RegisterInternalMBeans`,
  `RuntimeDataCacheXMXPercentage`, `socketTimeoutInMillisecs`, `indexEnabled`, `iterationCount`,
  `DataSourceLocation`, `LogsDBDataSourceLocation`, `RepositoryDataSourceLocation` (JNDI names of
  data sources, no credentials), `ClusterBindAddress`, `noOfRetries`, `retryInterval`. Any other
  name MUST be refused with `INVALID_INPUT` before any request.
- **FR-031**: The result MUST contain per name the value, type, display name, description, category
  and the editable/deprecated flags, or "not present on this node"; values MUST still pass the
  credential masking (defence in depth).
- **FR-032**: No configuration value MAY ever be changed.

**Temporary log level (US4)**

- **FR-040**: `set_temporary_log_level` and `reset_temporary_log_level` MUST be write tools: offered
  only when write tools are offered, subject to `write.enabled`, `write.productionOptIn` on
  production groups, the node's confirmation mode (as `restart_process`), annotated as changing
  the server, and requiring `unsupported.logLevelWrite`.
- **FR-041**: Inputs MUST be checked before any request: one to five logger names, each
  `com.inubit.ibis` followed by one or more dot-separated Java identifiers; level one of `TRACE`,
  `DEBUG`, `INFO`, `WARN`, `ERROR`; `durationMinutes` 1–60, default 10.
- **FR-042**: Before a change (set, reset, and every automatic reset) the MCP server MUST ask the
  addressed server which INUBIT node answers, record it in the lease, and refuse a set or reset when
  an open lease of that server names another node; an automatic reset then sends nothing and marks
  the lease `RESET_FAILED` with the reason "other node answers". Without an open lease a reset
  compares against nothing and records the answering node in its result. The documentation MUST
  state the precondition that a server entry addresses one node directly (as for 021) and the
  remaining risk that a load balancer may route the change to another node.
- **FR-043**: After a successful set the MCP server MUST record a lease (server, INUBIT node,
  loggers with level, set time, expiry, owner instance, audit id) durably, readable and writable
  only by the operating-system user, in the profile's data directory, serialized against concurrent
  instances. The owner instance is identified by process ID plus process start time. A set that
  merges into an existing lease makes the setting instance the owner. The owner resets the node with
  `trace --reset` when the lease expires (within one minute) and before it shuts down (all its nodes
  in parallel, waiting at most `cliTimeout` in total; a lease not reset then stays open). A set for
  a node whose lease is `RESET_FAILED` MUST be refused before any change until that reset succeeded
  or the lease was forgotten. Every running instance MUST, at least every minute and at its start,
  take over and reset expired or `RESET_FAILED` leases whose owner instance no longer runs. These
  cleanup resets MUST run whenever all preconditions of FR-005 and FR-006 hold (node configured,
  client installation and credentials available, version supported and detectable), also when write
  tools are off (owner decision, see Clarifications); otherwise the lease is `RESET_FAILED` and a
  startup warning. A failed reset MUST be retried at least every minute while an instance runs and
  at every start until it succeeds, the reset tool forgets it, or it ends automatically as
  `FORGOTTEN` (FR-044). Every automatic reset, successful or failed, MUST produce an audit record
  (tool, server, node, lease, outcome).
- **FR-044**: `reset_temporary_log_level` with `node` MUST reset the addressed node and end its
  lease; it MUST also reset a node without a lease (cleaning up an earlier change). With `leaseId`
  and `forget: true` it MUST end a lease in state `RESET_FAILED` (any reason; other states are
  refused with `INVALID_INPUT`) without sending anything, audited, with a result saying the level
  may still be raised on that node until its restart; this is only possible while the lease's server
  is configured, and the guards of FR-040 are checked against that server's group. A lease whose
  server entry is no longer configured MUST end automatically 7 days after its expiry in the state
  `FORGOTTEN` (audited, a startup warning until then).
- **FR-045**: The set and reset results MUST state that the reset restores every temporary level on
  the node, also those of other users, that levels can be lost earlier (restart, log configuration
  reload), and the expiry. `get_health` MUST show the open and failed leases of a node (also when
  write tools are off); leases of servers that are no longer configured MUST appear in the startup
  log and the configuration summary (`--check-config`). Ended leases (`RESET`, `FORGOTTEN`) MUST be
  kept for 7 days, then removed.

### Key Entities

- **Cluster node**: name, status, raw status, version, zone, node ID, uptime; details as name/value
  pairs.
- **I/O message reference**: process name, process ID, module name and ID, direction, index.
- **Node configuration value**: name, value, type, display name, description, category, flags.
- **Log-level lease**: lease ID, server, INUBIT node, loggers with level, set time, expiry, owner
  instance, state (`ACTIVE`, `RESET`, `RESET_FAILED`, `FORGOTTEN`), last reset attempt and its
  reason.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An operator sees all nodes of a cluster with their status in one call, in under
  10 seconds on a reachable server, including nodes that do not answer.
- **SC-002**: The logged input and output of a run's modules can be listed and read without the
  Workbench; no secret of a configured node appears unmasked in any result (100% of tested cases).
- **SC-003**: No configuration name outside the fixed list is ever sent to INUBIT (verified by
  tests on every refusal path).
- **SC-004**: Every raised log level is reset within one minute after its lease expires while the
  MCP server runs, and otherwise at the next start of the MCP server; if the node cannot be reached,
  the lease is `RESET_FAILED`, shown in `get_health` and retried as in FR-043; no persistent trace
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
- Feature 021 is merged first. Feature 022 (full StartCLI and REST coverage, in progress) adds a
  coverage list of StartCLI commands and an area-based write guard; whichever merges second adds
  `trace --nodelevel`, `trace --reset` and `cachemode nodelist` to that list and uses the write
  guard then in place.

## Out of Scope

- Test and watch-mode results, starting test runs, watch mode, `ProcessingData`.
- Cache-changing operations, garbage collection, changing node configuration values.
- Re-running a process from the I/O logging view.
- Persistent trace settings (`trace on|off|-l`).
