SPEC: specs/009-inubit-version-detection/spec.md  ROUND: 3

PREVIOUS FINDINGS (round 2):
- [major] Client range check / newer-patch server / unevaluable range / inclusiveness – RESOLVED (clarification, FR-011 major.minor with inclusive bounds and ignored suffixes, edge cases, Assumptions); the unevaluable-case definition creates F1.
- [major] FR-002 vs the health budget of feature 001 – RESOLVED.
- [major] `--check-config` and the client probe – RESOLVED (FR-011a).
- [minor] US4-1 / Independent Test vs FR-001 – RESOLVED.
- [minor] Concrete pin optional – RESOLVED (MUST in FR-012, clarification, Key Entities).
- [minor] Lifetime of a pin contradiction – RESOLVED (FR-012).
- [minor] FR-010 404 rule vs edge case – RESOLVED.
- [minor] FR-014a unknown source node – RESOLVED.
- [minor] `get_inventory_item` row – RESOLVED.

FINDINGS:
- [major] F1: FR-011 / FR-002 vs SC-003 / Assumptions – A failed client probe is an unannounced 8.1 behaviour change with undefined lifetime: FR-011 refuses StartCLI-backed writes when the range "cannot be evaluated (... probe failed, no range printed)"; today no probe runs; the probe can fail on a healthy 8.1 node (`CliVersionProbe` throws TIMEOUT when the JVM start exceeds `cliTimeout`; supportedFrom/supportedTo are Optional). Every StartCLI write would then be refused, not listed among the deliberate changes. FR-002 keeps the result "for the lifetime of the process" and only describes a retry for the get_health timeout. Error code for a failed probe unspecified. – Questions: (1) should a failed probe / missing range block writes (then add to deliberate changes with remedy)? (2) retry on next call (proposed: not cached, at most once per 60 s)? (3) error code (proposed: CLI_UNAVAILABLE / TIMEOUT when StartCLI cannot run; UNSUPPORTED_VERSION when the probe succeeds without a range).
- [minor] F2: FR-014a last sentence contradicts itself ("A line pin (`V8_1`, `V9_X`) gives major.minor ...; `V9_X` gives no minor"). – "A pin `V8_1` or a concrete version gives major.minor ...; `V9_X` counts as unknown."
- [minor] F3: FR-011 match/mismatch on pinned nodes – which server version is compared (pin or observed)? With `V8_1` always "not comparable". – "the version observed by get_health if present, otherwise the pin".
- [minor] F4: US4-3 vs FR-007 / US2-5 – with every write-enabled node pinned `V9_X`, write tools are no longer announced. – "... no longer announced if no other node can run them, and refused otherwise".
- [minor] F5: Edge case "node upgraded" can be read as re-detecting on reads after the bound. – "re-detected before the next write if older than the bound or stale (FR-010); reads use the cached version".
- [minor] F6: FR-010 / FR-014a – which nodes deploy_release re-detects. – "Before execution, FR-010 applies to every source and target node".
- [minor] F7: FR-012 concrete pin format – invalid values / suffix. – "at least major.minor.patch (suffix allowed, ignored); anything else is a configuration error".
- [minor] F8: FR-005 / SC-005 – refusal for client incompatibility should name the client version and declared range.

VERDICT: CHANGES_REQUESTED

Notes: F1 checked against CliVersionProbe.java (Optional range; throws CLI_UNAVAILABLE or TIMEOUT). `run_e2e_test`'s own gate (E2eConfig) is covered by FR-003. No [NEEDS CLARIFICATION] markers; no constitution conflict.
