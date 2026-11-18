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
