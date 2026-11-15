package de.dadecker.inubit.mcp.domain.model.node;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The MCP server instance that owns a lease (FR-043, research D-13): process id plus process start
 * time, if the operating system reports it.
 *
 * @param pid       greater than zero
 * @param startedAt the process start time
 */
public record LeaseOwner(long pid, Optional<Instant> startedAt) {

    public LeaseOwner {
        if (pid <= 0) {
            throw new IllegalArgumentException("Invalid pid: expected a number greater than zero");
        }
        Objects.requireNonNull(startedAt, "startedAt");
    }
}
