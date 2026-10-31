# INUBIT 6.1 StartCLI fixtures

StartCLI runs of an INUBIT 6.1 node, replayed offline by `FakeProcessLauncher` (feature 011,
research D-15). The case layout is the one of `../../v8_1/cli/README.md`: one case =
`<case>.stdout`, `<case>.stderr`, `<case>.exit` (the exit code as text). `V61FixturesShapeTest`
checks the shape.

## Source

**Reconstructed from the vendor documentation of inubit 6.1 until recorded.** No 6.1 node has been
recorded yet. Reconstructed cases follow the option names, column names and formats the
documentation describes; their text is written for the tests, not copied from it. Every
reconstructed case is listed in `RECONSTRUCTED.txt` (`<case> <capability>` per line, `#`
comments). A capability is declared supported for 6.1 only when none of its cases is listed there
any more (FR-005); the recordings that replace the reconstructed cases are made by hand as in
`specs/011-inubit-61-cli-reads/quickstart.md` and neutralized with `tools/neutralize.py`.

## Neutral names

Reconstructed cases use only neutral names: node `legacy/node1`, owner `jdoe`
(`Doe, John`, `jdoe@example.test`), host `inubit-legacy-1.example.test`.

## Cases

None yet; each case gets a row here and a line in `RECONSTRUCTED.txt` when it is added.

| Case | Capability | Kind | What it is |
|---|---|---|---|
