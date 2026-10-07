# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/D4lD4r/inubit-mcp-server/releases/tag/v0.1.0
