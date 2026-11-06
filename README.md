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
cannot do the job. Nodes of an older **INUBIT 6.1** installation can be configured read-only:
they are reached through StartCLI only, never over REST (see [Requirements](#requirements)). One
server process serves one **profile** (one customer or project, one YAML file); several profiles
run side by side as separate registrations.

## Tools

| Tool | What it answers | INUBIT access |
|---|---|---|
| `list_nodes` | which profile this is and which groups and nodes are configured (production flag, write access, repository writes, CLI) | none |
| `get_health` | is a node or a whole group reachable and ready; maintenance mode, version, memory, threads, queue | REST |
| `find_processes` | failed (`ERROR`), waiting, queued or hanging process instances | REST |
| `query_logs` | log entries (systemLog, queueLog, auditLog, …) by time, workflow, severity, process id, text | REST |
| `list_inventory` | diagrams or modules of the configured owner, filtered by name, type, INUBIT group | REST (diagrams), StartCLI (modules) |
| `get_inventory_item` | one diagram or module: versions, tags, active flag, modules used | REST + StartCLI |
| `restart_process` | restart ONE process instance in `ERROR` on ONE node (two-step confirmation) | REST + StartCLI |
| `kill_process` | kill ONE process instance on ONE node (two-step confirmation) | REST + StartCLI |
| `export_artifacts` | export technical workflows (by diagram group), modules, or repository files and folders (by path) into a local, git-versioned workspace as readable files, with the repository files the exported artifacts reference (also indirectly); secrets replaced by placeholders, key material withheld | StartCLI `export` (read-only) |
| `check_artifacts` | check workspace files offline: workflow structure, referenced modules, a stylesheet run with deterministic stand-ins, XML/XSD validation | only the module list for modules missing locally |
| `export_archives` | export the technical workflows of one diagram group as Workbench-importable archives (one `.diagram.zip` per workflow) into a local folder outside the workspace, the server's directories, git working trees and network paths; the archives hold the workflows' **secrets in clear text**, so the call needs an explicit acknowledgement and, on a production group, `archives.productionOptIn`; existing files are never replaced | StartCLI `export` (read-only) |
| `import_artifacts` | import the changed workflows of one diagram group (with changed or new modules), changed modules, or new and changed repository files (by path), into one development node, together with the new and changed repository files they reference — checked, conflict-free, backed up, verified, rolled back on failure; the preview lists modules the node has already as `existing` (updated as a new version) or `identical` (not sent, the workflows are bound to them) | StartCLI `export` + `import` |
| `restore_backup` | re-import the backup of an earlier development call, or of a deployment on one node of a target group | StartCLI `export` + `import` |
| `set_active` | activate or deactivate one workflow on a development node | StartCLI `export` + `import` |
| `tag_artifacts` | tag the head versions of whole named diagram groups (never owner-wide; an existing tag name is reused), verified | StartCLI history `export` + `tag` |
| `run_e2e_test` | send a SOAP envelope from the workspace to a development node (or a non-production node of a target group) and report the response, processes, errors and logs it caused | SOAP + REST |
| `deploy_release` | deploy the diagram groups that carry a tag from a group into the next group of the stage chain, node by node (preview and code first; backed up, verified, rolled back per node), or write import packages for a package-only group | StartCLI `export`, `import`, `tag`; REST |

Which tools a profile offers depends on its configuration; every tool that changes INUBIT needs its
own explicit setting:

| Tools | Offered when |
|---|---|
| `list_nodes`, `get_health`, `check_artifacts` | always |
| `find_processes`, `query_logs`, `list_inventory`, `get_inventory_item` | a node is not pinned to an INUBIT version that refuses the tool (none of them is offered, e.g., in a profile of INUBIT 6.1 nodes only) |
| `export_artifacts`, `export_archives` | a node has a StartCLI installation and is not pinned to an INUBIT version that refuses the export |
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
- **Writes only on a verified INUBIT version.** Each call is checked against its node's INUBIT
  version, pinned with `versionLine` or detected from the node on first use (`AUTO`). Write tools
  run only on a known INUBIT 8.1 node. They are refused with `UNSUPPORTED_VERSION` before anything
  but the version detection is sent on an unknown version (also after a failed detection), on
  any version other than 8.1 (INUBIT 9.x, `V9_X`) and while `get_health` reads a version that
  contradicts the pin. On an unknown version and on versions other than 8.1, read tools run with
  a warning that their results are unverified — except on INUBIT 6.1, where every tool not
  declared supported for 6.1 is refused. StartCLI-backed tools are also refused where the
  local StartCLI client does not support the node's INUBIT major.minor, and `deploy_release`
  deploys only between nodes of the same minor version.
  `list_nodes` shows per node the version, the client and the usable capabilities without
  contacting it ([INUBIT version](docs/setup.md#inubit-version-versionline)).
- **Two-step confirmation.** By default the server issues a short-lived, one-time confirmation code
  for each write, bound to the node, the action and the state it previewed (process instance,
  workspace and server state, or release and target nodes); the action runs only when that code
  comes back and nothing it was bound to has changed. Deployments always need it.
- **Audit.** Every write call that reaches the server (refused, previewed, executed or failed) is
  appended to an owner-only JSON Lines audit log per profile; if the record cannot be written, the
  action is not executed.
- **Credential guard.** After a rejected login the server re-reads that node's credentials once
  and allows one login with changed values, then does not retry them for 60 s, so an assistant
  cannot lock the INUBIT account out: at most two failed logins per node per 60 s, and a changed
  password needs no restart. Passwords and tokens are scrubbed from every result, error message
  and log line.
- **Secrets only from the credential store or AWS Parameter Store.** Each node reads its
  credentials on first use from the credential store of the system (macOS Keychain, Windows
  Credential Manager, Linux Secret Service; the default) or from AWS Systems Manager Parameter
  Store ([details](docs/setup.md#4-credentials-sources)), never from environment variables, the
  profile or the conversation: the YAML file names entries or parameters only (a credential value
  there is a startup error), no tool takes or returns a secret, and no AWS access key is
  configured. StartCLI receives the password on stdin, never as a process argument.
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
- **Repository files only where allowed.** `import_artifacts` and `restore_backup` create or
  change repository files (schemas, stylesheets, WSDLs, …) only on a development node with
  `development.repository: WRITE` (default `READ_ONLY`; effective only on development nodes,
  never on production). Such a write always needs a **server-issued confirmation code**, also
  with `development.confirmation: CLIENT`, because a repository file is shared by every workflow
  and module that uses it. Key material and `${secret:…}` placeholders are never written, and no
  repository file is ever deleted. Deployments and their backups follow the stage chain rules
  below instead.
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
- **INUBIT 6.1** is supported read-only, through StartCLI only, for nodes pinned with
  `versionLine: V6_1` (or a concrete 6.1 version): the server sends them no REST request and
  never writes to them. In this version every tool except `list_nodes` and the offline checks
  of `check_artifacts` is still refused on 6.1 (`UNSUPPORTED_VERSION`); health and the process
  listing (`get_health`, `find_processes`) become available once they have been verified against
  recorded outputs of a real 6.1 node. Such nodes need a StartCLI client that supports INUBIT 6.1
  and its Java runtime ([INUBIT 6.1 nodes](docs/setup.md#inubit-61-nodes-v6_1)); like every
  StartCLI-backed part, not on Windows.
- Only for the CLI-based parts (`restart_process`, `kill_process`, the module list and version
  histories of the inventory tools, and every tool that exports, imports or tags artifacts —
  `export_artifacts`, `export_archives`, the development tools and `deploy_release`, which needs
  it for the nodes of the source and the target group): a local **INUBIT 8.1 Workbench client
  installation** with StartCLI (`bin/startcli.sh`; on Windows `bin\startcli.bat` on a local
  drive) matching the servers' patch level, a Java 17 runtime for StartCLI, and "CLI login
  access" for the account. The INUBIT client is **not included** in this project. Without it, the
  REST-based tools work and CLI-based parts report `CLI_UNAVAILABLE`. On Windows the CLI-based
  tools work as on macOS, with a few rules for paths and characters
  ([StartCLI on Windows](docs/setup.md#startcli-on-windows)).
- Only for the artifact workspace (`export_artifacts`, the development tools and
  `deploy_release`): **git 2.32** or newer on `PATH`.
- For the credentials: the credential store of the system — the macOS Keychain and the Windows
  Credential Manager are built in; on Linux `secret-tool` (package `libsecret-tools` or
  `libsecret`) and a running Secret Service (e.g. GNOME Keyring) in the desktop session.
  Otherwise, AWS Parameter Store ([details](docs/setup.md#4-credentials-sources)). Environment
  variables are not a credential source.

## Installation in Claude Code

1. **Download** `inubit-mcp-server-<version>.jar` and `inubit-mcp-server-<version>.jar.sha256` from
   the [latest release](https://github.com/D4lD4r/inubit-mcp-server/releases/latest).

2. **Verify** the checksum (both files in the same directory) and place the JAR, for example in
   `~/.local/lib` (Windows: `%LOCALAPPDATA%\inubit-mcp`).

   macOS and Linux:

   ```bash
   VERSION=<version>   # 0.7.0 or later reads the credential store; earlier releases read variables
   shasum -a 256 -c "inubit-mcp-server-$VERSION.jar.sha256"   # Linux: sha256sum -c …
   mkdir -p ~/.local/lib && mv "inubit-mcp-server-$VERSION.jar" ~/.local/lib/
   ```

   Windows (PowerShell; the comparison must print `True`):

   ```powershell
   $VERSION = '<version>'
   (Get-FileHash "inubit-mcp-server-$VERSION.jar" -Algorithm SHA256).Hash -eq (Get-Content "inubit-mcp-server-$VERSION.jar.sha256").Split(' ')[0]
   New-Item -ItemType Directory -Force "$env:LOCALAPPDATA\inubit-mcp" | Out-Null
   Move-Item "inubit-mcp-server-$VERSION.jar" "$env:LOCALAPPDATA\inubit-mcp\"
   ```

   Release JARs also carry a build provenance attestation, which the GitHub CLI can check:
   `gh attestation verify <path to the JAR> --repo D4lD4r/inubit-mcp-server`.

   The JAR names its own version in its manifest (`Implementation-Version`); `java -jar <JAR>
   --version` prints it, whatever the file is called. You may therefore also keep it under a
   version-free name such as `inubit-mcp-server.jar`, so the registration of step 6 stays the same
   after an update.

3. **Create the profile** `~/.config/inubit-mcp/<profile>.yaml` (Windows:
   `%APPDATA%\inubit-mcp\<profile>.yaml`). Minimal read-only example for a profile `acme` with one
   non-production node:

   ```bash
   # macOS and Linux: the directory is readable by you only
   mkdir -p ~/.config/inubit-mcp && chmod 700 ~/.config/inubit-mcp
   ```

   ```powershell
   # Windows: %APPDATA% is already readable by you only
   New-Item -ItemType Directory -Force "$env:APPDATA\inubit-mcp" | Out-Null
   ```

   ```yaml
   # acme.yaml in that directory
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

4. **Store the credentials** in the credential store of your system: one entry per value, service
   `inubit-mcp`, account `INUBIT_<PROFILE>_<GROUP>[_<NODE>]_USERNAME` / `_PASSWORD`
   ([details](docs/setup.md#4-credentials-credential-store)). The commands prompt for the value,
   so it never appears on a command line or in the shell history.

   macOS (Keychain):

   ```bash
   security add-generic-password -U -s inubit-mcp -a INUBIT_ACME_DEV_USERNAME -w
   security add-generic-password -U -s inubit-mcp -a INUBIT_ACME_DEV_PASSWORD -w
   ```

   Windows (Credential Manager; PowerShell or Command Prompt, the target name is
   `inubit-mcp:<ACCOUNT>`):

   ```powershell
   cmdkey /generic:inubit-mcp:INUBIT_ACME_DEV_USERNAME /user:INUBIT_ACME_DEV_USERNAME /pass
   cmdkey /generic:inubit-mcp:INUBIT_ACME_DEV_PASSWORD /user:INUBIT_ACME_DEV_PASSWORD /pass
   ```

   Linux (Secret Service, needs `secret-tool`):

   ```bash
   secret-tool store --label='inubit-mcp INUBIT_ACME_DEV_USERNAME' service inubit-mcp account INUBIT_ACME_DEV_USERNAME
   secret-tool store --label='inubit-mcp INUBIT_ACME_DEV_PASSWORD' service inubit-mcp account INUBIT_ACME_DEV_PASSWORD
   ```

   Do not put credentials into environment variables, a start script or
   `claude mcp add -e …` (which stores them in plain text in the client configuration): since
   0.7.0 the server does not read them ([migration-environment-variables.md](docs/migration-environment-variables.md)).

   Instead, a profile, group or node can read its credentials from AWS Systems Manager Parameter
   Store (`credentials.source`, [details](docs/setup.md#aws-systems-manager-parameter-store)); it
   then needs a valid AWS login.

5. **Check the configuration** (reads the entries, prints their names, never values; exit code 0
   means OK; on macOS the Keychain may ask once per entry whether `security` may read it):

   ```bash
   java -jar ~/.local/lib/inubit-mcp-server-$VERSION.jar --profile acme --check-config
   ```

   ```powershell
   java -jar "$env:LOCALAPPDATA\inubit-mcp\inubit-mcp-server-$VERSION.jar" --profile acme --check-config
   ```

6. **Register** the server with Claude Code (once per profile), with the **absolute path** of a
   Java 21 `java`: a client started from the desktop (Dock, Finder, Start menu, an application
   launcher) does not see your shell's `PATH`.

   macOS:

   ```bash
   claude mcp add --scope user inubit-acme -- \
     "$(/usr/libexec/java_home -v 21)/bin/java" -jar "$HOME/.local/lib/inubit-mcp-server-$VERSION.jar" --profile acme
   ```

   Linux (with a Java 21 `java` on `PATH`; `readlink -f` resolves it to its real path, e.g.
   `/usr/lib/jvm/java-21-openjdk-amd64/bin/java`):

   ```bash
   claude mcp add --scope user inubit-acme -- \
     "$(readlink -f "$(command -v java)")" -jar "$HOME/.local/lib/inubit-mcp-server-$VERSION.jar" --profile acme
   ```

   Windows (PowerShell; `(Get-Command java).Source` shows the path of the `java.exe` on `PATH`,
   e.g. `C:\Program Files\Eclipse Adoptium\jdk-21…\bin\java.exe`):

   ```powershell
   claude mcp add --scope user inubit-acme -- (Get-Command java).Source -jar "$env:LOCALAPPDATA\inubit-mcp\inubit-mcp-server-$VERSION.jar" --profile acme
   ```

   No environment variable and no start script are needed, so the same registration works for a
   client started from a terminal or from the desktop. (The optional start-up check for changed
   server certificates on macOS uses a small start script without credentials, see
   [docs/setup.md](docs/setup.md#certificate-changes).)

7. **Start Claude Code** (for Parameter Store log in to AWS first, see
   [AWS credentials](docs/setup.md#aws-credentials-and-aws-profiles)). `/mcp` shows
   `inubit-acme` with its tools; try "Which INUBIT systems do you know?" or "Is dev up?". A changed
   password only needs a changed entry, no restart.

Several profiles: one YAML file and one registration (`inubit-acme`, `inubit-globex`, …) each; see
[docs/setup.md](docs/setup.md#several-profiles-side-by-side).

## Updates

The server checks at start and then every 24 hours whether a newer release exists. It **never
downloads or installs** anything: update by hand with steps 1 and 2 above (replace the JAR the
registration runs) and restart the client.

- **What is sent**: one anonymous request to
  `https://api.github.com/repos/D4lD4r/inubit-mcp-server/releases/latest` with the user agent
  `inubit-mcp-server/<version>`. No profile, node, host name or credential leaves the machine.
- **No waiting**: the check runs in the background with short timeouts; it delays neither the start
  nor a tool call. A failure (offline, proxy, rate limit) is logged at DEBUG only and retried at the
  next daily check.
- **Where a newer release shows up** (each once per version):
  - a WARN line on stderr (the client's MCP log);
  - `serverUpdate` in the result of `get_health` ([tools.md](docs/tools.md#get_health));
  - a short note block in the first successful tool result of a session, so the assistant can
    mention it;
  - after a restart, in the server instructions, from the cache `~/.inubit-mcp/update-check.json`
    (also offline);
  - in clients that support MCP forms (elicitation), a dialog with the release link and the option
    "Do not remind me of this version again"; it opens after the first tool call and does not hold
    it up.
- **Switching it off**: `updateCheck: {enabled: false}` in the profile, or the environment variable
  `INUBIT_MCP_UPDATE_CHECK=off` for every profile the client starts. Then the server sends no
  request, reads and writes no cache and shows no notice.
- **Proxy**: the check uses the JVM's standard proxy settings; add them before `-jar` in the
  registration, e.g. `-Dhttps.proxyHost=proxy.example.test -Dhttps.proxyPort=3128`, or
  `-Djava.net.useSystemProxies=true`. A proxy that requires authentication is not supported;
  switch the check off there.

Details: [docs/setup.md](docs/setup.md#update-check-updatecheck).

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
