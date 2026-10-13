package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportPreview;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 008: a development import whose change set holds repository files.
 *
 * <p>T023 (research D-5 step 3): the confirmation code is bound to the repository files of the
 * node (their fingerprint joins the server fingerprint) and of the workspace (their content is
 * part of the workspace hash). The workspace is {@code GRP-01} of {@code jdoe} with the exported
 * schema {@code xsd/a.xsd}, which the node holds unchanged; {@code Workflow-0001} is changed and
 * so is {@code Module-0003}, which it uses, to reference {@code a.xsd} (changed as well) and the
 * new {@code b.xsd}. The node uses server confirmation, so the first call is a preview; every
 * StartCLI call is scripted.
 */
@Timeout(60)
class RepositoryImportTest {

    private static final String A = "/Root/jdoe/xsd/a.xsd";
    private static final String B = "/Root/jdoe/xsd/b.xsd";

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
        harness.exports.history.commitAll("export dev/jdoe: repository",
            Map.of(VersionHistoryPort.SERVER_STATE, ImportHarness.DEV.group().value()));
        node.putRepositoryFile(A, schema("a"));
        harness.edit(harness.moduleDirectory("Module-0003") + "/module.xml", "</Properties>",
            "<Property name=\"x.a\">inubitrepository:" + A + "</Property>"
                + "<Property name=\"x.b\">inubitrepository:" + B + "</Property></Properties>");
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        harness.edit(file(A), "</xs:schema>", "  <xs:element name=\"added\"/>\n</xs:schema>");
        harness.write(file(B), schema("b"));
        service = harness.service();
    }

    private static String schema(String name) {
        return "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">\n  <xs:element name=\""
            + name + "\"/>\n</xs:schema>\n";
    }

    private static String file(String repositoryPath) {
        return WorkspacePath.repository(ImportHarness.DEV.group(), "jdoe", repositoryPath)
            .toRelativePath().toString().replace('\\', '/');
    }

    /** The node read of one call: the diagram group, then the repository folder. */
    private void nodeRead() {
        harness.exportGroup().exportRepository(node, "/Root/jdoe/xsd");
    }

    private ImportPreview preview() {
        nodeRead();
        ImportService.Response response = service.importArtifacts(harness.group("Schemas"));
        harness.cli.verifyComplete();
        assertThat(response).isInstanceOf(ImportService.Response.Challenge.class);
        ImportPreview preview = ((ImportService.Response.Challenge) response).preview();
        assertThat(preview.create()).as("b.xsd is absent on the node").contains(B);
        assertThat(preview.modify()).containsExactly("Workflow-0001", "Module-0003", A);
        return preview;
    }

    private ToolError confirmedRefusal(ImportPreview preview) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> service.importArtifacts(ImportHarness.confirmed(harness.group("Schemas"),
                preview.confirmationCode())));
        assertThat(exception).as("expected a refusal").isNotNull();
        harness.cli.verifyComplete();
        assertThat(harness.inubit.imported).as("nothing was imported").isEmpty();
        assertThat(node.repositoryImports).as("nothing was imported").isEmpty();
        assertThat(harness.audit("import_artifacts")).last().satisfies(record ->
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED));
        return exception.error();
    }

    @Test
    void aRepositoryFileCreatedOnTheNodeAfterThePreviewIsAConflict() {
        // b.xsd is NEW: the node read classifies it without a conflict of its own, only the
        // fingerprint of the preview tells that the node changed
        ImportPreview preview = preview();
        node.putRepositoryFile(B, schema("someone else's b"));
        nodeRead();

        ToolError error = confirmedRefusal(preview);

        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(error.message()).contains("after the preview", "nothing was sent");
    }

    @Test
    void aRepositoryFileChangedOnTheNodeAfterThePreviewIsAConflict() {
        ImportPreview preview = preview();
        node.putRepositoryFile(A, schema("changed on the node"));
        nodeRead();

        ToolError error = confirmedRefusal(preview);

        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(error.message()).contains(A, ".reports/conflict-");
    }

    @Test
    void aRepositoryFileEditedInTheWorkspaceAfterThePreviewNeedsANewPreview() {
        // only the repository file changes: its content is part of the workspace hash
        ImportPreview preview = preview();
        harness.edit(file(B), "name=\"b\"", "name=\"b2\"");

        ToolError error = confirmedRefusal(preview);

        assertThat(error.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        assertThat(error.message()).contains("workspace changed", "preview again");
        assertThat(harness.cli.launches()).as("only the node read of the preview").hasSize(2);
    }
}
