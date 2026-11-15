FEATURE: specs/024-unsupported-node-tools  ROUND: 1
Coverage complete (every FR/US/SC/edge case mapped to tasks); code references checked against the codebase; no blockers.
FINDINGS:
- [major] D-16/D-14/T048/T050: two-instance race — a reset decided before another instance merges a set can end the lease while the level is raised; NodeLogLevelLocks is in-process only. Add a lease generation (end only if unchanged), optionally a cross-process per-node lock; two-instance and set-during-reset tests.
- [major] Constitution VII / Open question 2 / D-22: cleanup reset without a written PENDING audit record can leave no audit record (FR-043); row VII marked ✅ without Complexity Tracking. Choose fail-closed or record the deviation; test it.
- [major] contracts set/reset descriptions / T052: FR-003 sentence (unsupported, undocumented, no vendor support/stability guarantee, diagnosis only) missing; conflicts with D-25 docs test.
- [minor] open question 1 (orphaned unexpired leases) not recorded for US4-3; T006 vs T013/T014 create the same files in parallel; false parallel claims (T027∥T034, T026∥T033, T054∥T051/T052, T051/T052 unordered); LeaseStore wiring into HealthService missing; forget path guards without gate/lookup (T046); T049 lacks reset tests for US4-6/US4-7; T048/T050 too large (split cleanup vs scheduler); node details may exceed the 1 MiB helper stdout cap; small inconsistencies (NodeToolResults, LeaseSummary, D-26 case list, truncated field); reuse KnowledgeLock.
VERDICT: CHANGES_REQUESTED
