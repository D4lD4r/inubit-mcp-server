# Handoff — IMC-9 · Verifying → Awaiting approval (M6) · 2026-11-19 · run verifier-1

## Status
Verification of PR https://github.com/D4lD4r/inubit-mcp-server/pull/104 at head `b01790ae` (a
handoff commit after the green CI head `d7375c24`) is complete. The evidence is complete and no
finding blocks. The risk class is medium, so the card goes to `Awaiting approval` (M6). It is not
merged.
Spec: `specs/025-inubit-74-support/` Mandate A (US1–US5, FR-001 – FR-020 incl. FR-010a, FR-016a),
pinned `2bfe55e7`; spec.md is unchanged since the pin. Plan and tasks in the same folder
(T001–T025 all `[X]`).

## Done since last handoff
- Built and tested the PR head in a separate worktree: `mvn -B clean verify` exit 0, 11,260 tests,
  0 failures, 0 errors, 62 skipped (plus 4 tests in a second run). GitHub CI on the PR is green
  (build, windows, CodeQL).
- Traced US1–US5 and FR-001 – FR-020 to tests (V74RefusalTest, VersionLineRegressionTest,
  V74FixturesShapeTest, V74RecordedFixturesTest, V74HelpContractTest, RecordFixturesPs1Test and the
  T005–T017 unit tests). No gap found.
- Reviewed safety and contracts: refusal before any CLI or REST call on 7.4, V74PortSet with
  refusing ports and no REST client, refusal texts against `contracts/mcp-tools-delta.md` (incl.
  IMC-14 option 1), `list_nodes` schema against docs, recorder password and neutralisation path,
  6.1/8.1/9.0 regression baseline, architecture tests, fixtures.
- Checked the touch set (128 files; extra paths are named by plan.md and tasks.md), the non-goals
  (no Mandate B work), and the reviews (all 25 tasks, clean-up, plan and spec end APPROVED).
- Evidence package: comment 10034 on IMC-9 (2026-11-19, verifier), then M6.

## Next step
M8 sampled review by the Product Engineer (risk class medium). Read the evidence package on IMC-9,
sample the PR, then accept (→ Integrating, then merge) or reject with a reason (M9 → Executing).

## Open questions
- None blocking. [IMC-14](https://danieldecker.atlassian.net/browse/IMC-14) is answered and applied
  (`9a1cdf43`); its QC2/QC5 moves are the Product Engineer's.

## Decisions received
- M1 by Daniel Decker (card description). IMC-14 option 1 by Daniel Decker: the `get_health`
  refusal on 7.4 names no tool. Both applied.

## Tried and discarded
- None in verification.

## Assumptions, risks, gotchas
- Executor readings checked and accepted: `get_health` stays announced (FR-009, feature 009);
  `HealthService` keeps its own client comparison until T031 (Mandate B), with no 7.4 effect
  because `get_health` refuses first.
- One RecordFixturesPs1Test assertion moved from `Assert-ExportInputs` to `Assert-CliUrl`; the host
  allowlist now applies to every case that contacts a server (stronger, not weaker).
- The recorder session mode is verified only against fakes; the recorder was never run (Mandate A
  is offline only).
- Trap for Mandate B: `CapabilityMatrix.lines()` skips 7.4 only while every capability has a note.
- Minor findings for the executor (non-blocking): see the evidence package, items m1–m6 (open task
  minors, a broken README anchor, Windows coverage of the `--check-config` probe, UTF-8-only secret
  check in the recorder, stale resume path in the previous handoff).
- Jira: the verifier service account cannot read `/myself` (401); issue, comment and transition
  calls work. A comment sent with a transition is dropped, so post it separately.

## Claims for verification
- Verified by the verifier, not claimed: see the evidence package on IMC-9.

## Budget
Not tracked by the pilot setup.
