# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/D4lD4r/inubit-mcp-server/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/D4lD4r/inubit-mcp-server/releases/tag/v0.1.0
