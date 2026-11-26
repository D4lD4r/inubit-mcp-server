# Implementation Plan: INUBIT 7.4 Support, CLI Only, Cut into Two Mandates

**Branch**: `claude/inubit-74-support-0b6255` | **Date**: 2026-11-18 (Mandate B replanned
2026-11-26) | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/025-inubit-74-support/spec.md` (Jira intent IMC-6)

## Summary

A node pinned to INUBIT 7.4 (`versionLine: V7_4` or a concrete 7.4 version such as `"7.4.0.90"`)
becomes a known, CLI-only version on which every tool is refused clearly until it is verified. No
tool is added. The work is cut into two mandates:

**Mandate A (no 7.4 node; designed in full here)**

- `V7_4` and the fourth version part (`InubitVersion.build`), with a per-range patch depth so that
  only 7.4 compares four parts (D-1 – D-3);
- a production matrix entry 7.4: CLI-only, every capability `UNSUPPORTED` with the note "not yet
  verified", which drives the refusal texts and the configuration rules (D-4, D-6, D-9);
- a refusing 7.4 port set that builds no REST client (D-5);
- the 404 hint names `V6_1` and `V7_4` (D-7);
- the 7.4 fixture folder with the attached help output as the only real case, the reconstructed
  cases in `RECONSTRUCTED.txt`, `DERIVED.txt` and two guard tests (D-11);
- the Windows recorder with a case table, an interactive-session mode and the 7.4 catalogue (D-12);
- a regression test written against today's behaviour for 6.1, 8.1 and 9.0 (D-13); docs and
  changelog (D-14).

**Mandate B (no 7.4 node; replanned 2026-11-26, designed in full below)**: by owner decision of
2026-11-26 the recording session and the live check will not take place, and 7.4 is offered as
**untested**. On a node pinned to 7.4, `get_health` (MONITORING) and `find_processes`
(PROCESS_DIAGNOSIS) run through StartCLI with the 6.1 adapters and parsers and a 7.4 profile,
declared with the existing `SupportState.UNVERIFIED`, so every result carries the existing
warning "INUBIT <version> on <node> is not verified for <capability>; results may be incomplete".
Every other tool keeps refusing 7.4 as in Mandate A; 7.4 stays read-only; `AUTO` never selects 7.4
(detection withdrawn). The evidence is the reconstructed 7.4 fixtures (built from the 6.1
fixtures, which come from the 6.1 vendor documentation) checked against the real 7.4 help output
(constitution 2.1.0, ratified 2026-11-26, IMC-25); no recording, no live check. By the owner's
second decision of 2026-11-26, INUBIT 6.1 `get_health` and `find_processes` become `UNVERIFIED` on
the same basis, with the existing 6.1 reads, in the same flip (spec US9, FR-036, FR-037). The
recording kit stays as an optional path for a future mandate that may raise 7.4 or 6.1 to
supported.

## Technical Context

**Language/Version**: Java 21 (unchanged); PowerShell 5.1+ for the Windows recorder.

**Primary Dependencies**: unchanged, no new dependency.

**Storage**: none new; fixtures under `src/test/resources/fixtures/v7_4/cli/`.

**Testing**:
- JUnit 5, AssertJ; `FakeProcessLauncher` (fails on any unexpected launch), WireMock with
  `verify(0, anyRequestedFor(anyUrl()))`, the TLS handshake check fake (feature 012) — together they
  prove "no contact" for each refused tool;
- `VersionLineRegressionTest` written first, against the unchanged code;
- `V74FixturesShapeTest`, `V74RecordedFixturesTest`, `V74HelpContractTest`;
- `RecordFixturesPs1Test` (static checks on every platform; the run test on Windows CI only);
- `NoCustomerIdentifiersTest` (locally, with the identifier lists).

**Target Platform**: macOS, Linux and Windows (the refusal rules contain no platform branch; the
suite runs on both CI jobs).

**Project Type**: a single Maven module, built as a shaded JAR.

**Performance Goals**: none new in Mandate A (refusals answer without contact). Mandate B: the
6.1 budget (15 s for `get_health` on one node, feature 012 FR-009), enforced offline by the budget
tests (SC-007); not measured against a real node.

**Constraints**:
- Mandate A: no REST request, StartCLI run or TLS handshake to a node known as 7.4 (FR-007);
  Mandate B: StartCLI (and the TLS handshake for the pin) only for `get_health` and
  `find_processes` on a pinned 7.4 node, never REST, never the 8.1 adapters (FR-030);
- no version-string branching in tool logic: every 7.4 rule is read from the matrix entry (patch
  depth, CLI-only lines, notes);
- 6.1, 8.1 and 9.0 unchanged apart from the 404 hint (FR-020).

**Scale/Scope (Mandate A)**:
- New production classes: `PatchDepth` (domain), `V74PortSet` (adapter). New test classes:
  `VersionLineRegressionTest`, `V74RefusalTest`, `V74PortSetTest`, `V74FixturesShapeTest`,
  `V74RecordedFixturesTest`, `V74HelpContractTest`.
- Extended: `VersionSetting`, `InubitVersion`, `ClientVersion`, `CapabilityMatrix` (7.4 entry,
  `comparePatch`), `VersionGate` (note branch, static `unknown` with lines), `RefusingPorts`,
  `CliClientProbe` (no `-h` while not verified), `ConfigLoader`, `ConfigValidator`, `NodeOverview`
  (hint, client comparison, client note), `ConfigSummary` (client comparison, client note),
  `Wiring`, `list_nodes.output.json`, `tools/record-fixtures.ps1`, `.gitattributes`, docs.

**Scale/Scope (Mandate B, 2026-11-26)**:
- New production classes: `CliLineProfile` (`adapter/cli/v61`), `V74Profile` (`adapter/cli/v74`).
  New test classes: `V74HealthContractTest`, `V74ProcessQueryContractTest`. New fixture:
  `fixtures/v7_4/cli/version.*` (reconstructed).
- Extended: `VersionGate` (unverified on a CLI-only version, `cliMonitoringWarnings`,
  `comparePatch`, client check of FR-029), `NodeOverview`, `HealthService`, `SupportState` (doc),
  `CapabilityMatrix` (`lines()`, the own-client flag, the flip), `UnavailablePart`,
  `V61HealthAdapter`, `V61ProcessQueryAdapter`, `V61QueryRules`, `V61SessionParser` (profile),
  `V74PortSet`, `Wiring`, `EffectiveNodeConfig` / `CliRunner` (client order), `ConfigValidator`,
  `ConfigSummary` (`cliOnly`: unverified reads count as available), `V61Connection` and
  `CliRunner.execute` (the "contacted" marker), `SecretTiming` (keeps warnings), `NodeResult` (warnings on a failure), `ToolErrorException` (`warnings`,
  `withWarnings`), `FanOut` (keeps warnings; deadline `TIMEOUT` with the registered warnings),
  `NodePages` (renders them), `DiagnosisService` and `HealthService` (warnings only on data and
  contacted errors; `get_health` client check before the session for own-client entries),
  `NodeOverview.clientRefusal`, `CliRunner.noHome` / `Selection.entryHint`,
  `find_processes.output.json` (description only), `V74RecordedFixturesTest` and
  `V61RecordedFixturesTest` (guards), the production entries `V6_1` and `V7_4`, docs (incl. 6.1).
  New: `ContactedException` (domain). New test class: `NodePagesTest`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.* Constitution v1.1.0
(Mandate A). Mandate B (replanned 2026-11-26) is checked against v2.1.0, **ratified by the owner
on 2026-11-26 (IMC-25)** (MINOR: Principles III and V add the evidence level *unverified*, on
reconstructed fixtures built from the vendor documentation of the version or from recordings,
vendor documentation or reconstructed fixtures of another version, checked against real output of
the version where any exists), on top of v2.0.0 (ratified 2026-11-23, IMC-23: 7.4 read-only,
CLI-only).

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ✅ read-only; Mandate A refuses everything | ✅ <ul><li>Every 7.4 capability `UNSUPPORTED`, refused before contact (D-4, D-6).</li><li>The 7.4 port set refuses as a second line (D-5).</li><li>Write checks keep their order (FR-007).</li><li>Mandate B: two reads only on 7.4 and on 6.1, on pinned nodes only, every per-node entry after the gate warned; writes stay refused (`UNVERIFIED` is rejected for writes by `CapabilityMatrix`).</li></ul> |
| II. Secrets Stay Out | ⚠️ the recorder gains a session mode with a password | ✅ <ul><li>Password only on stdin; `Assert-NoSecret` on every case (D-12).</li><li>No trust-store password ever reaches the client; 7.4 trust store only through the Java runtime settings, no host-name skipping (D-12, feature 012 FR-012a).</li></ul> |
| III. Test-First | ⚠️ no recorded 7.4 outputs | ✅ <ul><li>Every task starts with a failing test; the regression test is written before the version model changes (D-13).</li><li>No 7.4 capability can become `SUPPORTED` while a case is reconstructed (D-11 guard).</li><li>The one real case (help) has a contract test.</li><li>Mandate B (2.1.0 III, unverified, ratified): 7.4: contract tests of the 6.1 adapters with the 7.4 profile against the reconstructed 7.4 fixtures (derived from the 6.1 fixtures, i.e. from the 6.1 vendor documentation; each listed in `RECONSTRUCTED.txt`), checked against the real 7.4 help (the only real output). 6.1: the existing 6.1 contract tests against the 6.1 fixtures from the vendor documentation (no real 6.1 output exists). No recording, no live check. The guards (FR-015 as amended, FR-037) forbid `SUPPORTED` on 7.4 and 6.1 and `UNVERIFIED` on any capability but monitoring and process diagnosis.</li></ul> |
| IV. Use-Case-Oriented Tools | ✅ no new tool | ✅ unchanged |
| V. Adapter Isolation & Version Compatibility | ⚠️ Principle V names 6.1 as the only CLI-only version | ✅ <ul><li>Mandate A declares no 7.4 capability supported and uses no channel, so it does not invoke the CLI-only exception.</li><li>7.4 code only in `adapter/cli/v74` (port set, `V74Profile`); the 6.1 adapters are reused through a profile, no version branch in tool logic; every rule read from the matrix entry (CLI-only, own-client flag, patch depth).</li><li>Mandate B (2.1.0 V, unverified, ratified 2026-11-26): two read capabilities, monitoring and process diagnosis, `UNVERIFIED` on 7.4 and 6.1, every per-node entry after the gate with the user-visible warning, docs "untested" (FR-034); no write capability; `SUPPORTED` stays forbidden in this feature. 7.4 and 6.1 read-only, CLI-only per 2.0.0.</li></ul> |
| VI. Bounded, Structured Output | ✅ | ✅ refusals actionable (node, version, capability, next step) |
| VII. Observability & Auditability | ✅ | ✅ refusals logged by `VersionGate.refuse()` |
| Workflow: docs in the same change | ✅ | ✅ D-14 in Mandate A; FR-034 for Mandate B (T035 and T036 in one commit) |

**Result**: PASS. No Complexity Tracking entry.

## Project Structure

### Documentation (this feature)

```text
specs/025-inubit-74-support/
├── plan.md
├── research.md                 # D-1…D-15
├── data-model.md
├── quickstart.md               # offline table, recording session (optional), validation record
├── contracts/
│   ├── configuration-delta.md
│   └── mcp-tools-delta.md
├── checklists/requirements.md
├── reviews/                    # spec-r*, plan-r*
└── tasks.md                    # Mandate A tasks, Mandate B tasks (replanned 2026-11-26)
```

### Source Code (repository root)

```text
src/main/java/de/dadecker/inubit/mcp/
├── domain/model/        # VersionSetting (V7_4), InubitVersion (build), PatchDepth (new), ClientVersion (compare with depth),
│                        # CapabilityMatrix (7.4 entry, patchDepth, cliOnlyLines, allNotYetVerified), NodeSummary (doc)
├── adapter/             # RefusingPorts (release in the text)
├── adapter/cli/v74/     # V74PortSet (new)
├── application/         # VersionGate (patch depth in checkClient, 7.4 refusal branch, 404 hint), NodeOverview (client note)
├── config/              # ConfigLoader (error text), ConfigValidator (no warning while all not yet verified), ConfigSummary
└── Wiring.java          # 7.4 registration
src/main/resources/schemas/list_nodes.output.json   # descriptions
src/test/java/...                                   # tests per quickstart table
src/test/resources/fixtures/v7_4/cli/               # README.md, RECONSTRUCTED.txt, DERIVED.txt, help + reconstructed cases
tools/record-fixtures.ps1                           # case table, session mode, v74_* cases
.gitattributes                                      # fixtures/v7_4/** -text
docs/, README.md, CHANGELOG.md
```

**Structure Decision**: the same single module. Stages, each reviewed (creator/reviewer) and each
ending with a green `mvn -q -o clean verify`:

**Mandate A** is delivered as **one PR** (all of stages 1–7): merging part of it would ship `V7_4`
without the refusing matrix entry or without the docs.

1. **Regression baseline**: `VersionLineRegressionTest` against the unchanged code (D-13).
2. **Version model**: `V7_4`, `build`, `PatchDepth`, `ClientVersion.compare` with depth (D-1 – D-3).
3. **Matrix, port set, gate (US1)**: 7.4 entry, `V74PortSet`, `RefusingPorts` text, refusal branch,
   client check with depth, per-tool refusal test, announcement, `list_nodes`, `--check-config`,
   `check_artifacts` (D-4 – D-6, D-8, D-10).
4. **Hint and configuration (US2, FR-010a)**: 404 hint, validator rule, client note (D-7, D-9).
5. **Fixtures and guards (US4)**: folder, help case and its contract test, reconstructed cases,
   `DERIVED.txt`, guard tests (D-11).
6. **Windows recorder (US4)**: case table, session mode, 7.4 catalogue, TLS rule,
   `RecordFixturesPs1Test` (D-12); the `.gitattributes` rule is set with the help case in stage 5
   (T002).
7. **Docs, changelog, quickstart validation (US5)** (D-14). Mandate A is complete here.

**Mandate B** (replanned 2026-11-26; no 7.4 node; one PR after Mandate A is merged). The
original stages 8–10 (recording session, re-clarification from the recordings, live check) are
withdrawn (owner decision 2026-11-26; T026–T029, T034).

8. **Review** of the 2026-11-26 spec, plan and tasks update (`review-spec`, `review-plan`);
   constitution 2.1.0 ratified 2026-11-26 (T030, IMC-25).
9. **Gate and overview** (T038): an `UNVERIFIED` read on a CLI-only version (7.4 and 6.1) runs
   through the entry's channels with the warning; `get_health` reaches such a node over StartCLI;
   `list_nodes` and the `--check-config` summary count it as usable.
10. **Adapters** (T031, T032): the 6.1 StartCLI adapters with the 7.4 profile in the 7.4 port set;
    contract tests against the reconstructed 7.4 fixtures; warning on data entries.
11. **Client selection and `lines()`** (T031 → T033 → T039; T033 shares `HealthService` and
    `VersionGate` with T031 and `CapabilityMatrix` with T039), **guards**
    (T040, T042, test only, in parallel).
12. **Warning on error entries**: marker (T041) → carrier (T044) → services (T045), then **6.1
    verification** (T043).
13. **Flip and docs** (T035 + T036, one commit; 7.4 and 6.1 together), **verification** on both CI
    jobs (T037).

### Mandate B design (2026-11-26)

| Point (T029 was to decide it from recordings) | Decision, from the code and the real 7.4 help | Spec |
|---|---|---|
| support state | existing `SupportState.UNVERIFIED` for MONITORING and PROCESS_DIAGNOSIS on 7.4 and 6.1 (production entries `V7_4`, `V6_1`); no new state; `SUPPORTED` forbidden | FR-030, FR-036 |
| warning scope | a "contacted" marker: `ContactedException` (a `ToolErrorException`, same `ToolError` JSON). Contact begins with the server's own TLS handshake or with the start of the StartCLI process, not with the limiter permit: inside the permit `CliRunner.runSession` still refuses before any launch (Windows credential refusal, `checkLaunch`, the `CredentialGuard` cooldown, StartCLI cannot be started). Moving these before the permit is not clean (cooldown and start failure belong to the launch), so `CliRunner.execute` (`:863`) marks errors raised after the process started, `V61Connection` marks `TlsHandshakeCheck.verify` errors, and `V61HealthAdapter` / `V61ProcessQueryAdapter` mark classification and parse errors of a returned session (T041). Everything else is plain (pre-contact). `DiagnosisService` and `HealthService` attach the admission warnings to data entries, marked errors and deadline `TIMEOUT`s of admitted nodes only (T045) | FR-030 (b) |
| warning carrier | `NodeResult` failure gets `warnings`; `ToolErrorException.warnings` / `withWarnings` (keeps the subtype); `FanOut` maps a thrown exception with warnings to such a failure and gives its deadline `TIMEOUT` (`FanOut.java:82`) the warnings the task registered after admission (per-call sink); `SecretTiming.explain` (`:74-82`) keeps them when it rebuilds a `TIMEOUT`; `NodePages` renders them beside `error`; `find_processes.output.json` description "a failed {node} has only an error" → "an error and possibly warnings" (shape unchanged); `docs/tools.md` (T044). `HealthService.Outcome` (`:921`) carries the marker; the `await` budget `TIMEOUT` and the fan-out backstop `TIMEOUT` (`:246-254`, reached only when a node's thread does not return) count as deadlines of an admitted node and carry the warning (T045) | FR-030 (b) |
| `get_health` client check | `get_health` never reaches `VersionGate.checkClient`; for an entry with the own-client flag `HealthService.checkServerViaCli` runs the client probe before the session and refuses `CLI_UNAVAILABLE` (pre-contact, no warning) when `-v` does not report the line or is unreadable; `NodeOverview.clientRefusal` mirrors it (T033) | FR-029 |
| `--check-config` summary | `ConfigSummary.cliOnly` (`:434-450`) counts an `UNVERIFIED` read on a CLI-only version as available (T038) | FR-010, FR-036 |
| version note | `VersionGate.notes` adds "logs of INUBIT 7.4 (6.1) cannot be read with query_logs; read them in the Workbench" to every admitted `find_processes` entry on 7.4 and 6.1 (existing behaviour, asserted in T032, T043) | FR-028, FR-036 |
| 6.1 specifics | existing 6.1 adapters, texts, budget and version-less `defaults.cliHome` unchanged (no own-client flag); `NOT_AVAILABLE_ON_6_1`; the 6.1 refusal texts of the other capabilities unchanged (`cliOnlyStep`); the 6.1 `--check-config` warning unchanged; `VersionLineRegressionTest` 6.1 rows of MONITORING and PROCESS_DIAGNOSIS (`:53-55`) adjusted deliberately at the flip | FR-020, FR-036 |
| `list_nodes` status | `NodeOverview` gives `partially_supported` for a node with some usable and some refused capabilities (as for 9.0 with unverified partner management); usable names without suffix | US6-14, US9-6 |
| gate on a CLI-only version | `VersionGate.judge` today refuses every non-`SUPPORTED` state on a CLI-only version; it gets an `UNVERIFIED`-read branch: warning, then the client branch with the entry's channels (StartCLI). `monitoringAccess` returns CLI for `UNVERIFIED`; `cliMonitoringWarnings` gives `get_health`'s CLI path the warning. `NodeOverview` lists such a capability as usable | FR-030 |
| adapters | reused: `V61HealthAdapter`, `V61ProcessQueryAdapter`, `V61QueryRules`, `V61SessionParser` take a `CliLineProfile` (release in texts, unavailable reason, `uname` = server version?); `V61Connection`, `V61HealthParser`, `V61PsParser` unchanged (`V61Connection` gets only the T041 marker); `V74Profile` in `adapter/cli/v74` | FR-024 |
| session vs script mode | session, as on 6.1: the reconstructed fixtures are session outputs; the real help shows the interactive mode | FR-024 |
| `uname` version | the client's, not observed (6.1 behaviour, `UNAME_REPORTS_SERVER_VERSION = false`); no `-sv` run; **known limitation** of the unverified level, accepted by the owner 2026-11-26: no server version and no pin contradiction from 7.4 | FR-027 |
| unavailable parts | readiness, threads, blocking queue: `NOT_AVAILABLE_ON_7_4` | FR-026 |
| hanging, time range | on the `DATE` column, as on 6.1; `tag` refused | FR-028 |
| line ends, language | LF fixtures; CRLF and German variants built in the tests from the 7.4 fixtures with the 6.1 German labels, column and state names (no German 7.4 fixture file) | FR-025 |
| client | node `cli.home` → group `cli.home` → `defaults.cliClients."7.4"`; never the version-less `cliHome` (entry flag `ownClientOnly`, tested in T033 with a test matrix, set on the production 7.4 entry in T035; `EffectiveNodeConfig.choose` takes it as a parameter, `cliFor(version)` reads it from `CapabilityMatrix.production()`; `choosesCliByVersion` is true for a setting with the flag; no version check); `-v` must report 7.4 (reconstructed `version` case; `version`, `login_failed` and `unreachable` listed once per capability in `RECONSTRUCTED.txt`, one line per case and capability, as for 6.1); unreadable `-v` fail-closed (`CLI_UNAVAILABLE`) | FR-029 |
| TLS options | decided as for 6.1 today: `V61Connection` takes them from the local `-h` probe (`CliClientProbe.optionsFor`, cached; an unreadable option counts as absent, `NOTHING_KNOWN`); for the real 7.4 help the probe finds neither a trust-store nor a host-name option (`V74HelpContractTest`) | FR-029, FR-035 |
| `lines()` (IMC-16 m6) | notes of a CLI-only entry do not count, so 7.4 never appears in "; supported: …" | FR-035 |
| T009 next step | unchanged (owner, 2026-11-26); "no tool is verified on it yet" stays true for unverified capabilities | FR-007, FR-035 |
| T014 note and warning | disappear with the flip (`allNotYetVerified` false for 7.4); no replacement in `list_nodes` (owner, 2026-11-26) | FR-010a, FR-035 |
| T015 `-h` gate | the `-h` probe runs for 7.4 after the flip (`V61Connection` needs the options); local only | FR-035 |
| `--check-config` without client | the CLI-only warning applies to 7.4 and names `defaults.cliClients."7.4"` | FR-029, FR-035 |
| guard | any `SUPPORTED` on 7.4 fails; `UNVERIFIED` only on the two capabilities, backed by reconstructed cases and the recorded `help` (`V74RecordedFixturesTest`); the same for 6.1 without the `help` condition (`V61RecordedFixturesTest`) | FR-015, FR-037 |
| detection | withdrawn; `AUTO` never selects 7.4, the 404 hint stays | FR-032, FR-033 |
| verification | offline suite on both CI jobs; no live check | FR-031 |

## Complexity Tracking

None.
