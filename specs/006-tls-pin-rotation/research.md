# Research: Confirmed Rotation of Pinned INUBIT Server Certificates

Feature: [spec.md](spec.md) · Plan: [plan.md](plan.md)

All findings were checked on the operator's workstation on 2026-10-08 (macOS, Claude Code 2.1.293
in the desktop app). Experiments that write files ran on copies in a scratch directory; the live
configuration and trust store were only read. Names follow the spec: profile `acme`, stages `dev`
and `int`.

## R-1 Who starts the MCP server, and in which context

- **Decision**: Treat Claude Code (CLI and desktop Code tab) as the client. The profile's server is
  registered at user scope in `~/.claude.json` with the launcher as command; the Claude Desktop chat
  configuration has no MCP servers.
- **Findings**: Every running server JVM is a child of the `claude` binary of a Code session, with no
  controlling terminal (`tty` = `??`). Five sessions → five server processes at the same time.
  The Bash tool of a Code session runs as a child of the same `claude` process, also without a
  terminal, so experiments from it reproduce the launcher's context.
- **Consequences**: (a) every new session runs the launcher, so start-up checks happen often →
  remembered rejections (FR-019a) and a cross-process lock (R-8) are essential; (b) after an
  adoption, *other* running sessions keep the old pin in memory until they reconnect.

## R-2 Can a dialog be shown from that context?

- **Decision**: Use `/usr/bin/osascript` `display dialog … giving up after 60`, run with stdin and
  stdout detached from the MCP channel. No alternative mechanism is needed.
- **Evidence**: From the Code-session context (no terminal) a dialog with the buttons "Reject" /
  "Accept" appeared and returned `button returned:, gave up:true` after `giving up after 3`,
  exit 0. `display notification` also returned exit 0 (whether it is visible depends on the
  notification settings for Script Editor/osascript; to be confirmed by the operator once).
- **Details**: Do **not** declare a `cancel button`: with one, "Reject" ends osascript with
  exit 1 / error -128, which is indistinguishable from other failures. Without it, the result line
  is always `button returned:<label>, gave up:<bool>` and is parsed exactly. Any other output or
  exit status = "dialog could not be shown" (FR-019, not remembered).
- **Alternatives considered**: `terminal-notifier` / `alerter` (extra dependency, rejected),
  `System Events` dialogs (needs Automation permission, rejected), a dialog only in chat (not
  available at start-up, which is case A).

## R-3 Client start-up timeout

- **Decision**: Wait for the dialog at most **20 s from the start of the check**; the JVM then has
  ≈ 10 s left before the client gives up.
- **Evidence** (Claude Code docs, env-vars page): `MCP_TIMEOUT` = start-up timeout of an MCP server,
  default 30 000 ms. Stdio servers are not reconnected automatically; a failed server is retried only
  manually via `/mcp` → Reconnect (interactive) — the model cannot trigger a reconnect.
  `MCP_CONNECT_TIMEOUT_MS` (5 000 ms) only bounds how long a session start blocks before the tool
  list is snapshotted; pending servers keep connecting in the background.
- **Consequence for case C**: Claude cannot reconnect the server itself; the CLAUDE.md rule tells
  the operator to use `/mcp` → `inubit-<profile>` → Reconnect (or to start a new session).

## R-4 Trust store and pin: what must change on adoption

- **Decision**: Adoption updates the stage pin **and** adds the certificate to the stage's own
  trust store (spec clarification Q1, option B).
- **Evidence**: `PinningTrustManager` always runs PKIX validation against the configured trust store
  and checks the pin in addition; `disableHostnameVerification` only skips endpoint identification.
  The live trust store has exactly one entry (the old certificate), so the new certificate fails with
  `CertPathValidatorException` before the pin is consulted.
- **Residual risk (from setup docs §5)**: StartCLI cannot pin; it receives only
  `--trustStoreFilePath` and `--disableHostNameVerification`. CLI calls therefore accept **every**
  certificate in the stage's trust store. With option B the superseded certificate stays trusted for
  CLI calls of that stage until it is pruned. Mitigation: the check lists superseded entries
  (FR-013a) and a separate, explicit `--prune` command removes them (contract C-5); the dialog and
  the CLAUDE.md rule mention pruning after a successful adoption.

## R-5 Password-less trust store maintenance

- **Decision**: Use the JDK `keytool` that also runs the server (configured, see R-9) with
  `-storepass unused -J-Dkeystore.pkcs12.certProtectionAlgorithm=NONE
  -J-Dkeystore.pkcs12.macAlgorithm=NONE`, exactly as `docs/setup.md` §5 documents for creating it.
- **Evidence**: On a copy of the live store, `keytool -importcert -noprompt` added a second
  `trustedCertEntry`; afterwards `KeyStore.load(in, null)` in Java listed both aliases and
  `openssl pkcs12 -passin pass:` read both certificates. Removal uses `keytool -delete -alias`.
- **Verification after every change**: list the store password-less (Java semantics: `keytool -list`
  with the same properties) and require that the pinned fingerprint is present (the server's
  `--check-config` does **not** look inside the store, see R-6).
- **Alias**: `<stage>-<first 8 hex digits of the fingerprint>-<yyyymmdd>`, e.g. `dev-bb223344-20261008`.

## R-6 Validating the configuration after a change

- **Decision**: Run the server's own `--check-config --config <file>` with a **clean environment**
  (`HOME`, `PATH` only) plus a placeholder value (`cert-check-placeholder`) for every
  `<PREFIX>_<GROUP>_USERNAME/_PASSWORD`; exit 0 = valid. In addition, re-read the file with the tool's
  own reader and require: the stage's pin equals the new fingerprint, every other stage's pin is
  unchanged, and the server ids equal those printed by `--check-config`.
- **Evidence**: Without credential variables `--check-config` fails (missing username/password);
  passwords shorter than 4 characters are rejected; with placeholders the live-equivalent copy gives
  `Result: OK`, exit 0. A malformed pin is reported (`tls.pinnedCertificateSha256 must be a SHA-256
  fingerprint`). A missing trust-store file is reported; a garbage trust-store file is **not**
  (hence the separate trust-store verification in R-5). No secret is ever needed or passed.

## R-7 Reading the YAML without a YAML library

- **Decision**: A small, strict reader for the subset the tool needs, inside the tool: top-level
  `profile.name`, `credentials.envPrefix`, `x-cert-check` (tool settings, R-9), `x-*` anchors on
  mappings, and under `groups:` each item's `name`, `tls` (inline block or `*alias`) and
  `nodes[].name/baseUrl/tls`. Comments, flow mappings of other keys and unknown keys are skipped.
  Anything inside the needed paths that the reader does not understand is an error (fail closed).
- **Writing**: pins are changed by replacing the value on exactly one physical line (the
  `pinnedCertificateSha256:` line of the stage's own `tls` block, found by the reader with its line
  number), keeping indentation, quoting style and trailing comment. `--accept` refuses a stage whose
  effective pin comes from an alias, a `defaults` block or a server-level override shared with
  another stage, because a one-line change would then affect more than that stage.
- **Alternatives considered**: running a Java helper against the server JAR's Jackson YAML (depends
  on the JAR's internals, +1–2 s start-up, rejected); a new server option that prints the effective
  TLS settings as JSON (requires a server release, out of scope per spec; possible follow-up);
  a third-party YAML package (forbidden: no extra dependencies).

## R-8 Concurrency between sessions

- **Decision**: One lock file per profile, `<state>/cert-check.lock`, using `fcntl.flock`.
  The interactive mode takes it **non-blocking** before showing dialogs; if another session holds
  it, this start shows no dialog, logs "dialog open in another session" and continues immediately.
  `--accept` and `--prune` take it blocking with a 30 s limit. The lock is held by the dialog worker
  (R-10) until all its dialogs and adoptions are done.
- **Rationale**: Five concurrent sessions must not open five dialogs or edit the files at the same
  time; `flock` is released automatically when a process dies.

## R-9 Implementation language and where the tool lives

- **Decision**: Python 3 standard library only, run as `/usr/bin/python3 -I` (macOS ships 3.9.6 with
  the Command Line Tools; code stays 3.9-compatible). Source in the repository as
  `tools/inubit-cert-check.py` (customer-agnostic, takes `--profile`/`--config`), tests in
  `tools/tests/`, installed by copying to `~/.local/bin/inubit-cert-check`. External programs:
  `/usr/bin/openssl` (LibreSSL 3.3.6), `/usr/bin/osascript`, and `java`/`keytool` of the JDK that runs
  the server.
- **Tool settings** live in the profile file under a top-level `x-cert-check` key, which the server
  ignores (contract 002: top-level `x-*` keys are allowed):
  `java` (path of the java binary; `keytool` is taken from the same directory), `serverJar`.
  The launcher passes nothing else; command-line options override for tests.
- **Rationale**: Concurrency, JSON, exact text editing and file locking are robust in Python and
  painful in zsh; `-I` keeps the environment and the current directory out of the import path. The
  repository already keeps operator tools in `tools/` (Python). The server code is untouched.
- **Alternatives considered**: zsh (no JSON, hard timeouts and parallelism via background jobs and
  `kill`, fragile text editing — rejected); Homebrew Python (not guaranteed in the launchd PATH).

## R-10 Fetching the presented certificate with a hard 5 s limit

- **Decision**: Per server, in a thread: resolve the host once (`getaddrinfo`, first IPv4/IPv6
  address), then run `openssl s_client -connect <ip>:<port> -servername <host> -showcerts`
  with stdin `/dev/null` via `subprocess.run(timeout=…)`, which kills the process at the deadline;
  pipe the first certificate into `openssl x509 -noout -fingerprint -sha256 -subject -issuer -dates`.
  The overall deadline (5 s per server, all servers in parallel) is enforced by the main thread
  waiting at most 5.5 s for all workers; a worker still resolving DNS then counts as `unreachable`.
- **Rationale**: Connecting to the resolved IP guarantees that the reported IP is the one whose
  certificate was read; `--accept` uses the same procedure (FR-011). The leaf is the first
  certificate, the same one `PinningTrustManager` pins.

## R-11 Start-up flow with the 20 s budget

- **Decision**: `--interactive` (called by the launcher) runs the check; if no stage needs a dialog
  it returns at once (≤ ~6 s, FR-021). Otherwise it starts a **detached dialog worker**
  (`start_new_session=True`, stdin/stdout/stderr `/dev/null`; it writes to the diagnostic log itself) that holds the lock,
  shows the dialogs one stage after another and performs adoptions. The parent polls the worker's
  status file until all dialogs are answered or the 20 s budget (measured from the start of the
  check) is used up, then returns 0 in every case. A late "Accept" is still adopted by the
  worker, which then posts a notification asking for `/mcp` → Reconnect.
- **Why detached**: the launcher `exec`s the JVM right after the check; a child that inherited the
  MCP stdout could corrupt the protocol or keep the pipe open.

## R-12 Testing approach

- **Decision**: `unittest` (standard library), offline: tests start local TLS servers on 127.0.0.1
  with throw-away self-signed certificates generated by `openssl req`, write temporary profile
  files, and replace the dialog, notification, `keytool` and `--check-config` calls by in-process
  fakes (monkeypatching module functions — there is **no** runtime switch or environment variable
  that can answer a dialog, so nothing can auto-accept in production). One opt-in test exercises the
  real `keytool` when `JAVA_HOME`/`keytool` is available. CI runs the suite on Ubuntu (python3 and
  openssl present). Live acceptance (spec SC-001…SC-007) follows [quickstart.md](quickstart.md).
