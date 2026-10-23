# Feature Specification: Configurable Secret Sources

**Feature Branch**: `010-secret-sources`

**Created**: 2026-10-17

**Status**: Draft

**Input**: User description (translated from German): "Secret sources configurable in the
profile. First implementation as now, further implementation AWS Parameter Store. Necessary
configuration in the profile."

## Context & Findings *(verified before writing this spec)*

- **Today there is exactly one source: environment variables.** Their names are derived from the
  profile's credential prefix (`credentials.envPrefix`, default `INUBIT_<PROFILE>`) plus group and
  node: `<PREFIX>_<GROUP>[_<NODE>]_USERNAME`, `_PASSWORD`, `_TRUSTSTORE_PASSWORD`, and the
  end-to-end test credentials. The node-specific name wins over the group name.
- **All secrets are read once at startup.** A missing username or password of a node is a startup
  error; `--check-config` names the exact variables per node, never values. Other profiles in the
  configuration directory are checked for shared variable names.
- **Secrets never reach the conversation** (Constitution II): they are not tool parameters, never
  appear in results, logs, error messages or command lines; StartCLI gets the password on stdin.
  Rejected credentials pause a node for 60 s so that a wrong password does not lock the account.
- **In practice the variables are filled from a secret store by a start script** (for example the
  macOS Keychain). The server itself does not know where the values come from.
- **Why another source**: running the server where a central secret store is the norm (a shared
  host, a container, the organization's MCP gateway) needs secrets fetched from that store, with
  its access control, rotation and audit, instead of variables set by hand.

## Clarifications

### Session 2026-10-17

- Q: When are secrets from AWS loaded? → A: On first use per node. The server starts without AWS
  access; a node's secrets are fetched the first time a tool needs that node, so secrets of nodes
  that are never used (for example production) are never fetched.
- Q: Is the source set per profile, or per group/node? → A: Per profile, with an override per
  group or node (for example development credentials from environment variables, production from
  Parameter Store).
- Q: How are parameters named? → A: Explicitly per group or node in the profile (no derived
  scheme for Parameter Store). A group-level name applies to every node of the group; a node-level
  name wins.

### Session 2026-10-20 (spec review r1)

- Q: Does a fan-out call (for example `get_health` without a target) count as first use of every
  node it addresses? → A: Yes. Every call that has to authenticate to a node fetches that node's
  secrets, also within a fan-out; only secrets of nodes no call ever addresses are never fetched.
- Q: May a group or node override set its own source settings (region, AWS profile, prefix)? → A:
  Yes. An override replaces the whole source including its settings (for example production in
  another AWS account). All secret kinds of a node come from the same effective source.
- Q: Is rotation without restart (US4) in scope? → A: Yes, in its simple form: after a rejected
  login the node's secrets are re-read once; if they changed, the next call (not the rejected one)
  uses the new value; if not, the node is paused for 60 s as today. No time-based refresh.
- Q: A node-level parameter name is configured but the parameter is missing or empty. → A: Error.
  The node-level name is the only one read for that kind; there is no fallback to the group-level
  parameter.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Keep using environment variables, now as an explicit, configurable source (Priority: P1)

An administrator with an existing profile changes nothing and everything works as before. An
administrator who wants to be explicit declares in the profile that secrets come from environment
variables, with the same prefix and naming rules as today.

**Why this priority**: Every existing installation depends on it. The new configuration model must
not break or change it; it is also the reference behaviour for any new source.

**Independent Test**: Load existing profiles (without any source setting) and the same profiles
with an explicit environment-variable source, and confirm that the same variables are read, the
same startup errors and `--check-config` output appear, and all existing tests stay green.

**Acceptance Scenarios**:

1. **Given** an existing profile without a secret source setting, **When** the server starts,
   **Then** it reads the same environment variables as before and behaves identically.
2. **Given** a profile that declares the environment-variable source with a prefix, **When**
   `--check-config` runs, **Then** it shows the source and, per node, the variable names it uses,
   never values.
3. **Given** a profile whose legacy `credentials.envPrefix` and a new explicit source setting
   contradict each other, **When** it is loaded, **Then** it is a configuration error naming both.

---

### User Story 2 - Fetch secrets from AWS Parameter Store (Priority: P1)

An administrator runs the server on a machine or in an environment with access to AWS. They store
each node's username and password as parameters in AWS Systems Manager Parameter Store (encrypted)
and declare in the profile that secrets come from there, with the region and the parameter name
of each secret per group or node. The server fetches the values itself; no secret is set as an environment variable or written
to a file.

**Why this priority**: This is the new capability the feature asks for.

**Independent Test**: With a stubbed Parameter Store, configure a profile for the AWS source, start
the server and confirm that each node gets its username and password from the expected parameters,
that `--check-config` names the parameters (never values), and that a missing or inaccessible
parameter produces a clear error naming the parameter and the node.

**Acceptance Scenarios**:

1. **Given** a profile with the AWS Parameter Store source, a region and explicit parameter names
   for every node, **When** a tool runs on a node, **Then** it authenticates with the
   values from Parameter Store, and no secret appears in results, logs or errors.
2. **Given** a parameter that does not exist or that the server's AWS identity may not read,
   **When** the secret is needed, **Then** the error names the node, the parameter name and the
   kind of failure (not found, access denied, store unreachable), and suggests the fix, without
   any value.
3. **Given** encrypted (`SecureString`) parameters, **When** they are read, **Then** they are
   decrypted by the store and used. A plain `String` parameter is accepted with a warning that
   recommends encryption, in the log of the fetch and in `--check-config`; a `StringList`
   parameter is refused as an invalid value.
4. **Given** that the server's AWS access itself is configured outside the profile (the usual AWS
   credential chain: environment, shared AWS configuration, container or instance role), **When**
   the source names an AWS profile of the local AWS configuration, or none, **Then** the server
   uses that profile or the default chain; the profile never contains AWS access keys. An AWS
   profile that does not exist locally is reported as an error of the first fetch (and by
   `--check-config`), not at startup.

---

### User Story 3 - See and check where each node's secrets come from (Priority: P2)

An administrator wants to verify a profile before handing it to a colleague or deploying it. They
run `--check-config` and see, per node, the source, the exact names (variable or parameter) and
whether they are resolvable, without any value being printed.

**Why this priority**: Misconfigured secret names are the most common setup failure today; the
check must cover the new source as well as the old.

**Independent Test**: Run `--check-config` on profiles with each source and with deliberate
mistakes (missing parameter, wrong region, missing variable) and confirm the report names the
problem per node and exits with the code defined in FR-009.

**Acceptance Scenarios**:

1. **Given** a profile with the AWS source, **When** `--check-config` runs with AWS access,
   **Then** it reports per node which parameters exist and are readable, without values.
2. **Given** a profile with the AWS source, **When** `--check-config` runs without AWS access,
   **Then** it still validates the profile structure and lists the parameter names, and reports
   that resolvability could not be checked.

---

### User Story 4 - Rotate a password without restarting (Priority: P3)

The password of an INUBIT technical user is rotated in Parameter Store. The server picks up the new
value without a restart when INUBIT rejects the old one.

**Why this priority**: Central stores exist largely for rotation. It is in scope in its simple
form (re-read on rejection); a time-based refresh is not.

**Independent Test**: With a stubbed store, change a parameter value, provoke a rejected login with
the old value, and confirm that the rejected call fails, the next call logs in with the new value,
and an unchanged value still pauses the node for 60 s.

**Acceptance Scenarios**:

1. **Given** a rotated password, **When** INUBIT rejects the old one, **Then** the server re-reads
   the node's username and password once; the rejected call fails as today, the value has changed,
   so the node is not paused and the next call logs in with the new value. If that login is
   rejected too, the node is paused for 60 s without another re-read.
2. **Given** an unchanged password that INUBIT rejects, **When** the secrets are re-read and are
   the same, **Then** the node is paused for 60 s as today and no further login is attempted within
   the pause.

### Edge Cases

- AWS is unreachable at startup: the server starts anyway (secrets are loaded on first use). A tool
  on a node whose secrets cannot be fetched fails with an error naming the node, the parameter and
  the reason; tools on other nodes keep working.
- A node with the AWS source but no parameter name configured for one of the required kinds
  (username, password): a configuration error at startup and in `--check-config`, naming the node
  and the missing setting.
- A node-level parameter name is configured but the parameter is missing or empty: an error of the
  call naming the node and that parameter; the group-level name is not tried.
- A group uses the AWS source and one of its nodes overrides it with environment variables (or the
  reverse, or another AWS region or AWS profile): each node uses its own effective source, and the
  group's parameter names do not apply to a node with its own source; `--check-config` shows the
  source per node.
- The secrets of a node fetched once are kept for the process lifetime (until re-read after a
  rejected login, US4). A failed fetch is not repeated for that node within 30 s; calls in that
  time fail at once with the recorded failure, so an unreachable store does not slow every call.
- Concurrent calls that need the same node's secrets share one fetch.
- A parameter value contains characters that are not allowed in a username or password today (a
  `:` in a username, control characters, a leading `-`): refused as today, naming the parameter,
  never the value.
- The trust store password and the end-to-end test credentials: come from the node's effective
  source. For environment variables they follow today's naming rules; for Parameter Store they are
  read only if a parameter name is configured for them, and without a name they are absent, as an
  unset variable is today. Configuring only one of the two end-to-end names (username or password)
  is a startup configuration error naming the node and the missing name.
- Two profiles read the same parameter: allowed (unlike environment variables, a shared parameter
  is deliberate). The checks for shared and unmatched environment variables apply only to nodes
  whose effective source is environment variables.
- A fan-out call that needs the secrets of many nodes fetches them without a noticeable delay per
  node (the fetches of different nodes do not wait for each other).
- The store answers slowly or throttles: a fetch is bounded by a 10 s timeout and reported like an
  unreachable store.
- `credentials.envPrefix` and an explicit environment-variable source: the legacy prefix stays the
  default prefix of every environment-variable source that names none; a profile-level
  environment-variable source that names a different prefix is a configuration error naming both
  (US1-3).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The profile MUST be able to declare where secrets come from, once for the profile and
  optionally overridden per group and per node (node before group before profile). An override
  replaces the whole source of that group or node, including its settings (prefix; region, AWS
  profile); all secret kinds of a node come from the same effective source. Supported sources in
  this feature: environment variables and AWS Systems Manager Parameter Store. Adding a further
  source later MUST NOT require changes to tools.
- **FR-002**: Without a source setting, the server MUST behave exactly as today (environment
  variables, `credentials.envPrefix`, all existing naming, precedence and validation rules).
- **FR-003**: The environment-variable source MUST support the existing prefix and naming scheme
  and MUST be selectable explicitly; existing profiles MUST NOT need any change.
- **FR-004**: The AWS Parameter Store source MUST be configurable in the profile with the region
  (required) and, per group or node, the full parameter name of each secret kind it needs (a
  node-level name wins over a group-level one; there is no path prefix and no derived name); it MAY
  name an AWS profile of the local AWS configuration. A parameter name is a plain hierarchical name
  (for example `/inubit/prod/node1/password`), validated at startup; version or label selectors
  and ARNs are not supported. Parameter names configured on a group or node whose effective source
  is environment variables are ignored with a warning at startup and in `--check-config`.
  Group-level parameter names apply only to
  nodes whose effective source is the group's source; a node with its own source override MUST
  configure its own names for the required kinds (otherwise a startup configuration error). The
  profile MUST NOT contain AWS access keys or any other secret.
- **FR-005**: The server MUST authenticate to AWS through the standard AWS credential chain
  (environment, shared configuration and profile, container or instance role), so that the same
  profile works on a workstation and in a hosted environment.
- **FR-006**: For every node the server MUST resolve username, password and, where configured, the
  trust store password and the end-to-end test credentials from the node's effective source, with
  node-specific names taking precedence over group-wide ones. For environment variables precedence
  works by value as today (an unset or empty node variable falls back to the group variable). For
  Parameter Store precedence works on the configured names only: if a node-level name is
  configured, it is the only one read for that kind, and a missing or empty parameter is an error.
- **FR-006a**: Secrets from AWS Parameter Store MUST be fetched on first use per node, not at
  startup; the server MUST start without AWS access. First use is the first call that has to
  authenticate to that node, including each node of a fan-out call; tools that do not contact a
  node (for example `list_nodes`) fetch nothing. One fetch loads the node's username, password and,
  if configured, trust store password together (the trust store password is needed before the
  first request); the end-to-end test credentials are fetched only when an end-to-end test runs on
  that node. A missing parameter-name setting is a startup configuration error; a missing or
  unreadable parameter is an error of the call that needs it. Environment variables keep today's
  behaviour (read and validated at startup).
- **FR-007**: Secrets from any source MUST be handled exactly as today: never in tool parameters,
  results, logs, error messages or command lines; scrubbed from error output; the same validity
  checks on usernames and passwords; the same lockout protection, except for the single extra login
  after a changed re-read that FR-010 allows for re-readable sources (Constitution II).
- **FR-008**: Every error about a secret MUST name the node, the source and the exact name
  (variable or parameter) and the kind of failure (missing, access denied, store unreachable,
  invalid value), never a value, and give a next step that refers to the effective source (for
  environment variables as today; for Parameter Store: correct the parameter or its permissions, no
  restart needed). Tool errors carry only this classification; the raw message of the store (which
  may contain account ids or role names) goes to the log only.
- **FR-009**: `--check-config` MUST show the source per node and the names it uses. For the AWS
  source it MUST, when AWS access is available, read each configured parameter and check that it
  exists, is readable and passes the same validity checks as at run time, never printing a value;
  a configured parameter (required or optional) that is missing, denied or invalid, and an AWS
  profile named in the profile
  that does not exist locally, are errors and the check exits with code 1. When AWS access is
  unavailable (no credentials resolvable from the chain, including an expired login, or the store
  unreachable or timed out), it validates the structure, lists the parameter names, reports that
  resolvability could not be checked as a warning and exits with code 0 if nothing else is wrong.
  Warnings (unencrypted `String` parameter, resolvability not checked, ignored parameter names) do
  not change the exit code. Exit codes otherwise stay as today (0 success, 1 configuration error, 2
  usage).
- **FR-010**: When INUBIT rejects the username and password of a node whose source supports
  re-reading (Parameter Store), the server MUST re-read that node's username and password once.
  The rejected call fails as today. If the username or the password differs from the value held,
  the node is not paused and exactly one further login with the new values is allowed; if that
  login is rejected too, the node is paused for 60 s without another re-read. If the values are
  unchanged (or the re-read fails), the node is paused for 60 s as today. This bounds failed logins
  to at most two per node per 60 s (today: one). Rejections that happen at the same time share one
  re-read. A failed re-read keeps the held values, is logged as a failed fetch and leads to the
  60 s pause, without an additional 30 s back-off. A rejected end-to-end test login does not
  trigger a re-read. There is no time-based refresh.
- **FR-011**: A fetch from the store MUST be bounded by a 10 s timeout and MUST NOT block tools on
  nodes whose secrets are already available. After a failed fetch the node's secrets are not
  fetched again for 30 s (calls fail at once with the recorded failure); a fetch whose value fails
  validation counts as a failed fetch (invalid value). Concurrent calls that need the same node's
  secrets share one fetch. The separate fetch of the end-to-end test credentials follows the same
  rules and FR-013.
- **FR-012**: The access to the store MUST need read permission only, on the configured parameters
  only; the documentation MUST name the minimal permissions (read the parameters, decrypt with the
  key used).
- **FR-013**: Every fetch attempt from an external store MUST be logged (structured, stderr) with
  node, source, parameter names and outcome, never a value: INFO for success, WARN for failure, one
  record per attempt, including re-reads after a rejected login (FR-010) with whether the value
  changed (never the value itself); calls refused during the 30 s pause after a failure are not
  logged again (Constitution VII).
- **FR-014**: The documentation MUST describe both sources, the profile settings, the naming
  scheme, the AWS permissions and how to move an existing installation from a start script with
  environment variables to Parameter Store.

### Key Entities

- **Secret source**: where a profile's (or a group's/node's) secrets come from: its kind
  (environment variables, AWS Parameter Store) and its settings (prefix; region, optional AWS
  profile).
- **Secret reference**: the name under which one secret of one node is found in its source (a
  derived variable name, or the explicit parameter name configured for the group or node), with its
  precedence (node-specific before group-wide).
- **Secret kind**: username, password, trust store password, end-to-end test username and
  password.
- **Resolved secret**: the value held in memory for one node and kind, never shown; replaced only
  by a re-read after a rejected login (FR-010).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of existing profiles start and behave unchanged without editing them (all
  existing tests green without changed expectations).
- **SC-002**: A profile can be switched from environment variables to Parameter Store by changing
  only the secret source settings in the profile and storing the parameters; no start script is
  needed any more.
- **SC-003**: No secret value appears in any tool result, log line, error message or
  `--check-config` output, verified by dedicated tests for both sources.
- **SC-004**: Every misconfigured secret (missing, unreadable, invalid) is reported with node,
  source and name in one message that is enough to fix it without reading logs.
- **SC-005**: With a stubbed store that answers within 1 s, the first call on a node takes at most
  2 s longer than with environment variables; startup and later calls differ by at most 50 ms from
  the environment-variable source.
- **SC-006**: Secrets of a node that no tool call addresses are never fetched from the store.

## Out of Scope

- AWS Secrets Manager, a native macOS Keychain source or any other vault (possible later sources).
- Writing, creating or rotating parameters from the server.
- Time-based refresh of secrets; re-reading environment variables.
- Assume-role or cross-account setup inside the server (use an AWS profile of the local AWS
  configuration instead).

## Assumptions

- AWS Systems Manager Parameter Store (standard parameters, `SecureString` with the default or a
  customer-managed key) is the AWS service meant by "AWS Parameter Store"; AWS Secrets Manager is
  out of scope for this feature but could be another source later.
- The machine or environment running the server already has AWS access configured by the usual
  means; setting up IAM users, roles and keys is outside the server.
- The macOS Keychain stays usable through the existing start-script approach; a native Keychain
  source is not part of this feature.
- Tests use a stubbed Parameter Store; no test contacts AWS (Constitution III). An optional live
  check against a real Parameter Store may be added like the existing live tests.
- Re-reading after rotation (US4) applies only to the external store; environment variables are
  fixed for the lifetime of the process, as today.
