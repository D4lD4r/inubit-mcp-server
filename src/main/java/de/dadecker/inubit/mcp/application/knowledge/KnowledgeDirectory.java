package de.dadecker.inubit.mcp.application.knowledge;

import de.dadecker.inubit.mcp.domain.model.NoteName;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The knowledge directory of a profile on disk (feature 019, research D-4, D-9). Every call walks
 * the directory again (FR-009): links inside it are not followed, hidden entries (a segment that
 * starts with {@code .}, which includes {@code .history}, or that {@link Files#isHidden} reports)
 * are skipped, and the walk stops at {@link KnowledgeLimits#WALK_DEPTH} levels and
 * {@link KnowledgeLimits#WALK_ENTRIES} entries. The root itself may be a link (D-2): the walk runs
 * on its real path.
 *
 * <p>The write side (D-3, D-10, D-12) needs the {@link PrivateFileSystem} and a clock: it checks
 * every segment on the way without following links (also case-insensitively against the
 * directory listing), creates missing directories one segment at a time owner-only and publishes
 * a note atomically from an owner-only temporary file {@code .<file name>.<uuid>.tmp} in the
 * target directory - a new note by a hard link, which never replaces a file that appeared
 * meanwhile, an existing one by an atomic move. There is no non-atomic fallback.
 *
 * <p>Nothing here masks: names of skipped entries and occupied paths go to the caller, which
 * masks them.
 */
public final class KnowledgeDirectory {

    /** Temporary files of a crashed write older than this are removed on the next write. */
    static final Duration STALE_TEMPORARY = Duration.ofHours(1);

    private static final Pattern TEMPORARY = Pattern.compile(
        "\\..+\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tmp");

    private final Path root;
    private final PrivateFileSystem files;
    private final Clock clock;
    private final int maxDepth;
    private final int maxEntries;
    private final int measureEntries;
    private Runnable beforePublish = () -> { };
    private Publisher publisher = Publisher.FILES;

    /** The read side only; every write throws {@link IllegalStateException}. */
    public KnowledgeDirectory(Path root) {
        this(root, KnowledgeLimits.WALK_DEPTH, KnowledgeLimits.WALK_ENTRIES);
    }

    /**
     * Read and write side.
     *
     * @param files creates directories, temporary files and the root owner-only
     * @param clock the age of temporary files left by a crashed write
     */
    public KnowledgeDirectory(Path root, PrivateFileSystem files, Clock clock) {
        this(root, Objects.requireNonNull(files, "files"), Objects.requireNonNull(clock, "clock"),
            KnowledgeLimits.WALK_DEPTH, KnowledgeLimits.WALK_ENTRIES,
            KnowledgeLimits.SIZE_WALK_ENTRIES);
    }

    KnowledgeDirectory(Path root, int maxDepth, int maxEntries) {
        this(root, null, null, maxDepth, maxEntries, KnowledgeLimits.SIZE_WALK_ENTRIES);
    }

    KnowledgeDirectory(Path root, PrivateFileSystem files, Clock clock, int maxDepth,
        int maxEntries, int measureEntries) {
        this.root = Objects.requireNonNull(root, "root");
        this.files = files;
        this.clock = clock;
        this.maxDepth = maxDepth;
        this.maxEntries = maxEntries;
        this.measureEntries = measureEntries;
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

    /**
     * A path on the way to a note that is in the way.
     *
     * @param relative the path below the real root, as on disk (masked by the caller)
     * @param what     what is wrong with it, e.g. {@code is a symbolic link}; no name in it
     */
    public record Occupied(Path relative, String what) {

        public Occupied {
            Objects.requireNonNull(relative, "relative");
            Objects.requireNonNull(what, "what");
        }
    }

    /** A write the directory refused; nothing was published. */
    public static final class WriteRefusal extends Exception {

        private static final long serialVersionUID = 1L;

        /** Why. */
        public enum Kind {
            /** A path on the way is a link, of the wrong type or differs only in case. */
            OCCUPIED,
            /** A file appeared at the target of a new note; it was not replaced. */
            EXISTS,
            /** The file system cannot create a hard link or move atomically. */
            NOT_ATOMIC,
            /** More entries than {@link KnowledgeLimits#SIZE_WALK_ENTRIES} to measure. */
            TOO_LARGE_TO_MEASURE
        }

        private final Kind kind;
        private final transient Occupied occupied;

        WriteRefusal(Kind kind, Occupied occupied, String message) {
            super(message);
            this.kind = Objects.requireNonNull(kind, "kind");
            this.occupied = occupied;
        }

        public Kind kind() {
            return kind;
        }

        /** The path in the way ({@link Kind#OCCUPIED}, {@link Kind#EXISTS}). */
        public Optional<Occupied> occupied() {
            return Optional.ofNullable(occupied);
        }
    }

    /** How a temporary file becomes the note; replaced in tests. */
    interface Publisher {

        Publisher FILES = new Publisher() {
            @Override
            public void link(Path target, Path temporary) throws IOException {
                Files.createLink(target, temporary);
            }

            @Override
            public void move(Path temporary, Path target) throws IOException {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            }
        };

        /** A hard link; fails if {@code target} exists. */
        void link(Path target, Path temporary) throws IOException;

        /** An atomic move over {@code target}. */
        void move(Path temporary, Path target) throws IOException;
    }

    /** Runs between writing the temporary file and publishing it (tests: the race). */
    void beforePublish(Runnable hook) {
        this.beforePublish = Objects.requireNonNull(hook, "hook");
    }

    void publisher(Publisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    /**
     * Creates the directory owner-only with its missing parents if it is absent (D-3) and returns
     * its real path; called before the lock, which lives in it.
     *
     * @throws NotDirectoryException if it exists but is no directory
     */
    public Path writeRoot() throws IOException {
        requireWriteSide();
        if (!Files.exists(root)) {
            files.createDirectories(root);
        }
        Path real = root.toRealPath();
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new NotDirectoryException(root.toString());
        }
        return real;
    }

    /**
     * What is in the way of a new note {@code name}: a parent segment that is a link or no
     * directory, the note path taken by anything, or an entry that differs from a segment only in
     * case ({@link NoteName#fold}); empty when the path is free (missing segments are free).
     */
    public Optional<Occupied> checkFree(NoteName name) throws IOException {
        if (!Files.exists(root)) {
            return Optional.empty();
        }
        Path real = root.toRealPath();
        Path relative = null;
        List<String> segments = segments(name);
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            Path parent = relative == null ? real : real.resolve(relative);
            Optional<String> entry = entry(parent, segment);
            if (entry.isEmpty()) {
                return Optional.empty();
            }
            relative = relative == null ? Path.of(entry.get()) : relative.resolve(entry.get());
            if (!entry.get().equals(segment)) {
                return Optional.of(new Occupied(relative,
                    "differs from the note path only in case"));
            }
            boolean last = i == segments.size() - 1;
            Optional<String> problem = last ? Optional.of("already exists")
                : notADirectory(real.resolve(relative));
            if (problem.isPresent()) {
                return Optional.of(new Occupied(relative, problem.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * What is in the way of writing the existing note at {@code relative} (from a listing): a
     * parent segment that is a link or no directory, or the note itself no regular file.
     */
    public Optional<Occupied> checkExisting(Path relative) throws IOException {
        Path real = root.toRealPath();
        Path current = null;
        for (int i = 0; i < relative.getNameCount(); i++) {
            current = current == null ? relative.getName(0) : current.resolve(relative.getName(i));
            Path path = real.resolve(current);
            boolean last = i == relative.getNameCount() - 1;
            if (Files.isSymbolicLink(path)) {
                return Optional.of(new Occupied(current, "is a symbolic link"));
            }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(new Occupied(current, "no longer exists"));
            }
            if (last && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(new Occupied(current, "is not a regular file"));
            }
            if (!last && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(new Occupied(current, "is not a directory"));
            }
        }
        return Optional.empty();
    }

    /**
     * Publishes the new note {@code name}: missing parent segments are created one at a time,
     * owner-only; the text goes to an owner-only temporary file, which is then hard-linked to the
     * note path - a file there by then is never replaced.
     *
     * @return the note file
     * @throws WriteRefusal {@code OCCUPIED} (a segment is in the way), {@code EXISTS} (a file
     *     appeared at the note path), {@code NOT_ATOMIC}; nothing was published
     */
    public Path publishNew(NoteName name, byte[] text) throws IOException, WriteRefusal {
        requireWriteSide();
        Path real = root.toRealPath();
        List<String> segments = segments(name);
        Path parent = real;
        for (String segment : segments.subList(0, segments.size() - 1)) {
            parent = parent.resolve(segment);
            if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    files.createDirectory(parent);
                } catch (FileAlreadyExistsException e) {
                    // created meanwhile, or a link that appeared: checked below
                }
            }
            Optional<String> problem = notADirectory(parent);
            if (problem.isPresent()) {
                Occupied occupied = new Occupied(real.relativize(parent), problem.get());
                throw new WriteRefusal(WriteRefusal.Kind.OCCUPIED, occupied, occupied.what());
            }
        }
        Path target = parent.resolve(segments.getLast());
        Path temporary = temporary(target, text);
        try {
            beforePublish.run();
            publisher.link(target, temporary);
        } catch (FileAlreadyExistsException e) {
            removeQuietly(temporary);
            throw new WriteRefusal(WriteRefusal.Kind.EXISTS,
                new Occupied(real.relativize(target), "already exists"), "already exists");
        } catch (FileSystemException | UnsupportedOperationException e) {
            removeQuietly(temporary);
            throw notAtomic(e);
        } catch (IOException | RuntimeException e) {
            removeQuietly(temporary);
            throw e;
        }
        // after the commit point: a temporary file left behind is removed by a later size walk
        removeQuietly(temporary);
        return target;
    }

    /**
     * Replaces the note at {@code relative} atomically with {@code text} from an owner-only
     * temporary file.
     *
     * @return the note file
     * @throws WriteRefusal {@code OCCUPIED} (see {@link #checkExisting}), {@code NOT_ATOMIC}; the
     *     note was not changed
     */
    public Path publishReplace(Path relative, byte[] text) throws IOException, WriteRefusal {
        requireWriteSide();
        Optional<Occupied> occupied = checkExisting(relative);
        if (occupied.isPresent()) {
            throw new WriteRefusal(WriteRefusal.Kind.OCCUPIED, occupied.get(),
                occupied.get().what());
        }
        Path target = root.toRealPath().resolve(relative);
        Path temporary = temporary(target, text);
        boolean moved = false;
        try {
            beforePublish.run();
            publisher.move(temporary, target);
            moved = true;
        } catch (AtomicMoveNotSupportedException | UnsupportedOperationException e) {
            throw notAtomic(e);
        } finally {
            if (!moved) {
                removeQuietly(temporary);
            }
        }
        return target;
    }

    /** A failure to remove a temporary file never hides the outcome of the write. */
    private static void removeQuietly(Path temporary) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException e) {
            // left for the size walk, which removes stale temporary files
        }
    }

    /**
     * The bytes of every regular file in the directory - notes, other files and the history - for
     * the {@link KnowledgeLimits#TOTAL_BYTES} bound; the lock file is left out, links are not
     * followed and there is no depth limit. Temporary files of a crashed write older than
     * {@link #STALE_TEMPORARY} are removed on the way and not counted.
     *
     * @throws WriteRefusal {@code TOO_LARGE_TO_MEASURE} after more entries than the measure bound
     */
    public long totalBytes() throws IOException, WriteRefusal {
        requireWriteSide();
        if (!Files.exists(root)) {
            return 0;
        }
        Path real = root.toRealPath();
        Path lock = real.resolve(KnowledgeLock.FILE);
        Path history = real.resolve(KnowledgeHistory.DIRECTORY);
        Instant stale = clock.instant().minus(STALE_TEMPORARY);
        long[] total = {0};
        int[] entries = {0};
        boolean[] tooMany = {false};
        Files.walkFileTree(real, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
            new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    return count(dir) ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                    if (!count(file)) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!attributes.isRegularFile() || file.equals(lock)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!file.startsWith(history)
                        && TEMPORARY.matcher(file.getFileName().toString()).matches()
                        && attributes.lastModifiedTime().toInstant().isBefore(stale)) {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }
                    total[0] += attributes.size();
                    return FileVisitResult.CONTINUE;
                }

                private boolean count(Path path) {
                    if (path.equals(real)) {
                        return true;
                    }
                    entries[0]++;
                    if (entries[0] > measureEntries) {
                        tooMany[0] = true;
                        return false;
                    }
                    return true;
                }
            });
        if (tooMany[0]) {
            throw new WriteRefusal(WriteRefusal.Kind.TOO_LARGE_TO_MEASURE, null,
                "more than " + measureEntries + " entries");
        }
        return total[0];
    }

    private void requireWriteSide() {
        if (files == null) {
            throw new IllegalStateException("the knowledge directory was opened for reading only");
        }
    }

    private static List<String> segments(NoteName name) {
        List<String> segments = new ArrayList<>(name.segments());
        segments.set(segments.size() - 1, name.lastSegment() + NoteName.SUFFIX);
        return segments;
    }

    /** The entry of {@code parent} equal to {@code segment} after folding, the exact one first. */
    private static Optional<String> entry(Path parent, String segment) throws IOException {
        String folded = NoteName.fold(segment);
        Optional<String> other = Optional.empty();
        try (Stream<Path> entries = Files.list(parent)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                String fileName = entry.getFileName().toString();
                if (fileName.equals(segment)) {
                    return Optional.of(fileName);
                }
                if (other.isEmpty() && NoteName.fold(fileName).equals(folded)) {
                    other = Optional.of(fileName);
                }
            }
        }
        return other;
    }

    private static Optional<String> notADirectory(Path path) {
        if (Files.isSymbolicLink(path)) {
            return Optional.of("is a symbolic link");
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of("is not a directory");
        }
        return Optional.empty();
    }

    /** {@code .<file name>.<uuid>.tmp} next to {@code target}, owner-only, written and forced. */
    private Path temporary(Path target, byte[] text) throws IOException {
        Path temporary = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID()
            + ".tmp");
        files.createFile(temporary);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(text);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
        return temporary;
    }

    private static WriteRefusal notAtomic(Exception cause) {
        WriteRefusal refusal = new WriteRefusal(WriteRefusal.Kind.NOT_ATOMIC, null,
            "the file system cannot write notes atomically (" + cause.getClass().getSimpleName()
                + ")");
        refusal.initCause(cause);
        return refusal;
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
