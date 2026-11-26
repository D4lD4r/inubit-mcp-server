FEATURE: specs/025-inubit-74-support  ROUND: 2 (Mandate B replan, T030–T043)

Round-1 findings: all 4 majors and 9 minors fixed (M1 decided via ContactedException but its boundary is wrong, see new major; M2 carrier, M3 get_health fail-closed client check, M4 ConfigSummary.cliOnly fixed; all minors fixed).

COVERAGE (Mandate B): FR-006 → T035; FR-007 → T035, T038; FR-009 → T035; FR-010 → T038, T035; FR-010a → T035; FR-011 → T031, T032; FR-014 → T033, T040; FR-015 → T040; FR-020 → T035, T037, T043; FR-021–023, FR-032/033 withdrawn; FR-024/025 → T031, T032; FR-026/027 → T031; FR-028 → T032; FR-029 → T033, T031; FR-030 → T038, T041, T035; FR-031 → T037; FR-034 → T036; FR-035 → T039, T035; FR-036 → T038, T043, T035; FR-037 → T042. US6, US7, US9 covered; SC-005 → T035; SC-007 → T031, T032, T041, T037; SC-009 → T040; SC-010 → T042, T043. No scope creep.

CROSS-CHECK mismatches: pre-contact list in contract/FR-030 (b) vs data-model ContactedException ("permit taken = contact began") (major); version note "every 7.4 entry" vs only admitted entries (minor); configuration-delta "after the flip T035" vs T033 setting the production flag (minor); client-check row lacks "or cannot be read" (minor).

FINDINGS:
- [major] T041 / plan "warning scope" / data-model ContactedException – inside the permit, V61Connection.session calls CliRunner.runSession, which refuses before launching: checkLaunch (UNSUPPORTED_VERSION when the client range excludes the version, CLI_UNAVAILABLE when the client changed), the CredentialGuard cooldown (AUTH_FAILED, no login attempted), CLI_UNAVAILABLE when StartCLI cannot be started. On http:// nodes no contact comes before these. Set the marker where contact actually starts (e.g. checkLaunch before the permit; mark only TlsHandshakeCheck.verify errors and errors from the started process) or state explicitly that these carry the warning; add V61ConnectionTest cases for launch-guard refusal, cooldown and start failure; fix the data-model definition.
- [minor] T041 HealthService – await/Outcome keep only the ToolError; Outcome must carry the marker. "session-budget TIMEOUT comes after the launch" is wrong (budget can run out waiting for a slot; result matches FR-030 (b), fix wording). State whether the get_health fan-out backstop TIMEOUT (:246-254) gets the warning or why it is unreachable.
- [minor] T041 – SecretTiming.explain rebuilds the find_processes TIMEOUT NodeResult; say whether the deadline warnings survive and test it.
- [minor] T041 tests – add the FR-030 (b) case: FanOut deadline TIMEOUT of an admitted node still waiting for a slot carries the warning (distinct from the limiter's no-slot TIMEOUT, which carries none).
- [minor] T041 / T033 size – split T041 into marker, carrier, services.
- [minor] T033 / Dependencies / configuration-delta – T033 sets the own-client flag on the production 7.4 entry before the flip; set it in T035 (T033 tests with a test matrix) or reword; name how EffectiveNodeConfig.choose reads the flag and whether choosesCliByVersion (:353) is true for 7.4.
- [minor] contract / T043 version note wording – only admitted entries carry it; add "or cannot be read" to the client-check row.
- [minor] Dependencies – T031 and T033 both edit HealthService, HealthServiceTest, VersionGate; order them (T031 → T033).

VERDICT: CHANGES_REQUESTED
