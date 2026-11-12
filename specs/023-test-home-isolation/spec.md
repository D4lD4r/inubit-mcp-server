# Feature Specification: Test runs leave the real user home untouched

**Feature Branch**: `023-test-home-isolation`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "Tests must not write into the real user home. Today every test run
fills ~/.inubit-mcp/acme/backups (and deployments) with test data because the composition root
reads the user home itself. The home directory must be passed in from outside: the launcher already
knows it and hands it on; tests hand in a temporary directory. No fallback to the process user home
in code that tests reach. Live tests keep reading the real home for configuration and keychain, but
must not leave test artefacts there. Test first: a test that is red while a wiring test writes under
the real ~/.inubit-mcp and green afterwards. Done when the full build is green and after a full test
run no file under ~/.inubit-mcp is newer than a marker created before the run."

## Background

The server keeps its per-profile state below the user home: `~/.inubit-mcp/<profile>/` holds the
audit log, workspace, local knowledge, import backups, the deployment ledger and deployment
packages; `~/.inubit-mcp/update-check.json` holds the cached update check. The configuration and the
credential store also start from the user home.

Most of these locations are already derived from a home directory that the caller hands in (the
configuration loader and the launcher context take it as a parameter, and tests pass a temporary
directory). Four locations are not: the composition root asks the process for its user home when it
builds the folder rules of the archive and server-log export, the import backup store, the
deployment ledger and the deployment package directory. Every test that builds the server through
the composition root with the test profile `acme` therefore writes into the developer's real
`~/.inubit-mcp/acme/` (more than 470 backup files since 2026-11-07, 40 in one night).

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

1. **Given** a marker file created right before the run, **When** the full build (unit and
   integration tests) runs, **Then** no file or directory under `~/.inubit-mcp` is newer than the
   marker.
2. **Given** a test that builds the server through the composition root with a temporary home,
   **When** that test exercises a tool that writes per-profile state (import backup, deployment
   ledger, deployment package, archive export folder rules), **Then** the state is written below the
   temporary home and nowhere else.

---

### User Story 2 - A regression is caught by the build (Priority: P1)

As the developer, I want the build to fail as soon as any code path that tests reach derives a
location from the process user home instead of the home it was given, so the defect cannot come back
unnoticed.

**Why this priority**: The defect went unnoticed for days because nothing checks it. Without a guard
the next new per-profile directory reintroduces it.

**Independent Test**: On the code before the fix the guard test is red; after the fix it is green;
re-introducing a read of the process user home in the composition root makes it red again.

**Acceptance Scenarios**:

1. **Given** the composition root reads the process user home for any of its locations, **When** the
   guard test runs, **Then** it fails and names the offending location.
2. **Given** the composition root uses only the home it was handed, **When** the guard test runs,
   **Then** it passes.
3. **Given** production code other than the single process entry point reads the process user home,
   **When** the guard runs, **Then** it fails and names the source file and line.

---

### User Story 3 - The real server keeps its locations (Priority: P2)

As a user of the server, nothing changes for me: started normally, the server still keeps its
backups, ledger, packages, audit log, workspace, knowledge and update-check cache under
`~/.inubit-mcp` of my real home.

**Why this priority**: The fix must not move real data.

**Independent Test**: Start the packaged server as a separate process with an explicit home and
check that its per-profile state appears below that home in the same layout as before.

**Acceptance Scenarios**:

1. **Given** the server is started by its normal entry point, **When** it builds its per-profile
   locations, **Then** they resolve below the process user home exactly as before this change.

---

### User Story 4 - Live tests read but do not write my home (Priority: P3)

As the developer running the opt-in live tests, they still find my real configuration and my
credential store under my real home, but they leave no test artefacts there.

**Why this priority**: Live tests run rarely and on demand, but they are the one place that must
keep reading the real home.

**Independent Test**: With a marker, run the live test groups against a configured test target;
afterwards no file under `~/.inubit-mcp` is newer than the marker (reading the configuration and
the credential store does not change files).

**Acceptance Scenarios**:

1. **Given** the live tests are enabled, **When** they build the server, **Then** they load the
   configuration from the real home but hand a temporary home to the composition root for every
   location that is written.

### Edge Cases

- A test builds the server without saying which home to use: it must not silently fall back to the
  process user home; either the test helper supplies a fresh temporary home or the call does not
  compile.
- A test that starts the packaged server as a child process: the child's user home is set to a
  temporary directory (already the case for the existing process tests; this stays so).
- Other sessions or worktrees running older code at the same time can still write into the real
  home; the acceptance check is meaningful only when no other test run is active.
- A path in a test configuration that names a fixed directory such as `/home/test` (not the real
  home) is out of scope as long as it does not resolve below the real `~/.inubit-mcp`.
- The existing test data in `~/.inubit-mcp/acme` is removed once, after the fix, by moving it to the
  trash (not deleting it).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The composition root MUST derive every per-profile location below the user home
  (archive and server-log export folder rules, import backups, deployment ledger, deployment
  packages) from a home directory handed to it by its caller.
- **FR-002**: The composition root MUST NOT read the process user home, and no construction path of
  it MUST fall back to the process user home.
- **FR-003**: The process entry point MUST be the only production code that reads the process user
  home; it hands that home on to the configuration loader, the credential store, the update-check
  cache and the composition root.
- **FR-004**: The configuration loader's unused factory that reads the process user home MUST be
  removed.
- **FR-005**: Every test that builds the server through the composition root MUST hand in a
  temporary home; the shared test helper MUST supply one when a test does not choose it explicitly.
- **FR-006**: The live tests MAY read the real home for the configuration and the credential store,
  but MUST hand a temporary home to the composition root.
- **FR-007**: A guard test MUST fail while any production code path that tests reach (anything other
  than the process entry point) reads the process user home, and MUST name the offending location.
- **FR-008**: A behavioural test MUST show that per-profile state written through the composition
  root (at least an import backup) lands below the home that was handed in.
- **FR-009**: Started through its normal entry point, the server MUST keep every per-profile location
  exactly where it was before this change.
- **FR-010**: After the fix is merged, the test data accumulated in `~/.inubit-mcp/acme` MUST be moved
  to the trash, not deleted.

### Key Entities

- **User home**: the directory below which the server keeps `.inubit-mcp/`. Read from the process
  once at the entry point, handed to everything else.
- **Per-profile state**: audit log, workspace, local knowledge, import backups, deployment ledger,
  deployment packages of one profile, below `~/.inubit-mcp/<profile>/`.
- **Update-check cache**: `~/.inubit-mcp/update-check.json`, shared by all profiles.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After one full build (unit and integration tests) started right after creating a
  marker file, the number of files and directories under `~/.inubit-mcp` newer than the marker is 0
  (today: about 40 per run).
- **SC-002**: The full build is green.
- **SC-003**: The number of places in production code that read the process user home is exactly 1
  (today: 6).
- **SC-004**: The guard test is observed red on the code before the fix and green after it.
- **SC-005**: A server started normally writes its per-profile state to the same directories as the
  release before this change (0 moved locations).

## Assumptions

- The launcher already receives the user home in its context and passes it to the configuration
  loader, the credential store and the update-check cache; only the composition root is missing it.
- The process tests that start the packaged server already set the child's user home to a temporary
  directory.
- The opt-in live tests are not part of the default build; they are checked by reading their code
  and, where a test target is available, by one live run.
- The 021 and 022 feature branches are not merged yet; whichever merges later adapts its new wiring
  call sites to the new constructor parameter.
- This is a defect fix without user-visible behaviour change, so no release note entry beyond a
  short "Fixed" line is needed.
