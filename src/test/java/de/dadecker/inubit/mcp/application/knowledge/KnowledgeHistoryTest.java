package de.dadecker.inubit.mcp.application.knowledge;

import static de.dadecker.inubit.mcp.application.knowledge.KnowledgeFixture.all;
import static de.dadecker.inubit.mcp.application.knowledge.KnowledgeFixture.error;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NoteName;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * 019-T027 (research D-11): the versions of a note in
 * {@code .history/<note path>.md/<instant>-<counter>.md}, a new one never replacing an existing
 * one, pruned to the ten newest by the parsed (instant, counter), files of another pattern kept.
 */
class KnowledgeHistoryTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:15:00.123456789Z");
    private static final NoteName ACME = NoteName.of("partners/acme");

    @TempDir
    Path temp;

    private KnowledgeFixture fixture;
    private final MutableClock clock = new MutableClock(NOW);

    private KnowledgeHistory history() throws IOException {
        if (fixture == null) {
            fixture = new KnowledgeFixture(temp);
        }
        return new KnowledgeHistory(fixture.root, PrivateFiles.forHost(), clock);
    }

    private Path note(String text) throws IOException {
        history();
        return fixture.note("partners/acme.md", text, NOW);
    }

    private Path versions() {
        return fixture.root.resolve(".history/partners/acme.md");
    }

    private List<String> versionNames() throws IOException {
        try (Stream<Path> files = Files.list(versions())) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void aVersionIsACopyOfTheNoteNamedByTheInstantAndTheFirstCounter() throws IOException {
        Path note = note("first");

        Path version = history().add(note, ACME);

        assertThat(version).isEqualTo(versions().resolve("20261110T101500.123456789Z-000.md"));
        assertThat(version).hasContent("first");
        assertThat(note).hasContent("first");
    }

    @Test
    void aWholeSecondStillCarriesNineFractionDigits() throws IOException {
        Path note = note("x");
        KnowledgeHistory whole = new KnowledgeHistory(fixture.root, PrivateFiles.forHost(),
            Clock.fixed(Instant.parse("2026-11-10T10:15:00Z"), ZoneOffset.UTC));

        Path version = whole.add(note, ACME);

        assertThat(version.getFileName()).hasToString("20261110T101500.000000000Z-000.md");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void versionsAndTheDirectoriesCreatedForThemAreOwnerOnly() throws IOException {
        Path version = history().add(note("x"), ACME);

        assertThat(permissions(version)).isEqualTo("rw-------");
        assertThat(permissions(fixture.root.resolve(".history"))).isEqualTo("rwx------");
        assertThat(permissions(fixture.root.resolve(".history/partners")))
            .isEqualTo("rwx------");
        assertThat(permissions(versions())).isEqualTo("rwx------");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anExistingHistoryDirectoryKeepsItsPermissions() throws IOException {
        Path existing = Files.createDirectories(temp.resolve("knowledge/.history"));
        Files.setPosixFilePermissions(existing, PosixFilePermissions.fromString("rwxr-x---"));

        history().add(note("x"), ACME);

        assertThat(permissions(existing)).isEqualTo("rwxr-x---");
    }

    private static String permissions(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    @Test
    void twelveVersionsInOneInstantKeepTheTenNewestWithTheirContents() throws IOException {
        KnowledgeHistory history = history();
        Path note = note("v0");
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Files.writeString(note, "v" + i);
            history.add(note, ACME);
            kept.add(history.prune(ACME));
        }

        assertThat(kept).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10, 10);
        List<String> names = versionNames();
        assertThat(names).hasSize(10).first().asString().endsWith("-002.md");
        assertThat(names).last().asString().endsWith("-011.md");
        for (int i = 2; i < 12; i++) {
            assertThat(versions().resolve("20261110T101500.123456789Z-%03d.md".formatted(i)))
                .hasContent("v" + i);
        }
    }

    @Test
    void theCounterIsOneAboveTheGreatestOfTheInstantSoGapsAreNotReused() throws IOException {
        KnowledgeHistory history = history();
        Path note = note("x");
        Files.createDirectories(versions());
        Files.writeString(versions().resolve("20261110T101500.123456789Z-007.md"), "old");

        assertThat(history.add(note, ACME).getFileName())
            .hasToString("20261110T101500.123456789Z-008.md");
        assertThat(versions().resolve("20261110T101500.123456789Z-007.md")).hasContent("old");
    }

    @Test
    void aVersionBeyondCounter999IsRefusedAndNothingIsTouched() throws IOException {
        KnowledgeHistory history = history();
        Path note = note("x");
        Files.createDirectories(versions());
        Files.writeString(versions().resolve("20261110T101500.123456789Z-999.md"), "last");

        ToolError error = error(ErrorCode.PRECONDITION_FAILED, () -> history.add(note, ACME));

        assertThat(all(error)).contains("999");
        assertThat(versionNames()).containsExactly("20261110T101500.123456789Z-999.md");
    }

    @Test
    void pruningDeletesTheOldestByInstantThenCounter() throws IOException {
        KnowledgeHistory history = history();
        Path note = note("x");
        Files.createDirectories(versions());
        // ten versions: a high counter of an older instant is older than counter 000 of a newer one
        for (int counter = 0; counter < 5; counter++) {
            Files.writeString(versions().resolve(
                "20251231T235959.999999999Z-%03d.md".formatted(counter + 5)), "old" + counter);
            Files.writeString(versions().resolve(
                "2026010%dT000000.000000000Z-000.md".formatted(counter + 1)), "new" + counter);
        }

        Path newest = history.add(note, ACME);
        int kept = history.prune(ACME);

        assertThat(kept).isEqualTo(10);
        assertThat(newest).exists();
        assertThat(versions().resolve("20251231T235959.999999999Z-005.md")).doesNotExist();
        assertThat(versions().resolve("20251231T235959.999999999Z-006.md")).exists();
        assertThat(versions().resolve("20260101T000000.000000000Z-000.md")).exists();
    }

    @Test
    void filesOfAnotherPatternAreNeitherCountedNorPruned() throws IOException {
        KnowledgeHistory history = history();
        Path note = note("x");
        Files.createDirectories(versions());
        Files.writeString(versions().resolve("restored by hand.md"), "kept");
        Files.writeString(versions().resolve("20261110T101500.123Z-000.md"), "kept");
        for (int i = 0; i < 11; i++) {
            history.add(note, ACME);
        }

        assertThat(history.prune(ACME)).isEqualTo(10);
        assertThat(versions().resolve("restored by hand.md")).hasContent("kept");
        assertThat(versions().resolve("20261110T101500.123Z-000.md")).hasContent("kept");
        assertThat(versionNames()).hasSize(12);
    }

    @Test
    void undoRemovesTheAddedVersionOnly() throws IOException {
        KnowledgeHistory history = history();
        Path note = note("x");
        Path first = history.add(note, ACME);
        Path second = history.add(note, ACME);

        history.undo(second);

        assertThat(second).doesNotExist();
        assertThat(first).exists();
        assertThat(note).hasContent("x");
    }

    @Test
    void theHistoryOfANoteAtTheTopLevelLiesDirectlyBelowHistory() throws IOException {
        KnowledgeHistory history = history();
        Path note = fixture.note("deploy.md", "x", NOW);

        Path version = history.add(note, NoteName.of("deploy"));

        assertThat(version.getParent()).isEqualTo(fixture.root.resolve(".history/deploy.md"));
        assertThat(history.prune(NoteName.of("deploy"))).isEqualTo(1);
    }

    @Test
    void pruningANoteWithoutHistoryKeepsNothing() throws IOException {
        assertThat(history().prune(ACME)).isZero();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aLinkAsTheHistoryDirectoryIsRefusedAndNothingIsWrittenOutside(@TempDir Path outside)
        throws IOException {
        KnowledgeHistory history = history();
        Path note = note("secret-free");
        Files.createSymbolicLink(fixture.root.resolve(".history"), outside);

        ToolError error = error(ErrorCode.PRECONDITION_FAILED, () -> history.add(note, ACME));

        assertThat(all(error)).contains(".history").contains("link");
        assertThat(outside).isEmptyDirectory();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aLinkAsAHistorySegmentIsRefusedAndNothingIsWrittenOutside(@TempDir Path outside)
        throws IOException {
        KnowledgeHistory history = history();
        Path note = note("x");
        Files.createDirectories(fixture.root.resolve(".history"));
        Files.createSymbolicLink(fixture.root.resolve(".history/partners"), outside);

        ToolError error = error(ErrorCode.PRECONDITION_FAILED, () -> history.add(note, ACME));

        assertThat(all(error)).contains(".history/partners").contains("link");
        assertThat(outside).isEmptyDirectory();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aLinkAsTheVersionDirectoryIsRefused(@TempDir Path outside) throws IOException {
        KnowledgeHistory history = history();
        Path note = note("x");
        Files.createDirectories(fixture.root.resolve(".history/partners"));
        Files.createSymbolicLink(versions(), outside);

        error(ErrorCode.PRECONDITION_FAILED, () -> history.add(note, ACME));
        error(ErrorCode.PRECONDITION_FAILED, () -> history.prune(ACME));

        assertThat(outside).isEmptyDirectory();
    }

    @Test
    void aFileInPlaceOfAHistorySegmentIsRefused() throws IOException {
        KnowledgeHistory history = history();
        Path note = note("x");
        Files.createDirectories(fixture.root.resolve(".history"));
        Files.writeString(fixture.root.resolve(".history/partners"), "a file");

        ToolError error = error(ErrorCode.PRECONDITION_FAILED, () -> history.add(note, ACME));

        assertThat(all(error)).contains(".history/partners");
        assertThat(fixture.root.resolve(".history/partners")).hasContent("a file");
    }

    @Test
    void theClockMovingOnStartsTheCounterAgain() throws IOException {
        KnowledgeHistory history = history();
        Path note = note("x");
        history.add(note, ACME);
        clock.advance(Duration.ofNanos(1));

        assertThat(history.add(note, ACME).getFileName())
            .hasToString("20261110T101500.123456790Z-000.md");
    }
}
