# Migrating a configuration from feature 001 to feature 002

Feature 002 makes the server customer-agnostic: one configuration file describes one **profile**
(a customer or project), and the two levels "stage" and "server" are now called **groups** and
**nodes** in the YAML keys and the tool contracts. A configuration file of feature 001 is refused
at startup; this guide turns it into a profile that behaves exactly as before: the same nodes,
the same credential variables, and, if you like, the same words in every message.

The example uses the fictitious profile `acme` with the groups `dev` and `qa` and the nodes
`node1` and `node2`; all hosts are `*.example.test`. Replace them with your own names. The
complete configuration reference is [setup.md](setup.md).

## What you see before migrating

Started with a 001 file, the server (and `--check-config`) exits with code 1 and prints which keys
to change, without echoing any value of the file:

```text
Configuration file /Users/jdoe/.config/inubit-mcp/config.yaml uses the format of feature 001 (or is not fully migrated); this version reads only the new format. Change these keys:
  - stages (top level): rename to groups
  - servers (at stages[0].servers, stages[1].servers): rename to nodes
  - profile (missing): add a profile block at the top, profile: {name: <name>}, the name matching ^[a-z0-9][a-z0-9-]{0,31}$, except audit
To keep the credential variables of feature 001 (INUBIT_<STAGE>[_<SERVER>]_USERNAME / _PASSWORD), add credentials.envPrefix: INUBIT; without it the variables start with INUBIT_<PROFILE>.
Audit records of feature 001 stay in their directory (default ~/.inubit-mcp/audit); new records go to ~/.inubit-mcp/<profile.name>/audit unless auditDirectory is set.
Step-by-step guide: docs/migration-001-to-002.md
```

A file that mixes old and new keys (e.g. `groups` with a `servers` list inside) is refused the
same way; the message names each old key and where it is.

## Key mapping

| Feature 001 | Feature 002 | Note |
|---|---|---|
| — | `profile.name` (required) | `^[a-z0-9][a-z0-9-]{0,31}$`; `audit` is reserved |
| — | `profile.description` (optional) | one line, at most 200 characters; shown to the assistant |
| — | `terminology` (optional) | the words used in messages and descriptions; default `group`/`groups`, `node`/`nodes` |
| — | `credentials.envPrefix` (optional) | default `INUBIT_<PROFILE>`; `INUBIT` keeps the 001 variables |
| `stages` | `groups` | same content (`name`, `production`, `write`, `tls`, `cli`, timeouts, `inventory`, …) |
| `stages[].servers` | `groups[].nodes` | same content (`name`, `baseUrl`, overrides) |
| `auditDirectory` (default `~/.inubit-mcp/audit`) | `auditDirectory` (default `~/.inubit-mcp/<profile.name>/audit`) | see step 6 |
| `defaults.inventory.owner` (had a built-in default) | `inventory.owner` (no built-in default) | see step 7 |
| everything else | unchanged | `logLevel`, `resultLimits`, `defaults`, `x-` anchors, `tls`, `cli`, `write`, … |

Ids keep their form: the 001 server `qa/node2` is the node `qa/node2`.

## Step by step

### 1. Find your current file

The 001 server read `--config <path>`, else `INUBIT_MCP_CONFIG`, else
`~/.config/inubit-mcp/config.yaml` (Windows: `%APPDATA%\inubit-mcp\config.yaml`). `claude mcp list`
shows the command line of your registration.

### 2. Choose a profile name and copy the file

The name identifies the customer or project, e.g. `acme`. It must match
`^[a-z0-9][a-z0-9-]{0,31}$`; `audit` is reserved (its audit directory would lie inside the one of
feature 001). Copy the file to `<name>.yaml` in the default configuration directory, so that
`--profile <name>` finds it. Then rename the old file: a leftover `config.yaml` is still the
default location, so every start without `--profile` (or `--config`) would read it and be refused
with the message above. Keep it as a backup under a name that does not end in `.yaml`:

```bash
cd ~/.config/inubit-mcp
cp config.yaml acme.yaml
mv config.yaml config.yaml.001-backup
```

The copy keeps the owner-only permissions of the original (`chmod 600 acme.yaml` if not).

### 3. Rename the keys

In `acme.yaml`, rename the top-level `stages` to `groups` and every `servers` list to `nodes`.
Nothing inside them changes.

### 4. Add the profile block

Add at the top:

```yaml
profile:
  name: acme
  description: "ACME – INUBIT integration platform"
```

The description is optional. The assistant sees the profile in every tool description
(`[acme: ACME – INUBIT integration platform] …`), in the server title "INUBIT MCP – acme" and in
the `list_nodes` result.

### 5. Keep the old wording (optional)

Without a `terminology` section, messages and tool descriptions speak of "group" and "node". To
keep the words of feature 001, add:

```yaml
terminology:
  group: { singular: stage,  plural: stages }
  node:  { singular: server, plural: servers }
```

Write the words as they appear inside an English sentence, i.e. lower case (`stage`, not `Stage`);
the server upper-cases the first letter only at the start of a sentence or in a title. The
terminology is display text only: YAML keys, tool names and the input and result fields stay
`groups`/`nodes`, `group`/`node` (step 9).

### 6. Keep the credential variables

Feature 001 read `INUBIT_<STAGE>[_<SERVER>]_USERNAME` / `_PASSWORD` (and `_TRUSTSTORE_PASSWORD`).
Feature 002 reads `<PREFIX>_<GROUP>[_<NODE>]_…`, where the prefix defaults to `INUBIT_` plus the
profile name (`INUBIT_ACME`). To keep your exported variables unchanged, set the prefix
explicitly:

```yaml
credentials:
  envPrefix: INUBIT
```

The rule "node-specific variable before group-wide variable" is unchanged. Alternatively, rename
your variables to the default prefix (`INUBIT_DEV_USERNAME` → `INUBIT_ACME_DEV_USERNAME`) and
omit `credentials`; this is the better choice as soon as you add a second profile (step 10).

### 7. Audit directory and inventory owner

- **Audit records**: the records written by feature 001 stay where they are,
  `~/.inubit-mcp/audit/audit-YYYY-MM.jsonl` (or the `auditDirectory` your 001 file set); feature
  002 does not move or delete them. They
  keep the field names of feature 001 (`server`, `stage`) and have no `profile` field. Remove an
  explicit `auditDirectory: ~/.inubit-mcp/audit` from the file: new records then go to
  `~/.inubit-mcp/acme/audit`, carry `"profile":"acme"` and use the fields `node` and `group`.
  (An explicit `auditDirectory` still works, but two profiles must never share one.)
- **Inventory owner**: feature 001 had a built-in default for `inventory.owner`; feature 002 has
  none. If your 001 file did not set it, add the owning Workbench user or user group, e.g. in
  `defaults`:

  ```yaml
  defaults:
    inventory:
      owner: INTEGRATION
  ```

  Without it, `list_inventory` and `get_inventory_item` return `NOT_CONFIGURED` for that node,
  and `--check-config` warns if a CLI is configured.

### 8. Check and re-register

```bash
java -jar /absolute/path/to/inubit-mcp-server-<version>.jar --profile acme --check-config
```

It must end with `Result: OK` and list the same nodes and the same variable names as before
(with `envPrefix: INUBIT`: `Credential variables: INUBIT_<STAGE>[_<SERVER>]_USERNAME / _PASSWORD`).
Then replace the registration of feature 001 by one per profile:

```bash
claude mcp remove --scope user inubit
claude mcp add --scope user inubit-acme -- java -jar /absolute/path/to/inubit-mcp-server-<version>.jar \
  --profile acme
```

Use the scope and name of your old registration in `remove` (`claude mcp list` shows them). Restart
Claude Code from a shell that has the credential variables ([setup.md](setup.md), section 7).

### 9. Update prompts and scripts

The tool contracts use the neutral names for every profile, whatever the terminology:

| Feature 001 | Feature 002 |
|---|---|
| tool `list_servers` | tool `list_nodes`; result `{profile, terminology, groups: [{name, production, nodes: [...]}]}` |
| result field `server` (all result records: health reports, process instances, log entries, inventory items, previews, process control results, errors) | `node` |
| result field `stage` (health reports only) | `group` |
| Queue Manager column `node` of a process instance | `inubitNode` |
| input `server` of `restart_process` / `kill_process` | `node` (same id form `<group>/<node>`) |
| input `target` of the read tools | `target` (unchanged) |
| error code `ENVIRONMENT_UNKNOWN` | `TARGET_UNKNOWN` |
| — | new error code `NOT_CONFIGURED` (e.g. no `inventory.owner`, step 7) |
| audit fields `server`, `stage` | `node`, `group`, plus `profile` |

Two names keep their INUBIT meaning and are not the configured levels (see "INUBIT's own group and
node" in [contracts/mcp-tools-delta.md](../specs/002-customer-agnostic-config/contracts/mcp-tools-delta.md)):
the `group` input of `list_inventory` and the `group` of inventory items (the INUBIT diagram or
module group), and `fields.node` of a `query_logs` log entry (a raw INUBIT column).

A prompt such as "list the servers" still works; a script that calls `list_servers`, reads
`stages`/`server` from a result or passes `server` to a write tool must be changed. Details:
[tools.md](tools.md).

### 10. Adding more profiles later

A profile with `envPrefix: INUBIT` reads `INUBIT_<GROUP>…`, which other profiles can derive as
well: the profile `acme` (default prefix `INUBIT_ACME`) with the group `test` reads
`INUBIT_ACME_TEST_USERNAME`, and so does a profile with `envPrefix: INUBIT` and the group
`acme-test`. When two profile files in `~/.config/inubit-mcp` derive the same variable name, the
server refuses to start (`--check-config` exits 1) and names the other file and the shared
variable names. To resolve it, give the migrated profile its own prefix: remove
`credentials.envPrefix` (or set another one) and rename its exported variables accordingly
(`INUBIT_DEV_USERNAME` → `INUBIT_ACME_DEV_USERNAME`), or rename the conflicting group. Use
`envPrefix: INUBIT` for at most one profile.

## Before and after

The 001 file (`~/.config/inubit-mcp/config.yaml`):

```yaml
logLevel: INFO
auditDirectory: ~/.inubit-mcp/audit

defaults:
  timeout: PT5S
  inventory:
    owner: INTEGRATION

stages:
  - name: dev
    write:
      enabled: true
      confirmation: CLIENT
    servers:
      - name: node1
        baseUrl: https://inubit-dev-1.example.test:8443
  - name: qa
    servers:
      - name: node1
        baseUrl: https://inubit-qa-1.example.test:8443
      - name: node2
        baseUrl: https://inubit-qa-2.example.test:8443
```

The migrated profile (`~/.config/inubit-mcp/acme.yaml`):

```yaml
profile:                           # new: required, one profile per file
  name: acme
  description: "ACME – INUBIT integration platform"

terminology:                       # new, optional: keeps the wording of feature 001
  group: { singular: stage,  plural: stages }
  node:  { singular: server, plural: servers }

credentials:                       # new, optional: keeps the variables INUBIT_<STAGE>[_<SERVER>]_*
  envPrefix: INUBIT

logLevel: INFO
# auditDirectory removed: new audit records go to ~/.inubit-mcp/acme/audit

defaults:
  timeout: PT5S
  inventory:
    owner: INTEGRATION

groups:                            # was: stages
  - name: dev
    write:
      enabled: true
      confirmation: CLIENT
    nodes:                         # was: servers
      - name: node1
        baseUrl: https://inubit-dev-1.example.test:8443
  - name: qa
    nodes:                         # was: servers
      - name: node1
        baseUrl: https://inubit-qa-1.example.test:8443
      - name: node2
        baseUrl: https://inubit-qa-2.example.test:8443
```

With the unchanged variables

```bash
export INUBIT_DEV_USERNAME='jdoe'
export INUBIT_DEV_PASSWORD='…'
export INUBIT_QA_USERNAME='jdoe'
export INUBIT_QA_PASSWORD='…'
export INUBIT_QA_NODE2_PASSWORD='…'   # node-specific: wins for qa/node2
```

`--profile acme --check-config` lists the same three nodes with the same variables as feature
001:

```text
Configuration: /Users/jdoe/.config/inubit-mcp/acme.yaml
Profile: acme (ACME – INUBIT integration platform)
Terminology: stage/stages, server/servers
Credential variables: INUBIT_<STAGE>[_<SERVER>]_USERNAME / _PASSWORD
Audit directory: /Users/jdoe/.inubit-mcp/acme/audit
Stage dev:
  Server dev/node1: write enabled (confirmation CLIENT), cli: unavailable, username ← INUBIT_DEV_USERNAME, password ← INUBIT_DEV_PASSWORD
Stage qa:
  Server qa/node1: read-only, cli: unavailable, username ← INUBIT_QA_USERNAME, password ← INUBIT_QA_PASSWORD
  Server qa/node2: read-only, cli: unavailable, username ← INUBIT_QA_USERNAME, password ← INUBIT_QA_NODE2_PASSWORD
Result: OK
```

The repository keeps both blocks as test files (`src/test/resources/config/migration-before.yaml`
and `migration-after.yaml`); `MigrationGuideExampleTest` checks that this guide contains them
verbatim, that the "before" file is refused with the message above, and that the "after" file
passes the check with the same nodes and variable names.
