# Handoff — IMC-20 · Executing → PR open · 2026-11-20 · run executor-1

## Status
The IMC-9 verifier minors m1–m4 are fixed on branch `d4ld4r-imc-20-verifier-minors-m1-m4` in one
PR against main (base `bca0f0ae`). The PR is ready for review and not merged. Risk class low.
Specs referenced, unchanged except the one m1 line in
`specs/025-inubit-74-support/data-model.md` (Kind `export-backslash`).

## Done since last handoff
- m4: `tools/record-fixtures.ps1` `Assert-NoSecret` also matches the UTF-16 LE bytes of the
  password (013 FR-003). The RecordFixturesPs1Test cases were written first. The UTF-16LE case
  failed on `bca0f0ae` (the recording was "kept" instead of stopping with exit 3) and passes now.
  The cases run wherever PowerShell exists (pwsh on Linux/macOS, powershell.exe on Windows).
- m3 (025 FR-013): the MainTest `--check-config` 7.4 probe test is no longer
  `@DisabledOnOs(WINDOWS)`. On Windows it uses a `startcli.bat` stand-in.
- m1:
  - T014: the "not evaluable" check is one contiguous excerpt.
  - T016: the 404 hint builds each pin through `VersionSetting.Line#text()`. There is a new
    VersionGateTest case.
  - T017: the 6.1 client comparison uses a `6.1.0.89` pin (qa/node8).
  - T021: data-model Kind `export-backslash`.
  - T023: a RecordFixturesPs1Test case runs the real `Invoke-CaseFailure` for `v74_login_failed`
    with a stub StartCLI. It pins the arguments `-u mcp-record-no-such-user --execCommand uptime
    <CliUrl>`, standard input `not-a-password\n` only, the trust store options, and the secret
    check entry. A mutation check confirmed the case fails when the argument order changes.
  - V74RefusalTest Javadoc is within 100 characters.
  - README and setup say "(unless `knowledge.recording: false`)".
- m2:
  - The README credentials link points to `docs/setup.md#4-credentials-sources`. Every README
    anchor now resolves to a heading. On base, exactly one did not: it pointed to a raw
    `<a id>`, not to the section.
  - README no longer says 6.1 is "not on Windows".
- CHANGELOG: Unreleased → Documentation, Fixed, Tests.
- `mvn -B clean verify`:
  - local on the branch: 11,249 tests, 0 failures, 62 skipped, plus 16 and 4 ITs.
  - baseline `bca0f0ae`: 11,244 tests, 62 skipped, plus 16 and 4 ITs.

- Windows CI:
  - The first run lost its runner (infrastructure).
  - The rerun found a real bug in the m3 test: a Windows path in a double-quoted YAML scalar
    (`\U` escape). Fixed in `3f493ac5` with single quotes, CRLF-normalized output, and a hardened
    `.bat` stand-in.
  - The same rerun had a temp-dir cleanup error in `WindowsProcessTreeTest`: the `client` folder
    was still in use. The PR does not touch that class, so this is a pre-existing flake.
  - All 27 RecordFixturesPs1Test cases ran on Windows PowerShell 5.1, none skipped.

## Next step
Verifier: check the PR (CI build, windows, CodeQL) against the IMC-20 success criteria.

## Open questions
- None.

## Decisions received
- Mandate IMC-20 (card description) by Daniel Decker via the coordinator.

## Tried and discarded
- Adding a 7.4 version fixture under `fixtures/v7_4/cli` for the m3 test was discarded: it would
  change the lists of V74FixturesShapeTest. A hand-written `.bat`/`.sh` stand-in is used instead.

## Assumptions, risks, gotchas
- Deviation from the touch set: one production change in `VersionGate.java` (T016). The private
  `pin(line)` replaces `"V" + line.replace('.', '_')`. The output is identical for every line the
  matrix has (`V6_1`, `V7_4`), so there is no behaviour change. It is named in the PR.
- The recorder was changed and tested offline only, never run. No INUBIT contact.
- m5 and m6 are untouched (non-goals).

## Claims for verification
- The new UTF-16LE m4 case fails on `bca0f0ae` and passes on the branch head.
- All README anchors resolve to headings of the linked files.
- The 7.4 `--check-config` probe test runs (not skipped) on the Windows CI job.
- The branch has no fewer tests than `bca0f0ae` (+5).

## Budget
- One executor run, within the mandate.
