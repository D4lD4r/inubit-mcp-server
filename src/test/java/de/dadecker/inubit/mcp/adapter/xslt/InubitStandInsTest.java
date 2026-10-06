package de.dadecker.inubit.mcp.adapter.xslt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XsltTransformer;
import org.junit.jupiter.api.Test;

/**
 * T029 (research D-11, clarification 4): the deterministic stand-ins of the INUBIT extension
 * functions, with the arities found in real stylesheets.
 */
class InubitStandInsTest {

    private static final String NAMESPACES = """
        xmlns:Misc="java:com.inubit.ibis.xsltext.Misc"
        xmlns:Formatter="java:com.inubit.ibis.xsltext.Formatter"
        xmlns:ISFunctions="java:com.inubit.ibis.xsltext.ISFunctions"
        xmlns:UUID="java:java.util.UUID"
        xmlns:Thread="java:java.lang.Thread"
        xmlns:XThread="http://xml.apache.org/xalan/java/java.lang.Thread"
        xmlns:URLDecoder="java:java.net.URLDecoder"
        """;

    private InubitStandIns standIns;

    /** The text output of {@code select} evaluated on {@code <x><y>1</y></x>}. */
    private String eval(String select, Optional<Instant> now) throws SaxonApiException {
        standIns = new InubitStandIns(now);
        Processor processor = new Processor(false);
        standIns.register(processor);
        String stylesheet = "<xsl:stylesheet version=\"3.0\""
            + " xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" " + NAMESPACES
            + " exclude-result-prefixes=\"#all\"><xsl:output method=\"text\"/>"
            + "<xsl:template match=\"/\"><xsl:value-of select=\"" + select.replace("&", "&amp;")
                .replace("<", "&lt;").replace("\"", "&quot;")
            + "\" separator=\"|\"/></xsl:template></xsl:stylesheet>";
        XsltTransformer transformer;
        try {
            transformer = processor.newXsltCompiler().compile(new StreamSource(
                new StringReader(stylesheet))).load();
        } catch (SaxonApiException e) {
            return fail("the stand-ins do not serve %s: %s", select, e.getMessage());
        }
        StringWriter out = new StringWriter();
        transformer.setSource(new StreamSource(new StringReader("<x><y>1</y></x>")));
        transformer.setDestination(processor.newSerializer(out));
        transformer.transform();
        return out.toString();
    }

    private String eval(String select) throws SaxonApiException {
        return eval(select, Optional.empty());
    }

    @Test
    void identifiersAreFixed() throws SaxonApiException {
        assertThat(eval("Misc:guid(), string(UUID:randomUUID())"))
            .isEqualTo("00000000-0000-0000-0000-000000000000|"
                + "00000000-0000-0000-0000-000000000000");
        assertThat(standIns.used()).containsExactly("Misc.guid", "UUID.randomUUID");
    }

    @Test
    void timeIsTheFixedInstantOrTheRunsNow() throws SaxonApiException {
        assertThat(eval("Formatter:getDateTime('yyyy-MM-dd HH:mm'),"
            + " Formatter:getDateAsString('dd.MM.yyyy')"))
            .isEqualTo("2000-01-01 00:00|01.01.2000");
        assertThat(eval("Formatter:getDateTime('yyyy-MM-dd')",
            Optional.of(Instant.parse("2026-10-06T12:00:00Z")))).isEqualTo("2026-10-06");
    }

    @Test
    void datesAreConverted() throws SaxonApiException {
        assertThat(eval("Formatter:changeDateFormat('2026-10-06', 'yyyy-MM-dd|dd.MM.yyyy')"))
            .isEqualTo("06.10.2026");
        assertThat(eval("Formatter:convertDateString('06.10.2026', 'dd.MM.yyyy',"
            + " 'yyyy-MM-dd', 'UTC')")).isEqualTo("2026-10-06");
        assertThat(eval("Formatter:convertDateString('06.10.2026', 'dd.MM.yyyy', 'de', 'DE',"
            + " 'UTC', 'yyyyMMdd', 'en', 'US', 'UTC')")).isEqualTo("20261006");
        assertThat(eval("Formatter:changeDateFormat('not a date', 'yyyy-MM-dd|dd.MM.yyyy')"))
            .as("an unparseable value stays").isEqualTo("not a date");
        assertThat(eval("Formatter:parseSchemaDateToSQLTimestamp('2026-10-06T12:30:00')"))
            .isEqualTo("2026-10-06 12:30:00.0");
        assertThat(eval("Formatter:calculateDateDifference('a', 'b', 'c', 'd', 'e', 'f', 'g',"
            + " 'h', 'i', 'j', 'k')")).isEqualTo("0");
    }

    @Test
    void textAndNumbers() throws SaxonApiException {
        assertThat(eval("Formatter:trim('  a b  '), Formatter:isNumber('12.5'),"
            + " Formatter:isNumber('x'), Formatter:formatNumber('1234.5', '#,##0.00', 'en')"))
            .isEqualTo("a b|true|false|1,234.50");
        assertThat(eval("concat('a', Formatter:crlf(), 'b', Formatter:lf())"))
            .isEqualTo("a\r\nb\n");
        assertThat(eval("URLDecoder:decode('a%20b%2Fc')")).isEqualTo("a b/c");
    }

    @Test
    void encodingRoundTrips() throws SaxonApiException, IOException {
        assertThat(eval("Misc:encode('abc'), Misc:encode('abc', 'UTF-8'), Misc:decode('YWJj'),"
            + " ISFunctions:encode('abc')")).isEqualTo("YWJj|YWJj|abc|YWJj");
        byte[] compressed = Base64.getDecoder().decode(eval(
            "Misc:encodeWithCompression('abc')"));
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("abc");
        }
    }

    @Test
    void xmlStringsAndNodes() throws SaxonApiException {
        assertThat(eval("count(Misc:stringToBranch('<a><b/><b/></a>')/a/b),"
            + " name(ISFunctions:deserialize('<c/>')/*)")).isEqualTo("2|c");
        assertThat(eval("ISFunctions:serialize(/x/y)")).isEqualTo("<y>1</y>");
        assertThat(eval("Misc:stringToBranch('<!DOCTYPE a [<!ENTITY e SYSTEM \"file:///\">]>"
            + "<a>&e;</a>')")).as("a document type declaration is not parsed: empty")
            .isEmpty();
    }

    @Test
    void variablesLiveForOneRun() throws SaxonApiException {
        assertThat(eval("Misc:setVariableStorage('run'), Misc:setVariable('k', 'v', 'run'),"
            + " Misc:getVariable('k', 'run'), Misc:getVariable('other', 'run')"))
            .isEqualTo("v|");
        assertThat(standIns.used()).containsExactly("Misc.getVariable", "Misc.setVariable",
            "Misc.setVariableStorage");
    }

    @Test
    void sleepReturnsAtOnce() throws SaxonApiException {
        long start = System.nanoTime();

        assertThat(eval("Thread:sleep(600000), XThread:sleep(600000), 'done'"))
            .isEqualTo("done");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(
            30));
        assertThat(standIns.used()).containsExactly("Thread.sleep");
    }

    @Test
    void twoRunsGiveTheSameResult() throws SaxonApiException {
        String select = "Misc:guid(), Formatter:getDateTime('yyyyMMddHHmmssSSS')";

        assertThat(eval(select)).isEqualTo(eval(select));
    }
}
