# Implementation Plan: INUBIT 9 Support

**Branch**: `017-inubit-9-support` | **Date**: 2026-11-06 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/017-inubit-9-support/spec.md`. Target release 0.8.1,
built on the merged test branch `0.8.0` (012 + 013 + 016).

## Summary

INUBIT 9.0.x becomes a supported version for every capability 8.1 has, and the server picks the
StartCLI client per INUBIT version. No tool is added. Instead:

- **`defaults.cliClients`**: one client directory (and optional JDK) per major.minor, e.g. `"8.1"`
  and `"9.0"`; node/group `cli.home` still win, `defaults.cliHome` stays the version-less fallback
  (D-1, D-3);
- the choice of client, JDK and **default StartCLI address** (servlet path below 9, base URL from 9)
  is computed in the configuration layer from the node's effective version (`cliFor`) and applied
  by `CliRunner` at every launch (D-2, D-4);
- a **client check belongs to its client** (`ClientKey`): a version change of an `AUTO` node never
  reuses the previous client's `-v` result; `withClient` becomes a live question (D-5 … D-7);
- the **matrix declares 9.0 `ALL_SUPPORTED`**, `V9_0` is a new `versionLine`, `V9_X` is unchanged
  (D-9); the 8.1 port set serves 9.0 (D-14);
- REST requests send the **`Accept` their parser reads**, JSON error bodies give the excerpt
  (D-11, D-12);
- `--check-config` checks every client and JDK, `list_nodes` gets an additive **`startCli`**
  field (D-17, D-18);
- documentation-derived **9.0 fixtures** with a **release guard**: 0.8.1 cannot be released
  while a 9.0 case is still reconstructed; the record scripts learn `RECORD_INUBIT_LINE=9.0`
  (D-10).

The live check against a 9.0 node is the person's, in the joint 0.8.x live test; it is a release
blocker (clarify Q3).

## Technical Context

**Language/Version**: Java 21 (unchanged).

**Primary Dependencies**: unchanged (MCP Java SDK, Jackson, logback); no new dependency.

**Storage**: none new; version and client-check state in memory per process; fixtures under
`src/test/resources/fixtures/v9_0/`.

**Testing**:
- JUnit 5, AssertJ; WireMock with `Accept` header matchers;
- `FakeProcessLauncher` / `ScriptedProcessLauncher` recording the launched client, JDK and URL;
- a 9.0 contract test running the 8.1 adapters against the 9.0 fixtures;
- `V90RecordedFixturesTest` (release guard);
- Python `unittest` for the record scripts (`tools/tests`);
- baseline after the 0.8.0 merge: 4998 tests, 0 failures, 51 skipped (surefire + failsafe).

**Target Platform**: macOS, Linux and Windows (StartCLI on Windows per 013); 9.0 clients behave the
same on all three.

**Project Type**: a single Maven module, built as a shaded JAR.

**Performance Goals**: unchanged budgets (009/012); a version change costs at most one extra
`startcli -v` per client and node; results are kept per client key, so switching back reuses the
earlier result (60 s back-off per key after a failure).

**Constraints**:
- FR-009: a profile without `cliClients` and without 9.x nodes behaves as in 0.8.0 (texts,
  addresses, launches);
- `PackageBoundaryTest`: `config` must not depend on `adapter`; `application` only on ports;
- no credential in any new text (SC-005); the new outputs (`list_nodes.startCli`, `--check-config`
  lines) show the address kind, never `cli.url`; existing transport errors keep showing the
  address as in 0.8.0 (`cli.url` carries no user info: `ConfigLoader` refuses `@`);
- a tool never launches a client whose key changed since the last admission without that key
  being checked, nor one whose checked range excludes the selected version (FR-017; launch guard,
  D-4; the guard itself never launches a process); a version change between admission and launch
  is not re-judged for capabilities (as in 0.8.0; accepted, follow-up after 0.8.1);
- no version-string branching in tool logic (FR-014): the gate reads the matrix, the CLI adapter
  reads `CliChoice`;
- `pom.xml` version unchanged (`0.8.0-test.1`; the integration branch owns it).

**Scale/Scope**:
- 0 new tools; `list_nodes` gets `startCli`; schemas `list_nodes.output.json`.
- New production types (about 11): `CliClient`, `CliClientsDeserializer`, `CliChoice`,
  `JdkRelease` (config); `MajorMinor`, `ClientKey`, `ProbedClient` (domain);
  `ClientSelectionPort` (port); `CliClientSelector`, `MediaRange` (adapter); `NodeSummary.StartCli`.
- Extended: `Defaults`, `EffectiveNodeConfig`, `ConfigLoader`, `ConfigValidator`,
  `ConfigSummary`, `VersionSetting`, `CapabilityMatrix`, `NodeVersionState`, `ClientVersionPort`,
  `NodeSummary`, `NodeVersions`, `VersionGate`, `HealthService`, `NodeOverview`, `CliRunner`,
  `CliVersionProbe`, `CliClientProbe`, `CliOutputClassifier`, `V61Connection`,
  `InubitHttpClient`, the V81 adapters (Accept), `PortSetRegistry`, `AdapterGatewayFactory`,
  `Wiring`, `Launcher`, record scripts, docs.
- About 25 test classes move their "undeclared version" example from 9.0.7 to 9.1.0 (D-20).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.* Constitution v1.1.0.

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ⚠️ writes on 9.0 become supported | ✅ <ul><li>Writes stay behind the existing gates (`writeEnabled`, `developmentEnabled`, confirmation, production rules); only the version gate opens for 9.0.</li><li>`deploy_release` stays version-equal (9.0 ↔ 9.0).</li><li>Unknown versions, `V9_X` and 9.1+ keep refusing writes.</li></ul> |
| II. Secrets Stay Out | ✅ | ✅ <ul><li>`ClientKey`, `CliChoice`, `startCli` and every new message carry paths and kinds only, never `cli.url` or credentials.</li><li>JSON error messages are scrubbed like XML excerpts (D-12).</li><li>Password on stdin as today.</li></ul> |
| III. Test-First | ⚠️ no recorded 9.0 outputs | ✅ <ul><li>Every task starts with failing tests (quickstart table).</li><li>9.0 contract tests against documentation-derived fixtures; recordings replace them before release (SC-006, guard).</li></ul> |
| IV. Use-Case-Oriented Tools | ✅ no new tool | ✅ existing tools answer for 9.0; `startCli` explains the choice. |
| V. Adapter Isolation & Version Compatibility | ⚠️ "supported only with recorded responses" | ⚠️ → Complexity Tracking. <ul><li>9.0 access stays in adapters: `PortSetRegistry` range 9.0 → V81 port set, `Accept` per adapter, JSON errors in `InubitHttpClient`.</li><li>Client and address defaults derive from the version in `config` and are applied by the CLI adapter; tool logic does not branch on versions (FR-014).</li><li>CLI argument arrays, timeouts, exit codes unchanged.</li></ul> |
| VI. Bounded, Structured Output | ✅ | ✅ `startCli` is a small fixed object; no new listing. |
| VII. Observability & Auditability | ✅ | ✅ the chosen client and address kind are logged at debug level per launch (no URL); version refusals logged as today. |
| Tech constraints (TLS for CLI) | ✅ | ✅ the pin handshake (`HandshakeStartCliPinProbe`) uses the chosen URL (D-4). |

**Result**: PASS with one justified deviation (Principle V, below).

## Project Structure

### Documentation (this feature)

```text
specs/017-inubit-9-support/
├── plan.md
├── research.md                 # D-1…D-23
├── data-model.md
├── quickstart.md               # offline table, live check, validation record
├── contracts/
│   ├── configuration-delta.md
│   └── mcp-tools-delta.md
├── checklists/requirements.md
├── reviews.md                  # producer/reviewer rounds
└── tasks.md                    # /speckit-tasks
```

### Source Code (repository root)

```text
src/main/java/de/dadecker/inubit/mcp/
├── config/              # Defaults (+cliClients), CliClient, CliClientsDeserializer, EffectiveNodeConfig (CliSources,
│                        # cliFor, withCli), CliChoice, JdkRelease, ConfigLoader (V9_0 text), ConfigValidator (entries,
│                        # JDK, V9_X, servlet, Windows), ConfigSummary (client/address lines)
├── domain/model/        # MajorMinor, VersionSetting (V9_0), CapabilityMatrix (9.0 ALL_SUPPORTED, declares),
│                        # ClientKey, ProbedClient, NodeVersionState (keyed client state), NodeSummary (+startCli)
├── domain/port/         # ClientSelectionPort (new), ClientVersionPort (ProbedClient)
├── adapter/             # PortSetRegistry (9.0 range), AdapterGatewayFactory
├── adapter/cli/         # CliRunner (versions, select, texts), CliClientSelector, CliVersionProbe (ProbedClient, case),
│                        # CliClientProbe (keyed cache), CliOutputClassifier (9.x login hint), HandshakeStartCliPinProbe
├── adapter/cli/v61/     # V61Connection (select at start)
├── adapter/rest/        # InubitHttpClient (MediaRange, JSON errors); v81/* adapters pass their MediaRange
├── application/         # NodeVersions, VersionGate (selection port, warning), HealthService (keyed re-probe),
│                        # NodeOverview (startCli)
├── Launcher.java        # --check-config client probes per distinct client, JDK majors
└── Wiring.java          # CliRunner version source, ClientSelectionPort, tool announcement
src/main/resources/schemas/list_nodes.output.json
src/test/resources/fixtures/v9_0/   # rest/, cli/, <case>.origin, NOT_RECORDABLE.txt, README.md
tools/record-fixtures.sh, tools/tests/
docs/setup.md, docs/tools.md, docs/research/inubit-interfaces.md, README.md, CHANGELOG.md
```

**Structure Decision**: the same single module. Stages, each reviewed by a `code-review` reviewer
and each ending with a green `mvn -o -q clean verify` (plus the Python tests where scripts change):

1. **Version model and matrix**: `MajorMinor`, `V9_0`, 9.0 `ALL_SUPPORTED`, `declares`, the
   `V9_0` texts; move the "undeclared version" examples to 9.1.0 (D-20); `PortSetRegistry` 9.0
   range.
2. **Configuration**: `cliClients` binding and validation, `CliSources`, `cliFor`/`CliChoice`,
   default address by major, `withCli`; FR-009 tests on 0.8.0 profiles.
3. **CLI selection**: `CliRunner` version source and `select` at every entry point,
   `V61Connection`, pin probe, Windows checks on the chosen paths, FR-005 texts, 9.x login hint,
   case-insensitive range.
4. **Client check per client (FR-017)**: `ClientKey`, `ProbedClient`, keyed `NodeVersionState`,
   `ClientCheckLookup` and the launch guard in `CliRunner` (race test: observation changes between
   admission and launch; two overlapping `get_health` calls),
   `CliClientProbe` cache, `ClientSelectionPort`/`CliClientSelector`, `VersionGate` (dynamic
   `withClient`, servlet warning), `HealthService` re-probe, `Wiring`.
5. **REST for 9.0**: `MediaRange` per request, JSON error excerpt, URL-shape test, 9.0 fixtures
   and contract test.
6. **Operator view**: `--check-config` (client probes, JDK majors, warnings, summary lines),
   `list_nodes.startCli` and schema.
7. **Recording and guard**: `RECORD_INUBIT_LINE=9.0` (`.sh`) with tests, `.origin` per case,
   `NOT_RECORDABLE.txt`, `V90Cases` registry, `V90FixtureCatalogueTest` (closed world, both
   directions), `V90RecordedFixturesTest`.
8. **Docs and CHANGELOG 0.8.1**, quickstart validation record.

### Touch points with 012, 013, 016 (already merged; D-22)

- **012** (version/capability matrix, 6.1): 017 edits the 9.0 entry of `CapabilityMatrix`, adds a
  `PortSetRegistry` range, keys `NodeVersionState` client state and the `CliClientProbe` cache;
  `V61Connection` gets the same `select` call. 6.1 behaviour unchanged (tests stay green).
- **013** (Windows): `startcli.bat`, UNC and character refusals, `ProcessContainment` are reused;
  `CliRunner.launchTarget` sees the chosen paths, `ConfigValidator`'s Windows checks include the
  entries. `tools/record-fixtures.ps1` stays unchanged (no recorded Windows fixtures and no
  consumer yet); 9.0 on Windows is a live-check step.
- **016** (update check): no touch point.

## Follow-up (not part of 017)

- **Write race between admission and launch** (plan reviews 3/4, decided by the person): re-judge
  a write on the version at launch under a per-node version lock held until the process start,
  with the write purpose passed through `ImportPort`/`TagPort`. Exists in 0.8.0 as well; planned
  as its own feature after 0.8.1.

## Complexity Tracking

| Violation | Why needed | Simpler alternative rejected because |
|---|---|---|
| Principle V: 9.0 is declared supported before recorded 9.0 responses exist; the contract tests run against documentation-derived fixtures | The person decided (clarify Q2, Q3) that 9.0 is fully supported in 0.8.1 and that the live check happens in the joint 0.8.x live test, as a release blocker; this session has no access to a 9.0 node | Keeping 9.0 `READS_UNVERIFIED` until the recordings exist contradicts the decision and would need a second feature to flip it. The deviation is bounded: `V90RecordedFixturesTest` fails every release build (version without pre-release) while a catalogue case's `.origin` is not `recorded`, and `V90FixtureCatalogueTest` ties fixtures, origin files and contract-test cases to the record scripts' 9.0 catalogue (closed world), so no release declares 9.0 supported without recordings of every recordable case (SC-006); non-recordable cases are listed: write successes confirmed by the live check, REST 403/500/503 documentation-derived with a status-driven, contract-tested mapping (the body only feeds a scrubbed excerpt). |
