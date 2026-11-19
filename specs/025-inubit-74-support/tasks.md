---

description: "Task list for INUBIT 7.4 support, CLI only, cut into Mandate A and Mandate B (feature 025, IMC-6)"
---

# Tasks: INUBIT 7.4 Support, CLI Only, Cut into Two Mandates

**Input**: Design documents from `/specs/025-inubit-74-support/`

**Prerequisites**:
- [plan.md](plan.md) and [spec.md](spec.md) (spec reviews r1–r2 approved; plan reviews r1–r3, approved in r3)
- [research.md](research.md) (D-1…D-15) and [data-model.md](data-model.md)
- [contracts/configuration-delta.md](contracts/configuration-delta.md) and
  [contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md)
- [quickstart.md](quickstart.md)
- [evidence/startcli-7.4-help.txt](evidence/startcli-7.4-help.txt): the IMC-6 attachment
  (StartCLI `-h` of a 7.4 node, LF line ends, SHA-256
  `c7b04fd434d7e36127ca6ae32bbaa363c244c478ced625d5f64e6637d1f6416e`)
- constitution v1.1.0; the code of features 001–024 (branch based on `main` 0.10.2)

**Tests**: REQUIRED (Constitution III, TDD). Every task starts with the test(s) it names, observed
failing on an assertion before the production code exists, and ends with `mvn -q -o verify` green.
Each task contains its own tests; there are no separate "write failing tests" tasks. Tasks marked
"verification task" cover behaviour that earlier tasks or existing code already provide, so their
tests may pass at once.

**Organization**: two clearly separated groups.

- **Mandate A** (Phases 1–8, T001–T025): needs no 7.4 node. Implementable and mergeable on its own.
- **Mandate B** (Phases 9–12, T026–T037): needs a 7.4 node. **Blocked** until a Windows machine
  with an INUBIT 7.4 Workbench client and its JDK and a non-production 7.4.0.90 node are available
  (spec, Preconditions). Its implementation tasks are placeholders that T029 replaces with detailed
  tasks after the recordings (spec, "Planning depth").

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on unfinished tasks)
- **[Story]**: US1 pinned 7.4 refused, US2 404 hint, US3 four-part patch, US4 recording kit,
  US5 regression, US6 health (B), US7 processes (B), US8 detection (B)

## Path Conventions

| Short form | Expands to |
|---|---|
| `main/…` | `src/main/java/de/dadecker/inubit/mcp/…` |
| `test/…` | `src/test/java/de/dadecker/inubit/mcp/…` |
| `res/…` | `src/main/resources/…` |
| `fixtures/…` | `src/test/resources/fixtures/…` |

## Safety rules

- **Mandate A**: run no INUBIT command and no network call against any INUBIT server; all tests are
  offline (WireMock, `FakeProcessLauncher` / `ScriptedProcessLauncher`, the TLS handshake fake of
  feature 012). The recorder is changed and tested, never run against a server.
- **Mandate B**: the only live steps are the recording session and the live checks, run by hand on
  a **non-production** 7.4.0.90 node after the owner's approval in the chat; never a node classified
  production (spec FR-021).
- **Fixtures**: reconstructed 7.4 cases are neutral (`legacy74/node1`, owner `jdoe`, host
  `inubit-legacy74-1.example.test`); recorded cases are neutralized by the recorder with
  `tools/neutralize.py`. The identifier hooks must pass; never use `--no-verify`.
- **Configuration**: never change `~/.config/inubit-mcp/*` or the MCP registration.
- **Public repository**: no customer names in commit messages, PR descriptions or files.
- **Other features**: do not touch the branch of feature 022 (IMC-2) or its files.

---

# Mandate A — no 7.4 node needed

## Phase 1: Setup

- [X] T001 Fixture scaffold (research D-11): create `fixtures/v7_4/cli/README.md` (case layout
  as `fixtures/v6_1/cli/README.md`; source "reconstructed from the 7.4 help output and the 6.1
  cases until recorded"; neutral names of the safety rules; a section `## Recorded cases` with one
  case per line, the single source for recorded cases, spec FR-015), `fixtures/v7_4/cli/RECONSTRUCTED.txt`
  (format `<case> <capability>` per line, `#` comments, header as the 6.1 file) and
  `fixtures/v7_4/cli/DERIVED.txt` (format `<case> <source-case> <capability> <edit…>`, comments only).
  Test first: `test/adapter/cli/V74FixturesShapeTest` (pattern `V61FixturesShapeTest`) fails while
  the folder or a file is missing; it checks the README, the line formats of both lists, that every
  listed case has its three files, and that every `<case>.stdout` in the folder is in exactly one of
  `RECONSTRUCTED.txt`, `DERIVED.txt` and the README's recorded list.
- [X] T002 The real help case (after T001: same README and test) (spec FR-014, FR-019): copy
  `specs/025-inubit-74-support/evidence/startcli-7.4-help.txt` byte for byte to
  `fixtures/v7_4/cli/help.stdout`; `help.exit` = `0`, `help.stderr` empty; README: provenance
  ("IMC-6 attachment, `startcli.sh -h` of a 7.4 node on Linux; exit code and stderr assumed"),
  recorded list line `help attachment`. Add `src/test/resources/fixtures/v7_4/** -text` to
  `.gitattributes`. Test first: a `V74FixturesShapeTest` case asserting the SHA-256 of
  `help.stdout` (`c7b04fd4…416e`, see Prerequisites) and that `help` is not in
  `RECONSTRUCTED.txt`; a `test/tools/RecordFixturesPs1Test` case asserting the `-text` rule for
  `fixtures/v7_4/**` (pattern `gitKeepsTheWindowsRecordingsByteForByte`).

---

## Phase 2: Foundational (blocks every Mandate A story)

- [X] T003 Regression baseline (research D-13, spec FR-020, SC-005; verification task, written
  against the unchanged code): `test/application/VersionLineRegressionTest` (in `application`,
  because it uses the package-private `VersionGate` helpers), parameterised over
  `V6_1`, `V8_1`, `V9_0`, `V9_X` and the pins `"6.1.0.89"`, `"8.1.17"`, `"9.0.10"`: the parsed
  `InubitVersion` (major, minor, patch, raw), `ClientVersion.compare` for equal, patch-different and
  patch-absent pairs (`6.1.0.89` vs `6.1.0.80` = `MATCH`), `CapabilityMatrix.production().state`
  for every capability, `isCliOnly`, the refusal summary texts of `VersionGate` for one refused
  capability per line, and a `QUEUE_ENTRY_READ` refusal on `V6_1`, on a pin outside every range
  (`"8.0.5"`) and on `V9_X`, each ending in "; supported: 8.1, 9.0" (the `supportedLines` texts). It must pass now and stay unchanged
  through T004–T015.
- [X] T004 Line value `V7_4` (research D-1, spec FR-001): `main/domain/model/VersionSetting.java`
  (`V7_4 = new Line(7, OptionalInt.of(4))`, `parse`, Javadoc), `main/config/ConfigLoader.java:807`
  (error text "expected AUTO, V6_1, V7_4, V8_1, V9_0, V9_X or a full version such as \"8.1.17\""),
  `main/domain/model/NodeSummary.java:22` (doc), `res/schemas/list_nodes.output.json:63,117`
  (descriptions). Test first: `VersionSettingTest` (`V7_4` parses to `Line(7, 4)`, `text()` =
  `V7_4`; `"7.4.0"`, `"7.4.0.90"`, `"7.4.0.90-HF1"` are `Pinned`; `"V7_X"` is refused),
  `ConfigLoaderTest` (the new error text; the existing assertion of the old text is the deliberate
  change of SC-005).
- [X] T005 Fourth version part (research D-2, spec FR-002, FR-004): `main/domain/model/InubitVersion.java`
  gets `OptionalInt build` ("present only if `patch` is present and the value has a fourth numeric
  part"), the regex `^(\d{1,9})\.(\d{1,9})(?:\.(\d{1,9})(?:\.(\d{1,9}))?)?(?!\d)`, and a kept
  four-argument constructor (build empty); `compareTo` and `sameMinor` unchanged. Test first:
  `InubitVersionTest` — `"7.4.0.90"` → build 90, raw `7.4.0.90`; `"7.4.0"` → build empty;
  `"6.1.0.89-HF1"` → build 89; `"8.1.17"` → build empty; `"7.4.0.90"` and `"7.4.0.80"` compare as
  equal (`compareTo` = 0); `"7.4.0.90-HF1"` → build 90, raw `7.4.0.90-HF1`; `"7.4.0.90.5"` →
  build 90, the rest only in raw.
- [X] T006 Patch depth (research D-3, spec FR-003, data-model "ClientVersion.Comparison"): new
  `main/domain/model/PatchDepth.java` (`THREE`, `FOUR`); `main/domain/model/ClientVersion.java`
  gets `compare(server, PatchDepth)`, the existing `compare(server)` delegates with `THREE`. Test
  first: `ClientVersionTest`, every row of the data-model table with `FOUR` (`7.4.0.90`/`7.4.0.90`
  MATCH, `7.4.0.80`/`7.4.0.90` MISMATCH, `7.4.0`/`7.4.0.90` NOT_COMPARABLE, `7.4.0`/`7.4.1.5`
  MISMATCH, `7.4.0`/`7.4.0` MATCH, patch absent NOT_COMPARABLE) and with `THREE`
  (`6.1.0.89`/`6.1.0.80` MATCH).
- [X] T007 Matrix entry 7.4 (research D-4, spec FR-006, data-model "CapabilityMatrix.Entry"):
  `main/domain/model/CapabilityMatrix.java` — `Entry` gets `PatchDepth patchDepth` (default
  `THREE`, existing constructors keep it), the production entry `V7_4` (range 7.4–7.4, no
  `SUPPORTED` state, channels MONITORING and PROCESS_DIAGNOSIS = {CLI}, `cliOnly = true`, note "not
  yet verified" on every `Capability`, `FOUR`), `PRODUCTION = List.of(V6_1, V7_4, V8_1, V9_0)`, and
  the queries `patchDepth(Optional<InubitVersion>)` (`THREE` for unknown and out-of-range),
  `comparePatch(ClientVersion, Optional<InubitVersion> server)` (= `client.compare(server,
  patchDepth(server))`, the one entry point for patch comparisons),
  `cliOnlyLines()` (`["6.1", "7.4"]`) and `allNotYetVerified(Optional<InubitVersion>)`; `lines(capability)`
  (`:199-206`) ignores the notes of an entry on which every capability carries a note, so 7.4 never
  appears in "; supported: …" (research D-4); class Javadoc of `production()` extended. Test first:
  `CapabilityMatrixTest` — every capability on 7.4 is `UNSUPPORTED` with the note, `isCliOnly(7.4)`,
  channels, `patchDepth` per line, `cliOnlyLines`, `allNotYetVerified` true for 7.4 and false for
  6.1, 8.1, 9.0, `lines(QUEUE_ENTRY_READ)` and `lines(IO_LOG_READ)` without 7.4;
  `VersionLineRegressionTest` still green.

**Checkpoint**: the version model knows 7.4; nothing else uses it yet.

---

## Phase 3: User Story 1 — a pinned 7.4 node is known and every tool refuses clearly (P1) 🎯 MVP

**Goal**: spec US1, FR-006 – FR-013. **Independent test**: `V74RefusalTest` plus the `list_nodes`
and `--check-config` tests, offline, on both CI jobs.

- [X] T008 [US1] Refusing 7.4 port set (research D-5, spec FR-011): new
  `main/adapter/cli/v74/V74PortSet.java` (`RANGE` 7.4–7.4; `factory` → `RefusingPorts` for every
  port; `cliHealth` → a `CliHealthPort` that throws the same refusal); `main/adapter/RefusingPorts.java`
  takes the release and the likely cause (message "(INUBIT <release>, StartCLI only)"; likely
  cause for 7.4 "INUBIT 7.4 is recognised, but no tool is verified on it yet"; both 6.1 texts
  unchanged, including "INUBIT 6.1 is supported read-only through StartCLI, for a few reads only");
  `main/Wiring.java:474-476` registers 7.4 after 6.1 with `V61QueryRules.INSTANCE`. Test first:
  `test/adapter/cli/v74/V74PortSetTest` (every port and the health port refuse with
  `UNSUPPORTED_VERSION` naming the node and "INUBIT 7.4", and the 7.4 likely cause), an `AdapterGatewayFactoryTest` case (a
  node pinned `V7_4` gets `NodeAccess.CLI_ONLY` and no REST client is built), and the 6.1
  `RefusingPorts` text assertion unchanged.
- [X] T009 [US1] Refusal text on 7.4 (research D-6, spec FR-007, contracts/mcp-tools-delta.md):
  in `main/application/VersionGate.java` `refuseUnsupported`, the note branch (`:878-885`) keeps its
  message for every version and, only when the version is CLI-only, (a) uses the next step
  "INUBIT 7.4 is recognised, but no tool is verified on it yet; use the INUBIT Workbench for
  `<label>` on `<node>`" (release from the version, not a literal) and (b) for INVENTORY appends
  "; modules of INUBIT <release> cannot be looked up" as the CLI-only branch does (`:893-894`).
  Expected message: `INUBIT 7.4 on legacy74/node1: monitoring is not yet verified for INUBIT 7.4;
  nothing was sent` for `get_health`, which names no tool (as on 6.1); every other tool's message
  starts with `<tool>: ` (IMC-14, option 1) (`INUBIT 7.4.0.90 on …` for the concrete pin). Test first:
  `VersionGateTest` cases for a `V7_4` and a `"7.4.0.90"` pin (read, write, monitoring, inventory
  suffix); the 6.1, 8.1 and 9.0 refusal texts in `VersionLineRegressionTest` unchanged.
- [X] T010 [US1] Per-tool refusal without contact (spec FR-007, FR-012, FR-013, SC-003):
  `test/application/V74RefusalTest` (pattern `V61RefusalTest`): one case per tool that contacts a
  node (every tool registered in `Wiring` except `list_nodes`, `check_artifacts`,
  `read_knowledge`, `record_knowledge`) against a profile with a `V7_4` node, asserting
  `UNSUPPORTED_VERSION`, the texts of T009, zero process launches (`FakeProcessLauncher` with no
  case), zero HTTP requests (WireMock `verify(0, anyRequestedFor(anyUrl()))`), zero TLS handshakes,
  and the structured refusal log record; a group with an 8.1 and a 7.4 node for `get_health` and
  `find_processes` (8.1 answered, 7.4 refused in its own entry); a 7.4 node without any client gets
  the same refusal; a write tool with writes enabled and with writes disabled gets the same
  outcome as the corresponding 6.1 case in `V61RefusalTest`. Fix production code only if a case
  fails (expected: none beyond T007–T009).
- [X] T011 [P] [US1] `check_artifacts` on 7.4 (spec FR-008; verification task): an
  `ArtifactCheckServiceTest` case — module lookup on a `V7_4` node runs the offline checks and
  returns the gate's refusal message as notice, which contains "modules of INUBIT 7.4 cannot be
  looked up" (T009 (b), `ArtifactCheckService.java:528-535`), no contact.
- [X] T012 [P] [US1] Announcement (spec FR-009; verification task): a `test/ToolAnnouncementTest`
  case — a profile whose only nodes are pinned `V7_4` announces only `list_nodes`, `get_health`,
  `read_knowledge`, `record_knowledge` and `check_artifacts`; no other tool (reads, opt-in, write,
  log-level; development, deployment and e2e in `test/DevelopmentWiringTest`). `get_health` stays
  announced under the unchanged feature 009/012 rule (FR-009; review T012-r1).
- [X] T013 [US1] Display (spec FR-010, SC-001, contracts/mcp-tools-delta.md "list_nodes entry"):
  `ListNodesToolTest` and `ConfigSummaryTest` cases for `V7_4` and `"7.4.0.90"` (version shown with
  all four parts for the pin, `access: cli_only`, no usable capability, the
  summary "(INUBIT 7.4, StartCLI only; available: none; refused: …)"); change
  `main/application/NodeOverview.java` / `main/config/ConfigSummary.java` only if a case fails.
- [X] T014 [US1] Client configuration and display for 7.4 (research D-9, spec FR-010a,
  contracts/configuration-delta.md, contracts/mcp-tools-delta.md "list_nodes entry"):
  `main/config/ConfigValidator.java:779-786` gives the "reached through StartCLI only, but no
  cliHome" warning only when `!matrix.allNotYetVerified(pin)`, with the release taken from the pin
  ("INUBIT <major.minor> is reached through StartCLI only …"; 6.1 text unchanged);
  `main/config/ConfigSummary.java` (`client`, the `--check-config` text line) appends "; not used until
  a 7.4 capability is verified" for a node whose version is `allNotYetVerified`;
  `main/application/NodeOverview.java` adds to the node's `warnings` list of `list_nodes` (no schema
  change; `res/schemas/list_nodes.output.json` `warnings` is a free string array) the entry
  "INUBIT 7.4 is recognised, but no tool is verified on it yet; the StartCLI client of <node> is not
  used until a 7.4 capability is verified" (release from the version). Test first:
  `ConfigValidatorTest` (7.4 pin without client: no warning; 6.1: warning unchanged;
  `defaults.cliClients."7.4"` accepted; no JDK major warning for it), `ConfigSummaryTest` (client
  line with the suffix), `ListNodesToolTest` (the warning entry for `V7_4` with and without a
  client; none for 6.1, 8.1, 9.0) — these run on every CI job (FR-013) — and `MainTest`
  (`--check-config` builds its own `SystemProcessLauncher`, `Launcher.java:415`; with a POSIX
  `startcli.sh` stand-in that records its arguments, as `MainTest` does today, disabled on Windows:
  a `"7.4"` entry runs `-v`, shows a "not evaluable" result and the suffix, contacts no node).
- [X] T015 [US1] No `-h` option probe while 7.4 is not verified (research D-9, spec FR-010a,
  FR-007): `main/adapter/cli/CliClientProbe.java` `optionsFor` (`:75-79`) returns empty while
  `matrix.allNotYetVerified(pin)` (as for a non-CLI-only node), so `--check-config`
  (`Launcher.java:420-441`) runs no `-h` for a 7.4 node and neither `ConfigValidator.clientOptionWarnings`
  (`:1511-1535`) nor `ConfigSummary` show option warnings or an option line. Test first:
  `CliClientProbeTest` (7.4 pin: empty, no launch; 6.1 pin: unchanged; runs on every CI job),
  `ConfigValidatorTest` (no client option warning for a 7.4 node), `MainTest` (POSIX stand-in as in
  T014: the recorded arguments contain no `-h`).

**Checkpoint**: US1 complete — a pinned 7.4 node is safe on every platform.

---

## Phase 4: User Story 2 — a failed detection points to 7.4 as well (P2)

**Goal**: spec US2, FR-005, SC-002.

- [X] T016 [US2] 404 hint (research D-7): `main/application/VersionGate.java` `unknown`
  (`:1004-1011`) stays static and takes `List<String> cliOnlyLines`; it builds "; if <node> runs
  INUBIT 6.1, pin V6_1; if it runs INUBIT 7.4, pin V7_4" from them. Both callers pass
  `matrix.cliOnlyLines()`: inside `VersionGate` and `main/application/NodeOverview.java:194`
  (`list_nodes`). Test first: `VersionGateTest` — a detection failure with `endpointMissing` gives
  the new text; timeout, TLS error, 401, 500 and an unparsable answer give the unchanged text; a
  `ListNodesToolTest` case for an `AUTO` node whose detection got a 404 shows the new hint; adjust the
  existing 6.1 hint assertions and static callers (deliberate change of SC-005, list them in the
  commit message).

---

## Phase 5: User Story 3 — the patch level of a four-part 7.4 version is kept (P2)

**Goal**: spec US3, FR-003, SC-004.

- [X] T017 [US3] Patch depth at every comparison (research D-3): replace every
  `ClientVersion.compare(...)` call by `matrix.comparePatch(...)` — `main/application/VersionGate.java:819`
  (`checkClient`), `main/config/ConfigSummary.java:408` ("client vs pin"),
  `main/application/NodeOverview.java:145` and `:250` (`list_nodes` client warning and line). The
  static `ConfigSummary.client` (`:398`) gets the `PatchDepth` as a parameter from its caller, which
  holds the matrix. `main/application/HealthService.java:686` (no matrix there, no visible effect in
  Mandate A) is changed in T031 through a `VersionGate.comparePatch` that delegates to the gate's
  matrix. Test first: `ConfigSummaryTest` and `ListNodesToolTest`
  — a 7.4.0.80 client at a `"7.4.0.90"` pin is a mismatch, a 6.1.0.80 client at a `"6.1.0.89"` pin a
  match; and
  `VersionGateTest` with a test matrix in which 7.4 monitoring is `SUPPORTED` (pattern
  `CapabilityExtensionTest`, never the production matrix): a 7.4.0.80 client at a 7.4.0.90 server
  gives the patch-mismatch warning of feature 009; 7.4.0.90/7.4.0.90 gives none; a 6.1.0.80 client
  at a 6.1.0.89 server gives none (fourth part ignored, as today).

---

## Phase 6: User Story 4 — the recording kit for 7.4 is ready on Windows (P2)

**Goal**: spec US4, FR-014 – FR-019, SC-006. **Independent test**: `V74FixturesShapeTest`,
`V74RecordedFixturesTest`, `V74HelpContractTest`, `RecordFixturesPs1Test`, `tools\record-fixtures.ps1 -List`.

- [X] T018 [P] [US4] Reconstructed 7.4 cases (research D-11, spec FR-014 table): create
  `fixtures/v7_4/cli/<case>.stdout/.stderr/.exit` for `health_session_ok`,
  `health_session_maintenance`, `health_session_alive_failed`, `login_failed`, `unreachable`,
  `ps_session_all`, `ps_session_error`, `ps_session_waiting`, `ps_session_queued`,
  `ps_session_limit5`, `ps_session_empty`, `ps_session_unassigned`, each built from the 6.1 case of
  the same purpose in `fixtures/v6_1/cli/` (`ps_session_waiting` and `ps_session_queued` by splitting
  `ps_session_waiting_queued`; `ps_session_limit5` by cutting `ps_session_all` to five rows) with the
  neutral 7.4 names and the server version
  `7.4.0.90`; list each in `RECONSTRUCTED.txt` with its capability (`monitoring` or
  `process_diagnosis`) and a `#` comment naming the recorder case that replaces it (spec FR-014
  table). Test first: `V74FixturesShapeTest` — in Mandate A the folder holds exactly these twelve
  plus `help` (the recorded research cases of Mandate B go into the README's recorded list and are
  allowed then).
- [X] T019 [P] [US4] Guard (spec FR-015, FR-016a, FR-030): `test/adapter/cli/V74RecordedFixturesTest`
  (pattern `V61RecordedFixturesTest`): fails if `CapabilityMatrix.production()` declares a 7.4
  capability `SUPPORTED` while (a) a case of it is in `RECONSTRUCTED.txt`, (b) a case of it is in
  `DERIVED.txt` with a source case not in the README's recorded list, or (c) the README's recorded
  line for `help` does not name `v74_help` as its source. Self-tests with a flipped test matrix for
  (a), (b) and (c) use their own synthetic lists in a temporary directory (the guard's list reading
  takes a folder), so T019 does not depend on T018.
- [X] T020 [P] [US4] Contract test of the real help case (research D-11; after T002, T007 and
  T015): `test/adapter/cli/v74/V74HelpContractTest` — `main/adapter/cli/CliClientProbe.java` with a
  **test** matrix whose 7.4 entry is CLI-only without notes (so `optionsFor` runs `-h`), fed with
  `FakeProcessLauncher.replaying("v7_4", "help")`, reports no trust-store option and no
  host-name-verification option (input for spec FR-029 and feature 012 FR-012a), and the help text
  contains `--execCommand`. No production change expected; if the parser cannot read the 7.4
  format, report it as a finding instead of changing the parser (the 7.4 client path is Mandate B).
- [ ] T021 [US4] Recorder case table (research D-12, data-model "Recorder case"):
  `tools/record-fixtures.ps1` — replace the `$Cases` hashtable (`:87-94`) by a table of case objects
  (`Name`, `Description`, `Kind`, `Arguments`/`Commands`, `Credentials`, `Contacts`, `Folder`,
  `FixtureName`); the six `win_*` cases keep exactly their behaviour, output names and folder
  `fixtures/windows/`. Test first: `test/tools/RecordFixturesPs1Test` — the existing cases stay
  green (`listsAllSixReadOnlyCases` becomes "lists every case of the table"), plus a check that
  every case's arguments and commands are defined only in the table.
- [ ] T022 [US4] Recorder session mode (research D-12, spec FR-017): new function
  `Invoke-StartCliSession` in `tools/record-fixtures.ps1` — one `startcli.bat` process through the
  existing `Invoke-StartCli` (`cmd.exe /d /v:off /s /c`, allowlisted environment), standard input =
  password line, the case's commands, the case's end-of-session command; raw output in the private
  directory, path/host replacement, `neutralize.py`, `Assert-NoSecret` as for every case. Test
  first: `RecordFixturesPs1Test` static checks (session input only through stdin, never `-p`,
  never `--encryptString`, `Assert-NoSecret` on session output) and, on the Windows CI job, a run
  test of a session case against the substitute client of `recordsTheClientVersionThroughTheSubstitute`.
- [ ] T023 [US4] Recorder 7.4 catalogue (research D-12, spec FR-016 – FR-018, quickstart): the
  cases `v74_help`, `v74_version` (local), `v74_exec_help` (`--execCommand "help"`),
  `v74_exec_ps_help` (`--execCommand "ps -h"`), `v74_direct_health` (`-c -sv --pctmem
  --maintenanceState -ne -nx -nw -nq`), `v74_health_session` (`uname`, `uptime`, `maintenance`,
  `free -m`, `ps -c -e`, `ps -c -w`, `ps -c -q`, `ps -c -a`, then `exit`), `v74_ps_all`
  (`ps -a -l 10000`), `v74_ps_error` (`-e`), `v74_ps_waiting` (`-w`), `v74_ps_queued` (`-q`),
  `v74_ps_limit5` (`ps -a -l 5`), `v74_login_failed` (new parameter `-FailedLoginUser`, default
  `mcp-record-no-such-user`, and the dummy password of `win_failure`), `v74_failure` (`uptime`
  against `$FailureUrl`), folder `fixtures/v7_4/cli/`, fixture names per the spec FR-014 table
  (research cases keep their `v74_` name). Every case that contacts a server calls
  `Assert-AllowedGroup` and `Assert-AllowedHost` before any launch; for `v74_*` cases
  `Get-TlsArguments` is not used: `-TrustStore` goes into `JAVA_TOOL_OPTIONS` as
  `-Djavax.net.ssl.trustStore=<path>` (and the type from the extension), and
  `-DisableHostNameVerification` stops the run; header help and `-List` describe the 7.4 cases.
  Test first: `RecordFixturesPs1Test` — the 7.4 cases are listed with descriptions, use StartCLI
  only (no `Invoke-WebRequest`, no `curl`), check both allowlists before launch, never pass
  `--trustStoreFilePath` or `--disableHostNameVerification`, stop on `-DisableHostNameVerification`,
  and write to `fixtures/v7_4/cli/` under the mapped names.

**Checkpoint**: SC-006 — the recording session needs no code change.

---

## Phase 7: User Story 5 — 6.1, 8.1 and 9.0 behave exactly as before (P1)

**Goal**: spec US5, FR-020, SC-005.

- [ ] T024 [US5] Regression verification (verification task): `VersionLineRegressionTest`
  unchanged since T003 and green; the full suite green with `mvn -q -o clean verify`; the CI jobs
  for macOS/Linux and Windows green on the PR; `NoCustomerIdentifiersTest` run locally with the
  identifier lists. Record in [quickstart.md](quickstart.md) (validation record) every existing
  test that was adjusted and why: the 404 hint texts and static `unknown` callers (T016), the
  `versionLine` lists (T004), the `RefusingPorts` constructor (T008), the production entries in
  `CapabilityMatrixTest` (T007); any other adjustment is a regression to fix, not to record.

---

## Phase 8: Polish — documentation and changelog (Mandate A)

- [ ] T025 Docs and changelog (research D-14, spec FR-020): `docs/setup.md` (requirements row,
  versionLine table with `V7_4`, 404 hint, new section "INUBIT 7.4 nodes (`V7_4`)": recognised,
  every tool refused until verified, pin instead of `AUTO`, `defaults.cliClients."7.4"` not used
  yet; `--check-config` summary), `docs/tools.md` (capability table with a 7.4 column, hint, refusal
  text, version section), `README.md` (supported versions), `docs/research/inubit-interfaces.md`
  (a 7.4 section: facts of the help output, what is unknown, the recording plan),
  `CHANGELOG.md` `[Unreleased]` (Added: INUBIT 7.4 recognised by pin and refused clearly; the
  Windows recorder session mode and 7.4 cases. Changed: the 404 hint names `V7_4`; the fourth
  version part is kept). Test first: none (docs); the doc-link checks of the build stay green.

**Mandate A complete**: IMC-6 criteria 1 (pin), 3, 4 and 5.

---

# Mandate B — needs a 7.4 node (BLOCKED)

**Blocked until** (spec, Preconditions): a Windows machine with an INUBIT 7.4 Workbench client and
its JDK; CLI login to a **non-production** 7.4.0.90 node; a person who runs the recorder there with
the local neutralize map; for the macOS / Linux half of the live check, a macOS or Linux machine
with a copy of the 7.4 client and network access to the node; the owner's approval in the chat.

## Phase 9: Recording session (manual, approved)

- [ ] T026 [US6] Preconditions and approval: confirm in the chat with the owner the machine, the
  client version (`v74_version` must report 7.4), the node and its non-production classification;
  record them (without host names) in [quickstart.md](quickstart.md). No recording before the
  owner's approval.
- [ ] T027 [US6] Research recordings (quickstart steps 1–3): run `v74_help`, `v74_version`,
  `v74_exec_help`, `v74_exec_ps_help`, `v74_direct_health` with `tools/record-fixtures.ps1`;
  compare the interactive commands and `ps` options with the catalogue and adjust the 7.4 entries
  of the catalogue (one place, spec FR-018) before the session cases; note every adjustment in
  the quickstart. The research recordings are stored in `fixtures/v7_4/cli/` under their `v74_`
  names and added to the README's recorded list (they back no capability). Stop and ask the owner if the client reads the password from the console instead
  of standard input.
- [ ] T028 [US7] Session and failure recordings (quickstart step 4): `v74_health_session`,
  `v74_ps_all`, `v74_ps_error`, `v74_ps_waiting`, `v74_ps_queued`, `v74_ps_limit5`,
  `v74_login_failed`, `v74_failure`; replace each reconstructed case in `fixtures/v7_4/cli/`
  (spec FR-022), remove it from `RECONSTRUCTED.txt`, add it to the README's recorded list (and
  `help` with source `v74_help`); move the derivable cases to `DERIVED.txt` with source and edit
  (spec FR-016a); run `NoCustomerIdentifiersTest`; commit.

## Phase 10: Re-clarification from the recordings

- [ ] T029 Research addendum and Mandate B update (spec FR-023, "Planning depth"; research D-15):
  document the differences 7.4 vs 6.1 in `docs/research/inubit-interfaces.md` and a research
  addendum in `specs/025-inubit-74-support/research.md`; then update `spec.md` (Mandate B
  requirements as the recordings fix them), `plan.md` and this `tasks.md` (replace T031–T035 by
  detailed TDD tasks: adapters or parser profiles, script mode vs session, client selection,
  detection trigger; revisit what switches at the first flip because it is keyed on "every 7.4
  capability carries a note": the next-step text of T009, the `lines()` exclusion of T007 (7.4 must
  not appear in "; supported: …" for capabilities out of scope on 7.4), the warning of T014 and the
  `-h` gate of T015; decide what a remaining "not yet verified" note means on 7.4, research D-15), each reviewed with `d4ld4r-speckit-duo:review-spec` / `review-plan` before any
  Mandate B code is written.
- [ ] T030 Constitution amendment (plan, Constitution Check V): propose with `/speckit-constitution`
  a MINOR amendment of `.specify/memory/constitution.md` Principle V naming INUBIT 7.4 as supported
  read-only, StartCLI only, because its REST interface is unverified; the owner ratifies it before
  T035.

## Phase 11: Implementation (placeholders, detailed by T029)

- [ ] T031 [US6] Health on 7.4 (spec FR-024 – FR-027): `main/application/HealthService.java:686`
  through `VersionGate.comparePatch` (four-part patch comparison of the observed version, with a
  `HealthServiceTest` case); 7.4 health adapter or 6.1 adapter profile in `main/adapter/cli/v74/`, parsers tested against the recordings, `NOT_AVAILABLE_ON_7_4` parts
  in `main/domain/model/UnavailablePart.java`, observed version from `-sv` for the contradiction
  rule.
- [ ] T032 [US7] Processes on 7.4 (spec FR-028): 7.4 process query adapter in
  `main/adapter/cli/v74/`, required fields, hanging and time range per the recordings, refused
  inputs, 6.1 budget.
- [ ] T033 [US6] Client selection (spec FR-029): `main/adapter/cli/CliClientSelector.java` — no fallback
  to the version-less `defaults.cliHome` for 7.4 (a matrix flag, not a version check); the chosen
  client's `-v` must report 7.4, else `CLI_UNAVAILABLE`; feature 012 FR-012 / FR-012a for the
  7.4 client.
- [ ] T034 [US8] Detection (spec FR-032, FR-033): the detection step and trigger fixed by T029 from
  the live observation; no additional request to nodes whose REST detection succeeds; 6.1 stays
  unknown.

## Phase 12: Live checks, enablement, docs

- [ ] T035 [US6] Live checks and enablement (spec FR-030, FR-031, SC-007 – SC-009): run the live
  check of `get_health`, `find_processes` and `AUTO` detection against the 7.4.0.90 node from
  Windows and from macOS or Linux; record the outcomes and the detection failure code of the
  `AUTO` node in [quickstart.md](quickstart.md); only after both pass and T030 is ratified, flip
  MONITORING and PROCESS_DIAGNOSIS on 7.4 to `SUPPORTED` in `main/domain/model/CapabilityMatrix.java`
  and remove their "not yet verified" notes (the `V74RecordedFixturesTest` guard must stay green).
  If one platform cannot be checked, stop and let the owner decide (spec FR-031).
- [ ] T036 Docs and changelog for Mandate B (spec FR-034): `docs/setup.md` (`defaults.cliClients."7.4"`
  with its JDK, client precedence for 7.4), `docs/tools.md` (7.4 health and processes),
  `README.md`, `CHANGELOG.md`.
- [ ] T037 Mandate B verification: full suite on both CI jobs; quickstart validation record
  complete; IMC-6 criteria 1 (detection) and 2 met.

---

## Dependencies & Execution Order

```text
Mandate A
  T001 → T002 (setup, sequential: same README and test)
  T003 → T004 → T005 → T006 → T007          (foundational, sequential: same model files)
  T007 → T008 → T009 → T010                  (US1 core)
         T011, T012 [P] after T009
         T013 after T009; T014 after T013; T015 after T007
  T009 → T016 (US2)          T007 + T009 + T014 → T017 (US3)
  T002 → T018, T019 [P]; T002 + T007 + T015 → T020 [P]   (US4 fixtures)
  T021 → T022 → T023                         (US4 recorder, same script)
  all of T001–T023 → T024 (US5) → T025
Mandate B (blocked on the preconditions; after Mandate A is merged)
  T026 → T027 → T028 → T029 → T030 → T031, T032, T033, T034 → T035 → T036 → T037
```

- US2, US3 and US4 depend on the foundation only and can follow US1 in any order; US4's fixture
  tasks (T018–T020) and recorder tasks (T021–T023) are independent of each other.
- Mandate B starts only after the Mandate A PR is merged and its preconditions are met.

## Parallel Example

```text
After T002: T018 (reconstructed cases), T019 (guard) and T020 (help contract test) touch different
files and can run in parallel, while T021–T023 change only tools/record-fixtures.ps1 and its test.
```

## Implementation Strategy

- **MVP** (a review checkpoint inside the one Mandate A PR, not a separate merge): Phases 1–3 (T001–T015): a pinned 7.4 node is recognised and safely refused on every
  platform (IMC-6 criteria 1 pin and 3).
- **Mandate A complete**: + T016–T025 (criteria 4 and 5, the recording kit, docs). Mandate A is
  merged as **one PR** with green CI on both jobs: a partial merge would ship `V7_4` without the
  refusing matrix entry (reads through the 8.1 adapters) or without the docs. Commits per task
  inside the branch as usual.
- **Mandate B**: only after its preconditions; recordings first, then re-clarification, then code.
