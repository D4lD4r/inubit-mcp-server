SPEC: specs/025-inubit-74-support/spec.md  ROUND: 2 (Mandate B replanning incl. INUBIT 6.1, 2026-11-26)

Round-1 status: 13 of 14 findings fully resolved, including the blocker and all three majors (provenance covered by ratified constitution 2.1.0 Principle III; warning scope by FR-030 (b) and SC-007; failure scenarios by US6 7–10 and US7 7; German variants built in tests by FR-025). Resolved minors: mixed group (US6 11, US7 6), list_nodes (US6 13), `version` case in FR-014/FR-015, fail-closed `-v`, SC-009, help output accepted as 7.4, 6.1 vs 7.4 (US9, FR-036, FR-037), TLS (US6 12), FR-005/FR-006. Partly resolved: implementation detail (one class name left).

FINDINGS:
- [minor] US7 scenario 3 – class name `V61QueryRules` in an acceptance scenario; write "(as on 6.1, feature 012)".
- [minor] FR-030 (b) vs. Edge Cases (`-v`) vs. constitution V – "need not" leaves pre-contact refusals untestable; Edge Cases says "carries no unverified warning"; CLI_UNAVAILABLE passes the version check. Wording: "…refusals decided before any contact (…) do not carry it: they return no output of that version, so they are not results of the capability in the sense of constitution V".
- [minor] US6 scenario 12 – mixes TLS_ERROR after contact (warning) and a refused host-name-verification skip before contact (no warning); split into two scenarios.
- [minor] US9 scenario 1 – warning text for a concrete pin should use the full raw version (`INUBIT 6.1.0.89 on …`), as US6 scenario 1 and `VersionGate.name` do.
- [minor] FR-014 / FR-015 – `version` listed only for monitoring though the client check guards both tools; same for `login_failed` and `unreachable`. List shared cases per capability ("one line per case and capability", as in 6.1)?
- [minor] FR-035 (4th bullet) vs. FR-029 – does the 7.4 `-h` probe decide the client's TLS options, or are they fixed (neither option) and the probe only checks the client starts? State one.
- [minor] FR-034 – two more README passages become false for 6.1 (announcement table "none of them is offered, e.g., in a profile of INUBIT 6.1 nodes only"; "except on INUBIT 6.1, where every tool not declared supported for 6.1 is refused"); name them generically.
- [minor] SC-005 / US5 – no criterion lists the existing tests Mandate B changes on purpose (feature 012 6.1 refusal tests, 6.1-only profile not offering find_processes, 6.1 list_nodes status, Mandate A 7.4 refusal tests and "client not used" note); the US5 Mandate B note breaks the numbered list, move it after scenario 3.
- [minor] Open Questions – blank line missing before `### Decided`.

VERDICT: APPROVED

No blocker or major findings. No conflict with constitution 2.1.0 (III and V on the unverified level, plus I, II, IV); no `[NEEDS CLARIFICATION]` marker left.
