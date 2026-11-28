# Quickstart: Modules Managed by a System Diagram

**Feature**: [spec.md](spec.md) | **Contract**: [contracts/management-section.md](contracts/management-section.md)

## Build and test

```bash
mvn verify
```

```bash
python3 -I -m unittest discover -s tools/tests -v
```

## Validation scenarios (automated, WireMock fake server)

Synthetic fixtures (research D-11): a system diagram `SD-HTTP` (group `GRP-CONNECT`) whose `modelByName`
lists `sdExtSystem` `Central-Backend` (node 201), `sdExtSystem` `Second-Backend` (node 202),
`sdInubitIS` and `sdCommunicationCloud`; `nodeByModel` answers from `admin-model-node-by-model-sd-ext-system.xml` (planted
secrets 3911–3914), `admin-model-node-by-model-sd-ext-system-2.xml` (3917) and
`admin-model-node-by-model-managed-module.xml` (3918); a module `Demo-HTTP-Out-A` used by workflow `TW-Out` whose own `ServerName` is
`http://localhost:8000/dummy`.

| # | Scenario | Spec | Expected |
|---|---|---|---|
| V-1 | `get_inventory_item` MODULE `Demo-HTTP-Out-A` | US1 AS1, AS2 | `management.status = managed`, element `Central-Backend`, `differing` ServerName with both values, password masked |
| V-2 | module linked by no element | US1 AS3 | `not_managed`, `complete: true`, `systemDiagramsChecked: 1` |
| V-3 | linked by both elements | US1 AS4 | two elements, warning "managed more than once" |
| V-4 | stage of two servers, other central server on node2 | US1 AS5 | each result its own settings |
| V-5 | second system diagram answers 500 | US1 AS6 | `managed`, `complete: false`, `firstNotRead` names it, warning |
| V-6 | two modules share the name (types match / differ / none match) | US1 AS7 | only the matching one / both with "cannot be attributed" |
| V-7 | `read_model_details` module of `TW-Out` | US2 AS1 | properties unchanged + same `management` |
| V-8 | `read_model_details` of `sdExtSystem` / `sdCommunicationCloud` | US2 AS2 | no `management` |
| V-9 | server without `inventory.owner` | US2 AS3 | `unknown`, `NOT_CONFIGURED`, properties shown |
| V-10 | diagram not in the owner's list | US2 AS4 | `unknown`, "does not belong to the configured owner" |
| V-11 | `get_inventory_item` DIAGRAM `SD-HTTP` | US3 AS1, AS2 | `managedModules` with both elements; `Demo-HTTP-Gone` `found: false` |
| V-12 | system diagram without `sdExtSystem` | US3 AS3 | no `managedModules` |
| V-13 | no cached module list / one element fails / the diagram itself unreadable / diagram missing from a cached index | US3 AS4, AS5 | names without `found` + `moduleListUnavailable`; element with `error`; `unavailable` with the item's code; `unavailable` `NOT_FOUND` "call again with `refresh: true`" |
| V-14 | tool list | US4 | both descriptions contain the sentence (pinned) |
| V-15 | node on 9.0 | FR-015 | `unknown`, `UNSUPPORTED_VERSION` "not yet verified" |
| V-16 | slow `nodeByModel` beyond the budget | FR-017 | `unknown`, `TIMEOUT`, "the read goes on and fills the cache"; next call served from cache |
| V-17 | two module calls within the TTL | SC-004 | no second `modelByName`/`nodeByModel` request |
| V-18 | leak test: result, error excerpts, logs, cache | SC-003 | none of 3911–3914, 3917, 3918 |
| V-19 | 8.1 node | D-2 | the check runs without a version warning in `management.warnings` / `managedModules.warnings` |
| V-20 | `read_model_details` / DIAGRAM detail with no cached module list | D-13 | no StartCLI run; managed module with "module list not loaded" warning; `managedModules` without `found` + `moduleListUnavailable` |
| V-21 | diagram list fails | D-13 | `unknown`, `complete: false`, `systemDiagramsChecked: 0`, `firstNotRead.kind = diagramList`; `managedModules.unavailable` |
| V-22 | ten system diagrams, 20 elements, responsive fake | SC-005 | index loaded within `managementBudget` |

## Live acceptance (owner-gated, T-record and T-live)

Prerequisite: a reachable 8.1 non-production node with a system diagram that manages connectors
centrally, and the operator's credentials in the configured store.

1. Record (operator runs the recorder; the agent never handles credentials):

   ```bash
   tools/record-fixtures.sh <group> <node> model_by_name_systemdiagram
   ```

   ```bash
   tools/record-fixtures.sh <group> <node> node_by_model_sd_ext_system
   ```

   ```bash
   tools/record-fixtures.sh <group> <node> model_export_systemdiagram
   ```

2. With a build of the branch registered as a second MCP server (or the stdio probe of the plan),
   call `get_inventory_item` for a centrally managed connector module and for its system diagram,
   and `read_model_details` for the module's node in a using workflow. Expected: `managed` with the
   managing element; the system diagram lists the module; no secret value in the answers.
3. SC-001: the operator's agent, prompt "Where does module Demo-HTTP-Out-A send its data?" against
   the fake server of V-1, 10 runs; every answer names `https://central.example.test`.
