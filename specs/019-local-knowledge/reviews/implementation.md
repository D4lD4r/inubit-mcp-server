# Implementation reviews — Feature 019

## Baseline (T001)

- `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-019-tmp` on 556fc7f
  (0.8.0 + the 019 documents), JDK 25.0.5 / Maven 3.10.0: surefire 5495 tests, 0 failures,
  1 error, 52 skipped. The plan's figure (4998 / 51) was outdated.
- The error was environmental: `GitCliTest.hooksSigningAndLineEndingSettingsOfTheRepositoryHaveNoEffect`
  writes `.git/hooks/pre-commit`, but git 2.56 no longer creates `.git/hooks` on `init`. The test
  now creates the directory first (test-only change, same assertion); it passes alone.

## Implementation — phase 2 (T002–T007)

Build on 1240f95: surefire 5532 tests, 1 failure, 52 skipped (failsafe not reached). Code review
of `556fc7f..1240f95` by a review agent.

| # | Severity | Finding | Resolution |
|---|---|---|---|
| B1 | build | `NoCustomerIdentifiersTest` (local denylist) matched a word in a character-name comment of `CaseFolding-15.1.0-CF.txt` | the name comments of the data lines are removed (header and Unicode notice kept); the table test reads only the fields |
| R1 | minor | `NoteName.writableProblem` refused `.md` only at the end of the whole name, so `x.md/y` was writable: its folder `x.md` clashes with the file of note `x`, and the histories nest (D-11) | no segment may end in `.md` (any case), message `segment <n> ends in .md`; tests `x.md/y`, `a/x.Md/y`; D-4 and T003 wording updated |
| R2 | minor | `PackageWriter.defaultDirectory` was inserted between the constructor's Javadoc and the constructor | moved above the constructor's Javadoc |

All other points of D-2, D-4, D-6 and data-model.md were confirmed.

Build on 89184f2 + T008 (a0269e3): surefire 5542 tests, 0 failures, 0 errors, 52 skipped;
failsafe 2 tests, 0 failures.

## Implementation — phase 3 (T008–T011)

Build on 6d8cb9c: surefire 5581 tests, 0 failures, 0 errors, 52 skipped; failsafe 2 tests,
0 failures. Code review of `89184f2..6d8cb9c` by a review agent.

| # | Severity | Finding | Resolution |
|---|---|---|---|
| R1 | major | The allowance "inside `~/.inubit-mcp/<own profile>`" applied to every forbidden directory, so `workspace: ~` or `auditDirectory: ~/.inubit-mcp/acme` (or another profile's audit directory there) let the default knowledge directory lie inside a workspace or audit directory | the allowance holds only for the configuration directory and the folder of the configuration file (`Forbidden.mayHoldOwnData`); test `theDataDirectoryExemptionDoesNotCoverAWorkspaceOrAuditDirectoryHoldingIt` (fails without the fix); D-2 and the contract worded accordingly |

Targeted after the fix: `ConfigValidatorKnowledgeTest` 16, `ConfigValidatorTest` 189 (1 skipped),
`MainTest` 78 (3 skipped), 0 failures.

## Implementation — phase 4 (T012–T016)

Build on 54691ce: surefire 5679 tests, 0 failures, 0 errors, 52 skipped; failsafe 2 tests,
0 failures. Code review of `11c91ee..54691ce` by a review agent (no findings in
`NodeSecrets.awaitSettled` or `ProfileSecrets`).

| # | Severity | Finding | Resolution |
|---|---|---|---|
| R1 | major | A MIME-wrapped base64 keystore was joined with a prose line directly before it (`Truststore`) or after it (`Hello`): the decoded bytes no longer started with the keystore header or did not decode, so the keystore was neither masked nor refused (fail-open) | a continuation run must fill its line; a block holds runs of the width of its first run, only the last may be shorter; a block that does not decode is tried once more without its last run; tests `aProseLineDirectlyBeforeAWrappedKeystoreIsNotPartOfIt`, `aProseLineDirectlyAfterAWrappedKeystoreIsNotPartOfIt` (fail without the fix) |
| R2 | major | `START_TAG` backtracked quadratically on `<` followed by a long word without `>` (40,000 characters took about 5 s), against the linear bound of T012 | possessive quantifiers (same matches); test `aLongTagLikeWordWithoutClosingBracketIsScannedInLinearTime` (200,000 characters, 2 s bound; times out without the fix) |

Targeted after the fixes: `SecretFinderTest` 67, 0 failures.

## Implementation — phase 5 (T017–T024)

Build on 63c71fb: surefire 5829 tests, 3 failures, 0 errors, 56 skipped (failsafe not reached).
Code review of `04aaa8e..63c71fb` by a review agent (no findings on fail-closed reading, masking
before search, cut and page, logs, package boundaries, level words or the start-time wait).

| # | Severity | Finding | Resolution |
|---|---|---|---|
| B1 | build | `TerminologyRenderingTest`: the `ref` description of the `read_knowledge` output schema said "valid while the server runs" (a level word of the default terminology), and the read-only tool count was still nine | the description says "valid until the next restart" (schema and contract); the count is ten |
| R1 | minor | `KnowledgeService.search` computed `offset + limit` in `int`: an offset near `Integer.MAX_VALUE` wrapped and returned a negative `nextOffset`, against the output schema (`minimum: 0`) | the page end is a `long`; `nextOffset` only when hits follow; test `anOffsetNearTheLargestIntegerGivesAnEmptyPageWithoutANextOffset` (fails without the fix) |

Targeted after the fixes: `KnowledgeServiceSearchTest` 17 (1 skipped), `TerminologyRenderingTest`
8, `ReadKnowledgeToolTest` 11, 0 failures.
