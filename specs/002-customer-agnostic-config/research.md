# Research: Customer-Agnostic Configuration

Phase 0 output of `/speckit-plan` for [spec.md](spec.md). Builds on feature 001
([plan](../001-inubit-mcp-mvp/plan.md), [research](../001-inubit-mcp-mvp/research.md)). The current-state
facts below come from a repository inventory made on 2026-10-04 (branch `002-customer-agnostic-config`).

## Current state (inventory summary)

| Topic | Where | Size |
|---|---|---|
| The previous namespace | 178 main + 88 test classes; `pom.xml` (groupId, `main.class`, build.properties include/exclude); `logback.xml` (2 FQNs); `BuildInfo` resource path; `PackageBoundaryTest.BASE`; test logger names; specs/001 plan/tasks | ~1,400 lines |
| Level names in config keys | `ServerConfig.stages`, `StageConfig.servers`, `ConfigLoader` path labels, validator messages | ~30 sites |
| Level names in tool contract | 16 schema files (`stage`, `stages`, `server`, `servers` properties and descriptions), tool name `list_servers`, write input `server`, 8 `DESCRIPTION` constants, record fields (`ServerSummary`, `HealthReport`, `ProcessInstance`, `LogEntry`, `InventoryItem`, `InventoryDetail`, `Preview`, `ProcessControlResult`, `ToolError.server`, page entries), audit JSON (`server`, `stage`), `ErrorCode.ENVIRONMENT_UNKNOWN` | ~120 sites |
| Credential naming | `CredentialResolver` (`"INUBIT_" + STAGE [+ "_" + SERVER] + "_" + KIND`), `CredentialGuard.fixCredentials`, message texts in `ConfigValidator`, `CliOutputClassifier`, `SslContexts`; `tools/record-fixtures.sh` | ~15 main sites, ~300 test lines |
| Customer defaults | built-in default inventory owner (a customer user group) in `Defaults`; "restart Claude Code" (client-specific) in `CredentialGuard` | 2 main sites |
| Customer identifiers in repo | customer owner group, diagram group names, stage names, credential variable names, certificate CN, trust-store file names, host names in tests (~700 lines), fixtures (~130), docs, specs/001 and `tools/*` defaults | inventory kept outside the repository |
| Per-process resources | audit dir default `~/.inubit-mcp/audit`; audit record without profile; export dirs `inubit-mcp-export-<pid>-…` + startup sweep; serverInfo name `inubit-mcp-server`; config location `--config` → `INUBIT_MCP_CONFIG` → `~/.config/inubit-mcp/config.yaml` | — |
| Description/schema composition | static `DESCRIPTION` constants; schemas are static classpath files loaded verbatim by `SchemaResources`; single choke point `McpServerFactory.specification()` | — |

---

## D-1 Rename namespace and coordinates first, as a pure refactoring

- **Decision**: a dedicated first step moves all sources and resources from
  the previous namespace to `de/dadecker/inubit/mcp`, updates package and import statements,
  `pom.xml` (`groupId` `de.dadecker`, `main.class`, build.properties include and exclude), `logback.xml`
  FQNs, the `BuildInfo` resource path, `PackageBoundaryTest.BASE`, test logger names and Javadoc FQNs.
  There is no behaviour change, and the full suite must stay green (1,019 tests).
- **Rationale**: this is mechanical and very large (~1,400 lines). Keeping it separate makes later diffs
  reviewable and lets a reviewer confirm "no behaviour change" by test parity alone.
- **Alternatives considered**: doing the rename together with the functional changes. That mixes
  diffs and makes the review harder.

## D-2 Configuration format v2: neutral keys, profile, terminology

- **Decision**: new top-level structure (full contract: [contracts/configuration.md](contracts/configuration.md)):

  ```yaml
  profile:                # required
    name: acme            # ^[a-z0-9][a-z0-9-]{0,31}$
    description: "ACME – INUBIT integration platform"   # optional, ≤ 200 chars, one line
  terminology:            # optional; defaults group/groups, node/nodes
    group: { singular: Stage, plural: Stages }
    node:  { singular: Server, plural: Servers }
  credentials:            # optional
    envPrefix: INUBIT     # default INUBIT_<PROFILE>
  auditDirectory: …       # default ~/.inubit-mcp/<profile>/audit
  defaults: { … }         # as in 001, but inventory.owner has no default
  groups:
    - name: test
      production: false
      nodes:
        - name: node1
          baseUrl: https://…
  ```

  - Machine-readable keys are neutral and identical for all profiles: `groups`, `nodes`.
  - All block keys from 001 are unchanged: `write`, `tls`, `cli`, `inventory`, `defaults`, `timeout`
    and so on.
- **Rationale**:
  - FR-008: machine names neutral.
  - FR-002/006: profile and terminology live in the file.
  - The 001 inheritance (node → group → defaults → built-in) is kept unchanged.
- **Alternatives considered**: configurable YAML keys (`stages:` vs `umgebungen:`). Rejected: it
  complicates the parser and validation and the docs, for no user value beyond display names.

## D-3 Tool contract: neutral names, terminology only in text

- **Decision**: rename the machine-readable names in the tool contract, which is a breaking change
  covered by the migration guide (full delta: [contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md)):

  | 001 | 002 |
  |---|---|
  | tool `list_servers` | `list_nodes` |
  | output `stages[]` / `servers[]` (list) | `groups[]` / `nodes[]` |
  | field `stage` | `group` |
  | field `server` (results, errors, preview, audit) | `node` |
  | write input `server` | `node` |
  | `ErrorCode.ENVIRONMENT_UNKNOWN` | `TARGET_UNKNOWN` |
  | — | `ErrorCode.NOT_CONFIGURED` (FR-014, missing inventory owner) |
  | `ServerSummary` | `NodeSummary` (+ `profile` on the list result) |

  - Internal type names follow (`ServerId` → `NodeId`, `StageId` → `GroupId`, `Target.Stage` /
    `Target.Server` → `Target.Group` / `Target.Node`, `StageConfig` → `GroupConfig`,
    `ServerEntryConfig` → `NodeConfig`, …), so that code and contract use the same words.
- **Rationale**: FR-008 requires that results look the same for every profile. Customer words appear
  only where a human or the model reads text (FR-007).
- **Alternatives considered**: keeping `stage`/`server` as neutral machine names. Rejected, because
  they are exactly the customer-shaped terms the user wants gone.

## D-4 Terminology rendering at one choke point plus a `Terms` value

- **Decision**:
  - The domain gets a value object `Terminology` (group and node, each singular and plural).
  - Tool descriptions become templates with placeholders `{group}`, `{groups}`, `{node}`, `{nodes}`
    (the configured names exactly, written as inside an English sentence),
    `{Group}`/`{Groups}`/`{Node}`/`{Nodes}` (first code point upper-cased, only at a sentence start or
    in a title), and `{profile}`. Templates never put "a"/"an" directly before a placeholder and never
    inflect one (`{node}s`, `{node}'s`), so they work for any language and any name (US1 review U1).
  - Schema files may use the same placeholders in `description` strings only.
  - `McpServerFactory.specification()` renders the descriptions and schema text. It also prefixes every
    tool description with `"[<profile>: <description>] "`, or `"[<profile>] "` when there is no
    description.
  - Placeholders are validated: an unknown placeholder fails at startup, and a test checks all 16
    schemas and 8 descriptions.
  - Human-readable messages built in application, config and adapter code use `Terms` injected through
    the composition root (`Wiring`), not hard-coded "stage"/"server".
- **Rationale**: one rendering point for the announced contract, and an explicit dependency for
  messages. The schemas stay static files that are reviewable as such.
- **Alternatives considered**: generating schemas in code. Rejected: it loses the reviewable JSON files
  that the 001 tests rely on.

## D-5 Profile visibility to the assistant

- **Decision**: the profile becomes visible in three places:
  - **Tool descriptions** carry the profile prefix (D-4).
  - **`list_nodes`** returns `profile: {name, description}` plus the terminology.
  - **`serverInfo`** keeps the name `inubit-mcp-server` and the version, and its `title` (protocol
    2025-06-18+) becomes "INUBIT MCP – <profile>" so that the self-identification names the profile even
    without instructions. If the SDK lacks `title` (spike T-S1), the server name becomes
    `inubit-mcp-server-<profile>`.
  - The MCP initialize result's server `instructions` carry the text "INUBIT systems of profile <name> (<description>). Targets are
    <groups> (<Group>) and <nodes> (<Node>) …", if SDK 2.0.1 exposes `instructions(...)` on the server
    builder. That is verified at implementation (spike T-S1). Otherwise the tool descriptions and
    `list_nodes` suffice.
- **Rationale**: FR-003. Assistants show tools grouped by registration name, and the description
  prefix makes the profile explicit even in flat tool lists.

## D-6 Credential variable prefix

- **Decision**:
  - Variables are named `<PREFIX>_<GROUP>[_<NODE>]_<KIND>`, where KIND is `USERNAME`, `PASSWORD` or
    `TRUSTSTORE_PASSWORD`.
  - The default `PREFIX` is `INUBIT_` + normalize(profile.name), for example `INUBIT_ACME`. An
    explicit `credentials.envPrefix` (`^[A-Z][A-Z0-9_]{0,63}$`) overrides it. `INUBIT` reproduces the
    001 names, which is the migration path.
  - Collision detection and the "unmatched variable" warning use the effective prefix instead of the
    fixed `INUBIT_`.
  - All message texts are generated from the effective prefix.
  - The client-specific text "restart Claude Code" becomes "restart the MCP client".
- **Rationale**: FR-010, and it keeps today's variables working through an explicit prefix.
- **Alternatives considered**: a mandatory explicit prefix. Rejected: a default derived from the
  profile is safe and needs less configuration.

## D-7 Audit location and record

- **Decision**:
  - The default `auditDirectory` becomes `~/.inubit-mcp/<profile>/audit`. Records gain a first-level
    field `profile`; `server` becomes `node` and `stage` becomes `group`.
  - Field order: `auditId`, `timestamp`, `profile`, `node`, `group`, `capability`, `step`, `inputs`,
    `account`, `outcome`, `reason`, `mcpClient`.
  - The old directory `~/.inubit-mcp/audit` is never touched; the migration guide points to it.
  - The configuration check warns when two profiles share an audit directory **only** if the other
    profile's file is found in the default configuration directory. That is best effort.
- **Rationale**: FR-011, and existing records stay readable.

## D-8 Temporary export data per profile

- **Decision**:
  - Export directories become `inubit-mcp-export-<profile>-<pid>-<random>`.
  - The startup sweep deletes only directories that match **its own profile**, are owned by the current
    user and whose pid is dead.
  - The sweep keeps removing legacy 001 directories (`inubit-mcp-export-<pid>-…`, no profile) under the
    same owner and dead-pid rules, because no running 002 instance can own them.
  - The `CliPaths` sample path is updated accordingly.
- **Rationale**: FR-012. A crashed profile's data is only cleaned up by the same profile. Legacy data is
  ownerless and safe to clean.

## D-9 Profile selection

- **Decision**: the configuration file is located in this order (first match wins):
  1. `--config <path>`
  2. `--profile <name>` → `~/.config/inubit-mcp/<name>.yaml` (Windows `%APPDATA%\inubit-mcp\<name>.yaml`)
  3. env `INUBIT_MCP_CONFIG`
  4. env `INUBIT_MCP_PROFILE`, resolved like `--profile`
  5. `~/.config/inubit-mcp/config.yaml`

  - `--config` together with `--profile` is a usage error (exit 2).
  - With `--profile`, the file's `profile.name` must equal the argument (error otherwise).
- **Rationale**: FR-004 and a one-line MCP registration per customer:
  `claude mcp add inubit-acme -- java -jar …jar --profile acme`.

## D-10 No customer defaults; NOT_CONFIGURED

- **Decision**:
  - Remove `Defaults.BUILTIN_INVENTORY_OWNER`. `inventory.owner` is optional at every level.
  - If it is missing for a node, `list_inventory` and `get_inventory_item` return `NOT_CONFIGURED`
    for that node, with nextStep "set inventory.owner for <group>/<node> or in defaults".
  - The configuration check warns once per profile when no owner is set anywhere and a CLI is
    configured.
  - No other built-in default refers to a customer, and all defaults are listed in the contract.
- **Rationale**: FR-014/015.

## D-11 Neutral test data and repository cleanup

- **Decision**: one scripted, reviewable replacement pass over current files. The **mapping from customer
  values to neutral values is kept in a local, untracked file** (path in `INUBIT_MCP_NEUTRALIZE_MAP`, or
  `.neutralize-map` in the repository root, git-ignored), in the same way as the denylist. The script
  `tools/neutralize.py` only contains the mechanism, no customer values. Mapping categories (the
  neutral targets are fixed so that tests and docs are consistent):

  | Category | Neutral value |
  |---|---|
  | customer inventory owner group | `OWNERS` |
  | customer diagram groups | `GRP-<suffix>` in the first pass; then fully synthetic (below) |
  | customer stage names used in tests/docs | `dev` / `qa` |
  | customer node names | `node1` / `node2` |
  | customer credential variables | variables of the neutral test profile `acme` (`INUBIT_ACME_DEV_*`, `INUBIT_ACME_QA_*`) |
  | customer certificate CN in test certificates | `selfsigned.example.test` |
  | customer trust-store file name / YAML anchor names | `truststore.p12` / `x-tls` |
  | real host names | `inubit-<stage>-<n>.example.test` |
  | customer-specific local paths in docs | `/opt/inubit/client` |
  | previous code namespace in prose | "the previous namespace" |

  - **Business object names are fully synthetic** (review decision N1): diagram groups `GRP-01`…,
    workflows/diagrams `Workflow-0001`…, modules `Module-0001`…, web services `Service-01`…, version
    tags `TAG-01`…. `tools/synthesize_names.py` collects the names from the fixture formats and writes
    a deterministic local mapping (`.synthetic-map`, git-ignored) that `tools/neutralize.py --map`
    applies to fixtures, test code and docs. Numbers follow the case-insensitive name order, so sorted
    results keep their order; every name gets its own number. Single-token names (letters and digits
    only) are replaced only as complete quoted values or element text. Plugin types (e.g.
    `XSLT Converter`, `AS2 Connector`) stay. Names that only occur in test code or docs were chosen by
    hand with the properties the tests need (substring, sort order, edit distance).
  - Fixture ZIPs are re-packed after replacing the entry contents.
  - `tools/record-fixtures.sh` and `tools/anonymize.py` lose customer defaults (sample objects and keep
    names become arguments or environment, without defaults).
  - `specs/001` **and `specs/002`** documents are neutralized in place with a note "customer identifiers
    neutralized in feature 002". Git history is not rewritten.
  - A repository guard test, `NoCustomerIdentifiersTest`, scans tracked text files for a denylist
    pattern supplied by the developer. It reads the environment `INUBIT_MCP_DENYLIST` or a local
    untracked file `.denylist`, so the denylist itself is not committed. It is skipped when no denylist
    is configured. Running it with the denylist is a mandatory release check
    (`docs/release-checks.md`; SC-002).
- **Rationale**: FR-015, SC-002. A committed denylist would reintroduce the customer names, so it stays
  local.

## D-12 Migration from the 001 format

- **Decision**:
  - The loader detects the 001 format by top-level `stages` or nested `servers`, or by a missing
    `profile`, and refuses to start.
  - The message lists each obsolete key and its replacement: `stages` → `groups`, `servers` → `nodes`,
    the missing `profile`, and the env-prefix hint ("set credentials.envPrefix: INUBIT to keep your
    variables").
  - It also mentions the old audit directory and points to `docs/migration-001-to-002.md`.
  - No automatic rewrite tool. YAGNI: one existing installation, migrated by hand with the guide.
- **Rationale**: FR-018/019, SC-005.

## D-13 Live tests

- **Decision**:
  - `INUBIT_LIVE_SERVER` becomes `INUBIT_LIVE_NODE` (old name rejected with a hint).
  - Live tests resolve the configuration through the same location order (D-9), so
    `INUBIT_MCP_PROFILE=<name>` selects the customer.
  - The production refusal is unchanged.
  - The user's own configuration lives outside the repository and is migrated by the user, or by the
    coordinator with approval, as a final step.
- **Rationale**: no customer names in test code; live tests work for any profile.

## Spikes

| ID | Question | Method | Fallback |
|---|---|---|---|
| T-S1 | Does MCP Java SDK 2.0.1's sync server builder support `instructions(String)` and a `serverInfo` title? | inspect SDK API; start a server in a test | name `inubit-mcp-server-<profile>`; descriptions plus `list_nodes` (D-5) |

### T-S1 result (2026-10-04)

**Both supported; the fallback is not needed.** D-5 is implemented as decided: `serverInfo.name` stays
`inubit-mcp-server`, `serverInfo.title` carries the profile, and `instructions` carries the profile text.

- **API** (`javap` on `mcp-core-2.0.1.jar`, confirmed in `mcp-core-2.0.1-sources.jar`):
  - `McpServer.SyncSpecification#instructions(String)` exists. `McpAsyncServer` copies it into the
    `InitializeResult` (`McpSchema.InitializeResult#instructions()`).
  - `McpServer.SyncSpecification#serverInfo(McpSchema.Implementation)` exists next to the
    `serverInfo(String name, String version)` overload that 001 uses.
  - `McpSchema.Implementation` is a record `(name, title, version, description, icons, websiteUrl)`.
    It has a builder: `Implementation.builder(name, version).title(String).build()`.
- **Proof**: a throw-away test (deleted afterwards) started `McpServer.sync(new
  StdioServerTransportProvider(mapper, pipedIn, pipedOut))` with
  `.serverInfo(Implementation.builder("inubit-mcp-server", v).title("INUBIT MCP - acme").build())` and
  `.instructions("…")`, and sent a raw `initialize` over the piped stdio. For the client protocol
  versions `2024-11-05`, `2025-06-18` and `2025-11-25`, the result contained
  `"serverInfo":{"name":"inubit-mcp-server","title":"INUBIT MCP - acme","version":…}` and
  `"instructions":"…"`.
- **Note**: the SDK sends `title` for every negotiated protocol version, including `2024-11-05`, which
  does not define it. Older clients ignore the unknown field, so no version check is needed in our code.
