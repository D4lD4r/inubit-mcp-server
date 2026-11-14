FEATURE: specs/022-full-interface-coverage  ROUND: 5

Scope: the US3 changes after T039 (spec, plan, research D-1, D-2, D-6, D-14, D-15, D-21, live checks, "Live recording T039", "Decided after T039", data-model, both contracts, quickstart, tasks T039–T047a, T079, T104), checked against the owner decisions of 2026-11-14, the T039 fixtures, the existing code (`VersionGate`, `DiagnosisService`, `V81ProcessQueryAdapter`, `interface-coverage.tsv`) and the constitution. The reviewer had no shell and read the current files instead of `git diff HEAD`.

Owner decisions 1–7 are applied consistently in all artifacts: `module` from XML, no `moduleName`, `nodeByName` excluded on 8.1; `libraries` with `rowFormatVerified: false`; `metadata` and `checkIsConnector` stay planned (L-19, L-20); `xslt10Report` dropped; `userColumn` from the REST field `userDefined<i>`; `ownerWithoutGroups` exports technical workflows only; the `metadata` export takes registry names; `9.0-followup` used the same way in data-model, the coverage-list contract, D-2, T046 and T047; the US3 9.0 part moves to T039a, T046a and T047a. The tool count is unchanged at 9 new tools.

COVERAGE (changed or relevant to US3; all other rows unchanged from round 4):
- FR-002 → T007, T021, T044 (`fetchXSLT1.0` excluded on 8.1), T045 (`ps -u` covered), T046 (`nodeByName` excluded), T104 (L-19/L-20 fallback). No task for a positive L-19/L-20 result (finding 2).
- FR-004 → T046 (`WAITS_ITEM` + `9.0-followup`), T047, T046a, T047a, T104
- FR-005 → T044, T045
- FR-007 → T043, T044, T045
- FR-008 → T041, T044
- FR-009 → T043, T044 (`source: "CLI"`), T045
- FR-010 → T040, T043, T045
- FR-012 → T044
- FR-020 → T041, T044 (schema)
- FR-025 → T043 (`admitCall`), T046, T046a. The 9.0 gate for `userColumn` is unspecified (finding 1).
- FR-027 → T047, T047a
- US3 AS1 → T045, existing `find_processes`
- US3 AS2 → T040, T042, T043, T046
- US3 AS3 → T040, T042, T043, T046. No task runs L-21 (finding 3).
- US3 AS4 → T041 (no form), T044 (8.1 row excluded), T039a/T046a (9.0)
- US3 AS5 → T040, T045, T046
- US3 AS6 → export: T041, T044, T046; read: T047 (`waits` L-19), T104 (fallback only)
- US3 AS7 → T041, T044, T046
- US3 AS8 → T047 (`waits` L-20), T104 (fallback only)

FINDINGS:
- [major] T045 / contracts/mcp-tools-delta.md `find_processes` "Rules" – The mechanism that refuses `userColumn` on 9.0 (and 6.1) and allows it on 8.1 is not named. `VersionGate.admitCall` / `InterfaceCoverage.refusal` treat a `Covered` row as a refusal ("not sent by this server on …", VersionGate.java:630), so `covered:find_processes` of `ps -u` would refuse 8.1 too. `V81ProcessQueryAdapter` serves 8.1 and 9.0, so a hard-coded version check would end up in `DiagnosisService` (tool logic, Principle V). T046a lists no code change to lift such a check. – Name the gate in T045, e.g. a small `VersionGate`/`InterfaceCoverage` method that admits `userColumn` when the row `CLI ps -u` is `Covered("find_processes")` or `Reachable` for the node's line; a missing row (6.1) or `planned` is refused with `UNSUPPORTED_VERSION` "not yet verified". Add its tests, including one that flips the 9.0 row in a test coverage list and sees 9.0 admitted, so T046a's row flip is enough to enable 9.0.
- [major] T039a / T046a / T104 / mcp-tools-delta `read_model_details` "Planned, not offered in US3" – Only the failure path of L-19 and L-20 has a task (fallback in T104). No task builds `metadata` or `checkIsConnector` (parser, `RestEndpoint`, enum and schema, `ModelDetailsSecretLeakTest`, contract test, flip) if a live check records a working form; the same for `xslt10Report` on 9.0. – Add conditional tasks ("only if L-19 / L-20 / L-7 on 9.0 records a working form") with files and tests, or state that a positive result in this feature still leads to `excluded:` plus a backlog entry, with the reason.
- [major] research L-21 / T079 / D-14 / US6 `deleteAllLibraries` – No task runs L-21, so `rowFormatVerified` can never become `true`, while the same unverified `LibraryListParser` output is the preview list and re-read state of the destructive bulk change `deleteAllLibraries` (D-14; T085). T079 uploads a test library (`PUT /libraries`) on 8.1, the natural moment to record a non-empty list. – Add `library --list --type driver|plugin` after the upload to T079 as L-21; add a step to the US6 flip task that updates `LibraryListParser`, its fixtures and `rowFormatVerified`; while L-21 is open, either do not offer `deleteAllLibraries` or use the D-14 count-only preview with the sentence that the list format is not verified.
- [minor] spec.md Clarifications "Live recording T039" / FR-002 / T104 – Owner decision 8 (L-19/L-20 fallback at Phase 10) is missing from the clarification; the fallback reasons are not among FR-002's exclusion reasons. – Add decision 8; phrase the reasons in FR-002 terms or extend FR-002's list.
- [minor] plan.md Project Structure / data-model `ProcessUserColumn` – Listed as a new domain type, but T040 puts `userColumn` on `ProcessQuery` and `userColumnValue` on `ProcessInstance`; no task creates it. – Drop it from the plan's type list and describe it as the output view of `find_processes`, or have T040 create it.
- [minor] T039a / T046a – A 9.0 `queueLog` row with `userDefined1` set is already recorded (`fixtures/queue-entry/v9_0/error_entry.rows.json`, with `.origin`). – Use it for the 9.0 `userColumn` case and drop or relax the new recording.
- [minor] T039a – "otherwise its 9.0 rows get the 8.1 reason": the 8.1 reason names "INUBIT 8.1.17". – The 9.0 rows need a reason that names the recorded 9.0 version.
- [minor] T046 – Still tagged "[BLOCKED: T039]"; mixes the domain change to `CoverageEntry.WAITS_ITEM` into the contract-and-flip task. – Remove the stale tag; optionally move the `WAITS_ITEM` change to the start of T047 or its own task.
- [minor] quickstart.md – The US3 test row lacks `DiagnosisServiceTest` and the `ps -u` assertion in `InterfaceCoverageResourceTest` (T045); smoke step 3 does not exercise `ownerWithoutGroups` and `metadata` exports. – Add them.
- [minor] plan.md "Open questions" item 4 and research "Consequences for the plan (… owner decision needed)" – Both are decided now; increment 3 names T039a and T046a but not T047a. – Mark them decided/applied and add T047a.

VERDICT: CHANGES_REQUESTED
