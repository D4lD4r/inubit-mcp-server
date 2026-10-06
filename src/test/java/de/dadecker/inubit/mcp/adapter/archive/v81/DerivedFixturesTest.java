package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * T003: the defect fixtures are {@code grp-a.zip} unzipped with exactly one documented edit of
 * {@code workflow/workflow.xml}, and the XSLT/XML fixtures are (or deliberately are not)
 * well-formed as the later checks expect.
 */
class DerivedFixturesTest {

    private static final String DEFECTS = "defects/";
    private static final String XSLT = "xslt/";

    @ParameterizedTest
    @ValueSource(strings = {"dangling-edge", "id-collision", "demux-key-unmatched",
        "missing-module", "repository-ref-missing", "variable-unresolved"})
    void eachDefectIsGroupAWithOneEditOfTheWorkflowFile(String defect) {
        Map<String, byte[]> original = ArtifactFixtures.entries("grp-a.zip");

        for (Map.Entry<String, byte[]> entry : original.entrySet()) {
            byte[] derived = ArtifactFixtures.bytes(DEFECTS + defect + "/" + entry.getKey());
            if (entry.getKey().equals("workflow/workflow.xml")) {
                assertThat(derived).as("%s: the edited workflow file", defect)
                    .isNotEqualTo(entry.getValue());
                assertThat(parse(derived)).as("%s: still well-formed", defect).isNotNull();
            } else {
                assertThat(derived).as("%s: %s unchanged", defect, entry.getKey())
                    .isEqualTo(entry.getValue());
            }
        }
        String note = new String(ArtifactFixtures.bytes(DEFECTS + defect + "/DEFECT.md"),
            StandardCharsets.UTF_8);
        assertThat(note.strip()).as("%s: one line naming the edit", defect).isNotBlank()
            .doesNotContain("\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain.xsl", "standins.xsl", "unknown-extension.xsl",
        "syntax-error.xsl", "repository-import.xsl", "common.xsl", "input.xml", "schema.xsd",
        "valid.xml", "invalid.xml"})
    void xsltAndXmlFixturesAreWellFormed(String name) {
        assertThat(parse(ArtifactFixtures.bytes(XSLT + name))).as(name).isNotNull();
    }

    @Test
    void theStandInStylesheetCallsTheInubitExtensionsInTheirRecordedNamespaces() {
        String text = new String(ArtifactFixtures.bytes(XSLT + "standins.xsl"),
            StandardCharsets.UTF_8);

        assertThat(text).contains("xmlns:Misc=\"java:com.inubit.ibis.xsltext.Misc\"",
            "xmlns:Formatter=\"java:com.inubit.ibis.xsltext.Formatter\"",
            "xmlns:UUID=\"java:java.util.UUID\"", "xmlns:Thread=\"java:java.lang.Thread\"",
            "Misc:guid()", "Formatter:changeDateFormat(", "Formatter:getDateTime(",
            "UUID:randomUUID()", "Thread:sleep(");
        assertThat(new String(ArtifactFixtures.bytes(XSLT + "repository-import.xsl"),
            StandardCharsets.UTF_8))
            .contains("href=\"inubitrepository:/Root/OWNERS/xsl/common.xsl\"");
    }

    @Test
    void theNotWellFormedDocumentFailsOnItsDocumentedLine() {
        assertThatThrownBy(() -> parse(ArtifactFixtures.bytes(XSLT + "not-well-formed.xml")))
            .hasCauseInstanceOf(SAXParseException.class)
            .satisfies(e -> assertThat(((SAXParseException) e.getCause()).getLineNumber())
                .isEqualTo(4));
    }

    private static Document parse(byte[] xml) {
        try (InputStream in = new ByteArrayInputStream(xml)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler()); // throws, prints nothing
            return builder.parse(in);
        } catch (SAXException e) {
            throw new IllegalStateException("not well-formed", e);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
