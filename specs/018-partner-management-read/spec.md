# Feature Specification: Read the Partner Management

**Feature Branch**: `018-partner-management-read`

**Created**: 2026-11-09

**Status**: Draft

**Input**: the operator's request (German, summarized; the full text is in the session that created
this spec): make the Partner Management of an INUBIT server readable through the MCP server, so
that questions like "Which connection settings does partner X have on stage Y?" are answered
without manual research. Background: in a support case the operator had to find out with which
server, protocol, login and authentication a fallback workflow transfers files to a partner. The
values are not in the technical workflow but in the Partner Management; the MCP server could not
answer, and the operator exported them by hand with StartCLI.

## Context

What the operator's research found (checked on INUBIT 8.1.17, on a test and a production stage):

- **The Partner Management belongs to communication clouds in system diagrams.** It is no separate
  store: a system diagram (diagram type `systemdiagram`) can contain several communication clouds
  (modules of type `sdCommunicationCloud`, identified by their module name), and each can carry
  one Partner Management (confirmed by the plan's recording, research D-2). An owner can have
  several system diagrams; `list_inventory` with `type: systemdiagram` already lists them.
- **Structure in the exported diagram.** The system diagram's `workflow.xml` holds, per
  communication cloud module, a property map `PartnerManagementSettings` with
  - `PartnerFields`: the partner fields configured for this diagram (e.g. `Id`, `Name`);
  - `ReferencableTWFs`: the technical workflows allowed to use the partner data;
  - `Partners`: a list of `Partner` maps. A partner has `Fields` (e.g. `Id`, often an EIC or GLN
    code, and `Name`, which can be outdated) and `ReferencedTWFs`, a list of `ReferencedTWF` maps
    with an attribute `protocol` (e.g. `FTP`, `WebService`). Each holds `ProtocolSettings`
    (e.g. `ServerName`, `Login`, `Password`, `FileName`, `Auth`, `SSLKeyStore`) and
    `AdditionalParameterSettings` (e.g. `FTPAuth`, `FTPProtocol` with values like `SFTP`/`FTPS`,
    `WSSecurityUsername`, `WSSecurityPassword`). A partner can have several entries of the same
    protocol, one per referenced workflow.
- **How workflows use it.** Technical workflows read these values through the variables
  `ISPartnerProtocol.<protocol setting>` and `ISPartnerParameter.<additional parameter>`; a
  technical workflow can also contain a `PartnerManagement` node (feature 015 already walks
  through its children). Whoever sees such a variable in a workflow has to look into the Partner
  Management.
- **No dedicated read interface over REST.** INUBIT has a `PartnerManagementRestlet` (operation
  `modifyPartnerManagement`: create, update, delete one partner, delete all partners). It only
  writes; it cannot read. `/ibis/rest/application.wadl` answers 404. Whether the generic REST
  diagram export (`/model/export`, used by `get_inventory_item` for diagram details) also carries
  `PartnerManagementSettings` of a system diagram was not checked by the operator; the plan's
  recording shows that it does (research D-1).
- **StartCLI can export system diagrams.** `export` with `--exportWorkflowType systemdiagram`
  (owner, diagram group, export file) works and reports `1-OK: Workflow group exported
  successfully.`; the archive has the layout of the technical export (`archive.properties`,
  `workflow/workflow.xml`, `module/module.xml`, `Repository.zip`). Today every StartCLI export of
  this server uses `technical`.
- **Secrets come in several forms.** Most passwords are `encrypted="true" type="Password"` (AES
  values), but the development spike (docs/research/spike-development-deployment.md, section 4)
  found older non-AES encodings and `type="Password"` properties without `encrypted="true"`. The
  workspace export's redaction (feature 003) already treats every `type="Password"` as secret,
  with or without `encrypted`.
- **Stored secrets are comparable across servers.** The same spike compared 212 password
  properties present on two stages by hash only: 202 had identical ciphertext, the 10 others were
  stage-specific passwords; "the encryption does not depend on the server".
- **Values differ between servers and stages** (e.g. another host and login on the test stage than
  on the production stage), so a comparison across servers and stages is part of the use case.
- **Audit today.** Of the read-only exports, `export_archives` (feature 015) writes audit records;
  `export_artifacts` records its exports in the workspace history instead.

**Tool surface (decision of this spec, Principle IV).** Considered:

- (a) Extend `export_artifacts` to export system diagrams into the workspace and add a tool that
  reads the Partner Management from the workspace. Two calls per question, the workspace lock and
  its history are involved, and a stage comparison needs one export per server first.
- (b) **One new read tool** that reads the system diagrams of the owner in memory, masks the
  secrets, and answers the search, list and comparison questions directly (like
  `get_inventory_item`, which also reads exports in memory); plus an extension of
  `get_inventory_item` for the workflow-to-partner question (user story 4), so no second new tool.

This spec follows (b) (operator's decision, see Clarifications). (a), a masked export of system
diagrams into the workspace for a versioned history of partner changes, is a later feature
(docs/backlog.md). The tool's working name is `find_partners`; the plan fixes the name.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Read a partner's connection settings (Priority: P1)

An operator in a support case asks "Which server, login and authentication does partner ACME use
on the test stage?". They give a partner Id or part of a name and a stage or one server. They get,
per matching partner and per protocol entry, the server, the login, the protocol settings and
additional parameters, and the authentication-related settings as configured (e.g. `Auth`,
`FTPAuth`, `FTPProtocol`, shown as values without interpretation), together with the system
diagram the partner belongs to and the technical workflow the entry is for. Passwords and keys
appear only as "set" or "not set".

**Why this priority**: this is the question from the support case and the whole reason for the
feature; without it nothing else is useful.

**Independent Test**: against a fake INUBIT server whose export returns a system diagram with
three synthetic partners (one FTP entry with an encrypted password, one SFTP entry with an
unencrypted legacy password, one WebService entry with WS-Security credentials), search by Id and
by a lower-case part of the name on one server; check every shown value and that no secret value,
encrypted or not, appears in the result, the log, the audit record or an error.

**Acceptance Scenarios**:

1. **Given** a server whose owner has one system diagram with partner `Id=ACME-0001`,
   `Name=ACME Corp`, **When** the operator searches for `acme-0001`, **Then** the result names the
   server, the system diagram and its diagram group, the communication cloud, the partner's fields and, per
   `ReferencedTWF`, the protocol, the referenced workflow, every protocol setting and additional
   parameter that is not a secret, and each secret as masked with "set"/"not set".
2. **Given** the same server, **When** the operator searches for `corp`, **Then** the same partner
   is found (case-insensitive substring of any non-secret partner field).
3. **Given** a partner with two FTP entries for two different workflows, **When** it is shown,
   **Then** both entries appear, each with its workflow, and none is merged into the other.
4. **Given** a `Password` property without `encrypted="true"` holding a clear-text value, and
   a property named like `WSSecurityPassword` without a password type, **When** the partner is
   shown, **Then** neither value nor anything derived from it (length, hash, prefix) appears; both
   are reported as masked.
5. **Given** an owner with two system diagrams that both contain a partner matching the search,
   **When** the operator searches, **Then** both are returned, each naming its system diagram.
6. **Given** a search that matches nothing, **When** the call ends, **Then** the result is empty,
   states which system diagrams were read, and lists up to five similar partner Ids or names
   (FR-005b), never values of masked fields.

---

### User Story 2 - List the partners of a stage (Priority: P2)

An operator wants an overview: which partners exist in the Partner Management of a server or
stage, in which system diagram, with which protocols. The list is paginated and can be narrowed by
system diagram, protocol or referenced workflow.

**Why this priority**: needed when the operator does not know the partner's Id or name, and to see
which partners a workflow serves; it builds on the same data as story 1.

**Independent Test**: with a fake export of 120 synthetic partners, page through the list with
the default and a custom page size and with each filter; check order, `nextOffset`, totals and
the size bound.

**Acceptance Scenarios**:

1. **Given** a server with 120 partners, **When** the operator lists without a search term,
   **Then** the first page holds the default number of partners in a stable order (system diagram
   group and name, communication cloud, then partner `Id`, then `Name`), with the total and the offset of the next
   page.
2. **Given** a filter `protocol: ftp`, **When** listing, **Then** only partners with at least one
   entry whose `protocol` attribute is `FTP` appear, and each shows only those entries; a filter
   `protocol: SFTP` matches nothing, because `SFTP` is the value of the additional parameter
   `FTPProtocol`, not a protocol (FR-005a).
3. **Given** a filter by a technical workflow, **When** listing, **Then** only partners with an
   entry for exactly that workflow appear, and only those entries are shown.
4. **Given** list entries, **When** shown, **Then** each is a summary (FR-006), and the full
   details are available through the search of story 1.

---

### User Story 3 - Compare servers and stages (Priority: P2)

An operator suspects that a partner is configured differently on the servers of a stage, or wants
to know how the test stage differs from the production stage. They target a stage (all its
servers), or a stage or server together with a second one to compare with. Values that are equal
everywhere are shown once; differing values are shown per server.

**Why this priority**: the operator's support case had different hosts and logins on test and
production; comparing by hand is error-prone. It reuses stories 1 and 2.

**Independent Test**: two fake stages with two servers each, where one partner differs in
`ServerName` between the stages and in `Login` between the two servers of one stage, one partner
exists on one stage only, and one password differs on one server; compare and check grouping, the
"only on" markers, paging, and that secrets appear only as "set"/"not set" and "same"/"differs" by
server groups.

**Acceptance Scenarios**:

1. **Given** a stage target with two servers that have identical Partner Management, **When** a
   partner is searched, **Then** each setting appears once, marked as equal on all servers.
2. **Given** `Login` differs between the two servers, **When** the partner is searched, **Then**
   `Login` is listed with its value per server, and the other settings once.
3. **Given** a comparison of two stages, **When** a partner exists only on one stage, or an entry
   (protocol and workflow) exists only on some servers, **Then** this is marked as "only on
   <servers>".
4. **Given** a password that is set on all servers, with the same stored value on two servers and
   another one on the third, **When** compared, **Then** the result says it is set on every server
   and that it differs, naming which servers share a value (e.g. "same on node1, node2; differs on
   node3"), without any value, length, hash or encoding of it.
5. **Given** a password that is set on all compared servers with the same stored value, **When**
   compared, **Then** it is shown once as "set, same on all servers".
6. **Given** one server of the stage is unreachable or refuses the call, **When** compared,
   **Then** the other servers are compared and the failed server is reported with its error;
   the comparison states that it is incomplete.
7. **Given** a comparison without a search term over 120 partners, **When** listed, **Then** each
   page holds the summary of each partner plus only the settings that differ (or are only on some
   servers); `total` counts matched partners across all compared servers (FR-012c).
8. **Given** `Name` of a partner matches the search on one stage but not on the other (the name
   was changed there), **When** searched, **Then** the partner is included with its entries from
   both stages, matched by `Id`.

---

### User Story 4 - From a workflow to its partner settings (Priority: P3)

An operator looks at a technical workflow and sees `ISPartnerProtocol.ServerName` or a
`PartnerManagement` node. They want to know which partner variables the workflow uses and in which
system diagram these values are maintained, to continue with story 1 or 2.

**Why this priority**: it shortens the path from the workflow to the right system diagram, but the
operator can reach the same data with story 2's workflow filter.

**Independent Test**: a fake technical workflow using two `ISPartnerProtocol.*` variables, one
`ISPartnerParameter.*` variable and a `PartnerManagement` node, and a fake system diagram whose
`ReferencableTWFs` names that workflow; ask for the workflow's details.

**Acceptance Scenarios**:

1. **Given** that workflow, **When** the operator asks for its details, **Then** the result lists
   the distinct partner variables used (`ISPartnerProtocol.ServerName`, …), and the system
   diagrams (with their communication clouds) of the owner whose `ReferencableTWFs` contain the
   workflow.
2. **Given** a workflow without partner variables and without a `PartnerManagement` node, **When**
   asked, **Then** the result says so, and no system diagram is read for it.

---

### Operational Scenarios (all stories)

1. **Given** a system diagram without `PartnerManagementSettings` next to one with partners,
   **When** searched, **Then** the result lists the first under "read without a Partner
   Management" and shows the `ReferencableTWFs` of the second (FR-007).
2. **Given** a node pinned to INUBIT 6.1, **When** the tool is called, **Then** it is refused with
   `UNSUPPORTED_VERSION` for that node; **given** a node on INUBIT 9.0 or `V9_X`, **Then** the
   result carries the "not verified for partner management" warning (FR-016).
3. **Given** a result collected two minutes ago, **When** the same question is asked again,
   **Then** it is answered from the cache with the old `collectedAt` and its audit record carries
   `fromCache: true` (FR-020); **with** `refresh: true` a
   new read is made; **given** two concurrent calls for the same server and owner, **Then** one
   read serves both (FR-018).
4. **Given** the audit record cannot be written, **When** the tool is called, **Then** the call
   ends with `INTERNAL` and returns no partner data (FR-020).
5. **Given** an unknown system diagram name as filter, **When** called, **Then** the server answers
   `NOT_FOUND` with up to five similar system diagram names; **given** an owner that is not
   configured for a node, **Then** that node gets `NOT_CONFIGURED` and the other nodes are answered
   (FR-019).

---

### Edge Cases

- **No system diagram, or no Partner Management in it**: the owner has no system diagram, or a
  system diagram without `PartnerManagementSettings` or with an empty `Partners` list. Result:
  empty, stating which system diagrams were read; not an error.
- **Partner without `Id`**: the configured `PartnerFields` may not include `Id`. Partners are then
  identified by the configured fields in their order; a comparison matches partners by the first
  configured field that is present on both sides, and reports unmatched ones per server.
- **Duplicate partner Ids** in one communication cloud: both are shown; a comparison reports the
  duplicate instead of matching arbitrarily.
- **Same system diagram name in two diagram groups**: they are different system diagrams and are
  never matched with each other.
- **Several communication clouds in one system diagram**: partners are reported per cloud; the
  same partner `Id` in two clouds is two partners, never merged or reported as a duplicate.
- **A `ReferencedTWF` whose workflow reference does not resolve** (its `referencableTWFId` names no
  `ReferencableTWFs` entry): the entry is shown with the workflow as unresolved and a warning.
- **Unknown or new protocols and parameters** (a protocol other than FTP or WebService, a setting
  name not seen before): shown as found, with the same masking rules; nothing is dropped silently.
- **Secret-like names with harmless values** (e.g. an `SSLKeyStore` that names a keystore of the
  Keystore Manager): masked anyway when the name matches a secret pattern; "set" still tells the
  operator that a keystore is configured. The result says how many values were masked, not why.
- **Key material in a non-secret field** (a long base64 value that decodes to a keystore, a PEM
  private key): masked, as in the workspace export.
- **A partner field holding a secret** (a custom partner field named like a password): listed as
  masked, and excluded from the search and from the similar-name suggestions, so that a search
  term cannot probe secret values.
- **Same secret, different stored form** (an older encoding on one server, AES on another):
  reported as differing; the documentation explains why (FR-012a).
- **Compare target equal to or overlapping `target`** (the same stage twice, or a stage and one of
  its servers): refused with `INVALID_INPUT` before INUBIT is contacted.
- **Huge Partner Management** (thousands of partners, very long values): results stay within the
  configured size bounds; long texts are cut with an explicit marker; the page reports truncation.
- **Workflow in edit mode or diagram checked out**: no effect on reading; the export reads the
  stored version.
- **Export trace in INUBIT**: a StartCLI export changes the export suffix of the exported
  diagram's check-in comment (spike, section 3). The channel is REST (research D-1), which leaves no
  such trace; this applies only if a later change adds the StartCLI channel.
- **Slow or failing read**: a timeout names the timeout setting; an authentication failure, a
  missing StartCLI installation or an unsupported client version give the existing error kinds
  per server; other servers of a stage are still answered.
- **Archive that cannot be read** (unexpected structure, unknown version format): an error result
  for that server that names the part that could not be read, never raw XML or values.

## Requirements *(mandatory)*

### Functional Requirements

**Tool surface**

- **FR-001**: The server MUST offer one new read tool for stories 1–3 (working name
  `find_partners`) and MUST NOT add a second new tool for this feature; story 4 extends
  `get_inventory_item`. The tool MUST carry the annotations of a read tool (read-only,
  non-destructive, idempotent) and be offered under the condition of its channel (FR-001a): with
  StartCLI, on a node with a StartCLI installation, like the other StartCLI-backed reads; with
  REST, on every node.
- **FR-001a**: The channel MUST be REST if the plan's research shows, against a recorded response,
  that the REST diagram export of a system diagram carries the complete `PartnerManagementSettings`
  (Principle V: REST is preferred); otherwise StartCLI's export with diagram type `systemdiagram`.
  The tool's availability (FR-001) and the capability's channel (FR-016) follow that decision.
- **FR-002**: The tool MUST accept the id of one stage or one server as `target`, an optional
  second stage or server to compare with (`compareWith`), an optional `owner`, an optional search
  term, optional filters (system diagram name, protocol, referenced technical workflow), paging
  (`offset`, `limit`) and a `refresh` flag. Every input MUST be validated before INUBIT is
  contacted: names as for the existing diagram names; the search term 1–200 characters, any
  printable character (letters with diacritics, `&`, `/` included), no control characters.
  `compareWith` equal to or overlapping `target` MUST be refused with `INVALID_INPUT`.
- **FR-002a**: Without `owner`, the owner MUST be resolved per server from that server's
  configured inventory owner; a server without one fails alone with `NOT_CONFIGURED`. An explicit
  `owner` applies to every targeted and compared server.

**Reading**

- **FR-003**: The tool MUST read the Partner Management of every system diagram of the owner on
  each targeted server, or only of the named system diagram, and MUST NOT use any interface that
  can change the Partner Management (in particular never the `PartnerManagementRestlet`) or change
  any other state on INUBIT, except the export trace in the check-in comment accepted in the
  Assumptions (StartCLI channel only).
- **FR-004**: For each partner the result MUST show the system diagram (name and diagram group),
  the communication cloud (module name), every partner field (a masked field as masked with "set"/"not set"), and for each
  `ReferencedTWF` entry its protocol, its referenced technical workflow, every protocol setting and
  every additional parameter, secrets masked (FR-008). Entries MUST NOT be merged; their order MUST
  be the order in the export.
- **FR-005**: The search MUST match a case-insensitive substring against the non-masked partner
  fields (at least `Id` and `Name`). Without a search term the tool lists (story 2).
- **FR-005a**: Filters MUST match case-insensitively and exactly, and combine with each other and
  with the search by AND: `protocol` against the `ReferencedTWF` attribute `protocol` only (not
  against parameters such as `FTPProtocol`), `systemDiagram` against the system diagram name (in
  any diagram group), `workflow` against the referenced workflow name. With `protocol` or
  `workflow`, a partner shows only its matching entries. An unknown system diagram gives
  `NOT_FOUND` (FR-019); a protocol or workflow without matches gives an empty result. In a
  comparison, `NOT_FOUND` applies only if the system diagram exists on none of the servers that
  answered; otherwise its partners are marked "only on <servers>". Entry filters are applied per
  entry key (protocol and workflow) on all compared servers alike; an entry matching on one server
  is shown with its counterparts and "only on" markers.
- **FR-005b**: If a search matches no partner, the result MUST list up to five similar values taken
  only from non-masked `Id` and `Name` values of the system diagrams read, with the rule of
  `get_inventory_item`'s `NOT_FOUND` suggestions (names containing the term, names of at least 3
  characters contained in it, or within an edit distance of `max(2, length / 3)`; closest first).
- **FR-006**: The result of a search MUST show full details per matching partner. A list without a
  search term MUST show a summary per partner: system diagram, partner fields, and per entry the
  protocol, the workflow and, if present, the protocol settings `ServerName` and `Login` (absent
  ones are omitted; for protocols that name their endpoint or user differently, the plan's research
  names those settings from a recorded export and the documentation lists them).
- **FR-007**: The result MUST also report the `ReferencableTWFs` of every communication cloud that
  contributed a partner, and which system diagrams were read without any Partner Management.

**Secrets (Principle II)**

- **FR-008**: No password, key, keystore, certificate with possible private key or other secret
  value, in clear or encrypted form, and nothing derived from it (length, hash, prefix, encoding
  kind) may appear in a tool result, error message, log line or audit record. The only exception
  is the equality statement of a comparison (FR-012a). A value MUST be masked when any of these
  holds:
  - its property is `type="Password"`, with or without `encrypted="true"`;
  - its property has `encrypted="true"`, whatever its type;
  - its property type is a keystore, key or certificate type (as in the workspace export's
    redaction: `KeyStore`, `X509` and the like);
  - its name (protocol setting, additional parameter or partner field) matches a secret name
    pattern, case-insensitively: at least `password`, `passwd`, `pwd`, `secret`, `token`,
    `keystore`, `privatekey`, `passphrase`, `credential`, `apikey`, `accesskey`;
  - its value is key material (a PEM private key, or a long base64 value that decodes to a JKS,
    JCEKS or PKCS#12 keystore), as the workspace export detects it.
  The masking MUST be at least as strict as the workspace export's redaction (feature 003) for
  every property kind both see. Names that match a pattern are masked even if their value is
  harmless (e.g. a keystore reference in `SSLKeyStore`).
- **FR-009**: A masked value MUST be shown only as masked with "set" (non-empty) or "not set"
  (empty or absent). The result MUST give the number of masked values per partner.
- **FR-010**: Masking MUST happen in memory before anything leaves the component that parses the
  export: the unmasked values MUST NOT reach the result builder, the log, an error message, the
  audit, or the cache, neither in clear nor in encrypted form. With StartCLI, the raw export file
  MUST be deleted with StartCLI's private temporary directory as in the existing exports; with
  REST, the export MUST NOT be written to disk. The only thing the
  parsing component MAY pass on about a secret besides "set"/"not set" is a comparison fingerprint
  as defined in FR-012b.
- **FR-011**: The search and the similar-value suggestions MUST NOT use masked values, so that a
  search term cannot probe a secret.

**Comparison (story 3)**

- **FR-012**: With a stage target (several servers) or `compareWith`, the result MUST group per
  partner and per entry: values equal on all compared servers are shown once and marked equal;
  differing values are shown per server; partners and entries present only on some servers are
  marked with the servers they are on.
- **FR-012a**: For a masked value that is set on at least two compared servers, the comparison MUST
  state whether the stored values are the same on all of them, and if not, which servers share a
  stored value (groups of servers, no value). The statement compares the values as INUBIT stores
  them (comparable across servers per the spike, see Context); the tool documentation MUST say
  that the same password in two different stored forms (an older encoding on one server, AES on
  another) is reported as differing. Outside a comparison (one server, or a value set on only one
  server) no equality statement is made. If the plan's research finds that stored forms of equal
  secrets are not comparable for a version or channel, the comparison MUST make no equality
  statement for secrets there and say why.
- **FR-012b**: The equality MUST be decided from a fingerprint that the parsing component computes
  with a random key created in memory when the server process starts. The key is never written,
  logged, returned or audited and is lost when the process ends; the fingerprint never leaves
  memory (it may be kept in the cache of FR-018) and is never shown.
- **FR-012c**: In a comparison, `total`, `offset` and `nextOffset` MUST count matched partners
  (FR-013) across all compared servers. A partner is included if the search matches its non-masked
  fields on at least one server; it is then shown with its entries from all compared servers.
  With a search term the comparison shows all settings grouped (FR-012); without one it shows the
  summary fields of FR-006 plus only the settings that differ or are present only on some servers.
- **FR-013**: Partners MUST be matched across servers by system diagram (name and diagram group),
  communication cloud (module name) and partner `Id` (or, without `Id`, the first configured non-masked partner field present on both
  sides; masked fields are never a match key); entries by protocol and referenced workflow (its name,
  resolved through the entry's reference into `ReferencableTWFs`), then by position among entries
  with the same key.
  Ambiguous matches (duplicate keys) MUST be reported, not resolved arbitrarily.
- **FR-014**: A server that fails MUST NOT fail the comparison; it MUST be reported with its error,
  and the comparison MUST state that it is incomplete. A failed server is left out of all equality
  statements and "only on" markers; the result names it as not compared.

**Workflow to partner settings (story 4)**

- **FR-015**: For a technical workflow, `get_inventory_item` MUST list the distinct
  `ISPartnerProtocol.*` and `ISPartnerParameter.*` variables it uses, whether it contains a
  `PartnerManagement` node, and the system diagrams and communication clouds of the owner whose
  `ReferencableTWFs` contain it. It MUST NOT read system diagrams for a workflow without partner variables and without a
  `PartnerManagement` node. The system diagrams it reads MUST come from the cache of FR-018 (or
  fill it). It returns names only (no partner data), so it is not audited, like the rest of
  `get_inventory_item`. If the plan finds that this needs more than new fields in
  `get_inventory_item`'s result, story 4 moves to a later feature with a backlog entry. If the
  partner management capability is not available on the node (no channel, refused version), the
  result still lists the partner variables and the `PartnerManagement` node and states why the
  system diagrams were not read (error kind and next step).

**Versions, bounds, errors**

- **FR-016**: The feature MUST declare its own capability in the capability matrix (features 009,
  012, 017), named in words "partner management", kind read, with the channel of FR-001a. INUBIT
  8.1.x: supported. INUBIT 9.0.x, 9.x (`V9_X`), any other version and an unknown version: the read
  runs with the "not verified" warning in the result (and, with StartCLI, subject to the client
  check); it MUST NOT be declared supported until recorded responses of that version are under
  contract test. INUBIT 6.1.x: refused with `UNSUPPORTED_VERSION` until recorded 6.1 responses are
  under contract test. `list_nodes` and `--check-config` MUST show the new capability.
- **FR-017**: Results MUST be paginated (`limit` 1–100; the plan fixes the default) with a total
  and the next offset; every text MUST be cut at a fixed length with an explicit marker; the result
  MUST stay within the server's result size bound, cutting detail before entries and reporting
  `truncated`.
- **FR-018**: The read system diagrams MUST be cached per server and owner for the inventory cache
  lifetime (`inventory.cacheTtl`), only in masked form (with at most the keyed fingerprints of
  FR-012b); every result MUST carry `collectedAt` per server; `refresh: true` MUST bypass the
  cache; concurrent calls for the same data MUST share one read. A cache entry records which system
  diagrams it covers; a call is answered from the cache only if the entry covers every system
  diagram the call needs.
- **FR-019**: Failures MUST be actionable error results per server with the existing error kinds,
  including those of every other read (`UNREACHABLE`, `TLS_ERROR`, `FORBIDDEN`,
  `MAINTENANCE_MODE`, …) and in particular (`TARGET_UNKNOWN`, `INVALID_INPUT`, `NOT_CONFIGURED` without an owner, `NOT_FOUND` for an unknown
  system diagram with up to five similar system diagram names, `UNSUPPORTED_VERSION`,
  `CLI_UNAVAILABLE`, `AUTH_FAILED`, `TIMEOUT` naming the timeout setting, `UNEXPECTED_RESPONSE`,
  `INTERNAL`), each with likely cause and next step, and never with raw export content.

**Audit and observability (Principle VII)**

- **FR-020**: Every call MUST write one audit record per targeted or compared server, like
  `export_archives` does for its read-only export, whether that server's data came from INUBIT or
  from the cache (field `fromCache`), all records of one call sharing one call id: profile, server,
  capability, sanitized inputs (target, compare target, owner, search term, filters), MCP client,
  outcome (`EXECUTED`, `REFUSED`, `FAILED`), and that server's counts (system diagrams read,
  partners returned, values masked). A call refused before any server is contacted (e.g.
  `INVALID_INPUT`) writes one record without a server. The audit record MUST NOT contain partner
  settings (servers, logins, parameters) or any secret. If any audit record of a call cannot be
  written, the whole call MUST end in `INTERNAL` and return no partner data for any server.
- **FR-021**: Each call MUST write one structured INFO log line per server (server, owner,
  outcome, counts, call id) without partner settings or secrets.

**Documentation and repository hygiene**

- **FR-022**: The tool reference (`docs/tools.md`), the capability table, `SECURITY.md` (partner
  connection data such as hosts and logins now reach the conversation; secrets do not),
  `README.md` (tool list) and `CHANGELOG.md` MUST be updated in the same change.
- **FR-023**: No customer name, host, owner, partner Id, partner name, system diagram or workflow
  name may enter the repository (code, tests, fixtures, specs, docs, commit messages, PR texts);
  test data MUST use synthetic values (ACME, `*.example.test`, `Workflow-0001`, …) and pass
  `tools/check-identifiers.py` and `NoCustomerIdentifiersTest`.
- **FR-024**: The live check against a real partner and stage named by the operator MUST be
  recorded in `docs/live-tests.md` only in neutralized form (no real values).

**Operating knowledge**

- **FR-025**: Customer-specific operating knowledge (e.g. "the fallback channel reads its target
  directory from a repository configuration file and server and login from the Partner
  Management") MUST NOT be in the repository, not even as an example in this feature's tool
  descriptions. A place for it outside the repository is feature 019 (local knowledge per
  profile, see Clarifications); this feature does not depend on it.

### Key Entities

- **System diagram**: an INUBIT diagram of type `systemdiagram` of one owner, identified by name
  and diagram group, on one server; contains communication clouds.
- **Communication cloud**: a module of type `sdCommunicationCloud` in a system diagram, identified
  by its module name; carries at most one Partner Management.
- **Partner Management**: per communication cloud: the configured partner fields, the referencable
  technical workflows, and the partners.
- **Partner**: a set of partner field values (e.g. `Id`, `Name`) and a list of protocol entries.
- **Protocol entry** (`ReferencedTWF`): a protocol (FTP, WebService, …), the referenced technical
  workflow, protocol settings and additional parameters, each a named value that is either shown
  or masked.
- **Masked value**: a secret's place in the result; only its name and "set"/"not set", and in a
  comparison whether the stored values are the same (by server groups).
- **Comparison**: per partner and entry, the settings grouped into "equal on all servers" and
  "differs" (value per server), plus "only on <servers>" markers and the failed servers.
- **Partner variable** (story 4): an `ISPartnerProtocol.*` or `ISPartnerParameter.*` reference in
  a technical workflow.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The support question "which server, login, protocol and authentication settings does
  partner X use on stage Y" is answered with one tool call, without StartCLI by hand; in the live
  check the operator confirms the answer against the values they know.
- **SC-002**: Across the test suite, including a clear-text legacy password, a password without
  `encrypted`, a secret in a custom partner field and key material in a non-secret field, zero
  secret values or derived values appear in results, errors, logs or audit records (except the
  equality statement of FR-012a), the cache holds at most the keyed fingerprints of FR-012b, and no
  unkeyed hash of a secret exists anywhere.
- **SC-003**: A comparison of the servers of a stage and of two stages shows every differing
  setting, every differing stored secret (as "differs", grouped by servers) and every partner or
  entry present only on some servers, and nothing as differing that is equal; checked against
  prepared fixtures with known differences, with and without a search term.
- **SC-004**: Any page of results for 1,000 synthetic partners stays within the configured result
  size bound and reports truncation where it cut.
- **SC-005**: A repeated question within the cache lifetime is answered without a new read; on the
  1,000-partner fixture, the first answer for one server takes at most 5 seconds longer than the
  read of the owner's system diagrams itself.
- **SC-006**: The number of tools grows by at most one.

## Clarifications

### Session 2026-11-09

- Q: One new read tool, or system diagrams exported by `export_artifacts` into the workspace plus a
  tool that reads from there? → A: one new read tool now (in memory, plus the `get_inventory_item`
  extension for story 4); the workspace export of system diagrams, for a versioned history of
  partner changes, is a later feature (backlog entry).
- Q: May a comparison say whether a masked secret is the same on all compared servers? → A: yes,
  as one statement per secret (same on all / which servers share a value), decided inside the
  server process; no value or unkeyed derivative leaves it (FR-012a, FR-012b).
- Q: Does this feature add an optional local knowledge file per profile (MCP resource or
  instructions) for customer-specific operating knowledge? → A: no; it becomes feature 019, to be
  specified right after 018. The local knowledge is bound to the profile, and the fact that a
  local knowledge exists and can be used to keep research results belongs to the core of the
  server (not to the partner tool).

### Review round 1 decisions

- Channel: REST if a recorded REST diagram export carries the complete Partner Management,
  otherwise StartCLI (FR-001a); the plan's research decides.
- Comparison and paging: counts are matched partners across servers; a search hit on one server
  includes the partner from all; without a search term only differences plus the summary
  (FR-012c); overlapping targets are refused.
- Filters: case-insensitive exact matches; `protocol` is the `ReferencedTWF` attribute only
  (FR-005a).
- Similar values: from non-masked `Id` and `Name` only, with `get_inventory_item`'s rule (FR-005b).
- Secret equality: always from a process-keyed fingerprint computed by the parsing component
  (FR-012b); comparable stored forms per the spike, with a fallback if research disagrees.
- Keystore references such as `SSLKeyStore` stay masked by name; "set" still tells that one is
  configured.
- Story 4 is in scope (P3) with a stated criterion for moving it; it uses the cache and is not
  audited.
- Audit: one record per contacted server, linked by a call id.

### Review round 2 decisions

- Audit: one record per targeted or compared server, also for cache hits (`fromCache`), so that no
  partner data is returned without an audit record (FR-020).
- A failed server is left out of equality statements and "only on" markers (FR-014).
- Filters in a comparison: `NOT_FOUND` only if the system diagram is on no answering server; entry
  filters apply per entry key on all servers (FR-005a).
- Masked partner fields are never a match key (FR-013).
- Story 4 without the capability still lists the variables and says why no system diagram was read
  (FR-015).
- REST channel: no export on disk (FR-010); `apikey`, `accesskey` added to the name patterns
  (FR-008).

### Plan research decisions

- Channel: REST; the recorded REST export carries the same Partner Management as the StartCLI
  export (FR-001a, research D-1).
- A system diagram contains communication clouds, each with at most one Partner Management;
  results, filters' scope and the match key include the cloud (FR-004, FR-007, FR-013).
- Workflows in the Partner Management are identified by name only (Assumptions).

## Assumptions

- The channel is chosen by FR-001a; the plan's recording selected REST (research D-1): one REST
  diagram export per system diagram of the owner (found through the diagram list).
- Partner settings that are not secrets (hosts, ports, logins, directories, file names, protocol
  parameters) are operational data the operator is allowed to see; they reach the conversation
  like other inventory data. The same restriction to the configured owner as in the inventory
  tools applies.
- The audit follows `export_archives` (each call audited) rather than `export_artifacts` (workspace
  history), because this tool has no workspace history and returns connection data.
- Only the owner's own system diagrams are read; diagrams of other owners are not found, as in the
  inventory tools.
- A `PartnerManagement` node in a technical workflow and the `ISPartner*` variables are identified
  by name in the exported workflow; their exact form is confirmed against a recorded export in the
  plan's research.
- `ReferencableTWFs` entries identify a technical workflow by name only, and a `ReferencedTWF`
  points to one of them by a local id (recorded, research D-2). The `workflow` filter (FR-005a),
  the entry match key (FR-013) and story 4 (FR-015) therefore use the workflow name; two workflows
  of the same name in different diagram groups are not told apart, and the documentation says so.
- INUBIT stores equal secrets in equal form across servers and stages of one installation landscape
  (spike, section 4); FR-012a has a fallback if the research finds otherwise.
- Exporting a system diagram through StartCLI has the same small trace in INUBIT as other exports
  (check-in comment suffix); this is accepted as for the other read exports.

## Out of Scope

- Any change to the Partner Management (create, update, delete partners), also through the
  `PartnerManagementRestlet`.
- Exporting system diagrams into the workspace or as Workbench archives (backlog entry, later
  feature).
- Interpreting the authentication beyond showing the configured values (e.g. testing a login).
- Resolving where a workflow takes values that are not in the Partner Management (e.g. a target
  directory from a repository configuration file); see FR-025.
- A local knowledge store per profile (feature 019).
- INUBIT 6.1 support for this capability.
