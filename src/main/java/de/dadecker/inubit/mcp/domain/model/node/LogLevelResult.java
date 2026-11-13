package de.dadecker.inubit.mcp.domain.model.node;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The result of the log-level tools (feature 024, data-model "Results"; shapes per
 * contracts/mcp-tools-delta.md): the preview of a call confirmed by the MCP server, or the
 * outcome of an execution.
 */
public sealed interface LogLevelResult {

    /**
     * Nothing was changed; the call is repeated with {@code confirmationCode} before
     * {@code expiresAt}.
     */
    record Challenge(String confirmationCode, Instant expiresAt, Preview preview, String message)
        implements LogLevelResult {

        public Challenge {
            Objects.requireNonNull(confirmationCode, "confirmationCode");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(preview, "preview");
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * What a set would change.
     *
     * @param inubitNode the INUBIT node that answered the own-node lookup
     * @param loggers    the requested loggers with the requested level, in request order
     * @param openLease  the lease open for the server at the preview
     */
    record Preview(NodeId node, String inubitNode, List<LeaseSummary.Logger> loggers,
        int durationMinutes, Optional<LeaseSummary> openLease) {

        public Preview {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(inubitNode, "inubitNode");
            loggers = List.copyOf(loggers);
            Objects.requireNonNull(openLease, "openLease");
        }
    }

    /** {@code outcome} of an execution. */
    enum Status {
        EXECUTED,
        FAILED
    }

    /**
     * An execution.
     *
     * @param leaseId   the lease the call recorded, merged into or ended
     * @param loggers   every logger of that lease
     * @param expiresAt the lease's expiry, while it is open
     * @param leaseOpen whether that lease is open ({@code ACTIVE} or {@code RESET_FAILED}) now
     * @param warnings  the version gate's warnings, then those of the call
     */
    record Outcome(NodeId node, String inubitNode, Status outcome, Optional<UUID> leaseId,
        List<LeaseSummary.Logger> loggers, Optional<Instant> expiresAt, boolean leaseOpen,
        String message, UUID auditId, List<String> warnings) implements LogLevelResult {

        public Outcome {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(inubitNode, "inubitNode");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(leaseId, "leaseId");
            loggers = List.copyOf(loggers);
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(auditId, "auditId");
            warnings = List.copyOf(warnings);
        }
    }
}
