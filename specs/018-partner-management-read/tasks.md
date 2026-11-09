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

- [ ] T001 Baseline: the build above is green on branch `018-partner-management-read`; note the test
  count in `specs/018-partner-management-read/reviews.md` ("Implementation — baseline")
- [ ] T002 `tools/anonymize.py`: inside a `PartnerManagementSettings` subtree keep the attribute
  `fieldType` (a type, not data) and classify the attribute `format` as a generic value
  (`format-<n>`), not as a workflow reference (research D-2); `--verify` accepts kept `fieldType`
  values only if they match `^[A-Za-z][A-Za-z0-9_.]{0,40}$`. Tests first in
  `tools/tests/test_anonymize_partner.py`; update the self-test
- [ ] T003 Operator (live, non-production, after T002): record `model_export_systemdiagram` again
  with the commands of quickstart.md §1 (stage `ent`, the same system diagram), run
  `export_systemdiagram` too if its `fieldType` values differ; neutralize names outside the subtree
  as on 2026-11-09 (local map, deleted afterwards); check with `anonymize.py --verify`,
  `check-identifiers.py --all`; record the plain `fieldType` values found as `PLAIN_FIELD_TYPES`
  in `specs/018-partner-management-read/research.md` (D-2 facts). **Blocks T009 and every search
  task** (without the allowlist FR-008 masks `Id` and `Name`)

## Phase 2: Foundational — model, masking, REST read, capability, cache, audit (blocks every story)

### Domain types (data-model.md)

- [ ] T004 [P] `DiagramRef(name, diagramGroup)` ("two refs are equal only if both fields are
  equal") and `WorkflowRef(Optional<String> name)` ("empty if the id does not resolve (shown as
  unresolved, with a warning)") with tests — main `domain/model/partner/DiagramRef.java`,
  `domain/model/partner/WorkflowRef.java`, test `domain/model/partner/DiagramRefTest.java`
- [ ] T005 [P] `SettingValue` sealed: `Shown(String value)` ("cut at 200 characters with
  `…[truncated]` when rendered (not when compared)") and `Masked(boolean set,
  Optional<SecretFingerprint> fingerprint)` ("the fingerprint is present only when `set`"); and
  `SecretFingerprint` (final class, not a record; bytes private; `equals`/`hashCode` over the bytes;
  `toString()` = `SecretFingerprint[redacted]`; no getter; Jackson refuses to serialize it — test
  serializes it with the server's `ObjectMapper` and expects an exception) — main
  `domain/model/partner/SettingValue.java`, `domain/model/partner/SecretFingerprint.java`, tests
  `domain/model/partner/SettingValueTest.java`, `domain/model/partner/SecretFingerprintTest.java`
- [ ] T006 [P] `Setting(name, SettingValue)`, `ProtocolEntry(protocol, WorkflowRef workflow,
  Optional<String> format, List<Setting> protocolSettings, List<Setting> additionalParameters, int
  position)` ("index among entries with the same `(protocol, workflow)`"), `Partner(List<Setting>
  fields, List<ProtocolEntry> entries, int maskedCount)` with `key()` → `PartnerKey(diagram, cloud,
  keyField, keyValue)` ("`Id` if present and not masked, else the first configured non-masked
  field present"), `CommunicationCloud(moduleName, moduleId, List<PartnerField> partnerFields,
  List<String> referencableWorkflows, List<Partner> partners)`, `SystemDiagramPartners(DiagramRef
  diagram, List<CommunicationCloud> clouds)` — main `domain/model/partner/*.java`, tests
  `domain/model/partner/PartnerTest.java` (key rules incl. masked `Id`, missing `Id`)
- [ ] T007 [P] `PartnerQuery` with validation (FR-002): `target` required; `compareWith` equal to
  or overlapping `target` (same id, or a stage and one of its servers) → `INVALID_INPUT`; `search`
  1–200 code points, no ISO control characters, letters with diacritics, `&`, `/` allowed;
  `systemDiagram`, `workflow` as the existing diagram names; `protocol` "1–64 characters
  `[A-Za-z0-9_-]`"; `offset` ≥ 0 default 0; `limit` 1–100 default 20; `refresh` default false —
  main `domain/model/partner/PartnerQuery.java`, test `domain/model/partner/PartnerQueryTest.java`

### Masking and parsing (D-3, D-5)

- [ ] T008 `PartnerSecretRules` in the archive package: masked when (1) `SecretPaths.kind(name,
  type, encrypted, value)` is not empty, (2) `type="Password"` or `encrypted="true"`, (3) the name
  contains case-insensitively `password`, `passwd`, `pwd`, `secret`, `token`, `keystore`,
  `privatekey`, `passphrase`, `credential`, `apikey`, `accesskey`, (4) the value is key material
  (`KeyMaterial`), (5) a partner field whose `fieldType` is not on `PLAIN_FIELD_TYPES` (from T003;
  unknown types masked). Tests: every `SecretPaths` case of the workspace export is also masked
  here ("at least as strict", FR-008); clear-text legacy `Password` without `encrypted`;
  `WSSecurityPassword` without type; `SSLKeyStore` of type `HTTPConnectorTrustStore` masked by
  name; key material in a plain field — main `adapter/archive/v81/PartnerSecretRules.java`, test
  `adapter/archive/v81/PartnerSecretRulesTest.java`
- [ ] T009 `SecretFingerprinter` (after T005): 32-byte key from `SecureRandom`, created once per process in
  memory; `HMAC-SHA256(key, UTF-8 bytes of the stored value)` → `SecretFingerprint`; tests: equal
  values → equal fingerprints, two instances (simulated processes) → different fingerprints, the key
  is never reachable (no getter, `toString` redacted) — main
  `adapter/archive/v81/SecretFingerprinter.java`, test
  `adapter/archive/v81/SecretFingerprinterTest.java`
- [ ] T010 `PartnerFixtures` (test support): synthetic `workflow.xml` builders in the recorded
  structure (research D-2: `WorkflowModule moduleType="sdCommunicationCloud"` with `ModuleId`,
  `ModuleName`, `Properties/Property name="PartnerManagementSettings" type="Map"` → `PartnerFields`,
  `ReferencableTWFs` (`name`, `id`, `moduleId`), `Partners/Partner/Fields`, `ReferencedTWFs/
  ReferencedTWF` with `protocol`, `referencableTWFId`, `format`); sets: 3 detail partners (FTP with
  AES password, SFTP-via-FTP with clear-text legacy password, WebService with WS-Security
  credentials), 120 and 1,000 list partners, two clouds in one diagram sharing `ACME-0001`, two
  stages × two servers with known differences (US3 independent test) — test
  `adapter/archive/v81/PartnerFixtures.java`
- [ ] T011 `PartnerManagementReader.read(DiagramRef, XmlTree.Element workflowRoot)` →
  `SystemDiagramPartners` (after T003, T005, T006, T008, T009, T010): one `CommunicationCloud` per
  `sdCommunicationCloud` module with `PartnerManagementSettings`; values masked inside the method
  that reads them (FR-010); entries resolve `referencableTWFId` (unresolved → empty name + warning);
  entries keep export order and are never merged; `maskedCount` per partner. Tests against
  `PartnerFixtures` and both recorded fixtures (`model_export_systemdiagram.zip` two clouds,
  `export_systemdiagram.zip` three clouds); a test that the REST and CLI fixtures give equal models
  per cloud (`PartnerChannelDecisionTest`, D-1 regression); leak check on `toString` of every
  produced object — main `adapter/archive/v81/PartnerManagementReader.java`, tests
  `adapter/archive/v81/PartnerManagementReaderTest.java`,
  `adapter/archive/v81/PartnerChannelDecisionTest.java`

### Port and REST adapter (D-1, D-4)

- [ ] T012 `PartnerManagementPort.read(String owner, List<DiagramRef> systemDiagrams)` →
  `List<SystemDiagramPartners>` (masked only) and `V81PartnerManagementAdapter`: one `GET
  /ibis/rest/model/export/<name>` per system diagram (as `V81InventoryAdapter.diagramMetadata`,
  in memory, bounded by `InubitHttpClient.MAX_BODY_BYTES`, never on disk), `ArchiveReader`/`XmlTree`
  → `PartnerManagementReader`; unreadable archive → `UNEXPECTED_RESPONSE` naming the part, never
  content. Contract test with WireMock serving `model_export_systemdiagram.{http,zip}`; error cases
  401/403/404/503/timeout map to the existing error kinds; a test that `src/main` never references
  `PartnerManagementRestlet` or `modifyPartnerManagement` (`PartnerNoWriteTest`) — main
  `domain/port/PartnerManagementPort.java`, `adapter/rest/v81/V81PartnerManagementAdapter.java`,
  tests `adapter/rest/v81/V81PartnerManagementAdapterContractTest.java`,
  `architecture/PartnerNoWriteTest.java`
- [ ] T013 Register the port in `PortSet` for 8.1 (served for 9.0 like the other 8.1 ports, none for
  6.1) — main `adapter/PortSet.java`, `adapter/AdapterGatewayFactory.java`, test
  `adapter/PortSetRegistryTest.java`

### Capability and versions (D-7, FR-016)

- [ ] T014 [P] `Capability.PARTNER_MANAGEMENT(Kind.READ, Set.of(Channel.REST))`, label "partner
  management"; `CapabilityMatrix`: 6.1 entry `PARTNER_MANAGEMENT → UNSUPPORTED`, 9.0 entry overrides
  it to `UNVERIFIED`, 8.1 `SUPPORTED`, others via `READS_UNVERIFIED`; `NodeOverview` name
  `partner_management`; `ConfigSummary` lists it; constant `COMPARABLE_SECRET_LINES = {8.1}` next to
  the matrix (D-5). Tests in `domain/model/CapabilityMatrixTest.java`,
  `application/NodeOverviewTest.java`, `config/ConfigSummaryTest.java` — main
  `domain/model/Capability.java`, `domain/model/CapabilityMatrix.java`,
  `application/NodeOverview.java`, `config/ConfigSummary.java`

### Cache and audit (D-6, D-8)

- [ ] T015 [P] `InventoryKind.PARTNERS`; `InventoryCache.peek(Key)` returns a fresh snapshot without
  loading (empty if absent or expired); `PartnerSnapshot(List<SystemDiagramPartners> diagrams,
  Set<DiagramRef> covered)`; scope `<owner>` or `<owner>|<group>/<name>` — main
  `domain/model/InventoryKind.java`, `application/InventoryCache.java`,
  `application/PartnerSnapshot.java`, test `application/InventoryCachePeekTest.java`
- [ ] T016 [P] `AuditRecord` additive fields `Optional<UUID> callId` and `Map<String, Object> details`
  (empty by default, overloaded constructor keeps existing call sites); `AuditLog` writes them only
  when present (existing JSONL lines byte-identical — test with a recorded line) — main
  `domain/model/AuditRecord.java`, `infra/AuditLog.java`, tests `infra/AuditLogTest.java`
- [ ] T017 `PartnerAudit` (one per call, like `ArchiveExportAudit`): `refused(ToolError)` one
  `REFUSED` record without node; `perServer(node, outcome, fromCache, systemDiagramsRead,
  partnersReturned, valuesMasked)`; capability `find_partners`, step `EXECUTE`, inputs target,
  compareWith, owner, search, filters each cut to 500 characters; never results; any write failure
  → the caller gets `INTERNAL` naming the `callId` (FR-020). Tests incl. leak check on every
  written line — main `application/PartnerAudit.java`, test `application/PartnerAuditTest.java`

**Checkpoint**: build green; code-review phase 2.

## Phase 3: User Story 1 – Read a partner's connection settings (P1) 🎯 MVP

**Goal**: search by Id or name part on one server or stage returns full details per partner, each
naming system diagram, diagram group and communication cloud; secrets only "set"/"not set".
**Independent test**: WireMock with one system diagram of 3 synthetic partners; search by Id and by
a lower-case name part; check every value and the leak check over result, log, audit and errors.

- [ ] T018 [US1] `PartnerService` (single server, search mode; after T011–T017): resolve target via
  `TargetResolver`, owner per server (`inventory.owner`, explicit `owner` for all; none →
  `NOT_CONFIGURED` for that server only, FR-002a); gate `VersionGate.admit(…,
  PARTNER_MANAGEMENT, READ, {REST})` (6.1 → `UNSUPPORTED_VERSION`, 9.x → warning); system diagrams
  of the owner from the inventory listing (type `systemdiagram`); cache via `PartnerSnapshot`
  (`peek` the owner scope first, `refresh` bypasses, single-flight); search = case-insensitive
  substring over non-masked partner fields (FR-005, FR-011); full details (FR-004) incl. `format`;
  `referencableWorkflows` per contributing cloud and every system diagram read, marked without
  Partner Management, clouds without partners with count 0 (FR-007); per-server `collectedAt`;
  one audit record per server, also from cache (`fromCache`), INFO log line per server (FR-020,
  FR-021) — main `application/PartnerService.java`, test `application/PartnerServiceTest.java`
  (US1-AS1…5, Op-1, Op-3, Op-4)
- [ ] T019 [US1] Similar values (FR-005b): no hit → up to five from non-masked `Id`/`Name` via
  `SimilarNames.of(term, candidates, 5)`; unknown `systemDiagram` filter → `NOT_FOUND` with up to
  five similar system diagram names (FR-019) — main `application/PartnerService.java`, test
  `application/PartnerServiceTest.java` (US1-AS6, Op-5)
- [ ] T020 [US1] `FindPartnersTool` (name `find_partners`, hints `ToolHints.readOnly("Find partners
  in the INUBIT Partner Management", true, true)`), schemas
  `src/main/resources/schemas/find_partners.input.json` / `.output.json` exactly as
  `contracts/find_partners.md` (single-server shape); input errors → one audit record without node;
  registration in `Wiring.toolHandlers()` when `anyPossiblyCapable(Capability.PARTNER_MANAGEMENT)`;
  description without customer knowledge (FR-025) — main `mcp/tools/FindPartnersTool.java`,
  `Wiring.java`, tests `mcp/tools/FindPartnersToolTest.java` (Op-2, Op-5, FR-002 overlap),
  `ToolAnnouncementTest.java`, `mcp/ToolHintsTest.java`, `WiringAuditTest.java`
- [ ] T021 [US1] `list_nodes` shows `partner_management` usable/notUsable; `--check-config` lists
  it — tests `mcp/tools/ListNodesToolTest.java`, `config/ConfigSummaryTest.java`

**Checkpoint**: build green; US1 works end to end on one server; code-review phase 3.

## Phase 4: User Story 2 – List the partners of a stage (P2)

**Goal**: list without a search term, paginated, filtered by system diagram, protocol, workflow.
**Independent test**: 120 synthetic partners; default and custom page size, each filter; order,
`nextOffset`, totals, size bound.

- [ ] T022 [US2] List mode: summary per partner (system diagram name and group, cloud module name,
  partner fields, per entry protocol, workflow, `ServerName`, `Login` if present — FR-006);
  order "system diagram group and name, communication cloud module name, then partner `Id`, then
  `Name`"; filters case-insensitive exact, AND-combined, `protocol` only the `protocol` attribute
  (`SFTP` matches nothing), `workflow` by resolved name (unresolved never matches), entry filters
  show only matching entries (FR-005a) — main `application/PartnerService.java`, test
  `application/PartnerServiceTest.java` (US2-AS1…4)
- [ ] T023 [US2] Paging and bounds (FR-017): `ResultLimiter`/`Page`, `limit` 1–100 default 20, texts
  cut at `ItemBounds.MAX_FIELD_CHARS` with `…[truncated]`, detail cut before entries (first
  additional parameters, then settings to the summary keys, then entries), `truncated` reported;
  SC-004 test with 1,000 partners (every page ≤ `resultLimits.maxChars`) — main
  `application/PartnerService.java`, test `application/PartnerServiceBoundsTest.java`

**Checkpoint**: build green; code-review phase 4.

## Phase 5: User Story 3 – Compare servers and stages (P2)

**Goal**: stage targets and `compareWith` group equal and differing settings, mark "only on", state
secret equality by server groups (8.1 only), report failed servers.
**Independent test**: two fake stages × two servers with known differences (T010).

- [ ] T024 [US3] `PartnerComparison` (pure, D-10): partner key (diagram, cloud, `Id` or first
  non-masked configured field); entry key (protocol, resolved workflow name) + position; settings
  and entry attributes such as `format` as `equal`/`differs`; masked: `set` per server and `sameOn`
  groups by fingerprint when every holder is on a line in `COMPARABLE_SECRET_LINES`, else
  `equality: "not verified on <version>"`; `onlyOn`; ambiguous keys and same-named clouds reported;
  unresolved entries never matched; failed servers in `notCompared`, left out of every statement
  (FR-012…FR-014) — main `application/PartnerComparison.java`, test
  `application/PartnerComparisonTest.java` (US3-AS1…6, AS8, AS9, SC-003, table-driven)
- [ ] T025 [US3] Comparison in `PartnerService` and the tool: fan-out over all targeted and
  compared servers; `total`/`nextOffset` count matched partner keys across servers; a search hit on
  one server includes the partner from all; without a search term summary fields grouped like
  settings plus only differences (FR-012c); output shape `ComparedPartner` of the contract; one
  audit record per server with the shared `callId` — main `application/PartnerService.java`,
  `mcp/tools/FindPartnersTool.java`, schema `find_partners.output.json`, tests
  `application/PartnerServiceTest.java` (US3-AS7), `mcp/tools/FindPartnersToolTest.java`

**Checkpoint**: build green; code-review phase 5.

## Phase 6: User Story 4 – From a workflow to its partner settings (P3)

**Goal**: `get_inventory_item` lists partner variables, the `PartnerManagement` node and the
system diagrams/clouds whose `ReferencableTWFs` contain the workflow.
**Independent test**: synthetic technical workflow with two `ISPartnerProtocol.*`, one
`ISPartnerParameter.*` and a `PartnerManagement` node; synthetic system diagram naming it.

- [ ] T026 [P] [US4] `DiagramExportParser`: distinct, sorted `ISPartner(Protocol|Parameter)\.
  [A-Za-z0-9_]+` from every attribute value and text of a technical workflow, and whether a
  `moduleType="PartnerManagement"` node exists — main `adapter/rest/v81/DiagramExportParser.java`,
  test `adapter/rest/v81/DiagramExportParserTest.java`
- [ ] T027 [US4] `InventoryDetail.partnerUsage` (`variables`, `partnerManagementNode`,
  `systemDiagrams` as `{diagram, cloud}`, `unavailable`); `InventoryService` reads system diagrams
  only if variables or the node exist, through `PartnerService` (cache, gate); gate refused →
  `unavailable` with error kind and next step; no audit; schema
  `get_inventory_item.output.json` additive — main `domain/model/InventoryDetail.java`,
  `application/InventoryService.java`, test `application/InventoryServiceTest.java` (US4-AS1, AS2)

**Checkpoint**: build green; code-review phase 6.

## Phase 7: Polish & cross-cutting

- [ ] T028 [P] `PartnerSecretLeakTest`: the whole tool over all fixtures (single, list, compare,
  errors, cache hit, audit failure): no synthetic secret or derivative in results, errors, log
  output (captured appender) or audit lines; the cache holds only `Masked` values (SC-002) — test
  `security/PartnerSecretLeakTest.java`
- [ ] T029 [P] `PartnerServicePerformanceTest`: 1,000-partner fixture, parse + mask + fingerprint +
  page ≤ 5 s beyond the read; a repeated call within the TTL makes no request (SC-005) — test
  `application/PartnerServicePerformanceTest.java`
- [ ] T030 Docs (FR-022): `docs/tools.md` new `find_partners` section (inputs, output, clouds,
  summary keys, masking incl. `fieldType`, equality statement and its limits — different stored
  forms reported as differing, 8.1 only — workflows by name only), capability table row,
  "All eighteen tools" (and `DevelopmentToolsReferenceTest`), `get_inventory_item.partnerUsage`;
  `README.md` tool list; `SECURITY.md` (partner hosts and logins reach the conversation, secrets
  do not); `CHANGELOG.md`; `docs/setup.md` record cases
- [ ] T031 Full build and Python tests green; `python3 tools/check-identifiers.py --all`; test count
  vs T001 in `reviews.md`
- [ ] T032 Operator live check (quickstart.md §3) with the partner and stage the operator names;
  `docs/live-tests.md` entry in neutralized form only (FR-024)

## Dependencies

- T002 → T003 → T008 (allowlist) → T011; T003 blocks every search task (T018 ff.).
- Phase 2 (T004–T017) blocks all stories. Inside: T004–T007 parallel; T010 before T011; T011 →
  T012 → T013; T014–T016 parallel to T011–T013; T016 → T017.
- US1 (T018–T021) before US2 (T022–T023) and US3 (T024–T025): both extend `PartnerService`.
  T024 can start after T006/T009 (pure) in parallel with US1.
- US4 (T026–T027) needs T018 (service); T026 can start any time after Phase 1.
- Polish after the stories; T032 after the release candidate is built.

## Parallel opportunities

- T004, T005, T006, T007 (different files, no dependency on each other); T009 after T005.
- T014, T015, T016 next to T011–T013.
- T024 (pure comparison) next to US1; T026 (parser) next to US1–US3.
- T028, T029 next to T030.

## Implementation strategy

1. Phase 1 incl. the re-recording (T003) — without it no partner field can be shown.
2. Phase 2, then US1 → MVP: the support question of SC-001 on one server or stage.
3. US2 (list), then US3 (compare), then US4; each checkpoint is a shippable increment.
4. Polish and the live check; release decision is the operator's.
