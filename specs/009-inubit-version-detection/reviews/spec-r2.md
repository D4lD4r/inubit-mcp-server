SPEC: specs/009-inubit-version-detection/spec.md  ROUND: 2

PREVIOUS FINDINGS (round 1):
- [major] list_nodes contact / when versions are obtained – RESOLVED (clarification 2026-10-18, FR-004, US1-2, SC-001); new consequence on the get_health side (FR-002 below).
- [major] V9_X meaning changes – RESOLVED (FR-012, US4-3, SC-003).
- [major] Failed AUTO detection vs SC-003 – RESOLVED (deliberate change with remedy in SC-003 and Assumptions).
- [major] Detect before a write vs "nothing is sent" – RESOLVED (FR-005, FR-010, US2-1, SC-002).
- [major] Matrix states / status definitions / precedence – RESOLVED (FR-003, FR-004, FR-009).
- [major] Tool-to-capability mapping – RESOLVED (table covers all 16 tools); small gap below.
- [major] Pin bypass – RESOLVED as far as this spec can go (FR-012, US2-4; FR-005 no false pin); a pin is trusted until get_health runs (acceptable, minor on FR-012 below).
- [major] deploy_release vs per-node results – RESOLVED (FR-014a, FR-006); gap for unknown source nodes below.
- [major] US2 Independent Test – RESOLVED.
- [minor] Context StartCLI bullet – RESOLVED.
- [minor] FR-010 triggers / bound – RESOLVED (imprecision below).
- [minor] FR-007 list-changed – RESOLVED.
- [minor] FR-011 mismatch definition – RESOLVED (creates the conflict below).
- [minor] Version format edge case – RESOLVED.
- [minor] Line pin in the same-minor check – RESOLVED.

FINDINGS:
- [major] FR-011 / FR-002 / SC-003 / Assumptions – Client range check not fully defined and an unannounced 8.1 behaviour change: (a) clients declare an upper bound equal to their own version (8.1.17: "from 4.0.1 to 8.1.17"), so a server patched to 8.1.18 with an 8.1.17 client refuses every StartCLI-backed tool, which runs today; not listed as a deliberate change. (b) No rule when the range cannot be evaluated (server version unknown, probe failed/unparsable, no range; CliVersion bounds are Optional). (c) Inclusiveness and comparison of versions without patch or with suffix unstated. – Question: refuse a newer-patch server? If yes, add to deliberate changes with remedy; if no, check major.minor only. Proposed wording for unevaluable ranges and bounds.
- [major] FR-002 vs feature 001 SC-002 – get_health running `startcli.sh -v` (one JVM per node, bounded by cliTimeout) can break 001 SC-002 (5 s per environment, 10 s for up to 10 servers). – Question: must the probe stay within the health budget? Proposed: run in parallel, bounded by the budget, report "not yet detected (timeout)".
- [major] FR-011 / FR-012 – `--check-config` validates offline (006 spec:38) and cannot know a detected server version; unclear whether it runs `startcli.sh -v`. – Proposed: no INUBIT contact; runs the local client probe and shows client version and range; compares only against a concrete pin, otherwise "server version: determined at runtime".
- [minor] US4-1 / Independent Test vs FR-001 – "no detection request" vs get_health still reading the version on a pinned node. – Proposed: "no request beyond what the tool sends today".
- [minor] FR-012 – "a concrete pin MAY be added" while Assumptions and FR-011 rely on it. – Question: in scope? MUST, or drop from Assumptions.
- [minor] FR-012 / US2-4 – Lifetime of a pin contradiction undefined (correction needs a restart; later matching observation; other responses). – Proposed: held in memory until a later get_health observation matches or the process restarts; only get_health counts.
- [minor] FR-010 vs Edge Cases – "HTTP 404 on an expected endpoint" also fires on ordinary NOT_FOUND; edge case says "triggers re-detection" vs FR-010 "marks stale". – Proposed: 404 not mapped to NOT_FOUND for a requested resource, 406, or UNEXPECTED_RESPONSE marks stale; next write re-detects; align edge case.
- [minor] FR-014a vs Edge Cases – Unknown source node not covered. – Proposed: "If any source or target node is unknown, or any target node is not write-capable, ...".
- [minor] Tool table – `get_inventory_item` only "(history)"; inputs DIAGRAM and MODULE. – State whether every call loads the CLI history or add a REST-only row.

VERDICT: CHANGES_REQUESTED

Notes: Context claims and defaults verified (AdapterGatewayFactory FAILED_DETECTION_TTL 60 s, V9_X on 8.1 adapters; Defaults BUILTIN_INVENTORY_CACHE_TTL 10 min; ErrorCode UNSUPPORTED_VERSION and CLI_UNAVAILABLE; CliVersionProbe optional bounds). No constitution conflicts; no [NEEDS CLARIFICATION] markers remain.
