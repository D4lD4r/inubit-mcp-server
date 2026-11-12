# Implementation Plan: Test runs leave the real user home untouched

**Branch**: `023-test-home-isolation` | **Date**: 2026-11-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/023-test-home-isolation/spec.md`. Base: `main` at 0.9.1.

## Summary

The composition root `Wiring` reads `System.getProperty("user.home")` in four places (export folder
rules, import backups, deployment ledger, deployment packages), so every test that builds the server
with the test profile `acme` writes into the developer's real `~/.inubit-mcp/acme/`. The fix makes
the home a required constructor parameter of `Wiring`, passed by `Launcher` from the context it
already has; `TestWiring` supplies a fresh temporary home per server; the seven tests that call the
constructor directly pass a `@TempDir`; `LiveTarget` uses one temporary home shared by its wirings
and moves audit, workspace and knowledge of the live runs into temporary directories. A static guard
test (`UserHomeReadGuardTest`) keeps every other production file from reading the process home, and
a behavioural test in `WiringAuditTest`, committed red first, proves that the import backup lands
below the handed-in home and not below the real `~/.inubit-mcp`. Details and alternatives: [research.md](research.md).

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: unchanged (MCP Java SDK, Jackson, logback); no new dependency

**Storage**: files below `<home>/.inubit-mcp/<profile>/` (unchanged layout, see
[data-model.md](data-model.md))

**Testing**: JUnit 5 + AssertJ via Maven surefire (unit) and failsafe (`*IT` against the shaded JAR);
live groups `live`, `live-store` opt-in

**Target Platform**: macOS, Linux, Windows (the server); the guard test runs on every platform

**Project Type**: single Maven project (MCP server, stdio)

**Performance Goals**: none; the guard scans about 400 source files in well under a second

**Constraints**: no behaviour change for a normally started server (FR-009); no fallback to the
process home in any `Wiring` constructor (FR-002)

**Scale/Scope**: 3 production files (`Wiring` incl. a package-private `homeLocations()` test seam,
research D-6a; `Launcher`; `ConfigLoader`), the test helper `TestWiring`, `LiveTarget`, 7 tests with
direct `new Wiring(...)`, 2 new test classes (`UserHomeReadGuardTest`, `WiringHomeLocationsTest`) and
new test methods in `WiringAuditTest`, `ExportArchivesWiringTest`, `MainTest`, `LiveTargetTest`

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Note |
|---|---|---|
| I. Safe by Default | PASS | No tool, guard or confirmation changes; the forbidden export root still follows the home of the running server. |
| II. Secrets Stay Out | PASS | Credential store lookup unchanged; live tests keep reading the real keychain; no secret in any new output. |
| III. Test-First | PASS | `WiringAuditTest#anImportLeavesTheRealHomeUntouched` (real-home assertion) and `UserHomeReadGuardTest` are committed and observed red before the production change (D-5, D-6). |
| IV. Use-Case Tools | N/A | No tool added or changed. |
| V. Adapter Isolation | N/A | No adapter touched. |
| VI. Bounded Output | N/A | No output changes. |
| VII. Observability & Audit | PASS | Audit log location of a normal server unchanged; tests and live tests write their audit records into temporary directories (D-4). |
| Quality gates | PASS | `mvn verify` green; marker check of SC-001 recorded in quickstart. |

Post-design re-check: unchanged, PASS.

## Project Structure

### Documentation (this feature)

```text
specs/023-test-home-isolation/
├── spec.md
├── plan.md              # this file
├── research.md          # D-1 … D-8
├── data-model.md        # the home-derived locations
├── quickstart.md        # validation incl. marker check
├── contracts/
│   └── wiring-home.md   # Wiring / TestWiring signature contract
├── checklists/requirements.md
├── reviews/
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
src/main/java/de/dadecker/inubit/mcp/
├── Wiring.java                 # + Path userHome in every constructor; 4 reads replaced;
│                               #   package-private homeLocations() seam (D-6a)
├── Launcher.java               # passes context.userHome() to Wiring
└── config/ConfigLoader.java    # forSystem() removed

src/test/java/de/dadecker/inubit/mcp/
├── TestWiring.java             # fresh temp home per server, userHome(), cleanup; withHome(…)
├── WiringAuditTest.java        # + anImportLeavesTheRealHomeUntouched, red first (D-6)
├── ExportArchivesWiringTest.java       # + forbidden root follows the home (D-6)
├── WiringHomeLocationsTest.java        # new: reference locations (FR-009)
├── MainTest.java               # + Context.system hands over user.home
├── architecture/UserHomeReadGuardTest.java # new, red first (D-5)
├── live/LiveTarget.java        # shared temp home + isolated audit/workspace/knowledge (D-4)
├── live/LiveTargetTest.java    # + isolation assertions
└── ToolAnnouncementTest, KnowledgeToolsWiringTest, MessageTerminologyTest,
    StartCliClientWiringTest, WiringE2eTest, KnowledgeProfilesTest   # pass @TempDir
```

**Structure Decision**: single project, existing packages; no new production class (only the
package-private record of the `homeLocations()` seam inside `Wiring`).

## Delivery order

1. Red: `WiringAuditTest#anImportLeavesTheRealHomeUntouched` (real-home assertion only) and
   `UserHomeReadGuardTest`; run both, record the red output (the import's backup lands in the real
   home; the guard lists 5 lines).
2. Remove the dead `ConfigLoader.forSystem()`.
3. Green: `Wiring`/`Launcher` change, `TestWiring` temp home, the seven direct callers, then the
   temp-home assertions in `WiringAuditTest` and `ExportArchivesWiringTest`.
4. `WiringHomeLocationsTest` and the `MainTest` assertion (FR-009).
5. `LiveTarget` isolation (compile + `LiveTargetTest`; one live run when a target is available).
6. Docs: `CHANGELOG.md` "Fixed" line; `docs/live-tests.md` live-test artefact locations.
7. Validation per [quickstart.md](quickstart.md): `mvn verify`, marker check.
8. After merge: move `~/.inubit-mcp/acme` to the trash.

## Complexity Tracking

No constitution violations; nothing to justify.
