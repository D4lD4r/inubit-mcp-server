# Feature Specification: Modules Managed by a System Diagram

**Feature Branch**: `026-system-diagram-managed-modules`

**Created**: 2026-11-27

**Status**: Draft

**Input**: the operator's request (German, summarized): when an agent analyzes modules through the
MCP server, it always overlooks that the modules are configured by a system diagram. Is that
detectable, and can it become a fixed check of the MCP server? An analysis in the same session
showed that it is detectable (see Context) and fixed the prerequisite: `read_model_details` now
reads the `Map` and `List` properties of system diagram elements (PR #113).

## Context

**Owner** in this spec means the server's configured `inventory.owner`, as in `get_inventory_item`.
**System diagram element** means a module of a system diagram; INUBIT gives all of them a type
starting with `sd` (seen: `sdExtSystem`, `sdInubitIS`, `sdCommunicationCloud`).

What the analysis found (INUBIT 8.1.17, one non-production node, 2026-11-27):

- **INUBIT manages connection parameters of system connectors centrally in system diagrams.** The
  INUBIT documentation ("Managing the Connection Parameters of System Connectors Centrally"): an
  external system element of a system diagram can hold the connection parameters of one system
  connector type ("Edit resource settings") and assign them to several modules of that type; the
  element then shows a connection symbol whose context menu lists the managed system connectors.
- **It is used widely.** The owner on the probed node has ten system diagrams, six of them named
  after connector types (file, HTTP, REST endpoint, web service, database, JMS).
- **The module itself carries no marker.** The module detail of a managed HTTP Connector
  (`read_model_details`) shows its own properties only; nothing points to the system diagram. Its
  own `ServerName` pointed to a local dummy endpoint, while the external system element that
  manages it held a different server. An agent reading the module took the dummy for the real
  endpoint.
- **The system diagram side names the managed modules.** A system diagram lists its elements with
  type and node id (`get_inventory_item` with `kind: DIAGRAM`); an external system element has type
  `sdExtSystem`. Its module detail (`read_model_details`, readable since PR #113) has:
  - `ResourceType` (String): the system connector type it manages;
  - `ResourceSettings` (Map): the central connection settings, e.g. `ServerName`, `Login`,
    `Password` (type `Password`), `SSLKeyStore` (type `HTTPConnectorTrustStore`) and, for one
    element, OAuth settings (`OAUTH_PROPERTY.client_secret`, `…refresh_token`, …);
  - `ResourcesLinked` (List): one `Module` entry (String) per managed module, holding its name.
  In the probe each of two elements named one module, and the named modules exist in the owner's
  module list. A `Module` entry holds only the name; INUBIT does not forbid two modules of an
  owner with the same name (`get_inventory_item` already tells them apart by plugin type).
- **Values differ per stage.** System diagrams are deployed per stage like other diagrams; the
  point of central management is that the same module gets other connection settings on each
  stage. Comparing them is done as today: one call with a stage id shows each server of the stage
  side by side; another stage needs another call.
- **Secrets.** `ResourceSettings` holds passwords, trust stores and OAuth secrets; the masking of
  `read_model_details` (feature 022, extended in PR #113) masks them per entry.
- **Existing building blocks.** `list_inventory` lists system diagrams (`type: systemdiagram`) and
  modules; `get_inventory_item` shows a module's using workflows and versions and, for technical
  workflows, already reads the owner's system diagrams for their Partner Management (feature 018,
  with the inventory cache).

**Tool surface (decision of this spec, Principle IV).** Considered:

- (a) A new tool "which system diagram manages module X". Agents would have to know to call it,
  which is exactly what fails today.
- (b) **No new tool; a fixed check in the tools agents already use for modules**: the module
  results of `get_inventory_item` and `read_model_details` say on their own whether the module is
  managed by a system diagram, by which element, and with which central settings. A system
  diagram's detail lists its external systems and the modules they manage.

This spec follows (b): the check is "fixed" because it runs on every module result without a
parameter, and its outcome is always stated, also when it could not be determined.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A module result says that a system diagram manages it (Priority: P1)

An agent analyzing a connector module (e.g. "where does module X send its data on the test
stage?") calls `get_inventory_item` with `kind: MODULE`. The result states that the module's
connection settings are managed centrally, names the system diagram (with its diagram group), the
external system element and the managed connector type, and shows the central settings with
secrets masked. The agent therefore answers with the central server, not the module's own value.

**Why this priority**: this is the failure the operator reported; without it the feature has no
value.

**Independent Test**: against a fake INUBIT server whose owner has one system diagram with one
external system element that links module `Demo-HTTP-Out-A` (central `ServerName`
`https://central.example.test`, a planted password) and a module `Demo-HTTP-Out-A` whose own
`ServerName` is `http://localhost:8000/dummy`: the module result names the diagram, group, element
and type, shows the central `ServerName`, masks the password, and no planted secret appears in the
result, the log or an error.

**Acceptance Scenarios**:

1. **Given** a module linked by exactly one external system element, **When** `get_inventory_item`
   shows the module, **Then** the result has a management section with status "managed", the
   system diagram and its diagram group, the element name, the connector type, and the central
   settings in INUBIT's order with each secret masked.
2. **Given** the same module, **When** its own connection setting differs from the central one for
   a setting that is not secret (e.g. `ServerName`), **Then** the result names that setting as
   differing and shows both values, so the agent sees which value the module's own properties
   would suggest and which one is managed centrally.
3. **Given** a module that no external system element of the owner links, **When** it is shown,
   **Then** the management section has status "not managed" and names how many system diagrams
   were checked.
4. **Given** a module linked by two external system elements (in the same or in two system
   diagrams), **When** it is shown, **Then** both are listed and the result warns that the module
   is managed more than once.
5. **Given** a stage with two servers whose system diagrams set different central servers for the
   same module, **When** `get_inventory_item` is called with the stage id, **Then** each server's
   result shows its own central settings; the tool does not compute a comparison between servers.
6. **Given** a module linked by one element of a system diagram that was read, while another
   system diagram of the owner could not be read, **When** the module is shown, **Then** the status
   is "managed", the section says the check is incomplete, names the first system diagram that
   could not be read and how many were not read, and says that further management cannot be ruled
   out.
7. **Given** two modules of the owner that share the linked name, one of the element's connector
   type and one of another type, **When** each is shown, **Then** only the module of the matching
   type is "managed"; if both have the matching type (or the type cannot be compared), both are
   "managed" with a warning that the link cannot be attributed to a single module; the same
   applies when none of them has the matching type.

---

### User Story 2 - The module detail carries the same notice (Priority: P2)

An agent reads the properties of a connector module with `read_model_details` (`action: module`).
The result carries the same management section as user story 1, so that reading the module's own
properties cannot be mistaken for reading its effective connection settings.

**Why this priority**: `read_model_details` is where the misleading value is visible; the notice
belongs next to it. It builds on user story 1 and adds no new information.

**Independent Test**: the fake server of user story 1; `read_model_details` for the module's node
id in its technical workflow shows the module's own properties unchanged plus the management
section with status "managed".

**Acceptance Scenarios**:

1. **Given** a managed module, **When** `read_model_details` reads it, **Then** the result has the
   module's own properties unchanged and the management section of user story 1.
2. **Given** a system diagram element (type starting with `sd`, e.g. `sdExtSystem` or
   `sdCommunicationCloud`), **When** `read_model_details` reads it, **Then** the result has its
   properties (for `sdExtSystem` the `ResourceSettings` and `ResourcesLinked` entries) as today and
   no management section (an element is not a managed module).
3. **Given** a server without `inventory.owner`, **When** `read_model_details` reads a module,
   **Then** the module's own properties are shown as today and the section has status "unknown"
   with the reason that no owner is configured (NOT_CONFIGURED) and what to configure.
4. **Given** a `diagram` that is not in the owner's diagram list (e.g. a diagram of another
   INUBIT user), **When** `read_model_details` reads a module of it, **Then** the section has
   status "unknown" with the reason that the diagram does not belong to the configured owner, whose
   system diagrams are the ones checked.

---

### User Story 3 - A system diagram's detail lists the modules it manages (Priority: P2)

An operator asks "which modules does system diagram Y manage, with which settings?". They call
`get_inventory_item` with `kind: DIAGRAM` for the system diagram. The result lists, per external
system element, the connector type, the central settings with secrets masked and the managed
modules, marking each linked module name that does not exist in the owner's module list.

**Why this priority**: it answers the reverse question and is the overview an operator uses to
review central settings per stage; the per-module check (P1) does not need it.

**Independent Test**: a fake system diagram with two external system elements (one linking two
modules, one linking a module name that does not exist): the result lists both elements, their
types, masked settings and linked modules, and marks the missing one.

**Acceptance Scenarios**:

1. **Given** a system diagram with external system elements, **When** its detail is shown,
   **Then** each element appears with its connector type, central settings (secrets masked) and
   linked module names in INUBIT's order.
2. **Given** an element that links a module name the owner has no module of, **When** the detail
   is shown, **Then** that name is marked as not found.
3. **Given** a system diagram without external system elements (e.g. only communication clouds),
   **When** its detail is shown, **Then** it has no management list and its other fields are
   unchanged.
4. **Given** the owner's module list cannot be read, **When** the detail is shown, **Then** the
   linked names appear without a found/not-found mark and the list says why (error kind, what
   happened, what to do).
5. **Given** one element whose detail cannot be read, **When** the detail is shown, **Then** that
   element appears with its name and the error instead of type, settings and modules, and the
   other elements and the diagram's other fields are shown as usual.

---

### User Story 4 - The agent is told about the check (Priority: P3)

The descriptions of `get_inventory_item` and `read_model_details` state that connector modules can
be managed centrally by a system diagram and that their results say so, so an agent reads the
management section before it trusts a module's own connection settings.

**Why this priority**: it makes user stories 1 and 2 effective for agents that skim results; it is
text only.

**Independent Test**: the announced tool descriptions contain the statement; a pinned-description
test fails when it is removed.

**Acceptance Scenarios**:

1. **Given** the tool list, **When** an agent reads the two descriptions, **Then** both mention
   central management by system diagrams and the management section.

---

### Edge Cases

- **The check cannot be completed** (a system diagram or an element cannot be read, the node
  refuses the capability, the check's deadline is reached): if a link to the module was found in
  what was read, the status is "managed" and marked incomplete (user story 1, scenario 6);
  otherwise it is "unknown" with the reason and the first diagram, element or list (diagram list,
  module list) that failed. It never
  says "not managed" when not every system diagram of the owner was checked.
- **No owner configured** (possible for `read_model_details`): status "unknown", NOT_CONFIGURED.
- **The owner has no system diagram**: status "not managed", zero diagrams checked.
- **A module that is not found or a server that fails**: a NOT_FOUND item (with similar names) or a
  server error carries no management section; the section belongs to a successful module detail.
- **A non-connector module** (e.g. an XSLT converter): the check still runs and normally gives
  "not managed"; the spec does not decide by module type which modules can be managed, because
  self-developed system connector types can be managed too.
- **Module names**: a `Module` entry is compared with the module name exactly (case-sensitive),
  as INUBIT module names are; whitespace is not trimmed. When several modules of the owner share
  the name, the element's connector type decides (user story 1, scenario 7).
- **An element without `ResourcesLinked`** or with an empty list: it manages no module; it still
  appears in the system diagram's detail (user story 3) with an empty module list.
- **An element whose `ResourceSettings` is masked as a whole** (secret-named container): the
  settings are shown as masked; linked modules are still listed.
- **Secret settings that differ**: for a secret setting (masked), the result does not compare or
  show values; it only lists the setting as managed centrally.
- **Many system diagrams or elements**: results stay bounded; the management section and the
  element list are truncated with an explicit marker beyond the bounds of the plan.
- **INUBIT versions**: on a version where this read is not verified, the section says "unknown"
  with the version as reason instead of guessing.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Every successful module detail of `get_inventory_item` (`kind: MODULE`) MUST contain
  a management section with a status of exactly one of "managed", "not managed" or "unknown", on
  every server of the result. NOT_FOUND items and server errors carry no section.
- **FR-002**: Status "managed" MUST list every external system element of the owner's system
  diagrams that links the module (FR-003), each with the system diagram name, its diagram group,
  the element name, the connector type and the central settings.
- **FR-003**: An element links a module if a `Module` entry of its `ResourcesLinked` equals the
  module's name exactly. If several modules of the owner have that name, only those whose type
  matches the element's connector type are linked; if none or more than one remains, or the types
  cannot be compared (e.g. the module list cannot be read), every module of that name is linked
  and its section warns that the link cannot be attributed to a single module. If exactly one
  module has the name and its type is known not to match the element's connector type, it is
  linked and its section warns about the mismatch.
- **FR-004**: The section MUST say whether the check was complete. Status "not managed" MUST be
  given only when every system diagram of the owner on that server was checked completely, and
  MUST state the number of system diagrams checked.
- **FR-005**: When the check is incomplete and a link was found in what was read, the status MUST be
  "managed", marked incomplete, with the first system diagram or element that could not be read
  (or the list that could not be read: the owner's diagram list or module list), the number not
  read, and a note that further management cannot be ruled out. When the check is
  incomplete and no link was found, the status MUST be "unknown" with the reason (error kind, what
  happened, what to do) and the first system diagram or element, or the list, that could not be
  read. In both
  cases the result's other fields MUST stay as today.
- **FR-006**: Central settings MUST be shown in INUBIT's order and masked by the same rules as
  `read_model_details` (per entry; a secret-named container as a whole); no secret value may
  appear in a result, log line, audit record or error excerpt.
- **FR-007**: For a managed module, the section MUST list each non-secret central setting whose
  name is also a property of the module and whose value differs from the module's own value, with
  both values; secret settings MUST NOT be compared. If the module's own properties cannot be
  read, the section MUST say that the comparison is unavailable (error kind, what happened, what
  to do); the status and the managing elements are unaffected.
- **FR-008**: A module linked by more than one element MUST show all of them and a warning that the
  module is managed more than once.
- **FR-009**: The module result of `read_model_details` (`action: module`) MUST carry the same
  management section for the module it reads, checked against the system diagrams of the server's
  owner, and MUST leave the module's own properties unchanged. It MUST carry no section for a
  system diagram element (type starting with `sd`). Without a configured owner the status MUST be
  "unknown" with reason NOT_CONFIGURED; for a `diagram` that is not in the owner's diagram list it
  MUST be "unknown" with the reason that the diagram does not belong to the configured owner.
- **FR-010**: The detail of a system diagram (`get_inventory_item`, `kind: DIAGRAM`, type
  `systemdiagram`) MUST list its external system elements with connector type, masked central
  settings and linked module names, and MUST mark linked names for which the owner has no module.
  If the owner's module list cannot be read, the names MUST appear without a mark and the list MUST
  say why; an element whose detail cannot be read MUST appear with its name and the error; the
  diagram's other fields MUST stay as today.
- **FR-011**: The check MUST run without a tool parameter (no opt-in, no opt-out).
- **FR-012**: The descriptions of `get_inventory_item` and `read_model_details` MUST state that a
  connector module's connection settings can be managed centrally by a system diagram and that the
  result's management section says so.
- **FR-013**: The check MUST only read INUBIT; it MUST NOT change anything on INUBIT.
- **FR-014**: Results MUST stay bounded; the plan sets the bounds and the truncation marker of the
  management section and the element list.
- **FR-015**: The check MUST be supported on INUBIT 8.1. On other versions it MUST give status
  "unknown" naming the version until the read is verified there; the plan states what is needed
  for 9.0.
- **FR-016**: What the check reads about the owner's system diagrams MAY be cached; the section
  MUST state when it was collected. `refresh` of `get_inventory_item` MUST bypass that cache;
  `read_model_details` has no `refresh` and uses the cache as it is (a `get_inventory_item` call
  with `refresh` renews it). Which cache (the inventory cache, the system diagram cache of
  `find_partners`, or a new one) is decided in the plan.
- **FR-017**: The check MAY extend the deadline of the calls it runs in by a budget set in the
  plan; reaching the check's budget MUST give "unknown" (or incomplete "managed", FR-005), not a
  failure of the whole result.

### Key Entities

- **Management section** (per module and server): status ("managed" | "not managed" | "unknown"),
  whether the check was complete, number of system diagrams checked (and not read), the owner
  checked, when it was collected, the managing elements, warnings, reason when "unknown" or
  incomplete.
- **Managing element**: system diagram (name, diagram group), element name, connector type
  (`ResourceType`), central settings (masked entries), differing settings (non-secret name, central
  value, module value).
- **System diagram management list** (per system diagram): its external system elements, each
  with connector type, central settings and linked module names, a linked name marked when not
  found.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In a manual acceptance run with the operator's agent and the prompt "Where does
  module Demo-HTTP-Out-A send its data?" against the fake server of user story 1, 10 of 10 answers
  name `https://central.example.test`, none the module's dummy endpoint.
- **SC-002**: Every successful module detail of both tools carries the management section with one
  of the three statuses; no result says "not managed" when a system diagram of the owner was not
  read.
- **SC-003**: No planted secret of the test fixtures (central passwords, trust stores, OAuth
  secrets) appears in any result, log line, audit record or error excerpt of the check.
- **SC-004**: A second module call on the same server within the cache's lifetime reads no system
  diagram again.
- **SC-005**: For an owner with ten system diagrams, the first module call on a server completes
  within the tool's deadline as extended by the plan for this check (FR-017).

## Assumptions

- A `Module` entry of `ResourcesLinked` holds the module name exactly as the module list shows it;
  the probe confirmed it for two modules. Whether INUBIT uses the central settings at runtime
  instead of the module's own values is taken from the INUBIT documentation and the operator's
  experience; the probe saw that the two can differ. The result therefore says "managed
  centrally" and shows both values, without claiming which one INUBIT applied in a past run.
- Only external system elements (`sdExtSystem`) manage modules. Other system diagram element types
  seen so far (communication clouds, inubit systems) do not have `ResourcesLinked`; if another
  type has it, the plan's recording decides whether it is included.
- How the system diagrams are read (diagram export, module detail per element, or both) and how
  many requests that needs is decided in the plan; the plan records one system diagram's export
  and element details as fixtures, neutralized and with planted secrets.
- The connector type of an element (`ResourceType`) can be compared with a module's type; the
  plan's recording confirms the form of both. If it cannot, FR-003's fallback (all modules of the
  name, with a warning) applies.

## Out of Scope

- A new tool; markers in `list_inventory` module lists or in modules exported by
  `export_artifacts` (a possible later feature).
- Changing central settings or the assignment of modules to elements.
- Deciding which value INUBIT applied at runtime in a past run.
- Comparing servers or stages within the tool (a stage id shows its servers side by side, as
  today; another stage needs another call).
