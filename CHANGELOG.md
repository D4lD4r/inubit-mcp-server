# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Repository files on a development stage (feature 008): schemas, stylesheets, WSDLs and other XML
  files below `/Root/<owner>/` can be brought from the workspace into a development node, not only
  read:
  - Setting `development.repository` (`READ_ONLY` by default, or `WRITE`; defaults, group, node),
    effective only on a development stage. An explicit `WRITE` on a node that is not a development
    stage, or on a production group or its nodes, is a startup error; on a non-production group
    without a development node it is a warning. `--check-config` adds
    `, repository: write | read-only` to the development part of a node's line, and `list_nodes`
    reports `repositoryWrite`.
  - `import_artifacts` takes the new scope `repositoryPaths` (1–50 paths relative to the owner's
    repository root). A diagram-group or module import also carries the new and changed
    repository files that the modules reach, also through other repository files; a diagram group
    with only repository changes is a valid import. Every call that would send a repository file
    returns a preview with a server code first, also on a node with
    `development.confirmation: CLIENT`. The preview lists the files in `create`, `modify`,
    `existing` and `identical`, the workspace artifacts outside the scope that use them
    (`repositoryReferences`), a diff (`repositoryDiff`) and the shared-use warnings. The
    repository files are imported before the workflows and modules, verified byte for byte and
    written back to the workspace; on a failure they are rolled back from the backup first, and a
    failed repository rollback names the files and points to `restore_backup` or the Workbench
    (only to the Workbench and the backup's repository exports if the last re-export of the
    repository files failed).
    Created files are never removed (`createdNotRemoved`). The result's `tag` lists the imported
    repository files the group tag covers (`tag.repositoryFiles`); audit records name every file
    with its operation and hash, never its content.
  - `export_artifacts` takes the new scope `repositoryPaths` (1–20 files or folders, folders
    recursively, at most 500 files and 100 MiB); if any path is missing on the node, the whole
    call is `NOT_FOUND` and nothing is written. Two paths that differ only in letter case are
    refused with `INVALID_INPUT`.
  - `restore_backup` also restores the previous content of the repository files the backed-up
    development call changed, repository files first, with conflict check, verification and
    rollback, and always with a server code. Files that call created stay
    (`createdNotRemoved`); a file changed on the node since is a `CONFLICT`. Backups of
    deployments are restored as before, whatever the switch says.
  - Error code `REPOSITORY_WRITE_DENIED`: on a node whose `development.repository` is
    `READ_ONLY`, an import or restore that would create or change a repository file is refused as
    a whole, naming the paths, before anything is sent.

  See `docs/setup.md` ("Development settings", "Artifact workspace") and `docs/tools.md`
  ("Repository files (feature 008)", `restore_backup`).

### Changed

- Exports write the repository files the exported workflows and modules reach **transitively**:
  also files that only another repository file includes or imports, and the message-type schemas
  of the module index (`InputMessageTypeSchema`, `OutputMessageTypeSchema`). An export can
  therefore write more files than before. A URI in a string literal (e.g. in an `xsl:variable`)
  no longer counts as a reference. Repository files below another owner's root are no longer
  written; a warning counts them.
- `check_artifacts` also checks these references, including inside repository files (and still
  reports every `inubitrepository:` URI): a missing included schema or stylesheet, or a missing
  message-type schema, is `REPOSITORY_REF_MISSING`; one in another owner's root is
  `REPOSITORY_REF_UNVERIFIED`.
- The workspace history entry that records the verified state after `import_artifacts`,
  `restore_backup` or `set_active` carries the call's reason as a `Reason: <reason>` trailer next
  to `Server-State`.
- Backup manifests of development calls name the repository files the backup holds
  (`repositoryPaths`) and keep them, and their `kind`, when the call's outcome is recorded.
  Manifests of earlier versions stay readable.

### Fixed

- `check_artifacts` decodes percent-escaped repository paths before the existence check, so a
  reference to e.g. `a%20b.xsd` no longer gives a false `REPOSITORY_REF_MISSING`; its messages
  show the decoded path.

## [0.5.3] - 2026-10-17

### Fixed

- A repository import that INUBIT carried out is no longer taken for a failure. StartCLI prints
  the import's progress text `Completed = … MB / … MB` without a line break, so its `1-OK`
  message follows on the same line; the output parser now reads it there. Before, every
  repository import of `deploy_release` (the new and changed repository files a release brings
  along) ended as a failure although the node had taken the files.

## [0.5.2] - 2026-10-09

### Fixed

- The automatic git maintenance (`gc --auto` or geometric repack) that a commit in the workspace
  triggers now runs in the foreground of that commit instead of detaching. A detached
  maintenance outlived the call and kept writing packs while the next git call or the cleanup of
  the workspace ran (seen as a sporadic test failure "Failed to delete temp directory …
  .git/objects/pack"). Such a commit now takes a few seconds longer roughly every 6700 new
  objects.

## [0.5.1] - 2026-10-09

### Fixed

- INUBIT writes the outgoing connections of a workflow module in a non-deterministic order. Every
  comparison now ignores that order: `import_artifacts`, `set_active` and `restore_backup` no
  longer end in a false `VERIFY_MISMATCH`, a needless rollback, a false `rollback: FAILED` or a
  false `CONFLICT`; `deploy_release` classifies such workflows as `UNCHANGED` (or `LAYOUT_ONLY`
  if their layout differs as well), finds the source nodes consistent and verifies and rolls back
  without a false mismatch. Fingerprints of previews, backups and the deployment ledger are
  computed with the connections in one order.
- Real differences are still found: a connection added, removed or changed (target, id, label
  position), any other content change and a different order of the workflow modules themselves.
  What is imported, deployed, restored or stored is unchanged, and difference reports still show
  the renderings as the server wrote them.
- Fingerprints that 0.5.0 or earlier recorded in backups and in the deployment ledger are still
  recognised, so restoring such a backup or the next deployment preview is not refused or warned
  about for content that is unchanged. Known limitation: a fingerprint recorded by 0.5.0 or
  earlier while the server showed one connection order is not recognised after the order
  flipped, so restoring such an old backup is refused with `CONFLICT` (nothing is changed) and
  the next deployment preview may warn `OUTSIDE_CHAIN` for that workflow.
- `export_artifacts`, and the write-back after an import or a deployment, keep a workspace
  workflow file that differs from the server's new rendering only in that order: no rewritten
  file, no commit and no `MODIFIED` entry for it. The workspace still holds only renderings as
  the server wrote them.

## [0.5.0] - 2026-10-09

### Added

- `tools/inubit-cert-check.py` (feature 006): an operator tool for changed server certificates of
  a stage. `--check` compares the certificate every server presents with the stage's pin (text or
  `--json`; exit 0, 10 changed, 20 unreachable). `--accept STAGE FINGERPRINT` adopts a certificate
  only after re-verifying it on every server of the stage: backups, trust-store import, single-line
  pin change, validation with the server's `--check-config`, rollback on any failure or interrupt,
  one audit line. `--interactive` (for the launcher) asks in a desktop dialog when the server
  starts and never writes to stdout; `--prune` removes superseded trust-store entries and
  `--forget` drops a remembered rejection. Python 3.9 standard library only, tests in `tools/tests`
  (also run in CI). See `docs/setup.md`, section 5 "Certificate changes".

## [0.4.3] - 2026-10-08

### Fixed

- `import_artifacts`: the preview now lists, under `identical`, an unchanged workspace module that a
  changed workflow references but that the diagram group's export lacks (e.g. a module exported on
  its own, or left by a rolled-back import). Before, the import bound the workflow to that module
  without the preview saying so. Such a module is exported on its own and compared with its
  exported base. If it changed on the node since that export, the import is a `CONFLICT`.
  Unchanged modules inside the group's export are not listed.

### Changed

- The result of `import_artifacts` carries `identical` as well. The tool description, README and
  `docs/tools.md` explain `existing` and `identical`.

## [0.4.2] - 2026-10-08

Not tagged or released on its own; its changes are part of the 0.4.3 release.

### Fixed

- `import_artifacts`: an embedded stylesheet or WSDL that ends with a line break (as an editor
  saves it) no longer fails the verification with `VERIFY_MISMATCH`. INUBIT stores embedded XML
  documents without trailing whitespace: the archive is built that way, and the change set, the
  conflict check and the verification compare `.xsl` and `.wsdl` files without trailing
  whitespace and with either line end.
- `import_artifacts`: after a rolled-back import, the next import of the same diagram group is no
  longer a `CONFLICT`. The conflict check compares reviewed content, so the check-in comment and
  last update a rollback wrote are no change; a new artifact the node has already (e.g. a module
  the rollback left, `createdNotRemoved`) is not sent if identical, otherwise updated as a new
  version and shown as such in the preview (`existing`, `identical`). A module of the change set
  that the diagram group's export lacks is exported on its own, and a module file that an export
  wrote unchanged no longer counts as a local change. Content changes on the node and workflows
  open in the Workbench are still conflicts.
- The conflict and verification difference reports note differences the lines do not show
  (missing trailing newline, line ends, trailing whitespace, a missing file).

### Changed

- `list_nodes` reports `developmentEnabled` (`development.enabled && !production`); the
  description of `writeEnabled` says that it governs restart and kill only.

## [0.4.1] - 2026-10-08

### Changed

- Documentation brought up to date for 0.4.0: README, SECURITY.md, CONTRIBUTING.md,
  `docs/setup.md`, `docs/tools.md` and `docs/live-tests.md` (offered tools, requirements, audit,
  workspace lock, `--check-config` output, error codes and the `deploy_release` challenge).
- The titles of `restore_backup` ("Restore a development or deployment backup on an INUBIT node")
  and `run_e2e_test` ("Send a SOAP test message to an INUBIT node") cover deployment targets too.

## [0.4.0] - 2026-10-07

### Added

- Stage chain and deployments (feature 005): a group with a `deploy` record (`from`, `mode`
  `EXECUTE | PACKAGE_ONLY`, `exclude`) receives releases from exactly one source group;
  `--check-config` shows the chains and each node's `deploy` line.
- `deploy_release`: deploys every diagram group that carries a tag on the source into ONE target
  group, node by node. The first call is always a preview per node (new, changed, layout-only,
  unchanged, excluded, active flags, warnings such as `OUTSIDE_CHAIN`, `SHARED_MODULE`,
  `STAGE_SPECIFIC_VALUE`) with a server code valid `deployConfirmationTtl`; the call with the code
  re-checks, backs up, imports only what changed (repository files, modules, workflows per active
  flag) with each node's own secret values, verifies, rolls back a failing node and stops there,
  then tags the deployed groups, records the ledger and commits the verified state. Package-only
  groups (e.g. production) get owner-only import packages (archives, `diff.txt`,
  `warnings.txt`, `README.md` with the StartCLI commands) and never an import or a tag.
- Error codes `CHAIN_VIOLATION`, `SOURCE_INCONSISTENT` and `DEPLOY_LOCKED`; audit outcome
  `PACKAGED`.
- Deployment live test (`live/DeploymentLiveTest`, opt-in, never production or package-only).

### Changed

- `restore_backup` also restores a deployment backup on the node of the target group it was taken
  on (always with preview and server code); it is offered with an `EXECUTE` target even without a
  development node.
- `run_e2e_test` also runs on non-production nodes of groups that receive deployments, as their
  `e2eTests` allows; it is offered where such a node allows it.

### Fixed

- `import_artifacts` (and deployments): a newly created module was reported as `VERIFY_MISMATCH`
  and rolled back, because INUBIT puts one more `DefaultCommitCommentImport###` in front of the
  check-in comment of a created module; the reason check accepts that form.

## [0.3.0] - 2026-10-07

### Added

- Development on a development stage (feature 004), offered only if a node has
  `development.enabled: true` (never on production groups) and refusing every other node with
  `NOT_DEVELOPMENT`:
  - `import_artifacts`: imports the changed workflows of one diagram group (with their changed or
    new modules), or changed modules, from the workspace. The server enforces the sequence: checks
    of `check_artifacts`, conflict detection against the last verified server state (changed on
    the server, Workbench edit mode), server-side confirmation by default (preview and one-time
    code bound to inputs, workspace and server state, re-checked before sending), a backup,
    an archive with only the changed artifacts and the target's current secret values (in
    memory), protocol matching, verification by re-export with the reason in the check-in
    comment, then the commit with a `Server-State` trailer — or a rollback from the backup.
    Created artifacts are never removed (listed instead).
  - `restore_backup`: re-imports the backup of an earlier development call, limited to the
    artifacts that call changed, with its own backup, conflict check against the state the call
    left, verification and rollback.
  - `set_active`: activates or deactivates one workflow, built from the server's current state.
  - `tag_artifacts`: tags the head versions of whole named diagram groups and their modules
    (INUBIT tags only whole groups). Pre-check and verification export only the requested groups
    (never owner-wide); an existing tag name is reused — it moves to the current versions within
    the requested groups and stays elsewhere; a mismatch or a failing tag command is reported as
    `FAILED` and nothing is removed (a tag removal would act owner-wide).
  - `import_artifacts` takes an optional `tag` for a diagram-group import: the group is tagged
    after the verified import in the same audited call; a tag failure keeps the import
    (`EXECUTED`, `tag.applied: false`, warning to retry `tag_artifacts`); a module import with a
    tag is refused before anything is sent.
  - `run_e2e_test`: sends a SOAP envelope from the workspace (`e2eTests: FREE | CONFIRM`) with a
    test-id header and reports the response, process instances, errors and log entries, found by
    the test id or, marked uncertain, by workflow and time window.
- Settings `development.enabled`, `development.confirmation`, `e2eTests` and `e2e.soap.baseUrl`;
  optional end-to-end basic authentication from `<PREFIX>_<GROUP>[_<NODE>]_E2E_USERNAME` /
  `_E2E_PASSWORD`; `--check-config` shows the development and end-to-end settings per node.
- Backups in `~/.inubit-mcp/<profile>/backups`, kept 30 days (the newest per scope always);
  removals are audited. Every call of the development tools is audited.
- Writes work for user and user-group owners alike: every import names the owner with
  `--importUser` (INUBIT 8.1 refuses `--importUserGroup`). There is no owner-kind lookup and no
  `owners` setting; such a section in a profile is a startup error (unknown key).

## [0.2.0] - 2026-10-06

### Added

- `export_artifacts` (feature 003): exports technical workflows by diagram group, or single
  modules, through StartCLI into a local **workspace** as readable files — one file per workflow,
  per module the module and index files plus every embedded document (stylesheets, WSDLs,
  schemas), the referenced repository files, volatile values under `.meta/` — and records the
  export in the workspace's own git history (never pushed). Uncommitted changes are recorded
  first as `local changes`; an unchanged re-export records nothing. Every secret is replaced by
  `${secret:<property path>}` in memory before anything is written. Offered only if a node has a
  StartCLI installation.
- `check_artifacts`: offline checks of workspace files — workflow structure (edges, ids,
  Demultiplexer keys, parent references, referenced modules with a module-list lookup,
  variables, repository references, derived checksums), stylesheet runs on Saxon-HE 10 with
  deterministic stand-ins for INUBIT's XSLT functions (fixed GUIDs and time, 60 s deadline, no
  access to the server's environment or files outside the workspace), well-formedness and XSD
  validation; findings bounded by `resultLimits` with a full report under `.reports/`.
- Setting `workspace` (default `~/.inubit-mcp/<profile>/workspace`, created `rwx------`);
  `--check-config` shows `Workspace: <path> (ok | created | <problem>)`; two profiles may not
  share or nest their workspaces.
- Requirement for the workspace: `git` 2.32 or newer.

### Dependencies

- `net.sf.saxon:Saxon-HE:10.9` (MPL-2.0) for local stylesheet runs; listed in
  `THIRD-PARTY-NOTICES.md`.

## [0.1.1] - 2026-10-05

### Added

- `tools/check-identifiers.py`: checks the staged changes (`--staged`) or the whole working tree
  (`--all`) against the local identifier lists and reports findings as
  `file:line: <list> rule <n>: <masked match>`, never the full value.
- Git hooks in `.githooks/` that run this check; install them with
  `git config core.hooksPath .githooks`. `pre-commit` checks the staged changes; `pre-push` checks
  what the push publishes (`--push`: diffs, file names, messages and author/committer of the
  commits the push target does not have yet, annotated tags and the names of the pushed refs)
  and the working tree (`--all`).
- An optional local allowlist of exact values (generic words) that the check does not report.
- Setup guide: how to run the server from the Claude desktop app and other GUI clients on macOS,
  which do not read `~/.zshrc`, with a start script that loads the credentials from the Keychain.

### Changed

- The guard test `NoCustomerIdentifiersTest` also uses the regular expressions of the neutralize
  map and the synthetic map as forbidden patterns, not only the denylist.
- The identifier lists are also found in the default directory `~/.config/inubit-mcp/`
  (`%APPDATA%\inubit-mcp\` on Windows), so a local `mvn verify` runs the guard test; new variable
  `INUBIT_MCP_SYNTHETIC_MAP`. Without any list the test is still skipped, and the skip message
  names the lists it looked for.
- Findings of the guard test name the list and the rule number and show the match masked.
- Dependabot proposes Jackson 3 patch releases (security fixes); minor and major updates are still
  aligned by hand together with an MCP SDK update.

### Security

- Jackson 3 (`jackson-core`, `jackson-databind`, `jackson-dataformat-yaml`) 3.1.4 → 3.1.7, which
  fixes GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89, GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54,
  GHSA-gx83-3vf8-gh7j, GHSA-q4xh-88c3-wmh7, GHSA-wjgm-6hv5-3cvf, GHSA-vvgp-rfg2-7rr6 and
  GHSA-5gvw-p9qm-jgwh.

## [0.1.0] - 2026-10-05

First public release.

### Added

- Local MCP server over stdio for INUBIT 8.1, packaged as one executable JAR (Java 21).
- Read-only tools: `list_nodes`, `get_health`, `find_processes`, `query_logs`, `list_inventory`
  and `get_inventory_item` (health and readiness, failed, waiting or hanging process instances,
  log queries, diagram and module inventory with version histories and module usage).
- Write tools `restart_process` and `kill_process` for exactly one process instance on one node,
  off by default, with a production lock, server-side two-step confirmation bound to the instance
  state, and an append-only audit log per profile.
- Profiles: one YAML file per customer or project (`--profile <name>`), configurable terminology
  for the two levels (groups and nodes), per-profile credential prefixes, audit directories and
  temporary data; several profiles run side by side.
- Credentials only from environment variables, secret scrubbing of all output, a 60-second login
  back-off against account lockout, TLS with a dedicated trust store and certificate pinning.
- `--check-config` to validate a profile without starting the server, and `--version`.
- Version-specific REST and StartCLI adapters; an offline test suite with recorded fixtures.
- Migration guide for configuration files of the earlier `stages`/`servers` format.

[Unreleased]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.5.3...HEAD
[0.5.3]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.5.2...v0.5.3
[0.5.2]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.5.1...v0.5.2
[0.5.1]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.4.3...v0.5.0
[0.4.3]: https://github.com/D4lD4r/inubit-mcp-server/compare/983fa42dc6ebcdd8fe32e6a2b5b5aefeef1296fc...v0.4.3
[0.4.2]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.4.1...983fa42dc6ebcdd8fe32e6a2b5b5aefeef1296fc
[0.4.1]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/D4lD4r/inubit-mcp-server/releases/tag/v0.1.0
