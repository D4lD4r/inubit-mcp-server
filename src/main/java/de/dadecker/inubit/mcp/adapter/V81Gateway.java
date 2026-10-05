package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The 8.1 adapter set of one server, on the server's REST client (owned and closed by
 * {@link AdapterGatewayFactory}); the user-story phases add their 8.1 port adapters here, built
 * from {@link #client()}.
 */
final class V81Gateway implements Gateway {

    private final NodeId server;
    private final Optional<String> detectedVersion;
    private final List<String> warnings;
    private final InubitHttpClient client;
    private final MonitoringPort monitoring;
    private final ProcessQueryPort processes;
    private final LogPort logs;
    private final InventoryPort inventory;
    private final ProcessControlPort processControl;
    private final CredentialGuard guard;

    /**
     * @param monitoring the server's monitoring adapter, shared with
     *     {@link AdapterGatewayFactory#monitoring}
     * @param ports      the server's 8.1 ports, shared with {@link AdapterGatewayFactory}
     * @param guard      the server's credential guard, shared by REST and CLI calls
     */
    V81Gateway(NodeId server, Optional<String> detectedVersion, List<String> warnings,
        InubitHttpClient client, MonitoringPort monitoring, Ports ports,
        CredentialGuard guard) {
        this.server = Objects.requireNonNull(server, "server");
        this.detectedVersion = Objects.requireNonNull(detectedVersion, "detectedVersion");
        this.warnings = List.copyOf(warnings);
        this.client = Objects.requireNonNull(client, "client");
        this.monitoring = Objects.requireNonNull(monitoring, "monitoring");
        this.processes = Objects.requireNonNull(ports, "ports").processes();
        this.logs = ports.logs();
        this.inventory = ports.inventory();
        this.processControl = ports.processControl();
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @Override
    public NodeId node() {
        return server;
    }

    @Override
    public AdapterLine adapterLine() {
        return AdapterLine.V8_1;
    }

    @Override
    public Optional<String> detectedVersion() {
        return detectedVersion;
    }

    @Override
    public List<String> warnings() {
        return warnings;
    }

    @Override
    public MonitoringPort monitoring() {
        return monitoring;
    }

    @Override
    public ProcessQueryPort processes() {
        return processes;
    }

    @Override
    public LogPort logs() {
        return logs;
    }

    @Override
    public InventoryPort inventory() {
        return inventory;
    }

    @Override
    public ProcessControlPort processControl() {
        return processControl;
    }

    /**
     * The ports of US2 (REST), US3 (REST and CLI exports) and US4 (CLI), created once per server
     * with its client and credential guard.
     */
    record Ports(ProcessQueryPort processes, LogPort logs, InventoryPort inventory,
        ProcessControlPort processControl) {
        Ports {
            Objects.requireNonNull(processes, "processes");
            Objects.requireNonNull(logs, "logs");
            Objects.requireNonNull(inventory, "inventory");
            Objects.requireNonNull(processControl, "processControl");
        }
    }

    /** The credential guard of this server, for its CLI calls (US3/US4). */
    CredentialGuard credentialGuard() {
        return guard;
    }

    /** The REST client of this server (with the 8.1 maintenance probe). */
    InubitHttpClient client() {
        return client;
    }

    @Override
    public String toString() {
        return "V81Gateway[" + server + "]";
    }
}
