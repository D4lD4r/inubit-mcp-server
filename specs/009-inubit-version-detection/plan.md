# Implementation Plan: INUBIT Version Detection and Version-Dependent Capabilities

**Branch**: `009-inubit-version-detection` | **Date**: 2026-10-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/009-inubit-version-detection/spec.md`

## Summary

The server learns which INUBIT version each node runs and decides per tool whether that version
supports it. No new tool is added. Instead:

- a domain **capability matrix** declares per version range and capability `SUPPORTED`,
  `UNVERIFIED` (reads run with a warning) or `UNSUPPORTED` (D-3): 8.1.x supported, 9.0 and every
  other or unknown version unverified for reads and unsupported for writes;
- a **version gate** in the application layer is called by every tool's guard before anything is
  sent; it detects the version on first use (and again before writes when it is missing, stale or
  older than the node's `inventory.cacheTtl`, default 10 minutes), applies the matrix, the pin contradiction and the client check, and
  either admits (with a warning for unverified reads) or refuses with `UNSUPPORTED_VERSION` (D-5);
- a per-node **version state** (`NodeVersions`) records detections, `get_health` observations,
  failures and the StartCLI client probe; `list_nodes` reports it without contacting nodes (D-4,
  D-9);
- the StartCLI **client probe** (`startcli.sh -v`) is wired: in `get_health` in parallel within the
  health budget, otherwise before the first StartCLI-backed call (D-7); its range is checked by
  major.minor (D-8);
- `versionLine` accepts a concrete version (`"8.1.17"`) besides `AUTO`, `V8_1`, `V9_X` (D-2);
- `deploy_release` refuses deployments across major.minor versions or with unknown nodes, in
  preview and before execution (D-11);
- read tools get an optional `warnings` field (D-6); `--check-config` shows the client version
  (D-13).

## Technical Context

**Language/Version**: Java 21 (unchanged).

**Primary Dependencies**: unchanged, no new dependency (MCP Java SDK, Jackson YAML, logback).

**Storage**: none new; the version state is in memory per server process (D-4). Audit log as
before.

**Testing**:
- JUnit 5 and AssertJ;
- WireMock and the existing `system_info` fixture, plus a synthetic 9.0 fixture (D-15);
- `FakeProcessLauncher` with the `version.*` CLI fixture and synthetic variants;
- a test-only capability matrix and adapter for US3;
- existing timing tests for `get_health` (`HealthOverviewPerformanceTest`, `GetHealthToolTest`).

**Target Platform**: macOS and Linux (unchanged; the client probe needs the CLI like every
CLI-backed tool).

**Project Type**: a single Maven module, built as a shaded JAR.

**Performance Goals**: `get_health` stays within feature 001 SC-002 (5 s per environment, 10 s for
10 servers) with the client probe in parallel (D-7); `list_nodes` stays as fast as today (no
contact). A detection on first use adds one REST request; a client probe outside `get_health`
adds one JVM start (about 2–3 s) once per node and process.

**Constraints**:
- writes only on nodes whose version is known and declared `SUPPORTED` (FR-008);
- nothing but the version detection is sent before the capability check passes (FR-005);
- no INUBIT contact at startup, in `list_nodes` or in `--check-config` (FR-004, FR-011a);
- tool logic never branches on version strings (FR-013);
- failed detections keep today's 60 s back-off (account lockout).

**Scale/Scope**:
- 0 new tools; widened: `list_nodes` (version block), `get_health` (part `client`, client
  fields), `find_processes`/`query_logs`/`list_inventory`/`get_inventory_item` (optional
  `warnings`), `deploy_release` (refusal), all write tools (refusal);
- new production classes (about 15): `InubitVersion`, `VersionSetting`, `Capability`, `Channel`,
  `SupportState`, `VersionRange`, `CapabilityMatrix`, `ClientVersion`, `NodeVersionState` (domain);
  `ClientVersionPort`, `VersionObserver` (ports); `NodeVersions`, `VersionGate` (application);
  `StaleReportingPorts`, `PortSetRegistry` (adapter);
- extensions: `VersionLine` → `VersionSetting` with deserializer, `EffectiveNodeConfig`,
  `ConfigValidator`, `ConfigSummary`, `Launcher`, `AdapterGatewayFactory` (detect + reporting),
  `CliVersionProbe`, `HealthService`, `UnavailablePart`, `NodeSummary`, `ListNodesTool`, the
  guards and services named in D-5/D-11, `Wiring`, schemas, docs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ⚠️ Today 9.x and undetected nodes run 8.1 write logic | ✅ <ul><li>Writes only on known, `SUPPORTED` versions; unknown, unverified, unsupported or contradicted pins refuse (FR-008, D-5).</li><li>The refusal happens before any request other than the detection.</li><li>`deploy_release` checks all nodes in preview and execute (D-11).</li><li>Tools no node can run are not announced (D-12).</li></ul> |
| II. Secrets Stay Out | ✅ | ✅ <ul><li>The client probe runs without credentials and without a server URL.</li><li>Detection uses the existing authenticated request and credential guard.</li><li>Results carry versions and states only, never URLs or credentials (FR-016).</li></ul> |
| III. Test-First | ✅ | ✅ <ul><li>Every gate rule, matrix entry and refusal has a test before code (quickstart table).</li><li>The safety rules (writes refused per state) have one test per write tool (SC-002).</li><li>A test-only matrix proves extensibility (US3).</li><li>No live test needed; fixtures as in D-15.</li></ul> |
| IV. Use-Case-Oriented Tools | ✅ no new tool | ✅ Version information is added to `list_nodes`/`get_health`; no raw version endpoint passthrough. |
| V. Adapter Isolation | ✅ | ✅ <ul><li>Matrix and version types in `domain`; gate and state in `application`.</li><li>Detection stays in `adapter/rest/v81`, the probe in `adapter/cli`; adapter selection stays in `AdapterGatewayFactory`.</li><li>Adding 9.0 later = adapters + a matrix entry (US3, D-3).</li><li>`PackageBoundaryTest` stays green.</li></ul> |
| VI. Bounded Output | ✅ | ✅ The version block is small and fixed in shape; warnings are one line per node. |
| VII. Observability & Auditability | ✅ | ✅ <ul><li>Every version refusal is logged; refused writes are audited `REFUSED` through the existing `Call.refuse`/`DeployGuard.refuse` (FR-015).</li><li>Detections and probe results are logged at INFO/WARN.</li></ul> |
| Tech constraints | ✅ | ✅ No new dependency, a single artifact, artifacts in English, user docs updated in the same change. |

**Result**: PASS. The ⚠️ item is resolved by D-5, D-11 and D-12. No Complexity Tracking entry.

## Project Structure

### Documentation (this feature)

```text
specs/009-inubit-version-detection/
├── plan.md
├── research.md                 # D-1…D-16
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── configuration-delta.md
│   └── mcp-tools-delta.md
├── checklists/requirements.md
├── reviews/                    # spec-r1…r4 (+ plan reviews)
└── tasks.md                    # /speckit-tasks
```

### Source Code

```text
src/main/java/de/dadecker/inubit/mcp/
├── config/                 # VersionSetting deserializer (VersionLine removed), EffectiveNodeConfig, Defaults,
│                           # ConfigValidator (concrete pin, V9_X text), ConfigSummary (client/version part)
├── domain/model/           # InubitVersion, VersionSetting value, Capability, SupportState, CapabilityMatrix,
│                           # ClientVersion, NodeVersionState, NodeSummary (+version block), VersionRange
├── domain/port/            # ClientVersionPort (new); GatewayFactory.detect (new); version-state callback
├── adapter/                # AdapterGatewayFactory: detect(node), PortSetRegistry (D-16), StaleReportingPorts (D-10)
├── adapter/rest/           # InubitHttpClient ResourceKind (endpoint 404 → UNEXPECTED_RESPONSE); v81 adapters mark endpoints
├── adapter/cli/            # CliVersionProbe implements ClientVersionPort; FR-011 comparison
├── application/            # NodeVersions, VersionGate (new); DevelopmentGuard (Operation enum), WriteGuard, DeployGuard,
│                           # DiagnosisService, InventoryService, ArtifactCheckService, WorkspaceService, HealthService call the gate
├── mcp/tools/              # ListNodesTool (supplier), read tools and restart/kill (warnings)
├── Launcher.java           # CliRunner for --check-config
└── Wiring.java             # NodeVersions/VersionGate wiring; announcement conditions (D-12)
src/main/resources/schemas/ # list_nodes, get_health, find_processes, query_logs, list_inventory, get_inventory_item,
                            # restart_process, kill_process outputs
src/test/…                  # tests per quickstart table; test-only matrix/adapter (US3)
src/test/resources/fixtures/v9_0/rest/  # synthetic system_info (D-15); fixtures/v8_1/cli/version variants
docs/                       # setup.md (versionLine values, behaviour), tools.md (list_nodes, get_health, warnings,
                            # UNSUPPORTED_VERSION, tool classification)
CHANGELOG.md                # [Unreleased]: feature 009 incl. the deliberate behaviour changes
```

**Structure Decision**: the same single module. Stages, each reviewed (Creator/Reviewer) and each
ending with a green `mvn verify`:

1. **Domain**: `InubitVersion`, `VersionSetting`, `Capability`, `SupportState`, `CapabilityMatrix`,
   `ClientVersion` with pure unit tests.
2. **Configuration (US4)**: `versionLine` concrete pins, deserializer, validation, `--check-config`
   text (client part later in stage 5).
3. **Version state and detection (US1 part)**: `NodeVersions`, `GatewayFactory.detect`, slot
   reporting, `get_health` observation, stale triggers.
4. **Gate and refusals (US2)**: `VersionGate`; guards and services; warnings on reads; audit; tool
   announcement (D-12).
5. **Client probe (US1, FR-011)**: `ClientVersionPort`, `get_health` part `client`, gate client
   check, `--check-config`.
6. **`list_nodes` (US1)** and **`deploy_release` (FR-014a)**.
7. **Port-set selection (D-16) and the extensibility proof (US3)**, docs, CHANGELOG, quickstart
   validation.

## Complexity Tracking

None.
