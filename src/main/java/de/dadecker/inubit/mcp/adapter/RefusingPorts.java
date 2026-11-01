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
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.ProcessControlPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The ports an INUBIT 6.1 node does not offer (feature 011, research D-4): every method throws
 * {@code UNSUPPORTED_VERSION} without contacting the node. The version gate refuses these calls
 * first; this is the second line of defence. One instance can serve as each port of a
 * {@link PortSet}.
 */
public final class RefusingPorts implements ProcessQueryPort, LogPort, InventoryPort,
    ProcessControlPort, ArtifactPort, ImportPort, TagPort {

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
    public String restart(String processId) {
        throw refusal();
    }

    @Override
    public String kill(String processId) {
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
    public ImportProtocol importArchive(byte[] archive, Mode mode, String owner) {
        throw refusal();
    }

    @Override
    public void importRepository(byte[] archive, String owner) {
        throw refusal();
    }

    @Override
    public History history(String owner, String diagramGroup) {
        throw refusal();
    }

    @Override
    public void tag(String tag, String diagramGroup, String owner) {
        throw refusal();
    }
}
