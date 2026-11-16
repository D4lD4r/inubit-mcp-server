# Quickstart: validate StartCLI on Windows

## Offline (every platform)

```bash
mvn -q -o clean verify
```

- macOS/Linux: the suite is green with no test removed (SC-007); `NoCustomerIdentifiersTest` runs
  (20 tests, 0 skipped).
- Windows CI job (`windows-latest`): `mvn -B verify` green; the Windows-only tests run
  (`WindowsCommandLineInjectionTest`, `WindowsSecretInspectionTest`, `WindowsReadOnlyToolsTest`,
  `WindowsWriteToolsTest`, `WindowsProcessTreeTest`, `OsPrivateFileSystemAclTest`, `WindowsJobTest`,
  `WindowsSystemTest`, the Windows cases of `SystemProcessLauncherTest`); none skipped.

| Check | Expected | Test |
|---|---|---|
| injection matrix (contracts/windows-invocation.md) | refused or unchanged, never a second program | SC-002 |
| no credential in arguments or environment; allowlist only | 0 findings | SC-003 |
| timeout ends the tree | 0 processes alive 1 s after grace, 20 runs | SC-004 |
| recordings in code pages 850, 1252, 65001 | identical parse results | SC-005 |
| ACL of a file the substitute writes into the export directory | owner + SYSTEM, inherited | FR-007 |

## Recording on Windows (approval required, non-production node, read-only)

```powershell
$env:RECORD_ALLOWED_GROUPS = '<non-production group>'
$env:RECORD_ALLOWED_HOSTS = '<non-production host>'
powershell -ExecutionPolicy Bypass -File tools\record-fixtures.ps1 -Group <group> -Node <node> `
  -CliHome <cli.home> -JavaHome <cli.javaHome> -Case win_version,win_help,win_failure,win_argv
powershell -ExecutionPolicy Bypass -File tools\record-fixtures.ps1 -Group <group> -Node <node> `
  -CliHome <cli.home> -JavaHome <cli.javaHome> -TrustStore <tls.trustStore> `
  -CliUrl https://<node>:<port>/ibis/servlet/IBISSoapServlet -Owner <owner group> `
  -WorkRoot C:\inubit-record -Case win_modules,win_backslash
```

Cases: `win_version`, `win_help`, `win_modules`, `win_failure`, `win_argv` (argument forwarding for
the test values of the invocation contract), `win_backslash` (`\` inside a quoted command value),
later the 6.1 cases. `win_argv` needs a JDK (`-DumpJavaHome` if `-JavaHome` is a JRE). The
script asks for the username and the password (export cases only; host from `RECORD_ALLOWED_HOSTS`),
replaces local paths and the username, runs `tools/neutralize.py` with the local map
(`-NeutralizeMap`, `INUBIT_MCP_NEUTRALIZE_MAP` or `.neutralize-map`) in its private work directory
and writes to `src/test/resources/fixtures/windows/`; `-List` prints the cases. Then
`NoCustomerIdentifiersTest`.

## Live check on Windows (approval required)

1. `--check-config`: node line with `cli: available` and client version; no Windows warning.
2. Read-only: `list_inventory` with modules (non-ASCII names), `get_inventory_item` history,
   `export_artifacts` of a test diagram group; no temporary export data afterwards.
3. Record the unknowns: password read from stdin (yes/no), output encoding, `C:/` paths in the
   command, `\` inside a quoted value (`win_backslash`), argument forwarding (`win_argv`), the
   length `startcli.bat` adds (margin of R-4).
4. The opt-in JUnit live tests on Windows, read-only first (`InventoryLiveTest`,
   `ArtifactExportLiveTest`, `CredentialStoreLiveTest`; `docs/live-tests.md`).
5. Only on a development node, with approval, also `DevelopmentLiveTest` (and
   `DeploymentLiveTest` on an approved target): `import_artifacts` preview and execution,
   `restore_backup`, `set_active`, `tag_artifacts`; check the ACL of the backup (`icacls`).
6. Record per step: Windows version, client version, code page, outcome kind (no customer data),
   in this file under "Validation record".

## Validation record

Offline (T033, commit cd27b2a):

- macOS 27.2, JDK 21: `mvn -o clean verify` green, 4376 tests, 0 failures, 0 errors,
  51 skipped; `NoCustomerIdentifiersTest` 20 tests, 0 skipped.
- Windows CI job (`windows-latest`, no longer `continue-on-error`): run 42249980293
  (job 128002180359) green, 4555 tests, 0 failures, 0 errors, 55 skipped. The Windows-only tests
  ran without skips (run 42243345056 for 00170ea, same counts for cd27b2a):
  `WindowsCommandLineInjectionTest` 133, `WindowsSecretInspectionTest` 3,
  `WindowsReadOnlyToolsTest` 3, `WindowsWriteToolsTest` 10, `WindowsProcessTreeTest` 40,
  `OsPrivateFileSystemAclTest` 8, `WindowsJobTest` 6, `WindowsSystemTest` 11;
  `SystemProcessLauncherTest` 27 (the 5 skipped are POSIX-only cases with a Windows equivalent).
- Table: SC-002, SC-003, SC-004 and FR-007 green through the tests above; SC-005 (recordings in
  code pages 850, 1252, 65001) follows with T031/T032.

Live check on Windows (T031, 2026-11-16), by the Windows user with approval per step, against a
development node (INUBIT 8.1); no production node was contacted:

- Machine: Windows 11 Pro 25H2 (10.0.26200), ARM64, German; console code page 65001, OEM code
  page 850, ANSI code page 1252. Server: release 0.10.0 and the fix below, JDK 23. Clients:
  Workbench 8.1.17 and 9.0.11, each with its bundled Temurin runtime (17.0.18 and 21.0.12, x64).
- Step 1, `--check-config`: `cli: available` on every node, both clients detected
  (`CLI 8.1.17 (4.0.1–8.1.17)`, `CLI 9.0.11 (4.0.1–9.0.11)`), no Windows warning.
- **Password from stdin: no for 8.1.17 (FR-003 stop), yes for 9.0.11.** With 0.10.0 every
  StartCLI call on the 8.1 node ended in `TIMEOUT` with the password prompt as last output, with
  `\n` and with `\r\n`. Cause: client 8.1.17 reads the password through jline 2, whose default
  Windows terminal reads the console and ignores redirected stdin (research R-6). Fix: the
  Windows `JAVA_TOOL_OPTIONS` add `-Djline.terminal=jline.UnsupportedTerminal`
  (`StartCliEnvironment`). Against an unreachable address with a dummy password, 8.1.17 then
  reads stdin and does not echo it; 9.0.11 is unchanged. With the fix, on the development node:
  `list_inventory` of modules, `export_artifacts` of one module (paths with spaces, `C:/` paths
  in the command), `InventoryLiveTest` and `ArtifactExportLiveTest` (one diagram group) pass;
  afterwards no StartCLI process and no `inubit-mcp-export-*` directory.
- Step 5, `DevelopmentLiveTest` on the development node with a disposable test diagram group and
  workflow: `importRestoreActivateAndTagOnATestDiagramGroup` passes (import preview and
  execution, `restore_backup`, `set_active`, `tag_artifacts`); the repository case is skipped
  (no `INUBIT_LIVE_DEV_REPOSITORY_FOLDER`). Each backup file (ZIP and JSON) has the account of
  the server and SYSTEM only, not inherited (FR-007).
- Output encoding: UTF-8 through `JAVA_TOOL_OPTIONS`, decoded without error. With the jline
  setting, client 8.1.17 prints `null` lines after the password prompt; the classification is not
  affected (the live tests above pass).
- Argument forwarding and the R-4 margin: `startcli.bat` of both clients passes `%*` unchanged
  and starts `"%JAVA_HOME%\bin\java.exe"` after calling `java_home_path_setter.cmd`, which sets
  `JAVA_HOME` itself. So `win_argv` cannot put its recording runtime in place and is not recorded
  (the script now writes the reason); `cli.javaHome` has no effect for these clients, which
  `--check-config` now names when the setter points elsewhere. Indirectly, client 9.0.11 named
  each of the twelve contract test values unchanged as one argument ("Keystore file not found or
  is not a valid file: <value>"). The length `startcli.bat` adds stays the default margin.
- `\` inside a quoted value (`win_backslash`) and module names with umlauts (`win_modules`): not
  recorded (the cases ask for credentials interactively).
- Recordings: `win_version`, `win_help`, `win_failure`, `win_argv` of client 8.1.17 under
  `src/test/resources/fixtures/windows/` (no credentials, neutralised, `NoCustomerIdentifiersTest`
  20 tests, 0 skipped).
- Offline build on this machine: green except for tests that depended on an English Windows
  (`MainTest`, group names) and on x64 (`SystemProcessLauncherTest`, `PROCESSOR_ARCHITECTURE`,
  which Windows on ARM64 sets in every new process), both adjusted;
  `DevelopmentAuditTest.aLongChangeSetIsCutInTheAudit` exceeded the 60 s class limit on this VM
  and now has 180 s.
- Not run: `CredentialStoreLiveTest` (no test entries), `DeploymentLiveTest` (no deploy target
  approved), the 6.1 session (T028).

Open: `win_modules` and `win_backslash` recordings, `CredentialStoreLiveTest` and
`DeploymentLiveTest` on Windows (T031); the Windows recordings in the offline suite (T032); the
6.1 session on Windows (T028); T033 is completed with the SC-005 row after T032.
