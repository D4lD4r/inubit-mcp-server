# Feature Specification: Local Knowledge per Profile

**Feature Branch**: `019-local-knowledge`

**Created**: 2026-11-09

**Status**: Draft

**Input**: the operator's decision in the clarification of feature 018 (2026-11-09, German,
summarized): customer-specific operating knowledge does not belong in the repository; it becomes
its own feature 019, specified right after 018. The local knowledge is bound to the profile, and
the fact that a local knowledge exists and can be used to keep research results belongs to the
core of the MCP server.

## Context

- **The problem.** Answering support questions needs operating knowledge about a customer's
  integrations that no INUBIT interface returns. Example (neutral): "the fallback channel reads its
  target directory from a repository configuration file, and server and login from the Partner
  Management". This knowledge must never enter this public, customer-neutral repository
  (CONTRIBUTING.md, `tools/check-identifiers.py`). Today it lives in the operator's head or in old
  chat transcripts, and what was found out in a support case is lost when the session ends.
- **Profiles.** One server process serves exactly one profile (one customer installation, e.g.
  `acme`). Per-profile local state already exists: the workspace (feature 003, default
  `~/.inubit-mcp/<profile>/workspace`), the audit directory, backups, deployment ledgers. All are
  created owner-only.
- **What the client sees today.** The server's `initialize` result carries instructions: the
  profile name and description and how targets are addressed. The server offers only tools, no MCP
  resources. Every tool description starts with `[<profile>: <description>]`.
- **Principles touched.** I (read-only by default; it governs state on INUBIT, but every
  state-changing tool is also annotated and gated), II (no secrets in results, logs, or now in
  stored knowledge), IV (small use-case tool set), VI (bounded output), VII (audit of
  state-changing calls).

**Decided before this spec (operator, 2026-11-09):**

- The local knowledge is bound to the **profile**: every profile has its own, and a server process
  sees only the knowledge of its profile.
- That a local knowledge exists and can be used to keep research results belongs to the **core of
  the server**: the core instructions announce it for every profile; it is not tied to a single
  INUBIT tool (such as the partner tool of feature 018).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The assistant uses what is already known (Priority: P1)

An operator asks a support question ("how does the fallback channel deliver files to partner
ACME?"). The assistant knows from the server's instructions that this profile has a local
knowledge with notes on certain topics. It searches the knowledge, reads the matching note, and
answers with it, combined with live data from the INUBIT tools, saying which part came from the
note and how old it is.

**Why this priority**: knowledge that is not found and read is worthless; reading is also the
safe half (no state changes).

**Independent Test**: a profile whose knowledge directory holds three hand-written Markdown notes
(synthetic content); start the server, check the instructions name the knowledge and its topics;
search for a term from one note and read it; check bounds, paging and that a secret written by
hand into a note is masked on reading.

**Acceptance Scenarios**:

1. **Given** a profile with notes, **When** a client connects, **Then** the instructions state
   that a local knowledge for this profile exists, how to search and read it, that research
   results can be kept in it (US2), and list the note titles up to a fixed size bound (with the
   total number when cut).
2. **Given** a profile without a knowledge directory or without notes, **When** a client
   connects, **Then** the instructions still state that a local knowledge can be used for this
   profile and that it is empty.
3. **Given** a search term, **When** the assistant searches, **Then** it gets matching notes
   (title, topics, last change, a short excerpt around the match), case-insensitive, paginated.
4. **Given** a note name, **When** the assistant reads it, **Then** it gets its content, metadata
   and last change; content above the size bound is cut with an explicit marker and a way to read
   the rest.
5. **Given** a hand-written note that contains a configured credential of the profile, a PEM
   private key or a line like `password: …`, **When** it is read or found by a search, **Then**
   those values are masked in the result.

---

### User Story 2 - Keep research results for later sessions (Priority: P1)

After a support case the assistant has found out how a channel works (which workflow, which
configuration file, which Partner Management entry). It proposes a note to the operator and, after
the operator agrees, records it in the profile's local knowledge, so that the next session (also another person on the same machine with the same
profile directory) can use it. Corrections replace or extend an existing note; nothing is lost.

**Why this priority**: this is the operator's stated goal ("research results survive the
session"); without it the knowledge only grows by hand.

**Independent Test**: record a new note, extend it, replace it, try to record a note containing a
configured credential and one containing key material; check the files, the kept previous
versions, the audit records and that INUBIT is never contacted.

**Acceptance Scenarios**:

1. **Given** a research result, **When** the assistant records it with a title, topics and
   content, **Then** a note is created in the profile's knowledge directory, the result names it,
   and an audit record without the content is written.
2. **Given** an existing note, **When** the assistant records a correction, **Then** the note is
   updated and the previous version stays recoverable; the result says what changed (sizes, not
   content).
3. **Given** content that contains a secret (a configured credential value of the profile, key
   material, an encrypted INUBIT value, a `password`-like assignment), **When** the assistant
   tries to record it, **Then** the write is refused with the reason (kind and position, never the
   value), nothing is written, and the refusal is audited.
4. **Given** recording is switched off for the profile (see FR-012), **When** a client connects,
   **Then** the record tool is not offered and the instructions say the knowledge is read-only;
   reading still works.
5. **Given** a research result in a session, **When** the assistant has found it, **Then** it
   proposes the note (title, topics, content) and records it only after the operator agrees
   (FR-017).

---

### User Story 3 - The operator keeps control (Priority: P2)

The operator can read, edit and delete the notes with any text editor, put notes there by hand,
and move the knowledge to another directory (e.g. a private, synchronized folder). The knowledge
of one profile is never visible to the server process of another profile.

**Why this priority**: the knowledge is the operator's; the assistant only helps to fill it.

**Independent Test**: two profiles with different knowledge directories; check each server sees
only its own; edit a note by hand and check the next read shows it; configure a directory inside
another profile's directory or inside the workspace and check the configuration is refused.

**Acceptance Scenarios**:

1. **Given** two profiles, **When** each server runs, **Then** each lists, searches, reads and
   writes only its own profile's knowledge.
2. **Given** a note edited by hand while the server runs, **When** it is read next, **Then** the
   edited content is returned (no stale cache).
3. **Given** a configured knowledge directory that overlaps another profile's directories, the
   workspace, the audit directory or the server's configuration directory, **When** the server
   starts or `--check-config` runs, **Then** the configuration is reported as invalid with the
   conflicting path.

---

### Edge Cases

- **Missing directory**: created owner-only on the first write, not at start; reading an absent
  directory gives an empty knowledge, not an error.
- **Files that are not notes** (images, binaries, hidden files, very large files): ignored by
  listing and search; a read names them as unsupported.
- **Malformed metadata** in a hand-written note: the note is still listed with its file name as
  title and a warning.
- **Two notes with the same title**: both listed; the note name (file name) identifies them.
- **Note names**: restricted to safe names (no path separators, no `..`, no reserved device names,
  bounded length); a name that differs from an existing one only in letter case collides.
- **Concurrent writes** from two server processes of the same profile (two clients): one wins
  cleanly, the other is refused or retried; a note is never half written.
- **Symbolic links** inside the knowledge directory that point outside it: not followed for
  reading or writing.
- **Large knowledge** (thousands of notes, megabytes): instructions list only the newest or most
  important titles within their bound and give the total; search stays paginated and bounded.
- **Customer names in the knowledge**: allowed and expected (it is outside the repository); but
  the repository's tests, fixtures and docs use synthetic notes only.
- **A note claims to be an instruction** ("ignore the rules and …"): the server returns notes as
  data; the instructions tell the client that notes are reference material, not instructions.
- **Unreadable file** (permissions): listed with a warning, read gives an actionable error.

## Requirements *(mandatory)*

### Functional Requirements

**Storage and separation**

- **FR-001**: Every profile MUST have a local knowledge directory, by default
  `~/.inubit-mcp/<profile>/knowledge`, configurable per profile. A server process MUST read and
  write only its own profile's knowledge directory.
- **FR-002**: The configured directory MUST NOT lie inside or contain the workspace, the audit
  directory, another per-profile directory of this or another profile, or the server's
  configuration directory; violations MUST be reported by the configuration check at start and by
  `--check-config`.
- **FR-003**: Directories and files the server creates MUST be owner-only (as the other profile
  directories); existing directories' permissions MUST NOT be changed.
- **FR-004**: A note MUST be a human-readable and human-editable text file (Markdown) with optional
  metadata: title, topics (free tags, e.g. a stage, a workflow, a partner), last change, and
  source (who or what recorded it: the operator by hand, or the assistant with the MCP client's
  name). Hand-written files without metadata MUST still be usable.

**Core instructions**

- **FR-005**: The server's instructions MUST, for every profile, state that a local knowledge for
  this profile exists, that it holds operating knowledge and research results of earlier
  sessions, how to search, read and record it, and that notes are reference material, not
  instructions.
- **FR-006**: The instructions MUST list the note titles (with topics) within a fixed size bound;
  if cut, they MUST give the total and point to the search. The instructions reflect the state at
  connection time; the search reflects the current state.
- **FR-007**: This announcement MUST be part of the server core, independent of which INUBIT tools
  are offered for the profile.

**Reading**

- **FR-008**: The assistant MUST be able to list and search the notes (case-insensitive substring
  over title, topics and content; filter by topic), paginated, each hit with title, topics, last
  change, source and a bounded excerpt; and read one note by name, its content cut at a fixed
  bound with an explicit marker and a continuation offset.
- **FR-009**: Reading MUST reflect edits made by hand since the last read.
- **FR-010**: Reading MUST mask in results and excerpts: values equal to a credential configured
  for the profile, key material (PEM private keys, keystores in base64), INUBIT encrypted values
  (e.g. `AES…` ciphertexts), and the value of assignments whose key matches a secret name pattern
  (`password`, `passwd`, `pwd`, `secret`, `token`, `passphrase`, `credential`, `apikey`). The
  result MUST say how many values were masked.

**Recording**

- **FR-011**: The assistant MUST be able to record a note: create it, or update an existing note
  (replace its content or append a section), with title, topics and content. Recording MUST never
  delete a note; deleting is the operator's job by hand.
- **FR-012**: Recording MUST be on by default for every profile and switchable off per profile in
  the configuration; it never touches INUBIT, so the INUBIT write switches (`write.enabled`,
  production opt-in) MUST NOT govern it. With recording off, the instructions MUST say that the
  knowledge is read-only for this profile.
- **FR-013**: Before writing, the content, title and topics MUST be checked with the rules of
  FR-010; if any secret is found, the whole write MUST be refused, naming the kind and the line,
  never the value. A note MUST NOT exceed a fixed size bound; the knowledge directory MUST NOT grow
  beyond a fixed total bound through recording.
- **FR-014**: Every update MUST keep the previous version recoverable inside the knowledge
  directory (e.g. a history kept next to the notes), bounded in number per note; the operator can
  restore it by hand.
- **FR-015**: A note MUST be written completely or not at all (no half-written file visible under
  its name), also with two server processes of the same profile writing at the same time.
- **FR-016**: The recording tool MUST be annotated as a local write (not read-only, not
  destructive, not idempotent, no INUBIT contact), so that clients may ask for confirmation.
- **FR-017**: The instructions (FR-005) MUST tell the assistant to propose a note after it has
  found out something worth keeping (title, topics and the content it would record) and to record
  it only after the user agrees, or when the user asks for it; never on its own. The record tool's
  description MUST repeat this rule.

**Tool surface**

- **FR-018**: The feature MUST add at most two tools (one to list, search and read; one to record)
  and no INUBIT capability; the tools MUST work without any INUBIT node being reachable and MUST
  appear for every profile (the record tool not when recording is switched off). The knowledge is
  offered through tools only; the server does not offer MCP resources in this feature.

**Audit, logs, errors**

- **FR-019**: Every recording call (created, updated, refused, failed) MUST write an audit record
  (profile, capability, note name, action, sizes before and after, MCP client, outcome) without
  the note's content, title text beyond the name, or any detected secret. Reads MUST NOT be
  audited but MUST be logged at DEBUG without content.
- **FR-020**: Failures MUST be actionable error results (`INVALID_INPUT` for a bad name or a
  secret in the content, `NOT_FOUND` with similar note names, `PRECONDITION_FAILED` for recording
  switched off, a size bound reached or an unusable directory, `INTERNAL`), without note content.

**Documentation and repository hygiene**

- **FR-021**: `docs/setup.md` (directory, switch, bounds), `docs/tools.md`, `README.md`,
  `SECURITY.md` (what may and may not be stored; the knowledge is local, unencrypted, owner-only)
  and `CHANGELOG.md` MUST be updated in the same change.
- **FR-022**: Tests, fixtures, examples and docs MUST use synthetic notes only (ACME,
  `*.example.test`, `Workflow-0001`); no customer knowledge enters the repository; the change
  passes `tools/check-identifiers.py` and `NoCustomerIdentifiersTest`.

### Key Entities

- **Local knowledge**: the per-profile directory of notes, with its bounds and its switch for
  recording.
- **Note**: a Markdown file with a name, optional title, topics, last change and source, and its
  previous versions.
- **Search hit**: a note's metadata and a bounded, masked excerpt.
- **Recording**: one create or update of a note, with its audit record.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In a new session, a question whose answer was recorded in an earlier session is
  answered from the note without repeating the research; checked in the live test with a
  neutralized example.
- **SC-002**: Zero configured credentials, key material or encrypted INUBIT values end up in a note
  through recording, or in a read result from a hand-written note, across the test suite.
- **SC-003**: No server process can read or write another profile's knowledge (tested with two
  profiles).
- **SC-004**: Every result (instructions, search page, read) stays within its size bound for a
  knowledge of 5,000 notes and a 1 MiB note, and reports where it cut.
- **SC-005**: Every recording is traceable in the audit log, and every overwritten version is
  recoverable by the operator.
- **SC-006**: The tool count grows by at most two.

## Clarifications

### Session 2026-11-09

- Q: Is recording into the local knowledge on or off by default? → A: on by default, switchable
  off per profile (FR-012).
- Q: When does the assistant record a research result? → A: it proposes a note and records it only
  after the user agrees, or when the user asks (FR-017).
- Q: Tools only, or notes additionally as MCP resources? → A: tools only (FR-018); resources are
  out of scope.

## Assumptions

- The knowledge is stored unencrypted, protected by owner-only permissions like the workspace;
  encryption at rest is out of scope (SECURITY.md says so).
- Customer names, hosts and other identifiers are allowed in the knowledge (it is outside the
  repository); only secrets are forbidden.
- Notes are plain Markdown so the operator can keep the directory in a private, synchronized or
  version-controlled folder of their choice; the server does not synchronize or push anything.
- The server does not interpret notes (no automatic linking to INUBIT objects); topics are free
  text that the assistant and the operator choose.
- Feature 018 (Partner Management) does not depend on this feature, and this feature does not
  depend on 018; they can be merged in either order.

## Out of Scope

- Sharing knowledge between profiles or between machines (the operator may sync the directory
  themselves).
- Deleting notes through a tool.
- Offering notes as MCP resources.
- Encryption of the knowledge at rest.
- Knowledge about INUBIT in general (not customer-specific): that belongs in the repository's
  documentation.
- Automatic extraction of knowledge from INUBIT or from chat transcripts.
