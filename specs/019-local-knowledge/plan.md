# Implementation Plan: Local Knowledge per Profile

**Branch**: `019-local-knowledge` | **Date**: 2026-11-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/019-local-knowledge/spec.md` (four review rounds).
Base: `main` at the 0.8.0 release (42abe47). Independent of 018 (Partner Management).

## Summary

Each profile gets a **local knowledge**: a directory of Markdown notes (default
`~/.inubit-mcp/<profile>/knowledge`) that the operator writes by hand and the assistant searches,
reads and — after the operator agreed — extends. Two tools, one instructions paragraph, no INUBIT
contact:

- **Configuration** `knowledge.directory` / `knowledge.recording` (strict boolean, default on),
  overlap rules against every server directory and every other profile, owner-only creation on
  the first write, a permission warning and a `Knowledge:` line in `--check-config` (D-1 … D-3);
- **notes** are lowercase `.md` files in non-hidden directories; names are NFC paths; the record
  rule limits names the assistant writes; a hand-written front-matter block carries title,
  topics, source, last writer (D-4, D-5);
- **one secret finder** for masking on read and refusing on write: configured credentials, key
  material (the export's rule, moved to `domain`), INUBIT encrypted values (`AES-`, `AESG` and the
  recorded legacy form inside `encrypted="true"` elements), and assignments of password-like keys
  (D-6);
- **configured credentials** must be known before a note is shown: the knowledge tools read the
  profile's credentials first and refuse while a store does not answer (fail closed, D-7 —
  option (A), confirmed by the person on 2026-11-10, see "Decision");
- **instructions** announce the knowledge for every profile with the propose-then-record rule and
  a bounded, quoted, masked note list (D-8);
- **`read_knowledge`** lists/searches (masked text, newest first, paged, excerpts, version
  tokens) or reads one note in pages of 20,000 characters (D-9);
- **`record_knowledge`** `create` / `append` / `replace` with secret check, bounds (256 KiB,
  100 MiB fail-closed, 4 MiB read), version token for `replace`, a 10-version history pruned only
  after success, a cross-process lock with a 10 s wait, atomic writes (`create` by hard link, so it
  never replaces a file), no writes through links on any internal path, and an audit record per
  call (D-10 … D-13).

## Decision (person, D-7) — option (A), confirmed by the person on 2026-11-10

FR-010 (a) asks to mask the profile's configured credentials in notes. Since 011 (R-10) the server
reads no credential at start, so they are unknown until a node is first used. The plan **fails
closed** (D-7): the first knowledge call reads all of the profile's credentials (with possible
access dialogs for every node) and refuses while a store does not answer (e.g. AWS Parameter Store
offline); at server start the reads run for up to 5 s only when notes exist. Rejected
alternatives: (B) mask only credentials already read (never blocks, breaks SC-002 for unread
ones), (C) as (A) but never read at start (the note list then never appears in the instructions
of a profile with nodes). The person chose (A) on 2026-11-10; recorded in the spec's "Plan
decisions" and research D-7. **Gate**: the decision task T016 in tasks.md (after the
configuration phase) is passed; the secrets, read and record phases may start.

## Validation status

This plan was written in a session that could not run Maven, Java or `git push` (no permission
approval available). Every file reference was checked against the source; no build was run. T001
records the baseline before any code change.

## Technical Context

**Language/Version**: Java 21 (unchanged).

**Primary Dependencies**: unchanged (MCP Java SDK, Jackson, logback). No YAML or Markdown library
in `application` (front matter parsed by hand, D-5).

**Storage**: the knowledge directory per profile (files only); history under `.history`; lock
file `.lock`. Nothing in memory across calls (FR-009).

**Testing**:
- JUnit 5, AssertJ, `@TempDir` knowledge directories with synthetic notes (FR-022);
- `FakeConfiguredSecrets` for D-7 states; `testing/TestSecrets` for leak tests;
- `KnowledgeCrossProcessIT` (failsafe) starting a second JVM;
- POSIX-only tests (`@DisabledOnOs(WINDOWS)`) for permission bits and symbolic links, as in 013;
- baseline 0.8.0: 4998 tests, 0 failures, 51 skipped; T001 confirms it on this branch.

**Target Platform**: macOS, Linux, Windows (owner-only via the 013 ACL on Windows).

**Project Type**: single Maven module, shaded JAR.

**Performance Goals**: every call walks and reads the knowledge again (no cache); bounded by the
walk limits (depth 16, 100,000 entries), the read bound (4 MiB per note) and the 100 MiB total;
5,000 notes listed within a few seconds on a laptop (SC-004 test asserts bounds, not time).

**Constraints**:
- no INUBIT contact, independent of nodes, groups and write switches (FR-012, FR-018);
- `PackageBoundaryTest`: `application` only JDK/domain/SLF4J; `mcp` not `config`/`adapter`;
  `config` not `adapter`; workspace adapters not `application`/`mcp`;
- no note content, title, topic, excerpt, version token or detected secret in audit, logs or errors
  (FR-019, FR-020); names masked and cut there;
- `pom.xml` version unchanged.

**Scale/Scope**:
- 2 new tools (17 → 19), 4 new schemas, 1 new instructions paragraph, 1 new config block.
- New production types (about 22): `KnowledgeConfig`, `KnowledgeStatus`, `NoteName`, `Masked`,
  `SecretSpan`, `ConfiguredSecrets` (+ `Readiness`), `NoteMetadata`, `SecretFinder`, `Note`,
  `KnowledgeDirectory`, `VersionToken`, `KnowledgeLock`, `KnowledgeHistory`, `KnowledgeAudit`,
  `KnowledgeAnnouncement`, `KnowledgeService` (+ records), `KnowledgeLimits`, `ProfileSecrets`,
  `ReadKnowledgeTool`, `RecordKnowledgeTool`; moved: `KeyMaterial`.

## Constitution Check

*GATE: passed before research; re-checked after design.*

| Principle | Pre-research | Post-design |
|---|---|---|
| I. Safe by Default | ⚠️ the assistant writes files | ✅ <ul><li>No INUBIT state is touched; the precedent for local writes is `export_artifacts` (spec Context).</li><li>Never deletes a note; previous version kept (10); `replace` needs the token read; writes serialized and atomic.</li><li>Record only after the operator agreed (instructions and description, FR-017); switchable off per profile; annotated as a write (`readOnlyHint=false`).</li></ul> |
| II. Secrets Stay Out | ⚠️ hand-written notes may hold secrets | ✅ <ul><li>One finder masks on read (before paging and searching) and refuses on write; names masked in instructions, hits, audit, logs, errors.</li><li>Configured credentials known before any note is shown (fail closed, D-7).</li><li>Owner-only creation; permission warning; no secret in the version token (hash of masked text).</li><li>Prose secrets are a documented residual risk (SECURITY.md).</li></ul> |
| III. Test-First | ✅ | ✅ every task starts with failing tests (quickstart table); leak test with `TestSecrets`; two-profile and cross-process tests. |
| IV. Use-Case-Oriented Tools | ✅ | ✅ two tools named by the use case (`read_knowledge`, `record_knowledge`), within FR-018's limit. |
| V. Adapter Isolation & Version Compatibility | ✅ | ✅ no INUBIT access; file work in `application/knowledge` through `java.nio` and the `PrivateFileSystem` port (as `BackupStore`); credentials through the new `ConfiguredSecrets` port; `KeyMaterial` moved to `domain` so the archive adapter and the knowledge share one rule. |
| VI. Bounded, Structured Output | ✅ | ✅ instructions list ≤ 4,000 characters, search ≤ 50 hits with 300-character excerpts, pages ≤ 20,000 characters with marker and offset, warnings capped at 20; schemas for both tools. |
| VII. Observability & Auditability | ✅ | ✅ every recording call audited (PENDING → final, REFUSED); reads logged at DEBUG with names and counts only; `--check-config` line. |
| Tech constraints (011 R-10: no credential at start) | — | ⚠️ changed for profiles with notes (D-7) → Complexity Tracking, confirmed by the person on 2026-11-10 (option A). |

## Project Structure

### Documentation (this feature)

```text
specs/019-local-knowledge/
├── spec.md
├── plan.md                 # this file
├── research.md             # D-1 … D-18
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── configuration-delta.md
│   └── mcp-tools-delta.md
├── checklists/requirements.md
├── reviews/                # spec-r1..r4, plan-rN, tasks-rN
└── tasks.md                # /speckit-tasks
```

### Source Code (repository root)

```text
src/main/java/de/dadecker/inubit/mcp/
├── Launcher.java                     # serve: knowledge paragraph, start prepare (D-7, D-8);
│                                     # --check-config KnowledgeStatus; permission warning
├── Wiring.java                       # KnowledgeService, ProfileSecrets, 2 tools
├── config/
│   ├── KnowledgeConfig.java          # NEW
│   ├── KnowledgeStatus.java          # NEW
│   ├── ProfileConfig.java            # + knowledge
│   ├── ConfigLoader.java             # default directory, strict recording
│   ├── ConfigValidator.java          # checkKnowledge (FR-002)
│   └── ConfigSummary.java            # Knowledge: line
├── domain/model/
│   ├── NoteName.java                 # NEW
│   ├── KeyMaterial.java              # MOVED from adapter/archive/v81 (+ longBase64)
│   ├── Masked.java, SecretSpan.java  # NEW
├── domain/port/
│   ├── ConfiguredSecrets.java        # NEW
│   └── PrivateFileSystem.java        # + othersMayAccess, openLockFileNoFollow
├── application/
│   ├── SimilarNames.java             # public
│   ├── PackageWriter.java            # + defaultDirectory
│   └── knowledge/                    # NEW sub-package (D-16)
│       ├── KnowledgeLimits.java, NoteMetadata.java, SecretFinder.java, Note.java,
│       ├── KnowledgeDirectory.java, VersionToken.java, KnowledgeLock.java, NoteRefs.java,
│       ├── KnowledgeHistory.java, KnowledgeAudit.java, KnowledgeAnnouncement.java,
│       └── KnowledgeService.java (+ NoteSelector, Search, SearchPage, Hit, NotePage, Record, Recorded)
├── adapter/
│   ├── NodeSecrets.java              # + awaitSettled, awaitSettledE2e (Cell refactor)
│   ├── ProfileSecrets.java           # NEW
│   ├── os/OsPrivateFileSystem.java   # + othersMayAccess, openLockFileNoFollow
│   └── archive/v81/                  # import domain KeyMaterial (SecretRedactor, RepositoryArchive, SecretPaths)
├── infra/SecretScrubber.java         # + occurrences
└── mcp/
    ├── McpServerFactory.java         # knowledge paragraph in instructions()
    ├── ToolHints.java                # + localOnlyWrite
    └── tools/ReadKnowledgeTool.java, RecordKnowledgeTool.java   # NEW
src/main/resources/schemas/
    read_knowledge.{input,output}.json, record_knowledge.{input,output}.json   # NEW
src/test/java/de/dadecker/inubit/mcp/
    application/knowledge/*Test, domain/model/NoteNameTest, KeyMaterialTest,
    adapter/ProfileSecretsTest, security/KnowledgeSecretLeakTest, KnowledgeProfilesTest,
    KnowledgeToolsWiringTest, mcp/tools/*KnowledgeToolTest, KnowledgeToolsReferenceTest,
    KnowledgeCrossProcessIT (failsafe)
docs/setup.md, docs/tools.md, README.md, SECURITY.md, CHANGELOG.md
```

**Structure Decision**: single module as before; the knowledge use cases get their own
`application` sub-package (the first one) because they form a closed group of about 15 types;
`PackageBoundaryTest` rules cover sub-packages already (prefix match), its Javadoc is updated.

## Implementation stages

Each stage ends with a code review of its diff, the findings fixed, and a green build
(`mvn -o -q clean verify`).

1. **Foundation** — `KeyMaterial` move (behaviour-neutral, existing tests green), `NoteName`,
   `Masked`/`SecretSpan`, `SecretScrubber.occurrences`, `SimilarNames` public,
   `PackageWriter.defaultDirectory`, `KnowledgeLimits`.
2. **Configuration** — `KnowledgeConfig`, loader defaults and strict boolean, FR-002 checks,
   `othersMayAccess`, `KnowledgeStatus` and the `--check-config` line, start warning (US3 AS3,
   AS4).
3. **Secrets** — `SecretFinder` (b)–(d), `ConfiguredSecrets` port, `NodeSecrets.awaitSettled`,
   `ProfileSecrets`, (a) in the finder (FR-010).
4. **Read (US1)** — `NoteMetadata`, `KnowledgeDirectory`, `VersionToken`, `NoteRefs`, `KnowledgeService`
   search/read, `ReadKnowledgeTool`, schemas, wiring, `KnowledgeAnnouncement` and the instructions
   paragraph, start-time `prepare`.
5. **Record (US2)** — `openLockFileNoFollow`, `KnowledgeLock`, `KnowledgeHistory`, `KnowledgeAudit`, record flow,
   `RecordKnowledgeTool`, `ToolHints.localOnlyWrite`, `recording: false` wiring, cross-process IT.
6. **Operator control and bounds (US3, SC-003, SC-004)** — two-profile test, bounds test,
   leak test across all surfaces.
7. **Documentation** — FR-021 files, `KnowledgeToolsReferenceTest`, identifier check; live check
   (quickstart) by the person.

### Touch points

- **011 (credentials, R-10)**: D-7 reads credentials before knowledge calls and, with notes, at
  start; `NodeSecrets` gets non-throwing `awaitSettled` methods built on its existing shared read.
- **013 (owner-only on Windows)**: reused through `PrivateFileSystem`.
- **015 (archive export)**: `KeyMaterial` moves to `domain`; `SecretRedactor`/`RepositoryArchive`
  behaviour unchanged (their tests stay unchanged).
- **016 (instructions with update notice)**: the knowledge paragraph goes before the notice.
- **018 (Partner Management, separate branch)**: both add tools; whichever merges second updates
  the tool count in `docs/tools.md` and the README table, and `ToolAnnouncementTest`'s expected list.

## Complexity Tracking

| Violation | Why needed | Simpler alternative rejected because |
|---|---|---|
| 011 R-10 ("no credential is resolved at startup") no longer holds for profiles with notes; every knowledge call needs all of the profile's credentials (D-7) — option (A), confirmed by the person on 2026-11-10 | FR-010 (a) and SC-002 require masking configured credentials in hand-written notes; values never read are unknown to the process | (B) masking only held credentials leaves unread passwords visible; (C) never reading at start keeps 011 intact but withholds the note list from the instructions of nearly every profile (FR-006). Both were rejected by the person (2026-11-10). |
| A second home of the key-material rule is avoided by moving `KeyMaterial` from `adapter/archive/v81` to `domain/model` | FR-010 (b) requires "the existing rule"; `application` must not use an adapter | A copy would drift; the move is behaviour-neutral and covered by the existing archive tests. |
| FR-020 is read as "the record before the write": a failed final audit after the note was published leaves the `PENDING` record and a warning (D-13) | The note cannot be un-published atomically after the fact; `export_archives` (015) set the same precedent | Undoing the write after a final audit failure could itself fail and lose the operator's text; refusing to write without both records is impossible to guarantee. |
| First `application` sub-package | about 15 cohesive types; the flat package already holds 54 files | Flat placement would mix the knowledge with INUBIT use cases; the boundary rules apply by prefix and need no change. |
