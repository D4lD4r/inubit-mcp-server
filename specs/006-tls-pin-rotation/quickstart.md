# Quickstart & Validation: Confirmed Rotation of Pinned INUBIT Server Certificates

Feature: [spec.md](spec.md) · Contract: [contracts/cli.md](contracts/cli.md)

Profile `acme` with stages `dev` (one server) and `int` (two servers) stands for the operator's real
profile. Commands use `~/.local/bin/inubit-cert-check`; replace `acme` with the real profile name
locally — real names never go into this repository.

## Prerequisites

- macOS with `/usr/bin/python3` (3.9+), `/usr/bin/openssl`, `/usr/bin/osascript`.
- The JDK that runs the server (its `java` and `keytool`), the server JAR in `~/.local/lib`.
- The profile file converted to one `tls` block and one trust store per stage (plan, step 1), with
  `x-cert-check: { java: …, serverJar: … }`.
- A scratch directory `$T` for test copies (never the live files for scenarios 3 and 4).

## 0. Offline tests

```bash
/usr/bin/python3 -I -m unittest discover -s tools/tests -v
```

Expected: all tests pass without network access to INUBIT; no dialog appears.

## 1. Check shows the current state (spec SC-001, acceptance 1)

```bash
inubit-cert-check --profile acme --check; echo "exit=$?"
inubit-cert-check --profile acme --check --json
```

Expected: `dev` `changed` with old/new fingerprint, subject, issuer, validity and IP; both `int`
servers `ok`; `exit=10`. Compare the new fingerprint with an independent measurement:

```bash
openssl s_client -connect <dev-host>:8443 -servername <dev-host> </dev/null 2>/dev/null | openssl x509 -noout -fingerprint -sha256
```

`shasum` of profile file and trust stores before and after: identical.

## 2. Real adoption via the start-up dialog (SC-002, acceptance 2)

1. In the terminal CLI: `/mcp` → `inubit-acme` → Reconnect; in the desktop app's Code tab (no `/mcp reconnect` there): start a new session.
2. The dialog "INUBIT certificate changed - stage dev" appears within ~6 s. Compare the fingerprint
   with scenario 1, then click **Accept** within about 10 s (the start waits 20 s from the
   beginning of the check; a later click is still adopted but needs another reconnect).
3. Expected: `cert-changes.log` gains `outcome=adopted … by=dialog`; backups
   `*.bak-<timestamp>` exist; `get_health` for `dev` is OK and for `int` still OK; the `dev` trust
   store lists old and new entry, `--prune dev` (without `--yes`) lists the old one.
4. Other sessions that were already running: reconnect them too (they still hold the old pin).

## 3. Rejection and timeout change nothing (SC-003, SC-004, acceptance 3)

Copy profile file and trust stores to `$T`, point `trustStore` paths in the copy to `$T`, set the
`dev` pin in the copy to a wrong but well-formed fingerprint, and copy the start script to
`$T/launcher` with `--config $T/acme.yaml` instead of `--profile acme` (cert check and server).
Use `--state-dir $T/state` for the check.

1. Run `$T/launcher </dev/null >$T/stdout.bin 2>$T/stderr.txt` → dialog → **Reject**.
   Expected: copy files unchanged (`shasum`), `rejected` line in `$T/state/cert-changes.log`,
   server started (stderr shows the server's start-up), `$T/stdout.bin` empty until the server's
   own MCP output.
2. Run again → **no** dialog (remembered), warning on stderr, start continues in ≤ ~6 s.
3. `inubit-cert-check --config $T/acme.yaml --state-dir $T/state --forget dev`, run again, let the
   dialog time out (60 s; the start continues after 20 s). Expected: `timed-out` line, files
   unchanged.

## 4. Unreachable server (SC-005, acceptance 4)

In the copy, change one `int` server's `baseUrl` to an unroutable address
(e.g. `https://192.0.2.1:8443`) and restore the correct `dev` pin.

```bash
time inubit-cert-check --config $T/acme.yaml --state-dir $T/state --check; echo "exit=$?"
```

Expected: that server `unreachable` (`error=timeout`), no dialog, `exit=20`, wall time ≤ ~6 s;
the test launcher starts the server without delay beyond that.

## 5. MCP channel stays clean (SC-006, acceptance 5)

Pipe an MCP `initialize` request into the test launcher and check that the first byte on stdout
is `{` and every stdout line is a JSON-RPC message:

```bash
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"check","version":"0"}}}' \
  | $T/launcher 2>/dev/null | head -1
```

Expected: one JSON-RPC response with `"id":1` — in all variants of scenarios 3 and 4, and also
when the check itself fails (make the copy's state directory read-only with `chmod 500 $T/state`;
restore with `chmod 700` afterwards); profile and trust stores of the copy stay unchanged.

## 6. In-session flow (spec User Story 4)

With the test copy registered temporarily as a second MCP server (or after a real change), call a
tool on `dev`; on `TLS_ERROR … CertPathValidatorException` Claude shows stage, fingerprints,
notBefore and IP and asks. Answer anything but "Ja" → nothing changes. Answer "Ja" → adoption
(`by=claude`), Claude asks for a reconnect (`/mcp` → Reconnect, or a new session in the desktop Code tab) and then checks `get_health`.
