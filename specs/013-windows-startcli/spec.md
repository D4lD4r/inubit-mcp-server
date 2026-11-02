# Feature Specification: StartCLI-Backed Tools on Windows

**Feature Branch**: `013-windows-startcli`

**Created**: 2026-11-02

**Status**: Draft

**Input**: User description (translated from German): "StartCLI on Windows: all StartCLI-backed
tools (INUBIT 8.1 and 6.1) shall also run when the MCP server runs on Windows (so far
`CLI_UNAVAILABLE` since the MVP decision R-6). Safe invocation without command injection through
cmd.exe, an environment allowlist for Windows, ending the process tree, output with CRLF and code
page, the password still only through stdin; tests and a live check by a Windows user. Branch from
main." The user chose the full scope ("if at all, then completely"): every StartCLI-backed tool,
not only the 6.1 reads.

## Context & Findings *(verified before writing this spec)*

- **Windows is refused today.** Since the MVP decision of 2026-10-01 (feature 001, research R-6)
  every StartCLI call on Windows fails with `CLI_UNAVAILABLE` "CLI tools are not supported on
  Windows in this version", next step "Run the CLI-backed tools from macOS or Linux; the REST
  tools work on Windows". `--check-config` warns for every node with a StartCLI installation on
  Windows and skips the client check. The research gives no technical reason; it was a scope
  decision of the MVP. The configuration already resolves `bin/startcli.bat` on Windows.
- **Affected tools (INUBIT 8.1).** `restart_process` and `kill_process` (their StartCLI part), the
  module list and version histories of `list_inventory` / `get_inventory_item`, `export_artifacts`,
  `import_artifacts`, `restore_backup`, `set_active`, `tag_artifacts`, `deploy_release`, and the
  StartCLI client check (`startcli -v`) of `--check-config` and `get_health`.
- **INUBIT 6.1 (feature 012).** Feature 012 (branch `012-inubit-61-cli-reads`, not yet on `main`)
  reaches 6.1 nodes through StartCLI only, in an interactive session fed through stdin, with a
  `startcli -h` client-option probe, a TLS pre-check of its own and the trust store passed through
  a client option or the Java runtime settings. On Windows it is refused like every StartCLI call.
- **How StartCLI is started today** (macOS and Linux): an argument array, never a shell; never a
  `-p` password option, the password plus a line break is written to stdin; the child environment
  is an allowlist (`PATH`, `HOME`, `TMPDIR`, `LANG`, `LC_ALL`, `USER`, plus `JAVA_HOME` and fixed
  `JAVA_TOOL_OPTIONS`); the working directory is the client installation; on timeout the whole
  process tree is ended (forcibly after 2 s); stdout and stderr are bounded and classified by a
  tested parser.
- **The Windows client** of INUBIT ships `startcli.bat` with the same options as `startcli.sh`
  (the 6.1 documentation names both; without `-p` the client asks for the password at its prompt).
  A batch file is always run by the Windows command interpreter, which gives characters such as
  `&`, `|`, `<`, `>`, `^`, `%` and `!` in its arguments a meaning of its own. Passing an argument
  array therefore does not by itself keep the "no shell interpolation" guarantee of Constitution
  Principles I and V on Windows.
- **Unknown on Windows:** whether StartCLI reads the password from redirected stdin (on macOS and
  Linux it does), how it encodes its output (console code page), and whether its log lines and
  messages differ. None of this has been observed; there is no Windows recording.
- **Private files.** Several StartCLI-backed paths create owner-only files and directories with
  POSIX permissions: the temporary export archives (which contain secrets before they are
  replaced), import data, backups, deployment packages, the deployment lock and ledger, and the
  workspace history. Some paths have a fallback for systems without POSIX permissions (the
  workspace directory, the audit directory), others do not. The start-up sweep of export
  directories left behind by stopped servers is skipped on Windows.
- **Tests.** The CI runs on Linux only. 32 test classes use a fake `startcli.sh`; the offline suite
  has never run on Windows.
- **Related:** the Windows credential store (feature 011) reads credentials from the Credential
  Manager; its live check on Windows is still open. `tools/record-fixtures.sh` (the recording of
  CLI outputs) is a bash script. The certificate tool `inubit-cert-check` does not run on Windows
  (out of scope here).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Use the read-only StartCLI tools on Windows (Priority: P1)

An operator runs the MCP server on a Windows workstation with the INUBIT Workbench client
installed. They list the modules of the inventory owner, read a module's version history and
export a diagram group into the workspace, exactly as a colleague does on macOS.

**Why this priority**: the read-only StartCLI tools are the most used ones, carry no write risk
and prove that StartCLI can be started, fed the password and parsed on Windows. Everything else
builds on this.

**Independent Test**: on a Windows host with a non-production INUBIT 8.1 node, `--check-config`
shows the client version, `list_inventory` with modules returns the owner's modules and
`export_artifacts` writes readable files with secrets replaced.

**Acceptance Scenarios**:

1. **Given** a Windows host with a StartCLI installation configured for a node, **When** the
   operator runs `--check-config`, **Then** the node line shows `cli: available` and the client
   version (no "not supported on Windows" warning).
2. **Given** that node, **When** the assistant lists the modules of the inventory owner, **Then**
   the result equals the result on macOS for the same node and owner (names, types, versions),
   including names with non-ASCII characters.
3. **Given** that node, **When** the assistant exports a diagram group, **Then** the workspace
   receives the same files as on macOS, secrets replaced, and no temporary export data remains.
4. **Given** a node whose client installation path contains spaces (e.g. below
   `C:\Program Files\`), **When** any StartCLI-backed tool runs, **Then** it works as for a path
   without spaces.

---

### User Story 2 - StartCLI on Windows cannot be turned into a command and never sees the password as an argument (Priority: P1)

The person responsible for the server needs the same guarantees on Windows as on macOS and Linux:
no configured value, tool input or value read from INUBIT can make Windows run anything other than
StartCLI, and the password never appears on a command line, in the environment or in a file.

**Why this priority**: it is a NON-NEGOTIABLE constitution rule (Principles I, II and V). Without
it, the feature must not ship.

**Independent Test**: offline tests on Windows start the StartCLI substitute with values that
contain every character the Windows command interpreter treats specially and show that no other
program runs and the command StartCLI receives is unchanged, or that the call is refused before
any process starts; the substitute records its arguments and environment and finds no secret.

**Acceptance Scenarios**:

1. **Given** a username, URL, path or tool input containing `&`, `|`, `<`, `>`, `^`, `%`, `!`,
   `"` or a line break, **When** a StartCLI-backed tool runs on Windows, **Then** either StartCLI
   receives exactly that value and nothing else runs, or the call is refused with
   `INVALID_INPUT` (tool input) or the configuration is refused at startup (configured value),
   naming the setting, before any process starts.
2. **Given** any StartCLI call on Windows, **When** the process is inspected, **Then** its
   arguments and environment contain no password, and the password reached StartCLI only through
   its standard input.
3. **Given** a Windows client that does not read the password from standard input, **When** this
   is observed in the live check, **Then** the feature stops and is escalated to the person; the
   server never falls back to passing the password as an argument.

---

### User Story 3 - Use the write tools on Windows with the same safety (Priority: P2)

A developer on Windows imports changed workflows into the development node, sets a workflow
active, tags the tested state and deploys a release to the next stage, and an operator restarts a
failed process instance, with the same confirmation codes, backups, audit records and roll-backs
as on macOS.

**Why this priority**: the full scope was requested, but the write tools depend on Story 1 working
and add the private-file and roll-back requirements.

**Independent Test**: on a Windows host with a development node, an `import_artifacts` preview and
execution with the server-issued code succeeds, the backup is written and readable by the user
only, `restore_backup` restores it, and the audit log has one record per call.

**Acceptance Scenarios**:

1. **Given** a development node on Windows, **When** the developer imports a changed workflow,
   **Then** the preview, confirmation, backup, import, verification and roll-back behave as on
   macOS, and the backup is accessible to the user only.
2. **Given** a stage chain on Windows, **When** a release is deployed, **Then** each node is
   backed up, imported, verified and rolled back on failure as on macOS, and package-only targets
   get packages accessible to the user only.
3. **Given** write access on a node, **When** `restart_process` or `kill_process` runs on Windows,
   **Then** the two-step confirmation and the audit record are the same as on macOS.

---

### User Story 4 - Timeouts and cleanup leave nothing behind on Windows (Priority: P2)

When StartCLI hangs or the server stops, no StartCLI or Java process keeps running and no
temporary export data with secrets stays on disk.

**Why this priority**: a hanging Java process holds an INUBIT session and file locks; temporary
export data contains secrets before they are replaced.

**Independent Test**: offline tests on Windows let the StartCLI substitute hang, start a child
process of its own and keep a file open; after the timeout plus grace no process of the tree is
alive and the temporary directory is gone or reported.

**Acceptance Scenarios**:

1. **Given** a StartCLI call that exceeds its timeout on Windows, **When** the timeout elapses,
   **Then** the call fails with `TIMEOUT` and every process the call started (the command
   interpreter, Java and their children) has ended within the grace period.
2. **Given** a server killed during an export on Windows, **When** the same profile starts again,
   **Then** the export directories it left behind are removed, as on macOS.
3. **Given** a temporary file that Windows cannot delete because another program holds it open,
   **When** the cleanup runs, **Then** the server retries briefly and otherwise logs a warning
   naming the directory (never its contents) and removes it at the next start.

---

### User Story 5 - Read INUBIT 6.1 nodes from Windows (Priority: P2)

An operator on Windows reaches an INUBIT 6.1 node through the 6.1 StartCLI client with the same
behaviour as feature 012 defines on macOS and Linux.

**Why this priority**: 6.1 nodes are reached through StartCLI only, so without this Story a
Windows user cannot use them at all; it depends on feature 012 being on `main`.

**Independent Test**: once both features are on `main`, the 6.1 session tests of feature 012 run
on Windows with the Windows substitute, and the Windows user runs the 6.1 live check of feature
012 from Windows.

**Acceptance Scenarios**:

1. **Given** a node pinned to INUBIT 6.1 and a 6.1 client on Windows, **When** a 6.1 capability
   that feature 012 declares supported runs, **Then** the session, the TLS pre-check, the
   client-option probe and the refusals behave as on macOS.
2. **Given** a 6.1 client without a trust-store option and a trust-store path with spaces,
   **When** the node is used on Windows, **Then** it is refused as on macOS (feature 012).

---

### User Story 6 - The Windows support is verified and documented (Priority: P3)

The maintainer sees the offline suite pass on Windows in CI, and a Windows user has run the live
checks; the documentation tells Windows users how to set up the client and what to expect.

**Why this priority**: it turns "should work" into evidence and replaces the "not supported on
Windows" statements, but it follows the implementation.

**Independent Test**: the CI shows a green Windows job; the live-check record names the Windows
version, client version and outcome per step; README and setup guide describe the Windows setup
without the "not supported" statements.

**Acceptance Scenarios**:

1. **Given** a pull request, **When** CI runs, **Then** the offline suite also runs on Windows and
   every StartCLI-backed test runs there (none skipped for being on Windows).
2. **Given** the Windows user's approval and a non-production node, **When** they run the live
   checks, **Then** the outcomes are recorded per step (counts and outcome kinds only, no customer
   data).

### Edge Cases

- **Paths:** the client installation, its Java runtime, the workspace or the trust store below a
  path with spaces, non-ASCII characters, a UNC path (`\\server\share\…`) or longer than 260
  characters.
- **Output:** CRLF line endings; output in a console code page instead of UTF-8 (umlauts in
  diagram, module or user names); a byte-order mark; log lines of the Windows client that differ
  from the macOS client.
- **Password prompt:** the Windows client waits for the password on the console instead of
  reading the redirected standard input: the call must end with `TIMEOUT` (never hang, never fall
  back to `-p`), and the live check reports it.
- **Command interpreter:** values with `%VAR%` or `!VAR!` (variable expansion), `^` (escape),
  `"` (quoting), line breaks; a command line longer than Windows allows (refused before start with
  a clear error).
- **Process tree:** the command interpreter exits while its Java child keeps running; the Java
  child starts further processes; an antivirus scan delays the start beyond `cliTimeout`.
- **Missing parts:** `startcli.bat` missing (only `startcli.sh` present, e.g. a client copied from
  macOS), Java runtime missing, `JAVA_HOME` of the server pointing to a 32-bit or older Java.
- **File locks:** a temporary or backup file held open by an indexer or antivirus when it is to be
  deleted or replaced.
- **Mixed hosts:** the same profile used on macOS and Windows (different `cli.home` per host is a
  configuration matter; the profile directory differs, `%APPDATA%\inubit-mcp`).
- **6.1 before 012:** this feature lands on `main` before feature 012 or after it; whichever lands
  second must make the 6.1 session path pass on Windows.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: On Windows, every StartCLI-backed capability that is available on macOS and Linux
  MUST be available with the same inputs, results, error codes and next steps; Windows-specific
  differences are allowed only where this specification names them.
- **FR-002**: No configured value, tool input or value derived from INUBIT output MAY make Windows
  run any program other than the StartCLI client, or change the command StartCLI receives. A value
  that cannot be passed literally MUST be refused before any process starts: a tool input with
  `INVALID_INPUT`, a configured value with a configuration error at startup and in
  `--check-config` that names the setting (never a secret value).
- **FR-003**: The password (and every other secret) MUST reach StartCLI only through its standard
  input, never as a process argument, environment variable or file, on Windows as everywhere. If
  the live check shows that the Windows client cannot read the password from standard input, the
  feature MUST stop and be escalated to the person; a fallback to a password argument is not
  allowed.
- **FR-004**: On Windows the StartCLI environment MUST be an allowlist: only the variables Windows
  and the Java runtime need to start (the list is fixed in the plan and documented), plus
  `JAVA_HOME` and the fixed Java runtime settings as today. No credential and no other variable of
  the server's environment MUST reach StartCLI.
- **FR-005**: When a StartCLI call times out or is interrupted on Windows, every process it started
  (the command interpreter, Java and their descendants) MUST be ended, forcibly after the existing
  grace period; the call fails with `TIMEOUT` as today.
- **FR-006**: StartCLI output on Windows MUST be decoded so that line endings (CRLF or LF) and
  characters outside ASCII yield the same parsed results as the same content recorded on macOS;
  the encoding of the Windows client's output is determined from recordings, not assumed.
- **FR-007**: Every file and directory that the server creates owner-only on macOS and Linux MUST
  on Windows be accessible only to the user who runs the server (and the system principals Windows
  always grants access). If that cannot be ensured for a file that holds secrets, the operation
  MUST fail before the secret is written.
- **FR-008**: Temporary export data MUST be removed after each call on Windows, and directories
  left behind by a stopped server of the same profile MUST be removed at its next start, as on
  macOS and Linux. A file Windows cannot delete because it is open elsewhere MUST be retried
  briefly and otherwise reported in the log by directory name only.
- **FR-009**: Paths of the client installation, its Java runtime, the workspace and the trust store
  MUST work on Windows with spaces and non-ASCII characters; a path StartCLI or Windows cannot take
  (e.g. one the Java runtime settings cannot carry, feature 012) MUST be refused with a
  configuration warning or error that names the setting.
- **FR-010**: `--check-config` on Windows MUST run the StartCLI client check (`startcli.bat -v`,
  for 6.1 nodes also `-h`) like on macOS and MUST NOT print the "not supported on Windows"
  warning; `list_nodes` and the tool announcement MUST treat a Windows client installation like
  any other.
- **FR-011**: A command line that would exceed the length Windows allows MUST be refused before
  start with an error naming the tool and the reason.
- **FR-012**: For INUBIT 6.1 nodes, every rule of feature 012 (session through standard input,
  TLS pre-check, client-option probe, trust store through a client option or the Java runtime
  settings, refusals) MUST hold on Windows once feature 012 is on `main`; whichever of the two
  features lands second carries the tests that prove it.
- **FR-013**: The offline test suite MUST run on Windows in CI with a Windows StartCLI substitute;
  every StartCLI-backed test and every safety and secret test (Constitution III) MUST run there,
  none disabled for Windows without a recorded reason in the plan.
- **FR-014**: Recorded outputs of the Windows client MUST be added for the read-only cases that
  decide parsing (client version, client options, a module list with non-ASCII names, a failure
  message, and for 6.1 the session cases of feature 012), neutralized like all fixtures and checked
  by the customer-identifier test. Recording MUST be possible on Windows without a Unix shell, MUST
  use only read-only commands and MUST happen only with the person's approval on a non-production
  node.
- **FR-015**: The opt-in live tests MUST run on Windows; a Windows user runs the read-only checks
  first and the write checks only on a development node with the person's approval. The outcomes
  (Windows version, client version, step, outcome kind) MUST be recorded without customer data.
- **FR-016**: README and setup guide MUST describe the Windows client setup (installation path,
  Java runtime for StartCLI, `startcli.bat`) and MUST no longer state that StartCLI-backed tools
  are not supported on Windows; the CHANGELOG MUST name the change.
- **FR-017**: Behaviour on macOS and Linux MUST NOT change; their offline suite stays green
  unchanged except for tests that are made platform-neutral.

### Key Entities

- **StartCLI invocation (Windows)**: the program started, its arguments, working directory,
  environment allowlist and standard input content; the unit the injection and secret tests
  inspect.
- **Windows StartCLI substitute**: the offline stand-in for `startcli.bat` that records arguments,
  environment and input and replays recorded outputs, the Windows counterpart of the fake
  `startcli.sh`.
- **Private file or directory**: anything the server creates owner-only (temporary export data,
  backups, packages, deployment lock and ledger, workspace history, audit log) with its required
  access on Windows.
- **Windows recording**: a neutralized output of the Windows client for one read-only case, with
  the client version and code page it was taken with.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every StartCLI-backed tool that works on macOS works on Windows: 100 % of their
  offline tests pass on the Windows CI job, and the Windows live check passes the read-only steps
  (client check, module list, export of a test diagram group) with no failure.
- **SC-002**: For each character the Windows command interpreter treats specially, a test proves
  that it neither starts a second program nor changes the StartCLI command (0 of all cases).
- **SC-003**: In every StartCLI call of the Windows test suite and the live check, 0 secret values
  appear in process arguments, environment or files written outside the private locations.
- **SC-004**: After a timeout, 0 processes of the StartCLI call remain alive one second after the
  grace period, in 20 consecutive test runs on Windows.
- **SC-005**: Parsing a Windows recording and the macOS recording of the same content gives
  identical results for 100 % of the recorded cases, including non-ASCII names.
- **SC-006**: A Windows user sets up the server with the StartCLI client from the documentation
  alone and completes the read-only live check without asking for help.
- **SC-007**: The macOS and Linux offline suite stays green with no test removed.

## Out of Scope

- Running the server inside WSL (that is Linux and already supported).
- The certificate tool `inubit-cert-check` on Windows (it remains macOS-first; Windows users follow
  the manual steps of the setup guide).
- Installers, Windows services or a Start-menu entry for the server.
- 32-bit Windows and Windows versions without current security support.
- New tools or changed tool behaviour beyond running the existing StartCLI-backed tools on Windows.

## Assumptions

- Windows 10 or 11, or Windows Server 2019 or newer, 64-bit; the server runs on a Java 21 runtime
  for Windows.
- The INUBIT Workbench client for Windows is installed per node version (8.1.x with a Java 17
  runtime; for 6.1 the 6.1 client and its runtime) and contains `bin\startcli.bat` with the same
  options as `startcli.sh`.
- A Windows user with a non-production INUBIT 8.1 node (and, for Story 5, a 6.1 node) is available
  for recordings and live checks; their approval is given per step in the chat, as for every live
  access.
- Git for Windows is installed where the workspace tools are used.
- The credential store of Windows (feature 011) provides the credentials; its own live check is
  carried out by the same Windows user.
- Feature 012 is merged to `main` independently; the 6.1 part of this feature (Story 5, FR-012) is
  completed by whichever feature lands second.
