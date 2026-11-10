package de.dadecker.inubit.mcp.application.knowledge;

import de.dadecker.inubit.mcp.domain.model.NoteName;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The knowledge directory of a profile on disk (feature 019, research D-4, D-9). Every call walks
 * the directory again (FR-009): links inside it are not followed, hidden entries (a segment that
 * starts with {@code .}, which includes {@code .history}, or that {@link Files#isHidden} reports)
 * are skipped, and the walk stops at {@link KnowledgeLimits#WALK_DEPTH} levels and
 * {@link KnowledgeLimits#WALK_ENTRIES} entries. The root itself may be a link (D-2): the walk runs
 * on its real path.
 *
 * <p>Nothing here masks: names of skipped entries go to the caller, which masks them.
 */
public final class KnowledgeDirectory {

    private final Path root;
    private final int maxDepth;
    private final int maxEntries;

    public KnowledgeDirectory(Path root) {
        this(root, KnowledgeLimits.WALK_DEPTH, KnowledgeLimits.WALK_ENTRIES);
    }

    KnowledgeDirectory(Path root, int maxDepth, int maxEntries) {
        this.root = Objects.requireNonNull(root, "root");
        this.maxDepth = maxDepth;
        this.maxEntries = maxEntries;
    }

    /** The configured directory. */
    public Path root() {
        return root;
    }

    /**
     * A file the walk found to be a note.
     *
     * @param relative the path below the real root, as on disk
     * @param file     the file below the real root
     */
    public record NoteFile(NoteName name, Path relative, Path file, long size,
        FileTime modified) {

        public NoteFile {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(relative, "relative");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(modified, "modified");
        }
    }

    /**
     * An entry the walk left out with a directory-level warning.
     *
     * @param relative the path below the real root (its name is masked by the caller)
     */
    public record Skipped(Path relative, Reason reason) {

        /** Why the entry was left out. */
        public enum Reason {
            /** A symbolic link (file or directory); not followed. */
            LINK,
            /** A directory or file whose attributes could not be read. */
            UNREADABLE
        }

        public Skipped {
            Objects.requireNonNull(relative, "relative");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * One walk.
     *
     * @param present  whether the directory exists
     * @param files    the notes, newest first, then by name and raw path in code point order
     * @param skipped  links and unreadable entries, in walk order
     * @param warnings the walk bounds reached; no name in them
     */
    public record Listing(boolean present, List<NoteFile> files, List<Skipped> skipped,
        List<String> warnings) {

        public Listing {
            files = List.copyOf(files);
            skipped = List.copyOf(skipped);
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * What {@code --check-config} shows (research D-3).
     *
     * @param notes the notes {@link #list} finds
     */
    public record Status(int notes) {
    }

    /**
     * The notes in the directory; an empty listing if it does not exist.
     *
     * @throws IOException if it exists but is no directory or cannot be walked
     */
    public Listing list() throws IOException {
        if (!Files.exists(root)) {
            return new Listing(false, List.of(), List.of(), List.of());
        }
        Path real = root.toRealPath();
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new NotDirectoryException(root.toString());
        }
        Walk walk = new Walk(real);
        Files.walkFileTree(real, EnumSet.noneOf(FileVisitOption.class), maxDepth,
            walk);
        if (walk.rootFailure != null) {
            throw walk.rootFailure;
        }
        List<NoteFile> files = new ArrayList<>(walk.files);
        files.sort(Comparator.comparing(NoteFile::modified, Comparator.reverseOrder())
            .thenComparing(file -> file.name().value(), KnowledgeDirectory::byCodePoint)
            .thenComparing(file -> file.relative().toString(), KnowledgeDirectory::byCodePoint));
        return new Listing(true, files, walk.skipped, walk.warnings);
    }

    /** The number of notes, without reading any of them (and so no credential). */
    public Status status() throws IOException {
        return new Status(list().files().size());
    }

    /** The text of {@code file}; never throws: a note that cannot be read says why. */
    public Note read(NoteFile file) {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file.file(), BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return Note.notRead(file, Note.State.UNREADABLE, cannotRead(e));
        }
        if (!attributes.isRegularFile()) {
            return Note.notRead(file, Note.State.UNREADABLE, "the note is no longer a regular"
                + " file");
        }
        NoteFile current = new NoteFile(file.name(), file.relative(), file.file(),
            attributes.size(), attributes.lastModifiedTime());
        if (attributes.size() > KnowledgeLimits.READ_BYTES) {
            return tooLarge(current);
        }
        byte[] bytes;
        try (SeekableByteChannel channel = Files.newByteChannel(file.file(),
            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
            InputStream in = Channels.newInputStream(channel)) {
            bytes = in.readNBytes(KnowledgeLimits.READ_BYTES + 1);
        } catch (IOException e) {
            return Note.notRead(current, Note.State.UNREADABLE, cannotRead(e));
        }
        if (bytes.length > KnowledgeLimits.READ_BYTES) {
            return tooLarge(current);
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            return Note.ok(current, text);
        } catch (CharacterCodingException e) {
            return Note.notRead(current, Note.State.UNREADABLE, "the note is not valid UTF-8");
        }
    }

    private static Note tooLarge(NoteFile file) {
        return Note.notRead(file, Note.State.TOO_LARGE, "the note is larger than "
            + KnowledgeLimits.READ_BYTES / (1024 * 1024) + " MiB, the bound for reading");
    }

    private static String cannotRead(IOException e) {
        String cause = e instanceof NoSuchFileException ? "it no longer exists"
            : e.getClass().getSimpleName();
        return "the note cannot be read (" + cause + ")";
    }

    private static int byCodePoint(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int x = a.codePointAt(i);
            int y = b.codePointAt(j);
            if (x != y) {
                return Integer.compare(x, y);
            }
            i += Character.charCount(x);
            j += Character.charCount(y);
        }
        return Boolean.compare(i < a.length(), j < b.length());
    }

    /** The visitor of one walk. */
    private final class Walk extends SimpleFileVisitor<Path> {

        private final Path real;
        private final List<NoteFile> files = new ArrayList<>();
        private final List<Skipped> skipped = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private int entries;
        private boolean tooDeep;
        private IOException rootFailure;

        Walk(Path real) {
            this.real = real;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes)
            throws IOException {
            if (dir.equals(real)) {
                return FileVisitResult.CONTINUE;
            }
            if (!counted()) {
                return FileVisitResult.TERMINATE;
            }
            return hidden(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
            throws IOException {
            if (!counted()) {
                return FileVisitResult.TERMINATE;
            }
            if (hidden(file)) {
                return FileVisitResult.CONTINUE;
            }
            Path relative = real.relativize(file);
            if (attributes.isSymbolicLink()) {
                skipped.add(new Skipped(relative, Skipped.Reason.LINK));
            } else if (attributes.isDirectory()) {
                // a directory at the depth bound: its content is not walked
                if (!tooDeep) {
                    tooDeep = true;
                    warnings.add("notes more than " + maxDepth + " levels deep are not listed");
                }
            } else if (attributes.isRegularFile() && isNoteFileName(file)) {
                files.add(new NoteFile(NoteName.ofRelativePath(relative), relative, file,
                    attributes.size(), attributes.lastModifiedTime()));
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException failure) {
            if (file.equals(real)) {
                rootFailure = failure;
                return FileVisitResult.TERMINATE;
            }
            if (!counted()) {
                return FileVisitResult.TERMINATE;
            }
            if (!hidden(file)) {
                skipped.add(new Skipped(real.relativize(file), Skipped.Reason.UNREADABLE));
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException failure) {
            if (failure != null && !dir.equals(real)) {
                skipped.add(new Skipped(real.relativize(dir), Skipped.Reason.UNREADABLE));
            } else if (failure != null) {
                rootFailure = failure;
            }
            return FileVisitResult.CONTINUE;
        }

        /** Counts one entry; false (with the warning) once the bound is exceeded. */
        private boolean counted() {
            entries++;
            if (entries > maxEntries) {
                warnings.add("only the first " + maxEntries + " entries of the knowledge"
                    + " directory are listed");
                return false;
            }
            return true;
        }

        private static boolean isNoteFileName(Path file) {
            String name = file.getFileName().toString();
            return name.endsWith(NoteName.SUFFIX) && name.length() > NoteName.SUFFIX.length();
        }

        private static boolean hidden(Path entry) {
            if (entry.getFileName().toString().startsWith(".")) {
                return true;
            }
            try {
                return Files.isHidden(entry);
            } catch (IOException e) {
                return false;
            }
        }
    }
}
