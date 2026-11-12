SPEC: specs/024-unsupported-node-tools/spec.md  ROUND: 1
FINDINGS:
- [major] No FR names the tools, inputs or count (021 FR-001 convention, Constitution IV).
- [major] FR-030: the three data source location names not listed; possible credentials in a location.
- [major] FR-030 vs US3 AS4: 9.0-only names vs "not present" example contradict.
- [major] US4 AS5 vs FR-041: "no duration" refused vs default 10.
- [major] FR-042/AS6: no configured INUBIT node name setting exists; first set has nothing to compare; node check and trace command may hit different nodes.
- [major] FR-044: reset tool guards/confirmation/annotation unspecified; no refusal scenario.
- [major] FR-043: cleanup resets vs read-only mode / missing opt-in / missing node or credentials undefined.
- [major] FR-043/AS3: shutdown reset unbounded.
- [major] FR-043: concurrent instances per profile (orphaned vs live lease, serialization) undefined.
- [major] FR-045/AS2: "a tool or field" for leases undecided; visibility in read-only mode; retention.
- [minor] RESET_FAILED retry after 24 h vs SC-004; audit of every automatic reset; merge of two sets; AS1 confirmation mode and expiry start; FR-010 raw status and absent fields; US3 AS1 category; US3 addressed server only; FR-020/021 references beyond 500; US2 AS4 cluster hint; FR-011 masking of details; FR-002 business/personal data warning; "state directory" wording; US3 AS5 "nothing is sent".
VERDICT: CHANGES_REQUESTED
