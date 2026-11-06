package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.application.ArchiveFolderWriter.FileOps;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The real file operations of {@link ArchiveFolderWriter} with a hook before each one (given the
 * path and the 1-based number of that operation's call), to inject failures or hold a call for a
 * race in the tests of {@link ArchiveExportService} (feature 015 T020).
 */
final class HookedFileOps implements FileOps {

    /** Runs before an operation; may throw to make it fail. */
    @FunctionalInterface
    interface Hook {
        void run(Path path, int call) throws IOException;
    }

    private final FileOps real = FileOps.system();
    private final AtomicInteger creates = new AtomicInteger();
    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicInteger links = new AtomicInteger();
    private final AtomicInteger deletes = new AtomicInteger();
    private final AtomicInteger directories = new AtomicInteger();
    volatile Hook beforeCreateTemporary = (path, call) -> { };
    /** {@code path} is {@code null}: the channel's file is not known here. */
    volatile Hook beforeWrite = (path, call) -> { };
    volatile Hook beforeLink = (path, call) -> { };
    volatile Hook beforeDelete = (path, call) -> { };
    volatile Hook beforeCreateDirectory = (path, call) -> { };

    @Override
    public FileChannel createTemporary(Path file, FileAttribute<?>... attributes)
        throws IOException {
        beforeCreateTemporary.run(file, creates.incrementAndGet());
        return real.createTemporary(file, attributes);
    }

    @Override
    public void writeAndForce(FileChannel channel, byte[] content) throws IOException {
        beforeWrite.run(null, writes.incrementAndGet());
        real.writeAndForce(channel, content);
    }

    @Override
    public void link(Path link, Path existing) throws IOException {
        beforeLink.run(link, links.incrementAndGet());
        real.link(link, existing);
    }

    @Override
    public void delete(Path file) throws IOException {
        beforeDelete.run(file, deletes.incrementAndGet());
        real.delete(file);
    }

    @Override
    public void createDirectory(Path directory, FileAttribute<?>... attributes)
        throws IOException {
        beforeCreateDirectory.run(directory, directories.incrementAndGet());
        real.createDirectory(directory, attributes);
    }
}
