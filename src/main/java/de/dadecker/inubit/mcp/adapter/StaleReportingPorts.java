package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.domain.model.EntryPart;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportProtocol;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.LogEntry;
import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.QueueEntries;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRef;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent;
import de.dadecker.inubit.mcp.domain.model.ServerLogKind;
import de.dadecker.inubit.mcp.domain.model.SystemInfo;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.model.node.ClusterNodes;
import de.dadecker.inubit.mcp.domain.model.node.IoLogListing;
import de.dadecker.inubit.mcp.domain.model.node.IoMessageRef;
import de.dadecker.inubit.mcp.domain.model.node.LoggerName;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigName;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigValues;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.ClusterNodePort;
import de.dadecker.inubit.mcp.domain.port.DownloadedLog;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.IoLogPort;
import de.dadecker.inubit.mcp.domain.port.LogLevelPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.MonitoringPort;
import de.dadecker.inubit.mcp.domain.port.NodeConfigPort;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.QueueEntryContentPort;
import de.dadecker.inubit.mcp.domain.port.ServerLogPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import de.dadecker.inubit.mcp.domain.port.VersionObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Stale trigger of research D-10: wraps the ports of one node so that a
 * {@link ToolErrorException} with {@code UNEXPECTED_RESPONSE} (from the REST client, e.g. HTTP 406
 * or a missing endpoint, or from a parser of the port set) reports
 * {@link VersionObserver#stale} for the node before it is rethrown unchanged. Every other outcome
 * passes through. The write permit of a write passes through unchanged (feature 017, research
 * D-24), and the unexpected response of a write reports {@link VersionObserver#staleAfterWrite}
 * with that permit, so that the same call may still send its rollback; a refused hand-off
 * ({@code UNSUPPORTED_VERSION}) reports nothing.
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
            new Tags(ports.tags()), new Partners(ports.partners()),
            new QueueEntryContent(ports.queueEntryContent()),
            new ServerLogs(ports.serverLogs()), new ClusterNodeList(ports.clusterNodes()),
            new IoLog(ports.ioLog()), new NodeConfig(ports.nodeConfig()),
            new LogLevel(ports.logLevel()));
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

    /**
     * As {@link #call} for a write handed off with {@code permit}: the stale mark of its own
     * response carries the permit (feature 017, research D-24).
     */
    private <T> T write(WritePermit permit, Supplier<T> call) {
        try {
            return call.get();
        } catch (ToolErrorException e) {
            if (e.error().code() == ErrorCode.UNEXPECTED_RESPONSE) {
                observer.staleAfterWrite(node, permit);
            }
            throw e;
        }
    }

    private void runWrite(WritePermit permit, Runnable call) {
        write(permit, () -> {
            call.run();
            return null;
        });
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

        /** Forwarded: the default would refuse without asking the wrapped port. */
        @Override
        public QueueEntries queueEntries(String processId) {
            return call(() -> delegate.queueEntries(processId));
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
        public String restart(String processId, WritePermit permit) {
            return write(permit, () -> delegate.restart(processId, permit));
        }

        @Override
        public String kill(String processId, WritePermit permit) {
            return write(permit, () -> delegate.kill(processId, permit));
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
        public ImportProtocol importArchive(byte[] archive, Mode mode, String owner,
            WritePermit permit) {
            return write(permit, () -> delegate.importArchive(archive, mode, owner, permit));
        }

        @Override
        public void importRepository(byte[] archive, String owner, WritePermit permit) {
            runWrite(permit, () -> delegate.importRepository(archive, owner, permit));
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
        public void tag(String tag, String diagramGroup, String owner, WritePermit permit) {
            runWrite(permit, () -> delegate.tag(tag, diagramGroup, owner, permit));
        }
    }

    final class Partners implements PartnerManagementPort {
        private final PartnerManagementPort delegate;

        Partners(PartnerManagementPort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public Result read(String owner, List<DiagramRef> systemDiagrams) {
            return call(() -> delegate.read(owner, systemDiagrams));
        }
    }

    final class QueueEntryContent implements QueueEntryContentPort {
        private final QueueEntryContentPort delegate;

        QueueEntryContent(QueueEntryContentPort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public RawEntryContent read(QueueEntryRef ref, InubitVersion version,
            Set<EntryPart> parts) {
            return call(() -> delegate.read(ref, version, parts));
        }
    }

    final class ServerLogs implements ServerLogPort {
        private final ServerLogPort delegate;

        ServerLogs(ServerLogPort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public DownloadedLog download(ServerLogKind kind, InubitVersion version,
            long deadlineNanos) {
            return call(() -> delegate.download(kind, version, deadlineNanos));
        }
    }

    final class ClusterNodeList implements ClusterNodePort {
        private final ClusterNodePort delegate;

        ClusterNodeList(ClusterNodePort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public ClusterNodes nodes(Optional<String> details, InubitVersion version,
            long deadlineNanos) {
            return call(() -> delegate.nodes(details, version, deadlineNanos));
        }
    }

    final class IoLog implements IoLogPort {
        private final IoLogPort delegate;

        IoLog(IoLogPort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public IoLogListing list(String processId, String workflow, InubitVersion version,
            long deadlineNanos) {
            return call(() -> delegate.list(processId, workflow, version, deadlineNanos));
        }

        @Override
        public IoLogRead read(String processId, String workflow, IoMessageRef ref,
            InubitVersion version, long deadlineNanos) {
            return call(() -> delegate.read(processId, workflow, ref, version, deadlineNanos));
        }
    }

    final class NodeConfig implements NodeConfigPort {
        private final NodeConfigPort delegate;

        NodeConfig(NodeConfigPort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public NodeConfigValues values(List<NodeConfigName> names, InubitVersion version,
            long deadlineNanos) {
            return call(() -> delegate.values(names, version, deadlineNanos));
        }
    }

    final class LogLevel implements LogLevelPort {
        private final LogLevelPort delegate;

        LogLevel(LogLevelPort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String answeringNode(InubitVersion version, long deadlineNanos) {
            return call(() -> delegate.answeringNode(version, deadlineNanos));
        }

        @Override
        public void raise(TemporaryLevel level, LoggerName logger, InubitVersion version,
            Duration timeout) {
            run(() -> delegate.raise(level, logger, version, timeout));
        }

        @Override
        public void resetAll(InubitVersion version, Duration timeout) {
            run(() -> delegate.resetAll(version, timeout));
        }
    }
}
