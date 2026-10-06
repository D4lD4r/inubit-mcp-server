package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.archive.v81.WorkspaceWriter.Rendered;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T017 (research D-2 – D-6, FR-012 – FR-017): the workspace files of an export. */
class WorkspaceWriterTest {

    private static final GroupId DEV = new GroupId("dev");

    @TempDir
    Path root;

    static RedactedArchive redacted(byte[] zip) {
        return new SecretRedactor().redact(new ArchiveReader().read(zip));
    }

    static RedactedArchive redacted(String fixture) {
        return redacted(ArtifactFixtures.bytes(fixture));
    }

    private static String text(Rendered rendered, String path) {
        assertThat(rendered.files()).containsKey(path);
        return new String(rendered.files().get(path), StandardCharsets.UTF_8);
    }

    private List<String> filesOnDisk() throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).map(p -> root.relativize(p).toString())
                .sorted().toList();
        }
    }

    @Test
    void aDiagramGroupBecomesWorkflowModuleAndEmbeddedFiles() {
        Rendered rendered = WorkspaceWriter.render(redacted("grp-a.zip"), DEV, "jdoe");

        assertThat(rendered.files().keySet()).contains(
            "dev/jdoe/workflows/GRP-01/Workflow-0001.xml",
            "dev/jdoe/workflows/GRP-01/Workflow-0002.xml",
            "dev/jdoe/modules/XSLT Converter/Module-0001/module.xml",
            "dev/jdoe/modules/XSLT Converter/Module-0001/index.xml",
            "dev/jdoe/modules/XSLT Converter/Module-0001/xslt.stylesheet.xsl",
            "dev/jdoe/modules/Demultiplexer/Module-0006/module.xml",
            ".meta/dev/jdoe/workflows/GRP-01/Workflow-0002.xml.json",
            ".meta/dev/jdoe/modules/Assign/Module-0003/index.xml.json",
            ".meta/dev/jdoe/exports/workflows/GRP-01.json");
        assertThat(rendered.files().keySet()).filteredOn(p -> p.endsWith("/index.xml"))
            .hasSize(9);
        assertThat(text(rendered, "dev/jdoe/workflows/GRP-01/Workflow-0002.xml"))
            .startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Workflow ")
            .contains("<CheckoutUser>jdoe</CheckoutUser>", "<WorkflowUId/>")
            .doesNotContain("Deploying User");
        assertThat(text(rendered, "dev/jdoe/modules/XSLT Converter/Module-0001/module.xml"))
            .contains("<Property name=\"xslt.stylesheet\" type=\"XmlDocument\">"
                + "@file:xslt.stylesheet.xsl</Property>");
        assertThat(text(rendered,
            "dev/jdoe/modules/XSLT Converter/Module-0001/xslt.stylesheet.xsl"))
            .startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<xsl:stylesheet");
        assertThat(text(rendered, ".meta/dev/jdoe/workflows/GRP-01/Workflow-0002.xml.json"))
            .contains("\"WorkflowUId\"", "\"CheckinComment.exportSuffix\"", "\"context\"")
            .doesNotContain("Export/Deployment");
        assertThat(rendered.subtrees()).contains("dev/jdoe/workflows/GRP-01",
            ".meta/dev/jdoe/workflows/GRP-01", "dev/jdoe/modules/Assign/Module-0003");
        assertThat(rendered.warnings())
            .containsExactly("Workflow Workflow-0002 is in edit mode by jdoe (CheckoutUser)");
    }

    @Test
    void onlyReferencedRepositoryFilesAreWritten() {
        Rendered rendered = WorkspaceWriter.render(redacted("grp-b.zip"), DEV, "OWNERS");

        assertThat(rendered.files().keySet()).filteredOn(p -> p.contains("/repository/"))
            .containsExactlyInAnyOrder("dev/OWNERS/repository/Root/OWNERS/xsd/msg.xsd",
                "dev/OWNERS/repository/Root/OWNERS/xsd/core.xsd",
                ".meta/dev/OWNERS/repository/Root/OWNERS/xsd/msg.xsd.json",
                ".meta/dev/OWNERS/repository/Root/OWNERS/xsd/core.xsd.json");
        assertThat(text(rendered, ".meta/dev/OWNERS/repository/Root/OWNERS/xsd/msg.xsd.json"))
            .contains("contentMD5");

        Map<String, byte[]> entries = new LinkedHashMap<>(ArtifactFixtures.entries("grp-b.zip"));
        for (String module : List.of("module/module-0027.xml")) {
            entries.put(module, new String(entries.get(module), StandardCharsets.UTF_8)
                .replace("inubitrepository:/Root/OWNERS/xsd/", "urn:none:")
                .getBytes(StandardCharsets.UTF_8));
        }
        assertThat(WorkspaceWriter.render(redacted(ArtifactFixtures.zip(entries)), DEV,
            "OWNERS").files().keySet()).noneMatch(p -> p.contains("/repository/"));
    }

    @Test
    void aModuleOnlyExportWritesTheModuleAndAnExportRecord() {
        Rendered rendered = WorkspaceWriter.render(redacted("module-smime.zip"), DEV, "OWNERS");

        assertThat(rendered.files().keySet()).containsExactlyInAnyOrder(
            "dev/OWNERS/modules/SMIME/Module-0029/module.xml",
            "dev/OWNERS/modules/SMIME/Module-0029/index.xml",
            ".meta/dev/OWNERS/modules/SMIME/Module-0029/module.xml.json",
            ".meta/dev/OWNERS/modules/SMIME/Module-0029/index.xml.json",
            ".meta/dev/OWNERS/exports/modules/SMIME/Module-0029.json");
        assertThat(text(rendered, "dev/OWNERS/modules/SMIME/Module-0029/module.xml"))
            .contains("${secret:smime.keystore.data}");
    }

    @Test
    void renderingAndWritingTheSameArchiveTwiceGivesIdenticalBytes() throws IOException {
        Rendered first = WorkspaceWriter.render(redacted("grp-b.zip"), DEV, "OWNERS");
        Rendered second = WorkspaceWriter.render(redacted("grp-b.zip"), DEV, "OWNERS");
        assertThat(second.files().keySet()).isEqualTo(first.files().keySet());
        first.files().forEach((path, bytes) -> assertThat(second.files().get(path)).as(path)
            .isEqualTo(bytes));

        WorkspaceWriter.write(root, first);
        List<String> once = filesOnDisk();
        Map<String, byte[]> content = new LinkedHashMap<>();
        for (String file : once) {
            content.put(file, Files.readAllBytes(root.resolve(file)));
        }
        WorkspaceWriter.write(root, second);

        assertThat(filesOnDisk()).isEqualTo(once).hasSize(first.files().size());
        for (String file : once) {
            assertThat(Files.readAllBytes(root.resolve(file))).as(file)
                .isEqualTo(content.get(file));
        }
    }

    @Test
    void writingReplacesTheExportedSubtreesOnly() throws IOException {
        WorkspaceWriter.write(root, WorkspaceWriter.render(redacted("grp-a.zip"), DEV, "jdoe"));
        Path other = root.resolve("dev/jdoe/workflows/GRP-09/Other.xml");
        Files.createDirectories(other.getParent());
        Files.writeString(other, "<Workflow/>");
        Path local = root.resolve("dev/jdoe/workflows/GRP-01/Removed-In-Workbench.xml");
        Files.writeString(local, "<Workflow/>");

        WorkspaceWriter.write(root, WorkspaceWriter.render(redacted("grp-a.zip"), DEV, "jdoe"));

        assertThat(local).as("no longer exported (FR-017)").doesNotExist();
        assertThat(other).as("another diagram group").exists();
        assertThat(root.resolve("dev/jdoe/workflows/GRP-01/Workflow-0001.xml")).exists();
    }

    @Test
    void namesThatDifferOnlyInCaseAreRefused() {
        Map<String, byte[]> entries = new LinkedHashMap<>(ArtifactFixtures.entries("grp-a.zip"));
        entries.put("workflow/workflow.xml", new String(entries.get("workflow/workflow.xml"),
            StandardCharsets.UTF_8).replace("<WorkflowName>Workflow-0002</WorkflowName>",
                "<WorkflowName>WORKFLOW-0001</WorkflowName>").getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> WorkspaceWriter.render(redacted(ArtifactFixtures.zip(entries)),
            DEV, "jdoe")).isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.error().message()).contains("Workflow-0001.xml",
                    "WORKFLOW-0001.xml");
            });
    }

    @Test
    void aModuleListedTwiceIsWrittenOnceWithBothPositions() {
        Map<String, byte[]> entries = new LinkedHashMap<>(
            ArtifactFixtures.entries("module-one.zip"));
        String index = new String(entries.get("module/module.xml"), StandardCharsets.UTF_8);
        String module = index.substring(index.indexOf("<Module "),
            index.indexOf("</Module>") + "</Module>".length());
        entries.put("module/module.xml", index.replace(module, module + module)
            .getBytes(StandardCharsets.UTF_8));

        Rendered rendered = WorkspaceWriter.render(redacted(ArtifactFixtures.zip(entries)), DEV,
            "OWNERS");

        assertThat(rendered.files().keySet()).filteredOn(p -> p.endsWith("index.xml"))
            .hasSize(1);
        assertThat(text(rendered,
            ".meta/dev/OWNERS/modules/XSLT Converter/Module-0023/index.xml.json"))
            .contains("\"positions\"").containsPattern("(?s)\"position\" : 0.*\"position\" : 1");
    }
}
