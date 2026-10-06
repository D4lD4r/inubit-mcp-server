# Data Model: Artifact Workspace (feature 003)

Delta to the data models of features 001 and 002. Decisions referenced as D-n are in
[research.md](research.md).

## Configuration

| Entity | Fields | Rules |
|---|---|---|
| `ProfileConfig` (extended) | `workspace: Path` | default `~/.inubit-mcp/<profile.name>/workspace`; `~` expanded; must be absolute after expansion; created `rwx------` at startup if missing; must be readable and writable; must not equal or contain the workspace of another profile in the default configuration directory (FR-001–FR-003) |

`ConfigSummary` prints `Workspace: <path> (<state>)` with state `ok`, `created` or the error.

## Workspace

| Entity | Fields | Notes |
|---|---|---|
| `Workspace` | `root: Path`, `profile: String` | one per process; owns the lock and the `VersionHistoryPort` |
| `WorkspacePath` | `group: GroupId`, `owner: String`, `kind: WORKFLOW \| MODULE \| MODULE_INDEX \| EMBEDDED \| REPOSITORY`, `segments: List<String>` | maps names ↔ relative paths with the encoding of D-3; `toRelativePath()`, `parse(Path)` |
| `ArtifactRef` | `group`, `owner`, `kind: WORKFLOW \| MODULE \| REPOSITORY_FILE`, `name`, `diagramGroup?` (workflow), `pluginType?` (module) | identity is the **name** within owner and kind (spike §5); UIDs are never part of it |
| `MetaRecord` | `artifact: ArtifactRef`, `values: SortedMap<String,String>` | JSON under `.meta/` mirroring the artifact path (D-6): enclosing XML context, UIDs, CheckinComment export suffix (without the export time), `sourceVersion` of the archive properties (export time and `operationId` are dropped), repository metadata |

## Archive model (adapter `archive/v81`, in memory only)

| Entity | Fields | Notes |
|---|---|---|
| `ExportArchive` | `properties: Map`, `workflowGroups: List<WorkflowGroupXml>`, `moduleIndex: List<ModuleIndexEntry>`, `moduleFiles: Map<name, ModuleXml>`, `repository: Map<path, RepositoryFile>` | parsed from the StartCLI ZIP; raw bytes discarded after parsing |
| `WorkflowXml` | `name`, `diagramGroup`, `element` (the `Workflow` subtree), `context` (IBISWorkflow version, group attributes, position) | becomes one workflow file + meta |
| `ModuleXml` | `name`, `pluginType`, `element`, `embedded: List<EmbeddedDocument>` | |
| `EmbeddedDocument` | `property: String`, `encoding: ESCAPED_XML \| GZIP_BASE64`, `extension`, `content: byte[]` | written as `<property>.<ext>`; property text in `module.xml` becomes `@file:<file name>` |
| `RepositoryFile` | `path`, `content: byte[]`, `metadata` (to meta) | written only if referenced |

## Secrets

| Entity | Fields | Notes |
|---|---|---|
| `SecretPlaceholder` | `propertyPath: String` | rendered `${secret:<propertyPath>}`; no value-derived data (D-7) |
| `RedactionReport` | `count: int`, `kinds: Map<kind,int>` | returned in the export result as counts only |
| `RedactedArchive` | the `ExportArchive` after redaction + its `RedactionReport` | constructible only by `SecretRedactor`; the workspace writer and the assembler accept only this type, so nothing unredacted can be written (FR-025) |

## History

| Entity | Fields | Notes |
|---|---|---|
| `HistoryEntry` | `commit: String` (short id), `message`, `changes: List<PathChange>` | |
| `PathChange` | `path`, `kind: ADDED \| MODIFIED \| DELETED` | |

Transitions of one export (D-9): `Idle → Locked → (LocalChangesCommitted) → Exported(in memory) →
Written → Committed | Unchanged → Idle`; any failure before `Written` leaves the tree as it was; a
failure during `Written` restores the affected sub-trees from `HEAD`.

## Checks

| Entity | Fields | Notes |
|---|---|---|
| `CheckRequest` | `paths: List<String>` (workspace-relative files or directories), `xslt?: {stylesheet, input, params: Map<String,String>, now?: Instant}`, `schema?: path` | validated: every path inside the workspace, no `..`, exists |
| `CheckFinding` | `severity: ERROR \| WARNING \| INFO`, `check: STRUCTURE \| XSLT \| XML \| XSD`, `path`, `location?` (element path or line:column), `code`, `message` | `code` is a stable identifier, e.g. `EDGE_TARGET_MISSING`, `ID_COLLISION`, `DEMUX_KEY_UNMATCHED`, `PARENT_REF_MISSING`, `MODULE_MISSING`, `MODULE_UNVERIFIED`, `VARIABLE_UNRESOLVED`, `REPOSITORY_REF_MISSING`, `XSLT_STATIC_ERROR`, `XSLT_NOT_TESTABLE`, `XSLT_STANDINS_USED`, `XML_NOT_WELL_FORMED`, `XSD_INVALID` |
| `CheckReport` | `findings`, `counts per severity`, `outputs: List<path>`, `truncated`, `fullReport?: path` | bounded by `resultLimits` (D-10); each message at most 500 characters |
| `XsltRun` | `stylesheet`, `input`, `output: path`, `outcome: OK \| ERROR \| NOT_TESTABLE`, `standInsUsed: List<String>` | output under `.tests/<group>/<owner>/<module>/<input name>.out` |
