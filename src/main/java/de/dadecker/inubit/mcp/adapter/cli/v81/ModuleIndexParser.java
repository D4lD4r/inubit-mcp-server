package de.dadecker.inubit.mcp.adapter.cli.v81;

import de.dadecker.inubit.mcp.adapter.rest.XmlSupport;
import de.dadecker.inubit.mcp.adapter.rest.v81.DiagramExportParser;
import de.dadecker.inubit.mcp.adapter.rest.v81.InventoryXml;
import de.dadecker.inubit.mcp.domain.model.ConnectorFlags;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.ModuleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.w3c.dom.Element;

/**
 * Parses {@code module/module.xml} of the CLI module export (research R-11, S-6b; fixture
 * {@code cli/export_modules_sample.zip}): {@code IBISWorkflow/Modules/ModuleGroup*} with
 * {@code ModuleGroupName} and {@code Module*}.
 *
 * <ul>
 *   <li>{@code name} = {@code ModuleName}, {@code type} = {@code PluginName} (else the module
 *       group), {@code group} = {@code ModuleGroupName} (INUBIT names it after the plugin type,
 *       e.g. {@code XSLT Converter}; else the plugin name).
 *   <li>{@code active} = {@code IsActive}, {@code lastChange} = {@code LastUpdate}
 *       ({@code dd.MM.yyyy HH:mm:ss}, Europe/Berlin; the last content change, not the last
 *       check-in), {@code connectorWorkflow} = {@code WorkflowName} (INUBIT states it only for
 *       connector modules: the workflow the connector is bound to; it says nothing about which
 *       workflows use other modules, finding F1), {@code CheckinComment}, {@code UserComment},
 *       and the connector flags
 *       {@code IsInputConnector}, {@code IsOutputConnector}, {@code IsScheduled} (missing =
 *       false).
 *   <li>Nothing else is taken over (not {@code ExportUser}, {@code ModuleUId},
 *       {@code ErrorSuppression}, …); module configurations are never part of this file.
 * </ul>
 */
public final class ModuleIndexParser {

    private ModuleIndexParser() {
    }

    /**
     * @param owner the configured owner, which every module of the export belongs to
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} (with the server id) if the document
     *     is not an {@code IBISWorkflow} or a module has no name
     */
    public static List<ModuleEntry> parse(NodeId server, String owner, byte[] xml) {
        Element root = InventoryXml.parse(server, xml).getDocumentElement();
        if (!XmlSupport.localName(root).equals("IBISWorkflow")) {
            throw InventoryXml.unexpected(server, "The module export of " + server
                + " is not an IBISWorkflow document");
        }
        List<ModuleEntry> modules = new ArrayList<>();
        for (Element section : XmlSupport.children(root, "Modules")) {
            for (Element group : XmlSupport.children(section, "ModuleGroup")) {
                Optional<String> groupName = InventoryXml.childText(group, "ModuleGroupName");
                for (Element module : XmlSupport.children(group, "Module")) {
                    modules.add(module(server, owner, groupName, module));
                }
            }
        }
        return List.copyOf(modules);
    }

    private static ModuleEntry module(NodeId server, String owner, Optional<String> groupName,
        Element module) {
        String name = InventoryXml.childText(module, "ModuleName").orElseThrow(() ->
            InventoryXml.unexpected(server, "A module in the module export of " + server
                + " has no ModuleName"));
        Optional<String> plugin = InventoryXml.childText(module, "PluginName");
        InventoryItem item = InventoryItem.module(server, name,
            plugin.or(() -> groupName).orElse(""),
            groupName.or(() -> plugin).orElse(""),
            owner,
            InventoryXml.childText(module, "IsActive").flatMap(DiagramExportParser::bool),
            InventoryXml.childText(module, "LastUpdate").flatMap(InubitDates::parse));
        return new ModuleEntry(item,
            InventoryXml.childText(module, "CheckinComment"),
            InventoryXml.childText(module, "UserComment"),
            new ConnectorFlags(flag(module, "IsInputConnector"),
                flag(module, "IsOutputConnector"), flag(module, "IsScheduled")),
            InventoryXml.childText(module, "WorkflowName"));
    }

    private static boolean flag(Element module, String name) {
        return InventoryXml.childText(module, name).flatMap(DiagramExportParser::bool)
            .orElse(false);
    }
}
