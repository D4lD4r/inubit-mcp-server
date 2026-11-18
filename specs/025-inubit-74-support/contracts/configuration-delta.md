# Contract: Configuration Delta (Mandate A)

## `versionLine`

Allowed values: `AUTO`, `V6_1`, **`V7_4`**, `V8_1`, `V9_0`, `V9_X`, or a concrete version with at
least major.minor.patch (a fourth numeric part and a suffix allowed, e.g. `"7.4.0.90"`,
`"7.4.0.90-HF1"`).

Load error for any other value (changed text):

```text
Invalid versionLine "<value>" at <path> in <file>: expected AUTO, V6_1, V7_4, V8_1, V9_0, V9_X or a full version such as "8.1.17"
```

## `defaults.cliClients."7.4"`

No syntax change (feature 017: any quoted `"<major>.<minor>"`). Behaviour for a 7.4 node in
Mandate A:

| Where | Behaviour |
|---|---|
| load / validation | key and directories checked as for every entry |
| `--check-config` | runs the entry's local `-v` probe (no node contact) and reports the result, including "not evaluable"; no JDK major warning for 7.4 |
| `--check-config`, `list_nodes` | the client line of a 7.4 node carries the note `not used until a 7.4 capability is verified` |
| tools | never run the client against a 7.4 node (every tool is refused first) |

## `--check-config` warnings

| Node | Warning |
|---|---|
| pinned 6.1, no client | unchanged: `<id>: INUBIT 6.1 is reached through StartCLI only, but no cliHome is configured; its capabilities report CLI_UNAVAILABLE` |
| pinned 7.4, no client | none in Mandate A (no tool uses a client while every 7.4 capability is not yet verified) |

## `--check-config` summary line of a 7.4 node

```text
<id> … (INUBIT 7.4, StartCLI only; available: none; refused: monitoring, process diagnosis, …)
```

## Unchanged

`AUTO` stays REST-based; `cliClients` selection by major.minor; `cli.home` precedence (feature
017 FR-003) — the 7.4 deviation of spec FR-029 belongs to Mandate B.
