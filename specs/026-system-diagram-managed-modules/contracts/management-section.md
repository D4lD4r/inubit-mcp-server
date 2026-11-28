# Contract: the `management` section and `managedModules`

**Feature**: [spec.md](../spec.md) | **Data model**: [data-model.md](../data-model.md)

No new tool, no new input parameter (FR-011). Two output extensions and two description sentences.

## 1. `management` (module items)

Where:

- `get_inventory_item` → `results[].item.management` for `kind: MODULE` when the module was found
  (not on a NOT_FOUND item, not on a server `error`);
- `read_model_details` → `module.management` for `action: module`, except when the module's `type`
  starts with `sd`.

```json
"management": {
  "status": "managed",
  "complete": true,
  "owner": "OWNERS",
  "collectedAt": "2026-11-28T08:00:00Z",
  "systemDiagramsChecked": 3,
  "systemDiagramsNotRead": 0,
  "elements": [
    {"systemDiagram": {"name": "SD-HTTP", "diagramGroup": "GRP-CONNECT"},
     "element": "Central-Backend",
     "connectorType": "HTTP Connector",
     "settings": [
       {"name": "ServerName", "type": "String", "value": "https://central.example.test"},
       {"name": "Login", "type": "String", "value": ""},
       {"name": "Password", "type": "Password", "masked": true}],
     "differing": [
       {"name": "ServerName", "central": "https://central.example.test",
        "module": "http://localhost:8000/dummy"}]}],
  "warnings": [],
  "truncated": false
}
```

| Field | Presence | Meaning |
|---|---|---|
| `status` | always | `managed` \| `not_managed` \| `unknown` |
| `complete` | always | every system diagram (and element) of the owner was read; `not_managed` only with `true` |
| `owner` | unless `NOT_CONFIGURED` | the owner whose system diagrams were checked |
| `collectedAt` | when something was read | when the cached index was collected (ISO-8601) |
| `systemDiagramsChecked` | always | readable system diagrams |
| `systemDiagramsNotRead` | always | 0 when `complete` |
| `firstNotRead` | when not `complete` and something was read | `{kind: diagramList \| moduleList \| systemDiagram \| element, name?, systemDiagram?, code, message}` |
| `elements` | `managed` only (≥ 1, ≤ 10) | the managing elements |
| `elements[].settings` | always (may be `[]`) | `Property` items of `read_model_details` (value / masked / entries) |
| `elements[].differing` | when non-empty | `{name, central, module}`; non-secret top-level settings only |
| `elements[].comparisonUnavailable` | when the module's own properties could not be read | `{code, message, nextStep}` |
| `warnings` | always (may be `[]`) | `managed more than once`, `cannot be attributed to a single module`, `type does not match`, `incomplete: further management cannot be ruled out` |
| `reason` | `unknown` only | `{code, message, nextStep}`, e.g. `NOT_CONFIGURED`, `UNSUPPORTED_VERSION`, `TIMEOUT` ("the read goes on and fills the cache"), `NOT_FOUND` ("diagram does not belong to the configured owner") |
| `truncated` | always | `elements` or a list inside was cut |

Schema rules (`get_inventory_item.output.json` `$defs.Management`, `read_model_details.output.json`
`$defs.Management`, identical): `additionalProperties: false`; `status: managed` requires
`elements` (minItems 1) and forbids `reason`; `status: unknown` requires `reason` and forbids
`elements`; `status: not_managed` requires `complete: true` and forbids `elements`, `reason` and
`firstNotRead`.

## 2. `managedModules` (system diagram detail)

Where: `get_inventory_item` → `results[].item.managedModules` for `kind: DIAGRAM` when the diagram's
type is `systemdiagram` and it has at least one `sdExtSystem` element (or the elements could not be
listed).

```json
"managedModules": {
  "collectedAt": "2026-11-28T08:00:00Z",
  "elements": [
    {"element": "Central-Backend", "nodeId": "117", "connectorType": "HTTP Connector",
     "settings": [{"name": "ServerName", "type": "String", "value": "https://central.example.test"}],
     "linked": [{"name": "Demo-HTTP-Out-A", "found": true},
                {"name": "Demo-HTTP-Gone", "found": false}]},
    {"element": "Broken-Element", "nodeId": "149",
     "error": {"code": "UNEXPECTED_RESPONSE", "message": "…"}}],
  "truncated": false
}
```

| Field | Presence | Meaning |
|---|---|---|
| `elements[]` | always (≤ 50) | one per `sdExtSystem`, in INUBIT's order; either `connectorType?`, `settings`, `linked` or `error` |
| `linked[].found` | unless `moduleListUnavailable` | whether the owner has a module of that name |
| `moduleListUnavailable` | when the module list could not be read | `{code, message, nextStep}` |
| `unavailable` | when the elements could not be listed or the gate refused | `{code, message, nextStep}`; then `elements` is `[]` |
| `truncated` | always | |

## 3. Descriptions (FR-012)

Appended to both descriptions (`get_inventory_item`, `read_model_details`):

> A connector module's connection settings can be managed centrally by a system diagram; the
> module result's `management` says whether, by which element and with which settings — read it
> before trusting the module's own connection properties.

`get_inventory_item` additionally: "The detail of a system diagram lists in `managedModules` the
modules its external systems manage."

## 4. Other surfaces

- `list_nodes` / `--check-config`: capability `module_management` per server (supported on 8.1).
- `interface-coverage.tsv`: `GET /model/nodeByModel/{model}/{node}` also reachable for
  `get_inventory_item/default`; `docs/interface-coverage.md` matches.
- No audit record (read-only, names and masked settings only).
