---

description: "Task list for INUBIT 7.4 support, CLI only, cut into Mandate A and Mandate B (feature 025, IMC-6)"
---

# Tasks: INUBIT 7.4 Support, CLI Only, Cut into Two Mandates

**Input**: Design documents from `/specs/025-inubit-74-support/`

**Prerequisites**:
- [plan.md](plan.md) and [spec.md](spec.md) (spec reviews r1–r2 approved; plan reviews r1–r3, approved in r3)
- [research.md](research.md) (D-1…D-16; D-15 superseded by D-16) and [data-model.md](data-model.md)
- [contracts/configuration-delta.md](contracts/configuration-delta.md) and
  [contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md)
- [quickstart.md](quickstart.md)
- [evidence/startcli-7.4-help.txt](evidence/startcli-7.4-help.txt): the IMC-6 attachment
  (StartCLI `-h` of a 7.4 node, LF line ends, SHA-256
  `c7b04fd434d7e36127ca6ae32bbaa363c244c478ced625d5f64e6637d1f6416e`)
- constitution v1.1.0 (Mandate A); 2.0.0 (ratified 2026-11-23) and 2.1.0 (ratified 2026-11-26,
  IMC-25: the evidence level *unverified*) for Mandate B; the code of features 001–024 (branch based on
  `main` 0.10.2)

**Tests**: REQUIRED (Constitution III, TDD). Every task starts with the test(s) it names, observed
failing on an assertion before the production code exists, and ends with `mvn -q -o verify` green.
Each task contains its own tests; there are no separate "write failing tests" tasks. Tasks marked
"verification task" cover behaviour that earlier tasks or existing code already provide, so their
tests may pass at once.

**Organization**: two clearly separated groups.

- **Mandate A** (Phases 1–8, T001–T025): needs no 7.4 node. Implementable and mergeable on its own.
- **Mandate B** (Phases 9–12, T026–T046): replanned 2026-11-26 (owner decision): no 7.4 node, no
  recording, no live check. T026–T029 and T034 are withdrawn; T030 carries constitution 2.1.0
  (ratified 2026-11-26); T031–T033 and T038–T046 are detailed TDD tasks; T035 flips MONITORING
  and PROCESS_DIAGNOSIS on 7.4 and on 6.1 to `UNVERIFIED`; T036 docs "untested"; T037
  verification on both CI jobs.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on unfinished tasks)
- **[Story]**: US1 pinned 7.4 refused, US2 404 hint, US3 four-part patch, US4 recording kit,
  US5 regression, US6 health (B, unverified), US7 processes (B, unverified), US8 detection (B,
  withdrawn 2026-11-26), US9 6.1 health and processes (B, unverified)

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
- **Mandate B** (2026-11-26): no live step; all tests are offline as in Mandate A (the 7.4
  contract tests replay `fixtures/v7_4/cli/`). An optional later recording with the kit is a
  future mandate and keeps the rules of spec FR-016 – FR-019 and IMC-24 (never a node classified
  production, raw output never in a repository).
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
- [X] T021 [US4] Recorder case table (research D-12, data-model "Recorder case"):
  `tools/record-fixtures.ps1` — replace the `$Cases` hashtable (`:87-94`) by a table of case objects
  (`Name`, `Description`, `Kind`, `Arguments`/`Commands`, `Credentials`, `Contacts`, `Folder`,
  `FixtureName`); the six `win_*` cases keep exactly their behaviour, output names and folder
  `fixtures/windows/`. Test first: `test/tools/RecordFixturesPs1Test` — the existing cases stay
  green (`listsAllSixReadOnlyCases` becomes "lists every case of the table"), plus a check that
  every case's arguments and commands are defined only in the table.
- [X] T022 [US4] Recorder session mode (research D-12, spec FR-017): new function
  `Invoke-StartCliSession` in `tools/record-fixtures.ps1` — one `startcli.bat` process through the
  existing `Invoke-StartCli` (`cmd.exe /d /v:off /s /c`, allowlisted environment), standard input =
  password line, the case's commands, the case's end-of-session command; raw output in the private
  directory, path/host replacement, `neutralize.py`, `Assert-NoSecret` as for every case. Test
  first: `RecordFixturesPs1Test` static checks (session input only through stdin, never `-p`,
  never `--encryptString`, `Assert-NoSecret` on session output) and, on the Windows CI job, a run
  test of a session case against the substitute client of `recordsTheClientVersionThroughTheSubstitute`.
- [X] T023 [US4] Recorder 7.4 catalogue (research D-12, spec FR-016 – FR-018, quickstart): the
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

- [X] T024 [US5] Regression verification (verification task): `VersionLineRegressionTest`
  unchanged since T003 and green; the full suite green with `mvn -q -o clean verify`; the CI jobs
  for macOS/Linux and Windows green on the PR; `NoCustomerIdentifiersTest` run locally with the
  identifier lists. Record in [quickstart.md](quickstart.md) (validation record) every existing
  test that was adjusted and why: the 404 hint texts and static `unknown` callers (T016), the
  `versionLine` lists (T004), the `RefusingPorts` constructor (T008), the production entries in
  `CapabilityMatrixTest` (T007); any other adjustment is a regression to fix, not to record.

---

## Phase 8: Polish — documentation and changelog (Mandate A)

- [X] T025 Docs and changelog (research D-14, spec FR-020): `docs/setup.md` (requirements row,
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

# Mandate B — 7.4 unverified (untested), no 7.4 node needed (replanned 2026-11-26)

**Replanned 2026-11-26** (owner decision, spec Clarifications "Session 2026-11-26"): the recording
session (IMC-22) and the Windows live check will not take place. MONITORING and PROCESS_DIAGNOSIS
run on pinned 7.4 nodes through StartCLI with the 6.1 adapters and a 7.4 profile, declared
`UNVERIFIED`; every per-node entry after the version gate carries the existing warning, except
refusals decided before any contact (spec FR-030 (b)). `AUTO`
detection is dropped. Second round (owner, 2026-11-26): INUBIT 6.1 `get_health` and
`find_processes` become `UNVERIFIED` on the same basis, with the existing 6.1 reads, in the same
flip (spec US9, FR-036, FR-037). The precondition of the flip (T035), the ratified constitution
2.1.0, is met (T030, 2026-11-26, IMC-25); all tasks run offline like Mandate A. Test matrices follow the pattern `CapabilityExtensionTest`; the
production matrix changes only in T035.

## Phase 9: Recording session (withdrawn 2026-11-26)

- [ ] ~~T026 [US6] Preconditions and approval~~ — **withdrawn 2026-11-26, owner decision** (no
  recording session). Was: confirm machine, client version, node and its non-production
  classification with the owner; the approval of 2026-11-23 stays in the validation record as
  history. The kit stays as an optional later verification path.
- [ ] ~~T027 [US6] Research recordings~~ — **withdrawn 2026-11-26, owner decision**. Was: record
  `v74_help`, `v74_version`, `v74_exec_help`, `v74_exec_ps_help`, `v74_direct_health` and adjust
  the catalogue.
- [ ] ~~T028 [US7] Session and failure recordings~~ — **withdrawn 2026-11-26, owner decision**.
  Was: record the session and failure cases outside the repository and adjust the reconstructed
  cases to the evaluation (spec FR-021, FR-022, both withdrawn).

## Phase 10: Re-clarification and constitution

- [ ] ~~T029 Research addendum and Mandate B update~~ — **withdrawn 2026-11-26, owner decision**:
  replaced by the spec, plan and tasks update of 2026-11-26, which decides the points T029 was to
  revisit (spec FR-024 – FR-031, FR-035; plan, "Mandate B design"). That update is reviewed with
  `d4ld4r-speckit-duo:review-spec` and `review-plan` before T038.
- [X] T030 Constitution amendment: 2.0.0 ratified by the owner on 2026-11-23 (IMC-23; 7.4
  read-only, StartCLI only). **2.1.0 ratified by the owner on 2026-11-26** (IMC-25, commit
  `660683ba`; MINOR, Principles III and V: the evidence level *unverified*, on reconstructed
  fixtures from the vendor documentation of the version or from recordings, vendor documentation
  or reconstructed fixtures of another version, checked against real output where any exists).
  (History: drafted 2026-11-23 as proposed 1.2.0, revised the same day to 2.0.0 after IMC-24; 2.1.0
  proposed and widened after spec review spec-replan-r1 on 2026-11-26.)

## Phase 11: Implementation (TDD)

- [X] T038 [US6] [US7] [US9] Unverified reads on a CLI-only version, 7.4 and 6.1 (spec FR-030,
  FR-035, FR-036): in
  `main/application/VersionGate.java` `judge` (`:395-409`), on a CLI-only version an `UNVERIFIED`
  read adds the existing warning ("<name> on <node> is not verified for <capability>; results may
  be incomplete") and goes on to the client branch with the entry's channels (`:410`) instead of
  `refuseUnsupported`; `UNSUPPORTED` and writes stay refused as today. `monitoringAccess` returns
  `Channel.CLI` for a CLI-only pin whose MONITORING is `SUPPORTED` or `UNVERIFIED`; new
  `VersionGate.cliMonitoringWarnings(NodeId)` returns that warning for the pinned version (no
  detection, no probe, empty when `SUPPORTED`). `main/application/NodeOverview.java` `refusal`
  (`:184-188`) counts an `UNVERIFIED` read as usable on a CLI-only version too.
  `main/config/ConfigSummary.java` `cliOnly` (`:434-450`, today only `SUPPORTED` counts as
  available) counts an `UNVERIFIED` read on a CLI-only version as available.
  `main/domain/model/SupportState.java` and the class Javadoc of `CapabilityMatrix` (`:20`):
  `UNVERIFIED` runs with the adapters of the version's entry (the 8.1 adapters for versions
  without own adapters, the StartCLI adapters on a CLI-only version). Test first: `VersionGateTest`
  with a test matrix whose 7.4 entry declares MONITORING and PROCESS_DIAGNOSIS `UNVERIFIED` on CLI
  (notes on every other capability): PROCESS_DIAGNOSIS on `V7_4` and `"7.4.0.90"` is admitted with
  `INUBIT 7.4 on legacy74/node1 is not verified for process diagnosis; results may be incomplete`
  (`INUBIT 7.4.0.90 on …`) and runs the client check; LOG_DIAGNOSIS keeps the T009 refusal text; a
  write keeps the 6.1 order of checks; `monitoringAccess` = CLI and `cliMonitoringWarnings` gives
  the monitoring warning. The same cases with a test matrix whose 6.1 entry declares the two
  capabilities `UNVERIFIED` (`INUBIT 6.1 on legacy/node1 is not verified for monitoring; results may
  be incomplete`; LOG_DIAGNOSIS keeps the feature 012 refusal and next step). `ListNodesToolTest`
  with these test matrices: `status: partially_supported`, monitoring and process diagnosis usable,
  no suffix; `ConfigSummaryTest` with these test matrices: the `--check-config` summary line reads
  "(INUBIT 7.4, StartCLI only; available: monitoring, process diagnosis; refused: …)" (6.1 alike). Production unchanged: `V74RefusalTest`, `V61RefusalTest` and
  `VersionLineRegressionTest` stay green.
- [ ] T031 [US6] Health on 7.4: profile, port set, data entries (spec FR-024 – FR-027, after T038):
  new `main/adapter/cli/v61/CliLineProfile.java` (record: release, unavailable reason, whether
  `uname` reports the server's version; constant `V61` = "6.1", `NOT_AVAILABLE_ON_6_1`, false);
  `main/adapter/cli/v61/V61HealthAdapter.java` takes the profile (the 6.1 constructor keeps today's
  behaviour and texts); new `main/adapter/cli/v74/V74Profile.java` ("7.4", `NOT_AVAILABLE_ON_7_4`,
  false: 6.1 behaviour, spec FR-027); `main/domain/model/UnavailablePart.java` gets
  `NOT_AVAILABLE_ON_7_4`; `main/adapter/cli/v74/V74PortSet.java` `cliHealth(probe, limiter)` builds
  a `V61HealthAdapter` with a `V61Connection` (unchanged) and the 7.4 profile; `main/Wiring.java`
  passes the shared probe and limiter as for 6.1. `main/application/HealthService.java`
  `checkServerViaCli` adds `gate.cliMonitoringWarnings(server)` to a report built from a successful
  session (error entries: T041, T044, T045), and `:686` `known.compare(observed)` becomes
  `gate.comparePatch(known, observed)` (new `VersionGate.comparePatch`, delegating to the gate's
  matrix; T017). Test first: `test/adapter/cli/v74/V74HealthContractTest`
  (`FakeProcessLauncher.replaying("v7_4", …)`): `health_session_ok` (parts of FR-026, the three
  `NOT_AVAILABLE_ON_7_4` parts "INUBIT 7.4 has no readiness check" etc., version not the server's),
  `health_session_maintenance`, `health_session_alive_failed` (`ERROR`), `login_failed`
  (`AUTH_FAILED`), `unreachable` (`UNREACHABLE`), an unrecognized session output
  (`UNEXPECTED_RESPONSE`, never an empty report), and variants of `health_session_ok` built in the
  test: CRLF line ends, and German labels exactly as `V61HealthParser` accepts them (no German 7.4
  fixture file, spec FR-025); TLS at the adapter (spec FR-029): an `https://` URL whose certificate
  the handshake fake rejects gives `TLS_ERROR` and no launch, `disableHostnameVerification` gives
  `TLS_ERROR` before contact, the client options coming from the `-h` probe replaying the 7.4
  `help` (as `V61Connection` does for 6.1); `V74PortSetTest` (the health port is the StartCLI
  adapter; every other port still refuses); `HealthServiceTest` with the T038 test matrix: the
  warning on a successful CLI report of a `V7_4` node, the client's version warning of FR-027, a
  7.4.0.80 client at a `"7.4.0.90"` pin is a mismatch in the client part; 6.1 cases unchanged. The
  gate still refuses 7.4 in production, so the adapter is reached only after T035.
- [ ] T032 [US7] Processes on 7.4 (spec FR-024, FR-025, FR-028, after T031):
  `V74PortSet.factory(probe, limiter)` builds a `V61ProcessQueryAdapter` with the 7.4 connection and
  profile; every other port stays `RefusingPorts` with the Mandate A 7.4 text.
  The 6.1 classes take the profile only for the texts that name the release: the
  `findByProcessId` refusal of `V61ProcessQueryAdapter` (`:142-147`, today `new
  RefusingPorts(node)`, the 6.1 text), the `tag` refusal of `V61QueryRules` (`:48-49`, "INUBIT 6.1
  does not support the input tag", "StartCLI 6.1"; `INSTANCE` stays the 6.1 rules, and
  `main/Wiring.java` registers a 7.4 instance instead of `V61QueryRules.INSTANCE` for 7.4, T008)
  and the format name of `V61SessionParser` (`:34`, "the expected INUBIT 6.1 format"). Test first:
  `test/adapter/cli/v74/V74ProcessQueryContractTest`
  replaying `ps_session_all`, `_error`, `_waiting`, `_queued`, `_limit5` (truncated, total a lower
  bound), `_empty` (empty page, no error), `_unassigned` (no `inubitNode`): the fields of FR-028,
  the state filter, `hangingOnly` and `since`/`until` on the `DATE` column as on 6.1, CRLF and
  German variants of `ps_session_all` built in the test (German header and state names exactly as
  `V61PsParser` accepts them), an unrecognized listing (`UNEXPECTED_RESPONSE`, never an empty
  page); `tag` refused with `UNSUPPORTED_VERSION` by `validate` ("find_processes:
  INUBIT 7.4 does not support the input tag; nothing was sent"); `findByProcessId` and an
  unexpected listing name INUBIT 7.4; the 6.1 texts unchanged (`V61ProcessQueryAdapterTest`,
  `V61QueryRules` tests); `V74PortSetTest` (the process query port); a `DiagnosisServiceTest` case with
  the T038 test matrix: the warning in the 7.4 node's entry, none for an 8.1 node of the same group,
  and the existing version note of `VersionGate.notes` (`:1016-1025`) "logs of INUBIT 7.4 cannot be
  read with query_logs; read them in the Workbench" on every admitted 7.4 `find_processes` entry (error
  entries: T041, T044, T045).
- [ ] T033 [US6] Client choice for 7.4 (spec FR-029; after T031): the choice of StartCLI in
  `main/config/EffectiveNodeConfig.java` (`cliFor` / `choose`, `:337-360`) and
  `main/adapter/cli/CliRunner.java` `select` (`:285`) does not fall back to the version-less
  `defaults.cliHome` for a version whose matrix entry says so: a new `CapabilityMatrix.Entry` flag
  `ownClientOnly` (default false) with the query `ownClientOnly(Optional<InubitVersion>)`; no
  version check. T033 tests with a test matrix whose 7.4 entry sets the flag; the production 7.4
  entry gets it in T035, with the flip. The static `EffectiveNodeConfig.choose` (`:358`) takes the
  flag as a parameter: when set it skips `sources.defaultHome()` (node or group `cli.home` and the
  `"7.4"` entry still apply; without them the choice is `HomeSource.NONE`); `cliFor(version)`
  reads it from `CapabilityMatrix.production()` (as `ConfigValidator` does), and a package-private
  `cliFor(version, matrix)` serves the tests. `choosesCliByVersion` (`:353`, today false for 7.4
  unless `cliClients` is configured, because the major is below 9) returns true for a setting
  whose version has the flag, so `list_nodes` and `--check-config` show the version-dependent
  client. Without node `cli.home`, group `cli.home` or `defaults.cliClients."7.4"`,
  StartCLI-backed tools answer `CLI_UNAVAILABLE` naming `defaults.cliClients."7.4"`:
  `Selection.entryHint` (`:271`, today a major.minor only when `cliClients` is configured or the
  major is 9 or above) reads the same flag, and `CliRunner.noHome` (`:1060-1072`) for an entry with
  the flag drops "or set cliHome" from its next step and "nor cliHome" from its likely cause, so
  no text suggests `cliHome` (spec FR-029). Test first: `EffectiveNodeConfigTest` / `CliRunnerTest`
  (order node → group → `"7.4"`; a 7.4 pin with only `defaults.cliHome` gets no client, and the
  message names `defaults.cliClients."7.4"` and contains no "cliHome"; the same without any
  `cliClients`; `choosesCliByVersion` true for the flagged setting; 6.1 with `cliHome` unchanged,
  its message unchanged).
- [ ] T046 [US6] Fail-closed client check (spec FR-014, FR-015, FR-029; after T033): in
  `main/application/VersionGate.java` `checkClient` a client of an entry with `ownClientOnly` whose
  `-v` does not report the entry's major.minor, or cannot be read, refuses with `CLI_UNAVAILABLE`
  naming the client and what it reported. `get_health` never reaches `checkClient`
  (`monitoringAccess`, `V61Connection.healthSession` without the launch guard, the probe parallel
  to the session), so for such an entry `main/application/HealthService.java` `checkServerViaCli`
  runs the client probe **before** the session (bounded by the node's budget) and refuses with the
  same `CLI_UNAVAILABLE`, pre-contact and without the unverified warning; every other entry keeps
  the parallel probe. `main/application/NodeOverview.java` `clientRefusal` (`:207`) mirrors it:
  for such an entry a probed client of another line, or an unreadable one, makes monitoring and
  process diagnosis not usable with that reason. A reconstructed `fixtures/v7_4/cli/version.*`
  (the format of `fixtures/v8_1/cli/version.*`, client 7.4.0.90) is listed in `RECONSTRUCTED.txt`
  once per capability, one line per case and capability as in the 6.1 list (spec FR-014, FR-015):
  `version monitoring` and `version process_diagnosis`; the shared cases `login_failed` and
  `unreachable` get their `process_diagnosis` line next to the existing `monitoring` line, and
  `V74FixturesShapeTest` accepts a case listed under several capabilities (as
  `V61FixturesShapeTest` does for `help`). Test first: `VersionGateTest` with the T038 test matrix
  (flag set): an 8.1.17 client at a 7.4 pin → `CLI_UNAVAILABLE`; an unreadable `-v` →
  `CLI_UNAVAILABLE`; the replayed `version` case is accepted; `HealthServiceTest` (`get_health` on a
  7.4 pin with an 8.1.17 client and with an unreadable `-v`: `CLI_UNAVAILABLE`, no session
  launched, no unverified warning; with the replayed `version` case the session runs; a 6.1 node
  keeps the parallel probe); `ListNodesToolTest` (the mirrored reason); `V74FixturesShapeTest` (the
  new case and the per-capability lines); `CliClientProbeTest` (the `-v` replay of the 7.4 client).
- [ ] ~~T034 [US8] Detection~~ — **withdrawn 2026-11-26, owner decision** (spec FR-032, FR-033,
  US8): `AUTO` never selects 7.4; detection stays unchanged.
- [ ] T039 `lines()` without CLI-only notes (spec FR-035, IMC-16 m6; after T033 and T038, which
  also edit `CapabilityMatrix` and its test): `main/domain/model/CapabilityMatrix.java` `lines`
  (`:265-272`) counts a note only on an entry that is not CLI-only; this replaces the "every
  capability noted" exclusion of T007 (`allNoted` stays for `allNotYetVerified`). Test first:
  `CapabilityMatrixTest` with the T038 test matrix: `lines(QUEUE_ENTRY_READ)` and
  `lines(IO_LOG_READ)` without 7.4; production `lines` unchanged (`VersionLineRegressionTest`
  "; supported: 8.1, 9.0" green).
- [ ] T040 [P] Guard for unverified 7.4 (spec FR-015 as amended 2026-11-26, SC-009; test only):
  `test/adapter/cli/V74RecordedFixturesTest` fails when the production matrix declares any 7.4
  capability `SUPPORTED`, declares a capability other than MONITORING and PROCESS_DIAGNOSIS
  `UNVERIFIED`, or declares one of these two `UNVERIFIED` while it has no case in
  `RECONSTRUCTED.txt` or `help` is not in the README's recorded list; the derived-source and
  `v74_help` rules for `SUPPORTED` are dropped (no 7.4 capability may be supported). Self-tests with
  test matrices and synthetic lists in a temporary directory: `SUPPORTED` monitoring fails;
  `UNVERIFIED` log diagnosis fails; `UNVERIFIED` monitoring without a reconstructed monitoring case
  fails; `UNVERIFIED` monitoring and process diagnosis backed by reconstructed cases and `help`
  pass. The production matrix passes before and after T035. `V74FixturesShapeTest` (it rejects a
  fixture case in none of the lists) stays green and is part of SC-009's evidence.
- [ ] T041 [US6] [US7] [US9] "Contacted" marker (spec FR-030 (b); after T032 and T046): new
  `main/domain/model/ContactedException.java` (extends `ToolErrorException`, same `ToolError`, the
  error's JSON unchanged). Contact begins with the server's own TLS handshake or with the start of
  the StartCLI process, not with the limiter permit: inside the permit `CliRunner.runSession`
  (`:574-612`) still refuses before any launch (the Windows credential refusal, `checkLaunch`
  (`:589`, `:653`) with `UNSUPPORTED_VERSION` or `CLI_UNAVAILABLE`, the `CredentialGuard` cooldown
  of `guard.acquire()` with `AUTH_FAILED`, and `CLI_UNAVAILABLE` when StartCLI cannot be started,
  `LaunchFailure`). Moving all of these before the permit is not clean (the cooldown and the start
  failure belong to the launch itself), so the marker is set where the process has started:
  `main/adapter/cli/CliRunner.java`: only the session path marks. The private `runSession`
  (`:610`) passes `execute` (`:863`) a flag "mark contact" and, instead of `StartHook.DIRECT`, a
  `StartHook` (`main/adapter/cli/ProcessRun.java`) that wraps `DIRECT` and records that
  `ProcessRun` started the process; `execute` rethrows an error raised after that record (the
  run's `TIMEOUT`, a cut or failed run) as `ContactedException`. Errors before the start, and every
  error of the other `execute` callers (the local `-v` and `-h` probes, exports, `run`), stay plain
  `ToolErrorException`s;
  `main/adapter/cli/v61/V61Connection.java` (`session`, `healthSession`, `:86-132`) throws errors
  of `TlsHandshakeCheck.verify` as `ContactedException`; `V61HealthAdapter` and
  `V61ProcessQueryAdapter` throw classification (`CliOutputClassifier`) and parse errors of a
  returned session as `ContactedException`. Pre-contact (plain): missing credentials,
  `checkCredentials`, refused TLS options (`refuse`), no client (`checkAvailable`), no free slot
  (`limiter.acquire` `TIMEOUT`), the launch guard, the cooldown, the start failure. Test first:
  `V61ConnectionTest` — plain: missing credentials, refused TLS options, no free slot, launch-guard
  refusal (`UNSUPPORTED_VERSION`, `CLI_UNAVAILABLE` for a changed client), `CredentialGuard`
  cooldown (`AUTH_FAILED`, no launch), start failure (`CLI_UNAVAILABLE`); marked: handshake
  rejected (`TLS_ERROR`), handshake `UNREACHABLE`, the session's `TIMEOUT` after launch; the error
  JSON is the same in both cases; `CliRunnerTest` (a session run marks only after the start; a
  local `-v` / `-h` probe failure stays plain); `V74HealthContractTest` /
  `V74ProcessQueryContractTest` (a non-zero exit classified by `CliOutputClassifier`,
  `login_failed`, `unreachable`, an unrecognized output: marked).
- [ ] T044 [US6] [US7] [US9] Carrier for warnings on failed entries (spec FR-030 (b); after T041):
  `main/domain/model/NodeResult.java` gets a component `warnings` (a failure may carry them) and
  keeps a 3-argument constructor `(node, payload, error)` with no warnings, so
  `InventoryService.java:595` and `SecretTiming.java:82` compile unchanged (`failure(node, error)`
  keeps none); `main/domain/model/ToolErrorException.java` gets `warnings()`
  (default empty) and `withWarnings(List<String>)` (keeps the subtype, so a `ContactedException`
  stays one); `main/application/FanOut.java` maps a thrown exception with warnings to a failure
  with these warnings and gives its deadline `TIMEOUT` (`:82`) the warnings the node's task
  registered right after its admission: a new overload `FanOut.run(servers, deadline, call, sink)`
  hands each task a `FanOut.Sink` (`register(NodeId, List<String>)`); the existing `run` overloads
  and their callers stay unchanged (no sink, no warnings on the deadline `TIMEOUT`);
  `main/application/SecretTiming.java` `explain` (`:74-82`), which rebuilds the `TIMEOUT`
  `NodeResult` of `find_processes` and `query_logs`, keeps the result's warnings;
  `main/mcp/tools/NodePages.java` renders a failure's warnings beside `error`;
  `res/schemas/find_processes.output.json` (`results` description "a failed {node} has only an
  error" → "an error and possibly warnings"; shape unchanged); `docs/tools.md` (the
  `find_processes` result and the warning table). Test first: `NodeResultTest`, a new
  `test/mcp/tools/NodePagesTest` (a failure with warnings renders them), `FanOutTest` (a thrown
  exception with warnings keeps them; the deadline `TIMEOUT` of a node that registered warnings
  carries them, one that registered none carries none; the overload without sink behaves as
  today), `SecretTimingTest` (an explained
  `TIMEOUT` keeps the warnings), `FindProcessesToolTest` (schema description, a failed entry with
  warnings validates).
- [ ] T045 [US6] [US7] [US9] Services attach the warning to data and contacted errors only (spec
  FR-030 (b), SC-007, SC-010; after T044): `main/application/DiagnosisService.java`
  (`find_processes`, `:225-245`) registers the admission's warnings in the `FanOut` sink right
  after `admit` and rethrows a `ContactedException` from the port `withWarnings(admission
  warnings)`; a plain `ToolErrorException` and the `tag` refusal of `validate` stay without them.
  `main/application/HealthService.java`: `Outcome` (`:921`) carries whether its error came from a
  `ContactedException` (`await` keeps the marker); `checkServerViaCli` adds
  `cliMonitoringWarnings` to a failed report only for a marked error or a deadline `TIMEOUT` of
  `await` (the node was admitted; the budget may run out while the session still waits for a slot,
  which FR-030 (b) treats as the call's deadline, not as the limiter's no-slot refusal): this
  includes the deadline `TIMEOUT` of the credentials `await` (`:423-428`); the `sessionTimeout`
  rewrite (`:446-450`) keeps the original `Outcome`'s marker, so a limiter no-slot `TIMEOUT`
  stays plain. `failed(server, error)` for a credentials error that is not a deadline (missing,
  pending), the port, the T046 client check and a plain error stays without them. The `get_health` fan-out backstop `TIMEOUT` (`:246-254`, deadline `cliBudget`,
  while `checkServerViaCli` ends at `cliBudget - FAN_OUT_GRACE`) is reached only when a node's
  thread does not return; it gets the warning through the same `FanOut` sink (the T044 overload),
  registered by `checkServer` once `monitoringAccess` chose StartCLI, and `HealthService.check`
  (`:252-254`) builds its failed report with the `NodeResult`'s warnings instead of
  `failed(node, error)` with none. Test first: `DiagnosisServiceTest` with
  the T038 test matrices (7.4 and 6.1): `AUTH_FAILED` from a rejected login, `UNREACHABLE`,
  `UNEXPECTED_RESPONSE`, the session `TIMEOUT` after launch and the `FanOut` deadline `TIMEOUT` of
  an admitted node still waiting for a StartCLI slot carry the warning; `CLI_UNAVAILABLE`, missing
  credentials, refused TLS options, the limiter's no-slot `TIMEOUT`, the launch guard, the cooldown
  and `tag` do not; `HealthServiceTest` with the T038 test matrix: the same split for
  `get_health` (handshake rejected `TLS_ERROR` with the warning, refused host-name skip without,
  the `await` budget `TIMEOUT` while waiting for a slot with, the credentials-`await` deadline
  `TIMEOUT` with, a missing credential without, the backstop `TIMEOUT` with), a group with an 8.1
  and a 7.4 node
  (overview budget, only the 7.4 entry warned); 8.1, 9.0 and unknown-version error entries
  unchanged.
- [ ] T042 [P] [US9] Guard for unverified 6.1 (spec FR-037, SC-010; test only):
  `test/adapter/cli/V61RecordedFixturesTest` fails when the production matrix declares any 6.1
  capability `SUPPORTED`, declares a capability other than MONITORING and PROCESS_DIAGNOSIS
  `UNVERIFIED`, or declares one of these two `UNVERIFIED` while it has no case in
  `fixtures/v6_1/cli/RECONSTRUCTED.txt`. Self-tests with test matrices as in T040. The production
  matrix passes before and after T035.
- [ ] T043 [US9] 6.1 reads as unverified (spec FR-036; verification task, after T045): with
  a test matrix whose 6.1 entry declares the two capabilities `UNVERIFIED`, `get_health` and
  `find_processes` on a `V6_1` and a `"6.1.0.89"` node run the existing 6.1 reads (`FakeV61Node`,
  the replayed `fixtures/v6_1/cli/` cases incl. `_de`) with the 6.1 warning; the version-less
  `defaults.cliHome` is used as today; every admitted entry (data or contacted error) carries the version note "logs of INUBIT 6.1
  cannot be read with query_logs; read them in the Workbench"; every other capability keeps the
  feature 012 refusal. Fix
  production code only if a case fails (expected: none beyond T038, T041, T044, T045).

## Phase 12: Enablement, docs, verification

- [ ] T035 [US6] [US7] [US9] Flip to `UNVERIFIED`, 7.4 and 6.1 together (spec FR-020, FR-030,
  FR-031, FR-035, FR-036, SC-007, SC-009, SC-010): after T031 – T033 and T038 – T046; its
  precondition, the ratified constitution 2.1.0, is satisfied (T030, 2026-11-26); no live check. In
  `main/domain/model/CapabilityMatrix.java` the production entry `V7_4` declares MONITORING and
  PROCESS_DIAGNOSIS `UNVERIFIED` without a note (every other capability keeps `UNSUPPORTED` and "not
  yet verified") and sets the T033 flag `ownClientOnly`, and the production entry `V6_1` declares
  the same two `UNVERIFIED` (partner
  management and the rest stay `UNSUPPORTED`); Javadoc of both entries and of `production()`. The switches of
  FR-035 that key on `allNotYetVerified` follow without code change, except
  `main/config/ConfigValidator.java:779-786`: the warning for a CLI-only node without client names
  `defaults.cliClients."7.4"` for an entry with the T033 flag. Test first (new expectations,
  failing until the flip): `CapabilityMatrixTest` (7.4 states and notes, `allNotYetVerified` false
  for 7.4), `V74RefusalTest` (`get_health` and `find_processes` run through the replayed fixtures
  with the warning; every other tool unchanged; the group case), `ToolAnnouncementTest`
  (`find_processes` announced for a 7.4-only profile, with and without a client: the announcement
  rule, `Wiring.possiblyCapable`, does not depend on a client), `ListNodesToolTest` (usable
  monitoring and process diagnosis, no T014 client warning), `ConfigSummaryTest` (no "not used
  until …" suffix), `CliClientProbeTest` and `MainTest` (`-h` runs for a 7.4 pin, POSIX stand-in),
  `ConfigValidatorTest` (the warning naming `defaults.cliClients."7.4"`; no JDK major warning),
  `V74RecordedFixturesTest` green; for 6.1: `V61RefusalTest` (`get_health` and `find_processes` run
  with the warning; every other tool unchanged), `ToolAnnouncementTest` (`find_processes` announced
  for a 6.1-only profile), `ListNodesToolTest` and `ConfigSummaryTest` (6.1: `partially_supported`,
  "available: monitoring, process diagnosis"), `VersionLineRegressionTest` (the 6.1 rows of
  MONITORING and PROCESS_DIAGNOSIS, `:53-55`, become `UNVERIFIED`: the one deliberate change of
  that test, spec FR-020), `V61RecordedFixturesTest` green. Record every adjusted existing test in
  the quickstart (validation record). Lands in one commit with T036.
- [ ] T036 Docs and changelog for Mandate B (spec FR-034; same commit as T035): `docs/setup.md`
  (section "INUBIT 7.4 nodes": `get_health` and `find_processes` **untested** (unverified), pinned
  nodes only, `AUTO` never 7.4; `defaults.cliClients."7.4"` with its JDK and the client order of
  FR-029), `docs/tools.md` (capability table 7.4 column "unverified (untested)", the warning table
  "unverified version" with a 7.4 example, `find_processes` and `get_health` on 7.4,
  `NOT_AVAILABLE_ON_7_4`), `README.md` (supported versions: 7.4 health and processes untested),
  `docs/research/inubit-interfaces.md` (7.4: no recordings, 6.1 behaviour assumed, the recording
  kit as optional later verification), `CHANGELOG.md` `[Unreleased]`. 6.1 (spec FR-034): `README.md`
  (the 6.1 paragraph: "become available once verified against recorded outputs" → health and
  processes **untested** (unverified); the announcement row that names a 6.1-only profile as
  offering none of the read tools; the "except on INUBIT 6.1" sentence), `docs/setup.md` (section
  "INUBIT 6.1 nodes": health and processes untested), `docs/tools.md` (6.1 column, warning table
  example for 6.1), changelog entry. Both: the fixture provenance in one sentence (reconstructed
  from the 6.1 vendor documentation; 7.4 checked against its real help output). Test first: none
  (docs); the doc-link checks stay green.
- [ ] T037 Mandate B verification (verification task): `mvn -q -o clean verify` green;
  `VersionLineRegressionTest` unchanged since T035; `NoCustomerIdentifiersTest` run locally with the
  identifier lists; the CI jobs for macOS/Linux and Windows green on the PR (the Windows job runs
  the 7.4 and 6.1 contract tests with the feature 013 invocation); quickstart validation record
  complete.
  IMC-6 criterion 2 met as unverified (untested); criterion 1 (detection) withdrawn. No live check.

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
Mandate B (replanned 2026-11-26; after Mandate A is merged)
  T026, T027, T028, T029, T034: withdrawn
  T030 done (2.1.0 ratified 2026-11-26, IMC-25)
  T038 → T031 → T032 (same port set and Wiring)
  T031 → T033 → T046 (T031 and T046 edit HealthService, HealthServiceTest and VersionGate)
  T033 → T039 (T033, T038, T039 and T035 edit CapabilityMatrix and its test)
  T032 + T046 → T041 → T044 → T045 → T043
  T040 [P], T042 [P] (test only, independent of T031–T033, T046)
  T031 – T033 + T038 – T046 ──→ T035 + T036 (one commit) → T037
```

- The code tasks T038, T031 – T033 and T039 – T046 change no production behaviour on their own:
  the production matrix keeps refusing 7.4 and 6.1 health and processes until T035.

## Parallel Example

```text
After T002: T018 (reconstructed cases), T019 (guard) and T020 (help contract test) touch different
files and can run in parallel, while T021–T023 change only tools/record-fixtures.ps1 and its test.
Mandate B: T040 and T042 (guard tests) run in parallel with T038 → T031 → T032.
```

## Implementation Strategy

- **MVP** (a review checkpoint inside the one Mandate A PR, not a separate merge): Phases 1–3 (T001–T015): a pinned 7.4 node is recognised and safely refused on every
  platform (IMC-6 criteria 1 pin and 3).
- **Mandate A complete**: + T016–T025 (criteria 4 and 5, the recording kit, docs). Mandate A is
  merged as **one PR** with green CI on both jobs: a partial merge would ship `V7_4` without the
  refusing matrix entry (reads through the 8.1 adapters) or without the docs. Commits per task
  inside the branch as usual.
- **Mandate B** (replanned 2026-11-26): one PR. First the spec/plan/tasks update is reviewed
  (`review-spec`, `review-plan`); then T038 → T031 → T032, T033 → T046, T039 – T045, each test-first and
  invisible in production; then T035 and T036 in one commit (flip of 7.4 and 6.1 and "untested"
  docs together, FR-034; constitution 2.1.0 is ratified); then T037. No recording, no live check.
  The recording kit stays for a future mandate that may raise 7.4 or 6.1 to supported.
