# Quickstart: validate StartCLI on Windows

## Offline (every platform)

```bash
mvn -q -o clean verify
```

- macOS/Linux: the suite is green with no test removed (SC-007); `NoCustomerIdentifiersTest` runs
  (20 tests, 0 skipped).
- Windows CI job (`windows-latest`): `mvn -B verify` green; the Windows-only tests run
  (`WindowsCommandLineInjectionTest`, `WindowsProcessTreeTest`, `OsPrivateFileSystemAclTest`,
  `WindowsJobTest`, the Windows cases of `SystemProcessLauncherTest`); none skipped.

| Check | Expected | Test |
|---|---|---|
| injection matrix (contracts/windows-invocation.md) | refused or unchanged, never a second program | SC-002 |
| no credential in arguments or environment; allowlist only | 0 findings | SC-003 |
| timeout ends the tree | 0 processes alive 1 s after grace, 20 runs | SC-004 |
| recordings in code pages 850, 1252, 65001 | identical parse results | SC-005 |
| ACL of a file the substitute writes into the export directory | owner + SYSTEM, inherited | FR-007 |

## Recording on Windows (approval required, non-production node, read-only)

```powershell
powershell -ExecutionPolicy Bypass -File tools\record-fixtures.ps1 -Profile <name> -Node <group/node> -Case win_version
```

Cases: `win_version`, `win_help`, `win_modules`, `win_failure`, `win_argv` (argument forwarding for
the test values of the invocation contract), `win_backslash` (`\` inside a quoted command value),
later the 6.1 cases. Then `tools/neutralize.py` and
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

(empty)
