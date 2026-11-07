# Configuration Delta: INUBIT 9 Support

Delta to the profile format of features 002/009/012/013 and to `--check-config`. Nothing else
changes; a profile without `cliClients` and without 9.x nodes behaves as in 0.8.0 (FR-009).

## `versionLine`

| Value | Meaning |
|---|---|
| `V9_0` (new) | the node runs INUBIT 9.0.x; every capability supported (FR-011); client entry `"9.0"`; address without servlet path |
| `"9.0.7"`, `"9.0.7-HF1"` | concrete 9.0 pin; as `V9_0` |
| `V9_X` | unchanged: some 9.x, minor unknown; reads unverified, writes refused; the version-less client; address without servlet path; `--check-config` warning (below) |
| `AUTO`, `V6_1`, `V8_1`, other concrete versions | unchanged |

Error text for an invalid value: `Invalid versionLine at <path> in <file>: expected AUTO, V6_1,
V8_1, V9_0, V9_X or a full version (major.minor.patch, e.g. 8.1.17)`.

## `defaults.cliClients` (new)

```yaml
defaults:
  cliHome: /opt/inubit-8.1/client        # version-less client, unchanged meaning
  cliJavaHome: /opt/jdk-17
  cliClients:
    "8.1":
      home: /opt/inubit-8.1/client
      javaHome: /opt/jdk-17
    "9.0":
      home: /opt/inubit-9.0/client
      javaHome: /opt/jdk-21
groups:
  - name: test
    versionLine: V9_0
    nodes:
      - name: node1
        baseUrl: https://inubit-test-1.acme.example.test:8443
        # StartCLI: /opt/inubit-9.0/client with /opt/jdk-21 at https://inubit-test-1.acme.example.test:8443
  - name: prod
    versionLine: AUTO
    nodes:
      - name: node1
        baseUrl: https://inubit-prod-1.acme.example.test:8443
        # 8.1 detected: the 8.1 client, …/ibis/servlet/IBISSoapServlet; 9.0 detected: the 9.0 client, no path
```

Windows: the same with `C:\INUBIT\9.0\client` etc.; StartCLI is `bin\startcli.bat` (013).

| Rule | Kind |
|---|---|
| key not `<major>.<minor>` (`"9"`, `"9.x"`, `"V9_0"`, `"9.00"`) | load error `Invalid cliClients key at defaults.cliClients.<key> in <file>: expected major.minor, e.g. "9.0"` |
| duplicate key | load error `Duplicate cliClients key "<key>" at defaults.cliClients in <file>` |
| entry without `home` | load error (missing required field, as for other required fields) |
| unknown field in an entry | load error (as everywhere) |
| `cliClients` in a group or node | load error (unknown field, as today) |
| key the capability matrix does not declare (e.g. `"9.1"`) | `--check-config` warning `defaults.cliClients."9.1": INUBIT 9.1 is not declared supported; nodes detected as 9.1 use this client with unverified reads and refused writes` |

### Choice per node (FR-003, FR-004, research D-3)

Client directory: node `cli.home` → group `cli.home` → `defaults.cliClients."<M.m>".home` of the
effective version → `defaults.cliHome`.

JDK: node `cli.javaHome` → group `cli.javaHome` → the entry's `javaHome` (only if the entry's
`home` was chosen) → `defaults.cliJavaHome` → the server's `JAVA_HOME` (unchanged fallback).

Address: explicit `cli.url` (node → group) → `<baseUrl>/ibis/servlet/IBISSoapServlet` for major
< 9 or unknown → `<baseUrl>` for major ≥ 9.

Effective version: pin, detection, or `get_health` observation (feature 009). `V9_X` and an unknown
version never choose an entry.

## `--check-config`

New warnings (no INUBIT contact; each distinct client probed once with `startcli -v`):

| Condition | Warning |
|---|---|
| entry's client range excludes its key | `defaults.cliClients."9.0": the StartCLI client in <home> supports INUBIT <from> to <to>, which excludes 9.0` |
| entry `home` or `javaHome` missing | `defaults.cliClients."9.0": <home\|javaHome> <path> does not exist` |
| entry `home` exists without `bin/startcli.sh` (`.bat` on Windows), e.g. the INUBIT installation instead of its client directory | `defaults.cliClients."9.0": <home>/bin/startcli.sh not found; CLI-backed capabilities report CLI_UNAVAILABLE on nodes using this entry` |
| no JDK for the entry (entry `javaHome` → `defaults.cliJavaHome` → `JAVA_HOME`); the entry is not probed | `defaults.cliClients."9.0": neither its javaHome, defaults.cliJavaHome nor JAVA_HOME is set; CLI-backed capabilities report CLI_UNAVAILABLE on nodes using this entry without their own cli.javaHome` |
| JDK major ≠ 17 for 8.1 / ≠ 21 for 9.0 (entry or pinned node) | `<subject>: the JDK in <javaHome> is Java <n>; INUBIT 9.0 ships with Java 21` |
| `V9_X` | `versionLine V9_X of <node> does not select 9.0 support; use V9_0 (INUBIT 9.0.x) or AUTO` |
| pinned ≥ 9 with explicit `cli.url` containing `/ibis/servlet/IBISSoapServlet` | `cli.url of <node> uses /ibis/servlet/IBISSoapServlet, which INUBIT 9.0.2 and later removed; remove cli.url or set it to the base URL` |

Node line in the summary (FR-015):

```text
test/node1: … , cli: available, client: /opt/inubit-9.0/client (cliClients "9.0"), address: base URL
prod/node1: … , cli: available, client: /opt/inubit-8.1/client (defaults.cliHome), address: servlet path; chosen at runtime by the detected version
```

Client lines (one per distinct client): `client /opt/inubit-9.0/client (Java 21): CLI 9.0.7
(9.0.0–9.0.10)`; a failed probe is a warning as today. An entry no node uses is probed with
`-v` under a neutral copy of the first node (no trust store, no `cli.url` of its own), so that
no setting of that node can refuse the run.
