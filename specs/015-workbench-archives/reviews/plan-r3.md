# Plan review – round 3 (2026-11-05)

FEATURE: specs/015-workbench-archives  ROUND: 3  VERDICT: APPROVED

All six round-2 findings are resolved; code references spot-checked and accurate; coverage complete;
constitution check honest. Seven minor findings, each settleable inside its task:

1. T025 walks "Q1–Q29" but the table ends at Q31; Q31 is automated → "Q1–Q29 and Q31".
2. R-15's `--check-config` wording differs from contracts/configuration-delta.md and T016; use the
   contract's wording.
3. `NetworkPathProbe.remote(Path)`: on the Ubuntu CI host `Z:\x` has no root; take the drive letter
   (`remoteDrive(char)`) or read `<X>:\` from the path string.
4. T009/T019: define the full writer result (written, failed, notWritten, warnings) in T009, or make
   T019 depend on T010.
5. T010/T011 "three-workflow group of Q1": no fixture has exactly three workflows; name the source.
6. T020 concurrency test: force both calls past the collision check (latch in the fake
   `ArtifactPort` or the `FileOps` seam) before either writes.
7. T005/R-6: the shared `ArchiveReferences` functions take the bytes to scan (plus the reference
   source), not the redacted model types.
