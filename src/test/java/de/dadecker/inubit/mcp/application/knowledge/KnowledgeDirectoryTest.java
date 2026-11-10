package de.dadecker.inubit.mcp.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.Listing;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.NoteFile;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.Skipped;
import de.dadecker.inubit.mcp.application.knowledge.Note.State;
import de.dadecker.inubit.mcp.domain.model.NoteName;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.Occupied;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.WriteRefusal;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.WindowsAcl;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 019 T018: the read side of the knowledge directory (research D-4, D-9); T028: the write
 * side (D-3, D-10, D-12) - occupancy, atomic publication, owner-only creation.
 */
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

    // T028: the write side

    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-11-10T10:15:00Z"), ZoneOffset.UTC);
    private static final byte[] TEXT = "text\n".getBytes(StandardCharsets.UTF_8);

    private static KnowledgeDirectory writable(Path root) {
        return new KnowledgeDirectory(root, PrivateFiles.forHost(), CLOCK);
    }

    private static String posix(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    private static List<String> entries(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    private static void assertRefused(ThrowingAction action, WriteRefusal.Kind kind) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(WriteRefusal.class,
            e -> assertThat(e.kind()).isEqualTo(kind));
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theFirstNoteCreatesTheAbsentRootItsParentsAndTheNoteOwnerOnly() throws Exception {
        Path root = temp.resolve("a").resolve("b").resolve("knowledge");
        KnowledgeDirectory directory = writable(root);

        Path real = directory.writeRoot();
        Path note = directory.publishNew(NoteName.of("partners/acme"), TEXT);

        assertThat(real).isEqualTo(root.toRealPath());
        assertThat(note).isEqualTo(real.resolve("partners").resolve("acme.md"));
        assertThat(Files.readString(note)).isEqualTo("text\n");
        assertThat(posix(temp.resolve("a"))).isEqualTo("rwx------");
        assertThat(posix(root)).isEqualTo("rwx------");
        assertThat(posix(root.resolve("partners"))).isEqualTo("rwx------");
        assertThat(posix(note)).isEqualTo("rw-------");
        assertThat(entries(root.resolve("partners"))).containsExactly("acme.md");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theFirstNoteCreatesTheAbsentRootItsParentsAndTheNotePrivateOnWindows()
        throws Exception {
        Path root = temp.resolve("a").resolve("knowledge");
        KnowledgeDirectory directory = writable(root);

        directory.writeRoot();
        Path note = directory.publishNew(NoteName.of("partners/acme"), TEXT);

        WindowsAcl.assertPrivate(root, WindowsAcl.DIRECTORY_INHERITANCE);
        WindowsAcl.assertPrivate(root.resolve("partners"), WindowsAcl.DIRECTORY_INHERITANCE);
        WindowsAcl.assertPrivate(note, "None");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void existingDirectoriesKeepTheirPermissions() throws Exception {
        Path root = root();
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-x---"));
        Path partners = Files.createDirectory(root.resolve("partners"));
        Files.setPosixFilePermissions(partners, PosixFilePermissions.fromString("rwxr-x---"));
        KnowledgeDirectory directory = writable(root);

        directory.writeRoot();
        directory.publishNew(NoteName.of("partners/acme"), TEXT);

        assertThat(posix(root)).isEqualTo("rwxr-x---");
        assertThat(posix(partners)).isEqualTo("rwxr-x---");
    }

    @Test
    void aFreeTargetHasNoOccupantAlsoWhenTheRootIsAbsent() throws Exception {
        assertThat(writable(temp.resolve("missing")).checkFree(NoteName.of("a/b"))).isEmpty();
        Path root = root();
        Files.createDirectories(root.resolve("partners"));
        Files.writeString(root.resolve("partners").resolve("other.md"), "x");

        assertThat(writable(root).checkFree(NoteName.of("partners/acme"))).isEmpty();
        assertThat(writable(root).checkFree(NoteName.of("partners/new/acme"))).isEmpty();
    }

    @Test
    void aFileWithAnotherCaseOfTheSuffixOccupiesTheTarget() throws Exception {
        Path root = root();
        Files.createDirectories(root.resolve("partners"));
        Files.writeString(root.resolve("partners").resolve("acme.MD"), "by hand");

        Optional<Occupied> occupied = writable(root).checkFree(NoteName.of("partners/acme"));

        assertThat(occupied).hasValueSatisfying(o -> {
            assertThat(o.relative()).isEqualTo(Path.of("partners", "acme.MD"));
            assertThat(o.what()).isEqualTo("differs from the note path only in case");
        });
    }

    @Test
    void aDirectoryInPlaceOfTheNoteOccupiesTheTarget() throws Exception {
        Path root = root();
        Files.createDirectories(root.resolve("partners").resolve("acme.md"));

        assertThat(writable(root).checkFree(NoteName.of("partners/acme")))
            .contains(new Occupied(Path.of("partners", "acme.md"), "already exists"));
    }

    @Test
    void aFileInPlaceOfAParentSegmentOccupiesTheTarget() throws Exception {
        Path root = root();
        Files.writeString(root.resolve("partners"), "x");

        assertThat(writable(root).checkFree(NoteName.of("partners/acme")))
            .contains(new Occupied(Path.of("partners"), "is not a directory"));
        assertRefused(() -> writable(root).publishNew(NoteName.of("partners/acme"), TEXT),
            WriteRefusal.Kind.OCCUPIED);
    }

    @Test
    void aParentSegmentInAnotherCaseOccupiesTheTarget() throws Exception {
        Path root = root();
        Files.createDirectories(root.resolve("Partners"));

        assertThat(writable(root).checkFree(NoteName.of("partners/acme")))
            .contains(new Occupied(Path.of("Partners"),
                "differs from the note path only in case"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aLinkAsParentSegmentOccupiesTheTargetAndNothingIsWrittenThroughIt() throws Exception {
        Path root = root();
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.createSymbolicLink(root.resolve("partners"), outside);
        KnowledgeDirectory directory = writable(root);

        assertThat(directory.checkFree(NoteName.of("partners/acme")))
            .contains(new Occupied(Path.of("partners"), "is a symbolic link"));
        assertRefused(() -> directory.publishNew(NoteName.of("partners/acme"), TEXT),
            WriteRefusal.Kind.OCCUPIED);
        assertThat(entries(outside)).isEmpty();
    }

    @Test
    void aNoteCreatedBetweenCheckAndPublicationIsNeverReplaced() throws Exception {
        Path root = root();
        KnowledgeDirectory directory = writable(root);
        Path target = root.resolve("acme.md");
        directory.beforePublish(() -> {
            try {
                Files.writeString(target, "by hand");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(directory.checkFree(NoteName.of("acme"))).isEmpty();
        assertRefused(() -> directory.publishNew(NoteName.of("acme"), TEXT),
            WriteRefusal.Kind.EXISTS);

        assertThat(Files.readString(target)).isEqualTo("by hand");
        assertThat(entries(root)).containsExactly("acme.md");
    }

    @Test
    void aTemporaryFileThatCannotBeRemovedDoesNotHideThatTheNoteExists() throws Exception {
        Path root = root();
        KnowledgeDirectory directory = writable(root);
        directory.publisher(new KnowledgeDirectory.Publisher() {
            @Override
            public void link(Path target, Path temporary) throws IOException {
                Files.writeString(target, "written meanwhile");
                Files.delete(temporary);
                Files.createDirectories(temporary.resolve("in-the-way"));
                throw new FileAlreadyExistsException(target.toString());
            }

            @Override
            public void move(Path temporary, Path target) {
                throw new AssertionError("not used");
            }
        });

        assertRefused(() -> directory.publishNew(NoteName.of("acme"), TEXT),
            WriteRefusal.Kind.EXISTS);
        assertThat(Files.readString(root.resolve("acme.md"))).isEqualTo("written meanwhile");
    }

    @Test
    void aFileSystemWithoutHardLinksRefusesToCreateAndLeavesNothingBehind() throws Exception {
        Path root = root();
        KnowledgeDirectory directory = writable(root);
        directory.publisher(new KnowledgeDirectory.Publisher() {
            @Override
            public void link(Path target, Path temporary) throws IOException {
                throw new FileSystemException(target.toString(), null, "not supported");
            }

            @Override
            public void move(Path temporary, Path target) throws IOException {
                throw new AssertionError("not used");
            }
        });

        assertRefused(() -> directory.publishNew(NoteName.of("acme"), TEXT),
            WriteRefusal.Kind.NOT_ATOMIC);
        assertThat(entries(root)).isEmpty();
    }

    @Test
    void aFileSystemWithoutAtomicMovesRefusesToReplaceAndKeepsTheNote() throws Exception {
        Path root = root();
        Files.writeString(root.resolve("acme.md"), "old");
        KnowledgeDirectory directory = writable(root);
        directory.publisher(new KnowledgeDirectory.Publisher() {
            @Override
            public void link(Path target, Path temporary) {
                throw new AssertionError("not used");
            }

            @Override
            public void move(Path temporary, Path target) throws IOException {
                throw new AtomicMoveNotSupportedException(temporary.toString(),
                    target.toString(), "not supported");
            }
        });

        assertRefused(() -> directory.publishReplace(Path.of("acme.md"), TEXT),
            WriteRefusal.Kind.NOT_ATOMIC);
        assertThat(Files.readString(root.resolve("acme.md"))).isEqualTo("old");
        assertThat(entries(root)).containsExactly("acme.md");
    }

    @Test
    void replacingSwapsTheNoteAndLeavesNoTemporaryFile() throws Exception {
        Path root = root();
        Files.createDirectories(root.resolve("partners"));
        Files.writeString(root.resolve("partners").resolve("acme.md"), "old");
        KnowledgeDirectory directory = writable(root);

        assertThat(directory.checkExisting(Path.of("partners", "acme.md"))).isEmpty();
        Path note = directory.publishReplace(Path.of("partners", "acme.md"), TEXT);

        assertThat(note).isEqualTo(root.toRealPath().resolve("partners").resolve("acme.md"));
        assertThat(Files.readString(note)).isEqualTo("text\n");
        assertThat(entries(root.resolve("partners"))).containsExactly("acme.md");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aReplacedNoteIsOwnerOnly() throws Exception {
        Path root = root();
        Path old = Files.writeString(root.resolve("acme.md"), "old");
        Files.setPosixFilePermissions(old, PosixFilePermissions.fromString("rw-r--r--"));

        Path note = writable(root).publishReplace(Path.of("acme.md"), TEXT);

        assertThat(posix(note)).isEqualTo("rw-------");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aLinkOnTheWayToAnExistingNoteIsReported() throws Exception {
        Path root = root();
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path target = Files.writeString(outside.resolve("acme.md"), "outside");
        Files.createSymbolicLink(root.resolve("partners"), outside);
        Files.createSymbolicLink(root.resolve("acme.md"), target);
        KnowledgeDirectory directory = writable(root);

        assertThat(directory.checkExisting(Path.of("partners", "acme.md")))
            .contains(new Occupied(Path.of("partners"), "is a symbolic link"));
        assertThat(directory.checkExisting(Path.of("acme.md")))
            .contains(new Occupied(Path.of("acme.md"), "is a symbolic link"));
        assertRefused(() -> directory.publishReplace(Path.of("acme.md"), TEXT),
            WriteRefusal.Kind.OCCUPIED);
        assertThat(Files.readString(target)).isEqualTo("outside");
        assertThat(Files.isSymbolicLink(root.resolve("acme.md"))).isTrue();
    }

    @Test
    void aNoteThatIsNoLongerThereIsReported() throws Exception {
        Path root = root();

        assertThat(writable(root).checkExisting(Path.of("acme.md")))
            .contains(new Occupied(Path.of("acme.md"), "no longer exists"));
    }

    @Test
    void aDirectoryWithoutTheWriteSideRefusesToWrite() throws Exception {
        Path root = root();

        assertThatThrownBy(() -> new KnowledgeDirectory(root).publishNew(NoteName.of("a"), TEXT))
            .isInstanceOf(IllegalStateException.class);
        assertThat(entries(root)).isEmpty();
    }
}
