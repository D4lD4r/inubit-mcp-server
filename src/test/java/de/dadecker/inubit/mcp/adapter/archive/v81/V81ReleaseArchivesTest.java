package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.RepositoryUpload;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ReleaseArchivePort.PropertyChange;
import de.dadecker.inubit.mcp.domain.port.ReleaseArchivePort.ReleaseExport;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    @Test
    void tellsARepositoryExportFromAnArtifactExport() {
        // T026: the backup of a deployment holds both kinds
        assertThat(archives.repositoryExport(cliFixture("export_repository.zip"))).isTrue();
        assertThat(archives.repositoryExport(cliFixture("export_release.zip"))).isFalse();
        assertThat(archives.repositoryExport(ArtifactFixtures.bytes("grp-a.zip"))).isFalse();
        assertThat(archives.repositoryExport(ArtifactFixtures.bytes("module-one.zip")))
            .isFalse();
    }

    // --- feature 008: nested folders (probe P-2, T002) -----------------------------------------

    private static final String MAIN = "/Root/jdoe/xsd/P8/folder/main.xsd";
    private static final String INCLUDED = "/Root/jdoe/xsd/P8/folder/sub/included.xsd";

    @Test
    void readsTheFilesOfANestedFolderExport() {
        byte[] folder = cliFixture("export_repository_folder.zip");

        assertThat(archives.repositoryExport(folder)).isTrue();
        Map<String, byte[]> files = archives.repositoryFiles(folder);
        assertThat(files).hasSize(16).containsKeys("/Root/jdoe/xsd/probe.xsd",
            "/Root/jdoe/xsd/probe.xsl", MAIN, INCLUDED, "/Root/jdoe/xsd/P8/types/probe.wsdl",
            "/Root/jdoe/xsd/P8/untyped/probe.xslt", "/Root/jdoe/xsd/P8/nested/inner-uri.xsl");
        assertThat(text(files.get(MAIN)))
            .contains("<xs:include schemaLocation=\"sub/included.xsd\"/>");
        // LF only, no trailing newline: the bytes the probe sent
        assertThat(files.get(INCLUDED)).hasSize(193).doesNotContain((byte) '\r')
            .endsWith((byte) '>');
    }

    @Test
    void theExportAfterTheImportHoldsTheNewFilesInVersionOne() {
        byte[] after = cliFixture("export_repository_after_import.zip");
        Map<String, byte[]> later = archives.repositoryFiles(
            cliFixture("export_repository_folder.zip"));

        Map<String, byte[]> files = archives.repositoryFiles(after);
        assertThat(files).hasSize(14).containsKeys(MAIN, INCLUDED)
            .doesNotContainKey("/Root/jdoe/xsd/P8/nested/inner-uri.xsl");
        assertThat(files.get(MAIN)).isEqualTo(later.get(MAIN));
        assertThat(files.get(INCLUDED)).isEqualTo(later.get(INCLUDED));
        // probe.xsd was changed by that import and restored by a later one
        assertThat(text(files.get("/Root/jdoe/xsd/probe.xsd"))).contains("\"probe8\"");
        assertThat(text(later.get("/Root/jdoe/xsd/probe.xsd"))).contains("\"probe4\"");
        Map<String, String> metadata = new java.util.TreeMap<>();
        RepositoryArchive.read(after).forEach(file -> metadata.put(file.path(),
            text(file.metadata())));
        assertThat(metadata.get(MAIN)).contains("version=\"1.0\"", "anonymousRead=\"false\"")
            .doesNotContain("<Description");
        assertThat(metadata.get("/Root/jdoe/xsd/probe.xsd")).contains("version=\"1.5\"");
    }

    // --- feature 008: the import archive from workspace files (T012, research D-7, D-8) ------

    private static final String STYLESHEET = "/Root/jdoe/xsd/release.xsl";

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Repository path → metadata of the import archive {@code zip} for jdoe. */
    private static Map<String, String> importedMetadata(byte[] zip) {
        Map<String, String> metadata = new java.util.TreeMap<>();
        RepositoryArchive.readRelative("jdoe", zip).forEach(file -> metadata.put(file.path(),
            text(file.metadata())));
        return metadata;
    }

    /** An exported file's metadata in the {@code .meta} form that {@code WorkspaceWriter} stores. */
    private static String exportedMetadata(String path) {
        return RepositoryArchive.read(cliFixture("export_repository.zip")).stream()
            .filter(file -> file.path().equals(path))
            .map(file -> text(XmlNormalizer.normalize(file.metadata())))
            .findFirst().orElseThrow();
    }

    private static String withContentFigures(String metadata, byte[] content) throws Exception {
        String md5 = String.format("%032x", new java.math.BigInteger(1,
            java.security.MessageDigest.getInstance("MD5").digest(content)));
        return metadata.replaceFirst("contentSize=\"\\d+\"", "contentSize=\"" + content.length
            + "\"").replaceFirst("contentMD5=\"[0-9a-f]+\"", "contentMD5=\"" + md5 + "\"");
    }

    @Test
    void anUploadWithMetadataKeepsItExceptTheContentFigures() throws Exception {
        // a MODIFIED file with its .meta record, or an EXISTING file with the node's metadata
        String metadata = exportedMetadata(STYLESHEET);
        byte[] content = bytes("<xsl:stylesheet version=\"1.0\"/>\n");

        byte[] zip = archives.repositoryArchiveFromFiles("jdoe", List.of(new RepositoryUpload(
            STYLESHEET, content, Optional.of(metadata))));

        assertThat(ArtifactFixtures.entries(zip).keySet()).containsExactly("xsd/",
            "xsd/release.xsl.xml", "xsd/release.xsl.dat");
        assertThat(RepositoryArchive.readRelative("jdoe", zip)).singleElement()
            .satisfies(file -> assertThat(file.content()).isEqualTo(content));
        String imported = importedMetadata(zip).get(STYLESHEET);
        assertThat(imported).isNotEqualTo(metadata)
            .isEqualTo(withContentFigures(metadata, content))
            .contains("anonymousRead=\"true\"", "contentType=\"application/xslt+xml\"",
                "<Description>Fixture stylesheet</Description>");
    }

    @Test
    void aNewFileWithoutMetadataGetsTheSynthesizedOne() throws Exception {
        String path = "/Root/jdoe/xsd/orders/sub/new.wsdl";
        byte[] content = bytes("<definitions/>");

        byte[] zip = archives.repositoryArchiveFromFiles("jdoe", List.of(
            new RepositoryUpload(path, content, Optional.empty()),
            new RepositoryUpload(STYLESHEET, bytes("<x/>"),
                Optional.of(exportedMetadata(STYLESHEET)))));

        assertThat(ArtifactFixtures.entries(zip).keySet()).containsExactly("xsd/",
            "xsd/orders/", "xsd/orders/sub/", "xsd/orders/sub/new.wsdl.xml",
            "xsd/orders/sub/new.wsdl.dat", "xsd/release.xsl.xml", "xsd/release.xsl.dat");
        // the synthesized metadata plus the figures of the content written
        String imported = importedMetadata(zip).get(path);
        String synthesized = RepositoryMetadata.forNewFile(path);
        assertThat(imported).startsWith(synthesized.substring(0, synthesized.indexOf('>')))
            .contains("contentType=\"application/wsdl+xml\"", "contentSize=\"14\"")
            .doesNotContain("anonymousRead");
        assertThat(imported.replaceFirst(" contentSize=\"14\" contentMD5=\"[0-9a-f]{32}\"", ""))
            .isEqualTo(synthesized);
        assertThat(imported).isEqualTo(withContentFigures(imported, content));
    }

    @Test
    void uploadsAreRefusedLikeEveryRepositoryImport() {
        String certificate = "/Root/jdoe/keys/partner.pem";
        String metadata = "<Property name=\"partner.pem\" type=\"RepositoryFile\" path=\""
            + certificate + "\" contentType=\"application/octet-stream\"/>";

        // key material never leaves the workspace (FR-015a)
        assertThatThrownBy(() -> archives.repositoryArchiveFromFiles("jdoe", List.of(
            new RepositoryUpload(certificate, bytes("plain"), Optional.of(metadata)))))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(e.error().code())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED));
        // a new file of another extension has no content type
        assertThatThrownBy(() -> archives.repositoryArchiveFromFiles("jdoe", List.of(
            new RepositoryUpload("/Root/jdoe/data/a.json", bytes("{}"), Optional.empty()))))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(e.error().code())
                .isEqualTo(ErrorCode.INVALID_INPUT));
        // another owner's root
        assertThatThrownBy(() -> archives.repositoryArchiveFromFiles("jdoe", List.of(
            new RepositoryUpload("/Root/other/a.xsd", bytes("<x/>"), Optional.empty()))))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(e.error().code())
                .isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void certificateFilesCountAsKeyMaterialByTheirName() {
        // research D-4: the port's detector includes the certificate names, not only KeyMaterial
        byte[] harmless = bytes("not a certificate");
        assertThat(archives.keyMaterial("/Root/jdoe/certs/partner.cer", harmless)).isTrue();
        assertThat(archives.keyMaterial("/Root/jdoe/certs/partner.pem", harmless)).isTrue();
        assertThat(archives.keyMaterial("/Root/jdoe/certs/partner.xml", harmless)).isFalse();
    }

    // --- feature 007: the order of a module's connections (contract P-3) -------------------

    @Test
    void connectionsInAnotherOrderAreTheSameContent() {
        byte[] a = ConnectionOrderFixtures.bytes("workflow-a.xml");
        byte[] b = ConnectionOrderFixtures.bytes("workflow-b.xml");

        assertThat(archives.equivalent(WORKFLOW, a, b)).isTrue();
        assertThat(archives.canonical(WORKFLOW, a)).isEqualTo(archives.canonical(WORKFLOW, b));
        // no difference is left, so none of them lies in the layout only
        assertThat(archives.layoutOnly(WORKFLOW, a, b)).isFalse();
    }

    @Test
    void anotherOrderWithAnotherLabelPositionIsLayoutOnly() {
        byte[] a = ConnectionOrderFixtures.bytes("workflow-a.xml");
        byte[] moved = ConnectionOrderFixtures.realDifferences().get(
            ConnectionOrderFixtures.LABEL_POSITION).getBytes(StandardCharsets.UTF_8);

        assertThat(archives.equivalent(WORKFLOW, a, moved)).isFalse();
        assertThat(archives.layoutOnly(WORKFLOW, a, moved)).isTrue();
        assertThat(archives.canonical(WORKFLOW, a)).isNotEqualTo(archives.canonical(WORKFLOW,
            moved));
    }

    @Test
    void realDifferencesOfConnectionsStayChanges() {
        byte[] a = ConnectionOrderFixtures.bytes("workflow-a.xml");

        ConnectionOrderFixtures.realDifferences().forEach((difference, text) -> {
            byte[] other = text.getBytes(StandardCharsets.UTF_8);
            assertThat(archives.equivalent(WORKFLOW, a, other)).as(difference).isFalse();
            assertThat(archives.canonical(WORKFLOW, a)).as(difference).isNotEqualTo(
                archives.canonical(WORKFLOW, other));
            assertThat(archives.layoutOnly(WORKFLOW, a, other)).as(difference).isEqualTo(
                difference.equals(ConnectionOrderFixtures.LABEL_POSITION));
        });
    }

    @Test
    @SuppressWarnings("deprecation")
    void theLegacyCanonicalFormKeepsTheConnectionOrder() {
        // recognises fingerprints recorded by <= 0.5.0 (contract P-5)
        byte[] a = ConnectionOrderFixtures.bytes("workflow-a.xml");
        byte[] b = ConnectionOrderFixtures.bytes("workflow-b.xml");
        String legacy = text(archives.legacyCanonical(WORKFLOW, a));

        assertThat(legacy).doesNotContain("CheckinComment").containsSubsequence(
            "moduleOutId=\"315\"", "moduleOutId=\"171\"", "moduleOutId=\"315\"",
            "moduleOutId=\"171\"");
        assertThat(legacy).isEqualTo(text(a).replace("  <CheckinComment>JD: Fixture###"
            + "</CheckinComment>\n", ""));
        assertThat(archives.legacyCanonical(WORKFLOW, b)).isEqualTo(archives.canonical(WORKFLOW,
            b)).isNotEqualTo(archives.legacyCanonical(WORKFLOW, a));
        byte[] unreadable = "<Workflow>".getBytes(StandardCharsets.UTF_8);
        assertThat(archives.legacyCanonical(WORKFLOW, unreadable)).isEqualTo(unreadable)
            .isNotSameAs(unreadable);
    }
}
