# Feature Specification: Read-Only INUBIT 6.1 Support via StartCLI Only

**Feature Branch**: `011-inubit-61-cli-reads`

**Created**: 2026-10-31

**Status**: Draft

**Input**: User description (translated from German): "6.1, CLI only" — support INUBIT 6.1 nodes
read-only, using only the INUBIT command-line client (StartCLI), no REST.

## Context & Findings *(verified before writing this spec)*

Sources: the vendor documentation of inubit 6.1 (online help and manual, December 2016), kept
locally outside the repository, and the project's 8.1/9.0 research notes
(`docs/research/inubit-interfaces.md`).

- **The 8.1 REST access does not exist on 6.1.** INUBIT 6.1's REST interface ("External
  Interface") needs a separate license and offers only tasks, models, process instances, users,
  diagram images and reports. The endpoints the server uses for health, version and logs
  (`system/info`, `healthcheck`, `ready`, `metrics`, `log/`) are not part of it. The REST API was
  redesigned in 8.0 (standardized endpoints, JSON). This feature therefore uses no REST on 6.1.
- **StartCLI on 6.1 covers monitoring and process listing.** Documented direct options: the
  Process Engine's product version, alive check, JVM memory, counts of processes in
  Error/Processing/Waiting/Queued state, queue display, maintenance mode status; the command `ps`
  lists process instances (all, error, waiting, queued; row limit) with the columns node, owner,
  process id, priority, state, date, workflow and module. The documented columns contain no tag.
- **StartCLI on 6.1 lacks what other tools rely on.** Not documented in 6.1: killing or
  restarting a process, exporting or importing single modules, the version history option, the
  import protocol option, and the client's own trust-store and host-name options. Logs (System Log,
  Queue Manager log) are only readable through an "IS Configuration" module inside a workflow
  running on the server, not from outside.
- **StartCLI export on 6.1 exists for workflows and repository files** (by diagram group, type,
  owner and tag; by repository path). Whether its archive format matches the 8.1 format the
  workspace relies on is not documented.
- **Clients.** The 6.1 client declares support for servers from 4.0.1 to 6.1; the 8.1.17 client
  used today declares 4.0.1 to 8.1.17, so the existing client check (feature 009, FR-011) would
  accept a 6.1 node. Whether the 8.1.17 client works against a 6.1 server in practice is unverified.
  INUBIT 6.1 runs on Java 7, from patch 6.1.0.89 on Java 8; its examples use plain HTTP on port
  8000 and the StartCLI URL `<server>/ibis/servlet/IBISSoapServlet` (the server's default today).
- **Today a 6.1 node is mishandled.** With `AUTO`, the detection request (`system/info`) fails, the
  version stays unknown, reads run with the 8.1 REST adapters and an "unknown version" warning
  (and fail or mislead), writes are refused. A pin to a 6.1 version is accepted by the
  configuration but treated as "outside every declared range" (reads unverified).
- **The certificate pin is checked by the server's own REST client today** (feature 006). StartCLI
  connections rely on the client's trust-store option, which the 6.1 client does not document.
- **`list_nodes` shows one capability "diagnosis"** for `find_processes` and `query_logs` today.
- **Constitution V** names 8.1.x as the baseline and 9.x as the next version; adapters are selected
  by configuration or detected version. Supporting an older version needs an amendment that
  extends the same rule to 6.1 (minor version of the constitution).

## Clarifications

### Session 2026-10-31

- Q: Which channel may the server use for a 6.1 node? → A: StartCLI only; no REST request is sent
  to a 6.1 node (user input).
- Q: Is `export_artifacts` for 6.1 part of this feature? → A: Yes. The 6.1 archive format is
  verified on sample exports of a 6.1 node first; the export is built on what they show.
- Q: How is a 6.1 node identified? → A: Only by a pin (a line value `V6_1` or a concrete 6.1
  version such as `"6.1.0"`). `AUTO` stays REST-based; a 6.1 node on `AUTO` stays unknown, with a
  conditional hint to pin 6.1.

### Session 2026-10-31 (spec review round 1)

- Q: Who checks the certificate pin of an HTTPS 6.1 node? → A: The server, by its own TLS
  handshake to the node (no HTTP request) before StartCLI runs; the rules of feature 006 apply
  unchanged, including `disableHostnameVerification` together with a pin.
- Q: May the feature be complete with US1–US3 before the export (US4) exists? → A: Yes. Until
  US4 is done, the export is refused on 6.1 with `UNSUPPORTED_VERSION`; if no sample exports can
  be obtained, US4 moves to a later feature. (US5, added in this round, belongs to the core.)
- Q: May the read-only recordings (StartCLI outputs, sample exports) be made on a 6.1 node
  classified as production? → A: Yes, read-only, by hand, only after the person confirms the node
  and its classification in the chat and approves the recording; automated tests never target
  production.
- Q: How is the capability "diagnosis" split? → A: Into `process diagnosis` (`find_processes`) and
  `log diagnosis` (`query_logs`) for every version; the changed `list_nodes` output on 8.1 is a
  deliberate change, noted in the changelog.

### Session 2026-10-31 (planning)

- Q: How is a password-protected trust store handled? → A: By the existing rule, unchanged: a
  trust-store password on a node with a configured StartCLI is a configuration error (found in
  the code during planning; stricter than the per-node refusal drafted before).
- Q: What does `list_nodes` call the split capabilities? → A: `process_diagnosis` and
  `log_diagnosis` (the snake_case convention of `list_nodes`); messages say "process diagnosis"
  and "log diagnosis".
- Q: Is a raw node value `__NotAssigned__` kept? → A: No; `inubitNode` is absent, no extra field.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Check health and version of a 6.1 node (Priority: P1)

A person operating an older INUBIT 6.1 installation asks the assistant "Is the old server up?".
The server answers for the 6.1 node from StartCLI alone: reachable and logged in, alive, the
product version, maintenance mode, JVM memory and the number of processes per state, and says
which parts of a health report do not exist on 6.1.

**Why this priority**: The health check is the entry point of every diagnosis and the cheapest
proof that the 6.1 connection, credentials and client work.

**Independent Test**: With recorded StartCLI outputs of a 6.1 node (fixtures), call `get_health`
for that node and confirm the report fields, the parts listed as not available on 6.1, and that no
REST request was sent.

**Acceptance Scenarios**:

1. **Given** a node pinned to INUBIT 6.1 with a StartCLI installation, **When** `get_health`
   targets it, **Then** the report shows `reachable`, `status`, the product version as reported by
   StartCLI (labelled as server or client version per FR-006),
   the maintenance mode, the JVM memory and the process counts per state, and lists readiness,
   threads, licensed threads and blocking queue as unavailable with the reason
   `NOT_AVAILABLE_ON_6_1`.
2. **Given** the alive check answers negatively, **When** `get_health` runs, **Then** `status` is
   `ERROR` and the other parts that could be read are still reported.
3. **Given** the same node, **When** `get_health` runs, **Then** no REST request is sent to it.
4. **Given** a 6.1 node without a StartCLI installation, **When** `get_health` targets it, **Then**
   the node is reported with `CLI_UNAVAILABLE` and a next step naming the missing client, and the
   other nodes of the call are reported normally.
5. **Given** a group with an 8.1 node and a 6.1 node, **When** `get_health` targets the group,
   **Then** each node is reported by its own version and channel within the overview time budget;
   6.1 parts that do not finish in time are listed as unavailable with `TIMEOUT`.
6. **Given** a 6.1 node whose credentials are rejected, **When** `get_health` runs twice within
   60 seconds, **Then** only one login is attempted and the second report says why (credential
   guard as today).
7. **Given** a node pinned to 6.1 whose StartCLI reports another major.minor version, **When**
   `get_health` runs, **Then** the contradiction is reported with both versions (FR-006).

---

### User Story 2 - Find failed and hanging processes on a 6.1 node (Priority: P1)

The person asks "Which processes are in error on the old server?". The server lists process
instances of the 6.1 node in Error, Waiting, Queued or Processing state from StartCLI, with process
id, workflow, module, owner, state and time, and marks hanging ones like on 8.1.

**Why this priority**: Finding failed processes is the main diagnosis use case; on 6.1 it is the
only diagnosis that can be offered without logs.

**Independent Test**: With recorded `ps` outputs of a 6.1 node, call `find_processes` with
different states, the hanging filter, a workflow filter and a time range, and confirm the results,
the truncation marker and paging.

**Acceptance Scenarios**:

1. **Given** a 6.1 node with processes in Error and Waiting state, **When** `find_processes` asks
   for `ERROR`, **Then** only the instances in Error state are returned, newest first, with the
   fields of FR-010.
2. **Given** a hanging threshold of 60 minutes, **When** `find_processes` asks for hanging
   instances, **Then** only instances whose time in their current state exceeds 60 minutes are
   returned.
3. **Given** a filter by workflow name or time range, **When** `find_processes` runs on a 6.1
   node, **Then** the result contains only matching instances.
4. **Given** a `tag` filter, **When** `find_processes` runs on a 6.1 node, **Then** it is refused
   for that node with `UNSUPPORTED_VERSION`, naming the input `tag`.
5. **Given** the state `ACTIVE`, **When** `find_processes` runs on a 6.1 node, **Then** it is
   answered by filtering the full process listing for Processing instances (FR-010).
6. **Given** a process listing that reaches the row cap, **When** `find_processes` returns,
   **Then** the result carries a truncation marker and its total is marked as a lower bound, also
   when filters reduce the returned rows.
7. **Given** a listing with `Total: 0`, **When** `find_processes` returns, **Then** the result is
   empty, not an error.
8. **Given** a 6.1 result, **When** it is returned, **Then** it carries the next step "logs of
   INUBIT 6.1: read them in the Workbench" instead of suggesting `query_logs`.

---

### User Story 3 - Unavailable tools on 6.1 are refused clearly (Priority: P1)

The person or the assistant calls a tool that 6.1 cannot support (logs, inventory, restart/kill,
any development or deployment tool, end-to-end tests, and the export until US4 is done). The server
refuses it for the 6.1 node before anything is sent, naming the node, its version and the missing
capability.

**Why this priority**: Running 8.1 logic against 6.1 would fail or mislead; the refusals are what
makes partial support safe.

**Independent Test**: For every tool, call it on a 6.1 node (fixtures only) and confirm a refusal
with `UNSUPPORTED_VERSION` for the unsupported ones, with no StartCLI, TLS or REST contact.

**Acceptance Scenarios**:

1. **Given** a 6.1 node, **When** `query_logs` targets it, **Then** it is refused with
   `UNSUPPORTED_VERSION`, saying that logs of INUBIT 6.1 cannot be read from outside the server.
2. **Given** a 6.1 node with write access configured, **When** `restart_process` or
   `kill_process` targets it, **Then** it is refused with `UNSUPPORTED_VERSION`.
3. **Given** a group of an 8.1 node and a 6.1 node, **When** `query_logs` targets the group,
   **Then** the 8.1 node returns its logs and the 6.1 node a refusal in its own result entry.
4. **Given** a profile whose only nodes that could run a tool are pinned to 6.1, **When** the
   server starts, **Then** that tool is not announced (tool list rule of feature 009).
5. **Given** `list_nodes`, **When** it lists a 6.1 node, **Then** it shows the version, the
   channel "StartCLI only" and exactly the usable capabilities of the tool classification, without
   contacting the node; for an 8.1 node it shows `process diagnosis` and `log diagnosis` instead
   of `diagnosis`.
6. **Given** a 6.1 pin contradicted by an observed version, **When** `find_processes` targets the
   node, **Then** it is refused with `UNSUPPORTED_VERSION` naming both versions; `get_health`,
   `list_nodes` and the offline checks of `check_artifacts` still run.
7. **Given** a node on `AUTO` whose `system/info` request is answered with HTTP 404, **When** a
   read runs, **Then** the existing "unknown version" warning adds "if this node runs INUBIT 6.1,
   pin `V6_1`"; for any other detection failure the warning is unchanged.
8. **Given** a 6.1 node, **When** `check_artifacts` runs with module lookup, **Then** the offline
   checks run and the result carries the notice that the lookup was skipped; no StartCLI, TLS or
   REST contact.

---

### User Story 4 - Export workflows and repository files of a 6.1 node (Priority: P3)

The person asks to bring the workflows of a diagram group of the 6.1 node into the local
workspace, to read and compare them offline. The server exports them with StartCLI and writes them
as readable files with secrets replaced, as on 8.1.

**Why this priority**: Valuable for analysing or migrating an old installation, but it depends on
the undocumented archive format and on the secret replacement, the largest risk of this feature.
It is built only after sample exports of a real 6.1 node have shown the archive format (FR-020);
the feature can be complete without it (FR-023).

**Independent Test**: With recorded 6.1 export archives (neutralized), call `export_artifacts` for
a diagram group and a repository path and confirm the written files, the replaced secrets and the
commit.

**Acceptance Scenarios**:

1. **Given** a 6.1 node and a diagram group, **When** `export_artifacts` runs, **Then** the
   technical workflows are written to the workspace as readable files, every secret value is
   replaced by a placeholder, and one local commit records the export.
2. **Given** a repository path, **When** `export_artifacts` runs, **Then** the files below that
   path are written as on 8.1.
3. **Given** a request to export modules on their own, **When** it targets a 6.1 node, **Then** it
   is refused for that node with `UNSUPPORTED_VERSION` because 6.1 cannot export single modules.
4. **Given** an archive whose structure the server does not recognize, **When** the export runs,
   **Then** nothing is written to the workspace and the result names the unrecognized part
   (fail closed: no file with possibly unreplaced secrets).

---

### User Story 5 - Connections to a 6.1 node are as safe as to an 8.1 node (Priority: P1)

The person relies on the same safety guarantees for the 6.1 node as for 8.1: the certificate is
verified and pinned, the password never shows up in process lists, wrong configuration is reported
before anything is sent, and the old server is not flooded with client processes or logins.

**Why this priority**: These are the constitution's safety rules (II, III); partial support must
not weaken them.

**Independent Test**: With fake StartCLI launchers and a local TLS test server, run the 6.1 tools
and confirm the pin check, the password channel, the configuration findings and the process
limits.

**Acceptance Scenarios**:

1. **Given** an HTTPS 6.1 node whose certificate does not match the configured pin, **When**
   `get_health` runs, **Then** the node is reported with `TLS_ERROR` naming the pin mismatch and
   no StartCLI process is started (no login attempted).
2. **Given** an HTTPS 6.1 node whose certificate is not trusted by the configured trust store and
   no pin is set, **When** a StartCLI-backed tool runs, **Then** it fails with `TLS_ERROR` and no
   login is attempted.
3. **Given** a 6.1 node with `disableHostnameVerification: true`, a pin, and a client that has an
   option to skip host names, **When** a tool runs, **Then** the server's handshake checks the pin
   but not the host name, and the client is told to skip only the host name (feature 006 rules);
   without a pin the configuration is refused at startup as today.
4. **Given** the same node with a client that has no such option, **When** a StartCLI-backed tool
   runs, **Then** it fails with `TLS_ERROR` naming the missing client option, no StartCLI process
   is started, and `--check-config` warns about it.
5. **Given** a 6.1 node with a trust-store password and a configured StartCLI, **When** the
   configuration is loaded or `--check-config` runs, **Then** it is reported as a configuration
   error as today (StartCLI needs a password-less trust store); the password never reaches the
   client.
6. **Given** a 6.1 node whose StartCLI URL is overridden to another host or port than `baseUrl`,
   **When** a StartCLI-backed tool runs, **Then** the server's TLS check targets the StartCLI URL's
   host and port.
7. **Given** any StartCLI-backed tool on a 6.1 node, **When** it runs, **Then** the password
   appears in no process argument and no environment variable of the client process; it reaches
   the client only through its standard input.
8. **Given** a 6.1 node with an `http://` base URL and `allowInsecureHttp: true`, **When**
   `--check-config` runs, **Then** it warns that credentials travel unencrypted; without
   `allowInsecureHttp` it reports an error as today.
9. **Given** a profile with a 6.1 node, **When** `--check-config` runs, **Then** it shows the node
   as "INUBIT 6.1, StartCLI only", the available and refused capabilities, a warning if the node
   has no StartCLI installation, and contacts no INUBIT server.
10. **Given** five `find_processes` calls on the same 6.1 node at once, **When** they run, **Then**
   at most two client processes run concurrently on that node and the others wait at most the
   node's StartCLI timeout before failing with `TIMEOUT`.
11. **Given** a refusal for version reasons on a 6.1 node, **When** it happens, **Then** it is
   logged as a structured stderr record like the version refusals of feature 009.

---

### Edge Cases

- StartCLI output in German or English (dates, state names, labels) is parsed; output in any other
  language or an unknown format is `UNEXPECTED_RESPONSE`, never an empty result (FR-017).
- A `ps` row without module: the module is omitted from that instance. A node column
  `__NotAssigned__`: `inubitNode` is absent (the value carries no node). A raw state the
  server does not know: `state` is `OTHER` and `rawState` is kept, as on 8.1.
- The `ps` date turns out not to be the time of entering the state (FR-010): hanging and time in
  state are not computed, and the hanging filter and time range are refused.
- A 6.1 concrete pin with four parts (`"6.1.0.89"`): accepted; major.minor.patch is `6.1.0`, the
  fourth part is kept for display and ignored for matching and for the patch warning.
- The 6.1 client prompts for the password instead of reading it: the password still reaches it
  only through standard input (FR-011).
- The client declares a range that excludes 6.1 (e.g. a future client): StartCLI-backed tools are
  refused as today (feature 009, FR-011).
- The 8.1.17 client does not work against the 6.1 server: the node is configured with a 6.1
  client and its Java runtime (FR-013).
- The only 6.1 installation is classified `production`: the recordings of FR-020 are allowed only
  as stated there; automated or opt-in tests never target it.

## Requirements *(mandatory)*

### Tool classification on INUBIT 6.1

| Tool | Capability | 6.1 in this feature | Channel |
|---|---|---|---|
| `list_nodes` | – (configuration) | runs, shows the 6.1 node | none |
| `get_health` | monitoring | supported | StartCLI (+ TLS handshake for the pin) |
| `find_processes` | process diagnosis | supported | StartCLI (+ TLS handshake for the pin) |
| `query_logs` | log diagnosis | unsupported (refused) | – |
| `list_inventory`, `get_inventory_item` | inventory | unsupported (refused) | – |
| `export_artifacts` (workflows by diagram group, repository files by path) | workspace export | unsupported (refused) until US4 is done (FR-023); supported afterwards | StartCLI (+ TLS handshake for the pin) |
| `export_artifacts` (modules on their own) | workspace export | refused by input (FR-021) | – |
| `check_artifacts` | – (offline check) | runs; the optional module lookup on a 6.1 node is skipped with a notice | none |
| `restart_process`, `kill_process` | process control | unsupported (refused) | – |
| `import_artifacts`, `restore_backup`, `set_active`, `tag_artifacts` | development writes | unsupported (refused) | – |
| `deploy_release` | deployment | unsupported (refused) | – |
| `run_e2e_test` | end-to-end test | unsupported (refused) | – |

### Functional Requirements

#### Identification, capabilities, refusals

- **FR-001**: The server MUST support INUBIT 6.1.x nodes for the capabilities of the tool
  classification marked `supported`, using StartCLI only. It MUST NOT send any REST or SOAP
  request of its own to a node it treats as 6.1. The only contacts are StartCLI's own connection
  and, for HTTPS nodes, the server's TLS handshake for the pin check (FR-012), which sends no HTTP
  request.
- **FR-002**: A node MUST be treated as 6.1 only when its version setting pins 6.1: the new line
  value `V6_1` or a concrete version with major.minor 6.1 (three or four numeric parts, e.g.
  `"6.1.0"` or `"6.1.0.89"`, optionally with a suffix as today, e.g. `"6.1.0.89-HF1"`; a fourth
  part and a suffix are kept for display and ignored for matching and for the patch warning). `AUTO` MUST stay REST-based (feature 009). `--check-config` and `list_nodes`
  MUST accept and show `V6_1` like the other line values.
- **FR-003**: For a node on `AUTO` whose detection request is answered with HTTP 404, the
  existing "unknown version" warning MUST add the conditional hint "if this node runs INUBIT 6.1,
  pin `V6_1`". For every other detection failure the warning stays unchanged. This amends feature
  009 FR-005 (which forbids suggesting a pin the node is not known to run) by this one
  conditional hint.
- **FR-004**: The capability matrix of feature 009 MUST declare INUBIT 6.1 with the states of the
  tool classification. The capability "diagnosis" MUST be split into `process diagnosis`
  (`find_processes`) and `log diagnosis` (`query_logs`) for every version; on every version other
  than 6.1 both keep the state "diagnosis" has today. `list_nodes` and `--check-config` MUST show
  the new names (deliberate output change, SC-005).
- **FR-005**: The matrix MUST declare 6.1 `supported` for a capability only when that capability's
  contract tests run against recorded outputs of a real 6.1 node (FR-017, Constitution III);
  until then the capability is `unsupported` on 6.1. 009's `unverified` state (runs with the 8.1
  adapters) MUST NOT be used for 6.1.
- **FR-006**: The version read from the node by `get_health` on a 6.1 node MUST count as the
  node's observed version for the pin contradiction rule of feature 009 (FR-012 there), provided
  the recorded outputs confirm that the product-version option reports the server's version; if
  they do not, the value is reported as the client's and not used for this rule. While a 6.1 pin is
  contradicted, every tool that contacts the node, except `get_health`, MUST be refused for it
  with `UNSUPPORTED_VERSION` naming both versions; `list_nodes` and the offline checks of
  `check_artifacts` keep running. `get_health` keeps running through StartCLI and clears the
  contradiction when it observes 6.1 again.
- **FR-006a**: `check_artifacts` with module lookup for a 6.1 node MUST run its offline checks and
  skip the lookup, with a notice that modules of INUBIT 6.1 cannot be looked up; no StartCLI, TLS
  or REST contact.
- **FR-007**: Every tool, capability or input that 6.1 cannot serve MUST be refused with
  `UNSUPPORTED_VERSION` before any StartCLI run or TLS handshake, naming the node, its version and
  the missing capability or input, with a next step (use another node; for logs: read them in the
  Workbench). In calls addressing several nodes, the refusal MUST be per node (feature 009,
  FR-006). SC-003 covers every such refusal.

#### Health

- **FR-008**: `get_health` on a 6.1 node MUST report, from StartCLI: `reachable` (StartCLI
  connected and the login succeeded), `status`, the product version, the maintenance mode (with a
  warning when on, as on 8.1), the JVM memory (used, free, maximum, used percentage) and the number
  of processes per state (Error, Processing, Waiting, Queued). `status` MUST be `OK` when the
  alive check succeeds, `ERROR` when it answers negatively, and `UNKNOWN` when it could not be
  obtained (timeout, `CLI_UNAVAILABLE`, login refused, unreachable, TLS error); maintenance mode
  and process counts do not change `status`. Readiness, threads, licensed threads and blocking
  queue MUST be listed as unavailable with the reason `NOT_AVAILABLE_ON_6_1`. The local client
  check of feature 009 (FR-002 there) MUST still be reported.
- **FR-009**: Time budget: `get_health` on a single 6.1 node MUST return within 15 seconds; for 6.1
  nodes this replaces the 5-second single-node bound of feature 001 (8.1 and 9.x unchanged). When
  a call addresses several nodes, the overview budget of feature 001 (10 seconds) MUST hold for the
  whole call; 6.1 parts not finished in time MUST be listed as unavailable with `TIMEOUT`, and the
  other parts and nodes are still reported. `get_health` on a 6.1 node MUST start at most three
  StartCLI processes (besides the local client check of feature 009).

#### Processes

- **FR-010**: `find_processes` on a 6.1 node MUST return process instances from StartCLI's process
  listing with the 8.1 output shape: `processId`, `state` and `rawState`, `since`, `timeInState`,
  `hanging`, `workflow`, `module`, `owner`, `priority` and `inubitNode`, each where 6.1 provides
  it. Rules:
  - States: `ERROR`, `WAITING` and `QUEUED` from the listing of that state; `ACTIVE` by filtering
    the full listing for Processing instances; if the recorded outputs show that the full listing
    never contains Processing instances, `ACTIVE` MUST be refused with `UNSUPPORTED_VERSION`.
    No state given (all states) or `ACTIVE` among several states: the full listing, filtered.
    Several states without `ACTIVE`: one listing per state, merged. `hangingOnly` does not change
    which listings run.
  - Raw states map as: Error → `ERROR`, Processing → `ACTIVE`, Waiting and Retry → `WAITING`,
    Queued → `QUEUED`, with the German names taken from the recorded outputs; any other raw state →
    `OTHER` with `rawState` kept, as on 8.1.
  - `since` is the listing's date column. The recorded outputs MUST establish that it is the time
    of entering the current state; if it is not, `since`, `timeInState` and `hanging` MUST be
    omitted and `hangingOnly`, `hangingThresholdMinutes`, `since` and `until` refused with
    `UNSUPPORTED_VERSION`.
  - Dates without a time zone MUST be read as Europe/Berlin, as zone-less INUBIT dates are on 8.1
    (feature 001), and returned with offset. No new setting is introduced.
  - Filters by workflow, time range and hanging are applied by the server; `tag` MUST always be
    refused on 6.1 with `UNSUPPORTED_VERSION` (the listing has no tag column).
  - Sorting and paging as on 8.1 (newest first; `offset` + `limit` ≤ 10,000). The server MUST ask
    each listing for at most 10,000 rows; when any listing returns that many, the result MUST
    carry a truncation marker and the total MUST be marked as a lower bound, also after filtering
    and merging. Which rows the listing drops at its cap MUST be documented from a recording made
    with a small row limit (e.g. 5) on a listing with more rows.
  - If `since` is omitted (date column not the state-entry time), results keep the listing's
    order and the result says that it is not sorted by time.
  - A 6.1 result MUST carry the next step "logs of INUBIT 6.1: read them in the Workbench" instead
    of a `query_logs` suggestion.

#### Connection safety

- **FR-011**: StartCLI on a 6.1 node MUST receive the password only through its standard input,
  never as a command-line argument or environment variable of the client process (Constitution
  II), also when the client prompts for it interactively.
- **FR-012**: A 6.1 node is "HTTPS" when its effective StartCLI URL uses `https://`. For such a
  node, before the first StartCLI run of a tool call, the server MUST open its own TLS connection
  to the host and port of the effective StartCLI URL (handshake only, no HTTP request) and verify
  the certificate by the rules of feature 006: chain against the node's trust store, the pin if
  set, host name unless `disableHostnameVerification` is set together with a pin. On failure the
  call MUST end with `TLS_ERROR` for that node and no StartCLI process MUST be started.
  Certificate verification MUST never be switched off. Plain HTTP follows the existing
  `allowInsecureHttp` rule, applied to the effective StartCLI URL. A changed certificate is handled
  by the existing certificate-change procedure of feature 006.
- **FR-012a**: The StartCLI client MUST verify the certificate itself as well: it MUST be given the
  node's trust store, by its own option where it has one, otherwise by the trust-store settings of
  its Java runtime. Rules for what reaches the client:
  - A trust-store password MUST NOT appear in any argument or environment variable of the client
    process. The existing rule applies unchanged: a trust-store password on a node with a
    configured StartCLI is a configuration error (StartCLI needs a password-less trust store).
  - Host-name verification of the client MAY be skipped only through the client's own option and
    only under the feature 006 rule (`disableHostnameVerification` with a pin). If the node's
    client has no such option, a 6.1 node with `disableHostnameVerification: true` MUST be refused
    for StartCLI-backed tools with `TLS_ERROR` naming the missing client option; host-name
    verification MUST NOT be disabled by any other means.
  - Whether the client has these options is determined locally from the client (no INUBIT
    contact); if it cannot be determined, the option counts as absent (fail closed). A trust-store
    format the client's runtime cannot read (e.g. PKCS12 on Java 7) MUST be refused for that node
    with a next step naming a readable format.
  - These refusals (missing host-name option, unreadable trust-store format) use `TLS_ERROR` and
    happen before the TLS handshake of FR-012 and before any StartCLI run.
  - `--check-config` MUST report each of these cases, including an option that could not be
    determined, as a warning naming the node and the cause; the server still starts and the node's
    StartCLI-backed tools answer with the refusal.
- **FR-013**: Each node MUST be able to use its own StartCLI installation and Java runtime
  (existing per-node client settings), so that a 6.1 client with an older Java runtime can serve a
  6.1 node while other nodes use the 8.1 client. The client check of feature 009 (FR-011 there)
  applies unchanged: a client whose declared range excludes 6.1 refuses StartCLI-backed tools on
  that node.
- **FR-014**: A 6.1 node MUST still have a `baseUrl`; it gives the default StartCLI URL
  (`<baseUrl>/ibis/servlet/IBISSoapServlet`, valid for 6.1). An overridden StartCLI URL is used as
  today; the TLS check and the HTTP rule follow the effective StartCLI URL (FR-012).
  `--check-config` MUST show each 6.1 node as "INUBIT 6.1, StartCLI only" with its available and
  refused capabilities, MUST warn (not fail) for a 6.1 node without a StartCLI installation (the
  server starts; the node answers `CLI_UNAVAILABLE`), and MUST contact no INUBIT server (feature
  009, FR-011a).
- **FR-015**: At most two StartCLI processes that contact the node MUST run concurrently per 6.1
  node (the local client check of feature 009 does not count); further calls MUST wait at most the
  node's StartCLI timeout and then fail with `TIMEOUT`. Every StartCLI run is
  bounded by the node's StartCLI timeout (within `get_health` additionally by FR-009). The
  credential guard (one failed login per node per 60 s) applies to every StartCLI login.

#### Implementation boundaries

- **FR-016**: All 6.1-specific behaviour MUST live in 6.1-specific adapters selected by the node's
  version; tool logic MUST NOT branch on version strings (Constitution V).
- **FR-017**: The StartCLI output parsers for 6.1 MUST be tested against recorded 6.1 outputs
  (neutralized of customer identifiers), MUST parse German and English output, and MUST treat
  unrecognized output as `UNEXPECTED_RESPONSE`, never as an empty result. Fixtures reconstructed
  from the documentation may be used during development but MUST be replaced by recorded ones
  before the capability is declared `supported` (FR-005).
- **FR-018**: Existing behaviour for 8.1 and 9.x nodes MUST NOT change, apart from the deliberate
  changes listed in SC-005; in particular `AUTO` detection on non-6.1 nodes stays REST-based.
- **FR-019**: Every refusal for version reasons on a 6.1 node MUST be logged like the version
  refusals of feature 009 (structured, stderr; audited for write tools).

#### Recordings and export

- **FR-020**: The recorded outputs (health options, process listings in every state, and for US4
  sample exports of at least one technical-workflow diagram group with modules and secrets and one
  repository path) MUST be made read-only, by hand, against a real 6.1 node after the person has
  approved the recording in the chat. If the node is classified `production`, the person MUST
  additionally confirm the node and its classification. The recordings MUST be neutralized of
  customer identifiers and secret values before they enter the repository, and the differences to
  8.1 (output formats, archive format) MUST be documented in the research notes.
- **FR-021**: `export_artifacts` on a 6.1 node MUST export technical workflows by diagram group
  (with the modules the archive contains) and repository files by path, and write them to the
  workspace with the 8.1 guarantees: readable files, every secret value replaced by a placeholder
  before anything is written, key material withheld, one local commit, nothing sent anywhere.
  A request for modules on their own MUST be refused for a 6.1 node with `UNSUPPORTED_VERSION`
  (FR-007).
- **FR-022**: If a 6.1 archive contains a part the server does not recognize, or a part whose
  secrets it cannot identify, `export_artifacts` MUST write nothing to the workspace and name the
  part (fail closed).
- **FR-023**: The feature is complete with US1, US2, US3 and US5. Until US4 is done, workspace
  export is `unsupported` on 6.1. If no sample exports can be obtained, US4 moves to a later
  feature and the 6.1 matrix entry keeps workspace export `unsupported`.

### Key Entities

- **6.1 node**: a configured node pinned to INUBIT 6.1 (`V6_1` or a concrete 6.1 version,
  FR-002), reached through StartCLI only.
- **Channel**: how the server reaches a node's capabilities: REST and StartCLI (8.1) or StartCLI
  only (6.1); shown by `list_nodes` and `--check-config`.
- **Capability matrix entry 6.1**: the states of each capability on 6.1 (tool classification);
  `process diagnosis` and `log diagnosis` are separate capabilities on every version.
- **6.1 health report**: the health parts 6.1 provides via StartCLI and the parts listed as
  `NOT_AVAILABLE_ON_6_1`.
- **Recorded 6.1 output / sample archive**: a recorded, neutralized StartCLI output or export of a
  real 6.1 node used to define and test the 6.1 adapters (FR-020).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A person can answer "Is the 6.1 server up, which version runs, and how many
  processes are in error?" with one health request that returns within 15 seconds for a responsive
  6.1 node; an overview including 6.1 nodes still returns within 10 seconds.
- **SC-002**: Listing the failed processes of a 6.1 node returns within 15 seconds for up to 100
  instances.
- **SC-003**: 100% of the tools, capabilities and inputs refused on 6.1 are refused before any
  contact with the node (verified by a test per tool and per refused input).
- **SC-004**: No request other than StartCLI and the TLS handshake of FR-012 reaches a 6.1 node
  during the offline test suite's 6.1 scenarios (verified by fakes that fail on any REST call).
- **SC-005**: Existing 8.1 profiles behave as before: the offline test suite of features 001–010
  stays green, apart from these deliberate changes, whose tests are adjusted: the capability
  "diagnosis" shown as `process diagnosis` and `log diagnosis` in `list_nodes` and
  `--check-config`, and the conditional 6.1 hint after a detection answered with HTTP 404.
- **SC-006**: A refusal on a 6.1 node tells the person in one message which node, which version,
  which capability or input is missing and what to do instead.
- **SC-007**: For every recorded 6.1 sample archive, no secret value of the archive appears in any
  file written to the workspace (verified by the secret-leak tests of features 003/004 extended to
  the 6.1 samples).

## Out of Scope

- Writes on 6.1 of any kind (process control, development tools, deployments, end-to-end tests).
- Logs and inventory on 6.1.
- REST or SOAP access to 6.1 nodes, including the licensed 6.1 "External Interface".
- Detecting 6.1 automatically (`AUTO` stays REST-based; FR-002).
- Deployments between 6.1 and other versions.

## Preconditions

- The constitution amendment (MINOR) that names INUBIT 6.1 as an additionally supported,
  read-only version under Principle V is ratified before `/speckit-plan`; the plan's Constitution
  Check refers to it.

## Assumptions

- The 6.1 StartCLI options and `ps` columns are as documented in the 6.1 vendor documentation;
  exact output formats (including German texts and dates) come from the recordings of FR-020.
- A real 6.1 node is available for the read-only recordings of FR-020; health and process listing
  can be developed against reconstructed fixtures meanwhile (FR-017).
- Whether the 8.1.17 client works against a 6.1 server is checked with the recordings; the feature
  works with either client (FR-013).
- An unlicensed or plain 6.1 server answers the 8.1 detection request (`system/info`) with HTTP
  404, so the hint of FR-003 appears for typical 6.1 nodes on `AUTO`. If a 6.1 server answers
  differently, the hint does not appear and the warning stays as today; nothing else depends on
  this assumption.
- The vendor documentation stays outside the repository (copyright); specs and research notes
  only reference it.
