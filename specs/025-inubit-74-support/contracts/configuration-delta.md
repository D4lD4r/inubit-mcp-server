# Contract: Configuration Delta (Mandate A, Mandate B)

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
| `--check-config` | runs the entry's local `-v` probe (no node contact) and reports the result, including "not evaluable", and the client vs pin comparison with four parts; runs no `-h` option probe and gives no client option warning while every 7.4 capability is not yet verified; no JDK major warning for 7.4 |
| `--check-config` | the client text line of a 7.4 node ends with `; not used until a 7.4 capability is verified` |
| `list_nodes` | the node's `warnings` list carries the entry of contracts/mcp-tools-delta.md (no schema change) |
| tools | never run the client against a 7.4 node (every tool is refused first) |

Mandate B (2026-11-26, after the flip T035; spec FR-029, FR-035):

| Where | Behaviour |
|---|---|
| client choice | node `cli.home` → group `cli.home` → `defaults.cliClients."7.4"` (with its `javaHome`); never the version-less `defaults.cliHome` |
| client check | the chosen client's `-v` must report 7.4, else `CLI_UNAVAILABLE` naming the client and its version |
| `--check-config` | runs the `-h` option probe for a 7.4 node as for 6.1; the suffix `; not used until a 7.4 capability is verified` is gone; still no JDK major warning for 7.4 |
| `list_nodes` | the Mandate A warning entry is gone, without replacement |
| tools | `get_health` and `find_processes` run the client (unverified); every other tool is refused first |

## `--check-config` warnings

| Node | Warning |
|---|---|
| pinned 6.1, no client | unchanged: `<id>: INUBIT 6.1 is reached through StartCLI only, but no cliHome is configured; its capabilities report CLI_UNAVAILABLE` |
| pinned 7.4, no client | none in Mandate A (no tool uses a client while every 7.4 capability is not yet verified); after Mandate B the CLI-only warning, naming `defaults.cliClients."7.4"` instead of `cliHome` (exact text fixed in T035) |

## `--check-config` summary line of a 7.4 node

```text
<id> … (INUBIT 7.4, StartCLI only; available: none; refused: monitoring, process diagnosis, …)
```

After Mandate B: `available: monitoring, process diagnosis` (unverified); the rest refused. The
same holds for a pinned 6.1 node (spec FR-036); its client rules (version-less `cliHome`) and its
`--check-config` warning are unchanged.

## Unchanged

`AUTO` stays REST-based and never selects 7.4 (detection withdrawn 2026-11-26); `cliClients`
selection by major.minor; `cli.home` precedence (feature 017 FR-003) — the 7.4 deviation of spec
FR-029 belongs to Mandate B (above).
