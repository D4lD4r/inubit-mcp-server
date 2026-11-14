package de.dadecker.inubit.mcp.application.nodetools;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The input of one {@code set_temporary_log_level} call (feature 024), as the tool received it;
 * {@link LogLevelService#set} checks every value again (research D-11, D-14 step 1).
 *
 * @param node             the id of exactly one server ({@code <group>/<node>})
 * @param loggers          the logger names as requested, in request order
 * @param level            the requested level name
 * @param durationMinutes  the requested duration; absent: the default of 10 minutes
 * @param confirmationCode the code of a preview, if given
 * @param mcpClient        the MCP client's {@code name/version} from {@code initialize}, if known
 */
public record SetLevelRequest(String node, List<String> loggers, String level,
    OptionalInt durationMinutes, Optional<String> confirmationCode, Optional<String> mcpClient) {

    public SetLevelRequest {
        Objects.requireNonNull(node, "node");
        loggers = List.copyOf(loggers);
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(durationMinutes, "durationMinutes");
        Objects.requireNonNull(confirmationCode, "confirmationCode");
        Objects.requireNonNull(mcpClient, "mcpClient");
    }
}
