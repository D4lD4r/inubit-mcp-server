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
| `collectedAt` | when an index was had | when the cached index was collected (ISO-8601) |
| `systemDiagramsChecked` | always | system diagrams read completely (all elements read) |
| `systemDiagramsNotRead` | always | 0 when `complete` |
| `firstNotRead` | when an index was had and it is not complete (a failed diagram list included; never for gate refusal, no owner, foreign diagram, timeout) | `{kind: diagramList \| systemDiagram \| element, name?, systemDiagram?, nodeId?, code, message}` (`systemDiagram` and `nodeId` for `element` only) |
| `elements` | `managed` only (≥ 1, ≤ 10); left out otherwise | the managing elements |
| `elements[].systemDiagram` | always | `{name, diagramGroup}` of the managing system diagram |
| `elements[].element` | always | the external system element's name |
| `elements[].connectorType` | when `ResourceType` is shown | the element's `ResourceType` |
| `elements[].settings` | always (may be `[]`) | `Property` items exactly as `read_model_details` writes them: `{name, type, value, truncated?}` \| `{name, type, masked: true}` \| `{name, type, entries}` (serialized by `ResultJson`, research D-10) |
| `elements[].differing` | when non-empty | `{name, central, module}`; non-secret top-level settings only |
| `elements[].comparisonUnavailable` | when the module's own properties could not be read | `{code, message, nextStep}` |
| `warnings` | always (may be `[]`) | `managed more than once`, `cannot be attributed to a single module`, `type does not match`, `module list not loaded; names are not checked for duplicates …` (research D-13), `incomplete: further management cannot be ruled out`, and the version gate's warnings, if any |
| `reason` | `unknown` only | `{code, message, nextStep}`, e.g. `NOT_CONFIGURED`, `UNSUPPORTED_VERSION`, `TIMEOUT` ("the read goes on and fills the cache"), `NOT_FOUND` ("diagram does not belong to the configured owner"), or the code and message of `firstNotRead` for an incomplete index without a link, with the fixed next step per kind (research D-13) |
| `truncated` | always | `elements` or a list inside was cut |

Schema rules (`get_inventory_item.output.json` `$defs.Management`, `read_model_details.output.json`
`$defs.Management`, identical; both tools serialize the same domain record through `ResultJson`):
`additionalProperties: false`; `status: managed` requires `elements` (minItems 1) and forbids
`reason`; `status: unknown` requires `reason` and forbids `elements` (it may carry `firstNotRead`);
`status: not_managed` requires `complete: true` and forbids `elements`, `reason` and
`firstNotRead`.

## 2. `managedModules` (system diagram detail)

Where: `get_inventory_item` → `results[].item.managedModules` for `kind: DIAGRAM` when the diagram's
type is `systemdiagram` and it has at least one `sdExtSystem` element (a diagram without one, e.g. only communication clouds, has no `managedModules`). When there is no usable index for the diagram, `elements` is `[]` and `unavailable` is set; `collectedAt`, `warnings` and `truncated` follow their rules below.

```json
"managedModules": {
  "collectedAt": "2026-11-28T08:00:00Z",
  "elements": [
    {"element": "Central-Backend", "nodeId": "201", "connectorType": "HTTP Connector",
     "settings": [{"name": "ServerName", "type": "String", "value": "https://central.example.test"}],
     "linked": [{"name": "Demo-HTTP-Out-A", "found": true},
                {"name": "Demo-HTTP-Out-B", "found": true}]},
    {"element": "Second-Backend", "nodeId": "202",
     "error": {"code": "UNEXPECTED_RESPONSE", "message": "…",
               "nextStep": "check the system diagram with get_inventory_item, then retry with refresh: true"}}],
  "warnings": [],
  "truncated": false
}
```

| Field | Presence | Meaning |
|---|---|---|
| `collectedAt` | when an index was had (also with a failed diagram list) | when the cached index was collected |
| `elements[]` | always (≤ 50; `[]` with `unavailable`) | one per `sdExtSystem`, in INUBIT's order; either `connectorType?`, `settings` and `linked` (both always present, possibly `[]`, e.g. an element without `ResourcesLinked`) or `error {code, message, nextStep}` (then `connectorType`, `settings` and `linked` are left out) |
| `linked[]` | with `settings` | ≤ 100 names per element, in INUBIT's order; then `truncated` |
| `linked[].found` | unless `moduleListUnavailable` | whether the owner has a module of that name |
| `moduleListUnavailable` | when no cached module list exists and not `unavailable` (the check never starts the StartCLI module export, research D-13) | `{code, message, nextStep}` |
| `unavailable` | when there is no usable index for this diagram: gate refused, budget reached (`TIMEOUT`, "the read goes on and fills the cache"), diagram list failed, the diagram itself unreadable, or the diagram missing from a cached index (`NOT_FOUND`, "call again with `refresh: true`") | `{code, message, nextStep}` |
| `warnings` | always (may be `[]`) | the version gate's warnings, if any |
| `truncated` | always | |

## 3. Descriptions (FR-012)

Appended to both descriptions (`get_inventory_item`, `read_model_details`):

> A connector module's connection settings can be managed centrally by a system diagram; the
> module result's `management` says whether, by which element and with which settings — read it
> before trusting the module's own connection properties.

`get_inventory_item` additionally: "The detail of a system diagram lists in `managedModules` the
modules its external systems manage."

## 4. Other surfaces

- `list_nodes` / `--check-config`: capability `module_management` per server (8.1 supported; 9.0 not yet verified); `list_nodes.output.json` gains
  the enum value.
- `interface-coverage.tsv`: a comment line above the `GET /model/nodeByModel/{model}/{node}` row
  names its use by `get_inventory_item` (the management check) and the check's `modelByName`/`models`
  reads from `read_model_details`; the state column stays `reachable:read_model_details/module`
  (tasks.md T013); `docs/interface-coverage.md` is regenerated.
- No audit record (read-only, names and masked settings only).
