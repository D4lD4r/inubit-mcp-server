# Data Model: Workbench-Importable Workflow Archives (feature 015)

Names are Java types; packages below `de.dadecker.inubit.mcp`. Research ids in brackets.

## ArchiveExportRequest (`application`)

The validated input of one call (from `ExportArchivesTool`).

| Field | Type | Rule |
|---|---|---|
| `target` | `String` | group or node id; resolved to one `NodeId` (group → first node) |
| `owner` | `Optional<String>` | default `inventory.owner` of the node, else `NOT_CONFIGURED` |
| `diagramGroup` | `String` | pattern of `export_artifacts`' diagram groups; not blank |
| `workflows` | `List<String>` | empty = all; else 1–50 unique, each 1–200 chars, no control characters (FR-002) |
| `targetFolder` | `String` | rules of `TargetFolderRules` [R-9] |
| `acknowledgeClearTextSecrets` | `boolean` | must be `true` (FR-007) |
| `reason` | `Optional<String>` | one line, ≤ 500 chars |
| `mcpClient` | `Optional<String>` | from `CallContext`, for the audit |

## CheckedFolder (`application`, from `TargetFolderRules`) [R-9, R-10]

| Field | Type | Meaning |
|---|---|---|
| `path` | `Path` | the absolute target folder as given (normalized) |
| `exists` | `boolean` | whether it exists at check time |
| `nearestExisting` | `Path` | the folder itself or its nearest existing ancestor (a writable directory) |
| `warnings` | `List<String>` | the cloud-sync warning, if any |

Refusals (`INVALID_INPUT`): UNC or remote drive (Windows; with the windows flag, UNC roots
`\\server\share`, `\\?\UNC\…` and drive roots `X:\` are recognised from the raw string before
the absolute check, so they are judged as Windows paths on any host); relative; `.`/`..` element; existing
non-directory; not writable (folder or nearest ancestor); inside `~/.inubit-mcp`, `workspace`,
`auditDirectory`, the default configuration directory (`ConfigLoader.defaultDirectory()`:
`~/.config/inubit-mcp`, Windows `%APPDATA%\inubit-mcp`) or the folder of the configuration file in
use (parent of `LoadedConfig.source()`) — each checked on the given and the resolved path, against
the root as given and resolved; `.git` in the folder or an ancestor (given or resolved).

## ConfigLocation (`config`) [R-9]

| Field | Type | Meaning |
|---|---|---|
| `defaultDirectory` | `Path` | `ConfigLoader.defaultDirectory()` of the running server |
| `source` | `Path` | `LoadedConfig.source()`, the configuration file in use |

`roots()` = `defaultDirectory` and `source.toAbsolutePath().getParent()`, each once. Created in
`Launcher` (`loader.defaultDirectory()`, `loaded.source()`), passed through `Launcher.serve` and
every `Wiring` constructor; `Wiring` hands `configLocation.roots()` to `TargetFolderRules` as
`List<Path> configurationRoots` (`application` must not depend on `config`,
`PackageBoundaryTest`). Tests use `TEST_CONFIG_LOCATION` with `defaultDirectory` =
`<tmpdir>/inubit-mcp-test-config` (`java.io.tmpdir`).

## WorkflowArchivePort (`domain/port`) [R-3]

```text
SlicedExport slice(byte[] diagramGroupExport, GroupId group, String owner, String diagramGroup)
```

`SlicedExport`:

| Member | Meaning |
|---|---|
| `List<String> workflows()` | the technical workflows of `diagramGroup` in export order |
| `WorkflowArchivePlan plan(String workflow)` | the content listing (names only) |
| `byte[] archive(String workflow)` | the archive bytes, built on demand; never logged |

Throws `UNEXPECTED_RESPONSE` for an export that cannot be read (as `export_artifacts`).

## WorkflowArchivePlan (`domain/model`) [R-5, R-6]

| Field | Type | Meaning |
|---|---|---|
| `workflow` | `String` | workflow name |
| `modules` | `List<ModuleEntry>` | `{name, pluginType}` of node modules and bound modules found in the export, export order, each once |
| `missingModules` | `List<String>` | technical node module names (`moduleType="technical"`) not in the export (FR-005) |
| `repositoryPaths` | `List<String>` | repository paths (`/Root/<owner>/…`) in the archive, sorted |
| `workflowConnector` | `boolean` | a node's module has plug-in type `Workflow Connector` (string unverified, one constant; confirmed in the manual check, R-5) |

Derived warnings (per archive, FR-012): missing modules ("imports only where these modules
exist"), Workflow Connector ("the called workflow is not included").

## ArchiveFileNames (`domain/model`) [R-8]

| Function | Rule |
|---|---|
| `fileName(String workflow)` | FR-004 derivation + `.diagram.zip`; `INVALID_INPUT` over 255 UTF-8 bytes |
| `key(String fileName)` | NFC, then `toUpperCase(ROOT).toLowerCase(ROOT)` — the comparison key of FR-009 |

## Workflow archive (file in the target folder) [R-7, R-11]

ZIP named `<fileName>`, entries in the export's order:

| Entry | Content |
|---|---|
| `archive.properties` | byte-identical |
| `Repository.zip` | rebuilt: `<path>.xml` + `<path>.dat` per selected repository file, ancestor directory entries the original has; headers, bytes, order, archive comment kept |
| `workflow/workflow.xml` | `IBISWorkflow/Workflows/WorkflowGroup` (original attributes, `WorkflowGroupName`) with this workflow's unchanged `Workflow` element |
| `module/module.xml` | the selected index entries in original group/position order |
| `module/<name>.xml` | byte-identical, for each selected module that has a file |

Owner-only (`rw-------`) on POSIX; Windows inherits the folder's ACLs. Never replaces a file.

## ArchiveExportResult (`domain/model`) [R-14]

| Field | Type | Meaning |
|---|---|---|
| `node`, `owner`, `diagramGroup`, `targetFolder` | `String` | call facts |
| `auditId` | `UUID` | id of the call's audit records |
| `clearTextSecrets` | `String` | fixed statement: the files contain secrets in clear text; do not share or commit them as they are |
| `archives` | `List<WrittenArchive>` | written completely, in write order |
| `failed` | `List<FailedArchive>` | at most one entry (the write stops at the first failure) |
| `notWritten` | `List<String>` | workflows not attempted after the failure |
| `truncated` | `boolean` | lists cut by `resultLimits.maxItems` |
| `warnings` | `List<String>` | call level: cloud sync, version admission, leftover temporary `.part` file (naming it; the archive counts as written), audit outcome not written |

`WrittenArchive`: `workflow`, `file` (name), `path` (absolute), `size` (bytes), `moduleCount`,
`modules` (`{name, pluginType}`, bounded), `missingModuleCount`, `missingModules` (`{name}`,
bounded), `repositoryFileCount`, `repositoryPaths` (bounded), `warnings`.

`FailedArchive`: `workflow`, `code` (`INTERNAL`), `message` (names the file for a name that appeared
after the check; never content).

## Audit records [R-12]

`AuditRecord` unchanged; `capability = "export_archives"`, `step = EXECUTE`.

| Outcome | When |
|---|---|
| `REFUSED` (single record) | a check of R-13 steps 1–2 failed |
| `PENDING` | before the version gate and StartCLI |
| `EXECUTED` | all selected archives written (or none selected) |
| `REFUSED` (after `PENDING`) | version gate or an export-dependent check |
| `FAILED` | StartCLI, version-gate probe or slicing error (tool error, nothing written) |
| `PARTIALLY_COMPLETED` (new enum value) | every write-failure result (FR-011): `failed[]` has an entry, with any number of archives written — also zero (target folder not created, first archive failed) |

Inputs: `owner`, `diagramGroup`, `targetFolder`, `workflow.<n>`, `file.<n>`,
`acknowledgeClearTextSecrets`, `reason` (each ≤ 500 chars). Never content or secret values.

## State transitions of one call

```text
received ──static check fails──▶ REFUSED (end)
   │
   ▼
PENDING ──gate/export/check fails──▶ REFUSED | FAILED (end)
   │
   ▼
writing ──all written──▶ EXECUTED
        ──folder or first archive fails──▶ PARTIALLY_COMPLETED (result, archives[] empty)
        ──later archive fails──▶ PARTIALLY_COMPLETED (result)
```

## Configuration: ArchivesConfig (`config`) [R-15]

| Key | Level | Type | Default | Rule |
|---|---|---|---|---|
| `archives.productionOptIn` | group | boolean | `false` | needed for `export_archives` on a `production: true` group; on other groups a startup warning "has no effect" |

`EffectiveNodeConfig.archivesProductionOptIn()` = the group's value or `false`.
