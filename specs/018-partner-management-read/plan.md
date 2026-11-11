# Implementation Plan: Read the Partner Management

**Branch**: `018-partner-management-read` | **Date**: 2026-11-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/018-partner-management-read/spec.md` (approved in
spec review round 5).

## Summary

One new read tool, **`find_partners`**, answers "which connection settings does partner X have on
stage Y" and lists and compares the Partner Management of system diagrams. `get_inventory_item`
gets an optional `partnerUsage` for technical workflows (story 4). No second tool is added.

- **Channel REST, by evidence** (D-1): the recorded REST diagram export of a system diagram
  carries the same Partner Management as the StartCLI `systemdiagram` export, so the tool reads one
  REST export per system diagram, in memory, behind `PartnerManagementPort` (D-3, D-4).
- **Communication clouds** (D-2): a system diagram holds several `sdCommunicationCloud` modules,
  each with at most one Partner Management; results, ordering and the match key include the cloud
  (module name); entries resolve their workflow through `referencableTWFId`.
- **Masking inside the parser** (D-3): `PartnerManagementReader` in `adapter/archive/v81` reuses
  the workspace export's `SecretPaths`/`KeyMaterial` rules plus the name patterns of FR-008; raw
  values never leave it. A set secret carries only a process-keyed HMAC fingerprint (D-5) that is
  never serialized.
- **Cache with coverage** (D-6): `InventoryCache` with owner-scoped keys (`systemdiagrams:<owner>`,
  `partners:<owner>`, `partners:<owner>|<group>/<name>`, kind `DIAGRAM`); single-flight and TTL
  unchanged.
- **Comparison** (D-10): a pure `PartnerComparison` groups equal and differing settings, `onlyOn`
  markers, `sameOn` groups for secrets (only on INUBIT 8.1, D-5) and failed servers.
- **Capability** `PARTNER_MANAGEMENT` (D-7): 8.1 supported, 9.0 and others warn, 6.1 refused.
- **Audit** (D-8): one record per server with a shared `callId` and `details` counts, also for cache
  hits; a failed audit write ends the call with `INTERNAL` and no data.

## Technical Context

**Language/Version**: Java 21 (unchanged).

**Primary Dependencies**: unchanged (MCP Java SDK, Jackson, logback). HMAC-SHA256 and
`SecureRandom` come from the JDK (`javax.crypto.Mac`). No new dependency.

**Storage**: none new. Masked partner models in the in-memory `InventoryCache`; audit records in
the existing JSONL audit log; REST exports only in memory (never on disk, FR-010).

**Testing**:
- JUnit 5, AssertJ; WireMock (REST) through `InventoryToolFixture`;
- recorded fixtures `fixtures/v8_1/rest/model_export_systemdiagram.*` and
  `fixtures/v8_1/cli/export_systemdiagram.*` (recorded 2026-11-09, neutralized; the CLI one is a
  parser fixture and the evidence for D-1), plus synthetic fixtures built from them (`PartnerFixtures`: 3 detail partners, 120 and 1,000 list partners, two stages with
  known differences);
- secret-leak tests in `security/` (`PartnerSecretLeakTest`), covering result, error, log, audit
  and cache contents; `NoCustomerIdentifiersTest` and `tools/check-identifiers.py`;
- `PackageBoundaryTest` keeps HTTP/process types out of `application` and `domain`.

**Target Platform**: macOS, Linux, Windows (REST only; no StartCLI involved).

**Project Type**: single Maven module, shaded JAR.

**Performance Goals**: SC-005: on the 1,000-partner fixture, parse, mask, fingerprint and page in
at most 5 s beyond the read itself (expected well below 1 s); a repeated call within
`inventory.cacheTtl` makes no read.

**Constraints**:
- no raw secret value outside `PartnerManagementReader` (FR-010, SC-002);
- no write interface of INUBIT is called; the `PartnerManagementRestlet` is never referenced;
- results bounded by `resultLimits` (FR-017);
- no customer identifiers in code, fixtures, docs or commits (FR-023).

**Scale/Scope**:
- 1 new tool (`find_partners`, tool count 17 → 18); `get_inventory_item` output gains
  `partnerUsage`; `list_nodes` and `--check-config` show `partner_management`.
- New production types (about 21): `PartnerManagementPort`, `SystemDiagramPartners`,
  `CommunicationCloud`, `Partner`, `PartnerField`, `PartnerKey`, `Setting`, `ProtocolEntry`,
  `SettingValue`, `SecretFingerprint`, `WorkflowRef`, `DiagramRef`, `PartnerQuery`,
  `PartnerUsage` (domain); `PartnerManagementReader`, `PartnerSecretRules`,
  `SecretFingerprinter`, `V81PartnerManagementAdapter` (adapter); `PartnerService`, `PartnerComparison`,
  `PartnerAudit`, `PartnerSnapshot` (application); `FindPartnersTool` (mcp/tools).
- Extended: `Capability`, `CapabilityMatrix`, `NodeOverview`, `ConfigSummary`,
  `InventoryCache` (`peek`), `Gateway`, `V81Gateway`, `PortSet`, `V81PortSet`, `V61PortSet`,
  `RefusingPorts`, `StaleReportingPorts`, `SecretPaths` (visibility), `InventoryPort.DiagramMetadata`, `AuditRecord`, `AuditLog`, `InventoryDetail`, `DiagramExportParser`,
  `InventoryService`, `InventoryBounds`, `AdapterGatewayFactory`, `Wiring`, `tools/anonymize.py` (`format` as a generic value),
  schemas, docs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.* Constitution v1.1.0.

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ✅ read only | ✅ <ul><li>Read-only hints; no write gate needed.</li><li>No interface that changes the Partner Management; the `PartnerManagementRestlet` is never called (a test greps the adapter for it).</li><li>Inputs validated before INUBIT is contacted.</li><li>REST channel (D-1): a GET of the diagram export; no INUBIT state changes, not even the StartCLI export trace.</li></ul> |
| II. Secrets Stay Out | ⚠️ new data class with many secret forms | ✅ <ul><li>Masking inside the parser (D-3), at least as strict as the workspace export by reuse of `SecretPaths`/`KeyMaterial`, plus name patterns.</li><li>Only "set"/"not set" and a process-keyed HMAC leave the parser; the fingerprint has no accessor, no serializer and a redacted `toString` (D-5).</li><li>Mandatory tests: clear-text legacy password, password without `encrypted`, secret in a custom partner field, key material in a plain field, `WSSecurityPassword` without type; checks on result, error, log, audit and cache.</li><li>Search and suggestions use non-masked fields only (FR-011).</li></ul> |
| III. Test-First | ⚠️ no recorded system diagram | ✅ <ul><li>Neutralized 8.1 recordings of both channels exist (2026-11-09); contract tests run the REST adapter and the parser against them.</li><li>Every task starts with failing tests ([quickstart.md](quickstart.md) table).</li></ul> |
| IV. Use-Case-Oriented Tools | ✅ one tool, spec states use cases | ✅ `find_partners` covers search, list and compare; story 4 is a field of `get_inventory_item`; SC-006 holds. |
| V. Adapter Isolation & Version Compatibility | ⚠️ REST preferred, channel unknown | ✅ <ul><li>REST, decided by recorded evidence (D-1); one port, one adapter.</li><li>Only 8.1 is declared supported (recorded); 9.0 and others run with a warning; 6.1 refused (D-7).</li><li>No version branching in tool logic; the comparability set of D-5 is data next to the matrix.</li></ul> |
| VI. Bounded, Structured Output | ✅ | ✅ Paginated (`limit` 1–100, default 20), texts cut with marker, detail cut before entries, `truncated` reported; per-server errors are structured `ToolError`s with cause and next step. |
| VII. Observability & Auditability | ✅ | ✅ <ul><li>One audit record per server with `callId`, `fromCache` and counts (D-8); no partner settings in it.</li><li>One INFO log line per server (server, owner, outcome, counts, call id).</li></ul> |
| Tech constraints | ✅ | ✅ No new dependency; TLS/pinning unchanged (existing REST client). |

No violation; Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
specs/018-partner-management-read/
├── spec.md
├── plan.md              # this file
├── research.md          # D-1 … D-12
├── data-model.md        # domain types, snapshot, comparison, audit fields
├── quickstart.md        # T-record, validation scenarios, test map
├── contracts/
│   ├── find_partners.md          # tool input/output contract
│   └── other-changes.md          # get_inventory_item, list_nodes, --check-config, audit record
├── checklists/requirements.md
├── reviews/
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
src/main/java/de/dadecker/inubit/mcp/
├── domain/
│   ├── model/
│   │   ├── Capability.java                  # + PARTNER_MANAGEMENT
│   │   ├── CapabilityMatrix.java            # 6.1 UNSUPPORTED, 9.0 UNVERIFIED for it
│   │   ├── AuditRecord.java                 # + callId, details (additive)
│   │   ├── InventoryDetail.java             # + partnerUsage
│   │   └── partner/                         # new: the 14 domain types of Scale/Scope
│   │                                        #      (SystemDiagramPartners … PartnerUsage) and
│   │                                        #      package-info.java
│   └── port/PartnerManagementPort.java      # new
├── adapter/
│   ├── archive/v81/
│   │   ├── PartnerManagementReader.java     # new: parse + mask (D-3)
│   │   ├── PartnerSecretRules.java          # new: rules of FR-008 on top of SecretPaths
│   │   └── SecretFingerprinter.java         # new: per-process HMAC key (D-5)
│   ├── rest/v81/
│   │   ├── DiagramExportParser.java         # + partner variables, PartnerManagement node (D-11)
│   │   └── V81PartnerManagementAdapter.java # new: REST export per system diagram (D-1)
│   ├── PortSet.java, V81PortSet.java, V81Gateway.java, RefusingPorts.java,
│   │   StaleReportingPorts.java, cli/v61/V61PortSet.java   # port in the gateway (D-4)
├── application/
│   ├── PartnerService.java                  # fan-out, gate, cache, filters, paging, audit
│   ├── PartnerComparison.java               # pure comparison (D-10)
│   ├── PartnerAudit.java                    # per-call audit helper (D-8)
│   ├── PartnerSnapshot.java                 # cached value with coverage (D-6)
│   ├── InventoryCache.java                  # + peek
│   ├── InventoryService.java                # partnerUsage lookup (D-11)
│   └── NodeOverview.java                    # + partner_management
├── infra/AuditLog.java                      # writes callId, details
├── config/ConfigSummary.java                # capability line
├── mcp/tools/FindPartnersTool.java          # new
└── Wiring.java                              # register tool and service

src/main/resources/schemas/
├── find_partners.input.json, find_partners.output.json   # new
└── get_inventory_item.output.json                         # + partnerUsage

src/test/java/de/dadecker/inubit/mcp/
├── adapter/archive/v81/PartnerManagementReaderTest.java, PartnerSecretRulesTest.java,
│   SecretFingerprinterTest.java, PartnerFixtures.java
├── adapter/rest/v81/V81PartnerManagementAdapterContractTest.java
├── application/PartnerServiceTest.java, PartnerComparisonTest.java, PartnerAuditTest.java,
│   InventoryCachePeekTest.java
├── mcp/tools/FindPartnersToolTest.java
└── security/PartnerSecretLeakTest.java

src/test/resources/fixtures/v8_1/{rest,cli}/…systemdiagram…   # T-record, neutralized
tools/record-fixtures.sh, tools/anonymize.py                 # + the two cases, partner neutralization (.ps1 has no REST cases)

docs/tools.md (new section, capability table, "eighteen"), README.md, SECURITY.md, CHANGELOG.md,
docs/live-tests.md (neutralized live check), docs/backlog.md
```

**Structure Decision**: the existing single module and its layering (domain ← application ←
adapter/mcp) are kept; partner types get their own `domain/model/partner` package because there
are fourteen of them.

## Implementation order

1. **T-record** — done 2026-11-09: both channels recorded and neutralized; D-1 = REST, D-2 facts
   in research.md; spec amended for communication clouds.
2. Anonymizer keeps `fieldType` and treats `format` as a generic value; the operator records
   `model_export_systemdiagram` again to fix `PLAIN_FIELD_TYPES` (FR-008). Then domain types and
   `SecretFingerprint`; `PartnerSecretRules`, `SecretFingerprinter`,
   `PartnerManagementReader` against synthetic and recorded fixtures (secret tests first).
3. Port and the REST adapter with contract tests; capability and matrix entries.
4. `InventoryCache.peek`, `PartnerSnapshot`, `AuditRecord`/`AuditLog` fields, `PartnerAudit`.
5. `PartnerService` on one server (read path, search, similar values, audit and INFO log line),
   `FindPartnersTool`, schemas, wiring; then list, filters, paging, bounds.
6. `PartnerComparison` and its integration (stage targets, `compareWith`), comparison bounds.
7. `list_nodes`, `--check-config` (with the capability in step 3).
8. Story 4 (`DiagramExportParser`, `partnerUsage`).
9. Docs, `PartnerSecretLeakTest` over the whole tool, identifier check, live check (neutralized
   entry in `docs/live-tests.md`).

## Complexity Tracking

No deviations from the constitution.
