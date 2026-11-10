package de.dadecker.inubit.mcp.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.WriteRefusal;
import de.dadecker.inubit.mcp.application.knowledge.Record.Mode;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.testing.FakeConfiguredSecrets;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 019 T028 (research D-10, D-12): the size of the knowledge directory for the 100 MiB
 * bound - every regular file, history included, the lock file excluded, links not followed, no
 * depth limit, and fail closed when there are too many entries to measure; temporary files of a
 * crashed write older than one hour are removed on the way. 019-T035 (SC-004, US2 AS8): 5,000
 * notes and a 1 MiB note keep the instructions, a search page and a read page within their bounds
 * and report the cut; a note reaches exactly 256 KiB and the knowledge exactly 100 MiB, one byte
 * more is refused.
 */
class KnowledgeBoundsTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:15:00Z");
    private static final String UUID = "123e4567-e89b-12d3-a456-426614174000";

    @TempDir
    Path temp;

    private Path root() throws IOException {
        return Files.createDirectories(temp.resolve("knowledge"));
    }

    private static KnowledgeDirectory directory(Path root) {
        return new KnowledgeDirectory(root, PrivateFiles.forHost(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Path file(Path root, String relative, int bytes) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.write(file, new byte[bytes]);
    }

    @Test
    void anAbsentDirectoryMeasuresZero() throws Exception {
        assertThat(directory(temp.resolve("missing")).totalBytes()).isZero();
    }

    @Test
    void everyRegularFileCountsWithTheHistoryButWithoutTheLockFile() throws Exception {
        Path root = root();
        file(root, "a.md", 10);
        file(root, "partners/acme.md", 20);
        file(root, "notes.txt", 5);
        file(root, ".hidden/x", 7);
        file(root, ".history/a.md.md/20261110T101500.000000000Z-000.md", 30);
        file(root, ".lock", 1000);

        assertThat(directory(root).totalBytes()).isEqualTo(10 + 20 + 5 + 7 + 30);
    }

    @Test
    void theMeasureHasNoDepthLimit() throws Exception {
        Path root = root();
        String deep = "d/".repeat(KnowledgeLimits.WALK_DEPTH + 4) + "deep.md";
        file(root, deep, 11);

        assertThat(directory(root).totalBytes()).isEqualTo(11);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void linksAreNotFollowed() throws Exception {
        Path root = root();
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path big = file(outside, "big.md", 5000);
        Files.createSymbolicLink(root.resolve("big.md"), big);
        Files.createSymbolicLink(root.resolve("dir"), outside);
        file(root, "a.md", 3);

        assertThat(directory(root).totalBytes()).isEqualTo(3);
    }

    @Test
    void tooManyEntriesFailClosed() throws Exception {
        Path root = root();
        for (int i = 0; i < 5; i++) {
            file(root, "n" + i + ".md", 1);
        }
        KnowledgeDirectory directory = new KnowledgeDirectory(root, PrivateFiles.forHost(),
            Clock.fixed(NOW, ZoneOffset.UTC), KnowledgeLimits.WALK_DEPTH,
            KnowledgeLimits.WALK_ENTRIES, 3);

        assertThatThrownBy(directory::totalBytes).isInstanceOfSatisfying(WriteRefusal.class,
            e -> assertThat(e.kind()).isEqualTo(WriteRefusal.Kind.TOO_LARGE_TO_MEASURE));
        assertThat(new KnowledgeDirectory(root, PrivateFiles.forHost(),
            Clock.fixed(NOW, ZoneOffset.UTC), KnowledgeLimits.WALK_DEPTH,
            KnowledgeLimits.WALK_ENTRIES, 5).totalBytes()).isEqualTo(5);
    }

    @Test
    void temporaryFilesOlderThanAnHourAreRemovedAndNotCounted() throws Exception {
        Path root = root();
        Path stale = file(root, "partners/.acme.md." + UUID + ".tmp", 100);
        Files.setLastModifiedTime(stale, FileTime.from(NOW.minus(Duration.ofMinutes(61))));
        Path fresh = file(root, ".b.md." + UUID + ".tmp", 7);
        Files.setLastModifiedTime(fresh, FileTime.from(NOW.minus(Duration.ofMinutes(59))));
        Path other = file(root, ".notes.tmp", 3);
        Files.setLastModifiedTime(other, FileTime.from(NOW.minus(Duration.ofDays(2))));
        Path history = file(root, ".history/.c.md." + UUID + ".tmp", 2);
        Files.setLastModifiedTime(history, FileTime.from(NOW.minus(Duration.ofDays(2))));

        assertThat(directory(root).totalBytes()).isEqualTo(7 + 3 + 2);

        assertThat(stale).doesNotExist();
        assertThat(fresh).exists();
        assertThat(other).exists();
        assertThat(history).as("never written there, so left alone").exists();
    }

    private static KnowledgeService service(Path root) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new KnowledgeService(directory(root), FakeConfiguredSecrets.held(),
            new NoteRefs(new SecureRandom()), Duration.ofSeconds(30),
            new KnowledgeService.Writing(PrivateFiles.forHost(), clock,
                new KnowledgeAudit.Dependencies(record -> { }, clock, "acme",
                    java.util.UUID::randomUUID)));
    }

    private static Record create(String name, String content) {
        return new Record(Optional.of(name), Optional.empty(), Mode.CREATE, Optional.of("T"),
            Optional.empty(), content, Optional.empty(), Optional.of("bounds"),
            Optional.of("bounds/1"));
    }

    private static Record append(String name, String section) {
        return new Record(Optional.of(name), Optional.empty(), Mode.APPEND, Optional.empty(),
            Optional.empty(), section, Optional.empty(), Optional.of("bounds"),
            Optional.of("bounds/1"));
    }

    private static ToolError refusal(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            return e.error();
        }
        throw new AssertionError("the call was not refused");
    }

    private static void sparse(Path file, long bytes) throws IOException {
        try (RandomAccessFile out = new RandomAccessFile(file.toFile(), "rw")) {
            out.setLength(bytes);
        }
    }

    @Test
    @Timeout(120)
    void fiveThousandNotesAndAOneMebibyteNoteStayWithinTheirBoundsAndReportTheCut()
        throws IOException {
        Path root = root();
        for (int i = 0; i < 5_000; i++) {
            Path note = root.resolve("bulk-%02d/note-%04d.md".formatted(i % 50, i));
            Files.createDirectories(note.getParent());
            Files.writeString(note, "---\ntitle: Bulk note %d\ntopics: [bulk, t%d]\n---\n"
                .formatted(i, i) + "word ".repeat(100));
            Files.setLastModifiedTime(note, FileTime.from(NOW.minusSeconds(i + 1L)));
        }
        String big = "line of the big note\n".repeat(52_429);
        Files.writeString(root.resolve("big.md"), "---\ntitle: Big\n---\n" + big);
        Files.setLastModifiedTime(root.resolve("big.md"), FileTime.from(NOW));
        KnowledgeService service = service(root);

        String paragraph = KnowledgeAnnouncement.paragraph(true,
            service.overview(Duration.ofSeconds(60)));
        String list = paragraph.substring(paragraph.indexOf("Notes (newest first):"));
        SearchPage page = service.search(new Search(Optional.of("word"), Optional.empty(),
            KnowledgeLimits.SEARCH_LIMIT_MAX, 0));
        SearchPage last = service.search(new Search(Optional.empty(), Optional.of("bulk"),
            KnowledgeLimits.SEARCH_LIMIT_MAX, 4_990));
        NotePage read = service.read(new NoteSelector.ByName("big"), 0);
        NotePage further = service.read(new NoteSelector.ByName("big"),
            read.nextOffset().orElseThrow());

        assertThat(big.length()).isGreaterThan(1_048_576);
        assertThat(list.length()).isLessThanOrEqualTo(KnowledgeLimits.LIST_CHARS);
        assertThat(list).startsWith("Notes (newest first):\n- \"big\" — \"Big\"")
            .containsPattern("\\(\\d+ of 5001 notes shown; search with read_knowledge for the"
                + " others\\.\\)$");
        assertThat(page.total()).isEqualTo(5_000);
        assertThat(page.hits()).hasSize(KnowledgeLimits.SEARCH_LIMIT_MAX)
            .allSatisfy(hit -> assertThat(hit.excerpt().orElseThrow().length())
                .isLessThanOrEqualTo(KnowledgeLimits.EXCERPT_CHARS));
        assertThat(page.nextOffset()).hasValue(KnowledgeLimits.SEARCH_LIMIT_MAX);
        assertThat(last.hits()).hasSize(10);
        assertThat(last.nextOffset()).isEmpty();
        assertThat(read.length()).isEqualTo(big.length());
        assertThat(read.content().length()).isLessThanOrEqualTo(KnowledgeLimits.PAGE_CHARS);
        assertThat(read.nextOffset()).isPresent();
        assertThat(further.offset()).isEqualTo(read.nextOffset().getAsInt());
        assertThat(further.content().length()).isLessThanOrEqualTo(KnowledgeLimits.PAGE_CHARS);
    }

    @Test
    void aNoteReachesExactlyTheNoteBoundAndOneByteMoreIsRefused() throws IOException {
        Path root = root();
        KnowledgeService service = service(root);
        service.record(create("a", "x"));
        Pattern wouldHold = Pattern.compile("would hold (\\d+) bytes");
        int probe = KnowledgeLimits.NOTE_BYTES;

        ToolError over = refusal(() -> service.record(append("a", "y".repeat(probe))));
        Matcher size = wouldHold.matcher(over.message());
        assertThat(size.find()).as(over.message()).isTrue();
        int exact = probe - (Integer.parseInt(size.group(1)) - KnowledgeLimits.NOTE_BYTES);
        ToolError oneMore = refusal(() -> service.record(append("a", "y".repeat(exact + 1))));
        Recorded recorded = service.record(append("a", "y".repeat(exact)));

        assertThat(over.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(oneMore.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(oneMore.message()).contains("would hold " + (KnowledgeLimits.NOTE_BYTES + 1)
            + " bytes", "256 KiB");
        assertThat(recorded.sizeAfter()).isEqualTo(KnowledgeLimits.NOTE_BYTES);
        assertThat(Files.size(root.resolve("a.md"))).isEqualTo(KnowledgeLimits.NOTE_BYTES);
    }

    @Test
    void theKnowledgeReachesExactlyItsBoundAndOneByteMoreIsRefused() throws IOException {
        Path probeRoot = Files.createDirectories(temp.resolve("probe"));
        long note = service(probeRoot).record(create("a", "x")).sizeAfter();
        Path atBound = Files.createDirectories(temp.resolve("at-bound"));
        Path overBound = Files.createDirectories(temp.resolve("over-bound"));
        sparse(atBound.resolve("data.bin"), KnowledgeLimits.TOTAL_BYTES - note);
        sparse(overBound.resolve("data.bin"), KnowledgeLimits.TOTAL_BYTES - note + 1);

        Recorded recorded = service(atBound).record(create("a", "x"));
        ToolError refused = refusal(() -> service(overBound).record(create("a", "x")));

        assertThat(recorded.sizeAfter()).isEqualTo(note);
        assertThat(refused.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(refused.message()).contains("100 MiB");
        assertThat(overBound.resolve("a.md")).doesNotExist();
        assertThat(atBound.resolve("a.md")).isRegularFile();
    }
}
