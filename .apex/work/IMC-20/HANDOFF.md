# Handoff — IMC-20 · Verifying → Awaiting approval (M6) · 2026-11-20 · run verifier-1

## Status
Verification of PR https://github.com/D4lD4r/inubit-mcp-server/pull/105 at head `3167b749` (CI
head; this handoff commit follows it) against base `bca0f0ae` is complete. The evidence is
complete and no finding blocks. The effective risk class is low, but the touch-set check does not
pass as the card words it: the PR changes `src/main/.../application/VersionGate.java` (T016),
which is not a test seam, and modifies the existing `VersionGateTest`, which the touch set does not
name. The card therefore goes to `Awaiting approval` (M6) so the Product Engineer decides. It is
not merged.
Mandate: IMC-20 card description (m1–m4 of the IMC-9 verifier evidence, comment 10034). Specs
referenced, unchanged except the one m1 line in `specs/025-inubit-74-support/data-model.md`.

## Done since last handoff
- Assigned IMC-20 to the APEX Verifier account (read back).
- Built and tested the PR head in this worktree: `mvn -B clean verify` exit 0 in 11:17 min:
  11,249 tests, 0 failures, 0 errors, 62 skipped, plus ITs 16 + 4. Two earlier runs on the shared
  machine under a load average of ~437 failed one timing test each (`GitHubReleaseSourceTest`
  500 ms timeout; `NoSecretLeakTest` AWS case, REST 500 ms timeout); both classes are untouched by
  the PR, and the run after the machine restart was fully green.
- GitHub CI on `3167b749` green: CI run 44707436666 (build, windows), CodeQL run 44707431192.
  Unit tests vs `bca0f0ae` (CI run 44620582699): Linux 11,244 → 11,249 (skipped 61 → 61),
  Windows 11,406 → 11,411 (skipped 158 → 157); ITs 16 + 4 unchanged on both jobs.
- m4 test-first check, run locally with pwsh 7 on macOS: the PR's `RecordFixturesPs1Test` against
  the `bca0f0ae` script fails exactly one case, `refusesARecordingThatEchoesThePassword[2]`
  (UTF-16LE; output "kept" instead of exit 3); UTF-8, clean-output and T023 cases pass on base.
- T023 mutation check (base script, pwsh): the launch test fails on each of three mutations of
  `Invoke-CaseFailure` (URL before the arguments, client TLS options on a v74 case, extra stdin).
- README anchors: all 19 local anchor links resolve to headings; on base exactly one did not
  (`docs/setup.md#4-credentials-credential-store`, a raw `<a id>`).
- Traced m1–m4 and every success criterion (see the evidence comment on IMC-20).

## Next step
Product Engineer decides on the touch-set deviation (M6 → Awaiting approval). Accept → Integrating
(then merge, M8 sampled review as the card says); reject → Executing with the reason (e.g. revert
the VersionGate.java change and drop the T016 part).

## Open questions
- For the Product Engineer: accept the production change in `VersionGate.java` (T016 pin naming,
  output identical) and the unnamed `VersionGateTest` edit as within the mandate? The card's scope
  asks for T016, which can only be done in `src/main`, while the touch set allows `src/main` only
  for a test seam — the card contradicts itself here.

## Decisions received
- Mandate IMC-20 (card description) by Daniel Decker; assignment rule (verifier assigns itself)
  via the coordinator.

## Tried and discarded
- None in verification.

## Assumptions, risks, gotchas
- `VersionGate.pin(line)` adds an `IllegalArgumentException` path for a CLI-only line that is not
  `major.minor`; unreachable with today's matrix (`6.1`, `7.4`), where the old code printed a
  string. Existing exact-text VersionGateTest cases (feature 025 T016) prove the output is
  unchanged.
- The recorder was changed and tested offline only; never run. No INUBIT contact in verification.
- Timing tests with 500 ms budgets (`GitHubReleaseSourceTest`, `NoSecretLeakTest`) fail under
  heavy local load; not caused by this PR; candidates for IMC-15 together with the
- Pre-existing Windows flake `WindowsProcessTreeTest` (temp-dir cleanup) seen by the executor in
  one run; untouched by this PR; belongs to IMC-15.
- Jira: the verifier service account cannot read `/myself` (401, scope); the accountId comes from
  its own comments. A comment sent with a transition is dropped, so post it separately.

## Claims for verification
- Verified by the verifier, not claimed: see the evidence comment on IMC-20.

## Budget
Not tracked by the pilot setup.
