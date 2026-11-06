# Feature Specification: Update Check and Manifest Version

**Feature Branch**: `016-update-check`

**Created**: 2026-11-06

**Status**: Draft

**Input**: Operator request (2026-11-06, translated): "Could an up-to-dateness check be built into
the MCP server, at start and once a day? Also, Claude reports: the JAR's manifest has no version of
its own, so the version only comes from the file name."

## Context

The server is distributed as a single JAR attached to a GitHub release of the public repository
`D4lD4r/inubit-mcp-server`; the release workflow publishes `inubit-mcp-server-<version>.jar` and its
`.sha256` file per tag `v<version>`. An operator installs a JAR once, registers it with the MCP
client and then rarely looks at it again. Nothing tells them that a newer release with fixes
exists; the server can run for days in a client session.

The server knows its own version (it reports it with `--version` and in the MCP `serverInfo`), but
from a build resource inside the JAR that only the server itself reads. The JAR manifest — what
tools, the JVM and a model inspecting the file look at — carries no version, so anyone looking at
the JAR from outside can only infer the version from the file name, which an operator may rename
(for example to a version-free `inubit-mcp-server.jar`).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Learn about a newer release at start (Priority: P1)

The operator's MCP client starts the server. Shortly after start, the server looks up the latest
published release. If it is newer than the running version, the operator (and the model) can see
that an update exists, which version it is and where to get it. The start itself is not slowed
down, and nothing is downloaded or installed.

**Why this priority**: it is the core of the request: an installed server that silently ages is
the problem.

**Independent Test**: start the server against a fake release source that announces a higher
version; the notice appears within seconds, while the MCP handshake and a first tool call complete
as fast as without the check.

**Acceptance Scenarios**:

1. **Given** the running version is 0.7.1 and the latest release is 0.8.0, **When** the server
   starts, **Then** within a few seconds a notice names the running version 0.7.1, the available
   version 0.8.0 and the release page, and states that the update is manual (FR-001, FR-005,
   FR-006).
2. **Given** the running version is 0.7.1 and the latest release is 0.7.1, **When** the server
   starts, **Then** no update notice appears (FR-004).
3. **Given** the release source does not answer, **When** the server starts, **Then** the MCP
   handshake and tool calls are not delayed and no tool call fails because of the check (FR-002,
   FR-008).

---

### User Story 2 - Learn about a release published while the server runs (Priority: P2)

A server that runs for days in a client session checks again once a day, so a release published
after the start is still noticed.

**Why this priority**: valuable for long-running sessions, but the start-up check (US1) already
covers the common case of a client that restarts its servers regularly.

**Independent Test**: with a controllable scheduler, start the check against a fake release source
that announces the running version, then switch the source to a higher version and run the next
scheduled check; the notice appears after the second check, and no request is made in between.

**Acceptance Scenarios**:

1. **Given** the server has been running for 24 hours since its last check, **When** the daily
   check runs and a newer release exists, **Then** the notice appears (FR-003).
2. **Given** the same newer version was already announced, **When** a later daily check finds the
   same version again, **Then** the stderr log does not repeat the notice; the notice stays
   visible where it is queried (FR-007).

---

### User Story 3 - The JAR carries its own version (Priority: P1)

Anyone inspecting the JAR — the operator, a script, the JVM, a model — reads the version from the
JAR's manifest instead of guessing it from the file name. The server reports the same version, even
when the file has been renamed.

**Why this priority**: it is the second explicit part of the request and a prerequisite for a
trustworthy comparison in US1.

**Independent Test**: build the JAR, read its manifest: it names the project version; copy the JAR
to a version-free file name and run `--version`: the output is the same version.

**Acceptance Scenarios**:

1. **Given** a JAR built from version 0.8.0, **When** its manifest is read, **Then** it names the
   implementation title `inubit-mcp-server` and the implementation version 0.8.0 (FR-010).
2. **Given** that JAR renamed to `inubit-mcp-server.jar`, **When** it is run with `--version`,
   **Then** it prints `inubit-mcp-server 0.8.0` (FR-011).
3. **Given** a build whose manifest version and the server's reported version could diverge,
   **When** the build runs its tests, **Then** the build fails (FR-012).

---

### User Story 4 - Turn the check off (Priority: P3)

An operator in a network without internet access, or one whose policy forbids outgoing calls to
GitHub, turns the check off; the server then makes no request at all.

**Why this priority**: needed for acceptance in restricted networks, but the default (check on,
failures silent) already works there without errors.

**Independent Test**: start the server with the check turned off against a fake release source;
the source receives no request.

**Acceptance Scenarios**:

1. **Given** the check is turned off, **When** the server starts and runs for more than a day,
   **Then** no request is made and no update notice appears (FR-009).

---

### Edge Cases

- The running version is a development build (for example `0.8.0-SNAPSHOT`, a local build ahead of
  the latest release): by semantic-version ordering `0.8.0-SNAPSHOT` is older than `0.8.0` and
  newer than `0.7.1`; no notice for 0.7.1.
- The latest release tag cannot be read as a version (unexpected tag name): treated like a failed
  check (FR-008), no notice.
- Drafts and pre-releases on GitHub are never offered as updates.
- The release source answers with a rate-limit or server error: failed check, retried at the next
  regular check (no tight retry loop).
- The network hangs: the check gives up after a short timeout (FR-002) and never holds the server
  open at shutdown.
- The server is started with `--version` or `--check-config`: no check runs (these modes print and
  exit; FR-013).
- Several profiles run as separate server processes: each checks for itself; the request carries no
  profile information (FR-014).
- The latest release is *older* than the running version (a newer build is installed): no notice.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: When the server starts in MCP mode, it MUST check once whether a newer release of the
  server has been published.
- **FR-002**: The check MUST NOT delay the MCP handshake, server readiness or any tool call; it runs
  in the background with a short overall timeout (at most 10 seconds per check) and MUST NOT delay
  shutdown.
- **FR-003**: While the server runs, it MUST repeat the check every 24 hours after the previous
  check.
- **FR-004**: A release counts as newer only if its version is strictly higher than the running
  version by semantic-version ordering; drafts and pre-releases on the release source are ignored.
- **FR-005**: The update notice MUST name the running version, the newer version and the release
  page URL, and say that the update is manual (download the JAR, check its SHA-256, re-register or
  replace it). The server MUST NOT download, install or replace anything.
- **FR-006**: The notice MUST be visible to the operator in the server's stderr log and to the model
  in the result of `get_health` as a server-level field (independent of the INUBIT nodes).
- **FR-007**: The stderr log MUST announce a given newer version once per server process; repeated
  checks finding the same version stay quiet in the log at normal level.
- **FR-008**: A failed check (no network, DNS failure, timeout, TLS error, HTTP error, rate limit,
  unreadable answer) MUST NOT produce an error for the operator or any tool; it is logged at a low
  level without stack trace, and the last successful result (if any) remains valid.
- **FR-009**: The operator MUST be able to turn the check off; when off, the server makes no request
  to the release source at all.
- **FR-010**: The JAR manifest MUST carry the implementation title `inubit-mcp-server` and the
  implementation version equal to the project version of the build.
- **FR-011**: The server MUST determine its reported version (`--version`, MCP `serverInfo`, the
  update comparison) independent of the JAR file name, preferring the manifest of the running JAR.
- **FR-012**: The build MUST verify that the manifest version of the built JAR equals the version
  the server reports.
- **FR-013**: `--version` and `--check-config` MUST NOT contact the release source.
- **FR-014**: The request to the release source MUST NOT contain profile names, node or host names,
  URLs of INUBIT systems, user names or any other configuration data; it identifies the client only
  as `inubit-mcp-server` with its version.
- **FR-015**: User documentation (README, setup guide, CHANGELOG) MUST describe the check, how the
  notice appears, how to turn it off, and how to read the version from the manifest.
- **FR-016**: The outgoing request MUST honour the network proxy settings of the Java runtime, so
  that it works in networks that require a proxy configured for the JVM.

### Key Entities

- **Running version**: the version of the server process, from the JAR manifest (or the build
  resource when not run from a JAR).
- **Latest release**: the version and page URL of the newest published, non-draft, non-pre-release
  release of the project on GitHub.
- **Update status**: the result of the last check — when it ran, whether it succeeded, the latest
  release found and whether it is newer than the running version; read by `get_health`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: With the release source unreachable or hanging, the MCP handshake and the first tool
  call complete within the same time as with the check turned off (no added latency in the
  automated start test).
- **SC-002**: With a newer release available, the notice is visible in the log and in `get_health`
  within 10 seconds of start.
- **SC-003**: With the check turned off, zero requests reach the release source over a simulated
  run of more than 24 hours.
- **SC-004**: For every built JAR, the manifest version, `--version` and `serverInfo.version` agree
  (verified by the build), also after renaming the file.
- **SC-005**: The outgoing request contains none of the configured profile, group, node, host or
  user names (verified by a test that inspects the captured request).

## Assumptions

- The release source is the public GitHub REST API of `D4lD4r/inubit-mcp-server`
  (latest release endpoint); no authentication, so the anonymous rate limit (60 requests per hour
  per IP) is far above one request per day per process.
- The 24-hour cadence is measured per process from its previous check; nothing is persisted between
  processes, so every server start checks once.
- The notice is informational; it changes neither the health status of INUBIT nodes nor the exit
  code.
- A check is also useful without internet: it fails silently, so the default can be "on".
