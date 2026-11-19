# Handoff — IMC-9 · Mandate ready → Executing (M3) · 2026-11-19 · run executor-1

## Status
Claimed by APEX Executor (read-back: assignee APEX Executor, state `Mandate ready`). Accountable:
Daniel Decker (Product Engineer). Branch `claude/025-mandate-a-imc-9`, cut from `origin/main`
`c98bd7e3` (IMC-2 merged as #102). Spec: `specs/025-inubit-74-support/` US1–US5, FR-001 – FR-020,
pinned `2bfe55e7`. Plan and tasks live there (Spec Kit); this mandate owns tasks T001–T025 only.

## Done since last handoff
- Claim checks: IMC-2 `Released` and on main; no other mandate in `Executing`/`Verifying`/
  `Integrating` (module lease free); `.apex/work/IMC-9/HANDOFF.md` not ignored.
- Plan posted on IMC-9.

## Next step
Implement T001 (fixture scaffold) with `d4ld4r-speckit-duo:implement`, then T002 … T025 in order,
one commit and push per task.

## Open questions
- None.

## Decisions received
- M1 by Daniel Decker (card description).

## Tried and discarded
- None.

## Assumptions, risks, gotchas
- tasks.md was written against main 0.10.2, before #102 (022 US4) merged. #102 touched VersionGate,
  CapabilityMatrix, RefusingPorts, Wiring, NodeOverview, ConfigSummary, list_nodes.output.json and
  docs/setup.md, so line numbers in tasks.md are pointers only; the spec wins (truth hierarchy).
- Mandate A is offline only: no INUBIT command, no network call to any INUBIT server; the recorder
  is changed and tested, never run.
- Jira: setting the assignee as a bare accountId string clears the field silently; use
  `{"accountId": "…"}`.

## Claims for verification
- None yet.

## Budget
Run started 2026-11-19 12:10 CET. No token cap set by an orchestrator (manual start).
