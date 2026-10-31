FEATURE: /Users/dadecker/Development/Privat/inubit-mcp-server/specs/011-inubit-61-cli-reads  ROUND: 3

Read spec.md, plan.md, tasks.md, research.md, data-model.md, both contracts, quickstart.md, the constitution and reviews/plan-r2.md; checked the new and changed tasks against DiagnosisService (validateHeld/validate, deadline :295-298, sort :204), ConfigValidator (`checkCli` only when `cliConfigured()`), CliRunner (`checkAvailable`, `JAVA_TOOL_OPTIONS`, trust arguments) and the use sites of CliVersionProbe/ClientVersionPort (Wiring, Launcher, HealthService, VersionGate).

All three round-2 majors are fixed: the missing-StartCLI warning (T014 adds a new warning for a 6.1 node without `cliHome`, 8.1 nodes stay finding-free, both cases tested, configuration-delta row fixed); client-option ownership and order (D-8/T017/T020: adapter-side cache in `CliClientProbe`, `V61Connection` reads synchronously via `optionsFor`, only `-v` runs parallel to the health session in T027, first-call test named); and the `find_processes` deadline (T033/D-10 derive it from the process-diagnosis channel — cliTimeout + timeout + cliTimeout + grace + AWS allowance — with a test that a slow session is answered). All round-2 minors are fixed (T016 independent of `CliClientOptions`, whitespace path refused, unreadable runtime version refused, D-5 alternative keeps the password on stdin, 6.1 warning text from gate admission notes, injectable matrix in `Wiring`, contradiction-clearing test named, client-version label in the contract).

COVERAGE:
- FR-001 → T007, T009, T020, T025, T027, T033
- FR-002 → T003, T013, T014
- FR-003 → T011
- FR-004 → T004, T013, T014
- FR-005 → T005, T034, T040
- FR-006 → T006, T026, T027 (clearing test), T037
- FR-006a → T010
- FR-007 → T006, T007, T008, T009, T032, T033
- FR-008 → T023, T024, T026, T027
- FR-009 → T027
- FR-010 → T029–T033, T038, T039
- FR-011 → T016
- FR-012 → T015, T020
- FR-012a → T017, T018, T020, T021
- FR-013 → T006, T017
- FR-014 → T014, T020, T021
- FR-015 → T016, T019, T020, T033
- FR-016 → D-3/D-4 design, T008, T010, T025, T033
- FR-017 → T024, T031, T037, T038
- FR-018 → T004, T016 (8.1 argv regression), T017 (8.1: no `-h`), T021, existing suites
- FR-019 → T006
- FR-020 → T035, T036, T038 (rows dropped at `-l`), T044
- FR-021 → T045
- FR-022 → T045
- FR-023 → T040, T043, Phase 10
- US1-1…7 → T026, T027, T028
- US2-1…7 → T032, T033; US2-8 → T008, T033
- US3-1/2/3 → T008, T009; US3-4 → T012; US3-5 → T013; US3-6 → T006, T010, T027; US3-7 → T011; US3-8 → T010
- US4-1…4 → T045
- US5-1/2 → T015, T020; US5-3 → T015, T018, T020; US5-4 → T020, T021; US5-5 → T018; US5-6 → T020; US5-7 → T016; US5-8 → T021; US5-9 → T014, T021; US5-10 → T019, T020, T033; US5-11 → T006
- SC-001 → T027; SC-002 → T033; SC-003 → T009; SC-004 → T009, T027, T033; SC-005 → T004, T011, T042, T043; SC-006 → T008; SC-007 → T045

FINDINGS:
- [minor] research.md D-13 / data-model.md "Configuration" row – Both still say a missing StartCLI on a 6.1 node gets "the existing warning (`ConfigValidator.checkCli`)", contradicting the fixed T014 and configuration-delta (new warning for a 6.1 node without `cliHome`; the existing one only for `cliHome` without `bin/startcli.sh`). – Align both texts with T014.
- [minor] data-model.md `ClientVersionPort` row / plan.md structure "ClientVersionPort (+options)" / T017 – Unclear whether `CliClientProbe` is a renamed `CliVersionProbe` or a new class beside it, and whether `ClientVersionPort`'s result carries `CliClientOptions`; since that port runs the probe on every call, carrying options there conflicts with D-8 ("only `-v` runs in parallel", options from the `optionsFor` cache). Launcher (`Launcher.java:236`) builds `CliVersionProbe` directly for `--check-config`. – In T017 state rename vs. new class, how T021/Launcher obtain the options (e.g. `optionsFor`), and either drop the `ClientVersionPort` change or state that it returns the cached options without running `-h`.
- [minor] T033 / D-10 – The CLI deadline (cliTimeout + timeout + cliTimeout + grace + AWS) does not account for the synchronous `-h` run `V61Connection` makes on a first call (bounded by cliTimeout, D-8) or the credential-guard wait; a slow first call may be cut off with `TIMEOUT`. – Add one more cliTimeout for the options probe, or state that it is accepted/covered by the slack.
- [minor] tasks.md Dependencies – Phase 4 may start after Phase 2 in parallel with Phase 3, but T021 edits the same files as T014 (ConfigValidator, ConfigSummary, their tests, LauncherTest) and T025 edits `Wiring` like T012, with no dependency between them. – Add "T021 after T014" and "T025 after T012", or let Phase 4 start after Phase 3.

VERDICT: APPROVED
