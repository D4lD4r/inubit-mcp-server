# Live tests

The default build (`mvn verify`) is fully offline: the REST and CLI adapters are tested against
recorded fixtures and WireMock, and no INUBIT server is contacted. Tests tagged `@Tag("live")`
are excluded from it (`surefire.excludedGroups=live`).

Live tests are **opt-in** and **never run against production** (Constitution III). All of them are
read-only except the development live test of feature 004 (see
[Development live test](#development-live-test-feature-004)), which writes to dedicated test
workflows of a personal diagram group on a development node only, and with its repository case
(see [Repository files](#repository-files-feature-008)) to one probe file in a personal test
repository folder of that node, and the deployment live test of
feature 005 (see [Deployment live test](#deployment-live-test-feature-005)), which deploys one
test diagram group into an approved, non-production test target group.

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
- A single test: add `-Dtest=HealthLiveTest` (or `DiagnosisLiveTest`, `InventoryLiveTest`,
  `ArtifactExportLiveTest`).
- `INUBIT_LIVE_DIAGRAM_GROUP` (optional) names a diagram group of the node's `inventory.owner`
  for `ArtifactExportLiveTest`; without it that test is skipped. Choose a small group.
- `INUBIT_LIVE_GROUP` (optional, `InventoryLiveTest` only) names the diagram group whose version
  history is exported; without it the group of the first technical diagram is used.
- `InventoryLiveTest` and `ArtifactExportLiveTest` need the StartCLI settings (`cliHome`,
  `cliJavaHome`, setup.md section 3) and an `inventory.owner` for the node; each runs two
  StartCLI exports (`InventoryLiveTest` about 30–60 s); `HealthLiveTest` and `DiagnosisLiveTest`
  need REST only.
- The configuration is the real one, found like the server finds it without `--config` or
  `--profile` ([setup.md](setup.md), section 3): `INUBIT_MCP_CONFIG` (a path), then
  `INUBIT_MCP_PROFILE` (a profile name for `~/.config/inubit-mcp/<name>.yaml`, which must declare
  that `profile.name`), then `~/.config/inubit-mcp/config.yaml`. So `INUBIT_MCP_PROFILE` selects
  the customer. The file must validate without errors, exactly as with
  `java -jar … --profile <name> --check-config` (including the comparison with the other profile
  files in the default configuration directory).
- Credentials are read exactly as the server reads them (feature 011): from the credential store
  of this system (the profile's entries, setup.md section 4) or from AWS Parameter Store for
  nodes with that source (log in to AWS first). They are never passed on the command line or as
  environment variables. On macOS the first read may show a Keychain access dialog.
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
exactly this scenario — and only on a dedicated, disposable test diagram group with a dedicated
test workflow (never production). The owner may be a user or a user group (research D-26: every
import uses `--importUser`); a user group's diagram groups are shared, so nothing is chosen by
default:

```bash
INUBIT_MCP_CONFIG=/path/to/acme-live-copy.yaml INUBIT_LIVE_DEV_NODE=dev/node1 \
  INUBIT_LIVE_DEV_OWNER=OWNERS INUBIT_LIVE_DEV_DIAGRAM_GROUP=GRP-01 \
  INUBIT_LIVE_DEV_WORKFLOW=Workflow-0001 \
  mvn verify -Plive -Dtest=DevelopmentLiveTest
```

- Without `INUBIT_LIVE_DEV_NODE`, `INUBIT_LIVE_DEV_OWNER`, `INUBIT_LIVE_DEV_DIAGRAM_GROUP` **and**
  `INUBIT_LIVE_DEV_WORKFLOW` the test is skipped: the diagram group and the workflow to change are
  always named explicitly (there is no "first workflow of the group" default any more). The tag
  step always covers the whole diagram group (INUBIT tags per group).
- It **fails before anything is written** unless the node is a development node
  (`development.enabled: true`; the selection is covered offline by `LiveTargetTest`). A
  production group is refused as for every live test.
- Use a temporary copy of the profile if it needs `development.enabled: true`; never edit the
  real file under `~/.config/inubit-mcp/` for a test run. Keep the copy outside that directory and
  point the run at it with `INUBIT_MCP_CONFIG` as above (it comes before `INUBIT_MCP_PROFILE`); the
  copy keeps the `profile.name`, so the validation only warns that it shares the name with the real
  file. The test works in a **temporary workspace**; the audit records go to the profile's audit
  directory and the backups to `~/.inubit-mcp/<profile>/backups` as for every development call.
- Scenario (research D-23): `export_artifacts` of the group → one layout value (`xPos`) of the
  named workflow +10 → `import_artifacts` (preview confirmed with its code) → the workspace shows
  the verified change → `restore_backup` of that import → the original value is back →
  `set_active` to the other state and back → `tag_artifacts` of the whole diagram group with the
  fixed tag `LIVE-TEST`. The tag is **never removed** (StartCLI removes a tag only for the whole
  owner, research D-26): it stays as a harmless label on the group's current versions, and the
  next run reuses the name, which moves it to the then current versions.
- Each write creates new versions of the test workflow in INUBIT, and every export appends to
  its check-in comment (INUBIT behaviour). Only counts and timings are printed, e.g.
  `[live] dev/node1: import, restore, set_active x2 and tag of one test diagram group: 1 workflow(s), 4 module(s) carry LIVE-TEST, 212 s`.

### Repository files (feature 008)

The repository case of `live/DevelopmentLiveTest`
(`importAndRestoreAProbeFileInThePersonalTestRepositoryFolder`) exercises repository files against
the same development node. It **writes to INUBIT**, so the same rules apply: it runs only when
started explicitly — starting it is the approval of exactly this scenario in exactly the named
folder — and never on production:

```bash
INUBIT_MCP_CONFIG=/path/to/acme-live-copy.yaml INUBIT_LIVE_DEV_NODE=dev/node1 \
  INUBIT_LIVE_DEV_OWNER=jdoe INUBIT_LIVE_DEV_REPOSITORY_FOLDER=live-test \
  mvn verify -Plive -Dtest='DevelopmentLiveTest#importAndRestoreAProbeFileInThePersonalTestRepositoryFolder'
```

Prerequisites:

- A **temporary copy** of the profile in which the development node has
  `development.repository: WRITE` (setup.md, `development.repository`); never edit the real file
  under `~/.config/inubit-mcp/` for a test run. Point the run at the copy with `INUBIT_MCP_CONFIG`
  as above (outside that directory; see the feature-004 section). Without `WRITE` on the node the
  test **fails before anything is written**.
- Your **personal test repository folder** on that node, below `/Root/<owner>/`, which already
  exists. `INUBIT_LIVE_DEV_REPOSITORY_FOLDER` names it relative to the owner's repository root
  (`live-test` stands for `/Root/jdoe/live-test`). Never name a shared folder or the owner's root.
- Without `INUBIT_LIVE_DEV_NODE`, `INUBIT_LIVE_DEV_OWNER` **and**
  `INUBIT_LIVE_DEV_REPOSITORY_FOLDER` the case is skipped; no folder is chosen by default. The
  diagram-group case of the same class needs its own variables, so either case runs alone.

The test writes only one file, `inubit-mcp-live-probe.xsd` in that folder: a small synthetic
schema with the run's time stamp. Scenario (quickstart.md of feature 008, "Live"), in a
**temporary workspace**:

1. `export_artifacts(repositoryPaths: [<folder>])`: the folder's files below
   `repository/Root/<owner>/<folder>/` of the workspace.
2. The probe is changed in the workspace (created on the first run) and imported alone with
   `import_artifacts(repositoryPaths: [<folder>/inubit-mcp-live-probe.xsd])`: the preview lists it
   under `modify` (`create` on the first run), names the `repositoryDiff` report and carries a
   server code; the call with the code is `EXECUTED` with a `backupRef`.
3. The same node with `development.repository: READ_ONLY` (an in-memory copy of the
   configuration and a new server, as after editing the copy and restarting) refuses another
   change of the probe with `REPOSITORY_WRITE_DENIED`; nothing is sent.
4. Back on `WRITE`, `restore_backup` with the `backupRef` of step 2 re-imports the previous
   content, confirmed with its server code and verified. On the first run the probe was created:
   the restore cannot delete it and lists it in `createdNotRemoved`.
5. A second export of the folder shows every other file of the folder byte for byte as in step 1
   and the probe with its previous content (or, after the first run, with the content of step 2).

What stays: the probe file in your test folder (never deleted; every run creates new versions of
it in INUBIT), the backups of the import and the restore under `~/.inubit-mcp/<profile>/backups`
and the audit records of `import_artifacts` and `restore_backup` (the refused call as `REFUSED`),
as for every development call. Only counts and timings are printed, e.g.
`[live] dev/node1: repository probe modified, refused read-only, restored; 3 file(s) in the test folder, 95 s`.

## Deployment live test (feature 005)

`live/DeploymentLiveTest` exercises `deploy_release` against a real chained **test target group**.
It **writes to INUBIT** on every node of that group, so it runs only when started explicitly —
starting it is the approval of exactly this scenario on exactly the named group, diagram group,
tag and owner — and the user must have approved that target group beforehand:

```bash
INUBIT_MCP_PROFILE=acme INUBIT_LIVE_DEPLOY_TARGET=int INUBIT_LIVE_DEPLOY_DIAGRAM_GROUP=GRP-01 \
  INUBIT_LIVE_DEPLOY_TAG=LIVE-DEPLOY INUBIT_LIVE_DEPLOY_OWNER=OWNERS \
  mvn verify -Plive -Dtest=DeploymentLiveTest
```

- `INUBIT_LIVE_DEPLOY_TARGET` is the id of ONE group with a `deploy` record in mode `EXECUTE`;
  without it (or without `INUBIT_LIVE_DEPLOY_DIAGRAM_GROUP`, `INUBIT_LIVE_DEPLOY_TAG` and
  `INUBIT_LIVE_DEPLOY_OWNER`) the test is skipped. A node id, a group without `deploy`, a
  **package-only** group and any **production** group are refused before anything is contacted
  (covered offline by `LiveTargetTest`).
- The release is the diagram group `INUBIT_LIVE_DEPLOY_DIAGRAM_GROUP` with the tag
  `INUBIT_LIVE_DEPLOY_TAG` on the source group (`deploy.from`). If the source group has exactly one
  node and it is a development node, the test tags the group there itself (`tag_artifacts`);
  otherwise tag it on every source node before. The preview must show exactly that diagram group
  — use a tag that no other diagram group of the owner carries.
- Scenario: (tag) → preview (executable) → execute with the code (every node `DEPLOYED` or
  `UNCHANGED`) → a second preview and execution find every node `UNCHANGED` (only the tag) →
  `restore_backup` of the first written node with its deployment backup → on every target node
  no diagram outside the test group appeared or disappeared, and no module that was not deployed
  changed (`list_inventory`). The restored node keeps the state before the deployment; deploy
  again to bring it to the release.
- The test works in a **temporary workspace**; the audit records, backups and the ledger go to the
  profile's directories under `~/.inubit-mcp/<profile>/` as for every deployment. Only counts and
  timings are printed, e.g.
  `[live] int: deploy, redeploy (unchanged) and restore of one test diagram group on 2 node(s): 5 artifact(s) imported, 340 s`.

### What stays after the deployment live test

- **On the target nodes**: the new versions the deployment and the restore created, and every
  artifact the deployment **created** (new workflows, modules, repository files) — nothing is
  ever deleted, and `restore_backup` keeps created artifacts (it lists them). The restored node
  holds the state before the deployment, the other nodes the release.
- **The tag** on the source (if the test tagged it) and on every target node's deployed diagram
  group — never removed (StartCLI removes tags only owner-wide); the next run with the same tag
  moves it.
- **Under `~/.inubit-mcp/<profile>/`**: the `DEPLOYMENT` backups of every written node and of the
  restore (`backups/`, 30-day retention, the newest per scope kept), the ledger and lock file of
  the target group (`deployments/<group>.ledger.json`, `deployments/<group>.lock`), and — never for
  this test, which refuses package-only groups — `packages/`.
- **Audit records** (`audit/audit-YYYY-MM.jsonl`) of every call: `deploy_release` (group and node
  records), `tag_artifacts`, `restore_backup`.
- The temporary workspace is removed; the reports below it go with it.

## Credential store live check (feature 011)

`CredentialStoreLiveTest` reads entries of the fictitious profile `livetest` from the real
credential store of the machine it runs on (macOS Keychain, Windows Credential Manager, Linux
Secret Service), through the adapter the server uses. It never contacts INUBIT and never prints a
value; it prints only what kind of answer each entry gave. It runs only with the profile
`live-store` **and** `INUBIT_LIVE_STORE=1` (it is excluded from the default build and from
`-Plive`).

1. Create two entries with a test username and a test password that contains `ä€` (to cover the
   encoding); the commands prompt for the value (setup.md section 4):

   ```bash
   # macOS
   security add-generic-password -U -s inubit-mcp -a INUBIT_LIVETEST_DEV_USERNAME -w
   security add-generic-password -U -s inubit-mcp -a INUBIT_LIVETEST_DEV_PASSWORD -w
   ```

   ```bat
   :: Windows
   cmdkey /generic:inubit-mcp:INUBIT_LIVETEST_DEV_USERNAME /user:INUBIT_LIVETEST_DEV_USERNAME /pass
   cmdkey /generic:inubit-mcp:INUBIT_LIVETEST_DEV_PASSWORD /user:INUBIT_LIVETEST_DEV_PASSWORD /pass
   ```

   ```bash
   # Linux
   secret-tool store --label='inubit-mcp INUBIT_LIVETEST_DEV_USERNAME' service inubit-mcp account INUBIT_LIVETEST_DEV_USERNAME
   secret-tool store --label='inubit-mcp INUBIT_LIVETEST_DEV_PASSWORD' service inubit-mcp account INUBIT_LIVETEST_DEV_PASSWORD
   ```

   The entry `INUBIT_LIVETEST_DEV_NODE9_PASSWORD` must not exist.
2. Run, with the username and the SHA-256 of the password's UTF-8 bytes as the expectation (the
   password itself is never passed):

   ```bash
   INUBIT_LIVE_STORE=1 INUBIT_LIVE_STORE_USERNAME='<test username>' \
     INUBIT_LIVE_STORE_PASSWORD_SHA256="$(printf '%s' '<test password>' | shasum -a 256 | cut -d' ' -f1)" \
     mvn -q verify -Plive-store
   ```

   (Type the password into a file or use your shell's history settings if it must not appear in
   the history.) The test checks that both entries are found, the username matches, the password's
   hash matches and contains a non-ASCII character, and that the missing entry is "not found".
   On macOS answer the access dialog with "Allow".
3. **Access denied (manual):** on macOS repeat step 2 and choose "Deny" in the dialog: the test
   fails with `failed (ACCESS_DENIED)` for that entry. On Linux lock the keyring and dismiss the
   unlock prompt; record what the line shows (it may be "not found", see setup.md section 4).
   The Windows Credential Manager has no such step.
4. Record the outcome per system (counts and answer kinds only) in
   `specs/011-os-credential-stores/quickstart.md`, then delete the two entries.

The selection of the stores by the live tests is covered offline by `live/LiveTargetTest`
(`theWiringReadsEachNodeFromItsOwnSourceLikeTheServer`); the opt-in gate by
`live/CredentialStoreLiveOptInTest`.

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
   `~/.config/inubit-mcp/<profile>.yaml` (confirmation stays `SERVER`) and reconnect the server
   (`/mcp`).
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
