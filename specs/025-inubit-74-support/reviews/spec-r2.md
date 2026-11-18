SPEC: specs/025-inubit-74-support/spec.md  ROUND: 2

All 7 major findings from round 1 are fixed:
- 404 assumption and the FR-032 trigger: fixed by an Assumption, and the trigger is now taken from the recordings.
- 7.4 nodes on `AUTO`: an Edge Case and an Open Question now state the behaviour.
- TLS: covered by FR-017, FR-029 and US4 scenario 8.
- Fixture coverage: the FR-014 table, FR-016a and the new recorder cases.
- `cliClients."7.4"` in Mandate A: FR-010a.
- Client order and the detection exception: FR-029, FR-032 and FR-033.

All round-1 minor findings are fixed too. No `[NEEDS CLARIFICATION]` markers are left. The findings below are new and all minor.

FINDINGS:
- [minor] FR-001 vs FR-020 / US5 / SC-005 – FR-001 changes the load error text that lists the allowed `versionLine` values (`ConfigLoader.java:807`, checked by `ConfigLoaderTest`) and the `list_nodes` output schema descriptions (`list_nodes.output.json:63,117`). These texts change for every profile, but US5 says "the only visible change is the extended 404 hint" and SC-005 allows only the FR-005 hint tests to be adjusted. – Proposed wording for FR-020, US5 and SC-005: "apart from the extended hint of FR-005 and the lists of allowed `versionLine` values (load error text, `list_nodes` schema descriptions, docs) that now name `V7_4`".
- [minor] FR-016 / FR-017 – The password rule ("standard input only") is stated only for the interactive-session mode. `v74_exec_help`, `v74_exec_ps_help` and `v74_direct_health` also log in. The 7.4 help shows `-p` and `--encryptString`. – Proposed: append to FR-017 "Every 7.4 case that logs in passes the password on standard input only (as the feature 013 script-mode cases do), never through `-p` or an `--encryptString` value; the secret check applies to every such case." Make the Edge Case exact: "reads the password from the console instead of standard input".
- [minor] US1 Independent Test / SC-003 vs FR-010a – US1's Independent Test lists `--check-config` and asserts "no … StartCLI run", but FR-010a runs the local version probe. – Reword to "no REST request, StartCLI run against the node or TLS handshake reaches the node (the local probe of FR-010a excepted)" and add an acceptance scenario for FR-010a.
- [minor] FR-014 table – `ps_session_unassigned` of the 6.1 set is neither in the 7.4 table nor explained. – Add it or state that it is left out on purpose.
- [minor] FR-014 / FR-030 – `help` counts as recorded, but its exit code and stderr are assumed and it is not tied to 7.4.0.90. – Add to FR-030: "… and the `help` case has been replaced by the `v74_help` recording."
- [minor] FR-015 – "neither listed nor known as recorded" does not say where recorded cases are listed. – Name the single source.
- [minor] FR-029 (Mandate B) – A group `cli.home` pointing at the 8.1 client would be used for a 7.4 node in a mixed group; the range check refuses only if the client declares a range. – Question for the Mandate B update: must the chosen client's `-v` report 7.4 (major.minor), with `CLI_UNAVAILABLE` otherwise?
- [minor] US6 scenario 4 – "whose StartCLI reports another major.minor" does not say client (`-v`) or server (`-sv`). – Use the server version read through StartCLI.
- [minor] SC-007 vs FR-028 – SC-007 sets a time limit for `find_processes`, FR-028 has none. – Add the 6.1 budget to FR-028 or drop it from SC-007.
- [minor] FR-016 `v74_login_failed` – A wrong-password login can count towards account lockout. – Use a dummy user name that does not exist, or a user the owner names for this purpose.

VERDICT: APPROVED

Checked against: InubitVersion.java, ClientVersion.java, VersionGate.java:1004-1010, CliRunner.java:673-699, ConfigLoader.java:807, list_nodes.output.json, fixtures/v6_1/cli/, tools/record-fixtures.ps1, specs/012 FR-012/FR-012a, the constitution, the attached 7.4 help output. Existing tests using 7.x values (CapabilityMatrixTest, AdapterGatewayFactoryTest, CapabilityExtensionTest) use 7.0, 7.8 and 7.9 as out-of-range examples, so a 7.4–7.4 range does not affect them.
