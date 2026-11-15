package de.dadecker.inubit.mcp.application.nodetools;

import de.dadecker.inubit.mcp.application.OwnerOnlyFileLock;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * One log-level change per server at a time, across all instances of the profile (feature 024,
 * research D-12): the {@link OwnerOnlyFileLock} on
 * {@code log-levels/nodes/<group>.<node>.lock}, held by a set, a reset and a cleanup reset around
 * the own-node lookup and the StartCLI runs. It is always taken before the store lock of the
 * {@link LeaseStore}, never while holding it.
 */
public final class NodeLogLevelLocks {

    /** The directory of the node lock files in the log-levels directory. */
    public static final String DIRECTORY = "nodes";

    private final Path logLevels;
    private final Path nodes;
    private final PrivateFileSystem files;
    private final OwnerOnlyFileLock lock;

    /**
     * @param logLevels {@code ~/.inubit-mcp/<profile>/log-levels}
     *                  ({@link LeaseStore#defaultDirectory})
     * @param files     creates the private directories and lock files
     */
    public NodeLogLevelLocks(Path logLevels, PrivateFileSystem files) {
        this.logLevels = Objects.requireNonNull(logLevels, "logLevels");
        this.nodes = logLevels.resolve(DIRECTORY);
        this.files = Objects.requireNonNull(files, "files");
        this.lock = new OwnerOnlyFileLock(files, "the log levels of one server",
            "the log-levels nodes directory");
    }

    /**
     * Takes the lock of {@code server}.
     *
     * @throws Busy {@code PRECONDITION_FAILED} when another change of the server holds it beyond
     *     {@code wait}
     * @throws ToolErrorException {@code PRECONDITION_FAILED} when the lock file or its
     *     directories cannot be used (a link, a directory, an I/O error)
     */
    public OwnerOnlyFileLock.Held acquire(NodeId server, Duration wait) {
        refuseLink(logLevels, "The log-levels directory ");
        refuseLink(nodes, "The log-levels nodes directory ");
        try {
            files.createDirectories(nodes);
        } catch (IOException | UnsupportedOperationException e) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The log-levels nodes directory " + nodes + " cannot be created ("
                    + e.getClass().getSimpleName() + "); nothing was changed",
                "The directory is not writable or does not support owner-only permissions",
                "Check ~/.inubit-mcp/<profile>/log-levels, then retry"));
        }
        return lock.acquire(nodes.resolve(server.group().value() + "." + server.name() + ".lock"),
            wait, () -> busy(server));
    }

    private static void refuseLink(Path directory, String name) {
        if (Files.isSymbolicLink(directory)) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                name + directory + " is a symbolic link; nothing was changed",
                "A link there is never followed",
                "Replace the link by a directory of your own, then retry"));
        }
    }

    /** Another change of the server holds the lock, unlike a lock file that cannot be used. */
    public static final class Busy extends ToolErrorException {

        private static final long serialVersionUID = 1L;

        Busy(ToolError error) {
            super(error);
        }
    }

    private static Busy busy(NodeId server) {
        return new Busy(ToolError.of(ErrorCode.PRECONDITION_FAILED,
            "Another log-level change for " + server.value() + " is running",
            "A set, a reset or a lease cleanup of this server (in this or another MCP server"
                + " process of the profile) has not finished within the wait",
            "Try again when it has finished"));
    }
}
