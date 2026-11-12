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
  `userHome()`, and deletes it recursively in `close()`; a static registry with one JVM shutdown hook
  deletes every such home not yet deleted (servers a test never closes). A deletion failure is
  logged at WARN and otherwise ignored (test cleanup must not fail a test). A second factory family
  takes a caller-owned home (`withHome(…, Path userHome)`): `TestWiring` never deletes such a home;
  the caller does.
- **Rationale**: FR-005: the 24 test classes that use `TestWiring` stay unchanged and get isolation
  without choosing a home; the per-server directory keeps backups of one test invisible to another.
  The caller-owned variant exists for tests that need state to survive a restart of the server
  (the live tests, D-4).
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
  home. Since `LiveTarget` is a record, the state lives in an extra record component, a small
  holder `LiveState` set in `resolve`/`resolveDeployment` (the two `new LiveTarget(...)` calls), which
  owns one temporary directory (created lazily with
  `Files.createTempDirectory("inubit-mcp-live")`, path printed once to stderr as
  `[live] state directory: <path>`, deleted recursively by a JVM shutdown hook) and builds every
  wiring with
  - `TestWiring.withHome(…, <tmp>/home)`, the same home for all wirings of that `LiveTarget`, so
    backups, ledger and packages survive from one wiring to the next as on a real server
    (`DevelopmentLiveTest` imports in one wiring and restores the `backupRef` in a later one);
  - a configuration copy from one package-private `isolated(ProfileConfig, Path workspace)` step that sets
    `auditDirectory = <tmp>/audit`, `workspace` = the caller's workspace or `<tmp>/workspace`, and
    `knowledge = new KnowledgeConfig(<tmp>/knowledge, recording kept)`.
  `CredentialStoreLiveTest` only reads the credential store and stays unchanged.
- **Rationale**: FR-006 and US4: the real configuration may name the real audit, workspace and
  knowledge directories; a live deploy would otherwise append to the real audit log.
- **Alternatives considered**: exempt the audit log of live runs for traceability (Constitution VII):
  rejected, the owner asked for no test artefacts in the home; the server-side INUBIT audit of the
  test node is unaffected. A fresh home per live wiring: breaks restore across wirings.

## D-5 Guard test (static scan)

- **Decision**: `UserHomeReadGuardTest` in package `de.dadecker.inubit.mcp.architecture` (next to the
  other production-source scans such as `TemplateGrammarTest`) walks `src/main/java` and
  blanks out comments with a single-pass scanner whose states are code, string literal, char
  literal, text block, line comment and block comment: comment characters become spaces, newlines are
  kept (so line numbers stay exact), literals stay intact (a `/*` or `//` inside `"https://…"`,
  `"**/*.xml"` or `'"'` is not a comment; a `"user.home"` inside a Javadoc is gone). It then reports `file:line` for:
  - the string literal `"user.home"` in code;
  - `getenv("HOME")`, `getenv("USERPROFILE")`, and `getenv().get(…)` /
    `getenv().getOrDefault(…)` with `"HOME"` or `"USERPROFILE"`;
  - `getProperty(X)`, `getenv(X)`, `getenv().get(X)`, `getenv().getOrDefault(X, …)` where `X` is a
    `static final String` constant of the same file whose value is one of the three names.
  The allowed place is the body of `static Context system(` in `Launcher.java`; the test asserts no
  finding outside it and exactly one inside it, so a moved entry point cannot leave the guard
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
  the real home (D-8). A new test `anImportLeavesTheRealHomeUntouched` runs that import and takes its
  audit id (a random UUID per call) from the test's own audit log. It asserts:
  1. no file whose name starts with that audit id exists below `<user.home>/.inubit-mcp` (backup
     manifests and zips are named `<auditId>.json` / `<auditId>-<n>.zip`);
  2. (with the fix) `<auditId>.json` exists below `<handed-in home>/.inubit-mcp/acme/backups`.
  Assertion 1 is committed first and observed red on the unchanged code (it writes one backup into
  the real home, removed with the follow-up cleanup); assertion 2 and the test's `@TempDir` home come
  with the fix. Keying on the audit id makes the test immune to other processes (older worktrees,
  the developer's own server) writing below the real home at the same time.
  The forbidden export root following the handed-in home (FR-008, second half) is asserted in
  `ExportArchivesWiringTest`: an export into `testWiring.userHome().resolve(".inubit-mcp/export")`
  is refused as a forbidden target.
- **Rationale**: The owner asked for a test that is red while a wiring test writes below the real
  `~/.inubit-mcp`; reusing the scenario that actually caused the backups avoids a second fake-server
  fixture. Reading the real home in test code is allowed (only production code is guarded).
- **Alternatives considered**: a before/after snapshot of `~/.inubit-mcp/acme` (fails spuriously
  when another worktree writes an identical-looking acme backup meanwhile); a new test class with its
  own import fixture (duplicates `WiringAuditTest`'s harness); only the static guard (does not prove
  where state lands).

## D-6a Test seam for the resolved locations

- **Decision**: `Wiring` gets a package-private `HomeLocations homeLocations()` returning a small
  package-private record (backups, deployments, packages, forbiddenExportRoot), filled in the
  canonical constructor from the same variables it hands to the services.
- **Rationale**: FR-009/SC-005 need the four `Wiring`-derived paths checked as the services get them;
  the deployment ledger and packages are otherwise only observable through a full fake deployment.
  The seam is package-private and adds no behaviour.
- **Alternatives considered**: a full fake `deploy_release` through the wiring (large fixture for a
  path check); reflection on private fields (brittle).

## D-7 Dead factory

- **Decision**: `ConfigLoader.forSystem()` is removed (no callers on `main`; checked on the 021 and
  022 branches too).

## D-8 Inventory: which tests write into the real home today

Method: one full `mvn verify` on `main` (5ec65996) with `-DargLine=-Duser.home=<sandbox>`, a marker
in the sandbox, then `find <sandbox> -newer <marker>` (2026-11-12, build green, exit 0).

| Below `<sandbox>/.inubit-mcp` | Files | Writer |
|---|---|---|
| `acme/backups/<auditId>.json` + `<auditId>-1.zip` | 2 × 2 | `WiringAuditTest` (two `import_artifacts` tests): manifest `node dev/node1`, `owner jdoe`, `scope diagram group GRP-01`, `kind IMPORT`, `takenAt 2026-10-21T08:00:00Z` |
| `acme/deployments`, `acme/packages`, `audit`, `workspace`, `knowledge`, `update-check.json` | 0 | – |

The other wiring tests named in the defect report (e.g. `ExportArtifactsWiringTest`,
`NoSecretLeakTest`, `KnowledgeToolsWiringTest`) reach the process home on `main` only through the
read-only forbidden export root; they write nothing there. The surefire JVM of failsafe (`*IT`) wrote
nothing either. During the same run another worktree's test run (older code on another branch) wrote
two further acme backups into the real home: the defect report's higher counts (40 files per night)
come from several branches running the suite. Branches that merge after this fix inherit it through
the `Wiring` signature (their new call sites do not compile without a home).
