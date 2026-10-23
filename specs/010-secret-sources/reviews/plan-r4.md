FEATURE: specs/010-secret-sources  ROUND: 4

All round 3 findings fixed: clear-text e2e rule (T004 refuses AWS-node e2e parameter names with an http SOAP URL and e2e tests not forbidden; T007 limits `e2eCredentialVariables`/`checkE2eNodeNames` to environment nodes; contract row; R-6; checked against ConfigValidator.java:533-601); `SoapE2eClient` switched in T021; R-6/data-model wording; stray markup removed; T012 fetches before `computeIfAbsent` with a test that a failed fetch is not cached.

COVERAGE: FR-001..FR-014, US1-1..US4-2, edge cases, SC-001..SC-006, Constitution VII (T020) all mapped. No scope creep.

FINDINGS:
- [minor] data-model NodeSecrets – `allowance()` duplicates `SecretTiming.allowance(NodeId)` (T013 builds SecretTiming from the plans). – Remove it or name SecretTiming the only owner.
- [minor] data-model SecretPlan validation and configuration contract error table – incomplete vs T004: the clear-text rule's condition "end-to-end tests not forbidden" missing in data-model; the `trustStorePassword`-name checks (error with StartCLI, ConfigValidator.java:807-811; warning without `tls.trustStore`) missing in both. – Add.
- [minor] T010/T013 – edge case "fetches of different nodes do not wait for each other" untested; executor of the shared `CompletableFuture` unspecified (common ForkJoinPool could serialize blocking SDK calls). – Name the executor (e.g. one virtual thread per fetch); test two AWS nodes with a 1 s fake delay resolved in about 1 s.

VERDICT: APPROVED
