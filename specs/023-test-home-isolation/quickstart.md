# Quickstart: validating the home isolation

## Prerequisites

- No other inubit-mcp process active on the machine: no running server (check your MCP clients) and
  no test run in another checkout or worktree (`pgrep -fl surefire`).

## 1. Red first (on the commit with only the new tests)

```bash
mvn -q test -Dtest='WiringHomeIsolationTest,UserHomeReadGuardTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: both fail. The guard lists `ConfigLoader.java:124` and `Wiring.java` lines 461, 492, 527,
544; the isolation test names a new file below `~/.inubit-mcp/acme/backups`.

## 2. Green

Same command after the fix: both pass.

## 3. Full build with marker check (SC-001, SC-002)

```bash
touch /tmp/inubit-mcp-marker && mvn verify && find ~/.inubit-mcp -newer /tmp/inubit-mcp-marker
```

Expected: `BUILD SUCCESS`, and `find` prints nothing.

## 4. Unchanged locations of a real server (SC-005)

`WiringHomeIsolationTest` covers backups and the forbidden export root for a handed-in home; the
existing `MainProcessTest` starts the packaged server with `-Duser.home=<temp>` and its state appears
below that home. Code check: `Launcher` passes `context.userHome()` and `Context.system` reads
`user.home`.

## 5. Live tests (optional, when a test target is configured)

```bash
touch /tmp/inubit-mcp-marker && mvn verify -Plive && find ~/.inubit-mcp -newer /tmp/inubit-mcp-marker
```

Expected: `find` prints nothing (configuration and keychain are only read).

## 6. After merge

```bash
trash ~/.inubit-mcp/acme
```

(Moves the directory to the trash; never `rm -rf`.)
