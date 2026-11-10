# Implementation reviews — Feature 019

## Baseline (T001)

- `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-019-tmp` on 556fc7f
  (0.8.0 + the 019 documents), JDK 25.0.5 / Maven 3.10.0: surefire 5495 tests, 0 failures,
  1 error, 52 skipped. The plan's figure (4998 / 51) was outdated.
- The error was environmental: `GitCliTest.hooksSigningAndLineEndingSettingsOfTheRepositoryHaveNoEffect`
  writes `.git/hooks/pre-commit`, but git 2.56 no longer creates `.git/hooks` on `init`. The test
  now creates the directory first (test-only change, same assertion); it passes alone.
