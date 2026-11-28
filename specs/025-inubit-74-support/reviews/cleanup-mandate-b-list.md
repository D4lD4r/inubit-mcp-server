# Mandate B cleanup — minor findings collected from the task reviews

Source: reviews T031-r1, T032-r1, T033-r1, T046-r1, T041-r1, T045-r1, T043-r1, T035-T036-r1.
Each item: fix it, or (where marked optional) fix it if cheap; do not change behaviour except item 6.

1. (T031) `application/HealthService.java` ~:123 — Javadoc line > 100 characters; rewrap.
2. (T032) `adapter/cli/v61/V61SessionParser.java` ~:63 — `named(error, profile)` must return `error` unchanged when its code is not `UNEXPECTED_RESPONSE` (today it always rebuilds as UNEXPECTED_RESPONSE). Add a unit test.
3. (T032) `adapter/cli/v61/V61ProcessQueryAdapter.java` ~:98 — add a one-line Javadoc to the public 4-argument (profile) constructor, like the 3-argument one and `V61HealthAdapter`'s.
4. (T033, now possible after T035) add a `CliRunnerMessagesTest` (or `CliRunnerTest`) case that goes through `CliRunner.select` with a 7.4 pin and only `defaults.cliHome` and asserts the `noHome` text names `defaults.cliClients."7.4"` and not `cliHome`; add a `versionLine: V7_4` node to one flagged case in `EffectiveNodeConfigCliForTest`.
5. (T046) `application/VersionGate.java` ~:258 — `@throws` Javadoc line > 100 characters; rewrap.
6. (T046, behaviour) `application/HealthService.java` ~:446-451 — the own-client-only pre-session probe picks its client from `versions.effectiveVersion(server)`, but `gate.ownClientRefusal` looks up the pin's client (`selection.current(node, pin)`); with a contradicted pin the check could see nothing recorded and fail open. Probe the pin's client in the own-client-only branch (or derive both from the same version). Add a HealthServiceTest case for a contradicted own-client-only pin that stays refused (fail-closed).
7. (T041) `domain/model/ContactedException.java`, `adapter/cli/CliRunner.java` ~:904-912, `adapter/cli/v61/V61Connection.java` ~:141-147 — re-wrapping as `new ContactedException(e.error())` drops `ToolErrorException.details()`; add a constructor taking details and pass `e.details().orElse(null)` (keep `warnings` behaviour of T044). Test that details survive.
8. (T041) `adapter/cli/CliRunner.java` ~:533-536 — public `runSession` `@throws` Javadoc: say errors after the process start come as `ContactedException`.
9. (T041) `adapter/cli/CliRunner.java` ~:890-895 — create the recording `StartHook` and its `AtomicBoolean` only when `markContact` is true.
10. (T045, optional) HealthServiceTest case for a deadline TIMEOUT of the StartCLI port `await` (carries the warning per FR-030 (b)).
11. (T043) `application/VersionGateTest.java` ~:1408-1414 — `everyOtherCapabilityOn61KeepsTheFeature012Refusal…` compares against a hand-built `beforeTheFlip` matrix; now that production is flipped, assert against `gate(CapabilityMatrix.production())` (every capability other than MONITORING and PROCESS_DIAGNOSIS on both 6.1 pins keeps the feature-012 refusal text, no probe, no detection) and drop the copy.
12. (T035) `config/ConfigValidatorTest.java` ~:933-935 — stale comment ("--check-config runs no -h probe for 7.4 … pinned in CliClientProbeTest"); say `validate()` runs no probe and point to `CliClientProbeTest.aSevenFourNodeIsProbedWithMinusHAsASixOneNode`.
13. (T035) `domain/model/CapabilityMatrix.java` ~:195 and `src/test/java/.../testing/TestMatrices.java` ~:16 — Javadoc lines ~150 characters; rewrap at 100.
