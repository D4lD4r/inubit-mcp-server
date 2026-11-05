# Plan review – round 3 (2026-11-05)

FEATURE: specs/014-stage-distinct-certs  ROUND: 3  VERDICT: APPROVED

All 10 round-2 findings are resolved; the code the plan relies on holds (`CliRunner.run` is the
only path to `runUnguarded`; `cli.url` resolves node → group → `baseUrl` + default path;
`PinningTrustManager` checks chain then pin; `SslContexts.forServer` and
`InubitHttpClient.transportError` match R-15). No requirement is uncovered, no task is scope creep.

## Findings (none blocks implementation)

1. [minor] T030: "only these existing test files change" contradicts the tasks that add tests to
   existing files. Use: existing test files get additions only; no existing line changed or removed,
   except `tools/tests/support.py` (T002) and `TlsTestServer.java` (T011).
2. [minor] R-15/T012: the trust-store password overload is unneeded — a node with a CLI can never
   have a trust-store password (`ConfigValidator` 639–645, `NodeSecrets` 195–205, `SslContexts` 76);
   drop the overload and the four caller edits, or state the invariant.
3. [minor] R-15/T011: a handshake read timeout may arrive wrapped in an `SSLException`; check for
   `SocketTimeoutException` in the cause chain before `SSLException`.
4. [minor] quickstart Q3a/T031: `int/b` fails at the REST login (`confirmCredentials`) before the
   probe; add a second HTTPS stub with certificate B as `int/b`'s `baseUrl`.
5. [minor] data-model/R-8/C-1′/T013: `unreachable` as `noOfferReason` for a `changed` server is
   contradictory; state the JSON value for an unreachable server and align.
6. [minor] plan.md: the `reviews/` comment lists only r1.
