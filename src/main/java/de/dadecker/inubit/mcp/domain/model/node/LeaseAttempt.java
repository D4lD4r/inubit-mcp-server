package de.dadecker.inubit.mcp.domain.model.node;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** The last reset attempt of a lease that did not end it (research D-12, D-16). */
public record LeaseAttempt(
    Instant at, AuditOutcome outcome, Optional<ErrorCode> code, Optional<LeaseReason> reason) {

    public LeaseAttempt {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(reason, "reason");
    }
}
