package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.node.IoLogEntry;
import de.dadecker.inubit.mcp.domain.model.node.IoLogListing;
import de.dadecker.inubit.mcp.domain.model.node.IoMessageRef;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Lists the I/O log of a process run and reads one of its messages through an unsupported INUBIT
 * interface (feature 024, research D-8, D-9). Thread-safe.
 */
public interface IoLogPort {

    /**
     * The I/O log entries of the run {@code processId} in {@code workflow} on a server running
     * {@code version}. {@code deadlineNanos} is the call deadline as a {@link System#nanoTime()}
     * value set by the service.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException for a failure of the whole
     *     call (e.g. login, {@code TIMEOUT})
     */
    IoLogListing list(String processId, String workflow, InubitVersion version,
        long deadlineNanos);

    /**
     * Reads the message of the listed entry whose reference is {@code ref} into a private
     * directory; {@link IoLogRead.NoMatch} if no listed entry has that reference. The caller
     * closes the result, which deletes the private directory; no file is written outside it.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException for a failure of the whole
     *     call (e.g. login, {@code TIMEOUT}); the private directory is deleted then
     */
    IoLogRead read(String processId, String workflow, IoMessageRef ref, InubitVersion version,
        long deadlineNanos);

    /** The outcome of {@link #read}. */
    sealed interface IoLogRead extends AutoCloseable {

        /** The listing the reference was looked up in. */
        IoLogListing listing();

        /** Deletes the private directory of this read, if any. */
        @Override
        void close();

        /** No listed entry has the reference; nothing was downloaded. */
        record NoMatch(IoLogListing listing) implements IoLogRead {

            public NoMatch {
                Objects.requireNonNull(listing, "listing");
            }

            @Override
            public void close() {
            }
        }

        /**
         * The message of {@code entry}: the private copy {@code file} of {@code size} bytes.
         * {@link #close()} runs {@code deletePrivateDirectory}, which deletes the private
         * directory holding {@code file}.
         */
        record Message(IoLogListing listing, IoLogEntry entry, Path file, long size,
            Runnable deletePrivateDirectory) implements IoLogRead {

            public Message {
                Objects.requireNonNull(listing, "listing");
                Objects.requireNonNull(entry, "entry");
                Objects.requireNonNull(file, "file");
                Objects.requireNonNull(deletePrivateDirectory, "deletePrivateDirectory");
                if (size < 0) {
                    throw new IllegalArgumentException("Invalid size: negative");
                }
            }

            @Override
            public void close() {
                deletePrivateDirectory.run();
            }
        }
    }
}
