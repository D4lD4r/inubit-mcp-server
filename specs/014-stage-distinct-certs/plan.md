# Implementation Plan: Stages Whose Servers Present Different Certificates

**Branch**: `014-stage-distinct-certs` | **Date**: 2026-11-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/014-stage-distinct-certs/spec.md`

## Summary

A stage can be marked with `tls.pinPerServer: true` (research R-1). In a marked stage every https
server carries its own `pinnedCertificateSha256` in its own `tls` block, the stage keeps one trust
store, and the stage's `tls` carries no pin. The MCP server gains configuration rules (FR-004,
including one own StartCLI and SOAP address per server of a marked stage), one start-up log line
(FR-005), and a certificate check before every StartCLI call to a server of a marked stage
(FR-005a, research R-15): a TLS handshake to the address StartCLI connects to, through the existing
pinning trust manager, that refuses the call before StartCLI starts. The TLS check of REST and SOAP
connections is unchanged, because it already enforces one pin per server (research R-4).

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
- Server: JUnit 5 for `ConfigLoaderTest`, `ConfigValidatorTest`, `EffectiveNodeConfigTest`,
  `MainTest`, `InubitHttpClientTest`, `CliRunnerTest` and the new `StartCliPinProbeTest`. The last
  three use two local TLS servers with distinct self-signed certificates and one shared trust
  store; `CliRunnerTest` uses `FakeProcessLauncher` to prove that StartCLI is never launched after a
  refused probe.
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
| I. Safe by Default | ✅ No INUBIT write path is touched. Pinning gets stricter where the setting applies: each server accepts only its own certificate (FR-002, SC-004) — on REST and SOAP through `PinningTrustManager`, and for StartCLI, which cannot pin, through a TLS handshake with the same trust manager to the StartCLI address before every call; on a mismatch or failure StartCLI is not started (FR-005a, research R-15). The short window between that check and StartCLI's own connection is stated in research R-15 and `docs/setup.md`, with subjectAltNames plus host-name verification as the way to close it. Unmarked stages are unchanged and not probed (FR-017). Adoption stays operator-confirmed and scoped to one server, with re-verification of that server, backup, `--check-config`, rollback and an audit line (FR-009–FR-012). There is still no switch that answers a dialog automatically. |
| II. Secrets out of the conversation | ✅ No credential is read or passed. Output holds fingerprints, subjects, IPs and server ids only. |
| III. Test-First | ✅ Java validation rules, the StartCLI probe and every new tool behaviour get failing tests first, inside the task that implements them (outline steps 1–2). Regression tests for behaviour that exists already are marked as such. Dedicated tests cover the safety rules: other servers untouched, refusal cases, rejection memory per server, no stdout in `--interactive`, a server refusing the certificate of another server (REST), and StartCLI never launched after a refused probe. |
| IV. Use-case-oriented tools | n/a. No MCP tool is added or changed. |
| V. Adapter isolation | ✅ The REST and SOAP adapters (`PinningTrustManager`, `SslContexts`, `InubitHttpClient`, `SoapE2eClient`) are unchanged. The probe lives in the CLI adapter (interface `adapter/cli/StartCliPinProbe` with the real `HandshakeStartCliPinProbe`, called from `CliRunner.run` before the credential guard) and reuses `SslContexts` the way the SOAP adapter already does; no domain or tool code learns about TLS. Configuration rules sit in `config/`. |
| VI. Bounded, structured output | ✅ The `--json` shape is extended only by added fields (contract C-1′). Errors are short reasons. |
| VII. Observability & auditability | ✅ A start-up log line names the marked stages (FR-005). Every server decision is one audit line with `server=` (FR-011). Diagnostics go to stderr and the log; stdout stays reserved. |
| Language: Java | ⚠️ The operator tool stays Python. This is the existing deviation of 006, recorded again in Complexity Tracking. |
| TLS verification on by default | ✅ Unchanged. The new setting cannot switch verification off; a marked stage needs a trust store and a pin on every https server. |
| Dependencies pinned and minimal | ✅ None added. |
| SDD artifacts in English | ✅ |
| Docs updated with behaviour | ✅ `docs/setup.md` §3 and §5, `CHANGELOG.md` and the C-8 rule text, all in the same change (FR-018). `docs/tools.md` does not mention the certificate tool. |

Post-design re-check (after Phase 1, revised after plan review r1): all rows pass, with one
recorded deviation. The design adds one runtime component in the CLI adapter (the StartCLI pin
probe, active for marked stages only) and no new file in the state directory.

## Project Structure

### Documentation (this feature)

```text
specs/014-stage-distinct-certs/
├── plan.md              # This file
├── research.md          # Phase 0: R-1 … R-15
├── data-model.md        # Phase 1: configuration, profile view, check result, rejections, log
├── quickstart.md        # Phase 1: offline tests + live validation on a configuration copy
├── contracts/
│   ├── configuration.md # Phase 1: tls.pinPerServer and its validation rules (server)
│   └── cli.md           # Phase 1: delta to 006 contracts C-1…C-6, C-8
├── checklists/
│   └── requirements.md
├── reviews/             # spec reviews (r1, r2), plan review (r1)
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
src/main/java/de/dadecker/inubit/mcp/
├── config/
│   ├── TlsConfig.java                 # + Optional<Boolean> pinPerServer
│   ├── EffectiveNodeConfig.java       # + Tls.pinPerServer (group value; old constructor kept)
│   └── ConfigValidator.java           # + checkPinPerServer (FR-004, V-1…V-11), on the written group/node values
├── adapter/cli/
│   ├── StartCliPinProbe.java          # new interface: check(server, trustStorePassword) (FR-005a)
│   ├── HandshakeStartCliPinProbe.java # new: real probe, TLS handshake to the StartCLI address before StartCLI
│   ├── CliRunner.java                 # 4-arg constructor kept (real probe) + 5-arg with probe; new run overload with trustStorePassword, probe before guard.acquire()
│   ├── CliTagRunner.java              # call the new run overload with credentials.trustStorePassword()
│   ├── CliExportRunner.java           # same
│   ├── CliImportRunner.java           # same
│   └── v81/V81ProcessControlAdapter.java  # same
├── Wiring.java                        # unchanged: the 4-arg CliRunner constructor uses the real probe
└── Launcher.java                      # + one start-up log line (FR-005); -v runner unchanged (no probe)

src/test/java/de/dadecker/inubit/mcp/
├── MainTest.java                      # start-up log line names the marked stages (FR-005)
├── config/ConfigLoaderTest.java       # binding of tls.pinPerServer; unknown on defaults
├── config/ConfigValidatorTest.java    # every FR-004 rule (V-1…V-11), names stage and server
├── config/EffectiveNodeConfigTest.java# node pin wins, no stage pin; Tls.pinPerServer per node
├── adapter/rest/InubitHttpClientTest.java  # server A refuses server B's certificate, cause CertificateException (SC-004)
├── adapter/rest/TlsTestServer.java    # usable from adapter/cli tests; + handshake counter, silent mode
├── adapter/cli/StartCliPinProbeTest.java   # new: swap refused, unreachable, timeout, http not probed, error texts
└── adapter/cli/CliRunnerTest.java     # new methods only: refused probe → no launch, guard untouched; unmarked not probed

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

docs/setup.md                          # §3 tls.pinPerServer; §5 per-server rotation, C-8′ rule text, StartCLI probe and its remaining window
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
   - `checkPinPerServer` covers every FR-004 rule (V-1…V-11), each message naming stage and server.
   - One start-up log line lists the marked stages (FR-005).
   - `InubitHttpClientTest` (regression): two local TLS servers, each pinned to its own
     certificate, one shared trust store; each refuses the other's certificate with the cause
     `CertificateException` (SC-004).
   - StartCLI probe (FR-005a, research R-15): `HandshakeStartCliPinProbe` against two local TLS
     servers, then wired into `CliRunner.run` for marked servers only, before the credential guard
     is entered; StartCLI is never launched after a refused probe; unmarked servers are never
     probed. The existing `CliRunner` constructor and `run` signatures stay, so existing callers
     and tests are not edited.
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
4. **Docs, CI, CHANGELOG**: `docs/setup.md` §3 and §5, the C-8′ rule text (`TLS_ERROR` from
   `CertPathValidatorException`, `SunCertPathBuilderException` or a pin mismatch; prune hint kept), the StartCLI probe with its remaining window and the SAN recommendation, per-node
   `cli.url` / `e2e.soap.baseUrl` in a marked stage, and the CHANGELOG.
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
