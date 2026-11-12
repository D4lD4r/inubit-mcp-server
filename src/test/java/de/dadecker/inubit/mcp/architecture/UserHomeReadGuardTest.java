package de.dadecker.inubit.mcp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 023 FR-007 (research D-5): only {@code Launcher.Context.system} reads the process user home. A
 * read is the literal {@code "user.home"}, a lookup of {@code HOME} or {@code USERPROFILE} through
 * {@code getenv(…)} or {@code getenv().get/getOrDefault(…)}, or one of these lookups through a
 * {@code static final String} constant of the same file holding one of the three names.
 *
 * <p>Scanned statically: comments are blanked by a single-pass scanner (newlines kept, so line
 * numbers stay exact); string, char and text block literals stay intact but a match starting
 * inside one is ignored, so messages and allowlists such as {@code List.of("HOME", …)} are not
 * reads.
 */
class UserHomeReadGuardTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path LAUNCHER = Path.of("de/dadecker/inubit/mcp/Launcher.java");
    private static final Pattern ALLOWED_PLACE =
        Pattern.compile("static\\s+Context\\s+system\\s*\\(");

    private static final Pattern LITERAL_READ = Pattern.compile(
        "\"user\\.home\"|\\bgetenv\\s*\\(\\s*(?:\\)\\s*\\.\\s*(?:get|getOrDefault)\\s*\\(\\s*)?"
            + "\"(?:HOME|USERPROFILE)\"\\s*[,)]");
    private static final Pattern CONSTANT = Pattern.compile(
        "\\bstatic\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"(?:user\\.home|HOME|USERPROFILE)\"\\s*;");

    @Test
    void onlyLauncherContextSystemReadsTheProcessHome() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            files = walk.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
        List<String> outside = new ArrayList<>();
        List<String> inside = new ArrayList<>();
        boolean launcherSeen = false;
        for (Path file : files) {
            String source = Files.readString(file);
            String relative = SOURCES.relativize(file).toString().replace(File.separatorChar, '/');
            int[] allowed = {0, -1};
            if (SOURCES.relativize(file).equals(LAUNCHER)) {
                launcherSeen = true;
                allowed = bodyLines(source);
            }
            for (int line : readLines(source)) {
                boolean inAllowedPlace = line >= allowed[0] && line <= allowed[1];
                (inAllowedPlace ? inside : outside).add(relative + ":" + line);
            }
        }
        // guards against a vacuous pass: the scan must see the allowed place
        assertThat(launcherSeen).as("Launcher.java scanned").isTrue();
        assertThat(outside).as("reads of the process user home outside Launcher.Context.system")
            .isEmpty();
        assertThat(inside).as("reads of the process user home inside Launcher.Context.system")
            .hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "// String home = System.getProperty(\"user.home\");",
        "/** Falls back to {@code System.getenv(\"HOME\")}. */ int x;",
        "throw new IllegalStateException(\"System.getenv(\\\"HOME\\\") and user.home are unset\");",
        "private static final String H = \"HOME\";\nvoid f() { log.warn(\"getenv(H) is empty\"); }",
        "char quote = '\"'; // System.getProperty(\"user.home\")",
        "String help = \"\"\"\n    System.getProperty(\"user.home\")\n"
            + "    System.getenv(\"HOME\")\n    \"\"\";",
        "private static final List<String> PASSED = List.of(\"HOME\", \"PATH\");",
        "Map<String, String> environment = new HashMap<>(System.getenv());",
    })
    void notARead(String snippet) {
        assertThat(readLines(snippet)).isEmpty();
    }

    @Test
    void aReadOnTheLineAfterAMultiLineJavadocIsReportedWithItsLine() {
        String snippet = """
            /**
             * Reads System.getProperty("user.home") and getenv("USERPROFILE").
             */
            Path home = Path.of(System.getenv("USERPROFILE"));
            """;
        assertThat(readLines(snippet)).containsExactly(4);
    }

    @Test
    void codeAfterLiteralsThatLookLikeCommentStartsIsStillScanned() {
        String snippet = """
            String url = "https://x"; String home = System.getProperty("user.home");
            String glob = "**/*.xml";
            String profile = System.getenv("USERPROFILE");
            """;
        assertThat(readLines(snippet)).containsExactly(1, 3);
    }

    @Test
    void aReadThroughAConstantIsReported() {
        String snippet = """
            private static final String H = "user.home";
            Path home = Path.of(System.getProperty(H));
            """;
        // line 1 holds the literal itself, line 2 the lookup through the constant
        assertThat(readLines(snippet)).containsExactly(1, 2);
    }

    @Test
    void aLookupInTheEnvironmentMapIsReported() {
        assertThat(readLines("String home = System.getenv().getOrDefault(\"HOME\", \"/\");"))
            .containsExactly(1);
    }

    /** Line numbers (1-based) of the reads of the process user home in {@code source}. */
    static SortedSet<Integer> readLines(String source) {
        Scanned scanned = scan(source);
        SortedSet<Integer> lines = new TreeSet<>();
        collect(scanned, LITERAL_READ, lines);
        Matcher constant = CONSTANT.matcher(scanned.code());
        while (constant.find()) {
            if (!scanned.literal().get(constant.start())) {
                collect(scanned, Pattern.compile(
                    "\\b(?:getProperty|getenv(?:\\s*\\(\\s*\\)\\s*\\.\\s*(?:get|getOrDefault))?)"
                        + "\\s*\\(\\s*" + Pattern.quote(constant.group(1)) + "\\s*[,)]"), lines);
            }
        }
        return lines;
    }

    private static void collect(Scanned scanned, Pattern pattern, SortedSet<Integer> lines) {
        Matcher matcher = pattern.matcher(scanned.code());
        while (matcher.find()) {
            if (!scanned.literal().get(matcher.start())) {
                lines.add(scanned.lineOf(matcher.start()));
            }
        }
    }

    /** First and last line of the body of {@code static Context system(…)}. */
    private static int[] bodyLines(String source) {
        Scanned scanned = scan(source);
        String code = scanned.code();
        Matcher signature = ALLOWED_PLACE.matcher(code);
        assertThat(signature.find()).as("static Context system( in Launcher.java").isTrue();
        int open = code.indexOf('{', signature.end());
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            if (scanned.literal().get(i)) {
                continue;
            }
            if (code.charAt(i) == '{') {
                depth++;
            } else if (code.charAt(i) == '}' && --depth == 0) {
                return new int[] {scanned.lineOf(open), scanned.lineOf(i)};
            }
        }
        throw new AssertionError("unbalanced body of static Context system(");
    }

    private enum State { CODE, STRING, CHAR, TEXT_BLOCK, LINE_COMMENT, BLOCK_COMMENT }

    /**
     * {@code code} is {@code source} with every comment character replaced by a space (newlines
     * kept, same length); {@code literal} marks the characters inside a literal, after its opening
     * delimiter.
     */
    private record Scanned(String code, BitSet literal) {

        int lineOf(int index) {
            return (int) code.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
        }
    }

    private static Scanned scan(String source) {
        StringBuilder code = new StringBuilder(source.length());
        BitSet literal = new BitSet();
        State state = State.CODE;
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            int length = 1;
            boolean blank = false;
            switch (state) {
                case CODE -> {
                    if (source.startsWith("//", i)) {
                        state = State.LINE_COMMENT;
                        length = 2;
                        blank = true;
                    } else if (source.startsWith("/*", i)) {
                        state = State.BLOCK_COMMENT;
                        length = 2;
                        blank = true;
                    } else if (source.startsWith("\"\"\"", i)) {
                        state = State.TEXT_BLOCK;
                        length = 3;
                        literal.set(i + 1, i + 3);
                    } else if (c == '"') {
                        state = State.STRING;
                    } else if (c == '\'') {
                        state = State.CHAR;
                    }
                }
                case STRING, CHAR, TEXT_BLOCK -> {
                    if (c == '\\') {
                        length = 2;
                    } else if (state == State.TEXT_BLOCK && source.startsWith("\"\"\"", i)) {
                        length = 3;
                        state = State.CODE;
                    } else if (state == State.STRING && c == '"'
                        || state == State.CHAR && c == '\'') {
                        state = State.CODE;
                    }
                    literal.set(i, Math.min(i + length, source.length()));
                }
                case LINE_COMMENT -> {
                    blank = true;
                    if (c == '\n') {
                        state = State.CODE;
                    }
                }
                case BLOCK_COMMENT -> {
                    blank = true;
                    if (source.startsWith("*/", i)) {
                        length = 2;
                        state = State.CODE;
                    }
                }
            }
            int end = Math.min(i + length, source.length());
            for (int k = i; k < end; k++) {
                char kept = source.charAt(k);
                code.append(blank && kept != '\n' ? ' ' : kept);
            }
            i = end;
        }
        return new Scanned(code.toString(), literal);
    }
}
