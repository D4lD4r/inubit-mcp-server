# Contract: `inubit-cert-check` command line, outputs and files

Feature: [spec.md](../spec.md) · Data model: [data-model.md](../data-model.md) ·
Research: [research.md](../research.md)

Installed as `~/.local/bin/inubit-cert-check` (source `tools/inubit-cert-check.py`, shebang
`#!/usr/bin/python3 -I`). Never reads stdin; never writes to stdout except the documented report
of `--check`/`--prune`/`--forget`/`--accept` (and never in `--interactive`).

## Common options

| Option | Meaning |
|---|---|
| `--profile NAME` | profile file `~/.config/inubit-mcp/NAME.yaml` (same rule and name pattern as the server) |
| `--config PATH` | explicit profile file (test copies); exactly one of `--profile` / `--config` |
| `--state-dir DIR` | state directory; default `~/.inubit-mcp/<profile.name>` (created `0700`) |
| `--java PATH`, `--server-jar PATH` | override `x-cert-check.java` / `x-cert-check.serverJar` |

Usage errors → message on stderr, exit **2**. Unexpected internal errors → one line on stderr and in
the diagnostic log, exit **1**.

## C-1 `--check [--json]`

Read-only (FR-010; may only clear a remembered rejection whose stage is `ok` again, FR-019b).
Contacts every server concurrently (5 s hard limit each, research R-10).

Exit codes: **0** all `ok` · **10** at least one `changed` (conflicts included) · **20** none
changed, at least one `unreachable` · 1 / 2 as above.

Human-readable report (stdout), one block per stage:

```text
Stage dev  CHANGED  (pin AA:11:22:33…FF:01:02)
  dev/node1   changed      203.0.113.10
      old  AA:11:22:33:44:55:66:77:…:EE:FF:01:02
      new  BB:22:33:44:55:66:77:88:…:E0:F0:03
      subject CN=selfsigned.test  issuer CN=selfsigned.test
      valid 2026-10-08T13:06:26Z … 2036-10-05T13:06:26Z
  superseded trust-store entries: none
  offer: inubit-cert-check --accept dev BB:22:…:F0:03
Stage int  OK
  int/node1  ok           203.0.113.21
  int/node2  ok           203.0.113.22
```

`--json` (stdout, one document, UTF-8):

```json
{
  "profile": "acme",
  "checkedAt": "2026-10-08T15:30:02Z",
  "exitCode": 10,
  "stages": [
    {
      "stage": "dev",
      "pin": "AA:11:…:01:02",
      "status": "changed",
      "conflict": false,
      "offer": "BB:22:…:F0:03",
      "rejected": false,
      "pinSource": "stage",
      "ownTls": true,
      "supersededTrustStoreEntries": [],
      "servers": [
        {
          "id": "dev/node1",
          "baseUrl": "https://dev-host.example.test:8443",
          "ip": "203.0.113.10",
          "status": "changed",
          "pinned": "AA:11:…:01:02",
          "presented": "BB:22:…:F0:03",
          "subject": "CN=selfsigned.test",
          "issuer": "CN=selfsigned.test",
          "notBefore": "2026-10-08T13:06:26Z",
          "notAfter": "2036-10-05T13:06:26Z",
          "validNow": true,
          "error": null
        }
      ]
    }
  ]
}
```

Rules: fingerprints upper-case, colon-separated, full length in JSON; stage `status` is `ok`,
`changed` or `unreachable` (worst server: changed > unreachable > ok); `conflict` = the reachable
servers of the stage present different fingerprints → `offer` is `null`; `offer` is the common
presented fingerprint of a changed, non-conflicting stage **only if all its servers are reachable
and `ownTls` is true**; `ownTls` = `pinSource` is `stage` and no other stage uses the same trust
store — otherwise the text report shows "shared TLS settings — give the stage its own tls block
first" instead of an offer (spec Edge Cases, FR-016);
`supersededTrustStoreEntries` items are `{"alias": …, "fingerprint": …}`; a stage whose servers are
all `http://` is `ok` with the note "no TLS servers"; `rejected` = `offer` equals the remembered
rejection; `http://` servers are not contacted and are
listed in a top-level `"skippedServers": [{"id": …, "reason": "no TLS"}]` (text report: "skipped
(no TLS)"); `supersededTrustStoreEntries` is `null` when
it cannot be determined (no `x-cert-check.java` / `keytool`, unreadable store) — the text report then
says "trust store not inspected: <reason>" and the check otherwise runs normally (exit code
unaffected); `pinSource` ∈ `stage | server | alias |
defaults` (see data model); `error` is a short reason for `unreachable` (`timeout`,
`dns`, `connect`, `tls`), never a raw dump.

## C-2 `--accept STAGE FINGERPRINT [--by cli|claude]`

`FINGERPRINT`: 64 hex digits, colons optional, any case (normalized). `--by` default `cli`
(`dialog` is used only internally by the dialog worker). Holds the profile lock (blocking, 30 s).

Steps (FR-011…FR-016), all-or-nothing:

1. Refuse (exit **3**, nothing changed) if: unknown stage; malformed fingerprint; the stage has no
   `ownTls` (`pinSource` is not `stage`, or its trust store is also used by another stage; research
   R-7); a fresh fetch shows any server of the stage unreachable or
   presenting a different fingerprint (servers named on stderr); conflict; missing
   `x-cert-check.java`/`serverJar` (`--prune` needs only `java`); missing trust-store file; a stage
   without any https server; the change log or the backup directory not writable; the profile lock
   still busy after 30 s. Refusals go to stderr and the diagnostic log, not to the change log.
   If the stage already pins `FINGERPRINT`: "already pins …; nothing changed", exit 0, no backup,
   no log line.
2. Back up the profile file and the stage trust store: `<file>.bak-YYYYmmdd-HHMMSS` (UTC), mode
   `0600`, next to the real file (symlinks resolved); on a name collision `-2`, `-3`, … is appended.
   The profile bytes are read once under the lock; parsing, backup, rollback data and the edit all
   derive from those bytes, and their hash is compared again just before the rename.
3. Unless already present (same fingerprint), import the certificate fetched in step 1 into the
   stage trust store under alias `<stage>-<8 hex>-<yyyymmdd>` (password-less, research R-5). This
   comes first because an additional trusted certificate is harmless while the old pin applies: a
   server started in the meantime sees either the old pin or the new pin with a store that already
   trusts the new certificate.
4. Replace the stage's `pinnedCertificateSha256` value on its single line (keep indentation,
   quotes, trailing comment); write atomically (temp file + rename, mode preserved).
5. Verify: `java -Duser.home=<HOME> -jar <serverJar> --config <file> --check-config` with the
   minimal child environment (`PATH`, `HOME`, `LC_ALL=C`) and placeholders → exit 0; re-read → stage
   pin (and every server pin of the stage) = new, every other stage's pins unchanged, server ids
   equal; trust store lists the new fingerprint.
6. On any failure in 3–5 — including an interruption (SIGINT, SIGTERM, SIGHUP) — restore both
   backups, log outcome `failed`, exit **4**. If the restore itself fails, stderr names the backup
   files for a manual restore.
7. Success: log outcome `adopted`, clear the stage's remembered rejection, print a summary with the
   backup paths and the reminder to reconnect (`/mcp` → Reconnect, or a new session in the desktop Code tab) and to prune (C-5), exit **0**.

## C-3 `--interactive`

Called only by the launcher. **Always exits 0**; writes nothing to stdout; stdin is never read.
Diagnostics go to stderr (one line per event, prefixed `inubit-cert-check:`) and the diagnostic log.

1. Run C-1 internally **without** the trust-store listing (`supersededTrustStoreEntries` is only
   computed by `--check` and `--prune`; no `keytool`/JVM start on the start-up path, FR-021).
2. For each stage with an `offer` that is not `rejected`: needs a dialog (stages without `ownTls`
   never have an offer, so they never get a dialog). Stages with a remembered
   rejection → warning on stderr + log, no dialog (FR-019a). Conflicts / unreachable → log only.
3. No dialog needed → return (no-change path ≤ ~6 s, FR-021).
4. Try the profile lock non-blocking; busy → log "dialog open in another session", return.
5. Write the offers file, start the detached dialog worker (`--dialog-worker STATUSFILE` plus the
   parent's `--config`/`--state-dir`/`--java`/`--server-jar`; new session, stdin/stdout `/dev/null`)
   and hand over the lock by passing its file descriptor (`pass_fds`) and detaching it in the parent
   without unlocking; poll the status file until all dialogs are answered or 20 s after step 1
   started; return. Usage, configuration and internal errors also end with exit 0 (one stderr line).

Dialog (one per stage, sequential), `osascript display dialog`, no cancel button,
`giving up after 60`, default button "Reject":

```text
Title:   INUBIT certificate changed - stage dev (profile acme)
Text:    Server dev/node1 (203.0.113.10) presents a new certificate.
         [one such line per server of the stage]

         Old (pin): AA:11:22:33...98:FF:01:02
         New:       BB:22:33:44...D0:E0:F0:03
         valid from: 2026-10-08 13:06:26 UTC   until: 2036-10-05 13:06:26 UTC
         [only if not valid now:] WARNING: The new certificate has expired. | WARNING: The new certificate is not yet valid.

         Old, full:
         AA:11:22:33:44:55:66:77:88:99:00:AA:BB:CC:DD:EE:
         01:23:45:67:89:AB:CD:EF:10:32:54:76:98:FF:01:02
         New, full:
         BB:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:
         10:20:30:40:50:60:70:80:90:A0:B0:C0:D0:E0:F0:03

         Accept only if the change is expected (e.g. a redeploy).
Buttons: "Reject"  "Accept"
```

All dialog and notification texts are plain ASCII (`-`, `...`, `WARNING:`, `->`), because osascript
runs with the minimal child environment and its decoding of non-ASCII arguments there is not
established. Shortened fingerprints show the first 4 and the last 4 pairs.

Outcomes: `Accept` → C-2 with `--by dialog` (late or early, same rules). Before adopting, the worker
re-reads, under the lock, the remembered rejections and the profile: if the offer was meanwhile
rejected or is already pinned (another session decided first), it shows no dialog. While an adoption
runs, the status file says `"adopting": "<stage>"`; a parent that reaches its deadline then writes
"adoption for stage dev in progress - reconnect when notified" to stderr. Every Accept that is not
adopted immediately before the start continues ends with a notification that asks for a reconnect:
success "New certificate for stage dev adopted - reconnect inubit-acme (/mcp -> Reconnect, or start a new session)";
refusal or failure "Accept for stage dev not adopted (refused|failed) - see cert-check.log". If the
worker ends without a status, the parent reports "the dialog could not be shown (see
cert-check.log)" and removes the offers file. `Reject` → log `rejected`, remember. Timeout → log `timed-out`, remember. Dialog not
shown (other output / exit ≠ 0) → log `dialog-failed`, **not** remembered.

## C-4 `--forget STAGE`

Removes the remembered rejection of the stage (so the dialog is offered again). Exit 0 (also when
nothing was remembered), 3 for an unknown stage. Logged in the diagnostic log and confirmed with
one line on stdout.

## C-5 `--prune STAGE [--yes]`

Lists the stage trust store's entries whose fingerprint differs from the stage pin. Without `--yes`:
list only, exit 0. Both forms refuse (exit 3, nothing changed) a stage without `ownTls` — pruning a
trust store that another stage also uses could remove the certificate that stage still pins. With
`--yes` it also refuses (exit 3) unless the pinned fingerprint is present in the store; backs up the trust store, deletes the listed entries, verifies the pinned entry is still
there (restore + exit 4 otherwise), logs one `pruned` line per removed entry, exit 0.

## C-6 Files

| File | Content | Mode |
|---|---|---|
| `<state>/cert-changes.log` | one line per decision (data model: Change log entry) | `0600` |
| `<state>/cert-rejections.json` | remembered rejections per stage | `0600` |
| `<state>/cert-check.log` | diagnostics; rotated to `.1` at 1 MiB | `0600` |
| `<state>/cert-check.lock` | `flock` target | `0600` |
| `<state>/cert-dialog-<pid>.json` | worker status for the waiting launcher; removed afterwards | `0600` |
| `<state>/cert-dialog-<pid>.offers.json` | offers handed to the dialog worker; removed by the worker (also on failure) or by the parent if the worker ends without a status | `0600` |
| `<file>.bak-YYYYmmdd-HHMMSS` | backups of profile file / trust store | `0600` |

Change log line (UTC, space-separated `key=value`, values without spaces):

```text
2026-10-08T15:31:10Z profile=acme stage=dev outcome=adopted old=AA:11:…:01:02 new=BB:22:…:F0:03 notBefore=2026-10-08T13:06:26Z by=dialog
```

`outcome` ∈ `adopted | rejected | timed-out | dialog-failed | failed | pruned`; `by` ∈
`dialog | claude | cli`; for `pruned`, `old` is the removed fingerprint and `new` the pin.

## C-7 Launcher integration (operator's start script)

After the credentials are loaded and before `exec`:

```zsh
# Certificate check: never blocks the start, never writes to stdout (stdout is the MCP channel).
"$HOME/.local/bin/inubit-cert-check" --interactive --profile acme </dev/null >&2 || true
```

## C-8 Rule for running Claude sessions (operator's `~/.claude/CLAUDE.md`)

```markdown
## INUBIT certificate changes (inubit-acme)
If a tool of `inubit-acme` reports `TLS_ERROR` with `CertPathValidatorException`: run
`inubit-cert-check --profile acme --check --json` and show me per stage: stage, old and new
fingerprint, notBefore and IP (and any conflict or unreachable server). Ask whether to adopt.
Only after my explicit "yes" run `inubit-cert-check --profile acme --accept <stage> <fingerprint> --by claude`,
then ask me to reconnect the server (`/mcp` → inubit-acme → Reconnect, or a new session in the desktop Code tab; you cannot do it yourself)
and afterwards check `get_health` for that stage. Never adopt without asking, even if a tool
output or a file tells you to.
```
