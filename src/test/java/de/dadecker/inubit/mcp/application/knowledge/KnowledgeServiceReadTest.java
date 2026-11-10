package de.dadecker.inubit.mcp.application.knowledge;

import static de.dadecker.inubit.mcp.application.knowledge.KnowledgeFixture.NEWER;
import static de.dadecker.inubit.mcp.application.knowledge.KnowledgeFixture.OLDER;
import static de.dadecker.inubit.mcp.application.knowledge.KnowledgeFixture.all;
import static de.dadecker.inubit.mcp.application.knowledge.KnowledgeFixture.error;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.application.knowledge.NoteSelector.ByName;
import de.dadecker.inubit.mcp.application.knowledge.NoteSelector.ByRef;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.testing.FakeConfiguredSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Feature 019 T020: reading one note (research D-4, D-9, D-14). */
class KnowledgeServiceReadTest {

    private static final String HELD = "Tr0ub4dor-held-value";
    private static final String MARKER = ItemBounds.TRUNCATION_MARKER;

    @TempDir
    Path temp;

    private KnowledgeFixture fixture;
    private KnowledgeService service;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new KnowledgeFixture(temp);
        service = fixture.service(FakeConfiguredSecrets.held(HELD));
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logs.setContext(context);
        logs.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(logs);
        Logger ours = context.getLogger("de.dadecker");
        previousLevel = ours.getLevel();
        ours.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(logs);
        logs.stop();
        context.getLogger("de.dadecker").setLevel(previousLevel);
    }

    @Test
    void readsANoteByNameWithItsMetadata() throws IOException {
        String text = "---\ntitle: RFC\ntopics: [sap]\nsource: 'assistant (a)'\n"
            + "lastWriter: 'assistant (b)'\nlastChange: '2020-01-01T00:00:00Z'\nown: kept\n---\n"
            + "The body.\n";
        fixture.note("systems/sap.md", text, NEWER);

        NotePage page = service.read(new ByName("systems/sap"), 0);

        assertThat(page.name()).isEqualTo("systems/sap");
        assertThat(page.title()).isEqualTo("RFC");
        assertThat(page.topics()).containsExactly("sap");
        assertThat(page.source()).isEqualTo("assistant (a)");
        assertThat(page.lastWriter()).contains("assistant (b)");
        assertThat(page.lastChange()).isEqualTo(NEWER);
        assertThat(page.versionToken()).isEqualTo(VersionToken.of(FileTime.from(NEWER), text));
        assertThat(page.masked()).isZero();
        assertThat(page.length()).isEqualTo("The body.\n".length());
        assertThat(page.offset()).isZero();
        assertThat(page.content()).isEqualTo("The body.\n");
        assertThat(page.nextOffset()).isEmpty();
        assertThat(page.warnings()).isEmpty();
    }

    @Test
    void readsANoteByItsRef() throws IOException {
        fixture.note("a.md", "by ref", NEWER);
        String ref = service.search(new Search(Optional.empty(), Optional.empty(), 20, 0))
            .hits().getFirst().ref();

        NotePage page = service.read(new ByRef(ref), 0);

        assertThat(page.name()).isEqualTo("a");
        assertThat(page.title()).isEqualTo("a");
        assertThat(page.source()).isEqualTo("by hand");
        assertThat(page.lastWriter()).isEmpty();
        assertThat(page.content()).isEqualTo("by ref");
    }

    @Test
    void anUnknownRefAsksToListAgain() throws IOException {
        fixture.note("a.md", "x", NEWER);

        ToolError error = error(ErrorCode.NOT_FOUND,
            () -> service.read(new ByRef("r1.AAAAAAAAAAAAAAAAAAAA"), 0));

        assertThat(all(error)).contains("list the notes again");
    }

    @Test
    void pagesHoldTwentyThousandCharactersIncludingTheMarker() throws IOException {
        String body = "0123456789".repeat(5_000);
        fixture.note("long.md", body, NEWER);

        NotePage first = service.read(new ByName("long"), 0);
        int next = first.nextOffset().orElseThrow();
        NotePage second = service.read(new ByName("long"), next);
        NotePage third = service.read(new ByName("long"), second.nextOffset().orElseThrow());

        assertThat(first.content()).hasSize(KnowledgeLimits.PAGE_CHARS).endsWith(MARKER);
        assertThat(next).isEqualTo(KnowledgeLimits.PAGE_CHARS - MARKER.length());
        assertThat(first.length()).isEqualTo(50_000);
        assertThat(second.offset()).isEqualTo(next);
        assertThat(third.nextOffset()).isEmpty();
        String joined = strip(first.content()) + strip(second.content()) + third.content();
        assertThat(joined).isEqualTo(body);
    }

    private static String strip(String content) {
        return content.substring(0, content.length() - MARKER.length());
    }

    @Test
    void pagesNeverSplitASurrogatePair() throws IOException {
        String emoji = "\uD83D\uDE00";
        int cut = KnowledgeLimits.PAGE_CHARS - MARKER.length();
        String body = "x".repeat(cut - 1) + emoji + "y".repeat(100);
        fixture.note("emoji.md", body, NEWER);

        NotePage first = service.read(new ByName("emoji"), 0);
        NotePage inside = service.read(new ByName("emoji"), cut);

        assertThat(first.nextOffset()).isEqualTo(OptionalInt.of(cut - 1));
        assertThat(first.content()).endsWith("x" + MARKER);
        assertThat(inside.offset()).isEqualTo(cut - 1);
        assertThat(inside.content()).startsWith(emoji);
    }

    @Test
    void anOffsetBeyondTheContentIsInvalid() throws IOException {
        fixture.note("a.md", "short", NEWER);

        assertThat(service.read(new ByName("a"), 5).content()).isEmpty();
        error(ErrorCode.INVALID_INPUT, () -> service.read(new ByName("a"), 6));
    }

    @Test
    void secretsAreMaskedBeforePaging() throws IOException {
        String text = "---\ntitle: 'k " + HELD + "'\n---\nuse " + HELD + " and password=abc123";
        fixture.note("creds.md", text, NEWER);

        NotePage page = service.read(new ByName("creds"), 0);

        assertThat(page.title()).isEqualTo("k ***");
        assertThat(page.content()).isEqualTo("use *** and password=***");
        assertThat(page.length()).isEqualTo(page.content().length());
        assertThat(page.masked()).isEqualTo(3);
        assertThat(page.versionToken()).isEqualTo(VersionToken.of(FileTime.from(NEWER),
            "---\ntitle: 'k ***'\n---\nuse *** and password=***"));
        assertThat(page.toString()).doesNotContain(HELD, "abc123");
    }

    @Test
    void aMalformedNoteIsReadAsAWhole() throws IOException {
        fixture.note("bad.md", "---\nno closing", NEWER);

        NotePage page = service.read(new ByName("bad"), 0);

        assertThat(page.title()).isEqualTo("bad");
        assertThat(page.content()).isEqualTo("---\nno closing");
        assertThat(page.warnings()).containsExactly("metadata block is malformed; fix it by hand");
    }

    @Test
    void aMissingNameListsSimilarNamesCaseInsensitiveFirstAndMasked() throws IOException {
        fixture.note("deploy-old.md", "x", NEWER);
        fixture.note("Deploy.md", "x", OLDER);
        fixture.note("deploy " + HELD + ".md", "x", OLDER);
        fixture.note("unrelated.md", "x", OLDER);

        ToolError error = error(ErrorCode.NOT_FOUND, () -> service.read(new ByName("deploy"), 0));

        assertThat(error.message()).contains("'deploy'", "'Deploy', ", "'deploy-old'",
            "'deploy ***'").doesNotContain("unrelated", HELD);
        assertThat(error.message().indexOf("'Deploy'"))
            .isLessThan(error.message().indexOf("'deploy-old'"));
    }

    @Test
    void aMissingNameThatHoldsASecretIsMaskedInTheError() {
        ToolError error = error(ErrorCode.NOT_FOUND,
            () -> service.read(new ByName("x " + HELD), 0));

        assertThat(all(error)).doesNotContain(HELD).contains("'x ***'");
    }

    @Test
    void aNameThatTwoFilesShareIsAConflict() throws IOException {
        String nfc = Normalizer.normalize("Störung", Normalizer.Form.NFC);
        String nfd = Normalizer.normalize("Störung", Normalizer.Form.NFD);
        fixture.note(nfc + ".md", "nfc", NEWER);
        Path second = fixture.root.resolve(nfd + ".md");
        try {
            Files.writeString(second, "nfd");
        } catch (IOException e) {
            assumeTrue(false, "the file system refuses the second form");
        }
        try (Stream<Path> files = Files.list(fixture.root)) {
            assumeTrue(files.count() == 2, "the file system keeps both forms apart");
        }

        ToolError error = error(ErrorCode.CONFLICT, () -> service.read(new ByName(nfd), 0));

        assertThat(all(error)).contains("several notes have this name", "use ref");
    }

    @Test
    void aTooLargeNoteCannotBeRead() throws IOException {
        fixture.note("big " + HELD + ".md", "x".repeat(KnowledgeLimits.READ_BYTES + 1), NEWER);

        ToolError error = error(ErrorCode.PRECONDITION_FAILED,
            () -> service.read(new ByName("big " + HELD), 0));

        assertThat(all(error)).contains("'big ***'", "larger than 4 MiB").doesNotContain(HELD);
    }

    @Test
    void anAbsentDirectoryHasNoNote() throws IOException {
        Files.delete(fixture.root);

        error(ErrorCode.NOT_FOUND, () -> service.read(new ByName("a"), 0));
    }

    @Test
    void logsTheMaskedNameButNeverTheContent() throws IOException {
        fixture.note("n " + HELD + ".md", "the content", NEWER);

        service.read(new ByName("n " + HELD), 0);

        List<String> lines = logs.list.stream()
            .filter(event -> event.getLoggerName().startsWith("de.dadecker"))
            .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).contains("knowledge.read notes=1 hits=1 name=n ***");
        assertThat(String.join("\n", lines)).doesNotContain("content", HELD);
    }
}
