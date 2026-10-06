package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** T008 (research D-5): the normalized serialization of workspace XML files. */
class XmlNormalizerTest {

    private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    private static String normalize(String xml) {
        return new String(XmlNormalizer.normalize(xml.getBytes(StandardCharsets.UTF_8)),
            StandardCharsets.UTF_8);
    }

    @Test
    void elementOnlyContentIsIndentedWithTwoSpacesBehindAFixedDeclaration() {
        String inubit = "<?xml version='1.0' encoding='UTF-8'?>\n\n<Properties version=\"4.1\">\n"
            + "\t<Property name=\"ErrorSuppression\" type=\"Boolean\">false</Property>\n"
            + "\t<Property name=\"xslt.params\" type=\"List\" />\n</Properties>\n";

        assertThat(normalize(inubit)).isEqualTo(DECLARATION
            + "<Properties version=\"4.1\">\n"
            + "  <Property name=\"ErrorSuppression\" type=\"Boolean\">false</Property>\n"
            + "  <Property name=\"xslt.params\" type=\"List\"/>\n"
            + "</Properties>\n");
    }

    @Test
    void singleLineDocumentsAreIndentedToo() {
        assertThat(normalize("<a><b><c>x</c></b><d/></a>")).isEqualTo(DECLARATION
            + "<a>\n  <b>\n    <c>x</c>\n  </b>\n  <d/>\n</a>\n");
    }

    @Test
    void attributesAreSortedByNameAndEmptyElementsCollapsed() {
        assertThat(normalize("<a z='1' b=\"2\" m='3'></a>"))
            .isEqualTo(DECLARATION + "<a b=\"2\" m=\"3\" z=\"1\"/>\n");
    }

    @Test
    void textContentIsKeptExactly() {
        String text = "<p>  leading and trailing  \n\tsecond line\n</p>";

        assertThat(normalize(text)).isEqualTo(DECLARATION + text + "\n");
        assertThat(normalize("<p> </p>")).isEqualTo(DECLARATION + "<p> </p>\n");
    }

    @Test
    void embeddedXmlKeepsInubitsEscaping() {
        // spike §5: INUBIT escapes '<' and '&' only; '>' stays literal except after "]]"
        String property = "<Property name=\"xslt.stylesheet\" type=\"XmlDocument\">&lt;?xml"
            + " version=\"1.0\"?>\n&lt;xsl:stylesheet a=\"x &amp;amp; y\">]]&gt;"
            + "&lt;/xsl:stylesheet></Property>";

        assertThat(normalize(property)).isEqualTo(DECLARATION + property + "\n");
        assertThat(normalize("<a>&gt; &quot; &apos; ]]&gt;</a>"))
            .isEqualTo(DECLARATION + "<a>> \" ' ]]&gt;</a>\n");
    }

    @Test
    void mixedContentIsKeptExactly() {
        String mixed = "<root><p>a <b>bold</b>\n   <i x='1' a='2'>it</i> c</p></root>";

        assertThat(normalize(mixed)).isEqualTo(DECLARATION
            + "<root>\n  <p>a <b>bold</b>\n   <i a=\"2\" x=\"1\">it</i> c</p>\n</root>\n");
    }

    @Test
    void whitespaceMarkedAsPreservedIsKept() {
        String preserved = "<a xml:space=\"preserve\">\n <b/>\n</a>";

        assertThat(normalize(preserved)).isEqualTo(DECLARATION + preserved + "\n");
    }

    @Test
    void commentsProcessingInstructionsAndNamespacesStay() {
        String xml = "<!-- head --><?pi data?>"
            + "<r xmlns=\"urn:d\" xmlns:x=\"urn:x\" x:b=\"1\" a=\"2\"><!-- c --><x:e/></r>";

        assertThat(normalize(xml)).isEqualTo(DECLARATION + "<!-- head -->\n<?pi data?>\n"
            + "<r xmlns=\"urn:d\" xmlns:x=\"urn:x\" a=\"2\" x:b=\"1\">\n  <!-- c -->\n  <x:e/>\n"
            + "</r>\n");
    }

    @Test
    void lineEndingsBecomeLfAndOtherControlCharactersStayRecoverable() {
        assertThat(normalize("<a>\r\n  <b>x\r\ny</b>\r\n</a>\r\n"))
            .isEqualTo(DECLARATION + "<a>\n  <b>x\ny</b>\n</a>\n");
        assertThat(normalize("<a v=\"t&#9;n&#10;r&#13;\">x&#13;</a>"))
            .isEqualTo(DECLARATION + "<a v=\"t&#9;n&#10;r&#13;\">x&#13;</a>\n");
    }

    @Test
    void cdataBecomesEscapedText() {
        assertThat(normalize("<a><![CDATA[1 < 2 & 3]]></a>"))
            .isEqualTo(DECLARATION + "<a>1 &lt; 2 &amp; 3</a>\n");
    }

    @Test
    void attributeValuesAreEscaped() {
        assertThat(normalize("<a v='say \"hi\" &amp; &lt;go&gt;'/>"))
            .isEqualTo(DECLARATION + "<a v=\"say &quot;hi&quot; &amp; &lt;go>\"/>\n");
    }

    @Test
    void nonAsciiTextIsWrittenAsUtf8() {
        byte[] latin1 = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>Größe</a>"
            .getBytes(StandardCharsets.ISO_8859_1);

        assertThat(new String(XmlNormalizer.normalize(latin1), StandardCharsets.UTF_8))
            .isEqualTo(DECLARATION + "<a>Größe</a>\n");
    }

    @Test
    void documentTypeDeclarationsAndMalformedXmlAreRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> normalize(
            "<!DOCTYPE a [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><a>&e;</a>"));
        assertThatIllegalArgumentException().isThrownBy(() -> normalize("<a><b></a>"));
        assertThatIllegalArgumentException().isThrownBy(() -> normalize(""));
    }

    @Test
    void normalizingIsIdempotentAndLosslessOnTheRecordedFixtures() {
        int checked = 0;
        for (String fixture : ArtifactFixtures.EXPORTS) {
            for (Map.Entry<String, byte[]> entry : xmlEntries(fixture).entrySet()) {
                byte[] once = XmlNormalizer.normalize(entry.getValue());
                byte[] twice = XmlNormalizer.normalize(once);

                assertThat(twice).as("%s!%s idempotent", fixture, entry.getKey())
                    .isEqualTo(once);
                assertThat(XmlEquality.equal(entry.getValue(), once))
                    .as("%s!%s equal to the original", fixture, entry.getKey()).isTrue();
                checked++;
            }
        }
        assertThat(checked).isGreaterThan(30);
    }

    /** The XML entries of an export, those of its {@code Repository.zip} included. */
    static Map<String, byte[]> xmlEntries(String fixture) {
        Map<String, byte[]> xml = new java.util.LinkedHashMap<>();
        ArtifactFixtures.entries(fixture).forEach((name, bytes) -> {
            if (name.endsWith(".xml")) {
                xml.put(name, bytes);
            } else if (name.equals("Repository.zip")) {
                ArtifactFixtures.entries(bytes).forEach((inner, data) -> xml.put(name + "!"
                    + inner, data));
            }
        });
        return xml;
    }
}
