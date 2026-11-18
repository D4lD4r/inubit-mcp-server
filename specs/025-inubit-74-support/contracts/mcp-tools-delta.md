# Contract: MCP Tools Delta (Mandate A)

No tool is added or removed; no input or output schema changes shape. Changes are values and texts.

## Refusal on a 7.4 node (every tool that contacts a node)

```json
{
  "code": "UNSUPPORTED_VERSION",
  "node": "legacy74/node1",
  "message": "get_health: INUBIT 7.4 on legacy74/node1: monitoring is not yet verified for INUBIT 7.4; nothing was sent",
  "likelyCause": "This server declares monitoring unsupported for that version",
  "nextStep": "INUBIT 7.4 is recognised, but no tool is verified on it yet; use the INUBIT Workbench for monitoring on legacy74/node1"
}
```

- Given before any REST request, StartCLI run (including local client probes) or TLS handshake.
- Per node in calls that address several nodes.
- Write tools: the existing order of the write checks and the version check (as for 6.1).
- For a concrete pin the name is the pin, e.g. `INUBIT 7.4.0.90 on legacy74/node1: …`.
- Inventory (and therefore the `check_artifacts` notice) appends `; modules of INUBIT 7.4 cannot be
  looked up` to the message.

## `list_nodes` entry of a 7.4 node

| Field | Value |
|---|---|
| `versionLine` | `V7_4` or the concrete pin as configured (e.g. `7.4.0.90`) |
| `version` | `7.4` for `V7_4`, the full pin text for a concrete pin |
| `access` | `cli_only` |
| capabilities | none usable (every capability refused) |
| client | the client directory if configured, the `-v` result and the client vs pin comparison with four parts (a 7.4.0.80 client at a 7.4.0.90 pin is a mismatch); no client options (no `-h` probe while 7.4 is not verified) |
| `warnings` | the entry `INUBIT 7.4 is recognised, but no tool is verified on it yet; the StartCLI client of <node> is not used until a 7.4 capability is verified` (existing free string array; no schema change) |

## Unknown-version warning after a detection answered with HTTP 404

Before:

```text
the INUBIT version of dev/node1 is unknown (<CODE> at <time>); if dev/node1 runs INUBIT 6.1, pin V6_1
```

After (deliberate change, SC-005):

```text
the INUBIT version of dev/node1 is unknown (<CODE> at <time>); if dev/node1 runs INUBIT 6.1, pin V6_1; if it runs INUBIT 7.4, pin V7_4
```

Every other detection failure: unchanged.

## `check_artifacts` with module lookup on a 7.4 node

Offline checks run; the result carries the notice that modules of INUBIT 7.4 cannot be looked up;
no contact.

## Tool announcement

A tool whose only candidate nodes are 7.4 nodes is not announced (feature 009 rule, unchanged).
