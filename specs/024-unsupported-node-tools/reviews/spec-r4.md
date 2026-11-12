SPEC: specs/024-unsupported-node-tools/spec.md  ROUND: 4 (confirming)
Round-3 major and all minors resolved.
FINDINGS:
- [minor] FR-044: which leases `forget` may end. (Fixed: only `RESET_FAILED`, any reason; other states INVALID_INPUT.)
- [minor] FR-043/Edge Cases: retry end conditions miss the automatic FORGOTTEN end. (Fixed in both places.)
VERDICT: APPROVED
