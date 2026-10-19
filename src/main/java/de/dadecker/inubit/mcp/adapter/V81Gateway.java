package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The gateway of one server, on the server's REST client (owned and closed by
 * {@link AdapterGatewayFactory}). Its ports are those of the {@link PortSet} that the factory
 * currently selects for the node's effective version (feature 009 research D-16); in production
 * that is always the 8.1 port set.
 */
final class V81Gateway implements Gateway {

    private final NodeId server;
    private final Optional<String> detectedVersion;
    private final List<String> warnings;
    private final InubitHttpClient client;
    private final MonitoringPort monitoring;
    private final Supplier<PortSet> ports;
    private final CredentialGuard guard;

    /**
     * @param monitoring the server's monitoring adapter, shared with
     *     {@link AdapterGatewayFactory#monitoring}
     * @param ports      the server's currently selected port set, shared with
     *     {@link AdapterGatewayFactory}
     * @param guard      the server's credential guard, shared by REST and CLI calls
     */
    V81Gateway(NodeId server, Optional<String> detectedVersion, List<String> warnings,
        InubitHttpClient client, MonitoringPort monitoring, Supplier<PortSet> ports,
        CredentialGuard guard) {
        this.server = Objects.requireNonNull(server, "server");
        this.detectedVersion = Objects.requireNonNull(detectedVersion, "detectedVersion");
        this.warnings = List.copyOf(warnings);
        this.client = Objects.requireNonNull(client, "client");
        this.monitoring = Objects.requireNonNull(monitoring, "monitoring");
        this.ports = Objects.requireNonNull(ports, "ports");
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
        return ports.get().processes();
    }

    @Override
    public LogPort logs() {
        return ports.get().logs();
    }

    @Override
    public InventoryPort inventory() {
        return ports.get().inventory();
    }

    @Override
    public ProcessControlPort processControl() {
        return ports.get().processControl();
    }

    @Override
    public ArtifactPort artifacts() {
        return ports.get().artifacts();
    }

    @Override
    public ImportPort imports() {
        return ports.get().imports();
    }

    @Override
    public TagPort tags() {
        return ports.get().tags();
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
