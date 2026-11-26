# Contract: MCP Tools Delta (Mandate A, Mandate B)

No tool is added or removed; no input or output schema changes shape. Changes are values and texts.
Mandate B (replanned 2026-11-26, spec FR-030) changes `get_health` and `find_processes` on a pinned
7.4 node; see "Mandate B (2026-11-26)" at the end. Every other tool keeps the Mandate A refusal.

## Refusal on a 7.4 node (every tool that contacts a node)

```json
{
  "code": "UNSUPPORTED_VERSION",
  "node": "legacy74/node1",
  "message": "INUBIT 7.4 on legacy74/node1: monitoring is not yet verified for INUBIT 7.4; nothing was sent",
  "likelyCause": "This server declares monitoring unsupported for that version",
  "nextStep": "INUBIT 7.4 is recognised, but no tool is verified on it yet; use the INUBIT Workbench for monitoring on legacy74/node1"
}
```

- Given before any REST request, StartCLI run (including local client probes) or TLS handshake.
- Per node in calls that address several nodes.
- Write tools: the existing order of the write checks and the version check (as for 6.1).
- For a concrete pin the name is the pin, e.g. `INUBIT 7.4.0.90 on legacy74/node1: …`.
- The example is the per-node `get_health` refusal, which names no tool, as on 6.1. Every other
  tool's message starts with `<tool>: `, e.g. `find_processes: INUBIT 7.4 on legacy74/node1:
  process diagnosis is not yet verified for INUBIT 7.4; nothing was sent` (IMC-14, option 1).
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
| `warnings` | the entry `INUBIT 7.4 is recognised, but no tool is verified on it yet; the StartCLI client of <node> is not used until a 7.4 capability is verified` (existing free string array; no schema change); removed by Mandate B, without replacement |

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
After Mandate B a pinned 7.4 node is a candidate for `find_processes` under the same rule.

## Mandate B (2026-11-26): `get_health` and `find_processes` on a pinned 7.4 node

Only after the flip (tasks T035), only for nodes pinned to 7.4; a node on `AUTO` is never treated
as 7.4 and keeps the unknown-version behaviour and the 404 hint above.

| Tool | Behaviour |
|---|---|
| `get_health` | StartCLI session as on 6.1; `reachable`, `status`, maintenance, JVM memory, process counts; readiness, threads, blocking queue unavailable with `NOT_AVAILABLE_ON_7_4: INUBIT 7.4 has no readiness check` (and the like); no REST request |
| `find_processes` | StartCLI `ps` listings as on 6.1, the 6.1 result fields, hanging and time range on the `DATE` column; `tag` refused: `find_processes: INUBIT 7.4 does not support the input tag; nothing was sent` |
| both | every per-node entry after the version gate carries the warning below: data entries and errors that arose after contact began (`UNEXPECTED_RESPONSE`, `AUTH_FAILED` from a rejected login, `UNREACHABLE`, `TLS_ERROR` from the handshake, `TIMEOUT` of the session or of the call's deadline); pre-contact refusals (`CLI_UNAVAILABLE`, missing credentials, the Windows credential refusal, refused TLS options, the limiter's no-free-slot `TIMEOUT`, the launch guard, the credential cooldown, StartCLI cannot be started, `tag`) do not; the call's deadline `TIMEOUT` of an admitted node carries it, also while the node still waits for a slot; in a group only the 7.4 node's entry carries it |
| `find_processes` version note | every admitted 7.4 entry (data or contacted error) also carries the existing note `logs of INUBIT 7.4 cannot be read with query_logs; read them in the Workbench` (feature 012, FR-010) |
| failed entry shape | `{ "node": …, "error": {…}, "warnings": [ … ] }`: a failed node may now carry `warnings` beside `error` (the schema already has both; its description changes from "a failed {node} has only an error") |
| `get_health` client | for 7.4 the client is probed before the session; a client whose `-v` does not report 7.4, or cannot be read, refuses `CLI_UNAVAILABLE` naming the client and what it reported, without the warning; `list_nodes` shows that reason for monitoring and process diagnosis |
| every other tool | the Mandate A refusal above, text and next step unchanged |

Warning in every result (existing text of feature 009, unchanged):

```text
INUBIT 7.4 on legacy74/node1 is not verified for monitoring; results may be incomplete
INUBIT 7.4.0.90 on legacy74/node1 is not verified for process diagnosis; results may be incomplete
```

Known limitation (unverified level, accepted by the owner 2026-11-26): the version `uname` reports
is the StartCLI client's, as on 6.1; `get_health` adds `the version <text> read on <node> is the
StartCLI client's; the server version cannot be read`, observes no server version, and a 7.4 pin is
never contradicted from 7.4.

`list_nodes` after Mandate B: `status: partially_supported` (as for any node with usable
unverified reads), monitoring and process diagnosis usable without suffix, every other capability
not usable with its Mandate A text; client options shown (the `-h` probe runs); no extra warning.

## Mandate B (2026-11-26, second round): `get_health` and `find_processes` on a pinned 6.1 node

The same as for 7.4 above, with the existing 6.1 reads of feature 012 (`NOT_AVAILABLE_ON_6_1`, the
6.1 texts, the version-less `defaults.cliHome`) and the warning `INUBIT 6.1 on legacy/node1 is not
verified for monitoring; results may be incomplete`, and on admitted `find_processes` entries the note
`logs of INUBIT 6.1 cannot be read with query_logs; read them in the Workbench`. Every other tool
keeps the feature 012 refusal. `list_nodes`: `partially_supported`, monitoring and process diagnosis usable;
`find_processes` is announced for a 6.1-only profile.
