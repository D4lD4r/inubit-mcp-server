# Live test of release 0.8.0

Release 0.8.0 joins features 012 (INUBIT 6.1 reads over StartCLI), 013 (StartCLI on Windows),
016 (update check, `Implementation-Version` in the manifest) and 017 (INUBIT 9.0). It was released
without a live check; the maintainer accepted that risk (CHANGELOG, "Known limitations / not
live-verified"). The JAR is `inubit-mcp-server-0.8.0.jar` from the GitHub release (check it
against its `.sha256` file first).

This list joins the open live tasks of the four features. The details stay in the features'
specs; each item names where. Per item:

- **Platform**: macOS, Windows or both.
- **Approval**: what the person has to approve in the chat before the step runs. "INUBIT
  read-only" means a read against a configured node; "INUBIT write" means a change on a node.
  Every INUBIT step needs that approval, and for a node classified production also the
  confirmation of node and classification. No step may run against production unless the person
  says so for that step.

Use only `acme`-style names and `*.example.test` hosts in anything committed; recordings are
neutralized with `tools/neutralize.py` and checked by `NoCustomerIdentifiersTest` (it must run,
not be skipped).

## 0. Install the build (both)

- [ ] Copy the JAR and the `.sha256` to the machine; `shasum -a 256 -c` (macOS) or
  `Get-FileHash -Algorithm SHA256` (Windows) matches.
- [ ] Point the MCP registration (or `serverJar` of the profile for `inubit-cert-check`) at the
  JAR; restart the MCP client.
- [ ] `java -jar inubit-mcp-server-0.8.0.jar --check-config --profile <profile>`: no error;
  8.1 nodes unchanged. Approval: none (local only; the StartCLI client check runs `-v`/`-h`
  locally, without credentials or server URL).

## 1. Feature 016: update check and manifest

No INUBIT action. The check sends one anonymous request to `api.github.com` (User-Agent with the
server version); approve that the machine may reach GitHub. Run on **both** platforms where
noted: the cache directory is created differently (POSIX owner-only vs. inherited ACL). Steps are
those of `specs/016-update-check/quickstart.md` ("Live").

This build is the latest release v0.8.0, so the real check must report **no** update. To see the notices, use the cached-result step below.

- [ ] **Manifest** (both; approval: none):
  `unzip -p inubit-mcp-server-0.8.0.jar META-INF/MANIFEST.MF | grep Implementation-`
  shows `Implementation-Version: 0.8.0`; `java -jar … --version` prints
  `inubit-mcp-server 0.8.0`, also when the JAR was copied to another directory.
- [ ] **Real GitHub query** (both; approval: GitHub access): start through the MCP client. Within
  about 10 s no WARN "A newer inubit-mcp-server is available" in the client's server log;
  `get_health` has `serverUpdate` with `currentVersion` `0.8.0`, `latestVersion` `0.8.0`,
  `updateAvailable: false`; no note line in the first tool result; no dialog.
- [ ] **Cache** (both; approval: none): `~/.inubit-mcp/update-check.json`
  (`%USERPROFILE%\.inubit-mcp\update-check.json`) exists with `latestVersion` `0.8.0` and
  `checkedAt`; macOS: a directory the server created is `700` (`ls -ld`), the file is not
  readable by others; Windows: the directory inherits the ACL of the user profile (`icacls`
  shows no entry beyond the user, SYSTEM and Administrators).
- [ ] **Notices with a newer release** (both; approval: none, network off): with the client
  stopped, write `{"latestVersion":"0.9.0","checkedAt":"<now, ISO-8601 UTC>"}` to the cache
  file, switch the network off (the start-up check then fails and leaves the cached result) and
  start the client. Expected: the server `instructions` (as far as the client shows them) end
  with "A newer inubit-mcp-server 0.9.0 is available (installed 0.8.0) …"; the first tool
  result has the note line "Note: inubit-mcp-server 0.9.0 is available …", the second has not;
  `get_health.serverUpdate.updateAvailable` is `true`; tools work and start-up is not slower.
  Alternative from the quickstart: a scratch build with pom version `0.0.1` against the real
  check. Delete the cache file afterwards.
- [ ] **Elicitation dialog in Claude Code** (macOS, Windows optional; approval: none): in the
  "notices" setup, the first tool call shows the dialog "inubit-mcp-server 0.9.0 is available …"
  while the tool result already arrives. Tick "Do not remind me of this version again": after a
  restart no dialog for 0.9.0 (note line and `get_health` still there), `dismissedVersion` is in
  the cache. Closing or declining instead changes nothing. Delete the cache file afterwards.
- [ ] **Opt-out** (both; approval: none): restart with `INUBIT_MCP_UPDATE_CHECK=off` in the
  registration's environment, then with `updateCheck: {enabled: false}` in the profile: INFO
  "Update check is off (…)" naming the source; no `serverUpdate`, no note, no dialog; the cache
  file is neither read nor written (timestamp unchanged). An unknown value such as `maybe` warns
  and keeps the check on.
- [ ] **Proxy** (where a proxy exists, usually Windows in the company network; approval: GitHub
  access through the proxy): add `-Djava.net.useSystemProxies=true` or
  `-Dhttps.proxyHost=… -Dhttps.proxyPort=…` before `-jar`; the real query works (cache
  `checkedAt` updated). INUBIT connections are unaffected. A proxy with authentication is not
  supported: expect only a DEBUG "Update check failed" and switch the check off there.
- [ ] **Offline** (both; approval: none): without network the start is as fast as before, tools
  work, no WARN/ERROR from the check (DEBUG only with `logLevel: DEBUG`).

## 2. Feature 013: StartCLI on Windows (T031–T033)

Needs a Windows machine with the INUBIT 8.1 client and a **non-production** 8.1 node. Steps from
`specs/013-windows-startcli/quickstart.md` ("Recording on Windows", "Live check on Windows"),
tasks T031–T033 in `specs/013-windows-startcli/tasks.md`. Record per step: Windows version,
client version, code page (`chcp`), outcome kind (no customer data), in the quickstart's
"Validation record".

- [ ] **`--check-config`** (Windows; approval: none): node line with `cli: available` and the
  client version; no Windows warning.
- [ ] **Password on stdin** (Windows; approval: INUBIT read-only): the first StartCLI-backed read
  (e.g. `list_inventory(kind=MODULE)`) logs in. **If the client does not read the password from
  redirected stdin: stop and ask the person** (FR-003); do not work around it.
- [ ] **Read-only tools** (Windows; approval: INUBIT read-only): `list_inventory` with modules
  (non-ASCII names correct), `get_inventory_item` with history, `export_artifacts` of a test
  diagram group; no temporary export data left afterwards.
- [ ] **Recordings with `tools/record-fixtures.ps1`** (Windows; approval: INUBIT read-only, the
  non-production group and host in `RECORD_ALLOWED_GROUPS`/`RECORD_ALLOWED_HOSTS`): cases
  `win_version`, `win_help`, `win_failure`, `win_argv` (needs a JDK), then `win_modules`,
  `win_backslash` with trust store and URL; neutralize (local map), files under
  `src/test/resources/fixtures/windows/`; `NoCustomerIdentifiersTest` runs (20 tests, 0 skipped).
  Record the unknowns: password from stdin, output encoding, `C:/` paths, `\` in a quoted value,
  argument forwarding, the length `startcli.bat` adds (R-4 margin).
- [ ] **Code pages / SC-005** (Windows recording, then any platform; approval: none after the
  recording): note the console code page of the recording (850, 1252 or 65001). T032 (code work,
  not live): `WindowsRecordingsTest` parses the recordings transcoded into 850, 1252 and 65001
  and compares them with the macOS results. If separator, `\` handling, encoding or the R-4
  margin differ from the defaults: follow-up task for spec, research R-3/R-4/R-6 and code.
- [ ] **ACLs** (Windows; approval: INUBIT write for the backup case): `icacls` of an exported file
  and of a backup shows owner + SYSTEM (inherited, no other entries); the private temporary
  directory is gone after the call.
- [ ] **Opt-in JUnit live tests** (Windows; approval: INUBIT read-only, see `docs/live-tests.md`):
  `InventoryLiveTest`, `ArtifactExportLiveTest`, `CredentialStoreLiveTest` (Windows Credential
  Manager).
- [ ] **Write live tests** (Windows; approval: INUBIT write, development node or approved deploy
  target only): `DevelopmentLiveTest` (`import_artifacts` preview and execution,
  `restore_backup`, `set_active`, `tag_artifacts`), `DeploymentLiveTest`.
- [ ] **T033** (macOS + Windows CI; approval: none): after T031/T032 the SC-005 row and the live
  record in the quickstart; full `mvn -q -o clean verify` on macOS and the Windows CI job green.

## 3. Feature 012: INUBIT 6.1 over StartCLI (T036–T040, T043–T045)

Needs an INUBIT 6.1 node, a StartCLI client whose range includes 6.1 (6.1 client with its Java
runtime, or the 8.1.17 client), a password-less JKS trust store and the pin of the node's
certificate. Steps from `specs/012-inubit-61-cli-reads/quickstart.md`.

**Before the flip (T040) every 6.1 tool that would contact the node is refused**: only
`list_nodes` and the offline checks of `check_artifacts` run; `get_health` reports the 6.1 node
with `UNSUPPORTED_VERSION`, `find_processes` and `query_logs` are not offered in a profile of 6.1
nodes only. So in this build the live part of 012 is the configuration, the refusals and the
recordings; health and process listing on 6.1 come after T037–T040.

- [ ] **Configuration** (both; approval: none): group with `versionLine: V6_1`, `cli.home`,
  `cli.javaHome`, `tls.trustStore` (JKS, no password), `tls.pinnedCertificateSha256`.
  `--check-config` shows the node as `INUBIT 6.1, StartCLI only; available: …; refused: …`; the
  client options of `startcli -h` (trust store, host-name option, command option) and the Java
  version of `cli.javaHome` are reported; a missing host-name option with
  `disableHostnameVerification: true` is a warning. On Windows the same with `startcli.bat`.
- [ ] **Refusal without contact** (both; approval: none, nothing reaches the node): `list_nodes`
  shows `access: cli_only`; `get_health` and every other tool on the node give
  `UNSUPPORTED_VERSION` before any launch, TLS check or REST request (no StartCLI process, no
  connection in a packet log or the node's access log, if available).
- [ ] **Pin / TLS pre-check** (both; approval: INUBIT read-only, a TLS handshake only, no login):
  only after T040, when a session can run; before the flip only the `--check-config` part above.
  Then: the server's own handshake against `<baseUrl>/ibis/servlet/IBISSoapServlet` precedes
  StartCLI; a wrong pin gives `TLS_ERROR` and no launch; a trust store that does not contain the
  certificate gives `TLS_ERROR`. On Windows a username with `%`, `|`, `"` … or a non-ASCII
  password is refused with `AUTH_FAILED` before this handshake (feature 013 T028).
- [ ] **T036 recordings** (macOS, `tools/record-fixtures.sh`; approval: INUBIT read-only, the 6.1
  node; for production also node and classification): cases `v61_help`, `v61_version`,
  `v61_health_session`, `v61_ps_all`, `v61_ps_error`, `v61_ps_waiting`, `v61_ps_queued`,
  `v61_ps_limit5` with `RECORD_ALLOWED_GROUPS=<group>`; neutralize, `NoCustomerIdentifiersTest`
  runs; answer the REC questions of research D-5, D-6, D-10 (stdin mode, `uname` = server
  version, meaning of `DATE`, Processing rows in `ps -a`, rows dropped at `-l`, German labels).
- [ ] **T037–T039** (code work, not live; approval: none): recorded health and `ps` fixtures
  replace the reconstructed ones; the REC outcomes of D-10 applied.
- [ ] **T040 flip** (code work; approval: none): `MONITORING` and `PROCESS_DIAGNOSIS` `SUPPORTED`
  for 6.1; docs and CHANGELOG. Needs a new release.
- [ ] **T043 live smoke check** (both, after T040; approval: INUBIT read-only): `get_health` of
  the node within 15 s (version and counts plausible), `find_processes(states: [ERROR])`,
  `query_logs` still `UNSUPPORTED_VERSION`; at most two StartCLI sessions at once per node.
  On Windows additionally: the session runs through `cmd.exe` with `startcli.bat`, password on
  stdin, trust store per client options. Record in the quickstart's "Validation record" and
  re-run the rows of "To re-run after T040".
- [ ] **T044 export recordings** (macOS; approval: INUBIT read-only, exports of one test diagram
  group and one repository path): `v61_export_group`, `v61_export_repository` once added to the
  catalogue; neutralize; research addendum on the 6.1 archive format. Blocked on sample exports.
- [ ] **T045** (code work after T044; approval: none): export on 6.1, then flip
  `WORKSPACE_EXPORT` (also admits `export_archives`).

## 4. Feature 017: INUBIT 9.0 (T047–T048)

Steps are those of `specs/017-inubit-9-support/quickstart.md` ("Live check").

- [ ] **T047 live check** (macOS or Windows; approval: INUBIT read-only, and INUBIT write for the
  write steps, against a 9.0.x **test** node only): steps 1–11 of the quickstart; append a dated
  record (tested 9.0 version, result per step) to the quickstart.
- [ ] **T048 recording** (macOS; approval: INUBIT read-only against the 9.0.x test node):
  `RECORD_INUBIT_LINE=9.0` (quickstart step 12); the recorded files replace the
  documentation-derived ones (`.origin` = `recorded`); neutralize; build green; compare the
  non-recordable write successes with T047's results (SC-006).

## 5. After the live test

- [ ] Results go into the quickstarts' "Validation record" sections and the tasks are checked
  off in `specs/` on a branch from `main`; this file is not a record.
- [ ] Delete `~/.inubit-mcp/update-check.json` if it still holds a test value.
- [ ] Code changes that follow (e.g. the 6.1 flip T040/T045, recorded 9.0 fixtures) ship in a
  later release.
