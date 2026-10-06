package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ConnectorFlags;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.InventoryKind;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** T026: the module lists of one server by owner; {@code unreachable} fails every lookup. */
final class FakeModuleLists implements InventoryPort {

    final NodeId node;
    final Map<String, List<String>> modules = new ConcurrentHashMap<>();
    final List<String> calls = new CopyOnWriteArrayList<>();
    volatile boolean unreachable;

    FakeModuleLists(NodeId node) {
        this.node = node;
    }

    @Override
    public List<ModuleEntry> listModules(String owner) {
        calls.add(owner);
        if (unreachable) {
            throw new ToolErrorException(ToolError.of(ErrorCode.UNREACHABLE, "unreachable",
                "fake", "fake").withNode(node));
        }
        return modules.getOrDefault(owner, List.of()).stream().map(name -> new ModuleEntry(
            new InventoryItem(node, InventoryKind.MODULE, name, "Assign", "Assign", owner,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            Optional.empty(), Optional.empty(), new ConnectorFlags(false, false, false),
            Optional.empty())).toList();
    }

    @Override
    public List<InventoryItem> listDiagrams(String owner) {
        throw new AssertionError("not used");
    }

    @Override
    public DiagramDetail diagramDetail(String owner, String name) {
        throw new AssertionError("not used");
    }

    @Override
    public DiagramMetadata diagramMetadata(String name) {
        throw new AssertionError("not used");
    }

    @Override
    public VersionHistory versionHistory(String owner, String type, String group) {
        throw new AssertionError("not used");
    }
}
