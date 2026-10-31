FEATURE: /Users/dadecker/Development/Privat/inubit-mcp-server/specs/011-inubit-61-cli-reads  ROUND: 1

Read spec.md, plan.md, tasks.md, research.md, data-model.md, both contracts, quickstart.md and constitution v1.1.0, and checked the plan against VersionGate, HealthService, DiagnosisService, CliRunner, CliVersionProbe, CapabilityMatrix, PortSetRegistry, AdapterGatewayFactory, Wiring and ConfigValidator. Coverage is complete at the requirement/scenario level, but four major findings remain.

COVERAGE:
- FR-001 → T008, T024, T025, T031 (gap for get_health before T025, see major 1)
- FR-002 → T003, T012, T013
- FR-003 → T010
- FR-004 → T004, T012, T013
- FR-005 → T005, T032, T035
- FR-006 → T006, T025, T035
- FR-006a → T009
- FR-007 → T006, T007, T008, T030 (per-node input refusal gap, see major 2)
- FR-008 → T022, T023, T024, T025
- FR-009 → T025 (the "at most three processes" limit has no named test)
- FR-010 → T027–T031, T035 ("not sorted by time" case uncovered, see minor)
- FR-011 → T015
- FR-012 → T014, T019
- FR-012a → T016, T017, T019, T020
- FR-013 → T006, T016 (existing behaviour; no named test that a client range excluding 6.1 refuses)
- FR-014 → T013, T020
- FR-015 → T015, T018
- FR-016 → design (D-3, D-4), T024
- FR-017 → T023, T029, T035
- FR-018 → T004, existing suites (at risk, see major 3)
- FR-019 → T006
- FR-020 → T033, T034, T039
- FR-021 → T040
- FR-022 → T040
- FR-023 → plan stages 7/8, Phase 10
- US1-1…7 → T022–T026 (US1-6/7 in T025)
- US2-1…7 → T030; US2-8 → T031
- US3-1/2/3 → T007, T008; US3-4 → T011; US3-5 → T012; US3-6 → T006, T009; US3-7 → T010; US3-8 → T009
- US4-1…4 → T039, T040
- US5-1/2 → T014, T019; US5-3 → T014, T017; US5-4 → T017, T020; US5-5 → T017; US5-6 → T019; US5-7 → T015; US5-8 → (no explicit task; existing behaviour); US5-9 → T013, T020; US5-10 → T018; US5-11 → T006
- SC-001 → T025; SC-002 → T031; SC-003 → T008; SC-004 → T025, T031; SC-005 → T004, T010, T037; SC-006 → T007; SC-007 → T040

FINDINGS:
- [major] T006 / T025 / Phase 2 checkpoint – `get_health` on a 6.1 node sends REST requests until T025 and no task proves it is refused before contact. `HealthService.checkServer` (HealthService.java:176-225) calls `gateways.monitoring()` and fires healthcheck/ready/system/info/metrics first and only afterwards `gate.admitMonitoring()` (documented as "never detect and never refuse"). With T006 refusing MONITORING on a `cliOnly` entry, between T006 and T025 `get_health` on 6.1 sends four REST requests (violates FR-001) and then throws or warns; the Phase 2 checkpoint ("refused everywhere") and "MVP = Phases 1–4" are false for get_health. Neither T008 nor T025 tests get_health on 6.1 with the production matrix (MONITORING UNSUPPORTED until Phase 8); the gate API HealthService would use to "branch on the monitoring channel" is not assigned to a task. – Expected: in T006 (or a new task right after it) add a gate admission for `cliOnly` nodes called by HealthService before `gateways.monitoring()` (returns the channel or the refusal; `admitMonitoring` stays non-refusing for other nodes); test: 6.1 node + production matrix → report with UNSUPPORTED_VERSION, WireMock 0 requests, no launch; add get_health with the production matrix to V61RefusalTest. T025 builds on this.
- [major] T030 / T031 – The per-node refusal of `tag` (US2-4, FR-007) is lost for nodes whose credentials are already held: `DiagnosisService.validateHeld`/`validate` (DiagnosisService.java:262-286) swallow every error except INVALID_INPUT for held nodes, and those are not re-validated in their fan-out branch (188-195). The UNSUPPORTED_VERSION that research D-10 puts into `V61ProcessQueryAdapter.validate` disappears, the gate admits (6.1 SUPPORTED), `find()` runs and silently ignores the tag filter (same for the REC-dependent refusals ACTIVE/time inputs). – Expected: T030/T031 state where the per-node refusal surfaces (e.g. re-throw UNSUPPORTED_VERSION in the node's fan-out branch, or `find()` refuses too); test in DiagnosisServiceTest/FindProcessesToolTest with a held-credentials 6.1 node in a mixed 8.1+6.1 group: 6.1 entry refused, 8.1 entry results, no launch.
- [major] T016 / T017 / T020 / D-8 – The client-option logic is not scoped to 6.1 nodes, so 8.1 behaviour can change (FR-018) and rests on a synthetic fixture. Today `CliRunner.runUnguarded` (CliRunner.java:86-100) always passes `--trustStoreFilePath`/`--disableHostNameVerification`; T017 makes both depend on probed `-h` options for every node. A failed or misparsed 8.1 `-h` makes fail-closed switch an 8.1 node to JAVA_TOOL_OPTIONS or refuse it with TLS_ERROR (with `disableHostnameVerification`); the 8.1 `help` fixture is "marked synthetic", so an 8.1 production decision rests on an unrecorded output (Constitution III/V), not mentioned in the Constitution Check. D-8/T020 also add an extra `-h` process to every client probe and client options to `--check-config` for 8.1 nodes — an output change not among SC-005's deliberate changes. – Expected: restrict option probing, option-dependent arguments and check-config option output to `cliOnly` (6.1) nodes; keep the 8.1 argument array unchanged with a CliRunnerTest regression test; say so in T016/T017/T020 and D-8. (Alternative: record the 8.1 `-h` output and list the 8.1 changes as deliberate in SC-005/CHANGELOG.)
- [major] T035 – Too large for one implement/review round and hides a possible redesign: it bundles fixture replacement plus both parser adjustments, the D-5 alternative (up to three direct-option runs with new parsers instead of the interactive session — effectively redoing T015/T023/T024/T030), the optional `since` schema change with ACTIVE/time-input refusals, and the matrix flip. – Expected: split into (a) recorded health fixtures + parser changes, (b) recorded `ps` fixtures + parser changes, (c) conditional REC tasks (optional `since` + time-input refusal + "not sorted by time" note; ACTIVE refusal), (d) matrix flip + V61RecordedFixturesTest green; state that the D-5 alternative requires a plan/tasks update with new tasks rather than being absorbed into T035.
- [minor] T011 / US3-4 – In a 6.1-only profile (before recordings) `find_processes` is announced although always refused; filter it by PROCESS_DIAGNOSIS too, or document why get_health/find_processes stay announced.
- [minor] US5-8 – No task tests a 6.1 node with an `http://` URL (ConfigValidator.java:880-905 already covers cli.url); add a 6.1 case to the T013/T020 tests.
- [minor] FR-010 "keep the listing's order and say it is not sorted by time" is in no task, and `DiagnosisService.findProcesses` sorts by `ProcessInstance::since` unconditionally (line 204), which breaks if `since` becomes optional; name both in the conditional REC task.
- [minor] T017 / T019 – T017's tests use V61ConnectionTest before T019 creates V61Connection, and the option refusals are placed both in CliRunner (T017) and V61Connection (T019); give them one owner. V61Connection is also missing from the plan's class list and data-model.md.
- [minor] T019 – The order "option refusals → permit → handshake → runSession" runs the TLS handshake before StartCLI is known to be installed; a 6.1 node without StartCLI (US1-4) gets an unnecessary contact and possibly TLS_ERROR instead of CLI_UNAVAILABLE. Call `CliRunner.checkAvailable` first.
- [minor] Dependencies – "Parallel examples" lists T008–T011 right after Phase 2, while the dependency list has them after T007; T016, T021 and T027 all edit `RECONSTRUCTED.txt` while marked [P] — note append-only edits or sequence them.
- [minor] T033 – Done criterion "RecordFixturesScriptTest (or the existing script checks)" is ambiguous; src/test has no test of record-fixtures.sh. Name one concrete test.
- [minor] T009 – Building "modules of INUBIT 6.1 cannot be looked up" inside ArtifactCheckService puts version text into the application layer (FR-016); take it from the gate's refusal cause.
- [minor] D-4 / T024 – D-4 says `ensureClient()` is no longer called on the 6.1 path, but `AdapterGatewayFactory.Slot.ports()` (line 331) calls it unconditionally; handle it in T024 or correct D-4.
- [minor] T025 / T006 – No named test for FR-009 "at most three StartCLI processes per get_health", nor for FR-013 (client range excluding 6.1 refuses via channels from the matrix entry).
- [minor] T024 – Covers a new port, adapter, registry, gateway factory, port set, RefusingPorts and Wiring; consider splitting registry/gateway plumbing from V61HealthAdapter.
- [minor] T008 / US3-2 – The test should give the 6.1 node write access, as the scenario requires.
- [minor] US1-1 / contract – The spec lists "licensed threads" separately; the plan folds it into the `threads` part — acceptable, but say so in mcp-tools-delta.md.

VERDICT: CHANGES_REQUESTED
