package de.dadecker.inubit.mcp.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.Listing;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.NoteFile;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.Skipped;
import de.dadecker.inubit.mcp.application.knowledge.Note.State;
import de.dadecker.inubit.mcp.domain.model.NoteName;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Feature 019 T018: the read side of the knowledge directory (research D-4, D-9). */
class KnowledgeDirectoryTest {

    @TempDir
    Path temp;

    private Path root() throws IOException {
        return Files.createDirectories(temp.resolve("knowledge"));
    }

    private static Path note(Path root, String relative, String text, Instant modified)
        throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }

    private static List<String> names(Listing listing) {
        return listing.files().stream().map(file -> file.name().value()).toList();
    }

    @Test
    void anAbsentDirectoryListsNothing() throws IOException {
        KnowledgeDirectory directory = new KnowledgeDirectory(temp.resolve("missing"));

        Listing listing = directory.list();

        assertThat(listing.present()).isFalse();
        assertThat(listing.files()).isEmpty();
        assertThat(listing.skipped()).isEmpty();
        assertThat(listing.warnings()).isEmpty();
        assertThat(directory.status().notes()).isZero();
        assertThat(temp.resolve("missing")).doesNotExist();
    }

    @Test
    void listsOnlyLowercaseMdFilesNewestFirstThenByName() throws IOException {
        Path root = root();
        Instant older = Instant.parse("2026-01-01T10:00:00Z");
        Instant newer = Instant.parse("2026-02-01T10:00:00Z");
        note(root, "b.md", "b", older);
        note(root, "a.md", "a", older);
        note(root, "systems/sap/rfc.md", "rfc", newer);
        note(root, "upper.MD", "x", newer);
        note(root, "notes.txt", "x", newer);
        note(root, "readme.markdown", "x", newer);

        Listing listing = new KnowledgeDirectory(root).list();

        assertThat(listing.present()).isTrue();
        assertThat(names(listing)).containsExactly("systems/sap/rfc", "a", "b");
        NoteFile rfc = listing.files().getFirst();
        assertThat(rfc.relative()).isEqualTo(Path.of("systems", "sap", "rfc.md"));
        assertThat(rfc.file()).isEqualTo(root.toRealPath().resolve("systems/sap/rfc.md"));
        assertThat(rfc.size()).isEqualTo(3);
        assertThat(rfc.modified().toInstant()).isEqualTo(newer);
        assertThat(listing.skipped()).isEmpty();
        assertThat(listing.warnings()).isEmpty();
    }

    @Test
    void theOrderByNameIsCodePointOrder() throws IOException {
        Path root = root();
        Instant same = Instant.parse("2026-01-01T10:00:00Z");
        // U+FF5E sorts before U+1F600 in UTF-16 order (0xFF5E > 0xD83D), not in code point order
        note(root, "\uD83D\uDE00.md", "x", same);
        note(root, "\uFF5E.md", "x", same);
        note(root, "z.md", "x", same);

        assertThat(names(new KnowledgeDirectory(root).list()))
            .containsExactly("z", "\uFF5E", "\uD83D\uDE00");
    }

    @Test
    void hiddenEntriesAndTheHistoryAreSkippedWithoutWarning() throws IOException {
        Path root = root();
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        note(root, "visible.md", "x", now);
        note(root, ".hidden.md", "x", now);
        note(root, ".history/visible/2026.md", "x", now);
        note(root, "sub/.secret/inner.md", "x", now);
        note(root, ".lock", "", now);

        Listing listing = new KnowledgeDirectory(root).list();

        assertThat(names(listing)).containsExactly("visible");
        assertThat(listing.skipped()).isEmpty();
        assertThat(listing.warnings()).isEmpty();
    }

    @Test
    void aHiddenRootSegmentAboveTheDirectoryDoesNotHideIt() throws IOException {
        Path root = Files.createDirectories(temp.resolve(".inubit-mcp/acme/knowledge"));
        note(root, "a.md", "x", Instant.parse("2026-01-01T10:00:00Z"));

        assertThat(names(new KnowledgeDirectory(root).list())).containsExactly("a");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aFileWithTheWindowsHiddenAttributeIsSkipped() throws IOException {
        Path root = root();
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        note(root, "visible.md", "x", now);
        Path hidden = note(root, "hidden.md", "x", now);
        Files.setAttribute(hidden, "dos:hidden", true);
        Path folder = Files.createDirectories(root.resolve("folder"));
        note(root, "folder/inner.md", "x", now);
        Files.setAttribute(folder, "dos:hidden", true);

        assertThat(names(new KnowledgeDirectory(root).list())).containsExactly("visible");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void symbolicLinksAreNotFollowedButReportedAsSkipped() throws IOException {
        Path root = root();
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        Path outside = Files.createDirectories(temp.resolve("outside"));
        note(outside, "secret.md", "outside", now);
        note(root, "real.md", "x", now);
        Files.createSymbolicLink(root.resolve("linked.md"), outside.resolve("secret.md"));
        Files.createSymbolicLink(root.resolve("folder"), outside);

        Listing listing = new KnowledgeDirectory(root).list();

        assertThat(names(listing)).containsExactly("real");
        assertThat(listing.skipped()).containsExactlyInAnyOrder(
            new Skipped(Path.of("folder"), Skipped.Reason.LINK),
            new Skipped(Path.of("linked.md"), Skipped.Reason.LINK));
        assertThat(new KnowledgeDirectory(root).status().notes()).isEqualTo(1);
    }

    @Test
    void theWalkStopsBelowTheDepthBoundWithAWarning() throws IOException {
        Path root = root();
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        note(root, "a/b/two.md", "x", now);
        note(root, "a/b/c/three.md", "x", now);

        Listing listing = new KnowledgeDirectory(root, 3, 1_000).list();

        assertThat(names(listing)).containsExactly("a/b/two");
        assertThat(listing.warnings()).containsExactly("notes more than 3 levels deep are not"
            + " listed");
    }

    @Test
    void theWalkStopsAfterTheEntryBoundWithAWarning() throws IOException {
        Path root = root();
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            note(root, "n" + i + ".md", "x", now);
        }

        Listing listing = new KnowledgeDirectory(root, 16, 3).list();

        assertThat(listing.files()).hasSize(3);
        assertThat(listing.warnings()).containsExactly("only the first 3 entries of the"
            + " knowledge directory are listed");
    }

    @Test
    void theDefaultBoundsAreThoseOfTheLimits() throws IOException {
        Path root = root();
        Path deep = root;
        for (int i = 0; i < KnowledgeLimits.WALK_DEPTH; i++) {
            deep = deep.resolve("d");
        }
        note(root, "d/x.md", "x", Instant.now());
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("too-deep.md"), "x");

        Listing listing = new KnowledgeDirectory(root).list();

        assertThat(names(listing)).containsExactly("d/x");
        assertThat(listing.warnings()).containsExactly("notes more than 16 levels deep are not"
            + " listed");
    }

    @Test
    void readsTheTextAndTheMetadataOfANote() throws IOException {
        Path root = root();
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        note(root, "a.md", "---\ntitle: 'Ä'\ntopics: [sap]\n---\nbody ü\n", now);
        KnowledgeDirectory directory = new KnowledgeDirectory(root);

        Note note = directory.read(directory.list().files().getFirst());

        assertThat(note.state()).isEqualTo(State.OK);
        assertThat(note.name()).isEqualTo(NoteName.of("a"));
        assertThat(note.text()).contains("---\ntitle: 'Ä'\ntopics: [sap]\n---\nbody ü\n");
        assertThat(note.metadata().title()).contains("Ä");
        assertThat(note.metadata().topics()).containsExactly("sap");
        assertThat(note.body()).isEqualTo("body ü\n");
        assertThat(note.problem()).isEmpty();
        assertThat(note.file().modified().toInstant()).isEqualTo(now);
    }

    @Test
    void aNoteOverTheReadBoundIsListedButNotRead() throws IOException {
        Path root = root();
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        Path big = root.resolve("big.md");
        Files.write(big, new byte[KnowledgeLimits.READ_BYTES + 1]);
        Files.setLastModifiedTime(big, FileTime.from(now));
        note(root, "edge.md", "a".repeat(KnowledgeLimits.READ_BYTES), now);
        KnowledgeDirectory directory = new KnowledgeDirectory(root);
        Listing listing = directory.list();

        assertThat(names(listing)).containsExactly("big", "edge");
        Note tooLarge = directory.read(listing.files().getFirst());
        assertThat(tooLarge.state()).isEqualTo(State.TOO_LARGE);
        assertThat(tooLarge.text()).isEmpty();
        assertThat(tooLarge.metadata()).isEqualTo(NoteMetadata.NONE);
        assertThat(tooLarge.problem().orElseThrow()).contains("larger than 4 MiB");
        assertThat(directory.read(listing.files().get(1)).state()).isEqualTo(State.OK);
    }

    @Test
    void aNoteThatGrewOverTheBoundAfterTheWalkIsTooLarge() throws IOException {
        Path root = root();
        Path file = note(root, "a.md", "x", Instant.parse("2026-01-01T10:00:00Z"));
        KnowledgeDirectory directory = new KnowledgeDirectory(root);
        NoteFile listed = directory.list().files().getFirst();
        Files.write(file, new byte[KnowledgeLimits.READ_BYTES + 1]);

        assertThat(directory.read(listed).state()).isEqualTo(State.TOO_LARGE);
    }

    @Test
    void aNoteThatIsNoUtf8IsUnreadable() throws IOException {
        Path root = root();
        Path file = root.resolve("latin.md");
        Files.write(file, "Störung".getBytes(StandardCharsets.ISO_8859_1));
        KnowledgeDirectory directory = new KnowledgeDirectory(root);

        Note note = directory.read(directory.list().files().getFirst());

        assertThat(note.state()).isEqualTo(State.UNREADABLE);
        assertThat(note.text()).isEmpty();
        assertThat(note.problem().orElseThrow()).contains("not valid UTF-8");
    }

    @Test
    void aNoteThatVanishedAfterTheWalkIsUnreadable() throws IOException {
        Path root = root();
        Path file = note(root, "a.md", "x", Instant.parse("2026-01-01T10:00:00Z"));
        KnowledgeDirectory directory = new KnowledgeDirectory(root);
        NoteFile listed = directory.list().files().getFirst();
        Files.delete(file);

        Note note = directory.read(listed);

        assertThat(note.state()).isEqualTo(State.UNREADABLE);
        assertThat(note.problem().orElseThrow()).contains("cannot be read");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aNoteReplacedByALinkAfterTheWalkIsNotFollowed() throws IOException {
        Path root = root();
        Path file = note(root, "a.md", "x", Instant.parse("2026-01-01T10:00:00Z"));
        Path outside = Files.writeString(temp.resolve("outside.md"), "outside");
        KnowledgeDirectory directory = new KnowledgeDirectory(root);
        NoteFile listed = directory.list().files().getFirst();
        Files.delete(file);
        Files.createSymbolicLink(file, outside);

        Note note = directory.read(listed);

        assertThat(note.state()).isEqualTo(State.UNREADABLE);
        assertThat(note.text()).isEmpty();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anUnreadableNoteIsListedAndUnreadable() throws IOException {
        Path root = root();
        Path file = note(root, "locked.md", "x", Instant.parse("2026-01-01T10:00:00Z"));
        assumeTrue(file.toFile().setReadable(false, false) && !Files.isReadable(file),
            "the file system honours the read bit (not running as root)");
        KnowledgeDirectory directory = new KnowledgeDirectory(root);

        Listing listing = directory.list();

        assertThat(names(listing)).containsExactly("locked");
        assertThat(directory.read(listing.files().getFirst()).state())
            .isEqualTo(State.UNREADABLE);
        assertThat(directory.status().notes()).isEqualTo(1);
    }

    @Test
    void bothFilesOfNamesEqualAfterNfcAreListed() throws IOException {
        Path root = root();
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        String nfc = Normalizer.normalize("Störung", Normalizer.Form.NFC);
        String nfd = Normalizer.normalize("Störung", Normalizer.Form.NFD);
        note(root, nfc + ".md", "nfc", now);
        Path second = root.resolve(nfd + ".md");
        try {
            Files.writeString(second, "nfd");
        } catch (IOException e) {
            assumeTrue(false, "the file system refuses the second form");
        }
        try (Stream<Path> files = Files.list(root)) {
            assumeTrue(files.count() == 2, "the file system keeps both forms apart");
        }
        Files.setLastModifiedTime(second, FileTime.from(now));

        Listing listing = new KnowledgeDirectory(root).list();

        assertThat(names(listing)).containsExactly(nfc, nfc);
        assertThat(listing.files()).extracting(NoteFile::relative)
            .containsExactly(Path.of(nfd + ".md"), Path.of(nfc + ".md"));
    }

    @Test
    void theStatusCountsTheNotesWithoutReadingThem() throws IOException {
        Path root = root();
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        note(root, "a.md", "x", now);
        note(root, "sub/b.md", "x", now);
        note(root, ".hidden.md", "x", now);
        Files.write(root.resolve("big.md"), new byte[KnowledgeLimits.READ_BYTES + 1]);

        assertThat(new KnowledgeDirectory(root).status().notes()).isEqualTo(3);
    }

    @Test
    void aRootThatIsAFileIsAnError() throws IOException {
        Path file = Files.writeString(temp.resolve("file"), "x");

        assertThatIOException().isThrownBy(() -> new KnowledgeDirectory(file).list());
    }
}
