SPEC: specs/018-partner-management-read/spec.md  ROUND: 3

Round 2 is fully addressed (audit for cache hits with `fromCache` in FR-020 and Operational scenario 3; failed servers FR-014; filters in a comparison FR-005a; masked fields never a match key FR-013; story 4 without the capability FR-015; no REST export on disk FR-010; `apikey`, `accesskey` in FR-008). No [NEEDS CLARIFICATION] markers. Nothing conflicts with the constitution (II: FR-008 to FR-012b; IV: FR-001, SC-006; V: FR-001a, FR-016; VI: FR-017, FR-019; VII: FR-020 does more than required).

Checked against the codebase: every error kind of FR-019 exists in ErrorCode.java; the inventory cache is in memory (FR-012b holds); default inventory.cacheTtl is PT10M (Operational scenario 3 consistent).

FINDINGS:
- [minor] SC-002 vs FR-018 / FR-012b – SC-002 says no derived values other than the equality statement appear in the cache, but FR-018 allows keyed fingerprints there. – "… zero secret values or derived values appear in results, errors, logs or audit records (except the equality statement of FR-012a), the cache holds at most the keyed fingerprints of FR-012b, and no unkeyed hash of a secret exists anywhere."
- [minor] FR-020 / Operational scenario 4 – Unclear when only one of several audit records of a call fails. – "If any audit record of the call cannot be written, the whole call MUST end in `INTERNAL` and return no partner data for any server."
- [minor] FR-018 / FR-003 – A read filtered by `systemDiagram` may fill the per-server cache incompletely. – "A cache entry records which system diagrams it covers; a call is answered from the cache only if the entry covers every system diagram the call needs."
- [minor] FR-019 – The list of error kinds reads as complete but leaves out `UNREACHABLE`, `TLS_ERROR`, `FORBIDDEN`, `MAINTENANCE_MODE`. – "… with the existing error kinds, including …", or add them.
- [minor] FR-005a / FR-013 / FR-015 – Workflows are identified by name only; two technical workflows of the same name in different diagram groups are not addressed. – Say whether `ReferencedTWF` / `ReferencableTWFs` identify a workflow by name or by group and name; the plan's research may confirm it from a recorded export.

VERDICT: APPROVED
