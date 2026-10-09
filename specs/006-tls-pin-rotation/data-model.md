# Data Model: Confirmed Rotation of Pinned INUBIT Server Certificates

Feature: [spec.md](spec.md) · Contract: [contracts/cli.md](contracts/cli.md)

All entities live in the tool (`tools/inubit-cert-check.py`) or in local files; the MCP server's
data model is unchanged.

## Profile view (read from the profile file, research R-7)

| Field | Source in YAML | Rules |
|---|---|---|
| `profileName` | `profile.name` | required; names the state directory |
| `envPrefix` | `credentials.envPrefix`, else `INUBIT_` + normalize(`profileName`) | used only for `--check-config` placeholders |
| `java`, `serverJar` | `x-cert-check.java`, `x-cert-check.serverJar` (`~` expanded) | required for `--accept`/`--prune`; optional for `--check` (without them the trust-store listing is skipped, see contract C-1); `keytool` = sibling of `java` |
| `stages[]` | `groups[]` (commented-out items do not exist for the reader) | ≥ 1 |

### Stage

| Field | Rules |
|---|---|
| `name` | `groups[].name` |
| `tls` | effective block: server → stage → `defaults` (inheritance of contract 002) |
| `pin` | effective `pinnedCertificateSha256`, normalized to upper-case colon form |
| `pinSource` | `stage` (own inline block of the group) · `alias` (`tls: *x`) · `defaults` · `server` (≥ 1 server overrides) |
| `pinLine` | line number of the `pinnedCertificateSha256:` line when `pinSource = stage`; else none |
| `ownTls` | `pinSource = stage`, `trustStore` set in the stage's own inline `tls` block (not inherited from `defaults`), and that trust store (compared by real path) used by no other stage; required for an offer, `--accept` and `--prune` |
| `trustStore` | effective `tls.trustStore` path |
| `servers[]` | `nodes[]` |

`--accept` and `--prune` require `pinSource = stage` and a trust store used by no other stage
(otherwise exit 3, "shared TLS settings — give the stage its own tls block first").

### Server

| Field | Rules |
|---|---|
| `id` | `<stage>/<node.name>` |
| `baseUrl` | `https://host[:port]`; port default 443; an `http://` server (allowed by the server with `allowInsecureHttp`) has no certificate: it is **skipped** — not contacted, not part of its stage's evaluation, listed under `skippedServers` (reason `no TLS`); any other scheme → configuration error |
| `host`, `port` | from `baseUrl`; `host` is also the SNI name |

## Presented certificate (per server and check run)

| Field | Rules |
|---|---|
| `ip` | the address connected to (resolved once per run) |
| `fingerprint` | SHA-256 of the leaf (first certificate), upper-case colon form |
| `subject`, `issuer` | one-line RFC 2253-like strings from `openssl x509` |
| `notBefore`, `notAfter` | ISO-8601 UTC |
| `validNow` | `notBefore ≤ now ≤ notAfter` |
| `pem` | kept in memory only, for adoption (never logged) |

## Check result

Server status: `ok` (fingerprint = pin) · `changed` (≠ pin) · `unreachable` (timeout, DNS,
connect or TLS failure; `error` names which).

Stage: `status` = worst server status (changed > unreachable > ok); `conflict` = reachable servers
present ≥ 2 different fingerprints; `offer` = common fingerprint if `changed`, no conflict and no
server unreachable and `ownTls` (= `pinSource = stage` and a trust store used by no other stage);
`rejected` = `offer` equals the remembered rejection; `superseded` = trust-store
entries whose fingerprint ≠ `pin` (computed only by `--check` and `--prune`, never on the
start-up path).

Overall exit code: 10 if any server `changed` (conflicts included), else 20 if any `unreachable`,
else 0.

## Remembered rejection (`<state>/cert-rejections.json`)

```json
{ "dev": { "fingerprint": "BB:22:…", "notBefore": "2026-10-08T13:06:26Z",
           "outcome": "rejected", "at": "2026-10-08T15:31:10Z" } }
```

Lifecycle per stage:

```text
(none) ──Reject / timeout──▶ remembered(F)
remembered(F) ──stage presents F again──▶ remembered(F)   (no dialog, warning)
remembered(F) ──stage presents G ≠ F, G ≠ pin──▶ dialog for G (F stays until G is decided)
remembered(F) ──stage presents its pin (ok)──▶ (none)
remembered(F) ──--accept F (cli/claude)──▶ (none)
remembered(F) ──--forget──▶ (none)
```

A dialog that could not be shown never creates an entry.

## Change log entry (`<state>/cert-changes.log`)

One line per decision: `time profile stage outcome old new notBefore by` (format in contract C-6).
Append-only; written with `O_APPEND` under the profile lock.

## Backup

`<file>.bak-YYYYmmdd-HHMMSS` (UTC) next to the profile file and the stage trust store, mode `0600`,
created before the first change of an `--accept` or `--prune --yes`. Never deleted by the tool,
except when the run is refused before any change ("profile changed during adoption"): then the
fresh backups of that run are removed, since nothing was changed.

## Dialog worker status (`<state>/cert-dialog-<pid>.json`)

`{ "pending": ["dev"], "adopting": "dev", "done": { "dev": "adopted" } }` (`adopting` only while an adoption runs) — written atomically by the worker after
every decision, read by the waiting `--interactive` parent, deleted by the worker at the end. It is a
pure status channel. The worker gets profile, state dir, `java` and server JAR on its command line
(the same options as the parent) and the offers to ask about from a separate input file
`<state>/cert-dialog-<pid>.offers.json` (stage, offered fingerprint, pin, per-server id/IP, validity),
written by the parent with mode `0600` and deleted by the worker. The offers file only selects what
to ask; every adoption re-verifies all servers (FR-011), so it cannot cause an adoption by itself.
