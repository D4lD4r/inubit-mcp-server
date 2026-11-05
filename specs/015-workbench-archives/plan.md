# Implementation Plan: Workbench-Importable Workflow Archives

**Branch**: `015-workbench-archives` | **Date**: 2026-11-05 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/015-workbench-archives/spec.md` (GitHub issue #56,
spec reviews r1–r3 in `reviews/`)

**Base**: `main` after feature 011 (`[Unreleased]`, planned as 0.7.0). This feature is added to
the same `[Unreleased]` section.

## Summary

A new MCP tool **`export_archives`** exports the technical workflows of one diagram group of one
server as **one Workbench-importable archive per workflow** into a folder the call names. It reuses
the StartCLI diagram-group export of `export_artifacts` (`ArtifactPort.exportWorkflowGroup`), keeps
the raw export in memory only, slices it per workflow in the 8.1 archive adapter and places each
archive owner-only and atomically ("never replace") in the target folder. Secrets stay in clear
text, so the call needs `acknowledgeClearTextSecrets: true`, a target folder outside the server's
directories, git working trees and network paths, and on production groups the new group setting
`archives.productionOptIn: true`.

- **Names** (R-1): tool `export_archives`; parameters `target`, `owner`, `diagramGroup`,
  `workflows`, `targetFolder`, `acknowledgeClearTextSecrets`, `reason`; setting
  `archives.productionOptIn` (group level, default `false`).
- **Export path** (R-2): `ArtifactPort.exportWorkflowGroup` as is (raw bytes in memory, private
  temporary directory deleted on every exit path); the bytes never pass `ArchiveCodec`, the
  workspace or a log.
- **Slicing** (R-3 … R-7): new domain port `WorkflowArchivePort`, 8.1 adapter
  `V81WorkflowArchives` in `adapter/archive/v81`: raw ZIP entries (`RawZip`) for byte-identical
  copies, `ArchiveReader` for the model; modules = `ModuleName` of every nested `WorkflowModule`
  plus index entries bound by `WorkflowName`; repository closure with `RepositoryReferences` /
  `RepositoryClosure` on the unredacted XML; `workflow.xml` / `module.xml` rebuilt with
  `XmlTree`/`XmlNormalizer` as `ArchiveAssembler` does; `Repository.zip` rebuilt from copied raw
  entries with their metadata and the archive comment.
- **File names** (R-8): `ArchiveFileNames` in `domain/model` (FR-004 rules, NFC + Unicode case
  fold key for comparisons).
- **Folder rules** (R-9, R-10): `TargetFolderRules` in `application` (absolute, no `.`/`..`,
  forbidden roots on the given and the resolved path — `~/.inubit-mcp`, `workspace`,
  `auditDirectory`, the default configuration directory of `ConfigLoader.defaultDirectory()` and
  the folder of the configuration file in use, plumbed as `ConfigLocation` from `Launcher` through
  `Wiring`; `.git` in the folder or an ancestor; writable directory or nearest existing ancestor;
  cloud-sync warning); network paths through the new port
  `NetworkPathProbe` (UNC by path; mapped drives by `GetDriveTypeW` in `adapter/os`, JNA already
  present).
- **Placement** (R-11): `ArchiveFolderWriter` in `application`: temporary owner-only file in the
  target folder (`CREATE_NEW`), `force`, then `Files.createLink(final, temp)` (fails atomically if
  the name exists), temporary file deleted; fallback for file systems without hard links:
  `CREATE_NEW` on the final name and deletion on failure.
- **Audit** (R-12, R-13): write-tool pattern: a single `REFUSED` record for a static refusal;
  otherwise `PENDING` (forced) before the version gate and StartCLI, then the outcome record with
  the same `auditId`: `EXECUTED`, `REFUSED`, `FAILED` or the new `PARTIALLY_COMPLETED` (every
  write-failure result, also with zero archives written). A refusal
  whose audit record cannot be written becomes `INTERNAL` carrying the original code and message.
- **Result** (R-14): bounded by `resultLimits.maxItems` over modules, missing modules and repository
  paths of all archives; `truncated` flag; counts always complete; clear-text statement.
- **Configuration and announcement** (R-15, R-16): `ArchivesConfig` in `GroupConfig`,
  `--check-config` line per group, startup warning on a non-production group; tool announced
  exactly when `export_artifacts` is (a node with `cliHome`), also on production groups.

## Technical Context

**Language/Version**: Java 21 (unchanged).

**Primary Dependencies**: existing only (MCP Java SDK, Jackson, logback, Saxon-HE for the existing
XML code, JNA core in `adapter/os`). No new dependency: ZIP handling with `java.util.zip`, Unicode
normalization with `java.text.Normalizer`, the drive-type probe with the JNA core already used by
`adapter/os/JnaCredApi.java`.

**Storage**: the archives in the operator's target folder (owner-only on POSIX); the audit log
(unchanged format, one new outcome value). Nothing in the workspace, no manifest file.

**Testing**:
- JUnit 5, AssertJ; offline only (Constitution III).
- Adapter tests on the recorded fixtures `fixtures/v8_1/artifacts/grp-a.zip` and `grp-b.zip`
  (fictitious names `GRP-01`, `GRP-02`, `Workflow-0001`…`Workflow-0006`, `Module-0001`…,
  owner `OWNERS`, user `jdoe`) plus test-built variants (a 10-workflow group for SC-002, a module
  bound by `WorkflowName`, a Workflow Connector node, a module missing from the export, nested
  nodes, a repository chain, a `Repository.zip` with directory entries), all built in test code from the fixtures — no new recorded file.
- Application tests with fakes for `ArtifactPort`, `WorkflowArchivePort` (where the real adapter is
  not needed), `AuditPort`, `NetworkPathProbe` and a `FileOps` seam of `ArchiveFolderWriter`
  (disk full, a file appearing between check and link, hard links unsupported).
- Tool tests through `McpTestClient` (schemas, annotations, structured result); wiring tests with
  `TestWiring` and the fake StartCLI launcher (`ScriptedProcessLauncher`).
- `security/ArchiveExportSecretLeakTest` (SC-004), `NoCustomerIdentifiersTest` run (not skipped) in
  the final validation, `PackageBoundaryTest` unchanged rules.
- SC-001 (Workbench import) is a manual check by the operator with their approval; it also
  confirms the not yet verified plug-in type string `Workflow Connector` (R-5).

**Target Platform**: macOS, Linux, Windows (Windows: UNC/mapped-drive refusal, inherited ACLs).

**Project Type**: single Maven module, shaded JAR.

**Performance Goals**: SC-002: building and writing 10 archives from the 10-workflow fixture ≤ 10 s
without StartCLI time (expected well below 1 s: one parse, ten slices of a few hundred KiB).

**Constraints**:
- no archive byte in the workspace, a log line, an error, the result or the audit (FR-003,
  FR-012, FR-013, SC-004); archives are built one at a time, so memory stays at the raw export
  (≤ 128 MiB, `CliExportRunner.MAX_ARCHIVE_BYTES`) plus one archive;
- never replace or change an existing file, never leave a partial archive (FR-009, FR-011);
- version-specific archive code only in `adapter/archive/v81`, behind a `domain/port` interface
  (Constitution V; `PackageBoundaryTest`);
- every check of FR-017 at its moment; audit before StartCLI (FR-013).

**Scale/Scope**: one new tool. New: `ExportArchivesTool`, schemas `export_archives.input.json` /
`.output.json`, `ArchiveExportService`, `TargetFolderRules`, `ArchiveFolderWriter`,
`ArchiveExportAudit`, domain records (`ArchiveFileNames`, `WorkflowArchivePlan`,
`ArchiveExportResult`), ports `WorkflowArchivePort` and `NetworkPathProbe`, adapters
`V81WorkflowArchives`, `RawZip`, `ArchiveReferences` (extracted from `WorkspaceWriter`),
`WindowsNetworkPaths` (+ `DriveTypeApi`), config `ArchivesConfig` and `ConfigLocation`. Changed:
`Launcher` (passes the `ConfigLocation`), `GroupConfig`,
`EffectiveNodeConfig`, `ConfigValidator`, `ConfigSummary`, `AuditOutcome`, `ToolHints`
(`localWrite` factory), `WorkspaceWriter` (uses `ArchiveReferences`), `Wiring` (constructors take
the `ConfigLocation`), `TestWiring` and the other direct `new Wiring(` callers (`WiringE2eTest`,
`WiringAuditTest`, `MessageTerminologyTest`),
docs (`docs/tools.md`, `docs/setup.md`, `SECURITY.md`, `CHANGELOG.md`, `README.md` tool list).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ✅ read-only for INUBIT; local writes | ✅ <ul><li>Annotations `readOnlyHint:false`, `destructiveHint:false`, `idempotentHint:false`, `openWorldHint:true` so clients ask (R-1, new `ToolHints.localWrite`).</li><li>Production groups refused unless `archives.productionOptIn` (independent of `write.*`, R-15).</li><li>All input validated before StartCLI (static checks, R-13); no shell, argument arrays of the existing `CliExportRunner`.</li><li>Never replace a file (link/`CREATE_NEW`, R-11).</li></ul> |
| II. Secrets Stay Out (NON-NEGOTIABLE) | ⚠️ the feature writes clear-text secrets on purpose | ✅ <ul><li>Secrets only in the archives the operator asked for, behind the acknowledgement, the client confirmation and the folder rules (R-9, R-10).</li><li>Raw bytes never in the result, a log, an error or the audit; only names, sizes and paths (R-12, R-14).</li><li>No secret in a tool parameter (the call carries names and a path only).</li><li>`ArchiveExportSecretLeakTest` (SC-004).</li></ul> The deliberate clear-text output is the spec's decision (clarification 2026-11-05) and is documented in `SECURITY.md`; it is not a deviation because no secret reaches the conversation, logs or audit. |
| III. Test-First (NON-NEGOTIABLE) | ✅ | ✅ Every task names its failing test first; fixtures and fakes only; the live Workbench import (SC-001) is manual and opt-in. |
| IV. Use-Case-Oriented Tools | ✅ use case stated in the spec (backup, hand-over, ticket attachment) | ✅ One tool with a typed schema and a documented output (contracts/mcp-tools-delta.md). |
| V. Adapter Isolation | ✅ | ✅ 8.1 archive layout only in `adapter/archive/v81` behind `WorkflowArchivePort`; drive-type probe in `adapter/os` behind `NetworkPathProbe`; StartCLI through the existing `ArtifactPort`; version gate as `export_artifacts` (`WORKSPACE_EXPORT`, R-2). |
| VI. Bounded Output | ✅ | ✅ Lists bounded by `resultLimits.maxItems` with `truncated`; counts complete; actionable `ToolError`s (R-14). |
| VII. Observability & Auditability | ✅ | ✅ Every schema-valid call audited (R-12): `REFUSED`, or `PENDING` + outcome; new outcome `PARTIALLY_COMPLETED`; INFO log line per call with names only. |
| Tech constraints | ✅ no new dependency | ✅ JDK and the existing JNA core only. |

**Result**: PASS. Complexity Tracking: none.

## Project Structure

### Documentation (this feature)

```text
specs/015-workbench-archives/
├── plan.md
├── research.md                 # R-1 … R-17
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── configuration-delta.md
│   └── mcp-tools-delta.md
├── checklists/requirements.md
├── reviews/                    # spec-r1 … spec-r3, plan-r1 …
└── tasks.md                    # /speckit-tasks
```

### Source Code

```text
src/main/java/de/dadecker/inubit/mcp/
├── domain/model/  # ArchiveFileNames (FR-004 rules, comparison key), WorkflowArchivePlan
│                  # (+ ModuleEntry, warnings), ArchiveExportResult (+ WrittenArchive, FailedArchive),
│                  # AuditOutcome.PARTIALLY_COMPLETED
├── domain/port/   # WorkflowArchivePort (+ SlicedExport), NetworkPathProbe
├── adapter/archive/v81/
│                  # V81WorkflowArchives (port impl), RawZip (raw entries + metadata + comment),
│                  # ArchiveReferences (extracted from WorkspaceWriter.render, shared)
├── adapter/os/    # WindowsNetworkPaths (+ DriveTypeApi, JnaDriveTypeApi): GetDriveTypeW
├── application/   # ArchiveExportService (flow, R-13), TargetFolderRules (R-9, R-10),
│                  # ArchiveFolderWriter (+ FileOps seam, R-11), ArchiveExportAudit (R-12)
├── config/        # ArchivesConfig; ConfigLocation (default directory + file in use, R-9);
│                  # GroupConfig.archives; EffectiveNodeConfig.archivesProductionOptIn;
│                  # ConfigValidator (no-effect warning); ConfigSummary (per group)
├── mcp/           # ToolHints.localWrite
├── mcp/tools/     # ExportArchivesTool (+ Result)
├── Launcher.java  # ConfigLocation from the loaded configuration into Wiring
└── Wiring.java    # service, probe, folder rules and tool; announced with export_artifacts
src/main/resources/schemas/export_archives.input.json, export_archives.output.json
src/test/…         # tests per quickstart table; security/ArchiveExportSecretLeakTest;
                   # TestWiring (probe seam); fixtures built in test code from grp-a/grp-b
docs/tools.md, docs/setup.md, SECURITY.md, CHANGELOG.md, README.md (tool list)
```

**Structure Decision**: the same single module. Stages, each ending with a green `mvn -q -o verify`:

1. **Domain and configuration**: `ArchiveFileNames`, `AuditOutcome.PARTIALLY_COMPLETED`,
   `ToolHints.localWrite`, `ArchivesConfig` with validation and `--check-config`.
2. **Slicing (US1)**: `RawZip`, `ArchiveReferences` extraction, `V81WorkflowArchives` (modules,
   bound modules, closure, rebuilt XML entries, `Repository.zip`, warnings, missing modules).
3. **Local placement (US2, US3)**: `TargetFolderRules`, `NetworkPathProbe` + Windows adapter,
   `ArchiveFolderWriter` (link placement, fallback, folder creation, failures).
4. **Service, audit and tool (US1–US3)**: `ArchiveExportService` with both check moments, audit,
   result bounding; `ExportArchivesTool`, schemas, wiring; secret-leak test; SC-002 timing.
5. **Docs and validation**: `docs/tools.md`, `docs/setup.md`, `SECURITY.md`, `CHANGELOG.md`,
   quickstart run, `NoCustomerIdentifiersTest`; SC-001 Workbench import by the operator.

## Complexity Tracking

None.
