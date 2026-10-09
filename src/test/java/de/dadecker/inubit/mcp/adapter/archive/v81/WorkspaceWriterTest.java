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
                ".meta/dev/OWNERS/repository/Root/OWNERS/xsd/core.xsd.json",
                ".meta/dev/OWNERS/repository/Root/OWNERS/keys/fixture-client.p12.json");
        assertThat(text(rendered, ".meta/dev/OWNERS/repository/Root/OWNERS/xsd/msg.xsd.json"))
            .contains("contentMD5");

        Map<String, byte[]> entries = new LinkedHashMap<>(ArtifactFixtures.entries("grp-b.zip"));
        for (String module : List.of("module/module-0027.xml", "module/module-0018.xml")) {
            entries.put(module, new String(entries.get(module), StandardCharsets.UTF_8)
                .replace("inubitrepository:/Root/OWNERS/", "urn:none:")
                .getBytes(StandardCharsets.UTF_8));
        }
        assertThat(WorkspaceWriter.render(redacted(ArtifactFixtures.zip(entries)), DEV,
            "OWNERS").files().keySet()).noneMatch(p -> p.contains("/repository/"));
    }

    @Test
    void theRenderedBytesCannotBeChangedFromOutside() {
        // stage-2 minor 3: write() must only see what render() produced
        Rendered rendered = WorkspaceWriter.render(redacted("grp-a.zip"), DEV, "jdoe");
        String path = "dev/jdoe/workflows/GRP-01/Workflow-0001.xml";
        byte[] before = rendered.files().get(path).clone();

        rendered.files().get(path)[0] ^= 1;

        assertThat(rendered.files().get(path)).isEqualTo(before);
    }

    @Test
    void keyMaterialIsNeitherWrittenNorDecoded() {
        // review I3: the repository keystore stays out, .meta holds a placeholder instead
        Rendered rendered = WorkspaceWriter.render(redacted("grp-b.zip"), DEV, "OWNERS");

        assertThat(rendered.files()).doesNotContainKey(
            "dev/OWNERS/repository/Root/OWNERS/keys/fixture-client.p12");
        assertThat(text(rendered,
            ".meta/dev/OWNERS/repository/Root/OWNERS/keys/fixture-client.p12.json"))
            .contains("\"withheld\" : \"${secret:Repository/Root/OWNERS/keys/"
                + "fixture-client.p12}\"")
            .contains("fixture-client.p12").doesNotContain("contentMD5", "contentSize");
        assertThat(rendered.warnings()).contains("1 repository file(s) with key material were"
            + " not written; .meta holds a placeholder");
        assertThat(rendered.files().keySet()).filteredOn(p -> p.contains("Module-0018/"))
            .noneMatch(p -> p.contains("partnerTrustStore"));
        assertThat(text(rendered, "dev/OWNERS/modules/JSON Validator/Module-0018/module.xml"))
            .contains(">${secret:partnerTrustStore}</Property>");
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

    /** {@code grp-a.zip} without {@code Module-0009} in its index and files. */
    private static byte[] grpAWithoutModule0009() {
        Map<String, byte[]> entries = new LinkedHashMap<>(ArtifactFixtures.entries("grp-a.zip"));
        entries.remove("module/module-0009.xml");
        entries.put("module/module.xml", new String(entries.get("module/module.xml"),
            StandardCharsets.UTF_8).replaceFirst("(?s)<Module type=\"technical\""
                + " version=\"head\">\\s*<ModuleName>Module-0009</ModuleName>.*?</Module>\\s*", "")
            .getBytes(StandardCharsets.UTF_8));
        return ArtifactFixtures.zip(entries);
    }

    @Test
    void aModuleNoLongerInTheDiagramGroupIsRemoved() {
        WorkspaceWriter.write(root, WorkspaceWriter.render(redacted("grp-a.zip"), DEV, "jdoe"));
        Path module = root.resolve("dev/jdoe/modules/Assign/Module-0009");
        Path meta = root.resolve(".meta/dev/jdoe/modules/Assign/Module-0009");
        assertThat(module).isDirectory();
        assertThat(meta).isDirectory();

        WorkspaceWriter.write(root, WorkspaceWriter.render(redacted(grpAWithoutModule0009()), DEV,
            "jdoe"));

        assertThat(module).as("no longer exported (FR-017)").doesNotExist();
        assertThat(meta).doesNotExist();
        assertThat(root.resolve("dev/jdoe/modules/Assign/Module-0008")).isDirectory();
    }

    @Test
    void aModuleStillExportedByAnotherRecordIsKept() throws IOException {
        WorkspaceWriter.write(root, WorkspaceWriter.render(redacted("grp-a.zip"), DEV, "jdoe"));
        Map<String, byte[]> entries = new LinkedHashMap<>(ArtifactFixtures.entries("grp-a.zip"));
        entries.put("workflow/workflow.xml", new String(entries.get("workflow/workflow.xml"),
            StandardCharsets.UTF_8).replace("GRP-01", "GRP-03").getBytes(
                StandardCharsets.UTF_8));
        WorkspaceWriter.write(root, WorkspaceWriter.render(redacted(ArtifactFixtures.zip(entries)),
            DEV, "jdoe"));

        WorkspaceWriter.write(root, WorkspaceWriter.render(redacted(grpAWithoutModule0009()), DEV,
            "jdoe"));

        assertThat(root.resolve("dev/jdoe/modules/Assign/Module-0009"))
            .as("still part of the export of GRP-03").isDirectory();
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

    // --- feature 007: INUBIT writes a module's connections in any order (contract P-6) -------

    private static final String WORKFLOW = "dev/jdoe/workflows/GRP-01/Workflow-0001.xml";
    /** The connections of Workflow-0001/Module-0002 as the export archive holds them. */
    private static final String PAIR = "<Connection moduleOutId=\"4\"><ConnectionId>9"
        + "</ConnectionId></Connection><Connection moduleOutId=\"3\"><ConnectionId>6"
        + "</ConnectionId></Connection>";
    private static final String SWAPPED = "<Connection moduleOutId=\"3\"><ConnectionId>6"
        + "</ConnectionId></Connection><Connection moduleOutId=\"4\"><ConnectionId>9"
        + "</ConnectionId></Connection>";

    /** {@code grp-a.zip} with the workflow archive changed by {@code change}. */
    private static Rendered grpA(java.util.function.UnaryOperator<String> change) {
        Map<String, byte[]> entries = new LinkedHashMap<>(ArtifactFixtures.entries("grp-a.zip"));
        String workflows = new String(entries.get("workflow/workflow.xml"),
            StandardCharsets.UTF_8);
        String changed = change.apply(workflows);
        assertThat(changed).isNotEqualTo(workflows);
        entries.put("workflow/workflow.xml", changed.getBytes(StandardCharsets.UTF_8));
        return WorkspaceWriter.render(redacted(ArtifactFixtures.zip(entries)), DEV, "jdoe");
    }

    @Test
    void aWorkflowFileThatDiffersOnlyInConnectionOrderIsKept() throws IOException {
        WorkspaceWriter.write(root, WorkspaceWriter.render(redacted("grp-a.zip"), DEV, "jdoe"));
        byte[] before = Files.readAllBytes(root.resolve(WORKFLOW));
        Rendered swapped = grpA(xml -> xml.replace(PAIR, SWAPPED));
        assertThat(swapped.files().get(WORKFLOW)).isNotEqualTo(before);

        WorkspaceWriter.write(root, swapped);

        assertThat(Files.readAllBytes(root.resolve(WORKFLOW))).isEqualTo(before);
    }

    @Test
    void aRealDifferenceBesideAnotherConnectionOrderIsWrittenExactly() throws IOException {
        WorkspaceWriter.write(root, WorkspaceWriter.render(redacted("grp-a.zip"), DEV, "jdoe"));
        Rendered changed = grpA(xml -> xml.replace(PAIR, SWAPPED.replace(">9<", ">10<")));

        WorkspaceWriter.write(root, changed);

        assertThat(Files.readAllBytes(root.resolve(WORKFLOW)))
            .isEqualTo(changed.files().get(WORKFLOW));
    }

    @Test
    void aNewWorkflowFileIsWrittenAsRendered() throws IOException {
        Rendered swapped = grpA(xml -> xml.replace(PAIR, SWAPPED));

        WorkspaceWriter.write(root, swapped);

        assertThat(Files.readAllBytes(root.resolve(WORKFLOW)))
            .isEqualTo(swapped.files().get(WORKFLOW));
    }

    @Test
    void aWorkflowFileWithOtherFormattingIsRewrittenEvenIfOnlyTheOrderDiffers()
        throws IOException {
        // only a genuine rendering is kept: a file formatted otherwise gets the new rendering
        WorkspaceWriter.write(root, WorkspaceWriter.render(redacted("grp-a.zip"), DEV, "jdoe"));
        Path file = root.resolve(WORKFLOW);
        Files.writeString(file, Files.readString(file).replace("\n  <", "\n    <"));
        Rendered swapped = grpA(xml -> xml.replace(PAIR, SWAPPED));

        WorkspaceWriter.write(root, swapped);

        assertThat(Files.readAllBytes(file)).isEqualTo(swapped.files().get(WORKFLOW));
    }
}
