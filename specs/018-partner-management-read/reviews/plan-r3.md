FEATURE: specs/018-partner-management-read  ROUND: 3

All round-2 findings are fixed (T027 bounds the comparison with an SC-004 test; `partnerUsage.systemDiagrams` carries `cloud`; T013 refusing default `partners()` and `new PortSet(...)` call sites; T029 constructor call sites, `InventoryBounds`, `PartnerUsage`; quickstart map, MCP client in T017, D-1/D-10 texts, duplicate `PortSet`). One new major, several minors.

COVERAGE:
- FR-001 → T014, T022; FR-001a → T011, T012; FR-002 → T007, T022; FR-002a → T018
- FR-003 → T012, T018 (named-diagram-only read not in a done criterion); FR-004 → T006, T011, T019; FR-005 → T019; FR-005a → T023, T026; FR-005b → T020
- FR-006 → T023; FR-007 → T019; FR-008 → T002, T003, T008, T011; FR-009 → T011, T019 (comparison output has no count); FR-010 → T011, T012, T015, T030; FR-011 → T019, T020
- FR-012 → T025, T026; FR-012a → T025, T032; FR-012b → T005, T009; FR-012c → T026; FR-013 → T006, T025; FR-014 → T025, T026
- FR-015 → T028, T029; FR-016 → T013, T014, T018, T022; FR-017 → T005, T024, T027; FR-018 → T015, T018; FR-019 → T012, T018, T020, T022
- FR-020 → T016, T017, T021, T022, T026; FR-021 → T021; FR-022 → T032; FR-023 → T033; FR-024 → T034; FR-025 → T022
- US1 → T019, T020; US2 → T023, T024; US3 → T025, T026, T027; US4 → T028, T029
- Op-1 → T019; Op-2 → T014, T018, T022; Op-3 → T015, T018; Op-4 → T021; Op-5 → T018, T020, T022
- SC-001 → T034; SC-002 → T030; SC-003 → T025; SC-004 → T024, T027; SC-005 → T031; SC-006 → T022, T032
- Edge cases: all mapped except "unknown or new protocols and parameters".

FINDINGS:
- [major] contract `ComparedPartner`, data-model comparison result, T025, T027 – FR-009 requires the number of masked values per partner; `ComparedPartner` has no `maskedCount`. Add it grouped like a setting.
- [minor] T013 / D-4 – Services get ports through `domain/port/GatewayFactory`; add `default PartnerManagementPort partners(NodeId)`; state the refusal code of the `Gateway.partners()` default (not `CLI_UNAVAILABLE`).
- [minor] T012 / D-4 – Throwing `UNEXPECTED_RESPONSE` on a name collision fails the whole server and marks it stale; report the collision per diagram without throwing; contract and test.
- [minor] data-model `SecretFingerprint` – a Jackson annotation in `domain` breaks `PackageBoundaryTest`; rely on `FAIL_ON_EMPTY_BEANS` or a refusing serializer in `infra/ResultJson`; T005 uses the `ResultJson` mapper; result types map `Masked` to `{masked, set}`.
- [minor] T018 – done criterion lacks FR-003 (only the named diagram read) and FR-018 coverage (owner scope answers one diagram, not vice versa).
- [minor] Dependencies – T015 needs T004, T006; T025 needs T010.
- [minor] Phase 3 goal / strategy – "one server or stage": a multi-server stage is a comparison (T026); make the MVP one server or say what happens before Phase 5.
- [minor] T010 / T011 – no fixture with an unknown protocol or setting name.
- [minor] contract – `PartnerSummaryKeys` is not defined anywhere; T023 hard-codes `ServerName`/`Login`.
- [minor] plan.md Scale/Scope – type list and count incomplete (`Setting`, `PartnerField`, `PartnerKey`, `PartnerQuery`).

The Constitution Check is honest; empty Complexity Tracking justified.

VERDICT: CHANGES_REQUESTED
