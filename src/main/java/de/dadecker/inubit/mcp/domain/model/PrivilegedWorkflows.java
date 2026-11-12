package de.dadecker.inubit.mcp.domain.model;

import java.util.List;

/**
 * The share of workflow threads reserved for privileged workflows and the privileged workflows
 * (data-model.md → ServerSettings).
 *
 * @param percentage the reserved share of the workflow threads, 0 … 100
 * @param workflows  the names of the privileged workflows, at most {@value #MAX_WORKFLOWS}
 * @param truncated  true if INUBIT named more workflows than {@code workflows} holds
 */
public record PrivilegedWorkflows(int percentage, List<String> workflows, boolean truncated) {

    public static final int MAX_WORKFLOWS = 200;

    public PrivilegedWorkflows {
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("percentage must be 0 … 100: " + percentage);
        }
        workflows = List.copyOf(workflows);
        if (workflows.size() > MAX_WORKFLOWS) {
            throw new IllegalArgumentException(
                "more than " + MAX_WORKFLOWS + " workflows: " + workflows.size());
        }
    }

    /** All of {@code workflows}, cut to the first {@value #MAX_WORKFLOWS} names. */
    public static PrivilegedWorkflows of(int percentage, List<String> workflows) {
        boolean truncated = workflows.size() > MAX_WORKFLOWS;
        return new PrivilegedWorkflows(percentage,
            truncated ? workflows.subList(0, MAX_WORKFLOWS) : workflows, truncated);
    }
}
