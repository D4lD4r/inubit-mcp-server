# Contract: StartCLI invocation on Windows

The process the server starts for every StartCLI call on Windows (research R-1 to R-6). macOS and
Linux are unchanged.

## Process

```text
program:      <SystemRoot>\System32\cmd.exe            (absolute; SystemRoot validated, else C:\Windows)
command line: "<program>" /d /v:off /s /c ""<cli.home>\bin\startcli.bat" "<arg1>" … "<argN>""
working dir:  <cli.home>                                (never UNC)
stdin:        <password> "\n" (UTF-8), then closed      (8.1 --execCommand runs)
              <password> "\n" <session commands> "\n"…  (6.1 sessions, feature 012)
environment:  allowlist of research R-5 only
job:          the server's kill-on-close job (R-7)
```

`<arg…>` are today's StartCLI arguments: `-u`, the username, the trust-store options, `--execCommand`
and its command, the StartCLI URL. Never `-p`.

## Argument rules

| Character in a value passed outside the command | Outcome |
|---|---|
| `"` `%` `!` `^` `&` `|` `<` `>`, control characters (tab, line breaks), non-ASCII | refused (configuration: node `CLI_UNAVAILABLE` + warning; username: `AUTH_FAILED` at read time) |
| space `'` `(` `)` `;` `,` `=` `\` `*` `?` | reaches StartCLI unchanged |
| trailing `\` | doubled by the builder, reaches StartCLI as one `\` |

Values inside `--execCommand` keep today's rule (`CliCommand.VALUE`: letters, digits, `_ . -`
space); file paths inside it follow the Windows path rule
`^[A-Za-z]:/[A-Za-z0-9_.\- /]{1,255}$` (data-model.md).

## Limits

- Command line ≤ 6 143 characters (8 191 minus a 2 048 margin for `startcli.bat`): longer →
  `INVALID_INPUT` (tool input) or `CLI_UNAVAILABLE` naming the setting (configuration), before start.
- Timeout: `cliTimeout` as today; then the tree is ended, forcibly after 2 s; at most one second
  later no process of the call is alive (SC-004).

## Test values for the argument-forwarding recording (FR-014)

`plain`, `with space`, `''`, `a'b`, `(x)`, `a;b`, `a,b`, `a=b`, `C:\dir\`, `C:/dir/x`, `*`, `a?b` —
each as the username and as a trust-store path; the recording lists what the client's Java main
receives.

## Output

stdout/stderr: UTF-8 (forced by `JAVA_TOOL_OPTIONS`), decoded strictly as UTF-8, else in the host's
native code page; CRLF normalised to LF before classification. A `TIMEOUT` whose last output line is
the client's password prompt says so (FR-005).
