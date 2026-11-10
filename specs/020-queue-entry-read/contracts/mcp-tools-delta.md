# MCP Tools Delta: Read Queue Manager Entries

Delta to [docs/tools.md](../../../docs/tools.md). One tool is added: `read_queue_entry`. No
existing tool changes its inputs or results; `list_nodes` gains the capability name
`queue_entry_read`, which appears only for nodes of opted-in groups.

## Announcement (FR-002, SC-002)

`read_queue_entry` is in `tools/list` iff at least one node

1. belongs to a group with `unsupported.queueEntryRead: true`,
2. is not pinned to a version or line on which `queue_entry_read` is unsupported (6.1, a line
   other than 8.1/9.0; under the FR-019 fallback also 9.0), and
3. has an INUBIT client home configured for one of its possible versions (`cli.home`,
   `defaults.cliClients`, `defaults.cliHome`; installed or not, as for the exports).

Otherwise the tool list is exactly that of 0.8.0 (`ToolAnnouncementTest`, `MainTest`).

Annotations: `ToolHints.readOnly("Read a Queue Manager entry", openWorld = true, idempotent =
true)` → `readOnlyHint: true`, `destructiveHint: false`, `idempotentHint: true`,
`openWorldHint: true`.

## Description (shown to clients)

```text
Reads the content of one INUBIT Queue Manager entry in state ERROR: the entry's metadata, the data stream that ended in the queue (text cut at maxChars; binary content only as size and kind) and the workflow variables, with credentials masked as ${secret:…}. Use it after find_processes or query_logs (logType queueLog) showed an ERROR entry whose cause is not in the error text. UNSUPPORTED: uses an undocumented internal INUBIT interface (the Workbench's Queue Manager "View file") without vendor support or stability guarantee; it may stop working or behave differently after any INUBIT update; for diagnosis only. Returns business data of the entry; credentials under unusual names may not be masked. Available only for groups with unsupported.queueEntryRead.
```

`QueueEntryDocsTest` asserts the words "unsupported", "undocumented", "no vendor support" (or
"without vendor support"), "any INUBIT update", "diagnosis only" and "business data".

## Input schema (`schemas/read_queue_entry.input.json`)

```json
{
  "type": "object",
  "required": ["target", "processId"],
  "properties": {
    "target": {
      "type": "string",
      "pattern": "^[a-z0-9][a-z0-9-]{0,31}/[a-z0-9][a-z0-9-]{0,31}$",
      "description": "Id of exactly one {node} (`<{group}>/<{node}>`); {group} ids are rejected"
    },
    "processId": {
      "type": "string",
      "pattern": "^[1-9][0-9]{0,18}$",
      "description": "The decimal Queue Manager id: processId from find_processes (queueLog workflowId); a UUID globalProcessId is not accepted"
    },
    "fileName": {
      "type": "string",
      "minLength": 1,
      "maxLength": 255,
      "pattern": "^[^/\\\\\\u0000-\\u001F\\u007F-\\u009F]{1,255}$",
      "description": "The queue file name as query_logs with logType queueLog lists it in fields.fileName; needed when the process has several ERROR entries"
    },
    "maxChars": {
      "type": "integer",
      "minimum": 1,
      "maximum": 100000,
      "default": 20000,
      "description": "Maximum characters of the data stream text returned (masked text; the full size is always reported)"
    }
  },
  "additionalProperties": false
}
```

The service repeats every check (`INVALID_INPUT` with the field name; a group id gives the
`TargetResolver.resolveSingleServer` text).

## Output (`schemas/read_queue_entry.output.json`)

```json
{
  "node": "test/node1",
  "entry": {
    "processId": "1234",
    "globalProcessId": "0f0e0d0c-0000-4000-8000-000000000001",
    "workflow": "Workflow-0001",
    "module": "Module-0001",
    "moduleType": "Throw",
    "state": "ERROR",
    "rawState": "Error",
    "since": "2026-11-10T08:15:30Z",
    "fileName": "Module-0001(4711)@1234@0a1b2c3d",
    "sizeBytes": 772,
    "errorTexts": { "userDefined1": "ACME-ERROR", "userDefined2": "Wrong data type for receiver" }
  },
  "data": {
    "available": true,
    "kind": "text",
    "charset": "UTF-8",
    "sizeBytes": 772,
    "totalChars": 772,
    "returnedChars": 772,
    "truncated": false,
    "text": "<soap:Envelope …><wsse:Password>${secret:data.Password}</wsse:Password>…"
  },
  "variables": {
    "available": true,
    "totalCount": 51,
    "returnedCount": 51,
    "truncated": false,
    "items": [
      { "name": "ISFileSize", "system": true, "type": "Long", "kind": "text", "value": "772", "masked": false },
      { "name": "apiToken", "system": false, "kind": "text", "value": "${secret:variables.apiToken}", "masked": true },
      { "name": "var.order", "system": false, "type": "XmlDocument", "kind": "text", "value": "<Order><Id>Order-0001</Id>…", "masked": false, "totalChars": 5120, "truncated": true }
    ]
  },
  "warnings": ["Read through an unsupported, undocumented INUBIT interface; diagnosis only"]
}
```

Variants:

| Part | Shape |
|---|---|
| `data` empty | `{"available": true, "kind": "empty", "sizeBytes": 0}` |
| `data` binary | `{"available": true, "kind": "binary", "sizeBytes": 20480, "detected": "ZIP", "hint": "Binary content is not returned"}` (`detected` only if a signature matched) |
| `data` / `variables` unavailable | `{"available": false, "reason": "…"}`; reasons: `"too large (12582912 bytes; limit 10 MiB)"`, `"INUBIT did not find the content of this entry"`, `"INUBIT reported an error: <scrubbed excerpt>"`, `"unexpected format; the unsupported INUBIT interface may have changed"` |
| variable binary value | `{"name": "…", "system": false, "kind": "binary", "sizeBytes": 1024, "detected": "GZIP", "masked": false}` |
| variable name cut | `"nameTruncated": true` (name cut at 200) |
| variable list cut | `"truncated": true`, `returnedCount` 200, `totalCount` > 200 |

Bounds (FR-009, FR-010): `text` ≤ `maxChars`; ≤ 200 items; each `value` ≤ 2,000; all values ≤
40,000 (a value past the budget is `""` with `truncated: true`); all on the masked text.
`since` ISO-8601 UTC. Every string passes `SecretScrubber` (`ResultMapper`).

## Errors

All as `ToolError` (`code`, `message`, `likelyCause`, `nextStep`, `node`, `excerpt?`). "Nothing
was sent" = no request to the server; "no content request" = the helper was not started.

| Code | When | Message (example) | Next step |
|---|---|---|---|
| `INVALID_INPUT` | input fails a check; group id as target | `Invalid processId 'abc'` / `Invalid fileName: …` / `Invalid maxChars 0 (1–100000)` | fix the input |
| `TARGET_UNKNOWN` | unknown id | existing `TargetResolver` text | use an id from `list_nodes` |
| `NOT_CONFIGURED` | group without the opt-in (checked before version detection; nothing sent) | `read_queue_entry is not enabled for {group} test: unsupported.queueEntryRead is not set` | `Set unsupported.queueEntryRead: true on {group} test and restart the server (unsupported INUBIT functionality, see docs/setup.md "Unsupported functions")` |
| `UNSUPPORTED_VERSION` | detected 6.1, other line or undeclared version; 9.0 under the fallback | `read_queue_entry: Reading Queue Manager entries refused: INUBIT 6.1.3 on legacy/node1 …; supported: 8.1, 9.0` / `… is not yet verified for INUBIT 9.0` | gate texts (`VersionGate`) |
| detection error's own code (`UNREACHABLE`, `TIMEOUT`, `TLS_ERROR`, `AUTH_FAILED`, `UNEXPECTED_RESPONSE`, …) | version of an `AUTO` node cannot be detected (US3-4): the failure of this call's detection is passed through; during the 60 s back-off the recorded code | the detection's own message / `The INUBIT version of test/node1 could not be detected (UNREACHABLE at 08:15:30Z); read_queue_entry needs it` | the detection's own next step / `Fix the cause and retry in a minute, or set versionLine of test/node1` |
| `NOT_FOUND` | no row for the process ID; `fileName` not among its rows; entry left the Queue Manager after the content request | `Process 1234 is not (or no longer) in the Queue Manager of test/node1; nothing was read` / `Queue entry Module-0001(4711)@1234@0a1b2c3d of process 1234 left the Queue Manager of test/node1 (restarted, killed or deleted meanwhile)` | `Look the instance up again with find_processes` |
| `INVALID_INPUT` | rows exist, none in ERROR; `fileName` names a non-ERROR row (no content request) | `read_queue_entry reads only ERROR entries; process 1234 on test/node1 has: Waiting (Module-0002(…)@1234@…), Retry (…)` | `Wait until the entry is in ERROR, or use find_processes` |
| `INVALID_INPUT` | several ERROR rows without `fileName` | `Process 1234 on test/node1 has 2 ERROR entries: Module-0001(…)@1234@… (Workflow-0001, Module-0001, 2026-11-10T08:15:30Z); Module-0003(…)@1234@… (Workflow-0002, Module-0003, 2026-11-10T08:15:29Z)` | `Repeat the call with fileName set to one of them` |
| `CLI_UNAVAILABLE` | no client home for the version; `lib/ibis.jar` or JDK missing; class path separator in a path; helper could not start; vendor class/method missing; no private temp directory | `No INUBIT client installation is configured for test/node1 (INUBIT 9.0.10)` / `The INUBIT client installation /opt/inubit-9.0/client (chosen for INUBIT 9.0) lacks com.inubit.ibis.client.rest.InubitClientFactory` | `Add defaults.cliClients."9.0".home …` / `Check that defaults.cliClients."9.0" points to an INUBIT 9.0 client` |
| `AUTH_FAILED` | login rejected (shared `CredentialGuard` back-off) | `Login of the queue-entry helper to test/node1 was rejected` | as StartCLI; on 9.x plus the internal-user-management hint |
| `FORBIDDEN` | INUBIT refuses the Queue Manager read for the user | `test/node1 refused reading the Queue Manager entry for the configured user` | `Give the user the Queue Manager right the Workbench needs for "View file"` |
| `TLS_ERROR` / `UNREACHABLE` / `TIMEOUT` | TLS check before the launch, login or download connection; `cliTimeout` exceeded (slot wait + check + helper); no partial content | probe texts with "the queue-entry helper was not started" / `The queue-entry helper did not finish within cliTimeout (60 s)` | existing texts naming `tls.*` / `cliTimeout` |
| `SECRET_UNRESOLVED` (or the node's other credential codes `AUTH_FAILED`/`UNREACHABLE`/`TIMEOUT`) | credentials of the node not yet available | the node's usual credential error from the queueLog lookup, naming the missing secret; nothing launched | as for the node's other reads |
| `MAINTENANCE_MODE` | queueLog lookup answers 503 in maintenance | existing `InubitHttpClient` text | — |
| `UNEXPECTED_RESPONSE` | queueLog row unusable; helper output without `end`, unknown frame, over 1 MiB, exit code ≠ frames | `read_queue_entry got an unexpected answer from test/node1; the unsupported INUBIT interface may have changed after an INUBIT update` | `Read the entry in the Workbench (Queue Manager, View file); report the INUBIT version` |
| `INTERNAL` | helper resource missing; shutting down | `The queue-entry helper is missing from this build` | — |

Not errors (partial result, FR-012): one part `NotFound` while the row still exists, one part
failed with an INUBIT error, one part too large or of unexpected format.

## Capability table and `list_nodes` (docs/tools.md)

| Tool | Capability | Kind | Channel | 8.1 | 9.0 | 6.1 / other / unknown |
|---|---|---|---|---|---|---|
| `read_queue_entry` | `queue_entry_read` | read (unsupported vendor interface) | REST lookup + helper on the INUBIT client | supported | supported (after the 9.0 recording; else refused "not yet verified") | refused |

`list_nodes`: `queue_entry_read` appears in `usable`/`notUsable` only for nodes whose group has
the opt-in (`list_nodes.output.json` enum + `"queue_entry_read"`).
