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
and declare in the profile that secrets come from there, with the region and where the parameters
live. The server fetches the values itself; no secret is set as an environment variable or written
to a file.

**Why this priority**: This is the new capability the feature asks for.

**Independent Test**: With a stubbed Parameter Store, configure a profile for the AWS source, start
the server and confirm that each node gets its username and password from the expected parameters,
that `--check-config` names the parameters (never values), and that a missing or inaccessible
parameter produces a clear error naming the parameter and the node.

**Acceptance Scenarios**:

1. **Given** a profile with the AWS Parameter Store source, a region and a parameter path, and
   parameters for every node, **When** a tool runs on a node, **Then** it authenticates with the
   values from Parameter Store, and no secret appears in results, logs or errors.
2. **Given** a parameter that does not exist or that the server's AWS identity may not read,
   **When** the secret is needed, **Then** the error names the node, the parameter name and the
   kind of failure (not found, access denied, store unreachable), and suggests the fix, without
   any value.
3. **Given** encrypted (secure string) parameters, **When** they are read, **Then** they are
   decrypted by the store and used; a parameter stored as plain text is accepted [default
   assumed: with a warning recommending encryption].
4. **Given** that the server's AWS access itself is configured outside the profile (the usual AWS
   credential chain: environment, shared AWS profile, instance or container role), **When** the
   profile names an AWS profile or no AWS credentials at all, **Then** the server uses that chain;
   the profile never contains AWS access keys.

---

### User Story 3 - See and check where each node's secrets come from (Priority: P2)

An administrator wants to verify a profile before handing it to a colleague or deploying it. They
run `--check-config` and see, per node, the source, the exact names (variable or parameter) and
whether they are resolvable, without any value being printed.

**Why this priority**: Misconfigured secret names are the most common setup failure today; the
check must cover the new source as well as the old.

**Independent Test**: Run `--check-config` on profiles with each source and with deliberate
mistakes (missing parameter, wrong region, missing variable) and confirm the report names the
problem per node and exits with the documented code.

**Acceptance Scenarios**:

1. **Given** a profile with the AWS source, **When** `--check-config` runs with AWS access,
   **Then** it reports per node which parameters exist and are readable, without values.
2. **Given** a profile with the AWS source, **When** `--check-config` runs without AWS access,
   **Then** it still validates the profile structure and lists the parameter names, and reports
   that resolvability could not be checked.

---

### User Story 4 - Rotate a password without restarting (Priority: P3)

The password of an INUBIT technical user is rotated in Parameter Store. The server picks up the new
value without a restart, at the latest when the old value is rejected.

**Why this priority**: Central stores exist largely for rotation; without it, a rotation still
needs a restart, which is acceptable for a first version.

**Independent Test**: With a stubbed store, change a parameter value, provoke a rejected login with
the old value, and confirm the next call uses the new value while the 60-second protection against
account lockout still holds.

**Acceptance Scenarios**:

1. **Given** a rotated password, **When** INUBIT rejects the old one, **Then** the server re-reads
   the secret once and retries no earlier than today's lockout protection allows.
2. **Given** an unchanged password that INUBIT rejects, **When** the secret is re-read and is the
   same, **Then** the node is paused as today and no further login is attempted within the pause.

### Edge Cases

- AWS is unreachable at startup: [NEEDS CLARIFICATION: are secrets from AWS loaded at startup for
  every node (the server refuses to start if any is missing, as today with environment variables),
  or on first use per node (the server starts without AWS access, and only the nodes that are used
  need their secrets, so production secrets are not fetched unless production is queried)?]
- A group or node needs secrets from a different source than the rest of the profile (for example
  development credentials from environment variables, production from Parameter Store):
  [NEEDS CLARIFICATION: is the source set once per profile, or can groups and nodes override it?]
- Parameter names: [NEEDS CLARIFICATION: are parameter names derived by a fixed scheme from a
  configured path, group and node (like the environment-variable names today, e.g.
  `/inubit-mcp/acme/dev/node1/password`, node-specific before group-wide), or named explicitly per
  group/node in the profile, or both (derived by default, explicit override)?]
- A parameter value contains characters that are not allowed in a username or password today (a
  `:` in a username, control characters, a leading `-`): refused as today, naming the parameter,
  never the value.
- The trust store password and the end-to-end test credentials: come from the same source and
  follow the same naming rules as username and password.
- Two profiles read the same parameter: allowed (unlike environment variables, a shared parameter
  is deliberate); the shared-name error of environment variables does not apply to parameters.
- Very many nodes: reading secrets must not take noticeably longer than today's startup (batched
  reads where the store allows).
- The store answers slowly or throttles: bounded by a timeout, reported like any unreachable store.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The profile MUST be able to declare where secrets come from. Supported sources in
  this feature: environment variables and AWS Systems Manager Parameter Store. The design MUST
  allow further sources later (for example the macOS Keychain or another vault) without changing
  how tools obtain secrets.
- **FR-002**: Without a source setting, the server MUST behave exactly as today (environment
  variables, `credentials.envPrefix`, all existing naming, precedence and validation rules).
- **FR-003**: The environment-variable source MUST support the existing prefix and naming scheme
  and MUST be selectable explicitly; existing profiles MUST NOT need any change.
- **FR-004**: The AWS Parameter Store source MUST be configurable in the profile with at least the
  region and where the parameters are found; it MAY name an AWS profile of the local AWS
  configuration. The profile MUST NOT contain AWS access keys or any other secret.
- **FR-005**: The server MUST authenticate to AWS through the standard AWS credential chain
  (environment, shared configuration and profile, container or instance role), so that the same
  profile works on a workstation and in a hosted environment.
- **FR-006**: For every node the server MUST resolve username, password and, where configured, the
  trust store password and the end-to-end test credentials from the node's source, with
  node-specific names taking precedence over group-wide ones (as today).
- **FR-007**: Secrets from any source MUST be handled exactly as today: never in tool parameters,
  results, logs, error messages or command lines; scrubbed from error output; the same validity
  checks on usernames and passwords; the same lockout protection (Constitution II).
- **FR-008**: Every error about a secret MUST name the node, the source and the exact name
  (variable or parameter) and the kind of failure (missing, access denied, store unreachable,
  invalid value), never a value, and give a next step.
- **FR-009**: `--check-config` MUST show the source per node and the names it uses; for the AWS
  source it MUST check, when AWS access is available, that each required parameter exists and is
  readable, and otherwise report that this could not be checked without failing the structural
  validation.
- **FR-010**: When INUBIT rejects credentials from a source that supports re-reading, the server
  MUST re-read that node's secrets once before pausing the node, and MUST NOT attempt more logins
  than today's lockout protection allows.
- **FR-011**: Reading from the store MUST be bounded by a timeout and MUST NOT block tools on nodes
  whose secrets are already available.
- **FR-012**: The access to the store MUST need read permission only, on the configured parameters
  only; the documentation MUST name the minimal permissions (read the parameters, decrypt with the
  key used).
- **FR-013**: Every secret read from an external store MUST be logged (structured, stderr) with
  node, source and parameter name and outcome, never the value (Constitution VII).
- **FR-014**: The documentation MUST describe both sources, the profile settings, the naming
  scheme, the AWS permissions and how to move an existing installation from a start script with
  environment variables to Parameter Store.

### Key Entities

- **Secret source**: where a profile's (or a group's/node's) secrets come from: its kind
  (environment variables, AWS Parameter Store) and its settings (prefix; region, path, optional
  AWS profile).
- **Secret reference**: the name under which one secret of one node is found in its source (a
  variable name or a parameter name), with its precedence (node-specific before group-wide).
- **Secret kind**: username, password, trust store password, end-to-end test username and
  password.
- **Resolved secret**: the value held in memory for one node and kind, never shown, with the time
  it was read (for re-reading after rotation).

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
- **SC-005**: Secret resolution for a profile of 10 nodes adds no more than a few seconds to
  startup or to the first call on a node.

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
