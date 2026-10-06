package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * T018 (research D-4, FR-015, SC-002): an archive rebuilt from the workspace and {@code .meta/}
 * is equal to the export — after replacing secrets by placeholders on both sides and ignoring
 * the values INUBIT changes on every export (export time, {@code operationId}).
 */
class ArchiveRoundTripTest {

    private static final GroupId DEV = new GroupId("dev");
    private static final String TIME = "01.01.2027 00:00:00";

    @TempDir
    Path root;

    /** The original export, redacted, as archive entries. */
    private static Map<String, byte[]> original(String fixture) {
        return ArchiveAssembler.entries(WorkspaceWriterTest.redacted(fixture));
    }

    private byte[] rebuild(String fixture, String owner) {
        RedactedArchive redacted = WorkspaceWriterTest.redacted(fixture);
        WorkspaceWriter.write(root, WorkspaceWriter.render(redacted, DEV, owner));
        ExportArchive archive = redacted.archive();
        if (!archive.workflowGroups().isEmpty()) {
            return ArchiveAssembler.assembleDiagramGroup(root, DEV, owner,
                archive.workflowGroups().get(0).name(), TIME);
        }
        var module = archive.moduleIndex().get(0);
        return ArchiveAssembler.assembleModule(root, DEV, owner, module.pluginType(),
            module.name(), TIME);
    }

    @ParameterizedTest
    @CsvSource({"grp-a.zip, jdoe", "grp-b.zip, OWNERS", "module-one.zip, OWNERS",
        "module-smime.zip, OWNERS"})
    void theRebuiltArchiveEqualsTheExport(String fixture, String owner) throws IOException {
        Map<String, byte[]> expected = original(fixture);
        Map<String, byte[]> rebuilt = ArtifactFixtures.entries(rebuild(fixture, owner));

        assertThat(rebuilt.keySet()).as("entries").containsExactlyElementsOf(expected.keySet());
        assertEqual(expected, rebuilt, fixture);
    }

    @ParameterizedTest
    @CsvSource({"grp-a.zip", "module-one.zip"})
    void entriesOfASecretFreeExportEqualTheRecordedEntries(String fixture) throws IOException {
        // the serialization of the model itself is faithful
        assertEqual(ArtifactFixtures.entries(fixture), original(fixture), fixture);
    }

    @Test
    void repositoryChecksumsAreRecomputedFromTheContent() throws Exception {
        rebuild("grp-b.zip", "OWNERS");
        Path schema = root.resolve("dev/OWNERS/repository/Root/OWNERS/xsd/msg.xsd");
        byte[] edited = "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>"
            .getBytes(StandardCharsets.UTF_8);
        Files.write(schema, edited);

        byte[] zip = ArchiveAssembler.assembleDiagramGroup(root, DEV, "OWNERS", "GRP-02", TIME);
        Map<String, byte[]> repository = ArtifactFixtures.entries(
            ArtifactFixtures.entries(zip).get("Repository.zip"));
        Element metadata = XmlTree.parse(repository.get("Root/OWNERS/xsd/msg.xsd.xml")).root();

        assertThat(repository.get("Root/OWNERS/xsd/msg.xsd.dat")).isEqualTo(edited);
        assertThat(metadata.attribute("contentSize")).contains(String.valueOf(edited.length));
        assertThat(metadata.attribute("contentMD5")).contains(HexFormat.of().formatHex(
            MessageDigest.getInstance("MD5").digest(edited)));
    }

    @Test
    void theRebuildWritesTheGivenExportTime() {
        Map<String, byte[]> rebuilt = ArtifactFixtures.entries(rebuild("grp-a.zip", "jdoe"));

        assertThat(new String(rebuilt.get("workflow/workflow.xml"), StandardCharsets.UTF_8))
            .contains("@@@Export/Deployment: " + TIME + "@@@</CheckinComment>");
    }

    // --- equality (research D-4) -----------------------------------------------------------

    static void assertEqual(Map<String, byte[]> expected, Map<String, byte[]> actual,
        String where) throws IOException {
        for (String name : new TreeSet<>(expected.keySet())) {
            byte[] a = expected.get(name);
            byte[] b = actual.get(name);
            assertThat(b).as("%s!%s exists", where, name).isNotNull();
            if (name.endsWith("/")) {
                continue;
            }
            if (name.equals("archive.properties")) {
                assertThat(properties(b).getProperty("sourceVersion")).as(name)
                    .isEqualTo(properties(a).getProperty("sourceVersion"));
            } else if (name.endsWith(".zip")) {
                Map<String, byte[]> inner = ArtifactFixtures.entries(a);
                Map<String, byte[]> other = ArtifactFixtures.entries(b);
                assertThat(other.keySet()).as(name).containsExactlyInAnyOrderElementsOf(
                    inner.keySet());
                assertEqual(inner, other, where + "!" + name);
            } else if (name.endsWith(".xml")) {
                assertThat(XmlEquality.equal(stable(a), stable(b))).as("%s!%s", where, name)
                    .isTrue();
            } else {
                assertThat(b).as("%s!%s", where, name).isEqualTo(a);
            }
        }
    }

    private static Properties properties(byte[] bytes) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(new String(bytes, StandardCharsets.ISO_8859_1)));
        return properties;
    }

    /** The document without the export time INUBIT writes into every check-in comment. */
    private static byte[] stable(byte[] xml) {
        return XmlNormalizer.normalize(strip(XmlTree.parse(xml).root()));
    }

    private static Element strip(Element element) {
        if (element.localName().equals("CheckinComment")) {
            return element.withText(element.text().replaceAll(
                "Export/Deployment: [^@]*@@@$", ""));
        }
        List<Node> children = new ArrayList<>();
        for (Node child : element.children()) {
            children.add(child instanceof Element e ? strip(e) : child);
        }
        return element.withChildren(children);
    }
}
