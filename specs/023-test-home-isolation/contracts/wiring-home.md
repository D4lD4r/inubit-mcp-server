# Contract: the home of the composition root

Internal contract (package-private `Wiring`, test-visible `TestWiring`); no MCP interface changes.

## Wiring

- Every constructor takes `Path userHome` directly after `ConfigLocation configLocation`.
- `userHome` is required (`NullPointerException` with the message `userHome` otherwise).
- No constructor reads `System.getProperty("user.home")`, `System.getenv("HOME")` or
  `System.getenv("USERPROFILE")`, and none supplies a default for `userHome`.
- Locations derived from it: see [data-model.md](../data-model.md), rows 1–4.
- Test seam (research D-6a): package-private `HomeLocations homeLocations()`, a package-private
  record `(Path backups, Path deployments, Path packages, Path forbiddenExportRoot)` holding exactly
  the values handed to `BackupStore`/`E2eTestService`, `DeploymentLedger`, `PackageWriter` and
  `TargetFolderRules`.

## Launcher

- `Launcher.Context.system` is the only production code that reads the process home.
- `Launcher` passes `context.userHome()` to `Wiring` (the same value it passes to `ConfigLoader`,
  the credential store and the update-check cache).

## TestWiring

- Every existing factory keeps its signature; each built server gets a fresh directory from
  `Files.createTempDirectory("inubit-mcp-home")` as its `userHome`.
- `Path userHome()` returns that directory.
- `close()` closes the wiring and then deletes the directory recursively; directories of servers
  that were never closed are deleted by a JVM shutdown hook. A deletion failure is logged at WARN
  and otherwise ignored.
- `withHome(…, Path userHome)` (same parameters as `withParameterStoreSupplier` plus the home):
  uses the caller's home and never deletes it; `userHome()` returns it.

## LiveTarget

- Configuration and credential store: loaded with the real home (unchanged).
- One temporary directory per `LiveTarget` (path printed once to stderr, deleted by a JVM shutdown
  hook). Every `TestWiring` it builds uses `withHome(…, <tmp>/home)` — the same home for all its
  wirings, so a backup taken in one wiring can be restored in the next — and a configuration whose
  `auditDirectory`, `workspace` (unless the caller passes one) and `knowledge.directory` lie below
  `<tmp>`; `knowledge.recording` is kept.
- `LiveTarget` is a record: the temporary directory is held by an extra component, a holder
  `LiveState` (lazy creation, shutdown hook), created in `resolve`/`resolveDeployment`.
- Test visibility: package-private `Path stateDirectory()` and
  `ProfileConfig isolated(ProfileConfig config, Path workspace)` for `LiveTargetTest` (same
  package); the four `Wiring`-derived locations are observed through the public
  `TestWiring.userHome()` (pinned to backups/deployments/packages/forbidden root by
  `WiringHomeLocationsTest`). No new public API on `TestWiring` beyond `userHome()` and `withHome(…)`.
