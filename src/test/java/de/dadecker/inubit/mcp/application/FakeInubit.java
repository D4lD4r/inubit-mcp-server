package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlNormalizer;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Text;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double of one INUBIT 8.1 server for the import tests (feature 004): it holds the export
 * archive of one diagram group of one owner, answers exports from it and applies imports to it
 * as the spike observed — artifacts matched by name, replaced or added, new UIDs, a new
 * {@code LastUpdate}, the archive's check-in comment kept — and prints the fixed-width import
 * protocol. Knobs simulate the failure modes of the spike and of research D-10. Like INUBIT, it
 * stores an embedded XML document ({@code type="XmlDocument"}) without trailing whitespace
 * (0.4.2).
 */
public final class FakeInubit {

    private static final String WORKFLOWS = "workflow/workflow.xml";
    private static final String INDEX = "module/module.xml";

    private final Map<String, byte[]> entries;
    private int uid;
    /** Every imported archive, in order (they hold secret values). */
    public final List<byte[]> imported = new CopyOnWriteArrayList<>();
    /** The next import prints its protocol but changes nothing. */
    public volatile boolean ignoreNextImport;
    /** The next import applies only the first artifact of the archive. */
    public volatile boolean partialNextImport;
    /** A row the next protocol lists in addition (an artifact that was not sent). */
    public volatile String extraProtocolRow;
    /** The next import changes this text of the workflow file after applying it. */
    public volatile String[] tamperNextImport;
    /** The next import stores the check-in comments rewritten by this function. */
    public volatile java.util.function.UnaryOperator<String> rewriteNextComments;
    /**
     * Like INUBIT: the export of the diagram group holds only the modules its workflows use
     * (off by default, the fixtures hold every module).
     */
    public volatile boolean onlyUsedModules;

    public FakeInubit(byte[] export) {
        this.entries = new LinkedHashMap<>(ArtifactFixtures.entries(export));
    }

    /** The current export of the diagram group. */
    public synchronized byte[] exportWorkflowGroup() {
        if (!onlyUsedModules) {
            return ArtifactFixtures.zip(entries);
        }
        String workflows = workflowXml();
        java.util.function.Predicate<String> used = name -> java.util.regex.Pattern.compile(
            "(?<![\\w-])" + java.util.regex.Pattern.quote(name) + "(?![\\w-])")
            .matcher(workflows).find();
        Element index = root(INDEX);
        Element modules = index.elements().get(0);
        List<Node> groups = new ArrayList<>();
        Map<String, byte[]> export = new LinkedHashMap<>();
        for (Element group : modules.elements()) {
            List<Node> kept = new ArrayList<>();
            for (Node node : group.children()) {
                if (node instanceof Element module && module.localName().equals("Module")) {
                    String name = module.child("ModuleName").orElseThrow().text();
                    if (!used.test(name)) {
                        continue;
                    }
                    String file = "module/" + name.toLowerCase(Locale.ROOT) + ".xml";
                    export.put(file, entries.get(file));
                }
                kept.add(node);
            }
            if (kept.stream().anyMatch(n -> n instanceof Element e
                && e.localName().equals("Module"))) {
                groups.add(group.withChildren(kept));
            }
        }
        Map<String, byte[]> zip = new LinkedHashMap<>();
        entries.forEach((name, content) -> {
            if (!name.startsWith("module/")) {
                zip.put(name, content);
            }
        });
        zip.put(INDEX, XmlNormalizer.normalize(index.withChildren(List.of(
            modules.withChildren(groups)))));
        zip.putAll(export);
        return ArtifactFixtures.zip(zip);
    }

    /** True if the server has module {@code name} (real StartCLI: NOT_FOUND otherwise). */
    public synchronized boolean hasModule(String name) {
        return contains(root(INDEX), name);
    }

    /** The current module-only export of {@code name}. */
    public synchronized byte[] exportModule(String pluginType, String name) {
        Element index = root(INDEX);
        Element entry = null;
        for (Element modules : index.elements()) {
            for (Element group : modules.elements()) {
                for (Element module : group.elements()) {
                    if (module.localName().equals("Module") && module.child("ModuleName")
                        .map(Element::text).filter(name::equals).isPresent()) {
                        entry = module;
                    }
                }
            }
        }
        if (entry == null) {
            throw new IllegalStateException("no module " + name);
        }
        Element group = element("ModuleGroup", List.of(leaf("ModuleGroupName", pluginType),
            entry));
        Map<String, byte[]> module = new LinkedHashMap<>();
        module.put("archive.properties", entries.get("archive.properties"));
        module.put("workflow/", new byte[0]);
        module.put(INDEX, XmlNormalizer.normalize(index.withChildren(List.of(
            element("Modules", List.of(group))))));
        String file = "module/" + name.toLowerCase(Locale.ROOT) + ".xml";
        module.put(file, entries.get(file));
        module.put("Repository.zip", ArtifactFixtures.zip(Map.of()));
        return ArtifactFixtures.zip(module);
    }

    /** Applies {@code zip} and returns StartCLI's stdout with the protocol. */
    public synchronized String importArchive(byte[] zip) {
        imported.add(zip.clone());
        Map<String, byte[]> archive = ArtifactFixtures.entries(zip);
        List<String[]> rows = new ArrayList<>();
        boolean apply = !ignoreNextImport;
        ignoreNextImport = false;
        boolean partial = partialNextImport;
        partialNextImport = false;
        java.util.function.UnaryOperator<String> comments = rewriteNextComments == null
            ? java.util.function.UnaryOperator.identity() : rewriteNextComments;
        rewriteNextComments = null;
        int applied = 0;
        if (archive.containsKey(INDEX)) {
            Element index = root(INDEX);
            for (Element group : XmlTree.parse(archive.get(INDEX)).root().elements().get(0)
                .elements()) {
                String pluginType = group.child("ModuleGroupName").orElseThrow().text();
                for (Element module : group.elements()) {
                    if (!module.localName().equals("Module")) {
                        continue;
                    }
                    String name = module.child("ModuleName").orElseThrow().text();
                    boolean exists = contains(index, name);
                    rows.add(new String[] {"Module [" + name + "] was " + (exists ? "modified."
                        : "created."), "/" + name});
                    if (apply && (!partial || applied++ == 0)) {
                        index = putModule(index, pluginType, name, comment(withValue(withValue(
                            module, "ModuleUId", "-fake:" + ++uid), "LastUpdate",
                            "07.10.2026 12:00:00"), comments));
                        String file = "module/" + name.toLowerCase(Locale.ROOT) + ".xml";
                        entries.put(file, stored(archive.get(file)));
                    }
                }
            }
            entries.put(INDEX, XmlNormalizer.normalize(index));
        }
        if (archive.containsKey(WORKFLOWS)) {
            Element workflows = root(WORKFLOWS);
            for (Element group : XmlTree.parse(archive.get(WORKFLOWS)).root().elements().get(0)
                .elements()) {
                for (Element workflow : group.elements()) {
                    if (!workflow.localName().equals("Workflow")) {
                        continue;
                    }
                    String name = workflow.child("WorkflowName").orElseThrow().text();
                    boolean exists = workflows.elements().get(0).elements().stream()
                        .flatMap(g -> g.elements().stream())
                        .anyMatch(w -> w.child("WorkflowName").map(Element::text)
                            .filter(name::equals).isPresent());
                    rows.add(new String[] {"Diagram [" + name + "] was " + (exists
                        ? "modified." : "created."), name});
                    if (apply && (!partial || applied++ == 0)) {
                        workflows = putWorkflow(workflows, comment(withValue(workflow,
                            "WorkflowUId", "-fake:" + ++uid), comments));
                    }
                }
            }
            byte[] bytes = XmlNormalizer.normalize(workflows);
            String[] tamper = tamperNextImport;
            tamperNextImport = null;
            if (apply && tamper != null) {
                bytes = new String(bytes, StandardCharsets.UTF_8).replace(tamper[0], tamper[1])
                    .getBytes(StandardCharsets.UTF_8);
            }
            entries.put(WORKFLOWS, bytes);
        }
        if (extraProtocolRow != null) {
            rows.add(new String[] {"Module [" + extraProtocolRow + "] was modified.",
                "/" + extraProtocolRow});
            extraProtocolRow = null;
        }
        return protocol(rows);
    }

    /** A colleague changes the workflow file on the server (outside any import). */
    public synchronized void changeWorkflows(java.util.function.UnaryOperator<String> change) {
        String before = workflowXml();
        String after = change.apply(before);
        if (after.equals(before)) {
            throw new IllegalStateException("the change does not apply");
        }
        entries.put(WORKFLOWS, after.getBytes(StandardCharsets.UTF_8));
    }

    /** A colleague changes the file of module {@code name} on the server. */
    public synchronized void changeModule(String name,
        java.util.function.UnaryOperator<String> change) {
        String file = "module/" + name.toLowerCase(Locale.ROOT) + ".xml";
        String before = new String(entries.get(file), StandardCharsets.UTF_8);
        String after = change.apply(before);
        if (after.equals(before)) {
            throw new IllegalStateException("the change does not apply");
        }
        entries.put(file, after.getBytes(StandardCharsets.UTF_8));
    }

    /** The text of the current workflow file of the diagram group. */
    public synchronized String workflowXml() {
        return new String(entries.get(WORKFLOWS), StandardCharsets.UTF_8);
    }

    /** A module file as INUBIT stores it: embedded XML documents without trailing whitespace. */
    private static byte[] stored(byte[] moduleFile) {
        Element properties = XmlTree.parse(moduleFile).root();
        return XmlNormalizer.normalize(properties.withChildren(properties.children().stream()
            .map(node -> node instanceof Element property
                && property.attribute("type").filter("XmlDocument"::equals).isPresent()
                && !property.hasElements() ? property.withText(property.text().stripTrailing())
                : node).toList()));
    }

    private static String protocol(List<String[]> rows) {
        int description = "DESCRIPTION".length();
        int diagram = "DIAGRAM/MODULE".length();
        for (String[] row : rows) {
            description = Math.max(description, row[0].length());
            diagram = Math.max(diagram, row[1].length());
        }
        String format = "%-12s%-" + (description + 1) + "s%-" + (diagram + 1) + "s%s  \r\n";
        StringBuilder out = new StringBuilder("JAVA_HOME is set\nPassword: \n");
        out.append(String.format(format, "TYPE", "DESCRIPTION", "DIAGRAM/MODULE", "GROUP/USER"));
        for (String[] row : rows) {
            out.append(String.format(format, "INFORMATION", row[0], row[1], "jdoe"));
        }
        return out.append("Total: ").append(rows.size()).append("\r\n\n").toString();
    }

    private Element root(String entry) {
        return XmlTree.parse(entries.get(entry)).root();
    }

    private static boolean contains(Element index, String name) {
        return index.elements().get(0).elements().stream().flatMap(g -> g.elements().stream())
            .anyMatch(m -> m.child("ModuleName").map(Element::text).filter(name::equals)
                .isPresent());
    }

    private static Element putModule(Element index, String pluginType, String name,
        Element module) {
        Element modules = index.elements().get(0);
        List<Node> groups = new ArrayList<>();
        boolean placed = false;
        for (Node node : modules.children()) {
            if (node instanceof Element group && group.child("ModuleGroupName")
                .map(Element::text).filter(pluginType::equals).isPresent()) {
                groups.add(replaceOrAppend(group, "ModuleName", name, module));
                placed = true;
            } else {
                groups.add(node);
            }
        }
        if (!placed) {
            groups.add(element("ModuleGroup", List.of(leaf("ModuleGroupName", pluginType),
                module)));
        }
        return index.withChildren(List.of(modules.withChildren(groups)));
    }

    private static Element putWorkflow(Element root, Element workflow) {
        Element list = root.elements().get(0);
        Element group = list.elements().get(0);
        String name = workflow.child("WorkflowName").orElseThrow().text();
        List<Node> groups = new ArrayList<>(list.children());
        groups.set(groups.indexOf(group), replaceOrAppend(group, "WorkflowName", name,
            workflow));
        return root.withChildren(List.of(list.withChildren(groups)));
    }

    private static Element replaceOrAppend(Element parent, String key, String name,
        Element replacement) {
        List<Node> children = new ArrayList<>();
        boolean replaced = false;
        for (Node node : parent.children()) {
            if (node instanceof Element child && child.child(key).map(Element::text)
                .filter(name::equals).isPresent()) {
                children.add(replacement);
                replaced = true;
            } else {
                children.add(node);
            }
        }
        if (!replaced) {
            children.add(replacement);
        }
        return parent.withChildren(children);
    }

    private static Element withValue(Element element, String child, String value) {
        if (element.child(child).isEmpty()) {
            List<Node> children = new ArrayList<>(element.children());
            children.add(leaf(child, value));
            return element.withChildren(children);
        }
        return element.withChildren(element.children().stream().map(node ->
            node instanceof Element e && e.localName().equals(child) ? e.withText(value)
                : node).toList());
    }

    private static Element comment(Element element,
        java.util.function.UnaryOperator<String> rewrite) {
        return element.child("CheckinComment").map(c -> withValue(element, "CheckinComment",
            rewrite.apply(c.text()))).orElse(element);
    }

    private static Element leaf(String name, String text) {
        return new Element("", name, "", List.of(), List.of(), List.of(new Text(text)));
    }

    private static Element element(String name, List<? extends Node> children) {
        return new Element("", name, "", List.of(), List.of(), List.copyOf(children));
    }
}
