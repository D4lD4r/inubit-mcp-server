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
