SPEC: specs/023-test-home-isolation/spec.md  ROUND: 2
FINDINGS:
- [major] FR-007 / SC-003 / FR-002 – What counts as a read of HOME is undefined. "HOME"/"USERPROFILE" appear in env-var allowlists passed to child processes (StartCliEnvironment.java:43,47, KeychainStore.java:53, SecretServiceStore.java:42), and Wiring.java:254 hands over System.getenv() as a whole. Define a read precisely (System.getProperty("user.home"), System.getenv("HOME"/"USERPROFILE"), literal or constant, map lookups on System.getenv()); exclude allowlist entries and passing the whole map; add US2 scenario 4.
- [minor] US3 scenario 1 / SC-005 – List audit, workspace, knowledge defaults concretely (H/.inubit-mcp/P/audit|workspace|knowledge, ConfigLoader.java:378-406).
- [minor] FR-005 – Removal on close does not cover tests that never close the server; guarantee removal at the latest when the test ends.
VERDICT: CHANGES_REQUESTED
Round 1 findings all resolved.
