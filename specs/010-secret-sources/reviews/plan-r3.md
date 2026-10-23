FEATURE: specs/010-secret-sources  ROUND: 3

Round 2 findings all fixed (T011 split: T021 SecretLabels, port method + CredentialState in T014; slot awaits secrets outside the lock, interruptible, bound stated; T020 package and `accounts` function with import/tag/check-in tests; T008 byte-for-byte without `source`; T004 both ignored-names cases; T009 NO_CREDENTIALS; shaded-JAR smoke in T015; T009/T010 [P]; R-2 updated; R-1…R-17 and "about 11 new classes" aligned; T003 next step mentions both sources).

COVERAGE: FR-001..FR-014 mapped (FR-007 partial, see finding 1), US1-1..US4-2, edge cases, SC-001..SC-006, Constitution VII (T020). No scope creep.

FINDINGS:
- [major] T004/T007 (FR-007, Constitution II) – `ConfigValidator.checkDevelopment` (ConfigValidator.java:549-566, `e2eCredentialVariables` :570-581) refuses e2e basic-auth over an `http://` `e2e.soap.baseUrl` ("credentials would travel in clear text") but finds credentials only via derived environment variables; nothing refuses this at runtime. An AWS node with `e2eUsername`/`e2ePassword` names and an http SOAP URL falls through to the plain warning, and `run_e2e_test` sends the fetched credentials in clear text. `checkE2eNodeNames` (:587-601) is about environment names only and not limited to environment nodes. – T004: for AWS nodes treat configured e2e parameter names as "e2e basic authentication is set" (http SOAP URL + e2eTests not FORBIDDEN → error naming the parameter names); T007: limit `e2eCredentialVariables`/`checkE2eNodeNames` to environment nodes; tests and a contract row.
- [minor] T021 – changing `SslContexts.forServer` to `SecretLabels` breaks its second caller `SoapE2eClient` (SoapE2eClient.java:88), not listed in T021. – Add a mechanical switch (or overload) to T021; leave only the e2e trust store password source to T012.
- [minor] research R-6 and data-model SecretPlan warning – still "parameter names on an environment node". – Align with T004 and the contract.
- [minor] All design files – stray tool markup lines `</content>` / `</invoke>` (plan.md:161-162, research.md:245-246, data-model.md:108-109, tasks.md:377-378, quickstart.md:49-50, contracts/configuration-delta.md:80-81, contracts/mcp-tools-delta.md:54-55). – Remove.
- [minor] T012 – the SOAP client is created inside `e2eClients.computeIfAbsent` (Wiring.java:281-285); fetching the AWS e2e cell inside the lambda can block other entries for up to 10 s and a failure throws inside it. – Fetch before `computeIfAbsent` (or a supplier); test that a failed fetch is not cached as a client.

VERDICT: CHANGES_REQUESTED
