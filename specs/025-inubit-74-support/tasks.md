---

description: "Task list for INUBIT 7.4 support, CLI only, cut into Mandate A and Mandate B (feature 025, IMC-6)"
---

# Tasks: INUBIT 7.4 Support, CLI Only, Cut into Two Mandates

**Input**: Design documents from `/specs/025-inubit-74-support/`

**Prerequisites**:
- [plan.md](plan.md) and [spec.md](spec.md) (spec reviews r1–r2)
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

- **Mandate A** (Phases 1–8, T001–T024): needs no 7.4 node. Implementable and mergeable on its own.
- **Mandate B** (Phases 9–12, T025–T036): needs a 7.4 node. **Blocked** until a Windows machine
  with an INUBIT 7.4 Workbench client and its JDK and a non-production 7.4.0.90 node are available
  (spec, Preconditions). Its implementation tasks are placeholders that T028 replaces with detailed
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

- [ ] T001 [P] Fixture scaffold (research D-11): create `fixtures/v7_4/cli/README.md` (case layout
  as `fixtures/v6_1/cli/README.md`; source "reconstructed from the 7.4 help output and the 6.1
  cases until recorded"; neutral names of the safety rules; a section `## Recorded cases` with one
  case per line, the single source for recorded cases, spec FR-015), `fixtures/v7_4/cli/RECONSTRUCTED.txt`
  (format `<case> <capability>` per line, `#` comments, header as the 6.1 file) and
  `fixtures/v7_4/cli/DERIVED.txt` (format `<case> <source-case> <capability> <edit…>`, comments only).
  Test first: `test/adapter/cli/V74FixturesShapeTest` (pattern `V61FixturesShapeTest`) fails while
  the folder or a file is missing; it checks the README, the line formats of both lists, that every
  listed case has its three files, and that every `<case>.stdout` in the folder is in exactly one of
  `RECONSTRUCTED.txt`, `DERIVED.txt` and the README's recorded list.
- [ ] T002 [P] The real help case (spec FR-014, FR-019): copy
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

- [ ] T003 Regression baseline (research D-13, spec FR-020, SC-005; verification task, written
  against the unchanged code): `test/domain/model/VersionLineRegressionTest`, parameterised over
  `V6_1`, `V8_1`, `V9_0`, `V9_X` and the pins `"6.1.0.89"`, `"8.1.17"`, `"9.0.10"`: the parsed
  `InubitVersion` (major, minor, patch, raw), `ClientVersion.compare` for equal, patch-different and
  patch-absent pairs (`6.1.0.89` vs `6.1.0.80` = `MATCH`), `CapabilityMatrix.production().state`
  for every capability, `isCliOnly`, and the refusal summary texts of `VersionGate` for one refused
  capability per line. It must pass now and stay unchanged through T004–T014.
- [ ] T004 Line value `V7_4` (research D-1, spec FR-001): `main/domain/model/VersionSetting.java`
  (`V7_4 = new Line(7, OptionalInt.of(4))`, `parse`, Javadoc), `main/config/ConfigLoader.java:807`
  (error text "expected AUTO, V6_1, V7_4, V8_1, V9_0, V9_X or a full version such as \"8.1.17\""),
  `main/domain/model/NodeSummary.java:22` (doc), `res/schemas/list_nodes.output.json:63,117`
  (descriptions). Test first: `VersionSettingTest` (`V7_4` parses to `Line(7, 4)`, `text()` =
  `V7_4`; `"7.4.0"`, `"7.4.0.90"`, `"7.4.0.90-HF1"` are `Pinned`; `"V7_X"` is refused),
  `ConfigLoaderTest` (the new error text; the existing assertion of the old text is the deliberate
  change of SC-005).
- [ ] T005 Fourth version part (research D-2, spec FR-002, FR-004): `main/domain/model/InubitVersion.java`
  gets `OptionalInt build` ("present only if `patch` is present and the value has a fourth numeric
  part"), the regex `^(\d{1,9})\.(\d{1,9})(?:\.(\d{1,9})(?:\.(\d{1,9}))?)?(?!\d)`, and a kept
  four-argument constructor (build empty); `compareTo` and `sameMinor` unchanged. Test first:
  `InubitVersionTest` — `"7.4.0.90"` → build 90, raw `7.4.0.90`; `"7.4.0"` → build empty;
  `"6.1.0.89-HF1"` → build 89; `"8.1.17"` → build empty; `"7.4.0.90"` and `"7.4.0.80"` compare as
  equal (`compareTo` = 0); `"7.4.0.90.5"` → build 90, the rest only in raw.
- [ ] T006 Patch depth (research D-3, spec FR-003, data-model "ClientVersion.Comparison"): new
  `main/domain/model/PatchDepth.java` (`THREE`, `FOUR`); `main/domain/model/ClientVersion.java`
  gets `compare(server, PatchDepth)`, the existing `compare(server)` delegates with `THREE`. Test
  first: `ClientVersionTest`, every row of the data-model table with `FOUR` (`7.4.0.90`/`7.4.0.90`
  MATCH, `7.4.0.80`/`7.4.0.90` MISMATCH, `7.4.0`/`7.4.0.90` NOT_COMPARABLE, `7.4.0`/`7.4.1.5`
  MISMATCH, `7.4.0`/`7.4.0` MATCH, patch absent NOT_COMPARABLE) and with `THREE`
  (`6.1.0.89`/`6.1.0.80` MATCH).
- [ ] T007 Matrix entry 7.4 (research D-4, spec FR-006, data-model "CapabilityMatrix.Entry"):
  `main/domain/model/CapabilityMatrix.java` — `Entry` gets `PatchDepth patchDepth` (default
  `THREE`, existing constructors keep it), the production entry `V7_4` (range 7.4–7.4, no
  `SUPPORTED` state, channels MONITORING and PROCESS_DIAGNOSIS = {CLI}, `cliOnly = true`, note "not
  yet verified" on every `Capability`, `FOUR`), `PRODUCTION = List.of(V6_1, V7_4, V8_1, V9_0)`, and
  the queries `patchDepth(Optional<InubitVersion>)` (`THREE` for unknown and out-of-range),
  `cliOnlyLines()` (`["6.1", "7.4"]`) and `allNotYetVerified(Optional<InubitVersion>)`; class
  Javadoc of `production()` extended. Test first: `CapabilityMatrixTest` — every capability on 7.4
  is `UNSUPPORTED` with the note, `isCliOnly(7.4)`, channels, `patchDepth` per line, `cliOnlyLines`,
  `allNotYetVerified` true for 7.4 and false for 6.1, 8.1, 9.0; `VersionLineRegressionTest` still
  green.

**Checkpoint**: the version model knows 7.4; nothing else uses it yet.

---

## Phase 3: User Story 1 — a pinned 7.4 node is known and every tool refuses clearly (P1) 🎯 MVP

**Goal**: spec US1, FR-006 – FR-013. **Independent test**: `V74RefusalTest` plus the `list_nodes`
and `--check-config` tests, offline, on both CI jobs.

- [ ] T008 [US1] Refusing 7.4 port set (research D-5, spec FR-011): new
  `main/adapter/cli/v74/V74PortSet.java` (`RANGE` 7.4–7.4; `factory` → `RefusingPorts` for every
  port; `cliHealth` → a `CliHealthPort` that throws the same refusal); `main/adapter/RefusingPorts.java`
  takes the release ("(INUBIT <release>, StartCLI only)"; the 6.1 text unchanged);
  `main/Wiring.java:474-476` registers 7.4 after 6.1 with `V61QueryRules.INSTANCE`. Test first:
  `test/adapter/cli/v74/V74PortSetTest` (every port and the health port refuse with
  `UNSUPPORTED_VERSION` naming the node and "INUBIT 7.4"), an `AdapterGatewayFactoryTest` case (a
  node pinned `V7_4` gets `NodeAccess.CLI_ONLY` and no REST client is built), and the 6.1
  `RefusingPorts` text assertion unchanged.
- [ ] T009 [US1] Refusal text on 7.4 (research D-6, spec FR-007, contracts/mcp-tools-delta.md):
  `main/application/VersionGate.java` `refuseUnsupported` (`:859-902`) gets the branch "CLI-only
  and a note present" before the note branch: summary "`<tool>` on `<node>`: `<label>` is not yet
  verified for INUBIT 7.4", next step "INUBIT 7.4 is recognised, but no tool is verified on it yet;
  use the INUBIT Workbench for `<label>` on `<node>`". Test first: `VersionGateTest` cases for a
  `V7_4` and a `"7.4.0.90"` pin (read, write, monitoring); the 6.1 and 9.0 refusal texts in
  `VersionLineRegressionTest` unchanged.
- [ ] T010 [US1] Per-tool refusal without contact (spec FR-007, FR-012, FR-013, SC-003):
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
- [ ] T011 [P] [US1] `check_artifacts` on 7.4 (spec FR-008; verification task): an
  `ArtifactCheckServiceTest` case — module lookup on a `V7_4` node runs the offline checks and
  returns the notice "modules of INUBIT 7.4 cannot be looked up", no contact.
- [ ] T012 [P] [US1] Announcement (spec FR-009; verification task): a `test/ToolAnnouncementTest`
  case — a profile whose only nodes are pinned `V7_4` does not announce `get_health`,
  `find_processes` and the other node tools.
- [ ] T013 [US1] Display (spec FR-010, SC-001, contracts/mcp-tools-delta.md "list_nodes entry"):
  `ListNodesToolTest` and `ConfigSummaryTest` cases for `V7_4` and `"7.4.0.90"` (version shown with
  all four parts for the pin, `access: cli_only`, every capability unsupported with the note, the
  summary "(INUBIT 7.4, StartCLI only; available: none; refused: …)"); change
  `main/application/NodeOverview.java` / `main/config/ConfigSummary.java` only if a case fails.
- [ ] T014 [US1] Client configuration for 7.4 (research D-9, spec FR-010a,
  contracts/configuration-delta.md): `main/config/ConfigValidator.java:779-786` gives the
  "reached through StartCLI only, but no cliHome" warning only when
  `!matrix.allNotYetVerified(pin)` (6.1 text unchanged); `NodeOverview` / `ConfigSummary` add the
  note "not used until a 7.4 capability is verified" to the client line of a node whose version is
  `allNotYetVerified`. Test first: `ConfigValidatorTest` (7.4 pin without client: no warning; 6.1:
  warning unchanged; `defaults.cliClients."7.4"` accepted; no JDK major warning for it),
  `MainTest` (`--check-config` with a `"7.4"` entry runs the local `-v` probe through the fake
  launcher, shows a "not evaluable" result and the note, contacts no node), `ListNodesToolTest`
  (client line with the note).

**Checkpoint**: US1 complete — a pinned 7.4 node is safe on every platform.

---

## Phase 4: User Story 2 — a failed detection points to 7.4 as well (P2)

**Goal**: spec US2, FR-005, SC-002.

- [ ] T015 [US2] 404 hint (research D-7): `main/application/VersionGate.java` `unknown`
  (`:1004-1011`) becomes an instance method building the hint from `matrix.cliOnlyLines()`:
  "; if <node> runs INUBIT 6.1, pin `V6_1`; if it runs INUBIT 7.4, pin `V7_4`" (backticks as in the
  existing text, i.e. none if the existing text has none). Test first: `VersionGateTest` — a
  detection failure with `endpointMissing` gives the new text; timeout, TLS error, 401, 500 and an
  unparsable answer give the unchanged text; adjust the existing 6.1 hint assertions (deliberate
  change of SC-005, list them in the commit message).

---

## Phase 5: User Story 3 — the patch level of a four-part 7.4 version is kept (P2)

**Goal**: spec US3, FR-003, SC-004.

- [ ] T016 [US3] Client check with patch depth (research D-3): `VersionGate.checkClient`
  (`:782-824`) calls `ClientVersion.compare(server, matrix.patchDepth(server))`. Test first:
  `VersionGateTest` with a test matrix in which 7.4 monitoring is `SUPPORTED` (pattern
  `CapabilityExtensionTest`, never the production matrix): a 7.4.0.80 client at a 7.4.0.90 server
  gives the patch-mismatch warning of feature 009; 7.4.0.90/7.4.0.90 gives none; a 6.1.0.80 client
  at a 6.1.0.89 server gives none (fourth part ignored, as today).

---

## Phase 6: User Story 4 — the recording kit for 7.4 is ready on Windows (P2)

**Goal**: spec US4, FR-014 – FR-019, SC-006. **Independent test**: `V74FixturesShapeTest`,
`V74RecordedFixturesTest`, `V74HelpContractTest`, `RecordFixturesPs1Test`, `tools\record-fixtures.ps1 -List`.

- [ ] T017 [P] [US4] Reconstructed 7.4 cases (research D-11, spec FR-014 table): create
  `fixtures/v7_4/cli/<case>.stdout/.stderr/.exit` for `health_session_ok`,
  `health_session_maintenance`, `health_session_alive_failed`, `login_failed`, `unreachable`,
  `ps_session_all`, `ps_session_error`, `ps_session_waiting`, `ps_session_queued`,
  `ps_session_limit5`, `ps_session_empty`, `ps_session_unassigned`, each built from the 6.1 case of
  the same purpose in `fixtures/v6_1/cli/` with the neutral 7.4 names and the server version
  `7.4.0.90`; list each in `RECONSTRUCTED.txt` with its capability (`monitoring` or
  `process_diagnosis`) and a `#` comment naming the recorder case that replaces it (spec FR-014
  table). Test first: `V74FixturesShapeTest` lists exactly these twelve plus `help`.
- [ ] T018 [P] [US4] Guard (spec FR-015, FR-016a, FR-030): `test/adapter/cli/V74RecordedFixturesTest`
  (pattern `V61RecordedFixturesTest`): fails if `CapabilityMatrix.production()` declares a 7.4
  capability `SUPPORTED` while (a) a case of it is in `RECONSTRUCTED.txt`, (b) a case of it is in
  `DERIVED.txt` with a source case not in the README's recorded list, or (c) the README's recorded
  line for `help` does not name `v74_help` as its source. Self-tests with a flipped test matrix for
  (a), (b) and (c).
- [ ] T019 [P] [US4] Contract test of the real help case (research D-11): `test/adapter/cli/v74/V74HelpContractTest`
  — the client option parser used by `main/adapter/cli/CliClientProbe.java` for the `-h` probe,
  fed with `FakeProcessLauncher.replaying("v7_4", "help")`, reports no trust-store option and no
  host-name-verification option (input for spec FR-029 and feature 012 FR-012a), and the help text
  contains `--execCommand`. No production change expected; if the parser cannot read the 7.4
  format, report it as a finding instead of changing the parser (the 7.4 client path is Mandate B).
- [ ] T020 [US4] Recorder case table (research D-12, data-model "Recorder case"):
  `tools/record-fixtures.ps1` — replace the `$Cases` hashtable (`:87-94`) by a table of case objects
  (`Name`, `Description`, `Kind`, `Arguments`/`Commands`, `Credentials`, `Contacts`, `Folder`,
  `FixtureName`); the six `win_*` cases keep exactly their behaviour, output names and folder
  `fixtures/windows/`. Test first: `test/tools/RecordFixturesPs1Test` — the existing cases stay
  green (`listsAllSixReadOnlyCases` becomes "lists every case of the table"), plus a check that
  every case's arguments and commands are defined only in the table.
- [ ] T021 [US4] Recorder session mode (research D-12, spec FR-017): new function
  `Invoke-StartCliSession` in `tools/record-fixtures.ps1` — one `startcli.bat` process through the
  existing `Invoke-StartCli` (`cmd.exe /d /v:off /s /c`, allowlisted environment), standard input =
  password line, the case's commands, the case's end-of-session command; raw output in the private
  directory, path/host replacement, `neutralize.py`, `Assert-NoSecret` as for every case. Test
  first: `RecordFixturesPs1Test` static checks (session input only through stdin, never `-p`,
  never `--encryptString`, `Assert-NoSecret` on session output) and, on the Windows CI job, a run
  test of a session case against the substitute client of `recordsTheClientVersionThroughTheSubstitute`.
- [ ] T022 [US4] Recorder 7.4 catalogue (research D-12, spec FR-016 – FR-018, quickstart): the
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

- [ ] T023 [US5] Regression verification (verification task): `VersionLineRegressionTest`
  unchanged since T003 and green; the full suite green with `mvn -q -o clean verify`; the CI jobs
  for macOS/Linux and Windows green on the PR; `NoCustomerIdentifiersTest` run locally with the
  identifier lists. Record in [quickstart.md](quickstart.md) (validation record) which existing
  tests were adjusted and why (only the 404 hint and the `versionLine` lists).

---

## Phase 8: Polish — documentation and changelog (Mandate A)

- [ ] T024 Docs and changelog (research D-14, spec FR-020): `docs/setup.md` (requirements row,
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

- [ ] T025 [US6] Preconditions and approval: confirm in the chat with the owner the machine, the
  client version (`v74_version` must report 7.4), the node and its non-production classification;
  record them (without host names) in [quickstart.md](quickstart.md). No recording before the
  owner's approval.
- [ ] T026 [US6] Research recordings (quickstart steps 1–3): run `v74_help`, `v74_version`,
  `v74_exec_help`, `v74_exec_ps_help`, `v74_direct_health` with `tools/record-fixtures.ps1`;
  compare the interactive commands and `ps` options with the catalogue and adjust the 7.4 entries
  of the catalogue (one place, spec FR-018) before the session cases; note every adjustment in
  the quickstart. Stop and ask the owner if the client reads the password from the console instead
  of standard input.
- [ ] T027 [US7] Session and failure recordings (quickstart step 4): `v74_health_session`,
  `v74_ps_all`, `v74_ps_error`, `v74_ps_waiting`, `v74_ps_queued`, `v74_ps_limit5`,
  `v74_login_failed`, `v74_failure`; replace each reconstructed case in `fixtures/v7_4/cli/`
  (spec FR-022), remove it from `RECONSTRUCTED.txt`, add it to the README's recorded list (and
  `help` with source `v74_help`); move the derivable cases to `DERIVED.txt` with source and edit
  (spec FR-016a); run `NoCustomerIdentifiersTest`; commit.

## Phase 10: Re-clarification from the recordings

- [ ] T028 Research addendum and Mandate B update (spec FR-023, "Planning depth"; research D-15):
  document the differences 7.4 vs 6.1 in `docs/research/inubit-interfaces.md` and a research
  addendum in `specs/025-inubit-74-support/research.md`; then update `spec.md` (Mandate B
  requirements as the recordings fix them), `plan.md` and this `tasks.md` (replace T030–T034 by
  detailed TDD tasks: adapters or parser profiles, script mode vs session, client selection,
  detection trigger), each reviewed with `d4ld4r-speckit-duo:review-spec` / `review-plan` before any
  Mandate B code is written.
- [ ] T029 Constitution amendment (plan, Constitution Check V): propose with `/speckit-constitution`
  a MINOR amendment of `.specify/memory/constitution.md` Principle V naming INUBIT 7.4 as supported
  read-only, StartCLI only, because its REST interface is unverified; the owner ratifies it before
  T034.

## Phase 11: Implementation (placeholders, detailed by T028)

- [ ] T030 [US6] Health on 7.4 (spec FR-024 – FR-027): 7.4 health adapter or 6.1 adapter profile
  in `main/adapter/cli/v74/`, parsers tested against the recordings, `NOT_AVAILABLE_ON_7_4` parts
  in `main/domain/model/UnavailablePart.java`, observed version from `-sv` for the contradiction
  rule.
- [ ] T031 [US7] Processes on 7.4 (spec FR-028): 7.4 process query adapter in
  `main/adapter/cli/v74/`, required fields, hanging and time range per the recordings, refused
  inputs, 6.1 budget.
- [ ] T032 [US6] Client selection (spec FR-029): `main/adapter/cli/CliClientSelector.java` — no fallback
  to the version-less `defaults.cliHome` for 7.4 (a matrix flag, not a version check); the chosen
  client's `-v` must report 7.4, else `CLI_UNAVAILABLE`; feature 012 FR-012 / FR-012a for the
  7.4 client.
- [ ] T033 [US8] Detection (spec FR-032, FR-033): the detection step and trigger fixed by T028 from
  the live observation; no additional request to nodes whose REST detection succeeds; 6.1 stays
  unknown.

## Phase 12: Live checks, enablement, docs

- [ ] T034 [US6] Live checks and enablement (spec FR-030, FR-031, SC-007 – SC-009): run the live
  check of `get_health`, `find_processes` and `AUTO` detection against the 7.4.0.90 node from
  Windows and from macOS or Linux; record the outcomes and the detection failure code of the
  `AUTO` node in [quickstart.md](quickstart.md); only after both pass and T029 is ratified, flip
  MONITORING and PROCESS_DIAGNOSIS on 7.4 to `SUPPORTED` in `main/domain/model/CapabilityMatrix.java`
  and remove their "not yet verified" notes (the `V74RecordedFixturesTest` guard must stay green).
  If one platform cannot be checked, stop and let the owner decide (spec FR-031).
- [ ] T035 Docs and changelog for Mandate B (spec FR-034): `docs/setup.md` (`defaults.cliClients."7.4"`
  with its JDK, client precedence for 7.4), `docs/tools.md` (7.4 health and processes),
  `README.md`, `CHANGELOG.md`.
- [ ] T036 Mandate B verification: full suite on both CI jobs; quickstart validation record
  complete; IMC-6 criteria 1 (detection) and 2 met.

---

## Dependencies & Execution Order

```text
Mandate A
  T001, T002 (setup, parallel)
  T003 → T004 → T005 → T006 → T007          (foundational, sequential: same model files)
  T007 → T008 → T009 → T010                  (US1 core)
         T011, T012 [P] after T009
         T013 after T009; T014 after T013
  T009 → T015 (US2)          T006 + T009 → T016 (US3)
  T001 → T017, T018 [P]; T002 → T019 [P]     (US4 fixtures)
  T020 → T021 → T022                         (US4 recorder, same script)
  all of T001–T022 → T023 (US5) → T024
Mandate B (blocked on the preconditions; after Mandate A is merged)
  T025 → T026 → T027 → T028 → T029 → T030, T031, T032, T033 → T034 → T035 → T036
```

- US2, US3 and US4 depend on the foundation only and can follow US1 in any order; US4's fixture
  tasks (T017–T019) and recorder tasks (T020–T022) are independent of each other.
- Mandate B starts only after the Mandate A PR is merged and its preconditions are met.

## Parallel Example

```text
After T002: T017 (reconstructed cases), T018 (guard) and T019 (help contract test) touch different
files and can run in parallel, while T020–T022 change only tools/record-fixtures.ps1 and its test.
```

## Implementation Strategy

- **MVP**: Phases 1–3 (T001–T014): a pinned 7.4 node is recognised and safely refused on every
  platform (IMC-6 criteria 1 pin and 3).
- **Mandate A complete**: + T015–T024 (criteria 4 and 5, the recording kit, docs). One PR per
  phase or one PR for Mandate A, each with green CI on both jobs.
- **Mandate B**: only after its preconditions; recordings first, then re-clarification, then code.
