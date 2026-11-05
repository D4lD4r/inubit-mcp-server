---

description: "Task list for stages whose servers present different certificates (feature 014)"
---

# Tasks: Stages Whose Servers Present Different Certificates

**Input**: Design documents from `/specs/014-stage-distinct-certs/`

**Prerequisites**:
- [plan.md](plan.md) and [spec.md](spec.md) (clarifications 2026-11-03, spec reviews r1–r2)
- [research.md](research.md) (R-1…R-14) and [data-model.md](data-model.md)
- [contracts/configuration.md](contracts/configuration.md) (V-1…V-9) and
  [contracts/cli.md](contracts/cli.md) (C-1′…C-5′, C-8′, delta to
  [006 contracts/cli.md](../006-tls-pin-rotation/contracts/cli.md))
- [quickstart.md](quickstart.md) (scenario numbers `Q<n>` below)
- the code of features 001–011 (`main` after 0.7.0 work), in particular the tool of feature 006
  (`tools/inubit-cert-check.py` and its suite `tools/tests/`)

**Tests**: REQUIRED (Constitution III, TDD). Every behaviour gets a test that is observed failing on
an assertion before the production code exists. Test tasks name the class (and where useful the
method) they add; the implementation task that follows makes them pass. A task is done when
`mvn -q -o verify` (Java tasks) or `/usr/bin/python3 -I -m unittest discover -s tools/tests -v`
(tool tasks) is green. Every existing 006 test stays unchanged (SC-005).

**Organization**: the tool's profile view and change-log field are the foundation (Phase 2). US1
(configuration, validation, start-up log, conflict-free check) and US2 (per-server adoption, prune,
serialization) are both P1; US3 (dialog per server, rejection memory, in-session rule) is P2.

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
  `FakeCheckConfig` and temp directories; Java tests use WireMock and `TestCertificates`.
- **Never adopt**: no task adopts a certificate on a real stage. Only the operator does, in T030.
- **Public repository**: profile `acme`, stages `dev` / `int`, servers `int/a` / `int/b`, hosts under
  `example.test`, fictitious fingerprints only; no customer names in files, commit messages or PR
  descriptions; the identifier hooks must pass; never use `--no-verify`.
- **No new dependency** (plan: Technical Context): JDK and Jackson on the server side; Python
  standard library, `/usr/bin/openssl`, `/usr/bin/osascript`, `keytool` for the tool.

## Decisions on the points found while generating this list (2026-11-05)

The reviewed spec governs; the other documents were aligned in the same change.

- **Stage-level `rejected` of a marked stage** is `null` (spec FR-007, research R-8);
  data-model.md and contracts/cli.md now say `null` too. T011/T012 test `null`.
- **Concurrent adoption (Story 2 AS 9)**: AS 9 now states the R-12 behaviour explicitly — the two
  adoptions run one after another and both land; a command-line adoption that waits longer than
  30 s for an open dialog is refused with "profile lock busy", changes nothing and succeeds when
  repeated. T018 tests both orders.
- **Flow-mapping server `tls`**: the tool keeps failing closed on a server `tls` that is not a
  block mapping or an alias (`tools/inubit-cert-check.py`); research.md's example now uses the block
  form, and the documentation (T024) shows only the block form.
- **`--check-config` summary**: no TLS values in the summary (no plan change); quickstart.md now
  expects exit 0 and `Result: OK`, and T029 checks that.
- **`noOfferReason` of an unchanged server**: stays absent (`None`) — an `ok` server offers nothing
  because nothing changed, so there is nothing to refuse (FR-012 refusal reasons apply to `changed`
  servers only, data-model.md).

---

## Phase 1: Setup

- [ ] T001 [P] Customer-agnostic Java fixture `testres/config/pin-per-server.yaml`
  (`src/test/resources/config/pin-per-server.yaml`): the example of contracts/configuration.md —
  profile `acme`; uniform stage `dev` (stage pin, own trust store); marked stage `int` with
  `tls.pinPerServer: true`, `tls.trustStore` and `disableHostnameVerification: true` on the stage, and
  nodes `a` and `b` with their own block-form `tls.pinnedCertificateSha256` (distinct fictitious
  fingerprints, hosts `int-a.example.test` / `int-b.example.test`). Header comment "every name is
  fictitious". It is loaded by the tests of T006–T009; `NoCustomerIdentifiersTest` covers it.
- [ ] T002 [P] Test helpers in `tools/tests/support.py` (research R-14): `write_profile` gains a
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

**⚠️ CRITICAL**: no tool task of US1–US3 starts before T005. The Java tasks of US1 (T006–T010) do not
depend on this phase and may start after T001.

- [ ] T003 Tests first: new class `PinPerServerTest(ReaderTestCase)` in
  `tools/tests/test_profile_reader.py` against data-model "Profile view" and research R-7:
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
- [ ] T004 Implement in `tools/inubit-cert-check.py` (makes T003 pass): `TLS_KEYS` gains
  `pinPerServer` (parsed as a boolean in `_tls_block`); dataclass `Server` gains `pin_source` and
  `pin_line`; dataclass `Stage` gains `pin_per_server`, `expected` and `problem`, all with defaults so
  every 006 construction and comparison is unchanged; `_read_stage` records each server's pin source
  and pin line; `_finish_stages` computes `own_tls` for marked stages without the stage-pin condition
  and sets `problem`. The reader does not duplicate the server's validation beyond the R-7 rules.
- [ ] T005 Change-log field `server=` (FR-011, research R-10, data-model "Change log entry"). Tests
  first in `tools/tests/test_state.py` `ChangeLogTest`: `log_change(..., server="int/b")` writes
  `… profile=acme stage=int server=int/b outcome=adopted old=… new=… notBefore=… by=cli` with
  `server=` right after `stage=`; without `server` the line is byte-identical to the 006 format (exact
  string assertion); a `pruned` line of a marked stage has `new=-` and no `server=`. Then extend
  `State.log_change(self, stage, outcome, old, new, not_before, by, now=None, server=None)` in
  `tools/inubit-cert-check.py`.

**Checkpoint**: the tool reads marked stages; nothing else behaves differently yet.

---

## Phase 3: User Story 1 — Declare and verify a stage with per-server certificates (Priority: P1) 🎯 MVP

**Goal**: `tls.pinPerServer` is accepted and validated by the MCP server, logged at start, and the
check reports a marked stage without a conflict.

**Independent Test**: with `pin-per-server.yaml` and two local TLS servers presenting different
certificates, `--check-config` passes, both servers connect, and `inubit-cert-check --check`
reports `int` as `ok`, no conflict, exit 0 (spec US1 Independent Test, Q2, Q3, Q4 step a).

- [ ] T006 [P] [US1] Bind the key (FR-001, research R-1, R-2, contract V-2/V-9). Tests first in
  `test/config/ConfigLoaderTest.java` (new `@Nested class PinPerServer`): `pin-per-server.yaml` loads
  and `group.tls().pinPerServer()` is `Optional.of(true)` for `int` and empty for `dev`;
  `pinPerServer` on a node's `tls` binds (it is refused by the validator in T008); `defaults.tls`
  with `pinPerServer` is refused with `Unknown key 'tls'` (existing behaviour, asserted); `full.yaml`
  and `anchors.yaml` load exactly as before. Then add `Optional<Boolean> pinPerServer` to
  `main/config/TlsConfig.java` (`EMPTY`, compact constructor with `Optionals.orEmpty`) and a helper
  `boolean marksStage()` (`pinPerServer.orElse(false)`); adapt every `new TlsConfig(` call site.
- [ ] T007 [US1] Effective TLS of a marked stage (FR-002, FR-003, research R-4; after T006). Test
  only, in `test/config/EffectiveNodeConfigTest.java`: for each node of `int` in
  `pin-per-server.yaml` the effective `Tls` has the group's trust store and that node's own pin, and
  no node gets another node's pin. `main/config/EffectiveNodeConfig.java` is expected to stay
  unchanged; if the test fails, stop and report, because R-4 assumes it.
- [ ] T008 [US1] Validation rules (FR-004, research R-3, contract V-1…V-9; after T006). Tests first in
  `test/config/ConfigValidatorTest.java`, new `@Nested class PinPerServer`, one test per row, each
  asserting the exact message of contracts/configuration.md with `{group}` / `{node}` rendered by the
  profile's terminology (also once with custom terms):
  - V-1 `pinPerServer` on a node (inline and through an alias) → `int/b: tls.pinPerServer is only
    valid on a {group}`;
  - V-3 stage pin, inline and through an alias → `{group} 'int': tls.pinnedCertificateSha256 is not
    allowed with tls.pinPerServer; set it on each {node}`;
  - V-4 no stage trust store → `{group} 'int': tls.pinPerServer needs tls.trustStore on the {group}`;
  - V-5 trust store on a node → `int/b: tls.trustStore is not allowed on a {node} of a {group} with
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
- [ ] T009 [US1] Start-up log line (FR-005, research R-5; after T006). Tests first in
  `test/MainTest.java`: starting with `pin-per-server.yaml` logs exactly
  `Stages with one pinned certificate per server (tls.pinPerServer): int` once (logback
  `ListAppender` on the `Launcher` logger, as in `test/security/SecretLeakTest.java`); a profile
  without a marked stage logs no such line; stdout carries only MCP traffic (existing assertion
  pattern). Then in `main/Launcher.java` `serve(...)`, right after the "started for profile" line,
  `LOG.info("Stages with one pinned certificate per server (tls.pinPerServer): {}", names)` with the
  marked stage names in file order, comma-separated, only when at least one stage is marked.
- [ ] T010 [P] [US1] Runtime regression (FR-002, FR-005, SC-001, SC-004, research R-4). Test only, in
  `test/adapter/rest/InubitHttpClientTest.java`: two WireMock HTTPS servers with
  `TestCertificates.localhostKeyStore()` and `selfsignedKeyStore()`, one shared
  `TestCertificates.trustStore()`, each server pinned to its own certificate (group trust store, node
  pin) → both calls succeed; each server pinned to the *other* server's certificate → `TLS_ERROR` in
  both directions. `PinningTrustManager` and `SslContexts` stay unchanged; if a test fails, stop and
  report.
- [ ] T011 [US1] Check tests first (FR-006, FR-007, FR-008, FR-010a, FR-013, FR-017, SC-001, SC-004,
  SC-005, contract C-1′, research R-8), with `support.marked_stage` (T002):
  - `tools/tests/test_check.py` new class `PinPerServerCheckTest(CheckTestCase)`:
    `test_distinct_certificates_are_ok_without_conflict` (stage `ok`, `conflict` false, exit 0,
    Story 1 AS 2); `test_one_server_marked_stage` and `test_all_servers_same_certificate` (no
    conflict, no warning); `test_server_presenting_other_servers_certificate_is_changed` (`int/a`
    `changed`, `expectedBy == ["int/b"]`, exit 10, Story 1 AS 3); `test_changed_server_gets_offer`
    (`int/b.offer` = F, `int/a` `ok`, stage `changed`, Story 2 AS 1); `test_changed_and_unreachable`
    (stage `changed`, `int/a` `unreachable`, `int/b` still offered); `test_no_offer_reasons` —
    `noOfferReason` is one of "`unreachable` · `pin through alias` · `trust store not the stage's own`
    · `configuration problem`" for the matching profile, with `offer` null; `test_problem_reported`
    (stage `problem` set, no offer, servers still compared); `test_rejected_per_server`
    (`state.rejection("int/b")` equals the offer → server `rejected` true, status `changed`, exit
    10); `test_check_never_changes_profile_or_store` (sha256 unchanged, FR-017);
    `test_unmarked_stage_with_two_certificates_still_conflicts` (conflict, no offer, Story 1 AS 4);
  - JSON in the same class: a marked stage has exactly the C-1′ keys — `pinPerServer: true`,
    `pin: null`, `pinSource: "server"`, `conflict: false`, stage `offer: null`, stage `rejected` (see
    open points), `ownTls`, `problem`; each server has `offer`, `rejected`, `expectedBy` (list),
    `noOfferReason` (string or null) and `pinned` = its own pin; none of these new keys appears on a
    uniform stage, whose JSON is byte-identical to the 006 output (golden comparison);
  - `TrustStoreTest.test_marked_stage_superseded_entries`: store A, B, F with `int/a`→A and
    `int/b`→F → `supersededTrustStoreEntries` lists only B (Story 2 AS 8);
  - `OutputTest.test_marked_stage_text_report`: the C-1′ lines `Stage int  CHANGED  (one pin per
    server)`, per server `old`/`new`/`subject`/`valid`, `offer: inubit-cert-check --profile acme
    --accept int/b <fp>`, `note: this is the certificate expected for int/a`, `no offer: …`,
    `rejected earlier: … (offered again only after --forget int/b)`, `configuration problem: <text> -
    run --check-config`.
- [ ] T012 [US1] Implement in `tools/inubit-cert-check.py` (makes T011 pass): `evaluate_stage` branches
  on `stage.pin_per_server`; `ServerResult` gains `offer`, `rejected`, `expected_by`,
  `no_offer_reason` (offer only when `changed`, reachable, `pin_source == "server"` with a pin line,
  stage `own_tls`, no `problem`); stage `status` = worst server status (changed > unreachable > ok),
  `conflict` false, stage `offer` null; `superseded_entries` uses `stage.expected` for marked stages;
  `check_json` and `check_text` add the marked-only fields and lines; exit codes of `run_check`
  unchanged (10 if any server `changed`, else 20 if any `unreachable`, else 0).

**Checkpoint**: US1 complete — a marked stage is configured, validated, logged and checked without a
conflict; uniform stages unchanged.

---

## Phase 4: User Story 2 — Adopt a new certificate in a stage with per-server certificates (Priority: P1)

**Goal**: `--accept STAGE/SERVER FINGERPRINT` adopts for exactly one server; `--prune` keeps every
certificate a server still expects; adoptions run one after another.

**Independent Test**: on a configuration copy with `int/b` outdated: `--check` offers `int/b`,
`--accept int/b F` changes only `int/b`'s pin line, the store gains F, a backup exists, one `adopted`
line names `server=int/b` (spec US2 Independent Test, Q4 steps b–g).

- [ ] T013 [US2] Targets on the command line (research R-6, contract "Targets"). Tests first in
  `tools/tests/test_cli.py` `UsageTest`: `--accept int/b FP`, `--accept dev FP`, `--forget int/b`
  and `--forget int` parse into a target; each part must match `[a-z0-9][a-z0-9-]{0,31}`; `int/b/c`,
  `/b`, `int/`, `INT/b` → usage error, exit 2; `--prune int/b` → usage error, exit 2 (`--prune` keeps
  taking a stage). Then update `build_parser` / `parse_args` in `tools/inubit-cert-check.py`
  (`--accept TARGET FINGERPRINT [--by cli|claude]`, `--forget TARGET`).
- [ ] T014 [US2] Refusal tests first (FR-010, FR-012, Story 2 AS 3 and AS 7, contract C-2′ step 1):
  new class `PinPerServerRefusalTest(AcceptTestCase)` in `tools/tests/test_accept.py`; each case exits
  3, leaves profile and trust store byte-identical (sha256), and names the reason on stderr and in
  the diagnostic log:
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
- [ ] T015 [US2] Success and rollback tests first (FR-009, FR-010, FR-011, SC-002, SC-003, Story 2 AS
  2, 4, 5, 6, contract C-2′ steps 2–7) in `tools/tests/test_accept.py`:
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
- [ ] T016 [US2] Implement per-server adoption in `tools/inubit-cert-check.py` (makes T014, T015
  pass): `cmd_accept`, `accept_locked`, `_usable_stage`, `_change_and_report` and `verify_adoption`
  accept a server target of a marked stage per C-2′ — fetch only that server; `trust_alias` builds
  `<stage>-<node>-<8 hex>-<yyyymmdd>`; `replace_pin_line` edits `Server.pin_line` (refusal message
  names the server's pin line); verification checks that this server's pin is new, every other pin
  (servers and stages) and every server id is unchanged, and the store lists F; log lines carry
  `server=`; the summary and reconnect hint stay as in 006. Uniform-stage adoption is untouched.
- [ ] T017 [US2] Prune and superseded entries (FR-013, research R-13, contract C-5′). Tests first in
  `tools/tests/test_accept.py` `PruneTest`: `--prune int` with A, B, F (`int/a`→A, `int/b`→F) lists
  only B (Story 2 AS 8); `--prune int --yes` removes only B and logs one `pruned` line per removed
  entry with `new=-` and no `server=`; refuses with exit 3 unless every server's pin is in the store;
  after deleting, a missing server pin (`FakeKeytool(fail_on=…)`) → restore and exit 4; a stage
  without `ownTls` or with a `problem` → refusal naming the reason; every 006 prune test unchanged.
  Then implement in `_prune_listing` / `_prune_locked` of `tools/inubit-cert-check.py` with
  `stage.expected`.
- [ ] T018 [US2] Serialization (FR-009, Story 2 AS 9, research R-12). Tests first: new class
  `PinPerServerSerialTest(AcceptTestCase)` in `tools/tests/test_accept.py`: `--accept int/a` and
  `--accept int/b` started concurrently against the same copy (two processes through
  `tools/tests/run_with_fakes.py`) → the second waits for `cert-check.lock`, re-reads the profile
  bytes after taking it, and the final profile and store contain both new pins and both new
  certificates; the same for `--accept int/b` against a running `--prune int --yes`; with the lock
  held longer than the (patched, short) lock timeout → exit 3 "profile lock busy" and nothing
  changed. No production change is expected (R-12); fix `accept_locked` / `_prune_locked` only if a
  test fails.

**Checkpoint**: US1 + US2 — a marked stage can be rotated from the command line without editing a
file by hand.

---

## Phase 5: User Story 3 — Confirm per-server changes at start-up and in a Claude session (Priority: P2)

**Goal**: one dialog per changed server, rejection memory per server, and the C-8′ rule for Claude.

**Independent Test**: with a copy where `int/a` and `int/b` changed, `--interactive` shows two
dialogs one after another; "Reject" for `int/b` is remembered under `int/b` only; `--forget int/b`
brings the dialog back (Q5).

- [ ] T019 [US3] Rejection memory per server (FR-015, research R-9, data-model "Remembered
  rejection", contract C-1′ last rule). Tests first:
  - `tools/tests/test_state.py` `RejectionTest`: keys containing `/` are stored, read and cleared
    without touching stage keys; new `State.clear_rejections(keys)` removes several keys under the
    rejections lock in one write;
  - `tools/tests/test_check.py` `RejectionTest`: `--check` removes a stage-keyed entry `"int"` of a
    marked stage; it removes `"int/b"` when `int/b` presents its own pin again; it keeps `"int/b"`
    (F) when `int/b` presents G ≠ F; it never touches entries of uniform stages; these are the only
    writes of `--check`.
  Then implement `State.clear_rejections` and the housekeeping in `run_check` / `cmd_check` of
  `tools/inubit-cert-check.py`.
- [ ] T020 [US3] `--forget TARGET` (FR-015, contract C-4′). Tests first in
  `tools/tests/test_interactive.py` `ForgetTest`: `--forget int/b` removes only that entry;
  `--forget int` on a marked stage removes the entries of all its servers and a leftover stage-keyed
  entry; `--forget dev` behaves as in 006; exit 0 also when nothing was remembered; exit 3 for an
  unknown stage or server and for a server of a uniform stage (`--forget dev/node1`). Then update
  `cmd_forget` in `tools/inubit-cert-check.py`.
- [ ] T021 [US3] Dialog tests first (FR-010a, FR-014, FR-015, FR-017, Story 3 AS 1–2, contract C-3′,
  research R-11, data-model "Dialog worker files") in `tools/tests/test_interactive.py`:
  - `DialogTest`: `dialog_title` = `INUBIT certificate changed - server int/b (stage int, profile
    acme)`; `dialog_text` shows only that server's line with its IP, old and new fingerprint,
    validity, and `WARNING: this is the certificate expected for server int/a.` only when
    `expectedBy` is not empty; plain ASCII; `offer_info` gains `target`, `server` and `expectedBy`;
  - `NoDialogTest`: remembered F for `int/b` and `int/b` presenting F → no dialog for `int/b`, a
    warning naming `int/b` on stderr and in the diagnostic log, while a change on `int/a` is still
    offered (AS 2); a stage-keyed leftover `"int"` never suppresses a dialog; servers with a
    `noOfferReason` are only logged;
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
- [ ] T022 [US3] Implement in `tools/inubit-cert-check.py` (makes T021 pass): `offer_info`,
  `dialog_title`, `dialog_text`, `notification_text`, `not_adopted_text`, `_no_dialog_reason`,
  `cmd_interactive`, `_report_dialog_outcomes`, `cmd_dialog_worker` and `_decide` work on targets
  (server id for marked stages, stage name otherwise); offers file entries gain `target`, `server`
  (id or `null`) and `expectedBy`; same lock, same 20 s and 60 s limits. Every 006 interactive test
  stays green.
- [ ] T023 [P] [US3] In-session rule (FR-016, Story 3 AS 3, contract C-8′): replace the rule block
  "INUBIT certificate changes (inubit-acme)" in `docs/setup.md` §5 "Certificate changes" with the
  C-8′ text (target `<stage>` or `<stage>/<server>`, `expectedBy` note, `--accept <target>
  <fingerprint> --by claude`, never adopt without an explicit "yes"). `acme` names only. The
  operator's own `~/.claude/CLAUDE.md` is changed only in T030.

**Checkpoint**: all three stories done; nothing has been adopted on a real stage.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T024 [P] Documentation (FR-018, plan "Docs updated with behaviour") in `docs/setup.md`:
  - §3 configuration reference: key `tls.pinPerServer` (groups only, boolean, default `false`), the
    customer-agnostic example of contracts/configuration.md in block form, and the error messages
    V-1…V-6;
  - §5 new subsection "Stages whose servers present different certificates": when to mark a stage
    (each node creates its own certificate), converting a uniform stage by hand followed by
    `--check-config`, one trust store holding the certificates of all servers, `--check` output per
    server, `--accept int/b`, `--prune int` keeps what any server expects, `--forget int/b` /
    `--forget int`, one dialog per server, what the "expected for server …" warning means (possible
    misrouting or man-in-the-middle; check before accepting), removing the marking falls back to the
    006 behaviour. Every quoted message and command checked against the code.
- [ ] T025 Usage text (FR-016, FR-018): tests first in `tools/tests/test_cli.py`
  `UsageTest.test_help_names_server_targets` (`--help` shows `TARGET` as `<stage>` or
  `<stage>/<server>` for `--accept` and `--forget`); then update the module docstring (contract
  reference `specs/014-stage-distinct-certs/contracts/cli.md`) and the `--help` texts in
  `tools/inubit-cert-check.py`.
- [ ] T026 [P] `CHANGELOG.md` `[Unreleased]`: feature 014 — Added `tls.pinPerServer` and the
  per-server workflow of `inubit-cert-check` (`--accept STAGE/SERVER`, `--forget STAGE[/SERVER]`, one
  dialog per server, `expectedBy` note); Changed: change-log lines of marked stages carry `server=`,
  rejections of marked stages are kept per server; unchanged for stages without the setting.
- [ ] T027 Review `tools/inubit-cert-check.py`, `main/config/ConfigValidator.java` and
  `main/Launcher.java` against FR-001…FR-018, SC-001…SC-006 and the clarifications (Q1, Q2) using the
  coverage table below; list any gap as a new task here before continuing.
- [ ] T028 Validate offline (SC-005, FR-018, plan outline step 3):
  - `mvn -q -o clean verify`;
  - the tool suite as CI runs it: `python3 -I -m unittest discover -s tools/tests -v` (and once with
    `/usr/bin/python3 -I`);
  - `mvn -q -o -Dtest=NoCustomerIdentifiersTest test` with the local identifier lists present — it
    must run, not skip (the surefire report shows the test executed, 0 skipped);
  - `git diff main -- tools/tests src/test` shows no changed assertion in an existing 006 test
    (only additions and the helper extension of T002);
  - record the outcome (counts only) in `specs/014-stage-distinct-certs/quickstart.md`.
- [ ] T029 Local validation on a configuration copy (quickstart Q2–Q7, SC-001–SC-004): with the
  operator's OK, reinstall the tool to `~/.local/bin/inubit-cert-check`; run the Q2 configuration
  checks (valid copy exit 0 without errors; each broken copy exit 1 naming stage and server), Q3
  start-up log, Q4 steps a–g with two local TLS servers (time step d from seeing the error to the
  restored check: under 2 minutes, SC-002), Q5 dialogs and rejection memory (the operator clicks),
  Q6 concurrency, Q7 uniform-stage regression. Uses copies only; record the outcome without real
  names in `quickstart.md`.
- [ ] T030 Manual live step — operator only, needs the operator's explicit approval, never automated
  (FR-016, FR-017, SC-006, Story 3 AS 3):
  - show the C-8′ diff for the operator's `~/.claude/CLAUDE.md` (back it up first) and apply it only
    after the operator's "yes";
  - if, and only if, the operator decides to mark a real stage: the operator edits
    `~/.config/inubit-mcp/<profile>.yaml` by hand, runs `--check-config` and
    `inubit-cert-check --profile <profile> --check`, and reconnects the MCP server;
  - on a real `TLS_ERROR` in a session: Claude runs `--check --json`, shows target, old and new
    fingerprint, notBefore, IP and any `expectedBy` note, asks, and runs `--accept <target>
    <fingerprint> --by claude` only after an explicit "Ja"; then asks for the reconnect and checks
    `get_health`;
  - record the outcome without real names; nothing is committed before the operator asks.

---

## Dependencies & Execution Order

- Phase 1: T001 and T002 in parallel.
- Phase 2: T003 → T004 → T005 (all in the tool file). Needs T002.
- US1: T006 after T001; T007, T008, T009 after T006 (T008 and T009 touch different files and may run
  in parallel); T010 any time (test only). T011 → T012 after T005.
- US2: needs T012 (offers come from the check). T013 → T014 → T015 → T016 → T017 → T018 (same tool
  file).
- US3: needs T016 (the dialog's "Accept" runs C-2′). T019 → T020 → T021 → T022; T023 any time after
  T013 (it documents the target form).
- Polish: T024 (after T023, same file) and T026 in parallel after T022; T025 after T022 (tool file); T027 after T025; T028
  after T027; T029 after T028 and the operator's OK; T030 last, only with the operator.
- The Java track (T006–T010) and the tool track (T003–T005, T011–T022) are independent until T028.

## Parallel Example

```text
Phase 1:  T001 Java fixture | T002 tool test helpers
US1:      T006 → (T007 | T008 | T009)   alongside   T003 → T004 → T005 → T011 → T012   and   T010
US3/Pol.: (T023 → T024, both docs/setup.md) | T025 tool usage text | T026 CHANGELOG
```

## Implementation Strategy

- **MVP**: Phases 1–3 (US1). A marked stage is valid, connects, is logged and is checked without a
  conflict. On its own it is not releasable for a real stage, because a rotation would still need a
  hand edit; release together with US2.
- Then US2 (command-line rotation, prune, serialization), then US3 (dialog, rejection memory,
  in-session rule), then docs and validation.
- Run each task through the implementer → reviewer loop; every commit stays green because each
  implementation task follows its own failing tests.

## Coverage

| Requirement | Tasks |
|---|---|
| FR-001 setting, off by default | T006, T011 (uniform unchanged), T024 |
| FR-002 one own pin per server | T007, T008, T010 |
| FR-003 one stage trust store, "own" rule | T003, T004, T007, T014 |
| FR-004 configuration rules | T006 (V-2), T008, T003 (`problem` in the tool) |
| FR-005 runtime per-server pin, start-up log | T009, T010 |
| FR-006 no conflict, per-server status | T011, T012 |
| FR-007 report and JSON fields | T011, T012 |
| FR-008 exit codes | T011, T012 |
| FR-009 adoption steps, serialization | T015, T016, T018 |
| FR-010 one server per decision | T014, T015, T016 |
| FR-010a expected-by note (check, dialog, Claude) | T011, T021, T023, T030 |
| FR-011 `server=` in log lines | T005, T015, T017, T021 |
| FR-012 refusals | T014, T016 |
| FR-013 superseded entries and prune | T011, T017 |
| FR-014 dialog per server, limits, lock | T021, T022 |
| FR-015 rejection memory per server | T011, T019, T020, T021 |
| FR-016 in-session rule and check docs | T023, T025, T030 |
| FR-017 no adoption without decision, uniform unchanged | T011, T021, T028, T030 |
| FR-018 docs and customer-agnostic examples | T001, T023, T024, T025, T026, T028 |
| SC-001 two certificates configured and healthy | T008, T010, T011, T029 |
| SC-002 restore in under 2 minutes | T029 |
| SC-003 adoption changes only the named server | T015, T029 |
| SC-004 wrong certificate refused and reported | T010, T011, T029 |
| SC-005 006 tests unchanged | T011, T021, T028 |
| SC-006 no adoption without operator action | T021, T030 |
