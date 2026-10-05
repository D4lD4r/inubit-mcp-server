# Contract: Configuration

> **Renamed in feature 002**: the YAML keys `stages`/`servers` are now `groups`/`nodes`, and the
> configuration types are `ProfileConfig`/`GroupConfig`/`NodeConfig`. Feature 002 also adds the
> required `profile` block and the `terminology` and `credentials` sections (configuration format
> v2); see [002 contracts/configuration.md](../../002-customer-agnostic-config/contracts/configuration.md),
> which supersedes this contract. The prose below still uses the 001 words "stage" and "server" for
> the two levels (002: group and node).

The server is configured by two inputs:

1. a **YAML file** describing stages, servers, and their settings (no credentials), and
2. **operating-system environment variables** carrying the credentials (FR-002a).

Field semantics and validation rules: [data-model.md → Configuration entities](../data-model.md).

## Location of the YAML file (first match wins)

1. `--config <path>` command-line argument
2. `INUBIT_MCP_CONFIG` environment variable
3. `~/.config/inubit-mcp/config.yaml` (Windows: `%APPDATA%\inubit-mcp\config.yaml`)

No config file found → startup error with the searched locations. A location that is given
explicitly (`--config` or `INUBIT_MCP_CONFIG`) but does not exist is an error; the later locations
are then **not** tried, so the server never silently falls back to another configuration.

## YAML format

Settings on a stage apply to all its servers; a server may override `baseUrl`-independent settings
(`write`, `tls`, `cli`, timeouts). Production classification is a **stage** property only (a stage is
either production or not).

```yaml
logLevel: INFO
auditDirectory: ~/.inubit-mcp/audit
resultLimits:
  maxItems: 100
  maxChars: 50000

defaults:
  timeout: PT5S
  cliTimeout: PT30S
  hangingThreshold: PT60M
  confirmationTtl: PT5M
  cliExportTimeout: PT120S
  inventory:
    owner: OWNERS                         # owning Workbench user group of diagrams/modules
    cacheTtl: PT10M
  cliHome: /opt/inubit/client      # contains bin/startcli.sh; must match server patch level
  cliJavaHome: /Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home  # JDK for StartCLI

groups:
  - name: dev
    write:
      enabled: true
      confirmation: CLIENT             # allowed: not production
    nodes:
      - name: inubit01
        baseUrl: https://inubit-dev-01.example.internal:8443

  - name: test
    write:
      enabled: true                    # confirmation defaults to SERVER
    tls:
      trustStore: ~/.config/inubit-mcp/truststore.p12
    nodes:
      - name: inubit01
        baseUrl: https://inubit-test-01.example.internal:8443
      - name: inubit02
        baseUrl: https://inubit-test-02.example.internal:8443
        write:
          enabled: false               # server-level override

  - name: prod
    production: true
    nodes:
      - name: inubit01
        baseUrl: https://inubit-prod-01.example.internal:8443
    # write.enabled defaults to false; even if true, productionOptIn is required
```

Identifiers used in tool inputs and results: stage `test`, server `test/inubit01`.

Unknown keys are rejected, except top-level keys starting with `x-`. These are ignored and exist
so that YAML anchors can share blocks such as a common `tls` section (`x-tls: &tls …` and
`tls: *tls`).

YAML rules:

- Anchors are supported on **mappings and sequences** only. Anchors on scalar values
  (`x-t: &t PT5S`) and merge keys (`<<: *tls`) are not supported and are rejected with a hint;
  anchor the whole mapping instead.
- Exactly one YAML document per file (no `---` separators); duplicate keys and empty list entries
  (`nodes: [null]`, a bare `-`) are errors.
- `~` at the start of a path means the user's home directory (`~/…`); a bare `~` is the home
  directory, not null.
- Durations are ISO-8601 strings and must be positive (`PT5S`, `PT10M`); plain numbers are
  rejected with the key path.
- Error messages name the key path but never echo the offending value.

## Credential environment variables

Variable names are derived from stage and server name: uppercase, every character that is not
`A–Z`/`0–9` replaced by `_`, prefixed with `INUBIT_`.

| Variable | Scope | Required |
|---|---|---|
| `INUBIT_<STAGE>_<SERVER>_USERNAME` | one server | one of server/stage form |
| `INUBIT_<STAGE>_<SERVER>_PASSWORD` | one server | one of server/stage form |
| `INUBIT_<STAGE>_USERNAME` | all servers of the stage without server-specific variable | — |
| `INUBIT_<STAGE>_PASSWORD` | all servers of the stage without server-specific variable | — |
| `INUBIT_<STAGE>_<SERVER>_TRUSTSTORE_PASSWORD` / `INUBIT_<STAGE>_TRUSTSTORE_PASSWORD` | trust store password, if the configured trust store needs one | only if `tls.trustStore` is password-protected; not allowed for servers with CLI configured (see validation) |

Resolution per server: server-specific variable → stage-wide variable → missing (startup error).
Username and password are resolved **independently** (e.g. stage-wide username with a
server-specific password is valid).

Example for the YAML above:

```bash
export INUBIT_DEV_USERNAME=jdoe
export INUBIT_DEV_PASSWORD='…'
export INUBIT_TEST_USERNAME=jdoe
export INUBIT_TEST_PASSWORD='…'          # valid for test/inubit01 and test/inubit02
export INUBIT_TEST_INUBIT02_PASSWORD='…' # overrides for test/inubit02 only
export INUBIT_PROD_USERNAME=jdoe
export INUBIT_PROD_PASSWORD='…'
export INUBIT_TEST_TRUSTSTORE_PASSWORD='…'
```

Variables are read once at startup. Values are never logged, echoed, or included in any result;
`--check-config` reports only *which* variable was used per server (e.g.
`test/inubit02: username ← INUBIT_TEST_USERNAME, password ← INUBIT_TEST_INUBIT02_PASSWORD`).

## Startup validation outcomes

| Situation | Behaviour |
|---|---|
| credential keys (`username`, `password`, `credentials`) present in the YAML | **error** — credentials belong in environment variables |
| no username or no password variable resolvable for a server | **error**, naming the expected variable names |
| two servers whose derived variable names collide (e.g. stage `a-b`/server `c` and stage `a`/server `b-c`) | **error** |
| duplicate stage name, or duplicate server name within a stage | **error** |
| stage or server name not matching `^[a-z0-9][a-z0-9-]{0,31}$` | **error** |
| `write.confirmation: CLIENT` on a stage with `production: true` (or its servers) | **error** |
| `confirmationTtl` longer than `PT1H` (effective value of any server) | **error** — a confirmation must refer to a recent preview |
| `http://` base URL without `allowInsecureHttp: true` | **error** |
| `disableHostnameVerification: true` without `pinnedCertificateSha256` | **error** |
| a trust-store password variable (`INUBIT_…_TRUSTSTORE_PASSWORD`) is resolved for a server with CLI configured (`cliHome`/`cli.home`) | **error** — StartCLI could only receive it as a process argument; use a password-less trust store for CLI-enabled servers |
| `http://` with `allowInsecureHttp: true`, or `disableHostnameVerification: true` (with pin) | warning |
| `cliHome` set but `bin/startcli.sh` (`.bat` on Windows) not found | warning; CLI tools report `CLI_UNAVAILABLE` |
| no `cliJavaHome` and no `JAVA_HOME`, or `startcli.sh -v` version ≠ server version line | warning; CLI tools report `CLI_UNAVAILABLE` / version-mismatch warning |
| `versionLine: V9_X` | warning "unsupported in this version" |
| any key named `username` or `credentials`, or containing `password` (case-insensitive), at any depth — including `x-*` blocks | **error** (the value is dropped, never printed) |
| user info in `baseUrl` or `cli.url`: **any** `@` anywhere in the value (`https://user:pw@host`, but also `https://u:p@x@h`, `https://u:p/q@h`, `https://u:p#x@h`) | **error**, naming only the key and the stage/server id; the whole value is dropped while loading (before binding), so no part of it can ever be printed (a dropped `baseUrl` is then also reported as missing) |
| query or fragment in `baseUrl` | **error** (removed while loading) |
| `cli.url` not `https://` (or `http://` without `allowInsecureHttp: true`) | **error**; `http://` with `allowInsecureHttp: true` → warning |
| `cli.url` set on a stage with more than one server | warning (all servers would use the same StartCLI endpoint) |
| `tls.trustStore` file not found | **error** |
| `tls.pinnedCertificateSha256` not 64 hex digits (optionally colon-separated) | **error** |
| `inventory.owner` not matching `^[A-Za-z0-9_.][A-Za-z0-9_.\- ]{0,199}$` (no leading `-`; it is inserted into a StartCLI command, research R-11) | **error** |
| a resolved username containing `:` or control characters, or starting with `-` (it goes into the Basic-auth header and the StartCLI `-u` argument) | **error**, naming only the variable |
| `cliHome` configured on Windows (CLI tools are not supported on Windows in this version) | warning; CLI tools report `CLI_UNAVAILABLE`, REST tools work |
| a duration that is zero or negative | **error** |
| `resultLimits.maxItems` < 1 or `resultLimits.maxChars` < 10000 | **error** |
| a trust-store password variable is resolved for a server without `tls.trustStore` | warning (the variable is ignored) |
| a resolved password is shorter than 4 characters | **error**: such a short value cannot be scrubbed reliably from logs and results without masking unrelated text (Phase 3 review m4; was a warning) |
| an `INUBIT_*_PASSWORD` variable is set that matches no configured stage/server | warning (likely typo), naming the variable, not its value |

All errors are collected and printed together to stderr; the process exits with a non-zero code.

## Command-line arguments

| Argument | Meaning |
|---|---|
| `--config <path>` | config file location |
| `--check-config` | validate config, resolve credential variables, print a summary (variable names only, no values) and exit `0`/`1` |
| `--version` | print server version and exit |

`--version` and `--check-config` print to **stdout** and exit without starting the MCP server.
Configuration errors, startup failures and usage errors go to **stderr**; in server mode stdout
carries MCP protocol messages only, and configuration warnings are printed to stderr (and logged)
independently of `logLevel`.

| Exit code | Meaning |
|---|---|
| `0` | success: `--version`, `--check-config` without errors, or the server stopped because stdin was closed |
| `1` | configuration error (also with `--check-config`), missing configuration file, or another startup failure (one line on stderr) |
| `2` | usage error: unknown argument, `--config` without value or given twice, `--check-config` together with `--version` |

## MCP client registration (example: Claude Code)

The MCP server inherits the environment of the MCP client process. With the variables exported in
the shell profile (e.g. `~/.zshrc`), registration needs no secrets:

```bash
claude mcp add inubit -- java -jar /path/to/inubit-mcp-server.jar --config ~/.config/inubit-mcp/config.yaml
```

If the client is started without the shell profile (e.g. a GUI app), start it from a shell that has
the variables. Do **not** pass the values through the MCP client's own config (e.g.
`claude mcp add -e INUBIT_…_PASSWORD=…`), because that stores them in plain text in the client's
configuration file.
