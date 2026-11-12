package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import java.time.Duration;

/**
 * Raises and resets temporary log levels of one INUBIT server through StartCLI, and names the
 * node that answers (feature 024, research D-1, D-3, D-11). Thread-safe.
 */
public interface LogLevelPort {

    /**
     * The INUBIT node that answers for a server running {@code version}. {@code deadlineNanos}
     * is the call deadline as a {@link System#nanoTime()} value set by the service.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException if the node cannot be named
     *     (e.g. login, {@code TIMEOUT})
     */
    String answeringNode(InubitVersion version, long deadlineNanos);

    /**
     * Raises {@code logger} to {@code level} on the answering node, within {@code timeout}.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException if the change failed or its
     *     outcome is unknown
     */
    void raise(TemporaryLevel level, LoggerName logger, InubitVersion version, Duration timeout);

    /**
     * Resets every temporary log level of the answering node, within {@code timeout}.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException if the reset failed or its
     *     outcome is unknown
     */
    void resetAll(InubitVersion version, Duration timeout);
}
