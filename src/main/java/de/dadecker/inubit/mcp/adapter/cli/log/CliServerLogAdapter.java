package de.dadecker.inubit.mcp.adapter.cli.log;

import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliTraceRunner;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.ServerLogKind;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.DownloadedLog;
import de.dadecker.inubit.mcp.domain.port.ServerLogPort;
import java.time.Duration;
import java.util.Objects;

/**
 * The server-log port of an 8.1/9.0 node (feature 021, research D-15, D-19): one call takes a
 * slot of the node's {@link CliConcurrencyLimiter} and runs the download of its kind, both within
 * the call deadline the service passes (start of the download phase + the node's
 * {@code cliExportTimeout}). The trace log goes through {@link CliTraceRunner}, the web service
 * log through {@link CliWebServiceLogAdapter}.
 *
 * <p>Every {@code TIMEOUT} names {@code cliExportTimeout} in its next step and adds that large
 * logs need a larger one; once the deadline has passed its message is "The {@code <log>} log
 * download from {@code <node>} did not finish within cliExportTimeout ({@code <n>})". A deadline
 * that passed before the call launches nothing. The runner stops the process tree and deletes the
 * private directory; the slot is released when the call ends.
 */
public final class CliServerLogAdapter implements ServerLogPort {

    private static final String LARGE_LOGS = "large logs need a larger cliExportTimeout";

    private final EffectiveNodeConfig server;
    private final CliTraceRunner traces;
    private final CliWebServiceLogAdapter webServiceLogs;
    private final CliConcurrencyLimiter limiter;

    public CliServerLogAdapter(EffectiveNodeConfig server, CliTraceRunner traces,
        CliWebServiceLogAdapter webServiceLogs, CliConcurrencyLimiter limiter) {
        this.server = Objects.requireNonNull(server, "server");
        this.traces = Objects.requireNonNull(traces, "traces");
        this.webServiceLogs = Objects.requireNonNull(webServiceLogs, "webServiceLogs");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
    }

    @Override
    public DownloadedLog download(ServerLogKind kind, InubitVersion version, long deadlineNanos) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(version, "version");
        Duration wait = remaining(deadlineNanos);
        if (wait.isZero()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT, exceeded(kind),
                "The earlier steps of the call took all of cliExportTimeout",
                "Retry; check get_health; raise cliExportTimeout if the INUBIT server is just"
                    + " slow; " + LARGE_LOGS).withNode(server.id()));
        }
        try (CliConcurrencyLimiter.Permit slot =
                 limiter.acquireForHelper(server, wait, "cliExportTimeout")) {
            return switch (kind) {
                case TRACE -> traces.download(remaining(deadlineNanos));
                case WEBSERVICE -> webServiceLogs.download(version, deadlineNanos);
            };
        } catch (ToolErrorException e) {
            if (e.error().code() != ErrorCode.TIMEOUT) {
                throw e;
            }
            // a cancelled call keeps its own message
            String message = remaining(deadlineNanos).isZero() ? exceeded(kind)
                : e.error().message();
            throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT, message,
                e.error().likelyCause(), e.error().nextStep() + "; " + LARGE_LOGS)
                .withNode(server.id()));
        }
    }

    private String exceeded(ServerLogKind kind) {
        return "The " + kind.wireName() + " log download from " + server.id()
            + " did not finish within cliExportTimeout ("
            + Durations.human(server.cliExportTimeout()) + ")";
    }

    private static Duration remaining(long deadlineNanos) {
        long left = deadlineNanos - System.nanoTime();
        return left > 0 ? Duration.ofNanos(left) : Duration.ZERO;
    }
}
