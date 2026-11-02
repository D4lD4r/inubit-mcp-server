# Implementation Plan: StartCLI-Backed Tools on Windows

**Branch**: `013-windows-startcli` | **Date**: 2026-11-02 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/013-windows-startcli/spec.md`

**Base**: `main` at `18344e2` (0.7.0 unreleased: features 010, 011; setup docs for three systems).
Feature 012 (INUBIT 6.1) is not on `main`; see research R-14.

## Summary

Every StartCLI-backed tool runs when the server runs on Windows, with the same results and the same
safety as on macOS and Linux. The server starts `bin\startcli.bat` through the system's own command
interpreter with a command line it builds and quotes itself (`cmd.exe /d /v:off /s /c`), refuses the
few characters the interpreter would interpret, keeps the password on standard input, forces UTF-8
output, binds all child processes to a kill-on-close job, and creates private files with an access
list for the server's account and `SYSTEM` only. A Windows CI job, Windows recordings and a live
check by a Windows user verify it; the defaults of the spec's Clarifications apply until then.

- **Launch** (research R-1, R-2, R-4): `WindowsCommandLine` (quoting, trailing backslashes, length
  limit), `WindowsArguments` (refused characters, UNC), raw command line in
  `ProcessLauncher.LaunchSpec`.
- **Paths inside the command** (R-3): Windows rule in `CliPaths`, `toRealPath()` and `/`.
- **Environment and encoding** (R-5, R-6): Windows allowlist, Windows-only `JAVA_TOOL_OPTIONS`,
  UTF-8-first decoder with native fallback, CRLF normalisation.
- **Process lifetime** (R-7): kill-on-close job in `adapter/os` behind a `ProcessContainment` port.
- **Private files** (R-8): `infra.PrivateFiles` for POSIX permissions (unchanged) and Windows ACLs;
  checks of existing audit and deployment objects.
- **Credentials** (R-9): Windows username and non-ASCII password refusals before the guard.
- **Cleanup, messages, `--check-config`** (R-10, R-11).
- **Tests and CI** (R-12), **recordings and live check** (R-13), **feature 012** (R-14).

## Technical Context

**Language/Version**: Java 21 (unchanged); StartCLI clients run on their own runtimes (Java 17 for
8.1; the 6.1 client's runtime).

**Primary Dependencies**: unchanged. JNA 5.17.0 core (already present since feature 011, Apache-2.0
option) is additionally used for `kernel32` job objects in `adapter/os`. No new dependency.

**Storage**: none new. Private files as today, with Windows ACLs.

**Testing**: JUnit 5, AssertJ. Pure units (command-line builder, argument rules, path rule,
decoder, allowlist) on every platform; Windows-only tests (`@EnabledOnOs(WINDOWS)`) with a
substitute `startcli.bat` that calls a test-classpath `ArgvRecorder` main; POSIX tests unchanged.
New CI job `windows-latest` (`mvn -B verify`). Python tests of `tools/` unchanged; a Pester-free
PowerShell recorder is checked by `RecordFixturesCatalogueTest`-style text tests.

**Target Platform**: macOS, Linux (unchanged), Windows 10/11 and Server 2019+ x64 (new for
StartCLI-backed tools).

**Project Type**: single Maven module, shaded JAR.

**Performance Goals**: none new; the job setup at startup is a few milliseconds; StartCLI calls
gain no measurable overhead.

**Constraints**:
- Constitution I, II, V on Windows: no interpretation of values by the interpreter (R-1, R-2), no
  credential in arguments, environment or files (R-5, R-9), absolute program paths.
- macOS and Linux behaviour unchanged (FR-017); every POSIX code path stays as it is.
- JNA only in `adapter/os` (`PackageBoundaryTest`); natives loaded only on Windows.
- Offline default build.

**Scale/Scope**: no new tool or configuration key. Changed: `CliRunner`, `SystemProcessLauncher`,
`ProcessLauncher.LaunchSpec`, `CliPaths`, `CliResources`, `CliExportRunner`, `CliImportRunner`,
`CliOutputClassifier`, `ConfigValidator`, `EntryCheck`, `Launcher`, `Wiring`, `AuditLog`,
`BackupStore`, `PackageWriter`, `DeployLock`, `DeploymentLedger`, `GitCli`,
`WorkspaceDirectory`; docs (README, setup, tools, live-tests, CHANGELOG). New: `WindowsCommandLine`,
`WindowsArguments`, `StartCliEnvironment`, `OutputDecoder`, `ProcessContainment` (+ `WindowsJob`
in `adapter/os`), `PrivateFiles`, `tools/record-fixtures.ps1`, Windows test helpers, CI job.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Safe by default | No new write path; write tools keep their gates. "Never pass unvalidated input to a shell": on Windows a batch file always runs in the interpreter, so the plan validates every value that reaches it (R-2) and quotes the rest (R-1); the injection matrix proves it (SC-002). | PASS (see Complexity Tracking) |
| II. Secrets out of the conversation | Password only on stdin; no credential variable in the allowlist; username/password checks name entries, never values; private files restricted by ACL (R-8, R-9). | PASS |
| III. Test-first | Every new unit test-first; Windows-only tests for the rules that only Windows can show; safety and secret tests run on the Windows CI job (FR-013). | PASS |
| IV. Use-case tools | No tool change. | PASS |
| V. Adapter isolation | Launch details stay in `adapter/cli`; JNA job in `adapter/os` behind a port; Windows parts selected by the existing `windows` flag. "Argument arrays (no shell interpolation)" cannot hold literally for a `.bat` on Windows: recorded below. | DEVIATION (justified) |
| VI. Bounded output | Unchanged bounds; decoder works on the bounded buffers. | PASS |
| VII. Observability | New warnings and log lines name settings and directories, never values. | PASS |

**Post-design re-check (after Phase 1)**: unchanged; the contracts keep every value outside the
interpreter's reach except the documented literal characters.

## Project Structure

### Documentation (this feature)

```text
specs/013-windows-startcli/
├── spec.md
├── plan.md              # this file
├── research.md          # R-1 … R-14
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── windows-invocation.md
│   └── configuration-delta.md
├── checklists/requirements.md
└── reviews/
```

### Source Code (repository root)

```text
src/main/java/de/dadecker/inubit/mcp/
├── adapter/cli/   WindowsCommandLine, StartCliEnvironment, OutputDecoder, ProcessContainment (port);
│                  CliRunner, SystemProcessLauncher, ProcessLauncher, CliResources,
│                  CliExportRunner, CliImportRunner, CliOutputClassifier (changed)
├── adapter/os/    WindowsJob (JNA kernel32), NoJob (POSIX)
├── adapter/git/   GitCli (hooks directory via PrivateFiles)
├── application/   BackupStore, PackageWriter, DeployLock, DeploymentLedger (PrivateFiles)
├── config/        WindowsArguments, CliPaths, ConfigValidator, EntryCheck, WorkspaceDirectory
├── infra/         PrivateFiles, AuditLog
├── Launcher.java, Wiring.java
src/test/java/de/dadecker/inubit/mcp/
├── adapter/cli/   WindowsCommandLineTest, WindowsCommandLineInjectionTest (Windows),
│                  WindowsProcessTreeTest (Windows), OutputDecoderTest, StartCliEnvironmentTest
├── adapter/os/    WindowsJobTest (Windows)
├── infra/         PrivateFilesTest, PrivateFilesAclTest (Windows)
├── testing/       ArgvRecorder, FakeStartCli (writes .sh or .bat)
tools/record-fixtures.ps1
src/test/resources/fixtures/windows/   recordings (neutralised)
.github/workflows/ci.yml               + windows-latest job
```

**Structure Decision**: existing single module; Windows parts sit next to their POSIX counterparts
and are selected by the `windows` flag the code already passes around.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| Principle V "argument arrays (no shell interpolation)" on Windows: `startcli.bat` always runs in `cmd.exe` | The INUBIT client for Windows is started through its batch file (the clarification fixes this until the batch files are examined); a batch file has no argument array. | Starting the client's Java directly avoids the interpreter but needs each client version's class path and options, unknown until R-13; refusing Windows keeps today's gap. The deviation is bounded: absolute interpreter with `/d /v:off`, every argument quoted, the interpreting characters refused, proven by SC-002 on the Windows CI job. |
| A job object through JNA (`kernel32`) | FR-005 requires ending StartCLI on a forced termination of the server; Windows offers no other mechanism. | Shutdown hooks do not run on forced termination; a watchdog process adds a process to manage. JNA is already a dependency. |
