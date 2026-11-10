package de.dadecker.inubit.mcp.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.WriteRefusal;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 019 T028 (research D-10, D-12): the size of the knowledge directory for the 100 MiB
 * bound - every regular file, history included, the lock file excluded, links not followed, no
 * depth limit, and fail closed when there are too many entries to measure; temporary files of a
 * crashed write older than one hour are removed on the way.
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
}
