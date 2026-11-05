# Research: Stages Whose Servers Present Different Certificates

Feature: [spec.md](spec.md) · Plan: [plan.md](plan.md)

Every decision was checked against `main` (18344e2) — server code under
`src/main/java/de/dadecker/inubit/mcp/config/`, `adapter/rest/`, `adapter/soap/` and
`adapter/cli/`, the tool `tools/inubit-cert-check.py`, and the 006 contract
`specs/006-tls-pin-rotation/contracts/cli.md`.

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
  | own StartCLI address (more than one node) | `group.cli().url()` empty or `node.cli().url()` present | `int/b: cli.url is inherited from {group} 'int' with tls.pinPerServer; set cli.url on each {node}` |
  | own SOAP address (more than one node) | `group.e2e().soap().baseUrl()` empty or `node.e2e().soap().baseUrl()` present | `int/b: e2e.soap.baseUrl is inherited from {group} 'int' with tls.pinPerServer; set e2e.soap.baseUrl on each {node}` |

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
- **Note on the two address rules** (plan review r1 finding 11): `EffectiveNodeConfig.resolve`
  inherits `cli.url` and `e2e.soap.baseUrl` node → group. A stage-level value makes every server of
  the stage use the same address, but each server checks its own pin there (R-4, R-15), so at most
  one server can pass. The existing warning "cli.url is set for N {nodes}" (`checkStructure`)
  stays as it is for every stage; in a marked stage with more than one node the two rules above
  are errors, named per inheriting node. A stage-level value that every node overrides is dead and
  not reported. A marked stage with one node may set either address on the stage.

## R-4 The TLS check of REST and SOAP connections is unchanged; StartCLI is not covered by it

- **Finding (REST, SOAP)**: `EffectiveNodeConfig.resolve` takes the node pin before the group pin.
  `SslContexts` builds one `PinningTrustManager` per server from the effective `Tls`; both
  `InubitHttpClient` and `SoapE2eClient` use it. A marked stage has no group pin, so each server's
  context holds exactly its own pin and the shared stage trust store. A server presenting another
  server's certificate passes PKIX (same store) but fails the pin. That is FR-002, FR-005 and
  SC-004 for REST and SOAP.
- **Finding (which exception each case produces)** — `InubitHttpClient.transportError` reports
  `TLS_ERROR` with the root cause's simple class name in parentheses:

  | Case | Fails in | Reported cause |
  |---|---|---|
  | new certificate not in the trust store (uniform stage after a redeploy) | PKIX chain validation of the delegate trust manager | `CertPathValidatorException` (the case the 006 rule was written for) |
  | certificate in the store but not the server's pin: another server's certificate in a marked stage, or a replaced but not yet pruned certificate | `PinningTrustManager.checkPin` | `CertificateException` ("does not match the pinned SHA-256 fingerprint") |
  | certificate with a subject the trust store does not know (no path to any trusted entry, e.g. a new self-signed certificate; 002 quickstart A5) | PKIX path building of the delegate trust manager | `SunCertPathBuilderException` |
  | host name does not match (hostname verification on) | session-aware delegate check | `CertificateException` |

  So a swapped certificate in a marked stage never produces a `CertPathValidatorException`, and a
  new certificate whose subject the store does not know produces a `SunCertPathBuilderException`.
  The C-8′ rule (contracts/cli.md) and `docs/setup.md` therefore name all three causes:
  `CertPathValidatorException`, `SunCertPathBuilderException` and the pin mismatch
  (`CertificateException`) (plan review r1 finding 1, plan review r2 finding 7).
- **Finding (StartCLI)**: `CliRunner.runUnguarded` passes only `--trustStoreFilePath` and
  `--disableHostNameVerification` to StartCLI; StartCLI has no pin option. In a uniform stage this
  is the documented residual risk, reduced by pruning. In a marked stage the shared store always
  holds every server's certificate, so StartCLI alone would accept `int/b`'s certificate from
  `int/a`, and pruning can never fix that. This is closed, up to a short window, by the check
  before StartCLI (R-15, FR-005a).
- **Finding (SOAP address)**: `SoapE2eClient` pins the node's own certificate against
  `e2e.soap.baseUrl`, inherited node → group. One stage-level URL for several servers of a marked
  stage lets at most one node pass; `e2e.soap.baseUrl` must be set per node (R-3, contract V-11).
- **Decision**: no change in `adapter/rest` or `adapter/soap`. A regression test in
  `InubitHttpClientTest` proves SC-004 for REST. It uses two WireMock HTTPS servers with the
  existing `TestCertificates` key stores (`localhost` and `selfsigned`, both trusted by
  `trustStore()`), pins each server to its own certificate, shows that a swap is refused, and
  asserts that the reported cause is `CertificateException`. SOAP uses the same `SslContexts` and
  needs no test of its own.

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
    without a pin, a `pinPerServer` on a node, or a marked stage without any effective trust store.
    The two address rules of R-3 (`cli.url`, `e2e.soap.baseUrl`) are not repeated in the tool:
    they do not affect pins or the trust store, and `--accept` runs `--check-config`, which
    reports them.
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
    - `configuration problem` (exactly this string, no suffix; the problem itself is reported in
      the stage's `problem`; plan review r2 finding 9);
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
  - Java tests are added to the existing classes named in plan.md, plus the new
    `StartCliPinProbeTest` (R-15) and one YAML fixture
    `src/test/resources/config/pin-per-server.yaml`, customer-agnostic.
  - Tool tests extend the existing modules. New cases build profiles with two local TLS servers
    that use distinct throw-away certificates (`tls_fixtures.py` already creates them per test).
  - CI is unchanged: `mvn verify` and `python3 -I -m unittest discover -s tools/tests` already run.
  - Every 006 test stays untouched as the regression net (SC-005).

## R-15 Certificate check before every StartCLI call in a marked stage (FR-005a)

- **Finding — where StartCLI connects**: `CliRunner.runUnguarded` passes
  `server.cli().url()` as the last argument. `EffectiveNodeConfig.resolve` sets it to node
  `cli.url`, else group `cli.url`, else `baseUrl` + `/ibis/servlet/IBISSoapServlet`
  (`DEFAULT_CLI_PATH`). By default StartCLI therefore uses the host and port of `baseUrl`, but a
  configured `cli.url` may name another host or port. `ConfigValidator.checkCliUrl` allows `http`
  only with `allowInsecureHttp`. `CliVersionProbe` runs `startcli -v` through
  `CliRunner.execute`, which does not log in and does not contact the server.
- **Decision**:
  - New interface `adapter/cli/StartCliPinProbe` (one method `check(EffectiveNodeConfig server,
    Optional<Secret> trustStorePassword)`) with the real implementation in its own class
    `adapter/cli/HandshakeStartCliPinProbe`, so `CliRunnerTest` can use a counting fake.
    `CliRunner` keeps its four-argument constructor, which delegates to a new five-argument
    constructor with a `HandshakeStartCliPinProbe`; tests pass their own probe to the
    five-argument constructor (plan review r2 finding 1). `CliRunner.run` calls the probe
    **before** `guard.acquire()`, only for a server of a marked stage: the probe contacts the
    network, and doing that inside the permit (the exclusive `CredentialGuard` probe semaphore
    while credentials are not yet confirmed) would let a slow StartCLI address block the first
    logins of other calls to that node (plan review r2 finding 3). A refused probe therefore
    never touches the guard.
  - `EffectiveNodeConfig.Tls` gains `boolean pinPerServer`, resolved from the group's
    `tls.pinPerServer` (a node value is invalid, V-1); a constructor with the old three arguments
    keeps every existing call site unchanged. `CliRunner.execute` (the `-v` probe) never probes,
    and `Launcher`'s `-v` runner keeps the four-argument constructor.
  - Address: host and port of `server.cli().url()` — exactly the address StartCLI connects to;
    port `-1` means 443. An `http` address has no certificate and is not probed.
  - Handshake: `SslContexts.forServer(server.secretLabels(), server.tls(), trustStorePassword)`,
    the same context the REST client builds (shared stage trust store and this server's own pin in
    `PinningTrustManager`). An `SSLSocket` connects with `server.timeout()` as connect and read
    timeout, sets SNI to the host and, unless `disableHostnameVerification` is set, the endpoint
    identification algorithm `HTTPS`, so the host-name rule matches the REST client; then
    `startHandshake()` and close. No HTTP request and no credential are sent.
  - Trust-store password: the four production callers of `CliRunner.run` (`CliTagRunner`,
    `CliExportRunner`, `CliImportRunner`, `V81ProcessControlAdapter`) already hold the node's
    `NodeCredentials`, and each of them may run against a marked stage, so all four switch to a
    new `run` overload with an additional `Optional<Secret> trustStorePassword` (from
    `credentials.trustStorePassword()`). The two existing `run` signatures stay and delegate with
    `Optional.empty()`, so the existing `run(` calls in `CliRunnerTest` stay unchanged.
  - Errors, mirroring `InubitHttpClient.transportError` (same codes; likely-cause and next-step
    texts as there, except the `UNREACHABLE` next step):
    - `SSLException` or `CertificateException` in the cause chain → `TLS_ERROR`, message
      `The TLS connection to int/a failed for the StartCLI address int-a.example.test:8443
      (<root cause simple name>); StartCLI was not started` — `(CertificateException)` for a pin
      mismatch;
    - connect timeout or handshake timeout (`SocketTimeoutException`, e.g. a listener that
      accepts but never answers the handshake) → `TIMEOUT`, message `The StartCLI address
      int-a.example.test:8443 of int/a did not complete the TLS handshake within <timeout>;
      StartCLI was not started`, likely cause and next step as the REST `TIMEOUT`;
    - `ConnectException`, unknown host, no route → `UNREACHABLE`, message `Cannot connect to int/a
      for the StartCLI address …; StartCLI was not started`; the next step names the effective
      StartCLI URL, not `baseUrl`: `Check cli.url (<effective cli.url>), DNS and the VPN, then
      retry` (plan review r2 finding 4).
    The probe runs before the permit is taken, so the guard is neither accepted nor rejected and a
    refused probe never triggers the credential pause.
  - Unmarked stages: never probed; arguments, environment and timing of StartCLI unchanged
    (FR-017).
  - Cost: one TCP connect and TLS handshake per StartCLI call to a marked stage, milliseconds next
    to the seconds StartCLI's JVM needs to start.
- **Residual race (TOCTOU), stated honestly**: the probe checks one handshake; StartCLI then opens
  its own connection(s) one to a few seconds later (JVM start). If in that window the address
  starts presenting another certificate — a rotation, a DNS or load-balancer switch, or an
  attacker who can redirect traffic selectively — StartCLI validates it only against the stage's
  trust store, which in a marked stage holds every server's certificate, and with
  `disableHostnameVerification` it skips the host-name check. So within that window StartCLI could
  still accept another server's certificate. The probe narrows the exposure from "every StartCLI
  call" to "a switch exactly between probe and StartCLI connect"; it cannot close it. The way out
  is certificates whose subjectAltNames match each host and host-name verification left on (no
  `disableHostnameVerification`): then StartCLI itself refuses `int/b`'s certificate at `int/a`'s
  host. `docs/setup.md` states both.
- **Alternatives considered**:
  - Narrow FR-005 / SC-004 to REST and SOAP and only document or warn (plan review r1 finding 2,
    first option). Rejected by the operator's decision of 2026-11-05.
  - A temporary trust store per call holding only this server's certificate, passed to StartCLI.
    It would close the window without SANs, but per-server trust stores are out of scope (spec
    Assumptions), and it writes key material to a temporary file per call. May be revisited.
  - A local TLS-terminating proxy that pins and forwards. Far more moving parts than the gain.
- **Tests**: `StartCliPinProbeTest` with two local TLS endpoints (`TlsTestServer`, made usable
  from `adapter/cli`) and one shared trust store: matching pin passes, swapped certificate →
  `TLS_ERROR` with `(CertificateException)`, closed port → `UNREACHABLE` naming `cli.url`,
  silent listener → `TIMEOUT`, http address → no handshake (the `TlsTestServer` handshake counter
  stays 0), likely-cause and next-step texts of `TLS_ERROR` equal the REST ones. `CliRunnerTest` with `FakeProcessLauncher`:
  refused probe → StartCLI never launched; matching certificate → launched with unchanged
  arguments; unmarked server → probe never called.
