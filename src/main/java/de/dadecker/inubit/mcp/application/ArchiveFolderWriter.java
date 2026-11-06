package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ArchiveFileNames;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
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
 * permissions of existing folders are never changed. Existing levels are detected following
 * symbolic links; a level created concurrently is accepted only as a real directory — a symbolic
 * link created concurrently is refused and fails the archive. POSIX attributes are used only when
 * the default file system supports the {@code posix} view.
 *
 * <p>Without hard links ({@code UnsupportedOperationException}, or a {@code FileSystemException}
 * other than {@code FileAlreadyExistsException} from the link, e.g. FAT/exFAT) the temporary
 * file is deleted and the archive is written with {@code CREATE_NEW} on the final name directly;
 * if that write fails, the file this call created is deleted.
 *
 * <p>The writes stop at the first failure — the target folder, building an archive, or any file
 * operation, also a runtime exception: that archive is reported in {@link Result#failed} with
 * {@code INTERNAL}, the workflows after it in {@link Result#notWritten}, the archives written
 * before it in {@link Result#written}. Every file the call created for a failed archive is
 * deleted; a folder it created stays. A file that cannot be deleted (also the temporary file of
 * a written archive) is named in a call-level warning. Messages name files, never content; for
 * a runtime exception only its class name.
 */
public final class ArchiveFolderWriter {

    /** The file operations of the writer; replaceable in tests to inject failures or races. */
    interface FileOps {

        /**
         * Creates {@code file} with {@code CREATE_NEW} and opens it for writing: the temporary
         * file, or the final name when hard links are unsupported.
         */
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
     *     archive in memory at a time); an exception stops the writes at this archive
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
     * @param message  what failed, naming the file, never content; a {@code ToolError} of
     *     building the archive with its code and message, a runtime exception by its class name
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
     * @param warnings   call-level warnings: files the call could not delete (a leftover
     *     temporary file of a written archive, or a file of the failed one), naming them
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
     * missing parents first. Nothing is created when {@code archives} is empty. Never throws for
     * a failure of the folder, an archive or a file operation: the writes stop there and the
     * failure is part of the result.
     */
    public Result write(Path folder, List<Archive> archives) {
        List<Written> written = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (archives.isEmpty()) {
            return new Result(written, List.of(), List.of(), warnings);
        }
        try {
            createFolder(folder);
        } catch (IOException | RuntimeException e) {
            return stopped(written, warnings, archives, 0,
                "The target folder could not be created: " + reason(e));
        }
        for (int i = 0; i < archives.size(); i++) {
            Archive archive = archives.get(i);
            String file = ArchiveFileNames.fileName(archive.workflow());
            byte[] content;
            try {
                content = archive.content().get();
            } catch (ToolErrorException e) {
                return stopped(written, warnings, archives, i, "The archive file '" + file
                    + "' could not be built (" + e.error().code() + ": " + e.error().message()
                    + ")");
            } catch (RuntimeException e) {
                return stopped(written, warnings, archives, i, "The archive file '" + file
                    + "' could not be built (" + e.getClass().getSimpleName() + ")");
            }
            try {
                written.add(place(folder, archive.workflow(), file, content, warnings));
            } catch (NameTaken e) {
                return stopped(written, warnings, archives, i, "The file '" + file + "' appeared"
                    + " in the target folder after the collision check; it was left unchanged");
            } catch (IOException | RuntimeException e) {
                return stopped(written, warnings, archives, i,
                    "Writing the archive file '" + file + "' failed: " + reason(e));
            }
        }
        return new Result(written, List.of(), List.of(), warnings);
    }

    /** The final name exists: created by someone else after the caller's collision check. */
    private static final class NameTaken extends Exception {

        private static final long serialVersionUID = 1L;

        NameTaken(FileAlreadyExistsException cause) {
            super(cause);
        }
    }

    /**
     * Writes a temporary file and links the final name to it; without hard links (step 4 of
     * R-11) writes the final name directly with {@code CREATE_NEW}. Only a clash of the final
     * name is a {@link NameTaken}; a clash of the random temporary name is an ordinary failure.
     */
    private Written place(Path folder, String workflow, String file, byte[] content,
        List<String> warnings) throws IOException, NameTaken {
        Path target = folder.resolve(file);
        Path temporary = folder.resolve(temporaryName(file));
        // A clash of the random name throws here and never deletes the foreign file.
        fill(temporary, createNew(temporary), content, warnings);
        try {
            ops.link(target, temporary);
        } catch (FileAlreadyExistsException e) {
            remove(temporary, warnings);
            throw new NameTaken(e);
        } catch (UnsupportedOperationException | FileSystemException e) {
            remove(temporary, warnings);
            return placeDirectly(target, workflow, file, content, warnings);
        } catch (IOException | RuntimeException e) {
            remove(temporary, warnings);
            throw e;
        }
        remove(temporary, warnings);
        return new Written(workflow, file, content.length);
    }

    private Written placeDirectly(Path target, String workflow, String file, byte[] content,
        List<String> warnings) throws IOException, NameTaken {
        FileChannel created;
        try {
            created = createNew(target);
        } catch (FileAlreadyExistsException e) {
            throw new NameTaken(e);
        }
        fill(target, created, content, warnings);
        return new Written(workflow, file, content.length);
    }

    private FileChannel createNew(Path file) throws IOException {
        return posix
            ? ops.createTemporary(file, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
            : ops.createTemporary(file);
    }

    /**
     * Writes and closes {@code created}, the new {@code file}; on any failure, including
     * {@code close()}, the file — created by this call — is deleted.
     */
    private void fill(Path file, FileChannel created, byte[] content, List<String> warnings)
        throws IOException {
        try (FileChannel channel = created) {
            if (posix) {
                Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
            }
            ops.writeAndForce(channel, content);
        } catch (IOException | RuntimeException e) {
            remove(file, warnings);
            throw e;
        }
    }

    private void createFolder(Path folder) throws IOException {
        Deque<Path> missing = new ArrayDeque<>();
        for (Path level = folder; level != null && !Files.exists(level);
            level = level.getParent()) {
            missing.push(level);
        }
        for (Path level : missing) {
            try {
                if (posix) {
                    ops.createDirectory(level,
                        PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
                } else {
                    ops.createDirectory(level);
                }
            } catch (FileAlreadyExistsException e) {
                if (Files.isDirectory(level, LinkOption.NOFOLLOW_LINKS)) {
                    continue; // created concurrently: accepted as it is
                }
                throw e;
            }
            if (posix) {
                Files.setPosixFilePermissions(level, DIRECTORY_PERMISSIONS);
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

    /**
     * Deletes a file this call created; if that fails, a call-level warning names it (it holds
     * the archive, or part of it, with the secrets in clear text).
     */
    private void remove(Path file, List<String> warnings) {
        try {
            ops.delete(file);
        } catch (NoSuchFileException e) {
            // already gone
        } catch (IOException | RuntimeException e) {
            warnings.add("The file " + file + " could not be deleted (" + reason(e) + "); it"
                + " holds an archive or part of it with the workflow's secrets in clear text —"
                + " delete it");
        }
    }

    private static Result stopped(List<Written> written, List<String> warnings,
        List<Archive> archives, int index, String message) {
        Archive archive = archives.get(index);
        Failed failed = new Failed(archive.workflow(),
            ArchiveFileNames.fileName(archive.workflow()), ErrorCode.INTERNAL, message);
        List<String> notWritten = archives.subList(index + 1, archives.size()).stream()
            .map(Archive::workflow).toList();
        return new Result(written, List.of(failed), notWritten, warnings);
    }

    /**
     * The reason of a failure without paths or content: the file system's reason, the message
     * of another {@code IOException}, or only the class name of a runtime exception.
     */
    private static String reason(Exception e) {
        if (e instanceof FileSystemException fse) {
            return fse.getReason() != null ? fse.getReason() : e.getClass().getSimpleName();
        }
        if (e instanceof IOException && e.getMessage() != null) {
            return e.getMessage();
        }
        return e.getClass().getSimpleName();
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
