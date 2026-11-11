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
      "systemDiagramsRead": [{"name": "…", "diagramGroup": "…",
                              "clouds": [{"cloud": "Module-0901", "moduleId": "33",
                                          "partners": 0}]}],
                                      // moduleId only when two clouds of one diagram share a name
                                      // every cloud with its total partner count (FR-007: a
                                      // cloud without partners shows 0); empty for a diagram
                                      // without Partner Management
      "withoutPartnerManagement": [{"name": "…", "diagramGroup": "…"}],
      "unreadableDiagrams": [         // per diagram, the server is still answered (D-4); also
                                      // UNEXPECTED_RESPONSE "…holds several diagrams of this name
                                      // in this group…", and NOT_FOUND for an export with HTTP 404
        { "systemDiagram": {"name": "…", "diagramGroup": "…"}, "code": "UNEXPECTED_RESPONSE",
          "message": "the export of this name returned a diagram of another group or owner" } ],
      "warnings": ["9.0.3 on qa/node1 is not verified for partner management; …",
                   "an entry of partner ACME-0001 in Module-0901 references an unknown workflow"],
      "error": {…} }                 // only for a failed server: ToolError {code, message,
                                      // likelyCause, nextStep, node}; absent otherwise
  ],
  "referencableWorkflows": [         // FR-007, per communication cloud that contributed a partner
    { "systemDiagram": {"name": "…", "diagramGroup": "…"}, "cloud": "Module-0901",
      "moduleId": "33",              // only when two clouds of one diagram share a name
      "workflows": ["Workflow-0001"] }
  ],
  "partners": [ /* single server: Partner; comparison: ComparedPartner (below) */ ],
  "similarValues": ["ACME-0001"],    // only when a search matched nothing (FR-005b)
  "total": 120, "offset": 0, "limit": 20, "nextOffset": 20, "truncated": false,
  "incomplete": false,               // comparison only: a server failed or could not read a diagram
  "notCompared": [{"node": "…", "code": "UNREACHABLE"},   // comparison only, in server order
                  {"node": "…", "code": "NOT_FOUND",      // a system diagram not read there
                   "systemDiagram": {"name": "…", "diagramGroup": "…"}}]
}
```

**Partner (single server)**

```jsonc
{ "systemDiagram": {"name": "…", "diagramGroup": "…"}, "cloud": "Module-0901",
  "moduleId": "33",                  // only when two clouds of one diagram share a name
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
{ "systemDiagram": {…}, "cloud": "Module-0901",
  "moduleId": "33",                  // only when two clouds of one diagram share a name
  "key": {"field": "Id", "value": "ACME-0001"},   // absent: no key, compared on its server alone
  "onlyOn": ["prod/node1"],          // absent when every answering server holds it
  "fields": [{"name": "Name", "equal": "ACME Corp"}],
  "entries": [
    { "protocol": "FTP", "workflow": {"name": "Workflow-0001"}, "position": 0,
                                      // position among the entries of this protocol and workflow
      "onlyOn": ["qa/node1"],         // absent when every server holding the partner holds it
      "format": {"name": "format", "equal": "…"},   // a setting named format
      "protocolSettings": [
        {"name": "ServerName", "differs": {"qa/node1": "a.example.test", "prod/node1": "b.example.test"}},
        {"name": "Login", "equal": "acme"},
        {"name": "Password", "masked": true, "set": {"qa/node1": true, "prod/node1": true},
         "sameOn": [["qa/node1"], ["prod/node1"]]}           // or "equality": "not verified on 9.0.3"
      ],
      "additionalParameters": [{"name": "FTPProtocol", "equal": "SFTP"}] } ],
  "maskedCount": {"equal": 3},       // or {"differs": {"qa/node1": 3, "prod/node1": 2}} (FR-009)
  "ambiguous": [{"node": "qa/node1", "partners": 2, "moduleIds": ["33"]}] }
```

Absent values are omitted, never `null`. A partner without key (key field masked, absent or blank)
and every partner of an ambiguous key (several partners of the key on a server, or two clouds of
the module name there) is compared on its server alone: no `key` or no match, `onlyOn` names that
server, and `ambiguous` says why. An entry whose workflow does not resolve is compared on its
server alone likewise. A setting or field masked on any server is compared as masked: `set` per
server, and only when it is set on at least two servers either `sameOn` or `equality`; a set value
without fingerprint (child elements, or shown on its server) gets neither. A field masked on any
server is not searched. A failed server is only listed in `notCompared` (its error in `servers`);
`onlyOn` and every statement consider the answering servers only. A server that answered but could
not read one system diagram (`unreadableDiagrams` in `servers`) is listed in `notCompared` with
that `systemDiagram`, the result is `incomplete`, and it is left out of `onlyOn` and every
statement for that diagram's partners only: it neither holds nor lacks them. `notCompared`
lists only the diagrams kept in the bounded `unreadableDiagrams`, with their cut names.

Result types map a masked value to `{"masked": true, "set": …}` and never carry the
fingerprint.

Without a search term, a ComparedPartner holds the summary fields plus only `differs`, masked
settings whose `set` or `sameOn` differ, and `onlyOn` entries (FR-012c); `format` only if it
differs, `additionalParameters` only the differing ones (absent if none). With a search term, the partners
keep the comparison order; without, the list order.

## Errors

Call level (no partner data): `INVALID_INPUT`, `TARGET_UNKNOWN`, `NOT_FOUND` (unknown
`systemDiagram` on every answering server; up to five similar system diagram names in `nextStep`,
as `ToolError` has no list field), `INTERNAL` (also: an audit record
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
- Size: within `resultLimits.maxChars`; `truncated: true` when cut. The per-server lists `systemDiagramsRead`,
  `withoutPartnerManagement` and `unreadableDiagrams` are cut to fit too (diagrams with a partner on
  the page first; unreadable diagrams before the others), and each cloud's `workflows` in
  `referencableWorkflows` likewise; the version gate's warnings are always kept. Only if the parts that are never cut (version gate warnings,
  server errors, at most five similar values, the fields and the system diagram of the first partner of the page)
  alone
  exceed the bound, the result does too, always with `truncated: true`.
- Size of a comparison: the same bound and order of cuts on ComparedPartners. Texts are cut with
  the marker (a search still matches the full value); then the detail from the last partner of the
  page to the first: `additionalParameters` dropped, then `protocolSettings` down to `ServerName`
  and `Login`; then partners from the end of the page (the first is kept), then the last entries
  of that partner. `referencableWorkflows` holds, per cloud of a returned partner, the workflows of
  every server holding a partner of that cloud on the page before the size bound (a server
  whose partners of that cloud were cut still holds the cloud); the system diagram of a returned
  partner stays listed on every server holding it.
