# Contract: configuration and `--check-config` on Windows

No new configuration keys. Behaviour changes on Windows only.

## Removed

- Warning `<id>: CLI tools are not supported on Windows in this version; CLI-backed capabilities
  report CLI_UNAVAILABLE (REST tools work)`.
- `CLI_UNAVAILABLE` "CLI tools are not supported on Windows in this version".
- The skipped `java.io.tmpdir` check and the skipped client probe on Windows.

## Node-level checks on Windows (warnings; the node's StartCLI-backed capabilities report `CLI_UNAVAILABLE` naming the setting)

| Condition | Warning |
|---|---|
| `bin\startcli.bat` missing | `<id>: <cli.home>\bin\startcli.bat not found; CLI-backed capabilities report CLI_UNAVAILABLE` |
| refused character in `cli.home`, the effective Java home (`cli.javaHome`, else the server's `JAVA_HOME`; also `;`), `tls.trustStore` or the StartCLI URL | `<id>: <setting> contains a character the Windows command interpreter interprets (<class>); CLI-backed capabilities report CLI_UNAVAILABLE` |
| `cli.home` on a UNC path | `<id>: cli.home is a network path (\\…); StartCLI needs a local drive path on Windows` |
| configured values exceed the command-line limit | `<id>: the StartCLI command line would exceed 6143 characters (<setting>); CLI-backed capabilities report CLI_UNAVAILABLE` |
| system directory unreadable (`GetSystemDirectoryW`) | `StartCLI cannot be started: the Windows system directory could not be read (<reason>); CLI-backed capabilities report CLI_UNAVAILABLE` |
| process job unavailable | `StartCLI processes could not be bound to the server (<reason>); CLI-backed capabilities report CLI_UNAVAILABLE` |

## Profile-level checks on Windows

| Condition | Outcome |
|---|---|
| `java.io.tmpdir` breaks the Windows path rule | warning with the remedy `-Djava.io.tmpdir=<path>` (as on macOS) |
| default audit directory, audit log, deployment ledger or lock grants access to another principal | warning naming the object and the `icacls` command that restricts it |

## Credential entries (`secrets:` part)

On nodes with a StartCLI installation on Windows, a username with a refused character is shown as
`invalid value (Windows: <class>)` and is an error (exit code 1), as other invalid values.

## Node line

Unchanged format; on Windows `cli: available` and the client part appear as on macOS.
