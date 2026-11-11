# Quickstart: Read the Partner Management

**Feature**: 018-partner-management-read | **Plan**: [plan.md](plan.md)

How to prove the feature works. Contracts: [find_partners.md](contracts/find_partners.md),
[other-changes.md](contracts/other-changes.md). Types: [data-model.md](data-model.md).

## 1. T-record: record and decide the channel (operator, live, non-production) — done 2026-11-09

Kept for re-recording (e.g. for 9.0 later); the outcome is in research.md (D-1 = REST).

Prerequisites: a non-production stage listed in `RECORD_ALLOWED_GROUPS`, a system diagram of the
configured owner that has a Partner Management, and the diagram group it lives in.

```bash
tools/record-fixtures.sh <stage> node1 model_export_systemdiagram
```

```bash
tools/record-fixtures.sh <stage> node1 export_systemdiagram
```

Expected: two neutralized fixtures under `src/test/resources/fixtures/v8_1/{rest,cli}/`; no real
name, host or ciphertext in them:

```bash
python3 tools/check-identifiers.py --all
```

Then apply the D-1 rule (`PartnerChannelDecisionTest` parses both fixtures with
`PartnerManagementReader` and compares the masked models per communication cloud; it stays as a
regression test); record the outcome and the D-2 facts
(workflow identity, summary keys, map/list encoding, variable form) in research.md.

## 2. Offline validation

```bash
./mvnw -q verify
```

Expected: all tests pass; the scenarios below are covered by the named tests.

| Scenario (spec) | Test (task) | Expected |
|---|---|---|
| US1-AS1/2 search by Id and by lower-case name part | `PartnerServiceSearchTest` (T019) | partner found with diagram, group, cloud, fields, entries; secrets `set` |
| US1-AS3 two FTP entries for two workflows | `PartnerManagementReaderTest` (T011) | two entries, export order, not merged |
| US1-AS4 legacy clear-text password, `WSSecurityPassword` without type | `PartnerSecretRulesTest` (T008), `PartnerSecretLeakTest` (T030) | masked; no value, length or hash anywhere |
| US1-AS5 two system diagrams | `PartnerServiceSearchTest` (T019) | both returned, each naming its diagram |
| US1-AS6 no hit with similar values | `PartnerServiceSearchTest` (T020) | ≤ 5 similar Ids/names from non-masked fields |
| US2-AS1…4 list 120 partners, filters `protocol: ftp`, `protocol: SFTP`, workflow | `PartnerServiceListTest` (T023) | stable order incl. cloud, `total`, `nextOffset`; `SFTP` empty; summaries |
| US3-AS1…6, AS8, AS9 comparisons, `sameOn`, failed server, renamed partner, two clouds | `PartnerComparisonTest` (T025) | grouping, `onlyOn`, `notCompared`, matching by diagram, cloud and `Id` |
| US3-AS7 comparison without search term, paging; FR-005a in a comparison | `PartnerServiceCompareTest` (T026) | counts across servers, only differences, `NOT_FOUND` only if on no server |
| US4-AS1/2 workflow with partner variables / without | `InventoryServiceTest` (T029) | `partnerUsage` with clouds; no system diagram read without |
| Op-1 diagram without Partner Management | `PartnerServiceSearchTest` (T019) | named and marked; `ReferencableTWFs` per cloud |
| Op-2 6.1 refused, 9.0 warned | `FindPartnersToolTest` (T022), `CapabilityMatrixTest` (T014) | `UNSUPPORTED_VERSION`; warning text |
| Op-3 cache, `fromCache`, `refresh`, shared read | `PartnerServiceReadTest` (T018), `InventoryCachePeekTest` (T015) | one read for two calls; none within the TTL |
| Op-4 audit write fails | `PartnerServiceAuditTest` (T021) | `INTERNAL`, no partner data |
| Op-5 unknown system diagram; no owner | `PartnerServiceSearchTest` (T020), `PartnerServiceReadTest` (T018) | `NOT_FOUND` + similar names; `NOT_CONFIGURED` for that node only |
| FR-002 overlap, bad search term, bad owner | `PartnerQueryTest` (T007), `FindPartnersToolTest` (T022) | `INVALID_INPUT` before any INUBIT contact; one audit record whose `node` is the target as requested |
| FR-008 at least as strict as the workspace export | `PartnerSecretRulesTest` (T008) | every `SecretPaths` case also masked here |
| FR-012b fingerprint never serialized | `SecretFingerprinterTest` (T009), `SecretFingerprintTest` (T005) | Jackson refuses; `toString` redacted; new key per process |
| D-4 name collision of the REST export | `V81PartnerManagementAdapterContractTest` (T012) | other group/owner → `UNEXPECTED_RESPONSE`, no data |
| SC-004 1,000 partners, bound (one server and comparison) | `PartnerServiceBoundsTest` (T024), `PartnerServiceCompareTest` (T027) | every page ≤ `resultLimits.maxChars`, `truncated` where cut |
| SC-005 timing | `PartnerServicePerformanceTest` (T031) | ≤ 5 s beyond the read on the fixture |
| Principle I no write interface | `PartnerNoWriteTest` (T012) | no reference to `PartnerManagementRestlet` or `modifyPartnerManagement` in `src/main` |
| FR-023 identifiers | `NoCustomerIdentifiersTest`, `check-identifiers.py --staged` | pass |

## 3. Live check (operator, after implementation)

With the real partner and stage the operator names (values stay out of the repository):

1. `find_partners` with `target: <stage>`, `search: <partner Id>` → server, login, protocol and
   authentication settings match what the operator knows; passwords only `set`.
2. Same with `compareWith: <other stage>` → differing hosts/logins per server; secrets `sameOn`.
3. `get_inventory_item` of the fallback workflow → `partnerUsage` names the system diagram.
4. Audit log: one record per server with the same `callId`, no settings.

Record the result in `docs/live-tests.md` in neutralized form only (ACME, `*.example.test`).
