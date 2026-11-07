SPEC: specs/013-windows-startcli/spec.md  ROUND: 2

Round 1 status: all 9 majors are fixed. Two round-1 minors are only partly fixed (FR-011 scenario). This round has 4 new major findings: three come from the round-1 changes, and one (backslashes in the StartCLI command) is an unknown that round 1 missed.

FINDINGS:
- [major] FR-002 (second bullet) / US2 AS1 – "a configured username" is treated as a configuration value refused at startup. Since feature 011 the username is a credential (credential store or Parameter Store), read only at use time and in `--check-config` (011 research R-11/R-13, `EntryCheck`); a startup read would open store dialogs and put a credential-derived value into a configuration error. – Refuse such a username when it is read, before any process starts, with `AUTH_FAILED` naming the entry (never the value), not counting as a failed login, and report it in `--check-config`; check URL, trust-store path, client installation and Java runtime path at startup and in `--check-config`. Make US2 AS1 match.
- [major] Edge Cases (Paths) vs FR-002 / Out of Scope – "workspace" is listed among paths refused for non-ASCII characters, but FR-002 does not list it and the workspace is never passed to StartCLI; refusing it would change git and REST tools. – Remove it; if needed state "a workspace path with non-ASCII characters works as on macOS".
- [major] FR-009 / Context "Unknown on Windows" – `\` separators inside single-quoted `--execCommand` values are unverified (literal or escape?). – Add it to the unknowns, verify it in FR-014/FR-015, and reword FR-009 to "separators `\` or `/`, whichever the live check shows StartCLI accepts; the plan fixes one".
- [major] FR-007 / Key Entity – the rule covers only what "the server creates"; the temporary export archive is written by StartCLI, git writes into the workspace; "no inherited entries" may be read as non-inheritable entries, so child-created files would get default access; directory access alone does not protect them (bypass traverse checking). – Require that the access of a private directory is inherited by everything created below it, including files written by StartCLI or git; a test checks a file written by the substitute into the temporary export directory.
- [minor] FR-007 list / Key Entities – the empty git hooks directory (`GitCli`, `rwx------`) is missing; if others can write to it, git runs their hooks. – Add it and mark the list as examples.
- [minor] FR-007 – "as on macOS when owner-only permissions cannot be set" is misleading (workspace and audit directory fall back to unrestricted creation on non-POSIX file systems); existing files the server appends to are not covered. – Reword; extend the warning to existing private files.
- [minor] FR-002 / SC-002 – SC-002 does not list the characters; `(`, `)`, `;`, `,`, `=`, space, tab and `'` must reach StartCLI unchanged per FR-002, and `%1`-style forwarding splits on unquoted `=`, `;`, `,`. – List the full set and the expected outcome for each.
- [minor] FR-002 – a forbidden character in `cli.home` or the Java runtime path makes the whole server fail to start, while a missing `cli.home` is only a warning plus `CLI_UNAVAILABLE`. – Confirm, or make it node-level `CLI_UNAVAILABLE` naming the setting.
- [minor] FR-009 – "letters" is ambiguous. – Write "ASCII letters".
- [minor] FR-003 – the default stdin line ending before the live check is not stated. – State it (e.g. UTF-8 and `\n` until the live check shows otherwise).
- [minor] FR-011 – still no acceptance scenario; when the configuration case is reported is open. – Add a scenario; state when the configuration case is reported.
- [minor] SC-005 / FR-014 – whether each code page needs its own recording or recordings may be transcoded is open. – State which.
- [minor] FR-014 – "the argument forwarding of the shipped `startcli.bat`" can be read as committing the vendor file. – Clarify that the recorded artefact is the argument list the client receives for test values, not the file.
- [minor] FR-012 / Story 5 / Assumptions – if this feature lands first, Story 5 cannot be accepted here and this spec cannot bind feature 012. – State that Story 5 is then not part of this feature's acceptance and a task is added to feature 012's tasks.md.
- [minor] Out of Scope (last bullet) – can be read as putting FR-007 out of scope. – Reword.

VERDICT: CHANGES_REQUESTED
