FEATURE: specs/025-inubit-74-support  ROUND: 3 (Mandate B replan, T030–T045)

Verdict: APPROVED — no blocker or major. All 8 round-2 findings resolved, checked against the code (contact begins at process start / handshake; launch guard, cooldown, start failure, Windows credential refusal stay plain; Outcome carries the marker; SecretTiming keeps warnings; deadline vs no-slot TIMEOUT tested; T041 split into T041/T044/T045; ownClientOnly set in T035; contract wording; T031 → T033).

COVERAGE (Mandate B): FR-006 → T035; FR-007 → T038, T035; FR-009 → T035; FR-010 → T038, T035; FR-010a → T035; FR-011 → T031, T032; FR-014 → T033, T040; FR-015 → T040; FR-020 → T035, T037, T043; FR-021–023, FR-032/033 withdrawn; FR-024/025 → T031, T032; FR-026/027 → T031; FR-028 → T032; FR-029 → T033, T031; FR-030 → T038, T041, T044, T045, T035; FR-031 → T037; FR-034 → T036; FR-035 → T039, T035; FR-036 → T038, T043, T045, T035; FR-037 → T042. US6 1,2,4 → T031, T035; 3 → T033; 5 → T037; 6 → existing suite; 7–13 → T031, T045; 14 → T038, T035. US7 1–5 → T032, T035; 6,7 → T045. US9 1–3 → T043, T045; 4,6 → T035; 5 → T043. SC-005 → T035; SC-007 → T031, T032, T045, T037; SC-009 → T040; SC-010 → T042, T043, T045. No scope creep.

FINDINGS:
- [minor] T045 / HealthService :446-450 – the sessionTimeout rewrite must keep the original's marker (no-slot TIMEOUT stays plain); decide the credentials-await deadline TIMEOUT (:423-428): node admitted, FR-030 (b) says warn, T045 lists it without.
- [minor] T045 / HealthService.check :252-254 – the backstop failure uses failed(node, error) with empty warnings; name that it takes the NodeResult's warnings.
- [minor] T044 – FanOut.ServerCall has no way to reach a sink; name the API (e.g. a run overload with a sink, existing callers unchanged); NodeResult canonical constructor also used at InventoryService.java:595 (keep a 3-argument constructor or add the file).
- [minor] T041 / CliRunner.execute :863 – shared by local -v/-h probes; restrict marking to session runs (flag from runSession) or explain; name how execute learns the process started (e.g. wrap the StartHook ProcessRun :233 calls); the V61ConnectionTest "non-zero exit" case belongs to the adapter contract tests.
- [minor] T033 / CliRunner.noHome :1068-1072 – for an ownClientOnly entry the text must not suggest cliHome (FR-029); assert in CliRunnerTest.
- [minor] T033 size – split into client choice (flag, EffectiveNodeConfig, noHome/entryHint) and fail-closed check (VersionGate.checkClient, HealthService pre-session probe, NodeOverview.clientRefusal, version fixture, RECONSTRUCTED.txt).
- [minor] wording – plan constitution check rows I/V and tasks.md Mandate B intro (:378): add FR-030 (b)'s pre-contact exception; T043 version note "data or contacted error"; contract pre-contact list add the Windows credential refusal; tasks Prerequisites cite D-1…D-15, D-16 exists.

VERDICT: APPROVED
