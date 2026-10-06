package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.SecretPlaceholder;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * T018 (research D-4, FR-015, SC-002): an archive rebuilt from the workspace and {@code .meta/}
 * is equal to the recorded export (review I4) under {@link XmlEquality} — a placeholder only
 * where the recording holds a synthetic secret of the fixture README, and without the values
 * INUBIT changes on every export (export time, {@code operationId}; research D-6).
 */
class ArchiveRoundTripTest {

    private static final GroupId DEV = new GroupId("dev");
    private static final String TIME = "01.01.2027 00:00:00";
    private static final List<String> SYNTHETIC = ArtifactFixtures.syntheticSecrets().stream()
        .map(ArtifactFixtures.SyntheticSecret::value).toList();

    @TempDir
    Path root;

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
    void theRebuiltArchiveEqualsTheRecordedExport(String fixture, String owner) {
        // review I4: against the recorded ZIP, not the model; a placeholder is accepted only
        // where the recording holds a listed synthetic secret, volatile values per D-6
        Map<String, byte[]> recorded = ArtifactFixtures.entries(fixture);
        Map<String, byte[]> rebuilt = ArtifactFixtures.entries(rebuild(fixture, owner));
        List<String> differences = new ArrayList<>();

        assertThat(rebuilt.keySet()).as("entries").containsExactlyElementsOf(recorded.keySet());
        compareRecorded(recorded, rebuilt, fixture, differences);

        assertThat(differences).as("entries that differ from the recording").isEmpty();
    }

    @Test
    void theGrownCheckinCommentsOfASecondExportAreRestoredExactly() {
        byte[] grown = de.dadecker.inubit.mcp.application.ExportHarness.grownComments();
        RedactedArchive redacted = WorkspaceWriterTest.redacted(grown);
        WorkspaceWriter.write(root, WorkspaceWriter.render(redacted, DEV, "jdoe"));
        Map<String, byte[]> rebuilt = ArtifactFixtures.entries(ArchiveAssembler
            .assembleDiagramGroup(root, DEV, "jdoe", "GRP-01", TIME));
        List<String> differences = new ArrayList<>();

        compareRecorded(ArtifactFixtures.entries(grown), rebuilt, "grown", differences);

        assertThat(differences).isEmpty();
        assertThat(new String(rebuilt.get("workflow/workflow.xml"), StandardCharsets.UTF_8))
            .contains("###Import from inubit without version history@@@Deploying User:");
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

    /** Compares recorded and rebuilt entries; differences are collected by entry name. */
    private static void compareRecorded(Map<String, byte[]> recorded, Map<String, byte[]> rebuilt,
        String where, List<String> differences) {
        Set<String> withheld = new TreeSet<>();
        for (String name : new TreeSet<>(recorded.keySet())) {
            byte[] a = recorded.get(name);
            byte[] b = rebuilt.get(name);
            if (name.endsWith(".dat") && b != null && !Arrays.equals(a, b)
                && SecretPlaceholder.isPlaceholder(new String(b, StandardCharsets.UTF_8))
                && isSyntheticKeystore(a)) {
                withheld.add(name.substring(0, name.length() - ".dat".length()));
            }
        }
        for (String name : new TreeSet<>(recorded.keySet())) {
            byte[] a = recorded.get(name);
            byte[] b = rebuilt.get(name);
            String at = where + "!" + name;
            if (b == null) {
                differences.add(at + " missing");
            } else if (name.endsWith("/")) {
                continue;
            } else if (name.equals("archive.properties")) {
                if (!Objects.equals(sourceVersion(a), sourceVersion(b))) {
                    differences.add(at);
                }
            } else if (name.endsWith(".zip")) {
                Map<String, byte[]> inner = ArtifactFixtures.entries(a);
                Map<String, byte[]> other = ArtifactFixtures.entries(b);
                if (!new TreeSet<>(inner.keySet()).equals(new TreeSet<>(other.keySet()))) {
                    differences.add(at + " entries");
                }
                compareRecorded(inner, other, at, differences);
            } else if (name.endsWith(".dat") && withheld.contains(
                name.substring(0, name.length() - ".dat".length()))) {
                continue; // a synthetic keystore, withheld (review I3)
            } else if (name.endsWith(".xml") || (a.length > 0 && a[0] == '<')) {
                Element recordedRoot = strip(XmlTree.parse(a).root());
                if (withheld.contains(name.substring(0, name.length() - ".xml".length()))) {
                    recordedRoot = withoutContentValues(recordedRoot);
                }
                Element rebuiltRoot = strip(XmlTree.parse(b).root());
                if (!XmlEquality.equal(XmlNormalizer.normalize(reconcile(recordedRoot,
                    rebuiltRoot)), XmlNormalizer.normalize(rebuiltRoot))) {
                    differences.add(at);
                }
            } else if (!Arrays.equals(a, b)) {
                differences.add(at);
            }
        }
    }

    /**
     * {@code recorded} with each leaf value replaced by the rebuilt placeholder at the same place
     * — only if the recorded value is a listed synthetic secret (or holds one, or is a synthetic
     * keystore). Anything else stays and makes the comparison fail.
     */
    private static Element reconcile(Element recorded, Element rebuilt) {
        if (!recorded.hasElements() && !rebuilt.hasElements()
            && SecretPlaceholder.isPlaceholder(rebuilt.text().strip())
            && isSyntheticSecret(recorded.text())) {
            return recorded.withText(rebuilt.text());
        }
        List<Element> others = rebuilt.elements();
        if (recorded.elements().size() != others.size()) {
            return recorded;
        }
        List<Node> children = new ArrayList<>();
        int next = 0;
        for (Node child : recorded.children()) {
            children.add(child instanceof Element e ? reconcile(e, others.get(next++)) : child);
        }
        return recorded.withChildren(children);
    }

    private static boolean isSyntheticSecret(String value) {
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            return false;
        }
        if (SYNTHETIC.stream().anyMatch(stripped::contains)) {
            return true;
        }
        return KeyMaterial.decodeDocument(stripped)
            .filter(ArchiveRoundTripTest::isSyntheticKeystore).isPresent();
    }

    private static boolean isSyntheticKeystore(byte[] content) {
        return ArtifactFixtures.syntheticKeystores().stream()
            .anyMatch(keystore -> Arrays.equals(keystore.bytes(), content));
    }

    private static Element withoutContentValues(Element metadata) {
        return metadata.withAttributes(metadata.attributes().stream()
            .filter(a -> !a.qualifiedName().equals("contentSize")
                && !a.qualifiedName().equals("contentMD5")).toList());
    }

    private static String sourceVersion(byte[] bytes) {
        try {
            return properties(bytes).getProperty("sourceVersion");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Properties properties(byte[] bytes) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(new String(bytes, StandardCharsets.ISO_8859_1)));
        return properties;
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
