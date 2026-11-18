# Handoff — IMC-2 · Executing, Blocked (M13) · 2026-11-18 · run executor-1

## Status
All offline tasks of US4 Phase 6 are done: T048–T053, T056–T065, each through the
implementer → reviewer loop (reviews in `specs/022-full-interface-coverage/reviews/T0xx-r*.md`),
committed and pushed on `claude/022-us4-operate-server-1b63fe` (last task commit `ecf76f77`).
Spec `specs/022-full-interface-coverage/` US4 @ `915f2aef`. No PR yet (T066 opens it).
Open: T054, T055 (live write checks, owner-gated) and T066 (blocked by both).
IMC-2 is flagged Blocked by the Question cards IMC-7 and IMC-8.

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
Run the live checks T054 and T055 on the 8.1 TEST server, one owner-approved command at a time
(command set: shared notes `imc-2-live-checks-draft.md`, also in the IMC-2 comment). Then T066.

## Open questions
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
