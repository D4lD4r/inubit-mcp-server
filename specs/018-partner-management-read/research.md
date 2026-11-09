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

- **Decision**: The channel is decided by a recording made in the first implementation step
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

- **Decision**: Confirmed from the same recording (T-record), then fixed in the synthetic fixtures:
  - whether `ReferencedTWF` and `ReferencableTWFs` name a workflow by name only or by diagram group
    and name. The data model carries `WorkflowRef(name, Optional<diagramGroup>)`; with name only,
    the documentation says that equal names in different groups are not told apart;
  - the summary keys per protocol (FR-006): `FTP` → `ServerName`, `Login`; `WebService` → the
    endpoint and user keys found in the recording (expected: an URL key and `WSSecurityUsername`);
    stored as a constant table `PartnerSummaryKeys` and listed in `docs/tools.md`; an unknown
    protocol falls back to `ServerName`, `Login` if present;
  - the property encoding of maps and lists inside `workflow.xml` (expected: nested
    `<Property type="Map">` / `type="List"` as in the technical `PartnerManagementConfiguration`);
  - the form of `ISPartnerProtocol.<key>` and `ISPartnerParameter.<key>` references in a
    technical workflow (expected: inside property values, e.g. XPath or variable mappings) for
    story 4; the scan matches `ISPartner(Protocol|Parameter)\.[A-Za-z0-9_]+` in every attribute
    value and text of the workflow tree, which is independent of the exact place.
- **Rationale**: the spec defers these facts to research against a recorded export; inventing
  them would make the fixtures and the contract tests self-confirming.
- **Alternatives considered**: deriving the structure from documentation only (rejected: no public
  documentation of the XML; feature 017 showed that documentation-derived fixtures need a marked
  origin and a later live check).

### D-3 Parse and mask in one adapter component (FR-008 … FR-011, FR-012b)

- **Decision**: A new `PartnerManagementReader` in `adapter/archive/v81` (next to
  `SecretRedactor`, so it can use the package-private `SecretPaths` and `KeyMaterial`) takes the
  `XmlTree.Element` of a system diagram's `workflow.xml` and returns the domain model
  `PartnerManagement` in which every value is already a `SettingValue.Shown` or
  `SettingValue.Masked(set, Optional<SecretFingerprint>)`. Raw values never leave the method that
  reads the property. Masking rules (`PartnerSecretRules`):
  1. `SecretPaths.kind(name, type, encrypted, value)` is not empty (the workspace export's rules,
     FR-008 "at least as strict"), or
  2. `encrypted="true"` or `type="Password"` (redundant with 1, tested on its own), or
  3. the name contains, case-insensitively, one of `password`, `passwd`, `pwd`, `secret`,
     `token`, `keystore`, `privatekey`, `passphrase`, `credential`, `apikey`, `accesskey`, or
  4. the value is key material (`KeyMaterial`).
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
  masked models. Adapters:
  - REST (`V81PartnerManagementAdapter`, if D-1 picks REST): one `/model/export/<name>` per system
    diagram, parsed in memory;
  - StartCLI (`V81CliPartnerManagementAdapter`, if D-1 picks StartCLI): one export per diagram
    group with `--exportWorkflowType systemdiagram` via a new
    `CliExportRunner.exportWorkflowGroup(owner, diagramGroup, WorkflowExportType)` (the old
    signature delegates with `TECHNICAL`), archive read with `ArchiveReader`.
  The system diagrams of the owner come from the existing inventory listing (`InventoryPort`, type
  `systemdiagram`, cached as today), so the port reads only what it is told.
  The port is registered in `PortSet` for 8.1 and served for 9.0 like the other 8.1 ports; 6.1 has
  none (refused by the matrix, D-7).
- **Rationale**: tool logic stays free of HTTP and process details; the channel decision of D-1 is
  one adapter.

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

- **Decision**: `InventoryKind` gains `PARTNERS`. The scope is `<owner>` for "all system diagrams
  of the owner" and `<owner>|<diagramGroup>/<name>` for one system diagram. A call for one system
  diagram first asks `InventoryCache.peek(key-all)` (new: returns a fresh snapshot without loading)
  and uses it if present; otherwise it loads its own key. A call for all loads the owner key. The
  snapshot value is `PartnerSnapshot(List<SystemDiagramPartners>, List<DiagramRef> covered,
  List<DiagramRef> withoutPartnerManagement)`. Single-flight, TTL (`inventory.cacheTtl`) and
  "failures are not cached" come from `InventoryCache` unchanged; `refresh: true` bypasses.
- **Rationale**: reuses the tested cache and its TTL resolution; coverage is explicit in the key.
- **Alternatives considered**: a separate cache class (rejected: duplicates TTL and single-flight).

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
  - `REFUSED` with no node for input errors before any server is contacted;
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
  parameters of non-matching entries, then settings to the summary keys, then entries, reporting
  `truncated`. Search term: 1–200 code points, `Character.isISOControl` refused. Names validated
  with the existing diagram name rule.
- **Rationale**: matches the existing paging helpers and bounds; 20 detailed partners fit the
  default 50,000 characters on the fixture (checked in the quickstart).

### D-10 Comparison algorithm (FR-012 … FR-014)

- **Decision**: `PartnerComparison` (application, pure) takes per server a masked
  `PartnerSnapshot` or a `ToolError` and builds:
  - partner key: `(diagramGroup, diagramName, Id)` or, without `Id`, the first configured
    non-masked partner field present on both sides; duplicates → `ambiguous` list, not matched;
  - entry key: `(protocol, WorkflowRef)` plus the position among equal keys;
  - per setting: equal on all answering servers → once; else value per server; masked → `set` per
    server and, if comparable (D-5), `sameOn` groups;
  - `onlyOn` for partners and entries missing on some answering servers; failed servers listed in
    `notCompared` and left out of every statement.
  Paging counts matched partner keys across servers (FR-012c); a partner matches if the search
  matches its non-masked fields on at least one server.
- **Rationale**: one pure function, tested with table-driven fixtures for SC-003.

### D-11 Story 4 in `get_inventory_item` (FR-015)

- **Decision**: in scope. `DiagramExportParser` collects, for a technical workflow, the distinct
  `ISPartnerProtocol.*` / `ISPartnerParameter.*` references and whether a `moduleType=
  "PartnerManagement"` node exists. `InventoryDetail` gains `Optional<PartnerUsage> partnerUsage`
  (`variables`, `partnerManagementNode`, `systemDiagrams` (name, diagram group), `unavailable`
  (error kind and next step)). Only if variables or the node exist, the service asks the partner
  service for the owner's system diagrams (cache of D-6, gate of D-7) and keeps those whose
  `ReferencableTWFs` contain the workflow. No audit (names only).
- **Rationale**: the criterion of FR-015 ("only new fields") holds: one optional field and one
  lookup through the existing service.

### D-12 Similar values (FR-005b, FR-019)

- **Decision**: `SimilarNames.of(term, candidates, 5)` (package-private in `application`, used by
  the new service in the same package) over the non-masked `Id` and `Name` values for a search
  without hits, and over the system diagram names for an unknown `systemDiagram` filter.

## Open after research

- D-1 and D-2 are resolved by T-record before the adapter tasks; until then the parser is
  developed against a synthetic fixture whose structure follows the operator's description and is
  replaced (same content, recorded structure) once the recording exists. The recording is made
  by the operator on a non-production stage; real values never enter the repository (FR-023,
  `tools/check-identifiers.py`).
