# Data Model: StartCLI-Backed Tools on Windows

No persistent data model changes. The entities below are in-memory values and file-system objects.

## WindowsCommandLine (adapter/cli)

| Field | Rule |
|---|---|
| `interpreter` | `<system dir>\cmd.exe`, system directory from `GetSystemDirectoryW` (research R-1) |
| `script` | absolute path `<cli.home>\bin\startcli.bat` |
| `arguments` | today's StartCLI arguments; each passes `WindowsArguments` (R-2) |
| `arguments to the JDK` | `[interpreter, /d, /v:off, /s, /c, ""<script>" "<arg>" …"]`; the last one starts and ends with `"` (R-1); trailing backslashes of an argument doubled |
| `length` | ≤ 6 143 characters (R-4), else `INVALID_INPUT` (tool input) or `CLI_UNAVAILABLE` (configuration) |

## WindowsArguments (config)

Refused characters (Windows only): `"`, `%`, `!`, `^`, `&`, `|`, `<`, `>`, control characters, any
character outside ASCII. Applied to: `cli.home` (also refused if UNC), `cli.javaHome`,
`tls.trustStore`, the StartCLI URL (at validation; node-level `CLI_UNAVAILABLE`), the username (at
read time for a StartCLI call; `AUTH_FAILED`, not a failed login). The password: non-ASCII refused
(FR-003 default).

## WindowsPathRule (config, `CliPaths`)

`^[A-Za-z]:/[A-Za-z0-9_.\- /]{1,256}$` (≤ 259 characters), no `.`/`..` segment, after `toRealPath()`
(of the parent for a file not yet written) and `\` → `/`; rendered by `CliCommand.Builder.path`.

## StartCliEnvironment (adapter/cli)

POSIX: unchanged. Windows: the allowlist of research R-5, case-insensitive names; `ComSpec` set to
the interpreter; `PATH` = `<JAVA_HOME>\bin;<system dir>`; `NoDefaultCurrentDirectoryInExePath=1`; `JAVA_TOOL_OPTIONS` = `-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8
-Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8`.

## ProcessContainment (adapter/cli port, adapter/os implementation)

| State | Meaning |
|---|---|
| `BOUND` | the server is in a kill-on-close job (Windows) or no job is needed (macOS, Linux) |
| `UNAVAILABLE(reason)` | Windows, the job could not be set up: StartCLI-backed capabilities report `CLI_UNAVAILABLE` with the reason; `--check-config` warns |

## PrivateFileSystem (domain.port; implementation adapter.os.OsPrivateFileSystem)

Operations: `createDirectory`, `createDirectories`, `createTempDirectory`, `createFile`,
`tightenOnPosix` (an existing object owner-only on POSIX, as today; nothing changed on Windows),
`check` (other principals on an existing object), `ownedByCurrentUser` (owner is the token user;
used by the sweep).


| Object | Created by | Windows access | Existing object |
|---|---|---|---|
| temporary export / import directory | `CliExportRunner`, `CliImportRunner` | token user + `SYSTEM`, protected DACL, inherited by children | — (always new) |
| backups | `BackupStore` | as above | — |
| packages | `PackageWriter` | as above | — |
| deployment lock and ledger | `DeployLock`, `DeploymentLedger` | as above | checked, warning |
| git hooks directory | `GitCli` | as above | — (always new) |
| workspace directory | `WorkspaceDirectory` (when it creates it) | as above | not checked |
| audit directory and log | `Launcher`, `AuditLog` | as above | default directory and log checked, warning |

Windows access = protected DACL applied right after creation, before anything is written (R-8).
Creation that cannot apply it deletes the object and fails with the caller's existing error.
POSIX: today's permissions and fallbacks, unchanged.

## DecodedOutput (adapter/cli)

Windows: per line, strict UTF-8, else the console OEM code page (`GetOEMCP`); CRLF → LF before
`CliOutputClassifier`. POSIX: unchanged (`toString(UTF_8)`).
