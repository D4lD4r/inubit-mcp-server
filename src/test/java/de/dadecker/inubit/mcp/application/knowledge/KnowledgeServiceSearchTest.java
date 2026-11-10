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
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.testing.FakeConfiguredSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Feature 019 T020: listing and searching the knowledge (research D-9). */
class KnowledgeServiceSearchTest {

    private static final String HELD = "Tr0ub4dor-held-value";

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

    private static Search all20() {
        return new Search(Optional.empty(), Optional.empty(), 20, 0);
    }

    private static Search query(String query) {
        return new Search(Optional.of(query), Optional.empty(), 20, 0);
    }

    private static List<String> names(SearchPage page) {
        return page.hits().stream().map(Hit::name).toList();
    }

    @Test
    void listsEveryNoteNewestFirstWithItsMetadata() throws IOException {
        String text = "---\ntitle: 'RFC to SAP'\ntopics: [sap, rfc]\nsource: 'assistant (x)'\n"
            + "---\nThe RFC destination is SAP_PRD.\n";
        fixture.note("systems/sap.md", text, NEWER.plusNanos(123_456_789));
        fixture.note("deploy.md", "Deploy on Fridays only.", OLDER);

        SearchPage page = service.search(all20());

        assertThat(names(page)).containsExactly("systems/sap", "deploy");
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.offset()).isZero();
        assertThat(page.limit()).isEqualTo(20);
        assertThat(page.nextOffset()).isEmpty();
        assertThat(page.warnings()).isEmpty();
        Hit sap = page.hits().getFirst();
        assertThat(sap.title()).isEqualTo("RFC to SAP");
        assertThat(sap.topics()).containsExactly("sap", "rfc");
        assertThat(sap.source()).isEqualTo("assistant (x)");
        assertThat(sap.lastChange()).isEqualTo(NEWER);
        assertThat(sap.ref()).isEqualTo(fixture.refs.of(Path.of("systems", "sap.md")));
        // the token takes the stored time in full (NTFS keeps steps of 100 ns)
        FileTime stored = Files.getLastModifiedTime(fixture.root.resolve("systems/sap.md"));
        assertThat(stored.toInstant()).isAfter(NEWER);
        assertThat(sap.versionToken()).contains(VersionToken.of(stored, text));
        assertThat(sap.excerpt()).contains("The RFC destination is SAP_PRD. ");
        assertThat(sap.masked()).isZero();
        assertThat(sap.writable()).isTrue();
        assertThat(sap.warnings()).isEmpty();
        Hit deploy = page.hits().get(1);
        assertThat(deploy.title()).isEqualTo("deploy");
        assertThat(deploy.topics()).isEmpty();
        assertThat(deploy.source()).isEqualTo("by hand");
        assertThat(deploy.excerpt()).contains("Deploy on Fridays only.");
    }

    @Test
    void anOffsetNearTheLargestIntegerGivesAnEmptyPageWithoutANextOffset() throws IOException {
        // phase 5 review: offset + limit must not wrap into a negative nextOffset
        fixture.note("a.md", "destination", NEWER);
        int offset = Integer.MAX_VALUE - 1;

        SearchPage list = service.search(new Search(Optional.empty(), Optional.empty(), 20,
            offset));
        SearchPage found = service.search(new Search(Optional.of("destination"),
            Optional.empty(), 20, offset));

        for (SearchPage page : List.of(list, found)) {
            assertThat(page.hits()).isEmpty();
            assertThat(page.total()).isEqualTo(1);
            assertThat(page.nextOffset()).isEmpty();
        }
    }

    @Test
    void anAbsentDirectoryListsNothing() throws IOException {
        Files.delete(fixture.root);

        SearchPage page = service.search(query("x"));

        assertThat(page.hits()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.warnings()).isEmpty();
    }

    @Test
    void aDirectoryThatIsAFileIsUnusable() throws IOException {
        Files.delete(fixture.root);
        Files.writeString(fixture.root, "not a directory");

        ToolError error = error(ErrorCode.PRECONDITION_FAILED, () -> service.search(all20()));

        assertThat(all(error)).contains("not usable", "not a directory", "--check-config");
    }

    @Test
    void theQueryIsALiteralCaseInsensitiveSubstring() throws IOException {
        fixture.note("a.md", "uses Version A.B here", NEWER);
        fixture.note("b.md", "uses version aXb here", OLDER);
        fixture.note("c.md", "---\ntitle: Über STRASSE\n---\nnothing", OLDER);
        fixture.note("topic.md", "---\ntopics: ['a.b']\n---\nnothing", OLDER);
        fixture.note("name a.b.md", "nothing", OLDER);

        assertThat(names(service.search(query("a.b"))))
            .containsExactlyInAnyOrder("a", "topic", "name a.b");
        assertThat(names(service.search(query("über strasse")))).containsExactly("c");
        assertThat(names(service.search(query("[a-z]")))).isEmpty();
    }

    @Test
    void theTopicIsComparedAfterFoldingAndAndsWithTheQuery() throws IOException {
        fixture.note("a.md", "---\ntopics: [SAP, rfc]\n---\ndestination", NEWER);
        fixture.note("b.md", "---\ntopics: [sap-rfc]\n---\ndestination", OLDER);
        fixture.note("c.md", "---\ntopics: [Sap]\n---\nother", OLDER);

        assertThat(names(service.search(new Search(Optional.empty(), Optional.of("sap"), 20, 0))))
            .containsExactly("a", "c");
        assertThat(names(service.search(
            new Search(Optional.of("destination"), Optional.of("sap"), 20, 0))))
            .containsExactly("a");
    }

    @Test
    void pagesByLimitAndOffset() throws IOException {
        fixture.note("a.md", "x", NEWER.plusSeconds(2));
        fixture.note("b.md", "x", NEWER.plusSeconds(1));
        fixture.note("c.md", "x", NEWER);

        SearchPage first = service.search(new Search(Optional.empty(), Optional.empty(), 2, 0));
        SearchPage second = service.search(new Search(Optional.of("x"), Optional.empty(), 2, 2));
        SearchPage beyond = service.search(new Search(Optional.empty(), Optional.empty(), 2, 9));

        assertThat(names(first)).containsExactly("a", "b");
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.nextOffset()).isEqualTo(OptionalInt.of(2));
        assertThat(names(second)).containsExactly("c");
        assertThat(second.total()).isEqualTo(3);
        assertThat(second.nextOffset()).isEmpty();
        assertThat(beyond.hits()).isEmpty();
        assertThat(beyond.nextOffset()).isEmpty();
    }

    @Test
    void theExcerptStartsAHundredCharactersBeforeTheFirstBodyMatch() throws IOException {
        String before = "b".repeat(150);
        String after = "a".repeat(400);
        fixture.note("long.md", "---\ntitle: needle\n---\n" + before + "line\nneedle\r\n" + after,
            NEWER);

        Hit hit = service.search(query("NEEDLE")).hits().getFirst();

        String excerpt = hit.excerpt().orElseThrow();
        assertThat(excerpt).hasSize(300)
            .startsWith("b".repeat(95) + "line needle a")
            .doesNotContain("\n", "\r");
    }

    @Test
    void theExcerptNeverSplitsASurrogatePair() throws IOException {
        String emoji = "\uD83D\uDE00";
        fixture.note("start.md", "x" + emoji.repeat(100) + "needle" + "y".repeat(400), NEWER);
        fixture.note("end.md", "x" + emoji.repeat(200), OLDER);

        for (Hit hit : service.search(all20()).hits()) {
            String excerpt = hit.excerpt().orElseThrow();
            assertThat(Character.isLowSurrogate(excerpt.charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(excerpt.charAt(excerpt.length() - 1))).isFalse();
            assertThat(excerpt.length()).isLessThanOrEqualTo(300);
        }
        String found = service.search(query("needle")).hits().getFirst().excerpt().orElseThrow();
        assertThat(Character.isLowSurrogate(found.charAt(0))).isFalse();
        assertThat(found).contains("needle");
    }

    @Test
    void secretsAreMaskedBeforeSearchingAndInEveryField() throws IOException {
        String text = "---\ntitle: 'login " + HELD + "'\ntopics: ['" + HELD + "']\n---\n"
            + "user admin\npassword=hunter2-secret\nthe value " + HELD + " again";
        fixture.note("creds.md", text, NEWER);
        fixture.note("name " + HELD + ".md", "plain", OLDER);

        assertThat(service.search(query("hunter2")).total()).isZero();
        assertThat(service.search(query(HELD.substring(0, 8))).total()).isZero();
        SearchPage page = service.search(query("***"));

        assertThat(names(page)).containsExactly("creds", "name ***");
        Hit creds = page.hits().getFirst();
        assertThat(creds.title()).isEqualTo("login ***");
        assertThat(creds.topics()).containsExactly("***");
        assertThat(creds.masked()).isEqualTo(4);
        assertThat(creds.excerpt().orElseThrow()).contains("password=***")
            .doesNotContain("hunter2", HELD);
        assertThat(creds.versionToken()).isPresent();
        Hit named = page.hits().get(1);
        assertThat(named.writable()).isFalse();
        assertThat(named.warnings()).anyMatch(w -> w.contains("cannot write"));
        assertThat(page.toString()).doesNotContain(HELD, "hunter2");
    }

    @Test
    void namesTitlesAndTopicsAreOneLineAndCut() throws IOException {
        String longTitle = "t".repeat(200);
        fixture.note("n\u2028m.md", "---\ntitle: '" + longTitle + "'\ntopics: ['a  \u00a0 b c']\n"
            + "---\nx", NEWER);

        Hit hit = service.search(all20()).hits().getFirst();

        assertThat(hit.name()).isEqualTo("n m");
        assertThat(hit.title()).hasSize(120).endsWith("…");
        assertThat(hit.topics()).containsExactly("a b c");
    }

    @Test
    void tooLargeNotesAreListedButNotSearched() throws IOException {
        fixture.note("big.md", "x".repeat(KnowledgeLimits.READ_BYTES + 1), NEWER);
        fixture.note("small.md", "x", OLDER);

        SearchPage listed = service.search(all20());
        SearchPage searched = service.search(query("x"));

        assertThat(names(listed)).containsExactly("big", "small");
        Hit big = listed.hits().getFirst();
        assertThat(big.title()).isEqualTo("big");
        assertThat(big.versionToken()).isEmpty();
        assertThat(big.excerpt()).isEmpty();
        assertThat(big.writable()).isFalse();
        assertThat(big.warnings()).anyMatch(w -> w.contains("larger than 4 MiB"));
        assertThat(names(searched)).containsExactly("small");
    }

    @Test
    void malformedMetadataIsListedWithTheFileNameAndSearchedOverTheWholeText()
        throws IOException {
        fixture.note("bad.md", "---\ntitle: a\ntitle: b\n---\nbody text", NEWER);

        Hit hit = service.search(query("title: b")).hits().getFirst();

        assertThat(hit.title()).isEqualTo("bad");
        assertThat(hit.writable()).isFalse();
        assertThat(hit.warnings()).contains("metadata block is malformed; fix it by hand");
        assertThat(hit.excerpt().orElseThrow()).startsWith("--- title: a title: b");
    }

    @Test
    void namesOutsideTheRecordRuleAreReadOnly() throws IOException {
        fixture.note("a+b.md", "x", NEWER);

        Hit hit = service.search(all20()).hits().getFirst();

        assertThat(hit.writable()).isFalse();
        assertThat(hit.warnings()).containsExactly(
            "the assistant cannot write to this note (name outside the record rule)");
    }

    @Test
    void namesEqualAfterNfcAreBothListedWithAWarning() throws IOException {
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

        SearchPage page = service.search(all20());

        assertThat(page.hits()).hasSize(2).allSatisfy(hit -> assertThat(hit.warnings())
            .contains("another file has the same name in another Unicode form; use ref"));
        assertThat(page.hits().get(0).ref()).isNotEqualTo(page.hits().get(1).ref());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void linksGiveMaskedDirectoryWarningsBoundedToTwentyLines() throws IOException {
        Path target = Files.writeString(temp.resolve("outside.md"), "x");
        Files.createSymbolicLink(fixture.root.resolve("a-old " + HELD + ".md"), target);
        for (int i = 0; i < 24; i++) {
            Files.createSymbolicLink(fixture.root.resolve("link" + i + ".md"), target);
        }

        List<String> warnings = service.search(all20()).warnings();

        assertThat(warnings).hasSize(21).contains("symbolic link a-old ***.md is ignored")
            .last().isEqualTo("… and 5 more");
        assertThat(warnings.toString()).doesNotContain(HELD);
    }

    @Test
    void logsCountsButNeverTheQueryOrTheTopic() throws IOException {
        fixture.note("a.md", "the content", NEWER);

        service.search(new Search(Optional.of("content"), Optional.of("secret-topic"), 20, 0));
        service.search(query("content"));

        List<String> lines = logs.list.stream()
            .filter(event -> event.getLoggerName().startsWith("de.dadecker"))
            .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines)
            .contains("knowledge.read notes=1 hits=0", "knowledge.read notes=1 hits=1");
        assertThat(String.join("\n", lines)).doesNotContain("content", "secret-topic");
    }
}
