# Feature Specification: Credentials Only from a Credential Store

**Feature Branch**: `011-os-credential-stores`

**Created**: 2026-10-31

**Status**: Draft

**Input**: User description (translated from German): "In principle I want to take the credentials
only from the Keychain or from the Parameter Store. The environment-variable source shall be
deprecated." Decided on 2026-10-31 (Clarifications): the operating-system credential stores of
Windows and Linux are covered as well, and the environment-variable source is removed in this
feature.

## Context & Findings *(verified before writing this spec)*

- **Two sources today** (feature 010): environment variables (the default) and AWS Systems Manager
  Parameter Store, chosen per profile, group or node with `credentials.source`
  (`type: environment | awsParameterStore`). The effective source of a node is node → group →
  profile → environment variables.
- **Environment variables** are named `<PREFIX>_<GROUP>[_<NODE>]_<KIND>` with `<KIND>` one of
  `USERNAME`, `PASSWORD`, `TRUSTSTORE_PASSWORD`, `E2E_USERNAME`, `E2E_PASSWORD`; the node name wins
  over the group name, each kind independently, by value (an unset or empty node variable falls
  back to the group variable). `<PREFIX>` is the source's `prefix`, else `credentials.envPrefix`,
  else `INUBIT_<PROFILE>`. They are read once at startup; a missing username or password is a
  startup error. Derived names shared with another profile in the default configuration directory,
  or by two groups or nodes of the same profile, are configuration errors; a `<PREFIX>_*_PASSWORD`
  variable that matches no node is a warning.
- **The macOS Keychain is not a source of the server.** The documented way for GUI clients (the
  Claude desktop app) is a start script that reads Keychain entries (service `inubit-mcp`, account
  = the variable name) with `/usr/bin/security` and exports them as environment variables before it
  starts the JAR (`docs/setup.md`, "Claude Desktop and other GUI clients (macOS)").
- **Changing a credential is inconsistent today.** For exported variables the documentation says a
  restart of the MCP client from a new shell is needed and `/mcp` → reconnect is not enough; with
  the start script a reconnect is enough. The `AUTH_FAILED` next step of an environment node always
  says "restart the MCP client from a shell with the new values" (`SecretLabels`). Parameter Store
  nodes re-read their values after a rejected login and need no restart (010 FR-010).
- **Parameter Store** values are fetched on the node's first use (10 s timeout, 30 s back-off after
  a failed fetch, one shared fetch per node), kept in memory, re-read once after a rejected login
  (at most two failed logins per node per 60 s), checked with the same value rules as variables, and
  read and checked by `--check-config` without printing values.
- **The opt-in live tests** (`mvn verify -Plive`, `LiveTarget`) resolve credentials from
  environment variables under the profile's prefix only; they ignore `credentials.source`, so nodes
  with Parameter Store or with their own `prefix` cannot be live-tested.
- **The server runs on macOS, Windows and Linux** (configuration directory `~/.config/inubit-mcp`,
  Windows `%APPDATA%\inubit-mcp`); every environment-variable rule above applies on every platform.
- A short end-to-end password from a variable (`_E2E_PASSWORD`, fewer than 4 characters) was
  accepted although it cannot be scrubbed; this is fixed independently of this feature (CHANGELOG,
  Unreleased).

## Clarifications

### Session 2026-10-31

- Q: The macOS Keychain exists on macOS only. What applies on Windows and Linux? → A: The
  operating-system credential stores of Windows (Credential Manager) and Linux (Secret Service) are
  covered as well, as part of this feature.
- Q: How hard is the environment-variable source deprecated? → A: It is removed in this feature
  (breaking change, migration needed).
- Q: What do the opt-in live tests use? → A: The same source resolution as the server (every source
  the server supports), which also closes today's gap for Parameter Store nodes.
- Q: How are credential store entries named? → A: As in today's Keychain how-to: service
  `inubit-mcp`, account = today's derived variable name (`<PREFIX>_<GROUP>[_<NODE>]_<KIND>`, node
  before group). Existing Keychain entries keep working without migration.

### Session 2026-10-31 (spec review r1)

Decisions taken on the review findings, confirmed by the product owner on 2026-10-31:

- Windows identity: a generic credential with the target name `inubit-mcp:<ACCOUNT>`; its user-name
  field is ignored. Linux: the attributes `service` = `inubit-mcp` and `account` = `<ACCOUNT>`.
- Error codes follow feature 010: what a person must fix is `AUTH_FAILED`, a store that does not
  answer in time is `UNREACHABLE`.
- Only one of the two end-to-end entries is an error of the `run_e2e_test` call (today an unset
  variable silently meant "no basic authentication"); listed in the migration notes.
- The profile-wide prefix key is `credentials.prefix`.
- Store reads are serialised (at most one access dialog at a time) and the timeout bounds the whole
  read of a node.
- One opt-in live check per operating system is a deliverable; the release needs it executed on
  macOS at least.
- The removal is released as 0.7.0 (breaking change).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Read credentials from the macOS Keychain without a start script (Priority: P1)

An administrator on macOS keeps each node's username and password in the login Keychain, as the
current how-to describes. They register the JAR directly with Claude Code or the Claude desktop
app, without a start script and without exporting anything. The server reads the entries itself
when it first needs a node.

**Why this priority**: This is the main path of the people who use the server today, and the
reason for the feature: one way that works for terminal and GUI clients alike.

**Independent Test**: With a test double of the credential store, configure a profile without any
`credentials.source`, store entries under the derived names and confirm that each node
authenticates with them, that nothing is read at startup, and that no value appears in results,
logs, errors or `--check-config`. On a macOS machine, an opt-in check reads real Keychain entries
created for a fictitious profile.

**Acceptance Scenarios**:

1. **Given** Keychain entries with service `inubit-mcp` and the accounts
   `INUBIT_ACME_DEV_USERNAME` and `INUBIT_ACME_DEV_PASSWORD` (created for the existing start
   script), **When** the server, registered directly without a script, runs a tool on `dev/node1`,
   **Then** it authenticates with these values; the entries need no change, although macOS may ask
   once per entry whether the server may read it (US1-4).
2. **Given** an entry for the node (`INUBIT_ACME_DEV_NODE1_PASSWORD`) and one for its group
   (`INUBIT_ACME_DEV_PASSWORD`), **When** the node's password is needed, **Then** the node entry is
   used; without the node entry, the group entry is used.
3. **Given** no entry for a node's password under either name, **When** a tool needs the node,
   **Then** the call fails with `AUTH_FAILED` naming the node, the store and both entry names it
   looked for (service and account), and a next step that tells how to create the entry; tools on other nodes keep
   working.
4. **Given** the Keychain asks for permission the first time the server reads an entry, **When**
   the person allows it, **Then** the call continues; **When** the person denies it, **Then** the
   call fails with `AUTH_FAILED` "access denied" naming the entry, and no further dialog is opened
   for that node within 30 s; **When** nobody answers within the store timeout, **Then** the call
   fails with `UNREACHABLE` "timeout"; never a value.

---

### User Story 2 - Read credentials from the Windows and Linux credential stores (Priority: P1)

An administrator on Windows keeps the credentials in the Windows Credential Manager; one on Linux
in the Secret Service of the desktop session (for example GNOME Keyring or KWallet). The same
profile works on all three systems: the server uses the store of the system it runs on, with the
same entry names.

**Why this priority**: The environment-variable source is removed (US3), so every platform the
server supports needs a local source; without it Windows and Linux users could only use Parameter
Store.

**Independent Test**: With a test double of each platform's store, confirm the same resolution,
errors and checks as on macOS. On a Windows or Linux machine, an opt-in check reads real entries
created for a fictitious profile.

**Acceptance Scenarios**:

1. **Given** a profile without `credentials.source` on Windows with generic credentials whose target
   names are `inubit-mcp:<derived name>` (for example `inubit-mcp:INUBIT_ACME_DEV_PASSWORD`) in the
   Credential Manager, **When** a tool runs on a node, **Then** it authenticates with them.
2. **Given** the same profile on Linux with entries (attribute service `inubit-mcp`, account = the
   derived name) in the Secret Service, **When** a tool runs on a node, **Then** it authenticates
   with them.
3. **Given** a Linux machine without a running Secret Service (for example a headless server or a
   container, or no session bus in the server's environment), or a locked store that cannot show an
   unlock prompt (no prompter, an SSH session on macOS), **When** a node with the credential store as
   source is needed, **Then** the call fails at once with `AUTH_FAILED` "store not available", which
   names AWS Parameter Store as the alternative. A locked store that can show an unlock prompt waits
   for it like an access dialog (US1-4).
4. **Given** the documentation, **When** an administrator follows it on any of the three systems,
   **Then** they can create, change and delete an entry with the system's own tools, without
   putting the value on a command line where the tool allows that, and the server reads the value
   exactly as typed.

---

### User Story 3 - Environment variables are no longer read (Priority: P1)

An administrator whose profile still relies on environment variables (exported in the shell
profile, or exported by a start script) upgrades. The server tells them precisely what changed and
how to move to a credential store or to Parameter Store; no credential is read from the environment
any more.

**Why this priority**: The removal is a breaking change. Without clear errors and a migration path,
every existing installation that exports variables stops working without a hint.

**Independent Test**: Load profiles that declare `source: { type: environment }` on each level,
start the server with leftover credential variables in its environment, and confirm the errors and
warnings, that no variable value is ever used, and that the migration documentation leads to a
working profile.

**Acceptance Scenarios**:

1. **Given** a profile that declares `credentials.source` with `type: environment` (top level,
   group or node), **When** it is loaded, **Then** it is a configuration error that names the key
   path, says that the source was removed and names the replacement (`credentialStore`, the default,
   or `awsParameterStore`) and the migration guide.
2. **Given** a profile without `credentials.source` and an environment that still contains
   variables with the derived names of its nodes (for example `INUBIT_ACME_DEV_PASSWORD`), **When**
   the server starts or `--check-config` runs, **Then** one warning lists those variable names (never
   values; the name set of FR-003), says that they are no longer read and points to the migration guide; the server reads
   the credential store.
3. **Given** a group whose environment source named its own `prefix` (for example a group with
   variables under an older name), **When** the administrator follows the migration guide, **Then**
   they can keep these names for the credential store entries with a `prefix` on the group's
   credential store source.
4. **Given** a profile with `credentials.envPrefix`, **When** it is loaded, **Then** the prefix still
   applies (now to the entry names) and a warning recommends `credentials.prefix`; a profile that
   sets profile-wide prefixes that differ (FR-005) is a configuration error naming them.

---

### User Story 4 - See and check where each node's credentials come from (Priority: P2)

An administrator verifies a profile before handing it on. `--check-config` shows per node the
source, the entry or parameter names, and whether each one can be read and is valid, without
printing any value.

**Why this priority**: Missing or misnamed entries are the most common setup failure; since the
credential store is read on first use (FR-006), the check is the place to find them before a tool
fails.

**Independent Test**: Run `--check-config` against test doubles with correct entries, missing
entries, invalid values, denied access and an unavailable store, and confirm output and exit codes.

**Acceptance Scenarios**:

1. **Given** a profile with nodes on the credential store, **When** `--check-config` runs, **Then**
   every node line shows `secrets: credentialStore (<store of this system>)` and, per kind, the
   entry name that was found (node or group name) with its outcome (`ok`, `not found`,
   `invalid value`, `access denied`, `not checked`; FR-011), never a value.
2. **Given** a required entry (username, password) that exists under neither name, or an entry
   with an invalid value, **When** `--check-config` runs, **Then** it reports an error naming the
   node and both names and exits with code 1.
3. **Given** a credential store that cannot be asked (no store on this machine, locked without a
   prompt, or an access dialog left unanswered until the timeout),
   **When** `--check-config` runs, **Then** it lists the names it would read, reports that
   resolvability could not be checked as one warning per store and exits with code 0 if nothing else
   is wrong.

---

### User Story 5 - Change a password without restarting (Priority: P2)

The password of an INUBIT user is changed. The administrator updates the entry in the credential
store (or the parameter in Parameter Store); the server picks up the new value the next time INUBIT
rejects the old one, without a restart and without `/mcp` → reconnect.

**Why this priority**: Today the steps to change a credential differ by how the variables are set
and partly need a client restart; one rule for every source removes that confusion.

**Independent Test**: With a test double of the store, change an entry, provoke a rejected login
with the old value and confirm that the rejected call fails, the next call logs in with the new
value, and an unchanged value pauses the node for 60 s.

**Acceptance Scenarios**:

1. **Given** a changed entry in the credential store, **When** INUBIT rejects the old password,
   **Then** the server re-reads the node's username and password once, the rejected call fails as
   today, and the next call logs in with the new value (the rules of 010 FR-010 apply unchanged: at
   most two failed logins per node per 60 s). The re-read runs on the rejected call and can add up
   to the store timeout to it.
2. **Given** an unchanged entry that INUBIT rejects, **When** the re-read finds the same values,
   **Then** the node is paused for 60 s as today, and the `AUTH_FAILED` next step names the entries to
   correct and says that no restart is needed.

---

### User Story 6 - Live tests use the server's own credential resolution (Priority: P3)

A developer runs the opt-in live tests against a test node whose credentials are in the credential
store or in Parameter Store. The tests read them exactly as the server would.

**Why this priority**: The live tests are opt-in and used rarely, but today they cannot run against
nodes with Parameter Store and would stop working entirely once variables are removed.

**Independent Test**: Run the live test setup (`LiveTarget`) against test doubles of both stores and
confirm it resolves the same values as the server; `docs/live-tests.md` describes how to provide
them. Separately, one opt-in live check per operating system reads entries created for a
fictitious profile from the real store (FR-017).

**Acceptance Scenarios**:

1. **Given** a live target node with the credential store as source, **When** the live tests run,
   **Then** they read its entries as the server does, and fail with the server's own error if an
   entry is missing.
2. **Given** a live target node with Parameter Store as source, **When** the live tests run, **Then**
   they fetch its parameters as the server does.

### Edge Cases

- **Entry exists but is empty**: counts as not found (as an empty variable does today); the group
  entry is used if the node entry is empty.
- **Value encoding**: a value created with the documented system tool of each operating system is
  read exactly as typed (in the character encoding that tool writes, for example UTF-16 for a
  Windows generic credential; no trailing line break added or kept). A value that cannot be decoded
  is an invalid value.
- **Invalid value** (a username with `:`, control characters or a leading `-`; a password shorter
  than 4 characters, also for `_E2E_PASSWORD`): an error naming the entry, never the value, at the
  call and in `--check-config`; a node entry with an invalid value does not fall back to the group
  entry.
- **Trust store password and end-to-end credentials**: read from the node's effective source under
  the kinds `TRUSTSTORE_PASSWORD`, `E2E_USERNAME`, `E2E_PASSWORD`. They are optional: absent entries
  mean no trust store password and no end-to-end basic authentication, as unset variables do today.
  The startup checks that today look at variable values move as follows for credential store nodes:
  - the trust store password entry is read only for a node with `tls.trustStore`; an entry for a
    node without a trust store is a `--check-config` warning ("ignored");
  - a trust store password entry for a node with CLI configured is an error of the node's first read
    (`AUTH_FAILED`, nothing is sent) and of `--check-config`;
  - the end-to-end entries are read only when an end-to-end test runs on the node; one of the two
    without the other is an error of that call naming the missing entry (a change from unset
    variables, which silently meant no basic authentication);
  - end-to-end entries together with an `http://` `e2e.soap.baseUrl` while end-to-end tests are not
    `FORBIDDEN` are refused at the first `run_e2e_test` before anything is sent, and reported as an
    error by `--check-config`.
- **Several entries for the same name** (possible in some stores, for example two Keychain items in
  different keychains of the search list, or two Secret Service items with the same attributes in
  different collections): the system's default search decides, as the system tools' own lookup does;
  the documentation says how to find duplicates.
- **The store asks the person** (a Keychain access dialog per entry, an unlock prompt of the
  keyring): reads are serialised, so at most one dialog is open at a time, also during a fan-out over
  many nodes; the store timeout bounds the whole read of one node (all its entries), and a node waiting
  behind another node's read counts that wait in its own timeout. Nodes whose credentials are held are
  never blocked. After "access denied" the node's back-off (30 s) applies, so no new dialog for that
  node within that time.
- **Access control of the store**: whatever the person allows ("Always Allow" in the Keychain
  dialog, an unlocked keyring) applies to the program that reads the entry; the documentation names
  which program that is on each system and what the permission means.
- **Two profiles derive the same entry name**: a configuration error as today for variables (both
  read the same entry; another profile in the default configuration directory, or two groups or
  nodes of the same profile); a file with the same `profile.name` stays a warning, and a node named
  `e2e` where its group allows end-to-end tests stays an error (its username entry is the group's
  `_E2E_USERNAME` entry). Two profiles may still read the same Parameter Store parameter.
- **Leftover environment variables of other profiles**: the warning of US3-2 lists only the names
  this profile derives (FR-003), so variables of other profiles are not reported. Today's warning
  "`<PREFIX>_*_PASSWORD` matches no configured group or node (typo?)" is dropped.
- **The server runs where no credential store exists** (container, CI, headless Linux without
  Secret Service): it starts; nodes with the credential store as source fail on first use with the
  error of US2-3; nodes with Parameter Store work.
- **A node overrides the profile's credential store with Parameter Store, or the reverse**: each node
  uses its own effective source; an override replaces the whole source including its settings, as
  in feature 010.
- **Unknown or misspelled `type`**: a configuration error listing the supported types
  (`credentialStore`, `awsParameterStore`); `environment` gets the removal message of US3-1 instead.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The server MUST support exactly two secret sources: the **credential store** of the
  operating system it runs on (`type: credentialStore`; macOS Keychain, Windows Credential Manager,
  Linux Secret Service) and **AWS Systems Manager Parameter Store** (`type: awsParameterStore`,
  unchanged from feature 010). The source is chosen per profile, group or node with
  `credentials.source` as in feature 010 (node → group → profile); without any `source`, a node uses
  the credential store. An override replaces the whole source including its settings.
- **FR-002**: The environment-variable source MUST be removed: the server MUST NOT read any INUBIT
  credential (username, password, trust store password, end-to-end credentials) from environment
  variables. `type: environment` on any level MUST be a configuration error that names the key path,
  the replacement and the migration guide (US3-1).
- **FR-003**: Variables in the server's environment whose names this profile derives for any of its
  nodes (every node, also Parameter Store nodes, under the prefix the node would use as a credential
  store node; every kind including `TRUSTSTORE_PASSWORD` and the end-to-end kinds) MUST produce one
  warning at startup and in `--check-config` that lists the names (never values), says that they are
  no longer read and points to the migration guide. The warning about a `<PREFIX>_*_PASSWORD`
  variable that matches no node is dropped. Variables that are not INUBIT credentials
  (`INUBIT_MCP_CONFIG`, `INUBIT_MCP_PROFILE`, the AWS SDK's variables) are unaffected.
- **FR-004**: A credential store entry MUST be identified by the service `inubit-mcp` and an account
  name derived as today's variable names: `<PREFIX>_<GROUP>_<NODE>_<KIND>`, else
  `<PREFIX>_<GROUP>_<KIND>`, with the same normalisation (upper case, every character other than
  `A–Z`/`0–9` replaced by `_`) and the same kinds. The node entry wins over the group entry, each kind
  independently, by value (a missing or empty node entry falls back to the group entry). The identity
  maps onto the stores as follows: macOS Keychain, a generic password with service `inubit-mcp` and
  account `<ACCOUNT>`; Windows Credential Manager, a generic credential with the target name
  `inubit-mcp:<ACCOUNT>` (its user-name field is ignored); Linux Secret Service, an item with the
  attributes `service` = `inubit-mcp` and `account` = `<ACCOUNT>`. A value created with the
  documented system tool MUST be read exactly as typed (Edge Cases, "Value encoding").
- **FR-005**: The prefix of the entry names MUST be configurable as today: a `prefix` on the
  credential store source of a group or node, else the profile-wide prefix, else `INUBIT_<PROFILE>`
  (normalised as today), with the pattern of today (`^[A-Z][A-Z0-9_]{0,63}$`). The profile-wide prefix
  is `credentials.prefix`; `credentials.envPrefix` and a top-level `source: { type: credentialStore,
  prefix }` stay accepted with the same meaning (`envPrefix` with a warning recommending
  `credentials.prefix`); two or three of them set to different values MUST be a configuration error
  naming them (US3-4). The checks of today on derived names (another profile in the default
  configuration directory: error; a file with the same `profile.name`: warning; two groups or nodes of
  the same profile: error; a node named `e2e` where its group allows end-to-end tests: error) MUST
  apply to the derived entry names of every node with the credential store as source.
- **FR-006**: Credentials from the credential store MUST be read on first use per node, not at
  startup, with the rules of 010 FR-006a: first use is the first call that has to authenticate to the
  node (also within a fan-out); one read covers username, password and, if present, the trust store
  password; the end-to-end credentials are read only when an end-to-end test runs on the node;
  tools that do not contact a node read nothing; the server MUST start without access to any store.
  A missing username or password entry is an error of the call that needs it (no longer a startup
  error).
- **FR-007**: Values from the credential store MUST pass the same checks as every other source
  (010 research R-17): username without `:`, control characters or a leading `-`; password and
  end-to-end password at least 4 characters; an empty entry counts as not found. A value that breaks
  a rule is a failed read ("invalid value") naming the entry.
- **FR-008**: A read from the credential store MUST be bounded by a timeout, long enough for a person
  to answer a system access dialog (default 60 s, see Assumptions), that bounds the whole read of one
  node including any wait behind another node's read; reads from the credential store are serialised
  so that at most one access dialog is open at a time. A read MUST NOT block calls on nodes whose
  credentials are already held. Concurrent calls that need the same node share one read. A fan-out
  call counts a node's read in that node's deadline as in feature 010 (010 research R-8), so a slow
  read fails that node's part, not the whole call. After
  a failed read the node's credentials are not read again for 30 s (calls fail at once with the
  recorded failure); the first call after that reads again, so a created or corrected entry needs no
  restart. `get_health` keeps its time budget as in feature 010 (a node still being read is reported
  as "credentials not yet available").
- **FR-009**: The re-read after a rejected login (010 FR-010) MUST apply to the credential store as
  well: when INUBIT rejects a node's username and password, the server re-reads the node's entries
  once; changed values allow exactly one further login without a pause; unchanged values or a failed
  re-read pause the node for 60 s. At most two failed logins per node per 60 s for every source. The
  re-read runs on the rejected call and can add up to the store timeout to it.
- **FR-010**: Every error about a credential MUST name the node, the source (and for the credential
  store which store: Keychain, Credential Manager, Secret Service), the entry or parameter names and
  the kind of failure (not found, access denied, invalid value, store not available, timeout), never a
  value. Error codes as in feature 010: not found, access denied, invalid value and store not
  available are `AUTH_FAILED`; a timeout (also an access dialog nobody answered) is `UNREACHABLE`.
  The `get_health` text for a node whose read is still running names the credential store instead of
  Parameter Store. The next step MUST refer to the source: for the credential store, create or correct the
  entry (naming service and account) — no restart and no reconnect needed. The message "restart the
  MCP client from a shell with the new values" MUST NOT appear any more. Raw messages of the store go
  to the log only, scrubbed.
- **FR-011**: `--check-config` MUST show per node the source and, for the credential store, per kind
  the entry name it found (node or group name) or both names when none was found, with the outcome
  (`ok`, `not found`, `invalid value`, `access denied`, `not checked`), never a value. Missing
  required entries, invalid values and denied access are errors (exit code 1); a store that cannot be
  asked (no store on this machine, locked without a prompt, or an access dialog left unanswered until
  the timeout) is one warning per store with `not checked` after its names (exit code 0 if nothing
  else is wrong). An access dialog that the person denies is `access denied` (error). The Parameter Store part stays as in feature 010.
  The `Credential variables:` line is replaced by the entry naming scheme.
- **FR-012**: Credential values MUST be handled as today (Constitution II): never in tool parameters,
  results, logs, error messages, audit records or files; never as a process argument (also not when
  the server uses a system tool to read the store); registered with the scrubber as soon as they are
  read; StartCLI keeps receiving the password on stdin.
- **FR-013**: Every read from the credential store MUST be logged like a Parameter Store fetch (010
  FR-013): one structured record per attempt with node, source, store, entry names, duration and
  outcome (and `changed` for a re-read), INFO on success, WARN on failure, never a value; calls
  refused within the 30 s back-off are not logged again.
- **FR-014**: The opt-in live tests MUST resolve credentials with the server's own resolution for
  every supported source (credential store and Parameter Store), including per-node source settings;
  `docs/live-tests.md` MUST describe it.
- **FR-015**: The documentation MUST be rewritten for the two sources: README (installation steps
  and security summary), `docs/setup.md` (section 4 with one general description of reading,
  checking and changing credentials that matches every source; per operating system how to create,
  change and delete entries without putting the value on a command line where the tool allows it;
  what an access permission of the store grants), `docs/tools.md`, `SECURITY.md`, and a migration
  guide from environment variables (exported in a shell profile, or exported by the macOS start
  script, which then is no longer needed) to the credential store or Parameter Store, including the
  changed end-to-end behaviour (Edge Cases). Also updated: `docs/migration-001-to-002.md` (variable
  names and `envPrefix`), the hint of the feature-001 format check ("add credentials.envPrefix:
  INUBIT"), the `--help` text of `--check-config`, and the CHANGELOG with a prominent breaking-change
  note.
- **FR-016**: Tools, their parameters and their results MUST NOT change, except texts of errors and
  next steps (FR-010); no tool takes or returns a secret or says where a node's secrets come from.
- **FR-017**: Every operating system MUST have an opt-in live check (skipped unless explicitly enabled,
  like the existing live tests) that creates or expects entries for a fictitious profile in the real
  store and verifies identity mapping, value encoding and the not-found and access-denied outcomes; it
  never contacts INUBIT. Existing tests and fixtures that supply credentials through environment maps
  move to test doubles of the stores; only the SC-002 negative test and the FR-003 warning test keep
  credential variables.

### Key Entities

- **Secret source**: where a node's credentials come from: `credentialStore` (settings: optional
  `prefix`) or `awsParameterStore` (settings: region, optional AWS profile; feature 010).
- **Credential store**: the store of the operating system the server runs on (macOS Keychain,
  Windows Credential Manager, Linux Secret Service); available or not on a given machine.
- **Credential entry**: one value in the credential store, identified by the service `inubit-mcp` and
  an account name derived from prefix, group, node and kind; node entry before group entry.
- **Secret kind**: username, password, trust store password, end-to-end username and password
  (unchanged).
- **Held credentials**: the values of one node in memory, never shown; replaced only by a re-read
  after a rejected login (FR-009).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An installation that uses the macOS start script of the current how-to works after
  re-registering the JAR without the script, with no change to its Keychain entries or its profile,
  after allowing at most one Keychain access request per entry; verified by the macOS live check
  (FR-017) before the release.
- **SC-002**: No INUBIT credential is read from an environment variable on any code path, verified by
  a test that sets every derived variable to a distinct value and asserts that none is ever used.
- **SC-003**: No credential value appears in any tool result, log line, error message, audit record,
  process argument or `--check-config` output, verified by dedicated tests for both sources.
- **SC-004**: Every missing, unreadable or invalid credential is reported with node, source, store and
  name in one message that is enough to fix it without reading logs.
- **SC-005**: A changed credential takes effect without restart or reconnect for every source (at the
  latest after one rejected login), and the documentation describes one procedure per source for
  changing a credential.
- **SC-006**: With a test double of the store that answers within 100 ms, the first call on a node
  takes at most 200 ms longer than with credentials already held; later calls show no measurable
  difference.
- **SC-007**: The same profile file works unchanged on macOS, Windows and Linux when the entries exist
  in each system's store: verified with test doubles of every store in the build, and by the live
  check of each operating system (FR-017) where it has been executed; the release notes say on which
  systems it was executed.

## Out of Scope

- Writing, creating or deleting entries or parameters from the server (a helper command for that
  could be a later feature); the documentation uses the systems' own tools.
- Other stores: AWS Secrets Manager, HashiCorp Vault, 1Password, `pass`, KeePass, or a file-based
  keyring for headless Linux.
- Keeping environment variables as a deprecated fallback, or an opt-in to re-enable them.
- A time-based refresh of credentials.
- Configuring the service name `inubit-mcp` or a specific keychain or keyring collection.

## Assumptions

- **Breaking release**: removing the environment-variable source is released as a new minor version
  before 1.0 (0.7.0), with the migration guide and a prominent CHANGELOG entry; installations that
  export variables must migrate before upgrading.
- **Store per system**: the keychain search list of the user (macOS, normally the login keychain),
  the generic credentials of the current user (Windows), all unlocked collections of the Secret
  Service of the user's session, as `secret-tool lookup` searches them (Linux); entries are those of
  the user who runs the MCP client.
- **Timeout**: 60 s by default because the first read on macOS may wait for the person to answer the
  Keychain dialog; whether it should be configurable (like `timeout` and `cliTimeout`) is decided in
  the plan.
- **Headless use** (containers, CI, servers without a desktop session) is served by Parameter Store;
  the credential store is meant for the person's own workstation.
- **The macOS Keychain dialog**: the entry's access control decides which program may read it without
  asking; with the start script that was `/usr/bin/security`. Which program the server uses to read
  the store (and so what "Always Allow" grants) is decided in the plan, together with any new
  dependency (its license goes to `THIRD-PARTY-NOTICES.md`).
- The default build uses test doubles of every store; no test of the default build contacts a real
  store, AWS or INUBIT (Constitution III). The live checks of FR-017 are opt-in.
- The feature-010 Parameter Store behaviour is unchanged except where this spec says otherwise (the
  same `AUTH_FAILED` texts, timeouts, back-off and checks).
