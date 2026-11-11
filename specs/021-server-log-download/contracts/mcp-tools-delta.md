# MCP Tools Delta: Download Server Log Files

Delta to [docs/tools.md](../../../docs/tools.md). One tool is added: `download_server_log`. No
existing tool changes its inputs or results; `list_nodes` gains the capability names
`trace_log_read` (nodes with a StartCLI client) and `webservice_log_read` (only nodes of groups
with `unsupported.serverLogRead`).

## Announcement (FR-002, SC-002)

`download_server_log` is in `tools/list` iff at least one node

1. has an INUBIT client home configured for one of its possible versions (`cli.home`,
   `defaults.cliClients`, `defaults.cliHome`; installed or not, as for the exports), and
2. is not pinned to a version or line on which `trace_log_read` is unsupported (6.1, a line other
   than 8.1/9.0, a version still "not yet verified"), **or** its group has
   `unsupported.serverLogRead: true` and it is not pinned to a version on which
   `webservice_log_read` is unsupported.

The tool changes nothing on INUBIT: it is offered in read-only mode and is independent of
`write.*`, `archives.*` and the production write opt-ins.

Annotations: `ToolHints.localWrite("Download an INUBIT server log")` → `readOnlyHint: false`,
`destructiveHint: false`, `idempotentHint: false`, `openWorldHint: true` (FR-014: it writes a local
file with clear-text content, so clients ask first).

## Description (shown to clients)

```text
Downloads the trace log (trace.log) or the web service log (webservice.log) of one INUBIT {node} into a local folder you name and returns the newest lines, or the lines containing a search text, as a masked excerpt with line numbers. Use it when a process or connector failed and find_processes, query_logs or read_queue_entry do not show the cause (trace), or to see the SOAP messages a web service connector really sent or received (webservice). The file is written IN CLEAR TEXT: both logs may contain business data and credentials; only the excerpt is masked. trace uses StartCLI's supported trace download. webservice is UNSUPPORTED: it uses an undocumented internal INUBIT interface (the Workbench's Cluster Manager log download) without vendor support or stability guarantee; it may stop working after any INUBIT update; available only for groups with unsupported.serverLogRead. The folder must be an absolute path outside the workspace, the server's directories, git working trees and network paths; existing files are never replaced. Changes nothing on INUBIT.
```

`ServerLogDocsTest` asserts the words "clear text", "business data and credentials", "supported
trace download", "unsupported", "undocumented", "without vendor support", "any INUBIT update" and
"never replaced".

## Input schema (`schemas/download_server_log.input.json`)

```json
{
  "type": "object",
  "required": ["target", "log", "targetFolder"],
  "properties": {
    "target": {
      "type": "string",
      "pattern": "^[a-z0-9][a-z0-9-]{0,31}/[a-z0-9][a-z0-9-]{0,31}$",
      "description": "Id of exactly one {node} (`<{group}>/<{node}>`); {group} ids are rejected"
    },
    "log": {
      "type": "string",
      "enum": ["trace", "webservice"],
      "description": "trace: trace.log (StartCLI trace download); webservice: webservice.log (unsupported, needs unsupported.serverLogRead)"
    },
    "targetFolder": {
      "type": "string",
      "minLength": 1,
      "maxLength": 4096,
      "description": "Absolute path of a local folder for the log file; created owner-only if missing; same folder rules as export_archives"
    },
    "tailLines": {
      "type": "integer",
      "minimum": 1,
      "maximum": 2000,
      "default": 200,
      "description": "Number of newest lines (or newest matching lines with contains) in the excerpt"
    },
    "contains": {
      "type": "string",
      "minLength": 1,
      "maxLength": 200,
      "pattern": "^[^\\u0000-\\u001F\\u007F-\\u009F]{1,200}$",
      "description": "Plain text (not a pattern), matched case-insensitively against the masked lines"
    },
    "maxChars": {
      "type": "integer",
      "minimum": 1,
      "maximum": 100000,
      "default": 20000,
      "description": "Maximum characters of the excerpt lines; older lines are dropped first"
    }
  },
  "additionalProperties": false
}
```

The service repeats every check (`INVALID_INPUT` naming the field, never echoing `contains`; a
group id gives the `TargetResolver.resolveSingleServer` text).

## Decision flow

Nothing is sent to INUBIT and nothing is created locally before steps 1–5 passed.

1. Input checks (as above).
2. `target` → one server (`TARGET_UNKNOWN`, group id `INVALID_INPUT`).
3. `log: webservice` without the group's opt-in → `NOT_CONFIGURED`.
4. Folder rules (as `export_archives`, `INVALID_INPUT`).
5. Audit `PENDING` (unwritable → `INTERNAL`); refusals of steps 1–4 are audited as `REFUSED`
   (schema failures never reach the server).
6. Version gate (`admitWithDetection`; `trace_log_read` / `webservice_log_read`): refusal
   `UNSUPPORTED_VERSION` (audited `REFUSED`), detection error passed through (audited `FAILED`).
7. Call deadline = now + `cliExportTimeout`; StartCLI slot + download into a private temporary
   directory within it.
8. Credentials of every configured node prepared (bounded by the remaining deadline; not all
   known → excerpt withheld); excerpt permit (within the remaining deadline, else `TIMEOUT`);
   excerpt from the private copy (masking, selection, bounds).
9. Placement in `targetFolder` under the claimed name; final audit record; one INFO log line.

## Output (`schemas/download_server_log.output.json`)

```json
{
  "node": "test/node1",
  "log": "webservice",
  "inubitNode": "node1",
  "file": "/Users/operator/inubit-logs/test-node1-webservice-20261111-081530.log",
  "sizeBytes": 734003,
  "downloadedAt": "2026-11-11T08:15:30Z",
  "excerpt": {
    "kind": "text",
    "charset": "UTF-8",
    "window": { "startLine": 1, "lines": 9120, "bytes": 734003, "wholeFile": true },
    "selection": "tail",
    "returnedLines": 200,
    "lines": [
      { "number": 8921, "text": "2026-11-11 08:15:29,114 INFO  [acme.ws.OrderService] request sent to https://partner.example.test/order" },
      { "number": 8922, "text": "<wsse:Password>${secret:data.Password}</wsse:Password>" }
    ],
    "truncated": false
  },
  "hints": [],
  "warnings": [
    "Read through an unsupported, undocumented INUBIT interface; diagnosis only",
    "The log file contains clear-text content"
  ]
}
```

Fields:

| Field | Rule |
|---|---|
| `node` | the server id |
| `log` | `trace` / `webservice` |
| `inubitNode` | only for `webservice`: the node name INUBIT reported (D-5); absent for `trace` (the configured server is named) |
| `file` | absolute path of the written file (`<group>-<node>-<log>-<yyyyMMdd-HHmmss>[-n].log`) |
| `sizeBytes` | size of the written file (= the download, FR-008) |
| `downloadedAt` | ISO-8601 UTC; the same instant as in the file name |
| `excerpt.window` | `startLine` (absolute), `lines`, `bytes`, `wholeFile` (false if the file is larger than 50 MiB) |
| `excerpt.selection` | `tail` or `contains`; with `contains` also `matchingLines` (all matches in the window) |
| `excerpt.lines` | oldest first; `number` absolute in the file; `text` masked; a cut line has `"cut": true` |
| `excerpt.truncated` | `false` or `{"droppedLines": n, "cutLines": 0 \| 1}` (FR-009 step 5) |
| `hints` | fixed texts (see variants) |
| `warnings` | the unsupported warning (webservice only), the clear-text warning, the cloud-sync warning of the folder rules, version and client warnings of the gate, an audit warning |

Variants:

| Case | Shape |
|---|---|
| empty log | `"excerpt": {"kind": "empty"}`, hint `The log is empty` (webservice: `; web service logging may be disabled`); for an adapter that reports presence `UNKNOWN`: `The log is empty or does not exist on the server` (webservice: `The log is empty or does not exist on the server; web service logging may be disabled`) |
| credentials of some configured node not read yet | `"excerpt": {"kind": "withheld"}`, hint `The excerpt is withheld: the credentials of prod/node2 (credential store: waiting) have not been read yet, so they could not be masked; the file is written`; the file is written as usual |
| binary window | `"excerpt": {"kind": "binary", "detected": "GZIP"}`, hint `Binary content is not returned` (`detected` only if a signature matched) |
| `contains` without match | `"selection": "contains", "matchingLines": 0, "returnedLines": 0, "lines": []`, hint `No line of the scanned window contains the search text` |
| file > 50 MiB | `"window": {"startLine": 412877, …, "wholeFile": false}`, hint `Only the last 50 MiB were scanned; line numbers count the whole file` |
| no line break in the last 50 MiB | `"excerpt": {"kind": "text", "charset": …, "window": {…, "lines": 0}, "lines": []}`, hint `No whole line in the last 50 MiB of the log` |

Bounds: `lines` ≤ `tailLines` (≤ 2,000); the sum of `text` lengths ≤ `maxChars` (≤ 100,000); all
counts on the masked text. Every string passes `SecretScrubber` (`ResultMapper`).

## Errors

All as `ToolError` (`code`, `message`, `likelyCause`, `nextStep`, `node`, `excerpt?`). On every
error no file of the call remains in `targetFolder`; a folder the call created stays (empty).

| Code | When | Message (example) | Next step |
|---|---|---|---|
| `INVALID_INPUT` | input check; group id; folder rule; no free name up to `-999`; file system without hard links and atomic rename | `Invalid tailLines 0 (1–2000)` / `targetFolder lies inside a git working tree: /work/repo/logs` / `The file system of the target folder supports neither hard links nor atomic renames` | fix the input / choose a local folder |
| `TARGET_UNKNOWN` | unknown id | existing `TargetResolver` text | use an id from `list_nodes` |
| `NOT_CONFIGURED` | `log: webservice`, group without the opt-in (nothing sent, no detection) | `download_server_log for the web service log is not enabled for {group} test: unsupported.serverLogRead is not set` | `Set unsupported.serverLogRead: true on {group} test and restart the MCP client (unsupported INUBIT functionality, see docs/setup.md "Unsupported functions")` |
| `UNSUPPORTED_VERSION` | 6.1, other line, undeclared version; a version/log still "not yet verified" | `download_server_log: Downloading the web service log refused: INUBIT 6.1.3 on legacy/node1 …; supported: 8.1, 9.0` / `… is not yet verified for INUBIT 9.0` | gate texts (`VersionGate`) |
| detection error's own code | version of an `AUTO` node not detectable (US3-3) | the detection's message / `The INUBIT version of test/node1 could not be detected (UNREACHABLE at 08:15:30Z); download_server_log needs it` | `Fix the cause and retry in a minute, or set versionLine of test/node1` |
| `NOT_FOUND` | the log file does not exist on the server (D-8) | `trace.log does not exist on test/node1` / `test/node1 has no web service log (webservice.log on INUBIT node node1)` | trace: `Check the trace settings of the server in the Workbench`; webservice: `Enable web service logging in the server configuration, then retry` |
| `CLI_UNAVAILABLE` | no client home; `lib/ibis.jar` or JDK missing; StartCLI or helper could not start; temp root not passable; vendor class/method missing; no private temp directory | 017/020 texts, e.g. `The INUBIT client installation /opt/inubit-9.0/client (chosen for INUBIT 9.0) lacks com.inubit.ibis.server.cluster.ClusterClient` | `Check that defaults.cliClients."9.0" points to an INUBIT 9.0 client` |
| `AUTH_FAILED` | login rejected (shared `CredentialGuard` back-off) | StartCLI text / `Login of the INUBIT client helper to test/node1 was rejected` | as StartCLI; on 9.x plus the internal-user-management hint |
| `FORBIDDEN` | the user lacks the right (StartCLI login right; the Cluster Manager's `Configuration` right for the web service log) | `test/node1 refused the web service log download for the configured user` | `Give the user the right the Workbench's Cluster Manager needs` |
| `TLS_ERROR` / `UNREACHABLE` / `TIMEOUT` | TLS check, connection; `cliExportTimeout` exceeded (slot + check + process + excerpt permit wait) | existing texts; `The trace log download from test/node1 did not finish within cliExportTimeout (120 s)` | existing texts naming `tls.*` / `cliExportTimeout`, plus `large logs need a larger cliExportTimeout` |
| `SECRET_UNRESOLVED` (or the node's other credential codes) | credentials not available | the node's usual credential error; nothing launched | as for the node's other StartCLI tools |
| `MAINTENANCE_MODE` | the server reports maintenance (detection or StartCLI) | existing texts | — |
| `UNEXPECTED_RESPONSE` | StartCLI without the `Trace data (` message or without the file; helper output without `end`, unknown frame, over 1 MiB, exit code ≠ frames, invalid node name, `Server not in cluster mode.`, any other INUBIT error of the internal download | `download_server_log got an unexpected answer from test/node1; the unsupported INUBIT interface may have changed after an INUBIT update` (trace: `… StartCLI did not confirm the trace download`) | `Download the log in the Workbench; report the INUBIT version` |
| `INTERNAL` | helper resource missing; audit `PENDING` not writable; shutting down | `The INUBIT client helper is missing from this build` / audit texts of `export_archives` | — |

## Capability table and `list_nodes` (docs/tools.md)

| Tool | Capability | Kind | Channel | 8.1 | 9.0 | 6.1 / other / unknown |
|---|---|---|---|---|---|---|
| `download_server_log` `log: trace` | `trace_log_read` | read on INUBIT, writes a local file | StartCLI | supported (after the 8.1 recording) | supported (after the 9.0 recording; else refused "not yet verified") | refused |
| `download_server_log` `log: webservice` | `webservice_log_read` | read (unsupported vendor interface), writes a local file | helper on the INUBIT client | supported (after the 8.1 recording) | supported (after the 9.0 recording; else refused "not yet verified") | refused |

`list_nodes`: `trace_log_read` appears in `usable`/`notUsable` for nodes with a client home (as
`workspace_export`); `webservice_log_read` only for nodes of opted-in groups with a client home
(`list_nodes.output.json` enum `+ "trace_log_read", "webservice_log_read"`).
