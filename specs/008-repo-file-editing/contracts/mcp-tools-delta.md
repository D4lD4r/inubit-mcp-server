# MCP tools delta (feature 008)

No new tool. Three tools get wider inputs and outputs, and `list_nodes` gets one field (see
[configuration-delta.md](configuration-delta.md)). Research D-4 to D-12.

## Repository paths in tool inputs (all tools)

- A path is **relative to the owner's repository root**, for example `schemas/order.xsd` or
  `stylesheets/common`. It stands for `/Root/<owner>/schemas/order.xsd`.
- The schema pattern is `^[A-Za-z0-9_.][A-Za-z0-9_.\- ]{0,199}(/[A-Za-z0-9_.][A-Za-z0-9_.\- ]{0,199})*$`.
  This is the segment rule of `CliCommand.REPOSITORY_PATH`.
- The server additionally refuses the segments `.` and `..`, an absolute path, an empty path and
  the root itself with `INVALID_INPUT` (FR-005).
- Outputs name repository files by their **full** path (`/Root/<owner>/…`). An artifact name never
  starts with `/`, so the two cannot be confused.

## `export_artifacts` (widened)

Input: new optional `repositoryPaths` (array, 1–20, unique, the pattern above). Exactly one of
`diagramGroups`, `modules` and `repositoryPaths` is allowed. Otherwise the call fails with
`INVALID_INPUT`: "Give exactly one of diagramGroups, modules or repositoryPaths".

Behaviour:
- One StartCLI repository export per path. Folders are exported recursively.
- At most 500 files and 100 MiB in total. A larger scope is refused with `INVALID_INPUT` before
  anything is written, naming the count and the size.
- Key material is withheld as in every export.
- Paths that differ only in letter case are refused with `INVALID_INPUT`, naming both.
- The export is committed as one history entry: `export <node>: repository <paths> (<n> files)`.
- If any given path is missing on the node, the whole call is `NOT_FOUND` naming every missing path;
  nothing is written and the workspace stays unchanged.

Output: unchanged shape. `changes` lists workspace paths below `repository/`.

Changed behaviour of diagram-group and module exports: the repository files written are now the
**transitive** closure of the references of the exported artifacts, within the export (D-3).

Description addition (quoted in `docs/tools.md`): "… or repository files and folders by their path
relative to the owner's repository root …".

## `import_artifacts` (widened)

Input: new optional `repositoryPaths` (array, 1–50, unique, the pattern above). `oneOf` becomes
`diagramGroup | modules | repositoryPaths`. Using `tag` with `repositoryPaths` is `INVALID_INPUT`.

The repository files the server includes:

| Scope | Included repository files |
|---|---|
| `repositoryPaths` | new and changed files at or below the given paths |
| `diagramGroup` | new and changed files in the transitive closure of every module that the group's workspace workflows use, changed or not |
| `modules` | new and changed files in the transitive closure of the given modules |

Other changed repository files are listed in `notImported`. A diagram group whose only changes are
repository files is valid (FR-011a).

Refusals:

| Code | When | Contacts the node? |
|---|---|---|
| `INVALID_INPUT` | a deleted repository file in scope; a file below another owner's root; key material (keystore, private key, certificate); `${secret:` in the content; a file over 10 MiB; more than 50 files to send; the extension of a file to be **created** (absent on the node, so known only after the node read) not in the content-type table (the message lists `.xsd .xsl .xslt .wsdl .xml`, as finalized by P-1); a file the node already holds keeps the node's content type; a path the CLI rules refuse; `tag` with `repositoryPaths` | no |
| `PRECONDITION_FAILED` | a repository file with a `Server-State` base whose `.meta` record is missing or unreadable ("export the path first"); a file without any base is NEW and follows `create` / `modify`+`existing` (research D-4) | no |
| `REPOSITORY_WRITE_DENIED` | the node is read-only and a file would be created or modified; the whole call is refused and the paths are named | only to read, nothing is sent (a call with a file changed in the workspace is refused before any read) |
| `CONFLICT` | the node's content differs from the base of a changed file; written to `.reports/conflict-<auditId>.diff` | read only |

Confirmation: every call that would send a repository file returns a challenge with a server-issued
code first, whatever `development.confirmation` says (D-6).

Challenge: the existing fields, plus:

```json
{"challenge": {
  "node": "dev/node1", "scope": "diagram group GRP-01", "baseCommit": "…",
  "create": ["/Root/jdoe/schemas/invoice.xsd"],
  "modify": ["Workflow-0001", "Mapping-0002", "/Root/jdoe/schemas/order.xsd"],
  "existing": [], "identical": [], "notImported": ["/Root/jdoe/stylesheets/other.xsl"],
  "checkWarnings": [],
  "repositoryReferences": [
    {"path": "/Root/jdoe/schemas/order.xsd", "artifacts": ["Mapping-0007 (GRP-02)"], "truncated": false}
  ],
  "repositoryDiff": ".reports/repository-<auditId>.diff",
  "confirmationCode": "…", "expiresAt": "…", "message": "…"}}
```

Result: the existing fields. `created`, `modified`, `identical` and `createdNotRemoved` carry
repository paths. `tag` gains an optional `repositoryFiles` list (D-12). Warnings name references
that were not followed (`… outside the owner's repository root`) and repository files that are only
referenced indirectly under a tag.

Order of execution: backup, `PENDING` audit record, repository import, artifact import (if any),
verification (repository files byte for byte; workflows and modules as before, including the
reason), then write-back and commit. On failure, roll back the repository files first, then the
artifacts.

## `restore_backup` (widened)

- **Backup of a development call:** also re-imports the previous content of the repository files
  that call modified. It runs the conflict check against the state the call left, needs the server
  code, verifies and rolls back on failure.
- **Created files:** listed in `createdNotRemoved`.
- **Node now read-only:** the whole restore is refused with `REPOSITORY_WRITE_DENIED`.
- **Deployment backups:** unchanged (feature 005), and the switch is not checked.
- **Output:** the restore preview's `modify` lists repository paths too.

## Audit inputs

For `import_artifacts` and `restore_backup`, there is one key per repository file:
`repository.<n>` = `<create|modify|existing|identical> /Root/<owner>/<path> sha256:<16 hex>`.
Content is never recorded (D-11).

## New error code

`REPOSITORY_WRITE_DENIED`: the node's `development.repository` is `READ_ONLY`. Next step: revert
the files in the workspace (`git restore`) or ask the administrator to set `WRITE` on that
development node.
