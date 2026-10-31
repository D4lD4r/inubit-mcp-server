FEATURE: /Users/dadecker/Development/Privat/inubit-mcp-server/specs/011-inubit-61-cli-reads  ROUND: 2

Read spec.md, plan.md, tasks.md, research.md, data-model.md, both contracts, quickstart.md, constitution v1.1.0 and the round-1 review, and checked the plan against Wiring, CapabilityMatrix, VersionGate, NodeVersions/NodeVersionState, HealthService, DiagnosisService, ResultLimiter, CliRunner, CliVersionProbe, ClientVersionPort, ConfigValidator and EffectiveNodeConfig/Defaults. All four round-1 majors are fixed (T007 monitoring admission before contact; T033 per-node `tag` refusal incl. held credentials; D-8/T016/T017/T021 scoped to `cliOnly` with an 8.1 argument-array regression test; T035 split into T037–T040 with the D-5 alternative requiring a plan update); nearly all round-1 minors are fixed. Three new majors came out of the code check.

COVERAGE:
- FR-001 → T007, T009, T020, T025, T027, T033
- FR-002 → T003, T013, T014
- FR-003 → T011
- FR-004 → T004, T013, T014
- FR-005 → T005, T034, T040
- FR-006 → T006, T027, T037 (no named test that a 6.1 observation clears the contradiction, minor)
- FR-006a → T010
- FR-007 → T006, T007, T008, T009, T032, T033
- FR-008 → T023, T024, T026, T027
- FR-009 → T027
- FR-010 → T029–T033, T038, T039
- FR-011 → T016
- FR-012 → T015, T020
- FR-012a → T017, T018, T020, T021 (options source unclear, major 2)
- FR-013 → T006
- FR-014 → T014, T021 (missing-StartCLI warning only partly covered, major 1)
- FR-015 → T016, T019, T020 (find_processes deadline, major 3)
- FR-016 → D-3/D-4 design, T010, T025
- FR-017 → T024, T031, T037, T038
- FR-018 → T004, T016, T017, T021, existing suites
- FR-019 → T006
- FR-020 → T035, T036, T044
- FR-021 → T045
- FR-022 → T045
- FR-023 → T040, T043, Phase 10
- US1-1…7 → T026, T027, T028
- US2-1…7 → T032; US2-4 also T033; US2-8 → T033
- US3-1/2/3 → T008, T009; US3-4 → T012; US3-5 → T013; US3-6 → T006, T010; US3-7 → T011; US3-8 → T010
- US4-1…4 → T045
- US5-1/2 → T015, T020; US5-3 → T015, T018; US5-4 → T020, T021; US5-5 → T018; US5-6 → T020; US5-7 → T016; US5-8 → T021; US5-9 → T014 (partial, major 1); US5-10 → T019, T020; US5-11 → T006
- SC-001 → T027; SC-002 → T033; SC-003 → T009; SC-004 → T009, T027, T033; SC-005 → T004, T011, T042, T043; SC-006 → T008; SC-007 → T045

FINDINGS:
- [major] T014 / FR-014 / US5-9 – T014 says "a missing StartCLI on a 6.1 node stays the existing warning", but no warning exists for a node without any `cliHome`: `ConfigValidator.checkCli` runs only `if (server.cliConfigured())` (ConfigValidator.java:517-519) and only warns about a configured but missing `startcli.sh` (:955-958). A 6.1 node with no StartCLI configured gets no finding, breaking FR-014. – Expected: T014 (or T021) adds a warning for `cliOnly` nodes without a configured CLI home ("…; CLI-backed capabilities report CLI_UNAVAILABLE"), keeps 8.1 nodes without CLI free of findings, and names both cases (no `cliHome`; `cliHome` without `startcli.sh`) in `ConfigValidatorTest`/`ConfigSummaryTest`; also fix the configuration-delta row "warning (existing text)".
- [major] T017 / T020 / T026 / T027 – No task says how `V61Connection` obtains the `CliClientOptions` it needs for its option/format refusals and T018's trust arguments, and the ordering contradicts T027. `ClientVersionPort` documents "Caching … are the caller's concern; every call runs the probe" (ClientVersionPort.java:10), probe results are cached in `NodeVersions` (application layer, not readable by an adapter), and T027 runs "the snapshot in parallel with the client probe" — so on the first call the session starts before the `-h` options are known and fail-closed would wrongly refuse a node with `disableHostnameVerification` + pin (`TLS_ERROR`) or use `JAVA_TOOL_OPTIONS` although the client has `--trustStoreFilePath`. – Expected: state ownership and ordering, e.g. `CliClientProbe` keeps an adapter-side per-node cache of the options and `V61Connection` obtains them synchronously from it (running `-h` locally if missing, within the budget) before the refusals; only the `-v` version part runs in parallel with the snapshot. Add a `V61ConnectionTest`/`HealthServiceTest` case: first call, options not yet cached, node with `disableHostnameVerification` + pin and a client that has the option → admitted and `--disableHostNameVerification` passed.
- [major] T033 / FR-015 / US5-10 – `find_processes` on a 6.1 node keeps the REST fan-out deadline `3 × timeout + 0.9 s` (DiagnosisService.java:295-298, justified as "guard wait, detection, query", which does not fit a pinned CLI-only node). With defaults `timeout` 5 s, `cliTimeout` 30 s (Defaults.java:27-28) the fan-out cuts a 6.1 StartCLI session or limiter wait at ~15.9 s, so the node's `cliTimeout` is ineffective for 6.1 diagnosis, a slow `ps -l 10000` on an old server fails, and the TIMEOUT names the wrong setting. No task plans or tests this deadline. – Expected: in T033 derive the per-node deadline from the node's process-diagnosis channel (not a version string): for CLI, limiter wait (`cliTimeout`) + handshake (`timeout`) + session (`cliTimeout`) + grace (+ AWS allowance); add a `DiagnosisServiceTest` case where a 6.1 session longer than 3 × timeout but shorter than `cliTimeout` is answered, not cut off.
- [minor] T016 / T017 / T018 – T016 is [P] next to T017, yet T016's `runSession` already takes "trust options from the given CliClientOptions", a type created in T017, and building trust arguments is listed in both T016 and T018. Drop the `CliClientOptions` parameter from T016 (T018 adds it) or make T016 depend on T017.
- [minor] T018 / D-8 – `-Djavax.net.ssl.trustStore=<path>` in `JAVA_TOOL_OPTIONS` is split at whitespace and Java 7/8 do not support quoting there; a trust-store path with spaces breaks silently. Refuse per node (`TLS_ERROR`, next step "use a path without spaces") or warn in `--check-config`, with a test.
- [minor] T020 / D-8 – The format refusal reads the Java version from `cli.javaHome/release`; unspecified: `cli.javaHome` absent (`JAVA_HOME` fallback) and an unreadable version with a PKCS12 trust store. State that fail-closed applies and name the case in a test.
- [minor] D-5 alternative – The "direct options" alternative does not say how the password reaches the client; `-p` would break FR-011. Note in D-5 that the alternative must also keep the password on stdin, or US1/US2 are blocked.
- [minor] T033 / FR-016 – The 6.1 warning "logs of INUBIT 6.1 cannot be read with query_logs; read them in the Workbench" has no named source; hard-coding it in `DiagnosisService` puts version text in the application layer. Take it from the gate admission (as with T010) or the 6.1 port.
- [minor] T012 – `Wiring.possiblyCapable` uses the static production `MATRIX` (Wiring.java:585-593); the "before and after the flip (test matrix)" test needs an injectable matrix in `Wiring` — say so in T012.
- [minor] T027 / FR-006 – Add a named test that `get_health` observing 6.1 again clears a contradicted 6.1 pin, so `find_processes` is admitted again.
- [minor] mcp-tools-delta `get_health` – If the version turns out to be the client's, the contract drops `version` and only "a warning says so"; FR-006/US1-1 require the value to be reported, labelled as the client's. State that the warning carries the value.

VERDICT: CHANGES_REQUESTED
