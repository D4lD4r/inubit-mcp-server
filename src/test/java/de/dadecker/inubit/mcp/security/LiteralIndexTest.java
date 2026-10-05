package de.dadecker.inubit.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The prefilter that keeps ~2,000 rules fast: every rule contributes the longest literal that each
 * of its matches must contain, and one Aho-Corasick pass per text selects the rules worth running.
 * A rule without such a literal always runs. Fictitious values only.
 */
class LiteralIndexTest {

    private static Optional<String> literal(String javaRegex) {
        return LiteralIndex.requiredLiteral(javaRegex).map(LiteralIndex.Literal::text);
    }

    @Test
    void theLongestRequiredLiteralIsExtracted() {
        assertThat(literal("(?<![\\w-])Globex\\ Billing\\-Import(?![\\w-])"))
            .contains("Globex Billing-Import");
        assertThat(literal("(?<=[\\\">/])Orders(?=[\\\"</?\\r\\n]|\\z)")).contains("Orders");
        assertThat(literal("bar-\\d+\\.internal")).contains(".internal");
        assertThat(literal("\\bacme\\b")).contains("acme");
        assertThat(literal("colou?r")).contains("colo");
        assertThat(literal("ab*cd")).contains("cd");
        assertThat(literal("(globex)corp")).contains("corp");
        assertThat(literal("x{2}yz")).contains("yz");
        assertThat(literal("[a-z]+-foocorp")).contains("-foocorp");
        assertThat(literal("acme.example")).contains("example");
        assertThat(literal("ä-globex")).contains("ä-globex");
    }

    @Test
    void rulesWithoutASafeLiteralGetNone() {
        assertThat(literal("acme|globex")).isEmpty();
        assertThat(literal("[a-z]+\\d")).isEmpty();
        assertThat(literal("\\Qacme\\E")).isEmpty();
        assertThat(literal("(?x)ac me")).isEmpty();
        assertThat(literal("(?<=x)")).isEmpty();
    }

    @Test
    void caseInsensitiveRulesGetAFoldedLiteral() {
        Optional<LiteralIndex.Literal> ci = LiteralIndex.requiredLiteral("(?i)FooCorp");
        assertThat(ci).get().extracting(LiteralIndex.Literal::text,
                LiteralIndex.Literal::folded)
            .containsExactly("foocorp", true);
        assertThat(LiteralIndex.requiredLiteral("x(?i:y)FooCorp")).get()
            .extracting(LiteralIndex.Literal::text).isEqualTo("foocorp");
        assertThat(LiteralIndex.requiredLiteral("FooCorp")).get()
            .extracting(LiteralIndex.Literal::folded).isEqualTo(false);
        assertThat(LiteralIndex.fold("ſTRASSE K")).isEqualTo(LiteralIndex.fold("strasse k"));
    }

    @Test
    void theLiteralIsContainedInEveryMatch() {
        List<String> regexes = List.of("(?i)foo\\.corp", "bar-\\d+\\.internal", "colou?r",
            "(?<![\\w-])Globex\\ Billing(?![\\w-])", "ab*cd", "(?i)ſtrasse");
        List<String> texts = List.of("FOO.CORP", "x bar-17.internal", "color", "colour",
            "a Globex Billing b", "acd", "abbbcd", "STRASSE", "ſtrasse");
        for (String regex : regexes) {
            Pattern pattern = Pattern.compile(regex,
                Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);
            LiteralIndex.Literal literal = LiteralIndex.requiredLiteral(regex).orElseThrow();
            for (String text : texts) {
                if (pattern.matcher(text).find()) {
                    String haystack = literal.folded() ? LiteralIndex.fold(text) : text;
                    assertThat(haystack).as(regex + " on " + text).contains(literal.text());
                }
            }
        }
    }

    /** Deterministic fuzz over translated Python rules: the prefilter never hides a match. */
    @Test
    void thePrefilterNeverHidesAMatch() {
        String[] fragments = {"a", "b", "A", "ı", "İ", "i", "I", "ß", "ẞ", "{", "}", "{2}", "{,2}",
            "{1,}", "x{y}", "(?#c(|)", "(?#z)", "*", "+", "?", "|", "(a)", "(?:b|c)", "[ab]", "[{]",
            "\\{", "\\.", ".", "-", "\\w", "\\b", "\\s", "(?i:a)", "(?-i:b)", "ab", "[^\\w]"};
        String alphabet = "aAbBcxyıIİißẞ{}.,|()#- 2\u0301\u001c";
        Random random = new Random(4711);
        List<String> misses = new ArrayList<>();
        int checked = 0;
        for (int r = 0; r < 3000; r++) {
            StringBuilder regex = new StringBuilder(random.nextDouble() < 0.4 ? "(?i)" : "");
            for (int f = random.nextInt(6) + 1; f > 0; f--) {
                regex.append(fragments[random.nextInt(fragments.length)]);
            }
            Pattern pattern;
            try {
                pattern = PythonRegex.compile(regex.toString());
            } catch (IllegalArgumentException invalid) {
                continue;
            }
            Optional<LiteralIndex.Literal> literal =
                LiteralIndex.requiredLiteral(pattern.pattern());
            if (literal.isEmpty()) {
                continue;
            }
            LiteralIndex index = new LiteralIndex(List.of(literal.get()));
            for (int t = 0; t < 8; t++) {
                StringBuilder text = new StringBuilder();
                for (int c = random.nextInt(10); c > 0; c--) {
                    text.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
                if (random.nextBoolean()) {
                    text.insert(Math.min(3, text.length()), literal.get().text());
                }
                checked++;
                if (pattern.matcher(text).find() && index.find(text.toString()).isEmpty()) {
                    misses.add(regex + " / " + text);
                }
            }
        }
        assertThat(checked).isGreaterThan(1000);
        assertThat(misses).isEmpty();
    }

    @Test
    void oneAhoCorasickPassFindsEveryLiteralIncludingOverlaps() {
        LiteralIndex index = new LiteralIndex(List.of(
            new LiteralIndex.Literal("he", false), new LiteralIndex.Literal("she", false),
            new LiteralIndex.Literal("hers", false), new LiteralIndex.Literal("acme", true),
            new LiteralIndex.Literal("xyz", false)));

        assertThat(index.find("ushers")).containsExactly(0, 1, 2);
        assertThat(index.find("ACME and he")).containsExactly(0, 3);
        assertThat(index.find("nothing")).isEmpty();
    }
}
