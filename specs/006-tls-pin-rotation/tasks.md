---

description: "Task list for feature 006: confirmed rotation of pinned INUBIT server certificates"
---

# Tasks: Confirmed Rotation of Pinned INUBIT Server Certificates

**Input**: Design documents from `specs/006-tls-pin-rotation/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/cli.md](contracts/cli.md), [quickstart.md](quickstart.md)

**Tests**: Required — the constitution (Principle III) mandates test-first. Every test task is
written and observed to **fail** before the implementation task that follows it.

**Organization**: Tasks are grouped by user story (US1 check, US2 adopt, US3 start-up dialog,
US4 in-session rule) so each story can be implemented and validated on its own.

## Ground rules for every task

- Tool source: `tools/inubit-cert-check.py` (single file, Python 3.9-compatible standard library
  only, shebang `#!/usr/bin/python3 -I`). Tests: `tools/tests/`, run with
  `/usr/bin/python3 -I -m unittest discover -s tools/tests -v`.
- **Public repository**: no real profile, stage, host, IP or fingerprint in any repository file.
  Use `acme`, `dev`, `int`, `node1`, `*.example.test`, `203.0.113.x`, fictitious fingerprints.
  Real values exist only in the local files below. Run `mvn -q -o test -Dtest=NoCustomerIdentifiersTest
  -Dsurefire.failIfNoSpecifiedTests=false` after each phase that touches repository files.
- **Local files** (operator workstation, never committed): `<profile>` below means the operator's
  real profile name; before editing any of them, copy it to `<file>.bak-<YYYYmmdd-HHMMSS>` (UTC).
- **Never adopt the real new `dev` certificate** except through the operator's own click on
  "Accept" in T046. Nothing is committed or pushed before the operator says so.
- No test may contain or enable a runtime switch (CLI option, environment variable) that answers a
  dialog; fakes are injected only by monkeypatching inside the test process or via
  `tools/tests/run_with_fakes.py` (research R-12).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1–US4 from spec.md

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Test harness and tool skeleton

- [X] T001 Create `tools/tests/support.py` with `load_tool()` that imports `tools/inubit-cert-check.py` via `importlib.util.spec_from_file_location` (hyphenated name) and returns the module; and `tools/tests/run_with_fakes.py`, a test-only entry point that loads the tool, replaces `show_dialog`/`notify` with scripted fakes read from a JSON file given as its first argument (answer per stage, optional delay in seconds), then calls `main(argv[2:])` and exits with its return code
- [X] T002 [P] Create `tools/tests/tls_fixtures.py`: `make_cert(tmpdir, cn, days=3650, not_before_offset=0)` generating a self-signed RSA-2048 certificate + key with `openssl req -x509 -nodes` (returns PEM path, key path, SHA-256 fingerprint via `openssl x509 -fingerprint -sha256`); `TlsServer(cert, key)` serving TLS handshakes on `127.0.0.1:0` in a daemon thread, recording the SNI name of each handshake, with `swap_cert(cert, key)`; `BlackHole()` — a TCP listener that accepts but never answers (for timeouts); `closed_port()` returning a free port with nothing listening
- [X] T003 Create the skeleton `tools/inubit-cert-check.py`: module docstring (purpose, customer-agnostic, contract `specs/006-tls-pin-rotation/contracts/cli.md`), shebang `#!/usr/bin/python3 -I`, exit-code constants (`OK=0, INTERNAL=1, USAGE=2, REFUSED=3, ROLLED_BACK=4, CHANGED=10, UNREACHABLE=20`), `argparse` with mutually exclusive `--check [--json]`, `--accept STAGE FINGERPRINT [--by cli|claude]`, `--interactive`, `--forget STAGE`, `--prune STAGE [--yes]`, hidden `--dialog-worker STATUSFILE`; common options `--profile NAME` (pattern as server `ProfileInfo.NAME_RULE`) xor `--config PATH`, `--state-dir`, `--java`, `--server-jar`; usage errors → stderr, exit 2; `main(argv) -> int` guarded so any unexpected exception logs one line and returns 1; make the file executable (`chmod 755`)
- [X] T004 [P] Add a step to `.github/workflows/ci.yml` that runs `python3 -I -m unittest discover -s tools/tests -v` (Ubuntu runner; tests that need macOS binaries or `keytool` skip themselves)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Profile reader, certificate fetch, state files, trust-store access, and the converted
local profile — needed by every story

**⚠️ CRITICAL**: No user-story work begins before this phase is complete

### Tests (write first, see them fail)

- [X] T005 [P] Write `tools/tests/test_profile_reader.py` against data-model "Profile view": stages from `groups[].name`; servers `<stage>/<node>` with `host`/`port` from `baseUrl` (port default 443; non-https → error); effective TLS inheritance server → stage → `defaults`; `pinSource` = `stage` (own inline block, with correct 1-based `pinLine`), `alias` (`tls: *x` to a top-level `x-…: &x` mapping), `defaults`, `server` (≥ 1 node override); pins normalized to upper-case colon form; `envPrefix` = `credentials.envPrefix` else `INUBIT_` + normalize(`profile.name`) (upper case, non-`[A-Z0-9]` → `_`); `x-cert-check.java`/`serverJar` with `~` expanded; commented-out `groups` items and comments ignored; flow mappings of other keys (e.g. `terminology`) skipped; fail closed (clear error, no partial result) on: tab indentation, a `---` separator, merge keys `<<:`, an unknown shape inside `groups`, a duplicate stage name
- [X] T006 [P] Write `tools/tests/test_fetch.py`: fingerprint normalization (64 hex, colons optional, any case → `AA:BB:…`; 63/65 digits or non-hex → error); fetching from `TlsServer` returns the fixture fingerprint, subject, issuer, `notBefore`/`notAfter` as ISO-8601 UTC (`…Z`), `validNow`, `ip == "127.0.0.1"`, and the server recorded the SNI host name; `BlackHole` → `unreachable` with `error="timeout"` returned within 5.5 s; `closed_port()` → `error="connect"`; host `does-not-exist.invalid` → `error="dns"`; a plain-TCP non-TLS echo → `error="tls"`; PEM kept in memory and never written to the diagnostic log; the `openssl x509` output parser accepts both LibreSSL (macOS, `subject=CN=x`) and OpenSSL 3 (CI, `subject=CN = x`) samples, including both date formats
- [X] T007 [P] Write `tools/tests/test_state.py`: state dir created with mode `0700`, files `0600`; change-log line exactly `"<UTC time> profile=<p> stage=<s> outcome=<o> old=<fp> new=<fp> notBefore=<iso> by=<who>"` with `outcome ∈ adopted|rejected|timed-out|dialog-failed|failed|pruned` and `by ∈ dialog|claude|cli`; appended, never truncated; diagnostic log rotated to `.1` at 1 MiB; `cert-rejections.json` load/save atomic (temp + rename) with schema of data-model "Remembered rejection"; lock: second non-blocking acquire fails while a child process holds it, succeeds after that process is killed; blocking acquire with 30 s limit returns "busy" on timeout (use a short limit in the test)
- [X] T008 [P] Write `tools/tests/test_keytool.py`: with a fake runner, `list_entries(store)` parses alias + SHA-256 fingerprint from `keytool -list -v` output (German and English locale samples, or force `-J-Duser.language=en`); `import_cert(store, pem, alias)` and `delete_entry(store, alias)` build argv with `-storetype PKCS12 -storepass unused -J-Dkeystore.pkcs12.certProtectionAlgorithm=NONE -J-Dkeystore.pkcs12.macAlgorithm=NONE -noprompt` (no shell); alias format `<stage>-<first 8 hex lower-case>-<yyyymmdd>`; opt-in real-keytool test (skips when no `keytool`): create store, import two fixture certs, list both, delete one, and load the result password-less (`openssl pkcs12 -nokeys -passin pass:` shows the expected count)

### Implementation

- [X] T009 Implement the profile reader in `tools/inubit-cert-check.py` until T005 passes (research R-7; read-only, returns line numbers for pin lines)
- [X] T010 Implement fingerprint normalization and `fetch_certificate(server, deadline)` in `tools/inubit-cert-check.py` until T006 passes (research R-10: resolve once, `openssl s_client -connect <ip>:<port> -servername <host> -showcerts` with stdin `/dev/null` and `subprocess.run(timeout=…)`, first certificate → `openssl x509 -noout -fingerprint -sha256 -subject -issuer -dates`; `/usr/bin/openssl` first, else `openssl` on PATH)
- [X] T011 Implement state handling in `tools/inubit-cert-check.py` until T007 passes (paths per contract C-6, permissions, change log with `O_APPEND`, diagnostic log + rotation, rejection store, `fcntl.flock` lock helpers)
- [X] T012 Implement trust-store access in `tools/inubit-cert-check.py` until T008 passes (`keytool` = sibling of the configured `java`; research R-5)
- [X] T013 Convert the operator profile `~/.config/inubit-mcp/<profile>.yaml` by targeted edits only (FR-001–FR-003): back up the file; copy the shared trust store to `~/.config/inubit-mcp/<profile>-<stage>-truststore.p12` for each active stage (mode as original); replace the shared `x-…-tls` anchor block and every `tls: *alias` of the active stages by an inline `tls:` block per stage (same `disableHostnameVerification`, the **current old pin** for both stages, the stage's own trust store); in the commented-out prepared stages replace `tls: *alias` by a commented example of an own `tls:` block; add top-level `x-cert-check:` with `java:` (absolute path used by the launcher) and `serverJar:` (the JAR the launcher runs); keep the old shared trust store untouched as backup. Verify: `diff` shows only these lines, the server's `--check-config` (clean env + placeholders, research R-6) returns `Result: OK`, and the tool's reader reports `pinSource=stage` for every active stage

**Checkpoint**: Foundation ready — `python3 -I -m unittest discover -s tools/tests` green for T005–T008; local profile converted

---

## Phase 3: User Story 1 — See whether pinned certificates still match (Priority: P1) 🎯 MVP

**Goal**: Read-only check with text and JSON output and exit codes 0/10/20

**Independent Test**: quickstart scenario 1 — `dev` reported `changed`, both `int` servers `ok`,
exit 10, profile and trust stores byte-identical before and after

### Tests for User Story 1

- [X] T014 [US1] Write `tools/tests/test_check.py` using `TlsServer`/`BlackHole` and temporary profiles: per-server status `ok|changed|unreachable`; stage status = worst (changed > unreachable > ok); `conflict` when reachable servers of a stage present ≥ 2 fingerprints → `offer` null; `offer` only when changed, no conflict, no server unreachable and `ownTls`; a stage whose pin comes from an alias/`defaults`/server override or whose trust store is shared gets `ownTls=false`, `offer` null and the text "shared TLS settings — give the stage its own tls block first"; `rejected` true when `offer` equals the remembered rejection; `supersededTrustStoreEntries` = store entries ≠ pin (fake `list_entries`); without `x-cert-check` (or with an unreadable store) it is `null`, the text report says "trust store not inspected: <reason>" and status and exit code are unaffected; exit codes 0 / 10 (conflict included) / 20; `--json` output parses and contains exactly the keys of contract C-1 (stage and server objects); text report contains stage, server id, status, IP, old and new fingerprint, subject, issuer, validity and the `--accept` offer line; three `BlackHole` servers finish in ≤ 6 s total (parallel); SHA-256 of profile and trust stores unchanged after the run; a remembered rejection of a stage that is `ok` again is cleared (FR-019b)

### Implementation for User Story 1

- [X] T015 [US1] Implement check evaluation (data-model "Check result"), parallel fetch with overall deadline 5.5 s, JSON and text rendering for `--check` in `tools/inubit-cert-check.py` until T014 passes (contract C-1); the trust-store listing (`supersededTrustStoreEntries`) is a separate step that `--check` calls and the start-up path (`--interactive`) skips
- [X] T016 [US1] Install the tool locally: copy `tools/inubit-cert-check.py` to `~/.local/bin/inubit-cert-check`, `chmod 755`; run `inubit-cert-check --profile <profile> --check` and `--check --json`; confirm against an independent `openssl s_client … | openssl x509 -fingerprint -sha256` measurement that `dev` is `changed` (new fingerprint matches), both `int` servers `ok`, exit 10; compare `shasum` of profile and trust stores before/after (quickstart scenario 1, spec SC-001)

**Checkpoint**: US1 complete — usable on its own for manual diagnosis

---

## Phase 4: User Story 2 — Adopt a new certificate for one stage after confirmation (Priority: P1)

**Goal**: Guarded `--accept` with backup, single-line pin edit, trust-store add, validation,
rollback and audit line; `--prune` for superseded entries

**Independent Test**: on a test copy with an outdated pin, `--accept` changes only the chosen
stage's pin and trust store, leaves the other stage untouched, creates backups and one log line

### Tests for User Story 2

- [X] T017 [US2] Write `tools/tests/test_accept.py` (fakes for `--check-config` runner and `keytool`, real `TlsServer`): refusals with exit 3 and byte-identical files for unknown stage, malformed fingerprint, `pinSource` ≠ `stage`, trust store shared with another stage, one server presenting another fingerprint, one server unreachable, conflict; success path: backups `<file>.bak-YYYYmmdd-HHMMSS` (UTC) of profile and stage trust store with mode `0600`; exactly one line differs in the profile (the pin line) and indentation, quoting style and trailing comment are preserved (golden files: unquoted, double-quoted, single-quoted, with `# comment`); file mode preserved; `import_cert` called with the PEM fetched during re-verification and alias `<stage>-<8 hex>-<yyyymmdd>`; no import when the fingerprint is already in the store; the `--check-config` runner receives an environment containing only `HOME`, `PATH` and `<PREFIX>_<GROUP>_USERNAME/_PASSWORD` = `cert-check-placeholder` for every stage; re-read verification (stage pin new, other pins unchanged, server ids equal to the runner's reported ids); failure injected at pin write, import, config check and re-read each restore both files byte-identically, log `outcome=failed`, exit 4; success logs `outcome=adopted by=cli` (and `by=claude` with `--by claude`), clears the stage's remembered rejection, prints backup paths and the reconnect/prune hints, exit 0; a second concurrent `--accept` waits for the lock; when the backup directory is not writable (or the state directory cannot be created) the run aborts with exit 3 before any file is changed
- [X] T018 [US2] Extend `tools/tests/test_accept.py` with `--prune`: with and without `--yes` it refuses (exit 3, store and profile byte-identical) a stage whose pin comes from an alias/`defaults`/server override and a stage whose trust store is shared with another stage; otherwise without `--yes` lists entries ≠ pin, changes nothing, exit 0; with `--yes` refuses (exit 3) when the pinned fingerprint is missing from the store; otherwise backs up the store, deletes the listed entries, verifies the pinned entry remains (else restore + exit 4), logs one `outcome=pruned` line per removed entry (`old`=removed, `new`=pin)

### Implementation for User Story 2

- [X] T019 [US2] Implement the `--check-config` runner in `tools/inubit-cert-check.py` (research R-6: `java -jar <serverJar> --config <file> --check-config`, clean environment + placeholders, exit 0 = valid, parse the server ids from the `Server <stage>/<node>:` lines, 60 s timeout)
- [X] T020 [US2] Implement `--accept` steps 1–7 of contract C-2 in `tools/inubit-cert-check.py` (lock, re-verify with fresh fetch, backups, atomic single-line pin replacement, trust-store import with dedupe, validation, rollback, log, rejection clear) until T017 passes
- [X] T021 [US2] Implement `--prune` per contract C-5 in `tools/inubit-cert-check.py` until T018 passes
- [X] T022 [US2] Validate on a test copy in a scratch directory (never the live files): copy profile + stage trust stores, point `trustStore` paths and `--state-dir` to the copy, set the `int` pin of the copy to a wrong but well-formed fingerprint, run `inubit-cert-check --config <copy> --state-dir <copy>/state --accept int <real int fingerprint>`; expect exit 0, one-line diff, backup files, `adopted` log line, `--check` exit 0 for `int`; then `--prune int` (without `--yes`) lists nothing and exits 0, because the copy's `int` trust store contains only the certificate that is now pinned — confirms US2 without touching the real `dev` change

**Checkpoint**: US1 + US2 — the operator can diagnose and adopt manually

---

## Phase 5: User Story 3 — Confirm a change in a dialog when the MCP server starts (Priority: P2)

**Goal**: `--interactive` for the launcher: dialog via `osascript`, start waits ≤ 20 s, late
acceptance, remembered rejections, lock across sessions, nothing on stdout; `--forget`

**Independent Test**: quickstart scenarios 3–5 with a test copy and a copied launcher

### Tests for User Story 3

- [X] T023 [US3] Write dialog unit tests in `tools/tests/test_interactive.py`: `osascript` argv (no shell; title, text, buttons `{"Reject", "Accept"}`, `default button "Reject"`, **no** `cancel button`, `giving up after 60`) with AppleScript string escaping of `"` and `\`; result parsing: `button returned:Accept, gave up:false` → accept, `button returned:Reject, gave up:false` → reject, `button returned:, gave up:true` → timeout, anything else or exit ≠ 0 → dialog-failed; dialog text (contract C-3) contains stage, profile, one line per server with id and IP, old pin and new fingerprint each shortened **and** in full (full ones wrapped in two lines of 16 pairs), notBefore/notAfter in UTC, and the warning line "⚠ The new certificate has expired." / "⚠ The new certificate is not yet valid." exactly when `validNow` is false (both cases tested, absent otherwise); notification argv for late acceptance names stage and `/mcp` → Reconnect
- [X] T024 [US3] Extend `tools/tests/test_interactive.py` with process-level tests through `tools/tests/run_with_fakes.py` (subprocess, stdin = a pipe pre-filled with bytes, stdout and stderr captured): in every scenario stdout is empty, exit code 0, and the pre-filled stdin bytes are still unread afterwards; all ok → no dialog, returns ≤ 6 s and no `keytool`/`java` process is started (fake runner records no call); one `BlackHole` server, nothing changed → no dialog, ≤ 6 s; changed + fake answer "Accept" after 1 s → returns after adoption, `adopted by=dialog`; changed + "Reject" → `rejected` logged and remembered, files unchanged; changed + timeout → `timed-out` logged and remembered; dialog failure → `dialog-failed` logged, not remembered; remembered fingerprint → no dialog, warning line on stderr, ≤ 6 s; a changed stage without `ownTls` → no dialog, log line only; another process holds the lock → no dialog, "dialog open in another session" logged, returns immediately; answer "Accept" after the start budget (budget shortened via a module constant patched in the wrapper, e.g. 3 s, answer after 5 s) → parent returns at the budget with files unchanged, the detached worker later adopts and calls `notify`; internal exception (e.g. unreadable profile) → exit 0, one stderr line; worker runs in a new session (`os.getsid` differs) with stdout `/dev/null`
- [X] T025 [US3] Add `--forget` tests to `tools/tests/test_interactive.py`: removes the stage's remembered rejection, exit 0 also when none existed, exit 3 for an unknown stage, the next interactive run offers the dialog again

### Implementation for User Story 3

- [X] T026 [US3] Implement `show_dialog(stage_info)` and `notify(text)` (osascript, argv only, stdin `/dev/null`) and the dialog text builder in `tools/inubit-cert-check.py` until T023 passes
- [X] T027 [US3] Implement `--interactive` and the detached `--dialog-worker` per contract C-3 and research R-11 in `tools/inubit-cert-check.py` (start budget constant 20 s measured from the start of the check; worker via `subprocess.Popen([sys.executable, "-I", __file__, "--dialog-worker", status, <parent's --config/--state-dir/--java/--server-jar>], start_new_session=True, stdin=DEVNULL, stdout=DEVNULL, stderr=DEVNULL (the worker logs through the diagnostic log itself, so nothing is written twice), pass_fds=[lock fd])`; offers handed over in `<state>/cert-dialog-<pid>.offers.json` (data model); lock handed over by fd inheritance and `Lock.detach()` in the parent (never `LOCK_UN` in the parent); status file `<state>/cert-dialog-<pid>.json` written atomically after each decision; parent polls every 200 ms) until T024 passes
- [X] T028 [US3] Implement `--forget` per contract C-4 in `tools/inubit-cert-check.py` until T025 passes
- [X] T029 [US3] Re-install the tool to `~/.local/bin/inubit-cert-check` (copy + `chmod 755`) and repeat T016's check to confirm nothing regressed
- [X] T030 [US3] Edit the operator launcher `~/.local/bin/inubit-mcp-<profile>` (back it up first): after the Keychain loop and before `exec`, insert the two lines of contract C-7 with the real profile name; verify with `zsh -n`
- [X] T031 [US3] Quickstart scenario 3 on a test copy (copied profile, stage trust stores, launcher with `--config <copy>` for check and server, `--state-dir <copy>/state`, `dev` pin set to a wrong fingerprint): "Reject" → files unchanged, `rejected` line, server starts; second start → no dialog, warning; `--forget dev`, then let the dialog time out → `timed-out` line, start continued after ≤ 20 s; confirm with the operator that the dialog was visible
- [X] T032 [US3] Quickstart scenario 4 on the test copy: one `int` server's `baseUrl` → `https://192.0.2.1:8443`, correct `dev` pin; `time inubit-cert-check --config <copy> --state-dir <copy>/state --check` → `unreachable` (`error=timeout`), exit 20, ≤ ~6 s; test launcher starts without dialog
- [X] T033 [US3] Quickstart scenario 5 on the test copy: pipe an MCP `initialize` request into the test launcher for the variants of T031/T032 and for a check that fails internally (state directory of the copy made read-only with `chmod 500`), and confirm the first stdout line is the JSON-RPC response with `"id":1` (nothing else on stdout) and that the copy's profile and trust stores are unchanged; record measured start-up delays

**Checkpoint**: US1–US3 — start-up confirmation works; the real `dev` change is still pending

---

## Phase 6: User Story 4 — Resolve the TLS error inside a running Claude session (Priority: P3)

**Goal**: Standing rule in the operator's global Claude instructions

**Independent Test**: quickstart scenario 6 — Claude asks before adopting and adopts only after "Ja"

- [X] T034 [US4] Back up `~/.claude/CLAUDE.md` and append the rule of contract C-8 with the real profile name and server name (`inubit-<profile>`); keep the file's existing structure and language
- [X] T035 [US4] (skipped by operator decision, 2026-10-09) Validate the rule with the test copy: register it temporarily at local scope as a second MCP server (`claude mcp add --scope local inubit-certtest -- <copy launcher>`), set its `dev` pin wrong, in a new session call a read tool on `dev` and confirm that Claude runs `--check --json`, shows stage/old/new/notBefore/IP and asks; answer "nein" → nothing changes; answer "Ja" → `adopted by=claude` in the copy's log, Claude asks for `/mcp` → Reconnect and then checks `get_health`; remove the temporary registration (`claude mcp remove --scope local inubit-certtest`) and the test copy

**Checkpoint**: All four stories done; real adoption still pending

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T036 [P] Document certificate rotation in `docs/setup.md` §5 (new subsection "Certificate changes"): why both pin and trust store change, one `tls` block and trust store per stage, `x-cert-check`, install command, `--check/--accept/--prune/--forget`, the launcher line (contract C-7), the start-up dialog behaviour (20 s / 60 s, remembered rejections), the CLAUDE.md rule (contract C-8), the StartCLI residual risk and why to prune after an adoption — with `acme` names only
- [X] T037 [P] Add a usage header and `--help` texts matching contract C-1…C-5 in `tools/inubit-cert-check.py`
- [X] T038 [P] Add an "Unreleased" entry for `tools/inubit-cert-check` to `CHANGELOG.md`
- [X] T039 Run the full offline suite `/usr/bin/python3 -I -m unittest discover -s tools/tests -v` and `mvn -q -o verify` (includes `NoCustomerIdentifiersTest`); fix findings
- [X] T040 Review `tools/inubit-cert-check.py` against spec FR-001…FR-026 and the clarifications (Q1–Q3); list any gap as a new task here before continuing
- [X] T041 Re-install the final tool to `~/.local/bin/inubit-cert-check` and run `--check` once more (expect `dev` changed, `int` ok)
- [X] T042 Verify that no live file was changed by the tests: compare `shasum` of the live profile, stage trust stores and launcher with the state after T013/T030
- [X] T043 Ask the operator to restart the MCP server (`/mcp` → `inubit-<profile>` → Reconnect) for the real adoption, and explain that the change applies at once only if the dialog is confirmed while the start is still waiting (about 10 s after it appears, since the adoption itself takes several seconds; the 20 s budget runs from the start of the check); a later click within the dialog's 60 s is still adopted but needs another reconnect

## Phase 8: Real adoption (operator-driven, quickstart scenario 2)

- [X] T044 Operator restarts the server; confirm that the dialog for `dev` appears and that its new fingerprint equals the one measured in T016
- [X] T045 If the operator clicks "Reject" or lets it time out: stop here, report, and offer `--forget dev` for another attempt — never adopt otherwise
- [X] T046 After the operator's "Accept": verify `cert-changes.log` has `outcome=adopted … by=dialog`, list the backup files, run `get_health` for `dev` (OK) and `int` (still OK), and remind the operator to reconnect the other running sessions
- [X] T047 Show `inubit-cert-check --profile <profile> --prune dev` (list only) and run `--prune dev --yes` **only** if the operator agrees
- [X] T048 Summarize for the operator: changed files (repository and local), backup locations, log location; ask whether to commit/push the repository part (nothing is committed before that answer)

---

## Dependencies & Execution Order

### Phase dependencies

- Phase 1 → Phase 2 → US1 (Phase 3) → US2 (Phase 4) → US3 (Phase 5) → US4 (Phase 6) → Polish
  (Phase 7) → Real adoption (Phase 8).
- US2 depends on US1 (re-verification reuses the check); US3 depends on US1 and US2 (the dialog
  calls the adoption); US4 depends on US1 and US2 only (it could run before US3).
- T013 (profile conversion) must precede T016 and every live or copy-based validation.
- T030 (launcher edit) must precede T031–T033 and Phase 8.
- Phase 8 only after T039–T043 and the operator's explicit restart.

### Within each story

- Test task(s) first and failing → implementation → local install/validation.
- All implementation tasks edit the same file (`tools/inubit-cert-check.py`) and run sequentially.

### Parallel opportunities

- Phase 1: T002 and T004 alongside T001/T003.
- Phase 2: T005, T006, T007, T008 (four test files) in parallel; T009–T012 are sequential (same
  file); T013 (local profile) can run in parallel with T005–T012.
- Phase 5: none — T023–T025 share `tools/tests/test_interactive.py`, T026–T028 the tool file.
- Phase 7: T036, T037, T038 in parallel.

## Parallel Example: Phase 2

```text
Task: "Write tools/tests/test_profile_reader.py (T005)"
Task: "Write tools/tests/test_fetch.py (T006)"
Task: "Write tools/tests/test_state.py (T007)"
Task: "Write tools/tests/test_keytool.py (T008)"
Task: "Convert the operator profile (T013)"   # local file, independent of the repository tests
```

## Implementation Strategy

### MVP first (US1)

1. Phases 1–2, then US1 (T014–T016).
2. **Stop and validate**: the check reports the real situation correctly; the operator could
   already adopt by hand following docs/setup.md §5.

### Incremental delivery

1. US2 → manual, guarded adoption from the command line (and for Claude in US4).
2. US3 → start-up dialog; validated on copies only.
3. US4 → in-session rule.
4. Polish → docs, CI, full verification.
5. Real adoption by the operator's click; prune on request; commit only on request.
