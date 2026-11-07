# Feature Specification: INUBIT 9 Support

**Feature Branch**: `017-inubit-9-support`

**Created**: 2026-11-06

**Status**: Draft

**Target release**: 0.8.0 — 017 is merged into the release branch `0.8.0` (features 012 INUBIT
6.1 CLI reads, 013 StartCLI on Windows, 016 update check), which is then released (the target was
0.8.1 until the person's decision of 2026-11-06)

**Input**: the operator's request (German, verbatim): "Ich möchte ein weiteres Feature 017
implementieren. Der MCP soll auch Inubit 9 unterstützen. Dafür ist es wahrscheinlich notwendig pro
Version einen eigenen Startcli Pfad zu konfigurieren."

## Context

- **Feature 009 prepared INUBIT 9 but did not implement it.** Every node has a `versionLine`
  (`AUTO`, `V8_1`, `V9_X`, a concrete version; 012 adds `V6_1`). A capability matrix decides per
  INUBIT version which tools run. Today INUBIT 9.0, `V9_X`, every other version and an unknown
  version run reads with the 8.1 adapters and an "unverified" warning, and refuse every write
  with `UNSUPPORTED_VERSION`. 009 states that "adapters for 9.0 follow in later features"; this is
  that feature.
- **StartCLI is configured once per group or node.** `cli.home` (the INUBIT client directory with
  `bin/startcli.sh`, `.bat` on Windows after 013), `cli.javaHome` (the JDK StartCLI runs with) and
  `cli.url` (the address StartCLI connects to) can be set per group or node; `cliHome` and
  `cliJavaHome` also in `defaults`. Without `cli.url` the address is
  `<baseUrl>/ibis/servlet/IBISSoapServlet` for every node, whatever its version.
- **A local client check exists.** Before the first StartCLI call on a node, `startcli -v` reports
  which INUBIT versions the client supports; an 8.1 client at a 9.0 node refuses every
  StartCLI-backed tool on that node.
- **What INUBIT 9.0 changes for this server** (official documentation, research notes §4 and the
  017 research):
  - StartCLI connects to `https://<host>:<port>` without a path from 9.0.0; the old
    `/ibis/servlet/IBISSoapServlet` address is deprecated in 9.0.0 and removed in 9.0.2.
  - The INUBIT 9.0 client and server run on JDK 21 (8.1: JDK 17); a JDK of another major version is
    not guaranteed to work.
  - The StartCLI commands and options this server uses (`export`, `import`, `tag --tagMove`,
    `processErrorStart`, `kill`, `-v`, the TLS options, the password prompt on stdin) are documented
    unchanged.
  - Workbench and StartCLI log in with OAuth by default; login with user and password against the
    internal user management remains supported. Basic authentication of REST remains supported
    as a deprecated fallback.
  - REST: the `Accept` header is mandatory, and endpoints that now also answer JSON choose the
    format from it; errors are JSON (`{statusCode, message, path, timestamp}`) instead of HTML
    pages (401) or plain text; URLs with `//`, `/./` or `/../` are refused; several endpoints
    changed their documented parameters (e.g. `/model/models` requires `user`).
  - The internal SOAP API of the process engine is removed. User-defined SOAP listeners of
    workflows (used by `run_e2e_test`) are not part of that API.
- In this spec "group" and "node" are the configured levels of a profile (its terminology may call
  them stage and server); "client" is a local INUBIT client installation that contains StartCLI.

## Clarifications

### Session 2026-11-06

- Q: How is "one StartCLI path per version" configured? → A: A new version-keyed setting in
  `defaults` maps an INUBIT major.minor (e.g. `8.1`, `9.0`) to a client directory and its JDK; a
  node uses the entry of its effective version. `cli.home`/`cli.javaHome` on the node or its group
  keep winning; `defaults.cliHome`/`cliJavaHome` remain the client without a version.
- Q: Which capabilities become supported on 9.0? → A: All of them — every read and every write,
  including `run_e2e_test`. (Revised 2026-11-06: 017 ships in 0.8.0, and the person releases
  0.8.0 without the live check, accepting the risk; the live check follows after the release.)
- Q: When does the live check against a 9.0 node happen? → A: Not during this implementation;
  until then 9.0 fixtures derived from the documentation are marked as such. Revised by the
  person (2026-11-06): "Ich bin mutig und will ohne Live Test releasen" — 0.8.0 is released
  without it; the live check is done after the release, the person accepted the risk.
- Q: How is a node pinned to 9.0? → A: A new `versionLine` value `V9_0` (9.0.x, fully supported);
  `V9_X` keeps its meaning "some 9.x version" with unchanged behaviour.
- Spec review round 1 (see `reviews.md`): the configuration key is `defaults.cliClients`;
  `V9_X` uses the version-less client; a client check belongs to the client it checked (FR-017);
  `list_nodes` gains one additive field.
- Q: A write is admitted on the version known at admission and sent later; the known version can
  change in between (0.8.0 has the same gap for 8.1 → 9.0). Close it in 017 or later? → A: Close
  it in 017 (plan review 5, the person revised the earlier "follow-up after 0.8.1"): a write is
  sent only while the node's known version is the one it was admitted on, for every transition
  including 8.1 ↔ 9.0 (FR-018).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Work with INUBIT 9.0 nodes like with 8.1 nodes (Priority: P1)

An operator whose stage runs INUBIT 9.0 uses the same tools as on 8.1: health, diagnosis, logs,
inventory, exports, and — where write access is configured — process control, development writes
and deployments between 9.0 stages.

**Why this priority**: it is the request itself; today every write on 9.0 is refused and reads
carry an "unverified" warning.

**Independent Test**: with recorded or documentation-derived 9.0 responses of a fake INUBIT server
and a fake 9.0 client, call each tool on a node pinned to 9.0 and on an `AUTO` node detected as
9.0.7; each runs without a version warning and with the same result shape as on 8.1.

**Acceptance Scenarios**:

1. **Given** a node detected as INUBIT 9.0.7 with a 9.0 client, **When** a read tool runs,
   **Then** it returns its result without the "not verified" warning (FR-001).
2. **Given** a 9.0 node with effective write access and a 9.0 client, **When** `restart_process`,
   `kill_process`, a development write or `deploy_release` between two 9.0 stages runs, **Then**
   it is admitted by the version check and runs as on 8.1 (FR-001, FR-002).
6. **Given** a 9.0 stage with an end-to-end test configured, **When** `run_e2e_test` runs, **Then**
   it is admitted and behaves as on 8.1 (FR-002).
3. **Given** a 9.0 node, **When** a StartCLI-backed tool runs, **Then** StartCLI is started with
   the address `<baseUrl>` (no servlet path), unless `cli.url` is configured (FR-004).
4. **Given** a 9.0 endpoint that can answer both XML and JSON, **When** the server reads it,
   **Then** it receives the format its parser expects (FR-007).
5. **Given** a 9.0 node that rejects the credentials, **When** a REST tool runs, **Then** the result
   is `AUTH_FAILED` as on 8.1, although the 401 body is JSON (FR-008).

---

### User Story 2 - One StartCLI client per INUBIT version (Priority: P1)

The operator runs 8.1 and 9.0 stages side by side, for example during an upgrade. StartCLI of
8.1 refuses 9.0 servers and needs JDK 17; StartCLI of 9.0 needs JDK 21. The operator configures
which client and JDK serve which INUBIT version, and every node uses the one that fits its version
without being configured node by node.

**Why this priority**: without a fitting client, every StartCLI-backed tool on a 9.0 node is
refused; mixed landscapes are the normal state during an upgrade.

**Independent Test**: a profile with an 8.1 group, a 9.0 group and an `AUTO` group, and two client
installations (8.1 with JDK 17, 9.0 with JDK 21); a StartCLI-backed tool on each node launches the
client and JDK of that node's version.

**Acceptance Scenarios**:

1. **Given** clients configured in `defaults` for the versions 8.1 and 9.0 and no `cli.home` on
   groups or nodes, **When** a StartCLI-backed tool runs on an 8.1 node and on a 9.0 node, **Then**
   each uses the client and JDK of its version (FR-003).
2. **Given** an `AUTO` node that was 8.1 and is detected as 9.0 after an upgrade (by the version
   check of a tool or by `get_health`), **When** the next StartCLI-backed tool runs, **Then** the
   9.0 client and the 9.0 default address are used without a configuration change, and the
   client check of the 8.1 client is not reused for the 9.0 client (FR-003, FR-004, FR-017).
3. **Given** a 9.0 node and no client from any source (no node, group, version entry or
   version-less client), **When** a StartCLI-backed tool runs, **Then** it reports
   `CLI_UNAVAILABLE` naming the node, its version and the setting to add (FR-005).
4. **Given** a 9.0 node, no 9.0 entry, and a version-less 8.1 client, **When** a StartCLI-backed
   tool runs, **Then** the 8.1 client is selected and the client check refuses it as for a
   mismatching client today, naming the 9.0 entry to add (FR-005).
5. **Given** a group that sets only `cli.home` (to a 9.0 client) and no `cli.javaHome`, **When** a
   StartCLI-backed tool runs on its 9.0 node, **Then** the JDK is `defaults.cliJavaHome` as today,
   and `--check-config` warns if that JDK is not JDK 21 (FR-003, FR-006).
6. **Given** `--check-config`, **When** clients per version are configured, **Then** each client is
   checked locally (`startcli -v`) and a client whose declared range excludes the version it is
   configured for is reported (FR-006).
7. **Given** the same profile on Windows, **When** a StartCLI-backed tool runs, **Then** the
   version's client is launched with `startcli.bat` as 013 defines (FR-003).

---

### User Story 3 - Existing profiles keep working (Priority: P2)

An operator with an 8.1-only profile upgrades the MCP server and changes nothing.

**Why this priority**: most profiles are 8.1-only today; upgrades must not break them.

**Independent Test**: load every configuration example of 0.8.0 unchanged; the effective StartCLI
address, client and JDK of every 8.1 node are identical to 0.8.0, and the 8.1 and 6.1 test suites
pass unchanged.

**Acceptance Scenarios**:

1. **Given** a profile with only `cliHome`/`cliJavaHome` in `defaults` and 8.1 nodes, **When** it is
   loaded, **Then** every node uses that client and the 8.1 default address as before (FR-009).
2. **Given** a node with an explicit `cli.url`, **When** it is detected as 9.0, **Then** the explicit
   address is used unchanged; if it contains `/ibis/servlet/IBISSoapServlet` and the node's
   effective version (pinned, detected or observed) is 9 or later, `--check-config` (for a pinned
   node) and the result of every StartCLI-backed tool warn that 9.0.2 and later removed that address
   (FR-004, FR-010).
3. **Given** a node pinned `V9_X`, **When** the profile is loaded, **Then** it loads, and the
   behaviour of `V9_X` is as defined in FR-011.
4. **Given** a 6.1 node (feature 012), **When** any tool runs, **Then** its behaviour is unchanged.

### Edge Cases

- **Unknown version on an `AUTO` node** (detection failed): the server cannot choose a
  version-specific client or address. Reads that need StartCLI use the version-less client
  (node/group `cli.home`, else `defaults.cliHome`) and the 8.1 default address, with the existing
  "version unknown" warning; writes are refused as today (FR-005). A node not yet detected is
  detected first (version check of 009), so this applies only after a failed detection.
- **9.0.0 and 9.0.1** accept both addresses; the server uses the address without path for every
  9.x version, so a patch upgrade to 9.0.2 needs no change.
- **A later version** (9.1, 10.0): not declared in the matrix; reads unverified, writes refused, as
  today. Its default StartCLI address is the 9.x one for every major version 9 and later.
- **Mixed versions within a stage** (one node 8.1, one 9.0): each node uses its own client and
  address; `deploy_release` keeps its rule that every source and target node must run the same
  major.minor, so a deployment from 8.1 into 9.0 is refused.
- **OAuth-only 9.0 installation** (internal user management disabled for the configured user):
  StartCLI login fails; the result is `AUTH_FAILED` with a next step naming the internal user
  management. No OAuth flow is started.
- **The 9.0 client prints its version differently** (`startcli -v`): the output is parsed
  case-insensitively; an unrecognised range is "not evaluable" (warning), as in 009. The exact 9.0
  output is captured in the operator's live check (SC-006).
- **Invalid version entries**: a key that is not `<major>.<minor>`, a duplicate key, an entry
  without `home`, or a key for a version the matrix does not declare (e.g. `9.1`) is reported by
  `--check-config`; the first three make the profile invalid, the last is a warning (FR-003).
- **REST error bodies in JSON**: messages and excerpts are scrubbed as before; no JSON error body is
  returned unfiltered.
- **Version change during a write** (an `AUTO` node is found to run 9.0 by a parallel
  `get_health` while a write admitted on 8.1 is under way, or 9.0 → 9.1): the write is not sent
  on the new version; the call is refused with "changed … during this call; nothing was sent" and
  the operator calls it again, which judges it on the new version (FR-018). In a multi-step write
  (`deploy_release`) the refused step fails like a StartCLI error; the rollback is refused as well
  and the backup stays for `restore_backup`.
- **`tls.pinPerServer` (feature 014)**: the pin check targets the effective StartCLI address, which
  is now version-dependent; for the default address host and port are those of `baseUrl` in both
  cases.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The capability matrix MUST declare INUBIT 9.0.x as supported for every read
  capability that 8.1 supports, so that reads on 9.0 run without the "unverified" warning.
- **FR-002**: The capability matrix MUST declare every INUBIT 9.0.x write capability that 8.1
  supports as supported: process control, development writes, deployment and the end-to-end test.
  Every capability declared supported on 9.0 MUST be covered by contract tests against 9.0
  responses (recorded, or derived from the official documentation and marked as such until the
  operator's live check replaces them).
- **FR-003**: The operator MUST be able to configure in `defaults`, once per INUBIT major.minor,
  which client directory and which JDK StartCLI uses for nodes of that version, as the setting
  `defaults.cliClients`: a map whose keys are `<major>.<minor>` strings (e.g. `"8.1"`, `"9.0"`) and
  whose values have `home` (required) and `javaHome` (optional), path syntax as for
  `defaults.cliHome`. A node MUST use the entry of its effective version's major.minor (detected,
  observed by `get_health`, or pinned with a minor). Precedence for the client directory: node
  `cli.home` → group `cli.home` → the version entry → `defaults.cliHome`. Precedence for the JDK:
  node `cli.javaHome` → group `cli.javaHome` → the `javaHome` of the version entry, only when that
  entry's `home` was chosen → `defaults.cliJavaHome`. A malformed key, a duplicate key or an entry
  without `home` makes the profile invalid; a key for a version the capability matrix does not
  declare is a `--check-config` warning. This MUST work on macOS, Linux and Windows (013).
- **FR-004**: Without an explicit `cli.url`, the StartCLI address MUST depend on the node's effective
  version: `<baseUrl>/ibis/servlet/IBISSoapServlet` for 8.x and earlier (including 6.1) and for an
  unknown version; `<baseUrl>` for 9.0 and later. An explicit `cli.url` MUST always be used
  unchanged.
- **FR-005**: Client selection for StartCLI-backed tools happens after the node's version is
  resolved (detection of 009 where needed) and before StartCLI is started:
  - no client from any source (FR-003) → `CLI_UNAVAILABLE`, naming the node, its version and the
    `defaults.cliClients` entry to add — when the version has a minor and `defaults.cliClients` is
    configured or the major is 9 or later; otherwise the 0.8.0 text is kept (FR-009);
  - a selected client whose declared range excludes the node's version → refused by the existing
    client check (009), its next step naming the `defaults.cliClients` entry to add under the same
    condition;
  - version unknown (failed detection) or a pin without minor (`V9_X`) → the version-less client
    (node/group `cli.home`, else `defaults.cliHome`).
- **FR-006**: `--check-config` MUST check every configured client locally (no INUBIT contact) and
  report, as warnings (as for `cliHome` today): a client whose declared version range excludes the
  version it is configured for; a missing client or JDK directory; and, for an 8.1 or 9.0 version
  entry or a node pinned to 8.1 or 9.0, an effective JDK whose major version (read from the JDK
  installation) is not the one that INUBIT version ships with (17 for 8.1, 21 for 9.0).
- **FR-007**: Every REST request MUST ask for exactly the format its parser reads (XML endpoints
  XML, JSON endpoints JSON, archives any), so that a 9.0 endpoint that offers both formats returns
  the expected one; 8.1 behaviour MUST stay the same.
- **FR-008**: REST errors of 9.0 (JSON error bodies for 401, 403, 404, 500, 503) MUST map to the
  same error codes as the corresponding 8.1 errors (`AUTH_FAILED`, `NOT_FOUND`,
  `UNEXPECTED_RESPONSE`, maintenance mode, …); the message of a JSON error body MAY be shown in an
  excerpt only after scrubbing.
- **FR-009**: Profiles that configure no version-based client MUST behave exactly as in 0.8.0 for
  every 8.1 and 6.1 node (client, JDK, address, refusals, messages).
- **FR-010**: When a node's effective `cli.url` contains `/ibis/servlet/IBISSoapServlet` and its
  effective version (pinned, detected or observed) has major version 9 or later, `--check-config`
  (for pinned nodes) MUST warn, and every StartCLI-backed tool on that node MUST add a warning to
  its result.
- **FR-011**: The `versionLine` setting MUST offer the value `V9_0`, which admits the 9.0
  capabilities without detection (as `V8_1` does for 8.1). `V9_X` MUST keep loading; it continues to
  mean "some 9.x version, minor unknown": reads unverified, writes refused, the 9.x default
  address, and the version-less client (no `defaults.cliClients` entry is chosen for it).
  `--check-config` MUST suggest `V9_0` or `AUTO` for `V9_X` nodes.
- **FR-012**: The StartCLI client check MUST recognise the version output of 8.1 and 9.0 clients
  (case of "INUBIT" ignored); a 9.0 client at a 9.0 node MUST pass the check.
- **FR-013**: Login to 9.0 MUST use user and password against the internal user management, as on
  8.1 (password on stdin for StartCLI, Basic for REST); no OAuth flow is started, and a failed
  StartCLI login on 9.0 MUST name the internal user management in its next step.
- **FR-014**: All 9.0-specific access (request formats, error mapping, parsers) MUST live in
  adapter code selected by the node's version (Constitution V); the default StartCLI address and
  the client per version are configuration defaults derived from the effective version's major
  and major.minor, computed in the configuration layer. Tool logic MUST not branch on the INUBIT
  version.
- **FR-015**: `list_nodes` and `--check-config` MUST show for each node which client directory
  (no credentials) and which StartCLI address kind (with or without servlet path) apply to its
  currently known effective version, without any additional node contact; for a node whose
  version is not yet known they show the version-less fallback and state that the final choice
  is made at runtime. In `list_nodes` this is an additive field; existing fields are unchanged.
- **FR-016**: The documentation (setup, tools, README, CHANGELOG for 0.8.0) MUST describe the 9.0
  support, the per-version client configuration, the version-dependent default address and the
  migration of an 8.1 profile when stages are upgraded to 9.0.
- **FR-017**: A client check result (009, `startcli -v`) MUST belong to the client and JDK it
  checked: when a node's selected client or JDK changes (because its effective version changed),
  the previous result MUST NOT be used, and the new client is checked before its next use.
  `get_health` on a node whose version it learns only in that call MUST report the client check of
  the client selected for the observed version.
- **FR-018**: A state-changing call MUST hand off each write (StartCLI process start, E2E request)
  only while the node's known version has the major.minor the call was admitted on and is not
  marked as possibly changed; the known version MUST NOT change between that check and the
  hand-off. Otherwise the write is refused with `UNSUPPORTED_VERSION` ("changed … during this
  call; nothing was sent", next step "Call the tool again"). This holds for every transition,
  including 8.1 ↔ 9.0, and for every write tool (`import_artifacts`, `set_active`,
  `restore_backup`, `tag_artifacts`, `run_e2e_test`, `deploy_release`, `restart_process`,
  `kill_process`).

### Key Entities

- **Client installation**: a local INUBIT client directory with StartCLI, the JDK it runs with, and
  the INUBIT version(s) it is configured to serve.
- **Effective version**: the version a node is judged by (pin, detection, `get_health` observation;
  feature 009); now also selects client and default StartCLI address.
- **Capability matrix entry 9.0**: the support state of each capability on 9.0.x.
- **9.0 port set**: the version-specific adapters selected for 9.0 nodes.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a node running INUBIT 9.0 with a 9.0 client, every tool declared supported runs
  without a version warning; in the operator's live check every such tool returns the same kind of
  result as on 8.1.
- **SC-002**: An operator can switch a stage from 8.1 to 9.0 by changing at most its `versionLine`
  (or nothing with `AUTO`) once the clients per version are configured.
- **SC-003**: 100 % of the 0.8.0 test suite and every 0.8.0 configuration example pass (8.1 and
  6.1 behaviour preserved); the only permitted test changes are expectations of the additive
  `list_nodes` field (FR-015), tests that used 9.0 as an example of an undeclared version and now
  use 9.1, and the 0.8.0 tests of the 9.0 matrix entry, which FR-001/FR-002 replace. Also
  permitted are additional assertions of the D-11 `Accept` header in the 8.1 adapter tests,
  test-harness wiring that mirrors production (the version source of `DeployHarness`) and widened
  visibility of test helpers; none of them changes an existing expectation of 8.1 or 6.1 behaviour.
- **SC-004**: No StartCLI call on a 9.x node uses the removed servlet address unless the operator
  configured it explicitly, in which case a warning is shown.
- **SC-005**: No credential appears in any new message, warning, excerpt, log line or the
  `--check-config` output.
- **SC-006**: In the operator's live check (after the 0.8.0 release; not a release gate, the
  person accepted the risk), the operator captures the exact output
  (stdout, stderr, exit status) of `startcli -v` of a 9.0 client and the responses of every REST
  endpoint used on 9.0; they are turned into contract fixtures that replace the
  documentation-derived ones, and the test suite passes with them. Until then every 9.0 fixture
  is marked as documentation-derived and the test run lists them (no build fails because of it). Exempt are only the responses
  the read-only recording cannot produce, listed by name: the success outputs of the StartCLI
  write commands (confirmed by the live check's write steps) and the REST 403, 500 and 503 bodies
  (no reproducible trigger; their handling depends on the status code, the body only feeds a
  scrubbed excerpt).

- **SC-007**: No write is handed off to a node whose known major.minor differs from the one the
  call was admitted on; in the offline tests a version change between admission and hand-off
  refuses the write for every write tool, and a version change requested during a hand-off takes
  effect only after it.

## Assumptions

- The test branch `0.8.0` (012 + 013 + 016) is merged into this branch before planning; 017 builds
  on 012's capability matrix (CLI-only ranges, split diagnosis capabilities) and 013's Windows
  StartCLI support instead of preparing a later merge.
- The configured technical user exists in INUBIT's internal user management on 9.0 nodes.
- 9.0 means 9.0.0 to the latest 9.0 patch (9.0.10 at the time of writing). 9.1 and later are not
  declared.
- 9.0 response fixtures are derived from the official documentation where no recording exists; they
  are replaced by recordings during the operator's live check. The live check against a 9.0 node is
  done by the operator, not by this session.
- `deploy_release` keeps requiring the same major.minor on every source and target node; an upgrade
  of a stage from 8.1 to 9.0 is INUBIT's migration procedure, not a deployment.
- `run_e2e_test` posts to user-defined SOAP listeners, which 9.0 keeps; it is declared supported
  on 9.0 (FR-002).
- The operator's live check against a 9.0 node happens after the 0.8.0 release (the person
  accepted the risk of releasing without it); until then the 9.0 support rests on
  documentation-derived fixtures.

## Out of Scope

- OAuth / Keycloak login of StartCLI or REST.
- INUBIT 9.1 or later; the OpenAPI endpoints of 9.0.2+.
- Deployments across major.minor versions (8.1 → 9.0).
- New tools; every tool keeps its name and input, and its result keeps every existing field
  (only `list_nodes` gains the additive field of FR-015).
- Automatic installation or download of INUBIT clients or JDKs.
