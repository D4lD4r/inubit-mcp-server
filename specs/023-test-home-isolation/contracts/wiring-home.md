# Contract: the home of the composition root

Internal contract (package-private `Wiring`, test-visible `TestWiring`); no MCP interface changes.

## Wiring

- Every constructor takes `Path userHome` directly after `ConfigLocation configLocation`.
- `userHome` is required (`NullPointerException` with the message `userHome` otherwise).
- No constructor reads `System.getProperty("user.home")`, `System.getenv("HOME")` or
  `System.getenv("USERPROFILE")`, and none supplies a default for `userHome`.
- Locations derived from it: see [data-model.md](../data-model.md), rows 1–4.

## Launcher

- `Launcher.Context.system` is the only production code that reads the process home.
- `Launcher` passes `context.userHome()` to `Wiring` (the same value it passes to `ConfigLoader`,
  the credential store and the update-check cache).

## TestWiring

- Every existing factory keeps its signature; each built server gets a fresh directory from
  `Files.createTempDirectory("inubit-mcp-home")` as its `userHome`.
- `Path userHome()` returns that directory.
- `close()` closes the wiring and then deletes the directory recursively; directories of servers
  that were never closed are deleted by a JVM shutdown hook. Deletion failures are ignored.

## LiveTarget

- Configuration and credential store: loaded with the real home (unchanged).
- Every `TestWiring` it builds uses a configuration whose `auditDirectory`, `workspace` (unless the
  caller passes one) and `knowledge.directory` lie below a temporary directory of that `LiveTarget`;
  `knowledge.recording` is kept.
