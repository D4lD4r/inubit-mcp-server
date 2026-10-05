package de.dadecker.inubit.mcp.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One Queue Manager entry (data-model.md → ProcessInstance; source in 8.1: the REST
 * {@code queueLog}, research R-8). The same {@code processId} occurs in several rows for a
 * process with sub-workflows; rows are keyed by {@code (processId, workflow, module)}.
 *
 * @param processId       {@code workflowId}: the id that restart/kill take
 * @param globalProcessId {@code globalPId}: equals {@code processId} for top-level processes, the
 *                        caller's id or a UUID for sub-workflow rows
 * @param rawState        the INUBIT {@code status} value, e.g. {@code Error}
 * @param since           when the entry entered the Queue Manager in its current state; for
 *                        {@code ERROR} the time of the error (FR-013)
 * @param timeInState     {@code now - since}, never negative
 * @param hanging         {@code state} is {@link ProcessState#NON_FINAL} and {@code timeInState}
 *                        exceeds the threshold (FR-010)
 * @param inubitNode      the INUBIT {@code node} column of the Queue Manager row (renamed in
 *                        feature 002, because {@code node} now names the configured target)
 */
public record ProcessInstance(
    NodeId node,
    String processId,
    Optional<String> globalProcessId,
    ProcessState state,
    String rawState,
    Instant since,
    Duration timeInState,
    boolean hanging,
    Optional<String> workflow,
    Optional<String> module,
    Optional<String> moduleType,
    Optional<String> tag,
    Optional<String> inubitNode,
    Optional<String> owner,
    Optional<String> priority) {

    public ProcessInstance {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(processId, "processId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(rawState, "rawState");
        Objects.requireNonNull(since, "since");
        Objects.requireNonNull(timeInState, "timeInState");
        globalProcessId = orEmpty(globalProcessId);
        workflow = orEmpty(workflow);
        module = orEmpty(module);
        moduleType = orEmpty(moduleType);
        tag = orEmpty(tag);
        inubitNode = orEmpty(inubitNode);
        owner = orEmpty(owner);
        priority = orEmpty(priority);
    }

    /**
     * A copy with {@code timeInState} and {@code hanging} computed for {@code now} and
     * {@code threshold} (FR-010).
     */
    public ProcessInstance assessedAt(Instant now, Duration threshold) {
        return new ProcessInstance(node, processId, globalProcessId, state, rawState, since,
            timeInState(since, now), hanging(state, since, now, threshold), workflow, module,
            moduleType, tag, inubitNode, owner, priority);
    }

    /** {@code now - since}, at least zero and truncated to seconds. */
    public static Duration timeInState(Instant since, Instant now) {
        Duration elapsed = Duration.between(since, now);
        return elapsed.isNegative() ? Duration.ZERO : elapsed.withNanos(0);
    }

    /**
     * The hanging rule (FR-010): a non-final state for longer than the threshold, decided on the
     * exact (untruncated) time since {@code since}.
     */
    public static boolean hanging(ProcessState state, Instant since, Instant now,
        Duration threshold) {
        return state.nonFinal() && Duration.between(since, now).compareTo(threshold) > 0;
    }

    private static <T> Optional<T> orEmpty(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }
}
