package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Places a downloaded log in the target folder without ever replacing a file or showing partial
 * content under the final name (feature 021 research D-7 step 4, FR-007, FR-008, FR-011, SC-004).
 *
 * <p>The folder and its missing parents are created by {@link OwnerOnlyFolders}. The source — the
 * private copy of the download — is streamed byte for byte into {@code .<base>.<random>.part} in
 * the folder ({@code CREATE_NEW}, {@code rw-------} on POSIX) and forced to the device. The name
 * {@code <base>.log}, or the first free of {@code <base>-2.log} … {@code <base>-999.log}, is
 * claimed with a hard link to the part file — the kernel refuses an existing name atomically, so
 * concurrent calls never share a name — and the part name is deleted.
 *
 * <p>Without hard links ({@code UnsupportedOperationException}, or a {@code FileSystemException}
 * other than {@code FileAlreadyExistsException}, e.g. FAT/exFAT) the name is claimed by an empty
 * placeholder created with {@code CREATE_NEW} (same names), which the part file then replaces by
 * an atomic move. Without atomic moves too the call fails with {@code INVALID_INPUT}.
 *
 * <p>On any failure every file the call created is deleted; a folder it created stays.
 */
public final class LogFileWriter {

    /** The file operations of the writer; replaceable in tests to inject failures or races. */
    interface FileOps {

        /** Creates {@code file} with {@code CREATE_NEW} and opens it for writing. */
        FileChannel createNew(Path file, FileAttribute<?>... attributes) throws IOException;

        /** Streams all bytes of {@code source} into {@code target}; returns their number. */
        long copy(Path source, FileChannel target) throws IOException;

        /** Forces {@code channel}'s content and metadata to the device. */
        void force(FileChannel channel) throws IOException;

        /** Creates the hard link {@code link} to {@code existing}; never replaces a file. */
        void link(Path link, Path existing) throws IOException;

        /** Moves {@code source} onto {@code target} with {@code ATOMIC_MOVE, REPLACE_EXISTING}. */
        void move(Path source, Path target) throws IOException;

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
     * The placed log.
     *
     * @param file the absolute path of the file in the target folder
     * @param size its size in bytes
     */
    public record Placed(Path file, long size) {

        public Placed {
            Objects.requireNonNull(file, "file");
        }
    }

    private static final int MAX_SUFFIX = 999;
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
        PosixFilePermissions.fromString("rw-------");

    private final FileOps ops;
    private final boolean posix =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private final SecureRandom random = new SecureRandom();

    public LogFileWriter() {
        this(FileOps.system());
    }

    LogFileWriter(FileOps ops) {
        this.ops = Objects.requireNonNull(ops, "ops");
    }

    /**
     * Places the content of {@code source} in {@code folder} as {@code <baseName>.log} or
     * {@code <baseName>-<n>.log}.
     *
     * @throws ToolErrorException {@code INVALID_INPUT} if {@code <baseName>.log} …
     *     {@code <baseName>-999.log} all exist, or if the folder's file system supports neither
     *     hard links nor atomic renames
     * @throws IOException if creating the folder or a file operation fails
     */
    public Placed place(TargetFolderRules.CheckedFolder folder, String baseName, Path source)
        throws IOException {
        Path directory = folder.path();
        OwnerOnlyFolders.create(directory, ops::createDirectory);
        Path part = directory.resolve(
            "." + baseName + "." + HexFormat.of().toHexDigits(random.nextLong()) + ".part");
        List<Path> created = new ArrayList<>();
        try {
            long size = write(part, source, created);
            return new Placed(claim(directory, baseName, part, created), size);
        } catch (IOException | RuntimeException e) {
            for (Path file : created) {
                remove(file, e);
            }
            throw e;
        }
    }

    /** Streams {@code source} into the new part file and forces it to the device. */
    private long write(Path part, Path source, List<Path> created) throws IOException {
        // A clash of the random name throws here, before the foreign file is listed as created.
        FileChannel channel = createNew(part);
        created.add(part);
        try (channel) {
            if (posix) {
                Files.setPosixFilePermissions(part, FILE_PERMISSIONS);
            }
            long size = ops.copy(source, channel);
            ops.force(channel);
            return size;
        }
    }

    private Path claim(Path directory, String baseName, Path part, List<Path> created)
        throws IOException {
        for (int n = 1; n <= MAX_SUFFIX; n++) {
            Path file = directory.resolve(name(baseName, n));
            try {
                ops.link(file, part);
            } catch (FileAlreadyExistsException e) {
                continue;
            } catch (UnsupportedOperationException | FileSystemException e) {
                return claimWithPlaceholder(directory, baseName, part, created);
            }
            created.add(file);
            ops.delete(part);
            return file;
        }
        throw noFreeName(baseName);
    }

    private Path claimWithPlaceholder(Path directory, String baseName, Path part,
        List<Path> created) throws IOException {
        for (int n = 1; n <= MAX_SUFFIX; n++) {
            Path file = directory.resolve(name(baseName, n));
            FileChannel placeholder;
            try {
                placeholder = createNew(file);
            } catch (FileAlreadyExistsException e) {
                continue;
            }
            created.add(file);
            placeholder.close();
            try {
                ops.move(part, file);
            } catch (AtomicMoveNotSupportedException e) {
                throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                    "The file system of the target folder supports neither hard links nor atomic"
                        + " renames; choose a local folder",
                    "The target folder is on a file system without hard links and atomic"
                        + " renames, e.g. a FAT or exFAT drive or a network share",
                    "Choose a folder on a local disk"));
            }
            return file;
        }
        throw noFreeName(baseName);
    }

    private FileChannel createNew(Path file) throws IOException {
        return posix
            ? ops.createNew(file, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
            : ops.createNew(file);
    }

    private static String name(String baseName, int n) {
        return n == 1 ? baseName + ".log" : baseName + "-" + n + ".log";
    }

    private static ToolErrorException noFreeName(String baseName) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
            "The target folder has no free file name for " + baseName + ".log: it and "
                + baseName + "-2.log … " + baseName + "-" + MAX_SUFFIX + ".log all exist",
            "Earlier downloads of this log at the same second fill the folder",
            "Move or delete older log files, or choose another target folder"));
    }

    /** Deletes a file this call created; a failure is added to {@code failure} as suppressed. */
    private void remove(Path file, Exception failure) {
        try {
            ops.delete(file);
        } catch (NoSuchFileException e) {
            // already gone, e.g. the part file after the atomic move
        } catch (IOException | RuntimeException e) {
            failure.addSuppressed(e);
        }
    }

    private static final class SystemFileOps implements FileOps {

        static final SystemFileOps INSTANCE = new SystemFileOps();
        private static final Set<StandardOpenOption> CREATE_NEW =
            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        @Override
        public FileChannel createNew(Path file, FileAttribute<?>... attributes)
            throws IOException {
            return FileChannel.open(file, CREATE_NEW, attributes);
        }

        @Override
        public long copy(Path source, FileChannel target) throws IOException {
            // The stream is not closed here: closing it would close the caller's channel.
            return Files.copy(source, Channels.newOutputStream(target));
        }

        @Override
        public void force(FileChannel channel) throws IOException {
            channel.force(true);
        }

        @Override
        public void link(Path link, Path existing) throws IOException {
            Files.createLink(link, existing);
        }

        @Override
        public void move(Path source, Path target) throws IOException {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
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
