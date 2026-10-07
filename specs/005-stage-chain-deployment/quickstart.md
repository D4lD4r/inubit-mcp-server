# Quickstart: Stage Chain and Deployment (feature 005)

Validation guide. Contracts: [configuration-delta.md](contracts/configuration-delta.md),
[mcp-tools-delta.md](contracts/mcp-tools-delta.md); decisions: [research.md](research.md).

## Offline (always)

```bash
mvn -q clean verify
mvn -q -Dtest=NoCustomerIdentifiersTest test     # with the local identifier lists; must run, not skip
```

Expected: green; in particular

| Scenario | Proof |
|---|---|
| Chain configuration (US6): unknown source, self, cycle, `EXECUTE` on production without opt-in, `deploy` on a node, bad exclude entry | `ConfigValidatorTest`, `ConfigLoaderTest`: startup error naming the groups |
| `--check-config` prints the chain, exclusions, per-node deploy line | `ConfigSummaryTest` |
| Chain cannot be bypassed (US2) | `DeployServiceTest`: no StartCLI launch for a target without `deploy`; the source is not an input |
| Preview per node, classes, active flags, warnings, code only when executable (US1, US5) | `DeployServiceTest` on `DeployHarness` (source + 2 target `FakeInubit`s) |
| Execute: backup, imports in D-7 order, verification, tag, ledger, commit (US1) | `DeployServiceTest`, scripted launches verified complete |
| Failure on node 2 of 3: node 1 deployed, node 2 rolled back, node 3 untouched (US3) | `DeployFailureTest` (import NOK, verify mismatch, rollback failure) |
| Release or target changed between preview and execute | `DeployServiceTest`: `CONFLICT`, nothing written |
| Package-only: no writing command, owner-only package with the node's secrets (US4) | `PackageOnlyTest`, `DeploySecretLeakTest` |
| Exclusions and missing excluded module (US5) | `ReleasePlannerTest` |
| Re-deploying an unchanged release imports nothing (SC-003) | `DeployServiceTest`: state `UNCHANGED`, no import launch |
| `DEPLOY_LOCKED` across processes | `DeployLockTest` with `LockHolder` |
| Restore of a deployment backup needs the server code | `RestoreDeploymentTest` |
| E2E on a target group | `DevelopmentGuardTest`, `DevelopmentWiringTest` |

## Live (opt-in, with the user's approval of the target)

Prerequisites: a profile copy with a chain `<dev> → <test target>` where the test target is **not**
production and the operator has approved writes there; a dedicated test diagram group on the
development node (e.g. the personal test group) with test modules only.

1. Tag the test group on the development node (`tag_artifacts`).
2. `deploy_release` with `target=<test target>`, `tag=<tag>` → preview: only the test group, its
   modules and referenced repository files; no other diagram group.
3. Call again with the code → every node `DEPLOYED`, tag applied, workspace commit
   `deploy <target> ← <dev>: <tag>`.
4. Call again without changes → every node `UNCHANGED` (no new version, SC-003).
5. `restore_backup` with the backup reference of one node (preview + code) → previous state.
6. Check that no other artifact on the target changed (inventory before/after).
