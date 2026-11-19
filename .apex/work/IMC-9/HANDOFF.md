# Handoff — IMC-9 · Executing · 2026-11-19 · run executor-1

## Status
IMC-9 is `Executing` (M3 on 2026-11-19), assignee APEX Executor, accountable Daniel Decker (Product
Engineer). Branch `claude/025-mandate-a-imc-9`, cut from `origin/main` `c98bd7e3` (IMC-2 merged as
#102). Spec: `specs/025-inubit-74-support/` US1–US5, FR-001 – FR-020, pinned `2bfe55e7`. Plan and
tasks live there (Spec Kit); this mandate owns tasks T001–T025 only. T001–T019 done (Phases 1–5
complete). Question IMC-14 answered (option 1) and applied in T010; the **Blocked flag is still
set** — clearing it (M14) and moving IMC-14 (QC2/QC5) are the Product Engineer's moves.

**Paused (2026-11-19, on Daniel's request)** before T020. The implementer of T020 was stopped
before it reported; its partial, unreviewed `src/test/java/de/dadecker/inubit/mcp/adapter/cli/v74/
V74HelpContractTest.java` is left **untracked and uncommitted** in the worktree. Last finished
task: T010 (`9a1cdf43`). Full suite after Phases 4–5: exit 0, 11,245 tests (before T010 round 2,
T018, T019; those ran their touched-area tests only).

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
| T008 refusing 7.4 port set | `fd782036` | 1 |
| T009 refusal text on 7.4 | `009f34d3` | 1 |
| T010 per-tool refusal test | `40ba35d5`, `9a1cdf43` | 2 (round 2 after IMC-14) |
| T011 `check_artifacts` on 7.4 | `b02131f8` | 1 |
| T012 announcement | `fa46149c` | 2 |
| T013 display | `e189390e` | 1 |
| T014 client configuration 7.4 | `0d4de76f` | 1 |
| T015 no `-h` probe on 7.4 | `f7fdabbf` | 1 |
| T016 404 hint | `8824eb74` | 1 |
| T017 patch depth at every comparison | `9d7c0990` | 1 |
| T018 reconstructed 7.4 cases | `3e0bedbd` | 1 |
| T019 recorded-fixtures guard | `f187d8cf` | 2 |

Full suites: after Phase 1 exit 0, 11,012 tests; after Phase 2 exit 0, 11,103 tests (0 failures,
58 skipped each). Reviews in `specs/025-inubit-74-support/reviews/T0xx-r<n>.md`.

## Next step
Resume T020 (contract test of the real help case): either finish the untracked
`V74HelpContractTest.java` in a new implementer round or delete it and start the task fresh; then
review, commit, and continue T021 … T025 in order with `d4ld4r-speckit-duo:implement`,
one commit and push per task. Run the full suite after Phase 6. T025 docs: the `get_health`
refusal on 7.4 names no tool (IMC-14).

## Open questions
- None blocking. [IMC-14](https://danieldecker.atlassian.net/browse/IMC-14) is answered and
  applied in T010 (`9a1cdf43`); the card's QC2/QC5 moves and clearing the Blocked flag on IMC-9 are
  the Product Engineer's.
- Jira housekeeping: a wrong link "IMC-9 blocks IMC-14" was created first; the executor connector
  cannot delete links. The correct link "IMC-14 blocks IMC-9" exists. The wrong one needs removing
  by a human.

## Decisions received
- M1 by Daniel Decker (card description).
- IMC-14, option 1, by Daniel Decker in the executor's chat (2026-11-19): the documents follow the
  code; the `get_health` refusal on 7.4 names no tool, as on 6.1. Not behaviour-changing; contract
  example, T009 Expected message and research D-6 amended in `9a1cdf43`.

## Readings taken (spec wins over tasks.md, P2-14 rule 2)
- T012: the task text said a 7.4-only profile does not announce `get_health`. FR-009 ("feature 009
  rule, unchanged"), the contract and feature 012 D-12 keep `get_health` always announced. The test
  follows the spec; the T012 description in tasks.md was reworded (review T012-r1/r2).

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
