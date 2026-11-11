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

**Live, 2026-11-11** (T041; `origin/main` 97c697a, jar built with `mvn -o clean package
-DskipTests`, reports version 0.8.1). The released 0.8.1 jar was not usable: 0.8.1 is cut from
`release/0.8.x` and holds neither feature 018 nor 019 (no knowledge classes or schemas, no
`Knowledge:` line). Client: a scripted Python JSON-RPC stdio client (`initialize`, `tools/list`,
`tools/call`); the conversation of steps 2–4 (ask, propose, agree; answer from the note) was
simulated, the server side of it checked: the instructions wording and the tools offered.
Scratch profile `acme-test` with `--config` outside the user configuration directory, group
`test` with node `node1` (`https://node1.test.example.test:8443`, never contacted), credentials
from `awsParameterStore` answered by a local stand-in for SSM `GetParameters` with synthetic
values (the knowledge tools need readable credentials, D-7; the Keychain was not touched).
- Step 1 **pass**: `Knowledge: ~/.inubit-mcp/acme-test/knowledge (absent), recording on, 0 notes`.
- Step 2 **pass**: the instructions carry the knowledge paragraph (propose first, record only
  after agreement, never secrets) and "The local knowledge of this profile is empty."; the
  search found nothing; `create` of `acme/order-export` wrote the note `-rw-------` in folders
  `drwx------` (also `.lock`); two audit lines `record_knowledge` (node `-`, `PENDING`, then
  `EXECUTED` with `name`, `mode`, `sizeBefore` 0, `sizeAfter` 261), without the content.
- Step 3 **pass** (after a restart): the instructions list `- "acme/order-export" — "ACME order
  export" [topics: "acme", "export"]` under "Notes (newest first):"; search and read return the
  note with `lastChange` and `source`.
- Step 4 **pass**: `append` "Contact: ops@example.test" gave `historyVersions` 1 and one file in
  `.history/acme/order-export.md/` (`-rw-------`).
- Step 5 **pass**: with `password: Example-Only-1` added by hand, the read shows `password: ***`
  with `masked: 1`; the search for `Example-Only-1` has no hit; `replace` is refused with
  `PRECONDITION_FAILED` ("holds a secret (password assignment in line 12 (key password)) …
  nothing was written"), audited as `REFUSED`. After removing the line by hand, `replace`
  succeeded (`historyVersions` 2); `Example-Only-1` was nowhere in the profile's folders.
- Step 6 **pass**: with `knowledge.recording: false`, `tools/list` has no `record_knowledge`
  (a call is answered `Tool not found`); the instructions say "The local knowledge is read-only
  for this profile; do not propose new notes." and still list the note; `--check-config` shows
  `(ok), recording off, 1 notes`.
- Step 7 **pass**: `~/.inubit-mcp/acme-test/` deleted; the other profiles' folders unchanged.
- Observations: (a) with `recording: false` the hits still report `writable: true`, although
  `record_knowledge` is not offered (`writable` reflects only the note and its name);
  (b) the server writes a note without a final line break, so a line added by hand with `>>`
  joins the last line (the secret was still masked, as key `example.testpassword`).
