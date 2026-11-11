package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.LogPresence;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * A server log downloaded by a {@link ServerLogPort} (feature 021, research D-19): the private
 * copy {@code file} of {@code size} bytes, the INUBIT node name the server reported
 * ({@code inubitNode}, 1–128 chars of {@code [A-Za-z0-9_.:-]}, checked by the adapter) and
 * whether the log is known to exist. {@link #close()} runs {@code deletePrivateDirectory}, which
 * deletes the private directory holding {@code file}.
 */
public record DownloadedLog(Path file, long size, Optional<String> inubitNode,
    LogPresence presence, Runnable deletePrivateDirectory) implements AutoCloseable {

    public DownloadedLog {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(inubitNode, "inubitNode");
        Objects.requireNonNull(presence, "presence");
        Objects.requireNonNull(deletePrivateDirectory, "deletePrivateDirectory");
    }

    /** Deletes the private directory holding {@link #file()}. */
    @Override
    public void close() {
        deletePrivateDirectory.run();
    }
}
