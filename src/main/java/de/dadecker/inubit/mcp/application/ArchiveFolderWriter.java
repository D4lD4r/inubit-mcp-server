package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ArchiveFileNames;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Places workflow archives in a target folder without ever replacing a file (feature 015,
 * research R-11, FR-009, FR-010, FR-011).
 *
 * <p>Per archive: a hidden temporary file {@code .<file name>.<random>.part} is created in the
 * target folder with {@code CREATE_NEW} (owner-only on POSIX), the bytes are written and
 * {@code force(true)}d, then {@code Files.createLink(final, temporary)} gives the archive its
 * name — the kernel refuses atomically if the name exists, also when another call created it
 * after the caller's collision check — and the temporary name is deleted. The final name thus
 * only ever appears complete.
 *
 * <p>The target folder and its missing parents are created right before the first archive, one
 * level at a time from the nearest existing ancestor, each {@code rwx------} on POSIX; the
 * permissions of existing folders are never changed. POSIX attributes are used only when the
 * default file system supports the {@code posix} view.
 *
 * <p>The writes stop at the first failure: that archive is reported in {@link Result#failed},
 * the workflows after it in {@link Result#notWritten}. Messages name files, never content.
 */
public final class ArchiveFolderWriter {

    /** The file operations of the writer; replaceable in tests to inject failures or races. */
    interface FileOps {

        /** Creates {@code file} with {@code CREATE_NEW} and opens it for writing. */
        FileChannel createTemporary(Path file, FileAttribute<?>... attributes) throws IOException;

        /** Writes all of {@code content} to {@code channel} and forces it to the device. */
        void writeAndForce(FileChannel channel, byte[] content) throws IOException;

        /** Creates the hard link {@code link} to {@code existing}; never replaces a file. */
        void link(Path link, Path existing) throws IOException;

        /** Deletes {@code file}. */
        void delete(Path file) throws IOException;

        /** Creates the single directory {@code directory}. */
        void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException;

        /** The operations of the default file system. */
        static FileOps system() {
            return SystemFileOps.INSTANCE;
        }
    }

    /**
     * One archive to place.
     *
     * @param workflow the workflow name; the file name is {@link ArchiveFileNames#fileName} of it
     * @param content  the archive bytes, built on demand right before the archive is placed (one
     *     archive in memory at a time); its exceptions propagate unchanged
     */
    public record Archive(String workflow, Supplier<byte[]> content) {

        public Archive {
            Objects.requireNonNull(workflow, "workflow");
            Objects.requireNonNull(content, "content");
        }
    }

    /**
     * An archive placed completely.
     *
     * @param workflow the workflow name
     * @param file     the file name in the target folder
     * @param size     the size in bytes
     */
    public record Written(String workflow, String file, long size) {

        public Written {
            Objects.requireNonNull(workflow, "workflow");
            Objects.requireNonNull(file, "file");
        }
    }

    /**
     * The archive whose placement failed.
     *
     * @param workflow the workflow name
     * @param file     the file name it was to get
     * @param code     the error code ({@code INTERNAL})
     * @param message  what failed, naming the file, never content
     */
    public record Failed(String workflow, String file, ErrorCode code, String message) {

        public Failed {
            Objects.requireNonNull(workflow, "workflow");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * What one {@link #write} did.
     *
     * @param written    the archives placed completely, in write order
     * @param failed     at most one entry: the archive the writes stopped at
     * @param notWritten the workflows after the failed one, not attempted
     * @param warnings   call-level warnings
     */
    public record Result(List<Written> written, List<Failed> failed, List<String> notWritten,
        List<String> warnings) {

        public Result {
            written = List.copyOf(written);
            failed = List.copyOf(failed);
            notWritten = List.copyOf(notWritten);
            warnings = List.copyOf(warnings);
            if (failed.size() > 1) {
                throw new IllegalArgumentException("At most one failed archive");
            }
        }
    }

    private static final int MAX_NAME_BYTES = 255;
    private static final String PART = ".part";
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
        PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
        PosixFilePermissions.fromString("rwx------");

    private final FileOps ops;
    private final boolean posix =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private final SecureRandom random = new SecureRandom();

    public ArchiveFolderWriter() {
        this(FileOps.system());
    }

    ArchiveFolderWriter(FileOps ops) {
        this.ops = Objects.requireNonNull(ops, "ops");
    }

    /**
     * Places {@code archives} in {@code folder}, in the given order, creating the folder and its
     * missing parents first. Nothing is created when {@code archives} is empty.
     */
    public Result write(Path folder, List<Archive> archives) {
        List<Written> written = new ArrayList<>();
        if (archives.isEmpty()) {
            return new Result(written, List.of(), List.of(), List.of());
        }
        try {
            createFolder(folder);
        } catch (IOException e) {
            Archive first = archives.get(0);
            return stopped(written, new Failed(first.workflow(),
                ArchiveFileNames.fileName(first.workflow()), ErrorCode.INTERNAL,
                "The target folder could not be created: " + reason(e)), archives, 0);
        }
        for (int i = 0; i < archives.size(); i++) {
            Archive archive = archives.get(i);
            String file = ArchiveFileNames.fileName(archive.workflow());
            try {
                written.add(place(folder, archive, file));
            } catch (FileAlreadyExistsException e) {
                return stopped(written, new Failed(archive.workflow(), file, ErrorCode.INTERNAL,
                    "The file '" + file + "' appeared in the target folder after the collision"
                        + " check; it was left unchanged"), archives, i);
            } catch (IOException e) {
                return stopped(written, new Failed(archive.workflow(), file, ErrorCode.INTERNAL,
                    "Writing the archive file '" + file + "' failed: " + reason(e)), archives, i);
            }
        }
        return new Result(written, List.of(), List.of(), List.of());
    }

    private Written place(Path folder, Archive archive, String file) throws IOException {
        byte[] content = archive.content().get();
        Path temporary = folder.resolve(temporaryName(file));
        FileChannel created = posix
            ? ops.createTemporary(temporary, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
            : ops.createTemporary(temporary);
        // The cleanup also covers a failing close(), which would otherwise leave the secrets behind.
        try (FileChannel channel = created) {
            if (posix) {
                Files.setPosixFilePermissions(temporary, FILE_PERMISSIONS);
            }
            ops.writeAndForce(channel, content);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temporary, e);
            throw e;
        }
        try {
            ops.link(folder.resolve(file), temporary);
            ops.delete(temporary);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temporary, e);
            throw e;
        }
        return new Written(archive.workflow(), file, content.length);
    }

    private void createFolder(Path folder) throws IOException {
        Deque<Path> missing = new ArrayDeque<>();
        for (Path level = folder; level != null && !Files.exists(level);
            level = level.getParent()) {
            missing.push(level);
        }
        for (Path level : missing) {
            if (posix) {
                ops.createDirectory(level,
                    PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
                Files.setPosixFilePermissions(level, DIRECTORY_PERMISSIONS);
            } else {
                ops.createDirectory(level);
            }
        }
    }

    /**
     * {@code .<file name>.<random>.part}; the file name part is shortened (at a code point) when
     * the whole would exceed the 255 bytes a file name may have.
     */
    private String temporaryName(String file) {
        String suffix = "." + HexFormat.of().toHexDigits(random.nextLong()) + PART;
        int budget = MAX_NAME_BYTES - 1 - suffix.length();
        String base = file;
        while (base.getBytes(StandardCharsets.UTF_8).length > budget) {
            base = base.substring(0, base.offsetByCodePoints(base.length(), -1));
        }
        return "." + base + suffix;
    }

    private void deleteQuietly(Path temporary, Exception failure) {
        try {
            if (Files.exists(temporary)) {
                ops.delete(temporary);
            }
        } catch (IOException | RuntimeException e) {
            failure.addSuppressed(e);
        }
    }

    private static Result stopped(List<Written> written, Failed failed, List<Archive> archives,
        int index) {
        List<String> notWritten = archives.subList(index + 1, archives.size()).stream()
            .map(Archive::workflow).toList();
        return new Result(written, List.of(failed), notWritten, List.of());
    }

    /** The reason of a failure without paths or content. */
    private static String reason(IOException e) {
        if (e instanceof FileSystemException fse) {
            return fse.getReason() != null ? fse.getReason() : e.getClass().getSimpleName();
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static final class SystemFileOps implements FileOps {

        static final SystemFileOps INSTANCE = new SystemFileOps();
        private static final Set<StandardOpenOption> CREATE_NEW =
            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        @Override
        public FileChannel createTemporary(Path file, FileAttribute<?>... attributes)
            throws IOException {
            return FileChannel.open(file, CREATE_NEW, attributes);
        }

        @Override
        public void writeAndForce(FileChannel channel, byte[] content) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }

        @Override
        public void link(Path link, Path existing) throws IOException {
            Files.createLink(link, existing);
        }

        @Override
        public void delete(Path file) throws IOException {
            Files.delete(file);
        }

        @Override
        public void createDirectory(Path directory, FileAttribute<?>... attributes)
            throws IOException {
            Files.createDirectory(directory, attributes);
        }
    }
}
