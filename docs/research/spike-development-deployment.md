# Spike: development and deployment (INUBIT 8.1.17)

Findings of the spike in section 10 of
[the development and deployment design](../design/2026-10-05-development-and-deployment.md),
run on 2026-10-06 against a development stage with INUBIT 8.1.17 and the StartCLI of the 8.1.17
client. Reads covered the shared owner's artifacts and, for secrets, the next stage; **all writes
went to two test workflows in one person's own diagram group**. Names below are neutral
(`<owner>`, `<user>`, `SPIKE…`); counts are from the real system.

## Summary for features 003–005 and 008

| Question | Answer | Consequence |
|---|---|---|
| Import command | `import --importFile <zip> --importWorkflow [--importWorkflowActive \| --importWorkflowInactive] [--importUser <user> \| --importUserGroup <group>] --returnProtocol`; module mode `--importModule` | one adapter command for workflows, one for modules |
| Identity | artifacts are matched **by name**; UIDs are reassigned on every modifying import | UIDs go to `.meta/`, never used as identity |
| Versioning | every import of an existing artifact creates a new version, even if nothing changed | import only what changed (workflow archive or module-only archive) |
| Round trip | structural changes (nodes, edges, Demultiplexer conditions) are applied exactly; re-importing an earlier export restores the content | design section 5 holds |
| Validation by INUBIT | **none**: dangling edges and references to non-existent modules are stored; the latter breaks the export of the whole diagram group | `check_artifacts` structure checks are mandatory, the verify step must treat a failing export as an import failure |
| Ids | `ModuleId` and `ConnectionId` share one id space per workflow | `check_artifacts`: ids unique across nodes **and** connections |
| Tags | CLI tagging is scoped to a user/owner, at the smallest to a **diagram group**; `--tagDiagram` is ignored | a release is a set of diagram groups, not single workflows |
| Release export | `export --exportTag <tag>` returns the tagged version, not head | releases can be exported by tag (feature 005) |
| Active flag | set by the import flags | no separate channel needed |
| Secrets | encryption is installation-independent; most values are identical across stages, some differ | "secrets come from the target" is mandatory |
| Volatile values | `archive.properties`, ZIP entry times, the export suffix of `CheckinComment` and, for workflows, its `###` history segments (plus UIDs after imports); see the correction in section 3 | normalization is small and well-defined |
| XSLT on Saxon-HE 10 | 88 % of the owner's stylesheets compile as they are; the rest needs ~20 INUBIT extension functions | stubs for those functions make local XSLT checks broadly useful |
| Artifacts in edit mode | export marks them with `<CheckoutUser>`; INUBIT does **not** protect them: an import overwrites the edited workflow and a later publish from the Workbench overwrites the import, both without a warning (last writer wins) | `import_artifacts` must refuse with `CONFLICT` when `CheckoutUser` is set, and re-check right before importing |
| Repository export (section 10) | `export --exportRepositoryPath /Root/<owner>/<path>` returns `Root/<owner>/<path>/<name>.xml` (metadata) + `<name>.dat` (content); a missing path fails with `Path not found` (exit 1) | repository files can be read per path; "missing" is a recognizable `NOT_FOUND` |
| Repository import (section 10) | `import --importRepositoryPath /Root/<owner>` takes entries **relative** to the import path, prints no protocol, creates a new version on every import | the archive is built relative to the owner's root; verification by re-export |
| Repository files in workflow and module imports (section 10) | `Repository.zip` of a workflow or module archive is **ignored** | repository files need their own import (repository mode) |
| Repository references (section 10) | a group export carries the files its modules reference by `inubitrepository:` in `xsl:import`/`xs:import`; a string literal is no reference | the release's repository files come from the export, not from a text search |
| Tags on repository files (section 10) | a group-scoped tag also tags the referenced repository files; `--tagRepositoryPath` with `--tagUser` is refused | tagging the diagram groups is enough; never combine those options |
| Release export (section 10) | `export --exportWorkflowGroup '' --exportTag <tag>` returns only the tagged diagram groups in their tagged versions, with tagged modules and repository files, without touching other groups' comments | a release is discovered with one export per source node (feature 005) |
| Content type on repository import (section 11) | a sent `contentType` is stored unchanged; without one INUBIT stores `application/octet-stream`, it derives nothing from the extension | the server sends a content type for every repository file it imports (feature 008) |
| Observed content types (section 11) | `.xsd` `application/x-xsd+xml`, `.xsl` `application/xslt+xml`, `.xml` `application/xml`; INUBIT's own type for `.xslt` and `.wsdl` was not observed | the content-type table for new files (research D-7 of feature 008) |
| Repository folder import (section 11) | an import creates new folders and subfolders from the archive; re-exported content is byte-identical, also LF-only without a trailing newline | verification by byte comparison per folder export |
| Repository versions (section 11) | every import creates a new version and prepends `DefaultCommitCommentImport@@@`, also a restore of earlier content (and, section 10, unchanged content); a sent `version` is ignored | verify compares content only; identical files are not sent; the `.meta` record changes after every import, rollbacks included |
| Metadata of new repository files (section 11) | INUBIT sets `version="1.0"`, `anonymousRead="false"` and its own `uuid`, and drops an empty `<Description/>` | the synthesized metadata of a new file carries no `anonymousRead` (research D-7 of feature 008) |
| Repository import output (section 11) | `1-OK: Imported successfully` follows the progress text `Completed = … MB / … MB` on the **same line** | the output classifier accepts the progress text before `n-OK` |
| Durations (section 11) | repository export 2.4–2.7 s, repository import 2.3–2.8 s, diagram-group export 2.9–4.1 s, `tag` 3.8 s (small files) | one StartCLI call per folder keeps a 20-file import well inside 2 minutes (SC-001 of feature 008) |
| Tags on indirectly referenced files (section 11) | a group tag also tags repository files referenced only by another repository file (relative and `inubitrepository:` `xsl:import`) | no warning for indirectly referenced files under a tag (research D-12 of feature 008) |
| Group export of indirectly referenced files (section 11) | the diagram-group export, head and by tag, puts such files into `Repository.zip` | the transitive reference closure of feature 008 matches INUBIT's own export (research D-3) |

## 1. StartCLI

- No per-command help: `help <command>`, `<command> -h` and `<command> --help` fail ("Command not
  found" / "Unrecognized option"). The offline `startcli.sh -h` (no login) prints every command
  with its options; it is the reference for the adapter.
- Every failure exits with **1**; the cause is only in the text: `Command not found.`,
  `Unrecognized option: …`, `Invalid password` (`CliConnectionException … WrongPassword`),
  `Internal INUBIT error! The following error occurred: {0}`. Success prints `1-OK: …` and exits 0.
- The password on stdin (as `CliRunner` does today) works for every command used here.
- Relevant options (8.1.17):
  - `export`: `--exportFile`, `--exportWorkflowUser`, `--exportWorkflowType`
    (`technical | organigram | systemdiagram | constraintsdiagram | bpd | processmap | all`),
    `--exportWorkflowGroup` (comma separated, `""` = all), `--exportWorkflowGroupExclude`,
    `--exportTag`, `--includeHistory`, `--exportModule`, `--exportModuleGroup`,
    `--exportModuleUser`, `--exportRepositoryPath`, `--exportMetadata[Names]`.
  - `import`: `--importFile`, `--importWorkflow`, `--importWorkflowActive`,
    `--importWorkflowInactive`, `--importModule`, `--importRepositoryPath`, `--importUser`,
    `--importUserGroup`, `--importMetadata`, `--returnProtocol`.
  - `tag`: `--tagMove <tag>` (set/move), `--tagDelete <tag>`, `--tagRemove`, `--tagUser`,
    `--tagWorkflowGroup`, `--tagWorkflowGroupExclude`, `--tagWorkflowType`, `--tagDiagram`,
    `--tagRepositoryPath`; `--tagSetActive` sets a user's *active tag*, not the active flag.
  - `deploy`: `--deployWorkflowArchive`, `--deployConfiguration` (`ibis_deploy.xml`),
    `--deployCreateProperties`, `--deploySourceProperties`, `--deployTargetProperties`,
    `--deployMergedProperties`, `--deployMergedArchive`, `--deploySystemName`, `--deployUser` —
    INUBIT's own mechanism for stage-specific properties during deployment. Not probed; not
    needed while artifacts are stage-independent (design section 7).
- The import protocol (`--returnProtocol`) is a fixed-width table with the columns `TYPE`,
  `DESCRIPTION` (`Module [<name>] was created.` / `… was modified.`, `Diagram [<name>] …`),
  `DIAGRAM/MODULE`, `GROUP/USER`, followed by `Total: <n>`.

## 2. Export layout

- Diagram-group export: `archive.properties`, `workflow/workflow.xml` (all workflows of the
  selection), `module/module.xml` (index), `module/<lower-case name>.xml` for **every module used
  by the exported workflows**, and `Repository.zip` (the owner's repository: `<file>.dat` +
  `<file>.xml`). `--includeHistory` adds `versionHistory.xml` (per workflow and module: versions
  with number, check-in user, comment, time and tags).
- Module-only export: `archive.properties`, `module/module.xml`, `module/<name>.xml`, an empty
  `workflow/` directory entry and an empty `Repository.zip`.
- A workflow's modules carry no name inside their own file; the name comes from the index and the
  file name.

## 3. Volatile values

Two exports of the same unchanged diagram group, nine seconds apart, differ only in:

- `archive.properties`: date comment and `operationId`; ZIP entry timestamps;
- `CheckinComment` of every workflow and module: its suffix
  `@@@Deploying User: <exporting user>@@@Server: <host>@@@Version: <n>@@@Export/Deployment: <time>@@@`
  is written **at export time**.
- **Correction (live acceptance of feature 003, 2026-10-06; confirmed on the raw recordings, counts
  only):** for **workflows** the part of `CheckinComment` *before* `@@@Deploying User:` grows as
  well: it consists of `###`-separated segments, and every export appends copies of its **last**
  segment at the end (e.g. `######` when the last segment is empty, or
  `###Import from inubit without version history` twice) — on every pair of recordings of the same
  workflow the added tail was exactly such copies. The other segments (e.g. a person's comment)
  do not change. Module comments do not grow. The original
  statement of this section missed it because the comparison above looked at the suffix only.

An export is therefore **not completely side-effect free in INUBIT**: it leaves a trace in the
check-in comment of every exported workflow. This applies to the StartCLI exports of the
inventory tools of feature 001 (version histories) as well.

`LastUpdate`, `ExportUser`, module files, repository contents and encrypted values are identical.
After an import, `WorkflowUId` and `ModuleUId` change as well (section 5).

## 4. Secrets across stages

The password properties of the owner's modules were compared between the development stage and
the next stage by hash only (values never printed): 329 and 221 properties, 212 present on both,
**202 with identical ciphertext, 10 different**. The encryption does not depend on the server; the
different ones are stage-specific passwords. A deployment that carried the source's values would
silently overwrite them. Value formats include `AES-…`, `AESG…` and older non-AES encodings; some
password properties lack `encrypted="true"`.

## 5. Import behaviour

All probes used a copy `SPIKE_Roundtrip_Claude` (modules `SPIKE_C_…`) of a small test workflow
(XSLT Converter → Demultiplexer → two Assign modules) in the person's own diagram group.

- **Create**: importing an archive whose names do not exist creates them
  (`was created`); the UIDs in the archive are kept; the check-in comment becomes
  `DefaultCommitCommentImport###<comment in the archive>###…`, version 1.
- **Update**: an archive with existing names updates them (`was modified`); every import makes a
  new version (even without changes); `WorkflowUId` and `ModuleUId` are **reassigned**.
- **Unchanged round trip**: content identical apart from the UIDs and the check-in comment.
- **Structural change**: a new node, a rewired default branch and a Demultiplexer condition
  (`<Name>(<id>)@@@DeMuxInput` = `/*/@type@@@=@@@a`, `…@@@ProcessingOrder`, `DefaultOutput`)
  arrived exactly as written.
- **No validation**:
  - an edge to a non-existent node id was stored as is;
  - a node referencing a module that is neither in the archive nor on the server was accepted, but
    afterwards **every export of that diagram group failed** with `Internal INUBIT error` (log:
    `WorkflowIDService register: ID n already used`) until the previous state was re-imported.
- **Id space**: `ModuleId` and `ConnectionId` share one id space per workflow; a node id equal to
  a connection id is reported as "already used".
- **Restore**: re-importing an earlier export restores the content exactly.
- **Module-only import** (`--importModule`, archive with index and one module file) updates the
  module; the workflow version does not change. INUBIT re-escapes embedded XML on save (only `<`
  and `&` are escaped; `>` stays literal), so normalization must use that style.
- **Active flag**: `--importWorkflowActive` / `--importWorkflowInactive` set `IsActive`.

## 6. Tags

- `tag --tagMove <tag> --tagUser <user> [--tagWorkflowType technical] [--tagDiagram <name>]`
  tags the head version of **every** diagram and module of that user: `--tagDiagram` is ignored.
  (This happened once during the spike: 234 head versions were tagged; `tag --tagDelete <tag>
  --tagUser <user>` removed the tag everywhere, the 71 other tags were unchanged and the shared
  owner was not affected.)
- With `--tagWorkflowGroup <group>` the tag is limited to that diagram group and its modules. The
  smallest unit that can be tagged through the CLI is therefore a diagram group.
- `export … --exportTag <tag>` returns the tagged version even when head is newer.

## 7. XSLT on Saxon-HE 10

All 656 stylesheets of the owner's XSLT Converter modules (all stored as escaped XML, none base64)
were compiled with Saxon-HE 10.9, resolving `inubitrepository:` references from the exported
repository:

| Result | Count |
|---|---|
| compiles | 578 (88 %) |
| needs an INUBIT or Java extension function | 75 |
| genuine error in the stylesheet | 2 |
| repository file not exported with the owner | 1 |

Extension functions in use: `com.inubit.ibis.xsltext.Formatter` (80 calls: `changeDateFormat`,
`convertDateString`, `trim`, `formatNumber`, `crlf`, `lf`, `getDateTime`, `isNumber`,
`getDateAsString`, `parseSchemaDateToSQLTimestamp`, `calculateDateDifference`),
`com.inubit.ibis.xsltext.Misc` (41: `guid`, `encode`, `decode`, `stringToBranch`,
`setVariableStorage`, `getVariable`), `com.inubit.ibis.xsltext.ISFunctions` (10: `serialize`,
`deserialize`), `java.lang.Thread#sleep`, `java.util.UUID#randomUUID`,
`java.net.URLDecoder#decode`, and one installation-specific class.

## 8. Artifacts in edit mode

In the Workbench a workflow is put into edit mode ("Edit", German "Editieren"; elsewhere called
check-out) and saved as a new version by "Publish" ("Publizieren"; check-in). Probe with the copy
in edit mode by the person:

- The export marks the workflow with `<CheckoutUser><user></CheckoutUser>`; the element is absent
  otherwise. Modules in the index carried no marker.
- An import of the workflow during edit mode succeeded (`was modified`, new version) and the
  export afterwards showed no `CheckoutUser` any more; the Workbench, however, still showed the
  workflow in edit mode and no lock or notice.
- Publishing from the Workbench afterwards gave no warning and created the next version with the
  Workbench state: the imported change was lost.

Both directions are silent lost updates. The server is the only place that can prevent them.

## 9. Workbench rendering

The person opened the structurally changed copy in the Workbench: the new node, the rewired
default branch and the Demultiplexer condition are shown as intended.

## 10. Repository files and releases (feature 005)

Probes of 2026-10-07 with StartCLI 8.1.17 on the development stage, approved by the person; all
writes went to the person's **personal test repository area** and personal test diagram group
(one stray probe folder remained in that area; nothing outside it was touched). Names below are
neutral (`<owner>`, `<path>`, `<tag>`).

- **Repository export**: `export --exportRepositoryPath '/Root/<owner>[/<path>]' --exportFile
  '<zip>'` prints `1-OK: Repository path exported successfully.` and writes, for each file below
  the path, `Root/<owner>/<path>/<name>.xml` and `<name>.dat` (plus directory entries; archive
  comment `5.3`). The `.xml` is one element `<Property name="<name>" type="RepositoryFile" …>`
  with the attributes `uuid`, `path`, `version`, `contentMD5`, `contentSize`, `modified`,
  `versionComment`, `contentType`, `modificator`, a `<Description>` child and, in an export with
  `--exportTag`, `tagName`. The `.dat` is the content. A path that does not exist fails (exit 1)
  with `Internal INUBIT error!` and `Path not found //ibis:Root/<owner>/<path>`.
- **Repository import**: `import --importFile '<zip>' --importRepositoryPath '/Root/<owner>'`
  prints a progress line `Completed = 0 MB / 0 MB` and `1-OK: Imported successfully` — **no
  protocol**. The archive entries are taken **relative to the import path**: an archive in the
  export shape (`Root/<owner>/…`) ended up below `/Root/<owner>/Root/<owner>/…`; entries
  `<path>/<name>.xml` + `.dat` land at `/Root/<owner>/<path>/<name>`. The archive's `uuid` is
  ignored when a file is created and stays stable afterwards; `Description` is taken; the
  archive's `versionComment` is ignored, and the stored comment grows by a
  `DefaultCommitCommentImport@@@` prefix on every import. Every import creates a new version
  (`1.0` → `1.1` with changed content, → `1.2` unchanged).
- **Workflow and module imports ignore `Repository.zip`**: a module archive and a workflow archive
  whose `Repository.zip` carried a changed repository file were imported (`was modified`); the
  repository file stayed unchanged. Repository files can only be written in repository mode.
- **References**: a diagram-group export puts into `Repository.zip` the repository files its
  modules reference by `inubitrepository:/Root/<owner>/<path>` in an `xsl:import` or `xs:import`;
  the same URI as a string literal in an XSLT variable was **not** treated as a reference.
- **Tags**: `tag --tagMove <tag> --tagUser <owner> --tagWorkflowType technical
  --tagWorkflowGroup <group>` also tags the **referenced repository files** of the group
  (`tagName` in a tagged repository export); unreferenced files of the same folder are not
  tagged. `tag --tagMove <tag> --tagRepositoryPath <path>` tags a repository path directly; with
  `--tagUser` it is refused: `Cannot specify user when repository is tagged.`
- **Release export**: `export --exportWorkflowUser <owner> --exportWorkflowType technical
  --exportWorkflowGroup '' --exportTag <tag>` prints `1-OK: Workflow group exported
  successfully.` and returns **only** the diagram groups that carry the tag, in their tagged
  versions (`version="<n>"` instead of `head`, `@@@Tag: <tag>@@@` in the export suffix of every
  check-in comment, `tag="<tag>"` on every workflow node, an extra `usertags.xml`), with the
  tagged versions of their modules and of their referenced repository files (`Repository.zip`
  held version 1.0 of a file whose head was 1.1). The check-in comments of untagged diagram
  groups of the owner were **not** touched (a control group's comment grew only by its own two
  exports, section 3). An owner-wide tag export without any tagged diagram group was not probed.

Neutralized, synthetic fixtures of these shapes: `src/test/resources/fixtures/v8_1/cli/README.md`
(feature 005 section).

## 11. Repository files on the development stage (feature 008)

Probes P-1 to P-3 of
[feature 008](../../specs/008-repo-file-editing/research.md) (research D-3, D-7, D-8, D-12), run on
2026-10-13 with StartCLI 8.1.17 on the development stage and approved by the person. All writes
went to the person's personal test repository area (`/Root/<owner>/<test folder>`) and personal
test diagram group (`<test diagram group>`); nothing else was touched and nothing was deleted.
Names below are neutral (`<owner>`, `<test folder>`, `<test diagram group>`, `<tag>`). Neutralized
recordings: `src/test/resources/fixtures/v8_1/cli/README.md` (feature 008 section).

- **Content type on import (P-1)**: a `contentType` sent in the archive's metadata is stored
  unchanged, for every extension tried. Without one, INUBIT stores `application/octet-stream` for
  every file, `.xsd` and `.xml` included: it derives **nothing** from the file extension. The
  server therefore has to send a content type for every file it imports.
- **Observed content types (P-1)**: existing files of the person's repository root that no probe
  created have `application/x-xsd+xml` (`.xsd`) and `application/xml` (`.xml`); `.xsl` has
  `application/xslt+xml` (the feature-005 fixture `export_repository.zip`, and the person's only
  `.xsl`, which a feature-005 probe imported with that type). There was no existing `.xslt` or
  `.wsdl` file: the values sent for them (`application/xslt+xml`, `application/wsdl+xml`) were the
  probe's own choice, and INUBIT stored them unchanged. INUBIT's own default for `.xslt` and
  `.wsdl` (for example from a Workbench upload) was **not** observed.
- **Folder import (P-2)**: one import of an archive with directory entries for a new folder and a
  new subfolder below `<test folder>`, two new files (`main.xsd` in the folder, `included.xsd` in
  the subfolder) and one changed existing file created both folders and both files. The re-export
  of `<test folder>` (recursive, subfolders included) returned all three files **byte-identical**
  to what was sent, including `included.xsd` with LF line ends and no trailing newline.
- **Versions (P-2)**: **every** import creates a new version of each file in the archive, also the
  restore of earlier content at the end (one file went `1.4` → `1.5` → `1.6` over a change and the
  restore, the other `1.1` → `1.2` → `1.3`); section 10 found the same for unchanged content. Each import prepends one more
  `DefaultCommitCommentImport@@@` to `versionComment` and changes `modified`. A `version` sent in
  the archive is ignored; INUBIT assigns the next one. The metadata of a re-export therefore
  differs after every import, a rollback to the earlier content included; only the content can
  match what was sent.
- **Metadata of new files (P-1, P-2)**: all 14 files created by the probes came back with
  `version="1.0"`, `versionComment="DefaultCommitCommentImport@@@null"`, `anonymousRead="false"` and
  a distinct `uuid` assigned by INUBIT (none was sent). The empty `<Description/>` the probe sent
  was dropped (the stored `<Property …/>` has no child). The two existing probe files, created by
  feature-005 probe imports whose archives carried `anonymousRead="true"`, have `"true"`.
- **Created files stay**: an import cannot delete; the files the probes created are still on the
  node (14 files below `<test folder>`).
- **Import output (P-2)**: the repository import prints the progress text `Completed = 0 MB / 0 MB `
  twice, each time after a carriage return and without a line break, so `1-OK: Imported
  successfully` stands **on the same line** as the second progress text (section 10 described them
  as separate lines). A classifier that looks for `n-OK` only at the start of a line misses it;
  `CliOutputClassifier` accepts the progress text before the message since feature 008.
- **Durations**: one repository export took 2.4–2.7 s (seven calls, a repository export with
  `--exportTag` included), one repository import 2.3–2.8 s (five calls), a diagram-group export of
  `<test diagram group>` 3.5–4.1 s at head and 2.9 s by tag, one `tag --tagMove` 3.8 s. The files
  were small (under 1 KiB); larger files were not timed.
- **Tags on indirectly referenced files (P-3)**: the modules of `<test diagram group>` reference
  only one stylesheet `A`, by `inubitrepository:/Root/<owner>/<test folder>/A.xsl`; `A` imports a
  second stylesheet by a **relative** `xsl:import` and a third one by an `inubitrepository:`
  `xsl:import`. After `tag --tagMove <tag> --tagUser <owner> --tagWorkflowType technical
  --tagWorkflowGroup <test diagram group>`, a repository export of `<test folder>` with
  `--exportTag <tag>` returned exactly these three files, each with `tagName`; the other files of
  the folder were not returned. The group tag therefore also covers repository files that only
  another repository file references.
- **Group export of indirectly referenced files (P-3)**: before the two nested stylesheets existed,
  the group's `Repository.zip` held `A` only; afterwards it held all three files, both in the head
  export and in the export by `--exportTag <tag>`. INUBIT's diagram-group export follows references
  inside repository files, relative and `inubitrepository:` alike.
- **Message-type schemas (2026-10-14, offline, research D-3)**: a group export carries a schema
  referenced only as a message type. In one raw diagram-group export from a local backup (no new
  INUBIT call), `Repository.zip` held a schema that the group's modules name only as
  `InputMessageTypeSchema`/`OutputMessageTypeSchema` (`/Root/<owner>/…`, no scheme) in their module
  index entries, and no file that the module references do not reach.
- **Not probed**: chains deeper than one level of indirection, and `xs:include` / `xs:import` in
  schemas as the indirect reference (the indirect references above are `xsl:import`).
- **Leftovers**: the tag `<tag>` stayed on `<test diagram group>` and on the three stylesheets in
  their tagged versions, because StartCLI removes a tag only owner-wide.
