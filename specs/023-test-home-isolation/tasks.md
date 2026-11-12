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

Paths are relative to the repository root; Java packages below `src/main/java/de/dadecker/inubit/mcp/`
(main) and `src/test/java/de/dadecker/inubit/mcp/` (test), written `main/…` and `test/…` below.

## Phase 1: Setup

- [ ] T001 Record the inventory of D-8 in `specs/023-test-home-isolation/research.md`: from the
  sandbox run on `main` (`mvn verify -DargLine=-Duser.home=<sandbox>`, marker in the sandbox) list
  every path below `<sandbox>/.inubit-mcp` newer than the marker, grouped by directory, and name the
  test class(es) that produced them (manifest fields `node`, `owner`, `scope`, `kind` identify the
  `WiringAuditTest` import scenario). If the run shows locations beyond backups, add them to D-8 and
  to the later tasks before continuing. No code change.

## Phase 2: Foundational

None: the red tests of US1 and US2 are independent of each other.

## Phase 3: User Story 1 – A test run does not touch my home directory (P1) 🎯 MVP

**Goal**: No test writes below the real `~/.inubit-mcp`; per-profile state follows the home handed
to `Wiring`.

**Independent test**: `WiringAuditTest#anImportLeavesTheRealHomeUntouched` green; quickstart step 3
(`find ~/.inubit-mcp -newer <marker>` empty after `mvn verify`).

- [ ] T002 [US1] Red test in `test/WiringAuditTest.java`: add
  `anImportLeavesTheRealHomeUntouched()`. Before `importGroup()` take a snapshot of
  `Path.of(System.getProperty("user.home")).resolve(".inubit-mcp/acme")` as a map
  relative path → (size, last-modified) of every regular file (an absent directory is an empty map);
  start the server as the existing AWS-free import tests do, run `importGroup()`, assert it was
  executed, take the snapshot again and assert both maps are equal (AssertJ, message naming the new
  or changed paths). Run it on the unchanged production code and observe it red (research D-6);
  paste the failure line into the commit message body. No production change in this task.
- [ ] T003 [US1] Make the home a required `Wiring` parameter (research D-1, contract
  `contracts/wiring-home.md`): in `main/Wiring.java` add `Path userHome` directly after
  `ConfigLocation configLocation` to every constructor (all overloads, Javadoc `@param userHome the
  home below which the per-profile state lies ({@code <home>/.inubit-mcp}); the caller's, never the
  process's`), store it with `Objects.requireNonNull(userHome, "userHome")` in the canonical
  constructor and replace the four `Path.of(System.getProperty("user.home"))` reads (TargetFolderRules,
  `BackupStore.defaultRoot`, `DeploymentLedger.defaultDirectory`, `PackageWriter.defaultDirectory`)
  by it; update the class Javadoc that names `~/.inubit-mcp/<profile>/backups`. In
  `main/Launcher.java` pass `context.userHome()` to the `new Wiring(...)` call. In
  `main/config/ConfigLoader.java` delete `forSystem()` and imports that become unused. In
  `test/TestWiring.java` implement research D-2: per built server a fresh
  `Files.createTempDirectory("inubit-mcp-home")`, passed to `Wiring`; `public Path userHome()`;
  `close()` closes the wiring, then deletes the directory recursively; a static registry plus one JVM
  shutdown hook deletes homes never closed; deletion failures are ignored. Update the seven direct
  callers to pass a `@TempDir` (reuse an existing `@TempDir` field where the class has one, e.g.
  `temp.resolve("home")`): `test/ToolAnnouncementTest.java`, `test/WiringAuditTest.java`,
  `test/KnowledgeToolsWiringTest.java`, `test/MessageTerminologyTest.java`,
  `test/StartCliClientWiringTest.java`, `test/WiringE2eTest.java`, `test/KnowledgeProfilesTest.java`.
  `mvn -q test-compile` and the touched test classes green; T002 now green.
- [ ] T004 [US1] Complete the behavioural assertions (FR-008, research D-6): in
  `test/WiringAuditTest.java` extend `anImportLeavesTheRealHomeUntouched()` to assert that exactly
  one backup manifest (`*.json`) of the import exists below `<handed-in home>/.inubit-mcp/acme/backups`.
  In `test/ExportArchivesWiringTest.java` add a test, modelled on
  `aCallIntoTheConfigurationFilesFolderIsRefusedBeforeStartCli`, that calls `export_archives` with a
  target folder `testWiring.userHome().resolve(".inubit-mcp/export")` and asserts the refusal as a
  forbidden target before StartCLI is started.

**Checkpoint**: US1 delivered; `mvn -q test` green.

## Phase 4: User Story 2 – A regression is caught by the build (P1)

**Goal**: A static guard fails as soon as production code other than `Launcher.Context.system`
reads the process home.

**Independent test**: `UserHomeReadGuardTest` red on `main` (5 findings), green after T003.

- [ ] T005 [P] [US2] Red guard test `test/security/UserHomeReadGuardTest.java` (research D-5, spec
  FR-007): a small scanner (package-private nested class or static methods in the test) that reads
  every `*.java` below `src/main/java`, blanks out comments (`//…`, `/*…*/`, keeping line numbers),
  and reports `relative/path.java:line` for: the string literal `"user.home"`;
  `getenv("HOME")`, `getenv("USERPROFILE")`, `getenv().get("HOME")`, `getenv().get("USERPROFILE")`
  (whitespace tolerant); `getProperty(X)`, `getenv(X)`, `getenv().get(X)` where `X` is a
  `static final String` constant of the same file whose value is `"user.home"`, `"HOME"` or
  `"USERPROFILE"`. The allowed place is the body of `static Context system(` in
  `Launcher.java`; the test asserts (a) no finding outside it and (b) exactly one finding inside it.
  Scanner self-tests on inline source snippets: a read in a comment, in a Javadoc and in a message
  string is not reported; `List.of("HOME", "PATH")` and `new HashMap<>(System.getenv())` are not
  reported (spec US2 scenarios 3–4); a constant `private static final String H = "user.home";` used
  in `System.getProperty(H)` is reported. Run it before T003 and observe it red with the five
  findings `config/ConfigLoader.java:124` and `Wiring.java:461, 492, 527, 544`; record the red output
  in the commit body. T005 runs before T003.

**Checkpoint**: US2 delivered.

## Phase 5: User Story 3 – The real server keeps its locations (P2)

**Goal**: A normally started server resolves every location exactly as before (data-model.md).

**Independent test**: the test of T006 and `MainProcessTest` green.

- [ ] T006 [US3] Location test in `test/WiringHomeLocationsTest.java` (package
  `de.dadecker.inubit.mcp`, so it can call the package-private constructor): build `Wiring` for a
  `@TempDir` home H and profile `acme` with a minimal valid configuration loaded through
  `new ConfigLoader(Map.of(), H, false)` (no explicit directories), and assert the resolved
  locations against data-model.md: backups `H/.inubit-mcp/acme/backups`, deployments
  `H/.inubit-mcp/acme/deployments`, packages `H/.inubit-mcp/acme/packages`, audit
  `H/.inubit-mcp/acme/audit`, workspace `H/.inubit-mcp/acme/workspace`, knowledge
  `H/.inubit-mcp/acme/knowledge`. Read the four `Wiring`-derived paths through one package-private
  accessor `Wiring.homeLocations()` returning a small record (backups, deployments, packages,
  forbiddenExportRoot) that the canonical constructor fills from the same variables it hands to the
  services (so the test checks what the services get, not a recomputation); the three
  configuration-derived ones from the loaded `ProfileConfig`. Also assert in `test/MainTest.java`
  (or the nearest Launcher test with a `Context` built for a temporary home) that the launcher hands
  `context.userHome()` to the wiring, e.g. via a `toolHandlers` function that captures
  `wiring.homeLocations()`.

**Checkpoint**: US3 delivered.

## Phase 6: User Story 4 – Live tests read but do not write my home (P3)

**Goal**: Live runs load configuration and keychain from the real home and write nothing there.

**Independent test**: `LiveTargetTest` green; optional live run per quickstart step 5.

- [ ] T007 [US4] `test/live/LiveTarget.java` (research D-4, contract): add one temporary directory
  per `LiveTarget` (created lazily with `Files.createTempDirectory("inubit-mcp-live")`, removed by a
  JVM shutdown hook) and a private `isolated(ProfileConfig config, Path workspace)` that returns a
  copy with `auditDirectory = <tmp>/audit`, `workspace = workspace != null ? workspace :
  <tmp>/workspace`, `knowledge = new KnowledgeConfig(<tmp>/knowledge, config.knowledge().recording())`;
  route every `wiring(...)` / `readOnlyRepositoryWiring(...)` through it. Expose
  `Path auditDirectory()` returning the isolated audit directory, and change every live test that
  reads audit files via `loaded().config().auditDirectory()` (grep `auditDirectory()` in
  `test/live/`) to use it. Keep `store()` and the configuration loading on the real home. Extend
  `test/live/LiveTargetTest.java` with a test that the wiring's configuration has its audit,
  workspace and knowledge directories outside `<user.home>/.inubit-mcp`.

**Checkpoint**: US4 delivered (live run itself optional, see quickstart step 5).

## Phase 7: Polish & validation

- [ ] T008 [P] Docs: `CHANGELOG.md` under `## [Unreleased]` add `### Fixed` with one line ("Tests and
  live tests no longer write into `~/.inubit-mcp`; the server's state locations are unchanged.");
  in `docs/live-tests.md` replace the statements that live runs leave backups, ledger, packages and
  audit records under `~/.inubit-mcp/<profile>/` (around lines 110, 170, 205, 218, 525) by the
  temporary locations of T007, and say where to find them during a run (printed path or
  `LiveTarget.auditDirectory()`).
- [ ] T009 Validation (quickstart steps 1–4): with no other inubit-mcp process active,
  `touch <marker> && mvn verify && find ~/.inubit-mcp -newer <marker>`; record command, result and
  the empty `find` output in `specs/023-test-home-isolation/validation.md`; if `find` lists a file,
  identify the writer, fix it in the responsible task and repeat.

## Follow-up after merge (not on the branch)

- Move `~/.inubit-mcp/acme` to the trash with `trash ~/.inubit-mcp/acme` (spec "Follow-up after
  merge").

## Dependencies & execution order

```text
T001 ─► T002 ─┐
T001 ─► T005 ─┴─► T003 ─► T004 ─► T006 ─► T007 ─► T008 ─► T009
```

- T002 and T005 must be committed and observed red before T003 (Test-First).
- T003 breaks compilation of the seven direct callers until they are updated in the same task.
- T006 needs the accessor added in T003's canonical constructor; it may add it itself.

## Parallel opportunities

- T005 (new file, scanner only) alongside T002.
- T008 (docs) is independent of code but kept before T009 so the validation covers the final branch.

## Implementation strategy

MVP = US1 (T002–T004): the defect is gone. US2 makes it stay gone; US3 pins the unchanged layout;
US4 extends the guarantee to the opt-in live runs.
