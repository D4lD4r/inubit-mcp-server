# Tasks: Read the Partner Management

**Input**: Design documents from `specs/018-partner-management-read/` (spec approved in review
round 5, plan, research D-1…D-12 with the T-record results, data-model, contracts, quickstart)

**Tests**: mandatory (constitution Principle III, TDD): each implementation task starts with its
failing test in the same task. No test contacts INUBIT; REST behaviour is tested with WireMock and
the recorded fixtures `src/test/resources/fixtures/v8_1/rest/model_export_systemdiagram.*` and
`src/test/resources/fixtures/v8_1/cli/export_systemdiagram.*`, plus synthetic fixtures built from
their structure (ACME, `*.example.test`, `Workflow-0001`; never a real value).

**Build**: `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-018-tmp` (never
two builds in parallel); Python: `python3 -I -m unittest discover -s tools/tests`. Paths below are
relative to `src/main/java/de/dadecker/inubit/mcp/` (main) and `src/test/java/de/dadecker/inubit/mcp/`
(test) unless they start with `src/`, `docs/`, `tools/`, `specs/`.

**Secrets (FR-008…FR-011, SC-002, Constitution II)**: every task that adds a result field, error
message, warning, log line or audit field asserts that none of the synthetic secret values of
`PartnerFixtures` (AES value, clear-text legacy password, `WSSecurityPassword` without type, secret
custom partner field, key material in a plain field) nor anything derived from them (length, hash,
prefix) appears in it — named below as "leak check".

**Identifiers (FR-023)**: before every commit `python3 tools/check-identifiers.py --staged`.

**Reviews**: after each phase a `code-review` reviewer checks the phase diff; findings and
decisions go to `specs/018-partner-management-read/reviews.md` ("Implementation — phase N").

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [X] T001 Baseline: the build above is green on branch `018-partner-management-read`; note the test
  count in `specs/018-partner-management-read/reviews.md` ("Implementation — baseline")
- [X] T002 `tools/anonymize.py`: inside a `PartnerManagementSettings` subtree keep the attribute
  `fieldType` (a type, not data) and classify the attribute `format` as a generic value
  (`format-<n>`), not as a workflow reference (research D-2); `--verify` accepts kept `fieldType`
  values only if they match `^[A-Za-z][A-Za-z0-9_.]{0,40}$`. Tests first in
  `tools/tests/test_anonymize_partner.py`; update the self-test
- [X] T003 Operator (live, non-production, after T002): record **both** `model_export_systemdiagram`
  and `export_systemdiagram` again with the commands of quickstart.md §1 (stage `ent`, the same
  system diagram and group, one shared `RECORD_ANON_STATE`); neutralize names outside the subtree as
  on 2026-11-09 (local map, deleted afterwards); check with `anonymize.py --verify` and
  `check-identifiers.py --all`; record the plain `fieldType` values as `PLAIN_FIELD_TYPES` in
  `specs/018-partner-management-read/research.md` (D-2 facts). **Blocks T008, T010, T011 and every
  search task** (without the allowlist FR-008 masks `Id` and `Name`)

## Phase 2: Foundational — model, masking, REST read, capability, cache, audit (blocks every story)

### Domain types (data-model.md)

- [X] T004 [P] `DiagramRef(name, diagramGroup)` ("two refs are equal only if both fields are
  equal") and `WorkflowRef(Optional<String> name)` ("empty if the id does not resolve (shown as
  unresolved, with a warning)") with tests; `domain/model/partner/package-info.java` — main
  `domain/model/partner/DiagramRef.java`,
  `domain/model/partner/WorkflowRef.java`, test `domain/model/partner/DiagramRefTest.java`
- [X] T005 [P] `SettingValue` sealed: `Shown(String value)` ("cut at 200 characters with
  `…[truncated]` when rendered (not when compared)") and `Masked(boolean set,
  Optional<SecretFingerprint> fingerprint)` ("the fingerprint is present only when `set`"); and
  `SecretFingerprint` (final class, not a record; bytes private; `equals`/`hashCode` over the bytes;
  `toString()` = `SecretFingerprint[redacted]`; no getter, no properties, no Jackson annotation —
  test serializes it with the `infra/ResultJson` mapper and expects an exception
  (a refusing serializer registered for `SecretFingerprint` in `ResultJson`)) — main
  `domain/model/partner/SettingValue.java`, `domain/model/partner/SecretFingerprint.java`, tests
  `domain/model/partner/SettingValueTest.java`, `domain/model/partner/SecretFingerprintTest.java`
- [X] T006 (after T004, T005) `Setting(name, SettingValue)`, `ProtocolEntry(protocol, WorkflowRef
  workflow, Optional<String> format, List<Setting> protocolSettings, List<Setting>
  additionalParameters, int position)` ("index among entries with the same `(protocol,
  workflow)`"), `Partner(List<Setting> fields, List<ProtocolEntry> entries, int maskedCount)`,
  `PartnerField(name, fieldType)`, `CommunicationCloud(moduleName, moduleId, List<PartnerField>
  partnerFields, List<String> referencableWorkflows, List<Partner> partners)` with
  `keyOf(DiagramRef, Partner)` → `Optional<PartnerKey(diagram, cloudModuleName, "Id", value)>`
  (empty when `Id` is absent or masked; the both-sides fallback belongs to `PartnerComparison`),
  `SystemDiagramPartners(DiagramRef diagram, List<CommunicationCloud> clouds)` — main
  `domain/model/partner/*.java`, test `domain/model/partner/CommunicationCloudTest.java`
- [X] T007 [P] `PartnerQuery` with validation (FR-002): `target` required; `compareWith` equal to
  or overlapping `target` (same id, or a stage and one of its servers) → `INVALID_INPUT`; `owner`
  `^[A-Za-z0-9_.][A-Za-z0-9_.\- ]{0,199}$` (as `export_archives`); `search` 1–200 code points, no
  ISO control characters, letters with diacritics, `&`, `/` allowed; `systemDiagram`, `workflow`
  as the existing diagram names; `protocol` "1–64 characters `[A-Za-z0-9_-]`"; `offset` ≥ 0
  default 0; `limit` 1–100 default 20; `refresh` default false — main
  `domain/model/partner/PartnerQuery.java`, test `domain/model/partner/PartnerQueryTest.java`

### Masking and parsing (D-3, D-5)

- [X] T008 (after T003) `PartnerSecretRules` in the archive package; widen
  `SecretPaths.kind(name, type, encrypted, value)` (returns `null` when not secret) and
  `SecretPaths.isKeyMaterial(Element, type, value)` from `private` to package-private, no behaviour
  change. Masked when (1) `SecretPaths.kind(…) != null`, (2) `type="Password"` or
  `encrypted="true"`, (3) the name contains case-insensitively `password`, `passwd`, `pwd`,
  `secret`, `token`, `keystore`, `privatekey`, `passphrase`, `credential`, `apikey`, `accesskey`,
  (4) the value is key material, (5) a partner field whose `fieldType` is not on
  `PLAIN_FIELD_TYPES` (T003; unknown types masked). Tests: every `SecretPaths` case of the
  workspace export is also masked here ("at least as strict", FR-008); clear-text legacy
  `Password` without `encrypted`; `WSSecurityPassword` without type; `SSLKeyStore` of type
  `HTTPConnectorTrustStore` masked by name; key material in a plain field; unknown `fieldType` —
  main `adapter/archive/v81/PartnerSecretRules.java`, `adapter/archive/v81/SecretPaths.java`, test
  `adapter/archive/v81/PartnerSecretRulesTest.java`
- [X] T009 (after T005) `SecretFingerprinter`: 32-byte key from `SecureRandom`, created once per
  process in memory; `HMAC-SHA256(key, UTF-8 bytes of the stored value)` → `SecretFingerprint`;
  tests: equal values → equal fingerprints, two instances (simulated processes) → different
  fingerprints, the key is never reachable (no getter, `toString` redacted) — main
  `adapter/archive/v81/SecretFingerprinter.java`, test
  `adapter/archive/v81/SecretFingerprinterTest.java`
- [X] T010 (after T003, T006) `PartnerFixtures` (test support): synthetic `workflow.xml` builders in
  the recorded structure (research D-2: `WorkflowModule moduleType="sdCommunicationCloud"` with
  `ModuleId`, `ModuleName`, `Properties/Property name="PartnerManagementSettings" type="Map"` →
  `PartnerFields` with the recorded plain `fieldType` values, `ReferencableTWFs` (`name`, `id`,
  `moduleId`), `Partners/Partner/Fields`, `ReferencedTWFs/ReferencedTWF` with `protocol`,
  `referencableTWFId`, `format`); one entry with an unknown protocol (`AS4`) and an unknown setting
  name; sets: 3 detail partners (FTP with AES password, SFTP-via-FTP with
  clear-text legacy password, WebService with WS-Security credentials), 120 and 1,000 list
  partners, two clouds in one diagram sharing `ACME-0001`, one entry with an unresolvable
  `referencableTWFId`, two stages × two servers with known differences (US3 independent test) —
  test `adapter/archive/v81/PartnerFixtures.java`
- [X] T011 (after T008, T009, T010) `PartnerManagementReader.read(DiagramRef, XmlTree.Element
  workflowRoot)` → `SystemDiagramPartners` plus warnings: one `CommunicationCloud` per
  `sdCommunicationCloud` module with `PartnerManagementSettings`; values masked inside the method
  that reads them (FR-010); entries resolve `referencableTWFId` (unresolved → empty name and a
  warning naming partner key and cloud); entries keep export order and are never merged;
  `maskedCount` per partner; unknown protocols and setting names are kept with the same masking
  rules, nothing dropped. Tests against `PartnerFixtures` and both recorded fixtures
  (`model_export_systemdiagram.zip` two clouds, `export_systemdiagram.zip` three clouds);
  `PartnerChannelDecisionTest`: the REST and CLI fixtures give equal models per cloud (D-1
  regression); leak check on `toString` of every produced object — main
  `adapter/archive/v81/PartnerManagementReader.java`, tests
  `adapter/archive/v81/PartnerManagementReaderTest.java`,
  `adapter/archive/v81/PartnerChannelDecisionTest.java`

### Port and REST adapter (D-1, D-4)

- [X] T012 (after T011) `PartnerManagementPort.read(String owner, List<DiagramRef> systemDiagrams)`
  → `List<SystemDiagramPartners>` (masked only, with warnings) and `V81PartnerManagementAdapter`:
  one `GET /ibis/rest/model/export/<name>` per system diagram (as
  `V81InventoryAdapter.diagramMetadata`, in memory, bounded by `InubitHttpClient.MAX_BODY_BYTES`,
  never on disk), `ArchiveReader`/`XmlTree` → `PartnerManagementReader`. Collision check (D-4): the
  archive's `WorkflowGroupName` and `UserOrUserGroupName` must equal the requested group and owner,
  else no data for that diagram and a per-diagram entry in the port's result (`unreadableDiagrams`,
  code `UNEXPECTED_RESPONSE`, "the export of this name returned a diagram of another group or
  owner; the name is not unique"; several diagrams of the name in the requested group and owner:
  `UNEXPECTED_RESPONSE`, "the export of this name holds several diagrams of this name in this group;
  the name is not unique"; an export answered with HTTP 404: code `NOT_FOUND`, naming group/name and
  server), **without throwing** (other diagrams answered, node not stale); unreadable archive →
  `UNEXPECTED_RESPONSE` naming the part, never content. Contract test with WireMock serving
  `model_export_systemdiagram.{http,zip}`, a variant whose group differs and one whose owner
  differs; 401/403/404/503/timeout map to the existing error kinds; `PartnerNoWriteTest`: `src/main`
  never references `PartnerManagementRestlet` or `modifyPartnerManagement` — main
  `domain/port/PartnerManagementPort.java`, `adapter/rest/v81/V81PartnerManagementAdapter.java`,
  tests `adapter/rest/v81/V81PartnerManagementAdapterContractTest.java`,
  `architecture/PartnerNoWriteTest.java`
- [X] T013 (after T012) Port in the gateway (D-4): `GatewayFactory.partners(NodeId)` (default,
  delegating to `forServer(server).partners()`); `Gateway.partners()` as a `default` method that
  refuses with `UNSUPPORTED_VERSION` ("partner management is not available for this INUBIT
  version"), so test gateways stay unchanged; `PortSet` component (update
  `new PortSet(...)` in `CapabilityExtensionTest` and `AdapterGatewayFactoryTest`);
  `V81PortSet`/`V81Gateway` serve it for 8.1 and 9.0; `V61PortSet` gets a refusing stub from
  `RefusingPorts` (`UNSUPPORTED_VERSION`); `StaleReportingPorts` wraps it. Tests: 9.0 selects the
  8.1 port, 6.1 refuses, an `UNEXPECTED_RESPONSE` from the partner port marks the node stale — main
  `domain/port/GatewayFactory.java`, `domain/port/Gateway.java`, `adapter/PortSet.java`, `adapter/V81PortSet.java`,
  `adapter/V81Gateway.java`, `adapter/cli/v61/V61PortSet.java`, `adapter/RefusingPorts.java`,
  `adapter/StaleReportingPorts.java`, `adapter/AdapterGatewayFactory.java`, tests
  `adapter/PortSetRegistryTest.java`, `adapter/StaleReportingPortsTest.java`

### Capability and versions (D-7, FR-016)

- [X] T014 [P] `Capability.PARTNER_MANAGEMENT(Kind.READ, Set.of(Channel.REST))`, label "partner
  management"; `CapabilityMatrix`: 6.1 entry `PARTNER_MANAGEMENT → UNSUPPORTED`, 9.0 entry overrides
  it to `UNVERIFIED`, 8.1 `SUPPORTED`, others via `READS_UNVERIFIED`; `NodeOverview` name
  `partner_management` (shown by `list_nodes`); `ConfigSummary` lists it (`--check-config`);
  constant `COMPARABLE_SECRET_LINES = {8.1}` next to the matrix (D-5). Tests in
  `domain/model/CapabilityMatrixTest.java`, `application/NodeOverviewTest.java`,
  `config/ConfigSummaryTest.java`, `mcp/tools/ListNodesToolTest.java` — main
  `domain/model/Capability.java`, `domain/model/CapabilityMatrix.java`,
  `application/NodeOverview.java`, `config/ConfigSummary.java`

### Cache and audit (D-6, D-8)

- [X] T015 (after T004, T006) `InventoryCache.peek(Key)` returns a fresh snapshot without loading (empty if absent
  or expired); `PartnerSnapshot(String owner, List<SystemDiagramPartners> diagrams,
  Set<DiagramRef> covered, List<UnreadableDiagram> unreadableDiagrams, Map<DiagramRef,
  List<String>> warnings)` (owner, unreadable diagrams and warnings added in T018);
  scopes on `InventoryKind.DIAGRAM` (no new `InventoryKind`): `systemdiagrams:<owner>`,
  `partners:<owner>`, `partners:<owner>|<group>/<name>` as constants of `PartnerSnapshot` — main
  `application/InventoryCache.java`, `application/PartnerSnapshot.java`, test
  `application/InventoryCachePeekTest.java`
- [X] T016 [P] `AuditRecord` additive fields `Optional<UUID> callId` and `Map<String, Object> details`
  (empty by default, overloaded constructor keeps existing call sites); `AuditLog` writes them only
  when present (existing JSONL lines byte-identical — test with a recorded line) — main
  `domain/model/AuditRecord.java`, `infra/AuditLog.java`, test `infra/AuditLogTest.java`
- [X] T017 (after T016) `PartnerAudit` (one per call, like `ArchiveExportAudit`):
  `refused(ToolError)` one `REFUSED` record without node; `perServer(node, outcome, fromCache,
  systemDiagramsRead, partnersReturned, valuesMasked)`; capability `find_partners`, step `EXECUTE`,
  inputs target, compareWith, owner, search, filters each cut to 500 characters, `mcpClient` in
  the record's `mcpClient` field (tested); never results;
  any write failure → `INTERNAL` naming the `callId` (FR-020). Tests incl. leak check on every
  written line — main `application/PartnerAudit.java`, test `application/PartnerAuditTest.java`

**Checkpoint**: build green; code-review phase 2.

## Phase 3: User Story 1 – Read a partner's connection settings (P1) 🎯 MVP

**Goal**: search by Id or name part on one server (or a stage with one server) returns full details per partner, each
naming system diagram, diagram group and communication cloud; secrets only "set"/"not set".
**Independent test**: WireMock with one system diagram of 3 synthetic partners; search by Id and by
a lower-case name part; check every value and the leak check over result, log, audit and errors.

- [X] T018 [US1] `PartnerService` read path (after T012–T015): resolve target via
  `TargetResolver`; owner per server (`inventory.owner`, an explicit `owner` for all; none →
  `NOT_CONFIGURED` for that server only, FR-002a); gate `VersionGate.admit(…, PARTNER_MANAGEMENT,
  READ, {REST})` (6.1 → `UNSUPPORTED_VERSION`, 9.x → warning); the owner's system diagrams via the
  gateway's `InventoryPort.listDiagrams(owner)` filtered to `systemdiagram`, cached under
  `systemdiagrams:<owner>` (never through `InventoryService`); Partner Managements through
  `partners()` cached as `PartnerSnapshot` (`peek` the owner scope first, `refresh` bypasses,
  single-flight); the port through `GatewayFactory.partners(server)`; per-server `collectedAt`,
  `fromCache`, warnings, `unreadableDiagrams`. **Done when** tests show: an explicit owner gets its
  own diagrams; with `systemDiagram` only the matching diagrams are read (scope
  `partners:<owner>|<group>/<name>`, FR-003); an owner-scope entry answers a call for one diagram,
  an entry for one diagram does not answer a call for all (FR-018 coverage); two concurrent calls make one read; a second call within
  the TTL makes none; `refresh` reads again; a server without owner fails alone — main
  `application/PartnerService.java`, test `application/PartnerServiceReadTest.java` (Op-3, Op-5
  `NOT_CONFIGURED`)
- [X] T019 [US1] Search and details (after T018): case-insensitive substring over non-masked
  partner fields (FR-005, FR-011); full details per partner (FR-004: diagram, group, cloud, fields
  with masked ones as "set"/"not set", entries with protocol, workflow, `format`, settings,
  parameters, `maskedCount`); unresolved workflow as `{"name": null, "unresolved": true}`, reader
  warnings into `servers[].warnings`; every system diagram read named, those without a Partner
  Management marked, clouds without partners with count 0, `referencableWorkflows` per
  contributing cloud (FR-007). **Done when** US1-AS1…5 and Op-1 pass with the leak check — main
  `application/PartnerService.java`, test `application/PartnerServiceSearchTest.java`
- [X] T020 [US1] Similar values and `NOT_FOUND` on one server (after T019): no hit → up to five from
  non-masked `Id`/`Name` via `SimilarNames.of(term, candidates, 5)` (FR-005b); unknown
  `systemDiagram` filter → `NOT_FOUND` with up to five similar system diagram names (FR-019) —
  main `application/PartnerService.java`, test `application/PartnerServiceSearchTest.java`
  (US1-AS6, Op-5)
- [X] T021 [US1] Audit and log integration (after T017, T019): one `PartnerAudit` per call, one
  record per targeted server also for cache hits (`fromCache`), shared `callId`, counts; any
  audit write failure → `INTERNAL`, no partner data for any server; one structured INFO line per
  server (server, owner, outcome, counts, call id) without settings or secrets (FR-020, FR-021).
  **Done when** Op-4 passes and the captured log and audit lines pass the leak check — main
  `application/PartnerService.java`, test `application/PartnerServiceAuditTest.java`
- [X] T022 [US1] `FindPartnersTool` (name `find_partners`, hints `ToolHints.readOnly("Find partners
  in the INUBIT Partner Management", true, true)`), schemas
  `src/main/resources/schemas/find_partners.input.json` / `.output.json` exactly as
  `contracts/find_partners.md` (single-server shape; a target with several servers or a
  `compareWith` is handled from T026 on and covered by its tests, `owner` pattern, unresolved workflow shape);
  input errors → one audit record whose `node` is the target as requested; registration in `Wiring.toolHandlers()` when
  `anyPossiblyCapable(Capability.PARTNER_MANAGEMENT)`; description without customer knowledge
  (FR-025) — main `mcp/tools/FindPartnersTool.java`, `Wiring.java`, tests
  `mcp/tools/FindPartnersToolTest.java` (Op-2, Op-5, FR-002 overlap), `ToolAnnouncementTest.java`,
  `mcp/ToolHintsTest.java`, `WiringAuditTest.java`

**Checkpoint**: build green; US1 works end to end on one server; code-review phase 3.

## Phase 4: User Story 2 – List the partners of a stage (P2)

**Goal**: list without a search term, paginated, filtered by system diagram, protocol, workflow.
**Independent test**: 120 synthetic partners; default and custom page size, each filter; order,
`nextOffset`, totals, size bound.

- [ ] T023 [US2] List mode (after T019): summary per partner (system diagram name and group, cloud
  module name, partner fields, per entry protocol, workflow, `ServerName`, `Login` if present —
  FR-006, keys as constant `PartnerService.SUMMARY_KEYS`); order "system diagram group and name,
  communication cloud module name, then partner
  `Id`, then `Name`"; filters case-insensitive exact, AND-combined, `protocol` only the `protocol`
  attribute (`SFTP` matches nothing), `workflow` by resolved name (unresolved never matches),
  entry filters show only matching entries (FR-005a, single server) — main
  `application/PartnerService.java`, test `application/PartnerServiceListTest.java` (US2-AS1…4)
- [ ] T024 [US2] Paging and bounds (FR-017): `ResultLimiter`/`Page`, `limit` 1–100 default 20, texts
  cut at `ItemBounds.MAX_FIELD_CHARS` with `…[truncated]`, detail cut before entries (first
  additional parameters, then settings to the summary keys, then entries), `truncated` reported;
  SC-004 test with 1,000 partners (every page ≤ `resultLimits.maxChars`) — main
  `application/PartnerService.java`, test `application/PartnerServiceBoundsTest.java`

**Checkpoint**: build green; code-review phase 4.

## Phase 5: User Story 3 – Compare servers and stages (P2)

**Goal**: stage targets and `compareWith` group equal and differing settings, mark "only on", state
secret equality by server groups (8.1 only), report failed servers.
**Independent test**: two fake stages × two servers with known differences (T010).

- [ ] T025 [US3] `PartnerComparison` (pure, D-10; after T006, T009, T010, T014, T015): partner key
  (diagram, cloud, `Id`; without a non-masked `Id` the first configured non-masked field present on
  both sides); entry key (protocol, resolved workflow name) + position; settings and entry
  attributes such as `format` as `equal`/`differs`; masked: `set` per server and `sameOn` groups by
  fingerprint only when the value is set on at least two compared servers and every holder is on a
  line in `COMPARABLE_SECRET_LINES`, else `equality: "not verified on <version>"` (no statement
  with fewer than two holders); `maskedCount` grouped like a setting (FR-009); `onlyOn`; ambiguous keys and same-named clouds reported; unresolved
  entries never matched; failed servers in `notCompared`, left out of every statement
  (FR-012…FR-014) — main `application/PartnerComparison.java`, test
  `application/PartnerComparisonTest.java` (US3-AS1…6, AS8, AS9, SC-003, table-driven)
- [ ] T026 [US3] Comparison in `PartnerService` (after T021, T024, T025): fan-out over all targeted
  and compared servers; `total`/`nextOffset` count matched partner keys across servers; a search
  hit on one server includes the partner from all; without a search term summary fields grouped
  like settings plus only differences (FR-012c); FR-005a in a comparison: `NOT_FOUND` only if the
  system diagram exists on none of the answering servers, otherwise its partners are marked "only
  on"; entry filters applied per entry key on all compared servers, an entry matching on one server
  shown with its counterparts; one audit record per server with the shared `callId` — main
  `application/PartnerService.java`, test `application/PartnerServiceCompareTest.java` (US3-AS7,
  FR-005a comparison rules)
- [ ] T027 [US3] Comparison output and bounds (after T026): output shape `ComparedPartner` of the
  contract in the tool and schema (incl. `maskedCount`, separate `protocolSettings` and
  `additionalParameters`, `format` grouped like a setting); the comparison result within `resultLimits.maxChars` with the
  cut order of T024 (additional parameters, then settings to the summary keys, then entries),
  `truncated` reported. **Done when** a 1,000-partner comparison of two stages keeps every page
  within the bound (SC-004) and the tool test shows the shape — main
  `application/PartnerService.java`, `mcp/tools/FindPartnersTool.java`, schema
  `src/main/resources/schemas/find_partners.output.json`, tests
  `application/PartnerServiceCompareTest.java`, `mcp/tools/FindPartnersToolTest.java`

**Checkpoint**: build green; code-review phase 5.

## Phase 6: User Story 4 – From a workflow to its partner settings (P3)

**Goal**: `get_inventory_item` lists partner variables, the `PartnerManagement` node and the
system diagrams/clouds whose `ReferencableTWFs` contain the workflow.
**Independent test**: synthetic technical workflow with two `ISPartnerProtocol.*`, one
`ISPartnerParameter.*` and a `PartnerManagement` node; synthetic system diagram naming it.

- [ ] T028 [P] [US4] `DiagramExportParser`: distinct, sorted `ISPartner(Protocol|Parameter)\.
  [A-Za-z0-9_]+` from every attribute value and text of a technical workflow, and whether a
  `moduleType="PartnerManagement"` node exists; carried in `InventoryPort.DiagramMetadata` (new
  fields, `EMPTY` extended; adjust test fakes that build `DiagramMetadata`) — main
  `adapter/rest/v81/DiagramExportParser.java`, `domain/port/InventoryPort.java`, test
  `adapter/rest/v81/DiagramExportParserTest.java`
- [ ] T029 [US4] (after T018, T028) `PartnerUsage` (`domain/model/partner/PartnerUsage.java`) and
  `PartnerService.referencingClouds(NodeId server, String owner,
  String workflowName, boolean refresh)` → clouds whose `referencableWorkflows` contain the
  workflow; `InventoryDetail.partnerUsage` (`variables`, `partnerManagementNode`, `systemDiagrams`
  as `{diagram, cloud}`, `unavailable`); `InventoryService` calls `referencingClouds` only if
  variables or the node exist; gate refused → `unavailable` with error kind and next step; no
  audit; `Wiring` passes `PartnerService` into `InventoryService`; schema
  `src/main/resources/schemas/get_inventory_item.output.json` additive, shape exactly as
  `contracts/other-changes.md` (`systemDiagrams` with `cloud`); `InventoryBounds` keeps and bounds
  `partnerUsage`; the new constructor parameter is passed in `InventoryServiceTest`,
  `InventoryOwnerTest`, `CapabilityExtensionTest` and `mcp/tools/InventoryToolFixture` — main
  `domain/model/InventoryDetail.java`, `domain/model/partner/PartnerUsage.java`,
  `application/InventoryService.java`, `application/InventoryBounds.java`,
  `application/PartnerService.java`, `Wiring.java`, tests `application/InventoryServiceTest.java`
  (US4-AS1, AS2), `application/InventoryBoundsTest.java`

**Checkpoint**: build green; code-review phase 6.

## Phase 7: Polish & cross-cutting

- [ ] T030 [P] `PartnerSecretLeakTest`: the whole tool over all fixtures (single, list, compare,
  errors, cache hit, audit failure): no synthetic secret or derivative in results, errors, log
  output (captured appender) or audit lines; the cache holds only `Masked` values (SC-002) — test
  `security/PartnerSecretLeakTest.java`
- [ ] T031 [P] `PartnerServicePerformanceTest`: 1,000-partner fixture, parse + mask + fingerprint +
  page ≤ 5 s beyond the read; a repeated call within the TTL makes no request (SC-005) — test
  `application/PartnerServicePerformanceTest.java`
- [ ] T032 Docs (FR-022): `docs/tools.md` new `find_partners` section (inputs, output, clouds,
  summary keys, masking incl. `fieldType`, equality statement and its limits — different stored
  forms reported as differing, 8.1 only — workflows by name only, name collisions of the REST
  export), capability table row, "All eighteen tools" (and `DevelopmentToolsReferenceTest`),
  `get_inventory_item.partnerUsage`; `README.md` tool list; `SECURITY.md` (partner hosts and logins
  reach the conversation, secrets do not); `CHANGELOG.md`; `docs/setup.md` record cases
- [ ] T033 Full build and Python tests green; `python3 tools/check-identifiers.py --all`; test count
  vs T001 in `specs/018-partner-management-read/reviews.md`
- [ ] T034 Operator live check (quickstart.md §3) with the partner and stage the operator names;
  `docs/live-tests.md` entry in neutralized form only (FR-024)

## Dependencies

- T002 → T003 → T008, T010; T003 blocks every search task (T018 ff.).
- Phase 2: T004, T005, T007 parallel; T006 after T004, T005; T009 after T005; T010 after T003,
  T006; T011 after T008, T009, T010; T012 → T013; T014, T016 parallel to T011–T013; T015 after
  T004, T006; T017 after T016.
- US1: T018 → T019 → T020; T021 after T017, T019; T022 after T021.
- US2: T023 after T019; T024 after T023.
- US3: T025 after T006, T009, T010, T014, T015 (pure, can run next to US1); T026 after T021, T024, T025;
  T027 after T026.
- US4: T028 any time after Phase 1; T029 after T018, T028.
- Polish after the stories; T034 after the release candidate is built.

## Parallel opportunities

- T004, T005, T007 (different files, no dependency on each other).
- T014, T016 next to T011–T013; T015 once T006 is done.
- T025 (pure comparison) next to US1; T028 (parser) next to US1–US3.
- T030, T031 next to T032.

## Implementation strategy

1. Phase 1 incl. the re-recording (T003) — without it no partner field can be shown.
2. Phase 2, then US1 → MVP: the support question of SC-001 on one server; stages with several
   servers and `compareWith` follow with US3.
3. US2 (list), then US3 (compare), then US4; each checkpoint is a shippable increment.
4. Polish and the live check; release decision is the operator's.
