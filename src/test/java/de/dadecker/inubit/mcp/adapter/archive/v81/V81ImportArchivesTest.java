package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.port.ImportArchivePort.Archive;
import de.dadecker.inubit.mcp.domain.port.ImportArchivePort.Artifact;
import de.dadecker.inubit.mcp.domain.port.ImportArchivePort.Build;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * T016 support (feature 004, research D-6, D-9, D-11): the 8.1 import archive port — the
 * assembler with the target's secrets of all its exports, and the comparison of reviewed
 * content that ignores what INUBIT rewrites on an import.
 */
class V81ImportArchivesTest {

    private static final GroupId GROUP = new GroupId("dev");
    private final V81ImportArchives archives = new V81ImportArchives();

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void theArchiveCarriesTheSecretsOfTheTargetsExports() {
        byte[] export = ArtifactFixtures.bytes("grp-b.zip");
        var raw = new ArchiveReader().read(export);
        var files = new TreeMap<>(WorkspaceWriter.render(new SecretRedactor().redact(raw), GROUP,
            "OWNERS").files());

        Archive archive = archives.assemble(new Build(GROUP, "OWNERS", Optional.of("GRP-02"),
            List.of(new Artifact("Workflow-0006", Optional.empty(), false)), List.of(), files,
            List.of(ArtifactFixtures.bytes("module-one.zip"), export), "Fix it", "jdoe",
            "inubit-dev-1.example.test", "07.10.2026 10:00:00", Set.of()));

        String workflow = new String(ArtifactFixtures.entries(archive.zip())
            .get("workflow/workflow.xml"), StandardCharsets.UTF_8);
        assertThat(workflow).contains("synthetic-literal-0001")
            .contains("DefaultCommitCommentImport###Fix it###");
        assertThat(archive.workflows()).containsExactly("Workflow-0006");
        assertThat(archive.toString()).doesNotContain("synthetic");
    }

    @Test
    void reviewedContentIgnoresWhatAnImportRewrites() {
        String before = "<Workflow><WorkflowName>W</WorkflowName><WorkflowUId/>"
            + "<CheckinComment>JD: x</CheckinComment><CheckoutUser>jdoe</CheckoutUser>"
            + "<IsActive>false</IsActive><WorkflowModule><StyleSheet xPos=\"1\"/>"
            + "</WorkflowModule></Workflow>";
        String after = "<Workflow>\n  <WorkflowName>W</WorkflowName>\n  <WorkflowUId>a:b</WorkflowUId>"
            + "<CheckinComment>DefaultCommitCommentImport###r###</CheckinComment>"
            + "<IsActive>false</IsActive><WorkflowModule><StyleSheet xPos=\"1\"/>"
            + "</WorkflowModule></Workflow>";
        String moved = after.replace("xPos=\"1\"", "xPos=\"2\"");
        String index = "<Module><ModuleName>M</ModuleName><CheckinComment>a</CheckinComment>"
            + "<LastUpdate>01.01.2026 00:00:00</LastUpdate></Module>";

        assertThat(archives.equivalent("dev/o/workflows/G/W.xml", bytes(before), bytes(after)))
            .isTrue();
        assertThat(archives.equivalent("dev/o/workflows/G/W.xml", bytes(before), bytes(moved)))
            .isFalse();
        assertThat(archives.equivalent("dev/o/modules/A/M/index.xml", bytes(index),
            bytes(index.replace("01.01.2026", "07.10.2026").replace(">a<", ">b<")))).isTrue();
        assertThat(archives.equivalent("dev/o/modules/A/M/x.bin", bytes("a"), bytes("a")))
            .isTrue();
        assertThat(archives.equivalent("dev/o/modules/A/M/x.bin", bytes("a"), bytes("b")))
            .isFalse();
        assertThat(archives.equivalent("dev/o/modules/A/M/x.bin", bytes("a"), null)).isFalse();
    }

    @Test
    void theCheckinCommentIsRead() {
        assertThat(archives.checkinComment(bytes("<Workflow><CheckinComment>Default"
            + "CommitCommentImport###r###</CheckinComment></Workflow>")))
            .contains("DefaultCommitCommentImport###r###");
        assertThat(archives.checkinComment(bytes("<Properties/>"))).isEmpty();
        assertThat(archives.checkinComment(bytes("not xml"))).isEmpty();
        assertThat(Map.of()).isEmpty();
    }

    @Test
    void theActiveFlagOfAWorkflowFileIsReadAndSet() {
        // T020 (research D-15): set_active changes only IsActive of the server's workflow
        byte[] inactive = bytes("<Workflow>\n  <WorkflowName>W</WorkflowName>\n"
            + "  <IsActive>false</IsActive>\n  <XPathVersion>3.1</XPathVersion>\n</Workflow>\n");

        byte[] active = archives.withActive(inactive, true);

        assertThat(archives.active(inactive)).contains(false);
        assertThat(archives.active(active)).contains(true);
        assertThat(new String(active, StandardCharsets.UTF_8))
            .isEqualTo(new String(XmlNormalizer.normalize(XmlTree.parse(new String(inactive,
                StandardCharsets.UTF_8).replace("false", "true").getBytes(StandardCharsets.UTF_8))
                .root()), StandardCharsets.UTF_8));
        assertThat(archives.withActive(active, false)).isEqualTo(XmlNormalizer.normalize(
            XmlTree.parse(inactive).root()));
        assertThat(archives.active(bytes("<Workflow><WorkflowName>W</WorkflowName></Workflow>")))
            .isEmpty();
        assertThat(archives.active(bytes("not xml"))).isEmpty();
    }

    @Test
    void aDeploymentBuildTakesTheFlagOfANewWorkflowFromTheRelease() {
        // feature 005 (T017, research D-7): a new active workflow is no error for a deployment
        byte[] export = ArtifactFixtures.bytes("grp-a.zip");
        var raw = new ArchiveReader().read(export);
        var files = new TreeMap<>(WorkspaceWriter.render(new SecretRedactor().redact(raw),
            new GroupId("int"), "jdoe").files());
        String path = "int/jdoe/workflows/GRP-01/Workflow-0001.xml";
        files.put(path, bytes(new String(files.get(path), StandardCharsets.UTF_8)
            .replace("<IsActive>false</IsActive>", "<IsActive>true</IsActive>")));

        Archive archive = archives.assemble(new Build(new GroupId("int"), "jdoe",
            Optional.of("GRP-01"), List.of(new Artifact("Workflow-0001", Optional.empty(), true)),
            List.of(), files, List.of(), "deploy TAG-01 from dev", "jdoe",
            "inubit-int-1.example.test", "07.10.2026 10:00:00", Set.of(), true));

        assertThat(archive.active()).containsExactly(Map.entry("Workflow-0001", true));
        assertThat(new Build(GROUP, "o", Optional.empty(), List.of(), List.of(), files, List.of(),
            "r", "u", "h", "07.10.2026 10:00:00", Set.of()).fromRelease()).isFalse();
    }

    @Test
    void anEmbeddedTextDocumentIsComparedAsInubitStoresIt() {
        // 0.4.2: INUBIT drops the trailing line break of an embedded stylesheet
        String path = "dev/o/modules/XSLT Converter/M/xslt.stylesheet.xsl";
        String stylesheet = "<xsl:stylesheet>\n  <xsl:template match=\"/\"/>\n</xsl:stylesheet>";

        assertThat(archives.equivalent(path, bytes(stylesheet + "\n"), bytes(stylesheet)))
            .isTrue();
        assertThat(archives.equivalent(path, bytes(stylesheet + " \r\n\n"), bytes(stylesheet)))
            .isTrue();
        assertThat(archives.equivalent(path, bytes(stylesheet.replace("\n", "\r\n")),
            bytes(stylesheet))).isTrue();
        assertThat(archives.equivalent("dev/o/modules/A/M/WsdlData.wsdl", bytes("<d/>\n"),
            bytes("<d/>"))).isTrue();
        // content, trailing spaces inside and binary documents still count
        assertThat(archives.equivalent(path, bytes(stylesheet), bytes(stylesheet
            .replace("/\"", "/x\"")))).isFalse();
        assertThat(archives.equivalent(path, bytes(stylesheet.replace("\n  <", "  \n  <")),
            bytes(stylesheet))).isFalse();
        assertThat(archives.equivalent("dev/o/modules/A/M/x.bin", bytes("a\n"), bytes("a")))
            .isFalse();
    }

    // --- feature 007: the order of a module's connections (contract P-2) -------------------

    private static final String WORKFLOW = "dev/jdoe/workflows/GRP-01/Workflow-0001.xml";

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** The rendered workspace file of {@code Workflow-0001} of {@code grp-a.zip}. */
    private static String grpA() {
        return text(WorkspaceWriter.render(new SecretRedactor().redact(new ArchiveReader().read(
            ArtifactFixtures.bytes("grp-a.zip"))), GROUP, "jdoe").files().get(
                "dev/jdoe/workflows/GRP-01/Workflow-0001.xml"));
    }

    @Test
    void connectionsInAnotherOrderAreEquivalent() {
        byte[] a = ConnectionOrderFixtures.bytes("workflow-a.xml");
        byte[] b = ConnectionOrderFixtures.bytes("workflow-b.xml");
        String module0002 = """
                <Connection moduleOutId="4">
                  <ConnectionId>9</ConnectionId>
                </Connection>
                <Connection moduleOutId="3">
                  <ConnectionId>6</ConnectionId>
                </Connection>
            """;
        String swapped = """
                <Connection moduleOutId="3">
                  <ConnectionId>6</ConnectionId>
                </Connection>
                <Connection moduleOutId="4">
                  <ConnectionId>9</ConnectionId>
                </Connection>
            """;

        assertThat(archives.equivalent(WORKFLOW, a, b)).isTrue();
        assertThat(archives.equivalent(WORKFLOW, bytes(grpA()), bytes(
            ConnectionOrderFixtures.edit(grpA(), module0002, swapped)))).isTrue();
    }

    @Test
    void realDifferencesOfConnectionsStayDifferences() {
        byte[] a = ConnectionOrderFixtures.bytes("workflow-a.xml");

        ConnectionOrderFixtures.realDifferences().forEach((difference, other) ->
            assertThat(archives.equivalent(WORKFLOW, a, bytes(other))).as(difference).isFalse());
    }

    @Test
    void theConnectionOrderedRenderingKeepsEverythingElse() {
        byte[] a = ConnectionOrderFixtures.bytes("workflow-a.xml");
        byte[] b = ConnectionOrderFixtures.bytes("workflow-b.xml");
        String withVolatile = "<Workflow><WorkflowName>W</WorkflowName><LastUpdate>01.01.2026"
            + " 00:00:00</LastUpdate><WorkflowUId>u</WorkflowUId><WorkflowModule>"
            + "<Connection moduleOutId=\"2\"/><Connection moduleOutId=\"1\"/></WorkflowModule>"
            + "</Workflow>";

        assertThat(archives.connectionOrdered(WORKFLOW, a)).isEqualTo(
            archives.connectionOrdered(WORKFLOW, b)).isEqualTo(b);
        assertThat(text(archives.connectionOrdered(WORKFLOW, bytes(withVolatile))))
            .contains("<LastUpdate>01.01.2026 00:00:00</LastUpdate>", "<WorkflowUId>u<")
            .containsSubsequence("moduleOutId=\"1\"", "moduleOutId=\"2\"");
        // an embedded document, another file and unparsable XML: an unchanged copy
        for (String path : List.of("dev/jdoe/modules/XSLT Converter/M/xslt.stylesheet.xsl",
            "dev/jdoe/modules/A/M/x.bin", WORKFLOW)) {
            byte[] file = bytes(path.endsWith(".xml") ? "<Workflow><WorkflowModule>" : "<x>\n"
                + "  <b/><a/>\n</x>\n");
            byte[] copy = archives.connectionOrdered(path, file);
            assertThat(copy).as(path).isEqualTo(file).isNotSameAs(file);
        }
        assertThat(Arrays.equals(a, b)).isFalse();
    }
}
