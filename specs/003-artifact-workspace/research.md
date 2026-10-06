# Research: Artifact Workspace (feature 003)

Inputs: [spec.md](spec.md), the umbrella design
[docs/design/2026-10-05-development-and-deployment.md](../../docs/design/2026-10-05-development-and-deployment.md)
and the spike findings
[docs/research/spike-development-deployment.md](../../docs/research/spike-development-deployment.md)
(INUBIT 8.1.17). Decisions are numbered D-1…D-14; each states the decision, the rationale and the
alternatives that were rejected.

## D-1 Version history: the system `git` command behind a port

**Decision**: A domain port `VersionHistoryPort` (init, status, add-all, commit, diff-name-status,
restore-to-HEAD for a sub-tree) implemented by `adapter/git/GitCli`, which runs the system `git`
with argument arrays, a timeout, a minimal environment and these fixed options on every call:
`-c core.hooksPath=<empty dir>`, `-c commit.gpgsign=false`, `-c core.autocrlf=false`,
`-c core.quotepath=false`, `-c user.name="INUBIT MCP (<profile>)"`,
`-c user.email=inubit-mcp@localhost`, `GIT_TERMINAL_PROMPT=0`, `GIT_CONFIG_NOSYSTEM=1`. The server
never runs `remote`, `push`, `fetch` or `clone`; `GitCli` has no method for them (FR-007).

Implementation details (T009, review): `core.hooksPath` points to an owner-only empty directory
below `java.io.tmpdir`; commits also pass `--no-verify`; the environment is `PATH` of the server
plus the two variables above, `GIT_CONFIG_GLOBAL=/dev/null` (no global configuration of the person,
e.g. `core.fsmonitor`, templates, filters), `GIT_LITERAL_PATHSPECS=1` (paths are never patterns)
and `LC_ALL=C`. This needs **git 2.32 or newer** (`GIT_CONFIG_GLOBAL`; `git init -b` needs 2.28).
git is started through the CLI adapter's `ProcessLauncher`, so only `adapter.cli` starts processes.
A missing git is `PRECONDITION_FAILED` "git not found", any other start failure "git could not be
started".

**Rationale**: no new runtime dependency (Constitution "dependencies minimal"); the same execution
rules as StartCLI (Constitution V: argument arrays, timeout, exit code, tested parser); git is on
every machine that runs the INUBIT client. Fixed identity and disabled hooks make commits
independent of the user's git configuration and keep foreign hooks from running.

**Alternatives**: JGit — about 3 MB more in the shaded JAR and a second git implementation to trust;
rejected. Plain file snapshots without git — no diff tooling for the person; rejected (the user asked
for git).

## D-2 Workspace layout

**Decision** (per profile, root = `workspace`):

```
<root>/
  .git/                                   history (never a remote)
  .gitignore                              ignores .tests/, .reports/, .lock
  <group>/<owner>/
    workflows/<diagram group>/<workflow>.xml
    modules/<plugin type>/<module>/module.xml        configuration (embedded content as references)
    modules/<plugin type>/<module>/index.xml         entry of the module index (reviewed fields)
    modules/<plugin type>/<module>/<property>.<ext>  embedded documents (.xsl, .wsdl, .xsd, .xml, .txt)
    repository/<path>/<file>                         repository files referenced by the artifacts
  .meta/<group>/<owner>/…                            volatile values, same relative paths + .json
  .tests/…                                           outputs of check_artifacts (not versioned)
  .reports/…                                         full path lists of large exports (not versioned)
  .lock                                              workspace lock (not versioned)
```

The owner is part of the path because exports can be done for the configured owner and for another
owner (for example a personal diagram group), and two owners can use the same names. A module is a
directory, so that its embedded documents sit next to it.

**Rationale**: one sub-tree per group makes stage comparisons plain file diffs (FR-008); the paths
mirror how a person finds an artifact in the Workbench.

**Alternatives**: flat files with encoded full names — unreadable; one file per diagram group —
diffs of unrelated workflows mix; rejected.

## D-3 Names to paths

**Decision**: path segments are the INUBIT names, percent-encoded for every character outside
`[A-Za-z0-9 ._()+,=@-]`, plus a leading `.` and trailing spaces or dots, so that every name maps to
exactly one segment and back (FR-016). Before writing, the export compares the target paths
case-insensitively; two different names that collide only in case abort the export with
`INVALID_INPUT` naming both. (INUBIT's own export already stores module files under lower-case
names, so case-only module collisions cannot occur in one export; workflows can.)

**Alternatives**: hashing names — not readable; case-folding suffixes — surprising paths; rejected.

## D-4 Splitting and rebuilding the archive (lossless)

**Decision**: a version-specific codec `adapter/archive/v81/ArchiveCodec` reads an export ZIP into an
in-memory model and writes it to the workspace; the inverse (`ArchiveAssembler`) rebuilds a ZIP from
workspace + `.meta/`. Feature 003 uses the inverse only in tests (round trip); feature 004 uses it
for imports.

- `workflow/workflow.xml` is split per `Workflow` element. Each workflow file is a standalone
  document whose root is the `Workflow` element; the enclosing context (`IBISWorkflow@version`,
  `WorkflowGroup` name and attributes, the position of the workflow in its group) goes to `.meta/`.
- `module/module.xml` is split per `Module` element into the module's `index.xml`; the enclosing
  `ModuleGroup` context goes to `.meta/`.
- Embedded documents: `<Property type="XmlDocument">` text (entity-escaped XML, never CDATA) and
  `<Property type="InternalDocument">` content (gzip + base64, decoded by content, not by the
  `xslt.base64Zipped` flag) are written as files; in `module.xml` the property keeps its attributes
  and gets the text `@file:<file name>`. File extensions: `xslt.stylesheet` → `.xsl`, `WsdlData` /
  `ValidWsdlData` → `.wsdl`, InternalDocument by `documentContentType`/`documentName` (`.xsd`,
  `.xml`, otherwise `.bin`), any other XmlDocument → `.xml`.
- Inline stylesheets in workflow assignments (`<xsl>` inside `from`) stay in the workflow file; they
  are not extracted and not run by `check_artifacts` in this feature.
- Repository: `Repository.zip` entries `<path>.dat` → `repository/<path>`; `<path>.xml` metadata →
  `.meta/…`. Only files referenced by an exported artifact (`inubitrepository:` references,
  repository-path properties) are written (FR-012); the rest of the owner's repository is ignored.
- On rebuild, embedded XML is escaped like INUBIT writes it: `&` and `<`, and `>` only after `]]`
  (spike §5); InternalDocuments are gzipped and base64-encoded again.
- Values derived from embedded content are recomputed on re-embedding (review of T002): the
  `JSONStaticSchemaMD5` of a JSON Validator is the MD5 of the decoded `JSONStaticSchema`, and the
  repository metadata `contentMD5`/`contentSize` describe the `.dat` content; an edited document
  must not keep the old values. `check_artifacts` may warn about a mismatch (T025).
- The module index may list the same module twice (seen in a real export); both index entries are
  kept, the module file is read once. Exports by tag (`usertags.xml`) and with history
  (`versionHistory.xml`) are not produced by this feature and are refused by the reader.

**Rationale**: the spike showed INUBIT accepts reformatted XML on import (the probes rewrote
`workflow.xml` with another serializer) and identifies artifacts by name, so element order and
whitespace between elements are free, text content is not.

**Equality** ("treated as identical", FR-015, SC-002): two archives are equal when, after removing
the volatile values of D-6 and replacing secret values by placeholders (D-7) on both sides, every XML
entry is equal under XML canonicalization (comments kept, whitespace-only text between elements
ignored, all other text compared exactly) and every non-XML entry is byte-equal after decoding.

## D-5 Normalized serialization

**Decision**: StAX-based writer: UTF-8, LF, two-space indentation for element-only content; text and
mixed content written exactly as read; attributes sorted by name; empty elements as `<x/>`; no XML
declaration differences between files (`<?xml version="1.0" encoding="UTF-8"?>`). Embedded documents
are written exactly as decoded (no reformatting), so stylesheet diffs show the author's formatting.

**Alternatives**: DOM + Transformer indent — reorders namespace declarations and touches text;
rejected.

## D-6 Volatile values

**Decision**: moved to `.meta/` (JSON, sorted keys): from `archive.properties` only
`sourceVersion` (in the export record `.meta/<group>/<owner>/exports/…`, with the entry order); the
export suffix of `CheckinComment` (from the first `@@@Deploying User:` up to the end) **without** its
trailing `Export/Deployment: <time>@@@`; for **workflows** also the `###`-separated history
segments of `CheckinComment` (from the first `###` up to the suffix, key
`CheckinComment.history`), because INUBIT appends to them on every export (correction found by the
live acceptance; spike §3) — the workflow file keeps only the text before the first `###`, a
module's comment keeps its text up to the suffix (module comments do not grow). A `.meta` record
that differs from the stored one only in `CheckinComment.history` is not rewritten, so that an
unchanged re-export changes no file (SC-001); the history is then the one of the last export that
changed the workflow, and a rebuild restores that one; `WorkflowUId`, `ModuleUId` (the elements stay in the files, empty);
repository file metadata. **Dropped** entirely, because they change on every export and would
break SC-001 (an unchanged re-export changes no file): the export time (the
`Export/Deployment: <time>` field and the timestamp comment of `archive.properties`) and
`operationId`; a rebuild writes a new export time and no `operationId`. Stay in the reviewed files
(FR-014): `CheckoutUser`, `IsActive`, layout (`StyleSheet`, `Junctures`), `LastUpdate`,
`ExportUser` (stable across exports per spike §3), comments. ZIP entry timestamps are not kept.

## D-7 Secrets and placeholders

**Decision**: `SecretRedactor` replaces, before anything is written:

| Where | Rule |
|---|---|
| module files, workflow instance properties | every `Property` with `type="Password"`, any value, with or without `encrypted` |
| module files, workflow instance properties | every `Property` with `encrypted="true"`, whatever its type (e.g. `type="MaskedString"`) |
| module files | `type="KeyStore"` values; `type="X509"` and certificate properties only if they contain a private key |
| module files | untyped secrets (review of T002, found in the recordings): keystores `SSLKeyStoreRemoteConnector` (Web Services Connector) and `smime.keystore.data` (SMIME); passwords `SSLKeyStorePasswordRemoteConnector` and `smime.keystore.alias.password` |
| workflow | `literal` with `isPassword="true"`; `DefaultValue` of variables of type `is:password` |
| XSLT modules | the values of `xslt.sourceVariables` (saved test values) and the saved test messages `xslt.source` and `xslt.target` (also when stored as `InternalDocument`) |
| any property (review I3) | key material outside `type="KeyStore"`: an `InternalDocument` whose `documentName` ends in `.jks`, `.jceks`, `.p12`, `.pfx`, `.keystore` or `.key`, or whose decoded content is a JKS (`FEEDFEED`), JCEKS (`CECECECE`) or PKCS#12 (DER, version 3, PKCS#7 content) keystore; any value with a PEM private key |
| repository (review I3) | a referenced repository file with such a name or content is **not written**: its `.meta` record holds `withheld: ${secret:Repository/<path>}` and its metadata without `contentSize`/`contentMD5`; the export reports a count-only warning |

Any property that is still unredacted and whose name contains `password`, `secret`, `keystore` or
`token` (case-insensitive) is counted and reported as a warning with its count only (never its name
or value), so that a new secret form shows up without leaking.

Placeholder: `${secret:<property path>}` where the path is the property name chain inside the
artifact (e.g. `${secret:Mime.Sign.Password}`, `${secret:xslt.sourceVariables/ISCurrentTime}`); the
artifact is identified by the file the placeholder is in (FR-024), so feature 004 always resolves
placeholders per file. Empty values stay empty. Nothing derived
from the value (hash, length) is stored. The redaction runs on the in-memory model; the raw ZIP lives
only in the private temporary directory of the export and is deleted on every exit path (existing
`CliExportRunner` rules, FR-026). The global log scrubber is not relied on: tool results and logs
only ever see redacted data.

Corpus check (review I3, 2026-10-06, counts only): a scan of every ZIP of the local spike exports
(77 archives, 45 nested `Repository.zip`, 6319 entries, 24 `InternalDocument`s) found no key
material outside `type="KeyStore"` and the known untyped keystore properties — no entry or
`InternalDocument` with a keystore name or JKS/JCEKS/PKCS#12 content and no PEM private key; the
opt-in `ArchiveCorpusTest` reports 0 for both new kinds. The rules above cover the forms anyway;
the fixtures carry synthetic examples (`grp-b.zip`, `keystores/`).

**Alternatives**: keeping hashes to detect changes of secrets — leaks information about short
values; rejected (feature 004 compares secrets on the server side, in memory).

## D-8 Export via StartCLI

**Decision**: extend `CliExportRunner` with
`exportWorkflowGroup(owner, group)` →
`export --exportWorkflowUser '<owner>' --exportWorkflowType 'technical' --exportWorkflowGroup '<group>' --exportFile '<tmp>/export.zip'`
(one StartCLI run per diagram group: `CliCommand.VALUE` allows no comma, and per-group runs keep
failures attributable)
and `exportModule(owner, pluginType, name)` →
`export --exportModule '<name>' --exportModuleGroup '<plugin type>' --exportModuleUser '<owner>' --exportFile '<tmp>/module.zip'`.
Both return the archive as bytes with a cap of 128 MiB (the real owner-wide module export is 4.3 MB);
quoting and value rules of `CliCommand.VALUE` apply (letters, digits, `_`, `.`, `-`, space;
a name outside that set is refused with `INVALID_INPUT` "not supported by StartCLI quoting"). `--exportWorkflowType` is always `technical`
(clarification 2); the tool has no type parameter. A module whose plugin type is not given is looked
up in the inventory module list. The new port method lives in a new `ArtifactPort`
(`Gateway.artifacts()`), implemented in `adapter/cli/v81`.

**Diagram group `""` means "all"** in StartCLI (spike §1): an empty or blank group is rejected by
input validation before StartCLI runs.

## D-9 Export transaction

**Decision** (`application/WorkspaceService.export`):

1. acquire the workspace lock (`FileChannel.tryLock` on `.lock`; held → refused at once with
   `PRECONDITION_FAILED` "another export or check is running", no waiting (FR-020); works across the
   CLI and desktop instances of one profile);
2. `git status` — if anything is uncommitted, commit it as `local changes: <n> files` (clarification
   1);
3. run the StartCLI export(s); parse, redact, normalize into memory — any failure here leaves the
   workspace untouched (FR-018);
4. replace the affected sub-trees (`<group>/<owner>/workflows/<diagram group>/…` of the exported
   diagram groups, the exported modules and their referenced repository files) — deleting files of
   artifacts no longer exported (FR-017); a failure while writing restores the sub-trees from `HEAD`
   (safe because step 2 committed everything);
5. commit `export <group>/<node>: <what> (<n> files)` if anything changed;
6. release the lock.

Modules used by an exported workflow but owned by another owner (library modules) are not in the
export; nothing is written for them.

## D-10 Bounded results

**Decision**: both tools use the profile's existing `resultLimits` (`maxItems`, `maxChars`).
`export_artifacts` returns counts per change kind and at most `maxItems` paths; when there are more,
the full list goes to `.reports/export-<commit>.txt` and the result returns that path
(`truncated: true`). `check_artifacts` returns at most `maxItems` findings (most severe first) and
writes the full list to `.reports/check-<timestamp>.json` when truncated. No new settings.

## D-11 XSLT engine and stand-ins

**Decision**: add `net.sf.saxon:Saxon-HE:10.9` (MPL-2.0; INUBIT runs Saxon 10; the spike compiled
88 % of real stylesheets with this exact version). Stand-ins are Saxon *integrated extension
functions* (`ExtensionFunctionDefinition`) registered under the URIs the stylesheets use
(`java:com.inubit.ibis.xsltext.Formatter`, `…Misc`, `…ISFunctions`, `java:java.util.UUID`,
`java:java.lang.Thread`, `java:java.net.URLDecoder`, and the Xalan form
`http://xml.apache.org/xalan/java/java.lang.Thread`) with the arities seen in real stylesheets (spike
§7). Deterministic (clarification 4): `guid`/`randomUUID` → `00000000-0000-0000-0000-000000000000`,
date/time functions → a fixed instant (`2000-01-01T00:00:00Z`) or the run's `now` parameter, `sleep`
returns at once. Behaviour of the date/number formatting stand-ins follows the INUBIT documentation
of `Formatter`; where the documentation is silent, each stand-in documents its assumption and the
result marks the run as "used stand-ins".

The `xslt.transformer` of a module (recorded: `net.sf.saxon.TransformerFactoryImpl` and the Saxon-EE
factory of `com.saxonica.config`) does not select the engine: every `com.saxonica.*` factory is
treated the same way (runs on Saxon-HE, EE-only constructs are `NOT_TESTABLE`), never keyed on an
exact class name; the fixtures use `com.saxonica.config.ProfessionalTransformerFactory`.

Any other `Q{java:…}` or `saxon:`/EE-only construct found at compile time → `NOT_TESTABLE` with
reason; a static error → `ERROR` with line/column. `inubitrepository:` resolves from the workspace
`repository/` of the same group and owner (FR-032).

**Risk / first test**: Saxon-HE must accept an integrated function in a `java:` namespace without
attempting reflexive binding — verified by the first test of the XSLT task before anything else is
built on it (T028: passed).

Stand-ins whose behaviour the INUBIT documentation does not state (e.g. `Misc:encode`, the variable
storage, `convertDateString`, `formatNumber`) and fallbacks (an unreadable date or number kept,
malformed XML parsed to nothing) add the WARNING `XSLT_STANDIN_ASSUMED`, so that an `OK` run never
rests silently on an invented result; `Formatter:calculateDateDifference` (eleven undocumented
parameters) and an unseeded `random-number-generator()` are `NOT_TESTABLE`. A run sees nothing of the
server's host (no environment variables, no Java system properties, no `xsl:result-document`) and
ends after 60 s (`XSLT_RUNTIME_ERROR`), so that it cannot hold the workspace lock indefinitely.

**SC-005 measurement** (clarification of 2026-10-06): on a corpus without real input messages,
SC-005 counts the stylesheets that compile and execute locally with every extension call served by
a stand-in — `OK`, or `XSLT_RUNTIME_ERROR` on the given input (never passed); the share of fully
successful runs is reported separately. Local run of `XsltCorpusTest` on the spike corpus
(2026-10-06; minimal input `<root/>`, required top-level parameters as empty strings; counts only):
656 stylesheets — `OK` 599 (**91.3 %**), `XSLT_RUNTIME_ERROR` 50, so **executable 649 (98.9 %)**;
`NOT_TESTABLE` 4 (two customer Java extensions, one document type declaration,
`calculateDateDifference`), `XSLT_STATIC_ERROR` 3. The runtime errors come from the minimal input
(required template parameters XTDE0700 32, type errors XPTY0004/XPTY0019/FORG0001 16, other 2).
A run stopped by the 60 s deadline would count as `TIMEOUT` and not as executable; the rerun
had none, the numbers are unchanged.

**Alternatives**: Saxon-HE 12 — different defaults (e.g. XSLT 3.0 features, error codes) than
INUBIT's Saxon 10; rejected. Xalan — wrong engine; rejected.

## D-12 XSD validation

**Decision**: the JDK's `javax.xml.validation` (XSD 1.0) with a resource resolver for the workspace
(`repository/` and sibling files), secure processing on, external access limited to `file` inside
the workspace. No new dependency (Saxon-HE has no schema validation).

## D-13 Structure checks and server lookups

**Decision**: `application/ArtifactCheckService` checks workflow files (FR-027) with findings
`ERROR`/`WARNING`/`INFO`:

- edges (`Connection@moduleOutId`) target an existing `ModuleId` → ERROR;
- `ModuleId` and `ConnectionId` unique together → ERROR;
- Demultiplexer keys `<Name>(<id>)@@@…` and `DefaultOutput` refer to an outgoing edge of that node
  whose target has that name and id → ERROR;
- `ParentModule`, `EndLoopId`, `scopeChildId` refer to existing ids → ERROR;
- referenced module names exist in the workspace (any owner of the same group) or in the server's
  module list of the artifact's owner or of `inventory.owner` (read via `InventoryPort`, cached) →
  otherwise ERROR; server unreachable → WARNING "could not verify" (FR-028);
- variable references in assignments and conditions (`WFSP` operands, `@variable`) resolve to
  declared variables → WARNING (INUBIT also has implicit system variables; names starting with `IS`
  are taken as such and not reported — an assumption, as INUBIT's list is not documented);
- `inubitrepository:` references resolve in `repository/` → ERROR.

## D-14 Fixtures

**Decision**: offline fixtures are recorded from the spike's development-stage exports (small diagram
groups with Demultiplexer, Assign, XSLT, WS and AS2 connector modules), neutralized with
`tools/neutralize.py`, and every secret value replaced by a synthetic value of the same shape
(`AES-…`, `AESG…`, legacy base64, plain, keystore base64) — no real ciphertext is ever committed. The
identifier guard covers the fixture ZIPs (entry names and text entries). Defect fixtures
(dangling edge, missing module, id collision, broken condition, unknown extension, syntax error) are
derived from them by small, documented edits. The fixtures also hold the StartCLI outputs of an
export of a non-existent diagram group and of a non-existent module (read-only recordings, stdout,
stderr and exit code separately), as required for contract tests (Constitution III).

Saved test messages, assignment literals, stylesheets, WSDLs and embedded schemas are customer
content: in the fixtures they are always **synthetic** documents of the same structure (same XSLT
constructs, escaping, namespaces, binding style), never recorded content; `ArtifactFixturesTest`
refuses literals and saved test messages that are large or not visibly synthetic. The synthetic
secret forms include SMIME (`module-smime.zip`) and `MaskedString`.

The archive layout is also checked against local, real exports by the opt-in
`ArchiveCorpusTest` (`INUBIT_MCP_ARCHIVE_CORPUS`, skipped when unset, prints counts only).

The stylesheet coverage of SC-005 cannot be proven on committed fixtures (the real stylesheets are
customer content). A local, opt-in corpus test (`XsltCorpusTest`) runs against a directory given in
`INUBIT_MCP_XSLT_CORPUS` (for example the spike's extracted stylesheets and repository) and is
skipped when the variable is not set, like the identifier guard without its lists; CI runs the
fixture-based `XsltCoverageTest`.
