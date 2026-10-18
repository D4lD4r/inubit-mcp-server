SPEC: specs/009-inubit-version-detection/spec.md  ROUND: 4 (new loop after rounds 1-3)

All eight round-3 findings are resolved; no blockers, no majors, six minor findings.

PREVIOUS FINDINGS (round 3):
- [major] F1 failed client probe – RESOLVED (clarification 2026-10-18 round 3, FR-002, FR-011, edge case agree: warning, not cached, retried at most once per 60 s; error-code question moot; "no change for today's 8.1 setups" accurate).
- [minor] F2 FR-014a pin wording – RESOLVED (follow-up N3).
- [minor] F3 which server version FR-011 compares on pinned nodes – RESOLVED.
- [minor] F4 US4-3 vs FR-007 – RESOLVED.
- [minor] F5 "node upgraded" edge case – RESOLVED.
- [minor] F6 which nodes deploy_release re-detects – RESOLVED.
- [minor] F7 concrete pin format – RESOLVED.
- [minor] F8 client-caused refusals name client version and range – RESOLVED.

FINDINGS:
- [minor] N1: FR-004 / US1 – Whether `unverified` counts as "usable" is unstated (a 9.0 node with writes disabled: `partially supported` or `unsupported`?); no scenario lists a 9.0 node. – Proposed: "A capability is usable if its tools run on the node (`supported`, or `unverified` for reads, and not refused by the client check)"; add US1-5 for a 9.0 node with an 8.1 client (`partially supported`).
- [minor] N2: Tool table vs FR-011 – "per FR-011 (8.1 client: refused)" is only true for 9.0+; an unknown server version runs with a warning. – "9.0 or newer with an 8.1 client: refused; unknown server version: runs with a warning"; same for the "StartCLI part per FR-011" cells.
- [minor] N3: FR-011 server version used – may prefer an older get_health value over a newer re-detection; open whether a get_health observation updates an AUTO node's cache (today AdapterGatewayFactory.Slot.succeeded ignores it). – "the most recent observed version (get_health or detection); pin without observation; on AUTO a get_health observation replaces the cached version".
- [minor] N4: FR-011 with a `V9_X` pin – major only or unknown for the client range check? – Question; proposed: refused when the whole line lies outside the range.
- [minor] N5: FR-002 probe on the first StartCLI-backed call is unbounded outside get_health (up to one cliTimeout, default 30 s). – "Outside get_health bounded by cliTimeout, not counted against the tool's own timeout; a timed-out probe = range not evaluable".
- [minor] N6: No acceptance scenario for the round-3 rule (failed/timed-out probe → warning; retry after 60 s) or the get_health probe timeout. – Add two Given/When/Then scenarios.

VERDICT: APPROVED

Notes: no [NEEDS CLARIFICATION]; no constitution conflict; Context claims verified (detection is one authenticated GET /ibis/rest/system/info). Write tools reject group ids today, so FR-006 affects reads plus deploy_release. Implicit intended change: today read ports are handed out without waiting for detection, FR-001 makes the first call of any kind detect — keep in mind for planning.
