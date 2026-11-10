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
