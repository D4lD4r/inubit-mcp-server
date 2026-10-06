package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.util.Optional;

/**
 * Hands out the {@link Gateway} (adapter set) of a configured server. Implementations resolve the
 * version line lazily and cache the gateway per server, so that a server that is down does not
 * affect the start-up or the other servers.
 */
public interface GatewayFactory {

    /**
     * The gateway of {@code server}. A failed version detection does not fail this call: the 8.1
     * adapters are returned with a warning; that fallback is kept for a short time (60 s in
     * {@code AdapterGatewayFactory}) before the detection is retried, so that rejected
     * credentials are not tried again on every call.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException
     *     {@code TARGET_UNKNOWN} for a server that is not configured, {@code TLS_ERROR} if
     *     the server's trust store or certificate pin cannot be used
     */
    Gateway forServer(NodeId server);

    /**
     * The monitoring port of {@code server} at once, without waiting for a version detection
     * (Phase 3 review H1): the health endpoints are needed to find out whether a server is up,
     * and {@code /system/info} is the detection itself. For {@code AUTO} servers, the result of
     * this port's {@link MonitoringPort#systemInfo()} completes the detection (a real failure
     * counts as a failed detection), so that health checks do not call {@code /system/info}
     * twice.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException as
     *     {@link #forServer}, but never because of the version detection
     */
    MonitoringPort monitoring(NodeId server);

    /**
     * The process query port of {@code server} at once, without waiting for a version detection
     * (same rule as {@link #monitoring}): only the 8.1 adapters exist, so the detection cannot
     * change the adapter. When a 9.x adapter line is added, this method must wait for the
     * detection (bounded by the server timeout) instead.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException as
     *     {@link #monitoring}
     */
    ProcessQueryPort processes(NodeId server);

    /** The log port of {@code server} at once; see {@link #processes}. */
    LogPort logs(NodeId server);

    /**
     * The inventory port of {@code server} at once, without waiting for a version detection; see
     * {@link #processes} (US3, its CLI exports share the server's credential guard).
     */
    InventoryPort inventory(NodeId server);

    /**
     * The process control port of {@code server} at once, without waiting for a version
     * detection; see {@link #processes} (US4, its StartCLI calls share the server's credential
     * guard).
     */
    ProcessControlPort processControl(NodeId server);

    /**
     * The artifact port of {@code server} (feature 003); the default asks the gateway, which
     * may detect the version first.
     */
    default ArtifactPort artifacts(NodeId server) {
        return forServer(server).artifacts();
    }

    /**
     * The gateway of {@code server} if its adapter set is known without contacting the server:
     * a configured version line, or a detected (or cached fallback) {@code AUTO} version. Never
     * blocks on a running detection.
     */
    Optional<Gateway> knownGateway(NodeId server);
}
