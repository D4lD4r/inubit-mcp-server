FEATURE: specs/013-windows-startcli  ROUND: 3

The round-2 findings are fixed (porting decisions, effective JAVA_HOME, prompt timeout via the spec amendment and T023, failing-stub cases, lineLength in config, OEM code page, token-user owner and ownedByCurrentUser, non-inheritable job handle, fallback as a separate task, T008 → T009, T024–T026 in sequence, T013/T014 split, Windows Red procedure, GitCli wording, win_export, branch protection). Two new majors remain, both from the existing code.

COVERAGE:
- FR-001 → T013, T018, T020, T021, T023, T027
- FR-002 → T006–T009, T013–T016; partial: no per-call CLI_UNAVAILABLE naming cli.home (or UNC), tls.trustStore or the StartCLI URL (finding 2)
- FR-003 → T016, T017, T031; FR-004 → T009, T017; FR-005 → T012, T019, T022, T023; FR-006 → T010, T021, T032
- FR-007 → T011, T019, T024–T027; FR-008 → T019; FR-009 → T018; FR-010 → T013, T020, T028; FR-011 → T007, T013, T015; FR-012 → T028
- FR-013 → T001–T005, T021; partial: test doubles and POSIX-binary launcher tests not ported (finding 1)
- FR-014 → T030–T032; FR-015 → T031; FR-016 → T029; FR-017 → T003, T004, T009, T010, T017, T033
- US1–US6, SC-001–SC-007 covered as listed in the report

FINDINGS:
- [major] T003/T004/T005, "Replaced POSIX tests" (FR-013, T021 done criterion): porting covers the script name and the runner flag, not the code that reads the launch. ScriptedProcessLauncher.execCommand (line 203), FakeProcessLauncher and helpers in CliExportRunnerTest:113, V81ProcessControlAdapterTest:90, V81InventoryAdapterTest (~19 classes, incl. the Deploy and Import harnesses) find `--execCommand` as its own element of `spec.command()`; a Windows LaunchSpec is `[cmd.exe, /d, /v:off, /s, /c, <inner>]`. Expected strings like `"--exportFile '" + file + "'"` use `\`; CliRunnerTest 177/204 assert the POSIX list exactly; every SystemProcessLauncherTest method uses POSIX binaries; the old CliRunner constructor with NoJob and POSIX defaults leaves Windows hosts without a system directory, and no task moves Launcher:244 or Wiring:222 to the new constructor. – Decide how tests see the logical StartCLI arguments, port the doubles and helpers, make expected paths platform-aware, give the POSIX-binary launcher cases guards plus ArgvRecorder Windows equivalents, give the old constructor real Windows queries or make it test-only, name Launcher.probeClients and Wiring as users of the new constructor, and add all of this to the table.
- [major] T013 / T007 (US2 AS1, FR-002): per call only length, Java home, containment and system directory are mapped; nothing maps a refused character in cli.home, tls.trustStore or the StartCLI URL, or a UNC cli.home, to CLI_UNAVAILABLE naming the setting. – Add per-call checks in CliRunner.launchTarget (Windows) with CliRunnerTest cases per setting; state the typed failure of the T007 guard.
- [minor] T017 runs Windows export/import/history calls before T018 changes the path rule. – Order T017 after T018 (and T019).
- [minor] T019 vs T024 wiring of PrivateFileSystem. – T019 wires it for adapter/cli.
- [minor] T012: setting up the job in Wiring binds the surefire JVM into a new job per in-process Wiring. – Once per process in Launcher (main path), state passed to Wiring.
- [minor] T013/T010: system queries reach adapter/cli from adapter/os.WindowsSystem directly (cycle, not behind a port). – Small port in adapter/cli implemented by WindowsSystem.
- [minor] T015: the username is unknown at validation. – State which username length the configured-length warning assumes.
- [minor] T025: the failing-DACL case should be a failing-stub test on every platform.
- [minor] Consistency: R-12 table lacks the dead-PID and SIGTERM rows and says "T021 makes it required"; data-model omits ownedByCurrentUser; configuration-delta lacks an unreadable-system-directory warning; quickstart's Windows-only list omits WindowsSecretInspectionTest, WindowsReadOnlyToolsTest, WindowsWriteToolsTest, WindowsSystemTest.

VERDICT: CHANGES_REQUESTED
