---

description: "Task list for stages whose servers present different certificates (feature 014)"
---

# Tasks: Stages Whose Servers Present Different Certificates

**Input**: Design documents from `/specs/014-stage-distinct-certs/`

**Prerequisites**:
- [plan.md](plan.md) and [spec.md](spec.md) (clarifications 2026-11-03 and 2026-11-05, spec reviews
  r1–r2, plan reviews r1–r2)
- [research.md](research.md) (R-1…R-15) and [data-model.md](data-model.md)
- [contracts/configuration.md](contracts/configuration.md) (V-1…V-11, runtime behaviour) and
  [contracts/cli.md](contracts/cli.md) (C-1′…C-5′, C-8′, delta to
  [006 contracts/cli.md](../006-tls-pin-rotation/contracts/cli.md))
- [quickstart.md](quickstart.md) (scenario numbers `Q<n>` below)
- the code of features 001–011 (`main` after 0.7.0 work), in particular the tool of feature 006
  (`tools/inubit-cert-check.py` and its suite `tools/tests/`) and the CLI adapter
  (`src/main/java/de/dadecker/inubit/mcp/adapter/cli/CliRunner.java`)

**Tests**: REQUIRED (Constitution III, TDD). Every task that adds behaviour contains its own tests
and runs in this order: write the failing tests, run them and see them fail on an assertion, then
implement until they pass. Test and implementation of one behaviour are one task, so every task
ends green and every commit stays green. Tasks marked **Regression** cover behaviour that exists
already: their tests pass at once, there is no red step, and a failing regression test means stop
and report instead of changing production code. A task is done when `mvn -q -o verify` (Java tasks)
or `/usr/bin/python3 -I -m unittest discover -s tools/tests -v` (tool tasks) is green. Every
existing 006 test stays unchanged (SC-005).

**Organization**: the tool's profile view and change-log field are the foundation (Phase 2). US1
(configuration, validation, start-up log, runtime pin incl. the StartCLI probe, conflict-free check)
and US2 (per-server adoption, prune, serialization) are both P1; US3 (dialog per server, rejection
memory, in-session rule) is P2.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on unfinished tasks)
- **[Story]**: US1 declare and verify, US2 adopt per server, US3 confirm at start-up and in a Claude
  session

## Path Conventions

| Short form | Expands to |
|---|---|
| `main/…` | `src/main/java/de/dadecker/inubit/mcp/…` |
| `test/…` | `src/test/java/de/dadecker/inubit/mcp/…` |
| `testres/…` | `src/test/resources/…` |
| `tool` | `tools/inubit-cert-check.py` |

## Safety rules

- **While developing**: no access to a real INUBIT stage, the operator's profile
  (`~/.config/inubit-mcp/*`), their trust stores, their state directory (`~/.inubit-mcp/*`), their
  installed tool (`~/.local/bin/inubit-cert-check`) or their `~/.claude/CLAUDE.md`. Tool tests use
  local TLS servers with throw-away certificates (`tools/tests/tls_fixtures.py`), `FakeKeytool`,
  `FakeCheckConfig` and temp directories; Java tests use WireMock, `TlsTestServer`,
  `TestCertificates` and `FakeProcessLauncher` (no real StartCLI).
- **Never adopt**: no task adopts a certificate on a real stage. Only the operator does, in T032.
- **Public repository**: profile `acme`, stages `dev` / `int`, servers `int/a` / `int/b`, hosts under
  `example.test`, fictitious fingerprints only; no customer names in files, commit messages or PR
  descriptions; the identifier hooks must pass; never use `--no-verify`.
- **No new dependency** (plan: Technical Context): JDK and Jackson on the server side; Python
  standard library, `/usr/bin/openssl`, `/usr/bin/osascript`, `keytool` for the tool.

## Decisions on the points found while generating this list (2026-11-05)

The reviewed spec governs; the other documents were aligned in the same change.

- **Stage-level `rejected` of a marked stage** is `null` (spec FR-007, research R-8);
  data-model.md and contracts/cli.md now say `null` too. T013 tests `null`.
- **Concurrent adoption (Story 2 AS 9)**: AS 9 now states the R-12 behaviour explicitly — the two
  adoptions run one after another and both land; a command-line adoption that waits longer than
  30 s for an open dialog is refused with
  "the profile lock is held by another process … for more than 30 s", changes nothing and succeeds when
  repeated. T019 tests both orders.
- **Flow-mapping server `tls`**: the tool keeps failing closed on a server `tls` that is not a
  block mapping or an alias (`tools/inubit-cert-check.py`); research.md's example now uses the block
  form, and the documentation (T025) shows only the block form.
- **`--check-config` summary**: no TLS values in the summary (no plan change); quickstart.md now
  expects exit 0 and `Result: OK`, and T031 checks that.
- **`noOfferReason` of an unchanged server**: stays absent (`None`) — an `ok` server offers nothing
  because nothing changed, so there is nothing to refuse (FR-012 refusal reasons apply to `changed`
  servers only, data-model.md).

### Decisions after plan review r1 (2026-11-05, `reviews/plan-r1.md`)

- **StartCLI (finding 2, operator decision)**: in a marked stage the MCP server checks the
  certificate at the StartCLI address against the server's own pin before every StartCLI call and
  refuses the call before StartCLI starts (spec FR-005a, clarification 2026-11-05, research R-15).
  FR-005 and SC-004 now hold for REST, SOAP and StartCLI. The window between probe and StartCLI's
  own connection is documented (T026). Tasks T011, T012, T026.
- **C-8′ trigger (finding 1)**: `TLS_ERROR` caused by `CertPathValidatorException` or by a pin
  mismatch (`CertificateException`) (spec FR-016, research R-4, contracts/cli.md). T010 asserts the
  cause of the swapped-certificate case; T024, T025, T032 use the wider trigger.
- **Shared addresses (finding 11)**: a server of a marked stage with more than one server must not
  inherit `cli.url` or `e2e.soap.baseUrl` from the stage (spec FR-004, contract V-10, V-11). T008.
- **Task shape (findings 3–7)**: test-only tasks merged with their implementation; T006, T010 and
  T019 marked **Regression**; check, report, dialog and worker tasks split; `[P]` markers follow the
  Dependencies section.
- **Leftover stage-keyed rejection under `--interactive` (finding 10)**: kept as in data-model
  R-9 and tested in T022.

### Decisions after plan review r2 (2026-11-05, `reviews/plan-r2.md`)

- **`CliRunner` API (finding 1)**: the four-argument constructor stays and delegates to a
  five-argument one with the real `HandshakeStartCliPinProbe`; `StartCliPinProbe` is an interface so
  `CliRunnerTest` can pass a counting fake. The `run` signatures stay, so existing tests are not
  edited (the trust-store password overload of r2 was dropped in r3, see below). `Launcher` is not
  touched by T012. T011, T012, T030.
- **T009/T012 (finding 2)**: independent, since T012 no longer edits `Launcher.java`.
- **Probe before the guard (finding 3)**: the probe runs before `guard.acquire()` (research R-15,
  data-model). T011, T012.
- **Error texts (findings 4, 5)**: the `UNREACHABLE` next step names the effective `cli.url`;
  equality with the REST texts is asserted for `TLS_ERROR` only; a silent listener yields
  `TIMEOUT`. T011.
- **Test helper (finding 6)**: `TlsTestServer` gains a handshake counter and a silent mode. T011.
- **C-8′ (findings 7, 8)**: trigger also on `SunCertPathBuilderException`; the prune hint stays
  (`--prune <stage>`, the stage part of a `stage/server` target). Spec FR-016, research R-4,
  contracts/cli.md; T024, T025, T028, T032.
- **`noOfferReason` (finding 9)**: exactly `configuration problem`, no suffix (research R-8).
- **Quickstart (finding 10)**: the FR-005 log line appears at server start only (Q3); new Q3a
  exercises FR-005a without INUBIT, run by T031.

### Decisions after plan review r3 (2026-11-05, `reviews/plan-r3.md`)

- **Existing test files (finding 1)**: they get additions only; no existing line is changed or
  removed, except in `tools/tests/support.py` (T002) and
  `src/test/java/de/dadecker/inubit/mcp/adapter/rest/TlsTestServer.java` (T011). T030.
- **No trust-store password for the probe (finding 2)**: a node with a CLI never has a trust-store
  password (`ConfigValidator`, `NodeSecrets`, `SslContexts`; research R-15), so the probe is
  `check(server)`, there is no new `run` overload, and `CliTagRunner`, `CliExportRunner`,
  `CliImportRunner` and `V81ProcessControlAdapter` stay unchanged. T011, T012.
- **Timeout before TLS error (finding 3)**: a `SocketTimeoutException` anywhere in the cause chain
  maps to `TIMEOUT`, checked before `SSLException` (research R-15). T011.
- **Q3a (finding 4)**: a second HTTPS stub with certificate B is `int/b`'s `baseUrl`, so `int/b`
  passes the REST login and reaches the probe (four endpoints). T031.
- **`noOfferReason` of an unreachable server (finding 5)**: `null`; an `unreachable` server is never
  `changed` (006 `server_status`), so `unreachable` is no `noOfferReason` value (data-model, R-8,
  C-1′). T013.

---

## Phase 1: Setup

- [X] T001 [P] Customer-agnostic Java fixture `testres/config/pin-per-server.yaml`
  (`src/test/resources/config/pin-per-server.yaml`): the example of contracts/configuration.md —
  profile `acme`; uniform stage `dev` (stage pin, own trust store); marked stage `int` with
  `tls.pinPerServer: true`, `tls.trustStore` and `disableHostnameVerification: true` on the stage, and
  nodes `a` and `b` with their own block-form `tls.pinnedCertificateSha256` (distinct fictitious
  fingerprints, hosts `int-a.example.test` / `int-b.example.test`); no `cli.url` and no
  `e2e.soap.baseUrl` on the stage. Header comment "every name is fictitious". It is loaded by the
  tests of T005–T009 and T012; `NoCustomerIdentifiersTest` covers it.
- [X] T002 [P] Test helpers in `tools/tests/support.py` (research R-14): `write_profile` gains a
  per-stage `pin_per_server` flag (writes `pinPerServer: true` into the stage's inline `tls` block,
  or into the anchor when the stage uses `tls`), and per-server TLS: a server entry may be
  `(node, baseUrl, tls)` where `tls` is a dict `{pin, pin_raw, store, alias, pin_per_server}`
  written as a block mapping, or `tls: *<alias>` when `alias` is given. New helper
  `marked_stage(testcase, nodes=("a", "b"))` that creates one throw-away certificate per server with
  `tls_fixtures.make_cert`, starts one `tls_fixtures.TlsServer` per server, registers cleanup, and
  returns the certificates, fingerprints and ports. Existing callers stay unchanged: the full
  `tools/tests` suite is green after this task.

---

## Phase 2: Foundational — the tool's profile view and change-log field

**Purpose**: every tool behaviour of US1–US3 reads the marked stage through the profile view and
writes `server=` lines.

**⚠️ CRITICAL**: no tool task of US1–US3 starts before T004. The Java tasks of US1 (T005–T012) do not
depend on this phase and may start after T001.

- [X] T003 Profile view for marked stages (FR-003, FR-004, research R-7, data-model "Profile view").
  Write the failing tests first in `tools/tests/test_profile_reader.py`, new class
  `PinPerServerTest(ReaderTestCase)`, and see them fail:
  - `pinPerServer: true` in the stage's inline `tls` → `Stage.pin_per_server` is `True`; also when the
    stage's `tls` is an alias to an anchor with `pinPerServer: true`; `false` or absent → `False`, and
    the `Stage` and `Server` objects of every existing reader test stay equal to 006;
  - marked stage: `Stage.pin` is `None`; `Stage.pin_source` is `"server"`; `Stage.expected` is "the set
    of the pins of its https servers"; `Stage.own_tls` is `True` exactly when "`trustStore` written in
    the stage's own inline `tls` block, and that file (real path) used by no other stage; no pin
    condition", so `False` for a trust store through a stage alias, inherited from `defaults.tls`, or
    shared with another stage;
  - `Server.pin_source`: `server` ("the server's own inline `tls` block has the pin") · `alias` ("the
    server's `tls` is an alias") · `stage` ("inherited"); `Server.pin_line` is the 1-based line of
    `pinnedCertificateSha256` when `pin_source = server`, else `None`;
  - `Stage.problem` is `None` for a valid marked stage, else "the first broken rule" as a short text:
    a stage pin (inline or through an alias), a trust store on a server, an https server without its
    own pin, `pinPerServer` on a node, a marked stage without any effective trust store; an http
    server of a marked stage needs no pin;
  - a server `tls` written as a flow mapping still fails closed with the 006 message, and a
    non-boolean `pinPerServer` value fails closed like any other malformed value (006 R-7).
  Then implement in `tools/inubit-cert-check.py`: `TLS_KEYS` gains `pinPerServer` (parsed as a
  boolean in `_tls_block`); dataclass `Server` gains `pin_source` and `pin_line`; dataclass `Stage`
  gains `pin_per_server`, `expected` and `problem`, all with defaults so every 006 construction and
  comparison is unchanged; `_read_stage` records each server's pin source and pin line;
  `_finish_stages` computes `own_tls` for marked stages without the stage-pin condition and sets
  `problem`. The reader does not duplicate the server's validation beyond the R-7 rules.
- [X] T004 Change-log field `server=` (FR-011, research R-10, data-model "Change log entry"). Write
  the failing tests first in `tools/tests/test_state.py` `ChangeLogTest` and see them fail:
  `log_change(..., server="int/b")` writes
  `… profile=acme stage=int server=int/b outcome=adopted old=… new=… notBefore=… by=cli` with
  `server=` right after `stage=`; without `server` the line is byte-identical to the 006 format (exact
  string assertion); a `pruned` line of a marked stage has `new=-` and no `server=`. Then extend
  `State.log_change(self, stage, outcome, old, new, not_before, by, now=None, server=None)` in
  `tools/inubit-cert-check.py`.

**Checkpoint**: the tool reads marked stages; nothing else behaves differently yet.

---

## Phase 3: User Story 1 — Declare and verify a stage with per-server certificates (Priority: P1) 🎯 MVP

**Goal**: `tls.pinPerServer` is accepted and validated by the MCP server, logged at start, enforced
per server on REST, SOAP and — through the probe — StartCLI, and the check reports a marked stage
without a conflict.

**Independent Test**: with `pin-per-server.yaml` and two local TLS servers presenting different
certificates, `--check-config` passes, both servers connect, a CLI call to a server presenting the
other server's certificate is refused before StartCLI starts, and `inubit-cert-check --check`
reports `int` as `ok`, no conflict, exit 0 (spec US1 Independent Test, AS 8, Q1, Q2, Q3, Q4 step a).

- [X] T005 [P] [US1] Bind the key (FR-001, research R-1, R-2, contract V-2/V-9). Write the failing
  tests first in `test/config/ConfigLoaderTest.java` (new `@Nested class PinPerServer`) and see them
  fail: `pin-per-server.yaml` loads and `group.tls().pinPerServer()` is `Optional.of(true)` for `int`
  and empty for `dev`; `pinPerServer` on a node's `tls` binds (it is refused by the validator in
  T007); `defaults.tls` with `pinPerServer` is refused with `Unknown key 'tls'` (existing behaviour,
  asserted); `full.yaml` and `anchors.yaml` load exactly as before. Then add
  `Optional<Boolean> pinPerServer` to `main/config/TlsConfig.java` (`EMPTY`, compact constructor with
  `Optionals.orEmpty`) and a helper `boolean marksStage()` (`pinPerServer.orElse(false)`); adapt every
  `new TlsConfig(` call site.
- [X] T006 [P] [US1] **Regression** — effective TLS of a marked stage (FR-002, FR-003, research R-4;
  after T005). The behaviour exists; the test passes at once, there is no red step. In
  `test/config/EffectiveNodeConfigTest.java`: for each node of `int` in `pin-per-server.yaml` the
  effective `Tls` has the group's trust store and that node's own pin, and no node gets another
  node's pin. `main/config/EffectiveNodeConfig.java` stays unchanged in this task; if the test
  fails, stop and report, because R-4 assumes it.
- [X] T007 [P] [US1] Validation rules V-1…V-9 (FR-004, research R-3, contract V-1…V-9; after T005).
  Write the failing tests first in `test/config/ConfigValidatorTest.java`, new
  `@Nested class PinPerServer`, one test per row, each asserting the exact message of
  contracts/configuration.md with `{group}` / `{node}` rendered by the profile's terminology (also
  once with custom terms), and see them fail:
  - V-1 `pinPerServer` on a node (inline and through an alias) → `int/b: tls.pinPerServer is only
    valid on a {group}`;
  - V-3 stage pin, inline and through an alias → `{group} 'int': tls.pinnedCertificateSha256 is not
    allowed with tls.pinPerServer; set it on each {node}`;
  - V-4 no stage trust store → `{group} 'int': tls.pinPerServer needs tls.trustStore on the {group}`;
  - V-5 trust store on a node → `int/b: tls.trustStore is not allowed on this {node} of {group} 'int' with
    tls.pinPerServer; set it on the {group}`;
  - V-6 https node without own pin → `int/b: tls.pinPerServer on {group} 'int' needs
    tls.pinnedCertificateSha256 on this {node}`;
  - V-7 http node with `allowInsecureHttp: true` and no pin → valid; V-8 node pin through an alias →
    valid; V-9 `pinPerServer: false` or absent → no new finding; the fixture → no error;
  - a malformed node pin in a marked stage is still reported by `checkTls` (format rule unchanged).
  Then add `ConfigValidator.checkPinPerServer(ProfileConfig config, Findings findings)` in
  `main/config/ConfigValidator.java`, called from `validate` after `checkStructure`, walking
  `config.groups()` and `group.nodes()` on the written `GroupConfig.tls()` / `NodeConfig.tls()` values
  (not the effective ones), messages through `findings.termError`.
- [X] T008 [US1] Validation rules V-10/V-11 — one own StartCLI and SOAP address per server of a
  marked stage (FR-004, research R-3 "Note on the two address rules", R-4, contract V-10/V-11;
  after T007, same files). Write the failing tests first in `test/config/ConfigValidatorTest.java`
  `PinPerServer` and see them fail: a marked stage with nodes `a`, `b` and `cli.url` on the stage →
  one error per inheriting node, `int/b: cli.url is inherited from {group} 'int' with
  tls.pinPerServer; set cli.url on each {node}`; the same for `e2e.soap.baseUrl` (V-11); no error
  when every node sets its own value, when the marked stage has only one node, or for an unmarked
  stage (where the existing "cli.url is set for 2 {nodes}" warning is unchanged, asserted). Then
  extend `checkPinPerServer` in `main/config/ConfigValidator.java` on the written
  `GroupConfig.cli()` / `NodeConfig.cli()` and `GroupConfig.e2e().soap()` /
  `NodeConfig.e2e().soap()` values.
- [X] T009 [P] [US1] Start-up log line (FR-005, research R-5; after T005). Write the failing tests
  first in `test/MainTest.java` and see them fail: starting with `pin-per-server.yaml` logs exactly
  `Stages with one pinned certificate per server (tls.pinPerServer): int` once (logback
  `ListAppender` on the `Launcher` logger, as in `test/security/SecretLeakTest.java`); a profile
  without a marked stage logs no such line; stdout carries only MCP traffic (existing assertion
  pattern). Then in `main/Launcher.java` `serve(...)`, right after the "started for profile" line,
  `LOG.info("Stages with one pinned certificate per server (tls.pinPerServer): {}", names)` with the
  marked stage names in file order, comma-separated, only when at least one stage is marked.
- [X] T010 [P] [US1] **Regression** — REST runtime pin per server (FR-002, FR-005, SC-001, SC-004,
  research R-4). The behaviour exists; the tests pass at once, there is no red step. In
  `test/adapter/rest/InubitHttpClientTest.java`: two WireMock HTTPS servers with
  `TestCertificates.localhostKeyStore()` and `selfsignedKeyStore()`, one shared
  `TestCertificates.trustStore()`, each server pinned to its own certificate (group trust store, node
  pin) → both calls succeed; each server pinned to the *other* server's certificate → `TLS_ERROR` in
  both directions, and the error message reports the cause `(CertificateException)`, not
  `CertPathValidatorException` (plan review r1 finding 1: the chain passes the shared store, only the
  pin fails). `PinningTrustManager` and `SslContexts` stay unchanged; if a test fails, stop and
  report.
- [X] T011 [P] [US1] StartCLI pin probe (FR-005a, SC-004, research R-15, data-model "StartCLI pin
  probe", contract configuration.md "Runtime behaviour"). Write the failing tests first in the new
  `test/adapter/cli/StartCliPinProbeTest.java` and see them fail. Test support (plan review r2
  finding 6): `test/adapter/rest/TlsTestServer.java` becomes usable from `adapter/cli` (public class
  and the members the new test uses) and gains a handshake counter (`handshakes()`: number of
  completed or attempted TLS handshakes, counted per accepted connection) and a "silent" mode (the
  listener accepts the TCP connection but never answers the handshake); existing users of
  `TlsTestServer` stay unchanged. Two `TlsTestServer`s with `TestCertificates.localhostKeyStore()`
  and `selfsignedKeyStore()`, one shared `TestCertificates.trustStore()`, an `EffectiveNodeConfig`
  per server whose `cli.url` points to its `TlsTestServer`:
  - pin = the certificate the address presents → the probe returns normally;
  - swapped certificate (address of `int/a` presents `int/b`'s certificate) → `ToolErrorException`
    with `TLS_ERROR`, the message names `int/a`, the StartCLI address `<host>:<port>`, the cause
    `(CertificateException)` and "StartCLI was not started";
  - `TLS_ERROR` text: likely cause and next step equal the texts of `InubitHttpClient`'s
    `TLS_ERROR` (string comparison against the REST error for the same swap); this equality is
    asserted for `TLS_ERROR` only (plan review r2 finding 4);
  - closed port → `UNREACHABLE`, message naming the StartCLI address, next step naming the
    effective `cli.url` (`Check cli.url (<url>), DNS and the VPN, then retry`), not `baseUrl`;
  - silent listener (accepts, never answers the handshake) with a short `server.timeout()` →
    `TIMEOUT`, message naming the StartCLI address and "StartCLI was not started", returned within
    the timeout plus a margin (plan review r2 finding 5);
  - `cli.url` with `http://` → no connection attempt (the `TlsTestServer`'s handshake counter stays
    0);
  - with hostname verification on, the endpoint identification algorithm is set (a certificate
    whose subjectAltNames do not match the address is refused with `TLS_ERROR`).
  Then add the interface `main/adapter/cli/StartCliPinProbe.java`
  (`void check(EffectiveNodeConfig server)`) and its real
  implementation `main/adapter/cli/HandshakeStartCliPinProbe.java` (the test exercises this class):
  host and port of `server.cli().url()` (port `-1` → 443), `http` → return without contact,
  `SslContexts.forServer(server.secretLabels(), server.tls(), Optional.empty())` (a node with a CLI
  never has a trust-store password, research R-15), `SSLSocket` with
  SNI, endpoint identification `HTTPS` unless `disableHostnameVerification`, connect and read
  timeout `server.timeout()`, `startHandshake()`, close; errors mapped as research R-15
  (`TIMEOUT` when a `SocketTimeoutException` is anywhere in the cause chain, checked before
  `SSLException`, so a handshake read timeout wrapped in an `SSLException` is still `TIMEOUT`; then
  `TLS_ERROR` / `UNREACHABLE`; `TLS_ERROR` texts as the REST path, `UNREACHABLE` next step naming
  `cli.url`).
- [X] T012 [US1] Wire the probe into StartCLI calls (FR-005a, FR-017, SC-004, research R-15; after
  T005, T006 and T011). Write the failing tests first and see them fail:
  - `test/config/EffectiveNodeConfigTest.java`: `Tls.pinPerServer()` is `true` for both nodes of
    `int` and `false` for `dev` in `pin-per-server.yaml`; the three-argument `Tls` constructor yields
    `false`;
  - `test/adapter/cli/CliRunnerTest.java` (new test methods only; the existing 8 `run(` calls and
    the existing `new CliRunner(` calls stay as they are) with `FakeProcessLauncher`, the real
    `HandshakeStartCliPinProbe` passed to the new five-argument constructor and two `TlsTestServer`s:
    a server of a marked stage whose StartCLI address presents the other server's certificate →
    the existing `run(...)` throws `TLS_ERROR` naming the pin mismatch and the launcher recorded
    **no** launch; the same for an unreachable address (`UNREACHABLE`, no launch); a matching
    certificate → StartCLI is launched once with exactly the arguments it got before this feature;
    the probe runs before `guard.acquire()` (plan review r2 finding 3): while the test holds the
    permit of an unconfirmed guard (first login in progress), a call to a marked server whose
    address presents the wrong certificate still fails at once with `TLS_ERROR`
    (`assertTimeoutPreemptively`, it does not wait for the permit); a refused probe leaves the guard
    unconfirmed and not paused (a following call is not answered with a cached `AUTH_FAILED`);
  - with a counting fake `StartCliPinProbe` (five-argument constructor): unmarked stage → the probe
    is never called, and the launched arguments, environment and stdin are byte-identical to
    today's (FR-017); `execute(... "-v" ...)` (the client version probe) never calls the probe, for
    marked servers too; each existing `run` signature calls the probe once for a marked server;
  - the four-argument constructor still exists and uses `HandshakeStartCliPinProbe` (a marked
    server whose address presents the wrong certificate is refused through it).
  Then: `EffectiveNodeConfig.Tls` gains `boolean pinPerServer` from the group's
  `tls.pinPerServer` (a constructor with the old three arguments sets `false`) in
  `main/config/EffectiveNodeConfig.java`. In `main/adapter/cli/CliRunner.java`: the existing
  four-argument constructor stays and delegates to a new five-argument constructor that takes a
  `StartCliPinProbe` (default `new HandshakeStartCliPinProbe()`); the two existing `run` signatures
  stay and call the probe **before** `guard.acquire()`, only when `server.tls().pinPerServer()` is
  true. There is no new `run` overload and no trust-store password: a node with a CLI never has one
  (research R-15), so the production callers `CliTagRunner`, `CliExportRunner`, `CliImportRunner`
  and `V81ProcessControlAdapter` are not edited. `main/Wiring.java` keeps the four-argument
  constructor (real probe); `main/Launcher.java` is not touched (its `-v` runner only calls
  `execute`). Test files changed by this task: `test/config/EffectiveNodeConfigTest.java` and
  `test/adapter/cli/CliRunnerTest.java` (additions only). Every existing CLI test stays green
  unchanged.
- [X] T013 [US1] Check evaluation and JSON for marked stages (FR-006, FR-007, FR-008, FR-010a,
  FR-015, FR-017, SC-001, SC-004, SC-005, contract C-1′, research R-8; after T004). Write the
  failing tests first with `support.marked_stage` (T002) in `tools/tests/test_check.py`, new class
  `PinPerServerCheckTest(CheckTestCase)`, and see them fail:
  - `test_distinct_certificates_are_ok_without_conflict` (stage `ok`, `conflict` false, exit 0,
    Story 1 AS 2); `test_one_server_marked_stage` and `test_all_servers_same_certificate` (no
    conflict, no warning); `test_server_presenting_other_servers_certificate_is_changed` (`int/a`
    `changed`, `expectedBy == ["int/b"]`, exit 10, Story 1 AS 3); `test_changed_server_gets_offer`
    (`int/b.offer` = F, `int/a` `ok`, stage `changed`, Story 2 AS 1); `test_changed_and_unreachable`
    (stage `changed`, `int/a` `unreachable` with `offer` and `noOfferReason` null, `int/b` still
    offered); `test_no_offer_reasons` — a `changed` server's `noOfferReason` is one of "`pin through
    alias` · `trust store not the stage's own` · `configuration problem`" for the matching profile,
    with `offer` null; `test_problem_reported`
    (stage `problem` set, no offer, servers still compared); `test_rejected_per_server`
    (`state.rejection("int/b")` equals the offer → server `rejected` true, status `changed`, exit
    10); `test_check_never_changes_profile_or_store` (sha256 unchanged, FR-017);
    `test_unmarked_stage_with_two_certificates_still_conflicts` (conflict, no offer, Story 1 AS 4);
  - JSON: a marked stage has exactly the C-1′ keys — `pinPerServer: true`, `pin: null`,
    `pinSource: "server"`, `conflict: false`, stage `offer: null`, stage `rejected: null`, `ownTls`,
    `problem`; each server has `offer`, `rejected`, `expectedBy` (list), `noOfferReason` (string or
    null) and `pinned` = its own pin; none of these new keys appears on a uniform stage, whose JSON is
    byte-identical to the 006 output (golden comparison).
  Then implement in `tools/inubit-cert-check.py`: `evaluate_stage` branches on
  `stage.pin_per_server`; `ServerResult` gains `offer`, `rejected`, `expected_by`, `no_offer_reason`
  (offer only when `changed`, `pin_source == "server"` with a pin line, stage `own_tls`, no
  `problem`; `no_offer_reason` only for a `changed` server without an offer, else `None`, also for
  an `unreachable` server, which is never `changed` in 006 `server_status`); stage `status` =
  worst server status (changed > unreachable > ok), `conflict` false, stage `offer` and `rejected`
  null; `check_json` adds the marked-only fields; exit codes of `run_check` unchanged (10 if any
  server `changed`, else 20 if any `unreachable`, else 0).
- [X] T014 [US1] Text report and superseded entries for marked stages (FR-007, FR-010a, FR-013,
  contract C-1′, research R-8, R-13; after T013, same files). Write the failing tests first and see
  them fail:
  - `tools/tests/test_check.py` `TrustStoreTest.test_marked_stage_superseded_entries`: store A, B, F
    with `int/a`→A and `int/b`→F → `supersededTrustStoreEntries` lists only B (Story 2 AS 8);
  - `tools/tests/test_check.py` `OutputTest.test_marked_stage_text_report`: the C-1′ lines
    `Stage int  CHANGED  (one pin per server)`, per server `old`/`new`/`subject`/`valid`,
    `offer: inubit-cert-check --profile acme --accept int/b <fp>`, `note: this is the certificate
    expected for int/a`, `no offer: …`, `rejected earlier: … (offered again only after --forget
    int/b)`, `configuration problem: <text> - run --check-config`; the text report of a uniform stage
    is unchanged (golden comparison).
  Then implement in `tools/inubit-cert-check.py`: `superseded_entries` uses `stage.expected` for
  marked stages; `check_text` adds the marked-only lines.

**Checkpoint**: US1 complete — a marked stage is configured, validated, logged, enforced per server
on REST, SOAP and StartCLI, and checked without a conflict; uniform stages unchanged.

---

## Phase 4: User Story 2 — Adopt a new certificate in a stage with per-server certificates (Priority: P1)

**Goal**: `--accept STAGE/SERVER FINGERPRINT` adopts for exactly one server; `--prune` keeps every
certificate a server still expects; adoptions run one after another.

**Independent Test**: on a configuration copy with `int/b` outdated: `--check` offers `int/b`,
`--accept int/b F` changes only `int/b`'s pin line, the store gains F, a backup exists, one `adopted`
line names `server=int/b` (spec US2 Independent Test, Q4 steps b–g).

- [X] T015 [US2] Targets on the command line (research R-6, contract "Targets"). Write the failing
  tests first in `tools/tests/test_cli.py` `UsageTest` and see them fail: `--accept int/b FP`,
  `--accept dev FP`, `--forget int/b` and `--forget int` parse into a target; each part must match
  `[a-z0-9][a-z0-9-]{0,31}`; `int/b/c`, `/b`, `int/`, `INT/b` → usage error, exit 2; `--prune int/b`
  → usage error, exit 2 (`--prune` keeps taking a stage). Then update `build_parser` / `parse_args`
  in `tools/inubit-cert-check.py` (`--accept TARGET FINGERPRINT [--by cli|claude]`,
  `--forget TARGET`).
- [X] T016 [US2] Refusals of per-server adoption (FR-010, FR-012, Story 2 AS 3 and AS 7, contract
  C-2′ step 1). Write the failing tests first in `tools/tests/test_accept.py`, new class
  `PinPerServerRefusalTest(AcceptTestCase)`, and see them fail; each case exits 3, leaves profile and
  trust store byte-identical (sha256), and names the reason on stderr and in the diagnostic log:
  - unknown stage; unknown server `int/z`;
  - the stage name of a marked stage → "stage int has one pin per server - name the server, e.g.
    int/b";
  - a server of a uniform stage → "stage dev has one pin for the stage - use --accept dev";
  - an http server; a server whose pin is reached through an alias or inherited; a stage that is not
    `ownTls` (store through an alias, from `defaults`, shared with another stage); a stage with a
    `problem` → "configuration not valid for tls.pinPerServer: <problem> — run --check-config";
    a malformed fingerprint;
  - a fresh fetch shows `int/b` unreachable or presenting another fingerprint → exit 3 and one
    `failed` line with `server=int/b` (006 FR-015);
  - in every case `int/a`'s `TlsServer` recorded no handshake (other servers are not contacted);
  - `int/b` already pins FINGERPRINT → "already pins", exit 0, nothing changed.
  Then implement the target resolution and refusals in `cmd_accept`, `accept_locked` and
  `_usable_stage` of `tools/inubit-cert-check.py` per C-2′ step 1: fetch only the named server; every
  uniform-stage refusal stays as in 006.
- [X] T017 [US2] Per-server adoption, success and rollback (FR-009, FR-010, FR-011, SC-002, SC-003,
  Story 2 AS 2, 4, 5, 6, contract C-2′ steps 2–7; after T016, same files). Write the failing tests
  first in `tools/tests/test_accept.py` and see them fail:
  - new class `PinPerServerSuccessTest(AcceptTestCase)`: the profile diff is exactly `int/b`'s pin
    line (indentation, quotes and trailing comment kept); `int/a`, `dev` and the server ids are
    unchanged on re-read; the store gains F under the alias `int-b-<8 hex>-<yyyymmdd>` and keeps A and
    B; F already in the store → no import; backups of profile and store exist with owner-only
    permissions; `FakeCheckConfig` was called; one `adopted` line `stage=int server=int/b … by=cli`
    (`--by claude` → `by=claude`); the rejection under `int/b` is cleared; `int/a` on `closed_port()`
    → adoption still succeeds and `int/a` is not contacted (AS 4); both servers changed, only `int/b`
    adopted → a following `--check` still reports `int/a` `changed` (AS 5);
  - new class `PinPerServerRollbackTest(AcceptTestCase)`: a failure after the backup at each step
    (keytool import, pin-line edit, `--check-config`, verification) → profile and store restored
    byte-identical, exit 4, one `failed` line with `server=int/b` (AS 6).
  Then implement in `_change_and_report` and `verify_adoption` of `tools/inubit-cert-check.py`:
  `trust_alias` builds `<stage>-<node>-<8 hex>-<yyyymmdd>`; `replace_pin_line` edits
  `Server.pin_line` (refusal message names the server's pin line); verification checks that this
  server's pin is new, every other pin (servers and stages) and every server id is unchanged, and
  the store lists F; log lines carry `server=`; the summary and reconnect hint stay as in 006.
  Uniform-stage adoption is untouched.
- [X] T018 [US2] Prune and superseded entries (FR-013, research R-13, contract C-5′). Write the
  failing tests first in `tools/tests/test_accept.py` `PruneTest` and see them fail: `--prune int`
  with A, B, F (`int/a`→A, `int/b`→F) lists only B (Story 2 AS 8); `--prune int --yes` removes only B
  and logs one `pruned` line per removed entry with `new=-` and no `server=`; refuses with exit 3
  unless every server's pin is in the store; after deleting, a missing server pin
  (`FakeKeytool(fail_on=…)`) → restore and exit 4; a stage without `ownTls` or with a `problem` →
  refusal naming the reason; every 006 prune test unchanged. Then implement in `_prune_listing` /
  `_prune_locked` of `tools/inubit-cert-check.py` with `stage.expected`.
- [X] T019 [US2] **Regression** — serialization (FR-009, Story 2 AS 9, research R-12). The lock
  behaviour exists (R-12); the tests are expected to pass at once, there is no red step; if one
  fails, stop and report before changing `accept_locked` / `_prune_locked`. New class
  `PinPerServerSerialTest(AcceptTestCase)` in `tools/tests/test_accept.py`:
  - `--accept int/a` and `--accept int/b` started concurrently against the same copy (two processes
    through `tools/tests/run_with_fakes.py`) → the second waits for `cert-check.lock`, re-reads the
    profile bytes after taking it, and the final profile and store contain both new pins and both
    new certificates; the same for `--accept int/b` against a running `--prune int --yes`;
  - offline dialog case (plan review r1 finding 9): a dialog worker — the real worker
    (`cmd_dialog_worker`) with the fake dialog of `test_interactive.py` held open, or a fake worker
    process that takes `cert-check.lock` exactly as the worker does — holds the lock while
    `--accept int/a` runs →
    `--accept` waits; after the worker adopts for `int/b` and releases the lock, `--accept int/a`
    lands on the worker's result (both new pins present);
  - the lock held longer than the (patched, short) lock timeout → exit 3 "the profile lock is held by another process …" and
    nothing changed; repeating after the release succeeds.

**Checkpoint**: US1 + US2 — a marked stage can be rotated from the command line without editing a
file by hand.

---

## Phase 5: User Story 3 — Confirm per-server changes at start-up and in a Claude session (Priority: P2)

**Goal**: one dialog per changed server, rejection memory per server, and the C-8′ rule for Claude.

**Independent Test**: with a copy where `int/a` and `int/b` changed, `--interactive` shows two
dialogs one after another; "Reject" for `int/b` is remembered under `int/b` only; `--forget int/b`
brings the dialog back (Q5).

- [X] T020 [US3] Rejection memory per server (FR-015, research R-9, data-model "Remembered
  rejection", contract C-1′ last rule). Write the failing tests first and see them fail:
  - `tools/tests/test_state.py` `RejectionTest`: keys containing `/` are stored, read and cleared
    without touching stage keys; new `State.clear_rejections(keys)` removes several keys under the
    rejections lock in one write;
  - `tools/tests/test_check.py` `RejectionTest`: `--check` removes a stage-keyed entry `"int"` of a
    marked stage; it removes `"int/b"` when `int/b` presents its own pin again; it keeps `"int/b"`
    (F) when `int/b` presents G ≠ F; it never touches entries of uniform stages; these are the only
    writes of `--check`.
  Then implement `State.clear_rejections` and the housekeeping in `run_check` / `cmd_check` of
  `tools/inubit-cert-check.py`.
- [X] T021 [US3] `--forget TARGET` (FR-015, contract C-4′). Write the failing tests first in
  `tools/tests/test_interactive.py` `ForgetTest` and see them fail: `--forget int/b` removes only
  that entry; `--forget int` on a marked stage removes the entries of all its servers and a leftover
  stage-keyed entry; `--forget dev` behaves as in 006; exit 0 also when nothing was remembered; exit
  3 for an unknown stage or server and for a server of a uniform stage (`--forget dev/node1`). Then
  update `cmd_forget` in `tools/inubit-cert-check.py`.
- [X] T022 [US3] Dialog text, offers and the no-dialog rules (FR-010a, FR-014, FR-015, Story 3 AS 2,
  contract C-3′, research R-9, R-11, data-model "Dialog worker files"). Write the failing tests
  first in `tools/tests/test_interactive.py` and see them fail:
  - `DialogTest`: `dialog_title` = `INUBIT certificate changed - server int/b (stage int, profile
    acme)`; `dialog_text` shows only that server's line with its IP, old and new fingerprint,
    validity, and `WARNING: this is the certificate expected for server int/a.` only when
    `expectedBy` is not empty; plain ASCII; `offer_info` gains `target`, `server` and `expectedBy`;
    the offers file entries carry `target`, `server` (id or `null`) and `expectedBy`, and for a
    uniform stage they are byte-identical to 006 apart from the added keys;
  - `NoDialogTest`: remembered F for `int/b` and `int/b` presenting F → no dialog for `int/b`, a
    warning naming `int/b` on stderr and in the diagnostic log, while a change on `int/a` is still
    offered (AS 2); a stage-keyed leftover `"int"` never suppresses a dialog **and is removed from
    `cert-rejections.json` by `--interactive`** (data-model R-9, plan review r1 finding 10); servers
    with a `noOfferReason` are only logged.
  Then implement in `tools/inubit-cert-check.py`: `offer_info`, `dialog_title`, `dialog_text`,
  `_no_dialog_reason` and the offers file work on targets (server id for marked stages, stage name
  otherwise); `cmd_interactive` runs the same leftover housekeeping as `--check` (T020) before
  building the offers.
- [X] T023 [US3] Dialog worker, outcomes and status file (FR-011, FR-014, FR-015, FR-017, SC-006,
  Story 3 AS 1, contract C-3′, research R-11; after T022, same files). Write the failing tests first
  in `tools/tests/test_interactive.py` and see them fail:
  - `DialogOutcomeTest`: two changed servers → two dialogs, one after another in check order
    (AS 1); "Accept" runs C-2′ for that server with `--by dialog`; "Reject" / timeout remember under
    the server id with `rejected` / `timed-out` lines carrying `server=`; `dialog-failed` carries
    `server=` and is not remembered; the notification reads "New certificate for server int/b
    adopted - reconnect …"; no answer other than "Accept" adopts;
  - `WorkerTest`: status file `pending` / `adopting` / `done` keyed by target, and for uniform stages
    byte-identical to 006; before each dialog the worker re-reads profile and rejections for that
    target under the lock; an "Accept" for `int/a` after one for `int/b` starts from the first
    result (both new pins present); the parent waits about 20 s at most in total for all dialogs and
    each dialog gives up after 60 s (fake clock); late answers as in 006; `--interactive` writes
    nothing to stdout and never reads stdin; two concurrent `--interactive` runs show at most one
    dialog per server and fingerprint.
  Then implement in `tools/inubit-cert-check.py`: `notification_text`, `not_adopted_text`,
  `cmd_interactive`, `_report_dialog_outcomes`, `cmd_dialog_worker` and `_decide` work on targets;
  same lock, same 20 s and 60 s limits. Every 006 interactive test stays green.
- [X] T024 [P] [US3] In-session rule (FR-010a, FR-016, Story 3 AS 3, contract C-8′; any time after
  T015): replace the rule block "INUBIT certificate changes (inubit-acme)" in `docs/setup.md` §5
  "Certificate changes" with the C-8′ text — trigger `TLS_ERROR` caused by
  `CertPathValidatorException`, `SunCertPathBuilderException` **or** a pin mismatch
  (`CertificateException`), target `<stage>` or `<stage>/<server>`, `expectedBy` note,
  `--accept <target> <fingerprint> --by claude`, the prune hint kept from 006 ("point me to
  `inubit-cert-check --profile acme --prune <stage>`, first without `--yes`", the stage part of a
  `<stage>/<server>` target), never adopt without an explicit "yes". `acme` names only. The operator's own `~/.claude/CLAUDE.md` is
  changed only in T032.

**Checkpoint**: all three stories done; nothing has been adopted on a real stage.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T025 Documentation of the per-server workflow (FR-004, FR-016, FR-018, plan "Docs updated with
  behaviour"; after T024, same file) in `docs/setup.md`:
  - §3 configuration reference: key `tls.pinPerServer` (groups only, boolean, default `false`), the
    customer-agnostic example of contracts/configuration.md in block form, and the error messages
    V-1…V-6, V-10 and V-11; in a marked stage with several servers `cli.url` and
    `e2e.soap.baseUrl` are set per node, because each server's own pin is checked at that address
    and one shared address lets at most one server pass;
  - §5 new subsection "Stages whose servers present different certificates": when to mark a stage
    (each node creates its own certificate), converting a uniform stage by hand followed by
    `--check-config`, one trust store holding the certificates of all servers, `--check` output per
    server, `--accept int/b`, `--prune int` keeps what any server expects, `--forget int/b` /
    `--forget int`, one dialog per server, what the "expected for server …" warning means (possible
    misrouting or man-in-the-middle; check before accepting), removing the marking falls back to the
    006 behaviour;
  - §5 "Certificate changes": the sentence that the refusal is always a `CertPathValidatorException`
    names all three causes — `CertPathValidatorException` for a certificate missing from the trust
    store, `SunCertPathBuilderException` for a certificate whose subject the trust store does not
    know, `CertificateException` for a certificate in the store that does not match the pin
    (research R-4).
  Every quoted message and command checked against the code.
- [X] T026 Documentation of the StartCLI probe and its remaining window (FR-005a, FR-018, research
  R-15; after T012 and T025, same file) in `docs/setup.md` §5 next to the existing paragraph "StartCLI
  cannot pin a certificate …": in a marked stage every StartCLI call is preceded by a TLS handshake to
  the StartCLI address (`cli.url`, default derived from `baseUrl`) with the server's own pin, and a
  mismatch or failure refuses the call with `TLS_ERROR` / `TIMEOUT` / `UNREACHABLE` before StartCLI
  starts and before the credential guard is entered (quote the messages of contract
  configuration.md; the `UNREACHABLE` next step names `cli.url`); unmarked stages keep the existing residual risk
  unchanged; the remaining window — a certificate switch between the probe and StartCLI's own
  connection is not caught, because StartCLI checks only the stage trust store, which in a marked
  stage holds every server's certificate, and skips the host name with `disableHostnameVerification`;
  therefore certificates whose subjectAltNames match each host, with `disableHostnameVerification`
  removed, are still recommended — they close the window because StartCLI itself then refuses
  another server's certificate.
- [X] T027 Usage text (FR-016, FR-018; after T023, tool file). Write the failing test first in
  `tools/tests/test_cli.py` `UsageTest.test_help_names_server_targets` (`--help` shows `TARGET` as
  `<stage>` or `<stage>/<server>` for `--accept` and `--forget`) and see it fail; then update the
  module docstring (contract reference `specs/014-stage-distinct-certs/contracts/cli.md`) and the
  `--help` texts in `tools/inubit-cert-check.py`.
- [X] T028 [P] `CHANGELOG.md` `[Unreleased]` (after T012 and T023): feature 014 — Added
  `tls.pinPerServer`, the certificate check before every StartCLI call to a server of a marked stage,
  and the per-server workflow of `inubit-cert-check` (`--accept STAGE/SERVER`,
  `--forget STAGE[/SERVER]`, one dialog per server, `expectedBy` note); Changed: change-log lines of
  marked stages carry `server=`, rejections of marked stages are kept per server, the in-session rule
  also fires on a pin mismatch (`CertificateException`) and on `SunCertPathBuilderException`;
  unchanged for stages without the setting.
- [X] T029 Review `tools/inubit-cert-check.py`, `main/config/ConfigValidator.java`,
  `main/config/EffectiveNodeConfig.java`, `main/adapter/cli/StartCliPinProbe.java`,
  `main/adapter/cli/HandshakeStartCliPinProbe.java`, `main/adapter/cli/CliRunner.java` (T012; its
  callers stay unchanged), and `main/Launcher.java` (T009) against FR-001…FR-018 (with FR-005a and
  FR-010a), SC-001…SC-006 and the clarifications (2026-11-03 Q1, Q2; 2026-11-05) using the coverage
  table below; list any gap as a new task here before continuing.
- [X] T029a [P] [US1] Regression, SOAP (gap 1 of `reviews/T029-r1.md`; SC-004, FR-005): in
  `src/test/java/de/dadecker/inubit/mcp/adapter/soap/SoapE2eClientTest.java` (additions only) two
  HTTPS WireMock servers with `localhostKeyStore()` and `selfsignedKeyStore()`, the shared
  `TestCertificates.trustStore()` and two `EffectiveNodeConfig`s of a marked stage, each pinned to
  its own certificate: each own address → the POST succeeds; swapped addresses → `TLS_ERROR` naming
  the node with cause `(CertificateException)`. No production change; if a test fails, stop and
  report.
- [ ] T029b [US1] Pin for an http server with an https `cli.url` or `e2e.soap.baseUrl` (gap 2 of
  `reviews/T029-r1.md`; FR-004, FR-005): needs the operator's decision — extend V-6 (a node of a
  marked stage needs its own pin if its `baseUrl`, own `cli.url` or own `e2e.soap.baseUrl` is
  https; failing test first in `ConfigValidatorTest.PinPerServer`, `v7…` stays green; align
  contracts/configuration.md, spec FR-004 and its "http servers" edge case, `docs/setup.md`) or
  record the case as accepted and document it next to the window paragraph in `docs/setup.md` §5.
- [ ] T030 Validate offline (SC-005, FR-018, plan outline step 3):
  - `mvn -q -o clean verify`;
  - the tool suite as CI runs it: `python3 -I -m unittest discover -s tools/tests -v` (and once with
    `/usr/bin/python3 -I`);
  - `mvn -q -o -Dtest=NoCustomerIdentifiersTest test` with the local identifier lists present — it
    must run, not skip (the surefire report shows the test executed, 0 skipped);
  - `git diff main -- tools/tests src/test`: existing test files get additions only; no existing
    line is changed or removed, except in `tools/tests/support.py` (T002) and
    `src/test/java/de/dadecker/inubit/mcp/adapter/rest/TlsTestServer.java` (T011);
  - record the outcome (counts only) in `specs/014-stage-distinct-certs/quickstart.md`.
- [ ] T031 Local validation on a configuration copy (quickstart Q2–Q7 with Q3a, SC-001–SC-004,
  FR-005a): with the operator's OK, reinstall the tool to `~/.local/bin/inubit-cert-check`; run the
  Q2 configuration checks (valid copy exit 0 and `Result: OK` without errors and without the FR-005
  log line; each broken copy, V-10 and V-11 included, exit 1 naming stage and server), Q3 start-up
  log (server start), Q3a StartCLI probe without INUBIT (four endpoints: two HTTPS REST stubs with
  certificates A and B as the `baseUrl` of `int/a` and `int/b`, and two StartCLI addresses with
  swapped certificates; a CLI-backed tool for `int/a` and `int/b` passes the REST login and then
  fails with `TLS_ERROR … (CertificateException); StartCLI was not started`, no marker file from
  the stub `startcli.sh`), Q4 steps a–g with two local TLS servers
  (time step d from seeing the error to the restored check: under 2 minutes, SC-002), Q5 dialogs and
  rejection memory (the operator clicks), Q6 concurrency, Q7 uniform-stage regression. Uses copies
  only; record the outcome without real names in `quickstart.md`.
- [ ] T032 Manual live step — operator only, needs the operator's explicit approval, never automated
  (FR-016, FR-017, SC-006, Story 3 AS 3):
  - show the C-8′ diff for the operator's `~/.claude/CLAUDE.md` (back it up first; the trigger now
    names `CertPathValidatorException`, `SunCertPathBuilderException` or a pin mismatch
    `CertificateException`; the prune hint "`--prune <stage>`, first without `--yes`" stays, with
    the stage part of a `stage/server` target) and apply it only after the operator's "yes";
  - if, and only if, the operator decides to mark a real stage: the operator edits
    `~/.config/inubit-mcp/<profile>.yaml` by hand (per-node `cli.url` / `e2e.soap.baseUrl` where
    set), runs `--check-config` and `inubit-cert-check --profile <profile> --check`, and reconnects
    the MCP server;
  - on a real `TLS_ERROR` in a session (any of the three causes, REST or a refused StartCLI call):
    Claude runs `--check --json`, shows target, old and new fingerprint, notBefore, IP and any
    `expectedBy` note, asks, and runs `--accept <target> <fingerprint> --by claude` only after an
    explicit "Ja"; then asks for the reconnect, checks `get_health` and points to
    `--prune <stage>` (first without `--yes`);
  - record the outcome without real names; nothing is committed before the operator asks.

---

## Dependencies & Execution Order

- Phase 1: T001 and T002 in parallel.
- Phase 2: T003 → T004 (both in the tool file). Needs T002.
- US1, Java track: T005 after T001; T006, T007 and T009 after T005, in parallel (different files);
  T008 after T007 (same files); T010 and T011 any time (own test files; T011 adds two new main
  files and changes only the test helper `TlsTestServer`); T012 after T005, T006 (same test file
  `EffectiveNodeConfigTest`) and T011 (uses the probe and `TlsTestServer`). T009 and T012 are
  independent: T012 does not edit `Launcher.java` (plan review r2 finding 2).
- US1, tool track: T013 → T014 after T004 (same tool and test files).
- US2: needs T014 (offers come from the check). T015 → T016 → T017 → T018 → T019 (same tool file).
- US3: needs T017 (the dialog's "Accept" runs C-2′). T020 → T021 → T022 → T023 (same tool file);
  T024 any time after T015 (it documents the target form; `docs/setup.md` only).
- Polish: T025 after T024 and T008 (same file; documents V-10/V-11); T026 after T025 and T012 (same
  file); T027 after T023 (tool file); T028 after T012 and T023, in parallel with T025–T027; T029
  after T026, T027 and T028; T030 after T029; T031 after T030 and the operator's OK; T032 last, only
  with the operator.
- The Java track (T005–T012) and the tool track (T003–T004, T013–T023, T027) are independent until
  T029.

## Parallel Example

```text
Phase 1:  T001 Java fixture | T002 tool test helpers
US1:      T005 → (T006 | T007 → T008 | T009);  (T006, T011) → T012   alongside   T010 | T011   and   T003 → T004 → T013 → T014
US3/Pol.: T024 docs rule (during US2/US3) → T025 → T026 (all docs/setup.md) | T027 tool usage text | T028 CHANGELOG
```

## Implementation Strategy

- **MVP**: Phases 1–3 (US1). A marked stage is valid, connects, is logged, is enforced per server on
  REST, SOAP and StartCLI, and is checked without a conflict. On its own it is not releasable for a
  real stage, because a rotation would still need a hand edit; release together with US2.
- Then US2 (command-line rotation, prune, serialization), then US3 (dialog, rejection memory,
  in-session rule), then docs and validation.
- Run each task through the implementer → reviewer loop; every task contains its own failing tests
  and the implementation that makes them pass (regression tasks: tests only, green at once), so
  every task ends green and every commit stays green.

## Coverage

| Requirement | Tasks |
|---|---|
| FR-001 setting, off by default | T005, T013 (uniform unchanged), T025 |
| FR-002 one own pin per server | T006, T007, T010, T011 |
| FR-003 one stage trust store, "own" rule | T003, T006, T016 |
| FR-004 configuration rules | T005 (V-2), T007 (V-1, V-3…V-9), T008 (V-10, V-11), T003 (`problem` in the tool), T025 |
| FR-005 runtime per-server pin (REST, SOAP, StartCLI), start-up log | T009, T010, T011, T012, T029a, T029b |
| FR-005a check before every StartCLI call (before the credential guard; `TLS_ERROR`, `TIMEOUT`, `UNREACHABLE`) | T011, T012, T026, T031 (Q3a) |
| FR-006 no conflict, per-server status | T013 |
| FR-007 report and JSON fields | T013, T014 |
| FR-008 exit codes | T013 |
| FR-009 adoption steps, serialization | T017, T019 |
| FR-010 one server per decision | T016, T017 |
| FR-010a expected-by note (check, dialog, Claude) | T013, T014, T022, T024, T032 |
| FR-011 `server=` in log lines | T004, T017, T018, T023 |
| FR-012 refusals | T016 |
| FR-013 superseded entries and prune | T014, T018 |
| FR-014 dialog per server, limits, lock | T022, T023 |
| FR-015 rejection memory per server | T013, T020, T021, T022, T023 |
| FR-016 in-session rule (`TLS_ERROR` from `CertPathValidatorException`, `SunCertPathBuilderException` or a pin mismatch; prune hint kept) and check docs | T010, T024, T025, T027, T032 |
| FR-017 no adoption without decision, uniform unchanged | T012 (no probe), T013, T023, T030, T032 |
| FR-018 docs and customer-agnostic examples | T001, T024, T025, T026, T027, T028, T030 |
| SC-001 two certificates configured and healthy | T007, T010, T013, T031 |
| SC-002 restore in under 2 minutes | T031 |
| SC-003 adoption changes only the named server | T017, T031 |
| SC-004 wrong certificate refused (REST, SOAP, StartCLI) and reported | T010, T011, T012, T013, T029a, T031 (Q3a) |
| SC-005 006 tests unchanged | T013, T014, T023, T030 |
| SC-006 no adoption without operator action | T023, T032 |
