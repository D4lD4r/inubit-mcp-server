# Handoff — IMC-9 · Executing · 2026-11-19 · run executor-1

## Status
IMC-9 is `Executing` (M3 on 2026-11-19), assignee APEX Executor, accountable Daniel Decker (Product
Engineer). Branch `claude/025-mandate-a-imc-9`, cut from `origin/main` `c98bd7e3` (IMC-2 merged as
#102). Spec: `specs/025-inubit-74-support/` US1–US5, FR-001 – FR-020, pinned `2bfe55e7`. Plan and
tasks live there (Spec Kit); this mandate owns tasks T001–T025 only. T001–T019 done (Phases 1–5
complete). Question IMC-14 answered (option 1) and applied in T010; the **Blocked flag is still
set** — clearing it (M14) and moving IMC-14 (QC2/QC5) are the Product Engineer's moves.

**Resumed 2026-11-19 16:30 CET.** T020–T025 done (Mandate A complete) plus a clean-up of open
minors (`b27813ab`); full suite after T025 running. Full suite after Phase 6 (`b17b853f`): exit 0,
11,263 tests, 0 failures, 62 skipped. The partial T020 test of the stopped implementer was kept and
finished (its node lacked a `cliJavaHome`, so `CliRunner` refused the launch before `-h`).

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
- Open minor findings (all approved, non-blocking; details in `reviews/T0xx-r<n>.md`), candidates
  for a clean-up before the PR:
  - T006: `ClientVersion.compare(server, depth)` does not null-check `depth`.
  - T008: `V74PortSetTest` skips interface default methods, so `queueEntries` is not checked for 7.4.
  - T009: method Javadoc of `VersionGate.refuseUnsupported` does not mention the 7.4 note branch.
  - T010: `V74RefusalTest.java:67` Javadoc line over 100 characters.
  - T014: `MainTest` "not evaluable" check should be one contiguous excerpt.
  - T015: `ConfigValidatorTest.aV74NodeHasNoClientOptionFinding` cannot catch a regression; rename
    or drop.
  - T016: pin name built as `"V" + line.replace('.', '_')` duplicates the `VersionSetting` naming.
  - T017: the 6.1 case in `ListNodesToolTest` uses an observed version instead of a pin.
  - T018: unclear failure message in `V74FixturesShapeTest:66`.
  - T019: awkward Javadoc of `monitoring(SupportState)` in `V74RecordedFixturesTest`.
- Recurring theme in the minors: tests that pass for the wrong reason (T008, T015) and Javadoc
  that lags behind the code (T009, T019).
- Mandate A is offline only: no INUBIT command, no network call to any INUBIT server; the recorder
  is changed and tested, never run.
- A local context-mode hook blocks plain `mvn` calls for subagents in some runs; they use a wrapper.
- Full suite timing varies with machine load: 3, 6 and 13 minutes so far. Run it in the background
  and announce about 15 minutes. Do not start an implementer while it runs: both use `target/`.
- Jira (connector `atlassian-apex-executor` only, cloudId `693d2be5-6c84-4b10-a061-a48c71dabe8f`):
  - setting the assignee as a bare accountId string clears the field silently; use
    `{"accountId": "…"}`;
  - the Blocked flag is `customfield_10021` = `[{"value": "Impediment"}]`;
  - `createJiraIssueLink` with type Blocks: `inwardIssue` is the blocker, `outwardIssue` the
    blocked card ("A blocks B" = inward A, outward B);
  - the connector cannot delete issue links.

## How to resume
- Worktree: `/Users/dadecker/Development/Privat/inubit-mcp-server/.claude/worktrees/025-mandate-a-imc-9`
  (branch `claude/025-mandate-a-imc-9`, pushed). The main checkout is on another branch; do not
  work there.
- Start `d4ld4r-speckit-duo:implement` for T020–T025 (Mandate A only, never T026–T037), one
  commit and push per task, HANDOFF status line before each full suite.
- After T025: full suite `mvn -q -o clean verify`, then one PR for all of Mandate A (no AI hint in
  the PR text, English, short), wait for green CI, commit HANDOFF and tasks.md, then M4 (Executing →
  Verifying) with a comment linking PR, CI and handoff. Stop at M4; verification is another agent.

## Claims for verification
- T001–T019 implemented test-first and approved by an independent task reviewer; see
  `specs/025-inubit-74-support/reviews/`. Verification tasks (T003, T011, T012, T013, T018 parts)
  passed at once by design.
- No production code beyond the tasks' touch set; Mandate A ran fully offline.

## Budget
Run started 2026-11-19 12:10 CET, paused about 16:25 CET (about 4 h 15 min). No token cap set by an
orchestrator (manual start).
