package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The result of an executed (or attempted) restart or kill (data-model.md →
 * ProcessControlResult). Refusals before execution are tool errors, not results.
 *
 * @param stateBefore the instance's state read just before the action
 * @param stateAfter  re-read after the action: a {@link ProcessState} name, or
 *                    {@value #NOT_IN_QUEUE} if the instance has left the Queue Manager; empty if
 *                    the re-read failed
 * @param message     INUBIT's confirmation text, or the failure (code, message, next step)
 * @param auditId     the id of the execution's audit records (pending and final)
 */
public record ProcessControlResult(NodeId node, ProcessAction action, String processId,
    Outcome outcome, ProcessState stateBefore, Optional<String> stateAfter, String message,
    UUID auditId) {

    /** {@code stateAfter} of an instance that is no longer in the Queue Manager. */
    public static final String NOT_IN_QUEUE = "NOT_IN_QUEUE";

    /** {@code EXECUTED}: INUBIT confirmed the action; {@code FAILED}: it did not. */
    public enum Outcome {
        EXECUTED,
        REFUSED,
        FAILED
    }

    public ProcessControlResult {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(processId, "processId");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(stateBefore, "stateBefore");
        stateAfter = stateAfter == null ? Optional.empty() : stateAfter;
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(auditId, "auditId");
    }
}
