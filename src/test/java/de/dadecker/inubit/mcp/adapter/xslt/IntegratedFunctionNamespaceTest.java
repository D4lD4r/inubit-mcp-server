package de.dadecker.inubit.mcp.adapter.xslt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.lib.ExtensionFunctionCall;
import net.sf.saxon.lib.ExtensionFunctionDefinition;
import net.sf.saxon.om.Sequence;
import net.sf.saxon.om.StructuredQName;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltTransformer;
import net.sf.saxon.value.SequenceType;
import net.sf.saxon.value.StringValue;
import org.junit.jupiter.api.Test;

/**
 * T028 — risk gate of research D-11: Saxon-HE 10.9 calls an integrated extension function that
 * is registered under the {@code java:} namespace URI of an INUBIT class, without attempting a
 * reflexive (Java) binding, which Saxon-HE does not have.
 */
class IntegratedFunctionNamespaceTest {

    private static final String STYLESHEET = """
        <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
            xmlns:Misc="java:com.inubit.ibis.xsltext.Misc"
            xmlns:UUID="java:java.util.UUID"
            xmlns:Thread="http://xml.apache.org/xalan/java/java.lang.Thread"
            exclude-result-prefixes="#all">
          <xsl:output method="text"/>
          <xsl:template match="/">
            <xsl:value-of select="Misc:guid(), UUID:randomUUID(), Thread:sleep(5)"
                separator="|"/>
          </xsl:template>
        </xsl:stylesheet>
        """;

    /** A function {@code local()} in {@code namespace} that returns {@code result}. */
    private static ExtensionFunctionDefinition function(String namespace, String local,
        int arity, String result) {
        return new ExtensionFunctionDefinition() {
            @Override
            public StructuredQName getFunctionQName() {
                return new StructuredQName("", namespace, local);
            }

            @Override
            public int getMinimumNumberOfArguments() {
                return arity;
            }

            @Override
            public int getMaximumNumberOfArguments() {
                return arity;
            }

            @Override
            public SequenceType[] getArgumentTypes() {
                return arity == 0 ? new SequenceType[0]
                    : new SequenceType[] {SequenceType.SINGLE_ATOMIC};
            }

            @Override
            public SequenceType getResultType(SequenceType[] suppliedArgumentTypes) {
                return SequenceType.SINGLE_STRING;
            }

            @Override
            public ExtensionFunctionCall makeCallExpression() {
                return new ExtensionFunctionCall() {
                    @Override
                    public Sequence call(net.sf.saxon.expr.XPathContext context,
                        Sequence[] arguments) {
                        return new StringValue(result);
                    }
                };
            }
        };
    }

    @Test
    void anIntegratedFunctionServesAJavaNamespaceWithoutReflexiveBinding() throws Exception {
        Processor processor = new Processor(false);
        for (ExtensionFunctionDefinition definition : List.of(
            function("java:com.inubit.ibis.xsltext.Misc", "guid", 0,
                "00000000-0000-0000-0000-000000000000"),
            function("java:java.util.UUID", "randomUUID", 0,
                "00000000-0000-0000-0000-000000000000"),
            function("http://xml.apache.org/xalan/java/java.lang.Thread", "sleep", 1, ""))) {
            processor.registerExtensionFunction(definition);
        }
        XsltCompiler compiler = processor.newXsltCompiler();
        assertThat(processor.getSaxonEdition()).isEqualTo("HE");

        assertThatCode(() -> compiler.compile(new StreamSource(new StringReader(STYLESHEET))))
            .as("Saxon-HE compiles the java: calls against the integrated functions")
            .doesNotThrowAnyException();
        XsltTransformer transformer = compiler.compile(new StreamSource(
            new StringReader(STYLESHEET))).load();
        StringWriter out = new StringWriter();
        transformer.setSource(new StreamSource(new StringReader("<a/>")));
        transformer.setDestination(processor.newSerializer(out));
        transformer.transform();

        assertThat(out.toString()).isEqualTo("00000000-0000-0000-0000-000000000000|"
            + "00000000-0000-0000-0000-000000000000|");
    }
}
