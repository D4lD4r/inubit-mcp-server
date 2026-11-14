FEATURE: specs/022-full-interface-coverage  ROUND: 3

Round 2 follow-up: all round-2 findings are fixed.
- Major (gate per call): D-2 now has "gate per call". In the data model, `InterfaceCoverage.offered/refusal` take the entries of a call, and `ActionSpec` has `entries(input)`, `parts` and `declaredEntries()`. T010 adds `admitCall` and `admitParts`, with tests for a variant refused while its sibling is admitted, a part reported as unavailable, and a node without a client. T021, T043, T049, T084, T088 and T097 use the new gate.
- Minors: D-23 order; D-11 alternatives; T085 module backup before `deployKeys`; T095 after T094; the serial points T062/T063 and T087; `InterfaceCoverageCheckTest` used everywhere; `ChangeToolsProductionRefusalTest` named and created in T065; plan increment 6 and the `settings` comment; the missing types added to Project Structure.

Fixing them left one gap in the coverage check, rated major (finding 1).

COVERAGE:
- FR-001 → T006, T007
- FR-002 → T007, T045, T066/T074/T091/T102 (exclusions from the live checks), T104
- FR-003 → T004, T005, T008, T010 (check 6)
- FR-004 → T027, T038, T047, T066, T074, T091, T102; check 5 in T008 (see finding 1)
- FR-005 → T044, D-1
- FR-006 → T001, T107
- FR-007 → T022, T035, T043, T044, T061, T064, T073, T086, T089, T101
- FR-008 → T019, T032, T041, T035 (msgread), T044 (check-in trace)
- FR-009 → T021, T034, T043, T044
- FR-010 → T013, T017, T040, T036
- FR-011 → T030, T034, T036
- FR-012 → T044
- FR-013 → T010 (admitParts), T019, T021
- FR-014 → T049, T065, T073, T090, T101
- FR-015 → T051
- FR-016 → T049, T067, T085, T090
- FR-016a → T072
- FR-017 → T051, T060, T072, T085
- FR-018 → T053, T064, T083, T084, T085 (module backup before keys)
- FR-019 → T094, T097, T098
- FR-019a → T062, T063, T064, T066
- FR-019b → T095, T099
- FR-020 → T041, T068, T088, T100
- FR-021 → T075, T076
- FR-022 → T077, T088, T093, T098
- FR-023 → T052
- FR-024 → T059, T060, T064, T072, T084, T085, T088, T097
- FR-025 → T010, T025, T026, T103
- FR-026 → T054, T055, T069, T079, T092 (gates)
- FR-027 → T027, T038, T047, T066, T074, T091, T102, T104
- US1 AS1–AS7 → T015–T023, T025
- US2 AS1–AS6 → T029–T037
- US3:
  - AS1, AS5 → T045
  - AS2, AS3, AS6 (read), AS8 → T043
  - AS4, AS6 (export), AS7 → T044
- US4:
  - AS1–AS3, AS5–AS11 → T059
  - AS4 → T060
  - AS12–AS14 → T064
- US5 AS1–AS7 → T072, T073
- US6:
  - AS1, AS2, AS9, AS10, AS12, AS16, AS17 → T084
  - AS3, AS4, AS8, AS14, AS15 → T085
  - AS5–AS7, AS11, AS13 → T088
- US7:
  - AS1, AS4, AS5, AS9, AS10 → T097
  - AS1 (with entry), AS2–AS4 → T098
  - AS6, AS7 → T095, T099
  - AS8 → T100
- Start options `--backupFile` / `--backupRepository` → T079, T087, T088, T091
- Edge cases:
  - version differences → T010, T043
  - 6.1 → T010
  - missing role or CLI right → T019, T020
  - state changed → T051, T060, T085, T088
  - maintenance mode → T020, T059
  - nothing to do → T060
  - restart needed → T085, T088
  - own access → T094
  - upload refusals → T076
  - unknown output → T018
  - stage id for a change → T049
  - second change → T050 and the lock keys in each service task
  - IS Connector payload → T079, T084

FINDINGS:
- [major] contracts/coverage-list.md check 5 / data-model `InterfaceCoverage` / T006, T008, T027 (and T038, T047, T066, T074, T091, T102) – The coverage gate cannot pass in the way the tasks deliver it:
  - Check 5 allows a `planned` row in a delivered story only when a comment line `# waits: L-n` goes with it. That marker is defined only in the contract. The data model, the loader in T006 (which skips comment lines) and research D-2 (which states the rule with no exception) do not define it. The contract does not say which row a comment belongs to, and no flip or docs task tells the implementer to add it.
  - T027 sets `delivered-stories: US1` while the 9.0 rows of US1 stay `planned` until T024/T026, which wait for the pin renewal, not for a live check `L-n`. The US1 build is therefore red against check 5 as written. The same happens in every later PR that merges with open live checks, as the plan's Structure Decision allows.
  - The contract's own example (`# delivered-stories: US1` with `GET /license … planned` for 9.0 and no marker) breaks check 5.

  Expected change: make the wait marker data. For example, add a TSV column `waits` (`L-n`, `9.0-pin` or `-`) or a state form `planned:<reason>`. Model it in data-model `CoverageEntry` and the T006 loader rules, align D-2 and check 5, fix the contract example, and tell T025/T027 and each later flip/PR task to set the marker on rows left open. T104 then asserts that no marker is left.
- [minor] research D-12 vs T049 vs T010 – Signatures differ:
  - D-12 has `ChangeGuard.admit(tool, nodeId, Area, actionEntries)`, while T049 has `admit(tool, nodeId, ActionSpec)` with no call entries. Step 5 needs `entries(input)`.
  - `VersionGate.admitCall` "runs the existing capability admission", which takes a `Capability`, but `ActionSpec` (data-model, T010) has no capability field.

  Expected change: give `ChangeGuard.admit` the call entries or the input, and add `capability` to `ActionSpec`, or state that it is derived from the tool.
- [minor] quickstart increment 5 / T067 – The quickstart lists `AdministrationConfigTest`, but T067 creates only the existing config tests. Also, the plan's Project Structure says `MetadataParser`, while T040 creates `ProcessMetadataParser` and `IsConnectorCheckParser`, and `UserImportFileParser` (T095) is missing there. – Align the names.
- [minor] T011 – The list of port tasks that add a capability includes T082, which adds none (T082 says `OBJECT_ADMINISTRATION` from T081 covers it). – Drop T082 from the list.

VERDICT: CHANGES_REQUESTED

Files reviewed (relative to the repository root):
- specs/022-full-interface-coverage/spec.md, plan.md, research.md, data-model.md, tasks.md, quickstart.md
- specs/022-full-interface-coverage/contracts/coverage-list.md, contracts/configuration-delta.md, contracts/mcp-tools-delta.md (first 200 lines)
- specs/022-full-interface-coverage/reviews/plan-r2.md; plan-r1.md was skipped (its read came back truncated and r2 confirms all its findings fixed)
- .specify/memory/constitution.md (section headings and version 1.1.0 only)

Code checked that the tasks rely on, under src/main/java/de/dadecker/inubit/mcp/: `ToolHints`, `WriteLock`, `ProcessQueryPort`, `MonitoringPort`, `ItemBounds`, `TargetFolderRules`, `ResultLimiter`, `HealthService`, `ArchiveFolderWriter` and `Wiring` exist. `application/VersionGate.java` admits through a `Capability` (`admit(NodeId, Capability, …)` and `admit(String tool, NodeId, Capability, …)`), which is the basis of the second minor finding.
