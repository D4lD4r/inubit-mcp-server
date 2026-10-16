package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.model.WritePreview;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
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
 * <p>The referenced call is a repository-only import ({@code repositoryPaths: xsd}) of
 * {@code jdoe} that modified the exported {@code xsd/a.xsd} and {@code xsd/c.xsd} and created
 * {@code xsd/b.xsd}; every StartCLI call is scripted.
 */
@Timeout(60)
class RepositoryRestoreTest {

    private static final String A = "/Root/jdoe/xsd/a.xsd";
    private static final String B = "/Root/jdoe/xsd/b.xsd";
    private static final String C = "/Root/jdoe/xsd/c.xsd";
    private static final String XSD = "/Root/jdoe/xsd";

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

    @Test
    void untilT043AConfirmedRestoreOfRepositoryFilesIsRefusedBeforeAnythingIsSent() {
        // T043 sends, verifies and rolls back the repository files of a restore; until then
        // the confirmed call fails closed: no backup, no PENDING, nothing sent
        String ref = importedTwoModifiedAndOneCreated();
        harness.exportRepository(node, XSD);
        String code = preview(ref).confirmationCode();
        harness.exportRepository(node, XSD);

        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> service.restore(new ImportService.RestoreRequest("dev/node1", ref,
                "Undo the schemas", Optional.of(code), Optional.empty())));

        assertThat(exception).as("expected a refusal").isNotNull();
        harness.cli.verifyComplete();
        assertThat(exception.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(exception.error().message()).contains(A, C, "nothing was sent");
        assertThat(node.repositoryImports).as("only the referenced import").hasSize(1);
        assertThat(harness.audit("restore_backup")).extracting(record -> record.outcome())
            .containsExactly(AuditOutcome.CHALLENGE_ISSUED, AuditOutcome.REFUSED);
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
}
