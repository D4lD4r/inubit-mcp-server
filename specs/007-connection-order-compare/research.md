# Research: Order-Insensitive Comparison of Workflow Connections

Feature: [spec.md](spec.md) · Plan: [plan.md](plan.md)

Findings from the code (paths relative to `src/main/java/de/dadecker/inubit/mcp/`) and from the
incident's workspace history (read locally, not reproduced here).

## R-1 Root cause in the comparisons

- **Finding**: Every content comparison serializes with `adapter/archive/v81/XmlNormalizer`
  (sorted attributes, uniform indentation, canonical escaping) and compares bytes or SHA-256
  hashes. Child elements stay in document order. `V81ImportArchives.reviewed` (L120-131) and
  `V81ReleaseArchives.reviewed` (L217-231) only drop the top-level volatile children
  (`CheckinComment`, `LastUpdate`, `WorkflowUId`, `ModuleUId`, `CheckoutUser`; the release variant
  optionally `IsActive`); both keep their own copy of that list.
- **Consequence**: a flipped order of the `<Connection>` children of a `<WorkflowModule>` is a
  difference for import (`ConflictDetector.compare/sameAsWorkspace`, `ChangeSetBuilder.differs`,
  `ImportService.verify`, `sameState` → needless rollback and `rollback: FAILED`), for fingerprints
  (`ConflictDetector.fingerprint` over rendered files: preview → confirm, `set_active`, restore,
  manifest `intendedState`), for deployment (`ArtifactClassifier` `CHANGED` instead of
  `UNCHANGED`, `ReleaseDiscovery.differences`, `ReleasePlanner` node and artifact fingerprints,
  `NodeDeployer` re-check, `verify`, `same()`) and for the export (`WorkspaceWriter.write` rewrites
  on any byte difference → `MODIFIED` commit).
- `LayoutDiff.layoutOnly` strips `StyleSheet` elements from normalized bytes and compares; with
  connections ordered beforehand it classifies "order + label position" correctly as layout only.

## R-2 One ordering, one place

- **Decision**: A package-private final class `adapter/archive/v81/WorkflowComparison` holds the
  volatile list, `reviewed(root, withoutActive)` and `connectionsOrdered(element)`. Both
  `V81ImportArchives` and `V81ReleaseArchives` use it; their private copies are removed (FR-005).
  `WorkspaceWriter` (same package) uses it for the export rule (R-5).
- **Ordering** (FR-002), applied recursively to every `WorkflowModule` whose parent is a
  `Workflow` (covers workspace files with root `Workflow` and archive files with root
  `IBISWorkflow/…/Workflow`): take the positions of its direct `Connection` element children,
  sort those elements, put them back into the same positions; all other children (elements, text,
  comments) stay where they are. Sort key, total and deterministic:
  1. connections whose `moduleOutId` attribute and `ConnectionId` child text are both plain
     decimal numbers come first, ordered by (`moduleOutId`, `ConnectionId`) numerically
     (`BigInteger`, so no overflow);
  2. ties and all other connections are ordered by their `XmlNormalizer` serialization (UTF-8
     bytes, unsigned lexicographic).
  A per-pair fallback ("compare numerically if both numeric, else textually") would not be
  transitive; the two-group key is.
- **Not generalized**: the order of `WorkflowModule` elements, and connections anywhere else, stay
  significant (FR-004; not observed).
- **Alternatives considered**: sorting all children of all elements (would hide real reorderings
  and changes semantics of ordered lists); a set-based comparison (no canonical bytes for
  fingerprints).

## R-3 Where the ordering is applied

- **Import port**: `V81ImportArchives.equivalent` compares `comparisonForm(path, bytes)` of both
  sides: XML → `normalize(connectionsOrdered(reviewed(root, false)))`; `.xsl`/`.wsdl` and other
  files unchanged; parse failure → raw bytes, as today. New port method
  `ImportArchivePort.connectionOrdered(path, file)`: the rendering with only the connection order
  normalized (XML; anything else or unparsable → unchanged copy). It is used for fingerprints
  (R-4) and the workspace rule (R-5). It is deliberately *not* the full comparison form, so
  fingerprints keep guarding everything they guard today (e.g. `LastUpdate`, `IsActive`).
- **Release port**: `canonical`, `equivalent` and `layoutOnly` apply `connectionsOrdered` after
  `reviewed`. Verified that `canonical()` bytes are only compared or hashed (ReleaseDiscovery,
  ReleasePlanner fingerprints and artifact states, `NodeDeployer.same`) and never imported,
  written or sent; `source.diff` lists only paths; `ReleasePlanner.writeDiff` writes raw renderings
  (FR-006, FR-007 hold).
- **Application**: `ImportService` and `ConflictDetector` compute every `ConflictDetector.fingerprint`
  over `connectionOrdered` renderings (detect L281, `set_active` L540, `requireStateLeftBy` L900,
  `Fresh` L965, `updateManifest` L1664). `ConflictDetector.fingerprint` itself (static hash over
  path → bytes) stays unchanged.

## R-4 Fingerprints recorded before the upgrade (FR-009)

| Recorded value | Where | Compared | Old vs new effect without care |
|---|---|---|---|
| Import/`set_active` backup `intendedState` | `backups/<auditId>.json` (newest per scope kept indefinitely) | `restore_backup` → `requireStateLeftBy` | false **CONFLICT** refusal |
| Deploy backup `intendedState` (canonical artifact states) | same | `NodeDeployer` restore L789-797 | false **CONFLICT** refusal |
| Ledger artifact states | `deployments/<group>.ledger.json` | `DeployService` `OUTSIDE_CHAIN` (CHANGED/LAYOUT_ONLY only) | false **warning** |
| Preview → confirm states | memory only, TTL ≤ 2 h | same process | none (lost on restart = upgrade) |

- **Decision**: wherever a *persisted* fingerprint is compared, accept it if it equals the
  fingerprint of the current state computed the new way **or** the legacy way (without connection
  ordering). New values are always written the new way. No format version is added; a legacy
  value can only match legacy-current for the same content, so nothing real is masked.
  - Import side: legacy = fingerprint over the rendered files as today (already at hand).
  - Release side: new port method `ReleaseArchivePort.legacyCanonical(path, file)` (= today's
    `canonical`), `@Deprecated` with "remove after 0.5.x; only for values recorded by ≤ 0.5.0".
- **Alternatives considered**: a one-time migration of backup manifests and ledgers (touches
  operator state, harder to test); accepting the false refusals (restoring yesterday's import
  backup would fail after the upgrade).
- **Known limitation** (operator-accepted): a value recorded by ≤ 0.5.0 while the server showed
  order X is not recognised once the server shows order Y — neither fingerprint matches, so a
  restore of that backup is refused with `CONFLICT`. This is safe (no wrong action) and affects
  only backups from before the upgrade; covering it would need the committed workspace state of
  that call and is out of scope.

## R-5 Workspace writes keep genuine renderings (FR-008, decision B)

- **Decision**: a workspace workflow file that already exists and differs from the new rendering
  **only in connection order** (`connectionsOrdered` + `normalize` equal, bytes different) is kept
  unchanged. Applied in all three places that write server renderings into the workspace, so the
  history stays free of order flips:
  - export: `WorkspaceWriter.write` L354-366 (next to the existing `onlyTheCommentHistoryGrew`
    skip);
  - after an import: `ImportService.writeBack` L1511-1534;
  - after a deployment: `DeployService.commit` L297-319.
  The two application paths use `ImportArchivePort.connectionOrdered` for the check.
- **Safe**: `set_active`'s check (`history.show(lastServerState) == workspace file`) still holds,
  because the kept file is the committed server-state file; conflict detection, change sets and
  verify use the order-insensitive equivalence; `.meta` holds no order information; an import
  sends the kept file, which is a genuine earlier server rendering (FR-006).
- **Rejected**: storing the sorted form (operator decision; would change what is imported).

## R-6 Tests and fixtures

- **Adapter**: `V81ImportArchivesTest`, `V81ReleaseArchivesTest` (inline workflow XML helpers),
  new `WorkflowComparisonTest` (ordering rule, slots, fallback ordering, totality, no
  generalization), `WorkspaceWriterTest`/`LayoutDiffTest` for the export rule and layout-only.
- **Application**: `ImportRollbackTest` (`FakeInubit.tamperNextImport` swapping two connections of
  `Workflow-0001`/`Module-0002`, which in `grp-a.zip` has `moduleOutId` 4/9 then 3/6) → `EXECUTED`,
  no rollback, no verify report; rollback re-export flipped → `SUCCEEDED`; preview → confirm with
  a flip in between. Deploy: `DeployFailureTest` pattern with `FakeServer.tamperNextImport`
  (matches the indented normalized text), `ReleasePlannerTest` (classification), `ReleaseDiscoveryTest`
  (two sources with different order). Export: `WorkspaceExportTest` with
  `ExportHarness.rewrite(...)` swapping the two connections → unchanged, no commit.
  Compatibility: manifests and a ledger written with legacy fingerprints.
- **Incident excerpt**: a small fixture with the incident's structure (two modules, connections
  `moduleOutId` 315/171, `ConnectionId` 63/174, with `labelPosition`), all names neutral
  (`Workflow-0001`, `Module-0010`…). The numbers and label positions identify nothing; no customer
  value is copied. Checked by `NoCustomerIdentifiersTest`.
