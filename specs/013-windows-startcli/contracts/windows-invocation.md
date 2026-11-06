# Contract: StartCLI invocation on Windows

The process the server starts for every StartCLI call on Windows (research R-1 to R-6). macOS and
Linux are unchanged.

## Process

```text
program:      <system dir>\cmd.exe                     (GetSystemDirectoryW; unreadable → CLI_UNAVAILABLE)
argument list: [program, /d, /v:off, /s, /c, ""<cli.home>\bin\startcli.bat" "<arg1>" … "<argN>""]
              (the last element starts and ends with ", so the JDK passes it unchanged; pinned by a Windows test)
working dir:  <cli.home>                                (never UNC)
stdin:        <password> "\n" (UTF-8), then closed      (8.1 --execCommand runs)
              <password> "\n" <session commands> "\n"…  (6.1 sessions, feature 012)
environment:  allowlist of research R-5 only; PATH = <JAVA_HOME>\bin;<system dir>;
              NoDefaultCurrentDirectoryInExePath=1
job:          the server's kill-on-close job (R-7)
```

`<arg…>` are today's StartCLI arguments: `-u`, the username, the trust-store options, `--execCommand`
and its command, the StartCLI URL. Never `-p`.

## Argument rules

| Character in a value passed outside the command | Outcome |
|---|---|
| `"` `%` `!` `^` `&` `|` `<` `>`, control characters (tab, line breaks), non-ASCII; in the Java home also `;` | refused (configuration: node `CLI_UNAVAILABLE` + warning; username: `AUTH_FAILED` at read time) |
| space `'` `(` `)` `;` `,` `=` `\` `*` `?` | reaches StartCLI unchanged |
| trailing `\` | doubled by the builder, reaches StartCLI as one `\` |

Values inside `--execCommand` keep today's rule (`CliCommand.VALUE`: letters, digits, `_ . -`
space); file paths inside it follow the Windows path rule
`^[A-Za-z]:/[A-Za-z0-9_.\- /]{1,256}$` (data-model.md).

## Limits

- Inner line ≤ 6 143 characters (8 191 minus a 2 048 margin for `startcli.bat`, a default revisited
  in T031): longer →
  `INVALID_INPUT` (tool input) or `CLI_UNAVAILABLE` naming the setting (configuration), before start.
- Timeout: `cliTimeout` as today; then the tree is ended, forcibly after 2 s; at most one second
  later no process of the call is alive (SC-004).

## Test values for the argument-forwarding recording (FR-014)

`plain`, `with space`, `''`, `a'b`, `(x)`, `a;b`, `a,b`, `a=b`, `C:\dir\`, `C:/dir/x`, `*`, `a?b` —
each as the username and as a trust-store path; the recording lists what the client's Java main
receives (`win_argv`). A second recording (`win_backslash`) runs a read-only command with a
`C:\…` path inside a quoted `--execCommand` value and shows how StartCLI's parser treats `\`.

## Output

stdout/stderr: UTF-8 (forced by `JAVA_TOOL_OPTIONS`), decoded per line strictly as UTF-8, else in the
console OEM code page; CRLF normalised to LF before classification (Windows only; POSIX unchanged).
On Windows a `TIMEOUT` whose last output line is the client's password prompt names both
possibilities (waiting for the password, or a slow server) and keeps the `cliTimeout` next step
(FR-005).
