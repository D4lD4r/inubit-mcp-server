# INUBIT MCP Server

A local [Model Context Protocol](https://modelcontextprotocol.io) server (stdio, Java 21) that lets
an AI assistant such as Claude Code operate and diagnose **INUBIT 8.1** integration servers: check
health, find failed or hanging process instances and read the matching logs, inspect the inventory
of diagrams and modules, and — only where explicitly allowed — restart or kill a single process
instance. It uses the INUBIT REST API wherever possible and the INUBIT command-line client
(StartCLI) only where REST cannot do the job. One server process serves one **profile** (one
customer or project, one YAML file); several profiles run side by side as separate registrations.

## Tools

| Tool | What it answers | INUBIT access |
|---|---|---|
| `list_nodes` | which profile this is and which groups and nodes are configured (production flag, write access, CLI) | none |
| `get_health` | is a node or a whole group reachable and ready; maintenance mode, version, memory, threads, queue | REST |
| `find_processes` | failed (`ERROR`), waiting, queued or hanging process instances | REST |
| `query_logs` | log entries (systemLog, queueLog, auditLog, …) by time, workflow, severity, process id, text | REST |
| `list_inventory` | diagrams or modules of the configured owner, filtered by name, type, INUBIT group | REST (diagrams), StartCLI (modules) |
| `get_inventory_item` | one diagram or module: versions, tags, active flag, modules used | REST + StartCLI |
| `restart_process` | restart ONE process instance in `ERROR` on ONE node (two-step confirmation) | REST + StartCLI |
| `kill_process` | kill ONE process instance on ONE node (two-step confirmation) | REST + StartCLI |

The two write tools are registered only if at least one node of the profile has effective write
access; with the default configuration the server offers the six read-only tools. Inputs, outputs
and example prompts: [docs/tools.md](docs/tools.md).

## Safety model

- **Read-only by default.** `restart_process` and `kill_process` are off unless
  `write.enabled: true` is set for a group or node. They act on exactly one process instance on one
  node; there are no bulk operations.
- **Production lock.** On groups marked `production: true`, writes additionally require
  `write.productionOptIn: true`.
- **Two-step confirmation.** By default the server issues a short-lived, one-time confirmation code
  for each write, bound to the node, the action, the process instance and its state; the action
  runs only when that code comes back and the instance has not changed.
- **Audit.** Every write call that reaches the server (refused, previewed, executed or failed) is
  appended to an owner-only JSON Lines audit log per profile; if the record cannot be written, the
  action is not executed.
- **Credential guard.** After a rejected login the server does not retry that node's credentials
  for 60 s, so an assistant cannot lock the INUBIT account out. Passwords and tokens are scrubbed
  from every result, error message and log line.
- **Secrets only via environment variables.** Credentials are never read from the YAML file (a
  credential key there is a startup error) or from tool parameters; StartCLI receives the password
  on stdin, never as a process argument.
- **TLS on.** Self-signed server certificates are handled with a dedicated trust store plus a
  certificate pin, never by switching verification off.

Details: [docs/setup.md](docs/setup.md) and the project [constitution](.specify/memory/constitution.md).

## Requirements

- A **Java 21** (or newer) runtime to run the server.
- **INUBIT 8.1** servers reachable over HTTPS, and an INUBIT account per group or node with rights
  to read logs, monitoring and models.
- Only for the CLI-based parts (`restart_process`, `kill_process`, the module list and version
  histories of the inventory tools): a local **INUBIT 8.1 Workbench client installation** with
  StartCLI (`bin/startcli.sh`) matching the servers' patch level, a Java 17 runtime for StartCLI,
  and "CLI login access" for the account. The INUBIT client is **not included** in this project.
  Without it, the REST-based tools work and CLI-based parts report `CLI_UNAVAILABLE`. CLI-based
  tools are not supported on Windows in this version.

## Installation in Claude Code

1. **Download** `inubit-mcp-server-<version>.jar` and `inubit-mcp-server-<version>.jar.sha256` from
   the [latest release](https://github.com/D4lD4r/inubit-mcp-server/releases/latest).

2. **Verify** the checksum (both files in the same directory) and place the JAR, for example in
   `~/.local/lib`:

   ```bash
   VERSION=0.1.0
   shasum -a 256 -c "inubit-mcp-server-$VERSION.jar.sha256"   # Linux: sha256sum -c …
   mkdir -p ~/.local/lib && mv "inubit-mcp-server-$VERSION.jar" ~/.local/lib/
   ```

   Release JARs also carry a build provenance attestation, which the GitHub CLI can check:
   `gh attestation verify ~/.local/lib/inubit-mcp-server-$VERSION.jar --repo D4lD4r/inubit-mcp-server`.

3. **Create the profile** `~/.config/inubit-mcp/<profile>.yaml`. Minimal read-only example for a
   profile `acme` with one non-production node:

   ```bash
   mkdir -p ~/.config/inubit-mcp && chmod 700 ~/.config/inubit-mcp
   ```

   ```yaml
   # ~/.config/inubit-mcp/acme.yaml
   profile:
     name: acme

   groups:
     - name: dev
       nodes:
         - name: node1
           baseUrl: https://node1.dev.example.test:8443
   ```

   Terminology, inventory owner, StartCLI, write access, TLS trust store and certificate pin are
   described in [docs/setup.md](docs/setup.md#3-configuration-file).

4. **Set the credential variables** in your shell profile (e.g. `~/.zshrc`). By default their
   names are `INUBIT_<PROFILE>_<GROUP>[_<NODE>]_USERNAME` / `_PASSWORD`
   ([details](docs/setup.md#4-credentials-environment-variables)):

   ```bash
   export INUBIT_ACME_DEV_USERNAME='jdoe'
   export INUBIT_ACME_DEV_PASSWORD='…'
   ```

   Do not pass credentials with `claude mcp add -e …`: that stores them in plain text in the client
   configuration.

5. **Check the configuration** (prints variable names, never values; exit code 0 means OK):

   ```bash
   java -jar ~/.local/lib/inubit-mcp-server-$VERSION.jar --profile acme --check-config
   ```

6. **Register** the server with Claude Code (once per profile):

   ```bash
   claude mcp add --scope user inubit-acme -- \
     java -jar "$HOME/.local/lib/inubit-mcp-server-$VERSION.jar" --profile acme
   ```

7. **Restart Claude Code from a shell that has the variables.** The server inherits the environment
   of Claude Code and reads it once at startup; `/mcp` → reconnect is not enough after changing a
   variable. `/mcp` then shows `inubit-acme` with its tools; try "Which INUBIT systems do you
   know?" or "Is dev up?".

Several profiles: one YAML file and one registration (`inubit-acme`, `inubit-globex`, …) each; see
[docs/setup.md](docs/setup.md#several-profiles-side-by-side).

## Build from source

Requires JDK 21+ and Maven 3.9+:

```bash
mvn -q clean verify       # offline test suite, no INUBIT server needed
java -jar target/inubit-mcp-server-*.jar --version
```

The build produces the single executable JAR `target/inubit-mcp-server-<version>.jar`.

## Documentation

- [docs/setup.md](docs/setup.md) — configuration, credentials, TLS, `--check-config`, registration,
  several profiles side by side
- [docs/tools.md](docs/tools.md) — tool reference with inputs, outputs and example prompts
- [docs/migration-001-to-002.md](docs/migration-001-to-002.md) — migrating a configuration file of
  the earlier `stages`/`servers` format
- [docs/live-tests.md](docs/live-tests.md) — opt-in, read-only tests against a non-production server
- [docs/release-checks.md](docs/release-checks.md) — checks before a release
- [specs/](specs/) — specifications, plans and contracts of the features (Spec Kit)
- [CHANGELOG.md](CHANGELOG.md), [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md)

## Disclaimer

This software is provided "AS IS", without warranties or conditions of any kind, and without any
liability of the authors or contributors for any damage or other consequences arising from its
use, as stated in sections 7 and 8 of the [Apache License 2.0](LICENSE). You use it at your own
risk. The write tools can restart and kill process instances on INUBIT servers; enable them only
where you are allowed to do so, and always test against non-production systems first.

## Trademark

INUBIT is a trademark of Virtimo AG. This project is an independent work and is not affiliated
with, endorsed by or supported by Virtimo AG.

## License

Copyright 2026 Daniel Decker. Licensed under the [Apache License, Version 2.0](LICENSE); see also
[NOTICE](NOTICE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
