package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.RepositoryArchive;
import de.dadecker.inubit.mcp.adapter.archive.v81.RepositoryArchive.RepositoryFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T004 (feature 008): {@link FakeServer} models what probes P-1 to P-3 observed (spike section
 * 11) — repository imports store the sent {@code contentType} and {@code Description}, a
 * repository export of a folder is recursive with a directory entry per folder, and a diagram
 * group export and a group tag cover repository files that only another repository file
 * references — and {@link ImportHarness#repositoryFile} writes a repository file into the
 * workspace.
 */
class FakeServerTest {

    private static final String XSD = "/Root/jdoe/xsd";
    private static final String MAIN = XSD + "/main.xsd";

    @TempDir
    Path temp;

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] importArchive(String path, String metadata, String content) {
        return RepositoryArchive.build("jdoe", List.of(new RepositoryFile(path,
            metadata.getBytes(StandardCharsets.UTF_8), content.getBytes(StandardCharsets.UTF_8))));
    }

    private static String property(String path, String attributes, String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Property name=\""
            + path.substring(path.lastIndexOf('/') + 1) + "\" type=\"RepositoryFile\" path=\""
            + path + "\"" + attributes + ">" + body + "</Property>";
    }

    private static String metadataOf(byte[] export, String path) {
        return RepositoryArchive.read(export).stream().filter(file -> file.path().equals(path))
            .map(file -> text(file.metadata())).findFirst().orElseThrow();
    }

    private static List<String> entryNames(byte[] zip) {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                names.add(entry.getName());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }

    private static List<String> paths(byte[] repositoryZip) {
        return RepositoryArchive.read(repositoryZip).stream().map(RepositoryFile::path).toList();
    }

    // --- repository imports (P-1, P-2) ---------------------------------------------------------

    @Test
    void aNewFileKeepsTheSentContentTypeAndDescriptionWithInubitsOwnValues() {
        FakeServer server = new FakeServer();

        server.importRepository(importArchive(MAIN, property(MAIN, " contentType=\"application/"
            + "x-xsd+xml\" uuid=\"11111111-1111-1111-1111-111111111111\" version=\"7.3\"",
            "<Description>Order schema</Description>"), "<xs:schema/>"), "jdoe");

        String metadata = metadataOf(server.exportRepository(MAIN).orElseThrow(), MAIN);
        assertThat(metadata).contains("contentType=\"application/x-xsd+xml\"",
            "<Description>Order schema</Description>", "version=\"1.0\"",
            "versionComment=\"DefaultCommitCommentImport@@@null\"", "anonymousRead=\"false\"")
            .doesNotContain("11111111-1111-1111-1111-111111111111");
        assertThat(server.repositoryUuid(MAIN)).isPresent();
        assertThat(metadata).contains("uuid=\"" + server.repositoryUuid(MAIN).get() + "\"");
        assertThat(server.repositoryContent(MAIN)).contains("<xs:schema/>");
    }

    @Test
    void aFileWithoutContentTypeBecomesOctetStreamAndAnEmptyDescriptionIsDropped() {
        FakeServer server = new FakeServer();

        server.importRepository(importArchive(MAIN, property(MAIN, "", "<Description/>"),
            "<xs:schema/>"), "jdoe");

        String metadata = metadataOf(server.exportRepository(MAIN).orElseThrow(), MAIN);
        assertThat(metadata).contains("contentType=\"application/octet-stream\"")
            .endsWith("/>").doesNotContain("Description");
    }

    @Test
    void everyImportIsANewVersionWithTheSentMetadataAndTheSameUuid() {
        FakeServer server = new FakeServer();
        server.importRepository(importArchive(MAIN, property(MAIN, " contentType=\"application/"
            + "x-xsd+xml\" anonymousRead=\"true\"", "<Description>v1</Description>"), "a"), "jdoe");
        String uuid = server.repositoryUuid(MAIN).orElseThrow();

        server.importRepository(importArchive(MAIN, property(MAIN, " contentType=\"application/"
            + "xml\" version=\"1.0\"", "<Description>v2</Description>"), "a"), "jdoe");

        assertThat(server.repositoryVersion(MAIN)).contains("1.1");
        assertThat(server.repositoryUuid(MAIN)).contains(uuid);
        assertThat(metadataOf(server.exportRepository(MAIN).orElseThrow(), MAIN)).contains(
            "contentType=\"application/xml\"", "<Description>v2</Description>",
            "anonymousRead=\"true\"", "version=\"1.1\"", "versionComment=\""
                + "DefaultCommitCommentImport@@@DefaultCommitCommentImport@@@null\"");
    }

    // --- repository exports (P-2) --------------------------------------------------------------

    @Test
    void aFolderExportIsRecursiveWithADirectoryEntryPerFolderFilesBeforeSubfolders() {
        FakeServer server = new FakeServer();
        server.putRepositoryFile(XSD + "/zz.xsd", "zz");
        server.putRepositoryFile(XSD + "/P8/folder/sub/included.xsd", "included");
        server.putRepositoryFile(XSD + "/P8/folder/main.xsd", "main");
        server.putRepositoryFile(XSD + "/P8/types/probe.xml", "types");
        server.putRepositoryFile("/Root/jdoe/xsdx/other.xsd", "sibling with the same prefix");
        server.putRepositoryFile("/Root/jdoe/other.xsd", "outside");

        byte[] export = server.exportRepository(XSD).orElseThrow();

        assertThat(entryNames(export)).containsExactly("Root/jdoe/xsd/",
            "Root/jdoe/xsd/zz.xsd.xml", "Root/jdoe/xsd/zz.xsd.dat",
            "Root/jdoe/xsd/P8/",
            "Root/jdoe/xsd/P8/folder/",
            "Root/jdoe/xsd/P8/folder/main.xsd.xml", "Root/jdoe/xsd/P8/folder/main.xsd.dat",
            "Root/jdoe/xsd/P8/folder/sub/",
            "Root/jdoe/xsd/P8/folder/sub/included.xsd.xml",
            "Root/jdoe/xsd/P8/folder/sub/included.xsd.dat",
            "Root/jdoe/xsd/P8/types/",
            "Root/jdoe/xsd/P8/types/probe.xml.xml", "Root/jdoe/xsd/P8/types/probe.xml.dat");
        assertThat(paths(export)).hasSize(4).allMatch(path -> path.startsWith(XSD + "/"));
        assertThat(entryNames(server.exportRepository(XSD + "/P8/folder/sub/included.xsd")
            .orElseThrow())).containsExactly("Root/jdoe/xsd/P8/folder/sub/",
                "Root/jdoe/xsd/P8/folder/sub/included.xsd.xml",
                "Root/jdoe/xsd/P8/folder/sub/included.xsd.dat");
    }

    @Test
    void aPathWithoutFilesIsNotFound() {
        FakeServer server = new FakeServer();
        server.putRepositoryFile(MAIN, "main");

        assertThat(server.exportRepository("/Root/jdoe/missing")).isEmpty();
        assertThat(server.exportRepository("/Root/jdoe/xs")).isEmpty();
        assertThat(server.exportRepository(MAIN + "x")).isEmpty();
    }

    // --- group export and tag follow references inside repository files (P-3) -----------------

    /**
     * {@code Module-0005} of {@code GRP-01} imports {@code release.xsl}, which imports
     * {@code nested/inner-relative.xsl} relatively and {@code nested/inner-uri.xsl} by
     * {@code inubitrepository:}; {@code inner-relative.xsl} imports {@code ../schemas/deep.xsd},
     * {@code inner-uri.xsl} imports {@code release.xsl} again (a cycle) and a missing file. A
     * URI as a string literal, an {@code http:} import and an unreferenced file are not exported.
     */
    private static FakeServer withNestedReferences() {
        FakeServer server = FakeServer.of(DeployHarness.withRepositoryReference(
            ImportHarness.withoutEditMode()));
        server.putRepositoryFile(DeployHarness.RELEASE_XSL, "<xsl:stylesheet>"
            + "<xsl:import href=\"nested/inner-relative.xsl\"/>"
            + "<xsl:import href=\"inubitrepository:/Root/jdoe/xsd/nested/inner-uri.xsl\"/>"
            + "<xsl:import href=\"http://example.test/remote.xsl\"/>"
            + "<xsl:variable name=\"v\" select=\"'inubitrepository:/Root/jdoe/xsd/literal.xsl'\"/>"
            + "</xsl:stylesheet>");
        server.putRepositoryFile(XSD + "/nested/inner-relative.xsl", "<xsl:stylesheet>"
            + "<xs:include schemaLocation=\"../schemas/deep.xsd\"/></xsl:stylesheet>");
        server.putRepositoryFile(XSD + "/nested/inner-uri.xsl", "<xsl:stylesheet>"
            + "<xsl:import href=\"../release.xsl\"/><xsl:import href=\"missing.xsl\"/>"
            + "</xsl:stylesheet>");
        server.putRepositoryFile(XSD + "/schemas/deep.xsd", "<xs:schema/>");
        server.putRepositoryFile(XSD + "/literal.xsl", "<xsl:stylesheet/>");
        server.putRepositoryFile(XSD + "/unused.xsl", "<xsl:stylesheet/>");
        return server;
    }

    private static final List<String> CLOSURE = List.of(XSD + "/nested/inner-relative.xsl",
        XSD + "/nested/inner-uri.xsl", DeployHarness.RELEASE_XSL, XSD + "/schemas/deep.xsd");

    @Test
    void theGroupExportHoldsTheRepositoryFilesReferencedTransitively() {
        FakeServer server = withNestedReferences();

        Map<String, byte[]> entries = ArtifactFixtures.entries(server.exportWorkflowGroup(
            DeployHarness.GROUP).orElseThrow());

        assertThat(paths(entries.get("Repository.zip"))).containsExactlyInAnyOrderElementsOf(
            CLOSURE);
        assertThat(entryNames(entries.get("Repository.zip"))).noneMatch(name ->
            name.endsWith("/"));
    }

    @Test
    void theGroupTagCoversTheTransitivelyReferencedFilesInTheirTaggedVersions() {
        FakeServer server = withNestedReferences();
        server.tag(DeployHarness.GROUP, "TAG-01");
        server.putRepositoryFile(XSD + "/schemas/deep.xsd", "<xs:schema v2/>");

        byte[] repository = ArtifactFixtures.entries(server.exportRelease("TAG-01"))
            .get("Repository.zip");

        assertThat(paths(repository)).containsExactlyInAnyOrderElementsOf(CLOSURE);
        RepositoryFile deep = RepositoryArchive.read(repository).stream()
            .filter(file -> file.path().equals(XSD + "/schemas/deep.xsd")).findFirst()
            .orElseThrow();
        assertThat(text(deep.metadata())).contains("tagName=\"TAG-01\"", "version=\"1.0\"");
        assertThat(text(deep.content())).isEqualTo("<xs:schema/>");
        assertThat(server.repositoryVersion(XSD + "/schemas/deep.xsd")).contains("1.1");
    }

    @Test
    void theGroupExportHoldsASchemaReferencedOnlyAsAMessageType() {
        // T050 (research D-3): a module index entry names it, no module file does
        FakeServer server = FakeServer.of(ExportHarness.rewrite("grp-a.zip", "module/module.xml",
            xml -> xml.replace("<ModuleName>Module-0001</ModuleName>",
                "<ModuleName>Module-0001</ModuleName>"
                    + "<InputMessageTypeSchema>" + XSD + "/msg.xsd</InputMessageTypeSchema>")));
        server.putRepositoryFile(XSD + "/msg.xsd",
            "<xs:schema><xs:include schemaLocation=\"types.xsd\"/></xs:schema>");
        server.putRepositoryFile(XSD + "/types.xsd", "<xs:schema/>");
        server.putRepositoryFile(XSD + "/unused.xsd", "<xs:schema/>");

        byte[] repository = ArtifactFixtures.entries(server.exportWorkflowGroup(
            DeployHarness.GROUP).orElseThrow()).get("Repository.zip");

        assertThat(paths(repository)).containsExactlyInAnyOrder(XSD + "/msg.xsd",
            XSD + "/types.xsd");
    }

    // --- ImportHarness: a repository file in the workspace -------------------------------------

    @Test
    void theHarnessWritesARepositoryFileWithItsMetaRecord() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);

        String file = harness.repositoryFile("xsd/order.xsd", "<xs:schema/>",
            "application/x-xsd+xml");

        assertThat(file).isEqualTo("dev/jdoe/repository/Root/jdoe/xsd/order.xsd");
        assertThat(harness.read(file)).isEqualTo("<xs:schema/>");
        String meta = Files.readString(harness.root.resolve(
            ".meta/dev/jdoe/repository/Root/jdoe/xsd/order.xsd.json"));
        assertThat(meta).contains("\"metadata\"", "name=\\\"order.xsd\\\"",
            "type=\\\"RepositoryFile\\\"", "path=\\\"/Root/jdoe/xsd/order.xsd\\\"",
            "contentType=\\\"application/x-xsd+xml\\\"");
    }
}
