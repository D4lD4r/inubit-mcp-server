# Quickstart validation: Workbench-Importable Workflow Archives (feature 015)

The default build runs offline: StartCLI is the scripted fake launcher, the archives come from the
recorded fixtures `fixtures/v8_1/artifacts/grp-a.zip` / `grp-b.zip` and variants built from them in
test code (`WorkflowArchiveFixtures`, research R-17), target folders are JUnit temporary
directories. No test contacts INUBIT (Constitution III). Shapes: [contracts/mcp-tools-delta.md](contracts/mcp-tools-delta.md),
[data-model.md](data-model.md).

## Commands

```bash
mvn -q -o clean verify
```

`security/NoCustomerIdentifiersTest` must **run** (the local identifier lists present, see
`docs/release-checks.md`); a skip does not count as validation.

## Scenarios and their tests

| # | Scenario (spec) | Expected | Test |
|---|---|---|---|
| Q1 | US1-1: three workflows into an empty folder | three `<name>.diagram.zip`, result lists files, sizes, modules, repository paths | `ArchiveExportServiceTest`, `ExportArchivesToolTest` |
| Q2 | US1-2: shared module | each archive holds the module; no module FR-005 does not select | `V81WorkflowArchivesTest` |
| Q3 | US1-3: repository chain | exactly the reached files of the owner (`.xml` + `.dat`), headers, order, comment kept; of the original's directory entries only the ancestors of a kept file (variant with directory entries) | `V81WorkflowArchivesTest`, `RawZipTest` |
| Q4 | US1-4: secrets | archive holds the values as exported; result, log, audit hold none | `V81WorkflowArchivesTest`, `security/ArchiveExportSecretLeakTest` |
| Q5 | US1-5: `workflows` names one workflow | only that archive | `ArchiveExportServiceTest` |
| Q6 | US1-6: file names | `Order_ In_Out_.diagram.zip`, `_CON.diagram.zip`; > 255 bytes refused | `ArchiveFileNamesTest`, `ArchiveExportServiceTest` |
| Q7 | FR-006: byte identity | module files, `archive.properties` byte-identical; XML entries `XmlEquality`-equal | `V81WorkflowArchivesTest` |
| Q8 | FR-005: nested nodes, bound modules, missing modules, Workflow Connector warning | as spec | `V81WorkflowArchivesTest` |
| Q9 | US2-1: no acknowledgement | `INVALID_INPUT` before StartCLI, audited `REFUSED`, nothing written | `ArchiveExportServiceTest` |
| Q10 | US2-2: folder rules (relative, `..`, `~/.inubit-mcp`, workspace, audit dir, default configuration directory in POSIX and `APPDATA` form, folder of a configuration file loaded from elsewhere, `.git`, each also via a symbolic link, UNC, remote drive — with the Windows flag recognised from the raw string before the absolute check, also on a non-Windows host) | `INVALID_INPUT` before StartCLI, message names the rule (UNC and remote drive: the network-path rule, not "relative") | `TargetFolderRulesTest`, `ConfigLocationTest`, `ArchiveExportServiceTest`, `ExportArchivesWiringTest`, `MainTest` |
| Q11 | US2-3: collision with an existing file (NFC/case fold) | refused after export, before writing; message names the file; file unchanged | `ArchiveExportServiceTest` |
| Q12 | US2-4: missing folder | created with missing parents `rwx------` right before the first archive; archives `rw-------` | `ArchiveFolderWriterTest` |
| Q13 | US2-5: target is a file / not writable / ancestor not writable | `INVALID_INPUT` before StartCLI | `TargetFolderRulesTest` |
| Q14 | US2-6/7: statement and annotations | `clearTextSecrets`; hints false/false/false/true | `ExportArchivesToolTest`, `ToolHintsTest` |
| Q15 | US2-8: cloud-sync root | not refused; call-level warning | `TargetFolderRulesTest` |
| Q16 | US3-1: audit of a successful call | `PENDING` + `EXECUTED` with names, folder, files, reason | `ArchiveExportAuditTest` |
| Q17 | US3-2: StartCLI fails / times out / export over 128 MiB | `export_artifacts` error passed through unchanged, nothing written, audited `FAILED` | `ArchiveExportServiceTest` |
| Q18 | US3-3: second of three writes fails; first archive or target folder fails; `.part` not deletable | result with `archives[1]`, `failed[1]` (`INTERNAL`), `notWritten[1]`; no partial file; `PARTIALLY_COMPLETED`, also with empty `archives[]`; a leftover `.part` gives a call-level warning (`EXECUTED`) | `ArchiveFolderWriterTest`, `ArchiveExportServiceTest` |
| Q19 | US3-4: missing module | archive written, `missingModules`, warning | `V81WorkflowArchivesTest`, `ArchiveExportServiceTest` |
| Q20 | US3-5: audit not writable | `INTERNAL` before StartCLI; a static refusal whose record fails → `INTERNAL` with the original code | `ArchiveExportAuditTest`, `ArchiveExportServiceTest` |
| Q21 | Edge: empty diagram group; > 50 workflows; unknown name; duplicate file names | as spec | `ArchiveExportServiceTest` |
| Q22 | Edge: file appears after the check / two concurrent calls | never replaced; `failed[]` names the file | `ArchiveFolderWriterTest`, `ArchiveExportConcurrencyTest` |
| Q23 | FR-014: production group without / with opt-in | `PRODUCTION_PROTECTED` naming `archives.productionOptIn` / allowed; tool announced | `ArchiveExportServiceTest`, `ExportArchivesWiringTest` |
| Q24 | FR-014: configuration and `--check-config` | key parsed, warning on non-production, summary line | `ConfigLoaderTest`, `ConfigValidatorTest`, `ConfigSummaryTest` |
| Q25 | FR-012: result bound | lists cut at `resultLimits.maxItems`, `truncated`, counts complete | `ArchiveExportServiceTest` |
| Q26 | FR-015: other version | `UNSUPPORTED_VERSION` as `export_artifacts` | `ArchiveExportServiceTest` |
| Q27 | SC-002: 10 workflows | build and write ≤ 10 s without StartCLI | `ArchiveExportPerformanceTest` |
| Q28 | FR-001/FR-003: workspace untouched, private export directory gone | no workspace file, no history entry | `security/ArchiveExportSecretLeakTest` |
| Q29 | FR-016: docs | `docs/tools.md` section and tool table, `README.md`; `docs/setup.md`, `SECURITY.md`; `CHANGELOG.md` (client confirmation, folder rules, Windows ACLs) | `ArchiveToolReferenceTest`, `ArchiveSetupSecurityDocsTest`, `ArchiveChangelogTest` |
| Q30 | SC-001: Workbench import (manual) | one archive from the development stage imports without error; workflow and modules equal; the `Workflow Connector` plug-in type string confirmed (R-5) | operator, with approval (`docs/live-tests.md`) |
| Q31 | R-13 step 1: unknown target / no owner (no `owner`, no `inventory.owner`) | `TARGET_UNKNOWN` / `NOT_CONFIGURED` before StartCLI, exactly one `REFUSED` audit record, nothing written | `ArchiveExportServiceTest` |

## Manual check SC-001 (operator only)

1. With the operator's approval, call `export_archives` on the development stage for one workflow
   with modules and repository files, into a new folder outside every git working tree.
2. In the INUBIT 8.1 Workbench: import the archive (diagram import), open the workflow, compare
   with the original (nodes, modules, repository files).
3. In a real export with a Workflow Connector node, check that the `ModuleGroupName` of its
   module's entry in `module/module.xml` is `Workflow Connector` (research R-5, unverified so far);
   record only the string.
4. Delete the archive afterwards (it holds clear-text secrets). Record date, INUBIT version and the
   result (no names of the customer) in the validation record below and in `CHANGELOG.md`.

## Validation record

| Date | Build | Result |
|---|---|---|
| — | — | — |
