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

- **The Partner Management belongs to system diagrams.** It is no separate store: every system
  diagram (diagram type `systemdiagram`) can carry one. An owner can have several system diagrams;
  `list_inventory` with `type: systemdiagram` already lists them.
- **Structure in the exported diagram.** The system diagram's `workflow.xml` holds a property map
  `PartnerManagementSettings` with
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
- **No read interface over REST.** INUBIT has a `PartnerManagementRestlet` (operation
  `modifyPartnerManagement`: create, update, delete one partner, delete all partners). It only
  writes; it cannot read. `/ibis/rest/application.wadl` answers 404.
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
- **Values differ between servers and stages** (e.g. another host and login on the test stage than
  on the production stage), so a comparison across servers and stages is part of the use case.
- **Audit today.** Of the read-only exports, `export_archives` (feature 015) writes audit records;
  `export_artifacts` records its exports in the workspace history instead.

**Tool surface (decision of this spec, Principle IV).** Considered:

- (a) Extend `export_artifacts` to export system diagrams into the workspace and add a tool that
  reads the Partner Management from the workspace. Two calls per question, the workspace lock and
  its history are involved, and a stage comparison needs one export per server first.
- (b) **One new read tool** that exports the system diagrams of the owner in memory, masks the
  secrets, and answers the search, list and comparison questions directly (like
  `get_inventory_item`, which also runs StartCLI exports in memory); plus an optional extension of
  `get_inventory_item` for the workflow-to-partner question (user story 4), so no second new tool.

This spec follows (b) (operator's decision, see Clarifications). (a), a masked export of system
diagrams into the workspace for a versioned history of partner changes, is a later feature
(docs/backlog.md). The tool's working name is `find_partners`; the plan fixes the name.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Read a partner's connection settings (Priority: P1)

An operator in a support case asks "Which server, login and authentication does partner ACME use
on the test stage?". They give a partner Id or part of a name and a stage or one server. They get,
per matching partner and per protocol entry, the server, the login, the protocol and additional
parameters and the authentication kind, together with the system diagram the partner belongs to
and the technical workflow the entry is for. Passwords and keys appear only as "set" or "not set".

**Why this priority**: this is the question from the support case and the whole reason for the
feature; without it nothing else is useful.

**Independent Test**: against a fake INUBIT server whose StartCLI export returns a system diagram
with three synthetic partners (one FTP entry with an encrypted password, one SFTP entry with an
unencrypted legacy password, one WebService entry with WS-Security credentials), search by Id and
by a lower-case part of the name on one server; check every shown value and that no secret value,
encrypted or not, appears in the result, the log or an error.

**Acceptance Scenarios**:

1. **Given** a server whose owner has one system diagram with partner `Id=ACME-0001`,
   `Name=ACME Corp`, **When** the operator searches for `acme-0001`, **Then** the result names the
   server, the system diagram, the partner's fields and, per `ReferencedTWF`, the protocol, the
   referenced workflow, every protocol setting and additional parameter that is not a secret, and
   each secret as masked with "set"/"not set".
2. **Given** the same server, **When** the operator searches for `corp`, **Then** the same partner
   is found (case-insensitive substring of any non-secret partner field, at least `Id` and `Name`).
3. **Given** a partner with two FTP entries for two different workflows, **When** it is shown,
   **Then** both entries appear, each with its workflow, and none is merged into the other.
4. **Given** a `Password` property without `encrypted="true"` holding a clear-text value, and
   a property named like `WSSecurityPassword` without a password type, **When** the partner is
   shown, **Then** neither value nor anything derived from it (length, hash, prefix) appears; both
   are reported as masked.
5. **Given** an owner with two system diagrams that both contain a partner matching the search,
   **When** the operator searches, **Then** both are returned, each naming its system diagram.
6. **Given** a search that matches nothing, **When** the call ends, **Then** the result is empty
   with a clear statement that no partner matched in which system diagrams (not an error), and up
   to five similar partner Ids or names are suggested.

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
   **Then** the first page holds the default number of partners in a stable order (system diagram,
   then partner Id, then name), with the total and the offset of the next page.
2. **Given** a filter `protocol: FTP`, **When** listing, **Then** only partners with at least one
   FTP entry appear, and each shows only its FTP entries.
3. **Given** a filter by a technical workflow, **When** listing, **Then** only partners with an
   entry for that workflow appear, and only those entries are shown.
4. **Given** list entries, **When** shown, **Then** each is a summary (system diagram, partner
   fields, protocols with server and login per entry) and the full details are available through
   the search of story 1.

---

### User Story 3 - Compare servers and stages (Priority: P2)

An operator suspects that a partner is configured differently on the servers of a stage, or wants
to know how the test stage differs from the production stage. They target a stage (all its
servers) or a stage and a second stage to compare with. Values that are equal everywhere are
shown once; differing values are shown per server.

**Why this priority**: the operator's support case had different hosts and logins on test and
production; comparing by hand is error-prone. It reuses stories 1 and 2.

**Independent Test**: two fake stages with two servers each, where one partner differs in
`ServerName` between the stages and in `Login` between the two servers of one stage, and one
partner exists on one stage only, and one password differs on one server; compare and check
grouping, the "only on" markers and that secrets appear only as "set"/"not set" and "same"/
"differs" by server groups.

**Acceptance Scenarios**:

1. **Given** a stage target with two servers that have identical Partner Management, **When** a
   partner is shown, **Then** each setting appears once, marked as equal on all servers.
2. **Given** `Login` differs between the two servers, **When** the partner is shown, **Then**
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

---

### User Story 4 - From a workflow to its partner settings (Priority: P3, optional)

An operator looks at a technical workflow and sees `ISPartnerProtocol.ServerName` or a
`PartnerManagement` node. They want to know which partner variables the workflow uses and in which
system diagram these values are maintained, to continue with story 1 or 2.

**Why this priority**: it shortens the path from the workflow to the right system diagram, but the
operator can reach the same data with story 2's workflow filter. It may move to a later feature
if the plan finds it too large.

**Independent Test**: a fake technical workflow using two `ISPartnerProtocol.*` variables, one
`ISPartnerParameter.*` variable and a `PartnerManagement` node, and a fake system diagram whose
`ReferencableTWFs` names that workflow; ask for the workflow's details.

**Acceptance Scenarios**:

1. **Given** that workflow, **When** the operator asks for its details, **Then** the result lists
   the distinct partner variables used (`ISPartnerProtocol.ServerName`, …), and the system
   diagrams of the owner whose `ReferencableTWFs` contain the workflow.
2. **Given** a workflow without partner variables and without a `PartnerManagement` node, **When**
   asked, **Then** the result says so, and no system diagram is exported for it.

---

### Edge Cases

- **No system diagram, or no Partner Management in it**: the owner has no system diagram, or a
  system diagram without `PartnerManagementSettings` or with an empty `Partners` list. Result:
  empty, stating which system diagrams were read; not an error.
- **Partner without `Id`**: the configured `PartnerFields` may not include `Id`. Partners are then
  identified by the configured fields in their order; a comparison matches partners by the first
  configured field that is present on both sides, and reports unmatched ones per server.
- **Duplicate partner Ids** in one system diagram: both are shown; a comparison reports the
  duplicate instead of matching arbitrarily.
- **Unknown or new protocols and parameters** (a protocol other than FTP or WebService, a setting
  name not seen before): shown as found, with the same masking rules; nothing is dropped silently.
- **Secret-like names with harmless values** (e.g. a `KeyStoreType` of `JKS`): masked anyway when
  the name matches a secret pattern; the result says how many values were masked, not why each.
- **Key material in a non-secret field** (a long base64 value that decodes to a keystore, a PEM
  private key): masked, as in the workspace export.
- **A partner field holding a secret** (a custom partner field named like a password): masked, and
  excluded from the search so that a search term cannot probe secret values.
- **Same secret, different stored form** (an older encoding on one server, AES on another):
  reported as differing; the documentation explains why (FR-012a).
- **Huge Partner Management** (thousands of partners, very long values): results stay within the
  configured size bounds; long texts are cut with an explicit marker; the page reports truncation.
- **Workflow in edit mode or diagram checked out**: no effect on reading; the export reads the
  stored version.
- **Export trace in INUBIT**: a StartCLI export changes the export suffix of the exported
  diagram's check-in comment (spike, section 3); the tool's documentation states this like for
  the other exports.
- **Slow or failing export**: a timeout names the export timeout setting; an authentication
  failure, a missing StartCLI installation or an unsupported client version give the existing
  error kinds per server; other servers of a stage are still answered.
- **Concurrent calls** for the same server share one export; a refresh flag forces a new export.
- **Archive that cannot be read** (unexpected structure, unknown version format): an error result
  for that server that names the part that could not be read, never raw XML or values.

## Requirements *(mandatory)*

### Functional Requirements

**Tool surface**

- **FR-001**: The server MUST offer one new read tool for stories 1–3 (working name
  `find_partners`) and MUST NOT add a second new tool for this feature; story 4, if built, extends
  `get_inventory_item`. The tool MUST carry the annotations of a read tool (read-only,
  non-destructive, idempotent) and be offered under the same condition as the other
  StartCLI-backed reads (a node with a StartCLI installation).
- **FR-002**: The tool MUST accept the id of one stage or one server as `target`, an optional
  second stage or server to compare with, an optional `owner` (default: the node's configured
  inventory owner), an optional search term, optional filters (system diagram name, protocol,
  referenced technical workflow), paging (`offset`, `limit`) and a `refresh` flag. Every input
  MUST be validated before INUBIT is contacted (length limits, allowed characters as for the
  existing diagram names).

**Reading**

- **FR-003**: The tool MUST read the Partner Management of every system diagram of the owner on
  each targeted server, or only of the named system diagram, and MUST NOT use any interface that
  can change the Partner Management (in particular never the `PartnerManagementRestlet`) or any
  other state on INUBIT.
- **FR-004**: For each partner the result MUST show the system diagram (name and diagram group),
  every non-secret partner field, and for each `ReferencedTWF` entry its protocol, its referenced
  technical workflow, every protocol setting and every additional parameter, secrets masked
  (FR-008). Entries MUST NOT be merged; their order MUST be the order in the export.
- **FR-005**: The search MUST match a case-insensitive substring against the non-secret partner
  fields (at least `Id` and `Name`); filters MUST combine with the search by AND. Without a search
  term the tool lists (story 2).
- **FR-006**: The result of a search MUST show full details per matching partner; a list without a
  search term MUST show a summary per partner (system diagram, partner fields, and per entry
  protocol, workflow, server and login), so that a page stays bounded.
- **FR-007**: The result MUST also report the system diagram's `ReferencableTWFs` for every system
  diagram that contributed a partner, and which system diagrams were read without a Partner
  Management.

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
    `keystore`, `privatekey`, `passphrase`, `credential`;
  - its value is key material (a PEM private key, or a long base64 value that decodes to a JKS,
    JCEKS or PKCS#12 keystore), as the workspace export detects it.
  The masking MUST be at least as strict as the workspace export's redaction (feature 003) for
  every property kind both see.
- **FR-009**: A masked value MUST be shown only as masked with "set" (non-empty) or "not set"
  (empty or absent). The result MUST give the number of masked values per partner. Values with a
  secret-like name that are not masked do not exist under FR-008; no "suspicious" list is needed.
- **FR-010**: Masking MUST happen in memory before anything leaves the component that parses the
  export: the unmasked values MUST NOT reach the result builder, the log, an error message, the
  audit, or the cache, neither in clear nor in encrypted form. The raw export MUST be deleted with
  StartCLI's private temporary directory as in the existing exports. The only thing the parsing
  component MAY pass on about a secret besides "set"/"not set" is a comparison fingerprint as
  defined in FR-012b.
- **FR-011**: The search MUST NOT match against masked values, so that a search term cannot probe a
  secret.

**Comparison (story 3)**

- **FR-012**: With a stage target (several servers) or a second target to compare with, the result
  MUST group per partner and per entry: values equal on all compared servers are shown once and
  marked equal; differing values are shown per server; partners and entries present only on some
  servers are marked with the servers they are on.
- **FR-012a**: For a masked value that is set on at least two compared servers, the comparison MUST
  state whether the stored values are the same on all of them, and if not, which servers share a
  stored value (groups of servers, no value). The statement compares the values as INUBIT stores
  them; the tool documentation MUST say that the same password in two different stored forms (an
  older encoding on one server, AES on another) is reported as differing. Outside a comparison
  (one server, or a value set on only one server) no equality statement is made.
- **FR-012b**: The equality MUST be decided inside the server process. If the plan keeps anything
  derived from a secret value for it (e.g. in the cache, FR-018), it MUST be a fingerprint keyed
  with a random key that is created in memory when the server process starts, is never written,
  logged, returned or audited, and is lost when the process ends; the fingerprint itself MUST
  never leave memory either. Without such a fingerprint, a comparison MUST read the values it
  compares in one call (bypassing the cache for that purpose).
- **FR-013**: Partners MUST be matched across servers by system diagram name and partner `Id`
  (or, without `Id`, the first configured partner field present on both sides); entries by
  protocol and referenced workflow, then by position among entries with the same key. Ambiguous
  matches (duplicate keys) MUST be reported, not resolved arbitrarily.
- **FR-014**: A server that fails MUST NOT fail the comparison; it MUST be reported with its error,
  and the comparison MUST state that it is incomplete.

**Workflow to partner settings (story 4, optional)**

- **FR-015**: For a technical workflow, `get_inventory_item` MAY list the distinct
  `ISPartnerProtocol.*` and `ISPartnerParameter.*` variables it uses, whether it contains a
  `PartnerManagement` node, and the system diagrams of the owner whose `ReferencableTWFs` contain
  it. If built, it MUST NOT export system diagrams for a workflow without partner variables and
  without a `PartnerManagement` node.

**Versions, bounds, errors**

- **FR-016**: The feature MUST declare its own capability in the capability matrix (features 009,
  012, 017), named in words "partner management", kind read, channel StartCLI. INUBIT 8.1.x:
  supported. INUBIT 9.0.x, 9.x (`V9_X`), any other version and an unknown version: the read runs
  with the "not verified" warning in the result, subject to the StartCLI client check; it MUST NOT
  be declared supported until recorded exports of that version are under contract test. INUBIT
  6.1.x: refused with `UNSUPPORTED_VERSION` until recorded 6.1 exports are under contract test.
  `list_nodes` and `--check-config` MUST show the new capability.
- **FR-017**: Results MUST be paginated (`limit` 1–100; the plan fixes the default) with a total
  and the next offset; every text MUST be cut at a fixed length with an explicit marker; the result
  MUST stay within the server's result size bound, cutting detail before entries and reporting
  `truncated`.
- **FR-018**: System diagram exports MUST be cached per server and owner for the inventory cache
  lifetime (`inventory.cacheTtl`), only in masked form (with at most the keyed fingerprints of
  FR-012b); every result MUST carry the time the data
  was collected; `refresh: true` MUST bypass the cache; concurrent calls for the same data MUST
  share one export.
- **FR-019**: Failures MUST be actionable error results per server with the existing error kinds
  (`TARGET_UNKNOWN`, `INVALID_INPUT`, `NOT_CONFIGURED` without an owner, `NOT_FOUND` for an unknown
  system diagram with similar names, `UNSUPPORTED_VERSION`, `CLI_UNAVAILABLE`, `AUTH_FAILED`,
  `TIMEOUT` naming the export timeout setting, `UNEXPECTED_RESPONSE`, `INTERNAL`), each with
  likely cause and next step, and never with raw export content.

**Audit and observability (Principle VII)**

- **FR-020**: Every call MUST be recorded in the audit log like `export_archives` does for its
  read-only export: profile, server, capability, sanitized inputs (target, compare target, owner,
  search term, filters), MCP client, outcome (`EXECUTED`, `REFUSED`, `FAILED`), and counts (system
  diagrams read, partners returned, values masked). The audit record MUST NOT contain partner
  settings (servers, logins, parameters) or any secret. A call whose audit record cannot be
  written MUST end in `INTERNAL` without returning partner data.
- **FR-021**: Each call MUST write one structured INFO log line per server (server, owner,
  outcome, counts, audit id) without partner settings or secrets.

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

- **System diagram**: an INUBIT diagram of type `systemdiagram` of one owner, in a diagram group,
  on one server; carries at most one Partner Management.
- **Partner Management**: per system diagram: the configured partner fields, the referencable
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

- **SC-001**: The support question "which server, login, protocol and authentication does partner
  X use on stage Y" is answered with one tool call, without StartCLI by hand; in the live check the
  operator confirms the answer against the values they know.
- **SC-002**: Across the test suite, including a clear-text legacy password, a password without
  `encrypted`, a secret in a custom partner field and key material in a non-secret field, zero
  secret values (or derived values other than the equality statement) appear in results, errors,
  logs, audit records or the cache, and no unkeyed hash of a secret exists anywhere.
- **SC-003**: A comparison of the servers of a stage and of two stages shows every differing
  setting, every differing stored secret (as "differs", grouped by servers) and every partner or
  entry present only on some servers, and nothing as differing that is equal; checked against
  prepared fixtures with known differences.
- **SC-004**: Any page of results for 1,000 synthetic partners stays within the configured result
  size bound and reports truncation where it cut.
- **SC-005**: A repeated question within the cache lifetime is answered without a new export;
  the first answer for one server takes no longer than one system diagram export of its owner plus
  a few seconds.
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

## Assumptions

- The data is read through StartCLI's export with diagram type `systemdiagram` of the owner's
  diagram groups that contain system diagrams (found through the diagram list); whether INUBIT's
  REST diagram export also carries `PartnerManagementSettings` is a research item of the plan, not
  a requirement.
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
- Exporting a system diagram has the same small trace in INUBIT as other exports (check-in comment
  suffix); this is accepted as for the other read exports.

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
