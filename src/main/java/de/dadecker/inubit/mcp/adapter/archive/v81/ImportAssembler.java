package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.EmbeddedDocuments.Document;
import de.dadecker.inubit.mcp.adapter.archive.v81.EmbeddedDocuments.Encoding;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleIndexEntry;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowGroupXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Attribute;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Text;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.SecretPlaceholder;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the archive a StartCLI import receives (feature 004, research D-6, D-7, D-11, D-24,
 * D-25) from workspace files and their {@code .meta/} records — the workspace on disk, or the
 * rendering of a backup for a rollback.
 *
 * <ul>
 *   <li>Only the requested artifacts: a workflow archive ({@code archive.properties},
 *       {@code workflow/workflow.xml} with the requested workflows of one diagram group,
 *       {@code module/module.xml} with only the requested modules, their module files) without
 *       {@code Repository.zip}, as probed (D-24); a module archive ({@code archive.properties},
 *       index, module files and an empty {@code Repository.zip}, the probed module-only shape).
 *   <li>Every {@code ${secret:<path>}} becomes the target's value at that position
 *       ({@link SecretValues}, same walk as the redaction); a placeholder without a value is
 *       {@code SECRET_UNRESOLVED} naming artifact and path, never a value.
 *   <li>{@code CheckinComment} of every workflow and index entry is the probed export shape
 *       {@code DefaultCommitCommentImport###<reason>###@@@Deploying User: <user>@@@Server:
 *       <host>@@@Version: <current + 1>@@@Export/Deployment: <time>@@@} (D-11);
 *       {@code CheckoutUser} is removed.
 *   <li>A modified artifact gets its UIDs, its module file name and the context of its diagram
 *       group from the target's fresh export ({@link Target}), never from the editable
 *       {@code .meta/} records (review I1); a new one has no UID (INUBIT assigns them), the
 *       diagram group of the scope and the target's (or the default) context. A new workflow
 *       must say {@code IsActive} {@code false} in its file ({@code INVALID_INPUT} otherwise,
 *       review I2): INUBIT creates it inactive, and {@code set_active} switches it on. Every workflow's {@code UserOrUserGroupName} is the
 *       owner of the request; a file that names another owner is {@code INVALID_INPUT}.
 *   <li>A created artifact whose name the target uses for another kind or owner is
 *       {@code PRECONDITION_FAILED} (D-25).
 *   <li>The archive is read back and must hold exactly the requested artifacts (D-7);
 *       otherwise {@code INTERNAL}.
 * </ul>
 *
 * <p>The archive holds secret values: callers keep it in memory and in the private temporary
 * directory of the import only.
 */
public final class ImportAssembler {

    private static final String PROPERTIES = "archive.properties";
    private static final String WORKFLOWS = "workflow/workflow.xml";
    private static final String MODULE_INDEX = "module/module.xml";
    private static final String REPOSITORY = "Repository.zip";
    private static final String DEFAULT_DOCUMENT_VERSION = "5.3";
    private static final Pattern VERSION = Pattern.compile("@@@Version: (\\d+)@@@");
    private static final Pattern REASON = Pattern.compile("^[^#@\\p{Cntrl}]{1,500}$");
    private static final Pattern FIELD = Pattern.compile("^[^#@\\p{Cntrl}]{1,200}$");
    private static final Pattern ENTRY_NAME = Pattern.compile("^module/[^/]+\\.xml$");
    private static final String OWNER = "UserOrUserGroupName";

    /**
     * One artifact to import.
     *
     * @param pluginType the plugin type of a module (empty for a workflow)
     * @param created    true if the import creates it (no UIDs, default context)
     */
    public record Artifact(String name, Optional<String> pluginType, boolean created) {
        public Artifact {
            Objects.requireNonNull(name, "name");
            pluginType = pluginType == null ? Optional.empty() : pluginType;
        }
    }

    /**
     * The check-in comment of the new versions (research D-11): {@code reason} is the only
     * person-written segment; it and the other fields must not contain {@code #}, {@code @} or
     * control characters, which would break the segment structure.
     *
     * @param user   the INUBIT user the server imports as
     * @param server the INUBIT host
     * @param time   {@code dd.MM.yyyy HH:mm:ss}
     */
    public record CheckinComment(String reason, String user, String server, String time) {
        public CheckinComment {
            if (reason == null || reason.isBlank() || !REASON.matcher(reason).matches()) {
                throw new IllegalArgumentException("A reason is 1-500 characters without #, @"
                    + " and control characters");
            }
            for (String field : List.of(user, server, time)) {
                if (field == null || !FIELD.matcher(field).matches()) {
                    throw new IllegalArgumentException("Invalid check-in comment field");
                }
            }
        }

        /** The comment of a new version {@code version}. */
        String render(int version) {
            return "DefaultCommitCommentImport###" + reason + "###@@@Deploying User: " + user
                + "@@@Server: " + server + "@@@Version: " + version + "@@@Export/Deployment: "
                + time + "@@@";
        }
    }

    /**
     * What to build.
     *
     * @param diagramGroup    the diagram group of a workflow archive; empty for a module
     *                        archive
     * @param currentVersions the current version per artifact name on the target
     *                        ({@link #currentVersions}); a missing name counts as 0
     * @param takenNames      names that exist on the target as another kind of artifact or
     *                        for another owner; a created artifact must not use one (D-25)
     */
    public record Request(GroupId group, String owner, Optional<String> diagramGroup,
        List<Artifact> workflows, List<Artifact> modules, CheckinComment comment,
        Map<String, Integer> currentVersions, Set<String> takenNames) {
        public Request {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(owner, "owner");
            diagramGroup = diagramGroup == null ? Optional.empty() : diagramGroup;
            workflows = List.copyOf(workflows);
            modules = List.copyOf(modules);
            Objects.requireNonNull(comment, "comment");
            currentVersions = Map.copyOf(currentVersions);
            takenNames = Set.copyOf(takenNames);
            if (diagramGroup.isEmpty() && !workflows.isEmpty()) {
                throw new IllegalArgumentException("Workflows need their diagram group");
            }
            if (modules.stream().anyMatch(module -> module.pluginType().isEmpty())) {
                throw new IllegalArgumentException("A module needs its plugin type");
            }
        }
    }

    /** The archive (with secret values) and the names it holds. */
    public record Assembled(byte[] zip, List<String> workflows, List<String> modules) {
        public Assembled {
            zip = zip.clone();
            workflows = List.copyOf(workflows);
            modules = List.copyOf(modules);
        }

        @Override
        public byte[] zip() {
            return zip.clone();
        }

        @Override
        public String toString() {
            return "Assembled[" + workflows.size() + " workflows, " + modules.size()
                + " modules]";
        }
    }

    private ImportAssembler() {
    }

    /**
     * The current version of every workflow and module of raw exports of the target, from the
     * export suffix of their check-in comments ({@code @@@Version: <n>@@@}).
     */
    public static Map<String, Integer> currentVersions(List<byte[]> rawExports) {
        Map<String, Integer> versions = new HashMap<>();
        ArchiveReader reader = new ArchiveReader();
        for (byte[] zip : rawExports) {
            var archive = reader.read(zip);
            for (WorkflowGroupXml group : archive.workflowGroups()) {
                for (WorkflowXml workflow : group.workflows()) {
                    version(workflow.element()).ifPresent(v -> versions.merge(workflow.name(),
                        v, Math::max));
                }
            }
            for (ModuleIndexEntry entry : archive.moduleIndex()) {
                version(entry.element()).ifPresent(v -> versions.merge(entry.name(), v,
                    Math::max));
            }
        }
        return versions;
    }

    /** The secret values of raw exports of the target (in memory only). */
    public static SecretValues secretValues(byte[] rawExport) {
        return SecretValues.of(new ArchiveReader().read(rawExport));
    }

    /**
     * What the target holds now: its raw exports of the scope (in memory only). The identity of
     * a modified artifact — its UIDs, its module file name and the context of its diagram group
     * — and the secret values come from here, never from the workspace's {@code .meta/} records,
     * which anyone can edit (review I1).
     */
    public static final class Target {

        private final List<ExportArchive> exports;
        private final SecretValues secrets;

        private Target(List<ExportArchive> exports) {
            this.exports = List.copyOf(exports);
            this.secrets = SecretValues.of(this.exports);
        }

        /** The target of the raw exports {@code exports} (read by {@link ArchiveReader}). */
        public static Target of(List<ExportArchive> exports) {
            return new Target(exports);
        }

        SecretValues secrets() {
            return secrets;
        }

        // loops, not lambdas: no method of this package outside the gate takes an ExportArchive
        // (RedactionGateTest)
        Optional<WorkflowGroupXml> diagramGroup(String name) {
            for (ExportArchive export : exports) {
                for (WorkflowGroupXml group : export.workflowGroups()) {
                    if (group.name().equals(name)) {
                        return Optional.of(group);
                    }
                }
            }
            return Optional.empty();
        }

        Optional<WorkflowXml> workflow(String diagramGroup, String name) {
            return diagramGroup(diagramGroup).flatMap(group -> group.workflows().stream()
                .filter(workflow -> workflow.name().equals(name)).findFirst());
        }

        Optional<ModuleIndexEntry> indexEntry(String name, String pluginType) {
            for (ExportArchive export : exports) {
                for (ModuleIndexEntry entry : export.moduleIndex()) {
                    if (entry.name().equals(name) && entry.pluginType().equals(pluginType)) {
                        return Optional.of(entry);
                    }
                }
            }
            return Optional.empty();
        }

        Optional<String> entryName(String name) {
            for (ExportArchive export : exports) {
                ExportArchive.ModuleXml module = export.moduleFiles().get(name);
                if (module != null) {
                    return Optional.of(module.entryName());
                }
            }
            return Optional.empty();
        }

        @Override
        public String toString() {
            return "Target[" + exports.size() + " exports]";
        }
    }

    /**
     * The import archive of {@code request}.
     *
     * @param files  workspace-relative path → content: the artifact files and their
     *               {@code .meta/} records (more files do no harm)
     * @param target the target's raw exports of the scope
     * @throws ToolErrorException {@code PRECONDITION_FAILED} if a requested artifact is not in
     *     {@code files} or a modified one is not on the target, {@code INVALID_INPUT} for a
     *     workflow file that names another owner, {@code SECRET_UNRESOLVED} for a placeholder
     *     without a value on the target, {@code INTERNAL} if the archive does not hold exactly
     *     the request
     */
    public static Assembled assemble(SortedMap<String, byte[]> files, Request request,
        Target target) {
        Objects.requireNonNull(files, "files");
        Objects.requireNonNull(target, "target");
        SecretValues secrets = target.secrets();
        List<String> collisions = new ArrayList<>();
        request.workflows().stream().filter(Artifact::created).map(Artifact::name)
            .filter(request.takenNames()::contains).forEach(collisions::add);
        request.modules().stream().filter(Artifact::created).map(Artifact::name)
            .filter(request.takenNames()::contains).forEach(collisions::add);
        if (!collisions.isEmpty()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The new artifact name(s) " + String.join(", ", collisions) + " exist on the"
                    + " target as another kind of artifact or for another owner; nothing was"
                    + " sent",
                "INUBIT identifies artifacts by name; the import would not create a new one",
                "Choose another name for the new artifact"));
        }
        List<String> absent = new ArrayList<>();
        request.workflows().stream().filter(artifact -> !artifact.created())
            .filter(artifact -> target.workflow(request.diagramGroup().orElseThrow(),
                artifact.name()).isEmpty())
            .forEach(artifact -> absent.add(artifact.name()));
        request.modules().stream().filter(artifact -> !artifact.created())
            .filter(artifact -> target.indexEntry(artifact.name(), artifact.pluginType()
                .orElseThrow()).isEmpty())
            .forEach(artifact -> absent.add(artifact.name()));
        if (!absent.isEmpty()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The modified artifact(s) " + String.join(", ", absent) + " are not on the"
                    + " target; nothing was sent",
                "They were removed or renamed on the server since the export",
                "Export the scope again (export_artifacts) and redo the change"));
        }
        List<String> unresolved = new ArrayList<>();
        List<String> foreign = new ArrayList<>();
        List<String> active = new ArrayList<>();
        List<Element> workflows = new ArrayList<>();
        // the context of the diagram group on the target (review I1), else the default
        Optional<WorkflowGroupXml> targetGroup = request.diagramGroup()
            .flatMap(target::diagramGroup);
        List<Attribute> groupAttributes = targetGroup.map(WorkflowGroupXml::attributes)
            .filter(attributes -> !attributes.isEmpty())
            .orElse(List.of(new Attribute("", "workflowType", "", "technical")));
        String documentVersion = targetGroup.flatMap(group -> group.workflows().stream()
            .findFirst()).map(workflow -> workflow.context().documentVersion())
            .orElse(DEFAULT_DOCUMENT_VERSION);
        for (Artifact artifact : request.workflows()) {
            WorkspacePath path = WorkspacePath.workflow(request.group(), request.owner(),
                request.diagramGroup().orElseThrow(), artifact.name());
            Element element = parse(files, path, artifact.name());
            Optional<String> named = element.child(OWNER).map(Element::text);
            if (named.filter(owner -> !owner.equals(request.owner())).isPresent()) {
                foreign.add(artifact.name() + " (" + named.get() + ")");
            }
            element = withOwner(element, request.owner());
            element = artifact.created() ? without(element, "WorkflowUId")
                : withUid(element, "WorkflowUId", target.workflow(request.diagramGroup()
                    .orElseThrow(), artifact.name()).orElseThrow().element());
            element = without(element, "CheckoutUser");
            if (artifact.created() && element.child("IsActive").map(Element::text)
                .filter("false"::equals).isEmpty()) {
                // research D-25 (H9), review I2: INUBIT creates a new workflow inactive
                active.add(artifact.name());
            }
            element = withChild(element, "CheckinComment", request.comment().render(
                request.currentVersions().getOrDefault(artifact.name(), 0) + 1));
            workflows.add(secrets(element, artifact.name(), (name, secret) ->
                secrets.workflow(name, secret), unresolved));
        }
        if (!foreign.isEmpty()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "The workflow file(s) of " + String.join(", ", foreign) + " name another owner"
                    + " than " + request.owner() + " in UserOrUserGroupName; nothing was sent",
                "The file was copied from another owner's diagram group or edited; an import"
                    + " addresses one owner only",
                "Set UserOrUserGroupName to " + request.owner() + " (or remove it) in the"
                    + " workspace file, or import into the other owner's scope"));
        }
        if (!active.isEmpty()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "The new workflow(s) " + String.join(", ", active) + " must be inactive in the"
                    + " workspace file (<IsActive>false</IsActive>); nothing was sent",
                "INUBIT creates a new workflow inactive, so the verification after the import"
                    + " would fail for a file that says otherwise",
                "Set IsActive to false in the file, import, then switch the workflow on with"
                    + " set_active"));
        }

        Map<String, List<Element>> index = new LinkedHashMap<>();
        Map<String, Element> moduleFiles = new LinkedHashMap<>();
        Map<String, String> entryNames = new LinkedHashMap<>();
        for (Artifact artifact : request.modules()) {
            String pluginType = artifact.pluginType().orElseThrow();
            WorkspacePath indexPath = WorkspacePath.moduleIndex(request.group(),
                request.owner(), pluginType, artifact.name());
            WorkspacePath modulePath = WorkspacePath.module(request.group(), request.owner(),
                pluginType, artifact.name());
            Element entry = parse(files, indexPath, artifact.name());
            entry = artifact.created() ? without(entry, "ModuleUId")
                : withUid(entry, "ModuleUId", target.indexEntry(artifact.name(), pluginType)
                    .orElseThrow().element());
            entry = withChild(entry, "CheckinComment", request.comment().render(
                request.currentVersions().getOrDefault(artifact.name(), 0) + 1));
            index.computeIfAbsent(pluginType, type -> new ArrayList<>()).add(entry);
            Map<String, Object> moduleMeta = meta(files, modulePath);
            Element properties = secrets(parse(files, modulePath, artifact.name()),
                artifact.name(), (name, secret) -> secrets.module(name, secret), unresolved);
            moduleFiles.put(artifact.name(), embed(files, modulePath, properties, moduleMeta,
                artifact.name()));
            // review I1: the module file name of the target, and only a plain module/<x>.xml
            String defaultEntry = "module/" + artifact.name().toLowerCase(Locale.ROOT) + ".xml";
            entryNames.put(artifact.name(), artifact.created() ? defaultEntry
                : target.entryName(artifact.name()).filter(name -> ENTRY_NAME.matcher(name)
                    .matches()).orElse(defaultEntry));
        }
        if (!unresolved.isEmpty()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.SECRET_UNRESOLVED,
                "The target has no value for " + unresolved.size() + " secret placeholder(s): "
                    + String.join(", ", unresolved.size() <= 10 ? unresolved
                        : unresolved.subList(0, 10)) + "; nothing was sent",
                "A placeholder stands for a secret of the target at the same artifact and"
                    + " property path; a new artifact or a new property has none there",
                "Set the secret in the Workbench first, or remove the placeholder from the"
                    + " workspace file"));
        }

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(PROPERTIES, properties(files, request));
        if (request.diagramGroup().isPresent()) {
            entries.put(WORKFLOWS, XmlNormalizer.normalize(workflowDocument(
                request.diagramGroup().get(), groupAttributes, documentVersion, workflows)));
        }
        entries.put(MODULE_INDEX, XmlNormalizer.normalize(indexDocument(index)));
        moduleFiles.forEach((name, properties) -> entries.put(entryNames.get(name),
            XmlNormalizer.normalize(properties)));
        if (request.diagramGroup().isEmpty()) {
            entries.put(REPOSITORY, zip(Map.of()));
        }
        byte[] zip = zip(entries);
        List<String> workflowNames = request.workflows().stream().map(Artifact::name).toList();
        List<String> moduleNames = request.modules().stream().map(Artifact::name).toList();
        guard(zip, workflowNames, moduleNames);
        return new Assembled(zip, workflowNames, moduleNames);
    }

    /** Research D-7: the archive holds exactly the request and no repository file. */
    private static void guard(byte[] zip, List<String> workflows, List<String> modules) {
        var back = new ArchiveReader().read(zip);
        List<String> readWorkflows = back.workflowGroups().stream()
            .flatMap(group -> group.workflows().stream()).map(WorkflowXml::name).toList();
        Set<String> readIndex = new LinkedHashSet<>(back.moduleIndex().stream()
            .map(ModuleIndexEntry::name).toList());
        boolean exact = readWorkflows.equals(workflows)
            && readIndex.equals(new LinkedHashSet<>(modules))
            && back.moduleFiles().keySet().equals(new LinkedHashSet<>(modules))
            && back.repository().isEmpty();
        if (!exact) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "The import archive does not hold exactly the change set; nothing was sent",
                "An internal error of the INUBIT MCP server",
                "Report the problem with the MCP server log"));
        }
    }

    // --- elements --------------------------------------------------------------------------

    /** Replaces every placeholder below {@code element} by the target's value. */
    private static Element secrets(Element element, String artifact,
        BiFunction<String, String, Optional<String>> lookup, List<String> unresolved) {
        if (!element.hasElements()) {
            Optional<SecretPlaceholder> placeholder = SecretPlaceholder.parse(element.text());
            if (placeholder.isEmpty()) {
                return element;
            }
            String path = placeholder.get().propertyPath();
            Optional<String> value = lookup.apply(artifact, path);
            if (value.isEmpty()) {
                unresolved.add(artifact + ": " + path);
                return element;
            }
            return element.withText(value.get());
        }
        List<Node> children = new ArrayList<>();
        for (Node child : element.children()) {
            children.add(child instanceof Element e ? secrets(e, artifact, lookup, unresolved)
                : child);
        }
        return element.withChildren(children);
    }

    /** Re-embeds the documents a module file refers to. */
    private static Element embed(SortedMap<String, byte[]> files, WorkspacePath modulePath,
        Element properties, Map<String, Object> meta, String name) {
        Path directory = modulePath.toRelativePath().getParent();
        Map<String, Object> recorded = meta.get("embedded") instanceof Map<?, ?> map
            ? cast(map) : Map.of();
        List<Document> documents = new ArrayList<>();
        for (Element property : properties.elements()) {
            String text = property.text();
            if (!property.localName().equals("Property") || property.hasElements()
                || !text.startsWith(EmbeddedDocuments.FILE_REFERENCE)) {
                continue;
            }
            String fileName = text.substring(EmbeddedDocuments.FILE_REFERENCE.length());
            byte[] content = files.get(slash(directory.resolve(fileName)));
            if (content == null) {
                throw missing("The embedded document " + fileName + " of module " + name);
            }
            Encoding encoding;
            if (recorded.get(fileName) instanceof Map<?, ?> entry) {
                encoding = Encoding.valueOf(String.valueOf(entry.get("encoding")));
            } else {
                encoding = property.attribute("type").filter("InternalDocument"::equals)
                    .isPresent() ? Encoding.GZIP_BASE64 : Encoding.ESCAPED_XML;
            }
            documents.add(new Document(property.attribute("name").orElse(""), fileName,
                encoding, content));
        }
        return EmbeddedDocuments.embed(properties, documents);
    }

    private static Element workflowDocument(String diagramGroup, List<Attribute> attributes,
        String version, List<Element> workflows) {
        List<Node> children = new ArrayList<>();
        children.add(leaf("WorkflowGroupName", diagramGroup));
        children.addAll(workflows);
        return element("IBISWorkflow", List.of(new Attribute("", "version", "", version)),
            List.of(element("Workflows", List.of(), List.of(element("WorkflowGroup", attributes,
                children)))));
    }

    private static Element indexDocument(Map<String, List<Element>> index) {
        List<Node> groups = new ArrayList<>();
        index.forEach((pluginType, entries) -> {
            List<Node> children = new ArrayList<>();
            children.add(leaf("ModuleGroupName", pluginType));
            children.addAll(entries);
            groups.add(element("ModuleGroup", List.of(), children));
        });
        return element("IBISWorkflow", List.of(new Attribute("", "version", "",
            DEFAULT_DOCUMENT_VERSION)), List.of(element("Modules", List.of(), groups)));
    }

    /** {@code UserOrUserGroupName} set to {@code owner}, added after the name if missing. */
    private static Element withOwner(Element element, String owner) {
        if (element.child(OWNER).isPresent()) {
            return withChild(element, OWNER, owner);
        }
        List<Node> children = new ArrayList<>();
        boolean placed = false;
        for (Node child : element.children()) {
            children.add(child);
            if (!placed && child instanceof Element e && e.localName().equals("WorkflowName")) {
                children.add(leaf(OWNER, owner));
                placed = true;
            }
        }
        if (!placed) {
            children.add(0, leaf(OWNER, owner));
        }
        return element.withChildren(children);
    }

    /** The UID {@code name} of {@code current} (the target's element), or none. */
    private static Element withUid(Element element, String name, Element current) {
        Optional<String> uid = current.child(name).map(Element::text).filter(t -> !t.isEmpty());
        return uid.map(text -> withChild(element, name, text))
            .orElseGet(() -> without(element, name));
    }

    private static Element without(Element element, String name) {
        return element.withChildren(element.children().stream()
            .filter(child -> !(child instanceof Element e && e.localName().equals(name)))
            .toList());
    }

    private static Element append(Element element, Element child) {
        List<Node> children = new ArrayList<>(element.children());
        children.add(child);
        return element.withChildren(children);
    }

    /** {@code element} with the text of its child {@code name} set (added if missing). */
    private static Element withChild(Element element, String name, String text) {
        if (element.child(name).isEmpty()) {
            return append(element, leaf(name, text));
        }
        return element.withChildren(element.children().stream()
            .map(child -> child instanceof Element e && e.localName().equals(name)
                ? e.withText(text) : child).toList());
    }

    private static Element leaf(String name, String text) {
        return element(name, List.of(), List.of(new Text(text)));
    }

    private static Element element(String name, List<Attribute> attributes,
        List<? extends Node> children) {
        return new Element("", name, "", List.of(), attributes, List.copyOf(children));
    }

    private static Optional<Integer> version(Element element) {
        return element.child("CheckinComment").map(Element::text).map(VERSION::matcher)
            .filter(Matcher::find).map(matcher -> Integer.parseInt(matcher.group(1)));
    }

    // --- files -------------------------------------------------------------------------------

    private static Element parse(SortedMap<String, byte[]> files, WorkspacePath path,
        String name) {
        byte[] content = files.get(slash(path.toRelativePath()));
        if (content == null) {
            throw missing("The file " + slash(path.toRelativePath()) + " of " + name);
        }
        return XmlTree.parse(content).root();
    }

    private static Map<String, Object> meta(SortedMap<String, byte[]> files,
        WorkspacePath path) {
        byte[] record = files.get(slash(path.metaPath()));
        return record == null ? Map.of() : MetaStore.deserialize(record);
    }

    /** {@code archive.properties} with the recorded {@code sourceVersion}, a new operation id. */
    private static byte[] properties(SortedMap<String, byte[]> files, Request request) {
        String sourceVersion = "";
        String records = WorkspaceWriter.exportRecord(request.group(), request.owner(),
            "x", "x");
        String prefix = records.substring(0, records.lastIndexOf("/x/"));
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            if (file.getKey().startsWith(prefix + "/")) {
                Object version = MetaStore.deserialize(file.getValue()).get("sourceVersion");
                if (version != null && !String.valueOf(version).isBlank()) {
                    sourceVersion = String.valueOf(version);
                    break;
                }
            }
        }
        Map<String, String> properties = new TreeMap<>();
        properties.put("sourceVersion", sourceVersion);
        properties.put("operationId", UUID.randomUUID().toString());
        StringBuilder text = new StringBuilder("#\n");
        properties.forEach((key, value) -> text.append(key).append('=').append(value)
            .append('\n'));
        return text.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static List<Attribute> attributes(Object value) {
        List<Attribute> attributes = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> attribute) {
                    String qualified = String.valueOf(attribute.get("name"));
                    int colon = qualified.indexOf(':');
                    Object namespace = attribute.get("namespace");
                    attributes.add(new Attribute(colon < 0 ? "" : qualified.substring(0, colon),
                        colon < 0 ? qualified : qualified.substring(colon + 1),
                        namespace == null ? "" : String.valueOf(namespace),
                        String.valueOf(attribute.get("value"))));
                }
            }
        }
        return attributes;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static ToolErrorException missing(String what) {
        return new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
            what + " is missing; nothing was sent",
            "The workspace (or the backup) does not hold the complete artifact",
            "Export the scope again, or restore the file"));
    }
}
