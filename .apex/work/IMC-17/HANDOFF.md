# Handoff — IMC-17 · Executing (Blocked) · 2026-11-23 · run executor-1

## Status
Running: T030 (constitution amendment draft) through d4ld4r-speckit-duo:implement — expected
~20 minutes (started 2026-11-23, after commit "IMC-17: record IMC-19 decision").

IMC-17 claimed (assignee APEX Executor, read back), plan posted (comment 10052), M3 → `Executing`,
flag `Blocked` by [IMC-21](https://danieldecker.atlassian.net/browse/IMC-21) (Question) and
[IMC-22](https://danieldecker.atlassian.net/browse/IMC-22) (Owner action).
Spec: `specs/025-inubit-74-support/` pinned `2bfe55e7`; this branch amends spec.md for the IMC-19
decision (not yet reviewed or merged). Plan and tasks in the same folder; this mandate's range is
T026–T037. Branch `claude/025-imc-17-mandate-b-b4dc46`, based on main `065cc4f2`.

## Done since last handoff
- Claim, plan comment, M3, Question IMC-21, Owner action IMC-22 (due 2026-12-04), both linked as
  Blocks → IMC-17; flag set.
- IMC-19 decision applied to spec.md (FR-031, SC-007, US6/US7 independent tests, Preconditions,
  Open Questions → Decided), plan.md, tasks.md (Mandate B header, T035) and quickstart.md step 6:
  the live check is Windows-only.

## Next step
Finish T030 (draft only; the owner ratifies before T035). Then only blocked work is left: wait for
IMC-21 and IMC-22.

## Open questions
- [IMC-21](https://danieldecker.atlassian.net/browse/IMC-21) — how the 7.4 recordings are made on
  the Windows machine where only MCP releases can be rolled out (IMC-18). Options A (recorder copied
  as the "additional Windows script"), B1 (recorder in the release archive), B2 (record mode in the
  jar), C (no recording → re-cut). Recommendation A. Blocks T026–T028, T035, hence T029, T031–T037.

## Decisions received
- IMC-19 (Daniel Decker, 2026-11-23): no macOS machine with 7.4; assume it works, fix bugs when
  they appear → Windows-only live check (spec open question foresaw this owner decision). Applied
  in spec.md on this branch; reaches main with the reviewed spec MR of delivery step 1.
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
- IMC-16 m6 (`CapabilityMatrix.lines()` skips 7.4 only while every capability has a note) is part
  of T029.
- Jira: `editJiraIssue` needs `{"assignee": {"accountId": …}}`; a bare string is silently ignored.

## Claims for verification
- None yet (no Mandate B code).

## Budget
Not tracked by the pilot setup.
