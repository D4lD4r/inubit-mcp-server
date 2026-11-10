# Tasks: Local knowledge per profile

**Input**: Design documents from `specs/019-local-knowledge/` (spec, plan, research D-1…D-18,
data-model, contracts, quickstart, reviews)

**Tests**: mandatory (constitution Principle III, TDD): each implementation task starts with its
failing test in the same task. No test contacts INUBIT; notes are synthetic (ACME,
`*.example.test`, `Workflow-0001`, FR-022) in `@TempDir` knowledge directories; configured
credentials come from a `FakeConfiguredSecrets` (test) unless a task names the real adapter.

**Build**: `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-019-tmp` (tmpdir
outside the checkout; never two builds in parallel); Python: `python3 -I -m unittest discover -s
tools/tests`. Paths below are relative to `src/main/java/de/dadecker/inubit/mcp/` (main) and
`src/test/java/de/dadecker/inubit/mcp/` (test) unless they start with `src/`, `docs/`, `tools/`.
`knowledge/` means `application/knowledge/`.

**Secrets (SC-002, Constitution II)**: every task that adds a message, warning, log line, excerpt,
instructions text, audit input or `--check-config` line asserts with `testing/TestSecrets` that a
configured password and every FR-010 kind placed in a note's name, title, topics, `source`,
`lastWriter` or body never appears in it — named below as "leak check".

**Reviews**: after each phase a `code-review` reviewer checks the phase diff; findings and
decisions go to `specs/019-local-knowledge/reviews/implementation.md` ("Implementation — phase N").

**Decision gate (D-7)**: passed. The gate task T016 (between Phases 3 and 4) recorded the
person's choice on 2026-11-10: option (A), fail closed (plan.md "Decision"). Tasks marked
**[D-7]** implement that option as written.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [X] T001 Baseline: the build above is green on `019-local-knowledge` (on top of 0.8.0); note the
  test count (expected 4998 tests, 0 failures, 51 skipped) in `reviews/implementation.md`

## Phase 2: Foundational (plan stage 1; blocks every story)

- [X] T002 Move `KeyMaterial` from `adapter/archive/v81` to `domain/model` (D-6 (b), behaviour
  unchanged; `+ longBase64` only if research D-6 needs it for the token rule); update the imports
  of `SecretRedactor`, `RepositoryArchive`, `SecretPaths`; keep `adapter/archive/v81/package-info`
  (`theWorkspaceAdapterPackagesExist`). There is no dedicated test today: first write
  `domain/model/KeyMaterialTest` against the new location, pinning the current behaviour (red: the
  class does not exist there yet), then move (green);
  `SecretRedactorTest`, `RepositoryArchiveTest`, `ArchiveRoundTripTest` and
  `V81ReleaseArchivesTest` stay unchanged apart from the import — main
  `domain/model/KeyMaterial.java`, test `domain/model/KeyMaterialTest.java` (new)
- [X] T003 [P] `NoteName` (D-4): NFC, record rule (1–4 segments, 1–100 code points of `\p{L}`,
  `\p{N}`, `_ . -` and space; no leading `.`, no trailing `.`/space, no Windows device name incl.
  superscript digits, no segment ending in `.md` in any case), messages naming rule and segment number, never
  the name; `fold` = upper/lower to a fixpoint (≤ 4 rounds) then NFC; `equals` on NFC code points.
  Tests: every rule, `ß`/`ẞ`/`SS`/`ss`, `Σ`/`ς`/`σ`, `ſ`/`s`, Kelvin sign, `ı`/`İ`/`i`, NFD/NFC
  `Störung`, plus the `CaseFolding.txt` (Unicode 15.1, C+F lines of letters, license header kept)
  table test — main `domain/model/NoteName.java`, test `domain/model/NoteNameTest.java`, resource
  `src/test/resources/unicode/CaseFolding-15.1.0-CF.txt`
- [X] T004 [P] `Masked` and `SecretSpan` records (D-6): masking of spans to `***`, count, merging
  overlapping spans, never splitting a surrogate pair — main `domain/model/Masked.java`,
  `domain/model/SecretSpan.java`, test `domain/model/MaskedTest.java`
- [X] T005 `SecretScrubber.occurrences(String)` returning the spans of held values (D-6 (a)),
  behaviour of the existing scrub methods unchanged — main `infra/SecretScrubber.java`, test
  `infra/SecretScrubberTest.java`
- [X] T006 [P] `SimilarNames` made `public` (behaviour unchanged): first a red test in
  `knowledge/SimilarNamesAccessTest.java` that calls `SimilarNames.of` from the
  sub-package (does not compile while package-private), then the modifier; `PackageWriter.defaultDirectory`
  (D-2, the export default used by FR-002) — main `application/SimilarNames.java`,
  `application/PackageWriter.java`, tests `knowledge/SimilarNamesAccessTest.java` (new),
  `application/SimilarNamesTest.java` (new, pins the behaviour today covered through
  `InventoryServiceTest`), `application/PackageWriterTest.java`
- [X] T007 [P] `knowledge/package-info.java` and `KnowledgeLimits` (data-model.md constants, incl.
  `SIZE_WALK_ENTRIES = 1_000_000`): first `KnowledgeLimitsTest` asserting every value of
  data-model.md and the relations between them (e.g. read page < note read bound, excerpt <
  instructions bound) — red, then the class; `PackageBoundaryTest` stays green without a new rule
  — main `knowledge/KnowledgeLimits.java`, tests `knowledge/KnowledgeLimitsTest.java` (new),
  `architecture/PackageBoundaryTest.java` (unchanged, run)

**Checkpoint**: build green; code-review phase 2.

## Phase 3: Configuration (plan stage 2; US3 AS3, AS4; FR-001 … FR-003)

**Goal**: `knowledge: {directory, recording}` per profile, overlap checks, permission warning, the
`--check-config` line.
**Independent test**: a profile with and without the block loads; overlaps fail; `--check-config`
prints the line without reading a credential.

- [X] T008 [US3] `KnowledgeConfig` (directory, strict boolean `recording`, default on) in
  `ProfileConfig`; `ConfigLoader` default `~/.inubit-mcp/<profile>/knowledge`, `~` expanded,
  relative paths loaded as given (refused by `ConfigValidator`, T009), `recording: "yes"` refused — main
  `config/KnowledgeConfig.java`, `config/ProfileConfig.java`, `config/ConfigLoader.java`, test
  `config/ConfigLoaderTest.java`
- [X] T009 [US3] `ConfigValidator.checkKnowledge` (FR-002, D-2): after `canonical()`, the
  relations *equal*, *inside* and *contains* are each an error, tested for every forbidden path of
  the D-2 table: this profile's `workspace` and `auditDirectory`; `BackupStore.defaultRoot`
  (backups); `DeploymentLedger.defaultDirectory` (deployment ledger);
  `PackageWriter.defaultDirectory` (packages); `~/.inubit-mcp` itself (equal and contains only);
  `~/.inubit-mcp/<other>` and every other profile's `workspace`, `auditDirectory` and
  `knowledge.directory`; the default configuration directory and the folder of the configuration
  file in use (`ConfigLocation.roots()`). Also: inside `~/.inubit-mcp/<profile>` is allowed, a copy
  of the same profile gets the existing warning only, a symbolic-link root is resolved; error
  texts per configuration-delta.md; leak check — main `config/ConfigValidator.java`, test
  `config/ConfigValidatorKnowledgeTest.java`
- [X] T010 [P] [US3] `PrivateFileSystem.othersMayAccess(Path)` (D-3): POSIX group/other read or
  write, Windows ACL `check`; empty for a missing path — main `domain/port/PrivateFileSystem.java`,
  `adapter/os/OsPrivateFileSystem.java` (and the other `*PrivateFileSystem` implementations), test
  `adapter/os/OsPrivateFileSystemTest.java`
- [X] T011 [US3] `KnowledgeStatus` record (`OK`, `ABSENT`, `PROBLEM` with `not a directory` or
  `not readable`, `recording`, `notes`) and its `ConfigSummary` line `Knowledge: <path> (<status>),
  recording on|off, <n> notes`, tested with constructed values; in `Launcher` the start-time
  warnings of configuration-delta.md: the permission warning (T010) and "not usable: not a
  directory / not readable" (`Files.isDirectory`/`isReadable`, no walk); leak check. The note
  count from the walk is wired into `--check-config` in T018 — main `config/KnowledgeStatus.java`,
  `config/ConfigSummary.java`, `Launcher.java`, tests `config/ConfigSummaryTest.java`,
  `MainTest.java`

**Checkpoint**: build green; code-review phase 3.

## Decision gate (D-7; after Phase 3, before Phase 4)

- [X] T016 The person chose D-7 option (A), fail closed, on 2026-11-10. Recorded in spec.md
  "Plan decisions", plan.md ("Decision", Constitution Check, Complexity Tracking) and research
  D-7, reviewed in `reviews/tasks-d7.md`; no other artifact changes for (A), the [D-7] tasks stay
  as written. Phases 4 to 8 may start.

## Phase 4: Secrets (plan stage 3; FR-010, FR-008a, FR-013)

**Goal**: one finder for all four kinds; configured credentials available through a port.
**Independent test**: `SecretFinderTest` finds every kind in every position, masks spans only.

- [X] T012 `SecretFinder` rules (b)–(d) (D-6): key material via `KeyMaterial`; `AES-`/`AESG`
  tokens (≥ 16 base64 characters); content of elements with `encrypted="true"`/`type="Password"`
  and of key-word elements or `name="…key word…"` elements **across lines** to the matching
  closing tag (missing tag → to the end of the text); assignments (plain, YAML/properties, JSON,
  quoted, XML attribute) with the value extents and exemptions of D-6, YAML block scalars
  (`|`, `>`, indicators) as one span; keys stay readable; at most 4 MiB input — main
  `knowledge/SecretFinder.java`, test `knowledge/SecretFinderTest.java` (each form, CDATA,
  multiline, `${secret:…}` exempt, empty block, findings never contain the value)
- [X] T013 [D-7] `ConfiguredSecrets` port (`prepare`, `occurrences`, readiness states per D-7) and rule
  (a) in `SecretFinder` through the port; `FakeConfiguredSecrets` (test) with `Held`, `Waiting`,
  `Failed`, `NothingConfigured` — main `domain/port/ConfiguredSecrets.java`,
  `knowledge/SecretFinder.java`, test `testing/FakeConfiguredSecrets.java`,
  `knowledge/SecretFinderTest.java`
- [X] T014 [D-7] `NodeSecrets.awaitSettled(Duration)` / `awaitSettledE2e(Duration)`: never throw;
  `Held`, `NothingConfigured`, `Waiting`, `Failed(FailureKind)`; `Cell.Refusal` keeps the
  classified outcome (D-7, private `Cell.settle` keeps its name); existing behaviour of the reads
  unchanged — main `adapter/NodeSecrets.java`, test `adapter/NodeSecretsTest.java`
- [X] T015 [D-7] `ProfileSecrets` implementing `ConfiguredSecrets` over all nodes' `NodeSecrets`
  (parallel reads, bounded wait, `SecretScrubber.occurrences`) — main `adapter/ProfileSecrets.java`,
  test `adapter/ProfileSecretsTest.java` (all held, one waiting, one failed (AWS offline), none
  configured)

**Checkpoint**: build green; code-review phase 4.

## Phase 5: User Story 1 – The assistant uses what is already known (P1, plan stage 4)

**Goal**: instructions list the notes; `read_knowledge` lists, searches and reads, masked.
**Independent test**: a directory with synthetic notes, a fake readiness: instructions, search,
read and paging match quickstart rows US1 AS1–AS6 and D-9.

- [X] T017 [P] [US1] `NoteMetadata` parser/writer (D-5): front matter within 64 lines / 8 KiB,
  plain/double/single-quoted values and flow lists, malformed cases (FR-004d), unknown keys kept
  in order; writer single-quotes every string and refuses a block over the bounds; round-trip tests
  with quotes, backslashes, `#`, `:`, `---`; 63/64/65 lines and 8 KiB ± 1 — main
  `knowledge/NoteMetadata.java`, test `knowledge/NoteMetadataTest.java`
- [X] T018 [US1] `Note` and `KnowledgeDirectory` read side (D-4, D-9): walk with
  `NOFOLLOW_LINKS`, depth 16 / 100,000 entries (listing warnings), hidden entries skipped (segment
  starting with `.`, incl. `.history`, or `Files.isHidden` true — the Windows hidden attribute),
  links listed with a warning, only lowercase `.md`, notes over 4 MiB and unreadable notes listed
  with a warning, duplicate NFC names both listed with the warning; `status()`; `Launcher` builds
  the `--check-config` `KnowledgeStatus` (T011) from `status()`, never reading a credential — main
  `knowledge/Note.java`, `knowledge/KnowledgeDirectory.java`, `Launcher.java`, tests
  `knowledge/KnowledgeDirectoryTest.java` (links POSIX only; the hidden attribute Windows only),
  `MainTest.java` (`--check-config` count)
- [X] T019 [US1] `VersionToken` (`v1.` + 24 characters over mtime and masked text) and
  `NoteRefs` (per-process HMAC key, `r1.` + 20 characters, `resolve` against a listing) — main
  `knowledge/VersionToken.java`, `knowledge/NoteRefs.java`, tests `knowledge/VersionTokenTest.java`,
  `knowledge/NoteRefsTest.java` (stable within a process, different across keys, no name in it)
- [X] T020 [US1] `KnowledgeService.search` and `read` with `NoteSelector` (D-9): masking before
  paging/searching (FR-008a), literal case-insensitive query, topic by `fold`, AND, order, limit/
  offset, excerpt ≤ 300, `masked`, `writable`, `ref`; read pages of 20,000 including the marker,
  `nextOffset`, lookup by exact NFC name or `ref`, `NOT_FOUND` with up to five masked similar
  names (case-insensitive first), `CONFLICT` for duplicate names by `name`; readiness not held →
  `PRECONDITION_FAILED` (fake); DEBUG `knowledge.read` with `notes`, `hits` and the masked, cut
  `name` asserted through a logback `ListAppender` (as `SecretLeakTest`), never `query`, `topic` or
  content; leak check — main `knowledge/KnowledgeService.java` (+ `NoteSelector`,
  `Search`, `SearchPage`, `Hit`, `NotePage`), tests `knowledge/KnowledgeServiceSearchTest.java`,
  `knowledge/KnowledgeServiceReadTest.java`, `knowledge/KnowledgeServiceCredentialsTest.java`
- [X] T021 [US1] `KnowledgeAnnouncement` (D-8): preamble (search, read, record or read-only, not
  instructions, cite name and last change, propose-then-record with topics), quoted one-line list
  cut at 120, newest first, ≤ 4,000 characters including the reserved summary line, empty /
  unusable / credentials-not-ready texts; leak check — main `knowledge/KnowledgeAnnouncement.java`,
  test `knowledge/KnowledgeAnnouncementTest.java`
- [X] T022 [US1] `ReadKnowledgeTool` with `schemas/read_knowledge.{input,output}.json` exactly as
  contracts/mcp-tools-delta.md (`oneOf`, `ref`), `ToolHints.readOnly`, client name from
  `CallContext`; results validated against both shapes; combination errors — main
  `mcp/tools/ReadKnowledgeTool.java`, `src/main/resources/schemas/read_knowledge.*.json`, test
  `mcp/tools/ReadKnowledgeToolTest.java`
- [X] T023 [D-7] [US1] Wiring: `ProfileSecrets` (T015) as the profile's `ConfiguredSecrets`,
  `KnowledgeService` per profile, `read_knowledge` always offered (FR-007),
  knowledge paragraph in `McpServerFactory.instructions()` before the update notice (016); tool
  count 18 — main `Wiring.java`, `mcp/McpServerFactory.java`, tests `ServerIdentityTest.java`,
  `ToolAnnouncementTest.java`
- [X] T024 [D-7] [US1] Start-time `prepare` in `Launcher` (only when notes exist, ≤ 5 s; otherwise
  the announcement's credentials-not-ready text; the INFO line of configuration-delta.md when not
  ready, asserted through a `ListAppender`; leak check) — main `Launcher.java`, tests `MainTest.java`,
  `KnowledgeToolsWiringTest.java`

**Checkpoint**: build green; code-review phase 5.

## Phase 6: User Story 2 – Keep research results for later sessions (P1, plan stage 5)

**Goal**: `record_knowledge` create/append/replace per D-10 … D-13.
**Independent test**: quickstart rows US2 AS1–AS10 and the D-10 … D-13 rows pass with a fake
readiness and a failing-audit fake.

- [X] T025 [P] [US2] `PrivateFileSystem.openLockFileNoFollow(Path)`: as `openLockFile` (Windows
  `NOSHARE_DELETE`) plus `NOFOLLOW_LINKS`; `openLockFile` unchanged — main
  `domain/port/PrivateFileSystem.java`, `adapter/os/OsPrivateFileSystem.java`, test
  `adapter/os/OsPrivateFileSystemTest.java` (link refused, POSIX; Windows simulation)
- [X] T026 [US2] `KnowledgeLock` (D-12): in-process lock, then `tryLock` polling up to 10 s,
  `.lock` created owner-only and opened with `openLockFileNoFollow` — main
  `knowledge/KnowledgeLock.java`, test `knowledge/KnowledgeLockTest.java` (threads, timeout,
  link)
- [X] T027 [P] [US2] `KnowledgeHistory` (D-11): `add` (counter one above the greatest for the
  instant, owner-only, segments created one at a time), `undo`, `prune` to 10 by parsed (instant,
  counter), unmatched files never pruned — main `knowledge/KnowledgeHistory.java`, test
  `knowledge/KnowledgeHistoryTest.java` (twelve same-instant versions with contents, link on
  `.history` and a segment)
- [X] T028 [US2] `KnowledgeDirectory` write side (D-10, D-12): `checkInternal` (`NOFOLLOW_LINKS` on
  every parent segment, `.history`, `.lock`), occupancy check, `publishNew` via
  `Files.createLink` (exists → `CONFLICT`, unsupported → `PRECONDITION_FAILED`), `publishReplace`
  via atomic move, test hook between check and publish, `totalBytes` walk (not depth-limited,
  fail closed after 1,000,000 entries), stale temporary files older than one hour removed; first-write creation (D-3): an absent nested
  root, its missing parents, note subdirectories, `.history` and the note are created owner-only
  (through the injected `PrivateFileSystem` port, `createDirectories`/`createFile`; tests use the
  `testing/PrivateFiles` implementations and check POSIX bits / the Windows ACL), existing directories keep their permissions — main
  `knowledge/KnowledgeDirectory.java`, tests `knowledge/KnowledgeDirectoryTest.java`,
  `knowledge/KnowledgeBoundsTest.java` (walk part)
- [X] T029 [P] [US2] `KnowledgeAudit` (D-13): `REFUSED` / `PENDING` → `EXECUTED|REFUSED|FAILED`,
  capability `record_knowledge`, node `-`, inputs name (masked or `***`), mode, sizes; never
  title, topics, content, token or `ref`; leak check — main `knowledge/KnowledgeAudit.java`, test
  `knowledge/KnowledgeAuditTest.java`
- [X] T030 [US2] `KnowledgeService.record` (D-10 steps 1–8): combination rules and the early
  256 KiB `content` bound before readiness and scanning; readiness; secret check of the new text
  (name always); target (name rule, or `ref` resolved, checked, re-resolved under the lock);
  `PENDING`; lock; checks under the lock (fold collision, occupancy, `NOT_FOUND`, 4 MiB,
  malformed metadata, stale token, secret in a note to replace); new text with metadata;
  256 KiB / 100 MiB bounds; history add → publish (commit point) → prune; undo before the commit
  point; warnings after it; result. Tests for every row of quickstart D-10 … D-13 incl. the
  failing-audit fake and the race hook; INFO `knowledge.record` with the masked name, mode,
  sizes and outcome asserted through a `ListAppender` for each outcome; leak check — main
  `knowledge/KnowledgeService.java` (+ `Record`, `Recorded`), test `knowledge/KnowledgeServiceRecordTest.java`
- [ ] T031 [US2] `ToolHints.localOnlyWrite(title)` (`openWorld=false`) and `RecordKnowledgeTool`
  with `schemas/record_knowledge.{input,output}.json` exactly as the contract (structural rules
  only, description with the propose-then-record rule incl. topics) — main `mcp/ToolHints.java`,
  `mcp/tools/RecordKnowledgeTool.java`, `src/main/resources/schemas/record_knowledge.*.json`,
  tests `mcp/ToolHintsTest.java`, `mcp/tools/RecordKnowledgeToolTest.java`
- [ ] T032 [US2] Wiring: `record_knowledge` offered only with `recording: true`; instructions say
  read-only otherwise (FR-012); tool count 19 — main `Wiring.java`, tests
  `KnowledgeToolsWiringTest.java`, `ToolAnnouncementTest.java`, `ServerIdentityTest.java`
- [ ] T033 [US2] `KnowledgeCrossProcessIT` (failsafe): two JVMs append to one note; both appends
  present, history holds both previous versions — test `KnowledgeCrossProcessIT.java`

**Checkpoint**: build green; code-review phase 6.

## Phase 7: User Story 3 – The operator keeps control; bounds and leaks (P2, plan stage 6)

- [ ] T034 [P] [US3] `KnowledgeProfilesTest`: two profiles, two directories; each server's tools
  and instructions see only their own notes (SC-003), and a record through each profile changes
  only that profile's directory (FR-001, US3 AS1) — test `KnowledgeProfilesTest.java`
- [ ] T035 [P] `KnowledgeBoundsTest`: 5,000 notes and a 1 MiB note — instructions, search page and
  read page within their bounds and reporting the cut (SC-004); 256 KiB / 100 MiB write bounds
  (US2 AS8) — test `knowledge/KnowledgeBoundsTest.java`
- [ ] T036 `KnowledgeSecretLeakTest`: every FR-010 kind in a hand-written note's name, title,
  topics, `source`, `lastWriter` and body (also YAML block and multiline XML) never appears in
  instructions, hits, pages, version tokens, refs, audit lines, captured log lines (the test
  first asserts that the read and record log lines were captured, so the check is not vacuous) or
  error results; every kind in new text refused without echo, also in an invalid name (SC-002) — test
  `security/KnowledgeSecretLeakTest.java`

**Checkpoint**: build green; code-review phase 7.

## Phase 8: Documentation and polish (plan stage 7; FR-021, FR-022)

- [ ] T037 [P] Docs (FR-021): `docs/setup.md` (directory, switch, bounds, history and restoring by
  hand, `--check-config` line, the credential reads of D-7 as decided), `docs/tools.md` (both
  tools, schemas, errors, tool count), `README.md` (tool table and count; if 018 merged first, the tool count reconciled here and in
  `ToolAnnouncementTest` per the plan's touch points), `SECURITY.md` (what may
  be stored; local, unencrypted, owner-only; pattern-based check, prose secrets not caught)
- [ ] T038 [P] `CHANGELOG.md` `[Unreleased]`: the local knowledge, the two tools, the credential
  reads (D-7 as decided); `pom.xml` version unchanged
- [ ] T039 `KnowledgeToolsReferenceTest`: `docs/tools.md` and `docs/setup.md` match the schemas
  and `KnowledgeLimits`; `NoCustomerIdentifiersTest` and `tools/check-identifiers.py` pass (FR-022)
  — test `KnowledgeToolsReferenceTest.java`
- [ ] T040 Full verification (mvn + Python), quickstart offline table checked row by row and the
  validation record filled in `quickstart.md`; final code-review over
  `git diff origin/main...HEAD`; findings in `reviews/implementation.md`

## Live check (SC-001; after the implementation, by the person)

- [ ] T041 Live check steps 1–7 of `quickstart.md` "Live check" with a scratch profile; result
  (client, version, pass/fail per step) in the PR description

## Dependencies

- Phase 2 blocks everything; T002 before T012 (`KeyMaterial`); T003 before T018, T020, T030;
  T004 → T005; T004, T005 before T012, T013.
- Phase 3: T008 → T009; T008 + T010 → T011.
- Gate: Phase 3 → T016 → Phases 4 to 8.
- Phase 4: T012 → T013 → T014 → T015.
- US1: T017 → T018 (also T003, T011) → T019 → T020 (also T013) → T021 → T022 → T023 (also
  T015) → T024.
- US2: T025 → T026; T018 → T028; T026 + T027 + T028 + T029 + T020 → T030 → T031 → T032 → T033.
- Phase 7 needs T032.
- Phase 8 needs all of Phases 2–7; T037/T038 wording of D-7 needs T016.
- T041 after T040.

## Parallel opportunities

T003/T004/T006/T007 (then T005 after T004); T010 alongside T008/T009; T017 alongside T012 to T015; T025/T027/T029
alongside T028; T034/T035; T037 (docs) and T038 (CHANGELOG) touch different files. Builds always sequential.

## Implementation strategy

Phases 1 to 3 (foundation and configuration), then the passed gate T016. MVP = Phases 4 and
5: reading and the instructions with the real readiness. Then
recording (Phase 6), bounds and leak proof (Phase 7), docs. Each phase ends green and reviewed.
