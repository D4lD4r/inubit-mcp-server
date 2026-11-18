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
  output must therefore come from recordings of a real 7.4 node, as for 6.1 (feature 012).
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
  supported read-only, CLI-only. Declaring 7.4 `supported` for any capability, and serving it
  through StartCLI only, needs an amendment that extends the same rule to 7.4. Mandate A declares
  no 7.4 capability supported and therefore does not depend on it; Mandate B does.

## Clarifications

None yet. Decisions taken as informed defaults are listed under Assumptions; the open points for
the owner are listed under Open Questions.

## Mandates

The feature is delivered in two mandates. Mandate A needs no 7.4 node and can be implemented and
merged on its own. Mandate B needs live access to a 7.4 node and stays in Draft until its
prerequisites (Preconditions) are met.

| | Mandate A (no 7.4 node needed) | Mandate B (needs a 7.4 node) |
|---|---|---|
| User stories | US1, US2, US3, US4, US5 | US6, US7, US8 |
| Requirements | FR-001 – FR-020 | FR-021 – FR-033 |
| IMC-6 criteria | 1 (pin), 3, 4, 5 | 1 (detection), 2 |
| Result | 7.4 is known, pinned, shown, and refused clearly on every platform; the recording kit for Windows is ready | `get_health` and `find_processes` verified on a real 7.4.0.90 node on Windows and on macOS / Linux; automatic 7.4 detection |

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
platform's test job, and confirm the refusals and that no REST request, StartCLI run or TLS
handshake reaches the node.

**Acceptance Scenarios**:

1. **Given** a node with `versionLine: V7_4`, **When** the configuration is loaded and checked,
   **Then** it is accepted, and `--check-config` and `list_nodes` show version line 7.4, the channel
   "StartCLI only" and no usable capability.
2. **Given** a node pinned to `"7.4.0.90"`, **When** `list_nodes` lists it, **Then** it shows the
   version `7.4.0.90` (all four parts) and the same channel and capabilities as for `V7_4`.
3. **Given** a 7.4 node, **When** any tool that contacts a node targets it (`get_health`,
   `find_processes`, `query_logs`, inventory, export, any write, any node tool), **Then** it is
   refused with `UNSUPPORTED_VERSION` before any contact, naming the node, version 7.4, the
   capability and a next step.
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

---

### User Story 2 - A failed detection points to 7.4 as well as 6.1 (Priority: P2, Mandate A)

A person adds a 7.4 node with `AUTO`. The REST detection fails with HTTP 404. The warning tells the
person that the node may run INUBIT 6.1 or 7.4 and which pin to set for each, instead of suggesting
only the 6.1 pin as today.

**Why this priority**: IMC-6 criterion 1 asks that a failed detection points to 7.4; without a
7.4 node the server cannot tell 6.1 from 7.4 on a 404, so the hint names both until Mandate B adds
detection.

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
4. **Given** a 7.4 value with three parts (`"7.4.0"`) on one side and four parts on the other,
   **When** the patch levels are compared, **Then** the result is "not comparable" (as for an absent
   patch today), never a match.
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

**Why this priority**: Mandate B depends on one well-prepared recording session on a machine the
owner does not have; everything that can be prepared without the node must be ready and tested.

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
7. **Given** a recording target whose group is not on the recorder's allowlist, or a node not
   confirmed as non-production, **When** a 7.4 case is started, **Then** the recorder stops before
   any connection.

---

### User Story 5 - 6.1, 8.1 and 9.0 behave exactly as before (Priority: P1, Mandate A)

Profiles with 6.1, 8.1 and 9.0 nodes behave exactly as before on every platform; the only visible
change is the extended 404 hint of US2.

**Why this priority**: IMC-6 criterion 5; the change touches the shared version gate and the
detection path every version uses (risk class medium).

**Independent Test**: The full offline test suite on the macOS/Linux and the Windows test jobs,
plus dedicated regression tests per version for parsing, matching, gating and the client check.

**Acceptance Scenarios**:

1. **Given** the offline test suite before this feature, **When** it runs after Mandate A, **Then**
   it passes unchanged on every platform, apart from the tests of the deliberate change of US2.
2. **Given** an 8.1.17 server with an 8.1.16 client, or a 9.0.10 server with a 9.0.10 client,
   **When** the patch levels are compared, **Then** the outcome is as before.
3. **Given** a node pinned to `V6_1`, `V8_1`, `V9_0` or `V9_X`, **When** any tool runs, **Then**
   the gate decision, channel and refusal texts are as before.

---

### User Story 6 - Check the health of a real 7.4 node (Priority: P1, Mandate B)

A person operating a 7.4 installation asks "Is the 7.4 server up?". The server answers from the 7.4
StartCLI client alone: reachable and logged in, alive, the server version, maintenance state, JVM
memory and process counts per state, and says which parts of a health report do not exist on 7.4 —
both when the MCP server runs on Windows and on macOS or Linux.

**Why this priority**: IMC-6 criterion 2; the health check is the entry point of every diagnosis.

**Independent Test**: Contract tests against the recorded, neutralized 7.4 outputs; a live check on
the 7.4.0.90 node from Windows and from macOS or Linux.

**Acceptance Scenarios**:

1. **Given** a node pinned to 7.4 with a 7.4 client configured in `defaults.cliClients."7.4"`,
   **When** `get_health` targets it, **Then** the report shows the parts of FR-026 and lists the
   others as unavailable with the reason `NOT_AVAILABLE_ON_7_4`, and no REST request is sent.
2. **Given** the alive check answers negatively, **When** `get_health` runs, **Then** `status` is
   `ERROR` and the other parts that could be read are still reported.
3. **Given** a 7.4 node without a configured client, **When** `get_health` targets it, **Then** it is
   reported with `CLI_UNAVAILABLE` and a next step naming `defaults.cliClients."7.4"`.
4. **Given** a node pinned to 7.4 whose StartCLI reports another major.minor, **When** `get_health`
   runs, **Then** the contradiction is reported with both versions.
5. **Given** the MCP server runs on Windows, **When** scenarios 1–4 run against the real node,
   **Then** they behave as on macOS or Linux (feature 013 invocation).

---

### User Story 7 - Find failed and hanging processes on a real 7.4 node (Priority: P1, Mandate B)

The person asks "Which processes are in error on the 7.4 server?". The server lists process
instances of the 7.4 node by state from the 7.4 StartCLI client, with the fields 6.1 provides, and
marks hanging ones where the recordings show the time in state.

**Why this priority**: IMC-6 criterion 2; failed processes are the main diagnosis use case.

**Independent Test**: Contract tests against recorded 7.4 process listings in every state; a live
check from Windows and from macOS or Linux.

**Acceptance Scenarios**:

1. **Given** a 7.4 node with processes in Error and Waiting state, **When** `find_processes` asks
   for `ERROR`, **Then** only Error instances are returned, with the fields of FR-028.
2. **Given** a listing that reaches the row cap, **When** `find_processes` returns, **Then** the
   result carries a truncation marker and its total is marked as a lower bound.
3. **Given** a `tag` filter or another input the recordings show 7.4 cannot serve, **When**
   `find_processes` runs on a 7.4 node, **Then** it is refused for that node with
   `UNSUPPORTED_VERSION`, naming the input.
4. **Given** a listing with no instance, **When** `find_processes` returns, **Then** the result is
   empty, not an error.

---

### User Story 8 - A 7.4 node on `AUTO` is detected (Priority: P3, Mandate B)

A person adds a 7.4 node with `AUTO` and a 7.4 client configured. The server identifies it as 7.4
without a pin and serves it like a pinned 7.4 node.

**Why this priority**: IMC-6 criterion 1 (detection); the pin covers the need until then.

**Independent Test**: With recorded 7.4 answers to the detection steps, confirm that a node on
`AUTO` becomes 7.4, that 6.1, 8.1 and 9.0 nodes are detected as before, and that a node the steps
cannot identify stays unknown with the US2 hint.

**Acceptance Scenarios**:

1. **Given** a 7.4 node on `AUTO` with a 7.4 client configured, **When** its version is detected,
   **Then** it is 7.4 and `list_nodes` shows it as detected 7.4.
2. **Given** an 8.1 or 9.0 node on `AUTO`, **When** its version is detected, **Then** the result and
   the requests sent are exactly as before.
3. **Given** a 6.1 node on `AUTO`, **When** its version is detected, **Then** it stays unknown with
   the US2 hint, as before (6.1 is identified by pin only, feature 012).

---

### Edge Cases

- A concrete pin with three parts (`"7.4.0"`) is accepted; patch comparison with a four-part
  client is "not comparable" (US3 scenario 4).
- A pin `"7.4.0.90"` while `get_health` (Mandate B) observes 7.3 or 8.0: pin contradiction, every
  contacting tool except `get_health` is refused naming both versions (feature 009 rule).
- A 7.3, 7.5 or 8.0 pin stays "known version outside every declared range", as today; this
  feature declares 7.4 only.
- `V7_X` is not offered; a whole 7 line is out of scope.
- The 7.4 client is configured but needs a JDK the profile does not name: the client check reports
  the start failure as today (feature 017); the next step names `javaHome` of the `"7.4"` entry.
- The 8.1 client is configured as the version-less `cliHome` and no `"7.4"` entry exists
  (Mandate B): StartCLI-backed tools on the 7.4 node are refused with `CLI_UNAVAILABLE` naming the
  missing `"7.4"` entry; the 8.1 client is never used against a 7.4 node, because its behaviour
  against 7.4 is unverified.
- The 7.4 client prompts for the password instead of reading it from standard input: the recording
  stops and the owner decides (feature 013 rule); `-p` on the command line is never used.
- The recordings show German output, CRLF line ends or a non-UTF-8 code page on Windows: the
  parsers handle them as on 6.1 and 8.1; any unknown format is `UNEXPECTED_RESPONSE`, never an empty
  result.
- Recordings show that an interactive command differs from 6.1 (name, options, columns): the
  catalogue and parsers follow the recordings; the reconstructed case is replaced, not kept beside
  the recorded one.
- The only available 7.4 node is classified `production`: no recording is made (FR-021); Mandate B
  stays blocked.

## Requirements *(mandatory)*

### Tool classification on INUBIT 7.4

| Tool | Capability | 7.4 after Mandate A | 7.4 after Mandate B | Channel |
|---|---|---|---|---|
| `list_nodes` | – (configuration) | runs, shows the 7.4 node | unchanged | none |
| `get_health` | monitoring | unsupported (refused) | supported after the live checks of FR-031 | StartCLI (+ TLS handshake for the pin) |
| `find_processes` | process diagnosis | unsupported (refused) | supported after the live checks of FR-031 | StartCLI (+ TLS handshake for the pin) |
| `check_artifacts` | – (offline check) | runs; module lookup skipped with a notice | unchanged | none |
| every other tool (logs, inventory, export, partners, queue entries, server logs, server settings, users, model details, node tools, all writes, deployment, end-to-end tests) | its capability | unsupported (refused) | unsupported (refused) | – |

### Functional Requirements

#### Mandate A — Identification and version model

- **FR-001**: The configuration MUST accept the line value `V7_4` and concrete 7.4 versions with
  three or four numeric parts, optionally with a suffix (`"7.4.0"`, `"7.4.0.90"`,
  `"7.4.0.90-HF1"`). `--check-config` and `list_nodes` MUST show `V7_4` like the other line values;
  the load error texts that list the allowed values MUST name `V7_4`.
- **FR-002**: The version model MUST keep the fourth numeric part of a version as its own value
  (absent when the value has three parts), for configured, server-reported and client-reported
  versions alike, and MUST show it wherever the version is shown.
- **FR-003**: For 7.4 versions the client/server patch comparison MUST compare major, minor, patch
  and the fourth part: equal in all four is a match, a difference in any is a mismatch, and a
  fourth part present on one side only is "not comparable". For every other version line the
  comparison MUST stay as today; in particular the fourth part of a 6.1 version stays ignored
  (feature 012 FR-002). Where this rule applies MUST be declared per version range, not decided
  by version checks in tool logic (Constitution V).
- **FR-004**: The ordering and equality of versions used elsewhere (range membership, contradiction
  by major.minor, `cliClients` selection by major.minor) MUST NOT change.
- **FR-005**: `AUTO` MUST stay REST-based in Mandate A. For a node on `AUTO` whose detection
  request is answered with HTTP 404, the conditional hint of feature 012 FR-003 MUST become "if
  <node id> runs INUBIT 6.1, pin `V6_1`; if it runs INUBIT 7.4, pin `V7_4`". Every other detection
  failure keeps today's warning.

#### Mandate A — Capabilities and refusals

- **FR-006**: The production capability matrix MUST declare a range 7.4–7.4, StartCLI only (no REST
  channel), with every capability `unsupported`; its monitoring and process diagnosis channels MUST
  be StartCLI, so that Mandate B only flips their state. The `unverified` state (runs with the 8.1
  adapters) MUST NOT be used for 7.4.
- **FR-007**: Every tool that contacts a node MUST be refused for a 7.4 node with
  `UNSUPPORTED_VERSION` before any REST request, StartCLI run (including the local option and
  version probes of the client) or TLS handshake, naming the node, its version (`INUBIT 7.4`, not
  6.1), the missing capability and a next step. In calls addressing several nodes the refusal
  MUST be per node. The refusal MUST come before the client lookup, so a missing client never
  changes it.
- **FR-008**: `check_artifacts` with module lookup on a 7.4 node MUST run its offline checks and
  skip the lookup with a notice, without contact.
- **FR-009**: A tool whose only candidate nodes are 7.4 nodes MUST NOT be announced (feature 009).
- **FR-010**: `list_nodes` and `--check-config` MUST show a 7.4 node with its version (all four
  parts if configured so), the channel "StartCLI only" and no usable capability, without contacting
  the node.
- **FR-011**: The adapters selected for a 7.4 node MUST NOT build or use a REST client; every port
  of the 7.4 range refuses with `UNSUPPORTED_VERSION` as a second line of defence behind the gate.
- **FR-012**: Refusals on a 7.4 node MUST be logged like the version refusals of feature 009
  (structured, stderr).
- **FR-013**: FR-006 to FR-012 MUST hold identically when the MCP server runs on Windows, macOS and
  Linux, verified by the offline test jobs of each platform.

#### Mandate A — Fixtures and recording kit

- **FR-014**: A 7.4 fixture folder MUST exist with the case layout of the other versions
  (`<case>.stdout`, `.stderr`, `.exit`) and a README. Its only real case is the attached help
  output (`help`), stored with its exact text; the README states where it came from and that its
  exit code and standard error were not part of the attachment. Every other case is reconstructed
  (from the help output and the 6.1 cases) and listed with its capability in `RECONSTRUCTED.txt`.
- **FR-015**: A guard test MUST fail when the production matrix declares a 7.4 capability
  `supported` while any case of that capability is listed in `RECONSTRUCTED.txt`, and MUST fail
  when a case listed there has no files or a fixture case is neither listed nor known as recorded.
  The 7.4 fixtures MUST pass the customer identifier scan.
- **FR-016**: The Windows recorder MUST know these 7.4 cases, StartCLI only, read-only, each
  storing standard output, standard error and exit code:
  - `v74_help` (`-h`, local, no server) and `v74_version` (`-v`, local, no server);
  - `v74_exec_help` (`--execCommand "help"`) and `v74_exec_ps_help` (`--execCommand "ps -h"`);
  - `v74_direct_health` (the direct options `-c`, `-sv`, `--pctmem`, `--maintenanceState`, `-ne`,
    `-nx`, `-nw`, `-nq` in one run);
  - `v74_health_session` (one interactive session with the health commands of the 6.1 session:
    `uname`, `uptime`, `maintenance`, `free -m`, `ps -c -e`, `ps -c -w`, `ps -c -q`, `ps -c -a`);
  - `v74_ps_all`, `v74_ps_error`, `v74_ps_waiting`, `v74_ps_queued`, `v74_ps_limit5` (interactive
    sessions with one `ps` listing each);
  - `v74_failure` (a wrong URL, to record the connection error).
- **FR-017**: The recorder MUST gain an interactive-session mode: one StartCLI process per case,
  password on standard input only, the case's commands, then an end-of-session command; the
  environment allowlist, the group allowlist, the TLS arguments and the secret check of feature 013
  apply unchanged. It MUST refuse to start a 7.4 case unless the person has confirmed, in a
  parameter of that run, that the node is not classified `production`.
- **FR-018**: The recorder's 7.4 session commands MUST be editable in one place (the catalogue) so
  that the recording session can adjust them after `v74_exec_help` and `v74_exec_ps_help` without a
  code change elsewhere.
- **FR-019**: Windows recordings MUST keep their line ends and bytes in the repository (no line-end
  conversion for the 7.4 fixtures).

#### Mandate A — Regression and documentation

- **FR-020**: Behaviour for 6.1, 8.1 and 9.0 nodes MUST NOT change on any platform, apart from the
  extended hint of FR-005. Regression tests MUST pin, per version line, the version parsing, the
  patch comparison, the gate decision per capability and the refusal texts. The user documentation
  (setup, tool reference, README, research notes) and the changelog MUST describe 7.4 as
  "configurable and recognised, every tool refused until verified" in the same change.

#### Mandate B — Recordings

- **FR-021**: The 7.4 cases MUST be recorded read-only, by hand, with the Windows recorder, on a
  Windows machine with the 7.4 client and its JDK, against a non-production 7.4.0.90 node, after the
  owner has approved the recording in the chat. A node classified `production` MUST NOT be
  recorded. The recordings MUST be neutralized with the owner's local neutralize map before they
  enter the repository.
- **FR-022**: Each recorded case MUST replace its reconstructed counterpart and be removed from
  `RECONSTRUCTED.txt`. Where a recording shows that a reconstructed case does not exist on 7.4 (for
  example a command with another name), the case and every parser expectation built on it MUST be
  replaced, not kept.
- **FR-023**: The differences between 7.4 and 6.1 (options, interactive commands, output formats,
  password handling, Java runtime of the client) MUST be documented in the research notes from the
  recordings.

#### Mandate B — Health and processes

- **FR-024**: All 7.4-specific behaviour MUST live in adapters selected by the node's version; tool
  logic MUST NOT branch on version strings. The 7.4 adapters MAY reuse the 6.1 StartCLI adapters
  where the recordings show the same commands and formats.
- **FR-025**: The 7.4 parsers MUST be tested against the recorded 7.4 outputs, MUST parse every
  language and line-end variant the recordings show, and MUST treat unrecognized output as
  `UNEXPECTED_RESPONSE`, never as an empty result.
- **FR-026**: `get_health` on a 7.4 node MUST report, from StartCLI: `reachable`, `status` (`OK`
  when the alive check succeeds, `ERROR` when it answers negatively, `UNKNOWN` when it could not be
  obtained), the server version (all four parts), and of maintenance state, JVM memory and process
  counts per state (Error, Processing, Waiting, Queued) those the recordings show; every part a
  health report has on 8.1 and 7.4 does not provide MUST be listed as unavailable with the reason
  `NOT_AVAILABLE_ON_7_4`. The time budget of 6.1 (feature 012 FR-009: 15 seconds for one node, the
  overview budget of feature 001 for several) applies.
- **FR-027**: The version read by `get_health` on a 7.4 node MUST count as the node's observed
  version for the pin contradiction rule of feature 009, provided the recordings confirm that the
  option used reports the server's version.
- **FR-028**: `find_processes` on a 7.4 node MUST return the fields the recordings show (at least
  process id, workflow, state and time; module, owner and node where present), support the states
  and filters the recordings back, refuse every other input with `UNSUPPORTED_VERSION` naming the
  input, and keep the truncation and paging rules of 6.1.
- **FR-029**: StartCLI on a 7.4 node MUST use the 7.4 client configured in
  `defaults.cliClients."7.4"` (with its `javaHome`); no other client MUST be used against a 7.4
  node. On Windows it MUST use the feature 013 invocation (password through standard input only,
  environment allowlist, CRLF and code page handling). The server-side certificate pin check of
  feature 012 (TLS handshake before StartCLI, no HTTP request) applies, because the 7.4 client has
  no trust-store or host-name options.
- **FR-030**: A 7.4 capability MAY be declared `supported` only when none of its cases is listed in
  `RECONSTRUCTED.txt` (FR-015) and its contract tests run against the recordings (Constitution III,
  V).
- **FR-031**: `get_health` and `find_processes` on 7.4 MUST be declared `supported` only after a
  live check of that capability against the 7.4.0.90 node has passed both from Windows and from
  macOS or Linux, with the outcomes recorded in the quickstart. The capability matrix stays
  platform-independent: if one platform cannot be checked, the capability stays `unsupported`
  everywhere and the owner decides how to proceed.

#### Mandate B — Detection

- **FR-032**: For a node on `AUTO` with a `"7.4"` client configured whose REST detection fails with
  HTTP 404, the server MUST identify 7.4 by the detection step the recordings verify (default: the
  server version reported by the 7.4 client), and MUST treat the node as 7.4 only when that step
  reports major.minor 7.4. Any other outcome leaves the node unknown with the hint of FR-005.
- **FR-033**: Detection for 6.1, 8.1 and 9.0 nodes MUST stay exactly as before: no additional
  request reaches an 8.1 or 9.0 node, and a 6.1 node on `AUTO` stays unknown (feature 012).

### Key Entities

- **7.4 node**: a configured node pinned to INUBIT 7.4 (`V7_4` or a concrete 7.4 version), or in
  Mandate B detected as 7.4; reached through StartCLI only.
- **Version with fourth part**: major, minor, patch, an optional fourth numeric part and the raw
  text (suffix kept for display).
- **Patch comparison rule per range**: whether the fourth part counts for the client/server patch
  comparison; four parts for 7.4, three for every other range.
- **Capability matrix entry 7.4**: CLI-only, every capability `unsupported` in Mandate A; monitoring
  and process diagnosis `supported` in Mandate B after recordings and live checks.
- **7.4 fixture case**: recorded (the help output; all others after Mandate B) or reconstructed
  (listed in `RECONSTRUCTED.txt`).
- **7.4 recording case**: an entry of the Windows recorder's catalogue: local run, script-mode run,
  direct-option run or interactive session.

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
  the macOS/Linux and Windows test jobs, with only the tests of the FR-005 hint adjusted.
- **SC-006** (Mandate A): a person with the Windows machine and the 7.4 client can record every 7.4
  case of FR-016 in one sitting by following the quickstart, without editing code.
- **SC-007** (IMC-6 criterion 2 — Mandate B): `get_health` and `find_processes` return verified
  results for the real 7.4.0.90 node through the 7.4 client, from Windows and from macOS or Linux;
  `get_health` on one 7.4 node returns within 15 seconds, and listing up to 100 failed processes
  within 15 seconds.
- **SC-008** (IMC-6 criterion 1, detection — Mandate B): a 7.4 node on `AUTO` with a 7.4 client is
  detected as 7.4; no 8.1 or 9.0 node receives an additional request during detection.
- **SC-009** (Mandate B): no 7.4 capability is `supported` while one of its cases is reconstructed
  (guard test green with the production matrix).

## Out of Scope

- REST-based tools on 7.4, including any REST detection probe specific to 7.4.
- Write tools on 7.4: restart, kill, import, tag, deploy, backup, restore, delete, rename.
- Client-helper tools on 7.4 (queue entries, log download, node tools); the helper knows only 8.1
  and 9.0.
- Logs, inventory, workspace export and partner management on 7.4; further CLI commands follow as
  separate mandates once recordings exist.
- Other 7.x lines (7.3, 7.5) and a whole-major line value `V7_X`.
- Detecting 6.1 automatically.
- Recording on macOS or Linux with the bash recorder (the owner records on Windows only).

## Preconditions

- **Mandate A**: none.
- **Mandate B**:
  - a Windows machine with an INUBIT 7.4 Workbench client and its JDK, CLI login to a
    non-production 7.4.0.90 node, and a person who runs the Windows recorder there (with the local
    neutralize map) and the live check of the MCP server on that machine;
  - for the macOS / Linux half of FR-031: a macOS or Linux machine with a copy of the 7.4 client and
    its JDK and network access to the same node;
  - the constitution amendment that names INUBIT 7.4 as an additionally supported, read-only,
    CLI-only version under Principle V, ratified before the first 7.4 capability is declared
    `supported`.

## Assumptions

- The attached help output is that of a 7.4.0.90 client (the patch the owner targets); the text
  itself does not name a version. It was produced by `startcli.sh` on Linux; Windows `-h` output may
  differ in line ends only.
- The interactive commands and their output on 7.4 are close to 6.1 (the direct options are);
  reconstructed cases start from the 6.1 cases and the help output, and the recordings decide.
- The 404 hint names both pins (FR-005) because a 404 alone cannot tell 6.1 from 7.4 without a 7.4
  recording; Mandate B narrows it through detection.
- Keeping the fourth part only for 7.4 comparisons (FR-003) satisfies IMC-6 criteria 4 and 5
  together: 8.1 and 9.0 versions have three parts, and feature 012 deliberately ignores the fourth
  part on 6.1.
- A 7.4 node is reached with the StartCLI URL `<server>/ibis/servlet/IBISSoapServlet`, as the help
  output's default shows.
- The 7.4 client needs Java 11 or older; the `"7.4"` entry of `defaults.cliClients` names its JDK.

## Open Questions (owner)

- **Why** (IMC-6): which installations and how many 7.4 nodes — accepted as open at I2; it decides
  no requirement of this spec.
- **macOS / Linux live check** (FR-031): can a copy of the 7.4 client run on a macOS or Linux machine
  with network access to the 7.4.0.90 node? If not, criterion 2 cannot be met in full, and the owner
  decides whether a Windows-only live check suffices before 7.4 health and processes are enabled.
- **Constitution amendment** (Preconditions): the owner ratifies the Principle V amendment before
  Mandate B declares the first 7.4 capability `supported`.
