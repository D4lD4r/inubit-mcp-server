TASK: T054 (write set, W11 and W12)  ROUND: 1
STAGE1: FAIL
STAGE2: SKIPPED
TESTS: 105/19/12/20 green offline. Checks (1)–(6) held: no delete on unknown/changed/partial set or missing ids; lists cannot cross; no request outside the approved list; ERROR-set read equals the find_processes path; nothing raw printed; other lists unchanged.
FINDINGS:
- [major] docs/live-tests.md W12 – a process entering ERROR between the before-read and `processdelete error` is deleted without approval (no id filter) and not visible after; docs claimed new ids are not deleted. – Document the window; add delete-count check via ProcessDeleteAnswerParser.
- [minor] errorIds paging under concurrent change could miss a row. – Require same total on every page and rows == total.
- [minor] tasks.md:924 – IMC-10 owner decision not recorded. – Note it.
VERDICT: CHANGES_REQUESTED
