FEATURE: specs/013-windows-startcli  ROUND: 1

COVERAGE:
- FR-001 → T010, T016, T017, T022 (Windows variants of next steps/messages not named, m1)
- FR-002 → T003, T004, T010, T011, T012; partial: Java resolution inside startcli.bat, matrix lacks cli.home and cli.javaHome (M2, M3)
- FR-003 → T012, T013, T026
- FR-004 → T006, T013
- FR-005 → T009, T018, T019, T015
- FR-006 → T007, T017, T027
- FR-007 → T008, T015, T020, T021
- FR-008 → T015
- FR-009 → T014
- FR-010 → T010, T011, T016, T023
- FR-011 → T004, T011; partial: no per-call INVALID_INPUT / CLI_UNAVAILABLE mapping (M5)
- FR-012 → T023
- FR-013 → T001, T002; partial: no porting of the existing suite, no list of replaced POSIX tests (B1)
- FR-014 → T025, T026, T027; partial: `\` inside a quoted value missing (M7)
- FR-015 → T026; partial: JUnit live tests not run on Windows (M8)
- FR-016 → T024
- FR-017 → T006/T007, T013 POSIX twin, T020, T028
- US1 AS1/AS2 → T016; AS3/AS4 → T017; AS5 → none (M3); AS6 → T014
- US2 AS1 → T011; AS2 → T012; AS3 → T010 (longest length not named); AS4 → T013; AS5 → T012; AS6/AS7 → partial (M5); AS8 → T026
- US3 AS1 → T020, T022; AS2 → dry run only (M6); AS3 → kill_process missing (M6); AS4 → T021
- US4 → T009, T015, T018, T019; US5 → T023; US6 → T001, T025, T026
- SC-001 → T017, T022, T026; SC-002 → T003, T010 (partial); SC-003 → T013; SC-004 → T018; SC-005 → T007, T027; SC-006 → T024, T026; SC-007 → T028

FINDINGS:
- [blocker] FR-013 / R-12 / T001: no task makes the existing offline suite run on Windows (tests write `#!/bin/sh` `startcli.sh` and call `Files.setPosixFilePermissions` unguarded, e.g. MainTest 646-651, 929, 1026, GitCliTest, AdapterGatewayFactoryTest, BackupStoreTest, DeployLockTest, CliExportRunnerTest; SystemProcessLauncherTest is `@DisabledOnOs(WINDOWS)`; AuditLogTest and MainTest skip via `assumeTrue(posix)`). The `windows` job would be red from the start; the plan lacks the promised list of replaced POSIX tests. – Add the list, porting tasks by package after T002, and either keep the job non-required until then or order the porting first.
- [major] T020 / R-8: `infra.PrivateFiles` cannot be used from `application` (PackageBoundaryTest allows JDK, domain, application, SLF4J); moving it to `application` breaks `adapter.git`. – Decide its place (JDK-only helper in `domain`, or a `domain.port` implemented outside) and keep PackageBoundaryTest green.
- [major] R-8 / T008: `acl:acl` at creation does not prevent inheritance: the JDK builds the descriptor without `SE_DACL_PROTECTED`, so the parent's inheritable ACEs are merged (e.g. Administrators under %TEMP%). – Decide a mechanism that sets a protected DACL before content is written (e.g. JNA `SetSecurityInfo` with `PROTECTED_DACL_SECURITY_INFORMATION` in adapter/os behind a port).
- [major] R-1 / T005: the JDK has no public API for a raw Windows command line; `ProcessImpl` quotes per argument depending on `jdk.lang.Process.allowAmbiguousCommands`. – State the mechanism and preconditions (e.g. one pre-quoted argument left unchanged, or JNA `CreateProcessW`) and pin it with a test on the CI JDK; T005 asserts the line the child receives.
- [major] FR-002 / R-1, R-5: only the interpreter is absolute; `startcli.bat` may call `java` unqualified, found in `cli.home` or the copied `PATH`. – Add `NoDefaultCurrentDirectoryInExePath=1`, build `PATH` as `%JAVA_HOME%\bin;%SystemRoot%\System32`, and test with decoy `java.exe` files.
- [major] T010 / SC-002: matrix lacks `cli.home` and `cli.javaHome`; US1 AS5 untested. – Extend the matrix; add the AS5 case with an assertion that nothing was written into the installation.
- [major] US2 AS6/AS7 / FR-011: no task maps the length failure to `INVALID_INPUT` / per-call `CLI_UNAVAILABLE` with tests. – Name CliRunnerTest cases, also for the longest allowed length.
- [major] T022 / US3 AS2, AS3: a dry run does not exercise backup/import/verify/roll-back or package ACLs; `kill_process` missing. – Real deployment against the substitute incl. a failing node and a package-only target; add `kill_process`.
- [major] T025 / FR-014: no case for `\` inside a quoted command value. – Add `win_backslash` to recorder, contract and quickstart.
- [major] T026 / FR-015: the JUnit live tests are not run on Windows. – Run them with their opt-in variables (read-only first, writes only on a development node after approval).
- [major] T020 size: eight classes plus Windows tests in one task. – Split (audit; deployment; workspace and git).
- [major] T014 / R-3: paths go into the command through `CliCommand.Builder.path` (`toString()`) and the static `CliPaths.passable`, and `exportRootUsable` call sites in the runners print the POSIX regex. – Add `CliCommand` and the runners; `toRealPath()` needs an existing path (use the parent for files not yet written).
- [minor] m1: Windows variants of next steps such as "the file permissions of bin/startcli.sh" (CliRunner.launchAndWait) and path-rule messages; name them in T010/T014 with tests.
- [minor] T006/T007 both [P] but both change CliRunner. – Order them.
- [minor] T007 / R-6: whole-buffer fallback decodes UTF-8 lines natively once one line is not UTF-8; native fallback and CRLF change on POSIX could alter today's output. – Decide per line, Windows only; POSIX path unchanged.
- [minor] T019 "on every platform" conflicts with FR-001/FR-017. – Restrict to Windows or justify.
- [minor] R-3 regex `{1,255}` allows 258, FR-009 says 259. – Use `{1,256}` or a separate length check.
- [minor] T002/T005: `ProcessHandle.Info.commandLine()` is not populated on Windows and would describe Java's line anyway. – Rely on `args`; say how the interpreter line is checked.
- [minor] R-8 / R-10: on an elevated account the default owner can be `BUILTIN\Administrators`. – Take the owner ACE from the process token's user SID; say how `sweepStale` behaves.
- [minor] T004 / R-1: `C:\Windows` fallback is wrong on other drives. – Use `GetSystemDirectoryW` or refuse.
- [minor] T026/T027: `.gitattributes` `* text=auto eol=lf` would turn CRLF in `fixtures/windows/` into LF. – Add `-text`.
- [minor] Test names `ToolAnnouncementTest`, `CliPathsTest`, `LauncherTest`, `RecordFixturesCatalogueTest` do not exist in the worktree. – Mark "(new)" or use existing names.
- [minor] R-4: the 2 048 margin is a guess. – Note it as a default revisited in T026.

VERDICT: CHANGES_REQUESTED
