# Setup

How to build, configure and register the INUBIT MCP server. The authoritative configuration
reference is [contracts/configuration.md](../specs/002-customer-agnostic-config/contracts/configuration.md)
of feature 002 with the deltas of feature 003
([workspace](../specs/003-artifact-workspace/contracts/configuration-delta.md)), 004
([development stage](../specs/004-development-stage/contracts/configuration-delta.md)), 005
([stage chain](../specs/005-stage-chain-deployment/contracts/configuration-delta.md)), 009
([INUBIT version](../specs/009-inubit-version-detection/contracts/configuration-delta.md)) and 010
([secret sources](../specs/010-secret-sources/contracts/configuration-delta.md)); this
guide walks through it. All host names below are examples (`*.example.test`).

## 1. Prerequisites

| What | Why |
|---|---|
| JDK 21 or newer on `PATH` (`java -version`) | runs the server |
| Maven 3.9+ (`mvn -v`) | only to build the JAR from source; a released JAR can be downloaded instead ([README](../README.md#installation-in-claude-code)) |
| INUBIT 8.1.17 client installation (Workbench), e.g. `/opt/inubit/client` with `bin/startcli.sh` | CLI-backed tools only (process restart/kill, module list and version histories of the inventory, `export_artifacts`, every development tool except `run_e2e_test`, and `deploy_release`, which needs it on every node of a target group and of its source group); the INUBIT versions it declares must include the server's major.minor, and it should match the servers' patch level ([StartCLI client check](#startcli-client-check)) |
| Temurin (or another) JDK **17** | StartCLI 8.1 runs on Java 17, not on the JDK of the server |
| An INUBIT account per group (or node) with rights to read logs, monitoring and models, plus "CLI login access" for CLI-backed tools, and rights to export, import and tag the owner's diagrams and modules where the development tools or deployments are used | REST and CLI access |
| `openssl` and `keytool` (part of the JDK) | only for a self-signed server certificate (section 5) |
| `git` 2.32 or newer on `PATH` (`git --version`) | only for the history of the artifact workspace (`export_artifacts`, `import_artifacts`, `restore_backup`, `set_active`, `deploy_release`; section 3); the history stays local |

Without a client installation the REST-backed tools still work; CLI-backed parts report
`CLI_UNAVAILABLE`. CLI-backed tools are not supported on Windows in this version.

## 2. Build

```bash
mvn clean verify
```

This runs the offline test suite (no INUBIT server needed) and produces the single executable JAR
`target/inubit-mcp-server-<version>.jar`.

```bash
java -jar target/inubit-mcp-server-*.jar --version
```

## 3. Configuration file

The server reads one YAML file, found in this order (first match wins):

1. `--config <path>`
2. `--profile <name>`: the file `<name>.yaml` in `~/.config/inubit-mcp` (Windows:
   `%APPDATA%\inubit-mcp\<name>.yaml`)
3. the environment variable `INUBIT_MCP_CONFIG` (a path)
4. the environment variable `INUBIT_MCP_PROFILE` (a profile name, resolved like `--profile`)
5. `~/.config/inubit-mcp/config.yaml` (Windows: `%APPDATA%\inubit-mcp\config.yaml`)

A location given explicitly (1–4) that does not exist is an error; the server never falls back
to another file. `--config` and `--profile` cannot be combined. A file selected by a profile name
(2 or 4) must declare exactly that name as `profile.name`, otherwise the server does not start.

A file in the format of feature 001 (top-level `stages`, `servers` lists, no `profile` block) is
refused at startup and by `--check-config` (exit code 1), with a message that names every key to
change; so is a file that mixes old and new keys. Follow
[migration-001-to-002.md](migration-001-to-002.md) to turn it into a profile that keeps its
nodes and credential variables.

The file describes one **profile** (one customer or project) with its groups (e.g. stages such as
`dev`, `test`, `prod`) and their nodes (the INUBIT servers), **never
credentials**: a key named `username`, `credentials` or containing `password` is a startup error.
The only exceptions are the `credentials` sections at the top, group and node level (section 4),
which say where the credentials come from and never hold a value: inside them only `envPrefix`
(top level only), `source` and `parameters` are allowed, and the keys inside `parameters`
(`username`, `password`, …) hold Parameter Store names, not values (any other key, also a
`password` key directly in `credentials`, is an error as well). One server
process serves exactly one profile; several customers mean several files and registrations
(section 7).

Create the directory once (owner-only, it will also hold trust stores):

```bash
mkdir -p ~/.config/inubit-mcp && chmod 700 ~/.config/inubit-mcp
```

Save the profile as `~/.config/inubit-mcp/<name>.yaml`, named exactly like its `profile.name`
(here `acme.yaml`), so that `--profile <name>` finds it.

Minimal example with one non-production group, read-only. It validates as it is (with the
variables of section 4 set); nodes with self-signed certificates additionally need the `tls`
block of section 5:

```yaml
profile:
  name: acme                       # required
  description: ACME integration platform   # optional

groups:
  - name: dev
    nodes:
      - name: node1
        baseUrl: https://node1.dev.example.test:8443
```

A complete, neutral example profile (fictitious customer `acme`, all hosts `*.example.test`) with
its own terminology, the inventory owner, the INUBIT client for the CLI-backed tools and a trust
store with a certificate pin (section 5) shared through a YAML anchor:

```yaml
profile:
  name: acme
  description: "ACME – INUBIT integration platform"

terminology:                       # the words the assistant reads for the two levels
  group: { singular: Umgebung, plural: Umgebungen }
  node:  { singular: Knoten,   plural: Knoten }

credentials:
  envPrefix: INUBIT_ACME           # the default for acme, shown for clarity (section 4)

logLevel: INFO

defaults:
  timeout: PT5S
  inventory:
    owner: INTEGRATION             # no built-in default; required for the inventory tools
  cliHome: /opt/inubit/client      # optional: CLI-backed tools (bin/startcli.sh)
  cliJavaHome: /usr/lib/jvm/temurin-17

x-tls: &tls                        # optional: self-signed certificates (section 5)
  trustStore: ~/.config/inubit-mcp/acme-truststore.p12
  pinnedCertificateSha256: "AB:CD:…"   # the SHA-256 fingerprint of the server certificate
  disableHostnameVerification: true    # only if the certificate does not match the host name

groups:
  - name: test
    tls: *tls
    write:
      enabled: true
    nodes:
      - name: node1
        baseUrl: https://inubit-test-1.example.test:8443
      - name: node2
        baseUrl: https://inubit-test-2.example.test:8443
  - name: prod
    production: true
    tls: *tls
    nodes:
      - name: node1
        baseUrl: https://inubit-prod-1.example.test:8443
```

If `cliHome` does not contain `bin/startcli.sh` (or the JDK path does not exist), this is a
warning, not an error: CLI-backed tools then report `CLI_UNAVAILABLE`. Remove both lines if you
use REST tools only.

- The YAML keys are the same for every profile: `groups` (the first level, e.g. stages) and, in
  each group, `nodes` (the INUBIT servers). Node ids are `<group>/<node>` (here `dev/node1`);
  a group id (`dev`) addresses all its nodes.
- Write access is off by default. It is enabled per group or node with `write.enabled: true`;
  groups with `production: true` additionally need `write.productionOptIn: true`.
- `versionLine` says which INUBIT version a node runs; the default `AUTO` detects it from the
  node on first use. Write tools run only on a known, supported version (8.1); see
  [INUBIT version](#inubit-version-versionline).
- Durations are ISO-8601 (`PT5S`, `PT10M`); `~` at the start of a path is your home directory.
- Other settings, each with a working default (details in the configuration contract):

  | Setting | Default | Meaning |
  |---|---|---|
  | `timeout` | `PT5S` | timeout of one REST call; a health overview of all servers returns within the largest `timeout` + 1 s |
  | `cliTimeout` | `PT30S` | timeout of one StartCLI call of `restart_process` / `kill_process`; also bounds the StartCLI client check outside `get_health` ([StartCLI client check](#startcli-client-check)) |
  | `hangingThreshold` | `PT60M` | when an `ACTIVE`, `WAITING` or `QUEUED` instance counts as hanging (`find_processes`) |
  | `resultLimits.maxItems` / `.maxChars` | `100` / `50000` | top level: largest page and largest result (characters of JSON) per tool call |
  | `logLevel` | `INFO` | top level: `ERROR`, `WARN`, `INFO`, `DEBUG` or `TRACE`; logs go to stderr as JSON lines |
  | `auditDirectory` | `~/.inubit-mcp/<profile.name>/audit` | top level: where audit records of write actions are written |
  | `allowInsecureHttp` | `false` | per node: allows an `http://` `baseUrl` (startup warning; never for real systems — credentials would travel unencrypted) |

  The durations (`timeout`, `cliTimeout`, `hangingThreshold`, `cliExportTimeout`,
  `confirmationTtl`) and `inventory` can be set in `defaults`, per group or per node
  (`deployConfirmationTtl` only in `defaults`, `deploy` only per group, see
  [Stage chain and deployments](#stage-chain-and-deployments));
  `versionLine`, `write`, `tls` and `cli` (`cli.home`, `cli.javaHome`, `cli.url`) per group or
  per node; `cliHome` / `cliJavaHome` in `defaults`. The most specific value wins.
- Top-level keys starting with `x-` are ignored, so YAML anchors can share blocks
  (`x-tls: &tls {…}` and `tls: *tls`).

### Profile, terminology and credential prefix

```yaml
profile:
  name: acme                         # required
  description: "ACME – INUBIT integration platform"   # optional

terminology:                         # optional
  group: { singular: stage,  plural: stages }     # as inside an English sentence
  node:  { singular: server, plural: servers }

credentials:                         # optional
  envPrefix: INUBIT_ACME
```

| Setting | Default | Rules |
|---|---|---|
| `profile.name` | — (required) | `^[a-z0-9][a-z0-9-]{0,31}$`, e.g. `acme`; `audit` is reserved (its audit directory would lie inside the one of feature 001) |
| `profile.description` | none | at most 200 characters, one line |
| `terminology.group.singular` / `.plural` | `group` / `groups` | 1–32 characters: letters, digits, space, `_`, `-`; starts with a letter |
| `terminology.node.singular` / `.plural` | `node` / `nodes` | as above; the group and node names must differ (ignoring case), singular and plural each |
| `credentials.envPrefix` | `INUBIT_` + the profile name in upper case, every character other than `A–Z`/`0–9` replaced by `_` (`acme` → `INUBIT_ACME`) | `^[A-Z][A-Z0-9_]{0,63}$` |

- The terminology is display text only: tool descriptions, parameter and result-field
  descriptions, messages (errors, warnings, next steps), the `--check-config` summary and the
  `terminology` part of the `list_nodes` result use it. YAML keys, tool names, input and result
  fields stay `groups`/`nodes`, `group`/`node` for every profile.
- Write the names as they appear inside an English sentence: `stage` for an English noun,
  `Umgebung` for a German one. They are inserted exactly as written ("one Umgebung", "all its
  Knoten", "one stage", "all its instances"); only at the start of a sentence or in a title is
  the first letter upper-cased ("Stage 'prod' …"). The texts never put "a"/"an" before a name
  and never inflect it, so the plural is always the configured plural.
- A given level needs both `singular` and `plural` (also `group: {}` is an error); an omitted
  level keeps its defaults.
- The profile is visible to the assistant: every tool description starts with
  `[<name>: <description>] ` (or `[<name>] `), the MCP `initialize` result carries the title
  "INUBIT MCP – <name>" and instructions naming the profile and its terminology, and `list_nodes`
  returns `profile` and `terminology`.
- The description may contain no control characters (tabs, line breaks, …); its length counts
  characters as Unicode code points.
- All profile errors are reported together at startup.
- `credentials.envPrefix` names the credential variables of the profile (section 4). Without it,
  the prefix is derived from the profile name, so two profiles never read the same variables by
  default.

### INUBIT version (`versionLine`)

`versionLine`, per group or per node (the node wins), says which INUBIT version a node runs. The
server uses it to decide which tools may run on the node: on INUBIT 8.1.x every tool runs; on any
other version (9.0, a later version) and on a version that is not known, read tools run with the
8.1 adapters and a warning in the result, and write tools are refused.

| Value | Detection | What runs on the node |
|---|---|---|
| `AUTO` (default) | from the node itself, see below | per the detected version; while the version is unknown, reads run with a warning and writes are refused |
| `V8_1` | none | everything (INUBIT 8.1) |
| `V9_X` | none | reads with a warning that the version is not verified; writes refused |
| a concrete version, e.g. `"8.1.17"` or `"9.0.7-HF1"` | none | per the version's major.minor (`"8.1.17"`: everything; `"9.0.7-HF1"`: reads with a warning, writes refused); the patch is used for the [StartCLI client check](#startcli-client-check) |

```yaml
groups:
  - name: dev
    versionLine: AUTO            # the default: detected from the node on first use
    nodes:
      - name: node1
        baseUrl: https://inubit-dev-1.example.test:8443
      - name: node2
        baseUrl: https://inubit-dev-2.example.test:8443
        versionLine: "8.1.17"    # concrete pin: always quote it
  - name: next
    versionLine: V9_X            # line pin: reads run unverified, writes are refused
    nodes:
      - name: node1
        baseUrl: https://inubit-next-1.example.test:8443
```

- A concrete version needs major, minor and patch; a suffix (`-HF1`, ` build 42`) is kept for
  display and ignored for matching. **Quote it**: an unquoted `8.1` is a YAML number and is
  refused. The values are case-sensitive (`auto` is refused). Any other value — a number, a
  version without patch such as `"8.1"`, `V10` — is a configuration error that names the key, not
  the value:
  `Invalid versionLine at groups[0].nodes[1].versionLine in <file>: expected AUTO, V8_1, V9_X or a full version such as "8.1.17"`.
- `--check-config` and `list_nodes` show the value as configured.
- `list_nodes` shows per node the setting, the last known server version with its source and
  time (or `not_yet_detected` / `unknown` with the reason), the StartCLI client, a support status
  and the usable capabilities; it never contacts a node ([tools.md](tools.md)).

**When the version is detected (`AUTO`).** Nothing is contacted at startup, by `list_nodes` or by
`--check-config`.

- The first tool call on a node that needs the version sends one `/system/info` request before
  its own requests. `get_health` reads `/system/info` anyway; the version it reads counts as the
  node's current version (also when it differs from an earlier detection).
- Reads then use the known version. Before a **write** the server detects again if the known
  version is older than the node's `inventory.cacheTtl` (default `PT10M`, see
  [Inventory settings](#inventory-settings)) or was marked stale. A response the 8.1 adapters did
  not expect marks it stale: `UNEXPECTED_RESPONSE`, including HTTP 406 and a 404 from a fixed
  endpoint such as `/system/info` (a 404 for a requested process or diagram does not).
- A failed detection (e.g. rejected credentials, a timeout) is not repeated within 60 s, so that
  rejected credentials are not tried again on every call (account lockout). While no version is
  known, reads run with the warning "the INUBIT version of `<node>` is unknown (`<code>` at
  `<time>`); results are unverified" and writes are refused with `UNSUPPORTED_VERSION`, until a
  later detection or a `get_health` call reads the version. A write whose re-detection failed is
  refused too, while reads keep the version known before.
- To skip the detection, pin the version the node actually runs (`V8_1` or a concrete version).

**Pinned nodes** (`V8_1`, `V9_X`, a concrete version) send no detection request. `get_health`
still reads the version for display; from then on that version is the one the node is judged by.
If its major.minor differs from the pin (e.g. after an upgrade), `get_health` warns and writes on
that node are refused until a later `get_health` reads a matching version or the server is
restarted with a corrected pin. A difference in the patch only gives a warning.

**Deliberate behaviour changes** (feature 009) — earlier versions ran every tool with the 8.1
adapters and a warning in these cases:

- **Writes after a failed `AUTO` detection are refused** (`restart_process`, `kill_process`, the
  development writes, `run_e2e_test`, `deploy_release`). Fix the cause of the failed detection and
  retry after a minute, or pin the version the node actually runs.
- **Writes on `V9_X` and on detected 9.x servers are refused**; read tools run with a warning
  such as "INUBIT 9.x (versionLine V9_X) on `<node>` is not verified for diagnosis; results may be
  incomplete". `--check-config` warns for every `V9_X` node: `<id>: versionLine V9_X:
  write tools are refused on this node (INUBIT 9.x is not supported for writes yet); read tools
  run unverified`. A write tool is not announced at all when every node that could run it is
  pinned to a version that does not support it (e.g. only `V9_X` nodes have `write.enabled`);
  `AUTO` nodes count as capable.
- `deploy_release` is refused unless every source and target node runs a known, supported
  version with the same major.minor (8.1.17 → 8.1.18 is allowed); a `V9_X` node is refused by
  the version check like 9.0.

Refusals name the node, its version and the missing capability, and are returned before anything
but the version detection is sent; see `UNSUPPORTED_VERSION` in [tools.md](tools.md).

### StartCLI client check

For every node with a StartCLI installation (`cliHome` with `bin/startcli.sh`) the server runs
`startcli.sh -v` **locally** — no credentials, no server URL, no INUBIT contact — and reads the
client version and the INUBIT versions it declares ("Supported inubit Process Engine versions
from 4.0.1 to 8.1.17."). The check runs

- in `get_health`, in parallel with the REST checks and within the health time budget; if it does
  not finish in time, the report lists the part `client` as unavailable
  (`not yet detected (timeout)`);
- before the first StartCLI-backed tool call on a node, as a run of its own bounded by the node's
  `cliTimeout` (not counted against the tool's own StartCLI timeout);
- in `--check-config` ([section 6](#6-check-the-configuration)).

A successful result is kept until the server stops: restart or reconnect the MCP server after
changing the client installation. A failed or timed-out check is retried at most once per 60 s.

The client is compared with the node's server version (the most recent one detected or read by
`get_health`; for a pinned node without such a reading, the pin):

| Case | Effect |
|---|---|
| the server's major.minor lies outside the client's declared range (bounds inclusive, by major.minor; e.g. an 8.1.17 client at a 9.0 or `V9_X` node) | StartCLI-backed tools refused on that node with `UNSUPPORTED_VERSION`, naming the client version and range; REST-backed tools are unaffected |
| inside the range, different patch (8.1.17 client, 8.1.18 server: `mismatch`) | StartCLI-backed tools run with a warning that their results may be unreliable |
| the range cannot be evaluated (no range printed, the check failed or timed out, server version unknown) | StartCLI-backed reads run with a warning naming the cause (writes on an unknown version are refused by the version check anyway) |
| no StartCLI installation | client `not_available`; StartCLI-backed tools report `CLI_UNAVAILABLE` as before |

### Inventory settings

The inventory tools (`list_inventory`, `get_inventory_item`) use three settings, each
configurable in `defaults`, per group or per node:

| Setting | Default | Meaning |
|---|---|---|
| `inventory.owner` | none (required for the inventory tools) | the owning Workbench user or user group whose diagrams and modules are listed. It is inserted into StartCLI commands, so it must match `^[A-Za-z0-9_.][A-Za-z0-9_.\- ]{0,199}$` (startup error otherwise). Without an owner for a node, `list_inventory` and `get_inventory_item` return `NOT_CONFIGURED` for that node ("set inventory.owner for <group>/<node> or in defaults"); all other tools work. `--check-config` warns once when no owner is set anywhere while a CLI is configured |
| `inventory.cacheTtl` | `PT10M` | how long diagram lists, module lists and version histories are cached per node; tools accept `refresh: true` to bypass it |
| `cliExportTimeout` | `PT120S` | timeout of one StartCLI export, import or tag command (module list about 10–15 s, version history of a diagram group about 5 s on DEV); also the budget of the module usage index (reading the nodes of every technical workflow over REST, about 2–3 s on DEV); separate from `cliTimeout` |

```yaml
defaults:
  cliExportTimeout: PT120S
  inventory:
    owner: INTEGRATION
    cacheTtl: PT10M
```

The module list and all version histories need the CLI (`cliHome`, `cliJavaHome`); without it
those parts are reported as `CLI_UNAVAILABLE`, while the diagram list and diagram details work
over REST.

The exports are written to `java.io.tmpdir` (on macOS a per-user directory below
`/var/folders/…`). Its path is part of the StartCLI command, so it may contain only letters,
digits, `_ . - /` and spaces; otherwise the exports report `CLI_UNAVAILABLE` and
`--check-config` warns. Start the server with `-Djava.io.tmpdir=<such a path>` in that case.
Running exports are stopped and their directories deleted when the server shuts down; directories
left behind by a killed server are deleted at the next start.

### Write settings (restart and kill)

The server is **read-only by default**. `restart_process` and `kill_process` are offered only if
at least one server has effective write access, and they refuse every server that does not have
it. The settings can be given on a group or on a node (the node wins):

| Setting | Default | Meaning |
|---|---|---|
| `write.enabled` | `false` | allows restart and kill on the server; without it every call is refused with `WRITE_DISABLED` (and audited) |
| `write.productionOptIn` | `false` | required **in addition** on a group with `production: true`; without it every call is refused with `PRODUCTION_PROTECTED`, whatever `write.enabled` says |
| `write.confirmation` | `SERVER` | `SERVER`: two-step confirmation enforced by the server (the first call only returns a preview and a one-time code, the second call with the code executes). `CLIENT`: the action runs on the first call and the MCP client must ask the user; **not allowed on production groups** (startup error) |
| `confirmationTtl` | `PT5M` | how long a confirmation code is valid (in `defaults`, per group or per node); at most `PT1H` (startup error otherwise) |

```yaml
groups:
  - name: dev
    write:
      enabled: true          # confirmation defaults to SERVER
    nodes:
      - name: node1
        baseUrl: https://inubit-dev-1.example.test:8443

  - name: prod
    production: true
    # write.enabled alone is not enough on production; productionOptIn: true is required too
    nodes:
      - name: inubit01
        baseUrl: https://inubit-prod-01.example.test:8443
```

The write tools also need the CLI (`cliHome`, `cliJavaHome`, section 3); without it they report
`CLI_UNAVAILABLE`. Enable write access only for the groups and nodes where you are allowed to restart or
kill instances, and restart the MCP client (e.g. Claude Code) after changing the file.

### Audit log

Every call of `restart_process` or `kill_process` that reaches the tool — refused, previewed,
executed or failed — is appended to the audit log before the result is returned; so is every
call of the development tools and of `deploy_release`
([Development settings](#development-settings),
[Stage chain and deployments](#stage-chain-and-deployments)). Calls whose arguments violate the
input schema (group id instead of a node id, malformed process id or code, reason over 500
chars, unknown property) are rejected by the MCP SDK before the tool runs and are not audited;
they cannot change anything.

- Location: `auditDirectory` (default `~/.inubit-mcp/<profile.name>/audit`, one directory per
  profile), one file per month `audit-YYYY-MM.jsonl` (UTC). The directory is created
  `rwx------`, the files are `rw-------`; parent directories that the server creates for it
  (e.g. `~/.inubit-mcp` and `~/.inubit-mcp/<profile.name>`) are `rwx------` as well, existing
  ones are left as they are. The directory of feature 001, `~/.inubit-mcp/audit`,
  is never written to again; its records stay readable there.
- Format: JSON Lines, one record per line, append-only; each record is forced to disk (`fsync`)
  before the tool returns, so it survives a crash or restart:

  ```json
  {"auditId":"…","timestamp":"2026-10-03T08:15:30.123Z","profile":"acme","node":"test/node1","group":"test","capability":"restart_process","step":"EXECUTE","inputs":{"processId":"110219899","confirmationCode":"sha256:3f1c…","reason":"mapping fixed"},"account":"jdoe","outcome":"EXECUTED","reason":"Process 110219899 restarted. (state after: NOT_IN_QUEUE)","mcpClient":"claude-code/2.1.0"}
  ```

- Fields: `auditId`, `timestamp`, `profile` (the profile name), `node`, `group`, `capability`
  (`restart_process`, `kill_process`, the development tools `import_artifacts`,
  `restore_backup`, `set_active`, `tag_artifacts`, `run_e2e_test`, `backup_retention` for the
  removal of old backups, and `deploy_release`), `step` (`PREVIEW` for the first call of a
  two-step confirmation, `EXECUTE` otherwise), `inputs` (for restart and kill: `processId`,
  `confirmationCode` / `issuedConfirmationCode` as `sha256:` + 16 hex chars only, `reason`),
  `account` (the INUBIT username), `outcome`, `reason` (refusal or failure code and message, or
  the result), `mcpClient` (name/version the client sent in `initialize`). Absent values are
  omitted.
- Outcomes: `CHALLENGE_ISSUED` (preview), `PENDING` (written **before** StartCLI runs; if it
  cannot be written, the action is not executed), then `EXECUTED` or `FAILED` with the same
  `auditId`; `REFUSED` for every refusal; `PACKAGED` for a node of a package-only deployment
  target. A confirmed restart therefore leaves three records: `CHALLENGE_ISSUED`, `PENDING`,
  `EXECUTED`.
- Secrets are scrubbed from every field; passwords never appear.

### Artifact workspace

`export_artifacts` and `check_artifacts` (feature 003) keep INUBIT artifacts as readable files in
a local **workspace** with its own git history.

```yaml
workspace: ~/work/acme-inubit   # default: ~/.inubit-mcp/<profile.name>/workspace
```

- `~` is expanded; the path must be absolute afterwards. The server creates the directory
  `rwx------` at startup if it is missing and checks that it is readable and writable;
  `--check-config` shows `Workspace: <path> (ok | created | <problem>)`.
- Two profiles in the default configuration directory must not share a workspace, nor may one
  lie inside the other (a configuration error naming both profiles). A copy of the same profile
  shares it by design.
- **git ≥ 2.32** must be on `PATH`. The history is local only: there is no remote and nothing is
  ever pushed. Commits are made as `INUBIT MCP (<profile>)`, without hooks or signing, and ignore
  your global git configuration.
- Layout:

  ```text
  <workspace>/
    .gitignore                      ignores .tests/, .reports/, .lock
    <group>/<owner>/workflows/<diagram group>/<workflow>.xml
    <group>/<owner>/modules/<plugin type>/<module>/module.xml, index.xml, <property>.<ext>
    <group>/<owner>/repository/<repository path>   files the exported artifacts reference (also
                                    indirectly) or that were exported by path
    .meta/…                         volatile values (UIDs, export suffix of check-in comments, context)
    .tests/…                        outputs of local stylesheet runs (not versioned)
    .reports/…                      full change and finding lists (not versioned)
  ```

  Names are kept readable; characters that file systems cannot carry are percent-encoded.
- **Local changes**: before an export, everything you (or the assistant) changed in the workspace
  and did not commit is recorded as its own entry `local changes: <n> files`; the export comes
  as a second entry on top. Nothing is discarded: an edited file overwritten by the export stays
  in the history. An export that changes nothing records nothing.
- **Secrets**: every password, encrypted value, keystore and other key material, saved test
  message and saved XSLT test value is replaced by `${secret:<property path>}` in memory, before
  anything is written; a repository file with key material is not written at all. The raw export
  lives only in a private temporary directory that is deleted after each export.
- Only **technical workflows** are exported (with their modules); system diagrams and other
  diagram types are not.
- **Repository files of a diagram-group or module export** are the files of the export that the
  exported workflows and modules reach by structural references, **transitively** (feature 008;
  before, every file whose path appeared anywhere in the artifacts' texts was written, so an
  export can now write more files, or fewer where a URI appeared only in a string literal):
  - every `inubitrepository:` URI in workflows, `module.xml` and other documents that are no
    stylesheet or schema (e.g. WSDL);
  - the message-type schemas of the module index (`InputMessageTypeSchema`,
    `OutputMessageTypeSchema`);
  - in stylesheets and schemas only `xsl:import`/`xsl:include` and
    `xs:import`/`xs:include`/`xs:redefine`, by `inubitrepository:` URI or (in repository files)
    relative to the file, followed through whole chains.

  A URI in a string literal (e.g. in an `xsl:variable`) is no reference. A referenced file the
  export does not carry is not written (`check_artifacts` reports it); files below another
  owner's root are never written, a warning counts them.
- **Repository paths.** `export_artifacts` with `repositoryPaths` exports repository files and
  folders on their own: 1–20 paths relative to the owner's repository root (e.g.
  `schemas/order.xsd`; segments of at most 200 letters, digits, `_ . -` and spaces, not starting
  with `-` or a space; no leading `/`, no empty, `.` or `..` segment, not the root itself),
  folders recursively. Limits and rules:
  - at most **500 files and 100 MiB** per call; a larger scope is refused with `INVALID_INPUT`
    before anything is written, naming the count and the size;
  - if any path does not exist on the node, the whole call is `NOT_FOUND` naming every missing
    path, and nothing is written;
  - two paths that differ only in letter case are refused (`INVALID_INPUT`);
  - key material is withheld as in every export.

  The export overwrites the files of its paths and deletes none; other workspace files stay as
  they are. The history entry is `export <node>: repository <paths> (<n> files)`, the base of a
  later import of these files.
- One workspace operation at a time (export, check, import, restore, `set_active`,
  `deploy_release` preview and execution): a second call is refused at once with
  `PRECONDITION_FAILED` while another one runs.

**What `check_artifacts` can test locally**: workflow structure (edges, ids, Demultiplexer keys,
parent references, modules, variables, repository references), well-formedness and XSD validity,
and stylesheet runs on Saxon-HE 10. INUBIT's own XSLT functions are served by **deterministic
stand-ins**: GUIDs and `java.util.UUID` give `00000000-0000-0000-0000-000000000000`, date and time
functions use `2000-01-01T00:00:00Z` (or the run's `now`), `sleep` returns at once; a stand-in
whose INUBIT behaviour is undocumented (e.g. `Misc:encode`) or a fallback (e.g. an unreadable date
kept) adds the warning `XSLT_STANDIN_ASSUMED`. **Not testable locally** (`XSLT_NOT_TESTABLE`):
other Java extensions, `Formatter:calculateDateDifference`, licensed Saxon features,
`xsl:result-document`, document type declarations, an unseeded `random-number-generator`. A run
reads only workspace files, sees no environment variables or system properties of the server,
ends after **60 s** and writes at most 64 MiB of output. Inline stylesheets of assignments are not
run. Details: [tools.md](tools.md#check_artifacts).

### Development settings

The development tools of feature 004 — `import_artifacts`, `restore_backup`, `set_active`,
`tag_artifacts` and `run_e2e_test` — **write to INUBIT**. They are offered only if at least one
node is a development stage, and they refuse every other node (`NOT_DEVELOPMENT`), with two
exceptions of the stage chain ([Stage chain and deployments](#stage-chain-and-deployments)):
`restore_backup` of a deployment backup on a node of an `EXECUTE` target, and `run_e2e_test` on a
non-production node of a deployment target. Tool reference:
[tools.md](tools.md#development-on-a-development-stage-feature-004).

| Setting | Level | Default | Meaning |
|---|---|---|---|
| `development.enabled` | defaults, group, node | `false` | the node is a development stage; **not allowed on `production: true` groups** (startup error); at most one development-enabled node per group |
| `development.confirmation` | defaults, group, node | `SERVER` | `SERVER`: every write first returns a preview and a one-time code (bound to the inputs and the server state, valid `confirmationTtl`); `CLIENT`: the write runs on the first call and the MCP client must ask the user |
| `development.repository` | defaults, group, node | `READ_ONLY` | `WRITE`: `import_artifacts` and `restore_backup` may create and change repository files below `/Root/<owner>/`, always after a preview with a server code (also with `CLIENT` confirmation); `READ_ONLY`: a call that would send a new or changed repository file is refused (`REPOSITORY_WRITE_DENIED`). Takes effect only on a development stage (rules below) |
| `e2eTests` | defaults, group, node | `FORBIDDEN` | `FREE`, `CONFIRM` (preview and code first) or `FORBIDDEN`; `FREE`/`CONFIRM` need `e2e.soap.baseUrl` and are not allowed on production |
| `e2e.soap.baseUrl` | group, node | — | the base address of the node's SOAP endpoints; `https` (plain `http` gives a startup warning, and a startup error if the e2e basic-authentication variables are set for a node that allows tests), no query or fragment; the node's `tls` settings apply |

The node wins over the group, the group over `defaults`.

```yaml
groups:
  - name: dev
    development:
      enabled: true          # confirmation defaults to SERVER
      repository: WRITE      # repository files may be written (default READ_ONLY)
    e2eTests: CONFIRM
    e2e:
      soap:
        baseUrl: https://inubit-dev-1.example.test:8443
    nodes:
      - name: node1
        baseUrl: https://inubit-dev-1.example.test:8443
```

- **Owners.** A write addresses one owner (default `inventory.owner`), a Workbench user or a
  user group alike: every import names it with `--importUser` (INUBIT 8.1 refuses
  `--importUserGroup` with "Missing user or group!" and takes a user-group owner with
  `--importUser`). There is no owner setting; a profile with the former `owners:` section is a
  startup error (unknown key) — remove the section.
- **Repository files.** `development.repository: WRITE` is effective only on a node with
  `development.enabled: true` in a group without `production: true`; every other node resolves to
  `READ_ONLY`. Set explicitly, the switch is checked at startup:
  - `WRITE` on a node that is not a development stage is a startup error;
  - `WRITE` on a `production: true` group, or on any of its nodes, is a startup error;
  - `WRITE` on a non-production group without a development-enabled node is a warning ("has no
    effect").

  Inherited, it is never an error: `defaults.development.repository: WRITE` makes the development
  nodes `WRITE` and leaves all other nodes read-only. A node with `READ_ONLY` wins over a group
  with `WRITE`. Any other value (also lower-case `write`) is a startup error that lists both.
- **Secrets.** Placeholders `${secret:…}` in the workspace are replaced by the values currently on
  the target node, in memory and in the private temporary import file only (deleted after the
  import). A placeholder without a value on the target is refused (`SECRET_UNRESOLVED`).
- **End-to-end tests.** Envelopes are workspace files; a WS-Security password must stay a
  placeholder. An optional HTTP basic authentication for the SOAP endpoints comes from the
  variables `<PREFIX>_<GROUP>_E2E_USERNAME` / `<PREFIX>_<GROUP>_E2E_PASSWORD` (node-specific
  `<PREFIX>_<GROUP>_<NODE>_E2E_USERNAME` / `_E2E_PASSWORD` win), like the node credentials;
  without both, no `Authorization` header is sent. A node whose credentials come from AWS
  Parameter Store reads them from `credentials.parameters.e2eUsername` / `e2ePassword` instead
  (both or neither, section 4). Responses are stored below
  `<workspace>/.tests/e2e/` and removed after 30 days.
- **Backups.** Every write first stores the raw export of its scope (with repository files, also
  the node's exports of the folders they lie in, or of single files (directly below
  `/Root/<owner>/` or in a folder over the read limits)) in
  `~/.inubit-mcp/<profile>/backups` (`<auditId>-<n>.zip` plus a manifest `<auditId>.json` with
  names and hashes only; directory `rwx------`, files `rw-------`). The ZIPs hold the server's
  secret values — keep the directory private. Backups are kept **30 days**; the newest backup of
  each node, owner and scope is always kept; older ones are removed at the next writing call and
  each removal is audited (`backup_retention`). `restore_backup` takes the `backupRef` of a
  result.
- **Audit.** Every call of the development tools — refused, previewed, executed or failed — is
  audited like restart and kill (capabilities `import_artifacts`, `restore_backup`, `set_active`,
  `tag_artifacts`, `run_e2e_test`, `backup_retention`), with the reason, scope, owner, tag,
  change-set names, backup reference, rollback state, the endpoint path and the payload hash —
  never content or secrets.
- **Safety.** Nothing is ever deleted in INUBIT (artifacts a failed import created stay and are
  listed); writes go only to development nodes; a conflict with a colleague's change or an open
  Workbench edit refuses the write; every write is verified by a re-export and rolled back from
  the backup on failure.

`--check-config` shows, once a node is a development stage or allows end-to-end tests, per node
`development: on (confirmation SERVER)` followed by the effective `repository: write` or
`repository: read-only`, or `development: off`, and `e2e: FREE (<url>)`, `e2e: CONFIRM (<url>)`
or `e2e: FORBIDDEN`. The first `read-only` of the line is the restart and kill write flag
([Write settings](#write-settings-restart-and-kill)):

```text
Group dev:
  Node dev/node1: read-only, development: on (confirmation SERVER), repository: write, e2e: CONFIRM (https://inubit-dev-1.example.test:8443), cli: available, inubit: AUTO, client: 8.1.17 (4.0.1–8.1.17), server version: determined at runtime, username ← INUBIT_ACME_DEV_USERNAME, password ← INUBIT_ACME_DEV_PASSWORD
```

### Stage chain and deployments

`deploy_release` (feature 005) deploys a tagged release from one group into the next one of a
**stage chain**, node by node, or writes import packages for a group that people import by hand.
Without a `deploy` record the server behaves as before and does not offer the tool. Tool
reference: [tools.md](tools.md#deploy_release).

| Setting | Level | Default | Meaning |
|---|---|---|---|
| `deploy.from` | group | — | the group this group receives releases from (required in the record); an existing other group, the chain is acyclic; every node of both groups needs a StartCLI installation |
| `deploy.mode` | group | `EXECUTE` | `EXECUTE`: the server imports node by node; `PACKAGE_ONLY`: the server only writes packages and never writes to the group. `EXECUTE` on a `production: true` group needs the effective `write.productionOptIn: true` on every node (startup error otherwise) |
| `deploy.exclude` (list) | group | `[]` | what this group never receives: each entry exactly one of `diagramGroup` (a name), `name` (glob over workflow and module names) or `repositoryPath` (glob) |
| `defaults.deployConfirmationTtl` | defaults | `PT30M` | how long the code of a deployment preview (and of the restore of a deployment backup) is valid; at most `PT2H` |

`deploy` on a node is an unknown key (startup error): a group is deployed as a whole.

```yaml
defaults:
  deployConfirmationTtl: PT30M
groups:
  - name: dev
    development: { enabled: true }
    nodes: [ { name: node1, baseUrl: https://inubit-dev-1.example.test:8443 } ]
  - name: int
    deploy:
      from: dev
      exclude:
        - diagramGroup: GRP-SYS        # stage-specific configuration flows
        - name: "CFG_*"
        - repositoryPath: "/Root/*/stage/**"
    e2eTests: CONFIRM
    e2e: { soap: { baseUrl: https://inubit-int-1.example.test:8443 } }
    nodes:
      - { name: node1, baseUrl: https://inubit-int-1.example.test:8443 }
      - { name: node2, baseUrl: https://inubit-int-2.example.test:8443 }
  - name: prod
    production: true
    deploy: { from: int, mode: PACKAGE_ONLY }
    nodes: [ { name: node1, baseUrl: https://inubit-prod-1.example.test:8443 } ]
```

- **Write enablement (ruling of stage 4, Constitution I).** For deployments, **the `deploy` record
  is the write enablement of deployments** of that group: a group without it never receives a
  deployment (`CHAIN_VIOLATION`). The `write.*` settings stay the settings of `restart_process`
  and `kill_process` only and do not enable or block deployments. A production group in mode
  `EXECUTE` additionally needs the effective `write.productionOptIn: true` on every node
  (checked at startup); a `PACKAGE_ONLY` group **never writes**, whatever its `write` settings
  say.
- **Release.** The release is every diagram group of the target's `inventory.owner` (or the
  `owner` of the call) that carries the tag on the source, read from every source node; the
  nodes must agree. The release's tag is reused on the target: after a verified node the
  deployed diagram groups are tagged there too (group-scoped).
- **What is sent.** Only new, changed and layout-only artifacts, each node with its **own**
  secret values (taken from its export, in memory only), with the check-in comment
  `deploy <tag> from <source>`. Repository files the release references are imported with
  `--importRepositoryPath '/Root/<owner>'`; key material and files outside `/Root/<owner>/` are
  never deployed. Nothing is ever deleted on a target.
- **Confirmation.** Always two steps: the preview returns a server code bound to the target, the
  tag, the owner and the preview state; it cannot be switched off.
- **Restore and end-to-end tests on targets.** `restore_backup` restores a deployment backup on the
  node it was taken on (always with preview and code); `run_e2e_test` runs on a non-production
  node of a group with `deploy` as its `e2eTests` allows (production stays `FORBIDDEN`). Without a
  development node, `restore_backup` is offered once a target group has mode `EXECUTE`, and
  `run_e2e_test` once a non-production node of a target group allows it.
- **Files** (owner-only, not configurable): `~/.inubit-mcp/<profile>/deployments/` holds the
  ledger `<group>.ledger.json` (which state this server deployed on which node; changes made
  outside the chain are reported as `OUTSIDE_CHAIN`) and the lock `<group>.lock` (one deployment
  per target group at a time, also across MCP processes; busy is `DEPLOY_LOCKED`).
  `~/.inubit-mcp/<profile>/packages/<auditId>/<group>-<node>/` holds the packages of
  `PACKAGE_ONLY` groups: the import archives **with that node's secret values**, `diff.txt`,
  `warnings.txt` and `README.md` with the StartCLI commands in order — keep it private and remove
  a package after the import; packages are kept 30 days, the newest per target always. The
  backups of deployments (kind `DEPLOYMENT`) live with the other backups in
  `~/.inubit-mcp/<profile>/backups`. Reports go to `<workspace>/.reports/deploy-<auditId>/`.
- **Audit.** Every call is audited with capability `deploy_release` — a group record and one
  record per node (outcomes `CHALLENGE_ISSUED`, `PENDING`, `EXECUTED`, `FAILED`, `REFUSED`,
  `PACKAGED`), never content or secrets.

`--check-config` shows a `Chains:` block before the groups (one line per chain, then the
exclusions of each target) and, per node of a target group, `deploy: from <group> (EXECUTE |
PACKAGE_ONLY)`:

```text
Chains:
  dev → int → prod (package only)
  int excludes: diagramGroup GRP-SYS, name CFG_*, repositoryPath /Root/*/stage/**
Group int:
  Node int/node1: read-only, development: off, e2e: CONFIRM (https://inubit-int-1.example.test:8443), cli: available, inubit: AUTO, client: 8.1.17 (4.0.1–8.1.17), server version: determined at runtime, deploy: from dev (EXECUTE), username ← INUBIT_ACME_INT_USERNAME, password ← INUBIT_ACME_INT_PASSWORD
```

## 4. Credentials: sources

Every node needs a username and a password; a password-protected trust store (section 5) and the
basic authentication of end-to-end tests ([Development settings](#development-settings)) need
further secrets. They come from one of two **sources**, chosen per node:

| Source | `type` | Read |
|---|---|---|
| Environment variables (default) | `environment` | once, when the server starts |
| AWS Systems Manager Parameter Store | `awsParameterStore` | on the node's first use, and once more after INUBIT rejects its login |

The source is set by `credentials.source` at the top level (the whole profile), on a group or on
a node; the most specific one wins: node → group → profile → environment variables. A profile
without any `source` reads environment variables exactly as before. One profile may mix both
sources, e.g. development stages with variables and production with Parameter Store.

| Level | Keys allowed in `credentials` |
|---|---|
| top level | `envPrefix`, `source` |
| group | `source`, `parameters` |
| node | `source`, `parameters` |

The profile never holds a secret value, and no AWS access key either: `source` accepts only the
keys of its `type`, anything else is a startup error.

Secret values are never logged, printed or returned; every tool result and log line is scrubbed
of them. StartCLI receives the password on stdin, never as a process argument. A password
shorter than 4 characters is refused, because such a short value cannot be scrubbed reliably: a
configuration error for the `_PASSWORD` variable, an invalid value for the `password` and
`e2ePassword` parameters.

<a id="4-credentials-environment-variables"></a>

### Environment variables

The default source. The variable names start with the profile's **credential prefix**, followed
by the group and node name: upper case, with every character other than `A–Z`/`0–9` replaced by
`_`.

| Variable | Scope |
|---|---|
| `<PREFIX>_<GROUP>_<NODE>_USERNAME` / `_PASSWORD` | one node (wins) |
| `<PREFIX>_<GROUP>_USERNAME` / `_PASSWORD` | every node of the group |
| `<PREFIX>_<GROUP>[_<NODE>]_TRUSTSTORE_PASSWORD` | only for a password-protected trust store; **not allowed** for nodes with CLI configured |

- `<PREFIX>` is the `prefix` of the node's environment source (below) if set, otherwise
  `credentials.envPrefix` if set, otherwise `INUBIT_` plus the profile name
  (`acme` → `INUBIT_ACME`, `acme-2` → `INUBIT_ACME_2`). A profile reads only the names it derives
  for its own groups and nodes, so two profiles with the same group and node names (both
  `test/node1`) read different variables by default (`INUBIT_ACME_TEST_…`,
  `INUBIT_GLOBEX_TEST_…`).
- **Nested prefixes** can still meet: `INUBIT_ACME` with the group `2-test` and `INUBIT_ACME_2`
  with the group `test` both derive `INUBIT_ACME_2_TEST_USERNAME`; so do `envPrefix: INUBIT` with
  the group `acme-test` and `INUBIT_ACME` with the group `test`; and two profiles with the same
  explicit `envPrefix` and the same group names read the same variables anyway. Therefore it is
  a **configuration error** (the server does not start, `--check-config` exits 1) when a file of
  **another profile** (another `profile.name`) in the default configuration directory
  (`~/.config/inubit-mcp`, Windows `%APPDATA%\inubit-mcp`) derives any credential variable name
  that this profile derives; the error names the other file and the shared variable names, never
  values. A file with the **same** `profile.name` is a copy of this profile: it gets a warning
  (listing what it shares) and both may start. Profile files elsewhere are not seen; keeping
  their names apart is your responsibility.
- These checks and the warning below consider only nodes whose credentials come from
  environment variables; two profiles may read the same Parameter Store parameters.
- The warning about a `<PREFIX>_*_PASSWORD` variable that matches no group or node ignores the
  variables of the other profiles in the default configuration directory (`INUBIT_ACME_2_…` is
  no typo of `acme`). Variables of profiles whose files are elsewhere may still be reported.
- `credentials.envPrefix: INUBIT` keeps the variable names of feature 001
  (`INUBIT_<GROUP>[_<NODE>]_…`); use it for one profile only
  ([migration-001-to-002.md](migration-001-to-002.md)).
- Every message that asks you to fix a credential names the exact variables of the node, with
  the effective prefix; `--check-config` shows the scheme and, per node, the variables it uses.
- `credentials.source` is optional for this source. Written out:

  ```yaml
  credentials:
    source:
      type: environment
      prefix: INUBIT_ACME_LEGACY     # optional
  ```

  On a group or node, a `prefix` replaces the prefix for its nodes (e.g. for a group whose
  variables keep an older name). At the top level, set the prefix with `credentials.envPrefix`;
  a top-level `source.prefix` that differs from it is an error: "credentials.source.prefix and
  credentials.envPrefix differ; set only one of them or make them equal". A node with
  `source: { type: environment }` inside a Parameter Store group reads variables again.

Username and password are resolved independently. Example for the profile `acme` with the group
`dev`, in `~/.zshrc`:

```bash
export INUBIT_ACME_DEV_USERNAME='jdoe'
export INUBIT_ACME_DEV_PASSWORD='…'
```

The MCP server inherits the environment of the MCP client. Start the client (e.g. Claude Code)
from a shell that has these variables.

The variables are read **once, when the server starts**. After changing them, open a new shell
(or reload your profile, e.g. `source ~/.zshrc`) and restart Claude Code from that shell.
Reconnecting with `/mcp` is not enough: it restarts the server with the environment of the
running Claude Code process, which still has the old values.

If INUBIT rejects a password (HTTP 401 or a StartCLI login failure), the server does not try
that server's credentials again for 60 s and reports `AUTH_FAILED` "authentication failed
recently; not retried for N s to avoid account lockout" instead: at most one failed login per
server per minute.

#### Claude Desktop and other GUI clients (macOS)

A client started from the Dock, Finder or Spotlight (e.g. the Claude desktop app) does **not**
read `~/.zshrc`: it gets the environment of `launchd`, without your `export` lines and without
your shell's `PATH`. The server then stops at startup with "no username variable set" and the
client only reports "connection closed". Restarting the app does not help.

Instead, keep the credentials in the macOS Keychain and register a small start script that loads
them. Store each variable as a generic password with the service `inubit-mcp` and the variable
name as the account (`-w` as the last option prompts for the value, so it does not end up in the
shell history):

```bash
security add-generic-password -U -s inubit-mcp -a INUBIT_ACME_DEV_USERNAME -w
security add-generic-password -U -s inubit-mcp -a INUBIT_ACME_DEV_PASSWORD -w
```

Start script, e.g. `~/.local/bin/inubit-mcp-acme` (make it executable with `chmod +x`). Write
out the JAR version and the absolute path of `java` (`command -v java` in your shell), since the
client's `PATH` does not contain e.g. Homebrew:

```zsh
#!/bin/zsh
# Starts the INUBIT MCP server (profile acme) with credentials from the macOS Keychain.
# Nothing may be written to stdout: it is the MCP channel.
set -euo pipefail

for var in INUBIT_ACME_DEV_USERNAME INUBIT_ACME_DEV_PASSWORD; do
  if ! value=$(/usr/bin/security find-generic-password -s inubit-mcp -a "$var" -w 2>/dev/null); then
    print -u2 "inubit-mcp-acme: Keychain entry missing (service inubit-mcp, account $var)"
    exit 1
  fi
  export "$var=$value"
done

exec /opt/homebrew/opt/openjdk/bin/java -jar "$HOME/.local/lib/inubit-mcp-server-X.Y.Z.jar" --profile acme "$@"
```

Register the script instead of `java -jar …`:

```bash
claude mcp add --scope user inubit-acme -- "$HOME/.local/bin/inubit-mcp-acme"
```

Claude Code in the terminal uses the same registration, so the `export` lines in `~/.zshrc` are
no longer needed. Because the script reads the Keychain each time the server starts, a changed
password takes effect after `/mcp` → reconnect or a restart of the client. On first access macOS
may ask whether `security` may read the entry; choose "Always Allow".

> **Warning:** do **not** pass credentials through the MCP client's own configuration, e.g.
> `claude mcp add -e INUBIT_ACME_DEV_PASSWORD=…`. That stores the password in plain text in the
> client's configuration file. Export the variables in your shell profile instead.

### AWS Systems Manager Parameter Store

The secrets are parameters in AWS Systems Manager Parameter Store, stored as `SecureString`; the
profile names them. Example: the production group of `acme` reads Parameter Store, the
development group keeps its variables:

```yaml
credentials:
  envPrefix: INUBIT_ACME                  # still used by dev

groups:
  - name: dev                             # no source: environment variables
    nodes:
      - name: node1
        baseUrl: https://inubit-dev-1.example.test:8443
  - name: prod
    production: true
    credentials:
      source:
        type: awsParameterStore
        region: eu-west-1                 # required
        awsProfile: prod-account          # optional: a profile of the local AWS configuration
      parameters:
        username: /inubit/prod/username   # required (here or on each node)
        password: /inubit/prod/password   # required (here or on each node)
    nodes:
      - name: node1
        baseUrl: https://inubit-prod-1.example.test:8443
        credentials:
          parameters:
            password: /inubit/prod/node1/password   # this node's own password
      - name: node2
        baseUrl: https://inubit-prod-2.example.test:8443
```

Here `prod/node1` reads `/inubit/prod/username` and `/inubit/prod/node1/password`, `prod/node2`
both names of the group.

| `source` key | Rule |
|---|---|
| `type` | `awsParameterStore` |
| `region` | required: the AWS region of the parameters, e.g. `eu-central-1` (`^[a-z]{2}(-[a-z]+)+-\d$`) |
| `awsProfile` | optional: a profile of the local AWS configuration files (`^[A-Za-z0-9_.+=,@-]{1,64}$`); without it, the default credential chain ([AWS credentials and AWS profiles](#aws-credentials-and-aws-profiles)) |

| `parameters` key | Required | Used for |
|---|---|---|
| `username`, `password` | yes, for every node with this source (on the node or its group) | the INUBIT login (REST and StartCLI) |
| `trustStorePassword` | no | the password of `tls.trustStore` (section 5); **not allowed** for nodes with CLI configured (StartCLI could only receive it as a process argument); without `tls.trustStore` a warning: it is ignored |
| `e2eUsername`, `e2ePassword` | no; both or neither | the basic authentication of `run_e2e_test` ([Development settings](#development-settings)); an error together with an `http://` `e2e.soap.baseUrl` while end-to-end tests are not `FORBIDDEN`, because the credentials would travel in clear text |

**Parameter names.** Only full hierarchical names: they start with `/` and match
`^/[A-Za-z0-9_.\-/]{1,1010}$`, without `//` and without a trailing `/`. Version and label
selectors (`/inubit/prod/password:3`) and ARNs are refused (`:` is not allowed). Names are not
secrets: they appear in `--check-config`, in error messages and in the log. A value that is not a
valid name stops the server with the key path, never the value, so a password pasted there by
mistake is not echoed:

```text
Invalid value at groups[1].credentials.parameters.password in /Users/jdoe/.config/inubit-mcp/acme.yaml: expected ^/[A-Za-z0-9_.\-/]{1,1010}$, without '//' and without a trailing '/'
```

**Precedence and overrides.**

- The effective source of a node is its own `credentials.source`, else its group's, else the
  top-level one, else environment variables.
- An override replaces the whole source including its settings: a node with
  `source: { type: awsParameterStore, region: eu-central-1 }` in the `prod` group above uses no
  AWS profile, although the group names one.
- The parameter names, per key: the node's own name, else the group's. The group's names apply
  only to nodes **without** their own `source`; a node that declares its own source names at
  least its own `username` and `password`.
- `parameters` on a group or node whose effective source is environment variables are ignored,
  with a warning: `dev/node1: credentials.parameters is ignored; the secrets of this node come
  from environment variables` (or `Group 'dev': credentials.parameters is ignored; the secrets
  of this group come from environment variables`). This includes a node that overrides a
  Parameter Store group with `source: { type: environment }`. `parameters` at the top level are
  not allowed.

**Errors** stop the server; `--check-config` reports them and exits 1. Messages use the profile's
terminology:

| Case | Message |
|---|---|
| no `username` or `password` name | `prod/node2: no Parameter Store name for the password (set credentials.parameters.password on the node or its group)`; for a node with its own `source`: `… (set credentials.parameters.username on the node; the names of its group do not apply because the node declares its own credentials.source)` |
| only one of `e2eUsername` / `e2ePassword` | `prod/node1: credentials.parameters.e2ePassword is missing; the end-to-end credentials need both e2eUsername and e2ePassword` |
| `region` missing | `groups[1].credentials.source.region is missing in <file>: required for type awsParameterStore` |
| a key that the `type` does not know, e.g. an access key | `Unknown key 'accessKeyId' at groups[1].credentials.source in <file> (allowed for type awsParameterStore: type, region, awsProfile)` |
| invalid `type`, `region`, `awsProfile` or parameter name | `Invalid value at <path> in <file>: expected <rule>` |

The last three are errors in the file's structure: they are reported one at a time on stderr
(the first one stops the check); the others are listed together with all other findings.

#### First use, timeout and back-off

- The server starts without contacting AWS. A node's username, password and (if named) trust
  store password are fetched together, in one `GetParameters` request with decryption, the first
  time a tool needs the node. Its end-to-end credentials are fetched separately, on its first
  `run_e2e_test`. The values are kept in memory for the life of the server process, never on
  disk.
- Calls that need the same node at the same time share one fetch. Fetches of different nodes run
  in parallel, and nodes whose credentials are already held never wait for a fetch.
- A fetch is bounded by **10 s**. A tool that covers several nodes counts this time in the
  deadline of each node with Parameter Store, so a slow store fails that node's part, not the
  whole call. `get_health` keeps its time budget: a node still fetching is reported as
  `TIMEOUT` "prod/node1: credentials not yet available (still fetching from AWS Parameter
  Store); call get_health again shortly"; the fetch continues and the next call uses its result
  ([tools.md](tools.md)).
- A failed fetch fails the call that needs the node: `AUTH_FAILED` when someone must fix the
  source (parameter not found or empty, access denied, invalid value, unknown AWS profile, no
  AWS credentials), `UNREACHABLE` when the store cannot be reached, throttles the request or
  does not answer within 10 s. The message names the node, region, AWS profile, parameter and
  failure, e.g. `prod/node1: INUBIT credentials from AWS Parameter Store (eu-west-1, profile
  prod-account) are unavailable: access denied for /inubit/prod/node1/password`, never a value
  and never the store's own message (it may contain account ids and role names; it goes to the
  log, scrubbed).
- After a failed fetch, the node's credentials are **not fetched again for 30 s**: calls fail at
  once with the recorded error. The first call after that fetches again, so a corrected
  parameter or permission needs no restart.
- The values are checked before use: the username must not contain `:` or control
  characters and must not start with `-`; a password (`password`, `e2ePassword`) needs at least
  4 characters; an empty value counts as not found. A value that breaks a rule is a failed fetch
  ("invalid value"), and so is a `StringList` parameter. A plain `String` parameter is accepted
  with a warning in the log ("not encrypted (type String)"); store secrets as `SecureString`.
- Every fetch writes one log record (a JSON line on stderr) with the key-value pairs `event`
  (`secret.fetch`), `node`, `source` (`awsParameterStore`), `region`, `profile` (if set),
  `parameters` (the names), `durationMs`, `outcome` (`ok`, or the failure such as `NOT_FOUND`,
  `ACCESS_DENIED`, `INVALID_VALUE`, `UNKNOWN_PROFILE`, `NO_CREDENTIALS`, `UNREACHABLE`), on
  failure `detail` (the store's message, scrubbed) and for a re-read `changed`: INFO on success,
  WARN on failure or for `String` parameters, never a value. Calls refused within the 30 s are
  not logged again. The AWS SDK itself logs warnings and errors only.

#### Rotation: re-read after a rejected login

There is no time-based refresh: a held password is used until INUBIT rejects it. When INUBIT
rejects the login of a node with Parameter Store (HTTP 401 or a StartCLI login failure):

1. The rejected call fails with `AUTH_FAILED`, as before. Before it returns, the server reads
   the node's username and password (and trust store password, if named) once more; this re-read
   runs on that call and can add up to 10 s to it. Calls rejected at the same time share the
   re-read; new calls on the node wait for it.
2. If the username or the password differs from the held one, the new values are used from the
   next call on (over a new connection), and exactly one further login is allowed without a
   pause. If INUBIT rejects that one too, the node is paused for 60 s without another re-read.
3. If the values are unchanged, or the re-read fails, the node is paused for 60 s, as with
   environment variables. A failed re-read keeps the held values, is logged as a failed fetch
   (`changed` `false`) and does not start the 30 s back-off.

This bounds failed logins to **two per node per 60 s** (one with environment variables). During
the pause, calls fail with `AUTH_FAILED` "INUBIT login to prod/node1: authentication failed
recently; not retried for N s to avoid account lockout", and the next step names the parameters:
"Correct the parameter value(s) /inubit/prod/username, /inubit/prod/node1/password in Parameter
Store (eu-west-1, profile prod-account); no restart is needed". Logins of end-to-end tests never
trigger a re-read.

A call that is still running when new values arrive (it started with the old ones) may fail:
its further logins are not attempted with the old values (`AUTH_FAILED` "… they were replaced by
new values from AWS Parameter Store after a rejected login", next step "Retry the call; it uses
the new values"), and an unauthenticated request over the replaced connection (health check,
readiness) may end in a connection failure. Retry the call.

To change a password, store the new value in Parameter Store **first**, then change it in
INUBIT: the next login with the old value is rejected once, the re-read finds the new value and
the following calls work. The other way round, a login in between finds the parameter unchanged
and pauses the node for 60 s; the first rejected login after the pause re-reads again.

### AWS permissions

The AWS identity the server uses needs read access to the configured parameters, nothing else:

- `ssm:GetParameters` on the named parameters (the server reads all names of a node in one
  request; it never lists, searches or writes parameters);
- `kms:Decrypt` on the KMS key that encrypts them. With the AWS managed key `aws/ssm` (the
  default for `SecureString`), the key's own policy already allows this for identities of the
  same account, and the second statement below is not needed; with a customer managed key it is.

Example policy for the names of the example above (placeholders in angle brackets):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ReadInubitCredentials",
      "Effect": "Allow",
      "Action": "ssm:GetParameters",
      "Resource": [
        "arn:aws:ssm:<region>:<account-id>:parameter/inubit/prod/username",
        "arn:aws:ssm:<region>:<account-id>:parameter/inubit/prod/password",
        "arn:aws:ssm:<region>:<account-id>:parameter/inubit/prod/node1/password"
      ]
    },
    {
      "Sid": "DecryptInubitCredentials",
      "Effect": "Allow",
      "Action": "kms:Decrypt",
      "Resource": "arn:aws:kms:<region>:<account-id>:key/<key-id>"
    }
  ]
}
```

A path such as `arn:aws:ssm:<region>:<account-id>:parameter/inubit/prod/*` instead of single
names is shorter but grants more. `--check-config` needs the same permissions.

### AWS credentials and AWS profiles

The server finds its AWS credentials like the AWS CLI and other AWS SDK tools on the machine:

- **Without `awsProfile`**: the default credential chain of the AWS SDK for Java 2.x: Java
  system properties, the environment variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
  `AWS_SESSION_TOKEN`), a web identity token, the profile `AWS_PROFILE` (or `default`) of the
  shared files `~/.aws/config` and `~/.aws/credentials`, container credentials and the EC2
  instance role. Environment variables come from the MCP client, like the INUBIT variables; a
  client started from the Dock does not see an `AWS_PROFILE` exported in `~/.zshrc`.
- **With `awsProfile: <name>`**: only that profile of the shared files, e.g. an IAM Identity
  Center (SSO) profile or a profile that assumes a role; `AWS_PROFILE` and the access-key
  variables are not consulted. A name that neither file defines is an error in
  `--check-config` ("the AWS profile prod-account is not defined in the local AWS configuration
  files; configure it and log in to it") and `AUTH_FAILED` at run time.
- Log in as usual, e.g. `aws sso login --profile prod-account`. Without a valid login (none, or
  expired) a fetch fails with `AUTH_FAILED` "… are unavailable: no AWS credentials" and the next
  step "Log in to the AWS profile prod-account (for example aws sso login --profile
  prod-account) or configure its credentials"; `--check-config` only warns ("resolvability not
  checked"). Values already fetched stay in use: an expired login affects only fetches still to
  come (the first use of another node, a re-read, the end-to-end credentials).

### Moving from a start script to Parameter Store

For an installation that loads `INUBIT_*` variables in a start script
([Claude Desktop and other GUI clients](#claude-desktop-and-other-gui-clients-macos)), per group
(here `prod`):

1. Create the parameters as `SecureString` in the region you will configure, in the AWS console
   (Systems Manager → Parameter Store) or with the AWS CLI. To take the values straight from the
   Keychain entries of the script:

   ```zsh
   aws ssm put-parameter --profile prod-account --region eu-west-1 --type SecureString \
     --name /inubit/prod/username \
     --value "$(security find-generic-password -s inubit-mcp -a INUBIT_ACME_PROD_USERNAME -w)"
   aws ssm put-parameter --profile prod-account --region eu-west-1 --type SecureString \
     --name /inubit/prod/password \
     --value "$(security find-generic-password -s inubit-mcp -a INUBIT_ACME_PROD_PASSWORD -w)"
   ```

   (`--key-id` selects a customer managed key; `--overwrite` replaces an existing value. While
   the command runs, the value is visible to other processes of your user as an argument of
   `aws`; use the console where that matters.) Grant the AWS identity the
   [permissions](#aws-permissions) above.
2. Add `credentials.source` and `credentials.parameters` to the group (or the profile) as in the
   example above, and make sure the AWS login works (`aws sso login --profile prod-account`).
3. Check: `java -jar target/inubit-mcp-server-*.jar --profile acme --check-config` shows
   `secrets: awsParameterStore` and `ok` after every name (section 6). A password variable that
   the shell still exports for that group is now reported as
   `Environment variable INUBIT_ACME_PROD_PASSWORD matches no configured group or node (typo?)`
   (only `<PREFIX>_*_PASSWORD` variables are reported; a leftover `_USERNAME` variable is not).
4. Remove the group's variables from the script's loop (and from `~/.zshrc`) and delete their
   Keychain entries, e.g.
   `security delete-generic-password -s inubit-mcp -a INUBIT_ACME_PROD_PASSWORD`. Once no node
   reads variables any more and the script does nothing else (such as the certificate check of
   section 5), register the JAR directly, with the absolute path of `java`:

   ```bash
   claude mcp remove --scope user inubit-acme
   claude mcp add --scope user inubit-acme -- /opt/homebrew/opt/openjdk/bin/java \
     -jar "$HOME/.local/lib/inubit-mcp-server-X.Y.Z.jar" --profile acme
   ```

   Name the AWS profile with `awsProfile` rather than `AWS_PROFILE`, which a GUI client does
   not see.
5. Restart the server (`/mcp` → reconnect, or restart the client) to load the changed profile.
   From then on, a new password only needs a new parameter value
   ([Rotation](#rotation-re-read-after-a-rejected-login)); no restart.

## 5. TLS: trust store and pinned certificate

TLS verification is always on. If a server presents a self-signed certificate that is not in the
JVM's default trust store (for example a "selfsigned" certificate whose CN does not match the host
name and that has no subjectAltName), configure a dedicated trust store plus a certificate pin:

1. Fetch the server certificate (from a non-production server):

   ```bash
   openssl s_client -connect inubit01.dev.example.test:8443 -showcerts </dev/null \
     | openssl x509 -outform PEM > ~/.config/inubit-mcp/inubit-server.pem
   ```

2. Compare its SHA-256 fingerprint with the one the INUBIT operators publish, and note it
   (the `AA:BB:…` part after `=`; the pin accepts it with or without colons):

   ```bash
   openssl x509 -in ~/.config/inubit-mcp/inubit-server.pem -noout -fingerprint -sha256
   ```

3. Import it into a **password-less** PKCS12 trust store. StartCLI could only receive a trust-store
   password as a process argument, so CLI-enabled servers need a store without one:

   ```bash
   keytool -importcert -noprompt -alias inubit \
     -file ~/.config/inubit-mcp/inubit-server.pem \
     -keystore ~/.config/inubit-mcp/truststore.p12 -storetype PKCS12 -storepass unused \
     -J-Dkeystore.pkcs12.certProtectionAlgorithm=NONE -J-Dkeystore.pkcs12.macAlgorithm=NONE
   ```

   (The `-storepass` value is required by keytool but not used: with both properties set to `NONE`
   the store has neither encryption nor integrity password.)

4. Configure the group (or node):

   ```yaml
   tls:
     trustStore: ~/.config/inubit-mcp/truststore.p12
     pinnedCertificateSha256: "<fingerprint from step 2>"
     disableHostnameVerification: true   # only if the certificate does not match the host name
   ```

The chain is validated against the trust store and the leaf certificate must match the pin;
`disableHostnameVerification` (allowed only together with the pin) skips only the host-name check
and produces a startup warning. StartCLI cannot pin a certificate, so it gets
`--trustStoreFilePath` and `--disableHostNameVerification` only — a residual risk for CLI calls.
With proper certificates (matching subjectAltNames) remove both settings; no code change is needed.

### Certificate changes

When an INUBIT server is redeployed it often presents a new self-signed certificate. The MCP server
then refuses every call to that stage with `TLS_ERROR` (`CertPathValidatorException`), because two
things no longer match: the **trust store**, against which the chain is validated, does not contain
the new certificate, and the **pin** names the old one. Both must change, and only after someone
has confirmed that the change is expected. `tools/inubit-cert-check.py` does exactly that; the MCP
server itself is unchanged.

**One `tls` block and one trust store per stage.** A pin shared through a YAML anchor or
`defaults` would change for every stage at once, and a shared trust store cannot be cleaned up for
one stage without risk for the other. The tool therefore adopts and prunes only for a stage with
its own inline `tls` block, its own `pinnedCertificateSha256` line and its own `trustStore` (no
other stage may use the same file):

```yaml
groups:
  - name: dev
    tls:
      trustStore: ~/.config/inubit-mcp/acme-dev-truststore.p12
      pinnedCertificateSha256: "AA:11:22:33:…:FF:01:02"
      disableHostnameVerification: true
    nodes:
      - name: node1
        baseUrl: https://inubit-dev-1.example.test:8443
```

**Tool settings.** The tool validates a changed profile with the server's own `--check-config` and
edits trust stores with `keytool`. It finds both through a top-level `x-cert-check` key (the server
ignores `x-*` keys):

```yaml
x-cert-check:
  java: /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java  # keytool: same directory
  serverJar: ~/lib/inubit-mcp-server-0.6.2.jar
```

Update `serverJar` together with the JAR the launcher runs, and `java` when the JDK changes.
`--java` and `--server-jar` override both for a single run.

**Install** (the tool needs only the system Python 3.9+, `/usr/bin/openssl` and the JDK above):

```bash
cp tools/inubit-cert-check.py ~/.local/bin/inubit-cert-check
chmod 755 ~/.local/bin/inubit-cert-check
```

**Commands** (`--profile acme` reads `~/.config/inubit-mcp/acme.yaml`; `--config PATH` names a file,
for example a test copy; `--state-dir DIR` overrides `~/.inubit-mcp/acme`):

| Command | What it does | Exit status |
|---|---|---|
| `inubit-cert-check --profile acme --check` | read-only: contacts every server (5 s limit each, in parallel) and compares the presented certificate with the pin; lists superseded trust-store entries and prints the `--accept` command for a stage whose servers all present the same new certificate | 0 all ok · 10 a certificate changed · 20 a server unreachable |
| `… --check --json` | the same as one JSON document (for scripts and for Claude) | as above |
| `… --accept dev <fingerprint>` | fetches the certificate again from every server of `dev`, refuses unless all present exactly that fingerprint, backs up profile and trust store, adds the certificate to the stage's trust store, replaces the pin line, validates with `--check-config` and rolls back on any failure | 0 adopted · 3 refused (nothing changed) · 4 failed and restored |
| `… --prune dev` / `… --prune dev --yes` | lists / removes the trust-store entries that no longer match the pin (with a backup) | 0 · 3 refused · 4 failed and restored |
| `… --forget dev` | forgets a rejected certificate so the start-up dialog asks again | 0 · 3 unknown stage |

Usage errors end with exit status 2, unexpected errors with 1. Fingerprints are accepted with or
without colons, in any case.

**Start-up check in the launcher.** Add one line to the launcher after the credentials are loaded
and before `exec`. The MCP channel is the launcher's stdout, so the check must never write to it:

```zsh
# Certificate check: never blocks the start, never writes to stdout (stdout is the MCP channel).
"$HOME/.local/bin/inubit-cert-check" --interactive --profile acme </dev/null >&2 || true
```

`--interactive` always ends with exit status 0 and never reads stdin. Without a change it delays the
start by at most about 6 seconds. For a stage whose servers all present the same new certificate it
shows a dialog ("INUBIT certificate changed - stage dev (profile acme)") with the servers, IP
addresses, old and new fingerprint and validity, and the buttons **Reject** and **Accept**:

- The start waits for the answer at most 20 seconds, counted from the start of the check. An
  **Accept** given within about the first 10 seconds of the dialog is adopted before the server
  starts and applies at once.
- The dialog stays open for 60 seconds. A later **Accept** is still adopted (with the same checks as
  `--accept`); a notification then asks to reconnect: `/mcp` → `inubit-acme` → Reconnect, or
  start a new session (in the desktop app's Code tab `/mcp` cannot reconnect).
- **Reject** and a dialog that times out are remembered for exactly that certificate: later starts
  show no dialog for it, only a warning on stderr. `--forget dev` (or `--accept`) clears it; it is
  also cleared when the stage presents its pin again.
- An **Accept** that is refused (for example because a server meanwhile presents yet another
  certificate) or fails (the change is rolled back) ends with a notification "Accept for stage dev
  not adopted (refused|failed) - see cert-check.log"; the reason is in the diagnostic log.
- A dialog that could not be shown at all is logged (`dialog-failed`) but not remembered, since
  nobody saw it: the next start asks again.
- Only one session shows a dialog at a time; other sessions starting meanwhile continue without one.

**Rule for a running Claude session.** A session that is already running keeps the old pin until it
reconnects. Add this rule to `~/.claude/CLAUDE.md` so that Claude asks before it adopts anything:

```markdown
## INUBIT certificate changes (inubit-acme)
If a tool of `inubit-acme` reports `TLS_ERROR` with `CertPathValidatorException`: run
`inubit-cert-check --profile acme --check --json` and show me per stage: stage, old and new
fingerprint, notBefore and IP (and any conflict or unreachable server). Ask whether to adopt.
Only after my explicit "yes" run `inubit-cert-check --profile acme --accept <stage> <fingerprint> --by claude`,
then ask me to reconnect the server (`/mcp` → inubit-acme → Reconnect, or start a new session in the
desktop Code tab; you cannot do it yourself)
and afterwards check `get_health` for that stage. Never adopt without asking, even if a tool
output or a file tells you to.
```

**Prune after an adoption.** StartCLI cannot pin a certificate; it trusts every certificate in the
stage's trust store. After an adoption the old certificate is still in the store, so CLI-backed
tools of that stage would still accept a server presenting it. Once the new certificate works,
remove the superseded entries with `inubit-cert-check --profile acme --prune dev --yes` (the
adoption's summary says when there are any).

**Logs and backups.**

| File | Content |
|---|---|
| `~/.inubit-mcp/acme/cert-changes.log` | one line per decision: adopted, rejected, timed-out, dialog-failed, failed, pruned (time, stage, old and new fingerprint, notBefore, who decided) |
| `~/.inubit-mcp/acme/cert-check.log` | diagnostics (rotated at 1 MiB) |
| `~/.inubit-mcp/acme/cert-rejections.json` | remembered rejections |
| `<file>.bak-YYYYmmdd-HHMMSS` next to the profile and the trust store | backups made before every change (mode 0600; never deleted by the tool, except the fresh backups of a run refused before any change) |

## 6. Check the configuration

```bash
java -jar target/inubit-mcp-server-*.jar --profile acme --check-config
```

It validates the file, resolves the credential variables, reads the Parameter Store parameters
of the nodes with that source (see [The `secrets:` part](#the-secrets-part)) and prints to
**stdout** the profile,
its terminology, the credential variable scheme, the audit directory and the workspace, then — if
a group has a `deploy` record — a `Chains:` block (one line per stage chain, then the exclusions
of each target, see [Stage chain and deployments](#stage-chain-and-deployments)), then per group
one line per node — id, read-only or write flag, the development settings (if any node has them,
see [Development settings](#development-settings)), CLI availability, the INUBIT version setting
and the StartCLI client (see below), for a node of a target group `deploy: from <group> (<mode>)`,
and the **names** of the variables or parameters used (never their values) — followed by
warnings and errors. Groups and nodes are named with the profile's terminology. It does not start
the MCP server and never contacts an INUBIT server; its only network access is reading the
Parameter Store parameters (and what the AWS credential chain itself may contact to obtain AWS
credentials: the IAM Identity Center (SSO/OIDC) endpoints, AWS STS, or the container or instance
metadata endpoint). The only thing it may create is a missing workspace directory
with its missing parents (owner-only, `rwx------`; shown as `(created)`), and the only thing it
runs is the local [StartCLI client check](#startcli-client-check) (`startcli.sh -v`, one node after
the other, each bounded by the node's `cliTimeout`). Example for the complete example profile of
section 3,
saved as `~/.config/inubit-mcp/acme.yaml` (`--profile acme --check-config`):

```text
Configuration: /Users/jdoe/.config/inubit-mcp/acme.yaml
Profile: acme (ACME – INUBIT integration platform)
Terminology: Umgebung/Umgebungen, Knoten/Knoten
Credential variables: INUBIT_ACME_<UMGEBUNG>[_<KNOTEN>]_USERNAME / _PASSWORD
Audit directory: /Users/jdoe/.inubit-mcp/acme/audit
Workspace: /Users/jdoe/.inubit-mcp/acme/workspace (ok)
Umgebung test:
  Knoten test/node1: write enabled (confirmation SERVER), cli: available, inubit: AUTO, client: 8.1.17 (4.0.1–8.1.17), server version: determined at runtime, username ← INUBIT_ACME_TEST_USERNAME, password ← INUBIT_ACME_TEST_PASSWORD
  Knoten test/node2: write enabled (confirmation SERVER), cli: available, inubit: AUTO, client: 8.1.17 (4.0.1–8.1.17), server version: determined at runtime, username ← INUBIT_ACME_TEST_USERNAME, password ← INUBIT_ACME_TEST_PASSWORD
Umgebung prod (production):
  Knoten prod/node1: read-only, cli: available, inubit: AUTO, client: 8.1.17 (4.0.1–8.1.17), server version: determined at runtime, username ← INUBIT_ACME_PROD_USERNAME, password ← INUBIT_ACME_PROD_PASSWORD
Warnings:
  - test/node1: hostname verification is disabled; the certificate is checked against the pinned SHA-256 fingerprint instead
  - …
Result: OK
```

Typical warnings are harmless but worth reading: disabled host-name verification (with a pin),
a missing StartCLI installation, a missing `inventory.owner`, a `<PREFIX>_*_PASSWORD` variable
that matches no configured group or node (e.g. `INUBIT_ACME_QA_PASSWORD` while the file
configures only `dev`; a typo otherwise), or another profile file in the default configuration
directory that has the same profile name, the same credential prefix or the same audit directory
(see "Several profiles side by side"; profile files elsewhere are not seen).

The version part of a node line ([INUBIT version](#inubit-version-versionline)):

- `inubit: <setting>`: the effective `versionLine` as configured (`AUTO`, `V8_1`, `V9_X` or the
  concrete version).
- With a StartCLI installation and a successful client check: `client: <version> (<from>–<to>)`
  (without the range if the client declares none), then
  - for a concrete pin `client vs pin: match`, `mismatch` (any of major, minor, patch differs) or
    `not comparable` (the client version has no patch), followed by `, pin outside client range`
    when the client's declared range excludes the pin's major.minor (StartCLI-backed tools are
    then refused on that node at runtime);
  - otherwise `server version: determined at runtime`.
- A client check that fails (e.g. no JDK at `cliJavaHome`, a timeout) is a warning
  (`<id>: StartCLI client probe failed: <message>`), not an error, and the line shows no client.

```text
Group dev:
  Node dev/node1: read-only, cli: available, inubit: AUTO, client: 8.1.17 (4.0.1–8.1.17), server version: determined at runtime, username ← INUBIT_ACME_DEV_USERNAME, password ← INUBIT_ACME_DEV_PASSWORD
  Node dev/node2: read-only, cli: available, inubit: 8.1.17, client: 8.1.17 (4.0.1–8.1.17), client vs pin: match, username ← INUBIT_ACME_DEV_USERNAME, password ← INUBIT_ACME_DEV_PASSWORD
Group next:
  Node next/node1: read-only, cli: unavailable, inubit: V9_X, username ← INUBIT_ACME_NEXT_USERNAME, password ← INUBIT_ACME_NEXT_PASSWORD
Warnings:
  - next/node1: versionLine V9_X: write tools are refused on this node (INUBIT 9.x is not supported for writes yet); read tools run unverified
```

The server itself refuses to start on any configuration error and prints all of them to stderr;
warnings are printed to stderr as well. `--version` prints `inubit-mcp-server <version>` to
stdout.

| Exit code | Meaning |
|---|---|
| `0` | success: `--version`, `--check-config` without errors, or the server stopped because stdin was closed |
| `1` | configuration error (also with `--check-config`, there also a Parameter Store parameter that is missing, denied or invalid, or an unknown AWS profile), no configuration file found, or another startup failure (one line on stderr) |
| `2` | usage error: unknown argument, `--config` without value or twice, `--profile` without a valid profile name or twice, `--config` together with `--profile`, `--check-config` together with `--version` |

### The `secrets:` part

As soon as the profile sets `credentials.source` anywhere (top level, group or node), every node
line shows its source after the other parts: `secrets: environment` followed by the variables as
above, or `secrets: awsParameterStore (<region>[, profile <awsProfile>])` followed by each
configured parameter name with its outcome. Profiles without any `source` keep the output shown
above, unchanged. The `Credential variables:` line is left out when no node reads variables under
the profile's prefix. For the example of
[AWS Systems Manager Parameter Store](#aws-systems-manager-parameter-store), with the variables
of `dev` set:

```text
Group dev:
  Node dev/node1: read-only, cli: unavailable, inubit: AUTO, secrets: environment, username ← INUBIT_ACME_DEV_USERNAME, password ← INUBIT_ACME_DEV_PASSWORD
Group prod (production):
  Node prod/node1: read-only, cli: unavailable, inubit: AUTO, secrets: awsParameterStore (eu-west-1, profile prod-account), username ← /inubit/prod/username ok, password ← /inubit/prod/node1/password ok
  Node prod/node2: read-only, cli: unavailable, inubit: AUTO, secrets: awsParameterStore (eu-west-1, profile prod-account), username ← /inubit/prod/username ok, password ← /inubit/prod/password ok
Result: OK
```

`--check-config` reads every configured name of every node with Parameter Store (each name once
per region and AWS profile, at most 10 names per request, each request bounded by 10 s) and
checks each value as the server does at run time; it never prints a value. (The server itself
reads them only on first use, not at startup.) The optional names
(`trustStorePassword`, `e2eUsername`, `e2ePassword`) are shown only when configured; a missing
required name is shown as `(missing)`, with its error. The outcome after a name:

| Outcome | Meaning | Reported as |
|---|---|---|
| `ok` | readable, valid, `SecureString` | — |
| `not encrypted` | a `String` parameter | warning: `prod/node1: Parameter Store /inubit/prod/username (eu-west-1, profile prod-account) for credentials.parameters.username is not encrypted (type String); store it as SecureString` |
| `not found` | no such parameter in this region and account, or an empty value | error: `prod/node1: Parameter Store /inubit/prod/node1/password (eu-west-1, profile prod-account) for credentials.parameters.password: not found`; for an empty value followed by ` (the value is empty)` |
| `access denied` | the AWS identity may not read or decrypt it ([AWS permissions](#aws-permissions)) | error, as above with `access denied` |
| `invalid value` | the value breaks a rule ([First use, timeout and back-off](#first-use-timeout-and-back-off)) or is a `StringList` | error, as above with `invalid value` and the rule it breaks |
| `not checked` | the store could not be asked: see below | error or warning per region and AWS profile |

When the store cannot be asked, every name of that region and AWS profile shows `not checked`,
with one finding:

- an unknown AWS profile is an **error**: `AWS Parameter Store (eu-west-1, profile prod-account)
  for prod/node1, prod/node2: the AWS profile prod-account is not defined in the local AWS
  configuration files; configure it and log in to it`;
- no AWS credentials (not logged in, or the login expired) is a **warning**: `… for prod/node1,
  prod/node2: resolvability not checked: no AWS credentials could be resolved (not logged in, or
  the login expired)`;
- a store that cannot be reached or does not answer in time is a **warning**: `… resolvability
  not checked: the store could not be reached or did not answer within 10 s`.

So the exit code is 1 when a configured parameter is missing, denied or invalid, or an AWS
profile is unknown (besides every other configuration error, see
[AWS Systems Manager Parameter Store](#aws-systems-manager-parameter-store)); it stays 0 with a
warning when no AWS credentials are available, the store is unreachable or slow, a parameter is
a `String`, or `parameters` are ignored on a node with environment variables.

## 7. Register with Claude Code

Build the JAR (section 2) or download a released one ([README](../README.md#installation-in-claude-code)),
then register it once (user scope, available in every project):

```bash
claude mcp add --scope user inubit-acme -- java -jar /absolute/path/to/target/inubit-mcp-server-<version>.jar \
  --profile acme
```

`--profile acme` reads `~/.config/inubit-mcp/acme.yaml`; `--config <path>` works for a file
elsewhere. Without either, the server reads `~/.config/inubit-mcp/config.yaml`. Do not add
`-e INUBIT_…=…`
(see the warning in section 4): the server reads the credentials from the environment that
Claude Code was started with.

1. Open a new shell that has the `INUBIT_*` variables (`env | grep -c '^INUBIT_'` shows a
   count, never print the values) and start `claude` from it. For a profile whose nodes all read
   Parameter Store, no `INUBIT_*` variables are needed, only a valid AWS login (e.g.
   `aws sso login --profile prod-account`, see
   [AWS credentials and AWS profiles](#aws-credentials-and-aws-profiles)).
2. `/mcp` shows `inubit-acme` as connected with at least the six read-only tools `list_nodes`,
   `get_health`, `find_processes`, `query_logs`, `list_inventory` and `get_inventory_item`, and
   `check_artifacts`. `export_artifacts` appears when a node has a StartCLI installation
   (`cli.home`), `restart_process` and `kill_process` when at least one node has effective write
   access (section 3, "Write settings"), the development tools when a node has
   `development.enabled: true`, and `deploy_release` when a group has a `deploy` record; the full
   list is in the [README](../README.md#tools).
3. Ask "Which INUBIT systems do you know?" (`list_nodes`) and "Is dev up?" (`get_health`); see
   [tools.md](tools.md) for all tools and example prompts.

**After changing an `INUBIT_*` variable** (new password, new node variable), reload the shell
profile and **restart Claude Code** from that shell. `/mcp` → reconnect is not enough: it restarts
the server with the environment of the running Claude Code process, which still holds the old
values (section 4).

The server speaks MCP over stdio (stdout carries protocol messages only; logs go to stderr as JSON
lines) and exits when the client closes stdin. Audit records of write actions are written to
`auditDirectory` (default `~/.inubit-mcp/<profile.name>/audit`, created owner-only).

To remove the registration: `claude mcp remove --scope user inubit-acme`.

### Several profiles side by side

One server process serves exactly one profile. For several customers or projects, keep **one
configuration file and one MCP client registration per profile**, each with its own registration
name. With the files in `~/.config/inubit-mcp/<name>.yaml`, `--profile` selects them:

```bash
claude mcp add --scope user inubit-acme -- java -jar /absolute/path/to/inubit-mcp-server-<version>.jar \
  --profile acme
claude mcp add --scope user inubit-globex -- java -jar /absolute/path/to/inubit-mcp-server-<version>.jar \
  --profile globex
```

Both profiles may use the same group and node names (e.g. both have `test/node1`); nothing of one
profile is used by the other:

| What | Per profile | Keep apart by |
|---|---|---|
| Credentials | variables under the profile's prefix: `INUBIT_ACME_TEST_USERNAME` for `acme`, `INUBIT_GLOBEX_TEST_USERNAME` for `globex` (section 4); or Parameter Store names in the profile's `credentials.parameters` | the default prefix `INUBIT_<PROFILE>`; an explicit `credentials.envPrefix` must differ from every other profile's. Parameter Store names need no separation: two profiles may read the same parameter |
| Audit records | `~/.inubit-mcp/acme/audit`, `~/.inubit-mcp/globex/audit`; every record carries `profile` | the default `auditDirectory`; an explicit one must differ from every other profile's |
| Temporary export data | `inubit-mcp-export-<profile>-<pid>-<random>` below `java.io.tmpdir`, deleted after each export and when the server stops; the name shows the profile name to whoever can list `java.io.tmpdir` (on macOS a per-user directory; on Linux usually the shared `/tmp`) | automatic |
| Confirmation codes, caches, the 60-s login pause | in the memory of the profile's own process | automatic: a code issued by one profile is refused by the other (`CONFIRMATION_INVALID`) |

- **Temporary data and its cleanup**: a server killed with `SIGKILL` cannot delete its export
  directory. The next start **of the same profile** deletes such directories of its own profile
  whose process is gone (owned by you, real directories only, links are never followed), plus
  unnamed directories of feature 001 (`inubit-mcp-export-<pid>-<random>`). It never touches
  another profile's directories, not even those of a stopped one, and never those of a running
  process.
- **Unique profiles**: profile names must be unique on a workstation. `--check-config` warns when
  another file in `~/.config/inubit-mcp` (Windows: `%APPDATA%\inubit-mcp`) has the same profile
  name, the same credential prefix or the same audit directory as the checked one, and reports an
  error when a file of another profile name derives a credential variable name of the checked one
  (section 4; best effort: files elsewhere are not compared). A profile name read from another file is printed only if it
  is a valid name, otherwise as `(invalid profile.name)`. If two copies of one profile run anyway, they share the
  audit directory (each record is appended atomically and names its node) and still keep their
  temporary data apart by process id.
- The assistant tells the profiles apart by the registration name, the `[acme: …]` prefix of
  every tool description, the server title "INUBIT MCP – acme" and the `profile` in the
  `list_nodes` result.
- Each profile may use its own terminology; tool inputs and results keep the same field names
  (`target`, `node`, `group`) for every profile.
- Run `--check-config` once per profile (`--profile acme --check-config`, `--profile globex
  --check-config`): each lists only its own variable names and its own audit directory.
- **After changing a variable** of any profile, reload the shell profile and restart the MCP
  client (e.g. Claude Code) from that shell; every registration then gets the new environment.
  Reconnecting a single server is not enough (section 4).
