# Implementation Plan: Development on a Development Stage

**Branch**: `004-development-stage` | **Date**: 2026-10-06 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-development-stage/spec.md`

## Summary

The server gains five writing tools for development stages: `import_artifacts`, `restore_backup`,
`set_active`, `tag_artifacts` and `run_e2e_test` (SOAP).

**Server-enforced sequence.** Every write runs through the same steps:
1. guard: development node only;
2. workspace lock;
3. local changes committed;
4. change set built against the last verified server state;
5. feature-003 checks;
6. conflict detection: server changes, or a workflow in Workbench edit mode;
7. preview and code, by default;
8. backup;
9. import archive with only the changed artifacts and with secrets from the target, in memory;
10. guard on the archive's contents;
11. StartCLI import with protocol matching;
12. verification by re-export;
13. commit, or rollback from the backup.

**Supporting pieces:**
- **Tags** are set per diagram group and verified afterwards. An existing tag is never moved.
- **The SOAP test** carries a test-id header and is correlated by that id, falling back to a marked
  time window.
- **Backups** are kept 30 days, and the newest per scope is always kept.
- **Owner kind** comes from INUBIT's user list, with a profile override.

## Technical Context

**Language/Version**: Java 21 (unchanged).

**Primary Dependencies**: unchanged. There is no new dependency: the JDK `HttpClient` is used for
SOAP, and git, StartCLI and Saxon-HE are already in place.

**Storage**:
- the workspace and its git history (feature 003);
- `~/.inubit-mcp/<profile>/backups/` (owner-only; the ZIPs contain secrets, the index does not);
- the audit log (existing).

**Testing**:
- JUnit 5 and AssertJ.
- A new `ScriptedProcessLauncher` answers sequences of StartCLI calls (export → import → export) from
  recorded, neutralized import, tag and finger outputs (D-22).
- WireMock covers REST and SOAP.
- Real git runs in `@TempDir`.
- Live tests are opt-in, on a development node and a personal diagram group only (D-23).

**Target Platform**: macOS/Linux (CLI-based, as before).

**Project Type**: single Maven module, shaded JAR.

**Performance Goals**: SC-007, an import of 20 workflows with 100 modules within 2 minutes plus
INUBIT's own time.

**Constraints**:
- Never write to non-development nodes or production.
- Never delete artifacts.
- No secret leaves memory except into the private temporary import ZIP and the owner-only backup.
- Every call is audited.
- Results are bounded.

**Scale/Scope**:
- 5 tools and 10 schemas;
- about 30 new production classes (application services and guard, a generic challenge registry,
  import/tag runners, protocol parser, `SecretValues`/`SecretPaths`/`ImportAssembler`,
  `BackupStore`, `OwnerKindResolver` (removed again by research D-26), `E2eClient`);
- config, wiring and docs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ⚠️ First feature that changes INUBIT artifacts | ✅ Writes are disabled by default (`development.enabled: false`), configured per group/node, and forbidden on production at startup. The guard refuses non-development nodes. Confirmation is SERVER by default, the code is bound to inputs and state, and the conflict check repeats at execute. Tools are annotated destructive and non-idempotent. All inputs are validated before any call, and StartCLI gets argument arrays with `VALUE`/path rules. Blank tag groups are refused (StartCLI would tag the whole owner). Nothing is ever deleted. |
| II. Secrets Stay Out | ⚠️ Secrets must be re-inserted for imports | ✅ Secret values come only from the target's fresh export and exist only in memory and in the private temporary import ZIP, which is deleted on every path. Backups are owner-only and listed by an index without secrets. Placeholders and errors name paths, never values. A leak test covers workspace, history, backup index, audit, results and logs (D-6, D-13). |
| III. Test-First | ✅ | ✅ Every step has a failing test first. The scripted fake StartCLI uses recorded protocols, and failure modes are tested synthetically. Live tests are opt-in and never touch production or shared owners. |
| IV. Use-Case-Oriented Tools | ✅ Five tools with stated use cases (US1–US5) | ✅ The tools are task-shaped: import a diagram group, restore, activate, tag, test. There is no raw `import`/`tag` passthrough. |
| V. Adapter Isolation | ✅ | ✅ StartCLI import/tag lives in `adapter/cli` and `adapter/cli/v81`, archive/secret assembly in `adapter/archive/v81`, SOAP in `adapter/soap`, and user lookup in `adapter/rest/v81`, all behind ports. Application services stay format-free, and `PackageBoundaryTest` is extended. |
| VI. Bounded Output | ✅ | ✅ Diffs, protocols and responses go to workspace report files whose paths are returned. Lists are capped by `resultLimits`, and SOAP excerpts are ≤ 2 KB. |
| VII. Observability & Auditability | ✅ | ✅ Every call (refused, preview, executed, failed, rollback) and every backup removal produces an audit record with sanitized inputs; codes are hashed. |
| Tech constraints | ✅ | ✅ No new dependency, single artifact, English artifacts. |

**Result**: PASS. Both ⚠️ items are resolved by design (D-1, D-2, D-6, D-13). No Complexity Tracking
entry.

## Project Structure

### Documentation (this feature)

```text
specs/004-development-stage/
├── plan.md
├── research.md                 # D-1…D-23
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── configuration-delta.md
│   └── mcp-tools-delta.md
├── checklists/requirements.md
└── tasks.md                    # /speckit-tasks
```

### Source Code

```text
src/main/java/de/dadecker/inubit/mcp/
├── config/                 # DevelopmentConfig, E2eConfig; EffectiveNodeConfig.development() (OwnerKind/owners removed, D-26);
│                           # ConfigValidator production rules; ConfigSummary development/e2e lines
├── domain/model/           # DevelopmentPolicy, E2ePolicy, ImportScope, ChangeSet, ChangedArtifact, Conflict,
│                           # WriteOutcome, TagOutcome, E2eRun, ErrorCode (+6)
├── domain/port/            # ImportPort, TagPort (Gateway), E2ePort, BackupPort (UserDirectoryPort removed, D-26);
│                           # VersionHistoryPort (+lastServerState, show, changedPaths); ArchiveCodecPort (+files view,
│                           # +assembleForImport)
├── adapter/cli/            # CliCommand (+import, +tag options), CliImportRunner, ImportProtocolParser
├── adapter/cli/v81/        # V81ImportAdapter, V81TagAdapter
├── adapter/archive/v81/    # SecretPaths (shared with SecretRedactor), SecretValues, ImportAssembler
├── adapter/soap/           # SoapE2eClient (JDK HttpClient, node TLS)
├── adapter/git/            # GitCli (+3 read methods, Server-State trailer)
├── application/            # DevelopmentGuard, WriteChallengeRegistry, ChangeSetBuilder, ConflictDetector,
│                           # BackupStore (+retention), ImportService (import/restore/activate),
│                           # TagService, E2eTestService; WorkspaceService (+trailer, +shared steps)
├── mcp/tools/              # ImportArtifactsTool, RestoreBackupTool, SetActiveTool, TagArtifactsTool, RunE2eTestTool
└── Wiring.java             # registration only with a development node
src/main/resources/schemas/ # 10 schemas
src/test/…                  # mirrors main; ScriptedProcessLauncher; live/DevelopmentLiveTest
src/test/resources/fixtures/v8_1/cli/  # import_*, tag_*, finger_* (neutralized recordings) + synthetic failures
docs/                       # tools.md (5 tools), setup.md (development settings), live-tests.md
```

**Structure Decision**: same single module. Stages, each reviewed:

1. **Foundation:** config and guard, the generic challenge registry, `ErrorCode`s, GitCli read
   methods with the trailer, and fixtures plus `ScriptedProcessLauncher`.
2. **Import core (US1):** change set, conflict detector, backup store, `SecretPaths`/`SecretValues`/
   `ImportAssembler`, import runner and protocol parser, verify, rollback, `ImportService`, and the
   `import_artifacts` tool.
3. **Restore and activate (US2, US3).**
4. **Tag (US4).**
5. **SOAP test (US5)** and the owner-kind resolver.
6. **US6 config check**, docs, the live test and validation.

## Complexity Tracking

None.
