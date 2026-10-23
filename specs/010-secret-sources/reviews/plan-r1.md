FEATURE: specs/010-secret-sources  ROUND: 1

COVERAGE:
- FR-001 → T002, T003, T004; FR-002 → T004, T006, T008; FR-003 → T004, T006; FR-004 → T002, T003, T004 (no AWS keys only partly); FR-005 → T009; FR-006 → T004, T006, T010 (e2e SOAP trust store password of AWS nodes not covered); FR-006a → T010, T011, T012; FR-007 → T010, T011, T016; FR-008 → T010, T011 (TLS error texts not covered); FR-009 → T008, T015; FR-010 → T016; FR-011 → T009, T010, T012; FR-012 → T017; FR-013 → T010, T016; FR-014 → T017
- US1-1 → T006, T008; US1-2 → T008; US1-3 → T004; US2-1 → T010, T011; US2-2 → T010; US2-3 → T010, T015; US2-4 → T009, T011, T015; US3 → T015; US4 → T016
- Edge cases: back-off/single flight/timeout → T009, T010; e2e pair → T004; shared parameters → T007; fan-out → T010, T013, T014
- SC-003 → T011, T015; SC-005 → T010; SC-006 → T011
- No task without a requirement (T001, T019 infrastructure)

FINDINGS:
- [major] R-7 vs R-9 / mcp-tools-delta `get_health` / T011, T014 – R-7/T011 build the HTTP client in `Slot.ensureClient()` only after `NodeSecrets.current()`, but `Slot.monitoring()` and `Slot.knownGateway()` also call `ensureClient()` (AdapterGatewayFactory.java:247-250, 273-289), and `HealthService.checkServer` obtains the monitoring port first (HealthService.java:158-163). A slow fetch blocks the whole health report → TIMEOUT; the unauthenticated checks never run; the "credentials not yet available" parts never appear. T014's "non-blocking readiness check on the gateway" names no file (would need a `domain/port/GatewayFactory` method plus fakes). – Decide how the client is built before the secrets arrive (and what happens when the trust store password itself comes from Parameter Store); name the port change and fakes in T011/T014; state that `monitoring()`/`knownGateway()` never block on a fetch; test that the report is produced while the fetch is pending.
- [major] T011/T012, Wiring / SslContexts / ConfigValidator – Trust store password of AWS nodes: (a) `Wiring` builds `SoapE2eClient` with the startup trust store password (Wiring.java:283, 343-349), empty for AWS nodes → `run_e2e_test` TLS fails; (b) `SslContexts.forServer(server.credentialVariables(), …)` (InubitHttpClient.java:196, SoapE2eClient.java:88) names `_TRUSTSTORE_PASSWORD` variables in TLS_ERROR texts → breaks FR-008 for AWS nodes; (c) `ConfigValidator.checkCredentials` (:803-823: trust store password + CLI = error; without `tls.trustStore` = warning) has no equivalent for a configured `trustStorePassword` name. – Extend T012 (SOAP client from the node's main `NodeSecrets` cell), T011 (SslContexts texts per source), T004 (both checks by name, with tests).
- [major] Wiring `policy(...)` / audit (Constitution VII), no task – `WritePolicy.account` comes from the startup username (Wiring.java:352-363) and feeds every audit record (AuditLog.java:180); for AWS nodes it is unknown at startup and a rotated username never reaches the audit. – Decide how the audited account is resolved for AWS nodes; add to a task with a test on the audit record.
- [minor] [P] markers/dependencies: T005 uses `StoreLocation` from T002; T006 and T007 both change `CredentialResolver`; T012 [P] and T013 both change `Wiring.java`. – Fix markers and the dependency section.
- [minor] T011 – `CredentialGuard` built from `server.credentialVariables()` (AdapterGatewayFactory.java:230; InubitHttpClient.java:124/139/176), `V81PortSet` builds `CliOutputClassifier` the same way. – Name the constructor change and `V81PortSet` in T011.
- [minor] T016 – the slot's cached `V81Gateway` holds the old client (AdapterGatewayFactory.java:418, 425). – Discard the gateway too; add to the done criterion.
- [minor] T010/T015 – run-time value checks planned in adapter `NodeSecrets`, but `--check-config` must apply the same checks. – One shared place in `config` reused by both.
- [minor] T010 – SDK `ApiCallTimeout` may not cover slow credential-provider steps (IMDS, SSO refresh). – Also bound the shared future (`orTimeout`) → UNREACHABLE + back-off.
- [minor] T002/T003 – AWS keys (`accessKeyId`, `secretAccessKey`) inside `credentials.source` not explicitly refused. – Unknown keys inside `source` and `parameters` are errors; test.
- [minor] quickstart rows 5 and 8 name `SecretErrorsTest`/`ListNodesToolTest`, which no task creates/extends. – Align.
- [minor] T001 – vague test ("DependencyTest or the existing check"). – Name the test and how it asserts the absence of Netty/Apache clients.
- [minor] T010 is large. – Move the e2e cell into T012.

VERDICT: CHANGES_REQUESTED
