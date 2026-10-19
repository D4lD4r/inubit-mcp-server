# Quickstart: INUBIT Version Detection (feature 009)

Validation guide. Contracts: [configuration-delta.md](contracts/configuration-delta.md),
[mcp-tools-delta.md](contracts/mcp-tools-delta.md). Decisions: [research.md](research.md).

## Offline (always)

```bash
mvn -q clean verify
mvn -q -Dtest=NoCustomerIdentifiersTest test     # with the local identifier lists; it must run, not skip
```

Expected: all green. In particular:

| Scenario | Proof |
|---|---|
| Version parsing (suffix, missing patch, garbage → unknown), ranges, same-minor | `InubitVersionTest`, `CapabilityMatrixTest` (domain) |
| `versionLine` accepts `AUTO`/`V8_1`/`V9_X`/`"8.1.17"`; `8.1` (a YAML number) and `"8.1"` (no patch) refused; `V9_X` warning text (US4) | `ConfigLoaderTest`, `ConfigValidatorTest`, `ConfigSummaryTest` |
| `list_nodes` shows `not_yet_detected` without any request, then the detected version and client after `get_health` (US1-1, US1-2) | `ListNodesToolTest` with WireMock request count 0 before `get_health` |
| Failed detection → `unknown` with reason; writes refused, reads unverified (US1-3, US2-3) | `VersionGateTest`, `AdapterGatewayFactoryTest` |
| Client probe in `get_health` within the budget; timeout → part `client`, `not yet detected (timeout)`; failure not cached, retried after 60 s (FR-002) | `HealthServiceTest`, `GetHealthToolTest` timing, `HealthOverviewPerformanceTest` |
| Client comparison `match`/`mismatch`/`not comparable`; range by major.minor (8.1.17 client + 8.1.18 server → warning; + 9.0 → refused; `V9_X` → refused); range missing → warning (FR-011) | `ClientVersionTest`, `VersionGateTest` |
| Every write tool refused on 9.0 / `V9_X` / unknown with only the detection request sent (SC-002, US2-1) | one test per write tool (`ImportServiceVersionTest`, `TagServiceTest`, `E2eTestServiceTest`, `ProcessControlServiceTest`, `DeployServiceVersionTest`), WireMock/`FakeProcessLauncher` show no other request |
| Reads on 9.0 run with the `warnings` entry; test-only matrix marking a read `unsupported` refuses it (US2 test, FR-009) | `DiagnosisServiceTest`, `InventoryServiceTest`, `VersionGateTest` |
| Pin contradicted by `get_health` → writes refused; later matching observation clears it (US2-4, FR-012) | `NodeVersionsTest`, `VersionGateTest` |
| Re-detection before a write after 10 min or after `UNEXPECTED_RESPONSE`/406/unmapped 404; reads use the cache (FR-010) | `VersionGateTest`, `AdapterGatewayFactoryTest` |
| `deploy_release`: 8.1.17 → 8.1.18 allowed; 8.1 → 9.0, unknown source/target, differing sources refused, in preview and execute (FR-014a) | `DeployServiceVersionTest` |
| Tool announcement: profile whose only development nodes are `V9_X` announces no development tools (FR-007) | `DevelopmentWiringTest` |
| A test-only matrix entry and adapter for a fictitious version give exactly the declared tools; 8.1 unchanged (US3, SC-004) | `CapabilityExtensionTest` |
| Refusal text names node, version, capability, client (FR-005, SC-005); audit `REFUSED` (FR-015) | assertions in the tests above |
| No baseUrl, username, password or unscrubbed StartCLI output in refusals, warnings, `list_nodes` `version`, `get_health` `client`/`unavailable` (FR-016) | `VersionGateTest`, `CliVersionProbeTest`, `HealthServiceTest`, `ListNodesToolTest` |
| Mixed-channel reads on 9.0 with an 8.1 client: diagrams listed with a warning, modules refused, item history `unavailable` with `UNSUPPORTED_VERSION`; `check_artifacts` skips the module lookup with the reason (FR-009, FR-011) | `InventoryServiceTest`, `ArtifactCheckServiceTest` |
| Write tools carry admission warnings (client mismatch, failed probe) in `warnings`, incl. restart/kill (US2-5) | `ImportServiceVersionTest`, `ProcessControlServiceTest` |
| Stale triggers: parser `UNEXPECTED_RESPONSE`, 406, endpoint 404 (not a resource 404) (FR-010) | `AdapterGatewayFactoryTest`, `InubitHttpClientTest` |
| Port set chosen by effective version; registry default for every version in production (D-16) | `AdapterGatewayFactoryTest`, `CapabilityExtensionTest` |
| Existing 8.1 behaviour unchanged apart from the deliberate changes (SC-003) | the full suite |

## Live (optional, only with the person's approval)

Only read-only checks are useful here: run `get_health` and `list_nodes` against a development
stage and compare the shown server and client versions with the Workbench "About" dialog and
`startcli.sh -v`. No write is needed to validate this feature. Respect any organisational approval
needed before connecting the server to real systems.

## Validation record (T029)

**2026-10-20, branch `009-inubit-version-detection`, commit `295b545`: offline validation done. The
optional live check was not run (it needs the person's approval; live use is paused).**

- `mvn -q -o clean verify`: exit 0 on the first run (no rerun). 219 test classes, 3120 tests,
  0 failures, 0 errors, 3 skipped. The 3 skipped tests are the opt-in corpus tests
  (`ArchiveCorpusTest`, `StructureCorpusTest`, `XsltCorpusTest`; corpus variables not set).
- `NoCustomerIdentifiersTest` ran (not skipped) inside the build with the local identifier lists
  (denylist, neutralize map, synthetic map and allowlist all present): 20 tests, 0 failures,
  0 skipped; 1204 files scanned, no finding.
- `mvn -q -o -Dtest=NoCustomerIdentifiersTest test` on its own: exit 0; 20 tests, 0 failures,
  0 skipped; 1206 files scanned (the 1204 git files plus the two jars the build left in `target/`),
  no finding.
- Scenario coverage: every class named in the table above exists and ran green in the build
  (tests per class): `InubitVersionTest` 20, `CapabilityMatrixTest` 55, `ConfigLoaderTest` 105,
  `ConfigValidatorTest` 112, `ConfigSummaryTest` 17, `ListNodesToolTest` 21, `VersionGateTest` 29,
  `AdapterGatewayFactoryTest` 44, `HealthServiceTest` 43, `GetHealthToolTest` 12,
  `HealthOverviewPerformanceTest` 1, `ClientVersionTest` 15, `ImportServiceVersionTest` 15,
  `TagServiceTest` 12, `E2eTestServiceTest` 17, `ProcessControlServiceTest` 43,
  `DeployServiceVersionTest` 11, `DiagnosisServiceTest` 24, `InventoryServiceTest` 37,
  `NodeVersionsTest` 21, `DevelopmentWiringTest` 10, `CapabilityExtensionTest` 5,
  `CliVersionProbeTest` 13, `ArtifactCheckServiceTest` 31, `InubitHttpClientTest` 37. No scenario
  is without automated coverage; SC-003 is covered by the full suite being green.
- Live (optional) `get_health` / `list_nodes` against a real node: not run.
