package de.dadecker.inubit.mcp.application.nodetools;

import java.util.Objects;
import java.util.Optional;

/**
 * The input of one {@code reset_temporary_log_level} call (feature 024), as the tool received it;
 * {@link LogLevelService#reset} checks the combination and every value again (research D-15):
 * either {@code node}, or {@code leaseId} with {@code forget: true}.
 *
 * @param node             the id of exactly one server ({@code <group>/<node>}), if given
 * @param leaseId          the id of a lease to forget, if given
 * @param forget           the {@code forget} flag, if given
 * @param confirmationCode the code of a preview, if given
 * @param mcpClient        the MCP client's {@code name/version} from {@code initialize}, if known
 */
public record ResetLevelRequest(Optional<String> node, Optional<String> leaseId,
    Optional<Boolean> forget, Optional<String> confirmationCode, Optional<String> mcpClient) {

    public ResetLevelRequest {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(leaseId, "leaseId");
        Objects.requireNonNull(forget, "forget");
        Objects.requireNonNull(confirmationCode, "confirmationCode");
        Objects.requireNonNull(mcpClient, "mcpClient");
    }
}
