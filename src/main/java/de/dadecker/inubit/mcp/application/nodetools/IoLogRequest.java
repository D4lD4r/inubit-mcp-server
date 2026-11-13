package de.dadecker.inubit.mcp.application.nodetools;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The input of one {@code read_io_log} call (feature 024), as the tool received it after the input
 * schema.
 *
 * @param target    the id of exactly one server ({@code <group>/<node>})
 * @param workflow  the process (workflow) name as {@code query_logs} returns it
 * @param processId the process ID of the run as {@code query_logs} returns it
 * @param message   the {@code ref} of one entry of the run's listing, if that message is asked for
 * @param module    lists only entries whose module name or module ID equals this text, if given
 * @param maxChars  the maximum characters of the returned message text, if given
 */
public record IoLogRequest(String target, String workflow, String processId,
    Optional<String> message, Optional<String> module, OptionalInt maxChars) {

    public IoLogRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(processId, "processId");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(maxChars, "maxChars");
    }
}
