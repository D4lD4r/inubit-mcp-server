# Implementation Plan: Order-Insensitive Comparison of Workflow Connections

**Branch**: `007-connection-order-compare` | **Date**: 2026-10-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/007-connection-order-compare/spec.md`

## Summary

INUBIT writes the outgoing `<Connection>` children of a `<WorkflowModule>` in a non-deterministic
order, and every content comparison of the server is order-sensitive. Fix for 0.5.1: one shared,
package-private rule in `adapter/archive/v81` orders those connections (by `moduleOutId`, then
`ConnectionId`, numerically; otherwise by normalized text; other children keep their slots). The
import and release archive ports apply it in every comparison and in the forms that fingerprints
are computed over; imported, deployed and stored content is unchanged. Fingerprints persisted by
≤ 0.5.0 are still recognised. Workspace writes keep an existing workflow file that differs only in
connection order (operator decision B), so exports stop reporting pseudo-`MODIFIED`.

## Technical Context

**Language/Version**: Java 21 (project baseline), Maven

**Primary Dependencies**: none new; existing `XmlTree`/`XmlNormalizer` in `adapter/archive/v81`

**Storage**: unchanged formats (workspace git repository, backup manifests, deployment ledger)

**Testing**: JUnit 5 + AssertJ; existing fakes (`FakeInubit`, `FakeServer`), harnesses
(`ImportHarness`, `DeployHarness`, `ExportHarness`) and fixtures (`grp-a.zip`)

**Target Platform**: the MCP server JAR (stdio), operator workstation

**Project Type**: single Java project (hexagonal: `domain/port`, `application`, `adapter`)

**Performance Goals**: no noticeable change; the ordering is linear in the number of elements per
workflow file

**Constraints**: imported/deployed/stored bytes unchanged (FR-006); difference reports unchanged
(FR-007); no customer identifiers in the repository (`NoCustomerIdentifiersTest`)

**Scale/Scope**: patch release 0.5.1; affects comparisons of workflow files only

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Assessment |
|---|---|
| I. Safe by Default | ✅ No new write capability; what is sent to INUBIT is unchanged. Real differences remain detected (FR-003/FR-004) — conflict and verification still protect. Legacy-fingerprint acceptance cannot mask a real change (a legacy value matches only legacy-current of the same content). |
| II. Secrets | ✅ Not touched. |
| III. Test-First | ✅ Tests for the rule, both ports, import/deploy/export flows and the legacy rule are written first and observed failing. |
| IV. Use-case tools | ✅ No tool changes. |
| V. Adapter isolation | ✅ XML handling stays in `adapter/archive/v81`; the application sees two port methods (`ImportArchivePort.connectionOrdered`, deprecated `ReleaseArchivePort.legacyCanonical`). |
| VI. Structured output | ✅ Unchanged result shapes; fewer false `VERIFY_MISMATCH`/`CONFLICT`. |
| VII. Observability | ✅ Audit and reports unchanged; reports keep the stored renderings. |
| Docs with behaviour | ✅ CHANGELOG "Fixed"; `docs/tools.md` gets a sentence on connection order in comparisons. |

Post-design re-check: unchanged, all ✅.

## Project Structure

### Documentation (this feature)

```text
specs/007-connection-order-compare/
├── plan.md
├── research.md          # R-1 … R-6
├── data-model.md        # renderings, comparison forms, recorded fingerprints
├── quickstart.md        # offline + install + live validation
├── contracts/ports.md   # P-1 … P-6
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
src/main/java/de/dadecker/inubit/mcp/
├── adapter/archive/v81/
│   ├── WorkflowComparison.java      # NEW: volatile list, reviewed(), connectionsOrdered()
│   ├── V81ImportArchives.java       # equivalent via the rule; connectionOrdered(); own copies removed
│   ├── V81ReleaseArchives.java      # canonical/equivalent/layoutOnly via the rule; legacyCanonical()
│   └── WorkspaceWriter.java         # keep-rule in write()
├── domain/port/
│   ├── ImportArchivePort.java       # + connectionOrdered(path, file)
│   └── ReleaseArchivePort.java      # + legacyCanonical(path, file) @Deprecated
└── application/
    ├── ConflictDetector.java        # fingerprints over connection-ordered renderings
    ├── ImportService.java           # fingerprints, legacy acceptance (requireStateLeftBy), writeBack keep-rule
    ├── NodeDeployer.java            # legacy acceptance for deploy-backup intendedState
    └── DeployService.java           # legacy acceptance for ledger OUTSIDE_CHAIN; commit keep-rule

src/test/java/de/dadecker/inubit/mcp/
├── adapter/archive/v81/WorkflowComparisonTest.java   # NEW
├── adapter/archive/v81/V81ImportArchivesTest.java, V81ReleaseArchivesTest.java, LayoutDiffTest.java, WorkspaceWriterTest.java
└── application/ImportRollbackTest, ImportConfirmationTest, RestoreServiceTest, DeployFailureTest,
    ReleasePlannerTest, ReleaseDiscoveryTest, RestoreDeploymentTest, DeployExecuteTest, WorkspaceExportTest
src/test/resources/fixtures/v8_1/connection-order/      # NEW neutral excerpt (R-6)

pom.xml (0.5.1), CHANGELOG.md, docs/tools.md
```

**Structure Decision**: existing single project; the rule lives next to `XmlNormalizer`/`LayoutDiff`
in the v8.1 archive adapter, as required by its package rules.

## Implementation Outline (input for /speckit-tasks)

1. Tests first for `WorkflowComparison` (ordering, slots, totality, idempotence, no
   generalization, parse-independence of non-workflow files); implement it; move the volatile
   list and `reviewed()` there; switch both adapters (no behaviour change except ordering).
2. Port tests and changes: `ImportArchivePort.equivalent`/`connectionOrdered`,
   `ReleaseArchivePort.canonical`/`equivalent`/`layoutOnly`/`legacyCanonical` (positive and
   negative cases from the spec, incl. the neutral incident excerpt).
3. Import flow tests (US1): swapped re-export → `EXECUTED`, no rollback, no report; real
   difference → `VERIFY_MISMATCH`; flipped rollback re-export → `SUCCEEDED`; flip between preview
   and confirm → no conflict. Then fingerprints over `connectionOrdered`.
4. Deploy flow tests (US2): classification `UNCHANGED`/`LAYOUT_ONLY`, consistent sources,
   verify without mismatch/rollback, preview→execute flip; real difference still detected.
5. Legacy fingerprints (FR-009): restore of import and deploy backups and ledger written with
   legacy values → no refusal/warning for content that differs only in order.
6. Export and write-back keep-rule (US3, FR-008).
7. Docs, CHANGELOG (Fixed), version 0.5.1, `mvn verify`.
8. Local install: build JAR, copy to `~/.local/lib`, update the start script and
   `x-cert-check.serverJar` (with backups), validate, ask the operator to start a new session;
   live validation per quickstart §2–§3. Publishing a GitHub release only on the operator's
   request.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Deprecated `ReleaseArchivePort.legacyCanonical` | Recognise fingerprints recorded by ≤ 0.5.0 so that restoring an older deploy backup is not refused (FR-009). | Migrating stored manifests/ledgers rewrites operator state; accepting false refusals breaks restores after the upgrade. Remove after 0.5.x. |
