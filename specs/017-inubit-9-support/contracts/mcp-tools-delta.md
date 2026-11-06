# MCP Tools Delta: INUBIT 9 Support

Delta to [docs/tools.md](../../../docs/tools.md). No tool is added or removed; inputs are
unchanged; outputs change only additively (`list_nodes.startCli`) and by the 9.0 support state.

## All tools on a 9.0 node (FR-001, FR-002)

- A node whose effective version is 9.0.x (`V9_0`, a 9.0 pin, or `AUTO` detected/observed as 9.0)
  is admitted for every capability 8.1 is admitted for, without the "not verified" warning:
  `get_health`, `find_processes`, `query_logs`, `list_inventory`, `get_inventory_item`,
  `export_artifacts`, `export_archives`, `check_artifacts`, `restart_process`, `kill_process`,
  `import_artifacts`, `tag_artifacts`, `set_active`, `restore_backup`, `deploy_release`
  (9.0 ↔ 9.0 only), `run_e2e_test`.
- `V9_X`, 9.1 and later: unchanged (reads unverified, writes `UNSUPPORTED_VERSION`).
- Result shapes are those of 8.1.

## StartCLI-backed tools (FR-003 … FR-005, FR-010, FR-013, FR-017)

- The client, JDK and address are chosen at launch time from the node's effective version
  (configuration-delta.md).
- No client from any source on a node with a known major.minor while `cliClients` is configured
  or the major is ≥ 9:

  ```json
  { "code": "CLI_UNAVAILABLE",
    "message": "No CLI home is configured for test/node1 (INUBIT 9.0.7)",
    "likelyCause": "Neither cli.home, defaults.cliClients.\"9.0\" nor cliHome is set",
    "nextStep": "Add defaults.cliClients.\"9.0\".home (the INUBIT 9.0 client directory) or set cliHome",
    "node": "test/node1" }
  ```

  Otherwise the 0.8.0 text is unchanged.
- Client range excludes the version: `UNSUPPORTED_VERSION` as in 009; next step additionally
  `…, or add defaults.cliClients."9.0"` under the same condition.
- Warning on the result when an explicit `cli.url` with `/ibis/servlet/IBISSoapServlet` is used on
  a node of major ≥ 9: `cli.url of test/node1 uses /ibis/servlet/IBISSoapServlet, which INUBIT
  9.0.2 and later removed; remove cli.url or set it to the base URL`.
- Failed StartCLI login on a node whose client was chosen for a 9.x version (any address, also an
  explicit `cli.url`): `AUTH_FAILED` as today; the next step ends with `On
  INUBIT 9 the user must exist in the internal user management; users that log in only through
  OAuth cannot use StartCLI.`
- After a version change of an `AUTO` node the previous client check is not used; the new client
  is checked (`startcli -v`) before its first use — also when the change happens between the
  admission and the launch of a call (launch guard), nothing is launched:
  - the new client's checked range excludes the version → the 009 `UNSUPPORTED_VERSION` text;
  - the new client has not been checked yet →

    ```json
    { "code": "CLI_UNAVAILABLE",
      "message": "The StartCLI client for test/node1 changed to the client for INUBIT 9.0 during this call; the new client has not been checked yet",
      "likelyCause": "INUBIT on test/node1 was found to run a different version while the call was waiting",
      "nextStep": "Call the tool again",
      "node": "test/node1" }
    ```

## Every write tool (FR-018, D-24)

`import_artifacts`, `set_active`, `restore_backup`, `tag_artifacts`, `run_e2e_test`,
`deploy_release`, `restart_process`, `kill_process`: when the node's known version changed to
another major.minor (or was marked as possibly changed) after the call was admitted, the write is
not sent:

```json
{ "code": "UNSUPPORTED_VERSION",
  "message": "INUBIT on test/node1 changed from 8.1.17 to 9.0.7 during this call; the write was admitted for INUBIT 8.1; nothing was sent",
  "likelyCause": "A parallel call found that test/node1 runs another INUBIT version",
  "nextStep": "Call the tool again; it is judged on the version INUBIT runs now",
  "node": "test/node1" }
```

Stale or unknown current version: message `INUBIT on test/node1 may run another version than
8.1.17 since this call was admitted; nothing was sent`, same cause and next step.

- A patch change within the admitted major.minor (9.0.6 → 9.0.7) does not refuse; the launch
  guard (above) checks the client's range for the new version.
- Multi-step writes: in `deploy_release`, `import_artifacts` with tag and `restore_backup`, a
  refused step fails like a StartCLI error of that step (existing step failure, with this error in
  the step's report); steps already sent stay sent. `deploy_release`'s rollback is refused as well
  (`ROLLBACK_FAILED` as today); instead of the 0.8.0 warning "… is in the backup <ref>
  (restore_backup)" its warning reads `The rollback of test/node1 was not sent because INUBIT
  changed from 8.1.17 to 9.0.7 during this call; its state before the deployment is in the backup
  <ref>, taken on INUBIT 8.1; restore it with restore_backup only on INUBIT 8.1`.
- Reads are not affected.

## `get_health`

- Part `client`: the check of the client chosen for the version read in this call. If the choice
  changed and the remaining budget does not allow a second check, `unavailable` contains
  `{ "part": "client", "reason": "the StartCLI client for INUBIT 9.0.7 on test/node1 has not been checked yet", "nextStep": "Call get_health again" }`.

## `list_nodes` (FR-015)

New field per node (always present):

```json
"startCli": {
  "clientDirectory": "/opt/inubit-9.0/client",
  "source": "version_entry",
  "versionEntry": "9.0",
  "address": "base_url",
  "final": true
}
```

| Field | Values |
|---|---|
| `clientDirectory` | the chosen client directory; absent when `source` is `none` |
| `source` | `node`, `group`, `version_entry`, `defaults`, `none` |
| `versionEntry` | the `cliClients` key; only with `version_entry` |
| `address` | `servlet_path`, `base_url`, `configured` (explicit `cli.url`; the URL itself is never shown) |
| `final` | `false` while the node's version is unknown (`AUTO` not yet detected, failed detection): the choice may change at runtime |

`cliAvailable` now refers to the client currently chosen. The schema
`list_nodes.output.json` gets the `StartCli` definition (all properties described, no
`additionalProperties`). The `versionLine` description lists `V9_0`.
