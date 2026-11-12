package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Feature 021 (T015, FR-009 steps 2–5, research D-9, D-10, D-12): {@code LogExcerpt.build}
 * classifies the window, masks it as a whole, selects the last lines (or the last matching lines),
 * numbers them absolutely and bounds their text by {@code maxChars}.
 */
class LogExcerptTest {

    private static final Path EXCERPT = Path.of("src/test/resources/fixtures/server-log/excerpt");
    private static final String SECRET = TestSecrets.CREDENTIAL_PAYLOAD_VALUE;
    private static final String PASSWORD = TestSecrets.CONFIGURED_PASSWORD;
    private static final Function<String, List<SecretSpan>> NONE = text -> List.of();
    private static final int TAIL = ServerLogBounds.DEFAULT_TAIL_LINES;
    private static final int MAX = ServerLogBounds.DEFAULT_MAX_CHARS;
    private static final String WINDOW_HINT =
        "Only the last 50 MiB were scanned; line numbers count the whole file";

    // selection and numbering

    /** US1-1: the last 200 lines of 1,000, numbered 801–1000. */
    @Test
    void withoutContainsTheLastTailLinesAreSelected() {
        LogExcerpt excerpt = LogExcerpt.build(whole(numbered(1_000)), 200, Optional.empty(), MAX,
            NONE);

        assertThat(excerpt.kind()).isEqualTo(ContentKind.TEXT);
        assertThat(excerpt.charset()).contains(StandardCharsets.UTF_8);
        assertThat(excerpt.selection()).isEqualTo(LogExcerpt.Selection.TAIL);
        assertThat(excerpt.matchingLines()).isEmpty();
        assertThat(excerpt.window()).isEqualTo(new LogExcerpt.Window(1, 1_000,
            numbered(1_000).getBytes(StandardCharsets.UTF_8).length, true));
        assertThat(excerpt.lines()).hasSize(200);
        assertThat(excerpt.lines().get(0)).isEqualTo(new ExcerptLine(801, "line 801", false));
        assertThat(excerpt.lines().get(199)).isEqualTo(new ExcerptLine(1_000, "line 1000", false));
        assertThat(excerpt.truncated()).isEmpty();
        assertThat(excerpt.hints()).isEmpty();
    }

    @Test
    void fewerLinesThanTailLinesAreAllSelected() {
        LogExcerpt excerpt = LogExcerpt.build(whole(numbered(3)), TAIL, Optional.empty(), MAX, NONE);

        assertThat(excerpt.lines()).extracting(ExcerptLine::number).containsExactly(1L, 2L, 3L);
    }

    /** US1-2: case-insensitive plain text, only the last matches, absolute numbers, the count. */
    @Test
    void withContainsTheLastMatchingLinesAreSelected() {
        StringBuilder log = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            log.append(i % 10 == 0
                ? "ERROR java.lang." + (i % 20 == 0 ? "OutOfMemoryError" : "outofmemoryerror")
                    + " in line " + i
                : "INFO  line " + i).append('\n');
        }
        LogWindow window = new LogWindow(utf8(log.toString()), 5_000, false, 9_999_999);

        LogExcerpt excerpt = LogExcerpt.build(window, 3, Optional.of("OutOfMemoryError"), MAX, NONE);

        assertThat(excerpt.selection()).isEqualTo(LogExcerpt.Selection.CONTAINS);
        assertThat(excerpt.matchingLines()).isEqualTo(OptionalLong.of(10));
        assertThat(excerpt.lines()).extracting(ExcerptLine::number)
            .containsExactly(5_080L, 5_090L, 5_100L);
        assertThat(excerpt.lines()).extracting(ExcerptLine::text).containsExactly(
            "ERROR java.lang.OutOfMemoryError in line 80",
            "ERROR java.lang.outofmemoryerror in line 90",
            "ERROR java.lang.OutOfMemoryError in line 100");
    }

    @Test
    void containsIsPlainTextNotAPattern() {
        String log = "a.c\nabc\n[x]\nx\n";

        LogExcerpt dot = LogExcerpt.build(whole(log), TAIL, Optional.of("a.c"), MAX, NONE);
        LogExcerpt brackets = LogExcerpt.build(whole(log), TAIL, Optional.of("[X]"), MAX, NONE);

        assertThat(dot.lines()).extracting(ExcerptLine::text).containsExactly("a.c");
        assertThat(brackets.lines()).extracting(ExcerptLine::text).containsExactly("[x]");
    }

    @Test
    void containsWithoutMatchGivesNoLinesAndAHint() {
        LogExcerpt excerpt = LogExcerpt.build(whole(numbered(10)), TAIL,
            Optional.of("OutOfMemoryError"), MAX, NONE);

        assertThat(excerpt.kind()).isEqualTo(ContentKind.TEXT);
        assertThat(excerpt.matchingLines()).isEqualTo(OptionalLong.of(0));
        assertThat(excerpt.lines()).isEmpty();
        assertThat(excerpt.truncated()).isEmpty();
        assertThat(excerpt.hints())
            .containsExactly("No line of the scanned window contains the search text");
    }

    /** A text that occurs only inside a masked value never matches (FR-009 step 3). */
    @Test
    void aTextOnlyInsideAMaskedValueNeverMatches() {
        String log = "<password>" + SECRET + "</password>\nlogin of acme with " + PASSWORD
            + " refused\nAuthorization: Basic " + SECRET + "\n";

        LogExcerpt inElement = LogExcerpt.build(whole(log), TAIL,
            Optional.of("credential-payload"), MAX, known(PASSWORD));
        LogExcerpt knownValue = LogExcerpt.build(whole(log), TAIL,
            Optional.of("CONFIGURED-PASSWORD"), MAX, known(PASSWORD));

        assertThat(inElement.matchingLines()).isEqualTo(OptionalLong.of(0));
        assertThat(inElement.lines()).isEmpty();
        assertThat(knownValue.matchingLines()).isEqualTo(OptionalLong.of(0));
        assertThat(knownValue.lines()).isEmpty();
    }

    /** US1-7: line numbers count the lines before the window. */
    @Test
    void linesBeforeTheWindowAreCounted() {
        LogWindow window = new LogWindow(utf8(numbered(5)), 412_876, false, 60L * 1024 * 1024);

        LogExcerpt excerpt = LogExcerpt.build(window, TAIL, Optional.empty(), MAX, NONE);

        assertThat(excerpt.window().startLine()).isEqualTo(412_877);
        assertThat(excerpt.window().wholeFile()).isFalse();
        assertThat(excerpt.lines()).extracting(ExcerptLine::number)
            .containsExactly(412_877L, 412_878L, 412_879L, 412_880L, 412_881L);
        assertThat(excerpt.lines().get(0).text()).isEqualTo("line 1");
        assertThat(excerpt.hints()).containsExactly(WINDOW_HINT);
    }

    // maxChars

    /** US1-4: 200 lines with 60,000 chars and maxChars 20,000 keep only the newest that fit. */
    @Test
    void overMaxCharsTheOldestWholeLinesAreDropped() throws IOException {
        LogExcerpt excerpt = LogExcerpt.build(fixture("long_lines.log"), 200, Optional.empty(),
            20_000, NONE);

        assertThat(excerpt.lines()).hasSize(66);
        assertThat(excerpt.lines().get(0).number()).isEqualTo(135);
        assertThat(excerpt.lines().get(65).number()).isEqualTo(200);
        assertThat(excerpt.lines()).allSatisfy(line -> {
            assertThat(line.text()).hasSize(300);
            assertThat(line.cut()).isFalse();
        });
        assertThat(excerpt.truncated()).contains(new Truncation(134, 0));
    }

    @Test
    void linesThatFitExactlyAreNotTruncated() {
        LogExcerpt excerpt = LogExcerpt.build(whole("abc\ndef\n"), TAIL, Optional.empty(), 6, NONE);

        assertThat(excerpt.lines()).extracting(ExcerptLine::text).containsExactly("abc", "def");
        assertThat(excerpt.truncated()).isEmpty();
    }

    @Test
    void aNewestLineLongerThanMaxCharsIsCutAtItsEnd() throws IOException {
        LogExcerpt excerpt = LogExcerpt.build(fixture("long_lines.log"), 200, Optional.empty(),
            299, NONE);
        String line200 = Files.readString(EXCERPT.resolve("long_lines.log")).split("\n")[199];

        assertThat(excerpt.lines()).containsExactly(
            new ExcerptLine(200, line200.substring(0, 299), true));
        assertThat(excerpt.truncated()).contains(new Truncation(199, 1));
    }

    @Test
    void aCutNeverSplitsASurrogatePair() {
        String smile = "😀";
        String log = "older\n" + "a".repeat(9) + smile + "tail\n";

        LogExcerpt excerpt = LogExcerpt.build(whole(log), TAIL, Optional.empty(), 10, NONE);

        assertThat(excerpt.lines()).containsExactly(new ExcerptLine(2, "a".repeat(9), true));
        assertThat(excerpt.truncated()).contains(new Truncation(1, 1));
    }

    @Test
    void aCutAfterACompleteSurrogatePairKeepsIt() {
        String smile = "😀";
        String log = "a".repeat(8) + smile + "tail\n";

        LogExcerpt excerpt = LogExcerpt.build(whole(log), TAIL, Optional.empty(), 10, NONE);

        assertThat(excerpt.lines()).containsExactly(new ExcerptLine(1, "a".repeat(8) + smile, true));
        assertThat(excerpt.truncated()).contains(new Truncation(0, 1));
    }

    @Test
    void maxCharsCountsTheMaskedText() {
        String log = "<pwd>" + SECRET + "</pwd>\n";
        int masked = "<pwd>${secret:data.pwd}</pwd>".length();

        LogExcerpt excerpt = LogExcerpt.build(whole(log), TAIL, Optional.empty(), masked, NONE);

        assertThat(excerpt.lines()).containsExactly(
            new ExcerptLine(1, "<pwd>${secret:data.pwd}</pwd>", false));
        assertThat(excerpt.truncated()).isEmpty();
    }

    // classification (D-10)

    @Test
    void aBinaryWindowGivesNoLinesAndNamesTheSignature() throws IOException {
        LogExcerpt excerpt = LogExcerpt.build(fixture("binary_gzip.log"), TAIL, Optional.empty(),
            MAX, NONE);

        assertThat(excerpt.kind()).isEqualTo(ContentKind.BINARY);
        assertThat(excerpt.detected()).contains(BinarySignature.GZIP);
        assertThat(excerpt.charset()).isEmpty();
        assertThat(excerpt.lines()).isEmpty();
        assertThat(excerpt.matchingLines()).isEmpty();
        assertThat(excerpt.truncated()).isEmpty();
        assertThat(excerpt.hints()).containsExactly("Binary content is not returned");
    }

    @Test
    void aBinaryWindowWithoutSignatureHasNoDetected() {
        byte[] bytes = {'a', 0x00, 'b', '\n'};

        LogExcerpt excerpt = LogExcerpt.build(new LogWindow(bytes, 0, true, bytes.length), TAIL,
            Optional.of("a"), MAX, NONE);

        assertThat(excerpt.kind()).isEqualTo(ContentKind.BINARY);
        assertThat(excerpt.detected()).isEmpty();
        assertThat(excerpt.selection()).isEqualTo(LogExcerpt.Selection.CONTAINS);
        assertThat(excerpt.lines()).isEmpty();
        assertThat(excerpt.hints()).containsExactly("Binary content is not returned");
    }

    @Test
    void aWindowThatIsNotValidUtf8IsDecodedAsLatin1() throws IOException {
        LogExcerpt excerpt = LogExcerpt.build(fixture("latin1.log"), TAIL, Optional.empty(), MAX,
            NONE);

        assertThat(excerpt.kind()).isEqualTo(ContentKind.TEXT);
        assertThat(excerpt.charset()).contains(StandardCharsets.ISO_8859_1);
        assertThat(excerpt.lines()).extracting(ExcerptLine::text).containsExactly(
            "2026-11-02 12:00:00,001 INFO  [acme.import.Customer] node1 customer Müller imported",
            "2026-11-02 12:00:00,002 WARN  [acme.import.Customer] node1 street Hauptstraße 5"
                + " shortened",
            "2026-11-02 12:00:00,003 INFO  [acme.import.Customer] node1 Größe 12 µm accepted");
    }

    /**
     * FR-010: one byte that is not UTF-8 makes the whole window ISO-8859-1; a non-ASCII password
     * the log wrote as UTF-8 is still masked by the profile's credentials (the scrubber's forms).
     */
    @Test
    void aNonAsciiPasswordInAWindowDecodedAsLatin1IsMasked() {
        SecretScrubber scrubber = new SecretScrubber();
        scrubber.register("Pässwort1");
        byte[] password = utf8("login with Pässwort1 done\n");
        byte[] bytes = Arrays.copyOf(password, password.length + 7);
        System.arraycopy(new byte[] {'s', 't', 'r', 'a', 'y', (byte) 0xFF, '\n'}, 0, bytes,
            password.length, 7);

        LogExcerpt excerpt = LogExcerpt.build(new LogWindow(bytes, 0, true, bytes.length), TAIL,
            Optional.empty(), MAX, scrubber::occurrences);

        assertThat(excerpt.charset()).contains(StandardCharsets.ISO_8859_1);
        assertThat(excerpt.lines()).extracting(ExcerptLine::text)
            .containsExactly("login with *** done", "strayÿ");
    }

    @Test
    void aUtf8ByteOrderMarkIsDropped() {
        byte[] bytes = utf8("﻿first\nsecond\n");

        LogExcerpt excerpt = LogExcerpt.build(new LogWindow(bytes, 0, true, bytes.length), TAIL,
            Optional.empty(), MAX, NONE);

        assertThat(excerpt.charset()).contains(StandardCharsets.UTF_8);
        assertThat(excerpt.lines()).extracting(ExcerptLine::text).containsExactly("first", "second");
    }

    @Test
    void anEmptyLogIsKindEmpty() {
        LogExcerpt excerpt = LogExcerpt.build(new LogWindow(new byte[0], 0, true, 0), TAIL,
            Optional.empty(), MAX, NONE);

        assertThat(excerpt.kind()).isEqualTo(ContentKind.EMPTY);
        assertThat(excerpt.detected()).isEmpty();
        assertThat(excerpt.charset()).isEmpty();
        assertThat(excerpt.window()).isEqualTo(new LogExcerpt.Window(1, 0, 0, true));
        assertThat(excerpt.lines()).isEmpty();
        assertThat(excerpt.truncated()).isEmpty();
        assertThat(excerpt.hints()).isEmpty();
    }

    @Test
    void aNonEmptyFileWithoutAWholeLineInTheWindowIsTextWithoutLines() {
        LogWindow window = new LogWindow(new byte[0], 7, false, 60L * 1024 * 1024);

        LogExcerpt excerpt = LogExcerpt.build(window, TAIL, Optional.of("x"), MAX, NONE);

        assertThat(excerpt.kind()).isEqualTo(ContentKind.TEXT);
        assertThat(excerpt.window()).isEqualTo(new LogExcerpt.Window(8, 0, 0, false));
        assertThat(excerpt.lines()).isEmpty();
        assertThat(excerpt.matchingLines()).isEqualTo(OptionalLong.of(0));
        assertThat(excerpt.hints())
            .containsExactly(WINDOW_HINT, "No whole line in the last 50 MiB of the log");
    }

    // line terminators (D-9)

    @Test
    void aLastLineWithoutTerminatorIsALine() throws IOException {
        LogExcerpt excerpt = LogExcerpt.build(fixture("no_newline.log"), TAIL, Optional.empty(),
            MAX, NONE);

        assertThat(excerpt.window().lines()).isEqualTo(3);
        assertThat(excerpt.lines()).extracting(ExcerptLine::number).containsExactly(1L, 2L, 3L);
        assertThat(excerpt.lines().get(2)).isEqualTo(new ExcerptLine(3,
            "2026-11-02 13:00:00,003 INFO  [acme.flow.Runner] node1 step 2 running", false));
    }

    @Test
    void crlfBelongsToTheTerminatorAndALoneCarriageReturnToTheLine() throws IOException {
        LogExcerpt crlf = LogExcerpt.build(fixture("crlf.log"), TAIL, Optional.empty(), MAX, NONE);
        LogExcerpt lone = LogExcerpt.build(whole("a\rb\r\n\n\rc\r"), TAIL, Optional.empty(), MAX,
            NONE);

        assertThat(crlf.lines()).extracting(ExcerptLine::text).containsExactly(
            "2026-11-02 10:00:00,001 INFO  [acme.http.Server] node1 request received",
            "Authorization: ${secret:authorization}",
            "<password>${secret:data.password}",
            "",
            "</password> done",
            "2026-11-02 10:00:00,005 INFO  [acme.http.Server] node1 request finished");
        assertThat(lone.lines()).extracting(ExcerptLine::text)
            .containsExactly("a\rb", "", "\rc\r");
    }

    @Test
    void theEmptySegmentAfterAFinalLineFeedIsNoLine() {
        LogExcerpt excerpt = LogExcerpt.build(whole("a\n\n"), TAIL, Optional.empty(), MAX, NONE);

        assertThat(excerpt.window().lines()).isEqualTo(2);
        assertThat(excerpt.lines()).extracting(ExcerptLine::text).containsExactly("a", "");
    }

    // masking before selection (FR-009 step 2, FR-010)

    /** US1-8: a value over lines 10–12 is masked on the whole window, then line 11 on is selected. */
    @Test
    void theWholeWindowIsMaskedBeforeSelection() {
        String log = numbered(9) + "<password>" + SECRET + "\n" + SECRET + "\n" + SECRET
            + "</password> next\nline 13\n";

        LogExcerpt excerpt = LogExcerpt.build(whole(log), 3, Optional.empty(), MAX, NONE);

        assertThat(excerpt.lines()).containsExactly(
            new ExcerptLine(11, "", false),
            new ExcerptLine(12, "</password> next", false),
            new ExcerptLine(13, "line 13", false));
    }

    @Test
    void theKnownSpansAreAskedForTheWholeDecodedWindow() {
        String log = "first\nlogin with " + PASSWORD + "\n";
        List<String> asked = new ArrayList<>();

        LogExcerpt.build(whole(log), 1, Optional.empty(), MAX, text -> {
            asked.add(text);
            return known(PASSWORD).apply(text);
        });

        assertThat(asked).containsExactly(log);
    }

    /** No masked value reaches any excerpt line, whatever the selection. */
    @Test
    void noMaskedValueLeaksIntoAnyLine() throws IOException {
        for (String name : List.of("secrets_everywhere.log", "secrets_multiline.log",
            "secrets_window_start.log", "crlf.log")) {
            LogWindow window = fixture(name);
            for (Optional<String> contains : List.of(Optional.<String>empty(),
                Optional.of("node1"), Optional.of("password"), Optional.of("0001"))) {
                LogExcerpt excerpt = LogExcerpt.build(window, ServerLogBounds.MAX_TAIL_LINES,
                    contains, MAX, known(PASSWORD));

                String text = excerpt.lines().stream().map(ExcerptLine::text)
                    .collect(Collectors.joining("\n"));
                TestSecrets.assertNoLeak(text);
                assertThat(text).doesNotContain("-secret-0001",
                    "YWNtZTpiYXNpYy1zZWNyZXQtMDAwMQ==");
            }
        }
    }

    private static LogWindow fixture(String name) throws IOException {
        byte[] bytes = Files.readAllBytes(EXCERPT.resolve(name));
        return new LogWindow(bytes, 0, true, bytes.length);
    }

    private static LogWindow whole(String text) {
        byte[] bytes = utf8(text);
        return new LogWindow(bytes, 0, true, bytes.length);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** {@code "line 1\n" … "line n\n"}. */
    private static String numbered(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> "line " + i + "\n")
            .collect(Collectors.joining());
    }

    /** The profile's known credentials: every occurrence of {@code value}. */
    private static Function<String, List<SecretSpan>> known(String value) {
        return text -> {
            List<SecretSpan> spans = new ArrayList<>();
            for (int i = text.indexOf(value); i >= 0; i = text.indexOf(value, i + 1)) {
                spans.add(new SecretSpan(i, i + value.length()));
            }
            return spans;
        };
    }
}
