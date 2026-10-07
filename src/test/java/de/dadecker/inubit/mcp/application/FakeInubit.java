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
 * protocol. Knobs simulate the failure modes of the spike and of research D-10.
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

    public FakeInubit(byte[] export) {
        this.entries = new LinkedHashMap<>(ArtifactFixtures.entries(export));
    }

    /** The current export of the diagram group (feature 005: with its repository files). */
    public synchronized byte[] exportWorkflowGroup() {
        return ArtifactFixtures.zip(groupEntries());
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
                        entries.put(file, archive.get(file));
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

    // --- feature 005 (T010): releases, repository files, active flags ------------------------

    /** Every repository import archive, in order. */
    public final List<byte[]> repositoryImports = new CopyOnWriteArrayList<>();
    /** The active flag of every workflow or module import ({@code null}: no flag option). */
    public final List<Boolean> importFlags = new CopyOnWriteArrayList<>();
    /** The next repository import fails with {@code 1-NOK} and changes nothing. */
    public volatile boolean refuseNextRepositoryImport;

    /** Repository path → its versions, oldest first. */
    private final java.util.TreeMap<String, List<RepositoryVersion>> repository =
        new java.util.TreeMap<>();
    /** Tag → the tagged state of the diagram group and its referenced repository files. */
    private final Map<String, Tagged> tags = new LinkedHashMap<>();

    /** One version of a repository file. */
    private record RepositoryVersion(String version, String uuid, String comment,
        String description, String modified, byte[] content, java.util.Set<String> tags) {
    }

    /** The tagged state: the group's entries and the tagged repository versions. */
    private record Tagged(Map<String, byte[]> entries, Map<String, RepositoryVersion> files) {
    }

    /**
     * A server without diagram group {@code diagramGroup} and without modules (everything a
     * deployment brings is new there); an import creates them.
     */
    public static FakeInubit without(String diagramGroup) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("archive.properties", "#\nsourceVersion=8.1.17\n"
            .getBytes(StandardCharsets.UTF_8));
        entries.put("Repository.zip", ArtifactFixtures.zip(Map.of()));
        entries.put(WORKFLOWS, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<IBISWorkflow"
            + " version=\"5.3\"><Workflows><WorkflowGroup workflowType=\"technical\">"
            + "<WorkflowGroupName>" + diagramGroup + "</WorkflowGroupName></WorkflowGroup>"
            + "</Workflows></IBISWorkflow>").getBytes(StandardCharsets.UTF_8));
        entries.put(INDEX, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<IBISWorkflow"
            + " version=\"5.3\"><Modules/></IBISWorkflow>").getBytes(StandardCharsets.UTF_8));
        return new FakeInubit(ArtifactFixtures.zip(entries));
    }

    /** True if the diagram group has at least one workflow (StartCLI: not found otherwise). */
    public synchronized boolean hasDiagramGroup() {
        return workflowXml().contains("<WorkflowName>");
    }

    /**
     * Writes repository file {@code path} as a Workbench user would: version {@code 1.0}, or the
     * next version of an existing file.
     */
    public synchronized void putRepositoryFile(String path, String content) {
        store(path, content.getBytes(StandardCharsets.UTF_8), "Fixture file", "Fixture edit");
    }

    /** The head version of repository file {@code path}, e.g. {@code 1.1}. */
    public synchronized java.util.Optional<String> repositoryVersion(String path) {
        return head(path).map(RepositoryVersion::version);
    }

    /** The {@code uuid} of repository file {@code path} (stable across versions). */
    public synchronized java.util.Optional<String> repositoryUuid(String path) {
        return head(path).map(RepositoryVersion::uuid);
    }

    /**
     * The repository export of a file or folder (head versions, export shape: a directory
     * entry, then {@code Root/…/<name>.xml} and {@code .dat} per file, archive comment
     * {@code 5.3}); empty if the path does not exist (StartCLI: "Path not found").
     */
    public synchronized java.util.Optional<byte[]> exportRepository(String path) {
        Map<String, RepositoryVersion> files = new java.util.TreeMap<>();
        repository.forEach((file, versions) -> {
            if (file.equals(path) || file.startsWith(path + "/")) {
                files.put(file, versions.get(versions.size() - 1));
            }
        });
        if (files.isEmpty()) {
            return java.util.Optional.empty();
        }
        String directory = files.containsKey(path) ? path.substring(0, path.lastIndexOf('/'))
            : path;
        return java.util.Optional.of(repositoryZip(directory.substring(1) + "/", files, null));
    }

    /**
     * Applies a repository import into {@code /Root/<owner>} (entries relative to it) as probed
     * (research D-1): a new file gets version {@code 1.0} and a server {@code uuid} (the
     * archive's is ignored), an existing one the next version with its {@code uuid}; the
     * archive's {@code versionComment} is ignored, the stored one grows by
     * {@code DefaultCommitCommentImport@@@}; the {@code Description} is taken. Returns StartCLI's
     * stdout (no protocol).
     */
    public synchronized String importRepository(byte[] zip, String owner) {
        repositoryImports.add(zip.clone());
        if (refuseNextRepositoryImport) {
            refuseNextRepositoryImport = false;
            return "JAVA_HOME is set\nPassword: \n1-NOK: Import failed.\n";
        }
        for (var file : de.dadecker.inubit.mcp.adapter.archive.v81.RepositoryArchive
            .readRelative(owner, zip)) {
            java.util.regex.Matcher description = java.util.regex.Pattern.compile(
                "<Description>([^<]*)</Description>").matcher(new String(file.metadata(),
                StandardCharsets.UTF_8));
            store(file.path(), file.content(), description.find() ? description.group(1) : "",
                null);
        }
        return "JAVA_HOME is set\nPassword: \nCompleted = 0 MB / 0 MB\n"
            + "1-OK: Imported successfully\n";
    }

    /**
     * {@code tag --tagMove <tag> --tagWorkflowGroup <group> …}: records the current state of the
     * diagram group and tags the repository files its modules reference
     * ({@code inubitrepository:} in an {@code href} or {@code schemaLocation}, research D-1).
     */
    public synchronized void tag(String tag) {
        Map<String, RepositoryVersion> files = new java.util.TreeMap<>();
        for (String path : references()) {
            List<RepositoryVersion> versions = repository.get(path);
            if (versions != null) {
                RepositoryVersion head = versions.get(versions.size() - 1);
                java.util.Set<String> tagged = new java.util.TreeSet<>(head.tags());
                tagged.add(tag);
                RepositoryVersion withTag = new RepositoryVersion(head.version(), head.uuid(),
                    head.comment(), head.description(), head.modified(), head.content(),
                    java.util.Set.copyOf(tagged));
                versions.set(versions.size() - 1, withTag);
                files.put(path, withTag);
            }
        }
        tags.put(tag, new Tagged(new LinkedHashMap<>(groupEntries()), files));
    }

    /**
     * The owner-wide release export of {@code tag} (research D-1): the tagged state of the
     * diagram group — {@code version="<n>"} instead of {@code head}, {@code @@@Tag: <tag>@@@} in
     * every check-in comment, {@code tag="<tag>"} on every node, {@code usertags.xml} — with the
     * tagged repository files; without the tag an archive without workflows.
     */
    public synchronized byte[] exportRelease(String tag) {
        Tagged tagged = tags.get(tag);
        if (tagged == null) {
            Map<String, byte[]> empty = new LinkedHashMap<>();
            empty.put("archive.properties", entries.get("archive.properties"));
            empty.put(WORKFLOWS, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<IBISWorkflow"
                + " version=\"5.3\"><Workflows/></IBISWorkflow>").getBytes(StandardCharsets.UTF_8));
            return ArtifactFixtures.zip(empty);
        }
        Map<String, byte[]> release = new LinkedHashMap<>();
        tagged.entries().forEach((name, bytes) -> {
            switch (name) {
                case WORKFLOWS -> {
                    release.put(name, tagged(new String(bytes, StandardCharsets.UTF_8),
                        "Workflow", tag).replace("<WorkflowModule ", "<WorkflowModule tag=\""
                            + tag + "\" ").getBytes(StandardCharsets.UTF_8));
                    release.put("usertags.xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "\n<IBISTagging version=\"5.3\"><ActiveTag/><Tags><Tag>" + tag
                        + "</Tag></Tags><Comments/></IBISTagging>")
                        .getBytes(StandardCharsets.UTF_8));
                }
                case INDEX -> release.put(name, tagged(new String(bytes,
                    StandardCharsets.UTF_8), "Module", tag).getBytes(StandardCharsets.UTF_8));
                case "Repository.zip" -> release.put(name, repositoryZip(null,
                    tagged.files(), tag));
                default -> release.put(name, bytes);
            }
        });
        return ArtifactFixtures.zip(release);
    }

    /**
     * Applies {@code zip} like {@link #importArchive(byte[])}; {@code active} is the flag option
     * of the import ({@code --importWorkflowActive} / {@code --importWorkflowInactive}), which
     * sets {@code IsActive} of every imported workflow; {@code null}: the archive's value.
     */
    public synchronized String importArchive(byte[] zip, Boolean active) {
        importFlags.add(active);
        String protocol = importArchive(zip);
        if (active != null && ArtifactFixtures.entries(zip).containsKey(WORKFLOWS)) {
            java.util.Set<String> names = new java.util.HashSet<>();
            java.util.regex.Matcher name = java.util.regex.Pattern.compile(
                "<WorkflowName>([^<]*)</WorkflowName>").matcher(new String(
                ArtifactFixtures.entries(zip).get(WORKFLOWS), StandardCharsets.UTF_8));
            while (name.find()) {
                names.add(name.group(1));
            }
            Element root = root(WORKFLOWS);
            Element list = root.elements().get(0);
            List<Node> groups = new ArrayList<>();
            for (Node node : list.children()) {
                if (node instanceof Element group) {
                    groups.add(group.withChildren(group.children().stream().map(child ->
                        child instanceof Element workflow && workflow.child("WorkflowName")
                            .map(Element::text).filter(names::contains).isPresent()
                            ? withValue(workflow, "IsActive", active.toString()) : child)
                        .toList()));
                } else {
                    groups.add(node);
                }
            }
            entries.put(WORKFLOWS, XmlNormalizer.normalize(root.withChildren(List.of(
                list.withChildren(groups)))));
        }
        return protocol;
    }

    /** The group export's entries, with a {@code Repository.zip} of the referenced files. */
    private Map<String, byte[]> groupEntries() {
        Map<String, byte[]> export = new LinkedHashMap<>(entries);
        if (!repository.isEmpty()) {
            Map<String, RepositoryVersion> files = new java.util.TreeMap<>();
            for (String path : references()) {
                head(path).ifPresent(head -> files.put(path, head));
            }
            export.put("Repository.zip", repositoryZip(null, files, null));
        }
        return export;
    }

    /** The repository paths the module files reference. */
    private java.util.SortedSet<String> references() {
        java.util.SortedSet<String> paths = new java.util.TreeSet<>();
        java.util.regex.Pattern reference = java.util.regex.Pattern.compile(
            "(?:href|schemaLocation)=\"inubitrepository:(/Root/[^\"]+)\"");
        entries.forEach((name, bytes) -> {
            if (name.startsWith("module/") && !name.equals(INDEX)) {
                java.util.regex.Matcher matcher = reference.matcher(new String(bytes,
                    StandardCharsets.UTF_8));
                while (matcher.find()) {
                    paths.add(matcher.group(1));
                }
            }
        });
        return paths;
    }

    private java.util.Optional<RepositoryVersion> head(String path) {
        List<RepositoryVersion> versions = repository.get(path);
        return versions == null ? java.util.Optional.empty()
            : java.util.Optional.of(versions.get(versions.size() - 1));
    }

    /** Stores a new version; {@code comment} {@code null}: an import (D-1 comment growth). */
    private void store(String path, byte[] content, String description, String comment) {
        List<RepositoryVersion> versions = repository.computeIfAbsent(path,
            p -> new ArrayList<>());
        RepositoryVersion head = versions.isEmpty() ? null : versions.get(versions.size() - 1);
        String version = head == null ? "1.0" : "1." + (versions.size());
        String uuid = head == null ? java.util.UUID.nameUUIDFromBytes(path.getBytes(
            StandardCharsets.UTF_8)).toString() : head.uuid();
        String stored = comment != null ? comment : "DefaultCommitCommentImport@@@"
            + (head == null ? "null" : head.comment());
        versions.add(new RepositoryVersion(version, uuid, stored, description,
            "2026-10-07T10:00:" + String.format("%02d", versions.size()), content.clone(),
            java.util.Set.of()));
    }

    /**
     * A repository archive in export shape: {@code directory} (an entry name ending in
     * {@code /}, or {@code null} for none), then per file metadata and content; {@code tag}
     * adds {@code tagName}.
     */
    private static byte[] repositoryZip(String directory, Map<String, RepositoryVersion> files,
        String tag) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(bytes)) {
            if (directory != null) {
                out.putNextEntry(new java.util.zip.ZipEntry(directory));
                out.closeEntry();
            }
            for (Map.Entry<String, RepositoryVersion> file : files.entrySet()) {
                String entry = file.getKey().substring(1);
                out.putNextEntry(new java.util.zip.ZipEntry(entry + ".xml"));
                out.write(metadata(file.getKey(), file.getValue(), tag));
                out.closeEntry();
                out.putNextEntry(new java.util.zip.ZipEntry(entry + ".dat"));
                out.write(file.getValue().content());
                out.closeEntry();
            }
            out.setComment("5.3");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static byte[] metadata(String path, RepositoryVersion version, String tag) {
        String md5;
        try {
            md5 = String.format("%032x", new java.math.BigInteger(1,
                java.security.MessageDigest.getInstance("MD5").digest(version.content())));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Property name=\""
            + path.substring(path.lastIndexOf('/') + 1) + "\" type=\"RepositoryFile\""
            + " anonymousRead=\"true\" versionComment=\"" + version.comment() + "\""
            + " writeable=\"true\" uuid=\"" + version.uuid() + "\" path=\"" + path + "\""
            + " modificator=\"jdoe\" contentSize=\"" + version.content().length + "\""
            + " modified=\"" + version.modified() + "\" contentEncoding=\"UTF-8\""
            + " contentType=\"application/octet-stream\" systemElement=\"false\""
            + " contentMD5=\"" + md5 + "\"" + (tag != null && version.tags().contains(tag)
                ? " tagName=\"" + tag + "\"" : "") + " version=\"" + version.version()
            + "\"><Description>" + version.description() + "</Description></Property>")
            .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The tagged form of the {@code element} entries of an export: {@code version="<n>"} from
     * the check-in comment instead of {@code head}, and {@code @@@Tag: <tag>@@@} in the export
     * suffix.
     */
    private static String tagged(String xml, String element, String tag) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?s)(<" + element
            + " [^>]*?)version=\"head\"([^>]*>)(.*?</" + element + ">)").matcher(xml);
        String versioned = matcher.replaceAll(match -> {
            java.util.regex.Matcher version = java.util.regex.Pattern.compile(
                "@@@Version: (\\d+)@@@").matcher(match.group(3));
            String n = version.find() ? version.group(1) : "1";
            return java.util.regex.Matcher.quoteReplacement(match.group(1) + "version=\"" + n
                + "\"" + match.group(2) + match.group(3));
        });
        return versioned.replace("@@@Export/Deployment: ", "@@@Tag: " + tag
            + "@@@Export/Deployment: ");
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

    /** The text of the current workflow file of the diagram group. */
    public synchronized String workflowXml() {
        return new String(entries.get(WORKFLOWS), StandardCharsets.UTF_8);
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
