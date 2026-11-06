# Tasks: Update Check and Manifest Version

**Input**: Design documents from `specs/016-update-check/` (spec, plan, research, data-model,
contracts, quickstart)

**Tests**: mandatory (constitution Principle III, TDD): each implementation task starts with its
failing test in the same task. No test may contact GitHub (research R-10).

**Build**: `mvn -o -q verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-016-tmp` (tmpdir outside
the checkout; never two builds in parallel).

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [X] T001 Add `maven-failsafe-plugin` (version property `maven-failsafe-plugin.version` = 3.6.1, in
  `pluginManagement` and `plugins` with goals `integration-test` and `verify`, honouring
  `${argLine}`) in `pom.xml`; confirm it resolves offline (`mvn -o -q verify` stays green)

## Phase 2: Foundational

- [X] T002 [P] `SemVer` per data-model.md ("`MAJOR.MINOR.PATCH[-PRE][+BUILD]` (build ignored), else
  `Optional.empty()`", "no leading zeros", SemVer 2.0.0 precedence, `0.8.0-SNAPSHOT < 0.8.0`) with
  tests in `src/main/java/de/dadecker/inubit/mcp/domain/model/SemVer.java` and
  `src/test/java/de/dadecker/inubit/mcp/domain/model/SemVerTest.java`

## Phase 3: User Story 3 – The JAR carries its own version (P1)

**Goal**: manifest names the version; the server reports it independent of the file name.
**Independent test**: `JarVersionIT` passes in `mvn verify`.

- [X] T003 [US3] `BuildInfo`: testable `static Optional<String> manifestVersion(Path jar)` (main
  attribute `Implementation-Version` of a JAR file; empty for a directory, missing file or missing
  attribute) and `static String resolve(Optional<String> manifest, Properties buildProperties)`
  (manifest first, else `build.properties`, else the existing `IllegalStateException`); `version()`
  uses the `CodeSource` location of `BuildInfo.class`; tests with temporary JARs in
  `src/main/java/de/dadecker/inubit/mcp/infra/BuildInfo.java`,
  `src/test/java/de/dadecker/inubit/mcp/infra/BuildInfoTest.java`
- [ ] T004 [US3] Manifest entries `Implementation-Title: ${project.artifactId}` and
  `Implementation-Version: ${project.version}` in the shade `ManifestResourceTransformer` of
  `pom.xml`; Failsafe test `src/test/java/de/dadecker/inubit/mcp/JarVersionIT.java` (system
  properties `project.version` and `project.jar` from `pom.xml`): reads the manifest, copies the JAR
  to `inubit-mcp-server.jar` in a temp directory, runs `java -jar <copy> --version` and expects
  `inubit-mcp-server <project.version>` (FR-010–FR-012, SC-004; `serverInfo` uses the same
  `BuildInfo.version()`, Launcher)

## Phase 4: User Story 1 – Learn about a newer release at start (P1)

**Goal**: one background check after start; notice in log (WARN once) and `get_health.serverUpdate`.
**Independent test**: Launcher test with a fake source announcing a higher version.

- [ ] T005 [P] [US1] Port `ReleaseSource` (`LatestRelease latest() throws ReleaseCheckException`),
  `record LatestRelease(SemVer version)`, `ReleaseCheckException` (message only, no stack trace
  needed) in `src/main/java/de/dadecker/inubit/mcp/application/ReleaseSource.java`
- [ ] T006 [P] [US1] `ServerUpdate` record per data-model.md ("`notice` only if `updateAvailable`",
  URL `https://github.com/D4lD4r/inubit-mcp-server/releases/tag/v<latest>`) with factory
  `of(SemVer current, SemVer latest, Instant checkedAt)` and tests in
  `src/main/java/de/dadecker/inubit/mcp/domain/model/ServerUpdate.java`,
  `src/test/java/de/dadecker/inubit/mcp/domain/model/ServerUpdateTest.java`
- [ ] T007 [US1] `GitHubReleaseSource` (research R-1, R-2, R-5: fixed URL, headers `Accept`,
  `X-GitHub-Api-Version`, `User-Agent: inubit-mcp-server/<version>`; `ProxySelector.getDefault()`;
  connect 5 s, request 10 s; body ≤ 1 MiB; only `tag_name` = `v`+SemVer, `draft`/`prerelease` true
  rejected; every failure → `ReleaseCheckException`; default TLS verification, no custom `SSLContext`), base URI injectable; WireMock tests incl.
  404, 403 rate limit, malformed JSON, bad tag, oversized body, slow answer, and SC-005 (captured
  request contains no configuration name) in
  `src/main/java/de/dadecker/inubit/mcp/adapter/github/GitHubReleaseSource.java`,
  `src/test/java/de/dadecker/inubit/mcp/adapter/github/GitHubReleaseSourceTest.java`
- [ ] T008 [US1] `UpdateStatus` (thread-safe holder) and `UpdateCheck.checkOnce()` (compare,
  record, WARN once per newer version, DEBUG on failure keeping the last status; FR-004, FR-007,
  FR-008; `Clock` injected for `checkedAt`) with tests capturing log events in
  `src/main/java/de/dadecker/inubit/mcp/application/UpdateStatus.java`,
  `src/main/java/de/dadecker/inubit/mcp/application/UpdateCheck.java`,
  `src/test/java/de/dadecker/inubit/mcp/application/UpdateCheckTest.java`
- [ ] T009 [US1] `get_health`: `Result(List<HealthReport> reports, Optional<ServerUpdate>
  serverUpdate)` from a `Supplier<Optional<ServerUpdate>>`; output schema `$defs/ServerUpdate`
  (contracts/get_health-delta.md); description sentence; `Wiring` owns the `UpdateStatus` and
  exposes it; tests in `src/main/java/de/dadecker/inubit/mcp/mcp/tools/GetHealthTool.java`,
  `src/main/resources/schemas/get_health.output.json`,
  `src/main/java/de/dadecker/inubit/mcp/Wiring.java`, existing GetHealthTool/schema tests
- [ ] T010 [US1] `Launcher`: `Context` gets `Supplier<ReleaseSource> releaseSource` (system:
  `GitHubReleaseSource`); in `serve`, after `factory.start(...)`, start `UpdateCheck` on a daemon
  single-thread scheduler; stop it on shutdown; never in `--version`/`--check-config` (FR-013);
  tests (fake source: newer version visible via `get_health` and WARN; hanging source does not
  delay `initialize`) in `src/main/java/de/dadecker/inubit/mcp/Launcher.java`,
  `src/test/java/de/dadecker/inubit/mcp/MainTest.java`; `MainProcessTest` children get
  `INUBIT_MCP_UPDATE_CHECK=off`

## Phase 5: User Story 2 – Daily re-check (P2)

**Goal**: the check repeats 24 h after the previous one.
**Independent test**: controllable scheduler; second run finds a newer version.

- [ ] T011 [US2] `UpdateCheck.start(ScheduledExecutorService)` with `scheduleWithFixedDelay(…, 0,
  24h)` and `close()`; tests with a recording executor (delay 24 h, second run announces a newer
  version, same version not logged twice) in
  `src/main/java/de/dadecker/inubit/mcp/application/UpdateCheck.java`,
  `src/test/java/de/dadecker/inubit/mcp/application/UpdateCheckTest.java`

## Phase 6: User Story 4 – Turn the check off (P3)

**Goal**: key or environment variable turns it off; no request at all.
**Independent test**: Launcher test with the check off: the fake source is never called.

- [ ] T012 [US4] `UpdateCheckConfig(Optional<Boolean> enabled)` as top-level `updateCheck` of
  `ProfileConfig`; strict YAML boolean in `ConfigLoader` ("Invalid value at updateCheck.enabled in
  <file>: expected true or false"); tests in `src/main/java/de/dadecker/inubit/mcp/config/`
  (`UpdateCheckConfig.java`, `ProfileConfig.java`, `ConfigLoader.java`) and `ConfigLoaderTest`
- [ ] T013 [US4] `UpdateCheckSetting.resolve(ProfileConfig, Map<String,String> env)` (contracts/
  configuration-delta.md: `off|false|0|no` / `on|true|1|yes`, case-insensitive, blank = unset,
  other value → warning without the value); `Launcher` logs INFO "Update check is off (…)" and does
  not start the check; tests in `src/main/java/de/dadecker/inubit/mcp/config/UpdateCheckSetting.java`,
  `src/test/java/de/dadecker/inubit/mcp/config/UpdateCheckSettingTest.java`, `MainTest`

## Phase 7: Polish

- [ ] T014 [P] Docs: README (install: manifest/`--version`, version-free file name allowed; update
  check section), `docs/setup.md` (configuration key, environment variable, proxy options, log
  lines), `docs/tools.md` (`get_health.serverUpdate`), `CHANGELOG.md` `[Unreleased]`; wherever docs tell
  the version by the file name (`docs/setup.md` certificate section, start script) point to
  `--version`/manifest; `docs/release-checks.md` gets a manifest check step
- [ ] T015 Full verification: `mvn -o clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-016-tmp`
  and `python3 -I -m unittest discover -s tools/tests`; quickstart offline steps

## Dependencies

- T001 → T004. T002 → T006, T007, T008. T005, T006 → T007, T008 → T009 → T010 → T011, T013.
  T012 → T013. T014 after T013. T015 last.
- US3 (T003–T004) is independent of US1/US2/US4.

## Parallel opportunities

- T002, T003, T005 touch different files and can run together; T006 with T005.
- T014 (docs) can run alongside T011–T013 once contracts are fixed.

## Implementation strategy

MVP = US3 + US1 (manifest version and start-up check). US2 and US4 are small increments on the same
classes. Commit after each task (`T0NN: <summary>`).
