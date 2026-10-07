package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.RepositoryArchive;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlNormalizer;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Attribute;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Text;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Test double of one INUBIT 8.1 server for the deployment tests (feature 005, research D-1,
 * D-16; stage 2 review #7): the artifacts of one owner in <em>several</em> diagram groups, with
 * owner-wide modules (a module used by workflows of two groups exists once), version numbers,
 * repository files with versions, tags per diagram group and the module list and diagram list
 * the REST inventory sees. It answers the exports StartCLI gives — a diagram group export with
 * exactly the modules its workflows use and the repository files they reference, module exports,
 * repository exports, the owner-wide release export of a tag — and applies imports as the spike
 * observed (artifacts matched by name, new UIDs, a new version per import, the active-flag
 * option; repository imports relative to {@code /Root/<owner>}).
 *
 * <p>The single-group {@link FakeInubit} stays the double of the feature-004 tests.
 */
public final class FakeServer {

    private static final String WORKFLOWS = "workflow/workflow.xml";
    private static final String INDEX = "module/module.xml";
    private static final String DOCUMENT_VERSION = "5.3";
    private static final Pattern VERSION = Pattern.compile("@@@Version: (\\d+)@@@");
    private static final Pattern REFERENCE = Pattern.compile(
        "(?:href|schemaLocation)=\"inubitrepository:(/Root/[^\"]+)\"");
    private static final byte[] PROPERTIES = ("#\nsourceVersion=8.1.17\n"
        + "operationId=00000000-0000-0000-0000-000000000105\n").getBytes(StandardCharsets.UTF_8);

    /** One workflow: its element (as exported, {@code version="head"}) and version. */
    private record Workflow(Element element, int version) {
    }

    /** One module: plugin type, index entry, module file and version. */
    private record Module(String pluginType, Element entry, byte[] file, int version) {
    }

    /** One version of a repository file. */
    private record RepositoryVersion(String version, String uuid, String comment,
        String description, byte[] content, Set<String> tags) {
    }

    /** What a tag marks in one diagram group. */
    private record Tagged(Map<String, Workflow> workflows, Map<String, Module> modules,
        Map<String, RepositoryVersion> repository) {
    }

    private final Map<String, Map<String, Workflow>> groups = new LinkedHashMap<>();
    private final Map<String, Module> modules = new LinkedHashMap<>();
    private final TreeMap<String, List<RepositoryVersion>> repository = new TreeMap<>();
    /** tag → diagram group → its tagged state. */
    private final Map<String, Map<String, Tagged>> tags = new LinkedHashMap<>();
    private int uid;

    /** Every workflow or module import archive, in order (they hold secret values). */
    public final List<byte[]> imported = new CopyOnWriteArrayList<>();
    /** The active-flag option of every workflow or module import ({@code null}: none). */
    public final List<Boolean> importFlags = new CopyOnWriteArrayList<>();
    /** Every repository import archive, in order. */
    public final List<byte[]> repositoryImports = new CopyOnWriteArrayList<>();
    /** The next repository import fails with {@code 1-NOK} and changes nothing. */
    public volatile boolean refuseNextRepositoryImport;
    /** The next workflow or module import applies only its first artifact (protocol: all). */
    public volatile boolean partialNextImport;
    /** After the next import, this text of the stored workflow and module files is replaced. */
    public volatile String[] tamperNextImport;

    /** A server with the diagram groups and modules of {@code exports}. */
    public static FakeServer of(byte[]... exports) {
        FakeServer server = new FakeServer();
        for (byte[] export : exports) {
            server.load(export);
        }
        return server;
    }

    /** Adds the diagram groups, workflows and modules of a diagram group export. */
    public synchronized FakeServer load(byte[] export) {
        Map<String, byte[]> entries = ArtifactFixtures.entries(export);
        if (entries.containsKey(INDEX)) {
            for (Element group : XmlTree.parse(entries.get(INDEX)).root().elements().get(0)
                .elements()) {
                String pluginType = group.child("ModuleGroupName").orElseThrow().text();
                for (Element entry : group.elements()) {
                    if (entry.localName().equals("Module")) {
                        String name = entry.child("ModuleName").orElseThrow().text();
                        modules.put(name, new Module(pluginType, entry, entries.get("module/"
                            + name.toLowerCase(Locale.ROOT) + ".xml"), version(entry)));
                    }
                }
            }
        }
        if (entries.containsKey(WORKFLOWS)) {
            for (Element group : XmlTree.parse(entries.get(WORKFLOWS)).root().elements().get(0)
                .elements()) {
                String name = group.child("WorkflowGroupName").orElseThrow().text();
                Map<String, Workflow> workflows = groups.computeIfAbsent(name,
                    n -> new LinkedHashMap<>());
                for (Element workflow : group.elements()) {
                    if (workflow.localName().equals("Workflow")) {
                        workflows.put(name(workflow), new Workflow(workflow, version(workflow)));
                    }
                }
            }
        }
        return this;
    }

    // --- reads ---------------------------------------------------------------------------------

    /** The diagram groups, in load order. */
    public synchronized List<String> diagramGroups() {
        return groups.entrySet().stream().filter(e -> !e.getValue().isEmpty())
            .map(Map.Entry::getKey).toList();
    }

    /** True if diagram group {@code group} has a workflow (StartCLI: not found otherwise). */
    public synchronized boolean hasDiagramGroup(String group) {
        return !groups.getOrDefault(group, Map.of()).isEmpty();
    }

    /** True if the owner has module {@code name}. */
    public synchronized boolean hasModule(String name) {
        return modules.containsKey(name);
    }

    /** The plugin type of module {@code name}. */
    public synchronized Optional<String> pluginType(String name) {
        return Optional.ofNullable(modules.get(name)).map(Module::pluginType);
    }

    /** The version of workflow or module {@code name}. */
    public synchronized Optional<Integer> version(String name) {
        for (Map<String, Workflow> workflows : groups.values()) {
            if (workflows.containsKey(name)) {
                return Optional.of(workflows.get(name).version());
            }
        }
        return Optional.ofNullable(modules.get(name)).map(Module::version);
    }

    /** Workflow name → its diagram group (the REST diagram list of the owner). */
    public synchronized Map<String, String> diagrams() {
        Map<String, String> diagrams = new LinkedHashMap<>();
        groups.forEach((group, workflows) -> workflows.keySet()
            .forEach(name -> diagrams.put(name, group)));
        return diagrams;
    }

    /** Module name → its plugin type (the module list of the owner). */
    public synchronized Map<String, String> moduleTypes() {
        Map<String, String> types = new LinkedHashMap<>();
        modules.forEach((name, module) -> types.put(name, module.pluginType()));
        return types;
    }

    /** The modules the nodes of workflow {@code name} run (the REST model of the workflow). */
    public synchronized List<String> modulesOf(String name) {
        for (Map<String, Workflow> workflows : groups.values()) {
            if (workflows.containsKey(name)) {
                return used(workflows.get(name).element());
            }
        }
        throw new IllegalStateException("no workflow " + name);
    }

    /** The {@code IsActive} flag of workflow {@code name}. */
    public synchronized Optional<Boolean> active(String name) {
        for (Map<String, Workflow> workflows : groups.values()) {
            if (workflows.containsKey(name)) {
                return workflows.get(name).element().child("IsActive").map(Element::text)
                    .map("true"::equals);
            }
        }
        return Optional.empty();
    }

    // --- exports -------------------------------------------------------------------------------

    /**
     * The export of diagram group {@code group} (head versions): its workflows, the modules they
     * use, and the repository files those reference; empty if the group does not exist.
     */
    public synchronized Optional<byte[]> exportWorkflowGroup(String group) {
        if (!hasDiagramGroup(group)) {
            return Optional.empty();
        }
        Map<String, Workflow> workflows = groups.get(group);
        Map<String, Module> used = usedModules(workflows.values());
        return Optional.of(archive(Map.of(group, workflows), used, references(used.values())
            .stream().filter(repository::containsKey).collect(TreeMap::new,
                (map, path) -> map.put(path, head(path)), Map::putAll), null));
    }

    /** The module-only export of {@code name}, empty if missing or of another plugin type. */
    public synchronized Optional<byte[]> exportModule(String pluginType, String name) {
        Module module = modules.get(name);
        if (module == null || !module.pluginType().equals(pluginType)) {
            return Optional.empty();
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("archive.properties", PROPERTIES);
        entries.put("workflow/", new byte[0]);
        entries.put(INDEX, index(Map.of(name, module), null));
        entries.put(file(name), module.file());
        entries.put("Repository.zip", repositoryZip(null, Map.of(), null));
        return Optional.of(ArtifactFixtures.zip(entries));
    }

    /**
     * The repository export of a file or folder (head versions, export shape); empty if the
     * path does not exist (StartCLI: "Path not found").
     */
    public synchronized Optional<byte[]> exportRepository(String path) {
        Map<String, RepositoryVersion> files = new TreeMap<>();
        repository.forEach((file, versions) -> {
            if (file.equals(path) || file.startsWith(path + "/")) {
                files.put(file, versions.get(versions.size() - 1));
            }
        });
        if (files.isEmpty()) {
            return Optional.empty();
        }
        String directory = files.containsKey(path) ? path.substring(0, path.lastIndexOf('/'))
            : path;
        return Optional.of(repositoryZip(directory.substring(1) + "/", files, null));
    }

    /**
     * The owner-wide release export of {@code tag} (research D-1): every diagram group that
     * carries the tag, in its tagged state ({@code version="<n>"}, {@code @@@Tag: <tag>@@@}, a
     * {@code tag} attribute on every node, {@code usertags.xml}) with the tagged modules and
     * repository files; without such a group an archive without workflows.
     */
    public synchronized byte[] exportRelease(String tag) {
        Map<String, Tagged> tagged = tags.getOrDefault(tag, Map.of());
        if (tagged.isEmpty()) {
            Map<String, byte[]> empty = new LinkedHashMap<>();
            empty.put("archive.properties", PROPERTIES);
            empty.put(WORKFLOWS, document(element("Workflows", List.of())));
            return ArtifactFixtures.zip(empty);
        }
        Map<String, Map<String, Workflow>> releaseGroups = new TreeMap<>();
        Map<String, Module> releaseModules = new TreeMap<>();
        Map<String, RepositoryVersion> releaseRepository = new TreeMap<>();
        tagged.forEach((group, state) -> {
            releaseGroups.put(group, state.workflows());
            releaseModules.putAll(state.modules());
            releaseRepository.putAll(state.repository());
        });
        return archive(releaseGroups, releaseModules, releaseRepository, tag);
    }

    // --- writes outside StartCLI imports -------------------------------------------------------

    /**
     * {@code tag --tagMove <tag> --tagWorkflowGroup <group> …}: the tag moves to the current
     * versions of the group's workflows and modules and of the repository files they reference.
     */
    public synchronized void tag(String group, String tag) {
        Map<String, Workflow> workflows = new LinkedHashMap<>(groups.getOrDefault(group,
            Map.of()));
        Map<String, Module> used = usedModules(workflows.values());
        Map<String, RepositoryVersion> files = new TreeMap<>();
        for (String path : references(used.values())) {
            List<RepositoryVersion> versions = repository.get(path);
            if (versions != null) {
                RepositoryVersion head = versions.get(versions.size() - 1);
                Set<String> withTag = new TreeSet<>(head.tags());
                withTag.add(tag);
                RepositoryVersion tagged = new RepositoryVersion(head.version(), head.uuid(),
                    head.comment(), head.description(), head.content(), Set.copyOf(withTag));
                versions.set(versions.size() - 1, tagged);
                files.put(path, tagged);
            }
        }
        tags.computeIfAbsent(tag, t -> new TreeMap<>()).put(group,
            new Tagged(workflows, used, files));
    }

    /**
     * A person publishes workflow {@code name} from the Workbench: its file text is changed by
     * {@code change} and it gets the next version.
     */
    public synchronized void publishWorkflow(String name, UnaryOperator<String> change) {
        for (Map<String, Workflow> workflows : groups.values()) {
            Workflow workflow = workflows.get(name);
            if (workflow != null) {
                String before = new String(XmlNormalizer.normalize(workflow.element()),
                    StandardCharsets.UTF_8);
                String after = change.apply(before);
                if (after.equals(before)) {
                    throw new IllegalStateException("the change does not apply");
                }
                workflows.put(name, new Workflow(XmlTree.parse(after.getBytes(
                    StandardCharsets.UTF_8)).root(), workflow.version() + 1));
                return;
            }
        }
        throw new IllegalStateException("no workflow " + name);
    }

    /** A person publishes module {@code name}: its module file changes, next version. */
    public synchronized void publishModule(String name, UnaryOperator<String> change) {
        Module module = modules.get(name);
        String before = new String(module.file(), StandardCharsets.UTF_8);
        String after = change.apply(before);
        if (after.equals(before)) {
            throw new IllegalStateException("the change does not apply");
        }
        modules.put(name, new Module(module.pluginType(), module.entry(),
            after.getBytes(StandardCharsets.UTF_8), module.version() + 1));
    }

    /** A person copies workflow {@code from} as {@code to} into diagram group {@code group}. */
    public synchronized void copyWorkflow(String from, String to, String group) {
        for (Map<String, Workflow> workflows : new ArrayList<>(groups.values())) {
            Workflow workflow = workflows.get(from);
            if (workflow != null) {
                String text = new String(XmlNormalizer.normalize(workflow.element()),
                    StandardCharsets.UTF_8).replace("<WorkflowName>" + from + "</WorkflowName>",
                    "<WorkflowName>" + to + "</WorkflowName>");
                groups.computeIfAbsent(group, g -> new LinkedHashMap<>()).put(to, new Workflow(
                    XmlTree.parse(text.getBytes(StandardCharsets.UTF_8)).root(), 1));
                return;
            }
        }
        throw new IllegalStateException("no workflow " + from);
    }

    /** A person opens workflow {@code name} in Workbench edit mode. */
    public synchronized void editMode(String name, String user) {
        for (Map<String, Workflow> workflows : groups.values()) {
            Workflow workflow = workflows.get(name);
            if (workflow != null) {
                List<Node> children = new ArrayList<>(workflow.element().children());
                int at = 0;
                for (int i = 0; i < children.size(); i++) {
                    if (children.get(i) instanceof Element e
                        && e.localName().equals("CheckinComment")) {
                        at = i + 1;
                    }
                }
                children.add(at, leaf("CheckoutUser", user));
                workflows.put(name, new Workflow(workflow.element().withChildren(children),
                    workflow.version()));
                return;
            }
        }
        throw new IllegalStateException("no workflow " + name);
    }

    /** Writes repository file {@code path} as a Workbench user would (next version). */
    public synchronized void putRepositoryFile(String path, String content) {
        store(path, content.getBytes(StandardCharsets.UTF_8), "Fixture file", "Fixture edit");
    }

    /** The head version of repository file {@code path}, e.g. {@code 1.1}. */
    public synchronized Optional<String> repositoryVersion(String path) {
        return Optional.ofNullable(repository.get(path)).map(v -> v.get(v.size() - 1).version());
    }

    /** The {@code uuid} of repository file {@code path} (stable across versions). */
    public synchronized Optional<String> repositoryUuid(String path) {
        return Optional.ofNullable(repository.get(path)).map(v -> v.get(v.size() - 1).uuid());
    }

    /** The head content of repository file {@code path}. */
    public synchronized Optional<String> repositoryContent(String path) {
        return Optional.ofNullable(repository.get(path)).map(v -> new String(
            v.get(v.size() - 1).content(), StandardCharsets.UTF_8));
    }

    // --- imports -------------------------------------------------------------------------------

    /**
     * Applies a workflow or module archive and returns StartCLI's stdout with the protocol:
     * every artifact matched by name, replaced or added, new UIDs, the next version, the
     * archive's check-in comment; {@code active} (the flag option) sets {@code IsActive} of
     * every imported workflow.
     */
    public synchronized String importArchive(byte[] zip, Boolean active) {
        imported.add(zip.clone());
        importFlags.add(active);
        Map<String, byte[]> archive = ArtifactFixtures.entries(zip);
        List<String[]> rows = new ArrayList<>();
        partial = partialNextImport;
        partialNextImport = false;
        int applied = 0;
        if (archive.containsKey(INDEX)) {
            for (Element group : XmlTree.parse(archive.get(INDEX)).root().elements().get(0)
                .elements()) {
                String pluginType = group.child("ModuleGroupName").orElseThrow().text();
                for (Element entry : group.elements()) {
                    if (!entry.localName().equals("Module")) {
                        continue;
                    }
                    String name = entry.child("ModuleName").orElseThrow().text();
                    Module old = modules.get(name);
                    rows.add(new String[] {"Module [" + name + "] was " + (old == null
                        ? "created." : "modified."), "/" + name});
                    if (skip(applied++)) {
                        continue;
                    }
                    byte[] file = archive.get(file(name));
                    modules.put(name, new Module(pluginType, withValue(entry, "ModuleUId",
                        "-fake:" + ++uid), file != null ? file : old.file(),
                        old == null ? 1 : old.version() + 1));
                }
            }
        }
        if (archive.containsKey(WORKFLOWS)) {
            for (Element group : XmlTree.parse(archive.get(WORKFLOWS)).root().elements().get(0)
                .elements()) {
                String groupName = group.child("WorkflowGroupName").orElseThrow().text();
                Map<String, Workflow> workflows = groups.computeIfAbsent(groupName,
                    n -> new LinkedHashMap<>());
                for (Element element : group.elements()) {
                    if (!element.localName().equals("Workflow")) {
                        continue;
                    }
                    String name = name(element);
                    Workflow old = workflows.get(name);
                    for (Map<String, Workflow> other : groups.values()) {
                        if (old == null && other != workflows) {
                            old = other.remove(name); // INUBIT moves it into this group
                        }
                    }
                    rows.add(new String[] {"Diagram [" + name + "] was " + (old == null
                        ? "created." : "modified."), name});
                    if (skip(applied++)) {
                        if (old != null) {
                            workflows.put(name, old);
                        }
                        continue;
                    }
                    Element stored = withValue(element, "WorkflowUId", "-fake:" + ++uid);
                    if (active != null) {
                        stored = withValue(stored, "IsActive", active.toString());
                    }
                    workflows.put(name, new Workflow(stored, old == null ? 1
                        : old.version() + 1));
                }
            }
        }
        String[] tamper = tamperNextImport;
        tamperNextImport = null;
        if (tamper != null) {
            modules.replaceAll((name, module) -> new Module(module.pluginType(), module.entry(),
                new String(module.file(), StandardCharsets.UTF_8).replace(tamper[0], tamper[1])
                    .getBytes(StandardCharsets.UTF_8), module.version()));
            for (Map<String, Workflow> workflows : groups.values()) {
                workflows.replaceAll((name, workflow) -> new Workflow(XmlTree.parse(new String(
                    XmlNormalizer.normalize(workflow.element()), StandardCharsets.UTF_8)
                    .replace(tamper[0], tamper[1]).getBytes(StandardCharsets.UTF_8)).root(),
                    workflow.version()));
            }
        }
        return protocol(rows);
    }

    private boolean partial;

    private boolean skip(int index) {
        return partial && index > 0;
    }

    /**
     * The history export of diagram group {@code group} ({@code versionHistory.xml}): the
     * current version of each workflow and of each module they use, with the tags that mark
     * exactly that version.
     */
    public synchronized Optional<byte[]> exportHistory(String group) {
        if (!hasDiagramGroup(group)) {
            return Optional.empty();
        }
        Map<String, Workflow> workflows = groups.get(group);
        StringBuilder xml = new StringBuilder("<VersionInformation><Workflows><WorkflowGroup"
            + " Name=\"" + group + "\">");
        workflows.forEach((name, workflow) -> xml.append("<Workflow Name=\"").append(name)
            .append("\" Type=\"technical\">").append(version(workflow.version(), tagsOf(group,
                name, workflow.version(), true))).append("</Workflow>"));
        xml.append("</WorkflowGroup></Workflows><Modules>");
        usedModules(workflows.values()).forEach((name, module) -> xml.append("<Module Name=\"")
            .append(name).append("\">").append(version(module.version(), tagsOf(group, name,
                module.version(), false))).append("</Module>"));
        xml.append("</Modules></VersionInformation>");
        return Optional.of(ArtifactFixtures.zip(Map.of("versionHistory.xml",
            xml.toString().getBytes(StandardCharsets.UTF_8))));
    }

    private Set<String> tagsOf(String group, String name, int version, boolean workflow) {
        Set<String> result = new TreeSet<>();
        tags.forEach((tag, byGroup) -> byGroup.forEach((taggedGroup, state) -> {
            if (workflow && taggedGroup.equals(group) && state.workflows().containsKey(name)
                && state.workflows().get(name).version() == version) {
                result.add(tag);
            }
            if (!workflow && state.modules().containsKey(name)
                && state.modules().get(name).version() == version) {
                result.add(tag);
            }
        }));
        return result;
    }

    private static String version(int version, Set<String> tags) {
        StringBuilder xml = new StringBuilder("<Version><versionNode>").append(version)
            .append("</versionNode><CheckinUser>jdoe</CheckinUser><DateTime>07.10.2026"
                + " 10:00:00</DateTime>");
        if (!tags.isEmpty()) {
            xml.append("<Tags>");
            tags.forEach(tag -> xml.append("<Tag>").append(tag).append("</Tag>"));
            xml.append("</Tags>");
        }
        return xml.append("</Version>").toString();
    }

    /**
     * Applies a repository import into {@code /Root/<owner>} (entries relative to it) as probed
     * (research D-1): a new file gets version {@code 1.0} and a server {@code uuid}, an existing
     * one the next version with its {@code uuid}; the archive's {@code versionComment} is
     * ignored, the stored one grows by {@code DefaultCommitCommentImport@@@}. No protocol.
     */
    public synchronized String importRepository(byte[] zip, String owner) {
        repositoryImports.add(zip.clone());
        if (refuseNextRepositoryImport) {
            refuseNextRepositoryImport = false;
            return "JAVA_HOME is set\nPassword: \n1-NOK: Import failed.\n";
        }
        for (var file : RepositoryArchive.readRelative(owner, zip)) {
            Matcher description = Pattern.compile("<Description>([^<]*)</Description>")
                .matcher(new String(file.metadata(), StandardCharsets.UTF_8));
            store(file.path(), file.content(), description.find() ? description.group(1) : "",
                null);
        }
        return "JAVA_HOME is set\nPassword: \nCompleted = 0 MB / 0 MB\n"
            + "1-OK: Imported successfully\n";
    }

    // --- archives ------------------------------------------------------------------------------

    private byte[] archive(Map<String, Map<String, Workflow>> workflowGroups,
        Map<String, Module> used, Map<String, RepositoryVersion> files, String tag) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("archive.properties", PROPERTIES);
        entries.put("Repository.zip", repositoryZip(null, files, tag));
        List<Node> groupElements = new ArrayList<>();
        workflowGroups.forEach((group, workflows) -> {
            List<Node> children = new ArrayList<>(List.of(leaf("WorkflowGroupName", group)));
            workflows.values().forEach(workflow -> children.add(exported(workflow.element(),
                workflow.version(), tag)));
            groupElements.add(new Element("", "WorkflowGroup", "", List.of(),
                List.of(new Attribute("", "workflowType", "", "technical")), children));
        });
        entries.put(WORKFLOWS, document(element("Workflows", groupElements)));
        if (tag != null) {
            entries.put("usertags.xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<IBISTagging version=\"5.3\"><ActiveTag/><Tags><Tag>" + tag + "</Tag></Tags>"
                + "<Comments/></IBISTagging>").getBytes(StandardCharsets.UTF_8));
        }
        entries.put(INDEX, index(used, tag));
        used.forEach((name, module) -> entries.put(file(name), module.file()));
        return ArtifactFixtures.zip(entries);
    }

    private static byte[] index(Map<String, Module> used, String tag) {
        Map<String, List<Node>> byType = new LinkedHashMap<>();
        used.values().forEach(module -> byType.computeIfAbsent(module.pluginType(),
            type -> new ArrayList<>(List.of(leaf("ModuleGroupName", type))))
            .add(exported(module.entry(), module.version(), tag)));
        List<Node> groups = new ArrayList<>();
        byType.values().forEach(children -> groups.add(element("ModuleGroup", children)));
        return document(element("Modules", groups));
    }

    /**
     * The element as exported: {@code version="head"} (or the tagged version), the version in
     * the export suffix of the check-in comment, {@code @@@Tag: <tag>@@@} and {@code tag} on
     * the nodes of a tagged export.
     */
    private static Element exported(Element element, int version, String tag) {
        List<Attribute> attributes = new ArrayList<>();
        boolean hasVersion = false;
        for (Attribute attribute : element.attributes()) {
            if (attribute.localName().equals("version")) {
                hasVersion = true;
                attributes.add(new Attribute("", "version", "", tag == null ? "head"
                    : Integer.toString(version)));
            } else {
                attributes.add(attribute);
            }
        }
        if (!hasVersion) {
            attributes.add(0, new Attribute("", "version", "", tag == null ? "head"
                : Integer.toString(version)));
        }
        Element result = element.withAttributes(attributes);
        Optional<Element> comment = result.child("CheckinComment");
        if (comment.isPresent()) {
            String text = comment.get().text();
            Matcher matcher = VERSION.matcher(text);
            text = matcher.find() ? matcher.replaceFirst("@@@Version: " + version + "@@@")
                : text + "@@@Deploying User: jdoe@@@Server: fake.example.test@@@Version: "
                    + version + "@@@Export/Deployment: 07.10.2026 10:00:00@@@";
            if (tag != null) {
                text = text.replace("@@@Export/Deployment: ", "@@@Tag: " + tag
                    + "@@@Export/Deployment: ");
            }
            result = withValue(result, "CheckinComment", text);
        }
        if (tag != null) {
            String tagValue = tag;
            result = result.withChildren(result.children().stream().map(child ->
                child instanceof Element node && node.localName().equals("WorkflowModule")
                    ? node.withAttributes(append(node.attributes(), new Attribute("", "tag", "",
                        tagValue))) : child).toList());
        }
        return result;
    }

    private static List<Attribute> append(List<Attribute> attributes, Attribute attribute) {
        List<Attribute> result = new ArrayList<>(attributes);
        result.add(attribute);
        return result;
    }

    private static byte[] document(Element child) {
        return XmlNormalizer.normalize(new Element("", "IBISWorkflow", "", List.of(),
            List.of(new Attribute("", "version", "", DOCUMENT_VERSION)), List.of(child)));
    }

    private Map<String, Module> usedModules(Iterable<Workflow> workflows) {
        Map<String, Module> used = new LinkedHashMap<>();
        for (Workflow workflow : workflows) {
            for (String name : used(workflow.element())) {
                Module module = modules.get(name);
                if (module != null) {
                    used.put(name, module);
                }
            }
        }
        return used;
    }

    private static List<String> used(Element workflow) {
        Set<String> names = new LinkedHashSet<>();
        for (Element node : workflow.elements()) {
            if (node.localName().equals("WorkflowModule")) {
                node.child("ModuleName").map(Element::text).ifPresent(names::add);
            }
        }
        return List.copyOf(names);
    }

    private static Set<String> references(Iterable<Module> used) {
        Set<String> paths = new TreeSet<>();
        for (Module module : used) {
            if (module.file() != null) {
                Matcher matcher = REFERENCE.matcher(new String(module.file(),
                    StandardCharsets.UTF_8));
                while (matcher.find()) {
                    paths.add(matcher.group(1));
                }
            }
        }
        return paths;
    }

    private RepositoryVersion head(String path) {
        List<RepositoryVersion> versions = repository.get(path);
        return versions.get(versions.size() - 1);
    }

    /** Stores a new version; {@code comment} {@code null}: an import (D-1 comment growth). */
    private void store(String path, byte[] content, String description, String comment) {
        List<RepositoryVersion> versions = repository.computeIfAbsent(path,
            p -> new ArrayList<>());
        RepositoryVersion head = versions.isEmpty() ? null : versions.get(versions.size() - 1);
        String version = head == null ? "1.0" : "1." + versions.size();
        String uuid = head == null ? UUID.nameUUIDFromBytes((path + "@fake")
            .getBytes(StandardCharsets.UTF_8)).toString() : head.uuid();
        String stored = comment != null ? comment : "DefaultCommitCommentImport@@@"
            + (head == null ? "null" : head.comment());
        versions.add(new RepositoryVersion(version, uuid, stored, description, content.clone(),
            Set.of()));
    }

    private static byte[] repositoryZip(String directory, Map<String, RepositoryVersion> files,
        String tag) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            if (directory != null) {
                out.putNextEntry(new ZipEntry(directory));
                out.closeEntry();
            }
            for (Map.Entry<String, RepositoryVersion> file : files.entrySet()) {
                String entry = file.getKey().substring(1);
                out.putNextEntry(new ZipEntry(entry + ".xml"));
                out.write(metadata(file.getKey(), file.getValue(), tag));
                out.closeEntry();
                out.putNextEntry(new ZipEntry(entry + ".dat"));
                out.write(file.getValue().content());
                out.closeEntry();
            }
            out.setComment(DOCUMENT_VERSION);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static byte[] metadata(String path, RepositoryVersion version, String tag) {
        String md5;
        try {
            md5 = String.format("%032x", new BigInteger(1, MessageDigest.getInstance("MD5")
                .digest(version.content())));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Property name=\""
            + path.substring(path.lastIndexOf('/') + 1) + "\" type=\"RepositoryFile\""
            + " anonymousRead=\"true\" versionComment=\"" + version.comment() + "\""
            + " writeable=\"true\" uuid=\"" + version.uuid() + "\" path=\"" + path + "\""
            + " modificator=\"jdoe\" contentSize=\"" + version.content().length + "\""
            + " modified=\"2026-10-07T10:00:00\" contentEncoding=\"UTF-8\""
            + " contentType=\"application/octet-stream\" systemElement=\"false\""
            + " contentMD5=\"" + md5 + "\"" + (tag != null && version.tags().contains(tag)
                ? " tagName=\"" + tag + "\"" : "") + " version=\"" + version.version()
            + "\"><Description>" + version.description() + "</Description></Property>")
            .getBytes(StandardCharsets.UTF_8);
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

    private static String file(String module) {
        return "module/" + module.toLowerCase(Locale.ROOT) + ".xml";
    }

    private static String name(Element workflow) {
        return workflow.child("WorkflowName").orElseThrow().text();
    }

    private static int version(Element element) {
        Matcher matcher = VERSION.matcher(element.child("CheckinComment").map(Element::text)
            .orElse(""));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 1;
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

    private static Element leaf(String name, String text) {
        return new Element("", name, "", List.of(), List.of(), List.of(new Text(text)));
    }

    private static Element element(String name, List<? extends Node> children) {
        return new Element("", name, "", List.of(), List.of(), List.copyOf(children));
    }

    // --- synthetic releases of any size (SC-007) -----------------------------------------------

    /**
     * {@code groupCount} diagram groups {@code GRP-01}… with {@code workflowsPerGroup}
     * workflows (copies of {@code Workflow-0001} of {@code grp-a.zip}, each with four nodes) and
     * {@code modulesPerGroup} modules each (copies of the Assign module {@code Module-0003});
     * workflow {@code i} of a group runs four of the group's modules.
     */
    public static FakeServer synthetic(int groupCount, int workflowsPerGroup,
        int modulesPerGroup) {
        Map<String, byte[]> fixture = ArtifactFixtures.entries(
            ImportHarness.withoutEditMode());
        Element workflowTemplate = XmlTree.parse(fixture.get(WORKFLOWS)).root().elements()
            .get(0).elements().get(0).elements().stream()
            .filter(e -> e.localName().equals("Workflow")).findFirst().orElseThrow();
        Element moduleTemplate = XmlTree.parse(fixture.get(INDEX)).root().elements().get(0)
            .elements().stream().flatMap(g -> g.elements().stream())
            .filter(e -> e.localName().equals("Module") && e.child("ModuleName")
                .map(Element::text).filter("Module-0003"::equals).isPresent())
            .findFirst().orElseThrow();
        byte[] moduleFile = fixture.get("module/module-0003.xml");
        String workflowText = new String(XmlNormalizer.normalize(workflowTemplate),
            StandardCharsets.UTF_8);
        String moduleText = new String(XmlNormalizer.normalize(moduleTemplate),
            StandardCharsets.UTF_8);
        FakeServer server = new FakeServer();
        for (int g = 1; g <= groupCount; g++) {
            String group = "GRP-%02d".formatted(g);
            Map<String, Workflow> workflows = server.groups.computeIfAbsent(group,
                n -> new LinkedHashMap<>());
            List<String> names = new ArrayList<>();
            for (int m = 0; m < modulesPerGroup; m++) {
                String name = "Module-%04d".formatted(g * 1000 + m);
                names.add(name);
                server.modules.put(name, new Module("Assign", XmlTree.parse(moduleText
                    .replace("Module-0003", name).getBytes(StandardCharsets.UTF_8)).root(),
                    moduleFile, 1));
            }
            for (int w = 0; w < workflowsPerGroup; w++) {
                String name = "Workflow-%04d".formatted(g * 1000 + w);
                String text = workflowText.replace("Workflow-0001", name);
                for (int k = 1; k <= 4; k++) {
                    text = text.replace("Module-000" + k, "Module-X" + k);
                }
                for (int k = 1; k <= 4; k++) {
                    text = text.replace("Module-X" + k, names.get((w * 4 + k - 1)
                        % names.size()));
                }
                workflows.put(name, new Workflow(XmlTree.parse(text.getBytes(
                    StandardCharsets.UTF_8)).root(), 1));
            }
        }
        return server;
    }
}
