package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The settings of one server (data-model.md → ServerSettings, US1). A fact that could not be
 * read is empty, and its part is listed in {@code unavailable} with the reason (FR-013).
 *
 * @param node                the server the settings belong to
 * @param license             license facts (part {@code license})
 * @param workflowThreads     the configured workflow threads (part {@code threads})
 * @param privilegedWorkflows the share of threads for privileged workflows and their names
 *                            (part {@code privilegedWorkflows})
 * @param dbConnectionTolerance the DB connection tolerance (part {@code dbConnectionTolerance})
 * @param blockingQueueSize   the maximum blocking queue size, from the {@code /metrics} call
 *                            {@code get_health} uses
 * @param version             the version string, from the same source as {@code get_health}
 * @param memoryUsedPercent   the memory share in use, from the same source as {@code get_health}
 * @param maintenanceMode     the maintenance mode, from the same source as {@code get_health}
 * @param serverTime          the server time as the server shows it (part {@code serverTime})
 * @param configuration       the server configuration, only when asked for (part
 *                            {@code configuration})
 * @param sources             the channel each returned fact came from, by field name (FR-009),
 *                            e.g. {@code licensedThreads → REST}
 * @param unavailable         parts that could not be obtained, with the reason
 */
public record ServerSettings(
    NodeId node,
    Optional<LicenseFacts> license,
    Optional<Long> workflowThreads,
    Optional<PrivilegedWorkflows> privilegedWorkflows,
    Optional<DbConnectionTolerance> dbConnectionTolerance,
    Optional<Long> blockingQueueSize,
    Optional<String> version,
    Optional<Double> memoryUsedPercent,
    Optional<Boolean> maintenanceMode,
    Optional<String> serverTime,
    Optional<ConfigurationView> configuration,
    Map<String, Channel> sources,
    List<UnavailablePart> unavailable) {

    public ServerSettings {
        Objects.requireNonNull(node, "node");
        license = orEmpty(license);
        workflowThreads = orEmpty(workflowThreads);
        privilegedWorkflows = orEmpty(privilegedWorkflows);
        dbConnectionTolerance = orEmpty(dbConnectionTolerance);
        blockingQueueSize = orEmpty(blockingQueueSize);
        version = orEmpty(version);
        memoryUsedPercent = orEmpty(memoryUsedPercent);
        maintenanceMode = orEmpty(maintenanceMode);
        serverTime = orEmpty(serverTime);
        configuration = orEmpty(configuration);
        sources = Map.copyOf(sources);
        unavailable = List.copyOf(unavailable);
    }

    private static <T> Optional<T> orEmpty(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }
}
