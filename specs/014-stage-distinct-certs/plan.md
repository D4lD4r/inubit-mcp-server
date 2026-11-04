# Implementation Plan: Stages Whose Servers Present Different Certificates

**Branch**: `014-stage-distinct-certs` | **Date**: 2026-11-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/014-stage-distinct-certs/spec.md`

## Summary

A stage can be marked with `tls.pinPerServer: true` (research R-1). In a marked stage every https
server carries its own `pinnedCertificateSha256` in its own `tls` block, the stage keeps one trust
store, and the stage's `tls` carries no pin. The MCP server gains only configuration rules (FR-004)
and one start-up log line (FR-005). The TLS check at connection time is unchanged, because it
already enforces one pin per server (research R-4).

The certificate tool `inubit-cert-check` (feature 006) learns the per-server workflow:
- **Check:** no conflict for a marked stage, offers and rejections per server, and a note when a
  server presents a certificate that another server of the stage expects.
- **Adopt and forget:** `--accept STAGE/SERVER FINGERPRINT` and `--forget STAGE[/SERVER]`.
- **Prune:** keeps every certificate that any server of the stage still expects.
- **Start-up dialog:** one decision per server.
- **Change log:** a `server=` field for server decisions.

Unmarked stages behave exactly as in feature 006. Documentation, the CHANGELOG and the C-8 rule
for the operator's `CLAUDE.md` are updated in the same change.

## Technical Context

**Language/Version**: Java 21 (MCP server: configuration model and validation); Python 3.9+
standard library only, run as `/usr/bin/python3 -I` (operator tool, unchanged from 006 R-9)

**Primary Dependencies**: no new ones. Server: Jackson YAML binding as today. Tool:
`/usr/bin/openssl`, `/usr/bin/osascript`, and `java`/`keytool` of the server's JDK, as in 006.

**Storage**: local files only.
- Unchanged from 006: the profile YAML, the per-stage PKCS12 trust store, and the state directory
  `~/.inubit-mcp/<profile>/`.
- New: rejection entries keyed by server id, and a `server=` field in the change log
  (research R-9, R-10).

**Testing**:
- Server: JUnit 5 for `ConfigLoaderTest`, `ConfigValidatorTest`, `EffectiveNodeConfigTest` and
  `InubitHttpClientTest`. The last one gets two servers with distinct self-signed certificates.
- Tool: `unittest` in `tools/tests` (local TLS servers, fakes for dialog, keytool and
  check-config), as in 006 R-12. CI runs both.

**Target Platform**: macOS workstation, as in 006. `--check`, `--accept`, `--prune` and `--forget`
also run on Linux; the dialog is macOS only.

**Project Type**: MCP server (Java, `src/`) plus CLI operator tool (`tools/`)

**Performance Goals**: unchanged.
- No-change start-up path: about 6 s or less (5 s per host, all hosts in parallel).
- With dialogs: the start continues after about 20 s at most in total, however many servers are
  offered (FR-014).

**Constraints**:
- Never adopt without an explicit operator action.
- Edit exactly one pin line per adoption, keeping comments and formatting.
- `--interactive` writes nothing to stdout and never reads stdin.
- The JSON of unmarked stages and the log lines of unmarked stages stay byte-compatible with 006.
- Customer names, hosts and fingerprints stay in local files only (`NoCustomerIdentifiersTest`).

**Scale/Scope**: one profile with a few stages and 1–3 servers per stage; up to about 5
concurrent Claude sessions starting the launcher.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle / constraint | Assessment |
|---|---|
| I. Safe by Default | ✅ No INUBIT write path is touched. Pinning gets stricter where the setting applies: each server accepts only its own certificate (FR-002, SC-004). Unmarked stages are unchanged (FR-017). Adoption stays operator-confirmed and scoped to one server, with re-verification of that server, backup, `--check-config`, rollback and an audit line (FR-009–FR-012). There is still no switch that answers a dialog automatically. |
| II. Secrets out of the conversation | ✅ No credential is read or passed. Output holds fingerprints, subjects, IPs and server ids only. |
| III. Test-First | ✅ Java validation rules and every new tool behaviour get failing tests first (outline step 2). Dedicated tests cover the safety rules: other servers untouched, refusal cases, rejection memory per server, no stdout in `--interactive`, and a server refusing the certificate of another server. |
| IV. Use-case-oriented tools | n/a. No MCP tool is added or changed. |
| V. Adapter isolation | ✅ The REST adapter (`PinningTrustManager`, `SslContexts`) is unchanged. The change sits in `config/`. |
| VI. Bounded, structured output | ✅ The `--json` shape is extended only by added fields (contract C-1′). Errors are short reasons. |
| VII. Observability & auditability | ✅ A start-up log line names the marked stages (FR-005). Every server decision is one audit line with `server=` (FR-011). Diagnostics go to stderr and the log; stdout stays reserved. |
| Language: Java | ⚠️ The operator tool stays Python. This is the existing deviation of 006, recorded again in Complexity Tracking. |
| TLS verification on by default | ✅ Unchanged. The new setting cannot switch verification off; a marked stage needs a trust store and a pin on every https server. |
| Dependencies pinned and minimal | ✅ None added. |
| SDD artifacts in English | ✅ |
| Docs updated with behaviour | ✅ `docs/setup.md` §3 and §5, `CHANGELOG.md` and the C-8 rule text, all in the same change (FR-018). `docs/tools.md` does not mention the certificate tool. |

Post-design re-check (after Phase 1): unchanged. All rows pass, with one recorded deviation. The
design adds no new runtime component and no new file in the state directory.

## Project Structure

### Documentation (this feature)

```text
specs/014-stage-distinct-certs/
├── plan.md              # This file
├── research.md          # Phase 0: R-1 … R-14
├── data-model.md        # Phase 1: configuration, profile view, check result, rejections, log
├── quickstart.md        # Phase 1: offline tests + live validation on a configuration copy
├── contracts/
│   ├── configuration.md # Phase 1: tls.pinPerServer and its validation rules (server)
│   └── cli.md           # Phase 1: delta to 006 contracts C-1…C-6, C-8
├── checklists/
│   └── requirements.md
├── reviews/             # spec reviews (r1, r2)
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
src/main/java/de/dadecker/inubit/mcp/
├── config/
│   ├── TlsConfig.java                 # + Optional<Boolean> pinPerServer
│   └── ConfigValidator.java           # + checkPinPerServer (FR-004), on the written group/node values
└── Launcher.java                      # + one start-up log line naming the marked stages (FR-005)

src/test/java/de/dadecker/inubit/mcp/
├── config/ConfigLoaderTest.java       # binding of tls.pinPerServer; unknown on defaults
├── config/ConfigValidatorTest.java    # every FR-004 rule, names stage and server
├── config/EffectiveNodeConfigTest.java# node pin wins, no stage pin in a marked stage
└── adapter/rest/InubitHttpClientTest.java  # server A refuses server B's certificate (SC-004)

src/test/resources/config/
└── pin-per-server.yaml                # customer-agnostic example (acme / int / a, b)

tools/
├── inubit-cert-check.py               # per-server workflow (contract cli.md)
└── tests/
    ├── test_profile_reader.py         # pinPerServer, per-server pin lines and pin sources
    ├── test_check.py                  # no conflict, per-server offers, expectedBy, JSON shape
    ├── test_accept.py                 # --accept STAGE/SERVER, refusals, prune rule
    ├── test_interactive.py            # per-server dialogs, rejection memory, migration
    ├── test_cli.py                    # argument forms, --forget STAGE/SERVER
    └── test_state.py                  # rejection keys, log line with server=

docs/setup.md                          # §3 tls.pinPerServer; §5 per-server rotation, C-8 rule text
CHANGELOG.md                           # Unreleased: feature 014
```

Local, outside the repository (operator workstation, never committed):

```text
~/.local/bin/inubit-cert-check   # reinstall the updated tool
~/.config/inubit-mcp/<profile>.yaml   # only if the operator marks a real stage
~/.claude/CLAUDE.md              # C-8′ rule text: only with the operator's OK
```

**Structure Decision**: same layout as features 006 and 012. The server keeps configuration rules
in `config/`. The tool stays a single file in `tools/` with its `unittest` suite. Nothing
profile-specific enters the repository.

## Implementation Outline (input for /speckit-tasks)

1. **Server, tests first** (contract configuration.md):
   - `tls.pinPerServer` is bound on group and node `tls`. `defaults.tls` stays an unknown key.
   - `checkPinPerServer` covers every FR-004 rule, each message naming stage and server.
   - One start-up log line lists the marked stages (FR-005).
   - `InubitHttpClientTest`: two local TLS servers, each pinned to its own certificate, one
     shared trust store; each refuses the other's certificate (SC-004).
2. **Tool, tests first** (contract cli.md):
   - Profile reader: `pinPerServer`, per-server pin line and pin source, the stage's own trust
     store without a pin, and configuration problems as a stage note (research R-7).
   - Check: per-server evaluation, the JSON fields of C-1′, the text report, and the superseded
     rule (FR-013).
   - `--accept STAGE/SERVER`: verify that one server; then backup, import, edit that pin line,
     `--check-config` and verification; refusals per FR-012.
   - `--prune`: the set rule for marked stages.
   - Rejection store keyed by server id, with removal of old stage keys (FR-015).
   - `--forget STAGE[/SERVER]`.
   - Interactive mode and dialog worker: one offer per server, the expectedBy warning in the dialog,
     the same 20 s and 60 s limits and the same lock.
   - Change log: the `server=` field.
3. **Regression**: every existing 006 test passes unchanged (SC-005); `NoCustomerIdentifiersTest`
   passes.
4. **Docs, CI, CHANGELOG**: `docs/setup.md` §3 and §5, the C-8′ rule text, and the CHANGELOG.
   CI already runs both suites; check that the new test files are discovered.
5. **Local validation** (quickstart): reinstall the tool, then run the scenarios on a configuration
   copy with two local TLS servers.
   - Live use against a real stage only after the operator decides to mark one.
   - The operator's `~/.claude/CLAUDE.md` is changed only with the operator's OK.
   - Nothing is adopted without an explicit operator action.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Python instead of Java for the operator tool (constitution: Language Java) | The existing tool of feature 006 is extended. It runs before the JVM in the launcher and must start in milliseconds (006 Complexity Tracking). | Rewriting the tool in Java now would change the start-up path and two features at once, and add at least 1 s of JVM start-up per check. |
| Minimal YAML reader inside the tool learns one more key | The standard library has no YAML parser and no dependency may be added (006). | Same as 006; the reader stays fail-closed and `--accept` cross-checks every edit with the server's `--check-config`. |
