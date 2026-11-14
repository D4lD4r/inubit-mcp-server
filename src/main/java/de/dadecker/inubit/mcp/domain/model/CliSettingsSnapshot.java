package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What one StartCLI settings session read on an INUBIT 8.1/9.0 node (feature 022, US1, research
 * D-7): the parts of {@link ServerSettings} that come from StartCLI. A part that was asked for is
 * either present or in {@code unavailable} (FR-013); a part that was not asked for is neither.
 *
 * @param workflowThreads       {@link ServerSettings#workflowThreads()}
 * @param privilegedWorkflows   {@link ServerSettings#privilegedWorkflows()}
 * @param dbConnectionTolerance {@link ServerSettings#dbConnectionTolerance()}
 * @param serverTime            {@link ServerSettings#serverTime()}
 * @param unavailable           the parts whose answer could not be read, with the reason
 */
public record CliSettingsSnapshot(Optional<Long> workflowThreads,
    Optional<PrivilegedWorkflows> privilegedWorkflows,
    Optional<DbConnectionTolerance> dbConnectionTolerance, Optional<String> serverTime,
    List<UnavailablePart> unavailable) {

    public CliSettingsSnapshot {
        Objects.requireNonNull(workflowThreads, "workflowThreads");
        Objects.requireNonNull(privilegedWorkflows, "privilegedWorkflows");
        Objects.requireNonNull(dbConnectionTolerance, "dbConnectionTolerance");
        Objects.requireNonNull(serverTime, "serverTime");
        unavailable = List.copyOf(unavailable);
    }
}
