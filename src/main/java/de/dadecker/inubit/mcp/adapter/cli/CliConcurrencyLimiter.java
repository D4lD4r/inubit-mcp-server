package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * At most {@link #PERMITS} concurrent StartCLI runs that contact the node, per node pinned to a
 * {@linkplain CapabilityMatrix#isCliOnly CLI-only} version (feature 012, research D-9, FR-015).
 * A further run waits up to the node's {@code cliTimeout}, then fails with {@code TIMEOUT}. Other
 * nodes get no limit. The local client probes ({@link CliVersionProbe}, {@link CliClientProbe})
 * take no permit; the {@link de.dadecker.inubit.mcp.adapter.CredentialGuard} applies on top.
 * Helper runs (feature 020, research D-14, FR-016) take the same per-node permits on every
 * version line; StartCLI runs on other nodes still take none.
 */
public final class CliConcurrencyLimiter {

    /** Concurrent StartCLI runs per CLI-only node. */
    public static final int PERMITS = 2;

    private final CapabilityMatrix matrix;
    private final Map<NodeId, Semaphore> semaphores = new ConcurrentHashMap<>();

    public CliConcurrencyLimiter(CapabilityMatrix matrix) {
        this.matrix = matrix;
    }

    /**
     * A permit for one StartCLI run on {@code server}; close it when the run is over. Returns at
     * once for a node not pinned to a CLI-only version.
     *
     * @throws ToolErrorException {@code TIMEOUT} if no permit is free within the node's
     *     {@code cliTimeout} or the caller is interrupted while waiting
     */
    public Permit acquire(EffectiveNodeConfig server) {
        if (!matrix.isCliOnly(NodeVersionState.initial(server.versionLine()).effectiveVersion())) {
            return new Permit(null);
        }
        return take(server, server.cliTimeout());
    }

    /**
     * A permit for one helper run on {@code server}, on any version line; close it when the run
     * is over. Shares the permits with {@link #acquire} on the same node.
     *
     * @throws ToolErrorException {@code TIMEOUT} if no permit is free within {@code wait} or the
     *     caller is interrupted while waiting
     */
    public Permit acquireForHelper(EffectiveNodeConfig server, Duration wait) {
        return take(server, wait);
    }

    private Permit take(EffectiveNodeConfig server, Duration wait) {
        Semaphore semaphore = semaphores.computeIfAbsent(server.id(),
            id -> new Semaphore(PERMITS, true));
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(wait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
                "The StartCLI call on " + server.id()
                    + " was cancelled while waiting for a free StartCLI slot",
                "The overall deadline of the request expired", "Retry").withNode(server.id()));
        }
        if (!acquired) {
            throw new ToolErrorException(ToolError.of(ErrorCode.TIMEOUT,
                "No StartCLI slot on " + server.id() + " became free within "
                    + Durations.human(wait),
                PERMITS + " other StartCLI runs on the node are still running (the INUBIT server"
                    + " is slow or unreachable)",
                "Retry later; check get_health; raise cliTimeout if the INUBIT server is just slow")
                .withNode(server.id()));
        }
        return new Permit(semaphore);
    }

    /** One StartCLI run's slot; closing releases it once. */
    public static final class Permit implements AutoCloseable {

        /** {@code null} for a node without a limit. */
        private final Semaphore semaphore;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (semaphore != null && closed.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }
}
