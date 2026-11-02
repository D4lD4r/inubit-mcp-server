# Data Model: StartCLI-Backed Tools on Windows

No persistent data model changes. The entities below are in-memory values and file-system objects.

## WindowsCommandLine (adapter/cli)

| Field | Rule |
|---|---|
| `interpreter` | absolute path `<SystemRoot>\System32\cmd.exe` (research R-1) |
| `script` | absolute path `<cli.home>\bin\startcli.bat` |
| `arguments` | today's StartCLI arguments; each passes `WindowsArguments` (R-2) |
| `line` | `/d /v:off /s /c ""<script>" "<arg>" …"`; trailing backslashes of an argument doubled |
| `length` | ≤ 6 143 characters (R-4), else `INVALID_INPUT` (tool input) or `CLI_UNAVAILABLE` (configuration) |

## WindowsArguments (config)

Refused characters (Windows only): `"`, `%`, `!`, `^`, `&`, `|`, `<`, `>`, control characters, any
character outside ASCII. Applied to: `cli.home` (also refused if UNC), `cli.javaHome`,
`tls.trustStore`, the StartCLI URL (at validation; node-level `CLI_UNAVAILABLE`), the username (at
read time for a StartCLI call; `AUTH_FAILED`, not a failed login). The password: non-ASCII refused
(FR-003 default).

## WindowsPathRule (config, `CliPaths`)

`^[A-Za-z]:/[A-Za-z0-9_.\- /]{1,255}$`, no `.`/`..` segment, after `toRealPath()` and `\` → `/`.

## StartCliEnvironment (adapter/cli)

POSIX: unchanged. Windows: the allowlist of research R-5, case-insensitive names; `ComSpec` set to
the interpreter; `JAVA_TOOL_OPTIONS` = `-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8
-Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8`.

## ProcessContainment (adapter/cli port, adapter/os implementation)

| State | Meaning |
|---|---|
| `BOUND` | the server is in a kill-on-close job (Windows) or no job is needed (macOS, Linux) |
| `UNAVAILABLE(reason)` | Windows, the job could not be set up: StartCLI-backed capabilities report `CLI_UNAVAILABLE` with the reason; `--check-config` warns |

## PrivateFiles (infra)

| Object | Created by | Windows access | Existing object |
|---|---|---|---|
| temporary export / import directory | `CliExportRunner`, `CliImportRunner` | owner + `SYSTEM`, inherited by children | — (always new) |
| backups | `BackupStore` | as above | — |
| packages | `PackageWriter` | as above | — |
| deployment lock and ledger | `DeployLock`, `DeploymentLedger` | as above | checked, warning |
| git hooks directory | `GitCli` | as above | — (always new) |
| workspace directory | `WorkspaceDirectory` (when it creates it) | as above | not checked |
| audit directory and log | `Launcher`, `AuditLog` | as above | default directory and log checked, warning |

Creation that cannot apply the access fails with the caller's existing error.

## DecodedOutput (adapter/cli)

StartCLI stdout/stderr bytes → text: strict UTF-8, else the host's `native.encoding`; CRLF → LF
before `CliOutputClassifier`.
