package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.RepositoryReference;
import de.dadecker.inubit.mcp.domain.model.RepositoryReference.Kind;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T006 (research D-3): the repository files an artifact file references, as INUBIT and the XSLT
 * and schema resolvers see them.
 */
class RepositoryReferencesTest {

    private static final GroupId DEV = new GroupId("dev");
    private static final String XSL = "xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\"";
    private static final String XS = "xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"";
    private static final WorkspacePath WORKFLOW = WorkspacePath.workflow(DEV, "jdoe", "DG", "WF");
    private static final WorkspacePath MODULE = WorkspacePath.module(DEV, "jdoe", "Mapping", "M");
    private static final WorkspacePath EMBEDDED =
        WorkspacePath.embedded(DEV, "jdoe", "Mapping", "M", "XSLT", "xsl");
    private static final WorkspacePath STYLESHEET =
        WorkspacePath.repository(DEV, "jdoe", "/Root/jdoe/xsl/main/a.xsl");
    private static final WorkspacePath SCHEMA =
        WorkspacePath.repository(DEV, "jdoe", "/Root/jdoe/xsd/msg/order.xsd");

    private static List<RepositoryReference> scan(WorkspacePath from, String content) {
        return new WorkspaceInspector().repositoryReferences(from,
            content.getBytes(StandardCharsets.UTF_8));
    }

    private static RepositoryReference uri(WorkspacePath from, String to) {
        return new RepositoryReference(from, to, Kind.URI);
    }

    private static RepositoryReference relative(WorkspacePath from, String to) {
        return new RepositoryReference(from, to, Kind.RELATIVE);
    }

    @Test
    void aWorkflowReferencesByUriInTextAttributesAndAnEscapedInlineStylesheet() {
        String workflow = """
            <Workflow><WorkflowModule><ModuleId>1</ModuleId>
              <Properties><Property name="Note">inubitrepository:/Root/jdoe/xml/note.xml</Property>
              <Property name="Ref" value="inubitrepository:/Root/jdoe/xml/attr.xml"/></Properties>
              <Assignments><copy><from><xsl name="x">&lt;?xml version="1.0"?&gt;
            &lt;xsl:stylesheet %s version="2.0"&gt;
              &lt;xsl:import href="inubitrepository:/Root/jdoe/xsl/inline.xsl"/&gt;
              &lt;xsl:variable name="v" select="'inubitrepository:/Root/jdoe/xsl/literal.xsl'"/&gt;
            &lt;/xsl:stylesheet&gt;</xsl></from></copy></Assignments>
            </WorkflowModule></Workflow>
            """.formatted(XSL);

        assertThat(scan(WORKFLOW, workflow)).containsExactly(
            uri(WORKFLOW, "/Root/jdoe/xml/note.xml"),
            uri(WORKFLOW, "/Root/jdoe/xml/attr.xml"),
            uri(WORKFLOW, "/Root/jdoe/xsl/inline.xsl"));
    }

    @Test
    void anUnparsableInlineFragmentIsScannedAsText() {
        String workflow = "<Workflow><xsl>&lt;xsl:import href=\"inubitrepository:"
            + "/Root/jdoe/xsl/fragment.xsl\"/&gt;</xsl></Workflow>";

        assertThat(scan(WORKFLOW, workflow))
            .containsExactly(uri(WORKFLOW, "/Root/jdoe/xsl/fragment.xsl"));
    }

    @Test
    void aModuleXmlReferencesByUri() {
        String module = """
            <Properties version="4.1">
              <Property name="Schema">inubitrepository:/Root/jdoe/xsd/order.xsd</Property>
              <Property name="Other">inubitrepository:/Root/jdoe/xsd/order.xsd</Property>
              <Property name="XSLT">@file:XSLT.xsl</Property>
            </Properties>
            """;

        assertThat(scan(MODULE, module))
            .as("a path named twice is one reference")
            .containsExactly(uri(MODULE, "/Root/jdoe/xsd/order.xsd"));
    }

    @Test
    void anEmbeddedStylesheetReferencesOnlyThroughImportAndIncludeNotThroughStringLiterals() {
        String stylesheet = """
            <xsl:stylesheet %s version="2.0">
              <xsl:import href="inubitrepository:/Root/jdoe/xsl/common.xsl"/>
              <xsl:include href="inubitrepository://Root/jdoe/xsl/more.xsl"/>
              <xsl:include href="local.xsl"/>
              <xsl:variable name="u" select="'inubitrepository:/Root/jdoe/xsl/select.xsl'"/>
              <xsl:variable name="t">inubitrepository:/Root/jdoe/xsl/text.xsl</xsl:variable>
              <xsl:template match="/"><out ref="inubitrepository:/Root/jdoe/x.xml"/></xsl:template>
            </xsl:stylesheet>
            """.formatted(XSL);

        assertThat(scan(EMBEDDED, stylesheet))
            .as("relative locations count in repository files only; literals never (spike 10)")
            .containsExactly(
                uri(EMBEDDED, "/Root/jdoe/xsl/common.xsl"),
                uri(EMBEDDED, "/Root/jdoe/xsl/more.xsl"));
    }

    @Test
    void aRepositoryStylesheetResolvesRelativeImportsAndIncludesAgainstItsOwnPath() {
        String stylesheet = """
            <xsl:stylesheet %s version="2.0">
              <xsl:import href="b.xsl"/>
              <xsl:include href="../common/c.xsl"/>
              <xsl:include href="./sub/d.xsl"/>
              <xsl:import href="inubitrepository:/Root/other/xsl/e.xsl"/>
              <xsl:include href="http://example.test/f.xsl"/>
              <xsl:include href="file:///tmp/g.xsl"/>
              <xsl:include href="/Root/jdoe/h.xsl"/>
              <xsl:include href="../../../../../outside.xsl"/>
              <xsl:variable name="v" select="'i.xsl'"/>
            </xsl:stylesheet>
            """.formatted(XSL);

        assertThat(scan(STYLESHEET, stylesheet)).containsExactly(
            relative(STYLESHEET, "/Root/jdoe/xsl/main/b.xsl"),
            relative(STYLESHEET, "/Root/jdoe/xsl/common/c.xsl"),
            relative(STYLESHEET, "/Root/jdoe/xsl/main/sub/d.xsl"),
            uri(STYLESHEET, "/Root/other/xsl/e.xsl"));
    }

    @Test
    void aRepositorySchemaResolvesImportIncludeAndRedefine() {
        String schema = """
            <xs:schema %s targetNamespace="urn:order">
              <xs:import namespace="urn:types" schemaLocation="types.xsd"/>
              <xs:import namespace="http://www.w3.org/XML/1998/namespace"/>
              <xs:include schemaLocation="../common/inc.xsd"/>
              <xs:redefine schemaLocation="../../base.xsd"/>
              <xs:import namespace="urn:x" schemaLocation="inubitrepository:/Root/jdoe/x.xsd"/>
              <xs:annotation><xs:documentation>see other.xsd</xs:documentation></xs:annotation>
            </xs:schema>
            """.formatted(XS);

        assertThat(scan(SCHEMA, schema)).extracting(RepositoryReference::to,
            RepositoryReference::kind).containsExactly(
                tuple("/Root/jdoe/xsd/msg/types.xsd", Kind.RELATIVE),
                tuple("/Root/jdoe/xsd/common/inc.xsd", Kind.RELATIVE),
                tuple("/Root/jdoe/base.xsd", Kind.RELATIVE),
                tuple("/Root/jdoe/x.xsd", Kind.URI));
    }

    @Test
    void aSchemaInsideAnotherRepositoryDocumentResolvesToo() {
        WorkspacePath wsdl = WorkspacePath.repository(DEV, "jdoe", "Root/jdoe/wsdl/svc.wsdl");
        String content = """
            <definitions xmlns="http://schemas.xmlsoap.org/wsdl/"><types>
              <xs:schema %s><xs:import schemaLocation="../xsd/msg.xsd"/></xs:schema>
            </types></definitions>
            """.formatted(XS);

        assertThat(scan(wsdl, content))
            .containsExactly(relative(wsdl, "/Root/jdoe/xsd/msg.xsd"));
    }

    @Test
    void percentEscapesAreDecodedAndAPlusStays() {
        String stylesheet = """
            <xsl:stylesheet %s version="2.0">
              <xsl:import href="inubitrepository:/Root/jdoe/xsl/my%%20file%%C3%%A4+1.xsl"/>
              <xsl:include href="sub%%20dir/x%%2By.xsl"/>
              <xsl:include href="bad%%zz.xsl"/>
            </xsl:stylesheet>
            """.formatted(XSL);

        assertThat(scan(STYLESHEET, stylesheet)).containsExactly(
            uri(STYLESHEET, "/Root/jdoe/xsl/my fileä+1.xsl"),
            relative(STYLESHEET, "/Root/jdoe/xsl/main/sub dir/x+y.xsl"),
            relative(STYLESHEET, "/Root/jdoe/xsl/main/bad%zz.xsl"));
    }

    @Test
    void unparsableXmlHasNoReferences() {
        assertThat(scan(STYLESHEET, "<xsl:stylesheet " + XSL + "><xsl:import href=\"b.xsl\"/>"))
            .isEmpty();
        assertThat(scan(EMBEDDED, "var u = 'inubitrepository:/Root/jdoe/js/lib.js';")).isEmpty();
        assertThat(scan(SCHEMA, "")).isEmpty();
        assertThat(scan(SCHEMA, "<!DOCTYPE x [<!ENTITY e \"inubitrepository:/Root/jdoe/a\">]>"
            + "<x>&e;</x>")).isEmpty();
        assertThat(RepositoryReferences.scan(MODULE, new byte[] {(byte) 0xff, 0, 1}))
            .isEmpty();
    }
}
