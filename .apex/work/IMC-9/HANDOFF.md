# Handoff — IMC-9 · Verifying · 2026-11-19 · run executor-1

## Status
IMC-9 is `Verifying` (M3 and M4 on 2026-11-19; M4 taken by the APEX Executor account via REST at
19:15 CET), assignee APEX Executor, accountable Daniel Decker (Product
Engineer). Branch `claude/025-mandate-a-imc-9`, cut from `origin/main` `c98bd7e3` (IMC-2 merged as
#102). Spec: `specs/025-inubit-74-support/` US1–US5, FR-001 – FR-020, pinned `2bfe55e7`. Plan and
tasks live there (Spec Kit); this mandate owns tasks T001–T025 only. **T001–T025 done (Mandate A
complete).** Question IMC-14 answered (option 1) and applied in T010. Daniel cleared the Blocked
flag on IMC-9 (M14) and removed the wrong link "IMC-9 blocks IMC-14"; the link "IMC-14 blocks
IMC-9" remains. IMC-14 is still `Open`; its QC2/QC5 moves are the Product Engineer's.

**Resumed 2026-11-19 16:30 CET, ready for M4 at 18:35 CET.** T020–T025 done plus a clean-up of
open minors (`b27813ab`). Full suite after T025 (`23a97fd2`): exit 0, 11,264 tests, 0 failures,
62 skipped. [PR #104](https://github.com/D4lD4r/inubit-mcp-server/pull/104) open against `main`,
CI all green on the first attempt (no rerun), also on head `d7375c24`. This run had no
`atlassian-apex-executor` connector and used no other Jira identity; M4 was taken separately by
the executor account, and the comment below is posted on IMC-9 as a separate comment.

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
| T020 real help contract test | `ca8c900c` | 1 |
| T021 recorder case table | `7a2c9c25` | 1 |
| T022 recorder session mode | `202bbbc0` | 1 |
| T023 recorder 7.4 catalogue | `aa9cfe19` | 1 |
| T024 regression verification, validation record | `aed4818e` (CI row in the final handoff commit) | 1 |
| T025 docs and changelog | `d35b7751` | 1 |
| Clean-up of open minors | `b27813ab` | 1 (`reviews/cleanup-r1.md`) |

Full suites (`mvn -q -o clean verify`, macOS): after Phase 1 exit 0, 11,012 tests; after Phase 2
exit 0, 11,103 tests (58 skipped each); after Phase 6 (`b17b853f`) exit 0, 11,263 tests; after
T025 and clean-up (`23a97fd2`) exit 0, 11,264 tests (62 skipped each, 0 failures). Reviews in
`specs/025-inubit-74-support/reviews/T0xx-r<n>.md`.

CI on PR #104 at `23a97fd2`, run
[44573778803](https://github.com/D4lD4r/inubit-mcp-server/actions/runs/44573778803):
[build](https://github.com/D4lD4r/inubit-mcp-server/actions/runs/44573778803/job/135735630386)
10m55s, [windows](https://github.com/D4lD4r/inubit-mcp-server/actions/runs/44573778803/job/135735629782)
23m54s, CodeQL (actions, java-kotlin) — all pass, first attempt.

## Next step
Verification of IMC-9 by another agent (PR #104, the claims below). Nothing left for this run.

### M4 comment (posted on IMC-9)
> M4 Executing → Verifying. Mandate A (tasks T001–T025 of feature 025, INUBIT 7.4 recognised by
> pin and refused clearly, CLI only) is implemented test-first, one commit per task, each approved
> by an independent task reviewer.
> PR: https://github.com/D4lD4r/inubit-mcp-server/pull/104
> CI: https://github.com/D4lD4r/inubit-mcp-server/actions/runs/44573778803 (build and windows
> green, CodeQL green, no rerun).
> Local full suite at `23a97fd2`: exit 0, 11,264 tests, 0 failures, 62 skipped.
> Handoff: https://github.com/D4lD4r/inubit-mcp-server/blob/claude/025-mandate-a-imc-9/.apex/work/IMC-9/HANDOFF.md
> Readings and open minor findings are listed in the handoff. Ran fully offline: no INUBIT command
> and no call to any INUBIT server.

## Open questions
- None blocking. [IMC-14](https://danieldecker.atlassian.net/browse/IMC-14) is answered and
  applied in T010 (`9a1cdf43`); the card is still `Open`, its QC2/QC5 moves are the Product
  Engineer's.
- Jira housekeeping resolved: Daniel cleared the Blocked flag and removed the wrong link "IMC-9
  blocks IMC-14"; the correct link "IMC-14 blocks IMC-9" remains.

## Decisions received
- M1 by Daniel Decker (card description).
- IMC-14, option 1, by Daniel Decker in the executor's chat (2026-11-19): the documents follow the
  code; the `get_health` refusal on 7.4 names no tool, as on 6.1. Not behaviour-changing; contract
  example, T009 Expected message and research D-6 amended in `9a1cdf43`.

## Readings taken (spec wins over tasks.md, P2-14 rule 2)
- T012: the task text said a 7.4-only profile does not announce `get_health`. FR-009 ("feature 009
  rule, unchanged"), the contract and feature 012 D-12 keep `get_health` always announced. The test
  follows the spec; the T012 description in tasks.md was reworded (review T012-r1/r2).
- T020: the untracked partial `V74HelpContractTest.java` of the stopped implementer was kept and
  finished, not restarted (its test node lacked a `cliJavaHome`, so `CliRunner` refused the launch
  before `-h`).
- T021: the case table introduces a Kind `export-backslash`, not in the data model; kept as a
  recorder-internal kind (minor T021-r1).
- T024: the quickstart validation row for CI stayed pending until PR #104 was green; T024 was
  ticked in tasks.md only then.
- quickstart FR-009 row corrected to the T012 reading: `get_health` stays always announced.
- T025: the `get_health` refusal on 7.4 names no tool (IMC-14, option 1).

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
- Open minor findings (all approved, non-blocking; details in `reviews/`). Fixed in the clean-up
  `b27813ab`: T006, T008, T009, T010, T015, T018, T019, T020 and the T025 doc minors. Still open:
  - T014: `MainTest` "not evaluable" check should be one contiguous excerpt.
  - T016: pin name built as `"V" + line.replace('.', '_')` duplicates the `VersionSetting` naming.
  - T017: the 6.1 case in `ListNodesToolTest` uses an observed version instead of a pin.
  - T021: recorder Kind `export-backslash` is not in the data model.
  - T023: no test covers the launch of the `v74_login_failed` case.
  - cleanup-r1: `V74RefusalTest.java:537` is 101 characters; `docs/setup.md:2814` and
    `README.md:181` lack "(unless `knowledge.recording: false`)".
  - Seen while writing T025, outside the touch set: README says 6.1 is "not on Windows" (D-14);
    the README link `docs/setup.md#4-credentials-credential-store` points to a missing anchor.
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
  - the connector cannot delete issue links;
  - M4 (Executing → Verifying) has no transition screen: a comment sent with the transition is
    dropped silently. Post the comment as a separate comment.

## How to resume
- Worktree: `/Users/dadecker/Development/Privat/inubit-mcp-server/.claude/worktrees/025-mandate-a-imc-9`
  (branch `claude/025-mandate-a-imc-9`, pushed). The main checkout is on another branch; do not
  work there.
- Mandate A is finished and M4 is taken; nothing to implement. Remaining: verification by another
  agent. Mandate B (T026–T037) is not started.
- The subagent `mvn` wrapper note above still applies; `d4ld4r-speckit-duo:implement` was emulated
  in this run (implementer and task reviewer as separate subagents, same role prompts).

## Claims for verification
- T001–T025 implemented test-first and approved by an independent task reviewer; see
  `specs/025-inubit-74-support/reviews/`. Verification tasks (T003, T011, T012, T013, T018 parts,
  T024) passed at once by design.
- No production code beyond the tasks' touch set; Mandate A ran fully offline (the recorder was
  changed and tested, never run).
- Local full suite and CI green at `23a97fd2` (see above).

## Budget
Run started 2026-11-19 12:10 CET, paused about 16:25 CET (about 4 h 15 min); resumed 16:30 CET,
ready for M4 at 18:35 CET (about 2 h 05 min). Total about 6 h 20 min. No token cap set by an
orchestrator (manual start).
