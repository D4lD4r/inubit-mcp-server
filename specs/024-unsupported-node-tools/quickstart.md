# Quickstart: Validating the Unsupported Node Tools

How to check 024 offline (automated) and live (by the operator, against the non-production 8.1 and
9.0 test servers).

## Prerequisites

- Branch `024-unsupported-node-tools` built offline:
  `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-024-tmp` and
  `python3 -I -m unittest discover -s tools/tests`.
- The build contains one helper class: `unzip -l target/inubit-mcp-server-*.jar | grep
  META-INF/inubit-mcp/client-helper/` lists exactly one `ClientHelper.class`.
- Live only: the non-production 8.1 test server with its 8.1 client (JDK 17) and the
  non-production 9.0 test server with its 9.0 client (JDK 21) on the MCP host
  (`defaults.cliClients`); a private copy of the profile whose test group sets the four
  `unsupported.*` keys and, for the log-level part, `write.enabled: true`; no group
  `production: true`; a private folder outside every repository for raw spike output.

## Offline validation

| Scenario (spec) | Expected |
|---|---|
| no opt-in anywhere: tool list and summaries exactly as the 021 build; no lease file created (SC-005) | `MainTest`, `ToolAnnouncementTest`, `ConfigSummaryTest` |
| four `unsupported.*` keys: booleans load; `"true"`, `1`, list → error without value; independent; production warnings; `logLevelWrite` without write access warning (FR-002, FR-003) | `ConfigLoaderTest`, `EffectiveNodeConfigTest`, `ConfigValidatorTest` |
| summary group parts only for keys some group sets; `cliOnly` filter (FR-003) | `ConfigSummaryTest` |
| capabilities: 8.1/9.0 "not yet verified" until recorded; 6.1, 9.1, unknown refused; names in `list_nodes` only for opted-in groups with a client (FR-005, US5) | `CapabilityMatrixTest`, `VersionGateTest`, `NodeOverviewTest`, `NodeToolsFixtureCatalogueTest` |
| helper: 020/021 requests byte-identical; four new operations with their keys; malformed → exit 2, no vendor call (D-2) | `HelperRequestTest`, `ClientHelperTest`, `HelperFramesTest`, `git diff --stat src/test/resources/fixtures/queue-entry src/test/resources/fixtures/server-log` empty |
| helper vendor calls exactly as the contract per operation; forbidden methods (`setNodeConfigurationProperty`, `changeTemporaryLogLevel`, `resetTemporaryLogLevel`, GC, cache) never called (FR-007, FR-032) | `ClientHelperTest` (fake call recorder), `FakeVendorClassesTest` |
| allowlist in schema, service and helper equal; secret-holding names never sent (FR-030, SC-003) | `NodeConfigAllowlistTest`, `NodeConfigServiceTest`, `GetNodeConfigToolTest` |
| child JVM round trip per new operation | `ClientHelperProcessTest`, `ClientHelperPackagingTest` |
| node details bounded in the helper: ≤ 500 entries and ≤ 256 KiB of `detail` frames → `details ok <n> true`; ≤ 200 `member` frames with the total count; stdout below 1 MiB (D-2) | `ClientHelperTest`, `CliClusterNodeAdapterTest` (`details_truncated`) |
| message reference identical in server and helper (vector file); no caller text in a download name (FR-021) | `IoMessageRefTest`, `ClientHelperTest`, `CliIoLogAdapterTest` |
| US1: list incl. `UNKNOWN` nodes, single node, details masked + warning, unknown `nodeName` → `INVALID_INPUT`, no opt-in → `NOT_CONFIGURED` nothing sent (US1-1…6) | `ClusterNodeServiceTest`, `ListClusterNodesWiringTest`, `NodeToolsV81ContractTest` |
| US2: listing order, module filter, 500 cap + total, empty-listing hint; read masked and cut, Latin-1, binary, empty, > 10 MiB; unlisted ref → `INVALID_INPUT` without download; `maxChars` bounds (US2-1…7) | `IoLogServiceTest`, `CliIoLogAdapterTest`, `ReadIoLogWiringTest`, `NodeToolsV81ContractTest` |
| US3: values with type, flags, display texts; absent on 8.1 for `noOfRetries`; no names → all 20; masked value (US3-1…5) | `NodeConfigServiceTest`, `CliNodeConfigAdapterTest`, `NodeToolsV81ContractTest` |
| `trace` forms: only `--download`, `--nodelevel`+`--package`, `--reset`; `on`, `off`, `--level`, mixes refused before launch (FR-007) | `CliCommandTest` |
| StartCLI classification of the log-level runs | `CliOutputClassifierTest`, `CliLogLevelAdapterTest` |
| US4 set: write guard + opt-in + gate before anything; `RESET_FAILED` refusal; other node refusal; confirmation `SERVER`/`CLIENT`; write-ahead lease; expiry from the executed change; merge; failed run → immediate reset; record failure → immediate reset (US4-1, -5, -6, -7, -11, edge cases) | `LogLevelServiceSetTest`, `WriteGuardTest`, `WriteChallengeRegistryTest` |
| US4 reset / forget: with and without lease; other node answers → refused (US4-6); write disabled / production / no opt-in → refused, nothing sent (US4-7); forget only `RESET_FAILED`, guarded by `admitWithoutContact` (no gate, no lookup), nothing sent; combinations (US4-4, -6, -7, -8, -9) | `LogLevelServiceResetTest`, `WriteGuardTest`, `ResetTemporaryLogLevelToolTest` |
| lease store: owner-only, atomic, locked across instances, unreadable never overwritten, retention 7 days (FR-043, FR-045) | `LeaseStoreTest`, `LeaseTest` |
| owner identity and takeover (pid + start time) | `ProcessLivenessTest`, `LeaseSchedulerTest` |
| one lock implementation: `OwnerOnlyFileLock` generalized from `KnowledgeLock` (texts unchanged); cross-process node lock per server, node lock before store lock (D-12) | `OwnerOnlyFileLockTest`, `KnowledgeLockTest`, `NodeLogLevelLocksTest` |
| lease generation: a cleanup ends a lease only if unchanged since its decision; a set during the reset decision → nothing sent, lease stays `ACTIVE` with the merged loggers and expiry; a set holding the node lock → cleanup skipped (D-12, D-16) | `LogLevelServiceCleanupTest`, `LeaseTest` |
| two instances of one profile on one store directory: no lease lost or duplicated, exactly one reset per expiry, live owner's leases untouched | `LeaseTwoInstanceTest`, `LeaseStoreTest` |
| cleanup audit fail closed: `PENDING` not writable → no reset sent, state kept with `AUDIT_NOT_WRITABLE`, ERROR logged, retried next tick (D-22, Constitution VII) | `LogLevelServiceCleanupTest`, `LeaseSchedulerTest`, `LeaseShutdownWiringTest` |
| cleanup of one lease: **write tools off → still runs**; preconditions; other node → `RESET_FAILED`; reset failure → `RESET_FAILED` (FR-043) | `LogLevelServiceCleanupTest` |
| scheduler: reset ≤ 1 tick after expiry; retries; takeover (orphaned expired → at once, orphaned unexpired → taken over, reset at expiry; creator decision); unconfigured server → `FORGOTTEN` after 7 days; audit per attempt (US4-2, -3, -10, SC-004) | `LeaseSchedulerTest` (fake clock, fake liveness) |
| shutdown: lease resets before `stopCliWork`, parallel, bounded by `cliTimeout`; hook path once; startup lines (US4-3) | `LeaseShutdownWiringTest`, `MainTest` |
| `get_health` shows open/failed leases, also with write off, through the wired `LeaseStore`; unreadable store → warning (FR-045) | `HealthServiceTest`, `GetHealthToolTest`, `LogLevelWiringTest` |
| `--check-config` lists non-ended leases, unconfigured servers with the forget date; never resets (FR-045) | `ConfigSummaryTest`, `MainTest` |
| version selection across the five tools; 6.1/9.1/undeclared/undetectable refused, nothing sent (US5) | `NodeToolsVersionSelectionTest` |
| masking of details, values and messages; no value or content in logs or audit (SC-002) | `NodeTextMaskingTest`, `EntryTextTest`, service tests (leak checks), `LogLevelAuditTest` |
| `read_queue_entry` unchanged after the `EntryText` extraction | `QueueEntryServiceTest`, `QueueEntryV81ContractTest`, `QueueEntryV90ContractTest` |
| docs: FR-003 sentences, descriptions, documented 8.1/9.0 states equal the matrix | `NodeToolsDocsTest` |
| package boundaries; helper only `java/*` | `PackageBoundaryTest` |
| fictitious names in every fixture and doc | `NoCustomerIdentifiersTest` with the private lists, `tools/check-identifiers.py --all` |

## Early live tasks (operator approval; never production)

Run as early as possible (research D-28), each only after the concrete action was proposed and
approved in the chat; raw output stays owner-only outside every repository.

1. **Read spike** per version (T018 for 8.1, T060 for 9.0; read-only): a throw-away program
   performs exactly the vendor calls of contracts/helper-protocol-delta.md for `ownNode`,
   `clusterNodes` (with `details` of the answering node), `nodeConfig` (the 20 allowlisted names
   only) and `ioLog` (listing and one message of a run the owner names). Optional: the node list
   and one value with a user without the `Configuration` right.
2. **Log-level write spike** per version (T039 for 8.1, T061 for 9.0; changes the test server for
   seconds): only after the owner approved exactly this action — `trace --nodelevel INFO --package
   'com.inubit.ibis.cli'`, then `trace --reset` right after; get_health `systemInfo` before and
   after (persistent trace state unchanged).
3. Neutralize (research D-26): every name, address, path, value and content fictitious, sizes
   adjusted, identifier checks green; the owner reviews the fixture diff; findings appended to
   research.md "Spike findings"; the matrix flip in the same commit.

## Live check (operator; never production)

1. Configure `INUBIT_LIVE_NODE=test/node1` (8.1), optional
   `INUBIT_LIVE_IO_PROCESS=<workflow>/<processId>` of a run with I/O logging.
2. Reads: `mvn -Plive -Dtest=NodeToolsLiveTest verify` → `[live] list_cluster_nodes test/node1: <n>
   node(s) in <s> s`, `[live] get_node_config …`, `[live] read_io_log …` lines (no values).
3. Write part, only with the owner's approval of that run: `INUBIT_LIVE_LOG_LEVEL=approved` →
   set one logger at `INFO` for 1 minute, `get_health` shows the lease, the automatic reset within
   2 minutes, the lease `RESET`, audit records for set and automatic reset.
4. Check by hand: persistent trace state unchanged (get_health `systemInfo`); the MCP server log
   contains no value or message text. Repeat with a 9.0 node. Record the outcome below.

## Validation record

| Date | Version | Tool | Result | Notes |
|---|---|---|---|---|
| — | — | — | — | (filled by T068 / T069) |
