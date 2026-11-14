package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.domain.model.CliSettingsSnapshot;
import de.dadecker.inubit.mcp.domain.model.ConfigurationView;
import de.dadecker.inubit.mcp.domain.model.EntryPart;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportProtocol;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.LicenseFacts;
import de.dadecker.inubit.mcp.domain.model.ListedUser;
import de.dadecker.inubit.mcp.domain.model.LogEntry;
import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.LoginEntry;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.QueueEntries;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRef;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent;
import de.dadecker.inubit.mcp.domain.model.ServerLogKind;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UserFacts;
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
import de.dadecker.inubit.mcp.domain.port.NodeConfigPort;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.QueueEntryContentPort;
import de.dadecker.inubit.mcp.domain.port.ServerLogPort;
import de.dadecker.inubit.mcp.domain.port.ServerSettingsPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import de.dadecker.inubit.mcp.domain.port.UserDirectoryPort;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The ports an INUBIT 6.1 node does not offer (feature 012, research D-4): every method throws
 * {@code UNSUPPORTED_VERSION} without contacting the node. The version gate refuses these calls
 * first; this is the second line of defence. One instance can serve as each port of a
 * {@link PortSet}.
 */
public final class RefusingPorts implements ProcessQueryPort, LogPort, InventoryPort,
    ProcessControlPort, ArtifactPort, ImportPort, TagPort, PartnerManagementPort,
    QueueEntryContentPort, ServerLogPort, ServerSettingsPort, UserDirectoryPort, ClusterNodePort,
    IoLogPort, NodeConfigPort, LogLevelPort {

    private final NodeId node;

    public RefusingPorts(NodeId node) {
        this.node = Objects.requireNonNull(node, "node");
    }

    /** The refusal every method of this class throws. */
    public ToolErrorException refusal() {
        return new ToolErrorException(ToolError.of(ErrorCode.UNSUPPORTED_VERSION,
            "This operation is not available on " + node + " (INUBIT 6.1, StartCLI only);"
                + " nothing was sent",
            "INUBIT 6.1 is supported read-only through StartCLI, for a few reads only",
            "Call list_nodes for what this node supports, or use the INUBIT Workbench")
            .withNode(node));
    }

    @Override
    public void validate(ProcessQuery query) {
        throw refusal();
    }

    @Override
    public Page<ProcessInstance> find(ProcessQuery query) {
        throw refusal();
    }

    @Override
    public ProcessRows findByProcessId(String processId, Instant now,
        Duration hangingThreshold) {
        throw refusal();
    }

    @Override
    public QueueEntries queueEntries(String processId) {
        throw refusal();
    }

    @Override
    public void validate(LogQuery query) {
        throw refusal();
    }

    @Override
    public Page<LogEntry> query(LogQuery query) {
        throw refusal();
    }

    @Override
    public List<InventoryItem> listDiagrams(String owner) {
        throw refusal();
    }

    @Override
    public DiagramDetail diagramDetail(String owner, String name) {
        throw refusal();
    }

    @Override
    public DiagramMetadata diagramMetadata(String name) {
        throw refusal();
    }

    @Override
    public VersionHistory versionHistory(String owner, String type, String group) {
        throw refusal();
    }

    @Override
    public List<ModuleEntry> listModules(String owner) {
        throw refusal();
    }

    /** {@link ProcessControlPort}, {@link ImportPort} and {@link TagPort}. */
    @Override
    public void checkAvailable() {
        throw refusal();
    }

    @Override
    public String restart(String processId, WritePermit permit) {
        throw refusal();
    }

    @Override
    public String kill(String processId, WritePermit permit) {
        throw refusal();
    }

    @Override
    public byte[] exportWorkflowGroup(String owner, String diagramGroup) {
        throw refusal();
    }

    @Override
    public byte[] exportModule(String owner, String pluginType, String name) {
        throw refusal();
    }

    @Override
    public byte[] exportRelease(String owner, String tag) {
        throw refusal();
    }

    @Override
    public byte[] exportRepository(String path) {
        throw refusal();
    }

    @Override
    public ImportProtocol importArchive(byte[] archive, Mode mode, String owner,
        WritePermit permit) {
        throw refusal();
    }

    @Override
    public void importRepository(byte[] archive, String owner, WritePermit permit) {
        throw refusal();
    }

    @Override
    public History history(String owner, String diagramGroup) {
        throw refusal();
    }

    @Override
    public void tag(String tag, String diagramGroup, String owner, WritePermit permit) {
        throw refusal();
    }

    @Override
    public Result read(String owner, List<DiagramRef> systemDiagrams) {
        throw refusal();
    }

    @Override
    public RawEntryContent read(QueueEntryRef ref, InubitVersion version, Set<EntryPart> parts) {
        throw refusal();
    }

    @Override
    public DownloadedLog download(ServerLogKind kind, InubitVersion version, long deadlineNanos) {
        throw refusal();
    }

    @Override
    public LicenseFacts license() {
        throw refusal();
    }

    @Override
    public ConfigurationView configuration(Optional<String> section, int maxValues,
        int maxChars) {
        throw refusal();
    }

    @Override
    public CliSettingsSnapshot cliSettings(Set<String> parts) {
        throw refusal();
    }

    @Override
    public List<ListedUser> listUsers() {
        throw refusal();
    }

    @Override
    public List<UserFacts> readUsers(List<String> logins) {
        throw refusal();
    }

    @Override
    public List<LoginEntry> loggedIn() {
        throw refusal();
    }

    @Override
    public ClusterNodes nodes(Optional<String> details, InubitVersion version,
        long deadlineNanos) {
        throw refusal();
    }

    @Override
    public IoLogListing list(String processId, String workflow, InubitVersion version,
        long deadlineNanos) {
        throw refusal();
    }

    @Override
    public IoLogRead read(String processId, String workflow, IoMessageRef ref,
        InubitVersion version, long deadlineNanos) {
        throw refusal();
    }

    @Override
    public NodeConfigValues values(List<NodeConfigName> names, InubitVersion version,
        long deadlineNanos) {
        throw refusal();
    }

    @Override
    public String answeringNode(InubitVersion version, long deadlineNanos) {
        throw refusal();
    }

    @Override
    public void raise(TemporaryLevel level, LoggerName logger, InubitVersion version,
        Duration timeout) {
        throw refusal();
    }

    @Override
    public void resetAll(InubitVersion version, Duration timeout) {
        throw refusal();
    }
}
