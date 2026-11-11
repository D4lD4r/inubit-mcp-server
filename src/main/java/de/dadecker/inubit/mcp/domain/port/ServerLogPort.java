package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.ServerLogKind;

/**
 * Downloads one INUBIT server log of one node into a private directory (feature 021, research
 * D-19). Thread-safe.
 */
public interface ServerLogPort {

    /**
     * Downloads the {@code kind} log of a node running {@code version}. {@code deadlineNanos} is
     * the call deadline as a {@link System#nanoTime()} value set by the service (research D-15).
     * The caller closes the result, which deletes the private directory; no file is written
     * outside that directory.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException for a failure of the whole
     *     call (e.g. login, {@code TIMEOUT}); the private directory is deleted then
     */
    DownloadedLog download(ServerLogKind kind, InubitVersion version, long deadlineNanos);
}
