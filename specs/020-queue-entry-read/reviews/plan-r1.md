FEATURE: specs/020-queue-entry-read  ROUND: 1
COVERAGE: all FR-001..FR-020, US1-1..10, US2-1..5, US3-1..5, SC-001..005 mapped to tasks; edge case "credentials not yet available" unmapped.
FINDINGS:
- [major] T003/T028 – new Capability QUEUE_ENTRY_READ changes ConfigSummary.cliOnly (6.1 golden texts) from T003 on; filter only in T028 → move filter + regression test into T003, fix T028 deps.
- [major] T005/T012/T022 – abstract ProcessQueryPort.queueEntries breaks 4 main + 6 test implementers; T022 Gateway/GatewayFactory likewise → default methods (artifacts()/imports()/tags() pattern) or name the fakes; wrong "T017" reference.
- [major] T012 – QueueEntryRowParser tested only on synthetic rows; recorded fixtures v8_1/rest/log_queueLog*.json exist → add contract test over them.
- [minor] data-model QueueEntryService uses admit vs admitWithDetection.
- [minor] quickstart test names differ from tasks (US3-1..3, US2-2).
- [minor] T014/T015 – how tests reach the helper class compiled into META-INF (URLClassLoader + reflective run).
- [minor] credentials-not-yet-available case untested → add to T021 or T023.
- [minor] T037/T038 – fixture commit and matrix flip must be one commit.
- [minor] plan Constitution Check III/V – 8.1 error-marker fixtures reconstructed; note or record one negative case in T034.
VERDICT: CHANGES_REQUESTED
