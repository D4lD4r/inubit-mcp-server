# Contract delta: configuration and command line (feature 016)

## Profile key

```yaml
updateCheck:
  enabled: false   # strict YAML boolean; absent = true
```

- Top-level only. `updateCheck` with any other key is "Unknown key 'x' at updateCheck".
- A non-boolean `enabled` (for example `"false"`, `0`) is an error:
  `Invalid value at updateCheck.enabled in <file>: expected true or false`.

## Environment variable

`INUBIT_MCP_UPDATE_CHECK`: `off|false|0|no` turns the check off, `on|true|1|yes` leaves it on
(case-insensitive, surrounding blanks ignored, empty = unset). Any other value: startup warning
`INUBIT_MCP_UPDATE_CHECK has an unrecognised value; use off or on (the update check stays on)`.

The check runs only if neither the key nor the variable turns it off.

## Command line

- `--version` and `--check-config` never contact the release source (FR-013).
- `--version` prints `inubit-mcp-server <version>` with the version from the JAR manifest
  (`Implementation-Version`), falling back to the build resource when not run from a JAR.

## JAR manifest

```
Implementation-Title: inubit-mcp-server
Implementation-Version: <project version>
```

## Log lines (stderr)

- WARN, once per newer version and process:
  `A newer inubit-mcp-server is available: 0.8.0 (running 0.7.1): https://github.com/D4lD4r/inubit-mcp-server/releases/tag/v0.8.0 – update manually (no automatic download)`
- INFO at start when off: `Update check is off (<updateCheck.enabled: false | INUBIT_MCP_UPDATE_CHECK>)`
- DEBUG on failure: `Update check failed: <reason>`; next attempt at the next daily check.
