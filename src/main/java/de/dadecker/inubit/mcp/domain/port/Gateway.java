package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import java.util.Optional;

/**
 * The adapter set of one INUBIT server, selected by its version line (Constitution V, FR-029).
 *
 * <p><b>Extension point:</b> the user-story phases add one accessor per port here:
 * {@link #monitoring()} (US1), {@link #processes()} and {@link #logs()} (US2),
 * {@link #inventory()} (US3), {@link #processControl()} (US4), feature 003 adds
 * {@link #artifacts()}, feature 004 {@link #imports()} and {@link #tags()}, feature 018
 * {@link #partners()} and feature 020 {@link #queueEntryContent()}, implemented by the
 * version-specific adapters.
 * Application services obtain gateways only through {@link GatewayFactory}, so supporting 9.x is an
 * adapter change, not a change of the tool logic.
 */
public interface Gateway {

    /** Adapter lines that exist; 9.x servers currently use {@link #V8_1} (FR-029). */
    enum AdapterLine {
        V8_1
    }

    NodeId node();

    /** The adapter line in use for this server. */
    AdapterLine adapterLine();

    /** The INUBIT version read from the server when {@code versionLine} is {@code AUTO}. */
    Optional<String> detectedVersion();

    /** Warnings to show with results of this server; version warnings come from the gate. */
    List<String> warnings();

    /** Health and monitoring of this server (US1). */
    MonitoringPort monitoring();

    /** Process instances of this server (US2). */
    ProcessQueryPort processes();

    /** The INUBIT logs of this server (US2). */
    LogPort logs();

    /** The diagram and module inventory of this server (US3). */
    InventoryPort inventory();

    /** Restart and kill of process instances on this server (US4). */
    ProcessControlPort processControl();

    /**
     * Read-only artifact exports of this server (feature 003). The default is for gateways
     * without an artifact adapter.
     *
     * @throws ToolErrorException {@code CLI_UNAVAILABLE} with the server id, unless the
     *     version-specific gateway provides an adapter
     */
    default ArtifactPort artifacts() {
        throw new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "Artifact exports are not available for " + node(),
            "This adapter line has no artifact adapter",
            "Use a node with an INUBIT 8.1 CLI installation (cliHome)").withNode(node()));
    }

    /**
     * Imports into this server (feature 004). The default is for gateways without an import
     * adapter.
     *
     * @throws ToolErrorException {@code CLI_UNAVAILABLE} with the server id, unless the
     *     version-specific gateway provides an adapter
     */
    default ImportPort imports() {
        throw new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "Imports are not available for " + node(),
            "This adapter line has no import adapter",
            "Use a node with an INUBIT 8.1 CLI installation (cliHome)").withNode(node()));
    }

    /**
     * Tags on this server (feature 004). The default is for gateways without a tag adapter.
     *
     * @throws ToolErrorException {@code CLI_UNAVAILABLE} with the server id, unless the
     *     version-specific gateway provides an adapter
     */
    default TagPort tags() {
        throw new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "Tags are not available for " + node(),
            "This adapter line has no tag adapter",
            "Use a node with an INUBIT 8.1 CLI installation (cliHome)").withNode(node()));
    }

    /**
     * The Partner Managements of this server's system diagrams (feature 018, research D-4). The
     * default is for gateways without a partner management adapter.
     *
     * @throws ToolErrorException {@code UNSUPPORTED_VERSION} with the server id, unless the
     *     version-specific gateway provides an adapter
     */
    default PartnerManagementPort partners() {
        throw new ToolErrorException(ToolError.of(ErrorCode.UNSUPPORTED_VERSION,
            "partner management is not available for this INUBIT version on " + node(),
            "This adapter line has no partner management adapter",
            "Call list_nodes for what this node supports").withNode(node()));
    }

    /**
     * The content of Queue Manager entries on this server, read over the StartCLI client
     * (feature 020, research D-17). The default is for gateways without a queue-entry adapter.
     *
     * @throws ToolErrorException {@code CLI_UNAVAILABLE} with the server id, unless the
     *     version-specific gateway provides an adapter
     */
    default QueueEntryContentPort queueEntryContent() {
        throw new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "Queue entry reads are not available for " + node(),
            "This adapter line has no queue-entry adapter",
            "Use a node with an INUBIT 8.1 CLI installation (cliHome)").withNode(node()));
    }
}
