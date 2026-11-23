# Handoff — IMC-17 · Executing (Blocked) · 2026-11-23 · run executor-1

## Status
Run ended: only blocked work is left. IMC-21 answered (option A); IMC-17 stays `Executing` with flag
`Blocked` until IMC-22 is done, assignee APEX
Executor (claim read back), plan in comment 10052.
Spec: `specs/025-inubit-74-support/` pinned `2bfe55e7`; this branch amends spec.md for the IMC-19
decision (`c99fe5c8`, not yet reviewed with review-spec, not merged). Plan and tasks in the same
folder; this mandate's range is T026–T037 (T030 `[X]` as a proposal). Branch
`claude/025-imc-17-mandate-b-b4dc46`, based on main `065cc4f2`, pushed; no PR yet (delivery step 1
is a spec MR after T029).

## Done since last handoff
- Claim, plan comment, M3, flag; Question IMC-21, Owner actions IMC-22 and IMC-23, each linked
  Blocks → IMC-17.
- `c99fe5c8`: IMC-19 decision in spec.md (FR-031, SC-007, US6/US7 independent tests,
  Preconditions, Open Questions → Decided), plan.md, tasks.md (Mandate B header, T035),
  quickstart.md step 6 — the live check is Windows-only.
- `a99e59fb`: T030 — constitution 1.2.0 proposed (Principle V: 7.4 read-only, StartCLI only),
  marked PROPOSED, Last Amended a TODO; duo review round 1 APPROVED with two minors
  (`reviews/T030-r1.md`). `NoCustomerIdentifiersTest` green.

## Next step
The owner runs IMC-22 together with the owner of the Windows machine, using the run sheet
`.apex/work/IMC-17/record-v74.ps1` (fill in the block TO FILL IN; phases `Check` → `Research` →
send `v74_exec_help.stdout` / `v74_exec_ps_help.stdout` here → T027 catalogue check → `Session` →
`Pack`). Then unpack the ZIP into `src/test/resources/fixtures/v7_4/cli`, run
`NoCustomerIdentifiersTest`, finish T028, then T029.

## Setup on the Windows machine (before `-Phase Check`)
- Repository copy of branch `claude/025-imc-17-mandate-b-b4dc46` (ZIP from GitHub is enough).
- **Python 3.7 or newer** — unknown whether installed. `tools\neutralize.py` needs only the
  standard library (no pip packages). If missing: install from python.org ("Install for current
  user", tick "Add python.exe to PATH"; no admin rights needed), or unpack the "Windows embeddable
  package" and put its folder on PATH for that PowerShell session. The Microsoft Store placeholder
  `python.exe` does not count; `-Phase Check` detects it. Undo: uninstall via Apps, or delete the
  unpacked folder.
- The local neutralize map (copied to the machine for the session, deleted afterwards).
- 7.4 Workbench client with its JDK: present (owner, 2026-11-23).

## Open questions
- None. [IMC-21](https://danieldecker.atlassian.net/browse/IMC-21) answered (option A); its card
  moves QC2/QC5 are the Product Engineer's.

## Owner actions open
- [IMC-22](https://danieldecker.atlassian.net/browse/IMC-22) — recording session T026–T028 (commands
  for option A, with undo), due 2026-12-04.
- [IMC-23](https://danieldecker.atlassian.net/browse/IMC-23) — ratify constitution 1.2.0 before
  T035, due 2026-12-11.
- Still to create after T029: the Windows live check of T035 (commands follow from T029).

## Decisions received
- T026 (Daniel Decker, in the chat with this agent, 2026-11-23): recording **approved**. The
  owner does not know the machine and runs the session with its owner; a 7.4 Workbench with JDK is
  there; "the machine is not production". Still to confirm at the session: `v74_version` reports
  7.4 and the INUBIT node is not classified production (machine owner sets `$NodeIsNonProduction`
  in the run sheet; without it the sheet refuses to run). The approval covers the cases of IMC-22
  only, read-only, against that non-production node.
- IMC-21 (Daniel Decker, in the chat with this agent, 2026-11-23): option A — the recorder is the
  allowed "additional Windows script" and is copied to the machine; NoCustomerIdentifiersTest runs
  on the development machine. Procedural, no spec amendment; IMC-22 stays valid.
- IMC-19 (Daniel Decker, 2026-11-23): no macOS machine with 7.4; assume it works, fix bugs when
  they appear → Windows-only live check (the spec's open question foresaw this owner decision).
- IMC-18 (Daniel Decker, 2026-11-23): a 7.4 installation exists; on that machine only a new MCP
  version can be rolled out, maybe with an additional Windows script → reading unclear, IMC-21.

## Tried and discarded
- `addGraphContext` for the Blocks link: 403 (missing scope); `createJiraIssueLink` via
  executeWrite works.

## Assumptions, risks, gotchas
- The recorder needs Python (`tools/neutralize.py`) and the local neutralize map on the Windows
  machine, besides the 7.4 client and its JDK — named in IMC-21 and IMC-22.
- The live check needs a pre-flip MCP build on that machine; with "only MCP releases" this may mean
  a release before the flip (owner decision, IMC-21).
- T029 must run review-spec over the whole amended spec, including the IMC-19 change of `c99fe5c8`.
- T030 minors: Principle V text reads as in force without the PROPOSED context; at ratification set
  the version line and Last Amended date, drop the Sync Impact Report, update plan.md (cites 1.1.0).
- IMC-16 m6 (`CapabilityMatrix.lines()` skips 7.4 only while every capability has a note) is part
  of T029.
- Jira: `editJiraIssue` needs `{"assignee": {"accountId": …}}`; a bare string is silently ignored.
  The flag is `Flagged: ["Impediment"]`.

## Claims for verification
- None yet (no Mandate B code).

## Budget
Not tracked by the pilot setup.
