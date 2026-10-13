# Data Model: Editing INUBIT Repository Files (feature 008)

This feature extends the models of features 003 to 005. The reasons for each decision are in
[research.md](research.md).

## Configuration

| Entity | Change | Rules |
|---|---|---|
| `RepositoryMode` (config enum, new) | `READ_ONLY`, `WRITE` | bound by constant name like `ConfirmationMode` (D-1) |
| `DevelopmentConfig` | + `repository: Optional<RepositoryMode>` | allowed on `defaults`, group and node; unknown values are a loader error |
| `Defaults` | + `BUILTIN_DEVELOPMENT_REPOSITORY = READ_ONLY` | |
| `EffectiveNodeConfig.Development` | + `repositoryWrite: boolean` | `true` only if resolved `WRITE` (node, then group, then defaults, then built-in) **and** `enabled` **and** not `production` (D-2) |
| `ConfigValidator.checkRepositorySwitch` (new) | works on the raw groups and nodes | errors: explicit `WRITE` on a node that is not a development stage; `WRITE` on a production group or one of its nodes. Warning: explicit `WRITE` on a non-production group without a development-enabled node |

## Domain

| Entity | Change | Notes |
|---|---|---|
| `DevelopmentPolicy` | + `repositoryWrite: boolean` | `DevelopmentGuard.forRepositoryWrite(policy)` returns a copy with `confirmation = SERVER` (D-6) |
| `NodeSummary` | + `repositoryWrite: boolean` | shown by `list_nodes` (D-13) |
| `ImportScope` | + `repositoryPaths: List<String>` | exactly one of `diagramGroup`, `modules`, `repositoryPaths`; 1–50 paths relative to `/Root/<owner>`, normalized, no empty path and no root (FR-005); `describe()` gives `repository paths a, b` |
| `ChangeSet` | + `repository: List<ChangedArtifact>` (kind `REPOSITORY_FILE`) | `isEmpty`, `artifacts`, `paths`, `created`, `modified` and `existing` include it; identical repository files go to `identical` |
| `ChangedArtifact` | unchanged | for a repository file, `ref.name` is the full repository path `/Root/<owner>/…`, `paths` holds exactly one `WorkspacePath` of kind `REPOSITORY`, and `base` is the server-state commit |
| `RepositoryReference` (new record) | `from: WorkspacePath`, `to: String` (repository path), `kind: URI \| RELATIVE` | produced by the scanner (D-3) |
| `RepositoryClosure` (domain, new, pure Java) | input: start references and a function `path → Optional<List<RepositoryReference>>`; output: `included: SortedSet<String>`, `outsideOwner: List<RepositoryReference>`, `referencedBy: Map<String, SortedSet<String>>` | worklist, each path visited once; used by `application` and by `adapter/archive/v81/WorkspaceWriter` (D-3) |
| `RepositoryPaths` (domain, new, pure Java) | `passable(fullPath)`, `full(owner, relative)`, `relativeValid(relative)` | the StartCLI path rule moved out of `ReleasePlanner`; `.`/`..`, `%`, empty segments and a leading `/` are refused (D-4) |
| `RepositoryNodeState` (application, new) | `files: SortedMap<String, byte[]>`, `rawExports: List<byte[]>`, `fingerprint: String` | one export per distinct parent folder (D-5); `fingerprint` is a sorted list of `repository:<path>=sha256` entries |
| `ImportPreview` | + `repositoryReferences: List<{path, artifacts[≤20], truncated}>`, + `repositoryDiff: Optional<String>` | repository paths appear in `create`, `modify`, `existing` and `identical` |
| `WriteOutcome` | + `tag.repositoryFiles: List<String>` (optional) | `created`, `modified` and `createdNotRemoved` carry repository paths too |
| `ErrorCode` | + `REPOSITORY_WRITE_DENIED` | placed before `INTERNAL` (D-14) |

## Ports and adapters

| Port | Addition | Adapter |
|---|---|---|
| `ArtifactInspectorPort` | `List<RepositoryReference> repositoryReferences(WorkspacePath, byte[])` | `RepositoryReferences` (new, `adapter/archive/v81`); `WorkspaceInspector` and `ArtifactCheckService` reuse its pattern |
| `ReleaseArchivePort` | `byte[] repositoryArchiveFromFiles(String owner, List<RepositoryUpload>)`; key material uses the existing `keyMaterial(path, content)` (= `RepositoryArchive.isKeyMaterial`, certificates included) | `V81ReleaseArchives`, using `RepositoryArchive.build` and `RepositoryMetadata.forNewFile` (new, D-7) |
| `ArchiveCodecPort` | `PreparedExport prepareRepository(GroupId, String owner, List<byte[]> archives)` | `ArchiveCodec` with the `WorkspaceWriter` repository branch (D-10) |
| `ArtifactPort` / `ImportPort` | unchanged (`exportRepository`, `importRepository` from feature 005) | |

`RepositoryUpload` (domain record, new) is `path` (full repository path), `content`, and
`metadata: Optional<String>`. This is the normalized XML from `.meta` for a MODIFIED file, or the
node's metadata from the node-state export for an EXISTING file. It is empty for a `create` file,
which gets `RepositoryMetadata.forNewFile` (research D-7).

## Persistence

| Entity | Location | Change |
|---|---|---|
| Backup manifest | `~/.inubit-mcp/<profile>/backups/<auditId>.json` | `repositoryPaths` is now written and read for `IMPORT` manifests as well. `intendedState` gains `repository:<path>` keys. `updateManifest` keeps every field (D-8) |
| Backup ZIPs | `<auditId>-<n>.zip` | include the node's repository folder exports from before the call |
| Workspace | `<group>/<owner>/repository/Root/<owner>/…` and `.meta/…json` | written by repository-path exports and by the write-back after a verified import; base = newest `Server-State: <group>` commit of the path |
| Reports | `<workspace>/.reports/` | `repository-<auditId>.diff` (preview), `repository-references-<auditId>.txt`, and the existing `conflict-`, `verify-` and `import-check-` files with repository sections |
| Audit | `audit-YYYY-MM.jsonl` | inputs `repository.<n>` = `<op> <path> sha256:<16 hex>`; `scope` = `repository paths …` (D-11) |

## States of one repository file in an import call

```text
workspace NEW ──node absent──────────────▶ create ──┐
workspace NEW ──node identical───────────▶ identical (not sent)
workspace NEW ──node other content───────▶ modify + existing ──┐
workspace MODIFIED ──node == base────────▶ modify ─────────────┤
workspace MODIFIED ──node != base────────▶ CONFLICT (call refused, nothing sent)
                                                               ▼
                       switch READ_ONLY ─▶ REPOSITORY_WRITE_DENIED (call refused)
                       switch WRITE ─────▶ preview + server code ─▶ backup ─▶ importRepository
                                           ─▶ verify (bytes) ─▶ write back + commit
                                                     └─ failure ─▶ rollback (modified files from the backup;
                                                                   created files → createdNotRemoved)
```
