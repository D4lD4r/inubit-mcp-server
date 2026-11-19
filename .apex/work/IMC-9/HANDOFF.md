# Handoff — IMC-9 · Executing · 2026-11-19 · run executor-1

## Status
IMC-9 is `Executing` (M3 on 2026-11-19), assignee APEX Executor, accountable Daniel Decker (Product
Engineer). Branch `claude/025-mandate-a-imc-9`, cut from `origin/main` `c98bd7e3` (IMC-2 merged as
#102). Spec: `specs/025-inubit-74-support/` US1–US5, FR-001 – FR-020, pinned `2bfe55e7`. Plan and
tasks live there (Spec Kit); this mandate owns tasks T001–T025 only. Phases 1–2 done (T001–T007),
Phases 1–5 done except T010. **Blocked flag set (M13)** by Question IMC-14; T010 waits for its
answer, the other tasks continue (T018 next).

**Now (2026-11-19 15:50 CET):** full suite `mvn -q -o clean verify` after Phases 4–5, expected
about 15 minutes (the Phase 3 run took 13 minutes on a loaded machine). Last finished task: T017
(`9d7c0990`); T010 test committed open (`40ba35d5`).

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
| T010 per-tool refusal test | `40ba35d5` (open) | 1, changes requested |
| T011 `check_artifacts` on 7.4 | `b02131f8` | 1 |
| T012 announcement | `fa46149c` | 2 |
| T013 display | `e189390e` | 1 |
| T014 client configuration 7.4 | `0d4de76f` | 1 |
| T015 no `-h` probe on 7.4 | `f7fdabbf` | 1 |
| T016 404 hint | `8824eb74` | 1 |
| T017 patch depth at every comparison | `9d7c0990` | 1 |

Full suites: after Phase 1 exit 0, 11,012 tests; after Phase 2 exit 0, 11,103 tests (0 failures,
58 skipped each). Reviews in `specs/025-inubit-74-support/reviews/T0xx-r<n>.md`.

## Next step
T018 (reconstructed 7.4 cases), then T019 … T025 in order with `d4ld4r-speckit-duo:implement`,
one commit and push per task. When IMC-14 is answered: T010 round 2 (apply the answer, plus the two
minor findings of T010-r1), then tick T010.

## Open questions
- [IMC-14](https://danieldecker.atlassian.net/browse/IMC-14) — the `get_health` refusal on 7.4 has
  no `get_health: ` prefix (as on 6.1), while contracts/mcp-tools-delta.md:11, the T009 Expected
  message (tasks.md:170) and research.md:102 show it. Options: 1 amend the documents (recommended,
  no code change), 2 prefix for 7.4 only, 3 prefix for every version (changes the 6.1 baseline).
  Blocks: approval of T010 and that text in T025.
- Jira housekeeping: a wrong link "IMC-9 blocks IMC-14" was created first; the executor connector
  cannot delete links. The correct link "IMC-14 blocks IMC-9" exists. The wrong one needs removing
  by a human.

## Decisions received
- M1 by Daniel Decker (card description).

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
