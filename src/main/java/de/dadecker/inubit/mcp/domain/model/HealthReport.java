package de.dadecker.inubit.mcp.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Health of one server (data-model.md → HealthReport, FR-005 – FR-008).
 *
 * @param checkedAt   the INUBIT healthcheck timestamp, or the MCP server's clock if INUBIT gave
 *                    none
 * @param reachable   true if {@code /healthcheck} gave any HTTP response
 * @param processCounts the process instances per state, reported for INUBIT 6.1 only
 *                    (feature 011, FR-008)
 * @param client      the node's StartCLI client (feature 009, FR-002); empty while it is not
 *                    known, the part {@code client} in {@code unavailable} then says why
 * @param unavailable parts that could not be obtained, with the reason (FR-008)
 * @param error       set when {@code reachable=false}
 * @param warnings    e.g. maintenance mode, an unsupported version line (FR-029)
 */
public record HealthReport(
    NodeId node,
    GroupId group,
    Instant checkedAt,
    boolean reachable,
    HealthStatus status,
    Optional<Boolean> ready,
    Optional<String> readyMessage,
    Optional<Boolean> maintenanceMode,
    Optional<String> version,
    Optional<SystemInfo> systemInfo,
    Optional<LoadFigures> load,
    Optional<ProcessCounts> processCounts,
    Optional<Client> client,
    List<UnavailablePart> unavailable,
    Optional<ToolError> error,
    List<String> warnings) {

    public HealthReport {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(checkedAt, "checkedAt");
        Objects.requireNonNull(status, "status");
        ready = orEmpty(ready);
        readyMessage = orEmpty(readyMessage);
        maintenanceMode = orEmpty(maintenanceMode);
        version = orEmpty(version);
        systemInfo = orEmpty(systemInfo);
        load = orEmpty(load);
        processCounts = orEmpty(processCounts);
        client = orEmpty(client);
        unavailable = List.copyOf(unavailable);
        error = orEmpty(error);
        warnings = List.copyOf(warnings);
        if (!reachable && error.isEmpty()) {
            throw new IllegalArgumentException("an unreachable node's report needs an error");
        }
    }

    /**
     * The StartCLI client of a node (FR-002, FR-011): its version, declared range and the
     * comparison with the server version ({@code match}, {@code mismatch},
     * {@code not_comparable}), or only {@code state} {@code not_available} for a node without a
     * StartCLI installation.
     */
    public record Client(Optional<String> version, Optional<String> supportedFrom,
        Optional<String> supportedTo, Optional<String> comparison, Optional<String> state) {

        /** A node without a StartCLI installation: no probe runs. */
        public static final Client NOT_AVAILABLE = new Client(Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.of("not_available"));

        public Client {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(supportedFrom, "supportedFrom");
            Objects.requireNonNull(supportedTo, "supportedTo");
            Objects.requireNonNull(comparison, "comparison");
            Objects.requireNonNull(state, "state");
        }

        /** A probed client compared with the server version. */
        public static Client of(ClientVersion client, ClientVersion.Comparison comparison) {
            return new Client(Optional.of(client.version().raw()),
                client.supportedFrom().map(InubitVersion::raw),
                client.supportedTo().map(InubitVersion::raw),
                Optional.of(comparison.name().toLowerCase(Locale.ROOT)), Optional.empty());
        }
    }

    private static <T> Optional<T> orEmpty(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }
}
