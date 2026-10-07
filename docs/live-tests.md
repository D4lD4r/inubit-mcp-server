# Live tests

The default build (`mvn verify`) is fully offline: the REST and CLI adapters are tested against
recorded fixtures and WireMock, and no INUBIT server is contacted. Tests tagged `@Tag("live")`
are excluded from it (`surefire.excludedGroups=live`).

Live tests are **opt-in** and **never run against production** (Constitution III). All of them are
read-only except the development live test of feature 004 (see
[Development live test](#development-live-test-feature-004)), which writes to dedicated test
workflows of a personal diagram group on a development node only.

## Run

```bash
INUBIT_MCP_PROFILE=acme INUBIT_LIVE_NODE=test/node1 mvn verify -Plive
```

- The profile `live` runs **only** the `@Tag("live")` tests.
- `INUBIT_LIVE_NODE` names one configured node (`<group>/<node>`, e.g. `test/node1`). Without it
  the live tests are skipped. Run them once per node you want to check (e.g. `test/node1`,
  `test/node2`).
- `INUBIT_LIVE_SERVER`, the variable of feature 001, is **refused**: a live test fails with
  "INUBIT_LIVE_SERVER is no longer supported; use INUBIT_LIVE_NODE=<group>/<node> instead, and
  select the profile with INUBIT_MCP_PROFILE=<profile>". Unset it in your shell.
- A single test: add `-Dtest=HealthLiveTest` (or `DiagnosisLiveTest`, `InventoryLiveTest`).
- `INUBIT_LIVE_DIAGRAM_GROUP` (optional) names a diagram group of the node's `inventory.owner`
  for `ArtifactExportLiveTest`; without it that test is skipped. Choose a small group.
- `INUBIT_LIVE_GROUP` (optional, `InventoryLiveTest` only) names the diagram group whose version
  history is exported; without it the group of the first technical diagram is used.
- `InventoryLiveTest` needs the StartCLI settings (`cliHome`, `cliJavaHome`, setup.md section 3)
  and an `inventory.owner` for the node; it takes about 30–60 s (two StartCLI exports); the other
  live tests need REST only.
- The configuration is the real one, found like the server finds it without `--config` or
  `--profile` ([setup.md](setup.md), section 3): `INUBIT_MCP_CONFIG` (a path), then
  `INUBIT_MCP_PROFILE` (a profile name for `~/.config/inubit-mcp/<name>.yaml`, which must declare
  that `profile.name`), then `~/.config/inubit-mcp/config.yaml`. So `INUBIT_MCP_PROFILE` selects
  the customer. The file must validate without errors, exactly as with
  `java -jar … --profile <name> --check-config` (including the comparison with the other profile
  files in the default configuration directory).
- Credentials come from the profile's usual `<PREFIX>_<GROUP>[_<NODE>]_USERNAME` / `_PASSWORD`
  environment variables (setup.md, section 4); they are never passed on the command line. If
  your current shell does not have them, run the command through an interactive shell that
  loads your profile, e.g.
  `zsh -ic 'cd /path/to/inubit-mcp-server && INUBIT_MCP_PROFILE=acme INUBIT_LIVE_NODE=test/node1 mvn -q verify -Plive'`.
- The selection logic itself (old variable refused, node parsed, location order, production
  refusal) is covered offline by `live/LiveTargetTest`, which runs in the default build.

## Production refusal

If the group of `INUBIT_LIVE_NODE` is configured with `production: true`, the test **fails
before any request is sent**:

```text
Refusing to run live tests against prod/node1: its group is production: true (Constitution III)
```

Only non-production groups (e.g. development or test stages) may be used.

## Tests

| Test | What it checks | Calls |
|---|---|---|
| `live/DiagnosisLiveTest` (T078) | `find_processes(states=[ERROR], since=PT24H, limit=20)` and `query_logs(logType=systemLog, limit=5)` for `INUBIT_LIVE_NODE` with the production wiring: no error, `total` ≥ page size, processIds are digits, state `ERROR`, `since` within 24 h, both pages newest first | `POST /ibis/rest/log/queueLog?format=json`, `POST /ibis/rest/log/systemLog?format=json` (read-only queries) |
| `live/InventoryLiveTest` (T098) | `list_inventory(kind=DIAGRAM, type=technical)`, the number of diagrams in group `GRP-41`, the technical diagrams of one group (`INUBIT_LIVE_GROUP`, else the group of the first technical diagram), `list_inventory(kind=MODULE)` twice (the second from the cache: same `collectedAt` and `total`), `get_inventory_item` of the first diagram of that group: versions newest first, `active` present, no `activeVersion`, nothing `unavailable`; and (T126) `list_inventory(kind=MODULE, type=XSLT Converter)`: `usageComplete`, at least one module with `workflowCount` ≥ 1, whose `get_inventory_item` has versions and nothing `unavailable` | `GET /ibis/rest/model/models`, `/model/modelByName/…` (also for every technical workflow: the module usage index), `/model/export/…` (read-only); StartCLI `export --exportModule '' …` and `export … --includeHistory` of one or two diagram groups into a private temporary directory that is deleted afterwards (read-only exports) |
| `live/ArtifactExportLiveTest` (feature 003, T038) | runs only with `INUBIT_LIVE_DIAGRAM_GROUP` (one diagram group of the node's `inventory.owner`): `export_artifacts` of that group twice into a **temporary** workspace (never the configured one) — the second export is `unchanged`; no `AES-` value and no `type="Password"` value other than a `${secret:…}` placeholder in the workspace; `check_artifacts` on the exported workflows reports no ERROR | StartCLI `export --exportWorkflowType 'technical' --exportWorkflowGroup '<group>'` twice into a private temporary directory that is deleted afterwards (read-only exports); the module list only if a module is missing in the workspace |
| `live/HealthLiveTest` (T063) | `get_health` for `INUBIT_LIVE_NODE` through the real MCP server with the production wiring (`Wiring`, via the test helper `TestWiring`): `reachable`, `ready`, `version` starting with `8.1`, answer within 5 s (SC-002) | `GET /healthcheck`, `/ready`, `/system/info`, `/metrics?format=json` (read-only) |

Each test prints one summary line without secrets or business data to stderr, e.g.
`[live] test/node1: version 8.1.17, status OK, maintenanceMode false, load 51.7% memory, unavailable [], 699 ms`
and `[live] test/node1: find_processes(ERROR, PT24H) total 244, page 20, 311 ms; query_logs(systemLog, 5) total 42380, page 5, 158 ms`.
`InventoryLiveTest` prints only counts and timings, never workflow, module or group names (except
the fixed group `GRP-41`).
Configuration loading, the target selection and the production refusal are shared
(`live/LiveTarget`).

## Development live test (feature 004)

`live/DevelopmentLiveTest` exercises the development tools against a real **development** node.
It **writes to INUBIT**, so it runs only when started explicitly — starting it is the approval of
exactly this scenario — and only on a **personal** diagram group of a **user** with dedicated,
disposable test workflows (never a shared owner, never production):

```bash
INUBIT_MCP_PROFILE=acme INUBIT_LIVE_DEV_NODE=dev/node1 INUBIT_LIVE_DEV_OWNER=jdoe \
  INUBIT_LIVE_DEV_DIAGRAM_GROUP=SPIKE mvn verify -Plive -Dtest=DevelopmentLiveTest
```

- Without `INUBIT_LIVE_DEV_NODE`, `INUBIT_LIVE_DEV_OWNER` or `INUBIT_LIVE_DEV_DIAGRAM_GROUP` the
  test is skipped.
- It **fails before anything is written** unless the node is a development node
  (`development.enabled: true`; the selection is covered offline by `LiveTargetTest`) and the
  owner is a user (`owners.<name>: USER` in the profile or listed in INUBIT's user list). A
  production group is refused as for every live test.
- Use a temporary copy of the profile if it needs `development.enabled: true`; never edit the
  real file under `~/.config/inubit-mcp/` for a test run. The test works in a **temporary
  workspace**; the audit records go to the profile's audit directory and the backups to
  `~/.inubit-mcp/<profile>/backups` as for every development call.
- Scenario (research D-23): `export_artifacts` of the group → one layout value (`xPos`) of the
  first workflow +10 → `import_artifacts` (preview confirmed with its code) → the workspace shows
  the verified change → `restore_backup` of that import → the original value is back →
  `set_active` to the other state and back → `tag_artifacts` with a unique `LIVE-<timestamp>`
  tag → the tag is removed again directly through the tag port (`tag --tagDelete`, not audited)
  and the owner's history shows it nowhere. A tag left by a failing run is removed in a
  `finally` block.
- Each write creates new versions of the test workflow in INUBIT, and every export appends to
  its check-in comment (INUBIT behaviour). Only counts and timings are printed, e.g.
  `[live] dev/node1: import, restore, set_active x2 and tag of one personal diagram group: 1 workflow(s), 4 module(s) tagged and untagged, 212 s`.

## Manual write checks (approval only)

There is **no automated live test for `restart_process` / `kill_process`**: every state-changing
StartCLI command (`processErrorStart`, `kill`) against a real server needs the explicit approval
of the user, given per command (tasks.md, live-system rules). The offline tests use a fake
StartCLI; the success fixtures `cli/processErrorStart_ok.*` and `cli/kill_ok.*` are
**synthetic** (their `.README` says "SYNTHETIC – verify in T115") until the manual check below
replaces them with recordings.

Procedure (001-T115, only on a development node, only with approval for each action):

1. The user names a process instance in `ERROR` on that node that may be restarted, and one that
   may be killed.
2. Temporarily set `write.enabled: true` for that group (e.g. `dev`) in the profile file
   `~/.config/inubit-mcp/<profile>.yaml` (confirmation stays `SERVER`) and restart Claude Code.
3. Run the 001 quickstart V9–V12 with the MCP tools: preview, then — after approval — the call with the
   code; reuse of a code and a restart of an instance not in `ERROR` must be refused.
4. Check the audit file `~/.inubit-mcp/<profile>/audit/audit-YYYY-MM.jsonl`: `CHALLENGE_ISSUED`,
   `PENDING`, `EXECUTED` per action, `REFUSED` for the refusals, no secret.
5. Record the real StartCLI outputs of the approved `processErrorStart <pid>` and `kill <pid>`
   with `tools/record-fixtures.sh` (one approved run each), replace the synthetic fixtures, delete
   their `.README` files, and re-run `V81ProcessControlAdapterTest` and `CliOutputClassifierTest`.
6. Revert the configuration (`write.enabled` back to `false`) and record the outcome in
   the 001 research.md → Spike results.

Related offline checks run in the default build: `performance/HealthOverviewPerformanceTest`
(SC-002: ten HTTPS WireMock servers, one hanging, overview within 10 s),
`security/NoSecretLeakTest` (SC-006) and `mcp/ProcessControlToolsTest` (write refusals, audit).

V13 (production refusal) needs no server contact: a call on a `production: true` group without
`write.productionOptIn` is refused with `PRODUCTION_PROTECTED` before anything is read.
