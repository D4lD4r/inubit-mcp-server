TASK: Phase 4 code review findings (fix round)  ROUND: 1
STAGE1: PASS
STAGE2: PASS
TESTS: PartnerBoundsTest 2, PartnerServiceBoundsTest 14, FindPartnersToolTest 21, PartnerServiceSearchTest 22, PartnerServiceListTest 11, PartnerServiceReadTest 20, PartnerServiceAuditTest 10, PartnerAuditTest 17; MainTest 75 (3 skipped), WiringAuditTest 5, PackageBoundaryTest 22, ProcessControlToolsTest 11, ToolAnnouncementTest 8, ToolHintsTest 4, TemplateGrammarTest 4 — 0 failures.

Per finding (checked in code):
1. Resolved: page diagrams ranked in page order, first partner's always kept; `PartnerBounds.Servers.page` = longest page prefix whose diagrams are listed, passed to `PartnerBounds.page`; shortened page `truncated` with `nextOffset`; test over all pages in list mode.
2. Resolved: `TOOL_FIELDS_CHARS` removed; `Found` carries mode, compared, offset; tool returns it unchanged; tool test incl. offset Integer.MAX_VALUE and no extra fields.
3. Resolved: running totals in `PartnerBounds.Page`.
4. Resolved: clouds cut once per server+cloud (`computeIfAbsent`).
5. Unchanged on purpose; T026 carry-over accepted.
6. Resolved: `fit` by position; test with ten equal entries.
7. Resolved: uncut candidates, cut with marker in `PartnerBounds.similarValues`, `truncated` set.

FINDINGS:
- [minor] PartnerBounds.java:238-244 – duplicates after the cut; remove them after the cut.

VERDICT: APPROVED

Minor fixed before commit: `.distinct()` after the cut; test with two names differing only after the cut gives one suggestion.
