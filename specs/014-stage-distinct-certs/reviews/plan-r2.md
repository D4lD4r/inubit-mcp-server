# Plan review – round 2 (2026-11-05)

FEATURE: specs/014-stage-distinct-certs  ROUND: 2  VERDICT: CHANGES_REQUESTED (one major)

All 11 round-1 findings are resolved; the FR-005a material is consistent across spec, research
R-4/R-15, data model, contracts, plan and tasks T008, T011, T012, T026.

## Findings

1. [major] T012 changes the `CliRunner` constructor and `run` signature: 34 `new CliRunner(` calls
   in 24 test files and 8 `run(` calls in `CliRunnerTest`, none named in T012; T030 pre-authorises
   test diffs. Keep the 4-argument constructor (delegating to a real `StartCliPinProbe`, which acts
   only on marked servers), add a constructor taking a probe, make the probe an interface or
   non-final; add the trust-store password as a `run` overload; narrow T030. `Launcher`'s `-v`
   runner only calls `execute` and needs no probe.
2. [minor] T009 and T012 both edit `Launcher.java`; add T009 to T012's predecessors (or drop that
   edit).
3. [minor] R-15: the probe runs inside the credential guard's permit (the exclusive
   `CredentialGuard.probe` semaphore before credentials are confirmed); a slow StartCLI address
   blocks other first logins of that node. Run the probe before `guard.acquire()` or justify.
4. [minor] T011/R-15: the `UNREACHABLE` next step should name `cli.url`, not `baseUrl`; relax
   T011's equality for `UNREACHABLE`.
5. [minor] T011: no `TIMEOUT` case (listener that never answers the handshake).
6. [minor] T011: `TlsTestServer` needs a handshake counter for "no connection attempt".
7. [minor] R-4/C-8′: a certificate with an unknown subject fails with
   `SunCertPathBuilderException` (002 quickstart A5), which the widened trigger misses.
8. [minor] C-8′/T032: the rule text drops the prune hint ("`--prune <stage>`, first without
   `--yes`"); keep it and say prune takes the stage part of the target.
9. [minor] `noOfferReason` is `configuration problem` in data-model/cli.md/T013 but
   `configuration problem: …` in R-8; pick one exact string.
10. [minor] quickstart §2 says the FR-005 log line appears under `--check-config` (it is written in
    `Launcher.serve` only); no live scenario exercises FR-005a; add one that T031 runs (two local
    TLS servers with swapped certificates → `TLS_ERROR … (CertificateException); StartCLI was not
    started`).
