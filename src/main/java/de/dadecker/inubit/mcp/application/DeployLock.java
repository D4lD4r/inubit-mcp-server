package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The locks of one deployment (feature 005, FR-021, research D-11): first an exclusive
 * {@link FileChannel#tryLock() file lock} on
 * {@code ~/.inubit-mcp/<profile>/deployments/<group>.lock} — busy is {@code DEPLOY_LOCKED},
 * across the server processes of the profile and within one — then the {@link WorkspaceLock}
 * (busy is {@code PRECONDITION_FAILED}; the group lock is given back then). Both are held for
 * the preview and for the execute call and released by {@link #close()}, and by the operating
 * system when the process ends. The directory and the lock files are owner-only, created
 * through the {@link PrivateFileSystem} (feature 013, FR-007: {@code rwx------} and
 * {@code rw-------} on POSIX, on Windows a protected access list for the current user and
 * {@code SYSTEM}); an existing directory or lock file is used as it is. The lock files stay.
 */
public final class DeployLock implements AutoCloseable {

    /** The file name of a target group's lock: {@code <group>.lock}. */
    public static final String SUFFIX = ".lock";

    private final GroupLock group;
    private final WorkspaceLock workspace;

    private DeployLock(GroupLock group, WorkspaceLock workspace) {
        this.group = group;
        this.workspace = workspace;
    }

    /**
     * Locks the deployments into {@code group}, then the workspace.
     *
     * @param deployments {@code ~/.inubit-mcp/<profile>/deployments}
     * @param files       creates the private directory and lock file
     * @throws ToolErrorException {@code DEPLOY_LOCKED} if another deployment into the group
     *     runs, {@code PRECONDITION_FAILED} if the workspace is busy or a lock file cannot be
     *     used
     */
    public static DeployLock acquire(Path deployments, GroupId group, Path workspace,
        PrivateFileSystem files) {
        GroupLock groupLock = groupLock(deployments, group, files);
        try {
            return new DeployLock(groupLock, WorkspaceLock.acquire(workspace));
        } catch (RuntimeException e) {
            groupLock.close();
            throw e;
        }
    }

    /** The lock of {@code group} alone (also for a second test JVM). */
    static GroupLock groupLock(Path deployments, GroupId group, PrivateFileSystem files) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(files, "files");
        Path file = deployments.resolve(group.value() + SUFFIX);
        FileChannel channel;
        try {
            if (!Files.isDirectory(deployments)) {
                files.createDirectories(deployments);
            }
            if (!Files.exists(file)) {
                try {
                    files.createFile(file);
                } catch (FileAlreadyExistsException e) {
                    // created meanwhile by another process of the profile
                }
            }
            channel = files.openLockFile(file);
        } catch (IOException | UnsupportedOperationException e) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The deploy lock " + file + " cannot be opened (" + e.getClass().getSimpleName()
                    + "); nothing was sent",
                "The directory ~/.inubit-mcp/<profile>/deployments is not writable",
                "Check the permissions of that directory, then retry"));
        }
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null; // held in this process
        } catch (IOException e) {
            closeQuietly(channel);
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The deploy lock " + file + " cannot be locked (" + e.getClass().getSimpleName()
                    + "); nothing was sent",
                "The file system does not support file locks or reported an I/O error",
                "Keep ~/.inubit-mcp on a local file system and retry"));
        }
        if (lock == null) {
            closeQuietly(channel);
            throw new ToolErrorException(ToolError.of(ErrorCode.DEPLOY_LOCKED,
                "Another deployment into " + group + " is running; nothing was sent",
                "A deploy_release into this group (in this or another MCP server process of the"
                    + " profile) has not finished",
                "Wait until it has finished, then call deploy_release again"));
        }
        return new GroupLock(channel, lock);
    }

    /** Releases the workspace lock, then the group lock; closing twice is harmless. */
    @Override
    public void close() {
        try {
            workspace.close();
        } finally {
            group.close();
        }
    }

    /** The file lock of one target group. */
    static final class GroupLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private GroupLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() {
            try {
                if (lock.isValid()) {
                    lock.release();
                }
            } catch (IOException e) {
                // closing the channel releases it as well
            } finally {
                closeQuietly(channel);
            }
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            // nothing left to release
        }
    }
}
