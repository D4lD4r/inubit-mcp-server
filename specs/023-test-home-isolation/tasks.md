# Tasks: Test runs leave the real user home untouched

**Input**: Design documents from `/specs/023-test-home-isolation/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/wiring-home.md](contracts/wiring-home.md),
[quickstart.md](quickstart.md)

**Tests**: requested (Constitution III, spec SC-004): the two red tests come first and are observed
failing before any production change.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: the user story of spec.md the task serves

Paths are relative to the repository root; `main/…` = `src/main/java/de/dadecker/inubit/mcp/…`,
`test/…` = `src/test/java/de/dadecker/inubit/mcp/…`.

## Phase 1: Setup

- [x] T001 Inventory of the real-home writers on `main`, recorded in
  `specs/023-test-home-isolation/research.md` D-8 (sandbox run: only `WiringAuditTest`, two import
  backups per run; no other location written).

## Phase 2: Foundational

None: the red tests of US1 and US2 are independent of each other.

## Phase 3: User Story 1 – A test run does not touch my home directory (P1) 🎯 MVP

**Goal**: No test writes below the real `~/.inubit-mcp`; per-profile state follows the home handed
to `Wiring`.

**Independent test**: `WiringAuditTest#anImportLeavesTheRealHomeUntouched` green; quickstart step 3
(`find ~/.inubit-mcp -newer <marker>` empty after `mvn verify`).

- [X] T002 [US1] Red test in `test/WiringAuditTest.java` (research D-6): add
  `anImportLeavesTheRealHomeUntouched()`. Start the server as the existing non-AWS import test does,
  run `importGroup()`, assert it executed, take the import's audit id from the test's own audit log
  (`audit("import_artifacts")`, field holding the audit id), and assert that no file whose name
  starts with that audit id exists below `Path.of(System.getProperty("user.home"))
  .resolve(".inubit-mcp")` (walk the tree if it exists; message names the offending paths). Run it
  on the unchanged production code and observe it red; put the failure line into the commit body.
  No production change in this task.
- [X] T003 [US1] Remove the dead factory `ConfigLoader.forSystem()` and imports that become unused in
  `main/config/ConfigLoader.java` (FR-004; no callers on `main`, 021 or 022).
- [X] T004 [US1] Make the home a required `Wiring` parameter (research D-1, D-2, D-6a; contract
  `contracts/wiring-home.md`). In `main/Wiring.java`: add `Path userHome` directly after
  `ConfigLocation configLocation` to every constructor (Javadoc `@param userHome the home below which
  the per-profile state lies ({@code <home>/.inubit-mcp}); the caller's, never the process's`),
  `Objects.requireNonNull(userHome, "userHome")` in the canonical constructor, replace the four
  `Path.of(System.getProperty("user.home"))` reads (TargetFolderRules, `BackupStore.defaultRoot`,
  `DeploymentLedger.defaultDirectory`, `PackageWriter.defaultDirectory`) by it, add the
  package-private record `HomeLocations(Path backups, Path deployments, Path packages,
  Path forbiddenExportRoot)` and accessor `homeLocations()` filled from exactly the variables handed
  to the services (deployments and packages are computed even without a stage chain, for the
  record), and adjust the class Javadoc that names `~/.inubit-mcp/<profile>/backups`. In
  `main/Launcher.java` pass `context.userHome()` to `new Wiring(...)`. In `test/TestWiring.java`:
  per built server a fresh `Files.createTempDirectory("inubit-mcp-home")`, `public Path userHome()`,
  `close()` closes the wiring then deletes the home recursively, a static registry plus one JVM
  shutdown hook deletes homes never closed, deletion failures logged at WARN and otherwise ignored;
  plus `public static TestWiring withHome(<parameters of withParameterStoreSupplier>, Path userHome)`
  that uses the caller's home and never deletes it. Update the seven direct callers to pass a
  `@TempDir`-based home (reuse an existing `@TempDir` field, e.g. `temp.resolve("home")`):
  `test/ToolAnnouncementTest.java`, `test/WiringAuditTest.java`, `test/KnowledgeToolsWiringTest.java`,
  `test/MessageTerminologyTest.java`, `test/StartCliClientWiringTest.java`,
  `test/WiringE2eTest.java`, `test/KnowledgeProfilesTest.java`. `mvn -q test-compile` and the touched
  classes green; T002 now green. Red tests for this step: T002 (backup in the real home) and T006
  (the reads); the `Launcher` hand-over is characterized afterwards by T007 (unchanged layout, FR-009).
- [X] T005 [US1] Complete the behavioural assertions (FR-008, research D-6): in
  `test/WiringAuditTest.java` extend `anImportLeavesTheRealHomeUntouched()` to assert that
  `<auditId>.json` exists below `<the test's handed-in home>/.inubit-mcp/acme/backups`. In
  `test/ExportArchivesWiringTest.java` add a test, modelled on
  `aCallIntoTheConfigurationFilesFolderIsRefusedBeforeStartCli`, that calls `export_archives` with
  target folder `testWiring.userHome().resolve(".inubit-mcp/export")` and asserts the refusal as a
  forbidden target before StartCLI is started.

**Checkpoint**: US1 delivered; `mvn -q test` green.

## Phase 4: User Story 2 – A regression is caught by the build (P1)

**Goal**: A static guard fails as soon as production code other than `Launcher.Context.system`
reads the process home.

**Independent test**: `UserHomeReadGuardTest` red on `main` (5 findings), green after T003/T004.

- [X] T006 [P] [US2] Red guard test `test/architecture/UserHomeReadGuardTest.java` (research D-5,
  spec FR-007). Scanner over every `*.java` below `src/main/java`: a single-pass state machine (states: code,
  string literal with `\` escapes, char literal, text block `"""…"""`, line comment, block
  comment incl. Javadoc) that replaces comment characters by spaces and keeps every newline, so line
  numbers stay exact and literals stay intact (`TemplateGrammarTest` stays unchanged); then report
  `relative/path.java:line` for: the literal `"user.home"`; `getenv("HOME")`,
  `getenv("USERPROFILE")`; `getenv().get(…)` and `getenv().getOrDefault(…)` with `"HOME"` or
  `"USERPROFILE"`; `getProperty(X)`, `getenv(X)`, `getenv().get(X)`, `getenv().getOrDefault(X, …)`
  where `X` is a `static final String` constant of the same file whose value is one of the three
  names (whitespace tolerant). The allowed place is the body of `static Context system(` in
  `Launcher.java`; assert no finding outside it and exactly one inside it. Scanner self-tests on
  inline snippets: not reported — a read in a `//` comment, in a multi-line Javadoc (and a read on
  the line after it IS reported with the right line number), inside a message string, `'"'` followed
  by a comment on the same line, a read inside a text block,
  `List.of("HOME", "PATH")`, `new HashMap<>(System.getenv())`, and code following the literals
  `"https://x"` and `"**/*.xml"` is still scanned (a read after them on the same or next line IS
  reported); reported — `System.getProperty(H)` with `private static final String H =
  "user.home";`, `System.getenv().getOrDefault("HOME", "/")`. Run before T003/T004 and observe red
  with the five findings `config/ConfigLoader.java:124`, `Wiring.java:461, 492, 527, 544`; put the
  red output into the commit body.

**Checkpoint**: US2 delivered.

## Phase 5: User Story 3 – The real server keeps its locations (P2)

**Goal**: A normally started server resolves every location exactly as before (data-model.md).

**Independent test**: quickstart step 4 green.

- [X] T007 [US3] `test/WiringHomeLocationsTest.java` (package `de.dadecker.inubit.mcp`): build
  `Wiring` for a `@TempDir` home H and profile `acme` with a minimal valid configuration loaded via
  `new ConfigLoader(Map.of(), H, false)` without explicit directories, and assert
  `homeLocations()` = backups `H/.inubit-mcp/acme/backups`, deployments
  `H/.inubit-mcp/acme/deployments`, packages `H/.inubit-mcp/acme/packages`, forbidden export root
  `H/.inubit-mcp`; and from the loaded `ProfileConfig` audit `H/.inubit-mcp/acme/audit`, workspace
  `H/.inubit-mcp/acme/workspace`, knowledge `H/.inubit-mcp/acme/knowledge`. The update-check cache
  path `H/.inubit-mcp/update-check.json` is already asserted by
  `test/infra/FileUpdateCacheTest.java:36` (cite it in the class Javadoc). In `test/MainTest.java`
  add (a) a separate `@Test` method without an OS condition (the neighbouring
  `Context.system` test is `@DisabledOnOs(WINDOWS)`):
  `assertThat(Launcher.Context.system(System.out).userHome()).isEqualTo(Path.of(System.getProperty("user.home")))`,
  and (b) a test that runs the launcher with its temporary-home `Context` and a `toolHandlers`
  function capturing `wiring.homeLocations()`, asserting `backups` lies below that temporary home.

**Checkpoint**: US3 delivered.

## Phase 6: User Story 4 – Live tests read but do not write my home (P3)

**Goal**: Live runs load configuration and keychain from the real home and write nothing there.

**Independent test**: `LiveTargetTest` green; optional live run per quickstart step 5.

- [X] T008 [US4] `test/live/LiveTarget.java` (research D-4, contract): `LiveTarget` is a record, so
  add a component `LiveState state` (a small holder class in the same file or package) created in
  `resolve`/`resolveDeployment` (the `new LiveTarget(...)` calls at about lines 97 and 170); the
  holder owns one temporary directory per `LiveTarget`, created lazily with `Files.createTempDirectory("inubit-mcp-live")`, its path printed
  once to stderr as `[live] state directory: <path>`, deleted recursively by a JVM shutdown hook; a
  package-private `Path stateDirectory()` and package-private
  `isolated(ProfileConfig config, Path workspace)` returning a copy with
  `auditDirectory = <tmp>/audit`, `workspace = workspace != null ? workspace : <tmp>/workspace`,
  `knowledge = new KnowledgeConfig(<tmp>/knowledge, config.knowledge().recording())`; route every
  `wiring(...)` / `readOnlyRepositoryWiring(...)` through `isolated` and build each wiring with
  `TestWiring.withHome(…, <tmp>/home)` (one home shared by all wirings of the `LiveTarget`, so
  `DevelopmentLiveTest`'s restore of a `backupRef` from an earlier wiring keeps working). Keep
  `store()` and the configuration loading on the real home. Extend `test/live/LiveTargetTest.java`
  (same package; uses only `stateDirectory()`, `isolated(…)` and the public `TestWiring.userHome()`):
  for a `LiveTarget` resolved from a test configuration, `isolated(config, null)` has audit,
  workspace and knowledge directories below `stateDirectory()` (and keeps `knowledge.recording`),
  `isolated(config, w)` keeps workspace `w`; a built wiring's `userHome()` lies below
  `stateDirectory()` and outside `<user.home>/.inubit-mcp` (backups, deployments and packages follow
  `userHome()` per `WiringHomeLocationsTest`); two wirings of the same `LiveTarget` have the same
  `userHome()`.

**Checkpoint**: US4 delivered (live run itself optional, see quickstart step 5).

## Phase 7: Polish & validation

- [X] T009 [P] Docs: `CHANGELOG.md` under `## [Unreleased]` add `### Fixed` with one line ("Tests and
  live tests no longer write into `~/.inubit-mcp`; the server's state locations are unchanged.");
  in `docs/live-tests.md` replace the statements that live runs leave backups, ledger, packages and
  audit records under `~/.inubit-mcp/<profile>/` (around lines 110, 170, 205, 218; leave line 525,
  which is about the real server in "Manual write checks") by the live state directory of T008:
  printed at the start of the run as `[live] state directory: <path>`, deleted at JVM exit.
- [ ] T010 Validation (quickstart steps 1–4): with no other inubit-mcp process active,
  `touch <marker> && mvn verify && find ~/.inubit-mcp -newer <marker>`; record command, result and
  the empty `find` output in `specs/023-test-home-isolation/validation.md`; if `find` lists a file,
  identify the writer, fix it in the responsible task and repeat.

## Follow-up after merge (not on the branch)

- Move `~/.inubit-mcp/acme` to the trash with `trash ~/.inubit-mcp/acme` (spec "Follow-up after
  merge").

## Dependencies & execution order

```text
T002 ─┐
T006 ─┴─► T003 ─► T004 ─► T005 ─► T007 ─► T008 ─► T009 ─► T010
```

- T002 and T006 must be committed and observed red before T003/T004 (Test-First).
- T004 touches 11 files on purpose: the `Wiring` signature change compiles only together with its
  callers.
- T007 and T008 use `homeLocations()` and `withHome(…)` from T004.

## Parallel opportunities

- T006 (new file, scanner only) alongside T002.
- T009 (docs) is independent of code but kept before T010 so the validation covers the final branch.

## Implementation strategy

MVP = US1 (T002–T005): the defect is gone. US2 makes it stay gone; US3 pins the unchanged layout;
US4 extends the guarantee to the opt-in live runs.
