# Plan review – round 1 (2026-11-05)

FEATURE: specs/014-stage-distinct-certs  ROUND: 1

Verdict: CHANGES_REQUESTED (3 major, 0 blocker). Every FR and every acceptance scenario has at
least one task. Two runtime gaps come from the code, checked in `PinningTrustManager.java`,
`InubitHttpClient.java` (around line 401) and `CliRunner.java` (around line 89).

## Coverage

- FR-001 → T006, T011, T024 · FR-002 → T007, T008, T010 · FR-003 → T003, T004, T008, T014, T017
- FR-004 → T006, T008, T003/T004 · FR-005 → T009, T010 (REST only, finding 2)
- FR-006, FR-007, FR-008 → T011, T012 · FR-009 → T015, T016, T018 (+T029 Q6)
- FR-010 → T014, T015, T016 · FR-010a → T011, T012, T021, T022, T023, T030 (Claude part: finding 1)
- FR-011 → T005, T015, T016, T017, T021 · FR-012 → T014, T016 · FR-013 → T011, T017
- FR-014 → T021, T022 · FR-015 → T011, T015, T019, T020, T021 · FR-016 → T023, T025, T030
- FR-017 → T011, T014, T021, T028 · FR-018 → T001, T024, T025, T026, T028
- US1 AS1 → T008, T029 · AS2 → T011 · AS3 → T010, T011 · AS4 → T011 · AS5 → T008 · AS6 → T006,
  T008 · AS7 → T009
- US2 AS1 → T011 · AS2 → T015, T016 · AS3 → T014 · AS4–AS6 → T015 · AS7 → T014 · AS8 → T011, T017
  · AS9 → T018, T029
- US3 AS1, AS2 → T021, T022 · AS3 → T023, T030 (finding 1)
- SC-001 → T008, T010, T011, T029 · SC-002 → T029 · SC-003 → T015, T029 · SC-004 → T010, T011,
  T029 · SC-005 → T011, T021, T028 · SC-006 → T021, T030

## Findings

1. **[major] Claude rule cannot fire for a swapped certificate** (contracts/cli.md C-8′, T023,
   T024, T030, research R-4). The C-8′ rule fires only on `TLS_ERROR` with
   `CertPathValidatorException`. In a marked stage the shared trust store holds every server's
   certificate, so when `int/a` presents `int/b`'s certificate the chain check passes and
   `PinningTrustManager.checkPin` throws a plain `CertificateException`
   (`InubitHttpClient.transportError` reports "… (CertificateException)"). The same holds for a
   replaced but not yet pruned certificate. FR-010a's "Claude's question MUST show the same note"
   cannot be met; docs/setup.md (around line 1226) says the refusal is always a
   `CertPathValidatorException`. Expected: widen the C-8′ trigger to a pin mismatch
   (`CertificateException`) as well (contract, T023, T030, T024 docs); record in R-4 which exception
   each case produces; T010 asserts the reported cause for the swapped-certificate case.
2. **[major] CLI-backed tools do not check the pin** (plan Constitution Check row I, research R-4,
   spec FR-005 / SC-004, T024). REST and SOAP use `SslContexts` + `PinningTrustManager`; StartCLI
   (`CliRunner.runUnguarded`) gets only `--trustStoreFilePath` and `--disableHostNameVerification`.
   For uniform stages this is the documented residual risk (docs/setup.md around 1219 and 1351),
   reduced by pruning. In a marked stage the shared store always holds every server's certificate
   and the contract example disables hostname verification, so CLI calls to `int/a` (deploy
   included) accept `int/b`'s certificate and pruning can never fix it. Expected: acknowledge the
   CLI path in R-4 and the Constitution Check; narrow FR-005 / SC-004 to REST/SOAP or add a
   mitigation (e.g. a `--check-config` warning for a marked stage with CLI nodes and
   `disableHostnameVerification`); T024 documents the permanent risk and SANs with hostname
   verification as the way out.
3. **[major] Test-only tasks cannot meet the done criterion** (tasks.md "Tests" header, T003, T011,
   T014, T015, T021). The done criterion is "green", and every commit stays green; these tasks only
   add failing tests. Expected: merge each with its implementation task, or give test-only tasks
   their own done criterion and one shared commit.
4. [minor] T007, T010 cover existing behaviour and pass at once: mark them as regression tests.
5. [minor] T011/T012 and T021/T022 are large; split evaluation + JSON vs. text report + superseded
   entries, and dialog text / `offer_info` vs. worker / outcomes / status file.
6. [minor] T011 still says stage `rejected` "(see open points)"; it is `null`.
7. [minor] `[P]` markers: T024 is [P] but edits `docs/setup.md` like T023; T007–T009 are parallel in
   Dependencies but not marked [P].
8. [minor] plan.md does not list `src/test/java/de/dadecker/inubit/mcp/MainTest.java` (T009).
9. [minor] T018 has no offline case "dialog worker holds `cert-check.lock` while `--accept` runs".
10. [minor] R-7 omits "marked stage without any effective trust store" (T003 tests it); data-model
    R-9 says `--interactive` removes a stage-keyed leftover, but no task tests that.
11. [minor] `e2e.soap.baseUrl` on a marked group: each node's SOAP client pins its own certificate
    against one shared URL, so at most one node can pass; say in R-4 / T024 to set it per node.

VERDICT: CHANGES_REQUESTED
