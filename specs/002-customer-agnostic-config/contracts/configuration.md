# Contract: Configuration (format v2)

Supersedes [001 contracts/configuration.md](../../001-inubit-mcp-mvp/contracts/configuration.md).
Everything not mentioned here (block contents such as `write`, `tls`, `cli`, `inventory`, durations,
inheritance node → group → defaults → built-in, validation of URLs, pins, CLIENT on production, owner-only
files, etc.) is unchanged from 001. Field rules: [data-model.md](../data-model.md).

## One file per profile

Each customer/project has its own file and its own MCP client registration. One server process serves
exactly one profile.

### Location (first match wins)

1. `--config <path>`
2. `--profile <name>` → `~/.config/inubit-mcp/<name>.yaml` (Windows: `%APPDATA%\inubit-mcp\<name>.yaml`)
3. environment `INUBIT_MCP_CONFIG` (path)
4. environment `INUBIT_MCP_PROFILE` (name, resolved like `--profile`)
5. `~/.config/inubit-mcp/config.yaml`

- `--config` together with `--profile` → usage error (exit 2); so is `--profile` without a valid
  profile name (the value is not echoed).
- An `INUBIT_MCP_PROFILE` value that is no valid profile name → error (exit 1); it never becomes
  part of a path.
- With `--profile`/`INUBIT_MCP_PROFILE`, the file's `profile.name` MUST equal the given name.
- An explicitly given location that does not exist is an error (no fallback), as in 001.

## Format

```yaml
profile:
  name: acme                       # required, ^[a-z0-9][a-z0-9-]{0,31}$
  description: "ACME – INUBIT integration platform"   # optional, ≤ 200 chars, single line

terminology:                       # optional
  group: { singular: stage,  plural: stages }        # default group / groups
  node:  { singular: server, plural: servers }       # default node / nodes

credentials:                       # optional
  envPrefix: INUBIT_ACME           # default INUBIT_<PROFILE> (normalized)

logLevel: INFO
auditDirectory: ~/.inubit-mcp/acme/audit           # default ~/.inubit-mcp/<profile>/audit
resultLimits: { maxItems: 100, maxChars: 50000 }

defaults:
  timeout: PT5S
  cliTimeout: PT30S
  cliExportTimeout: PT120S
  hangingThreshold: PT60M
  confirmationTtl: PT5M
  inventory:
    owner: INTEGRATION             # NO built-in default (see below)
    cacheTtl: PT10M
  cliHome: /opt/inubit/client
  cliJavaHome: /usr/lib/jvm/temurin-17

x-tls: &tls                        # top-level x-* keys allowed for YAML anchors
  trustStore: ~/.config/inubit-mcp/acme-truststore.p12
  disableHostnameVerification: true
  pinnedCertificateSha256: "AB:CD:…"

groups:
  - name: test
    tls: *tls
    nodes:
      - name: node1
        baseUrl: https://inubit-test-1.example.test:8443
  - name: prod
    production: true
    nodes:
      - name: node1
        baseUrl: https://inubit-prod-1.example.test:8443
```

## Terminology rules

| Rule | Behaviour |
|---|---|
| each of `singular`/`plural` | 1–32 chars, `^\p{L}[\p{L}\p{N} _-]{0,31}$` (letters, digits, space, `_`, `-`; must start with a letter) |
| group vs node | singular names MUST differ case-insensitively; plural names MUST differ case-insensitively |
| omitted (or `null`) | defaults `group`/`groups`, `node`/`nodes` |
| spelling | as inside an English sentence (`stage`, `Umgebung`); inserted exactly, only a sentence start or title upper-cases the first letter |
| given, also empty (`group: {}`) | both `singular` and `plural` required (error names the missing key) |
| usage | display text only: tool and parameter descriptions, messages, `--check-config` summary, `list_nodes` result (`terminology`); never keys or field names |

## Credential environment variables

`<PREFIX>_<GROUP>[_<NODE>]_<KIND>` with `KIND ∈ {USERNAME, PASSWORD, TRUSTSTORE_PASSWORD}`; node-specific
before group-wide (unchanged rule). `PREFIX` = `credentials.envPrefix` (`^[A-Z][A-Z0-9_]{0,63}$`) or
`INUBIT_` + normalize(`profile.name`). Normalize: upper case, non-`[A-Z0-9]` → `_`.

Example (profile `acme`, default prefix): `INUBIT_ACME_TEST_USERNAME`, `INUBIT_ACME_TEST_NODE1_PASSWORD`.
Keeping 001 variables: `credentials.envPrefix: INUBIT` → `INUBIT_TEST_USERNAME` etc.

## Startup validation (additions and changes to 001)

| Situation | Behaviour |
|---|---|
| `profile` or `profile.name` missing | **error** |
| invalid `profile.name`, `description` > 200 characters (code points) or with a control character (`\p{Cc}`, incl. line breaks and tabs) or U+2028/U+2029 | **error** |
| terminology rule violated | **error** (names the field) |
| invalid `credentials.envPrefix` | **error** |
| `--profile X` but `profile.name ≠ X` | **error** |
| 001 format detected (`stages`, `servers`, no `profile`) | **error** listing every obsolete key with its replacement (`stages`→`groups`, `servers`→`nodes`, add `profile`), the prefix hint (`credentials.envPrefix: INUBIT`), the old audit directory and `docs/migration-001-to-002.md` |
| no `inventory.owner` anywhere while a CLI is configured | warning: inventory tools will return `NOT_CONFIGURED` |
| `profile.name: audit` (reserved: its audit directory would lie inside the 001 directory `~/.inubit-mcp/audit`) | **error** |
| a profile file with **another** `profile.name` found in the default configuration directory derives a credential variable name (any node, any kind, node-specific or group-wide) that this profile derives — by nested prefixes (`INUBIT_ACME` + group `2-test` vs. `INUBIT_ACME_2` + group `test`) or by an equal prefix with equal group names | **error** naming the other file, its profile (only if a valid name, else `(invalid profile.name)`) and the shared variable names, never values |
| a profile file with the **same** `profile.name` (a copy) derives shared variable names | warning (the same-name warning below, listing the shared names when the prefixes differ); the copy may start |
| another profile file found in the default configuration directory with the same audit directory, the same effective credential prefix or the same profile name | warning (best effort; one warning per such file, naming it and everything shared) |
| a resolved `*_PASSWORD` variable under the effective prefix matches no configured group/node | warning (unchanged rule, effective prefix); variables derived by another profile file of the default configuration directory are not reported, those of profiles elsewhere may be |

## Command-line arguments

| Argument | Meaning |
|---|---|
| `--config <path>` | configuration file |
| `--profile <name>` | select `<name>.yaml` in the default configuration directory |
| `--check-config` | validate, resolve credential variables, print the summary (profile, terminology, credential variable scheme, audit directory, nodes with display names, variable names; no values); exit 0/1 |
| `--version` | print version; exit 0 |

Exit codes unchanged (0 OK, 1 configuration/startup error, 2 usage error).

## MCP client registration (one per profile)

```bash
claude mcp add inubit-acme -- java -jar /path/to/inubit-mcp-server.jar --profile acme
```
