---

description: "Task list for the artifact workspace (feature 003)"
---

# Tasks: Artifact Workspace

**Input**: Design documents from `/specs/003-artifact-workspace/`

**Prerequisites**:
- [plan.md](plan.md)
- [spec.md](spec.md) (US1–US6, clarifications of 2026-10-06)
- [research.md](research.md) (D-1…D-14)
- [data-model.md](data-model.md)
- [contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md)
- [contracts/configuration-delta.md](contracts/configuration-delta.md)
- [quickstart.md](quickstart.md)
- spike findings: `docs/research/spike-development-deployment.md`

**Tests**: REQUIRED (Constitution III, TDD). Every task that adds behaviour starts with its test,
which must be seen failing on an assertion (not on a compile error alone) before the production code
is written. Each task ends with `mvn -q verify` green.

**Organization**: grouped by user story. US2 (secrets) is implemented **before** US1 (export): both
are P1, and no export may write a file before redaction exists.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on unfinished tasks)
- **[Story]**: US1 export, US2 secrets, US3 structure checks, US4 XSLT, US5 XML/XSD, US6 workspace
  configuration

## Path Conventions

| Short form | Expands to |
|---|---|
| `main/…` | `src/main/java/de/dadecker/inubit/mcp/…` |
| `test/…` | `src/test/java/de/dadecker/inubit/mcp/…` |
| `res/…` | `src/main/resources/…` |
| `fixtures/…` | `src/test/resources/fixtures/v8_1/…` |

## Safety rules

- No write command of any kind (`import`, `tag`, `delete`, `rename`, `processErrorStart`, `kill`) to
  any INUBIT server. Live runs only `export` on a development node, opt-in.
- No real secret, ciphertext, host, user, owner, workflow or module name of a customer in any committed
  file; the pre-commit identifier check must pass. Raw recordings stay in
  `~/.inubit-mcp/<profile>/spike` and are never copied into the repository unneutralized.
- The workspace git repository never gets a remote; no test may run `git push`, `fetch` or `remote`.
- Changing the user's local configuration or MCP registration needs explicit approval.

---

## Phase 1: Setup

- [X] T001 Add `net.sf.saxon:Saxon-HE` pinned to `10.9` (`<saxon.version>10.9</saxon.version>` with a
  comment "INUBIT 8.1 runs Saxon 10, research D-11") to `pom.xml`, exclude nothing, check that the
  shaded JAR still builds and that `mvn -q dependency:tree` shows no version conflict; add the Saxon-HE
  row (MPL-2.0) to `THIRD-PARTY-NOTICES.md`; add an ignore rule for `net.sf.saxon:*` minor and major
  updates to `.github/dependabot.yml` (the version must stay on INUBIT's Saxon 10 line).
- [X] T002 Record the fixtures (research D-14) from the local spike recordings: a small diagram group
  with XSLT Converter, Demultiplexer (with condition and `DefaultOutput`) and Assign modules; a
  diagram group with Web Services and AS2 connector modules and at least one embedded WSDL and one
  `InternalDocument`; a module-only export. Neutralize names with `tools/neutralize.py`, replace every
  secret value by a synthetic value of the same shape (`AES-` + base64, `AESG` + base64, legacy
  base64, plain text, keystore base64, `isPassword` literal, `is:password` default,
  `xslt.sourceVariables` values) and every `CheckinComment` user/host by fixture values; store them as
  `fixtures/artifacts/{grp-a.zip,grp-b.zip,module-one.zip}` plus `fixtures/artifacts/README.md`
  listing every synthetic secret value (the tests read that list) and the edits made. Also record,
  read-only, the StartCLI output (stdout, stderr, exit code) of an export of a non-existent diagram
  group and of a non-existent module, neutralized, as `fixtures/artifacts/cli/export-group-missing.txt`
  and `fixtures/artifacts/cli/export-module-missing.txt` (contract tests, Constitution III). Run
  `python3 tools/check-identifiers.py --staged` before committing.
- [X] T003 [P] Derive defect fixtures from `grp-a.zip` as unzipped directories under
  `fixtures/artifacts/defects/` — `dangling-edge`, `id-collision`, `demux-key-unmatched`,
  `missing-module`, `repository-ref-missing`, `variable-unresolved` — each with a one-line
  `DEFECT.md` naming the edit; and XSLT fixtures under `fixtures/artifacts/xslt/`: `plain.xsl`,
  `standins.xsl` (calls `Misc:guid()`, `Formatter:changeDateFormat(…)`, `Formatter:getDateTime(…)`,
  `UUID:randomUUID()`, `Thread:sleep(…)`), `unknown-extension.xsl`, `syntax-error.xsl`,
  `repository-import.xsl` (imports `inubitrepository:/Root/OWNERS/xsl/common.xsl`), `input.xml`,
  `schema.xsd`, `valid.xml`, `invalid.xml`, `not-well-formed.xml`.
- [ ] T004 Create the packages `main/adapter/archive/v81/`, `main/adapter/git/`,
  `main/adapter/xslt/` with `package-info.java`, and extend `test/architecture/PackageBoundaryTest.java`
  first (failing): `adapter.archive`, `adapter.git`, `adapter.xslt` may not depend on `mcp` or
  `application`; `application` reaches them only through `domain.port`; `net.sf.saxon` is used only in
  `adapter.xslt`.

---

## Phase 2: Foundational (blocking for all stories)

- [ ] T005 [P] Domain types in `main/domain/model/`: `ArtifactRef` (group, owner, kind
  `WORKFLOW|MODULE|REPOSITORY_FILE`, name, optional diagramGroup, optional pluginType; identity is the
  name within owner and kind — "UIDs are never part of it"), `PathChange` (path, kind
  `ADDED|MODIFIED|DELETED`), `HistoryEntry` (commit, message, changes), `CheckFinding` (severity
  `ERROR|WARNING|INFO`, check `STRUCTURE|XSLT|XML|XSD`, path, optional location, code, message),
  `CheckReport`, `XsltRun` (outcome `OK|ERROR|NOT_TESTABLE`, standInsUsed), `SecretPlaceholder`
  (`${secret:<propertyPath>}`, "no value-derived data"); tests in `test/domain/model/` for invariants
  (null checks, immutable copies, placeholder rendering and parsing).
- [ ] T006 [P] Ports in `main/domain/port/`: `ArtifactPort` (`byte[] exportWorkflowGroup(String owner,
  String diagramGroup)`, `byte[] exportModule(String owner, String pluginType, String name)`) with
  `Gateway.artifacts()` (default throws `CLI_UNAVAILABLE` in existing test gateways); `VersionHistoryPort`
  (`init`, `status`, `commitAll(String message) → Optional<HistoryEntry>`, `restore(Path subtree)`);
  `XsltPort` (`run(XsltRequest) → XsltRun`, `validate(Path xml, Optional<Path> xsd) →
  List<CheckFinding>`).
- [ ] T007 [P] `main/adapter/archive/v81/NameCodec.java` with `test/adapter/archive/v81/NameCodecTest.java`
  first: percent-encode every character outside `[A-Za-z0-9 ._()+,=@-]`, a leading `.` and trailing
  spaces/dots; `decode(encode(x)) == x` for property-generated names; `encode` never yields `.`, `..`,
  empty or a segment containing `/`; plus `WorkspacePath` (data-model) building
  `<group>/<owner>/workflows/<diagram group>/<workflow>.xml`,
  `<group>/<owner>/modules/<plugin type>/<module>/{module.xml,index.xml,<property>.<ext>}`,
  `<group>/<owner>/repository/<path>` and the mirrored `.meta/…json` path; `parse(Path)` inverts it.
- [ ] T008 [P] `main/adapter/archive/v81/XmlNormalizer.java` with test first (D-5): UTF-8, LF,
  two-space indentation for element-only content, text and mixed content byte-exact, attributes
  sorted by name, `<x/>` for empty elements, fixed XML declaration; normalizing twice is idempotent;
  canonical equality helper `XmlEquality.equal(a, b)` (D-4: comments kept, whitespace-only text
  between elements ignored, all other text exact) used by later round-trip tests.
- [ ] T009 [P] `main/adapter/git/GitCli.java` (implements `VersionHistoryPort`) with
  `test/adapter/git/GitCliTest.java` first, using real `git` in a `@TempDir`: argument arrays only;
  every call carries `-c core.hooksPath=<empty dir> -c commit.gpgsign=false -c core.autocrlf=false
  -c core.quotepath=false -c user.name=INUBIT MCP (<profile>) -c user.email=inubit-mcp@localhost`,
  environment `GIT_TERMINAL_PROMPT=0`, `GIT_CONFIG_NOSYSTEM=1`; timeout 30 s; `init` writes
  `.gitignore` with `.tests/`, `.reports/`, `.lock`; `commitAll` returns empty when nothing changed;
  a planted `pre-commit` hook in `.git/hooks` does not run; there is no method for remote, push, fetch,
  clone (assert by reflection); a missing `git` binary → `ToolErrorException` `PRECONDITION_FAILED`
  "git not found".
- [ ] T010 `main/application/WorkspaceLock.java` with test first: `FileChannel.tryLock` on
  `<root>/.lock`; second acquisition in the same JVM and from a second process (spawn
  `java -cp … LockHolder`) fails with `PRECONDITION_FAILED` "another export or check is running";
  released on close, also after an exception.
- [ ] T011 Workspace configuration (FR-001, FR-002) in `main/config/ProfileConfig.java`,
  `ConfigLoader.java`, `ConfigValidator.java` with tests first in `test/config/ConfigLoaderTest.java`
  and `ConfigValidatorTest.java`: optional top-level `workspace`, default
  `~/.inubit-mcp/<profile.name>/workspace`, `~` expanded, "must be absolute after expansion",
  created `rwx------` (created parents `rwx------`) at startup, "must be readable and writable" else
  configuration error naming the path.
- [ ] T012 `main/adapter/archive/v81/ArchiveReader.java` with test first on `fixtures/artifacts/*.zip`:
  parses an export ZIP into `ExportArchive` (data-model: properties, workflow groups with `WorkflowXml`
  incl. context, module index entries, module files, repository files from the nested
  `Repository.zip`), bounded to 128 MiB total and per entry, rejects ZIP entries with `..` or absolute
  names (`UNEXPECTED_RESPONSE`), accepts the empty `workflow/` directory entry of module-only exports.

**Checkpoint**: foundation ready; nothing is written to a workspace yet.

---

## Phase 3: User Story 2 — Secrets never leave INUBIT (Priority: P1)

**Goal**: every secret is replaced in memory before anything can be written.

**Independent test**: redact the parsed fixtures and search the result for every synthetic secret value
from `fixtures/artifacts/README.md`.

- [ ] T013 [US2] `test/adapter/archive/v81/SecretRedactorTest.java` first, then
  `main/adapter/archive/v81/SecretRedactor.java` (D-7): replaces "every `Property` with
  `type=\"Password\"`, any value, with or without `encrypted`" (module files and workflow instance
  properties), `type="KeyStore"` values, certificate properties only if they contain a private key,
  `literal isPassword="true"`, `DefaultValue` of `is:password` variables, all values under
  `xslt.sourceVariables`; placeholder `${secret:<property path>}` (nested map properties as
  `a/b`), empty values stay empty; returns a `RedactionReport` with counts per kind only; after
  redaction none of the README's synthetic values occurs in any serialized part of the model.
- [ ] T014 [US2] Test that redaction cannot be bypassed: `ArchiveReader` output is only reachable by
  the writer through `SecretRedactor.redact(...)` (the writer accepts a `RedactedArchive` type that only
  the redactor can construct) — compile-time guarantee plus a test in
  `test/adapter/archive/v81/RedactionGateTest.java`.

**Checkpoint**: US2 core done; the end-to-end leak test runs in T024 once files are written.

---

## Phase 4: User Story 1 — Export a diagram group as readable files (Priority: P1) 🎯 MVP

**Goal**: `export_artifacts` writes normalized, lossless workspace files and records them in git.

**Independent test**: export recorded fixtures through `FakeProcessLauncher`; check file tree, history,
unchanged re-export, round trip.

- [ ] T015 [P] [US1] `main/adapter/archive/v81/EmbeddedDocuments.java` with test first: extracts
  `XmlDocument` properties (entity-escaped XML) and `InternalDocument` properties (gzip+base64,
  "decoded by content, not by the `xslt.base64Zipped` flag") to files with extensions
  `xslt.stylesheet → .xsl`, `WsdlData`/`ValidWsdlData → .wsdl`, InternalDocument by
  `documentContentType`/`documentName` (`.xsd`, `.xml`, else `.bin`), other XmlDocument → `.xml`; the
  property text becomes `@file:<file name>`; re-embedding escapes `&`, `<`, and `>` only after `]]`,
  and re-encodes InternalDocuments; decode(encode(x)) == x on all fixture modules.
- [ ] T016 [P] [US1] `main/adapter/archive/v81/MetaStore.java` with test first (D-6): writes and reads
  `.meta/<same path>.json` (sorted keys) holding enclosing XML context, `WorkflowUId`/`ModuleUId`, the
  `CheckinComment` export suffix ("from the first `@@@Deploying User:` to the end"), archive
  properties and repository metadata; `CheckoutUser`, `IsActive`, layout, `LastUpdate`, `ExportUser`
  stay in the reviewed files (FR-014).
- [ ] T017 [US1] `main/adapter/archive/v81/WorkspaceWriter.java` with test first: turns a
  `RedactedArchive` into a set of files under `<group>/<owner>/` (one file per workflow with root
  `Workflow`, `module.xml` + `index.xml` + embedded files per module, referenced repository files only)
  plus meta records, all through `XmlNormalizer`; case-insensitive collision of two target paths →
  `INVALID_INPUT` naming both names; writing the same archive twice yields identical bytes.
- [ ] T018 [US1] `main/adapter/archive/v81/ArchiveAssembler.java` and
  `test/adapter/archive/v81/ArchiveRoundTripTest.java` first: rebuild a ZIP from workspace + `.meta/`
  (with placeholders still in place); for every fixture, `XmlEquality` holds between the original
  (redacted the same way) and the rebuilt archive entry by entry, `Repository.zip` content equal after
  decoding (FR-015, SC-002).
- [ ] T019 [US1] `main/adapter/cli/CliExportRunner.java`: add `exportWorkflowGroup(owner, diagramGroup)`
  and `exportModule(owner, pluginType, name)` (D-8) with tests first in
  `test/adapter/cli/CliExportRunnerTest.java`: exact `--execCommand` strings with
  `--exportWorkflowType 'technical'` always; blank or empty diagram group rejected before launch
  (`INVALID_INPUT`, "StartCLI treats an empty group as all"); values outside `CliCommand.VALUE` →
  `INVALID_INPUT`; 128 MiB cap; temporary directory deleted on success, failure and timeout;
  `TIMEOUT` names `cliExportTimeout`; `NOT_FOUND` when StartCLI reports a missing group/module,
  classified from the recordings `fixtures/artifacts/cli/export-*-missing.txt` of T002.
- [ ] T020 [US1] `main/adapter/cli/v81/V81ArtifactAdapter.java` implementing `ArtifactPort`, wired in the
  v8.1 gateway factory, with `test/adapter/cli/V81ArtifactAdapterTest.java` first.
- [ ] T021 [US1] `main/application/WorkspaceService.java` with `test/application/WorkspaceExportTest.java`
  first (D-9): lock (a second export or check is refused at once, FR-020) → commit uncommitted changes as `local changes: <n> files` → export(s) per diagram
  group or module (plugin type looked up via `InventoryPort.listModules` if absent) → read → redact →
  write affected sub-trees (deleting artifacts no longer exported, FR-017) → commit
  `export <group>/<node>: <what> (<n> files)` → unlock; second identical export → no commit,
  `unchanged`; a changed stylesheet in a second recording → exactly one `MODIFIED` path; a failing
  export or unreadable archive leaves files and history unchanged (FR-018); a failure while writing
  restores the sub-trees from `HEAD`; warnings for `CheckoutUser` and for used modules of another owner;
  performance (SC-006): a synthetic archive with one diagram group, 20 workflows and 100 modules (built
  from fixture modules by renaming) is read, redacted, written and committed in under 60 s with the
  fake StartCLI returning at once.
- [ ] T022 [US1] `test/application/WorkspaceLocalChangesTest.java` first, then the remaining
  `WorkspaceService` behaviour (clarification 1, FR-019): an edited and an added file are committed as
  `local changes` before the export commit; both commits in the result; nothing is discarded.
- [ ] T023 [US1] Extend `main/mcp/ToolHints.java` test-first with
  `readOnly(String title, boolean openWorld, boolean idempotent)` (the existing two-argument factory
  keeps `idempotent: true`); then `res/schemas/export_artifacts.input.json` and `.output.json` per
  `contracts/mcp-tools-delta.md`, `main/mcp/tools/ExportArtifactsTool.java` and registration in
  `main/Wiring.java` (only if a node has a CLI home) with tests first in `test/mcp/tools/`:
  description with profile prefix and terminology stating "only technical workflows",
  `ToolHints.readOnly(title, true, false)` (not idempotent: it writes a history entry), exactly one of
  `diagramGroups`/`modules`, bounded `changes` with `.reports/export-<commit>.txt` when truncated
  (D-10), `TARGET_UNKNOWN` for unknown ids, first node of a group used and named.
- [ ] T024 [US1] `test/security/SecretLeakTest.java` (SC-003): export all fixtures through the tool,
  then search the workspace files, `.meta/`, every git object (`git cat-file --batch-all-objects`), the
  tool results and the captured log output for every synthetic secret value of
  `fixtures/artifacts/README.md` — zero occurrences; the private temporary export directory no longer
  exists.

**Checkpoint**: MVP — export works end to end offline.

---

## Phase 5: User Story 3 — Check the structure of an edited workflow (Priority: P2)

**Goal**: `check_artifacts` finds the defects INUBIT accepts silently.

**Independent test**: one finding per defect fixture, none on unchanged fixtures.

- [ ] T025 [US3] `test/application/ArtifactCheckServiceTest.java` first, then
  `main/application/ArtifactCheckService.java` (D-13) for workflow files: `EDGE_TARGET_MISSING`,
  `ID_COLLISION` (ModuleId and ConnectionId together), `DEMUX_KEY_UNMATCHED` (keys
  `<Name>(<id>)@@@…` and `DefaultOutput`), `PARENT_REF_MISSING` (`ParentModule`, `EndLoopId`,
  `scopeChildId`), `REPOSITORY_REF_MISSING`, `VARIABLE_UNRESOLVED` (WARNING); each defect fixture of
  T003 yields exactly its finding; unchanged fixtures yield no ERROR (SC-004).
- [ ] T026 [US3] Module existence (FR-027, FR-028) in `ArtifactCheckService` with tests first: found in
  the workspace (any owner of the same group) → ok; else the server module lists of the artifact's
  owner and of `inventory.owner` via `InventoryPort` (cached) → ok; else `MODULE_MISSING` ERROR;
  server unreachable → `MODULE_UNVERIFIED` WARNING; `verifyOnServer: false` → `MODULE_UNVERIFIED` for
  modules not in the workspace.
- [ ] T027 [US3] `res/schemas/check_artifacts.input.json` and `.output.json`,
  `main/mcp/tools/CheckArtifactsTool.java`, registration in `main/Wiring.java` (always), tests first in
  `test/mcp/tools/`: paths must stay inside the workspace ("no `..`, no absolute paths, no symlinks
  leaving it") else `INVALID_INPUT`; at least one of `paths`/`xslt`; findings bounded with
  `.reports/check-<timestamp>.json` when truncated; every finding message at most 500 characters
  (longer ones cut with `…`, FR-034); holds the workspace lock while running (a concurrent export or
  check is refused, FR-020); never writes outside `.tests/` and `.reports/`; `ToolHints.readOnly(title,
  true, true)`.

---

## Phase 6: User Story 4 — Run a stylesheet locally (Priority: P2)

**Goal**: XSLT runs on Saxon-HE 10 with deterministic stand-ins.

**Independent test**: the XSLT fixtures of T003 and the coverage test over all recorded stylesheets.

- [ ] T028 [US4] Risk test first (D-11): `test/adapter/xslt/IntegratedFunctionNamespaceTest.java` — a
  Saxon-HE 10.9 integrated extension function registered under `java:com.inubit.ibis.xsltext.Misc` is
  called by a stylesheet that declares that namespace, without reflexive binding. If it fails, stop and
  report to the user before continuing (fallback to evaluate: rewrite the namespace URI at compile time
  in an `URIResolver`).
- [ ] T029 [US4] `main/adapter/xslt/InubitStandIns.java` with test first: integrated functions for
  `Formatter` (`changeDateFormat`, `convertDateString`, `trim`, `formatNumber`, `crlf`, `lf`,
  `getDateTime`, `isNumber`, `getDateAsString`, `parseSchemaDateToSQLTimestamp`,
  `calculateDateDifference`), `Misc` (`guid`, `encode`, `decode`, `stringToBranch`,
  `setVariableStorage`, `getVariable`), `ISFunctions` (`serialize`, `deserialize`),
  `java:java.util.UUID#randomUUID`, `java:java.lang.Thread#sleep`, the Xalan form of `Thread#sleep`,
  `java:java.net.URLDecoder#decode`, with the arities found in the spike; deterministic
  (clarification 4): GUIDs `00000000-0000-0000-0000-000000000000`, time `2000-01-01T00:00:00Z` or the
  run's `now`, `sleep` returns immediately; each stand-in documents its assumed behaviour in Javadoc.
- [ ] T030 [US4] `main/adapter/xslt/SaxonXsltRunner.java` (implements `XsltPort.run`) and
  `WorkspaceUriResolver.java` with `test/adapter/xslt/XsltRunnerTest.java` first: `plain.xsl` → `OK`
  and output under `.tests/<group>/<owner>/<module>/<input name>.out`; `standins.xsl` → `OK` with
  `XSLT_STANDINS_USED`, two runs byte-identical, `now` override appears; `unknown-extension.xsl` →
  `NOT_TESTABLE` with reason (never `OK`); `syntax-error.xsl` → `XSLT_STATIC_ERROR` with line/column;
  `repository-import.xsl` resolves from the workspace `repository/`; no network or file access outside
  the workspace.
- [ ] T031 [US4] `test/adapter/xslt/XsltCoverageTest.java`: over all stylesheets of the committed
  fixtures, every one is `OK`, `NOT_TESTABLE` or `XSLT_STATIC_ERROR` as listed in
  `fixtures/artifacts/README.md`, none falsely `OK`. Plus the opt-in
  `test/adapter/xslt/XsltCorpusTest.java` (SC-005, research D-14): runs every `*.xsl` below
  `INUBIT_MCP_XSLT_CORPUS` (repository files from its `repository/` sub-directory), asserts ≥ 95 % `OK`
  and that every other one is `NOT_TESTABLE` or `XSLT_STATIC_ERROR`, prints only counts and the
  stand-ins used (never stylesheet content or names); skipped when the variable is not set. Run it once
  locally against the spike corpus and record the counts in the PR description.
- [ ] T032 [US4] Wire the `xslt` input of `check_artifacts` to `SaxonXsltRunner` in
  `main/mcp/tools/CheckArtifactsTool.java` and `main/application/ArtifactCheckService.java` with a tool
  test first (output path and `xslt` block of the result per contract).

---

## Phase 7: User Story 5 — Validate XML documents (Priority: P3)

- [ ] T033 [US5] `main/adapter/xslt/XsdValidator.java` (implements `XsltPort.validate`) with
  `test/adapter/xslt/XmlValidationTest.java` first (D-12): `not-well-formed.xml` →
  `XML_NOT_WELL_FORMED` with line; `invalid.xml` against `schema.xsd` → `XSD_INVALID` with
  line/column per violation; `valid.xml` → no finding; secure processing on, resolver limited to the
  workspace; wire `schema` and XML well-formedness of `paths` into `check_artifacts` with a tool test.

---

## Phase 8: User Story 6 — Configure and inspect the workspace (Priority: P3)

- [ ] T034 [US6] `main/config/ConfigSummary.java` with test first: line
  `Workspace: <path> (ok | created | <error>)` in `--check-config` (FR-004).
- [ ] T035 [US6] `main/config/ConfigValidator.java` with test first (FR-003): two profiles in the
  default configuration directory whose workspaces are equal, or one inside the other, → configuration
  error naming both profiles and the path; extend `test/isolation/TwoProfilesTest.java` so two
  profiles export into separate workspaces without interfering.

---

## Phase 9: Polish & cross-cutting

- [ ] T036 [P] `docs/tools.md`: sections `export_artifacts` and `check_artifacts` (descriptions,
  inputs, outputs, errors, finding codes), tool table updated (10 tools).
- [ ] T037 [P] `docs/setup.md`: `workspace` setting, git requirement, workspace layout, local-changes
  behaviour, "never pushed"; `README.md` feature list.
- [ ] T038 [P] Live test `test/live/ArtifactExportLiveTest.java` and `docs/live-tests.md`
  (`INUBIT_LIVE_DIAGRAM_GROUP`): read-only export of one diagram group twice → second `unchanged`;
  no `AES-` value and no non-placeholder `type="Password"` value in the workspace; `check_artifacts`
  on the exported workflows → no ERROR. Never runs against `production` groups.
- [ ] T039 `CHANGELOG.md` (Unreleased: Added export_artifacts, check_artifacts, workspace setting;
  dependency Saxon-HE 10.9) and `THIRD-PARTY-NOTICES.md` check.
- [ ] T040 Validation per `quickstart.md` A–B: `mvn -q clean verify`, then
  `mvn -q -Dtest=NoCustomerIdentifiersTest test` without `clean`, `--check-config` with a test
  profile; record the test count; quickstart C/D with the user's approval on the development node.

---

## Dependencies & Execution Order

- **Setup (T001–T004)** → **Foundational (T005–T012)** → **US2 (T013–T014)** → **US1 (T015–T024)**.
- **US3 (T025–T027)** needs US1's workspace files (T017) and T006; it can start after T017.
- **US4 (T028–T032)** needs only Foundational (T006) and T003; T028 gates T029–T032. It can run in
  parallel with US1/US3 after Phase 2; T032 needs T027.
- **US5 (T033)** needs T006 and T027.
- **US6 (T034–T035)** needs T011; independent of US1–US5.
- **Polish (T036–T040)** after all stories.

## Parallel Examples

- Phase 2: T005, T006, T007, T008, T009 in parallel; then T010–T012.
- US1: T015 and T016 in parallel; T017 → T018; T019 → T020 → T021 → T022 → T023 → T024.
- After Phase 2: US4 (T028→T029→T030→T031) alongside US1.
- Polish: T036, T037, T038 in parallel.

## Implementation Strategy

1. **MVP** = Phases 1–4 (setup, foundation, secrets, export): the assistant can export diagram groups
   as reviewable files with history and no secrets. Stop and validate with quickstart A rows US1/US2.
2. Add **US3** (structure checks) — the safety net feature 004 depends on.
3. Add **US4** (XSLT), then **US5**, **US6**.
4. Polish and validate; open the PR.
