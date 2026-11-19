TASK: T055 blocker fix (CliTagAdminRunner.activeTag on the real 8.1.17 `user` session)  ROUND: 1
STAGE1: PASS
STAGE2: PASS
TESTS: CliTagAdminRunnerTest 16, ActiveTagParserTest 5, CliSessionParserTest 12, TagAdministrationServiceTest 22, ManageTagsLiveTestSupportTest 42, ManageTagsToolTest 13, NoCustomerIdentifiersTest 20 — 0 failures; check-identifiers clean.
Checked: `ranToExit` true only for exactly commands+1 prompts and an empty exit block; cut-off, doubled prompt, output after exit, refused command (NOT_FOUND), login failure, truncation stay errors; error text inside the block fails the strict parser; fixture neutralized with T014/T028 names; D-18 consistent; nothing else changes behaviour.
FINDINGS:
- [minor] CliTagAdminRunnerTest:200 – no test for the new exit-1 path with an unknown block or text after exit. – Add.
- [minor] research.md D-18 / fixture README – refusal forms imprecise (`Command not found.` vs `EXECUTION ERROR`); CliUserRunner.session / CliSettingsRunner.session likely share the defect. – Name precisely, record follow-up.
VERDICT: APPROVED

Orchestrator follow-up (same commit): both minors fixed by the implementer (two tests, CliTagAdminRunnerTest 18 green; D-18 and README name the refusal forms and record the follow-up for CliUserRunner/CliSettingsRunner).
