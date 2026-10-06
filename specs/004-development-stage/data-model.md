# Data Model: Development on a Development Stage (feature 004)

Delta to features 001–003. Decisions D-n are in [research.md](research.md).

## Configuration

| Entity | Fields | Rules |
|---|---|---|
| `DevelopmentConfig` (group/node, node wins) | `enabled: Optional<Boolean>`, `confirmation: Optional<ConfirmationMode>` | defaults `false`, `SERVER`; not allowed on production (D-1) |
| `E2eConfig` (group/node) | `tests: Optional<E2ePolicy>` (key `e2eTests`), `soapBaseUrl: Optional<URI>` (key `e2e.soap.baseUrl`) | default `FORBIDDEN`; ≠ `FORBIDDEN` needs a base URL and is not allowed on production |
| `ProfileConfig.owners` | `Map<String, OwnerKind>` | `OwnerKind = USER \| USER_GROUP`; optional override (D-21) |
| `DevelopmentPolicy` (effective, per node) | `node`, `enabled`, `confirmation`, `confirmationTtl`, `e2e: E2ePolicy`, `soapBaseUrl: Optional<URI>`, `production` | built in `EffectiveNodeConfig`; shown by `--check-config` |

## Change set and conflict

| Entity | Fields | Notes |
|---|---|---|
| `ImportScope` | `group`, `owner`, either `diagramGroup` or `modules: List<ModuleRef>` | one call = one scope (clarification 1) |
| `ChangeSet` | `scope`, `baseCommit`, `workflows: List<ChangedArtifact>`, `modules: List<ChangedArtifact>`, `notImported: List<String>` | from `changedPaths(base, subtree)` (D-4); deletions refused |
| `ChangedArtifact` | `ref: ArtifactRef`, `kind: NEW \| MODIFIED`, `paths: List<String>` | |
| `Conflict` | `artifact`, `kind: CHANGED_ON_SERVER \| IN_EDIT_MODE`, `user?` | `user` only from `CheckoutUser` |
| `ServerStateFingerprint` | SHA-256 over the rendered reviewed content of the scope | binds a confirmation code (D-2) |

## Writes

| Entity | Fields | Notes |
|---|---|---|
| `WriteChallenge` | `code`, `capability`, `node`, `inputFingerprint`, `previewState`, `expiresAt` | in memory; single use (D-2) |
| `Backup` | `auditId`, `node`, `owner`, `scope`, `changeSet` (names), `created` (names), `takenAt`, `zip: Path` | `~/.inubit-mcp/<profile>/backups/<auditId>.{zip,json}`; zip contains secrets → `rw-------`; json contains none (D-13) |
| `ImportProtocol` | `entries: List<{type, description, artifact, owner}>`, `total` | parsed fixed-width table (D-8) |
| `WriteOutcome` | `outcome: EXECUTED \| FAILED`, `commit?`, `backupRef`, `created`, `modified`, `notImported`, `rollback?: SUCCEEDED \| FAILED \| NOT_NEEDED`, `createdNotRemoved`, `reports: List<path>`, `warnings` | result of import/restore/activate |
| `TagOutcome` | `tag`, `diagramGroups`, `workflows: int`, `modules: int`, `removedAgain: boolean` | D-16 |

State of a writing call: `Admitted → Locked → (LocalChangesCommitted) → ChangeSetBuilt → Checked →
ConflictChecked → (Challenge issued | BackedUp → Assembled → Guarded → Imported → Verified →
Committed) → Unlocked`; any failure after `Imported` started → `RollingBack → RolledBack |
RollbackFailed`.

## End-to-end

| Entity | Fields | Notes |
|---|---|---|
| `E2eRequest` | `node`, `envelope` (workspace path), `path`, `soapAction?`, `workflow?`, `timeoutSeconds` | D-17 |
| `E2eRun` | `testId: UUID`, `status`, `durationMs`, `responseFile`, `excerpt (≤ 2 KB)`, `timedOut`, `correlation: BY_TEST_ID \| TIME_WINDOW_UNCERTAIN`, `processes`, `errors`, `logEntries` (bounded) | |

## Audit

`AuditRecord` unchanged; new `capability` values `import_artifacts`, `set_active`, `tag_artifacts`,
`restore_backup`, `run_e2e_test`, `backup_retention`; `inputs` keys: `reason`, `scope`, `owner`,
`ownerKind`, `tag`, `backupRef`, `path`, `changeSet` (≤ 20 names, then `…+n`), `rollback`,
`confirmationCode`/`issuedConfirmationCode` (hashed).

## Error codes (new)

`NOT_DEVELOPMENT`, `CONFLICT`, `SECRET_UNRESOLVED`, `IMPORT_FAILED`, `VERIFY_MISMATCH`, `E2E_FORBIDDEN`.
