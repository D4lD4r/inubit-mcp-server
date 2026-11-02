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
- constitution v1.0.0 (Complexity Tracking of plan.md records the Principle V deviation)

**Tests**: REQUIRED (Constitution III, TDD). Every task starts with the test(s) it names, observed
failing on an assertion before the production code exists, and ends with `mvn -q -o verify` green
on macOS. Tests marked **(Windows)** are `@EnabledOnOs(OS.WINDOWS)`: they are skipped locally and
run in the `windows` CI job (T001); a task with Windows tests is done only when that job is green
on the branch's draft pull request. Pushing the branch and opening or updating the draft pull
request need the person's OK in the chat.

**Organization**: a shared foundation (command line, arguments, environment, decoder, private
files, process lifetime), then US2 (the safety property) before US1 (read-only tools), then US4
(timeouts, cleanup), US3 (write tools), US5 (6.1, conditional on feature 012), US6 (recordings,
live check, docs). The recordings and the live check (Phase 9) are manual, approved steps by a
Windows user.

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
  `ArgvRecorder`). The only live steps are Phase 9, run by a Windows user after the person's
  approval in the chat.
- **Fixtures**: Windows recordings are neutralised with `tools/neutralize.py`; never the vendor's
  batch file, only what the client receives. The identifier hooks must pass; never `--no-verify`.
- **Configuration**: never change `~/.config/inubit-mcp/*` or the MCP registration.
- **Public repository**: no customer names in commit messages, PR descriptions or files.
- **macOS and Linux**: no behaviour change (FR-017); every POSIX path stays as it is.

---

## Phase 1: Setup

- [ ] T001 [P] Windows CI job (FR-013, research R-12): add a `windows` job to
  `.github/workflows/ci.yml` (`runs-on: windows-latest`, Temurin 21, `mvn -B -ntp clean verify`;
  the Python tool tests stay on Linux) and a `workflow_dispatch` trigger. Test first:
  `test/release/CiWorkflowTest` (new) reads the workflow and asserts both jobs run `verify` on
  Java 21 and the Windows job uses `windows-latest`.
- [ ] T002 [P] Test helpers (R-12): `test/testing/ArgvRecorder` (a main that writes its `args`,
  `System.getenv()`, the bytes read from stdin and `ProcessHandle.current().info().commandLine()`
  as JSON to a file named by a system property, optionally sleeps or starts a child) and
  `test/testing/FakeStartCli` (writes `bin/startcli.sh` on POSIX and `bin\startcli.bat` on
  Windows that run `ArgvRecorder` with the test classpath, forwarding `%*` on Windows). Test first:
  `FakeStartCliTest` runs the substitute through `SystemProcessLauncher` with plain arguments and
  reads the record (POSIX now; Windows in the CI job).

## Phase 2: Foundational (blocks every user story)

- [ ] T003 `WindowsArguments` (FR-002, R-2): `main/config/WindowsArguments` with
  `refusal(String value)` → `Optional<String>` character class (`"` `%` `!` `^` `&` `|` `<` `>`,
  control characters incl. tab and line breaks, non-ASCII) and `isUnc(Path)`. Pure, every platform.
  Test first: `test/config/WindowsArgumentsTest` with every character of SC-002, both outcomes.
- [ ] T004 `WindowsCommandLine` (R-1, R-2, R-4, contracts/windows-invocation.md):
  `main/adapter/cli/WindowsCommandLine` builds `"<SystemRoot>\System32\cmd.exe" /d /v:off /s /c
  ""<script>" "<arg>" …"` (every argument quoted, trailing backslashes doubled, `WindowsArguments`
  re-checked as a guard), validates `SystemRoot` (absolute drive path containing
  `System32\cmd.exe`, else `C:\Windows`), and enforces the 6 143-character limit with a typed
  failure telling tool input from configuration. Pure. Test first:
  `test/adapter/cli/WindowsCommandLineTest` (quoting, trailing `\`, `''`, the limit, `SystemRoot`
  fallback, the guard).
- [ ] T005 Raw command line in the launcher (R-1): `ProcessLauncher.LaunchSpec` gets an optional
  `windowsCommandLine`; `SystemProcessLauncher` on Windows starts the interpreter with exactly that
  line (no JDK re-quoting; research the JDK path in a code comment), working directory and
  environment as given; POSIX unchanged. Test first: `SystemProcessLauncherTest` POSIX cases
  unchanged; new **(Windows)** case: the substitute's record shows the expected argv and command
  line for the plain values.
- [ ] T006 [P] `StartCliEnvironment` (FR-004, R-5): extract today's POSIX allowlist from
  `CliRunner` into `main/adapter/cli/StartCliEnvironment` (unchanged) and add the Windows
  allowlist with case-insensitive names, `ComSpec` set to the interpreter and the Windows-only
  `JAVA_TOOL_OPTIONS`. Test first: `test/adapter/cli/StartCliEnvironmentTest` for both (POSIX
  output identical to today's, credential-like variables never copied).
- [ ] T007 [P] `OutputDecoder` (FR-006, R-6): `main/adapter/cli/OutputDecoder` — strict UTF-8,
  else the given native charset; CRLF → LF. `CliRunner` and the 6.1 session path (when present)
  decode through it; on POSIX the result equals today's. Test first:
  `test/adapter/cli/OutputDecoderTest` (UTF-8, 850, 1252, mixed CRLF, BOM).
- [ ] T008 `PrivateFiles` (FR-007, R-8): `main/infra/PrivateFiles` with `createDirectory`,
  `createDirectories`, `createTempDirectory`, `createFile`/`writeNew` and `check(Path)`; POSIX:
  today's permissions exactly; Windows: `acl:acl` attribute at creation (owner + `SYSTEM`, full
  control, `FILE_INHERIT`/`DIRECTORY_INHERIT` on directories), failure when it cannot be applied;
  `check` reports other principals. Test first: `test/infra/PrivateFilesTest` (POSIX permissions
  as today) and **(Windows)** `PrivateFilesAclTest` (read-back DACL has exactly two entries, none
  inherited; a file written into the directory by a child process inherits them).
- [ ] T009 `ProcessContainment` (FR-005, R-7): port `main/adapter/cli/ProcessContainment`
  (`state()`: `BOUND` | `UNAVAILABLE(reason)`), `main/adapter/os/NoJob` (POSIX, always `BOUND`) and
  `main/adapter/os/WindowsJob` (JNA `kernel32`: `CreateJobObjectW`, `SetInformationJobObject` with
  `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`, `AssignProcessToJobObject(GetCurrentProcess())`), set up
  once in `Launcher`/`Wiring` on Windows. Test first: `test/adapter/os/WindowsJobTest`
  **(Windows)**: a helper JVM binds itself, starts the substitute that starts a sleeping child, is
  killed with `destroyForcibly`; the child is gone within 2 s. `PackageBoundaryTest` stays green.

**Checkpoint**: the Windows building blocks exist and are unused; macOS behaviour unchanged.

---

## Phase 3: User Story 2 - No injection, password only on stdin (Priority: P1)

**Goal**: StartCLI on Windows receives exactly the intended values or nothing starts.
**Independent test**: the injection matrix (SC-002) and the credential inspection (SC-003) on the
Windows CI job.

- [ ] T010 [US2] Windows launch in `CliRunner` (FR-001, FR-002, FR-004): remove the
  `WINDOWS_UNSUPPORTED` refusal; on Windows build the call with `WindowsCommandLine`,
  `StartCliEnvironment` and `bin\startcli.bat`; refuse a `ProcessContainment` `UNAVAILABLE` with
  `CLI_UNAVAILABLE` and its reason. Test first: `CliRunnerTest` cases with a fake launcher on a
  simulated Windows flag (the `LaunchSpec` carries the expected line and environment; no
  `CLI_UNAVAILABLE` any more) and **(Windows)** `WindowsCommandLineInjectionTest`: every character
  of SC-002 in the username, trust-store path and StartCLI URL (refused or unchanged, as the
  contract says), `ArgvRecorder` sees no second program and the exact argv.
- [ ] T011 [US2] Configured values on Windows (FR-002, FR-011, contracts/configuration-delta.md):
  `ConfigValidator.checkCli` loses its Windows early return; on Windows it checks
  `bin\startcli.bat`, `WindowsArguments` for `cli.home`, `cli.javaHome`, `tls.trustStore` and the
  StartCLI URL, a UNC `cli.home`, the configured line length and the containment state, each as a
  warning with the node's StartCLI-backed capabilities reporting `CLI_UNAVAILABLE` naming the
  setting. Test first: `ConfigValidatorTest` Windows cases (each warning text of the contract; the
  REST tools of the node stay available; the old Windows warning is gone).
- [ ] T012 [US2] Credentials on Windows (FR-002, FR-003, R-9): before a StartCLI call on Windows,
  `CliRunner` refuses a username with a refused character and a password with a non-ASCII
  character with `AUTH_FAILED` naming the entry and cause, before the `CredentialGuard` login step
  (no failed-login count); `EntryCheck` shows `invalid value (Windows: <class>)` for such a
  username on nodes with a StartCLI installation. Test first: `CliRunnerTest` (no launch, guard
  untouched, message without value), `EntryCheckTest` Windows cases, and a `SecretLeakTest` case
  that the message never contains the value.
- [ ] T013 [US2] Secret inspection (SC-003, FR-003): **(Windows)** `WindowsSecretInspectionTest`
  runs export, import and history calls of `CliExportRunner`/`CliImportRunner` against the
  substitute and asserts that no credential occurs in argv, command line or environment, the
  environment holds only the allowlist, and the password arrived on stdin as UTF-8 plus `\n`. A
  POSIX twin asserts the same with today's launch (verification task for POSIX).

**Checkpoint**: StartCLI starts on Windows and the safety property holds.

---

## Phase 4: User Story 1 - Read-only StartCLI tools on Windows (Priority: P1)

**Goal**: client check, module list, history and export work on Windows as on macOS.
**Independent test**: the tool tests on the Windows CI job; later the live check (T026).

- [ ] T014 [US1] Windows path rule (FR-009, R-3): `CliPaths` gets the Windows rule
  (`^[A-Za-z]:/[A-Za-z0-9_.\- /]{1,255}$`, no `.`/`..`), the server's temporary paths go through
  `toRealPath()` and `\` → `/` on Windows before the check, and `ConfigValidator` runs the
  `java.io.tmpdir` check on Windows with the `-Djava.io.tmpdir` remedy. Test first: `CliPathsTest`
  (accepted and refused Windows paths, 8.3 name expanded via a stubbed resolver, UNC refused,
  length), `ConfigValidatorTest` Windows tmpdir warning.
- [ ] T015 [US1] Private temporary data (FR-007, FR-008): `CliExportRunner`, `CliImportRunner` and
  `CliResources` create their directories through `PrivateFiles`; `CliResources.sweepStale` runs on
  Windows too (`Launcher` drops the `!windows` condition), with `Files.getOwner` and 5 deletion
  attempts within 2 s for held files, then a log line with the directory name only. Test first:
  `CliResourcesTest` (sweep on a simulated Windows root; retry bound with an injected deleter) and
  **(Windows)** a case where the substitute writes a file into the export directory and its ACL
  is inherited.
- [ ] T016 [US1] Client check and announcement (FR-010): the client probe (`-v`) runs on Windows
  in `--check-config` and `get_health` through the Windows launch; `list_nodes` shows `cli: true`
  and the StartCLI-backed tools are announced for a Windows client installation. Test first:
  `ConfigSummaryTest`/`MainTest` Windows node line with client version, `ListNodesToolTest` and
  `ToolAnnouncementTest` on a simulated Windows flag.
- [ ] T017 [US1] Read-only tools end to end on Windows (FR-001, FR-006): **(Windows)**
  `WindowsReadOnlyToolsTest` runs `list_inventory` (modules), `get_inventory_item` (history) and
  `export_artifacts` through the Windows launch with the substitute replaying the macOS recordings
  of `fixtures/v8_1/cli/` encoded as UTF-8 with CRLF; results equal the macOS results; no temporary
  data remains. Verification task.

**Checkpoint**: the read-only StartCLI tools work on Windows offline.

---

## Phase 5: User Story 4 - Timeouts and cleanup (Priority: P2)

- [ ] T018 [US4] Process tree on Windows (FR-005, SC-004): **(Windows)** `WindowsProcessTreeTest`
  — the substitute hangs and starts a child; after `cliTimeout` the call fails with `TIMEOUT` and no
  process of the tree is alive one second after the grace period, 20 runs; the same for a closed
  MCP connection (orderly stop) with the export directory removed. Fix `SystemProcessLauncher`
  tree handling if the interpreter exits before Java. Verification task where it passes.
- [ ] T019 [US4] Password-prompt timeout (FR-005, R-10): `CliOutputClassifier` recognises the
  client's password prompt as the last output; the `TIMEOUT` cause says StartCLI was still
  waiting for the password and the next step names the live check, on every platform. Test first:
  `CliOutputClassifierTest`, `CliRunnerTest` timeout case.

---

## Phase 6: User Story 3 - Write tools with the same safety (Priority: P2)

- [ ] T020 [US3] Private files of the write paths (FR-007): `BackupStore`, `PackageWriter`,
  `DeployLock`, `DeploymentLedger`, `GitCli` (hooks directory), `WorkspaceDirectory` (when it
  creates the workspace), `AuditLog` and `Launcher.createAuditDirectory` use `PrivateFiles`; POSIX
  behaviour unchanged, including the non-POSIX fallback of workspace and audit directory on POSIX
  file systems. Test first: the existing permission tests stay green; new **(Windows)** cases per
  class read back the ACL; a case where the ACL cannot be applied fails with the caller's existing
  error.
- [ ] T021 [US3] Existing objects (FR-007, US3 AS4): at startup and in `--check-config`,
  `PrivateFiles.check` on the default audit directory, the audit log, the deployment ledger and
  lock warns with the `icacls` remedy when another principal has access; nothing is changed; an
  existing workspace is not checked. Test first: `ConfigValidatorTest`/`LauncherTest` with a
  stubbed ACL reader; **(Windows)** a real directory with an inherited `Users` entry gives the
  warning.
- [ ] T022 [US3] Write tools end to end on Windows: **(Windows)** `WindowsWriteToolsTest` runs
  `import_artifacts` (preview and execution with the code), `restore_backup`, `set_active`,
  `tag_artifacts`, `restart_process` and a `deploy_release` dry run against the substitute with
  recorded outputs; confirmation codes, audit records, backups and roll-back behave as in the
  existing POSIX tests. Verification task.

---

## Phase 7: User Story 5 - INUBIT 6.1 on Windows (Priority: P2, conditional)

- [ ] T023 [US5] (FR-012, R-14) Only if feature 012 is on `main` when this phase starts: merge
  `main`, route the 6.1 session through the Windows launch and add **(Windows)** variants of the
  012 session tests (`V61ConnectionTest`, `V61SessionTest` or their successors). Otherwise: add a
  task to `specs/012-inubit-61-cli-reads/tasks.md` on its branch ("Windows variants of the 6.1
  session tests, feature 013 FR-012") and mark this task done with a note.

---

## Phase 8: User Story 6 - Documentation (Priority: P3)

- [ ] T024 [P] [US6] Docs (FR-016): README (requirements: StartCLI on Windows with
  `bin\startcli.bat`, Java 17 runtime; remove "not supported on Windows"), `docs/setup.md`
  (Windows client setup, refused characters, path rule and `-Djava.io.tmpdir`, job and ACL notes,
  6.1 on Windows per T023), `docs/tools.md` (error texts), `docs/live-tests.md` (Windows section),
  CHANGELOG `[Unreleased]`. Test first: the docs reference tests stay green; a
  `DocsWindowsStatementsTest` (new) fails while a "not supported on Windows" statement remains.

## Phase 9: Recordings and live check on Windows (manual, approved; US6)

**Blocked on**: a Windows user with a non-production INUBIT 8.1 node and the person's approval in
the chat for each step.

- [ ] T025 [US6] Recorder (FR-014, R-13): `tools/record-fixtures.ps1` with the read-only cases
  `win_version`, `win_help`, `win_modules`, `win_failure`, `win_argv` (argument list for the test
  values of the invocation contract via a recording wrapper), same stdin and allowlist rules as
  the server, no password on the command line. Test first: `test/tools/RecordFixturesPs1Test`
  reads the script (no `-p`, password only through the process's stdin, allowlist, group allowlist
  check before each case).
- [ ] T026 [US6] Record and live-check (FR-014, FR-015; by the Windows user, after approval): run
  T025's cases, neutralise, add them under `fixtures/windows/`, run `NoCustomerIdentifiersTest`;
  run the live steps of quickstart.md and fill its "Validation record". If the client does not
  read the password from redirected stdin: stop and ask the person (FR-003). If separator,
  encoding or margin differ from the defaults: update spec, research R-3/R-4/R-6 and the code in a
  follow-up task.
- [ ] T027 [US6] Recorded Windows fixtures in the offline suite (SC-005): parse tests over the
  Windows recordings, transcoded into 850, 1252 and 65001, equal the macOS results; the substitute
  of T002 is adjusted to the recorded argument forwarding. Test first: `WindowsRecordingsTest`.
- [ ] T028 Quickstart validation: offline table of quickstart.md, full `mvn -q -o clean verify`
  green on macOS, `NoCustomerIdentifiersTest` run (20 tests, 0 skipped), Windows CI job green;
  record in quickstart.md.

---

## Dependencies & Execution Order

- Phase 1 (T001, T002) → Phase 2 (T003–T009; T003 before T004; T004 before T005; T006–T009 [P]
  after T002) → US2 (T010 → T011, T012 → T013) → US1 (T014 → T015 → T016 → T017) → US4 (T018,
  T019) → US3 (T020 → T021 → T022) → US5 (T023) → US6 (T024 any time after T017; T025 → T026 →
  T027) → T028.
- Windows-only tests need the CI job of T001 and a pushed branch with a draft pull request (the
  person's OK).

## Parallel Opportunities

- T001 and T002; T006, T007, T008, T009 after T002; T024 alongside Phases 5–7.

## Implementation Strategy

- **MVP**: Phases 1–4 (US2 + US1): read-only StartCLI tools on Windows with the safety property,
  verified on the Windows CI job; then the live check T026 with a Windows user.
- **Then**: US4, US3, US5, docs; the defaults of the Clarifications are revisited after T026.
