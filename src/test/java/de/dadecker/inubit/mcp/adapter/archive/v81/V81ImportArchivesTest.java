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
    void anImportMayTurnThePlainSaxonFactoryIntoASaxonicaFactory() {
        // issue #55; ProfessionalTransformerFactory stands in for the Saxon-EE factory
        String path = "dev/o/modules/XSLT Converter/M/module.xml";
        String plain = "<Properties version=\"4.1\">"
            + "<Property name=\"xslt.stylesheet\">s</Property>"
            + "<Property name=\"xslt.transformer\">net.sf.saxon.TransformerFactoryImpl</Property>"
            + "</Properties>";
        String saxonica = plain.replace("net.sf.saxon.TransformerFactoryImpl",
            "com.saxonica.config.ProfessionalTransformerFactory");

        assertThat(archives.equivalent(path, bytes(plain), bytes(saxonica))).isFalse();
        assertThat(archives.importedAs(path, bytes(plain), bytes(saxonica))).isTrue();
        assertThat(archives.importedAs(path, bytes(plain), bytes(plain))).isTrue();
        assertThat(archives.importedAs(path, bytes(saxonica), bytes(saxonica))).isTrue();
        assertThat(archives.importedAs(path, bytes(saxonica), bytes(plain)))
            .as("INUBIT does not turn a saxonica factory into the plain one").isFalse();
        assertThat(archives.importedAs(path, bytes(plain), bytes(plain.replace(
            "net.sf.saxon.TransformerFactoryImpl", "org.example.OtherFactory")))).isFalse();
        assertThat(archives.importedAs(path, bytes(plain), bytes(saxonica.replace(">s<",
            ">t<")))).as("another difference stays a difference").isFalse();
        assertThat(archives.importedAs(path, bytes(plain), null)).isFalse();
        assertThat(archives.importedAs("dev/o/modules/A/M/x.bin", bytes("a"), bytes("b")))
            .isFalse();
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
    void theUserCommentThatTheExportAppendsIsNotPartOfTheCheckinComment() {
        // An artifact with a Workbench user comment: the export appends it as a last ### segment.
        assertThat(archives.checkinComment(bytes("<Module><CheckinComment>Default"
            + "CommitCommentImport###DefaultCommitCommentImport###r######TICKET-1 note"
            + "</CheckinComment><UserComment>TICKET-1 note</UserComment></Module>")))
            .contains("DefaultCommitCommentImport###DefaultCommitCommentImport###r######");
        // Only a whole last segment equal to the user comment goes, nothing else.
        assertThat(archives.checkinComment(bytes("<Module><CheckinComment>Default"
            + "CommitCommentImport###r: TICKET-1 note</CheckinComment>"
            + "<UserComment>TICKET-1 note</UserComment></Module>")))
            .contains("DefaultCommitCommentImport###r: TICKET-1 note");
        assertThat(archives.checkinComment(bytes("<Module><CheckinComment>Default"
            + "CommitCommentImport###r######</CheckinComment><UserComment/></Module>")))
            .contains("DefaultCommitCommentImport###r######");
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

    /** The neutral excerpt of an observed import ({@code fixtures/v8_1/variable-order}). */
    private static String variableOrder(String name) {
        try (var in = V81ImportArchivesTest.class.getResourceAsStream(
            "/fixtures/v8_1/variable-order/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static final String NEW_VARIABLE = """
            <Variable comment="JD: new optional input" isInputVariable="true" name="var.flag" \
        type="xs:boolean">
              <DefaultValue>false</DefaultValue>
            </Variable>
        """;
    private static final String PROCESS_ID = """
            <Variable comment="the process id" name="var.processId" type="xs:string"/>
        """;
    private static final String CONNECTION_29 = """
            <Connection moduleOutId="1002">
              <ConnectionId>29</ConnectionId>
              <StyleSheet labelPosition="73.82352941176471"/>
            </Connection>
        """;
    private static final String CONNECTION_60 = """
            <Connection moduleOutId="1003">
              <ConnectionId>60</ConnectionId>
              <StyleSheet labelPosition="50.0"/>
            </Connection>
        """;

    /** {@code sent} with the new variable moved behind {@code var.processId}, as INUBIT did. */
    private static String variableMoved(String sent) {
        return ConnectionOrderFixtures.edit(ConnectionOrderFixtures.edit(sent, NEW_VARIABLE, ""),
            PROCESS_ID, PROCESS_ID + NEW_VARIABLE);
    }

    @Test
    void whatInubitReordersWhenItStoresAWorkflowIsImportedAsSent() {
        // observed on 8.1 (import of a changed workflow): the re-export lists the connections of
        // an untouched Demultiplexer in another order (feature 007) and puts a new workflow
        // variable behind an existing one instead of at the end (INUBIT keeps the variables
        // of a workflow in hash order); only the check-in comment differs besides
        String sent = variableOrder("workflow-sent.xml");
        String stored = variableOrder("workflow-stored.xml");
        String connectionsSwapped = ConnectionOrderFixtures.edit(sent,
            CONNECTION_29 + CONNECTION_60, CONNECTION_60 + CONNECTION_29);

        assertThat(sent).isNotEqualTo(stored);
        assertThat(archives.importedAs(WORKFLOW, bytes(sent), bytes(stored))).isTrue();
        assertThat(archives.importedAs(WORKFLOW, bytes(stored), bytes(sent))).isTrue();
        assertThat(archives.equivalent(WORKFLOW, bytes(sent), bytes(variableMoved(sent))))
            .as("the variables alone").isTrue();
        assertThat(archives.equivalent(WORKFLOW, bytes(sent), bytes(connectionsSwapped)))
            .as("the connections alone").isTrue();
    }

    @Test
    void realDifferencesOfVariablesStayDifferences() {
        String sent = variableOrder("workflow-sent.xml");
        String stored = variableOrder("workflow-stored.xml");
        Map<String, String> differences = new java.util.LinkedHashMap<>();
        differences.put("another default value", stored.replace(
            "<DefaultValue>false</DefaultValue>", "<DefaultValue>true</DefaultValue>"));
        differences.put("another type", stored.replace("type=\"xs:boolean\"",
            "type=\"xs:string\""));
        differences.put("no longer an input variable", stored.replace(
            "isInputVariable=\"true\" name=\"var.flag\"", "name=\"var.flag\""));
        differences.put("another name", stored.replace("name=\"var.flag\"",
            "name=\"var.flags\""));
        differences.put("a variable missing", ConnectionOrderFixtures.edit(stored, NEW_VARIABLE,
            ""));
        differences.put("a variable more", ConnectionOrderFixtures.edit(stored, PROCESS_ID,
            PROCESS_ID + PROCESS_ID.replace("var.processId", "var.extra")));
        differences.put("another comment of a variable", stored.replace("the kind",
            "the type"));
        differences.put("the properties of a module in another order (still significant)",
            ConnectionOrderFixtures.edit(stored, """
                  <Property name="Module-0003(1003)@@@Description">synchronous</Property>
                  <Property name="Module-0002(1002)@@@Description">asynchronous</Property>
            """, """
                  <Property name="Module-0002(1002)@@@Description">asynchronous</Property>
                  <Property name="Module-0003(1003)@@@Description">synchronous</Property>
            """));

        differences.forEach((difference, server) -> {
            assertThat(server).as(difference).isNotEqualTo(stored);
            assertThat(archives.importedAs(WORKFLOW, bytes(sent), bytes(server)))
                .as(difference).isFalse();
        });
    }

    @Test
    void theFingerprintFormKeepsTheOrderOfTheVariables() {
        // backup manifests keep fingerprints over the connection-ordered form (feature 007,
        // contract P-4/P-5); sorting the variables there would make every recorded state look
        // changed, and two exports of one stored version list the variables in the same order
        String sent = variableOrder("workflow-sent.xml");

        assertThat(archives.connectionOrdered(WORKFLOW, bytes(sent))).isNotEqualTo(
            archives.connectionOrdered(WORKFLOW, bytes(variableMoved(sent))));
        assertThat(archives.differsOnlyInConnectionOrder(WORKFLOW,
            archives.connectionOrdered(WORKFLOW, bytes(sent)),
            archives.connectionOrdered(WORKFLOW, bytes(variableMoved(sent))))).isFalse();
    }

    // --- what INUBIT normalizes when it stores a workflow or a stylesheet (observed on 8.1) ---

    private static final String STYLESHEET = "dev/jdoe/modules/XSLT Converter/Module-0023/"
        + "xslt.stylesheet.xsl";
    private static final String ADDED_COMMENT =
        "    <Comment>Globally used throw module</Comment>\n";

    /** The neutral excerpt of an observed import ({@code fixtures/v8_1/import-normalization}). */
    private static String normalization(String name) {
        try (var in = V81ImportArchivesTest.class.getResourceAsStream(
            "/fixtures/v8_1/import-normalization/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void whatInubitNormalizesInAStoredWorkflowIsImportedAsSent() {
        // the re-export of a new workflow: a module without a comment got the comment of the
        // module it uses, the unused namespace declaration of Variables is gone, ">" is no
        // longer escaped, connections and variables come in another order
        String sent = normalization("workflow-sent.xml");
        String stored = normalization("workflow-stored.xml");

        assertThat(archives.importedAs(WORKFLOW, bytes(sent), bytes(stored))).isTrue();
        assertThat(archives.equivalent(WORKFLOW, bytes(sent), bytes(
            ConnectionOrderFixtures.edit(stored, ADDED_COMMENT, "")))).as("without the comment")
            .isTrue();
        assertThat(archives.equivalent(WORKFLOW, bytes(sent), bytes(stored)))
            .as("the added comment is only what an import makes of the file").isFalse();
        assertThat(archives.importedAs(WORKFLOW, bytes(stored), bytes(sent)))
            .as("INUBIT does not remove a comment").isFalse();
    }

    @Test
    void realDifferencesOfAStoredWorkflowStayDifferences() {
        String sent = normalization("workflow-sent.xml");
        String stored = normalization("workflow-stored.xml");
        String typed = "name=\"var.test.server\" type=\"xs:string\"";
        String usesIs = "name=\"var.test.server\" type=\"is:anyElement\"";
        Map<String, String> differences = new java.util.LinkedHashMap<>();
        differences.put("an existing comment changed", stored.replace("Joins the test branches",
            "Joins the branches"));
        differences.put("an existing comment removed", ConnectionOrderFixtures.edit(stored,
            "    <Comment>Joins the test branches</Comment>\n", ""));
        differences.put("an added comment with another change of the module", stored.replace(
            ">Invalid test input<", ">Invalid input<"));
        differences.put("a comment in another element than a workflow module", stored.replace(
            "<Property name=\"FaultName\">", "<Comment>x</Comment><Property name=\"FaultName\">"));
        differences.put("another attribute value", stored.replace("labelPosition=\"50.0\"/>\n"
            + "    </Connection>\n  </WorkflowModule>", "labelPosition=\"60.0\"/>\n"
            + "    </Connection>\n  </WorkflowModule>"));
        differences.put("another namespace URI", stored.replace("2001/XMLSchema",
            "2001/XMLSchema-other"));
        differences.put("an escaped text changed", stored.replace("attempt > n", "attempt < n")
            .replace("attempt < n", "attempt &lt; n"));

        differences.forEach((difference, server) -> {
            assertThat(server).as(difference).isNotEqualTo(stored);
            assertThat(archives.importedAs(WORKFLOW, bytes(sent), bytes(server)))
                .as(difference).isFalse();
        });
        // a declaration that a variable type uses is content
        assertThat(archives.importedAs(WORKFLOW, bytes(sent.replace(typed, usesIs)),
            bytes(stored.replace(typed, usesIs)))).as("a used namespace removed").isFalse();
        assertThat(archives.importedAs(WORKFLOW, bytes(sent.replace(typed, usesIs)),
            bytes(stored.replace(typed, usesIs).replace("<Variables xmlns:xs=",
                "<Variables xmlns:is=\"http://inubit.com/variables/types\" xmlns:xs="))))
            .as("the used namespace kept").isTrue();
    }

    @Test
    void aStylesheetAsInubitReserializesItIsEquivalent() {
        // INUBIT re-serializes a stylesheet: no line break between a leading comment and the
        // root, start tags on one line, line breaks in attribute values as spaces (which is
        // what any XML parser reads them as), no final line break
        String sent = normalization("stylesheet-sent.xsl");
        String stored = normalization("stylesheet-stored.xsl");

        assertThat(archives.equivalent(STYLESHEET, bytes(sent), bytes(stored))).isTrue();
        assertThat(archives.importedAs(STYLESHEET, bytes(sent), bytes(stored))).isTrue();
        assertThat(archives.equivalent(STYLESHEET.replace("xslt.stylesheet.xsl",
            "WsdlData.wsdl"), bytes(sent), bytes(stored))).isTrue();
    }

    @Test
    void realDifferencesOfAStylesheetStayDifferences() {
        String sent = normalization("stylesheet-sent.xsl");
        String stored = normalization("stylesheet-stored.xsl");
        Map<String, String> differences = new java.util.LinkedHashMap<>();
        differences.put("another attribute value", stored.replace("'#now#'", "'#today#'"));
        differences.put("spaces of an attribute value collapsed", stored.replace("(.,       '",
            "(., '"));
        differences.put("the space of xsl:text", stored.replace("<xsl:text> </xsl:text>",
            "<xsl:text>  </xsl:text>"));
        differences.put("whitespace between elements", stored.replace(
            "<acme:separator>", "\n      <acme:separator>"));
        differences.put("the leading comment", stored.replace("test input", "input"));
        differences.put("another namespace URI", stored.replace("core/1.0", "core/2.0"));
        differences.put("a namespace declaration missing", stored.replace(
            " xmlns:env=\"http://schemas.xmlsoap.org/soap/envelope/\"", ""));
        differences.put("not well-formed", stored.replace("</xsl:stylesheet>", ""));

        differences.forEach((difference, server) -> {
            assertThat(server).as(difference).isNotEqualTo(stored);
            assertThat(archives.importedAs(STYLESHEET, bytes(sent), bytes(server)))
                .as(difference).isFalse();
        });
    }

    // --- a plain value at a secret path (verify of a new workflow) --------------------------

    /**
     * A new workflow whose Workflow Connector maps variables named like certificates: the values
     * are variable names, not secrets, and cannot be placeholders (the node has no value for a
     * new module ID), but the redaction treats any property named {@code …certificate…} as one.
     */
    private static final String CONNECTOR_SENT = """
        <Workflow>
          <WorkflowName>Workflow-0001</WorkflowName>
          <WorkflowModule moduleType="technical">
            <ModuleId>1002</ModuleId>
            <ModuleName>Module-0002</ModuleName>
            <Properties version="4.1">
              <Property name="useVariableFiltering" type="Boolean">true</Property>
              <Property name="InputVariablesMap" type="Map">
                <Property name="FilenameClientCertificate">FilenameClientCertificate</Property>
                <Property name="var.alpha">var.alpha</Property>
              </Property>
            </Properties>
          </WorkflowModule>
        </Workflow>
        """;
    private static final String CONNECTOR_PLACEHOLDER =
        "${secret:WorkflowModule(1002)/InputVariablesMap/FilenameClientCertificate}";

    /** {@link #CONNECTOR_SENT} as the re-export renders it: the value as its placeholder. */
    private static String connectorRendered() {
        return CONNECTOR_SENT.replace(">FilenameClientCertificate<",
            ">" + CONNECTOR_PLACEHOLDER + "<");
    }

    @Test
    void aPlainValueAtASecretPathIsImportedAsItsPlaceholder() {
        assertThat(archives.equivalent(WORKFLOW, bytes(CONNECTOR_SENT),
            bytes(connectorRendered()))).as("the workspace comparison is unchanged").isFalse();
        assertThat(archives.importedAs(WORKFLOW, bytes(CONNECTOR_SENT),
            bytes(connectorRendered()))).isTrue();
        // a placeholder in the workspace resolves on the node and comes back as itself
        assertThat(archives.importedAs(WORKFLOW, bytes(connectorRendered()),
            bytes(connectorRendered()))).isTrue();
    }

    @Test
    void besideAPlainValueAtASecretPathRealDifferencesStayDifferences() {
        String server = connectorRendered();
        Map<String, String> differences = new java.util.LinkedHashMap<>();
        differences.put("another variable mapped", server.replace(">var.alpha<", ">var.beta<"));
        differences.put("another flag", server.replace(">true<", ">false<"));
        differences.put("the value lost on the node", server.replace(CONNECTOR_PLACEHOLDER, ""));
        differences.put("the mapping missing", server.replace(
            "<Property name=\"FilenameClientCertificate\">" + CONNECTOR_PLACEHOLDER
                + "</Property>", ""));
        differences.put("the placeholder of another path", server.replace("(1002)", "(1003)"));

        differences.forEach((difference, changed) -> {
            assertThat(changed).as(difference).isNotEqualTo(server);
            assertThat(archives.importedAs(WORKFLOW, bytes(CONNECTOR_SENT), bytes(changed)))
                .as(difference).isFalse();
        });
    }

    @Test
    void aPlainValueAtASecretPathOfAModuleIsImportedAsItsPlaceholder() {
        String path = "dev/o/modules/AS2 Connector/Module-0024/module.xml";
        String sent = "<Properties version=\"4.1\">"
            + "<Property name=\"Mime.Decrypt.Password\" type=\"Password\">plain</Property>"
            + "<Property name=\"ClientCertificateAlias\">partner</Property>"
            + "<Property name=\"ServerCertificate\" type=\"InternalDocument\""
            + " documentName=\"server.cer\">@file:ServerCertificate.bin</Property>"
            + "<Property name=\"Url\">https://as2.example.test</Property>"
            + "</Properties>";
        String server = sent.replace(">plain<", ">${secret:Mime.Decrypt.Password}<")
            .replace(">partner<", ">${secret:ClientCertificateAlias}<");

        assertThat(archives.importedAs(path, bytes(sent), bytes(server))).isTrue();
        // the reference to an extracted document stays a reference (the document is compared
        // as its own file)
        assertThat(archives.importedAs(path, bytes(sent), bytes(server.replace(
            "@file:ServerCertificate.bin", "${secret:ServerCertificate}")))).isFalse();
        assertThat(archives.importedAs(path, bytes(sent), bytes(server.replace(
            "as2.example.test", "as2.example.org")))).isFalse();
    }
}
