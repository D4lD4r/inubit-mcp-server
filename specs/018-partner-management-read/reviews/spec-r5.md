SPEC: specs/018-partner-management-read/spec.md  ROUND: 5

All 11 round-4 findings have been worked in: FR-006 names the cloud in the summary and uses `ServerName`/`Login` as the summary keys for every protocol; FR-008 adds the `fieldType` allowlist (unknown types masked); FR-004 and FR-012 cover `format`; FR-013 covers unresolved references and clouds with the same name (Key Entities tells them apart by module id); story 3 has scenario 9 for communication clouds; FR-001a states the REST decision and limits the StartCLI clauses to a later change; FR-001 and research D-1 agree on where the tool is offered; the "Partner without `Id`" edge case says "non-masked"; the cloud sort order is in story 2 scenario 1; FR-012b says the key is created "once per server process, in memory".

No [NEEDS CLARIFICATION] markers; nothing conflicts with the constitution (Principle II masking is fail-closed, audit and logging follow Principle VII, one new tool per Principle IV). No new blocker or major issues.

FINDINGS:
- [minor] FR-007 vs story 1 scenario 6, Edge case "No system diagram, or no Partner Management in it" – Both require the result to state "which system diagrams were read"; FR-007 only requires those read without a Partner Management. The contract has `systemDiagramsRead`, but no FR requires it. – Proposed: "The result MUST name every system diagram read per server (name and diagram group), and MUST mark those without any Partner Management; it MUST also report the `ReferencableTWFs` of every communication cloud that contributed a partner."
- [minor] FR-007 / same edge case – A cloud with `PartnerManagementSettings` but an empty `Partners` list is in neither list. – Question: only in the "read" list, or also marked as "Partner Management without partners"? State it in FR-007.
- [minor] FR-002 – Validation is defined for names and the search term, not for the `protocol` filter and not explicitly for `workflow`. – Proposed: "`systemDiagram` and `workflow` are validated as the existing diagram names; `protocol` as 1–64 characters of letters, digits, `-` and `_`".
- [minor] FR-012c / story 3 scenario 7 – How a summary field (`ServerName`, `Login`) is shown when it differs between servers is not said. – Proposed: "summary fields are grouped like settings (FR-012): shown once when equal, per server when they differ".
- [minor] FR-015 – The conditional move of story 4 has been decided by the plan (`partnerUsage`, research D-11). – Record it in "Plan research decisions" and drop the conditional sentence.

Note for the plan: until the `fieldType` allowlist is fixed from a re-recorded export, FR-008 masks every partner field, including `Id` and `Name`, so search, suggestions and matching have nothing to work on; the tasks should make the re-recording a prerequisite of the search tasks.

VERDICT: APPROVED
