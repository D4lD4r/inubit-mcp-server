# Research: INUBIT Version Detection and Version-Dependent Capabilities (feature 009)

Decisions for [plan.md](plan.md). Code locations come from an analysis of branch
`009-inubit-version-detection` (based on `008-repo-file-editing`, commit `9e76b5a`). Paths are
relative to `src/main/java/de/dadecker/inubit/mcp/` unless given in full.

## Starting point (verified in the code)

- `config/VersionLine` is `{V8_1, V9_X, AUTO}`, bound by Jackson directly (no custom
  deserializer). It is resolved node → group → built-in `AUTO` in `EffectiveNodeConfig.resolve`
  (:224); there is no `defaults`-level value. `ConfigValidator.checkServer` (:503) warns about
  `V9_X`.
- **Detection happens only through `get_health` today.** `GatewayFactory.forServer()` is never
  called in production code: `AdapterGatewayFactory` overrides every port accessor (`monitoring`,
  `processes`, `logs`, `inventory`, `artifacts`, `imports`, `tags`, `processControl`, :136-178) to
  read `slot.ports()` without waiting for a detection. An `AUTO` node gets its version only when
  `get_health` reads `/system/info` (the `SystemInfoListener` → `Slot.succeeded/failed`,
  :293-327). A failed detection is cached for 60 s (`FAILED_DETECTION_TTL`) and falls back to 8.1.
- **Gateway warnings reach only `get_health`** (`HealthService.gatewayWarnings` → `knownGateway`).
  The output schemas of `find_processes`, `query_logs`, `list_inventory` and `get_inventory_item`
  have no `warnings` field.
- `ListNodesTool` computes its result once, in its constructor, from `NodeSummary`s built in
  `Wiring` (:180) by `EffectiveNodeConfig.summary()`.
- `adapter/cli/CliVersionProbe` exists and is tested, but nothing calls it. It runs
  `startcli.sh -v` through the package-private `CliRunner.execute` with `cliTimeout` (default
  30 s). Its `mismatchWarning` compares exact strings. Fixture: `fixtures/v8_1/cli/version.*`
  ("CLI 8.1.17", "Supported … from 4.0.1 to 8.1.17.").
- `get_health` (`application/HealthService`) checks each node in parallel on virtual threads with
  a deadline of `timeout` + 250 ms per node and `timeout` + 900 ms overall (feature 001 SC-002:
  5 s for one environment, 10 s for 10 servers). Parts: `status`, `ready`, `version`,
  `systemInfo`, `load` (`UnavailablePart.PARTS`).
- Write tools pass through guards: `DevelopmentGuard.admit` (import, restore, set_active, tag,
  run_e2e_test; has `Capability` and `needsCli()`), `WriteGuard.admit` (restart, kill),
  `DeployGuard.admit/check` (deploy_release, with the source and target node lists). Each service
  audits refusals through its `Call.refuse` (`AuditOutcome.REFUSED`), `DeployGuard.refuse` for
  deployments.
- The tool list is fixed at startup in `Wiring.toolHandlers()` (:323-352) with conditions such as
  "any development node" and "any write-enabled node"; `McpServerFactory` announces
  `tools.listChanged = false`.
- `--check-config` (`config/ConfigSummary.render`, called from `Launcher` :162-169) has no
  StartCLI runner today.
- `ErrorCode.UNSUPPORTED_VERSION` exists and appears in several output schemas' error-code enums;
  docs/tools.md (:131) calls it "reserved … not returned by this version".
- `PackageBoundaryTest`: domain depends only on `java.*`; application not on adapter; mcp not on
  adapter or config; processes only in `adapter.cli`.
- No INUBIT 9.x fixture exists; tests synthesise `9.0.2` by rewriting the 8.1.17
  `system_info.xml` (`AdapterGatewayFactoryTest.systemInfoWithVersion`).

## D-1 Version value type and parsing

**Decision**: a domain record `InubitVersion(int major, int minor, OptionalInt patch, String raw)`
with `parse(String)`: the leading `major.minor[.patch]` digits are read, anything after them (a
suffix such as `-SNAPSHOT`, `-HF3`, ` build 42`) stays in `raw` and is ignored for matching.
Without a parsable major.minor the result is empty → the version is `unknown`. Comparison by
major, minor, then patch (missing patch sorts as 0 for range checks only).

**Rationale**: spec edge case "unexpected format"; one type for server, client, pins and ranges.

**Alternatives**: keep raw strings and compare textually (rejected: `8.1.9` vs `8.1.17`).

## D-2 The version setting in the profile

**Decision**: keep the key `versionLine` on group and node. Allowed values: `AUTO`, `V8_1`,
`V9_X`, or a concrete version string with at least major.minor.patch (`"8.1.17"`, suffix allowed).
A custom deserializer accepts strings; an unquoted `8.1` arrives as a YAML float and is refused
with "use V8_1 or a full version such as \"8.1.17\"". Internally `VersionSetting` is a sealed type:
`Auto`, `Line(major, minor?)` (`V8_1` = 8.1, `V9_X` = 9 without minor), `Pinned(InubitVersion)`.
`--check-config`, `list_nodes` and audit show the configured text.

**Rationale**: FR-012, no migration (existing values keep their spelling); one key instead of a
second `version:` key that could contradict `versionLine`.

**Alternatives**: a new key `version:` next to `versionLine` (rejected: two sources of truth).

## D-3 Capability matrix

**Decision**: a pure domain class `CapabilityMatrix` with a static declaration:

| Range | MONITORING, DIAGNOSIS, INVENTORY, WORKSPACE_EXPORT | DEVELOPMENT_WRITE, DEPLOYMENT, E2E_TEST, PROCESS_CONTROL |
|---|---|---|
| 8.1.x | `SUPPORTED` | `SUPPORTED` |
| 9.0.x | `UNVERIFIED` | `UNSUPPORTED` |
| any other known version | `UNVERIFIED` | `UNSUPPORTED` |
| unknown | `UNVERIFIED` | `UNSUPPORTED` |

`Capability` (enum) carries `kind` (READ/WRITE) and `channels` (REST, CLI, SOAP), following the
spec's tool classification. A test-only constructor accepts other declarations (US3 test).
`list_nodes` and `check_artifacts` need no capability.

**Rationale**: FR-003, FR-014; data instead of `if (version …)` in tools (FR-013, Constitution V).

**Alternatives**: per-adapter "supports()" methods (rejected: scatters the declaration).

## D-4 Where the per-node version state lives

**Decision**: an application-layer `NodeVersions` (one per server process) holding, per node:
the setting, the last detection (`InubitVersion` + time) or the last failure (code + time), the
last `get_health` observation (version + time), a `stale` flag, and the client probe result
(`ClientVersion` or failure + time). It is fed by:

- the detection path (D-5): `GatewayFactory.detect` and the `Slot`'s AUTO detection report
  detections and failures through a domain port (`VersionObserver`);
- `HealthService` is the single owner of observations (FR-001/FR-012): it reports the version of
  every `get_health` (pinned nodes too) through the same port; the `SystemInfoListener` keeps
  feeding only the slot's AUTO detection (it returns early for non-AUTO nodes);
- the client probe (D-7).

Rules (spec N3): the effective server version of a node is the most recent of detection and
observation; for a write, a stale or over-bound `AUTO` version whose re-detection failed or is
suppressed by the back-off counts as unknown (`writableVersion`, FR-008/FR-010); for a pinned node without an observation, the pin. On an `AUTO` node a `get_health`
observation replaces the cached detection. A pin contradiction (observed major.minor ≠ pin) is set
by a `get_health` observation and cleared by a later matching observation (FR-012).

**Rationale**: one source for `list_nodes`, the gate (D-5), `get_health` and audit; the
`AdapterGatewayFactory` slot remains the adapter selector (Constitution V).

## D-5 The version gate (refusals and detection on first use)

**Decision**: an application-layer `VersionGate.admit(NodeId, Capability, Purpose, Set<Channel>
channels)` returning `Admission(warnings)` or throwing `ToolErrorException(UNSUPPORTED_VERSION)`.
`channels` names the channels the call is about to use (REST, CLI, SOAP), so that a mixed-channel
tool asks twice: before its REST part with `{REST}` and before its StartCLI part with `{CLI}`; the
client check (D-8) runs only when `CLI` is among them. `Purpose` is READ or WRITE. Steps:

1. Resolve the effective version (D-4). If the setting is `AUTO` and there is no version yet
   (first use), or the purpose is WRITE and the version is stale or older than the node's
   `inventory.cacheTtl` (D-10), detect now: one `/system/info` request through
   `GatewayFactory.detect(node)`. `detect` bypasses the slot's success cache (so re-detection works)
   and reports to `NodeVersions`, which owns the 60 s failure back-off for the gate; the slot keeps
   its own `fallbackUntil` only for adapter selection (D-16). `MONITORING` never detects: in
   `get_health` the health check's own `/system/info` read is the detection and observation, and
   the gate only derives the warning after it (no duplicate request, feature 001 budget).
2. Look up the matrix state for the capability; apply the pin contradiction (writes refused) and
   the client check for CLI channels (D-8).
3. `SUPPORTED` → admit; `UNVERIFIED` + read → admit with the warning
   "INUBIT <v> on <node> is not verified for <capability>; results may be incomplete";
   otherwise refuse (FR-005 text, incl. client version and range when the client is the cause).
   The gate builds its own `nextStep` from the contract table and never forwards the detector's
   (`V81VersionDetector` suggests `versionLine: V8_1` unconditionally).
   Exception: `MONITORING` is never refused, even if a (test-only) matrix declares it
   `UNSUPPORTED`; `get_health` then only carries a warning.

Call sites (one line each, before any state-changing call):
- `DevelopmentGuard.admit` (import, restore of development backups, set_active, tag,
  run_e2e_test) — after the configuration checks, before `cli.check`. Its nested
  `DevelopmentGuard.Capability` enum is renamed `DevelopmentGuard.Operation` (avoids the clash with
  the domain `Capability`) and maps `RUN_E2E_TEST` → `E2E_TEST` (channels SOAP+REST), every other
  operation → `DEVELOPMENT_WRITE` (channels CLI+REST);
- `DevelopmentGuard.admitDeploymentRestore` (restore of deployment backups) → `DEPLOYMENT`;
- `WriteGuard.admit` (restart, kill) → `PROCESS_CONTROL`, before the REST state read;
- `DeployGuard.check` for FR-014a (D-11), where `Admitted` has both node lists and the audited
  `refuse` is available;
- reads: `DiagnosisService` (find_processes, query_logs: `{REST}`), `InventoryService`
  (`list_inventory` diagrams `{REST}`; `list_inventory` modules `{CLI}` — the module list is a
  StartCLI export, so the whole call is refused when the client is incompatible;
  `get_inventory_item`: `{REST}` first, then `{CLI}` before the history, whose refusal becomes the
  existing `unavailable` history part with code `UNSUPPORTED_VERSION`, as the spec's edge case
  "behaves as today when its StartCLI part is unavailable" requires), `ArtifactCheckService`
  module lookup (`INVENTORY`, `{CLI}`; a refusal skips the lookup and reports it as the existing
  "module could not be looked up" finding with the reason), `WorkspaceService` export
  (`WORKSPACE_EXPORT`, `{CLI}`), `HealthService` (`MONITORING`, never refuses, never detects).

Refusals of write tools go through the existing `Call.refuse` / `DeployGuard.refuse` (audited
`REFUSED`, FR-015). Admission warnings of write tools are added to the result's `warnings`
(`restart_process`/`kill_process` gain an optional `warnings` field; the other write tools have
one already).

**Rationale**: FR-005, FR-008, FR-009, FR-010; the first tool call of any kind detects
(clarification 2026-10-18), which today does not exist (finding above).

**Alternatives**: check inside each adapter (rejected: Constitution V wants tool logic
version-free, and adapters cannot audit).

## D-6 Warnings on read tools

**Decision**: add an optional `warnings: string[]` to the results (and output schemas) of
`find_processes`, `query_logs`, `list_inventory`, `get_inventory_item`; present only when
non-empty. `export_artifacts` and `get_health` already have `warnings`. A group call puts the
per-node warning into that node's entry where the result is per node, otherwise prefixes it with
the node id.

**Rationale**: FR-009 requires the "unverified version" warning in the result; additive schema
change (`additionalProperties: false` needs the new field declared).

## D-7 The StartCLI client probe

**Decision**: a domain port `ClientVersionPort.probe(NodeId, Duration timeout)` implemented in
`adapter/cli` by `CliVersionProbe` (it must stay there: `CliRunner.execute` is package-private).
- In `get_health`: submitted as a fourth parallel future with the node's health deadline as its
  timeout (FR-002); on timeout the part `client` is `not yet detected (timeout)`. A new health part
  `client` is added to `UnavailablePart.PARTS` and the schema.
- Outside `get_health`: the gate runs the probe before the first CLI-backed call on a node, bounded
  by `cliTimeout`, not counted against the tool's own timeout (spec N5).
- A successful result is kept for the process lifetime; a failure or timeout is not cached and is
  retried at most once per 60 s (FR-002).
- `mismatchWarning` is replaced by the FR-011 comparison (D-8).

## D-8 Client comparison and range check

**Decision**:
- Comparison (FR-011): `match` / `mismatch` / `not comparable` on major.minor.patch, using the
  effective server version (D-4).
- Range check: the client's declared range is reduced to major.minor bounds, inclusive; a server
  major.minor inside → compatible (a newer patch only warns). A line pin `V9_X` (major only) is
  compatible only if some 9.x lies inside the range; the 8.1.17 client ("4.0.1 to 8.1.17") is
  therefore incompatible with `V9_X` (spec N4, reviewer proposal adopted). A probe that prints a version but no range is a successful
`ClientVersion` with an empty range (kept for the process lifetime) whose range check is
`NOT_EVALUABLE`; only failed or timed-out probes are not cached.
- Range not evaluable (server unknown, probe failed or timed out, no range printed) → CLI tools
  run with a warning naming the cause (clarification round 3).

## D-9 `list_nodes`

**Decision**: `ListNodesTool` takes a `Supplier<List<NodeSummary>>`; the summary keeps its
configuration fields (computed once) and gets a `version` block from `NodeVersions` at call time:
`setting`, `server` (`version`, `source` detected|pinned|observed, `at`) or `state`
(`not_yet_detected` | `unknown` with `reason`, `at`), `client` (`version`, `range`, `comparison`)
or `state`, `status` (`supported` | `partially_supported` | `unsupported` | `unknown`),
`capabilities` (`usable[]`, `notUsable[]` with reasons). "Usable" means the tools run on the node:
`SUPPORTED`, or `UNVERIFIED` for reads and not refused by the client check (spec N1). The
existing `versionLine` field stays (string: the configured text) for compatibility. `list_nodes`
never contacts a node (FR-004).

## D-10 Re-detection bound and stale triggers

**Decision**: the bound is the node's `inventory.cacheTtl` (default 10 min); no new setting in this
feature. Stale triggers and their hook:
- `AdapterGatewayFactory` wraps every port it hands out in a small decorator per port interface
  (`StaleReportingPorts`) that catches `ToolErrorException` with code `UNEXPECTED_RESPONSE`
  (whether raised by `InubitHttpClient` or by a v81 parser after the client returned) and reports
  `stale(node)` to the `VersionObserver` before rethrowing. HTTP 406 already arrives as
  `UNEXPECTED_RESPONSE` (`InubitHttpClient` default branch).
- HTTP 404: `InubitHttpClient` maps every 404 to `NOT_FOUND` today, so the request has to say what
  a 404 means. Requests get a flag `ResourceKind.ENDPOINT | RESOURCE` (default `RESOURCE`, today's
  behaviour); the v81 adapters pass `ENDPOINT` for fixed endpoints that must exist on a working
  8.1 server (`/system/info`, `/system/healthcheck`, `/log/...`, `/queueLog`, `/model/...` lists);
  a 404 there becomes `UNEXPECTED_RESPONSE` ("endpoint not found; the node may run another INUBIT
  version"), which the decorator reports. A 404 for a requested id (a process, a diagram) stays
  `NOT_FOUND` and is no trigger.
Reads keep using the cached version; the next write re-detects (FR-010).

## D-11 `deploy_release` (FR-014a)

**Decision**: in `DeployGuard.check()` (which builds `Admitted` with `targetNodes` and
`sourceNodes`, and whose `admit` audits refusals through the private `refuse`); `DeployService`
calls the guard in preview and in execute, so both are covered. `DeployGuard` gets the gate as a
constructor parameter (Wiring and the DeployGuard fixtures change) and `Admitted` gains the
admission `warnings`. Steps: call `VersionGate.evaluate(node, DEPLOYMENT, WRITE, {CLI, REST})`
(the non-throwing variant of `admit`, returning admitted-with-warnings or the refusal cause) for
every source and target node (detection, matrix, pin contradiction and the client check incl. the
probe; sources export and targets import and tag through StartCLI), collect all results, then
check: no node unknown, every target `SUPPORTED` for
`DEPLOYMENT`, all sources and targets equal in major.minor (`V8_1` or a concrete pin gives
major.minor; `V9_X` counts as unknown). A failure refuses the whole deployment with one message (audited `REFUSED`) that names every
source and target node with its version and, per refused node, its cause (client, contradiction,
unknown).
Admission warnings (client mismatch, failed probe) appear in a new optional top-level `warnings`
of the deployment preview (`DeploymentPreview` and `deploy_release.output.json`) and in the
execute result's existing `warnings`.

## D-12 Tool announcement (FR-007)

**Decision**: the `Wiring` conditions ("any development node", "any write-enabled node", "any e2e
node", "any deployment target") additionally require that at least one such node is not pinned to
a version whose matrix state for the capability is `UNSUPPORTED` (e.g. `V9_X`). `AUTO` nodes count
as possibly capable. Unchanged: decided once at startup, `listChanged = false`.

## D-13 `--check-config` (FR-011a, FR-012)

**Decision**: `Launcher` creates a `CliRunner` for the check (no credentials, no server URL) and
passes per-node `ClientVersion` results to `ConfigSummary`; the node line gains
`, inubit: <setting>[, client: <version> (<from>–<to>)[, <comparison vs pin>]]`. The `V9_X`
warning text becomes "versionLine V9_X: write tools are refused on this node (INUBIT 9.x is not
supported for writes yet); read tools run unverified". An invalid concrete version is a
configuration error (D-2).

## D-14 Behaviour changes for existing tests

Deliberate (spec SC-003): writes refused after a failed `AUTO` detection; writes refused on
`V9_X`; read results may carry an "unverified" warning on non-8.1 nodes. `TestNodeConfig`
defaults to `V8_1`, so most tests are unaffected. Tests expected to change: AdapterGatewayFactory
(V9_X/9.0.2 warnings now refusals for writes), ConfigSummary/ConfigValidator/MainTest (V9_X text),
HealthService (new `client` part), ListNodesTool (new `version` block), DevelopmentWiringTest and
ProcessControlToolsTest (announcement with `V9_X`-only profiles). `DiagnosisToolFixture` and
`InventoryToolFixture` use `AUTO`: the first read now sends `/system/info`, so their stubs and
request counts change (add the system_info stub, or pin `V8_1` where the test is not about
detection). Constructor changes for the gate touch `Wiring` and the fixtures building
`DevelopmentGuard`, `WriteGuard`, `DeployGuard`, `DiagnosisService`, `InventoryService`,
`WorkspaceService`, `ArtifactCheckService` and `HealthService`. Warnings travel from the guards to
the results in `DevelopmentPolicy.warnings`, `WritePolicy.warnings` and `DeployGuard.Admitted.warnings`
(new list fields).

## D-15 Fixtures

**Decision**: a synthetic `fixtures/v9_0/rest/system_info.xml` (same document shape as 8.1, version
`9.0.7`) marked synthetic in its README, used until a recorded 9.0 response exists; the 8.1
`version.*` CLI fixture plus a synthetic variant without the "Supported" line and one with a
9.0 range. No live probe is needed for this feature.

## D-16 Port-set selection by effective version (extension point for US3)

**Decision**: `AdapterGatewayFactory` gets a `PortSetRegistry` (constructor parameter) mapping
`VersionRange`s to port-set factories, plus a default factory. `Slot.ensureClient()` keeps building
the HTTP client lazily, but the port set (`V81Gateway.Ports`) is chosen from the registry by the
node's effective version, read through the domain port `VersionObserver` (extended with
`effectiveVersion(NodeId)`, implemented by `NodeVersions`) so that the adapter never depends on the
application layer, at the time ports are first requested **after** the gate has admitted the
call (the gate's detection happens first). Production registers only the 8.1 port set, as default
for every version (unverified reads run on the 8.1 adapters), so production behaviour is unchanged.
A later feature adds a 9.0 range with its own factory; US3's test registers a fictitious range and
a test-only port set. A change of the effective version range (after re-detection) rebuilds the
port set.

**Rationale**: FR-013/FR-014, US3-3: adding a version must not need tool changes; today
`ensureClient()` hard-wires the V81 port set before any detection.
