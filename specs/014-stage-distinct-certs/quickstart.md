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
