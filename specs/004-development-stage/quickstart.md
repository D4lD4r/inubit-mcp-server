# Quickstart: validating feature 004

Contracts: [contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md),
[contracts/configuration-delta.md](contracts/configuration-delta.md); entities:
[data-model.md](data-model.md); decisions: [research.md](research.md).

## Prerequisites

As feature 003 (JDK 21+, Maven 3.9+, git ≥ 2.32). Live: a profile whose development group has
`development.enabled: true`, a **personal** diagram group with disposable test workflows, and the
user's approval.

## A. Offline (CI)

```bash
mvn -q clean verify
mvn -q -Dtest=NoCustomerIdentifiersTest test
```

| Scenario | Evidence | Spec |
|---|---|---|
| No development node → none of the 5 tools offered; non-development node refused `NOT_DEVELOPMENT` | `DevelopmentWiringTest`, `DevelopmentGuardTest` | FR-001, US6 |
| Forbidden config combinations on production refused at startup; `--check-config` lines | `ConfigValidatorTest$Development`, `ConfigSummaryTest` | FR-002–FR-005 |
| Import of an edited fixture diagram group: only changed workflow + changed module sent; protocol matched; verify; commit with `Server-State` trailer; reason in the check-in comment | `ImportServiceTest` | US1, FR-006–FR-018, SC-003 |
| Check error / conflict / edit mode / unresolved secret → nothing sent | `ImportServiceRefusalTest` | SC-001 |
| Failing import, failing re-export, differing re-export, mismatching protocol → rollback verified, or `rollback: FAILED` reported with backup kept | `ImportRollbackTest` | SC-002, FR-016 |
| Server confirmation: preview, code, conflict re-check at execute; changed inputs → `CONFIRMATION_INVALID` | `WriteChallengeRegistryTest`, `ImportConfirmationTest` | FR-009 |
| No secret in workspace, history, backup index, audit, results, logs; secrets in the import archive come from the target | `ImportSecretLeakTest`, `SecretValuesTest` | FR-012, SC-004 |
| Restore of a backup ref; unknown/removed ref refused; retention 30 days with newest kept | `RestoreServiceTest`, `BackupStoreTest` | US2, FR-019, clarification 3 |
| `set_active` sends only the workflow | `SetActiveTest` | US3, FR-020 |
| Tag: blank group refused before launch; only the requested groups exported; existing tag name reused, the same tag in other groups stays; mismatch or failure reported, nothing removed (no `--tagDelete`) | `TagServiceTest`, `CliTagRunnerTest`, `V81TagAdapterTest` | US4, FR-021, SC-005, D-26 |
| SOAP test: FREE/CONFIRM/FORBIDDEN; test-id header; correlation by id or uncertain window; timeout keeps diagnostics | `E2eTestServiceTest` (WireMock) | US5, FR-022–FR-024 |
| Every owner (user or user group) imported with `--importUser`; `owners:` is an unknown key | `UserGroupOwnerTest`, `ConfigLoaderTest` | FR-014, research D-26 |
| Import with `tag`: diagram group only (modules + tag refused before sending); tagged after the verified import in the same audited call; a tag failure keeps the import `EXECUTED` with `tag.applied: false` | `ImportTagTest`, `ImportArtifactsToolTest` | US4 AS 5, FR-021, research D-26 |
| Every call audited (refused/preview/executed/failed), codes hashed, no content | `DevelopmentAuditTest` | FR-025 |
| 20 workflows / 100 modules import within 2 minutes (fake StartCLI) | `ImportServiceTest` | SC-007 |

## B. Configuration check

```bash
java -jar target/inubit-mcp-server-*.jar --profile acme --check-config
```

Expected per development node: `development: on (confirmation SERVER)`, `e2e: …`.

## C. Live, opt-in (development node, dedicated test diagram group and workflow only)

```bash
INUBIT_MCP_PROFILE=acme INUBIT_LIVE_DEV_NODE=dev/node1 INUBIT_LIVE_DEV_OWNER=OWNERS \
  INUBIT_LIVE_DEV_DIAGRAM_GROUP=GRP-01 INUBIT_LIVE_DEV_WORKFLOW=Workflow-0001 mvn verify -Plive
```

Scenario (research D-23): export → change one layout value → import (preview + code) → verify →
restore the backup → set_active off/on → tag the group with the fixed, reused tag `LIVE-TEST`
(never removed). Skipped unless diagram group and workflow are named; refused unless the node is a
development node. The owner may be a user or a user group (research D-26).

## D. With the AI assistant

1. "Export SPIKE from dev for jdoe, move node X, import it with reason 'layout'." → preview, code,
   result `EXECUTED`, backupRef.
2. "Undo that." → `restore_backup` with the backupRef.
3. "Tag SPIKE as LIVE-1." → 1 diagram group, n workflows, m modules.
4. "Send samples/order.xml to /ibis/ws/Service-01 on dev." → status, excerpt, processes (only where
   `e2eTests` allows).
