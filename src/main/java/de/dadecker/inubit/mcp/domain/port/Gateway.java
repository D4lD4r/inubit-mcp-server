package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.util.List;
import java.util.Optional;

/**
 * The adapter set of one INUBIT server, selected by its version line (Constitution V, FR-029).
 *
 * <p><b>Extension point:</b> the user-story phases add one accessor per port here:
 * {@link #monitoring()} (US1), {@link #processes()} and {@link #logs()} (US2),
 * {@link #inventory()} (US3), {@link #processControl()} (US4), implemented by the
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

    /** Warnings to show with results of this server, e.g. an unsupported version line. */
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
}
