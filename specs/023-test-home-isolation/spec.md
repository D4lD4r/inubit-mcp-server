# Feature Specification: Test runs leave the real user home untouched

**Feature Branch**: `023-test-home-isolation`

**Created**: 2026-11-12

**Status**: Approved (spec review round 3)

**Input**: User description: "Tests must not write into the real user home. Today every test run
fills ~/.inubit-mcp/acme/backups (and deployments) with test data because the composition root
reads the user home itself. The home directory must be passed in from outside: the launcher already
knows it and hands it on; tests hand in a temporary directory. No fallback to the process user home
in code that tests reach. Live tests keep reading the real home for configuration and keychain, but
must not leave test artefacts there. Test first: a test that is red while a wiring test writes under
the real ~/.inubit-mcp and green afterwards. Done when the full build is green and after a full test
run no file under ~/.inubit-mcp is newer than a marker created before the run."

## Background

The server keeps its state below the user home H: `H/.inubit-mcp/<profile>/` holds the audit log,
workspace, local knowledge, import backups, the deployment ledger and deployment packages;
`H/.inubit-mcp/update-check.json` holds the cached update check. The configuration and the
credential store also start from H.

Most of these locations are already derived from a home that the caller hands in: the configuration
loader (audit, workspace and knowledge defaults) and the launcher context (credential store,
update-check cache) take it as a parameter, and tests pass a temporary directory. Four places are
not: the composition root asks the process for its user home when it builds

- the import backup directory `H/.inubit-mcp/<profile>/backups`,
- the deployment ledger directory `H/.inubit-mcp/<profile>/deployments`,
- the deployment package directory `H/.inubit-mcp/<profile>/packages`,
- the export folder rules, which refuse any export target below the forbidden root `H/.inubit-mcp`
  (this one only reads; it writes nothing).

Every test that builds the server through the composition root with the test profile `acme`
therefore writes into the developer's real `~/.inubit-mcp/acme/` (more than 470 backup files since
2026-11-07, 40 in one night). A sixth read, an unused factory of the configuration loader, is dead
code.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A test run does not touch my home directory (Priority: P1)

As the developer running the build, I run the full test suite and my real `~/.inubit-mcp` is exactly
as it was before: no new backups, ledgers, packages, audit entries, workspace or knowledge files and
no rewritten update-check cache.

**Why this priority**: This is the defect. Test data piles up in a directory that also holds real
profiles (and is read by the running server), it costs disk space and it mixes test data with real
import backups.

**Independent Test**: Create a marker file, run the full build, then list every file under
`~/.inubit-mcp` that is newer than the marker. The list is empty.

**Acceptance Scenarios**:

1. **Given** a marker file created right before the run and no other inubit-mcp process running,
   **When** the full build (unit and integration tests) runs, **Then** no file or directory under
   `~/.inubit-mcp` is newer than the marker.
2. **Given** a test that builds the server through the composition root with a temporary home T and
   profile P, **When** that test runs a tool that takes an import backup, **Then** the backup is
   written below `T/.inubit-mcp/P/backups` and nothing is written below the process user home.
3. **Given** the same server, **When** an archive export names a target folder below
   `T/.inubit-mcp`, **Then** the export is refused as a forbidden target.

---

### User Story 2 - A regression is caught by the build (Priority: P1)

As the developer, I want the build to fail as soon as production code other than the one allowed
place reads the process user home, so the defect cannot come back unnoticed.

**Why this priority**: The defect went unnoticed for days because nothing checks it. Without a guard
the next new per-profile directory reintroduces it.

**Independent Test**: On the code before the fix the guard test is red and lists the offending
lines; after the fix it is green; adding a read of the process user home to any other production
source file makes it red again.

**Acceptance Scenarios**:

1. **Given** a production source file other than the allowed place contains a read of the process
   user home, **When** the guard test runs, **Then** it fails and names each offending file and line.
2. **Given** the allowed place is the only such read, **When** the guard test runs, **Then** it
   passes.
3. **Given** the text `~/.inubit-mcp` or `user.home` appears only in a comment, a message or a label,
   **When** the guard test runs, **Then** it does not count as a read.
4. **Given** `HOME` is named in an allowlist of environment variables passed on to a child process,
   **When** the guard test runs, **Then** it does not count as a read.

---

### User Story 3 - The real server keeps its locations (Priority: P2)

As a user of the server, nothing changes for me: started normally, the server still keeps its state
under `~/.inubit-mcp` of my real home, in the same directories as before.

**Why this priority**: The fix must not move real data.

**Independent Test**: Build the server for home H and profile P and compare the resolved locations
with the reference list below; check that the launcher's system context hands the process user home
over as H.

**Acceptance Scenarios**:

1. **Given** home H and profile P with no explicit directories configured, **When** the server is
   built, **Then** it resolves exactly: backups `H/.inubit-mcp/P/backups`, deployment ledger
   `H/.inubit-mcp/P/deployments`, deployment packages `H/.inubit-mcp/P/packages`, forbidden export
   root `H/.inubit-mcp`, audit `H/.inubit-mcp/P/audit`, workspace `H/.inubit-mcp/P/workspace`,
   knowledge `H/.inubit-mcp/P/knowledge`, update-check cache `H/.inubit-mcp/update-check.json`.
2. **Given** the server is started through its normal entry point, **When** the launcher builds its
   system context, **Then** H is the process user home.

---

### User Story 4 - Live tests read but do not write my home (Priority: P3)

As the developer running the opt-in live tests, they still find my real configuration and my
credential store under my real home, but they leave no test artefacts there.

**Why this priority**: Live tests run rarely and on demand, but they are the one place that must
keep reading the real home.

**Independent Test**: With a marker, run the live test groups against a configured test target;
afterwards no file under `~/.inubit-mcp` is newer than the marker.

**Acceptance Scenarios**:

1. **Given** the live tests are enabled, **When** they build the server, **Then** they load the
   configuration and the credential store from the real home, and every location they write (import
   backups, deployment ledger, deployment packages, audit directory, workspace, knowledge directory)
   lies below a temporary directory.

### Edge Cases

- A test builds the server without saying which home to use: it does not fall back to the process
  user home; the shared test helper supplies a fresh temporary home.
- A test that starts the packaged server as a child process: the child's user home is set to a
  temporary directory (already the case for the existing process tests; this stays so).
- Other inubit-mcp processes running at the same time (test runs of other branches or worktrees on
  older code, or the developer's own running server) can still write into the real home; the
  acceptance check is meaningful only while none is active.
- A test configuration that names a fixed directory such as `/home/test` (not the real home) is out
  of scope as long as it does not resolve below the real `~/.inubit-mcp`.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The composition root MUST derive the import backup, deployment ledger and deployment
  package directories (`<home>/.inubit-mcp/<profile>/backups|deployments|packages`) and the forbidden
  export root `<home>/.inubit-mcp` of the export folder rules from a home directory handed to it by
  its caller.
- **FR-002**: No constructor or factory of the composition root may read the process user home (as
  FR-007 defines a read) or fall back to it.
- **FR-003**: The system context that the launcher builds at process start MUST be the only place in
  the production sources that reads the process user home; it hands that home to the configuration
  loader, the credential store, the update-check cache and the composition root.
- **FR-004**: The configuration loader's unused factory that reads the process user home MUST be
  removed.
- **FR-005**: Every test that builds the server through the composition root MUST hand in a
  temporary home. The shared test helper MUST supply a fresh temporary directory per built server
  when a test does not choose one explicitly; that directory MUST be removed no later than the end of
  the test JVM; removing it when the server closes is allowed but not required.
- **FR-006**: The live tests MAY read the configuration and the credential store from the real home;
  every location they write (import backups, deployment ledger, deployment packages, audit directory,
  workspace, knowledge directory) MUST lie below a temporary directory.
- **FR-007**: A guard test MUST scan the production sources and fail when any file other than the
  allowed place (FR-003) reads the process user home, reporting file and line. A read is a lookup of
  the `user.home` system property, or a lookup of the `HOME` or `USERPROFILE` environment variable
  (directly or in the process environment map), with the name written as a literal or held in a
  constant. Not a read: comments, labels and messages that merely mention these names; naming `HOME`
  or `USERPROFILE` in an allowlist of variables passed on to a child process; handing the whole
  process environment on unread. Test sources are outside the guard.
- **FR-008**: A behavioural test MUST show that an import backup taken through the composition root
  lands below the home that was handed in, and that the forbidden export root follows that home.
- **FR-009**: For home H and profile P the server MUST resolve the locations listed in User Story 3,
  scenario 1, unchanged from the release before this change.

### Key Entities

- **User home**: the directory below which the server keeps `.inubit-mcp/`. Read from the process
  once, in the launcher's system context, and handed to everything else.
- **Per-profile state**: audit log, workspace, local knowledge, import backups, deployment ledger,
  deployment packages of one profile, below `~/.inubit-mcp/<profile>/` unless configured otherwise.
- **Update-check cache**: `~/.inubit-mcp/update-check.json`, shared by all profiles.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After one full build (unit and integration tests), started right after creating a
  marker file and with no other inubit-mcp process active, the number of files and directories under
  `~/.inubit-mcp` newer than the marker is 0 (today: about 40 per run).
- **SC-002**: The full build is green.
- **SC-003**: The number of reads of the process user home in the production sources, as FR-007
  counts them, is exactly 1 (today: 6).
- **SC-004**: The guard test is observed red on the code before the fix and green after it.
- **SC-005**: For home H and profile P, all locations of User Story 3, scenario 1 resolve to the
  listed paths (0 moved locations).

## Out of Scope

- Child processes (git, StartCLI, credential helpers) that inherit `HOME` from the server process.
- Reads of `~/.config/inubit-mcp` by test support code (identifier lists for the leak checks).
- The test configuration directory below `java.io.tmpdir` used by the test helper.
- Code on the unmerged 021 and 022 branches (e.g. the server-log export, which reuses the export
  folder rules); whichever branch merges after this one adapts its new call sites.

## Assumptions

- The launcher already receives the user home in its context and passes it to the configuration
  loader, the credential store and the update-check cache; only the composition root is missing it.
- The process tests that start the packaged server already set the child's user home to a temporary
  directory.
- The opt-in live tests are not part of the default build; they are checked by reading their code
  and, where a test target is available, by one live run.
- This is a defect fix without user-visible behaviour change; a short "Fixed" line in the changelog
  is enough.

## Follow-up after merge

- Move the directory `~/.inubit-mcp/acme` as a whole to the trash (not delete it). No configuration
  profile named `acme` exists on the developer machine (checked 2026-11-12: only the real customer profile exists).
