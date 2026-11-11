package de.dadecker.inubit.mcp.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Feature 019 T017 (research D-5, FR-004, FR-004a, FR-004d): the metadata block of a note. */
class NoteMetadataTest {

    private static final Instant CHANGE = Instant.parse("2026-11-10T08:15:30Z");

    private static NoteMetadata.Written written(String title, List<String> topics) {
        return new NoteMetadata.Written(title, topics, "assistant (Claude)",
            "assistant (Claude)", CHANGE, List.of());
    }

    // --- parsing ---

    @Test
    void aTextWithoutABlockHasNoMetadataAndTheWholeTextIsTheBody() {
        NoteMetadata metadata = NoteMetadata.parse("# Heading\n\ntext\n");

        assertThat(metadata).isEqualTo(NoteMetadata.NONE);
        assertThat(metadata.bodyStart()).isZero();
        assertThat(metadata.malformed()).isFalse();
        assertThat(metadata.problem()).isEmpty();
    }

    @Test
    void anOpeningLineThatIsNotExactlyThreeDashesIsNoBlock() {
        assertThat(NoteMetadata.parse("--- \ntitle: x\n---\n")).isEqualTo(NoteMetadata.NONE);
        assertThat(NoteMetadata.parse("----\ntitle: x\n---\n")).isEqualTo(NoteMetadata.NONE);
        assertThat(NoteMetadata.parse("\n---\ntitle: x\n---\n")).isEqualTo(NoteMetadata.NONE);
        assertThat(NoteMetadata.parse("")).isEqualTo(NoteMetadata.NONE);
    }

    @Test
    void readsTheKnownKeysInPlainForm() {
        String text = "---\ntitle: Cluster restart\ntopics: [cluster, Ops]\nsource: by hand\n"
            + "lastWriter: assistant (Claude)\nlastChange: 2026-11-10T08:15:30Z\n---\nbody\n";

        NoteMetadata metadata = NoteMetadata.parse(text);

        assertThat(metadata.malformed()).isFalse();
        assertThat(metadata.title()).contains("Cluster restart");
        assertThat(metadata.topics()).containsExactly("cluster", "Ops");
        assertThat(metadata.source()).contains("by hand");
        assertThat(metadata.lastWriter()).contains("assistant (Claude)");
        assertThat(metadata.lastChange()).contains("2026-11-10T08:15:30Z");
        assertThat(metadata.unknown()).isEmpty();
        assertThat(text.substring(metadata.bodyStart())).isEqualTo("body\n");
    }

    @Test
    void readsDoubleAndSingleQuotedValuesAndItems() {
        String text = "---\ntitle: \"say \\\"hi\\\" \\\\ there\"\n"
            + "topics: ['it''s', \"a, b\", plain item, '']\nsource: 'by hand'\n---\n";

        NoteMetadata metadata = NoteMetadata.parse(text);

        assertThat(metadata.problem()).isEmpty();
        assertThat(metadata.title()).contains("say \"hi\" \\ there");
        assertThat(metadata.topics()).containsExactly("it's", "a, b", "plain item", "");
        assertThat(metadata.source()).contains("by hand");
        assertThat(metadata.bodyStart()).isEqualTo(text.length());
    }

    @Test
    void anEmptyFlowListHasNoTopicsAndAnEmptyValueIsAbsent() {
        NoteMetadata metadata = NoteMetadata.parse("---\ntitle:\ntopics: []\n---\n");

        assertThat(metadata.malformed()).isFalse();
        assertThat(metadata.title()).isEmpty();
        assertThat(metadata.topics()).isEmpty();
    }

    @Test
    void unknownKeysCommentsAndEmptyLinesAreKeptInOrder() {
        String text = "---\n# my own fields\nowner: me\ntitle: x\n\nreviewed: 'yes'\n---\n";

        NoteMetadata metadata = NoteMetadata.parse(text);

        assertThat(metadata.malformed()).isFalse();
        assertThat(metadata.unknown()).containsExactly(
            new NoteMetadata.Entry(Optional.empty(), "# my own fields"),
            new NoteMetadata.Entry(Optional.of("owner"), "owner: me"),
            new NoteMetadata.Entry(Optional.empty(), ""),
            new NoteMetadata.Entry(Optional.of("reviewed"), "reviewed: 'yes'"));
    }

    @Test
    void windowsLineEndsAreAccepted() {
        String text = "---\r\ntitle: x\r\ntopics: [a]\r\n---\r\nbody";

        NoteMetadata metadata = NoteMetadata.parse(text);

        assertThat(metadata.title()).contains("x");
        assertThat(metadata.topics()).containsExactly("a");
        assertThat(text.substring(metadata.bodyStart())).isEqualTo("body");
    }

    @Test
    void aClosingLineAtTheEndOfTheTextWithoutALineBreakCloses() {
        NoteMetadata metadata = NoteMetadata.parse("---\ntitle: x\n---");

        assertThat(metadata.malformed()).isFalse();
        assertThat(metadata.bodyStart()).isEqualTo(16);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "---\ntitle: x\n",
        "---\n",
        "---\r\ntitle: x\r\n",
        "---\ntitle x\n---\n",
        "---\ntitle:x\n---\n",
        "---\n title: x\n---\n",
        "---\nmy key: x\n---\n",
        "---\ntitle: a\ntitle: b\n---\n",
        "---\ntopics: a, b\n---\n",
        "---\ntopics: [a, b\n---\n",
        "---\ntopics: [a, b] c\n---\n",
        "---\ntopics: [a,, b]\n---\n",
        "---\ntopics: [a, [b]]\n---\n",
        "---\ntopics: ['a' b]\n---\n",
        "---\ntitle: \"open\n---\n",
        "---\ntitle: \"a\\nb\"\n---\n",
        "---\ntitle: \"a\"b\"\n---\n",
        "---\ntitle: 'a'b'\n---\n",
        "---\ntitle: 'a' b\n---\n",
        "---\ntitle: a\tb\n---\n",
        "---\ntitle: a\u0007b\n---\n",
        "---\ntitle: 'a\u0000b'\n---\n",
    })
    void malformedBlocksAreFlaggedAndTheWholeTextIsTheBody(String text) {
        NoteMetadata metadata = NoteMetadata.parse(text + "secret body");

        assertThat(metadata.malformed()).isTrue();
        assertThat(metadata.problem()).isPresent();
        assertThat(metadata.bodyStart()).isZero();
        assertThat(metadata.title()).isEmpty();
        assertThat(metadata.topics()).isEmpty();
        assertThat(metadata.unknown()).isEmpty();
    }

    @Test
    void theProblemNamesTheLineAndKeyButNeverAValue() {
        NoteMetadata metadata = NoteMetadata.parse("---\ntitle: ok\ntopics: Pa55word!\n---\n");

        assertThat(metadata.problem().orElseThrow()).contains("line 3", "topics")
            .doesNotContain("Pa55word");
        assertThat(NoteMetadata.parse("---\nPa55word!\n---\n").problem().orElseThrow())
            .contains("line 2").doesNotContain("Pa55word");
        assertThat(NoteMetadata.parse("---\ntitle: a\ntitle: b\n---\n").problem().orElseThrow())
            .contains("duplicate", "title");
    }

    private static String blockOfLines(int lines) {
        // opening and closing line plus lines - 2 comment lines
        return "---\n" + IntStream.range(0, lines - 2).mapToObj(i -> "# c")
            .collect(Collectors.joining("\n", "", "\n")) + "---\n";
    }

    @Test
    void theBlockMayHave64LinesButNot65() {
        assertThat(NoteMetadata.parse(blockOfLines(63)).malformed()).isFalse();
        assertThat(NoteMetadata.parse(blockOfLines(64)).malformed()).isFalse();
        assertThat(NoteMetadata.parse(blockOfLines(65)).malformed()).isTrue();
        assertThat(NoteMetadata.parse(blockOfLines(65)).problem().orElseThrow())
            .contains("64 lines", "8 KiB");
    }

    /** A block of exactly {@code bytes} UTF-8 bytes (including the closing line break). */
    private static String blockOfBytes(int bytes) {
        String frame = "---\nnote: \n---\n";
        int fill = bytes - frame.getBytes(StandardCharsets.UTF_8).length;
        // a two-byte letter keeps the count honest about bytes versus characters
        String value = "ä".repeat(fill / 2) + "a".repeat(fill % 2);
        String block = "---\nnote: " + value + "\n---\n";
        assertThat(block.getBytes(StandardCharsets.UTF_8)).hasSize(bytes);
        return block;
    }

    @Test
    void theBlockMayHave8KiBButNotOneByteMore() {
        assertThat(NoteMetadata.parse(blockOfBytes(8191) + "body").malformed()).isFalse();
        assertThat(NoteMetadata.parse(blockOfBytes(8192) + "body").malformed()).isFalse();
        assertThat(NoteMetadata.parse(blockOfBytes(8193) + "body").malformed()).isTrue();
    }

    @Test
    void aHugeFirstLineAfterTheOpeningIsMalformedQuickly() {
        String text = "---\ntitle: " + "x".repeat(4_000_000);

        long start = System.nanoTime();
        assertThat(NoteMetadata.parse(text).malformed()).isTrue();
        assertThat(System.nanoTime() - start).isLessThan(2_000_000_000L);
    }

    // --- writing ---

    @Test
    void writesEveryStringSingleQuotedInKeyOrder() {
        String block = NoteMetadata.write(written("Cluster restart", List.of("cluster", "ops")))
            .orElseThrow();

        assertThat(block).isEqualTo("---\ntitle: 'Cluster restart'\ntopics: ['cluster', 'ops']\n"
            + "source: 'assistant (Claude)'\nlastWriter: 'assistant (Claude)'\n"
            + "lastChange: '2026-11-10T08:15:30Z'\n---\n");
    }

    @Test
    void writesNoTopicsAsAnEmptyListAndKeepsUnknownEntriesAfterTheServerKeys() {
        NoteMetadata.Written values = new NoteMetadata.Written("t", List.of(), "by hand",
            "assistant (unknown client)", CHANGE, List.of(
                new NoteMetadata.Entry(Optional.empty(), "# mine"),
                new NoteMetadata.Entry(Optional.of("owner"), "owner: me")));

        String block = NoteMetadata.write(values).orElseThrow();

        assertThat(block).contains("topics: []\n").endsWith("# mine\nowner: me\n---\n");
        assertThat(NoteMetadata.parse(block).unknown()).isEqualTo(values.unknown());
    }

    @ParameterizedTest
    @ValueSource(strings = {"it's", "say \"hi\"", "back\\slash \\\\", "a # b", "key: value",
        "---", "'", "''", "[a, b]", "   spaced   ", "ẞtörung €"})
    void whatTheServerWritesItReadsBackUnchanged(String value) {
        String block = NoteMetadata.write(written(value, List.of(value, "other"))).orElseThrow();

        NoteMetadata metadata = NoteMetadata.parse(block + "body");

        assertThat(metadata.problem()).isEmpty();
        assertThat(metadata.title()).contains(value);
        assertThat(metadata.topics()).containsExactly(value, "other");
        assertThat(metadata.source()).contains("assistant (Claude)");
        assertThat(metadata.lastWriter()).contains("assistant (Claude)");
        assertThat(metadata.lastChange()).contains("2026-11-10T08:15:30Z");
        assertThat(metadata.bodyStart()).isEqualTo(block.length());
    }

    @Test
    void aValueWithALineBreakOrAControlCharacterIsAProgrammingError() {
        assertThatThrownBy(() -> NoteMetadata.write(written("a\nb", List.of())))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NoteMetadata.write(written("t", List.of("a\u0000"))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static NoteMetadata.Written withUnknownLines(int count) {
        return new NoteMetadata.Written("t", List.of(), "by hand", "by hand", CHANGE,
            IntStream.range(0, count).mapToObj(i -> new NoteMetadata.Entry(Optional.empty(),
                "# c")).toList());
    }

    @Test
    void aBlockOver64LinesIsRefused() {
        // 7 lines of server keys and dashes, so 57 kept lines make 64
        assertThat(NoteMetadata.write(withUnknownLines(56))).isPresent();
        assertThat(NoteMetadata.write(withUnknownLines(57)).orElseThrow().lines()).hasSize(64);
        assertThat(NoteMetadata.write(withUnknownLines(58))).isEmpty();
    }

    @Test
    void aBlockOver8KiBIsRefused() {
        String base = NoteMetadata.write(written("", List.of())).orElseThrow();
        int fill = 8192 - base.getBytes(StandardCharsets.UTF_8).length;

        assertThat(NoteMetadata.write(written("ä".repeat(fill / 2), List.of()))).isPresent();
        assertThat(NoteMetadata.write(written("ä".repeat(fill / 2) + "a", List.of())))
            .as("8 KiB + 1 byte").isEmpty();
    }

    @Test
    void theServerKeysAloneStayFarBelowTheBounds() {
        String title = "'\uD83D\uDE00".repeat(100);
        List<String> topics = IntStream.range(0, 20).mapToObj(i -> "'\uD83D\uDE00".repeat(30))
            .toList();
        String client = "assistant (" + "'".repeat(60) + ")";

        assertThat(NoteMetadata.write(new NoteMetadata.Written(title, topics, client, client,
            CHANGE, List.of()))).isPresent();
    }
}
