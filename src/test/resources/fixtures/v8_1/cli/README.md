# StartCLI fixtures

Recorded and synthetic StartCLI runs of INUBIT 8.1.17, replayed offline by `FakeProcessLauncher`
(one case = `<case>.stdout`, `<case>.stderr`, `<case>.exit`). The feature-001 to feature-003 cases
(`ps_*`, `kill_*`, `processErrorStart_*`, `export_*`, `version`, `login_failed`, `unreachable`,
`unknown_command`) are described in their specs and, for the synthetic ones, in their
`<case>.README`. This file documents the cases of feature 004 (research D-22, D-24, D-25; tasks
T001); `ImportFixturesTest` checks their shape.

## Sources and neutralization

The recordings come from the spike of 2026-10-06 on a development stage
(`~/.inubit-mcp/<profile>/spike`, private, never committed). The spike scripts captured stdout and
stderr of each run in one file with a trailing `exit=<n> seconds=<s>` line; they were split like
the earlier recordings: the JVM line `Picked up JAVA_TOOL_OPTIONS: …` goes to `.stderr`, everything
else to `.stdout`, the exit code to `.exit`. The import protocol keeps INUBIT's `\r\n` line ends and
column padding byte for byte.

Neutralized (with `tools/neutralize.py`, the local maps and a local one-off map; none of them is
committed):

- the person who ran the spike → user `jdoe` (`Doe, John`, `jdoe@example.test`); the initials in
  the person's check-in comment → `JD`;
- the shared user-group owner → `OWNERS`;
- the server host → `inubit-dev-1.example.test`.

Kept: the test names of the spike (`SPIKE`, `SPIKE_Roundtrip_Claude`, `SPIKE_C_…` modules) — they
are neutral names chosen for the spike —, the line layout, timestamps of the error lines and the
Java stack traces.

## Import (`import --importFile '<zip>' … --returnProtocol`)

| Case | Kind | What it is | Source |
|---|---|---|---|
| `import_created` | recorded | `--importWorkflow --importUser 'jdoe'`: a new workflow with 4 new modules; five `was created.` rows, `Total: 5`, exit 0 | `claude/import-create.txt` |
| `import_modified` | recorded | the same archive again: five `was modified.` rows (every import makes a new version) | `t6/import.txt` |
| `import_created_and_modified` | recorded | structural change: one new module (`SPIKE_C_Assign-03` `was created.`), the workflow and four modules `was modified.`, `Total: 6` | `t7/import.txt` |
| `import_module_only` | recorded | `--importModule --importUser 'jdoe'` with a module-only archive: one `Module […] was modified.` row whose `DIAGRAM/MODULE` column is `/<module>` (no workflow) | `t8/import.txt` |
| `import_workflow_only` | recorded | `--importWorkflow --importUser 'jdoe'` with `import_workflow_only.zip` (D-24): one `Diagram […] was modified.` row; referenced modules got no new version | `p4/import-a.txt` |
| `import_workflow_only.zip` | recorded | the archive of that probe: `archive.properties`, `workflow/workflow.xml` with only the one workflow, an **empty** module index `module/module.xml`; no module files, no `Repository.zip`. INUBIT accepted it (D-24) | `p4/a.zip` (built by the spike script from an export) |
| `import_comment_history.xml` | recorded | `versionHistory.xml` excerpt (versions 13–19 of `SPIKE_Roundtrip_Claude`) of the check-in comment probes (D-11, D-24), see below | `p4/after-c2.zip` |
| `import_nok` | **synthetic** | an `n-NOK` result line (`1-NOK: Import failed.`) and exit 1, no protocol. StartCLI's failure lines follow `<n>-NOK`/`ERROR …`; the exact text of a failed import was not recorded (no failing import was provoked) | written for the tests |
| `import_protocol_mismatch` | **synthetic** | `import_modified` with one extra row `Module [SPIKE_C_Assign-09] was modified.` (an artifact that is not in the archive), `Total: 6`; column widths kept | derived from `import_modified` |
| `import_timeout` | **synthetic** | what is left of a run that was stopped on timeout: the preamble only, no protocol; exit 143 is what `FakeProcessLauncher` reports for a destroyed process. Replay it with `hanging()` so that the runner's timeout fires | written for the tests |

The import was run with `--importUser` only; `--importUserGroup` (a user-group owner) was not
probed, which is why feature 004 refuses writes for user-group owners (D-25).

### Check-in comment probes (`import_comment_history.xml`)

All on the workflow-only archive shape, `--importWorkflow --importUser 'jdoe'`:

| Version | `CheckinComment` in the imported archive | Stored by INUBIT |
|---|---|---|
| 13 | — (published in the Workbench) | `JD: Spike` |
| 14 | an export's comment (`JD: Spike###…@@@Deploying User: …@@@Version: 13@@@…@@@`) | `DefaultCommitCommentImport###` + the archive's comment |
| 15, 16 | the export comment of version 14, with `--importWorkflowActive` and then `--importWorkflowInactive` (D-15, D-24) | as 14; `IsActive` followed the flag (the export after version 16 shows `false`; the StartCLI output of these two runs was not kept) |
| 17 | a free text (`REASON-PROBE layout`) | `DefaultCommitCommentImport###` — the text is dropped |
| 18 | a `###` segment without the `@@@` suffix | `DefaultCommitCommentImport###` — dropped |
| 19 | the export shape `DefaultCommitCommentImport###REASON-PROBE three###@@@Deploying User: jdoe@@@Server: x@@@Version: 1@@@Export/Deployment: 06.10.2026 20:00:00@@@` | kept as written (the person-written segment survives) |

## Tag (`tag --tagMove|--tagDelete '<tag>' … --tagUser '<owner>'`)

| Case | Kind | What it is | Source |
|---|---|---|---|
| `tag_ok` | recorded | a successful `tag --tagMove '<tag>' … --tagUser 'jdoe'` of the spike's tag probe (the recording keeps the output, not the options of that run): preamble, one empty line, exit 0 — StartCLI prints **no** result line for `tag` | `t9/tag.txt` |
| `tag_delete_ok` | **synthetic** | `tag --tagDelete '<tag>' --tagUser 'jdoe'`: the same shape as `tag_ok`. The spike removed a tag this way (spike §6), but its output was not kept; the silent shape of `tag --tagMove` is assumed | derived from `tag_ok` |

Because `tag` prints nothing, the result of a tag call can only be verified by a history export
(D-16).

## Finger (`finger '<name>'`)

| Case | Kind | What it is | Source |
|---|---|---|---|
| `finger_user` | recorded | a user: `Login: jdoe       Name: Doe, John`, `Email: jdoe@example.test`, `Role: …`, exit 0 | `finger-<user>.txt` |
| `finger_not_registered` | recorded | the shared user-group owner: `The user or group "OWNERS" is not registered in the INUBIT Process Engine.`, stack trace, exit 1. An unknown name gives the identical answer, so `finger` cannot tell a user group from a typo; feature 004 does not use it (D-21, D-25) | `finger-<group owner>.txt` |

## REST: `../rest/user_users.{http,xml}`

`GET /ibis/rest/user/users?type=processEngineUser` (read-only, recorded once on the development
node on 2026-10-06 for D-21/D-25; `Accept: application/xml`): status 200,
`application/xml;charset=UTF-8`, one line, `ns2:UserList` with 58 `ns2:User` elements, each with
`id`, `email`, `type="ProcessEngineUser"` and, for 26 of them, `firstName` and `lastName`.

Every user entry was replaced by a fictitious one; element order, attribute set and attribute
order of each entry and the document structure are kept byte for byte otherwise. The person of the
spike is `jdoe` (`John`/`Doe`, `jdoe@example.test`); the other 57 entries are `User01`…`User57`
with `user<nn>@example.test` and, where the original had names, `First<nn>`/`Last<nn>`. The list
contains users only: the shared user-group owner (`OWNERS`) is not in it (D-21).
