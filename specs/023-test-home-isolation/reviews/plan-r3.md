FEATURE: specs/023-test-home-isolation  ROUND: 3
All round-2 findings resolved and checked against the code; full FR/US/SC coverage.
FINDINGS:
- [minor] research D-4 says "private isolated(...)"; tasks/contract say package-private.
- [minor] T007 (a) – put the Context.system userHome assertion into its own @Test without the OS condition (the neighbouring test is @DisabledOnOs(WINDOWS)).
- [minor] T004/T007 – state that T002/T006 are the red tests for the Launcher step and T007 pins the unchanged layout afterwards.
VERDICT: APPROVED
