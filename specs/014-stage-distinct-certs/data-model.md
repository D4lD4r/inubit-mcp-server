# Data Model: Stages Whose Servers Present Different Certificates

Feature: [spec.md](spec.md) · Research: [research.md](research.md) · Contracts:
[configuration.md](contracts/configuration.md), [cli.md](contracts/cli.md)

This feature extends the data model of feature 006
([006 data-model](../006-tls-pin-rotation/data-model.md)); only the differences are listed here.

## Configuration (server, `src/main/java/.../config`)

### `TlsConfig` (bound on groups and nodes; `defaults` has no `tls`, research R-2)

| Field | Type | Rules |
|---|---|---|
| `trustStore` | `Optional<Path>` | unchanged |
| `disableHostnameVerification` | `Optional<Boolean>` | unchanged |
| `pinnedCertificateSha256` | `Optional<String>` | unchanged |
| `pinPerServer` | `Optional<Boolean>` | **new**; meaningful only on a group; `true` marks the stage |

A stage is **marked** when `group.tls().pinPerServer()` is `Optional.of(true)`. `false` or absent
→ uniform stage (feature 006).

### Validation rules of a marked stage (FR-004, research R-3)

Checked on the values as written (before inheritance). Jackson has already resolved aliases.

| Where | Rule |
|---|---|
| any node | `tls.pinPerServer` absent |
| marked group | `tls.pinnedCertificateSha256` absent |
| marked group | `tls.trustStore` present (and the file exists: existing `checkTls`) |
| node of a marked group | `tls.trustStore` absent |
| node of a marked group with an https `baseUrl`, effective `cli.url` or effective `e2e.soap.baseUrl` | `tls.pinnedCertificateSha256` present (format: existing `checkTls`) (V-6) |
| node of a marked group whose addresses are all http | no TLS rule (allowed with `allowInsecureHttp`, as today) |
| node of a marked group with more than one node | `cli.url` set on the node, or not set on the group (V-10) |
| node of a marked group with more than one node | `e2e.soap.baseUrl` set on the node, or not set on the group (V-11) |

### Effective server TLS (research R-4, R-15)

`EffectiveNodeConfig.Tls` = node value, else group value. In a marked stage this yields
`trustStore` from the group and `pinnedCertificateSha256` from the node, so each server's
`PinningTrustManager` holds exactly its own pin.

| Field | Rule |
|---|---|
| `trustStore`, `disableHostnameVerification`, `pinnedCertificateSha256` | unchanged |
| `pinPerServer` | **new** `boolean`: the group's `tls.pinPerServer`, default `false` (a node value is invalid, V-1). A constructor with the three old arguments sets `false`, so existing call sites stay as they are |

### StartCLI pin probe (server, `adapter/cli`, FR-005a, research R-15)

| Item | Rule |
|---|---|
| type | interface `StartCliPinProbe` (`check(server)`), real implementation `HandshakeStartCliPinProbe`; `CliRunner`'s four-argument constructor uses the real one, a five-argument constructor takes any probe (tests) |
| when | in `CliRunner.run`, **before** `guard.acquire()` (no network contact inside the credential permit), before StartCLI is launched; only when `server.tls().pinPerServer()` is true; never in `CliRunner.execute` (`-v`) |
| address | host and port of `server.cli().url()` (port `-1` → 443); `http` → no probe |
| TLS | `SslContexts.forServer(server.secretLabels(), server.tls(), Optional.empty())` — a node with a CLI never has a trust-store password (`ConfigValidator`, `NodeSecrets`, research R-15), so no `run` overload and no caller change; SNI = host; endpoint identification `HTTPS` unless `disableHostnameVerification`; connect and read timeout `server.timeout()` |
| result | handshake completes → StartCLI runs as before; otherwise `ToolErrorException` with `TIMEOUT` (a `SocketTimeoutException` anywhere in the cause chain, checked before `SSLException`) / `TLS_ERROR` / `UNREACHABLE`, the message naming the server, the StartCLI address and "StartCLI was not started"; `TLS_ERROR` uses the REST likely-cause and next-step texts, the `UNREACHABLE` next step names the effective `cli.url`; the guard is not touched (no permit taken) |

## Profile view (tool, extends 006 "Profile view")

### Stage

| Field | Rules |
|---|---|
| `pinPerServer` | **new**; `true` if the stage's effective `tls` block (inline or alias) has `pinPerServer: true` |
| `pin` | uniform stage: as in 006. Marked stage: `None` (no stage-wide expected fingerprint) |
| `pinSource` | uniform stage: as in 006. Marked stage: `server` |
| `ownTls` | uniform stage: as in 006. Marked stage: `trustStore` written in the stage's own inline `tls` block, and that file (real path) used by no other stage; no pin condition |
| `expected` | **new**, marked stage only: the set of the own pins of its servers — every https server, and every http server with its own pin (V-6 requires it when the server's `cli.url` or `e2e.soap.baseUrl` is https; research R-13) |
| `problem` | **new**, marked stage only: `None`, or the first broken rule of the table above, as a short text (research R-7); an unreadable `cli`/`e2e` shape of the stage or a server comes first ("int/b: cannot read cli.url here (…)") |

### Server

| Field | Rules |
|---|---|
| `pin` | effective pin (unchanged rule) |
| `pinSource` | **new**: `server` (the server's own inline `tls` block has the pin) · `alias` (the server's `tls` is an alias) · `stage` (inherited) |
| `pinLine` | **new**: line of `pinnedCertificateSha256` when `pinSource = server`, else none |

## Check result (extends 006 "Check result")

### Server result in a marked stage

| Field | Rules |
|---|---|
| `status` | `ok` (presented = own pin) · `changed` · `unreachable` (unchanged definitions) |
| `offer` | presented fingerprint, when `changed`, `pinSource = server`, stage `ownTls`, stage `problem` is none; else `None` (a `changed` server is always reachable: 006 `server_status` returns `unreachable` first) |
| `noOfferReason` | when `changed` but no offer: `pin through alias` · `trust store not the stage's own` · `configuration problem`; else `None` (JSON `null`), also for an `unreachable` server |
| `expectedBy` | ids of the *other* servers of the stage whose pin equals the presented fingerprint (FR-010a); empty list if none |
| `rejected` | `offer` equals the remembered rejection of this server id |

### Stage result in a marked stage

`status` = worst server status (changed > unreachable > ok); `conflict` = `false`; `offer` =
`None`; `rejected` = `None` (JSON `null`, spec FR-007); `superseded` = trust-store entries whose fingerprint ∉ `expected`
(only by `--check` and `--prune`, as in 006).

Overall exit code: unchanged (10 if any server `changed`, else 20 if any `unreachable`, else 0).

## Remembered rejection (`<state>/cert-rejections.json`)

Shape unchanged: a map from key to entry. Key = stage name (uniform stage) or server id
(marked stage).

```json
{
  "dev":   { "fingerprint": "BB:22:…", "notBefore": "…", "outcome": "rejected", "at": "…" },
  "int/b": { "fingerprint": "CC:33:…", "notBefore": "…", "outcome": "timed-out", "at": "…" }
}
```

Lifecycle per server of a marked stage:

```text
(none) ──Reject / timeout for int/b──▶ remembered(F)
remembered(F) ──int/b presents F again──▶ remembered(F)   (no dialog for int/b, warning)
remembered(F) ──int/b presents G ≠ F, G ≠ own pin──▶ dialog for G (F stays until G is decided)
remembered(F) ──int/b presents its pin (ok)──▶ (none)
remembered(F) ──--accept int/b F (cli/claude)──▶ (none)
remembered(F) ──--forget int/b  or  --forget int──▶ (none)
```

Migration: an entry under the stage name of a marked stage (written by 006 before the stage was
marked) is removed by the next `--check` or `--interactive` and never suppresses a dialog.

## Change log entry (`<state>/cert-changes.log`)

| Decision | Fields |
|---|---|
| uniform stage (all outcomes) | unchanged 006 format |
| server of a marked stage (`adopted`, `rejected`, `timed-out`, `dialog-failed`, `failed`) | 006 fields with `server=<id>` inserted after `stage=` |
| `pruned` in a marked stage | 006 format; `new=-` (no single stage pin) |

## Dialog worker files

Offers file: each offer gains `target` (server id or stage name), `server` (id or `null`) and
`expectedBy`. Status file: `pending`, `adopting` and `done` are keyed by `target` instead of the
stage name. For uniform stages target = stage name, so their content does not change.

## Backup

Unchanged (006): the profile file and the stage trust store, before the first change of an
`--accept` or `--prune --yes`.
