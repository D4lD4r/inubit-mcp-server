# Spike: development and deployment (INUBIT 8.1.17)

Findings of the spike in section 10 of
[the development and deployment design](../design/2026-10-05-development-and-deployment.md),
run on 2026-10-06 against a development stage with INUBIT 8.1.17 and the StartCLI of the 8.1.17
client. Reads covered the shared owner's artifacts and, for secrets, the next stage; **all writes
went to two test workflows in one person's own diagram group**. Names below are neutral
(`<owner>`, `<user>`, `SPIKE…`); counts are from the real system.

## Summary for features 003–005

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
