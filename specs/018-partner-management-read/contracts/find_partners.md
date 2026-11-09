# Contract: `find_partners`

Read tool. Hints: `readOnly: true`, `destructive: false`, `idempotent: true`, `openWorld: true`.
Offered when at least one server is possibly capable of `PARTNER_MANAGEMENT`; the channel is REST
(research D-1), so the condition is the same as for `get_inventory_item`.

## Input (`find_partners.input.json`)

| Field | Type | Required | Constraints |
|---|---|---|---|
| `target` | string | yes | id of one stage or one server (`<stage>` or `<stage>/<server>`) |
| `compareWith` | string | no | stage or server id; must not equal or overlap `target` |
| `owner` | string | no | `^[A-Za-z0-9_.][A-Za-z0-9_.\- ]{0,199}$` (as `export_archives`); default: each server's `inventory.owner`; applies to all servers when given |
| `search` | string | no | 1–200 characters, no control characters; substring of non-masked partner fields, case-insensitive |
| `systemDiagram` | string | no | system diagram name, exact, case-insensitive, any diagram group |
| `protocol` | string | no | 1–64 characters `[A-Za-z0-9_-]`; `ReferencedTWF` attribute `protocol`, exact, case-insensitive |
| `workflow` | string | no | referenced workflow name, exact, case-insensitive (the export has no diagram group, D-2) |
| `offset` | integer | no | ≥ 0, default 0 |
| `limit` | integer | no | 1–100, default 20 |
| `refresh` | boolean | no | default false; bypasses the cache |

## Output (`find_partners.output.json`)

```jsonc
{
  "callId": "uuid",
  "mode": "search | list",          // search term given or not
  "compared": false,                 // true with a stage of several servers or compareWith
  "servers": [                       // one per targeted and compared server
    { "node": "qa/node1", "group": "qa", "owner": "…", "collectedAt": "…", "fromCache": true,
      "systemDiagramsRead": [{"name": "…", "diagramGroup": "…"}],
      "withoutPartnerManagement": [{"name": "…", "diagramGroup": "…"}],
      "unreadableDiagrams": [         // per diagram, the server is still answered (D-4); also
                                      // UNEXPECTED_RESPONSE "…holds several diagrams of this name
                                      // in this group…", and NOT_FOUND for an export with HTTP 404
        { "systemDiagram": {"name": "…", "diagramGroup": "…"}, "code": "UNEXPECTED_RESPONSE",
          "message": "the export of this name returned a diagram of another group or owner" } ],
      "warnings": ["9.0.3 on qa/node1 is not verified for partner management; …",
                   "an entry of partner ACME-0001 in Module-0901 references an unknown workflow"],
      "error": null }                // ToolError {code, message, cause, nextStep} for a failed server
  ],
  "referencableWorkflows": [         // FR-007, per communication cloud that contributed a partner
    { "systemDiagram": {"name": "…", "diagramGroup": "…"}, "cloud": "Module-0901",
      "workflows": ["Workflow-0001"] }
  ],
  "partners": [ /* single server: Partner; comparison: ComparedPartner (below) */ ],
  "similarValues": ["ACME-0001"],    // only when a search matched nothing (FR-005b)
  "total": 120, "offset": 0, "limit": 20, "nextOffset": 20, "truncated": false,
  "incomplete": false,               // a compared server failed
  "notCompared": [{"node": "…", "code": "UNREACHABLE"}]
}
```

**Partner (single server)**

```jsonc
{ "systemDiagram": {"name": "…", "diagramGroup": "…"}, "cloud": "Module-0901",
  "fields": [{"name": "Id", "value": "ACME-0001"}, {"name": "Secret", "masked": true, "set": true}],
  "maskedCount": 2,
  "entries": [
    { "protocol": "FTP", "workflow": {"name": "Workflow-0001"}, "format": "…",
      "protocolSettings": [{"name": "ServerName", "value": "ftp.example.test"},
                           {"name": "Password", "masked": true, "set": true}],
      "additionalParameters": [{"name": "FTPProtocol", "value": "SFTP"}] } ] }
```

An entry whose `referencableTWFId` does not resolve has `"workflow": {"name": null, "unresolved":
true}`; the reader's warning for it goes to `servers[].warnings` of that server.

In `list` mode an entry carries only `protocol`, `workflow` and the summary keys `ServerName` and
`Login` (if present, D-2); `fields` stay complete.

**ComparedPartner**

```jsonc
{ "systemDiagram": {…}, "cloud": "Module-0901", "key": {"field": "Id", "value": "ACME-0001"},
  "onlyOn": null,                      // or ["prod/node1"]
  "fields": [{"name": "Name", "equal": "ACME Corp"}],
  "entries": [
    { "protocol": "FTP", "workflow": {"name": "Workflow-0001"}, "position": 0, "onlyOn": null,
      "format": {"equal": "…"},       // entry attributes other than protocol/workflow, grouped like settings
      "protocolSettings": [
        {"name": "ServerName", "differs": {"qa/node1": "a.example.test", "prod/node1": "b.example.test"}},
        {"name": "Login", "equal": "acme"},
        {"name": "Password", "masked": true, "set": {"qa/node1": true, "prod/node1": true},
         "sameOn": [["qa/node1"], ["prod/node1"]]}           // or "equality": "not verified on 9.0.3"
      ],
      "additionalParameters": [{"name": "FTPProtocol", "equal": "SFTP"}] } ],
  "maskedCount": {"equal": 3},       // or {"differs": {"qa/node1": 3, "prod/node1": 2}} (FR-009)
  "ambiguous": [] }
```

Result types map a masked value to `{"masked": true, "set": …}` and never carry the
fingerprint.

Without a search term, a ComparedPartner holds the summary fields plus only `differs`, masked
settings whose `set` or `sameOn` differ, and `onlyOn` entries (FR-012c).

## Errors

Call level (no partner data): `INVALID_INPUT`, `TARGET_UNKNOWN`, `NOT_FOUND` (unknown
`systemDiagram` on every answering server, with `similarNames`), `INTERNAL` (also: an audit record
could not be written; message names the `callId`).

Server level (in `servers[].error`, others answered): `NOT_CONFIGURED` (no owner),
`UNSUPPORTED_VERSION` (6.1), `CLI_UNAVAILABLE`, `UNREACHABLE`, `TLS_ERROR`, `AUTH_FAILED`,
`FORBIDDEN`, `MAINTENANCE_MODE`, `TIMEOUT` (names the timeout setting), `UNEXPECTED_RESPONSE`
(names the archive part that could not be read). No error contains export content or a value.

## Guarantees

- No value of a masked setting, and nothing derived from it, in the output, an error, the log or
  the audit (SC-002); `sameOn` is the only statement about stored secrets.
- Order: system diagram group, name, communication cloud, then partner `Id`, then `Name` (list);
  entries in export order.
- Size: within `resultLimits.maxChars`; `truncated: true` when cut.
