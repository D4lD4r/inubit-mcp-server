# Data Model: Local Knowledge per Profile

**Feature**: 019-local-knowledge | **Date**: 2026-11-10 | **Research**: [research.md](research.md)

Paths relative to `src/main/java/de/dadecker/inubit/mcp/`. "New" types are added, "Changed" types
get the listed members; everything else is unchanged.

## Files on disk

| Item | Path | Created by | Rules |
|---|---|---|---|
| Knowledge directory | `knowledge.directory`, default `~/.inubit-mcp/<profile>/knowledge` | first write (owner-only) | never created at start or by a read; root may be a symbolic link (resolved); FR-002 overlap rules (D-2) |
| Note | `<dir>/<segment>/…/<last>.md` | operator or `record_knowledge` | regular file, lowercase `.md`, no hidden segment, not a link; ≤ 256 KiB when written by the server; read/search bound 4 MiB |
| Metadata block | first lines of a note, between `---` lines | operator or server | keys `title`, `topics`, `source`, `lastWriter`, `lastChange`; unknown keys kept (D-5) |
| History version | `<dir>/.history/<note path>.md/<yyyyMMdd'T'HHmmss.nnnnnnnnn'Z'>-<nnn>.md` | `append`/`replace` | owner-only; 10 newest kept per note; hidden from the walk; counts towards 100 MiB (D-11) |
| Lock file | `<dir>/.lock` | first write | owner-only, empty, never deleted, excluded from the 100 MiB total (D-12) |
| Temporary file | `<target dir>/.<file>.<uuid>.tmp` | a write | hidden; moved atomically over the target; leftovers older than 1 h deleted on the next write |

## Configuration (`config`)

| Type | Change | Fields | Rules |
|---|---|---|---|
| `KnowledgeConfig` | New record | `Path directory`, `boolean recording` | both filled by the loader when absent (default directory, recording `true`); a non-boolean `recording` is refused before binding (D-1) |
| `ProfileConfig` | Changed | `+ KnowledgeConfig knowledge` | JSON property `knowledge`; never null after loading |
| `ConfigLoader` | Changed | default `knowledge.directory`; `requireStrictKnowledgeRecording` | quoted boolean → load error |
| `ConfigValidator` | Changed | `checkKnowledge(…)` | errors per D-2; uses `canonical` |
| `KnowledgeStatus` | New record | `Path path`, `State state` (`OK`, `ABSENT`, `PROBLEM`), `Optional<String> problem`, `boolean recording`, `int notes`; `probe(KnowledgeConfig, int notes)` (no walk; a relative path is `not usable: see the errors`), `line()` | rendered by `ConfigSummary` as the `Knowledge:` line (D-3); `PrivateObjectCheck.knowledgeWarnings` reuses `probe` for the start-time warnings |
| `ConfigSummary` | Changed | `render(…, KnowledgeStatus)` | line after `Workspace:` |

## Domain (`domain`)

| Type | Change | Fields / members | Rules |
|---|---|---|---|
| `model/NoteName` | New value | `String value` (NFC); `static Optional<String> writableProblem(String)`; `static NoteName ofRelativePath(Path)`; `String fold()`; `List<String> segments()` | record rule and identity per D-4; `equals` = code points after NFC |
| `model/KeyMaterial` | Moved from `adapter/archive/v81` (package-private → public) | `isKeyMaterial(byte[])`, `decodeDocument(String)`, `hasKeyName(String)`, `longBase64(String)` (from `SecretPaths`) | behaviour unchanged; archive adapter imports it |
| `model/Masked` | New record | `String text`, `int count`; `MASK_TOKEN = "***"` | `count` = merged spans replaced |
| `model/SecretSpan` | New record | `int start`, `int end` | `0 ≤ start < end ≤ length` |
| `port/ConfiguredSecrets` | New port | `Readiness prepare(Duration bound)`; `List<SecretSpan> occurrences(String text)` | D-7 |
| `port/ConfiguredSecrets.Readiness` | New sealed type | `Ready`, `NotReady(List<Pending> pending)` (never empty); `ConfiguredSecrets.Pending(String node, String store, String state)` | `state` = `waiting` or a failure kind in lower case; never a value |
| `port/PrivateFileSystem` | Changed | `+ Optional<String> othersMayAccess(Path)`; `+ FileChannel openLockFileNoFollow(Path)` | POSIX bits / Windows `check` (D-3), empty for a missing path; lock file as `openLockFile` (Windows `NOSHARE_DELETE`) plus `NOFOLLOW_LINKS`, `openLockFile` unchanged (D-12) |

## Application (`application/knowledge`, new sub-package)

| Type | Kind | Fields / members | Rules |
|---|---|---|---|
| `NoteMetadata` | record + parser/writer | `Optional<String> title`, `List<String> topics`, `Optional<String> source`, `Optional<String> lastWriter`, `Optional<String> lastChange`, `List<Entry> unknown`, `int bodyStart`, `boolean malformed`, `Optional<String> problem` | grammar D-5; `write(...)` produces the block with single-quoted strings and refuses a block over 64 lines / 8 KiB (D-5) |
| `SecretFinder` | service | `List<Finding> find(String text)`; `Masked mask(String text)` | kinds (a)–(d) per D-6/D-7; (a) through `ConfiguredSecrets.occurrences` |
| `SecretFinder.Finding` | record | `Kind kind`, `int start`, `int end`, `int line`, `Optional<String> key` | `Kind` = `CONFIGURED_CREDENTIAL`, `KEY_MATERIAL`, `INUBIT_ENCRYPTED`, `ASSIGNMENT` |
| `Note` | record | `NoteFile file` (name, relative path, file, size, modification time as read), `State state` (`OK`, `TOO_LARGE`, `UNREADABLE`), `Optional<String> text` (absent unless OK), `NoteMetadata metadata`, `Optional<String> problem` (bound or cause, never a name); `name()`, `body()` | built per walk, never cached (FR-009); text decoded as strict UTF-8 (invalid → `UNREADABLE`), read with `NOFOLLOW_LINKS` and at most 4 MiB + 1 bytes (T018) |
| `KnowledgeDirectory` | file access | `Path root`; `Listing list()`; `Note read(NoteFile)`; `Status status()` (`int notes`, no note read); `long totalBytes()`; `checkInternal(Path)`; `publishNew(Path tmp, Path target)` (hard link); `publishReplace(...)` (atomic move) | listing walk per D-9 (`NOFOLLOW_LINKS`, depth 16, 100,000 entries); `totalBytes` not depth-limited, fails closed after 1,000,000 entries (D-10); test hook between check and publish (D-12) |
| `KnowledgeDirectory.Listing` | record | `boolean present`, `List<NoteFile> files`, `List<Skipped> skipped` (`relative`, `Reason` `LINK`/`UNREADABLE`), `List<String> warnings` (walk bounds, no name) | files ordered by last change desc, then name, then raw path (code point order); the walk runs on the real path of the root (D-2); the service masks the skipped names (T018) |
| `VersionToken` | utility | `static String of(FileTime mtime, String maskedText)` | `v1.` + base64url(first 18 bytes of SHA-256) (D-9) |
| `KnowledgeLock` | lock | `Held acquire(Duration wait)` (AutoCloseable) | in-process lock, then `FileChannel.tryLock` polling; `.lock` opened with `NOFOLLOW_LINKS`, a link there is refused (D-12) |
| `KnowledgeHistory` | file access | `Path add(Path note, NoteName name)`; `void undo(Path version)`; `int prune(NoteName name)` | add a new version, publish, then prune to 10; `undo` when publication fails (D-10, D-11) |
| `KnowledgeAudit` | audit helper | `pending`, `refused`, `finished` | D-13 |
| `KnowledgeAnnouncement` | text builder | `static String paragraph(boolean recording, State)`; `State` = `Empty`, `Unusable(problem)`, `NotReady(notes, nodes)`, `Listed(total, Iterator<Entry>)`; `Entry(name, title, topics)` (already reduced) | D-8; preamble ≤ 1,200, list ≤ 4,000 including the reserved summary; a line too long with its topics is shown without them; entries are pulled lazily |
| `NoteSelector` | sealed interface | `ByName(String name)`, `ByRef(String ref)` | exactly one of `name`/`ref` (D-9, D-10) |
| `NoteRefs` | utility | `NoteRefs(SecureRandom)` (32-byte HMAC key per process); `String of(Path relative)`; `Optional<NoteFile> resolve(String ref, Listing)` | `r1.` + base64url of 15 bytes of HMAC-SHA-256 (D-9), computed per RFC 2104 over `MessageDigest` (`javax.crypto` is outside the application's allowed packages; tested against `Mac`) |
| `KnowledgeService` | use case | `SearchPage search(Search)`; `NotePage read(NoteSelector target, int offset)`; `KnowledgeAnnouncement.State overview(Duration bound)` (prepares only when notes exist, D-7); `Recorded record(Record, Optional<String> client, String mcpClient)` | flow per D-9, D-10; all names in messages reduced (D-14) |
| `Shown` | utility (package-private) | `static String of(SecretFinder, String)`; `line(String)`; `cut(String)` | the D-8 reduction: masked, `\p{Cc}`, U+2028/9, U+0085 → space, white-space runs → one space, stripped, cut at 120 code points (the last one `…`) |
| `Search` | record | `Optional<String> query`, `Optional<String> topic`, `int limit`, `int offset` | limit 1–50, offset ≥ 0 |
| `SearchPage` / `Hit` | records | page: `hits`, `offset`, `limit`, `total`, `OptionalInt nextOffset`, `warnings`; hit: `name`, `ref`, `title`, `topics`, `lastChange`, `source`, `Optional<String> versionToken`, `Optional<String> excerpt`, `int masked`, `boolean writable`, `warnings` | D-9 |
| `NotePage` | record | `name`, `title`, `topics`, `source`, `Optional<String> lastWriter`, `lastChange`, `versionToken`, `int masked`, `int length`, `int offset`, `String content`, `OptionalInt nextOffset`, `warnings` | page 20,000 characters (D-9) |
| `Record` | record | `NoteSelector target`, `Mode mode`, `Optional<String> title`, `Optional<List<String>> topics`, `String content`, `Optional<String> versionToken` | `Mode` = `CREATE`, `APPEND`, `REPLACE`; `ByRef` only with `APPEND`/`REPLACE`; combination rules D-10 |
| `Recorded` | record | `name`, `mode`, `lastChange`, `versionToken`, `long sizeBefore`, `long sizeAfter`, `int historyVersions`, `String auditId`, `List<String> warnings` | D-10 step 8 |
| `KnowledgeLimits` | constants | `NOTE_BYTES = 262_144`, `TOTAL_BYTES = 104_857_600`, `READ_BYTES = 4_194_304`, `HISTORY = 10`, `LOCK_WAIT = 10 s`, `PAGE_CHARS = 20_000`, `EXCERPT_CHARS = 300`, `LINE_CHARS = 120`, `LIST_CHARS = 4_000`, `SEARCH_LIMIT_MAX = 50`, `WALK_DEPTH = 16`, `WALK_ENTRIES = 100_000`, `SIZE_WALK_ENTRIES = 1_000_000`, `START_PREPARE = 5 s` | referenced by docs test |

`application/SimilarNames` changes from package-private to public (no behaviour change).

## Adapters and wiring

| Type | Change | Members | Rules |
|---|---|---|---|
| `infra/SecretScrubber` | Changed | `+ List<SecretSpan> occurrences(String text)` | same matching as `scrub`; `scrub` uses it |
| `adapter/NodeSecrets` | Changed | `+ Settled awaitSettled(Duration)`, `+ Settled awaitSettledE2e(Duration)`, `+ String storeName()`; `Cell.Refusal` keeps the classified outcome | never throw; `Held`, `NothingConfigured`, `Waiting`, `Failed(FailureKind)` (D-7) |
| `adapter/ProfileSecrets` | New | implements `ConfiguredSecrets` over `Map<NodeId, NodeSecrets>` and the scrubber; `+ Duration timeout()` (longest store timeout, the tool-call bound) | parallel reads, bounded wait |
| `adapter/os/*PrivateFileSystem` | Changed | `othersMayAccess`, `openLockFileNoFollow` | POSIX / Windows (D-3) |
| `adapter/archive/v81/SecretRedactor`, `RepositoryArchive`, `SecretPaths` | Changed | import `domain.model.KeyMaterial` | behaviour unchanged |
| `application/PackageWriter` | Changed | `+ static Path defaultDirectory(Path home, String profile)` | used by `Wiring` and `ConfigValidator` |
| `Wiring` | Changed | `knowledgeService()`, tools in `toolHandlers()` | `record_knowledge` only with `recording()` |
| `Launcher` | Changed | knowledge paragraph at `serve`; `KnowledgeStatus` for `--check-config`; permission warning in `withPrivateObjects` | D-3, D-7, D-8 |
| `mcp/McpServerFactory` | Changed | `instructions()` appends the knowledge paragraph | after rendering, before the update notice |
| `mcp/ToolHints` | Changed | `+ localOnlyWrite(String title)` | `readOnly=false, destructive=false, idempotent=false, openWorld=false` |
| `mcp/tools/ReadKnowledgeTool`, `RecordKnowledgeTool` | New | schemas `read_knowledge.{input,output}.json`, `record_knowledge.{input,output}.json` | [contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md) |

## Audit record (`record_knowledge`)

| Field | Value |
|---|---|
| `capability` | `record_knowledge` |
| `node` | `-` |
| `group`, `account` | empty |
| `step` | `EXECUTE` |
| `inputs` | `name` (masked, one line, ≤ 120), `mode`, `sizeBefore`, `sizeAfter` |
| `outcome` | `REFUSED` (before writing) or `PENDING` → `EXECUTED` / `REFUSED` / `FAILED` |
| `reason` | error code and message (no content, title, topic, value or token) |
| `mcpClient` | from the call |

## State transitions of a note

```text
(absent) --create--> note v1 (block written, source = assistant (<client>))
note vN --append (token not needed)--> history += vN, note vN+1 = vN + section
note vN --replace (token = token(vN))--> history += vN, note vN+1 = new content
note vN --replace (token stale)--> CONFLICT, unchanged
note (hand-edited) --any write--> block added or updated, unknown keys kept
(no transition deletes or renames a note)
```
