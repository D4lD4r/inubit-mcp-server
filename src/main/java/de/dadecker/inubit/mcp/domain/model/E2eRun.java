package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The result of {@code run_e2e_test} (feature 004, US5, FR-022 – FR-024, research D-17,
 * data-model.md → E2eRun): the answer of the SOAP endpoint and what INUBIT did with the
 * message — found by the test id, or, without a match, the target workflow's instances in the
 * test's time window, marked uncertain.
 *
 * @param testId       the value of the {@code X-Inubit-Mcp-Test-Id} header
 * @param status       the HTTP status; absent on timeout
 * @param responseFile the workspace-relative file with the response body
 * @param excerpt      at most 2 KB of the response, only if requested
 * @param truncated    true if a list or the excerpt was cut
 */
public record E2eRun(UUID auditId, String testId, String endpoint, Optional<Integer> status,
    long durationMs, boolean timedOut, Optional<String> responseFile, Optional<String> excerpt,
    Correlation correlation, List<ProcessInstance> processes, List<LogEntry> errors,
    List<LogEntry> logEntries, boolean truncated, List<String> warnings) {

    /** How the instances and log entries were found. */
    public enum Correlation {
        BY_TEST_ID,
        TIME_WINDOW_UNCERTAIN
    }

    public E2eRun {
        Objects.requireNonNull(auditId, "auditId");
        Objects.requireNonNull(testId, "testId");
        Objects.requireNonNull(endpoint, "endpoint");
        status = status == null ? Optional.empty() : status;
        responseFile = responseFile == null ? Optional.empty() : responseFile;
        excerpt = excerpt == null ? Optional.empty() : excerpt;
        Objects.requireNonNull(correlation, "correlation");
        processes = List.copyOf(processes);
        errors = List.copyOf(errors);
        logEntries = List.copyOf(logEntries);
        warnings = List.copyOf(warnings);
    }
}
