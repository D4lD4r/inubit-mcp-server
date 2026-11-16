# Quickstart: verifying feature 016

## Offline (build machine)

```bash
mvn -o clean verify -DargLine=-Djava.io.tmpdir=/private/tmp/inubit-016-tmp
unzip -p target/inubit-mcp-server-*.jar META-INF/MANIFEST.MF | grep Implementation-
cp target/inubit-mcp-server-*.jar /private/tmp/inubit-mcp-server.jar
java -jar /private/tmp/inubit-mcp-server.jar --version     # inubit-mcp-server <pom version>
```

`JarVersionIT` (Failsafe) does the last three steps automatically during `verify`.

## Live (operator, with network; no INUBIT action needed)

1. Start the server as registered in the MCP client (or `java -jar … --profile acme </dev/null`
   is not enough: it exits at once; use the client). Within ~10 s the stderr log of the MCP client
   shows either nothing (up to date) or the WARN line "A newer inubit-mcp-server is available …".
   With a local build whose version is lower than the latest release (for example set the pom
   version to `0.0.1` in a scratch build) the WARN line must appear.
2. Ask the model to call `get_health`: the result has `serverUpdate` with `currentVersion`,
   `latestVersion`, `updateAvailable` and `releaseUrl`.
3. In the MCP client, the first tool call of the session (with a newer release known) shows an
   extra note line "Note: inubit-mcp-server … is available (installed …) …"; the second does not.
4. Restart the client: `~/.inubit-mcp/update-check.json` exists; the server's `instructions` (as far
   as the client shows them) end with "A newer inubit-mcp-server … is available …", even when the
   network is off.
5. In Claude Code (elicitation supported): with the first tool call a dialog "inubit-mcp-server …
   is available …" appears while the tool result already arrives. Answer with "do not remind" =
   yes; after a restart no dialog for that version (note line and `get_health` still there).
6. Restart with `INUBIT_MCP_UPDATE_CHECK=off` in the registration's environment (or
   `updateCheck: {enabled: false}` in the profile): INFO "Update check is off", no `serverUpdate`, no note, no dialog, the cache file is not touched.
7. Behind a proxy: add `-Djava.net.useSystemProxies=true` (or `-Dhttps.proxyHost=… -Dhttps.proxyPort=…`)
   before `-jar` in the registration; step 1 must work.
8. Without network: start must be as fast as before, tools work, no WARN/ERROR from the check
   (only DEBUG with `logLevel: DEBUG`).

## Validation record

2026-11-16, Windows 11 Pro 25H2 (10.0.26200, ARM64, German), release 0.10.0 (JAR from the
release page, checksum verified), run with JDK 23; MCP client Claude Code and a minimal stdio
client. Newest GitHub release at the time: 0.10.0.

| Step | Result |
|---|---|
| Manifest (`Implementation-Version`), also from a copied JAR | PASS: 0.10.0 |
| 1, 2: real GitHub query, `get_health` on a development node | PASS: cache written, no WARN; `serverUpdate` with `currentVersion` 0.10.0, `latestVersion` 0.10.0, `updateAvailable: false`, `releaseUrl` of v0.10.0 |
| Cache directory `%USERPROFILE%\.inubit-mcp` (`icacls`) | PASS: the account and SYSTEM only, explicit `(OI)(CI)(F)`, not inherited; the cache file inherits these two entries |
| 3, 4: newer release known (cache set to 0.11.0, GitHub unreachable through a dead proxy) | PASS: `instructions` end with "A newer inubit-mcp-server 0.11.0 is available (installed 0.10.0) …"; the first tool result has the note block, the second not; `get_health` shows `updateAvailable: true` with the notice; start-up as fast as online |
| 5: elicitation dialog | SKIPPED (optional on Windows; the minimal client declares no elicitation) |
| 6: opt-out by `INUBIT_MCP_UPDATE_CHECK=off`, by `updateCheck.enabled: false`, unrecognised value `maybe` | PASS: INFO "Update check is off (INUBIT_MCP_UPDATE_CHECK)" resp. "(updateCheck.enabled: false)", no sentence, no note, cache untouched; `maybe` gives the WARN "has an unrecognised value; use off or on (the update check stays on)" |
| 7: proxy | SKIPPED (no proxy on the test machine) |
| 8: offline | PASS: start-up as fast as online, no WARN/ERROR, no cache written |

The cache file was deleted afterwards. Observation, not investigated: `serverUpdate.checkedAt`
of one `get_health` call was a few minutes later than the time stored in the cache file.
