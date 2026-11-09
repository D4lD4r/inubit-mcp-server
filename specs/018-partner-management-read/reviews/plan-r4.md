FEATURE: specs/018-partner-management-read  ROUND: 4

All round-3 findings are fixed: `ComparedPartner.maskedCount` grouped like a setting (contract, data model, T025, T027); `GatewayFactory.partners(NodeId)` and an `UNSUPPORTED_VERSION` default of `Gateway.partners()` (T013); name collisions reported per diagram without throwing (T012); `SecretFingerprint` without Jackson annotation, tested against the `ResultJson` mapper (`FAIL_ON_EMPTY_BEANS` kept, checked); T018's done criterion covers FR-003 and FR-018; T015 after T004/T006, T025 after T010; MVP one server; unknown-protocol fixture (T010); `PartnerService.SUMMARY_KEYS`; complete type list.

Checked against the code base: `SecretPaths.kind`/`isKeyMaterial` (private static, widening correct), `XmlTree`/`ArchiveReader` (public), `InventoryCache.get(Key, refresh, loader)`, `SimilarNames.of`, `ItemBounds.MAX_FIELD_CHARS`, `InubitHttpClient.MAX_BODY_BYTES`, `ToolHints.readOnly(title, openWorld, idempotent)`, `Wiring.toolHandlers()`/`anyPossiblyCapable`, the default-method pattern in `Gateway`/`GatewayFactory`, and every named test file exist. `PackageBoundaryTest` lets `domain` depend only on `java.*` and `domain`; the HMAC belongs in the adapter, as planned. No blocker or major findings remain.

COVERAGE:
- FR-001 → T014, T022; FR-001a → T011, T012; FR-002 → T007, T022; FR-002a → T018
- FR-003 → T012, T018; FR-004 → T006, T011, T019; FR-005 → T019; FR-005a → T023, T026; FR-005b → T020
- FR-006 → T023; FR-007 → T019; FR-008 → T002, T003, T008, T011; FR-009 → T011, T019, T025, T027; FR-010 → T011, T012, T015, T030; FR-011 → T019, T020
- FR-012 → T025, T026; FR-012a → T025, T032; FR-012b → T005, T009; FR-012c → T026; FR-013 → T006, T025; FR-014 → T025, T026
- FR-015 → T028, T029; FR-016 → T013, T014, T018, T022; FR-017 → T024, T027; FR-018 → T015, T018; FR-019 → T012, T018, T020, T022
- FR-020 → T016, T017, T021, T022, T026; FR-021 → T021; FR-022 → T032; FR-023 → T033; FR-024 → T034; FR-025 → T022
- US1-AS1…6 → T011, T019, T020 (AS4 also T008, T030); US2-AS1…4 → T023, T024; US3-AS1…6, AS8, AS9 → T025; US3-AS7 → T026, T027; US4-AS1/2 → T028, T029
- Op-1 → T019; Op-2 → T014, T018, T022; Op-3 → T015, T018; Op-4 → T021; Op-5 → T018, T020, T022
- SC-001 → T034; SC-002 → T030; SC-003 → T025; SC-004 → T024, T027; SC-005 → T031; SC-006 → T022, T032
- Edge cases: all mapped.

FINDINGS:
- [minor] contracts/find_partners.md `ComparedPartner` / T027 – one merged `settings` list per compared entry; keep `protocolSettings`/`additionalParameters` separate (or a `kind`), show where a compared `format` goes.
- [minor] plan.md Project Structure – `domain/model/partner/` comment and "nine of them" out of line with Scale/Scope (14 types).
- [minor] T004 or T006 – add `domain/model/partner/package-info.java`.
- [minor] research.md D-2, D-9 and plan.md Implementation order – `PartnerSummaryKeys` vs `PartnerService.SUMMARY_KEYS`; D-9 cut order "of non-matching entries" vs T024; audit (step 6) after comparison (step 5) vs T021 before T026.

The Constitution Check is honest; empty Complexity Tracking justified.

VERDICT: APPROVED
