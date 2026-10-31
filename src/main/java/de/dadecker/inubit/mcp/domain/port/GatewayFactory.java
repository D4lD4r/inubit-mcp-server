package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.CredentialState;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.time.Duration;
import java.util.Optional;

/**
 * Hands out the {@link Gateway} (adapter set) of a configured server. Implementations resolve the
 * version line lazily and cache the gateway per server, so that a server that is down does not
 * affect the start-up or the other servers.
 */
public interface GatewayFactory {

    /**
     * The gateway of {@code server}. A failed version detection does not fail this call: the 8.1
     * adapters are returned; that fallback is kept for a short time (60 s in
     * {@code AdapterGatewayFactory}) before the detection is retried, so that rejected
     * credentials are not tried again on every call.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException
     *     {@code TARGET_UNKNOWN} for a server that is not configured, {@code TLS_ERROR} if
     *     the server's trust store or certificate pin cannot be used
     */
    Gateway forServer(NodeId server);

    /**
     * Detects the INUBIT version of {@code server} now (feature 009 research D-5): one
     * {@code /system/info} request, also when a version is already cached and also within the
     * 60 s fallback window of {@link #forServer} (that window only holds the adapter selection;
     * the caller owns the back-off of detections). The outcome is reported to the
     * {@link VersionObserver}, except for a cancelled (interrupted) call.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException the detection's error (with
     *     the server id), {@code UNEXPECTED_RESPONSE} for a version without a parsable
     *     major.minor, or as {@link #forServer}
     */
    InubitVersion detect(NodeId server);

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

    /** The import port of {@code server} (feature 004); the default asks the gateway. */
    default ImportPort imports(NodeId server) {
        return forServer(server).imports();
    }

    /** The tag port of {@code server} (feature 004); the default asks the gateway. */
    default TagPort tags(NodeId server) {
        return forServer(server).tags();
    }

    /**
     * Whether the credentials of {@code server} are held already, so that taking one of its
     * ports does not wait for them (feature 010, research R-8): environment credentials always
     * are, Parameter Store credentials once their first fetch succeeded. Never starts a fetch
     * and never waits. The default, for a factory whose credentials are all known at start-up,
     * is {@code true}.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException
     *     {@code TARGET_UNKNOWN} for a server that is not configured
     */
    default boolean credentialsHeld(NodeId server) {
        return true;
    }

    /**
     * The server-independent input checks of the diagnosis queries for {@code server}, chosen by
     * its version as its ports would be, without its credentials or client (feature 011, review
     * T013-T014 r1). The default, for a factory whose ports need no credentials, asks the ports.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException
     *     {@code TARGET_UNKNOWN} for a server that is not configured
     */
    default QueryRules queryRules(NodeId server) {
        return new QueryRules() {
            @Override
            public void validate(de.dadecker.inubit.mcp.domain.model.LogQuery query) {
                logs(server).validate(query);
            }

            @Override
            public void validate(de.dadecker.inubit.mcp.domain.model.ProcessQuery query) {
                processes(server).validate(query);
            }
        };
    }

    /**
     * Makes the credentials of {@code server} available, waiting at most {@code bound} (feature
     * 010, research R-9): {@code get_health} asks this first, so that a first fetch from AWS
     * Parameter Store cannot outlast its time budget. Starts the fetch if none is running, also
     * for a zero bound; a fetch that outlives {@code bound} is not cancelled ({@code PENDING}),
     * the next call uses its result. The default, for a factory whose credentials are all known
     * at start-up, is {@code READY}.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException the classified failure of
     *     the fetch (also at once within its back-off), or {@code TARGET_UNKNOWN} for a server
     *     that is not configured
     */
    default CredentialState prepareCredentials(NodeId server, Duration bound) {
        return CredentialState.READY;
    }

    /**
     * The gateway of {@code server} if its adapter set is known without contacting the server:
     * a configured version line, or a detected (or cached fallback) {@code AUTO} version. Never
     * blocks on a running detection.
     */
    Optional<Gateway> knownGateway(NodeId server);
}
