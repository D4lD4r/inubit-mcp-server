# Handoff — IMC-2 · Executing, Blocked (M13) · 2026-11-18 · run executor-1

## Status
All offline tasks of US4 Phase 6 are done: T048–T053, T056–T065, each through the
implementer → reviewer loop (reviews in `specs/022-full-interface-coverage/reviews/T0xx-r*.md`),
committed and pushed on `claude/022-us4-operate-server-1b63fe` (last task commit `ecf76f77`).
Spec `specs/022-full-interface-coverage/` US4 @ `915f2aef`. No PR yet (T066 opens it).
Open: T054, T055 (live write checks, owner-gated) and T066 (blocked by both).
IMC-2 is flagged Blocked by the Question cards IMC-7, IMC-8 and the Owner action cards
IMC-10 (T054), IMC-11 (T055).

## Done since last handoff
- Change infrastructure: change records, ChangeGuard (six-step order), ChangeLocks,
  ChangeChallenges, ChangeAudit, BackupStore administration kind.
- `operate_server`: CLI forms, CliOperationRunner + parsers (reconstructed answer forms),
  ServerOperationPort/adapter, capability SERVER_OPERATION ("not yet verified"),
  ServerOperationService (switch actions + deleteErrorProcesses), OperateServerTool.
- `manage_tags`: CLI tag forms, CliTagAdminRunner + TagAdministrationPort, capability
  TAG_ADMINISTRATION, TagAdministrationService, ManageTagsTool.
- Cross-cutting safety tests (production / write-off refusal for every change action, write race,
  secret leak). Full `mvn clean verify` green after every task.

## Next step
- 2026-11-19: T055 run 4 (c5017c34, `S1,W17,W18,X1`): green, server clean. `--tagSetActive` refused
  with TagNameWasNotFound although the group carried the tag (active tagging off on the node);
  `--tagRemoveActive` exit 0 without text. Live checks of T055 complete (IMC-11 summary). Open:
  owner decision IMC-7 on what manage_tags keeps (removeTag repositoryPath unverified, deleteTag
  owner-wide → excluded, active-tag pair unverifiable here), then fold into spec/code, tick T055,
  T066 (incl. IMC-8 contract text), full `mvn clean verify`, PR.
- 2026-11-19: T055 runs 1–3 (results on IMC-11). Run 1 stopped at the active-tag read (fixed
  f6be0005). Run 2: group-scoped `--tagRemove` needs `--tagRepositoryPath`; `--tagDelete` needs
  `--tagUser` (fixed 2149c277). Run 3: `--tagDelete` on g1 with owner deleted the test tag
  owner-wide (g2 lost it, the tag no longer exists) → `deleteTag` to be excluded (acts on the whole
  owner, D-18); `tagMove` tags workflow + modules, no repository files (repository-path removal not
  exercised); W17 refused because the tag no longer existed. Server clean (verified). Open: active-tag
  pair W17/W18 (needs a run that tags first), IMC-7 answer from these results, exclusions in spec,
  then T066. IMC-8 answered (contract text with T066).
- 2026-11-18: T054 done (40238211): recorded 8.1.17 answer forms as neutralized fixtures; ready-off
  503 "No Service." counts as success; trace level from `/system/info` TracingLevel; delete answer
  without count (no invented count; IMC-8 untouched); `setDebug` excluded (4e7841a0). Next: T055
  (Owner action IMC-11), then T066 after IMC-7/IMC-8. Full `mvn clean verify` still due before the
  PR (several production changes since T065). Still open: full body of `POST /ready/off` unrecorded
  (both forms accepted); get_health fixture `ready_not_ready.json` uses `NOT_READY` (real form
  `NOT READY`/`No Service.`); concurrent queueLog reads → HTTP 500 MonitoringLockedByOtherClient.
- 2026-11-18: run 4 (a1d525ac): W11 `triggerMidnightTask` exit 0, no text, trigger time field
  unchanged; W12 `processdelete error` on the approved ERROR set (256 ids, equal before the delete)
  exit 0, answer "Process with status [error] has been deleted." (no count), ERROR set empty after.
  T054 live checks done (W9/W10 excluded). Open before T066: production fixes — ready-off answers
  503 although it switches; `ProcessDeleteAnswerParser` form (no count); trace level readable from
  `/system/info` (TracingLevel); neutralize recordings into fixtures; mark T054 done. Owner: remove
  the test group's `write` block, re-enable the AWS LB health check. Then T055 (IMC-11).
- 2026-11-18: debug session (06eb387d): one interactive session `debug; debug on; debug; debug off;
  debug` → off/off/off, after session off; `debug on|off` has no observable effect on 8.1 (vendor
  help 6.1: trace switch of the interactive CLI mode). Proposal pending with the owner: exclude
  `setDebug` from operate_server (spec, contract, coverage). W11/W12 not run.
- 2026-11-18: write run 3 (0e54fa25, subset W5–W10): W5 ready/on from READY 200; trace on/off
  ok (no answer text); `trace -l debug|info` ok ("Successfully changed level to …"); stopped at
  W9: `debug on` exit 0, no text, `debug` read stays off. Nothing restored, baseline verified.
  Open: W9/W10 semantics (debug), W11/W12 not run; production fixes before T066: ready-off 503,
  setDebug. Results on IMC-10.
- 2026-11-18: write run 2 (b04e5230): W1, W2a, W2b (503 + effect), W3, W2u ok; stopped at W4:
  `POST /ready/off` answers HTTP 503 "No Service." on 8.1 although the switch succeeds, also
  without maintenance. Baseline restored and verified. Production finding: the REST client maps
  that 503 to an error, so `operate_server setReady off` would report failure for a success —
  fix before T066. W5–W10 (trace/debug) still open; next run needs a test change (ready-off 503
  with effect accepted at W4 too) and re-approval.
- 2026-11-18: write run 1 via `OperateServerWritesLiveTest` (c5330d08) on the 8.1 test node:
  W1 maintenance on ok; W2a `/ready` 503 "Maintenance mode active"; W2b `POST /ready/off` during
  maintenance → client UNREACHABLE (non-2xx) although the switch took effect; run stopped, restore
  (maintenance off, `POST /ready/on`) reached the baseline, verified. Ready-off survives leaving
  maintenance. W3–W10 not run (trace/debug writes open). Results on IMC-10. Next: owner decides
  how to run the rest (test change needed), re-approval per command.
- 2026-11-18: T054 reads R1–R9 done on the 8.1 test node (8.1.17) via `OperateServerReadsLiveTest`
  (568f000b); results on IMC-10. Trace is on at level info, debug off, maintenance off, ready.
  Writes W1–W12 next, through `operate_server` of a local-scope MCP registration of the branch
  jar (copy of 72e058d2 under `~/.local/lib/`); needs a session that has loaded it. Undo lines corrected: W6 no change, W7 undo `trace on`,
  W8 undo `trace -l info`. After the checks: owner removes the test group's `write` block and re-enables the AWS
  LB health check.
Run the live checks T054 and T055 on the 8.1 TEST server, one owner-approved command at a time
(command sets on IMC-10 / IMC-11; approval only in chat). Then T066.

## Open questions
- [IMC-10](https://danieldecker.atlassian.net/browse/IMC-10) — Owner action T054: test server,
  time window, one ERROR test process, APPROVE lines for W1–W12 (W11 only on explicit yes). Due 2026-11-20.
- [IMC-11](https://danieldecker.atlassian.net/browse/IMC-11) — Owner action T055: test server, g1/g2,
  repository path, test user, APPROVE lines for W13–W18. Due 2026-11-20.
- [IMC-7](https://danieldecker.atlassian.net/browse/IMC-7) — manage_tags `{repositoryPath}` scope and
  repository files touched by tag removal; blocks T066 flips of removeTag/deleteTag. Recommendation:
  decide after T055.
- [IMC-8](https://danieldecker.atlassian.net/browse/IMC-8) — contract text `affected` (workflows)
  for deleteErrorProcesses; blocks the T066 contract/docs update. Recommendation: amend the text.

## Decisions received
- M1 by Daniel Decker (2026-11-17).

## Decisions taken by the execution agent (verifier: check)
- T059: VersionGate admits `setMaintenance {on:false}` on the last known version when re-detection
  fails with MAINTENANCE_MODE (AS6 on `versionLine: AUTO`); never-known → UNSUPPORTED_VERSION
  "pin versionLine". research.md D-22.
- T063: tag listing only for technical diagram groups; repository-path listing refused until T055.
- T064: owner of preview/backup/re-read = node's `inventory.owner` (T055 confirms).
- Card non-goal "REST write operations" read as "outside US4": `POST /ready/{status}` is in US4
  (spec table A, AS9) and implemented.
- Touch set: files named by tasks.md T048–T066 treated as inside the touch set.

## Tried and discarded
- First executor session had no Jira connector; nothing on the board from it.

## Assumptions, risks, gotchas
- Answer forms of maintenance/trace/debug/midnight/processdelete/active tag are reconstructed
  (SYNTHETIC fixtures) until T054/T055; trace level and debug state have no read form yet
  (before state shown as unknown).
- Full Maven build takes ~10 min; tool wrappers kill foreground runs at 600 s — run it detached.
  Per task only the tests of the touched area; the full `clean verify` once per phase and
  before the PR (owner decision 2026-11-18).
- Open minor review findings (non-blocking) for the phase code review: T048 (2), T051 (READ in
  needsCode), T052 (Upload Javadoc), T053 (2), T056 (OPTIONS dual use), T057 (2), T058 (2),
  T060 (Javadoc line), T061 (TerminologyRenderingTest comment), T062 (CliTagRunner Javadoc),
  T065 (4, test-only).

## Claims for verification
- US4 AS1–AS14 covered by offline tests with fakes (ServerOperationServiceTest,
  ServerOperationDeleteErrorsTest, TagAdministrationServiceTest, OperateServerToolTest,
  ManageTagsToolTest, ChangeToolsProductionRefusalTest, WriteRaceToolsTest,
  OperationSecretLeakTest). AS5/AS8 only partly (before state unknown until T054).
- Live behaviour (L-2, L-3, L-4) not verified yet.

## Budget
Not tracked by the pilot setup.
