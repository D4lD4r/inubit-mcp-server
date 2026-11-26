FEATURE: specs/025-inubit-74-support  ROUND: 1 (Mandate B replan, T030–T043)

Verdict: CHANGES_REQUESTED — 4 major, 9 minor. Every Mandate B FR and acceptance scenario has a task; constitution check honest. All majors are places where the task design does not fit the current code.

COVERAGE (Mandate B): FR-006 → T035; FR-007 → T038, T035; FR-009 → T035; FR-010 → T038, T035 (ConfigSummary part missing, major 4); FR-010a → T035; FR-011 → T031, T032; FR-014/015 → T033, T040; FR-016a unchanged; FR-020 → T035, T043; FR-021–023, FR-032/033 withdrawn; FR-024/025 → T031, T032; FR-026/027 → T031; FR-028 → T032; FR-029 → T033, T031 (get_health fail-closed check missing, major 3); FR-030 → T038, T041, T035 (part (b) no workable mechanism, majors 1–2); FR-031 → T037; FR-034 → T036; FR-035 → T039, T035; FR-036 → T038, T043, T035; FR-037 → T042. US6 → T031 (+T035, T037, T038); US7 → T032, T041, T035; US9 → T038, T043, T035. SC-005 → T035; SC-007 → T031, T032, T041, T037; SC-009 → T040, T033; SC-010 → T042, T043. No scope creep.

CROSS-CHECK mismatches: per-node error entries carrying warnings vs NodeResult (failure has no payload); "no schema changes shape" vs find_processes.output.json description "a failed {node} has only an error"; warning/no-warning error codes not distinguishable (same codes from both sides); client check `-v` vs get_health path (no client check); `--check-config` "available: monitoring, process diagnosis" vs ConfigSummary.cliOnly (counts only SUPPORTED).

FINDINGS:
- [major] T031, T041, plan "warning scope" – pre-contact refusals and attempt errors are thrown by the same port call (V61Connection.session); codes overlap (AUTH_FAILED for missing credentials and rejected login; TLS_ERROR for refused options and rejected handshake; TIMEOUT for no free slot; CLI_UNAVAILABLE inside the port). Neither planned rule works; T031/T041 tests would fail. Decide the mechanism (e.g. a "contacted" marker on the thrown error set by V61Connection once the attempt starts, or a separate pre-contact check step on the port) and name its files.
- [major] T041 files – a failed find_processes entry cannot carry warnings: NodeResult.failure has no payload, NodePages.of takes warnings only from the payload, FanOut's deadline TIMEOUT (FanOut.java:82) has no access to them, schema description says "only an error". Design the carrier; add NodeResult, NodePages, FanOut, schema description, docs/tools.md to T041 and the plan; data-model; T041 test for the FanOut deadline TIMEOUT.
- [major] T033 / FR-029 – the fail-closed client check sits only in VersionGate.checkClient, which get_health never reaches (monitoringAccess, runHealthSession without launch guard, probe parallel to session, HealthService.client only warns). Decide how get_health applies it for own-client entries (e.g. probe before the session, refuse CLI_UNAVAILABLE), name HealthService / V61Connection.healthSession, add a HealthServiceTest case; NodeOverview.clientRefusal should mirror it.
- [major] T035 / T038 – ConfigSummary.cliOnly (:446) counts only SUPPORTED as available; add it to T038 (UNVERIFIED read on a CLI-only version counts as available) with a ConfigSummaryTest case; list ConfigSummary in the plan scope.
- [minor] T031 / T041 overlap on HealthService.checkServerViaCli error warnings, no order; T031 too large. Keep T031 to adapter/profile/port set/comparePatch/warning on data; move all error-entry warning work to T041; T041 after T031.
- [minor] T039 [P] edits CapabilityMatrix and its test like T033, T035, T038; drop [P] or order it.
- [minor] T032 / contract – VersionGate.notes adds "logs of INUBIT 7.4 cannot be read with query_logs; read them in the Workbench" to every find_processes entry on 7.4 and 6.1; list it in the contract, assert in T032 and T043.
- [minor] T033 – CLI_UNAVAILABLE text comes from CliRunner.noHome / Selection.entryHint and says "set cliHome"; name them and test without cliClients and with only defaults.cliHome.
- [minor] T035 – announcement (Wiring.possiblyCapable) does not depend on a client; drop "with a client" or test both.
- [minor] plan / research D-16 say V61Connection, V61HealthParser, V61PsParser take a CliLineProfile; tasks give it to V61HealthAdapter, V61ProcessQueryAdapter, V61QueryRules, V61SessionParser; align.
- [minor] tasks.md line 32 says T026–T040 (runs to T043); T035 cites VersionLineRegressionTest :53-54 (rows are :53-55).
- [minor] quickstart "Mandate B — validation" lacks a test→FR table like Mandate A.
- [minor] T040 / SC-009 – name V74FixturesShapeTest (rejects unlisted cases) under SC-009.

VERDICT: CHANGES_REQUESTED
