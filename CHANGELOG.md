# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/D4lD4r/inubit-mcp-server/releases/tag/v0.1.0
