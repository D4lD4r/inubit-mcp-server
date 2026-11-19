# Research: INUBIT 7.4 Support, CLI Only, Cut into Two Mandates

Phase 0 output of `/speckit-plan` for [spec.md](spec.md). Facts about this code base were verified
in the source on 2026-11-18 at `main` 0.10.2 (`9447c5c3`); paths are relative to
`src/main/java/de/dadecker/inubit/mcp/` (`M/`) and `src/test/java/de/dadecker/inubit/mcp/` (`T/`).
Facts about INUBIT 7.4 come only from the IMC-6 attachment `startcli.sh.help.txt`; there is no vendor
reference. Items marked **REC** are decided by the Mandate B recordings; Mandate A builds nothing that
depends on them.

---

## D-1 The line value `V7_4` and concrete 7.4 pins (FR-001)

- **Decision**: add `Line V7_4 = new Line(7, OptionalInt.of(4))` to `M/domain/model/VersionSetting.java`
  (:14-18), its `parse` case (:28-38) and the Javadoc lists; extend the load error text in
  `M/config/ConfigLoader.java:807` to "expected AUTO, V6_1, V7_4, V8_1, V9_0, V9_X or a full version
  such as \"8.1.17\""; extend the description strings of `src/main/resources/schemas/list_nodes.output.json`
  (:63, :117) and `M/domain/model/NodeSummary.java:22`. Concrete pins `"7.4.0"`, `"7.4.0.90"`,
  `"7.4.0.90-HF1"` already parse (`InubitVersion` regex, patch present).
- **Rationale**: mirrors `V6_1` (feature 012 D-1); the version setting is the one place that selects
  adapters (Constitution V).
- **Alternatives**: `V7_X` — rejected, the spec declares 7.4 only (Edge Cases).

## D-2 The fourth version part (FR-002, FR-004)

- **Decision**: `InubitVersion` gets a fifth component `OptionalInt build` (the fourth numeric part).
  The regex (`:23-24`) becomes `^(\d{1,9})\.(\d{1,9})(?:\.(\d{1,9})(?:\.(\d{1,9}))?)?(?!\d)`; a
  fourth part is read only when a patch is present. A four-argument constructor
  `InubitVersion(major, minor, patch, raw)` stays (build empty) so the existing call sites and tests
  compile unchanged. `compareTo` and `sameMinor` are unchanged (FR-004); `equals` already differs by
  `raw`, so adding `build` changes no existing equality. Display already uses `raw`, which keeps all
  four parts.
- **Rationale**: the smallest change that keeps the part as data; ordering, range membership and
  `cliClients` selection by major.minor stay untouched.
- **Alternatives**: parse the fourth part out of `raw` where needed — rejected, scattered parsing.

## D-3 Patch comparison per range (FR-003)

- **Decision**: `CapabilityMatrix.Entry` gets a component `PatchDepth patchDepth` (`THREE` default,
  `FOUR` for the 7.4 entry), with `CapabilityMatrix.patchDepth(Optional<InubitVersion>)` (`THREE` for
  unknown versions and versions outside every range). `ClientVersion.compare(server)` (`:42-48`)
  gets an overload `compare(server, PatchDepth)`; the old one delegates with `THREE`. With `FOUR`,
  after an equal major.minor.patch: build equal or both absent → `MATCH`, different → `MISMATCH`,
  present on one side only → `NOT_COMPARABLE`. One helper `CapabilityMatrix.comparePatch(ClientVersion,
  Optional<InubitVersion> server)` calls `client.compare(server, patchDepth(server))`, and **every**
  call site of `ClientVersion.compare` uses it: `VersionGate.checkClient` (`:819`),
  `ConfigSummary` (`:408`, "client vs pin" in `--check-config`; the static `client` method gets the
  `PatchDepth` from its caller), `NodeOverview` (`:145` client warning and `:250` client line of
  `list_nodes`) and, in Mandate B, `HealthService` (`:686`, which has no matrix; through a
  `VersionGate.comparePatch` that delegates to the gate's matrix).
  In Mandate A the visible effect is the `--check-config` and `list_nodes` client line of a 7.4
  node (the gate's client check never runs for 7.4 while every capability is refused).
- **Rationale**: the rule lives in the domain matrix, not in version checks (Constitution V); 6.1
  keeps `THREE`, so its fourth part stays ignored (feature 012 FR-002); 8.1/9.0 versions have three
  parts.
- **Alternatives**: compare four parts for every version — rejected, it changes 6.1 (criterion 5).

## D-4 The 7.4 matrix entry (FR-006, FR-010)

- **Decision**: `CapabilityMatrix` gets
  `Entry V7_4 = new Entry(new VersionRange(7, 4, 7, 4), Map.of(), Map.of(MONITORING, Set.of(CLI),
  PROCESS_DIAGNOSIS, Set.of(CLI)), true, notYetVerified(ALL capabilities), FOUR)` — CLI-only, every
  capability `UNSUPPORTED` (absent = unsupported, `:16-18`), the two CLI channels prepared for
  Mandate B, and the note "not yet verified" on every capability (the mechanism of feature 024,
  `notYetVerified`, `:79-95`). `PRODUCTION` becomes `List.of(V6_1, V7_4, V8_1, V9_0)` (`:118`).
- **`lines(capability)`** (`:199-206`) today counts an entry with `SUPPORTED` or a note ("supported
  once verified") and feeds `VersionGate.supportedLines` ("; supported: 8.1, 9.0" in refusals of the
  queue-entry, server-log and node-tool capabilities). It ignores the notes of an entry on which
  every capability carries a note, so 7.4 never appears there and the 6.1, 8.1, 9.0 and unknown texts
  stay unchanged.
- **Rationale**: the note is the single switch that tells "recognised but not verified" apart from
  6.1's "CLI-only, refused": the refusal text (D-6) and the configuration warning (D-9) read it, and
  Mandate B removes it per capability together with the state flip.
- **Alternatives**: a separate `recognisedOnly` flag on the entry — rejected, the note already
  exists and is removed capability by capability.

## D-5 The 7.4 port set: refusing only, no REST client (FR-011)

- **Decision**: new `M/adapter/cli/v74/V74PortSet` with `RANGE = new VersionRange(7, 4, 7, 4)`, a
  `factory` that returns `RefusingPorts` for every port, and a `cliHealth` factory that returns a
  refusing `CliHealthPort`. `Wiring` (`:474-476`) registers it after 6.1 through
  `PortSetRegistry.register(range, factory, V61QueryRules.INSTANCE, cliHealth)`, which marks the
  range `NodeAccess.CLI_ONLY` and builds no REST client (`Slot.ports()` skips the client for CLI-only
  factories, feature 012 D-4). `RefusingPorts` (`:81-88`) takes the release and the likely cause in
  its constructor: message "(INUBIT <release>, StartCLI only)", likely cause for 7.4 "INUBIT 7.4 is
  recognised, but no tool is verified on it yet"; both 6.1 texts are unchanged. `V61QueryRules` is never reached
  for 7.4 (the gate refuses first); it is passed only because `register` needs rules.
- **Rationale**: second line of defence (spec FR-011), same shape as 6.1, nothing that could talk to
  a 7.4 node.
- **Alternatives**: register the 6.1 adapters for 7.4 now — rejected: their request shapes are
  unverified on 7.4 (criterion 3); Mandate B decides from the recordings (D-15).

## D-6 Refusal texts on 7.4 (FR-007, FR-012)

- **Decision**: the existing note branch of `VersionGate.refuseUnsupported` (`:878-885`) keeps its
  message ("INUBIT 7.4 on legacy74/node1: monitoring is not yet verified for INUBIT 7.4"; `named()`
  `:448-460` prefixes the tool, `refuse()` appends "; nothing was sent") and changes only for a
  CLI-only version: the next step becomes "INUBIT 7.4 is recognised, but no tool is verified on it
  yet; use the INUBIT Workbench for `<label>` on `<node>`", and for INVENTORY the suffix "; modules
  of INUBIT 7.4 cannot be looked up" of the CLI-only branch (`:893-894`) is appended to the message,
  because `check_artifacts` uses that message as its notice (`ArtifactCheckService.java:528-535`).
  Full example (`get_health`, whose per-node refusal names no tool, as on 6.1; every other tool's
  message starts with `<tool>: `, IMC-14 option 1): `INUBIT 7.4 on legacy74/node1: monitoring is
  not yet verified for INUBIT 7.4; nothing was sent`. 6.1 has no notes (unchanged texts); 8.1/9.0 are not
  CLI-only (unchanged texts). `refuse()` logs as today (FR-012, feature 012 D-14). The order of the
  write checks and the gate is untouched, so it is the same as for 6.1 (FR-007). `notes()`
  (`:946-955`) adds no note for 7.4, because no admission happens.
- **Rationale**: the release comes from the matrix entry, not from a version string.

## D-7 The 404 hint (FR-005)

- **Decision**: `VersionGate.unknown` (`:1004-1011`) stays static and gets a parameter
  `List<String> cliOnlyLines` (from `matrix.cliOnlyLines()` → `6.1`, `7.4`, in matrix order), from
  which it builds the hint "; if <node> runs INUBIT 6.1, pin V6_1; if it runs INUBIT 7.4, pin V7_4".
  Callers: inside `VersionGate` and `NodeOverview.java:194` (the `list_nodes` path), both pass the
  lines of their matrix; static test callers pass them explicitly. Tests of the 6.1 hint
  (`docs/tools.md:294`, `docs/setup.md:323` and the hint tests) are the deliberate change of SC-005.
- **Rationale**: a 404 cannot tell 6.1 from 7.4; the hint lists every pinned-only line.

## D-8 Announcement, `list_nodes`, `--check-config`, `check_artifacts` (FR-008 – FR-010)

- **Decision**: no new code beyond D-3, D-4, D-6, D-7 and D-9: the read-tool announcement (feature 012 D-12),
  `NodeOverview` (`:99-117`, `access: cli_only`, capabilities from the entry), `ConfigSummary.cliOnly`
  (`:427-444`, "INUBIT 7.4, StartCLI only; available: none; refused: …") and the `check_artifacts`
  lookup skip (feature 012 FR-006a) are all driven by `isCliOnly` and the states. Tests pin each for
  7.4.
- **Rationale**: the 6.1 machinery is version-neutral; the tests prove it for 7.4.

## D-9 Configuration rules for a 7.4 node (FR-010a)

- **Decision**: `ConfigValidator` (`:779-786`) warns today for a CLI-only pin without a client
  ("INUBIT 6.1 is reached through StartCLI only, but no cliHome is configured …"). The warning stays
  for a CLI-only version without notes (6.1, text unchanged) and is not given while every capability
  of the version carries the note (7.4 in Mandate A): no tool would use the client. The
  `defaults.cliClients."7.4"` key needs no change (any quoted major.minor is valid; feature 017);
  `--check-config` runs its local `-v` probe as for every entry (`Launcher.java:428`) and reports a
  version it cannot parse as today; `SHIPPED_JAVA` (`:65-67`) gets no 7.4 entry, so no JDK warning.
  The note "not used until a 7.4 capability is verified" goes (keyed on the same notes) into the
  `--check-config` client text line (`ConfigSummary.client`) and, for `list_nodes`, into the node's
  `warnings` list (a free string array in `list_nodes.output.json`; no schema change):
  "INUBIT 7.4 is recognised, but no tool is verified on it yet; the StartCLI client of <node> is not
  used until a 7.4 capability is verified".
- **Client option probe (`-h`)**: `CliClientProbe.optionsFor` (`:75-99`) runs the client's `-h`
  for every CLI-only node, also in `--check-config` (`Launcher.java:420-441`), and
  `ConfigValidator.clientOptionWarnings` (`:1511-1535`) then warns, e.g. "… refused (TLS_ERROR)".
  Decision: `optionsFor` returns empty (as for a non-CLI-only node) while
  `matrix.allNotYetVerified(pin)`, so in Mandate A no `-h` runs for a 7.4 node and no option warning
  or option line appears; only the generic `-v` probe of feature 017 runs (spec FR-010a). Mandate B
  turns it on with the notes.
- **Release in the warning text**: the "reached through StartCLI only" warning takes the release from
  the pin (`INUBIT <major.minor>`), so the 6.1 text is unchanged and a 7.4 text is right once Mandate B
  turns the warning on.
- **Rationale**: FR-010a; the note switch from D-4 again keeps version strings out.

## D-10 Platforms (FR-013)

- **Decision**: the platform does not enter the matrix or the gate (only `CliRunner`,
  `CliOutputClassifier`, `CliClientSelector` and `checkCredentials`, all after the gate). The 7.4
  refusal tests are plain JUnit tests, so they run on the macOS/Linux and the Windows CI jobs alike;
  one test per tool asserts zero process launches (`FakeProcessLauncher` with no case), zero HTTP
  requests (WireMock `verify(0, …)`) and zero TLS handshakes (the handshake check fake).
- **Rationale**: SC-003 per platform without platform branches.

## D-11 7.4 fixtures, `RECONSTRUCTED.txt`, `DERIVED.txt` and guards (FR-014 – FR-016a)

- **Decision**:
  - New folder `src/test/resources/fixtures/v7_4/cli/` with `README.md`, `RECONSTRUCTED.txt`
    (format of 6.1: `<case> <capability>`) and `DERIVED.txt` (format `<case> <source-case>
    <capability> <edit>`; empty in Mandate A except comments).
  - `help.stdout` = the attachment byte for byte (LF, as produced by `startcli.sh`), `help.exit` =
    `0`, `help.stderr` empty; the README states that exit code and stderr were assumed. The
    README has a section "Recorded cases" (one case per line), the single source the guard reads
    for recorded cases (spec FR-015); in Mandate A it lists `help`.
  - The other cases of the spec's FR-014 table are reconstructed from the 6.1 cases (same commands,
    same layout) with neutral names (`legacy74/node1`, owner `jdoe`, host
    `inubit-legacy74-1.example.test`) and listed in `RECONSTRUCTED.txt`; the derivable ones are
    listed there too until a source exists (FR-016a).
  - `T/adapter/cli/V74FixturesShapeTest` (as `V61FixturesShapeTest`: README, line format, three
    files per case, `help` not listed) and `T/adapter/cli/V74RecordedFixturesTest` (as
    `V61RecordedFixturesTest` plus the `DERIVED.txt` rule and a self-test with a flipped matrix).
    Both share a small `CaseLists` helper with the 6.1 tests if the duplication exceeds a few lines.
  - `FakeProcessLauncher.replaying` accepts `v7_4` (it already takes the version, feature 012 T002).
  - The help case gets one real contract test: `CliClientProbe` with a **test** matrix whose 7.4
    entry is CLI-only without notes (so `optionsFor` runs `-h`) replays the 7.4 help and reports
    `--execCommand` and neither a trust-store nor a host-name option (input for FR-029 / feature 012
    FR-012a). With the production matrix it runs nothing (D-9).
  - `NoCustomerIdentifiersTest` scans the folder automatically (all git files).
- **Rationale**: Constitution III; FR-015 is enforced by tests, not by discipline.

## D-12 The Windows recorder: 7.4 catalogue and session mode (FR-016 – FR-019)

- **Decision** (`tools/record-fixtures.ps1`, today 6 `win_*` cases, `$Cases` `:87-94`, no session):
  - The catalogue becomes a table of case objects: name, description, kind (`local`, `exec`,
    `direct`, `session`, `failure`), arguments or session commands, needs credentials, output
    folder. All 7.4 arguments and commands, including the end-of-session command (`exit`, as in the
    6.1 sessions), live only there (FR-018). `win_*` cases keep their behaviour and folder
    (`fixtures/windows/`); `v74_*` cases write to `fixtures/v7_4/cli/` under their fixture names
    (`v74_ps_error` → `ps_session_error`, `v74_failure` → `unreachable`, …; research cases keep a
    `v74_` prefix inside the folder: `v74_version`, `v74_exec_help`, `v74_exec_ps_help`,
    `v74_direct_health`).
  - New function `Invoke-StartCliSession`: one `startcli.bat` process via the existing
    `Invoke-StartCli` (`:182`, `cmd.exe /d /v:off /s /c`, allowlisted environment), standard input =
    password line, commands, end-of-session command; output handled by the existing private
    directory, path/host replacement, `neutralize.py` and `Assert-NoSecret` (`:471`).
  - Every 7.4 case that contacts a server calls `Assert-AllowedGroup` and `Assert-AllowedHost`
    (`:115-138`, `RECORD_ALLOWED_HOSTS` = non-production hosts) before any launch (FR-017).
  - Every 7.4 case that logs in (exec, direct, session) gets the password on standard input only,
    as the `win_*` script-mode cases do (`Read-PasswordBytes`, `:258`); never `-p`, never
    `--encryptString`; `Assert-NoSecret` runs for each (spec FR-017).
  - TLS for 7.4 cases: `Get-TlsArguments` (`:269-276`) is not used; `-TrustStore` is passed as
    `-Djavax.net.ssl.trustStore=<path>` (and `-Djavax.net.ssl.trustStoreType` from the extension)
    appended to the allowlisted `JAVA_TOOL_OPTIONS`; `-DisableHostNameVerification` with a 7.4 case
    stops the run (FR-017).
  - `v74_login_failed` uses a user name that does not exist (parameter `-FailedLoginUser`, default
    `mcp-record-no-such-user`) and the dummy password of `win_failure` against the real node;
    `v74_failure` uses `$FailureUrl` (`:103`), no server.
  - `.gitattributes` gets `src/test/resources/fixtures/v7_4/** -text` (byte-exact, FR-019; the rule
    of feature 013 R-13 for `fixtures/windows/`).
  - `T/tools/RecordFixturesPs1Test` gains the static checks for the 7.4 cases (listed, StartCLI
    only, no `-p`, password only on stdin, allowlists before launch, no client TLS options for 7.4,
    `-text` rule); its Windows-only run test (`:175-205`) gets a session case against the substitute
    client used today (`recordsTheClientVersionThroughTheSubstitute`).
- **Rationale**: reuses every safeguard of feature 013; the session mode is the 6.1 mode of the
  bash recorder (`record_cli_session`, `tools/record-fixtures.sh:764-800`) in PowerShell.
- **Alternatives**: add the 7.4 cases to `tools/record-fixtures.sh` — rejected, the owner records on
  Windows only (spec Out of Scope).

## D-13 Regression (FR-020, SC-005)

- **Decision**: a parameterised `T/application/VersionLineRegressionTest` (in `application` for the
  package-private `VersionGate` helpers) pins, for `V6_1`, `V8_1`, `V9_0`, `V9_X` and the concrete pins
  `6.1.0.89`, `8.1.17`, `9.0.10`: parsing, `ClientVersion.compare` (today's three-part rule; later
  tasks route it through `comparePatch`, which must give the same results for these lines), the gate
  decision per capability with the production matrix, the refusal texts, and the `; supported: …`
  texts (a queue-entry refusal on 6.1 and on an unknown version). It
  is written **before** D-2/D-3/D-4 and must pass unchanged after them. The existing per-version
  tests (`V61*`, `V81*`, `V90*`, `CapabilityMatrixTest`, `VersionGateTest`, `ClientVersionTest`,
  `InubitVersionTest`, `VersionSettingTest`) stay unchanged except for the 404 hint texts.
- **Rationale**: criterion 5 is proven by a test written against the old behaviour.

## D-14 Documentation and changelog (FR-020)

- **Decision**: in the same change: `docs/setup.md` (requirements row :24, versionLine table
  :262-274, the 404 hint :323, a new section "INUBIT 7.4 nodes (`V7_4`)" after the 6.1 section
  :365-400, `--check-config` summary :2527-2551), `docs/tools.md` (capability table with a 7.4
  column :183-221, hint :294, version section after :399-422), `README.md` (supported versions
  :159-176), `docs/research/inubit-interfaces.md` (a 7.4 section: help-output facts, what is
  unknown), `CHANGELOG.md` (`[Unreleased]`, Added: 7.4 recognised and refused; Changed: the 404 hint
  names `V7_4`).
- **Note**: `README.md:176` still says 6.1 is "not on Windows" although `docs/setup.md:370-373` says
  otherwise; out of scope here, listed as a follow-up.

## D-15 Mandate B outline (FR-021 – FR-034) (REC)

- **Decision (default, re-decided after the recordings)**:
  - The recording session (quickstart) runs the research cases first (`v74_exec_help`,
    `v74_exec_ps_help`, `v74_direct_health`, `v74_version`), adjusts the catalogue if commands
    differ, then the session cases.
  - Channel: if the interactive commands and outputs match 6.1, the 7.4 port set reuses
    `V61HealthAdapter` / `V61ProcessQueryAdapter` with 7.4 parsers or parser profiles; if
    `--execCommand` is the more reliable path (it exists on 7.4, not on 6.1), a script-mode adapter is
    designed instead. Either way the password stays on stdin.
  - Client selection (FR-029): `CliClientSelector` gets the rule "a CLI-only version never falls back
    to the version-less `defaults.cliHome`" for 7.4 (a matrix flag on the entry, not a version check),
    and the chosen client's `-v` must report major.minor 7.4, else `CLI_UNAVAILABLE`.
  - Detection (FR-032): the live check records the detection failure code of a 7.4 node on `AUTO`;
    the trigger and the `-sv` step are designed from it.
  - Constitution amendment (MINOR) before the first `SUPPORTED`.
  - Everything keyed on "every 7.4 capability carries a note" switches together at the first flip:
    the `lines()` exclusion (D-4; 7.4 must not then appear in "; supported: …" for queue entries,
    server logs and node tools, which stay out of scope on 7.4), the configuration warning and the
    `-h` option probe (D-9). Mandate B replaces the switch by an explicit rule.
  - Texts to revisit when the first 7.4 capability is flipped: the next step "no tool is verified on
    it yet" (D-6) stays on the capabilities that keep the note and must then read "this capability is
    not verified on INUBIT 7.4 yet"; the client option probe and its warnings turn on (D-9).
- **Rationale**: none of these can be decided without the recordings; designing them blind would
  produce parsers for outputs nobody has seen.
