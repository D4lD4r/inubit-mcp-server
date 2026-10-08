# INUBIT MCP Server

A local [Model Context Protocol](https://modelcontextprotocol.io) server (stdio, Java 21) that lets
an AI assistant such as Claude Code operate and diagnose **INUBIT 8.1** integration servers: check
health, find failed or hanging process instances and read the matching logs, inspect the inventory
of diagrams and modules, and — only where explicitly allowed — restart or kill a single process
instance. On a configured **development stage** it brings workspace changes back into INUBIT,
switches workflows on or off, tags tested states, restores backups and sends SOAP test messages.
Along a configured **stage chain** it deploys a tagged release into the next group, node by node
— or, for a package-only group such as production, prepares import packages for a person. It uses
the INUBIT REST API wherever possible and the INUBIT command-line client (StartCLI) only where REST
cannot do the job. One server process serves one **profile** (one customer or project, one YAML
file); several profiles run side by side as separate registrations.

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
| `export_artifacts` | export technical workflows (by diagram group) or modules into a local, git-versioned workspace as readable files, secrets replaced by placeholders | StartCLI `export` (read-only) |
| `check_artifacts` | check workspace files offline: workflow structure, referenced modules, a stylesheet run with deterministic stand-ins, XML/XSD validation | only the module list for modules missing locally |
| `import_artifacts` | import the changed workflows of one diagram group (with changed or new modules), or changed modules, into one development node — checked, conflict-free, backed up, verified, rolled back on failure; the preview lists modules the node has already as `existing` (updated as a new version) or `identical` (not sent, the workflows are bound to them) | StartCLI `export` + `import` |
| `restore_backup` | re-import the backup of an earlier development call, or of a deployment on one node of a target group | StartCLI `export` + `import` |
| `set_active` | activate or deactivate one workflow on a development node | StartCLI `export` + `import` |
| `tag_artifacts` | tag the head versions of whole named diagram groups (never owner-wide; an existing tag name is reused), verified | StartCLI history `export` + `tag` |
| `run_e2e_test` | send a SOAP envelope from the workspace to a development node (or a non-production node of a target group) and report the response, processes, errors and logs it caused | SOAP + REST |
| `deploy_release` | deploy the diagram groups that carry a tag from a group into the next group of the stage chain, node by node (preview and code first; backed up, verified, rolled back per node), or write import packages for a package-only group | StartCLI `export`, `import`, `tag`; REST |

Which tools a profile offers depends on its configuration; every tool that changes INUBIT needs its
own explicit setting:

| Tools | Offered when |
|---|---|
| the six read-only tools and `check_artifacts` | always |
| `export_artifacts` | a node has a StartCLI installation |
| `restart_process`, `kill_process` | a node has effective write access (`write.enabled`) |
| `import_artifacts`, `restore_backup`, `set_active`, `tag_artifacts` | a node has `development.enabled: true` (never on production) |
| `deploy_release` | a group has a `deploy` record (stage chain); with a target in mode `EXECUTE` also `restore_backup` for deployment backups |
| `run_e2e_test` | a development node, or a non-production node of a deployment target, allows it (`e2eTests`) |

Inputs, outputs and example prompts: [docs/tools.md](docs/tools.md); configuration, workspace,
development stages and the stage chain: [docs/setup.md](docs/setup.md).

## Safety model

- **Read-only by default.** Without configuration the server only reads. Every kind of write has
  its own explicit setting per group or node: `write.enabled` for `restart_process` and
  `kill_process` (exactly one process instance on one node, no bulk operations),
  `development.enabled` for the development tools, and a `deploy` record for deployments.
- **Production lock.** On groups marked `production: true`, writes additionally require
  `write.productionOptIn: true`; development settings and end-to-end tests are not allowed there.
- **Two-step confirmation.** By default the server issues a short-lived, one-time confirmation code
  for each write, bound to the node, the action and the state it previewed (process instance,
  workspace and server state, or release and target nodes); the action runs only when that code
  comes back and nothing it was bound to has changed. Deployments always need it.
- **Audit.** Every write call that reaches the server (refused, previewed, executed or failed) is
  appended to an owner-only JSON Lines audit log per profile; if the record cannot be written, the
  action is not executed.
- **Credential guard.** After a rejected login the server does not retry that node's credentials
  for 60 s, so an assistant cannot lock the INUBIT account out. Passwords and tokens are scrubbed
  from every result, error message and log line.
- **Secrets only via environment variables.** Credentials are never read from the YAML file (a
  credential key there is a startup error) or from tool parameters; StartCLI receives the password
  on stdin, never as a process argument.
- **Local artifact history, never pushed.** `export_artifacts` writes only to the local workspace
  and its local git history; secrets are replaced before anything is written, and nothing is ever
  sent anywhere. `check_artifacts` runs stylesheets without access to the server's environment,
  files outside the workspace or the network.
- **Development stage only, never deleting.** The development tools refuse every node that is
  not a development stage. Each write is checked first, refused on a conflict with a colleague's
  change or an open Workbench edit, backed up (owner-only, 30 days), sends only what changed with
  the target's own secret values, is verified by a re-export and rolled back from the backup on
  failure; by default it needs a server-issued confirmation code. Nothing is ever deleted in
  INUBIT.
- **Stage chain, never skipping a stage.** `deploy_release` deploys only into a group with a
  `deploy` record, and only from its configured source (`deploy.from`); the record is the write
  enablement of deployments. Every deployment first returns a preview per node and a
  **server-issued confirmation code** that cannot be switched off; with the code each node is
  re-checked, backed up, imported with only what changed and with its own secret values,
  verified, and rolled back from its backup on failure — the deployment then stops. Production
  in mode `EXECUTE` needs `write.productionOptIn`; a **package-only** group (e.g. production)
  never receives an import or a tag: the server only writes owner-only packages for a person to
  import. Nothing is ever deleted on a target.
- **TLS on.** Self-signed server certificates are handled with a dedicated trust store plus a
  certificate pin, never by switching verification off.

Details: [docs/setup.md](docs/setup.md) and the project [constitution](.specify/memory/constitution.md).

## Requirements

- A **Java 21** (or newer) runtime to run the server.
- **INUBIT 8.1** servers reachable over HTTPS, and an INUBIT account per group or node with rights
  to read logs, monitoring and models; where the development tools or deployments are used, also
  with rights to export, import and tag the owner's diagrams and modules.
- Only for the CLI-based parts (`restart_process`, `kill_process`, the module list and version
  histories of the inventory tools, and every tool that exports, imports or tags artifacts —
  `export_artifacts`, the development tools and `deploy_release`, which needs it for the nodes of
  the source and the target group): a local **INUBIT 8.1 Workbench client installation** with
  StartCLI (`bin/startcli.sh`) matching the servers' patch level, a Java 17 runtime for StartCLI,
  and "CLI login access" for the account. The INUBIT client is **not included** in this project.
  Without it, the REST-based tools work and CLI-based parts report `CLI_UNAVAILABLE`. CLI-based
  tools are not supported on Windows in this version.
- Only for the artifact workspace (`export_artifacts`, the development tools and
  `deploy_release`): **git 2.32** or newer on `PATH`.

## Installation in Claude Code

1. **Download** `inubit-mcp-server-<version>.jar` and `inubit-mcp-server-<version>.jar.sha256` from
   the [latest release](https://github.com/D4lD4r/inubit-mcp-server/releases/latest).

2. **Verify** the checksum (both files in the same directory) and place the JAR, for example in
   `~/.local/lib`:

   ```bash
   VERSION=0.4.0
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

   Terminology, inventory owner, StartCLI, write access, development stages, the stage chain,
   end-to-end tests, the workspace, TLS trust store and certificate pin are described in
   [docs/setup.md](docs/setup.md#3-configuration-file).

4. **Set the credential variables** in your shell profile (e.g. `~/.zshrc`). By default their
   names are `INUBIT_<PROFILE>_<GROUP>[_<NODE>]_USERNAME` / `_PASSWORD`
   ([details](docs/setup.md#4-credentials-environment-variables)):

   ```bash
   export INUBIT_ACME_DEV_USERNAME='jdoe'
   export INUBIT_ACME_DEV_PASSWORD='…'
   ```

   Do not pass credentials with `claude mcp add -e …`: that stores them in plain text in the client
   configuration. The Claude desktop app and other clients started from the Dock do not read
   `~/.zshrc`; for them, load the credentials from the macOS Keychain with a start script
   ([details](docs/setup.md#claude-desktop-and-other-gui-clients-macos)).

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
  several profiles side by side, the artifact workspace, development stages, the stage chain and
  deployments
- [docs/tools.md](docs/tools.md) — tool reference with inputs, outputs, error codes and example
  prompts
- [docs/migration-001-to-002.md](docs/migration-001-to-002.md) — migrating a configuration file of
  the earlier `stages`/`servers` format
- [docs/live-tests.md](docs/live-tests.md) — opt-in tests against non-production servers: read-only,
  a development test on a test diagram group, and a deployment test on an approved target group
- [docs/release-checks.md](docs/release-checks.md) — checks before a release
- [specs/](specs/) — specifications, plans and contracts of the features (Spec Kit)
- [CHANGELOG.md](CHANGELOG.md), [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md)

## Disclaimer

This software is provided "AS IS", without warranties or conditions of any kind, and without any
liability of the authors or contributors for any damage or other consequences arising from its
use, as stated in sections 7 and 8 of the [Apache License 2.0](LICENSE). You use it at your own
risk. The write tools can restart and kill process instances, import, activate and tag workflows
and modules, and deploy releases on INUBIT servers; enable them only where you are allowed to do
so, and always test against non-production systems first.

## Trademark

INUBIT is a trademark of Virtimo AG. This project is an independent work and is not affiliated
with, endorsed by or supported by Virtimo AG.

## License

Copyright 2026 Daniel Decker. Licensed under the [Apache License, Version 2.0](LICENSE); see also
[NOTICE](NOTICE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
