# Quickstart: Validating INUBIT 9 Support

How to check 017 offline (automated) and live (by the person, in the joint 0.8.x live test).

## Prerequisites

- Branch `017-inubit-9-support` built offline:
  `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-017-tmp` and
  `python3 -I -m unittest discover -s tools/tests`.
- Live only: an INUBIT 9.0.x test node, its 9.0 client with JDK 21, and the 8.1 client with JDK 17
  on the same machine (macOS/Linux and Windows).

## Offline validation

| Scenario (spec) | Expected |
|---|---|
| `defaults.cliClients` with `"8.1"`/`"9.0"` loads; keys `"9"`, `"9.x"`, `"9.00"`, duplicates, missing `home`, entry in a group are refused (FR-003) | `ConfigLoaderTest` green; error texts name the path, never a value other than the key |
| choice table: node/group `cli.home` > entry > `defaults.cliHome`; JDK of the entry only with its home; `V9_X`/unknown → version-less (FR-003, FR-005, D-3) | `EffectiveNodeConfigTest` (cliFor) green |
| address: none → servlet path for 8.1/unknown, base URL for 9.x; explicit `cli.url` wins (FR-004) | `EffectiveNodeConfigTest`, `CliRunnerTest` argument arrays |
| `AUTO` node detected 8.1, later 9.0: 8.1 client then 9.0 client, each checked once with `-v` (US1, FR-017) | `CliRunnerSelectTest`, `CliClientSelectorTest`, `VersionGateTest`, `CliClientProbeTest` |
| `get_health` learns 9.0 in the call: client part reports the 9.0 client's check, or "not checked yet" when out of budget (FR-017) | `HealthServiceTest` |
| no client for 9.0 with `cliClients` configured → `CLI_UNAVAILABLE` naming `defaults.cliClients."9.0"`; 0.8.0 text otherwise (FR-005, FR-009) | `CliRunnerTest`, golden texts |
| explicit servlet `cli.url` on a 9.x node → warning on the result and in `--check-config` (FR-010, SC-004) | `VersionGateTest`, `ConfigValidatorTest` |
| `V9_0` and `"9.0.7"` admit every 8.1 capability without warning; `V9_X`, 9.1 unchanged (FR-001, FR-002, FR-011) | `CapabilityMatrixTest`, `VersionGateTest`, tool tests with 9.1.0 |
| 9.0 REST fixtures (JSON health/ready/metrics/log, XML info/models, export ZIP) through the 8.1 adapters with the right `Accept` (FR-007) | `V90RestContractTest`, `V90CliContractTest`, WireMock header matchers |
| JSON error bodies 401/403/404/500/503 → existing codes, scrubbed message excerpt (FR-008, SC-005) | `InubitHttpClientTest` |
| `startcli -v` of a 9.0 client, upper- and lower-case `INUBIT` (FR-012) | `CliVersionProbeTest` |
| `AUTH_FAILED` on a 9.x address names the internal user management (FR-013) | `CliOutputClassifierTest` |
| `list_nodes.startCli` for pinned, detected and undetected nodes; schema validates (FR-015) | `ListNodesToolTest` (incl. output schema) |
| `--check-config`: client lines with `-v` and JDK major, JDK warnings 17/21, `V9_X` warning, undeclared key warning (FR-006) | `ConfigSummaryTest`, `ConfigValidatorCliClientsTest`, `MainTest` |
| Windows: entries with `startcli.bat`, UNC/refused characters refused like `cli.home` (D-21) | `CliRunnerWindowsSelectTest`, `ConfigValidatorTest` |
| record scripts: `RECORD_INUBIT_LINE=9.0` writes `fixtures/v9_0/`, base URL as CLI URL (SC-006) | `RecordFixturesCatalogueTest`, `RecordFixturesPs1Test` |
| closed fixture world: catalogue (`.sh` 9.0 cases) ∪ `NOT_RECORDABLE.txt` = fixture and `.origin` files = cases loaded through `V90Cases` (both directions); a missing `.origin` fails (SC-006) | `V90FixtureCatalogueTest` |
| not-yet-recorded listing: every catalogue case whose `.origin` is not `recorded` is named in a skip message; no build fails because of it (person's decision, release without live check); was: release builds fail; pre-release builds skip with the list (SC-006) | `V90RecordedFixturesTest` |
| launch guard: key changed without a check → `CLI_UNAVAILABLE` "changed during this call", nothing launched; checked range excludes → `UNSUPPORTED_VERSION`; failure recorded or first use → launched as in 0.8.0 (D-4) | `CliRunnerLaunchGuardTest` |
| race: `AUTO` node observed as 9.0 between admission and launch; two overlapping `get_health` calls (FR-017) | `CliRunnerLaunchGuardTest`, `HealthServiceTest` — the unchecked 9.0 client is not launched; each `get_health` reports the client of its own observation |
| write race (FR-018, SC-007): version changes 8.1 → 9.0, 9.0 → 9.1, 9.0 → stale between admission and hand-off → `UNSUPPORTED_VERSION` "changed … during this call", nothing launched/sent; 9.0.6 → 9.0.7 → sent | `WriteLockTest` (`NodeVersions`), `CliRunnerWriteLockTest`, `SoapE2eClientTest`, write-service tests |
| a version change requested during a hand-off waits until the process started / the request was handed over; `update` inside a hand-off throws; no deadlock with parallel reads and `get_health` | `WriteLockTest` (latches, timeouts) |
| `deploy_release` with a version change between two node imports: step fails with the refusal, rollback refused, backup kept (FR-018) | `NodeDeployerWriteLockTest` |
| every write port call carries the permit of its admission (purpose and node) | compile-time; `StaleReportingPortsTest`, service tests |
| A→B→A version change: A's result reused, A's back-off kept (FR-017) | `NodeVersionStateTest` |
| tool announcement: only a `"9.0"` entry and only `V8_1` nodes → exports not announced; an `AUTO` node → announced (D-7) | `ToolAnnouncementTest`, `StartCliClientWiringTest` |
| `AUTH_FAILED` on 9.0 with explicit base-URL or servlet `cli.url` also names the internal user management (FR-013) | `CliOutputClassifierTest`, `CliRunnerTest` |
| 0.8.0 suite, 8.1 and 6.1 behaviour (FR-009, SC-003) | everything else green unchanged, apart from the changes SC-003 allows |

## Live check (after the 0.8.0 release; the person accepted releasing without it)

Run by the person, against a 9.0.x test node only; the server's write tools only with
`developmentEnabled` on a test stage.

1. `--check-config` with `cliClients` `"8.1"` and `"9.0"`: the 9.0 client line shows `CLI 9.0.x`
   and Java 21; no warning for the 9.0 node.
2. `startcli -v` output of the 9.0 client (exact text) → `fixtures/v9_0/cli/version.*`.
3. `list_nodes`: `startCli` of the 9.0 node: `version_entry`, `9.0`, `base_url`, `final: true`.
4. `get_health`: all parts, JSON health/ready/metrics; the client part checks the 9.0 client.
5. `list_inventory`, `get_inventory_item` (incl. `/model/models` with `user`), `query_logs`,
   `find_processes`: results as on 8.1.
6. A REST call with a wrong password: `AUTH_FAILED`, message excerpt from the JSON body.
7. StartCLI login with a user that exists only through OAuth: `AUTH_FAILED` with the internal
   user management hint.
8. `export_artifacts`, `export_archives`, `check_artifacts` (with module lookup),
   `import_artifacts` (test stage), `tag_artifacts`, `kill_process`, `restart_process`,
   `set_active`, `restore_backup` (test stage), `run_e2e_test`.
9. `deploy_release` between two 9.0 test stages: preview, then execution; and a refusal between an
   8.1 and a 9.0 stage.
10. `AUTO` node: first call detects 9.0 and uses the 9.0 client (and its JDK) without a servlet
    path; on a machine that also serves an 8.1 `AUTO` node, that node keeps the 8.1 client.
11. Windows: steps 1–4, 8 and 10 with `startcli.bat` (the chosen `.bat` and JDK in the log).
12. Recording: `RECORD_INUBIT_LINE=9.0 tools/record-fixtures.sh` for every 9.0 case; the
    recorded files replace the reconstructed ones and every catalogue `.origin` reads `recorded`.
    Of `NOT_RECORDABLE.txt`, the write successes are compared with the results of steps 8–9;
    REST 403/500/503 stay documentation-derived (D-10)
    (SC-006); the adapters are adjusted where a recording differs.

## Validation record

**Offline, 2026-11-07** (branch `017-inubit-9-support`, base `origin/0.8.0` d09500b):
- Every row of the offline table was checked against the test classes. Seven names in the table
  were planning names and are corrected to the real classes (e.g. `CliRunnerSelectTest`,
  `V90RestContractTest`/`V90CliContractTest`, `NodeDeployerWriteLockTest`,
  `ToolAnnouncementTest`).
- `mvn -o clean verify`: 5497 tests, 0 failures, 0 errors, 52 skipped. The skips include
  `V90RecordedFixturesTest`, which lists the 21 not yet recorded 9.0 cases.
- `python3 -I -m unittest discover -s tools/tests`: 411 tests OK.
- `tools/check-identifiers.py --all`: clean.

**Live**: not done. The 0.8.0 release ships without it; the person accepted the risk
(T047/T048 stay open).
