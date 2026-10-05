package de.dadecker.inubit.mcp.adapter.rest.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.DiagramMetadata;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * T082: the head metadata of a diagram from {@code GET /ibis/rest/model/export/<name>}, a ZIP
 * without {@code Content-Type} (S-6b) parsed in memory; only {@code workflow/workflow.xml} is
 * read.
 */
class DiagramExportParserTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String WORKFLOW_XML = "<IBISWorkflow version='5.3'><Workflows>"
        + "<WorkflowGroup workflowType='technical'><WorkflowGroupName>G</WorkflowGroupName>"
        + "<Workflow version='head'><WorkflowName>W</WorkflowName>"
        + "<UserOrUserGroupName>OWNERS</UserOrUserGroupName>"
        + "<CheckinComment>head comment</CheckinComment>"
        + "<WorkflowModule><CheckinComment>module comment</CheckinComment>"
        + "<IsActive>false</IsActive></WorkflowModule>"
        + "<IsActive>true</IsActive></Workflow></WorkflowGroup></Workflows></IBISWorkflow>";

    static byte[] zip(Map<String, String> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    @Test
    void theRecordedExportGivesActiveFlagCheckinCommentAndOwner() {
        DiagramMetadata metadata = DiagramExportParser.parse(DEV,
            RestFixtures.bytes("model_export_sample.zip"));

        assertThat(metadata).isEqualTo(new DiagramMetadata(Optional.of(true),
            Optional.of("[message 107]"), Optional.of("OWNERS")));
    }

    @Test
    void theParserWorksOnTheBytesInMemoryAndHasNoFileApi() {
        assertThat(Arrays.stream(DiagramExportParser.class.getDeclaredMethods())
            .map(Method::getParameterTypes).flatMap(Arrays::stream))
            .doesNotContain(Path.class, java.io.File.class);
    }

    @Test
    void onlyTheWorkflowEntryIsReadAndOtherEntriesAreIgnored() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("archive.properties", "sourceVersion=8.1.17");
        entries.put("module/module.xml", "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]>");
        entries.put("module/Secret.xml", "not xml at all <<<");
        entries.put("workflow/workflow.xml", WORKFLOW_XML);

        DiagramMetadata metadata = DiagramExportParser.parse(DEV, zip(entries));

        assertThat(metadata).as("the workflow's own fields, not those of a module subtree")
            .isEqualTo(new DiagramMetadata(Optional.of(true), Optional.of("head comment"),
                Optional.of("OWNERS")));
    }

    @Test
    void anExportWithoutWorkflowEntryHasNoMetadata() {
        assertThat(DiagramExportParser.parse(DEV, zip(Map.of("bpd/bpd.xml", "<x/>"))))
            .isEqualTo(DiagramMetadata.EMPTY);
    }

    @Test
    void aBodyWithoutTheZipSignatureIsAnUnexpectedResponse() {
        byte[] html = "<html>login</html>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> DiagramExportParser.parse(DEV, html))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().node()).contains(DEV);
                assertThat(e.error().message()).contains("ZIP");
            });
    }

    @Test
    void aCorruptZipOrAnOversizedOrMalformedWorkflowEntryIsAnUnexpectedResponse() {
        byte[] valid = zip(Map.of("workflow/workflow.xml", WORKFLOW_XML));
        byte[] corrupt = Arrays.copyOf(valid, 40);

        assertThatThrownBy(() -> DiagramExportParser.parse(DEV, corrupt))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
        assertThatThrownBy(() -> DiagramExportParser.parse(DEV, valid, 100))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().message()).contains("too large");
            });
        byte[] doctype = zip(Map.of("workflow/workflow.xml",
            "<!DOCTYPE x [<!ENTITY e 'x'>]><IBISWorkflow/>"));
        assertThatThrownBy(() -> DiagramExportParser.parse(DEV, doctype))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().node()).contains(DEV);
            });
    }
}
