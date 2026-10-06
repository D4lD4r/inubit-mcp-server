# Quickstart: validating feature 003

Validation scenarios for the artifact workspace. Contracts:
[contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md),
[contracts/configuration-delta.md](contracts/configuration-delta.md); entities:
[data-model.md](data-model.md).

## Prerequisites

- JDK 21+, Maven 3.9+, `git` on the `PATH`.
- For live checks only: a profile with a development group, a CLI installation and credentials, as
  in `docs/setup.md`.

## A. Offline (CI)

```bash
mvn -q clean verify
mvn -q -Dtest=NoCustomerIdentifiersTest test    # again without clean: also scans the JAR
```

Expected, among the existing tests:

| Scenario | Test evidence | Spec |
|---|---|---|
| Export of a recorded diagram group writes workflows, modules, embedded documents and referenced repository files | `WorkspaceExportTest` | US1, FR-012 |
| Second export of the same archive → `unchanged: true`, no commit | `WorkspaceExportTest` | SC-001 |
| Changed stylesheet in a second recording → exactly one modified file | `WorkspaceExportTest` | US1-3 |
| Uncommitted edits → `local changes` commit, then export commit | `WorkspaceLocalChangesTest` | clarification 1, FR-019 |
| Rebuild from workspace equals the recorded archive (D-4 equality) for every fixture | `ArchiveRoundTripTest` | FR-015, SC-002 |
| No synthetic secret value appears in workspace, `.meta/`, git objects, tool results or captured logs | `SecretRedactionTest` | US2, SC-003 |
| Dangling edge, id collision, unmatched Demultiplexer key, missing module, unresolved repository reference detected; unchanged fixtures clean | `ArtifactCheckServiceTest` | US3, SC-004 |
| Stylesheets with stand-ins run; two runs identical; `now` override appears; unknown extension → `NOT_TESTABLE`; syntax error → `XSLT_STATIC_ERROR` | `XsltRunnerTest` | US4, clarification 4 |
| Every fixture stylesheet classified as listed, none falsely passed | `XsltCoverageTest` | US4 |
| ≥ 95 % of a real owner's stylesheets compile and execute locally with every extension call served by a stand-in (`OK` or `XSLT_RUNTIME_ERROR` on the given input, never passed); the `OK` share is reported separately (local opt-in corpus, `INUBIT_MCP_XSLT_CORPUS`; skipped in CI) | `XsltCorpusTest` | SC-005 |
| Synthetic archive with 100 modules processed in under 60 s | `WorkspaceExportTest` | SC-006 |
| XML not well-formed / XSD violations reported with location | `XmlValidationTest` | US5 |
| Default/custom/unusable/shared workspace at startup and in `--check-config` | `WorkspaceConfigTest` | US6 |
| Git is called without remotes, hooks or signing; never push | `GitCliTest` | FR-007 |
| Only `technical` is ever passed to StartCLI; blank diagram group rejected | `CliExportRunnerTest` | clarification 2, D-8 |

## B. Configuration check

```bash
java -jar target/inubit-mcp-server-*.jar --profile acme --check-config
```

Expected: a line `Workspace: /…/.inubit-mcp/acme/workspace (created)` on first run, `(ok)` after.

## C. Live, read-only (opt-in)

```bash
INUBIT_MCP_PROFILE=acme INUBIT_LIVE_NODE=dev/node1 INUBIT_LIVE_DIAGRAM_GROUP=GRP-07 mvn verify -Plive
```

- exports the diagram group twice → second result `unchanged: true`;
- the workspace contains no `AES-` value and no `type="Password"` property with a non-placeholder
  value;
- `check_artifacts` on the exported workflows reports no `ERROR`.

## D. With the AI assistant

1. "Export the diagram group GRP-07 from dev." → paths and commit listed.
2. Change a stylesheet file, then "check it against samples/order.xml". → output path, `OK`.
3. Edit a workflow file to point an edge at a non-existent node, "check the workflow". → `ERROR`
   `EDGE_TARGET_MISSING`.
4. "Export GRP-07 again." → the edit is preserved as `local changes`, the server state is the new
   commit; `git -C <workspace> log --oneline` shows both.
