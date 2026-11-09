TASK: Phase 2 code review findings (fix round)  ROUND: 1
STAGE1: PASS
STAGE2: PASS
TESTS: `mvn -o -q test -Dtest='PartnerAuditTest,AuditLogTest,AuditRecord*Test,ArchiveExportAudit*Test,SecretFingerprintTest,SettingValueTest,PartnerManagementReaderTest,V81PartnerManagementAdapterContractTest,XmlTree*Test,ResultJson*Test,InventoryBoundsTest,InventoryServiceTest,GetHealthOutputSchemaTest,HealthReportTest,ListNodesToolTest,PartnerNoWriteTest,PackageBoundaryTest,V81Inventory*Test,DiagramExport*Test,CapabilityExtensionTest' -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-018-tmp` exited 0 (0 failures, 0 errors). Full `mvn verify` not run.

Per finding:
1. Fixed at the root. `PartnerAudit` maps a null target to "" and leaves out the `target` input; a blank target is kept. `refused` writes node = `bounded(target)`; `group` only when `Target.parse` succeeds. Tests for null and blank target. `perServer` unaffected (takes a `NodeId`).
2. Fixed. `client.get(..., MediaRange.ANY, 404)` uses the existing accepted-statuses varargs; a 404 gives a per-diagram `UnreadableDiagram` with NOT_FOUND naming group/name and server, no body (test checks). Other diagrams still read; the REST-failure test still covers 401, 403, 503.
3. Fixed. `mismatch` passes only when exactly one workflow has the name and its group and owner match; the reader's `found.size() != 1` cannot throw after the check passes. Test with two workflows of same name, group and owner.
4. Fixed. `bounded` steps back one char at a surrogate pair; test with an emoji at 499/500 checks node and inputs.
5. Fixed. `FAIL_ON_EMPTY_BEANS` removed; a `RefusingFingerprintSerializer` calls `reportBadDefinition` with a fixed message; tests check neither bytes nor Base64 appear.
6. Left unchanged on purpose; reason sound (SC-005, spec.md:545, measures time beyond the read of the system diagrams).
7. Fixed. `XmlTree.Element.elements(String)` and `childText(String)`; private copies gone; `PartnerManagementReader.workflowsNamed` is the one workflow selection.
8. Fixed. `AuditRecord` rejects a null `details` key or value; `AuditLog` has no `case null`; in `PartnerAudit` the record is built inside the try block.

Security: synthetic test data only; no secret or body text reaches a message; read-only holds (PartnerNoWriteTest green).

FINDINGS:
- [minor] V81PartnerManagementAdapter.java:110 (with `NOT_UNIQUE` at line 41) – several workflows of the requested name in the requested group and owner are reported as "returned a diagram of another group or owner"; wrong first half. Expected: separate message for `named.size() > 1` or a neutral NOT_UNIQUE.
- [minor] data-model.md:33 and tasks.md:62 still name `FAIL_ON_EMPTY_BEANS` as the guard for `SecretFingerprint`. Expected: name the refusing serializer.
- [minor] tasks.md:139-142 (T012) and research.md:~116 (D-4) still describe a 404 as a server-level error and only UNEXPECTED_RESPONSE in `unreadableDiagrams`. Expected: note per-diagram NOT_FOUND for an export 404 (also in contracts/find_partners.md if visible there).

VERDICT: APPROVED
