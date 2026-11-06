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
5. Restart with `INUBIT_MCP_UPDATE_CHECK=off` in the registration's environment (or
   `updateCheck: {enabled: false}` in the profile): INFO "Update check is off", no `serverUpdate`, no note, the cache file is not touched.
6. Behind a proxy: add `-Djava.net.useSystemProxies=true` (or `-Dhttps.proxyHost=… -Dhttps.proxyPort=…`)
   before `-jar` in the registration; step 1 must work.
7. Without network: start must be as fast as before, tools work, no WARN/ERROR from the check
   (only DEBUG with `logLevel: DEBUG`).
