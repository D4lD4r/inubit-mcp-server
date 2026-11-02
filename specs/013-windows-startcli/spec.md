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
  Windows and skips the client check and the check of `java.io.tmpdir`. The research gives no
  technical reason; it was a scope decision of the MVP. The configuration already resolves
  `bin/startcli.bat` on Windows.
- **Affected tools (INUBIT 8.1).** `restart_process` and `kill_process` (their StartCLI part), the
  module list and version histories of `list_inventory` / `get_inventory_item`, `export_artifacts`,
  `import_artifacts`, `restore_backup`, `set_active`, `tag_artifacts`, `deploy_release`, and the
  StartCLI client check (`-v`) of `--check-config` and `get_health`.
- **INUBIT 6.1 (feature 012).** Feature 012 (branch `012-inubit-61-cli-reads`, not yet on `main`)
  reaches 6.1 nodes through StartCLI only, in an interactive session fed through stdin, with a
  `-h` client-option probe, a TLS pre-check of its own and the trust store passed through a client
  option or the Java runtime settings. On Windows it is refused like every StartCLI call.
- **How StartCLI is started today** (macOS and Linux): an argument array, never a shell; never a
  `-p` password option, the password plus `\n` is written to stdin in UTF-8; the child environment
  is an allowlist (`PATH`, `HOME`, `TMPDIR`, `LANG`, `LC_ALL`, `USER`, plus `JAVA_HOME` and fixed
  `JAVA_TOOL_OPTIONS`); the working directory is the client installation; on timeout the whole
  process tree is ended (forcibly after 2 s); stdout and stderr are bounded and classified by a
  tested parser.
- **What goes into a StartCLI command today.** Values inside `--execCommand` are restricted to
  letters, digits, `_`, `.`, `-` and spaces (research R-11; repository paths additionally `/` and
  the `/Root` prefix) and wrapped in single quotes. File paths inside the command (the export,
  import and history files below `java.io.tmpdir`) must match `^/[A-Za-z0-9_.\-/ ]{1,1023}$`; no
  Windows path can match it. Values outside the command — the username, the StartCLI URL, the
  trust-store path, the client installation and its Java runtime — are passed as separate
  arguments or as the program and working directory, and are not restricted to these characters.
- **The Windows client** of INUBIT ships `startcli.bat` with the same options as `startcli.sh`
  (the 6.1 documentation names both; without `-p` the client asks for the password at its prompt).
  A batch file is always run by the Windows command interpreter, which gives characters such as
  `&`, `|`, `<`, `>`, `^`, `%`, `!` and `"` in its arguments a meaning of its own. Passing an
  argument array therefore does not by itself keep the "no shell interpolation" guarantee of
  Constitution Principles I and V on Windows.
- **Unknown on Windows** (none of this has been observed; there is no Windows recording):
  - whether the Windows client reads the password from redirected standard input (on macOS and
    Linux it does), in which encoding, and which line ending it expects;
  - how `startcli.bat` forwards its arguments to Java (`%*`, `%1`…`%9`, variables with delayed
    expansion), and whether single-quoted `--execCommand` values (with spaces, and `''`) reach
    StartCLI unchanged;
  - whether StartCLI's command parser takes `\` inside a quoted value literally or as an escape
    (Windows file paths inside the command; on macOS only `/` occurs);
  - in which encoding the client writes its output (console code page or other) and whether its
    log lines and messages differ from the macOS client.
- **Private files.** Several paths create owner-only files and directories with POSIX permissions:
  the temporary export archives (which contain secrets before they are replaced), import data,
  backups, deployment packages, the deployment lock and ledger, the workspace history and the
  audit log. Some paths have a fallback for systems without POSIX permissions (the workspace
  directory, the audit directory: they are created without restricted access), others do not.
  The start-up sweep of export directories left behind by stopped servers is skipped on Windows.
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
   version, and no "not supported on Windows" warning appears.
2. **Given** that node, **When** `list_nodes` runs and the server announces its tools, **Then** the
   node shows `cli: true` and the StartCLI-backed tools are announced as on macOS for the same
   profile.
3. **Given** that node, **When** the assistant lists the modules of the inventory owner, **Then**
   the result equals the result on macOS for the same node and owner (names, types, versions),
   including names with non-ASCII characters.
4. **Given** that node, **When** the assistant exports a diagram group, **Then** the workspace
   receives the same files as on macOS, secrets replaced, and no temporary export data remains.
5. **Given** a client installation below `C:\Program Files\` (a path with spaces the user cannot
   write to), **When** any StartCLI-backed tool runs, **Then** it works as for a path without
   spaces, and the server writes nothing into the installation.
6. **Given** a `java.io.tmpdir` that breaks the Windows path rule (FR-009), **When**
   `--check-config` runs, **Then** it warns, naming `java.io.tmpdir` and the remedy (start the
   server with `-Djava.io.tmpdir=<a path that matches the rule>`), as it does on macOS.

---

### User Story 2 - StartCLI on Windows cannot be turned into a command and never sees the password as an argument (Priority: P1)

The person responsible for the server needs the same guarantees on Windows as on macOS and Linux:
no configured value, tool input or value read from INUBIT can make Windows run anything other than
StartCLI, and the password never appears on a command line, in the environment or in a file.

**Why this priority**: it is a NON-NEGOTIABLE constitution rule (Principles I, II and V). Without
it, the feature must not ship.

**Independent Test**: offline tests on Windows start the StartCLI substitute (which forwards its
arguments like the shipped `startcli.bat`) with values that contain every character the Windows
command interpreter treats specially and show that no other program runs and the command
StartCLI receives is unchanged, or that the configuration is refused at startup; the substitute
records its arguments and environment and finds no credential.

**Acceptance Scenarios**:

1. **Given** a StartCLI URL, trust-store path, client installation or Java runtime path that
   contains a character FR-002 refuses, **When** the server starts on Windows or `--check-config`
   runs, **Then** a warning names the setting and the character class, the node's StartCLI-backed
   capabilities report `CLI_UNAVAILABLE` naming the setting, no StartCLI process is ever started
   for that node, and the REST tools keep working.
2. **Given** a username in the credential store or Parameter Store that contains a character
   FR-002 refuses, **When** a StartCLI-backed tool needs it on Windows, **Then** the call fails
   with `AUTH_FAILED` naming the entry (never the value) before any process starts, without
   counting as a failed login, and `--check-config` reports the entry as invalid on Windows.
3. **Given** configured values and tool inputs that pass the rules of FR-002, including values
   with spaces, `''` and the longest allowed length, **When** a StartCLI-backed tool runs on
   Windows, **Then** StartCLI receives exactly these values (as the substitute records), and no
   other program runs.
4. **Given** any StartCLI call on Windows, **When** the process is inspected, **Then** its
   arguments and environment contain no credential, and the password reached StartCLI only through
   its standard input.
5. **Given** a tool input that makes the StartCLI command longer than the limit of FR-011,
   **When** the tool runs on Windows, **Then** it fails with `INVALID_INPUT` and no process
   starts.
6. **Given** a Windows client that does not read the password from standard input, **When** this
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
execution with the server-issued code succeeds, the backup is written with access for the user
only (FR-007), `restore_backup` restores it, and the audit log has one record per call.

**Acceptance Scenarios**:

1. **Given** a development node on Windows, **When** the developer imports a changed workflow,
   **Then** the preview, confirmation, backup, import, verification and roll-back behave as on
   macOS, and the backup has the access of FR-007.
2. **Given** a stage chain on Windows, **When** a release is deployed, **Then** each node is
   backed up, imported, verified and rolled back on failure as on macOS, and package-only targets
   get packages with the access of FR-007.
3. **Given** write access on a node, **When** `restart_process` or `kill_process` runs on Windows,
   **Then** the two-step confirmation and the audit record are the same as on macOS.
4. **Given** an audit or workspace directory that an earlier version created on Windows without
   restricted access, **When** the server starts or `--check-config` runs, **Then** a warning
   names the directory and how to restrict it; the server does not change it, and every file it
   creates there from now on has the access of FR-007.

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
2. **Given** a running StartCLI call, **When** the MCP client closes the connection, **Then** every
   process of the call has ended within the grace period and its export directory is removed.
3. **Given** a server killed during an export on Windows, **When** the same profile starts again,
   **Then** the export directories it left behind are removed, as on macOS.
4. **Given** a temporary file that Windows cannot delete because another program holds it open,
   **When** the cleanup runs, **Then** the server retries (FR-008) and otherwise logs a warning
   naming the directory (never its contents) and removes it at the next start.
5. **Given** a Windows client that waits at its password prompt, **When** the call times out,
   **Then** the `TIMEOUT` error says that StartCLI was still waiting for the password and names the
   live check (FR-015), not "raise `cliTimeout`".

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

1. **Given** a pull request, **When** CI runs, **Then** the offline suite also runs on Windows under
   the rule of FR-013.
2. **Given** the person's approval and a non-production node, **When** the Windows user records the
   read-only cases with the Windows recording procedure, **Then** the recordings are made without a
   Unix shell, neutralized, and pass the customer-identifier test (FR-014).
3. **Given** the person's approval and a non-production node, **When** the Windows user runs the
   live checks, **Then** the outcomes are recorded per step (counts and outcome kinds only, no
   customer data).

### Edge Cases

- **Paths:** a client installation, Java runtime or trust store below a path with spaces (works)
  or with characters outside ASCII (refused for StartCLI, FR-002); a workspace path with spaces or
  non-ASCII characters (works as on macOS; it is never passed to StartCLI); a UNC path
  (`\\server\share\…`: refused for paths inside the StartCLI command, FR-009; allowed for the
  separately passed paths if they otherwise pass FR-002); longer than 259 characters (refused
  inside the StartCLI command, FR-009); an 8.3 short name in `java.io.tmpdir`
  (`C:\Users\ABCDEF~1\…`: expanded to the long name first, FR-009).
- **Output:** CRLF line endings; output in code page 850, 1252 or 65001 (umlauts in diagram, module
  or user names); a byte-order mark; log lines of the Windows client that differ from the macOS
  client (FR-006).
- **Password prompt:** the Windows client waits for the password on the console instead of reading
  the redirected standard input: the call ends with `TIMEOUT` and the cause of US4 AS5; never a
  hang, never a fallback to `-p` (FR-003).
- **Password characters:** a password with characters outside ASCII (FR-003).
- **Command length:** a command longer than Windows allows (FR-011).
- **Process tree:** the command interpreter exits while its Java child keeps running; the Java
  child starts further processes; an antivirus scan delays the start beyond `cliTimeout`
  (`TIMEOUT` as today).
- **Missing or wrong parts:** only `startcli.sh` present (e.g. a client copied from macOS):
  `CLI_UNAVAILABLE` naming `bin\startcli.bat`, and a `--check-config` warning as today for a
  missing script; Java runtime missing: the existing `cli.javaHome` warning; the runtime is older
  or 32-bit and the client fails: the client's own error, classified as today.
- **Program resolution:** a `cmd.exe` or `java.exe` placed in the client installation, in the
  working directory or earlier on `PATH` is never run instead of the intended program (FR-002).
- **Concurrent calls:** two StartCLI calls on the same node at the same time behave as on macOS
  (the existing per-node limits apply); a call that fails because the client cannot write its own
  files in a non-writable installation reports the client's error, classified as today; the server
  itself never writes into the installation.
- **File locks:** a temporary or backup file held open by an indexer or antivirus when it is to be
  deleted or replaced (FR-008).
- **Mixed hosts:** the same profile used on macOS and Windows: a `cli.home` that does not exist on
  the Windows host gives the existing "not found" warning and `CLI_UNAVAILABLE`; the profile
  directory differs (`%APPDATA%\inubit-mcp`).
- **6.1 before 012:** this feature lands on `main` before feature 012 or after it; whichever lands
  second must make the 6.1 session path pass on Windows (FR-012).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: On Windows, every StartCLI-backed capability that is available on macOS and Linux
  MUST be available with the same inputs, results, error codes and next steps. Allowed
  differences, and only these: the refusals of FR-002, FR-003, FR-009 and FR-011; next steps and
  messages that name the client script, file access or path syntax are adapted to Windows
  (`bin\startcli.bat`, access lists, drive-letter paths); fixed Windows-only Java runtime settings
  (FR-004).
- **FR-002**: No configured value, tool input or value derived from INUBIT output MAY make Windows
  run any program other than the StartCLI client, or change the command StartCLI receives:
  - Values inside the StartCLI command keep today's rules (letters, digits, `_`, `.`, `-`, space;
    repository paths also `/`), and file paths inside it follow FR-009; they MUST reach StartCLI
    unchanged.
  - On Windows, the characters `"`, `%`, `!`, `^`, `&`, `|`, `<`, `>`, control characters (tab and
    line breaks included) and characters outside ASCII are refused in the values passed to
    StartCLI outside its command:
    - a StartCLI URL, trust-store path, client installation or Java runtime path containing one
      makes the node's StartCLI-backed capabilities report `CLI_UNAVAILABLE` naming the setting and
      the character class, with a warning at startup and in `--check-config` (like a missing
      `cli.home`); the server and its REST tools still start;
    - a username (a credential since feature 011, read only on use) containing one is refused
      when it is read for a StartCLI call, before any process starts, with `AUTH_FAILED` naming
      the entry (never the value) and without counting as a failed login; `--check-config` reports
      the entry as invalid on Windows.
  - Every other character, in particular space, `'`, `(`, `)`, `;`, `,` and `=`, MUST reach
    StartCLI unchanged.
  - The command interpreter (if one is used) and the Java runtime MUST be started from absolute
    paths (the system's own interpreter, the configured or the server's Java runtime), never
    resolved through the working directory or `PATH`.
  - The plan's Constitution Check MUST explain how these rules satisfy Principle V on Windows, or
    record a deviation in its Complexity Tracking.
- **FR-003**: Every credential (password, token) MUST reach StartCLI only through its standard
  input, never as a process argument, environment variable or file, on Windows as everywhere. On
  Windows the server writes UTF-8 and `\n` to standard input, as on macOS, until the live check
  (FR-015) shows that the Windows client reads another encoding or line ending; the plan then
  adopts the observed one. Until that check has shown that a password with
  characters outside ASCII reaches the client unchanged, such a password MUST be refused on
  Windows before any process starts, with `AUTH_FAILED` naming the cause and the entry (never the
  value) and without counting as a failed login. If the live check shows that the Windows client
  cannot read the password from standard input at all, the feature MUST stop and be escalated to
  the person; a fallback to a password argument is not allowed.
- **FR-004**: On Windows the StartCLI environment MUST be an allowlist: only the variables Windows
  and the Java runtime need to start (the list is fixed in the plan and documented), plus
  `JAVA_HOME` and fixed Java runtime settings — today's, and fixed Windows-only ones (e.g. for the
  output encoding) that the plan names. No credential and no other variable of the server's
  environment MUST reach StartCLI.
- **FR-005**: When a StartCLI call times out, is interrupted or the server stops on Windows, every
  process it started (the command interpreter, Java and their descendants) MUST be ended, forcibly
  after the existing grace period; a timed-out call fails with `TIMEOUT` as today, and one that was
  still waiting at the password prompt says so in its cause and next step.
- **FR-006**: Parsed results of StartCLI output on Windows MUST be identical to those of the same
  content on macOS, for line endings CRLF and LF and characters outside ASCII, regardless of the
  Windows display language and console code page of the host (at least code pages 850, 1252 and
  65001).
- **FR-007**: Every file and directory that the server creates owner-only on macOS and Linux —
  whichever tool creates it, StartCLI-backed or not (e.g. temporary export data, import data,
  backups, packages, deployment lock and ledger, workspace directory and history, the empty git
  hooks directory, audit directory and log) — MUST on Windows grant access only to the account that
  runs the server and to `SYSTEM`, with no entries inherited from above it. The access of such a
  directory MUST be inherited by everything created below it, including files written by StartCLI
  or git (the temporary export archive is written by StartCLI); a test checks the access of a file
  the StartCLI substitute writes into the temporary export directory. If that access cannot be
  set, the operation MUST fail before anything is written (unlike the fallback that creates the
  workspace and audit directory without restricted access on POSIX systems without permission
  support, which stays unchanged). An existing private directory or file (audit log, history,
  ledger) with broader access is not changed: the server warns at startup and in `--check-config`,
  naming it and how to restrict it, and gives everything it creates there the access above.
- **FR-008**: Temporary export data MUST be removed after each call on Windows, and directories
  left behind by a stopped server of the same profile MUST be removed at its next start, as on
  macOS and Linux. A file Windows cannot delete because it is open elsewhere MUST be retried up to
  5 times within at most 2 s and otherwise reported in the log by directory name only.
- **FR-009**: A file path inside the StartCLI command MUST on Windows be an absolute drive-letter
  path of ASCII letters `A`–`Z`, `a`–`z`, digits, `_`, `.`, `-` and spaces, whose separators are
  `\` or `/` (whichever the live check shows StartCLI's command parser takes literally; the plan
  fixes one), without `.` or `..` segments and at most 259 characters; an 8.3 short name is expanded to the long name before the check; a UNC
  path is refused. The `java.io.tmpdir` check of `--check-config` MUST run on Windows with this
  rule and name the remedy, as on macOS. The separately passed paths (client installation, Java
  runtime, trust store) MUST work with spaces and follow FR-002 otherwise.
- **FR-010**: `--check-config` on Windows MUST run the StartCLI client check (`-v`, for 6.1 nodes
  also `-h`) like on macOS and MUST NOT print the "not supported on Windows" warning; `list_nodes`
  and the tool announcement MUST treat a Windows client installation like any other. How the
  client is launched (through `startcli.bat` or by starting its Java runtime directly) is decided
  in the plan; a launch that does not go through `startcli.bat` MUST use the same Java runtime,
  class path and runtime options as the `startcli.bat` of the supported client versions, verified
  against those files.
- **FR-011**: A StartCLI command line that would exceed the limit of the chosen launch mechanism
  (8 191 characters through the command interpreter, 32 767 otherwise) MUST be refused before any
  process starts: with `INVALID_INPUT` when a tool input makes it too long, with `CLI_UNAVAILABLE`
  naming the setting when the configuration does (reported per call, and as a warning in
  `--check-config` when the configured values alone exceed the limit).
- **FR-012**: For INUBIT 6.1 nodes, every rule of feature 012 (session through standard input,
  TLS pre-check, client-option probe, trust store through a client option or the Java runtime
  settings, refusals) MUST hold on Windows once feature 012 is on `main`; whichever of the two
  features lands second carries the tests that prove it. If this feature lands first, Story 5 is
  not part of its acceptance; a task for the Windows 6.1 path is then added to feature 012's
  tasks, and the Windows CI job of FR-013 enforces it.
- **FR-013**: The offline test suite MUST run on Windows in CI with a Windows StartCLI substitute
  that forwards its arguments the same way as the `startcli.bat` of the supported client versions.
  Every StartCLI-backed, safety and secret test (Constitution III) MUST run on Windows; a test of
  POSIX-only behaviour (e.g. permission bits) MAY be replaced by a Windows equivalent, listed in the
  plan. No test is skipped on Windows otherwise.
- **FR-014**: Recorded outputs of the Windows client MUST be added for the read-only cases that
  decide parsing and invocation (client version, client options, a module list with non-ASCII
  names, a failure message, the argument list the client receives from the shipped `startcli.bat`
  for a set of test values (the recorded artefact is that list, never the vendor's batch file),
  `\` inside a quoted command value, and for 6.1 the session cases of feature 012), neutralized
  like all fixtures and checked by the customer-identifier test. One real recording in the
  tester's code page is required; the tests MAY derive the other code pages of SC-005 from it by
  transcoding. Recording MUST be possible on Windows without a Unix shell, MUST use
  only read-only commands and MUST happen only with the person's approval on a non-production node.
- **FR-015**: The opt-in live tests MUST run on Windows. A Windows user runs the read-only checks
  first — including the unknowns of the Context (password from standard input and its encoding,
  argument forwarding, output encoding) — and the write checks only on a development node with the
  person's approval. The outcomes (Windows version, client version, code page, step, outcome kind)
  MUST be recorded without customer data.
- **FR-016**: README and setup guide MUST describe the Windows client setup (installation path,
  Java runtime for StartCLI, the Windows rules of FR-002, FR-003 and FR-009) and MUST no longer
  state that StartCLI-backed tools are not supported on Windows; the CHANGELOG MUST name the
  change.
- **FR-017**: Behaviour on macOS and Linux MUST NOT change; their offline suite stays green
  unchanged except for tests that are made platform-neutral.

### Key Entities

- **StartCLI invocation (Windows)**: the program started (by absolute path), its arguments, working
  directory, environment allowlist and standard input content; the unit the injection and secret
  tests inspect.
- **Windows StartCLI substitute**: the offline stand-in for `startcli.bat` that forwards its
  arguments like the shipped batch files, records arguments, environment and input, and replays
  recorded outputs; the Windows counterpart of the fake `startcli.sh`.
- **Private file or directory**: anything the server creates owner-only (e.g. temporary export
  data, backups, packages, deployment lock and ledger, workspace directory and history, the empty
  git hooks directory, audit directory and log) and everything created below a private directory,
  with the Windows access of FR-007.
- **Windows recording**: a neutralized output of the Windows client for one read-only case, with
  the client version and code page it was taken with.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every StartCLI-backed tool that works on macOS works on Windows: 100 % of their
  offline tests pass on the Windows CI job, and the Windows live check passes the read-only steps
  (client check, module list, export of a test diagram group) with no failure.
- **SC-002**: For each of `"`, `%`, `!`, `^`, `&`, `|`, `<`, `>`, tab, line break and a non-ASCII
  character (refused) and space, `'`, `(`, `)`, `;`, `,`, `=` (reach StartCLI unchanged), in every
  value FR-002 names, a test shows the outcome FR-002 requires; in 0 cases is a second program
  started or the StartCLI command changed.
- **SC-003**: In every StartCLI call of the Windows test suite and the live check, 0 credential
  values appear in process arguments, environment or files written outside the private locations.
- **SC-004**: After a timeout, 0 processes of the StartCLI call remain alive one second after the
  grace period, in 20 consecutive test runs on Windows.
- **SC-005**: Parsing a Windows recording and the macOS recording of the same content gives
  identical results for 100 % of the recorded cases, including non-ASCII names, for code pages
  850, 1252 and 65001.
- **SC-006**: A Windows user sets up the server with the StartCLI client from the documentation
  alone and completes the read-only live check without asking for help.
- **SC-007**: The macOS and Linux offline suite stays green with no test removed.

## Out of Scope

- Running the server inside WSL (that is Linux and already supported).
- The certificate tool `inubit-cert-check` on Windows (it remains macOS-first; Windows users follow
  the manual steps of the setup guide).
- Installers, Windows services or a Start-menu entry for the server.
- 32-bit Windows and Windows versions without current security support.
- New tools, or changed tool behaviour beyond (a) running the existing StartCLI-backed tools on
  Windows and (b) the Windows access of private files (FR-007), which also applies to tools that
  do not use StartCLI.

## Assumptions

- Windows 10 or 11, or Windows Server 2019 or newer, 64-bit; the server runs on a Java 21 runtime
  for Windows.
- The INUBIT Workbench client for Windows is installed per node version (8.1.x with a Java 17
  runtime; for 6.1 the 6.1 client and its runtime) and contains `bin\startcli.bat` with the same
  options as `startcli.sh`.
- INUBIT usernames, StartCLI URLs and client installation paths in practice use ASCII only, so the
  Windows refusals of FR-002 affect no existing setup; a `java.io.tmpdir` below a user profile with non-ASCII characters is
  handled with the documented `-Djava.io.tmpdir` remedy.
- A Windows user with a non-production INUBIT 8.1 node (and, for Story 5, a 6.1 node) is available
  for recordings and live checks; the person's approval is given per step in the chat, as for every
  live access.
- Git for Windows is installed where the workspace tools are used.
- The credential store of Windows (feature 011) provides the credentials; its own live check is
  carried out by the same Windows user.
- Feature 012 is merged to `main` independently; the 6.1 part of this feature (Story 5, FR-012) is
  completed by whichever feature lands second.
