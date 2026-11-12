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
  `ArtifactExportLiveTest`, `QueueEntryLiveTest`).
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
- The server state a live test writes stays out of `~/.inubit-mcp` (feature 023): every
  `LiveTarget` keeps it in one temporary **live state directory**, printed at the start of the run
  as `[live] state directory: <path>` and deleted at JVM exit. It holds `home/` (the backups, the
  deployment ledger and lock files and the packages below `home/.inubit-mcp/<profile>/`),
  `audit/` (the audit records), `workspace/` (unless the test uses its own temporary workspace)
  and `knowledge/`. Configuration and credentials are still read from the real home.
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
| `live/QueueEntryLiveTest` (feature 020, T033) | runs only with `INUBIT_LIVE_QUEUE_ENTRY` (an existing ERROR entry) on a node whose group sets `unsupported.queueEntryRead: true`: `read_queue_entry` through the real MCP server with the production wiring; the entry's queue row has the same state, `startTime`, `fileSize` and `fileName` before and after (SC-004); data and variables are available; neither the captured log nor the result contains an original masked value or a known secret, and the log contains none of the returned values (SC-003). See [Queue entry read](#queue-entry-read-feature-020) | `POST /ibis/rest/log/queueLog?format=json` before and after (read-only queries); the version detection (`/system/info`); one run of the queue-entry helper, which logs in with the INUBIT client of the node's version (8.1: SOAP servlet, 9.0: REST) and downloads the entry's data and variables into a private temporary directory that is deleted afterwards (read-only); with `INUBIT_LIVE_RECORD_DIR` a second helper run for a fictitious file name |
| `live/ServerLogLiveTest` (feature 021, T054) | runs only with `INUBIT_LIVE_LOG_FOLDER` (a private folder outside every repository): `download_server_log` with `log: trace` and, if the node's group sets `unsupported.serverLogRead: true`, with `log: webservice`, through the real MCP server with the production wiring; each result matches the output schema and its file lies in the folder with the reported size and `rw-------`; a trace download through a copy of the configuration with a closed loopback port fails and leaves no file in the folder; the captured log contains no excerpt line. See [Server log download](#server-log-download-feature-021) | the version detection (`/system/info`); StartCLI `trace --download '<private dir>/trace.log'` (downloads the trace log without switching the trace state); with the opt-in one run of the INUBIT client helper that downloads `webservice.log`; both into a private temporary directory that is deleted after the file was placed in the folder (read-only); the forced failure contacts only `127.0.0.1` |

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
  file. The test works in a **temporary workspace**; the audit records and the backups go to the
  live state directory (see [Run](#run)) and are deleted with it at JVM exit.
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
it in INUBIT). The backups of the import and the restore and the audit records of
`import_artifacts` and `restore_backup` (the refused call as `REFUSED`) go to the live state
directory (see [Run](#run)) and are deleted with it at JVM exit. Only counts and timings are
printed, e.g.
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
  live state directory (see [Run](#run)) and are deleted with it at JVM exit. Only counts and
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
- **Nothing under `~/.inubit-mcp`**: the `DEPLOYMENT` backups of every written node and of the
  restore, the ledger and lock file of the target group (`deployments/<group>.ledger.json`,
  `deployments/<group>.lock`) and the audit records of every call (`deploy_release` with group and
  node records, `tag_artifacts`, `restore_backup`) go to the live state directory (see
  [Run](#run)) and are deleted with it at JVM exit.
- The temporary workspace is removed; the reports below it go with it.

## Queue entry read (feature 020)

`live/QueueEntryLiveTest` reads one existing ERROR entry of the Queue Manager with
`read_queue_entry` and checks that reading leaves it untouched (SC-004). It is **read-only**; run
it once per INUBIT version (8.1 and 9.0 test servers):

```bash
INUBIT_MCP_PROFILE=acme INUBIT_LIVE_NODE=test/node1 INUBIT_LIVE_QUEUE_ENTRY=1234 \
  mvn verify -Plive -Dtest=QueueEntryLiveTest
```

- `INUBIT_LIVE_QUEUE_ENTRY=<processId>[/<fileName>]` names the entry: the decimal `processId`
  (from `find_processes` or `query_logs` with `logType: queueLog`) and, if the process has several
  ERROR entries, its `fileName` after a `/` (as `read_queue_entry` accepts it: 1 to 255
  characters, no `/`, `\` or control characters). Without it the test is skipped; a malformed
  value fails naming the variable, never echoing the file name.
- The group of `INUBIT_LIVE_NODE` must set `unsupported.queueEntryRead: true`
  ([setup.md](setup.md)); otherwise the test fails before anything is contacted. Production groups
  are refused as for every live test. The node needs the StartCLI client settings (`cliHome`,
  `cliJavaHome` or `defaults.cliClients`, setup.md section 3) and its credentials.
- The test reads the process's queue rows (REST), calls `read_queue_entry` through the wired
  server, and reads the rows again: the row with the returned `fileName` keeps its state,
  `startTime` and `fileSize`; a row that is gone fails as "left the Queue Manager or was renamed".
  The result must have data and variables. The server log (logger `de.dadecker.inubit` at
  `DEBUG`) is captured during the test, and three leak checks run on it (SC-003); failure
  messages name fields and counts, never values:
  - **Returned values**: the log contains none of the stripped lines of the data text with at
    least 10 characters, nor of the unmasked, non-system variable values: those with at least 10
    characters anywhere, those with 4 to 9 characters as a whole token (not next to a letter,
    digit or underscore). Shorter lines such as closing tags or numbers also occur in unrelated
    log text and are not checked. Neither is a value that occurs within the entry's `processId` or
    `fileName` (for example a variable holding the process id): the INFO log names both by
    design (FR-018).
  - **Masked values**: the test keeps the raw `.wfsp` the helper wrote (also without
    `INUBIT_LIVE_RECORD_DIR`) and parses it. For every variable the result reports as
    `masked: true` (the result lists the variables in file order), the original value, stripped,
    must occur neither in the log nor in any text of the result, whatever its length: from 4
    characters anywhere, shorter ones as a whole token (a plain search for one to three characters
    would hit unrelated words). Only a blank value is skipped. If the result has masked variables
    but the helper wrote no parseable `.wfsp` with as many variables as the result reports, the
    test fails.
  - **Known secrets**: no log line and no text of the result contains a secret registered in the
    live scrubber (the node password, the trust-store password and their encoded forms), whatever
    its length.
- It runs on 8.1 and 9.0 nodes alike: the production declaration supports `read_queue_entry` on
  both since the 9.0 recording (tasks.md T038).
- A read that started no queue-entry helper fails (nothing to check the masked values against,
  nothing to record).
- It prints one line, e.g.
  `[live] read_queue_entry test/node1 process 1234: data 2048 bytes (text), 12 variables, entry unchanged`.

### Recording the helper output (`INUBIT_LIVE_RECORD_DIR`)

To produce the fixture recording of research D-18 (quickstart "Live check" step 5), add
`INUBIT_LIVE_RECORD_DIR=<private directory outside the repository>`:

- The path is resolved to its real path: its nearest existing ancestor with symbolic links and
  the file system's letter case resolved, plus the rest. A relative path, one with `..`
  segments (after a symbolic link, `..` leads to the link target's parent), one inside the
  repository (also resolved, so a link into it or a different letter case on a case-insensitive
  file system is caught) and one inside any git working tree (a directory or a parent with a
  `.git` entry) are refused; the recording is written below the real path. Each run creates a
  new owner-only
  directory `queue-entry-…` below it (POSIX `rwx------`, files `rw-------`; on Windows an access
  list for the current user and SYSTEM only). On a file system with neither, the test fails and
  records nothing.
- The run writes, owner-only, the raw output of the queue-entry helper as case files:
  `error_entry.frames` (the helper's stdout), `error_entry.exit` (its exit code),
  `error_entry.data` and `error_entry.wfsp` (the raw data and variables). The request on stdin,
  which carries the password, is never recorded. The files hold **raw entry content**: neutralize
  them as in research D-18 before anything is copied into the repository, and delete them
  afterwards.
- It also runs one negative case directly on the adapter: the entry's workflow with the
  fictitious file name `Module-0000(0)@0@00000000`. The login succeeds, so it causes no
  `AUTH_FAILED` back-off; nothing is changed. Its helper output is recorded as
  `not_found_file.*`, and the summary line ends with the outcome per part, e.g.
  `; not-found case data NotFound, variables NotFound; recorded`.

## Server log download (feature 021)

`live/ServerLogLiveTest` downloads the server logs of one node with `download_server_log` and
checks the files it writes (research D-24, quickstart "Live check"). It is **read-only**: the
trace log is downloaded without switching the trace state. Run it once per INUBIT version (8.1
and 9.0 test servers), only after the concrete run was approved:

```bash
INUBIT_MCP_PROFILE=acme INUBIT_LIVE_NODE=test/node1 \
  INUBIT_LIVE_LOG_FOLDER=~/inubit-logs/021-live \
  mvn verify -Plive -Dtest=ServerLogLiveTest
```

- `INUBIT_LIVE_LOG_FOLDER` is the target folder of the downloads, a private folder outside every
  repository; the tool creates it owner-only if it is missing. It is resolved and refused like
  `INUBIT_LIVE_RECORD_DIR` (see [Recording the helper output](#recording-the-helper-output-inubit_live_record_dir)):
  a relative path, one with `..` segments, one inside this repository and one inside any git
  working tree fail naming the variable. Without it (or without `INUBIT_LIVE_NODE`) the test is
  skipped. The downloaded files stay in the folder for the checks by hand; they hold clear-text
  server logs, so delete them afterwards.
- The trace log is always downloaded. The web service log is downloaded too if the group of
  `INUBIT_LIVE_NODE` sets `unsupported.serverLogRead: true` ([setup.md](setup.md)). Production
  groups are refused as for every live test. The node needs the StartCLI client settings (setup.md
  section 3) and its credentials. A log the node's INUBIT version does not support fails the test
  with the tool's error.
- Per log, the result must match the output schema of `download_server_log`, name the node and
  the log (for `webservice` also the INUBIT node), and its `file` must be a regular file directly
  in the folder with `sizeBytes` bytes and the permissions `rw-------` (on Windows an access list
  for the current user and SYSTEM only).
- **Forced failure**: one more trace download runs through an in-memory copy of the configuration
  in which the node's `baseUrl` and explicit StartCLI address point at a closed port on
  `127.0.0.1`. It must fail, and the folder must hold the same entries before and after it. Nothing
  but `127.0.0.1` is contacted; the real configuration file is never changed.
  With `versionLine: AUTO` the call usually fails already at the version detection, before any
  temporary or target file exists; with a pinned `versionLine` it fails at the StartCLI download.
- The server log (logger `de.dadecker.inubit` at `DEBUG`) is captured during the test; it must
  contain none of the stripped excerpt lines with at least 10 characters (shorter lines such as
  brackets also occur in unrelated log text). The failure message counts, never quotes.
- It prints one line per log, e.g.
  `[live] download_server_log test/node1 trace: 52341 bytes, window 200` and
  `[live] download_server_log test/node1 webservice: 1874 bytes, window 23, INUBIT node node1`;
  without a text excerpt `window none (<kind>)`.
- To be checked by hand afterwards (quickstart "Live check" step 3): the Workbench shows the same
  trace state as before (SC-005).

### Recording the StartCLI and helper output (`INUBIT_LIVE_RECORD_DIR`)

For the fixture recordings of research D-21, add `INUBIT_LIVE_RECORD_DIR=<private directory
outside every repository>`, resolved and refused as for the queue entry recording. Each run
creates a new owner-only directory `server-log-…` below it and writes, owner-only, per log the
raw output of the process that downloaded it, before the result is checked:
`trace/<case>.stdout`, `.stderr` and `.exit` (StartCLI) and `webservice/<case>.frames` (the
helper's stdout) and `.exit`. `<case>` is `ok`, `empty` (a 0-byte log) or `error` (the call
failed). The stdin of either process, which carries the password, is never recorded. The files
hold **raw server output**: neutralize them as in research D-21 before anything is copied into
the repository, and delete them afterwards.

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
2. Compute the SHA-256 of the test password's UTF-8 bytes; each command reads the password from a
   prompt, so it appears neither on the command line nor in the history:

   ```bash
   # macOS
   read -rs PW && printf '%s' "$PW" | shasum -a 256 | cut -d' ' -f1; unset PW
   ```

   ```bash
   # Linux
   read -rs PW && printf '%s' "$PW" | sha256sum | cut -d' ' -f1; unset PW
   ```

   ```powershell
   # Windows (PowerShell 7)
   $pw = Read-Host -MaskInput; [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($pw))); Remove-Variable pw
   ```

3. Run, with the username and that hash as the expectation (the password itself is never passed):

   ```bash
   INUBIT_LIVE_STORE=1 INUBIT_LIVE_STORE_USERNAME='<test username>' \
     INUBIT_LIVE_STORE_PASSWORD_SHA256='<hash from step 2>' mvn -q verify -Plive-store
   ```

   The test checks that both entries are found, the username matches, the password's hash matches
   and contains a non-ASCII character, and that the missing entry is "not found". A failure names
   only the entry or variable, never a value. Entries created with `security add-generic-password`
   (step 1) trust `/usr/bin/security`, the program the server reads with, so macOS shows no access
   dialog here.
4. **Access denied (manual):**
   - macOS: make the Keychain ask first. In Keychain Access open the item `inubit-mcp` with the
     account `INUBIT_LIVETEST_DEV_PASSWORD`, tab "Access Control", remove `security` from "Always
     allow access by these applications" and keep "Confirm before allowing access"; save. (Or
     create that entry with `security add-generic-password -U -s inubit-mcp -a
     INUBIT_LIVETEST_DEV_PASSWORD -T "" -w`, which trusts no application.) Repeat step 3 and
     choose "Deny": the line shows `failed (ACCESS_DENIED)` for that entry and the test fails.
   - Linux: lock the collection that holds the entries (e.g. Seahorse → the keyring → Lock, or
     log out of and back into the keyring), repeat step 3 and dismiss the unlock prompt. Record
     what the line shows; it may be "not found" (setup.md section 4).
   - Windows: the Credential Manager has no per-entry access control for the user's own
     credentials, so there is no such step.
5. Record the outcome per system (counts and answer kinds only) in
   `specs/011-os-credential-stores/quickstart.md`, then delete the two entries.

The selection of the stores by the live tests is covered offline by `live/LiveTargetTest`
(`theWiringReadsEachNodeFromItsOwnSourceLikeTheServer`); the opt-in gate by
`live/CredentialStoreLiveOptInTest`.

## Partner Management live check (feature 018)

`find_partners` and the `partnerUsage` of `get_inventory_item` have no automated live test. The
operator check (018 quickstart.md section 3, task T034) is read-only: it contacts the stages
named below through the REST model export only, never a production stage, and writes nothing to
INUBIT. Record the result here in neutralized form only (ACME, `*.example.test`, no real partner,
host, login or diagram names).

Result of 2026-11-11, with a jar built from `main` at 97c697a (version string 0.8.1; the release
0.8.1, cut from `release/0.8.x`, does not contain feature 018), owner of the system diagrams as
configured (`inventory.owner`):

1. `find_partners` with `target: qa` (one server, INUBIT 8.1) and `search: ACME-0001` (a partner
   that exists on `qa` and `int/node1`): **passed**. The partner is returned once, in full, with
   its server URLs, logins, protocol and authentication settings in clear; `Password`,
   `WSSecurityPassword` and `SSLKeyStore` show only `masked` and `set`. List mode on `qa` and
   `int/node1` read 9 and 7 system diagrams and answered within 1.5 s each.
2. The same with `compareWith: int/node1`: **passed**. `Request.URI` and its overrides are
   reported per server (`differs`: `https://api.acme.example.test/…` on `qa`,
   `https://inubit.int.example.test:8443/…` on `int/node1`), `Login` once (`equal`), the REST
   `Password` with `sameOn: [["qa/node1","int/node1"]]`; an entry configured on `qa` only carries
   `onlyOn: ["qa/node1"]`. The repeated call came from the cache (`fromCache: true`, about 20 ms).
3. `get_inventory_item` of the technical workflow the REST entry references (`Workflow-0001` on
   `qa`): **passed**. `partnerUsage` lists its four `ISPartnerProtocol.*` variables,
   `partnerManagementNode: true` and the cloud of `SystemDiagram-01` that references it.
4. Audit log: **passed**. One record per server and call, sharing the `callId`; `details` holds
   the counts only (`fromCache`, `systemDiagramsRead`, `partnersReturned`, `valuesMasked`), a
   failed server is `FAILED`; no setting value appears in the audit file.

Findings (follow-ups, not regressions of the checked steps):

- On `dev` (INUBIT 8.1) one system diagram holds a communication cloud without `ModuleName`; the
  reader rejects it, so the **whole server** fails with `UNEXPECTED_RESPONSE` ("A communication
  cloud has no ModuleName") and no partner of the other system diagrams is returned (documented
  behaviour, docs/tools.md, but a single odd diagram should rather be one `unreadableDiagrams`
  entry).
- On `int/node2` (INUBIT 9.0) the REST model export is not a ZIP archive, so the server fails with
  `UNEXPECTED_RESPONSE` ("The diagram export … is not a ZIP archive"): reading the Partner
  Management does not work on INUBIT 9.0 as it is. The failed server's `warnings` are empty: the
  version warning ("not verified for partner management") is dropped when the read fails.

Both findings are fixed for 0.9.0. A system diagram without a readable Partner Management is now
one `unreadableDiagrams` entry and a failed server keeps its version warning (#78). The INUBIT 9.0
export was not a ZIP archive because INUBIT 9.0.10 sends it with `Content-Encoding: gzip`; the REST
client decodes it since #77 (feature 017, F-3), so `find_partners` on INUBIT 9.0 needs that fix.
With it, `find_partners` read every system diagram of the owner on the 9.0.10 node
(`specs/017-inubit-9-support/quickstart.md`, re-check of 2026-11-11).

## Network drive check on Windows (feature 015)

`export_archives` refuses a target folder on a mapped network drive (research R-10, FR-008): the
server asks Windows for the drive type (`GetDriveTypeW("<X>:\")` of `kernel32`, through JNA) and
refuses `DRIVE_REMOTE`. The offline build covers this only through a fake drive-type API
(`adapter/os/WindowsNetworkPathsTest`) and a fake probe (`application/TargetFolderRulesTest`); the
native call itself is checked by hand on a Windows machine. The refusal happens before StartCLI
runs, so no archive is exported and no secret is read from INUBIT. If the native call cannot be
loaded, the check answers "local" for every drive letter and the server logs one warning ("The
Windows drive-type API could not be loaded …"); look for it in the server log during this check.

1. Map a share as a network drive that is not otherwise in use, e.g. `Z:` (any share you may
   write to; the folder is never created):

   ```bat
   net use Z: \\fileserver.example.test\share
   ```

2. Start the server on that machine with a configuration that has a non-production group, and
   call `export_archives` for one of its nodes with `acknowledgeClearTextSecrets: true` and, in
   turn, `targetFolder` `Z:\inubit-archives`, `z:/inubit-archives` and
   `\\fileserver.example.test\share\inubit-archives`. Each call must end with `INVALID_INPUT`
   naming the network-path rule ("is a network path (Z: is a mapped network drive)", for the UNC
   path "is a network path (a UNC path)"), not "relative"; the audit file holds a `REFUSED` record
   per call, and nothing appears on the share.
3. Counter-check: the same call with a new folder on a local disk (e.g. `C:\Temp\inubit-archives`)
   is not refused by the network-path rule. A `subst` of a local folder (`subst Y: C:\Temp`, then
   `Y:\inubit-archives`) counts as local; a `subst` of a UNC path counts as remote, as Windows
   reports it.
4. Remove the mapping (`net use Z: /delete`, `subst Y: /d`) and record the outcome (refused or
   not, per path form; no paths of the real share) in `specs/015-workbench-archives/quickstart.md`.

## StartCLI live check on Windows (feature 013)

The offline build runs every StartCLI-backed tool on Windows in CI against a substitute of
`startcli.bat` (`WindowsReadOnlyToolsTest`, `WindowsWriteToolsTest` and the other Windows-only
tests). What only the real Windows client shows is checked by hand by a Windows user, on a
non-production node and with the person's approval in the chat for each step:

- whether the client reads the password from standard input (if it waits at its password prompt
  instead, the call ends with a `TIMEOUT` naming the password prompt; stop and report it, a
  password argument is never a fallback);
- how `startcli.bat` forwards arguments, how StartCLI treats `\` inside a quoted command value,
  and how many characters `startcli.bat` adds to the command line;
- the encoding of the output in the console code page of the machine (`chcp`).

`tools\record-fixtures.ps1` records these facts read-only (`-List` prints its cases; the
recording call is in `specs/013-windows-startcli/quickstart.md`). It starts `startcli.bat` the way
the server does, only for the groups in `RECORD_ALLOWED_GROUPS` and the hosts in
`RECORD_ALLOWED_HOSTS`, and writes neutralised files to `src/test/resources/fixtures/windows/`.

Steps, in PowerShell:

1. `java -jar inubit-mcp-server-<version>.jar --profile acme --check-config`: the node line shows
   `cli: available` and the client version, with no Windows warning.
2. Read-only first: `InventoryLiveTest` and `ArtifactExportLiveTest`, with `INUBIT_MCP_PROFILE`
   and `INUBIT_LIVE_NODE` set as in [Run](#run):

   ```powershell
   $env:INUBIT_MCP_PROFILE = 'acme'; $env:INUBIT_LIVE_NODE = 'test/node1'
   mvn verify -Plive "-Dtest=InventoryLiveTest,ArtifactExportLiveTest"
   ```

   `ArtifactExportLiveTest` needs `INUBIT_LIVE_DIAGRAM_GROUP`. Then `CredentialStoreLiveTest` as
   in [Credential store live check](#credential-store-live-check-feature-011). Afterwards no
   `inubit-mcp-export-*` directory is left below `java.io.tmpdir`.
3. Only on a development node or an approved target, with approval: `DevelopmentLiveTest` and
   `DeploymentLiveTest` ([Development live test](#development-live-test-feature-004),
   [Deployment live test](#deployment-live-test-feature-005)). The backups of the run lie in the
   live state directory, below `<live state directory>\home\.inubit-mcp\<profile>\backups` (path
   from the `[live] state directory:` line), and are deleted at JVM exit, so check them while the
   run is still going: debug `DeploymentLiveTest` in the IDE with a breakpoint on its
   `restore_backup` call, check the access list of a backup there with `icacls` (the account of
   the server and SYSTEM only, no inherited entries), then let the test finish.
4. Record per step the Windows version, the client version, the code page and the outcome kind
   (no customer data) in the "Validation record" of
   `specs/013-windows-startcli/quickstart.md`. If the separator, the handling of `\`, the
   encoding or the length margin differ from the defaults, a follow-up task adjusts the rules.

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

## Recording administration commands (feature 022)

The live checks L-1 … L-18 of feature 022 (`specs/022-full-interface-coverage/research.md`,
"Live checks") record administration commands on non-production servers of 8.1 and 9.0. Tasks
marked [LIVE] run against such a server; [LIVE-W] marks a command that changes it.

### Raw outputs

- Every raw output of a [LIVE] task goes to `~/inubit-mcp-recordings/022/`: StartCLI stdout,
  stderr and exit code, REST responses, the before and after state of a (W) check, and the
  `INUBIT_LIVE_RECORD_DIR` of a live test.
- The folder is outside every repository and every git working tree. Create it owner-only:

  ```bash
  mkdir -p ~/inubit-mcp-recordings/022
  chmod 700 ~/inubit-mcp-recordings ~/inubit-mcp-recordings/022
  ```

- Raw files never enter a repository or a commit. Only the neutralized case files
  (`tools/neutralize.py`, then `tools/check-identifiers.py`) are copied to
  `src/test/resources/fixtures/{v8_1,v9_0}/{cli,rest}/` as `admin-<entry-slug>[-<variant>].*`
  (layout in the fixture READMEs, section "Administration cases (feature 022)").
- `tools/record-fixtures.sh` is the exception: it keeps its raw output in its own temporary
  directory and writes only anonymized output straight into the fixture folders. The private
  folder is for everything else: StartCLI or REST output captured by hand, the before and after
  states of a [LIVE-W] command, and `INUBIT_LIVE_RECORD_DIR`.

### Approval of a [LIVE-W] command

Before each [LIVE-W] command (FR-026), the operator writes one approval line in the chat. One
line approves exactly one command on one server:

```text
APPROVE <group>/<node> | <command> | change: <expected change> | undo: <how to undo it>
```

- `<group>/<node>`: the server, a node of a non-production group.
- `<command>`: the complete command as it runs (StartCLI command with its options, or HTTP
  method and path), never a password.
- `change:` what the command changes on the server, as the before and after state should show.
- `undo:` the command or step that restores the previous state.

Example:

```text
APPROVE test/node1 | trace on | change: server trace switches from off to on | undo: trace off
```

- The agent runs only the command of the line, only on that server. Another command, another
  server or a second run needs a new line. The approval is noted in the "Validation record" of
  `specs/022-full-interface-coverage/quickstart.md`.
- A [LIVE] task that only reads needs no undo. When the agent runs it, the operator's approval
  names the server, the command and the output folder below `~/inubit-mcp-recordings/022/`.
