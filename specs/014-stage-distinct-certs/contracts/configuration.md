# Contract: `tls.pinPerServer` in the profile configuration

Feature: [spec.md](../spec.md) · Data model: [data-model.md](../data-model.md) · Research:
[research.md](../research.md) R-1 … R-5, R-15

Extends the configuration contract of feature 002
([002 configuration](../../002-customer-agnostic-config/contracts/configuration.md)). Everything not
mentioned here is unchanged.

## Key

| Key | Where | Type | Default | Meaning |
|---|---|---|---|---|
| `tls.pinPerServer` | `groups[].tls` only | boolean | `false` | The servers of this stage may present different certificates; each https server is pinned to its own. |

## Example (customer-agnostic)

```yaml
profile:
  name: acme

groups:
  - name: dev                        # uniform stage (feature 006): one pin for the stage
    tls:
      trustStore: ~/.config/inubit-mcp/acme-dev-truststore.p12
      pinnedCertificateSha256: "AA:11:…:01:02"
      disableHostnameVerification: true
    nodes:
      - name: node1
        baseUrl: https://dev-host.example.test:8443

  - name: int                        # marked stage: one pin per server
    tls:
      pinPerServer: true
      trustStore: ~/.config/inubit-mcp/acme-int-truststore.p12   # holds both certificates
      disableHostnameVerification: true                          # inherited by both servers
    nodes:
      - name: a
        baseUrl: https://int-a.example.test:8443
        tls:
          pinnedCertificateSha256: "BB:22:…:F0:03"
      - name: b
        baseUrl: https://int-b.example.test:8443
        tls:
          pinnedCertificateSha256: "CC:33:…:0E:04"
```

The marked stage sets neither `cli.url` nor `e2e.soap.baseUrl` on the stage: each server either
uses the default StartCLI address derived from its own `baseUrl`, or sets `cli.url` and
`e2e.soap.baseUrl` in its own node entry (V-10, V-11).

## Validation (`--check-config` and start-up; errors stop the start)

Messages use the profile's terminology (`{group}`, `{node}`) like every other configuration
message. Each one names the stage and, where it applies, the server.

| # | Configuration | Result |
|---|---|---|
| V-1 | `pinPerServer` on a node's `tls` (inline or through an alias) | error `int/b: tls.pinPerServer is only valid on a {group}` |
| V-2 | `defaults.tls` (with or without `pinPerServer`) | error `Unknown key 'tls'` (existing behaviour, research R-2) |
| V-3 | marked stage with `pinnedCertificateSha256` in its `tls` (inline or alias) | error `{group} 'int': tls.pinnedCertificateSha256 is not allowed with tls.pinPerServer; set it on each {node}` |
| V-4 | marked stage without `tls.trustStore` | error `{group} 'int': tls.pinPerServer needs tls.trustStore on the {group}` |
| V-5 | node of a marked stage with `tls.trustStore` | error `int/b: tls.trustStore is not allowed on a {node} of a {group} with tls.pinPerServer; set it on the {group}` |
| V-6 | https node of a marked stage without its own `tls.pinnedCertificateSha256` | error `int/b: tls.pinPerServer on {group} 'int' needs tls.pinnedCertificateSha256 on this {node}` |
| V-7 | http node (`allowInsecureHttp`) of a marked stage without a pin | valid |
| V-8 | node pin reached through an alias (`tls: *x-int-b`) | valid (the tool offers no adoption for it) |
| V-9 | `pinPerServer: false` or absent | uniform stage; every rule of features 001/002/006 unchanged |
| V-10 | marked stage with more than one node, `cli.url` on the stage, a node without its own `cli.url` | error per inheriting node `int/b: cli.url is inherited from {group} 'int' with tls.pinPerServer; set cli.url on each {node}` |
| V-11 | marked stage with more than one node, `e2e.soap.baseUrl` on the stage, a node without its own `e2e.soap.baseUrl` | error per inheriting node `int/b: e2e.soap.baseUrl is inherited from {group} 'int' with tls.pinPerServer; set e2e.soap.baseUrl on each {node}` |

The existing per-server checks still apply to the effective values: pin format, the trust-store
file exists, and `disableHostnameVerification` requires a pin. The existing warning for a
stage-level `cli.url` with several nodes stays for every stage; V-10 and V-11 add errors for marked
stages only (research R-3). A marked stage with one node may set both addresses on the stage.

## Runtime behaviour

- **REST and SOAP**: each server of a marked stage uses the stage's trust store and only its own
  pin. A server that presents another server's certificate fails the pin check (`TLS_ERROR`, cause
  `CertificateException`), exactly like any other pin mismatch. These adapters are unchanged
  (research R-4).
- **StartCLI** (FR-005a, research R-15): before every StartCLI call that logs in to a server of a
  marked stage, the CLI adapter opens one TLS handshake to the host and port of that server's
  effective `cli.url` (default: `baseUrl` + `/ibis/servlet/IBISSoapServlet`) through the same trust
  store, pin and host-name rule as the REST client. On a failure the call is refused and StartCLI
  is not started:

  | Probe result | Error code | Message (example) |
  |---|---|---|
  | pin mismatch | `TLS_ERROR` | `The TLS connection to int/a failed for the StartCLI address int-a.example.test:8443 (CertificateException); StartCLI was not started` |
  | other TLS failure | `TLS_ERROR` | same form, with the root cause's class name |
  | timeout | `TIMEOUT` | as the REST path reports it, naming the StartCLI address |
  | unreachable | `UNREACHABLE` | `Cannot connect to int/a for the StartCLI address int-a.example.test:8443 (ConnectException); StartCLI was not started` |

  Likely cause and next step are the texts of the REST path for the same code. An `http`
  `cli.url` is not probed. Servers of unmarked stages are never probed.
- **Remaining window**: StartCLI's own connection follows the probe by about the start time of its
  JVM; a certificate switch exactly in that window is not caught, because StartCLI checks only the
  stage's trust store (and the host name, unless disabled). Certificates whose subjectAltNames
  match the host, with host-name verification on, close it (research R-15, `docs/setup.md`).
- At start, when at least one stage is marked, the server logs one line (stderr and the log file,
  never stdout):

  ```text
  Stages with one pinned certificate per server (tls.pinPerServer): int
  ```
