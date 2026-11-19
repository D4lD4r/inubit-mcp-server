# INUBIT 7.4 StartCLI fixtures

StartCLI runs of an INUBIT 7.4 node, replayed offline by `FakeProcessLauncher` (feature 025,
research D-11). The case layout is the one of `../../v6_1/cli/README.md`: one case =
`<case>.stdout`, `<case>.stderr`, `<case>.exit` (the exit code as text). `V74FixturesShapeTest`
checks the shape.

## Source

**Reconstructed from the 7.4 help output and the 6.1 cases until recorded.** No 7.4 node has been
recorded yet. Reconstructed cases follow the 7.4 help output and the layout of the 6.1 cases in
`../../v6_1/cli/`; their text is written for the tests. Every case of the folder is in exactly one
of three lists (spec FR-015):

- `RECONSTRUCTED.txt` (`<case> <capability>` per line, `#` comments): the reconstructed cases. A
  capability is declared supported for 7.4 only when none of its cases is listed there any more
  (spec FR-030).
- `DERIVED.txt` (`<case> <source-case> <capability> <edit…>` per line, `#` comments): cases
  derived from a recorded case by a minimal, documented edit (spec FR-016a). They count as
  verified only while their source case is recorded.
- The section "Recorded cases" below: the recorded cases.

Recordings are made by hand with `tools/record-fixtures.ps1` as in
`specs/025-inubit-74-support/quickstart.md` and neutralized with `tools/neutralize.py`.

## Neutral names

Reconstructed cases use only neutral names: node `legacy74/node1`, owner `jdoe`, host
`inubit-legacy74-1.example.test`.

## Recorded cases

The single source for recorded cases (spec FR-015): one list item `- <case> <source>` per
recorded case, `<source>` naming where the recording came from (the recorder case, e.g.
`v74_help`). No case is recorded yet.
