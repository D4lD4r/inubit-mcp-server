# Feature Specification: INUBIT Version Detection and Version-Dependent Capabilities

**Feature Branch**: `009-inubit-version-detection`

**Created**: 2026-10-17

**Status**: Draft

**Input**: User description (translated from German): "Detection of the INUBIT version and
adaptation of the corresponding range of functions. This shall make it possible to use the MCP
server with different INUBIT versions."

## Context & Findings *(verified before writing this spec)*

- **A coarse detection exists.** Every node has a `versionLine` (`AUTO`, `V8_1`, `V9_X`; default
  `AUTO`). `AUTO` reads the version from INUBIT's system information on first use and caches it;
  a failed detection falls back to 8.1 with a warning and is retried after 60 s. `get_health`
  reports the version string, `list_nodes` the configured `versionLine`. `list_nodes` is a
  configuration-only tool (no INUBIT contact).
- **Only 8.1 is implemented.** A node configured or detected as 9.x runs with the 8.1 adapters and
  an "unsupported" warning; writes included. Every tool is still offered and called as for 8.1.
  The error code `UNSUPPORTED_VERSION` exists but is not used for this.
- **The StartCLI client has its own version.** `startcli.sh -v` reports the client version and the
  server versions it declares to support (the 8.1.17 client: "from 4.0.1 to 8.1.17"). A probe for
  this and a mismatch warning text exist but are not wired: today the client version is not
  checked at all.
- **INUBIT 9.0 differs in ways that matter here** (research notes, section 4): SOAP API and
  servlet removed (the StartCLI URL changes, `run_e2e_test`'s SOAP endpoint is gone), OAuth for
  Workbench and CLI (internal user/password still works), mandatory `Accept` header and JSON
  errors on REST, stricter REST URLs, some endpoints removed or changed, new endpoints, OpenAPI
  from 9.0.2 (opt-in).
- **Constitution V** requires that 9.x differences live in version-specific adapter code selected
  by configuration or the detected version, never scattered through tool logic.

## Clarifications

### Session 2026-10-17

- Q: Which tools must work on INUBIT 9.0 in this feature? → A: None yet. This feature delivers the
  version detection, the capability matrix and the refusals. INUBIT 9.0 is unsupported for writes
  and unverified for reads (reads run as today, with a warning). Adapters for 9.0 follow in later
  features, which only add entries to the matrix.
- Q: May `deploy_release` deploy between nodes of different INUBIT versions? → A: Only between
  nodes of the same minor version (e.g. 8.1.x → 8.1.y); a different major or minor version is
  refused.

### Session 2026-10-18 (spec review round 1)

- Q: Does `list_nodes` contact nodes? When are the versions obtained? → A: `list_nodes` never
  contacts INUBIT or runs StartCLI; it shows the last known state or "not yet detected". The
  server version is detected on the first tool call on a node and by `get_health` (and again
  before a write if it is missing or older than the re-detection bound); the client version on the
  first StartCLI-backed tool call on a node and by `get_health`. Nothing is contacted at startup.
- Q: May an `AUTO` node whose detection fails still write? → A: No. Writes are refused until the
  version is known; the remedy is a pin. This is a deliberate behaviour change.
- Q: A node is pinned, but `get_health` observed a different major.minor version. → A: Writes are
  refused until the pin is corrected.

### Session 2026-10-18 (spec review round 2)

- Q: Is a server with a newer patch than the client's declared upper bound (8.1.17 client, 8.1.18
  server) refused for StartCLI-backed tools? → A: No. The client range is checked by major.minor
  only; a patch difference gives a warning.
- Q: Can a concrete version be pinned (e.g. `8.1.17`)? → A: Yes, in addition to `AUTO`, `V8_1`
  and `V9_X`.

### Session 2026-10-18 (spec review round 3)

- Q: The client probe fails (slow JVM start) or prints no version range. Are StartCLI-backed writes
  refused? → A: No. They run with a warning, as today (today no probe runs at all). Only a
  successful probe whose range excludes the server's major.minor refuses them. A failed probe is
  not cached and is retried at most once per 60 s.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See which INUBIT version each node runs and what the server can do there (Priority: P1)

An operator connects the MCP server to several stages whose nodes run different INUBIT versions.
They run a health check and then ask which servers exist. For each node they see the INUBIT
version that was detected (or pinned), the version of the StartCLI client used for it, the support
status, and which groups of tools are available on that node.

**Why this priority**: Without knowing the version, neither the person nor the assistant can tell
whether a tool result is trustworthy on that node. It is also the base for every other story.

**Independent Test**: With recorded responses of nodes of two different versions, run `get_health`
and then `list_nodes`, and confirm that each node shows its detected version, its client version,
a support status and its available tool groups; before the health check the same listing shows
"not yet detected" without contacting any node.

**Acceptance Scenarios**:

1. **Given** a node that reports INUBIT 8.1.17 and a matching 8.1.17 client, **When** `get_health`
   has run and the nodes are listed, **Then** the node shows version 8.1.17 (detected), client
   8.1.17, status `supported` and all tool groups its configuration allows.
2. **Given** an `AUTO` node that no tool has addressed yet, **When** the nodes are listed, **Then**
   it shows "not yet detected" with the next step `get_health`, and no node is contacted.
3. **Given** a node whose detection failed (unreachable, rejected credentials, missing permission),
   **When** the nodes are listed, **Then** it shows `unknown` with the reason and the time of the
   last attempt, and the listing itself does not fail.
4. **Given** a node whose client version differs from the server version, **When** `get_health`
   runs or the nodes are listed afterwards, **Then** both versions are shown, the comparison result
   (`match`, `mismatch`, `not comparable`) and what it means for the StartCLI-backed tools (FR-011).

---

### User Story 2 - Tools that a node's version does not support are refused clearly, never run blindly (Priority: P1)

The assistant calls a write tool on a node whose INUBIT version is not declared write-capable (for
example a 9.0 node, or a node whose version could not be detected). Instead of running the 8.1
behaviour and possibly changing an integration platform in unverified ways, the server refuses
with a clear message: which version the node runs, which capability is missing, and what the
person can do.

**Why this priority**: Running 8.1 write logic against another version can misreport state or
change the platform in unverified ways. This is a safety property (Constitution I).

**Independent Test**: With recorded responses of a 9.0 node, call a write tool and confirm it is
refused with `UNSUPPORTED_VERSION` and that the only request sent to INUBIT was the version
detection; call a read tool and confirm it runs with the "unverified version" warning. With a
test-only matrix entry that marks a read capability `unsupported`, confirm that read tool is
refused too.

**Acceptance Scenarios**:

1. **Given** a node detected as a version without verified write support, **When** a write tool is
   called on it, **Then** the call is refused with `UNSUPPORTED_VERSION`, naming the node, its
   version and the missing capability; apart from the version detection (FR-010) nothing is sent
   to INUBIT.
2. **Given** a group whose nodes run different versions, **When** a read tool is called for the
   whole group, **Then** each node is answered according to its own version, and an unsupported
   node does not make the call fail (`deploy_release` excepted, FR-014a).
3. **Given** an `AUTO` node whose detection fails, **When** a write tool is called, **Then** it is
   refused with `UNSUPPORTED_VERSION` naming the detection failure and the remedy (pin the version
   the node runs); read tools run with the "unverified version" warning.
4. **Given** a node pinned to 8.1 whose health check observed 9.0, **When** a write tool is called,
   **Then** it is refused until the pin is corrected, naming both versions.
5. **Given** the tool list the server announces, **When** no configured node can run a tool by
   configuration or by pinned version, **Then** that tool is not offered (FR-007).

---

### User Story 3 - A new INUBIT version can be supported by adding to the capability matrix (Priority: P2)

A maintainer later adds support for INUBIT 9.0 (or a further 8.1 patch with a known difference).
They add version-specific access for the affected tools and declare the new version range in the
capability matrix; nothing else in the tools changes, and 8.1 nodes are unaffected. In this
feature, 9.0 is present in the matrix (unverified reads, unsupported writes), which proves the
mechanism.

**Why this priority**: This is what makes the server usable "with different INUBIT versions" over
time; the actual 9.0 adapters are deliberately left to later features (clarification
2026-10-17).

**Independent Test**: With a test-only matrix entry and a test-only adapter for a fictitious
version, confirm that a node of that version gets exactly the declared capabilities through that
adapter, that an 8.1 node is unchanged, and that a 9.0 node behaves as in US2.

**Acceptance Scenarios**:

1. **Given** a node detected as INUBIT 9.0.x, **When** a write tool is called, **Then** it is
   refused with `UNSUPPORTED_VERSION` naming 9.0 as not yet supported; **When** a REST-backed read
   tool is called, **Then** it runs as today with an "unverified version" warning (FR-009); **When**
   a StartCLI-backed read tool is called with an 8.1 client, **Then** it is refused because the
   client does not support 9.0 (FR-011).
2. **Given** a matrix entry that declares a version range with a subset of capabilities, **When** a
   node of that range is listed after detection, **Then** exactly those capabilities are shown as
   usable, and only those tools run on it.
3. **Given** a profile with nodes of different versions, **When** the same tool is called on each,
   **Then** each node is served by its own version's access or refused according to the matrix.

---

### User Story 4 - Pin the version per node (Priority: P3)

An administrator knows a node's version, or detection is not possible (for example the system
information endpoint is not permitted for the technical user). They pin the version per group or
node in the profile, see the effective value in `--check-config`, and the server uses it without
detecting.

**Why this priority**: Detection covers most cases; pinning is the fallback and the remedy for a
failed detection (US2-3).

**Independent Test**: Configure a pinned version on one node and `AUTO` on another; confirm with
`--check-config`, `get_health` and the node listing that the pinned node never triggers a
detection request (the version still being read for display by `get_health`, FR-001) and uses the
pinned capabilities, unless an observed major.minor contradicts the pin (US2-4).

**Acceptance Scenarios**:

1. **Given** a node pinned to 8.1, **When** any tool runs, **Then** no request is sent beyond what
   the tool sends today (`get_health` keeps reading the system information it already reads), and
   the 8.1 capabilities apply.
2. **Given** a node pinned to a version while `get_health` observes a different major.minor,
   **When** the node is listed, **Then** the contradiction is shown and writes are refused (US2-4);
   a different patch only gives a warning.
3. **Given** existing profiles with `versionLine: AUTO | V8_1 | V9_X`, **When** they are loaded,
   **Then** they load without change; `AUTO` and `V8_1` keep their behaviour, `V9_X` still means
   "this node runs 9.x" but write tools are no longer announced if no other node can run them
   (FR-007) and are refused on it otherwise, and `--check-config` names that change.

### Edge Cases

- A version string in an unexpected format: major.minor is enough to match a matrix range; a
  suffix (`-SNAPSHOT`, a hotfix tag) and a missing patch are kept in the raw value and ignored for
  matching; without a parsable major.minor the version is `unknown`.
- A node is upgraded while the MCP server runs: the cached version is re-detected before the next
  write if it is older than the bound or stale (FR-010); reads use the cached version. A response
  that indicates a different version marks it stale.
- Detection needs a login; rejected credentials are not retried more often than today (60 s
  back-off, account lockout).
- A version newer than any declared range (a later 9.x, 10.0): writes refused, reads unverified
  (FR-009).
- Mixed versions inside one group: each node is judged on its own (US2-2), except for
  `deploy_release` (FR-014a).
- Mixed versions along the stage chain of `deploy_release`: refused as a whole unless every source
  and target node runs the same major.minor (8.1.x → 8.1.y is allowed); a line pin (`V9_X`) gives
  no minor version and counts as unknown for this check.
- The StartCLI client declares a server version range whose major.minor does not include the
  node's major.minor (an 8.1 client at a 9.0 server): StartCLI-backed tools on that node are
  refused, REST-backed tools are unaffected. A newer patch than the client's upper bound (8.1.17
  client, 8.1.18 server) only gives a warning.
- The client range cannot be evaluated (server version unknown, the client probe failed or printed
  no range): StartCLI-backed tools run with a warning naming the cause, as today; the server-version
  rules (FR-008, FR-009) still apply.
- A node reachable over REST without a StartCLI installation: client version "not available";
  StartCLI-backed tools are refused as today (`CLI_UNAVAILABLE`).
- A tool that uses REST and StartCLI (see the tool table): behaves as today when its StartCLI part
  is unavailable, with `UNSUPPORTED_VERSION` as the reason when the client or version is the cause.

## Requirements *(mandatory)*

### Tool classification

Each tool needs one capability. The matrix (FR-003) declares per version range whether each
capability is `supported`, `unverified` or `unsupported`.

| Tool | Capability | Kind | Channel | 8.1.x | 9.0.x / newer / unknown |
|---|---|---|---|---|---|
| `list_nodes` | – (configuration) | read | none | supported | always runs (no INUBIT contact) |
| `get_health` | monitoring | read | REST | supported | unverified (runs, warning) |
| `find_processes`, `query_logs` | diagnosis | read | REST | supported | unverified (runs, warning) |
| `list_inventory` (diagrams) | inventory | read | REST | supported | unverified (runs, warning) |
| `list_inventory` (modules), `get_inventory_item` (diagrams and modules; the history always via StartCLI) | inventory | read | REST + StartCLI | supported | REST part unverified; StartCLI part per FR-011 |
| `export_artifacts` | workspace export | read on INUBIT (writes only the local workspace) | StartCLI | supported | per FR-011 (8.1 client: refused) |
| `check_artifacts` | – (offline check) | read | none, optional StartCLI module lookup | supported | runs; the module lookup per FR-011 |
| `import_artifacts`, `restore_backup`, `set_active`, `tag_artifacts` | development writes | write | StartCLI (+ REST reads) | supported | unsupported (refused) |
| `deploy_release` | deployment | write | StartCLI + REST | supported | unsupported (refused); same-minor rule FR-014a |
| `run_e2e_test` | end-to-end test | write (sends a message) | SOAP + REST | supported | unsupported (refused; SOAP removed in 9.0) |
| `restart_process`, `kill_process` | process control | write | REST read + StartCLI | supported | unsupported (refused) |

### Functional Requirements

- **FR-001**: The server MUST determine, per node with an automatic setting, the INUBIT server
  version (major, minor, patch, raw string) from the node itself, on the first tool call on that
  node and in `get_health`; nothing is contacted at startup. For a pinned node it MUST use the
  pinned version and send no detection request; `get_health` still reports the version it reads
  for display and contradiction checks (FR-012).
- **FR-002**: The server MUST determine, per node with a StartCLI installation, the client version
  and the server version range the client declares, without credentials and without contacting
  the INUBIT server, on the first StartCLI-backed tool call on that node and in `get_health`, and
  keep it for the lifetime of the process. In `get_health` the client probe MUST run in parallel
  with the REST checks and within the health time budget of feature 001 (SC-002 there); if it does
  not finish in time, the client version is reported as `not yet detected (timeout)`. A failed or
  timed-out probe is not cached; it is retried on the next StartCLI-backed call or health check,
  at most once per 60 s.
- **FR-003**: The server MUST keep a declared capability matrix: for each version range and each
  capability of the tool classification, one of `supported`, `unverified` (reads only; run with the
  8.1 adapters and an "unverified version" warning) or `unsupported`. A node's usable tools are
  the intersection of its configuration (write, development, deploy settings as today), its
  version's capabilities and, for StartCLI-backed tools, its client compatibility (FR-011), which
  overrides `unverified`.
- **FR-004**: `list_nodes` MUST NOT contact INUBIT or run StartCLI. It MUST report per node: the
  version setting; the last known server version with its source (detected or pinned) and time,
  or `not yet detected` (next step `get_health`), or `unknown` with the reason and time of the last
  failed attempt; the client version or `not yet detected` / `not available`; the support status;
  and the usable capabilities. The support status is `supported` if every capability the
  configuration allows is `supported`; `partially supported` if at least one is usable and at
  least one is `unverified` or `unsupported`; `unsupported` if none is usable; `unknown` if the
  version is unknown or not yet detected.
- **FR-005**: A tool call on a node whose version or client does not provide the tool's capability
  MUST be refused with `UNSUPPORTED_VERSION` before any request other than the version detection
  (FR-010), naming the node, its version and the missing capability (and, if the client is the
  cause, the client version and its declared range), with a next step (use a
  supported node; upgrade or match the client; if detection is not possible, pin the version the
  node actually runs). It MUST NOT suggest pinning a version the node is not known to run.
- **FR-006**: A read tool addressing several nodes (a group) MUST judge each node by its own
  version and return per-node results; an unsupported node MUST NOT make the whole call fail.
  `deploy_release` is excepted (FR-014a).
- **FR-007**: The tool list is decided at startup from the configuration and pinned versions, as
  today: a tool is not announced if no configured node can run it by configuration or by pinned
  version; nodes with automatic detection count as possibly capable. Later detections do not
  change the announced list (no list-changed notification); they lead to refusals instead.
- **FR-008**: Write tools MUST only run on a node whose version is known and declared `supported`
  for the tool's capability. They MUST be refused when the version is unknown (including a failed
  detection of an `AUTO` node), unverified, unsupported, or when a pinned version contradicts an
  observed major.minor (FR-012) (Constitution I).
- **FR-009**: Read tools on a node whose version is unknown, `unverified` (INUBIT 9.0 in this
  feature) or newer than every declared range MUST run with the 8.1 adapters and an explicit
  "unverified version" warning in the result, as today for 9.x; on a version that the matrix
  declares `unsupported` for the tool's capability they MUST be refused (FR-005). Client
  incompatibility refuses StartCLI-backed reads (FR-011).
- **FR-010**: A detected version MUST be cached per node. Before a write tool runs, the server MUST
  detect again if the cached version is missing, stale or older than the re-detection bound; apart
  from that one request nothing is sent before the capability check passes. A call that ends in
  `UNEXPECTED_RESPONSE`, in HTTP 406, or in HTTP 404 that is not mapped to `NOT_FOUND` for a
  requested resource, MUST mark the cached version stale. A failed detection MUST NOT be retried more often than today's
  back-off (60 s). The bound defaults to `inventory.cacheTtl` (10 minutes); whether it gets its own
  setting is decided in planning.
- **FR-011**: The client and server versions are compared as major.minor.patch: `match`,
  `mismatch` (any part differs) or `not comparable` (one is unknown or has no patch). The server
  version used is the one `get_health` last observed if present, otherwise the pin or the detected
  version. A mismatch is reported in `list_nodes` and `get_health`, and StartCLI-backed tools still
  run with a warning that results may be unreliable. The client's declared range is checked by
  major.minor only (bounds inclusive, suffixes ignored): only a server major.minor outside it MUST
  refuse StartCLI-backed tools on that node with `UNSUPPORTED_VERSION`. If the range cannot be
  evaluated (server version unknown, probe failed or timed out, no range printed),
  StartCLI-backed tools run with a warning naming the cause; FR-008 and FR-009 still apply to the
  server version. This is no change for today's 8.1 setups, where no probe runs.
- **FR-011a**: `--check-config` MUST NOT contact any INUBIT server. It runs the local client probe
  per node with a StartCLI installation and shows the client version and declared range; it
  compares them only against a concrete pin (FR-012), otherwise it shows "server version:
  determined at runtime".
- **FR-012**: The version setting MUST stay configurable per group and node. `AUTO` and `V8_1` keep
  their behaviour (apart from FR-008 for failed detections); `V9_X` keeps meaning "this node runs
  9.x" with writes now refused. The setting MUST also accept a concrete version (e.g. `8.1.17`),
  which pins major, minor and patch; it needs at least major.minor.patch (a suffix is allowed and
  ignored for matching), anything else is a configuration error reported at startup and by
  `--check-config`. `--check-config` MUST show the effective setting per node and
  name the changed meaning of `V9_X`. When `get_health` observes a major.minor different from the
  pin, writes MUST be refused and the contradiction shown; it is held in memory until a later
  `get_health` observation matches the pin again or the process restarts with a corrected pin.
  Only `get_health`'s observation counts. A different patch only gives a warning.
- **FR-013**: All version-specific behaviour MUST be selected through version-specific adapters
  chosen by the effective version; tool logic MUST NOT branch on version strings
  (Constitution V).
- **FR-014**: In this feature, the server MUST support INUBIT 8.1.x fully as today and MUST declare
  INUBIT 9.0 and every version outside the declared ranges as `unsupported` for writes and
  `unverified` for reads (tool classification). No 9.0-specific access is implemented. Adding
  support for a version later MUST only need version-specific access plus a matrix entry (US3).
- **FR-014a**: `deploy_release` MUST check every source and every target node, in the preview and
  again before execution. If any target node is unknown or not write-capable, if any target node
  differs in major.minor from the source nodes, if the source nodes differ from each other, or if
  any source node's version is unknown, the whole deployment MUST be refused before anything is
  sent, naming the nodes and versions. A pin `V8_1` or a concrete version gives major.minor, which
  is enough for this check; `V9_X` gives no minor version and counts as unknown. Before execution,
  FR-010 (re-detection before a write) applies to every source and target node of the
  deployment.
- **FR-015**: Every refusal for version reasons MUST be logged (structured, stderr) and, for write
  tools, audited like any other refused write (Constitution VII).
- **FR-016**: Detection results and capability decisions MUST never expose credentials, tokens or
  URLs in tool results (Constitution II).

### Key Entities

- **Node version**: the INUBIT server version of one node: raw string, major/minor/patch, source
  (detected or pinned), time of detection or of the last failed attempt and its reason, and
  whether it is stale.
- **Client version**: the StartCLI client version for one node and the server version range it
  declares to support.
- **Version setting**: the configured value per group or node (`AUTO`, a version line `V8_1` /
  `V9_X`, or a concrete version such as `8.1.17`).
- **Capability**: a named tool group from the tool classification (e.g. "development writes").
- **Capability matrix**: for each version range, each capability's state (`supported`,
  `unverified`, `unsupported`).
- **Support status**: per node, derived from version, client and matrix: `supported`,
  `partially supported`, `unsupported`, `unknown` (FR-004).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For every configured node, the person sees its last known INUBIT version, client
  version, support status and usable capabilities in one listing that contacts no node and answers
  as fast as today's `list_nodes`.
- **SC-002**: 100% of write-tool calls on a node with an unknown, unverified or unsupported version
  are refused, with no request to INUBIT other than the version detection (verified by tests for
  every write tool).
- **SC-003**: Existing 8.1 profiles whose version detection succeeds (or that pin `V8_1`) behave as
  before: the offline test suite of features 001–008 stays green, apart from new fields in
  `list_nodes`/`get_health` and the deliberate changes (writes refused after a failed detection,
  writes refused on `V9_X`), whose tests are adjusted.
- **SC-004**: Adding a version range to the capability matrix (verified with a test-only entry)
  needs no change in any tool's logic.
- **SC-005**: A version refusal tells the person in one message which node, which version, which
  capability is missing and what to do; no refusal needs a log lookup to be understood.

## Assumptions

- INUBIT 8.1.x remains the baseline. Deliberate changes for 8.1 profiles: writes are refused while
  an `AUTO` node's version is unknown (remedy: pin `V8_1` or the concrete version), and writes are
  refused on `V9_X` nodes. A client older than the server's patch (8.1.17 client, 8.1.18 server)
  keeps working, with a warning.
- The system information endpoint used today for detection is assumed to exist on 9.0 as well, so
  that a 9.0 node is recognized and refused correctly; where it does not, the node is `unknown`
  (writes refused) and a pin is the fallback.
- 9.0-specific access (REST differences, the changed StartCLI URL, OAuth) is out of scope and
  follows in later features.
- The re-detection bound defaults to `inventory.cacheTtl` (10 minutes) unless planning finds a
  better value.
