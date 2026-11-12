# Research: Test runs leave the real user home untouched

Base: `main` at 0.9.1 (5ec65996).

## D-1 How the composition root gets the home

- **Decision**: Every package-private constructor of `Wiring` gets a `Path userHome` parameter,
  placed right after `ConfigLocation configLocation`, and the canonical constructor stores it
  (`Objects.requireNonNull`). The four reads become `userHome`:
  `TargetFolderRules` (forbidden root `<home>/.inubit-mcp`), `BackupStore.defaultRoot(userHome, …)`,
  `DeploymentLedger.defaultDirectory(userHome, …)`, `PackageWriter.defaultDirectory(userHome, …)`.
  `Launcher` passes `context.userHome()`, which it already passes to `ConfigLoader`, the credential
  store and the update-check cache.
- **Rationale**: The launcher context is already the one place that turns the process into
  parameters; the composition root is the only consumer that bypassed it. A required parameter
  makes "no fallback" (FR-002) a compile-time property.
- **Alternatives considered**:
  - Put the home into `ConfigLocation`: it would travel through every overload for free, but
    `ConfigLocation` means "default configuration directory and file in use"; mixing in the state
    home blurs that and the test constant `TEST_CONFIG_LOCATION` would then decide where state goes.
  - Keep the short overloads defaulting to `System.getProperty("user.home")`: forbidden by FR-002;
    exactly this default is the defect.
  - Set `-Duser.home=<temp>` for the surefire JVM: hides the defect instead of removing it, and the
    live tests need the real home for configuration and keychain.

## D-2 Default temporary home in the test helper

- **Decision**: `TestWiring` creates one fresh directory per built server
  (`Files.createTempDirectory("inubit-mcp-home")`), hands it to `Wiring`, exposes it as
  `userHome()`, and deletes it recursively in `close()`. A homes registry with one JVM shutdown hook
  deletes every home not yet deleted (servers a test never closes). A deletion failure is logged
  and ignored (test cleanup must not fail a test).
- **Rationale**: FR-005: the 24 test classes that use `TestWiring` stay unchanged and get isolation
  without choosing a home; the per-server directory keeps backups of one test invisible to another.
- **Alternatives considered**: a `Path userHome` parameter on every `TestWiring` factory (24 call
  sites, more churn without more safety); one shared temp home per JVM (tests would see each
  other's backups and ledgers).

## D-3 Tests that call the constructor directly

- **Decision**: The seven tests that call `new Wiring(...)` directly (ToolAnnouncementTest,
  WiringAuditTest, KnowledgeToolsWiringTest, MessageTerminologyTest, StartCliClientWiringTest,
  WiringE2eTest, KnowledgeProfilesTest) pass a JUnit `@TempDir` (an existing one where the test has
  it, otherwise a new field).
- **Rationale**: The compiler finds every call site; JUnit removes the directory.

## D-4 Live tests

- **Decision**: `LiveTarget` keeps loading the configuration and the credential store with the real
  home. Every wiring it builds goes through one private `isolated(ProfileConfig, Path workspace)`
  step that replaces `auditDirectory`, `workspace` (unless the test gave one) and
  `knowledge.directory` (keeping `knowledge.recording`) by directories below one temporary
  directory per `LiveTarget`; backups, ledger and packages follow the temporary home from D-2.
  `CredentialStoreLiveTest` only reads the credential store and stays unchanged.
- **Rationale**: FR-006 and US4: the real configuration may name the real audit, workspace and
  knowledge directories; a live deploy would otherwise append to the real audit log.
- **Alternatives considered**: exempt the audit log of live runs for traceability (Constitution VII):
  rejected, the owner asked for no test artefacts in the home; the server-side INUBIT audit of the
  test node is unaffected.

## D-5 Guard test (static scan)

- **Decision**: `UserHomeReadGuardTest` (package `de.dadecker.inubit.mcp.security`, next to the other
  source-scanning checks) walks `src/main/java`, removes comments, and reports `file:line` for:
  - the string literal `"user.home"` in code;
  - `getenv("HOME")` / `getenv("USERPROFILE")` and `getenv().get("HOME")` /
    `getenv().get("USERPROFILE")`;
  - `getProperty(X)` / `getenv(X)` / `getenv().get(X)` where `X` is a `static final String`
    constant of the same file whose value is one of the three names.
  The allowed place is `Launcher.java`, inside `Context.system` only (the method body is located by
  its signature; a read elsewhere in `Launcher.java` is reported). The test also asserts that the
  allowed place still contains exactly one read, so a moved entry point cannot leave the guard
  vacuous.
- **Rationale**: FR-007/US2 ask for file and line, which only a source scan gives. Allowlists such as
  `List.of("HOME", …)` in `StartCliEnvironment`, `KeychainStore`, `SecretServiceStore` and the whole
  `System.getenv()` map handed on in `Wiring`/`Launcher` match none of the patterns (US2 scenario 4).
- **Observed red on `main`**: `ConfigLoader.java:124`, `Wiring.java:461`, `:492`, `:527`, `:544`.
- **Alternatives considered**: ArchUnit (new dependency, works on bytecode, cannot see a string
  constant's use in `getProperty`); a runtime check with a sentinel `user.home` (global JVM state,
  racy, and does not name lines).

## D-6 Behavioural test, red first

- **Decision**: The test goes into `WiringAuditTest`, whose `import_artifacts` scenario on
  `dev/node1` / `GRP-01` with owner `jdoe` and profile `acme` produces exactly the backups found in
  the real home (manifest `scope: diagram group GRP-01`, `kind: IMPORT`). A new test
  `anImportLeavesTheRealHomeUntouched` snapshots `<user.home>/.inubit-mcp/acme` (path → size and
  modification time; an absent directory is an empty snapshot) before the import and asserts:
  1. the snapshot after the import is identical (no new or changed file below the real test-profile
     directory);
  2. (with the fix) the backup manifest of the import lies below
     `<handed-in home>/.inubit-mcp/acme/backups`.
  Assertion 1 is committed first and observed red on the unchanged code (it writes one backup into
  the real home, removed with the follow-up cleanup); assertion 2 comes with the fix, together with
  the test's `@TempDir` home. The snapshot is limited to the test profile `acme` because the
  developer's real server may write `update-check.json` or its own profile directory at any time.
  The forbidden export root following the handed-in home (FR-008, second half) is asserted in
  `ExportArchivesWiringTest`: an export into `testWiring.userHome().resolve(".inubit-mcp/x")` is
  refused as a forbidden target.
- **Rationale**: The owner asked for a test that is red while a wiring test writes below the real
  `~/.inubit-mcp`; reusing the scenario that actually caused the backups avoids a second fake-server
  fixture. Reading the real home in test code is allowed (only production code is guarded).
- **Alternatives considered**: a new test class with its own import fixture (duplicates
  `WiringAuditTest`'s harness); only the static guard (does not prove where state lands).

## D-7 Dead factory

- **Decision**: `ConfigLoader.forSystem()` is removed (no callers on `main`; checked on the 021 and
  022 branches too).

## D-8 Inventory: which tests write into the real home today

Method: one full `mvn verify` on `main` with `-DargLine=-Duser.home=<sandbox>`, a marker in the
sandbox, then `find <sandbox> -newer <marker>`. Result: see the section below (filled in after the
run).
