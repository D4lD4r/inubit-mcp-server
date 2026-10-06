package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T008 (research D-4): "treated as identical" — comments kept, whitespace-only text between
 * elements ignored, all other text exact.
 */
class XmlEqualityTest {

    private static boolean equal(String a, String b) {
        return XmlEquality.equal(a.getBytes(StandardCharsets.UTF_8),
            b.getBytes(StandardCharsets.UTF_8));
    }

    static Stream<Arguments> equalDocuments() {
        return Stream.of(
            arguments("<a x='1' y='2'/>", "<a y=\"2\" x=\"1\"></a>"),
            arguments("<a>\n  <b>x</b>\n</a>", "<a><b>x</b></a>"),
            arguments("<?xml version='1.0'?><a/>",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<a/>"),
            arguments("<a><![CDATA[1 < 2]]></a>", "<a>1 &lt; 2</a>"),
            arguments("<a>x&gt;y</a>", "<a>x>y</a>"),
            arguments("<p:a xmlns:p='urn:x'/>", "<q:a xmlns:q='urn:x'/>"),
            arguments("<a>\r\n<b/>\r\n</a>", "<a><b/></a>"),
            arguments("<a><!-- c --> <b/></a>", "<a>\n<!-- c -->\n<b/>\n</a>"),
            arguments("<a><b>x</b>\n</a>", "<a>\n\t<b>x</b></a>"));
    }

    @ParameterizedTest
    @MethodSource
    void equalDocuments(String a, String b) {
        assertThat(equal(a, b)).isTrue();
        assertThat(equal(b, a)).isTrue();
    }

    static Stream<Arguments> differentDocuments() {
        return Stream.of(
            arguments("<a>x</a>", "<a>y</a>"),
            arguments("<a> x</a>", "<a>x</a>"),
            arguments("<a> </a>", "<a/>"),
            arguments("<a>x\ny</a>", "<a>x\r\ny</a>".replace("\r\n", "&#13;\n")),
            arguments("<a x='1'/>", "<a x='2'/>"),
            arguments("<a x='1'/>", "<a x='1' y='1'/>"),
            arguments("<a><b/><c/></a>", "<a><c/><b/></a>"),
            arguments("<a><b/></a>", "<a><b/><b/></a>"),
            arguments("<a><!-- c --><b/></a>", "<a><b/></a>"),
            arguments("<a><!-- c --></a>", "<a><!-- d --></a>"),
            arguments("<a>p <b/> q</a>", "<a>p <b/>q</a>"),
            arguments("<a xmlns='urn:x'/>", "<a/>"),
            arguments("<a><?pi x?></a>", "<a/>"));
    }

    @ParameterizedTest
    @MethodSource
    void differentDocuments(String a, String b) {
        assertThat(equal(a, b)).isFalse();
        assertThat(equal(b, a)).isFalse();
    }

    static Stream<Arguments> malformedDocumentsAreRefused() {
        return Stream.of(arguments("<a>", "<a/>"), arguments("<a/>", ""),
            arguments("<!DOCTYPE a><a/>", "<a/>"));
    }

    @ParameterizedTest
    @MethodSource
    void malformedDocumentsAreRefused(String a, String b) {
        assertThatIllegalArgumentException().isThrownBy(() -> equal(a, b));
    }
}
