# Implementation Plan: Read-Only INUBIT 6.1 Support via StartCLI Only

**Branch**: `011-inubit-61-cli-reads` | **Date**: 2026-10-31 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/011-inubit-61-cli-reads/spec.md`

## Summary

A node pinned to INUBIT 6.1 (`versionLine: V6_1` or a concrete 6.1 version) is served read-only
through StartCLI only. No tool is added. Instead:

- the capability **"diagnosis" is split** into process diagnosis and log diagnosis for every
  version (D-2);
- the capability matrix gets a **6.1 entry that is CLI-only**: per-capability channels (StartCLI
  for monitoring and process diagnosis) and no 8.1 fallback — anything not `SUPPORTED` is refused
  (D-3). A 6.1 capability becomes `SUPPORTED` in production only when its recorded fixtures are in
  the repository (FR-005, D-15);
- a **6.1 port set** in `adapter/cli/v61` (health and process listing; refusing stubs for the
  rest) is selected through the existing `PortSetRegistry` (D-4);
- StartCLI runs as **one interactive session per call** (password and commands on stdin), with a
  per-node limit of two concurrent processes (D-5, D-9);
- `get_health` gets a **CLI path** (alive, version, maintenance, memory, process counts; parts 6.1
  lacks reported as `NOT_AVAILABLE_ON_6_1`) with a 15 s single-node budget (D-6);
- `find_processes` reads **`ps` listings** with server-side filtering, paging and a truncation
  marker (D-10);
- the server **checks the certificate itself** by a handshake-only TLS connection before StartCLI
  runs; the client gets the trust store by its own option or by its runtime settings, never a
  password, and skips host names only through its own option (D-7, D-8);
- the gate refuses everything but `get_health` on a contradicted 6.1 pin, `AUTO` nodes get a
  conditional "pin V6_1" hint after a 404 detection, and read tools no node can run are not
  announced (D-11, D-12);
- `list_nodes` shows `access: cli_only`, `--check-config` shows the 6.1 line and the client
  options (D-13);
- the **export (US4)** stays refused on 6.1 until sample exports exist (D-17).

The live recordings (FR-020) are a manual, approved, read-only step in the middle of the work;
everything before it is built and tested offline against fixtures reconstructed from the vendor
documentation.

## Technical Context

**Language/Version**: Java 21 (unchanged).

**Primary Dependencies**: unchanged, no new dependency (MCP Java SDK, Jackson, logback; JDK
`javax.net.ssl` for the handshake check).

**Storage**: none new; client options and version state in memory per process; fixtures under
`src/test/resources/fixtures/v6_1/cli/`.

**Testing**:
- JUnit 5, AssertJ;
- `FakeProcessLauncher` / `ScriptedProcessLauncher` with 6.1 cases (reconstructed, then recorded);
- WireMock with `verify(0, anyRequestedFor(anyUrl()))` to prove that no REST request reaches a 6.1
  node; `TestCertificates.httpsWireMock` as the TLS test server for the handshake check;
- a test capability matrix with 6.1 `SUPPORTED` (pattern of `CapabilityExtensionTest`);
- `V61RecordedFixturesTest` (production matrix vs. `RECONSTRUCTED.txt`);
- timing: `HealthOverviewPerformanceTest`, a new single-node 6.1 timing test.

**Target Platform**: macOS and Linux (CLI-backed tools are not supported on Windows, as today).

**Project Type**: a single Maven module, built as a shaded JAR.

**Performance Goals**: `get_health` on one 6.1 node ≤ 15 s; an overview with 6.1 nodes ≤ 10 s
(SC-001); `find_processes` on a 6.1 node ≤ 15 s for up to 100 instances (SC-002). One JVM start
and one login per call and node (D-5).

**Constraints**:
- no REST or SOAP request of the server to a 6.1 node; only StartCLI and the handshake (FR-001);
- refusals before any launch, handshake or request (FR-007);
- the password only on stdin; no trust-store password ever reaches the client (FR-011, FR-012a);
- at most two concurrent StartCLI processes per 6.1 node (FR-015);
- no version-string branching in tool logic (FR-016);
- 8.1/9.x behaviour unchanged apart from SC-005's deliberate changes.

**Scale/Scope**:
- 0 new tools. Changed outputs: `list_nodes` (capability names, `access`), `get_health`
  (`processCounts`, optional thread/queue figures, new unavailable parts), `find_processes`
  (6.1 warning), refusal texts; schemas accordingly.
- New production classes (about 19): `ProcessCounts`, `MemoryFigures`, `CliHealthSnapshot`,
  `CliClientOptions`, `NodeAccess` (domain); `CliHealthPort` (port); `V61PortSet`,
  `V61HealthAdapter`, `V61ProcessQueryAdapter`, `V61SessionParser`, `V61PsParser`,
  `V61HealthParser`, `V61Connection`, `RefusingPorts`, `CliSessionCommand`, `CliClientProbe`,
  `CliConcurrencyLimiter`, `CliDates`, `TlsHandshakeCheck` (adapter).
- Extended: `VersionSetting`, `Capability`, `CapabilityMatrix`, `NodeVersionState`,
  `NodeVersions`, `HealthReport`, `LoadFigures`, `UnavailablePart`, `ConfigLoader`,
  `ConfigValidator`, `ConfigSummary`, `Launcher`, `CliRunner`, `CliOutputClassifier`,
  `V81VersionDetector`, `PortSetRegistry`, `AdapterGatewayFactory`, `VersionGate`,
  `HealthService`, `DiagnosisService`, `ArtifactCheckService`, `NodeOverview`, `Wiring`,
  `FakeProcessLauncher`, `tools/record-fixtures.sh`, schemas, docs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.* Constitution v1.1.0.

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ✅ read-only feature | ✅ <ul><li>Every write capability is `UNSUPPORTED` on 6.1 and refused before contact (FR-007, D-3).</li><li>A `cliOnly` entry never falls back to 8.1 adapters.</li><li>Inputs are validated before any StartCLI run; session commands come from an allowlist (`CliSessionCommand`), never from free text.</li></ul> |
| II. Secrets Stay Out | ⚠️ the 6.1 client documents no trust-store option, so the trust store may have to go through runtime settings | ✅ <ul><li>Password only on stdin (FR-011).</li><li>Trust store via the client option or `JAVA_TOOL_OPTIONS` path/type only; a trust-store password with a CLI is a configuration error (existing rule, D-8).</li><li>Outputs and logs scrubbed as today.</li></ul> |
| III. Test-First | ⚠️ no recorded 6.1 outputs yet | ✅ <ul><li>Tests first against reconstructed fixtures; production `SUPPORTED` only with recorded fixtures, enforced by `V61RecordedFixturesTest` (FR-005, D-15).</li><li>Every refusal and every TLS/secret rule has its own test (quickstart table).</li><li>Recordings are manual and approved, not automated tests; automated tests never contact a 6.1 node.</li></ul> |
| IV. Use-Case-Oriented Tools | ✅ no new tool | ✅ The existing tools answer for 6.1; no raw `ps`/StartCLI passthrough. |
| V. Adapter Isolation & Version Compatibility | ✅ v1.1.0 allows a CLI-only channel for 6.1 | ✅ <ul><li>6.1 code only in `adapter/cli/v61`, selected by `PortSetRegistry` (D-4).</li><li>Channels and `cliOnly` are declared in the domain matrix; the gate and `HealthService` branch on the declared channel, not on version strings (FR-016).</li><li>CLI invocations use argument arrays, timeouts, exit codes and tested parsers.</li></ul> |
| VI. Bounded, Structured Output | ✅ | ✅ Listings capped at 10,000 rows with `truncated`/`totalIsLowerBound`; health parts structured; refusals actionable. |
| VII. Observability & Auditability | ✅ | ✅ Version refusals logged by `VersionGate.refuse()`; no write runs on 6.1. |
| Tech constraints (TLS for CLI connections) | ⚠️ today the pin is checked only on the REST path | ✅ The server verifies the pinned certificate by its own handshake before StartCLI connects (D-7), as v1.1.0 requires. |

**Result**: PASS. The ⚠️ items are resolved by D-7, D-8 and D-15. No Complexity Tracking entry.

## Project Structure

### Documentation (this feature)

```text
specs/011-inubit-61-cli-reads/
├── plan.md
├── research.md                 # D-1…D-18
├── data-model.md
├── quickstart.md               # offline table, live recordings, validation record
├── contracts/
│   ├── configuration-delta.md
│   └── mcp-tools-delta.md
├── checklists/requirements.md
├── reviews/                    # spec-r1…r3 (+ plan reviews)
└── tasks.md                    # /speckit-tasks
```

### Source Code (repository root)

```text
src/main/java/de/dadecker/inubit/mcp/
├── domain/model/        # VersionSetting (V6_1), Capability (split), CapabilityMatrix (channels, cliOnly, 6.1 entry),
│                        # NodeVersionState (endpointMissing), HealthReport (+processCounts), LoadFigures (optional
│                        # threads/queue), UnavailablePart (+threads, +blockingQueue), ProcessCounts, MemoryFigures,
│                        # CliHealthSnapshot, CliClientOptions, NodeAccess
├── domain/port/         # CliHealthPort (new); GatewayFactory (+cliHealth, +access); ClientVersionPort unchanged
├── adapter/             # PortSetRegistry (+cliHealth factory), AdapterGatewayFactory (Slot.cliHealth), RefusingPorts
├── adapter/cli/         # CliRunner.runSession, CliSessionCommand, CliClientProbe, CliConcurrencyLimiter, CliDates,
│                        # CliOutputClassifier (session prompts)
├── adapter/cli/v61/     # V61PortSet, V61HealthAdapter, V61ProcessQueryAdapter, V61SessionParser, V61PsParser,
│                        # V61HealthParser, V61Connection (all new)
├── adapter/rest/        # TlsHandshakeCheck (new); v81/V81VersionDetector (404 flag)
├── application/         # VersionGate (cliOnly, channels, contradiction, hint), NodeVersions, HealthService (CLI path,
│                        # budgets), DiagnosisService (split admission, 6.1 warning), ArtifactCheckService (text),
│                        # NodeOverview (names, access)
├── config/              # ConfigLoader (V6_1, error text), ConfigValidator (6.1 warnings), ConfigSummary (6.1 line)
├── Launcher.java        # client options in --check-config
└── Wiring.java          # 6.1 registration, limiter, handshake check, read-tool announcement
src/main/resources/schemas/   # list_nodes, get_health, find_processes outputs
src/test/java/...             # tests per quickstart table; V61RecordedFixturesTest
src/test/resources/fixtures/v6_1/cli/   # README.md, RECONSTRUCTED.txt, <case>.stdout/.stderr/.exit
tools/record-fixtures.sh      # v61_* catalogue cases (StartCLI only)
docs/                         # setup.md, tools.md, research/inubit-interfaces.md (6.1 section)
README.md, CHANGELOG.md
```

**Structure Decision**: the same single module. Stages, each reviewed (creator/reviewer) and each
ending with a green `mvn -q -o clean verify`:

1. **Version model, capability split and gate (foundation)**: `V6_1`, concrete 6.1 pins,
   capability split with all usages and schema names, matrix channels/`cliOnly` with the 6.1
   entry (`UNSUPPORTED`), gate rules, and the monitoring admission that keeps `get_health` from
   sending REST requests to a 6.1 node; 8.1 behaviour otherwise unchanged.
2. **Refusals (US3)**: refusal texts, the per-tool refusal matrix (incl. `get_health` with the
   production matrix), `check_artifacts`, `AUTO` 404 hint, read-tool announcement, `list_nodes`
   access, `--check-config` 6.1 line.
3. **StartCLI session, client probe, TLS and limits (US5)**: `TlsHandshakeCheck`,
   `CliRunner.runSession`, `CliClientProbe` options (6.1 only; the 8.1 argument array unchanged),
   client trust arguments, `CliConcurrencyLimiter`, `V61Connection`, check-config warnings.
4. **Health on 6.1 (US1)**: reconstructed fixtures, report model, parsers, selection plumbing,
   `V61HealthAdapter`, `HealthService` CLI path and budgets.
5. **Processes on 6.1 (US2)**: reconstructed fixtures, `CliDates`, `V61PsParser`,
   `V61ProcessQueryAdapter`, `DiagnosisService` per-node refusal and warning.
6. **Recordings (manual, approved; FR-020)**: catalogue cases, recording and neutralization,
   recorded health and `ps` fixtures with parser changes, the conditional REC tasks, the matrix
   flip. If the interactive mode cannot be driven through stdin (D-5), plan and tasks are updated
   with new tasks before continuing. Blocked on the person's approval and a 6.1 node.
7. **Docs, CHANGELOG, quickstart validation** (the feature is complete here, FR-023).
8. **US4 export (optional, later)**: blocked on sample exports and a research addendum (D-17).

## Complexity Tracking

None.
