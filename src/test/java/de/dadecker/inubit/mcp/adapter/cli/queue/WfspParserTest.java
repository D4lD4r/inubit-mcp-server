package de.dadecker.inubit.mcp.adapter.cli.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import de.dadecker.inubit.mcp.domain.model.RawEntryContent.PartResult;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent.PartResult.Failed;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent.PartResult.Read;
import de.dadecker.inubit.mcp.domain.model.RawVariable;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The INUBIT variables file ({@code .wfsp}, research D-13): root {@code Properties}, one
 * {@code Property} per variable with a required {@code name}, an optional {@code type} and the
 * text as value, in file order; any other structure, a DTD or malformed XML is the fixed
 * "unexpected format" failure, never the parser's message.
 */
class WfspParserTest {

    private static final String UNEXPECTED_FORMAT =
        "unexpected format; the unsupported INUBIT interface may have changed";

    private static final String VALID = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<Properties version=\"4.1\">\n"
        + "  <Property name=\"ISModuleName\">Module-0001</Property>\n"
        + "  <Property name=\"var.flag\" type=\"Boolean\">false</Property>\n"
        + "  <Property name=\"var.doc\" type=\"XmlDocument\">&lt;a x=&quot;1&quot;&gt;"
        + "<![CDATA[acme]]>&#xE4;&lt;/a&gt;</Property>\n"
        + "  <Property name=\"var.empty\"/>\n"
        + "</Properties>\n";

    @Test
    void readsEveryTypeWithTheTextAsValueInFileOrder() {
        String wfsp = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Properties version="4.1">
              <Property name="ISWorkflowName">Workflow-0001</Property>
              <Property name="var.flag" type="Boolean">true</Property>
              <Property name="var.count" type="Integer">42</Property>
              <Property name="ISFileSize" type="Long">1048576</Property>
              <Property name="var.order" type="XmlDocument">&lt;?xml version="1.0"?&gt;\
            &lt;Order id=&quot;Order-0001&quot;&gt;&lt;Customer&gt;acme &amp;amp; globex\
            &lt;/Customer&gt;&lt;/Order&gt;</Property>
            </Properties>
            """;

        assertThat(parse(wfsp)).containsExactly(
            new RawVariable("ISWorkflowName", Optional.empty(), "Workflow-0001"),
            new RawVariable("var.flag", Optional.of("Boolean"), "true"),
            new RawVariable("var.count", Optional.of("Integer"), "42"),
            new RawVariable("ISFileSize", Optional.of("Long"), "1048576"),
            new RawVariable("var.order", Optional.of("XmlDocument"),
                "<?xml version=\"1.0\"?><Order id=\"Order-0001\"><Customer>acme &amp; globex"
                    + "</Customer></Order>"));
    }

    @Test
    void keepsTheValueTextAsWrittenIncludingWhitespaceCdataAndCharacterReferences() {
        String wfsp = "<Properties>"
            + "<Property name=\"var.text\">  line 1&#10;line 2\t</Property>"
            + "<Property name=\"var.cdata\"><![CDATA[<a>Module-0001</a>]]> tail</Property>"
            + "<Property name=\"var.umlaut\">Gr\u00fc\u00dfe</Property>"
            + "</Properties>";

        assertThat(parse(wfsp)).containsExactly(
            new RawVariable("var.text", Optional.empty(), "  line 1\nline 2\t"),
            new RawVariable("var.cdata", Optional.empty(), "<a>Module-0001</a> tail"),
            new RawVariable("var.umlaut", Optional.empty(), "Gr\u00fc\u00dfe"));
    }

    @Test
    void anEmptyElementIsAnEmptyValue() {
        String wfsp = "<Properties version=\"4.1\">"
            + "<Property name=\"var.empty\"/>"
            + "<Property name=\"var.blank\" type=\"Integer\"></Property>"
            + "</Properties>";

        assertThat(parse(wfsp)).containsExactly(
            new RawVariable("var.empty", Optional.empty(), ""),
            new RawVariable("var.blank", Optional.of("Integer"), ""));
    }

    @Test
    void anEmptyRootHasNoVariablesAndCommentsAreIgnored() {
        assertThat(parse("<Properties/>")).isEmpty();
        assertThat(parse("<!-- c --><Properties><!-- c --><?pi x?>"
            + "<Property name=\"a\">x<!-- c -->y</Property></Properties>"))
            .containsExactly(new RawVariable("a", Optional.empty(), "xy"));
    }

    @Test
    void keepsDuplicateNamesInFileOrder() {
        assertThat(parse("<Properties><Property name=\"a\">1</Property>"
            + "<Property name=\"b\">2</Property><Property name=\"a\">3</Property></Properties>"))
            .extracting(RawVariable::value).containsExactly("1", "2", "3");
    }

    @Test
    void readsTheDeclaredEncoding() {
        byte[] latin1 = ("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>"
            + "<Properties><Property name=\"a\">\u00e4\u00f6\u00fc</Property></Properties>")
            .getBytes(StandardCharsets.ISO_8859_1);

        assertThat(WfspParser.parse(latin1)).isEqualTo(new Read<>(
            List.of(new RawVariable("a", Optional.empty(), "\u00e4\u00f6\u00fc"))));
    }

    @Test
    void readsTenThousandProperties() {
        StringBuilder wfsp = new StringBuilder("<Properties version=\"4.1\">\n");
        for (int i = 0; i < 10_000; i++) {
            wfsp.append("  <Property name=\"var.").append(i).append("\" type=\"Integer\">")
                .append(i).append("</Property>\n");
        }
        wfsp.append("</Properties>\n");

        List<RawVariable> variables = assertTimeoutPreemptively(Duration.ofSeconds(10),
            () -> parse(wfsp.toString()));

        assertThat(variables).hasSize(10_000);
        assertThat(variables.get(0)).isEqualTo(
            new RawVariable("var.0", Optional.of("Integer"), "0"));
        assertThat(variables.get(9_999)).isEqualTo(
            new RawVariable("var.9999", Optional.of("Integer"), "9999"));
    }

    @Test
    void refusesADoctypeWithoutResolvingEntities() {
        String wfsp = "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE Properties [<!ENTITY secret SYSTEM \"file:///etc/passwd\">]>"
            + "<Properties><Property name=\"a\">&secret;</Property></Properties>";

        assertUnexpectedFormat(wfsp);
    }

    @Test
    void refusesAnInternalEntityExpansion() {
        String wfsp = "<!DOCTYPE Properties [<!ENTITY a \"aaaaaaaaaa\">"
            + "<!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">]>"
            + "<Properties><Property name=\"a\">&b;</Property></Properties>";

        assertUnexpectedFormat(wfsp);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "   ",
        "not xml at all",
        "<Variables><Property name=\"a\">1</Property></Variables>",
        "<properties><Property name=\"a\">1</Property></properties>",
        "<Properties><Variable name=\"a\">1</Variable></Properties>",
        "<Properties><Property>1</Property></Properties>",
        "<Properties><Property type=\"Integer\">1</Property></Properties>",
        "<Properties><Property name=\"\">1</Property></Properties>",
        "<Properties><Property name=\"a\"><Value>1</Value></Property></Properties>",
        "<Properties><Property name=\"a\"><Property name=\"b\">x</Property></Property>"
            + "</Properties>",
        "<Properties><Property name=\"a\"><Property name=\"b\"/></Property></Properties>",
        "<Properties><Other><Property name=\"b\"/></Other></Properties>",
        "<Property name=\"a\"/>",
        "<!-- no root element -->",
        "<Properties><Property name=\"a\">1<b/>2</Property></Properties>",
        "<Properties>text<Property name=\"a\">1</Property></Properties>",
        "<Properties><Property name=\"a\">1</Property>tail</Properties>",
        "<Properties><Property name=\"a\">1</Property>",
        "<Properties><Property name=\"a\">1</Properties>",
        "<Properties><Property name=\"a\">1</Property></Properties><Properties/>",
        "<Properties><Property name=\"a\">&undeclared;</Property></Properties>",
        "<Properties><Property name=\"a\" name=\"b\">1</Property></Properties>",
        "<?xml version=\"1.0\" encoding=\"x-unknown-0001\"?><Properties/>",
    })
    void refusesAnyOtherStructure(String wfsp) {
        assertUnexpectedFormat(wfsp);
    }

    @Test
    void refusesDeepNestingAtTheFirstUnexpectedElement() {
        String wfsp = "<Properties><Property name=\"a\">" + "<x>".repeat(100_000)
            + "</x>".repeat(100_000) + "</Property></Properties>";

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertUnexpectedFormat(wfsp));
    }

    @Test
    void refusesInvalidBytesWithTheFixedReason() {
        byte[] invalid = {'<', 'P', 'r', 'o', (byte) 0xC3, (byte) 0x28, '>'};

        assertThat(WfspParser.parse(invalid)).isEqualTo(new Failed<>(UNEXPECTED_FORMAT));
    }

    @Test
    void everyCutOffDocumentIsTheFixedFailure() {
        byte[] wfsp = bytes(VALID);
        int end = VALID.indexOf("</Properties>") + "</Properties>".length();
        assertThat(parse(VALID)).hasSize(4);

        for (int length = 0; length < end; length++) {
            byte[] prefix = Arrays.copyOf(wfsp, length);
            assertThat(WfspParser.parse(prefix)).as("prefix of %d bytes", length)
                .isEqualTo(new Failed<>(UNEXPECTED_FORMAT));
        }
    }

    @Test
    void corruptedDocumentsGiveAResultAndNeverThrow() {
        byte[] wfsp = bytes(VALID);
        Random random = new Random(20_261_110L);

        // ASCII only: invalid UTF-8 is covered above and makes the JDK parser print to stderr
        for (int round = 0; round < 5_000; round++) {
            byte[] corrupted = wfsp.clone();
            for (int flips = 1 + random.nextInt(4); flips > 0; flips--) {
                corrupted[random.nextInt(corrupted.length)] = (byte) random.nextInt(128);
            }
            assertThat(WfspParser.parse(corrupted)).isInstanceOfAny(Read.class, Failed.class);
        }
    }

    @Test
    void theFailureReasonNeverCarriesContent() {
        String wfsp = "<Properties><Property name=\"" + TestSecrets.CONFIGURED_PASSWORD + "\">"
            + TestSecrets.CREDENTIAL_PAYLOAD_VALUE + "<" + TestSecrets.TRUST_STORE_PASSWORD
            + "/></Property><" + TestSecrets.CONFIGURED_PASSWORD;

        PartResult<List<RawVariable>> result = WfspParser.parse(bytes(wfsp));

        assertThat(result).isEqualTo(new Failed<>(UNEXPECTED_FORMAT));
        TestSecrets.assertNoLeak(((Failed<List<RawVariable>>) result).reason());
    }

    private static void assertUnexpectedFormat(String wfsp) {
        assertThat(WfspParser.parse(bytes(wfsp))).isEqualTo(new Failed<>(UNEXPECTED_FORMAT));
    }

    private static List<RawVariable> parse(String wfsp) {
        PartResult<List<RawVariable>> result = WfspParser.parse(bytes(wfsp));
        assertThat(result).isInstanceOf(Read.class);
        return ((Read<List<RawVariable>>) result).value();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
