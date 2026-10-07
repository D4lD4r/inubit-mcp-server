package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ReleaseArchivePort.PropertyChange;
import de.dadecker.inubit.mcp.domain.port.ReleaseArchivePort.ReleaseExport;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import org.junit.jupiter.api.Test;

/**
 * T016/T017 (feature 005, research D-4, D-5): the release export in the shape the codec reads,
 * versions, and the comparisons the classification needs.
 */
class V81ReleaseArchivesTest {

    private final V81ReleaseArchives archives = new V81ReleaseArchives();

    private static byte[] cliFixture(String name) {
        try (InputStream in = V81ReleaseArchivesTest.class.getResourceAsStream(
            "/fixtures/v8_1/cli/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void aReleaseExportLosesTheTracesOfTheTagAndKeepsItsVersions() {
        ReleaseExport release = archives.normalize(cliFixture("export_release.zip"));

        Map<String, byte[]> entries = ArtifactFixtures.entries(release.export());
        assertThat(entries).doesNotContainKey("usertags.xml").containsKeys("Repository.zip",
            "workflow/workflow.xml", "module/module.xml", "module/module-0005.xml");
        String workflows = text(entries.get("workflow/workflow.xml"));
        String index = text(entries.get("module/module.xml"));
        assertThat(workflows + index).doesNotContain("tag=\"", "@@@Tag:")
            .contains("@@@Version: 11@@@Export/Deployment:");
        assertThat(workflows).contains("<Workflow version=\"head\"");
        assertThat(index).doesNotContain("version=\"1\"", "version=\"12\"");
        assertThat(release.diagramGroups()).containsExactly("GRP-01");
        assertThat(release.taggedVersions()).containsEntry("Workflow-0001", 1)
            .containsEntry("Workflow-0002", 11).containsEntry("Module-0003", 1);
        // the codec reads it
        SortedMap<String, byte[]> files = new ArchiveCodec().prepare(new GroupId("int"), "jdoe",
            List.of(release.export())).files();
        assertThat(files).containsKeys("int/jdoe/workflows/GRP-01/Workflow-0001.xml",
            "int/jdoe/repository/Root/jdoe/xsd/release.xsl");
    }

    @Test
    void anUnreadableReleaseIsAnUnexpectedResponse() {
        assertThatThrownBy(() -> archives.normalize(new byte[] {1, 2, 3}))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(e.error().code())
                .isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }

    @Test
    void theVersionsOfAHeadExportComeFromTheCheckinComments() {
        assertThat(archives.versions(ArtifactFixtures.bytes("grp-a.zip")))
            .containsEntry("Workflow-0001", 1).containsEntry("Workflow-0002", 11)
            .containsEntry("Module-0003", 1);
    }

    private static final String WORKFLOW = "int/jdoe/workflows/GRP-01/Workflow-0001.xml";

    private static byte[] workflow(String body) {
        return ("<Workflow version=\"head\" workflowType=\"technical\"><WorkflowName>W"
            + "</WorkflowName><CheckinComment>a</CheckinComment><IsActive>false</IsActive>"
            + body + "</Workflow>").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void comparesReviewedContentIgnoringCommentsAndTheActiveFlag() {
        byte[] release = workflow("<WorkflowModule><ModuleName>M1</ModuleName>"
            + "<StyleSheet xPos=\"1\"/></WorkflowModule>");
        byte[] target = text(release).replace("<CheckinComment>a", "<CheckinComment>b")
            .replace("false", "true").getBytes(StandardCharsets.UTF_8);
        byte[] moved = text(release).replace("xPos=\"1\"", "xPos=\"2\"")
            .getBytes(StandardCharsets.UTF_8);

        assertThat(archives.equivalent(WORKFLOW, release, target)).isTrue();
        assertThat(archives.equivalent(WORKFLOW, release, moved)).isFalse();
        assertThat(archives.layoutOnly(WORKFLOW, release, moved)).isTrue();
        assertThat(archives.modulesOf(release)).containsExactly("M1");
        // fingerprints keep the flag but not the comment
        assertThat(archives.canonical(WORKFLOW, release)).isEqualTo(archives.canonical(WORKFLOW,
            text(release).replace("<CheckinComment>a", "<CheckinComment>b")
                .getBytes(StandardCharsets.UTF_8)));
        assertThat(archives.canonical(WORKFLOW, release)).isNotEqualTo(
            archives.canonical(WORKFLOW, target));
    }

    @Test
    void namesChangedSimplePropertiesInDocumentOrder() {
        byte[] release = ("<Properties version=\"4.1\"><Property name=\"Url\">"
            + "https://a.example.test</Property><Property name=\"Same\">x</Property>"
            + "<Property name=\"Port\">1</Property></Properties>").getBytes(StandardCharsets.UTF_8);
        byte[] target = text(release).replace("a.example", "b.example").replace(">1<", ">2<")
            .getBytes(StandardCharsets.UTF_8);

        assertThat(archives.changedProperties("int/jdoe/modules/Assign/M1/module.xml", release,
            target)).containsExactly(new PropertyChange("Url", "https://a.example.test",
                "https://b.example.test"), new PropertyChange("Port", "1", "2"));
    }

    @Test
    void certificatesAndKeysAreKeyMaterial() {
        assertThat(archives.keyMaterial("/Root/jdoe/keys/a.cer", new byte[] {1})).isTrue();
        assertThat(archives.keyMaterial("/Root/jdoe/xsd/a.xsd", "<x/>".getBytes(
            StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void readsTheRepositoryFilesOfARepositoryOrAGroupExport() {
        assertThat(archives.repositoryFiles(cliFixture("export_repository.zip")).keySet())
            .containsExactly("/Root/jdoe/xsd/order.xsd", "/Root/jdoe/xsd/release.xsl");
        assertThat(archives.repositoryFiles(cliFixture("export_release.zip")).keySet())
            .containsExactly("/Root/jdoe/xsd/release.xsl");
        assertThat(archives.repositoryFiles(ArtifactFixtures.bytes("grp-a.zip"))).isEmpty();
    }

    @Test
    void buildsTheRepositoryImportOfChosenFilesRelativeToTheOwnersRoot() {
        // T021 (research D-1, D-7): entries relative to /Root/<owner>, never key material
        byte[] zip = archives.repositoryArchive(List.of(cliFixture("export_release.zip"),
            cliFixture("export_repository.zip")), "jdoe", List.of("/Root/jdoe/xsd/order.xsd"));

        assertThat(ArtifactFixtures.entries(zip).keySet()).containsExactly("xsd/",
            "xsd/order.xsd.xml", "xsd/order.xsd.dat");
        // the first archive that has a path wins
        byte[] release = archives.repositoryArchive(List.of(cliFixture("export_release.zip"),
            cliFixture("export_repository.zip")), "jdoe", List.of("/Root/jdoe/xsd/release.xsl"));
        assertThat(text(ArtifactFixtures.entries(release).get("xsd/release.xsl.dat")))
            .contains("'v1'");
        assertThatThrownBy(() -> archives.repositoryArchive(List.of(cliFixture(
            "export_repository.zip")), "jdoe", List.of("/Root/jdoe/xsd/missing.xsd")))
            .isInstanceOf(ToolErrorException.class);
    }
}

