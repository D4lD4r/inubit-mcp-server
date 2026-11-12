# Validation: Test runs leave the real user home untouched

## Full build with marker check (quickstart step 3, SC-001, SC-002)

Branch `023-test-home-isolation` at 6e945971, 2026-11-12, macOS.

```bash
touch <marker> && mvn -B verify && find ~/.inubit-mcp -newer <marker>
```

- Start 14:03:52, end 14:15:24.
- Surefire: 7263 tests, 0 failures, 0 errors, 58 skipped. Failsafe (`*IT`): 4 tests, 0 failures.
  `BUILD SUCCESS`, exit 0.
- Precondition of quickstart step 3 only partly met: no other test run (surefire) was active, but
  9 real inubit-mcp server JVMs started by MCP clients (oldest 2026-11-11, newest 12:10 the same day)
  ran during the whole build, and one more server was started during it (see below).
- `find ~/.inubit-mcp -newer <marker>`:

| Path | Modified | Writer |
|---|---|---|
| `~/.inubit-mcp` (directory entry) | 14:14:23 | the two files below |
| `~/.inubit-mcp/<real profile>/cert-check.log` | 14:14:22 | `inubit-cert-check` (the developer's certificate check, run by the start wrapper `~/.local/bin/inubit-mcp-<real profile>` before it starts a server), outside this build |
| `~/.inubit-mcp/update-check.json` | 14:14:23 | the update check of a real inubit-mcp server, outside this build |

Evidence for the attribution:

- `ps -eo pid,lstart,command`, run by the orchestrator right after the build, showed a non-test
  inubit-mcp server JVM (a development jar of another session, outside this worktree) started at
  14:14:21; it was no longer running when the review checked. Which start produced which of the two
  files is not determined; both are server-start artefacts, not test artefacts, as the next two
  points show.
- `cert-check.log` gained lines at 13:14:22Z (14:14:22 local) with certificate fingerprints of the
  real profile's INUBIT hosts; no test contacts those hosts, and no test runs the wrapper.
- `update-check.json` holds `{"latestVersion":"0.9.1","checkedAt":"2026-11-12T13:14:23.31Z"}`, a
  real release check; every test that runs the update check uses a temporary home (`MainTest`
  context) or `-Duser.home=<temp>` (`MainProcessTest`).
- Nothing below `~/.inubit-mcp/acme` (the test profile) and no backup, ledger, package, audit,
  workspace or knowledge file was written.

The writers are outside this code base (the developer's own server starts), so the
"fix it in the responsible task and repeat" step of T010 does not apply; a repeat cannot rule out a
server being started by an MCP client at any time.

Result: SC-001 holds by attribution: the literal `find` count is 3, all from real server starts,
0 from the build. SC-002 holds.

## Red first (quickstart step 1, SC-004)

- T002, commit 57f73c12: `WiringAuditTest.anImportLeavesTheRealHomeUntouched` red on unchanged
  production code (backup `<auditId>.json` / `<auditId>-1.zip` below `~/.inubit-mcp/acme/backups`).
- T006, commit 4e1504df: `UserHomeReadGuardTest` red with `Wiring.java:461, 492, 527, 544` and
  `config/ConfigLoader.java:124`.
- Both green since T004 (51156665).

## Unchanged locations (quickstart step 4, SC-003, SC-005)

- `WiringHomeLocationsTest`, `MainTest` (two new tests), `FileUpdateCacheTest`,
  `ExportArchivesWiringTest`: green in the full build above.
- `grep "user.home" src/main/java`: only `Launcher.java` inside `Context.system` (SC-003: 1 read).

## Live tests (quickstart step 5)

Not run against a real target in this validation (opt-in). Covered by `LiveTargetTest` in the default
build.
