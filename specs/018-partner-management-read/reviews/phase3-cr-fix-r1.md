TASK: Phase 3 code review findings (fix round)  ROUND: 1
STAGE1: PASS
STAGE2: PASS
TESTS: LogTextTest 4, PartnerAuditTest 17, PartnerServiceAuditTest 10, PartnerServiceReadTest 20, PartnerServiceSearchTest 22, FindPartnersToolTest 17, ArchiveExportServiceTest 73, PackageBoundaryTest 22, InventoryCacheTest 11, InventoryCachePeekTest 8, WiringAuditTest 5, ToolAnnouncementTest 8, SecretFingerprintTest 7, ExportArchivesToolTest 10, ArchiveExportConcurrencyTest 1, ListNodesToolTest 30, InventoryServiceTest 40, InventoryBoundsTest 3 — 0 failures, 0 errors.

Per finding (checked in the code):
1. Resolved: `refuseUnsupported` in the same try as the `PartnerQuery` validation; INVALID_INPUT "<param> is not supported yet", one REFUSED record, no port read; carry-over for T023/T026.
2. Resolved: paging over all servers (server order, export order) with `total` and `OptionalInt nextOffset`; audit and INFO line count the page; byte bound carried over to T024.
3. Not changed on purpose; reason recorded, T032 docs carry-over.
4. Resolved: at most two cache rounds, then one own read without the cache (`read` flag set, audit clock); `ContendedCache` proves rounds `[false, true]` and one port read.
5+6. Resolved: `application/LogText.loggable` (500 chars at a code-point boundary; C0, DEL, C1, U+2028/U+2029 → `?`) used by PartnerService and ArchiveExportService; copies removed.
7. Resolved: `PartnerAudit.Refusal(exception, recorded)`; both log lines tested.
8. Resolved: similar values only when `total == 0` and at least one server answered.
9. Resolved: mirror records removed; `ResultJson` mixin with `@JsonInclude(ALWAYS)` on `WorkflowView.name` (no package rule for `infra`; `FileUpdateCache` already depends on `application`).
10. Resolved: quoted Pattern with `CASE_INSENSITIVE | UNICODE_CASE`; tests with U+0130 and a regex metacharacter.

Side effects judged acceptable: `InventoryCache` not final (test double, no Mockito; Javadoc says why); ArchiveExportService log bound 100 → 500 chars; ArchiveExportService still compares ToolErrors for its refusal line (outside finding 7); `truncated` false for plain paging (ResultLimiter convention); `referencableWorkflows` only for returned partners.

FINDINGS:
- [minor] LogTextTest.java:20 – raw U+2028/U+2029 in a string literal; write them as escapes.

VERDICT: APPROVED

Minor fixed by the orchestrator before commit (escapes ` `/` `).
