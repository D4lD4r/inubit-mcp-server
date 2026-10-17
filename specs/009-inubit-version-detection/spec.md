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
  reports the version string, `list_nodes` the configured `versionLine`.
- **Only 8.1 is implemented.** A node configured or detected as 9.x runs with the 8.1 adapters and
  an "unsupported" warning. Every tool is still offered and called as for 8.1. The error code
  `UNSUPPORTED_VERSION` exists but is not used for this.
- **The StartCLI client has its own version.** `startcli.sh -v` reports the client version and the
  server versions it declares to support. The client must match the server's patch level; today a
  mismatch is only a warning.
- **INUBIT 9.0 differs in ways that matter here** (research notes, section 4): SOAP API and
  servlet removed (the StartCLI URL changes), OAuth for Workbench and CLI (internal user/password
  still works), mandatory `Accept` header and JSON errors on REST, stricter REST URLs, some
  endpoints removed or changed, new endpoints, OpenAPI from 9.0.2 (opt-in).
- **Constitution V** requires that 9.x differences live in version-specific adapter code selected
  by configuration or the detected version, never scattered through tool logic.

## Clarifications

### Session 2026-10-17

- Q: Which tools must work on INUBIT 9.0 in this feature? → A: None yet. This feature delivers the
  version detection, the capability matrix and the refusals; INUBIT 9.0 stays "unsupported" in the
  matrix. Adapters for 9.0 follow in later features, which only add entries to the matrix.
- Q: May `deploy_release` deploy between nodes of different INUBIT versions? → A: Only between
  nodes of the same minor version (e.g. 8.1.x → 8.1.y); a different major or minor version is
  refused.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See which INUBIT version each node runs and what the server can do there (Priority: P1)

An operator connects the MCP server to several stages whose nodes run different INUBIT versions.
They ask the assistant which servers exist. For each node they see the INUBIT version that was
detected (or configured), the version of the StartCLI client used for it, whether that combination
is supported, and which groups of tools are available on that node.

**Why this priority**: Without knowing the version, neither the person nor the assistant can tell
whether a tool result is trustworthy on that node. It is also the base for every other story.

**Independent Test**: Configure nodes of two different versions (recorded responses), list the
nodes, and confirm that each shows its detected version, its client version, a support status and
its available tool groups.

**Acceptance Scenarios**:

1. **Given** a node that reports INUBIT 8.1.17 and a matching 8.1.17 client, **When** the nodes are
   listed, **Then** the node shows version 8.1.17, client 8.1.17, status "supported" and all tool
   groups its configuration allows.
2. **Given** a node whose version cannot be detected (unreachable, rejected credentials),
   **When** the nodes are listed, **Then** the node shows "version unknown" with the reason and the
   time of the last attempt, and the listing itself does not fail.
3. **Given** a node whose client version differs from the server's patch level, **When** the nodes
   are listed or `--check-config`/health is run, **Then** the mismatch is shown with both versions
   and what it means for the CLI-backed tools.

---

### User Story 2 - Tools that a node's version does not support are refused clearly, never run blindly (Priority: P1)

The assistant calls a tool on a node whose INUBIT version does not support it (for example a
StartCLI-based write on a version for which no verified adapter exists). Instead of running the
8.1 behaviour and producing a wrong or harmful result, the server refuses with a clear message:
which version the node runs, which capability is missing, and what the person can do.

**Why this priority**: Running 8.1 logic against another version can misreport state or, for write
tools, change an integration platform in unverified ways. This is a safety property
(Constitution I).

**Independent Test**: With recorded responses of a node reporting an unsupported version, call one
read tool and one write tool and confirm both are refused with `UNSUPPORTED_VERSION` before any
state-changing call is made, while tools that are supported on that version keep working.

**Acceptance Scenarios**:

1. **Given** a node detected as a version without verified write support, **When** a write tool is
   called on it, **Then** the call is refused with `UNSUPPORTED_VERSION`, naming the node, its
   version and the missing capability, and nothing is sent to INUBIT.
2. **Given** a group whose nodes run different versions, **When** a tool is called for the whole
   group, **Then** each node is answered according to its own version (supported nodes return
   results, unsupported ones report the refusal), and the call as a whole does not fail.
3. **Given** a node whose version is unknown because detection failed, **When** a write tool is
   called, **Then** it is refused until the version is known; read tools behave as decided in
   FR-009.
4. **Given** the tool list the server announces, **When** no configured node supports a tool at
   all, **Then** that tool is not offered, as today for tools without any eligible node.

---

### User Story 3 - A new INUBIT version can be supported by adding to the capability matrix (Priority: P2)

A maintainer later adds support for INUBIT 9.0 (or a further 8.1 patch with a known difference).
They add version-specific access for the affected tools and declare the new version range in the
capability matrix; nothing else in the tools changes, and 8.1 nodes are unaffected. In this
feature, 9.0 is present in the matrix as "unsupported", which proves the mechanism.

**Why this priority**: This is what makes the server usable "with different INUBIT versions" over
time; the actual 9.0 adapters are deliberately left to later features (clarification
2026-10-17).

**Independent Test**: With a test-only matrix entry and a test-only adapter for a fictitious
version, confirm that a node of that version gets exactly the declared tools through that adapter,
that an 8.1 node is unchanged, and that a 9.0 node is refused as "unsupported".

**Acceptance Scenarios**:

1. **Given** a node detected as INUBIT 9.0.x, **When** a write tool is called, **Then** it is
   refused with `UNSUPPORTED_VERSION` naming 9.0 as not yet supported, instead of running the 8.1
   behaviour; **When** a read tool is called, **Then** it runs as today with an explicit
   "unverified version" warning (FR-009).
2. **Given** a matrix entry that declares a version range with a subset of tool groups, **When** a
   node of that range is listed, **Then** exactly those groups are shown as available, and only
   those tools run on it.
3. **Given** a profile with nodes of different versions, **When** the same tool is called on each,
   **Then** each node is served by its own version's access or refused according to the matrix.

---

### User Story 4 - Pin or override the version per node (Priority: P3)

An administrator knows a node's version, or detection is not possible (for example the system
information endpoint is blocked). They set the version per group or node in the profile, see the
effective value in `--check-config`, and the server uses it without detecting.

**Why this priority**: Detection covers most cases; pinning is the fallback and keeps today's
`versionLine: V8_1` behaviour working.

**Independent Test**: Configure a pinned version on one node and `AUTO` on another; confirm with
`--check-config` and the node listing that the pinned node never triggers a detection and uses the
pinned capabilities.

**Acceptance Scenarios**:

1. **Given** a node pinned to 8.1, **When** any tool runs, **Then** no version detection is made
   for that node and the 8.1 capabilities apply.
2. **Given** a node pinned to a version while the server reports a different one (seen through
   health), **When** the node is listed, **Then** the difference is shown as a warning; the pinned
   value still decides.
3. **Given** existing profiles with `versionLine: AUTO | V8_1 | V9_X`, **When** they are loaded,
   **Then** they keep working with the same meaning (no migration needed).

### Edge Cases

- A node reports a version string in an unexpected format (missing patch, suffix such as
  `-SNAPSHOT` or a hotfix tag): the version is parsed as far as possible; what cannot be parsed is
  "unknown" with the raw value shown.
- A node is upgraded while the MCP server runs: the cached version would be stale. The server
  detects again after a bounded time or on a health check, and never runs a write on a cached
  version older than that bound.
- Detection itself needs a login; rejected credentials must not be retried on every call (account
  lockout, as today).
- A version newer than any known one (e.g. a later 9.x or 10.0): treated as unsupported for writes;
  reads as decided in FR-009.
- Mixed versions inside one group: each node is judged on its own (US2-2).
- Mixed versions along the stage chain of `deploy_release` (source 8.1, target 9.0, or the
  reverse): refused unless source and target nodes run the same major and minor version
  (8.1.x → 8.1.y is allowed); the refusal names both nodes and versions. A target node whose
  version is unknown is refused (FR-008).
- The StartCLI client supports a range of server versions that does not include the node's
  version: CLI-backed tools on that node are refused, REST tools keep working.
- A node is reachable over REST but its StartCLI client is missing: version from REST, client
  version "not available", CLI-backed tools refused as today (`CLI_UNAVAILABLE`).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The server MUST determine, per node, the INUBIT server version (major, minor, patch
  and the raw string) from the node itself when the node's version setting is automatic, and MUST
  use the configured version without contacting the node when it is pinned.
- **FR-002**: The server MUST determine, per node with a StartCLI installation, the client version
  and the server version range the client declares, without credentials and without contacting
  the INUBIT server.
- **FR-003**: The server MUST keep a declared capability matrix that states, for each supported
  version range, which tool groups are available (at least: monitoring/health, process and log
  diagnosis, inventory, workspace export, development writes, deployment, end-to-end tests,
  process restart/kill). A node's available tools are the intersection of its configuration
  (write, development, deploy settings as today), its server version's capabilities and, for
  CLI-backed tools, its client version's compatibility.
- **FR-004**: `list_nodes` MUST report per node: the effective server version (or "unknown" with
  the reason and time of the last attempt), how it was obtained (detected or pinned), the client
  version (or "not available"), the support status (supported, partially supported, unsupported,
  unknown) and the available tool groups. `get_health` MUST keep reporting the version it reads.
- **FR-005**: A tool call on a node whose version does not provide the tool's capability MUST be
  refused with `UNSUPPORTED_VERSION` before any state-changing call to INUBIT, naming the node, its
  version and the missing capability, with a next step (pin the version, use a supported node,
  upgrade the client).
- **FR-006**: A tool addressing several nodes (a group) MUST judge each node by its own version and
  return per-node results; an unsupported node MUST NOT make the whole call fail.
- **FR-007**: The tool list the server announces MUST NOT contain a tool that no configured node
  can run (by configuration or version). Whether a tool is announced MUST be decidable without
  contacting nodes at startup (configured versions and the capability matrix; nodes with automatic
  detection count as possibly capable).
- **FR-008**: Write tools (anything that changes state on INUBIT) MUST only run on a node whose
  version is known and declared write-capable for that tool in the capability matrix; an unknown or
  undeclared version MUST refuse them (Constitution I).
- **FR-009**: Read tools on a node whose version is unknown, declared "unverified" (INUBIT 9.0 in
  this feature) or newer than every declared range MUST run with the closest known adapters and an
  explicit "unverified version" warning in the result, as today for 9.x; on a version that the
  matrix declares as not supporting the tool they MUST be refused (FR-005).
- **FR-010**: A detected version MUST be cached per node and re-detected after a bounded time and
  after a failed call that indicates a version change (for example an unexpected response shape);
  a failed detection MUST NOT be retried more often than today's back-off (account lockout).
- **FR-011**: A client version that does not match the server's patch level MUST be reported in
  `list_nodes`, `get_health` and `--check-config`; a client that declares not to support the
  server's version MUST make CLI-backed tools on that node refuse with `UNSUPPORTED_VERSION`.
- **FR-012**: The version setting MUST stay configurable per group and node; the existing values
  `AUTO`, `V8_1` and `V9_X` MUST keep their meaning. A finer pin (a concrete version such as
  `9.0.7`) MAY be added; `--check-config` MUST show the effective setting per node.
- **FR-013**: All version-specific behaviour MUST be selected through version-specific adapters
  chosen by the effective version; tool logic MUST NOT branch on version strings
  (Constitution V).
- **FR-014**: In this feature, the server MUST support INUBIT 8.1.x fully as today and MUST declare
  INUBIT 9.0 (and every version outside the declared ranges) as unsupported for writes and
  unverified for reads: no 9.0-specific access is implemented, 9.0 nodes no longer run the 8.1
  behaviour for write tools, and read tools keep today's behaviour with a warning (FR-009). Adding support
  for a version later MUST only need version-specific access plus a matrix entry (US3).
- **FR-014a**: `deploy_release` MUST refuse a deployment whose source and target nodes differ in
  major or minor INUBIT version (same minor with different patch is allowed), naming both nodes
  and versions, before anything is sent.
- **FR-015**: Every refusal for version reasons MUST be logged (structured, stderr) and, for write
  tools, audited like any other refused write (Constitution VII).
- **FR-016**: Detection results and capability decisions MUST never expose credentials, tokens or
  URLs in tool results (Constitution II).

### Key Entities

- **Node version**: the INUBIT server version of one node: raw string, major/minor/patch, source
  (detected or pinned), time of detection or of the last failed attempt and its reason.
- **Client version**: the StartCLI client version for one node and the server version range it
  declares to support.
- **Version setting**: the configured value per group or node (`AUTO`, a version line, possibly a
  concrete version).
- **Capability**: a named tool group (e.g. "development writes") that a tool needs.
- **Capability matrix**: for each supported version range, the capabilities available on the
  server side, and for CLI-backed capabilities the client compatibility rule.
- **Support status**: per node, derived from version, client and matrix: supported, partially
  supported, unsupported, unknown.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For every configured node, the person can see its INUBIT version, client version and
  available tool groups in one listing, and the listing answers within the usual health budget
  even when some nodes are unreachable.
- **SC-002**: 100% of write-tool calls on a node with an unknown or unsupported version are refused
  before anything is sent to INUBIT (verified by tests for every write tool).
- **SC-003**: Existing 8.1 profiles behave exactly as before: the full offline test suite of
  features 001–008 stays green without changes to their expectations, apart from new fields in
  `list_nodes`.
- **SC-004**: Adding a version range to the capability matrix (verified with a test-only entry)
  needs no change in any tool's logic.
- **SC-005**: A version refusal tells the person in one message which node, which version, which
  capability is missing and what to do; no refusal needs a log lookup to be understood.

## Assumptions

- INUBIT 8.1.x remains the baseline; its behaviour does not change except for the additional
  version information and refusals on versions that are not 8.1.
- The system information endpoint used today for detection is assumed to exist on 9.0 as well, so
  that a 9.0 node is recognized and refused correctly; where it does not, the node is "unknown"
  (writes refused) and pinning (US4) is the fallback.
- 9.0-specific access (REST differences, the changed StartCLI URL, OAuth) is out of scope and
  follows in later features.
- The re-detection bound (FR-010) defaults to the inventory cache time (`inventory.cacheTtl`,
  10 minutes) unless planning finds a better value.
- The exact list of tool groups in the capability matrix is refined in planning; FR-003 names the
  minimum set.
