PLAN: specs/011-os-credential-stores/plan.md  ROUND: 2

All 15 round-1 findings are resolved:
- per-source `NodeSecrets.timeout()` used by `awaitCredentials` and the e2e function (R-7, quickstart 22);
- the resolved `NodeCredentials` kept and the deferred placeholder removed (R-7, data model);
- `Lookup.Found(Secret)`, with `password:` lines dropped and stdout never logged (R-2, R-3, R-5, quickstart 5 and 7);
- unnamed callers now named (`E2eCredentials` moved, guard constructor kept for unauthenticated clients, `credentialNames()`, `SslContexts`, `TestWiring`, `ConfigValidator` texts, `CredentialResolverTest` replaced);
- `EffectiveNodeConfig` factory, split `SecretReader` with unchanged Parameter Store texts, `Optional` e2e cell;
- R-14 rewritten around a `CredentialStore` seam on `Wiring`/`TestWiring`;
- re-read inside a fan-out stated and tested (quickstart 23);
- audit fallback (R-17);
- `secret-tool` environment allowlist and fixed paths;
- Linux dismissed-prompt risk;
- exit codes first for `security`;
- JNA pinned at 5.17.0, with `LinkageError` mapped;
- SC-006 reworded in spec.md, with rows for SC-006 and FR-013;
- each account read once in `--check-config`;
- the R-16 docs items.

I checked the revision against the code. The claims hold:
- `AdapterGatewayFactory.SECRETS_WAIT`/`awaitCredentials`/`Slot` guard choice via `rereadable()` (:113, :281-299);
- `NodeSecrets` factories, `held()`, `e2e()` returning `Optional`, catching only `RuntimeException`;
- `Wiring` `policies` built per lookup, `accounts` derived from it (:257, :291), e2e function (:305-324), `secrets(...)` (:389-400);
- `SecretTiming(Set, Duration)`;
- `CredentialGuard` constructors (:87, :92) and the three `InubitHttpClient` uses;
- `ProcessLauncher.LaunchSpec` takes a complete environment with nothing inherited;
- `FailureKind` already has `NO_CREDENTIALS`;
- `TestWiring.of(...)` takes `CredentialResolution`.

The macOS low-byte exit codes (44/128/51/36/50) agree with the OSStatus values. Plan, research, data model, contracts, quickstart and the changed SC-006 agree on the 60 s/10 s timeouts, the 30 s back-off, two failed logins per 60 s, `--check-config` exit codes 1/0 and the class names. Three minor points remain. None of them changes a decision, and each can be settled when writing tasks.md.

FINDINGS:
- [minor] plan "Scale/Scope" (`HealthService (pending text per store)`), R-8, contracts `get_health` – `HealthService` is in `application`, which may depend only on the JDK, the domain and slf4j (`PackageBoundaryTest.applicationDependsOnlyOnTheJdkTheDomainItselfAndSlf4j`). Its pending text "still fetching from AWS Parameter Store" is hard-coded (HealthService.java:247-254), and `CredentialState.PENDING` carries no source. So the service cannot reach `SecretLabels`/`StoreKind` to name "the <store>". – Name the mechanism. Options: Wiring passes a `Function<NodeId, String>` with the source's display name into `HealthService` (as it does for `timeouts::get`), or the `PENDING` result carries a domain-level label. Keep the Parameter Store text byte-for-byte.
- [minor] R-2 ("the rule is widened to allow `ProcessLauncher` use in `adapter/os`"), plan change list "`PackageBoundaryTest` (… `ProcessLauncher` in `adapter/os`)" – `onlyTheCliAdapterStartsOrInspectsProcesses` forbids only `ProcessBuilder`/`Process`/`ProcessHandle`/`Runtime.exec` outside `adapter.cli` (PackageBoundaryTest.java:74-75, 171-176). Using the `ProcessLauncher` interface from `adapter/os` is already allowed, so there is nothing to widen. A task that "widens" the rule risks admitting the process types into `adapter/os`. – State that the process rule stays as it is. The only new boundary rule is "`com.sun.jna` only in `adapter/os`". Optionally list `adapter/os` in `theScanSeesTheExistingDependencies`.
- [minor] plan Technical Context "Performance Goals" – it still says "store double ≤ 100 ms → first call ≤ 200 ms slower", while spec SC-006 and quickstart 24 now say 25 ms per entry read, at most four reads. Also, the "at most four" in SC-006 ignores the trust store password kind (node + group = up to six reads, 150 ms, which is still within the 200 ms). – Align the plan line with SC-006. Optionally say "at most six with a trust store" in SC-006 and quickstart 24.
- [minor] cosmetic – plan tree "research.md # R-1 … R-16" (research now goes to R-17). R-2 says `E2eCredentials` is used by `Wiring` and `SoapE2eClient`, but `SoapE2eClient` uses its own `BasicAuth` and `Wiring` does not name the type; only `NodeSecrets` (and `NodeSecretsTest`) import it. – Fix when convenient.

VERDICT: APPROVED
