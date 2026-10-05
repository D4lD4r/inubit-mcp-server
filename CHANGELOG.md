# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
