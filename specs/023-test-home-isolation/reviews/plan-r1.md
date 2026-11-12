FEATURE: specs/023-test-home-isolation  ROUND: 1
COVERAGE: all FR/US/SC mapped (FR-006 partly, US3 partly).
FINDINGS:
1. [major] T007 / D-2, D-4 / contract – A fresh temp home per server deleted on close breaks DevelopmentLiveTest (import in one wiring, restore_backup with backupRef in a later wiring). LiveTarget needs one shared temp home; TestWiring factory with caller-owned home; LiveTargetTest asserts all six locations and the shared home.
2. [major] plan.md / quickstart.md vs tasks.md – WiringHomeIsolationTest named in plan/quickstart but tests live in WiringAuditTest; quickstart step 1 runs a non-existent class; step 4 vs WiringHomeLocationsTest; Wiring.homeLocations() seam missing from plan/contract.
3. [minor] T005 / D-5 – comment stripping must be literal-aware (InubitHttpClient has "/*" literals); reuse TemplateGrammarTest.withoutComments; put test in architecture package; add getenv().getOrDefault.
4. [minor] T008 – docs/live-tests.md line 525 is about the real server, keep it; T007 prints no path; mention deletion at JVM exit.
5. [minor] T007 – auditDirectory() accessor has no consumer; drop it.
6. [minor] T006 – assert forbidden export root; cite FileUpdateCacheTest:36; assert Context.system(...).userHome() equals user.home in MainTest.
7. [minor] T002 – real-home snapshot can fail spuriously when another worktree writes acme backups concurrently; narrow to this test's files or record the limitation.
8. [minor] T003 touches 11 files (acceptable); forSystem() removal could be its own task.
9. [minor] D-2 "logged and ignored" vs contract/T003 "ignored".
VERDICT: CHANGES_REQUESTED
