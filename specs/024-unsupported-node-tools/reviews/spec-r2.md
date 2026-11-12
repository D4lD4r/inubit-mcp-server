SPEC: specs/024-unsupported-node-tools/spec.md  ROUND: 2
All 10 round-1 majors fixed.
FINDINGS:
- [major] FR-043: only a starting instance takes over orphaned leases; with several running instances an expired lease of a crashed one waits; owner of merged leases undefined.
- [major] FR-042/043: automatic resets do not check the answering node.
- [major] FR-043/044/AS6: leases that can never end (server removed, other node answers).
- [major] Cleanup resets while write access is off deviate from Constitution I; the stated reason was wrong; needs owner decision and Complexity Tracking.
- [major] FR-001: target of read_io_log/get_node_config may be a stage id; fan-out undefined.
- [minor] AS for reset without lease; SC-004 for unreachable node; FR-007 node-answer command vs "cache operations" out of scope; FR-020 module filter match; owner identity by pid + start time; shutdown resets parallel with total bound.
VERDICT: CHANGES_REQUESTED
(Owner decision 2026-11-12: cleanup resets run also when write tools are off — recorded in Clarifications.)
