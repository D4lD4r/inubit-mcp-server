# Feature Specification: Customer-Agnostic Configuration

**Feature Branch**: `002-customer-agnostic-config`

**Created**: 2026-10-04

**Status**: Draft

**Input**: User description: "Make the INUBIT MCP server customer-agnostic so it can be used for different
customers/projects purely through configuration, without source-code changes. Builds on feature 001.
(1) Rename the base package to de.dadecker.inubit.mcp and the Maven coordinates to
de.dadecker:inubit-mcp-server. (2) Keep the two-level target structure but make the names of both levels
freely configurable. (3) One configuration per customer/project with its own MCP client registration;
instances must run side by side without interfering (credential variable prefix, audit, caches, temporary
exports); the profile must be visible to the AI assistant. (4) Remove all customer-specific assumptions
from code defaults and repository artifacts; generic capabilities (trust store, certificate pinning) stay.
(5) All behaviour and safety guarantees of feature 001 remain; documented migration path for existing
configurations."

## Overview

Feature 001 delivered an INUBIT MCP server whose structure, defaults and documentation were shaped by
the first customer it was built for: the two target levels are called "stage" and "server", credential
variables follow one fixed naming scheme, a customer's user group is the built-in default owner for
inventory lookups, and repository documents name that customer's hosts and groups.

This feature turns the server into a product that a developer or operator can use for any customer or
project by writing a configuration file — never by changing source code. Each customer gets its own
**configuration profile**; several profiles can be registered with the same AI assistant at the same
time and work side by side without touching each other's credentials, audit records or temporary data.

**Actors**

- **Developer/Operator**: works for one or more customers/projects; maintains one configuration profile
  per customer and registers each profile with the AI assistant.
- **AI assistant**: sees the tools of every registered profile and must be able to tell which customer's
  systems a tool talks to.
- **Maintainer**: develops the server itself; must be able to ship one build that serves all customers.

**Terminology used in this specification**

- **Profile**: one customer/project configuration (one configuration file, one MCP client registration).
- **Group** and **node**: the two levels of the target structure (feature 001: "stage" and "server").
  A node is one INUBIT server; a group is a named set of nodes. Each profile chooses its own display
  names for both levels (for example "Stage"/"Server", "Umgebung"/"Knoten", "Environment"/"Node").
- **Node id**: `<group>/<node>`, unchanged in structure from feature 001.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Serve a new customer by configuration only (Priority: P1)

A developer starts working for a second customer whose INUBIT landscape is organised in "Umgebungen"
with "Knoten". Using the released build, they write a new configuration profile for that customer
(profile name and description, their terminology, groups and nodes, owner for inventory lookups, TLS
settings, credentials via environment variables) and register it with their AI assistant. Without any
source-code change, the assistant can answer health, diagnosis and inventory questions for the new
customer, and all tool descriptions, messages and summaries use "Umgebung"/"Knoten".

**Why this priority**: it is the core promise of the feature; everything else supports it.

**Independent Test**: with the shipped build only, create a profile for a fictitious customer with
custom level names and a fake/test INUBIT endpoint, run the configuration check and call `list` and
`health` capabilities; verify that no customer-specific value from feature 001 is needed and that the
custom terminology appears in tool descriptions and results.

**Acceptance Scenarios**:

1. **Given** the released build and a new profile file with level names "Umgebung"/"Knoten",
   **When** the developer runs the configuration check, **Then** it succeeds without any setting that
   refers to feature 001's customer, and the summary lists the nodes using "Umgebung"/"Knoten".
2. **Given** that profile is registered with the AI assistant, **When** the assistant lists the tools,
   **Then** the tool descriptions refer to "Umgebung" and "Knoten" (not "stage"/"server") and state the
   profile's name and description.
3. **Given** the profile defines no inventory owner, **When** an inventory capability is called,
   **Then** it is refused with a configuration error explaining which setting is missing, while all other
   capabilities work.
4. **Given** the profile uses a self-signed certificate, **When** it configures a trust store and a
   pinned certificate fingerprint, **Then** connections succeed with the same safety rules as in
   feature 001.

---

### User Story 2 - Run several customer profiles side by side (Priority: P1)

The developer has profiles for two customers registered at the same time. Both customers happen to use
a group called "test" with a node "node1". The assistant may call tools of both profiles in the same
conversation. Credentials, audit records, caches and temporary export data of the two profiles never
mix, and every result and audit record makes clear which profile it belongs to.

**Why this priority**: working for several customers in parallel is the reason to make the server
customer-agnostic; interference would be a security and correctness problem.

**Independent Test**: register two profiles with overlapping group/node names and different credential
variables; call tools of both concurrently; verify each profile uses only its own credentials, audit
location, cache entries and temporary directories, and that results and audit records name the profile.

**Acceptance Scenarios**:

1. **Given** two profiles that both contain node `test/node1`, **When** credentials are resolved,
   **Then** each profile reads only environment variables under its own prefix, and a variable meant
   for one profile is never used by the other.
2. **Given** both profiles have write access enabled for a test node, **When** an action is executed in
   one profile, **Then** its audit records are stored in that profile's audit location and name the
   profile, and a confirmation code issued by one profile is never accepted by the other.
3. **Given** both profiles run inventory exports at the same time, **When** one profile's server stops,
   **Then** only that profile's running exports and temporary directories are cleaned up, and the
   startup cleanup of one profile never deletes another running profile's temporary data.
4. **Given** the assistant receives a result from either profile, **When** it reads the result, **Then**
   the profile name is identifiable (from the tool's registration and from the result itself).

---

### User Story 3 - Migrate the existing configuration (Priority: P2)

The developer already uses the feature 001 configuration for their first customer. They follow the
migration guide to turn it into a profile, choose the terminology "Stage"/"Server", keep their existing
credential environment variables by setting the credential prefix explicitly, and re-register the
server. Everything that worked before keeps working.

**Why this priority**: the first customer must not lose a working setup; but it affects one existing
installation and is a one-time step.

**Independent Test**: take a configuration in the feature 001 format, apply the documented migration
steps, and verify that the configuration check passes, the same nodes are listed and the same credential
variables are used.

**Acceptance Scenarios**:

1. **Given** a configuration in the feature 001 format, **When** the server starts with it, **Then** it
   refuses to start with a clear message that names the migration guide and each obsolete or renamed
   setting.
2. **Given** the developer applies the migration guide, **When** the configuration check runs, **Then**
   it passes, lists the same nodes, and reports the same credential variable names as before when the
   credential prefix is set to the old scheme.

---

### User Story 4 - Ship a neutral product (Priority: P2)

The maintainer prepares a release. The build, its documentation, examples, specifications and test data
contain no customer names, host names, user groups or other customer-specific values; generic features
introduced for the first customer remain available as configuration options. The code's base namespace
and coordinates belong to the maintainer.

**Why this priority**: required to hand the server to further customers without disclosing another
customer's details, and to avoid accidental reliance on customer-specific defaults.

**Independent Test**: search the repository's current files for the first customer's identifiers (company
name, host names, user groups, group/diagram names, personal logins) and for the old base namespace; run
the full test suite.

**Acceptance Scenarios**:

1. **Given** the repository's current files, **When** they are searched for the first customer's
   identifiers and the old namespace, **Then** no match is found (git history is out of scope).
2. **Given** the shipped defaults, **When** no value is configured for a customer-specific setting
   (inventory owner, credential prefix, profile name), **Then** the server either derives a neutral
   value from the profile (credential prefix) or reports the setting as required — it never falls back
   to a value of a specific customer.
3. **Given** the full automated test suite, **When** it runs after the rename and cleanup, **Then** all
   tests pass and the behaviour of all 8 tools is unchanged except for the documented terminology and
   configuration changes.

---

### Edge Cases

- **Level names that collide with each other or with reserved words** (for example both levels named
  "Node", or names containing characters that break tool descriptions): rejected at startup with a clear
  message.
- **Two profiles with the same profile name** (e.g. two copies of one file) running at the same time:
  the configuration check warns that profile names must be unique per workstation; if it happens anyway,
  the instances share the audit location (records stay intact because every record is appended
  atomically and names its node) and keep their temporary data apart by process.
- **Credential prefix collisions** between profiles (for example two profiles configured with the same
  explicit prefix, or nested prefixes such as `INUBIT_ACME` with group `2-test` and `INUBIT_ACME_2` with
  group `test`): when a file of another profile (another profile name) in the default configuration
  directory derives a credential variable name of this profile — by nested prefixes or by an equal prefix
  with equal group names — that is a startup error naming the shared variable names; a file with the same
  profile name is a copy and only gets a warning (see "Two profiles with the same profile name"); the same
  effective prefix without a shared name is a warning (best effort); profile files elsewhere are the
  operator's documented responsibility; never silently cross-used within one process.
- **Profile name with characters unsuitable for variable names or directory names**: rejected or mapped
  deterministically (documented mapping), never producing unsafe paths.
- **Old configuration keys** (feature 001 format) mixed with new keys: rejected with a message naming
  each obsolete key and its replacement.
- **Missing inventory owner**: inventory tools refuse with a configuration error; other tools unaffected.
- **Stale temporary export data** left by a crashed instance of another profile: the startup cleanup of a
  profile only removes data that belongs to that profile and whose owning process is gone.
- **Audit records written before the migration** (feature 001 location and format): remain readable and
  untouched; the migration guide explains where they are.

## Requirements *(mandatory)*

### Functional Requirements

**Product identity**

- **FR-001**: The server's code base namespace MUST be `de.dadecker.inubit.mcp` and its build
  coordinates `de.dadecker:inubit-mcp-server`; no code, configuration or documentation may reference the
  previous namespace.

**Configuration profile**

- **FR-002**: Every configuration MUST declare a profile with a name (required; short identifier) and a
  description (optional free text, bounded length). The profile name MUST follow a documented pattern
  suitable for identifiers, directory names and environment variable names.
- **FR-003**: The profile name and description MUST be visible to the AI assistant: in the server's
  self-identification towards the client, in every tool description (as a short prefix such as
  "<profile>: <description>"), and in the result of the server listing capability.
- **FR-004**: The server MUST accept the profile's configuration file explicitly (as today) and MUST
  additionally offer a way to select a profile by name that resolves to a per-profile file in the
  default configuration location; the documented setup uses one file and one MCP client registration per
  profile.

**Configurable terminology (two levels)**

- **FR-005**: The two target levels MUST keep their structure (group of nodes; node id `<group>/<node>`;
  read capabilities accept a group or a node id; write capabilities accept only a node id).
- **FR-006**: Each profile MUST be able to configure display names for both levels (singular and plural).
  Defaults MUST be neutral terms that do not refer to any customer.
- **FR-007**: Tool descriptions, input parameter descriptions, human-readable messages (errors, warnings,
  next steps), the configuration check summary and the server listing result MUST use the configured
  display names.
- **FR-008**: Machine-readable names (configuration keys, tool input and output field names, error codes)
  MUST be neutral and identical for all profiles, so that results stay comparable and documentation stays
  generic; the configured display names MUST NOT change the structure of results.
- **FR-009**: The configuration check MUST reject display names that are empty, identical for both
  levels, too long, or contain characters outside a documented safe set.

**Isolation between profiles**

- **FR-010**: Credential environment variable names MUST be derived from a configurable prefix plus
  group and node name. The default prefix MUST include the profile name (so that two profiles with the
  same group/node names never share variables by default); an explicit prefix MUST be configurable to
  support existing variables.
- **FR-011**: Audit records MUST include the profile name, and the default audit location MUST be
  separate per profile.
- **FR-012**: Temporary export data and the startup cleanup of stale temporary data MUST be scoped to the
  profile: an instance MUST never delete temporary data of another profile or of a running instance.
- **FR-013**: Confirmation codes, caches and credential lockout protection MUST be scoped to one profile
  instance (no sharing across instances).

**No customer-specific defaults**

- **FR-014**: The inventory owner MUST NOT have a built-in default value; it MUST be configured (per
  profile, overridable per group/node). Without it, inventory capabilities MUST refuse with a
  configuration error naming the setting; other capabilities MUST work.
- **FR-015**: No other built-in default, example, message, tool description, test name or test fixture
  may contain identifiers of a specific customer (company, hosts, user groups, diagram/module/group
  names, personal logins). Test data MUST use neutral, clearly fictitious names.
- **FR-016**: Generic capabilities introduced for the first customer (trust store, certificate pinning
  with disabled hostname verification, self-signed certificate support, local command line client
  location and runtime) MUST remain available as configuration options with unchanged safety rules.

**Compatibility and migration**

- **FR-017**: All capabilities, safety guarantees and behaviours of feature 001 MUST remain (read-only
  default, production lock incl. runtime defence, two-step confirmation bound to the previewed state,
  audit before execution, credential lockout protection, secret scrubbing, bounded results, timeouts),
  except for the documented terminology and configuration changes of this feature.
- **FR-018**: A configuration in the feature 001 format MUST be rejected with a message that names each
  obsolete or renamed setting, its replacement, and the migration guide.
- **FR-019**: The documentation MUST include a migration guide from the feature 001 format, including how
  to keep existing credential variable names (explicit prefix) and where existing audit records are.
- **FR-020**: The documentation MUST include a complete, neutral example profile and a guide for running
  several profiles side by side (one file and one MCP client registration per profile).

### Key Entities

- **Profile**: name, description, terminology (group singular/plural, node singular/plural), credential
  prefix (explicit or derived), audit location (explicit or derived), groups.
- **Terminology**: display names of the two levels (singular and plural); used only for human-readable
  text.
- **Group**: named set of nodes (formerly "stage"); carries production classification and inherited
  settings.
- **Node**: one INUBIT server (formerly "server"); id `<group>/<node>`; connection and override settings.
- **Audit Record**: as in feature 001, plus the profile name.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer can set up a new customer profile from the documentation and get a first
  successful health check in under 15 minutes, without reading or changing source code.
- **SC-002**: A search of the repository's current files for the first customer's identifiers and the old
  namespace returns 0 matches.
- **SC-003**: With two profiles registered at the same time and identical group/node names, 100% of
  credential resolutions, audit records and temporary-data operations in the test suite use only the
  calling profile's resources.
- **SC-004**: The full feature 001 test suite (adapted only for renamed packages, neutral test data and the
  documented configuration format) passes with 0 failures, and all 8 tools keep their behaviour.
- **SC-005**: A configuration in the feature 001 format is rejected with a message naming 100% of the
  obsolete settings; after following the migration guide, the configuration check passes and lists the
  same nodes and credential variables as before.
- **SC-006**: In a review of all tool descriptions and messages produced by the test suite for a profile
  with custom terminology, 0 occurrences of the default or previous level names appear where the custom
  names apply.

## Assumptions

- **One profile per process**: one server instance serves exactly one profile; multi-customer operation
  means several instances (one MCP client registration each). A single instance serving several
  profiles is out of scope.
- **Machine-readable field names**: tool input/output fields and configuration keys use neutral terms
  ("group", "node"); this is a breaking change to the tool contract and configuration format of feature
  001, accepted by the user, and is covered by the migration guide. MCP clients re-read tool definitions
  on reconnect.
- **Defaults**: neutral default display names are "Group"/"Groups" and "Node"/"Nodes"; the default
  credential prefix is `INUBIT_<PROFILE>`; the default audit location is a per-profile subdirectory of the
  feature 001 audit base directory.
- **Repository cleanup scope**: current files only (code, tests, fixtures, docs, specifications including
  feature 001's documents, handoff notes). Git history is not rewritten. Customer-specific local files
  (the user's own configuration, trust store) stay outside the repository and are migrated by the user
  following the guide.
- **Recorded fixtures** from feature 001 stay valid as test data after replacing customer-specific names
  (groups, diagrams, modules, logins) with neutral fictitious names; their structure is unchanged.
- **Open user tasks of feature 001** (T010 Workbench comparison, T115 live write check, T125
  walkthrough) remain open and are not part of this feature; they will be performed with the migrated
  profile.
- **INUBIT version support** is unchanged (8.1.x verified against 8.1.17).
