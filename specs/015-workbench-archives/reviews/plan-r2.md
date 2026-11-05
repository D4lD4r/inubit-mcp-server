# Plan review – round 2 (2026-11-05)

FEATURE: specs/015-workbench-archives  ROUND: 2  VERDICT: CHANGES_REQUESTED (one major)

All 12 round-1 findings are resolved; spot-checked code references are accurate; coverage complete.

## Findings

1. [major] T013/T016/data-model "ConfigLocation": `TargetFolderRules` (application) takes a
   `ConfigLocation` (config); `PackageBoundaryTest` (123-127) allows `application` only `java/`,
   `domain/`, `application/`, `org/slf4j/`. Pass `List<Path> configurationRoots`; `Wiring` passes
   `configLocation.roots()`; align data model, T013, T016 and the plan.
2. [minor] T013 Windows cases: CI runs on Ubuntu; `\\server\share\x` and `Z:\x` are relative there
   and step 1 (absolute) refuses them first. With the windows flag, recognise UNC and drive roots
   from the raw string before the `isAbsolute` check; assert the rule name.
3. [minor] T016 is large: split the `ConfigLocation` plumbing into its own task after T013.
4. [minor] `TEST_CONFIG_LOCATION`: name its `defaultDirectory` (e.g. `<tmpdir>/inubit-mcp-test-config`).
5. [minor] No task asserts the `REFUSED` audit record of `TARGET_UNKNOWN` / `NOT_CONFIGURED`.
6. [minor] New `TestWiring.of(…, ConfigLocation)` overload is ambiguous with `of(…, ParameterStore)`;
   use a distinct name (e.g. `withConfigLocation`).
