# Research: Read the Partner Management

**Feature**: 018-partner-management-read | **Date**: 2026-11-09 | **Spec**: [spec.md](spec.md)

Every decision names the requirement it serves. Facts about the code base were checked on branch
`018-partner-management-read` (base: release 0.8.0). No real customer value appears here; examples
use ACME, `*.example.test` and `Workflow-0001`.

## Findings from the code base

- **No recorded system diagram.** No fixture contains `PartnerManagementSettings`,
  `ISPartnerProtocol` or `ISPartnerParameter`. The REST fixture `fixtures/v8_1/rest/model_export_sample.zip`
  holds only `workflow/workflow.xml` and `archive.properties` of a technical workflow. The only
  partner-related fixture content is a technical workflow node
  `<WorkflowModule moduleType="PartnerManagement">` with a `Property name="PartnerManagementConfiguration"
  type="Map"` (`fixtures/v8_1/artifacts/grp-b.zip`, Workflow-0005).
- **REST diagram export.** `V81InventoryAdapter.diagramMetadata(name)` sends
  `GET /ibis/rest/model/export/<name>`, receives a ZIP (bounded by `InubitHttpClient.MAX_BODY_BYTES`,
  64 MB) and parses `workflow/workflow.xml` in memory (`DiagramExportParser`); nothing is written
  to disk.
- **StartCLI export.** `CliExportRunner.exportWorkflowGroup(owner, diagramGroup)` hard-codes
  `technical` (l.165, also `exportRelease` l.208 and `CliTagRunner` l.83); `exportHistory` already
  takes a type. The archive is read into memory from an owner-only private temporary directory that
  is deleted when the registration closes (`CliResources`).
- **Archive model.** `ArchiveReader.read(byte[])` → `ExportArchive` → `WorkflowXml(name,
  diagramGroup, XmlTree.Element, …)`; `XmlTree` is a StAX tree with `attribute`, `child`,
  `elements`, `text`.
- **Secrets.** `SecretRedactor` / `SecretPaths.kind(name, type, encrypted, value)` mask
  `type="Password"` (with or without `encrypted`), `encrypted="true"`, `KeyStore`, X509 with a
  private key, a fixed set of untyped secret names, and key material (`KeyMaterial`, package-private
  in `adapter/archive/v81`). The name pattern `SUSPICIOUS = (?i)password|secret|keystore|token`
  only counts, it does not mask. No keyed fingerprint exists; the code has only unkeyed SHA-256
  (audit confirmation codes, `ConflictDetector`, pinning).
- **Cache.** `InventoryCache` keys by `(NodeId, InventoryKind {DIAGRAM, MODULE}, scope)`, keeps
  `Snapshot(value, collectedAt, expiresAt)`, shares concurrent loads through a
  `CompletableFuture` per key and never caches failures.
- **Audit.** `AuditRecord` has no call id and no counts. `ArchiveExportAudit` is the per-call
  helper of `export_archives` (refused / pending / outcome records).
- **Capability matrix.** `CapabilityMatrix.PRODUCTION`: 6.1 (CLI-only) refuses `MONITORING` and
  `PROCESS_DIAGNOSIS` only; 8.1 and 9.0 are `ALL_SUPPORTED`; other versions `READS_UNVERIFIED`.
- **Tool count.** 17 tools; `docs/tools.md` says "All seventeen tools" and
  `DevelopmentToolsReferenceTest` asserts the phrase.

## Decisions

### D-1 Channel (FR-001, FR-001a, FR-016)

- **Decision**: REST (outcome of the recording, see "T-record results" below). How it was decided:
  the channel is decided by a recording made in the first implementation step
  (T-record, see [quickstart.md](quickstart.md) §1), not by assumption. Both responses are
  recorded from a non-production stage with `tools/record-fixtures.sh` and neutralized:
  - `model_export_systemdiagram`: `GET /ibis/rest/model/export/<system diagram>`;
  - `export_systemdiagram`: StartCLI `export --exportWorkflowType systemdiagram` of the
    diagram group of that system diagram.
  Rule (mechanical, recorded in this file when applied): if the REST ZIP's `workflow/workflow.xml`
  carries `PartnerManagementSettings` and the masked model parsed from it equals the masked model
  parsed from the StartCLI archive (same partners, entries, settings, masked flags), the channel is
  **REST**; otherwise **StartCLI**.
- **Rationale**: Principle V prefers REST, but FR-001a requires evidence. The existing REST call
  for diagram details already returns the diagram's `workflow.xml`, so REST is plausible; the
  operator's manual research proved StartCLI. The design (D-3) shares the parser, so the decision
  changes one adapter class and the tool's availability condition, nothing else.
- **Alternatives considered**: StartCLI now and REST later (rejected: breaks Principle V if REST
  works); both channels with fallback (rejected: two code paths to test and audit for one read;
  can be added later as a channel of the same port).
- **Consequences**: REST → the tool is offered wherever `INVENTORY` is possibly capable and no
  export trace is left in INUBIT (FR-003 exception not needed). StartCLI → the tool is offered
  only on nodes with a StartCLI client (like `export_archives`), and `CliExportRunner` gets a
  workflow type parameter (D-4).

### D-2 Workflow identity, summary keys and node form (Assumptions, FR-005a, FR-006, FR-013, FR-015)

- **Decision** (final, from the T-record results below): workflows in the Partner Management are
  identified by name only (`WorkflowRef(Optional<String> name)`, empty when `referencableTWFId`
  does not resolve); the summary keys are `ServerName` and `Login` for every protocol (FR-006);
  maps and lists are nested `<Property type="Map|List">` with leaf values as element text; a
  Partner Management belongs to an `sdCommunicationCloud` module; story 4's scan matches
  `ISPartner(Protocol|Parameter)\.[A-Za-z0-9_]+` in every attribute value and text of a technical
  workflow. `PLAIN_FIELD_TYPES` (FR-008) is fixed from the re-recording (tasks T003).
- **Rationale**: the spec deferred these facts to a recorded export; they are now recorded.

### D-3 Parse and mask in one adapter component (FR-008 … FR-011, FR-012b)

- **Decision**: A new `PartnerManagementReader` in `adapter/archive/v81` (next to
  `SecretRedactor`, so it can use the package-private `SecretPaths` and `KeyMaterial`) takes the
  `XmlTree.Element` of a system diagram's `workflow.xml` and returns `SystemDiagramPartners`
  (one `CommunicationCloud` per module with a Partner Management) in which every value is already a `SettingValue.Shown` or
  `SettingValue.Masked(set, Optional<SecretFingerprint>)`. Raw values never leave the method that
  reads the property. Masking rules (`PartnerSecretRules`):
  1. `SecretPaths.kind(name, type, encrypted, value)` is not empty (the workspace export's rules,
     FR-008 "at least as strict"), or
  2. `encrypted="true"` or `type="Password"` (redundant with 1, tested on its own), or
  3. the name contains, case-insensitively, one of `password`, `passwd`, `pwd`, `secret`,
     `token`, `keystore`, `privatekey`, `passphrase`, `credential`, `apikey`, `accesskey`, or
  4. the value is key material (`KeyMaterial`);
  5. a partner field whose `fieldType` in `PartnerFields` is not on `PLAIN_FIELD_TYPES`
     (allowlist; unknown types are masked, spec FR-008, review round 4).
  The rule kind is not exposed (the result says how many, not why).
- **Rationale**: FR-010 requires masking before anything leaves the parsing component; placing it
  in the archive package reuses the redaction rules instead of copying them, so "at least as
  strict as the workspace export" holds by construction, and a test proves it on the shared cases.
- **Alternatives considered**: running the existing `SecretRedactor` over the archive first
  (rejected: it rewrites XML for the workspace and keeps no "set"/"not set" or fingerprint);
  masking in the application layer (rejected: raw values would cross the port).

### D-4 Reading through one port (Principle V)

- **Decision**: New port `PartnerManagementPort` in `domain/port`:
  `List<SystemDiagramPartners> read(String owner, List<DiagramRef> systemDiagrams)`, returning only
  masked models, implemented by `V81PartnerManagementAdapter` (REST, D-1): one
  `GET /ibis/rest/model/export/<name>` per system diagram, parsed in memory.
  - **Name collisions.** The REST export selects by name only; whether INUBIT keeps diagram names
    unique per owner or system-wide is not documented. The adapter therefore checks the returned
    archive's `WorkflowGroupName` and `UserOrUserGroupName` against the requested `DiagramRef`
    group and the owner. On a mismatch it returns no data for that diagram and reports it per
    diagram (`unreadableDiagrams`, code `UNEXPECTED_RESPONSE`: "the export of this name returned a
    diagram of another group or owner; the name is not unique") without throwing, so the other
    diagrams are answered, the node is not marked stale, and another group's or owner's Partner
    Management is never attributed to the requested diagram. Several diagrams of the name in the
    requested group and owner are reported the same way (`UNEXPECTED_RESPONSE`: "the export of this
    name holds several diagrams of this name in this group; the name is not unique"); an export
    answered with HTTP 404 (deleted or renamed since it was listed) is a per-diagram entry with code
    `NOT_FOUND` naming group/name and server.
  - **Gateway.** Services get the port through `GatewayFactory.partners(NodeId)` (default,
    delegating to `forServer(server).partners()`). `Gateway.partners()` is a default method that
    refuses with `UNSUPPORTED_VERSION` ("partner management is not available for this INUBIT
    version"). The port is a new component of `PortSet`:
    `V81PortSet`/`V81Gateway` serve it for 8.1 and 9.0, `V61PortSet` gets a refusing stub from
    `RefusingPorts`, and `StaleReportingPorts` wraps it so that `UNEXPECTED_RESPONSE` marks the node
    stale like every other port.
  - **System diagram list (owner-specific).** `PartnerService` lists the owner's diagrams itself
    through the gateway's `InventoryPort.listDiagrams(owner)`, filtered to `systemdiagram`, cached
    under its own owner-scoped key (D-6). It never uses `InventoryService` (no cycle: story 4 makes
    `InventoryService` call `PartnerService.referencingClouds(server, owner, workflowName,
    refresh)`), and an explicit `owner` gets that owner's diagrams, not the configured owner's.
- **Rationale**: tool logic stays free of HTTP details; the collision check keeps FR-013's "same name
  in two diagram groups are different system diagrams" sound on a name-only endpoint.

### D-5 Fingerprint for secret equality (FR-012a, FR-012b, SC-002)

- **Decision**: `SecretFingerprinter` in `adapter/archive/v81` holds a 32-byte key from
  `SecureRandom` created once per process (on first use, in memory only) and computes
  `HMAC-SHA256(key, UTF-8 bytes of the stored value)` for a masked value that is set. The result is
  wrapped in `SecretFingerprint` (domain), a final class with `equals`/`hashCode` over the bytes,
  `toString()` returning `SecretFingerprint[redacted]`, and no accessor; it is not a record and is
  never given to Jackson (the comparison turns it into server groups before any result type is
  built). `PartnerManagement` objects are kept in the cache with these fingerprints.
  **Comparability** (FR-012a fallback): equality statements are made only when every compared
  server that holds the value runs a version whose stored forms are known to be comparable:
  INUBIT 8.1 (spike, 202 of 212 identical ciphertexts across stages). For any other version the
  comparison states "no equality statement: stored forms not verified as comparable on
  <version>". The set of verified lines is a constant next to the capability declaration.
- **Rationale**: an unkeyed hash of a low-entropy password could be brute-forced from a transcript;
  a per-process key makes the fingerprint useless outside the process and lets the cache serve
  comparisons.
- **Alternatives considered**: comparing raw values inside the parser per call (rejected by review
  round 1: needs raw values across servers in one place and defeats the cache); SHA-256 without key
  (rejected: SC-002).

### D-6 Cache with coverage (FR-018, SC-005)

- **Decision**: no new `InventoryKind` (that enum is part of the tool outputs). `InventoryCache`
  keys use `InventoryKind.DIAGRAM` with dedicated scopes: `systemdiagrams:<owner>` for the owner's
  system diagram list, `partners:<owner>` for all of the owner's Partner Managements and
  `partners:<owner>|<group>/<name>` for one system diagram. A call for one system diagram first asks
  `InventoryCache.peek(partners:<owner>)` (new: a fresh snapshot without loading) and uses it if
  present; otherwise it loads its own key. The value is `PartnerSnapshot(List<SystemDiagramPartners>
  diagrams, Set<DiagramRef> covered)`; diagrams without a cloud are those whose `clouds` is empty.
  Single-flight, TTL (`inventory.cacheTtl`) and "failures are not cached" come from
  `InventoryCache` unchanged; `refresh: true` bypasses.
- **Rationale**: reuses the tested cache and its TTL resolution; coverage is explicit in the key.
- **Alternatives considered**: a separate cache class (rejected: duplicates TTL and single-flight);
  `InventoryKind.PARTNERS` (rejected in plan review round 1: the enum is a domain value of the
  inventory tools' outputs).

### D-7 Capability and versions (FR-016)

- **Decision**: `Capability.PARTNER_MANAGEMENT(Kind.READ, Set.of(<channel of D-1>))`, label
  "partner management", `NodeOverview` name `partner_management`. Matrix: the 6.1 entry adds
  `PARTNER_MANAGEMENT → UNSUPPORTED`; the 9.0 entry overrides it to `UNVERIFIED` (the warning
  "9.0.x on <node> is not verified for partner management"); 8.1 stays `SUPPORTED`; other and
  unknown versions are `UNVERIFIED` through `READS_UNVERIFIED`. The tool and story 4 call
  `VersionGate.admit(..., PARTNER_MANAGEMENT, READ, channels)`.
- **Rationale**: FR-016 asks for 9.0 to warn although 9.0 is otherwise fully supported; the matrix
  already supports per-entry overrides.

### D-8 Audit per server with a call id (FR-020, Principle VII)

- **Decision**: `AuditRecord` gains two additive fields, `Optional<UUID> callId` and
  `Map<String, Object> details` (empty by default; `AuditLog` writes them only when present). A
  new `PartnerAudit` (application, one per call, modelled on `ArchiveExportAudit`) writes:
  - `REFUSED` for input errors before any server is contacted, with `node` = the target as requested (as `ArchiveExportAudit` does) and `group` only if the target parses;
  - after the fan-out, one record per targeted or compared server: outcome `EXECUTED` (data
    returned, also from the cache), `REFUSED` (version gate, `NOT_CONFIGURED`) or `FAILED`
    (error), `details`: `fromCache`, `systemDiagramsRead`, `partnersReturned`, `valuesMasked`.
  Inputs: target, compareWith, owner, search term, filters (each cut to 500 characters, scrubbed
  as today), never results. Capability `find_partners`, step `EXECUTE`. If any write throws, the
  service discards the result and returns `INTERNAL` naming the call id; already written records
  stay (append-only).
- **Rationale**: the spec asks for one record per server linked by a call id, which the current
  record cannot express without abusing `inputs`.
- **Alternatives considered**: putting call id and counts into `inputs` (rejected: inputs are what
  the caller sent; mixing results in breaks audit readers).

### D-9 Tool name, inputs, bounds (FR-001, FR-002, FR-017)

- **Decision**: name `find_partners`, hints `ToolHints.readOnly("Find partners in the INUBIT
  Partner Management", true, true)`. `limit` 1–100, default 20 (full details are large); text
  values cut at `ItemBounds.MAX_FIELD_CHARS` (200) with `…[truncated]`; the whole result through
  `ResultLimiter` (`resultLimits.maxChars`), cutting detail before entries: first additional
  parameters, then settings to the summary keys, then entries, reporting
  `truncated`. Search term: 1–200 code points, `Character.isISOControl` refused. Names validated
  with the existing diagram name rule.
- **Rationale**: matches the existing paging helpers and bounds; 20 detailed partners fit the
  default 50,000 characters on the fixture (checked in the quickstart).

### D-10 Comparison algorithm (FR-012 … FR-014)

- **Decision**: `PartnerComparison` (application, pure) takes per server a masked
  `PartnerSnapshot` or a `ToolError` and builds:
  - partner key: `(diagramGroup, diagramName, cloud module name, Id)` or, without `Id`, the first configured
    non-masked partner field present on both sides (decided here, not per partner); duplicates →
    `ambiguous` list, not matched;
  - entry key: `(protocol, workflow name)` (resolved through `referencableTWFId`) plus the
    position among equal keys;
  - per setting: equal on all answering servers → once; else value per server; masked → `set` per
    server and, only if the value is set on at least two compared servers and every holder is
    comparable (D-5), `sameOn` groups (FR-012a);
  - `onlyOn` for partners and entries missing on some answering servers; failed servers listed in
    `notCompared` and left out of every statement.
  Paging counts matched partner keys across servers (FR-012c); a partner matches if the search
  matches its non-masked fields on at least one server.
- **Rationale**: one pure function, tested with table-driven fixtures for SC-003.

### D-11 Story 4 in `get_inventory_item` (FR-015)

- **Decision**: in scope. `DiagramExportParser` collects, for a technical workflow, the distinct
  `ISPartnerProtocol.*` / `ISPartnerParameter.*` references and whether a `moduleType=
  "PartnerManagement"` node exists. `InventoryDetail` gains `Optional<PartnerUsage> partnerUsage`
  (`variables`, `partnerManagementNode`, `systemDiagrams` (`{systemDiagram {name, diagramGroup}, cloud}`), `unavailable`
  (error kind and next step)). The parser result travels in `InventoryPort.DiagramMetadata` (new
  fields). Only if variables or the node exist, `InventoryService` calls
  `PartnerService.referencingClouds(server, owner, workflowName, refresh)` (cache of D-6, gate of
  D-7), which returns the clouds whose `ReferencableTWFs` contain the workflow. No audit (names
  only). `Wiring` passes `PartnerService` into `InventoryService`.
- **Rationale**: the criterion of FR-015 ("only new fields") holds: one optional field and one
  lookup through the existing service.

### D-12 Similar values (FR-005b, FR-019)

- **Decision**: `SimilarNames.of(term, candidates, 5)` (package-private in `application`, used by
  the new service in the same package) over the non-masked `Id` and `Name` values for a search
  without hits, and over the system diagram names for an unknown `systemDiagram` filter.

## T-record results (2026-11-09)

Recorded by the operator on a non-production stage (INUBIT 8.1, one server) with
`model_export_systemdiagram` and `export_systemdiagram` (same anonymizer state), then neutralized:
partner subtree by `anonymize.py`, diagram, group, module and owner names by a local, deleted map
(`SystemDiagram-01`/`-02`, `GRP-PM`, `Module-0901`/`-0902`, `OWNERS`). `--verify`,
`check-identifiers.py --all` and the fixture catalogue tests pass. Fixtures:
`fixtures/v8_1/rest/model_export_systemdiagram.{http,zip}`,
`fixtures/v8_1/cli/export_systemdiagram.{exit,stdout,stderr,zip}`.

### D-1 outcome: REST

The REST ZIP's `workflow/workflow.xml` carries `PartnerManagementSettings`. Both Partner
Managements of the recorded system diagram are identical (canonical XML of the masked subtree) to
the corresponding ones in the StartCLI archive. By the D-1 rule the channel is **REST**: the
tool is offered where `INVENTORY` is possibly capable, no export trace is left in INUBIT, and
`CliExportRunner` stays unchanged. The StartCLI recording stays as evidence and as a contract
fixture for the parser.

### D-2 facts

- **Several Partner Managements per system diagram.** `PartnerManagementSettings` is a property of
  a `WorkflowModule moduleType="sdCommunicationCloud"` (with `ModuleId`, `ModuleName`), not of the
  system diagram itself. The recorded system diagram has two such modules, each with its own
  partners and referencable workflows; the StartCLI group export held two system diagrams with
  three in all. **This contradicts the spec** (Key Entities: "carries at most one Partner
  Management"; FR-004, FR-007, FR-013 identify partners by system diagram and `Id`): the module
  (communication cloud, by `ModuleName`) must become part of the result and of the match key.
  The spec needs an amendment before the tasks (see below).
- **Encoding.** Nested `<Property name="…" type="Map|List">`; leaf values are element text.
  `PartnerFields` lists the configured fields (`Id`, `Name`) with attributes `fieldType`,
  `showInTable`. The first recording's anonymizer replaced the `fieldType` values; after T002
  (`anonymize.py` keeps `fieldType`, `format` becomes a generic value) both fixtures were recorded
  again on 2026-11-09 (T003, same system diagram and group, names outside the subtree neutralized
  with the same mapping, D-1 equality per cloud unchanged). Every recorded `fieldType` is `String`
  (`Id` and `Name`, both fixtures), and every `format` is the same value (`format-1`):
  **`PLAIN_FIELD_TYPES = {String}`** (FR-008; any other or unknown `fieldType` is masked). A partner is `Property name="Partner" type="Map"` with `Fields` (Map) and
  `ReferencedTWFs` (List of `ReferencedTWF` Maps).
- **Workflow identity.** `ReferencableTWFs` entries are `<Property name="<workflow name>" id="<n>"
  moduleId="<n>">`; a `ReferencedTWF` points to one through the attribute `referencableTWFId`
  (local to its Partner Management), not by name. No diagram group is given: workflows are
  identified by name only (the documentation says so). The entry key of FR-013 becomes
  `(protocol, referenced workflow name)` resolved through that id.
- **Further `ReferencedTWF` attributes.** `protocol`, `referencableTWFId`, `type="Map"` and
  `format`. `format` has the same value on every recorded entry; since T002 the anonymizer treats it
  as a generic value (`format-1` in the fixtures). Its meaning is unknown; the tool shows it as an
  entry attribute, the parser does not interpret it.
- **Summary keys.** WebService entries also use `ServerName` (holding a URL) and `Login`;
  WS-Security credentials are additional parameters (`WSSecurityUsername`,
  `WSSecurityPassword`). `PartnerService.SUMMARY_KEYS` is therefore `ServerName`, `Login` for every
  protocol.
- **Secrets.** `Password` and `WSSecurityPassword` are `encrypted="true" type="Password"` with AES
  values; one recorded value is a non-AES form. `SSLKeyStore` has `type="HTTPConnectorTrustStore"`
  and no value (masked by name, shown as "not set"). `Auth` is `type="Boolean"`.
- **Story 4 variable form.** Not covered by these recordings (system diagrams only); the scan of
  D-2 stays independent of the exact place and is tested against a synthetic technical workflow.

## Open after research

- None. The spec was amended for the communication-cloud level and the REST channel (FR-004,
  FR-007, FR-013, Key Entities, Assumptions, Clarifications "Plan research decisions"); the
  data model and the contract follow it. Next: `/speckit-tasks`.
