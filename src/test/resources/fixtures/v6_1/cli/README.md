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

Each StartCLI case has a row here and its lines in `RECONSTRUCTED.txt`.
`replay_selftest` is not StartCLI output and serves no capability, so it is not listed in
`RECONSTRUCTED.txt`.

| Case | Capability | Kind | What it is |
|---|---|---|---|
| `replay_selftest` | – | test-only | marker output `v6_1 replay`, empty stderr, exit 3; lets `FakeProcessLauncherTest` (T002) check that a 6.1 case is replayed from this folder |
| `help` | monitoring, process diagnosis | reconstructed | `startcli.sh -h` of the 6.1 client, read by `CliClientProbe` (T017, research D-8): login, help and version options and the direct monitoring options; no trust-store, host-name or `--execCommand` option; exit 0 |
