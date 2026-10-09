# Implementation Plan: Confirmed Rotation of Pinned INUBIT Server Certificates

**Branch**: `006-tls-pin-rotation` | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/006-tls-pin-rotation/spec.md`

## Summary

A stand-alone operator tool, `inubit-cert-check`, detects when INUBIT servers present a certificate
other than their stage's pin and adopts a new one only after an explicit operator decision. It
offers a read-only check (text/JSON, exit 0/10/20), a guarded adoption per stage (re-verify on all
servers → backup → replace the pin line → add to the stage trust store → validate with the server's
own `--check-config` → roll back on failure → log), a start-up mode for the launcher that shows a
macOS dialog (start waits ≤ 20 s, dialog stays open ≤ 60 s, rejections remembered), and commands
to forget a rejection and to prune superseded trust-store entries. The operator's profile file is
converted once from a shared `tls` anchor to one `tls` block and one trust store per stage; the
launcher calls the tool before `exec`; `~/.claude/CLAUDE.md` gets a rule for the in-session case.
The MCP server itself is not changed.

## Technical Context

**Language/Version**: Python 3.9+ standard library only, run as `/usr/bin/python3 -I` (research R-9)

**Primary Dependencies**: none to install; uses `/usr/bin/openssl` (LibreSSL), `/usr/bin/osascript`,
and `java`/`keytool` of the JDK that runs the server (paths from `x-cert-check` in the profile)

**Storage**: local files — profile YAML, per-stage PKCS12 trust stores (password-less), state
directory `~/.inubit-mcp/<profile>/` (change log, remembered rejections, diagnostic log, lock)

**Testing**: `unittest` with local TLS servers on 127.0.0.1 and throw-away self-signed
certificates; dialog, notification, `keytool` and `--check-config` replaced by in-process fakes;
opt-in real-`keytool` test; CI step on Ubuntu (research R-12)

**Target Platform**: macOS workstation (Claude Code CLI and desktop Code tab start the launcher;
no terminal, research R-1); `--check`/`--accept` also work on Linux (no dialog there)

**Project Type**: CLI operator tool in `tools/` of this repository plus local configuration changes

**Performance Goals**: no-change start-up path ≤ ~6 s (5 s per host, all hosts in parallel; spec
≤ 10 s); with a dialog the start continues after ≤ 20 s (client timeout 30 s, research R-3)

**Constraints**: nothing on stdout in `--interactive`, stdin never read; never adopt without an
explicit operator action; no secrets read or passed (placeholders for `--check-config`); comments
and formatting of the profile file preserved (single-line replacement); customer names, hosts and
fingerprints only in local files (public repository, `NoCustomerIdentifiersTest`)

**Scale/Scope**: one profile with 2 active stages / 3 servers today (5 prepared); up to ~5
concurrent Claude Code sessions starting the launcher

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle / constraint | Assessment |
|---|---|
| I. Safe by Default | ✅ The tool changes only local trust settings and only after an explicit operator action (dialog click, `--accept` typed by the operator or run by Claude after an explicit "Ja"). Re-verification on all servers immediately before the change, backup, validation and rollback. No path auto-accepts; there is no test switch that could answer a dialog (R-12). |
| II. Secrets out of the conversation | ✅ No credential is read or needed; `--check-config` runs with a clean environment and placeholder values (R-6). Output contains fingerprints, subjects, IPs only. |
| III. Test-First | ✅ Offline `unittest` suite written before the code (local TLS servers, fakes for dialog/keytool/check-config); every safety rule (no auto-accept, re-verify, rollback, shared-pin refusal, rejection memory, stdout silence) has a dedicated test. |
| IV. Use-case-oriented tools | ✅ / n/a — no MCP tool is added; the CLI commands map to the operator's tasks (check, adopt, forget, prune). |
| V. Adapter isolation | n/a — server code unchanged. |
| VI. Bounded, structured output | ✅ `--json` with a stable shape (contract C-1); errors as short reasons, no raw dumps. |
| VII. Observability & auditability | ✅ stdout reserved for MCP in the launcher path; diagnostics to stderr/log; every adoption, rejection, timeout, failure and prune is one audit line (C-6). |
| Language: Java | ⚠️ Deviation recorded in Complexity Tracking: operator tool in Python like the existing `tools/*.py`. |
| Dependencies pinned and minimal | ✅ standard library only; system binaries. |
| SDD artifacts in English | ✅ (dialog and notification texts are English too). |
| Docs updated with behaviour | ✅ `docs/setup.md` §5 (rotation, tool, launcher line, CLAUDE.md rule) and `tools/` usage in the same change. |

Post-design re-check (after Phase 1): unchanged — all ✅, one recorded deviation.

## Project Structure

### Documentation (this feature)

```text
specs/006-tls-pin-rotation/
├── plan.md              # This file
├── research.md          # Phase 0: R-1 … R-12
├── data-model.md        # Phase 1: profile view, check result, rejection memory, log, backups
├── quickstart.md        # Phase 1: offline tests + live validation scenarios 1–6
├── contracts/
│   └── cli.md           # Phase 1: commands, exit codes, JSON, dialog, files, launcher line, CLAUDE.md rule
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
tools/
├── inubit-cert-check.py          # the tool (single file, stdlib only, #!/usr/bin/python3 -I)
└── tests/
    ├── support.py                # loads the tool module from its hyphenated file name
    ├── tls_fixtures.py           # local TLS servers, black-hole listener, throw-away certificates
    ├── run_with_fakes.py         # test-only wrapper: patches dialog/notify, then calls main()
    ├── test_profile_reader.py    # YAML subset reader, inheritance, pinSource
    ├── test_fetch.py             # fingerprint normalization, certificate fetch, timeouts
    ├── test_state.py             # state dir, logs, rejection store, lock
    ├── test_keytool.py           # trust-store list/import/delete (fake + opt-in real keytool)
    ├── test_check.py             # US1
    ├── test_accept.py            # US2 (incl. prune)
    └── test_interactive.py       # US3 (incl. forget)

docs/
└── setup.md                      # §5: certificate rotation, launcher line, CLAUDE.md rule

.github/workflows/ci.yml          # + step: python3 -I -m unittest discover -s tools/tests
CHANGELOG.md                      # Unreleased: tools/inubit-cert-check
```

Local, outside the repository (operator workstation, never committed):

```text
~/.local/bin/inubit-cert-check                 # installed copy of tools/inubit-cert-check.py (0755)
~/.local/bin/inubit-mcp-<profile>              # launcher: + one line before exec (contract C-7)
~/.config/inubit-mcp/<profile>.yaml            # per-stage tls blocks, x-cert-check settings
~/.config/inubit-mcp/<profile>-<stage>-truststore.p12   # one per stage (copies of the shared one)
~/.inubit-mcp/<profile>/cert-*.{log,json,lock}  # state (contract C-6)
~/.claude/CLAUDE.md                            # + rule (contract C-8)
```

**Structure Decision**: The tool is customer-agnostic and lives in `tools/` next to the other
operator scripts, so it is reviewed, tested in CI and documented with the server. Everything
profile-specific stays in local files. The server (`src/`) is not touched.

## Implementation Outline (input for /speckit-tasks)

1. **Convert the profile file** (local, by targeted edits, FR-001–FR-003): replace the shared
   `x-…-tls` anchor by an inline `tls` block per active stage, each with the current (old) pin and
   its own trust-store copy; update the commented-out prepared stages to show their own `tls` block;
   add `x-cert-check: { java, serverJar }`; keep the old shared trust store as backup. Verify with
   `--check-config` and a diff that only these lines changed.
2. **Tests first** for: YAML subset reader (anchors/aliases, inheritance, pinSource, line numbers,
   fail-closed on unknown shapes); fingerprint normalization; check (ok/changed/unreachable/
   conflict/timeout ≤ deadline, exit codes, JSON shape); accept (re-verify, refusal cases, backup,
   single-line edit preserving comments/quotes, trust-store add/dedupe, validation, rollback, log);
   rejection memory lifecycle; interactive (no stdout, stdin untouched, 20 s budget, detached worker,
   lock busy → skip, late accept → notification, dialog failure not remembered); prune; forget.
3. **Implement** `tools/inubit-cert-check.py` to make them pass (contract C-1…C-6).
4. **Install** to `~/.local/bin/inubit-cert-check`; run quickstart scenario 1 (expect `dev`
   changed, `int` ok).
5. **Launcher**: add the C-7 line; run quickstart scenarios 3–5 on test copies.
6. **CLAUDE.md** rule (C-8) with the real profile name.
7. **Docs/CI/CHANGELOG** in the repository.
8. **Real adoption** — only when the operator clicks "Accept" in the dialog (quickstart
   scenario 2); then prune decision with the operator. Nothing is committed or pushed before that.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Python instead of Java (constitution: Language Java) | The tool runs before the JVM in the launcher, must start in milliseconds, needs parallel TLS probes with hard timeouts, JSON, file locking and exact line edits; the repository already keeps operator tools in Python (`tools/*.py`). | A Java tool adds ≥ 1 s JVM start-up per check and a second build artifact; zsh lacks JSON, safe parallel timeouts and exact text editing. |
| Minimal YAML reader inside the tool | Standard library has no YAML parser and no dependencies may be added. | Using the server JAR's YAML library couples the tool to JAR internals; a new server option would need a server release (noted as possible follow-up). The reader is fail-closed and cross-checked against `--check-config`. |
