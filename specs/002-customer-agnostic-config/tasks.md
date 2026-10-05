---

description: "Task list for customer-agnostic configuration (feature 002)"
---

# Tasks: Customer-Agnostic Configuration

**Input**: Design documents from `/specs/002-customer-agnostic-config/`

**Prerequisites**:
- [plan.md](plan.md)
- [spec.md](spec.md)
- [research.md](research.md) (D-1…D-13)
- [data-model.md](data-model.md)
- [contracts/configuration.md](contracts/configuration.md)
- [contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md)
- [quickstart.md](quickstart.md)
- feature 001 artifacts in `specs/001-inubit-mcp-mvp/`

**Tests**: REQUIRED (Constitution III, TDD).
- Every behaviour change starts with a test that is seen failing on an assertion.
- Pure renames (T003, T006) are validated by test parity instead: the suite stays green with the same
  number of tests, except for tests explicitly renamed.

**Organization**: tasks are grouped by user story (US1–US4 from spec.md). They build on the feature 001
code base: 1,019 tests at commit `234214a`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on unfinished tasks)
- **[Story]**: US1 = new customer by configuration, US2 = profiles side by side, US3 = migration, US4 =
  neutral product

## Path Conventions

These hold after T003:

| Short form | Expands to |
|---|---|
| `main/…` | `src/main/java/de/dadecker/inubit/mcp/…` |
| `test/…` | `src/test/java/de/dadecker/inubit/mcp/…` |
| `res/…` | `src/main/resources/…` |
| `fixtures/…` | `src/test/resources/fixtures/v8_1/…` |

## Safety rules (unchanged from 001)

- Never send a state-changing command (`processErrorStart`, `kill`) to a real INUBIT server.
- Never contact TEST, STAGING or PROD.
- Credentials are passed only through the environment or stdin and are never printed.
- Live runs are read-only and only on the user's non-production nodes.
- Changing the user's local configuration (`~/.config/inubit-mcp/…`) or their MCP client registration
  needs explicit user approval.

---

## Phase 1: Setup

- [X] T001 Spike T-S1 (research D-5): find out whether MCP Java SDK 2.0.1's sync server builder offers
  `instructions(String)` and a `serverInfo` title, and whether the `initialize` result carries them.
  - Inspect the SDK API with `javap` or the sources jar from `mvn dependency:sources`.
  - Use a throw-away test under `src/test/java/…/spike/` and delete it afterwards.
  - Record the result as "T-S1" in `specs/002-customer-agnostic-config/research.md`.
- [X] T002 [P] Create the local denylist mechanism for the release check (research D-11), without
  committing any customer identifiers.
  - Document in `docs/release-checks.md` (new): the format is one Java regex per line with `#`
    comments, the location comes from the environment `INUBIT_MCP_DENYLIST` (a path) or the file
    `.denylist` in the repository root.
  - Add `.denylist` and `.neutralize-map` to `.gitignore`.
  - State in `docs/release-checks.md` that running `NoCustomerIdentifiersTest` with the denylist is a
    mandatory check before every release (the normal build skips it).

---

## Phase 2: Foundational (blocking)

**Purpose**: Namespace rename and neutral type, config and contract names. Every story needs these.

### R0: namespace rename (pure refactoring, test parity)

- [X] T003 Move the code to the new namespace (research D-1).
  - `git mv` the main sources from the previous namespace to `src/main/java/de/dadecker/inubit/mcp`.
  - Do the same for `src/test/java/…` and the `build.properties` resource of the previous namespace.
  - Replace the previous namespace with `de.dadecker.inubit.mcp` in all `package` and `import`
    statements and in Javadoc FQNs.
  - Update `pom.xml`: `groupId` `de.dadecker`, `<main.class>de.dadecker.inubit.mcp.Main</main.class>`,
    and the build.properties include and exclude paths.
  - Update the two FQNs in `res/logback.xml`.
  - Update `main/infra/BuildInfo.java`: `RESOURCE = "/de/dadecker/inubit/mcp/build.properties"`.
  - Update `test/architecture/PackageBoundaryTest.java`: `BASE = "de/dadecker/inubit/mcp/"`.
  - Test logger names in the previous namespace become `"de.dadecker…"`. This affects
    `test/infra/ScrubbingJsonEncoderTest.java` and `test/security/NoSecretLeakTest.java`.
  - FQN references in `test/adapter/cli/CliRunnerTest.java` and
    `test/adapter/rest/v81/V81MaintenanceProbeTest.java`.
  - Acceptance:
    - `mvn -q clean verify` is green with exactly 1,019 tests.
    - `git grep` for the previous namespace in `src` and `pom.xml` returns nothing.
    - The shaded jar has 0 entries of the previous namespace.
- [X] T004 Update the namespace mentions in `specs/001-inubit-mcp-mvp/plan.md` and
  `specs/001-inubit-mcp-mvp/tasks.md` (Path Conventions, T003 text, R-2 wording) to `de.dadecker.inubit.mcp`.
  Add a one-line note: "namespace renamed in feature 002".

### Neutral names (group/node) in types, config and contract

- [X] T005 [P] Rename the tests first, so the new names are pinned before the code changes (data-model
  "Renamed entities and fields"; contracts/mcp-tools-delta.md).
  - Test classes and expectations: `ServerIdTest` → `NodeIdTest`, `StageId` → `GroupId`;
    `TargetTest` uses `Target.Group` / `Target.Node`.
  - `ListServersToolTest` → `ListNodesToolTest`, which expects the tool name `list_nodes`, the output
    `groups[].nodes[]` and the `NodeSummary` keys `id, group, node, production, writeEnabled,
    confirmationMode, versionLine, cliAvailable`.
  - The output field `node` replaces `server` in `GetHealthToolTest`, `FindProcessesToolTest`,
    `QueryLogsToolTest`, `ListInventoryToolTest` and `GetInventoryItemToolTest`. `group` replaces
    `stage` in the health reports.
  - In `ProcessControlToolsTest`, the input `node` replaces `server`.
  - `TARGET_UNKNOWN` replaces `ENVIRONMENT_UNKNOWN` in the error-code assertions.
  - Config test YAMLs under `src/test/resources/config/` and inline YAML use `groups:` / `nodes:`.
  - `AuditLogTest` keys become `node` and `group` (the `profile` key follows in T018).
  - Confirm the renamed tests fail against the current code (compile or assertion).
- [X] T006 Implement the renames (research D-3) across `main/**` and `res/schemas/*.json`.
  - Type renames:
    - `StageId` → `GroupId`, `ServerId` → `NodeId`
    - `Target.Stage` / `Target.Server` → `Target.Group` / `Target.Node`
    - `ServerConfig` → `ProfileConfig` (root), `StageConfig` → `GroupConfig` (YAML key `groups`, inner
      `nodes`), `ServerEntryConfig` → `NodeConfig`
    - `EffectiveServerConfig` → `EffectiveNodeConfig`
    - `ServerSummary` → `NodeSummary`, `ServerResult` → `NodeResult`
  - Record components and JSON fields `stage`/`server` → `group`/`node` everywhere: domain records,
    page entries, Preview, ProcessControlResult, `ToolError.node`, and audit JSON `node`/`group`.
  - `ListServersTool` → `ListNodesTool`, tool name `list_nodes`. Rename the schema files to
    `list_nodes.input.json` / `list_nodes.output.json` and their content to `groups` / `nodes` /
    `NodeSummary`.
  - Write-tool input property `server` → `node` (pattern unchanged).
  - `ErrorCode.ENVIRONMENT_UNKNOWN` → `TARGET_UNKNOWN` in the enum and in the 5 output schema enums.
  - `ConfigLoader` key handling: `groups[i].nodes[j]` paths and labels. Inheritance stays node → group
    → defaults → built-in.
  - Tool descriptions and messages may still say "stage"/"server" at this point; T014/T015 template them.
  - Makes T005 pass with all other tests green.
- [X] T007 Update the 001 contract and design documents in place to the new machine names, each with the
  header note "renamed in feature 002":
  - `specs/001-inubit-mcp-mvp/contracts/mcp-tools.md`
  - `specs/001-inubit-mcp-mvp/contracts/configuration.md` (keys `groups`/`nodes`, plus a pointer to the
    002 format v2 contract)
  - `specs/001-inubit-mcp-mvp/data-model.md`
- [X] T008 Write `test/config/ProfileSectionTest.java` first. It covers loading and validation of `profile`,
  `terminology` and `credentials` (contracts/configuration.md "Format", "Terminology rules", "Startup
  validation"):
  - `profile.name` is required and matches `^[a-z0-9][a-z0-9-]{0,31}$`.
  - `profile.description` is optional, "≤ 200 chars, single line".
  - The terminology defaults are `group`/`groups` and `node`/`nodes` (lower case since the US1 review,
    R2). Each value matches
    `^\p{L}[\p{L}\p{N} _-]{0,31}$`. Group and node singular names must differ case-insensitively,
    and so must the two plurals.
  - `credentials.envPrefix` matches `^[A-Z][A-Z0-9_]{0,63}$`; when absent, the default is `INUBIT_` +
    normalize(profile.name).
  - All errors are collected and reported together.
- [X] T009 Implement T008:
  - config records `ProfileSection`, `TerminologyConfig`, `CredentialsConfig` in `main/config/`, with
    `ProfileConfig.profile/terminology/credentials`;
  - the domain value objects `main/domain/model/Terminology.java` and `main/domain/model/ProfileInfo.java`
    (data-model "New entities");
  - `ConfigValidator` rules.

  Update every test config and inline YAML to contain a `profile:` block. Use the neutral test profile
  `acme` everywhere; T038 neutralizes the remaining customer data.

- [X] T010 Update the user documentation in the same change as T006/T009 (Constitution: documentation in
  the same change).
  - `docs/setup.md`: the configuration keys `groups`/`nodes`, the required `profile` block, and the
    `terminology`/`credentials` sections with their defaults; keep the existing examples otherwise.
  - `docs/tools.md`: tool name `list_nodes`, the fields `group`/`node`, the write input `node`, and the
    error code `TARGET_UNKNOWN`.
  - `README.md`: the tool table entry `list_nodes`.

**Checkpoint**:
- The namespace is renamed.
- Neutral names are used throughout code, config and contract.
- The profile, terminology and credentials sections are parsed and validated.
- The full suite is green.

---

## Phase 3: User Story 1 - Serve a new customer by configuration only (Priority: P1) 🎯 MVP

**Goal**: A new customer works through a profile file alone, with its own terminology, a visible profile
and no customer defaults.

**Independent Test**: quickstart §2 (A1–A5) with a fictitious profile using "Umgebung"/"Knoten".

### Tests for User Story 1 (write first, must fail)

- [X] T011 [P] [US1] Write `test/domain/model/TerminologyTest.java` for `Terminology.render(template)`:
  - `{group}`, `{groups}`, `{node}`, `{nodes}` render the configured names exactly (US1 review R1;
    first draft lower-cased them);
  - `{Group}`, `{Groups}`, `{Node}`, `{Nodes}` upper-case only the first code point (sentence start, titles);
  - `TemplateGrammarTest` (review R4) scans all raw templates: no "a"/"an" before a placeholder, no
    inflected placeholder;
  - `{profile}` renders the profile name;
  - an unknown `{x}` raises an error;
  - text without placeholders comes back unchanged.
- [X] T012 [P] [US1] Write `test/mcp/TerminologyRenderingTest.java`. With the production `Wiring` and a
  profile using "Umgebung"/"Umgebungen" and "Knoten"/"Knoten" (description "ACME test"):
  - all 8 tool descriptions start with `[acme: ACME test] `;
  - every schema `description` string is rendered with no `{…}` placeholder left;
  - no word `stage`, `stages`, `server` or `servers` remains in descriptions and schema descriptions,
    case-insensitive and on word boundaries. Allowed exceptions are "MCP server" and "INUBIT
    server(s)" only where the text means the product. Keep a small, reviewed allowlist in the test.
  - Also test a profile without a description: the prefix is `[acme] `.
- [X] T013 [P] [US1] Extend `test/mcp/tools/ListNodesToolTest.java`. The result contains
  `profile {name, description}` and `terminology {group{singular,plural}, node{singular,plural}}`.
  The credential prefix is never included.
- [X] T014 [P] [US1] Write `test/application/InventoryOwnerTest.java`:
  - a node without an effective `inventory.owner` → per-node `NOT_CONFIGURED` with nextStep
    "set inventory.owner for <group>/<node> or in defaults";
  - another node of the same group with an owner works;
  - `get_health` is unaffected.
  - In `ConfigValidatorTest`, add a warning when no owner is set anywhere and a CLI is configured.
- [X] T015 [P] [US1] Write `test/mcp/ServerIdentityTest.java`. `initialize` returns a `serverInfo` whose
  `title` is "INUBIT MCP – <profile>" (fallback per T001: name `inubit-mcp-server-<profile>`). If T001
  found instructions support, the instructions name the profile, its description and the terminology.

### Implementation for User Story 1

- [X] T016 [US1] Implement `main/domain/model/Terminology.java#render` and placeholder validation to make
  T011 pass.
- [X] T017 [US1] Turn the tool `DESCRIPTION` constants in `main/mcp/tools/*` and every `description`
  string in `res/schemas/*.json` into neutral templates using `{group}`/`{groups}`/`{node}`/`{nodes}`
  (contracts/mcp-tools-delta.md "Descriptions and terminology").
  - `McpServerFactory.specification()` renders the templates through `Terminology` and prepends the
    profile prefix.
  - `ToolHandler` keeps returning the template.
  - `McpServerFactory` gets `ProfileInfo` from `Launcher`/`Wiring`.
  - Set the `serverInfo` title (or the fallback name) and add `instructions` if T001 is positive.
  - Makes T012 and T015 pass.
- [X] T018 [US1] Route the human-readable messages through `Terminology` so that no hard-coded
  "stage"/"server" display word remains in messages, wherever the text names a level.
  - Affected classes:
    - `TargetResolver`, `WriteGuard`, `ConfigValidator`, `CredentialResolver`, `CredentialGuard`
    - `AdapterGatewayFactory`, `ConfigSummary`
    - `HealthService`, `DiagnosisService`, `InventoryService`, `ProcessControlService`
    - the `domain/model` validation texts
  - Inject the terms through `Wiring`.
  - "Restart Claude Code" becomes "restart the MCP client".
  - Add a test that runs a profile with custom terms through the error paths (unknown target, stage id
    for a write action, missing credentials, production lock) and asserts the custom words in
    `message`/`nextStep`.
- [X] T019 [US1] Make the inventory owner configuration-only (research D-10).
  - Remove `Defaults.BUILTIN_INVENTORY_OWNER`. `EffectiveNodeConfig.inventory().owner()` becomes
    `Optional`.
  - Add `ErrorCode.NOT_CONFIGURED` to `main/domain/model/ErrorCode.java` and to the ToolError enum in all
    output schemas.
  - `InventoryService` returns `NOT_CONFIGURED` per node; the validator warning comes from T014.
  - Makes T014 pass.
- [X] T020 [US1] `ListNodesTool` returns `profile` and `terminology` (contracts/mcp-tools-delta.md).
  Update `list_nodes.output.json`. Makes T013 pass.
- [X] T021 [US1] Update the docs:
  - `docs/setup.md`:
    - a neutral example profile (`acme`, `*.example.test` hosts, `/opt/inubit/client`) with terminology,
      `envPrefix`, inventory owner, and TLS trust store plus pin as a generic option;
    - the multi-profile setup: one file and one registration per profile, `--profile`;
    - an `--check-config` example.
  - `docs/tools.md`: the new names (`list_nodes`, `group`/`node`, `TARGET_UNKNOWN`, `NOT_CONFIGURED`),
    a terminology and profile prefix note, and neutral examples.

**Checkpoint**: Quickstart A1–A5 pass with a fictitious profile.

---

## Phase 4: User Story 2 - Run several customer profiles side by side (Priority: P1)

**Goal**: Profiles never share credentials, codes, audit or temporary data.

**Independent Test**: quickstart §3 (B1–B4) with profiles `acme` and `globex`, both with `test/node1`,
fakes only.

### Tests for User Story 2 (write first, must fail)

- [X] T022 [P] [US2] Extend `test/config/CredentialResolverTest.java` for the prefix rules (research D-6):
  - the default prefix `INUBIT_ACME` gives `INUBIT_ACME_TEST_NODE1_PASSWORD`, then
    `INUBIT_ACME_TEST_PASSWORD`;
  - an explicit `envPrefix: INUBIT` gives the 001 names;
  - collision detection and the unmatched-variable warning work on the effective prefix;
  - a variable of another prefix is never used;
  - messages name the effective variable names.
- [X] T023 [P] [US2] Extend `test/infra/AuditLogTest.java`:
  - the record field order is `auditId, timestamp, profile, node, group, capability, step, inputs,
    account, outcome, reason, mcpClient`;
  - the default directory is `~/.inubit-mcp/<profile>/audit`, resolved from an injected home.
- [X] T024 [P] [US2] Extend `test/adapter/cli/CliResourcesTest.java` (research D-8):
  - export directory names follow `inubit-mcp-export-<profile>-<pid>-<random>`;
  - `sweepStale` removes only its own profile's directories with a dead pid, plus legacy
    `inubit-mcp-export-<pid>-…` directories with a dead pid;
  - it never removes another profile's directories, even with a dead pid;
  - owner and symlink rules are unchanged.
- [X] T025 [P] [US2] Extend `test/config/ConfigLoaderTest.java` and `test/MainTest.java` for profile
  selection (research D-9):
  - `--profile acme` resolves `~/.config/inubit-mcp/acme.yaml` (injected home; `%APPDATA%` on Windows);
  - `INUBIT_MCP_PROFILE` works the same way;
  - the order is `--config`, `--profile`, `INUBIT_MCP_CONFIG`, `INUBIT_MCP_PROFILE`, default;
  - `--config` together with `--profile` gives exit 2;
  - a `profile.name` mismatch is an error.
- [X] T026 [P] [US2] Write `test/isolation/TwoProfilesTest.java`. It runs two production `Wiring`s in one
  JVM: profiles `acme` and `globex`, both with `test/node1`, different injected env maps, write enabled,
  a fake StartCLI and HTTPS WireMock.
  - Each profile resolves only its own credentials.
  - A confirmation code issued by `acme` is refused by `globex` (`CONFIRMATION_INVALID`).
  - Audit files land in separate directories and carry the right `profile`.
  - Export directories carry the right profile.
  - `list_nodes` shows each profile.

### Implementation for User Story 2

- [X] T027 [US2] Implement the credential prefix in `main/config/CredentialResolver.java` and
  `main/adapter/CredentialGuard.java` (effective prefix from `ProfileInfo`). Also cover the message
  texts in `ConfigValidator`, `CliOutputClassifier` and `SslContexts`. Makes T022 pass.
- [X] T028 [US2] Implement the per-profile audit directory default (`ConfigLoader`) and the `profile`
  field in `main/infra/AuditLog.java` and `main/domain/model/AuditRecord.java`. Makes T023 pass.
- [X] T029 [US2] Implement profile-scoped export names and the sweep in `main/adapter/cli/CliResources.java`,
  `main/adapter/cli/CliExportRunner.java` and `main/config/CliPaths.java`. `Launcher` passes the profile
  to the sweep. Makes T024 pass.
- [X] T030 [US2] Implement `--profile` and `INUBIT_MCP_PROFILE` in `main/config/ConfigLoader.java` and
  `main/Launcher.java`, including the usage text. Makes T025 pass.
- [X] T031 [US2] Make T026 pass (wiring adjustments as needed). Add the best-effort warnings for another
  profile file in the default configuration directory with the same audit directory, the same effective
  credential prefix, or the same profile name (contracts/configuration.md; spec edge cases), each with a
  test in `ConfigValidatorTest` written first.
- [X] T032 [US2] Document running profiles side by side in `docs/setup.md`: the prefix default and
  override, the audit locations, the temporary data and its cleanup, and one MCP registration per
  profile.

**Checkpoint**: Quickstart B1–B4 pass.

---

## Phase 5: User Story 3 - Migrate the existing configuration (Priority: P2)

**Goal**: 001-format files are rejected with exact guidance, and the guide leads to an identical setup.

**Independent Test**: quickstart §4 (C1–C2) with a neutral 001-format test file.

- [X] T033 [P] [US3] Write `test/config/Format001DetectorTest.java`. A file with top-level `stages`,
  nested `servers`, and/or a missing `profile` is refused (exit 1). The message:
  - lists every obsolete key with its replacement (`stages`→`groups`, `servers`→`nodes`, add `profile`);
  - gives the prefix hint `credentials.envPrefix: INUBIT`;
  - names the old audit directory `~/.inubit-mcp/audit`;
  - points to `docs/migration-001-to-002.md`.

  Mixed old and new keys are refused, with each old key named.
- [X] T034 [US3] Implement `main/config/Format001Detector.java`, invoked by `ConfigLoader` before binding.
  Makes T033 pass.
  - Done: runs after the top-level `x-` keys are removed (a `servers` key inside one is ignored); a
    file without a `profile` key is refused here as well, so `ProfileSectionTest` now checks the
    validator's "profile.name is required" with an empty `profile:` block. The exit code 1 of the
    server and of `--check-config` is tested in `MainTest`.
- [X] T035 [US3] Write `docs/migration-001-to-002.md`:
  - a step-by-step guide with a before/after example (neutral names);
  - the key mapping, profile and terminology (Stage/Server to keep the old wording), `envPrefix: INUBIT`;
  - the per-profile audit location and where old records stay;
  - `--profile` registration;
  - the tool contract changes for prompts and scripts (`list_nodes`, `group`/`node`, `TARGET_UNKNOWN`).
- [X] T036 [US3] Write `test/config/MigrationGuideExampleTest.java`. It loads the guide's "after" example
  (kept in sync as `src/test/resources/config/migration-after.yaml`) together with an env map using the
  001 variable names, and asserts that the same nodes and variable names resolve.
  - Done: also checks that the guide contains `migration-before.yaml`, `migration-after.yaml`, the
    refusal message of the "before" file and the `--check-config` summary of the "after" file (rendered
    with home `/Users/jdoe`) verbatim. `.gitattributes` keeps these files at LF line endings.

**Checkpoint**: Quickstart C1–C2 pass.

---

## Phase 6: User Story 4 - Ship a neutral product (Priority: P2)

**Goal**: No customer identifiers in current repository files; a release guard exists.

**Independent Test**: quickstart §5 with the user's local denylist, plus a full suite run.

- [X] T037 [US4] Write `test/security/NoCustomerIdentifiersTest.java` (research D-11).
  - It reads the denylist from `INUBIT_MCP_DENYLIST` or `.denylist`, and is skipped without one.
  - It scans all git-tracked text files (via `git ls-files`, or by walking the tree while skipping
    `target/`, `.git/` and binaries) and the text entries of fixture ZIPs.
  - It reports every match as file:line, without printing the matched text beyond the pattern id.
  - Test the scanner itself with a synthetic denylist and temporary files.
- [X] T038 [US4] Neutralize the test code and test configs with the mapping categories from research D-11.
  - Write `tools/neutralize.py`: it contains only the mechanism. It reads the concrete
    customer→neutral mapping from the local, untracked file named by `INUBIT_MCP_NEUTRALIZE_MAP` (or
    `.neutralize-map`), is idempotent, and prints only counts and file names, never the mapped values.
    Give it a `--self-test` with fictitious values.
  - The coordinator provides the local mapping file (outside git).
  - Apply it to `src/test/**`.
  - Re-run the suite; the test count is unchanged.
- [X] T039 [US4] Neutralize the fixtures with the same mapping: `fixtures/**` including the text entries
  of the fixture ZIPs, which must be re-packed. Rename fixture files whose names contain a customer value
  (e.g. the model list fixture → `rest/model_models_owner.*`) and update the references. Re-run the suite.
  - Review N1: all INUBIT object names in fixtures, test code and docs are fully synthetic
    (`GRP-01`…, `Workflow-0001`…, `Module-0001`…, `Service-01`…, `TAG-01`…), collected by
    `tools/synthesize_names.py` into the local `.synthetic-map` and applied with
    `tools/neutralize.py --map` (research D-11).
- [X] T040 [US4] Remove customer defaults from `tools/record-fixtures.sh` and `tools/anonymize.py`.
  - The sample objects, owner and keep-names become required arguments or environment variables with
    no customer values.
  - Remove the customer-specific wording in comments and help texts.
  - Keep `--self-test` green with fictitious samples.
- [X] T041 [US4] Neutralize the docs and the 001 specifications with the same mapping, and the real host
  names → `inubit-<stage>-<n>.example.test`. Covers:
  - `docs/*.md`, `README.md`;
  - `specs/001-inubit-mcp-mvp/**` (spec, plan, research, data-model, contracts, quickstart, tasks,
    HANDOFF, checklists);
  - `specs/002-customer-agnostic-config/**`: replace the previous code namespace in prose by "the
    previous namespace", and remove any remaining customer value.

  Add a note in specs/001 spec/HANDOFF: "Customer identifiers neutralized in feature 002; git history
  unchanged." Neutralize `docs/release-checks.md` examples too.
- [X] T042 [US4] Run `NoCustomerIdentifiersTest` with the user's local denylist. The coordinator asks the
  user for it, or uses a list derived in the session and kept outside the repository. Fix all findings.
  Then `git grep` for the old namespace returns 0 hits.

**Checkpoint**: Quickstart §5 passes; the full suite is green.

---

## Phase 7: Polish & Validation

- [X] T043 [P] Live tests: rename `INUBIT_LIVE_SERVER` to `INUBIT_LIVE_NODE` (the old name is rejected
  with a hint). Write `test/live/LiveTargetTest.java` first (plain unit test, not tagged live): the old
  variable is refused with the hint, the new one is parsed. Resolve the config through the D-9 order. Update `docs/live-tests.md`. Covers
  `test/live/LiveTarget.java`, `InventoryLiveTest.java` and the other live tests.
- [X] T044 [P] Rewrite the README for the customer-neutral product: purpose, profiles, safety model, the
  links, and the status of features 001 and 002.
- [X] T045 Run the full quickstart validation for 002 (§1–§5), using fakes only and fictitious profiles.
  Measure SC-001: follow `docs/setup.md` for a new fictitious profile in a clean temporary home up to a
  first successful `get_health` against a fake server, and record the time.
  Record the results in a "Validation log" section of `specs/002-customer-agnostic-config/quickstart.md`.
- [X] T046 With explicit user approval, migrate the user's local configuration (outside the repository):
  1. Copy `~/.config/inubit-mcp/config.yaml` to `~/.config/inubit-mcp/<profile>.yaml` in format v2.
     The user chooses the profile name. Use `credentials.envPrefix: INUBIT` and terminology
     Stage/Server.
  2. Run `--check-config --profile <name>`.
  3. Run one read-only live run (§6) on the user's non-production nodes.
  4. Offer the updated MCP registration command. Do not run it without approval.
  Status 2026-10-04: steps 1–2 done (`Result: OK`); step 3 first blocked by the network, passed on 2026-10-05
  (3 live tests green on a non-production node); step 4 run by the user on 2026-10-05
  (registration connected).
- [X] T047 Update `specs/001-inubit-mcp-mvp/HANDOFF.md` (or a new `specs/002-…/HANDOFF.md`) with the final
  state. The open 001 user tasks (001-T010, 001-T115, 001-T125) carry over to the migrated profile.

---

## Dependencies & Execution Order

| Phase | Depends on |
|---|---|
| Setup (T001–T002) | nothing |
| Foundational: T003 → T004 | T003 must be first and on its own |
| Foundational: T005 → T006 → T007 | T003 |
| Foundational: T008 → T009 | T006 |
| US1 (Phase 3) | Phase 2 |
| US2 (Phase 4) | Phase 2; independent of US1 except for `ProfileInfo` (T009) |
| US3 (Phase 5) | Phase 2 |
| US4 (Phase 6) | T003; best done after US1–US3, so neutralization covers the final test code. T038/T039 must run after any phase that adds test data. |
| Polish | all |

```text
Setup ─► T003 (rename) ─► T005/T006 (neutral names) ─► T008/T009 (profile)
                                                         ├─► US1 (terminology, owner, list_nodes)
                                                         ├─► US2 (prefix, audit, temp, --profile)
                                                         └─► US3 (migration)
                                                                  └─► US4 (neutralize + guard) ─► Polish
```

---

## Parallel Examples

- **Phase 3 tests**: T011, T012, T013, T014, T015 (different files).
- **Phase 4 tests**: T022, T023, T024, T025, T026.
- **US1 and US2 implementation** can proceed in parallel after Phase 2 by different agents. They overlap
  in `ConfigValidator`/`Wiring`, so run them sequentially when one creator does both.

---

## Implementation Strategy

1. **R0 first**: T003 alone, reviewed and committed. It is the biggest diff and has a trivial review
   criterion: same tests, no previous namespace.
2. **Neutral names and profile** (Phase 2), committed.
3. **US1 + US2** (MVP of this feature): a new customer works, and profiles are isolated.
4. **US3** migration, then **US4** neutralization and guard.
5. **Polish**, including the user-approved migration of their own profile.

Each phase goes through Creator → Reviewer → fixes → commit, as in feature 001.

---

## Notes

- Never rewrite git history.
- Customer identifiers must not appear in any new file. The denylist stays local.
- Keep the 001 task IDs referenced in specs/001 unchanged; only text content is updated.
