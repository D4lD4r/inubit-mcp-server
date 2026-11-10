TASK: Phase 5 code review findings (fix round)  ROUND: 3
STAGE1: PASS
STAGE2: PASS
TESTS: PartnerComparisonTest 38, PartnerServiceCompareTest 30, FindPartnersToolTest 22, PartnerServiceBoundsTest 14, PartnerBoundsTest 2, PartnerServiceSearchTest, PartnerServiceListTest, PartnerServiceReadTest, PartnerServiceAuditTest, PartnerAuditTest, TemplateGrammarTest 4, TerminologyRenderingTest 8 — 0 failures.

Per finding (checked in code):
1. Resolved: unreadable diagrams passed into `Answered`, `notRead` in `Result`, `incomplete` includes them; `onlyOn` relative to `readers(diagram)`; the same holders feed items, workflows and audit; `notCompared` in server order (failure, then unread diagrams).
2. Resolved: `servers` description names targeted and compared {nodes}.

Round 1 FINDINGS:
- [medium] PartnerService.java `notCompared` – one entry per unreadable diagram, uncut and outside every bound; `fixed` alone can exceed `maxChars`.
Round 2 (fix: `notCompared` from the bounded `ServerView.unreadableDiagrams`, cut names) FINDINGS:
- [medium] PartnerBounds.java:180 – unreadable diagrams cut as a list-order prefix; a returned partner's diagram can be cut, so `notCompared` no longer names the server that could not read it and the absent `onlyOn` reads as "held there".
Round 3 (fix: page diagrams first in `unreadableDiagrams`, first partner's kept, page ends before a partner whose unreadable diagram is not listed) FINDINGS:
- none

VERDICT: APPROVED

Both medium findings of rounds 1 and 2 fixed test-first (`manyLongNamedSystemDiagramsOneServerCouldNotReadStayWithinTheBound`, `theUnreadableSystemDiagramOfAPartnerOnThePageIsKeptFirst`, each failing before its fix).
