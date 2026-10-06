# Implementation Plan: Artifact Workspace

**Branch**: `003-artifact-workspace` | **Date**: 2026-10-06 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-artifact-workspace/spec.md`

## Summary

First of the three features of the development and deployment design. The server gains a local,
per-profile **workspace** with a git history and two read-only tools:

1. **`export_artifacts`**: runs one StartCLI export per diagram group (technical workflows only) or
   per module, then:
   - parses the archive in memory and replaces every secret by a placeholder;
   - splits it into one normalized file per workflow and per module, with embedded documents as
     real files and the referenced repository files;
   - moves volatile values to `.meta/`;
   - records any uncommitted local edits first, then commits the export.
2. **`check_artifacts`**: checks workflow structure (the checks INUBIT does not do on import), runs
   stylesheets on Saxon-HE 10 with deterministic stand-ins for INUBIT's extension functions, and
   validates XML against XSD.

The archive transformation is lossless: a version-specific assembler rebuilds an archive equal to
the export under the equality of research D-4. Feature 003 proves this offline on recorded real
exports; feature 004 will use it for imports.

## Technical Context

**Language/Version**: Java 21 (unchanged).

**Primary Dependencies**:
- unchanged: MCP Java SDK 2.0.1, Jackson 3, Logback;
- **new**: `net.sf.saxon:Saxon-HE:10.9` (MPL-2.0), for XSLT runs matching INUBIT's Saxon 10 (D-11);
- **new external tool**: the system `git` command (D-1), not a library;
- XSD validation uses the JDK (D-12).

**Storage**: the per-profile workspace directory: git repository, `.meta/`, `.tests/`, `.reports/`
and `.lock` (D-2). Raw exports stay only in the existing private temporary export directories.

**Testing**: JUnit 5, AssertJ and the existing fake `ProcessLauncher` for StartCLI. Real `git` runs in
temporary directories. Recorded, neutralized development-stage exports with synthetic secrets serve
as fixtures (D-14). Live tests are opt-in and read-only.

**Target Platform**: macOS/Linux, as the CLI-based tools today. Windows keeps `CLI_UNAVAILABLE` for
export; `check_artifacts` works everywhere.

**Project Type**: single Maven module, shaded JAR.

**Performance Goals**: SC-006, a diagram group with up to 100 modules processed in under 1 minute,
plus StartCLI time. The archive is processed in memory; the measured owner-wide module export is
4.3 MB.

**Constraints**:
- **Read-only towards INUBIT.**
- **Secrets** never on disk outside the private temporary directory, nor in git, results or logs.
- **Git** never has a remote.
- **Results** are bounded by `resultLimits`.
- **Locking**: one workspace lock across the server processes of a profile.

**Scale/Scope**:
- 2 tools and 4 schemas;
- about 25 new production classes in 3 new adapter packages (`archive/v81`, `git`, `xslt`) plus
  `cli/v81` additions, 2 application services, and config and wiring changes;
- fixtures from about 3 recorded diagram groups plus defect variants;
- documentation: `docs/tools.md`, `docs/setup.md` (workspace), `docs/live-tests.md`,
  `THIRD-PARTY-NOTICES.md` (Saxon), `CHANGELOG.md`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ✅ No tool changes INUBIT | ✅ Both tools are read-only towards INUBIT and carry `ToolHints.readOnly`. The export passes only `technical` and refuses blank diagram groups, because StartCLI treats an empty group as "all" (D-8). The workspace is local and holds no secrets. |
| II. Secrets Stay Out | ⚠️ New risk: artifacts contain passwords, ciphertext and private keys | ✅ Redaction happens in memory, before anything is written (D-7), and covers every form known from the spike. Ciphertext counts as a secret, and placeholders contain nothing derived from values. Raw ZIPs live only in the existing private temp directories, which are deleted on every path. `SecretRedactionTest` searches the workspace, git objects, results and logs (SC-003), and the fixtures carry only synthetic secrets (D-14). |
| III. Test-First | ✅ | ✅ Every component has a failing test first. The round trip, redaction, checks and stand-ins are proven against recorded real exports. The first XSLT test proves the D-11 risk (integrated functions in a `java:` namespace on Saxon-HE) before anything is built on it. |
| IV. Use-Case-Oriented Tools | ✅ Two tools, each with a stated use case (US1–US5) | ✅ Inputs are task-shaped: diagram groups or modules, and files to check. There is no low-level CLI or git passthrough. |
| V. Adapter Isolation & Version Compatibility | ✅ | ✅ The INUBIT archive format lives in `adapter/archive/v81`, StartCLI in `adapter/cli`, git in `adapter/git`, and Saxon/XSD in `adapter/xslt`, all behind ports (`ArtifactPort`, `VersionHistoryPort`, `XsltPort`). Git and StartCLI calls use argument arrays, timeouts, exit codes and tested parsers. `PackageBoundaryTest` is extended. |
| VI. Bounded, Structured Output | ✅ | ✅ Paths and findings are capped by `resultLimits`, and full lists go to `.reports/` as files whose paths are returned (D-10). Errors use the existing `ToolError` codes, with no new codes. |
| VII. Observability & Auditability | ✅ | ✅ The tools change no INUBIT state, so there is no audit record. The workspace git history records every export, and logs go to stderr, redacted. |
| Tech constraints | ⚠️ New runtime dependency | ✅ Saxon-HE 10.9 is justified in D-11, because it is the same engine generation as INUBIT and no JDK alternative exists. It is pinned, recorded in `THIRD-PARTY-NOTICES.md` (MPL-2.0), and leaves a single artifact. Git is an external tool, documented like StartCLI. |
| Workflow | ✅ Feature branch, spec → plan → tasks | ✅ Docs change in the same change as the behaviour. |

**Result**: PASS. No violation needs Complexity Tracking. The two ⚠️ items are resolved in design
(D-7, D-11).

## Project Structure

### Documentation (this feature)

```text
specs/003-artifact-workspace/
├── plan.md                     # this file
├── research.md                 # D-1…D-14
├── data-model.md
├── quickstart.md               # offline, config, live, assistant scenarios
├── contracts/
│   ├── configuration-delta.md  # `workspace`
│   └── mcp-tools-delta.md      # export_artifacts, check_artifacts
├── checklists/requirements.md
└── tasks.md                    # /speckit-tasks
```

### Source Code

```text
pom.xml                                          # + Saxon-HE 10.9 (pinned)
src/main/java/de/dadecker/inubit/mcp/
├── config/                                      # ProfileConfig.workspace, ConfigLoader/Validator (default,
│                                                # create rwx------, unique per profile), ConfigSummary line
├── domain/model/                                # ArtifactRef, WorkspacePath, NameCodec, PathChange, HistoryEntry,
│                                                # CheckFinding, CheckReport, XsltRun, SecretPlaceholder
├── domain/port/                                 # ArtifactPort (Gateway.artifacts()), VersionHistoryPort, XsltPort
├── adapter/cli/                                 # CliExportRunner: exportWorkflowGroup, exportModule (D-8)
├── adapter/cli/v81/                             # V81ArtifactAdapter (ArtifactPort)
├── adapter/archive/v81/                         # ArchiveCodec (read+split), ArchiveAssembler (rebuild),
│                                                # EmbeddedDocuments, SecretRedactor, XmlNormalizer, MetaStore
├── adapter/git/                                 # GitCli (VersionHistoryPort)
├── adapter/xslt/                                # SaxonXsltRunner (XsltPort), InubitStandIns (Formatter, Misc,
│                                                # ISFunctions, Java/Xalan helpers), WorkspaceUriResolver, XsdValidator
├── application/                                 # WorkspaceService (lock, local changes, export transaction),
│                                                # ArtifactCheckService (structure checks, server lookups)
├── mcp/tools/                                   # ExportArtifactsTool, CheckArtifactsTool
└── Wiring.java                                  # registers the two tools (export only if a node has a CLI)
src/main/resources/schemas/                      # export_artifacts.*.json, check_artifacts.*.json
src/test/java/de/dadecker/inubit/mcp/            # mirrors main (tests named in quickstart A)
src/test/resources/fixtures/v8_1/artifacts/      # neutralized recorded exports + defect variants
docs/                                            # tools.md, setup.md (workspace), live-tests.md
THIRD-PARTY-NOTICES.md, CHANGELOG.md
```

**Structure Decision**: same single module. The steps below are sequenced by user story; each step
is test-first and committed separately.

1. **Fixtures**: record and neutralize the development-stage exports, with synthetic secrets (D-14).
2. **Foundation**: workspace config, `WorkspacePath`/`NameCodec`, `GitCli`, and the lock.
3. **Archive codec**: read, split, normalize, redact, meta, assemble, and the round-trip test. This
   covers US1, US2 and FR-015.
4. **Export**: the `CliExportRunner` additions, `V81ArtifactAdapter`, the `WorkspaceService`
   transaction, and `export_artifacts`. This covers US1 and US2.
5. **Structure checks**: `ArtifactCheckService` and `check_artifacts` (structure). This covers US3.
6. **XSLT**: Saxon-HE, the D-11 risk test first, then stand-ins and the coverage test. This covers
   US4.
7. **XML/XSD**: validation. This covers US5.
8. **Docs, notices, live tests and validation** per quickstart.

## Complexity Tracking

None.
