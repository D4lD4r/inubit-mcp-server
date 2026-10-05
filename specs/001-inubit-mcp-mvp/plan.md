# Implementation Plan: INUBIT MCP Server MVP

**Branch**: `001-inubit-mcp-mvp` | **Date**: 2026-10-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-inubit-mcp-mvp/spec.md`

## Summary

A local stdio MCP server, written in Java 21 on the official MCP Java SDK 2.0.1, that gives an AI
assistant eight use-case-oriented tools for INUBIT 8.1 (verified against 8.1.17 on DEV and QA):

- server listing
- health (P1)
- process search and log queries (P2)
- diagram and module inventory (P3)
- guarded restart and kill of single process instances (P4)

Each tool calls one or more servers, addressed by stage (`test`) or by server (`test/inubit01`). Reads use the
INUBIT REST API (`/ibis/rest`) wherever it offers the capability. StartCLI is used where REST does
not: process control, and the module list plus version history for the inventory (read-only
exports, cached per server). Process lists come from the REST `queueLog` (spike S-4). All INUBIT access sits behind port interfaces with
8.1-specific adapters, so that 9.x can be added later without changing the tools.

Safety lives in the server:

- read-only by default
- write access per stage or server, with an extra production lock
- server-side two-step confirmation by default
- an append-only, fsynced audit log
- credentials only from environment variables, with central scrubbing
- the CLI password is never put on the command line

Undocumented CLI behaviour (password input, exit codes, output formats) and some REST details are
resolved by verification spikes against the dev and test systems. Their recordings become the test
fixtures. See [research.md](research.md).

## Technical Context

**Language/Version**: Java 21 (`release 21`); runs on JDK 21+ (team has JDK 25)

**Primary Dependencies**:

| Dependency | Status |
|---|---|
| `io.modelcontextprotocol.sdk:mcp` 2.0.1 (via `mcp-bom`; brings Jackson 3, Reactor, json-schema-validator, SLF4J API) | runtime |
| `tools.jackson.dataformat:jackson-dataformat-yaml` (Jackson 3 line, aligned with the SDK) | runtime |
| `ch.qos.logback:logback-classic` | runtime |
| ~~`org.jetbrains.pty4j:pty4j`~~ | dropped: StartCLI accepts the password via stdin (S-2 ✅) |

**Storage**: local files only, namely the YAML config (no credentials) and the JSON Lines audit
log. Credentials come exclusively from environment variables (FR-002a). Confirmation codes are held in memory.

**Testing**: JUnit Jupiter + AssertJ (unit); WireMock 3.13.2 with recorded 8.1.17 responses (REST
contract); a fake process launcher with recorded StartCLI output (CLI contract); piped-stdio MCP
end-to-end tests; and opt-in live tests behind `@Tag("live")` and the Maven profile `live`

**Target Platform**: developer workstation on macOS or Linux, with a local INUBIT client
installation of the servers' patch level (8.1.17) for CLI-backed tools. Windows: REST tools only;
CLI-backed tools are not supported on Windows in the MVP (known limitation, research R-6; they
report `CLI_UNAVAILABLE`)

**Project Type**: single-module CLI application (MCP stdio server) distributed as one shaded JAR

**Performance Goals**: SC-002. A single-server health check takes ≤ 5 s. A health overview
of all servers (up to 10) takes ≤ 10 s even if one server is unreachable, thanks to
parallel fan-out on virtual threads with a deadline per server.

**Constraints**:

- stdout carries MCP protocol messages only
- no secrets in any output channel or in process arguments
- results ≤ 100 items or 50,000 chars (configurable)
- `cliTimeout` defaults to 30 s
- no undocumented INUBIT interfaces, and never the StartCLI cache mode

**Scale/Scope**: one developer per server instance; 5 stages with 1–2 standalone servers each
(≤ 10 servers); 8 MCP tools; INUBIT 8.1.x only, with 9.x prepared for but not supported

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design (below).*

| Principle | Gate | Pre-research | Post-design |
|---|---|---|---|
| I. Safe by Default | read-only default; write per stage/server; production lock; destructive annotations; input validation; no shell | ✅ spec FR-019…FR-024 | ✅ `WriteGuard` decision flow ([contracts/mcp-tools.md](contracts/mcp-tools.md)); write tools only registered when needed; argument-array CLI invocation with allow-listed tokens (R-6) |
| II. Secrets Stay Out | secrets only via config; never in results, logs or process args | ⚠️ StartCLI only documents `-p` | ✅ credentials only from environment variables by naming convention, never in the YAML (R-12); central `SecretScrubber`; `-p` is forbidden; the password goes to StartCLI via stdin (S-2 ✅). **Interpretation** (spec FR-025): "secrets" are passwords, the Basic-auth token and trust-store passwords; the username is a non-secret identifier, needed for audit attribution and passed with `-u` because StartCLI offers no other way. CLI-enabled servers use a password-less trust store (R-6). The StartCLI child gets an allowlisted environment (R-6, R-12) |
| III. Test-First | TDD; offline unit and contract tests; per-version fixtures; safety and scrubbing tests; live tests opt-in and never on production | ✅ | ✅ R-17 test pyramid; fixtures recorded by spikes (R-18); live harness refuses `production: true` |
| IV. Use-Case-Oriented Tools | small set; precise descriptions; typed schemas; documented outputs | ✅ | ✅ 8 tools, each mapped to a story; JSON schemas and output shapes in [contracts/mcp-tools.md](contracts/mcp-tools.md) |
| V. Adapter Isolation & Version Compatibility | ports and adapters; REST preferred; 9.x isolated; CLI with arg arrays, timeouts, exit codes, tested parser | ✅ | ✅ `domain.port` interfaces with `adapter.rest.v81` and `adapter.cli.v81`; CLI only for process control and inventory exports (module list, version history), which REST lacks (R-8 revised after S-4: process lists via REST `queueLog`; R-11); version-line selection per server |
| VI. Bounded, Structured Output | structured results; size limits; pagination; actionable errors | ✅ | ✅ `Page<T>`, `ResultLimiter`, `structuredContent` plus `outputSchema`, `ToolError` catalogue (data-model.md) |
| VII. Observability & Auditability | structured logs to stderr; audit for every write | ✅ | ✅ Logback JSON to stderr and stdout guard (R-16); JSONL audit with fsync and fail-closed (R-14) |
| Tech constraints | Java LTS ≥ 21; stdio; single artifact; TLS on by default; minimal pinned dependencies; English artifacts | ✅ | ✅ 3 runtime dependencies (pty4j dropped after S-2); shaded JAR; TLS trust store per stage/server |
| Workflow | SDD; branch per feature; review | ✅ | ✅ |

**Result**: PASS. Spike S-2 confirmed the login with the password via stdin on DEV, so Principle II
holds without a PTY. The pty4j dependency is dropped.

## Project Structure

### Documentation (this feature)

```text
specs/001-inubit-mcp-mvp/
├── plan.md              # This file
├── research.md          # Phase 0: decisions R-1…R-18, spikes S-0…S-6
├── data-model.md        # Phase 1: config, domain and result entities
├── quickstart.md        # Phase 1: build, configure, validation scenarios V1–V13
├── contracts/
│   ├── mcp-tools.md     # Phase 1: tool names, schemas, outputs, decision flow, error mapping
│   └── configuration.md # Phase 1: config file format and validation
├── checklists/
│   └── requirements.md  # spec quality checklist
└── tasks.md             # Phase 2 (/speckit-tasks — not created here)
```

### Source Code (repository root)

```text
pom.xml                                   # Java 21, mcp-bom, shade (ServicesResourceTransformer), profile "live"
README.md
tools/
├── record-fixtures.sh                    # R-18: record fixtures from DEV/QA (never TEST/STAGING/PROD)
└── anonymize.py                          # R-18: anonymize recorded fixtures (--self-test)
docs/
├── research/inubit-interfaces.md         # existing INUBIT interface research
├── setup.md                              # install, configure, register with MCP client
├── tools.md                              # tool reference for users (extended per user story)
└── live-tests.md                         # how to run opt-in live tests
src/main/java/de/dadecker/inubit/mcp/
├── Main.java                             # args, config load, stdout guard, wiring, server start
├── config/                               # ConfigLoader, ServerConfig, Defaults, StageConfig, ServerEntryConfig,
│                                         # Tls/Write/Cli/InventoryConfig, EffectiveServerConfig,
│                                         # CredentialResolver (env vars), ConfigValidator, ConfigSummary
├── domain/
│   ├── model/                            # ServerId, StageId, Target, ErrorCode, ToolError, Page, ServerResult,
│   │                                     # HealthReport, ProcessInstance, LogEntry, InventoryItem, ...
│   └── port/                             # MonitoringPort, ProcessQueryPort, LogPort, InventoryPort,
│                                         # ProcessControlPort, AuditPort, GatewayFactory
├── application/                          # TargetResolver, FanOut, ResultLimiter, HealthService,
│                                         # DiagnosisService, InventoryCache, InventoryService,
│                                         # WriteGuard, ConfirmationRegistry, ProcessControlService
├── adapter/
│   ├── AdapterGatewayFactory.java        # selects adapter set per server and version line
│   ├── V81InventoryAdapter.java          # combines REST + CLI inventory sources (R-11)
│   ├── rest/                             # InubitHttpClient, PinningTrustManager, SslContexts, XmlSupport
│   │   └── v81/                          # V81MonitoringAdapter, V81LogAdapter, LogRequestBuilder,
│   │                                     # LogFilterTable, V81ProcessQueryAdapter and
│   │                                     # QueueLogRowParser (queueLog),
│   │                                     # Model*/DiagramExport parsers, health parsers
│   └── cli/                              # ProcessLauncher, SystemProcessLauncher, CliCommand, CliRunner,
│       │                                 # CliResult, CliOutputClassifier, CliVersionProbe, CliExportRunner
│       └── v81/                          # V81ProcessControlAdapter, VersionHistoryParser,
│                                         # ModuleIndexParser
├── mcp/                                  # McpServerFactory, ToolHandler, SchemaResources, ResultMapper
│   └── tools/                            # one handler per tool (8)
└── infra/                                # Secret, SecretScrubber, ScrubbingJsonEncoder, StdoutGuard,
                                          # ClockProvider, AuditLog
src/main/resources/
├── schemas/                              # <tool>.input.json / <tool>.output.json (from contracts)
└── logback.xml                           # stderr, JSON encoder
src/test/java/de/dadecker/inubit/mcp/
├── <mirrors main packages>               # unit tests and contract tests (WireMock / FakeProcessLauncher
│                                         # + fixtures) next to the class under test's package
├── mcp/                                  # McpTestClient + piped-stdio end-to-end tool tests
├── live/                                 # @Tag("live"), profile "live", refuses production stages
├── security/                             # NoSecretLeakTest (SC-006)
├── performance/                          # HealthOverviewPerformanceTest (SC-002)
└── architecture/                         # PackageBoundaryTest (Constitution V, R-2)
src/test/resources/
├── config/                               # YAML test configurations
├── tls/                                  # test-only certificates / trust stores
└── fixtures/v8_1/
    ├── rest/                             # recorded, anonymized responses per endpoint
    └── cli/                              # recorded stdout/stderr/exit per command and case
```

**Structure Decision**: a single Maven module following ports and adapters. `domain` has no
dependencies. `application` depends only on `domain`. The adapters implement `domain.port`, and
`mcp` is the only package that imports the MCP SDK (R-2). Base package: `de.dadecker.inubit.mcp`
(Maven `groupId` `de.dadecker`, `artifactId` `inubit-mcp-server`).

> Note: namespace renamed in feature 002 (previously a different base package and `groupId`).

## Complexity Tracking

No constitution violations. (The reserved pty4j entry was dropped after spike S-2 succeeded.)
