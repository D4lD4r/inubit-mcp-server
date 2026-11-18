TASK: T054 follow-up (remove `setDebug` from `operate_server`; StartCLI `debug on|off` excluded)  ROUND: 1
STAGE1: FAIL
STAGE2: PASS
TESTS: 26 classes (ServerOperationServiceTest … WiringAuditTest), 756 tests, 0 failures, offline. Compiles cleanly; no debugSwitch/switchDebug references left.

FINDINGS:
- [major] research.md:25 – tool overview row still lists "debug" for operate_server; contradicts Clarifications, AS8, D-2, D-22. – Drop it.
- [minor] research.md ~716, ~738 – rewritten lines not rewrapped. – Rewrap.

Checked and fine: spec.md (Clarifications, background row, not-usable group, US4 intro, AS8, AS10), research.md D-2/D-22, contracts, plan.md:283, tasks.md (T054 unchecked; T066 flips no setDebug row); production removals (port and implementations, CliOperationRunner, DebugStateParser, CliCommand debugSwitch and allowlist entries, service SPECS/arms; other arms unchanged); schemas and description; TSV excluded with reason; docs/interface-coverage.md regenerated; new refusal tests; adjusted counts plausible; live tests still send `debug on`/`debug off` via test-only `UnlistedWriteCommand`; deletions without remaining users; no customer identifiers.

VERDICT: CHANGES_REQUESTED
