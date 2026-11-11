SPEC: specs/020-queue-entry-read/spec.md  ROUND: 3
All round-2 findings fixed. No NEEDS CLARIFICATION. No constitution conflict.
FINDINGS:
- [minor] FR-019 vs SC-001 / US3-2 / FR-002 – completeness wording vs fallback; under fallback 9.0 counts as unsupported line for FR-002.
- [minor] FR-011 – secret-named start tag without end tag (cut-off stream) could leak; mask to next `<` or end; self-closing has nothing to mask.
- [minor] FR-011 – quoted name=value (`pwd="a b"`) and header-style `name: value` lines not covered.
- [minor] FR-009/FR-011 – no upper limit on the stream size fetched and processed.
- [minor] FR-010 – ~140,000 ignores names and masking expansion; before/after masking unclear.
- [minor] FR-013 – list presented as complete but misses TARGET_UNKNOWN, SECRET_UNRESOLVED, MAINTENANCE_MODE.
VERDICT: APPROVED
