package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Attribute;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Text;
import de.dadecker.inubit.mcp.domain.model.WorkflowGraph;
import de.dadecker.inubit.mcp.domain.model.WorkflowGraph.Edge;
import de.dadecker.inubit.mcp.domain.model.WorkflowGraph.Reference;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.port.ArtifactInspectorPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The 8.1 {@link ArtifactInspectorPort} (research D-13) on the normalized workspace files.
 *
 * <ul>
 *   <li>Nodes are all {@code WorkflowModule} elements of a workflow (also nested ones, e.g. the
 *       children of a {@code PartnerManagement} node); edges their own {@code Connection}
 *       children; parent references {@code ParentModule@moduleId} and any
 *       {@code EndLoopId}/{@code scopeChildId} element or attribute of the node.
 *   <li>Variables are the {@code Variable@name} declarations; references every attribute named
 *       {@code variable} (assignments {@code from}/{@code to}, conditions).
 *   <li>Repository references are the paths after {@code inubitrepository:} in any text or
 *       attribute value (also inside escaped inline stylesheets).
 * </ul>
 */
public final class WorkspaceInspector implements ArtifactInspectorPort {

    /** {@code inubitrepository:/Root/…} up to a quote, bracket, ampersand or white space. */
    static final Pattern REPOSITORY_REFERENCE =
        Pattern.compile("inubitrepository:/+([^\"'<>&\\s]+)");
    private static final Set<String> PARENT_NAMES = Set.of("EndLoopId", "scopeChildId");
    private static final String MD5 = "MD5";
    private static final String FILE_REFERENCE = "@file:";

    @Override
    public WorkflowGraph workflow(Path file) {
        Element root = XmlTree.parse(read(file)).root();
        if (!root.localName().equals("Workflow")) {
            throw new IllegalArgumentException("The root element is <" + root.localName()
                + ">, not <Workflow>");
        }
        List<WorkflowGraph.Node> nodes = new ArrayList<>();
        Set<String> variables = new LinkedHashSet<>();
        List<Reference> variableReferences = new ArrayList<>();
        List<Reference> repositoryReferences = new ArrayList<>();
        walk(root, "Workflow", nodes, variables, variableReferences, repositoryReferences);
        return new WorkflowGraph(nodes, variables, variableReferences, repositoryReferences);
    }

    private static void walk(Element element, String location, List<WorkflowGraph.Node> nodes,
        Set<String> variables, List<Reference> variableReferences,
        List<Reference> repositoryReferences) {
        String here = location;
        if (element.localName().equals("WorkflowModule")) {
            WorkflowGraph.Node node = node(element);
            nodes.add(node);
            here = node.location();
        } else if (element.localName().equals("Variable")) {
            element.attribute("name").ifPresent(variables::add);
        }
        for (Attribute attribute : element.attributes()) {
            if (attribute.localName().equals("variable") && !attribute.value().isBlank()) {
                variableReferences.add(new Reference(attribute.value().strip(),
                    here + "/" + element.localName()));
            }
            repositoryReferences(attribute.value(), here, repositoryReferences);
        }
        for (Node child : element.children()) {
            if (child instanceof Element e) {
                walk(e, here, nodes, variables, variableReferences, repositoryReferences);
            } else if (child instanceof Text text) {
                repositoryReferences(text.value(), here, repositoryReferences);
            }
        }
    }

    private static void repositoryReferences(String text, String location,
        List<Reference> references) {
        Matcher matcher = REPOSITORY_REFERENCE.matcher(text);
        while (matcher.find()) {
            references.add(new Reference(matcher.group(1), location));
        }
    }

    private static WorkflowGraph.Node node(Element module) {
        String id = module.child("ModuleId").map(Element::text).orElse("").strip();
        String location = "WorkflowModule[ModuleId=" + id + "]";
        List<Edge> edges = new ArrayList<>();
        Map<String, String> properties = new LinkedHashMap<>();
        List<Reference> parents = new ArrayList<>();
        for (Element child : module.elements()) {
            switch (child.localName()) {
                case "Connection" -> child.attribute("moduleOutId").ifPresent(target ->
                    edges.add(new Edge(target.strip(), child.child("ConnectionId")
                        .map(Element::text).map(String::strip))));
                case "Properties" -> child.elements().stream()
                    .filter(p -> p.localName().equals("Property") && !p.hasElements())
                    .forEach(p -> p.attribute("name").ifPresent(name ->
                        properties.putIfAbsent(name, p.text())));
                case "ParentModule" -> child.attribute("moduleId").ifPresent(parent ->
                    parents.add(new Reference(parent.strip(), location + "/ParentModule")));
                default -> {
                    // other children of the node
                }
            }
        }
        parentReferences(module, location, parents, true);
        return new WorkflowGraph.Node(id, module.child("ModuleName").map(Element::text)
            .orElse("").strip(), module.attribute("moduleType").orElse(""), edges, properties,
            parents);
    }

    /** {@code EndLoopId}/{@code scopeChildId} elements and attributes, not of nested nodes. */
    private static void parentReferences(Element element, String location,
        List<Reference> parents, boolean top) {
        if (!top && element.localName().equals("WorkflowModule")) {
            return;
        }
        for (Attribute attribute : element.attributes()) {
            if (PARENT_NAMES.contains(attribute.localName()) && !attribute.value().isBlank()) {
                parents.add(new Reference(attribute.value().strip(), location + "/@"
                    + attribute.localName()));
            }
        }
        if (PARENT_NAMES.contains(element.localName()) && !element.text().isBlank()) {
            parents.add(new Reference(element.text().strip(), location + "/"
                + element.localName()));
        }
        for (Element child : element.elements()) {
            parentReferences(child, location, parents, false);
        }
    }

    @Override
    public List<String> derivedValueMismatches(Path root, Path file) {
        if (file.getFileName().toString().equals("module.xml")) {
            return moduleMismatches(file);
        }
        WorkspacePath path;
        try {
            path = WorkspacePath.parse(root.relativize(file));
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        if (path.kind() != WorkspacePath.Kind.REPOSITORY) {
            return List.of();
        }
        Optional<String> metadata = new MetaStore(root).read(path)
            .map(values -> values.get("metadata")).map(String::valueOf);
        if (metadata.isEmpty()) {
            return List.of();
        }
        Element element = XmlTree.parse(metadata.get().getBytes(
            java.nio.charset.StandardCharsets.UTF_8)).root();
        byte[] content = read(file);
        List<String> mismatches = new ArrayList<>();
        element.attribute("contentMD5").filter(md5 -> !md5.equalsIgnoreCase(md5(content)))
            .ifPresent(md5 -> mismatches.add("contentMD5"));
        element.attribute("contentSize")
            .filter(size -> !size.strip().equals(String.valueOf(content.length)))
            .ifPresent(size -> mismatches.add("contentSize"));
        return mismatches;
    }

    /** {@code <property>MD5} of each embedded document ({@code @file:} reference). */
    private static List<String> moduleMismatches(Path file) {
        Element properties = XmlTree.parse(read(file)).root();
        Map<String, String> values = new LinkedHashMap<>();
        properties.elements().stream().filter(p -> !p.hasElements())
            .forEach(p -> p.attribute("name").ifPresent(name -> values.put(name, p.text())));
        List<String> mismatches = new ArrayList<>();
        values.forEach((name, value) -> {
            String md5 = values.get(name + MD5);
            if (value.startsWith(FILE_REFERENCE) && md5 != null && !md5.isBlank()) {
                Path document = file.resolveSibling(value.substring(FILE_REFERENCE.length()));
                if (Files.isRegularFile(document)
                    && !md5.strip().equalsIgnoreCase(md5(read(document)))) {
                    mismatches.add(name + MD5);
                }
            }
        });
        return mismatches;
    }

    private static String md5(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
