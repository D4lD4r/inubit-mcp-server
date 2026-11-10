# Research: Local Knowledge per Profile

**Feature**: 019-local-knowledge | **Date**: 2026-11-10 | **Spec**: [spec.md](spec.md)

Base: `main` at the 0.8.0 release (42abe47) plus the spec commits of this branch. Baseline of
0.8.0: 4998 tests, 0 failures, 51 skipped (017 quickstart). The baseline of this branch is
recorded by T001 (tasks.md); this session could not run Maven (see plan "Validation status").

Sources: the spec, the code of 0.8.0 (paths below relative to
`src/main/java/de/dadecker/inubit/mcp/`), the recorded fixtures
`src/test/resources/fixtures/v8_1/artifacts/README.md` and
`docs/research/spike-development-deployment.md` §4.

---

## D-1 Configuration shape

**Decision**: one optional top-level block per profile file:

```yaml
knowledge:
  directory: ~/Notes/inubit/acme     # optional; default ~/.inubit-mcp/<profile>/knowledge
  recording: false                   # optional; default true
```

- `ProfileConfig` gets `KnowledgeConfig knowledge` (`Path directory`, `Optional<Boolean>
  recording`; `recording()` = `recording.orElse(true)`). `ConfigLoader` fills
  `knowledge.directory` with `<home>/.inubit-mcp/<profile.name>/knowledge` when it is missing,
  exactly as it fills `workspace` (`ConfigLoader:351-353`, `defaultWorkspace`), including the
  `NO_PROFILE_AUDIT_DIRECTORY` fallback for an invalid profile name (the server never starts with
  it).
- `knowledge.directory` uses the `Path` binding of `workspace` (`~` expansion; must be absolute
  afterwards, checked like `ConfigValidator.checkWorkspace`). `knowledge.recording` must be a YAML boolean (a strict check like
  `requireStrictUpdateCheck`, `ConfigLoader:617-627`): a quoted `"false"` is a load error, so
  recording is never left on by accident. Unknown keys in the block fail as everywhere
  (`FAIL_ON_UNKNOWN_PROPERTIES`).
- No environment variable or command-line switch: the setting is read at start (FR-012).

**Rationale**: mirrors `workspace` (feature 003) and `updateCheck` (feature 016), so operators find
it where the other per-profile directories are configured.

**Alternatives rejected**: a `defaults.knowledge` entry (knowledge is per profile, not per node);
an environment variable (other per-profile directories have none).

## D-2 Overlap rules (FR-002)

**Decision**: `ConfigValidator` gets `checkKnowledge(config, source, otherProfiles)`. All paths are
compared with the existing `canonical(Path)` (`ConfigValidator`, review M2: real path of the
nearest existing ancestor, the rest appended, lower-case on a case-insensitive file system). For
each forbidden path `P` the relation is *equal*, *inside* (`K.startsWith(P)`) or *contains*
(`P.startsWith(K)`); every relation is an **error**:

| Forbidden path | Source |
|---|---|
| this profile's `workspace` | `ProfileConfig.workspace()` |
| this profile's `auditDirectory` | `ProfileConfig.auditDirectory()` |
| `~/.inubit-mcp/<profile>/backups` | `BackupStore.defaultRoot(home, profile)` |
| `~/.inubit-mcp/<profile>/deployments` | `DeploymentLedger.defaultDirectory(home, profile)` |
| `~/.inubit-mcp/<profile>/packages` | the `deployments` sibling (`Wiring`, `PackageWriter`) |
| `~/.inubit-mcp` itself (only "equal" and "contains": it holds the other profiles) | home |
| `~/.inubit-mcp/<other>` for every other profile of the default configuration directory, and that profile's `workspace`, `auditDirectory` and `knowledge.directory` | `ConfigLoader.otherProfiles` (as for workspaces, `ConfigValidator:208-229`) |
| the default configuration directory and the folder of the configuration file in use | `ConfigLocation.roots()` |

- `config → application` (`BackupStore`, `DeploymentLedger`) is allowed by `PackageBoundaryTest`
  (only `config → adapter` is forbidden), so the defaults are not duplicated. The `packages`
  default moves into a static `PackageWriter.defaultDirectory(home, profile)` that `Wiring` uses
  as well.
- "Inside `~/.inubit-mcp/<profile>`" is allowed (it is the default), "equal to" or "containing"
  it is not (it contains the workspace and the audit directory, so the first rows report it).
  The allowance also holds where `~/.inubit-mcp/<profile>` itself lies inside a forbidden path
  (T009: a `--config` file in the home directory makes the home directory the folder of the
  configuration file; the default knowledge directory stays valid then).
- `ConfigValidator.withServerPaths(home, defaultConfigDirectory)` (T009) supplies home and
  configuration directory; `Launcher` always sets them. Without them (unit tests of other
  checks) only the configured directories of this and the other profiles are compared.
- A copy of the same profile (same profile name, the existing `copy` notion) shares the knowledge
  by design and gets the existing "same profile name" warning, not an error (two processes of one
  profile are expected, FR-015).
- Error texts name both paths as configured (`toAbsolutePath().normalize()`) and, for another
  profile, its file and profile name (printed only when valid, as today); the setting to change
  is named (`knowledge.directory`).
- The root of the knowledge directory may itself be a symbolic link (e.g. to a private synced
  folder): it is resolved for the comparison and the server works on the resolved path. Links
  *inside* the directory are not followed (D-9).

**Rationale**: FR-002 lists exactly these directories; reusing `canonical` gives the same
link and case behaviour as the workspace check of feature 003.

## D-3 Permissions, warnings and the `--check-config` line (FR-003)

**Decision**:

- Creation: the knowledge directory (and missing parents, e.g. `~/.inubit-mcp/<profile>`), note
  subdirectories, `.history` and its subdirectories are created with
  `PrivateFileSystem.createDirectories`; notes, history versions and the lock file with
  `PrivateFileSystem.createFile` (owner-only, on Windows the 013 ACL). Nothing is created at
  start or by reading (spec edge case "Missing directory").
- A `replace` or `append` writes a new owner-only file and moves it over the note (D-12), so a
  hand-written note that was group-readable becomes owner-only after the assistant wrote it.
  Directories that exist keep their permissions (FR-003). `docs/setup.md` says so.
- New port method `PrivateFileSystem.othersMayAccess(Path) : Optional<String>`: on POSIX the
  permission bits of an existing directory (`group`/`others` with `r` or `w`, as FR-003 says →
  e.g. "readable by group and others"; `x` alone is not reported); on Windows it delegates to `check(path)` (013) and
  joins its findings. Empty if the path does not exist. `Launcher.withPrivateObjects` adds one
  warning `The knowledge directory <path> is <finding>; run chmod 700 <path> (permissions are
  not changed by the server)` (Windows: the 013 wording). The existing `check` keeps its
  behaviour, so the audit-directory warnings of 0.8.0 do not change on POSIX.
- `--check-config` prints, after the `Workspace:` line (`ConfigSummary:119-126`):
  `Knowledge: <path> (ok | absent | <problem>), recording on|off, <n> notes`.
  `<problem>` is `not a directory` or `not readable`; FR-002 violations are not repeated on this
  line (they are in the error list). `<n>` counts notes as `read_knowledge` lists them (D-9), `0` when
  absent. The line is computed by `Launcher` through `KnowledgeDirectory.status()` and passed to
  `ConfigSummary` as a `config.KnowledgeStatus` value (record: `Path path, State state,
  Optional<String> problem, boolean recording, int notes`), like `EntryCheck.Result`; no note
  name or title is printed and no credential is read (the count needs no masking).

## D-4 Note names (FR-004, FR-004b, FR-004c)

**Decision**: `domain/model/NoteName` (pure Java):

- A **note** is a regular file (attributes read with `NOFOLLOW_LINKS`) whose file name ends in
  the lowercase `.md`, in the knowledge directory or a subdirectory, where no path segment is
  hidden. Hidden = the segment starts with `.` or `Files.isHidden` is true (Windows hidden
  attribute). Its **name** is the relative path with `/` separators, without `.md`, NFC-normalized.
- **Record rule** (`NoteName.writable(String)`): 1–4 segments split at `/`; each segment 1–100
  code points of `\p{L}`, `\p{N}`, `_`, `.`, `-` and space (U+0020); not `.` or `..`; not starting
  with `.` (it would be hidden; the spec's segment rule allows `.` inside, the hidden rule makes a
  leading `.` invisible, so it is refused) and not ending with `.` or space (Windows drops them); the
  part before the first `.` is not a Windows device name (`CON`, `PRN`, `AUX`, `NUL`, `COM0`–`COM9`,
  `LPT0`–`LPT9`, also with superscript digits, compared case-insensitively); no segment ends in
  `.md` in any letter case (a folder `x.md` would clash with the file of note `x` and nest the
  histories, D-11). The name is NFC-normalized before the check.
- **Identity**: lookups (read, `append`, `replace`) compare the NFC name exactly against the
  listed names (`NoteName.equals` is code-point equality after NFC). `create` collides when
  `fold(a).equals(fold(b))` with `fold(s)` = NFC, then `toUpperCase(ROOT)` followed by
  `toLowerCase(ROOT)` **repeated until the string no longer changes** (at most four rounds;
  `ẞ` → `ẞ` → `ß` → `SS` → `ss`), then NFC again. Java has no Unicode case folding, and the plan
  adds no library for it (the constitution keeps dependencies minimal; ICU4J would be 13 MB for one
  comparison). A single upper-then-lower round misses `ẞ` (U+1E9E) vs `ß`/`SS`; the fixpoint
  closes that. The fixpoint is designed to be **at least as coarse** as full case folding of
  `CaseFolding.txt` (C+F) for the letters the name rule allows: every C/F mapping of the table
  maps a character to a string that Java's upper/lower mappings also reach, while some letters fold
  together that the table keeps apart (dotless `ı`, dotted `İ` and `i`). A false collision costs a
  `CONFLICT` for a name a person would hardly tell apart anyway; the occupancy check against the
  real directory listing (D-10) still catches whatever the file system itself treats as equal.
  `NoteNameTest` reads a test resource with the C and F lines of `CaseFolding.txt` (Unicode
  15.1, the version of Java 21's character data; the Unicode License notice kept in its header —
  the JDK does not ship the file) for `\p{L}` and asserts `fold(source) == fold(target)` for each; a mismatch fails
  the build instead of going unnoticed. Recorded in the spec's plan decisions as the precise
  meaning of "case folding" in FR-004b. Named tests: `ß`/`ẞ`/`SS`/`ss`, `Σ`/`ς`/`σ`, `ſ`/`s`,
  Kelvin sign `K`/`k`, `ı`/`i`, `İ`/`i`, NFD/NFC `Störung`.
- **NOT_FOUND suggestions**: the case-insensitive matches (`fold` equal) first, then
  `SimilarNames.of(name, names, 5)` (made `public`, it moves from package-private; behaviour
  unchanged), at most five, each masked and cut as in FR-006 (D-8).
- **Hand-written names outside the rule** are notes as well (listed with the warning `the
  assistant cannot write to this note (name outside the record rule)`, searched, readable).
  `append`/`replace` on them: `INVALID_INPUT` (the name check runs first).

**Rationale**: the rule follows the spec; the leading-dot and trailing-dot/space refinements avoid
names that would be invisible or renamed by Windows. They are within "letters, digits, `_ . -`
and spaces" and only exclude names the server could not later list as written.

## D-5 Metadata block (FR-004, FR-004a, FR-004d)

**Decision**: a front-matter block, the first line exactly `---`, closed by the next line exactly
`---`, within the first 64 lines and 8 KiB. Inside, one entry per line `key: value`; empty lines
and lines starting with `#` are allowed. Keys (case-sensitive):

| Key | Value | Written by the server |
|---|---|---|
| `title` | one line | always (create: required input; append/replace: kept unless given) |
| `topics` | flow list `[a, b]` of single-line strings | always (`[]` when none) |
| `source` | `by hand` or `assistant (<client>)` | set on create; kept afterwards |
| `lastWriter` | as `source` | set on every write |
| `lastChange` | ISO-8601 instant (informative, FR-004a) | set on every write |

- Values may be plain, `"double-quoted"` (escapes `\"`, `\\`, `\n` not allowed — a value is one
  line) or `'single-quoted'` (`''` for `'`). Flow-list items may be plain or quoted the same way.
  The server writes every string **single-quoted** (`'` doubled), which represents every one-line
  string without control characters — `"` and `\` stay literal — so whatever the server accepts
  (title, topics, the client name reduced to one line) is read back unchanged. Round-trip tests:
  quotes, apostrophes, backslashes, `#`, `:` and `---` inside values.
- **Block bound on write**: the complete block the server is about to write (server keys plus
  preserved unknown keys and comments) is checked against the parser's bounds (64 lines, 8 KiB)
  before publication; if it would exceed them → `PRECONDITION_FAILED` "the metadata block of the
  note would exceed 64 lines or 8 KiB; shorten it by hand", nothing written. The server keys alone
  stay far below (worst case about 6 KiB with four-byte characters and doubled quotes). Tests at
  63/64/65 lines and 8 KiB ± 1 byte.
  Unknown keys are preserved in order on a write and otherwise ignored, so the operator can add
  own fields.
- **Malformed** (FR-004d): an opening `---` without a closing one within the bounds; a line that
  is not `key: value`, an empty line or a comment; a duplicate known key; `topics` that is not a
  flow list; a control character in a value. A malformed note is listed (title = file name,
  warning `metadata block is malformed; fix it by hand`), searched over its whole text, readable
  (content = the whole text, metadata empty); `append`/`replace` → `PRECONDITION_FAILED`.
- No block: title = the last name segment, topics empty, source `by hand`, last writer absent.
  A write adds the block (D-10).
- **Input bounds of the record tool** (the spec leaves them to the plan): `title` 1–200 code
  points, one line, no control characters; `topics` 0–20 items, each 1–60 code points, one line,
  no control characters, no `,` `[` `]` (so the flow list stays parseable without quoting
  rules the operator must learn), unique after `fold`. The JSON schema carries these bounds.
- **Exposed metadata**: every metadata value that leaves the server (`title`, `topics`,
  `source`, `lastWriter`) goes through the same pipeline as names (masked, one line, cut at 120,
  D-8), in hits, pages and instructions; unknown keys and the informative `lastChange` value are
  never returned. A hand-written `source`/`lastWriter` that is neither `by hand` nor
  `assistant (…)` is returned masked as written.
- **Body** = the text after the block (or the whole text without a block). Search excerpts and
  read pages use the body; name, title and topics are separate fields.
- **Client name** in `source`/`lastWriter`: `CallContext.client()` (`name` of `clientInfo`),
  passed by the tool handler to the service as a plain `Optional<String>`, reduced to one line, cut at 60 characters, masked; `unknown client` when absent.

**Rationale**: front matter is what Markdown editors and note tools (Obsidian, static site
generators) already show and keep; the restricted grammar lets `application` parse it without a
YAML library (the package may use only the JDK, `domain` and SLF4J).

**Alternatives rejected**: a sidecar file per note (two files to keep in sync by hand; editors do
not move it with the note); full YAML via Jackson (not allowed in `application`, and a full YAML
grammar gives hand-written notes many more ways to be "malformed").

## D-6 Secret rules (FR-010 (b)–(d)), masking and write findings

**Decision**: `application/knowledge/SecretFinder` returns `List<Finding>` for a text; a
`Finding` is `Kind kind, int start, int end, int line, Optional<String> key` (`key` only for
assignments). Kinds: `CONFIGURED_CREDENTIAL` (a), `KEY_MATERIAL` (b), `INUBIT_ENCRYPTED` (c),
`ASSIGNMENT` (d). Masking (`Masked mask(String)`) merges overlapping and adjacent spans and
replaces each merged span by `***` (`SecretScrubber.MASK`, re-declared as
`Masked.MASK_TOKEN` in the domain with a test that both are equal) and returns the masked text
and the number of merged spans (`masked`). The write check reports every finding as kind plus
field or line, never the value.

- **(b) Key material** — the existing rule, moved, not copied: `adapter/archive/v81/KeyMaterial`
  (package-private) moves to `domain/model/KeyMaterial` (public; `isKeyMaterial(byte[])`,
  `decodeDocument(String)`, `hasKeyName(String)`), and `SecretPaths.longBase64` moves with it as
  `KeyMaterial.longBase64`. The archive adapter imports it from the domain (allowed:
  workspace adapters may use `domain`); its tests move along (`KeyMaterialTest` new for the moved
  helpers; `SecretRedactorTest`, `RepositoryArchiveTest` stay green unchanged). In free text:
  - a PEM block: from a line containing `-----BEGIN` and `PRIVATE KEY-----` to the next line
    containing `-----END` and `PRIVATE KEY-----` (or the end of the text) is one span;
  - a base64 block: maximal runs of the base64 alphabet (`A–Z a–z 0–9 + / =`) delimited by any
    other character; consecutive lines that consist only of such a run (MIME-wrapped, optionally
    indented) form one block; a block of at least 64 base64 characters whose
    `KeyMaterial.decodeDocument` content `isKeyMaterial` is one span. Runs are scanned with a
    loop, not a regular expression (the stack issue noted at `SecretPaths.longBase64`).
- **(c) INUBIT encrypted values** — confirmed against the recorded exports
  (`fixtures/v8_1/artifacts/README.md`, table at lines 136-141 and the value list at 166-216):
  - `AES-` + 24 base64 characters (`AES-U1lOVEgtQUVTLTAwMDAwMDAx`, module-0022 `Password`,
    module-0020 `var.userPassword`);
  - `AESG` + base64 parts separated by `-`, `+` and `:`
    (`AESGs1-y+U1lOQUVTRzAwMDAwMDE:U1lOVEhJVjE:…==`, module-0024 `Mime.Decrypt.Password`);
  - **further form found**: the older non-AES encoding, 12 base64 characters
    (`U1lOMDAwMDA2`, module-0013, 0022), which cannot be told apart from an ordinary word. It only
    occurs as the content of a property marked `encrypted="true"`, so the rule adds: the content
    of an XML element that carries `encrypted="true"` or `type="Password"` (attribute order free,
    either quote) is a secret, up to its matching closing tag (across lines; without one, to the
    end of the text — fail closed).
  Token rule: `(?<![A-Za-z0-9+/=])(AES-|AESG)` followed by a run of `[A-Za-z0-9+/=:-]` that
  holds at least 16 base64 characters; the whole token is one span. No `{AES}`, `ENC(` or
  `!enc!` form occurs in the recordings; none is added (research of 017 found none either).
- **(d) Assignments** — the key words `password`, `passwd`, `pwd`, `secret`, `token`,
  `passphrase`, `credential`, `apikey`, case-insensitive substring of the key. Recognized forms
  (one scanner, line by line, plus an XML pass):
  - plain or YAML/properties: a key `[\p{L}\p{N}_.\-]+` directly before `:` or `=` (spaces
    allowed around the separator; a list dash or a Markdown bullet before the key allowed);
  - JSON/quoted key: `"key"` or `'key'` followed by `:` or `=`;
  - XML attribute `key="…"`/`key='…'` (covered by the two forms above: the value is quoted);
  - XML element whose element name contains a key word (`<dbPassword>…</dbPassword>`) or that
    has an attribute `name="…"` containing a key word (`<Property name="Login.Password">…`):
    the content up to the matching closing tag of that element, **across lines**; a missing
    closing tag masks to the end of the text (fail closed); a self-closing element has no
    content. Tests cover each XML form with the value on its own line(s), a CDATA value and a
    missing closing tag.
  Value extent: from the first non-space character after the separator; a quoted value (`"` or
  `'`) ends at the matching unescaped quote (or the end of the line); otherwise at the end of the
  line. Exempt: an empty value, the value `***`, a value that is a `${secret:…}` placeholder
  (`domain/model/SecretPlaceholder`, already reachable from `application`), **YAML block scalars**: if the value is only a block indicator (`|` or `>`, optionally with
  chomping/indentation indicators such as `|-`, `>+2`), the value is the block that follows: every
  following line that is empty or indented deeper than the key's line, up to the first non-empty
  line that is not; that block is one span (empty block → exempt). Tests cover `|`, `>`, `|-` and
  an empty block.
  The span covers the value only, never the key (the key stays readable, and write findings name
  it, cut at 40 characters and masked).
- **Determinism and bounds**: the finder runs on at most 4 MiB of text: notes are read only up to
  the 4 MiB read bound (FR-013), and record input larger than the 256 KiB note bound is refused in
  D-10 step 1, before any credential is read or text is scanned. It is linear in the text length
  apart from the base64 decoding, which is bounded by the block size.

**Rationale**: (b) reuses the export rule as FR-010 requires; (c) is the exact recorded shape plus
the one further form the recordings show; (d) follows the spec's grammar literally, including the
XML forms INUBIT exports use (`<Property name="…Password">`).

## D-7 Configured credentials (FR-010 (a)) — option (A), confirmed by the person on 2026-11-10

**Problem**: rule (a) needs the profile's configured credential values, but since feature 011
(research R-10) no credential is read at start; every node reads its credentials on first use from
the credential store (possibly with an access dialog, up to 60 s) or from AWS Parameter Store
(network, 10 s). Values not read yet are unknown to the process, so a hand-written note holding
such a value would be shown unmasked, and the global scrubber in `ResultMapper` would not catch it
either — a breach of Principle II and SC-002.

**Decision** (fail closed):

- New port `domain/port/ConfiguredSecrets`:
  - `Readiness prepare(Duration bound)` — starts or joins the reads of every node's main
    credentials and end-to-end credentials (`NodeSecrets.current`, `NodeSecrets.e2e`), all nodes in
    parallel (each on a virtual thread, as `NodeSecrets` already reads), and waits until all are
    settled or `bound` is over. `Readiness` is `READY` or `NOT_READY(List<Pending> nodes)` where a
    `Pending` names the node, the store kind and `waiting` or the failure kind;
  - `List<Span> occurrences(String text)` — the spans of every value registered with the
    scrubber (all spellings of `SecretForms`), from the new `SecretScrubber.occurrences(String)`
    which shares `scrub`'s matching loop (`scrub` becomes `mask(occurrences)`; behaviour unchanged).
- "Settled" per read: **held** (values registered), or **failed with `NOT_FOUND`** for every
  wanted name (nothing configured, so nothing to mask), or an end-to-end read that is empty
  (no end-to-end entries). Every other failure (`TIMEOUT`, `UNREACHABLE`, `ACCESS_DENIED`,
  `STORE_NOT_AVAILABLE`, `INVALID_VALUE`, …) means not ready. To tell them apart without parsing
  messages, `NodeSecrets` gets `Settled awaitSettled(Duration bound)` /
  `awaitSettledE2e(Duration bound)` that never throw and return `Held`, `NothingConfigured`,
  `Waiting` or `Failed(FailureKind)` from the same shared read. This needs a refactor of `Cell`
  (`NodeSecrets:128-150, 548-566`): today the back-off keeps only `Refusal(ToolError, Instant)`
  and the classified outcome lives in the transient `Failed`; `Refusal` gains the `FailureKind`
  (or "nothing configured") so that a caller during the back-off gets the classification too.
  Existing behaviour of `current`/`e2e` is unchanged (covered by `NodeSecretsTest`); the private
  method `Cell.settle` keeps its name, the new public ones are named `awaitSettled*` to avoid
  confusion.
- Implementation `adapter/ProfileSecrets` (adapter package: it uses `NodeSecrets` and the
  `SecretScrubber`), built in `Wiring` from the existing `secrets` map.
- **Tool calls** (`read_knowledge`, `record_knowledge`) call `prepare(<the longest store timeout
  of the profile's nodes>)` (60 s with any credential store node, else 10 s; a profile without nodes
  is `READY` at once). Not ready → `PRECONDITION_FAILED`: "The credentials of <nodes> have not been
  read yet (<store>: <waiting|kind>); notes are shown only after all of the profile's credentials
  are known, so that a password written in a note is masked." Next step: "Answer the credential
  store's access dialog (or restore access to AWS Parameter Store), then call again." Within the
  30 s back-off of a failed read the refusal comes at once.
- **Server start** (instructions, D-8): only if the knowledge holds at least one note, `Launcher`
  calls `prepare(5 s)` before the instructions are built. Ready → the note list. Not ready → the
  instructions give the count and say the list is withheld until the credentials are read, and
  point to `read_knowledge` (FR-006's "if cut" path). The reads go on in the background; the
  first tool call joins them.
- The masking of hits and pages, the version-token hash, the write check and the masking of names
  in audit, logs and errors all use the same `SecretFinder` with (a) included.

**Consequences (accepted by the person with option (A))**:

1. The first knowledge call of a session reads the credentials of **every** node of the profile,
   so the access dialogs of nodes the session never contacts may appear. With knowledge notes
   present, this happens at server start (bounded by 5 s; the dialogs stay open and the reads
   continue). This changes 011's "no credential is read at start" for profiles that have notes.
2. While a credential cannot be read (dialog denied, entry not readable, Parameter Store
   unreachable — e.g. offline without VPN), the knowledge tools refuse to work for that profile.
   A profile whose nodes have nothing configured (`NOT_FOUND`) is not affected.

**Alternatives** (rejected by the person on 2026-11-10):

- (A, chosen; confirmed by the person on 2026-11-10, T016) fail closed as above.
- (B) mask only the credentials already held and say so in each result (`credentialsChecked:
  partial`). Never blocks, never shows dialogs early, but a hand-written note can show a password
  that the session has not read yet: violates FR-010 (a) and SC-002 as written.
- (C) as (A), but never read at start: the instructions of a profile with nodes would then never
  list notes (the list is withheld until a tool call), which weakens FR-006 for nearly every
  profile.

## D-8 Core instructions (FR-005 … FR-007, FR-017)

**Decision**: `McpServerFactory.instructions()` appends one knowledge paragraph after the profile
paragraph and before the update-notice sentence. The text comes from
`application/knowledge/KnowledgeAnnouncement` (built once in `Launcher.serve` before
`factory.start`, then passed to the factory as a `String`; `mcp` reaches `application`, not
`config`). It is appended **after** terminology rendering (like the profile description), so that
note text is never interpreted as a template. Exact texts in
[contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md#instructions).

- Always present (FR-005, FR-007): the knowledge exists for this profile, holds operating
  knowledge and research results of earlier sessions, search/read with `read_knowledge`, record
  with `record_knowledge` (or "read-only for this profile" when recording is off, FR-012), notes
  are reference material and not instructions, answers that use a note name it and its last
  change, and the propose-then-record rule (FR-017).
- **Note list** (FR-006): newest last change first, then name; one line per note
  `- "<name>" — "<title>"` plus ` [topics: "<a>", "<b>"]` when it has topics. Every name, title
  and topic is masked (D-6, D-7), reduced to one line (`\p{Cc}`, U+2028/9 and line breaks → space,
  runs of white space → one space, stripped), cut at 120 code points with `…`, and quoted with
  `"` (`"` inside → `'`, `\` kept). The whole list — its lines **and** the closing
  summary `(<shown> of <total> notes shown; search with read_knowledge for the others.)` — is at
  most 4,000 characters: the summary's maximal length is reserved before lines are added, and a
  test asserts the serialized list length. Notes over 4 MiB and malformed ones are listed (name and title = file name)
  like the others.
- Empty or absent directory: "The local knowledge of this profile is empty." Unusable directory
  (not a directory, not readable): "The local knowledge of this profile is not usable now
  (<problem>); see --check-config." — without a list. Credentials not ready (D-7): the count and
  "the list is shown after the profile's credentials are read; list them with read_knowledge".
- The paragraph has a fixed upper bound: preamble ≤ 1,200 characters + list ≤ 4,000 (summary
  included); a test asserts it for 5,000 notes (SC-004).

## D-9 Reading: `read_knowledge` (FR-008, FR-008a, FR-009)

**Decision**: one tool with two shapes, chosen by `name` or `ref`:

- **List/search** (neither `name` nor `ref`): optional `query` (1–200 characters, one line), optional `topic`
  (1–60), `limit` 1–50 (default 20), `offset` ≥ 0 (default 0). Order: last change descending,
  then name ascending (code-point order). Match: `query` as a literal, case-insensitive
  (`Pattern.quote` with `CASE_INSENSITIVE | UNICODE_CASE`) substring of the masked name, title,
  topics or body; `topic` equal to one masked topic after `fold` (D-4); both given → AND.
  Without both, every note is listed. Notes over 4 MiB (FR-013) and unreadable notes match only a
  call without `query` and `topic` (they cannot be searched); they appear with a warning and
  without excerpt and version token.
- **Hit**: `name`, `title`, `topics`, `lastChange` (ISO-8601 UTC, seconds), `source`,
  `versionToken`, `excerpt` (≤ 300 characters of the masked body: from 100 characters before the
  first body match, else the body start; line breaks → space; never splitting a surrogate pair),
  `masked` (count in the whole note), `writable` (name follows the record rule, contains no
  secret, and metadata is well-formed), `ref`, `warnings` (per note). Names, titles and topics as in D-8 (masked, one line,
  cut at 120) but not quoted (they are JSON strings).
- **Note reference `ref`** (`NoteRefs`; FR-004: every listed note stays readable): the displayed `name` is
  masked and cut and therefore not always the exact name. Every hit carries `ref` =
  `r1.` + base64url of the first 15 bytes of HMAC-SHA-256(per-process random key, exact relative
  path) — 23 characters, stable for the life of the server process, revealing nothing about the
  name (no secret can be recovered from it). `read_knowledge` and `record_knowledge`
  (`append`/`replace`) accept `ref` instead of `name` (exactly one of the two → else
  `INVALID_INPUT`); a `ref` resolves against the current listing, an unknown one → `NOT_FOUND`
  "list the notes again" (e.g. after a restart). Written results and audit records carry the
  masked name, never the `ref` alone.
- **Duplicate names**: two files whose names are equal after NFC (e.g. an NFD and an NFC file on
  Linux) are both listed, each with its own `ref` and the warning `another file has the same name
  in another Unicode form; use ref`; a lookup by that `name` → `CONFLICT` "several notes have this
  name; use ref". Order between them: code-point order of the raw path.
- **Page result**: `notes`, `offset`, `limit`, `total`, `nextOffset` (absent on the last page),
  `warnings` (directory-level: symbolic links ignored, files skipped, walk bounds reached; at most
  20 lines then `… and <n> more`).
- **Read** (`name` or `ref` given — `NoteSelector.ByName`/`ByRef`; `query`/`topic`/`limit` →
  `INVALID_INPUT`): `offset` is a character
  offset into the masked body (code units, adjusted down to a code point boundary). Returns
  `name`, `title`, `topics`, `source`, `lastWriter`, `lastChange`, `versionToken`, `masked`,
  `length` (masked body length), `offset`, `content` (≤ 20,000 characters **including** the
  marker), `nextOffset` when cut, in which case `content` ends with `ItemBounds.TRUNCATION_MARKER`
  (`…[truncated]`; the page then holds 20,000 minus the marker's length of note text), and
  `warnings`. Lookup per D-4; missing → `NOT_FOUND` with up to five similar names; over 4 MiB or
  unreadable → `PRECONDITION_FAILED` naming the note (masked) and the bound or cause.
- **Walk**: `Files.walkFileTree` without `FOLLOW_LINKS`, skipping hidden directories (and so
  `.history`), max depth 16 and at most 100,000 entries (beyond: a warning, the rest ignored);
  symbolic links (file or directory) give a directory-level warning `symbolic link <name> is
  ignored` (name masked). No cache: every call walks and reads again (FR-009).
- **Version token** (FR-011): `v1.` + base64url (no padding) of the first 18 bytes of
  `SHA-256(<mtime as epoch seconds and nanos at the file system's precision> "\n" <masked whole
  file text>)`. It reveals nothing about masked values (they are masked before hashing) and changes
  when either the time or the content changes.
- **Logs**: DEBUG `knowledge.read` with `notes`, `hits`, `name` (masked, cut) — never `query`,
  `topic` or content (FR-019).

**Rationale**: the spec allows at most two tools (FR-018); "read by name" and "list/search" share
the masking, walk and ordering, and a single schema keeps the tool count at two.

## D-10 Recording: `record_knowledge` (FR-011 … FR-013, FR-016, FR-017)

**Decision**: input `name` or `ref` (D-9), `mode` (`create` | `append` | `replace`), `title`,
`topics`, `content`, `versionToken`. Required: `mode`, `content` and exactly one of `name`/`ref`
(`ref` only with `append`/`replace`); `create` also `title`; `replace` also `versionToken`; `versionToken` with another mode → `INVALID_INPUT`. The input schema carries only structural
rules (D-13); `content` must not be empty, its size is bounded by the 256 KiB note bound. Steps in
order:

1. **Combination rules** (mode/field presence, D-5 bounds of title/topics, empty `content`, a
   malformed `versionToken`) → `INVALID_INPUT`; raw input that cannot fit the note bound
   (`content` alone longer than 262,144 UTF-8 bytes) → `PRECONDITION_FAILED` naming the bound.
   These messages name the field and the rule only, never a value.
2. **Credentials ready** (D-7) → else `PRECONDITION_FAILED`.
3. **Secret check** of the new text (FR-013): `name` always; `create`/`replace`: title, every
   topic, content; `append`: the section and a given title/topics. Any finding →
   `INVALID_INPUT` with one line per finding: `<kind> in name|title|topics|content line <n>`
   (plus `(key <key>)` for assignments); never the value.
   With `ref` instead of `name`, the name is not input; it is checked in step 4.
4. **Target**: with `name`, the name rule (D-4, `.md` suffix) → `INVALID_INPUT` naming the
   violated rule and the segment number, not the name. With `ref`, the ref is resolved against a
   fresh listing (unknown → `NOT_FOUND` "list the notes again"); the resolved name gets the secret
   check of step 3 and the name rule, a failure → `PRECONDITION_FAILED` "this note cannot be
   written by the tool (`writable` is false)". From here on the call knows the exact masked name,
   so the `PENDING` record (step 5) always names the note.

   Until step 3 has passed, the audit record and any log line carry the name as `***` (before
   the credentials are known, a name cannot be masked reliably); afterwards the masked name
   (D-13). This order also means an invalid name is refused only after the credentials were read.
5. **Audit `PENDING`** (D-13); failure → `INTERNAL`, nothing written.
6. **Lock** (D-12), at most 10 s → `PRECONDITION_FAILED` "another write is in progress".
7. Under the lock, on the current state:
   - `create`: a listed note whose name collides by `fold` → `CONFLICT` naming it (masked); the
     target path or a parent segment exists as anything other than a directory for a parent / a
     free path for the file (a file with another case of `.md`, a directory, a symbolic link,
     checked with `NOFOLLOW_LINKS` segment by segment, also case-insensitively against the
     directory listing) → `PRECONDITION_FAILED` naming the occupying path (relative, masked).
   - `append`/`replace` by `ref`: the `ref` is resolved again under the lock; if it no longer
     resolves to the path found in step 4 → `NOT_FOUND` "the note was moved or removed; list the
     notes again".
   - `append`/`replace`: not listed → `NOT_FOUND` (similar names); a `name` shared by two files
     in different Unicode forms → `CONFLICT` "use ref"; (a name outside the record
     rule never gets here: step 4 refuses it); over
     4 MiB or unreadable → `PRECONDITION_FAILED`; malformed metadata → `PRECONDITION_FAILED`
     (FR-004d); parent segment or file is a link → `PRECONDITION_FAILED`.
   - `replace`: current token ≠ given → `CONFLICT` naming the current last change; the current
     text has a finding (any kind) → `PRECONDITION_FAILED` "remove the secret by hand first"
     (checked before the token so the operator learns it at once).
   - New text: `create` → block (title, topics, `source` and `lastWriter` = `assistant
     (<client>)`, `lastChange` = now) + `\n` + content; `append` → current text with the block
     updated (`lastWriter`, `lastChange`, a given title/topics) + (`\n` if the body does not end in
     a line break) + `\n` + section; `replace` → updated block (title/topics kept unless given,
     `source` kept, or `by hand` if the note had no block) + content. Line breaks are written as
     given; the server adds `\n` only.
   - Bounds: the new file > 256 KiB (UTF-8 bytes) → `PRECONDITION_FAILED` naming the bound
     and the size; the knowledge directory total after the write (all regular files, history
     included, links not followed, the lock file excluded) > 100 MiB → `PRECONDITION_FAILED`.
     The total is computed by a walk on every write that is not depth-limited; it stops after
     1,000,000 entries and then **fails closed** (`PRECONDITION_FAILED` "the knowledge directory
     is too large to measure"), so files beyond the listing walk's limits (D-9) still count.
   - Internal paths (D-12): every segment of the note's parent, `.history` with its segments and
     `.lock` are checked with `NOFOLLOW_LINKS`; an existing one that is a link or of the wrong type
     → `PRECONDITION_FAILED` naming it. Missing directories are created one segment at a time
     (`PrivateFileSystem.createDirectories` on a single segment whose parent was just checked), never
     through `createDirectories` of a deep path (it follows links).
   - Write (D-11, D-12): (i) `append`/`replace` copy the current file to a **new** history version
     (nothing existing is touched); (ii) publish the new note atomically — this is the **commit
     point**; (iii) only then prune the history to 10. Before the commit point every failure
     undoes (i) and leaves note and history as they were (`INTERNAL`, audit `FAILED`; if even the
     undo fails, the extra version stays and the error says so). After the commit point no
     failure turns the call into an error: the note is written and the result is a success whose
     `warnings` name what failed (pruning — the history then holds more than 10 versions until the
     next successful write prunes it; the final audit record, D-13); `historyVersions` reports the
     versions actually kept.
8. **Result**: `name`, `mode`, `lastChange`, `versionToken` (of the new file), `sizeBefore`
   (bytes; 0 for create), `sizeAfter`, `historyVersions` (kept for this note), `auditId`,
   `warnings` (e.g. the final audit record could not be written, D-13). Audit outcome
   `EXECUTED`.

- **Never deletes a note** (FR-011): no mode removes or renames a note file.
- **Annotations** (FR-016): `readOnlyHint=false`, `destructiveHint=false`,
  `idempotentHint=false`, `openWorldHint=false` (no INUBIT contact; `ToolHints.localWrite` sets
  `openWorld=true` for `export_archives`, so a new factory `ToolHints.localOnlyWrite(title)` is
  added). No confirmation code.
- **Description** (FR-017) repeats the propose-then-record rule (exact text in the contract).

## D-11 History (FR-014)

**Decision**: `<knowledge>/.history/<note path>.md/<stamp>.md`, e.g. the note `partners/acme` →
`.history/partners/acme.md/20261110T101500.123456789Z-000.md`. `<stamp>` is the write time in UTC
with nanoseconds, followed by a three-digit counter: **one more than the greatest counter of a
matching file with the same instant** (`-000` if none); gaps left by pruning are never reused, so
a new version always sorts newest; beyond `-999` → `PRECONDITION_FAILED`. Order is by the
**parsed** (instant, counter), not lexically; files that do not match the pattern are never
pruned. After adding, the versions of that note beyond the 10 newest are deleted (oldest first).
A test writes twelve versions within one clock instant (fixed clock) and asserts the contents of
the ten kept. Created owner-only
(D-3). The `.history` tree is hidden, so walk, search, instructions and the note count ignore it,
but it counts towards the 100 MiB bound. `docs/setup.md` documents where it is and how to restore
by hand (copy a version over the note).

**Rationale**: one directory per note keeps pruning local and lets the operator find versions by
the note's path; the `.md` suffix of the directory keeps the history of a note `a` apart from the
notes in a directory `a/`.

## D-12 Serialization and atomic writes (FR-015)

**Decision**: `application/knowledge/KnowledgeLock`:

- In-process: a `ReentrantLock` per knowledge directory, `tryLock(remaining)`.
- Across processes: `<knowledge>/.lock` (created owner-only, never deleted), `FileChannel.tryLock()`
  polled every 50 ms until 10 s after the call's start (the 10 s cover both steps). The JVM throws
  `OverlappingFileLockException` for a second lock on the same file from one process, so the
  in-process lock is taken first and the file lock is held by one thread at a time.
- Timeout → `PRECONDITION_FAILED` "another write to the local knowledge is in progress; try again
  in a moment"; nothing written. No retries inside the server.
- **Atomic write**: a temporary file `.<file name>.<uuid>.tmp` in the target directory (hidden,
  ignored by the walk), created owner-only, written and `force`d, then published:
  - `append`/`replace`: `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`;
  - `create`: `Files.createLink(target, tmp)` and then delete `tmp`. A hard link is created
    atomically and **fails if the target exists** (`link(2)` `EEXIST`, Windows `CreateHardLink`),
    so a file the operator creates in the same instant is never replaced (FR-004b) —
    `FileAlreadyExistsException` → `CONFLICT`. The new note shares the temporary file's inode, so
    it is owner-only (POSIX bits and the Windows ACL belong to the file, not the name).
  If the file system cannot do the needed primitive (`AtomicMoveNotSupportedException`,
  `UnsupportedOperationException` or a `FileSystemException` from `createLink`, e.g. FAT or some
  network shares), the write fails with `PRECONDITION_FAILED` "the file system of the knowledge
  directory cannot write notes atomically" — no non-atomic fallback. A test injects a file at the
  target between the occupancy check and publication (a hook in `KnowledgeDirectory`) and
  asserts `CONFLICT` and the injected file unchanged.
- **Links on internal paths**: `.lock` is created owner-only with `PrivateFileSystem.createFile`
  and opened with a new port method `PrivateFileSystem.openLockFileNoFollow(Path)`: as
  `openLockFile` (Windows: `NOSHARE_DELETE`, so the lock file cannot be deleted and recreated
  while held) plus `LinkOption.NOFOLLOW_LINKS`; a link there fails with `FileSystemException` →
  `PRECONDITION_FAILED`. The existing `openLockFile` is unchanged (workspace locks). `.history`
  segments and parent segments are checked as in D-10 step 7, and missing segments are created
  one at a time with `createDirectory` (which never follows a link at the last segment: a link
  there fails with `FileAlreadyExistsException`).
- **Threat model**: the knowledge directory is private to the operating-system user (FR-003
  warns otherwise). The link checks protect against links that already exist (made by hand, by a
  sync tool or by an unpacked archive) — the spec's edge case. A process of the same user that
  swaps a checked segment for a link between check and use is not defended against: it could write
  the notes directly anyway. `SecureDirectoryStream` would close that window on Linux only (not on
  Windows, not reliably on macOS) and is not used; recorded in the spec's plan decisions. Tests make `.lock`, `.history`, a history segment and a parent
  segment symbolic links (POSIX) and assert the refusal and that nothing is written outside. Temporary files left by a crash are deleted on the next
  write if older than one hour.
- `append` reads the current file under the lock, so it builds on the content current at that
  moment (FR-015, US2 AS2/AS9).

**Rationale**: `WorkspaceLock` refuses at once; FR-015 wants a 10 s wait, so a polling loop on the
same primitive. Same directory for the temporary file keeps the move on one file system.

## D-13 Audit and logs (FR-019)

**Decision**: `application/knowledge/KnowledgeAudit`, modelled on `ArchiveExportAudit`:

- One `auditId` per call, `step` `EXECUTE`, `capability` `record_knowledge`, `node` `-` (no INUBIT
  node; `AuditRecord.node` is required), `group` empty, `account` empty, `mcpClient` from the call.
- Inputs in fixed order: `name` (masked, one line, cut at 120 — D-8; a name refused for a secret,
  and a refusal before a `ref` was resolved, is `***`; a `ref` itself is never audited), `mode`, `sizeBefore`, `sizeAfter` (absent when unknown). Never title, topics, content or
  a version token.
- A refusal before step 5 of D-10 is one `REFUSED` record; afterwards `PENDING` then `EXECUTED`,
  `REFUSED` or `FAILED` with the same `auditId`. `reason` = error code and message (which never
  contain content or values, D-14). A refusal whose record cannot be written and a failed
  `PENDING` become `INTERNAL` with nothing written: this is FR-020's "the audit record cannot be
  written". A final record that cannot be written **after** the note was published leaves the
  durable `PENDING` record (tool, name, mode, sizes) and becomes a result warning plus an ERROR
  log, as in 015 (`ArchiveExportAudit`); undoing a published note would risk losing the operator's
  concurrent hand edit. Recorded in the spec's plan decisions. Tests inject both failures.
- **Schema-invalid calls**: the MCP SDK validates tool inputs before the handler
  (`McpServerFactory:114`, `validateToolInputs(true)`), so a call that violates the input schema
  never reaches the tool and writes nothing; it is not audited, as for every audited tool today.
  The schema is kept to the structural rules (types, enum, required `mode`/`content`,
  `additionalProperties`); every rule with a refusal worth auditing (combinations, bounds in code
  points, name rule, secrets) is checked by the service and audited. Recorded in the spec's plan
  decisions.
- Reads are not audited; DEBUG logs as in D-9. A write logs INFO `knowledge.record` with the masked
  name, mode, sizes and outcome.

## D-14 Errors (FR-020)

**Decision**: existing `ErrorCode`s only. Every error built by the knowledge service passes note
names, paths and keys through the D-8 reduction (masked, one line, cut at 120); the
`ResultMapper`'s global scrub stays as a second line of defence. No error carries content, a
title, a topic, an excerpt or a version token of another note. Mapping: `INVALID_INPUT` (name rule
and `.md` suffix of a given `name`, mode/field combinations, bounds of title/topics/query, secret
findings in the given name and new text; a `ref` whose resolved note fails the name rule or holds
a secret in its name is `PRECONDITION_FAILED`, D-10 step 4),
`NOT_FOUND` (with `similar`), `CONFLICT` (create collision, stale version token),
`PRECONDITION_FAILED` (bounds, replace of a note with a secret, malformed metadata, occupied
path, link, lock timeout, credentials not ready, directory unusable, non-atomic file system),
`INTERNAL` (audit not writable, unexpected I/O; nothing written).

## D-15 Tool surface and announcement (FR-018)

**Decision**: `read_knowledge` (read-only, idempotent, `openWorld=false`) is added for every
profile; `record_knowledge` for every profile with `knowledge.recording` on. Both are added in
`Wiring.toolHandlers()` unconditionally of nodes and write switches (FR-012, FR-018), right after
`get_health`. No MCP resources (`ServerCapabilities` unchanged). docs/tools.md's tool count
("all seventeen tools") becomes nineteen.

## D-16 Package placement (Constitution V, `PackageBoundaryTest`)

| Part | Package | Why |
|---|---|---|
| `NoteName`, `KeyMaterial` (moved), `Masked` | `domain/model` | pure Java; `KeyMaterial` must be reachable from `adapter/archive` and `application` |
| `ConfiguredSecrets` | `domain/port` | `application` reaches the credentials only through a port |
| `KnowledgeService`, `KnowledgeDirectory`, `NoteMetadata`, `SecretFinder`, `KnowledgeLock`, `KnowledgeHistory`, `KnowledgeAudit`, `KnowledgeAnnouncement`, `VersionToken` | `application/knowledge` (new sub-package) | file work through `java.nio` and the `PrivateFileSystem` port, as `BackupStore` and `DeploymentLedger` do |
| `ProfileSecrets` | `adapter` | uses `NodeSecrets` and `SecretScrubber` |
| `ReadKnowledgeTool`, `RecordKnowledgeTool` | `mcp/tools` | tool handlers |
| `KnowledgeConfig`, `KnowledgeStatus` | `config` | configuration and `--check-config` |

`SimilarNames` becomes public (used from the sub-package). `PackageBoundaryTest` needs no new rule;
its Javadoc records the 019 placement.

## D-17 Documentation and the touch point with 018

**Decision**: FR-021 docs in the last phase: `docs/setup.md` (directory, `recording`, bounds,
history and restore, owner-only, `--check-config` line, the D-7 credential behaviour),
`docs/tools.md` (two tools, count nineteen, the instructions paragraph), `README.md` (tools table,
one feature line), `SECURITY.md` (what may be stored, local and unencrypted, owner-only,
pattern-based check, prose residual risk, D-7), `CHANGELOG.md` `[Unreleased]`. A doc reference
test `KnowledgeToolsReferenceTest` (pattern of `ArchiveToolReferenceTest`) ties the docs to the
schemas and the bound constants.

Feature 018 (Partner Management) is specified on its own branch and adds a tool too. Whichever
merges second adjusts the tool count in docs/tools.md and the README table; no code touch point
(the announcement is independent of INUBIT tools, FR-007).

## D-18 Test approach (Principle III, SC-001 … SC-006)

- Unit tests per class with `@TempDir` knowledge directories and synthetic notes (ACME,
  `*.example.test`, `Workflow-0001`, FR-022); a `FakeConfiguredSecrets` for D-7 states.
- `KnowledgeSecretLeakTest` (`security/`, with `testing/TestSecrets`): every kind of FR-010 in a
  hand-written note's name, title, topics, `source`, `lastWriter` and body (also in a YAML block
  scalar) never appears in instructions, hits, pages, version tokens, audit lines, log lines
  (captured appender) or error results; every kind in new text (also in a structurally invalid
  name holding a not-yet-read configured credential) is refused without echo (SC-002).
- `KnowledgeProfilesTest`: two profiles, two directories, each server's tools see only their own
  (SC-003); config overlap errors.
- `KnowledgeBoundsTest`: 5,000 notes and a 1 MiB note; instructions, search page and read page
  within their bounds and reporting the cut (SC-004).
- `KnowledgeCrossProcessIT` (failsafe): two JVMs (the test starts a second `java` with the test
  classpath) appending to one note at the same time; both sections present, two history versions
  (US2 AS9). An in-process variant with two threads runs in surefire.
- `KnowledgeToolsWiringTest`: tools present for a profile without nodes, `record_knowledge` absent
  with `recording: false`, annotations, schemas valid; `ToolAnnouncementTest` extended.
- SC-001 is the live test of quickstart.md.
