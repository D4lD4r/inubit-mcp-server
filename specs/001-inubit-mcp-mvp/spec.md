# Feature Specification: INUBIT MCP Server MVP

**Feature Branch**: `001-inubit-mcp-mvp`

**Created**: 2026-10-01

**Status**: Draft

> Note: Customer identifiers neutralized in feature 002; git history unchanged.

**Input**: User description: "INUBIT MCP server (MVP) that lets an AI assistant (e.g. Claude Code) operate
and diagnose INUBIT Process Engine systems (baseline version 8.1.14, upgrade path to 9.x) through the
INUBIT REST API and the INUBIT command line interface (StartCLI). Runs locally on a developer workstation
next to the MCP client (stdio), where an INUBIT client installation (startcli) is available. One server
instance can address multiple configured environments (e.g. dev, test, prod); every tool takes the target
environment as a parameter and each environment has its own credentials, write permission, and production
classification. Read-only by default. P1 health & monitoring, P2 failure diagnosis, P3 inventory of
diagrams & modules, P4 guarded process control (restart error process, kill process) with audit record."

## Overview

Developers and operators of INUBIT integration platforms currently switch between the Workbench, the
server's log views, and command-line sessions to answer routine questions ("Is test up?", "Why did order
import fail last night?", "Which versions of workflow X exist on qa and is it active?"). This feature gives an AI
assistant a small, safe set of capabilities to answer such questions directly in the conversation, and —
only where explicitly permitted — to perform the two most common corrective actions on process instances.

**Actors**

- **Developer/Operator**: a person working with an AI assistant on their own workstation who configures
  the server with their INUBIT environments and asks questions or requests actions.
- **AI assistant**: the MCP client that discovers and invokes the server's capabilities on the
  developer's behalf.
- **INUBIT environment**: one INUBIT Process Engine server that the server can reach. Environments
  are grouped into **stages** (for example `dev`, `test`, `prod`); a stage consists of one or more
  standalone servers (today: 5 stages with one or two servers each). An environment is identified as
  `<stage>/<server>` (for example `test/inubit01`); a stage alone is identified by its name (`test`).
  In this specification, *environment* and *server* are synonyms; tool contracts use *server*.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Check environment health (Priority: P1)

A developer asks the assistant whether an environment is healthy ("Is test up and accepting work?").
The assistant checks the named environment and reports whether it is reachable and ready, whether
maintenance mode is active, the server version, and the current load picture (memory usage, threads in
use versus available, blocking queue fill level). The developer can also ask for an overview across all
configured environments at once.

**Why this priority**: it is the first question in nearly every support or deployment situation, it is
fully read-only, and it proves the end-to-end setup (configuration, connectivity, credentials) that all
other stories rely on.

**Independent Test**: configure one reachable environment and one unreachable environment, ask for the
health of each and for an overview of all; verify the reported status, version, maintenance flag, and
load figures match what the INUBIT system shows, and that the unreachable environment is reported as such
without blocking the others.

**Acceptance Scenarios**:

1. **Given** a configured, running environment `test`, **When** the developer asks for its health,
   **Then** the assistant receives reachability, readiness, maintenance-mode state, server version, and
   memory/thread/blocking-queue figures for `test`.
2. **Given** environment `test` is in maintenance mode, **When** health is requested, **Then** the result
   states that maintenance mode is on and that the server is therefore not processing normally.
3. **Given** environment `dev` is unreachable, **When** health is requested, **Then** the result reports
   `dev` as unreachable with the likely cause (for example connection refused, timeout, certificate
   problem, authentication rejected) within the configured timeout, instead of failing silently or
   hanging.
4. **Given** three configured environments, **When** the developer asks for an overview, **Then** one
   result lists the health summary of every environment, including those that could not be reached.
5. **Given** a request names an environment that is not configured, **When** the capability is invoked,
   **Then** it is rejected with a message listing the configured environment and stage names.
6. **Given** stage `test` consists of the standalone servers `test/inubit01` and `test/inubit02`, **When** the
   developer asks for the health of stage `test`, **Then** the result contains one health summary per
   server of that stage.
7. **Given** load metrics are not available on an environment (for example not licensed), **When** health
   is requested, **Then** all other health information is still returned and the missing part is marked
   as unavailable with the reason.

---

### User Story 2 - Diagnose failed and hanging processes (Priority: P2)

A developer asks why something failed ("Why did the order import fail on test last night?"). The
assistant finds process instances in error state or hanging, narrowed by workflow name, time range, and
status, and retrieves the related log entries (system log with workflow executions, queue log, connection log, and
other INUBIT logs) filtered by time, workflow, and severity. With this, the assistant can explain which
module failed, when, with what error message, and how often it happened.

**Why this priority**: failure analysis is the most time-consuming routine task and benefits most from an
assistant that can correlate process state with log entries; it remains read-only.

**Independent Test**: on a test environment with at least one failed and one long-running process
instance, ask for failed processes of a given workflow in a given time range and for the related log
entries; verify that the returned instances and log entries match those visible in INUBIT and that
filters narrow the result as expected.

**Acceptance Scenarios**:

1. **Given** failed process instances of workflow `OrderImport` exist on `test`, **When** the developer
   asks for failed processes of `OrderImport` in the last 24 hours, **Then** the result lists each
   matching instance with its identifier, server, workflow, failing module, status, timestamp, and
   tag/version.
2. **Given** a process instance has been in a non-final state longer than the hanging threshold,
   **When** the developer asks for hanging processes, **Then** the instance is listed with how long it has
   been in that state.
3. **Given** a failed instance, **When** the developer asks for related log entries, **Then** the result
   contains the log entries for that workflow and time window, including error messages, ordered
   newest first.
4. **Given** a log query that matches more entries than the result size limit, **When** it is executed,
   **Then** the result returns the first page, the total number of matches, and how to request the next
   page.
5. **Given** a filter on severity, time range, or text, **When** the log query is executed, **Then** only
   entries matching all given filters are returned.
6. **Given** the developer names a log type that does not exist, **When** the query is executed, **Then**
   it is rejected with the list of supported log types.
7. **Given** stage `test` has two standalone servers, **When** the developer asks for failed processes on
   stage `test`, **Then** the result covers both servers and states for each instance on which server it
   lives.

---

### User Story 3 - Inspect inventory of diagrams and modules (Priority: P3)

A developer asks what is deployed ("Which versions of `OrderImport` exist on qa, which tags do they
carry, and is the workflow active?"). The assistant lists diagrams (technical workflows, business
process diagrams, and other diagram types) and modules on an environment, filtered by name, type, or
group, and shows the details of a single diagram or module: its version history (version number,
check-in user, check-in time, comment, tags), whether it is active, its group/owner, and its last
change. Diagrams and modules are looked up within the owning Workbench user group (`OWNERS` on all ACME
stages).

**Why this priority**: it supports comparison between environments and preparation of deployments, but
is less urgent than health and failure analysis.

**Independent Test**: on a test environment with a diagram that has several versions and tags, list
diagrams filtered by name and type, then request the details of that diagram; verify versions, tags, and
the active flag match the Workbench view.

**Acceptance Scenarios**:

1. **Given** environment `test`, **When** the developer lists technical workflows whose name contains
   `Order`, **Then** the result lists the matching diagrams with name, type, and group (versions are part of the
   details, see AS 2).
2. **Given** a diagram with several versions and tags, **When** its details are requested, **Then** the
   result lists all versions with check-in user, check-in time, comment, and tags, and states whether
   the diagram is active.
3. **Given** the developer asks for modules of a given type or group, **When** the list is requested,
   **Then** matching modules are listed with name, type, group (module type such as XSLT Converter or
   HTTP Connector), active flag, and last change.
4. **Given** the same diagram exists on `test` and `prod`, **When** the developer asks for its details on
   both, **Then** the results use the same structure so that the assistant can compare them directly.
5. **Given** a diagram or module name that does not exist, **When** details are requested, **Then** the
   result states that it was not found and, where possible, suggests similarly named items.

---

### User Story 4 - Restart or kill a process instance (Priority: P4)

A developer who has diagnosed a failure asks the assistant to restart a process instance in error state
("Restart process 4711 on test/inubit01") or to kill a stuck process instance. The action is only possible when
write access is enabled for that environment, is never possible on a production-classified environment
unless that environment explicitly opts in, and every attempt — successful, failed, or refused — is
recorded in an audit log.

**Why this priority**: it closes the loop from diagnosis to resolution, but it changes state on the
integration platform and therefore depends on the safety mechanisms being proven first.

**Independent Test**: with write access disabled, attempt a restart and verify it is refused and audited;
enable write access for a test environment, restart a process instance in error state and kill a stuck
instance, and verify both actions took effect in INUBIT and appear in the audit log; verify the same
action on a production-classified environment without opt-in is refused.

**Acceptance Scenarios**:

1. **Given** write access is disabled for `test/inubit01` (the default), **When** the developer requests a restart
   of a process instance on `test/inubit01`, **Then** the request is refused with an explanation of how write
   access is enabled, nothing is changed on `test/inubit01`, and the refusal is audited.
2. **Given** write access is enabled for `test/inubit01` and process instance 4711 is in error state, **When** a
   restart of 4711 is requested and confirmed, **Then** the instance is restarted, the result reports the
   outcome, and an audit record is written.
3. **Given** write access is enabled for `test/inubit01` and process instance 4712 is not in error state, **When**
   a restart of 4712 is requested, **Then** the request is rejected with the instance's actual state and
   nothing is changed.
4. **Given** write access is enabled for `test/inubit01`, **When** a kill of a process instance is requested and
   confirmed, **Then** the instance is removed, the result reports the outcome, and an audit record is
   written.
5. **Given** stage `prod` is classified as production and has no explicit production opt-in,
   **When** any restart or kill on a server of stage `prod` is requested, **Then** it is refused regardless of the write-access
   setting, and the refusal is audited.
6. **Given** write access is not enabled for any environment, **When** the assistant lists the server's
   capabilities, **Then** the write capabilities are either not offered or clearly marked as unavailable,
   so the assistant does not attempt them.
7. **Given** a write action is requested on an environment with server-side confirmation (the default),
   **When** the first request is made, **Then** nothing is changed; the result shows a preview (server,
   process identifier, workflow, current state, intended action) and a one-time confirmation code, and the
   action is executed only by a second request that carries this code before it expires.
8. **Given** a write action names a stage instead of a single server, **When** it is requested, **Then**
   it is rejected; write actions always target exactly one server.

---

### Edge Cases

- **Slow or hanging environment**: every call to an environment is bounded by a configurable timeout;
  on timeout the result says so and names the environment. An overview across environments never waits
  longer than the slowest timeout and still returns results for the responsive environments.
- **Authentication failure or insufficient rights**: reported as such (not as a generic error), naming
  the environment and — where INUBIT reports it — the likely missing right (for example CLI login access
  or System Administrator role), without echoing any credential.
- **Certificate problems**: an untrusted or mismatching server certificate is reported as a certificate
  error; the connection is not silently downgraded.
- **Maintenance mode**: when an environment answers every request with "service unavailable" because
  of maintenance mode, results state this explicitly instead of reporting a generic failure.
- **Missing local CLI installation**: if capabilities that depend on the local INUBIT command line client
  are requested but the client is not installed or not configured, the result says which capability is
  unavailable and why; capabilities that do not depend on it keep working.
- **CLI client version mismatch**: if the local command line client's version does not match the target
  environment's version, the result warns about it.
- **Large results**: lists and logs larger than the result size limit are paginated or truncated with an
  explicit marker and total count; they never flood the conversation.
- **Unexpected output from INUBIT**: responses that cannot be interpreted are reported as such, with a
  short excerpt for diagnosis (scrubbed of secrets), instead of returning partial or wrong data.
- **Concurrent state change**: if a process instance changes state between diagnosis and a requested
  restart/kill (for example it already completed), the action reports the actual current state and does
  not act on a stale assumption.
- **Invalid input**: process identifiers, workflow names, and filter values that do not match the
  expected format are rejected before anything is sent to INUBIT.
- **Sensitive content in logs**: log entries may contain business data; results contain what INUBIT
  logs report, but credentials of the configured accounts never appear in results.

## Requirements *(mandatory)*

### Functional Requirements

**Environments & configuration**

- **FR-001**: The server MUST support multiple stages, each with one or more INUBIT servers
  (environments), in one configuration. Every capability that targets an INUBIT system MUST take the
  target as an explicit input: read capabilities accept either a stage (`<stage>`, resolving to all its
  servers) or a single server (`<stage>/<server>`); write capabilities accept only a single server.
- **FR-002**: Each environment configuration MUST define: its stage, its server name, its connection
  address, whether write access is enabled
  (default: disabled), whether its stage is classified as production (a stage-level property), whether
  production write access is
  explicitly opted in (default: no), its write-confirmation mode (see FR-022), its timeout, and its INUBIT version line (8.1 or 9.x) if it cannot
  be detected automatically. Settings defined on a stage apply to all its servers unless a server
  overrides them.
- **FR-002a**: Credentials (username and password) MUST be supplied exclusively through operating-system
  environment variables following a fixed naming convention derived from stage and server name —
  server-specific variables take precedence over stage-wide variables — and never through the
  configuration file or capability inputs. The naming convention is documented for users. (Both values
  are supplied this way; FR-025 defines which of them are secrets and must never be exposed.)
- **FR-003**: The server MUST offer a capability that lists the configured environments with their
  name, stage, classification, and whether write access is enabled — without revealing any credential or
  connection secret.
- **FR-004**: The server MUST validate the configuration at startup and report misconfigurations
  (missing credential variables — naming the expected variable names, never their values —, unknown
  version line, unreadable files, local command line client not found)
  with a clear message, without revealing secrets.

**P1 — Health & monitoring**

- **FR-005**: The server MUST report, per environment: reachability, readiness, maintenance-mode state,
  server version, and — where the environment provides them — memory usage, threads in use versus
  available, and blocking queue fill level.
- **FR-006**: The server MUST offer a health overview across all configured environments, or across the
  environments of one stage, in a single request, querying environments independently so that one failing environment does not prevent results
  for the others.
- **FR-007**: The server MUST report system information of an environment (version, platform/runtime
  details, tracing state) on request.
- **FR-008**: When a part of the health information is unavailable, the server MUST return the
  remaining information and mark the missing part with the reason.

**P2 — Failure diagnosis**

- **FR-009**: The server MUST list process instances of an environment filtered by any combination of
  workflow name, status (at least: error, running/active, waiting/queued), and time range.
- **FR-010**: The server MUST identify hanging process instances, defined as instances in a non-final
  state (a state in which the instance is still expected to progress; the exact set of INUBIT states is
  fixed in the data model) longer than a threshold that is configurable per request and per environment (default: 60
  minutes), and report how long each has been in that state.
- **FR-011**: The server MUST retrieve entries from the INUBIT logs (at least: system log (which
  records workflow executions; the process log endpoint is defective on 8.1.17), queue log,
  connection log, scheduler log, audit log) filtered by any combination of time range, workflow,
  severity/status, and free text.
- **FR-012**: For log and process queries, the server MUST support paging (offset and page size) and
  MUST return the total number of matches together with each page.
- **FR-013**: Results for a single process instance MUST include enough information to correlate it
  with log entries (identifier, workflow, module, status, timestamp of the current state, tag/version).

**P3 — Inventory**

- **FR-014**: The server MUST list diagrams of an environment filtered by name (substring), diagram type
  (at least technical workflow; also business process diagram, process map, organization diagram,
  system diagram where available), and group.
- **FR-015**: The server MUST list modules of an environment filtered by name, type, and group
  (module type), including modules that no workflow uses, and state for each module which
  workflows use it (from the modules used by the workflows, not only the workflow a connector is
  bound to). If the usage could not be determined completely, the result MUST say so instead of
  reporting modules as unused.
- **FR-016**: The server MUST show details for a single diagram or module: version history (version
  number, check-in user, check-in time, comment, tags), active flag, group/owner, and last-change
  information where INUBIT provides it. INUBIT 8.1 exposes no "active version" marker; the server MUST
  NOT infer one.
- **FR-016a**: Diagrams and modules MUST be looked up within a configurable owning Workbench user or
  user group per stage (default `OWNERS`).
- **FR-016b**: Because inventory data may take several seconds to collect, the server MAY cache it per
  server for a configurable time; cached results MUST state when the data was collected, and the
  assistant MUST be able to request fresh data.
- **FR-017**: Inventory results MUST use the same structure for every environment and version line so
  that results from different environments can be compared directly.

**P4 — Process control (write)**

- **FR-018**: The server MUST offer restarting a single process instance that is in error state, and
  killing a single process instance. Bulk operations (for example "delete all failed processes") are out
  of scope.
- **FR-019**: Write capabilities MUST be refused unless write access is enabled for the target
  environment. For an environment classified as production, they MUST additionally be refused unless
  production write access is explicitly opted in for that environment.
- **FR-020**: Before restarting, the server MUST verify that the instance is currently in error state
  and refuse otherwise, reporting the actual state.
- **FR-021**: Write capabilities MUST be identifiable as state-changing and destructive to the AI
  assistant, so the assistant (and its user) can require confirmation before invoking them.
- **FR-022**: Write capabilities MUST require explicit confirmation before changing anything. The
  confirmation mode is configurable per environment:
  - `server` (default): two-step confirmation enforced by the server. The first request changes nothing
    and returns a preview of the intended action plus a one-time confirmation code; only a second
    request carrying that code executes the action. A code is bound to exactly one environment, action,
    and process instance, can be used once, and expires after 5 minutes (configurable).
  - `client`: the action executes on the first request; confirmation relies on the AI assistant asking
    its user (see FR-021). This mode MUST NOT be configurable for production-classified environments.
  Both the preview step and the execution step are audited (FR-023).
- **FR-023**: Every invocation of a write capability — executed, failed, or refused — MUST produce an
  audit record with timestamp, environment, capability, sanitized inputs, acting INUBIT account, and
  outcome. Audit records MUST be persisted locally in an append-only form that survives server restarts.

**Cross-cutting**

- **FR-024**: The server MUST be read-only by default: with a configuration that does not explicitly
  enable write access, no capability can change state on any INUBIT environment.
- **FR-025**: Secret credentials MUST NOT appear in any capability result, error message, log output, or
  audit record, and MUST NOT be exposed to other local users or processes while the local command line
  client is executed (for example via the process list). In this requirement and in SC-006, "credentials"
  means the secrets: passwords, the Basic-auth token derived from them, and trust-store passwords. The
  username is a non-secret identifier: it is required for audit attribution (it appears in audit
  records) and it is passed to the command line client as an argument (`-u`), because the client offers
  no other way to receive it.
- **FR-026**: All results MUST be structured, with a stable shape per capability, and bounded in size;
  oversized results MUST be paginated or truncated with an explicit marker and the total count.
- **FR-027**: Failures MUST be returned as actionable results stating what failed, the likely cause, and
  a suggested next step; they MUST distinguish at least: environment unknown, unreachable, timeout,
  certificate error, authentication failed, insufficient rights, maintenance mode, not found, invalid
  input, local command line client unavailable, unexpected response.
- **FR-028**: Every capability MUST validate its inputs before contacting an environment and reject
  invalid inputs with a description of the expected format.
- **FR-029**: The server MUST work against INUBIT 8.1.17 (the version running on DEV and QA, verified
  2026-10-01) for all stories in this specification; other 8.1.x patch levels are supported when a
  matching local command line client is configured for that stage or server. INUBIT
  9.x is not supported in this MVP, but all version-specific behaviour MUST be isolated so that 9.x
  support can be added later without changing the capabilities offered to the AI assistant. When an
  environment reports a version line other than 8.1, the server MUST warn that it is unsupported.
- **FR-030**: The server MUST write its own operational log (not the audit log) with configurable
  verbosity, kept separate from the channel used to talk to the AI assistant.

### Key Entities

- **Stage**: a named group of environments (for example `dev`, `qa`, `prod`); one or more standalone
  servers per stage; carries the production classification.
- **Environment**: one named INUBIT Process Engine server. Attributes: name, stage, connection address,
  version line, production classification, write-access flag, production write opt-in, write-confirmation mode, timeout,
  hanging threshold, reference to credentials. Credentials themselves are never part of any result.
- **Health Report**: point-in-time status of one environment: reachability, readiness, maintenance mode,
  version, load figures (memory, threads, blocking queue), unavailable parts with reasons, timestamp.
- **Process Instance**: one execution of a workflow on an environment: identifier, environment, workflow, current
  module, status, timestamp of the current state, time in current state, tag/version, node.
- **Log Entry**: one record from an INUBIT log: log type, timestamp, severity/status, workflow, module,
  message, and process identifier where present.
- **Diagram**: a modeled artifact (technical workflow, business process diagram, process map, …): name,
  type, group, owner, version history with tags, active flag, last change.
- **Module**: a configured component (possibly unused by any workflow): name, type, group (plugin type),
  owner, the workflows that use it, version history with tags where available, active flag, last
  change.
- **Audit Record**: an immutable record of a write-capability invocation: timestamp, environment,
  capability, step (preview or execution), sanitized inputs, acting account, outcome, and
  failure/refusal reason.
- **Confirmation Code**: a one-time token issued by a preview step; bound to one environment, action,
  and process instance; single use; expires.
- **Page**: a bounded slice of a larger result: items, offset, page size, total count, truncation marker.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer can go from an empty setup to a first successful health check of one
  environment in under 15 minutes, following only the project's setup documentation.
- **SC-002**: A health check of a single responsive environment returns within 5 seconds; an overview of
  all configured environments (5 stages, up to 10 servers) returns within 10 seconds even if one of them
  is unreachable.
- **SC-003**: In a walkthrough with at least 5 real failure cases from a test environment, the assistant
  identifies the failed workflow, failing module, and error message for at least 4 of them using only
  this server's capabilities, without the developer opening the Workbench.
- **SC-004**: The time to answer "why did process X fail?" drops to under 2 minutes, compared with the
  current manual approach (baseline to be measured during the walkthrough).
- **SC-005**: With a default configuration, 100% of write attempts in the test suite are refused and
  audited; with production classification and no opt-in, 100% of write attempts are refused regardless of
  other settings; with server-side confirmation, 100% of executions without a valid, unexpired, matching
  confirmation code are refused.
- **SC-006**: In a review of all results, error messages, operational logs, and audit records produced
  by the test suite and the walkthrough, zero credentials (secrets as defined in FR-025) are found;
  during command line client execution, no secret is visible in the system's process list.
- **SC-007**: No single result exceeds the configured size limit; every truncated or paginated result
  states the total number of matches.
- **SC-008**: Inventory details of the same diagram on two environments can be compared by the
  assistant field by field, and versions, tags, and the active flag match the Workbench in 100% of the
  walkthrough cases.

## Assumptions

- **Users and setup**: each developer runs the server on their own workstation for their own use;
  multi-user or remote/shared hosting is out of scope for this MVP.
- **Local INUBIT client**: an INUBIT client installation matching the servers' patch level (providing the
  command line client) is installed on the workstation; capabilities that do not need it work without
  it.
- **Credentials**: each developer provides their own personal INUBIT accounts as environment variables
  on their workstation (not a shared technical account); often one account is valid for all servers of a
  stage, hence the stage-wide fallback (FR-002a), so INUBIT's own audit trail and the server's audit records attribute actions
  to a person. Read capabilities require an account with the rights to read logs, monitoring data, and
  models (process lists come from the queue log via the REST interface); process control (restart,
  kill) and the inventory exports via the command line client require System Administrator or "CLI
  login access" rights.
- **Interfaces**: the server uses only the INUBIT interfaces documented by the vendor for the respective
  version (see `docs/research/inubit-interfaces.md`); undocumented interfaces and the support-only
  "cache mode" of the command line client are never used.
- **Load metrics**: the memory/thread/queue metrics depend on an INUBIT license entry; environments
  without it still provide the remaining health information (see FR-008).
- **Hanging threshold**: 60 minutes by default, adjustable per environment and per request.
- **Result size limit**: default 100 items or about 50 KB of text per result, whichever comes first;
  configurable.
- **Audit log location**: a local file in a configurable directory on the workstation; central audit
  collection is out of scope.
- **Log content**: log messages are returned as INUBIT reports them; retrieving full message payloads
  (business documents processed by workflows) is out of scope for this MVP.
- **Out of scope for this MVP**: deployment, import/export as capabilities offered to the assistant
  (read-only exports are used internally to collect inventory data), tag changes, maintenance-mode switching,
  user management, library/plug-in management, bulk process operations, human tasks/portal features,
  starting workflows, and any transport other than local stdio.
- **Landscape**: 5 stages, each with one or two standalone INUBIT servers (no clustering within the
  scope of this MVP); a development system and several test systems are available.
- **Verification on a real system**: several behaviours of the command line client (exit codes, passing
  the password without exposing it, exact output formats) are not documented and will be verified
  against the available non-production 8.1.17 systems (DEV, QA) during planning; recorded outputs from these
  systems serve as test fixtures.
- **INUBIT 9.x**: support is a follow-up feature once the upgrade is scheduled (FR-029).
