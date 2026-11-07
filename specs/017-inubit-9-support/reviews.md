# Reviews: 017 INUBIT 9 Support

Producer/reviewer process: after every Spec Kit artefact a `rubber-duck` reviewer, after every
implementation phase and before the push a `code-review` reviewer. Each finding is verified before
it is accepted.

## Spec — round 1 (rubber-duck, 2026-11-06)

| # | Severity | Finding | Decision | Reason |
|---|----------|---------|----------|--------|
| 1 | BLOCKING | Version-keyed setting has no key, schema or validation rules | Accepted | FR-003 now names `defaults.cliClients`, key grammar `<major>.<minor>`, `home` required, `javaHome` optional, invalid vs. warning cases; edge case added |
| 2 | BLOCKING | Client check is cached per node and `get_health` probes in parallel with `/system/info`; an upgraded `AUTO` node would keep the 8.1 client's result | Accepted | Verified: `NodeVersions.clientProbed` stores one client result per node, `HealthService` submits the probe before the version is known. New FR-017; US2 scenario 2 extended |
| 3 | BLOCKING | `V9_X` choosing the sole 9.x entry could run a 9.0 client against 9.1 with false confidence | Accepted (simplified) | `V9_X` now always uses the version-less client; no entry is chosen without a minor (FR-005, FR-011) |
| 4 | BLOCKING | FR-015 changes result shapes of `list_nodes`/`get_health`, contradicting Out of Scope and SC-003 | Accepted (modified) | `get_health` dropped from FR-015 (its client part already reports the check); `list_nodes` gains one additive field; Out of Scope and SC-003 say so |
| 5 | SHOULD | "`get_health` without contacting any node" impossible; undetected `AUTO` display unclear | Accepted | FR-015: "no additional node contact", undetected nodes show the fallback and "decided at runtime" |
| 6 | SHOULD | Partial overrides can pair a 9.0 client with JDK 17 | Accepted (modified) | Precedence written out per field in FR-003; `--check-config` warns about the JDK major for 8.1/9.0 only (other versions have no documented JDK); US2 scenario 5 |
| 7 | SHOULD | "No configured client" conflicts with the `defaults.cliHome` fallback; refusal timing ambiguous | Accepted | FR-005 is a decision table (no client → `CLI_UNAVAILABLE`; excluding range → existing client-check refusal; unknown/`V9_X` → version-less); selection after version resolution; US2 scenarios 3–4 |
| 8 | SHOULD | Servlet-address warning only for "detected" 9.x contradicts SC-004 | Accepted | FR-010 and US3 scenario 2 use the effective version (pin, detection, observation) |
| 9 | SHOULD | 9.0 `startcli -v` output unverified; live check does not require keeping the evidence | Accepted | New SC-006: captured output and REST responses become contract fixtures before 0.8.1 |

Verdict after round 1: no blocking finding open.

## Spec changes during the plan phase (producer, 2026-11-06)

| Change | Reason |
|---|---|
| FR-014: the default StartCLI address and the client per version are configuration defaults computed in the configuration layer | `PackageBoundaryTest` forbids `config` → `adapter`; the choice needs only the version and the configuration (research D-2) |
| SC-003: tests that used 9.0 as "an undeclared version" move to 9.1, and the 0.8.0 tests of the 9.0 matrix entry are replaced | FR-001/FR-002 change exactly that entry; about 25 test classes used 9.0.7 as the undeclared example (D-20) |
| FR-005: the new missing-client texts only when the version has a minor and `cliClients` is configured or the major is ≥ 9 | plan review 1, finding 8: FR-005 and FR-009 contradicted each other |

## Plan — round 1 (rubber-duck, 2026-11-06)

| # | Severity | Finding | Decision | Reason |
|---|----------|---------|----------|--------|
| 1 | BLOCKING | The gate checks the client of its version snapshot, `CliRunner` re-reads the shared version at launch; a concurrent `get_health` can make it launch an unchecked client; `HealthService` re-reads shared state | Accepted | Verified: `VersionGate.evaluate` snapshots the state (lines 173–188), the runner launches later. D-4 launch guard (keyed `-v` result, probe on miss, refuse on excluding range); `ClientSelectionPort`/`ClientVersionPort` take the caller's version snapshot; `HealthService` selects with its own observation (D-5, D-6); race tests in quickstart |
| 2 | BLOCKING | 9.0 login hint keyed by address kind misses explicit `cli.url` (`CONFIGURED`) | Accepted | D-16 now keys on `CliChoice.major` ≥ 9; tests for explicit base-URL and servlet URLs |
| 3 | BLOCKING | Live check omits `export_archives`, `check_artifacts`, `restore_backup`, `deploy_release`; Windows omits the `AUTO` switch | Accepted | Quickstart live steps 8–11 extended (two 9.0 stages for `deploy_release`, Windows step with `AUTO`) |
| 4 | BLOCKING | Release guard relies on a hand-maintained `RECONSTRUCTED.txt`; an omitted case passes | Accepted (modified) | Closed world against the record scripts' 9.0 catalogue instead of a second manifest: `V90FixtureCatalogueTest` (D-10) ties catalogue (`.sh` = `.ps1`), fixture files, contract-test cases and `RECONSTRUCTED.txt` |
| 5 | SHOULD | One client result/failure per node contradicts "per client" caching and per-key back-off (A→B→A) | Accepted | `NodeVersionState` keeps maps keyed by `ClientKey`; plan performance text corrected; A→B→A test |
| 6 | SHOULD | "Any candidate" announces exports when only pinned 8.1 nodes and a `"9.0"` entry exist | Accepted | `anyCandidate`: pinned with minor → its own choice; without minor → version-less; only `AUTO` considers all entries (D-7); wiring test |
| 7 | SHOULD | "cli.url never shown" conflicts with the pin probe's existing error texts | Partly accepted | Verified: `HandshakeStartCliPinProbe` lines 89–95 show the address. Not changed: 0.8.0 behaviour (FR-009), URLs carry no credentials (`ConfigLoader` refuses `@`, lines 637–703), operators need the address to diagnose. The plan constraint is narrowed to the new outputs |
| 8 | SHOULD | FR-005 (always name the entry) vs. FR-009/D-8 (0.8.0 text) | Accepted | Condition of D-8 written into FR-005 |

Not a finding (reviewer): a pinned 9.x node without `cli.url` intentionally moves from the servlet path to the base URL (FR-004, FR-011).

## Plan — round 2 (rubber-duck, 2026-11-06)

| # | Severity | Finding | Decision | Reason |
|---|----------|---------|----------|--------|
| 1 | BLOCKING | Guard only checks the client range; a write admitted on 9.0 still runs if the node is observed as 9.1 (with a checked 9.1 client) before the launch; `select` had no version parameter | Partly accepted | `select(server, version)` and the ports take the caller's snapshot (D-4, D-5). Not accepted: re-judging capabilities at launch. The gate decides on the version known at admission; an upgrade between admission and launch is the same time-of-check gap 0.8.0 has whether observed or not, and is independent of the client choice. Documented as residual risk in D-4 |
| 2 | BLOCKING | Guard probing through `CliVersionProbe` (which uses `CliRunner.execute`) is circular; ignores back-off and budget; `checkAvailable` does not launch | Accepted | Verified (`CliVersionProbe` line 55). The guard no longer probes: it reads `ClientCheckLookup` (port, implemented by `NodeVersions`); key changed without a check → `CLI_UNAVAILABLE` "changed during this call"; recorded failure or first use → launch as the gate allows today (a failed probe is "not evaluable", `VersionGate.probeClient`); only `run`/`runSession` are guarded |
| 3 | BLOCKING | A reconstructed fixture omitted from `RECONSTRUCTED.txt` passes as recorded | Accepted | Positive provenance: mandatory `<case>.origin` (`reconstructed …` / `recorded …`, the latter written only by the record scripts); missing origin fails; release guard requires `recorded` for every catalogue case (D-10) |
| 4 | SHOULD | `.ps1` is the Windows invocation recorder (`win_*`), not a copy of the `.sh` catalogue; catalogue lacks `-h`, 401, 404 | Accepted | Verified (`record-fixtures.ps1` header, `record-fixtures.sh` `list_cases`). Catalogue = `.sh` 9.0 cases ∪ `.ps1` `win_*`; `.ps1` gets `-InubitLine 9.0`; `.sh` gets `help`, `rest_auth_failed`, `rest_not_found`; non-recordable cases listed in `NOT_RECORDABLE.txt` with their live-check step |
| 5 | SHOULD | Old signatures `available(node)`, `warning(node)` in D-7/D-19 | Accepted | Both take the version snapshot |
| 6 | SHOULD | "URLs carry no credentials" is stronger than the check (`cli.url` may have a query) | Accepted (wording) | Verified (`ConfigLoader` 675–717: query/fragment checked for `baseUrl` only). Statement narrowed to "no user info"; behaviour of the existing texts unchanged (0.8.0, FR-009) |

## Plan — round 3 (rubber-duck, 2026-11-06)

Feasibility confirmed by the reviewer: `ClientCheckLookup` needs no recursion, `NodeVersions` is built before `CliRunner` in `Wiring`, layering holds, no FR-009 regression in the reviewed paths; round 1 #7 partial rejection justified.

| # | Severity | Finding | Decision | Reason |
|---|----------|---------|----------|--------|
| 1 | BLOCKING | A write admitted on 9.0 is still sent after the node is observed as 9.1 (checked 9.1 client); the runner cannot see the admission version | Accepted (modified) | Verified: `Admission` carries no version; ~15 admission sites, some via `FanOut`, so carrying a permit through every port is a large change. Instead: write commands are marked with their capability (`CliCommand.write`, enforced in `build()`) and re-judged at launch by `LaunchRecheck`, which reuses the gate's extracted write rule on the version read at launch. Same outcome as the reviewer's "re-evaluate the specific capability"; reads are not re-judged (not destructive). Round 2 #1 residual risk reduced to unobserved changes |
| 2 | BLOCKING | Live steps do not produce REST 403/500/503, so "confirmed by the live check" is false | Accepted | Verified (quickstart steps 6–9). Write successes are confirmed by steps 8–9; 403/500/503 stay documentation-derived with a status-driven, contract-tested mapping (the body feeds only a scrubbed excerpt); D-10, quickstart 12 and Complexity Tracking say so |
| 3 | SHOULD | Closed world one-directional for contract-test use | Accepted | `V90Cases` registry is the only fixture access of the 9.0 contract tests; catalogue ∪ non-recordable must equal the loaded set. Windows `win_*` cases dropped from the 9.0 catalogue: verified that `fixtures/windows` does not exist and no test consumes `.ps1` output; `.ps1` stays unchanged, 9.0 on Windows is live step 11 (also revises round 2 #4) |
| 4 | NIT | D-7 still names `available(node)` | Accepted | Removed |

## Plan — round 4 (rubber-duck, 2026-11-06)

| # | Severity | Finding | Decision | Reason |
|---|----------|---------|----------|--------|
| 1 | BLOCKING | The one-shot re-check leaves a window before `launcher.launch`; needs a lease held until the process start | Decided by the person | Verified (`CliRunner.run` 191–201, `NodeVersions.observed`). Asked the person: the write race exists in 0.8.0 as well; closing it needs a version lock plus purpose plumbing. Decision: "Als eigenes Folge-Feature nach 0.8.1 vormerken, in 017 akzeptieren". The round-3 write re-check is withdrawn; D-4 keeps only the client guard; residual risk and follow-up recorded (D-4, plan "Follow-up") |
| 2 | BLOCKING | `ImportPort`/`TagPort` serve both `DEVELOPMENT_WRITE` and `DEPLOYMENT`; adapters cannot know the capability | Moot (verified) | Verified (`V81PortSet` 20–37, `NodeDeployer`, `ImportService`). Part of the follow-up above |
| 3 | SHOULD | Write-name enforcement not closed-world | Moot | Write re-check withdrawn |
| 4 | SHOULD | SC-006 does not name the permanent exemptions | Accepted | SC-006 now names them (write successes, REST 403/500/503); `NOT_RECORDABLE.txt` content asserted exactly (D-10) |

Round 3 #1 is superseded by this decision; the round-2 #1 residual risk stands as written in D-4.

## Plan — round 5 (the person's correction, 2026-11-06)

The answer recorded in round 4 #1 ("follow-up after 0.8.1") was given on the person's behalf by
the project coordinator; the person corrected it: "Möchte ich noch in 017 schließen". Round 4 #1
and #2 are therefore reopened and closed in 017 (FR-018, SC-007, D-24, plan stage 5, T040–T046):
a `WritePermit` (node, write capability, admitted version) from every write guard is passed to
every state-changing port method and all callers (this also settles round 4 #2: the purpose
travels with the permit); `NodeVersions` implements `WriteLock`, a per-node lock that every
version-state change takes; a write is handed off (process start, E2E request) only inside the
lock while the known major.minor equals the admitted one, and the lock is left as soon as the
process started / the request was handed over. The same mechanism closes 0.8.0's 8.1 → 9.0 gap.
The follow-up note in plan.md is removed; D-4's residual-risk paragraph is replaced.

## Tasks — round 1 (rubber-duck, 2026-11-06)

Before the review the producer checked every named path against the repository: REST adapters live in `adapter/rest/v81/` (`V81InventoryAdapter` in `adapter/`), `ClientCheck` is new, `Timed` is the existing `NodeVersionState.Timed`, and 012 already placed `cli/version.*` and `rest/system_info.*` under `fixtures/v9_0/` (four direct readers). Reviewer coverage check: no FR, SC, D-n or data-model row without a task.

| # | Severity | Finding | Decision | Reason |
|---|----------|---------|----------|--------|
| 1 | BLOCKING | T026 names a record component `final` (Java keyword) | Accepted | Verified (data-model.md:35 `finalChoice`, contract JSON `"final"`). T026: `finalChoice` rendered as `"final"`, both asserted |
| 2 | BLOCKING | Fixtures (T030) before their contract tests (T031) breaks TDD | Accepted | T030 = red (`V90Cases` + contract tests, missing `.origin` fails), T031 = green (fixtures, production fixes only where a test shows a 9.0 difference) |
| 3 | SHOULD | Dependency graph lets tasks run before prerequisites (T027→T028, T030→T031, T015→T018, T014→T025, T034→T035/T036) | Accepted | Dependencies and parallel section rewritten |
| 4 | SHOULD | T010 `[P]` but may change `CliRunner.java` like T012 | Accepted | T010 and T012 lose `[P]`; T009 → T010 → T011/T012 sequential (T012 also touches `CliRunner`) |
| 5 | SHOULD | Probe bypass of the launch guard not tested | Accepted | T017 adds: with only key A checked, `-v`/`-h` for key B still execute; `checkAvailable` not guarded |
| 6 | SHOULD | No test for `cliClients` under a group/node | Accepted | Verified (configuration-delta.md:54). T007 adds both cases with path and no value echo |
| 7 | NIT | Phase 5 checkpoint without code-review | Accepted | Added |

No blocking finding open.

## Tasks — round 2 (rubber-duck, 2026-11-06)

Round-1 fixes verified as applied; no new `[P]` collision, cycle or TDD defect.

| # | Severity | Finding | Decision | Reason |
|---|----------|---------|----------|--------|
| 1 | BLOCKING | `ClientKey`/`ProbedClient` created in T015 but used by T009 (`Selection`) and T014 | Accepted | Both types (with `ClientKeyTest`) move to the start of T009; T015 keeps only `NodeVersionState` |
| 2 | SHOULD | `T010 → (T011, T012)` reads as parallel branches | Accepted | Chain `T009 → T010 → T011 → T012` |

No blocking finding open.

## Analyze (speckit-analyze, 2026-11-06)

Coverage 23/23 (FR-001…FR-017, SC-001…SC-006); no CRITICAL; no constitution conflict (I: 9.0 writes use the unchanged 8.1 gates and audit; V: 9.0 access in adapters, selection in config; III: red/green per task).

| ID | Severity | Finding | Decision |
|----|----------|---------|----------|
| C1 | HIGH | SC-005 / Constitution II: no explicit secret-leak test for new texts, logs, JSON excerpts, `--check-config` | Fixed: "leak check" rule with `TestSecrets` in the header, named in T009, T011, T017, T024, T025, T029 |
| C2 | MEDIUM | SC-002 (switch by `versionLine` only) untested | Fixed: case added to T033 |
| C3 | LOW | FR-016, SC-001, SC-004 covered without ID reference | IDs added to T032, T037, T038 |

## Implementation — phase 1 (baseline)

T001: `mvn -o -q clean verify` on the merged branch (86bb40e, no source change since): 4998 tests, 0 failures, 0 errors, 51 skipped. SC-003 reference.

## Implementation — phase 2 (T002–T006, code-review)

Result before the fixes: 5037 tests, 0 failures, 51 skipped (green; `GitCliTest` needs the
Homebrew git without `GIT_*` variables, environmental).

SC-003 audit of the touched test classes (T005):
- **9.0 → 9.1.0 example moves** (only the version string; "undeclared version" now 9.1):
  `DevelopmentWiringTest`, `ArchiveExportServiceTest`, `ArtifactCheckServiceTest`,
  `CapabilityExtensionTest`, `DeployGuardTest`, `DeployServiceVersionTest`, `DiagnosisServiceTest`,
  `WorkspaceExportTest`, `HealthServiceTest` (`UNVERIFIED_910`), `E2eTestServiceTest`,
  `ImportServiceVersionTest`, `ProcessControlServiceTest`, `RestoreDeploymentTest`,
  `TagServiceTest`, `InventoryServiceTest`, `VersionGateTest` (7 tests), `ListNodesToolTest`.
- **Tests about 9.0 itself, expectation changed by FR-001**: `CapabilityMatrixTest` (9.0 =
  8.1), `FindProcessesToolTest` and `GetInventoryItemToolTest` (9.0 fixture, now no version
  warning; renamed `…CarriesNoVersionWarning`).
- **Additions only**: `MajorMinorTest` (new), `VersionSettingTest`, `ConfigLoaderTest`,
  `PortSetRegistryTest`, `AdapterGatewayFactoryTest` (8.1 → 9.0 rebuilds the ports).
- **Kept at 9.0.7** (independent of the production matrix): test matrices, client-range
  exclusion, pin contradiction, fixture parser, 9.x line.

| # | Severity | Finding | Decision | Reason |
|---|----------|---------|----------|--------|
| 1 | SHOULD | T005 marked done without the SC-003 class list | Accepted | List above |
| 2 | NIT | `productionRegistersOnly…` builds `v81Default()`, not the production registry | Accepted | Renamed `v81DefaultRegistersOnly…`; the production-like registry is covered in `PortSetRegistryTest` |

No blocking finding open.

## Design — round 1 of the write lock (rubber-duck, 2026-11-06)

Reviews the round-5 design (FR-018, D-24, T040–T046). All findings verified against the code.

| # | Severity | Finding | Decision | Reason |
|---|----------|---------|----------|--------|
| 1 | BLOCKING | `WritePermit` excluded `E2E_TEST`; T042 gave every `DevelopmentGuard` result `DEVELOPMENT_WRITE` | Accepted | Verified (`DevelopmentGuard` `Operation.capability()` → `E2E_TEST`; restore of a deployment backup admitted as `DEPLOYMENT`). Permit accepts every `WRITE` capability; the guard uses the admission's capability; a test per operation |
| 2 | BLOCKING | A non-fair lock lets a later hand-off barge past a queued version update | Accepted | Fair `ReentrantLock(true)`, blocking `lock()` only; ordering test (A held, update queued, B queued → update first, B refused) |
| 3 | SHOULD | Nested hand-off would keep the lock across the inner wait | Accepted | `handOff` inside a hand-off on the same thread → `IllegalStateException`, like `update` |
| 4 | SHOULD | Recommending `restore_backup` after a refused rollback can import 8.1 exports into 9.0; manifests carry no version | Accepted (modified) | The rollback warning after a version change names both versions and says to restore only on the admitted version. Recording the version in the manifest and refusing `restore_backup` on a mismatch is not added: the gap exists in 0.8.0 for any upgrade between deploy and restore, independent of the race; reported to the coordinator as a possible follow-up |
| 5 | SHOULD | T044 changed `SoapE2eClient` before T045 changed `E2ePort` (not green per task) | Accepted | `E2ePort` signature, its fakes and `E2eTestService` move into T044; T044 now depends on T042, T045 on T044 |
| 6 | SHOULD | `RefusingPorts` implements `ImportPort`/`TagPort`/`ProcessControlPort` but was not named | Accepted | Verified (`adapter/RefusingPorts.java`). Named in T045 with every implementation in the same step |
| 7 | SHOULD | Race coverage lacked standalone `import_artifacts` and `tag_artifacts` | Accepted | T046 parameterized over all eight FR-018 tools |

## Target and release decisions of the person (2026-11-06)

- "Wenn Feature 017 fertig ist, soll es noch in den 0.8.0 Branch rein und dieser soll dann
  released werden." → target 0.8.0 instead of 0.8.1: CHANGELOG entries in `[Unreleased]` of the
  `0.8.0` branch; spec, plan, research D-23, tasks T038 adjusted; `origin/0.8.0` merged again
  (d09500b, 6.1 tests on Windows).
- "Ich bin mutig und will ohne Live Test releasen" → the live check is no release blocker any
  more: it is done after the release, the person accepted the risk. `V90RecordedFixturesTest`
  lists not-yet-recorded 9.0 cases but never fails (was: fail release builds); open live tasks
  T047/T048 carry this note; Complexity Tracking and D-10 updated.

## Design — round 2 of the write lock and the retarget (rubber-duck, 2026-11-06)

Round-1 fixes confirmed. Findings on the retarget/no-gate change:

| # | Severity | Finding | Decision | Reason |
|---|----------|---------|----------|--------|
| 1 | BLOCKING | plan.md still called the live check a release blocker; Constitution row III said "replace before release" | Accepted | Verified (plan.md 31–32, 99). Both rewritten: after the release, not a gate, T047/T048 |
| 2 | SHOULD | "release guard" left in the D-10 heading and the tasks strategy | Accepted | Renamed to provenance / not-yet-recorded listing |
| 3 | SHOULD | `RefusingPorts` missing in the data-model adapter row | Accepted | Added |
| 4 | SHOULD | T047 → T048 order and evidence of the live check missing | Accepted | T047 appends a dated live-check record; T048 records the replacement and the write-success comparison; T047 → T048 |

No blocking finding open after these fixes (confirmed by the reviewer in round 3).


## Implementation — stages 2–3, T007–T014 (code-review, 2026-11-06)

Implemented test-first. Full build before the review: 5127 tests, 0 failures, 0 errors, 51
skipped. Deviations from the tasks: `VersionGate` gets the D-8 next-step suffix through a new
constructor flag (`cliClientsConfigured`, set in `Wiring`); until T015 `VersionGate.probeClient`,
`HealthService` and `Launcher` pass the version to the probe and use `ProbedClient.version()`.
Changed existing expectations: `VersionGateTest` (two next steps on INUBIT 9.x now end with
`, or add defaults.cliClients."9.x"`; FR-009 unaffected because both nodes run 9.x), probe fakes
moved to `ClientKey.NONE`, `V61Connection*Test`, `CliVersionProbeTest`.

| # | Severity | Finding | Decision | Reason |
|---|----------|---------|----------|--------|
| 1 | SHOULD | A `V9_X` node with an observed 9.x version is told to add `defaults.cliClients."9.y"`, but a line without minor never chooses an entry (FR-011, D-3): a hint loop | Accepted | Verified (`EffectiveNodeConfig.choose` sets `wholeMajorLine`; `entryHint` and `VersionGate` ignored the line). Both now name no entry for a line without minor; red-green tests `CliRunnerMessagesTest.aLineWithoutMinorKeepsThe080TextEvenWithAnObservedVersion`, `VersionGateTest.aLineWithoutMinorNamesNoEntryEvenAfterAnObservation` |

The reviewer confirmed the two changed `VersionGateTest` expectations and the 0.8.0 texts for
profiles without `cliClients` and without 9.x nodes; the no-JDK hint names both JDKs for every
profile, as T011 requires. No blocking finding open. Full build after the fix: 5131 tests, 0
failures, 0 errors, 51 skipped.

## Implementation — stage 4, T015–T022 (code-review, 2026-11-07)

Implemented test-first. Full build before the review: 5186 tests, 0 failures, 0 errors, 51
skipped. Deviations: `anyCandidate` means "a client home is configured" (0.8.0 announced the
export tools whenever `cli.home` was set, FR-009); `Launcher.probeClients` (`--check-config`)
keeps `ClientKey.NONE` until T025; small additions `clientFailureFor`, `ClientCheckLookup.NONE`,
`NodeSummary.withCliAvailable`. Existing tests changed only for new constructors and the keyed
`clientProbed`; `CapabilityExtensionTest` selects `of(id -> true)` (a recorded probe implies an
installed client) — confirmed by the reviewer as not weakened.

| # | Severity | Finding | Decision | Reason |
|---|----------|---------|----------|--------|
| 1 | SHOULD | The launch guard refused `get_health` on 6.1 outside any race: `monitoringAccess` never checks the client, so after the first parallel `-v` probe recorded an excluding range every later health session was refused (FR-009, D-4) | Accepted | Verified (`HealthService.checkServerViaCli` → `V61HealthAdapter` → `V61Connection.session` → `CliRunner.runSession` → `checkLaunch`). Health uses the unguarded `V61Connection.healthSession` → `CliRunner.runHealthSession`; every other tool path stays guarded (all checked: they pass a gate admission with the CLI client check). Red-green `V61LaunchGuardTest` with real `CliRunner` and `NodeVersions`; D-4 and data-model updated |
| 2 | NIT | `checkLaunch` read the check state twice; a probe recording in between gave a false "changed" | Accepted | `ClientCheckLookup.recorded(node, key)` answers from one snapshot; red-green racing test at the guard; T016 text updated |

No blocking finding open. Full build after the fixes: 5190 tests, 0 failures, 0 errors, 51
skipped.

## Implementation — stage 5, T040–T046 (code-review, 2026-11-07)

Implemented test-first. Full build before the review: 5275 tests, 0 failures, 0 errors, 51
skipped. Deviations: `stale` refuses a hand-off only on `AUTO` nodes (pinned and line nodes
ignore it, as the gate does; D-24 and data-model updated); the contract got the stale variant of
the rollback warning; other rollback failures keep the 0.8.0 text. Existing tests changed only
for the new permit parameters or gained permit assertions; harnesses run with the real lock.

| # | Severity | Finding | Decision | Reason |
|---|----------|---------|----------|--------|
| 1 | SHOULD (near BLOCKING) | A call's own write failing with `UNEXPECTED_RESPONSE` marks the node stale through `StaleReportingPorts`; on `AUTO` the hand-off then refuses the same call's rollback without any version change (FR-009) | Accepted (own design) | Verified (`StaleReportingPorts.call` → `observer.stale`; `NodeVersions.handOff` treats stale `AUTO` as unknown). No blanket rollback exemption: a mark raised by a write port carries that write's permit (`staleAfterWrite`); `handOff` ignores a mark owned only by its own permit (identity), a foreign or mixed mark still refuses. Today's 8.1 import/tag adapters map protocol trouble to `IMPORT_FAILED`, so the case is reachable only for restart/kill (no rollback) — the fix is protective; covered by `OwnStaleMarkTest` (test wrapper below the real `StaleReportingPorts`), `NodeVersionStateStaleMarkTest`, new `WriteLockTest` cases; mutation check done |
| 2 | NIT | A rollback refused partway said "was not sent" although earlier parts were re-imported | Accepted | `NodeDeployer.reimport` records sent parts; warning "was not completed …; it re-imported X before" vs. "was not sent"; both tested |

Contract wording "in the step's report" corrected to the failure message (in `deploy_release` the
node's failure message). No blocking finding open. Full build after the fixes: 5296 tests, 0
failures, 0 errors, 51 skipped.

## Implementation — stage 7, T023–T026 (code-review, 2026-11-07)

Full build before the review: 5353 tests, 0 failures, 0 errors, 51 skipped. Tests were written
first; for T025/T026 they were not run red before implementing (the reviewer confirmed the new
cases test the new behaviour). Changed expectations: the contract's `V9_X` warning text; a pinned
9.0.7 node shows client and address; `-v` runs once per shared client in `--check-config`
(output unchanged); `list_nodes` always carries `startCli` (JSON `final`). The reviewer found no
output change for profiles without `cliClients` and without 9.x nodes (FR-009).

| # | Severity | Finding | Decision | Reason |
|---|----------|---------|----------|--------|
| 1 | SHOULD | A `cliClients` entry whose home has no StartCLI script passed `--check-config` without a warning (FR-006, D-17) | Accepted | Entry-worded "… bin/startcli.sh not found …" warning, added to the contract table; on Windows a refused/network home keeps only its refusal warning (no host name in output); red-green tests |
| 2 | NIT | The `--check-config` example in docs/setup.md lacked the per-client lines | Accepted | Lines added (and the group line corrected); `MainTest` now checks the excerpt against real output |
| 3 | NIT | The `list_nodes` leak test still forbade the CLI home although `startCli.clientDirectory` must show it | Accepted | Removed from the forbidden list; new test with the real selector: the home appears only in `clientDirectory` |
| 4 | NIT | The entry probe ran through the first node's settings, so failures named that node or came from its trust store/URL | Accepted | Entry JDK resolved first with an entry-worded warning; probe on a neutral copy (no trust store, neutral URL); tests. A probe timeout may still name the first node (accepted, rare) |

No blocking finding open. Full build after the fixes: 5360 tests, 0 failures, 0 errors, 51
skipped.
