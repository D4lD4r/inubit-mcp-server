SPEC: specs/023-test-home-isolation/spec.md  ROUND: 1
FINDINGS:
- [major] US4 / FR-006 – Live tests' audit directory, workspace and knowledge directory come from the real configuration loaded with the real home (LiveTarget.java:202-286); a temporary home for the composition root alone does not satisfy US4. Proposed: every location the live tests write (backups, ledger, packages, audit, workspace, knowledge) lies below a temporary directory, unless the audit log is exempted on purpose.
- [major] US1 scenario 2 / FR-001 / Background – The export folder rules do not write state; they use <home>/.inubit-mcp as a forbidden export root (TargetFolderRules.java:95), not per-profile. Reword FR-001 and split scenario 2 (exports below <temp home>/.inubit-mcp are refused). "Server-log export" exists only on the 021 branch.
- [major] FR-007 / US2 / SC-003 – "Reads the process user home" undefined: static scan vs runtime check, which constructs count (user.home, HOME, USERPROFILE), string constants excluded, test-support code outside the guard. Align US2 scenarios 1 and 3.
- [major] US3 / FR-009 / SC-005 – No reference list for "exactly where it was before"; US3 independent test not runnable without a node. List expected paths for home H and profile P.
- [minor] FR-003 – Name the single allowed place: Launcher.Context.system, not Main.
- [minor] SC-001 / Edge Cases – The developer's own running inubit-mcp server also writes under ~/.inubit-mcp.
- [minor] FR-005 – Lifecycle of the default temporary home not stated.
- [minor] FR-010 – One-time cleanup, not checkable by the build; move to a follow-up note, confirm no real profile named acme.
- [minor] FR-002 – Double negative.
- [minor] Scope – No Out of Scope section (child processes inheriting HOME, ~/.config/inubit-mcp reads, test config dir under java.io.tmpdir).
VERDICT: CHANGES_REQUESTED
