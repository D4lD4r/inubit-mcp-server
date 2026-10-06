package de.dadecker.inubit.mcp.adapter.archive.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.EmbeddedDocuments.Document;
import de.dadecker.inubit.mcp.adapter.archive.v81.EmbeddedDocuments.Extracted;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleIndexEntry;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.RepositoryFile;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowGroupXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.MetaStore.Split;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Attribute;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NameCodec;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Turns a {@link RedactedArchive} into workspace files below {@code <group>/<owner>/} and writes
 * them (research D-2 – D-6, FR-012 – FR-017). Only a redacted archive is accepted (T014).
 *
 * <ul>
 *   <li>One file per workflow ({@code workflows/<diagram group>/<workflow>.xml}, root
 *       {@code Workflow}); per module {@code module.xml} (embedded documents as
 *       {@code @file:} references, {@link EmbeddedDocuments}), {@code index.xml} (the index
 *       entry; a module listed twice is written once) and one file per embedded document; the
 *       repository files referenced by an exported artifact ({@code inubitrepository:} URIs or
 *       their {@code /Root/…} path in any text), and only those.
 *   <li>Every XML file goes through {@link XmlNormalizer}; embedded documents are written as
 *       decoded. Volatile values go to {@code .meta/} ({@link MetaStore}): UIDs, the export suffix
 *       of check-in comments, the enclosing XML context, module file names and encodings,
 *       repository metadata, and per export the stable archive properties and entry order.
 *   <li>Two target paths that differ only in upper/lower case are refused with
 *       {@code INVALID_INPUT} naming both. Rendering the same archive twice yields identical
 *       bytes.
 *   <li>{@link #write} replaces the affected sub-trees — the exported diagram groups and modules
 *       with their {@code .meta/} mirrors — so that artifacts no longer exported disappear
 *       (FR-017); repository files are overwritten, not deleted.
 * </ul>
 */
public final class WorkspaceWriter {

    static final String EXPORTS_DIRECTORY = "exports";

    /**
     * The files of one export, by workspace-relative path, and what they replace. Only
     * {@link #render} (from a {@link RedactedArchive}) and {@link #merge} create one, so that
     * {@link #write} never receives unredacted bytes (review I2).
     */
    public static final class Rendered {

        private final SortedMap<String, byte[]> files;
        private final List<String> subtrees;
        private final List<String> warnings;

        private Rendered(SortedMap<String, byte[]> files, List<String> subtrees,
            List<String> warnings) {
            this.files = Collections.unmodifiableSortedMap(new TreeMap<>(files));
            this.subtrees = List.copyOf(subtrees);
            this.warnings = List.copyOf(warnings);
        }

        /** The files by workspace-relative path (unmodifiable). */
        public SortedMap<String, byte[]> files() {
            return files;
        }

        /** The sub-trees the files replace. */
        public List<String> subtrees() {
            return subtrees;
        }

        /** Warnings for the caller. */
        public List<String> warnings() {
            return warnings;
        }
    }

    /**
     * The renderings of one request as one: later files win (a module used by two exported
     * diagram groups is one set of files); sub-trees and warnings without duplicates.
     *
     * @throws ToolErrorException {@code INVALID_INPUT} for a case-only collision of two paths
     */
    public static Rendered merge(List<Rendered> renderings) {
        SortedMap<String, byte[]> files = new TreeMap<>();
        Set<String> subtrees = new LinkedHashSet<>();
        Set<String> warnings = new LinkedHashSet<>();
        for (Rendered rendered : renderings) {
            files.putAll(rendered.files());
            subtrees.addAll(rendered.subtrees());
            warnings.addAll(rendered.warnings());
        }
        checkCaseCollisions(files.keySet());
        return new Rendered(files, List.copyOf(subtrees), List.copyOf(warnings));
    }

    private WorkspaceWriter() {
    }

    /**
     * The workspace files of {@code archive} for {@code owner} on {@code group}.
     *
     * @throws ToolErrorException {@code INVALID_INPUT} for a case-only collision of two paths
     */
    public static Rendered render(RedactedArchive archive, GroupId group, String owner) {
        ExportArchive export = archive.archive();
        SortedMap<String, byte[]> files = new TreeMap<>();
        Set<String> subtrees = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        Set<String> usedModules = new HashSet<>();

        for (WorkflowGroupXml diagramGroup : export.workflowGroups()) {
            WorkspacePath any = WorkspacePath.workflow(group, owner, diagramGroup.name(), "x");
            subtrees.add(slash(any.toRelativePath().getParent()));
            subtrees.add(slash(any.metaPath().getParent()));
            for (WorkflowXml workflow : diagramGroup.workflows()) {
                WorkspacePath path = WorkspacePath.workflow(group, owner, workflow.diagramGroup(),
                    workflow.name());
                Split split = MetaStore.split(workflow.element());
                byte[] xml = XmlNormalizer.normalize(split.element());
                files.put(slash(path.toRelativePath()), xml);
                Map<String, Object> meta = new LinkedHashMap<>(split.values());
                meta.put("context", Map.of(
                    "documentVersion", workflow.context().documentVersion(),
                    "groupAttributes", attributes(workflow.context().groupAttributes()),
                    "groupPosition", workflow.context().groupPosition(),
                    "position", workflow.context().position()));
                files.put(slash(path.metaPath()), MetaStore.serialize(meta));
                texts.add(new String(xml, StandardCharsets.UTF_8));
                workflow.element().child("CheckoutUser").map(Element::text)
                    .filter(user -> !user.isBlank())
                    .ifPresent(user -> warnings.add("Workflow " + workflow.name()
                        + " is in edit mode by " + user + " (CheckoutUser)"));
                usedModules.addAll(moduleNames(workflow.element()));
            }
        }

        Map<String, List<ModuleIndexEntry>> index = new LinkedHashMap<>();
        export.moduleIndex().forEach(entry -> index.computeIfAbsent(entry.name(),
            name -> new ArrayList<>()).add(entry));
        for (List<ModuleIndexEntry> entries : index.values()) {
            ModuleIndexEntry entry = entries.get(0);
            WorkspacePath indexPath = WorkspacePath.moduleIndex(group, owner, entry.pluginType(),
                entry.name());
            subtrees.add(slash(indexPath.toRelativePath().getParent()));
            subtrees.add(slash(indexPath.metaPath().getParent()));
            Split split = MetaStore.split(entry.element());
            files.put(slash(indexPath.toRelativePath()), XmlNormalizer.normalize(split.element()));
            Map<String, Object> meta = new LinkedHashMap<>(split.values());
            meta.put("context", Map.of("documentVersion", entry.documentVersion(),
                "positions", entries.stream().map(e -> Map.of("groupPosition",
                    e.groupPosition(), "position", e.position())).toList()));
            files.put(slash(indexPath.metaPath()), MetaStore.serialize(meta));
            ModuleXml module = export.moduleFiles().get(entry.name());
            if (module != null) {
                module(group, owner, module, files, texts);
            }
        }
        List<Map<String, String>> exportedModules = index.values().stream()
            .map(entries -> Map.of("name", entries.get(0).name(),
                "pluginType", entries.get(0).pluginType()))
            .toList();
        for (WorkflowGroupXml diagramGroup : export.workflowGroups()) {
            files.put(exportRecord(group, owner, "workflows", diagramGroup.name()),
                MetaStore.serialize(Map.of("kind", "workflows",
                    "diagramGroup", diagramGroup.name(),
                    "modules", exportedModules,
                    "sourceVersion", export.properties().getOrDefault("sourceVersion", ""),
                    "entries", export.entries())));
        }
        if (export.workflowGroups().isEmpty()) {
            for (ModuleIndexEntry entry : export.moduleIndex()) {
                files.put(exportRecord(group, owner, "modules", entry.pluginType() + "/"
                    + entry.name()), MetaStore.serialize(Map.of("kind", "modules",
                        "module", entry.name(), "pluginType", entry.pluginType(),
                        "sourceVersion", export.properties().getOrDefault("sourceVersion", ""),
                        "entries", export.entries())));
            }
        }
        long foreign = usedModules.stream().filter(name -> !index.containsKey(name)).count();
        if (foreign > 0) {
            warnings.add(foreign + " used module(s) are not part of the export (e.g. modules of"
                + " another owner) and were not written");
        }

        for (RepositoryFile file : export.repository().values()) {
            String reference = "/" + file.path();
            if (texts.stream().noneMatch(text -> text.contains(reference))) {
                continue;
            }
            WorkspacePath path = WorkspacePath.repository(group, owner, file.path());
            files.put(slash(path.toRelativePath()), file.content());
            Map<String, Object> meta = new LinkedHashMap<>();
            file.metadata().ifPresent(metadata -> meta.put("metadata",
                new String(XmlNormalizer.normalize(metadata), StandardCharsets.UTF_8)));
            files.put(slash(path.metaPath()), MetaStore.serialize(meta));
        }
        checkCaseCollisions(files.keySet());
        return new Rendered(files, List.copyOf(subtrees), warnings);
    }

    private static void module(GroupId group, String owner, ModuleXml module,
        SortedMap<String, byte[]> files, List<String> texts) {
        Extracted extracted = EmbeddedDocuments.extract(module.element());
        WorkspacePath path = WorkspacePath.module(group, owner, module.pluginType(),
            module.name());
        byte[] xml = XmlNormalizer.normalize(extracted.properties());
        files.put(slash(path.toRelativePath()), xml);
        texts.add(new String(xml, StandardCharsets.UTF_8));
        SortedMap<String, Object> embedded = new TreeMap<>();
        for (Document document : extracted.documents()) {
            int dot = document.fileName().lastIndexOf('.');
            WorkspacePath file = WorkspacePath.embedded(group, owner, module.pluginType(),
                module.name(), document.property(), document.fileName().substring(dot + 1));
            files.put(slash(file.toRelativePath()), document.content());
            texts.add(new String(document.content(), StandardCharsets.UTF_8));
            embedded.put(document.fileName(), Map.of("property", document.property(),
                "encoding", document.encoding().name()));
        }
        files.put(slash(path.metaPath()), MetaStore.serialize(Map.of(
            "entryName", module.entryName(), "embedded", embedded)));
    }

    /** {@code .meta/<group>/<owner>/exports/<kind>/<name>.json}. */
    static String exportRecord(GroupId group, String owner, String kind, String name) {
        StringBuilder path = new StringBuilder(WorkspacePath.META_DIRECTORY).append('/')
            .append(group.value()).append('/').append(NameCodec.encode(owner)).append('/')
            .append(EXPORTS_DIRECTORY).append('/').append(kind);
        for (String segment : name.split("/", -1)) {
            path.append('/').append(NameCodec.encode(segment));
        }
        return path.append(".json").toString();
    }

    private static List<Map<String, String>> attributes(List<Attribute> attributes) {
        return attributes.stream().map(a -> Map.of("name", a.qualifiedName(),
            "namespace", a.namespaceUri(), "value", a.value())).toList();
    }

    /** The module names of the technical nodes of a workflow. */
    private static Set<String> moduleNames(Element workflow) {
        Set<String> names = new HashSet<>();
        for (Element node : workflow.elements()) {
            if (node.localName().equals("WorkflowModule")
                && node.attribute("moduleType").filter("technical"::equals).isPresent()) {
                node.child("ModuleName").map(Element::text).ifPresent(names::add);
            }
        }
        return names;
    }

    private static void checkCaseCollisions(Set<String> paths) {
        Map<String, String> folded = new HashMap<>();
        for (String path : paths) {
            String previous = folded.putIfAbsent(path.toLowerCase(Locale.ROOT), path);
            if (previous != null) {
                throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                    "Two artifacts differ only in upper/lower case and would overwrite each other"
                        + " in the workspace: " + previous + " and " + path,
                    "INUBIT names are case-sensitive, many file systems are not",
                    "Rename one of the two artifacts in INUBIT, then export again"));
            }
        }
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }

    /**
     * Writes {@code rendered} below {@code root}: the affected sub-trees are replaced (files not
     * rendered again are deleted, empty directories removed), all rendered files written. A
     * module that a diagram group exported before but no longer does is removed, unless another
     * export record of the owner still names it (FR-017).
     */
    public static void write(Path root, Rendered rendered) {
        try {
            for (Path directory : orphanedModules(root, rendered)) {
                deleteTree(directory);
                for (Path parent = directory.getParent(); parent != null
                    && !parent.equals(root) && isEmptyDirectory(parent);
                    parent = parent.getParent()) {
                    Files.delete(parent);
                }
            }
            for (String subtree : rendered.subtrees()) {
                Path directory = root.resolve(subtree);
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                List<Path> stale;
                try (Stream<Path> walk = Files.walk(directory)) {
                    stale = walk.filter(Files::isRegularFile).filter(file -> !rendered.files()
                        .containsKey(slash(root.relativize(file)))).toList();
                }
                for (Path file : stale) {
                    Files.delete(file);
                }
                try (Stream<Path> walk = Files.walk(directory)) {
                    for (Path dir : walk.filter(Files::isDirectory)
                        .sorted(Comparator.reverseOrder()).toList()) {
                        try (Stream<Path> entries = Files.list(dir)) {
                            if (entries.findAny().isEmpty()) {
                                Files.delete(dir);
                            }
                        }
                    }
                }
            }
            for (Map.Entry<String, byte[]> file : rendered.files().entrySet()) {
                Path target = root.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                if (!Files.exists(target) || !Arrays.equals(Files.readAllBytes(target),
                    file.getValue())) {
                    Files.write(target, file.getValue());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The module directories (files and {@code .meta/} mirror) that the diagram groups of
     * {@code rendered} exported before but no longer do and that no other export record of the
     * owner names.
     */
    private static List<Path> orphanedModules(Path root, Rendered rendered) throws IOException {
        List<Path> orphans = new ArrayList<>();
        for (Map.Entry<String, byte[]> file : rendered.files().entrySet()) {
            String path = file.getKey();
            int exports = path.indexOf("/" + EXPORTS_DIRECTORY + "/workflows/");
            Path previous = root.resolve(path);
            if (!path.startsWith(WorkspacePath.META_DIRECTORY + "/") || exports < 0
                || !Files.isRegularFile(previous)) {
                continue;
            }
            Set<List<String>> dropped = exportedModules(Files.readAllBytes(previous));
            dropped.removeAll(exportedModules(file.getValue()));
            if (dropped.isEmpty()) {
                continue;
            }
            String exportsDirectory = path.substring(0, exports + EXPORTS_DIRECTORY.length() + 1);
            try (Stream<Path> records = Files.walk(root.resolve(exportsDirectory))) {
                for (Path record : records.filter(Files::isRegularFile).toList()) {
                    if (!rendered.files().containsKey(slash(root.relativize(record)))) {
                        dropped.removeAll(exportedModules(Files.readAllBytes(record)));
                    }
                }
            }
            rendered.files().entrySet().stream()
                .filter(other -> other.getKey().startsWith(exportsDirectory + "/"))
                .forEach(other -> dropped.removeAll(exportedModules(other.getValue())));
            String[] owner = path.split("/");
            for (List<String> module : dropped) {
                WorkspacePath index = WorkspacePath.moduleIndex(new GroupId(owner[1]),
                    NameCodec.decode(owner[2]), module.get(1), module.get(0));
                orphans.add(root.resolve(index.toRelativePath()).getParent());
                orphans.add(root.resolve(index.metaPath()).getParent());
            }
        }
        return orphans;
    }

    /** The modules (name, plugin type) an export record names. */
    private static Set<List<String>> exportedModules(byte[] record) {
        Map<String, Object> values = MetaStore.deserialize(record);
        Set<List<String>> modules = new HashSet<>();
        if (values.get("modules") instanceof List<?> list) {
            for (Object module : list) {
                if (module instanceof Map<?, ?> map) {
                    modules.add(List.of(String.valueOf(map.get("name")),
                        String.valueOf(map.get("pluginType"))));
                }
            }
        } else if (values.containsKey("module")) {
            modules.add(List.of(String.valueOf(values.get("module")),
                String.valueOf(values.get("pluginType"))));
        }
        return modules;
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static boolean isEmptyDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }
}
