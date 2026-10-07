package de.dadecker.inubit.mcp.adapter.cli.v81;

import de.dadecker.inubit.mcp.adapter.rest.XmlSupport;
import de.dadecker.inubit.mcp.adapter.rest.v81.InventoryXml;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionEntry;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.VersionHistory;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.w3c.dom.Element;

/**
 * Parses {@code versionHistory.xml} of the CLI export with history (research R-11, S-6b; fixture
 * {@code cli/export_history_sample.zip}).
 *
 * <ul>
 *   <li>{@code VersionInformation/Workflows/WorkflowGroup/Workflow[@Name]/Version*} and
 *       {@code VersionInformation/Modules/Module[@Name]/Version*}; each {@code Version} has
 *       {@code versionNode}, {@code CheckinUser}, {@code CheckinComment}, {@code DateTime} and
 *       optional {@code UserComment} and {@code Tags/Tag*}.
 *   <li>{@code DateTime} is local time {@code Europe/Berlin} ({@link InubitDates}); an
 *       unparseable value leaves {@code checkinAt} absent.
 *   <li>{@code Tags/Tag*} is the current tag assignment.
 *   <li>The file lists versions oldest first; the result is sorted newest first (by version
 *       number). A name that occurs twice keeps its first occurrence.
 * </ul>
 */
public final class VersionHistoryParser {

    private VersionHistoryParser() {
    }

    /**
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} (with the server id) if the document
     *     is not a {@code VersionInformation} or a version has no integer {@code versionNode}
     */
    public static VersionHistory parse(NodeId server, byte[] xml) {
        Element root = InventoryXml.parse(server, xml).getDocumentElement();
        if (!XmlSupport.localName(root).equals("VersionInformation")) {
            throw InventoryXml.unexpected(server, "The version history export of " + server
                + " is not a VersionInformation document");
        }
        Map<String, List<VersionEntry>> workflows = new LinkedHashMap<>();
        for (Element section : XmlSupport.children(root, "Workflows")) {
            for (Element group : XmlSupport.children(section, "WorkflowGroup")) {
                for (Element workflow : XmlSupport.children(group, "Workflow")) {
                    add(server, workflows, workflow);
                }
            }
        }
        Map<String, List<VersionEntry>> modules = new LinkedHashMap<>();
        for (Element section : XmlSupport.children(root, "Modules")) {
            for (Element module : XmlSupport.children(section, "Module")) {
                add(server, modules, module);
            }
        }
        return new VersionHistory(workflows, modules);
    }

    /**
     * The history with the diagram group and type of every diagram (feature 004, research
     * D-16): what the tag check of {@code tag_artifacts} compares. A diagram name that occurs
     * twice keeps its first occurrence.
     *
     * @throws ToolErrorException as {@link #parse}
     */
    public static TagPort.History parseHistory(NodeId server, byte[] xml) {
        Element root = InventoryXml.parse(server, xml).getDocumentElement();
        if (!XmlSupport.localName(root).equals("VersionInformation")) {
            throw InventoryXml.unexpected(server, "The version history export of " + server
                + " is not a VersionInformation document");
        }
        Map<String, TagPort.Diagram> diagrams = new LinkedHashMap<>();
        for (Element section : XmlSupport.children(root, "Workflows")) {
            for (Element group : XmlSupport.children(section, "WorkflowGroup")) {
                String groupName = XmlSupport.attribute(group, "Name").map(String::strip)
                    .orElse("");
                for (Element workflow : XmlSupport.children(group, "Workflow")) {
                    Map<String, List<VersionEntry>> one = new LinkedHashMap<>();
                    add(server, one, workflow);
                    one.forEach((name, versions) -> diagrams.putIfAbsent(name,
                        new TagPort.Diagram(groupName, XmlSupport.attribute(workflow, "Type")
                            .map(String::strip).orElse(""), versions)));
                }
            }
        }
        Map<String, List<VersionEntry>> modules = new LinkedHashMap<>();
        for (Element section : XmlSupport.children(root, "Modules")) {
            for (Element module : XmlSupport.children(section, "Module")) {
                add(server, modules, module);
            }
        }
        return new TagPort.History(diagrams, modules);
    }

    private static void add(NodeId server, Map<String, List<VersionEntry>> histories,
        Element owner) {
        Optional<String> name = XmlSupport.attribute(owner, "Name").map(String::strip)
            .filter(n -> !n.isEmpty());
        if (name.isEmpty() || histories.containsKey(name.get())) {
            return;
        }
        List<VersionEntry> versions = new ArrayList<>();
        for (Element version : XmlSupport.children(owner, "Version")) {
            versions.add(version(server, name.get(), version));
        }
        versions.sort(Comparator.comparingInt(VersionEntry::version).reversed());
        histories.put(name.get(), List.copyOf(versions));
    }

    private static VersionEntry version(NodeId server, String name, Element version) {
        int number;
        try {
            number = Integer.parseInt(InventoryXml.childText(version, "versionNode").orElse(""));
        } catch (NumberFormatException e) {
            throw InventoryXml.unexpected(server, "A version of " + name
                + " in the version history export of " + server
                + " has no numeric versionNode");
        }
        List<String> tags = new ArrayList<>();
        for (Element tagList : XmlSupport.children(version, "Tags")) {
            for (Element tag : XmlSupport.children(tagList, "Tag")) {
                String text = XmlSupport.text(tag);
                if (!text.isEmpty()) {
                    tags.add(text);
                }
            }
        }
        return new VersionEntry(number,
            InventoryXml.childText(version, "CheckinUser"),
            InventoryXml.childText(version, "DateTime").flatMap(InubitDates::parse),
            InventoryXml.childText(version, "CheckinComment"),
            InventoryXml.childText(version, "UserComment"),
            tags);
    }
}
