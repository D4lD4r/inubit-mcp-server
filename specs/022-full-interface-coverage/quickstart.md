# Quickstart: Validating Full Interface Coverage

**Feature**: 022-full-interface-coverage | **Plan**: [plan.md](plan.md)

This guide proves each increment works. It holds no implementation; types are in
[data-model.md](data-model.md), tool shapes in [contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md).

## Prerequisites

- JDK 21+, Maven, the 0.9.1 baseline suite green (`mvn -o -q clean verify`), feature 021 merged.
- For live checks:
  - an 8.1 and a 9.0 **non-production** server in a profile, with an INUBIT client per version
    (feature 017);
  - a login user with the role "System Administrator" and "CLI login access";
  - the 9.0 server's certificate pin current (feature 021 open point).

## Offline validation per increment

Every increment ends with `mvn -o -q clean verify` green. Its tests were written first and seen
failing (Principle III).

| Increment | Tests that must exist and pass | Proves |
|---|---|---|
| 1 (US1) | `InterfaceCoverageTest`, `InterfaceCoverageDocsTest`, `RestEndpointRegistryTest`, `CliSessionCommandTest` (8.1 commands), `ServerSettingsServiceTest`, `GetServerSettingsToolTest`, `ConfigurationMaskingTest`, `LicenseXmlParserTest`, settings contract tests 8.1/9.0, `ServerSettingsSecretLeakTest` | FR-001–FR-003, US1 AS1–AS7, FR-010, SC-005 for US1 |
| 2 (US2) | `UserListParserTest` (personal fields dropped), `UserSessionParserTest`, `ReadUsersToolTest`, `ReadUsersSecretLeakTest`, contract tests | US2 AS1–AS6, FR-011 |
| 3 (US3) | `ReadModelDetailsToolTest`, `ModuleDetailParserTest`, `ExportArchivesContentTest` (three new forms, folder rules unchanged), `ArchiveToolReferenceTest` update, contract tests | US3 AS1–AS8, FR-012 |
| 4 (US4) | `ChangeGuardTest` (order of refusals, every code), `ChangeLocksTest`, `OperateServerToolTest`, `ManageTagsToolTest` (scope required; `--tagDiagram` never sent), `CliCommandTest` (new `trace`/`tag` options, rejected forms), `WriteRaceToolsTest` (+2 tools), `ProductionRefusalTest` (+2), audit tests | US4 AS1–AS14, FR-014, FR-015, FR-017, FR-019a, SC-003, SC-004 |
| 5 (US5) | `ExportFilterTest` (grammar, rejected characters), `TransferRuntimeDataToolTest` (export is a change; reads need the opt-in only), `RuntimeExportParserTest`, contract tests | US5 AS1–AS7, FR-016a, FR-020 |
| 6 (US6) | `UploadFilesTest` (link, outside folder, too large, swap after check), `SecretEntryTest` (reserved names, namespace, missing entry), `AdministrationConfigTest`, `UploadsConfigTest`, `AdministerObjectsToolTest`, `ChangeServerSettingsToolTest`, `AdministrationBackupTest` (backup before change, nothing sent when backup fails), `RestoreBackupAdministrationTest`, `AdministrationSecretLeakTest` | US6 AS1–AS17, FR-016, FR-018, FR-021, FR-022 |
| 7 (US7) | `OwnAccessTest`, `UserImportFileTest`, `AdministerUsersToolTest`, `PasswordNeverOnCommandLineTest` (argv of every launch holds no entry value), `UserAdminSecretLeakTest` | US7 AS1–AS10, FR-019, FR-019b, FR-022, SC-005 |

Every increment also passes the following:

- `ToolAnnouncementTest`;
- `ToolHintsTest`;
- `NoCustomerIdentifiersTest`;
- `NoSecretLeakTest`;
- `PackageBoundaryTest`;
- `DevelopmentToolsReferenceTest` (every error code documented);
- `MessageTerminologyTest`;
- the existing tool tests unchanged (SC-009).

## Live checks (operator)

The checks are L-1 … L-18 in [research.md](research.md#live-checks).

**Steps**:

1. **Approval**: before a check marked (W), the operator names the command and the server in the
   conversation and approves it (FR-026). The approval is noted in the validation record below.
2. **Recording**: `tools/record-fixtures.sh` (`RECORD_INUBIT_LINE=8.1` or `9.0`) with the
   check's case names. Fixtures go to `src/test/resources/fixtures/{v8_1,v9_0}/{cli,rest}/`.
3. **Neutralization**: `tools/neutralize.py`, then `tools/check-identifiers.py`. 9.0 cases get
   `.origin` = `recorded <date> <version>`.
4. **Flip**: in the same commit, set the coverage row to `reachable` (or `excluded:<reason>`) and
   add the contract test.

**Expected outcomes**:

- A read check gives the parser its recorded form, and the contract test passes.
- A (W) check also shows the before and after state, re-read by the tool itself on the test
  server.

## End-to-end smoke (per increment, test server)

These steps run through an MCP client (Claude Code) against the test profile.

1. **US1**: `get_server_settings` on the 8.1 test server. Expected:
   - the license expiry, threads and the privileged share;
   - `configuration` lists section names;
   - a second call with one section shows `maskedCount > 0` for a section with passwords.
2. **US2**: `read_users view=users limit=5`. Expected: five users with groups and roles, no
   `email`; `nextOffset` set.
3. **US4**: `operate_server setTraceLevel` on a stage without write enablement. Expected:
   `WRITE_DISABLED`.
4. **US4**: with write enabled, the same call. Expected: the preview first; with the code, the
   level before and after. Then set the level back.
5. **US5**: `transfer_runtime_data listBlocked` without `administration.runtimeData`. Expected:
   `NOT_CONFIGURED`.
6. **US6**: `administer_objects deleteWorkflow` of a throw-away workflow on the test server.
   Expected:
   - the preview names its versions;
   - after confirmation, a backup path, and the workflow is gone in `list_inventory`;
   - `restore_backup` brings it back on a development node.
7. **US7**: `administer_users setPassword` for a test user with `secretEntry`. Expected:
   - the result has no password;
   - `ps -ef` during the call shows no entry value;
   - the audit record names the entry.

## Validation record

| Date | Increment | Check / smoke | Server version | Approved by | Outcome |
|---|---|---|---|---|---|
| | | | | | |
