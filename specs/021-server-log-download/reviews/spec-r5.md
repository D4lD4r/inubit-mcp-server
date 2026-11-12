SPEC: specs/021-server-log-download/spec.md  ROUND: 5

All round 4 findings resolved; FR-013 version-check sentence consistent with US3-3 and research; no constitution conflicts.

FINDINGS:
- [minor] FR-010 end-tag-only case says "up to and including that end tag is masked" while US1-9 keeps the end tag and the text after it; align FR-010.
- [minor] FR-010 "credentials of every configured node cannot be read" reads as "none"; scenario 10 means "any one"; use "any configured node".
- [minor] FR-013 "a free slot for building the excerpt" – limit not stated (one excerpt at a time per MCP server process; waiting longer than the remaining deadline → TIMEOUT); add or add edge case "Concurrent calls".
- [minor] FR-013 deadline start – state that the cliExportTimeout deadline starts after the version check.

VERDICT: APPROVED
