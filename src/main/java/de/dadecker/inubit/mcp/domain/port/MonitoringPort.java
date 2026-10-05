package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.HealthStatus;
import de.dadecker.inubit.mcp.domain.model.LoadFigures;
import de.dadecker.inubit.mcp.domain.model.SystemInfo;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Monitoring of one INUBIT server (research R-10): the four calls behind {@code get_health}.
 * Each returns its result or throws a
 * {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException} carrying the
 * {@link de.dadecker.inubit.mcp.domain.model.ToolError} (with the server id). The methods
 * are thread-safe, so that the health service can call them concurrently.
 */
public interface MonitoringPort {

    /**
     * The unauthenticated healthcheck. Any HTTP response counts as reachable; a transport failure
     * ({@code UNREACHABLE}, {@code TIMEOUT}, {@code TLS_ERROR}) is thrown.
     */
    Healthcheck healthcheck();

    /** The unauthenticated readiness check: HTTP 200 = ready, 503 = not ready. */
    Readiness ready();

    /** The authenticated system information (version, platform, tracing). */
    SystemInfo systemInfo();

    /**
     * The authenticated load metrics. A failure that indicates a missing license (R-10) is
     * {@link Metrics.NotLicensed}, not an exception.
     */
    Metrics metrics();

    /**
     * True once INUBIT accepted this server's credentials and has not rejected them since. While
     * false, callers run the authenticated calls one after the other, so that wrong credentials
     * cost at most one failed login (Phase 3 review M2).
     */
    boolean credentialsConfirmed();

    /**
     * @param httpStatus      the HTTP status of the healthcheck response
     * @param maintenanceMode empty if the response did not state it
     * @param timestamp       the server's clock, if the response carried a parseable timestamp
     * @param problem         why status or maintenance mode are missing, e.g. a non-JSON body
     */
    record Healthcheck(int httpStatus, HealthStatus status, Optional<Boolean> maintenanceMode,
        Optional<Instant> timestamp, Optional<String> problem) {

        public Healthcheck {
            Objects.requireNonNull(status, "status");
            maintenanceMode = maintenanceMode == null ? Optional.empty() : maintenanceMode;
            timestamp = timestamp == null ? Optional.empty() : timestamp;
            problem = problem == null ? Optional.empty() : problem;
        }
    }

    record Readiness(boolean ready, Optional<String> message) {

        public Readiness {
            message = message == null ? Optional.empty() : message;
        }
    }

    /** The outcome of the metrics call. */
    sealed interface Metrics {

        record Available(LoadFigures figures) implements Metrics {
            public Available {
                Objects.requireNonNull(figures, "figures");
            }
        }

        /** @param reason what INUBIT answered, including the HTTP status */
        record NotLicensed(String reason) implements Metrics {
            public NotLicensed {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }
}
