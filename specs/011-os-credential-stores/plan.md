# Implementation Plan: Credentials Only from a Credential Store

**Branch**: `011-os-credential-stores` (developed on `claude/confident-faraday-kv6oi1`) | **Date**:
2026-10-31 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/011-os-credential-stores/spec.md`

**Base**: `main` after 0.6.2 (feature 010 merged). Released as 0.7.0 (breaking).

## Summary

INUBIT credentials come from exactly two sources: the credential store of the operating system the
server runs on (macOS Keychain, Windows Credential Manager, Linux Secret Service; the default) and
AWS Systems Manager Parameter Store (unchanged from 010). Environment variables are no longer read.
Every node's credentials are read on first use, held in memory, and re-read once after INUBIT
rejects them, so a changed credential never needs a restart.

- **Configuration** (research R-6): `type: credentialStore` replaces `type: environment` (now a
  configuration error with a migration hint); `credentials.prefix` (with `envPrefix` as deprecated
  alias); entry names = today's variable names (`CredentialNames`).
- **Store adapters** (R-1 to R-5): port `CredentialStore` in `config`; `adapter/os` with
  `KeychainStore` (`/usr/bin/security … -g`), `WindowsCredentialStore` (`CredReadW` through JNA),
  `SecretServiceStore` (`secret-tool lookup`), `UnavailableStore`.
- **Lazy credentials for every node** (R-7, R-8): `NodeSecrets.credentialStore(...)` next to
  `NodeSecrets.aws(...)`, with a per-source reader (`EntryReader`, `ParameterReader`); node entry
  before group entry by value; 60 s store timeout covering the whole read of a node, used by every
  caller's wait (`NodeSecrets.timeout()`); reads serialised process-wide (one dialog at a time);
  fan-out allowance only while a node's credentials are not held; the resolved `NodeCredentials`
  stays, the deferred placeholder goes.
- **Audit** (R-17): account fallback "(credential store <account>)" before the first read.
- **Rotation** (R-9): the guard always re-reads; at most two failed logins per node per 60 s.
- **Removal** (R-10, R-11): `CredentialResolver` shrinks to `LeftoverVariables` (warning with names
  only); startup checks that read variable values move to read time and `--check-config`.
- **Messages** (R-12), **`--check-config`** (R-13), **live tests** (R-14), **tests** (R-15),
  **docs and migration** (R-16).

## Technical Context

**Language/Version**: Java 21 (unchanged).

**Primary Dependencies**: existing (MCP Java SDK, Jackson YAML, logback, Saxon-HE, AWS SDK v2) plus
**JNA 5.17.0 core** (`net.java.dev.jna:jna`, Apache-2.0 option of its dual license) for `CredReadW` on
Windows only. Justification in research R-1/R-4. `THIRD-PARTY-NOTICES.md` is updated. Runtime
prerequisites per system: macOS `/usr/bin/security` (part of the OS); Linux `secret-tool`
(`libsecret-tools` on Debian/Ubuntu, `libsecret` on Fedora/Arch) and a Secret Service provider;
Windows nothing.

**Storage**: none; secrets in memory only (`infra.Secret`).

**Testing**:
- JUnit 5, AssertJ; in-memory `FakeCredentialStore` (scriptable values, delays, failures, call log)
  for everything above the adapters;
- adapter tests: `KeychainStore` and `SecretServiceStore` against a fake executable (shell script
  in a temp directory, as the fake StartCLI) that records its arguments and prints recorded outputs
  of the real tools (found, `0x` hex form, not found, denied, no D-Bus, hang for the timeout);
  `WindowsCredentialStore` decoding and outcome mapping behind a small `CredApi` seam, the JNA call
  itself only in the Windows live check;
- fake clock for back-off and pause; existing timing tests for `get_health` and fan-outs;
- opt-in `CredentialStoreLiveTest` per system (FR-017), never in the default build.

**Target Platform**: macOS, Linux and Windows (the profile and configuration directory already
support Windows; this feature adds its store).

**Project Type**: single Maven module, shaded JAR.

**Performance Goals**: SC-006 (store double ≤ 25 ms per entry read, at most six reads with a trust
store → first call ≤ 200 ms slower; later calls unchanged); startup has no store access; fan-out deadlines unchanged once credentials are held.

**Constraints**:
- no secret in tool parameters, results, logs, errors, audit records, files or process arguments
  (Constitution II); subprocesses get names only, values come back on a pipe;
- JNA only in `adapter/os` (`PackageBoundaryTest`); natives loaded only on Windows;
- at most one store read at a time process-wide; 60 s per node read;
- at most two failed INUBIT logins per node per 60 s for every source;
- offline default build; adding JNA needs one online dependency resolution.

**Scale/Scope**: no new tool; changed: profile schema (`credentials.prefix`, `type:
credentialStore`, `environment` removed), `--check-config`, error texts and next steps, `get_health`
pending text, docs. New classes: `CredentialStore` + `Lookup`, `StoreKind`, `CredentialNames`
(renamed from `CredentialVariables`), `E2eCredentials` (moved to `config`), `KeychainStore`,
`WindowsCredentialStore` + `CredApi`, `SecretServiceStore`, `UnavailableStore`, `CredentialStores`,
`StoreGate`, `SecretReader` + `EntryReader` + `ParameterReader`, `EntryCheck`, `LeftoverVariables`.
Changed: `SecretSourceConfig`, `CredentialsConfig`, `ProfileConfig`, `EffectiveNodeConfig`
(`credentialNames()`), `SecretPlan`, `ConfigLoader`, `ConfigValidator` (checks and texts),
`ConfigSummary`, `SecretLabels`, `FailureKind`, `NodeSecrets`, `NodeCredentials` (deferred form
removed), `CredentialGuard`, `AdapterGatewayFactory` (`awaitCredentials` per source), `SslContexts`,
`SecretTiming`, `HealthService`, `CliOutputClassifier`, `Format001Detector`, `Launcher`, `Wiring`
(store seam, audit fallback, pending labels for `HealthService`), `TestWiring`, `LiveTarget`,
`PackageBoundaryTest` (new rule: `com.sun.jna` only in `adapter/os`; the process rule is unchanged). Removed: `CredentialResolver`, `CredentialResolution`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ✅ no new write path | ✅ Lockout bound unchanged (two failed logins per 60 s, R-9); trust store / CLI and e2e over `http://` rules kept, only moved to read time (R-11). |
| II. Secrets Stay Out (NON-NEGOTIABLE) | ⚠️ new channels (subprocess pipes, native call) | ✅ <ul><li>Subprocess arguments carry service and account only; values on stdout/stderr pipes, never on a command line (R-3, R-5).</li><li>Values registered with the scrubber immediately; byte buffers wiped (R-4).</li><li>Raw tool messages only in the scrubbed log (R-12).</li><li>`--check-config` never prints values (R-13).</li><li>`NoSecretLeakTest` extended; `NoEnvironmentCredentialsTest` proves SC-002 (R-15).</li></ul> |
| III. Test-First (NON-NEGOTIABLE) | ✅ | ✅ Every rule has a test first (quickstart table); adapters tested against fake executables and a seam, real stores only in opt-in live checks. |
| IV. Use-Case-Oriented Tools | ✅ | ✅ No tool change (FR-016). |
| V. Adapter Isolation | ✅ | ✅ OS specifics confined to `adapter/os` behind the `config` port `CredentialStore`; JNA boundary rule (R-2). |
| VI. Bounded Output | ✅ | ✅ Single-line error texts with fixed fields. |
| VII. Observability & Auditability | ✅ | ✅ One `secret.fetch` record per read attempt with store and entry names, INFO/WARN (R-7). |
| Tech constraints | ⚠️ new runtime dependency (JNA) | ✅ Justified in R-1/R-4 (no read tool for Windows credentials; FFM not final in Java 21); core JNA only; single shaded JAR kept; notices updated. |

**Result**: PASS. Complexity Tracking: none.

## Project Structure

### Documentation (this feature)

```text
specs/011-os-credential-stores/
├── plan.md
├── research.md                 # R-1 … R-17
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── configuration-delta.md
│   └── mcp-tools-delta.md
├── checklists/requirements.md
├── reviews/                    # spec-r1, spec-r2, plan-r1 …
└── tasks.md                    # /speckit-tasks
```

### Source Code

```text
src/main/java/de/dadecker/inubit/mcp/
├── config/        # CredentialStore (+ Lookup), StoreKind, CredentialNames (renamed from CredentialVariables),
│                  # SecretSourceConfig.CredentialStore (Environment removed), CredentialsConfig.prefix,
│                  # ProfileConfig.credentialPrefix/credentialNameSet, SecretPlan, ConfigLoader (environment refused),
│                  # ConfigValidator (name checks, moved value checks), ConfigSummary, EntryCheck (new),
│                  # LeftoverVariables (new; replaces CredentialResolver/CredentialResolution), SecretLabels, FailureKind,
│                  # E2eCredentials (moved), NodeCredentials (resolved form only), EffectiveNodeConfig, Format001Detector (hint)
├── adapter/os/    # KeychainStore, WindowsCredentialStore (+ CredApi, JNA mapping), SecretServiceStore,
│                  # UnavailableStore, CredentialStores, StoreGate (+ package-info) — the only JNA user
├── adapter/       # NodeSecrets + SecretReader/EntryReader/ParameterReader (environment variant removed),
│                  # CredentialGuard (re-readable for every authenticated client), AdapterGatewayFactory (wait per source)
├── adapter/rest/  # SslContexts: CredentialNames, texts via SecretLabels
├── adapter/cli/   # CliOutputClassifier: login-failure text from SecretLabels
├── application/   # SecretTiming (per-node wait, only while not held), HealthService (pending text per store)
├── Launcher.java  # no credential resolution at startup; LeftoverVariables warning; EntryCheck in --check-config; --help
└── Wiring.java    # CredentialStore seam (CredentialStores.forOs in production), NodeSecrets for every node, audit fallback
src/test/…          # FakeCredentialStore, fake security / secret-tool executables, tests per quickstart table,
                    # NoEnvironmentCredentialsTest, CredentialStoreLiveTest (opt-in), TestWiring/LiveTarget with a store
pom.xml, THIRD-PARTY-NOTICES.md   # JNA core; live-store profile
docs/setup.md (section 4 rewritten), docs/migration-environment-variables.md (new), docs/tools.md,
docs/live-tests.md, docs/migration-001-to-002.md (note), README, SECURITY.md, CHANGELOG (0.7.0, breaking)
```

**Structure Decision**: the same single module. Stages, each reviewed and ending with a green
`mvn verify`:

1. **Port and adapters** (US1, US2): `CredentialStore`/`Lookup`/`StoreKind`, `FailureKind` additions,
   `StoreGate`, the four adapters with their tests, JNA dependency and boundary rule,
   `CredentialStores.forOs`.
2. **Configuration model** (US3): `CredentialNames`, `type: credentialStore` as the default,
   `environment` refused, `credentials.prefix` / `envPrefix`, name checks on credential store nodes,
   `LeftoverVariables` warning.
3. **Lazy credentials for every node** (US1, US2): `SecretReader` split, `NodeSecrets.credentialStore`,
   node-before-group fallback, moved value checks (R-11), e2e cell, `timeout()` used by every wait,
   `Wiring`/`TestWiring` store seam, audit fallback (R-17), removal of the environment path
   (`CredentialResolver`, the deferred `NodeCredentials` placeholder), `NoEnvironmentCredentialsTest`.
4. **Time budgets**: `SecretTiming` per node and only while not held, `get_health` pending text.
5. **Rotation and messages** (US5): guard always re-readable, `SecretLabels` credential store
   variant, `CliOutputClassifier`.
6. **`--check-config`** (US4): `EntryCheck`, `ConfigSummary` lines.
7. **Live tests** (US6): `LiveTarget` with the real store through `TestWiring`, `CredentialStoreLiveTest`.
8. **Docs, migration guide, CHANGELOG, notices, quickstart validation**; macOS live check executed
   before the release (SC-001).

## Complexity Tracking

None.
