# Handoff — IMC-9 · Executing · 2026-11-19 · run executor-1

## Status
IMC-9 is `Executing` (M3 on 2026-11-19), assignee APEX Executor, accountable Daniel Decker (Product
Engineer). Branch `claude/025-mandate-a-imc-9`, cut from `origin/main` `c98bd7e3` (IMC-2 merged as
#102). Spec: `specs/025-inubit-74-support/` US1–US5, FR-001 – FR-020, pinned `2bfe55e7`. Plan and
tasks live there (Spec Kit); this mandate owns tasks T001–T025 only. Phases 1–2 done (T001–T007).

**Now (2026-11-19, after T007):** full suite `mvn -q -o clean verify` after Phase 2, expected
about 5 minutes (Phase 1 run took 3 minutes). Last finished task: T007 (`71ed6b7e`).

## Done since last handoff
| Task | Commit | Review rounds |
|---|---|---|
| T001 fixture scaffold | `0c932f4b` | 1 |
| T002 real help case | `aa885669` | 1 |
| T003 regression baseline | `66f2f420` | 2 |
| T004 line value `V7_4` | `360e1db5` | 1 |
| T005 fourth version part | `85389139` | 1 |
| T006 patch depth | `c32bbe39` | 1 |
| T007 matrix entry 7.4 | `71ed6b7e` | 1 |

Full suite after Phase 1: exit 0, 11,012 tests, 0 failures, 58 skipped. Reviews in
`specs/025-inubit-74-support/reviews/T0xx-r<n>.md`.

## Next step
T008 (refusing 7.4 port set), then T009 … T025 in order with `d4ld4r-speckit-duo:implement`, one
commit and push per task.

## Open questions
- None.

## Decisions received
- M1 by Daniel Decker (card description).

## Tried and discarded
- T003 round 1: comparing whole `InubitVersion` records in the baseline; breaks once T005 adds
  `build`. The baseline compares major, minor, patch and raw only.

## Assumptions, risks, gotchas
- tasks.md was written against main 0.10.2, before #102 (022 US4) merged. Line numbers in tasks.md
  are pointers only; the spec wins (truth hierarchy).
- Research D-2 says adding `build` changes no existing equality. Not quite: a parsed four-part
  value (e.g. `6.1.0.89`) now carries build 89. Three tests were updated to that (FR-002); ordering
  (FR-004) is unchanged.
- **Trap for Mandate B:** `CapabilityMatrix.lines()` skips 7.4 only while every capability on it has
  a note. When Mandate B removes the MONITORING note, 7.4 will appear in "; supported: …" for the
  capabilities still noted (review T007-r1).
- Since T007, `production().declares(7.4)` is true, so `ConfigValidator.checkCliClients` no longer
  warns "7.4 is not declared supported" for a `cliClients` key 7.4; the 7.4 warnings come with T014.
- Open minor: `ClientVersion.compare(server, depth)` does not null-check `depth` (review T006-r1).
- Mandate A is offline only: no INUBIT command, no network call to any INUBIT server; the recorder
  is changed and tested, never run.
- A local context-mode hook blocks plain `mvn` calls for subagents in some runs; they use a wrapper.
- Jira: setting the assignee as a bare accountId string clears the field silently; use
  `{"accountId": "…"}`.

## Claims for verification
- T001–T007 implemented test-first and approved by an independent task reviewer; see reviews.

## Budget
Run started 2026-11-19 12:10 CET. No token cap set by an orchestrator (manual start).
