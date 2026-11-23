# Handoff — IMC-17 · Executing (Blocked) · 2026-11-23 · run executor-1

## Status
Only blocked work is left. IMC-17 is `Executing` with flag `Blocked`, assignee APEX Executor
(claim read back), plan in comment 10052. Blocked by IMC-22 (recording session). IMC-21 Applied;
IMC-24 Answered (→ Applied when the spec MR of T029 is merged); IMC-23 done in the chat
(constitution 2.0.0 ratified 2026-11-23; the owner moves the card to Done).
Spec: `specs/025-inubit-74-support/` pinned `2bfe55e7`; this branch amends it for IMC-19
(`c99fe5c8`) and IMC-24 (`4f7c6d4a`, `a0b9dace`). Not yet reviewed with review-spec, not merged;
delivery step 1 (spec MR after T029) carries it. Task range T026–T037 (T030 `[X]` as a proposal).
Branch `claude/025-imc-17-mandate-b-b4dc46`, based on main `065cc4f2`, pushed; no PR yet.

## Done since last handoff
- IMC-21 answered (option A) → recording kit in `.apex/work/IMC-17/`: `ANLEITUNG.txt` (German,
  step by step), `record-v74.ps1` (run sheet, phases Check / Research / Session / Pack),
  `build-kit.py` (builds the ZIP with the recorder from `tools/`).
- T026: owner approved the recording in the chat; the session runs with the machine's owner.
- IMC-24 (owner, chat): recordings for evaluation only, raw, never in the repository →
  spec FR-014/015/019/021–023/025/030, SC-009, Preconditions, Decided; tasks T027–T029;
  quickstart. Kit writes raw results to `C:\imc17-results` (recorder `-OutputDirectory`) with a
  map that replaces nothing; ZIP `imc17-v74-results-RAW.zip`.
- Constitution 2.0.0 **ratified** by the owner in the chat (2026-11-23): Sync Impact Report removed,
  Last Amended 2026-11-23; plan.md Constitution Check, tasks T030 and spec (Preconditions,
  Decided) follow. Reaches main with the spec MR.
- T030 revised to constitution **2.0.0 (MAJOR)** on the owner's choice (`a0b9dace`): Principles
  III and V accept fixtures adjusted to evaluated recordings plus a passed live check where storing
  recordings is forbidden; 7.4 read-only, StartCLI only. Review round 2 APPROVED, two minors
  (`reviews/T030-r2.md`).

## Next step
Kit (built at c09fd5c7) sent by the owner to the colleague with the 7.4 installation on
2026-11-23; waiting for the session (IMC-22). On receiving the three Research
files: compare `v74_exec_help` / `v74_exec_ps_help` with the 7.4 case table in
`tools/record-fixtures.ps1` (T027); answer OK or send a corrected recorder (rebuild the kit). On
receiving `imc17-v74-results-RAW.zip`: keep it outside every repository (a folder under the
owner's home, not under `~/Development`), evaluate it, adjust the reconstructed fixtures with
neutral values only (T028, FR-022), run `NoCustomerIdentifiersTest`, commit, then ask the owner to
delete the raw ZIP. Then T029.

## Setup on the Windows machine (before `-Phase Check`)
- **Python 3.7 or newer** — unknown whether installed; the recorder always runs
  `tools\neutralize.py` (standard library only). If missing: python.org installer ("Install for
  current user", "Add python.exe to PATH") or the "Windows embeddable package" on PATH for the
  session; the Store placeholder does not count (`-Phase Check` detects it). Undo: uninstall via
  Apps or delete the unpacked folder.
- 7.4 Workbench client with its JDK: present (owner, 2026-11-23). No neutralize map (IMC-24).

## Open questions
- None.

## Owner actions open
- [IMC-22](https://danieldecker.atlassian.net/browse/IMC-22) — recording session with the kit
  (comment 10057 replaces the description's commands), due 2026-12-04.
- Still to create after T029: the Windows live check of T035.

## Decisions received
- IMC-23 (Daniel Decker, chat, 2026-11-23): "Ich ratifiziere die Constitution 2.0.0" — ratified as
  drafted (the review minor on Principle III "optional" integration tests left as is).
- IMC-24 (Daniel Decker, chat, 2026-11-23): recordings evaluated only, no 7.4 fixtures from
  recordings, raw hand-over without neutralization; constitution conflict solved by a MAJOR
  amendment 2.0.0 (rejected: deviation in plan.md; revisiting IMC-24). Behaviour-changing:
  applied as spec amendment.
- T026 (chat): recording approved; machine unknown to the owner; 7.4 Workbench with JDK present;
  "the machine is not production". At the session: `v74_version` = 7.4 and the node is not
  production (`$NodeIsNonProduction`, set by the machine owner).
- IMC-21 (chat): option A — recorder copied to the machine as the "additional Windows script".
- IMC-19: Windows-only live check. IMC-18: only MCP releases on that machine (→ IMC-21).

## Tried and discarded
- Neutralize map in the kit (guide step written, then dropped by IMC-24).
- `addGraphContext` for Blocks links: 403; `createJiraIssueLink` via executeWrite works.

## Assumptions, risks, gotchas
- The raw results contain customer data: never under a repository, never in a commit, delete after
  evaluation. Fixtures get neutral values only (`legacy74/node1`, `jdoe`,
  `inubit-legacy74-1.example.test`).
- The recorder still masks host, login user, Windows names and paths, and stops on the password
  (built in, not changed).
- Kit tested on macOS only (parse, Check up to the Windows-only `Test-NetConnection`, Pack guard,
  no-op map); never run on Windows.
- T029 must change the FR-015 guard (`V74RecordedFixturesTest`) to "adjusted to the evaluation",
  run review-spec over all amendments, and handle IMC-16 m6 (`CapabilityMatrix.lines()`).
- Shell edits: the safety check misreads heredocs containing `Remove-Item`; use a script file.
- Jira: assignee needs `{"accountId": …}`; the flag is `Flagged: ["Impediment"]`.

## Claims for verification
- None yet (no Mandate B code).

## Budget
Not tracked by the pilot setup.
