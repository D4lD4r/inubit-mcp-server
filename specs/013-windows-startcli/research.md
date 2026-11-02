# Research: StartCLI-Backed Tools on Windows

Decisions for feature 013. The defaults of the spec's Clarifications (2026-11-02) are binding until
the Windows live check (R-13) records other facts; each decision names what the live check must
confirm. Code references are to `main` at `18344e2`.

## R-1 Launch mechanism

- **Decision**: On Windows the server starts the client's `bin\startcli.bat` through the system's
  own command interpreter, built by the server itself:
  `<SystemRoot>\System32\cmd.exe /d /v:off /s /c "<line>"`, where `<line>` is
  `"<cli.home>\bin\startcli.bat" "<arg1>" "<arg2>" …`, every argument wrapped in double quotes by a
  new `WindowsCommandLine` builder. `ProcessLauncher.LaunchSpec` gets an optional raw command line
  for Windows, and `SystemProcessLauncher` passes it unchanged (no re-quoting by the JDK).
  - `/d` disables the `AutoRun` commands of the registry; `/v:off` disables delayed expansion
    (`!`); `/s /c "…"` makes the interpreter strip exactly the outer quotes.
  - `<SystemRoot>` is the server's `SystemRoot` variable, accepted only if it is an absolute
    drive-letter path that contains `System32\cmd.exe`; otherwise `C:\Windows` (FR-002 program
    resolution). The batch file is named by its absolute path, so neither the working directory
    nor `PATH` is consulted for it or for the interpreter.
- **Rationale**: the clarification fixes the launch through `startcli.bat` until the shipped batch
  files have been examined (FR-010). Building the line ourselves makes the quoting exact and
  testable; letting the JDK turn an argument list for a `.bat` into a command line depends on JDK
  version and `jdk.lang.Process.allowAmbiguousCommands`.
- **Alternatives considered**: `ProcessBuilder(startcli.bat, args…)` (JDK-dependent quoting, not
  provable); starting the client's Java runtime directly (avoids the interpreter, but needs the
  class path and options of each client's batch file; allowed later by FR-010 once R-13 recorded
  them).

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

- **Decision**: `CliPaths` gets a Windows rule:
  `^[A-Za-z]:/[A-Za-z0-9_.\- /]{1,255}$` (forward slashes, ASCII, total ≤ 259, no `.`/`..`
  segment). The server converts its own temporary paths with `toRealPath()` (expands 8.3 short
  names) and `/` separators before the check. `ConfigValidator` runs the `java.io.tmpdir` check on
  Windows with this rule and the existing `-Djava.io.tmpdir` remedy; UNC paths fail the rule.
- **Rationale**: clarification default `/`; Java and the INUBIT client accept forward slashes in
  Windows paths.
- **To confirm in R-13**: that StartCLI takes `C:/…` in a quoted `--execCommand` value (and, for
  information, how it treats `\`).

## R-4 Command-line length

- **Decision**: `WindowsCommandLine` refuses a line longer than 8 191 − 2 048 = 6 143 characters
  before start: `INVALID_INPUT` when tool input (diagram groups, paths) makes it long,
  `CLI_UNAVAILABLE` naming the setting when the configured parts alone exceed it (also a
  `--check-config` warning). The 2 048-character margin covers what `startcli.bat` adds (class
  path and options).
- **To confirm in R-13**: the length the batch files add; the margin is lowered or raised then.

## R-5 Child environment on Windows

- **Decision**: `CliRunner` builds the Windows allowlist: `SystemRoot`, `windir`, `SystemDrive`,
  `ComSpec` (set to the absolute interpreter of R-1), `PATH`, `PATHEXT`, `TEMP`, `TMP`,
  `USERPROFILE`, `USERNAME`, `USERDOMAIN`, `APPDATA`, `LOCALAPPDATA`, `ProgramData`, `OS`,
  `PROCESSOR_ARCHITECTURE`, `NUMBER_OF_PROCESSORS` (copied if set), `JAVA_HOME` (as today) and
  `JAVA_TOOL_OPTIONS` = today's value plus the Windows-only
  `-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8` (R-6).
  Windows variable names are matched case-insensitively.
- **Rationale**: the minimum a batch file and a Java runtime need to start and to find the user's
  home; no credential variable exists any more since feature 011, and the allowlist keeps it so.

## R-6 Output and standard-input encoding

- **Decision**: The Windows-only runtime settings of R-5 make the client's `System.out`, `System.err`
  and its default charset UTF-8, regardless of the console code page. The server decodes
  StartCLI output strictly as UTF-8 and, only if that fails, in the host's native code page
  (`native.encoding`, the ANSI code page) — for lines the batch file itself prints. Line endings
  are normalised (CRLF → LF) before classification and parsing. Standard input: UTF-8 and `\n` as
  today (clarification default); with `file.encoding=UTF-8` the client's default reader decodes
  UTF-8 as well.
- **Tests (SC-005)**: recorded outputs transcoded into code pages 850, 1252 and 65001 (decoder
  configured with the matching native code page) parse to the same results as the macOS
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
  If the job cannot be set up (e.g. an outer job forbids nesting), StartCLI-backed capabilities
  report `CLI_UNAVAILABLE` "processes of StartCLI could not be bound to the server" and
  `--check-config` warns (FR-005 is a MUST).
- **Rationale**: Windows has no process groups or `PR_SET_PDEATHSIG`; a job object is the only
  mechanism that survives a forced termination. JNA is already a dependency (feature 011) and
  allowed in `adapter/os`; `adapter/cli` gets a port `ProcessContainment` implemented there.
- **Alternatives considered**: a watchdog process (another process to manage), shutdown hooks only
  (do not run on forced termination; rejected by the clarification).

## R-8 Private files on Windows

- **Decision**: A new `infra.PrivateFiles` replaces the scattered POSIX calls: on POSIX it applies
  today's permissions (unchanged, FR-017); on Windows it creates directories and files with an
  `acl:acl` attribute at creation time — two entries, the owner (the server's account) and
  `SYSTEM`, full control, with `FILE_INHERIT` and `DIRECTORY_INHERIT` on directories — so the DACL
  is set atomically and inherited by everything StartCLI and git create below. Users: temporary
  export and import directories (`CliExportRunner`, `CliImportRunner`, `CliResources`), backups
  (`BackupStore`), packages (`PackageWriter`), deployment lock and ledger, the git hooks directory
  (`GitCli`), the workspace directory when the server creates it (`WorkspaceDirectory`) and the
  audit directory and log (`Launcher`, `AuditLog`). If the attribute cannot be applied, creation
  fails and the caller reports its existing error (FR-007).
- **Existing objects**: `PrivateFiles.check` reads the ACL of the default audit directory, the
  audit log and the deployment ledger and lock; any entry for another principal gives a warning
  at startup and in `--check-config` with the `icacls` command that restricts it. Nothing is
  changed; an existing workspace is not checked (clarification r4).
- **To confirm on the Windows CI runner**: that the read-back DACL holds exactly the two entries
  and no inherited ones (a test reads it), and that a file the StartCLI substitute writes into the
  temporary export directory inherits them.

## R-9 Credentials on Windows

- **Decision**: Before a StartCLI call on Windows, `CliRunner` checks the username with the
  characters of R-2 and the password for non-ASCII characters (clarification default, FR-003).
  A failure is `AUTH_FAILED` naming the entry and the cause (never the value), raised before the
  `CredentialGuard` login step, so it never counts as a failed login. `EntryCheck`
  (`--check-config`) reports such a username as `invalid value (Windows: …)` on nodes with a
  StartCLI installation; REST-only nodes are unaffected. The non-ASCII password refusal is a
  constant that a later change lifts once R-13 records the evidence.

## R-10 Cleanup and file locks

- **Decision**: `CliResources.sweepStale` runs on Windows too (owner check with
  `Files.getOwner`, process check with `ProcessHandle`, no links followed; the reason it was
  skipped was the POSIX-only owner lookup). Deletion retries up to 5 times within 2 s on
  `AccessDeniedException`/`FileSystemException` (a file held open), then logs the directory name.
- **Password-prompt timeout (FR-005)**: `CliOutputClassifier` recognises the client's password
  prompt as the last output line; a `TIMEOUT` then says "StartCLI was still waiting for the
  password" with the next step pointing to the live check, not to `cliTimeout`.

## R-11 Configuration checks and messages

- **Decision**: `ConfigValidator.checkCli` loses the Windows early return; it checks
  `bin\startcli.bat`, `cli.javaHome`, the characters of R-2, a UNC `cli.home`, the line length of
  R-4, the job of R-7 and runs the client probe (`-v`, for 6.1 nodes `-h` once feature 012 is on
  `main`). Messages and next steps that name `bin/startcli.sh`, permissions or POSIX paths get
  Windows variants (FR-001). The constant `WINDOWS_UNSUPPORTED` and its next step are removed.

## R-12 Tests and CI

- **Decision**:
  - Unit tests of the new pure parts (`WindowsCommandLine`, `WindowsArguments`, the path rule,
    the decoder, the allowlist) run on every platform.
  - Platform tests run on Windows only (`@EnabledOnOs(WINDOWS)`) and are matched by POSIX tests on
    macOS/Linux: a substitute `startcli.bat` that calls a small Java main (`ArgvRecorder`, test
    classes) which writes its `argv`, environment, stdin bytes and command line (via
    `ProcessHandle.info()`) to a file; the injection matrix of SC-002; the tree test of SC-004
    (substitute starts a child that sleeps); ACL read-back (R-8); the job test (a child survives the
    server's forced termination on the macOS path only, on Windows it must not).
  - Tests that write a fake `startcli.sh` and run it through `SystemProcessLauncher`
    get a platform helper that writes `startcli.bat` on Windows; the 15 classes on
    `ScriptedProcessLauncher` need no change.
  - CI: a `windows-latest` job runs `mvn -B verify` (FR-013); POSIX-permission tests that cannot
    run there are replaced by ACL equivalents, listed in the plan.

## R-13 Recordings and live check on Windows

- **Decision**: `tools/record-fixtures.ps1` (PowerShell 5.1+, no Unix shell) records the read-only
  cases of FR-014 with the same allowlist and stdin rules as the server: `-v`, `-h`, a module list,
  a failure (wrong URL), the argument list for the test values (through a recording wrapper that
  replaces the client's Java main for one run), and for 6.1 the cases of feature 012 once it is on
  `main`. Neutralised with `tools/neutralize.py` (Python on Windows) and checked by
  `NoCustomerIdentifiersTest`. The live check (`docs/live-tests.md`, new Windows section) runs the
  read-only steps first and records the unknowns of the spec; write steps only on a development
  node with the person's approval.
- **Outcome handling**: if the client does not read the password from redirected stdin, work stops
  and the person decides (FR-003); other differences (separator, encoding, margin) change the
  defaults of R-3, R-4 and R-6 with a spec and plan update.

## R-14 Feature 012 (INUBIT 6.1)

- **Decision**: the 6.1 session path uses the same `CliRunner` launch, so R-1 to R-9 cover it.
  Whichever feature lands second adds the Windows variants of the 012 session tests (FR-012); if
  013 lands first, a task is added to 012's `tasks.md`.
