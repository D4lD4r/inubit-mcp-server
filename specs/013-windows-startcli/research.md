# Research: StartCLI-Backed Tools on Windows

Decisions for feature 013. The defaults of the spec's Clarifications (2026-11-02) are binding until
the Windows live check (R-13) records other facts; each decision names what the live check must
confirm. Code references are to `main` at `18344e2`.

## R-1 Launch mechanism

- **Decision**: On Windows the server starts the client's `bin\startcli.bat` through the system's
  own command interpreter: `ProcessBuilder` with the argument list
  `[<system dir>\cmd.exe, /d, /v:off, /s, /c, <inner>]`, where `<inner>` is one argument that
  starts and ends with `"`: `""<cli.home>\bin\startcli.bat" "<arg1>" … "<argN>""`, every StartCLI
  argument wrapped in double quotes by a new `WindowsCommandLine` builder.
  - **JDK precondition**: `java.lang.ProcessImpl` on Windows leaves an argument that already starts
    and ends with `"` unquoted; with the default `jdk.lang.Process.allowAmbiguousCommands` (not
    set, no security manager) it does not reject the embedded quotes of `<inner>`. The server
    never sets that property. A Windows test (T008) pins this on the CI JDK: it starts the
    substitute through this exact argument list and compares the argv the client's Java main
    receives and the line `cmd.exe` received (`%CMDCMDLINE%`, written by the substitute batch
    file) with the expected values. If a JDK changes this behaviour, the test fails and the
    fallback below is taken.
  - **Fallback (only if the precondition breaks)**: `CreateProcessW` through JNA in `adapter/os`
    with the exact line and inherited pipe handles, behind the existing `ProcessLauncher` port.
  - `/d` disables the `AutoRun` commands of the registry; `/v:off` disables delayed expansion
    (`!`); `/s /c "…"` makes the interpreter strip exactly the outer quotes.
  - `<system dir>` is the system directory reported by `GetSystemDirectoryW` (JNA, `adapter/os`);
    if it cannot be read, StartCLI-backed capabilities report `CLI_UNAVAILABLE` (no guessed
    `C:\Windows`). The batch file is named by its absolute path, so neither the working directory
    nor `PATH` is consulted for it or for the interpreter; for the `java` the batch file starts,
    see R-5.
- **Rationale**: the clarification fixes the launch through `startcli.bat` until the shipped batch
  files have been examined (FR-010). Building the inner line ourselves makes the quoting exact; the
  one JDK behaviour we rely on is pinned by a test on the platform that matters.
- **Alternatives considered**: `ProcessBuilder(startcli.bat, args…)` (the JDK's batch-file
  quoting mode depends on JDK version and properties); starting the client's Java runtime directly
  (avoids the interpreter but needs each client version's class path and options; allowed later by
  FR-010 once R-13 recorded them); `CreateProcessW` from the start (more code; kept as fallback).

## R-2 Characters on Windows

- **Decision**: With every argument in double quotes, the interpreter still interprets `"`, `%`
  (variables, also inside quotes), `^` (outside quotes, and inside after an odd number of quotes)
  and, with delayed expansion, `!`; `&`, `|`, `<`, `>` become operators if a quote is unbalanced.
  These, control characters and non-ASCII characters are refused (FR-002) by a `WindowsArguments`
  check in `config`: in `cli.home`, `cli.javaHome`, `tls.trustStore` and the StartCLI URL at
  validation time (node-level `CLI_UNAVAILABLE` + warning), and in the username when it is read for
  a StartCLI call (R-9). The remaining characters stay literal inside double quotes: space, `'`,
  `(`, `)`, `;`, `,`, `=` (no argument splitting inside quotes), `*`, `?` (the Java launcher expands
  wildcards only in unquoted arguments). A trailing backslash would escape the closing quote under
  the C runtime rules the Java launcher uses, so the builder doubles trailing backslashes (and any
  backslashes before an embedded quote, which cannot occur after the refusals). A UNC `cli.home`
  is refused (FR-002), because the interpreter does not accept a UNC working directory.
- **Rationale**: refusing the few dangerous characters instead of escaping them keeps the proof
  small (SC-002) and matches the spec.
- **To confirm in R-13**: the argument list the client receives for the test values of
  `contracts/windows-invocation.md` (forwarding through `%*` or `%1`…`%9`).

## R-3 Paths inside the StartCLI command

- **Decision**: `CliPaths` becomes platform-aware: on Windows the rule is
  `^[A-Za-z]:/[A-Za-z0-9_.\- /]{1,256}$` (forward slashes, ASCII, at most 259 characters, no
  `.`/`..` segment). `CliCommand.Builder.path` renders a `Path` with `/` separators on Windows and
  checks it with the platform rule; `CliPaths.passable` and `exportRootUsable` take the platform
  flag, and the messages of `CliExportRunner` and `CliImportRunner` that print the rule show the
  platform's rule. The server's temporary directories go through `toRealPath()` (expands 8.3 short
  names); for a file StartCLI has not written yet, the parent directory is resolved and the file
  name appended. `ConfigValidator` runs the `java.io.tmpdir` check on Windows with this rule and
  the existing `-Djava.io.tmpdir` remedy; UNC paths fail the rule.
- **Rationale**: clarification default `/`; Java and the INUBIT client accept forward slashes in
  Windows paths.
- **To confirm in R-13**: that StartCLI takes `C:/…` in a quoted `--execCommand` value
  (`win_modules`), and how it treats `\` there (`win_backslash`).

## R-4 Command-line length

- **Decision**: `WindowsCommandLine` refuses an inner line longer than 8 191 − 2 048 = 6 143
  characters before start. `CliRunner` maps the typed failure: `INVALID_INPUT` when tool input
  (diagram groups, paths) makes it long, `CLI_UNAVAILABLE` naming the setting when the configured
  parts alone exceed it (also a `--check-config` warning). The 2 048-character margin for what
  `startcli.bat` adds (class path and options) is a clarification default, revisited in T031 when
  the batch files of the supported clients have been examined.

## R-5 Child environment on Windows

- **Decision**: `StartCliEnvironment` builds the Windows allowlist: `SystemRoot`, `windir`,
  `SystemDrive`, `ComSpec` (set to the absolute interpreter of R-1), `PATHEXT`, `TEMP`, `TMP`,
  `USERPROFILE`, `USERNAME`, `USERDOMAIN`, `APPDATA`, `LOCALAPPDATA`, `ProgramData`, `OS`,
  `PROCESSOR_ARCHITECTURE`, `NUMBER_OF_PROCESSORS` (copied if set), `JAVA_HOME` (as today),
  `JAVA_TOOL_OPTIONS` = today's value plus the Windows-only
  `-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8` (R-6), and two
  values the server sets instead of copying:
  - `PATH` = `<JAVA_HOME>\bin;<system dir>` (not the server's `PATH`), so an unqualified `java` in
    `startcli.bat` finds only the configured runtime; the effective Java home (`cli.javaHome`, else
    the server's `JAVA_HOME`) is checked with the characters of R-2 plus `;` (the `PATH`
    separator) — at validation (warning naming `cli.javaHome` or `JAVA_HOME`) and per call
    (`CLI_UNAVAILABLE`);
  - `NoDefaultCurrentDirectoryInExePath=1`, so the interpreter does not search the working
    directory (`cli.home`) for programs.
  Windows variable names are matched case-insensitively. A Windows test places decoy `java.exe`
  files in `cli.home` and in a directory of the server's `PATH`; the substitute calls `java`
  unqualified and must reach the configured runtime (T009).
- **Rationale**: the minimum a batch file and a Java runtime need to start and to find the user's
  home; program resolution is closed for the interpreter (R-1) and for `java` (here).

## R-6 Output and standard-input encoding

- **Decision**: The Windows-only runtime settings of R-5 make the client's `System.out`,
  `System.err` and its default charset UTF-8, regardless of the console code page. On Windows the
  server decodes StartCLI output **per line**: strictly as UTF-8, and only a line that is not valid
  UTF-8 (a line the batch file itself prints) in the console OEM code page (`GetOEMCP`, e.g. 850, which `cmd.exe` uses for its own output);
  CRLF is normalised to LF before classification and parsing. On macOS and Linux decoding stays
  exactly as today (`toString(UTF_8)`, no normalisation; FR-017). Standard input: UTF-8 and `\n`
  as today (clarification default); with `file.encoding=UTF-8` the client's default reader decodes
  UTF-8 as well.
- **Tests (SC-005)**: recorded outputs transcoded into code pages 850, 1252 and 65001 (decoder
  configured with the matching OEM code page) parse to the same results as the macOS
  recordings.
- **To confirm in R-13**: that the client honours the settings (output of a module list with
  umlauts), and that it reads the password from redirected stdin.

## R-7 Process tree, timeouts and forced termination

- **Decision**: Timeouts and orderly stops keep today's mechanism (`destroy` of the snapshotted
  tree, `destroyForcibly` after 2 s); `ProcessHandle.descendants()` works on Windows. For forced
  termination of the server, `adapter/os` gets a `WindowsJob` (JNA, `kernel32`): at startup on
  Windows the server creates a job object with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` and assigns
  itself to it; every child started afterwards (interpreter, Java, their descendants) inherits the
  job, and Windows ends them when the server's last handle closes, also on a forced termination.
  The job handle is created non-inheritable (else a child holding it would keep the job open). If
  the job cannot be set up (e.g. an outer job forbids nesting), StartCLI-backed capabilities
  report `CLI_UNAVAILABLE` "processes of StartCLI could not be bound to the server" and
  `--check-config` warns (FR-005 is a MUST).
- **Rationale**: Windows has no process groups or `PR_SET_PDEATHSIG`; a job object is the only
  mechanism that survives a forced termination. JNA is already a dependency (feature 011) and
  allowed in `adapter/os`; `adapter/cli` gets a port `ProcessContainment` implemented there.
- **Alternatives considered**: a watchdog process (another process to manage), shutdown hooks only
  (do not run on forced termination; rejected by the clarification).

## R-8 Private files on Windows

- **Decision**: A port `domain.port.PrivateFileSystem` (JDK types only) replaces the scattered
  POSIX calls; its implementation `adapter.os.OsPrivateFileSystem` is injected by `Wiring` and
  `Launcher` into `application` (`BackupStore`, `PackageWriter`, `DeployLock`,
  `DeploymentLedger`), `adapter.cli`, `adapter.git` (`GitCli`), `config` (`WorkspaceDirectory`) and
  `infra` (`AuditLog`), so `PackageBoundaryTest` stays green (the application and the workspace
  adapters see only the domain port; JNA stays in `adapter.os`).
  - **POSIX**: today's permissions exactly, including the existing fallbacks (FR-017).
  - **Windows**: the object is created empty, then a **protected** DACL is applied before anything
    is written into it: `SetNamedSecurityInfoW` with
    `DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION` (JNA, `advapi32`), two
    entries (and the owner set to the same SID, `OWNER_SECURITY_INFORMATION`) — the user SID of the process token (`GetTokenInformation(TokenUser)`, not
    `Files.getOwner`, which can be `BUILTIN\Administrators` on an elevated account) and `SYSTEM` —
    full control, with `OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE` on directories. Everything
    StartCLI and git create below inherits these two entries. Between creation and protection the
    object is empty, so no secret is exposed. If the DACL cannot be applied, the object is deleted
    and the caller reports its existing error (FR-007).
  - The JDK's `acl:acl` creation attribute is not used: the JDK builds the descriptor without
    `SE_DACL_PROTECTED`, so the parent's inheritable entries would be merged.
- **Existing objects**: `PrivateFileSystem.check` reads the DACL of the default audit directory,
  the audit log and the deployment ledger and lock; any entry for another principal gives a warning
  at startup and in `--check-config` with the `icacls` command that restricts it. Nothing is
  changed; an existing workspace is not checked (clarification r4).
- **Tests**: on the Windows CI job a test reads the DACL back (exactly the two entries, protected,
  none inherited) and checks the inherited entries of a file the StartCLI substitute writes into the
  temporary export directory.

## R-9 Credentials on Windows

- **Decision**: Before a StartCLI call on Windows, `CliRunner` checks the username with the
  characters of R-2 and the password for non-ASCII characters (clarification default, FR-003).
  A failure is `AUTH_FAILED` naming the entry and the cause (never the value), raised before the
  `CredentialGuard` login step, so it never counts as a failed login. On macOS and Linux nothing
  changes. `EntryCheck`
  (`--check-config`) reports such a username as `invalid value (Windows: …)` on nodes with a
  StartCLI installation; REST-only nodes are unaffected. The non-ASCII password refusal is a
  constant that a later change lifts once R-13 records the evidence.

## R-10 Cleanup and file locks

- **Decision**: `CliResources.sweepStale` runs on Windows too: the owner check uses
  `PrivateFileSystem.ownedByCurrentUser` (the server sets the owner of its private objects to the
  token user, R-8, so this also holds on an elevated account), the process check
  uses `ProcessHandle`, no links are followed (the reason it was skipped was the POSIX-only owner
  lookup). Deletion retries up to 5 times within 2 s on `AccessDeniedException` /
  `FileSystemException` (a file held open), then logs the directory name.
- **Timeout after the password prompt (FR-005 as amended, Windows only)**: the client always prints
  `Password: ` first, so a prompt as the last output cannot tell a client waiting at its console
  from a slow server. On Windows such a `TIMEOUT` names both possibilities and keeps the
  "raise `cliTimeout`" next step, adding the live check; macOS and Linux keep today's message.

## R-11 Configuration checks and messages

- **Decision**: `ConfigValidator.checkCli` loses the Windows early return; it checks
  `bin\startcli.bat`, `cli.javaHome`, the characters of R-2, a UNC `cli.home`, the line length of
  R-4, the job of R-7 and runs the client probe (`-v`, for 6.1 nodes `-h` once feature 012 is on
  `main`). Messages and next steps that name `bin/startcli.sh`, permissions or POSIX paths get
  Windows variants (FR-001). The constant `WINDOWS_UNSUPPORTED` and its next step are removed.

## R-12 Tests and CI

- **Decision**:
  - Pure units (`WindowsCommandLine`, `WindowsArguments`, path rule, decoder, allowlist) run on
    every platform.
  - Windows-only tests (`@EnabledOnOs(WINDOWS)`) use a substitute `startcli.bat` that runs a
    test-classpath main `ArgvRecorder` with `%*` and first writes `%CMDCMDLINE%` (the line the
    interpreter received) to a file; `ArgvRecorder` writes its `args`, environment and stdin bytes.
    `ProcessHandle.Info` is not used (not populated on Windows).
  - **Porting the existing suite** (tasks T003–T005): a helper `FakeStartCli.install(cliHome)`
    writes `bin/startcli.sh` or `bin\startcli.bat` for the platform; every test that writes its own
    `startcli.sh` uses it. POSIX-permission assertions are kept for POSIX and get a Windows
    equivalent when the class moves to `PrivateFileSystem`:

    | Test class (POSIX assertion) | Windows equivalent |
    |---|---|
    | `BackupStoreTest`, `PackageWriterTest`, `DeployLockTest`, `DeploymentLedgerTest` (owner-only files and directories) | DACL read-back (T025) |
    | `AuditLogTest` (`assumeTrue(posix)`), `MainTest` audit directory (`assumeTrue(posix)`) | DACL read-back (T024) |
    | `GitCliTest` hooks directory, `ConfigValidatorTest` workspace directory | DACL read-back (T026) |
    | `CliExportRunnerTest`, `CliImportRunnerTest`, `ExportShutdownProcessTest` (temporary directories) | DACL read-back and inheritance (T019) |
    | `SystemProcessLauncherTest` (class-level `@DisabledOnOs(WINDOWS)`; signals and process tree) | class-level guard lifted; POSIX signal cases guarded per method; Windows tree cases (T005, T022) |
    | `MainTest` cases that make `startcli.sh` executable | `FakeStartCli` (T004) |

  - CI: a `windows` job runs `mvn -B verify` with `continue-on-error: true` until the porting and
    the Windows launch are done (T021 makes it required); a task with Windows tests is done when
    its own test classes pass in that job's report.

## R-13 Recordings and live check on Windows

- **Decision**: `tools/record-fixtures.ps1` (PowerShell 5.1+, no Unix shell) records the read-only
  cases of FR-014 with the same allowlist and stdin rules as the server: `win_version`,
  `win_help`, `win_modules` (non-ASCII names, a `C:/` path in the command), `win_failure` (wrong
  URL), `win_argv` (argument list the client's Java main receives for the test values of the
  invocation contract, through a recording wrapper for one run) and `win_backslash` (a read-only
  command with a `C:\…` path inside a quoted value), and for 6.1 the cases of feature 012 once it
  is on `main`. Neutralised with `tools/neutralize.py` and checked by `NoCustomerIdentifiersTest`;
  `.gitattributes` gets `src/test/resources/fixtures/windows/** -text` so CRLF survives.
- **Live check**: the existing opt-in JUnit live tests (`ArtifactExportLiveTest`,
  `InventoryLiveTest`, `CredentialStoreLiveTest`, then `DevelopmentLiveTest` and
  `DeploymentLiveTest`) run on Windows with their opt-in variables — read-only ones first, write
  ones only on a development node or an approved target after the person's approval — plus the
  manual steps of quickstart.md; outcomes are recorded there.
- **Outcome handling**: if the client does not read the password from redirected stdin, work stops
  and the person decides (FR-003); other differences (separator, encoding, margin) change the
  defaults of R-3, R-4 and R-6 with a spec and plan update.

## R-14 Feature 012 (INUBIT 6.1)

- **Decision**: the 6.1 session path uses the same `CliRunner` launch, so R-1 to R-9 cover it.
  Whichever feature lands second adds the Windows variants of the 012 session tests (FR-012); if
  013 lands first, a task is added to 012's `tasks.md`.
