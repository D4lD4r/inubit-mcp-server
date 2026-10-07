# Setup

How to build, configure and register the INUBIT MCP server. The authoritative configuration
reference is [contracts/configuration.md](../specs/001-inubit-mcp-mvp/contracts/configuration.md);
this guide walks through it. All host names below are examples (`*.example.test`).

## 1. Prerequisites

| What | Why |
|---|---|
| JDK 21 or newer on `PATH` (`java -version`) | runs the server |
| Maven 3.9+ (`mvn -v`) | only to build the JAR from source; a released JAR can be downloaded instead ([README](../README.md#installation-in-claude-code)) |
| INUBIT 8.1.17 client installation (Workbench), e.g. `/opt/inubit/client` with `bin/startcli.sh` | CLI-backed tools only (process restart/kill, module list and version histories of the inventory); must match the servers' patch level |
| Temurin (or another) JDK **17** | StartCLI 8.1 runs on Java 17, not on the JDK of the server |
| An INUBIT account per group (or node) with rights to read logs, monitoring and models, plus "CLI login access" for CLI-backed tools | REST and CLI access |
| `openssl` and `keytool` (part of the JDK) | only for a self-signed server certificate (section 5) |
| `git` 2.32 or newer on `PATH` (`git --version`) | only for the artifact workspace (`export_artifacts`, section 3); the history stays local |

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
The only exception is the top-level `credentials` section below, which names the credential
variables and never holds a value (a `password` key inside it is an error as well). One server
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
- `versionLine` defaults to `AUTO` (detected from `/system/info` on first use). `V9_X` and
  detected 9.x servers run with the 8.1 adapters and an "unsupported" warning. AUTO detection
  failure → 8.1 with a warning (retried after 60 s, so that rejected credentials are not tried
  again on every call); set `versionLine: V8_1` to skip the detection.
- Durations are ISO-8601 (`PT5S`, `PT10M`); `~` at the start of a path is your home directory.
- Other settings, each with a working default (details in the configuration contract):

  | Setting | Default | Meaning |
  |---|---|---|
  | `timeout` | `PT5S` | timeout of one REST call; a health overview of all servers returns within the largest `timeout` + 1 s |
  | `cliTimeout` | `PT30S` | timeout of one StartCLI call of `restart_process` / `kill_process` |
  | `hangingThreshold` | `PT60M` | when an `ACTIVE`, `WAITING` or `QUEUED` instance counts as hanging (`find_processes`) |
  | `resultLimits.maxItems` / `.maxChars` | `100` / `50000` | top level: largest page and largest result (characters of JSON) per tool call |
  | `logLevel` | `INFO` | top level: `ERROR`, `WARN`, `INFO`, `DEBUG` or `TRACE`; logs go to stderr as JSON lines |
  | `auditDirectory` | `~/.inubit-mcp/<profile.name>/audit` | top level: where audit records of write actions are written |
  | `allowInsecureHttp` | `false` | per node: allows an `http://` `baseUrl` (startup warning; never for real systems — credentials would travel unencrypted) |

  The durations (`timeout`, `cliTimeout`, `hangingThreshold`, `cliExportTimeout`,
  `confirmationTtl`) and `inventory` can be set in `defaults`, per group or per node;
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

### Inventory settings

The inventory tools (`list_inventory`, `get_inventory_item`) use three settings, each
configurable in `defaults`, per group or per node:

| Setting | Default | Meaning |
|---|---|---|
| `inventory.owner` | none (required for the inventory tools) | the owning Workbench user or user group whose diagrams and modules are listed. It is inserted into StartCLI commands, so it must match `^[A-Za-z0-9_.][A-Za-z0-9_.\- ]{0,199}$` (startup error otherwise). Without an owner for a node, `list_inventory` and `get_inventory_item` return `NOT_CONFIGURED` for that node ("set inventory.owner for <group>/<node> or in defaults"); all other tools work. `--check-config` warns once when no owner is set anywhere while a CLI is configured |
| `inventory.cacheTtl` | `PT10M` | how long diagram lists, module lists and version histories are cached per node; tools accept `refresh: true` to bypass it |
| `cliExportTimeout` | `PT120S` | timeout of one StartCLI export (module list about 10–15 s, version history of a diagram group about 5 s on DEV); also the budget of the module usage index (reading the nodes of every technical workflow over REST, about 2–3 s on DEV); separate from `cliTimeout` |

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
executed or failed — is appended to the audit log before the result is returned. Calls whose
arguments violate the input schema (group id instead of a node id, malformed process id or code, reason over 500
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
  (`restart_process` /
  `kill_process`), `step` (`PREVIEW` for the first call of a two-step confirmation, `EXECUTE`
  otherwise), `inputs` (`processId`, `confirmationCode` / `issuedConfirmationCode` as
  `sha256:` + 16 hex chars only, `reason`), `account` (the INUBIT username), `outcome`, `reason`
  (refusal or failure code and message, or the result), `mcpClient` (name/version the client
  sent in `initialize`). Absent values are omitted.
- Outcomes: `CHALLENGE_ISSUED` (preview), `PENDING` (written **before** StartCLI runs; if it
  cannot be written, the action is not executed), then `EXECUTED` or `FAILED` with the same
  `auditId`; `REFUSED` for every refusal. A confirmed restart therefore leaves three records:
  `CHALLENGE_ISSUED`, `PENDING`, `EXECUTED`.
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
    <group>/<owner>/repository/<repository path>   only files the exported artifacts reference
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
- One export or check at a time: a second call is refused at once while another one runs.

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
node is a development stage, and they refuse every other node (`NOT_DEVELOPMENT`). Tool
reference: [tools.md](tools.md#development-on-a-development-stage-feature-004).

| Setting | Level | Default | Meaning |
|---|---|---|---|
| `development.enabled` | defaults, group, node | `false` | the node is a development stage; **not allowed on `production: true` groups** (startup error); at most one development-enabled node per group |
| `development.confirmation` | defaults, group, node | `SERVER` | `SERVER`: every write first returns a preview and a one-time code (bound to the inputs and the server state, valid `confirmationTtl`); `CLIENT`: the write runs on the first call and the MCP client must ask the user |
| `e2eTests` | defaults, group, node | `FORBIDDEN` | `FREE`, `CONFIRM` (preview and code first) or `FORBIDDEN`; `FREE`/`CONFIRM` need `e2e.soap.baseUrl` and are not allowed on production |
| `e2e.soap.baseUrl` | group, node | — | the base address of the node's SOAP endpoints; `https` (plain `http` gives a startup warning), no query or fragment; the node's `tls` settings apply |

The node wins over the group, the group over `defaults`.

```yaml
groups:
  - name: dev
    development:
      enabled: true          # confirmation defaults to SERVER
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
- **Secrets.** Placeholders `${secret:…}` in the workspace are replaced by the values currently on
  the target node, in memory and in the private temporary import file only (deleted after the
  import). A placeholder without a value on the target is refused (`SECRET_UNRESOLVED`).
- **End-to-end tests.** Envelopes are workspace files; a WS-Security password must stay a
  placeholder. An optional HTTP basic authentication for the SOAP endpoints comes only from the
  variables `<PREFIX>_<GROUP>_E2E_USERNAME` / `<PREFIX>_<GROUP>_E2E_PASSWORD` (node-specific
  `<PREFIX>_<GROUP>_<NODE>_E2E_USERNAME` / `_E2E_PASSWORD` win), like the node credentials;
  without both, no `Authorization` header is sent. Responses are stored below
  `<workspace>/.tests/e2e/` and removed after 30 days.
- **Backups.** Every write first stores the raw export of its scope in
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
`development: on (confirmation SERVER)` or `development: off` and `e2e: FREE (<url>)`,
`e2e: CONFIRM (<url>)` or `e2e: FORBIDDEN`:

```text
Group dev:
  Node dev/node1: read-only, development: on (confirmation SERVER), e2e: CONFIRM (https://inubit-dev-1.example.test:8443), cli: available, username ← INUBIT_ACME_DEV_USERNAME, password ← INUBIT_ACME_DEV_PASSWORD
```

## 4. Credentials: environment variables

Credentials come only from environment variables. Their names start with the profile's
**credential prefix**, followed by the group and node name: upper case, with every character
other than `A–Z`/`0–9` replaced by `_`.

| Variable | Scope |
|---|---|
| `<PREFIX>_<GROUP>_<NODE>_USERNAME` / `_PASSWORD` | one node (wins) |
| `<PREFIX>_<GROUP>_USERNAME` / `_PASSWORD` | every node of the group |
| `<PREFIX>_<GROUP>[_<NODE>]_TRUSTSTORE_PASSWORD` | only for a password-protected trust store; **not allowed** for nodes with CLI configured |

- `<PREFIX>` is `credentials.envPrefix` if set, otherwise `INUBIT_` plus the profile name
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
- The warning about a `<PREFIX>_*_PASSWORD` variable that matches no group or node ignores the
  variables of the other profiles in the default configuration directory (`INUBIT_ACME_2_…` is
  no typo of `acme`). Variables of profiles whose files are elsewhere may still be reported.
- `credentials.envPrefix: INUBIT` keeps the variable names of feature 001
  (`INUBIT_<GROUP>[_<NODE>]_…`); use it for one profile only
  ([migration-001-to-002.md](migration-001-to-002.md)).
- Every message that asks you to fix a credential names the exact variables of the node, with
  the effective prefix; `--check-config` shows the scheme and, per node, the variables it uses.

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

### Claude Desktop and other GUI clients (macOS)

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

If INUBIT rejects a password (HTTP 401 or a StartCLI login failure), the server does not try
that server's credentials again for 60 s and reports `AUTH_FAILED` "authentication failed
recently; not retried for N s to avoid account lockout" instead: at most one failed login per
server per minute. A resolved password shorter than 4 characters is a configuration error.

Passwords are never logged, printed or returned; every tool result and log line is scrubbed of
them. StartCLI receives the password on stdin, never as a process argument.

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

## 6. Check the configuration

```bash
java -jar target/inubit-mcp-server-*.jar --profile acme --check-config
```

It validates the file, resolves the credential variables and prints to **stdout** the profile,
its terminology, the credential variable scheme and the audit directory, then per group one line
per node — id, read-only or write flag, the development settings (if any node has them, see
[Development settings](#development-settings)), CLI
availability and the **names** of the variables used (never their values) — followed by
warnings and errors. Groups and nodes are named with the profile's terminology. It does not
start the MCP server and creates nothing. Example for the complete example profile of section 3,
saved as `~/.config/inubit-mcp/acme.yaml` (`--profile acme --check-config`):

```text
Configuration: /Users/jdoe/.config/inubit-mcp/acme.yaml
Profile: acme (ACME – INUBIT integration platform)
Terminology: Umgebung/Umgebungen, Knoten/Knoten
Credential variables: INUBIT_ACME_<UMGEBUNG>[_<KNOTEN>]_USERNAME / _PASSWORD
Audit directory: /Users/jdoe/.inubit-mcp/acme/audit
Workspace: /Users/jdoe/.inubit-mcp/acme/workspace (ok)
Umgebung test:
  Knoten test/node1: write enabled (confirmation SERVER), cli: available, username ← INUBIT_ACME_TEST_USERNAME, password ← INUBIT_ACME_TEST_PASSWORD
  Knoten test/node2: write enabled (confirmation SERVER), cli: available, username ← INUBIT_ACME_TEST_USERNAME, password ← INUBIT_ACME_TEST_PASSWORD
Umgebung prod (production):
  Knoten prod/node1: read-only, cli: available, username ← INUBIT_ACME_PROD_USERNAME, password ← INUBIT_ACME_PROD_PASSWORD
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

The server itself refuses to start on any configuration error and prints all of them to stderr;
warnings are printed to stderr as well. `--version` prints `inubit-mcp-server <version>` to
stdout.

| Exit code | Meaning |
|---|---|
| `0` | success: `--version`, `--check-config` without errors, or the server stopped because stdin was closed |
| `1` | configuration error (also with `--check-config`), no configuration file found, or another startup failure (one line on stderr) |
| `2` | usage error: unknown argument, `--config` without value or twice, `--profile` without a valid profile name or twice, `--config` together with `--profile`, `--check-config` together with `--version` |

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
   count, never print the values) and start `claude` from it.
2. `/mcp` shows `inubit-acme` as connected with the six read-only tools `list_nodes`,
   `get_health`, `find_processes`, `query_logs`, `list_inventory` and `get_inventory_item`.
   `restart_process` and `kill_process` appear only when at least one server has effective write
   access (section 3, "Write settings").
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
| Credentials | variables under the profile's prefix: `INUBIT_ACME_TEST_USERNAME` for `acme`, `INUBIT_GLOBEX_TEST_USERNAME` for `globex` (section 4) | the default prefix `INUBIT_<PROFILE>`; an explicit `credentials.envPrefix` must differ from every other profile's |
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
