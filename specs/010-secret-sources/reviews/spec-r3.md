SPEC: specs/010-secret-sources/spec.md  ROUND: 3

All round-2 findings fixed. Majors resolved: FR-010 bounds failed logins to at most two per node per 60 s, simultaneous rejections share one re-read, an e2e rejection triggers no re-read; FR-004 and the edge case say group-level parameter names do not apply to a node with its own source override. Minors fixed: FR-009 defines "unavailable", FR-010 defines "differs", FR-011 counts a value failing validation as a failed fetch, FR-008 adds a source-specific next step, SC-005 gives a 50 ms tolerance. No `[NEEDS CLARIFICATION]` markers, no constitution conflict. Secret kinds checked against `config/CredentialVariables.java`: complete.

FINDINGS:
- [minor] FR-007 vs FR-010 – FR-007 requires "the same lockout protection" while FR-010 raises the limit to two failed logins per 60 s. – FR-007: "...except for the single extra login after a changed re-read that FR-010 allows for re-readable sources."
- [minor] FR-009 – "required parameter missing, denied or invalid" does not cover optional kinds with a configured name (FR-006 treats a missing configured parameter as an error at run time). – Use "configured parameter", or say a missing optional parameter is only a warning.
- [minor] Edge case trust store / e2e – Today e2e credentials count only if both variables are set (`CredentialResolver.e2e`); for Parameter Store, only one of the two e2e names configured is undefined. – Proposed: startup configuration error naming the node and the missing name.
- [minor] FR-006a / FR-011 – Not stated whether the separate e2e fetch follows the same rules (lifetime, 30 s back-off, shared fetch, FR-013 logging). – Add that it follows FR-011 and FR-013.
- [minor] FR-010 – A failed re-read: keep held values? start the 30 s back-off? – Proposed: keeps the held values, logged as a failed fetch, leads to the 60 s pause, no additional back-off.
- [minor] FR-013 – Name re-reads after a rejected login explicitly, with whether the value changed (never the value).
- [minor] FR-004 – Parameter names configured on a group/node whose effective source is environment variables (left-overs). – Proposed: warning in `--check-config` and at startup.
- [minor] FR-004 – Allowed form of a "full parameter name" unspecified (leading `/`, `name:3`, `name:label`, ARNs). – Proposed: plain hierarchical names only, validated at startup; version, label and ARN forms out of scope.
- [minor] FR-009 / US2 AS3 – Exit code when the only finding is the `String`-type warning not explicit. – "Warnings do not change the exit code."

VERDICT: APPROVED
