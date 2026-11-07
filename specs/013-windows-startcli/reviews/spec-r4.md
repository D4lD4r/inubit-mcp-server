SPEC: specs/013-windows-startcli/spec.md  ROUND: 4

Round 3 status: all eight round-3 findings are fixed (sequencing via the Clarifications defaults and FR-003/FR-009/FR-010/FR-013; FR-015 lists `\`; SC-002, FR-001, FR-007, FR-005, FR-011 and US2 AS5 updated). The three Clarifications were not reopened. This round finds 1 new major and 6 minors.

FINDINGS:
- [major] FR-007 (existing directories) / US3 AS4 – the server cannot tell which existing workspace it created: `WorkspaceDirectory.prepare` reports `created` only for the start that creates it; earlier versions created it on Windows with `Files.createDirectories`, giving the same inherited access as a user-created directory. A configured audit directory has the same problem; only the default audit directory can be recognised by its path. – Decide: (a) record created directories and warn only about recorded ones (earlier versions' directories used as is), or (b) check only the default audit directory and the files at fixed paths (audit log, ledger, lock), never an existing workspace. Say whether the workspace history is checked for a user-provided workspace.
- [minor] Edge Cases (Password prompt) – wrong cross-reference "US4 AS5"; it is US4 AS6 (FR-005).
- [minor] US4 AS1/AS2 vs FR-005 and SC-004 – "ended within the grace period" vs forced termination after the grace period and SC-004's "one second after". – Reword to "no later than one second after the grace period (SC-004)".
- [minor] FR-002 third bullet / SC-002 – unclear that the "unchanged" rule covers only values passed outside the command; SC-002 would test `'`, `(`, `\`, `*`, `?` inside the command, where they are refused today. – Reword both.
- [minor] Edge Cases (UNC) / FR-002 / FR-010 – a UNC client installation as working directory of the command interpreter is not accepted (it warns and continues in the Windows directory). – Decide: refuse with `CLI_UNAVAILABLE` naming `cli.home` and a `--check-config` warning, or require it to work with another working directory.
- [minor] US3 Independent Test – "access for the user only" conflicts with FR-007 (account and SYSTEM). – Reword.
- [minor] FR-011 configuration case and FR-004 "no other variable" have no acceptance scenario. – Add a US2 scenario; extend US2 AS4 with the allowlist.

VERDICT: CHANGES_REQUESTED
