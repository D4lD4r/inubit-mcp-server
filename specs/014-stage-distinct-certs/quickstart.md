# Quickstart: validating `tls.pinPerServer`

Feature: [spec.md](spec.md) · Contracts: [configuration.md](contracts/configuration.md),
[cli.md](contracts/cli.md)

## Prerequisites

- JDK 21 and Maven (server); `/usr/bin/python3`, `/usr/bin/openssl`, and the JDK's `keytool`
  (tool), as for feature 006.
- A built server JAR: `mvn -B -ntp clean verify`.
- No real INUBIT stage is needed. All scenarios run against local TLS servers and copies of a
  customer-agnostic profile. A real stage is marked only when the operator decides to.

## 1. Offline test suites

```bash
mvn -B -ntp clean verify
```

```bash
python3 -I -m unittest discover -s tools/tests -v
```

Expected:
- Both suites are green, including every unchanged 006 test (SC-005) and `NoCustomerIdentifiersTest`.
- The new cases cover:
  - configuration contract V-1 … V-11;
  - the SC-004 swap test for REST (cause `CertificateException`);
  - the StartCLI probe (FR-005a): a swapped certificate at the StartCLI address is refused and
    StartCLI is never launched; unmarked stages are not probed; the `TLS_ERROR` texts match the REST
    path. No real StartCLI or INUBIT is needed (fake process launcher, local TLS servers);
  - tool contracts C-1′ … C-5′.

Outcome (T030, offline, branch after merging `main`):
- `mvn -q -o clean verify`: green; 3643 tests, 0 failures, 0 errors, 3 skipped (the corpus tests
  `ArchiveCorpusTest`, `XsltCorpusTest`, `StructureCorpusTest`, which need a local corpus).
- Tool suite: 411 tests OK with Homebrew `python3` 3.15 and with `/usr/bin/python3` 3.9 (`-I -v`).
- `NoCustomerIdentifiersTest` with the local identifier lists present: 20 tests run, 0 skipped.
- `git diff origin/main -- tools/tests src/test`: 18 files, 3901 lines added, 19 removed; removals only in
  `tools/tests/support.py` (13, T002) and `TlsTestServer.java` (6, T011).

## 2. Configuration check (Story 1 AS 1, 5, 6)

Use the example of [configuration.md](contracts/configuration.md) with a local copy of the trust
store, then run:

```bash
java -jar target/inubit-mcp-server-*.jar --config /path/to/acme-copy.yaml --check-config
```

Expected:
- **Valid profile**: exit 0 and `Result: OK`; no error names `int/a` or `int/b` (the summary
  prints no TLS values). `--check-config` writes no FR-005 log line: that line is written only at
  server start (`Launcher.serve`), see §3.
- **Broken copies, one rule each (V-1, V-3, V-4, V-5, V-6, V-10, V-11, and `defaults.tls`)**:
  exit 1, and the message names stage and server as in the contract table.

## 3. Start-up log (Story 1 AS 7)

Start the server with the valid copy (credentials as in `docs/setup.md`).

Expected: one stderr line `Stages with one pinned certificate per server (tls.pinPerServer): int`,
and nothing but MCP traffic on stdout.

## 3a. StartCLI probe without INUBIT (FR-005a, SC-004)

Use the valid copy with `int` marked and `versionLine: V8_1` on `int`, plus:

- four local TLS endpoints with the throw-away certificates A and B of §4, all trusted by the
  stage trust store:
  - two HTTPS stubs, one with certificate A as `int/a`'s `baseUrl` and one with certificate B as
    `int/b`'s `baseUrl`, each answering every `GET` with `200` and `{}` (they only have to confirm
    the credentials, so each server passes its REST login and reaches the probe; if the REST login
    needs more, record what it needed and report it);
  - two bare TLS listeners (e.g. `openssl s_server`) as the `cli.url` of `int/a` and `int/b`,
    **swapped**: the listener at `int/a`'s `cli.url` presents B, the one at `int/b`'s presents A;
- `cli.home` of both servers pointing to a stub client directory whose `bin/startcli.sh` only
  creates a marker file and exits 0.

Start the server with this copy and call a CLI-backed tool for `int/a` through an MCP client, e.g.
`export_artifacts` (8.1 exports run through StartCLI).

Expected:
- the reply is `TLS_ERROR`, the message names `int/a`, the host and port of its `cli.url`, the cause
  `(CertificateException)` and "StartCLI was not started";
- the marker file does not exist (StartCLI was not started);
- the same for `int/b`; after swapping the listeners back (each `cli.url` presents its own
  server's certificate), the call reaches the stub (the marker file exists) and fails only because
  the stub produces no export.

## 4. Check, adoption and prune with two local TLS servers (Stories 1–2)

Set up two local TLS servers with throw-away certificates A and B, as `tools/tests/tls_fixtures.py`
does. Use a profile copy whose `int/a` pins A, `int/b` pins B, and whose stage trust store holds
A and B.

| Step | Command | Expected |
|---|---|---|
| a | `inubit-cert-check --config COPY --check` | `int` OK, no conflict, exit 0 (Story 1 AS 2) |
| b | `int/b` now serves a new certificate F; `--check --json` | `int/b` `changed` with `offer` F, `int/a` `ok`, stage `conflict: false`, exit 10 (Story 2 AS 1) |
| c | `--accept int` F | refused, exit 3, "name the server" (Story 2 AS 7) |
| d | stop `int/a`; `--accept int/b` F | adopted; only `int/b`'s pin line changed (diff); store holds A, B, F; one `adopted` line with `server=int/b` (Story 2 AS 2, 4) |
| e | `--prune int` | lists only B; A and F kept (Story 2 AS 8) |
| f | `int/b` serves A (the certificate of `int/a`); `--check` | `int/b` `changed`, note "expected for int/a" (FR-010a) |
| g | `int/b` stops responding; `--accept int/b` F2 | refused, exit 3; one `failed` line with `server=int/b`; files unchanged (Story 2 AS 3) |

## 5. Start-up dialog and rejection memory (Story 3)

Use a profile copy where `int/a` and `int/b` both changed. Run
`inubit-cert-check --config COPY --interactive`, as the launcher does.

Expected:
- **Dialogs**: two dialogs, one after another, each naming its server. The start continues after
  about 20 s at most.
- **Answers**: "Reject" for `int/b` and "Accept" for `int/a` → `int/a` is adopted; `int/b` is
  remembered under `int/b`.
- **Second run**: no dialog for `int/b` and a warning on stderr. A new change on `int/a` is still
  offered.
- **Forget**: `--forget int/b`, then another run → the dialog for `int/b` appears again.
- **Migration**: with a stage-keyed entry `"int": {…}` in `cert-rejections.json`, the next
  `--check` removes it.

## 6. Concurrency (Story 2 AS 9)

While a dialog for `int/b` is open, run `--accept int/a <fp>` from a terminal.

Expected: the terminal waits for the lock. After "Accept" in the dialog both adoptions are in the
profile, or the terminal run is refused with "the profile lock is held by another process … for more than 30 s" and succeeds when
repeated (research R-12).

## 7. Regression for uniform stages

Run the 006 quickstart scenarios 1–5 unchanged on a uniform stage.

Expected: identical output, JSON and log lines (SC-005).

## Outcome (T031, local copies)

Everything ran on copies under a scratch directory. Hosts were `127.0.0.1` only, and the profile was
`acme` with stages `dev` and `int` (`int/a`, `int/b` with `tls.pinPerServer`). Credentials came from a
stub `secret-tool`; the Keychain was not used. The tool ran from the repository
(`python3 -I tools/inubit-cert-check.py --config … --state-dir …`) instead of `~/.local/bin`.

- Q2 pass:
  - The valid copy: exit 0, `Result: OK`, no errors, no FR-005 line.
  - The broken copies V-1, V-3, V-4, V-5, V-6, V-10 and V-11: exit 1 each, naming stage and server.
    V-6 also reports the existing `disableHostnameVerification` error.
  - `defaults.tls`: `Unknown key 'tls' at defaults`.
- Q3 pass:
  - Exactly one start-up line `Stages with one pinned certificate per server (tls.pinPerServer): int`
    on stderr; stdout holds only JSON-RPC.
  - The server writes no log file of its own; docs/setup.md and contracts/configuration.md now say
    "stderr" only (the MCP client usually captures stderr in its log).
- Q3a pass:
  - With four endpoints, `export_artifacts` for `int/a` and `int/b` passed the REST login (stub
    answered only `GET /ibis/rest/system/info` → 200 `{}`). Each then returned
    `TLS_ERROR … for the StartCLI address 127.0.0.1:<port> (CertificateException); StartCLI was not started`.
  - No marker file from the stub `startcli.sh`; only its local version query ran.
  - With the StartCLI certificates swapped back, both calls reached the stub (2 marker files).
- Q4 pass:
  - a: OK, exit 0.
  - b: `int/b` changed, `int/a` ok, exit 10.
  - c: `--accept int <fp>` refused, exit 3, files unchanged.
  - d: check → `--accept int/b <fp>` → check took 1.7 s of command time, well under 2 minutes (SC-002).
    `int/a` was stopped; only the `int/b` pin line changed; the store held A, B and F; one
    `adopted … server=int/b` line was logged.
  - e: `--prune int` listed only the old `int/b` entry.
  - f: certificate A on `int/b` produced the note "expected for int/a", exit 10.
  - g: `int/b` silent; `--accept` refused after 5.2 s (`unreachable (timeout)`), exit 3, files unchanged,
    one `failed` line.
- Q5: not run in T031 (no macOS dialogs, user decision); run by the operator on 2026-11-06, see
  "Outcome (Q5, Q6 dialog variant, T032)" below.
- Q6 pass without a dialog:
  - A second process held the profile lock the way the dialog does. With the lock held for 8 s,
    `--accept int/a` waited and was adopted (exit 0).
  - With the lock held for 36 s, `--accept` was refused after 30.2 s ("the profile lock is held by
    another process … for more than 30 s", exit 3, files unchanged). Repeating it succeeded.
  - Two concurrent `--accept` runs for `int/a` and `int/b` were both adopted.
  - `--interactive` while the lock was held: "dialog open in another session", exit 0.
  - The dialog variant: run on 2026-11-06, see below.
- Q7 pass:
  - The 006 scenarios 1–5 ran on a uniform `int`, once with this branch's tool and once with the
    `main` tool, on identical copies. Text output, JSON and change-log lines were identical; only
    timestamps differed.
  - Dialog steps were replaced by the CLI: `--accept` instead of the dialog Accept, and a seeded
    rejection instead of the dialog Reject.
  - Scenario 4 used a silent `127.0.0.1` listener instead of `192.0.2.1`: exit 20 after 5.3 s.
  - `get_health` was not run, because it needs INUBIT.

## Outcome (Q5, Q6 dialog variant, T032)

2026-11-06, release 0.7.0 (`v0.7.0`), macOS. Q5 and Q6 ran on copies under a scratch directory
(the T031 setup: profile `acme`, `int/a` and `int/b` with `tls.pinPerServer`, local TLS servers on
`127.0.0.1`, stub `secret-tool`, tool from the repository); the operator clicked the dialogs.

- Q5 pass:
  - Dialogs: `--interactive` with both servers changed showed two dialogs, each naming its server;
    exit 0 after 11 s. "Accept" for `int/a` adopted it ("Accept given, certificate adopted");
    "Reject" for `int/b` was remembered under the key `int/b`. One change-log line each, with
    `server=int/a outcome=adopted` and `server=int/b outcome=rejected`.
  - Second run (`int/a` with yet another certificate, `int/b` unchanged): only the dialog for
    `int/a`, adopted; for `int/b` no dialog, only the stderr warning "… was rejected earlier; no
    dialog (to be asked again: … --forget int/b)".
  - Forget: `--forget int/b` ("remembered rejection removed"), then `--interactive` showed the
    dialog for `int/b` again; "Reject" remembered it again.
  - Migration: a stage-keyed entry `"int"` added to `cert-rejections.json` was removed by the
    next `--check`; `"int/b"` stayed.
- Q6 dialog variant pass: `--interactive` with dialogs for `int/a` and `int/b` open; the start
  continued after 20 s ("the dialog is still open; the start continues"). `--accept int/a <fp>`
  from a terminal, started 8 s later, was refused after 30 s ("the profile lock is held by another
  process … for more than 30 s", exit 3, files unchanged). After "Accept" in both dialogs both
  adoptions were logged; the repeated terminal `--accept` ended with exit 0 ("already pins …;
  nothing changed"), `--check` exit 0.
- T032 (operator, real profile, operator's approval at each step):
  - C-8′: the operator's `~/.claude/CLAUDE.md` was backed up and its rule block changed to the
    C-8′ trigger (`CertPathValidatorException`, `SunCertPathBuilderException` or a pin mismatch
    `CertificateException`), target `<stage>` or `<stage>/<server>`, `expectedBy` note, prune hint
    with the stage part, after the operator's "yes".
  - The operator marked one real stage with two servers by hand; the server's `--check-config`
    ended with `Result: OK`.
  - Finding (documentation, not code): the installed `inubit-cert-check` predated feature 014 and
    refused the profile (`unknown key 'pinPerServer'`); after reinstalling it, the first
    adoption was rolled back (`--check-config refused the changed profile: exit 1`) because the
    profile's `serverJar` still named a 0.6.x jar. After the operator set `serverJar` to 0.7.0 the
    adoption succeeded. `docs/setup.md` "Converting a stage" now has a step 0 for both.
  - `--check` reported one server changed (self-signed, notBefore 2026-10-30, no `expectedBy`
    note) and the other unreachable (connection refused). After the operator's explicit "yes"
    `--accept <stage>/<server> <fp> --by claude` adopted it (profile and trust-store backups);
    after the reconnect `get_health` for the stage was OK. `--prune <stage>` first without
    `--yes` listed one superseded entry and kept the pins of both servers (including the
    unreachable one); with `--yes` it removed that entry (backup).
  - A real `TLS_ERROR` inside a Claude session did not occur; the in-session rule was not
    exercised (not checkable).
