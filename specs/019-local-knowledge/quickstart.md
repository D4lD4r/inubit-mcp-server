# Quickstart: Local Knowledge per Profile

**Feature**: 019-local-knowledge | **Plan**: [plan.md](plan.md)

## Prerequisites

- Java 21, Maven (offline repository as for 0.8.0).
- Build: `mvn -o -q clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-019-tmp`
  (failsafe runs `KnowledgeCrossProcessIT`).
- For the live check only: a profile with at least one reachable node and an MCP client
  (Claude Desktop, VS Code or Copilot CLI) registered for the profile as in the README.

## Offline verification (automated)

| Scenario (spec) | Expected test classes |
|---|---|
| US1 AS1, AS2: instructions with list / empty | `KnowledgeAnnouncementTest`, `ServerIdentityTest` (knowledge paragraph in the instructions) |
| US1 AS3: search, case-insensitive, newest first, paged | `KnowledgeServiceSearchTest`, `ReadKnowledgeToolTest` |
| US1 AS4: read by name, page bound and marker | `KnowledgeServiceReadTest`, `ReadKnowledgeToolTest` |
| US1 AS5, FR-008a: masking, search for a secret finds nothing | `SecretFinderTest`, `KnowledgeServiceSearchTest`, `KnowledgeSecretLeakTest` |
| US1 AS6: quoted, single-line, cut references; "not instructions" | `KnowledgeAnnouncementTest` |
| US2 AS1: create, audit without content | `KnowledgeServiceRecordTest`, `KnowledgeAuditTest`, `RecordKnowledgeToolTest` |
| US2 AS2: append on current content, history, sizes | `KnowledgeServiceRecordTest`, `KnowledgeHistoryTest` |
| US2 AS3: replace with token / `CONFLICT` | `KnowledgeServiceRecordTest`, `VersionTokenTest` |
| US2 AS4: secret in new text refused (also in name) | `KnowledgeServiceRecordTest`, `KnowledgeSecretLeakTest` |
| US2 AS5: replace of a note with a secret | `KnowledgeServiceRecordTest` |
| US2 AS6: recording off | `KnowledgeToolsWiringTest`, `KnowledgeAnnouncementTest`, `ConfigLoaderTest` |
| US2 AS7: propose-then-record text | `KnowledgeAnnouncementTest`, `RecordKnowledgeToolTest` (description) |
| US2 AS8: 256 KiB and 100 MiB bounds | `KnowledgeBoundsTest` |
| US2 AS9: two processes append | `KnowledgeLockTest` (threads), `KnowledgeCrossProcessIT` (two JVMs) |
| US2 AS10: symbolic link `old.md` | `KnowledgeDirectoryTest`, `KnowledgeServiceRecordTest` (POSIX only) |
| D-12: links on `.lock`, `.history`, a history segment or a parent segment refused; nothing written outside the directory | `KnowledgeDirectoryTest`, `KnowledgeLockTest`, `KnowledgeHistoryTest` (POSIX only) |
| D-12: a file appears at the target between check and publish → `CONFLICT`, the file unchanged | `KnowledgeServiceRecordTest` (test hook) |
| D-10, FR-014: publish fails → note and history unchanged; prune only after success | `KnowledgeServiceRecordTest`, `KnowledgeHistoryTest` |
| D-9: listing deeper than 16 levels or beyond 100,000 entries → listed up to the limit, warning | `KnowledgeDirectoryTest` |
| D-10: size walk beyond 1,000,000 entries → `PRECONDITION_FAILED` (fail closed); files deeper than 16 levels still counted | `KnowledgeBoundsTest` |
| D-10 step 1: `content` over 256 KiB refused before credentials are read or text is scanned | `KnowledgeServiceRecordTest` (fake readiness not called) |
| D-10 commit point: pruning fails after publish → success with warning, `historyVersions` 11 | `KnowledgeServiceRecordTest`, `KnowledgeHistoryTest` |
| D-6: multiline XML secret values (element, `encrypted="true"`, `type="Password"`, `name="…Password"`, CDATA, missing closing tag) | `SecretFinderTest` |
| D-12: `.lock` as a link refused; Windows lock file not deletable while held | `KnowledgeLockTest`, `OsPrivateFileSystemTest` (`openLockFileNoFollow`) |
| D-13: pre-write audit fails → `INTERNAL`, nothing written; final audit fails → note written, `PENDING` stays, warning | `KnowledgeServiceRecordTest` (failing audit fake) |
| D-5, D-6: `source`/`lastWriter` and YAML block values masked | `NoteMetadataTest`, `SecretFinderTest`, `KnowledgeSecretLeakTest` |
| D-9: `ref` reads a note whose listed name is cut, masked or holds a control character; duplicate NFC names → `CONFLICT` by name, both readable by `ref`; unknown `ref` → `NOT_FOUND`; a ref-based write's `PENDING` record names the masked note | `KnowledgeServiceReadTest`, `KnowledgeServiceRecordTest`, `ReadKnowledgeToolTest` |
| D-5: single-quoted round trip (quotes, backslashes); block over 64 lines / 8 KiB refused | `NoteMetadataTest`, `KnowledgeServiceRecordTest` |
| D-11: twelve versions within one clock instant: counters never reused, the ten newest kept (contents asserted) | `KnowledgeHistoryTest` |
| D-4: fold collisions (`ß`/`ẞ`/`SS`, `ı`/`i`, NFC forms) and the `CaseFolding.txt` table | `NoteNameTest` |
| D-10 step 4: an invalid name holding a secret is refused without echo, audit shows `***` | `KnowledgeServiceRecordTest`, `KnowledgeSecretLeakTest` |
| US3 AS1, SC-003: two profiles | `KnowledgeProfilesTest` |
| US3 AS2, FR-009: hand edit visible | `KnowledgeServiceReadTest` |
| US3 AS3, FR-002: overlap errors | `ConfigValidatorKnowledgeTest` |
| US3 AS4, FR-003: permission warning, `--check-config` line | `OsPrivateFileSystemTest`, `MainTest` (`--check-config`, start warning), `ConfigSummaryTest` |
| Edge: names outside the rule, NFC/case collision, occupied path, malformed metadata, unreadable and oversized notes, hidden files | `NoteNameTest`, `NoteMetadataTest`, `KnowledgeDirectoryTest`, `KnowledgeServiceRecordTest` |
| FR-010 (a) readiness (D-7) | `ProfileSecretsTest`, `NodeSecretsTest` (`awaitSettled`), `KnowledgeServiceCredentialsTest` |
| FR-010 (b) moved rule unchanged | `KeyMaterialTest`, `SecretRedactorTest`, `RepositoryArchiveTest` |
| FR-016, FR-018, SC-006: annotations, two tools | `KnowledgeToolsWiringTest`, `ToolAnnouncementTest` |
| SC-004: 5,000 notes, 1 MiB note | `KnowledgeBoundsTest` |
| SC-005: audit and 10 versions | `KnowledgeAuditTest`, `KnowledgeHistoryTest` |
| FR-021: docs match schemas and bounds | `KnowledgeToolsReferenceTest` |
| FR-022: synthetic data | `NoCustomerIdentifiersTest`, `tools/check-identifiers.py` |
| Package placement | `PackageBoundaryTest` |

## Live check (SC-001)

Use a scratch profile (e.g. `acme-test`) and a synthetic finding; nothing here touches INUBIT.

1. `java -jar inubit-mcp-server-<version>.jar --profile acme-test --check-config` → `Knowledge: …/knowledge (absent),
   recording on, 0 notes`.
2. Session 1: connect the client. Ask: "What do you know about the ACME order export?" → the
   assistant says the local knowledge is empty and researches. Then: "Remember that the ACME
   order export runs in Workflow-0001 and writes to sftp.example.test." → the assistant **proposes**
   a note (name, title, topics, content) and waits; reply "yes" → `record_knowledge` `create`.
   Check the file `~/.inubit-mcp/acme-test/knowledge/<name>.md` (owner-only: `ls -l`), and the
   audit log line `record_knowledge` without the content.
3. Session 2 (new conversation, server restarted): ask the same question → the assistant answers
   from the note **without** INUBIT tool calls, naming the note and its last change. The
   instructions (client's server info) list the note.
4. Ask the assistant to append "Contact: ops@example.test" → proposal, agreement, `append`; check
   `.history/<name>.md/` holds one version.
5. Edit the note by hand, add a line `password: Example-Only-1`; ask the assistant to read it → the
   value shows as `***`, `masked: 1`; ask it to search for `Example-Only-1` → no hit; ask it to
   replace the note → `PRECONDITION_FAILED` (remove the secret by hand first).
6. Set `knowledge.recording: false`, restart → `record_knowledge` is not offered; the instructions
   say the knowledge is read-only.
7. Delete the scratch profile's knowledge directory.

Record the result (client, version, pass/fail per step) in the PR description.

## Validation record

**Offline, 2026-11-10** (branch `019-local-knowledge`, base `origin/main` 0.8.0, at 2d390b1):
- Every row of the offline table was checked against the test classes: each named class exists
  and covers its row (spot checks: the test hook of `KnowledgeServiceRecordTest`, `awaitSettled`
  in `NodeSecretsTest`, `openLockFileNoFollow`, the `CaseFolding.txt` test of `NoteNameTest`,
  the knowledge paragraph in `ServerIdentityTest`, the `--check-config` line in `MainTest` and
  `ConfigSummaryTest`). No name needed correcting.
- `mvn -o clean verify`: surefire 5952 tests, 0 failures, 0 errors, 58 skipped; failsafe 3 tests
  (including `KnowledgeCrossProcessIT`), 0 failures.
- `python3 -I -m unittest discover -s tools/tests`: 411 tests OK.
- `tools/check-identifiers.py --all`: clean.

**Live**: not done yet (T041, left to the person).
