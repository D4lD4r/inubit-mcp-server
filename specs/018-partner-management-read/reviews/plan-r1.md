FEATURE: specs/018-partner-management-read  ROUND: 1

COVERAGE:
- FR-001 → T020, T014; FR-001a → T012, T011; FR-002 → T007, T020 (`owner` not validated); FR-002a → T018
- FR-003 → T012, T018, T015; FR-004 → T006, T011, T018; FR-005 → T018; FR-005a → T022 (comparison clauses uncovered)
- FR-005b → T019; FR-006 → T022, T018; FR-007 → T018; FR-008 → T008, T011, T028; FR-009 → T011, T018
- FR-010 → T011, T012, T015, T028; FR-011 → T018, T019; FR-012 → T024; FR-012a → T024, T030; FR-012b → T005, T009
- FR-012c → T025; FR-013 → T006, T024; FR-014 → T024, T025; FR-015 → T026, T027; FR-016 → T014, T018, T021
- FR-017 → T007, T023; FR-018 → T015, T018, T029; FR-019 → T012, T018, T019, T020; FR-020 → T016, T017, T018, T020, T025
- FR-021 → T018; FR-022 → T030; FR-023 → T031; FR-024 → T032; FR-025 → T020
- US1 → T011, T018, T019 (AS4 also T008, T028); US2 → T022, T023; US3 → T024, T025; US4 → T026, T027
- Op-1 → T018; Op-2 → T018, T020; Op-3 → T015, T018; Op-4 → T017, T018; Op-5 → T018, T019, T020
- SC-001 → T032; SC-002 → T028; SC-003 → T024; SC-004 → T023; SC-005 → T029; SC-006 → T020
- Edge cases mapped (T004, T006, T008, T011, T012, T023, T024); "same name in two diagram groups" not sound on REST.

FINDINGS:
- [major] T013 / plan "Source Code" – Adding a port touches `domain/port/Gateway.java` (new `partners()`), `adapter/V81Gateway.java`, `adapter/V81PortSet.java`, `adapter/cli/v61/V61PortSet.java` and `adapter/RefusingPorts.java` (6.1 refusing stub), `adapter/StaleReportingPorts.java` (wrap so UNEXPECTED_RESPONSE marks the node stale). List them; test the stale wrapping and the 6.1 refusal.
- [major] FR-005a (comparison part) → T024/T025 – NOT_FOUND only if the system diagram exists on none of the answering servers (else only-on); entry filters per entry key on all compared servers. No task covers these.
- [major] T018 / research D-4 – The existing system-diagram listing is cached under `Key(server, DIAGRAM, ALL)` with the configured owner (`InventoryService.diagrams`, private); an explicit `owner` would get the wrong diagrams; T027 makes InventoryService call PartnerService → cycle if PartnerService uses InventoryService. Decide how PartnerService gets the owner-specific list; name the method story 4 calls; list `Wiring.java` in T027.
- [major] research D-1/D-4, T012 – `GET /ibis/rest/model/export/<name>` takes only a name; group/owner cannot be selected. State what INUBIT does on collisions; check `diagramGroup`/owner of the returned archive against the DiagramRef; mismatch → actionable error, never data; contract test.
- [major] T007 / contract – `owner` has no validation rule; use `^[A-Za-z0-9_.][A-Za-z0-9_.\- ]{0,199}$` (as in `export_archives.input.json`), in T007, schema and contract.
- [major] T018 – Too large; split into (a) fan-out, owner, gate, listing, cache; (b) search, details, FR-007; (c) audit and log integration.
- [major] Dependencies – T006 depends on T004 and T005 (not parallel); T024 also needs T014 and T015.
- [minor] T008 – `SecretPaths.kind(...)` (returns null) and `isKeyMaterial(Element, …)` are private static; widen; add `SecretPaths.java` to T008.
- [minor] T026 – Parser result travels in `InventoryPort.DiagramMetadata`; add `domain/port/InventoryPort.java`; check fakes.
- [minor] T003 – "Blocks T009" should be T008; re-recording the CLI fixture must be mandatory (its fieldType values are anonymized); add T003 → T010.
- [minor] research.md D-2/D-3/D-6 – Decision texts stale vs T-record facts and data model (`WorkflowRef` with group, per-protocol `PartnerSummaryKeys`, type `PartnerManagement`, `PartnerSnapshot.withoutPartnerManagement`).
- [minor] data-model `Partner.key()` – Partner has no diagram/cloud; both-sides fallback belongs to `PartnerComparison`.
- [minor] contract / T018 – Output shape of an unresolved workflow reference and the warning path to `servers[].warnings` undefined.
- [minor] T024 – FR-012a two-holder minimum for equality statements missing.
- [minor] T015 / D-6 – `InventoryKind.PARTNERS` conflates the domain enum used in tool outputs with a cache kind.
- [minor] T021 vs T014 – duplicate `ConfigSummaryTest`; no production file in T021.
- [minor] plan.md header – says spec approved in round 3; it is round 5.

The Constitution Check is honest; Complexity Tracking empty is justified.

VERDICT: CHANGES_REQUESTED
