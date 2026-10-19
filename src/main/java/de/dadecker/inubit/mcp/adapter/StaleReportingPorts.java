package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportProtocol;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.LogEntry;
import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.SystemInfo;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import de.dadecker.inubit.mcp.domain.port.VersionObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Stale trigger of research D-10: wraps the ports of one node so that a
 * {@link ToolErrorException} with {@code UNEXPECTED_RESPONSE} (from the REST client, e.g. HTTP 406
 * or a missing endpoint, or from a parser of the port set) reports {@link VersionObserver#stale} for the node
 * before it is rethrown unchanged. Every other outcome passes through.
 */
final class StaleReportingPorts {

    private final NodeId node;
    private final VersionObserver observer;

    StaleReportingPorts(NodeId node, VersionObserver observer) {
        this.node = Objects.requireNonNull(node, "node");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    MonitoringPort monitoring(MonitoringPort port) {
        return new Monitoring(port);
    }

    PortSet ports(PortSet ports) {
        return new PortSet(new Processes(ports.processes()), new Logs(ports.logs()),
            new Inventory(ports.inventory()), new ProcessControl(ports.processControl()),
            new Artifacts(ports.artifacts()), new Imports(ports.imports()),
            new Tags(ports.tags()));
    }

    private <T> T call(Supplier<T> call) {
        try {
            return call.get();
        } catch (ToolErrorException e) {
            if (e.error().code() == ErrorCode.UNEXPECTED_RESPONSE) {
                observer.stale(node);
            }
            throw e;
        }
    }

    private void run(Runnable call) {
        call(() -> {
            call.run();
            return null;
        });
    }

    final class Monitoring implements MonitoringPort {
        private final MonitoringPort delegate;

        Monitoring(MonitoringPort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public Healthcheck healthcheck() {
            return call(delegate::healthcheck);
        }

        @Override
        public Readiness ready() {
            return call(delegate::ready);
        }

        @Override
        public SystemInfo systemInfo() {
            return call(delegate::systemInfo);
        }

        @Override
        public Metrics metrics() {
            return call(delegate::metrics);
        }

        @Override
        public boolean credentialsConfirmed() {
            return call(delegate::credentialsConfirmed);
        }
    }

    final class Processes implements ProcessQueryPort {
        private final ProcessQueryPort delegate;

        Processes(ProcessQueryPort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void validate(ProcessQuery query) {
            run(() -> delegate.validate(query));
        }

        @Override
        public Page<ProcessInstance> find(ProcessQuery query) {
            return call(() -> delegate.find(query));
        }

        @Override
        public ProcessRows findByProcessId(String processId, Instant now,
            Duration hangingThreshold) {
            return call(() -> delegate.findByProcessId(processId, now, hangingThreshold));
        }
    }

    final class Logs implements LogPort {
        private final LogPort delegate;

        Logs(LogPort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void validate(LogQuery query) {
            run(() -> delegate.validate(query));
        }

        @Override
        public Page<LogEntry> query(LogQuery query) {
            return call(() -> delegate.query(query));
        }
    }

    final class Inventory implements InventoryPort {
        private final InventoryPort delegate;

        Inventory(InventoryPort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public List<InventoryItem> listDiagrams(String owner) {
            return call(() -> delegate.listDiagrams(owner));
        }

        @Override
        public DiagramDetail diagramDetail(String owner, String name) {
            return call(() -> delegate.diagramDetail(owner, name));
        }

        @Override
        public DiagramMetadata diagramMetadata(String name) {
            return call(() -> delegate.diagramMetadata(name));
        }

        @Override
        public VersionHistory versionHistory(String owner, String type, String group) {
            return call(() -> delegate.versionHistory(owner, type, group));
        }

        @Override
        public List<ModuleEntry> listModules(String owner) {
            return call(() -> delegate.listModules(owner));
        }
    }

    final class ProcessControl implements ProcessControlPort {
        private final ProcessControlPort delegate;

        ProcessControl(ProcessControlPort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void checkAvailable() {
            run(delegate::checkAvailable);
        }

        @Override
        public String restart(String processId) {
            return call(() -> delegate.restart(processId));
        }

        @Override
        public String kill(String processId) {
            return call(() -> delegate.kill(processId));
        }
    }

    final class Artifacts implements ArtifactPort {
        private final ArtifactPort delegate;

        Artifacts(ArtifactPort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public byte[] exportWorkflowGroup(String owner, String diagramGroup) {
            return call(() -> delegate.exportWorkflowGroup(owner, diagramGroup));
        }

        @Override
        public byte[] exportModule(String owner, String pluginType, String name) {
            return call(() -> delegate.exportModule(owner, pluginType, name));
        }

        @Override
        public byte[] exportRelease(String owner, String tag) {
            return call(() -> delegate.exportRelease(owner, tag));
        }

        @Override
        public byte[] exportRepository(String path) {
            return call(() -> delegate.exportRepository(path));
        }
    }

    final class Imports implements ImportPort {
        private final ImportPort delegate;

        Imports(ImportPort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void checkAvailable() {
            run(delegate::checkAvailable);
        }

        @Override
        public ImportProtocol importArchive(byte[] archive, Mode mode, String owner) {
            return call(() -> delegate.importArchive(archive, mode, owner));
        }

        @Override
        public void importRepository(byte[] archive, String owner) {
            run(() -> delegate.importRepository(archive, owner));
        }
    }

    final class Tags implements TagPort {
        private final TagPort delegate;

        Tags(TagPort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void checkAvailable() {
            run(delegate::checkAvailable);
        }

        @Override
        public History history(String owner, String diagramGroup) {
            return call(() -> delegate.history(owner, diagramGroup));
        }

        @Override
        public void tag(String tag, String diagramGroup, String owner) {
            run(() -> delegate.tag(tag, diagramGroup, owner));
        }
    }
}
