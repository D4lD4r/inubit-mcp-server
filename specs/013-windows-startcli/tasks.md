---

description: "Task list for StartCLI-backed tools on Windows (feature 013)"
---

# Tasks: StartCLI-Backed Tools on Windows

**Input**: Design documents from `/specs/013-windows-startcli/`

**Prerequisites**:
- [plan.md](plan.md) and [spec.md](spec.md) (clarifications 2026-11-02, spec reviews r1–r4)
- [research.md](research.md) (R-1…R-14) and [data-model.md](data-model.md)
- [contracts/windows-invocation.md](contracts/windows-invocation.md) and
  [contracts/configuration-delta.md](contracts/configuration-delta.md)
- [quickstart.md](quickstart.md)
- constitution v1.0.0 (Complexity Tracking of plan.md records the Principle V deviation and the JNA
  calls)

**Tests**: REQUIRED (Constitution III, TDD). Every task starts with the test(s) it names, observed
failing on an assertion before the production code exists, and ends with `mvn -q -o verify` green
on macOS. Tests marked **(Windows)** are `@EnabledOnOs(OS.WINDOWS)`: skipped locally, run in the
`windows` CI job of the draft pull request #53. For a Windows test, Red is observed by pushing the
failing test first and seeing it fail in that job, then pushing the implementation. A task with
Windows tests is done when its own test classes pass in that job's report (`continue-on-error` is
removed from the job in T021; making the check required in branch protection is the person's
setting). Pushing the branch needs the person's OK in the chat (given for the draft PR on
2026-11-02; ask again if in doubt). Tests take the platform from `test/testing/Platform` (the host
OS), never a hard-wired `false`.
"(new)" marks a test class that does not exist yet.

**Organization**: setup, then porting the existing suite to Windows, then the Windows building
blocks, then US2 (the safety property) before US1 (read-only tools), then US4 (timeouts), US3
(write tools and private files), US5 (6.1, conditional on feature 012), docs, and finally the
recordings and the live check (manual, approved, by a Windows user).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on unfinished tasks)
- **[Story]**: US1 read-only tools, US2 injection and credentials, US3 write tools, US4 timeouts
  and cleanup, US5 INUBIT 6.1, US6 verification and docs

## Path Conventions

| Short form | Expands to |
|---|---|
| `main/…` | `src/main/java/de/dadecker/inubit/mcp/…` |
| `test/…` | `src/test/java/de/dadecker/inubit/mcp/…` |
| `fixtures/…` | `src/test/resources/fixtures/…` |

## Safety rules

- **While developing**: no INUBIT command and no network call against any INUBIT server; all tests
  offline (`ScriptedProcessLauncher`, the substitute `startcli.bat`/`startcli.sh` with
  `ArgvRecorder`). The only live steps are T031, run by a Windows user after the person's approval
  in the chat.
- **Fixtures**: Windows recordings are neutralised with `tools/neutralize.py`; never the vendor's
  batch file, only what the client receives. The identifier hooks must pass; never `--no-verify`.
- **Configuration**: never change `~/.config/inubit-mcp/*` or the MCP registration.
- **Public repository**: no customer names in commit messages, PR descriptions or files.
- **macOS and Linux**: no behaviour change (FR-017); every POSIX path stays as it is.

---

## Phase 1: Setup

- [X] T001 [P] Windows CI job (FR-013, research R-12): add a `windows` job to
  `.github/workflows/ci.yml` (`runs-on: windows-latest`, Temurin 21, `mvn -B -ntp clean verify`,
  `continue-on-error: true` until T021; the Python tool tests stay on Linux) and a
  `workflow_dispatch` trigger. Test first: `test/release/CiWorkflowTest` (new) reads the workflow
  and asserts both jobs run `verify` on Java 21 and the Windows job uses `windows-latest`.
- [X] T002 [P] Test helpers (R-12): `test/testing/ArgvRecorder` (new; a main that writes its
  `args`, `System.getenv()` and the bytes read from stdin as JSON to the file named by a system
  property, optionally sleeps, starts a child or writes a file into a given directory) and
  `test/testing/FakeStartCli` (new; `install(cliHome)` writes `bin/startcli.sh` on POSIX or
  `bin\startcli.bat` on Windows; the Windows script first writes `%CMDCMDLINE%` to a file, then runs
  `ArgvRecorder` with the test classpath and `%*`; a `replaying` variant prints recorded outputs),
  and `test/testing/Platform` (new; `windows()` from the host OS, a dead-PID helper that starts and
  waits for a short `java -version` process instead of `/bin/sh -c "exit 0"`).
  Test first: `test/testing/FakeStartCliTest` (new) runs the substitute through
  `SystemProcessLauncher` with plain arguments and reads the record (POSIX now; Windows in CI).
  Also `test/testing/TestCliRunners` (new; builds a `CliRunner` for the host: the existing
  constructor until T013, which switches it to the new one with the host's `CliSystem`) and
  `Platform.commandPath(Path)` (renders a path as `CliCommand` does on the host).

## Phase 2: Port the existing suite (FR-013)

- [X] T003 [P] Port the adapter/cli tests: every class in `test/adapter/cli/` and
  `test/adapter/cli/v81/` that writes its own `startcli.sh` (`CliDeployExportsTest`,
  `CliExportRunnerTest`, `CliImportRunnerTest`, `CliRepositoryImportTest`, `CliResourcesTest`,
  `CliRunnerTest`, `CliTagRunnerTest`, `CliVersionProbeTest`, `ScriptedProcessLauncherTest`,
  `V81ArtifactAdapterTest`, `V81ImportAdapterTest`, `V81ProcessControlAdapterTest`,
  `V81TagAdapterTest`) uses `FakeStartCli.install`, and every `new CliRunner(…, false, …)` there
  passes `Platform.windows()` and a platform `PATH`; `CliResourcesTest` uses the `Platform`
  dead-PID helper; POSIX-permission assertions are guarded for POSIX (their Windows equivalents
  follow in T019). Verification task: the suite stays green on macOS with unchanged assertions.
  Minimal production change in this task: `ProcessLauncher.LaunchSpec` gains
  `startCliArguments` (the logical StartCLI arguments; on POSIX the command without the
  executable; hidden in `toString`), which `SystemProcessLauncher` ignores. The test doubles
  `ScriptedProcessLauncher` and `FakeProcessLauncher` and the helpers in `CliExportRunnerTest`,
  `V81ProcessControlAdapterTest` and `V81InventoryAdapterTest` read `--execCommand` from it;
  the `new CliRunner(` sites use `TestCliRunners`; expected command strings with paths use
  `Platform.commandPath`; `CliRunnerTest`'s exact-list assertions check `startCliArguments`.
- [X] T004 [P] Port the remaining tests that write a `startcli.sh`: `MainTest`,
  `MessageTerminologyTest`, `ExportShutdownProcessTest`, `adapter/AdapterGatewayFactoryTest`,
  `adapter/V81InventoryAdapterTest`, `application/DeployHarness`, `application/ImportHarness`,
  `config/ConfigSummaryTest`, `config/ConfigValidatorTest`, `isolation/TwoProfilesTest`,
  `live/LiveTargetTest`, `mcp/ProcessControlToolsTest`, `mcp/tools/ExportArtifactsToolTest`,
  `mcp/tools/InventoryToolFixture`, `mcp/tools/ListNodesToolTest`,
  `security/NoEnvironmentCredentialsTest`, `security/NoSecretLeakTest`, `security/SecretLeakTest`,
  and the other `new CliRunner(` sites (`application/CapabilityExtensionTest`,
  `mcp/tools/DiagnosisToolFixture`, `mcp/tools/GetHealthToolTest`) with `Platform.windows()`;
  `ExportShutdownProcessTest` uses the dead-PID helper, and its SIGTERM cases
  (`sigtermStopsTheExportAndDeletesItsDirectory`, `eofAndSigterm…`) are guarded for POSIX (Windows
  equivalent: T012 job kill and the sweep at the next start, T019);
  POSIX-permission assertions in `MainTest`, `BackupStoreTest`, `PackageWriterTest`,
  `DeployLockTest`, `DeploymentLedgerTest`, `GitCliTest`, `ConfigValidatorTest` and `AuditLogTest`
  are guarded for POSIX (Windows equivalents in T024–T026). Verification task.
- [X] T005 `SystemProcessLauncherTest`: lift the class-level `@DisabledOnOs(WINDOWS)`; guard every
  method that uses POSIX binaries (`/usr/bin/env`, `/bin/pwd`, `/bin/echo`, `/bin/cat`, `/bin/sh`)
  or signals for POSIX; their Windows equivalents with `ArgvRecorder` (environment, working
  directory, stdin) follow in T008, the tree cases in T022. Verification task.
- [X] T005a Port the remaining POSIX path assumptions of the suite that no later task covers (found
  by the Windows CI run on cfb5b5b; inserted after T005 so that the IDs named in code and docs stay
  valid): (1) fake POSIX home — `config/ConfigValidatorTest`, `config/ConfigSummaryTest`,
  `config/ProfileSectionTest` and `config/MigrationGuideExampleTest` use `Path.of("/home/test")`,
  which is not absolute on Windows (`workspace \home\test\.inubit-mcp\acme\workspace must be
  absolute after expansion of ~`); a `Platform` helper gives a host-absolute fake home
  (`/home/test` on POSIX, a drive path on Windows) and expected texts render paths for the host;
  `MigrationGuideExampleTest`'s verbatim comparison with the guide normalises the rendered home or
  is guarded for POSIX with a reason; (2) Windows paths inside double-quoted YAML —
  `isolation/TwoProfilesTest`, `mcp/tools/ExportArtifactsToolTest` and
  `performance/HealthOverviewPerformanceTest` write a temporary path into a `"…"` scalar, where `\U`/`\a`… are
  escapes (`Invalid YAML … at line 14, column 17`); use single quotes or forward slashes;
  (3) separator assumptions in assertions — `architecture/TemplateGrammarTest` (scan keys
  `de\dadecker\…`) and `adapter/xslt/XsltRunnerTest` (`.tests\dev\…` against a `/` predicate)
  compare host-independent forms; (4) `adapter/os/SecretServiceStoreTest
  .fallsBackToTheFirstExecutableOnPath` (`no process was launched`): port it to the host's
  `File.pathSeparator` and path form, or guard it for Linux if `SecretServiceStore` is Linux-only
  by design. Out of scope (the causes stay until their tasks): `CLI_UNAVAILABLE: StartCLI cannot be
  used` (T013), `--exportFile`/`--importFile` path refused (T018), the `java.io.tmpdir` warning
  (T015), the sweep in `CliResourcesTest` (T019), interrupt timeouts (T012, T022), node line and
  client probe (T020), `No private empty directory for git hooks`, deploy lock, ledger and
  `posix:permissions` (T024–T026). Verification task: the suite stays green on macOS with
  unchanged assertions (`NoCustomerIdentifiersTest` 20 tests, 0 skipped); no production change.

## Phase 3: Foundational (blocks every user story)

- [X] T006 `WindowsArguments` (FR-002, R-2): `main/config/WindowsArguments` with
  `refusal(String value)` → `Optional<String>` character class (`"` `%` `!` `^` `&` `|` `<` `>`,
  control characters incl. tab and line breaks, non-ASCII) and `isUnc(Path)`. Pure, every platform.
  Test first: `test/config/WindowsArgumentsTest` (new) with every character of SC-002, both
  outcomes.
- [X] T007 `WindowsCommandLine` (R-1, R-2, R-4, contracts/windows-invocation.md):
  `main/adapter/cli/WindowsCommandLine` builds the JDK argument list
  `[<system dir>\cmd.exe, /d, /v:off, /s, /c, ""<script>" "<arg>" …"]` (every StartCLI argument
  quoted, trailing backslashes doubled, `WindowsArguments` re-checked as a guard) from a given
  system directory, and enforces the 6 143-character limit with a typed failure telling tool input
  from configuration. Pure. Test first: `test/adapter/cli/WindowsCommandLineTest` (new): quoting,
  trailing `\`, `''`, the longest allowed line, one character over the limit, the guard.
  The guard raises `IllegalStateException` (a value that should have been refused earlier by
  T013/T015); it is never the user-facing refusal.
- [X] T008 Windows launch in `SystemProcessLauncher` and system queries (R-1, R-8):
  `main/adapter/os/WindowsSystem` (JNA: `GetSystemDirectoryW`, `GetOEMCP`, the token user SID) and the Windows
  path of `SystemProcessLauncher`, which starts the argument list of T007 unchanged (no `.bat`
  passed to the JDK). Test first: **(Windows)** `SystemProcessLauncherTest` cases: for the plain
  values and the contract's test values, `%CMDCMDLINE%` equals the expected interpreter line and
  `ArgvRecorder` receives the expected `args` (this pins the JDK behaviour of R-1; if it fails,
  stop and add a separate task for the `CreateProcessW` fallback of R-1, with its
  `PackageBoundaryTest` handling recorded in the plan's Complexity Tracking); `WindowsSystemTest` (new,
  Windows) reads a system directory that contains `cmd.exe` and a user SID.
  The system queries reach `adapter/cli` through a new port `main/adapter/cli/CliSystem`
  (`systemDirectory()`, `oemCharset()`), implemented by `WindowsSystem` and by a POSIX variant
  (no system directory needed); `adapter/cli` never references `adapter/os`. Also the Windows
  equivalents of the guarded `SystemProcessLauncherTest` cases (environment, working directory,
  stdin via `ArgvRecorder`).
- [X] T009 `StartCliEnvironment` (FR-002, FR-004, R-5), after T008: extract today's POSIX allowlist from
  `CliRunner` into `main/adapter/cli/StartCliEnvironment` (unchanged) and add the Windows allowlist
  (case-insensitive names, `ComSpec`, `PATH` = `<JAVA_HOME>\bin;<system dir>`,
  `NoDefaultCurrentDirectoryInExePath=1`, the Windows-only `JAVA_TOOL_OPTIONS`). Test first:
  `test/adapter/cli/StartCliEnvironmentTest` (new) for both (POSIX output identical to today's,
  no other variable copied) and **(Windows)** a decoy test: `java.exe` decoys in `cli.home` and in
  a directory of the server's `PATH`; the substitute calls `java` unqualified and reaches the
  configured runtime.
- [X] T010 `OutputDecoder` (FR-006, R-6), after T009: `main/adapter/cli/OutputDecoder` — on Windows
  per line strict UTF-8, else the console OEM code page (`GetOEMCP`, T008), CRLF → LF; on POSIX exactly today's
  `toString(UTF_8)`. `CliRunner` decodes through it. Test first:
  `test/adapter/cli/OutputDecoderTest` (new): UTF-8, 850, 1252 as OEM code page, a mixed buffer
  (one OEM line among UTF-8 lines), CRLF, BOM; the POSIX path byte-for-byte as today.
- [X] T011 `PrivateFileSystem` (FR-007, R-8): port `main/domain/port/PrivateFileSystem`
  (`createDirectory`, `createDirectories`, `createTempDirectory`, `createFile`, `check`) and
  `main/adapter/os/OsPrivateFileSystem`: POSIX today's permissions exactly (incl. the existing
  fallbacks); Windows creates the object empty, sets the owner to the token user and applies a
  protected DACL (token user + `SYSTEM`, inheritable on directories) with `SetNamedSecurityInfoW`,
  deletes it and fails if that fails; `check` reports other principals; `ownedByCurrentUser(Path)`
  (used by the sweep). No caller changes yet. Test first:
  `test/adapter/os/OsPrivateFileSystemTest` (new; POSIX permissions) and **(Windows)**
  `OsPrivateFileSystemAclTest` (new): read-back DACL is protected with exactly two entries; a file a
  child process writes into the directory inherits them. `PackageBoundaryTest` stays green.
- [X] T012 `ProcessContainment` (FR-005, R-7): port `main/adapter/cli/ProcessContainment`
  (`state()`: `BOUND` | `UNAVAILABLE(reason)`), `main/adapter/os/NoJob` (POSIX) and
  `main/adapter/os/WindowsJob` (JNA `kernel32`: `CreateJobObjectW`, `SetInformationJobObject` with
  `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`, `AssignProcessToJobObject(GetCurrentProcess())`), set up
  once per process in the production `Launcher` (server start, and `--check-config` before the
  client probe — changed in T013; never in the test JVM) and passed as a state to `Wiring` (tests build
  `Wiring` with a given state, so the test JVM is never bound); the job handle is created
  non-inheritable. Test first:
  **(Windows)** `WindowsJobTest` (new): a helper JVM binds itself, starts the substitute that starts
  a sleeping child, is killed with `destroyForcibly`; the child is gone within 2 s; the handle's
  `HANDLE_FLAG_INHERIT` is not set.

**Checkpoint**: the Windows building blocks exist and are unused; macOS behaviour unchanged.

---

## Phase 4: User Story 2 - No injection, password only on stdin (Priority: P1)

**Goal**: StartCLI on Windows receives exactly the intended values or nothing starts.
**Independent test**: the injection matrix (SC-002) and the credential inspection (SC-003) on the
Windows CI job.

- [X] T013 [US2] Windows launch in `CliRunner` (FR-001, FR-002, FR-004, FR-011): remove the
  `WINDOWS_UNSUPPORTED` refusal; on Windows build the call with `WindowsCommandLine`,
  `StartCliEnvironment` and `bin\startcli.bat`; check the effective Java home (`cli.javaHome`, else
  the server's `JAVA_HOME`) with `WindowsArguments` plus `;` (it goes into `PATH`); map the length
  failure to `INVALID_INPUT` (tool input) or `CLI_UNAVAILABLE` naming the setting (configuration)
  before any process starts; refuse `ProcessContainment` `UNAVAILABLE` and an unreadable system
  directory with `CLI_UNAVAILABLE`; Windows variants of next steps that name `bin/startcli.sh` or
  permissions (e.g. in `launchAndWait`). Test first: `CliRunnerTest` cases with a fake
  launcher and the Windows flag (expected argument list and environment; both length outcomes with
  no launch; the longest allowed command runs; a refused `JAVA_HOME`; Windows next-step texts).
  Per call on Windows, `CliRunner.launchTarget` also refuses a refused character in `cli.home`,
  `tls.trustStore` or the StartCLI URL, and a UNC `cli.home`, with `CLI_UNAVAILABLE` naming the
  setting and the character class (never the value); `CliRunnerTest` has one case per setting
  (no launch). The new constructor takes `CliSystem` and `ProcessContainment`; `Launcher.probeClients`
  and `Wiring` use it; the old constructor is removed and `TestCliRunners` switches to the new one.
- [ ] T014 [US2] Injection matrix (SC-002, US1 AS5): **(Windows)** `WindowsCommandLineInjectionTest`
  (new): every character of SC-002 in the username, trust-store path, StartCLI URL, `cli.home`,
  `cli.javaHome` and the server's `JAVA_HOME` (refused or unchanged as the contract says;
  `ArgvRecorder` sees no second program and the exact `args`), and US1 AS5: an installation path
  with spaces, `(` and `)`, made read-only, works and nothing is written into it. Verification
  task.
- [X] T015 [US2] Configured values on Windows (FR-002, FR-011, contracts/configuration-delta.md):
  `ConfigValidator.checkCli` loses its Windows early return; on Windows it checks
  `bin\startcli.bat`, `WindowsArguments` for `cli.home`, the effective Java home (`cli.javaHome`,
  else `JAVA_HOME`, also `;`), `tls.trustStore` and the StartCLI URL, a UNC `cli.home`, the
  configured line length and the containment state, each as a warning with the node's
  StartCLI-backed capabilities reporting `CLI_UNAVAILABLE` naming the setting. The line length is
  computed by `config/WindowsArguments.lineLength(…)` (the same arithmetic `WindowsCommandLine`
  uses), and the containment state is passed in by `Launcher`, so `config` does not depend on
  `adapter`. Test first: `ConfigValidatorTest` Windows cases (each warning text of the contract;
  the node's REST tools stay available; the old Windows warning is gone).
  The configured-length warning assumes a 64-character username (the username is a credential,
  read on use); the per-call check uses the real one.
  Open from T013 (review r1/r2): the contract's system-directory warning names a `(<reason>)`,
  but `CliSystem.systemDirectory()` returns an `Optional`; either the port carries the reason
  or the contract row drops it. `Launcher.probeClients` sets up the containment only when a
  node has a client; T015 needs the state for every Windows `--check-config`.
  Resolved in T015: the port returns `CliSystem.SystemDirectory` (`Read`/`Unreadable(reason)`),
  and `Launcher` sets up the containment for every Windows `--check-config` (reviews/T015-r1.md).
- [X] T016 [US2] Credentials on Windows (FR-002, FR-003, R-9): before a StartCLI call on Windows,
  `CliRunner` refuses a username with a refused character and a password with a non-ASCII
  character with `AUTH_FAILED` naming the entry and cause, before the `CredentialGuard` login step
  (no failed-login count); `EntryCheck` shows `invalid value (Windows: <class>)` for such a
  username on nodes with a StartCLI installation. Test first: `CliRunnerTest` (no launch, guard
  untouched, message without value), `EntryCheckTest` Windows cases, a `SecretLeakTest` case.
- [ ] T017 [US2] Secret inspection (SC-003, FR-003), after T019: **(Windows)** `WindowsSecretInspectionTest`
  (new) runs export, import and history calls of `CliExportRunner`/`CliImportRunner` against the
  substitute and asserts that no credential occurs in `args`, `%CMDCMDLINE%` or the environment,
  the environment holds only the allowlist, and the password arrived on stdin as UTF-8 plus `\n`.
  A POSIX twin asserts the same with today's launch. Verification task.

**Checkpoint**: StartCLI starts on Windows and the safety property holds.

---

## Phase 5: User Story 1 - Read-only StartCLI tools on Windows (Priority: P1)

**Goal**: client check, module list, history and export work on Windows as on macOS.
**Independent test**: the tool tests on the Windows CI job; later the live check (T031).

- [X] T018 [US1] Paths inside the command (FR-009, R-3): `CliPaths` platform-aware (Windows rule
  `^[A-Za-z]:/[A-Za-z0-9_.\- /]{1,256}$`, no `.`/`..`), `CliCommand.Builder.path` renders `/` on
  Windows and checks the platform rule, `CliExportRunner`/`CliImportRunner` messages print the
  platform's rule, temporary paths go through `toRealPath()` (the parent for files not yet
  written), and `ConfigValidator` runs the `java.io.tmpdir` check on Windows with the
  `-Djava.io.tmpdir` remedy. Test first: `CliPathsTest` (new; accepted and refused Windows paths,
  8.3 expansion via a stubbed resolver, UNC refused, 259/260 characters), `CliCommandTest` Windows
  rendering, `ConfigValidatorTest` Windows tmpdir warning.
- [ ] T019 [US1] Private temporary data (FR-007, FR-008, R-10): `CliExportRunner`,
  `CliImportRunner` and `CliResources` create their directories through `PrivateFileSystem`;
  `CliResources.sweepStale` runs on Windows (`Launcher` drops the `!windows` condition; owner
  compared with the token user), with 5 deletion attempts within 2 s for held files, then a log
  line with the directory name only; the owner check uses `PrivateFileSystem.ownedByCurrentUser`.
  Test first: `CliResourcesTest` (sweep on a simulated Windows root; retry bound with an injected
  deleter), a failing `PrivateFileSystem` stub (export and import fail with their existing code
  and a next step naming the directory and the required access; no launch) and **(Windows)** cases
  in `CliExportRunnerTest`, `CliImportRunnerTest` and `ExportShutdownProcessTest`: DACL of the
  temporary directory and of a file the substitute writes into it, as in T011; a server killed
  during an export leaves no StartCLI process (job, T012) and the next start sweeps its
  directory.
  This task wires `OsPrivateFileSystem` through `Launcher` and `Wiring` for `adapter/cli`.
- [ ] T020 [US1] Client check and announcement (FR-010): the client probe (`-v`) runs on Windows
  in `--check-config` and `get_health` through the Windows launch; `list_nodes` shows `cli: true`
  and the StartCLI-backed tools are announced for a Windows client installation. Test first:
  `ConfigSummaryTest`/`MainTest` Windows node line with client version, `ListNodesToolTest` and the
  tool-announcement cases of `WiringE2eTest` with the Windows flag.
- [ ] T021 [US1] Read-only tools end to end (FR-001, FR-006): **(Windows)**
  `WindowsReadOnlyToolsTest` (new) runs `list_inventory` (modules), `get_inventory_item` (history)
  and `export_artifacts` through the Windows launch with the substitute replaying the macOS
  recordings of `fixtures/v8_1/cli/` as UTF-8 with CRLF; results equal the macOS results; no
  temporary data remains. Then remove `continue-on-error` from the `windows` job (T001): the whole
  suite is green on Windows (making the check required in branch protection is the person's
  setting). Verification task.

**Checkpoint**: the read-only StartCLI tools work on Windows offline; the Windows job is green.

---

## Phase 6: User Story 4 - Timeouts and cleanup (Priority: P2)

- [ ] T022 [US4] Process tree on Windows (FR-005, SC-004): **(Windows)** `WindowsProcessTreeTest`
  (new) and `SystemProcessLauncherTest` Windows cases — the substitute hangs and starts a child;
  after `cliTimeout` the call fails with `TIMEOUT` and no process of the tree is alive one second
  after the grace period, 20 runs; the same for a closed MCP connection (orderly stop) with the
  export directory removed. Fix the tree handling of `SystemProcessLauncher` if the interpreter
  exits before Java. Verification task where it passes.
- [ ] T023 [US4] Timeout after the password prompt on Windows (FR-005 as amended after plan review
  r2, R-10): on Windows, when the last output of a timed-out call is the client's password prompt,
  the `TIMEOUT` cause names both possibilities (the client may be waiting for the password on its
  console, or the server is slow) and the next step keeps "raise `cliTimeout`" and adds the live
  check; macOS and Linux keep today's message. Test first: `CliOutputClassifierTest`,
  `CliRunnerTest` timeout cases for both platforms, with and without the prompt as last output.

---

## Phase 7: User Story 3 - Write tools with the same safety (Priority: P2)

- [ ] T024 [US3] Audit (FR-007): `AuditLog` and `Launcher.createAuditDirectory` use
  `PrivateFileSystem`; at startup and in `--check-config`, `check` on the default audit directory
  and the audit log warns with the `icacls` remedy when another principal has access; nothing is
  changed; POSIX behaviour unchanged. This task wires the port for `infra`, `application`,
  `config` and `adapter/git` (T025–T027); `adapter/cli` was wired in T019. Test first: `AuditLogTest`/`MainTest` POSIX cases unchanged; a
  failing `PrivateFileSystem` stub makes the server refuse to start (as today when the audit
  directory cannot be created); **(Windows)** DACL read-back and the warning for a directory with
  an inherited `Users` entry.
- [ ] T025 [US3] Deployment files (FR-007): `BackupStore`, `PackageWriter`, `DeployLock` and
  `DeploymentLedger` use `PrivateFileSystem` (injected; `PackageBoundaryTest` green); the existing
  deployment ledger and lock are checked like the audit log. Test first: their POSIX tests
  unchanged; a failing `PrivateFileSystem` stub fails each class with its existing error (every
  platform); **(Windows)** DACL read-back per class.
- [ ] T026 [US3] Workspace and git (FR-007): `WorkspaceDirectory` (when it creates the workspace)
  and `GitCli` (hooks directory) use `PrivateFileSystem`; an existing workspace is not checked.
  Test first: `GitCliTest`/`ConfigValidatorTest` POSIX cases unchanged; a failing
  `PrivateFileSystem` stub makes the workspace tools unavailable (as today when the workspace cannot
  be created); **(Windows)** DACL read-back of a new workspace and of the hooks directory; an
  existing workspace gives no warning.
- [ ] T027 [US3] Write tools end to end (FR-001): **(Windows)** `WindowsWriteToolsTest` (new) runs
  `import_artifacts` (preview and execution with the code), `restore_backup`, `set_active`,
  `tag_artifacts`, `restart_process`, `kill_process` and a real `deploy_release` against the
  substitute with recorded outputs — one node succeeding, one failing with roll-back from its
  backup, and a package-only target whose packages are read back with the DACL of FR-007;
  confirmation codes and audit records as in the existing POSIX tests. Verification task.

---

## Phase 8: User Story 5 - INUBIT 6.1 on Windows (Priority: P2, conditional)

- [ ] T028 [US5] (FR-012, R-14) Only if feature 012 is on `main` when this phase starts: merge
  `main`, route the 6.1 session through the Windows launch and add **(Windows)** variants of the
  012 session tests (`V61ConnectionTest`, `V61SessionTest` or their successors). Otherwise: add a
  task to `specs/012-inubit-61-cli-reads/tasks.md` on its branch ("Windows variants of the 6.1
  session tests, feature 013 FR-012") and mark this task done with a note.

---

## Phase 9: User Story 6 - Documentation (Priority: P3)

- [ ] T029 [P] [US6] Docs (FR-016): README (requirements: StartCLI on Windows with
  `bin\startcli.bat`, Java 17 runtime; remove "not supported on Windows"), `docs/setup.md`
  (Windows client setup, refused characters, path rule and `-Djava.io.tmpdir`, job and DACL notes,
  6.1 on Windows per T028), `docs/tools.md` (error texts), `docs/live-tests.md` (Windows section:
  which live tests run on Windows and their opt-in variables), CHANGELOG `[Unreleased]`. Test
  first: the docs reference tests stay green; `DocsWindowsStatementsTest` (new) fails while a "not
  supported on Windows" statement about StartCLI remains.

## Phase 10: Recordings and live check on Windows (manual, approved; US6)

**Blocked on**: a Windows user with a non-production INUBIT 8.1 node and the person's approval in
the chat for each step.

- [ ] T030 [US6] Recorder (FR-014, R-13): `tools/record-fixtures.ps1` with the read-only cases
  `win_version`, `win_help`, `win_modules`, `win_failure`, `win_argv`, `win_backslash`, same stdin
  and allowlist rules as the server, no password on the command line; `.gitattributes` gets
  `src/test/resources/fixtures/windows/** -text`. Test first: `test/tools/RecordFixturesPs1Test`
  (new) reads the script (no `-p`, password only through the child's stdin, allowlist, the group
  allowlist check before each case, all six cases listed).
- [ ] T031 [US6] Record and live-check (FR-014, FR-015; by the Windows user, after approval): run
  T030's cases, neutralise, add them under `fixtures/windows/`, run `NoCustomerIdentifiersTest`;
  run the opt-in JUnit live tests on Windows (read-only first: `InventoryLiveTest`,
  `ArtifactExportLiveTest`, `CredentialStoreLiveTest`; then, on a development node or approved
  target, `DevelopmentLiveTest` and `DeploymentLiveTest`) and the manual steps of quickstart.md;
  fill its "Validation record". If the client does not read the password from redirected stdin:
  stop and ask the person (FR-003). If separator, `\` handling, encoding or the R-4 margin differ
  from the defaults: update spec, research R-3/R-4/R-6 and the code in a follow-up task.
- [ ] T032 [US6] Windows recordings in the offline suite (SC-005): parse tests over the Windows
  recordings, transcoded into 850, 1252 and 65001, equal the macOS results; the substitute of T002
  is adjusted to the recorded argument forwarding. Test first: `WindowsRecordingsTest` (new).
- [ ] T033 Quickstart validation: offline table of quickstart.md, full `mvn -q -o clean verify`
  green on macOS, `NoCustomerIdentifiersTest` run (20 tests, 0 skipped), Windows CI job green;
  record in quickstart.md.

---

## Dependencies & Execution Order

- Phase 1 (T001, T002) → Phase 2 (T003, T004 [P] after T002; T005) → Phase 3 (T006 → T007 →
  T008 → T009 → T010; T011 and T012 after T008) → US2 (T013 → T014; T015, T016 after T013) → US1
  (T018 → T019) → T017 (after T019) → US1 (T020 → T021) → US4 (T022, T023) → US3 (T024 → T025 →
  T026 → T027) → US5 (T028) → docs (T029 any time after T021) → Phase 10 (T030 → T031 → T032) →
  T033.
- Windows-only tests need the `windows` CI job (T001) on the pushed branch of draft PR #53.

## Parallel Opportunities

- T001 and T002; T003 and T004; T011 and T012; T029 alongside Phases 6–8.

## Implementation Strategy

- **MVP**: Phases 1–5 (porting, building blocks, US2, US1): read-only StartCLI tools on Windows
  with the safety property, the Windows job required and green; then the live check T031 with a
  Windows user.
- **Then**: US4, US3, US5, docs; the defaults of the Clarifications are revisited after T031.
