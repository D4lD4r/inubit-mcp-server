# Implementation Plan: INUBIT 7.4 Support, CLI Only, Cut into Two Mandates

**Branch**: `claude/inubit-74-support-0b6255` | **Date**: 2026-11-18 | **Spec**: [spec.md](spec.md)

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

**Mandate B (needs a 7.4 node; outlined only, D-15)**: the recording session, the replacement of
reconstructed cases, the 7.4 health and process adapters, client selection without the
version-less fallback, detection, the constitution amendment and the matrix flip. Its design is
re-decided from the recordings by a spec, plan and tasks update before any Mandate B code is
written (spec, "Planning depth").

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

**Performance Goals**: none new in Mandate A (refusals answer without contact). Mandate B: 15 s for
`get_health` on one 7.4 node and for listing up to 100 processes (SC-007).

**Constraints**:
- no REST request, StartCLI run or TLS handshake to a node known as 7.4 (FR-007);
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
  `HealthService` (client comparison through `comparePatch`), `Wiring`, `list_nodes.output.json`, `tools/record-fixtures.ps1`, `.gitattributes`, docs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.* Constitution v1.1.0.

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ✅ read-only; Mandate A refuses everything | ✅ <ul><li>Every 7.4 capability `UNSUPPORTED`, refused before contact (D-4, D-6).</li><li>The 7.4 port set refuses as a second line (D-5).</li><li>Write checks keep their order (FR-007).</li></ul> |
| II. Secrets Stay Out | ⚠️ the recorder gains a session mode with a password | ✅ <ul><li>Password only on stdin; `Assert-NoSecret` on every case (D-12).</li><li>No trust-store password ever reaches the client; 7.4 trust store only through the Java runtime settings, no host-name skipping (D-12, feature 012 FR-012a).</li></ul> |
| III. Test-First | ⚠️ no recorded 7.4 outputs | ✅ <ul><li>Every task starts with a failing test; the regression test is written before the version model changes (D-13).</li><li>No 7.4 capability can become `SUPPORTED` while a case is reconstructed (D-11 guard).</li><li>The one real case (help) has a contract test.</li></ul> |
| IV. Use-Case-Oriented Tools | ✅ no new tool | ✅ unchanged |
| V. Adapter Isolation & Version Compatibility | ⚠️ Principle V names 6.1 as the only CLI-only version | ✅ <ul><li>Mandate A declares no 7.4 capability supported and uses no channel, so it does not invoke the CLI-only exception.</li><li>7.4 code only in `adapter/cli/v74`; every rule read from the matrix entry.</li><li>Mandate B needs the MINOR amendment (7.4 read-only, CLI-only, REST unverified) before its first `SUPPORTED`; it is a Mandate B task gated on the owner.</li></ul> |
| VI. Bounded, Structured Output | ✅ | ✅ refusals actionable (node, version, capability, next step) |
| VII. Observability & Auditability | ✅ | ✅ refusals logged by `VersionGate.refuse()` |
| Workflow: docs in the same change | ✅ | ✅ D-14 in Mandate A; FR-034 for Mandate B |

**Result**: PASS. No Complexity Tracking entry.

## Project Structure

### Documentation (this feature)

```text
specs/025-inubit-74-support/
├── plan.md
├── research.md                 # D-1…D-15
├── data-model.md
├── quickstart.md               # offline table, recording session, validation record
├── contracts/
│   ├── configuration-delta.md
│   └── mcp-tools-delta.md
├── checklists/requirements.md
├── reviews/                    # spec-r*, plan-r*
└── tasks.md                    # Mandate A tasks, Mandate B tasks (blocked)
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
6. **Windows recorder (US4)**: case table, session mode, 7.4 catalogue, TLS rule, `.gitattributes`,
   `RecordFixturesPs1Test` (D-12).
7. **Docs, changelog, quickstart validation (US5)** (D-14). Mandate A is complete here.

**Mandate B** (blocked on the preconditions; D-15)

8. **Recording session** on Windows (manual, approved; quickstart), commit of the neutralized
   recordings.
9. **Re-clarification**: research addendum from the recordings, spec/plan/tasks update for
   adapters, client selection and detection; constitution amendment proposal.
10. **Implementation** of the updated Mandate B tasks, live checks on Windows and macOS/Linux,
    matrix flip, docs (FR-034).

## Complexity Tracking

None.
