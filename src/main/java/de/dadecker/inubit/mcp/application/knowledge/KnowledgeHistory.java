package de.dadecker.inubit.mcp.application.knowledge;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.Names;
import de.dadecker.inubit.mcp.domain.model.NoteName;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The previous versions of the notes (feature 019, research D-11): the note {@code partners/acme}
 * keeps them in {@code .history/partners/acme.md/<instant>-<counter>.md}, the instant in UTC with
 * nine fraction digits, the counter one above the greatest of that instant. A version is a new
 * owner-only file, so nothing existing is replaced; after the note is published the versions
 * beyond the ten newest by the parsed (instant, counter) are deleted. Files of another pattern are
 * neither counted nor deleted. Every directory on the way is checked without following links and
 * missing ones are created one segment at a time, owner-only (D-3, D-12).
 */
public final class KnowledgeHistory {

    /** The directory of the history inside the knowledge directory. */
    public static final String DIRECTORY = ".history";

    private static final int MAX_COUNTER = 999;
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern VERSION =
        Pattern.compile("(\\d{8}T\\d{6}\\.\\d{9}Z)-(\\d{3})\\.md");

    private final Path root;
    private final PrivateFileSystem files;
    private final Clock clock;

    /**
     * @param root  the knowledge directory; it exists when a version is added
     * @param files creates the directories and versions owner-only
     * @param clock the time of a version
     */
    public KnowledgeHistory(Path root, PrivateFileSystem files, Clock clock) {
        this.root = Objects.requireNonNull(root, "root");
        this.files = Objects.requireNonNull(files, "files");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Copies {@code note} into a new version of {@code name}.
     *
     * @return the new version, for {@link #undo}
     * @throws ToolErrorException {@code PRECONDITION_FAILED} when a directory on the way is a link
     *     or no directory, or the counter of the instant is used up
     * @throws IOException        when the note cannot be read or the version not written
     */
    public Path add(Path note, NoteName name) throws IOException {
        Path directory = versionDirectory(name, true).orElseThrow();
        String stamp = STAMP.format(clock.instant());
        int counter = 0;
        for (Version version : versions(directory)) {
            if (version.stamp().equals(stamp)) {
                counter = Math.max(counter, version.counter() + 1);
            }
        }
        if (counter > MAX_COUNTER) {
            throw refused("The history of the note " + Names.quote(name.value())
                + " already holds version " + MAX_COUNTER + " of this instant",
                "More than " + (MAX_COUNTER + 1) + " versions were written within one clock tick",
                "Try again in a moment");
        }
        byte[] text;
        try (InputStream in = Files.newInputStream(note, LinkOption.NOFOLLOW_LINKS)) {
            text = in.readAllBytes();
        }
        Path version = directory.resolve(stamp + "-%03d".formatted(counter) + NoteName.SUFFIX);
        files.createFile(version);
        try (FileChannel channel = FileChannel.open(version, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(text);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException e) {
            Files.deleteIfExists(version);
            throw e;
        }
        return version;
    }

    /** Deletes a version {@link #add} made, when the note could not be published. */
    public void undo(Path version) throws IOException {
        Files.deleteIfExists(version);
    }

    /**
     * Deletes the oldest versions of {@code name} beyond the ten newest.
     *
     * @return the versions kept
     * @throws ToolErrorException {@code PRECONDITION_FAILED} when a directory on the way is a link
     *     or no directory
     * @throws IOException        when a version cannot be deleted
     */
    public int prune(NoteName name) throws IOException {
        Optional<Path> directory = versionDirectory(name, false);
        if (directory.isEmpty()) {
            return 0;
        }
        List<Version> versions = new ArrayList<>(versions(directory.get()));
        versions.sort(Comparator.comparing(Version::instant).thenComparing(Version::counter));
        int excess = versions.size() - KnowledgeLimits.HISTORY;
        for (int i = 0; i < excess; i++) {
            Files.deleteIfExists(versions.get(i).file());
        }
        return Math.min(versions.size(), KnowledgeLimits.HISTORY);
    }

    private record Version(Path file, String stamp, Instant instant, int counter) {
    }

    private static List<Version> versions(Path directory) throws IOException {
        List<Version> versions = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path file : (Iterable<Path>) entries::iterator) {
                Matcher matcher = VERSION.matcher(file.getFileName().toString());
                if (!matcher.matches() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    Instant instant = STAMP.parse(matcher.group(1), Instant::from);
                    versions.add(new Version(file, matcher.group(1), instant,
                        Integer.parseInt(matcher.group(2))));
                } catch (DateTimeParseException e) {
                    // not a version the server wrote: neither counted nor pruned
                }
            }
        }
        return versions;
    }

    /** {@code .history/<segments>.md}, checked segment by segment and created if asked. */
    private Optional<Path> versionDirectory(NoteName name, boolean create) throws IOException {
        List<String> segments = new ArrayList<>();
        segments.add(DIRECTORY);
        List<String> noteSegments = name.segments();
        segments.addAll(noteSegments.subList(0, noteSegments.size() - 1));
        segments.add(name.lastSegment() + NoteName.SUFFIX);
        Path directory = root;
        for (String segment : segments) {
            directory = directory.resolve(segment);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) {
                    return Optional.empty();
                }
                try {
                    files.createDirectory(directory);
                } catch (FileAlreadyExistsException e) {
                    // created meanwhile, or a link that appeared: checked below
                }
            }
            if (Files.isSymbolicLink(directory)) {
                throw occupied(directory, "is a symbolic link");
            }
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw occupied(directory, "is not a directory");
            }
        }
        return Optional.of(directory);
    }

    private ToolErrorException occupied(Path path, String what) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        return refused("The history path " + relative + " in the knowledge directory " + what
            + "; nothing was written",
            "The history of the notes is kept in " + DIRECTORY + " and never written through a"
                + " link",
            "Move " + relative + " away by hand, then retry");
    }

    private static ToolErrorException refused(String message, String cause, String next) {
        return new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED, message, cause,
            next));
    }
}
