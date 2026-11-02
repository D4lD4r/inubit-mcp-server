# Configuration Delta: Read-Only INUBIT 6.1

Delta to the profile format of features 002/009 and to `--check-config`. Nothing else changes.

## `versionLine`

| Value | Meaning |
|---|---|
| `V6_1` (new) | the node runs INUBIT 6.1; reached through StartCLI only; read-only |
| `"6.1.0"`, `"6.1.0.89"`, `"6.1.0.89-HF1"` | concrete 6.1 pin; major.minor.patch = 6.1.0; a fourth part and a suffix are kept for display only |
| `AUTO`, `V8_1`, `V9_X`, concrete 8.1/9.x | unchanged; `AUTO` never selects 6.1 |

Example:

```yaml
groups:
  - name: legacy
    versionLine: V6_1
    nodes:
      - name: node1
        baseUrl: https://inubit-legacy-1.example.test:8443   # gives the StartCLI URL
        cliHome: /opt/inubit-6.1/client                        # 6.1 client (or the 8.1 client)
        cliJavaHome: /usr/lib/jvm/temurin-8
        tls:
          trustStore: ~/.inubit-mcp/acme/legacy-truststore.jks   # password-less
          pinnedCertificateSha256: "AB:CD:…"
```

Error text for an invalid value:
`Invalid versionLine at groups[0].versionLine in <file>: expected AUTO, V6_1, V8_1, V9_X or a full
version such as "8.1.17"`.

## Validation (`--check-config` and startup)

| Case | Finding |
|---|---|
| 6.1 node without `cliHome` | warning (new): `<id>: INUBIT 6.1 is reached through StartCLI only, but no cliHome is configured; its capabilities report CLI_UNAVAILABLE` |
| 6.1 node whose `cliHome` has no `bin/startcli.sh` | warning (existing text); the node answers `CLI_UNAVAILABLE` |
| 8.1/9.x node without `cliHome` | no finding (unchanged) |
| 6.1 node, `disableHostnameVerification: true` with pin, client without host-name option (or options unknown) | warning: `<id>: the StartCLI client offers no option to skip host-name verification; StartCLI-backed tools on this node are refused (TLS_ERROR)` |
| 6.1 node, PKCS12 trust store, client runtime below Java 8 or unreadable | warning: `<id>: the StartCLI runtime (Java <n> / unknown) cannot read the PKCS12 trust store; convert it to JKS or set cliJavaHome` |
| 6.1 node whose client has no trust-store option, trust-store path with whitespace | warning: `<id>: the trust-store path contains spaces, which the StartCLI runtime settings cannot carry; use a path without spaces` |
| trust-store password on a node with a configured StartCLI | error (existing rule, unchanged) |
| `http://` StartCLI URL without `allowInsecureHttp` | error (existing rule, applied to the effective StartCLI URL) |

## `--check-config` node line

```text
legacy/node1: read-only, cli: available, inubit: V6_1 (INUBIT 6.1, StartCLI only;
  available: monitoring, process diagnosis; refused: log diagnosis, inventory, workspace export,
  process control, development writes, deployment, end-to-end test),
  client: 6.1.0 (4.0.1–6.1.0; options: no trust-store option, no host-name option)
```

Client options are shown for 6.1 nodes only; 8.1/9.x node lines are unchanged apart from the
capability names. Before the recordings are in the repository the "available" list is empty and
every capability is listed as refused (FR-005).
