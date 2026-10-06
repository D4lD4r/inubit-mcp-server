package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleIndexEntry;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.RepositoryFile;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowGroupXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Attribute;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** T012 (research D-4): parsing a StartCLI export ZIP into the in-memory archive model. */
class ArchiveReaderTest {

    private final ArchiveReader reader = new ArchiveReader();

    private static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static Map<String, byte[]> moduleOnly() {
        return new LinkedHashMap<>(ArtifactFixtures.entries("module-one.zip"));
    }

    private static void assertUnexpected(Runnable read, String... messageParts) {
        assertThatThrownBy(read::run).isInstanceOfSatisfying(ToolErrorException.class, e -> {
            assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
            assertThat(e.error().message()).contains(messageParts);
        });
    }

    @Test
    void readsADiagramGroupExportWithItsContext() {
        ExportArchive archive = reader.read(ArtifactFixtures.bytes("grp-a.zip"));

        assertThat(archive.properties()).containsEntry("sourceVersion", "8.1.17")
            .containsKey("operationId");
        assertThat(archive.entries()).startsWith("archive.properties", "Repository.zip",
            "workflow/workflow.xml").hasSize(13);
        assertThat(archive.workflowGroups()).singleElement().satisfies(group -> {
            assertThat(group.name()).isEqualTo("GRP-01");
            assertThat(group.attributes()).containsExactly(
                new Attribute("", "workflowType", "", "technical"));
        });
        List<WorkflowXml> workflows = archive.workflowGroups().get(0).workflows();
        assertThat(workflows).extracting(WorkflowXml::name)
            .containsExactly("Workflow-0001", "Workflow-0002");
        assertThat(workflows).allSatisfy(workflow -> {
            assertThat(workflow.diagramGroup()).isEqualTo("GRP-01");
            assertThat(workflow.element().localName()).isEqualTo("Workflow");
            assertThat(workflow.context().documentVersion()).isEqualTo("5.3");
            assertThat(workflow.context().groupPosition()).isZero();
        });
        assertThat(workflows).extracting(w -> w.context().position()).containsExactly(0, 1);
        assertThat(archive.moduleIndex()).hasSize(9).extracting(ModuleIndexEntry::pluginType)
            .containsOnly("Assign", "Demultiplexer", "XSLT Converter");
        assertThat(archive.moduleFiles()).hasSize(9);
        ModuleXml converter = archive.moduleFiles().get("Module-0001");
        assertThat(converter.pluginType()).isEqualTo("XSLT Converter");
        assertThat(converter.entryName()).isEqualTo("module/module-0001.xml");
        assertThat(converter.element().localName()).isEqualTo("Properties");
        assertThat(archive.repository()).isEmpty();
    }

    @Test
    void readsTheModuleIndexWithPositions() {
        ExportArchive archive = reader.read(ArtifactFixtures.bytes("grp-b.zip"));

        assertThat(archive.workflowGroups()).singleElement().extracting(WorkflowGroupXml::name)
            .isEqualTo("GRP-02");
        assertThat(archive.workflowGroups().get(0).workflows()).hasSize(4);
        assertThat(archive.moduleIndex()).hasSize(19);
        ModuleIndexEntry first = archive.moduleIndex().get(0);
        assertThat(first.name()).isEqualTo("Module-0024");
        assertThat(first.pluginType()).isEqualTo("AS2 Connector");
        assertThat(first.documentVersion()).isEqualTo("5.3");
        assertThat(first.groupPosition()).isZero();
        assertThat(first.position()).isZero();
        assertThat(first.element().localName()).isEqualTo("Module");
        assertThat(archive.moduleFiles().keySet())
            .containsExactlyInAnyOrderElementsOf(archive.moduleIndex().stream()
                .map(ModuleIndexEntry::name).toList());
    }

    @Test
    void readsTheRepositoryFilesOfTheNestedArchive() {
        ExportArchive archive = reader.read(ArtifactFixtures.bytes("grp-b.zip"));

        assertThat(archive.repository()).containsOnlyKeys("Root/OWNERS/xsd/msg.xsd",
            "Root/OWNERS/xsd/core.xsd");
        RepositoryFile msg = archive.repository().get("Root/OWNERS/xsd/msg.xsd");
        assertThat(new String(msg.content(), StandardCharsets.UTF_8)).startsWith("<?xml")
            .contains("urn:example:fixture:msg");
        assertThat(msg.metadata()).hasValueSatisfying(meta -> assertThat(meta.attributes())
            .contains(new Attribute("", "path", "", "/Root/OWNERS/xsd/msg.xsd")));
    }

    @Test
    void acceptsTheEmptyWorkflowDirectoryOfAModuleOnlyExport() {
        ExportArchive archive = reader.read(ArtifactFixtures.bytes("module-one.zip"));

        assertThat(archive.entries()).contains("workflow/");
        assertThat(archive.workflowGroups()).isEmpty();
        assertThat(archive.moduleIndex()).singleElement().satisfies(entry -> {
            assertThat(entry.name()).isEqualTo("Module-0023");
            assertThat(entry.pluginType()).isEqualTo("XSLT Converter");
        });
        assertThat(archive.moduleFiles()).containsOnlyKeys("Module-0023");
        assertThat(archive.repository()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../evil.xml", "/etc/evil.xml", "module/../../evil.xml",
        "module\\..\\evil.xml", "C:/evil.xml", "module/./x.xml"})
    void refusesEntryNamesThatLeaveTheArchive(String name) {
        Map<String, byte[]> entries = moduleOnly();
        entries.put(name, "<Properties/>".getBytes(StandardCharsets.UTF_8));

        assertUnexpected(() -> reader.read(zip(entries)), "entry name");
    }

    @Test
    void refusesRepositoryEntryNamesThatLeaveTheArchive() {
        Map<String, byte[]> entries = moduleOnly();
        entries.put("Repository.zip", zip(Map.of("../../x.dat", new byte[] {1})));

        assertUnexpected(() -> reader.read(zip(entries)), "entry name");
    }

    @Test
    void refusesEntriesItDoesNotKnow() {
        Map<String, byte[]> entries = moduleOnly();
        entries.put("versionHistory.xml", "<x/>".getBytes(StandardCharsets.UTF_8));

        assertUnexpected(() -> reader.read(zip(entries)), "versionHistory.xml");
    }

    @Test
    void refusesAModuleFileWithoutIndexEntry() {
        Map<String, byte[]> entries = moduleOnly();
        entries.put("module/module-9999.xml", "<Properties/>".getBytes(StandardCharsets.UTF_8));

        assertUnexpected(() -> reader.read(zip(entries)), "module/module-9999.xml");
    }

    @Test
    void anEntryLargerThanTheLimitIsRefused() {
        Map<String, byte[]> entries = moduleOnly();
        entries.put("module/module-0023.xml", ("<Properties>" + "x".repeat(4096)
            + "</Properties>").getBytes(StandardCharsets.UTF_8));

        assertUnexpected(() -> new ArchiveReader(4096).read(zip(entries)), "too large");
    }

    @Test
    void anArchiveLargerThanTheLimitInTotalIsRefused() {
        byte[] fixture = ArtifactFixtures.bytes("grp-b.zip");
        long total = ArtifactFixtures.entries(fixture).values().stream()
            .mapToLong(b -> b.length).sum();
        long largest = ArtifactFixtures.entries(fixture).values().stream()
            .mapToLong(b -> b.length).max().orElseThrow();

        assertThat(largest).isLessThan(total - 1);
        assertUnexpected(() -> new ArchiveReader(total - 1).read(fixture), "too large");
    }

    @Test
    void anUnreadableArchiveOrDocumentIsRefused() {
        assertUnexpected(() -> reader.read("not a zip".getBytes(StandardCharsets.UTF_8)),
            "export");
        Map<String, byte[]> entries = moduleOnly();
        entries.put("module/module.xml", "<IBISWorkflow>".getBytes(StandardCharsets.UTF_8));
        assertUnexpected(() -> reader.read(zip(entries)), "module/module.xml");
    }

    @Test
    void anArchiveWithoutModuleIndexIsRefused() {
        Map<String, byte[]> entries = moduleOnly();
        entries.remove("module/module.xml");
        entries.remove("module/module-0023.xml");

        assertUnexpected(() -> reader.read(zip(entries)), "module/module.xml");
    }
}
