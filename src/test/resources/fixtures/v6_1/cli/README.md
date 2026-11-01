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

## Health session layout (reconstructed)

A health session is one `CliRunner.runSession` run (research D-5, D-6): arguments
`-u jdoe <cli.url>`, stdin = the password line, then `uname`, `uptime`, `maintenance`, `free -m`,
`ps -c -e`, `ps -c -w`, `ps -c -q`, `ps -c -a` and `exit`, one per line. The reconstruction
assumes:

- stdin is not echoed; after `Password: ` the client prints the prompt `cli> ` before reading
  each line, and a command's output starts right after its prompt on the same line (further lines
  follow without prompt). So the output has one prompt per line sent after the password (9), and
  the blocks between prompts belong to the commands in the order sent; the last prompt is the
  one `exit` answers, without output.
- `uname`: `INUBIT Process Engine 6.1.0.89`; `uptime`: the alive check (`Process Engine is
  alive (up …)` or `Process Engine is not alive`); `maintenance`: `Maintenance mode: off|on`;
  `free -m`: three lines `Used memory: <n> MB`, `Free memory: <n> MB`, `Max memory: <n> MB`;
  `ps -c <state>`: `Total: <n>`.
- German labels (`health_session_de`): `Process Engine ist aktiv (läuft seit …)`,
  `Wartungsmodus: aus|ein`, `Belegter Speicher`, `Freier Speicher`, `Maximaler Speicher`,
  `Gesamt: <n>`; the client's own texts (`Password: `) stay English (`JAVA_TOOL_OPTIONS` sets the
  English locale).
- Values (those of the `get_health` example in `contracts/mcp-tools-delta.md`): version
  `6.1.0.89`; memory used 1210, free 838, max 2048 MB; counts Error 3, Waiting 12, Queued 0 and
  all 15, so all − Error − Waiting − Queued gives Processing 0.
- A rejected login and an unreachable server end before the first prompt with the
  `@Start@LoginFailure` / `@Start@LoginFailed` markers and the texts known from the 8.1
  recordings, which `CliOutputClassifier` and the credential guard read; exit 1.
- stderr holds the JVM line `Picked up JAVA_TOOL_OPTIONS: …` of the runner's environment.

## Process session layout (reconstructed)

A process session is one `CliRunner.runSession` run for `find_processes` (research D-5, D-10):
the password line, then one `ps <-a|-e|-w|-q> -l 10000` per listing and `exit`. Prompt and
password handling are those of the health session; one prompt per listing plus the empty one
answering `exit`. The reconstruction assumes (REC: to be confirmed by the recordings):

- A listing is a header line, one row per instance and `Total: <n>` with `n` = number of rows.
  An empty listing is the header and `Total: 0` (as the 8.1 recordings show it).
- Fixed-width columns in the documented order `NODE UID PID PRIO STATE DATE WORKFLOW MODULE`;
  each column starts where its header name starts (widths 21, 16, 11, 11, 11, 21, 21, then
  `MODULE` to the end of the line). Values are shorter than their column. Lines carry no trailing
  blanks, so a row without module ends after the workflow name.
- `NODE` is the INUBIT node or `__NotAssigned__`; `UID` the owner; `PRIO` `low`, `normal` or
  `high`; `DATE` the ISO local date-time `uuuu-MM-ddTHH:mm:ss` without zone; `MODULE`
  `Name(id)` as on 8.1.
- `ps -a` lists every state including `Processing` and `Retry`; `ps -w` lists `Waiting` only
  (not `Retry`), `ps -e` `Error`, `ps -q` `Queued`. `Suspended` stands for a raw state the server
  does not map (`OTHER`).
- German labels (`ps_session_de`): header `KNOTEN BENUTZER PID PRIO STATUS DATUM WORKFLOW MODUL`
  (same column starts), states `Fehler` (Error), `Aktiv` (Processing), `Wartend` (Waiting),
  `Wiederholt` (Retry), `Eingereiht` (Queued), `Angehalten` (unmapped, `OTHER`), dates
  `dd.MM.uuuu HH:mm:ss`, `Gesamt: <n>`; `__NotAssigned__` stays as it is.
- Rows: `ps_session_all` has nine instances on `node1`/`node2` (Error 3, Processing 1, Waiting 2,
  Retry 1, Queued 1, Suspended 1), not sorted by date; `ps_session_error`,
  `ps_session_waiting_queued` and `ps_session_de` hold the same instances (by `PID`).
- No fixture holds a listing at the row cap: tests that need 10,000 rows generate them from the
  row format above.

## Cases

Each StartCLI case has a row here and its lines in `RECONSTRUCTED.txt`.
`replay_selftest` is not StartCLI output and serves no capability, so it is not listed in
`RECONSTRUCTED.txt`.

| Case | Capability | Kind | What it is |
|---|---|---|---|
| `replay_selftest` | – | test-only | marker output `v6_1 replay`, empty stderr, exit 3; lets `FakeProcessLauncherTest` (T002) check that a 6.1 case is replayed from this folder |
| `help` | monitoring, process diagnosis | reconstructed | `startcli.sh -h` of the 6.1 client, read by `CliClientProbe` (T017, research D-8): login, help and version options and the direct monitoring options; no trust-store, host-name or `--execCommand` option; exit 0 |
| `health_session_ok` | monitoring | reconstructed | health session: version `6.1.0.89`, alive, maintenance off, memory 1210/838/2048 MB, counts Error 3, Waiting 12, Queued 0, all 15; exit 0 |
| `health_session_maintenance` | monitoring | reconstructed | as `health_session_ok` with `Maintenance mode: on`; exit 0 |
| `health_session_de` | monitoring | reconstructed | as `health_session_ok` with German labels; exit 0 |
| `health_session_alive_failed` | monitoring | reconstructed | as `health_session_ok`, but `uptime` answers `Process Engine is not alive` (login succeeded, alive check negative); exit 0 |
| `login_failed` | monitoring | reconstructed | session whose login is rejected: `@Start@LoginFailure@@@…@End@` and `The user does not exist or password does not match.`, no prompt; exit 1 |
| `unreachable` | monitoring | reconstructed | session that cannot connect: `@Start@LoginFailed@@@Login to the server failed.@End@` with `java.net.ConnectException: Connection refused`, no prompt; exit 1 |
| `ps_session_all` | process diagnosis | reconstructed | process session `ps -a -l 10000`: nine instances in all raw states, `Total: 9`; exit 0 |
| `ps_session_error` | process diagnosis | reconstructed | process session `ps -e -l 10000`: the three Error instances of `ps_session_all`, `Total: 3`; exit 0 |
| `ps_session_waiting_queued` | process diagnosis | reconstructed | process session with two listings, `ps -w -l 10000` (two Waiting instances, `Total: 2`) and `ps -q -l 10000` (one Queued instance, `Total: 1`); exit 0 |
| `ps_session_empty` | process diagnosis | reconstructed | process session `ps -e -l 10000`: header only, `Total: 0`; exit 0 |
| `ps_session_de` | process diagnosis | reconstructed | as `ps_session_all` with German header, state names, dates and `Gesamt: 9`; exit 0 |
| `ps_session_unassigned` | process diagnosis | reconstructed | process session `ps -a -l 10000`: a Waiting instance on node `__NotAssigned__` and an Error instance without module, `Total: 2`; exit 0 |
