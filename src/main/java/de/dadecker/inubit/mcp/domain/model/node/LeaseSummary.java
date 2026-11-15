package de.dadecker.inubit.mcp.domain.model.node;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The view of an open lease in {@code get_health} and the summary (FR-045, research D-23,
 * contracts {@code $defs/LeaseSummary}); no owner and no generation.
 *
 * @param inubitNode name rule of {@link ClusterNode}
 * @param loggers    at least one, sorted by name
 * @param state      {@link LeaseState#ACTIVE} or {@link LeaseState#RESET_FAILED}
 */
public record LeaseSummary(
    UUID leaseId,
    String inubitNode,
    List<Logger> loggers,
    Instant setAt,
    Instant expiresAt,
    LeaseState state,
    Optional<LeaseAttempt> lastAttempt) {

    public LeaseSummary {
        Objects.requireNonNull(leaseId, "leaseId");
        ClusterNode.checkName(inubitNode);
        loggers = List.copyOf(loggers).stream()
            .sorted(Comparator.comparing((Logger logger) -> logger.name().value()))
            .toList();
        if (loggers.isEmpty()) {
            throw new IllegalArgumentException("Invalid loggers: expected at least one");
        }
        Objects.requireNonNull(setAt, "setAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!state.open()) {
            throw new IllegalArgumentException("Only an open lease is summarized, not " + state);
        }
        Objects.requireNonNull(lastAttempt, "lastAttempt");
    }

    /** The summary of an open {@code lease}. */
    public static LeaseSummary of(Lease lease) {
        return new LeaseSummary(lease.leaseId(), lease.inubitNode(),
            lease.loggers().entrySet().stream()
                .map(entry -> new Logger(entry.getKey(), entry.getValue()))
                .toList(),
            lease.setAt(), lease.expiresAt(), lease.state(), lease.lastAttempt());
    }

    /** One raised logger and its level. */
    public record Logger(LoggerName name, TemporaryLevel level) {

        public Logger {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(level, "level");
        }
    }
}
