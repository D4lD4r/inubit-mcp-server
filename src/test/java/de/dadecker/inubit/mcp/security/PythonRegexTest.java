package de.dadecker.inubit.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The map rules are Python regular expressions; the guard compiles them for Java with Python's
 * semantics (Unicode classes, {@code \Z}, {@code {,n}}, named groups, literal brackets in sets)
 * and rejects what it cannot translate faithfully. Fictitious values only.
 */
class PythonRegexTest {

    private static boolean finds(String pythonRegex, String text) {
        return PythonRegex.compile(pythonRegex).matcher(text).find();
    }

    @Test
    void theRuleShapesWrittenBySynthesizeNamesBehaveLikeInPython() {
        String multiWord = "(?<![\\w-])Globex\\ Billing\\-Import(?![\\w-])";
        assertThat(finds(multiWord, "<m name=\"Globex Billing-Import\"/>")).isTrue();
        assertThat(finds(multiWord, "x-Globex Billing-Import")).isFalse();
        assertThat(finds(multiWord, "Globex Billing-Importer")).isFalse();
        assertThat(finds(multiWord, "äGlobex Billing-Import")).as("\\w is Unicode").isFalse();

        String singleWord = "(?<=[\\\">])Acme(?=[\\\"<])";
        assertThat(finds(singleWord, "<g>Acme</g>")).isTrue();
        assertThat(finds(singleWord, "group=\"Acme\"")).isTrue();
        assertThat(finds(singleWord, "the Acme call")).isFalse();

        String service = "(?<=[\\\">/])Orders(?=[\\\"</?\\r\\n]|\\Z)";
        assertThat(finds(service, "GET /ibis/ws/Orders")).as("\\Z at the end of a line").isTrue();
        assertThat(finds(service, "/ibis/ws/Orders?wsdl")).isTrue();
        assertThat(finds(service, "/ibis/ws/OrdersX")).isFalse();
    }

    @Test
    void inlineFlagsWordBoundariesAndLookaroundsAreKept() {
        assertThat(finds("(?i)foocorp", "FooCorp")).isTrue();
        assertThat(finds("(?i)straße", "STRASSE")).isFalse();
        assertThat(finds("(?i)ä", "Ä")).as("case folding is Unicode").isTrue();
        assertThat(finds("\\bFC\\b", "group FC.")).isTrue();
        assertThat(finds("\\bFC\\b", "FCX")).isFalse();
        assertThat(finds("(?<!x)FC(?=-)", "FC-Utils")).isTrue();
        assertThat(finds("(?i:acme)-X", "ACME-X")).isTrue();
        assertThat(finds("(?s)a.b", "a\nb")).isTrue();
    }

    @Test
    void pythonOnlySyntaxIsTranslated() {
        assertThat(finds("^a{,2}$", "aa")).as("{,n}").isTrue();
        assertThat(finds("^a{,2}$", "aaa")).isFalse();
        assertThat(finds("x{foo}", "x{foo}")).as("a brace that is no quantifier is literal")
            .isTrue();
        assertThat(finds("(?P<word>ab)-(?P=word)", "ab-ab")).isTrue();
        assertThat(finds("(?P<word>ab)-(?P=word)", "ab-cd")).isFalse();
        assertThat(finds("[[]x", "[x")).as("[ in a set is literal").isTrue();
        assertThat(finds("[a&&b]", "&")).as("&& in a set is literal").isTrue();
        assertThat(finds("[]a]", "]")).isTrue();
        assertThat(finds("[\\b]", "\b")).as("\\b in a set is a backspace").isTrue();
        assertThat(finds("a(?#a comment)b", "ab")).isTrue();
        assertThat(finds("\\U0001F600", "😀")).isTrue();
        assertThat(finds("a\\0b", "a\0b")).isTrue();
        assertThat(finds("a\\101b", "aAb")).as("octal escape").isTrue();
        assertThat(finds("(?P<se_cret>x)(?P=se_cret)", "xx")).as("any Python group name")
            .isTrue();
        assertThat(finds("a\\vb", "a\u000Bb")).isTrue();
        assertThat(finds("a\\vb", "a\nb")).isFalse();
        assertThat(finds("^ab$", "ab\r")).as("only \\n ends a line, as in Python").isFalse();
        assertThat(finds("\\Aab\\Z", "ab")).isTrue();
        assertThat(finds("(?u)\\w", "é")).isTrue();
        assertThat(finds("(?a)\\w", "é")).as("(?a) restricts classes to ASCII").isFalse();
        assertThat(finds("(?a)\\w", "e")).isTrue();
        assertThat(finds("a\\-b\\ c\\&d\\~e\\#f", "a-b c&d~e#f")).as("re.escape output").isTrue();
    }

    @Test
    void untranslatableConstructsAreRejectedWithoutTheRuleText() {
        for (String regex : new String[] {
            "(secret)?(?(1)a|b)",   // conditional group
            "(?x) secret # verbose", // verbose mode differs
            "(?L)secret",           // locale flag
            "(?a:secret)",          // scoped ASCII flag
            "secret\\p{L}",         // Java-only escape, invalid in Python
            "secret\\q",            // unknown escape
            "secret(?P=undefined)", // reference to an unknown group
            "secret\\777",          // octal escape beyond 0o377
            "secret(",              // invalid
        }) {
            assertThatThrownBy(() -> PythonRegex.compile(regex)).as(regex)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret")
                .satisfies(e -> assertThat(e.getCause()).isNull());
        }
    }

    @Test
    void pythonCharacterClassesAreKeptInsteadOfJavasUnicodeClasses() {
        assertThat(finds("a\\sb", "a\u001cb")).as("\\s includes \\x1c-\\x1f").isTrue();
        assertThat(finds("a[\\s]b", "a\u001fb")).isTrue();
        assertThat(finds("a\\Sb", "a\u001db")).isFalse();
        assertThat(finds("a[^\\s]b", "a\u001eb")).isFalse();
        assertThat(finds("a[x\\S]b", "a\u001db")).isFalse();
        assertThat(finds("a[x\\S]b", "ayb")).isTrue();
        assertThat(finds("^\\w$", "\u0301")).as("a combining mark is no \\w").isFalse();
        assertThat(finds("^\\w$", "\u203f")).as("connector punctuation is no \\w").isFalse();
        assertThat(finds("^\\w$", "\u200d")).as("a join control is no \\w").isFalse();
        assertThat(finds("^\\w$", "\u00b2")).as("other numbers are \\w").isTrue();
        assertThat(finds("^\\W$", "\u0301")).isTrue();
        assertThat(finds("[^\\w]x", "\u0301x")).isTrue();
        assertThat(finds("\\bacme\\b", "\u0301acme\u0301")).as("\\b follows \\w").isTrue();
        assertThat(finds("\\bacme", "\u00b2acme")).isFalse();
        assertThat(finds("acme\\B", "acme\u0301")).isFalse();
        assertThat(finds("(?a)\\w", "é")).isFalse();
        assertThat(finds("(?a)\\bacme", "éacme")).as("(?a) keeps ASCII \\b").isTrue();
    }

    /** The vectors shared with tools/check-identifiers.py --self-test; expected = Python's re. */
    @Test
    void theSharedParityVectorsMatchLikeInPython() throws IOException {
        List<String> wrong = new ArrayList<>();
        int count = 0;
        for (JsonNode vector : parityVectors()) {
            String regex = vector.get(0).asString();
            String text = vector.get(1).asString();
            boolean expected = vector.get(2).asBoolean();
            count++;
            Pattern pattern = PythonRegex.compile(regex);
            boolean found = pattern.matcher(text).find();
            if (found != expected) {
                wrong.add("vector " + count + " expected " + expected);
            }
            LiteralIndex.requiredLiteral(pattern.pattern()).ifPresent(literal -> {
                if (found && !new LiteralIndex(List.of(literal)).find(text).contains(0)) {
                    wrong.add("prefilter misses vector " + vector);
                }
            });
        }
        assertThat(count).isGreaterThan(50);
        assertThat(wrong).isEmpty();
    }

    static List<JsonNode> parityVectors() throws IOException {
        List<JsonNode> vectors = new ArrayList<>();
        JsonMapper mapper = JsonMapper.builder().build();
        try (InputStream in = PythonRegexTest.class.getResourceAsStream(
                "/identifier-check/parity-vectors.jsonl")) {
            assertThat(in).as("parity vectors").isNotNull();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    vectors.add(mapper.readTree(line));
                }
            }
        }
        return vectors;
    }

    @Test
    void theTranslationIsAValidJavaPattern() {
        Pattern pattern = PythonRegex.compile("(?i)\\bacme\\b");

        assertThat(pattern.flags() & Pattern.UNICODE_CHARACTER_CLASS).isNotZero();
        assertThat(pattern.flags() & Pattern.UNIX_LINES).isNotZero();
    }
}
