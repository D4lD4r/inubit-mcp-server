package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.VersionGate;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.Gateway;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * A {@link GatewayFactory} that only detects (feature 009): every {@link #detect} call stands for
 * one {@code /system/info} request and reports its scripted outcome to {@link NodeVersions} like
 * the real factory. Every other port is an assertion error.
 */
public final class FakeVersionDetection implements GatewayFactory {

    private final NodeVersions versions;
    private final Map<NodeId, Supplier<InubitVersion>> outcomes = new ConcurrentHashMap<>();
    private final List<NodeId> requests = new CopyOnWriteArrayList<>();

    public FakeVersionDetection(NodeVersions versions) {
        this.versions = versions;
    }

    /**
     * A gate over nodes that are all pinned to {@code V8_1}, so it never detects: the setting of
     * the tests that are not about the INUBIT version.
     */
    public static VersionGate pinned81() {
        NodeVersions versions = new NodeVersions(Clock.systemUTC(), node -> VersionSetting.V8_1,
            node -> Duration.ofMinutes(10));
        return new VersionGate(versions, new FakeVersionDetection(versions),
            CapabilityMatrix.production());
    }

    /** The detection of {@code node} finds {@code raw}, e.g. {@code 9.0.7}. */
    public FakeVersionDetection answer(NodeId node, String raw) {
        InubitVersion version = InubitVersion.parse(raw).orElseThrow();
        outcomes.put(node, () -> {
            versions.detected(node, version);
            return version;
        });
        return this;
    }

    /** The detection of {@code node} fails with {@code code}. */
    public FakeVersionDetection fail(NodeId node, ErrorCode code) {
        outcomes.put(node, () -> {
            versions.detectionFailed(node, code);
            throw new ToolErrorException(ToolError.of(code, "detection failed", "c", "s")
                .withNode(node));
        });
        return this;
    }

    /** The nodes detected so far, one entry per request. */
    public List<NodeId> requests() {
        return List.copyOf(requests);
    }

    @Override
    public InubitVersion detect(NodeId server) {
        requests.add(server);
        Supplier<InubitVersion> outcome = outcomes.get(server);
        if (outcome == null) {
            throw new AssertionError("no detection scripted for " + server);
        }
        return outcome.get();
    }

    @Override
    public Gateway forServer(NodeId server) {
        throw new AssertionError("only the detection may be requested");
    }

    @Override
    public MonitoringPort monitoring(NodeId server) {
        throw new AssertionError("only the detection may be requested");
    }

    @Override
    public ProcessQueryPort processes(NodeId server) {
        throw new AssertionError("only the detection may be requested");
    }

    @Override
    public LogPort logs(NodeId server) {
        throw new AssertionError("only the detection may be requested");
    }

    @Override
    public InventoryPort inventory(NodeId server) {
        throw new AssertionError("only the detection may be requested");
    }

    @Override
    public ProcessControlPort processControl(NodeId server) {
        throw new AssertionError("only the detection may be requested");
    }

    @Override
    public Optional<Gateway> knownGateway(NodeId server) {
        throw new AssertionError("only the detection may be requested");
    }
}
