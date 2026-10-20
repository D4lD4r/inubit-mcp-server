# Implementation Plan: Configurable Secret Sources

**Branch**: `010-secret-sources` | **Date**: 2026-10-20 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/010-secret-sources/spec.md`

**Base**: the branch is rebased onto `009-inubit-version-detection` (merge order 008 → 009 → 010),
because 009 and 010 change the same classes (`AdapterGatewayFactory`, `HealthService`,
`InventoryService`, `Wiring`, `ConfigSummary`).

## Summary

The profile declares where INUBIT secrets come from: environment variables (today's behaviour,
the default) or AWS Systems Manager Parameter Store, once for the profile and overridable per
group and node. AWS secrets are fetched on first use per node through the standard AWS credential
chain, cached for the process lifetime, re-read once after INUBIT rejects them, and never leave
the adapter layer.

- **Configuration** (research R-3 to R-6): `credentials.source` at top level, `credentials.source`
  and `credentials.parameters` on groups and nodes; per node a `SecretPlan` with the effective
  source and the variable or parameter names. The loader keeps refusing secrets in the YAML but
  accepts parameter names in `credentials.parameters` (R-4).
- **AWS adapter** (R-1, R-2): `adapter/aws/SsmParameterStore` implements the `config` port
  `ParameterStore` with the AWS SDK for Java v2 (`ssm`, `sso`, `ssooidc`, `sts`,
  `url-connection-client`).
- **Lazy credentials** (R-7): `adapter/NodeSecrets` per node — single-flight fetch, 10 s timeout,
  30 s back-off, validation and scrubber registration; the gateway slot builds the HTTP client only
  after the secrets are there.
- **Rotation** (R-10): `CredentialGuard` re-reads once after a rejection; a changed value gets one
  grace attempt; at most two failed logins per node per 60 s.
- **Time budgets** (R-8, R-9): fan-out deadlines add the fetch allowance for AWS nodes;
  `get_health` asks `GatewayFactory.prepareCredentials` first and waits only within its budget.
- **Messages and audit** (R-15, R-16): source-aware names in every secret-related error (incl.
  TLS); the audited account of AWS nodes is the fetched username.
- **`--check-config`** (R-12) reads and validates parameters, never prints values; exit 1 on
  missing/denied/invalid/unknown profile, warning when AWS is unavailable.
- **Errors** (R-11): `AUTH_FAILED` / `UNREACHABLE` with classified texts, raw AWS messages only in
  the scrubbed log.

## Technical Context

**Language/Version**: Java 21 (unchanged).

**Primary Dependencies**: existing (MCP Java SDK, Jackson YAML, logback, Saxon-HE) plus **AWS SDK
for Java v2 2.54.3** (`ssm`, `sso`, `ssooidc`, `sts`, `url-connection-client`; Netty and Apache
HTTP clients excluded). Justification in research R-1. `THIRD-PARTY-NOTICES.md` is updated.

**Storage**: none; secrets in memory only (`infra.Secret`).

**Testing**:
- JUnit 5, AssertJ; fake `ParameterStore` (in-memory, scriptable delays and failures) for
  everything above the adapter;
- `SsmParameterStoreTest` against a local stub HTTP server (WireMock) speaking the SSM JSON
  protocol (`AmazonSSM.GetParameters`), with static test AWS credentials and an endpoint override —
  no real AWS call;
- fake clock for back-off and the 60 s pause; existing timing tests for `get_health` and fan-outs.

**Target Platform**: macOS and Linux (unchanged); AWS access through the machine's AWS
configuration.

**Project Type**: single Maven module, shaded JAR.

**Performance Goals**: SC-005 (stub ≤ 1 s → first call ≤ 2 s slower, later calls ≤ 50 ms);
`get_health` keeps feature 001 SC-002; startup unchanged (no AWS contact).

**Constraints**:
- no secret in tool parameters, results, logs, errors, command lines (Constitution II);
- the profile contains no AWS keys; parameter names only (R-4);
- AWS SDK only in `adapter/aws` (R-2);
- at most two failed INUBIT logins per node per 60 s (FR-010);
- offline tests only; the first build after adding the SDK needs one online dependency
  resolution from Maven Central.

**Scale/Scope**: no new tool; changed: profile schema (`credentials`), `--check-config`, error
texts, `get_health` failure reason; about 11 new classes (`SecretSourceConfig`, `ParameterNames`,
`SecretPlan`, `StoreLocation`, `ParameterStore` + `Fetched`, `SecretLabels`, `CredentialRules`,
`CredentialState`, `SsmParameterStore`, `NodeSecrets`, `SecretTiming`) and extensions of `CredentialsConfig`, `GroupConfig`, `NodeConfig`,
`ConfigLoader`, `ConfigValidator`, `CredentialResolver`, `ConfigSummary`, `Launcher`,
`CredentialGuard`, `AdapterGatewayFactory`, `InubitHttpClient`, `HealthService`,
`InventoryService`, `DiagnosisService`, `Wiring`, `PackageBoundaryTest`, docs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ✅ no new write path | ✅ Lockout bound stays explicit (two failed logins per 60 s, FR-010, R-10); no secret fetched for nodes no call addresses (SC-006). |
| II. Secrets Stay Out (NON-NEGOTIABLE) | ⚠️ a new channel for secrets | ✅ <ul><li>Values only in `infra.Secret`, registered with the scrubber before use (R-7).</li><li>Profile holds names only; the loader refuses pasted values without echoing them (R-4).</li><li>Raw AWS messages only in the scrubbed log (R-11); SDK logging at WARN (R-14).</li><li>`--check-config` never prints values (R-12).</li><li>`NoSecretLeakTest` extended to the AWS source.</li></ul> |
| III. Test-First (NON-NEGOTIABLE) | ✅ | ✅ Every rule (precedence, back-off, single flight, re-read bound, deadlines, loader refusal) has a test before code (quickstart table); the AWS adapter is tested against a local stub, never AWS. |
| IV. Use-Case-Oriented Tools | ✅ | ✅ No tool exposes secrets or source details; `--check-config` is the inspection path. |
| V. Adapter Isolation | ✅ | ✅ AWS SDK confined to `adapter/aws` behind the `config` port `ParameterStore`; boundary rule added (R-2). |
| VI. Bounded Output | ✅ | ✅ Error texts are single lines with fixed fields. |
| VII. Observability & Auditability | ✅ | ✅ One structured record per fetch attempt, INFO/WARN, re-reads with "changed" (R-14). |
| Tech constraints | ⚠️ new runtime dependency | ✅ Justified in R-1 (credential chain and SigV4 are security code); pinned via BOM; HTTP client limited to `url-connection-client`; single shaded JAR kept; notices updated. |

**Result**: PASS. The two ⚠️ items are resolved by the design (R-1, R-4, R-7, R-11, R-12, R-14).
Complexity Tracking: none.

## Project Structure

### Documentation (this feature)

```text
specs/010-secret-sources/
├── plan.md
├── research.md                 # R-1 … R-17
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── configuration-delta.md
│   └── mcp-tools-delta.md
├── checklists/requirements.md
├── reviews/                    # spec-r1…r3 (+ plan reviews)
└── tasks.md                    # /speckit-tasks
```

### Source Code

```text
src/main/java/de/dadecker/inubit/mcp/
├── domain/        # model/CredentialState (READY|PENDING); port/GatewayFactory.prepareCredentials (default READY) — R-9
├── config/        # SecretSourceConfig, ParameterNames, SecretPlan, StoreLocation, ParameterStore (+ Fetched),
│                  # SecretLabels (R-15), CredentialRules (R-17);
│                  # CredentialsConfig/GroupConfig/NodeConfig (credentials section), ConfigLoader (R-4),
│                  # ConfigValidator (R-6, R-13), CredentialResolver (environment nodes only; deferred AWS nodes),
│                  # ProfileConfig.credentialVariableNames (env nodes), ConfigSummary (secrets part)
├── adapter/aws/   # SsmParameterStore (+ package-info) — the only AWS SDK user
├── adapter/       # NodeSecrets (new), CredentialGuard (re-read hook, grace), AdapterGatewayFactory (Slot uses NodeSecrets)
├── adapter/rest/  # InubitHttpClient, SslContexts: texts per source (SecretLabels)
├── adapter/soap/  # SoapE2eClient: trust store password from NodeSecrets, texts per source
├── application/   # SecretTiming (new); InventoryService, DiagnosisService (deadlines), HealthService (budget),
│                  # (WritePolicy in domain/model unchanged; Wiring fills the account per call, R-16)
├── Launcher.java  # --check-config with a ParameterStore when AWS nodes exist
└── Wiring.java    # ParameterStore, NodeSecrets per node, SecretTiming, e2e credentials per source
src/main/resources/logback.xml   # software.amazon.awssdk at WARN
src/test/…                       # fake ParameterStore, stub SSM server, tests per quickstart table
pom.xml, THIRD-PARTY-NOTICES.md   # AWS SDK BOM + modules, exclusions
docs/setup.md (section 4: both sources, AWS permissions, migration from a start script), docs/tools.md (errors, get_health), README "Safety model", CHANGELOG
```

**Structure Decision**: the same single module. Stages, each reviewed and ending with a green
`mvn verify`:

1. **Configuration model** (US1): `credentials` section at three levels, `SecretSourceConfig`,
   `ParameterNames`, loader exemption (R-4), effective source and names (R-6), validation,
   environment checks restricted to environment nodes (R-13); explicit environment source behaves
   as today.
2. **Port and AWS adapter** (US2): `ParameterStore`/`Fetched`, AWS SDK dependency,
   `SsmParameterStore` against a stub server, boundary rule.
3. **Lazy credentials** (US2): `NodeSecrets`, slot integration (client built after secrets),
   errors (R-11), logging (R-14), e2e credentials.
4. **Time budgets**: `SecretTiming` in fan-out deadlines (R-8), `get_health` budget (R-9).
5. **`--check-config`** (US3, R-12).
6. **Rotation** (US4, R-10): `CredentialGuard` re-read hook and grace attempt.
7. **Docs, CHANGELOG, notices, quickstart validation.**

## Complexity Tracking

None.
</content>
</invoke>
