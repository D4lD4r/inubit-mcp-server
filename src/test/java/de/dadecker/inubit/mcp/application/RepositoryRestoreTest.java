package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.archive.v81.RepositoryArchive;
import de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportScope;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.model.WritePreview;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T040/T041 (feature 008, US4, FR-018, research D-9): {@code restore_backup} of a call that
 * changed repository files. The restore set holds the files of the manifest's
 * {@code repositoryPaths} that the backup's repository exports hold (they existed before the
 * call), also for a backup of a repository-only call, whose scope {@code repository paths …} is
 * rebuilt from the manifest. Files the call created are not restorable and are reported. The
 * conflict check reads the node per parent folder and compares each file with the
 * {@code repository:<path>} state the call left; a difference is {@code CONFLICT} and nothing is
 * sent.
 *
 * <p>T040/T042 (FR-018, FR-019, research D-9, D-11): on a node switched to
 * {@code development.repository: READ_ONLY} since the call, the whole restore is refused with
 * {@code REPOSITORY_WRITE_DENIED} before anything is read or sent; restoring repository files
 * always needs a server code, also on a node with client confirmation; every audit record of
 * the restore lists each repository file as {@code repository.<n>} with its operation, full path
 * and the hash of the backup content the restore sends, never the content.
 *
 * <p>T040/T043 (research D-8, D-9): a confirmed restore takes its own backup (the node's current
 * repository exports), re-imports the backup content with one repository import before the
 * workflows and modules, verifies it byte for byte by re-export, writes the verified files back
 * and commits {@code restore <node>: …} with the reason; a failure rolls the files back from the
 * restore's own backup and reports {@code rollback}.
 *
 * <p>The referenced call is a repository-only import ({@code repositoryPaths: xsd}) of
 * {@code jdoe} that modified the exported {@code xsd/a.xsd} and {@code xsd/c.xsd} and created
 * {@code xsd/b.xsd}, unless a test names another; every StartCLI call is scripted.
 */
@Timeout(60)
class RepositoryRestoreTest {

    private static final String A = "/Root/jdoe/xsd/a.xsd";
    private static final String B = "/Root/jdoe/xsd/b.xsd";
    private static final String C = "/Root/jdoe/xsd/c.xsd";
    private static final String XSD = "/Root/jdoe/xsd";
    private static final String MODULE_IMPORT = "--importModule --importUser 'jdoe'"
        + " --returnProtocol";

    @TempDir
    Path temp;

    private ImportHarness harness;
    private ImportService service;
    private final FakeServer node = new FakeServer();

    @BeforeEach
    void setUp() throws IOException {
        harness = ImportHarness.grpA(temp);
        harness.confirmation = WritePolicy.Confirmation.SERVER;
        harness.repositoryFile("xsd/a.xsd", schema("a"), "application/x-xsd+xml");
        harness.repositoryFile("xsd/c.xsd", schema("c"), "application/x-xsd+xml");
        harness.exports.history.commitAll("export dev/jdoe: repository",
            Map.of(VersionHistoryPort.SERVER_STATE, ImportHarness.DEV.group().value()));
        node.putRepositoryFile(A, schema("a"));
        node.putRepositoryFile(C, schema("c"));
        service = harness.repositoryWrite(ImportHarness.DEV, true).service();
    }

    private static String schema(String name) {
        return "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">\n  <xs:element name=\""
            + name + "\"/>\n</xs:schema>\n";
    }

    private static String file(String repositoryPath) {
        return WorkspacePath.repository(ImportHarness.DEV.group(), "jdoe", repositoryPath)
            .toRelativePath().toString().replace('\\', '/');
    }

    private static String changed(String name) {
        return schema(name).replace("</xs:schema>", "  <xs:element name=\"added\"/>\n"
            + "</xs:schema>");
    }

    /** The executed repository-only import of {@code xsd}; its backup ref. */
    private String imported() {
        harness.exportRepository(node, XSD);
        String code = ((ImportService.Response.Challenge) service.importArtifacts(
            harness.repositoryPaths("Schemas", "xsd"))).preview().confirmationCode();
        harness.exportRepository(node, XSD).importRepositoryApplied(node)
            .exportRepository(node, XSD);
        ImportService.Response response = service.importArtifacts(ImportHarness.confirmed(
            harness.repositoryPaths("Schemas", "xsd"), code));
        harness.cli.verifyComplete();
        assertThat(response).isInstanceOf(ImportService.Response.Completed.class);
        WriteOutcome outcome = ((ImportService.Response.Completed) response).outcome();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        return outcome.backupRef().orElseThrow();
    }

    /** a.xsd and c.xsd changed, b.xsd new: the import modifies two files and creates one. */
    private String importedTwoModifiedAndOneCreated() {
        harness.write(file(A), changed("a"));
        harness.write(file(C), changed("c"));
        harness.write(file(B), schema("b"));
        String ref = imported();
        assertThat(node.repositoryContent(A)).hasValue(changed("a"));
        assertThat(node.repositoryContent(C)).hasValue(changed("c"));
        assertThat(harness.backups.find(ref).orElseThrow().created()).containsExactly(B);
        return ref;
    }

    private static ImportService.RestoreRequest restore(String ref) {
        return new ImportService.RestoreRequest("dev/node1", ref, "Undo the schemas",
            Optional.empty(), Optional.empty());
    }

    /** The restore's preview; nothing was sent. */
    private WritePreview preview(String ref) {
        ImportService.Response response = service.restore(restore(ref));
        harness.cli.verifyComplete();
        assertThat(response).isInstanceOf(ImportService.Response.WriteChallenge.class);
        assertThat(node.repositoryImports).as("only the referenced import").hasSize(1);
        return ((ImportService.Response.WriteChallenge) response).preview();
    }

    @Test
    void aRepositoryOnlyBackupIsRestorableWithItsRebuiltScope() {
        String ref = importedTwoModifiedAndOneCreated();
        // the conflict check reads the node once per parent folder
        harness.exportRepository(node, XSD);

        WritePreview preview = preview(ref);

        assertThat(harness.cli.execCommands()).filteredOn(line -> line.startsWith("export "))
            .as("the import's three reads and the restore's one")
            .hasSize(4).allMatch(line -> line.startsWith("export --exportRepositoryPath '" + XSD
                + "' "));
        assertThat(preview.scope()).isEqualTo("repository paths xsd");
        assertThat(preview.modify()).as("the files that existed before the call")
            .containsExactly(A, C);
        assertThat(harness.audit("restore_backup")).last().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.CHALLENGE_ISSUED);
            assertThat(record.inputs()).containsEntry("backupRef", ref)
                .containsEntry("scope", "repository paths xsd")
                .containsEntry("changeSet", A + ", " + C);
        });
    }

    @Test
    void aCreatedFileIsNotRestoredButNamedAsCreatedAndNotRemoved() {
        String ref = importedTwoModifiedAndOneCreated();
        harness.exportRepository(node, XSD);

        WritePreview preview = preview(ref);

        assertThat(preview.modify()).doesNotContain(B);
        assertThat(preview.notes()).anySatisfy(note -> assertThat(note)
            .contains("Created by that call and not removed", B));
    }

    @Test
    void aBackupWhoseFilesWereAllCreatedSendsNothingAndListsThemAsCreatedNotRemoved() {
        harness.write(file(B), schema("b"));
        String ref = imported();
        assertThat(harness.backups.find(ref).orElseThrow().repositoryPaths())
            .containsExactly(B);

        ImportService.Response response = service.restore(restore(ref));

        harness.cli.verifyComplete();
        assertThat(response).isInstanceOf(ImportService.Response.Completed.class);
        WriteOutcome outcome = ((ImportService.Response.Completed) response).outcome();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).isEmpty();
        assertThat(outcome.createdNotRemoved()).containsExactly(B);
        assertThat(node.repositoryImports).hasSize(1);
        assertThat(node.repositoryContent(B)).as("an import cannot delete")
            .hasValue(schema("b"));
    }

    /** The restore's preview code; the node read of the confirmed call is scripted. */
    private String previewed(String ref) {
        harness.exportRepository(node, XSD);
        String code = preview(ref).confirmationCode();
        harness.exportRepository(node, XSD);
        return code;
    }

    /** The confirmed restore; every scripted StartCLI call ran. */
    private WriteOutcome confirmed(String ref, String code) {
        ImportService.Response response = service.restore(new ImportService.RestoreRequest(
            "dev/node1", ref, "Undo the schemas", Optional.of(code), Optional.empty()));
        harness.cli.verifyComplete();
        assertThat(response).isInstanceOf(ImportService.Response.Completed.class);
        return ((ImportService.Response.Completed) response).outcome();
    }

    /** Repository path → content of the {@code n}-th repository import the node received. */
    private Map<String, String> repositoryImport(int n) {
        return RepositoryArchive.readRelative("jdoe", node.repositoryImports.get(n)).stream()
            .collect(Collectors.toMap(RepositoryArchive.RepositoryFile::path,
                f -> new String(f.content(), StandardCharsets.UTF_8)));
    }

    private static Map<String, String> repositoryState(String a, String c) {
        return BackupStore.repositoryState(Map.of(A, a.getBytes(StandardCharsets.UTF_8), C,
            c.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aConfirmedRestoreReImportsThePreviousContentVerifiesAndCommitsIt() {
        String ref = importedTwoModifiedAndOneCreated();
        String code = previewed(ref);
        harness.importRepositoryApplied(node).exportRepository(node, XSD);

        WriteOutcome outcome = confirmed(ref, code);

        assertThat(outcome.outcome()).as(outcome.toString())
            .isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).containsExactly(A, C);
        assertThat(outcome.createdNotRemoved()).containsExactly(B);
        assertThat(node.repositoryImports).hasSize(2);
        assertThat(repositoryImport(1)).as("the backup content of the files that existed before")
            .containsOnly(Map.entry(A, schema("a")), Map.entry(C, schema("c")));
        assertThat(node.repositoryContent(A)).hasValue(schema("a"));
        assertThat(node.repositoryContent(C)).hasValue(schema("c"));
        assertThat(node.repositoryContent(B)).as("an import cannot delete").hasValue(schema("b"));
        assertThat(harness.inubit.imported).as("repository files alone: no importArchive")
            .isEmpty();
        // the workspace takes the verified state, in one history entry with the reason
        assertThat(harness.read(file(A))).isEqualTo(schema("a"));
        assertThat(harness.read(file(C))).isEqualTo(schema("c"));
        assertThat(harness.exports.history.status()).isEmpty();
        assertThat(harness.exports.git("log", "-1", "--format=%B", outcome.commit()
            .orElseThrow()).strip()).isEqualTo("restore dev/node1: repository paths xsd"
                + " (2 artifacts) [" + outcome.auditId() + "]\n\nServer-State: dev\nReason:"
                + " Undo the schemas");
        // the restore's own backup holds what it replaced and the state it left
        BackupStore.Manifest own = harness.backups.find(outcome.backupRef().orElseThrow())
            .orElseThrow();
        assertThat(own.auditId()).isNotEqualTo(ref).isEqualTo(outcome.auditId().toString());
        assertThat(own.outcome()).isEqualTo("EXECUTED");
        assertThat(own.repositoryPaths()).containsExactly(A, C);
        assertThat(own.intendedState()).containsAllEntriesOf(repositoryState(schema("a"),
            schema("c")));
    }

    @Test
    void aRestoreTheNodeDoesNotShowByteForByteIsRolledBackFromItsOwnBackup() {
        // the node keeps a.xsd without its last line break
        String ref = importedTwoModifiedAndOneCreated();
        String code = previewed(ref);
        harness.importRepositoryApplied(node).exportRepository(node, XSD);
        harness.cli.then(spec -> node.putRepositoryFile(A, schema("a").stripTrailing()));
        harness.importRepositoryApplied(node).exportRepository(node, XSD);

        WriteOutcome outcome = confirmed(ref, code);

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.FAILED);
        assertThat(outcome.failure()).hasValueSatisfying(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.VERIFY_MISMATCH);
            assertThat(failure.step()).isEqualTo("verify");
        });
        assertThat(outcome.reports()).singleElement().satisfies(report -> assertThat(report)
            .startsWith(".reports/verify-"));
        assertThat(harness.read(outcome.reports().get(0))).contains("=== " + A)
            .doesNotContain("=== " + C);
        assertThat(node.repositoryImports).hasSize(3);
        assertThat(repositoryImport(2)).as("the rollback: the content the restore replaced")
            .containsOnly(Map.entry(A, changed("a")), Map.entry(C, changed("c")));
        assertThat(node.repositoryContent(A)).hasValue(changed("a"));
        assertThat(node.repositoryContent(C)).hasValue(changed("c"));
        assertThat(outcome.rollback()).hasValue(WriteOutcome.Rollback.SUCCEEDED);
        assertThat(outcome.commit()).isEmpty();
        assertThat(harness.read(file(A))).as("the workspace is not written").isEqualTo(
            changed("a"));
        BackupStore.Manifest own = harness.backups.find(outcome.backupRef().orElseThrow())
            .orElseThrow();
        assertThat(own.outcome()).isEqualTo("FAILED");
        assertThat(own.intendedState()).containsAllEntriesOf(repositoryState(changed("a"),
            changed("c")));
        assertThat(harness.backups.find(ref).orElseThrow().outcome()).as("the referenced"
            + " backup is unchanged").isEqualTo("EXECUTED");
        assertThat(harness.audit("restore_backup")).last().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.inputs()).containsEntry("rollback", "SUCCEEDED");
        });
    }

    @Test
    void aRestoreWhoseRollbackFailsReportsItWithThePathsAndTheRestoresBackup() {
        // INUBIT applies the files but reports 1-NOK; the re-import of the restore's backup is
        // refused, so both files keep the restored content
        String ref = importedTwoModifiedAndOneCreated();
        String code = previewed(ref);
        harness.cli.expect(java.util.regex.Pattern.compile("^import --importFile '[^']+'"
                + " --importRepositoryPath '/Root/jdoe'$"))
            .then(spec -> node.importRepository(ImportHarness.read(
                ScriptedProcessLauncher.importFile(spec)), "jdoe"))
            .replying("import_nok");
        harness.exportRepository(node, XSD).importRefused().exportRepository(node, XSD);

        WriteOutcome outcome = confirmed(ref, code);

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.FAILED);
        assertThat(outcome.rollback()).hasValue(WriteOutcome.Rollback.FAILED);
        String own = outcome.backupRef().orElseThrow();
        assertThat(own).isNotEqualTo(ref);
        assertThat(outcome.failure()).hasValueSatisfying(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.IMPORT_FAILED);
            assertThat(failure.step()).isEqualTo("import");
            // US4: restore_backup restores repository files now
            assertThat(failure.message()).contains(A, C, own, "restore_backup", "Workbench")
                .doesNotContain(B);
        });
        assertThat(node.repositoryContent(A)).hasValue(schema("a"));
        assertThat(outcome.createdNotRemoved()).containsExactly(B);
        assertThat(harness.backups.find(own).orElseThrow().intendedState())
            .containsAllEntriesOf(repositoryState(schema("a"), schema("c")));
    }

    /**
     * {@code node} without a.xsd: the referenced call's {@code repository:<path>} state is
     * {@code absent} now.
     */
    private void aWasRemovedOnTheNode(FakeServer now) {
        String ref = importedTwoModifiedAndOneCreated();
        harness.exportRepository(now, XSD);

        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> service.restore(restore(ref)));

        assertThat(exception).as("expected a refusal").isNotNull();
        harness.cli.verifyComplete();
        assertThat(exception.error().code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(exception.error().message()).contains("Nothing was sent", A);
        assertThat(node.repositoryImports).as("only the referenced import").hasSize(1);
        String report = exception.error().message().replaceAll("(?s).*Differences: ", "")
            .strip();
        assertThat(harness.read(report)).contains("=== " + A, "absent now");
    }

    @Test
    void aFileTheNodeNoLongerHoldsIsAConflict() {
        FakeServer now = new FakeServer();
        now.putRepositoryFile(C, changed("c"));

        aWasRemovedOnTheNode(now);
    }

    @Test
    void aFolderTheNodeNoLongerHoldsIsAConflict() {
        aWasRemovedOnTheNode(new FakeServer());
    }

    private String pluginType() {
        return harness.moduleDirectory("Module-0003").split("/")[3];
    }

    /**
     * The executed import of module {@code Module-0003}, which references {@code a.xsd} now,
     * and of the changed {@code a.xsd}; its backup ref.
     */
    private String importedModuleAndSchema() {
        harness.edit(harness.moduleDirectory("Module-0003") + "/module.xml", "</Properties>",
            "<Property name=\"x.a\">inubitrepository:" + A + "</Property></Properties>");
        harness.write(file(A), changed("a"));
        ImportService.ImportRequest request = new ImportService.ImportRequest(
            ImportHarness.DEV.value(), Optional.empty(), Optional.empty(), List.of(
                new ImportScope.Module("Module-0003", Optional.of(pluginType()))), "Schemas",
            Optional.empty(), Optional.empty());
        harness.exportModule(pluginType(), "Module-0003").exportRepository(node, XSD);
        String code = ((ImportService.Response.Challenge) service.importArtifacts(request))
            .preview().confirmationCode();
        harness.exportModule(pluginType(), "Module-0003").exportRepository(node, XSD)
            .importRepositoryApplied(node).importApplied(MODULE_IMPORT)
            .exportRepository(node, XSD).exportModule(pluginType(), "Module-0003");
        ImportService.Response response = service.importArtifacts(ImportHarness.confirmed(
            request, code));
        harness.cli.verifyComplete();
        WriteOutcome outcome = ((ImportService.Response.Completed) response).outcome();
        assertThat(outcome.outcome()).as(outcome.toString())
            .isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).containsExactly("Module-0003", A);
        return outcome.backupRef().orElseThrow();
    }

    @Test
    void aDiagramGroupCallThatAlsoChangedARepositoryFileIsRestoredFilesFirst() {
        String ref = importedWorkflowModuleAndSchema();
        harness.exportGroup().exportRepository(node, XSD);
        WritePreview preview = preview(ref);
        assertThat(preview.scope()).isEqualTo("diagram group GRP-01");
        assertThat(preview.modify()).containsExactlyInAnyOrder("Workflow-0001", "Module-0003",
            A);
        harness.exportGroup().exportRepository(node, XSD).importRepositoryApplied(node)
            .importRestored().exportRepository(node, XSD).exportGroup();

        WriteOutcome outcome = confirmed(ref, preview.confirmationCode());

        assertThat(outcome.outcome()).as(outcome.toString())
            .isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).containsExactlyInAnyOrder("Workflow-0001",
            "Module-0003", A);
        assertThat(harness.cli.execCommands()).filteredOn(line -> line.startsWith("import "))
            .as("the referenced import, then the restore: repository files first")
            .hasSize(4).satisfies(imports -> {
                assertThat(imports.get(2)).contains("--importRepositoryPath");
                assertThat(imports.get(3)).contains("--importWorkflow");
            });
        assertThat(node.repositoryContent(A)).hasValue(schema("a"));
        assertThat(harness.inubit.workflowXml()).contains("xPos=\"120\"")
            .doesNotContain("xPos=\"140\"");
        assertThat(harness.read(file(A))).isEqualTo(schema("a"));
        assertThat(harness.exports.history.status()).isEmpty();
    }

    @Test
    void aModuleCallThatAlsoChangedARepositoryFileIsRestoredFilesFirst() {
        String ref = importedModuleAndSchema();
        harness.exportModule(pluginType(), "Module-0003").exportRepository(node, XSD);
        WritePreview preview = preview(ref);
        assertThat(preview.scope()).isEqualTo("modules Module-0003");
        assertThat(preview.modify()).containsExactly("Module-0003", A);
        harness.exportModule(pluginType(), "Module-0003").exportRepository(node, XSD)
            .importRepositoryApplied(node).importApplied(MODULE_IMPORT)
            .exportRepository(node, XSD).exportModule(pluginType(), "Module-0003");

        WriteOutcome outcome = confirmed(ref, preview.confirmationCode());

        assertThat(outcome.outcome()).as(outcome.toString())
            .isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).containsExactly("Module-0003", A);
        assertThat(harness.cli.execCommands()).filteredOn(line -> line.startsWith("import "))
            .as("the referenced import, then the restore: repository files first")
            .hasSize(4).satisfies(imports -> {
                assertThat(imports.get(2)).contains("--importRepositoryPath");
                assertThat(imports.get(3)).contains("--importModule");
            });
        assertThat(node.repositoryContent(A)).hasValue(schema("a"));
        assertThat(harness.read(harness.moduleDirectory("Module-0003") + "/module.xml"))
            .doesNotContain("inubitrepository:" + A);
        assertThat(harness.read(file(A))).isEqualTo(schema("a"));
        assertThat(harness.exports.history.status()).isEmpty();
    }

    @Test
    void aFileChangedAgainOnTheNodeIsAConflictAndNothingIsSent() {
        String ref = importedTwoModifiedAndOneCreated();
        node.putRepositoryFile(C, changed("c").replace("added", "changed-again"));
        harness.exportRepository(node, XSD);

        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> service.restore(restore(ref)));

        assertThat(exception).as("expected a refusal").isNotNull();
        harness.cli.verifyComplete();
        ToolError error = exception.error();
        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(error.message()).contains("Nothing was sent", C, ".reports/conflict-")
            .doesNotContain(A);
        assertThat(node.repositoryImports).as("only the referenced import").hasSize(1);
        assertThat(node.repositoryContent(C)).hasValue(changed("c")
            .replace("added", "changed-again"));
        assertThat(harness.audit("restore_backup")).last().satisfies(record ->
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED));
    }

    // --- T042: the switch, the server code and the audit (FR-018, FR-019) ---------------------

    /** {@code sha256:} and the first 16 hex chars of {@code content}. */
    private static String hash(String content) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The executed import of the diagram group that changed {@code Workflow-0001},
     * {@code Module-0003} and {@code a.xsd}, which {@code Module-0003} references now; its
     * backup ref.
     */
    private String importedWorkflowModuleAndSchema() {
        harness.edit(harness.moduleDirectory("Module-0003") + "/module.xml", "</Properties>",
            "<Property name=\"x.a\">inubitrepository:" + A + "</Property></Properties>");
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        harness.write(file(A), changed("a"));
        harness.exportGroup().exportRepository(node, XSD);
        String code = ((ImportService.Response.Challenge) service.importArtifacts(
            harness.group("Schemas"))).preview().confirmationCode();
        harness.exportGroup().exportRepository(node, XSD).importRepositoryApplied(node)
            .importApplied().exportRepository(node, XSD).exportGroup();
        ImportService.Response response = service.importArtifacts(ImportHarness.confirmed(
            harness.group("Schemas"), code));
        harness.cli.verifyComplete();
        WriteOutcome outcome = ((ImportService.Response.Completed) response).outcome();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).containsExactlyInAnyOrder("Workflow-0001",
            "Module-0003", A);
        return outcome.backupRef().orElseThrow();
    }

    @Test
    void aNodeSwitchedToReadOnlySinceTheImportRefusesTheWholeRestoreBeforeAnythingIsRead() {
        String ref = importedWorkflowModuleAndSchema();
        service = harness.repositoryWrite(ImportHarness.DEV, false).service();

        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> service.restore(restore(ref)));

        assertThat(exception).as("expected a refusal").isNotNull();
        harness.cli.verifyComplete();
        ToolError error = exception.error();
        assertThat(error.code()).isEqualTo(ErrorCode.REPOSITORY_WRITE_DENIED);
        assertThat(error.message()).contains("READ_ONLY", A, "nothing was sent");
        assertThat(error.nextStep()).contains("restore").doesNotContain("git restore");
        assertThat(harness.inubit.imported).as("only the referenced import").hasSize(1);
        assertThat(node.repositoryImports).as("only the referenced import").hasSize(1);
        assertThat(node.repositoryContent(A)).hasValue(changed("a"));
        assertThat(harness.audit("restore_backup")).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.reason()).hasValueSatisfying(reason -> assertThat(reason)
                .startsWith("REPOSITORY_WRITE_DENIED: development.repository is READ_ONLY on "
                    + ImportHarness.DEV));
            assertThat(record.inputs()).containsEntry("repository.1", "modify " + A + " "
                + hash(schema("a"))).doesNotContainKey("repository.2");
            assertThat(record.inputs().values()).noneMatch(value -> value.contains("xs:"));
        });
    }

    @Test
    void aClientNodeGetsAServerCodeForTheRestoreOfRepositoryFiles() {
        String ref = importedTwoModifiedAndOneCreated();
        harness.confirmation = WritePolicy.Confirmation.CLIENT;
        service = harness.service();
        harness.exportRepository(node, XSD);

        WritePreview preview = preview(ref);

        assertThat(preview.modify()).containsExactly(A, C);
        assertThat(preview.confirmationCode()).isNotBlank();

        // a presented code is checked, also with client confirmation, before anything is read
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> service.restore(new ImportService.RestoreRequest("dev/node1", ref,
                "Undo the schemas", Optional.of("AAAAAAAAAAAAAAAAAAAAAA"), Optional.empty())));

        assertThat(exception).as("expected a refusal").isNotNull();
        harness.cli.verifyComplete();
        assertThat(exception.error().code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(node.repositoryImports).as("only the referenced import").hasSize(1);
    }

    @Test
    void everyRecordOfTheRestoreAuditsEachFileWithOperationAndTheHashOfTheBackupContent() {
        String ref = importedTwoModifiedAndOneCreated();
        String code = previewed(ref);
        harness.importRepositoryApplied(node).exportRepository(node, XSD);

        WriteOutcome outcome = confirmed(ref, code);

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(harness.audit("restore_backup")).extracting(record -> record.outcome())
            .containsExactly(AuditOutcome.CHALLENGE_ISSUED, AuditOutcome.PENDING,
                AuditOutcome.EXECUTED);
        assertThat(harness.audit("restore_backup")).allSatisfy(record -> {
            assertThat(record.inputs())
                .as("the content before the call, which the restore sends, not the workspace's")
                .containsEntry("repository.1", "modify " + A + " " + hash(schema("a")))
                .containsEntry("repository.2", "modify " + C + " " + hash(schema("c")))
                .doesNotContainKey("repository.3");
            assertThat(record.inputs().values()).noneMatch(value -> value.contains("xs:"));
        });
    }
}
