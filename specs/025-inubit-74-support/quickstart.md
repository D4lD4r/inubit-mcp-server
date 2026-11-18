# Quickstart: INUBIT 7.4 Support

## Mandate A — offline validation (any machine, no INUBIT contact)

```bash
mvn -q -o clean verify
```

| What | Test(s) | Spec |
|---|---|---|
| `V7_4` and 7.4 pins load; error text names `V7_4` | `VersionSettingTest`, `ConfigLoaderTest` | FR-001 |
| fourth part kept, shown | `InubitVersionTest` | FR-002, FR-004 |
| patch comparison with four parts on 7.4, three elsewhere | `ClientVersionTest`, `CapabilityMatrixTest`, `ConfigSummaryTest`, `ListNodesToolTest`, `VersionGateTest` | FR-003, SC-004 |
| 404 hint names `V6_1` and `V7_4`; other failures unchanged | `VersionGateTest` (hint) | FR-005, SC-002 |
| every contacting tool refused on 7.4, before any launch, request or handshake | `V74RefusalTest` (one case per tool) | FR-006, FR-007, FR-011, SC-003 |
| refusal logged | `V74RefusalTest` (log record) | FR-012 |
| `check_artifacts` skips the lookup | `ArtifactCheckServiceTest` (7.4 case) | FR-008 |
| tool not announced for 7.4-only profiles | `ToolAnnouncementTest` (7.4 case) | FR-009 |
| `list_nodes` / `--check-config` show 7.4 | `ListNodesToolTest`, `ConfigSummaryTest` | FR-010, SC-001 |
| `cliClients."7.4"` handling, no 6.1-style warning, note in `--check-config` and `list_nodes` warnings | `ConfigValidatorTest`, `ConfigSummaryTest`, `ListNodesToolTest`, `MainTest` (`--check-config`, POSIX only) | FR-010a |
| no `-h` option probe for 7.4 | `CliClientProbeTest`, `ConfigValidatorTest`, `MainTest` (POSIX only) | FR-010a, FR-007 |
| 7.4 port set refuses, builds no REST client | `V74PortSetTest`, `AdapterGatewayFactoryTest` | FR-011 |
| fixture layout and guards | `V74FixturesShapeTest`, `V74RecordedFixturesTest` | FR-014, FR-015, FR-016a |
| the real help case | `V74HelpContractTest` | FR-014 |
| recorder catalogue and safeguards | `RecordFixturesPs1Test` | FR-016 – FR-019, SC-006 |
| 6.1 / 8.1 / 9.0 unchanged | `VersionLineRegressionTest` + the existing suite | FR-020, SC-005 |

The Windows CI job runs the same suite (FR-013).

Recorder self-check on Windows (no server):

```powershell
powershell -ExecutionPolicy Bypass -File tools\record-fixtures.ps1 -List
```

## Mandate B — recording session (Windows, by hand, after approval)

Preconditions (spec): Windows machine with the 7.4 Workbench client and its JDK; CLI login to a
**non-production** 7.4.0.90 node; the local neutralize map; the owner's approval in the chat.

1. Set the allowlists for this run only:

   ```powershell
   $env:RECORD_ALLOWED_GROUPS = '<group>'; $env:RECORD_ALLOWED_HOSTS = '<non-production host>'
   ```

2. Local and research cases first:

   ```powershell
   powershell -ExecutionPolicy Bypass -File tools\record-fixtures.ps1 -Group <group> -Node <node> `
     -CliHome <7.4 client> -JavaHome <its JDK> -CliUrl <url>/ibis/servlet/IBISSoapServlet `
     -Case v74_help,v74_version,v74_exec_help,v74_exec_ps_help,v74_direct_health
   ```

3. Read `v74_exec_help` and `v74_exec_ps_help`; if the commands differ from the catalogue, adjust the
   7.4 entries of the catalogue (one place) and note the change here.
4. Session and failure cases:

   ```powershell
   ... -Case v74_health_session,v74_ps_all,v74_ps_error,v74_ps_waiting,v74_ps_queued,v74_ps_limit5,v74_login_failed,v74_failure
   ```

5. Run `NoCustomerIdentifiersTest`, review the diff, commit the recordings.
6. Live check on Windows and on macOS or Linux (MCP server with a `V7_4` node and an `AUTO` node):
   record here the outcome per step, including the detection failure code of the `AUTO` node.

## Validation record

| Date | Step | Platform | Outcome |
|---|---|---|---|
| | | | |
