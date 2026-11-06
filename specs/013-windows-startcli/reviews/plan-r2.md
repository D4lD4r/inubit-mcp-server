FEATURE: specs/013-windows-startcli  ROUND: 2

The r1 findings are fixed (blocker, PrivateFiles boundary as a domain.port port, protected DACL, JDK quoting precondition, java resolution, matrix and US1 AS5, length mapping, real deploy_release with kill_process, win_backslash, JUnit live tests, the T020 split, CliCommand/runners in T017, all r1 minors). The findings below are new or left over.

COVERAGE:
- FR-001 → T013, T017, T019, T020, T022, T026
- FR-002 → T006, T007, T008, T009, T013, T014, T015; partial: the server's JAVA_HOME fallback is never checked (2)
- FR-003 → T015, T016, T030; FR-004 → T009, T016
- FR-005 → T012, T018, T021, T022; prompt detection unsound (3)
- FR-006 → T010, T020, T031
- FR-007 → T011, T018, T023, T024, T025, T026; partial: "access cannot be set" tested only in T024 (4)
- FR-008 → T018; FR-009 → T017; FR-010 → T013, T019, T027; FR-011 → T007, T013, T014; FR-012 → T027
- FR-013 → T001–T005, T020; partial: porting inventory incomplete (1)
- FR-014 → T029–T031; FR-015 → T030; FR-016 → T028; FR-017 → T009, T010, T016, T032
- US1–US6 and SC-001–SC-007 covered as listed in the report (US4 AS6 → T022, see 3)

FINDINGS:
1. [major] T003/T004/T005, "Replaced POSIX tests" (FR-013): (a) ~35 test sites build `new CliRunner(..., Map.of("PATH","/usr/bin"), false, ...)`; after the port the runner hard-wired to `windows=false` looks for `bin/startcli.sh` and fails; (b) undecided whether the `CliPaths` rule follows the injected flag or the host OS (with the flag, `@TempDir` `C:\…` paths fail the POSIX rule); (c) `/bin/sh -c "exit 0"` dead-PID helpers in CliResourcesTest:56 and ExportShutdownProcessTest:160; (d) SIGTERM cases in ExportShutdownProcessTest are POSIX-only with no Windows equivalent (job kill + sweep at next start). – Decide (a)/(b), name the classes, add (c)/(d) to the table.
2. [major] T013/T014, R-2 (FR-002): only `cli.javaHome` is checked; the server's `JAVA_HOME` fallback (CliRunner.launchTarget 184–186) is expanded by `startcli.bat` and copied into the new `PATH`. – Apply `WindowsArguments` to the effective Java home (ConfigValidator warning naming `JAVA_HOME`, per-call `CLI_UNAVAILABLE`), add it to the matrix and contract, and handle `;` when `PATH` is built.
3. [major] T022 / R-10 (FR-001, FR-005, US4 AS6): "password prompt as the last output" cannot tell a client waiting for the password from a slow server (the client always prints `Password: ` first); every slow timeout would lose the `cliTimeout` next step; a client reading the console would prompt on the hidden console. – Define a distinguishing signal and test both, or change the spec (a cause naming both possibilities, keeping the `cliTimeout` next step).
4. [major] T018, T023, T025 (FR-007): the "access cannot be set" path is tested only in T024. – Add failing-stub cases (temp dir: error code, next step, no launch; audit: server does not start; workspace: tools unavailable).
5. [minor] T014 / R-11: how the configured line length and the containment state reach `ConfigValidator` without a config → adapter dependency.
6. [minor] R-6 / T010: lines printed by `startcli.bat` use the console OEM code page (850), not `native.encoding` (1252).
7. [minor] R-10 / T018: on an elevated account new objects are owned by `BUILTIN\Administrators`, so `sweepStale` would never match; set the owner in `OsPrivateFileSystem` or match the DACL; name the port through which `CliResources` gets the token user.
8. [minor] R-7 / T012: the job handle must be non-inheritable (children start with `bInheritHandles=TRUE`); assert it.
9. [minor] T008 / R-1 fallback: `CreateProcessW` in adapter/os conflicts with `onlyTheCliAdapterStartsOrInspectsProcesses` and is larger than the task; make it its own task with the boundary handling.
10. [minor] Dependencies: add T008 → T009.
11. [minor] T023/T024/T025 all wire `PrivateFileSystem` through `Wiring`/`Launcher`; drop the parallel claim.
12. [minor] T013 size: split the SC-002 matrix test into its own verification task; state the constructor approach for the ~35 `new CliRunner(` sites.
13. [minor] Tasks header: say how Red is observed for Windows-only tests (push the failing test, see it red in the job).
14. [minor] plan.md Source Code still says "PrivateFiles" for GitCli.
15. [minor] R-3 names a `win_export` case that does not exist elsewhere.
16. [minor] T001/T020: "required" is branch protection, not the workflow file.

VERDICT: CHANGES_REQUESTED
