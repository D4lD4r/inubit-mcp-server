# Feature Specification: INUBIT 7.4 Support, CLI Only, Cut into Two Mandates

**Feature Branch**: `claude/inubit-74-support-0b6255`

**Created**: 2026-11-18

**Status**: Draft

**Input**: Jira intent IMC-6 "Support INUBIT 7.4 servers" (status Specifying, owner Daniel
Decker): outcome, success criteria 1–5, constraints, non-goals, the proposed cut into Mandate A
and Mandate B, and the prerequisites; plus the attachment `startcli.sh.help.txt` (StartCLI `-h` of
a 7.4 node). Comments on the card: Windows is in scope for recording and for serving 7.4 nodes;
Mandate A needs no 7.4 node; Mandate B stays in Draft until a Windows machine with a 7.4 client and
a non-production 7.4.0.90 node are available.

## Context & Findings *(verified before writing this spec)*

Sources: the IMC-6 description and comments, the attached help output, the code on `main` at
0.10.2 (`9447c5c3`), and the precedents `specs/012-inubit-61-cli-reads/` (CLI-only range),
`specs/009-inubit-version-detection/`, `specs/013-windows-startcli/` and
`specs/017-inubit-9-support/`.

- **No vendor reference exists for 7.4.** All `docs.virtimo.net` 7.4 pages return 404; only 8.0,
  8.1 and 9.0 are online. The bundle the owner holds contains only the plugin Javadoc and JSDoc
  (ibis 7.4.0.90 API), not the REST or CLI reference. Every interface fact beyond the attached help
  output must therefore come from recordings of a real 7.4 node, as for 6.1 (feature 012), or,
  since the owner decision of 2026-11-26, is assumed from 6.1 and offered as unverified.
- **The 7.4 REST API is not the 8.1 one.** In 8.0 the REST API was reworked to standard endpoints
  answering JSON, and `/ready` is new in 8.0.1. The 8.1 REST adapters cannot be assumed to work
  on 7.4. REST on 7.4 is out of scope.
- **The attached 7.4 help output** (StartCLI `-h`, from a Linux `startcli.sh`) shows:
  - the direct options of 6.1, partly renamed: `-c,--check` (alive), `-sv` (server version),
    `-ne`, `-nx`, `-nw`, `-nq` (process counts per state), `--pctmem` (used JVM memory in percent)
    and `--maintenance <on|off>` instead of 6.1's `-pctmem` / `-maintenance`, plus the new
    `--maintenanceState`, `--who` / `--nwho`, `-q`, `-x`, `-l <rows>`, `-t <timeout>`, `-v`;
  - the script mode `--execCommand <command>` known from 8.x (with `--execMode`), and the
    interactive mode ("startcli -u <user> -p <password> [Url]", then `<command> <options> <args>`);
  - the batch commands `tag`, `rename`, `delete`, `export`, `import`, `backup`, `deploy`,
    `restore` and `process`;
  - no trust-store or host-name-verification option of the client;
  - the default URL `http://localhost:8000/ibis/servlet/IBISSoapServlet`.
  It does not list the interactive commands (for example `ps`, which `find_processes` uses on
  6.1), and it shows no output format. The version of the node it came from is not part of the
  text.
- **Today a 7.4 node is mishandled or unreachable.** `versionLine` accepts `AUTO`, `V6_1`, `V8_1`,
  `V9_0`, `V9_X` or a concrete version; a concrete 7.4 pin (`"7.4.0.90"`) is accepted but falls
  into "known version outside every declared range": reads run with the 8.1 adapters and an
  "unverified" warning, writes are refused. On `AUTO`, a detection answered with HTTP 404 adds the
  hint "if <node> runs INUBIT 6.1, pin `V6_1`" (feature 012 FR-003), which is wrong for a 7.4 node.
- **The fourth version part is dropped.** `InubitVersion` reads only `major.minor[.patch]`; for
  `7.4.0.90` the patch is `0` and `90` survives only in the raw text, so two 7.4.0.x builds compare
  as equal in the client/server patch comparison. Feature 012 FR-002 decided that the fourth part
  of a 6.1 version is ignored for matching and for the patch warning.
- **The 6.1 range is the model.** The production capability matrix declares 6.1 CLI-only with
  every capability `unsupported`, because the 6.1 recordings were never made; the 6.1 fixtures are
  all reconstructed and listed in `fixtures/v6_1/cli/RECONSTRUCTED.txt`, and the guard
  `V61RecordedFixturesTest` forbids declaring a capability supported while one of its cases is
  reconstructed.
- **Windows.** Feature 013 runs StartCLI on Windows through `startcli.bat` (`cmd.exe`, environment
  allowlist, password only through standard input, CRLF and code page handling) and records on
  Windows with `tools/record-fixtures.ps1`. That script knows local runs (`-v`, `-h`), export and
  failure cases, but no interactive StartCLI session. Whether the 7.4 client behaves like the 8.1
  and 6.1 clients on Windows is not verified.
- **Clients.** `defaults.cliClients` maps a quoted `"<major>.<minor>"` to a client directory and
  its JDK (feature 017); a `"7.4"` key is already valid syntax. The 7.4 client probably needs
  Java 11 or older (8.0 ships JDK 11 and the 7.4 Javadoc was built with JDK 11.0.18); not
  verified.
- **Constitution V** names 8.1.x as the baseline, 9.x as the next version and INUBIT 6.1.x as
  supported read-only, CLI-only, because its REST interface does not offer what the server needs.
  Declaring 7.4 `supported` for any capability, and serving it through StartCLI only, needs an
  amendment that extends the same rule to 7.4, whose REST interface is unverified rather than
  missing. Mandate A declares no 7.4 capability supported: its CLI-only matrix entry uses no
  channel at all (every capability is refused before contact), so it does not invoke the
  exception and does not depend on the amendment; Mandate B does. Constitution 2.0.0 (ratified
  2026-11-23) names 7.4 read-only and CLI-only; 2.1.0 (ratified by the owner 2026-11-26, IMC-25;
  Principles III and V) adds the evidence level *unverified*: a read capability of a non-baseline
  version runs on contract tests against reconstructed fixtures, built from the vendor
  documentation of that version or from recordings, vendor documentation or reconstructed fixtures
  of another version, and checked against real output of that version where any exists in the
  repository, when every result carries a user-visible "unverified" warning and the documentation
  marks it untested. Mandate B (replanned 2026-11-26) rests on that level.
- **Provenance of the fixtures Mandate B rests on (stated openly, 2026-11-26).** No 6.1 or 7.4 node
  has ever been recorded. The 6.1 fixtures are reconstructed from the vendor documentation of inubit
  6.1 (`fixtures/v6_1/cli/README.md`); every 6.1 case, its `help` included, is listed in the 6.1
  `RECONSTRUCTED.txt`; only the login-failure markers come from 8.1 recordings. The 7.4 fixtures
  are built from these 6.1 cases with server version 7.4.0.90 (`fixtures/v7_4/cli/RECONSTRUCTED.txt`).
  The only real output of either version in the repository is the 7.4 `help` (the IMC-6
  attachment), which the 7.4 tests check against (no trust-store or host-name option,
  `--execCommand` present). Unverified on 7.4 therefore rests on 6.1 documentation, carried over,
  plus one real 7.4 help output; unverified on 6.1 rests on 6.1 documentation alone (no real 6.1
  output exists). The owner accepted this basis on 2026-11-26 (Clarifications, second round).
- **Unverified on a CLI-only version (code check, 2026-11-26).** `SupportState.UNVERIFIED` exists
  and is used today only on non-CLI-only versions (partner management on 9.0, reads of unknown
  versions), where it runs the 8.1 adapters with the warning "INUBIT <version> on <node> is not
  verified for <capability>; results may be incomplete" (`VersionGate.judge`). On a CLI-only
  version the code refuses every state other than `SUPPORTED`: `VersionGate.judge` refuses before
  the warning branch, `VersionGate.monitoringAccess` refuses `get_health` unless `MONITORING` is
  `SUPPORTED`, and `NodeOverview` counts `UNVERIFIED` as usable only on a version that is not
  CLI-only. The 6.1 StartCLI adapters exist for health (`V61HealthAdapter`, `V61HealthParser`)
  and processes (`V61ProcessQueryAdapter`, `V61PsParser`, `V61QueryRules`); 6.1 has no other
  StartCLI adapter. `V61HealthAdapter` treats the version `uname` reports as the client's, not the
  server's (`UNAME_REPORTS_SERVER_VERSION = false`, until a recording confirms it), and lists
  readiness, threads and blocking queue as `NOT_AVAILABLE_ON_6_1`. `V61ProcessQueryAdapter` reads
  the time in state from the `DATE` column and applies hanging and time range to it; `tag` is
  refused. The reconstructed 7.4 fixtures use exactly the 6.1 session formats with server version
  7.4.0.90. Several Mandate A rules key on "every 7.4 capability carries a note"
  (`CapabilityMatrix.allNotYetVerified`, `lines()`); they switch at the first 7.4 capability
  without a note (FR-035).

## Clarifications

### Session 2026-11-18

- Q: Does an unidentified 7.4 node on `AUTO` stay an unknown version as today? → A: Yes, accepted
  by the owner; IMC-6 criterion 3 applies to nodes known as 7.4 (Open Questions, Decided).
- Q: Do derived edge-case fixtures count as verified? → A: Yes, accepted by the owner, when
  derived from a real recording by a documented minimal edit (FR-016a; Open Questions, Decided).

### Session 2026-11-26 (Mandate B replanned)

- Q: Will the 7.4 recording session (IMC-22) and the Windows live check take place? → A: No,
  decided by the owner, 2026-11-26. 7.4 is offered as **untested**.
- Q: Which mechanism serves 7.4 without recordings? → A: The existing `SupportState.UNVERIFIED`,
  decided by the owner, 2026-11-26: on a 7.4 node the read capabilities in scope run and every
  result carries the existing warning "INUBIT <version> on <node> is not verified for
  <capability>; results may be incomplete". No new support state.
- Q: Which capabilities? → A: "Everything 6.1 can", decided by the owner, 2026-11-26: in code 6.1
  has StartCLI adapters for health and processes only, so MONITORING (`get_health`) and
  PROCESS_DIAGNOSIS (`find_processes`), through StartCLI only, reusing the 6.1 adapters and parsers
  with a 7.4 profile. Every other tool keeps refusing 7.4 as in Mandate A. 7.4 stays read-only.
- Q: Is `AUTO` detection of 7.4 still in scope? → A: No, decided by the owner, 2026-11-26: only
  nodes pinned with `versionLine: V7_4` or a concrete 7.4 version are served; `AUTO` never selects
  7.4 and the 404 hint of FR-005 stays. FR-032, FR-033, US8 and SC-008 are withdrawn.
- Q: What happens to the recording kit? → A: It stays in the repository as an optional later
  verification path, no longer a precondition, decided by the owner, 2026-11-26. Raising 7.4 from
  unverified to supported is a future, separate mandate (recordings or evaluated recordings plus a
  live check, constitution 2.0.0 / 2.1.0).
- Q: How is the documentation worded? → A: README, `docs/setup.md`, `docs/tools.md` and the
  changelog mark `get_health` and `find_processes` on 7.4 as **untested** (unverified), decided by
  the owner, 2026-11-26.

### Session 2026-11-26, second round (spec review spec-replan-r1)

- Q: May 7.4 "unverified" rest on fixtures derived from the 6.1 fixtures, which come from the 6.1
  vendor documentation (review blocker)? → A: Yes, decided by the owner, 2026-11-26. Constitution
  2.1.0 was worded accordingly and ratified the same day (IMC-25); the provenance is stated in
  Context & Findings and Preconditions. The owner accepts the attached help output as the 7.4 help.
- Q: Does 6.1 stay `unsupported` while 7.4 becomes `unverified` on weaker evidence? → A: No,
  decided by the owner, 2026-11-26: 6.1 `get_health` and `find_processes` also become
  `UNVERIFIED` on the same basis (US9, FR-036, FR-037; FR-020 amended).
- Executor defaults (2026-11-26, the owner may revise): every per-node entry after the version gate
  carries the warning, data or error; refusals before contact need not (FR-030 (b)); failure, TLS
  and mixed-group scenarios added to US6 and US7; German variants are built in the tests from the
  7.4 fixtures (FR-025); `list_nodes` after the flip shows what the code already shows for usable
  unverified reads (`partially_supported`, no suffix; US6 scenario 13); an unreadable `-v` is
  fail-closed (FR-029); the `version` case is in the FR-014 table and covered by the guard.

Other decisions taken as informed defaults are listed under Assumptions; the points still open for
the owner are listed under Open Questions.

## Mandates

The feature is delivered in two mandates. Mandate A needs no 7.4 node and can be implemented and
merged on its own. Mandate B was first planned on recordings of a real 7.4 node and a live check;
replanned on 2026-11-26 (owner decision, Clarifications), it needs no 7.4 node either and serves
7.4 as **unverified (untested)** on the evidence of the reconstructed fixtures (constitution 2.1.0,
Preconditions).

| | Mandate A (no 7.4 node needed) | Mandate B (no 7.4 node; replanned 2026-11-26) |
|---|---|---|
| User stories | US1, US2, US3, US4, US5 | US6, US7, US9 (US8 withdrawn) |
| Requirements | FR-001 – FR-020 (incl. FR-010a, FR-016a) | FR-024 – FR-031, FR-034 – FR-037 (FR-021 – FR-023, FR-032, FR-033 withdrawn); amends FR-020 for 6.1 |
| IMC-6 criteria | 1 (pin), 3, 4, 5 | 2, met as unverified (untested); 1 (detection) withdrawn |
| Result | 7.4 is known, pinned, shown, and refused clearly on every platform; the recording kit for Windows is ready | `get_health` and `find_processes` run on pinned 7.4 and 6.1 nodes through StartCLI, unverified, with the warning in every per-node entry after the version gate; verified by the offline suite on the macOS/Linux and Windows CI jobs, no live check |

Planning depth: plan.md and tasks.md design Mandate A in full and, since 2026-11-26, Mandate B in
full as well: the 6.1 StartCLI adapters with a 7.4 profile, the unverified state on a CLI-only
version, client selection and the switches of FR-035. No step waits for a recording. The recording
kit (US4) stays an optional later verification path for a future mandate that may raise 7.4 to
supported.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A pinned 7.4 node is known and every tool refuses clearly (Priority: P1, Mandate A)

A person whose profile contains INUBIT 7.4 nodes pins them to 7.4. The server shows them as 7.4
nodes, reached through StartCLI only, and refuses every tool for them before anything is sent,
naming the node, its version and the missing capability — on Windows, macOS and Linux alike.

**Why this priority**: Today a pinned 7.4 node runs reads with the 8.1 adapters, whose request
shapes are unverified on 7.4 (IMC-6 criterion 3). The clear refusal is what makes 7.4 safe to
configure before anything is verified.

**Independent Test**: With a profile containing a node pinned to `V7_4` and one pinned to
`"7.4.0.90"`, run `--check-config`, `list_nodes` and every tool against them, offline, on each
platform's test job, and confirm the refusals and that no REST request, StartCLI run against the
node or TLS handshake reaches the node (the local client probe of FR-010a excepted).

**Acceptance Scenarios**:

1. **Given** a node with `versionLine: V7_4`, **When** the configuration is loaded and checked,
   **Then** it is accepted, and `--check-config` and `list_nodes` show version line 7.4, the channel
   "StartCLI only" and no usable capability.
2. **Given** a node pinned to `"7.4.0.90"`, **When** `list_nodes` lists it, **Then** it shows the
   version `7.4.0.90` (all four parts) and the same channel and capabilities as for `V7_4`.
3. **Given** a 7.4 node, **When** any tool that contacts a node targets it (`get_health`,
   `find_processes`, `query_logs`, inventory, export, any write, any node tool), **Then** it is
   refused with `UNSUPPORTED_VERSION` before any contact, naming the node, version 7.4, the
   capability and the next step of FR-007. For writes, the order of the write checks and the
   version check is the one that applies to 6.1 nodes today, unchanged.
4. **Given** a 7.4 node whose StartCLI client is missing or not configured, **When** a tool targets
   it, **Then** the refusal is the same `UNSUPPORTED_VERSION` (the version refusal is decided first,
   without looking for the client).
5. **Given** a group with an 8.1 node and a 7.4 node, **When** `get_health` or `find_processes`
   targets the group, **Then** the 8.1 node is answered normally and the 7.4 node carries its
   refusal in its own result entry.
6. **Given** a profile whose only nodes that could run a tool are pinned to 7.4, **When** the
   server starts, **Then** that tool is not announced (tool list rule of feature 009).
7. **Given** a 7.4 node, **When** `check_artifacts` runs with module lookup, **Then** the offline
   checks run and the result carries the notice that the lookup was skipped; no contact.
8. **Given** the same profile on Windows, macOS and Linux, **When** scenarios 1–7 run, **Then** the
   results are identical on each platform.
9. **Given** a `defaults.cliClients."7.4"` entry, **When** `--check-config` and `list_nodes` run,
   **Then** the result of the client's local version probe (including "not evaluable") and the
   client directory are shown with the note that no tool uses it until a 7.4 capability is
   verified, no JDK warning is given, and the node is not contacted.

*Mandate B (2026-11-26)*: once MONITORING and PROCESS_DIAGNOSIS are `UNVERIFIED` on 7.4 (FR-030),
scenarios 3, 5 and 6 apply to every tool except `get_health` and `find_processes` (US6, US7), and
scenarios 1, 2 and 9 change as FR-010, FR-010a and FR-035 say; every other tool keeps exactly the
Mandate A refusal.

---

### User Story 2 - A failed detection points to 7.4 as well as 6.1 (Priority: P2, Mandate A)

A person adds a 7.4 node with `AUTO`. The REST detection fails with HTTP 404. The warning tells the
person that the node may run INUBIT 6.1 or 7.4 and which pin to set for each, instead of suggesting
only the 6.1 pin as today.

**Why this priority**: IMC-6 criterion 1 asks that a failed detection points to 7.4; without a
7.4 node the server cannot tell 6.1 from 7.4 on a 404, so the hint names both. Since the
withdrawal of detection (US8, 2026-11-26) the hint stays in Mandate B as well.

**Independent Test**: With a stubbed node that answers the detection request with HTTP 404, run a
read and confirm the warning text; with any other detection failure, confirm the warning is
unchanged.

**Acceptance Scenarios**:

1. **Given** a node on `AUTO` whose detection request is answered with HTTP 404, **When** a read
   runs, **Then** the "unknown version" warning adds "if <node id> runs INUBIT 6.1, pin `V6_1`; if
   it runs INUBIT 7.4, pin `V7_4`".
2. **Given** any other detection failure (timeout, TLS error, 401, 500, unparsable answer),
   **When** a read runs, **Then** the warning is exactly as today.

---

### User Story 3 - The patch level of a four-part 7.4 version is kept (Priority: P2, Mandate A)

A 7.4 node runs 7.4.0.90 and the configured 7.4 client is 7.4.0.80. The server keeps the fourth
part and compares it, so the client/server patch comparison reports a mismatch instead of treating
both as 7.4.0.

**Why this priority**: IMC-6 criterion 4; 7.4 builds differ only in the fourth part, so dropping it
hides every client/server patch difference on 7.4.

**Independent Test**: Unit tests of the version model and of the client/server patch comparison
with 7.4, 6.1, 8.1 and 9.0 values.

**Acceptance Scenarios**:

1. **Given** the value `"7.4.0.90"` (configured, reported by the server or by the client),
   **When** it is read, **Then** major 7, minor 4, patch 0 and fourth part 90 are kept, and the
   value is shown as `7.4.0.90`.
2. **Given** a 7.4.0.90 server and a 7.4.0.80 client, **When** the patch levels are compared,
   **Then** they differ (the same outcome as an 8.1.16 client at an 8.1.17 server today).
3. **Given** a 7.4.0.90 server and a 7.4.0.90 client, **When** the patch levels are compared,
   **Then** they match.
4. **Given** a 7.4 value with three parts (`"7.4.0"`) on one side and `"7.4.0.90"` on the other,
   **When** the patch levels are compared, **Then** the result is "not comparable", never a match;
   **given** `"7.4.0"` and `"7.4.1.5"`, **then** it is a mismatch (the patch differs); **given**
   `"7.4.0"` on both sides, **then** it is a match.
5. **Given** a 6.1 value with four parts (`"6.1.0.89"`), **When** it is compared, **Then** the
   fourth part is ignored as today (feature 012 FR-002).
6. **Given** `"7.4.0.90-HF1"`, **When** it is read, **Then** the suffix is kept for display only,
   as for the other versions.

---

### User Story 4 - The recording kit for 7.4 is ready on Windows (Priority: P2, Mandate A)

Before anyone sits at a Windows machine with a 7.4 client, the repository holds everything the
recording needs: the 7.4 case catalogue in the PowerShell recorder, including an interactive
StartCLI session, the fixture folder for 7.4 with the one real case (the attached help output) and
every other case reconstructed and listed, and a guard that keeps reconstructed cases from ever
backing a supported capability.

**Why this priority**: Mandate B as first planned depended on one well-prepared recording session
on a machine the owner does not have; everything that can be prepared without the node must be
ready and tested. Since 2026-11-26 (owner decision) the kit is no longer a precondition of
Mandate B; it stays as the optional path of a future mandate that may raise 7.4 to supported.

**Independent Test**: Run the recorder's case listing and its offline self-tests (no server);
run the fixture shape test, the guard test and the identifier scan.

**Acceptance Scenarios**:

1. **Given** the PowerShell recorder, **When** its case catalogue is listed, **Then** it contains
   the 7.4 cases of FR-016, each with a one-line description, and none of them sends a REST request.
2. **Given** a 7.4 session case, **When** it runs, **Then** it logs in once through the interactive
   StartCLI mode with the password on standard input only, sends the case's commands, ends the
   session and stores standard output, standard error and the exit code; the password appears in no
   stored output (the recorder fails the case if it does).
3. **Given** the cases `v74_exec_help` and `v74_exec_ps_help`, **When** they run, **Then** they use
   the script mode (`--execCommand "help"` and `--execCommand "ps -h"`) and store the list of
   interactive commands and the `ps` options of the 7.4 client.
4. **Given** the fixture folder for 7.4, **When** the shape test runs, **Then** every case listed
   has its three files, the help case is not listed as reconstructed, and every other case is.
5. **Given** a production capability matrix that declares a 7.4 capability `supported` while one of
   that capability's cases is listed as reconstructed, **When** the guard test runs, **Then** it
   fails, naming the capability and the case.
6. **Given** the 7.4 fixtures, **When** the identifier scan runs, **Then** they pass it.
7. **Given** a recording target whose group is not on the recorder's group allowlist, or whose host
   is not on its allowlist of non-production hosts, **When** a 7.4 case that contacts a server is
   started, **Then** the recorder stops before any launch.
8. **Given** a 7.4 case with a trust store or a request to skip host-name verification, **When** it
   is prepared, **Then** the trust store reaches the client only through its Java runtime settings,
   and the request to skip host-name verification stops the recording.

---

### User Story 5 - 6.1, 8.1 and 9.0 behave exactly as before (Priority: P1, Mandate A)

Profiles with 6.1, 8.1 and 9.0 nodes behave exactly as before on every platform; the only visible
changes are the extended 404 hint of US2 and the lists of allowed `versionLine` values that now
name `V7_4`.

**Why this priority**: IMC-6 criterion 5; the change touches the shared version gate and the
detection path every version uses (risk class medium).

**Independent Test**: The full offline test suite on the macOS/Linux and the Windows test jobs,
plus dedicated regression tests per version for parsing, matching, gating and the client check.

**Acceptance Scenarios**:

1. **Given** the offline test suite before this feature, **When** it runs after Mandate A, **Then**
   it passes unchanged on every platform, apart from the tests of the deliberate changes of US2 and
   of the lists of allowed `versionLine` values.

*Mandate B (2026-11-26)*: US9 changes 6.1 for `get_health` and `find_processes` only (FR-020,
FR-036); scenario 3 then holds for every other 6.1 capability.
2. **Given** an 8.1.17 server with an 8.1.16 client, or a 9.0.10 server with a 9.0.10 client,
   **When** the patch levels are compared, **Then** the outcome is as before.
3. **Given** a node pinned to `V6_1`, `V8_1`, `V9_0` or `V9_X`, **When** any tool runs, **Then**
   the gate decision, channel and refusal texts are as before.

---

### User Story 6 - Check the health of a pinned 7.4 node, unverified (Priority: P1, Mandate B)

A person operating a 7.4 installation pins its nodes to 7.4 and asks "Is the 7.4 server up?". The
server answers from the 7.4 StartCLI client alone, with the 6.1 health session: reachable and
logged in, alive, maintenance state, JVM memory and process counts per state, says which parts of
a health report do not exist on 7.4, and warns in every result that INUBIT 7.4 is not verified for
monitoring — both when the MCP server runs on Windows and on macOS or Linux.

**Why this priority**: IMC-6 criterion 2; the health check is the entry point of every diagnosis.
Without recordings (owner decision 2026-11-26) it is offered as untested.

**Independent Test**: Contract tests of the 6.1 health adapter with the 7.4 profile against the
reconstructed 7.4 fixtures (and the real 7.4 `help` case), run by the offline suite on the
macOS/Linux and the Windows CI jobs. No live check.

**Acceptance Scenarios**:

1. **Given** a node pinned to 7.4 (`V7_4` or `"7.4.0.90"`) with a 7.4 client configured in
   `defaults.cliClients."7.4"`, **When** `get_health` targets it, **Then** it runs through
   StartCLI, the report shows the parts of FR-026, lists the others as unavailable with the reason
   `NOT_AVAILABLE_ON_7_4`, carries the warning `INUBIT 7.4 on <node> is not verified for
   monitoring; results may be incomplete` (`INUBIT 7.4.0.90 on …` for the concrete pin), and no
   REST request is sent.
2. **Given** the alive check answers negatively, **When** `get_health` runs, **Then** `status` is
   `ERROR`, the other parts that could be read are still reported, and the warning is present.
3. **Given** a 7.4 node without a 7.4 client (no node or group `cli.home`, no
   `defaults.cliClients."7.4"`), **When** `get_health` targets it, **Then** it is reported with
   `CLI_UNAVAILABLE` and a next step naming `defaults.cliClients."7.4"` (FR-029).
4. **Given** the session reports a version through `uname`, **When** `get_health` runs, **Then**
   that version is reported as the StartCLI client's ("the version … read on <node> is the
   StartCLI client's; the server version cannot be read"), as on 6.1, and is not observed as the
   server's version (FR-027).
5. **Given** the MCP server runs on Windows, **When** scenarios 1–4 run in the offline suite of the
   Windows CI job, **Then** they behave as on macOS or Linux (feature 013 invocation).
6. **Given** a 7.4 node on `AUTO`, **When** `get_health` targets it, **Then** it is handled as an
   unknown version exactly as in Mandate A (Edge Cases); 7.4 is never selected without a pin.
7. **Given** the node rejects the login, **When** `get_health` runs, **Then** the node's entry
   reports `AUTH_FAILED` and carries the warning.
8. **Given** the node cannot be reached, **When** `get_health` runs, **Then** the node's entry
   reports `UNREACHABLE` (`reachable: false`) and carries the warning.
9. **Given** the session prints output the parsers do not recognize, **When** `get_health` runs,
   **Then** the node's entry reports `UNEXPECTED_RESPONSE`, never an empty or partial report
   presented as complete, and carries the warning.
10. **Given** the session does not finish within the 15-second budget, **When** `get_health` runs,
    **Then** the node's entry reports `TIMEOUT` and carries the warning.
11. **Given** a group with an 8.1 node and a pinned 7.4 node, **When** `get_health` targets the
    group, **Then** both are reported within the overview budget of feature 001, the 8.1 node over
    REST and the 7.4 node through StartCLI, and only the 7.4 entry carries the warning.
12. **Given** an `https://` StartCLI URL whose certificate the server's own handshake rejects, or a
    request to skip host-name verification, **When** `get_health` targets the 7.4 node, **Then** it
    reports `TLS_ERROR` and no StartCLI process is started (FR-029).
13. **Given** the flip of FR-030, **When** `list_nodes` lists a pinned 7.4 node, **Then** it shows
    `status: partially_supported`, `monitoring` and `process_diagnosis` among the usable
    capabilities (as `list_nodes` already reports any version with usable unverified reads, e.g.
    partner management on 9.0; no "(unverified)" suffix), every other capability as not usable with its
    Mandate A text, and no warning about the client.

---

### User Story 7 - Find failed and hanging processes on a pinned 7.4 node, unverified (Priority: P1, Mandate B)

The person asks "Which processes are in error on the 7.4 server?". The server lists process
instances of the pinned 7.4 node by state from the 7.4 StartCLI client with the 6.1 process
session, with the fields 6.1 provides, marks hanging ones as on 6.1, and warns in every result
that INUBIT 7.4 is not verified for process diagnosis.

**Why this priority**: IMC-6 criterion 2; failed processes are the main diagnosis use case.

**Independent Test**: Contract tests of the 6.1 process query adapter with the 7.4 profile against
the reconstructed 7.4 process listings in every state, run by the offline suite on both CI jobs.
No live check.

**Acceptance Scenarios**:

1. **Given** a pinned 7.4 node with processes in Error and Waiting state, **When** `find_processes`
   asks for `ERROR`, **Then** it runs through StartCLI, only Error instances are returned with the
   fields of FR-028, and the result carries the warning `INUBIT 7.4 on <node> is not verified for
   process diagnosis; results may be incomplete`.
2. **Given** a listing that reaches the row cap, **When** `find_processes` returns, **Then** the
   result carries a truncation marker and its total is marked as a lower bound.
3. **Given** a `tag` filter, **When** `find_processes` runs on a 7.4 node, **Then** it is refused
   for that node with `UNSUPPORTED_VERSION`, naming the input (as on 6.1, `V61QueryRules`).
4. **Given** a listing with no instance, **When** `find_processes` returns, **Then** the result is
   empty, not an error, and still carries the warning.
5. **Given** `hangingOnly` or a time range, **When** `find_processes` runs on a 7.4 node, **Then**
   they apply to the `DATE` column as on 6.1 (FR-028).
6. **Given** a group with an 8.1 node and a pinned 7.4 node, **When** `find_processes` targets the
   group, **Then** both are answered, and only the 7.4 node's entry carries the warning.
7. **Given** a `ps` listing the parsers do not recognize, **When** `find_processes` runs, **Then**
   the node's entry reports `UNEXPECTED_RESPONSE`, never an empty result, and carries the warning;
   so do a rejected login (`AUTH_FAILED`), an unreachable node (`UNREACHABLE`) and an exhausted
   budget (`TIMEOUT`).

---

### User Story 8 - A 7.4 node on `AUTO` is detected (Priority: P3, Mandate B) — Withdrawn 2026-11-26

**Withdrawn 2026-11-26** (owner decision, Clarifications): without a 7.4 node no detection step
can be verified; only pinned nodes are served, and `AUTO` never selects 7.4. The 404 hint of US2
stays. The original text is kept for reference only:

A person adds a 7.4 node with `AUTO` and a 7.4 client configured. The server identifies it as 7.4
without a pin and serves it like a pinned 7.4 node (IMC-6 criterion 1, detection). Its scenarios
(a 7.4 node on `AUTO` detected as 7.4; 8.1 and 9.0 detection unchanged; 6.1 stays unknown) are not
part of this feature.

---

### User Story 9 - Health and processes of a pinned 6.1 node, unverified (Priority: P2, Mandate B)

A person with INUBIT 6.1 nodes pinned to `V6_1` asks for their health and their failed processes.
Since the owner decision of 2026-11-26 (second round) the server answers with the existing 6.1
StartCLI reads of feature 012 instead of refusing, and warns in every entry that INUBIT 6.1 is not
verified for the capability, on the same evidence as 7.4: fixtures reconstructed from the 6.1
vendor documentation.

**Why this priority**: the 6.1 reads exist since feature 012 and were refused only for lack of
recordings; the unverified level (constitution 2.1.0) makes them usable on the same terms as 7.4.

**Independent Test**: the existing 6.1 contract tests (English, German, CRLF) plus the per-entry
warning with the production matrix after the flip, on both CI jobs; the 6.1 guard of FR-037.

**Acceptance Scenarios**:

1. **Given** a node pinned to `V6_1` or `"6.1.0.89"` with a StartCLI client, **When** `get_health`
   targets it, **Then** it runs through StartCLI as in feature 012 (parts, `NOT_AVAILABLE_ON_6_1`,
   budget) and the entry carries `INUBIT 6.1 on <node> is not verified for monitoring; results may
   be incomplete`.
2. **Given** the same node, **When** `find_processes` runs, **Then** it returns the 6.1 fields with
   the warning `… is not verified for process diagnosis; results may be incomplete`; `tag` is
   refused as today.
3. **Given** a login failure, an unreachable node, unrecognized output or an exhausted budget,
   **When** either tool runs, **Then** the entry reports the error of feature 012 and carries the
   warning.
4. **Given** a 6.1 node, **When** any other tool targets it, **Then** the refusal, its text and next
   step are exactly those of feature 012.
5. **Given** a 6.1 node with only the version-less `defaults.cliHome`, **When** either tool runs,
   **Then** that client is used, as in features 012 and 017 (FR-029's rule applies to 7.4 only).
6. **Given** a profile of 6.1 nodes only, **When** the server starts, **Then** `find_processes` is
   announced under the unchanged rule of features 009 and 012, and `list_nodes` shows the 6.1
   node with `status: partially_supported` and `monitoring`, `process_diagnosis` usable.

---

### Edge Cases

- A 7.4 node on `AUTO` is never identified as 7.4 (Mandate A, and Mandate B since the withdrawal
  of detection, 2026-11-26): it is handled as an unknown version exactly as today: reads run with
  the 8.1 adapters and the "unverified" warning, writes are refused, and the 404 hint of FR-005
  asks for the pin. IMC-6 criterion 3 ("no request whose shape is unverified") applies to nodes
  known as 7.4; changing the unknown-version rule would change 6.1, 8.1 and 9.0 behaviour
  (criterion 5). The setup documentation tells 7.4 operators to pin `V7_4`; only a pinned node gets
  the 7.4 StartCLI path of Mandate B.
- A concrete pin with three parts (`"7.4.0"`) is accepted; patch comparison with a four-part
  client is "not comparable" (US3 scenario 4).
- A pin `"7.4.0.90"` on a node that runs another version: `get_health` on 7.4 observes no server
  version (FR-027, as on 6.1), so the contradiction rule of feature 009 is not fed from 7.4; the
  6.1 parsers then most likely fail with `UNEXPECTED_RESPONSE`, or the client check reports the
  client's range. The unverified warning tells the user that the results are not verified.
- A 7.3, 7.5 or 8.0 pin stays "known version outside every declared range", as today; this
  feature declares 7.4 only.
- `V7_X` is not offered; a whole 7 line is out of scope.
- The 7.4 client is configured but needs a JDK the profile does not name: the client check reports
  the start failure as today (feature 017); the next step names `javaHome` of the `"7.4"` entry.
- The 8.1 client is configured as the version-less `defaults.cliHome`, and neither a `"7.4"`
  entry nor a node or group `cli.home` exists (Mandate B): StartCLI-backed tools on the 7.4 node
  are refused with `CLI_UNAVAILABLE` naming the missing `"7.4"` entry; the version-less client is
  never used against a 7.4 node, because an 8.1 or 9.0 client's behaviour against 7.4 is
  unverified (FR-029).
- The real 7.4 client reads the password from the console instead of standard input (not known
  without a recording): the session sends the password on standard input only, as on 6.1, and
  never through `-p`; such a client ends the session with an error or a timeout, which is
  reported as such, never as an empty result.
- The real 7.4 output is German, has CRLF line ends or a non-UTF-8 code page on Windows: the 6.1
  parsers handle English and German labels and CRLF as on 6.1 (tested with variants built from the
  7.4 fixtures, FR-025); any unknown format is `UNEXPECTED_RESPONSE`, never an empty result, and
  the node's entry carries the unverified warning (FR-030 (b)).
- An interactive command of the real 7.4 client differs from 6.1 (name, options, columns): the
  node's entry reports `UNEXPECTED_RESPONSE` or the client's error, never a silent wrong result,
  and carries the unverified warning. Fixing it needs a recording or a reported output (future
  mandate or a defect fix when reported).
- The real 7.4 client's `-v` output differs from the reconstructed `version` case: the client check
  cannot read it and refuses StartCLI-backed tools with `CLI_UNAVAILABLE` (fail-closed, FR-029);
  this refusal comes before contact and carries no unverified warning.
- A 7.4 or 6.1 node classified `production`: reads only (both stay read-only), with the same
  warning; no recording is made in this feature.
- The same edge cases hold for 6.1 since Mandate B (US9), with the 6.1 texts of feature 012.

## Requirements *(mandatory)*

### Tool classification on INUBIT 7.4

| Tool | Capability | 7.4 after Mandate A | 7.4 after Mandate B | Channel |
|---|---|---|---|---|
| `list_nodes` | – (configuration) | runs, shows the 7.4 node | unchanged | none |
| `get_health` | monitoring | unsupported (refused) | unverified (untested), pinned nodes only; warning in every result (FR-030) | StartCLI (+ TLS handshake for the pin) |
| `find_processes` | process diagnosis | unsupported (refused) | unverified (untested), pinned nodes only; warning in every result (FR-030) | StartCLI (+ TLS handshake for the pin) |
| `check_artifacts` | – (offline check) | runs; module lookup skipped with a notice | unchanged | none |
| every other tool (logs, inventory, export, partners, queue entries, server logs, server settings, users, model details, node tools, all writes, deployment, end-to-end tests) | its capability | unsupported (refused) | unsupported (refused) | – |

On INUBIT 6.1 (pinned nodes) Mandate B changes `get_health` and `find_processes` from unsupported
(refused) to unverified (untested), warning in every per-node entry after the gate (FR-036); every
other tool stays refused as in feature 012.

### Functional Requirements

#### Mandate A — Identification and version model

- **FR-001**: The configuration MUST accept the line value `V7_4` and concrete 7.4 versions with
  three or four numeric parts, optionally with a suffix (`"7.4.0"`, `"7.4.0.90"`,
  `"7.4.0.90-HF1"`). `--check-config` and `list_nodes` MUST show `V7_4` like the other line values;
  the load error texts that list the allowed values MUST name `V7_4`.
- **FR-002**: The version model MUST keep the fourth numeric part of a version as its own value
  (absent when the value has three parts), for configured, server-reported and client-reported
  versions alike, and MUST show it wherever the version is shown.
- **FR-003**: For 7.4 versions the client/server patch comparison MUST first compare major, minor
  and patch as today (a patch absent on either side is "not comparable", a difference is a
  mismatch); only when these are equal does the fourth part decide: equal on both sides, or absent
  on both sides, is a match; different is a mismatch; present on one side only is "not
  comparable". For every other version line the
  comparison MUST stay as today; in particular the fourth part of a 6.1 version stays ignored
  (feature 012 FR-002). Where this rule applies MUST be declared per version range, not decided
  by version checks in tool logic (Constitution V).
- **FR-004**: The ordering and equality of versions used elsewhere (range membership, contradiction
  by major.minor, `cliClients` selection by major.minor) MUST NOT change.
- **FR-005**: `AUTO` MUST stay REST-based in this feature (Mandate A and Mandate B; detection of
  7.4 is withdrawn, FR-032). For a node on `AUTO` whose detection
  request is answered with HTTP 404, the conditional hint of feature 012 FR-003 MUST become "if
  <node id> runs INUBIT 6.1, pin `V6_1`; if it runs INUBIT 7.4, pin `V7_4`". Every other detection
  failure keeps today's warning.

#### Mandate A — Capabilities and refusals

- **FR-006**: The production capability matrix MUST declare a range 7.4–7.4, StartCLI only (no REST
  channel), with every capability `unsupported`; its monitoring and process diagnosis channels MUST
  be StartCLI, so that Mandate B only flips their state. A 7.4 node MUST never run with the 8.1
  adapters: the fallback of unknown versions (reads with the 8.1 adapters and the "unverified"
  warning) MUST NOT apply to 7.4. *Mandate B (2026-11-26):* only the states of MONITORING and
  PROCESS_DIAGNOSIS change, to `UNVERIFIED` through StartCLI (FR-030); every other capability stays
  as declared here.
- **FR-007**: Every tool that contacts a node MUST be refused for a 7.4 node with
  `UNSUPPORTED_VERSION` before any REST request, StartCLI run (including the local option and
  version probes of the client) or TLS handshake, naming the node, its version (`INUBIT 7.4`, not
  6.1), the missing capability and the next step "INUBIT 7.4 is recognised, but no tool is
  verified on it yet; use the INUBIT Workbench for <capability> on <node>". In calls addressing
  several nodes the refusal MUST be per node. The refusal MUST come before the client lookup, so a
  missing client never changes it. For write tools the order of the write checks and the version
  check MUST be the one that applies to 6.1 nodes today. *Mandate B (2026-11-26):* `get_health` and
  `find_processes` run on a pinned 7.4 node (FR-030); every other tool keeps this refusal, text
  and next step unchanged (the next step stays true: an unverified capability is not verified).
- **FR-008**: `check_artifacts` with module lookup on a 7.4 node MUST run its offline checks and
  skip the lookup with a notice, without contact.
- **FR-009**: A tool whose only candidate nodes are 7.4 nodes MUST NOT be announced (feature 009).
  *Mandate B (2026-11-26):* a pinned 7.4 node becomes a candidate node for `find_processes` under
  the unchanged announcement rule of features 009 and 012 (its capability is no longer
  `UNSUPPORTED` on 7.4); every other tool stays unannounced for 7.4-only profiles.
- **FR-010**: `list_nodes` and `--check-config` MUST show a 7.4 node with its version (all four
  parts if configured so), the channel "StartCLI only" and no usable capability, without contacting
  the node. *Mandate B (2026-11-26):* monitoring and process diagnosis are shown as usable (they
  run, unverified); every other capability stays not usable with its Mandate A text.
- **FR-010a**: A `defaults.cliClients."7.4"` entry MUST be handled in Mandate A by the generic rules
  of feature 017, unchanged: the key and its directories are validated as for any other key,
  `--check-config` runs its local version probe (no node contact) and reports the result,
  including a version or range it cannot evaluate; no JDK major warning is given for 7.4 (the
  shipped JDK of 7.4 is not verified); `list_nodes` and `--check-config` show the client
  directory of a 7.4 node per feature 017 FR-015 with the note that no tool uses it until a 7.4
  capability is verified. No tool runs the client against a 7.4 node in Mandate A (FR-007).
  *Mandate B (2026-11-26):* from the flip of FR-030 on, the client is used by `get_health` and
  `find_processes`; the note "not used until a 7.4 capability is verified" and the suppressed
  option probe end with it (FR-035). The rule "no JDK major warning for 7.4" stays.
- **FR-011**: The adapters selected for a 7.4 node MUST NOT build or use a REST client; every port
  of the 7.4 range refuses with `UNSUPPORTED_VERSION` as a second line of defence behind the gate.
  *Mandate B (2026-11-26):* the health port and the process query port of the 7.4 range are the
  6.1 StartCLI adapters with the 7.4 profile (FR-024); every other port keeps refusing.
- **FR-012**: Refusals on a 7.4 node MUST be logged like the version refusals of feature 009
  (structured, stderr).
- **FR-013**: FR-006 to FR-012 MUST hold identically when the MCP server runs on Windows, macOS and
  Linux, verified by the offline test jobs of each platform.

#### Mandate A — Fixtures and recording kit

- **FR-014**: A 7.4 fixture folder MUST exist with the case layout of the other versions
  (`<case>.stdout`, `.stderr`, `.exit`) and a README. Its only real case in Mandate A is the
  attached help output (`help`), stored with its exact text; the README states where it came from
  and that its exit code (assumed 0) and standard error (assumed empty) were not part of the
  attachment. Every other capability case is reconstructed (from the help output and the 6.1
  cases) and listed with its capability in `RECONSTRUCTED.txt`. The cases and what replaces them:

  | Fixture case | Capability | Mandate A | Replaced by (optional later verification; not in this feature since 2026-11-26) |
  |---|---|---|---|
  | `help` | monitoring, process diagnosis | recorded (attachment) | `v74_help` (re-recorded on Windows) |
  | `version` (added in Mandate B, 2026-11-26: the client's `-v`, FR-029) | monitoring | – | `v74_version` |
  | `health_session_ok` | monitoring | reconstructed | `v74_health_session` |
  | `health_session_maintenance` | monitoring | reconstructed | derived from `health_session_ok` (FR-016a): maintenance on cannot be recorded read-only |
  | `health_session_alive_failed` | monitoring | reconstructed | derived from `health_session_ok` (FR-016a) |
  | `login_failed` | monitoring | reconstructed | `v74_login_failed` |
  | `unreachable` | monitoring | reconstructed | `v74_failure` |
  | `ps_session_all` | process diagnosis | reconstructed | `v74_ps_all` |
  | `ps_session_error` | process diagnosis | reconstructed | `v74_ps_error` |
  | `ps_session_waiting` | process diagnosis | reconstructed | `v74_ps_waiting` |
  | `ps_session_queued` | process diagnosis | reconstructed | `v74_ps_queued` |
  | `ps_session_limit5` | process diagnosis | reconstructed | `v74_ps_limit5` |
  | `ps_session_empty` | process diagnosis | reconstructed | a recorded empty listing if one occurs, else derived from `ps_session_error` (FR-016a) |
  | `ps_session_unassigned` | process diagnosis | reconstructed | a recorded row without a cluster node if one occurs, else derived from `ps_session_all` (FR-016a) |

  German-language variants (as for 6.1) are added only if the recordings show language-dependent
  output; the recorder runs the client in English, as the server does. The research cases of
  FR-016 (`v74_version`, `v74_exec_help`, `v74_exec_ps_help`, `v74_direct_health`) back no
  capability; their recordings were to be evaluated for the Mandate B design and not kept in the
  repository (FR-021, IMC-24). *Mandate B (2026-11-26):* no case is replaced or adjusted; the
  reconstructed cases and the recorded `help` back the unverified capabilities of FR-030, and the
  last column names the path of a future mandate only.
- **FR-015**: A guard test MUST fail when the production matrix declares a 7.4 capability
  `supported` while any case of that capability is listed in `RECONSTRUCTED.txt`, or is a derived
  case whose source case is not recorded; and MUST fail when a listed case has no files or a fixture
  case is in none of `RECONSTRUCTED.txt`, `DERIVED.txt` and the README's list of recorded cases
  (the single source for recorded cases). The 7.4 fixtures MUST pass the customer identifier
  scan. *Mandate B (2026-11-26, replaces the IMC-24 amendment):* the guard MUST fail when the production matrix declares any 7.4 capability
  `SUPPORTED` (no 7.4 capability may be supported in this feature); MUST fail when it declares a
  7.4 capability other than MONITORING and PROCESS_DIAGNOSIS `UNVERIFIED`; and MUST fail when it
  declares MONITORING or PROCESS_DIAGNOSIS `UNVERIFIED` while that capability has no case in
  `RECONSTRUCTED.txt` or `help` is not in the README's recorded list (unverified needs
  reconstructed fixtures plus the real help output, constitution 2.1.0 Principle III). It MUST
  allow `UNVERIFIED` on these two capabilities backed by reconstructed cases. The rules on missing
  files and on the three lists stay. The reconstructed `version` case is listed in
  `RECONSTRUCTED.txt` as `version monitoring` (one line per case) and is therefore covered by the
  guard like every other case.
- **FR-016**: The Windows recorder MUST know these 7.4 cases, StartCLI only (no REST request),
  read-only, each storing standard output, standard error and exit code:
  - `v74_help` (`-h`, local, no server) and `v74_version` (`-v`, local, no server);
  - `v74_exec_help` (`--execCommand "help"`) and `v74_exec_ps_help` (`--execCommand "ps -h"`);
  - `v74_direct_health` (the direct options `-c`, `-sv`, `--pctmem`, `--maintenanceState`, `-ne`,
    `-nx`, `-nw`, `-nq` in one run);
  - `v74_health_session` (one interactive session with the health commands of the 6.1 session:
    `uname`, `uptime`, `maintenance`, `free -m`, `ps -c -e`, `ps -c -w`, `ps -c -q`, `ps -c -a`);
  - `v74_ps_all`, `v74_ps_error`, `v74_ps_waiting`, `v74_ps_queued`, `v74_ps_limit5` (interactive
    sessions with one `ps` listing each);
  - `v74_login_failed` (one login with a user name that does not exist, or a user the owner names
    for this purpose, and a dummy password, against the same node);
  - `v74_failure` (a wrong URL, to record the connection error; no server contacted).
- **FR-016a**: An edge case that cannot be recorded read-only on a healthy non-production node
  (maintenance on, a negative alive check, an empty listing that does not occur) MAY be derived
  from a recorded case by a minimal, documented edit. Derived cases MUST be listed in
  `DERIVED.txt` with their source case and a one-line description of the edit; they count as
  verified only while their source case is recorded. In Mandate A, when no source is recorded yet,
  such cases are reconstructed and listed in `RECONSTRUCTED.txt`. Since 2026-11-26 no 7.4 case is
  recorded in this feature, so `DERIVED.txt` stays empty; the rule serves a future mandate.
- **FR-017**: The recorder MUST gain an interactive-session mode: one StartCLI process per case,
  password on standard input only, the case's commands, then an end-of-session command; the
  environment allowlist, the group allowlist and the secret check of feature 013 apply unchanged.
  Every 7.4 case that contacts a server MUST check that the node's host is on the recorder's
  allowlist of non-production hosts before any launch. For 7.4 cases the recorder MUST NOT pass a
  trust-store or host-name option to the client (the 7.4 help lists neither): an HTTPS 7.4 node is
  recorded with a password-less trust store given to the client's Java runtime settings, and a
  request to skip host-name verification stops the recording (feature 012 FR-012a). Every 7.4 case
  that logs in passes the password on standard input only (as the feature 013 script-mode cases
  do), never through `-p` or an `--encryptString` value; the secret check applies to every such
  case.
- **FR-018**: The arguments and commands of every 7.4 case, including the session commands, the
  direct options and the end-of-session command, MUST be editable in one place (the catalogue), so
  that the recording session can adjust them after `v74_exec_help` and `v74_exec_ps_help` without a
  code change elsewhere.
- **FR-019**: Windows recordings MUST be stored byte-exact in the repository (line ends and code
  page kept). 7.4 recordings are not stored (FR-021, IMC-24); since 2026-11-26 none are made in
  this feature, and the reconstructed 7.4 fixtures keep LF line ends (CRLF is covered by the
  parser tests, FR-025).

#### Mandate A — Regression and documentation

- **FR-020**: Behaviour for 6.1, 8.1 and 9.0 nodes MUST NOT change on any platform, apart from the
  extended hint of FR-005 and the lists of allowed `versionLine` values (load error text,
  `list_nodes` schema descriptions, docs) that now name `V7_4`. Regression tests MUST pin, per version line, the version parsing, the
  patch comparison, the gate decision per capability and the refusal texts. The user documentation
  (setup, tool reference, README, research notes) and the changelog MUST describe 7.4 as
  "configurable and recognised, every tool refused until verified" in the same change.
  *Mandate B (2026-11-26, owner decision B):* 6.1 behaviour changes for exactly two capabilities,
  MONITORING and PROCESS_DIAGNOSIS, which become `UNVERIFIED` (FR-036): `get_health` and
  `find_processes` run on pinned 6.1 nodes with the warning instead of being refused, and
  `list_nodes`, `--check-config` and the tool announcement follow from the state. Everything else
  on 6.1, and everything on 8.1 and 9.0, stays unchanged; the regression tests pin it, and their
  6.1 rows for these two capabilities are adjusted deliberately in the flip.

#### Mandate B — Recordings (withdrawn 2026-11-26)

- **FR-021** — **Withdrawn 2026-11-26** (owner decision: the 7.4 recording session, IMC-22, will
  not take place). It required recording the 7.4 cases read-only on a Windows machine against a
  non-production 7.4.0.90 node, for evaluation only (IMC-24). No Mandate B requirement depends on
  it. The recorder and the kit stay as the optional path of a future mandate, under the rules of
  FR-016 – FR-019 and IMC-24 (never a `production` node, raw output never in a repository).
- **FR-022** — **Withdrawn 2026-11-26**: the reconstructed 7.4 cases are not adjusted to recordings;
  they stay as built in Mandate A (FR-014) and back the unverified capabilities (FR-030). A future
  mandate may adjust them to evaluated recordings.
- **FR-023** — **Withdrawn 2026-11-26**: no differences between 7.4 and 6.1 are researched from
  recordings. The research notes state what is known (the real 7.4 help output) and that every
  other 7.4 behaviour is assumed to be the one of 6.1 (FR-034).

#### Mandate B — Health and processes

- **FR-024**: All 7.4-specific behaviour MUST live in the adapter layer selected by the node's
  version; tool logic MUST NOT branch on version strings. The 7.4 health and process reads MUST
  behave exactly as the 6.1 StartCLI reads (one interactive StartCLI session per call and node,
  password on standard input only, the 6.1 commands, the 6.1 parsing rules and result shapes),
  differing only in (a) the version every text names ("INUBIT 7.4", e.g. "find_processes: INUBIT 7.4
  does not support the input tag; nothing was sent") and (b) the reason given for the unavailable
  health parts (FR-026). Session mode is used, as on 6.1 (decided 2026-11-26: the reconstructed
  fixtures are session outputs, and the real 7.4 help shows the interactive mode); the script mode
  (`--execCommand`) and the direct options of the 7.4 help are not used.
- **FR-025**: The 7.4 reads MUST be tested against the reconstructed 7.4 fixtures (FR-014) and
  against variants built from them inside the tests: CRLF line ends, and German labels, column
  names and state names exactly as the 6.1 parsers accept them (decided 2026-11-26: no German 7.4
  fixture file is added; the 6.1 German cases are not reused as 7.4 cases). Unrecognized output
  MUST be `UNEXPECTED_RESPONSE`, never an empty result.
- **FR-026**: `get_health` on a pinned 7.4 node MUST report, from StartCLI: `reachable`, `status`
  (`OK` when the alive check succeeds, `ERROR` when it answers negatively, `UNKNOWN` when it could
  not be obtained), maintenance state, JVM memory and process counts per state (Error, Processing,
  Waiting, Queued), as the 6.1 health session reads them; the version the session reports is
  handled by FR-027. Readiness, thread figures and the blocking queue (the parts 6.1 lacks) MUST be
  listed as unavailable with the reason `NOT_AVAILABLE_ON_7_4`. The warning of FR-030 (b) applies.
  The time budget of 6.1 (feature 012 FR-009: 15 seconds for one node, the overview budget of
  feature 001 for several) applies; a session that exhausts it is reported as `TIMEOUT` for that
  node.
- **FR-027**: Decided 2026-11-26 by assuming 6.1 behaviour: the version the 7.4 health session
  reports counts as the StartCLI client's, not the server's; it is reported in a warning ("the
  version … read on <node> is the StartCLI client's; the server version cannot be read") and MUST
  NOT be recorded as the node's observed version. The client/server patch comparison of
  `get_health` uses the pinned version and the four-part rule of FR-003. **Known limitation of the
  unverified level** (accepted by the owner, 2026-11-26): `get_health` on 7.4 (and on 6.1, FR-036)
  reports and observes no server version, a 7.4 or 6.1 pin is never contradicted through StartCLI,
  and no run of the direct option `-sv` is added. A future mandate may change this from a
  recording.
- **FR-028**: `find_processes` on a pinned 7.4 node MUST return the fields of the 6.1 result shape;
  process id, workflow and state are required in every row, otherwise the listing is
  `UNEXPECTED_RESPONSE`. Decided 2026-11-26 by assuming 6.1 behaviour: the date column is the time
  in state, so hanging instances and the time range follow the 6.1 rules of feature 012 (whether
  the waiting listing also lists `Retry` stays open as on 6.1); `tag` is refused with
  `UNSUPPORTED_VERSION` naming the input, before contact; truncation, paging and the time budget
  follow the 6.1 rules of feature 012. The warning of FR-030 (b) applies.
- **FR-029**: StartCLI on a 7.4 node MUST use, in this order: the node's `cli.home`, the group's
  `cli.home`, then `defaults.cliClients."7.4"` (with its `javaHome`). Deviating from feature 017
  FR-003, the version-less `defaults.cliHome` MUST NOT be used for a 7.4 node; without one of the
  three, StartCLI-backed tools are refused with `CLI_UNAVAILABLE` naming `defaults.cliClients."7.4"`,
  and `--check-config` warns about it. The client check of feature 009 (its `-v` and declared range)
  applies to the chosen client, and its `-v` MUST report major.minor 7.4. The check is fail-closed
  (decided 2026-11-26): any other client version, and a `-v` output that cannot be read (including
  a real 7.4 client whose output differs from the reconstructed `version` case of FR-014), refuses
  StartCLI-backed tools on the node with `CLI_UNAVAILABLE` naming the client and what it reported.
  On Windows the feature 013 invocation applies (password through standard input only,
  environment allowlist, CRLF and code page handling). Feature 012 FR-012 (the server's own TLS
  handshake for the pin, no HTTP request) and FR-012a apply unchanged: the 7.4 client counts as
  having neither a trust-store nor a host-name option, as the real 7.4 help shows; a certificate the
  handshake rejects starts no StartCLI process (`TLS_ERROR`); a request to skip host-name
  verification is refused with `TLS_ERROR` before contact.
- **FR-030** (replaced 2026-11-26): MONITORING and PROCESS_DIAGNOSIS on 7.4 MUST be declared
  `UNVERIFIED`, never `SUPPORTED`, and only when (a) their contract tests (FR-025 – FR-028) pass
  against the reconstructed 7.4 fixtures on the macOS/Linux and the Windows CI jobs; (b) every
  per-node entry of `get_health` and `find_processes` for a node of that version that passed the
  version gate carries the existing user-visible warning "INUBIT <version> on <node> is not
  verified for <capability>; results may be incomplete", whether it reports data or an error from
  the attempt (`UNEXPECTED_RESPONSE`, login failure, unreachable, `TLS_ERROR` from the handshake,
  `TIMEOUT`); refusals decided before any contact (`CLI_UNAVAILABLE`, missing credentials, `tag`,
  a TLS refusal before the handshake, `UNSUPPORTED_VERSION`) need not carry it; (c) the
  documentation of FR-034 is in the same change; and (d) constitution 2.1.0 is ratified by the
  owner (met: ratified 2026-11-26, IMC-25). On a version reached through StartCLI only, an `UNVERIFIED` read MUST run through
  StartCLI with that warning and the client check, instead of being refused, and MUST never fall
  back to the 8.1 adapters or REST; `get_health` reaches such a node through StartCLI. `SUPPORTED`
  on 7.4 is forbidden in this feature; every other 7.4 capability stays `UNSUPPORTED` with its
  note "not yet verified". No live check is made.
- **FR-031** (replaced 2026-11-26): No live check is made, on any platform. Verification of
  Mandate B is the offline suite on the macOS/Linux and the Windows CI jobs (FR-025 – FR-030,
  FR-036); the capability matrix stays platform-independent. Defects reported from real 7.4 or 6.1
  installations are fixed when they appear. Raising a capability from `UNVERIFIED` to `SUPPORTED`
  is a future, separate mandate with recordings or evaluated recordings plus a live check against
  a non-production node of that version (constitution 2.0.0 / 2.1.0, Principles III and V).

#### Mandate B — INUBIT 6.1 health and processes (added 2026-11-26)

- **FR-036**: On the same evidence level (owner decision 2026-11-26, second round), MONITORING
  (`get_health`) and PROCESS_DIAGNOSIS (`find_processes`) on 6.1 MUST be declared `UNVERIFIED`,
  never `SUPPORTED`, in the same change as 7.4 (FR-030), for nodes pinned to 6.1 (`V6_1` or a
  concrete 6.1 version), through StartCLI only, with the existing 6.1 reads of feature 012
  unchanged (commands, parsers, fields, budget, the version-less `defaults.cliHome` and the client
  rules of features 012 and 017, the fourth version part ignored). FR-030 (a) – (d) apply with the
  6.1 fixtures (reconstructed from the 6.1 vendor documentation, feature 012) and the existing 6.1
  contract tests, including their German and CRLF cases; FR-027's known limitation applies (6.1
  already treats the session's version as the client's). Every other 6.1 capability stays
  `UNSUPPORTED` with its texts and next steps of feature 012 unchanged. 6.1 stays read-only.
- **FR-037**: A guard test like FR-015 MUST hold for 6.1: it fails when the production matrix
  declares any 6.1 capability `SUPPORTED` (no 6.1 case is recorded); fails when it declares a 6.1
  capability other than MONITORING and PROCESS_DIAGNOSIS `UNVERIFIED`; and fails when it declares
  one of these two `UNVERIFIED` while that capability has no case in the 6.1 `RECONSTRUCTED.txt`.

#### Mandate B — Detection (withdrawn 2026-11-26)

- **FR-032** — **Withdrawn 2026-11-26** (owner decision): `AUTO` detection of 7.4 is out of scope.
  Without a 7.4 node no detection step and no trigger can be verified. `AUTO` never selects 7.4;
  only pinned nodes are served, and the 404 hint of FR-005 stays.
- **FR-033** — **Withdrawn 2026-11-26** with FR-032: detection stays unchanged for every node; no
  additional request (REST or StartCLI) is sent during detection.

#### Mandate B — Documentation and switches

- **FR-034**: When MONITORING and PROCESS_DIAGNOSIS on 7.4 and 6.1 are declared `UNVERIFIED`
  (FR-030, FR-036), the user documentation (setup incl. `defaults.cliClients."7.4"`, its JDK and
  the client order of FR-029; the tool reference; the README; the research notes) and the changelog
  MUST mark `get_health` and `find_processes` on 7.4 and on 6.1 as **untested** (unverified: the
  warning, pinned nodes only, no `AUTO`), replacing the README's "become available once verified
  against recorded outputs" for 6.1, in the same change.
- **FR-035** (added 2026-11-26): Once MONITORING and PROCESS_DIAGNOSIS on 7.4 are `UNVERIFIED`
  without the note "not yet verified", the following MUST hold (the Mandate A behaviour keyed on
  "every 7.4 capability carries a note" ends):
  - A refusal that lists the lines supporting a capability ("; supported: 8.1, 9.0") never lists
    7.4 (or 6.1) for a capability out of scope there; 8.1 and 9.0 are listed as today.
  - The refusal of every other capability on 7.4 keeps its Mandate A message and next step
    ("INUBIT 7.4 is recognised, but no tool is verified on it yet; use the INUBIT Workbench for
    <capability> on <node>"; owner, 2026-11-26).
  - `list_nodes` and `--check-config` no longer say that the 7.4 client is not used; `list_nodes`
    adds no other warning for 7.4 or 6.1 nodes (owner, 2026-11-26).
  - The local option probe (`-h`) of the 7.4 client runs as for 6.1, because the StartCLI
    connection needs the client options; it contacts no node.
  - `--check-config` warns about a 7.4 node without a 7.4 client, naming
    `defaults.cliClients."7.4"` (FR-029); the 6.1 warning is unchanged.

### Key Entities

- **7.4 node**: a configured node pinned to INUBIT 7.4 (`V7_4` or a concrete 7.4 version);
  reached through StartCLI only. A node on `AUTO` is never a 7.4 node (detection withdrawn
  2026-11-26).
- **Version with fourth part**: major, minor, patch, an optional fourth numeric part and the raw
  text (suffix kept for display).
- **Patch comparison rule per range**: whether the fourth part counts for the client/server patch
  comparison; four parts for 7.4, three for every other range.
- **Capability matrix entry 7.4**: CLI-only, every capability `unsupported` with the note "not yet
  verified" in Mandate A; in Mandate B monitoring and process diagnosis `unverified` without a note
  (FR-030), every other capability unchanged.
- **Capability matrix entry 6.1**: CLI-only, every capability `unsupported` until Mandate B; then
  monitoring and process diagnosis `unverified` (FR-036), every other capability unchanged.
- **7.4 profile**: what distinguishes the 7.4 reads from the 6.1 reads: the version the texts
  name and the reason given for unavailable health parts (FR-024, FR-026).
- **7.4 fixture case**: recorded (the help output only) or reconstructed (listed in
  `RECONSTRUCTED.txt`); in this feature every case except `help` stays reconstructed.
- **7.4 recording case**: an entry of the Windows recorder's catalogue: local run, script-mode run,
  direct-option run or interactive session; kept for an optional later verification.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001** (IMC-6 criterion 1, pin — Mandate A): a node pinned to `V7_4` or `"7.4.0.90"` is loaded
  and shown as 7.4 by `--check-config` and `list_nodes` on all three platforms.
- **SC-002** (IMC-6 criterion 1, hint — Mandate A): a detection answered with HTTP 404 names both
  `V6_1` and `V7_4` in its hint; every other detection failure is unchanged.
- **SC-003** (IMC-6 criterion 3 — Mandate A): 100% of the tools that contact a node are refused on a
  7.4 node before any contact, verified by one offline test per tool on each platform's test job,
  with fakes that fail on any REST request, StartCLI run or TLS handshake.
- **SC-004** (IMC-6 criterion 4 — Mandate A): 7.4.0.90 vs 7.4.0.80 is a patch mismatch, 7.4.0.90 vs
  7.4.0.90 a match; 6.1.0.89 vs 6.1.0.80 stays a match as today.
- **SC-005** (IMC-6 criterion 5 — Mandate A): the offline test suite of features 001–024 passes on
  the macOS/Linux and Windows test jobs, with only the tests of the FR-005 hint and of the lists of
  allowed `versionLine` values adjusted.
- **SC-006** (Mandate A): a person with the Windows machine and the 7.4 client can record every 7.4
  case of FR-016 in one sitting by following the quickstart, without editing code.
- **SC-007** (IMC-6 criterion 2, as unverified — Mandate B, rewritten 2026-11-26): on a pinned
  7.4 node, `get_health` and `find_processes` run through StartCLI against the reconstructed 7.4
  fixtures, and 100% of their per-node entries that passed the version gate carry the unverified
  warning, with data or with an error from the attempt (FR-030 (b)), in the offline suite of the
  macOS/Linux and the Windows CI jobs; the 6.1 time budget (15 seconds for one node) is enforced by
  the budget tests of feature 012 for 7.4 too. No live check.
- **SC-008** — **Withdrawn 2026-11-26** with FR-032 and FR-033 (detection out of scope). What
  stays: no node receives an additional request during detection (detection is unchanged).
- **SC-009** (Mandate B, rewritten 2026-11-26): the guard test is green with the production matrix:
  no 7.4 capability is `SUPPORTED`, and only monitoring and process diagnosis are `UNVERIFIED`,
  each backed by reconstructed cases and the recorded `help`; the only 7.4 output in the repository
  is the IMC-6 attachment (`help`); no output of the recorder for 7.4 is in the repository (IMC-24).
- **SC-010** (Mandate B, added 2026-11-26): the 6.1 guard (FR-037) is green with the production
  matrix: no 6.1 capability is `SUPPORTED`, only monitoring and process diagnosis are `UNVERIFIED`,
  each backed by reconstructed 6.1 cases; on a pinned 6.1 node every per-node entry of `get_health`
  and `find_processes` after the version gate carries the unverified warning (offline suite, both
  CI jobs), and every other 6.1 capability gives exactly the feature 012 refusal.

## Out of Scope

- REST-based tools on 7.4, including any REST detection probe specific to 7.4.
- Write tools on 7.4: restart, kill, import, tag, deploy, backup, restore, delete, rename.
- Client-helper tools on 7.4 (queue entries, log download, node tools); the helper knows only 8.1
  and 9.0.
- Logs, inventory, workspace export and partner management on 7.4 and on 6.1; further CLI commands
  follow as separate mandates once recordings exist.
- Detecting 7.4 on `AUTO` (FR-032, FR-033, US8, SC-008; withdrawn 2026-11-26): only pinned nodes
  are served.
- The 7.4 recording session, the evaluation of recordings and any live check against a 7.4 node
  (FR-021 – FR-023, withdrawn 2026-11-26), and declaring any 7.4 capability `SUPPORTED`: a future,
  separate mandate may raise monitoring and process diagnosis from unverified to supported with
  recordings or evaluated recordings plus a live check (constitution 2.0.0 / 2.1.0); the same holds
  for 6.1.
- Other 7.x lines (7.3, 7.5) and a whole-major line value `V7_X`.
- Detecting 6.1 automatically.
- Recording on macOS or Linux with the bash recorder (the owner records on Windows only).

## Preconditions

- **Mandate A**: none.
- **Mandate B** (replanned 2026-11-26): **met**. The constitution amendment 2.1.0 (MINOR) was
  ratified by the owner on 2026-11-26 (IMC-25): Principles III and V add the evidence level
  *unverified* (contract tests against reconstructed fixtures built from the vendor documentation
  of the version or from recordings, vendor documentation or reconstructed fixtures of another
  version, checked against real output of the version where any exists; a warning in every result;
  documentation "untested"). It builds on 2.0.0 (ratified 2026-11-23, IMC-23: 7.4 read-only,
  CLI-only). The provenance it covers is stated in Context & Findings: the 7.4 and 6.1 fixtures go
  back to the 6.1 vendor documentation; the only real output is the 7.4 `help`, which the owner
  accepted as the help output of a 7.4 client when ratifying (Assumptions). No 7.4 node, no
  Windows machine with a 7.4 client, no recording session and no live check are preconditions any
  more.

## Assumptions

- The attached help output is that of a 7.4.0.90 client (the patch the owner targets); the text
  itself does not name a version. The owner accepted it as the help output of a 7.4 client when
  ratifying constitution 2.1.0 (2026-11-26). It was produced by `startcli.sh` on Linux; Windows `-h` output may
  differ in line ends only.
- The interactive commands and their output on 7.4 are close to 6.1 (the direct options are);
  reconstructed cases start from the 6.1 cases and the help output. Since 2026-11-26 no recording
  decides: Mandate B assumes 6.1 behaviour wherever the real 7.4 help output says nothing, and
  every result says so through the unverified warning.
- A 7.4 node answers the REST detection request with HTTP 404 (8.0 reworked the REST API, so 7.4
  most likely lacks `system/info`). If it answers otherwise, the hint of FR-005 does not appear and
  the warning stays as today; nothing else in Mandate A depends on this. Since the withdrawal of
  detection (2026-11-26) nothing in Mandate B depends on it either.
- The 404 hint names both pins (FR-005) because a 404 alone cannot tell 6.1 from 7.4 without a 7.4
  recording; it stays so in Mandate B (detection withdrawn 2026-11-26).
- Keeping the fourth part only for 7.4 comparisons (FR-003) satisfies IMC-6 criteria 4 and 5
  together: 8.1 and 9.0 versions have three parts, and feature 012 deliberately ignores the fourth
  part on 6.1.
- A 7.4 node is reached with the StartCLI URL `<server>/ibis/servlet/IBISSoapServlet`, as the help
  output's default shows.
- The 7.4 client needs Java 11 or older; the `"7.4"` entry of `defaults.cliClients` names its JDK.
- The 7.4 client accepts the 6.1 session (password on standard input, the 6.1 interactive commands
  `uname`, `uptime`, `maintenance`, `free -m`, `ps …`) and prints the 6.1 formats; not verified
  (2026-11-26), hence `UNVERIFIED`.

## Open Questions (owner)

- **Why** (IMC-6): which installations and how many 7.4 nodes — accepted as open at I2; it decides
  no requirement of this spec.
### Decided

- **Mandate B without recordings, 7.4 unverified (untested)** (Mandates, US6 – US8, FR-014,
  FR-015, FR-021 – FR-035, SC-007 – SC-009, Preconditions) — **decided by the owner, 2026-11-26**:
  the recording session (IMC-22) and the Windows live check will not take place. MONITORING and
  PROCESS_DIAGNOSIS run on pinned 7.4 nodes through StartCLI with the 6.1 adapters and a 7.4
  profile, declared with the existing `SupportState.UNVERIFIED`; every result carries the existing
  warning; documentation says "untested". No new support state; 7.4 stays read-only; every other
  tool refuses as in Mandate A. `AUTO` detection of 7.4 is dropped (FR-032, FR-033, US8 withdrawn).
  The recording kit stays as an optional later verification path; raising 7.4 to supported is a
  future mandate. Governing rule: constitution 2.1.0 (ratified by the owner 2026-11-26, IMC-25). This supersedes, for Mandate B, the decisions "Recordings for evaluation only"
  and "macOS / Linux live check" below.

- **7.4 unverified on 6.1-derived fixtures; 6.1 unverified too** (Context, Preconditions, FR-020,
  FR-036, FR-037, US9, SC-010) — **decided by the owner, 2026-11-26 (second round)**: (A) the 7.4
  unverified level may rest on fixtures derived from the 6.1 fixtures, which come from the 6.1
  vendor documentation, checked against the one real 7.4 output (`help`); constitution 2.1.0
  says so and is ratified (IMC-25); the attached help output is accepted as the 7.4 help. (B) 6.1
  `get_health` and `find_processes` become `UNVERIFIED` on the same basis, in the same flip as
  7.4; everything else on 6.1 stays unchanged.

- **Follow-up decisions on the replanned Mandate B** (FR-027, FR-035) — **decided by the owner,
  2026-11-26**: (1) the `uname` version is the client's as on 6.1, no `-sv` run, no pin
  contradiction from 7.4: a known limitation of the unverified level; (2) the refusal next step
  "INUBIT 7.4 is recognised, but no tool is verified on it yet" stays unchanged for every other
  tool; (3) no extra `list_nodes` warning for 7.4 nodes: the per-result warning and the "untested"
  documentation suffice.

- **Constitution amendment** (Preconditions) — **ratified by the owner, 2026-11-23 (IMC-23)**:
  constitution 2.0.0 (Principle V: 7.4 read-only, StartCLI only, because its REST interface is
  unverified; Principles III and V: evidence without stored recordings, IMC-24).

- **Recordings for evaluation only** (FR-015, FR-021 – FR-023, FR-030, SC-009) — **decided by the
  owner, 2026-11-23 (IMC-24)**: the 7.4 recordings never enter the repository; they are handed
  over raw, evaluated outside the repository and deleted afterwards. The reconstructed fixtures
  are adjusted to the evaluation and stay reconstructed; 7.4 capabilities are enabled through the
  Windows live check and the ratified constitution amendment. Rejected alternatives: synthetic
  fixtures modelled on the recordings; neutralized recordings kept privately with local-only
  contract tests; neutralizing on the Windows machine. *Superseded for Mandate B on 2026-11-26* (no
  recordings are made); it still governs any future recording of 7.4.

- **macOS / Linux live check** (FR-031, SC-007) — **decided by the owner, 2026-11-23 (IMC-19)**:
  no macOS or Linux machine with the 7.4 client and access to a 7.4 node exists; the Windows-only
  live check suffices before 7.4 health and processes are enabled. macOS and Linux rest on the
  offline suite; defects found there are fixed when they appear. *Superseded on 2026-11-26*: no
  live check is made on any platform (FR-031).

- **`AUTO` 7.4 nodes** (Edge Cases) — **decided: accepted by the owner, 2026-11-18**: a 7.4 node
  on `AUTO` that is not identified stays an unknown version as today (8.1 reads with the
  "unverified" warning); IMC-6 criterion 3 applies to nodes known as 7.4. Since 2026-11-26 this
  holds for every 7.4 node on `AUTO` (detection withdrawn). The rejected alternative,
  refusing reads on every unknown node after a 404, would change 6.1 and 8.1 behaviour
  (criterion 5).
- **Derived edge-case fixtures** (FR-016a) — **decided: accepted by the owner, 2026-11-18**: edge
  cases that cannot be recorded read-only (maintenance on, negative alive check) are derived from a
  recording by a documented minimal edit and count as verified; the rejected alternative was to
  drop those scenarios for 7.4.
