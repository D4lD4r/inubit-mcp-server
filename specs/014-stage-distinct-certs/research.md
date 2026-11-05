# Research: Stages Whose Servers Present Different Certificates

Feature: [spec.md](spec.md) · Plan: [plan.md](plan.md)

Every decision was checked against `main` (18344e2) — server code under
`src/main/java/de/dadecker/inubit/mcp/config/` and `adapter/rest/`, the tool
`tools/inubit-cert-check.py`, and the 006 contract `specs/006-tls-pin-rotation/contracts/cli.md`.

## R-1 Where the setting lives: `tls.pinPerServer`

- **Decision**: a boolean `pinPerServer` inside the `tls` block of a group (stage):

  ```yaml
  groups:
    - name: int
      tls:
        pinPerServer: true
        trustStore: ~/.config/inubit-mcp/acme-int-truststore.p12
      nodes:
        - name: a
          baseUrl: https://int-a.example.test:8443
          tls:
            pinnedCertificateSha256: "AA:…"   # block mapping: the tool edits this one line
        - name: b
          baseUrl: https://int-b.example.test:8443
          tls:
            pinnedCertificateSha256: "BB:…"
  ```

- **Rationale**:
  - It sits next to the trust store and the pins it governs, so it is easy to find in
    `docs/setup.md` §5.
  - `TlsConfig` is bound on groups and nodes, so the validator can name a misplaced setting
    precisely: "… is only valid on a {group}".
  - The tool already reads exactly the `tls` blocks (`_tls_block`, closed key list `TLS_KEYS`), so
    one key is added there.
- **Alternatives considered**:
  - A group-level key outside `tls`, such as `distinctCertificates: true`. Jackson would reject it
    on nodes automatically, but it separates a TLS rule from the TLS block and needs a new
    parser path in the tool.
  - A list of allowed fingerprints per stage. Rejected by clarification Q1 (weaker binding).
  - Inferring the mode from server-level pins. Rejected by the spec assumption "opt-in per stage".

## R-2 `defaults.tls` does not exist in the server

- **Finding**: `Defaults` has no `tls` component, and `ConfigLoader` binds with
  `FAIL_ON_UNKNOWN_PROPERTIES`. A `defaults.tls` block is therefore already refused as
  "Unknown key 'tls'". `EffectiveNodeConfig.resolve` inherits TLS values node → group only.
  The tool's reader tolerates `defaults.tls` (006 data model "server → stage → defaults"), but a
  profile with it never passes `--check-config`.
- **Decision**: the spec's `defaults` clauses are met as follows, with no extra code in the server:
  - "the setting on `defaults`" → refused as an unknown key;
  - "a fingerprint in `defaults` is allowed" → it cannot occur in a valid profile;
  - "trust store inherited from `defaults`" → cannot occur for the server; the tool keeps
    treating such a trust store as not the stage's own, as in 006.

  The tool keeps its 006 tolerance, so its reader needs no new rules.
- **Alternatives considered**: introducing `defaults.tls` in the server. That is out of scope and
  would change features 001/002.

## R-3 Server validation works on the written values, not the effective ones

- **Decision**: a new `ConfigValidator.checkPinPerServer` walks `config.groups()` and their
  `nodes()` (the `GroupConfig` / `NodeConfig` records as bound, before `EffectiveNodeConfig`
  inheritance). For a group whose `tls.pinPerServer` is `true`:

  | Rule (FR-004) | Check | Message (terms rendered) |
  |---|---|---|
  | no pin in the stage's `tls` | `group.tls().pinnedCertificateSha256()` empty | `{group} 'int': tls.pinnedCertificateSha256 is not allowed with tls.pinPerServer; set it on each {node}` |
  | stage needs a trust store | `group.tls().trustStore()` present | `{group} 'int': tls.pinPerServer needs tls.trustStore on the {group}` |
  | no trust store on a server | `node.tls().trustStore()` empty | `int/b: tls.trustStore is not allowed on a {node} of a {group} with tls.pinPerServer; set it on the {group}` |
  | own pin on each https server | `node.tls().pinnedCertificateSha256()` present, unless `baseUrl` is `http` | `int/b: tls.pinPerServer on {group} 'int' needs tls.pinnedCertificateSha256 on this {node}` |

  For every node (marked stage or not): `node.tls().pinPerServer()` present →
  `int/b: tls.pinPerServer is only valid on a {group}`. The existing per-server checks
  (`checkTls`: malformed pin, missing trust-store file, hostname verification without a pin) still
  run on the effective values.
- **Rationale**: FR-004 is about *where* a value is written. The effective view hides that
  (`node pin or group pin`). Jackson resolves YAML aliases before binding, so the server cannot
  tell an alias from an inline block. The resulting semantics match the spec:
  - a pin that reaches the stage's `tls` through an alias is rejected (FR-004 bullet 4);
  - a pin that reaches a server's own `tls` through an alias is valid (FR-004 last sentence).
- **Note**: the 006 rule "hostname verification disabled without a pin" can no longer fire in a
  marked stage, because every https server has its own pin. It stays in `checkTls` unchanged.

## R-4 The TLS check at connection time is unchanged

- **Finding**: `EffectiveNodeConfig.resolve` takes the node pin before the group pin.
  `SslContexts` builds one `PinningTrustManager` per server from the effective `Tls`. A marked
  stage has no group pin, so each server's context holds exactly its own pin and the shared
  stage trust store. A server presenting another server's certificate passes PKIX (same store)
  but fails the pin. That is FR-002, FR-005 and SC-004.
- **Decision**: no change in `adapter/rest`. A regression test in `InubitHttpClientTest` proves
  SC-004. It uses two WireMock HTTPS servers with the existing `TestCertificates` key stores
  (`localhost` and `selfsigned`, both trusted by `trustStore()`), pins each server to its own
  certificate, and shows that a swap is refused.

## R-5 Start-up log line (FR-005)

- **Decision**: in `Launcher.serve`, right after the existing "started for profile" line, write
  `LOG.info("Stages with one pinned certificate per server (tls.pinPerServer): {}", names)`. It is
  written only when at least one stage is marked. It goes to stderr and the log, never to stdout.
- **Alternatives considered**: a validator warning. Rejected, because a deliberate setting is not
  a warning, and warnings are also printed by `--check-config`.

## R-6 Naming a server on the command line: `STAGE/SERVER`

- **Decision**: `--accept` and `--forget` take a *target*. A target is either a stage name or a
  server id `<stage>/<node>`, exactly the id the check prints. Stage and node names match
  `[a-z0-9][a-z0-9-]{0,31}`, so `/` is unambiguous.
  - `--accept int/b FP`: per-server adoption (marked stage only).
  - `--accept dev FP`: stage adoption (unmarked stage only).
  - `--forget int/b`: clears one server's rejection.
  - `--forget int`: for a marked stage, clears the rejections of all its servers.
  - `--prune` keeps taking a stage (the trust store belongs to the stage).
- **Rationale**: no new option, so the C-8 rule changes only in its placeholder (`<target>`); the
  check's offer line can be copied as it is.
- **Alternatives considered**: a separate `--server NAME` option. More typing, and it would allow
  a server without a stage.

## R-7 Profile reader: per-server pin data and configuration problems

- **Decision**:
  - `Stage` gains `pin_per_server` (bool, from the stage's effective `tls` block).
  - `Server` gains `pin_line` (the line of `pinnedCertificateSha256` in the server's own *inline*
    `tls` block, else `None`) and `pin_source` (`server` inline · `alias` · `stage` inherited).
  - For a marked stage, `own_tls` is computed without the stage-pin condition: the trust store is
    written in the stage's own inline block and no other stage uses the same file (006 rule).
  - The reader does not duplicate the server's validation. A marked stage that breaks a rule of R-3
    gets a `problem` text. The check still compares and reports the servers, but no offer is made,
    and `--accept`/`--prune` refuse with "configuration not valid for tls.pinPerServer: <problem>
    — run --check-config". The rules are: a stage pin, a server trust store, an https server
    without a pin, or a `pinPerServer` on a node.
- **Rationale**: the reader stays fail-closed for *shapes* (006 R-7). Semantic errors stay
  reportable, so `--check` remains usable for diagnosis.

## R-8 Check evaluation and JSON for marked stages

- **Decision**: `evaluate_stage` branches on `stage.pin_per_server`.
  - Server status is unchanged: `ok` when the presented fingerprint equals the server's pin.
  - Stage `status` is the worst server status, as in 006.
  - `conflict` is `false`; the stage-level `offer` and `rejected` are `null`.
  - **Per-server offer**: a server gets an offer when it is `changed` and reachable,
    `pin_source == server` with a pin line, the stage has `own_tls`, and the stage has no `problem`.
    Otherwise `noOfferReason` names why:
    - `pin through alias`;
    - `trust store not the stage's own`;
    - `configuration problem: …`;
    - `unreachable`.
  - **`expectedBy`**: the ids of other servers of the stage whose pin equals this server's
    presented fingerprint (FR-010a).
  - **`rejected`**: the offer equals the remembered rejection under the server id.

  JSON additions for a marked stage only, so unmarked stages stay byte-compatible (FR-007):
  - the stage gets `"pinPerServer": true`; `pin` is `null`; `pinSource` is `"server"`;
  - each server gets `offer`, `rejected`, `expectedBy` (list), `noOfferReason` (string or null);
  - the stage gets `problem` (string or null).
- **Exit codes**: unchanged (FR-008).

## R-9 Rejection memory keyed by server id

- **Decision**: `cert-rejections.json` keeps its shape, a map from key to entry. For marked stages
  the key is the server id (`"int/b"`); stage keys stay for unmarked stages. Ids contain `/` and
  stage names cannot, so the two never collide.
  - `--check` and `--interactive` remove a stage-keyed entry of a marked stage (FR-015, edge case
    "old state files"). They also clear a server entry when that server presents its pin again.
  - A successful `--accept int/b` clears `int/b`; `--forget` behaves as in R-6.
- **Alternatives considered**: a nested `{stage: {server: entry}}` map. That breaks the 006 file
  shape for no gain.

## R-10 Change-log line with `server=`

- **Decision**: decisions about a server of a marked stage insert `server=<id>` right after
  `stage=<stage>`:

  ```text
  2026-11-03T10:00:00Z profile=acme stage=int server=int/b outcome=adopted old=AA:… new=BB:… notBefore=… by=cli
  ```

  `pruned` lines and all lines of unmarked stages keep the 006 format exactly (FR-011).

## R-11 Dialog per server

- **Decision**:
  - `offer_info` gains `server` (the id) and `expectedBy`. Its key in the worker status is the
    target (`int/b`, or the stage name for unmarked stages).
  - Title: `INUBIT certificate changed - server int/b (stage int, profile acme)`.
  - The text shows only that server's line. If `expectedBy` is not empty, it adds:
    `WARNING: this is the certificate expected for server int/a.`
  - Offers are asked one after another in check order (stage order, then server order). The parent
    waits about 20 s at most in total and each dialog gives up after 60 s, as in 006 C-3.
  - Before each dialog the worker re-reads, under the lock, the profile and the rejections for that
    target (006 behaviour, per target).
- **Rationale**: the 006 worker already loops over a list of offers under one lock. Making the key a
  target keeps the loop, the status file and the late-accept notification as they are.

## R-12 Serialization of adoptions (FR-009, Story 2 AS 9)

- **Finding**: `--accept` and `--prune --yes` take the profile lock (`cert-check.lock`, `flock`,
  blocking up to 30 s). The dialog worker holds it from hand-over to its end.
- **Decision**: no new mechanism. Every write runs under that lock, and each run reads the profile
  bytes afresh after taking it (006 C-2 step 2), so it works on the previous run's result.
  - A CLI or Claude adoption that meets an open dialog waits up to 30 s. If the dialog is still
    open after that, the adoption is refused (exit 3, "profile lock busy"), as in 006, and can be
    repeated.
  - The quickstart covers both orders.

## R-13 Prune and superseded entries for marked stages (FR-013)

- **Decision**: the expected set is the pins of all https servers of the stage. An entry is
  superseded when its fingerprint is not in that set.
  - `--prune STAGE --yes` refuses (exit 3) unless every fingerprint of the expected set is in the
    store. After deleting, it verifies that all of them are still present (restore and exit 4
    otherwise).
  - `pruned` lines keep `new=` as in 006; for a marked stage it is `-`, because there is no single
    stage pin.

## R-14 Tests and CI

- **Decision**:
  - Java tests are added to the existing classes named in plan.md, plus one YAML fixture
    `src/test/resources/config/pin-per-server.yaml`, customer-agnostic.
  - Tool tests extend the existing modules. New cases build profiles with two local TLS servers
    that use distinct throw-away certificates (`tls_fixtures.py` already creates them per test).
  - CI is unchanged: `mvn verify` and `python3 -I -m unittest discover -s tools/tests` already run.
  - Every 006 test stays untouched as the regression net (SC-005).
